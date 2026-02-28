package com.pixel10.ai.inference

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * LiteRT-LM backend for Gemma 3n models (.litertlm format).
 *
 * This replaces MediaPipe for the newer Gemma 3n E4B/E2B models which use
 * the LiteRT-LM runtime. Runs fully on-device using the Tensor G5 GPU.
 *
 * Model files must be placed in the app's files directory (see [ModelDownloader]).
 */
class LiteRTModel private constructor(
    private val engine: Engine,
    private val modelName: String
) : OnDeviceModel {

    override val backendName = "LiteRT-LM ($modelName)"

    @Volatile
    override var isReady: Boolean = true
        private set

    override suspend fun generate(
        prompt: String,
        maxTokens: Int,
        temperature: Float
    ): String = withContext(Dispatchers.Default) {
        val conversation = engine.createConversation()
        try {
            conversation.sendMessage(prompt).toString()
        } catch (e: Exception) {
            Log.e(TAG, "LiteRT inference error", e)
            throw OnDeviceModel.InferenceException("Generation failed: ${e.message}", e)
        } finally {
            conversation.close()
        }
    }

    override suspend fun generateStreaming(
        prompt: String,
        onToken: (String) -> Unit
    ): String = withContext(Dispatchers.Default) {
        val conversation = engine.createConversation()
        val sb = StringBuilder()
        try {
            conversation.sendMessageAsync(prompt)
                .catch { e ->
                    throw OnDeviceModel.InferenceException("Streaming failed: ${e.message}", e)
                }
                .collect { message ->
                    val token = message.toString()
                    sb.append(token)
                    onToken(token)
                }
        } finally {
            conversation.close()
        }
        sb.toString()
    }

    override fun close() {
        isReady = false
        engine.close()
    }

    companion object {
        private const val TAG = "LiteRTModel"

        private val MODEL_EXTENSIONS = listOf("litertlm")

        suspend fun create(context: Context): LiteRTModel = withContext(Dispatchers.IO) {
            val modelPath = findModelPath(context)
                ?: throw OnDeviceModel.InferenceException(
                    "No LiteRT-LM model file found.\n" +
                    "Download a .litertlm model via the app or place one in:\n" +
                    "  ${context.filesDir.absolutePath}/"
                )

            val modelName = File(modelPath).name
            Log.i(TAG, "Loading LiteRT-LM model: $modelPath")

            try {
                val config = EngineConfig(
                    modelPath = modelPath,
                    backend = Backend.GPU
                )
                val engine = Engine(config)
                withContext(Dispatchers.Default) {
                    engine.initialize()
                }
                Log.i(TAG, "LiteRT-LM model loaded: $modelName")
                LiteRTModel(engine, modelName)
            } catch (gpuError: Exception) {
                Log.w(TAG, "GPU backend failed, trying CPU: ${gpuError.message}")
                try {
                    val config = EngineConfig(
                        modelPath = modelPath,
                        backend = Backend.CPU
                    )
                    val engine = Engine(config)
                    withContext(Dispatchers.Default) {
                        engine.initialize()
                    }
                    Log.i(TAG, "LiteRT-LM model loaded on CPU: $modelName")
                    LiteRTModel(engine, modelName)
                } catch (e: Exception) {
                    throw OnDeviceModel.InferenceException(
                        "Failed to load LiteRT-LM model from $modelPath: ${e.message}", e
                    )
                }
            }
        }

        private fun findModelPath(context: Context): String? {
            val searchDirs = listOfNotNull(
                context.filesDir,
                File(context.filesDir, "models"),
                context.getExternalFilesDir(null)
            )
            for (dir in searchDirs) {
                if (!dir.exists()) continue
                dir.listFiles()?.firstOrNull { it.extension in MODEL_EXTENSIONS }
                    ?.let { return it.absolutePath }
            }
            return null
        }
    }
}
