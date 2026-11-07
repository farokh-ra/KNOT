package dev.twoperson.messenger.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.Menu
import android.view.MenuItem
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.twoperson.messenger.App
import dev.twoperson.messenger.R
import dev.twoperson.messenger.data.Message
import dev.twoperson.messenger.data.MessageDirection
import dev.twoperson.messenger.data.MessageKind
import dev.twoperson.messenger.data.MessageState
import dev.twoperson.messenger.databinding.ActivityChatBinding
import dev.twoperson.messenger.engine.ConnectionState
import dev.twoperson.messenger.engine.ConnectionStatus
import dev.twoperson.messenger.engine.DirectKind
import dev.twoperson.messenger.protocol.AppCodec
import dev.twoperson.messenger.protocol.Protocol
import dev.twoperson.messenger.service.ConnectionService
import dev.twoperson.messenger.service.MessageNotifications
import dev.twoperson.messenger.util.AppVisibility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private lateinit var adapter: ChatAdapter
    private val container get() = (application as App).container

    private var initialMessagesLoaded = false
    private var autoScrollTarget: Int? = null
    private var latestStatus = ConnectionStatus()
    private var peerName: String? = null

    private val openFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) queueFiles(uris)
    }

    private var pendingSave: Message? = null
    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val message = pendingSave
            pendingSave = null
            if (granted && message != null) {
                saveToDownloadsNow(message)
            } else if (!granted) {
                Toast.makeText(
                    this,
                    R.string.chat_file_save_permission,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.chat_title)

        adapter = ChatAdapter(
            onFileClick = ::openFile,
            onMessageLongClick = ::showMessageActions
        )
        binding.listMessages.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.listMessages.adapter = adapter
        binding.listMessages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updateJumpButton()
            }
        })

        binding.buttonSend.setOnClickListener { send() }
        binding.inputMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                send()
                true
            } else {
                false
            }
        }
        binding.buttonAttach.setOnClickListener { pickFiles() }
        binding.buttonScrollBottom.setOnClickListener { scrollToBottom(smooth = true) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { container.repository.messages.collect { renderMessages(it) } }
                launch { container.engine.status.collect { renderStatus(it) } }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.onActivityStarted()
        if (container.engine.isConfigured()) ConnectionService.ensureRunning(this)
        MessageNotifications.dismissIncoming(this)
        lifecycleScope.launch {
            container.repository.markIncomingRead()
            refreshPeerTitle()
        }
    }

    override fun onStop() {
        AppVisibility.onActivityStopped()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { refreshPeerTitle() }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.chat, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.actionClear -> {
            confirmClear()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private fun renderMessages(messages: List<Message>) {
        val firstLoad = !initialMessagesLoaded
        initialMessagesLoaded = true
        updateKeepScreenOn()
        val target = autoScrollTarget
        val outgoingCount = messages.count { it.direction == MessageDirection.OUTGOING }
        val shouldScroll = firstLoad || (target != null && outgoingCount >= target)
        if (shouldScroll) autoScrollTarget = null

        adapter.submit(messages) {
            binding.textEmpty.isVisible = messages.isEmpty()
            if (shouldScroll && messages.isNotEmpty()) {
                scrollToBottom(smooth = !firstLoad)
            }
            binding.listMessages.post { updateJumpButton() }
        }
        binding.textEmpty.isVisible = messages.isEmpty()

        if (messages.any { it.direction == MessageDirection.INCOMING && !it.isRead }) {
            lifecycleScope.launch {
                container.repository.markIncomingRead()
                MessageNotifications.dismissIncoming(this@ChatActivity)
            }
        }
    }

    /** Arm the next list update for the outgoing message this send is about to insert. */
    private fun armAutoScroll() {
        autoScrollTarget = container.repository.messages.value
            .count { it.direction == MessageDirection.OUTGOING } + 1
    }

    private fun scrollToBottom(smooth: Boolean) {
        if (adapter.itemCount == 0) return
        val target = adapter.itemCount - 1
        if (smooth) {
            binding.listMessages.smoothScrollToPosition(target)
        } else {
            binding.listMessages.scrollToPosition(target)
        }
        binding.buttonScrollBottom.isVisible = false
    }

    private fun updateJumpButton() {
        if (adapter.itemCount == 0) {
            binding.buttonScrollBottom.isVisible = false
            return
        }
        val layoutManager = binding.listMessages.layoutManager as? LinearLayoutManager ?: return
        val lastVisible = layoutManager.findLastVisibleItemPosition()
        binding.buttonScrollBottom.isVisible = lastVisible < adapter.itemCount - 1
    }

    private fun send() {
        val raw = binding.inputMessage.text?.toString().orEmpty()
        if (raw.isBlank()) return
        if (raw.trim().codePointCount(0, raw.trim().length) > Protocol.MAX_TEXT_CODE_POINTS) {
            binding.inputMessageLayout.error = getString(R.string.chat_too_long)
            return
        }
        if (!container.engine.isConfigured()) {
            binding.inputMessageLayout.error = getString(R.string.chat_not_paired)
            return
        }
        binding.inputMessageLayout.error = null
        armAutoScroll()
        lifecycleScope.launch {
            val queued = container.engine.sendText(raw)
            if (queued) {
                binding.inputMessage.setText("")
            } else {
                binding.inputMessageLayout.error = getString(R.string.chat_too_long)
            }
        }
    }

    private fun pickFiles() {
        if (!container.engine.isConfigured()) {
            Toast.makeText(this, R.string.chat_not_paired, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            openFiles.launch(arrayOf("*/*"))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.chat_attach_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun queueFiles(uris: List<Uri>) {
        lifecycleScope.launch {
            var queued = 0
            var failed = 0
            var tooLarge = 0
            for (uri in uris) {
                try {
                    val meta = withContext(Dispatchers.IO) { resolveFileMeta(uri) }
                    if (meta.size > Protocol.MAX_FILE_BYTES) {
                        tooLarge++
                        continue
                    }
                    persistReadPermission(uri)
                    armAutoScroll()
                    val accepted = container.engine.sendFile(uri, meta.name, meta.mime, meta.size)
                    if (accepted) queued++ else failed++
                } catch (e: Exception) {
                    // A revoked grant or provider crash must not skip the remaining files.
                    failed++
                }
            }
            if (queued > 0) {
                Toast.makeText(
                    this@ChatActivity,
                    resources.getQuantityString(R.plurals.chat_attach_started, queued, queued),
                    Toast.LENGTH_SHORT
                ).show()
            }
            if (tooLarge > 0) {
                Toast.makeText(this@ChatActivity, R.string.chat_attach_too_large, Toast.LENGTH_LONG).show()
            }
            if (failed > 0) {
                Toast.makeText(this@ChatActivity, R.string.chat_attach_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun persistReadPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            // Some document providers do not offer persistable grants. The file can still be
            // read for this process; if the app is killed the message will report unavailable.
        }
    }

    private fun resolveFileMeta(uri: Uri): FileMeta {
        var name: String? = null
        var size = -1L
        try {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        } catch (e: Exception) {
            // Fall back to the URI segment and an unknown size below.
        }
        if (name.isNullOrBlank()) {
            name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        }
        val mime = try {
            contentResolver.getType(uri)
        } catch (e: Exception) {
            null
        }?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        return FileMeta(name ?: "file", mime, size)
    }

    private fun showMessageActions(message: Message) {
        val actions = ArrayList<Pair<String, () -> Unit>>()
        if (message.kind == MessageKind.TEXT) {
            actions += getString(R.string.chat_action_copy) to { copyText(message.body) }
        }
        if (message.kind == MessageKind.FILE && message.state == MessageState.DELIVERED) {
            if (localUriFor(message) != null) {
                actions += getString(R.string.chat_action_open) to { openFile(message) }
                actions += getString(R.string.chat_action_share) to { shareFile(message) }
            }
            actions += getString(R.string.chat_action_save) to { saveToDownloads(message) }
        }
        if (message.direction == MessageDirection.OUTGOING && message.state == MessageState.FAILED) {
            actions += getString(R.string.chat_action_retry) to { retryMessage(message) }
        }
        actions += getString(R.string.chat_action_delete) to { confirmDelete(message) }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.chat_message_actions)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions[which].second()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun copyText(text: String) {
        try {
            val clipboard = getSystemService(ClipboardManager::class.java) ?: return
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            // A locked-down device can refuse clipboard access; nothing to do.
        }
    }

    private fun retryMessage(message: Message) {
        lifecycleScope.launch {
            val retried = container.engine.retryMessage(message.id)
            Toast.makeText(
                this@ChatActivity,
                if (retried) R.string.chat_retry_started else R.string.chat_retry_failed,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun confirmDelete(message: Message) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.chat_delete_confirm_title)
            .setMessage(R.string.chat_delete_confirm_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.chat_delete_confirm_ok) { _, _ ->
                lifecycleScope.launch { container.engine.deleteMessage(message.id) }
            }
            .show()
    }

    private fun saveToDownloads(message: Message) {
        if (message.kind != MessageKind.FILE || message.state != MessageState.DELIVERED) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingSave = message
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        saveToDownloadsNow(message)
    }

    private fun saveToDownloadsNow(message: Message) {
        val name = AppCodec.sanitizeFileName(message.fileName ?: message.body) ?: "file"
        val mime = message.mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) { copyToDownloads(message, name, mime) }
            Toast.makeText(
                this@ChatActivity,
                if (saved) R.string.chat_file_saved else R.string.chat_file_save_failed,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun copyToDownloads(message: Message, name: String, mime: String): Boolean = try {
        val input = openMessageStream(message) ?: return false
        input.use { source ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/Knot"
                    )
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return false
                try {
                    val output = resolver.openOutputStream(uri)
                    if (output == null) {
                        resolver.delete(uri, null, null)
                        return false
                    }
                    output.use { source.copyTo(it) }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    true
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    false
                }
            } else {
                val directory = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "Knot"
                )
                if (!directory.isDirectory && !directory.mkdirs()) return false
                val target = uniqueDownloadFile(directory, name)
                target.outputStream().use { source.copyTo(it) }
                true
            }
        }
    } catch (e: Exception) {
        false
    }

    private fun openMessageStream(message: Message): InputStream? {
        val path = message.filePath
        if (!path.isNullOrBlank()) {
            val file = File(path)
            if (file.isFile) {
                try {
                    return file.inputStream()
                } catch (e: Exception) {
                    // Fall through to the original URI.
                }
            }
        }
        val raw = message.fileUri ?: return null
        return try {
            contentResolver.openInputStream(Uri.parse(raw))
        } catch (e: Exception) {
            null
        }
    }

    private fun uniqueDownloadFile(directory: File, name: String): File {
        val safe = name.replace('/', '_').replace('\\', '_').ifBlank { "file" }
        var candidate = File(directory, safe)
        if (!candidate.exists()) return candidate
        val dot = safe.lastIndexOf('.')
        val base = if (dot > 0) safe.substring(0, dot) else safe
        val extension = if (dot > 0) safe.substring(dot) else ""
        var counter = 1
        while (candidate.exists()) {
            candidate = File(directory, "$base ($counter)$extension")
            counter++
        }
        return candidate
    }

    private fun openFile(message: Message) {
        if (message.kind != MessageKind.FILE) return
        if (message.state != MessageState.DELIVERED) {
            Toast.makeText(this, R.string.chat_file_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        val uri = localUriFor(message)
        if (uri == null) {
            Toast.makeText(this, R.string.chat_file_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val mime = message.mimeType?.takeIf { it.isNotBlank() }
                ?: contentResolver.getType(uri)
                ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri(message.fileName ?: "file", uri)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.chat_file_open)))
        } catch (e: Exception) {
            // Includes ActivityNotFoundException and a revoked provider grant (SecurityException).
            Toast.makeText(this, R.string.chat_file_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareFile(message: Message) {
        if (message.kind != MessageKind.FILE) return
        if (message.state != MessageState.DELIVERED) {
            Toast.makeText(this, R.string.chat_file_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        val uri = localUriFor(message)
        if (uri == null) {
            Toast.makeText(this, R.string.chat_file_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val mime = message.mimeType?.takeIf { it.isNotBlank() }
                ?: contentResolver.getType(uri)
                ?: "application/octet-stream"
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri(message.fileName ?: "file", uri)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.chat_file_share)))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.chat_file_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun localUriFor(message: Message): Uri? {
        try {
            val path = message.filePath
            if (!path.isNullOrBlank()) {
                val file = File(path)
                if (file.isFile) {
                    try {
                        return FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                    } catch (e: Exception) {
                        // Fall through to the original SAF URI, if there is one.
                    }
                }
            }
            val raw = message.fileUri ?: return null
            return Uri.parse(raw)
        } catch (e: Exception) {
            // A provider can throw SecurityException if its grant was revoked.
            return null
        }
    }

    private suspend fun refreshPeerTitle() {
        val peer = container.repository.peer()
        peerName = peer?.label?.takeIf { it.isNotBlank() }
            ?: peer?.displayName?.takeIf { it.isNotBlank() }
        supportActionBar?.title = peerName ?: getString(R.string.chat_title)
        supportActionBar?.subtitle = statusSubtitle(latestStatus)
    }

    /**
     * A transfer should not be interrupted by the screen turning off while it can actually make
     * progress. Queued files on an offline link must not keep the screen awake indefinitely.
     */
    private fun updateKeepScreenOn() {
        binding.root.keepScreenOn = latestStatus.state == ConnectionState.CONNECTED &&
            container.repository.messages.value.any {
                it.kind == MessageKind.FILE && it.state == MessageState.PENDING
            }
    }

    private fun renderStatus(status: ConnectionStatus) {
        latestStatus = status
        updateKeepScreenOn()
        supportActionBar?.subtitle = statusSubtitle(status)
        if (peerName == null) {
            status.peerName?.takeIf { it.isNotBlank() }?.let {
                supportActionBar?.title = it
            }
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

    private fun statusSubtitle(status: ConnectionStatus): String? = when (status.state) {
        ConnectionState.CONNECTED -> getString(
            if (status.direct == DirectKind.LOCAL_NETWORK) {
                R.string.state_connected_lan
            } else {
                R.string.state_connected_internet
            }
        )

        ConnectionState.DISCOVERING, ConnectionState.CONNECTING -> getString(R.string.state_discovering)
        ConnectionState.DISCONNECTED -> getString(R.string.state_disconnected)
        ConnectionState.UNABLE_TO_CONNECT -> getString(R.string.state_unable)
        ConnectionState.NOT_PAIRED -> getString(R.string.state_not_paired)
        ConnectionState.IDLE -> getString(R.string.state_idle)
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

    private data class FileMeta(val name: String, val mime: String, val size: Long)
}
