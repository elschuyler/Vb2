package com.android.inputmethod.keyboard

import com.android.inputmethod.latin.BinaryDictionary
import com.example.logger.LogKeeper
import com.example.logger.LogTags

/**
 * JNI wrapper for ProximityInfo in libjni_latinime.so.
 */
class ProximityInfo(
    displayWidth: Int,
    displayHeight: Int,
    gridWidth: Int,
    gridHeight: Int,
    mostCommonKeyWidth: Int,
    mostCommonKeyHeight: Int,
    proximityChars: IntArray,
    keyCount: Int,
    keyXCoordinates: IntArray,
    keyYCoordinates: IntArray,
    keyWidths: IntArray,
    keyHeights: IntArray,
    keyCharCodes: IntArray,
    sweetSpotCenterXs: FloatArray,
    sweetSpotCenterYs: FloatArray,
    sweetSpotRadii: FloatArray
) {
    var nativeProximityInfo: Long = 0L
        private set

    val isValid: Boolean
        get() = nativeProximityInfo != 0L

    init {
        BinaryDictionary.loadNativeLibraryIfNeeded()
        if (BinaryDictionary.sNativeLoaded) {
            try {
                nativeProximityInfo = setProximityInfoNative(
                    displayWidth,
                    displayHeight,
                    gridWidth,
                    gridHeight,
                    mostCommonKeyWidth,
                    mostCommonKeyHeight,
                    proximityChars,
                    keyCount,
                    keyXCoordinates,
                    keyYCoordinates,
                    keyWidths,
                    keyHeights,
                    keyCharCodes,
                    sweetSpotCenterXs,
                    sweetSpotCenterYs,
                    sweetSpotRadii
                )
            } catch (t: Throwable) {
                LogKeeper.logError(LogTags.JNI, "PROXIMITY_INIT_ERR", "${t.message}")
                nativeProximityInfo = 0L
            }
        }
    }

    fun close() {
        if (nativeProximityInfo != 0L && BinaryDictionary.sNativeLoaded) {
            try {
                releaseProximityInfoNative(nativeProximityInfo)
            } catch (t: Throwable) {
                LogKeeper.logError(LogTags.JNI, "PROXIMITY_REL_ERR", "${t.message}")
            } finally {
                nativeProximityInfo = 0L
            }
        }
    }

    companion object {
        const val MAX_PROXIMITY_CHARS_SIZE = 16
        const val DEFAULT_GRID_WIDTH = 32
        const val DEFAULT_GRID_HEIGHT = 16

        /**
         * Builds a ProximityInfo instance from the active layout's key dimensions.
         */
        fun createFromKeys(
            keyboardWidth: Int,
            keyboardHeight: Int,
            keys: List<com.example.ime.keyboard.KeyData>
        ): ProximityInfo {
            val charKeys = keys.filter { it.code > 0 }
            val keyCount = charKeys.size.coerceAtMost(128)
            if (keyCount == 0 || keyboardWidth <= 0 || keyboardHeight <= 0) {
                return ProximityInfo(
                    1, 1, 1, 1, 1, 1,
                    IntArray(1 * 1 * MAX_PROXIMITY_CHARS_SIZE),
                    0, IntArray(0), IntArray(0), IntArray(0), IntArray(0), IntArray(0),
                    FloatArray(0), FloatArray(0), FloatArray(0)
                )
            }

            val gridWidth = DEFAULT_GRID_WIDTH
            val gridHeight = DEFAULT_GRID_HEIGHT
            val cellWidth = (keyboardWidth + gridWidth - 1) / gridWidth
            val cellHeight = (keyboardHeight + gridHeight - 1) / gridHeight

            val keyXCoordinates = IntArray(keyCount)
            val keyYCoordinates = IntArray(keyCount)
            val keyWidths = IntArray(keyCount)
            val keyHeights = IntArray(keyCount)
            val keyCharCodes = IntArray(keyCount)
            val sweetSpotCenterXs = FloatArray(keyCount)
            val sweetSpotCenterYs = FloatArray(keyCount)
            val sweetSpotRadii = FloatArray(keyCount)

            var totalWidth = 0
            var totalHeight = 0
            for (i in 0 until keyCount) {
                val key = charKeys[i]
                val kx = key.bounds.left.toInt()
                val ky = key.bounds.top.toInt()
                val kw = key.bounds.width().toInt().coerceAtLeast(1)
                val kh = key.bounds.height().toInt().coerceAtLeast(1)
                keyXCoordinates[i] = kx
                keyYCoordinates[i] = ky
                keyWidths[i] = kw
                keyHeights[i] = kh
                keyCharCodes[i] = key.code
                sweetSpotCenterXs[i] = key.bounds.centerX()
                sweetSpotCenterYs[i] = key.bounds.centerY()
                sweetSpotRadii[i] = Math.hypot(kw.toDouble(), kh.toDouble()).toFloat() / 2f
                totalWidth += kw
                totalHeight += kh
            }

            val mostCommonKeyWidth = (totalWidth / keyCount).coerceAtLeast(1)
            val mostCommonKeyHeight = (totalHeight / keyCount).coerceAtLeast(1)

            // Compute grid proximity characters
            val proximityChars = IntArray(gridWidth * gridHeight * MAX_PROXIMITY_CHARS_SIZE)
            for (gy in 0 until gridHeight) {
                val cellCenterY = gy * cellHeight + (cellHeight / 2)
                for (gx in 0 until gridWidth) {
                    val cellCenterX = gx * cellWidth + (cellWidth / 2)
                    val baseIndex = (gy * gridWidth + gx) * MAX_PROXIMITY_CHARS_SIZE

                    // Sort keys by squared distance to cell center
                    val nearKeys = ArrayList<Pair<Int, Double>>()
                    for (k in 0 until keyCount) {
                        val dx = cellCenterX - sweetSpotCenterXs[k]
                        val dy = cellCenterY - sweetSpotCenterYs[k]
                        val distSq = (dx * dx + dy * dy).toDouble()
                        nearKeys.add(Pair(keyCharCodes[k], distSq))
                    }
                    nearKeys.sortBy { it.second }

                    for (ci in 0 until MAX_PROXIMITY_CHARS_SIZE) {
                        if (ci < nearKeys.size) {
                            proximityChars[baseIndex + ci] = nearKeys[ci].first
                        } else {
                            proximityChars[baseIndex + ci] = 0
                        }
                    }
                }
            }

            return ProximityInfo(
                keyboardWidth,
                keyboardHeight,
                gridWidth,
                gridHeight,
                mostCommonKeyWidth,
                mostCommonKeyHeight,
                proximityChars,
                keyCount,
                keyXCoordinates,
                keyYCoordinates,
                keyWidths,
                keyHeights,
                keyCharCodes,
                sweetSpotCenterXs,
                sweetSpotCenterYs,
                sweetSpotRadii
            )
        }

        @JvmStatic
        private external fun setProximityInfoNative(
            displayWidth: Int,
            displayHeight: Int,
            gridWidth: Int,
            gridHeight: Int,
            mostCommonkeyWidth: Int,
            mostCommonkeyHeight: Int,
            proximityChars: IntArray,
            keyCount: Int,
            keyXCoordinates: IntArray,
            keyYCoordinates: IntArray,
            keyWidths: IntArray,
            keyHeights: IntArray,
            keyCharCodes: IntArray,
            sweetSpotCenterXs: FloatArray,
            sweetSpotCenterYs: FloatArray,
            sweetSpotRadii: FloatArray
        ): Long

        @JvmStatic
        private external fun releaseProximityInfoNative(proximityInfo: Long)
    }
}
