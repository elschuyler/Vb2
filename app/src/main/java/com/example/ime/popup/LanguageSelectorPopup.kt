package com.example.ime.popup

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.example.R
import com.example.ime.engine.TextEngineBridge.LanguageMode
import com.example.ime.keyboard.KeyData

/**
 * Floating Language Selector Popup shown on spacebar long-press.
 * Allows switching between English, Français, and Bilingual (DUAL) modes.
 * When in single-language mode, provides an instant quick-switch button back to Bilingual Mode.
 */
class LanguageSelectorPopup(
    private val context: Context,
    private val currentMode: LanguageMode,
    private val onModeSelected: (LanguageMode) -> Unit
) {

    private val popupWindow: PopupWindow
    private val rootLayout: LinearLayout

    init {
        val density = context.resources.displayMetrics.density
        rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                setColor(0xFF0F172A.toInt()) // Modern deep slate
                cornerRadius = 14f * density
                setStroke((1.5f * density).toInt(), 0xFF334155.toInt())
            }
            background = bg
            setPadding((14 * density).toInt(), (12 * density).toInt(), (14 * density).toInt(), (12 * density).toInt())
            elevation = 16f * density
        }

        // --- Header ---
        val tvHeader = TextView(context).apply {
            text = "SELECT LANGUAGE"
            setTextColor(0xFF64748B.toInt())
            textSize = 10f
            letterSpacing = 0.08f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding((4 * density).toInt(), 0, 0, (8 * density).toInt())
        }
        rootLayout.addView(tvHeader)

        // --- Quick Switch to Bilingual Button (if currently in single-language mode) ---
        if (currentMode != LanguageMode.DUAL) {
            val quickBilingualBtn = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val btnBg = GradientDrawable().apply {
                    setColor(0x3338BDF8.toInt()) // Soft sky blue
                    cornerRadius = 8f * density
                    setStroke((1.5f * density).toInt(), 0xFF38BDF8.toInt())
                }
                background = btnBg
                minimumHeight = (44 * density).toInt()
                setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (8 * density).toInt()
                }
                isClickable = true
                isFocusable = true

                val tvIcon = TextView(context).apply {
                    text = "⚡"
                    textSize = 14f
                    setPadding(0, 0, (8 * density).toInt(), 0)
                }
                addView(tvIcon)

                val tvQuick = TextView(context).apply {
                    text = "Quick Switch to Bilingual (EN • FR)"
                    setTextColor(0xFF38BDF8.toInt())
                    textSize = 13f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }
                addView(tvQuick)

                setOnClickListener {
                    popupWindow.dismiss()
                    onModeSelected(LanguageMode.DUAL)
                }
            }
            rootLayout.addView(quickBilingualBtn)
        }

        // --- Language Options ---
        val modes = listOf(
            LanguageMode.ENGLISH to "English (US)",
            LanguageMode.FRENCH to "Français (France)",
            LanguageMode.DUAL to "Bilingual Mode (EN • FR)"
        )

        for ((mode, label) in modes) {
            val isSelected = (mode == currentMode)

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val itemBg = GradientDrawable().apply {
                    if (isSelected) {
                        setColor(0x2638BDF8.toInt())
                        cornerRadius = 8f * density
                        setStroke((1f * density).toInt(), 0xFF38BDF8.toInt())
                    } else {
                        setColor(0xFF1E293B.toInt())
                        cornerRadius = 8f * density
                        setStroke((1f * density).toInt(), 0xFF334155.toInt())
                    }
                }
                background = itemBg
                minimumHeight = (48 * density).toInt()
                setPadding((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), (6 * density).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (6 * density).toInt()
                }
                isClickable = true
                isFocusable = true

                // Indicator Badge (EN / FR / DUAL)
                val badge = TextView(context).apply {
                    text = mode.indicator
                    setTextColor(if (isSelected) 0xFF38BDF8.toInt() else 0xFF94A3B8.toInt())
                    textSize = 11f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    val badgeBg = GradientDrawable().apply {
                        setColor(if (isSelected) 0x3338BDF8.toInt() else 0xFF0F172A.toInt())
                        cornerRadius = 4f * density
                    }
                    background = badgeBg
                    setPadding((6 * density).toInt(), (2 * density).toInt(), (6 * density).toInt(), (2 * density).toInt())
                }
                addView(badge)

                // Label
                val tvText = TextView(context).apply {
                    text = label
                    setTextColor(if (isSelected) Color.WHITE else 0xFFCBD5E1.toInt())
                    textSize = 13f
                    setTypeface(typeface, if (isSelected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                    setPadding((10 * density).toInt(), 0, 0, 0)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                addView(tvText)

                // Checkmark if selected
                if (isSelected) {
                    val checkmark = TextView(context).apply {
                        text = "✓"
                        setTextColor(0xFF38BDF8.toInt())
                        textSize = 14f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setPadding((8 * density).toInt(), 0, 0, 0)
                    }
                    addView(checkmark)
                }

                setOnClickListener {
                    popupWindow.dismiss()
                    onModeSelected(mode)
                }
            }
            rootLayout.addView(row)
        }

        popupWindow = PopupWindow(
            rootLayout,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            animationStyle = android.R.style.Animation_Dialog
            elevation = 16f * density
        }
    }

    fun show(anchorView: View, spaceKey: KeyData) {
        rootLayout.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val popupWidth = rootLayout.measuredWidth
        val popupHeight = rootLayout.measuredHeight

        val location = IntArray(2)
        anchorView.getLocationInWindow(location)

        val density = context.resources.displayMetrics.density
        val screenWidth = context.resources.displayMetrics.widthPixels
        val desiredX = location[0] + spaceKey.bounds.centerX() - (popupWidth / 2f)
        val clampedX = desiredX.coerceIn(12f * density, screenWidth - popupWidth - (12f * density)).toInt()

        val desiredY = (location[1] + spaceKey.bounds.top - popupHeight - (10 * density)).toInt()
        val clampedY = if (desiredY < 0) {
            (location[1] + spaceKey.bounds.bottom + (10 * density)).toInt()
        } else {
            desiredY
        }

        try {
            if (anchorView.isAttachedToWindow && anchorView.windowToken != null) {
                popupWindow.showAtLocation(anchorView, Gravity.NO_GRAVITY, clampedX, clampedY)
            }
        } catch (_: Exception) {}
    }
}
