package com.pocketds.hub.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable

/**
 * A controller button as it appears in the hint bar: a small circle with its
 * letter, or a pill for Start, Select and the shoulders.
 *
 * A is the only filled one, in the accent, and it always matches whatever looks
 * primary on screen, so the eye goes from the button on the page to the button
 * on the pad without reading.
 */
class KeyGlyphDrawable(
    private val colors: PocketColors,
    glyph: String,
    private val sizePx: Int,
    typeface: Typeface
) : Drawable() {
    val label: String = LETTERS[glyph] ?: glyph
    private val primary = label == "A"
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
        fill.color = if (primary) colors.accent else (colors.primaryText and 0x00FFFFFF) or 0x24000000
        canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, fill)
        text.color = if (primary) colors.accentText else colors.primaryText
        val baseline = rect.centerY() - (text.descent() + text.ascent()) / 2
        canvas.drawText(label, rect.centerX(), baseline, text)
    }

    override fun setAlpha(alpha: Int) { fill.alpha = alpha; text.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { fill.colorFilter = filter; text.colorFilter = filter; invalidateSelf() }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        private val LETTERS = mapOf("Ⓐ" to "A", "Ⓑ" to "B", "Ⓧ" to "X", "Ⓨ" to "Y", "⟳" to "Select", "⏵" to "Start")
    }
}
