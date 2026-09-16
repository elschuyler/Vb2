// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.database

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import helium314.keyboard.latin.utils.LogCatcher
import java.util.regex.Pattern

data class VoiceReplacementEntry(
    val id: Long,
    val originalWord: String,
    val replacementWord: String,
    val isWholeWord: Boolean = true,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Thread-safe DAO and in-memory cache for Word Improvement dictionary rules.
 * Maps misheard or phonetic words to user's desired terms (e.g. "knit" -> "need").
 */
class VoiceReplacementDao private constructor(private val db: Database) {

    interface Listener {
        fun onReplacementsChanged()
    }

    var listener: Listener? = null

    private val cache = mutableListOf<VoiceReplacementEntry>().apply {
        ensureTableExists(db.writableDatabase)
        loadFromDb(this)
    }

    private fun loadFromDb(target: MutableList<VoiceReplacementEntry>) {
        try {
            db.readableDatabase.query(
                TABLE,
                arrayOf(COLUMN_ID, COLUMN_ORIGINAL, COLUMN_REPLACEMENT, COLUMN_WHOLE_WORD, COLUMN_TIMESTAMP),
                null,
                null,
                null,
                null,
                "$COLUMN_TIMESTAMP DESC"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    target.add(
                        VoiceReplacementEntry(
                            id = cursor.getLong(0),
                            originalWord = cursor.getString(1) ?: "",
                            replacementWord = cursor.getString(2) ?: "",
                            isWholeWord = cursor.getInt(3) != 0,
                            timestamp = cursor.getLong(4)
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            LogCatcher.e("VoiceReplacementDao", "Error loading voice replacements from database", t)
        }
    }

    val count: Int get() = synchronized(this) { cache.size }

    fun getAll(): List<VoiceReplacementEntry> = synchronized(this) {
        cache.toList()
    }

    fun getEntry(id: Long): VoiceReplacementEntry? = synchronized(this) {
        cache.firstOrNull { it.id == id }
    }

    fun addOrUpdate(originalWord: String, replacementWord: String, isWholeWord: Boolean = true): Long = synchronized(this) {
        val trimmedOrig = originalWord.trim()
        val trimmedRepl = replacementWord.trim()
        if (trimmedOrig.isEmpty() || trimmedRepl.isEmpty()) return -1L

        val existingIndex = cache.indexOfFirst { it.originalWord.equals(trimmedOrig, ignoreCase = true) }
        val now = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put(COLUMN_ORIGINAL, trimmedOrig)
            put(COLUMN_REPLACEMENT, trimmedRepl)
            put(COLUMN_WHOLE_WORD, if (isWholeWord) 1 else 0)
            put(COLUMN_TIMESTAMP, now)
        }

        val id: Long
        if (existingIndex >= 0) {
            val existing = cache[existingIndex]
            db.writableDatabase.update(TABLE, cv, "$COLUMN_ID = ?", arrayOf(existing.id.toString()))
            id = existing.id
            cache[existingIndex] = VoiceReplacementEntry(id, trimmedOrig, trimmedRepl, isWholeWord, now)
        } else {
            id = db.writableDatabase.insert(TABLE, null, cv)
            if (id != -1L) {
                cache.add(0, VoiceReplacementEntry(id, trimmedOrig, trimmedRepl, isWholeWord, now))
            }
        }
        listener?.onReplacementsChanged()
        return id
    }

    fun delete(id: Long): Boolean = synchronized(this) {
        val deleted = db.writableDatabase.delete(TABLE, "$COLUMN_ID = ?", arrayOf(id.toString())) > 0
        if (deleted) {
            cache.removeAll { it.id == id }
            listener?.onReplacementsChanged()
        }
        deleted
    }

    fun clear(): Unit = synchronized(this) {
        db.writableDatabase.delete(TABLE, null, null)
        cache.clear()
        listener?.onReplacementsChanged()
    }

    /**
     * Applies configured phonetic replacements onto recognized speech text.
     * Respects whole-word boundaries when enabled.
     */
    fun applyReplacements(inputText: String): String = synchronized(this) {
        if (cache.isEmpty() || inputText.isEmpty()) return inputText
        var result = inputText
        for (entry in cache) {
            if (entry.originalWord.isEmpty()) continue
            if (entry.isWholeWord) {
                val escaped = Pattern.quote(entry.originalWord)
                val regex = "(?i)\\b$escaped\\b".toRegex()
                result = regex.replace(result, entry.replacementWord)
            } else {
                result = result.replace(entry.originalWord, entry.replacementWord, ignoreCase = true)
            }
        }
        return result
    }

    companion object {
        const val TABLE = "VOICE_REPLACEMENTS"
        const val COLUMN_ID = "_id"
        const val COLUMN_ORIGINAL = "ORIGINAL_WORD"
        const val COLUMN_REPLACEMENT = "REPLACEMENT_WORD"
        const val COLUMN_WHOLE_WORD = "IS_WHOLE_WORD"
        const val COLUMN_TIMESTAMP = "TIMESTAMP"

        const val CREATE_TABLE = "CREATE TABLE IF NOT EXISTS $TABLE (" +
                "$COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "$COLUMN_ORIGINAL TEXT NOT NULL, " +
                "$COLUMN_REPLACEMENT TEXT NOT NULL, " +
                "$COLUMN_WHOLE_WORD INTEGER NOT NULL DEFAULT 1, " +
                "$COLUMN_TIMESTAMP INTEGER NOT NULL)"

        private var instance: VoiceReplacementDao? = null

        fun ensureTableExists(db: SQLiteDatabase) {
            db.execSQL(CREATE_TABLE)
        }

        fun getInstance(context: Context): VoiceReplacementDao {
            if (instance == null) {
                instance = VoiceReplacementDao(Database.getInstance(context.applicationContext))
            }
            return instance!!
        }
    }
}
