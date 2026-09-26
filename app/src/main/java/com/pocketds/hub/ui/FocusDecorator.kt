package com.pocketds.hub.ui

import android.view.View
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

/**
 * How a focused thing looks, at arm's length on a 7" screen.
 *
 * A 2dp outline is not enough. Three things together are, and none of them cost
 * much:
 *
 *  * the accent ring, which lives in the background drawable's `state_focused`
 *  * a small scale-up, so the selection has physical presence
 *  * a haptic tick per step, gated so a held stick does not buzz continuously
 *
 * The spring constants are the ones the sibling project already tuned on this
 * exact hardware, so this starts at the right feel rather than discovering it
 * again.
 */
object FocusDecorator {

    private const val FOCUSED_SCALE = DetailLayout.POSTER_FOCUS_SCALE
    private const val TAG_SCALE_X = -0x7ffffff1
    private const val TAG_SCALE_Y = -0x7ffffff2
    private const val TAG_SCALE_ENABLED = -0x7ffffff3
    private const val TAG_DECORATED = -0x7fffffd9

    /**
     * @param ringVisible whether the app is in directional mode. When the user is
     *   driving the trackpad, focus still moves under the hood but showing a ring
     *   chasing it is noise.
     */
    fun attach(view: View, ringVisible: () -> Boolean, scale: Boolean = true) {
        // Most screens also need focus changes to update their selected item or
        // hint bar, so they replace this listener and call [refresh] themselves.
        // Keep the scale choice on the view so refresh cannot accidentally turn
        // a deliberately non-scaling full-width row back into a growing one.
        view.setTag(TAG_SCALE_ENABLED, scale)
        // Keep artwork fully opaque. Fading the whole card blends posters into
        // the page background and makes them look grey in the light theme.
        view.alpha = 1f
        view.setOnFocusChangeListener { v, hasFocus ->
            val decorate = hasFocus && ringVisible()
            apply(v, decorate, scale)
        }
    }

    /** Re-run the decoration after the input mode flips, without a focus change. */
    fun refresh(view: View, ringVisible: Boolean) {
        val decorate = view.hasFocus() && ringVisible
        val scale = view.getTag(TAG_SCALE_ENABLED) as? Boolean ?: true
        apply(view, decorate, scale)
    }

    private fun apply(view: View, decorate: Boolean, scale: Boolean) {
        val wasDecorated = view.getTag(TAG_DECORATED) == true
        view.setTag(TAG_DECORATED, decorate)
        if (decorate && !wasDecorated && view is android.view.ViewGroup) view.post {
            if (view.hasFocus() && view.height > 0) {
                val gutter = if (scale) ((maxOf(view.width, view.height) * (FOCUSED_SCALE - 1f) / 2f).toInt() + Styler.dpInt(view.context, 3f)) else 0
                view.requestRectangleOnScreen(android.graphics.Rect(-gutter, -gutter,
                    view.width + gutter, view.height + gutter), true)
                // A horizontal RecyclerView consumes rectangle requests before its
                // vertical shelf list can reveal the caption. Resolve that outer
                // viewport explicitly, after default focus scrolling has started.
                var ancestor = view.parent
                while (ancestor is android.view.ViewGroup) {
                    if (ancestor is androidx.recyclerview.widget.RecyclerView &&
                        ancestor.layoutManager?.canScrollVertically() == true) {
                        val list = ancestor
                        list.stopScroll()
                        val bounds = android.graphics.Rect(-gutter, -gutter, view.width + gutter, view.height + gutter)
                        list.offsetDescendantRectToMyCoords(view, bounds)
                        val top = list.paddingTop
                        val bottom = list.height - list.paddingBottom
                        // Keep the shelf heading with its cards when the whole
                        // row fits; otherwise prioritize the selected title.
                        list.findContainingItemView(view)?.takeIf { it !== view }?.let { row ->
                            val shelf = android.graphics.Rect(0, 0, row.width, row.height)
                            list.offsetDescendantRectToMyCoords(row, shelf)
                            val expandedTop = minOf(bounds.top, shelf.top)
                            val expandedBottom = maxOf(bounds.bottom, shelf.bottom)
                            if (expandedBottom - expandedTop <= bottom - top) {
                                bounds.top = expandedTop; bounds.bottom = expandedBottom
                            }
                        }
                        val dy = when {
                            bounds.height() > bottom - top -> bounds.top - top
                            bounds.top < top -> bounds.top - top
                            bounds.bottom > bottom -> bounds.bottom - bottom
                            else -> 0
                        }
                        if (dy != 0) list.scrollBy(0, dy)
                    }
                    ancestor = ancestor.parent
                }
            }
        }
        val target = if (decorate && scale) FOCUSED_SCALE else 1f
        if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
            spring(view, TAG_SCALE_X, SpringAnimation.SCALE_X).animateToFinalPosition(target)
            spring(view, TAG_SCALE_Y, SpringAnimation.SCALE_Y).animateToFinalPosition(target)
        } else {
            (view.getTag(TAG_SCALE_X) as? SpringAnimation)?.cancel()
            (view.getTag(TAG_SCALE_Y) as? SpringAnimation)?.cancel()
            view.scaleX=target;view.scaleY=target
        }
        // Lift growing posters above neighbours. Stationary controls need no
        // elevation: their shadow is clipped into a rectangle by the action strip.
        view.translationZ = if (decorate && scale) Styler.dp(view.context, 8f) else 0f
    }

    /**
     * One SpringAnimation per view per property, kept on the view itself.
     * Creating a new one per focus change leaves the old one still running and
     * the two fight over the same property.
     */
    private fun spring(
        view: View,
        tag: Int,
        property: androidx.dynamicanimation.animation.DynamicAnimation.ViewProperty
    ): SpringAnimation {
        (view.getTag(tag) as? SpringAnimation)?.let { return it }
        val animation = SpringAnimation(view, property).apply {
            spring = SpringForce().apply {
                // Focus must fit the clearance reserved by the shelf, including mid-animation.
                dampingRatio = 1f
                stiffness = SpringForce.STIFFNESS_MEDIUM
            }
        }
        view.setTag(tag, animation)
        return animation
    }
}
