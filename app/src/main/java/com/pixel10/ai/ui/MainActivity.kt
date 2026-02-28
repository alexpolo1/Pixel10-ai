package com.pixel10.ai.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.pixel10.ai.R
import com.pixel10.ai.databinding.ActivityMainBinding
import com.pixel10.ai.server.ApiServerService
import com.pixel10.ai.server.ApiServerService.Companion.PREF_API_KEY
import com.pixel10.ai.server.ApiServerService.Companion.PREFS_NAME
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var service: ApiServerService? = null
    private var bound = false

    private val logBuffer = StringBuilder()

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* proceed regardless */ }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as ApiServerService.LocalBinder
            service = localBinder.service
            bound = true

            service?.onStatusChanged = { state ->
                runOnUiThread { updateStatus(state) }
            }
            service?.onLog = { message ->
                runOnUiThread { appendLog(message) }
            }

            if (service?.isRunning == true) {
                updateStatus(ApiServerService.ServerState.RUNNING)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNotificationPermission()

        // Restore saved API key
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        binding.etApiKey.setText(prefs.getString(PREF_API_KEY, ""))

        binding.btnToggle.setOnClickListener {
            if (service?.isRunning == true) {
                stopServer()
            } else {
                startServer()
            }
        }

        updateStatus(ApiServerService.ServerState.STOPPED)
        appendLog("Pixel10 AI Server ready")
        appendLog("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLog("SoC: ${Build.SOC_MODEL}")
        appendLog("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLog("")
        appendLog("Tap 'Start Server' to begin serving AI inference")
    }

    override fun onStart() {
        super.onStart()
        Intent(this, ApiServerService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            service?.onStatusChanged = null
            service?.onLog = null
            unbindService(serviceConnection)
            bound = false
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun startServer() {
        val port = binding.etPort.text.toString().toIntOrNull() ?: 8080

        // Persist API key before starting the service
        val apiKey = binding.etApiKey.text.toString().trim()
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_API_KEY, apiKey)
            .apply()

        val intent = Intent(this, ApiServerService::class.java).apply {
            action = ApiServerService.ACTION_START
            putExtra(ApiServerService.EXTRA_PORT, port)
        }
        startForegroundService(intent)

        // Bind if not already bound
        if (!bound) {
            bindService(
                Intent(this, ApiServerService::class.java),
                serviceConnection,
                Context.BIND_AUTO_CREATE
            )
        }

        updateStatus(ApiServerService.ServerState.LOADING_MODEL)
    }

    private fun stopServer() {
        service?.stopServer()
        updateStatus(ApiServerService.ServerState.STOPPED)
    }

    private fun updateStatus(state: ApiServerService.ServerState) {
        when (state) {
            ApiServerService.ServerState.STOPPED -> {
                binding.tvServerStatus.text = getString(R.string.server_status_stopped)
                (binding.viewStatusDot.background as? GradientDrawable)?.setColor(getColor(R.color.status_stopped))
                binding.tvServerUrl.text = "http://—"
                binding.tvModelStatus.text = "Model: not loaded"
                binding.btnToggle.text = getString(R.string.btn_start)
                binding.btnToggle.isEnabled = true
                binding.etPort.isEnabled = true
                binding.etApiKey.isEnabled = true
            }
            ApiServerService.ServerState.LOADING_MODEL -> {
                binding.tvServerStatus.text = getString(R.string.server_status_starting)
                (binding.viewStatusDot.background as? GradientDrawable)?.setColor(getColor(R.color.primary))
                binding.tvModelStatus.text = getString(R.string.model_loading)
                binding.btnToggle.isEnabled = false
                binding.etPort.isEnabled = false
                binding.etApiKey.isEnabled = false
            }
            ApiServerService.ServerState.RUNNING -> {
                val port = binding.etPort.text.toString()
                val ip = getLocalIpAddress()
                binding.tvServerStatus.text = getString(R.string.server_status_running)
                (binding.viewStatusDot.background as? GradientDrawable)?.setColor(getColor(R.color.status_running))
                binding.tvServerUrl.text = "http://$ip:$port"
                binding.tvModelStatus.text = getString(R.string.model_ready)
                binding.btnToggle.text = getString(R.string.btn_stop)
                binding.btnToggle.isEnabled = true
                binding.etPort.isEnabled = false
                binding.etApiKey.isEnabled = false
            }
            ApiServerService.ServerState.ERROR -> {
                binding.tvServerStatus.text = getString(R.string.server_status_error)
                (binding.viewStatusDot.background as? GradientDrawable)?.setColor(getColor(R.color.error))
                binding.tvModelStatus.text = getString(R.string.model_error)
                binding.btnToggle.text = getString(R.string.btn_start)
                binding.btnToggle.isEnabled = true
                binding.etPort.isEnabled = true
                binding.etApiKey.isEnabled = true
            }
        }
    }

    private fun appendLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        logBuffer.append("[$timestamp] $message\n")
        binding.tvLog.text = logBuffer.toString()

        // Auto-scroll to bottom
        binding.scrollLog.post {
            binding.scrollLog.fullScroll(View.FOCUS_DOWN)
        }

        // Update request count
        service?.let {
            binding.tvRequestCount.text = "Requests served: ${it.requestCount}"
        }
    }

    @Suppress("DEPRECATION")
    private fun getLocalIpAddress(): String {
        try {
            val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val ip = wifiManager.connectionInfo.ipAddress
            if (ip != 0) {
                return "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"
            }
        } catch (_: Exception) {}

        // Fallback: iterate network interfaces
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: "0.0.0.0"
                    }
                }
            }
        } catch (_: Exception) {}

        return "0.0.0.0"
    }
}
