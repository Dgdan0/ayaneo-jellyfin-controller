package com.pocketds.hub.ui.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.widget.ImageView

/**
 * Artwork that fades into the Glass page rather than into a colour: its
 * opacity follows [stops] down its height, so where it thins out the ambient
 * layer behind shows through (GLASS_PLAN.md: "fades through a mask, not a
 * solid gradient"). Fading to a colour ended Home's hero in a band of
 * near-black, and a hard edge where the page's own colours began under it.
 *
 * The view draws into a hardware layer of its own and the mask is applied
 * inside it, so it thins only this picture and not what lies behind. The
 * layer is kept until the picture changes, so the mask costs nothing while
 * the page sits still.
 *
 * A [shade] for the words over the picture is drawn in the same layer, so it
 * thins out with the picture: a separate one stopped short at the view's foot
 * and left a hard line across the page below.
 */
class FadedImageView(context: Context) : ImageView(context) {

    /**
     * (position down the view from 0 to 1, opacity from 0 to 1), in order, as
     * ScrimDrawable takes its stops. Solid by default.
     */
    var stops: List<Pair<Float, Float>> = listOf(0f to 1f, 1f to 1f)
        set(value) {
            field = value
            maskHeight = -1
            invalidate()
        }

    /**
     * Black over the picture from its left edge: (position across from 0 to
     * 1, opacity from 0 to 1). None by default.
     */
    var shade: List<Pair<Float, Float>> = emptyList()
        set(value) {
            field = value
            shadeWidth = -1
            invalidate()
        }

    private val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private var maskHeight = -1
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shadeWidth = -1

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (height <= 0 || drawable == null) return
        if (shade.isNotEmpty()) {
            if (shadeWidth != width) {
                shadeWidth = width
                shadePaint.shader = LinearGradient(0f, 0f, width.toFloat(), 0f,
                    shade.map { (_, opacity) -> (opacity.coerceIn(0f, 1f) * 255).toInt() shl 24 }.toIntArray(),
                    shade.map { it.first }.toFloatArray(), Shader.TileMode.CLAMP)
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shadePaint)
        }
        if (maskHeight != height) {
            maskHeight = height
            mask.shader = LinearGradient(0f, 0f, 0f, height.toFloat(),
                stops.map { (_, opacity) -> (opacity.coerceIn(0f, 1f) * 255).toInt() shl 24 }.toIntArray(),
                stops.map { it.first }.toFloatArray(), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), mask)
    }

    companion object {
        /** Home's hero, as the prototype masks it: solid to 40% of the way down, gone by 97%. */
        val HERO = listOf(0f to 1f, 0.40f to 1f, 0.97f to 0f, 1f to 0f)
        /** The prototype's shade for the hero's words: half black at the left, gone by 62% across. */
        val HERO_SHADE = listOf(0f to 0.5f, 0.62f to 0f)
        /** A title page's backdrop (`.dart`): solid to 30% of the way down, gone by 94%. */
        val TITLE = listOf(0f to 1f, 0.30f to 1f, 0.94f to 0f, 1f to 0f)
        /** Its shade for the words: 60% black at the left, gone by 72% across. */
        val TITLE_SHADE = listOf(0f to 0.6f, 0.72f to 0f)
    }
}
