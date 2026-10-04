package com.pocketds.hub.ui.glass

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubEndpoints
import com.pocketds.hub.ui.Artwork

/**
 * The Glass page behind every screen: the focused artwork, small and blurred,
 * over its dark colour, with a veil that keeps type readable (GLASS_PLAN.md).
 *
 * The picture is decoded at about 64 pixels across and drawn scaled up with
 * filtering, then blurred once by RenderEffect on Android 12 and later. A
 * one-twentieth-size image filtered across the panel is already soft; the blur
 * only removes the last of its blockiness, and RenderNode keeps the result
 * until the picture changes, so the page costs nothing to keep on screen at
 * 120 Hz. Nothing here ever blurs live content.
 */
class AmbientLayerView(context: Context, private val api: HubApi) : FrameLayout(context) {

    private val base = View(context)
    private val layers = Array(2) {
        ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0f
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            // The prototype's saturate(1.5) brightness(0.62): the colour of the
            // artwork without the detail fighting the type above it.
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply {
                setSaturation(1.5f)
                postConcat(ColorMatrix().apply { setScale(0.62f, 0.62f, 0.62f, 1f) })
            })
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val radius = 18f * resources.displayMetrics.density
                setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
            }
        }
    }
    private val veil = View(context).apply {
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x24000000, 0x33000000, 0x99000000.toInt())
        )
    }
    private var front = 0
    private var shown: String? = null
    private var baseColor = ArtworkPalette.NEUTRAL.dark
    private var baseAnimator: ValueAnimator? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        base.setBackgroundColor(baseColor)
        addView(base, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        layers.forEach { addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }
        addView(veil, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /**
     * Shows [hubPath]'s artwork over [palette]'s dark colour. The same picture
     * again changes nothing; a new one cross-fades in once it has loaded, so a
     * slow image never leaves the page blank.
     */
    fun show(hubPath: String?, palette: ArtworkPalette = ArtworkPalette.NEUTRAL) {
        fadeBase(palette.dark)
        if (hubPath == shown) return
        shown = hubPath
        val next = layers[1 - front]
        next.animate().cancel()
        next.alpha = 0f
        if (hubPath.isNullOrBlank()) {
            layers[front].animate().alpha(0f).setDuration(FADE_MS).start()
            return
        }
        // The smallest size the hub serves; decoding tiny is the whole trick.
        Artwork.bindHub(next, api, HubEndpoints.sized(hubPath, 180), opaque = true) {
            size(DECODE_PX, DECODE_PX)
            crossfade(false)
            listener(onSuccess = { _, _ ->
                if (shown == hubPath) {
                    val old = layers[front]
                    next.animate().alpha(1f).setDuration(FADE_MS).start()
                    old.animate().alpha(0f).setDuration(FADE_MS).start()
                    front = 1 - front
                }
            })
        }
    }

    private fun fadeBase(target: Int) {
        if (target == baseColor) return
        baseAnimator?.cancel()
        baseAnimator = ValueAnimator.ofObject(ArgbEvaluator(), baseColor, target).apply {
            duration = FADE_MS
            addUpdateListener {
                baseColor = it.animatedValue as Int
                base.setBackgroundColor(baseColor)
            }
            start()
        }
    }

    companion object {
        /** The prototype's 0.6–0.8 s cross-fade. */
        const val FADE_MS = 700L
        private const val DECODE_PX = 64
    }
}
