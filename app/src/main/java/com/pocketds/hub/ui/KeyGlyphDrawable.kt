package com.pocketds.hub.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import com.pocketds.hub.ui.glass.GlassColors

/**
 * A controller button as it appears in the hint bar: a white chip with a dark
 * letter, as the prototype draws them over the tinted bar -- round for a
 * letter, a rounded square for a longer name such as Select or L2 / R2.
 */
class KeyGlyphDrawable(
    glyph: String,
    private val sizePx: Int,
    typeface: Typeface
) : Drawable() {
    val label: String = LETTERS[glyph] ?: glyph
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        textSize = sizePx * if (label.length == 1) 0.56f else 0.5f
        textAlign = Paint.Align.CENTER
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val width = if (label.length == 1) sizePx else (text.measureText(label) + sizePx * 0.7f).toInt()

    override fun getIntrinsicWidth() = width
    override fun getIntrinsicHeight() = sizePx

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        val radius = if (label.length > 2) rect.height() * SQUARE_CORNER else rect.height() / 2
        fill.color = GlassColors.KEY_CAP
        canvas.drawRoundRect(rect, radius, radius, fill)
        text.color = GlassColors.INK
        val baseline = rect.centerY() - (text.descent() + text.ascent()) / 2
        canvas.drawText(label, rect.centerX(), baseline, text)
    }

    override fun setAlpha(alpha: Int) { fill.alpha = alpha; text.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { fill.colorFilter = filter; text.colorFilter = filter; invalidateSelf() }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        private val LETTERS = mapOf("Ⓐ" to "A", "Ⓑ" to "B", "Ⓧ" to "X", "Ⓨ" to "Y", "⟳" to "Select", "⏵" to "Start")
        /** The prototype's 4px corner on a 17px cap. */
        private const val SQUARE_CORNER = 4f / 17f
    }
}
