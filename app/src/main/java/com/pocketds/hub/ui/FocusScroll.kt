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
 * [revealWhole] is a part of the page that comes into view whole while focus is
 * in it, when it fits: a page's header, so the overview or the Play pill in
 * focus keeps the title over it on a page scrolled down to its seasons (#23).
 *
 * A scrolled page fades out at its top edge rather than running under the top
 * bar ([com.pocketds.hub.ui.glass.TopFade]), so every scrolling page gets it
 * from here.
 */
open class FocusScrollView(context: Context, private val revealAbove: Int = 0) : ScrollView(context) {
    init { isFocusable = false; isFocusableInTouchMode = false }

    var revealWhole: android.view.View? = null

    private val topFade = com.pocketds.hub.ui.glass.TopFade(this)

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        topFade.measure()
    }

    override fun draw(canvas: Canvas) {
        topFade.draw(canvas, scrollY) { super.draw(it) }
    }

    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
        val widened = Rect(rect)
        if (revealAbove > 0) widened.top = (widened.top - revealAbove).coerceAtLeast(0)
        wholePart()?.let(widened::union)
        return super.computeScrollDeltaToGetChildRectOnScreen(widened)
    }

    /** [revealWhole]'s bounds, while focus is in it and it fits on screen. */
    private fun wholePart(): Rect? {
        val part = revealWhole?.takeIf { it.isShown } ?: return null
        val focused = findFocus() ?: return null
        // In this scroller, round the focused view: measured from here, so it must be inside.
        if (generateSequence(focused) { it.parent as? android.view.View }.takeWhile { it !== this }.none { it === part }) return null
        if (part.height > height - paddingTop - paddingBottom) return null
        return Rect(0, 0, part.width, part.height).also { offsetDescendantRectToMyCoords(part, it) }
    }
}

/** The horizontal [FocusScrollView]: never a focus stop of its own. */
open class FocusHorizontalScrollView(context: Context) : HorizontalScrollView(context) {
    init { isFocusable = false; isFocusableInTouchMode = false }
}
