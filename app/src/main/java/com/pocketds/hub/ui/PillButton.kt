package com.pocketds.hub.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.TextView
import com.pocketds.hub.ui.glass.GlassButtonBackground
import com.pocketds.hub.ui.glass.GlassColors

/**
 * A rounded action: Play, Details, Continue reading.
 *
 * The main action takes its side's face (GLASS_PLAN.md) -- white with dark
 * words on Media, the Books accent (gold) with its ink on Books ([mainFace],
 * [mainInk]) -- and the others are glass that takes the page's tint, all with
 * the prototype's 11dp corners. Focus draws a ring around the pill with a gap,
 * so a focused Play reads as Play with a ring rather than a bigger, paler
 * button. The ring needs room, so the view is [RING_DP] larger than the pill
 * on every side.
 */
object PillButton {
    const val RING_DP = 4f
    /** The prototype's Pocket button corner. */
    private const val CORNER_DP = 11f

    /** The face of a main action on [side]: white on Media, the Books accent on Books. */
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
        /** The side whose main action this is, which decides its face ([mainFace]). */
        side: com.pocketds.hub.state.ContentMode = com.pocketds.hub.state.ContentMode.MEDIA
    ): TextView = TextView(context).apply {
        text = label
        textSize = 13f
        textWeight(700)
        gravity = Gravity.CENTER
        isSingleLine = true
        includeFontPadding = false
        val ink = if (primary) mainInk(colors, side) else Color.WHITE
        // Before the padding: a background with insets replaces the view's padding.
        GlassButtonBackground.attach(this, colors, Styler.dp(context, CORNER_DP), Styler.dpInt(context, RING_DP), lit = primary,
            litColor = { mainFace(colors, side) })
        val ring = Styler.dpInt(context, RING_DP)
        val edge = Styler.dpInt(context, 14f)
        setPadding(ring + edge, ring, ring + edge, ring)
        minimumHeight = Styler.dpInt(context, heightDp + 2 * RING_DP)
        setTextColor(ink)
        icon?.let {
            val size = Styler.dpInt(context, 14f)
            compoundDrawablePadding = Styler.dpInt(context, 7f)
            setCompoundDrawables(AppIconDrawable(it, ink).apply { setBounds(0, 0, size, size) }, null, null, null)
        }
        contentDescription = label
        Styler.makeFocusable(this)
    }

    /**
     * The prototype's control button (`.cbtn`), a [CONTROL_DP]-tall
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
     * Lights a pill white, or puts it back to glass, in place: a filter that is
     * on. Its words and icon follow, dark on the white face.
     */
    fun setPrimary(view: TextView, primary: Boolean) {
        (view.background as? GlassButtonBackground)?.lit = primary
        val ink = if (primary) GlassColors.INK else Color.WHITE
        view.setTextColor(ink)
        (view.compoundDrawables[0] as? AppIconDrawable)?.tint(ink)
    }
}
