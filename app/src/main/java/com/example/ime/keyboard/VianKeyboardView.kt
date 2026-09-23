package com.example.ime.keyboard

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.HapticFeedbackConstants
import androidx.core.content.ContextCompat
import com.example.R
import com.example.ime.toolbar.ToolbarPreferences
import com.example.ime.toolbar.ToolbarTool

class VianKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var theme: KeyboardTheme = KeyboardTheme.loadFromPrefs(context)
        set(value) {
            field = value
            updatePaints()
            val density = resources.displayMetrics.density
            layout.buildLayout(width.toFloat(), height.toFloat(), theme, density, bottomNavInsetPx)
            requestLayout()
            invalidate()
        }

    val layout = KeyboardLayout()
    var onKeyAction: ((KeyData) -> Unit)? = null
    var onTextCommit: ((String) -> Unit)? = null
    var onActionExpand: (() -> Unit)? = null
    var onActionSelection: (() -> Unit)? = null
    var onActionClipboard: (() -> Unit)? = null

    // Toolbar tool callbacks
    var onToolbarToolClick: ((ToolbarTool) -> Unit)? = null
    var onToolbarToolLongClick: ((ToolbarTool) -> Unit)? = null
    var onAnchorLongClick: (() -> Unit)? = null
    var onCommaPopupSelected: ((String) -> Unit)? = null
    var onSymbolsLongClick: (() -> Unit)? = null
    var onSuggestionClick: ((String, Int) -> Unit)? = null
    var onSuggestionLongClick: ((KeyData, String, Int) -> Unit)? = null
    var onSpaceLongClick: ((KeyData) -> Unit)? = null
    var onLayoutUpdated: ((List<KeyData>, Int, Int) -> Unit)? = null

    // Lite Mode toggle (Phase 3.2): Disables gesture trajectory processing
    var isLiteMode: Boolean = false

    private val toolbarPrefs = ToolbarPreferences(context)
    private val iconCache = mutableMapOf<Int, Drawable>()
    private var isLongPressTriggered = false

    // Horizontal scroll tracking for expanded toolbar
    private var isToolbarScrolling = false
    private var toolbarTouchStartX = 0f
    private var toolbarInitialScrollOffset = 0f
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private val popupWindow = KeyPopupWindow(context)

    // Canvas Paints (Pre-allocated)
    private val backgroundPaint = Paint()
    private val toolbarBackgroundPaint = Paint()
    private val keyBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val actionKeyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val enterKeyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pressedKeyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val keyBevelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val actionKeyBevelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val enterKeyBevelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val suggestionDividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33000000
        style = Paint.Style.STROKE
    }
    private val tempRectF = RectF()
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
    }
    private val actionTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
    }
    private val enterTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        typeface = Typeface.DEFAULT
    }
    private val toolbarTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
    }
    private val suggestionBoldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    // Vector Icon Paints
    private val iconStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val iconFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val enterIconStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val enterIconFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val pathHelper = Path()

    private var activePressedKey: KeyData? = null
    private var lastShiftPressTime = 0L
    private var isMultiPopupActive = false
    private var isCommaGridPopupActive = false
    private var isPeriodGridPopupActive = false
    internal var bottomNavInsetPx = 0f

    // Comma popup item definitions (Settings hardcoded + 4 customizable slots = 5 items in 1 row)
    private val commaPrefs by lazy { com.example.ime.settings.CommaPreferences(context) }

    private fun getDynamicCommaPopupItems(): Pair<List<String>, List<Int>> {
        val selectedSlots = commaPrefs.getSelectedSlots()
        val items = mutableListOf("Settings")
        val icons = mutableListOf(R.drawable.ic_settings)

        for (slot in selectedSlots) {
            when (slot) {
                "Voice" -> {
                    items.add("Voice")
                    icons.add(R.drawable.ic_mic)
                }
                "Desktop" -> {
                    items.add("Shortcuts")
                    icons.add(R.drawable.ic_desktop_shortcuts)
                }
                "Emoji" -> {
                    items.add("Emoji")
                    icons.add(R.drawable.ic_emoji_smileys)
                }
                "Log Keeper" -> {
                    items.add("Log Keeper")
                    icons.add(R.drawable.ic_log_keeper)
                }
                "Clipboard" -> {
                    items.add("Clipboard")
                    icons.add(R.drawable.ic_clipboard)
                }
                "Prompt List" -> {
                    items.add("Prompt List")
                    icons.add(R.drawable.ic_prompt_list)
                }
                "One Hand" -> {
                    items.add("One Hand")
                    icons.add(R.drawable.ic_one_hand)
                }
                "Floating" -> {
                    items.add("Floating")
                    icons.add(R.drawable.ic_floating_keyboard)
                }
                "Personal Vault" -> {
                    items.add("Personal Vault")
                    icons.add(R.drawable.ic_personal_vault)
                }
                "Security Vault" -> {
                    items.add("Security Vault")
                    icons.add(R.drawable.ic_security_vault)
                }
            }
        }
        return Pair(items, icons)
    }

    // Period popup symbol definitions (16 symbols: 2 rows of 8) matching Screenshot 1
    // Top Row: ! ? ; / ^ : ~ \
    // Bottom Row: " ' - ( ) [ ] {
    private val periodPopupItems = listOf(
        "!", "?", ";", "/", "^", ":", "~", "\\",
        "\"", "'", "-", "(", ")", "[", "]", "{"
    )

    // Long-press and repeat handler
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isRepeatingBackspace = false

    private val repeatBackspaceRunnable = object : Runnable {
        override fun run() {
            if (activePressedKey?.type == KeyType.DELETE) {
                isRepeatingBackspace = true
                activePressedKey?.let { onKeyAction?.invoke(it) }
                mainHandler.postDelayed(this, 50)
            }
        }
    }

    private val longPressRunnable = Runnable {
        activePressedKey?.let { key ->
            if (key.type == KeyType.DELETE) {
                mainHandler.post(repeatBackspaceRunnable)
            } else if (key.type == KeyType.ACTION_EXPAND) {
                isLongPressTriggered = true
                onAnchorLongClick?.invoke()
            } else if (key.type == KeyType.SPACE) {
                isLongPressTriggered = true
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onSpaceLongClick?.invoke(key)
            } else if (key.type == KeyType.SUGGESTION) {
                isLongPressTriggered = true
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                val slotIndex = -(key.code + 200)
                onSuggestionLongClick?.invoke(key, key.label, slotIndex)
            } else if (key.type == KeyType.TOOLBAR_TOOL) {
                key.tool?.let { tool ->
                    isLongPressTriggered = true
                    onToolbarToolLongClick?.invoke(tool)
                }
            } else if (key.type == KeyType.COMMA) {
                isLongPressTriggered = true
                isCommaGridPopupActive = true
                val (dynItems, dynIcons) = getDynamicCommaPopupItems()
                val cols = dynItems.size.coerceAtLeast(1)
                popupWindow.showGridKeys(
                    anchor = this@VianKeyboardView,
                    key = key,
                    items = dynItems,
                    iconResIds = dynIcons,
                    cols = cols,
                    rows = 1,
                    theme = theme
                )
            } else if (key.type == KeyType.PERIOD) {
                isLongPressTriggered = true
                isPeriodGridPopupActive = true
                popupWindow.showGridKeys(
                    anchor = this@VianKeyboardView,
                    key = key,
                    items = periodPopupItems,
                    iconResIds = emptyList(),
                    cols = 8,
                    rows = 2,
                    theme = theme
                )
            } else if (key.type == KeyType.SYMBOLS_TOGGLE || key.type == KeyType.NUMPAD_TOGGLE) {
                if (key.label == "?123" || key.label.contains("12") || key.type == KeyType.NUMPAD_TOGGLE) {
                    isLongPressTriggered = true
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onSymbolsLongClick?.invoke()
                }
            } else if (key.moreKeys.isNotEmpty() || !key.hintLabel.isNullOrEmpty()) {
                isMultiPopupActive = true
                val popupItems = if (key.moreKeys.isNotEmpty()) key.moreKeys else listOf(key.hintLabel!!)
                popupWindow.showMoreKeys(this@VianKeyboardView, key, popupItems, theme)
            }
        }
    }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        layout.pinnedTools = toolbarPrefs.getPinnedTools()
        layout.expandedTools = toolbarPrefs.getExpandedTools()
        layout.hidePinnedWhenExpanded = toolbarPrefs.hidePinnedWhenExpanded
        updatePaints()

        setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val navInsets = insets.getInsets(WindowInsets.Type.navigationBars())
                bottomNavInsetPx = navInsets.bottom.toFloat()
            } else {
                @Suppress("DEPRECATION")
                bottomNavInsetPx = insets.systemWindowInsetBottom.toFloat()
            }
            requestLayout()
            insets
        }
    }

    private fun updatePaints() {
        val density = resources.displayMetrics.density

        backgroundPaint.color = theme.backgroundColor
        toolbarBackgroundPaint.color = theme.backgroundColor
        keyBackgroundPaint.color = theme.keyBackgroundColor
        actionKeyPaint.color = theme.actionKeyColor
        enterKeyPaint.color = theme.enterKeyColor
        pressedKeyPaint.color = theme.pressedKeyColor

        // HeliBoard Rounded Base Border bevel colors
        keyBevelPaint.color = theme.keyBottomBevelColor
        actionKeyBevelPaint.color = theme.actionKeyBevelColor
        enterKeyBevelPaint.color = 0xFF263238.toInt()

        suggestionDividerPaint.color = (theme.textColor and 0x00FFFFFF) or 0x22000000
        suggestionDividerPaint.strokeWidth = 1f * density

        borderPaint.color = theme.borderColor
        borderPaint.strokeWidth = theme.borderWidthDp * density

        textPaint.color = theme.textColor
        textPaint.textSize = 21f * density

        actionTextPaint.color = theme.textColor
        actionTextPaint.textSize = 14f * density

        enterTextPaint.color = theme.enterTextColor
        enterTextPaint.textSize = 18f * density

        hintPaint.color = theme.hintColor
        hintPaint.textSize = 10.5f * density

        toolbarTextPaint.color = theme.textColor
        toolbarTextPaint.textSize = 14.5f * density

        suggestionBoldPaint.color = theme.textColor
        suggestionBoldPaint.textSize = 15f * density

        iconStrokePaint.color = theme.textColor
        iconStrokePaint.strokeWidth = 2.2f * density

        iconFillPaint.color = theme.textColor

        enterIconStrokePaint.color = theme.enterTextColor
        enterIconStrokePaint.strokeWidth = 2.4f * density

        enterIconFillPaint.color = theme.enterTextColor
    }

    fun reloadTheme() {
        theme = KeyboardTheme.loadFromPrefs(context)
        reloadToolbarConfiguration()
    }

    fun setMode(newMode: KeyboardMode) {
        if (layout.mode != newMode) {
            layout.mode = newMode
            val density = resources.displayMetrics.density
            requestLayout()
            layout.buildLayout(width.toFloat(), height.toFloat(), theme, density, bottomNavInsetPx)
            invalidate()
        }
    }

    fun reloadToolbarConfiguration() {
        layout.pinnedTools = toolbarPrefs.getPinnedTools()
        layout.expandedTools = toolbarPrefs.getExpandedTools()
        layout.hidePinnedWhenExpanded = toolbarPrefs.hidePinnedWhenExpanded
        val density = resources.displayMetrics.density
        layout.buildLayout(width.toFloat(), height.toFloat(), theme, density, bottomNavInsetPx)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val density = resources.displayMetrics.density
        val rowCount = if (layout.mode == KeyboardMode.NUMPAD) 4 else 5
        val rowsTotalHeight = (theme.keyHeightDp * rowCount * density)
        val toolbarHeight = (theme.toolbarHeightDp * density)
        val paddingHeight = (12f * density)
        // Fixed overall keyboard container height based on user keyHeightDp + toolbar + insets.
        // The verticalGapDp slider adjusts internal spacing between rows inside the keyboard without altering total height.
        val totalCalculatedHeight = (rowsTotalHeight + toolbarHeight + paddingHeight + bottomNavInsetPx).toInt()
        val height = totalCalculatedHeight.coerceAtLeast((260 * density).toInt())
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val density = resources.displayMetrics.density
        layout.buildLayout(w.toFloat(), h.toFloat(), theme, density, bottomNavInsetPx)
        onLayoutUpdated?.invoke(layout.keys, w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val cornerRadius = theme.keyCornerRadiusDp * density
        val hasBorder = theme.borderWidthDp > 0

        // 1. Draw Keyboard Background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        // 2. Draw Top Toolbar Items
        if (layout.isToolbarExpanded && layout.toolbarScrollBounds.width() > 0) {
            // A. Draw Anchor Key first (fixed, non-scrolling)
            val anchorKey = layout.toolbarKeys.firstOrNull { it.type == KeyType.ACTION_EXPAND }
            if (anchorKey != null) {
                val bgPaint = if (anchorKey.isPressed) pressedKeyPaint else actionKeyPaint
                val circleDiameter = 28f * density
                val circleLeft = anchorKey.bounds.left + (4f * density)
                val circleTop = anchorKey.bounds.centerY() - (circleDiameter / 2f)
                tempRectF.set(circleLeft, circleTop, circleLeft + circleDiameter, circleTop + circleDiameter)
                val anchorRadius = circleDiameter / 2f
                canvas.drawRoundRect(tempRectF, anchorRadius, anchorRadius, bgPaint)
                if (layout.isIncognitoActive) {
                    drawVectorIcon(canvas, tempRectF, R.drawable.sym_keyboard_incognito_lxx, 14f * density, theme.textColor)
                } else {
                    val chevronRes = if (layout.isToolbarExpanded) R.drawable.ic_chevron_left else R.drawable.ic_chevron_right
                    drawVectorIcon(canvas, tempRectF, chevronRes, 13f * density, theme.textColor)
                }
            }

            // B. Clip and translate scrollable tools tray (tools in middle)
            canvas.save()
            canvas.clipRect(layout.toolbarScrollBounds)
            canvas.translate(-layout.toolbarScrollOffset, 0f)

            for (key in layout.toolbarKeys) {
                if (key.code !in -300 downTo -399) continue
                if (key.isPressed) {
                    val toolRadius = key.bounds.height() / 2f
                    canvas.drawRoundRect(key.bounds, toolRadius, toolRadius, pressedKeyPaint)
                }

                if (key.type == KeyType.TOOLBAR_TOOL) {
                    key.tool?.let { tool ->
                        drawVectorIcon(canvas, key.bounds, tool.iconResId, 18f * density, theme.textColor)
                    }
                } else {
                    val textY = key.bounds.centerY() - ((toolbarTextPaint.descent() + toolbarTextPaint.ascent()) / 2)
                    canvas.drawText(key.label, key.bounds.centerX(), textY, toolbarTextPaint)
                }
            }
            canvas.restore()

            // C. Draw docked pinned tools (fixed on right edge)
            for (key in layout.toolbarKeys) {
                if (key.code !in -400 downTo -499) continue
                if (key.isPressed) {
                    val toolRadius = key.bounds.height() / 2f
                    canvas.drawRoundRect(key.bounds, toolRadius, toolRadius, pressedKeyPaint)
                }
                if (key.type == KeyType.TOOLBAR_TOOL) {
                    key.tool?.let { tool ->
                        drawVectorIcon(canvas, key.bounds, tool.iconResId, 18f * density, theme.textColor)
                    }
                }
            }
        } else {
            // Draw subtle slot dividers for suggestion bar matching HeliBoard
            val anchorKey = layout.toolbarKeys.firstOrNull { it.type == KeyType.ACTION_EXPAND }
            val firstPinnedKey = layout.toolbarKeys.firstOrNull { it.code in -400 downTo -499 }
            val middleLeft = (anchorKey?.bounds?.right ?: (40f * density)) + (4f * density)
            val middleRight = (firstPinnedKey?.bounds?.left ?: (width - 40f * density)) - (4f * density)
            if (middleRight > middleLeft) {
                val middleWidth = middleRight - middleLeft
                val slotWidth = middleWidth / 3f
                val centerY = anchorKey?.bounds?.centerY() ?: (4f * density + (theme.toolbarHeightDp * density / 2f))
                val divHalfH = 7f * density
                val divY1 = centerY - divHalfH
                val divY2 = centerY + divHalfH
                val divX1 = middleLeft + slotWidth
                val divX2 = middleLeft + (slotWidth * 2f)
                canvas.drawLine(divX1, divY1, divX1, divY2, suggestionDividerPaint)
                canvas.drawLine(divX2, divY1, divX2, divY2, suggestionDividerPaint)
            }

            for (key in layout.toolbarKeys) {
                if (key.type == KeyType.ACTION_EXPAND) {
                    val bgPaint = if (key.isPressed) pressedKeyPaint else actionKeyPaint
                    val circleDiameter = 28f * density
                    val circleLeft = key.bounds.left + (4f * density)
                    val circleTop = key.bounds.centerY() - (circleDiameter / 2f)
                    tempRectF.set(circleLeft, circleTop, circleLeft + circleDiameter, circleTop + circleDiameter)
                    val anchorRadius = circleDiameter / 2f
                    canvas.drawRoundRect(tempRectF, anchorRadius, anchorRadius, bgPaint)
                    if (layout.isIncognitoActive) {
                        drawVectorIcon(canvas, tempRectF, R.drawable.sym_keyboard_incognito_lxx, 14f * density, theme.textColor)
                    } else {
                        val chevronRes = if (layout.isToolbarExpanded) R.drawable.ic_chevron_left else R.drawable.ic_chevron_right
                        drawVectorIcon(canvas, tempRectF, chevronRes, 13f * density, theme.textColor)
                    }
                } else if (key.type == KeyType.TOOLBAR_TOOL) {
                    if (key.isPressed) {
                        val toolRadius = key.bounds.height() / 2f
                        canvas.drawRoundRect(key.bounds, toolRadius, toolRadius, pressedKeyPaint)
                    }
                    key.tool?.let { tool ->
                        drawVectorIcon(canvas, key.bounds, tool.iconResId, 18f * density, theme.textColor)
                    }
                } else if (key.type == KeyType.SUGGESTION) {
                    val bgPaint = if (key.isPressed) pressedKeyPaint else toolbarBackgroundPaint
                    canvas.drawRoundRect(key.bounds, 8f * density, 8f * density, bgPaint)
                    val paintToUse = if (key.code == -201) suggestionBoldPaint else toolbarTextPaint
                    val textY = key.bounds.centerY() - ((paintToUse.descent() + paintToUse.ascent()) / 2)
                    canvas.drawText(key.label, key.bounds.centerX(), textY, paintToUse)
                }
            }
        }

        // 3. Draw Main Keys
        for (key in layout.keys) {
            val isActionKey = key.type != KeyType.CHARACTER && key.type != KeyType.SPACE && key.type != KeyType.COMMA && key.type != KeyType.PERIOD
            val isEnter = key.type == KeyType.ENTER

            val currentBgPaint = when {
                key.isPressed -> pressedKeyPaint
                isEnter -> enterKeyPaint
                isActionKey -> actionKeyPaint
                else -> keyBackgroundPaint
            }

            // Determine corner radius: special functional keys are pill/stadium curves matching HeliBoard Rounded Base Border
            val isSpecialKey = key.type == KeyType.SHIFT ||
                               key.type == KeyType.SYMBOLS_TOGGLE ||
                               key.type == KeyType.SYMBOLS_MORE_TOGGLE ||
                               key.type == KeyType.NUMPAD_TOGGLE ||
                               key.type == KeyType.DELETE ||
                               key.type == KeyType.ENTER

            val currentRadius = if (isSpecialKey) {
                key.bounds.height() / 2f
            } else {
                10f * density
            }

            val bevelInsetBottomPx = 1.0f * density

            if (key.isPressed) {
                // Key pressed: flat depressed surface
                canvas.drawRoundRect(key.bounds, currentRadius, currentRadius, pressedKeyPaint)
            } else {
                // 1. Bottom bevel layer (HeliBoard layer-list reproduction: only bottom edge shows dark bevel)
                val bevelPaint = when {
                    isEnter -> enterKeyBevelPaint
                    isActionKey -> actionKeyBevelPaint
                    else -> keyBevelPaint
                }
                canvas.drawRoundRect(key.bounds, currentRadius, currentRadius, bevelPaint)

                // 2. Top keycap surface inset at bottom by 1dp
                tempRectF.set(
                    key.bounds.left,
                    key.bounds.top,
                    key.bounds.right,
                    key.bounds.bottom - bevelInsetBottomPx
                )
                canvas.drawRoundRect(tempRectF, currentRadius, currentRadius, currentBgPaint)
            }

            // Key label or custom vector icon
            when (key.type) {
                KeyType.DELETE -> {
                    drawVectorIcon(canvas, key.bounds, R.drawable.sym_keyboard_delete_rounded, 22f * density, theme.textColor)
                }
                KeyType.SHIFT -> {
                    val (iconRes, tint) = when (layout.shiftState) {
                        ShiftState.OFF -> Pair(R.drawable.sym_keyboard_shift_rounded, theme.textColor)
                        ShiftState.ON -> Pair(R.drawable.sym_keyboard_shift_rounded, theme.accentColor)
                        ShiftState.CAPS_LOCK -> Pair(R.drawable.sym_keyboard_shift_lock_rounded, theme.accentColor)
                    }
                    drawVectorIcon(canvas, key.bounds, iconRes, 22f * density, tint)
                }
                KeyType.ENTER -> {
                    drawVectorIcon(canvas, key.bounds, R.drawable.sym_keyboard_return_rounded, 22f * density, theme.enterTextColor)
                }
                else -> {
                    val paintToUse = when {
                        key.type == KeyType.CHARACTER || key.type == KeyType.SPACE || key.type == KeyType.COMMA || key.type == KeyType.PERIOD -> textPaint
                        else -> actionTextPaint
                    }

                    if (key.type == KeyType.SPACE) {
                        if (key.label.isNotEmpty()) {
                            val textY = key.bounds.centerY() - ((hintPaint.descent() + hintPaint.ascent()) / 2)
                            canvas.drawText(key.label, key.bounds.centerX(), textY, hintPaint)
                        }
                    } else if (key.label.contains("\n")) {
                        val lines = key.label.split("\n")
                        val totalH = (lines.size * 12f * density)
                        var lineY = key.bounds.centerY() - (totalH / 2) + (8f * density)
                        for (line in lines) {
                            canvas.drawText(line, key.bounds.centerX(), lineY, paintToUse)
                            lineY += (12f * density)
                        }
                    } else {
                        val textY = key.bounds.centerY() - ((paintToUse.descent() + paintToUse.ascent()) / 2)
                        canvas.drawText(key.label, key.bounds.centerX(), textY, paintToUse)
                    }
                }
            }

            // Hint label (top right corner or bottom center-right for comma and period)
            if (theme.showHints && key.hintLabel != null) {
                if (key.type == KeyType.COMMA || key.type == KeyType.PERIOD) {
                    val hintX = key.bounds.centerX() + (3.5f * density)
                    val hintY = key.bounds.bottom - (4.5f * density)
                    canvas.drawText(key.hintLabel, hintX, hintY, hintPaint)
                } else if (key.type == KeyType.ENTER || key.type == KeyType.SPACE) {
                    // Suppress hint label on Space and Enter
                } else {
                    val hintX = key.bounds.right - (4f * density)
                    val hintY = key.bounds.top + (11f * density)
                    canvas.drawText(key.hintLabel, hintX, hintY, hintPaint)
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isLongPressTriggered = false
                isToolbarScrolling = false
                toolbarTouchStartX = x
                toolbarInitialScrollOffset = layout.toolbarScrollOffset

                val key = layout.findKeyAt(x, y)
                if (key != null) {
                    activePressedKey = key
                    key.isPressed = true
                    isRepeatingBackspace = false
                    isMultiPopupActive = false
                    isCommaGridPopupActive = false
                    isPeriodGridPopupActive = false

                    mainHandler.postDelayed(longPressRunnable, 400L)
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (isCommaGridPopupActive || isPeriodGridPopupActive) {
                    popupWindow.updateSelection(event.rawX, event.rawY)
                } else if (isMultiPopupActive) {
                    popupWindow.updateSelection(event.rawX, event.rawY)
                } else {
                    // Check if dragging/scrolling on the expanded toolbar
                    if (layout.isToolbarExpanded && layout.toolbarScrollBounds.contains(toolbarTouchStartX, y) && layout.maxToolbarScrollOffset > 0f) {
                        val deltaX = x - toolbarTouchStartX
                        if (isToolbarScrolling || Math.abs(deltaX) > touchSlop) {
                            isToolbarScrolling = true
                            mainHandler.removeCallbacks(longPressRunnable)
                            activePressedKey?.isPressed = false
                            activePressedKey = null

                            val newOffset = (toolbarInitialScrollOffset - deltaX).coerceIn(0f, layout.maxToolbarScrollOffset)
                            if (newOffset != layout.toolbarScrollOffset) {
                                layout.toolbarScrollOffset = newOffset
                                invalidate()
                            }
                            return true
                        }
                    }

                    val key = layout.findKeyAt(x, y)
                    if (key != activePressedKey) {
                        activePressedKey?.isPressed = false
                        mainHandler.removeCallbacks(longPressRunnable)
                        mainHandler.removeCallbacks(repeatBackspaceRunnable)
                        popupWindow.dismiss()

                        activePressedKey = key
                        key?.isPressed = true
                        if (key != null) {
                            mainHandler.postDelayed(longPressRunnable, 400L)
                        }
                        invalidate()
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                mainHandler.removeCallbacks(longPressRunnable)
                mainHandler.removeCallbacks(repeatBackspaceRunnable)

                if (isToolbarScrolling) {
                    isToolbarScrolling = false
                    activePressedKey?.isPressed = false
                    activePressedKey = null
                    invalidate()
                    return true
                }

                try {
                    if (isCommaGridPopupActive) {
                        val selected = popupWindow.getSelectedItem()
                        if (!selected.isNullOrEmpty()) {
                            onCommaPopupSelected?.invoke(selected)
                        }
                    } else if (isPeriodGridPopupActive) {
                        val selected = popupWindow.getSelectedItem()
                        if (!selected.isNullOrEmpty()) {
                            onTextCommit?.invoke(selected)
                        }
                    } else if (isMultiPopupActive) {
                        val selected = popupWindow.getSelectedItem()
                        if (!selected.isNullOrEmpty()) {
                            onTextCommit?.invoke(selected)
                        }
                    } else {
                        val key = activePressedKey
                        if (key != null && !isRepeatingBackspace && !isLongPressTriggered) {
                            handleKeySelection(key)
                        }
                    }
                } catch (e: Exception) {
                    com.example.logger.LogKeeper.logError(
                        component = "VianKeyboardView",
                        errorCode = "POPUP_SELECTION_ERROR",
                        errorDetails = "${e.javaClass.simpleName}: ${e.message}"
                    )
                } finally {
                    popupWindow.dismiss()
                    activePressedKey?.isPressed = false
                    activePressedKey = null
                    isRepeatingBackspace = false
                    isMultiPopupActive = false
                    isCommaGridPopupActive = false
                    isPeriodGridPopupActive = false
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(longPressRunnable)
                mainHandler.removeCallbacks(repeatBackspaceRunnable)
                popupWindow.dismiss()
                activePressedKey?.isPressed = false
                activePressedKey = null
                isRepeatingBackspace = false
                isMultiPopupActive = false
                isCommaGridPopupActive = false
                isPeriodGridPopupActive = false
                isToolbarScrolling = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleKeySelection(key: KeyData) {
        when (key.type) {
            KeyType.SHIFT -> {
                val now = System.currentTimeMillis()
                layout.shiftState = when (layout.shiftState) {
                    ShiftState.OFF -> ShiftState.ON
                    ShiftState.ON -> {
                        if (now - lastShiftPressTime < 350) ShiftState.CAPS_LOCK else ShiftState.OFF
                    }
                    ShiftState.CAPS_LOCK -> ShiftState.OFF
                }
                lastShiftPressTime = now
                val density = resources.displayMetrics.density
                layout.buildLayout(width.toFloat(), height.toFloat(), theme, density, bottomNavInsetPx)
                invalidate()
            }

            KeyType.SYMBOLS_TOGGLE -> {
                layout.mode = when (layout.mode) {
                    KeyboardMode.CHARACTERS -> KeyboardMode.SYMBOLS_1
                    KeyboardMode.NUMPAD -> KeyboardMode.CHARACTERS
                    else -> KeyboardMode.CHARACTERS
                }
                val density = resources.displayMetrics.density
                layout.buildLayout(width.toFloat(), height.toFloat(), theme, density, bottomNavInsetPx)
                invalidate()
            }

            KeyType.SYMBOLS_MORE_TOGGLE -> {
                layout.mode = if (layout.mode == KeyboardMode.SYMBOLS_1) KeyboardMode.SYMBOLS_2 else KeyboardMode.SYMBOLS_1
                val density = resources.displayMetrics.density
                layout.buildLayout(width.toFloat(), height.toFloat(), theme, density, bottomNavInsetPx)
                invalidate()
            }

            KeyType.NUMPAD_TOGGLE -> {
                layout.mode = KeyboardMode.NUMPAD
                val density = resources.displayMetrics.density
                layout.buildLayout(width.toFloat(), height.toFloat(), theme, density, bottomNavInsetPx)
                invalidate()
            }

            KeyType.TOOLBAR_TOOL -> {
                key.tool?.let { onToolbarToolClick?.invoke(it) }
            }

            KeyType.ACTION_EXPAND -> {
                onActionExpand?.invoke()
            }

            KeyType.ACTION_SELECTION -> {
                onActionSelection?.invoke()
            }

            KeyType.ACTION_CLIPBOARD -> {
                onActionClipboard?.invoke()
            }

            KeyType.SUGGESTION -> {
                val slotIndex = -(key.code + 200)
                if (onSuggestionClick != null) {
                    onSuggestionClick?.invoke(key.label, slotIndex)
                } else {
                    onTextCommit?.invoke(key.label + " ")
                }
            }

            else -> {
                onKeyAction?.invoke(key)
                // Auto-reset Shift if single capitalized letter typed
                if (layout.shiftState == ShiftState.ON && key.type == KeyType.CHARACTER) {
                    layout.shiftState = ShiftState.OFF
                    val density = resources.displayMetrics.density
                    layout.buildLayout(width.toFloat(), height.toFloat(), theme, density, bottomNavInsetPx)
                    invalidate()
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        popupWindow.dismiss()
        super.onDetachedFromWindow()
    }

    // Vector Icon Renderers (Matching HeliBoard & Screenshot)

    /**
     * Backspace Tag Icon (⌫):
     * Pointed tag outline pointing to the left with a centered '✕'
     */
    private fun drawBackspaceIcon(canvas: Canvas, bounds: RectF, density: Float) {
        val iconW = 20f * density
        val iconH = 14f * density
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val left = cx - (iconW / 2)
        val top = cy - (iconH / 2)
        val right = cx + (iconW / 2)
        val bottom = cy + (iconH / 2)
        val arrowW = 6.5f * density

        pathHelper.reset()
        pathHelper.moveTo(left, cy)
        pathHelper.lineTo(left + arrowW, top)
        pathHelper.lineTo(right, top)
        pathHelper.lineTo(right, bottom)
        pathHelper.lineTo(left + arrowW, bottom)
        pathHelper.close()
        canvas.drawPath(pathHelper, iconStrokePaint)

        // Draw inner ✕
        val crossHalf = 3.2f * density
        val crossCx = cx + (arrowW * 0.28f)
        canvas.drawLine(crossCx - crossHalf, cy - crossHalf, crossCx + crossHalf, cy + crossHalf, iconStrokePaint)
        canvas.drawLine(crossCx + crossHalf, cy - crossHalf, crossCx - crossHalf, cy + crossHalf, iconStrokePaint)
    }

    /**
     * Shift Chevron Icon:
     * Clean upward chevron (^).
     * OFF: stroke outline matching text
     * ON: illuminated / thicker stroke or accent fill
     * CAPS_LOCK: illuminated chevron with horizontal lock underline
     */
    private fun drawShiftChevronIcon(canvas: Canvas, bounds: RectF, density: Float, state: ShiftState) {
        val chevronW = 14f * density
        val chevronH = 8.5f * density
        val cx = bounds.centerX()
        val cy = bounds.centerY() - (if (state == ShiftState.CAPS_LOCK) 2f * density else 0f)

        val left = cx - (chevronW / 2)
        val right = cx + (chevronW / 2)
        val top = cy - (chevronH / 2)
        val bottom = cy + (chevronH / 2)

        when (state) {
            ShiftState.OFF -> {
                pathHelper.reset()
                pathHelper.moveTo(left, bottom)
                pathHelper.lineTo(cx, top)
                pathHelper.lineTo(right, bottom)
                canvas.drawPath(pathHelper, iconStrokePaint)
            }
            ShiftState.ON -> {
                // Active single shift - illuminated bold chevron
                val activePaint = Paint(iconStrokePaint).apply {
                    color = theme.accentColor
                    strokeWidth = 3.2f * density
                }
                pathHelper.reset()
                pathHelper.moveTo(left, bottom)
                pathHelper.lineTo(cx, top)
                pathHelper.lineTo(right, bottom)
                canvas.drawPath(pathHelper, activePaint)
            }
            ShiftState.CAPS_LOCK -> {
                // Caps Lock - illuminated chevron with lock bar underneath
                val lockPaint = Paint(iconStrokePaint).apply {
                    color = theme.accentColor
                    strokeWidth = 3.0f * density
                }
                pathHelper.reset()
                pathHelper.moveTo(left, bottom)
                pathHelper.lineTo(cx, top)
                pathHelper.lineTo(right, bottom)
                canvas.drawPath(pathHelper, lockPaint)

                // Lock horizontal bar beneath chevron
                val barY = bottom + (5f * density)
                canvas.drawLine(cx - (5.5f * density), barY, cx + (5.5f * density), barY, lockPaint)
            }
        }
    }

    /**
     * Enter / Return Elbow Icon (↵):
     * White return path entering from right, going left, ending in arrow
     */
    private fun drawEnterReturnIcon(canvas: Canvas, bounds: RectF, density: Float) {
        val iconW = 18f * density
        val iconH = 13f * density
        val cx = bounds.centerX()
        val cy = bounds.centerY()

        val left = cx - (iconW / 2)
        val right = cx + (iconW / 2)
        val top = cy - (iconH / 2)
        val bottom = cy + (iconH / 2)
        val arrowSize = 4.5f * density

        pathHelper.reset()
        // Start top right, come down, turn left
        pathHelper.moveTo(right, top)
        pathHelper.lineTo(right, bottom)
        pathHelper.lineTo(left, bottom)
        canvas.drawPath(pathHelper, enterIconStrokePaint)

        // Draw left arrow head
        pathHelper.reset()
        pathHelper.moveTo(left, bottom)
        pathHelper.lineTo(left + arrowSize, bottom - arrowSize)
        pathHelper.moveTo(left, bottom)
        pathHelper.lineTo(left + arrowSize, bottom + arrowSize)
        canvas.drawPath(pathHelper, enterIconStrokePaint)
    }

    private fun drawVectorIcon(canvas: Canvas, bounds: RectF, resId: Int, sizePx: Float, tintColor: Int) {
        val drawable = iconCache.getOrPut(resId) {
            ContextCompat.getDrawable(context, resId)?.mutate() ?: return
        }
        val left = (bounds.centerX() - (sizePx / 2f)).toInt()
        val top = (bounds.centerY() - (sizePx / 2f)).toInt()
        val right = (left + sizePx).toInt()
        val bottom = (top + sizePx).toInt()
        drawable.setBounds(left, top, right, bottom)
        drawable.setTint(tintColor)
        drawable.draw(canvas)
    }

    fun updateSuggestions(newSuggestions: List<String>) {
        if (layout.suggestions != newSuggestions) {
            layout.suggestions = newSuggestions
            if (width > 0 && height > 0) {
                val density = resources.displayMetrics.density
                layout.buildLayout(width.toFloat(), height.toFloat(), theme, density, bottomNavInsetPx)
            }
            invalidate()
        }
    }

    fun updateSpaceLabel(label: String) {
        if (layout.spaceLabel != label) {
            layout.spaceLabel = label
            if (width > 0 && height > 0) {
                val density = resources.displayMetrics.density
                layout.buildLayout(width.toFloat(), height.toFloat(), theme, density, bottomNavInsetPx)
            }
            invalidate()
        }
    }

    /**
     * Cold Surfaces Demotion (Phase 3.4): Purges transient symbol/numpad layout state and
     * popup windows on low-memory trim signals or keyboard close.
     */
    fun demoteColdSurfaces() {
        popupWindow.dismiss()
        if (layout.mode != KeyboardMode.CHARACTERS) {
            layout.mode = KeyboardMode.CHARACTERS
            if (width > 0 && height > 0) {
                val density = resources.displayMetrics.density
                layout.buildLayout(width.toFloat(), height.toFloat(), theme, density, bottomNavInsetPx)
            }
            invalidate()
        }
    }
}
