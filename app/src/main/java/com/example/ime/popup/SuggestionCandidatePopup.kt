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
import com.example.ime.keyboard.KeyData

/**
 * HeliBoard-style long-press popup for suggestion strip candidates.
 * Displays:
 * 1. Word Header with Action Button (Delete if personal dictionary, or Demote if general/user history).
 * 2. Horizontal row of similar/alternative candidate words to pick from.
 */
class SuggestionCandidatePopup(
    private val context: Context,
    private val targetWord: String,
    private val isPersonalDictionary: Boolean,
    private val similarWords: List<String>,
    private val onDeleteOrDemote: (isDelete: Boolean) -> Unit,
    private val onWordSelected: (String) -> Unit
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
            setPadding((14 * density).toInt(), (10 * density).toInt(), (14 * density).toInt(), (10 * density).toInt())
            elevation = 16f * density
        }

        // --- Row 1: Header (Word + Type Badge + Action Button) ---
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val wordInfoCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = (16 * density).toInt()
            }
        }

        val tvWord = TextView(context).apply {
            text = targetWord
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        wordInfoCol.addView(tvWord)

        val badgeLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val badgeBg = GradientDrawable().apply {
                setColor(if (isPersonalDictionary) 0x33EF4444.toInt() else 0x33F59E0B.toInt())
                cornerRadius = 4f * density
            }
            background = badgeBg
            setPadding((6 * density).toInt(), (2 * density).toInt(), (6 * density).toInt(), (2 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (2 * density).toInt()
            }
        }
        val tvBadge = TextView(context).apply {
            text = if (isPersonalDictionary) "Personal / Learned" else "Dictionary Word"
            setTextColor(if (isPersonalDictionary) 0xFFFCA5A5.toInt() else 0xFFFCD34D.toInt())
            textSize = 10f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        badgeLayout.addView(tvBadge)
        wordInfoCol.addView(badgeLayout)
        headerRow.addView(wordInfoCol)

        val btnAction = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val actionBg = GradientDrawable().apply {
                setColor(if (isPersonalDictionary) 0x26EF4444.toInt() else 0x26F59E0B.toInt())
                cornerRadius = 8f * density
                setStroke((1.5f * density).toInt(), if (isPersonalDictionary) 0xFFEF4444.toInt() else 0xFFF59E0B.toInt())
            }
            background = actionBg
            minimumHeight = (48 * density).toInt()
            setPadding((12 * density).toInt(), (6 * density).toInt(), (14 * density).toInt(), (6 * density).toInt())
            isClickable = true
            isFocusable = true

            val iv = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams((16 * density).toInt(), (16 * density).toInt()).apply {
                    marginEnd = (6 * density).toInt()
                }
                setImageResource(
                    if (isPersonalDictionary) R.drawable.ic_bin_rounded
                    else R.drawable.ic_arrow_down
                )
                setColorFilter(if (isPersonalDictionary) 0xFFEF4444.toInt() else 0xFFF59E0B.toInt())
            }
            addView(iv)

            val tv = TextView(context).apply {
                text = if (isPersonalDictionary) "Delete" else "Demote"
                setTextColor(if (isPersonalDictionary) 0xFFEF4444.toInt() else 0xFFF59E0B.toInt())
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            addView(tv)

            setOnClickListener {
                popupWindow.dismiss()
                onDeleteOrDemote(isPersonalDictionary)
            }
        }
        headerRow.addView(btnAction)
        rootLayout.addView(headerRow)

        // --- Row 2: Similar/Alternative Words (if available) ---
        val validAlts = similarWords.filter { !it.equals(targetWord, ignoreCase = true) }.take(5)
        if (validAlts.isNotEmpty()) {
            val divider = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (1f * density).toInt()
                ).apply {
                    topMargin = (8 * density).toInt()
                    bottomMargin = (8 * density).toInt()
                }
                setBackgroundColor(0xFF334155.toInt())
            }
            rootLayout.addView(divider)

            val altsLabel = TextView(context).apply {
                text = "ALTERNATIVES"
                setTextColor(0xFF64748B.toInt())
                textSize = 10f
                letterSpacing = 0.08f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, (4 * density).toInt())
            }
            rootLayout.addView(altsLabel)

            val altsRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            for (alt in validAlts) {
                val tvAlt = TextView(context).apply {
                    text = alt
                    setTextColor(0xFFCBD5E1.toInt())
                    textSize = 13f
                    val pillBg = GradientDrawable().apply {
                        setColor(0xFF1E293B.toInt())
                        cornerRadius = 8f * density
                        setStroke((1f * density).toInt(), 0xFF334155.toInt())
                    }
                    background = pillBg
                    minimumHeight = (36 * density).toInt()
                    gravity = Gravity.CENTER
                    setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
                    val lp = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginEnd = (6 * density).toInt()
                    }
                    layoutParams = lp
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        popupWindow.dismiss()
                        onWordSelected(alt)
                    }
                }
                altsRow.addView(tvAlt)
            }
            rootLayout.addView(altsRow)
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

    fun show(anchorView: View, key: KeyData) {
        rootLayout.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val popupWidth = rootLayout.measuredWidth
        val popupHeight = rootLayout.measuredHeight

        val location = IntArray(2)
        anchorView.getLocationInWindow(location)

        val density = context.resources.displayMetrics.density
        val screenWidth = context.resources.displayMetrics.widthPixels
        val desiredX = location[0] + key.bounds.centerX() - (popupWidth / 2f)
        val clampedX = desiredX.coerceIn(8f * density, screenWidth - popupWidth - (8f * density)).toInt()

        val desiredY = (location[1] + key.bounds.top - popupHeight - (8 * density)).toInt()
        val clampedY = if (desiredY < 0) {
            (location[1] + key.bounds.bottom + (8 * density)).toInt()
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
