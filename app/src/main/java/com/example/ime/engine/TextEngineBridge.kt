package com.example.ime.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodSubtype
import com.example.ime.keyboard.KeyData
import com.example.ime.keyboard.KeyType
import com.example.logger.LogKeeper
import helium314.keyboard.event.Event
import helium314.keyboard.keyboard.Key
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.keyboard.KeyboardElement
import helium314.keyboard.keyboard.KeyboardId
import helium314.keyboard.keyboard.KeyboardMode
import helium314.keyboard.keyboard.internal.KeyboardParams
import helium314.keyboard.latin.DictionaryFacilitator
import helium314.keyboard.latin.DictionaryFacilitatorImpl
import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.R
import helium314.keyboard.latin.RichInputMethodSubtype
import helium314.keyboard.latin.Suggest
import helium314.keyboard.latin.SuggestedWords
import helium314.keyboard.latin.WordComposer
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.CoordinateUtils
import helium314.keyboard.latin.settings.SettingsValuesForSuggestion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * TextEngineBridge coordinates bilingual orchestration (English, French, Dual mode),
 * native JNI proximity calculation, composing span lifecycle, and suggestion pipelines.
 */
class TextEngineBridge(private val context: Context) {

    enum class LanguageMode(val displayName: String, val indicator: String) {
        ENGLISH("English", "EN"),
        FRENCH("Français", "FR"),
        DUAL("Dual (EN + FR)", "EN • FR")
    }

    var currentMode: LanguageMode = LanguageMode.ENGLISH
        private set

    // Dormancy & On-Demand Lifecycle management:
    // French is dormant and unloaded until explicitly needed or triggered (diacritics or French suggestion selection).
    // Automatically returns to sleep after consecutive non-French words or on keyboard hide.
    private var isFrenchDormant: Boolean = true
    private var modeBeforeDormancy: LanguageMode = LanguageMode.ENGLISH
    private var nonFrenchWordStreak: Int = 0
    private var isFrenchLoading: Boolean = false

    val wordComposer: WordComposer = WordComposer()

    private var enFacilitator: DictionaryFacilitatorImpl? = null
    private var frFacilitator: DictionaryFacilitatorImpl? = null

    private var enSuggest: Suggest? = null
    private var frSuggest: Suggest? = null

    private var activeKeyboard: Keyboard? = null
    private val sequenceNumber = AtomicInteger(0)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private val settingsValues = SettingsValuesForSuggestion(false, false)
    private var lastCommittedWord: String? = null
    private var currentSuggestedWords: SuggestedWords? = null
    private val frenchWordsInLastQuery = mutableSetOf<String>()

    // Callback to update UI suggestions bar
    var onSuggestionsUpdated: ((List<String>, SuggestedWords?) -> Unit)? = null

    // French accented characters that instantly awaken French from dormancy
    private val frenchDiacritics = setOf(
        'é', 'è', 'ê', 'ë', 'à', 'â', 'ù', 'û', 'ô', 'ç', 'î', 'ï', 'œ', 'æ',
        'É', 'È', 'Ê', 'Ë', 'À', 'Â', 'Ù', 'Û', 'Ô', 'Ç', 'Î', 'Ï', 'Œ', 'Æ'
    )

    init {
        initializeDictionaries()
    }

    private fun initializeDictionaries() {
        // Eagerly load ONLY primary English dictionary. French is loaded on demand.
        scope.launch(Dispatchers.IO) {
            try {
                val en = DictionaryFacilitatorImpl()
                en.resetDictionaries(
                    context,
                    Locale.US,
                    false,
                    false,
                    true,
                    false,
                    "",
                    null
                )
                enFacilitator = en
                enSuggest = Suggest(en)
                LogKeeper.logEvent("TextEngineBridge", "EN dictionary loaded successfully")
            } catch (e: Throwable) {
                LogKeeper.logError("TextEngineBridge", "DICT_INIT_FAIL", "${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /**
     * Lazily loads French dictionary on-demand in background IO when awakened.
     */
    private fun ensureFrenchLoaded(onLoaded: (() -> Unit)? = null) {
        if (frFacilitator != null && frSuggest != null) {
            onLoaded?.invoke()
            return
        }
        if (isFrenchLoading) return
        isFrenchLoading = true

        scope.launch(Dispatchers.IO) {
            try {
                LogKeeper.logEvent("TextEngineBridge", "Loading French dictionary on demand...")
                val fr = DictionaryFacilitatorImpl()
                fr.resetDictionaries(
                    context,
                    Locale.FRENCH,
                    false,
                    false,
                    true,
                    false,
                    "",
                    null
                )
                frFacilitator = fr
                frSuggest = Suggest(fr)
                isFrenchLoading = false
                LogKeeper.logEvent("TextEngineBridge", "FR dictionary loaded on demand successfully")
                withContext(Dispatchers.Main) {
                    onLoaded?.invoke()
                    if (wordComposer.isComposingWord) {
                        querySuggestionsAsync()
                    }
                }
            } catch (e: Throwable) {
                isFrenchLoading = false
                LogKeeper.logError("TextEngineBridge", "FR_ON_DEMAND_LOAD_FAIL", "${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /**
     * Awakens French mode dynamically (e.g. from diacritic, French word selection, or explicit mode change).
     */
    fun awakenFrench(targetMode: LanguageMode = LanguageMode.DUAL) {
        isFrenchDormant = false
        nonFrenchWordStreak = 0
        currentMode = targetMode
        ensureFrenchLoaded()
        LogKeeper.logEvent("TextEngineBridge", "French awakened (active mode: ${currentMode.displayName})")
    }

    /**
     * Puts French into dormant sleep to save CPU and RAM.
     */
    fun sleepFrench() {
        if (!isFrenchDormant) {
            isFrenchDormant = true
            nonFrenchWordStreak = 0
            if (currentMode != LanguageMode.ENGLISH) {
                modeBeforeDormancy = currentMode
                currentMode = LanguageMode.ENGLISH
            }
            LogKeeper.logEvent("TextEngineBridge", "French put to sleep (dormant)")
        }
    }

    /**
     * Fully unloads French dictionary from memory if requested (zero RAM footprint).
     */
    fun unloadFrench() {
        sleepFrench()
        scope.launch(Dispatchers.IO) {
            try {
                frFacilitator?.closeDictionaries()
                frFacilitator = null
                frSuggest = null
                LogKeeper.logEvent("TextEngineBridge", "FR dictionary unloaded from RAM")
            } catch (e: Throwable) {
                LogKeeper.logError("TextEngineBridge", "FR_UNLOAD_FAIL", e.message ?: "")
            }
        }
    }

    /**
     * Updates the lightweight keyboard model used for ProximityInfo native matrix calculations.
     */
    fun updateKeyboardModel(keys: List<KeyData>, width: Int, height: Int) {
        if (width <= 0 || height <= 0 || keys.isEmpty()) return
        try {
            val locale = when (currentMode) {
                LanguageMode.ENGLISH -> Locale.US
                LanguageMode.FRENCH -> Locale.FRENCH
                LanguageMode.DUAL -> Locale.US
            }

            val extra = "KeyboardLayoutSet=qwerty,AsciiCapable,EnabledWhenDefaultIsNotAsciiCapable,EmojiCapable"
            val subtype = InputMethodSubtype.InputMethodSubtypeBuilder()
                .setSubtypeNameResId(R.string.subtype_generic)
                .setSubtypeIconResId(R.drawable.ic_ime_switcher)
                .setSubtypeLocale(locale.toString())
                .setSubtypeMode(Constants.Subtype.KEYBOARD_MODE)
                .setSubtypeExtraValue(extra)
                .setIsAsciiCapable(true)
                .build()
            val richSubtype = RichInputMethodSubtype.get(subtype)

            val kbId = KeyboardId(
                element = KeyboardElement.ALPHABET,
                subtype = richSubtype,
                width = width,
                height = height,
                mode = KeyboardMode.TEXT,
                inputType = InputType.TYPE_CLASS_TEXT,
                imeOptions = 0,
                imeAction = 0,
                deviceLocked = false,
                numberRowEnabled = false,
                numberRowInSymbols = false,
                languageSwitchKeyEnabled = false,
                emojiKeyEnabled = false,
                customActionLabel = null,
                hasShortcutKey = false,
                isSplitLayout = false,
                oneHandedModeEnabled = false,
                internalAction = null,
                emojiSearchAvailable = false
            )

            val params = KeyboardParams()
            params.mId = kbId
            params.mOccupiedWidth = width
            params.mOccupiedHeight = height
            params.mBaseWidth = width
            params.mBaseHeight = height
            params.mProximityCharsCorrectionEnabled = true

            for (kd in keys) {
                val code = when (kd.type) {
                    KeyType.SPACE -> Constants.CODE_SPACE
                    KeyType.DELETE -> KeyCode.DELETE
                    KeyType.SHIFT -> KeyCode.SHIFT
                    KeyType.ENTER -> Constants.CODE_ENTER
                    else -> kd.label.firstOrNull()?.code ?: kd.code
                }
                val k = Key(
                    kd.label,
                    null,
                    code,
                    null,
                    kd.hintLabel,
                    0,
                    0,
                    kd.bounds.left.toInt(),
                    kd.bounds.top.toInt(),
                    kd.bounds.width().toInt().coerceAtLeast(1),
                    kd.bounds.height().toInt().coerceAtLeast(1),
                    0,
                    0
                )
                params.onAddKey(k)
            }

            activeKeyboard = Keyboard(params)
        } catch (e: Throwable) {
            LogKeeper.logError("TextEngineBridge", "KB_MODEL_UPDATE_FAIL", "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Toggles or sets the active language mode.
     */
    fun setLanguageMode(mode: LanguageMode) {
        currentMode = mode
        if (mode == LanguageMode.FRENCH || mode == LanguageMode.DUAL) {
            awakenFrench(mode)
        } else {
            sleepFrench()
        }
        wordComposer.reset()
        clearSuggestions()
        LogKeeper.logEvent("TextEngineBridge", "Language switched to ${mode.displayName}")
    }

    /**
     * Checks if input type indicates password or sensitive field.
     */
    fun isSensitiveInput(info: EditorInfo?): Boolean {
        if (info == null) return false
        val inputType = info.inputType
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val isPassword = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        val isNumberPassword = (inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER &&
                (variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        return isPassword || isNumberPassword
    }

    /**
     * Called on IME start input. Re-initializes state and applies dormancy rules.
     */
    fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        wordComposer.reset()
        clearSuggestions()
        lastCommittedWord = null

        // French Dormancy Rule: Keep French asleep on fresh keyboard open to conserve battery/RAM
        if (!restarting) {
            if (currentMode != LanguageMode.ENGLISH) {
                modeBeforeDormancy = currentMode
                sleepFrench()
                LogKeeper.logEvent("TextEngineBridge", "French dormant; defaulted to English on start")
            }
        }
    }

    /**
     * Called on IME finish input. Persists updates and resets state.
     */
    fun onFinishInput(ic: InputConnection?) {
        if (wordComposer.isComposingWord) {
            ic?.finishComposingText()
        }
        wordComposer.reset()
        clearSuggestions()

        // Put French to sleep on close
        sleepFrench()

        try {
            enFacilitator?.onFinishInput()
            frFacilitator?.onFinishInput()
        } catch (e: Throwable) {
            LogKeeper.logError("TextEngineBridge", "ON_FINISH_FAIL", e.message ?: "")
        }
    }

    /**
     * Handles typing a character.
     */
    fun handleCharacter(char: String, touchX: Int, touchY: Int, ic: InputConnection?, info: EditorInfo?) {
        if (ic == null) return

        if (isSensitiveInput(info)) {
            wordComposer.reset()
            clearSuggestions()
            ic.commitText(char, 1)
            return
        }

        // Auto-Wake Trigger: Typing a French diacritic awakens French in Dual mode on-demand
        val firstCh = char.firstOrNull() ?: ' '
        if (isFrenchDormant && frenchDiacritics.contains(firstCh)) {
            awakenFrench(LanguageMode.DUAL)
            LogKeeper.logEvent("TextEngineBridge", "French auto-awakened by diacritic: $char")
        }

        val codePoint = char.codePointAt(0)
        val event = Event.createSoftwareKeypressEvent(codePoint, 0, touchX, touchY, false)
        val processed = wordComposer.processEvent(event)
        wordComposer.applyProcessedEvent(processed)

        val composing = wordComposer.typedWord
        ic.setComposingText(composing, 1)

        querySuggestionsAsync()
    }

    /**
     * Handles backspace / delete key action.
     */
    fun handleDelete(ic: InputConnection?, info: EditorInfo?) {
        if (ic == null) return

        if (isSensitiveInput(info)) {
            val selected = ic.getSelectedText(0)
            if (selected.isNullOrEmpty()) {
                ic.deleteSurroundingText(1, 0)
            } else {
                ic.commitText("", 1)
            }
            return
        }

        if (wordComposer.isComposingWord) {
            val event = Event.createSoftwareKeypressEvent(KeyCode.DELETE, 0, 0, 0, false)
            val processed = wordComposer.processEvent(event)
            wordComposer.applyProcessedEvent(processed)

            if (wordComposer.isComposingWord) {
                ic.setComposingText(wordComposer.typedWord, 1)
                querySuggestionsAsync()
            } else {
                ic.commitText("", 1)
                clearSuggestions()
            }
        } else {
            // Word resumption check: if cursor is right after a word, resume composing
            val textBefore = ic.getTextBeforeCursor(40, 0)?.toString() ?: ""
            if (textBefore.isNotEmpty() && textBefore.last().isLetter()) {
                val lastWord = textBefore.takeLastWhile { it.isLetter() }
                if (lastWord.length in 2..32) {
                    ic.deleteSurroundingText(lastWord.length, 0)
                    val codePoints = lastWord.map { it.code }.toIntArray()
                    val coordinates = CoordinateUtils.newCoordinateArray(
                        codePoints.size,
                        Constants.NOT_A_COORDINATE,
                        Constants.NOT_A_COORDINATE
                    )
                    wordComposer.setComposingWord(codePoints, coordinates)
                    // Delete the final character that backspace targeted
                    val delEv = Event.createSoftwareKeypressEvent(KeyCode.DELETE, 0, 0, 0, false)
                    wordComposer.applyProcessedEvent(wordComposer.processEvent(delEv))

                    if (wordComposer.isComposingWord) {
                        ic.setComposingText(wordComposer.typedWord, 1)
                        querySuggestionsAsync()
                    } else {
                        ic.commitText("", 1)
                        clearSuggestions()
                    }
                    return
                }
            }

            // Normal delete fallback
            val selected = ic.getSelectedText(0)
            if (selected.isNullOrEmpty()) {
                ic.deleteSurroundingText(1, 0)
            } else {
                ic.commitText("", 1)
            }
            clearSuggestions()
        }
    }

    /**
     * Handles space key action with auto-correction commit and next-word prediction.
     */
    fun handleSpace(ic: InputConnection?, info: EditorInfo?) {
        if (ic == null) return

        if (isSensitiveInput(info)) {
            wordComposer.reset()
            clearSuggestions()
            ic.commitText(" ", 1)
            return
        }

        if (wordComposer.isComposingWord) {
            val typed = wordComposer.typedWord
            val sw = currentSuggestedWords
            val wordToCommit = if (sw != null && sw.mWillAutoCorrect && sw.size() > SuggestedWords.INDEX_OF_AUTO_CORRECTION) {
                sw.getWord(SuggestedWords.INDEX_OF_AUTO_CORRECTION)
            } else {
                typed
            }

            ic.beginBatchEdit()
            try {
                ic.commitText("$wordToCommit ", 1)
            } finally {
                ic.endBatchEdit()
            }

            recordWordInHistory(wordToCommit)
            lastCommittedWord = wordToCommit
            wordComposer.reset()

            // Trigger Next-Word Prediction
            queryNextWordPredictions(wordToCommit)
        } else {
            ic.commitText(" ", 1)
            val textBefore = ic.getTextBeforeCursor(30, 0)?.toString()?.trim() ?: ""
            val lastWord = textBefore.takeLastWhile { it.isLetter() }
            if (lastWord.isNotEmpty()) {
                queryNextWordPredictions(lastWord)
            } else {
                clearSuggestions()
            }
        }
    }

    /**
     * Handles selection of a suggestion candidate from the suggestion strip.
     */
    fun selectSuggestion(candidate: String, slotIndex: Int, ic: InputConnection?) {
        if (ic == null) return

        ic.beginBatchEdit()
        try {
            // Replaces any existing composing span atomically
            ic.commitText("$candidate ", 1)
        } finally {
            ic.endBatchEdit()
        }

        // Check if selected word is a French word or contains diacritics to awaken French
        val isFrenchCandidate = frenchWordsInLastQuery.contains(candidate) || candidate.any { frenchDiacritics.contains(it) }
        if (isFrenchCandidate) {
            awakenFrench(LanguageMode.DUAL)
            nonFrenchWordStreak = 0
            LogKeeper.logEvent("TextEngineBridge", "French awakened by French suggestion selection: $candidate")
        } else if (!isFrenchDormant) {
            nonFrenchWordStreak++
            if (nonFrenchWordStreak >= 6) {
                sleepFrench()
                LogKeeper.logEvent("TextEngineBridge", "French put to sleep after $nonFrenchWordStreak consecutive English words")
            }
        }

        recordWordInHistory(candidate)
        lastCommittedWord = candidate
        wordComposer.reset()

        // Trigger Next-Word Prediction
        queryNextWordPredictions(candidate)
    }

    private fun recordWordInHistory(word: String) {
        if (word.isBlank()) return
        scope.launch(Dispatchers.IO) {
            try {
                val ngram = if (lastCommittedWord != null) {
                    NgramContext(NgramContext.WordInfo(lastCommittedWord))
                } else {
                    NgramContext.EMPTY_PREV_WORDS_INFO
                }
                val ts = System.currentTimeMillis() / 1000L

                if (currentMode == LanguageMode.ENGLISH || currentMode == LanguageMode.DUAL) {
                    enFacilitator?.addToUserHistory(word, false, ngram, ts, false)
                }
                if (currentMode == LanguageMode.FRENCH || currentMode == LanguageMode.DUAL) {
                    frFacilitator?.addToUserHistory(word, false, ngram, ts, false)
                }
            } catch (e: Throwable) {
                LogKeeper.logError("TextEngineBridge", "HISTORY_RECORD_FAIL", e.message ?: "")
            }
        }
    }

    private fun querySuggestionsAsync() {
        val seq = sequenceNumber.incrementAndGet()
        val kb = activeKeyboard ?: return
        val composerSnapshot = wordComposer

        scope.launch(Dispatchers.Default) {
            try {
                val ngram = if (lastCommittedWord != null) {
                    NgramContext(NgramContext.WordInfo(lastCommittedWord))
                } else {
                    NgramContext.EMPTY_PREV_WORDS_INFO
                }

                val results = mutableListOf<String>()
                var swResult: SuggestedWords? = null

                when (currentMode) {
                    LanguageMode.ENGLISH -> {
                        val en = enSuggest
                        if (en != null) {
                            val sw = en.getSuggestedWords(
                                composerSnapshot,
                                ngram,
                                kb,
                                settingsValues,
                                true,
                                SuggestedWords.INPUT_STYLE_TYPING,
                                seq
                            )
                            swResult = sw
                            populateCandidates(sw, composerSnapshot.typedWord, results)
                        }
                    }
                    LanguageMode.FRENCH -> {
                        val fr = frSuggest
                        if (fr != null) {
                            val sw = fr.getSuggestedWords(
                                composerSnapshot,
                                ngram,
                                kb,
                                settingsValues,
                                true,
                                SuggestedWords.INPUT_STYLE_TYPING,
                                seq
                            )
                            swResult = sw
                            populateCandidates(sw, composerSnapshot.typedWord, results)
                        }
                    }
                    LanguageMode.DUAL -> {
                        val en = enSuggest
                        val fr = frSuggest
                        val enWords = en?.getSuggestedWords(
                            composerSnapshot,
                            ngram,
                            kb,
                            settingsValues,
                            true,
                            SuggestedWords.INPUT_STYLE_TYPING,
                            seq
                        )
                        val frWords = fr?.getSuggestedWords(
                            composerSnapshot,
                            ngram,
                            kb,
                            settingsValues,
                            true,
                            SuggestedWords.INPUT_STYLE_TYPING,
                            seq
                        )
                        swResult = enWords ?: frWords
                        mergeDualCandidates(enWords, frWords, composerSnapshot.typedWord, results)
                    }
                }

                if (seq == sequenceNumber.get()) {
                    currentSuggestedWords = swResult
                    withContext(Dispatchers.Main) {
                        onSuggestionsUpdated?.invoke(results, swResult)
                    }
                }
            } catch (e: Throwable) {
                LogKeeper.logError("TextEngineBridge", "SUGGESTION_QUERY_FAIL", e.message ?: "")
            }
        }
    }

    private fun queryNextWordPredictions(prevWord: String) {
        val seq = sequenceNumber.incrementAndGet()
        val kb = activeKeyboard ?: return

        scope.launch(Dispatchers.Default) {
            try {
                val ngram = NgramContext(NgramContext.WordInfo(prevWord))
                val emptyComposer = WordComposer()
                val results = mutableListOf<String>()

                val suggestEngine = when (currentMode) {
                    LanguageMode.FRENCH -> frSuggest
                    else -> enSuggest
                }

                if (suggestEngine != null) {
                    val sw = suggestEngine.getSuggestedWords(
                        emptyComposer,
                        ngram,
                        kb,
                        settingsValues,
                        false,
                        SuggestedWords.INPUT_STYLE_PREDICTION,
                        seq
                    )
                    if (sw != null && !sw.isEmpty) {
                        val count = sw.size().coerceAtMost(3)
                        for (i in 0 until count) {
                            results.add(sw.getWord(i))
                        }
                    }
                }

                if (seq == sequenceNumber.get()) {
                    currentSuggestedWords = null
                    withContext(Dispatchers.Main) {
                        onSuggestionsUpdated?.invoke(results, null)
                    }
                }
            } catch (e: Throwable) {
                LogKeeper.logError("TextEngineBridge", "NEXT_WORD_QUERY_FAIL", e.message ?: "")
            }
        }
    }

    private fun populateCandidates(sw: SuggestedWords?, typed: String, out: MutableList<String>) {
        if (sw == null || sw.isEmpty) {
            if (typed.isNotEmpty()) out.add(typed)
            return
        }

        // Slot 0: Raw typed string / fallback
        out.add(typed)

        // Slot 1: Auto-correction candidate (highest rank)
        var autoCorrectWord: String? = null
        if (sw.size() > 1) {
            autoCorrectWord = sw.getWord(1)
        } else if (sw.size() == 1 && sw.getWord(0) != typed) {
            autoCorrectWord = sw.getWord(0)
        }

        if (autoCorrectWord != null && autoCorrectWord != typed) {
            out.add(autoCorrectWord)
        }

        // Slot 2: Alternative candidate
        for (i in 0 until sw.size()) {
            val w = sw.getWord(i)
            if (w != typed && w != autoCorrectWord && !out.contains(w)) {
                out.add(w)
                if (out.size >= 3) break
            }
        }

        // Ensure at most 3 slots
        while (out.size > 3) {
            out.removeAt(out.lastIndex)
        }
    }

    private fun mergeDualCandidates(
        en: SuggestedWords?,
        fr: SuggestedWords?,
        typed: String,
        out: MutableList<String>
    ) {
        // Slot 0: Raw typed string
        out.add(typed)

        frenchWordsInLastQuery.clear()
        val candidatePool = linkedSetOf<String>()

        // Gather candidates from both dictionaries
        if (en != null) {
            for (i in 0 until en.size().coerceAtMost(5)) {
                val w = en.getWord(i)
                if (w != typed) candidatePool.add(w)
            }
        }
        if (fr != null) {
            for (i in 0 until fr.size().coerceAtMost(5)) {
                val w = fr.getWord(i)
                if (w != typed) {
                    candidatePool.add(w)
                    frenchWordsInLastQuery.add(w)
                }
            }
        }

        for (candidate in candidatePool) {
            if (!out.contains(candidate)) {
                out.add(candidate)
                if (out.size >= 3) break
            }
        }
    }

    fun clearSuggestions() {
        currentSuggestedWords = null
        mainHandler.post {
            onSuggestionsUpdated?.invoke(emptyList(), null)
        }
    }

    fun onDestroy() {
        scope.cancel()
        enFacilitator?.closeDictionaries()
        frFacilitator?.closeDictionaries()
    }
}
