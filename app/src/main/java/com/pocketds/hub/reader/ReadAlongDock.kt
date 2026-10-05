package com.pocketds.hub.reader

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.pocketds.hub.playback.PlayerControlIcon
import com.pocketds.hub.playback.PlayerIconButton
import com.pocketds.hub.state.Fmt
import com.pocketds.hub.ui.AppIcon
import com.pocketds.hub.ui.OverlayButtons
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.ProgressLine
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.ThemeGradientDrawable
import com.pocketds.hub.ui.activateOnTap

/**
 * The read-along player (#16, X7), the prototype's glass dock: a line for how
 * far through this part of the narration, "Read along" over the time, −10, a
 * white Play and +10 in the middle, the speed and a way back to the sentence
 * being read at the end. Glass, tinted by the cover, with the player's discs;
 * on Classic a dark card. It takes the lower bar's place while the controls
 * show.
 */
class ReadAlongDock(context: Context, colors: PocketColors) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private fun dp(value: Int) = (value * density + .5f).toInt()
    val focusableControls = mutableListOf<View>()
    var onBack: () -> Unit = {}
    var onPlay: () -> Unit = {}
    var onForward: () -> Unit = {}
    var onSpeed: () -> Unit = {}
    var onFollow: () -> Unit = {}

    private val line: ProgressBar = ProgressLine.create(context, colors, Color.WHITE)
    private val timeLabel = TextView(context).apply {
        text = "0:00 of 0:00"
        textSize = 11.5f
        setTextColor(ReaderBars.SOFT_TEXT)
        maxLines = 1
    }
    private val playButton = PlayerIconButton(context, PlayerControlIcon.PLAY).apply {
        contentDescription = "Play narration"
        setIconColor(OverlayButtons.PLAY_INK, halo = false)
        background = OverlayButtons.playFace(context, colors.focusRing)
        Styler.makeFocusable(this)
        activateOnTap { onPlay() }
    }
    private val speedButton: TextView = OverlayButtons.pill(context, colors.focusRing, "1×", "Change narration speed") { onSpeed() }

    init {
        orientation = VERTICAL
        setPadding(dp(14), dp(8), dp(10), dp(6))
        if (!OverlayButtons.panel(this, 18f)) background = ThemeGradientDrawable().apply {
            setColor(0xF2212A31.toInt())
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), 0x66FFFFFF)
        }
        line.progress = 0
        addView(line, LayoutParams(LayoutParams.MATCH_PARENT, dp(4)).apply { bottomMargin = dp(4) })
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, dp(52)))
        val words = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        words.addView(TextView(context).apply {
            text = "Read along"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        words.addView(timeLabel)
        row.addView(words, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        row.addView(control(OverlayButtons.jump(context, colors.focusRing, "−10", "Back 10 seconds") { onBack() }),
            LayoutParams(dp(44), dp(44)))
        row.addView(control(playButton), LayoutParams(dp(52), dp(52)).apply { marginStart = dp(10); marginEnd = dp(10) })
        row.addView(control(OverlayButtons.jump(context, colors.focusRing, "+10", "Forward 10 seconds") { onForward() }),
            LayoutParams(dp(44), dp(44)))
        val end = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL or Gravity.END }
        end.addView(control(speedButton), LayoutParams(LayoutParams.WRAP_CONTENT, dp(44)))
        end.addView(control(OverlayButtons.round(context, colors.focusRing, AppIcon.READ_ALONG, "Return to narrated sentence") { onFollow() }),
            LayoutParams(dp(44), dp(44)).apply { marginStart = dp(2) })
        row.addView(end, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        visibility = View.GONE
    }

    private fun <T : View> control(view: T): T = view.also { focusableControls += it }

    fun update(playing: Boolean, position: ReadAlongPosition, timeline: ReadAlongTimeline, speed: Float) {
        playButton.setIcon(if (playing) PlayerControlIcon.PAUSE else PlayerControlIcon.PLAY)
        playButton.contentDescription = if (playing) "Pause narration" else "Play narration"
        timeLabel.text = ReadAlongDockText.time(position, timeline)
        line.progress = (ReadAlongDockText.fraction(position, timeline) * ProgressLine.MAX).toInt()
        speedButton.text = ReadAlongDockText.speed(speed)
    }

    companion object {
        /** The dock's height, line and row: what a book's page makes room for. */
        const val HEIGHT_DP = 8 + 4 + 4 + 52 + 6
    }
}

/** The dock's words, pure so a JVM test pins them. */
object ReadAlongDockText {
    /** "4:13 of 27:05" in the part playing, and which part of how many when there are several. */
    fun time(position: ReadAlongPosition, timeline: ReadAlongTimeline): String {
        val track = timeline.tracks.getOrNull(position.track) ?: return "0:00 of 0:00"
        val clock = "${Fmt.clock(position.offsetMs.coerceIn(0, track.durationMs))} of ${Fmt.clock(track.durationMs)}"
        return if (timeline.tracks.size > 1) "$clock · part ${position.track + 1} of ${timeline.tracks.size}" else clock
    }

    /** How far through the part playing, 0..1. */
    fun fraction(position: ReadAlongPosition, timeline: ReadAlongTimeline): Double {
        val track = timeline.tracks.getOrNull(position.track) ?: return 0.0
        if (track.durationMs <= 0) return 0.0
        return (position.offsetMs.toDouble() / track.durationMs).coerceIn(0.0, 1.0)
    }

    /** "1×", "1.25×": the speed without a trailing ".0". */
    fun speed(value: Float): String {
        val text = if (value == value.toInt().toFloat()) value.toInt().toString() else String.format(java.util.Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
        return "$text×"
    }
}
