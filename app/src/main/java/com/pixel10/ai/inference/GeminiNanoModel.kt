package com.pixel10.ai.inference

import android.content.Context
import android.util.Log
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.type.Content
import com.google.mlkit.genai.prompt.type.TextPart
import com.google.mlkit.genai.prompt.type.content
import com.google.mlkit.genai.prompt.type.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Gemini Nano backend via ML Kit Prompt API.
 *
 * This runs the system-provided Gemini Nano model on the Pixel 10's Tensor G5
 * TPU through Android's AICore service. The model is managed by the OS —
 * no manual download or file management needed.
 *
 * Key advantages:
 *  - Hardware-accelerated on Tensor G5 TPU (2.6x faster than G4)
 *  - 32,000 token context window on Pixel 10
 *  - ~3 GB model always resident in RAM for instant inference
 *  - Fully offline, private — data never leaves the device
 */
class GeminiNanoModel private constructor(
    private val generativeModel: GenerativeModel
) : OnDeviceModel {

    override val backendName = "Gemini Nano (ML Kit)"

    @Volatile
    override var isReady: Boolean = true
        private set

    override suspend fun generate(
        prompt: String,
        maxTokens: Int,
        temperature: Float
    ): String = withContext(Dispatchers.Default) {
        try {
            val request = content { text(prompt) }
            val response = generativeModel.generateContent(request)
            response.text ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Gemini Nano generation error", e)
            throw OnDeviceModel.InferenceException("Gemini Nano generation failed: ${e.message}", e)
        }
    }

    override suspend fun generateStreaming(
        prompt: String,
        onToken: (String) -> Unit
    ): String = withContext(Dispatchers.Default) {
        try {
            val request = content { text(prompt) }
            generativeModel.generateContentStream(request)
                .fold("") { acc, response ->
                    val chunk = response.text ?: ""
                    if (chunk.isNotEmpty()) onToken(chunk)
                    acc + chunk
                }
        } catch (e: Exception) {
            Log.e(TAG, "Gemini Nano streaming error", e)
            throw OnDeviceModel.InferenceException("Streaming failed: ${e.message}", e)
        }
    }

    override fun close() {
        isReady = false
        generativeModel.close()
    }

    companion object {
        private const val TAG = "GeminiNanoModel"

        suspend fun create(context: Context): GeminiNanoModel = withContext(Dispatchers.IO) {
            // Check if Gemini Nano is available on this device
            val model = GenerativeModel.newBuilder()
                .setContext(context)
                .build()

            // Verify feature is available — will throw if not supported
            suspendCancellableCoroutine { continuation ->
                model.isAvailable()
                    .addOnSuccessListener { available ->
                        if (available) {
                            continuation.resume(Unit)
                        } else {
                            continuation.resumeWithException(
                                OnDeviceModel.InferenceException(
                                    "Gemini Nano is not available on this device"
                                )
                            )
                        }
                    }
                    .addOnFailureListener { e ->
                        continuation.resumeWithException(
                            OnDeviceModel.InferenceException(
                                "Failed to check Gemini Nano availability: ${e.message}", e
                            )
                        )
                    }
            }

            // Trigger model download if needed
            suspendCancellableCoroutine { continuation ->
                model.downloadModel()
                    .addOnSuccessListener { continuation.resume(Unit) }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "Model download issue (may already be available): ${e.message}")
                        // Don't fail — model might already be cached
                        continuation.resume(Unit)
                    }
            }

            Log.i(TAG, "Gemini Nano model ready via ML Kit Prompt API")
            GeminiNanoModel(model)
        }
    }
}
