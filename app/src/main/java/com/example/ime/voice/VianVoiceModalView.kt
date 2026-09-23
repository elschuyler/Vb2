package com.example.ime.voice

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.example.ime.modal.ModalBottomBarView
import com.example.ime.settings.VoiceInputSettingsActivity
import com.example.logger.LogKeeper
import com.example.R
import java.util.Locale

/**
 * Modern Compact Voice Modal View (~175dp height).
 * Features:
 * 1. 9-bar reactive vertical waveform pulse visualizer moving dynamically to audio RMS.
 * 2. Live recording duration timer (MM:SS) with optional 30-second legacy auto-stop limit.
 * 3. Speech activity detection ("Hearing sound…" vs "Listening…").
 * 4. Circular Mic toggle button, Cancel button, and Confirm checkmark button.
 * 5. Missing model warning card with direct import action.
 * 6. Gain sensitivity pill (1x -> 2x -> 4x -> 1x).
 * 7. Settings shortcut opening VoiceInputSettingsActivity.
 * 8. Standard ModalBottomBarView ([ABC] [Space] [Backspace] [Enter]).
 * 9. Haptic feedback on interactions.
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

    private val llNoModelBanner: LinearLayout
    private val tvGainPill: TextView
    private val btnVoiceSettings: ImageButton

    private val btnVoiceMic: ImageButton
    private val tvVoiceTimer: TextView
    private val tvVoiceStatus: TextView
    private val voicePulseView: VoicePulseView
    private val btnVoiceCancel: ImageButton
    private val btnVoiceConfirm: ImageButton
    private val modalBottomBar: ModalBottomBarView

    private var voiceConnection: VoiceInputConnection? = null
    private var currentGain: Int = 1
    private var isRecording: Boolean = false
    private var isPaused: Boolean = false
    private var elapsedSeconds: Int = 0

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (isRecording && !isPaused) {
                elapsedSeconds++
                tvVoiceTimer.text = String.format(Locale.US, "%02d:%02d", elapsedSeconds / 60, elapsedSeconds % 60)

                if (VoiceSettingsPreferences.isLegacy30sLimitEnabled(context) && elapsedSeconds >= 30) {
                    LogKeeper.logEvent(TAG, "Legacy 30s recording limit reached; committing transcription")
                    confirmAndFinish()
                    return
                }
            }
            if (isRecording) {
                mainHandler.postDelayed(this, 1000L)
            }
        }
    }

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
                        btnVoiceMic.setImageResource(R.drawable.ic_voice_mic_white)
                        tvVoiceStatus.setText(R.string.voice_status_listening)
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
            mainHandler.post {
                if (isRecording && !isPaused) {
                    if (isSpeaking) {
                        tvVoiceStatus.setText(R.string.voice_status_hearing_sound)
                    } else {
                        tvVoiceStatus.setText(R.string.voice_status_listening)
                    }
                }
            }
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
                    val correctedText = WordReplacementStore.applyReplacements(context, text)
                    onCommitText?.invoke(correctedText)
                    tvVoiceStatus.setText(R.string.voice_status_listening)
                }
            }
        }
    }

    init {
        val view = LayoutInflater.from(context).inflate(R.layout.view_voice_modal, this, true)

        llNoModelBanner = view.findViewById(R.id.llNoModelBanner)
        tvGainPill = view.findViewById(R.id.tvGainPill)
        btnVoiceSettings = view.findViewById(R.id.btnVoiceSettings)

        btnVoiceMic = view.findViewById(R.id.btnVoiceMic)
        tvVoiceTimer = view.findViewById(R.id.tvVoiceTimer)
        tvVoiceStatus = view.findViewById(R.id.tvVoiceStatus)
        voicePulseView = view.findViewById(R.id.voicePulseView)
        btnVoiceCancel = view.findViewById(R.id.btnVoiceCancel)
        btnVoiceConfirm = view.findViewById(R.id.btnVoiceConfirm)
        modalBottomBar = view.findViewById(R.id.modalBottomBar)

        setupInteractions()
    }

    private fun setupInteractions() {
        modalBottomBar.onAbcClick = {
            performVoiceHaptic()
            stopVoiceInput()
            onDismissToAlpha?.invoke()
        }
        modalBottomBar.onSpaceClick = { onCommitText?.invoke(" ") }
        modalBottomBar.onDeleteClick = { onDelete?.invoke() }
        modalBottomBar.onEnterClick = { onEnter?.invoke() }

        // Warning banner click
        llNoModelBanner.setOnClickListener {
            performVoiceHaptic()
            stopVoiceInput()
            openVoiceSettings()
        }

        // Circular mic button toggles pause/resume
        btnVoiceMic.setOnClickListener {
            performVoiceHaptic()
            togglePauseResume()
        }

        // Cancel button stops recording without saving and dismisses
        btnVoiceCancel.setOnClickListener {
            performVoiceHaptic()
            stopVoiceInput()
            onDismissToAlpha?.invoke()
        }

        // Confirm button stops recording, commits final text, and returns to keyboard
        btnVoiceConfirm.setOnClickListener {
            performVoiceHaptic()
            confirmAndFinish()
        }

        // Gain cycling pill: 1x -> 2x -> 4x -> 1x
        tvGainPill.setOnClickListener {
            performVoiceHaptic()
            currentGain = when (currentGain) {
                1 -> 2
                2 -> 4
                else -> 1
            }
            tvGainPill.text = "${currentGain}x"
            voiceConnection?.setGain(currentGain)
            LogKeeper.logEvent(TAG, "User toggled gain to ${currentGain}x")
        }

        // Settings button
        btnVoiceSettings.setOnClickListener {
            performVoiceHaptic()
            stopVoiceInput()
            openVoiceSettings()
        }
    }

    private fun openVoiceSettings() {
        val intent = Intent(context, VoiceInputSettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun performVoiceHaptic() {
        if (VoiceSettingsPreferences.isHapticFeedbackEnabled(context)) {
            try {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            } catch (_: Exception) {}
        }
    }

    fun startVoiceInput() {
        // Check model existence
        val modelInfo = VoiceModelManager.getModelInfo(context)
        if (modelInfo == null || !modelInfo.isValid) {
            llNoModelBanner.visibility = View.VISIBLE
            voicePulseView.pulseState = VoicePulseView.PulseState.ERROR
            tvVoiceStatus.setText(R.string.voice_status_no_model)
            return
        } else {
            llNoModelBanner.visibility = View.GONE
        }

        // Check audio recording permission
        if (!VoicePermissionBridge.hasRecordAudioPermission(context)) {
            voicePulseView.pulseState = VoicePulseView.PulseState.ERROR
            tvVoiceStatus.setText(R.string.voice_status_mic_permission)
            VoicePermissionBridge.requestRecordAudioPermission(context) { granted ->
                if (granted) {
                    mainHandler.post { startVoiceInput() }
                }
            }
            return
        }

        // Reset Timer & Status
        elapsedSeconds = 0
        tvVoiceTimer.text = "00:00"
        tvVoiceStatus.setText(R.string.voice_status_initializing)
        voicePulseView.pulseState = VoicePulseView.PulseState.LISTENING

        // Bind and start
        if (voiceConnection == null) {
            voiceConnection = VoiceInputConnection(context, connectionListener)
        }
        voiceConnection?.bind()
        voiceConnection?.startRecording(currentGain)

        mainHandler.removeCallbacks(timerRunnable)
        mainHandler.postDelayed(timerRunnable, 1000L)
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

    private fun confirmAndFinish() {
        try {
            voiceConnection?.stopRecording()
            voiceConnection?.unbind()
        } catch (e: Exception) {
            LogKeeper.logError(TAG, "Error during confirm and finish", e.message ?: "")
        } finally {
            voiceConnection = null
            isRecording = false
            isPaused = false
            voicePulseView.release()
            mainHandler.removeCallbacks(timerRunnable)
            onDismissToAlpha?.invoke()
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
            mainHandler.removeCallbacks(timerRunnable)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopVoiceInput()
    }
}
