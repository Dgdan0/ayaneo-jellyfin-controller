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
 *  * **dimming everything else** rather than brightening the one thing, which is
 *    more readable and costs one property
 *  * a small scale-up, so the selection has physical presence
 *  * a haptic tick per step, gated so a held stick does not buzz continuously
 *
 * The spring constants are the ones the sibling project already tuned on this
 * exact hardware, so this starts at the right feel rather than discovering it
 * again.
 */
object FocusDecorator {

    private const val FOCUSED_SCALE = 1.08f
    private const val UNFOCUSED_ALPHA = 0.72f
    private const val TAG_SCALE_X = -0x7ffffff1
    private const val TAG_SCALE_Y = -0x7ffffff2

    /**
     * @param ringVisible whether the app is in directional mode. When the user is
     *   driving the trackpad, focus still moves under the hood but showing a ring
     *   chasing it is noise.
     */
    fun attach(view: View, ringVisible: () -> Boolean, scale: Boolean = true) {
        view.alpha = UNFOCUSED_ALPHA
        view.setOnFocusChangeListener { v, hasFocus ->
            val decorate = hasFocus && ringVisible()
            v.alpha = if (decorate) 1f else UNFOCUSED_ALPHA
            val target = if (decorate && scale) FOCUSED_SCALE else 1f
            spring(v, TAG_SCALE_X, SpringAnimation.SCALE_X).animateToFinalPosition(target)
            spring(v, TAG_SCALE_Y, SpringAnimation.SCALE_Y).animateToFinalPosition(target)
            // Above its neighbours, or the ring is clipped by the next card.
            v.translationZ = if (decorate) Styler.dp(v.context, 8f) else 0f
        }
    }

    /** Re-run the decoration after the input mode flips, without a focus change. */
    fun refresh(view: View, ringVisible: Boolean) {
        val decorate = view.hasFocus() && ringVisible
        view.alpha = if (decorate) 1f else UNFOCUSED_ALPHA
        spring(view, TAG_SCALE_X, SpringAnimation.SCALE_X)
            .animateToFinalPosition(if (decorate) FOCUSED_SCALE else 1f)
        spring(view, TAG_SCALE_Y, SpringAnimation.SCALE_Y)
            .animateToFinalPosition(if (decorate) FOCUSED_SCALE else 1f)
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
