package com.pixel10.ai.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.pixel10.ai.R
import com.pixel10.ai.databinding.ActivityMainBinding
import com.pixel10.ai.inference.ModelDownloader
import com.pixel10.ai.inference.ModelDownloader.ModelSpec
import com.pixel10.ai.server.ApiServerService
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private var service: ApiServerService? = null
    private var bound = false
    private var downloading = false

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
            service?.onActiveRequest = { active ->
                runOnUiThread {
                    if (active) {
                        binding.tvActiveRequest.text = "⚡ Processing request…"
                        binding.tvActiveRequest.visibility = View.VISIBLE
                    } else {
                        binding.tvActiveRequest.visibility = View.GONE
                    }
                }
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

        prefs = getSharedPreferences("pixel10_prefs", MODE_PRIVATE)
        requestNotificationPermission()

        // Restore saved settings
        binding.etHfToken.setText(prefs.getString("hf_token", ""))
        val savedTemp = (prefs.getFloat("temperature", 0.7f) * 100).toInt()
        binding.seekTemperature.progress = savedTemp
        binding.tvTemperatureValue.text = "%.1f".format(savedTemp / 100f)
        binding.etMaxTokens.setText(prefs.getInt("max_tokens", 1024).toString())
        binding.switchSystemPrompt.isChecked = prefs.getBoolean("auto_system_prompt", true)

        binding.seekTemperature.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                binding.tvTemperatureValue.text = "%.1f".format(progress / 100f)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        binding.btnToggle.setOnClickListener {
            if (service?.isRunning == true) stopServer() else startServer()
        }

        binding.btnDownloadModel.setOnClickListener {
            saveHfToken()
            startModelDownload(ModelSpec.GEMMA_3N_E4B)
        }
        binding.btnDownloadGemma3Q8.setOnClickListener {
            saveHfToken()
            startModelDownload(ModelSpec.GEMMA_3N_E4B_WEB)
        }

        updateModelCard()
        updateStatus(ApiServerService.ServerState.STOPPED)
        appendLog("Pixel10 AI Server ready")
        appendLog("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLog("SoC: ${Build.SOC_MODEL}")
        appendLog("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLog("")
        if (ModelDownloader.isModelPresent(this)) {
            appendLog("Model ready — server works in background")
        } else {
            appendLog("No local model found")
            appendLog("Tap 'Download Model' to enable background inference")
            appendLog("(Without it, Gemini Nano only works in foreground)")
        }
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

    private fun saveHfToken() {
        val token = binding.etHfToken.text.toString().trim()
        prefs.edit().putString("hf_token", token).apply()
    }

    private fun startModelDownload(spec: ModelSpec) {
        if (downloading) return
        val token = binding.etHfToken.text.toString().trim()
        if (token.isBlank()) {
            binding.tvModelDownloadStatus.text = "Enter your HuggingFace token first"
            return
        }
        downloading = true
        setDownloadButtonsEnabled(false)
        binding.progressDownload.visibility = View.VISIBLE
        binding.tvModelDownloadStatus.text = "Starting download: ${spec.displayName}…"

        lifecycleScope.launch {
            try {
                ModelDownloader.download(this@MainActivity, spec, token) { progress ->
                    runOnUiThread {
                        binding.progressDownload.progress = progress.percent
                        val mb = progress.downloadedBytes / 1_048_576
                        val total = progress.totalBytes / 1_048_576
                        binding.tvModelDownloadStatus.text =
                            "${spec.displayName}: ${mb}MB / ${total}MB (${progress.percent}%)"
                    }
                }
                runOnUiThread {
                    downloading = false
                    updateModelCard()
                    appendLog("${spec.displayName} downloaded — background inference enabled")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    downloading = false
                    setDownloadButtonsEnabled(true)
                    binding.progressDownload.visibility = View.GONE
                    binding.tvModelDownloadStatus.text = "Download failed: ${e.message}"
                    appendLog("Download error: ${e.message}")
                }
            }
        }
    }

    private fun setDownloadButtonsEnabled(enabled: Boolean) {
        binding.btnDownloadModel.isEnabled = enabled
        binding.btnDownloadGemma3Q8.isEnabled = enabled
    }

    private fun updateModelCard() {
        val spec = ModelDownloader.installedSpec(this)
        if (spec != null) {
            binding.tvModelDownloadStatus.text = getString(R.string.model_downloaded, spec.displayName)
            binding.etHfToken.visibility = View.GONE
            binding.btnDownloadModel.visibility = View.GONE
            binding.btnDownloadGemma3Q8.visibility = View.GONE
            binding.progressDownload.visibility = View.GONE
        } else {
            binding.tvModelDownloadStatus.text = getString(R.string.model_not_downloaded)
            binding.etHfToken.visibility = View.VISIBLE
            binding.btnDownloadModel.visibility = View.VISIBLE
            binding.btnDownloadGemma3Q8.visibility = View.VISIBLE
            setDownloadButtonsEnabled(true)
            binding.progressDownload.visibility = View.GONE
        }
    }

    private fun saveSettings() {
        prefs.edit()
            .putFloat("temperature", binding.seekTemperature.progress / 100f)
            .putInt("max_tokens", binding.etMaxTokens.text.toString().toIntOrNull() ?: 1024)
            .putBoolean("auto_system_prompt", binding.switchSystemPrompt.isChecked)
            .apply()
    }

    private fun startServer() {
        saveSettings()
        val port = binding.etPort.text.toString().toIntOrNull() ?: 8080
        val intent = Intent(this, ApiServerService::class.java).apply {
            action = ApiServerService.ACTION_START
            putExtra(ApiServerService.EXTRA_PORT, port)
        }
        startForegroundService(intent)
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
        val canEdit = state == ApiServerService.ServerState.STOPPED ||
                      state == ApiServerService.ServerState.ERROR
        binding.etPort.isEnabled = canEdit
        binding.seekTemperature.isEnabled = canEdit
        binding.etMaxTokens.isEnabled = canEdit
        binding.switchSystemPrompt.isEnabled = canEdit

        when (state) {
            ApiServerService.ServerState.STOPPED -> {
                binding.tvServerStatus.text = getString(R.string.server_status_stopped)
                (binding.viewStatusDot.background as? GradientDrawable)?.setColor(getColor(R.color.status_stopped))
                binding.tvServerUrl.text = "http://—"
                binding.tvModelStatus.text = "Model: not loaded"
                binding.btnToggle.text = getString(R.string.btn_start)
                binding.btnToggle.isEnabled = true
            }
            ApiServerService.ServerState.LOADING_MODEL -> {
                binding.tvServerStatus.text = getString(R.string.server_status_starting)
                (binding.viewStatusDot.background as? GradientDrawable)?.setColor(getColor(R.color.primary))
                binding.tvModelStatus.text = getString(R.string.model_loading)
                binding.btnToggle.isEnabled = false
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
            }
            ApiServerService.ServerState.ERROR -> {
                binding.tvServerStatus.text = getString(R.string.server_status_error)
                (binding.viewStatusDot.background as? GradientDrawable)?.setColor(getColor(R.color.error))
                binding.tvModelStatus.text = getString(R.string.model_error)
                binding.btnToggle.text = getString(R.string.btn_start)
                binding.btnToggle.isEnabled = true
            }
        }
    }

    private fun appendLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        logBuffer.append("[$timestamp] $message\n")
        binding.tvLog.text = logBuffer.toString()
        binding.scrollLog.post { binding.scrollLog.fullScroll(View.FOCUS_DOWN) }
        service?.let { binding.tvRequestCount.text = "Requests served: ${it.requestCount}" }
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
