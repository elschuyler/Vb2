package com.example.ime.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.example.logger.LogKeeper

/**
 * Client-side connection manager in the main IME process (:root).
 * Binds to the isolated VoiceInputService (:voice), dispatches user controls,
 * listens to RMS/speech events, and intercepts Binder death to ensure the keyboard
 * never crashes even if the isolated voice process encounters a native abort.
 */
class VoiceInputConnection(
    private val context: Context,
    private val listener: VoiceConnectionListener
) {
    companion object {
        private const val TAG = "VoiceInputConnection"
    }

    interface VoiceConnectionListener {
        fun onRmsChanged(rms: Float)
        fun onStateChanged(state: VoiceIpcProtocol.ServiceState)
        fun onSpeechActivity(isSpeaking: Boolean)
        fun onError(message: String)
        fun onTranscriptionPreview(text: String)
        fun onTranscriptionCommit(text: String)
    }

    private val clientHandler = Handler(Looper.getMainLooper(), ::handleServiceMessage)
    private val clientMessenger = Messenger(clientHandler)
    private var serviceMessenger: Messenger? = null
    private var isBound: Boolean = false
    private var pendingStartGain: Int? = null

    private val deathRecipient = IBinder.DeathRecipient {
        LogKeeper.logWarning(TAG, "VoiceInputService process (:voice) died or was terminated by OS")
        serviceMessenger = null
        isBound = false
        listener.onError("Voice service process stopped")
        listener.onStateChanged(VoiceIpcProtocol.ServiceState.ERROR)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            LogKeeper.logEvent(TAG, "Connected to VoiceInputService")
            val messenger = Messenger(service)
            serviceMessenger = messenger
            isBound = true

            try {
                service?.linkToDeath(deathRecipient, 0)
            } catch (e: RemoteException) {
                LogKeeper.logError(TAG, "Failed to link to death recipient", e.message ?: "")
            }

            // Register client messenger
            val registerMsg = Message.obtain(null, VoiceIpcProtocol.CMD_REGISTER_CLIENT).apply {
                replyTo = clientMessenger
            }
            sendServiceMessage(registerMsg)

            // If a start was requested while binding, execute now
            pendingStartGain?.let { gain ->
                startRecording(gain)
                pendingStartGain = null
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            LogKeeper.logEvent(TAG, "Disconnected from VoiceInputService")
            serviceMessenger = null
            isBound = false
            listener.onStateChanged(VoiceIpcProtocol.ServiceState.IDLE)
        }
    }

    fun bind() {
        if (isBound) return
        val intent = Intent(context, VoiceInputService::class.java)
        try {
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            LogKeeper.logError(TAG, "Failed to bind VoiceInputService", e.message ?: "")
            listener.onError("Could not bind voice service")
        }
    }

    fun unbind() {
        if (!isBound) return
        stopRecording()
        val unregisterMsg = Message.obtain(null, VoiceIpcProtocol.CMD_UNREGISTER_CLIENT)
        sendServiceMessage(unregisterMsg)

        try {
            serviceMessenger?.binder?.unlinkToDeath(deathRecipient, 0)
        } catch (ignored: Exception) {
        }

        try {
            context.unbindService(serviceConnection)
        } catch (e: Exception) {
            LogKeeper.logError(TAG, "Failed to unbind VoiceInputService", e.message ?: "")
        } finally {
            isBound = false
            serviceMessenger = null
            pendingStartGain = null
        }
    }

    fun startRecording(gainMultiplier: Int = 1) {
        if (!isBound || serviceMessenger == null) {
            pendingStartGain = gainMultiplier
            bind()
            return
        }
        val msg = Message.obtain(null, VoiceIpcProtocol.CMD_START_RECORDING, gainMultiplier, 0)
        sendServiceMessage(msg)
    }

    fun pauseRecording() {
        val msg = Message.obtain(null, VoiceIpcProtocol.CMD_PAUSE_RECORDING)
        sendServiceMessage(msg)
    }

    fun resumeRecording() {
        val msg = Message.obtain(null, VoiceIpcProtocol.CMD_RESUME_RECORDING)
        sendServiceMessage(msg)
    }

    fun stopRecording() {
        val msg = Message.obtain(null, VoiceIpcProtocol.CMD_STOP_RECORDING)
        sendServiceMessage(msg)
    }

    fun setGain(multiplier: Int) {
        val msg = Message.obtain(null, VoiceIpcProtocol.CMD_SET_GAIN, multiplier, 0)
        sendServiceMessage(msg)
    }

    private fun sendServiceMessage(msg: Message) {
        try {
            serviceMessenger?.send(msg)
        } catch (e: RemoteException) {
            LogKeeper.logError(TAG, "RemoteException sending msg ${msg.what} to VoiceInputService", e.message ?: "")
            listener.onError("Voice service connection broken")
        }
    }

    private fun handleServiceMessage(msg: Message): Boolean {
        when (msg.what) {
            VoiceIpcProtocol.EVENT_STATE_CHANGED -> {
                val stateOrdinal = msg.arg1
                val state = VoiceIpcProtocol.ServiceState.values().getOrNull(stateOrdinal)
                    ?: VoiceIpcProtocol.ServiceState.IDLE
                listener.onStateChanged(state)
            }
            VoiceIpcProtocol.EVENT_RMS_UPDATE -> {
                val rms = msg.data?.getFloat(VoiceIpcProtocol.KEY_RMS) ?: 0f
                listener.onRmsChanged(rms)
            }
            VoiceIpcProtocol.EVENT_SPEECH_ACTIVITY -> {
                val isSpeaking = msg.arg1 == 1
                listener.onSpeechActivity(isSpeaking)
            }
            VoiceIpcProtocol.EVENT_TRANSCRIPTION_PREVIEW -> {
                val text = msg.data?.getString(VoiceIpcProtocol.KEY_TRANSCRIPTION_TEXT) ?: ""
                listener.onTranscriptionPreview(text)
            }
            VoiceIpcProtocol.EVENT_TRANSCRIPTION_COMMIT -> {
                val text = msg.data?.getString(VoiceIpcProtocol.KEY_COMMIT_TEXT) ?: ""
                listener.onTranscriptionCommit(text)
            }
            VoiceIpcProtocol.EVENT_ERROR -> {
                val errorMsg = msg.data?.getString(VoiceIpcProtocol.KEY_ERROR_MESSAGE) ?: "Unknown voice error"
                listener.onError(errorMsg)
            }
            else -> return false
        }
        return true
    }
}
