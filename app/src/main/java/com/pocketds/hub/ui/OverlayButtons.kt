package com.pocketds.hub.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.pocketds.hub.ui.glass.GlassButtonBackground
import com.pocketds.hub.ui.glass.GlassColors

/**
 * Controls drawn over a picture -- the player's and the readers' -- and when
 * focused a ring standing a few dp outside the shape, so it shows round a
 * white disc and over bright art alike.
 *
 * There are no white discs over the picture (GLASS_PLAN.md › Player): each
 * control is dark glass tinted by what is playing or open, nearly solid on the
 * video palette's dark ([GlassButtonBackground.overPicture]), so it reads over
 * the brightest frame. Soft white discs washed out over a bright window and
 * left "+10" all but gone. A control that is on turns white ([light]), as a
 * filter does. Play alone stays a white disc ([playFace]).
 */
object OverlayButtons {
    private val PRESSED = Color.argb(90, 255, 255, 255)
    /** What the controls are laid on: the video palette's dark ([Theme.onVideo]). */
    private const val VIDEO_BASE = 0xFF0A0D12.toInt()

    /**
     * Makes [view]'s background a panel of the same dark glass, with
     * [cornerDp] corners (the timeline's bar, the seek preview, the up-next
     * card, the subtitle timing strip, a reader's bars), following what is
     * playing or open.
     */
    fun panel(view: View, cornerDp: Float) {
        com.pocketds.hub.ui.glass.GlassPanelDrawable.attach(view, Styler.dp(view.context, cornerDp)) { page ->
            GlassColors.overPicture(page, VIDEO_BASE)
        }
    }

    /** Gives [view] a round control's face: a disc of dark glass. */
    fun dressDisc(view: View, ring: Int) = dress(view, ring)

    /** Gives [view] a pill's face: a pill of dark glass. */
    fun dressPill(view: View, ring: Int) = dress(view, ring)

    private fun dress(view: View, ring: Int) {
        val context = view.context
        GlassButtonBackground.overPicture(view, ring, Styler.dp(context, 999f), Styler.dpInt(context, RING_GAP_DP), VIDEO_BASE)
    }

    /** A pill that is a setting, lit while on (the reader's Thirds): white with dark words. */
    fun light(view: TextView, on: Boolean) {
        val ink = if (on) GlassColors.INK else Color.WHITE
        // The icon before the words goes dark with them, or it vanishes on the white face.
        view.compoundDrawablesRelative.forEach { (it as? AppIconDrawable)?.tint(ink) }
        view.compoundDrawables.forEach { (it as? AppIconDrawable)?.tint(ink) }
        (view.background as? GlassButtonBackground)?.lit = on
        view.setTextColor(ink)
    }

    /** Play's face: a white disc, its symbol dark ([PLAY_INK]). */
    fun playFace(context: Context, ring: Int): StateListDrawable =
        ringed(context, ring, GradientDrawable.OVAL, Color.WHITE, pressed = Color.argb(255, 214, 219, 226))

    /** The dark symbol on [playFace]. */
    val PLAY_INK: Int = Color.argb(255, 10, 13, 18)

    /** "−10", "+10": a jump, written on a disc (the player's and the read-along dock's). */
    fun jump(context: Context, ring: Int, label: String, description: String, onTap: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            contentDescription = description
            textSize = 13f
            typeface = Type.text(context, 700)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            dressDisc(this, ring)
            Styler.makeFocusable(this)
            activateOnTap(onTap)
        }

    fun ringed(context: Context, ring: Int, shape: Int, fill: Int, pressed: Int = PRESSED): StateListDrawable {
        val gap = Styler.dpInt(context, RING_GAP_DP)
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

    /**
     * "Display", "Chapters": what a tool opens, in words, an [icon] before
     * them, as the prototype's player pills have.
     */
    fun pill(context: Context, ring: Int, label: String, description: String = label, icon: AppIcon? = null,
             onTap: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            contentDescription = description
            textSize = 12f
            typeface = Type.text(context, 600)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            dressPill(this, ring)
            // The face's insets replaced the padding: the ring's room, then the words' own.
            val gap = Styler.dpInt(context, RING_GAP_DP)
            val side = Styler.dpInt(context, 12f)
            setPadding(gap + side, gap, gap + side, gap)
            if (icon != null) {
                val size = Styler.dpInt(context, 14f)
                compoundDrawablePadding = Styler.dpInt(context, 7f)
                setCompoundDrawables(AppIconDrawable(icon, Color.WHITE).apply { setBounds(0, 0, size, size) }, null, null, null)
            }
            Styler.makeFocusable(this)
            activateOnTap(onTap)
        }

    /** A round button with one of the app's icons, white, on a disc of dark glass. */
    fun round(context: Context, ring: Int, icon: AppIcon, description: String, onTap: () -> Unit): ImageView =
        ImageView(context).apply {
            setImageDrawable(AppIconDrawable(icon, Color.WHITE))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = Styler.dpInt(context, 12f)
            setPadding(pad, pad, pad, pad)
            dressDisc(this, ring)
            // The face's insets replaced the padding; the icon keeps its room.
            setPadding(pad, pad, pad, pad)
            contentDescription = description
            Styler.makeFocusable(this)
            activateOnTap(onTap)
        }

    /** The room between a control and its focus ring. */
    private const val RING_GAP_DP = 4f
}
