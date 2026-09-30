package com.pocketds.hub.reader

import com.pocketds.hub.ui.ThemeGradientDrawable
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.pocketds.hub.playback.PlayerControlIcon
import com.pocketds.hub.playback.PlayerIconButton
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.state.Fmt

/** Compact audio controls stay available even when the book's full chrome is hidden. */
class ReadAlongDock(context: Context) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private fun dp(value: Int) = (value * density + .5f).toInt()
    val focusableControls = mutableListOf<View>()
    private val playButton = icon(PlayerControlIcon.PLAY, "Play narration")
    private val timeLabel = TextView(context).apply {
        text = "0:00 / 0:00"
        textSize = 11f
        setTextColor(0xFFD3DFE3.toInt())
        gravity = Gravity.CENTER
        minWidth = dp(104)
    }
    private val extra = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
    }
    private val speedButton = textButton("1×", "Change narration speed")
    private val followButton = textButton("↗ Text", "Return to narrated sentence")
    private val trackLabel = TextView(context).apply {
        textSize = 11f
        setTextColor(0xFFAFC3C8.toInt())
        gravity = Gravity.CENTER_VERTICAL
    }
    var onBack: () -> Unit = {}
    var onPlay: () -> Unit = {}
    var onForward: () -> Unit = {}
    var onSpeed: () -> Unit = {}
    var onFollow: () -> Unit = {}
    private var expanded = false

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(5), dp(2), dp(5), dp(2))
        background = ThemeGradientDrawable().apply {
            setColor(0xF2212A31.toInt())
            cornerRadius = dp(15).toFloat()
            setStroke(dp(1), 0xFF466F73.toInt())
        }
        elevation = dp(9).toFloat()
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        addView(row)
        row.addView(icon(PlayerControlIcon.REWIND, "Back 10 seconds").also {
            it.setOnClickListener { onBack() }
        })
        row.addView(playButton.also { it.setOnClickListener { onPlay() } })
        row.addView(icon(PlayerControlIcon.FORWARD, "Forward 10 seconds").also {
            it.setOnClickListener { onForward() }
        })
        row.addView(timeLabel)
        row.addView(textButton("⌄", "More narration controls").also {
            it.setOnClickListener { setExpanded(!expanded) }
        })
        addView(extra)
        extra.addView(speedButton.also { it.setOnClickListener { onSpeed() } })
        extra.addView(followButton.also { it.setOnClickListener { onFollow() } })
        extra.addView(trackLabel)
        visibility = View.GONE
    }

    private fun icon(value: PlayerControlIcon, label: String) = PlayerIconButton(context, value).apply {
        contentDescription = label
        layoutParams = LayoutParams(dp(48), dp(46))
        Styler.makeFocusable(this)
        focusableControls += this
    }

    private fun textButton(value: String, label: String) = TextView(context).apply {
        text = value
        textSize = 13f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        contentDescription = label
        minWidth = dp(48)
        minHeight = dp(44)
        setPadding(dp(7), 0, dp(7), 0)
        Styler.makeFocusable(this)
        focusableControls += this
    }

    private fun setExpanded(value: Boolean) {
        expanded = value
        extra.visibility = if (value) View.VISIBLE else View.GONE
    }

    fun update(playing: Boolean, position: ReadAlongPosition, timeline: ReadAlongTimeline, speed: Float) {
        playButton.setIcon(if (playing) PlayerControlIcon.PAUSE else PlayerControlIcon.PLAY)
        playButton.contentDescription = if (playing) "Pause narration" else "Play narration"
        val elapsed = timeline.tracks.take(position.track).sumOf { it.durationMs } + position.offsetMs
        val total = timeline.tracks.sumOf { it.durationMs }
        timeLabel.text = "${Fmt.clock(elapsed)} / ${Fmt.clock(total)}"
        trackLabel.text = "   Track ${position.track + 1}/${timeline.tracks.size}"
        speedButton.text = "${speed}×"
    }

}
