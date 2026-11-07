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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.twoperson.messenger.App
import dev.twoperson.messenger.R
import dev.twoperson.messenger.databinding.ActivityPairingBinding
import dev.twoperson.messenger.engine.ConnectionState
import dev.twoperson.messenger.service.ConnectionService
import kotlinx.coroutines.launch

class PairingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPairingBinding
    private val container get() = (application as App).container

    private lateinit var mode: String
    private var invitationCode: String? = null

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
        binding = ActivityPairingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_CREATE

        val showSection = mode == MODE_CREATE
        binding.textShowTitle.isVisible = showSection
        binding.textShowHint.isVisible = showSection
        binding.imageQr.isVisible = showSection
        binding.textCode.isVisible = showSection
        binding.buttonCopyCode.isVisible = showSection
        binding.textWaiting.isVisible = showSection
        binding.textEnterTitle.isVisible = !showSection
        binding.divider.isVisible = !showSection
        binding.inputJoinLayout.isVisible = !showSection
        binding.buttonScan.isVisible = !showSection
        binding.buttonJoin.isVisible = !showSection

        if (showSection) {
            val code = container.engine.currentInvitationCode() ?: container.engine.createInvitation()
            invitationCode = code
            binding.textCode.text = code
            binding.imageQr.setImageBitmap(Qr.bitmap(code, 640))
            binding.buttonCopyCode.setOnClickListener { copyCode(code) }
        }

        binding.buttonScan.setOnClickListener { requestScan() }
        binding.buttonJoin.setOnClickListener { attemptJoin(binding.inputJoinCode.text?.toString()) }
        binding.buttonDone.setOnClickListener { finish() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.engine.status.collect { status ->
                    when (status.state) {
                        ConnectionState.CONNECTED -> showStatus(getString(R.string.pairing_paired), error = false)
                        ConnectionState.DISCOVERING, ConnectionState.CONNECTING -> {
                            if (mode == MODE_CREATE) showStatus(getString(R.string.pairing_waiting), error = false)
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (container.engine.isConfigured()) ConnectionService.ensureRunning(this)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun attemptJoin(code: String?) {
        val trimmed = code?.trim().orEmpty()
        if (trimmed.isEmpty() || !container.engine.joinInvitation(trimmed)) {
            showStatus(getString(R.string.pairing_invalid), error = true)
            return
        }
        showStatus(getString(R.string.pairing_joined_note), error = false)
        ConnectionService.ensureRunning(this)
        lifecycleScope.launch {
            kotlinx.coroutines.delay(1_200)
            finish()
        }
    }

    private fun requestScan() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
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
        if (!error) binding.buttonDone.isVisible = true
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_CREATE = "create"
        const val MODE_JOIN = "join"
    }
}
