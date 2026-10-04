package com.pocketds.hub.ui.glass

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View

/**
 * A Glass panel on the Pocket: a translucent tint of the artwork's colour with
 * a hairline edge and a faint light along the top, the way the prototype draws
 * its bars, side sheets and cards. A tint over the already blurred ambient
 * layer reads as frosted glass without blurring anything (GLASS_PLAN.md).
 *
 * [fill] can change in place (focus moving to other artwork re-tints every
 * panel); call [retint]. Most panels follow the page: [attach].
 */
class GlassPanelDrawable(
    fill: Int,
    private val radiusPx: Float,
    private val hairlinePx: Float = 1f
) : Drawable() {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = hairlinePx
        color = GlassColors.EDGE
    }

    /**
     * An edge that says something, such as the amber round the card that
     * needs attention, [widthPx] wide; null puts back the hairline.
     */
    fun edge(color: Int?, widthPx: Float = hairlinePx) {
        edgePaint.color = color ?: GlassColors.EDGE
        edgePaint.strokeWidth = if (color == null) hairlinePx else widthPx
        invalidateSelf()
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = hairlinePx
        color = GlassColors.HIGHLIGHT
    }
    private val rect = RectF()

    fun retint(fill: Int) {
        if (fillPaint.color == fill) return
        fillPaint.color = fill
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val half = edgePaint.strokeWidth / 2f
        rect.set(bounds.left + half, bounds.top + half, bounds.right - half, bounds.bottom - half)
        val r = radiusPx.coerceAtMost(rect.height() / 2f)
        canvas.drawRoundRect(rect, r, r, fillPaint)
        canvas.drawRoundRect(rect, r, r, edgePaint)
        // The light along the top: the same outline, clipped to the upper edge.
        val save = canvas.save()
        canvas.clipRect(rect.left, rect.top - half, rect.right, rect.top + r.coerceAtLeast(hairlinePx * 2))
        rect.offset(0f, hairlinePx)
        canvas.drawRoundRect(rect, r, r, highlightPaint)
        rect.offset(0f, -hairlinePx)
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        /**
         * Sets a panel of the page's glass as [view]'s background, re-tinted
         * each time the page is while the view is attached ([GlassPage.follow]).
         * [tint] picks which of the page's glasses: a panel, or a sheet's
         * nearly solid one.
         */
        fun attach(view: View, radiusPx: Float, tint: (ArtworkPalette) -> Int = GlassColors::panel): GlassPanelDrawable {
            val panel = GlassPanelDrawable(tint(GlassPage.palette(view.context)), radiusPx)
            view.background = panel
            GlassPage.follow(view) { page -> panel.retint(tint(page)) }
            return panel
        }
    }
}
