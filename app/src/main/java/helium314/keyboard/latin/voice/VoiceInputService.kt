// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import helium314.keyboard.latin.database.VoiceReplacementDao
import helium314.keyboard.latin.utils.LogCatcher
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Isolated multi-process background service running under android:process=":voice".
 * Host for AudioRecordPipeline, speech segmentation, and on-device Whisper AI inference.
 * Decouples audio capture and native JNI inference from the main IME process to guarantee
 * that any native crash or audio buffer issue never crashes the keyboard.
 */
class VoiceInputService : Service(), AudioRecordPipeline.AudioPipelineListener {

    companion object {
        private const val TAG = "VoiceInputService"
        private const val IDLE_AUTO_SHUTDOWN_MS = 60_000L // 60 seconds of inactivity
        private const val MIN_SPEECH_SAMPLES = 8_000 // 0.5 seconds at 16kHz
        private const val MAX_SPEECH_SAMPLES = 480_000 // 30 seconds at 16kHz
        private const val INTERIM_INFERENCE_INTERVAL_MS = 800L
    }

    private val serviceHandler = Handler(Looper.getMainLooper(), ::handleClientMessage)
    private val serviceMessenger = Messenger(serviceHandler)
    private var clientMessenger: Messenger? = null

    private var audioPipeline: AudioRecordPipeline? = null
    private var currentState = VoiceIpcProtocol.ServiceState.IDLE

    private val whisperEngine = WhisperEngine()
    private val inferenceExecutor = Executors.newSingleThreadExecutor()
    private val isTranscribing = AtomicBoolean(false)
    private var lastInterimInferenceTime = 0L

    private val audioBuffer = ArrayList<Float>(MAX_SPEECH_SAMPLES)
    private val bufferLock = Any()

    private val autoShutdownRunnable = Runnable {
        LogCatcher.i(TAG, "Idle timeout reached (60s). Releasing audio pipeline and stopping service.")
        releaseAudioPipeline()
        stopSelf()
    }

    override fun onCreate() {
        super.onCreate()
        LogCatcher.i(TAG, "VoiceInputService created in process: ${android.os.Process.myPid()}")
        audioPipeline = AudioRecordPipeline(this)
        resetIdleTimeout()
    }

    override fun onBind(intent: Intent?): IBinder? {
        LogCatcher.i(TAG, "Client bound to VoiceInputService")
        resetIdleTimeout()
        return serviceMessenger.binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        LogCatcher.i(TAG, "Client unbound from VoiceInputService")
        clientMessenger = null
        pauseRecording()
        resetIdleTimeout()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        LogCatcher.i(TAG, "VoiceInputService onDestroy")
        serviceHandler.removeCallbacks(autoShutdownRunnable)
        releaseAudioPipeline()
        whisperEngine.release()
        try {
            inferenceExecutor.shutdown()
        } catch (e: Exception) {
            // Ignore shutdown errors
        }
    }

    private fun handleClientMessage(msg: Message): Boolean {
        resetIdleTimeout()
        when (msg.what) {
            VoiceIpcProtocol.CMD_REGISTER_CLIENT -> {
                clientMessenger = msg.replyTo
                notifyStateChanged(currentState)
            }
            VoiceIpcProtocol.CMD_UNREGISTER_CLIENT -> {
                clientMessenger = null
            }
            VoiceIpcProtocol.CMD_START_RECORDING -> {
                val gain = if (msg.arg1 in listOf(1, 2, 4)) msg.arg1 else 1
                startRecording(gain)
            }
            VoiceIpcProtocol.CMD_PAUSE_RECORDING -> {
                pauseRecording()
            }
            VoiceIpcProtocol.CMD_RESUME_RECORDING -> {
                resumeRecording()
            }
            VoiceIpcProtocol.CMD_STOP_RECORDING -> {
                stopRecording()
            }
            VoiceIpcProtocol.CMD_SET_GAIN -> {
                val gain = msg.arg1
                audioPipeline?.gainMultiplier = gain
            }
            VoiceIpcProtocol.CMD_FORCE_RELEASE -> {
                releaseAudioPipeline()
                stopSelf()
            }
        }
        return true
    }

    private fun startRecording(gain: Int) {
        if (audioPipeline == null) {
            audioPipeline = AudioRecordPipeline(this)
        }
        audioPipeline?.gainMultiplier = gain
        synchronized(bufferLock) {
            audioBuffer.clear()
        }
        val success = audioPipeline?.start() ?: false
        if (success) {
            currentState = VoiceIpcProtocol.ServiceState.RECORDING
            notifyStateChanged(currentState)
        } else {
            currentState = VoiceIpcProtocol.ServiceState.ERROR
            notifyStateChanged(currentState)
        }
    }

    private fun pauseRecording() {
        audioPipeline?.pause()
        currentState = VoiceIpcProtocol.ServiceState.PAUSED
        notifyStateChanged(currentState)
        triggerInference()
    }

    private fun resumeRecording() {
        audioPipeline?.resume()
        currentState = VoiceIpcProtocol.ServiceState.RECORDING
        notifyStateChanged(currentState)
    }

    private fun stopRecording() {
        audioPipeline?.stop()
        currentState = VoiceIpcProtocol.ServiceState.IDLE
        notifyStateChanged(currentState)
        triggerInference()
    }

    private fun releaseAudioPipeline() {
        audioPipeline?.release()
        audioPipeline = null
        synchronized(bufferLock) {
            audioBuffer.clear()
        }
        currentState = VoiceIpcProtocol.ServiceState.IDLE
        notifyStateChanged(currentState)
    }

    private fun resetIdleTimeout() {
        serviceHandler.removeCallbacks(autoShutdownRunnable)
        serviceHandler.postDelayed(autoShutdownRunnable, IDLE_AUTO_SHUTDOWN_MS)
    }

    private fun notifyStateChanged(state: VoiceIpcProtocol.ServiceState) {
        val messenger = clientMessenger ?: return
        try {
            val msg = Message.obtain(null, VoiceIpcProtocol.EVENT_STATE_CHANGED)
            msg.arg1 = state.ordinal
            messenger.send(msg)
        } catch (e: RemoteException) {
            clientMessenger = null
        }
    }

    private fun notifyFinalTranscription(text: String) {
        val messenger = clientMessenger ?: return
        try {
            val msg = Message.obtain(null, VoiceIpcProtocol.EVENT_FINAL_TRANSCRIPTION)
            val bundle = Bundle().apply {
                putString(VoiceIpcProtocol.KEY_FINAL_TEXT, text)
                putString(VoiceIpcProtocol.KEY_TRANSCRIPTION_TEXT, text)
            }
            msg.data = bundle
            messenger.send(msg)
        } catch (e: RemoteException) {
            clientMessenger = null
        }
    }

    private fun notifyTranscriptionPreview(text: String) {
        val messenger = clientMessenger ?: return
        try {
            val msg = Message.obtain(null, VoiceIpcProtocol.EVENT_TRANSCRIPTION_PREVIEW)
            val bundle = Bundle().apply {
                putString(VoiceIpcProtocol.KEY_TRANSCRIPTION_TEXT, text)
            }
            msg.data = bundle
            messenger.send(msg)
        } catch (e: RemoteException) {
            clientMessenger = null
        }
    }

    private fun triggerInterimPreviewInference() {
        val now = System.currentTimeMillis()
        if (now - lastInterimInferenceTime < INTERIM_INFERENCE_INTERVAL_MS) return
        if (isTranscribing.get()) return

        val samples: FloatArray
        synchronized(bufferLock) {
            if (audioBuffer.size < MIN_SPEECH_SAMPLES) return
            samples = audioBuffer.toFloatArray()
        }

        lastInterimInferenceTime = now
        inferenceExecutor.execute {
            if (isTranscribing.compareAndSet(false, true)) {
                try {
                    if (!whisperEngine.isModelLoaded) {
                        val modelFile = VoiceModelManager.getActiveModelFile(applicationContext)
                        if (modelFile.exists() && modelFile.length() > 1024 * 1024) {
                            whisperEngine.loadModel(modelFile)
                        } else {
                            return@execute
                        }
                    }

                    // 2-thread preview inference with dynamic audio_ctx
                    val rawResult = whisperEngine.transcribe(samples, numThreads = 2)
                    if (!rawResult.isNullOrBlank()) {
                        val replacedText = VoiceReplacementDao.getInstance(applicationContext).applyReplacements(rawResult)
                        notifyTranscriptionPreview(replacedText)
                    }
                } catch (t: Throwable) {
                    LogCatcher.e(TAG, "Exception during preview inference", t)
                } finally {
                    isTranscribing.set(false)
                }
            }
        }
    }

    private fun triggerInference() {
        lastInterimInferenceTime = 0L
        val samples: FloatArray
        synchronized(bufferLock) {
            if (audioBuffer.size < MIN_SPEECH_SAMPLES) {
                // Ignore audio clips shorter than 0.5s to avoid transcribing clicks
                audioBuffer.clear()
                return
            }
            samples = audioBuffer.toFloatArray()
            audioBuffer.clear()
        }

        inferenceExecutor.execute {
            if (isTranscribing.compareAndSet(false, true)) {
                try {
                    // Check and load model if needed
                    if (!whisperEngine.isModelLoaded) {
                        val modelFile = VoiceModelManager.getActiveModelFile(applicationContext)
                        if (modelFile.exists() && modelFile.length() > 1024 * 1024) {
                            LogCatcher.i(TAG, "Loading Whisper model into native runtime...")
                            val loaded = whisperEngine.loadModel(modelFile)
                            if (!loaded) {
                                LogCatcher.e(TAG, "Failed to load model file")
                                onError("Model loading failed")
                                return@execute
                            }
                        } else {
                            LogCatcher.w(TAG, "No model file available in storage")
                            onError("Model missing")
                            return@execute
                        }
                    }

                    val rawResult = whisperEngine.transcribe(samples)
                    if (!rawResult.isNullOrBlank()) {
                        val replacedText = VoiceReplacementDao.getInstance(applicationContext).applyReplacements(rawResult)
                        LogCatcher.i(TAG, "Transcription finalized: ${replacedText.length} chars (sanitized)")
                        notifyFinalTranscription(replacedText)
                    } else {
                        LogCatcher.i(TAG, "Inference returned empty result (silence/noise)")
                    }
                } catch (t: Throwable) {
                    LogCatcher.e(TAG, "Exception during inference processing", t)
                    onError("Inference error: ${t.javaClass.simpleName}")
                } finally {
                    isTranscribing.set(false)
                }
            }
        }
    }

    // --- AudioRecordPipeline.AudioPipelineListener Callbacks ---

    override fun onRmsChanged(rms: Float) {
        val messenger = clientMessenger ?: return
        try {
            val msg = Message.obtain(null, VoiceIpcProtocol.EVENT_RMS_UPDATE)
            val bundle = Bundle().apply {
                putFloat(VoiceIpcProtocol.KEY_RMS, rms)
            }
            msg.data = bundle
            messenger.send(msg)
        } catch (e: RemoteException) {
            clientMessenger = null
        }
    }

    override fun onSpeechActivityChanged(isSpeaking: Boolean) {
        val messenger = clientMessenger ?: return
        try {
            val msg = Message.obtain(null, VoiceIpcProtocol.EVENT_SPEECH_ACTIVITY)
            msg.arg1 = if (isSpeaking) 1 else 0
            messenger.send(msg)
        } catch (e: RemoteException) {
            clientMessenger = null
        }

        if (!isSpeaking) {
            // Trailing pause detected by EnergyVad! Process speech chunk
            triggerInference()
        }
    }

    override fun onAudioChunkAvailable(buffer: ShortArray, readSize: Int) {
        synchronized(bufferLock) {
            val maxToAdd = minOf(readSize, MAX_SPEECH_SAMPLES - audioBuffer.size)
            for (i in 0 until maxToAdd) {
                audioBuffer.add(buffer[i] / 32768.0f)
            }
        }
        if (currentState == VoiceIpcProtocol.ServiceState.RECORDING) {
            triggerInterimPreviewInference()
        }
    }

    override fun onError(message: String) {
        LogCatcher.e(TAG, "Audio error: $message")
        currentState = VoiceIpcProtocol.ServiceState.ERROR
        notifyStateChanged(currentState)
        val messenger = clientMessenger ?: return
        try {
            val msg = Message.obtain(null, VoiceIpcProtocol.EVENT_ERROR)
            val bundle = Bundle().apply {
                putString(VoiceIpcProtocol.KEY_ERROR_MESSAGE, message)
            }
            msg.data = bundle
            messenger.send(msg)
        } catch (e: RemoteException) {
            clientMessenger = null
        }
    }
}
