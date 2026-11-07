package dev.twoperson.messenger.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Local-only SQLite store: message history, file transfer state, the pinned peer identity
 * and small counters. Nothing here is uploaded anywhere; there is no server side to this app.
 */
class Db(context: Context) : SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE messages (
                id TEXT PRIMARY KEY NOT NULL,
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
        createIndexes(db)
        db.execSQL(
            """
            CREATE TABLE peer (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                device_id TEXT,
                public_key TEXT,
                label TEXT,
                display_name TEXT,
                paired_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
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
        createIndexes(db)
    }

    private fun createIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_order ON messages(created_at, sender_id, id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_transfer ON messages(transfer_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_state ON messages(direction, state)")
    }

    companion object {
        private const val NAME = "twoperson.db"
        private const val VERSION = 3
    }
}
