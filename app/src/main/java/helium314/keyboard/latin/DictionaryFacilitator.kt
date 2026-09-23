package helium314.keyboard.latin

import android.content.Context
import com.android.inputmethod.latin.BinaryDictionary
import com.example.logger.LogKeeper
import com.example.logger.LogTags
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

interface DictionaryFacilitator {
    fun resetDictionaries(
        context: Context,
        locale: Locale,
        useContacts: Boolean,
        useUserHistory: Boolean,
        copyAssets: Boolean,
        registerDictionary: Boolean,
        account: String,
        dictFile: Any?
    )
    fun closeDictionaries()
    fun onFinishInput()
    fun addToUserHistory(
        word: String,
        isBeginningOfSentence: Boolean,
        ngramContext: NgramContext,
        time: Long,
        blockUntilFinished: Boolean
    )
    fun unlearnFromUserHistory(
        word: String,
        ngramContext: NgramContext? = null
    )
    fun demoteWord(
        word: String,
        ngramContext: NgramContext? = null
    )
    fun isBuiltInWord(word: String): Boolean
    fun isUserHistoryWord(word: String): Boolean
    fun isPersonalDictionaryWord(word: String): Boolean
    fun isWordDemoted(word: String): Boolean
    fun addToUserDictionary(word: String, frequency: Int = 250): Boolean
}

open class DictionaryFacilitatorImpl : DictionaryFacilitator {

    private var activeLocale: Locale = Locale.US
    private val dictionaries = ConcurrentHashMap<String, BinaryDictionary>()
    private var userHistoryDictionary: BinaryDictionary? = null
    private var userDictionary: BinaryDictionary? = null
    private var appContext: Context? = null
    private val demotedWords = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    override fun resetDictionaries(
        context: Context,
        locale: Locale,
        useContacts: Boolean,
        useUserHistory: Boolean,
        copyAssets: Boolean,
        registerDictionary: Boolean,
        account: String,
        dictFile: Any?
    ) {
        appContext = context.applicationContext
        activeLocale = locale
        loadDemotedWords(context, locale.language)
        closeDictionaries()

        val lang = locale.language.lowercase(Locale.US)
        val dictFileName = when (lang) {
            "fr" -> "main_fr.dict"
            else -> "main_en-US.dict"
        }

        try {
            val extractedFile = extractDictAssetToDisk(context, dictFileName)
            if (extractedFile != null && extractedFile.exists()) {
                val binDict = BinaryDictionary(
                    dictFilePath = extractedFile.absolutePath,
                    dictOffset = 0L,
                    dictSize = extractedFile.length(),
                    isUpdatable = false,
                    locale = locale
                )
                dictionaries[dictFileName] = binDict
                LogKeeper.logEvent(
                    LogTags.DICT,
                    "DictionaryFacilitator reset for locale: $locale using $dictFileName (size: ${extractedFile.length()} bytes)"
                )
            } else {
                LogKeeper.logWarning(
                    LogTags.DICT,
                    "Could not extract dictionary asset for locale: $locale ($dictFileName)"
                )
            }

            // Initialize dynamic user history dictionary
            val historyDir = File(context.filesDir, "user_history")
            if (!historyDir.exists()) historyDir.mkdirs()
            val historyFile = File(historyDir, "history_${lang}.dict")
            if (!historyFile.exists() || historyFile.length() == 0L) {
                com.android.inputmethod.latin.utils.BinaryDictionaryUtils.createEmptyDictFile(
                    filePath = historyFile.absolutePath,
                    dictVersion = 403L,
                    locale = locale.toString()
                )
            }

            if (historyFile.exists() && historyFile.length() > 0L) {
                val histDict = BinaryDictionary(
                    dictFilePath = historyFile.absolutePath,
                    dictOffset = 0L,
                    dictSize = historyFile.length(),
                    isUpdatable = true,
                    locale = locale
                )
                if (histDict.isValid) {
                    userHistoryDictionary = histDict
                    dictionaries["history_${lang}.dict"] = histDict
                    LogKeeper.logEvent(LogTags.DICT, "Loaded user history dictionary: ${historyFile.name}")
                }
            }

            // Initialize dynamic user personal dictionary (user.dict / user_dicts/user_<lang>.dict)
            val userDictDir = File(context.filesDir, "user_dicts")
            if (!userDictDir.exists()) userDictDir.mkdirs()
            val userDictFile = File(userDictDir, "user_${lang}.dict")

            // Check if user.dict or user_<lang>.dict was imported into filesDir, dicts, or user_dicts
            val candidates = listOf(
                File(context.filesDir, "user.dict"),
                File(File(context.filesDir, "dicts"), "user.dict"),
                File(userDictDir, "user.dict"),
                File(context.filesDir, "user_${lang}.dict")
            )
            val genericUserDict = candidates.firstOrNull { it.exists() && it.length() > 0L }

            if (!userDictFile.exists() || userDictFile.length() == 0L) {
                if (genericUserDict != null && genericUserDict.absolutePath != userDictFile.absolutePath) {
                    try {
                        genericUserDict.copyTo(userDictFile, overwrite = true)
                    } catch (_: Exception) {}
                } else {
                    com.android.inputmethod.latin.utils.BinaryDictionaryUtils.createEmptyDictFile(
                        filePath = userDictFile.absolutePath,
                        dictVersion = 403L,
                        locale = locale.toString()
                    )
                }
            }

            if (userDictFile.exists() && userDictFile.length() > 0L) {
                val uDict = BinaryDictionary(
                    dictFilePath = userDictFile.absolutePath,
                    dictOffset = 0L,
                    dictSize = userDictFile.length(),
                    isUpdatable = true,
                    locale = locale
                )
                if (uDict.isValid) {
                    userDictionary = uDict
                    dictionaries["user_${lang}.dict"] = uDict
                    LogKeeper.logEvent(LogTags.DICT, "Loaded personal user dictionary: ${userDictFile.name} (size: ${userDictFile.length()} bytes)")

                    // Ingest any imported text wordlists (e.g. from HeliBoard text backups)
                    val importedWordsFile = File(userDictDir, "imported_words.txt")
                    if (importedWordsFile.exists() && importedWordsFile.length() > 0L) {
                        try {
                            val nowTs = (System.currentTimeMillis() / 1000L).toInt()
                            var wordsCount = 0
                            importedWordsFile.forEachLine { line ->
                                val trimmed = line.trim()
                                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                                    val parts = trimmed.split("\t", ",", " ")
                                    val word = parts[0].trim()
                                    val freq = if (parts.size > 1) parts[1].trim().toIntOrNull() ?: 250 else 250
                                    if (word.isNotEmpty() && word.length <= 48) {
                                        uDict.addUnigramEntry(word, freq, nowTs)
                                        wordsCount++
                                    }
                                }
                            }
                            uDict.flush()
                            importedWordsFile.delete()
                            LogKeeper.logEvent(LogTags.DICT, "Imported $wordsCount words from HeliBoard text backup into user dictionary")
                        } catch (t: Throwable) {
                            LogKeeper.logWarning(LogTags.DICT, "Failed to ingest imported text words: ${t.message}")
                        }
                    }
                }
            }

            // Mount any additional custom/imported .dict binary dictionaries in filesDir/dicts/
            val dictDir = File(context.filesDir, "dicts")
            if (dictDir.exists() && dictDir.isDirectory) {
                val customDicts = dictDir.listFiles { f ->
                    f.isFile && f.extension == "dict" && f.name != dictFileName && f.name != "user.dict"
                } ?: emptyArray()
                for (customFile in customDicts) {
                    if (!dictionaries.containsKey(customFile.name) && customFile.length() > 0L) {
                        try {
                            val customDict = BinaryDictionary(
                                dictFilePath = customFile.absolutePath,
                                dictOffset = 0L,
                                dictSize = customFile.length(),
                                isUpdatable = false,
                                locale = locale
                            )
                            if (customDict.isValid) {
                                dictionaries[customFile.name] = customDict
                                LogKeeper.logEvent(LogTags.DICT, "Loaded custom/imported dictionary: ${customFile.name}")
                            }
                        } catch (e: Exception) {
                            LogKeeper.logWarning(LogTags.DICT, "Failed to load custom dictionary ${customFile.name}: ${e.message}")
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            LogKeeper.logError(
                LogTags.DICT,
                "DICT_FACILITATOR_ERR",
                "Failed to reset dictionaries for $locale: ${t.message}"
            )
        }
    }

    override fun closeDictionaries() {
        try {
            userHistoryDictionary?.flushWithGC()
        } catch (_: Throwable) {}
        userHistoryDictionary = null

        try {
            userDictionary?.flushWithGC()
        } catch (_: Throwable) {}
        userDictionary = null

        for ((name, dict) in dictionaries) {
            try {
                dict.close()
            } catch (t: Throwable) {
                LogKeeper.logError(LogTags.DICT, "DICT_CLOSE_ERR", "Error closing $name: ${t.message}")
            }
        }
        dictionaries.clear()
    }

    override fun onFinishInput() {
        try {
            userHistoryDictionary?.flush()
        } catch (_: Throwable) {}
        try {
            userDictionary?.flush()
        } catch (_: Throwable) {}
    }

    override fun addToUserHistory(
        word: String,
        isBeginningOfSentence: Boolean,
        ngramContext: NgramContext,
        time: Long,
        blockUntilFinished: Boolean
    ) {
        val hist = userHistoryDictionary ?: return
        if (word.isBlank() || word.length > 48) return

        try {
            val timestamp = (time / 1000L).toInt()
            // Boost frequency for user typed vocabulary
            hist.addUnigramEntry(word, 200, timestamp)

            if (ngramContext.prevWordsCount > 0) {
                hist.addNgramEntry(ngramContext, word, 150, timestamp)
            }
            LogKeeper.logEvent(LogTags.DICT, "Learned to user history: '$word' (prevCount: ${ngramContext.prevWordsCount})")
        } catch (t: Throwable) {
            LogKeeper.logError(LogTags.DICT, "USER_HIST_LEARN_ERR", "${t.message}")
        }
    }

    override fun unlearnFromUserHistory(word: String, ngramContext: NgramContext?) {
        val hist = userHistoryDictionary ?: return
        if (word.isBlank()) return
        val clean = word.trim().lowercase(activeLocale)
        demotedWords.remove(clean)
        persistDemotedWords()
        try {
            if (ngramContext != null && ngramContext.prevWordsCount > 0) {
                hist.removeNgramEntry(ngramContext, word)
                if (clean != word) {
                    hist.removeNgramEntry(ngramContext, clean)
                }
            }
            hist.removeUnigramEntry(word)
            if (clean != word) {
                hist.removeUnigramEntry(clean)
            }
            hist.flush()

            try {
                userDictionary?.removeUnigramEntry(word)
                if (clean != word) {
                    userDictionary?.removeUnigramEntry(clean)
                }
                userDictionary?.flush()
            } catch (_: Throwable) {}

            LogKeeper.logEvent(LogTags.DICT, "Unlearned from user history & personal dict: '$word'")
        } catch (t: Throwable) {
            LogKeeper.logError(LogTags.DICT, "USER_HIST_UNLEARN_ERR", "${t.message}")
        }
    }

    override fun demoteWord(word: String, ngramContext: NgramContext?) {
        val hist = userHistoryDictionary ?: return
        if (word.isBlank()) return
        val clean = word.trim().lowercase(activeLocale)
        demotedWords.add(clean)
        persistDemotedWords()
        try {
            val ts = (System.currentTimeMillis() / 1000L).toInt()
            // Demote probability to minimal positive value (e.g. 1) rather than blacklisting
            hist.addUnigramEntry(word, 1, ts)
            if (clean != word) {
                hist.addUnigramEntry(clean, 1, ts)
            }
            if (ngramContext != null && ngramContext.prevWordsCount > 0) {
                hist.addNgramEntry(ngramContext, word, 1, ts)
            }
            hist.flush()
            LogKeeper.logEvent(LogTags.DICT, "Demoted word importance in user history: '$word'")
        } catch (t: Throwable) {
            LogKeeper.logError(LogTags.DICT, "USER_HIST_DEMOTE_ERR", "${t.message}")
        }
    }

    override fun isBuiltInWord(word: String): Boolean {
        if (word.isBlank()) return false
        val clean = word.trim()
        val lower = clean.lowercase(activeLocale)
        for ((_, dict) in dictionaries) {
            if (!dict.isUpdatable && dict.isValid) {
                if (dict.isValidWord(clean) || dict.isValidWord(lower)) {
                    return true
                }
            }
        }
        return false
    }

    override fun isUserHistoryWord(word: String): Boolean {
        if (word.isBlank()) return false
        val hist = userHistoryDictionary ?: return false
        if (!hist.isValid) return false
        val clean = word.trim()
        val lower = clean.lowercase(activeLocale)
        return hist.isValidWord(clean) || hist.isValidWord(lower)
    }

    override fun isPersonalDictionaryWord(word: String): Boolean {
        if (word.isBlank()) return false
        val uDict = userDictionary ?: return false
        if (!uDict.isValid) return false
        val clean = word.trim()
        val lower = clean.lowercase(activeLocale)
        return uDict.isValidWord(clean) || uDict.isValidWord(lower)
    }

    override fun isWordDemoted(word: String): Boolean {
        if (word.isBlank()) return false
        val clean = word.trim().lowercase(activeLocale)
        return demotedWords.contains(clean)
    }

    override fun addToUserDictionary(word: String, frequency: Int): Boolean {
        if (word.isBlank()) return false
        val uDict = userDictionary ?: return false
        val clean = word.trim()
        val timestamp = (System.currentTimeMillis() / 1000L).toInt()
        val success = uDict.addUnigramEntry(clean, frequency, timestamp)
        if (success) {
            try { uDict.flush() } catch (_: Throwable) {}
            LogKeeper.logEvent(LogTags.DICT, "Added word to personal dictionary: '$clean' (freq: $frequency)")
        }
        return success
    }

    private fun loadDemotedWords(context: Context, lang: String) {
        demotedWords.clear()
        try {
            val sp = context.getSharedPreferences("vian_demoted_words_$lang", Context.MODE_PRIVATE)
            val saved = sp.getStringSet("demoted_set", null)
            if (saved != null) {
                demotedWords.addAll(saved)
            }
        } catch (t: Throwable) {
            LogKeeper.logWarning(LogTags.DICT, "Could not load demoted words for $lang: ${t.message}")
        }
    }

    private fun persistDemotedWords() {
        val ctx = appContext ?: return
        val lang = activeLocale.language
        try {
            val sp = ctx.getSharedPreferences("vian_demoted_words_$lang", Context.MODE_PRIVATE)
            val copy = synchronized(demotedWords) { HashSet(demotedWords) }
            sp.edit().putStringSet("demoted_set", copy).apply()
        } catch (t: Throwable) {
            LogKeeper.logWarning(LogTags.DICT, "Could not persist demoted words for $lang: ${t.message}")
        }
    }

    fun isValidWord(word: String): Boolean {
        for (dict in dictionaries.values) {
            if (dict.isValidWord(word)) return true
        }
        return false
    }

    fun getFrequency(word: String): Int {
        var maxFreq = -1
        for (dict in dictionaries.values) {
            val freq = dict.getFrequency(word)
            if (freq > maxFreq) maxFreq = freq
        }
        return maxFreq
    }

    fun getDictionaries(): Collection<BinaryDictionary> = dictionaries.values

    private fun extractDictAssetToDisk(context: Context, dictFileName: String): File? {
        val dictDir = File(context.filesDir, "dicts")
        if (!dictDir.exists()) dictDir.mkdirs()
        val targetFile = File(dictDir, dictFileName)

        // Protect existing files: If file already exists on disk (from user import or prior extraction),
        // NEVER overwrite it with the bundled APK asset!
        if (targetFile.exists() && targetFile.length() > 0L) {
            return targetFile
        }

        val assetPath = "dicts/$dictFileName"
        try {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(8192)
                    var read = input.read(buffer)
                    while (read != -1) {
                        output.write(buffer, 0, read)
                        read = input.read(buffer)
                    }
                    output.flush()
                }
            }
            LogKeeper.logEvent(LogTags.DICT, "Extracted asset $assetPath to ${targetFile.absolutePath}")
            return targetFile
        } catch (e: Exception) {
            LogKeeper.logWarning(LogTags.DICT, "Asset $assetPath not available directly: ${e.message}")
            return if (targetFile.exists()) targetFile else null
        }
    }
}
