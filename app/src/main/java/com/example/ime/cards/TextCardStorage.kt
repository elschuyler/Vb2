package com.example.ime.cards

import android.content.Context
import android.content.SharedPreferences
import com.example.logger.LogKeeper
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections

/**
 * Unified Partitioned Card Storage for Clipboard and Prompt List.
 * Merges legacy ClipboardStorage and QuickNotesStorage into a single partitioned data store.
 * "Move to Prompt List" is an instant atomic tag toggle on the same item (zero serialization roundtrips).
 */
class TextCardStorage(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val appContext: Context = context.applicationContext

    // Thread-safe in-memory cache
    private val itemsList = Collections.synchronizedList(mutableListOf<TextCardItem>())

    companion object {
        const val PREFS_NAME = "vian_text_cards_store"
        private const val KEY_CARDS = "all_cards_json"
        private const val KEY_MIGRATED = "has_migrated_legacy_stores"
        const val MAX_RECENT_CLIPS = 25

        @Volatile
        private var instance: TextCardStorage? = null

        fun getInstance(context: Context): TextCardStorage {
            return instance ?: synchronized(this) {
                instance ?: TextCardStorage(context.applicationContext).also { instance = it }
            }
        }
    }

    init {
        loadFromDisk()
        checkAndMigrateLegacyStores()
    }

    @Synchronized
    private fun loadFromDisk() {
        itemsList.clear()
        val rawJson = prefs.getString(KEY_CARDS, null) ?: return
        try {
            val decoded = TextCardItem.decodeList(rawJson)
            if (decoded.isNotEmpty()) {
                itemsList.addAll(decoded)
                return
            }
            // Fallback for raw legacy JSONArray format
            val jsonArray = JSONArray(rawJson)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val item = TextCardItem.fromJson(obj)
                if (item != null) {
                    itemsList.add(item)
                }
            }
        } catch (e: Exception) {
            LogKeeper.logError("TextCardStorage", "JSON_LOAD_ERR", "Failed loading cards: ${e.message}")
        }
    }

    @Synchronized
    private fun saveToDisk() {
        try {
            val snapshot = synchronized(itemsList) { itemsList.toList() }
            val encoded = TextCardItem.encodeList(snapshot)
            prefs.edit().putString(KEY_CARDS, encoded).apply()
        } catch (e: Exception) {
            LogKeeper.logError("TextCardStorage", "JSON_SAVE_ERR", "Failed saving cards: ${e.message}")
        }
    }

    @Synchronized
    private fun checkAndMigrateLegacyStores() {
        if (prefs.getBoolean(KEY_MIGRATED, false)) return

        try {
            var migratedCount = 0
            // 1. Migrate legacy clipboard store
            val clipPrefs = appContext.getSharedPreferences("vian_clipboard_store", Context.MODE_PRIVATE)
            val legacyPinnedClips = parseStringList(clipPrefs.getString("pinned_items", null))
            val legacyRecentClips = parseStringList(clipPrefs.getString("recent_items", null))

            for (text in legacyPinnedClips) {
                if (findExisting(EntryType.CLIPBOARD, text) == null) {
                    itemsList.add(TextCardItem(text = text, type = EntryType.CLIPBOARD, isPinned = true))
                    migratedCount++
                }
            }
            for (text in legacyRecentClips) {
                if (findExisting(EntryType.CLIPBOARD, text) == null) {
                    itemsList.add(TextCardItem(text = text, type = EntryType.CLIPBOARD, isPinned = false))
                    migratedCount++
                }
            }

            // 2. Migrate legacy quick notes store
            val notesPrefs = appContext.getSharedPreferences("vian_quick_notes_store", Context.MODE_PRIVATE)
            val legacyPinnedNotes = parseStringList(notesPrefs.getString("pinned_notes", null))
            val legacyNotes = parseStringList(notesPrefs.getString("saved_notes", null))

            for (text in legacyPinnedNotes) {
                if (findExisting(EntryType.PROMPT, text) == null) {
                    itemsList.add(TextCardItem(text = text, type = EntryType.PROMPT, isPinned = true))
                    migratedCount++
                }
            }
            for (text in legacyNotes) {
                if (findExisting(EntryType.PROMPT, text) == null) {
                    itemsList.add(TextCardItem(text = text, type = EntryType.PROMPT, isPinned = false))
                    migratedCount++
                }
            }

            if (migratedCount > 0) {
                saveToDisk()
                LogKeeper.logEvent("TextCardStorage", "Migrated $migratedCount legacy cards into partitioned store")
            }
            prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
        } catch (e: Exception) {
            LogKeeper.logError("TextCardStorage", "MIGRATION_ERR", "Legacy migration failed: ${e.message}")
        }
    }

    private fun parseStringList(jsonStr: String?): List<String> {
        if (jsonStr.isNullOrEmpty()) return emptyList()
        val list = mutableListOf<String>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val s = arr.getString(i).trim()
                if (s.isNotEmpty()) list.add(s)
            }
        } catch (e: Exception) {
            // ignore
        }
        return list
    }

    private fun findExisting(type: EntryType, text: String): TextCardItem? {
        synchronized(itemsList) {
            return itemsList.firstOrNull { it.type == type && it.text == text }
        }
    }

    fun getItems(type: EntryType): List<TextCardItem> {
        synchronized(itemsList) {
            val matching = itemsList.filter { it.type == type }
            val pinned = matching.filter { it.isPinned }.sortedByDescending { it.timestamp }
            val unpinned = matching.filter { !it.isPinned }.sortedByDescending { it.timestamp }
            return pinned + unpinned
        }
    }

    fun getPinnedItems(type: EntryType): List<TextCardItem> {
        synchronized(itemsList) {
            return itemsList.filter { it.type == type && it.isPinned }.sortedByDescending { it.timestamp }
        }
    }

    fun getUnpinnedItems(type: EntryType): List<TextCardItem> {
        synchronized(itemsList) {
            return itemsList.filter { it.type == type && !it.isPinned }.sortedByDescending { it.timestamp }
        }
    }

    fun searchItems(type: EntryType, query: String): List<TextCardItem> {
        val trimmed = query.trim()
        val all = getItems(type)
        if (trimmed.isEmpty()) return all
        return all.filter { it.text.contains(trimmed, ignoreCase = true) }
    }

    @Synchronized
    fun addClip(text: String): TextCardItem? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        val existing = findExisting(EntryType.CLIPBOARD, trimmed)
        if (existing != null) {
            if (existing.isPinned) {
                return existing
            } else {
                existing.timestamp = System.currentTimeMillis()
                saveToDisk()
                return existing
            }
        }

        val newItem = TextCardItem(
            text = trimmed,
            type = EntryType.CLIPBOARD,
            isPinned = false,
            timestamp = System.currentTimeMillis()
        )
        itemsList.add(newItem)

        // Enforce MAX_RECENT_CLIPS for unpinned clips
        val unpinned = itemsList.filter { it.type == EntryType.CLIPBOARD && !it.isPinned }
            .sortedBy { it.timestamp }
        if (unpinned.size > MAX_RECENT_CLIPS) {
            val toRemoveCount = unpinned.size - MAX_RECENT_CLIPS
            for (i in 0 until toRemoveCount) {
                itemsList.remove(unpinned[i])
            }
        }

        saveToDisk()
        LogKeeper.logEvent("TextCardStorage", "Added clip: ${trimmed.take(20)}")
        return newItem
    }

    @Synchronized
    fun addPrompt(text: String, isPinned: Boolean = false): TextCardItem? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        val existing = findExisting(EntryType.PROMPT, trimmed)
        if (existing != null) {
            existing.timestamp = System.currentTimeMillis()
            if (isPinned) existing.isPinned = true
            saveToDisk()
            return existing
        }

        val newItem = TextCardItem(
            text = trimmed,
            type = EntryType.PROMPT,
            isPinned = isPinned,
            timestamp = System.currentTimeMillis()
        )
        itemsList.add(newItem)
        saveToDisk()
        LogKeeper.logEvent("TextCardStorage", "Added prompt note: ${trimmed.take(20)}")
        return newItem
    }

    @Synchronized
    fun updatePrompt(oldText: String, newText: String) {
        val trimmedNew = newText.trim()
        if (trimmedNew.isEmpty()) {
            deleteItemByText(EntryType.PROMPT, oldText)
            return
        }

        val existing = findExisting(EntryType.PROMPT, oldText)
        if (existing != null) {
            existing.text = trimmedNew
            existing.timestamp = System.currentTimeMillis()
            saveToDisk()
            LogKeeper.logEvent("TextCardStorage", "Updated prompt note")
        } else {
            addPrompt(trimmedNew)
        }
    }

    @Synchronized
    fun updatePromptById(id: String, newText: String) {
        val trimmed = newText.trim()
        if (trimmed.isEmpty()) {
            deleteItem(id)
            return
        }
        val item = itemsList.firstOrNull { it.id == id } ?: return
        item.text = trimmed
        item.timestamp = System.currentTimeMillis()
        saveToDisk()
    }

    @Synchronized
    fun togglePin(id: String) {
        val item = itemsList.firstOrNull { it.id == id } ?: return
        item.isPinned = !item.isPinned
        item.timestamp = System.currentTimeMillis()
        saveToDisk()
        LogKeeper.logEvent("TextCardStorage", "Toggled pin for id=$id (pinned=${item.isPinned})")
    }

    @Synchronized
    fun togglePinByText(type: EntryType, text: String) {
        val item = findExisting(type, text) ?: return
        item.isPinned = !item.isPinned
        item.timestamp = System.currentTimeMillis()
        saveToDisk()
    }

    fun isPinned(id: String): Boolean {
        return itemsList.firstOrNull { it.id == id }?.isPinned ?: false
    }

    fun isPinnedByText(type: EntryType, text: String): Boolean {
        return findExisting(type, text)?.isPinned ?: false
    }

    @Synchronized
    fun deleteItem(id: String) {
        val removed = itemsList.removeAll { it.id == id }
        if (removed) {
            saveToDisk()
            LogKeeper.logEvent("TextCardStorage", "Deleted item id=$id")
        }
    }

    @Synchronized
    fun deleteItemByText(type: EntryType, text: String) {
        val removed = itemsList.removeAll { it.type == type && it.text == text }
        if (removed) {
            saveToDisk()
        }
    }

    @Synchronized
    fun clearUnpinned(type: EntryType) {
        val removed = itemsList.removeAll { it.type == type && !it.isPinned }
        if (removed) {
            saveToDisk()
            LogKeeper.logEvent("TextCardStorage", "Cleared unpinned items for partition=$type")
        }
    }

    /**
     * Instant atomic partition switch (zero serialization roundtrips).
     * Changes the EntryType from CLIPBOARD to PROMPT directly in the shared store.
     */
    @Synchronized
    fun moveToPrompt(id: String): Boolean {
        val item = itemsList.firstOrNull { it.id == id } ?: return false
        item.type = EntryType.PROMPT
        item.timestamp = System.currentTimeMillis()
        saveToDisk()
        LogKeeper.logEvent("TextCardStorage", "Atomic tag toggle: Moved clip id=$id to PROMPT partition")
        return true
    }

    @Synchronized
    fun moveToPromptByText(text: String): Boolean {
        val item = findExisting(EntryType.CLIPBOARD, text) ?: return false
        item.type = EntryType.PROMPT
        item.timestamp = System.currentTimeMillis()
        saveToDisk()
        LogKeeper.logEvent("TextCardStorage", "Atomic tag toggle: Moved clip to PROMPT partition")
        return true
    }
}
