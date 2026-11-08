package dev.twoperson.messenger.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Local-only SQLite store: contacts, message history, file transfer state and small counters.
 * Nothing here is uploaded anywhere; there is no server side to this app.
 */
class Db(context: Context) : SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE messages (
                id TEXT PRIMARY KEY NOT NULL,
                contact_id TEXT NOT NULL,
                direction INTEGER NOT NULL,
                body TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                seq INTEGER NOT NULL,
                state INTEGER NOT NULL,
                sender_id TEXT NOT NULL,
                kind INTEGER NOT NULL DEFAULT 0,
                file_name TEXT,
                mime_type TEXT,
                file_size INTEGER NOT NULL DEFAULT 0,
                file_uri TEXT,
                file_path TEXT,
                transfer_id TEXT,
                received_bytes INTEGER NOT NULL DEFAULT 0,
                transfer_digest INTEGER NOT NULL DEFAULT 0,
                is_read INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent()
        )
        db.execSQL(CREATE_CONTACTS)
        createIndexes(db)
        db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE messages ADD COLUMN kind INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE messages ADD COLUMN file_name TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN mime_type TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN file_size INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE messages ADD COLUMN file_uri TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN file_path TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN transfer_id TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN received_bytes INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE messages ADD COLUMN is_read INTEGER NOT NULL DEFAULT 1")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE messages ADD COLUMN transfer_digest INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 4) {
            db.execSQL(CREATE_CONTACTS)
            db.execSQL("ALTER TABLE messages ADD COLUMN contact_id TEXT NOT NULL DEFAULT ''")
            migrateSinglePeerToContacts(db)
        }
        createIndexes(db)
    }

    /**
     * Version 4 introduces multiple local contacts. An existing installation had exactly one
     * pinned peer and one conversation, so it becomes the "primary" contact and keeps all of
     * its history. The old peer table is left untouched for safety but is no longer read.
     */
    private fun migrateSinglePeerToContacts(db: SQLiteDatabase) {
        val now = System.currentTimeMillis()
        val peerExists = db.rawQuery("SELECT COUNT(*) FROM peer", null).use { cursor ->
            cursor.moveToFirst() && cursor.getInt(0) > 0
        }
        val messageCount = db.rawQuery("SELECT COUNT(*) FROM messages", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
        if (!peerExists && messageCount == 0) return

        val values = ContentValues()
        values.put("id", LEGACY_CONTACT_ID)
        values.put("label", if (peerExists) null else "Saved conversation")
        values.put("created_at", now)
        values.put("last_opened_at", now)
        if (peerExists) {
            db.rawQuery(
                "SELECT device_id, public_key, label, display_name, paired_at FROM peer WHERE id = 1",
                null
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    if (!cursor.isNull(0)) values.put("device_id", cursor.getString(0))
                    if (!cursor.isNull(1)) values.put("public_key", cursor.getString(1))
                    if (!cursor.isNull(2)) values.put("label", cursor.getString(2))
                    if (!cursor.isNull(3)) values.put("display_name", cursor.getString(3))
                    values.put("paired_at", cursor.getLong(4))
                }
            }
        }
        db.insertWithOnConflict("contacts", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        db.execSQL(
            "UPDATE messages SET contact_id = ? WHERE contact_id = ''",
            arrayOf(LEGACY_CONTACT_ID)
        )
        val meta = ContentValues().apply {
            put("key", KEY_ACTIVE_CONTACT)
            put("value", LEGACY_CONTACT_ID)
        }
        db.insertWithOnConflict("meta", null, meta, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun createIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_order ON messages(created_at, sender_id, id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_transfer ON messages(transfer_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_state ON messages(direction, state)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_contact ON messages(contact_id, created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_contacts_opened ON contacts(last_opened_at)")
    }

    companion object {
        private const val NAME = "twoperson.db"
        private const val VERSION = 4
        const val LEGACY_CONTACT_ID = "primary"
        const val KEY_ACTIVE_CONTACT = "active_contact"

        private const val CREATE_CONTACTS = """
            CREATE TABLE IF NOT EXISTS contacts (
                id TEXT PRIMARY KEY NOT NULL,
                label TEXT,
                note TEXT,
                device_id TEXT,
                public_key TEXT,
                display_name TEXT,
                paired_at INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                last_opened_at INTEGER NOT NULL DEFAULT 0,
                has_invitation INTEGER NOT NULL DEFAULT 0
            )
        """
    }
}
