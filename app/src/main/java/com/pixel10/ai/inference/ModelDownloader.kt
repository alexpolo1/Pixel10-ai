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
 * All models are hosted on HuggingFace and require a free API token.
 * Get one at: https://huggingface.co/settings/tokens
 *
 * Models use the MediaPipe `.task` format, compatible with [MediaPipeModel].
 * Gemma 3n E4B/E2B (`.litertlm` format) requires a runtime upgrade — coming later.
 */
object ModelDownloader {

    private const val TAG = "ModelDownloader"
    private const val HF_BASE = "https://huggingface.co"

    /** Available model specs downloadable from HuggingFace (requires token + license acceptance). */
    enum class ModelSpec(
        val displayName: String,
        val filename: String,
        val repo: String,
        val sizeMb: Int,
        val description: String
    ) {
        /**
         * Gemma 3n E4B INT4 — best quality, Tensor G5 optimised, background-safe.
         * Accept license at: https://huggingface.co/google/gemma-3n-E4B-it-litert-lm
         */
        GEMMA_3N_E4B(
            displayName = "Gemma 3n E4B",
            filename = "gemma-3n-E4B-it-int4.litertlm",
            repo = "google/gemma-3n-E4B-it-litert-lm",
            sizeMb = 4920,
            description = "Best quality — Tensor G5 optimised (~4.9 GB)"
        ),
        /**
         * Gemma 3n E4B Web INT4 — smaller variant, slightly lower quality.
         * Same license as above.
         */
        GEMMA_3N_E4B_WEB(
            displayName = "Gemma 3n E4B (Web)",
            filename = "gemma-3n-E4B-it-int4-Web.litertlm",
            repo = "google/gemma-3n-E4B-it-litert-lm",
            sizeMb = 4280,
            description = "Slightly smaller variant (~4.3 GB)"
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

    /** Returns the file of the installed model, or E4B path as default. */
    fun modelFile(context: Context): File =
        installedSpec(context)?.let { modelFile(context, it) }
            ?: modelFile(context, ModelSpec.GEMMA_3N_E4B)

    /**
     * Download [spec] from HuggingFace, using [hfToken] for authentication.
     * Supports resume — if a partial file exists, continues from where it left off.
     *
     * Get a free token at https://huggingface.co/settings/tokens
     */
    suspend fun download(
        context: Context,
        spec: ModelSpec = ModelSpec.GEMMA_3N_E4B,
        hfToken: String,
        onProgress: (Progress) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (hfToken.isBlank()) throw OnDeviceModel.InferenceException(
            "HuggingFace token required.\nGet a free token at huggingface.co/settings/tokens"
        )

        val dest = modelFile(context, spec)
        val alreadyDownloaded = if (dest.exists()) dest.length() else 0L
        val downloadUrl = "$HF_BASE/${spec.repo}/resolve/main/${spec.filename}"

        Log.i(TAG, "Download starting ${spec.displayName} from $downloadUrl (already have $alreadyDownloaded bytes)")

        val conn = URL(downloadUrl).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 30_000
            conn.readTimeout   = 60_000
            conn.setRequestProperty("Authorization", "Bearer $hfToken")
            if (alreadyDownloaded > 0) {
                conn.setRequestProperty("Range", "bytes=$alreadyDownloaded-")
            }
            conn.connect()

            val code = conn.responseCode
            if (code == 401 || code == 403) throw OnDeviceModel.InferenceException(
                "Authentication failed (HTTP $code).\nCheck your HuggingFace token."
            )
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
