package com.pixel10.ai.inference

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cloud backend that proxies inference requests to the Gemini 2.0 Flash API.
 *
 * Works from any context (foreground service, background) because it uses
 * standard HTTPS rather than the AICore system service. This is the fallback
 * when Gemini Nano is unavailable (emulator, background inference blocked, etc.).
 *
 * Requires a Gemini API key (free tier available at ai.google.dev).
 */
class GeminiCloudModel(private val apiKey: String) : OnDeviceModel {

    override val backendName = "Gemini 2.0 Flash (Cloud)"

    override val isReady: Boolean = true

    override suspend fun generate(
        prompt: String,
        maxTokens: Int,
        temperature: Float
    ): String = withContext(Dispatchers.IO) {
        val url = URL("$BASE_URL:generateContent?key=$apiKey")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000

            val body = buildRequestBody(prompt, maxTokens, temperature)
            connection.outputStream.use { it.write(body.toByteArray()) }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val error = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                throw OnDeviceModel.InferenceException("Gemini API error $responseCode: $error")
            }

            val responseText = connection.inputStream.bufferedReader().readText()
            parseGenerateResponse(responseText)
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun generateStreaming(
        prompt: String,
        onToken: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val url = URL("$STREAMING_URL?key=$apiKey")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "text/event-stream")
            connection.doOutput = true
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000

            val body = buildRequestBody(prompt, maxTokens = 1024, temperature = 0.7f)
            connection.outputStream.use { it.write(body.toByteArray()) }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val error = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                throw OnDeviceModel.InferenceException("Gemini streaming API error $responseCode: $error")
            }

            val fullText = StringBuilder()
            BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line!!
                    if (!l.startsWith("data: ")) continue
                    val data = l.removePrefix("data: ").trim()
                    if (data == "[DONE]") break
                    try {
                        val token = parseChunkText(data)
                        if (token.isNotEmpty()) {
                            onToken(token)
                            fullText.append(token)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse SSE chunk: $data", e)
                    }
                }
            }

            fullText.toString()
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun chat(
        messages: List<OnDeviceModel.ConvMessage>,
        tools: List<OnDeviceModel.ToolDef>,
        maxTokens: Int,
        temperature: Float
    ): OnDeviceModel.ChatResult = withContext(Dispatchers.IO) {
        val url = URL("$BASE_URL:generateContent?key=$apiKey")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000

            val body = buildChatRequestBody(messages, tools, maxTokens, temperature)
            connection.outputStream.use { it.write(body.toByteArray()) }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val error = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                throw OnDeviceModel.InferenceException("Gemini chat API error $responseCode: $error")
            }

            val responseText = connection.inputStream.bufferedReader().readText()
            parseChatResponse(responseText)
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun generateWithThinking(
        prompt: String,
        maxTokens: Int,
        thinkingBudget: Int
    ): OnDeviceModel.ThinkingResult = withContext(Dispatchers.IO) {
        val url = URL("$THINKING_URL?key=$apiKey")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000

            val body = buildThinkingRequestBody(prompt, maxTokens, thinkingBudget)
            connection.outputStream.use { it.write(body.toByteArray()) }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val error = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                throw OnDeviceModel.InferenceException("Gemini thinking API error $responseCode: $error")
            }

            val responseText = connection.inputStream.bufferedReader().readText()
            parseThinkingResponse(responseText)
        } finally {
            connection.disconnect()
        }
    }

    override fun close() {
        // No resources to clean up
    }

    // ── Chat / Tool-calling helpers ───────────────────────────────────────────

    private fun buildChatRequestBody(
        messages: List<OnDeviceModel.ConvMessage>,
        tools: List<OnDeviceModel.ToolDef>,
        maxTokens: Int,
        temperature: Float
    ): String {
        // Build a map from tool_call_id → function name so we can label tool results
        val toolCallIdToName = mutableMapOf<String, String>()
        for (msg in messages) {
            msg.toolCalls?.forEach { tc -> toolCallIdToName[tc.id] = tc.name }
        }

        return JSONObject().apply {
            // System instruction (Gemini uses a dedicated field, not a role)
            val systemMsg = messages.firstOrNull { it.role == "system" }
            if (systemMsg?.content != null) {
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", systemMsg.content) })
                    })
                })
            }

            // Conversation turns (skip system — handled above)
            put("contents", JSONArray().apply {
                for (msg in messages) {
                    when (msg.role) {
                        "system" -> { /* handled via systemInstruction */ }

                        "user" -> put(JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply { put("text", msg.content ?: "") })
                            })
                        })

                        "assistant" -> {
                            if (msg.toolCalls != null) {
                                // Model requested tool calls
                                put(JSONObject().apply {
                                    put("role", "model")
                                    put("parts", JSONArray().apply {
                                        for (tc in msg.toolCalls) {
                                            put(JSONObject().apply {
                                                put("functionCall", JSONObject().apply {
                                                    put("name", tc.name)
                                                    put("args", safeJsonObject(tc.argsJson))
                                                })
                                            })
                                        }
                                    })
                                })
                            } else {
                                put(JSONObject().apply {
                                    put("role", "model")
                                    put("parts", JSONArray().apply {
                                        put(JSONObject().apply { put("text", msg.content ?: "") })
                                    })
                                })
                            }
                        }

                        "tool" -> {
                            // Tool result — Gemini expects a user-role functionResponse
                            val fnName = msg.toolName
                                ?: toolCallIdToName[msg.toolCallId]
                                ?: "unknown"
                            put(JSONObject().apply {
                                put("role", "user")
                                put("parts", JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("functionResponse", JSONObject().apply {
                                            put("name", fnName)
                                            put("response", JSONObject().apply {
                                                put("result", msg.content ?: "")
                                            })
                                        })
                                    })
                                })
                            })
                        }
                    }
                }
            })

            // Tool declarations
            if (tools.isNotEmpty()) {
                put("tools", JSONArray().apply {
                    put(JSONObject().apply {
                        put("functionDeclarations", JSONArray().apply {
                            for (tool in tools) {
                                put(JSONObject().apply {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    if (tool.parametersJson != null) {
                                        put("parameters", safeJsonObject(tool.parametersJson))
                                    }
                                })
                            }
                        })
                    })
                })
                put("toolConfig", JSONObject().apply {
                    put("functionCallingConfig", JSONObject().apply {
                        put("mode", "AUTO")
                    })
                })
            }

            put("generationConfig", JSONObject().apply {
                put("maxOutputTokens", maxTokens)
                put("temperature", temperature.toDouble())
            })
        }.toString()
    }

    private fun parseChatResponse(json: String): OnDeviceModel.ChatResult {
        return try {
            val candidate = JSONObject(json)
                .getJSONArray("candidates")
                .getJSONObject(0)
            val content = candidate.getJSONObject("content")
            val parts = content.getJSONArray("parts")
            val finishReason = candidate.optString("finishReason", "STOP")

            // Check if any part is a function call
            val toolCalls = mutableListOf<OnDeviceModel.ToolCallData>()
            val textBuilder = StringBuilder()

            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                when {
                    part.has("functionCall") -> {
                        val fc = part.getJSONObject("functionCall")
                        toolCalls += OnDeviceModel.ToolCallData(
                            id = "call_${System.currentTimeMillis()}_$i",
                            name = fc.getString("name"),
                            argsJson = fc.optJSONObject("args")?.toString() ?: "{}"
                        )
                    }
                    part.has("text") -> textBuilder.append(part.getString("text"))
                }
            }

            when {
                toolCalls.isNotEmpty() -> OnDeviceModel.ChatResult(
                    toolCalls = toolCalls,
                    finishReason = "tool_calls"
                )
                else -> OnDeviceModel.ChatResult(
                    content = textBuilder.toString(),
                    finishReason = if (finishReason == "MAX_TOKENS") "length" else "stop"
                )
            }
        } catch (e: Exception) {
            throw OnDeviceModel.InferenceException("Failed to parse Gemini chat response: ${e.message}", e)
        }
    }

    /** Parse a JSON string into a JSONObject, returning an empty object on failure. */
    private fun safeJsonObject(json: String): JSONObject = try {
        JSONObject(json)
    } catch (_: Exception) {
        JSONObject()
    }

    private fun buildThinkingRequestBody(prompt: String, maxTokens: Int, thinkingBudget: Int): String {
        return JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", prompt) })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("maxOutputTokens", maxTokens)
                put("thinkingConfig", JSONObject().apply {
                    put("thinkingBudget", thinkingBudget)
                })
            })
        }.toString()
    }

    private fun parseThinkingResponse(json: String): OnDeviceModel.ThinkingResult {
        return try {
            val parts = JSONObject(json)
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")

            val thinkingBuilder = StringBuilder()
            val responseBuilder = StringBuilder()

            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                val text = part.optString("text", "")
                if (part.optBoolean("thought", false)) {
                    thinkingBuilder.append(text)
                } else {
                    responseBuilder.append(text)
                }
            }

            OnDeviceModel.ThinkingResult(
                thinking = thinkingBuilder.toString(),
                response = responseBuilder.toString()
            )
        } catch (e: Exception) {
            throw OnDeviceModel.InferenceException(
                "Failed to parse Gemini thinking response: ${e.message}", e
            )
        }
    }

    private fun buildRequestBody(prompt: String, maxTokens: Int, temperature: Float): String {
        return JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", prompt)
                        })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("maxOutputTokens", maxTokens)
                put("temperature", temperature.toDouble())
            })
        }.toString()
    }

    private fun parseGenerateResponse(json: String): String {
        return try {
            JSONObject(json)
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
        } catch (e: Exception) {
            throw OnDeviceModel.InferenceException("Failed to parse Gemini response: ${e.message}", e)
        }
    }

    private fun parseChunkText(json: String): String {
        return try {
            JSONObject(json)
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .optString("text", "")
        } catch (_: Exception) {
            ""
        }
    }

    companion object {
        private const val TAG = "GeminiCloudModel"
        private const val API_ROOT = "https://generativelanguage.googleapis.com/v1beta/models"

        // Fast model — low latency, no reasoning trace
        private const val FAST_MODEL = "gemini-2.0-flash"
        private const val BASE_URL = "$API_ROOT/$FAST_MODEL"
        private const val STREAMING_URL = "$BASE_URL:streamGenerateContent?alt=sse"

        // Thinking model — step-by-step reasoning before answering
        private const val THINKING_MODEL = "gemini-2.5-flash-preview-04-17"
        private const val THINKING_URL = "$API_ROOT/$THINKING_MODEL:generateContent"
    }
}
