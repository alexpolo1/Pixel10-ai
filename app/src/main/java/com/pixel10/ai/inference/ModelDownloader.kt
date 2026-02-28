package com.pixel10.ai.inference

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a MediaPipe-compatible model for background-safe inference.
 *
 * Gemini Nano (ML Kit) blocks inference when the app is backgrounded (ErrorCode 30).
 * MediaPipe with a local model file has no such restriction — it runs entirely in
 * the app process using the Tensor G5 GPU via OpenCL/Vulkan.
 *
 * Three model options (all from Google's MediaPipe CDN):
 *  - [ModelSpec.GEMMA_3N_E4B_CODING]  — best coding/reasoning, ~2.5 GB (recommended)
 *  - [ModelSpec.GEMMA_3N_E2B_CODING]  — good balance, ~1.5 GB
 *  - [ModelSpec.GEMMA_2B_GENERAL]     — lightest, ~1.3 GB
 *
 * Custom models (DeepSeek Coder, Qwen2.5-Coder, etc.) can be placed manually in
 * the app's files directory after converting with ai-edge-torch.
 */
object ModelDownloader {

    private const val TAG = "ModelDownloader"

    /** Available model specs that can be downloaded from Google's MediaPipe CDN. */
    enum class ModelSpec(
        val displayName: String,
        val filename: String,
        val url: String,
        val sizeMb: Int,
        val description: String
    ) {
        /** Recommended: best coding & reasoning quality via MoE architecture. */
        GEMMA_3N_E4B_CODING(
            displayName = "Gemma 3n E4B",
            filename = "gemma-3n-E4B-it-int4.task",
            url = "https://storage.googleapis.com/mediapipe-models/llm_inference/" +
                  "gemma-3n-E4B-it-int4/float16/1/gemma-3n-E4B-it-int4.task",
            sizeMb = 2500,
            description = "Best coding & reasoning (~2.5 GB)"
        ),
        /** Good balance between quality and speed. */
        GEMMA_3N_E2B_CODING(
            displayName = "Gemma 3n E2B",
            filename = "gemma-3n-E2B-it-int4.task",
            url = "https://storage.googleapis.com/mediapipe-models/llm_inference/" +
                  "gemma-3n-E2B-it-int4/float16/1/gemma-3n-E2B-it-int4.task",
            sizeMb = 1500,
            description = "Good balance, faster (~1.5 GB)"
        ),
        /** Lightest option — general-purpose, not optimised for code. */
        GEMMA_2B_GENERAL(
            displayName = "Gemma 2B",
            filename = "gemma-2b-it-gpu-int4.bin",
            url = "https://storage.googleapis.com/mediapipe-models/llm_inference/" +
                  "gemma-2b-it-gpu-int4/float16/1/gemma-2b-it-gpu-int4.bin",
            sizeMb = 1300,
            description = "Lightest, general-purpose (~1.3 GB)"
        )
    }

    data class Progress(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val percent: Int = if (totalBytes > 0) (downloadedBytes * 100 / totalBytes).toInt() else 0
    )

    /** Returns true if any supported model is present in the app's files directory. */
    fun isModelPresent(context: Context): Boolean =
        ModelSpec.values().any { modelFile(context, it).let { f -> f.exists() && f.length() > 1_000_000L } }

    /** Returns the installed [ModelSpec], or null if no model is present. */
    fun installedSpec(context: Context): ModelSpec? =
        ModelSpec.values().firstOrNull { modelFile(context, it).let { f -> f.exists() && f.length() > 1_000_000L } }

    fun modelFile(context: Context, spec: ModelSpec): File =
        File(context.filesDir, spec.filename)

    /** Legacy compat — returns the file of the installed model, or Gemma 3n E4B path as default. */
    fun modelFile(context: Context): File =
        installedSpec(context)?.let { modelFile(context, it) }
            ?: modelFile(context, ModelSpec.GEMMA_3N_E4B_CODING)

    /**
     * Download [spec], reporting progress via [onProgress].
     * Supports resume — if a partial file exists, continues from where it left off.
     */
    suspend fun download(
        context: Context,
        spec: ModelSpec = ModelSpec.GEMMA_3N_E4B_CODING,
        onProgress: (Progress) -> Unit
    ) = withContext(Dispatchers.IO) {
        val dest = modelFile(context, spec)
        val alreadyDownloaded = if (dest.exists()) dest.length() else 0L

        Log.i(TAG, "Download starting ${spec.displayName} (already have $alreadyDownloaded bytes)")

        val conn = URL(spec.url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 30_000
            conn.readTimeout   = 60_000
            if (alreadyDownloaded > 0) {
                conn.setRequestProperty("Range", "bytes=$alreadyDownloaded-")
            }
            conn.connect()

            val code = conn.responseCode
            val resuming = code == HttpURLConnection.HTTP_PARTIAL   // 206
            if (code != HttpURLConnection.HTTP_OK && !resuming) {
                throw OnDeviceModel.InferenceException("Download failed: HTTP $code")
            }

            val serverBytes = conn.contentLengthLong.coerceAtLeast(0L)
            val totalBytes  = if (resuming) alreadyDownloaded + serverBytes else serverBytes

            conn.inputStream.use { input ->
                FileOutputStream(dest, /* append= */ resuming).use { out ->
                    val buf = ByteArray(128 * 1024)
                    var written = alreadyDownloaded
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                        written += read
                        onProgress(Progress(written, totalBytes))
                    }
                }
            }

            Log.i(TAG, "Download complete — ${dest.length()} bytes")
        } finally {
            conn.disconnect()
        }
    }

    fun deleteModel(context: Context) {
        ModelSpec.values().forEach { modelFile(context, it).delete() }
        Log.i(TAG, "All models deleted")
    }
}
