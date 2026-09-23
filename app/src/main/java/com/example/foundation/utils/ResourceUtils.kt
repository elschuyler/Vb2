package com.example.foundation.utils

import android.content.Context
import android.util.TypedValue
import androidx.core.content.ContextCompat

/**
 * Screen density conversions and safe resource lookup utilities.
 */
object ResourceUtils {

    /**
     * Converts density-independent pixels (dp) to display pixels (px).
     */
    fun dpToPx(dp: Float, context: Context): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics
        )
    }

    /**
     * Converts display pixels (px) to density-independent pixels (dp).
     */
    fun pxToDp(px: Float, context: Context): Float {
        val density = context.resources.displayMetrics.density
        return if (density > 0f) px / density else px
    }

    /**
     * Converts scale-independent pixels (sp) to display pixels (px).
     */
    fun spToPx(sp: Float, context: Context): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            sp,
            context.resources.displayMetrics
        )
    }

    /**
     * Safely reads a dimension in pixels from resources with a fallback.
     */
    fun getDimensionPixelSize(context: Context, resId: Int, defaultPx: Int): Int {
        return try {
            if (resId != 0) context.resources.getDimensionPixelSize(resId) else defaultPx
        } catch (_: Exception) {
            defaultPx
        }
    }

    /**
     * Safely reads a string from resources with a fallback.
     */
    fun getString(context: Context, resId: Int, defaultString: String = ""): String {
        return try {
            if (resId != 0) context.getString(resId) else defaultString
        } catch (_: Exception) {
            defaultString
        }
    }

    /**
     * Safely resolves a color integer with fallback.
     */
    fun getColor(context: Context, resId: Int, defaultColor: Int): Int {
        return try {
            if (resId != 0) ContextCompat.getColor(context, resId) else defaultColor
        } catch (_: Exception) {
            defaultColor
        }
    }
}
