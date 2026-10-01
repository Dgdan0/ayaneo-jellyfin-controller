package com.pocketds.hub.playback

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.widget.SeekBar
import com.pocketds.hub.ui.Styler

/**
 * The player's timeline with a small notch where each chapter starts, so you
 * can see where the opening ends or the credits begin before you jump.
 */
class ChapterSeekBar(context: Context) : SeekBar(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 10, 13, 18) }

    /** Chapter starts as fractions of the video, 0..1. The first, at 0, draws nothing. */
    var marks: List<Float> = emptyList()
        set(value) { field = value.filter { it > 0.002f && it < 0.998f }; invalidate() }

    @Synchronized
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (marks.isEmpty()) return
        val left = paddingLeft.toFloat()
        val track = (width - paddingLeft - paddingRight).toFloat()
        val half = Styler.dp(context, 1f)
        val reach = Styler.dp(context, 5f)
        val middle = height / 2f
        marks.forEach { fraction ->
            val x = left + track * fraction
            canvas.drawRect(x - half, middle - reach, x + half, middle + reach, paint)
        }
    }
}
