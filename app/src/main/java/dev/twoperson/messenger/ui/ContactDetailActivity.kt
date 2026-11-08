package dev.twoperson.messenger.ui

import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.twoperson.messenger.R
import dev.twoperson.messenger.data.Contact
import dev.twoperson.messenger.data.ContactNames
import dev.twoperson.messenger.databinding.ActivityContactDetailBinding
import dev.twoperson.messenger.engine.ConnectionStatus
import dev.twoperson.messenger.service.ConnectionService
import kotlinx.coroutines.launch
import java.util.Date

/** Full local profile for one contact, including pairing and destructive actions. */
class ContactDetailActivity : KnotActivity() {

    private lateinit var binding: ActivityContactDetailBinding
    private lateinit var contactId: String
    private var contact: Contact? = null
    private var activeId: String? = null
    private var latestStatus = ConnectionStatus()
    private var finishing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        contactId = intent?.getStringExtra(EXTRA_CONTACT_ID).orEmpty()
        if (contactId.isBlank()) {
            finishMissing()
            return
        }
        binding = ActivityContactDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbarContact)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.buttonSave.setOnClickListener { save() }
        binding.buttonOpenChat.setOnClickListener { openChat() }
        binding.buttonShowInvitation.setOnClickListener { showInvitation(createIfMissing = true) }
        binding.buttonNewInvitation.setOnClickListener { confirmNewInvitation() }
        binding.buttonJoinInvitation.setOnClickListener { openPairing(PairingActivity.MODE_JOIN) }
        binding.buttonUnpair.setOnClickListener { confirmUnpair() }
        binding.buttonClearConversation.setOnClickListener { confirmClear() }
        binding.buttonDeleteContact.setOnClickListener { confirmDelete() }

        lifecycleScope.launch {
            container.engine.selectContact(contactId)
            container.repository.refresh()
            render()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    container.repository.contacts.collect { contacts ->
                        val found = contacts.firstOrNull { it.id == contactId }
                        if (found == null) {
                            if (!finishing) finishMissing()
                        } else {
                            contact = found
                            render()
                        }
                    }
                }
                launch {
                    container.repository.activeContact.collect { active ->
                        activeId = active?.id
                        render()
                    }
                }
                launch {
                    container.engine.status.collect { status ->
                        latestStatus = status
                        render()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        lifecycleScope.launch {
            container.repository.refresh()
            render()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.contact_detail, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.actionDeleteContact -> {
            confirmDelete()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private fun render() {
        val current = contact ?: return
        binding.textAvatar.text = ContactNames.initial(current.name)
        binding.textName.text = current.name
        binding.textState.setText(StatusUi.contactState(current, activeId))

        val device = current.deviceLine
            ?: current.deviceId?.let { getString(R.string.contact_device_line, it.take(16)) }
            ?: getString(R.string.contact_not_paired_yet)
        binding.textDevice.text = device

        val identity = current.publicKeyHex ?: current.deviceId
        binding.textIdentity.isVisible = !identity.isNullOrBlank()
        if (!identity.isNullOrBlank()) {
            binding.textIdentity.text = getString(
                R.string.contact_identity_line,
                identity.take(24) + "…"
            )
        }

        if (!binding.inputName.hasFocus()) {
            binding.inputName.setText(current.label ?: current.displayName.orEmpty())
        }
        if (!binding.inputNote.hasFocus()) {
            binding.inputNote.setText(current.note.orEmpty())
        }

        val active = activeId == contactId
        binding.textConnection.text = if (active) {
            getString(StatusUi.of(latestStatus).label) + "\n\n" +
                getString(R.string.contact_connection_active)
        } else {
            getString(R.string.contact_connection_inactive)
        }

        val pairingLines = buildList {
            if (current.isPaired) {
                val date = DateFormat.getMediumDateFormat(this@ContactDetailActivity)
                    .format(Date(current.pairedAt))
                add(getString(R.string.contact_paired_on, date))
            }
            if (current.hasInvitation) add(getString(R.string.contacts_state_invitation))
            if (isEmpty()) add(getString(R.string.contact_not_paired_yet))
        }
        binding.textPairingInfo.text = pairingLines.joinToString("\n")
        binding.buttonUnpair.isVisible = current.hasInvitation
    }

    private fun save() {
        val name = binding.inputName.text?.toString()
        val note = binding.inputNote.text?.toString()
        binding.inputName.clearFocus()
        binding.inputNote.clearFocus()
        lifecycleScope.launch {
            container.repository.updateContact(contactId, name, note)
            Toast.makeText(this@ContactDetailActivity, R.string.contact_saved, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openChat() {
        val current = contact ?: return
        if (!current.hasInvitation) {
            Toast.makeText(this, R.string.chat_not_paired, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(this, ChatActivity::class.java)
                .putExtra(ChatActivity.EXTRA_CONTACT_ID, current.id)
        )
    }

    private fun showInvitation(createIfMissing: Boolean) {
        if (container.engine.currentInvitationCode(contactId) == null) {
            if (!createIfMissing) return
            container.engine.createInvitation(contactId)
        }
        openPairing(PairingActivity.MODE_SHOW)
    }

    private fun confirmNewInvitation() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.contact_replace_title)
            .setMessage(R.string.contact_replace_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.contact_replace_ok) { _, _ ->
                container.engine.createInvitation(contactId)
                openPairing(PairingActivity.MODE_SHOW)
            }
            .show()
    }

    private fun openPairing(mode: String) {
        startActivity(
            Intent(this, PairingActivity::class.java)
                .putExtra(PairingActivity.EXTRA_CONTACT_ID, contactId)
                .putExtra(PairingActivity.EXTRA_MODE, mode)
        )
    }

    private fun confirmUnpair() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.contact_unpair_title)
            .setMessage(R.string.contact_unpair_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.contact_unpair) { _, _ ->
                container.engine.forgetPartner(contactId)
                ConnectionService.stop(this)
                Toast.makeText(this, R.string.contact_unpaired, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.chats_clear_title)
            .setMessage(R.string.chats_clear_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.chats_clear_ok) { _, _ ->
                lifecycleScope.launch {
                    container.engine.clearConversation(contactId)
                    Toast.makeText(this@ContactDetailActivity, R.string.chats_cleared, Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.contact_delete_title)
            .setMessage(R.string.contact_delete_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.contact_delete) { _, _ ->
                lifecycleScope.launch {
                    finishing = true
                    container.engine.deleteContact(contactId)
                    ConnectionService.sync(this@ContactDetailActivity)
                    Toast.makeText(this@ContactDetailActivity, R.string.chats_deleted, Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            .show()
    }

    private fun finishMissing() {
        finishing = true
        Toast.makeText(this, R.string.chat_missing_contact, Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        const val EXTRA_CONTACT_ID = "contact_id"
    }
}
