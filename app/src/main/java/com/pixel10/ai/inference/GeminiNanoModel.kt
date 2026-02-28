package com.pixel10.ai.inference

import android.content.Context
import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.withContext

/**
 * Gemini Nano backend via ML Kit Prompt API.
 *
 * This runs the system-provided Gemini Nano model on the Pixel 10's Tensor G5
 * TPU through Android's AICore service. The model is managed by the OS —
 * no manual download or file management needed.
 *
 * Key advantages:
 *  - Hardware-accelerated on Tensor G5 TPU
 *  - Fully offline, private — data never leaves the device
 *  - System-managed model, no manual downloads
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
            val request = generateContentRequest(TextPart(prompt)) {
                this.temperature = temperature
                this.topK = 40
            }
            val response = generativeModel.generateContent(request)
            response.candidates.firstOrNull()?.text ?: ""
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
            generativeModel.generateContentStream(prompt)
                .fold("") { acc, chunk ->
                    val text = chunk.candidates.firstOrNull()?.text ?: ""
                    if (text.isNotEmpty()) onToken(text)
                    acc + text
                }
        } catch (e: Exception) {
            Log.e(TAG, "Gemini Nano streaming error", e)
            throw OnDeviceModel.InferenceException("Streaming failed: ${e.message}", e)
        }
    }

    override fun close() {
        isReady = false
        // GenerativeModel from Generation.getClient() is system-managed
    }

    companion object {
        private const val TAG = "GeminiNanoModel"

        suspend fun create(context: Context): GeminiNanoModel = withContext(Dispatchers.IO) {
            val model = Generation.getClient()

            // Check if Gemini Nano is available on this device
            val status = model.checkStatus()
            when (status) {
                FeatureStatus.UNAVAILABLE -> {
                    throw OnDeviceModel.InferenceException(
                        "Gemini Nano is not available on this device"
                    )
                }
                FeatureStatus.DOWNLOADABLE -> {
                    Log.i(TAG, "Downloading Gemini Nano model...")
                    model.download().collect { downloadStatus ->
                        when (downloadStatus) {
                            is DownloadStatus.DownloadStarted ->
                                Log.i(TAG, "Model download started")
                            is DownloadStatus.DownloadProgress ->
                                Log.i(TAG, "Download in progress...")
                            DownloadStatus.DownloadCompleted ->
                                Log.i(TAG, "Model download completed")
                            is DownloadStatus.DownloadFailed ->
                                throw OnDeviceModel.InferenceException("Model download failed")
                        }
                    }
                }
                FeatureStatus.DOWNLOADING -> {
                    Log.i(TAG, "Model already downloading, waiting...")
                    model.download().collect { downloadStatus ->
                        if (downloadStatus == DownloadStatus.DownloadCompleted) {
                            Log.i(TAG, "Download completed")
                        }
                    }
                }
                FeatureStatus.AVAILABLE -> {
                    Log.i(TAG, "Gemini Nano is available")
                }
            }

            // Warm up for lower first-inference latency
            try {
                model.warmup()
                Log.i(TAG, "Model warmup complete")
            } catch (e: Exception) {
                Log.w(TAG, "Warmup failed (non-fatal): ${e.message}")
            }

            Log.i(TAG, "Gemini Nano ready via ML Kit Prompt API")
            GeminiNanoModel(model)
        }
    }
}
