package dev.twoperson.messenger.ui

import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.twoperson.messenger.App
import dev.twoperson.messenger.R
import dev.twoperson.messenger.data.PeerInfo
import dev.twoperson.messenger.databinding.ActivityHomeBinding
import dev.twoperson.messenger.engine.ConnectionState
import dev.twoperson.messenger.engine.ConnectionStatus
import dev.twoperson.messenger.engine.DirectKind
import dev.twoperson.messenger.engine.EngineEvent
import dev.twoperson.messenger.service.ConnectionService
import kotlinx.coroutines.launch

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private val container get() = (application as App).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.buttonCreate.setOnClickListener { openPairing(PairingActivity.MODE_CREATE) }
        binding.buttonJoin.setOnClickListener { openPairing(PairingActivity.MODE_JOIN) }
        binding.buttonOpenChat.setOnClickListener {
            startActivity(android.content.Intent(this, ChatActivity::class.java))
        }
        binding.buttonSaveLabel.setOnClickListener { saveLabel() }
        binding.buttonClear.setOnClickListener { confirmClear() }
        binding.buttonUnpair.setOnClickListener { confirmUnpair() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { container.engine.status.collect { renderStatus(it) } }
                launch { container.engine.events.collect { handleEvent(it) } }
                launch {
                    container.repository.messages.collect { messages ->
                        binding.buttonClear.isVisible = messages.isNotEmpty()
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Keep the direct connection owned by the foreground service so backgrounding the
        // app or turning the screen off does not drop the link.
        if (container.engine.isConfigured()) {
            ConnectionService.ensureRunning(this)
            requestNotificationPermission()
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            container.repository.refresh()
            refreshPeerUi()
        }
    }

    private fun openPairing(mode: String) {
        startActivity(
            android.content.Intent(this, PairingActivity::class.java)
                .putExtra(PairingActivity.EXTRA_MODE, mode)
        )
    }

    private suspend fun refreshPeerUi() {
        val peer = container.repository.peer()
        binding.inputPeerLabel.setText(peer?.label.orEmpty())
        binding.textPeerLabel.text = when {
            peer?.label?.isNotBlank() == true -> peer.label
            peer?.displayName?.isNotBlank() == true -> peer.displayName
            peer != null -> getString(R.string.home_partner_unknown)
            else -> getString(R.string.home_partner_unknown)
        }
        val configured = container.engine.isConfigured()
        binding.buttonOpenChat.isVisible = configured
        binding.buttonUnpair.isVisible = configured
        binding.buttonCreate.isVisible = !configured
        binding.buttonJoin.isVisible = !configured
        binding.cardPartner.isVisible = configured
    }

    private fun saveLabel() {
        val label = binding.inputPeerLabel.text?.toString()?.trim().orEmpty()
        lifecycleScope.launch {
            container.repository.setPeerLabel(label.ifEmpty { null })
            refreshPeerUi()
            binding.inputPeerLabel.clearFocus()
        }
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.chat_clear_confirm_title)
            .setMessage(R.string.chat_clear_confirm_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.chat_clear_confirm_ok) { _, _ ->
                lifecycleScope.launch { container.repository.clearConversation() }
            }
            .show()
    }

    private fun confirmUnpair() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.home_unpair_confirm_title)
            .setMessage(R.string.home_unpair_confirm_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.home_unpair) { _, _ ->
                container.engine.forgetPartner()
                ConnectionService.stop(this)
                lifecycleScope.launch { refreshPeerUi() }
            }
            .show()
    }

    private fun handleEvent(event: EngineEvent) {
        when (event) {
            EngineEvent.PeerIdentityMismatch -> {
                MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.peer_changed)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }

            EngineEvent.PairingCompleted -> lifecycleScope.launch { refreshPeerUi() }
            EngineEvent.PeerOffline -> Unit
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) return
        notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private val notificationPermission =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {
            // A denial only hides the ongoing notification; the service keeps running.
        }

    private fun renderStatus(status: ConnectionStatus) {
        val (colorRes, labelRes) = when (status.state) {
            ConnectionState.NOT_PAIRED -> R.color.status_idle to R.string.state_not_paired
            ConnectionState.IDLE -> R.color.status_idle to R.string.state_idle
            ConnectionState.DISCOVERING -> R.color.status_warn to R.string.state_discovering
            ConnectionState.CONNECTING -> R.color.status_warn to R.string.state_connecting
            ConnectionState.CONNECTED -> R.color.status_ok to when (status.direct) {
                DirectKind.LOCAL_NETWORK -> R.string.state_connected_lan
                else -> R.string.state_connected_internet
            }

            ConnectionState.DISCONNECTED -> R.color.status_warn to R.string.state_disconnected
            ConnectionState.UNABLE_TO_CONNECT -> R.color.status_error to R.string.state_unable
        }
        binding.statusDot.backgroundTintList =
            android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))
        binding.textStatus.setText(labelRes)
        binding.textStatusDetail.isVisible = status.state == ConnectionState.UNABLE_TO_CONNECT
        if (status.state == ConnectionState.UNABLE_TO_CONNECT) {
            binding.textStatusDetail.setText(R.string.state_unable_detail)
        }
    }
}
