package com.pocketds.hub.ui

import android.view.View
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

/**
 * How a focused thing looks, at arm's length on a 7" screen.
 *
 * A 2dp outline is not enough. Four things together are, and none of them cost
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

    private const val FOCUSED_SCALE = 1.08f
    private const val TAG_SCALE_X = -0x7ffffff1
    private const val TAG_SCALE_Y = -0x7ffffff2
    private const val TAG_SCALE_ENABLED = -0x7ffffff3

    /**
     * @param ringVisible whether the app is in directional mode. When the user is
     *   driving the trackpad, focus still moves under the hood but showing a ring
     *   chasing it is noise.
     */
    fun attach(view: View, ringVisible: () -> Boolean, scale: Boolean = true) {
        // Most screens also need focus changes to update their selected item or
        // hint bar, so they replace this listener and call [refresh] themselves.
        // Keep the scale choice on the view so refresh cannot accidentally turn
        // a deliberately non-scaling full-width row back into an 8% larger one.
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
        val target = if (decorate && scale) FOCUSED_SCALE else 1f
        spring(view, TAG_SCALE_X, SpringAnimation.SCALE_X).animateToFinalPosition(target)
        spring(view, TAG_SCALE_Y, SpringAnimation.SCALE_Y).animateToFinalPosition(target)
        // Above its neighbours, or the ring is clipped by the next card.
        view.translationZ = if (decorate) Styler.dp(view.context, 8f) else 0f
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
                dampingRatio = 0.75f
                stiffness = SpringForce.STIFFNESS_MEDIUM
            }
        }
        view.setTag(tag, animation)
        return animation
    }
}
