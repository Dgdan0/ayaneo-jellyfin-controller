package com.pocketds.hub.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable

/**
 * A small number on the corner of a round button (#48): Keep ready's N on the series page's Download. Drawn over the
 * button as its foreground, in the accent with the ink that goes on it, so it reads on glass and on the white face.
 */
class CountBadgeDrawable(context: Context, colors: PocketColors, val count: Int) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.accent }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colors.accentText; textAlign = Paint.Align.CENTER; textSize = 9.5f * density
        typeface = Type.text(context, 800)
    }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC10131A.toInt() }

    override fun draw(canvas: Canvas) {
        val radius = 8f * density
        val bounds: Rect = bounds
        val cx = bounds.right - radius - 1f * density
        val cy = bounds.top + radius + 1f * density
        canvas.drawCircle(cx, cy, radius + 1.5f * density, rim)
        canvas.drawCircle(cx, cy, radius, disc)
        canvas.drawText(count.toString(), cx, cy - (ink.ascent() + ink.descent()) / 2f, ink)
    }

    override fun setAlpha(alpha: Int) { disc.alpha = alpha; ink.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { disc.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
