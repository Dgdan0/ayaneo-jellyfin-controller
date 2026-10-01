package com.pocketds.hub.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * A fade from the page colour into artwork, so text can sit on a poster.
 *
 * [stops] are (position, alpha) pairs along the fade, from its solid edge. The
 * colour is read from the palette at draw time, so a theme change repaints it.
 */
class ScrimDrawable(
    private val colors: PocketColors,
    private val edge: Edge,
    private val stops: List<Pair<Float, Float>>
) : Drawable() {
    enum class Edge { LEFT, BOTTOM, TOP }

    private val paint = Paint()
    private var shaderFor = 0L

    override fun draw(canvas: Canvas) {
        val b = bounds
        val key = (b.width().toLong() shl 32) or (b.height().toLong() shl 8) or colors.background.toLong().and(0xFF)
        if (key != shaderFor || paint.shader == null) {
            shaderFor = key
            val rgb = colors.background and 0x00FFFFFF
            val colorsAt = stops.map { (_, alpha) -> rgb or ((alpha * 255).toInt().coerceIn(0, 255) shl 24) }.toIntArray()
            val positions = stops.map { it.first }.toFloatArray()
            paint.shader = when (edge) {
                Edge.LEFT -> LinearGradient(b.left.toFloat(), 0f, b.right.toFloat(), 0f, colorsAt, positions, Shader.TileMode.CLAMP)
                Edge.BOTTOM -> LinearGradient(0f, b.bottom.toFloat(), 0f, b.top.toFloat(), colorsAt, positions, Shader.TileMode.CLAMP)
                Edge.TOP -> LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(), colorsAt, positions, Shader.TileMode.CLAMP)
            }
        }
        canvas.drawRect(b, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
