package com.example.ime.modal

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import helium314.keyboard.latin.R
import com.example.ime.keyboard.KeyboardTheme

/**
 * Unified 4-Button Bottom Bar Component exclusively for Clipboard and Emoji modals.
 * Layout: [ ABC ] [ Space ] [ ⌫ Backspace ] [ ↵ Enter ]
 * Built with authentic HeliBoard tactile keycaps (10dp corner radius, 1dp dark bottom bevel,
 * clean unbordered spacebar, and return icon with corner emoji hint).
 */
class ModalBottomBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onAbcClick: (() -> Unit)? = null
    var onSpaceClick: (() -> Unit)? = null
    var onDeleteClick: (() -> Unit)? = null
    var onEnterClick: (() -> Unit)? = null

    private var theme = KeyboardTheme.loadFromPrefs(context)

    // Button bounding rectangles
    private val abcRect = RectF()
    private val spaceRect = RectF()
    private val deleteRect = RectF()
    private val enterRect = RectF()
    private val tempRectF = RectF()

    // Paints
    private val backgroundPaint = Paint()
    private val keyBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val actionKeyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val enterKeyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val keyBevelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val actionKeyBevelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val enterKeyBevelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        typeface = Typeface.DEFAULT
    }

    private var deleteIcon: Drawable? = null
    private var enterIcon: Drawable? = null

    private var pressedIndex = -1 // 0: ABC, 1: Space, 2: Delete, 3: Enter
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isRepeatingDelete = false

    private val repeatDeleteRunnable = object : Runnable {
        override fun run() {
            if (pressedIndex == 2) {
                isRepeatingDelete = true
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onDeleteClick?.invoke()
                mainHandler.postDelayed(this, 50)
            }
        }
    }

    init {
        updatePaints()
        loadIcons()
    }

    fun reloadTheme() {
        theme = KeyboardTheme.loadFromPrefs(context)
        updatePaints()
        loadIcons()
        requestLayout()
        invalidate()
    }

    private fun updatePaints() {
        val density = resources.displayMetrics.density
        backgroundPaint.color = theme.backgroundColor
        keyBgPaint.color = theme.keyBackgroundColor
        actionKeyPaint.color = theme.actionKeyColor
        enterKeyPaint.color = theme.enterKeyColor

        // HeliBoard Rounded Base Border bevel colors
        keyBevelPaint.color = theme.keyBottomBevelColor
        actionKeyBevelPaint.color = theme.actionKeyBevelColor
        enterKeyBevelPaint.color = 0xFF263238.toInt()
        pressedPaint.color = theme.pressedKeyColor

        textPaint.color = theme.textColor
        textPaint.textSize = 14f * density

        hintPaint.color = theme.enterTextColor
        hintPaint.textSize = 11f * density
    }

    private fun loadIcons() {
        deleteIcon = ContextCompat.getDrawable(context, R.drawable.sym_keyboard_delete_rounded)?.mutate()
        deleteIcon?.setTint(theme.textColor)

        enterIcon = ContextCompat.getDrawable(context, R.drawable.sym_keyboard_return_rounded)?.mutate()
        enterIcon?.setTint(theme.enterTextColor)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val desiredHeight = (theme.keyHeightDp * density + theme.verticalGapDp * density).toInt()
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, resolveSize(desiredHeight, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeButtonRects(w.toFloat(), h.toFloat())
    }

    private fun computeButtonRects(w: Float, h: Float) {
        val density = resources.displayMetrics.density
        val horizGap = theme.horizontalGapDp * density
        val vertGap = theme.verticalGapDp * density / 2f
        val sidePadding = horizGap

        val availableW = w - sidePadding * 2f
        val keyHeight = h - vertGap * 2f
        val topY = vertGap
        val botY = topY + keyHeight

        // Proportions matching HeliBoard bottom row weights:
        // ABC: 1.4f, Space: 4.6f, Delete: 1.4f, Enter: 1.6f -> Total = 9.0f
        val totalWeight = 9.0f
        val totalGaps = horizGap * 3f
        val widthForKeys = availableW - totalGaps

        val abcW = (1.4f / totalWeight) * widthForKeys
        val spaceW = (4.6f / totalWeight) * widthForKeys
        val deleteW = (1.4f / totalWeight) * widthForKeys
        val enterW = (1.6f / totalWeight) * widthForKeys

        // 1. [ ABC ]
        var curX = sidePadding
        abcRect.set(curX, topY, curX + abcW, botY)
        curX += abcW + horizGap

        // 2. [ Space ]
        spaceRect.set(curX, topY, curX + spaceW, botY)
        curX += spaceW + horizGap

        // 3. [ ⌫ Backspace ]
        deleteRect.set(curX, topY, curX + deleteW, botY)
        curX += deleteW + horizGap

        // 4. [ ↵ Enter ]
        enterRect.set(curX, topY, curX + enterW, botY)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        val density = resources.displayMetrics.density
        val cornerRadius = theme.keyCornerRadiusDp * density

        // 1. Render [ ABC ] keycap
        drawKeycap(canvas, abcRect, actionKeyPaint, actionKeyBevelPaint, cornerRadius, pressedIndex == 0)
        val abcBaseline = abcRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText("ABC", abcRect.centerX(), abcBaseline, textPaint)

        // 2. Render [ Space ] clean unbordered keycap (No text, no stroke outline)
        drawKeycap(canvas, spaceRect, keyBgPaint, keyBevelPaint, cornerRadius, pressedIndex == 1)

        // 3. Render [ ⌫ Backspace ] keycap
        drawKeycap(canvas, deleteRect, actionKeyPaint, actionKeyBevelPaint, cornerRadius, pressedIndex == 2)
        deleteIcon?.let { icon ->
            val iconSize = (22f * density).toInt()
            val left = (deleteRect.centerX() - iconSize / 2f).toInt()
            val top = (deleteRect.centerY() - iconSize / 2f).toInt()
            icon.setBounds(left, top, left + iconSize, top + iconSize)
            icon.draw(canvas)
        }

        // 4. Render [ ↵ Enter ] keycap with smiley corner hint
        drawKeycap(canvas, enterRect, enterKeyPaint, enterKeyBevelPaint, cornerRadius, pressedIndex == 3)
        enterIcon?.let { icon ->
            val iconSize = (22f * density).toInt()
            val left = (enterRect.centerX() - iconSize / 2f).toInt()
            val top = (enterRect.centerY() - iconSize / 2f).toInt()
            icon.setBounds(left, top, left + iconSize, top + iconSize)
            icon.draw(canvas)
        }
        if (theme.showHints) {
            val hintX = enterRect.right - (4f * density)
            val hintY = enterRect.top + (12f * density)
            canvas.drawText("☺", hintX, hintY, hintPaint)
        }
    }

    /**
     * Tactile layered keycap rendering matching VianKeyboardView and HeliBoard:
     * 1. 1dp bottom bevel layer
     * 2. Top keycap surface inset at bottom by 1dp
     */
    private fun drawKeycap(
        canvas: Canvas,
        bounds: RectF,
        surfacePaint: Paint,
        bevelPaint: Paint,
        cornerRadius: Float,
        isPressed: Boolean
    ) {
        val density = resources.displayMetrics.density
        val bevelInsetBottomPx = 1.0f * density

        if (isPressed) {
            canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, pressedPaint)
        } else {
            // Bottom bevel layer
            canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, bevelPaint)

            // Top keycap surface inset at bottom by 1dp
            tempRectF.set(
                bounds.left,
                bounds.top,
                bounds.right,
                bounds.bottom - bevelInsetBottomPx
            )
            canvas.drawRoundRect(tempRectF, cornerRadius, cornerRadius, surfacePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedIndex = findButtonIndex(x, y)
                if (pressedIndex != -1) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (pressedIndex == 2) {
                        isRepeatingDelete = false
                        mainHandler.postDelayed(repeatDeleteRunnable, 400)
                    }
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val newIndex = findButtonIndex(x, y)
                if (newIndex != pressedIndex) {
                    mainHandler.removeCallbacks(repeatDeleteRunnable)
                    pressedIndex = newIndex
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                mainHandler.removeCallbacks(repeatDeleteRunnable)
                val targetIndex = pressedIndex
                pressedIndex = -1
                invalidate()

                if (targetIndex != -1) {
                    when (targetIndex) {
                        0 -> onAbcClick?.invoke()
                        1 -> onSpaceClick?.invoke()
                        2 -> {
                            if (!isRepeatingDelete) {
                                onDeleteClick?.invoke()
                            }
                            isRepeatingDelete = false
                        }
                        3 -> onEnterClick?.invoke()
                    }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(repeatDeleteRunnable)
                pressedIndex = -1
                isRepeatingDelete = false
                invalidate()
            }
        }
        return super.onTouchEvent(event)
    }

    private fun findButtonIndex(x: Float, y: Float): Int {
        return when {
            abcRect.contains(x, y) -> 0
            spaceRect.contains(x, y) -> 1
            deleteRect.contains(x, y) -> 2
            enterRect.contains(x, y) -> 3
            else -> -1
        }
    }
}
