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

    suspend fun refresh() = withContext(Dispatchers.IO) {
        _messages.value = queryAll()
    }

    suspend fun insertOutgoing(id: String, body: String, createdAt: Long, sequence: Long): Message =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val values = ContentValues().apply {
                    put("id", id)
                    put("direction", MessageDirection.OUTGOING.ordinal)
                    put("body", body)
                    put("created_at", createdAt)
                    put("seq", sequence)
                    put("state", MessageState.PENDING.ordinal)
                    put("sender_id", "local")
                }
                db.writableDatabase.insertWithOnConflict("messages", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
                _messages.value = queryAll()
                Message(id, MessageDirection.OUTGOING, body, createdAt, sequence, MessageState.PENDING, "local")
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
            val values = ContentValues().apply {
                put("id", id)
                put("direction", MessageDirection.INCOMING.ordinal)
                put("body", body)
                put("created_at", createdAt)
                put("seq", sequence)
                put("state", MessageState.DELIVERED.ordinal)
                put("sender_id", senderId)
            }
            val row = db.writableDatabase.insertWithOnConflict(
                "messages", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE
            )
            if (row >= 0) _messages.value = queryAll()
            row >= 0
        }
    }

    suspend fun setState(id: String, state: MessageState) = withContext(Dispatchers.IO) {
        lock.withLock {
            val values = ContentValues().apply { put("state", state.ordinal) }
            // Never downgrade a delivered message if a late transport callback arrives.
            db.writableDatabase.update(
                "messages", values, "id = ? AND state < ?", arrayOf(id, state.ordinal.toString())
            )
            _messages.value = queryAll()
        }
    }

    suspend fun pendingOutgoing(): List<Message> = withContext(Dispatchers.IO) {
        query("direction = ${MessageDirection.OUTGOING.ordinal} AND state < ${MessageState.DELIVERED.ordinal}")
    }

    /** Marks queued messages as pending again after a reconnect so they are retried. */
    suspend fun resetFailedToPending() = withContext(Dispatchers.IO) {
        lock.withLock {
            val values = ContentValues().apply { put("state", MessageState.PENDING.ordinal) }
            db.writableDatabase.update(
                "messages", values,
                "direction = ${MessageDirection.OUTGOING.ordinal} AND state = ${MessageState.FAILED.ordinal}", null
            )
            _messages.value = queryAll()
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
            _messages.value = queryAll()
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

    suspend fun clearConversation() = withContext(Dispatchers.IO) {
        lock.withLock {
            db.writableDatabase.delete("messages", null, null)
            _messages.value = emptyList()
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
            val existing = db.readableDatabase.rawQuery("SELECT label FROM peer WHERE id = 1", null).use {
                if (it.moveToFirst()) it.nullableString(0) else null
            }
            val values = ContentValues().apply {
                put("id", 1)
                if (deviceId != null) put("device_id", deviceId)
                if (publicKeyHex != null) put("public_key", publicKeyHex)
                if (displayName != null) put("display_name", displayName)
                if (existing != null) put("label", existing)
                put("paired_at", System.currentTimeMillis())
            }
            db.writableDatabase.insertWithOnConflict("peer", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    suspend fun setPeerLabel(label: String?) = withContext(Dispatchers.IO) {
        lock.withLock {
            val values = ContentValues().apply { put("label", label) }
            db.writableDatabase.update("peer", values, "id = 1", null)
        }
    }

    suspend fun clearPeer() = withContext(Dispatchers.IO) {
        lock.withLock {
            db.writableDatabase.delete("peer", null, null)
        }
    }

    private fun queryAll(): List<Message> = query(null)

    private fun query(where: String?): List<Message> {
        val cursor: Cursor = db.readableDatabase.rawQuery(
            """
            SELECT id, direction, body, created_at, seq, state, sender_id
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
                        senderId = it.getString(6)
                    )
                )
            }
        }
        return result
    }

    private fun Cursor.nullableString(column: Int): String? =
        if (isNull(column)) null else getString(column)
}
