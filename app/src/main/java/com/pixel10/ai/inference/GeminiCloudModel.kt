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

    override fun close() {
        // No resources to clean up
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
        private const val BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash"
        private const val STREAMING_URL =
            "$BASE_URL:streamGenerateContent?alt=sse"
    }
}
