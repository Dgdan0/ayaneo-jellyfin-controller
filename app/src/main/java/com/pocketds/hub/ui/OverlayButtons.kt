package com.pocketds.hub.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.widget.ImageView
import android.widget.TextView

/**
 * Controls drawn over a picture -- the player's and the comic reader's: white
 * on a soft translucent disc or pill, and when focused a ring standing a few
 * dp outside the shape, so it shows round a white disc and over bright art
 * alike. The player had these to itself; the reader's square glyph buttons
 * were the last of the old look.
 */
object OverlayButtons {
    private val SOFT = Color.argb(46, 255, 255, 255)
    private val PRESSED = Color.argb(90, 255, 255, 255)

    /** A round soft disc: Back, Cast, Lock, a page or issue step. */
    fun disc(context: Context, ring: Int): StateListDrawable = ringed(context, ring, GradientDrawable.OVAL, SOFT)

    /** A soft pill: a tool named in words. */
    fun pillFace(context: Context, ring: Int): StateListDrawable = ringed(context, ring, GradientDrawable.RECTANGLE, SOFT)

    fun ringed(context: Context, ring: Int, shape: Int, fill: Int, pressed: Int = PRESSED): StateListDrawable {
        val gap = Styler.dpInt(context, 4f)
        fun shaped(color: Int, stroke: Boolean = false) = ThemeGradientDrawable().apply {
            this.shape = shape
            if (shape == GradientDrawable.RECTANGLE) cornerRadius = Styler.dp(context, 999f)
            setColor(color)
            if (stroke) setStroke(Styler.dpInt(context, 2f), ring)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), InsetDrawable(shaped(pressed), gap))
            addState(intArrayOf(android.R.attr.state_focused),
                LayerDrawable(arrayOf(shaped(Color.TRANSPARENT, stroke = true), InsetDrawable(shaped(fill), gap))))
            addState(intArrayOf(), InsetDrawable(shaped(fill), gap))
        }
    }

    /** "Display", "Chapters": what a tool opens, in words. */
    fun pill(context: Context, ring: Int, label: String, description: String = label, onTap: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            contentDescription = description
            textSize = 12f
            typeface = Type.text(context, 600)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(Styler.dpInt(context, 16f), 0, Styler.dpInt(context, 16f), 0)
            background = pillFace(context, ring)
            Styler.makeFocusable(this)
            activateOnTap(onTap)
        }

    /** A round button with one of the app's icons, white, in a soft disc. */
    fun round(context: Context, ring: Int, icon: AppIcon, description: String, onTap: () -> Unit): ImageView =
        ImageView(context).apply {
            setImageDrawable(AppIconDrawable(icon, Color.WHITE))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = Styler.dpInt(context, 12f)
            setPadding(pad, pad, pad, pad)
            contentDescription = description
            background = disc(context, ring)
            Styler.makeFocusable(this)
            activateOnTap(onTap)
        }
}
