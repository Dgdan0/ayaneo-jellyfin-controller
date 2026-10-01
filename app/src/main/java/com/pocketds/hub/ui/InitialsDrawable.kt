package com.pocketds.hub.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/**
 * A person without a portrait: their initials, centred on a tinted square.
 * Author posters use it so a grid of authors stays a grid of faces and letters
 * rather than empty boxes.
 */
class InitialsDrawable(name: String, private val colors: PocketColors) : Drawable() {
    private val initials = name.split(' ', '-').filter { it.isNotBlank() }.take(2)
        .joinToString("") { it.first().uppercase() }.ifEmpty { "?" }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.focusFill }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colors.accent
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    override fun draw(canvas: Canvas) {
        canvas.drawRect(bounds, fill)
        text.textSize = bounds.width() * 0.34f
        val baseline = bounds.exactCenterY() - (text.descent() + text.ascent()) / 2
        canvas.drawText(initials, bounds.exactCenterX(), baseline, text)
    }

    override fun setAlpha(alpha: Int) { fill.alpha = alpha; text.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { text.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
