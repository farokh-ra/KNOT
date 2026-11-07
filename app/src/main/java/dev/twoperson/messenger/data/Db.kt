package dev.twoperson.messenger.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Local-only SQLite store: message history, the pinned peer identity and small counters.
 * Nothing here is uploaded anywhere; there is no server side to this app.
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
                sender_id TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_messages_order ON messages(created_at, sender_id, id)")
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
        // Version 1 is the first shipped schema. Future versions add explicit migrations here.
    }

    companion object {
        private const val NAME = "twoperson.db"
        private const val VERSION = 1
    }
}
