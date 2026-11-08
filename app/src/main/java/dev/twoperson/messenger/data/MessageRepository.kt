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
import java.util.UUID

/**
 * All contact and message persistence. Reads and writes happen on the IO dispatcher; the UI
 * observes one StateFlow for the selected conversation plus a summary list for the Chats tab.
 *
 * Because a Knot conversation is always one-to-one, a contact is the conversation. Only one
 * contact is selected at a time, and the connection engine follows that selection.
 */
class MessageRepository(private val db: Db) {

    private val lock = Mutex()

    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    private val _activeContact = MutableStateFlow<Contact?>(null)
    val activeContact: StateFlow<Contact?> = _activeContact.asStateFlow()

    private val _chats = MutableStateFlow<List<ChatSummary>>(emptyList())
    val chats: StateFlow<List<ChatSummary>> = _chats.asStateFlow()

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    private val _totalUnread = MutableStateFlow(0)
    val totalUnread: StateFlow<Int> = _totalUnread.asStateFlow()

    suspend fun initialize() = withContext(Dispatchers.IO) {
        lock.withLock { refreshLocked() }
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        lock.withLock { refreshLocked() }
    }

    // ---------------------------------------------------------------------
    // Contacts and the selected conversation
    // ---------------------------------------------------------------------

    suspend fun activeContactId(): String? = withContext(Dispatchers.IO) {
        _activeContact.value?.id
    }

    suspend fun contact(id: String): Contact? = withContext(Dispatchers.IO) {
        lock.withLock { queryContactLocked(id) }
    }

    suspend fun addContact(label: String?, note: String? = null): Contact =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val now = System.currentTimeMillis()
                val id = UUID.randomUUID().toString().replace("-", "")
                val values = ContentValues().apply {
                    put("id", id)
                    put("label", ContactNames.sanitizeLabel(label))
                    put("note", ContactNames.sanitizeNote(note))
                    put("paired_at", 0L)
                    put("created_at", now)
                    put("last_opened_at", 0L)
                    put("has_invitation", 0)
                }
                db.writableDatabase.insertWithOnConflict(
                    "contacts", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE
                )
                refreshLocked()
                queryContactLocked(id) ?: Contact(id = id, createdAt = now)
            }
        }

    suspend fun updateContact(id: String, label: String?, note: String? = null) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val values = ContentValues().apply {
                    put("label", ContactNames.sanitizeLabel(label))
                    put("note", ContactNames.sanitizeNote(note))
                }
                db.writableDatabase.update("contacts", values, "id = ?", arrayOf(id))
                refreshLocked()
            }
        }

    /** Creates the contact that carries a version-1 single-peer history. */
    suspend fun ensureLegacyContact(label: String = "My contact"): String =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val existing = queryContactLocked(Db.LEGACY_CONTACT_ID)
                if (existing != null) return@withLock existing.id
                val now = System.currentTimeMillis()
                val values = ContentValues().apply {
                    put("id", Db.LEGACY_CONTACT_ID)
                    put("label", label)
                    put("created_at", now)
                    put("last_opened_at", now)
                    put("paired_at", 0L)
                    put("has_invitation", 0)
                }
                db.writableDatabase.insertWithOnConflict(
                    "contacts", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE
                )
                refreshLocked()
                Db.LEGACY_CONTACT_ID
            }
        }

    suspend fun markInvitation(id: String, hasInvitation: Boolean) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val values = ContentValues().apply {
                    put("has_invitation", if (hasInvitation) 1 else 0)
                }
                db.writableDatabase.update("contacts", values, "id = ?", arrayOf(id))
                refreshLocked()
            }
        }

    /** A fresh invitation replaces the pinned identity but keeps the local history. */
    suspend fun resetContactPairing(id: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val values = ContentValues().apply {
                putNull("device_id")
                putNull("public_key")
                putNull("display_name")
                put("paired_at", 0L)
            }
            db.writableDatabase.update("contacts", values, "id = ?", arrayOf(id))
            refreshLocked()
        }
    }

    /** Sets the selected conversation. Returns false for an unknown contact. */
    suspend fun setActiveContact(id: String): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            val contact = queryContactLocked(id) ?: return@withLock false
            writeMetaLocked(Db.KEY_ACTIVE_CONTACT, contact.id)
            val values = ContentValues().apply { put("last_opened_at", System.currentTimeMillis()) }
            db.writableDatabase.update("contacts", values, "id = ?", arrayOf(contact.id))
            refreshLocked()
            true
        }
    }

    suspend fun setActiveContactLabel(label: String?) = withContext(Dispatchers.IO) {
        lock.withLock {
            val active = _activeContact.value ?: return@withLock
            val values = ContentValues().apply { put("label", ContactNames.sanitizeLabel(label)) }
            db.writableDatabase.update("contacts", values, "id = ?", arrayOf(active.id))
            refreshLocked()
        }
    }

    suspend fun savePeer(
        contactId: String,
        deviceId: String?,
        publicKeyHex: String?,
        displayName: String?
    ) = withContext(Dispatchers.IO) {
        lock.withLock {
            val existing = queryContactLocked(contactId) ?: return@withLock
            val sameIdentity = when {
                publicKeyHex != null && existing.publicKeyHex != null ->
                    existing.publicKeyHex == publicKeyHex
                deviceId != null && existing.deviceId != null -> existing.deviceId == deviceId
                else -> true
            }
            val pairedAt = if (sameIdentity && existing.pairedAt > 0) {
                existing.pairedAt
            } else {
                System.currentTimeMillis()
            }
            val values = ContentValues().apply {
                if (deviceId != null) put("device_id", deviceId)
                if (publicKeyHex != null) put("public_key", publicKeyHex)
                if (displayName != null) put("display_name", displayName)
                put("paired_at", pairedAt)
                put("has_invitation", 1)
            }
            db.writableDatabase.update("contacts", values, "id = ?", arrayOf(contactId))
            refreshLocked()
        }
    }

    /** Deletes a contact and its history. Returns the rows so local files can be cleaned up. */
    suspend fun deleteContact(id: String): List<Message> = withContext(Dispatchers.IO) {
        lock.withLock {
            val removed = queryMessagesLocked(id)
            db.writableDatabase.delete("messages", "contact_id = ?", arrayOf(id))
            db.writableDatabase.delete("contacts", "id = ?", arrayOf(id))
            if (_activeContact.value?.id == id) {
                db.writableDatabase.delete(
                    "meta", "key = ?", arrayOf(Db.KEY_ACTIVE_CONTACT)
                )
            }
            refreshLocked()
            removed
        }
    }

    /** Deletes every contact and all history. Returns the removed rows for file cleanup. */
    suspend fun deleteAllContacts(): List<Message> = withContext(Dispatchers.IO) {
        lock.withLock {
            val removed = query(null)
            db.writableDatabase.delete("messages", null, null)
            db.writableDatabase.delete("contacts", null, null)
            db.writableDatabase.delete("meta", "key = ?", arrayOf(Db.KEY_ACTIVE_CONTACT))
            refreshLocked()
            removed
        }
    }

    // ---------------------------------------------------------------------
    // Messages
    // ---------------------------------------------------------------------

    suspend fun insertOutgoing(
        contactId: String,
        id: String,
        body: String,
        createdAt: Long,
        sequence: Long
    ): Message = withContext(Dispatchers.IO) {
        lock.withLock {
            insertMessageLocked(
                contactId = contactId,
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

    suspend fun insertOutgoingFile(
        contactId: String,
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
                contactId = contactId,
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
        contactId: String,
        id: String,
        body: String,
        createdAt: Long,
        sequence: Long,
        senderId: String
    ): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            insertIncomingLocked(
                contactId = contactId,
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

    suspend fun insertIncomingFile(
        contactId: String,
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
                contactId = contactId,
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

    suspend fun pendingOutgoing(contactId: String): List<Message> = withContext(Dispatchers.IO) {
        query(
            "contact_id = ${quote(contactId)}" +
                " AND direction = ${MessageDirection.OUTGOING.ordinal}" +
                " AND state < ${MessageState.DELIVERED.ordinal}"
        )
    }

    /** Small text messages: these are sent first, ahead of bulk file data. */
    suspend fun pendingTextOutgoing(contactId: String): List<Message> = withContext(Dispatchers.IO) {
        query(
            "contact_id = ${quote(contactId)}" +
                " AND direction = ${MessageDirection.OUTGOING.ordinal}" +
                " AND kind = ${MessageKind.TEXT.ordinal}" +
                " AND state < ${MessageState.DELIVERED.ordinal}"
        )
    }

    suspend fun pendingFileOutgoing(contactId: String): List<Message> = withContext(Dispatchers.IO) {
        query(
            "contact_id = ${quote(contactId)}" +
                " AND direction = ${MessageDirection.OUTGOING.ordinal}" +
                " AND kind = ${MessageKind.FILE.ordinal}" +
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

    /** Marks queued messages in one conversation as pending again after a reconnect. */
    suspend fun resetFailedToPending(contactId: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val values = ContentValues().apply { put("state", MessageState.PENDING.ordinal) }
            db.writableDatabase.update(
                "messages", values,
                "contact_id = ? AND direction = ${MessageDirection.OUTGOING.ordinal}" +
                    " AND state = ${MessageState.FAILED.ordinal}",
                arrayOf(contactId)
            )
            refreshLocked()
        }
    }

    /**
     * Marks undelivered outgoing messages as failed after the peer stopped acknowledging.
     * The text is never deleted: a later connection resets these to queued and retries them.
     */
    suspend fun markUndeliveredAsFailed(contactId: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val values = ContentValues().apply { put("state", MessageState.FAILED.ordinal) }
            db.writableDatabase.update(
                "messages", values,
                "contact_id = ? AND direction = ${MessageDirection.OUTGOING.ordinal}" +
                    " AND state = ${MessageState.PENDING.ordinal}",
                arrayOf(contactId)
            )
            refreshLocked()
        }
    }

    suspend fun nextSequence(contactId: String): Long = withContext(Dispatchers.IO) {
        lock.withLock {
            val database = db.writableDatabase
            val key = "out_seq_$contactId"
            val current = database.rawQuery(
                "SELECT value FROM meta WHERE key = ?", arrayOf(key)
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0).toLongOrNull() ?: 0L
                } else if (contactId == Db.LEGACY_CONTACT_ID) {
                    database.rawQuery(
                        "SELECT value FROM meta WHERE key = 'out_seq'", null
                    ).use { legacy ->
                        if (legacy.moveToFirst()) legacy.getString(0).toLongOrNull() ?: 0L else 0L
                    }
                } else {
                    0L
                }
            }
            val next = current + 1
            val values = ContentValues().apply {
                put("key", key)
                put("value", next.toString())
            }
            database.insertWithOnConflict(
                "meta", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
            )
            next
        }
    }

    suspend fun markIncomingRead(contactId: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val values = ContentValues().apply { put("is_read", 1) }
            db.writableDatabase.update(
                "messages", values,
                "contact_id = ? AND direction = ${MessageDirection.INCOMING.ordinal} AND is_read = 0",
                arrayOf(contactId)
            )
            refreshLocked()
        }
    }

    /** Deletes one conversation and returns the removed rows so files can be cleaned up. */
    suspend fun clearConversation(contactId: String): List<Message> = withContext(Dispatchers.IO) {
        lock.withLock {
            val removed = queryMessagesLocked(contactId)
            db.writableDatabase.delete("messages", "contact_id = ?", arrayOf(contactId))
            refreshLocked()
            removed
        }
    }

    /** Deletes every conversation but keeps contacts and pairings. */
    suspend fun clearAllConversations(): List<Message> = withContext(Dispatchers.IO) {
        lock.withLock {
            val removed = query(null)
            db.writableDatabase.delete("messages", null, null)
            refreshLocked()
            removed
        }
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private fun refreshLocked() {
        val contacts = queryContactsLocked()
        val selected = _activeContact.value
        val active = contacts.firstOrNull { it.id == selected?.id }
            ?: contacts.maxByOrNull { maxOf(it.lastOpenedAt, it.pairedAt, it.createdAt) }
        if (active != null) {
            if (selected?.id != active.id) writeMetaLocked(Db.KEY_ACTIVE_CONTACT, active.id)
        } else {
            db.writableDatabase.delete("meta", "key = ?", arrayOf(Db.KEY_ACTIVE_CONTACT))
        }
        _activeContact.value = active
        _contacts.value = contacts
        _messages.value = active?.let { queryMessagesLocked(it.id) } ?: emptyList()
        _unreadCount.value = active?.let { queryUnreadCountLocked(it.id) } ?: 0
        _chats.value = buildChatsLocked(contacts)
        _totalUnread.value = queryTotalUnreadLocked()
    }

    private fun buildChatsLocked(contacts: List<Contact>): List<ChatSummary> =
        contacts.mapNotNull { contact ->
            val last = queryLastMessageLocked(contact.id)
            val unread = queryUnreadCountLocked(contact.id)
            if (last == null && !contact.isOnKnot) {
                null
            } else {
                ChatSummary(contact, last, unread)
            }
        }.sortedByDescending { it.sortTime }

    private fun queryContactsLocked(): List<Contact> {
        val result = ArrayList<Contact>()
        db.readableDatabase.rawQuery(
            """
            SELECT id, label, note, device_id, public_key, display_name,
                   paired_at, created_at, last_opened_at, has_invitation
            FROM contacts
            ORDER BY last_opened_at DESC, created_at DESC
            """.trimIndent(),
            null
        ).use { cursor ->
            while (cursor.moveToNext()) result.add(cursor.toContact())
        }
        return result
    }

    private fun queryContactLocked(id: String): Contact? =
        db.readableDatabase.rawQuery(
            """
            SELECT id, label, note, device_id, public_key, display_name,
                   paired_at, created_at, last_opened_at, has_invitation
            FROM contacts WHERE id = ? LIMIT 1
            """.trimIndent(),
            arrayOf(id)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toContact() else null }

    private fun Cursor.toContact(): Contact = Contact(
        id = getString(0),
        label = nullableString(1),
        note = nullableString(2),
        deviceId = nullableString(3),
        publicKeyHex = nullableString(4),
        displayName = nullableString(5),
        pairedAt = getLong(6),
        createdAt = getLong(7),
        lastOpenedAt = getLong(8),
        hasInvitation = getInt(9) != 0
    )

    private fun insertMessageLocked(
        contactId: String,
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
            put("contact_id", contactId)
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
            id = id,
            contactId = contactId,
            direction = direction,
            body = body,
            createdAt = createdAt,
            sequence = sequence,
            state = state,
            senderId = senderId,
            kind = kind,
            fileName = fileName,
            mimeType = mimeType,
            fileSize = fileSize,
            fileUri = fileUri,
            filePath = filePath,
            transferId = transferId,
            receivedBytes = receivedBytes,
            transferDigest = transferDigest,
            isRead = isRead
        )
    }

    private fun insertIncomingLocked(
        contactId: String,
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
            put("contact_id", contactId)
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

    private fun writeMetaLocked(key: String, value: String) {
        val values = ContentValues().apply {
            put("key", key)
            put("value", value)
        }
        db.writableDatabase.insertWithOnConflict(
            "meta", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun queryUnreadCountLocked(contactId: String): Int =
        db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM messages WHERE contact_id = ?" +
                " AND direction = ${MessageDirection.INCOMING.ordinal} AND is_read = 0",
            arrayOf(contactId)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    private fun queryTotalUnreadLocked(): Int =
        db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM messages WHERE direction = ${MessageDirection.INCOMING.ordinal}" +
                " AND is_read = 0",
            null
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    private fun messageByIdLocked(id: String): Message? {
        val cursor = db.readableDatabase.rawQuery(
            "$SELECT_MESSAGES WHERE id = ? LIMIT 1",
            arrayOf(id)
        )
        return cursor.use { if (it.moveToFirst()) it.toMessage() else null }
    }

    private fun queryMessagesLocked(contactId: String): List<Message> =
        query("contact_id = ${quote(contactId)}")

    private fun queryLastMessageLocked(contactId: String): Message? {
        val cursor = db.readableDatabase.rawQuery(
            "$SELECT_MESSAGES WHERE contact_id = ? ORDER BY created_at DESC, sender_id DESC, id DESC LIMIT 1",
            arrayOf(contactId)
        )
        return cursor.use { if (it.moveToFirst()) it.toMessage() else null }
    }

    private fun query(where: String?): List<Message> {
        val cursor: Cursor = db.readableDatabase.rawQuery(
            """
            $SELECT_MESSAGES
            ${if (where != null) "WHERE $where" else ""}
            ORDER BY created_at ASC, sender_id ASC, id ASC
            """.trimIndent(),
            null
        )
        val result = ArrayList<Message>()
        cursor.use {
            while (it.moveToNext()) result.add(it.toMessage())
        }
        return result
    }

    private fun Cursor.toMessage(): Message = Message(
        id = getString(0),
        contactId = getString(1),
        direction = if (getInt(2) == MessageDirection.OUTGOING.ordinal) {
            MessageDirection.OUTGOING
        } else {
            MessageDirection.INCOMING
        },
        body = getString(3),
        createdAt = getLong(4),
        sequence = getLong(5),
        state = MessageState.values().getOrElse(getInt(6)) { MessageState.PENDING },
        senderId = getString(7),
        kind = MessageKind.values().getOrElse(getInt(8)) { MessageKind.TEXT },
        fileName = nullableString(9),
        mimeType = nullableString(10),
        fileSize = getLong(11),
        fileUri = nullableString(12),
        filePath = nullableString(13),
        transferId = nullableString(14),
        receivedBytes = getLong(15),
        transferDigest = getLong(16),
        isRead = getInt(17) != 0
    )

    private fun dbFileSizeLocked(id: String): Long =
        db.readableDatabase.rawQuery("SELECT file_size FROM messages WHERE id = ?", arrayOf(id)).use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }

    private fun quote(value: String): String = "'${value.replace("'", "''")}'"

    private fun Cursor.nullableString(column: Int): String? =
        if (isNull(column)) null else getString(column)

    companion object {
        private const val SELECT_MESSAGES = """
            SELECT id, contact_id, direction, body, created_at, seq, state, sender_id,
                   kind, file_name, mime_type, file_size, file_uri, file_path,
                   transfer_id, received_bytes, transfer_digest, is_read
            FROM messages
        """
    }
}
