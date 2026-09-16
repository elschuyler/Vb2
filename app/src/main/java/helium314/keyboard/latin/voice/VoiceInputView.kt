// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.KeyboardTypeface
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.keyboard.internal.ShiftMode
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SettingsActivity

class VoiceInputView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private lateinit var pulseView: VoicePulseView
    private lateinit var streamingText: TextView
    private lateinit var gainPill: TextView
    private lateinit var divider: View
    private lateinit var keyAbc: TextView
    private lateinit var keySpace: TextView
    private lateinit var keyBackspace: ImageView
    private lateinit var keyEnter: ImageView

    private var keyboardActionListener: KeyboardActionListener? = null
    private var isVoiceActive: Boolean = false
    private var isVoicePaused: Boolean = false
    private var currentGainMultiplier: Int = 1
    private var voiceConnection: VoiceInputConnection? = null

    private val connectionListener = object : VoiceInputConnection.VoiceConnectionListener {
        override fun onRmsChanged(rms: Float) {
            post {
                if (isVoiceActive && !isVoicePaused) {
                    pulseView.setRms(rms)
                }
            }
        }

        override fun onStateChanged(state: VoiceIpcProtocol.ServiceState) {
            post {
                when (state) {
                    VoiceIpcProtocol.ServiceState.RECORDING -> {
                        pulseView.pulseState = VoicePulseView.PulseState.LISTENING
                        if (streamingText.text == context.getString(R.string.voice_status_paused)) {
                            streamingText.setText(R.string.voice_status_listening)
                        }
                    }
                    VoiceIpcProtocol.ServiceState.PAUSED -> {
                        pulseView.pulseState = VoicePulseView.PulseState.PAUSED
                        streamingText.setText(R.string.voice_status_paused)
                    }
                    VoiceIpcProtocol.ServiceState.ERROR -> {
                        pulseView.pulseState = VoicePulseView.PulseState.ERROR
                    }
                    VoiceIpcProtocol.ServiceState.IDLE -> {
                        pulseView.pulseState = VoicePulseView.PulseState.IDLE
                    }
                }
            }
        }

        override fun onSpeechActivity(isSpeaking: Boolean) {
            // Speech presence indicator for future expansion
        }

        override fun onError(message: String) {
            post {
                pulseView.pulseState = VoicePulseView.PulseState.ERROR
                streamingText.text = message
            }
        }

        override fun onTranscriptionPreview(text: String) {
            post {
                updateStreamingText(text)
            }
        }

        override fun onFinalTranscription(text: String) {
            post {
                if (text.isNotEmpty()) {
                    updateStreamingText(text)
                    keyboardActionListener?.onTextInput(text)
                }
            }
        }
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        pulseView = findViewById(R.id.voice_pulse_view)
        streamingText = findViewById(R.id.voice_streaming_text)
        gainPill = findViewById(R.id.voice_gain_pill)
        divider = findViewById(R.id.voice_divider)
        keyAbc = findViewById(R.id.voice_key_abc)
        keySpace = findViewById(R.id.voice_key_space)
        keyBackspace = findViewById(R.id.voice_key_backspace)
        keyEnter = findViewById(R.id.voice_key_enter)

        setupListeners()
    }

    private fun setupListeners() {
        keyAbc.setOnClickListener {
            stopVoiceInput()
            KeyboardSwitcher.getInstance().setAlphabetKeyboard(ShiftMode.UNSHIFT)
        }

        keySpace.setOnClickListener {
            pauseVoiceInput()
            keyboardActionListener?.onCodeInput(
                Constants.CODE_SPACE,
                Constants.NOT_A_COORDINATE,
                Constants.NOT_A_COORDINATE,
                false
            )
        }

        keyBackspace.setOnClickListener {
            pauseVoiceInput()
            keyboardActionListener?.onCodeInput(
                KeyCode.DELETE,
                Constants.NOT_A_COORDINATE,
                Constants.NOT_A_COORDINATE,
                false
            )
        }

        keyEnter.setOnClickListener {
            pauseVoiceInput()
            keyboardActionListener?.onCodeInput(
                Constants.CODE_ENTER,
                Constants.NOT_A_COORDINATE,
                Constants.NOT_A_COORDINATE,
                false
            )
        }

        streamingText.setOnClickListener {
            if (!VoiceModelManager.hasActiveModel(context)) {
                val intent = Intent(context, SettingsActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                return@setOnClickListener
            }
            if (isVoicePaused) {
                resumeVoiceInput()
            } else {
                pauseVoiceInput()
            }
        }

        gainPill.setOnClickListener {
            cycleGain()
        }
    }

    private fun cycleGain() {
        currentGainMultiplier = when (currentGainMultiplier) {
            1 -> 2
            2 -> 4
            else -> 1
        }
        context.prefs().edit().putInt(Settings.PREF_VOICE_INPUT_GAIN, currentGainMultiplier).apply()
        gainPill.text = "${currentGainMultiplier}x"
        voiceConnection?.setGain(currentGainMultiplier)
    }

    fun startVoiceInput(actionListener: KeyboardActionListener) {
        this.keyboardActionListener = actionListener
        val colors = Settings.getValues().mColors
        colors.setBackground(this, ColorType.MAIN_BACKGROUND)

        val keyboardViewAttr = context.obtainStyledAttributes(
            null, R.styleable.KeyboardView, R.attr.keyboardStyle, R.style.KeyboardView
        )
        val keyBgId = keyboardViewAttr.getResourceId(R.styleable.KeyboardView_keyBackground, 0)
        keyboardViewAttr.recycle()

        if (keyBgId != 0) {
            keyAbc.setBackgroundResource(keyBgId)
            keySpace.setBackgroundResource(keyBgId)
            keyBackspace.setBackgroundResource(keyBgId)
            keyEnter.setBackgroundResource(keyBgId)
            gainPill.setBackgroundResource(keyBgId)
        }

        colors.setBackground(keyAbc, ColorType.KEY_BACKGROUND)
        keyAbc.setTextColor(colors.get(ColorType.KEY_TEXT))
        KeyboardTypeface.applyToTextView(keyAbc)

        colors.setBackground(keySpace, ColorType.SPACE_BAR_BACKGROUND)
        keySpace.setTextColor(colors.get(ColorType.KEY_TEXT))
        KeyboardTypeface.applyToTextView(keySpace)

        val iconsSet = KeyboardIconsSet.instance
        iconsSet.loadIcons(context)

        colors.setBackground(keyBackspace, ColorType.FUNCTIONAL_KEY_BACKGROUND)
        val deleteDrawable = iconsSet.getNewDrawable(KeyboardIconsSet.NAME_DELETE_KEY, context)
        keyBackspace.setImageDrawable(deleteDrawable)
        colors.setColor(keyBackspace, ColorType.KEY_ICON)

        colors.setBackground(keyEnter, ColorType.ACTION_KEY_BACKGROUND)
        val enterDrawable = iconsSet.getNewDrawable(KeyboardIconsSet.NAME_ENTER_KEY, context)
        keyEnter.setImageDrawable(enterDrawable)
        colors.setColor(keyEnter, ColorType.ACTION_KEY_ICON)

        colors.setBackground(gainPill, ColorType.FUNCTIONAL_KEY_BACKGROUND)
        gainPill.setTextColor(colors.get(ColorType.KEY_TEXT))
        KeyboardTypeface.applyToTextView(gainPill)

        streamingText.setTextColor(colors.get(ColorType.KEY_TEXT))
        KeyboardTypeface.applyToTextView(streamingText)

        divider.setBackgroundColor(colors.get(ColorType.KEY_HINT_TEXT))

        currentGainMultiplier = context.prefs().getInt(Settings.PREF_VOICE_INPUT_GAIN, 1)
        if (currentGainMultiplier !in listOf(1, 2, 4)) currentGainMultiplier = 1
        gainPill.text = "${currentGainMultiplier}x"

        if (!VoiceModelManager.hasActiveModel(context)) {
            pulseView.pulseState = VoicePulseView.PulseState.ERROR
            streamingText.setText(R.string.voice_status_no_model)
            isVoiceActive = false
            isVoicePaused = false
            return
        }

        isVoiceActive = true
        isVoicePaused = false
        pulseView.pulseState = VoicePulseView.PulseState.LISTENING
        streamingText.setText(R.string.voice_status_listening)

        if (voiceConnection == null) {
            voiceConnection = VoiceInputConnection(context.applicationContext, connectionListener)
        }
        voiceConnection?.connectAndStart(currentGainMultiplier)
    }

    fun pauseVoiceInput() {
        if (!isVoiceActive || isVoicePaused) return
        isVoicePaused = true
        pulseView.pulseState = VoicePulseView.PulseState.PAUSED
        streamingText.setText(R.string.voice_status_paused)
        voiceConnection?.pause()
    }

    fun resumeVoiceInput() {
        if (!isVoiceActive || !isVoicePaused) return
        isVoicePaused = false
        pulseView.pulseState = VoicePulseView.PulseState.LISTENING
        streamingText.setText(R.string.voice_status_listening)
        voiceConnection?.resume()
    }

    fun updateStreamingText(text: String) {
        if (!isVoiceActive || isVoicePaused) return
        streamingText.text = text
    }

    fun updateRms(rms: Float) {
        if (!isVoiceActive || isVoicePaused) return
        pulseView.setRms(rms)
    }

    fun stopVoiceInput() {
        isVoiceActive = false
        isVoicePaused = false
        voiceConnection?.stopAndDisconnect()
        voiceConnection = null
        pulseView.release()
        keyboardActionListener = null
        visibility = GONE
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val keyboardWidth = ResourceUtils.getKeyboardWidth(context, Settings.getValues()) + paddingLeft + paddingRight
        val density = context.resources.displayMetrics.density
        val compactModalHeight = (150 * density).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(keyboardWidth, compactModalHeight)
    }
}
