// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.settings.Settings

object TempIncognitoManager {
    private val handler = Handler(Looper.getMainLooper())
    private var isTempActive = false

    val isActive: Boolean
        get() = isTempActive

    private val expireRunnable = Runnable {
        expire()
    }

    @JvmStatic
    fun startTempIncognito(latinIME: LatinIME, durationMs: Long = 120_000L) {
        handler.removeCallbacks(expireRunnable)
        isTempActive = true
        val settings = Settings.getInstance()
        if (!settings.isAlwaysIncognitoMode) {
            settings.setAlwaysIncognitoMode(true)
        }
        BackgroundGatheringCache.clear()
        latinIME.setGestureDataGatheringMode(latinIME.currentInputEditorInfo, false)
        handler.postDelayed(expireRunnable, durationMs)
        Toast.makeText(latinIME, "Incognito active for 2 minutes", Toast.LENGTH_SHORT).show()
    }

    private fun expire() {
        if (!isTempActive) return
        isTempActive = false
        val settings = Settings.getInstance()
        settings?.setAlwaysIncognitoMode(false)
        val latinIME = KeyboardSwitcher.getInstance()?.latinIME
        if (latinIME != null) {
            BackgroundGatheringCache.clear()
            latinIME.setGestureDataGatheringMode(latinIME.currentInputEditorInfo, false)
            Toast.makeText(latinIME, "Temporary incognito ended", Toast.LENGTH_SHORT).show()
        }
    }

    @JvmStatic
    fun onKeyboardClosed() {
        if (isTempActive) {
            handler.removeCallbacks(expireRunnable)
            isTempActive = false
            Settings.getInstance()?.setAlwaysIncognitoMode(false)
            val latinIME = KeyboardSwitcher.getInstance()?.latinIME
            if (latinIME != null) {
                BackgroundGatheringCache.clear()
                latinIME.setGestureDataGatheringMode(latinIME.currentInputEditorInfo, false)
            }
        }
    }
}
