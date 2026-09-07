package com.pocketds.hub.nav

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.TextView
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler

/**
 * A thin in-layout strip for transient messages and for staleness.
 *
 * Not a `Toast`: a Toast opens its own window with its own focus rules, and on a
 * gamepad-driven UI a second window is how the hint bar goes stale behind it.
 * This one is part of the layout and cannot steal anything.
 *
 * Its other job matters more. When a screen is showing cached data because the
 * hub is unreachable, this says so in as many words -- "showing results from 4
 * minutes ago". Being explicit about staleness is the whole difference between a
 * useful offline mode and a confusing one.
 */
class StatusStripView(context: Context, private val colors: PocketColors) : TextView(context) {

    private val handler = Handler(Looper.getMainLooper())
    private val hide = Runnable { visibility = GONE }

    init {
        textSize = 12f
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(colors.warningStrip)
        setTextColor(colors.warningStripText)
        val padH = Styler.dpInt(context, 12f)
        val padV = Styler.dpInt(context, 6f)
        setPadding(padH, padV, padH, padV)
        visibility = GONE
    }

    /** A message that goes away on its own. */
    fun flash(message: String, millis: Long = 2_500L) {
        handler.removeCallbacks(hide)
        text = message
        visibility = VISIBLE
        handler.postDelayed(hide, millis)
    }

    /** A condition that persists until it is cleared -- stale data, a dead service. */
    fun hold(message: String) {
        handler.removeCallbacks(hide)
        text = message
        visibility = VISIBLE
    }

    fun clear() {
        handler.removeCallbacks(hide)
        visibility = GONE
    }
}
