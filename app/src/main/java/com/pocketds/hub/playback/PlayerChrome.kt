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
 *
 * Every control but Play is dark glass (GLASS_PLAN.md › Player),
 * tinted by what is playing ([com.pocketds.hub.ui.OverlayButtons]), the tools
 * named in words carry their icons, and the timeline with its times sits in a
 * frosted bar: a white line on a faint track, the buffered part lighter, a
 * white thumb. Play stays the white disc with dark ink.
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

    /** How much is loaded ahead, a lighter part of the line (the prototype's `.buf`). */
    fun showBuffered(fraction: Double) {
        seekBar.secondaryProgress = (fraction.coerceIn(0.0, 1.0) * seekBar.max).toInt()
    }

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
        tracksButton = pill("Audio & subtitles", "Audio and subtitles", com.pocketds.hub.ui.AppIcon.SUBTITLES, actions::showTracks)
        addView(tracksButton)
        chaptersButton = pill("Chapters", "Chapters", com.pocketds.hub.ui.AppIcon.CONTENTS, actions::showChapters)
        addView(chaptersButton)
        optionsButton = pill("This video", "Quality, speed and aspect for this video", null, actions::showOptions)
        addView(optionsButton)
        castButton = MediaRouteButton(context).apply {
            contentDescription = "Play on a TV"
            isFocusable = true
            isFocusableInTouchMode = true
            com.pocketds.hub.ui.OverlayButtons.dressDisc(this, colors.focusRing)
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
            setIconColor(com.pocketds.hub.ui.OverlayButtons.PLAY_INK, halo = false)
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

    /**
     * The timeline across the whole width; the time and chapter under its
     * start, the time left under its end, both in a frosted bar 14dp in from
     * the edges (the prototype's `.pl-bot`).
     */
    private fun buildController(timeline: SeekBar.OnSeekBarChangeListener): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(30), dp(14), dp(10))
            background = ThemeGradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, Color.argb(215, 0, 0, 0))
            )
            val bar = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                com.pocketds.hub.ui.OverlayButtons.panel(this, GLASS_BAR_CORNER_DP)
                setPadding(dp(4), dp(4), dp(4), dp(6))
            }.also { addView(it, LinearLayout.LayoutParams(MATCH, WRAP)) }
            seekBar = ChapterSeekBar(context).apply {
                max = 10_000
                contentDescription = "Playback position"
                Styler.makeFocusable(this)
                setOnFocusChangeListener { _, focused -> if (focused) actions.controlFocused() }
                setOnSeekBarChangeListener(timeline)
                glassLine(this)
            }
            bar.addView(seekBar, LinearLayout.LayoutParams(MATCH, WRAP))
            bar.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(2), dp(8), 0)
                position = timeText("0:00").apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }
                addView(position, LinearLayout.LayoutParams(0, WRAP, 1f))
                duration = timeText("0:00").apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
                addView(duration)
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        }

    /**
     * The prototype's line (`.pl-line`), 6dp: white on a faint track,
     * the buffered part a little lighter, and a white thumb in a soft halo.
     */
    private fun glassLine(bar: SeekBar) {
        val radius = Styler.dp(context, 3f)
        fun line(color: Int) = ThemeGradientDrawable.rounded(radius, color)
        fun clipped(color: Int) = android.graphics.drawable.ClipDrawable(line(color), Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL)
        bar.progressDrawable = LayerDrawable(arrayOf(line(GLASS_TRACK), clipped(GLASS_BUFFERED), clipped(Color.WHITE))).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.secondaryProgress)
            setId(2, android.R.id.progress)
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            bar.minHeight = dp(GLASS_LINE_DP)
            bar.maxHeight = dp(GLASS_LINE_DP)
        }
        // With focus the halo becomes the white ring, standing off the thumb as
        // every ring does: left and right then seek, so where focus is must show.
        val halo = dp(GLASS_THUMB_HALO_DP)
        val side = dp(GLASS_THUMB_DP) + 2 * halo
        fun thumb(focused: Boolean) = LayerDrawable(arrayOf(
            (if (focused) ThemeGradientDrawable.oval(Color.TRANSPARENT, dp(2f), colors.focusRing)
                else ThemeGradientDrawable.oval(GLASS_THUMB_HALO)).apply { setSize(side, side) },
            InsetDrawable(ThemeGradientDrawable.oval(Color.WHITE), halo)
        ))
        bar.thumb = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), thumb(true))
            addState(intArrayOf(), thumb(false))
        }
        bar.splitTrack = false
    }

    private fun buildSeekPreview(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        visibility = View.GONE
        isClickable = false
        com.pocketds.hub.ui.OverlayButtons.panel(this, 12f)
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
        com.pocketds.hub.ui.OverlayButtons.panel(this, 18f)
    }


    /** A round button on a disc of dark glass: back, cast, lock, picture in picture, previous and next. */
    private fun round(icon: PlayerControlIcon, description: String, action: () -> Unit) =
        PlayerIconButton(context, icon).apply {
            contentDescription = description
            com.pocketds.hub.ui.OverlayButtons.dressDisc(this, colors.focusRing)
            // On dark glass the white symbol needs no halo.
            setIconColor(Color.WHITE, halo = false)
            Styler.makeFocusable(this)
            activateOnTap(action)
            setOnFocusChangeListener { _, focused -> if (focused) actions.controlFocused() }
        }

    /** "Audio & subtitles", "Chapters", "This video": what a tool opens, in words, with its [icon]. */
    private fun pill(label: String, description: String, icon: com.pocketds.hub.ui.AppIcon?, action: () -> Unit) =
        com.pocketds.hub.ui.OverlayButtons.pill(context, colors.focusRing, label, description, icon, action).apply {
            setOnFocusChangeListener { _, focused -> if (focused) actions.controlFocused() }
            layoutParams = LinearLayout.LayoutParams(WRAP, dp(44)).apply { marginStart = dp(2) }
        }

    /** −10 and +10: the jump, written on the disc. */
    private fun seekCircle(label: String, description: String, action: () -> Unit) =
        com.pocketds.hub.ui.OverlayButtons.jump(context, colors.focusRing, label, description, action).apply {
            setOnFocusChangeListener { _, focused -> if (focused) actions.controlFocused() }
        }

    /** Play: a white disc. */
    private fun discBackground(): StateListDrawable = com.pocketds.hub.ui.OverlayButtons.playFace(context, colors.focusRing)

    private fun timeText(value: String) = TextView(context).apply {
        text = value
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(Color.argb(255, 213, 219, 227))
        textDirection = View.TEXT_DIRECTION_LTR
    }

    private fun dp(value: Int) = Styler.dpInt(context, value.toFloat())
    private fun dp(value: Float) = Styler.dpInt(context, value)

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        /**
         * Glass, the prototype's Pocket player: the bar's 16dp corners, a 6dp
         * line on a track of white at 22% with the buffered part at 30%, and a
         * 16dp thumb in a 4dp halo of white at 25%.
         */
        const val GLASS_BAR_CORNER_DP = 16f
        const val GLASS_LINE_DP = 6f
        const val GLASS_TRACK = 0x38FFFFFF
        const val GLASS_BUFFERED = 0x4DFFFFFF
        const val GLASS_THUMB_DP = 16f
        const val GLASS_THUMB_HALO_DP = 4f
        const val GLASS_THUMB_HALO = 0x40FFFFFF
    }
}
