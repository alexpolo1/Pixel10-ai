package com.pixel10.ai.inference

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Unified interface for on-device AI inference on the Pixel 10's Tensor chip.
 *
 * Backends (tried in order):
 *  1. **Gemini Nano** via ML Kit Prompt API — system-managed model accelerated
 *     by the Tensor G5 TPU through AICore. Zero setup on supported Pixel devices.
 *  2. **MediaPipe LLM** — for custom open-weight models (Gemma 2B/3n, etc.)
 *     placed in the app's files directory.
 *
 * All inference is fully on-device. No data leaves the phone.
 */
interface OnDeviceModel {
    val backendName: String
    val isReady: Boolean

    suspend fun generate(
        prompt: String,
        maxTokens: Int = 8192,
        temperature: Float = 0.7f
    ): String

    suspend fun generateStreaming(
        prompt: String,
        onToken: (String) -> Unit
    ): String

    /**
     * Multi-turn conversation with optional tool/function calling.
     *
     * Accepts the full message history and an optional list of tools.
     * Returns a [ChatResult] which is either a text reply or a tool invocation.
     *
     * Default implementation flattens the conversation to a prompt and calls
     * [generate], so all backends work transparently.
     */
    suspend fun chat(
        messages: List<ConvMessage>,
        tools: List<ToolDef> = emptyList(),
        maxTokens: Int = 8192,
        temperature: Float = 0.7f
    ): ChatResult {
        val prompt = messages.joinToString("\n") { msg ->
            when (msg.role) {
                "system"    -> "System: ${msg.content.orEmpty()}"
                "user"      -> "User: ${msg.content.orEmpty()}"
                "assistant" -> "Assistant: ${msg.content.orEmpty()}"
                "tool"      -> "Tool result: ${msg.content.orEmpty()}"
                else        -> "${msg.role}: ${msg.content.orEmpty()}"
            }
        } + "\nAssistant:"
        return ChatResult(content = generate(prompt, maxTokens, temperature))
    }

    fun close()

    // ── Supporting types ──────────────────────────────────────────────────────

    /** A single message in a multi-turn conversation passed to [chat]. */
    data class ConvMessage(
        val role: String,
        /** Text content — null when role=assistant and tool_calls is set. */
        val content: String?,
        val toolCalls: List<ToolCallData>? = null,
        /** For role=tool messages: the tool_call id being responded to. */
        val toolCallId: String? = null,
        /** For role=tool messages: the function name. */
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
         * Create the best available on-device model.
         *
         * Priority order:
         *  1. MediaPipe (local model file) — runs in background, uses Tensor G5 GPU.
         *     This is the preferred backend: no foreground restriction, no AICore dep.
         *  2. Gemini Nano (ML Kit) — foreground only (ErrorCode 30 in background).
         *     Used as fallback when no MediaPipe model file is present.
         *
         * Tap "Download Model" in the app UI to get the MediaPipe model automatically.
         */
        suspend fun create(context: Context): OnDeviceModel = withContext(Dispatchers.IO) {
            // LiteRT-LM first — Gemma 3n .litertlm format, GPU-accelerated, background-safe
            try {
                Log.i(TAG, "Attempting LiteRT-LM with local .litertlm model...")
                val litert = LiteRTModel.create(context)
                Log.i(TAG, "LiteRT-LM model ready: ${litert.backendName}")
                return@withContext litert
            } catch (e: Exception) {
                Log.w(TAG, "LiteRT-LM not available: ${e.message}")
            }

            // MediaPipe fallback — .task/.bin format, background-safe
            try {
                Log.i(TAG, "Attempting MediaPipe LLM with local .task model...")
                val mediapipe = MediaPipeModel.create(context)
                Log.i(TAG, "MediaPipe model ready: ${mediapipe.backendName}")
                return@withContext mediapipe
            } catch (e: Exception) {
                Log.w(TAG, "MediaPipe not available: ${e.message}")
            }

            // Gemini Nano last resort — foreground only
            try {
                Log.i(TAG, "Attempting Gemini Nano via ML Kit (foreground only)...")
                val nano = GeminiNanoModel.create(context)
                Log.i(TAG, "Gemini Nano ready (foreground only)")
                return@withContext nano
            } catch (e: Exception) {
                Log.w(TAG, "Gemini Nano not available: ${e.message}")
            }

            throw InferenceException(
                "No model loaded yet.\n\n" +
                "Tap 'Download Model' in the app to download Gemma 3n E4B.\n" +
                "Once downloaded the server works fully in the background.\n\n" +
                "Or place a .litertlm file in:\n" +
                "  ${context.filesDir.absolutePath}/"
            )
        }
    }

    class InferenceException(message: String, cause: Throwable? = null) :
        Exception(message, cause)
}
