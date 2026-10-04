package com.pocketds.hub.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.pocketds.hub.ui.glass.GlassColors
import com.pocketds.hub.ui.glass.GlassPage
import com.pocketds.hub.ui.glass.GlassPanelDrawable

/**
 * A rounded action: Play, Details, Continue reading.
 *
 * The primary one is filled with the accent, the rest are a quiet translucent
 * white. Focus draws a ring around the pill with a gap, so a focused Play reads
 * as Play with a ring rather than a bigger, paler button. The ring needs room,
 * so the view is [RING_DP] larger than the pill on every side.
 *
 * [glass] is the Glass look's pair (GLASS_PLAN.md): the main action white with
 * dark words, the others glass that takes the page's tint, both with the
 * prototype's 11dp corners rather than round ends. A screen opts in as its
 * Glass milestone lands; the rest keep the accent pill until then.
 */
object PillButton {
    const val RING_DP = 4f
    /** The prototype's Pocket button corner. */
    private const val GLASS_CORNER_DP = 11f

    fun create(
        context: Context,
        colors: PocketColors,
        label: String,
        icon: AppIcon? = null,
        primary: Boolean = false,
        heightDp: Float = 38f,
        glass: Boolean = false
    ): TextView = TextView(context).apply {
        text = label
        textSize = 13f
        textWeight(if (primary || glass) 700 else 600)
        gravity = Gravity.CENTER
        isSingleLine = true
        includeFontPadding = false
        val ink = when {
            glass && primary -> GlassColors.INK
            glass -> Color.WHITE
            primary -> colors.accentText
            else -> colors.primaryText
        }
        // Before the padding: a background with insets replaces the view's padding.
        background = if (glass) glassBackground(this, colors, primary) else background(context, colors, primary)
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
     * Switches a pill between the accent fill and the quiet one: a filter that
     * is on. A new background resets the view's padding to the drawable's
     * insets, which cut the text against the pill's edge, so it is put back.
     */
    fun setPrimary(view: TextView, colors: PocketColors, primary: Boolean) {
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

    /**
     * Glass: white for the main action, the page's glass for the rest. The
     * glass ones follow the page as it re-tints, for as long as [view] is shown.
     */
    private fun glassBackground(view: TextView, colors: PocketColors, primary: Boolean): StateListDrawable {
        val context = view.context
        val ring = Styler.dpInt(context, RING_DP)
        val corner = Styler.dp(context, GLASS_CORNER_DP)
        val panels = ArrayList<Pair<GlassPanelDrawable, Boolean>>()
        fun glassFill(pressed: Boolean, page: Int) = if (pressed) KeyPressTint.pressed(page, true) else page
        fun fill(pressed: Boolean): Drawable =
            if (primary) ThemeGradientDrawable.rounded(corner, if (pressed) KeyPressTint.pressed(Color.WHITE, false) else Color.WHITE)
            else GlassPanelDrawable(glassFill(pressed, GlassColors.panel(GlassPage.palette(context))), corner).also { panels += it to pressed }
        fun outline() = ThemeGradientDrawable.rounded(corner + ring, Color.TRANSPARENT, Styler.dpInt(context, 2f), colors.focusRing)
        val states = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), InsetDrawable(fill(true), ring))
            addState(intArrayOf(android.R.attr.state_focused), LayerDrawable(arrayOf(outline(), InsetDrawable(fill(false), ring))))
            addState(intArrayOf(), InsetDrawable(fill(false), ring))
        }
        if (panels.isNotEmpty()) GlassPage.follow(view) { page ->
            val tint = GlassColors.panel(page)
            panels.forEach { (panel, pressed) -> panel.retint(glassFill(pressed, tint)) }
            view.invalidate()
        }
        return states
    }
}
