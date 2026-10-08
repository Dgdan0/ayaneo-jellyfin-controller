package com.pocketds.hub.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/**
 * The finished tick a cover carries (#54): the accent's circle and a check in its ink, 22dp, 6dp in from the
 * top right corner of whatever it is laid over. One drawing for a book in a series' row ([DetailArtworkCardView]'s
 * marks) and for the front cover of a finished series' fan ([CoverFanView]).
 */
object FinishedTick {
    private const val SIZE_DP = 22f
    private const val INSET_DP = 6f
    private const val PAD_DP = 5f

    fun drawable(colors: PocketColors, density: Float): Drawable = TickDrawable(colors, density)

    private class TickDrawable(private val colors: PocketColors, private val density: Float) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val check = AppIconDrawable(AppIcon.CHECK, colors.accentText)

        override fun draw(canvas: Canvas) {
            val size = SIZE_DP * density
            val inset = INSET_DP * density
            val cx = bounds.right - inset - size / 2
            val cy = bounds.top + inset + size / 2
            paint.color = colors.accent
            canvas.drawCircle(cx, cy, size / 2, paint)
            val pad = (PAD_DP * density).toInt()
            check.setBounds((cx - size / 2).toInt() + pad, (cy - size / 2).toInt() + pad, (cx + size / 2).toInt() - pad, (cy + size / 2).toInt() - pad)
            check.draw(canvas)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
