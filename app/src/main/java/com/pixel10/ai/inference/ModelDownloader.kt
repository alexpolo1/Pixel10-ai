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
 * Downloads a MediaPipe-compatible Gemma model for background-safe inference.
 *
 * Gemini Nano (ML Kit) blocks inference when the app is backgrounded (ErrorCode 30).
 * MediaPipe with a local model file has no such restriction — it runs entirely in
 * the app process using the Tensor G5 GPU via OpenCL/Vulkan.
 *
 * The downloaded model is stored in the app's private files directory and
 * survives app restarts. Only needs to be downloaded once (~1.3 GB).
 */
object ModelDownloader {

    private const val TAG = "ModelDownloader"

    const val MODEL_FILENAME = "gemma-2b-it-gpu-int4.bin"
    private const val MODEL_URL =
        "https://storage.googleapis.com/mediapipe-models/llm_inference/" +
        "gemma-2b-it-gpu-int4/float16/1/gemma-2b-it-gpu-int4.bin"

    data class Progress(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val percent: Int = if (totalBytes > 0) (downloadedBytes * 100 / totalBytes).toInt() else 0
    )

    fun isModelPresent(context: Context): Boolean =
        modelFile(context).let { it.exists() && it.length() > 1_000_000L }

    fun modelFile(context: Context): File = File(context.filesDir, MODEL_FILENAME)

    /**
     * Download the model, reporting progress via [onProgress].
     * Supports resume — if a partial file exists, continues from where it left off.
     */
    suspend fun download(
        context: Context,
        onProgress: (Progress) -> Unit
    ) = withContext(Dispatchers.IO) {
        val dest = modelFile(context)
        val alreadyDownloaded = if (dest.exists()) dest.length() else 0L

        Log.i(TAG, "Download starting (already have $alreadyDownloaded bytes)")

        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
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
        modelFile(context).delete()
        Log.i(TAG, "Model deleted")
    }
}
