// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.database

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.core.database.getStringOrNull

data class PromptEntry(
    val id: Long,
    val timestamp: Long,
    val isPinned: Boolean,
    val title: String,
    val text: String
)

class PromptDao private constructor(private val db: Database) {
    interface Listener {
        fun onPromptInserted(position: Int)
        fun onPromptsRemoved(position: Int, count: Int)
        fun onPromptMoved(oldPosition: Int, newPosition: Int)
        fun onPromptUpdated()
    }

    var listener: Listener? = null

    private val cache = mutableListOf<PromptEntry>().apply {
        ensureTableExists(db.writableDatabase)
        db.readableDatabase.query(
            TABLE,
            arrayOf(COLUMN_ID, COLUMN_TIMESTAMP, COLUMN_PINNED, COLUMN_TITLE, COLUMN_TEXT),
            null,
            null,
            null,
            null,
            "$COLUMN_PINNED DESC, $COLUMN_TIMESTAMP DESC"
        ).use {
            while (it.moveToNext()) {
                add(
                    PromptEntry(
                        id = it.getLong(0),
                        timestamp = it.getLong(1),
                        isPinned = it.getInt(2) != 0,
                        title = it.getStringOrNull(3) ?: "",
                        text = it.getStringOrNull(4) ?: ""
                    )
                )
            }
        }
    }

    val count: Int get() = synchronized(this) { cache.size }

    fun getEntry(position: Int): PromptEntry = synchronized(this) { cache[position] }

    fun getEntryContent(id: Long): PromptEntry? = synchronized(this) { cache.firstOrNull { it.id == id } }

    fun addPrompt(text: String, title: String = "", pinned: Boolean = false): Long = synchronized(this) {
        val existingIndex = cache.indexOfFirst { it.text == text }
        if (existingIndex >= 0) {
            val existing = cache[existingIndex]
            val now = System.currentTimeMillis()
            val cv = ContentValues().apply {
                put(COLUMN_TIMESTAMP, now)
            }
            db.writableDatabase.update(TABLE, cv, "$COLUMN_ID = ?", arrayOf(existing.id.toString()))
            cache[existingIndex] = existing.copy(timestamp = now)
            cache.sortByDescending { it.timestamp }
            cache.sortByDescending { it.isPinned }
            val newIdx = cache.indexOfFirst { it.id == existing.id }
            if (existingIndex != newIdx) {
                listener?.onPromptMoved(existingIndex, newIdx)
            }
            return existing.id
        }

        val now = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put(COLUMN_TIMESTAMP, now)
            put(COLUMN_PINNED, if (pinned) 1 else 0)
            put(COLUMN_TITLE, title.ifEmpty { text.take(30).replace("\n", " ").trim() })
            put(COLUMN_TEXT, text)
        }
        val id = db.writableDatabase.insert(TABLE, null, cv)
        val entry = PromptEntry(id, now, pinned, title.ifEmpty { text.take(30).replace("\n", " ").trim() }, text)
        val insertPos = if (pinned) 0 else cache.count { it.isPinned }
        cache.add(insertPos, entry)
        listener?.onPromptInserted(insertPos)
        return id
    }

    fun togglePinned(id: Long) {
        synchronized(this) {
            val index = cache.indexOfFirst { it.id == id }
            if (index < 0) return
            val entry = cache[index]
            val newPinned = !entry.isPinned
            val now = System.currentTimeMillis()
            val cv = ContentValues().apply {
                put(COLUMN_PINNED, if (newPinned) 1 else 0)
                put(COLUMN_TIMESTAMP, now)
            }
            db.writableDatabase.update(TABLE, cv, "$COLUMN_ID = ?", arrayOf(id.toString()))
            val updated = entry.copy(isPinned = newPinned, timestamp = now)
            cache.removeAt(index)
            cache.add(updated)
            cache.sortByDescending { it.timestamp }
            cache.sortByDescending { it.isPinned }
            val newIdx = cache.indexOfFirst { it.id == id }
            listener?.onPromptMoved(index, newIdx)
        }
    }

    fun removeEntry(position: Int) {
        synchronized(this) {
            if (position !in cache.indices) return
            val entry = cache.removeAt(position)
            db.writableDatabase.delete(TABLE, "$COLUMN_ID = ?", arrayOf(entry.id.toString()))
            listener?.onPromptsRemoved(position, 1)
        }
    }

    fun updatePrompt(id: Long, newText: String) {
        synchronized(this) {
            val index = cache.indexOfFirst { it.id == id }
            if (index < 0) return
            val entry = cache[index]
            val newTitle = newText.take(30).replace("\n", " ").trim()
            val cv = ContentValues().apply {
                put(COLUMN_TEXT, newText)
                put(COLUMN_TITLE, newTitle)
            }
            db.writableDatabase.update(TABLE, cv, "$COLUMN_ID = ?", arrayOf(id.toString()))
            val updated = entry.copy(text = newText, title = newTitle)
            cache[index] = updated
            listener?.onPromptUpdated()
        }
    }

    fun removeEntryById(id: Long) {
        synchronized(this) {
            val index = cache.indexOfFirst { it.id == id }
            if (index >= 0) {
                removeEntry(index)
            }
        }
    }

    fun clear() {
        synchronized(this) {
            val size = cache.size
            db.writableDatabase.delete(TABLE, null, null)
            cache.clear()
            listener?.onPromptsRemoved(0, size)
        }
    }

    companion object {
        const val TABLE = "PROMPTS"
        const val COLUMN_ID = "_id"
        const val COLUMN_TIMESTAMP = "TIMESTAMP"
        const val COLUMN_PINNED = "PINNED"
        const val COLUMN_TITLE = "TITLE"
        const val COLUMN_TEXT = "TEXT"

        const val CREATE_TABLE = "CREATE TABLE IF NOT EXISTS $TABLE (" +
                "$COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "$COLUMN_TIMESTAMP INTEGER, " +
                "$COLUMN_PINNED INTEGER, " +
                "$COLUMN_TITLE TEXT, " +
                "$COLUMN_TEXT TEXT)"

        private var instance: PromptDao? = null

        fun ensureTableExists(db: SQLiteDatabase) {
            db.execSQL(CREATE_TABLE)
        }

        fun getInstance(context: Context): PromptDao {
            if (instance == null) {
                instance = PromptDao(Database.getInstance(context.applicationContext))
            }
            return instance!!
        }
    }
}
