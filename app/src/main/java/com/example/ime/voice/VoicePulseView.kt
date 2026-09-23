package com.example.ime.voice

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.sin

/**
 * Real-time reactive waveform pulse visualizer for Voice Input.
 * Draws a 9-bar dynamic equalizer soundwave with rounded capsule bars
 * that dance and expand vertically in direct response to live microphone audio energy (RMS).
 *
 * States:
 * - LISTENING: Electric Cyan (#00E5FF / #38BDF8) active waveform dancing to audio
 * - PAUSED: Warm Amber (#F59E0B) steady indicator
 * - ERROR: Vivid Red (#EF4444) error indicator
 * - IDLE: Muted Slate (#64748B) resting baseline
 */
class VoicePulseView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class PulseState {
        IDLE,
        LISTENING,
        PAUSED,
        ERROR
    }

    companion object {
        private const val BAR_COUNT = 9
    }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    var pulseState: PulseState = PulseState.IDLE
        set(value) {
            if (field != value) {
                field = value
                updateAnimationState()
                invalidate()
            }
        }

    private var pulseFraction: Float = 0f
    private var pulseAnimator: ValueAnimator? = null
    private var currentRms: Float = 0f

    // Current and target heights for smooth physics easing per bar
    private val currentHeights = FloatArray(BAR_COUNT) { 4f }

    // Color palette matching modern audio pulse interfaces
    private val colorListening = Color.parseColor("#00E5FF") // Electric Cyan
    private val colorListeningAccent = Color.parseColor("#38BDF8") // Vibrant Sky Blue
    private val colorPaused = Color.parseColor("#F59E0B")    // Amber
    private val colorError = Color.parseColor("#EF4444")     // Red
    private val colorIdle = Color.parseColor("#64748B")      // Slate

    init {
        updateAnimationState()
    }

    fun setRms(rms: Float) {
        currentRms = rms.coerceIn(0f, 1f)
        if (pulseState == PulseState.LISTENING) {
            invalidate()
        }
    }

    private fun updateAnimationState() {
        pulseAnimator?.cancel()
        pulseAnimator = null

        if (pulseState == PulseState.LISTENING) {
            pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 900L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = LinearInterpolator()
                addUpdateListener { animator ->
                    pulseFraction = animator.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            pulseFraction = 0f
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val density = resources.displayMetrics.density
        val minBarWidth = 3f * density
        val maxBarHeight = (h * 0.88f).coerceAtLeast(8f * density)
        val minHeight = 4f * density
        val cy = h / 2f

        // Calculate bar geometry
        val totalSpacingRatio = 0.45f
        val calculatedBarWidth = ((w * (1f - totalSpacingRatio)) / BAR_COUNT).coerceAtLeast(minBarWidth)
        val barWidth = minOf(calculatedBarWidth, 5.5f * density)
        val totalBarsWidth = BAR_COUNT * barWidth
        val availableGap = (w - totalBarsWidth) / (BAR_COUNT - 1).coerceAtLeast(1)
        val gap = availableGap.coerceAtLeast(2f * density)
        val startX = (w - (totalBarsWidth + gap * (BAR_COUNT - 1))) / 2f

        val activeColor = when (pulseState) {
            PulseState.LISTENING -> if (currentRms > 0.15f) colorListening else colorListeningAccent
            PulseState.PAUSED -> colorPaused
            PulseState.ERROR -> colorError
            PulseState.IDLE -> colorIdle
        }
        barPaint.color = activeColor

        for (i in 0 until BAR_COUNT) {
            val distFromCenter = abs(i - (BAR_COUNT - 1) / 2f) / ((BAR_COUNT - 1) / 2f)
            val envelope = (1f - 0.52f * distFromCenter).coerceIn(0.4f, 1f)

            val targetHeight: Float = when (pulseState) {
                PulseState.LISTENING -> {
                    // Gentle breathing wave when quiet
                    val idleWave = (sin(pulseFraction * 2 * Math.PI.toFloat() + i * 0.85f) * 0.5f + 0.5f)
                    val idleH = minHeight + (maxBarHeight * 0.18f * envelope * idleWave)

                    // Dynamic soundwave reaction boosted by RMS energy
                    val audioJitter = (sin(i * 1.35f + pulseFraction * 4.2f) * 0.35f + 0.65f)
                    val audioBoost = currentRms * (maxBarHeight * 0.82f) * envelope * audioJitter
                    (idleH + audioBoost).coerceIn(minHeight, maxBarHeight)
                }
                PulseState.PAUSED -> {
                    minHeight + (maxBarHeight * 0.12f * envelope)
                }
                PulseState.ERROR -> {
                    minHeight * 1.2f
                }
                PulseState.IDLE -> {
                    minHeight
                }
            }

            // Smooth spring easing
            currentHeights[i] += (targetHeight - currentHeights[i]) * 0.38f
            val currentH = currentHeights[i].coerceIn(minHeight, maxBarHeight)

            val left = startX + i * (barWidth + gap)
            val top = cy - currentH / 2f
            val right = left + barWidth
            val bottom = cy + currentH / 2f
            val cornerRadius = barWidth / 2f

            canvas.drawRoundRect(left, top, right, bottom, cornerRadius, cornerRadius, barPaint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        release()
    }

    fun release() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        pulseState = PulseState.IDLE
    }
}
