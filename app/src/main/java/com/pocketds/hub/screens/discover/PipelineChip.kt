package com.pocketds.hub.screens.discover

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.pocketds.hub.model.Stage
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.ThemeGradientDrawable
import com.pocketds.hub.ui.glass.GlassPanelDrawable
import com.pocketds.hub.ui.textWeight
import java.util.Locale

/**
 * One stage of a title's pipeline as a Glass chip (the prototype's `.stage`):
 * a dot and a word on the page's glass. A stage that is done is lit in the
 * accent; the one under way is amber, its dot pulsing and its percentage
 * beside it; a failed or stuck one is red; the rest wait in grey.
 */
class PipelineChip(context: Context, colors: PocketColors, stage: Stage) : LinearLayout(context) {

    enum class Tone {
        DONE, ACTIVE, FAILED, PENDING;

        companion object {
            /** The hub's stage states; anything else (pending, unknown) waits. */
            fun of(state: String): Tone = when (state) {
                "done" -> DONE
                "active" -> ACTIVE
                "failed", "stuck" -> FAILED
                else -> PENDING
            }
        }
    }

    private val halo: View?
    private var pulse: ValueAnimator? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // The pulse swells into the chip's padding; clipped to the padding it was a D.
        clipChildren = false
        clipToPadding = false
        val tone = Tone.of(stage.state)
        setPadding(dp(10), dp(6), dp(11), dp(6))
        val panel = GlassPanelDrawable.attach(this, dp(999).toFloat())
        when (tone) {
            Tone.ACTIVE -> panel.edge(ACTIVE, dp(1).toFloat())
            Tone.FAILED -> panel.edge(FAILED, dp(1).toFloat())
            else -> Unit
        }

        val dotColour = when (tone) {
            Tone.DONE -> colors.accent
            Tone.ACTIVE -> ACTIVE
            Tone.FAILED -> FAILED
            Tone.PENDING -> PENDING_DOT
        }
        halo = if (tone == Tone.ACTIVE) View(context).apply {
            background = ThemeGradientDrawable.oval(HALO)
            scaleX = HALO_REST
            scaleY = HALO_REST
        } else null
        addView(FrameLayout(context).apply {
            clipChildren = false
            // Centred on the dot and larger than it: it swells into the chip's padding.
            halo?.let { addView(it, FrameLayout.LayoutParams(dp(HALO_DP), dp(HALO_DP), Gravity.CENTER)) }
            addView(View(context).apply { background = ThemeGradientDrawable.oval(dotColour) }, FrameLayout.LayoutParams(dp(DOT_DP), dp(DOT_DP), Gravity.CENTER))
        }, LayoutParams(dp(DOT_DP), dp(DOT_DP)).apply { marginEnd = dp(7) })

        val label = stage.compactLabel
        addView(TextView(context).apply {
            text = if (tone == Tone.ACTIVE && stage.progress > 0) label + " " + String.format(Locale.US, "%.0f%%", stage.progress * 100) else label
            textSize = 11f
            textWeight(700)
            includeFontPadding = false
            setTextColor(if (tone == Tone.PENDING) PENDING_TEXT else Color.WHITE)
        })
        contentDescription = label + ", " + when (tone) {
            Tone.DONE -> "done"
            Tone.ACTIVE -> "under way"
            Tone.FAILED -> "needs attention"
            Tone.PENDING -> "not yet"
        }
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
    }

    /** The prototype's pulse: a ring of amber swelling 5dp round the dot and back, every 1.4s, only while shown. */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val ring = halo ?: return
        if (!ValueAnimator.areAnimatorsEnabled()) return
        pulse = ObjectAnimator.ofPropertyValuesHolder(ring,
            PropertyValuesHolder.ofFloat(View.SCALE_X, HALO_REST, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, HALO_REST, 1f)).apply {
            duration = PULSE_MS / 2
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    override fun onDetachedFromWindow() {
        pulse?.cancel()
        pulse = null
        super.onDetachedFromWindow()
    }

    private fun dp(value: Int) = Styler.dpInt(context, value.toFloat())

    companion object {
        /** The prototype's amber for the stage under way (#F2B544), and its red for a stage that failed. */
        const val ACTIVE = 0xFFF2B544.toInt()
        const val FAILED = 0xFFFF5A5F.toInt()
        /** The summary under the chips while a stage is under way (#F5C75A). */
        const val SUMMARY = 0xFFF5C75A.toInt()
        private const val PENDING_DOT = 0x4DFFFFFF
        private const val PENDING_TEXT = 0x8CFFFFFF.toInt()
        /** The pulse's ring: the amber at 28%. */
        private const val HALO = 0x47F2B544
        private const val DOT_DP = 9
        private const val HALO_DP = 19
        /** At rest the ring is the dot's own size, hidden behind it. */
        private const val HALO_REST = 9f / 19f
        private const val PULSE_MS = 1400L
    }
}
