package com.example.ime.voice

import android.content.Context
import android.content.SharedPreferences
import com.example.logger.LogKeeper
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

data class WordReplacement(
    val id: String,
    val target: String,
    val replacement: String
)

/**
 * Lightweight Word Improvement store backed by SharedPreferences JSON.
 * Replaces legacy SQLite/Room dependencies with zero startup overhead.
 * Maintains an in-memory cached map for whole-word phonetic substitutions.
 */
object WordReplacementStore {
    private const val TAG = "WordReplacementStore"
    private const val PREFS_NAME = "vian_voice_replacements"
    private const val KEY_REPLACEMENTS_JSON = "replacements_json"

    private val cachedReplacements = ConcurrentHashMap<String, String>()
    private var isLoaded = false

    @Synchronized
    fun getAll(context: Context): List<WordReplacement> {
        ensureLoaded(context)
        val list = mutableListOf<WordReplacement>()
        val prefs = getPrefs(context)
        val jsonString = prefs.getString(KEY_REPLACEMENTS_JSON, null) ?: return emptyList()

        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    WordReplacement(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        target = obj.getString("target"),
                        replacement = obj.getString("replacement")
                    )
                )
            }
        } catch (e: Exception) {
            LogKeeper.logError(TAG, "Failed to parse replacements JSON", e.message ?: "")
        }
        return list
    }

    @Synchronized
    fun addReplacement(context: Context, target: String, replacement: String): Boolean {
        if (target.isBlank()) return false
        val list = getAll(context).toMutableList()
        // Remove existing if matching target case-insensitively
        list.removeAll { it.target.equals(target.trim(), ignoreCase = true) }
        list.add(
            WordReplacement(
                id = java.util.UUID.randomUUID().toString(),
                target = target.trim(),
                replacement = replacement.trim()
            )
        )
        return saveList(context, list)
    }

    @Synchronized
    fun deleteReplacement(context: Context, id: String): Boolean {
        val list = getAll(context).toMutableList()
        val removed = list.removeAll { it.id == id }
        if (removed) {
            saveList(context, list)
        }
        return removed
    }

    @Synchronized
    fun applyReplacements(context: Context, text: String): String {
        if (text.isBlank()) return text
        ensureLoaded(context)
        if (cachedReplacements.isEmpty()) return text

        var result = text
        for ((target, replacement) in cachedReplacements) {
            if (target.isBlank()) continue
            // Whole-word regex match, case-insensitive
            val regex = Pattern.compile("\\b${Pattern.quote(target)}\\b", Pattern.CASE_INSENSITIVE)
            result = regex.matcher(result).replaceAll(replacement)
        }
        return result
    }

    private fun ensureLoaded(context: Context) {
        if (isLoaded) return
        val prefs = getPrefs(context)
        val jsonString = prefs.getString(KEY_REPLACEMENTS_JSON, null)
        cachedReplacements.clear()

        if (!jsonString.isNullOrBlank()) {
            try {
                val jsonArray = JSONArray(jsonString)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val target = obj.optString("target")
                    val replacement = obj.optString("replacement")
                    if (target.isNotBlank()) {
                        cachedReplacements[target] = replacement
                    }
                }
            } catch (e: Exception) {
                LogKeeper.logError(TAG, "Error loading cached replacements", e.message ?: "")
            }
        }
        isLoaded = true
    }

    private fun saveList(context: Context, list: List<WordReplacement>): Boolean {
        val jsonArray = JSONArray()
        cachedReplacements.clear()

        for (item in list) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("target", item.target)
                put("replacement", item.replacement)
            }
            jsonArray.put(obj)
            cachedReplacements[item.target] = item.replacement
        }

        val success = getPrefs(context).edit()
            .putString(KEY_REPLACEMENTS_JSON, jsonArray.toString())
            .commit()

        LogKeeper.logEvent(TAG, "Saved ${list.size} word replacements (success=$success)")
        return success
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
