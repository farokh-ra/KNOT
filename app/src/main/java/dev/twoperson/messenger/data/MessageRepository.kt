package dev.twoperson.messenger.data

import android.content.ContentValues
import android.database.Cursor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * All message persistence. Reads and writes happen on the IO dispatcher; the UI observes a
 * single StateFlow of the conversation in deterministic display order.
 */
class MessageRepository(private val db: Db) {

    private val lock = Mutex()
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        lock.withLock { refreshLocked() }
    }

    suspend fun insertOutgoing(id: String, body: String, createdAt: Long, sequence: Long): Message =
        withContext(Dispatchers.IO) {
            lock.withLock {
                insertMessageLocked(
                    id = id,
                    direction = MessageDirection.OUTGOING,
                    body = body,
                    createdAt = createdAt,
                    sequence = sequence,
                    state = MessageState.PENDING,
                    senderId = "local",
                    kind = MessageKind.TEXT,
                    isRead = true
                )
            }
        }

    /**
     * Inserts an outgoing file message. The file itself stays at [fileUri] (a persisted SAF
     * URI) or, when a provider cannot report a size, at [filePath] in app storage.
     */
    suspend fun insertOutgoingFile(
        id: String,
        transferId: String,
        fileName: String,
        mimeType: String,
        size: Long,
        fileUri: String?,
        filePath: String?,
        createdAt: Long,
        sequence: Long
    ): Message = withContext(Dispatchers.IO) {
        lock.withLock {
            insertMessageLocked(
                id = id,
                direction = MessageDirection.OUTGOING,
                body = fileName,
                createdAt = createdAt,
                sequence = sequence,
                state = MessageState.PENDING,
                senderId = "local",
                kind = MessageKind.FILE,
                fileName = fileName,
                mimeType = mimeType,
                fileSize = size,
                fileUri = fileUri,
                filePath = filePath,
                transferId = transferId,
                isRead = true
            )
        }
    }

    /** Returns true when the message was new; false for a duplicate delivery. */
    suspend fun insertIncoming(
        id: String,
        body: String,
        createdAt: Long,
        sequence: Long,
        senderId: String
    ): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            insertIncomingLocked(
                id = id,
                body = body,
                createdAt = createdAt,
                sequence = sequence,
                senderId = senderId,
                kind = MessageKind.TEXT,
                fileName = null,
                mimeType = null,
                fileSize = 0,
                transferId = null
            )
        }
    }

    /** Inserts the visible placeholder for an incoming file. Completion updates the same row. */
    suspend fun insertIncomingFile(
        id: String,
        transferId: String,
        fileName: String,
        mimeType: String,
        size: Long,
        createdAt: Long,
        sequence: Long,
        senderId: String
    ): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            insertIncomingLocked(
                id = id,
                body = fileName,
                createdAt = createdAt,
                sequence = sequence,
                senderId = senderId,
                kind = MessageKind.FILE,
                fileName = fileName,
                mimeType = mimeType,
                fileSize = size,
                transferId = transferId
            )
        }
    }

    suspend fun setState(id: String, state: MessageState) = withContext(Dispatchers.IO) {
        lock.withLock {
            val current = db.readableDatabase.rawQuery(
                "SELECT state FROM messages WHERE id = ?",
                arrayOf(id)
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    MessageState.values().getOrElse(cursor.getInt(0)) { MessageState.PENDING }
                } else {
                    null
                }
            }
            if (current == null || stateRank(current) >= stateRank(state)) return@withLock
            val values = ContentValues().apply { put("state", state.ordinal) }
            db.writableDatabase.update("messages", values, "id = ?", arrayOf(id))
            refreshLocked()
        }
    }

    /** FAILED is displayed after DELIVERED, so ordering must not use enum ordinals here. */
    private fun stateRank(state: MessageState): Int = when (state) {
        MessageState.PENDING -> 0
        MessageState.SENT -> 1
        MessageState.FAILED -> 2
        MessageState.DELIVERED -> 3
    }

    /**
     * Updates visible transfer progress and, for a receiver, the running digest at that offset.
     * Progress never moves backwards unless [allowRewind] is set (an integrity retry starts at
     * zero), so a late packet cannot make the UI or a resume point jump back.
     */
    suspend fun setFileProgress(
        id: String,
        receivedBytes: Long,
        digest: Long? = null,
        allowRewind: Boolean = false
    ) = withContext(Dispatchers.IO) {
        lock.withLock {
            val (currentBytes, currentDigest) = db.readableDatabase.rawQuery(
                "SELECT received_bytes, transfer_digest FROM messages WHERE id = ?",
                arrayOf(id)
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getLong(0) to cursor.getLong(1)
                } else {
                    return@withLock
                }
            }
            val nextBytes = if (allowRewind) {
                receivedBytes.coerceAtLeast(0)
            } else {
                maxOf(currentBytes, receivedBytes)
            }
            val nextDigest = digest ?: currentDigest
            if (nextBytes == currentBytes && nextDigest == currentDigest) return@withLock
            val values = ContentValues().apply {
                put("received_bytes", nextBytes)
                put("transfer_digest", nextDigest)
            }
            db.writableDatabase.update("messages", values, "id = ?", arrayOf(id))
            // Progress arrives several times per second during a large transfer. Update the
            // already loaded row in place instead of re-querying the whole history.
            val current = _messages.value
            val index = current.indexOfFirst { it.id == id }
            if (index >= 0) {
                val copy = current.toMutableList()
                copy[index] = copy[index].copy(
                    receivedBytes = nextBytes,
                    transferDigest = nextDigest
                )
                _messages.value = copy
            }
        }
    }

    /** Receiver: the bytes are stored and the file is complete. */
    suspend fun completeIncomingFile(
        id: String,
        filePath: String,
        totalBytes: Long,
        digest: Long
    ) = withContext(Dispatchers.IO) {
        lock.withLock {
            val values = ContentValues().apply {
                put("state", MessageState.DELIVERED.ordinal)
                put("file_path", filePath)
                put("received_bytes", totalBytes)
                put("transfer_digest", digest)
            }
            db.writableDatabase.update("messages", values, "id = ?", arrayOf(id))
            refreshLocked()
        }
    }

    /** Sender: the receiver confirmed it stored the whole file. */
    suspend fun completeOutgoingFile(id: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val values = ContentValues().apply {
                put("state", MessageState.DELIVERED.ordinal)
                put("received_bytes", dbFileSizeLocked(id))
            }
            db.writableDatabase.update(
                "messages", values, "id = ? AND kind = ?",
                arrayOf(id, MessageKind.FILE.ordinal.toString())
            )
            refreshLocked()
        }
    }

    /**
     * Puts one failed outgoing message back in the queue. Delivered rows are never touched.
     * For a file the receiver's acknowledged offset and digest are kept, so it resumes.
     */
    suspend fun retryMessage(id: String): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            val values = ContentValues().apply { put("state", MessageState.PENDING.ordinal) }
            val updated = db.writableDatabase.update(
                "messages", values,
                "id = ? AND direction = ? AND state != ?",
                arrayOf(
                    id,
                    MessageDirection.OUTGOING.ordinal.toString(),
                    MessageState.DELIVERED.ordinal.toString()
                )
            )
            if (updated > 0) refreshLocked()
            updated > 0
        }
    }

    /**
     * Receiver: a failed incoming file is being resumed after a new offer (for example after
     * the user freed disk space). Unlike [setState], this may move the row backwards from
     * FAILED to PENDING because the transfer is genuinely active again.
     */
    suspend fun setIncomingPending(id: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val values = ContentValues().apply { put("state", MessageState.PENDING.ordinal) }
            db.writableDatabase.update(
                "messages", values,
                "id = ? AND direction = ? AND state = ?",
                arrayOf(
                    id,
                    MessageDirection.INCOMING.ordinal.toString(),
                    MessageState.FAILED.ordinal.toString()
                )
            )
            refreshLocked()
        }
    }

    /** Deletes one message and returns the removed row so local files can be cleaned up. */
    suspend fun deleteMessage(id: String): Message? = withContext(Dispatchers.IO) {
        lock.withLock {
            val removed = messageByIdLocked(id) ?: return@withLock null
            db.writableDatabase.delete("messages", "id = ?", arrayOf(id))
            refreshLocked()
            removed
        }
    }

    suspend fun pendingOutgoing(): List<Message> = withContext(Dispatchers.IO) {
        query(
            "direction = ${MessageDirection.OUTGOING.ordinal} AND state < ${MessageState.DELIVERED.ordinal}"
        )
    }

    /** Small text messages: these are sent first, ahead of bulk file data. */
    suspend fun pendingTextOutgoing(): List<Message> = withContext(Dispatchers.IO) {
        query(
            "direction = ${MessageDirection.OUTGOING.ordinal} AND kind = ${MessageKind.TEXT.ordinal}" +
                " AND state < ${MessageState.DELIVERED.ordinal}"
        )
    }

    suspend fun pendingFileOutgoing(): List<Message> = withContext(Dispatchers.IO) {
        query(
            "direction = ${MessageDirection.OUTGOING.ordinal} AND kind = ${MessageKind.FILE.ordinal}" +
                " AND state < ${MessageState.DELIVERED.ordinal}"
        )
    }

    suspend fun fileMessageByTransferId(transferId: String): Message? = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery(
            "SELECT id FROM messages WHERE transfer_id = ? LIMIT 1",
            arrayOf(transferId)
        ).use { cursor ->
            if (cursor.moveToFirst()) messageByIdLocked(cursor.getString(0)) else null
        }
    }

    suspend fun messageById(id: String): Message? = withContext(Dispatchers.IO) {
        messageByIdLocked(id)
    }

    /** Marks queued messages as pending again after a reconnect so they are retried. */
    suspend fun resetFailedToPending() = withContext(Dispatchers.IO) {
        lock.withLock {
            val values = ContentValues().apply { put("state", MessageState.PENDING.ordinal) }
            db.writableDatabase.update(
                "messages", values,
                "direction = ${MessageDirection.OUTGOING.ordinal} AND state = ${MessageState.FAILED.ordinal}", null
            )
            refreshLocked()
        }
    }

    /**
     * Marks undelivered outgoing messages as failed after the peer stopped acknowledging.
     * The text is never deleted: a later connection resets these to queued and retries them.
     */
    suspend fun markUndeliveredAsFailed() = withContext(Dispatchers.IO) {
        lock.withLock {
            val values = ContentValues().apply { put("state", MessageState.FAILED.ordinal) }
            db.writableDatabase.update(
                "messages", values,
                "direction = ${MessageDirection.OUTGOING.ordinal} AND state = ${MessageState.PENDING.ordinal}", null
            )
            refreshLocked()
        }
    }

    suspend fun nextSequence(): Long = withContext(Dispatchers.IO) {
        lock.withLock {
            val database = db.writableDatabase
            val current = database.rawQuery("SELECT value FROM meta WHERE key = 'out_seq'", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0).toLongOrNull() ?: 0L else 0L
            }
            val next = current + 1
            val values = ContentValues().apply {
                put("key", "out_seq")
                put("value", next.toString())
            }
            database.insertWithOnConflict("meta", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
            next
        }
    }

    suspend fun markIncomingRead() = withContext(Dispatchers.IO) {
        lock.withLock {
            val values = ContentValues().apply { put("is_read", 1) }
            db.writableDatabase.update(
                "messages", values,
                "direction = ${MessageDirection.INCOMING.ordinal} AND is_read = 0", null
            )
            refreshLocked()
        }
    }

    /** Deletes the history and returns the removed rows so app-private files can be cleaned up. */
    suspend fun clearConversation(): List<Message> = withContext(Dispatchers.IO) {
        lock.withLock {
            val removed = queryAll()
            db.writableDatabase.delete("messages", null, null)
            refreshLocked()
            removed
        }
    }

    suspend fun peer(): PeerInfo? = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery("SELECT device_id, public_key, label, display_name, paired_at FROM peer WHERE id = 1", null)
            .use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                PeerInfo(
                    deviceId = cursor.nullableString(0),
                    publicKeyHex = cursor.nullableString(1),
                    label = cursor.nullableString(2),
                    displayName = cursor.nullableString(3),
                    pairedAt = cursor.getLong(4)
                )
            }
    }

    suspend fun savePeer(deviceId: String?, publicKeyHex: String?, displayName: String?) = withContext(Dispatchers.IO) {
        lock.withLock {
            val existing = db.readableDatabase.rawQuery(
                "SELECT label, public_key, device_id, paired_at FROM peer WHERE id = 1",
                null
            ).use { cursor ->
                if (!cursor.moveToFirst()) {
                    null
                } else {
                    PeerRow(
                        label = cursor.nullableString(0),
                        publicKeyHex = cursor.nullableString(1),
                        deviceId = cursor.nullableString(2),
                        pairedAt = cursor.getLong(3)
                    )
                }
            }
            // Hello arrives on every reconnect. Keep the date the pair was first established
            // unless the identity itself changed (a re-pair), in which case it is a new pair.
            val sameIdentity = when {
                existing == null -> true
                publicKeyHex != null && existing.publicKeyHex != null ->
                    existing.publicKeyHex == publicKeyHex
                deviceId != null && existing.deviceId != null -> existing.deviceId == deviceId
                else -> true
            }
            val pairedAt = if (existing != null && sameIdentity && existing.pairedAt > 0) {
                existing.pairedAt
            } else {
                System.currentTimeMillis()
            }
            val values = ContentValues().apply {
                put("id", 1)
                if (deviceId != null) put("device_id", deviceId)
                if (publicKeyHex != null) put("public_key", publicKeyHex)
                if (displayName != null) put("display_name", displayName)
                if (sameIdentity && existing?.label != null) put("label", existing.label)
                put("paired_at", pairedAt)
            }
            db.writableDatabase.insertWithOnConflict("peer", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    /** Stores a friendly name even before the first connection created a peer row. */
    suspend fun setPeerLabel(label: String?) = withContext(Dispatchers.IO) {
        lock.withLock {
            val values = ContentValues().apply { put("label", label) }
            val updated = db.writableDatabase.update("peer", values, "id = 1", null)
            if (updated == 0) {
                val inserted = ContentValues().apply {
                    put("id", 1)
                    put("label", label)
                    put("paired_at", 0L)
                }
                db.writableDatabase.insertWithOnConflict(
                    "peer", null, inserted, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE
                )
            }
        }
    }

    suspend fun clearPeer() = withContext(Dispatchers.IO) {
        lock.withLock {
            db.writableDatabase.delete("peer", null, null)
        }
    }

    private fun insertMessageLocked(
        id: String,
        direction: MessageDirection,
        body: String,
        createdAt: Long,
        sequence: Long,
        state: MessageState,
        senderId: String,
        kind: MessageKind,
        isRead: Boolean,
        fileName: String? = null,
        mimeType: String? = null,
        fileSize: Long = 0,
        fileUri: String? = null,
        filePath: String? = null,
        transferId: String? = null,
        receivedBytes: Long = 0,
        transferDigest: Long = 0
    ): Message {
        val values = ContentValues().apply {
            put("id", id)
            put("direction", direction.ordinal)
            put("body", body)
            put("created_at", createdAt)
            put("seq", sequence)
            put("state", state.ordinal)
            put("sender_id", senderId)
            put("kind", kind.ordinal)
            if (fileName != null) put("file_name", fileName)
            if (mimeType != null) put("mime_type", mimeType)
            put("file_size", fileSize)
            if (fileUri != null) put("file_uri", fileUri)
            if (filePath != null) put("file_path", filePath)
            if (transferId != null) put("transfer_id", transferId)
            put("received_bytes", receivedBytes)
            put("transfer_digest", transferDigest)
            put("is_read", if (isRead) 1 else 0)
        }
        db.writableDatabase.insertWithOnConflict(
            "messages", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE
        )
        refreshLocked()
        return Message(
            id, direction, body, createdAt, sequence, state, senderId, kind,
            fileName, mimeType, fileSize, fileUri, filePath, transferId, receivedBytes,
            transferDigest, isRead
        )
    }

    private fun insertIncomingLocked(
        id: String,
        body: String,
        createdAt: Long,
        sequence: Long,
        senderId: String,
        kind: MessageKind,
        fileName: String?,
        mimeType: String?,
        fileSize: Long,
        transferId: String?
    ): Boolean {
        val values = ContentValues().apply {
            put("id", id)
            put("direction", MessageDirection.INCOMING.ordinal)
            put("body", body)
            put("created_at", createdAt)
            put("seq", sequence)
            put("state", if (kind == MessageKind.TEXT) MessageState.DELIVERED.ordinal else MessageState.PENDING.ordinal)
            put("sender_id", senderId)
            put("kind", kind.ordinal)
            if (fileName != null) put("file_name", fileName)
            if (mimeType != null) put("mime_type", mimeType)
            put("file_size", fileSize)
            if (transferId != null) put("transfer_id", transferId)
            put("received_bytes", 0)
            put("transfer_digest", 0)
            put("is_read", 0)
        }
        val row = db.writableDatabase.insertWithOnConflict(
            "messages", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE
        )
        if (row >= 0) refreshLocked()
        return row >= 0
    }

    private fun refreshLocked() {
        _messages.value = queryAll()
        _unreadCount.value = queryUnreadCount()
    }

    private fun queryAll(): List<Message> = query(null)

    private fun queryUnreadCount(): Int =
        db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM messages WHERE direction = ${MessageDirection.INCOMING.ordinal} AND is_read = 0",
            null
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    private fun messageByIdLocked(id: String): Message? = queryAll().firstOrNull { it.id == id }

    private fun dbFileSizeLocked(id: String): Long =
        db.readableDatabase.rawQuery("SELECT file_size FROM messages WHERE id = ?", arrayOf(id)).use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }

    private fun query(where: String?): List<Message> {
        val cursor: Cursor = db.readableDatabase.rawQuery(
            """
            SELECT id, direction, body, created_at, seq, state, sender_id,
                   kind, file_name, mime_type, file_size, file_uri, file_path,
                   transfer_id, received_bytes, transfer_digest, is_read
            FROM messages
            ${if (where != null) "WHERE $where" else ""}
            ORDER BY created_at ASC, sender_id ASC, id ASC
            """.trimIndent(),
            null
        )
        val result = ArrayList<Message>()
        cursor.use {
            while (it.moveToNext()) {
                result.add(
                    Message(
                        id = it.getString(0),
                        direction = if (it.getInt(1) == MessageDirection.OUTGOING.ordinal) MessageDirection.OUTGOING else MessageDirection.INCOMING,
                        body = it.getString(2),
                        createdAt = it.getLong(3),
                        sequence = it.getLong(4),
                        state = MessageState.values().getOrElse(it.getInt(5)) { MessageState.PENDING },
                        senderId = it.getString(6),
                        kind = MessageKind.values().getOrElse(it.getInt(7)) { MessageKind.TEXT },
                        fileName = it.nullableString(8),
                        mimeType = it.nullableString(9),
                        fileSize = it.getLong(10),
                        fileUri = it.nullableString(11),
                        filePath = it.nullableString(12),
                        transferId = it.nullableString(13),
                        receivedBytes = it.getLong(14),
                        transferDigest = it.getLong(15),
                        isRead = it.getInt(16) != 0
                    )
                )
            }
        }
        return result
    }

    private data class PeerRow(
        val label: String?,
        val publicKeyHex: String?,
        val deviceId: String?,
        val pairedAt: Long
    )

    private fun Cursor.nullableString(column: Int): String? =
        if (isNull(column)) null else getString(column)
}
