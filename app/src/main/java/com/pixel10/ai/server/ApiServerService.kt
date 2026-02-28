package com.pixel10.ai.server

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pixel10.ai.Pixel10AIApp
import com.pixel10.ai.R
import com.pixel10.ai.inference.OnDeviceModel
import com.pixel10.ai.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the AI API server running even when the
 * app is in the background. Shows a persistent notification with server status.
 */
class ApiServerService : Service() {

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var server: AIApiServer? = null
    private var model: OnDeviceModel? = null
    private var wakeLock: PowerManager.WakeLock? = null

    var onStatusChanged: ((ServerState) -> Unit)? = null
    var onLog: ((String) -> Unit)? = null

    val isRunning: Boolean get() = server != null
    val requestCount: Long get() = server?.requestCount?.get() ?: 0

    inner class LocalBinder : Binder() {
        val service: ApiServerService get() = this@ApiServerService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val port = intent.getIntExtra(EXTRA_PORT, DEFAULT_PORT)
                startServer(port)
            }
            ACTION_STOP -> stopServer()
        }
        return START_STICKY
    }

    private fun startServer(port: Int) {
        if (server != null) return

        startForeground(NOTIFICATION_ID, buildNotification(port))
        acquireWakeLock()
        notifyStatus(ServerState.LOADING_MODEL)

        scope.launch {
            try {
                // Load the AI model — prefer cloud if an API key is configured
                val apiKey = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(PREF_API_KEY, "") ?: ""
                notifyLog("Loading AI model...")
                if (apiKey.isNotBlank()) {
                    notifyLog("API key configured — using cloud backend")
                }
                model = OnDeviceModel.create(applicationContext, apiKey)
                notifyLog("Model ready: ${model!!.backendName}")

                // Start the HTTP server
                notifyLog("Starting API server on port $port...")
                val apiServer = AIApiServer(port, model!!)
                apiServer.onRequestLogged = { msg -> notifyLog(msg) }
                apiServer.start()
                server = apiServer

                notifyStatus(ServerState.RUNNING)
                notifyLog("Server running on port $port")
                notifyLog("Endpoints:")
                notifyLog("  POST /v1/chat/completions")
                notifyLog("  POST /v1/completions")
                notifyLog("  GET  /v1/models")
                notifyLog("  GET  /health")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start server", e)
                notifyLog("ERROR: ${e.message}")
                notifyStatus(ServerState.ERROR)
                stopServer()
            }
        }
    }

    fun stopServer() {
        server?.stop()
        server = null
        model?.close()
        model = null
        releaseWakeLock()
        notifyStatus(ServerState.STOPPED)
        notifyLog("Server stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Pixel10AI::ServerWakeLock"
        ).apply { acquire(4 * 60 * 60 * 1000L) } // 4 hours max
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    private fun buildNotification(port: Int): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, Pixel10AIApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text, port))
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun notifyStatus(state: ServerState) {
        onStatusChanged?.invoke(state)
    }

    private fun notifyLog(message: String) {
        Log.d(TAG, message)
        onLog?.invoke(message)
    }

    override fun onDestroy() {
        stopServer()
        scope.cancel()
        super.onDestroy()
    }

    enum class ServerState {
        STOPPED, LOADING_MODEL, RUNNING, ERROR
    }

    companion object {
        private const val TAG = "ApiServerService"
        const val ACTION_START = "com.pixel10.ai.START_SERVER"
        const val ACTION_STOP = "com.pixel10.ai.STOP_SERVER"
        const val EXTRA_PORT = "port"
        const val DEFAULT_PORT = 8080
        private const val NOTIFICATION_ID = 1
        const val PREFS_NAME = "pixel10_prefs"
        const val PREF_API_KEY = "gemini_api_key"
    }
}
