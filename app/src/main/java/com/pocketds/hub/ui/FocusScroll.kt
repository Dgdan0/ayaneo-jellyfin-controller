package com.pocketds.hub.ui

import android.content.Context
import android.graphics.Rect
import android.widget.HorizontalScrollView
import android.widget.ScrollView

/**
 * A vertical scroller that passes focus straight through to its contents.
 *
 * ScrollView is focusable by default, which makes it an invisible focus stop: a
 * directional press lands on the scroller, draws no ring, and reads as the pad
 * being dead. Every screen used to repeat `isFocusable = false`, and the ones
 * that forgot had the bug.
 *
 * [revealAbove] keeps that many pixels visible above the focused view when the
 * scroller brings it on screen, for a list whose rows sit under a heading (a day
 * in Upcoming). Without it the heading is scrolled half under whatever is above
 * the list, because ScrollView reveals exactly the focused row and nothing more.
 */
open class FocusScrollView(context: Context, private val revealAbove: Int = 0) : ScrollView(context) {
    init { isFocusable = false; isFocusableInTouchMode = false }

    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
        if (revealAbove <= 0) return super.computeScrollDeltaToGetChildRectOnScreen(rect)
        val widened = Rect(rect).apply { top = (top - revealAbove).coerceAtLeast(0) }
        return super.computeScrollDeltaToGetChildRectOnScreen(widened)
    }
}

/** The horizontal [FocusScrollView]: never a focus stop of its own. */
open class FocusHorizontalScrollView(context: Context) : HorizontalScrollView(context) {
    init { isFocusable = false; isFocusableInTouchMode = false }
}
