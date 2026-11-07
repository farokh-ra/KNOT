package dev.twoperson.messenger.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.twoperson.messenger.App
import dev.twoperson.messenger.R
import dev.twoperson.messenger.data.Message
import dev.twoperson.messenger.data.MessageDirection
import dev.twoperson.messenger.data.MessageKind
import dev.twoperson.messenger.data.PeerInfo
import dev.twoperson.messenger.databinding.ActivityHomeBinding
import dev.twoperson.messenger.engine.ConnectionState
import dev.twoperson.messenger.engine.ConnectionStatus
import dev.twoperson.messenger.engine.DirectKind
import dev.twoperson.messenger.engine.EngineEvent
import dev.twoperson.messenger.service.ConnectionService
import dev.twoperson.messenger.service.MessageNotifications
import dev.twoperson.messenger.util.AppVisibility
import kotlinx.coroutines.launch
import java.util.Date

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private val container get() = (application as App).container

    private var configured = false
    private var peer: PeerInfo? = null
    private var messages: List<Message> = emptyList()
    private var unread = 0
    private var latestStatus = ConnectionStatus()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            renderNotificationCard()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.cardConversation.setOnClickListener {
            if (container.engine.isConfigured()) {
                startActivity(Intent(this, ChatActivity::class.java))
            } else {
                openPairing(PairingActivity.MODE_CREATE)
            }
        }
        binding.buttonCreate.setOnClickListener { openPairing(PairingActivity.MODE_CREATE) }
        binding.buttonJoin.setOnClickListener { openPairing(PairingActivity.MODE_JOIN) }
        binding.buttonSaveLabel.setOnClickListener { saveLabel() }
        binding.buttonClear.setOnClickListener { confirmClear() }
        binding.buttonUnpair.setOnClickListener { confirmUnpair() }
        binding.buttonNotifications.setOnClickListener { requestNotifications() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    container.engine.status.collect { status ->
                        latestStatus = status
                        renderStatus(status)
                    }
                }
                launch { container.engine.events.collect { handleEvent(it) } }
                launch {
                    container.repository.messages.collect { list ->
                        messages = list
                        updateConversationCard()
                    }
                }
                launch {
                    container.repository.unreadCount.collect { count ->
                        unread = count
                        updateUnreadBadge()
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.onActivityStarted()
        if (container.engine.isConfigured()) {
            ConnectionService.ensureRunning(this)
        }
        renderNotificationCard()
    }

    override fun onStop() {
        AppVisibility.onActivityStopped()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        MessageNotifications.dismissIncoming(this)
        lifecycleScope.launch {
            container.repository.refresh()
            configured = container.engine.isConfigured()
            refreshPeerUi()
        }
        renderNotificationCard()
    }

    private fun openPairing(mode: String) {
        startActivity(
            Intent(this, PairingActivity::class.java)
                .putExtra(PairingActivity.EXTRA_MODE, mode)
        )
    }

    private suspend fun refreshPeerUi() {
        configured = container.engine.isConfigured()
        // A peer row can outlive the invitation (forget/unpair). Never present its name and
        // pairing date as if that device were still the configured partner.
        peer = if (configured) container.repository.peer() else null
        val name = peerName()
        binding.textConversationName.text = name
        binding.textAvatar.text = initial(name)
        binding.textContactAvatar.text = initial(name)
        binding.textPeerLabel.text = name
        binding.inputPeerLabel.setText(peer?.label.orEmpty())
        binding.textPeerDevice.text = deviceLine(name)
        binding.cardPartner.isVisible = configured
        binding.buttonUnpair.isVisible = configured
        binding.buttonCreate.isVisible = !configured
        binding.buttonJoin.isVisible = !configured
        binding.inputPeerLabel.isEnabled = configured
        binding.buttonSaveLabel.isEnabled = configured
        renderStatus(latestStatus)
        updateConversationCard()
        updateUnreadBadge()
        renderNotificationCard()
    }

    private fun peerName(): String {
        val label = peer?.label?.takeIf { it.isNotBlank() }
        val displayName = peer?.displayName?.takeIf { it.isNotBlank() }
        return label ?: displayName ?: getString(
            if (configured) R.string.home_partner_connecting else R.string.home_partner_unknown
        )
    }

    private fun deviceLine(currentName: String): String {
        val displayName = peer?.displayName
            ?.takeIf { it.isNotBlank() && it != currentName }
        val pairedAt = peer?.pairedAt?.takeIf { it > 0 }?.let { timestamp ->
            getString(
                R.string.home_paired_on,
                DateFormat.getMediumDateFormat(this).format(Date(timestamp))
            )
        }
        val details = listOfNotNull(displayName, pairedAt).joinToString(" · ")
        return details.ifEmpty {
            getString(if (configured) R.string.home_contact_device else R.string.home_unknown_device)
        }
    }

    private fun initial(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "K"
        val first = trimmed.firstOrNull { it.isLetterOrDigit() } ?: trimmed.first()
        return first.uppercase()
    }

    private fun updateConversationCard() {
        val latest = messages.lastOrNull()
        if (latest == null) {
            binding.textConversationPreview.setText(
                if (configured) R.string.home_conversation_empty_hint else R.string.home_conversation_new
            )
            binding.textConversationTime.text = ""
        } else {
            val content = when (latest.kind) {
                MessageKind.FILE -> getString(
                    R.string.home_file_preview,
                    latest.fileName ?: latest.body
                )

                MessageKind.TEXT -> latest.body
            }
            binding.textConversationPreview.text = if (latest.direction == MessageDirection.OUTGOING) {
                getString(R.string.home_preview_you, content)
            } else {
                content
            }
            binding.textConversationTime.text = UiTime.conversationTime(this, latest.createdAt)
        }
        binding.buttonClear.isVisible = messages.isNotEmpty()
    }

    private fun updateUnreadBadge() {
        binding.textUnreadBadge.isVisible = unread > 0
        binding.textUnreadBadge.text = if (unread > 99) "99+" else unread.toString()
    }

    private fun saveLabel() {
        val label = binding.inputPeerLabel.text?.toString()?.trim().orEmpty()
        lifecycleScope.launch {
            container.repository.setPeerLabel(label.ifEmpty { null })
            peer = container.repository.peer()
            refreshPeerUi()
            binding.inputPeerLabel.clearFocus()
            Toast.makeText(this@HomeActivity, R.string.home_label_saved, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.chat_clear_confirm_title)
            .setMessage(R.string.chat_clear_confirm_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.chat_clear_confirm_ok) { _, _ ->
                lifecycleScope.launch { container.engine.clearConversation() }
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
                configured = false
                peer = null
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

            EngineEvent.PairingCompleted -> lifecycleScope.launch {
                peer = container.repository.peer()
                refreshPeerUi()
            }

            EngineEvent.PeerOffline -> Unit
        }
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
        binding.textConversationStatus.setText(labelRes)
        binding.textStatusDetail.isVisible = status.state == ConnectionState.UNABLE_TO_CONNECT
        if (status.state == ConnectionState.UNABLE_TO_CONNECT) {
            binding.textStatusDetail.setText(R.string.state_unable_detail)
        }
    }

    private fun requestNotifications() {
        when {
            MessageNotifications.canPost(this) -> Unit
            MessageNotifications.shouldAskPermission(this) -> {
                MessageNotifications.markPermissionAsked(this)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            else -> openNotificationSettings()
        }
    }

    private fun openNotificationSettings() {
        val direct = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        try {
            startActivity(direct)
        } catch (e: Exception) {
            try {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(android.net.Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                // No settings activity: the card simply remains informational.
            }
        }
    }

    private fun renderNotificationCard() {
        val canPost = MessageNotifications.canPost(this)
        // Once notifications work, the card is only noise. It appears again automatically if
        // the user revokes the permission or blocks notifications in system settings.
        binding.cardNotifications.isVisible = !canPost
        if (canPost) {
            binding.textNotificationStatus.setText(R.string.home_notifications_on)
            binding.buttonNotifications.setText(R.string.home_notifications_on)
            binding.buttonNotifications.isEnabled = false
        } else {
            val canAsk = MessageNotifications.shouldAskPermission(this)
            binding.textNotificationStatus.setText(
                if (canAsk) R.string.home_notifications_body else R.string.home_notifications_off
            )
            binding.buttonNotifications.setText(
                if (canAsk) R.string.home_notifications_enable else R.string.home_notifications_settings
            )
            binding.buttonNotifications.isEnabled = true
        }
    }
}
