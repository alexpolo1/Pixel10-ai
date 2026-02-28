package com.pixel10.ai.inference

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Unified interface for on-device AI inference on the Pixel 10.
 *
 * Supports three backends, tried in order:
 *  1. **Gemini Nano** via ML Kit Prompt API — uses the system-managed model
 *     through AICore, accelerated by the Tensor G5 TPU. Zero setup needed
 *     on supported Pixel devices. Requires foreground context.
 *  2. **Gemini Cloud** — proxies to Gemini 2.0 Flash via HTTPS. Works from
 *     any context (background, emulator). Requires an API key.
 *  3. **MediaPipe LLM** — for custom open-weight models (Gemma 2B/3n, etc.)
 *     that you supply yourself. Place the .bin/.task file in the app's
 *     files directory.
 *
 * Pass [apiKey] to enable the cloud backend. If an API key is provided,
 * cloud is preferred over Nano to guarantee background operation.
 */
interface OnDeviceModel {
    val backendName: String
    val isReady: Boolean

    suspend fun generate(
        prompt: String,
        maxTokens: Int = 1024,
        temperature: Float = 0.7f
    ): String

    suspend fun generateStreaming(
        prompt: String,
        onToken: (String) -> Unit
    ): String

    /**
     * Generate with extended thinking. Returns a [ThinkingResult] containing
     * the model's reasoning trace and its final answer separately.
     *
     * The default implementation delegates to [generate] with an empty thinking trace,
     * so backends that don't support thinking still work transparently.
     */
    suspend fun generateWithThinking(
        prompt: String,
        maxTokens: Int = 16384,
        thinkingBudget: Int = 8192
    ): ThinkingResult = ThinkingResult(thinking = "", response = generate(prompt, maxTokens))

    /**
     * Multi-turn conversation with optional tool/function calling.
     *
     * Accepts the full message history so the model can reference prior turns,
     * and an optional list of tool definitions the model may invoke.
     *
     * Returns a [ChatResult] which is either:
     *  - A text reply ([ChatResult.content] set, [ChatResult.toolCalls] null)
     *  - A tool invocation ([ChatResult.toolCalls] set, [ChatResult.content] null)
     *
     * The default implementation flattens the conversation to a plain prompt
     * and calls [generate], so backends without native tool support still work
     * (they just won't invoke tools).
     */
    suspend fun chat(
        messages: List<ConvMessage>,
        tools: List<ToolDef> = emptyList(),
        maxTokens: Int = 8192,
        temperature: Float = 0.7f
    ): ChatResult {
        val prompt = messages.joinToString("\n") { msg ->
            when (msg.role) {
                "system" -> "System: ${msg.content.orEmpty()}"
                "user" -> "User: ${msg.content.orEmpty()}"
                "assistant" -> "Assistant: ${msg.content.orEmpty()}"
                "tool" -> "Tool result: ${msg.content.orEmpty()}"
                else -> "${msg.role}: ${msg.content.orEmpty()}"
            }
        } + "\nAssistant:"
        return ChatResult(content = generate(prompt, maxTokens, temperature))
    }

    fun close()

    // ── Supporting types ──────────────────────────────────────────────────────

    data class ThinkingResult(
        /** The model's internal reasoning trace (may be empty for non-thinking backends). */
        val thinking: String,
        /** The final answer shown to the user. */
        val response: String
    )

    /** A single message in a multi-turn conversation passed to [chat]. */
    data class ConvMessage(
        val role: String,
        /** Text content — null when role=assistant and tool_calls is set. */
        val content: String?,
        val toolCalls: List<ToolCallData>? = null,
        /** For role=tool messages: the tool_call id being responded to. */
        val toolCallId: String? = null,
        /** For role=tool messages: the function name (needed by Gemini). */
        val toolName: String? = null
    )

    /** A tool/function definition passed to [chat]. */
    data class ToolDef(
        val name: String,
        val description: String,
        /** JSON Schema for the function parameters, as a raw JSON string. */
        val parametersJson: String?
    )

    /** A tool call the model wants to make. */
    data class ToolCallData(
        val id: String,
        val name: String,
        /** Arguments as a JSON-encoded string. */
        val argsJson: String
    )

    /** Result from [chat]. Exactly one of content/toolCalls will be non-null. */
    data class ChatResult(
        val content: String? = null,
        val toolCalls: List<ToolCallData>? = null,
        val finishReason: String = if (toolCalls != null) "tool_calls" else "stop"
    )

    companion object {
        private const val TAG = "OnDeviceModel"

        /**
         * Create the best available model.
         *
         * Fallback chain:
         *  1. GeminiNano  — on-device, best quality, foreground only
         *  2. GeminiCloud — network, always works (background + CI); needs [apiKey]
         *  3. MediaPipe   — local model file, fully offline
         *
         * If [apiKey] is non-empty, cloud is tried *before* Nano so the server
         * stays responsive after the user navigates away from the app.
         */
        suspend fun create(context: Context, apiKey: String = ""): OnDeviceModel =
            withContext(Dispatchers.IO) {
                val hasKey = apiKey.isNotBlank()

                // Prefer cloud when an API key is available — guarantees background operation
                if (hasKey) {
                    Log.i(TAG, "API key set — using Gemini Cloud for background-safe inference")
                    return@withContext GeminiCloudModel(apiKey)
                }

                // Try Gemini Nano via ML Kit Prompt API
                try {
                    Log.i(TAG, "Attempting Gemini Nano via ML Kit Prompt API...")
                    val nano = GeminiNanoModel.create(context)
                    Log.i(TAG, "Gemini Nano ready!")
                    return@withContext nano
                } catch (e: Exception) {
                    Log.w(TAG, "Gemini Nano not available: ${e.message}")
                }

                // Fall back to MediaPipe with a local model file
                try {
                    Log.i(TAG, "Attempting MediaPipe LLM with local model...")
                    val mediapipe = MediaPipeModel.create(context)
                    Log.i(TAG, "MediaPipe model ready!")
                    return@withContext mediapipe
                } catch (e: Exception) {
                    Log.w(TAG, "MediaPipe model not available: ${e.message}")
                }

                throw InferenceException(
                    "No AI model available.\n\n" +
                    "Option 1: Enter a Gemini API key in the app (works everywhere)\n\n" +
                    "Option 2: Use a Pixel device with Gemini Nano support " +
                    "(Pixel 10/9/8 series)\n\n" +
                    "Option 3: Place a MediaPipe-compatible model (.bin or .task) in:\n" +
                    "  ${context.filesDir.absolutePath}/\n" +
                    "  Supported: gemma-3n-E2B.task, gemma-2b-it-gpu-int4.bin, etc.\n\n" +
                    "Download models from:\n" +
                    "  https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference/android"
                )
            }
    }

    class InferenceException(message: String, cause: Throwable? = null) :
        Exception(message, cause)
}
