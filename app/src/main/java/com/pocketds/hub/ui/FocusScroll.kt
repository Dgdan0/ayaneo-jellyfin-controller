package com.pocketds.hub.ui

import android.content.Context
import android.graphics.Canvas
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
 *
 * On Glass a scrolled page fades out at its top edge rather than running under
 * the top bar ([com.pocketds.hub.ui.glass.TopFade]), so every scrolling page
 * gets it from here.
 */
open class FocusScrollView(context: Context, private val revealAbove: Int = 0) : ScrollView(context) {
    init { isFocusable = false; isFocusableInTouchMode = false }

    private val topFade = if (Theme.isGlass(context)) com.pocketds.hub.ui.glass.TopFade(this) else null

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        topFade?.measure()
    }

    override fun draw(canvas: Canvas) {
        val fade = topFade ?: return super.draw(canvas)
        fade.draw(canvas, scrollY) { super.draw(it) }
    }

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
