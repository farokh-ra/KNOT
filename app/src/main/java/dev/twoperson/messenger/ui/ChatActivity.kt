package dev.twoperson.messenger.ui

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import dev.twoperson.messenger.App
import dev.twoperson.messenger.R
import dev.twoperson.messenger.databinding.ActivityChatBinding
import dev.twoperson.messenger.engine.ConnectionState
import dev.twoperson.messenger.engine.ConnectionStatus
import dev.twoperson.messenger.protocol.Protocol
import dev.twoperson.messenger.service.ConnectionService
import kotlinx.coroutines.launch

class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private lateinit var adapter: ChatAdapter
    private val container get() = (application as App).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        adapter = ChatAdapter()
        binding.listMessages.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.listMessages.adapter = adapter

        binding.buttonSend.setOnClickListener { send() }
        binding.inputMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                send()
                true
            } else {
                false
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    container.repository.messages.collect { messages ->
                        adapter.submit(messages)
                        binding.textEmpty.isVisible = messages.isEmpty()
                        if (messages.isNotEmpty()) {
                            binding.listMessages.scrollToPosition(messages.size - 1)
                        }
                    }
                }
                launch { container.engine.status.collect { renderStatus(it) } }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // The connection service owns the engine lifetime; the activity only makes sure it
        // is running. Leaving this screen no longer drops the link.
        if (container.engine.isConfigured()) ConnectionService.ensureRunning(this)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun send() {
        val raw = binding.inputMessage.text?.toString().orEmpty()
        if (raw.isBlank()) return
        if (raw.trim().codePointCount(0, raw.trim().length) > Protocol.MAX_TEXT_CODE_POINTS) {
            binding.inputMessageLayout.error = getString(R.string.chat_too_long)
            return
        }
        binding.inputMessageLayout.error = null
        lifecycleScope.launch {
            val queued = container.engine.sendText(raw)
            if (queued) {
                binding.inputMessage.setText("")
            } else if (!container.engine.isConfigured()) {
                binding.inputMessageLayout.error = getString(R.string.chat_not_paired)
            }
        }
    }

    private fun renderStatus(status: ConnectionStatus) {
        title = getString(R.string.chat_title)
        supportActionBar?.subtitle = when (status.state) {
            ConnectionState.CONNECTED -> getString(
                if (status.direct == dev.twoperson.messenger.engine.DirectKind.LOCAL_NETWORK) {
                    R.string.state_connected_lan
                } else {
                    R.string.state_connected_internet
                }
            )

            ConnectionState.DISCOVERING, ConnectionState.CONNECTING, ConnectionState.DISCONNECTED ->
                getString(R.string.state_discovering)

            ConnectionState.UNABLE_TO_CONNECT -> getString(R.string.state_unable)
            else -> null
        }

        when (status.state) {
            ConnectionState.CONNECTED -> {
                binding.cardBanner.isVisible = false
            }

            ConnectionState.UNABLE_TO_CONNECT -> {
                binding.cardBanner.isVisible = true
                binding.textBanner.setText(R.string.chat_banner_unable)
                binding.textBanner.setTextColor(ContextCompat.getColor(this, R.color.status_error))
            }

            ConnectionState.NOT_PAIRED -> {
                binding.cardBanner.isVisible = true
                binding.textBanner.setText(R.string.chat_not_paired)
                binding.textBanner.setTextColor(ContextCompat.getColor(this, R.color.status_warn))
            }

            else -> {
                binding.cardBanner.isVisible = true
                binding.textBanner.setText(R.string.chat_banner_reconnecting)
                binding.textBanner.setTextColor(ContextCompat.getColor(this, R.color.status_warn))
            }
        }
    }
}
