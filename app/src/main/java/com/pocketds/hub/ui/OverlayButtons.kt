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
 * Controls drawn over a picture -- the player's and the comic reader's: white
 * on a soft translucent disc or pill, and when focused a ring standing a few
 * dp outside the shape, so it shows round a white disc and over bright art
 * alike. The player had these to itself; the reader's square glyph buttons
 * were the last of the old look.
 *
 * On Glass (GLASS_PLAN.md › Player) there are no white discs over the picture:
 * each control is dark glass tinted by what is playing or open, nearly solid
 * on the video palette's dark ([GlassButtonBackground.overPicture]), so it
 * reads over the brightest frame. The soft white discs washed out over a
 * bright window and left "+10" all but gone. A control that is on turns
 * white ([light]), as a Glass filter does.
 */
object OverlayButtons {
    private val SOFT = Color.argb(46, 255, 255, 255)
    private val PRESSED = Color.argb(90, 255, 255, 255)
    /** What Glass's controls are laid on: the video palette's dark ([Theme.onVideo]). */
    private const val VIDEO_BASE = 0xFF0A0D12.toInt()

    /** A round soft disc: Back, Cast, Lock, a page or issue step. Classic only; [dressDisc] picks by look. */
    fun disc(context: Context, ring: Int): StateListDrawable = ringed(context, ring, GradientDrawable.OVAL, SOFT)

    /** A soft pill: a tool named in words. Classic only; [dressPill] picks by look. */
    fun pillFace(context: Context, ring: Int): StateListDrawable = ringed(context, ring, GradientDrawable.RECTANGLE, SOFT)

    /**
     * Glass: makes [view]'s background a panel of the same dark glass, with
     * [cornerDp] corners (the timeline's bar, the seek preview, the up-next
     * card, the subtitle timing strip), following what is playing. False on
     * Classic, where the caller keeps its own.
     */
    fun panel(view: View, cornerDp: Float): Boolean {
        if (!Theme.isGlass(view.context)) return false
        com.pocketds.hub.ui.glass.GlassPanelDrawable.attach(view, Styler.dp(view.context, cornerDp)) { page ->
            GlassColors.overPicture(page, VIDEO_BASE)
        }
        return true
    }

    /** Gives [view] a round control's face: the soft disc, or on Glass a disc of dark glass. */
    fun dressDisc(view: View, ring: Int) = dress(view, ring, round = true)

    /** Gives [view] a pill's face: the soft pill, or on Glass a pill of dark glass. */
    fun dressPill(view: View, ring: Int) = dress(view, ring, round = false)

    private fun dress(view: View, ring: Int, round: Boolean) {
        val context = view.context
        if (Theme.isGlass(context)) {
            GlassButtonBackground.overPicture(view, ring, Styler.dp(context, 999f), Styler.dpInt(context, RING_GAP_DP), VIDEO_BASE)
        } else {
            view.background = ringed(context, ring, if (round) GradientDrawable.OVAL else GradientDrawable.RECTANGLE, SOFT)
        }
    }

    /**
     * A pill that is a setting, lit while on (the reader's Thirds): white with
     * dark words on Glass, the accent with its ink on Classic.
     */
    fun light(view: TextView, colors: PocketColors, on: Boolean) {
        val glass = view.background as? GlassButtonBackground
        if (glass != null) {
            glass.lit = on
            view.setTextColor(if (on) GlassColors.INK else Color.WHITE)
            return
        }
        view.background = if (on) ringed(view.context, colors.focusRing, GradientDrawable.RECTANGLE, colors.accent)
            else pillFace(view.context, colors.focusRing)
        view.setTextColor(if (on) colors.accentText else Color.WHITE)
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
     * "Display", "Chapters": what a tool opens, in words. On Glass an [icon]
     * goes before them, as the prototype's player pills have.
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
            setPadding(Styler.dpInt(context, 16f), 0, Styler.dpInt(context, 16f), 0)
            dressPill(this, ring)
            if (Theme.isGlass(context)) {
                // The face's insets replaced the padding: the ring's room, then the words' own.
                val gap = Styler.dpInt(context, RING_GAP_DP)
                val side = Styler.dpInt(context, 12f)
                setPadding(gap + side, gap, gap + side, gap)
                if (icon != null) {
                    val size = Styler.dpInt(context, 14f)
                    compoundDrawablePadding = Styler.dpInt(context, 7f)
                    setCompoundDrawables(AppIconDrawable(icon, Color.WHITE).apply { setBounds(0, 0, size, size) }, null, null, null)
                }
            }
            Styler.makeFocusable(this)
            activateOnTap(onTap)
        }

    /** A round button with one of the app's icons, white, in a soft disc (dark glass on Glass). */
    fun round(context: Context, ring: Int, icon: AppIcon, description: String, onTap: () -> Unit): ImageView =
        ImageView(context).apply {
            setImageDrawable(AppIconDrawable(icon, Color.WHITE))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = Styler.dpInt(context, 12f)
            setPadding(pad, pad, pad, pad)
            dressDisc(this, ring)
            // Glass: the face's insets replaced the padding; the icon keeps its room.
            if (Theme.isGlass(context)) setPadding(pad, pad, pad, pad)
            contentDescription = description
            Styler.makeFocusable(this)
            activateOnTap(onTap)
        }

    /** The room between a control and its focus ring. */
    private const val RING_GAP_DP = 4f
}
