package com.example.ime.voice

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.example.logger.LogKeeper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Isolated multi-process background service running under android:process=":voice".
 * Host for AudioRecordPipeline, speech segmentation, and on-device Whisper AI inference.
 * Completely decoupled from the main IME process to guarantee that any native crash or
 * out-of-memory error during Whisper inference never affects VianBoardService.
 */
class VoiceInputService : Service(), AudioRecordPipeline.AudioPipelineListener {

    companion object {
        private const val TAG = "VoiceInputService"
        private const val IDLE_AUTO_SHUTDOWN_MS = 60_000L // 60 seconds of inactivity
        private const val MIN_SPEECH_SAMPLES = 8_000 // 0.5s at 16kHz
        private const val MAX_SPEECH_SAMPLES = 480_000 // 30s at 16kHz
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
        LogKeeper.logEvent(TAG, "Idle timeout reached (60s). Releasing audio pipeline and stopping service.")
        releaseAudioPipeline()
        stopSelf()
    }

    override fun onCreate() {
        super.onCreate()
        LogKeeper.logEvent(TAG, "VoiceInputService created in isolated process: ${android.os.Process.myPid()}")
        audioPipeline = AudioRecordPipeline(this)
        resetIdleTimeout()
    }

    override fun onBind(intent: Intent?): IBinder? {
        LogKeeper.logEvent(TAG, "Client bound to VoiceInputService")
        cancelIdleTimeout()
        loadActiveModel()
        return serviceMessenger.binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        LogKeeper.logEvent(TAG, "Client unbound from VoiceInputService")
        clientMessenger = null
        stopRecording()
        resetIdleTimeout()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        LogKeeper.logEvent(TAG, "VoiceInputService destroying")
        cancelIdleTimeout()
        releaseAudioPipeline()
        whisperEngine.release()
        inferenceExecutor.shutdownNow()
    }

    private fun loadActiveModel() {
        if (!whisperEngine.isModelLoaded) {
            val modelFile = VoiceModelManager.getActiveModelFile(this)
            if (modelFile.exists() && modelFile.length() > 1024 * 1024) {
                inferenceExecutor.execute {
                    val loaded = whisperEngine.loadModel(modelFile)
                    if (loaded) {
                        LogKeeper.logEvent(TAG, "Voice model loaded into Whisper context in :voice")
                    } else {
                        LogKeeper.logWarning(TAG, "Failed to load voice model into Whisper context")
                    }
                }
            } else {
                LogKeeper.logEvent(TAG, "No valid model file found in voice storage")
            }
        }
    }

    private fun handleClientMessage(msg: Message): Boolean {
        cancelIdleTimeout()
        when (msg.what) {
            VoiceIpcProtocol.CMD_REGISTER_CLIENT -> {
                clientMessenger = msg.replyTo
                sendStateToClient(currentState)
                loadActiveModel()
            }
            VoiceIpcProtocol.CMD_UNREGISTER_CLIENT -> {
                clientMessenger = null
                stopRecording()
                resetIdleTimeout()
            }
            VoiceIpcProtocol.CMD_START_RECORDING -> {
                val gain = msg.arg1.takeIf { it in listOf(1, 2, 4) } ?: 1
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
                LogKeeper.logEvent(TAG, "Audio gain set to ${gain}x")
            }
            VoiceIpcProtocol.CMD_FORCE_RELEASE -> {
                releaseAudioPipeline()
                stopSelf()
            }
            else -> return false
        }
        return true
    }

    private fun startRecording(gain: Int) {
        if (currentState == VoiceIpcProtocol.ServiceState.RECORDING) return

        synchronized(bufferLock) {
            audioBuffer.clear()
        }
        lastInterimInferenceTime = System.currentTimeMillis()

        audioPipeline?.gainMultiplier = gain
        val started = audioPipeline?.start() ?: false

        if (started) {
            currentState = VoiceIpcProtocol.ServiceState.RECORDING
            sendStateToClient(currentState)
            LogKeeper.logEvent(TAG, "Voice recording started successfully (gain=${gain}x)")
        } else {
            currentState = VoiceIpcProtocol.ServiceState.ERROR
            sendStateToClient(currentState)
            sendErrorToClient("Microphone initialization failed")
        }
    }

    private fun pauseRecording() {
        if (currentState != VoiceIpcProtocol.ServiceState.RECORDING) return
        audioPipeline?.pause()
        currentState = VoiceIpcProtocol.ServiceState.PAUSED
        sendStateToClient(currentState)
    }

    private fun resumeRecording() {
        if (currentState != VoiceIpcProtocol.ServiceState.PAUSED) return
        audioPipeline?.resume()
        currentState = VoiceIpcProtocol.ServiceState.RECORDING
        sendStateToClient(currentState)
    }

    private fun stopRecording() {
        if (currentState == VoiceIpcProtocol.ServiceState.IDLE) return
        audioPipeline?.stop()

        // Trigger final inference pass on buffered speech
        triggerFinalInference()

        currentState = VoiceIpcProtocol.ServiceState.IDLE
        sendStateToClient(currentState)
    }

    private fun releaseAudioPipeline() {
        stopRecording()
        audioPipeline?.release()
        audioPipeline = null
    }

    private fun resetIdleTimeout() {
        serviceHandler.removeCallbacks(autoShutdownRunnable)
        serviceHandler.postDelayed(autoShutdownRunnable, IDLE_AUTO_SHUTDOWN_MS)
    }

    private fun cancelIdleTimeout() {
        serviceHandler.removeCallbacks(autoShutdownRunnable)
    }

    // --- AudioPipelineListener Callbacks ---

    override fun onRmsChanged(rms: Float) {
        sendRmsToClient(rms)
    }

    override fun onSpeechActivityChanged(isSpeaking: Boolean) {
        sendSpeechActivityToClient(isSpeaking)
        if (!isSpeaking) {
            // Speech paused/ended: trigger inference for automatic commit
            triggerStreamingInference(isFinalPass = true)
        }
    }

    override fun onAudioChunkAvailable(buffer: ShortArray, readSize: Int) {
        synchronized(bufferLock) {
            if (audioBuffer.size + readSize > MAX_SPEECH_SAMPLES) {
                // Buffer full: trigger final pass and roll over
                triggerStreamingInference(isFinalPass = true)
                return
            }
            for (i in 0 until readSize) {
                audioBuffer.add(buffer[i] / 32768.0f)
            }
        }

        val now = System.currentTimeMillis()
        if (now - lastInterimInferenceTime >= INTERIM_INFERENCE_INTERVAL_MS &&
            audioBuffer.size >= MIN_SPEECH_SAMPLES &&
            !isTranscribing.get()
        ) {
            lastInterimInferenceTime = now
            triggerStreamingInference(isFinalPass = false)
        }
    }

    override fun onError(message: String) {
        currentState = VoiceIpcProtocol.ServiceState.ERROR
        sendStateToClient(currentState)
        sendErrorToClient(message)
    }

    // --- Inference Dispatch ---

    private fun triggerStreamingInference(isFinalPass: Boolean) {
        if (!whisperEngine.isModelLoaded) return
        if (isTranscribing.getAndSet(true)) return

        val samplesCopy: FloatArray
        synchronized(bufferLock) {
            if (audioBuffer.size < MIN_SPEECH_SAMPLES) {
                isTranscribing.set(false)
                return
            }
            samplesCopy = audioBuffer.toFloatArray()
            if (isFinalPass) {
                audioBuffer.clear()
            }
        }

        inferenceExecutor.execute {
            try {
                val rawText = whisperEngine.transcribe(samplesCopy)
                if (!rawText.isNullOrBlank()) {
                    // Apply user word replacements
                    val processedText = WordReplacementStore.applyReplacements(this@VoiceInputService, rawText)
                    if (isFinalPass) {
                        sendFinalCommitToClient(processedText)
                    } else {
                        sendPreviewToClient(processedText)
                    }
                }
            } catch (t: Throwable) {
                LogKeeper.logError(TAG, "Inference execution error", t.message ?: "")
            } finally {
                isTranscribing.set(false)
            }
        }
    }

    private fun triggerFinalInference() {
        triggerStreamingInference(isFinalPass = true)
    }

    // --- IPC Outgoing Dispatches ---

    private fun sendStateToClient(state: VoiceIpcProtocol.ServiceState) {
        clientMessenger?.let { messenger ->
            try {
                val msg = Message.obtain(null, VoiceIpcProtocol.EVENT_STATE_CHANGED, state.ordinal, 0)
                messenger.send(msg)
            } catch (e: RemoteException) {
                clientMessenger = null
            }
        }
    }

    private fun sendRmsToClient(rms: Float) {
        clientMessenger?.let { messenger ->
            try {
                val bundle = Bundle().apply { putFloat(VoiceIpcProtocol.KEY_RMS, rms) }
                val msg = Message.obtain(null, VoiceIpcProtocol.EVENT_RMS_UPDATE).apply { data = bundle }
                messenger.send(msg)
            } catch (e: RemoteException) {
                clientMessenger = null
            }
        }
    }

    private fun sendSpeechActivityToClient(isSpeaking: Boolean) {
        clientMessenger?.let { messenger ->
            try {
                val msg = Message.obtain(null, VoiceIpcProtocol.EVENT_SPEECH_ACTIVITY, if (isSpeaking) 1 else 0, 0)
                messenger.send(msg)
            } catch (e: RemoteException) {
                clientMessenger = null
            }
        }
    }

    private fun sendPreviewToClient(text: String) {
        clientMessenger?.let { messenger ->
            try {
                val bundle = Bundle().apply { putString(VoiceIpcProtocol.KEY_TRANSCRIPTION_TEXT, text) }
                val msg = Message.obtain(null, VoiceIpcProtocol.EVENT_TRANSCRIPTION_PREVIEW).apply { data = bundle }
                messenger.send(msg)
            } catch (e: RemoteException) {
                clientMessenger = null
            }
        }
    }

    private fun sendFinalCommitToClient(text: String) {
        clientMessenger?.let { messenger ->
            try {
                val bundle = Bundle().apply { putString(VoiceIpcProtocol.KEY_COMMIT_TEXT, text) }
                val msg = Message.obtain(null, VoiceIpcProtocol.EVENT_TRANSCRIPTION_COMMIT).apply { data = bundle }
                messenger.send(msg)
            } catch (e: RemoteException) {
                clientMessenger = null
            }
        }
    }

    private fun sendErrorToClient(message: String) {
        clientMessenger?.let { messenger ->
            try {
                val bundle = Bundle().apply { putString(VoiceIpcProtocol.KEY_ERROR_MESSAGE, message) }
                val msg = Message.obtain(null, VoiceIpcProtocol.EVENT_ERROR).apply { data = bundle }
                messenger.send(msg)
            } catch (e: RemoteException) {
                clientMessenger = null
            }
        }
    }
}
