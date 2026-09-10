package com.pocketds.hub.nav

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import androidx.appcompat.widget.AppCompatTextView
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
class StatusStripView(context: Context, private val colors: PocketColors) : AppCompatTextView(context) {

    private val handler = Handler(Looper.getMainLooper())
    private var messageVisible = false
    private var chromeVisible = true
    private val hide = Runnable {
        messageVisible = false
        syncVisibility()
    }

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
        messageVisible = message.isNotEmpty()
        syncVisibility()
        handler.postDelayed(hide, millis)
    }

    /** A condition that persists until it is cleared -- stale data, a dead service. */
    fun hold(message: String) {
        handler.removeCallbacks(hide)
        text = message
        messageVisible = message.isNotEmpty()
        syncVisibility()
    }

    fun clear() {
        handler.removeCallbacks(hide)
        messageVisible = false
        text = ""
        syncVisibility()
    }

    /** Immersive playback hides the chrome without turning an empty strip back on afterward. */
    fun setChromeVisible(visible: Boolean) {
        chromeVisible = visible
        syncVisibility()
    }

    private fun syncVisibility() {
        visibility = if (chromeVisible && messageVisible) VISIBLE else GONE
    }
}
