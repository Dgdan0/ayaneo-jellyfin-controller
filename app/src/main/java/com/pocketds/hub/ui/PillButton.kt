package com.pocketds.hub.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.widget.TextView
import androidx.core.graphics.ColorUtils

/**
 * A rounded action: Play, Details, Continue reading.
 *
 * The primary one is filled with the accent, the rest are a quiet translucent
 * white. Focus draws a ring around the pill with a gap, so a focused Play reads
 * as Play with a ring rather than a bigger, paler button. The ring needs room,
 * so the view is [RING_DP] larger than the pill on every side.
 */
object PillButton {
    const val RING_DP = 4f

    fun create(
        context: Context,
        colors: PocketColors,
        label: String,
        icon: AppIcon? = null,
        primary: Boolean = false,
        heightDp: Float = 38f
    ): TextView = TextView(context).apply {
        text = label
        textSize = 13f
        textWeight(if (primary) 700 else 600)
        gravity = Gravity.CENTER
        isSingleLine = true
        includeFontPadding = false
        // Before the padding: a background with insets replaces the view's padding.
        background = background(context, colors, primary)
        val ring = Styler.dpInt(context, RING_DP)
        setPadding(ring + Styler.dpInt(context, if (icon != null) 14f else 18f), ring, ring + Styler.dpInt(context, 18f), ring)
        minimumHeight = Styler.dpInt(context, heightDp + 2 * RING_DP)
        setTextColor(if (primary) colors.accentText else colors.primaryText)
        icon?.let {
            val size = Styler.dpInt(context, 15f)
            compoundDrawablePadding = Styler.dpInt(context, 8f)
            setCompoundDrawables(AppIconDrawable(it, if (primary) colors.accentText else colors.primaryText).apply { setBounds(0, 0, size, size) }, null, null, null)
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
}
