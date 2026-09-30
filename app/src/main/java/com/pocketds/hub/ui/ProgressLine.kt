package com.pocketds.hub.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.widget.ProgressBar

/**
 * The thin bar along the bottom of a poster or still: how much has been
 * watched, or downloaded.
 *
 * Poster and landscape cards each sized a coloured View by hand once the
 * artwork had been measured, and re-posted the calculation because a
 * RecyclerView binds before layout. Drawing the fraction here needs no
 * measurement at bind time at all.
 */
class ArtworkProgressView(context: Context, color: Int) : View(context) {
    private val paint = Paint().apply { this.color = color }

    /** 0..1. Nothing to show hides the bar. */
    var fraction: Double = 0.0
        set(value) {
            field = value.coerceIn(0.0, 1.0)
            visibility = if (field > 0.0) VISIBLE else GONE
            invalidate()
        }

    init {
        visibility = GONE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        // Never thinner than the bar is tall, so 1% still reads as started.
        val filled = (width * fraction).toFloat().coerceAtLeast(height.toFloat())
        canvas.drawRect(0f, 0f, filled, height.toFloat(), paint)
    }
}

/**
 * The progress line under a title: accent on the pressed-card track, or a
 * caller's state colour. Two of these were left untinted, so an episode strip
 * drew the platform's default colour next to the app's own everywhere else.
 */
object ProgressLine {
    const val MAX = 1_000

    fun create(context: Context, colors: PocketColors, color: Int = colors.accent): ProgressBar =
        ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = MAX
            progressTintList = ColorStateList.valueOf(color)
            progressBackgroundTintList = ColorStateList.valueOf(colors.cardSurfacePressed)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

    /** 0..1; nothing to show hides the line. */
    fun ProgressBar.showFraction(fraction: Double) {
        val value = fraction.coerceIn(0.0, 1.0)
        progress = (value * MAX).toInt()
        visibility = if (value > 0.0) View.VISIBLE else View.GONE
    }
}
