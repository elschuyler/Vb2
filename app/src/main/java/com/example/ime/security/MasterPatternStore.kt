package com.example.ime.security

import android.content.Context
import android.content.SharedPreferences
import com.example.logger.LogKeeper
import com.example.logger.LogLevel
import java.security.MessageDigest
import java.security.SecureRandom

enum class PatternPresentationMode {
    STANDARD_GRID,
    KEYBOARD_DISGUISE
}

object MasterPatternStore {
    private const val PREFS_NAME = "vian_security_vault_prefs"
    private const val KEY_PRESENTATION_MODE = "pattern_presentation_mode"
    private const val KEY_PATTERN_HASH = "master_pattern_hash"
    private const val KEY_PATTERN_SALT = "master_pattern_salt"
    private const val KEY_PRIVACY_PATTERN_HASH = "privacy_pattern_hash"
    private const val KEY_PRIVACY_PATTERN_SALT = "privacy_pattern_salt"
    private const val KEY_SEPARATE_PATTERNS = "separate_vault_patterns"
    private const val KEY_DISGUISE_SEQUENCE = "disguise_key_sequence"
    private const val KEY_FAILED_ATTEMPTS = "vault_failed_attempts"
    private const val KEY_LAST_FAILED_MS = "vault_last_failed_ms"

    // Default patterns for out-of-the-box immediate testing
    val DEFAULT_PATTERN_A = listOf(0, 1, 2, 5, 8)
    val DEFAULT_PATTERN_B = listOf(0, 3, 6, 7, 8)
    val DEFAULT_PATTERN_C = listOf(0, 1, 2, 4, 6, 7, 8)
    val DEFAULT_DISGUISE_SEQUENCE = listOf("Q", "W", "E", "R")

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getPresentationMode(context: Context): PatternPresentationMode {
        val raw = getPrefs(context).getString(KEY_PRESENTATION_MODE, PatternPresentationMode.STANDARD_GRID.name)
        return try {
            PatternPresentationMode.valueOf(raw ?: PatternPresentationMode.STANDARD_GRID.name)
        } catch (e: Exception) {
            PatternPresentationMode.STANDARD_GRID
        }
    }

    fun setPresentationMode(context: Context, mode: PatternPresentationMode) {
        getPrefs(context).edit().putString(KEY_PRESENTATION_MODE, mode.name).apply()
        LogKeeper.logEvent("PatternStore", "Presentation mode updated to ${mode.name}", LogLevel.INFO)
    }

    fun areSeparatePatternsEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SEPARATE_PATTERNS, false)
    }

    fun setSeparatePatternsEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SEPARATE_PATTERNS, enabled).apply()
        LogKeeper.logEvent("PatternStore", "Separate patterns setting updated: $enabled", LogLevel.INFO)
    }

    fun isPatternSet(context: Context, vaultType: VaultType = VaultType.SECURITY): Boolean {
        val prefs = getPrefs(context)
        val isSeparate = areSeparatePatternsEnabled(context)
        return if (vaultType == VaultType.PRIVACY && isSeparate) {
            prefs.contains(KEY_PRIVACY_PATTERN_HASH)
        } else {
            prefs.contains(KEY_PATTERN_HASH)
        }
    }

    fun verifyPattern(context: Context, pattern: List<Int>, vaultType: VaultType = VaultType.SECURITY): Boolean {
        val prefs = getPrefs(context)
        val isSeparate = areSeparatePatternsEnabled(context)
        val targetHashKey = if (vaultType == VaultType.PRIVACY && isSeparate) KEY_PRIVACY_PATTERN_HASH else KEY_PATTERN_HASH
        val targetSaltKey = if (vaultType == VaultType.PRIVACY && isSeparate) KEY_PRIVACY_PATTERN_SALT else KEY_PATTERN_SALT

        val storedHash = prefs.getString(targetHashKey, null)
        val storedSalt = prefs.getString(targetSaltKey, null)

        if (storedHash == null || storedSalt == null) {
            // Unset: accept default shapes (A, B, C) for testing
            val isDefaultMatch = pattern == DEFAULT_PATTERN_A || pattern == DEFAULT_PATTERN_B || pattern == DEFAULT_PATTERN_C
            if (isDefaultMatch) {
                resetFailedAttempts(context)
                LogKeeper.logEvent("PatternStore", "Unlock verified with default pattern", LogLevel.INFO)
                return true
            } else {
                recordFailedAttempt(context)
                LogKeeper.logEvent("PatternStore", "Default pattern verification failed", LogLevel.WARN)
                return false
            }
        }

        val patternString = pattern.joinToString(",")
        val computedHash = hashWithSalt(patternString, storedSalt)
        val valid = computedHash.equals(storedHash, ignoreCase = true)

        if (valid) {
            resetFailedAttempts(context)
            LogKeeper.logEvent("PatternStore", "Pattern verified successfully for $vaultType", LogLevel.INFO)
        } else {
            recordFailedAttempt(context)
            LogKeeper.logEvent("PatternStore", "Pattern verification failed for $vaultType", LogLevel.WARN)
        }
        return valid
    }

    fun verifyDisguiseSequence(context: Context, sequence: List<String>, vaultType: VaultType = VaultType.SECURITY): Boolean {
        val prefs = getPrefs(context)
        val stored = prefs.getString(KEY_DISGUISE_SEQUENCE, null)
        if (stored == null) {
            val upper = sequence.map { it.trim().uppercase() }
            val match = upper == DEFAULT_DISGUISE_SEQUENCE || (upper.size >= 4 && upper.take(4) == DEFAULT_DISGUISE_SEQUENCE)
            if (match) {
                resetFailedAttempts(context)
                LogKeeper.logEvent("PatternStore", "Disguise unlock verified with default sequence", LogLevel.INFO)
                return true
            } else {
                recordFailedAttempt(context)
                LogKeeper.logEvent("PatternStore", "Disguise unlock failed", LogLevel.WARN)
                return false
            }
        }

        val target = stored.split(",").map { it.trim().uppercase() }
        val input = sequence.map { it.trim().uppercase() }
        val valid = input == target
        if (valid) {
            resetFailedAttempts(context)
            LogKeeper.logEvent("PatternStore", "Disguise sequence verified for $vaultType", LogLevel.INFO)
        } else {
            recordFailedAttempt(context)
            LogKeeper.logEvent("PatternStore", "Disguise verification failed for $vaultType", LogLevel.WARN)
        }
        return valid
    }

    fun setPattern(context: Context, pattern: List<Int>, vaultType: VaultType = VaultType.SECURITY) {
        val prefs = getPrefs(context)
        val isSeparate = areSeparatePatternsEnabled(context)
        val targetHashKey = if (vaultType == VaultType.PRIVACY && isSeparate) KEY_PRIVACY_PATTERN_HASH else KEY_PATTERN_HASH
        val targetSaltKey = if (vaultType == VaultType.PRIVACY && isSeparate) KEY_PRIVACY_PATTERN_SALT else KEY_PATTERN_SALT

        val salt = generateSalt()
        val patternString = pattern.joinToString(",")
        val hash = hashWithSalt(patternString, salt)

        prefs.edit()
            .putString(targetHashKey, hash)
            .putString(targetSaltKey, salt)
            .apply()
        LogKeeper.logEvent("PatternStore", "Pattern saved for $vaultType (length: ${pattern.size})", LogLevel.INFO)
    }

    fun setDisguiseSequence(context: Context, sequence: List<String>) {
        val formatted = sequence.map { it.trim().uppercase() }.joinToString(",")
        getPrefs(context).edit().putString(KEY_DISGUISE_SEQUENCE, formatted).apply()
        LogKeeper.logEvent("PatternStore", "Disguise sequence saved (length: ${sequence.size})", LogLevel.INFO)
    }

    fun recordFailedAttempt(context: Context) {
        val prefs = getPrefs(context)
        val current = prefs.getInt(KEY_FAILED_ATTEMPTS, 0)
        prefs.edit()
            .putInt(KEY_FAILED_ATTEMPTS, current + 1)
            .putLong(KEY_LAST_FAILED_MS, System.currentTimeMillis())
            .apply()
    }

    fun resetFailedAttempts(context: Context) {
        getPrefs(context).edit()
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .apply()
    }

    fun getFailedAttempts(context: Context): Int {
        return getPrefs(context).getInt(KEY_FAILED_ATTEMPTS, 0)
    }

    private fun generateSalt(): String {
        val random = SecureRandom()
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun hashWithSalt(data: String, salt: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val combined = "$salt:$data".toByteArray(Charsets.UTF_8)
        val digest = md.digest(combined)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
