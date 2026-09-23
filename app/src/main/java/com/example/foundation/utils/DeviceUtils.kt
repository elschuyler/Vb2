package com.example.foundation.utils

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager

/**
 * Device configuration, screen measurement, and hardware capabilities inspection.
 */
object DeviceUtils {

    /**
     * Determines whether the current device is a tablet based on screen width >= 600dp.
     */
    fun isTablet(context: Context): Boolean {
        val config = context.resources.configuration
        return config.smallestScreenWidthDp >= 600
    }

    /**
     * Determines whether the device is currently in landscape orientation.
     */
    fun isLandscape(context: Context): Boolean {
        return context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    /**
     * Checks if a hardware keyboard is currently attached and active.
     */
    fun isHardwareKeyboardConnected(context: Context): Boolean {
        val config = context.resources.configuration
        return config.keyboard != Configuration.KEYBOARD_NOKEYS &&
                config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
    }

    /**
     * Checks if the device has an operable vibrator for haptic feedback.
     */
    @Suppress("DEPRECATION")
    fun hasVibrator(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.hasVibrator() == true
            } else {
                val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vib?.hasVibrator() == true
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Returns screen width and height in pixels as a Pair(width, height).
     */
    fun getScreenDimensions(context: Context): Pair<Int, Int> {
        val dm = context.resources.displayMetrics
        return Pair(dm.widthPixels, dm.heightPixels)
    }

    /**
     * Returns the maximum screen refresh rate supported by the display (e.g., 60Hz, 90Hz, 120Hz).
     */
    fun getDisplayRefreshRate(context: Context): Float {
        return try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.display?.refreshRate ?: 60f
            } else {
                @Suppress("DEPRECATION")
                wm?.defaultDisplay?.refreshRate ?: 60f
            }
        } catch (_: Exception) {
            60f
        }
    }
}
