package com.example.ime.voice

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import com.example.ime.modal.ModalBottomBarView
import com.example.ime.settings.VoiceInputSettingsActivity
import com.example.logger.LogKeeper
import helium314.keyboard.latin.R

/**
 * Compact Voice Modal View (~160dp height).
 * Matches unified modal architecture (alongside Clipboard, Prompt List, Emoji, and Desktop Shortcuts).
 * Features:
 * 1. Reactive VoicePulseView canvas animation driven by real-time microphone RMS energy.
 * 2. Streaming preview text & state indicator (Listening, Paused, Missing Model, Error).
 * 3. 3-stage gain toggle pill (1x -> 2x -> 4x -> 1x).
 * 4. Settings shortcut button opening VoiceInputSettingsActivity.
 * 5. Direct binding to VoiceInputConnection in isolated :voice process.
 * 6. Standard ModalBottomBarView ([ABC] [Space] [Backspace] [Enter]).
 */
class VianVoiceModalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "VianVoiceModalView"
    }

    var onDismissToAlpha: (() -> Unit)? = null
    var onCommitText: ((String) -> Unit)? = null
    var onDelete: (() -> Unit)? = null
    var onEnter: (() -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val voicePulseView: VoicePulseView
    private val tvVoiceStatus: TextView
    private val tvGainPill: TextView
    private val btnVoiceSettings: ImageButton
    private val modalBottomBar: ModalBottomBarView

    private var voiceConnection: VoiceInputConnection? = null
    private var currentGain: Int = 1
    private var isRecording: Boolean = false
    private var isPaused: Boolean = false

    private val connectionListener = object : VoiceInputConnection.VoiceConnectionListener {
        override fun onRmsChanged(rms: Float) {
            mainHandler.post {
                if (isRecording && !isPaused) {
                    voicePulseView.setRms(rms)
                }
            }
        }

        override fun onStateChanged(state: VoiceIpcProtocol.ServiceState) {
            mainHandler.post {
                when (state) {
                    VoiceIpcProtocol.ServiceState.RECORDING -> {
                        isRecording = true
                        isPaused = false
                        voicePulseView.pulseState = VoicePulseView.PulseState.LISTENING
                        if (tvVoiceStatus.text == context.getString(R.string.voice_status_paused) ||
                            tvVoiceStatus.text == context.getString(R.string.voice_status_initializing)) {
                            tvVoiceStatus.setText(R.string.voice_status_listening)
                        }
                    }
                    VoiceIpcProtocol.ServiceState.PAUSED -> {
                        isPaused = true
                        voicePulseView.pulseState = VoicePulseView.PulseState.PAUSED
                        tvVoiceStatus.setText(R.string.voice_status_paused)
                    }
                    VoiceIpcProtocol.ServiceState.ERROR -> {
                        isRecording = false
                        voicePulseView.pulseState = VoicePulseView.PulseState.ERROR
                    }
                    VoiceIpcProtocol.ServiceState.IDLE -> {
                        isRecording = false
                        isPaused = false
                        voicePulseView.pulseState = VoicePulseView.PulseState.IDLE
                    }
                }
            }
        }

        override fun onSpeechActivity(isSpeaking: Boolean) {
            // Optional visual indicator
        }

        override fun onError(message: String) {
            mainHandler.post {
                voicePulseView.pulseState = VoicePulseView.PulseState.ERROR
                tvVoiceStatus.text = message
            }
        }

        override fun onTranscriptionPreview(text: String) {
            mainHandler.post {
                if (text.isNotBlank()) {
                    tvVoiceStatus.text = text
                }
            }
        }

        override fun onTranscriptionCommit(text: String) {
            mainHandler.post {
                if (text.isNotBlank()) {
                    // Apply word improvement replacements from store
                    val correctedText = WordReplacementStore.applyReplacements(context, text)
                    onCommitText?.invoke(correctedText)
                    tvVoiceStatus.setText(R.string.voice_status_listening)
                }
            }
        }
    }

    init {
        val view = LayoutInflater.from(context).inflate(R.layout.view_voice_modal, this, true)

        voicePulseView = view.findViewById(R.id.voicePulseView)
        tvVoiceStatus = view.findViewById(R.id.tvVoiceStatus)
        tvGainPill = view.findViewById(R.id.tvGainPill)
        btnVoiceSettings = view.findViewById(R.id.btnVoiceSettings)
        modalBottomBar = view.findViewById(R.id.modalBottomBar)

        setupInteractions()
    }

    private fun setupInteractions() {
        modalBottomBar.onAbcClick = {
            stopVoiceInput()
            onDismissToAlpha?.invoke()
        }
        modalBottomBar.onSpaceClick = { onCommitText?.invoke(" ") }
        modalBottomBar.onDeleteClick = { onDelete?.invoke() }
        modalBottomBar.onEnterClick = { onEnter?.invoke() }

        // Tap preview row / pulse to pause/resume
        tvVoiceStatus.setOnClickListener { togglePauseResume() }
        voicePulseView.setOnClickListener { togglePauseResume() }

        // Gain cycling pill: 1x -> 2x -> 4x -> 1x
        tvGainPill.setOnClickListener {
            currentGain = when (currentGain) {
                1 -> 2
                2 -> 4
                else -> 1
            }
            tvGainPill.text = "${currentGain}x"
            voiceConnection?.setGain(currentGain)
            LogKeeper.logEvent(TAG, "User toggled gain to ${currentGain}x")
        }

        // Settings shortcut
        btnVoiceSettings.setOnClickListener {
            stopVoiceInput()
            val intent = Intent(context, VoiceInputSettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    fun startVoiceInput() {
        // Check model existence first
        val modelInfo = VoiceModelManager.getModelInfo(context)
        if (modelInfo == null || !modelInfo.isValid) {
            voicePulseView.pulseState = VoicePulseView.PulseState.ERROR
            tvVoiceStatus.setText(R.string.voice_status_no_model)
            tvVoiceStatus.setOnClickListener {
                val intent = Intent(context, VoiceInputSettingsActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
            return
        }

        // Check permission
        if (!VoicePermissionBridge.hasRecordAudioPermission(context)) {
            voicePulseView.pulseState = VoicePulseView.PulseState.ERROR
            tvVoiceStatus.setText(R.string.voice_status_mic_permission)
            tvVoiceStatus.setOnClickListener {
                VoicePermissionBridge.requestRecordAudioPermission(context) { granted ->
                    if (granted) {
                        mainHandler.post { startVoiceInput() }
                    }
                }
            }
            // Trigger permission flow directly
            VoicePermissionBridge.requestRecordAudioPermission(context) { granted ->
                if (granted) {
                    mainHandler.post { startVoiceInput() }
                }
            }
            return
        }

        // Reset UI
        tvVoiceStatus.setText(R.string.voice_status_initializing)
        voicePulseView.pulseState = VoicePulseView.PulseState.LISTENING

        // Bind and start
        if (voiceConnection == null) {
            voiceConnection = VoiceInputConnection(context, connectionListener)
        }
        voiceConnection?.bind()
        voiceConnection?.startRecording(currentGain)
        LogKeeper.logEvent(TAG, "Voice input session started")
    }

    private fun togglePauseResume() {
        if (!isRecording && !isPaused) {
            startVoiceInput()
            return
        }
        if (isPaused) {
            voiceConnection?.resumeRecording()
            isPaused = false
            voicePulseView.pulseState = VoicePulseView.PulseState.LISTENING
            tvVoiceStatus.setText(R.string.voice_status_listening)
        } else {
            voiceConnection?.pauseRecording()
            isPaused = true
            voicePulseView.pulseState = VoicePulseView.PulseState.PAUSED
            tvVoiceStatus.setText(R.string.voice_status_paused)
        }
    }

    fun stopVoiceInput() {
        try {
            voiceConnection?.stopRecording()
            voiceConnection?.unbind()
        } catch (e: Exception) {
            LogKeeper.logError(TAG, "Error stopping voice input", e.message ?: "")
        } finally {
            voiceConnection = null
            isRecording = false
            isPaused = false
            voicePulseView.release()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopVoiceInput()
    }
}
