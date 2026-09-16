// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.keyboard

import android.graphics.Typeface
import android.widget.TextView
import androidx.compose.ui.text.font.FontFamily

object KeyboardTypeface {
    @JvmStatic
    fun customTypeface(): Typeface? = null

    @JvmStatic
    fun emojiTypeface(): Typeface? = null

    @JvmStatic
    fun customFontFamily(): FontFamily? = null

    @JvmStatic
    fun resolve(
        text: CharSequence?,
        defaultTypeface: Typeface = Typeface.DEFAULT,
    ): Typeface = defaultTypeface

    @JvmStatic
    fun applyToTextView(textView: TextView) {
        applyToTextView(textView, textView.text, Typeface.DEFAULT)
    }

    @JvmStatic
    fun applyToTextView(textView: TextView, text: CharSequence?, defaultTypeface: Typeface) {
        textView.typeface = defaultTypeface
    }

    @JvmStatic
    fun clearCache() {
    }
}
