package com.pocketds.hub.ui.glass

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.pocketds.hub.ui.AppIcon
import com.pocketds.hub.ui.AppIconDrawable
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.ThemeGradientDrawable

/**
 * What a Glass still carries, on Home's tiles and a title's episodes alike
 * (the prototype's `.pic`): the watch progress as a white bar inside the
 * picture on a faint track, a play disc of the page's glass while the card has
 * focus, and a tick in the accent once watched. One set, so the two cards
 * cannot drift apart.
 */
class GlassStillMarks(context: Context, colors: PocketColors, frame: FrameLayout) {
    private val track = GlassProgressBar(context)
    private val disc = FrameLayout(context).apply {
        GlassPanelDrawable.attach(this, dp(context, 23).toFloat())
        alpha = 0f
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(ImageView(context).apply { setImageDrawable(AppIconDrawable(AppIcon.PLAY, Color.WHITE)) },
            FrameLayout.LayoutParams(dp(context, 18), dp(context, 18), Gravity.CENTER).apply { leftMargin = dp(context, 2) })
    }
    private val tick = TextView(context).apply {
        text = "✓"
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(colors.accentText)
        background = ThemeGradientDrawable.oval(colors.accent)
        visibility = View.GONE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    init {
        // The prototype's 6% inset from the edges of a 186dp tile.
        val inset = dp(context, INSET_DP)
        frame.addView(track, FrameLayout.LayoutParams(-1, dp(context, GlassProgressBar.HEIGHT_DP.toInt()), Gravity.BOTTOM).apply { setMargins(inset, 0, inset, inset) })
        frame.addView(disc, FrameLayout.LayoutParams(dp(context, 46), dp(context, 46), Gravity.CENTER))
        frame.addView(tick, FrameLayout.LayoutParams(dp(context, 22), dp(context, 22), Gravity.TOP or Gravity.END).apply {
            setMargins(dp(context, 6), dp(context, 6), dp(context, 6), dp(context, 6))
        })
    }

    /** How far in (nothing for none or finished) and whether it is watched. */
    fun bind(fraction: Double, watched: Boolean) {
        track.fraction = fraction
        tick.visibility = if (watched) View.VISIBLE else View.GONE
    }

    /** The play disc fades in on the focused card only, where A or X would start it. */
    fun focus(focused: Boolean) {
        disc.animate().alpha(if (focused) 1f else 0f).setDuration(200).start()
    }

    private companion object {
        const val INSET_DP = 11

        fun dp(context: Context, value: Int) = Styler.dpInt(context, value.toFloat())
    }
}
