package dev.twoperson.messenger.engine

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.StatFs
import dev.twoperson.messenger.crypto.Crypto
import dev.twoperson.messenger.data.Message
import dev.twoperson.messenger.data.MessageDirection
import dev.twoperson.messenger.data.MessageKind
import dev.twoperson.messenger.data.MessageRepository
import dev.twoperson.messenger.data.MessageState
import dev.twoperson.messenger.net.PeerLink
import dev.twoperson.messenger.protocol.AppCodec
import dev.twoperson.messenger.protocol.AppMessage
import dev.twoperson.messenger.protocol.FileDigest
import dev.twoperson.messenger.protocol.Protocol
import dev.twoperson.messenger.util.Hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * Streams files of any size over the one authenticated peer link.
 *
 * Design:
 *  - file bytes are read from disk in 16 KiB blocks and never held in memory as a whole;
 *  - blocks use the same reliable, ordered, encrypted channel as text, so packet loss,
 *    reordering and NAT rebinds are handled by the transport;
 *  - the receiver persists byte progress and an FNV-1a digest of the bytes it holds; the
 *    sender resumes from that durable offset without re-reading the prefix;
 *  - the sender waits for a durable acknowledgement before its in-flight window advances, so a
 *    fast sender cannot exhaust the receiver's memory or disk;
 *  - the receiver verifies the whole-file digest before publishing the file. A mismatch is
 *    reported as an integrity failure, the part file is discarded, and the sender retries;
 *  - text messages take priority over bulk data.
 *
 * There is intentionally no relay and no server: a file only moves while the two devices have
 * a direct connection.
 */
class FileTransferManager(
    context: Context,
    private val repository: MessageRepository,
    private val scope: CoroutineScope,
    private val onIncomingFileReady: suspend (Message) -> Unit
) {

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val incomingDir: File = File(appContext.filesDir, "incoming").apply { mkdirs() }
    private val transferDir: File = File(appContext.filesDir, "transfers").apply { mkdirs() }
    private val outgoingCopyDir: File = File(appContext.filesDir, "outgoing").apply { mkdirs() }

    private enum class Outcome { FINISHED, ABORTED, INTEGRITY_RETRY }

    private class SendSession(
        val transferId: String,
        val messageId: String,
        val totalBytes: Long,
        val fileName: String,
        val mimeType: String
    ) {
        @Volatile
        var remoteBytes: Long = -1L

        /** Receiver's running digest at [remoteBytes]; adopted when a transfer resumes. */
        @Volatile
        var remoteDigest: Long = FileDigest.OFFSET_BASIS

        @Volatile
        var lastAckAt: Long = 0L

        @Volatile
        var done: Boolean = false

        @Volatile
        var rejected: Boolean = false

        @Volatile
        var failureReason: Int = 0

        val signal = Channel<Unit>(Channel.CONFLATED)

        fun hasAck(): Boolean = remoteBytes >= 0

        fun signalChange() {
            signal.trySend(Unit)
        }
    }

    private class IncomingSession(
        val transferId: String,
        val messageId: String,
        val totalBytes: Long,
        val partFile: File,
        var expectedBytes: Long,
        var digest: Long
    ) {
        var output: OutputStream? = null
        var lastPersistedBytes: Long = expectedBytes
        var lastPersistedAt: Long = System.currentTimeMillis()
        var lastAckBytes: Long = expectedBytes
        var lastAckAt: Long = System.currentTimeMillis()
    }

    private val sessions = ConcurrentHashMap<String, SendSession>()
    private val incoming = ConcurrentHashMap<String, IncomingSession>()

    /** Serializes incoming state changes against a history clear from the UI. */
    private val incomingMutex = Mutex()
    private val linkLock = Any()

    @Volatile
    private var activeLink: PeerLink? = null

    @Volatile
    private var outgoingJob: Job? = null

    @Volatile
    private var outgoingLink: PeerLink? = null

    /**
     * Inserts the outgoing file message and starts sending as soon as a link is ready.
     * Returns null only when the source cannot be read at all.
     */
    suspend fun enqueueFile(
        uri: Uri,
        displayName: String,
        mimeType: String,
        reportedSize: Long
    ): Message? {
        // Known oversize files are rejected before a message row is created. A provider that
        // reports no size is checked during the bounded copy below.
        if (reportedSize > Protocol.MAX_FILE_BYTES) return null
        val safeName = AppCodec.sanitizeFileName(displayName) ?: "file"
        val safeMime = AppCodec.sanitizeMimeType(mimeType)
        val now = System.currentTimeMillis()
        val sequence = repository.nextSequence()
        val messageId = Hex.encode(Crypto.randomBytes(Protocol.MESSAGE_ID_BYTES))
        val transferId = Hex.encode(Crypto.randomBytes(Protocol.TRANSFER_ID_BYTES))

        var size = reportedSize
        var sourcePath: String? = null
        var sourceUri: String? = uri.toString()

        if (size < 0) {
            // Some document providers do not expose a size. Copy once to app storage so the
            // transfer can resume and the offer can carry an exact length. This runs off the
            // caller's (usually main) thread because the file can be arbitrarily large.
            val copied = withContext(Dispatchers.IO) { copyToOutgoing(uri, transferId) }
                ?: return null
            sourcePath = copied.absolutePath
            sourceUri = null
            size = copied.length()
        }

        val message = repository.insertOutgoingFile(
            id = messageId,
            transferId = transferId,
            fileName = safeName,
            mimeType = safeMime,
            size = size,
            fileUri = sourceUri,
            filePath = sourcePath,
            createdAt = now,
            sequence = sequence
        )
        // Tell the currently connected peer about the new queue entry immediately.
        synchronized(linkLock) { activeLink }?.let { startSending(it) }
        return message
    }

    /** Called when a direct link becomes ready. Resumes every queued outgoing file. */
    fun onLinkReady(link: PeerLink) {
        synchronized(linkLock) {
            activeLink = link
            startSendingLocked(link)
        }
    }

    fun onLinkClosed(link: PeerLink) {
        synchronized(linkLock) {
            if (activeLink === link) activeLink = null
            if (outgoingLink === link) {
                val job = outgoingJob
                outgoingJob = null
                outgoingLink = null
                job?.cancel()
            }
        }
    }

    private fun startSending(link: PeerLink) {
        synchronized(linkLock) { startSendingLocked(link) }
    }

    private fun startSendingLocked(link: PeerLink) {
        val running = outgoingJob
        if (running?.isActive == true) {
            if (outgoingLink === link) return
            running.cancel()
        }
        outgoingLink = link
        outgoingJob = scope.launch {
            try {
                while (link.isReady) {
                    val next = repository.pendingFileOutgoing()
                        .firstOrNull { it.state != MessageState.FAILED }
                    if (next == null) {
                        // Stay alive so a file enqueued just after the queue looked empty still
                        // starts without waiting for a reconnect.
                        delay(FILE_QUEUE_IDLE_MS)
                        continue
                    }
                    sendOne(link, next)
                    if (!link.isReady) break
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A transfer failure must never kill the connection or the engine.
            }
        }
    }

    private suspend fun sendOne(link: PeerLink, message: Message) {
        var integrityRetries = 0
        while (link.isReady) {
            when (sendAttempt(link, message)) {
                Outcome.FINISHED, Outcome.ABORTED -> return
                Outcome.INTEGRITY_RETRY -> {
                    integrityRetries++
                    if (integrityRetries > MAX_INTEGRITY_RETRIES) {
                        markFailed(message)
                        return
                    }
                    // The receiver discarded its part file and will answer the next offer
                    // with offset zero. Let its final acknowledgement drain first.
                    delay(INTEGRITY_RETRY_PAUSE_MS)
                }
            }
        }
    }

    private suspend fun sendAttempt(link: PeerLink, message: Message): Outcome {
        val transferId = message.transferId ?: return markFailed(message)
        val transferIdBytes = Hex.decode(transferId) ?: return markFailed(message)
        val messageIdBytes = Hex.decode(message.id) ?: return markFailed(message)
        val session = SendSession(
            transferId = transferId,
            messageId = message.id,
            totalBytes = message.fileSize,
            fileName = message.fileName ?: message.body,
            mimeType = message.mimeType ?: "application/octet-stream"
        )
        sessions[transferId] = session
        var source: SourceStream? = null
        try {
            val offer = AppMessage.FileOffer(
                transferId = transferIdBytes,
                messageId = messageIdBytes,
                sequence = message.sequence,
                createdAt = message.createdAt,
                totalBytes = message.fileSize,
                fileName = session.fileName,
                mimeType = session.mimeType
            )

            // Offer / resume handshake. The receiver answers with its durable byte count and
            // the digest of those bytes.
            val offerDeadline = System.currentTimeMillis() + OFFER_TIMEOUT_MS
            while (link.isReady && !session.hasAck() && !session.rejected &&
                System.currentTimeMillis() < offerDeadline
            ) {
                link.sendApplicationMessage(offer)
                waitFor(session, OFFER_RETRY_MS)
            }
            if (session.rejected) {
                return finishRejected(message, session)
            }
            if (!session.hasAck()) {
                if (link.isReady) markFailed(message)
                return Outcome.ABORTED
            }
            if (!link.isReady) return Outcome.ABORTED

            var offset = session.remoteBytes.coerceAtLeast(0)
            if (offset > message.fileSize) {
                markFailed(message)
                return Outcome.ABORTED
            }
            // A receiver that reports a non-zero offset must also report the digest of that
            // prefix. If it cannot (old row, corrupt state), restart from zero instead of
            // guessing and producing a wrong file.
            var digest = if (offset == 0L) {
                FileDigest.OFFSET_BASIS
            } else {
                session.remoteDigest.takeIf { it != 0L } ?: run {
                    offset = 0L
                    FileDigest.OFFSET_BASIS
                }
            }

            if (offset == message.fileSize) {
                return awaitCompletion(link, session, digest)
            }

            source = openSource(message, offset)
            if (source == null) {
                markFailed(message)
                return Outcome.ABORTED
            }

            var lastPriorityCheck = 0L
            var textWaiting = false
            var pendingBlock: ByteArray? = null
            while (link.isReady) {
                val now = System.currentTimeMillis()
                if (now - lastPriorityCheck > TEXT_PRIORITY_CHECK_MS) {
                    textWaiting = repository.pendingTextOutgoing().isNotEmpty()
                    lastPriorityCheck = now
                }
                if (textWaiting) {
                    delay(TEXT_PRIORITY_PAUSE_MS)
                    continue
                }
                if (session.rejected) {
                    return finishRejected(message, session)
                }

                // Receiver-side flow control: never run too far ahead of durable storage.
                val acked = session.remoteBytes.coerceAtLeast(0)
                if (offset - acked > MAX_IN_FLIGHT_BYTES) {
                    waitFor(session, FLOW_CONTROL_POLL_MS)
                    continue
                }

                val remaining = message.fileSize - offset
                if (remaining <= 0) break
                val wanted = minOf(remaining, Protocol.MAX_FILE_DATA_BYTES.toLong()).toInt()
                // Keep a block that could not be queued: the source stream has already
                // advanced, so re-reading would skip bytes and corrupt the file.
                val block = pendingBlock ?: source.readBlock(wanted)?.also { pendingBlock = it }
                if (block == null) {
                    markFailed(message)
                    return Outcome.ABORTED
                }
                val data = AppMessage.FileData(transferIdBytes, offset, block)
                if (!link.sendApplicationMessage(data)) {
                    // Reliable window is full; the block was not queued. Retry the same bytes.
                    if (!link.isReady) return Outcome.ABORTED
                    delay(SEND_BACKPRESSURE_MS)
                    continue
                }
                pendingBlock = null
                digest = FileDigest.update(digest, block)
                offset += block.size
            }
            if (!link.isReady) return Outcome.ABORTED
            if (offset != message.fileSize) {
                markFailed(message)
                return Outcome.ABORTED
            }
            return awaitCompletion(link, session, digest)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (link.isReady) markFailed(message)
            return Outcome.ABORTED
        } finally {
            source?.close()
            // Remove only this attempt: a reconnect may already have started a new session
            // with the same transfer id, and the old coroutine must not evict it.
            sessions.remove(transferId, session)
        }
    }

    private suspend fun finishRejected(message: Message, session: SendSession): Outcome {
        if (session.failureReason == Protocol.FILE_DONE_INTEGRITY) {
            return Outcome.INTEGRITY_RETRY
        }
        markFailed(message)
        return Outcome.ABORTED
    }

    private suspend fun awaitCompletion(
        link: PeerLink,
        session: SendSession,
        digest: Long
    ): Outcome {
        val transferId = Hex.decode(session.transferId) ?: return Outcome.ABORTED
        val end = AppMessage.FileEnd(transferId, digest)
        val deadline = System.currentTimeMillis() + COMPLETION_TIMEOUT_MS
        while (link.isReady && !session.done && !session.rejected &&
            System.currentTimeMillis() < deadline
        ) {
            link.sendApplicationMessage(end)
            waitFor(session, END_RETRY_MS)
        }
        if (session.done) {
            repository.completeOutgoingFile(session.messageId)
            return Outcome.FINISHED
        }
        if (session.rejected) {
            return finishRejected(repository.messageById(session.messageId) ?: return Outcome.ABORTED, session)
        }
        // Receiver went silent despite a healthy link. Keep the message queued: a later
        // connection will resume it from the receiver's durable offset.
        if (link.isReady) markFailed(repository.messageById(session.messageId) ?: return Outcome.ABORTED)
        return Outcome.ABORTED
    }

    private suspend fun waitFor(session: SendSession, timeoutMs: Long) {
        try {
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs.coerceAtLeast(1)) {
                session.signal.receive()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
        }
    }

    /**
     * Handles an application message. The engine calls this from one serial worker, so file
     * blocks are written in exactly the order the reliable transport delivered them.
     */
    suspend fun handle(message: AppMessage, link: PeerLink?) {
        when (message) {
            is AppMessage.FileOffer -> incomingMutex.withLock { handleOffer(message, link) }
            is AppMessage.FileData -> incomingMutex.withLock { handleData(message, link) }
            is AppMessage.FileEnd -> incomingMutex.withLock { handleEnd(message, link) }
            is AppMessage.FileAck -> handleAck(message)
            is AppMessage.FileDone -> handleDone(message)
            else -> Unit
        }
    }

    /**
     * Drops all in-memory transfer state. Called before the history is deleted so no worker can
     * re-create or complete a row afterwards, and so stale part files do not leak disk space.
     */
    suspend fun cancelAll() {
        synchronized(linkLock) {
            outgoingJob?.cancel()
            outgoingJob = null
            outgoingLink = null
            sessions.clear()
        }
        incomingMutex.withLock {
            for (session in incoming.values) {
                try {
                    session.output?.close()
                } catch (e: Exception) {
                }
                session.output = null
            }
            incoming.clear()
            transferDir.listFiles()?.forEach { file ->
                try {
                    file.delete()
                } catch (e: Exception) {
                }
            }
        }
    }

    /** Deletes app-private copies belonging to rows that were just removed from the history. */
    fun deleteLocalFiles(messages: List<Message>) {
        val paths = messages.asSequence()
            .filter { it.kind == MessageKind.FILE }
            .mapNotNull { it.filePath }
            .toSet()
        for (path in paths) {
            try {
                File(path).delete()
            } catch (e: Exception) {
            }
        }
    }

    /**
     * Stops an in-flight transfer whose message the user just deleted. The sender stops
     * streaming and the receiver drops its part file; a rejection is sent so the other side
     * does not wait for an acknowledgement that will never come.
     */
    suspend fun cancelMessage(message: Message) {
        val transferId = message.transferId ?: return
        sessions.remove(transferId)?.let { session ->
            session.rejected = true
            session.failureReason = Protocol.FILE_DONE_REJECTED
            session.signalChange()
        }
        incomingMutex.withLock {
            incoming.remove(transferId)?.let { session ->
                try {
                    session.output?.close()
                } catch (e: Exception) {
                }
                session.output = null
                try {
                    session.partFile.delete()
                } catch (e: Exception) {
                }
            }
        }
        Hex.decode(transferId)?.let { bytes ->
            val link = synchronized(linkLock) { activeLink }
            link?.sendApplicationMessage(
                AppMessage.FileDone(bytes, false, Protocol.FILE_DONE_REJECTED)
            )
        }
    }

    private suspend fun handleOffer(offer: AppMessage.FileOffer, link: PeerLink?) {
        val transferId = Hex.encode(offer.transferId)
        if (offer.totalBytes > Protocol.MAX_FILE_BYTES) {
            link?.sendApplicationMessage(
                AppMessage.FileDone(offer.transferId, false, Protocol.FILE_DONE_REJECTED)
            )
            return
        }
        var message = repository.fileMessageByTransferId(transferId)

        if (message != null && message.direction != MessageDirection.INCOMING) return

        if (message != null && message.state == MessageState.DELIVERED) {
            val digest = message.transferDigest.takeIf { it != 0L } ?: FileDigest.OFFSET_BASIS
            link?.sendApplicationMessage(AppMessage.FileAck(offer.transferId, offer.totalBytes, digest))
            link?.sendApplicationMessage(AppMessage.FileDone(offer.transferId, true, 0))
            return
        }

        val partFile = File(transferDir, "$transferId.part")
        if (partFile.isFile && partFile.length() > offer.totalBytes) {
            truncate(partFile, 0)
        }

        if (message == null) {
            // Check disk before creating a visible message that could never complete.
            if (!hasFreeSpace(offer.totalBytes)) {
                link?.sendApplicationMessage(
                    AppMessage.FileDone(offer.transferId, false, Protocol.FILE_DONE_REJECTED)
                )
                return
            }
            repository.insertIncomingFile(
                id = Hex.encode(offer.messageId),
                transferId = transferId,
                fileName = offer.fileName,
                mimeType = offer.mimeType,
                size = offer.totalBytes,
                createdAt = offer.createdAt,
                sequence = offer.sequence,
                senderId = repository.peer()?.deviceId ?: "peer"
            )
            message = repository.fileMessageByTransferId(transferId)
            if (message == null) {
                link?.sendApplicationMessage(
                    AppMessage.FileDone(offer.transferId, false, Protocol.FILE_DONE_REJECTED)
                )
                return
            }
        }

        if (message.fileSize != offer.totalBytes) {
            link?.sendApplicationMessage(
                AppMessage.FileDone(offer.transferId, false, Protocol.FILE_DONE_REJECTED)
            )
            return
        }

        val existing = incoming[transferId]
        if (existing != null) {
            // Reconcile with what is actually on disk. Bytes written after the last durable
            // checkpoint are discarded so the persisted digest stays valid; a short file is
            // hashed again from zero.
            flushIncoming(existing)
            val onDisk = if (partFile.isFile) partFile.length() else 0L
            if (onDisk > existing.expectedBytes) {
                truncate(partFile, existing.expectedBytes)
            } else if (onDisk < existing.expectedBytes) {
                existing.expectedBytes = onDisk
                existing.digest = computeDigest(partFile, onDisk) ?: FileDigest.OFFSET_BASIS
            }
            existing.lastAckBytes = existing.expectedBytes
            existing.lastAckAt = System.currentTimeMillis()
            existing.lastPersistedBytes = existing.expectedBytes
            existing.lastPersistedAt = existing.lastAckAt
            repository.setFileProgress(
                existing.messageId,
                existing.expectedBytes,
                existing.digest,
                allowRewind = true
            )
            link?.sendApplicationMessage(
                AppMessage.FileAck(offer.transferId, existing.expectedBytes, existing.digest)
            )
            return
        }

        val prepared = prepareIncoming(message, partFile, offer.totalBytes)
        if (prepared == null) {
            link?.sendApplicationMessage(
                AppMessage.FileDone(offer.transferId, false, Protocol.FILE_DONE_REJECTED)
            )
            return
        }
        val (expected, digest) = prepared
        if (!hasFreeSpace(offer.totalBytes - expected)) {
            link?.sendApplicationMessage(
                AppMessage.FileDone(offer.transferId, false, Protocol.FILE_DONE_REJECTED)
            )
            return
        }
        if (offer.totalBytes == 0L) {
            try {
                partFile.createNewFile()
            } catch (e: Exception) {
            }
        }
        repository.setIncomingPending(message.id)
        val session = IncomingSession(
            transferId = transferId,
            messageId = message.id,
            totalBytes = offer.totalBytes,
            partFile = partFile,
            expectedBytes = expected,
            digest = digest
        )
        incoming[transferId] = session
        repository.setFileProgress(message.id, expected, digest, allowRewind = true)
        link?.sendApplicationMessage(AppMessage.FileAck(offer.transferId, expected, digest))
    }

    private suspend fun handleData(data: AppMessage.FileData, link: PeerLink?) {
        val transferId = Hex.encode(data.transferId)
        val session = incoming[transferId] ?: return
        if (data.offset < session.expectedBytes) {
            // Duplicate after a lost acknowledgement; the stream is already written.
            link?.sendApplicationMessage(
                AppMessage.FileAck(data.transferId, session.expectedBytes, session.digest)
            )
            return
        }
        if (data.offset > session.expectedBytes) {
            // A gap should be impossible on the ordered channel; ask for the known offset.
            link?.sendApplicationMessage(
                AppMessage.FileAck(data.transferId, session.expectedBytes, session.digest)
            )
            return
        }
        val remaining = session.totalBytes - session.expectedBytes
        if (remaining <= 0 || data.data.size > remaining) {
            link?.sendApplicationMessage(
                AppMessage.FileAck(data.transferId, session.expectedBytes, session.digest)
            )
            return
        }
        try {
            val output = session.output ?: BufferedOutputStream(
                FileOutputStream(session.partFile, true),
                OUTPUT_BUFFER_BYTES
            ).also { session.output = it }
            output.write(data.data)
        } catch (e: Exception) {
            // Disk full or a provider/FS error: stop this transfer honestly instead of
            // letting the sender wait for an acknowledgement that will never come.
            failIncoming(session, link)
            return
        }
        session.expectedBytes += data.data.size
        session.digest = FileDigest.update(session.digest, data.data)

        val now = System.currentTimeMillis()
        if (session.expectedBytes - session.lastPersistedBytes >= PERSIST_STEP_BYTES ||
            now - session.lastPersistedAt >= PERSIST_INTERVAL_MS
        ) {
            repository.setFileProgress(session.messageId, session.expectedBytes, session.digest)
            session.lastPersistedBytes = session.expectedBytes
            session.lastPersistedAt = now
        }

        if (session.expectedBytes - session.lastAckBytes >= ACK_STEP_BYTES ||
            now - session.lastAckAt >= ACK_INTERVAL_MS
        ) {
            flushIncoming(session)
            link?.sendApplicationMessage(
                AppMessage.FileAck(data.transferId, session.expectedBytes, session.digest)
            )
            session.lastAckBytes = session.expectedBytes
            session.lastAckAt = now
        }
    }

    private suspend fun handleEnd(end: AppMessage.FileEnd, link: PeerLink?) {
        val transferId = Hex.encode(end.transferId)
        val session = incoming[transferId]
        if (session == null) {
            val message = repository.fileMessageByTransferId(transferId)
            when {
                message == null -> link?.sendApplicationMessage(
                    AppMessage.FileAck(end.transferId, 0, FileDigest.OFFSET_BASIS)
                )

                message.state == MessageState.DELIVERED ->
                    link?.sendApplicationMessage(AppMessage.FileDone(end.transferId, true, 0))

                else -> {
                    val partFile = File(transferDir, "$transferId.part")
                    if (partFile.isFile && partFile.length() == message.fileSize) {
                        val digest = computeDigest(partFile, message.fileSize)
                        if (digest != null && digest == end.digest) {
                            finalizeIncoming(
                                message, partFile, message.fileSize, digest, link, end.transferId
                            )
                        } else {
                            resetIncomingIntegrity(message, partFile, link, end.transferId)
                        }
                    } else {
                        // Without an in-memory session this link cannot continue the file.
                        // Reject it here; the sender retries from its durable offset after
                        // the next reconnect instead of waiting for a completion timeout.
                        link?.sendApplicationMessage(
                            AppMessage.FileDone(end.transferId, false, Protocol.FILE_DONE_REJECTED)
                        )
                    }
                }
            }
            return
        }
        flushIncoming(session)
        if (session.expectedBytes != session.totalBytes) {
            link?.sendApplicationMessage(
                AppMessage.FileAck(end.transferId, session.expectedBytes, session.digest)
            )
            return
        }
        val message = repository.messageById(session.messageId)
        if (message == null) {
            link?.sendApplicationMessage(
                AppMessage.FileDone(end.transferId, false, Protocol.FILE_DONE_REJECTED)
            )
            return
        }
        if (session.digest != end.digest) {
            resetIncomingIntegrity(message, session.partFile, link, end.transferId)
            incoming.remove(transferId, session)
            return
        }
        if (finalizeIncoming(
                message, session.partFile, session.totalBytes, session.digest, link, end.transferId
            )
        ) {
            incoming.remove(transferId, session)
        }
    }

    /** Moves a fully received part file into place and tells the sender it is stored. */
    private suspend fun finalizeIncoming(
        message: Message,
        partFile: File,
        totalBytes: Long,
        digest: Long,
        link: PeerLink?,
        transferId: ByteArray
    ): Boolean {
        val finalFile = uniqueDestination(message.fileName ?: message.body)
        val moved = try {
            partFile.renameTo(finalFile) || run {
                partFile.inputStream().use { input ->
                    finalFile.outputStream().use { output -> input.copyTo(output) }
                }
                partFile.delete()
                true
            }
        } catch (e: Exception) {
            false
        }
        if (!moved) {
            link?.sendApplicationMessage(
                AppMessage.FileDone(transferId, false, Protocol.FILE_DONE_REJECTED)
            )
            return false
        }
        repository.completeIncomingFile(message.id, finalFile.absolutePath, totalBytes, digest)
        link?.sendApplicationMessage(AppMessage.FileDone(transferId, true, 0))
        repository.messageById(message.id)?.let { completed -> onIncomingFileReady(completed) }
        return true
    }

    /**
     * Discards a file whose bytes did not match the sender's digest. The receiver resets its
     * durable progress to zero and reports an integrity failure; the sender then retries the
     * whole file from the beginning.
     */
    private suspend fun resetIncomingIntegrity(
        message: Message,
        partFile: File,
        link: PeerLink?,
        transferId: ByteArray
    ) {
        try {
            partFile.delete()
        } catch (e: Exception) {
        }
        repository.setFileProgress(
            message.id,
            0,
            FileDigest.OFFSET_BASIS,
            allowRewind = true
        )
        link?.sendApplicationMessage(
            AppMessage.FileDone(transferId, false, Protocol.FILE_DONE_INTEGRITY)
        )
    }

    /**
     * Stops an incoming transfer that cannot make progress. The part file is kept so the
     * acknowledged offset can be trusted if the user frees space and reconnects.
     */
    private suspend fun failIncoming(session: IncomingSession, link: PeerLink?) {
        flushIncoming(session)
        try {
            session.output?.close()
        } catch (e: Exception) {
        }
        session.output = null
        val onDisk = try {
            if (session.partFile.isFile) session.partFile.length() else 0L
        } catch (e: Exception) {
            session.expectedBytes
        }
        session.expectedBytes = onDisk.coerceIn(0L, session.totalBytes)
        if (session.expectedBytes == 0L) {
            session.digest = FileDigest.OFFSET_BASIS
        } else if (session.expectedBytes < session.totalBytes) {
            // The buffered writer may have written a partial block before the failure.
            session.digest = computeDigest(session.partFile, session.expectedBytes)
                ?: FileDigest.OFFSET_BASIS
        }
        incoming.remove(session.transferId, session)
        repository.setFileProgress(
            session.messageId,
            session.expectedBytes,
            session.digest,
            allowRewind = true
        )
        repository.setState(session.messageId, MessageState.FAILED)
        Hex.decode(session.transferId)?.let { bytes ->
            link?.sendApplicationMessage(
                AppMessage.FileDone(bytes, false, Protocol.FILE_DONE_REJECTED)
            )
        }
    }

    private fun handleAck(ack: AppMessage.FileAck) {
        val session = sessions[Hex.encode(ack.transferId)] ?: return
        val previous = session.remoteBytes
        if (ack.receivedBytes == 0L) {
            // A zero offset is an explicit reset after an integrity retry. It is sent after
            // every earlier acknowledgement on the ordered channel, so it must win.
            session.remoteBytes = 0L
            session.remoteDigest = ack.digest
        } else {
            session.remoteBytes = maxOf(previous, ack.receivedBytes)
            if (ack.receivedBytes >= previous) session.remoteDigest = ack.digest
        }
        session.lastAckAt = System.currentTimeMillis()
        session.signalChange()
        if (previous / PERSIST_STEP_BYTES != session.remoteBytes / PERSIST_STEP_BYTES) {
            scope.launch {
                repository.setFileProgress(session.messageId, session.remoteBytes)
            }
        }
    }

    private suspend fun handleDone(done: AppMessage.FileDone) {
        val transferId = Hex.encode(done.transferId)
        val session = sessions[transferId]
        if (session != null) {
            if (done.success) {
                session.done = true
            } else {
                session.rejected = true
                session.failureReason = done.reason
            }
            session.signalChange()
            return
        }
        if (done.success) return
        // The peer canceled a transfer this side was receiving (for example the sender deleted
        // the message). Stop the session and keep the row visible as failed instead of leaving
        // it "pending" forever with an orphaned part file.
        val receiving = incoming.remove(transferId) ?: return
        try {
            receiving.output?.close()
        } catch (e: Exception) {
        }
        receiving.output = null
        try {
            receiving.partFile.delete()
        } catch (e: Exception) {
        }
        repository.setFileProgress(
            receiving.messageId,
            0,
            FileDigest.OFFSET_BASIS,
            allowRewind = true
        )
        repository.setState(receiving.messageId, MessageState.FAILED)
    }

    private suspend fun markFailed(message: Message): Outcome {
        repository.setState(message.id, MessageState.FAILED)
        return Outcome.ABORTED
    }

    private fun flushIncoming(session: IncomingSession) {
        try {
            session.output?.flush()
        } catch (e: Exception) {
        }
    }

    /**
     * Works out the durable resume point for an incoming file. Bytes beyond the last persisted
     * offset are discarded (they were never covered by the persisted digest); a short file is
     * hashed again. Returns null only when the file cannot be read.
     */
    private suspend fun prepareIncoming(
        message: Message,
        partFile: File,
        total: Long
    ): Pair<Long, Long>? = withContext(Dispatchers.IO) {
        val persisted = message.receivedBytes.coerceIn(0L, total)
        var disk = if (partFile.isFile) partFile.length() else 0L
        if (disk > persisted) {
            if (!truncate(partFile, persisted)) return@withContext null
            disk = persisted
        }
        val digest = when {
            disk == 0L -> FileDigest.OFFSET_BASIS
            disk == persisted && message.transferDigest != 0L -> message.transferDigest
            else -> computeDigest(partFile, disk) ?: return@withContext null
        }
        if (disk != persisted || message.transferDigest != digest) {
            repository.setFileProgress(message.id, disk, digest, allowRewind = true)
        }
        disk to digest
    }

    private fun truncate(file: File, length: Long): Boolean = try {
        RandomAccessFile(file, "rw").use { it.setLength(length) }
        true
    } catch (e: Exception) {
        false
    }

    private fun computeDigest(file: File, length: Long): Long? {
        if (length <= 0) return FileDigest.OFFSET_BASIS
        return try {
            var hash = FileDigest.OFFSET_BASIS
            var remaining = length
            FileInputStream(file).use { input ->
                val buffer = ByteArray(DIGEST_BUFFER_BYTES)
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                    if (read < 0) return null
                    if (read == 0) continue
                    hash = FileDigest.update(hash, buffer, 0, read)
                    remaining -= read
                }
            }
            hash
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Copies a stream whose size is unknown. The copy is capped at the protocol maximum and
     * deleted on any failure, so an endless provider stream cannot fill the disk unnoticed.
     */
    private fun copyToOutgoing(uri: Uri, transferId: String): File? {
        val target = File(outgoingCopyDir, "$transferId.bin")
        var tooLarge = false
        try {
            val input = resolver.openInputStream(uri) ?: return null
            var total = 0L
            input.use { source ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        total += read
                        if (total > Protocol.MAX_FILE_BYTES) {
                            tooLarge = true
                            break
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (tooLarge) {
                target.delete()
                return null
            }
            return target.takeIf { it.isFile }
        } catch (e: Exception) {
            target.delete()
            return null
        }
    }

    private fun uniqueDestination(displayName: String): File {
        val safe = AppCodec.sanitizeFileName(displayName) ?: "file"
        var candidate = File(incomingDir, safe)
        if (candidate.exists()) {
            val dot = safe.lastIndexOf('.')
            val base = if (dot > 0) safe.substring(0, dot) else safe
            val ext = if (dot > 0) safe.substring(dot) else ""
            var counter = 1
            while (candidate.exists()) {
                candidate = File(incomingDir, "$base ($counter)$ext")
                counter++
            }
        }
        return candidate
    }

    private fun hasFreeSpace(needed: Long): Boolean {
        if (needed <= 0) return true
        return try {
            val stat = StatFs(appContext.filesDir.path)
            stat.availableBytes > needed + FREE_SPACE_MARGIN_BYTES
        } catch (e: Exception) {
            true
        }
    }

    private suspend fun openSource(message: Message, offset: Long): SourceStream? {
        val path = message.filePath
        if (path != null) {
            val file = File(path)
            if (!file.isFile) return null
            return try {
                val stream = FileInputStream(file)
                stream.channel.position(offset.coerceAtMost(stream.channel.size()))
                SourceStream(stream, null)
            } catch (e: Exception) {
                null
            }
        }
        val uri = message.fileUri?.let { Uri.parse(it) } ?: return null
        try {
            val descriptor = resolver.openFileDescriptor(uri, "r")
            if (descriptor != null) {
                val stream = FileInputStream(descriptor.fileDescriptor)
                try {
                    stream.channel.position(offset)
                    return SourceStream(stream, descriptor)
                } catch (e: Exception) {
                    stream.close()
                    descriptor.close()
                }
            }
        } catch (e: Exception) {
            // Fall back to a plain stream below (cloud providers, unusual descriptors).
        }
        return try {
            val stream = resolver.openInputStream(uri) ?: return null
            if (!skipFully(stream, offset)) {
                stream.close()
                return null
            }
            SourceStream(stream, null)
        } catch (e: Exception) {
            null
        }
    }

    private fun skipFully(input: InputStream, bytes: Long): Boolean {
        var remaining = bytes
        val scratch = ByteArray(COPY_BUFFER_BYTES)
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            val read = input.read(scratch, 0, minOf(remaining, scratch.size.toLong()).toInt())
            if (read < 0) return false
            remaining -= read
        }
        return true
    }

    private class SourceStream(
        private val input: InputStream,
        private val descriptor: ParcelFileDescriptor?
    ) {
        fun readBlock(maxBytes: Int): ByteArray? {
            val buffer = ByteArray(maxBytes)
            var filled = 0
            while (filled < maxBytes) {
                val read = input.read(buffer, filled, maxBytes - filled)
                if (read < 0) break
                if (read == 0) continue
                filled += read
            }
            if (filled == 0) return null
            return if (filled == maxBytes) buffer else buffer.copyOf(filled)
        }

        fun close() {
            try {
                input.close()
            } catch (e: Exception) {
            }
            try {
                descriptor?.close()
            } catch (e: Exception) {
            }
        }
    }

    companion object {
        private const val OFFER_RETRY_MS = 900L
        private const val OFFER_TIMEOUT_MS = 30_000L
        private const val END_RETRY_MS = 900L
        private const val COMPLETION_TIMEOUT_MS = 45_000L
        private const val FLOW_CONTROL_POLL_MS = 30L
        private const val SEND_BACKPRESSURE_MS = 8L
        private const val TEXT_PRIORITY_CHECK_MS = 400L
        private const val TEXT_PRIORITY_PAUSE_MS = 60L
        private const val FILE_QUEUE_IDLE_MS = 500L
        private const val ACK_STEP_BYTES = 512L * 1024
        private const val ACK_INTERVAL_MS = 50L
        private const val PERSIST_STEP_BYTES = 2L * 1024 * 1024
        private const val PERSIST_INTERVAL_MS = 1_000L
        private const val FREE_SPACE_MARGIN_BYTES = 8L * 1024 * 1024
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private const val OUTPUT_BUFFER_BYTES = 256 * 1024
        private const val DIGEST_BUFFER_BYTES = 256 * 1024
        private const val MAX_INTEGRITY_RETRIES = 2
        private const val INTEGRITY_RETRY_PAUSE_MS = 250L

        /**
         * Receiver durability window. The transport itself can hold much more, but allowing
         * only a few megabytes beyond the last acknowledged byte keeps memory and disk-buffer
         * pressure flat for multi-gigabyte files while still filling a fast link.
         */
        private const val MAX_IN_FLIGHT_BYTES = 8L * 1024 * 1024
    }
}
