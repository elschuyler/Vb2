// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

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
import helium314.keyboard.latin.utils.LogCatcher

/**
 * Client-side connection manager in the main IME process (:root).
 * Binds to the isolated VoiceInputService (:voice), dispatches user controls,
 * listens to RMS/speech events, and intercepts Binder death to ensure the keyboard
 * never crashes even if the voice process terminates.
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
        fun onFinalTranscription(text: String)
    }

    private val clientHandler = Handler(Looper.getMainLooper(), ::handleServiceMessage)
    private val clientMessenger = Messenger(clientHandler)
    private var serviceMessenger: Messenger? = null
    private var isBound: Boolean = false
    private var pendingStartGain: Int? = null

    private val deathRecipient = IBinder.DeathRecipient {
        LogCatcher.w(TAG, "VoiceInputService process (:voice) died or was terminated by OS")
        serviceMessenger = null
        isBound = false
        listener.onError("Voice service process stopped")
        listener.onStateChanged(VoiceIpcProtocol.ServiceState.ERROR)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            LogCatcher.i(TAG, "Connected to VoiceInputService")
            val messenger = Messenger(service)
            serviceMessenger = messenger
            isBound = true

            try {
                service?.linkToDeath(deathRecipient, 0)
                // Register client
                val registerMsg = Message.obtain(null, VoiceIpcProtocol.CMD_REGISTER_CLIENT)
                registerMsg.replyTo = clientMessenger
                messenger.send(registerMsg)

                pendingStartGain?.let { gain ->
                    val startMsg = Message.obtain(null, VoiceIpcProtocol.CMD_START_RECORDING)
                    startMsg.arg1 = gain
                    messenger.send(startMsg)
                    pendingStartGain = null
                }
            } catch (e: RemoteException) {
                LogCatcher.e(TAG, "RemoteException during service handshake", e)
                listener.onError("Voice service connection failure: ${e.message}")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            LogCatcher.i(TAG, "Disconnected from VoiceInputService")
            serviceMessenger = null
            isBound = false
            listener.onStateChanged(VoiceIpcProtocol.ServiceState.IDLE)
        }
    }

    fun connectAndStart(gainMultiplier: Int) {
        pendingStartGain = gainMultiplier
        if (!isBound) {
            val intent = Intent(context, VoiceInputService::class.java)
            try {
                context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            } catch (t: Throwable) {
                LogCatcher.e(TAG, "Failed to bind VoiceInputService", t)
                listener.onError("Failed to start voice service: ${t.message}")
            }
        } else {
            serviceMessenger?.let { messenger ->
                try {
                    val startMsg = Message.obtain(null, VoiceIpcProtocol.CMD_START_RECORDING)
                    startMsg.arg1 = gainMultiplier
                    messenger.send(startMsg)
                    pendingStartGain = null
                } catch (e: RemoteException) {
                    listener.onError("Failed to send start command: ${e.message}")
                }
            }
        }
    }

    fun pause() {
        val messenger = serviceMessenger ?: return
        try {
            val msg = Message.obtain(null, VoiceIpcProtocol.CMD_PAUSE_RECORDING)
            messenger.send(msg)
        } catch (e: RemoteException) {
            LogCatcher.w(TAG, "Failed to send pause command: ${e.message}")
        }
    }

    fun resume() {
        val messenger = serviceMessenger ?: return
        try {
            val msg = Message.obtain(null, VoiceIpcProtocol.CMD_RESUME_RECORDING)
            messenger.send(msg)
        } catch (e: RemoteException) {
            LogCatcher.w(TAG, "Failed to send resume command: ${e.message}")
        }
    }

    fun setGain(gainMultiplier: Int) {
        val messenger = serviceMessenger ?: return
        try {
            val msg = Message.obtain(null, VoiceIpcProtocol.CMD_SET_GAIN)
            msg.arg1 = gainMultiplier
            messenger.send(msg)
        } catch (e: RemoteException) {
            LogCatcher.w(TAG, "Failed to send setGain command: ${e.message}")
        }
    }

    fun stopAndDisconnect() {
        pendingStartGain = null
        if (isBound) {
            serviceMessenger?.let { messenger ->
                try {
                    val unregisterMsg = Message.obtain(null, VoiceIpcProtocol.CMD_UNREGISTER_CLIENT)
                    messenger.send(unregisterMsg)
                    val stopMsg = Message.obtain(null, VoiceIpcProtocol.CMD_STOP_RECORDING)
                    messenger.send(stopMsg)
                    messenger.binder?.unlinkToDeath(deathRecipient, 0)
                } catch (e: Exception) {
                    // Ignore unregister errors during teardown
                }
            }
            try {
                context.unbindService(serviceConnection)
            } catch (e: Exception) {
                // Ignore if already unbound
            }
            isBound = false
            serviceMessenger = null
        }
        listener.onRmsChanged(0f)
        listener.onSpeechActivity(false)
    }

    private fun handleServiceMessage(msg: Message): Boolean {
        when (msg.what) {
            VoiceIpcProtocol.EVENT_RMS_UPDATE -> {
                val rms = msg.data?.getFloat(VoiceIpcProtocol.KEY_RMS) ?: 0f
                listener.onRmsChanged(rms)
            }
            VoiceIpcProtocol.EVENT_STATE_CHANGED -> {
                val stateOrdinal = msg.arg1
                val state = VoiceIpcProtocol.ServiceState.values().getOrNull(stateOrdinal)
                    ?: VoiceIpcProtocol.ServiceState.IDLE
                listener.onStateChanged(state)
            }
            VoiceIpcProtocol.EVENT_SPEECH_ACTIVITY -> {
                val isSpeaking = msg.arg1 == 1
                listener.onSpeechActivity(isSpeaking)
            }
            VoiceIpcProtocol.EVENT_ERROR -> {
                val error = msg.data?.getString(VoiceIpcProtocol.KEY_ERROR_MESSAGE)
                    ?: "Voice input error"
                listener.onError(error)
            }
            VoiceIpcProtocol.EVENT_TRANSCRIPTION_PREVIEW -> {
                val text = msg.data?.getString(VoiceIpcProtocol.KEY_TRANSCRIPTION_TEXT) ?: ""
                listener.onTranscriptionPreview(text)
            }
            VoiceIpcProtocol.EVENT_FINAL_TRANSCRIPTION -> {
                val text = msg.data?.getString(VoiceIpcProtocol.KEY_FINAL_TEXT) ?: ""
                listener.onFinalTranscription(text)
            }
        }
        return true
    }
}
