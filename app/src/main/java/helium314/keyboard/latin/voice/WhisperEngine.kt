// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import helium314.keyboard.latin.utils.LogCatcher
import java.io.File

/**
 * JNI wrapper and lifecycle manager for on-device Whisper inference.
 * Safely bridges Kotlin audio floats to the native C/C++ libwhisper runtime.
 * Rigorously connected to LogCatcher for operational telemetry without logging PII.
 */
class WhisperEngine {

    companion object {
        private const val TAG = "WhisperEngine"
        private const val COMPONENT_NAME = "WhisperEngine"

        @Volatile
        private var isLibraryLoaded: Boolean = false

        init {
            try {
                System.loadLibrary("whisper")
                isLibraryLoaded = true
                LogCatcher.i(TAG, "Native whisper library (libwhisper.so) loaded successfully")
                LogCatcher.markComponentActive(COMPONENT_NAME, "VoiceEngine", "Native Library Ready")
            } catch (e: UnsatisfiedLinkError) {
                isLibraryLoaded = false
                LogCatcher.w(TAG, "libwhisper.so not loaded (requires CI compilation): ${e.message}")
            } catch (t: Throwable) {
                isLibraryLoaded = false
                LogCatcher.e(TAG, "Unexpected error loading libwhisper.so", t)
            }
        }

        fun isNativeAvailable(): Boolean = isLibraryLoaded

        /**
         * Strips common Whisper hallucination artifacts and control tokens.
         */
        fun cleanWhisperOutput(raw: String): String {
            return raw
                .replace(Regex("<\\|.*?\\|>"), "")
                .replace("[BLANK_AUDIO]", "")
                .replace("[MUSIC]", "")
                .replace("[APPLAUSE]", "")
                .trim()
        }
    }

    private var contextPtr: Long = 0L

    val isModelLoaded: Boolean
        get() = contextPtr != 0L

    @Synchronized
    fun loadModel(modelFile: File): Boolean {
        if (!isLibraryLoaded) {
            LogCatcher.w(TAG, "Cannot load model: native libwhisper.so is not available")
            return false
        }
        if (!modelFile.exists() || modelFile.length() < 1024 * 1024) {
            LogCatcher.e(TAG, "Model file does not exist or is invalid (<1MB)")
            return false
        }

        // Release prior model if active
        if (contextPtr != 0L) {
            release()
        }

        return try {
            val startTime = System.currentTimeMillis()
            LogCatcher.i(TAG, "Initializing Whisper context from file (size: ${modelFile.length()} bytes)")
            val ptr = initContext(modelFile.absolutePath)
            val elapsed = System.currentTimeMillis() - startTime

            if (ptr != 0L) {
                contextPtr = ptr
                LogCatcher.i(TAG, "Whisper context initialized successfully in ${elapsed}ms (ptr=$ptr)")
                LogCatcher.markComponentActive(COMPONENT_NAME, "VoiceEngine", "Active (Context: $ptr)")
                true
            } else {
                LogCatcher.e(TAG, "Native initContext returned null pointer for model")
                LogCatcher.markComponentActive(COMPONENT_NAME, "VoiceEngine", "Error: Null Context")
                false
            }
        } catch (t: Throwable) {
            LogCatcher.e(TAG, "Exception during native model initialization", t)
            LogCatcher.markComponentActive(COMPONENT_NAME, "VoiceEngine", "Init Exception: ${t.javaClass.simpleName}")
            false
        }
    }

    @Synchronized
    fun transcribe(
        audioSamples: FloatArray,
        numThreads: Int = minOf(4, Runtime.getRuntime().availableProcessors())
    ): String? {
        if (contextPtr == 0L) {
            LogCatcher.w(TAG, "transcribe called without active model context")
            return null
        }
        if (audioSamples.isEmpty()) {
            return null
        }

        return try {
            val durationSec = audioSamples.size / AudioRecordPipeline.SAMPLE_RATE_HZ.toFloat()
            LogCatcher.i(TAG, "Starting inference: ${audioSamples.size} samples (~${"%.2f".format(durationSec)}s, $numThreads threads)")
            LogCatcher.markComponentActive(COMPONENT_NAME, "VoiceEngine", "Transcribing (${"%.1f".format(durationSec)}s audio)")

            val startTime = System.currentTimeMillis()
            val rawResult = fullTranscribe(contextPtr, numThreads, audioSamples)
            val elapsed = System.currentTimeMillis() - startTime

            LogCatcher.i(TAG, "Inference completed in ${elapsed}ms")
            LogCatcher.markComponentActive(COMPONENT_NAME, "VoiceEngine", "Idle (Last inference: ${elapsed}ms)")

            if (rawResult != null) {
                val cleaned = cleanWhisperOutput(rawResult)
                cleaned
            } else {
                LogCatcher.w(TAG, "Native fullTranscribe returned null")
                null
            }
        } catch (t: Throwable) {
            LogCatcher.e(TAG, "Exception during native inference", t)
            LogCatcher.markComponentActive(COMPONENT_NAME, "VoiceEngine", "Inference Exception")
            null
        }
    }

    @Synchronized
    fun release() {
        if (contextPtr != 0L) {
            try {
                LogCatcher.i(TAG, "Releasing Whisper context (ptr=$contextPtr)")
                freeContext(contextPtr)
            } catch (t: Throwable) {
                LogCatcher.e(TAG, "Exception releasing Whisper context", t)
            } finally {
                contextPtr = 0L
                LogCatcher.markComponentInactive(COMPONENT_NAME, "Released")
            }
        }
    }

    // --- Native JNI Method Declarations ---
    private external fun initContext(modelPath: String): Long
    private external fun freeContext(contextPtr: Long)
    private external fun fullTranscribe(contextPtr: Long, numThreads: Int, audioData: FloatArray): String?
}
