package dev.twoperson.messenger.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.twoperson.messenger.R
import dev.twoperson.messenger.data.Contact
import dev.twoperson.messenger.databinding.ActivityPairingBinding
import dev.twoperson.messenger.engine.ConnectionState
import dev.twoperson.messenger.service.ConnectionService
import kotlinx.coroutines.launch

/**
 * Pairing for one contact. An invitation is always tied to the contact the user chose, and the
 * engine switches to it before showing or joining a code.
 */
class PairingActivity : KnotActivity() {

    private lateinit var binding: ActivityPairingBinding
    private lateinit var contactId: String
    private lateinit var mode: String
    private var contact: Contact? = null
    private var finishing = false
    private var selectionReady = false

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (!contents.isNullOrBlank()) {
            binding.inputJoinCode.setText(contents)
            attemptJoin(contents)
        }
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchScanner() else showStatus(getString(R.string.pairing_camera_permission), error = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        contactId = intent?.getStringExtra(EXTRA_CONTACT_ID)
            ?: container.repository.activeContact.value?.id.orEmpty()
        if (contactId.isBlank()) {
            Toast.makeText(this, R.string.pairing_missing_contact, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_CREATE

        binding = ActivityPairingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val showInvitation = mode != MODE_JOIN
        binding.textShowTitle.isVisible = showInvitation
        binding.textShowHint.isVisible = showInvitation
        binding.imageQr.isVisible = showInvitation
        binding.textCode.isVisible = showInvitation
        binding.buttonCopyCode.isVisible = showInvitation
        binding.textWaiting.isVisible = showInvitation
        binding.textEnterTitle.isVisible = !showInvitation
        binding.divider.isVisible = !showInvitation
        binding.inputJoinLayout.isVisible = !showInvitation
        binding.buttonScan.isVisible = !showInvitation
        binding.buttonJoin.isVisible = !showInvitation

        binding.buttonScan.setOnClickListener { requestScan() }
        binding.buttonJoin.setOnClickListener { attemptJoin(binding.inputJoinCode.text?.toString()) }
        binding.buttonDone.setOnClickListener { finish() }

        if (showInvitation) prepareInvitation()

        lifecycleScope.launch {
            selectionReady = container.engine.selectContact(contactId)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    container.repository.contacts.collect { contacts ->
                        val found = contacts.firstOrNull { it.id == contactId }
                        if (found == null) {
                            if (!finishing) {
                                finishing = true
                                Toast.makeText(
                                    this@PairingActivity,
                                    R.string.pairing_missing_contact,
                                    Toast.LENGTH_SHORT
                                ).show()
                                finish()
                            }
                        } else {
                            contact = found
                            renderContact(found)
                        }
                    }
                }
                launch {
                    container.engine.status.collect { status ->
                        val active = container.repository.activeContact.value?.id == contactId
                        if (selectionReady && active && status.state == ConnectionState.CONNECTED) {
                            showStatus(getString(R.string.pairing_paired), error = false)
                        } else if (mode != MODE_JOIN &&
                            (status.state == ConnectionState.DISCOVERING ||
                                status.state == ConnectionState.CONNECTING)
                        ) {
                            binding.textWaiting.isVisible = true
                        }
                    }
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun renderContact(found: Contact) {
        val name = found.name
        binding.textPairingContact.text = getString(R.string.pairing_contact_title, name)
        supportActionBar?.title = name
    }

    private fun prepareInvitation() {
        val code = container.engine.currentInvitationCode(contactId)
            ?: container.engine.createInvitation(contactId)
        binding.textCode.text = code
        binding.imageQr.setImageBitmap(Qr.bitmap(code, 640))
        binding.buttonCopyCode.setOnClickListener { copyCode(code) }
        binding.textWaiting.isVisible = true
        binding.textWaiting.setText(R.string.pairing_waiting)
    }

    private fun attemptJoin(code: String?) {
        val trimmed = code?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            showStatus(getString(R.string.pairing_invalid), error = true)
            return
        }
        lifecycleScope.launch {
            container.engine.selectContact(contactId)
            if (!container.engine.joinInvitation(contactId, trimmed)) {
                showStatus(getString(R.string.pairing_invalid), error = true)
                return@launch
            }
            showStatus(getString(R.string.pairing_joined_note), error = false)
            ConnectionService.sync(this@PairingActivity)
        }
    }

    private fun requestScan() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            launchScanner()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt(getString(R.string.pairing_scan_prompt))
            setBeepEnabled(false)
            setOrientationLocked(false)
            setBarcodeImageEnabled(false)
        }
        scanLauncher.launch(options)
    }

    private fun copyCode(code: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.pairing_section_show), code))
        Toast.makeText(this, R.string.pairing_copied, Toast.LENGTH_SHORT).show()
    }

    private fun showStatus(text: String, error: Boolean) {
        binding.textPairingStatus.isVisible = true
        binding.textPairingStatus.text = text
        binding.textPairingStatus.setTextColor(
            ContextCompat.getColor(this, if (error) R.color.status_error else R.color.status_ok)
        )
        binding.buttonDone.visibility = View.VISIBLE
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_CONTACT_ID = "contact_id"
        const val MODE_CREATE = "create"
        const val MODE_JOIN = "join"
        const val MODE_SHOW = "show"
    }
}
