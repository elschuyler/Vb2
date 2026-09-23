package com.android.inputmethod.latin

import com.example.logger.LogKeeper
import com.example.logger.LogTags
import java.io.File
import java.util.ArrayList
import java.util.Locale

/**
 * Mature JNI bridge to libjni_latinime.so.
 * Matches exact JNI method signatures registered in com_android_inputmethod_latin_BinaryDictionary.cpp.
 * Provides graceful fallback on platforms/tests where native binaries are not loaded.
 */
class BinaryDictionary(
    val dictFilePath: String,
    val dictOffset: Long,
    val dictSize: Long,
    val isUpdatable: Boolean,
    val locale: Locale
) {
    var nativeDict: Long = 0L
        private set

    val isValid: Boolean
        get() = nativeDict != 0L

    val traverseSession: DicTraverseSession by lazy {
        DicTraverseSession(locale, dictSize)
    }

    init {
        loadNativeLibraryIfNeeded()
        if (sNativeLoaded) {
            try {
                nativeDict = openNative(dictFilePath, dictOffset, dictSize, isUpdatable)
                if (nativeDict != 0L) {
                    LogKeeper.logEvent(
                        LogTags.DICT,
                        "Opened native dictionary: $dictFilePath (size: $dictSize bytes, ptr: 0x${nativeDict.toString(16)})"
                    )
                } else {
                    LogKeeper.logWarning(
                        LogTags.DICT,
                        "openNative returned null pointer for $dictFilePath"
                    )
                }
            } catch (t: Throwable) {
                LogKeeper.logError(
                    LogTags.DICT,
                    "NATIVE_OPEN_ERR",
                    "Failed to open native dict $dictFilePath: ${t.message}"
                )
                nativeDict = 0L
            }
        } else {
            LogKeeper.logEvent(
                LogTags.DICT,
                "JNI not loaded; operating in fallback mode for $dictFilePath"
            )
        }
    }

    fun close() {
        try {
            traverseSession.close()
        } catch (_: Throwable) {}
        if (nativeDict != 0L && sNativeLoaded) {
            try {
                closeNative(nativeDict)
                LogKeeper.logEvent(LogTags.DICT, "Closed native dictionary: 0x${nativeDict.toString(16)}")
            } catch (t: Throwable) {
                LogKeeper.logError(LogTags.DICT, "NATIVE_CLOSE_ERR", "${t.message}")
            } finally {
                nativeDict = 0L
            }
        }
    }

    fun getFrequency(word: String): Int {
        if (nativeDict == 0L || !sNativeLoaded) return -1
        return try {
            val codePoints = word.codePoints().toArray()
            getProbabilityNative(nativeDict, codePoints)
        } catch (t: Throwable) {
            -1
        }
    }

    fun isValidWord(word: String): Boolean {
        return getFrequency(word) > 0
    }

    fun getFormatVersion(): Int {
        if (nativeDict == 0L || !sNativeLoaded) return 0
        return try {
            getFormatVersionNative(nativeDict)
        } catch (t: Throwable) {
            0
        }
    }

    /**
     * Queries native C++ engine for suggestion candidates.
     */
    fun getSuggestions(
        proximityInfo: Long,
        traverseSession: Long,
        xCoordinates: IntArray,
        yCoordinates: IntArray,
        times: IntArray,
        pointerIds: IntArray,
        inputCodePoints: IntArray,
        inputSize: Int,
        suggestOptions: IntArray,
        prevWordCodePointArrays: Array<IntArray>,
        isBeginningOfSentenceArray: BooleanArray,
        prevWordCount: Int
    ): List<String> {
        if (nativeDict == 0L || !sNativeLoaded || inputSize <= 0) return emptyList()

        return try {
            val outSuggestionCount = IntArray(1)
            val outCodePointsArray = IntArray(MAX_WORD_LENGTH * MAX_RESULTS)
            val outScoresArray = IntArray(MAX_RESULTS)
            val outSpaceIndicesArray = IntArray(MAX_RESULTS)
            val outTypesArray = IntArray(MAX_RESULTS)
            val outAutoCommitConfidence = IntArray(1)
            val weight = floatArrayOf(-1.0f)

            getSuggestionsNative(
                nativeDict,
                proximityInfo,
                traverseSession,
                xCoordinates,
                yCoordinates,
                times,
                pointerIds,
                inputCodePoints,
                inputSize,
                suggestOptions,
                prevWordCodePointArrays,
                isBeginningOfSentenceArray,
                prevWordCount,
                outSuggestionCount,
                outCodePointsArray,
                outScoresArray,
                outSpaceIndicesArray,
                outTypesArray,
                outAutoCommitConfidence,
                weight
            )

            val count = outSuggestionCount[0].coerceIn(0, MAX_RESULTS)
            val results = ArrayList<String>(count)
            for (i in 0 until count) {
                val start = i * MAX_WORD_LENGTH
                var end = start
                while (end < start + MAX_WORD_LENGTH && outCodePointsArray[end] != 0) {
                    end++
                }
                if (end > start) {
                    val codePoints = outCodePointsArray.copyOfRange(start, end)
                    results.add(String(codePoints, 0, codePoints.size))
                }
            }
            results
        } catch (t: Throwable) {
            LogKeeper.logError(LogTags.DICT, "NATIVE_GET_SUGGEST_ERR", "${t.message}")
            emptyList()
        }
    }

    data class ScoredCandidate(
        val word: String,
        val score: Int,
        val autoCommitConfidence: Int = 0
    )

    fun getScoredSuggestions(
        proximityInfo: Long,
        traverseSession: Long,
        xCoordinates: IntArray,
        yCoordinates: IntArray,
        times: IntArray,
        pointerIds: IntArray,
        inputCodePoints: IntArray,
        inputSize: Int,
        suggestOptions: IntArray,
        prevWordCodePointArrays: Array<IntArray>,
        isBeginningOfSentenceArray: BooleanArray,
        prevWordCount: Int
    ): List<ScoredCandidate> {
        if (nativeDict == 0L || !sNativeLoaded || (inputSize <= 0 && prevWordCount <= 0)) return emptyList()

        return try {
            val outSuggestionCount = IntArray(1)
            val outCodePointsArray = IntArray(MAX_WORD_LENGTH * MAX_RESULTS)
            val outScoresArray = IntArray(MAX_RESULTS)
            val outSpaceIndicesArray = IntArray(MAX_RESULTS)
            val outTypesArray = IntArray(MAX_RESULTS)
            val outAutoCommitConfidence = IntArray(1)
            val weight = floatArrayOf(-1.0f)

            getSuggestionsNative(
                nativeDict,
                proximityInfo,
                traverseSession,
                xCoordinates,
                yCoordinates,
                times,
                pointerIds,
                inputCodePoints,
                inputSize,
                suggestOptions,
                prevWordCodePointArrays,
                isBeginningOfSentenceArray,
                prevWordCount,
                outSuggestionCount,
                outCodePointsArray,
                outScoresArray,
                outSpaceIndicesArray,
                outTypesArray,
                outAutoCommitConfidence,
                weight
            )

            val count = outSuggestionCount[0].coerceIn(0, MAX_RESULTS)
            val results = ArrayList<ScoredCandidate>(count)
            for (i in 0 until count) {
                val start = i * MAX_WORD_LENGTH
                var end = start
                while (end < start + MAX_WORD_LENGTH && outCodePointsArray[end] != 0) {
                    end++
                }
                if (end > start) {
                    val codePoints = outCodePointsArray.copyOfRange(start, end)
                    val word = String(codePoints, 0, codePoints.size)
                    results.add(ScoredCandidate(word, outScoresArray[i], outAutoCommitConfidence[0]))
                }
            }
            results
        } catch (t: Throwable) {
            LogKeeper.logError(LogTags.DICT, "NATIVE_GET_SCORED_SUGGEST_ERR", "${t.message}")
            emptyList()
        }
    }

    fun addUnigramEntry(
        word: String,
        probability: Int,
        timestamp: Int
    ): Boolean {
        if (nativeDict == 0L || !sNativeLoaded) return false
        return try {
            val codePoints = word.codePoints().toArray()
            addUnigramEntryNative(
                nativeDict,
                codePoints,
                probability,
                IntArray(0),
                0,
                false,
                false,
                false,
                timestamp
            )
        } catch (t: Throwable) {
            LogKeeper.logError(LogTags.DICT, "NATIVE_ADD_UNIGRAM_ERR", "${t.message}")
            false
        }
    }

    fun addNgramEntry(
        ngramContext: helium314.keyboard.latin.NgramContext,
        word: String,
        probability: Int,
        timestamp: Int
    ): Boolean {
        if (nativeDict == 0L || !sNativeLoaded) return false
        return try {
            val prevWordArrays = ngramContext.extractPrevWordsCodePointArrays()
            val isBosArray = ngramContext.extractIsBeginningOfSentenceArray()
            val codePoints = word.codePoints().toArray()
            addNgramEntryNative(
                nativeDict,
                prevWordArrays,
                isBosArray,
                codePoints,
                probability,
                timestamp
            )
        } catch (t: Throwable) {
            LogKeeper.logError(LogTags.DICT, "NATIVE_ADD_NGRAM_ERR", "${t.message}")
            false
        }
    }

    fun removeUnigramEntry(word: String): Boolean {
        if (nativeDict == 0L || !sNativeLoaded) return false
        return try {
            val codePoints = word.codePoints().toArray()
            removeUnigramEntryNative(nativeDict, codePoints)
        } catch (t: Throwable) {
            LogKeeper.logError(LogTags.DICT, "NATIVE_REMOVE_UNIGRAM_ERR", "${t.message}")
            false
        }
    }

    fun removeNgramEntry(
        ngramContext: helium314.keyboard.latin.NgramContext,
        word: String
    ): Boolean {
        if (nativeDict == 0L || !sNativeLoaded) return false
        return try {
            val prevWordArrays = ngramContext.extractPrevWordsCodePointArrays()
            val isBosArray = ngramContext.extractIsBeginningOfSentenceArray()
            val codePoints = word.codePoints().toArray()
            removeNgramEntryNative(nativeDict, prevWordArrays, isBosArray, codePoints)
        } catch (t: Throwable) {
            LogKeeper.logError(LogTags.DICT, "NATIVE_REMOVE_NGRAM_ERR", "${t.message}")
            false
        }
    }

    fun flush(filePath: String = dictFilePath): Boolean {
        if (nativeDict == 0L || !sNativeLoaded) return false
        return try {
            flushNative(nativeDict, filePath)
        } catch (t: Throwable) {
            LogKeeper.logError(LogTags.DICT, "NATIVE_FLUSH_ERR", "${t.message}")
            false
        }
    }

    fun flushWithGC(filePath: String = dictFilePath): Boolean {
        if (nativeDict == 0L || !sNativeLoaded) return false
        return try {
            flushWithGCNative(nativeDict, filePath)
        } catch (t: Throwable) {
            LogKeeper.logError(LogTags.DICT, "NATIVE_FLUSH_GC_ERR", "${t.message}")
            false
        }
    }

    companion object {
        const val MAX_RESULTS = 18
        const val MAX_WORD_LENGTH = 48
        const val JNI_LIB_NAME = "jni_latinime"
        @Volatile
        var sNativeLoaded: Boolean = false
            private set

        fun loadNativeLibraryIfNeeded() {
            if (sNativeLoaded) return
            synchronized(this) {
                if (sNativeLoaded) return
                try {
                    System.loadLibrary(JNI_LIB_NAME)
                    sNativeLoaded = true
                    LogKeeper.logEvent(LogTags.JNI, "Successfully loaded native library: $JNI_LIB_NAME")
                } catch (unsatisfied: UnsatisfiedLinkError) {
                    sNativeLoaded = false
                    LogKeeper.logWarning(
                        LogTags.JNI,
                        "Native library $JNI_LIB_NAME not available (expected on host JVM tests): ${unsatisfied.message}"
                    )
                } catch (t: Throwable) {
                    sNativeLoaded = false
                    LogKeeper.logError(LogTags.JNI, "JNI_LOAD_ERR", "Unexpected load error: ${t.message}")
                }
            }
        }

        // --- Native JNI declarations matching com_android_inputmethod_latin_BinaryDictionary.cpp ---

        @JvmStatic
        private external fun openNative(
            sourceDir: String,
            dictOffset: Long,
            dictSize: Long,
            isUpdatable: Boolean
        ): Long

        @JvmStatic
        private external fun createOnMemoryNative(
            formatVersion: Long,
            locale: String,
            attributeKeyStringArray: Array<String>,
            attributeValueStringArray: Array<String>
        ): Long

        @JvmStatic
        private external fun closeNative(dict: Long)

        @JvmStatic
        private external fun getFormatVersionNative(dict: Long): Int

        @JvmStatic
        private external fun getHeaderInfoNative(
            dict: Long,
            outHeaderSize: IntArray,
            outFormatVersion: IntArray,
            outAttributeKeys: ArrayList<IntArray>,
            outAttributeValues: ArrayList<IntArray>
        )

        @JvmStatic
        private external fun flushNative(dict: Long, filePath: String): Boolean

        @JvmStatic
        private external fun needsToRunGCNative(dict: Long, mindsBlockByGC: Boolean): Boolean

        @JvmStatic
        private external fun flushWithGCNative(dict: Long, filePath: String): Boolean

        @JvmStatic
        private external fun addUnigramEntryNative(
            dict: Long,
            word: IntArray,
            probability: Int,
            shortcuts: IntArray,
            shortcutProbability: Int,
            isNotAWord: Boolean,
            isPossiblyOffensive: Boolean,
            isUserHistory: Boolean,
            timestamp: Int
        ): Boolean

        @JvmStatic
        private external fun addNgramEntryNative(
            dict: Long,
            prevWordCodePointArrays: Array<IntArray>,
            isBeginningOfSentenceArray: BooleanArray,
            word: IntArray,
            probability: Int,
            timestamp: Int
        ): Boolean

        @JvmStatic
        private external fun removeUnigramEntryNative(
            dict: Long,
            word: IntArray
        ): Boolean

        @JvmStatic
        private external fun removeNgramEntryNative(
            dict: Long,
            prevWordCodePointArrays: Array<IntArray>,
            isBeginningOfSentenceArray: BooleanArray,
            word: IntArray
        ): Boolean

        @JvmStatic
        private external fun getProbabilityNative(dict: Long, word: IntArray): Int

        @JvmStatic
        private external fun getMaxProbabilityOfExactMatchesNative(dict: Long, word: IntArray): Int

        @JvmStatic
        private external fun getNgramProbabilityNative(
            dict: Long,
            prevWordCodePointArrays: Array<IntArray>,
            isBeginningOfSentenceArray: BooleanArray,
            word: IntArray
        ): Int

        @JvmStatic
        private external fun getSuggestionsNative(
            dict: Long,
            proximityInfo: Long,
            dicTraverseSession: Long,
            xCoordinatesArray: IntArray,
            yCoordinatesArray: IntArray,
            timesArray: IntArray,
            pointerIdsArray: IntArray,
            inputCodePointsArray: IntArray,
            inputSize: Int,
            suggestOptions: IntArray,
            prevWordCodePointArrays: Array<IntArray>,
            isBeginningOfSentenceArray: BooleanArray,
            prevWordCount: Int,
            outSuggestionCount: IntArray,
            outCodePointsArray: IntArray,
            outScoresArray: IntArray,
            outSpaceIndicesArray: IntArray,
            outTypesArray: IntArray,
            outAutoCommitFirstWordConfidenceArray: IntArray,
            inOutWeightOfLangModelVsSpatialModel: FloatArray
        )
    }
}
