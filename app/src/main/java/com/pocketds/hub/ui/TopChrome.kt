package com.pocketds.hub.ui

import android.view.View
import java.lang.ref.WeakReference

/**
 * Where the top bar is, for a side sheet opening on a page that draws under
 * it (a title page, Home): the sheet starts below the bar rather than sliding
 * beneath it, where the bar's icons sat over the sheet's heading and its close
 * button. HubActivity registers its bar; the player hides it, and then there
 * is nothing to keep clear of.
 */
object TopChrome {
    private var bar: WeakReference<View>? = null

    fun register(view: View) {
        bar = WeakReference(view)
    }

    /** How far the bottom of a showing top bar reaches below [view]'s top on screen; 0 when it does not. */
    fun overlap(view: View): Int {
        val shown = bar?.get()?.takeIf { it.isShown } ?: return 0
        val barAt = IntArray(2).also(shown::getLocationOnScreen)
        val viewAt = IntArray(2).also(view::getLocationOnScreen)
        return (barAt[1] + shown.height - viewAt[1]).coerceAtLeast(0)
    }
}
