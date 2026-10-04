package com.pocketds.hub.ui.glass

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.view.View
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.TopChrome

/**
 * Glass: a scrolled page's words fade out at its top edge instead of running
 * on under the top bar, where "85%" sat beside the L1 cap and the facts ran
 * behind the tabs. Where the bar reaches over the page ([TopChrome.overlap])
 * they are gone; below it they come back over [RAMP_DP]. A page below the bar
 * fades over the ramp alone. Nothing fades while the page is at its top, so a
 * title's backdrop still runs under the bar at rest, and the fade grows with
 * the first scroll rather than snapping in.
 *
 * The page draws twice: below the band as usual, and the band alone in a
 * layer with the fade cut out of it, so only a strip the bar's height is ever
 * offscreen.
 */
class TopFade(private val view: View) {
    private val paint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    private var overlap = 0
    private var shaderKey = -1L

    /** How far the top bar reaches over the page, read again whenever the page is laid out. */
    fun measure() {
        overlap = TopChrome.overlap(view)
    }

    /** Draws the page through [drawAll], faded at the top once it has scrolled. */
    fun draw(canvas: Canvas, scrollY: Int, drawAll: (Canvas) -> Unit) {
        val band = overlap + Styler.dpInt(view.context, RAMP_DP)
        if (scrollY <= 0 || view.width <= 0 || view.height <= band) {
            drawAll(canvas)
            return
        }
        val top = scrollY.toFloat()
        val width = view.width.toFloat()
        var save = canvas.save()
        canvas.clipRect(0f, top + band, width, top + view.height)
        drawAll(canvas)
        canvas.restoreToCount(save)

        save = canvas.saveLayer(0f, top, width, top + band, null)
        drawAll(canvas)
        val key = band.toLong() shl 32 or overlap.toLong()
        if (key != shaderKey) {
            shaderKey = key
            paint.shader = LinearGradient(0f, 0f, 0f, band.toFloat(),
                intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT),
                floatArrayOf(0f, overlap / band.toFloat(), 1f), Shader.TileMode.CLAMP)
        }
        paint.alpha = (255 * (scrollY / band.toFloat()).coerceAtMost(1f)).toInt()
        canvas.translate(0f, top)
        canvas.drawRect(0f, 0f, width, band.toFloat(), paint)
        canvas.restoreToCount(save)
    }

    companion object {
        /** How far below the bar (or the page's top) the words take to come back. */
        const val RAMP_DP = 22f
    }
}
