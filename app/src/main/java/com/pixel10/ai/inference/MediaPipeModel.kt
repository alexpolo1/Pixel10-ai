package com.pixel10.ai.inference

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * MediaPipe LLM Inference backend for custom open-weight models.
 *
 * Use this to run models like Gemma 3n E2B, Gemma 2B, or other compatible
 * LLMs that you download and place on the device yourself.
 *
 * On Pixel 10, MediaPipe automatically leverages the Tensor G5 GPU/NPU
 * for accelerated inference.
 *
 * To use:
 *  1. Download a compatible model (e.g. gemma-3n-E2B.task)
 *  2. Push to device: adb push model.task /data/local/tmp/llm/
 *     or copy to app files dir via the app
 */
class MediaPipeModel private constructor(
    private val llmInference: LlmInference,
    private val modelName: String
) : OnDeviceModel {

    override val backendName = "MediaPipe ($modelName)"

    @Volatile
    override var isReady: Boolean = true
        private set

    override suspend fun generate(
        prompt: String,
        maxTokens: Int,
        temperature: Float
    ): String = withContext(Dispatchers.Default) {
        try {
            llmInference.generateResponse(prompt)
        } catch (e: Exception) {
            Log.e(TAG, "MediaPipe inference error", e)
            throw OnDeviceModel.InferenceException("Generation failed: ${e.message}", e)
        }
    }

    override suspend fun generateStreaming(
        prompt: String,
        onToken: (String) -> Unit
    ): String = withContext(Dispatchers.Default) {
        suspendCancellableCoroutine { continuation ->
            val fullResponse = StringBuilder()
            try {
                llmInference.generateResponseAsync(prompt).addResultListener { partialResult, done ->
                    val chunk = partialResult ?: ""
                    fullResponse.append(chunk)
                    onToken(chunk)
                    if (done) {
                        continuation.resume(fullResponse.toString())
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Streaming error", e)
                continuation.resumeWithException(
                    OnDeviceModel.InferenceException("Streaming failed: ${e.message}", e)
                )
            }
        }
    }

    override fun close() {
        isReady = false
        llmInference.close()
    }

    companion object {
        private const val TAG = "MediaPipeModel"

        private val MODEL_FILENAMES = listOf(
            "gemma-3n-E2B.task",
            "gemma-3n-E4B.task",
            "gemma-2b-it-gpu-int4.bin",
            "gemini-nano.bin",
            "model.bin"
        )

        suspend fun create(context: Context): MediaPipeModel = withContext(Dispatchers.IO) {
            val modelPath = findModelPath(context)
                ?: throw OnDeviceModel.InferenceException(
                    "No MediaPipe model file found.\n" +
                    "Place a compatible .bin or .task file in:\n" +
                    "  ${context.filesDir.absolutePath}/\n" +
                    "Supported: ${MODEL_FILENAMES.joinToString()}"
                )

            val modelName = File(modelPath).name
            Log.i(TAG, "Loading MediaPipe model: $modelPath")

            try {
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelPath)
                    .setMaxTokens(2048)
                    .setTopK(40)
                    .setTemperature(0.7f)
                    .setRandomSeed(42)
                    .build()

                val inference = LlmInference.createFromOptions(context, options)
                Log.i(TAG, "MediaPipe model loaded: $modelName")
                MediaPipeModel(inference, modelName)
            } catch (e: Exception) {
                throw OnDeviceModel.InferenceException(
                    "Failed to load MediaPipe model from $modelPath: ${e.message}", e
                )
            }
        }

        private fun findModelPath(context: Context): String? {
            // Search standard locations
            val searchDirs = listOfNotNull(
                context.filesDir,
                File(context.filesDir, "models"),
                context.getExternalFilesDir(null),
                File("/data/local/tmp/llm")
            )

            for (dir in searchDirs) {
                if (!dir.exists()) continue
                for (name in MODEL_FILENAMES) {
                    val file = File(dir, name)
                    if (file.exists()) {
                        Log.i(TAG, "Found model: ${file.absolutePath}")
                        return file.absolutePath
                    }
                }
                // Also check for any .task or .bin file
                dir.listFiles()?.firstOrNull {
                    it.extension in listOf("task", "bin", "tflite")
                }?.let { return it.absolutePath }
            }

            return null
        }
    }
}
