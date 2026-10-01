package com.pocketds.hub.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View

/**
 * The shared look, and the one rule nobody may forget.
 *
 * Follows the sibling project's `KeyStyler`: `GradientDrawable` inside a
 * `StateListDrawable`, and deliberately no ripple. A ripple animates *after* the
 * input, which reads as lag when the selection is being flung around by a stick;
 * the state change has to be instant.
 */
object Styler {
    /** Persistent selection and temporary D-pad focus are independent visual states. */
    fun selectionBackground(
        context: Context,
        colors: PocketColors,
        selected: Boolean,
        baseFill: Int = Color.TRANSPARENT,
        selectedFill: Int = baseFill,
        selectedStrokeDp: Float = 0f,
        cornerDp: Float = 9f
    ): StateListDrawable {
        fun face(fill: Int, strokeDp: Float = 0f, strokeColor: Int = Color.TRANSPARENT) =
            ThemeGradientDrawable().apply {
                cornerRadius = dp(context, cornerDp)
                setColor(fill)
                if (strokeDp > 0f) setStroke(dpInt(context, strokeDp), strokeColor)
            }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), face(
                colors.focusFill, 2f, if (selected) colors.primaryText else colors.focusRing))
            addState(intArrayOf(), face(if (selected) selectedFill else baseFill,
                if (selected) selectedStrokeDp else 0f, colors.accent))
        }
    }

    /** Above edge-to-edge artwork so the image cannot cover the focus outline. */
    fun focusOutline(context: Context, colors: PocketColors) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), ThemeGradientDrawable().apply {
            cornerRadius=dp(context,ArtworkFrame.CORNER_DP);setColor(android.graphics.Color.TRANSPARENT);setStroke(dpInt(context,2f),colors.focusRing)
        })
        addState(intArrayOf(), android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
    }

    /**
     * Make a view focusable, correctly.
     *
     * `isFocusableInTouchMode` is not optional here and it is not defensive. The
     * bottom screen runs the sibling keyboard app as a trackpad, which drives a
     * cursor by dispatching synthetic gestures -- and any touch event puts the
     * window into touch mode. In touch mode a merely-`focusable` view refuses
     * focus, so the gamepad silently stops moving the selection and looks broken.
     * D-pad *key* events leave touch mode on their own; stick and hat
     * *motion* events do not.
     *
     * `defaultFocusHighlightEnabled = false` because the platform highlight is a
     * faint grey that is invisible on a 7" screen at arm's length. We draw our
     * own.
     */
    fun makeFocusable(view: View) {
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.defaultFocusHighlightEnabled = false
    }

    /**
     * A card background carrying its own focused and pressed states.
     *
     * The focused entry is a 3dp accent ring with an inner gap, which survives
     * being looked at across a room in a way a 1dp outline does not.
     */
    fun cardBackground(
        context: Context,
        colors: PocketColors,
        cornerDp: Float = 10f,
        baseFill: Int = colors.cardSurface,
        focusStrokeDp: Float = 3f
    ): StateListDrawable {
        val corner = dp(context, cornerDp)
        val dark = KeyPressTint.isDarkSurface(baseFill)

        fun face(fill: Int, strokeWidth: Int = 0, strokeColor: Int = 0) =
            ThemeGradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = corner
                setColor(fill)
                if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
            }

        return StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                face(KeyPressTint.pressed(baseFill, dark))
            )
            addState(
                intArrayOf(android.R.attr.state_focused),
                face(colors.focusFill, dp(context, focusStrokeDp).toInt(), colors.focusRing)
            )
            addState(intArrayOf(), face(baseFill))
        }
    }

    /** A pill, for hint-bar chips and tabs. */
    fun chipBackground(
        context: Context,
        colors: PocketColors,
        selected: Boolean = false
    ): StateListDrawable {
        val corner = dp(context, 14f)
        val base = if (selected) colors.accent else colors.stripBackground
        val dark = KeyPressTint.isDarkSurface(base)

        fun face(fill: Int, strokeWidth: Int = 0, strokeColor: Int = 0) =
            ThemeGradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = corner
                setColor(fill)
                if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
            }

        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), face(KeyPressTint.pressed(base, dark)))
            addState(
                intArrayOf(android.R.attr.state_focused),
                face(base, dp(context, 2f).toInt(), colors.focusRing)
            )
            addState(intArrayOf(), face(base))
        }
    }

    fun dp(context: Context, value: Float): Float =
        value * context.resources.displayMetrics.density

    fun dpInt(context: Context, value: Float): Int = dp(context, value).toInt()
}
