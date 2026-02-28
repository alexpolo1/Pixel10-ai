package com.pixel10.ai.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.pixel10.ai.databinding.ActivityChatBinding
import com.pixel10.ai.inference.OnDeviceModel
import com.pixel10.ai.server.AgentConfig
import com.pixel10.ai.server.ApiServerService
import kotlinx.coroutines.launch

class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private val messages = mutableListOf<ChatMessage>()
    private lateinit var adapter: MessageAdapter
    private var service: ApiServerService? = null
    private var bound = false
    private var generating = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as ApiServerService.LocalBinder).service
            bound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = MessageAdapter(messages)
        binding.rvMessages.layoutManager = LinearLayoutManager(this).also {
            it.stackFromEnd = true
        }
        binding.rvMessages.adapter = adapter

        binding.btnSend.setOnClickListener { sendMessage() }
        binding.etMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendMessage(); true } else false
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(
            Intent(this, ApiServerService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onStop() {
        super.onStop()
        if (bound) { unbindService(serviceConnection); bound = false }
    }

    private fun sendMessage() {
        if (generating) return
        val text = binding.etMessage.text.toString().trim()
        if (text.isEmpty()) return

        binding.etMessage.text?.clear()

        // Add user message
        messages.add(ChatMessage("user", text))
        adapter.notifyItemInserted(messages.size - 1)
        scrollToBottom()

        // Add empty AI placeholder
        messages.add(ChatMessage("assistant", ""))
        val aiIndex = messages.size - 1
        adapter.notifyItemInserted(aiIndex)
        scrollToBottom()

        binding.tvTyping.visibility = View.VISIBLE
        binding.btnSend.isEnabled = false
        generating = true

        val model = service?.currentModel
        if (model == null || !model.isReady) {
            messages[aiIndex].content = "⚠️ Server not running — start the server first."
            adapter.notifyItemChanged(aiIndex)
            finishGeneration()
            return
        }

        val prompt = buildPrompt()

        lifecycleScope.launch {
            try {
                model.generateStreaming(prompt) { token ->
                    runOnUiThread {
                        messages[aiIndex].content += token
                        adapter.notifyItemChanged(aiIndex)
                        scrollToBottom()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    messages[aiIndex].content = "⚠️ Error: ${e.message}"
                    adapter.notifyItemChanged(aiIndex)
                }
            } finally {
                runOnUiThread { finishGeneration() }
            }
        }
    }

    private fun finishGeneration() {
        generating = false
        binding.tvTyping.visibility = View.GONE
        binding.btnSend.isEnabled = true
        scrollToBottom()
    }

    private fun buildPrompt(): String {
        val prefs = getSharedPreferences("pixel10_prefs", MODE_PRIVATE)
        val useSystemPrompt = prefs.getBoolean("auto_system_prompt", true)

        val sb = StringBuilder()
        if (useSystemPrompt) {
            sb.append("System: ${AgentConfig.SYSTEM_PROMPT}\n\n")
        }
        // Include all messages except the last empty AI placeholder
        for (i in 0 until messages.size - 1) {
            val msg = messages[i]
            when (msg.role) {
                "user"      -> sb.append("User: ${msg.content}\n\n")
                "assistant" -> sb.append("Assistant: ${msg.content}\n\n")
            }
        }
        sb.append("Assistant:")
        return sb.toString()
    }

    private fun scrollToBottom() {
        if (messages.isNotEmpty()) {
            binding.rvMessages.smoothScrollToPosition(messages.size - 1)
        }
    }
}
