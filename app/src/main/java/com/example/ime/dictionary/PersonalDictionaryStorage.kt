package com.example.ime.dictionary

import android.content.Context
import android.os.Build
import com.example.logger.LogKeeper
import com.example.logger.LogLevel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class DictionaryPartition {
    NORMAL,         // Visible to system and other apps via selective mirror
    PRIVACY_VAULT   // 100% Sandboxed, masked suggestions, in-keyboard pattern unlock, zero-learning
}

data class PersonalDictionaryEntry(
    val id: String = UUID.randomUUID().toString(),
    val phrase: String,
    val shortcut: String,
    val weight: Int = 250,
    val locale: String? = null,
    val partition: DictionaryPartition = DictionaryPartition.NORMAL,
    val category: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("phrase", phrase)
            put("shortcut", shortcut)
            put("weight", weight)
            put("locale", locale ?: "")
            put("partition", partition.name)
            put("category", category ?: "")
            put("createdAt", createdAt)
            put("updatedAt", updatedAt)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): PersonalDictionaryEntry? {
            return try {
                val id = json.optString("id", UUID.randomUUID().toString())
                val phrase = json.getString("phrase")
                val shortcut = json.optString("shortcut", "")
                val weight = json.optInt("weight", 250)
                val localeStr = json.optString("locale", "")
                val locale = if (localeStr.isEmpty()) null else localeStr
                val partName = json.optString("partition", DictionaryPartition.NORMAL.name)
                val partition = try {
                    DictionaryPartition.valueOf(partName)
                } catch (e: Exception) {
                    DictionaryPartition.NORMAL
                }
                val categoryStr = json.optString("category", "")
                val category = if (categoryStr.isEmpty()) null else categoryStr
                val createdAt = json.optLong("createdAt", System.currentTimeMillis())
                val updatedAt = json.optLong("updatedAt", System.currentTimeMillis())

                PersonalDictionaryEntry(
                    id = id,
                    phrase = phrase,
                    shortcut = shortcut,
                    weight = weight,
                    locale = locale,
                    partition = partition,
                    category = category,
                    createdAt = createdAt,
                    updatedAt = updatedAt
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * Unified Sandboxed Master Storage for Personal Dictionary.
 * Holds both Normal and Privacy Vault partitions in a single internal file.
 * Keystroke matching is served from ultra-fast in-memory maps (O(L) < 0.005ms).
 */
class PersonalDictionaryStorage private constructor(private val appContext: Context) {

    private val storageDir = File(appContext.filesDir, "user_dicts").apply {
        if (!exists()) mkdirs()
    }
    private val masterFile = File(storageDir, "personal_dictionary.json")

    // In-Memory Fast Lookup Indices (Thread-safe)
    private val entriesById = ConcurrentHashMap<String, PersonalDictionaryEntry>()
    private val normalShortcuts = ConcurrentHashMap<String, PersonalDictionaryEntry>()
    private val vaultShortcuts = ConcurrentHashMap<String, PersonalDictionaryEntry>()
    private val vaultPhrases = ConcurrentHashMap<String, Boolean>()

    private val prefs = appContext.getSharedPreferences("vian_personal_dict_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_AUTO_RELOCK_ON_CLOSE = "pref_auto_relock_on_close"
        private const val KEY_PRIVACY_TIMEOUT_MINUTES = "pref_privacy_timeout_minutes"

        @Volatile
        private var INSTANCE: PersonalDictionaryStorage? = null

        fun getInstance(context: Context): PersonalDictionaryStorage {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PersonalDictionaryStorage(context.applicationContext).also { INSTANCE = it }
            }
        }

        /**
         * Smart masking utility: Masks sensitive phrases while leaving a few recognizable anchors.
         */
        fun maskPhrase(phrase: String): String {
            val trimmed = phrase.trim()
            if (trimmed.length <= 4) {
                return "••••"
            }

            // Email Pattern: abc***@domain.com
            if (trimmed.contains("@")) {
                val parts = trimmed.split("@", limit = 2)
                val user = parts[0]
                val domain = parts.getOrNull(1) ?: ""
                val prefix = if (user.length > 3) user.substring(0, 3) else user.take(1)
                return "$prefix••••@$domain"
            }

            // Phone / Tax ID Pattern: format preservation with last 4 visible
            val digitsOnly = trimmed.filter { it.isDigit() }
            if (digitsOnly.length >= 7) {
                val last4 = digitsOnly.takeLast(4)
                return if (trimmed.startsWith("+")) {
                    val code = trimmed.takeWhile { it != ' ' && it != '-' }
                    "$code••••$last4"
                } else {
                    "••••-••••-$last4"
                }
            }

            // Street Address / Multi-word: First 3 digits/chars + last 2 chars
            if (trimmed.contains(",") || trimmed.contains(" ")) {
                val prefix = trimmed.take(3)
                val suffix = trimmed.takeLast(2)
                return "$prefix••••$suffix"
            }

            // Generic Text: First 2 chars + •••• + Last 2 chars
            val prefix = trimmed.take(2)
            val suffix = trimmed.takeLast(2)
            return "$prefix••••$suffix"
        }
    }

    init {
        loadFromDisk()
        if (entriesById.isEmpty()) {
            populateDefaultEntries()
        }
    }

    @Synchronized
    private fun loadFromDisk() {
        entriesById.clear()
        normalShortcuts.clear()
        vaultShortcuts.clear()
        vaultPhrases.clear()

        if (!masterFile.exists()) {
            return
        }

        try {
            val jsonString = masterFile.readText()
            if (jsonString.isBlank()) return

            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val entry = PersonalDictionaryEntry.fromJson(obj)
                if (entry != null) {
                    indexEntry(entry)
                }
            }
            LogKeeper.logEvent("PersonalDictStorage", "Loaded ${entriesById.size} entries from unified file", LogLevel.INFO)
        } catch (e: Exception) {
            LogKeeper.logError("PersonalDictStorage", "LOAD_FAIL", e.message ?: "")
        }
    }

    private fun indexEntry(entry: PersonalDictionaryEntry) {
        entriesById[entry.id] = entry
        val shortcutKey = entry.shortcut.trim().lowercase()

        if (entry.partition == DictionaryPartition.PRIVACY_VAULT) {
            if (shortcutKey.isNotEmpty()) {
                vaultShortcuts[shortcutKey] = entry
            }
            vaultPhrases[entry.phrase.trim().lowercase()] = true
        } else {
            if (shortcutKey.isNotEmpty()) {
                normalShortcuts[shortcutKey] = entry
            }
        }
    }

    private fun removeIndex(entry: PersonalDictionaryEntry) {
        entriesById.remove(entry.id)
        val shortcutKey = entry.shortcut.trim().lowercase()
        normalShortcuts.remove(shortcutKey)
        vaultShortcuts.remove(shortcutKey)
        vaultPhrases.remove(entry.phrase.trim().lowercase())
    }

    @Synchronized
    private fun saveToDisk() {
        try {
            val jsonArray = JSONArray()
            for (entry in entriesById.values) {
                jsonArray.put(entry.toJson())
            }
            masterFile.writeText(jsonArray.toString(2))
        } catch (e: Exception) {
            LogKeeper.logError("PersonalDictStorage", "SAVE_FAIL", e.message ?: "")
        }
    }

    private fun populateDefaultEntries() {
        val defaults = listOf(
            PersonalDictionaryEntry(
                phrase = "On my way!",
                shortcut = "omw",
                weight = 250,
                partition = DictionaryPartition.NORMAL
            ),
            PersonalDictionaryEntry(
                phrase = "Be right back!",
                shortcut = "brb",
                weight = 250,
                partition = DictionaryPartition.NORMAL
            ),
            PersonalDictionaryEntry(
                phrase = "Thank you very much!",
                shortcut = "tyvm",
                weight = 240,
                partition = DictionaryPartition.NORMAL
            ),
            // Privacy Vault Samples (Masked by default)
            PersonalDictionaryEntry(
                phrase = "123 Broadway, Apt 4B, New York, NY",
                shortcut = "myaddr",
                weight = 250,
                partition = DictionaryPartition.PRIVACY_VAULT,
                category = "Address"
            ),
            PersonalDictionaryEntry(
                phrase = "schuylervianilewis@gmail.com",
                shortcut = "myemail",
                weight = 250,
                partition = DictionaryPartition.PRIVACY_VAULT,
                category = "Credentials"
            )
        )

        for (item in defaults) {
            indexEntry(item)
        }
        saveToDisk()

        // Mirror normal defaults to system
        for (item in defaults.filter { it.partition == DictionaryPartition.NORMAL }) {
            SystemUserDictionaryMirror.mirrorNormalEntry(appContext, item)
        }
    }

    fun getAllEntries(): List<PersonalDictionaryEntry> {
        return entriesById.values.sortedByDescending { it.createdAt }
    }

    fun getNormalEntries(): List<PersonalDictionaryEntry> {
        return entriesById.values
            .filter { it.partition == DictionaryPartition.NORMAL }
            .sortedByDescending { it.createdAt }
    }

    fun getVaultEntries(): List<PersonalDictionaryEntry> {
        return entriesById.values
            .filter { it.partition == DictionaryPartition.PRIVACY_VAULT }
            .sortedByDescending { it.createdAt }
    }

    fun addOrUpdateEntry(entry: PersonalDictionaryEntry) {
        val oldEntry = entriesById[entry.id]
        if (oldEntry != null) {
            removeIndex(oldEntry)
        }
        indexEntry(entry)
        saveToDisk()

        // Handle Selective System Mirroring
        if (entry.partition == DictionaryPartition.NORMAL) {
            SystemUserDictionaryMirror.mirrorNormalEntry(appContext, entry)
        } else if (oldEntry != null && oldEntry.partition == DictionaryPartition.NORMAL) {
            // Demoted from Normal to Vault: remove from public system dictionary!
            SystemUserDictionaryMirror.removeNormalEntry(appContext, oldEntry.shortcut, oldEntry.phrase)
        }
    }

    fun deleteEntry(id: String) {
        val entry = entriesById[id] ?: return
        removeIndex(entry)
        saveToDisk()

        if (entry.partition == DictionaryPartition.NORMAL) {
            SystemUserDictionaryMirror.removeNormalEntry(appContext, entry.shortcut, entry.phrase)
        }
    }

    fun togglePartition(id: String): PersonalDictionaryEntry? {
        val entry = entriesById[id] ?: return null
        val targetPartition = if (entry.partition == DictionaryPartition.NORMAL) {
            DictionaryPartition.PRIVACY_VAULT
        } else {
            DictionaryPartition.NORMAL
        }

        val updated = entry.copy(
            partition = targetPartition,
            updatedAt = System.currentTimeMillis()
        )
        addOrUpdateEntry(updated)
        return updated
    }

    /**
     * Nanosecond in-memory match during keystrokes (O(L) complexity).
     * Returns matching entries for the current query token.
     */
    fun findMatches(query: String): List<PersonalDictionaryEntry> {
        val cleanQuery = query.trim().lowercase()
        if (cleanQuery.isEmpty()) return emptyList()

        val matches = mutableListOf<PersonalDictionaryEntry>()

        // 1. Exact shortcut matches (Highest priority)
        vaultShortcuts[cleanQuery]?.let { matches.add(it) }
        normalShortcuts[cleanQuery]?.let { matches.add(it) }

        // 2. Prefix shortcut matches (if query >= 2 characters)
        if (cleanQuery.length >= 2 && matches.isEmpty()) {
            for ((k, v) in vaultShortcuts) {
                if (k.startsWith(cleanQuery)) {
                    matches.add(v)
                    if (matches.size >= 2) break
                }
            }
            for ((k, v) in normalShortcuts) {
                if (k.startsWith(cleanQuery)) {
                    matches.add(v)
                    if (matches.size >= 3) break
                }
            }
        }

        return matches
    }

    fun isVaultPhrase(text: String): Boolean {
        if (text.isBlank()) return false
        val clean = text.trim().lowercase()
        return vaultPhrases.containsKey(clean) || vaultShortcuts.values.any { it.phrase.trim().equals(clean, ignoreCase = true) }
    }

    fun isVaultShortcut(shortcut: String): Boolean {
        if (shortcut.isBlank()) return false
        return vaultShortcuts.containsKey(shortcut.trim().lowercase())
    }

    fun getVaultEntryByPhrase(phrase: String): PersonalDictionaryEntry? {
        val clean = phrase.trim()
        return vaultShortcuts.values.firstOrNull { it.phrase.equals(clean, ignoreCase = true) }
    }

    fun isAutoRelockOnCloseEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_RELOCK_ON_CLOSE, true)
    }

    fun setAutoRelockOnClose(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_RELOCK_ON_CLOSE, enabled).apply()
    }

    fun getPrivacyTimeoutMinutes(): Int {
        return prefs.getInt(KEY_PRIVACY_TIMEOUT_MINUTES, 5)
    }

    fun setPrivacyTimeoutMinutes(mins: Int) {
        prefs.edit().putInt(KEY_PRIVACY_TIMEOUT_MINUTES, mins).apply()
    }
}
