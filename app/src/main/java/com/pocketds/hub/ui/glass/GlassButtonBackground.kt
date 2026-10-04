package com.pocketds.hub.ui.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import com.pocketds.hub.ui.KeyPressTint
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.ThemeGradientDrawable

/**
 * A Glass button's background (GLASS_PLAN.md): the page's glass, or white
 * while [lit] -- the main action, a toggle that is on, a filter that is on --
 * inside room for the focus ring, which stands outside it with a gap as on
 * every pill.
 *
 * Lighting it swaps the face in place. A new background each time (a filter
 * pill turning on and off) reset the view's padding and registered another
 * GlassPage.follow; this keeps the one [attach] registered.
 *
 * Over a picture ([overPicture]: the player, a reader) the glass is the
 * nearly solid [GlassColors.overPicture], laid on the video palette's dark.
 * A lit face is white, or what [litColor] gives: the Books side's main
 * action is its accent (PillButton.mainFace).
 */
class GlassButtonBackground private constructor(
    context: Context,
    focusRing: Int,
    cornerPx: Float,
    ringPx: Int,
    lit: Boolean,
    pictureBase: Int? = null,
    litColor: () -> Int = { Color.WHITE }
) : StateListDrawable() {
    private val page = GlassPage.palette(context)
    private val faces = listOf(Face(cornerPx, true, page, pictureBase, litColor), Face(cornerPx, false, page, pictureBase, litColor),
        Face(cornerPx, false, page, pictureBase, litColor))

    var lit: Boolean = lit
        set(value) {
            if (field == value) return
            field = value
            faces.forEach { it.lit = value }
            invalidateSelf()
        }

    init {
        faces.forEach { it.lit = lit }
        val outline = ThemeGradientDrawable.rounded(cornerPx + ringPx, Color.TRANSPARENT, Styler.dpInt(context, 2f), focusRing)
        addState(intArrayOf(android.R.attr.state_pressed), InsetDrawable(faces[0], ringPx))
        addState(intArrayOf(android.R.attr.state_focused), LayerDrawable(arrayOf(outline, InsetDrawable(faces[1], ringPx))))
        addState(intArrayOf(), InsetDrawable(faces[2], ringPx))
    }

    fun retint(page: ArtworkPalette) {
        faces.forEach { it.retint(page) }
        invalidateSelf()
    }

    companion object {
        /**
         * Sets it as [view]'s background, following the page while the view is
         * shown. [cornerPx] past half the height draws a pill or a circle.
         */
        fun attach(view: View, colors: PocketColors, cornerPx: Float, ringPx: Int, lit: Boolean,
                   litColor: () -> Int = { Color.WHITE }): GlassButtonBackground {
            val background = GlassButtonBackground(view.context, colors.focusRing, cornerPx, ringPx, lit, litColor = litColor)
            view.background = background
            GlassPage.follow(view) { page -> background.retint(page) }
            return background
        }

        /**
         * A control over video or a page being read: the page's tint of what
         * is open, nearly solid on [base] (the video palette's dark) so it
         * reads over any frame; white while [lit]. Follows the page as
         * [attach] does once [view] is given one.
         */
        fun overPicture(view: View, focusRing: Int, cornerPx: Float, ringPx: Int, base: Int, lit: Boolean = false): GlassButtonBackground {
            val background = GlassButtonBackground(view.context, focusRing, cornerPx, ringPx, lit, base)
            view.background = background
            GlassPage.follow(view) { page -> background.retint(page) }
            return background
        }
    }

    /** One state's face: glass, or white while lit; pressed is a touch lighter glass or greyer white. */
    private class Face(private val cornerPx: Float, private val pressed: Boolean, page: ArtworkPalette,
                       private val pictureBase: Int?, private val litColor: () -> Int) : Drawable() {
        var lit = false
            set(value) { field = value; invalidateSelf() }
        private val panel = GlassPanelDrawable(glass(page), cornerPx)
        private val white = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        fun retint(page: ArtworkPalette) = panel.retint(glass(page))

        private fun glass(page: ArtworkPalette): Int =
            (if (pictureBase != null) GlassColors.overPicture(page, pictureBase) else GlassColors.panel(page))
                .let { if (pressed) KeyPressTint.pressed(it, true) else it }

        override fun onBoundsChange(bounds: Rect) {
            panel.bounds = bounds
        }

        override fun draw(canvas: Canvas) {
            if (!lit) {
                panel.draw(canvas)
                return
            }
            rect.set(bounds)
            val r = cornerPx.coerceAtMost(rect.height() / 2f)
            // Read when drawn: the Books accent can change under a screen that stays.
            val face = litColor()
            white.color = if (pressed) KeyPressTint.pressed(face, false) else face
            canvas.drawRoundRect(rect, r, r, white)
        }

        override fun setAlpha(alpha: Int) {
            white.alpha = alpha
            panel.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            white.colorFilter = colorFilter
            panel.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
