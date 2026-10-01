package com.pocketds.hub.playback

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.ThemeGradientDrawable
import com.pocketds.hub.ui.activateOnTap

/**
 * The player's on-screen controls: the top bar, the timeline and transport
 * row, the seek preview, the gesture readout and the next-episode panel.
 *
 * Built here and handed to [PlayerScreen], which owns what they do. The
 * construction was 300 lines of PlayerScreen assigning 25 of its fields as it
 * went; separating it leaves the screen with behaviour, and the views with one
 * place that decides how a control looks.
 */
internal class PlayerChrome(
    private val context: Context,
    private val colors: PocketColors,
    private val actions: Actions,
    seekSeconds: Int,
    timeline: SeekBar.OnSeekBarChangeListener
) {
    interface Actions {
        fun showTracks()
        fun showOptions()
        fun toggleLock()
        fun enterPictureInPicture()
        fun close()
        fun playPrevious()
        fun rewind()
        fun togglePlay()
        fun forward()
        fun skipSegment()
        fun playNext()
        fun cancelNext()
        /** A control took focus: keep the controls up. */
        fun controlFocused()
    }

    lateinit var titleView: TextView; private set
    lateinit var tracksButton: PlayerIconButton; private set
    lateinit var castButton: MediaRouteButton; private set
    lateinit var optionsButton: PlayerIconButton; private set
    lateinit var lockButton: PlayerIconButton; private set
    lateinit var pipButton: PlayerIconButton; private set
    lateinit var closeButton: PlayerIconButton; private set
    lateinit var position: TextView; private set
    lateinit var seekBar: SeekBar; private set
    lateinit var duration: TextView; private set
    lateinit var previousButton: PlayerIconButton; private set
    lateinit var rewindButton: PlayerIconButton; private set
    lateinit var playButton: PlayerIconButton; private set
    lateinit var forwardButton: PlayerIconButton; private set
    lateinit var skipButton: PlayerIconButton; private set
    lateinit var nextButton: PlayerIconButton; private set
    lateinit var seekPreviewImage: ImageView; private set
    /** Holds the frame; hidden until a frame has loaded, so the preview is a picture or just the time. */
    lateinit var seekPreviewFrame: FrameLayout; private set
    lateinit var seekPreviewTime: TextView; private set
    lateinit var seekPreviewDelta: TextView; private set
    lateinit var nextText: TextView; private set

    val top: LinearLayout = buildTop()
    val controller: LinearLayout = buildController(seekSeconds, timeline)
    val seekPreview: LinearLayout = buildSeekPreview()
    val gestureFeedback: TextView = buildGestureFeedback()
    val nextPanel: LinearLayout = buildNextPanel()

    fun setLocked(locked: Boolean) {
        lockButton.setIcon(if (locked) PlayerControlIcon.LOCK else PlayerControlIcon.UNLOCK)
        lockButton.contentDescription = if (locked) "Unlock touch controls" else "Lock touch controls"
    }

    private fun buildTop(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(22), dp(14), dp(16), dp(18))
        background = ThemeGradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(225, 0, 0, 0), Color.TRANSPARENT)
        )
        titleView = TextView(context).apply {
            textSize = 18f
            setTextColor(Color.WHITE)
            maxLines = 2
            setTypeface(typeface, Typeface.BOLD)
        }
        addView(titleView, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(10) })
        tracksButton = control(PlayerControlIcon.TRACKS, "Audio and subtitles", actions::showTracks)
        addView(tracksButton)
        castButton = MediaRouteButton(context).apply {
            contentDescription = "Play on a TV"
            isFocusable = true
            isFocusableInTouchMode = true
            CastButtonFactory.setUpMediaRouteButton(context, this)
        }
        addView(castButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        optionsButton = control(PlayerControlIcon.OPTIONS, "Playback options, chapters, speed and aspect", actions::showOptions)
        addView(optionsButton)
        lockButton = control(PlayerControlIcon.UNLOCK, "Lock touch controls", actions::toggleLock)
        addView(lockButton)
        pipButton = control(PlayerControlIcon.PICTURE_IN_PICTURE, "Open picture in picture", actions::enterPictureInPicture)
        addView(pipButton)
        closeButton = control(PlayerControlIcon.CLOSE, "Close playback", actions::close)
        addView(closeButton)
    }

    private fun buildController(seekSeconds: Int, timeline: SeekBar.OnSeekBarChangeListener): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(26), dp(18), dp(26), dp(20))
            background = ThemeGradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, Color.argb(225, 0, 0, 0))
            )
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                position = timeText("0:00")
                addView(position, LinearLayout.LayoutParams(dp(64), WRAP))
                seekBar = SeekBar(context).apply {
                    max = 10_000
                    contentDescription = "Playback position"
                    Styler.makeFocusable(this)
                    setOnFocusChangeListener { _, focused -> if (focused) actions.controlFocused() }
                    setOnSeekBarChangeListener(timeline)
                }
                addView(seekBar, LinearLayout.LayoutParams(0, WRAP, 1f))
                duration = timeText("0:00")
                addView(duration, LinearLayout.LayoutParams(dp(64), WRAP))
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER
                previousButton = control(PlayerControlIcon.PREVIOUS, "Play previous episode", actions::playPrevious)
                addView(previousButton)
                rewindButton = control(PlayerControlIcon.REWIND, "Jump back $seekSeconds seconds", actions::rewind)
                addView(rewindButton)
                playButton = control(PlayerControlIcon.PLAY, "Play", actions::togglePlay)
                addView(playButton)
                forwardButton = control(PlayerControlIcon.FORWARD, "Jump forward $seekSeconds seconds", actions::forward)
                addView(forwardButton)
                skipButton = control(PlayerControlIcon.SKIP, "Skip current segment", actions::skipSegment)
                    .apply { visibility = View.GONE }
                addView(skipButton)
                nextButton = control(PlayerControlIcon.NEXT, "Play next episode", actions::playNext)
                addView(nextButton)
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        }

    private fun buildSeekPreview(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        visibility = View.GONE
        isClickable = false
        background = ThemeGradientDrawable().apply {
            cornerRadius = Styler.dp(context, 12f)
            setColor(Color.argb(235, 22, 24, 29))
            setStroke(dp(1), Color.argb(120, 255, 255, 255))
        }
        setPadding(dp(10), dp(10), dp(10), dp(9))
        seekPreviewFrame = FrameLayout(context).apply {
            visibility = View.GONE
            seekPreviewImage = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            addView(seekPreviewImage, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        addView(seekPreviewFrame, LinearLayout.LayoutParams(MATCH, dp(94)))
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER
            seekPreviewTime = TextView(context).apply {
                textSize = 17f
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
            }
            addView(seekPreviewTime)
            seekPreviewDelta = TextView(context).apply {
                textSize = 13f
                setTextColor(this@PlayerChrome.colors.accent)
                setPadding(dp(12), 0, 0, 0)
            }
            addView(seekPreviewDelta)
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(7) })
    }

    private fun buildGestureFeedback(): TextView = TextView(context).apply {
        visibility = View.GONE
        gravity = Gravity.CENTER
        textSize = 17f
        setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(18), dp(12), dp(18), dp(12))
        background = ThemeGradientDrawable().apply {
            cornerRadius = Styler.dp(context, 18f)
            setColor(Color.argb(225, 22, 24, 29))
            setStroke(dp(1), Color.argb(110, 255, 255, 255))
        }
    }

    private fun buildNextPanel(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        background = ThemeGradientDrawable().apply {
            cornerRadius = Styler.dp(context, 14f)
            setColor(Color.argb(235, 25, 25, 30))
            setStroke(dp(2), this@PlayerChrome.colors.accent)
        }
        setPadding(dp(18), dp(16), dp(18), dp(16))
        nextText = TextView(context).apply { textSize = 16f; setTextColor(Color.WHITE) }
        addView(nextText)
        addView(LinearLayout(context).apply {
            gravity = Gravity.END
            addView(control(PlayerControlIcon.CLOSE, "Cancel next episode", actions::cancelNext))
            addView(control(PlayerControlIcon.NEXT, "Play next episode now", actions::playNext))
        })
    }

    private fun control(icon: PlayerControlIcon, description: String, action: () -> Unit) =
        PlayerIconButton(context, icon).apply {
            contentDescription = description
            // Player controls sit directly on the video. A focused control gets
            // a thin, high-contrast ring but never becomes an opaque blue tile.
            background = bareButtonBackground()
            Styler.makeFocusable(this)
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            activateOnTap(action)
            setOnFocusChangeListener { _, focused -> if (focused) actions.controlFocused() }
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(7) }
        }

    private fun bareButtonBackground(): StateListDrawable {
        fun face(fill: Int, strokeWidth: Int = 0, strokeColor: Int = 0) = ThemeGradientDrawable().apply {
            cornerRadius = Styler.dp(context, 11f)
            setColor(fill)
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), face(Color.argb(80, 0, 0, 0), dp(2), colors.focusRing))
            addState(intArrayOf(android.R.attr.state_pressed), face(Color.argb(75, 0, 0, 0)))
            addState(intArrayOf(), face(Color.TRANSPARENT))
        }
    }

    private fun timeText(value: String) = TextView(context).apply {
        text = value
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
    }

    private fun dp(value: Int) = Styler.dpInt(context, value.toFloat())

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
