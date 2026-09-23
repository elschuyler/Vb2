package com.example.ime

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.ime.clipboard.ClipboardStorage
import com.example.ime.clipboard.VianClipboardModalView
import com.example.ime.emoji.VianEmojiModalView
import com.example.ime.quicknotes.QuickNotesStorage
import com.example.ime.quicknotes.VianQuickNotesModalView
import com.example.ime.voice.VianVoiceModalView
import com.example.ime.keyboard.KeyData
import com.example.ime.keyboard.KeyType
import com.example.ime.keyboard.VianKeyboardView
import com.example.ime.settings.SettingsActivity
import com.example.ime.toolbar.ToolbarPreferences
import com.example.ime.toolbar.ToolbarTool
import com.example.ime.security.MasterPatternStore
import com.example.ime.security.VaultSessionManager
import com.example.ime.security.VaultType
import com.example.ime.security.VianPatternUnlockView
import com.example.ime.engine.TextEngineBridge
import com.example.logger.LogKeeper

class VianBoardService : InputMethodService() {

    private var inputViewContainer: FrameLayout? = null
    private var keyboardView: VianKeyboardView? = null
    private var activeModalView: View? = null
    private var warmClipboardView: VianClipboardModalView? = null
    private var warmQuickNotesView: VianQuickNotesModalView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isTempIncognitoActive = false
    private val incognitoExpireRunnable = Runnable {
        isTempIncognitoActive = false
        keyboardView?.let { kv ->
            kv.layout.isIncognitoActive = false
            kv.invalidate()
        }
        Toast.makeText(this, "Incognito mode ended", Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val ACTION_RELOAD_DICTIONARIES = "com.example.ime.ACTION_RELOAD_DICTIONARIES"

        @Volatile
        var activeInstance: VianBoardService? = null
            private set
    }

    private lateinit var clipboardStorage: ClipboardStorage
    private var clipboardManager: ClipboardManager? = null
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        capturePrimaryClip()
    }
    private lateinit var textEngineBridge: TextEngineBridge

    val engineBridge: TextEngineBridge
        get() = textEngineBridge

    private val reloadDictsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            LogKeeper.logEvent("IME", "Received ACTION_RELOAD_DICTIONARIES broadcast")
            textEngineBridge.reloadDictionaries()
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        LogKeeper.logComponentStart("VianBoardService")
        clipboardStorage = ClipboardStorage(this)
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        textEngineBridge = TextEngineBridge(this)
        textEngineBridge.onSuggestionsUpdated = { suggestions, _ ->
            keyboardView?.updateSuggestions(suggestions)
        }
        textEngineBridge.onVaultUnlockRequested = { vaultEntry, ic ->
            showPatternUnlockModal(VaultType.PRIVACY) {
                textEngineBridge.commitVaultPhrase(vaultEntry, ic ?: currentInputConnection)
            }
        }

        val filter = IntentFilter(ACTION_RELOAD_DICTIONARIES)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(reloadDictsReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(reloadDictsReceiver, filter)
        }
    }

    private fun capturePrimaryClip() {
        try {
            val clip = clipboardManager?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val desc = clip.description
                val isSensitive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    desc?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true
                } else false
                if (isSensitive) {
                    LogKeeper.logEvent("IME", "Zero-learning: skipped capturing sensitive clip")
                    return
                }
                val text = clip.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
                if (!text.isNullOrEmpty()) {
                    // ZERO-LEARNING GUARANTEE: strictly prevent Privacy Vault phrases from entering clipboard storage
                    if (textEngineBridge.personalDictStorage.isVaultPhrase(text)) {
                        LogKeeper.logEvent("IME", "Zero-learning: strictly blocked Privacy Vault phrase from clipboard storage")
                        return
                    }
                    clipboardStorage.addClip(text)
                    LogKeeper.logEvent("IME", "Auto-captured clip into storage without modal")
                }
            }
        } catch (e: Exception) {
            LogKeeper.logError("IME", "CLIPBOARD_CAPTURE_FAIL", e.message ?: "Unknown error")
        }
    }

    override fun onConfigureWindow(win: android.view.Window, isFullscreen: Boolean, isCandidatesOnly: Boolean) {
        super.onConfigureWindow(win, isFullscreen, isCandidatesOnly)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            win.setDecorFitsSystemWindows(false)
        }
        win.navigationBarColor = Color.BLACK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            win.isNavigationBarContrastEnforced = false
        }
    }

    override fun onComputeInsets(outInsets: InputMethodService.Insets) {
        super.onComputeInsets(outInsets)
        outInsets.contentTopInsets = 0
        outInsets.visibleTopInsets = 0
        outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_FRAME
    }

    override fun onCreateInputView(): View {
        LogKeeper.logEvent("IME", "onCreateInputView initializing 2D Canvas engine with Option C popups and on-demand modals")
        val container = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(Color.BLACK)
        }

        ViewCompat.setOnApplyWindowInsetsListener(container) { v, insets ->
            val navInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(0, 0, 0, navInsets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        val view = VianKeyboardView(this).apply {
            onKeyAction = { key -> handleKeyAction(key) }
            onTextCommit = { text -> currentInputConnection?.commitText(text, 1) }
            onActionSelection = { handleSelectionAction() }
            onActionClipboard = { showClipboardModal() }
            onActionExpand = { toggleToolbarExpand() }

            onToolbarToolClick = { tool -> handleToolbarToolClick(tool) }
            onToolbarToolLongClick = { tool -> handleToolbarToolLongClick(tool) }
            onAnchorLongClick = { handleAnchorLongClick() }
            onCommaPopupSelected = { item -> handleCommaPopupAction(item) }
            onSymbolsLongClick = { showPatternUnlockModal(VaultType.SECURITY) }
            onSuggestionClick = { candidate, slot ->
                textEngineBridge.selectSuggestion(candidate, slot, currentInputConnection)
            }
            onSuggestionLongClick = { key, candidate, slot ->
                handleSuggestionLongClick(key, candidate, slot)
            }
            onSpaceLongClick = { spaceKey ->
                handleSpaceLongClick(spaceKey)
            }
            onLayoutUpdated = { keys, w, h ->
                textEngineBridge.updateKeyboardModel(keys, w, h)
            }
        }
        container.addView(view)
        keyboardView = view
        inputViewContainer = container
        initWarmModals(container)
        return container
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        LogKeeper.logEvent("IME", "onStartInputView (restarting=$restarting)")
        keyboardView?.reloadTheme()

        val textEnginePrefs = com.example.ime.engine.TextEnginePreferences(this)
        textEngineBridge.setLiteMode(textEnginePrefs.liteMode)
        keyboardView?.isLiteMode = textEnginePrefs.liteMode

        textEngineBridge.onStartInput(info, restarting)
        keyboardView?.updateSpaceLabel(textEngineBridge.currentMode.indicator)
        if (textEngineBridge.isSensitiveInput(info)) {
            keyboardView?.updateSuggestions(emptyList())
        }

        // Auto-register clipboard listener and capture any current clip to storage
        try {
            clipboardManager?.removePrimaryClipChangedListener(clipboardListener)
            clipboardManager?.addPrimaryClipChangedListener(clipboardListener)
            capturePrimaryClip()
        } catch (e: Exception) {
            LogKeeper.logError("IME", "CLIPBOARD_LISTENER_FAIL", e.message ?: "Unknown error")
        }

        // Check if input requests numbers (EditorInfo.TYPE_CLASS_NUMBER or TYPE_CLASS_PHONE)
        val inputType = info?.inputType ?: 0
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        if (inputClass == InputType.TYPE_CLASS_NUMBER || inputClass == InputType.TYPE_CLASS_PHONE) {
            keyboardView?.setMode(com.example.ime.keyboard.KeyboardMode.NUMPAD)
        } else {
            keyboardView?.setMode(com.example.ime.keyboard.KeyboardMode.CHARACTERS)
        }
    }

    private fun toggleToolbarExpand() {
        keyboardView?.let { kv ->
            kv.layout.isToolbarExpanded = !kv.layout.isToolbarExpanded
            val density = resources.displayMetrics.density
            kv.layout.buildLayout(kv.width.toFloat(), kv.height.toFloat(), kv.theme, density, kv.bottomNavInsetPx)
            kv.invalidate()
        }
    }

    private fun handleAnchorLongClick() {
        // Long pressing the chevron or anchor toggles temporary 3-minute incognito mode
        activateTempIncognito(180_000L)
    }

    private fun activateTempIncognito(durationMs: Long) {
        isTempIncognitoActive = true
        handler.removeCallbacks(incognitoExpireRunnable)
        handler.postDelayed(incognitoExpireRunnable, durationMs)

        keyboardView?.let { kv ->
            kv.layout.isIncognitoActive = true
            val density = resources.displayMetrics.density
            kv.layout.buildLayout(kv.width.toFloat(), kv.height.toFloat(), kv.theme, density, kv.bottomNavInsetPx)
            kv.invalidate()
        }
        Toast.makeText(this, "Incognito mode active (3 min)", Toast.LENGTH_SHORT).show()
    }

    private fun handleToolbarToolClick(tool: ToolbarTool) {
        val ic = currentInputConnection
        when (tool) {
            ToolbarTool.UNDO -> {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON)
            }
            ToolbarTool.REDO -> {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_Y, KeyEvent.META_CTRL_ON)
            }
            ToolbarTool.SELECT_WORD -> {
                // Select surrounding word on tap
                selectSurroundingWord()
            }
            ToolbarTool.SELECT_ALL -> {
                ic?.performContextMenuAction(android.R.id.selectAll)
            }
            ToolbarTool.COPY -> {
                ic?.performContextMenuAction(android.R.id.copy)
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
                handler.postDelayed({ capturePrimaryClip() }, 100)
            }
            ToolbarTool.PASTE -> {
                ic?.performContextMenuAction(android.R.id.paste)
                handler.postDelayed({ capturePrimaryClip() }, 100)
            }
            ToolbarTool.UP -> {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_UP)
            }
            ToolbarTool.DOWN -> {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_DOWN)
            }
            ToolbarTool.INCOGNITO -> {
                // Toggle incognito mode
                if (isTempIncognitoActive) {
                    handler.removeCallbacks(incognitoExpireRunnable)
                    isTempIncognitoActive = false
                    keyboardView?.let { kv ->
                        kv.layout.isIncognitoActive = false
                        val density = resources.displayMetrics.density
                        kv.layout.buildLayout(kv.width.toFloat(), kv.height.toFloat(), kv.theme, density)
                        kv.invalidate()
                    }
                    Toast.makeText(this, "Incognito mode disabled", Toast.LENGTH_SHORT).show()
                } else {
                    activateTempIncognito(180_000L)
                }
            }
            ToolbarTool.VOICE -> {
                showVoiceModal()
            }
            ToolbarTool.PROMPT_LIST -> {
                showQuickNotesModal()
            }
            ToolbarTool.SECURITY_VAULT -> {
                showPatternUnlockModal(VaultType.SECURITY)
            }
            ToolbarTool.DESKTOP_SHORTCUTS -> {
                showDesktopShortcutsModal()
            }
            ToolbarTool.SETTINGS -> {
                val intent = Intent(this, SettingsActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            }
            ToolbarTool.CLIPBOARD -> {
                showClipboardModal()
            }
            ToolbarTool.TEXT_EDIT -> {
                // Text editing pad / select all & copy
                ic?.performContextMenuAction(android.R.id.selectAll)
                Toast.makeText(this, "Text editing: All text selected", Toast.LENGTH_SHORT).show()
            }
            ToolbarTool.THEME -> {
                val intent = Intent(this, com.example.ime.settings.AppearanceSettingsActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            }
            ToolbarTool.EMOJI -> {
                showEmojiModal()
            }
            ToolbarTool.NUMBER_ROW -> {
                Toast.makeText(this, "Number row toggle", Toast.LENGTH_SHORT).show()
            }
            ToolbarTool.CLEAR_CLIPBOARD -> {
                ClipboardStorage(this).clearUnpinned()
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.clearPrimaryClip()
                Toast.makeText(this, "Clipboard cleared", Toast.LENGTH_SHORT).show()
            }
            ToolbarTool.ONE_HANDED -> {
                Toast.makeText(this, "One-handed mode: Coming soon", Toast.LENGTH_SHORT).show()
            }
            ToolbarTool.FLOATING -> {
                Toast.makeText(this, "Floating keyboard: Coming soon", Toast.LENGTH_SHORT).show()
            }
            ToolbarTool.LOG_KEEPER -> {
                val logs = LogKeeper.getLogs()
                Toast.makeText(this, "Log Keeper: ${logs.size} entries", Toast.LENGTH_SHORT).show()
            }
            ToolbarTool.PERSONAL_VAULT -> {
                showPatternUnlockModal(VaultType.PRIVACY)
            }
        }
    }

    private fun handleToolbarToolLongClick(tool: ToolbarTool) {
        val ic = currentInputConnection
        when (tool) {
            ToolbarTool.INCOGNITO -> {
                activateTempIncognito(180_000L)
            }
            ToolbarTool.SELECT_WORD -> {
                // Long press select word -> select all
                ic?.performContextMenuAction(android.R.id.selectAll)
                Toast.makeText(this, "Select All", Toast.LENGTH_SHORT).show()
            }
            ToolbarTool.COPY -> {
                // Long press copy -> Quick Notes modal
                showQuickNotesModal()
            }
            ToolbarTool.PASTE -> {
                // Long press paste -> clipboard history modal
                showClipboardModal()
            }
            else -> {
                // For other tools, repeat standard action
                handleToolbarToolClick(tool)
            }
        }
    }

    private fun sendDownUpKeyEvents(keyCode: Int, metaState: Int) {
        val ic = currentInputConnection ?: return
        val eventTime = android.os.SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0, metaState))
        ic.sendKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0, metaState))
    }

    private fun handleKeyAction(key: KeyData) {
        val ic = currentInputConnection ?: return
        when (key.type) {
            KeyType.CHARACTER -> {
                textEngineBridge.handleCharacter(
                    key.label,
                    key.bounds.centerX().toInt(),
                    key.bounds.centerY().toInt(),
                    ic,
                    currentInputEditorInfo
                )
            }
            KeyType.SPACE -> {
                textEngineBridge.handleSpace(ic, currentInputEditorInfo)
            }
            KeyType.COMMA -> {
                ic.commitText(",", 1)
            }
            KeyType.PERIOD -> {
                ic.commitText(".", 1)
            }
            KeyType.DELETE -> {
                textEngineBridge.handleDelete(ic, currentInputEditorInfo)
            }
            KeyType.ENTER -> {
                sendEnter()
            }
            else -> {
                if (key.label.isNotEmpty()) {
                    ic.commitText(key.label, 1)
                }
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        LogKeeper.logEvent("IME", "onTrimMemory level=$level")
        textEngineBridge.onTrimMemory(level)
        keyboardView?.demoteColdSurfaces()
        keyboardView?.updateSpaceLabel(textEngineBridge.currentMode.indicator)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            if (activeModalView !== warmClipboardView) {
                warmClipboardView?.visibility = View.GONE
            }
            if (activeModalView !== warmQuickNotesView) {
                warmQuickNotesView?.visibility = View.GONE
            }
        }
    }

    private fun handleSpaceLongClick(spaceKey: com.example.ime.keyboard.KeyData) {
        val kv = keyboardView ?: return
        if (textEngineBridge.currentMode != TextEngineBridge.LanguageMode.DUAL) {
            // When a single language is active, long-pressing spacebar allows instant switching back to Bilingual Mode
            textEngineBridge.setLanguageMode(TextEngineBridge.LanguageMode.DUAL)
            kv.updateSpaceLabel(TextEngineBridge.LanguageMode.DUAL.indicator)
            Toast.makeText(this, "Switched to Bilingual Mode (EN • FR)", Toast.LENGTH_SHORT).show()
        } else {
            // When in Bilingual Mode, opens the full language selector popup
            showLanguageSelectorPopup(spaceKey)
        }
    }

    private fun showLanguageSelectorPopup(spaceKey: com.example.ime.keyboard.KeyData) {
        val kv = keyboardView ?: return
        val popup = com.example.ime.popup.LanguageSelectorPopup(
            context = this,
            currentMode = textEngineBridge.currentMode,
            onModeSelected = { newMode ->
                textEngineBridge.setLanguageMode(newMode)
                kv.updateSpaceLabel(newMode.indicator)
                Toast.makeText(this, newMode.displayName, Toast.LENGTH_SHORT).show()
            }
        )
        popup.show(kv, spaceKey)
    }

    private fun handleSuggestionLongClick(key: com.example.ime.keyboard.KeyData, candidate: String, slot: Int) {
        val kv = keyboardView ?: return
        if (candidate.isBlank()) return

        val isBuiltIn = textEngineBridge.isBuiltInWord(candidate)
        val isPersonal = !isBuiltIn

        val similarCandidates = textEngineBridge.getAllAlternativeCandidates(excludeWord = candidate)

        val popup = com.example.ime.popup.SuggestionCandidatePopup(
            context = this,
            targetWord = candidate,
            isPersonalDictionary = isPersonal,
            similarWords = similarCandidates,
            onDeleteOrDemote = { isDelete ->
                if (isDelete) {
                    textEngineBridge.unlearnWord(candidate)
                    textEngineBridge.removeCandidateAndRefresh(candidate)
                    Toast.makeText(this, "Purged \"$candidate\" from personal dictionary", Toast.LENGTH_SHORT).show()
                } else {
                    textEngineBridge.demoteWord(candidate)
                    textEngineBridge.removeCandidateAndRefresh(candidate)
                    Toast.makeText(this, "Demoted \"$candidate\" (scoring penalty applied)", Toast.LENGTH_SHORT).show()
                }
            },
            onWordSelected = { selectedWord ->
                textEngineBridge.selectSuggestion(selectedWord, slot, currentInputConnection)
            }
        )
        popup.show(kv, key)
    }

    private fun sendDelete() {
        val ic = currentInputConnection ?: return
        val selectedText = ic.getSelectedText(0)
        if (selectedText.isNullOrEmpty()) {
            ic.deleteSurroundingText(1, 0)
        } else {
            ic.commitText("", 1)
        }
    }

    private fun sendEnter() {
        val ic = currentInputConnection ?: return
        val imeOptions = currentInputEditorInfo?.imeOptions ?: 0
        val actionId = imeOptions and EditorInfo.IME_MASK_ACTION
        if (actionId != EditorInfo.IME_ACTION_NONE && actionId != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(actionId)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER, 0)
        }
    }

    private fun handleSelectionAction() {
        selectSurroundingWord()
    }

    private fun selectSurroundingWord() {
        val ic = currentInputConnection ?: return

        // 1. If text is already selected, expand to select all
        val currentSelection = ic.getSelectedText(0)
        if (!currentSelection.isNullOrEmpty()) {
            ic.performContextMenuAction(android.R.id.selectAll)
            return
        }

        // 2. Query text before and after cursor (up to 120 chars each)
        val before = ic.getTextBeforeCursor(120, 0)?.toString() ?: ""
        val after = ic.getTextAfterCursor(120, 0)?.toString() ?: ""

        if (before.isEmpty() && after.isEmpty()) {
            ic.performContextMenuAction(android.R.id.selectAll)
            return
        }

        val isWordChar = { c: Char -> c.isLetterOrDigit() || c == '_' }

        var leftCount = 0
        for (i in before.length - 1 downTo 0) {
            if (isWordChar(before[i])) {
                leftCount++
            } else {
                break
            }
        }

        var rightCount = 0
        for (i in 0 until after.length) {
            if (isWordChar(after[i])) {
                rightCount++
            } else {
                break
            }
        }

        if (leftCount > 0 || rightCount > 0) {
            val req = android.view.inputmethod.ExtractedTextRequest()
            val extracted = ic.getExtractedText(req, 0)
            if (extracted != null && extracted.selectionStart >= 0) {
                val cursor = extracted.selectionStart
                val selStart = (cursor - leftCount).coerceAtLeast(0)
                val selEnd = (cursor + rightCount).coerceAtMost(extracted.text?.length ?: (cursor + rightCount))
                ic.setSelection(selStart, selEnd)
                return
            }
        }
        ic.performContextMenuAction(android.R.id.selectAll)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.isCtrlPressed) {
            if (keyCode == KeyEvent.KEYCODE_W || keyCode == KeyEvent.KEYCODE_V) {
                if (event.repeatCount == 0) {
                    event.startTracking()
                }
                if (event.repeatCount > 0) {
                    // Hardware key held down (Desktop long-press equivalent)
                    if (keyCode == KeyEvent.KEYCODE_W) {
                        currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
                        Toast.makeText(this, "Select All", Toast.LENGTH_SHORT).show()
                        return true
                    } else if (keyCode == KeyEvent.KEYCODE_V) {
                        showClipboardModal()
                        return true
                    }
                }
            }
            when (keyCode) {
                KeyEvent.KEYCODE_W -> {
                    if (event.isShiftPressed) {
                        currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
                        Toast.makeText(this, "Select All", Toast.LENGTH_SHORT).show()
                        return true
                    }
                }
                KeyEvent.KEYCODE_A -> {
                    currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
                    return true
                }
                KeyEvent.KEYCODE_V -> {
                    if (event.isShiftPressed) {
                        showClipboardModal()
                        return true
                    }
                    handler.postDelayed({ capturePrimaryClip() }, 100)
                }
                KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_X -> {
                    handler.postDelayed({ capturePrimaryClip() }, 100)
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (event.isCtrlPressed) {
            when (keyCode) {
                KeyEvent.KEYCODE_W -> {
                    currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
                    Toast.makeText(this, "Select All", Toast.LENGTH_SHORT).show()
                    return true
                }
                KeyEvent.KEYCODE_V -> {
                    showClipboardModal()
                    return true
                }
            }
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (event.isCtrlPressed && !event.isCanceled) {
            if (keyCode == KeyEvent.KEYCODE_W && event.repeatCount == 0 && !event.isShiftPressed) {
                selectSurroundingWord()
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun getModalHeight(): Int {
        val minHeightPx = (260 * resources.displayMetrics.density).toInt()
        val kbHeight = keyboardView?.height ?: 0
        return if (kbHeight > minHeightPx) kbHeight else minHeightPx
    }

    private fun initWarmModals(container: FrameLayout) {
        // Pre-warm Clipboard Modal
        val clipboard = VianClipboardModalView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            visibility = View.GONE
            onDismissToAlpha = { dismissActiveModal() }
            onCommitText = { text -> currentInputConnection?.commitText(text, 1) }
            onDelete = { sendDelete() }
            onEnter = { sendEnter() }
            onSelectAll = { currentInputConnection?.performContextMenuAction(android.R.id.selectAll) }
            onCut = { currentInputConnection?.performContextMenuAction(android.R.id.cut) }
            onCopy = { currentInputConnection?.performContextMenuAction(android.R.id.copy) }
            onPaste = { currentInputConnection?.performContextMenuAction(android.R.id.paste) }
            onNavigate = { dir ->
                when (dir) {
                    1 -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT, 0)
                    2 -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT, 0)
                    3 -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_UP, 0)
                    4 -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_DOWN, 0)
                }
            }
        }
        container.addView(clipboard)
        warmClipboardView = clipboard

        // Pre-warm Quick Notes (Prompt List) Modal
        val quickNotes = VianQuickNotesModalView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            visibility = View.GONE
            onDismissToAlpha = { dismissActiveModal() }
            onCommitText = { text -> currentInputConnection?.commitText(text, 1) }
            onDelete = { sendDelete() }
            onEnter = { sendEnter() }
            onSelectAll = { currentInputConnection?.performContextMenuAction(android.R.id.selectAll) }
            onPasteToNewNote = {
                val clipMgr = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clipText = clipMgr?.primaryClip?.getItemAt(0)?.coerceToText(this@VianBoardService)?.toString()?.trim() ?: ""
                if (clipText.isNotEmpty()) {
                    val intent = Intent(this@VianBoardService, com.example.ime.quicknotes.QuickNoteEditActivity::class.java).apply {
                        putExtra(com.example.ime.quicknotes.QuickNoteEditActivity.EXTRA_OLD_TEXT, clipText)
                        putExtra(com.example.ime.quicknotes.QuickNoteEditActivity.EXTRA_IS_NEW, true)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                } else {
                    Toast.makeText(this@VianBoardService, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                }
            }
            onNavigate = { dir ->
                when (dir) {
                    1 -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT, 0)
                    2 -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT, 0)
                    3 -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_UP, 0)
                    4 -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_DOWN, 0)
                }
            }
        }
        container.addView(quickNotes)
        warmQuickNotesView = quickNotes
        LogKeeper.logEvent("IME", "Warm modals pre-initialized (Clipboard & Prompt List)")
    }

    private fun showClipboardModal() {
        val container = inputViewContainer ?: return
        dismissActiveModal()

        val modalHeight = getModalHeight()
        val clipboardView = warmClipboardView ?: VianClipboardModalView(this).also {
            warmClipboardView = it
            container.addView(it)
        }

        clipboardView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            modalHeight
        )
        clipboardView.syncPrimaryClip()
        clipboardView.refreshData()

        keyboardView?.visibility = View.INVISIBLE
        clipboardView.visibility = View.VISIBLE
        activeModalView = clipboardView
        LogKeeper.logEvent("IME", "Warm Clipboard modal displayed instantly")
    }

    private fun showQuickNotesModal() {
        val container = inputViewContainer ?: return
        dismissActiveModal()

        val modalHeight = getModalHeight()
        val quickNotesView = warmQuickNotesView ?: VianQuickNotesModalView(this).also {
            warmQuickNotesView = it
            container.addView(it)
        }

        quickNotesView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            modalHeight
        )
        quickNotesView.refreshData()

        keyboardView?.visibility = View.INVISIBLE
        quickNotesView.visibility = View.VISIBLE
        activeModalView = quickNotesView
        LogKeeper.logEvent("IME", "Warm Quick Notes (Prompt List) modal displayed instantly")
    }

    private fun showEmojiModal() {
        val container = inputViewContainer ?: return
        dismissActiveModal()

        val emojiView = VianEmojiModalView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                getModalHeight()
            )
            onDismissToAlpha = { dismissActiveModal() }
            onEmojiCommit = { emoji -> currentInputConnection?.commitText(emoji, 1) }
            onDelete = { sendDelete() }
            onEnter = { sendEnter() }
        }

        keyboardView?.visibility = View.INVISIBLE
        container.addView(emojiView)
        activeModalView = emojiView
        LogKeeper.logEvent("IME", "Emoji modal opened on-demand")
    }

    private fun showVoiceModal() {
        val container = inputViewContainer ?: return
        dismissActiveModal()

        val voiceHeightPx = (160 * resources.displayMetrics.density).toInt()
        val voiceView = VianVoiceModalView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                voiceHeightPx
            )
            onDismissToAlpha = { dismissActiveModal() }
            onCommitText = { text -> currentInputConnection?.commitText(text, 1) }
            onDelete = { sendDelete() }
            onEnter = { sendEnter() }
        }

        keyboardView?.visibility = View.INVISIBLE
        container.addView(voiceView)
        activeModalView = voiceView
        voiceView.startVoiceInput()
        LogKeeper.logEvent("IME", "Voice modal opened on-demand (~160dp)")
    }

    private fun sendDesktopKeyEvent(keyCode: Int, metaState: Int = 0) {
        val ic = currentInputConnection
        val now = SystemClock.uptimeMillis()
        val isCtrl = (metaState and KeyEvent.META_CTRL_ON) != 0
        val isShift = (metaState and KeyEvent.META_SHIFT_ON) != 0
        val isAlt = (metaState and KeyEvent.META_ALT_ON) != 0

        // 1. Dispatch modifier down events as physical keyboard hardware source
        if (isCtrl) {
            ic?.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, 0,
                KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_FROM_SYSTEM or KeyEvent.FLAG_KEEP_TOUCH_MODE or KeyEvent.FLAG_SOFT_KEYBOARD,
                InputDevice.SOURCE_KEYBOARD))
        }
        if (isShift) {
            ic?.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 0,
                KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_FROM_SYSTEM or KeyEvent.FLAG_KEEP_TOUCH_MODE or KeyEvent.FLAG_SOFT_KEYBOARD,
                InputDevice.SOURCE_KEYBOARD))
        }
        if (isAlt) {
            ic?.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT, 0,
                KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_FROM_SYSTEM or KeyEvent.FLAG_KEEP_TOUCH_MODE or KeyEvent.FLAG_SOFT_KEYBOARD,
                InputDevice.SOURCE_KEYBOARD))
        }

        // 2. Dispatch target key down and up
        ic?.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
            KeyEvent.FLAG_FROM_SYSTEM or KeyEvent.FLAG_KEEP_TOUCH_MODE or KeyEvent.FLAG_SOFT_KEYBOARD,
            InputDevice.SOURCE_KEYBOARD))
        ic?.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, metaState,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
            KeyEvent.FLAG_FROM_SYSTEM or KeyEvent.FLAG_KEEP_TOUCH_MODE or KeyEvent.FLAG_SOFT_KEYBOARD,
            InputDevice.SOURCE_KEYBOARD))

        // 3. Dispatch modifier up events
        if (isAlt) {
            ic?.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ALT_LEFT, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_FROM_SYSTEM or KeyEvent.FLAG_KEEP_TOUCH_MODE or KeyEvent.FLAG_SOFT_KEYBOARD,
                InputDevice.SOURCE_KEYBOARD))
        }
        if (isShift) {
            ic?.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_FROM_SYSTEM or KeyEvent.FLAG_KEEP_TOUCH_MODE or KeyEvent.FLAG_SOFT_KEYBOARD,
                InputDevice.SOURCE_KEYBOARD))
        }
        if (isCtrl) {
            ic?.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_FROM_SYSTEM or KeyEvent.FLAG_KEEP_TOUCH_MODE or KeyEvent.FLAG_SOFT_KEYBOARD,
                InputDevice.SOURCE_KEYBOARD))
        }

        // Fallback: standard sendDownUpKeyEvents if IC didn't consume
        sendDownUpKeyEvents(keyCode, metaState)
    }

    private fun showDesktopShortcutsModal() {
        val container = inputViewContainer ?: return
        dismissActiveModal()

        // Shorter than normal keyboard: 72% height or max 195dp, giving maximum screen real estate to web code editors
        val kbHeight = keyboardView?.height ?: (260 * resources.displayMetrics.density).toInt()
        val desktopModalHeight = ((kbHeight * 0.72f).toInt()).coerceAtLeast((185 * resources.displayMetrics.density).toInt())

        val shortcutsView = com.example.ime.desktop.VianDesktopShortcutsModalView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                desktopModalHeight
            )
            onDismissToAlpha = { dismissActiveModal() }
            onDesktopAction = { actionId -> executeDesktopAction(actionId) }
            onNavigate = { dir ->
                when (dir) {
                    1 -> sendDesktopKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT, 0)
                    2 -> sendDesktopKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT, 0)
                    3 -> sendDesktopKeyEvent(KeyEvent.KEYCODE_DPAD_UP, 0)
                    4 -> sendDesktopKeyEvent(KeyEvent.KEYCODE_DPAD_DOWN, 0)
                }
            }
            onHome = {
                // Line start / Home
                sendDesktopKeyEvent(KeyEvent.KEYCODE_MOVE_HOME, 0)
            }
            onUndo = {
                currentInputConnection?.performContextMenuAction(android.R.id.undo)
                sendDesktopKeyEvent(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON)
            }
            onRedo = {
                currentInputConnection?.performContextMenuAction(android.R.id.redo)
                sendDesktopKeyEvent(KeyEvent.KEYCODE_Y, KeyEvent.META_CTRL_ON)
            }
            onDocTop = {
                sendDesktopKeyEvent(KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.META_CTRL_ON)
            }
            onDocBottom = {
                sendDesktopKeyEvent(KeyEvent.KEYCODE_MOVE_END, KeyEvent.META_CTRL_ON)
            }
            onSelectWord = {
                selectSurroundingWord()
            }
            onSelectAll = {
                currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
                sendDesktopKeyEvent(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON)
                Toast.makeText(this@VianBoardService, "Select All", Toast.LENGTH_SHORT).show()
            }
            onCopy = {
                currentInputConnection?.performContextMenuAction(android.R.id.copy)
                sendDesktopKeyEvent(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON)
                Toast.makeText(this@VianBoardService, "Copied", Toast.LENGTH_SHORT).show()
                handler.postDelayed({ capturePrimaryClip() }, 100)
            }
            onPromptList = {
                showQuickNotesModal()
            }
            onPaste = {
                currentInputConnection?.performContextMenuAction(android.R.id.paste)
                sendDesktopKeyEvent(KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON)
            }
            onClipboard = {
                showClipboardModal()
            }
            onDeleteDesktop = {
                // Raw desktop backspace keyevent for editor outdenting/multicursor delete
                sendDesktopKeyEvent(KeyEvent.KEYCODE_DEL, 0)
            }
            onEnterDesktop = {
                // Raw desktop enter keyevent for editor auto-indent and bracket-closing
                sendDesktopKeyEvent(KeyEvent.KEYCODE_ENTER, 0)
            }
            onSpace = {
                currentInputConnection?.commitText(" ", 1)
            }
        }

        keyboardView?.visibility = View.INVISIBLE
        container.addView(shortcutsView)
        activeModalView = shortcutsView
        LogKeeper.logEvent("IME", "Desktop Shortcuts modal opened (compact web code editor mode)")
    }

    private fun executeDesktopAction(actionId: String) {
        val ic = currentInputConnection
        when (actionId) {
            "find" -> {
                sendDesktopKeyEvent(KeyEvent.KEYCODE_F, KeyEvent.META_CTRL_ON)
                Toast.makeText(this, "Find (Ctrl+F)", Toast.LENGTH_SHORT).show()
            }
            "replace" -> {
                sendDesktopKeyEvent(KeyEvent.KEYCODE_H, KeyEvent.META_CTRL_ON)
                Toast.makeText(this, "Replace (Ctrl+H)", Toast.LENGTH_SHORT).show()
            }
            "copy_all" -> {
                ic?.performContextMenuAction(android.R.id.selectAll)
                sendDesktopKeyEvent(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON)
                handler.postDelayed({
                    ic?.performContextMenuAction(android.R.id.copy)
                    sendDesktopKeyEvent(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON)
                    Toast.makeText(this, "Copied All", Toast.LENGTH_SHORT).show()
                    handler.postDelayed({ capturePrimaryClip() }, 100)
                }, 50)
            }
            "delete_all" -> {
                ic?.performContextMenuAction(android.R.id.selectAll)
                sendDesktopKeyEvent(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON)
                handler.postDelayed({
                    sendDesktopKeyEvent(KeyEvent.KEYCODE_DEL, 0)
                    Toast.makeText(this, "Deleted All", Toast.LENGTH_SHORT).show()
                }, 50)
            }
            "goto" -> {
                sendDesktopKeyEvent(KeyEvent.KEYCODE_G, KeyEvent.META_CTRL_ON)
                Toast.makeText(this, "Go To (Ctrl+G)", Toast.LENGTH_SHORT).show()
            }
            "save" -> {
                sendDesktopKeyEvent(KeyEvent.KEYCODE_S, KeyEvent.META_CTRL_ON)
                Toast.makeText(this, "Saved (Ctrl+S)", Toast.LENGTH_SHORT).show()
            }
            "undo" -> {
                ic?.performContextMenuAction(android.R.id.undo)
                sendDesktopKeyEvent(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON)
                Toast.makeText(this, "Undo (Ctrl+Z)", Toast.LENGTH_SHORT).show()
            }
            "redo" -> {
                ic?.performContextMenuAction(android.R.id.redo)
                sendDesktopKeyEvent(KeyEvent.KEYCODE_Y, KeyEvent.META_CTRL_ON)
                Toast.makeText(this, "Redo (Ctrl+Y)", Toast.LENGTH_SHORT).show()
            }
            "select_all" -> {
                ic?.performContextMenuAction(android.R.id.selectAll)
                sendDesktopKeyEvent(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON)
                Toast.makeText(this, "Select All", Toast.LENGTH_SHORT).show()
            }
            "select_word" -> {
                selectSurroundingWord()
            }
            "duplicate_line" -> {
                sendDesktopKeyEvent(KeyEvent.KEYCODE_D, KeyEvent.META_CTRL_ON)
            }
            "comment_line" -> {
                sendDesktopKeyEvent(KeyEvent.KEYCODE_SLASH, KeyEvent.META_CTRL_ON)
            }
            "indent" -> {
                sendDesktopKeyEvent(KeyEvent.KEYCODE_TAB, 0)
            }
            "outdent" -> {
                sendDesktopKeyEvent(KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON)
            }
            "line_up" -> {
                sendDesktopKeyEvent(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.META_ALT_ON)
            }
            "line_down" -> {
                sendDesktopKeyEvent(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.META_ALT_ON)
            }
            else -> {
                Toast.makeText(this, "Action: $actionId", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showPatternUnlockModal(vaultType: VaultType, onPendingCommit: (() -> Unit)? = null) {
        val container = inputViewContainer ?: return
        dismissActiveModal()

        val patternUnlockView = VianPatternUnlockView(this, vaultType).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                getModalHeight()
            )
            onDismissToAlpha = { dismissActiveModal() }
            onUnlockSuccess = { type ->
                if (type == VaultType.SECURITY) {
                    VaultSessionManager.unlockSecurity(VaultSessionManager.SECURITY_SESSION_DEFAULT_MS)
                    Toast.makeText(this@VianBoardService, "Security Vault Unlocked (Session: 3m)", Toast.LENGTH_SHORT).show()
                } else {
                    VaultSessionManager.unlockPrivacy(VaultSessionManager.PRIVACY_SESSION_DEFAULT_MS)
                    Toast.makeText(this@VianBoardService, "Privacy Vault Unlocked (Session: 5m)", Toast.LENGTH_SHORT).show()
                    onPendingCommit?.invoke()
                }
                dismissActiveModal()
                LogKeeper.logEvent("IME", "$type vault session activated via pattern unlock")
            }
        }

        keyboardView?.visibility = View.INVISIBLE
        container.addView(patternUnlockView)
        activeModalView = patternUnlockView
        LogKeeper.logEvent("IME", "Pattern unlock modal opened for $vaultType")
    }

    private fun dismissActiveModal() {
        activeModalView?.let { modal ->
            if (modal is VianVoiceModalView) {
                modal.stopVoiceInput()
            }
            if (modal === warmClipboardView || modal === warmQuickNotesView) {
                modal.visibility = View.GONE
            } else {
                inputViewContainer?.removeView(modal)
            }
            activeModalView = null
        }
        keyboardView?.visibility = View.VISIBLE
    }

    private fun handleCommaPopupAction(item: String) {
        when (item) {
            "Settings" -> {
                val intent = Intent(this, SettingsActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            }
            "Emoji" -> {
                showEmojiModal()
            }
            "Clipboard" -> {
                showClipboardModal()
            }
            "Log Keeper" -> {
                val logs = LogKeeper.getLogs()
                Toast.makeText(this, "Log Keeper: ${logs.size} entries", Toast.LENGTH_SHORT).show()
            }
            "Shortcuts" -> {
                showDesktopShortcutsModal()
            }
            "Prompt List" -> {
                showQuickNotesModal()
            }
            "Voice" -> {
                showVoiceModal()
            }
            "One Hand" -> {
                Toast.makeText(this, "One hand mode: Coming soon", Toast.LENGTH_SHORT).show()
            }
            "Floating" -> {
                Toast.makeText(this, "Floating keyboard: Coming soon", Toast.LENGTH_SHORT).show()
            }
            "Personal Vault" -> {
                Toast.makeText(this, "Personal vault: Coming soon", Toast.LENGTH_SHORT).show()
            }
            "Security Vault" -> {
                Toast.makeText(this, "Security vault: Coming soon", Toast.LENGTH_SHORT).show()
            }
            else -> {
                Toast.makeText(this, "$item: Coming soon", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        LogKeeper.logEvent("IME", "onFinishInputView")
        try {
            clipboardManager?.removePrimaryClipChangedListener(clipboardListener)
        } catch (e: Exception) {
            // Ignore
        }
        dismissActiveModal()
        if (textEngineBridge.personalDictStorage.isAutoRelockOnCloseEnabled()) {
            VaultSessionManager.lockPrivacy()
        }
        textEngineBridge.onFinishInput(currentInputConnection)
        keyboardView?.updateSpaceLabel(textEngineBridge.currentMode.indicator)
        super.onFinishInputView(finishingInput)
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)

        // HeliBoard Bug B Safeguard: Filter out delayed, out-of-order selection updates during rapid local editing
        if (textEngineBridge.prefs.cursorSyncGuard) {
            val timeSinceLastEdit = System.currentTimeMillis() - textEngineBridge.getLastLocalEditTimestamp()
            if (timeSinceLastEdit < 150L && textEngineBridge.wordComposer.isComposingWord) {
                return
            }
        }

        if (candidatesStart < 0 && textEngineBridge.wordComposer.isComposingWord) {
            textEngineBridge.wordComposer.reset()
            textEngineBridge.clearSuggestions()
        }
    }

    override fun onDestroy() {
        LogKeeper.logComponentStop("VianBoardService")
        try {
            clipboardManager?.removePrimaryClipChangedListener(clipboardListener)
        } catch (e: Exception) {
            // Ignore
        }
        dismissActiveModal()
        warmClipboardView = null
        warmQuickNotesView = null
        try {
            unregisterReceiver(reloadDictsReceiver)
        } catch (_: Exception) {}
        if (activeInstance === this) {
            activeInstance = null
        }
        textEngineBridge.onDestroy()
        inputViewContainer = null
        keyboardView = null
        super.onDestroy()
    }
}
