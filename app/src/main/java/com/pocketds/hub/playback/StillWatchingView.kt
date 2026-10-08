package com.pocketds.hub.playback

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.pocketds.hub.model.PlaybackItem
import com.pocketds.hub.ui.AppIcon
import com.pocketds.hub.ui.EpisodeLabel
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.PillButton
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Type
import com.pocketds.hub.ui.activateOnTap
import com.pocketds.hub.ui.textWeight
import com.pocketds.hub.ui.typeRole

/**
 * "Still watching?" (#48), over the video in the controls' dark glass: the player has paused because three episodes
 * in a row have started by themselves with nobody touching it. Any button carries on ([AutoplayRun]); this is
 * only the question and its one answer.
 */
class StillWatchingView(context: Context, colors: PocketColors, ringVisible: () -> Boolean) : LinearLayout(context) {
    var onContinue: (() -> Unit)? = null

    private val line = TextView(context).apply { textSize = 12.5f; setTextColor(Color.argb(210, 220, 226, 234)); gravity = Gravity.CENTER }
    val continueButton: TextView = PillButton.create(context, colors, AutoplayRun.CONTINUE, AppIcon.PLAY, primary = true, heightDp = 36f)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val pad = Styler.dpInt(context, 20f)
        setPadding(pad, pad, pad, Styler.dpInt(context, 14f))
        // Nearly solid, so what the controls drew behind it does not show through.
        background = com.pocketds.hub.ui.ThemeGradientDrawable.rounded(Styler.dp(context, 18f), Color.argb(246, 16, 20, 28))
        elevation = Styler.dp(context, 16f)
        isClickable = true
        visibility = GONE
        addView(TextView(context).apply {
            text = AutoplayRun.QUESTION
            typeRole(Type.Role.HEADING, 22f); setTextColor(Color.WHITE); gravity = Gravity.CENTER
        })
        addView(line, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = Styler.dpInt(context, 6f) })
        addView(continueButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = Styler.dpInt(context, 14f) })
        FocusDecorator.attach(continueButton, ringVisible, scale = false)
        continueButton.activateOnTap { onContinue?.invoke() }
    }

    /** Asks, naming what comes next. */
    fun ask(next: PlaybackItem?, episodes: Int) {
        val after = next?.let { listOf(EpisodeLabel.code(it.seasonNumber, it.episodeNumber), it.title.ifBlank { it.displayTitle() }).filter(String::isNotBlank).joinToString(" · ") }
        line.text = buildString {
            append("Paused after $episodes episodes in a row.")
            if (!after.isNullOrBlank()) append("\nNext: ").append(after)
        }
        visibility = VISIBLE
        bringToFront()
        continueButton.requestFocus()
    }

    fun dismiss() { visibility = GONE }

    val asking: Boolean get() = visibility == View.VISIBLE
}
