package com.pocketds.hub.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.pocketds.hub.ui.glass.GlassButtonBackground
import com.pocketds.hub.ui.glass.GlassColors

/**
 * A rounded action: Play, Details, Continue reading.
 *
 * The primary one is filled with the accent, the rest are a quiet translucent
 * white. Focus draws a ring around the pill with a gap, so a focused Play reads
 * as Play with a ring rather than a bigger, paler button. The ring needs room,
 * so the view is [RING_DP] larger than the pill on every side.
 *
 * [glass] is the Glass look's pair (GLASS_PLAN.md): the main action takes its
 * side's face -- white with dark words on Media, the Books accent (gold) with
 * its ink on Books ([mainFace], [mainInk]) -- and the others are glass that
 * takes the page's tint, all with the prototype's 11dp corners rather than
 * round ends. A screen opts in as its Glass milestone lands; the rest keep the
 * accent pill until then.
 */
object PillButton {
    const val RING_DP = 4f
    /** The prototype's Pocket button corner. */
    private const val GLASS_CORNER_DP = 11f

    /** The face of a Glass main action on [side]: white on Media, the Books accent on Books. */
    fun mainFace(colors: PocketColors, side: com.pocketds.hub.state.ContentMode): Int =
        if (side == com.pocketds.hub.state.ContentMode.BOOKS) colors.accent else Color.WHITE

    /** The words and icon on [mainFace]. */
    fun mainInk(colors: PocketColors, side: com.pocketds.hub.state.ContentMode): Int =
        if (side == com.pocketds.hub.state.ContentMode.BOOKS) colors.accentText else GlassColors.INK

    fun create(
        context: Context,
        colors: PocketColors,
        label: String,
        icon: AppIcon? = null,
        primary: Boolean = false,
        heightDp: Float = 38f,
        glass: Boolean = false,
        /** Glass: the side whose main action this is, which decides its face ([mainFace]). */
        side: com.pocketds.hub.state.ContentMode = com.pocketds.hub.state.ContentMode.MEDIA
    ): TextView = TextView(context).apply {
        text = label
        textSize = 13f
        textWeight(if (primary || glass) 700 else 600)
        gravity = Gravity.CENTER
        isSingleLine = true
        includeFontPadding = false
        val ink = when {
            glass && primary -> mainInk(colors, side)
            glass -> Color.WHITE
            primary -> colors.accentText
            else -> colors.primaryText
        }
        // Before the padding: a background with insets replaces the view's padding.
        if (glass) GlassButtonBackground.attach(this, colors, Styler.dp(context, GLASS_CORNER_DP), Styler.dpInt(context, RING_DP), lit = primary,
            litColor = { mainFace(colors, side) })
        else background = background(context, colors, primary)
        val ring = Styler.dpInt(context, RING_DP)
        if (glass) {
            val side = Styler.dpInt(context, 14f)
            setPadding(ring + side, ring, ring + side, ring)
        } else {
            setPadding(ring + Styler.dpInt(context, if (icon != null) 14f else 18f), ring, ring + Styler.dpInt(context, 18f), ring)
        }
        minimumHeight = Styler.dpInt(context, heightDp + 2 * RING_DP)
        setTextColor(ink)
        icon?.let {
            val size = Styler.dpInt(context, if (glass) 14f else 15f)
            compoundDrawablePadding = Styler.dpInt(context, if (glass) 7f else 8f)
            setCompoundDrawables(AppIconDrawable(it, ink).apply { setBounds(0, 0, size, size) }, null, null, null)
        }
        contentDescription = label
        Styler.makeFocusable(this)
    }

    /**
     * Glass: the prototype's control button (`.cbtn`), a [CONTROL_DP]-tall
     * round-ended pill of the page's glass in a row of controls -- Favourites,
     * Sort and its direction, Mark all seen -- its icon before the words.
     * [round] is a circle round the icon alone, such as search. The ring
     * stands outside it with a gap, as on every pill, so the view is
     * [RING_DP] larger on each side. Applied to [view] so a screen's own
     * button class (a [CenteredIconTextView]) keeps its behaviour.
     */
    fun control(view: TextView, colors: PocketColors, icon: AppIcon? = null, round: Boolean = false) {
        val context = view.context
        val ring = Styler.dpInt(context, RING_DP)
        GlassButtonBackground.attach(view, colors, Styler.dp(context, 999f), ring, lit = false)
        view.textSize = 12f
        view.textWeight(700)
        view.isSingleLine = true
        view.includeFontPadding = false
        view.gravity = Gravity.CENTER
        view.setTextColor(Color.WHITE)
        val iconSize = Styler.dpInt(context, 14f)
        val side = if (round) (Styler.dpInt(context, CONTROL_DP) - iconSize) / 2 else Styler.dpInt(context, 11f)
        view.setPadding(ring + side, ring, ring + side, ring)
        val outer = Styler.dpInt(context, CONTROL_DP + 2 * RING_DP)
        view.minimumHeight = outer
        view.minHeight = outer
        if (round) { view.minimumWidth = outer; view.minWidth = outer }
        icon?.let {
            view.compoundDrawablePadding = if (round) 0 else Styler.dpInt(context, 7f)
            view.setCompoundDrawables(AppIconDrawable(it, Color.WHITE).apply { setBounds(0, 0, iconSize, iconSize) }, null, null, null)
        }
    }

    /** A [control] button's height, without its ring: the prototype's Pocket `.cbtn`. */
    const val CONTROL_DP = 32f

    /**
     * Switches a pill between the accent fill and the quiet one: a filter that
     * is on. A new background resets the view's padding to the drawable's
     * insets, which cut the text against the pill's edge, so it is put back.
     *
     * A Glass pill turns white or back to glass in place: replacing its
     * background drew a Classic pill on the glass page.
     */
    fun setPrimary(view: TextView, colors: PocketColors, primary: Boolean) {
        (view.background as? GlassButtonBackground)?.let { glass ->
            glass.lit = primary
            val ink = if (primary) GlassColors.INK else Color.WHITE
            view.setTextColor(ink)
            (view.compoundDrawables[0] as? AppIconDrawable)?.tint(ink)
            return
        }
        val left = view.paddingLeft; val top = view.paddingTop; val right = view.paddingRight; val bottom = view.paddingBottom
        view.background = background(view.context, colors, primary)
        view.setPadding(left, top, right, bottom)
        view.setTextColor(if (primary) colors.accentText else colors.primaryText)
        view.textWeight(if (primary) 700 else 600)
    }

    fun background(context: Context, colors: PocketColors, primary: Boolean): StateListDrawable {
        val ring = Styler.dpInt(context, RING_DP)
        fun fill(pressed: Boolean) = ThemeGradientDrawable().apply {
            cornerRadius = Styler.dp(context, 999f)
            val base = if (primary) colors.accent else ColorUtils.setAlphaComponent(colors.primaryText, 0x24)
            setColor(if (pressed) KeyPressTint.pressed(if (primary) colors.accent else colors.cardSurface, !primary) else base)
        }
        fun outline() = ThemeGradientDrawable().apply {
            cornerRadius = Styler.dp(context, 999f)
            setColor(Color.TRANSPARENT)
            setStroke(Styler.dpInt(context, 2f), colors.focusRing)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), InsetDrawable(fill(true), ring))
            addState(intArrayOf(android.R.attr.state_focused), LayerDrawable(arrayOf(outline(), InsetDrawable(fill(false), ring))))
            addState(intArrayOf(), InsetDrawable(fill(false), ring))
        }
    }
}
