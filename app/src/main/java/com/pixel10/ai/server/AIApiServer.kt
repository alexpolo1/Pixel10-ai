package com.pixel10.ai.server

import android.os.Build
import android.util.Log
import com.google.gson.Gson
import com.pixel10.ai.inference.OnDeviceModel
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Embedded HTTP server that exposes the on-device AI model as a REST API.
 *
 * Provides OpenAI-compatible endpoints so existing tools (curl, Python openai
 * library, etc.) can talk to this phone as if it were a cloud AI endpoint.
 *
 * Usage from any device on the same network:
 *   curl http://<phone-ip>:8080/v1/chat/completions \
 *     -H "Content-Type: application/json" \
 *     -d '{"messages":[{"role":"user","content":"Hello!"}]}'
 */
class AIApiServer(
    port: Int,
    private val model: OnDeviceModel
) : NanoHTTPD(port) {

    private val gson = Gson()
    private val startTime = System.currentTimeMillis()
    val requestCount = AtomicLong(0)

    var onRequestLogged: ((String) -> Unit)? = null

    override fun serve(session: IHTTPSession): Response {
        val method = session.method
        val uri = session.uri
        val count = requestCount.incrementAndGet()

        log("[$count] ${method.name} $uri")

        return try {
            // Add CORS headers to all responses
            when {
                method == Method.OPTIONS -> corsPreflightResponse()
                uri == "/" || uri == "/health" -> handleHealth()
                uri == "/v1/models" && method == Method.GET -> handleModels()
                uri == "/v1/chat/completions" && method == Method.POST -> handleChatCompletions(session)
                uri == "/v1/completions" && method == Method.POST -> handleCompletions(session)
                else -> errorResponse(404, "Not found: $uri")
            }.also { addCorsHeaders(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Request error", e)
            log("ERROR: ${e.message}")
            errorResponse(500, "Internal server error: ${e.message}")
                .also { addCorsHeaders(it) }
        }
    }

    // ── Endpoint Handlers ──────────────────────────────────────────────

    private fun handleHealth(): Response {
        val status = ServerStatus(
            status = if (model.isReady) "ready" else "model_not_loaded",
            model = model.backendName,
            device = "${Build.MANUFACTURER} ${Build.MODEL} (${Build.SOC_MODEL})",
            uptime_seconds = (System.currentTimeMillis() - startTime) / 1000,
            requests_served = requestCount.get()
        )
        return jsonResponse(200, gson.toJson(status))
    }

    private fun handleModels(): Response {
        return jsonResponse(200, gson.toJson(ModelList()))
    }

    private fun handleChatCompletions(session: IHTTPSession): Response {
        val body = readBody(session)
        val request = gson.fromJson(body, ChatRequest::class.java)

        if (request.messages.isEmpty()) {
            return errorResponse(400, "messages array is required and must not be empty")
        }

        // Build a prompt from the chat messages
        val prompt = buildChatPrompt(request.messages)

        log("Chat prompt (${request.messages.size} messages, ${prompt.length} chars)")

        if (request.stream) {
            return handleStreamingResponse(prompt, request)
        }

        // Synchronous generation
        val responseText = runBlocking {
            model.generate(prompt, request.max_tokens, request.temperature)
        }

        log("Response: ${responseText.take(80)}...")

        val chatResponse = ChatResponse(
            id = "chatcmpl-${UUID.randomUUID().toString().take(8)}",
            choices = listOf(
                Choice(
                    message = Message(role = "assistant", content = responseText)
                )
            ),
            usage = Usage(
                prompt_tokens = estimateTokens(prompt),
                completion_tokens = estimateTokens(responseText),
                total_tokens = estimateTokens(prompt) + estimateTokens(responseText)
            )
        )

        return jsonResponse(200, gson.toJson(chatResponse))
    }

    private fun handleCompletions(session: IHTTPSession): Response {
        val body = readBody(session)
        val request = gson.fromJson(body, ChatRequest::class.java)

        val prompt = request.prompt
            ?: request.messages.lastOrNull()?.content
            ?: return errorResponse(400, "prompt or messages is required")

        log("Completion prompt (${prompt.length} chars)")

        val responseText = runBlocking {
            model.generate(prompt, request.max_tokens, request.temperature)
        }

        log("Response: ${responseText.take(80)}...")

        val chatResponse = ChatResponse(
            id = "cmpl-${UUID.randomUUID().toString().take(8)}",
            choices = listOf(
                Choice(
                    message = Message(role = "assistant", content = responseText)
                )
            ),
            usage = Usage(
                prompt_tokens = estimateTokens(prompt),
                completion_tokens = estimateTokens(responseText),
                total_tokens = estimateTokens(prompt) + estimateTokens(responseText)
            )
        )

        return jsonResponse(200, gson.toJson(chatResponse))
    }

    private fun handleStreamingResponse(prompt: String, request: ChatRequest): Response {
        val id = "chatcmpl-${UUID.randomUUID().toString().take(8)}"

        // For streaming, collect all tokens then return as SSE-formatted response.
        // NanoHTTPD doesn't natively support chunked streaming in a clean way,
        // so we buffer and return the full SSE payload.
        val sseBuilder = StringBuilder()

        // Initial role chunk
        val roleChunk = StreamChunk(
            id = id,
            choices = listOf(StreamChoice(delta = Delta(role = "assistant")))
        )
        sseBuilder.append("data: ${gson.toJson(roleChunk)}\n\n")

        val fullResponse = runBlocking {
            model.generateStreaming(prompt) { token ->
                val chunk = StreamChunk(
                    id = id,
                    choices = listOf(StreamChoice(delta = Delta(content = token)))
                )
                sseBuilder.append("data: ${gson.toJson(chunk)}\n\n")
            }
        }

        // Final done chunk
        val doneChunk = StreamChunk(
            id = id,
            choices = listOf(StreamChoice(delta = Delta(), finish_reason = "stop"))
        )
        sseBuilder.append("data: ${gson.toJson(doneChunk)}\n\n")
        sseBuilder.append("data: [DONE]\n\n")

        log("Streamed response: ${fullResponse.take(80)}...")

        return newFixedLengthResponse(
            Response.Status.OK,
            "text/event-stream",
            sseBuilder.toString()
        )
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun buildChatPrompt(messages: List<Message>): String {
        val sb = StringBuilder()
        for (msg in messages) {
            when (msg.role) {
                "system" -> sb.append("System: ${msg.content}\n\n")
                "user" -> sb.append("User: ${msg.content}\n\n")
                "assistant" -> sb.append("Assistant: ${msg.content}\n\n")
            }
        }
        sb.append("Assistant: ")
        return sb.toString()
    }

    private fun readBody(session: IHTTPSession): String {
        val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
        val buffer = ByteArray(contentLength)
        session.inputStream.read(buffer, 0, contentLength)
        return String(buffer)
    }

    private fun estimateTokens(text: String): Int {
        // Rough estimate: ~4 characters per token
        return (text.length / 4).coerceAtLeast(1)
    }

    private fun jsonResponse(statusCode: Int, json: String): Response {
        val status = when (statusCode) {
            200 -> Response.Status.OK
            400 -> Response.Status.BAD_REQUEST
            404 -> Response.Status.NOT_FOUND
            else -> Response.Status.INTERNAL_ERROR
        }
        return newFixedLengthResponse(status, "application/json", json)
    }

    private fun errorResponse(statusCode: Int, message: String): Response {
        val error = ErrorResponse(
            ErrorDetail(message = message, code = statusCode)
        )
        return jsonResponse(statusCode, gson.toJson(error))
    }

    private fun corsPreflightResponse(): Response {
        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
    }

    private fun addCorsHeaders(response: Response) {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        onRequestLogged?.invoke(message)
    }

    companion object {
        private const val TAG = "AIApiServer"
    }
}
