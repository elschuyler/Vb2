// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

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

    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
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

    // Color definitions
    private val colorListening = Color.parseColor("#34A853") // Soft green
    private val colorAmbient = Color.parseColor("#4285F4")   // Soft blue
    private val colorPaused = Color.parseColor("#FBBC05")    // Amber / Yellow
    private val colorError = Color.parseColor("#EA4335")     // Red
    private val colorIdle = Color.parseColor("#9AA0A6")      // Neutral gray

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
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { animator ->
                    pulseFraction = animator.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            pulseFraction = 0f
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val maxRadius = (minOf(width, height) / 2f) * 0.85f

        val (primaryColor, showRing) = when (pulseState) {
            PulseState.LISTENING -> {
                val activeColor = if (currentRms > 0.15f) colorListening else colorAmbient
                activeColor to true
            }
            PulseState.PAUSED -> colorPaused to false
            PulseState.ERROR -> colorError to false
            PulseState.IDLE -> colorIdle to false
        }

        innerPaint.color = primaryColor

        if (showRing) {
            val ringRadius = maxRadius * (0.6f + 0.4f * maxOf(pulseFraction, currentRms))
            val alpha = ((1f - pulseFraction * 0.7f) * 200).toInt().coerceIn(30, 220)
            ringPaint.color = primaryColor
            ringPaint.alpha = alpha
            canvas.drawCircle(cx, cy, ringRadius, ringPaint)
        }

        val baseInnerRadius = maxRadius * 0.45f
        val innerRadius = if (pulseState == PulseState.LISTENING) {
            baseInnerRadius + (maxRadius * 0.2f * currentRms)
        } else {
            baseInnerRadius
        }
        canvas.drawCircle(cx, cy, innerRadius, innerPaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulseAnimator?.cancel()
        pulseAnimator = null
    }

    fun release() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        pulseState = PulseState.IDLE
    }
}
