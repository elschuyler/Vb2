package com.example.ime.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.example.logger.LogKeeper
import kotlin.math.sqrt

/**
 * High-performance, low-latency audio capture pipeline for speech recognition.
 * Captures 16kHz 16-bit mono PCM, applies 3-stage digital gain with anti-clipping limiter,
 * calculates smoothed RMS levels, and detects speech activity via EnergyVad.
 */
class AudioRecordPipeline(
    private val listener: AudioPipelineListener
) {
    companion object {
        private const val TAG = "AudioRecordPipeline"
        const val SAMPLE_RATE_HZ = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val RMS_DISPATCH_INTERVAL_MS = 40L
    }

    interface AudioPipelineListener {
        fun onRmsChanged(rms: Float)
        fun onSpeechActivityChanged(isSpeaking: Boolean)
        fun onAudioChunkAvailable(buffer: ShortArray, readSize: Int)
        fun onError(message: String)
    }

    @Volatile
    private var isRunning: Boolean = false

    @Volatile
    private var isPaused: Boolean = false

    @Volatile
    var gainMultiplier: Int = 1
        set(value) {
            field = if (value in listOf(1, 2, 4)) value else 1
        }

    private var workerThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private val energyVad = EnergyVad()

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(): Boolean {
        if (isRunning) return true

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ, CHANNEL_CONFIG, AUDIO_FORMAT
        )
        if (minBufferSize <= 0) {
            listener.onError("Audio hardware does not support 16kHz mono recording")
            return false
        }

        val bufferSize = maxOf(minBufferSize * 2, SAMPLE_RATE_HZ / 10 * 2) // ~100ms buffer

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE_HZ,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord?.release()
                audioRecord = null
                // Fallback to default MIC source
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE_HZ,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )
                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    audioRecord?.release()
                    audioRecord = null
                    listener.onError("Failed to initialize AudioRecord")
                    return false
                }
            }

            audioRecord?.startRecording()
            isRunning = true
            isPaused = false
            energyVad.reset()

            workerThread = Thread({ recordLoop(bufferSize) }, "VianAudioRecordThread").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }

            LogKeeper.logEvent(TAG, "Audio recording started (gain=${gainMultiplier}x)")
            return true
        } catch (e: Exception) {
            LogKeeper.logError(TAG, "Exception starting AudioRecord", e.message ?: "")
            release()
            listener.onError(e.message ?: "AudioRecord start failed")
            return false
        }
    }

    fun pause() {
        isPaused = true
        LogKeeper.logEvent(TAG, "Audio recording paused")
    }

    fun resume() {
        isPaused = false
        energyVad.reset()
        LogKeeper.logEvent(TAG, "Audio recording resumed")
    }

    @Synchronized
    fun stop() {
        if (!isRunning) return
        isRunning = false
        isPaused = false

        try {
            workerThread?.interrupt()
            workerThread?.join(500)
        } catch (ignored: InterruptedException) {
        } finally {
            workerThread = null
        }

        try {
            if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord?.stop()
            }
            audioRecord?.release()
        } catch (e: Exception) {
            LogKeeper.logError(TAG, "Exception stopping AudioRecord", e.message ?: "")
        } finally {
            audioRecord = null
        }

        LogKeeper.logEvent(TAG, "Audio recording stopped")
    }

    fun release() {
        stop()
    }

    private fun recordLoop(bufferSize: Int) {
        val readChunkSize = SAMPLE_RATE_HZ / 20 // 50ms chunk = 800 shorts
        val buffer = ShortArray(readChunkSize)
        var lastRmsDispatchTime = 0L
        var smoothedRms = 0f

        while (isRunning) {
            if (Thread.currentThread().isInterrupted) break

            if (isPaused) {
                try {
                    Thread.sleep(20)
                } catch (e: InterruptedException) {
                    break
                }
                continue
            }

            val recorder = audioRecord ?: break
            val readCount = recorder.read(buffer, 0, buffer.size)

            if (readCount <= 0) {
                if (readCount == AudioRecord.ERROR_INVALID_OPERATION ||
                    readCount == AudioRecord.ERROR_BAD_VALUE
                ) {
                    listener.onError("AudioRecord read error: $readCount")
                    break
                }
                continue
            }

            // Apply digital gain with peak limiter
            val gain = gainMultiplier
            if (gain > 1) {
                for (i in 0 until readCount) {
                    val amplified = buffer[i] * gain
                    buffer[i] = amplified.coerceIn(
                        Short.MIN_VALUE.toInt(),
                        Short.MAX_VALUE.toInt()
                    ).toShort()
                }
            }

            // Calculate RMS
            var sumSquares = 0.0
            for (i in 0 until readCount) {
                val sample = buffer[i].toDouble()
                sumSquares += sample * sample
            }
            val instantRms = (sqrt(sumSquares / readCount) / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
            smoothedRms = smoothedRms * 0.6f + instantRms * 0.4f

            val now = System.currentTimeMillis()
            if (now - lastRmsDispatchTime >= RMS_DISPATCH_INTERVAL_MS) {
                lastRmsDispatchTime = now
                listener.onRmsChanged(smoothedRms)
            }

            // Voice Activity Detection
            val wasSpeaking = energyVad.isSpeechDetected
            val isSpeakingNow = energyVad.processFrame(buffer, readCount)
            if (wasSpeaking != isSpeakingNow) {
                listener.onSpeechActivityChanged(isSpeakingNow)
            }

            // Pass chunk to listener for buffering/inference
            listener.onAudioChunkAvailable(buffer, readCount)
        }
    }
}
