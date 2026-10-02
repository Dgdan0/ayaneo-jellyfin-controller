package com.pocketds.hub.playback

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
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
 * row, the seek preview and the gesture readout. The up-next card and the
 * Skip intro button are [PlayerScreen]'s, since they show without the rest.
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
        fun showChapters()
        fun showOptions()
        fun toggleLock()
        fun enterPictureInPicture()
        fun close()
        fun playPrevious()
        fun rewind()
        fun togglePlay()
        fun forward()
        fun playNext()
        /** A control took focus: keep the controls up. */
        fun controlFocused()
    }

    lateinit var titleView: TextView; private set
    /** Under the title: "S1E1 · Somewhere Not Here", or Offline. */
    lateinit var subtitleView: TextView; private set
    lateinit var tracksButton: TextView; private set
    lateinit var chaptersButton: TextView; private set
    lateinit var castButton: MediaRouteButton; private set
    lateinit var optionsButton: TextView; private set
    lateinit var lockButton: PlayerIconButton; private set
    lateinit var pipButton: PlayerIconButton; private set
    lateinit var closeButton: PlayerIconButton; private set
    lateinit var position: TextView; private set
    lateinit var seekBar: ChapterSeekBar; private set
    lateinit var duration: TextView; private set
    lateinit var previousButton: PlayerIconButton; private set
    lateinit var rewindButton: TextView; private set
    lateinit var playButton: PlayerIconButton; private set
    lateinit var forwardButton: TextView; private set
    lateinit var nextButton: PlayerIconButton; private set
    lateinit var seekPreviewImage: ImageView; private set
    /** Holds the frame; hidden until a frame has loaded, so the preview is a picture or just the time. */
    lateinit var seekPreviewFrame: FrameLayout; private set
    lateinit var seekPreviewTime: TextView; private set
    lateinit var seekPreviewDelta: TextView; private set

    val top: LinearLayout = buildTop()
    /** Previous, back, play, forward, next: in the middle of the picture. */
    val center: LinearLayout = buildCenter(seekSeconds)
    val controller: LinearLayout = buildController(timeline)
    val seekPreview: LinearLayout = buildSeekPreview()
    val gestureFeedback: TextView = buildGestureFeedback()

    fun setLocked(locked: Boolean) {
        lockButton.setIcon(if (locked) PlayerControlIcon.LOCK else PlayerControlIcon.UNLOCK)
        lockButton.contentDescription = if (locked) "Unlock touch controls" else "Lock touch controls"
    }

    /**
     * Back at the left, the title and what is playing, then the tools: text
     * pills for what they open, round buttons for the rest.
     */
    private fun buildTop(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), dp(14), dp(18), dp(26))
        background = ThemeGradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(190, 0, 0, 0), Color.TRANSPARENT)
        )
        closeButton = round(PlayerControlIcon.BACK, "Close playback", actions::close)
        addView(closeButton, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(10) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            titleView = TextView(context).apply {
                com.pocketds.hub.ui.Type.apply(this, com.pocketds.hub.ui.Type.Role.HEADING, 17f)
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                setShadowLayer(4f, 0f, 0f, Color.argb(160, 0, 0, 0))
            }
            addView(titleView)
            subtitleView = TextView(context).apply {
                textSize = 12f
                setTextColor(Color.argb(255, 213, 219, 227))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                textDirection = View.TEXT_DIRECTION_LTR
                setPadding(0, dp(2), 0, 0)
                setShadowLayer(4f, 0f, 0f, Color.argb(160, 0, 0, 0))
            }
            addView(subtitleView)
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(8) })
        tracksButton = pill("Audio & subtitles", "Audio and subtitles", actions::showTracks)
        addView(tracksButton)
        chaptersButton = pill("Chapters", "Chapters", actions::showChapters)
        addView(chaptersButton)
        optionsButton = pill("This video", "Quality, speed and aspect for this video", actions::showOptions)
        addView(optionsButton)
        castButton = MediaRouteButton(context).apply {
            contentDescription = "Play on a TV"
            isFocusable = true
            isFocusableInTouchMode = true
            background = roundBackground()
            CastButtonFactory.setUpMediaRouteButton(context, this)
            setOnFocusChangeListener { _, focused -> if (focused) actions.controlFocused() }
        }
        addView(castButton, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(4) })
        lockButton = round(PlayerControlIcon.UNLOCK, "Lock touch controls", actions::toggleLock)
        addView(lockButton, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(2) })
        pipButton = round(PlayerControlIcon.PICTURE_IN_PICTURE, "Open picture in picture", actions::enterPictureInPicture)
        addView(pipButton, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(2) })
    }

    private fun buildCenter(seekSeconds: Int): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        clipChildren = false
        clipToPadding = false
        setPadding(dp(8), dp(8), dp(8), dp(8))
        previousButton = round(PlayerControlIcon.PREVIOUS, "Play previous episode", actions::playPrevious)
        addView(previousButton, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(20) })
        rewindButton = seekCircle("−$seekSeconds", "Jump back $seekSeconds seconds", actions::rewind)
        addView(rewindButton, LinearLayout.LayoutParams(dp(58), dp(58)))
        playButton = PlayerIconButton(context, PlayerControlIcon.PLAY).apply {
            contentDescription = "Play"
            setIconColor(Color.argb(255, 10, 13, 18), halo = false)
            background = discBackground()
            Styler.makeFocusable(this)
            activateOnTap(actions::togglePlay)
            setOnFocusChangeListener { _, focused -> if (focused) actions.controlFocused() }
        }
        addView(playButton, LinearLayout.LayoutParams(dp(80), dp(80)).apply { marginStart = dp(28); marginEnd = dp(28) })
        forwardButton = seekCircle("+$seekSeconds", "Jump forward $seekSeconds seconds", actions::forward)
        addView(forwardButton, LinearLayout.LayoutParams(dp(58), dp(58)))
        nextButton = round(PlayerControlIcon.NEXT, "Play next episode", actions::playNext)
        addView(nextButton, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(20) })
    }

    /** The timeline across the whole width; the time and chapter under its start, the time left under its end. */
    private fun buildController(timeline: SeekBar.OnSeekBarChangeListener): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(34), dp(20), dp(12))
            background = ThemeGradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, Color.argb(215, 0, 0, 0))
            )
            seekBar = ChapterSeekBar(context).apply {
                max = 10_000
                contentDescription = "Playback position"
                Styler.makeFocusable(this)
                setOnFocusChangeListener { _, focused -> if (focused) actions.controlFocused() }
                setOnSeekBarChangeListener(timeline)
            }
            addView(seekBar, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(2), dp(8), 0)
                position = timeText("0:00").apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }
                addView(position, LinearLayout.LayoutParams(0, WRAP, 1f))
                duration = timeText("0:00").apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
                addView(duration)
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

    /** A round button on a soft disc: back, cast, lock, picture in picture, previous and next. */
    private fun round(icon: PlayerControlIcon, description: String, action: () -> Unit) =
        PlayerIconButton(context, icon).apply {
            contentDescription = description
            background = roundBackground()
            Styler.makeFocusable(this)
            activateOnTap(action)
            setOnFocusChangeListener { _, focused -> if (focused) actions.controlFocused() }
        }

    /** "Audio & subtitles", "Chapters", "This video": what a tool opens, in words. */
    private fun pill(label: String, description: String, action: () -> Unit) =
        com.pocketds.hub.ui.OverlayButtons.pill(context, colors.focusRing, label, description, action).apply {
            setOnFocusChangeListener { _, focused -> if (focused) actions.controlFocused() }
            layoutParams = LinearLayout.LayoutParams(WRAP, dp(44)).apply { marginStart = dp(2) }
        }

    /** −10 and +10: the jump, written on the disc. */
    private fun seekCircle(label: String, description: String, action: () -> Unit) = TextView(context).apply {
        text = label
        contentDescription = description
        textSize = 13f
        typeface = com.pocketds.hub.ui.Type.text(context, 700)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = roundBackground()
        Styler.makeFocusable(this)
        activateOnTap(action)
        setOnFocusChangeListener { _, focused -> if (focused) actions.controlFocused() }
    }

    private fun roundBackground(): StateListDrawable = com.pocketds.hub.ui.OverlayButtons.disc(context, colors.focusRing)

    /** Play: a white disc. */
    private fun discBackground(): StateListDrawable = com.pocketds.hub.ui.OverlayButtons.ringed(
        context, colors.focusRing, GradientDrawable.OVAL, Color.WHITE, pressed = Color.argb(255, 214, 219, 226))

    private fun timeText(value: String) = TextView(context).apply {
        text = value
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(Color.argb(255, 213, 219, 227))
        textDirection = View.TEXT_DIRECTION_LTR
    }

    private fun dp(value: Int) = Styler.dpInt(context, value.toFloat())

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
