package com.example.ime.security

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.example.ime.keyboard.KeyboardTheme
import kotlin.math.hypot

class VianPatternUnlockView(
    context: Context,
    private val vaultType: VaultType = VaultType.SECURITY
) : View(context) {

    var onUnlockSuccess: ((VaultType) -> Unit)? = null
    var onDismissToAlpha: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private var presentationMode = MasterPatternStore.getPresentationMode(context)

    // Colors & Paints
    private val theme = KeyboardTheme()
    private val bgPaint = Paint().apply {
        color = theme.backgroundColor
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.textColor
        textSize = 14f * density
        textAlign = Paint.Align.LEFT
    }
    private val dotNormalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF64748B.toInt() // Slate 500
        style = Paint.Style.FILL
    }
    private val dotSelectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0284C7.toInt() // Sky 600
        style = Paint.Style.FILL
    }
    private val dotSuccessPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF10B981.toInt() // Emerald 500
        style = Paint.Style.FILL
    }
    private val dotErrorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFEF4444.toInt() // Red 500
        style = Paint.Style.FILL
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0284C7.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x330284C7
        style = Paint.Style.FILL
    }

    // Key disguise paints
    private val keycapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.keyBackgroundColor
        style = Paint.Style.FILL
    }
    private val keycapActionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.actionKeyColor
        style = Paint.Style.FILL
    }
    private val keyBevelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.keyBottomBevelColor
        style = Paint.Style.FILL
    }
    private val keyTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.textColor
        textSize = 18f * density
        textAlign = Paint.Align.CENTER
    }
    private val keyActionTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.textColor
        textSize = 12f * density
        textAlign = Paint.Align.CENTER
    }

    // Top action bar
    private val topBarHeightPx = 42f * density
    private val closeButtonRect = RectF()
    private val modeToggleRect = RectF()

    // 3x3 Dot Matrix state
    private val dotCenters = Array(9) { floatArrayOf(0f, 0f) }
    private val selectedDots = mutableListOf<Int>()
    private var currentTouchX = 0f
    private var currentTouchY = 0f
    private var isTouching = false
    private var unlockState: UnlockState = UnlockState.IDLE

    // Disguise Mode state
    private data class DisguiseKey(
        val label: String,
        val isAction: Boolean,
        val bounds: RectF
    )
    private val disguiseKeys = mutableListOf<DisguiseKey>()
    private val selectedDisguiseSequence = mutableListOf<String>()
    private var lastTouchedKeyLabel: String? = null

    private enum class UnlockState {
        IDLE,
        TOUCHING,
        SUCCESS,
        ERROR
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeLayout(w, h)
    }

    private fun computeLayout(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return

        // 1. Top Bar Action Rects (Minimum 48dp touch targets)
        val closeBtnWidth = 48f * density
        val closeBtnHeight = topBarHeightPx
        closeButtonRect.set(w - closeBtnWidth, 0f, w.toFloat(), closeBtnHeight)

        val toggleWidth = 72f * density
        modeToggleRect.set(w - closeBtnWidth - toggleWidth - (8f * density), 0f, w - closeBtnWidth - (8f * density), closeBtnHeight)

        // 2. 3x3 Grid Dots Coordinates
        val gridTop = topBarHeightPx + (8f * density)
        val gridBottom = h - (12f * density)
        val gridHeight = gridBottom - gridTop
        val gridWidth = w.toFloat()

        val colPositions = floatArrayOf(gridWidth * 0.22f, gridWidth * 0.50f, gridWidth * 0.78f)
        val rowPositions = floatArrayOf(
            gridTop + gridHeight * 0.20f,
            gridTop + gridHeight * 0.50f,
            gridTop + gridHeight * 0.80f
        )

        var idx = 0
        for (r in 0..2) {
            for (c in 0..2) {
                dotCenters[idx][0] = colPositions[c]
                dotCenters[idx][1] = rowPositions[r]
                idx++
            }
        }

        // 3. Disguise Mode Keyboard Keys Layout
        disguiseKeys.clear()
        val kbTop = topBarHeightPx
        val availableKbHeight = h - kbTop
        val rowHeight = availableKbHeight / 4f
        val gapH = 4f * density
        val gapV = 3.5f * density

        // Row 1: Q W E R T Y U I O P (10 keys)
        val r1Chars = listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P")
        val r1KeyWidth = (w - (gapH * 11)) / 10f
        for (i in r1Chars.indices) {
            val left = gapH + i * (r1KeyWidth + gapH)
            val top = kbTop + (gapV / 2f)
            val rect = RectF(left, top, left + r1KeyWidth, top + rowHeight - gapV)
            disguiseKeys.add(DisguiseKey(r1Chars[i], false, rect))
        }

        // Row 2: A S D F G H J K L (9 keys, inset)
        val r2Chars = listOf("A", "S", "D", "F", "G", "H", "J", "K", "L")
        val r2SideMargin = (r1KeyWidth / 2f) + gapH
        val r2KeyWidth = (w - (r2SideMargin * 2) - (gapH * 8)) / 9f
        for (i in r2Chars.indices) {
            val left = r2SideMargin + i * (r2KeyWidth + gapH)
            val top = kbTop + rowHeight + (gapV / 2f)
            val rect = RectF(left, top, left + r2KeyWidth, top + rowHeight - gapV)
            disguiseKeys.add(DisguiseKey(r2Chars[i], false, rect))
        }

        // Row 3: Shift, Z X C V B N M, Del
        val r3Chars = listOf("Z", "X", "C", "V", "B", "N", "M")
        val functionalWidth = r1KeyWidth * 1.35f
        val r3RemainingWidth = w - (functionalWidth * 2) - (gapH * 8)
        val r3KeyWidth = r3RemainingWidth / 7f

        // Shift
        val shiftRect = RectF(gapH, kbTop + (rowHeight * 2) + (gapV / 2f), gapH + functionalWidth, kbTop + (rowHeight * 3) - (gapV / 2f))
        disguiseKeys.add(DisguiseKey("⇧", true, shiftRect))

        for (i in r3Chars.indices) {
            val left = gapH + functionalWidth + gapH + i * (r3KeyWidth + gapH)
            val top = kbTop + (rowHeight * 2) + (gapV / 2f)
            val rect = RectF(left, top, left + r3KeyWidth, top + rowHeight - gapV)
            disguiseKeys.add(DisguiseKey(r3Chars[i], false, rect))
        }

        // Del
        val delLeft = w - gapH - functionalWidth
        val delRect = RectF(delLeft, kbTop + (rowHeight * 2) + (gapV / 2f), delLeft + functionalWidth, kbTop + (rowHeight * 3) - (gapV / 2f))
        disguiseKeys.add(DisguiseKey("⌫", true, delRect))

        // Row 4: ?123, comma, space, period, enter
        val r4Top = kbTop + (rowHeight * 3) + (gapV / 2f)
        val r4Bottom = h - (gapV / 2f)
        val symWidth = functionalWidth
        val enterWidth = functionalWidth * 1.15f
        val smallKeyWidth = r1KeyWidth

        // ?123
        disguiseKeys.add(DisguiseKey("?123", true, RectF(gapH, r4Top, gapH + symWidth, r4Bottom)))
        // comma
        val commaLeft = gapH + symWidth + gapH
        disguiseKeys.add(DisguiseKey(",", false, RectF(commaLeft, r4Top, commaLeft + smallKeyWidth, r4Bottom)))
        // enter
        val enterLeft = w - gapH - enterWidth
        disguiseKeys.add(DisguiseKey("↵", true, RectF(enterLeft, r4Top, enterLeft + enterWidth, r4Bottom)))
        // period
        val periodLeft = enterLeft - gapH - smallKeyWidth
        disguiseKeys.add(DisguiseKey(".", false, RectF(periodLeft, r4Top, periodLeft + smallKeyWidth, r4Bottom)))
        // space
        val spaceLeft = commaLeft + smallKeyWidth + gapH
        val spaceRight = periodLeft - gapH
        disguiseKeys.add(DisguiseKey("Space", false, RectF(spaceLeft, r4Top, spaceRight, r4Bottom)))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        if (presentationMode == PatternPresentationMode.KEYBOARD_DISGUISE) {
            drawKeyboardDisguise(canvas)
        } else {
            drawStandardGrid(canvas)
        }
    }

    private fun drawStandardGrid(canvas: Canvas) {
        // 1. Top Header Bar
        val title = if (vaultType == VaultType.SECURITY) "🔒 Security Vault" else "🛡️ Privacy Vault"
        textPaint.color = theme.textColor
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(title, 16f * density, topBarHeightPx * 0.65f, textPaint)

        // Mode switch pill: [⌨ Disguise]
        val toggleBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.actionKeyColor
            style = Paint.Style.FILL
        }
        val toggleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.textColor
            textSize = 11f * density
            textAlign = Paint.Align.CENTER
        }
        canvas.drawRoundRect(modeToggleRect, 6f * density, 6f * density, toggleBgPaint)
        canvas.drawText("⌨ Disguise", modeToggleRect.centerX(), modeToggleRect.centerY() + (4f * density), toggleTextPaint)

        // Close button: [✕]
        val closeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.textColor
            textSize = 18f * density
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("✕", closeButtonRect.centerX(), closeButtonRect.centerY() + (6f * density), closeTextPaint)

        // 2. Connecting Lines
        if (selectedDots.isNotEmpty()) {
            val curLinePaint = when (unlockState) {
                UnlockState.SUCCESS -> dotSuccessPaint
                UnlockState.ERROR -> dotErrorPaint
                else -> linePaint
            }

            val path = Path()
            val first = dotCenters[selectedDots[0]]
            path.moveTo(first[0], first[1])

            for (i in 1 until selectedDots.size) {
                val pt = dotCenters[selectedDots[i]]
                path.lineTo(pt[0], pt[1])
            }

            if (isTouching && unlockState == UnlockState.TOUCHING) {
                path.lineTo(currentTouchX, currentTouchY)
            }

            canvas.drawPath(path, curLinePaint)
        }

        // 3. Dots
        val dotRadius = 9f * density
        val haloRadius = 24f * density

        for (i in 0 until 9) {
            val cx = dotCenters[i][0]
            val cy = dotCenters[i][1]

            val isSelected = selectedDots.contains(i)
            if (isSelected) {
                val curHaloPaint = when (unlockState) {
                    UnlockState.SUCCESS -> Paint(haloPaint).apply { color = 0x3310B981 }
                    UnlockState.ERROR -> Paint(haloPaint).apply { color = 0x33EF4444.toInt() }
                    else -> haloPaint
                }
                canvas.drawCircle(cx, cy, haloRadius, curHaloPaint)

                val curDotPaint = when (unlockState) {
                    UnlockState.SUCCESS -> dotSuccessPaint
                    UnlockState.ERROR -> dotErrorPaint
                    else -> dotSelectedPaint
                }
                canvas.drawCircle(cx, cy, dotRadius * 1.3f, curDotPaint)
            } else {
                canvas.drawCircle(cx, cy, dotRadius, dotNormalPaint)
            }
        }
    }

    private fun drawKeyboardDisguise(canvas: Canvas) {
        // 1. Top Decoy Bar
        // Subtle toggle button on left, subtle decoy icon (clipboard / exit) on right
        val toggleBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.actionKeyColor
            style = Paint.Style.FILL
        }
        val toggleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.hintColor
            textSize = 11f * density
            textAlign = Paint.Align.CENTER
        }
        canvas.drawRoundRect(modeToggleRect, 6f * density, 6f * density, toggleBgPaint)
        canvas.drawText("☷ Grid Mode", modeToggleRect.centerX(), modeToggleRect.centerY() + (4f * density), toggleTextPaint)

        // Decoy exit icon on right (looks like language/globe or subtle close)
        val decoyTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.hintColor
            textSize = 16f * density
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("📋", closeButtonRect.centerX(), closeButtonRect.centerY() + (5f * density), decoyTextPaint)

        // 2. Draw Disguise Keys (Exact look of standard keyboard)
        val keyRadius = 8f * density
        val bevelInset = 1f * density

        for (k in disguiseKeys) {
            val bg = if (k.isAction) keycapActionPaint else keycapPaint

            // Bottom bevel
            canvas.drawRoundRect(k.bounds, keyRadius, keyRadius, keyBevelPaint)

            // Surface
            val surface = RectF(k.bounds.left, k.bounds.top, k.bounds.right, k.bounds.bottom - bevelInset)
            canvas.drawRoundRect(surface, keyRadius, keyRadius, bg)

            // Label
            val tp = if (k.isAction) keyActionTextPaint else keyTextPaint
            val textY = surface.centerY() + (tp.textSize / 3f)
            canvas.drawText(k.label, surface.centerX(), textY, tp)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Check if tapped Close button
                if (closeButtonRect.contains(x, y)) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onDismissToAlpha?.invoke()
                    return true
                }

                // Check if tapped Mode Toggle
                if (modeToggleRect.contains(x, y)) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    presentationMode = if (presentationMode == PatternPresentationMode.STANDARD_GRID) {
                        PatternPresentationMode.KEYBOARD_DISGUISE
                    } else {
                        PatternPresentationMode.STANDARD_GRID
                    }
                    MasterPatternStore.setPresentationMode(context, presentationMode)
                    resetState()
                    invalidate()
                    return true
                }

                if (presentationMode == PatternPresentationMode.KEYBOARD_DISGUISE) {
                    handleDisguiseTouch(x, y, isDown = true)
                } else {
                    handleGridTouchDown(x, y)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (presentationMode == PatternPresentationMode.KEYBOARD_DISGUISE) {
                    handleDisguiseTouch(x, y, isDown = false)
                } else {
                    handleGridTouchMove(x, y)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (presentationMode == PatternPresentationMode.KEYBOARD_DISGUISE) {
                    handleDisguiseRelease()
                } else {
                    handleGridTouchRelease()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // --- Standard Grid Logic ---
    private fun handleGridTouchDown(x: Float, y: Float) {
        if (unlockState != UnlockState.IDLE) return
        resetState()
        isTouching = true
        currentTouchX = x
        currentTouchY = y

        val dotIdx = findDotNear(x, y)
        if (dotIdx != null) {
            selectedDots.add(dotIdx)
            unlockState = UnlockState.TOUCHING
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        invalidate()
    }

    private fun handleGridTouchMove(x: Float, y: Float) {
        if (!isTouching) return
        currentTouchX = x
        currentTouchY = y

        val dotIdx = findDotNear(x, y)
        if (dotIdx != null && !selectedDots.contains(dotIdx)) {
            // Check for intermediate dot jumping
            if (selectedDots.isNotEmpty()) {
                val lastIdx = selectedDots.last()
                val intermediate = getIntermediateDot(lastIdx, dotIdx)
                if (intermediate != null && !selectedDots.contains(intermediate)) {
                    selectedDots.add(intermediate)
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
            }

            selectedDots.add(dotIdx)
            unlockState = UnlockState.TOUCHING
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        invalidate()
    }

    private fun handleGridTouchRelease() {
        if (!isTouching) return
        isTouching = false

        if (selectedDots.size < 3) {
            resetState()
            invalidate()
            return
        }

        // Verify pattern with MasterPatternStore
        val isVerified = MasterPatternStore.verifyPattern(context, selectedDots, vaultType)
        if (isVerified) {
            unlockState = UnlockState.SUCCESS
            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            invalidate()
            postDelayed({
                onUnlockSuccess?.invoke(vaultType)
            }, 180L)
        } else {
            unlockState = UnlockState.ERROR
            performHapticFeedback(HapticFeedbackConstants.REJECT)
            invalidate()
            postDelayed({
                resetState()
                invalidate()
            }, 400L)
        }
    }

    private fun findDotNear(x: Float, y: Float): Int? {
        val touchThreshold = 36f * density
        for (i in 0 until 9) {
            val dist = hypot(x - dotCenters[i][0], y - dotCenters[i][1])
            if (dist <= touchThreshold) {
                return i
            }
        }
        return null
    }

    private fun getIntermediateDot(from: Int, to: Int): Int? {
        val r1 = from / 3; val c1 = from % 3
        val r2 = to / 3; val c2 = to % 3

        if ((r1 == r2 && kotlin.math.abs(c1 - c2) == 2) ||
            (c1 == c2 && kotlin.math.abs(r1 - r2) == 2) ||
            (kotlin.math.abs(r1 - r2) == 2 && kotlin.math.abs(c1 - c2) == 2)
        ) {
            val midR = (r1 + r2) / 2
            val midC = (c1 + c2) / 2
            return midR * 3 + midC
        }
        return null
    }

    // --- Keyboard Disguise Logic ---
    private fun handleDisguiseTouch(x: Float, y: Float, isDown: Boolean) {
        if (isDown) {
            selectedDisguiseSequence.clear()
            lastTouchedKeyLabel = null
        }

        val key = disguiseKeys.find { it.bounds.contains(x, y) }
        if (key != null && key.label != lastTouchedKeyLabel) {
            lastTouchedKeyLabel = key.label
            selectedDisguiseSequence.add(key.label)
            // Tactical vibration pulse on key boundary crossing
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    private fun handleDisguiseRelease() {
        if (selectedDisguiseSequence.isEmpty()) return

        val isVerified = MasterPatternStore.verifyDisguiseSequence(context, selectedDisguiseSequence, vaultType)
        if (isVerified) {
            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            postDelayed({
                onUnlockSuccess?.invoke(vaultType)
            }, 100L)
        } else {
            performHapticFeedback(HapticFeedbackConstants.REJECT)
            selectedDisguiseSequence.clear()
            lastTouchedKeyLabel = null
        }
    }

    private fun resetState() {
        selectedDots.clear()
        selectedDisguiseSequence.clear()
        lastTouchedKeyLabel = null
        unlockState = UnlockState.IDLE
        isTouching = false
    }
}
