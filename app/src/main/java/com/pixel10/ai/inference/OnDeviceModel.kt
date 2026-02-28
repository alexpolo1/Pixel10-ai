package com.pixel10.ai.inference

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Unified interface for on-device AI inference on the Pixel 10.
 *
 * Supports two backends:
 *  1. **Gemini Nano** via ML Kit Prompt API — uses the system-managed model
 *     through AICore, accelerated by the Tensor G5 TPU. Zero setup needed
 *     on supported Pixel devices.
 *  2. **MediaPipe LLM** — for custom open-weight models (Gemma 2B/3n, etc.)
 *     that you supply yourself. Place the .bin/.task file in the app's
 *     files directory.
 *
 * The factory method tries Gemini Nano first (preferred), then falls back
 * to MediaPipe if a local model file is found.
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

    fun close()

    companion object {
        private const val TAG = "OnDeviceModel"

        /**
         * Create the best available on-device model.
         * Tries Gemini Nano (AICore) first, falls back to MediaPipe.
         */
        suspend fun create(context: Context): OnDeviceModel = withContext(Dispatchers.IO) {
            // Try Gemini Nano via ML Kit Prompt API first
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
                "Option 1: Use a Pixel device with Gemini Nano support " +
                "(Pixel 10/9/8 series)\n\n" +
                "Option 2: Place a MediaPipe-compatible model (.bin or .task) in:\n" +
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
