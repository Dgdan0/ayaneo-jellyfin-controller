package com.pocketds.hub.playback

import com.pocketds.hub.ui.Artwork
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastState
import com.google.android.gms.cast.framework.CastStateListener
import com.google.common.util.concurrent.ListenableFuture
import coil.ImageLoader
import coil.request.Disposable
import coil.request.ImageRequest
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.debug.DebugLog
import com.pocketds.hub.model.PlaybackPrepareResponse
import com.pocketds.hub.model.PlaybackSelectBody
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.offline.OfflineRepository
import com.pocketds.hub.settings.HubSettings
import com.pocketds.hub.settings.PlaybackSettings
import com.pocketds.hub.settings.SubtitleSettings
import com.pocketds.hub.ui.TrackPresentation
import com.pocketds.hub.ui.ChoiceOverlay
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Theme
import com.pocketds.hub.ui.ThemeGradientDrawable
import com.pocketds.hub.ui.activateOnTap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.pocketds.hub.state.Fmt

/** Full-screen controller-first Media3 playback for a movie or episode. */
@UnstableApi
class PlayerScreen(
    private val api: HubApi,
    private val itemId: String,
    private val startMode: String,
    initialPlan: PlaybackPrepareResponse? = null,
    private val ringVisible: () -> Boolean
) : Screen {
    override val contentDomain = com.pocketds.hub.state.ContentMode.MEDIA
    override val title = initialPlan?.item?.title ?: "Player"
    override val immersive = true
    override val focusOnShow = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var host: ScreenHost
    private lateinit var colors: PocketColors
    private lateinit var root: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var videoDimmer: View
    private lateinit var dynamicSubtitleView: SubtitleView
    /** The bottom padding last given to both subtitle views. */
    private var subtitleFraction = -1f
    private lateinit var gestureView: PlayerGestureView
    /** The controls; the fields below are views it owns. */
    private lateinit var chrome: PlayerChrome
    private val topPanel: LinearLayout get() = chrome.top
    private val controllerPanel: LinearLayout get() = chrome.controller
    private val centerPanel: LinearLayout get() = chrome.center
    /** Everything that shows and hides together: the top bar, the middle row, the timeline. */
    private val controlPanels: List<LinearLayout> get() = listOf(topPanel, centerPanel, controllerPanel)
    private val titleView: TextView get() = chrome.titleView
    private lateinit var status: TextView
    private val position: TextView get() = chrome.position
    private val duration: TextView get() = chrome.duration
    private val seekBar: ChapterSeekBar get() = chrome.seekBar
    private val playButton: PlayerIconButton get() = chrome.playButton
    private val tracksButton: TextView get() = chrome.tracksButton
    private val chaptersButton: TextView get() = chrome.chaptersButton
    private val menuState = PlayerMenuState()
    private val optionsButton: TextView get() = chrome.optionsButton
    private val castButton: MediaRouteButton get() = chrome.castButton
    private val lockButton: PlayerIconButton get() = chrome.lockButton
    private val pipButton: PlayerIconButton get() = chrome.pipButton
    private val closeButton: PlayerIconButton get() = chrome.closeButton
    private val previousButton: PlayerIconButton get() = chrome.previousButton
    private val rewindButton: TextView get() = chrome.rewindButton
    private val forwardButton: TextView get() = chrome.forwardButton
    /** "Skip intro": shown over the video, with or without the rest of the controls. */
    private lateinit var skipPill: TextView
    private val nextButton: PlayerIconButton get() = chrome.nextButton
    private lateinit var choiceOverlay: ChoiceOverlay
    private lateinit var subtitleOffsetOverlay: SubtitleOffsetOverlay
    private lateinit var upNext: UpNextCardView
    private val seekPreview: LinearLayout get() = chrome.seekPreview
    private val seekPreviewImage: ImageView get() = chrome.seekPreviewImage
    private val seekPreviewFrame: FrameLayout get() = chrome.seekPreviewFrame
    private val seekPreviewTime: TextView get() = chrome.seekPreviewTime
    private val seekPreviewDelta: TextView get() = chrome.seekPreviewDelta
    private val gestureFeedback: TextView get() = chrome.gestureFeedback
    private lateinit var levelFeedback: PlayerLevelView

    private var plan: PlaybackPrepareResponse? = initialPlan
    private var prepareJob: Job? = null
    private var selectionJob: Job? = null
    private var subtitleJob: Job? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var serviceLoaded = false
    private var controlsVisible = true
    private var seekingByTouch = false
    private var suppressPlaybackChrome = false
    private var scrubStartMillis = 0L
    private var scrubTargetMillis = 0L
    private var scrubWasPlaying = false
    private var timelineWasPlaying = false
    private var brightnessStart = 1f
    private var videoBrightness = 1f
    private var volumeStart = 0
    private var lastGestureVolume: Int? = null
    private var subtitleOffsetMillis = 0L
    private var subtitleOffsetPreferenceScope = ""
    private var subtitleOffsetDirty = false
    private var subtitleSourceKey = ""
    private var dynamicSubtitleTimeline: SubtitleTimeline<Cue>? = null
    private var lastDynamicCues: List<Cue> = emptyList()
    private var pendingFallbackSubtitleOffset = false
    private var previewRequest: Disposable? = null
    private var pendingPreviewPosition = 0L
    private var lastPreviewThumbnail = -1
    /** Where the up-next card appears in this video, or null. */
    private var upNextAt: Long? = null
    /** Watch credits: the card stays away until the video ends. */
    private var upNextDismissed = false
    /** Segments already skipped by themselves, so seeking back into one does not skip it again. */
    private val autoSkipped = mutableSetOf<String>()
    private var selectedQuality = 0
    private var padTimelineSeeking = false
    private var castTransferJob: Job? = null
    private var castContext: CastContext? = null
    private val castStateListener = CastStateListener { state ->
        if (state == CastState.CONNECTED && !CastPlaybackCoordinator.isActive) startCastTransfer()
        if (state == CastState.NOT_CONNECTED && CastPlaybackCoordinator.isActive) {
            CastPlaybackCoordinator.stop()
            status.visibility = View.VISIBLE
            status.text = "TV disconnected · press Select to resume here"
            pipButton.visibility = View.VISIBLE
            plan = null
            serviceLoaded = false
        }
    }
    private var playbackSpeed = PlaybackEnhancements.defaultSpeed
    private var playbackAspect = PlaybackEnhancements.defaultAspect
    private var subtitleLook = SubtitleLook()
    private var touchLocked = false
    private var activeSegmentId = ""

    private val uiTick = object : Runnable {
        override fun run() {
            syncServicePlan()
            updateTimeline()
            handler.postDelayed(this, 500L)
        }
    }
    private val subtitleTick = object : Runnable {
        override fun run() {
            renderDynamicSubtitle()
            handler.postDelayed(this, SUBTITLE_TICK_MILLIS)
        }
    }
    private val hideControls = Runnable { setControls(false) }
    private val hideSeekPreview = Runnable {
        seekPreview.visibility = View.GONE
        padTimelineSeeking = false
    }
    private val hideGestureFeedback = Runnable { gestureFeedback.visibility = View.GONE }
    private val hideLevelFeedback = Runnable { levelFeedback.visibility = View.GONE }
    private val loadPreviewImage = Runnable { loadTrickplayImage(pendingPreviewPosition) }

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        // Over video, whatever the app theme: a white panel on a dark film glared.
        colors = Theme.onVideo(host.viewContext)
        subtitleLook = SubtitleSettings.look(host.viewContext)
        root = FrameLayout(host.viewContext).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        playerView = PlayerView(host.viewContext).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.BLACK)
        }
        root.addView(playerView, FrameLayout.LayoutParams(MATCH, MATCH))

        // External SRT/WebVTT tracks are parsed once and drawn here. Their
        // clock can move while the video keeps playing, which makes live timing
        // adjustment possible without rebuilding ExoPlayer's media item.
        dynamicSubtitleView = SubtitleView(host.viewContext).apply {
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(dynamicSubtitleView, FrameLayout.LayoutParams(MATCH, MATCH))
        videoDimmer = View(host.viewContext).apply {
            setBackgroundColor(Color.BLACK)
            alpha = 0f
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(videoDimmer, FrameLayout.LayoutParams(MATCH, MATCH))

        status = TextView(host.viewContext).apply {
            text = if (plan == null) "Negotiating playback with Jellyfin…" else "Opening player…"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(80, 0, 0, 0))
        }
        root.addView(status, FrameLayout.LayoutParams(MATCH, MATCH))
        // PlayerView's SurfaceView can own input once frames start. This sibling
        // stays above video and below the visible controls, so buttons keep taps.
        gestureView = PlayerGestureView(host.viewContext, object : PlayerGestureView.Listener {
                override fun onSingleTap() {
                    if (touchLocked) {
                        showGestureFeedback("Touch controls locked", PlayerGestureView.Side.CENTER)
                        return
                    }
                    if (controlsVisible) setControls(false) else showControls()
                }

                override fun onDoubleTap(side: PlayerGestureView.Side) {
                    if (!touchLocked) handleDoubleTap(side)
                }
                override fun onHorizontalStart() { if (!touchLocked) beginHorizontalScrub() }
                override fun onHorizontalMove(fraction: Float) { if (!touchLocked) updateHorizontalScrub(fraction) }
                override fun onHorizontalEnd(cancelled: Boolean) { if (!touchLocked) finishHorizontalScrub(cancelled) }
                override fun onVerticalStart(side: PlayerGestureView.Side) { if (!touchLocked) beginVerticalGesture(side) }
                override fun onVerticalMove(side: PlayerGestureView.Side, fraction: Float) =
                    if (!touchLocked) updateVerticalGesture(side, fraction) else Unit
                override fun onVerticalEnd(side: PlayerGestureView.Side, cancelled: Boolean) =
                    if (!touchLocked) finishVerticalGesture(side, cancelled) else Unit
            })
        root.addView(gestureView, FrameLayout.LayoutParams(MATCH, MATCH))
        chrome = PlayerChrome(host.viewContext, colors, ChromeActions(), configuredSeekSeconds(), TimelineListener())
        plan?.let { showTitle(it.item, it.offline) }
        root.addView(topPanel, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))
        root.addView(centerPanel, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        root.addView(controllerPanel, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        // The panel's height is known only after it lays out, and changes when
        // the skip or next buttons appear.
        controllerPanel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeSubtitles() }

        root.addView(
            seekPreview,
            FrameLayout.LayoutParams(dp(190), WRAP, Gravity.BOTTOM or Gravity.START).apply {
                bottomMargin = dp(122)
            }
        )
        root.addView(
            gestureFeedback,
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER)
        )
        levelFeedback = PlayerLevelView(host.viewContext)
        root.addView(
            levelFeedback,
            FrameLayout.LayoutParams(dp(88), dp(228), Gravity.CENTER_VERTICAL or Gravity.START).apply {
                leftMargin = dp(30)
            }
        )

        choiceOverlay = ChoiceOverlay(host.viewContext, colors, ringVisible, sidePanel = true)
        root.addView(choiceOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
        subtitleOffsetOverlay = SubtitleOffsetOverlay(host.viewContext, colors, ringVisible)
        root.addView(subtitleOffsetOverlay, FrameLayout.LayoutParams(dp(320), WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(16) })
        skipPill = TextView(host.viewContext).apply {
            textSize = 13f
            com.pocketds.hub.ui.Type.text(context, 700).let { typeface = it }
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(18), 0, dp(18), 0)
            minimumHeight = dp(40)
            visibility = View.GONE
            background = android.graphics.drawable.StateListDrawable().apply {
                fun face(focused: Boolean) = com.pocketds.hub.ui.ThemeGradientDrawable().apply {
                    cornerRadius = dp(999).toFloat()
                    setColor(if (focused) Color.WHITE else Color.argb(110, 0, 0, 0))
                    setStroke(dp(2), Color.WHITE)
                }
                addState(intArrayOf(android.R.attr.state_focused), face(true))
                addState(intArrayOf(), face(false))
            }
            setOnFocusChangeListener { view, focused ->
                (view as TextView).setTextColor(if (focused) Color.BLACK else Color.WHITE)
                if (focused && controlsVisible) scheduleHide()
            }
            com.pocketds.hub.ui.Styler.makeFocusable(this)
            activateOnTap { skipSegment() }
        }
        root.addView(skipPill, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(24); bottomMargin = dp(SKIP_BOTTOM_HIDDEN_DP)
        })
        upNext = UpNextCardView(host.viewContext, colors, api, ringVisible).apply {
            visibility = View.GONE
            onPlayNow = { playNext() }
            onFilled = { playNext() }
            onWatchCredits = { dismissUpNext() }
        }
        root.addView(upNext, FrameLayout.LayoutParams(dp(340), WRAP, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(dp(20), dp(20), dp(24), dp(UP_NEXT_BOTTOM_DP))
        })
        return root
    }

    override fun onShow() {
        castContext = runCatching { CastContext.getSharedInstance(host.viewContext) }.getOrNull()
        castContext?.addCastStateListener(castStateListener)
        handler.removeCallbacks(uiTick)
        handler.post(uiTick)
        handler.removeCallbacks(subtitleTick)
        handler.post(subtitleTick)
        val remote = CastPlaybackCoordinator.activePlan?.takeIf { it.item.id == itemId }
        if (remote != null) {
            plan = remote
            showRemotePlayback()
        } else if (plan == null) prepare() else beginPlayback()
        root.post { playButton.requestFocus() }
    }

    override fun onHide() {
        castContext?.removeCastStateListener(castStateListener)
        castContext = null
        persistSubtitleOffset()
        rememberPlaybackPosition()
        handler.removeCallbacks(uiTick)
        handler.removeCallbacks(subtitleTick)
        handler.removeCallbacks(hideControls)
        upNext.stop()
        handler.removeCallbacks(hideSeekPreview)
        handler.removeCallbacks(hideGestureFeedback)
        handler.removeCallbacks(hideLevelFeedback)
        handler.removeCallbacks(loadPreviewImage)
        previewRequest?.dispose()
        previewRequest = null
        releaseController()
        prepareJob?.cancel()
        selectionJob?.cancel()
        castTransferJob?.cancel()
        subtitleJob?.cancel()
        prepareJob = null
        selectionJob = null
        subtitleJob = null
    }

    override fun onDestroyView() {
        if (serviceLoaded) PlaybackService.stop(host.viewContext)
        scope.cancel()
    }

    override fun onAppBackgrounded() {
        if (!CastPlaybackCoordinator.isActive) PlaybackService.pause(host.viewContext)
    }

    /** Android system Back includes the AYANEO edge-swipe gesture. */
    override fun onSystemBack(): Boolean {
        if (subtitleOffsetOverlay.onPad(PadAction.Back)) return true
        if (choiceOverlay.onPad(PadAction.Back)) return true
        if (upNext.visibility == View.VISIBLE) { dismissUpNext(); return true }
        if (controlsVisible) { setControls(false); return true }
        host.back()
        return true
    }

    override fun onPictureInPictureModeChanged(active: Boolean) {
        if (active) {
            handler.removeCallbacks(hideControls)
            choiceOverlay.dismiss()
            controlPanels.forEach { it.visibility = View.GONE }
            upNext.visibility = View.GONE
            skipPill.visibility = View.GONE
            seekPreview.visibility = View.GONE
            gestureFeedback.visibility = View.GONE
            levelFeedback.visibility = View.GONE
            controlsVisible = false
        } else {
            showControls()
        }
    }

    override fun requestInitialFocus(): Boolean = playButton.requestFocus()
    override fun hints() = emptyList<com.pocketds.hub.nav.ButtonHint>()

    override fun onPad(action: PadAction): Boolean {
        if (subtitleOffsetOverlay.onPad(action)) return true
        if (choiceOverlay.onPad(action)) return true
        return when (action) {
            PadAction.Activate -> {
                val focused = root.findFocus()
                if (focused != null && (upNext.hasFocus() || focused === skipPill)) focused.performClick()
                // Any control on the overlay does its own thing; on the timeline, or with nothing
                // focused, A plays and pauses. The pills and -10/+10 are text, not icon buttons.
                else if (controlsVisible && focused != null && focused !== seekBar && inControls(focused)) focused.performClick()
                else togglePlay()
                true
            }
            is PadAction.Step -> {
                if (subtitleOffsetOverlay.isOpen && action.direction == Direction.UP && topPanel.hasFocus())
                    subtitleOffsetOverlay.focusTiming()
                else moveControllerFocus(action.direction)
                true
            }
            is PadAction.Page -> {
                val seek = configuredSeekMillis()
                seekBy(if (action.direction == Direction.UP) -seek else seek)
                true
            }
            PadAction.Primary -> { showTrackSheet(); true }
            PadAction.Menu -> { showPlaybackSheet(); true }
            PadAction.Refresh -> { if (plan == null) prepare(); true }
            PadAction.Back -> {
                when {
                    plan == null && prepareJob?.isActive != true -> host.back()
                    upNext.visibility == View.VISIBLE -> dismissUpNext()
                    controlsVisible -> setControls(false)
                    else -> host.back()
                }
                true
            }
            else -> true
        }
    }

    private fun prepare() {
        if (prepareJob?.isActive == true) return
        status.visibility = View.VISIBLE
        status.text = "Negotiating playback with Jellyfin…"
        prepareJob = scope.launch {
            val userId = HubSettings.userId(host.viewContext)
            val localPosition = PlaybackProgressStore.resumePosition(
                host.viewContext,
                userId,
                itemId,
                startMode
            )
            when (val result = api.preparePlayback(
                itemId,
                PlaybackCapabilitiesProbe.prepare(host.viewContext, startMode, localPosition)
            )) {
                is HubResult.Ok -> {
                    plan = applyRememberedSelection(result.value)
                    beginPlayback()
                }
                is HubResult.Failed -> {
                    status.text = result.message + "\nPress Select to retry or B to return"
                    status.setTextColor(colors.dangerText)
                }
            }
            prepareJob = null
        }
    }

    private suspend fun applyRememberedSelection(initial: PlaybackPrepareResponse): PlaybackPrepareResponse {
        val user = HubSettings.userId(host.viewContext)
        val scopeId = initial.item.seriesId.ifEmpty { initial.item.id }
        val remembered = PlaybackPreferences.get(host.viewContext, user, scopeId)
        val audio = PlaybackPreferences.preferredAudio(initial, remembered)
        val subtitle = PlaybackPreferences.preferredSubtitle(initial, remembered)
        val desiredSubtitle = when (remembered.subtitlesEnabled) {
            false -> -1
            true -> subtitle?.index
            null -> initial.selectedSubtitleIndex
        }
        val desiredAudio = audio?.index ?: initial.selectedAudioIndex
        if (desiredAudio == initial.selectedAudioIndex && desiredSubtitle == initial.selectedSubtitleIndex) return initial
        if (initial.offline) return initial.copy(
            selectedAudioIndex = desiredAudio,
            selectedSubtitleIndex = desiredSubtitle
        )
        return when (val selected = api.selectPlayback(
            initial.sessionId,
            PlaybackSelectBody(initial.positionMillis, audioStreamIndex = desiredAudio, subtitleStreamIndex = desiredSubtitle)
        )) {
            is HubResult.Ok -> selected.value
            is HubResult.Failed -> initial
        }
    }

    private fun beginPlayback() {
        val current = plan ?: return
        pipButton.visibility = View.VISIBLE
        restoreSubtitleOffset(current)
        prepareDynamicSubtitle(current)
        updateControlLabels(current)
        duration.text = PlayerLabels.remainingLine(controller?.currentPosition ?: 0L, current.durationMillis)
        status.setTextColor(Color.WHITE)
        status.text = "Opening ${current.playMethod.lowercase().ifEmpty { "media" }}…"
        if (!serviceLoaded) {
            serviceLoaded = true
            PlaybackService.load(host.viewContext, current, subtitleOffsetMillis = subtitleOffsetMillis)
        }
        connectController()
        if (castContext?.castState == CastState.CONNECTED && !CastPlaybackCoordinator.isActive) {
            startCastTransfer()
        }
    }

    private fun startCastTransfer() {
        if (castTransferJob?.isActive == true || CastPlaybackCoordinator.isActive) return
        val current = plan ?: return
        status.visibility = View.VISIBLE
        status.setTextColor(Color.WHITE)
        status.text = "Preparing TV playback…"
        val position = controller?.currentPosition?.coerceAtLeast(0) ?: current.positionMillis
        castTransferJob = scope.launch {
            val error = CastPlaybackCoordinator.transfer(host.viewContext, api, current, position)
            if (error != null) {
                status.visibility = View.GONE
                host.notify(error)
            } else {
                if (serviceLoaded) PlaybackService.stop(host.viewContext)
                serviceLoaded = false
                releaseController()
                plan = CastPlaybackCoordinator.activePlan
                showRemotePlayback()
                host.notify("Playing on ${CastPlaybackCoordinator.deviceName}")
            }
            castTransferJob = null
        }
    }

    private fun showRemotePlayback() {
        plan?.let { showTitle(it.item, it.offline) }
        status.visibility = View.VISIBLE
        status.setTextColor(Color.WHITE)
        status.text = "Playing on ${CastPlaybackCoordinator.deviceName}"
        pipButton.visibility = View.GONE
        previousButton.isEnabled = plan?.previousItem != null
        nextButton.isEnabled = plan?.nextItem != null
        updateTimeline()
        showControls()
    }

    private fun showCastTracks(tab: String) {
        val current = CastPlaybackCoordinator.activePlan ?: return
        menuState.selectTrackTab(tab)
        choiceOverlay.resetBody()
        choiceOverlay.open("TV audio & subtitles", onDismiss = ::showControls)
        choiceOverlay.tabs(listOf("audio" to "Audio", "subtitles" to "Subtitles"), tab, ::showCastTracks)
        var selected: View? = null
        if (tab == "audio") {
            val track = current.audioTracks.firstOrNull { it.index == current.selectedAudioIndex }
            selected = choiceOverlay.choice(
                track?.label ?: "Jellyfin default",
                "Audio is chosen before the TV stream starts",
                selected = true
            ) { choiceOverlay.dismiss() }
        } else {
            selected = choiceOverlay.choice("Off", selected = current.selectedSubtitleIndex == null) {
                CastPlaybackCoordinator.selectSubtitle(null)
                choiceOverlay.dismiss()
            }
            current.subtitleTracks.filter { it.external &&
                (it.codec.equals("srt", true) || it.codec.equals("subrip", true) ||
                    it.codec.equals("vtt", true) || it.codec.equals("webvtt", true))
            }.forEach { track ->
                val row = choiceOverlay.choice(track.label, "WebVTT on TV", selected = track.index == current.selectedSubtitleIndex) {
                    CastPlaybackCoordinator.selectSubtitle(track.index)
                    choiceOverlay.dismiss()
                }
                if (track.index == current.selectedSubtitleIndex) selected = row
            }
        }
        choiceOverlay.focusBody(selected)
    }

    private fun connectController() {
        if (controller != null || controllerFuture != null) return
        val future = MediaController.Builder(host.viewContext, PlaybackService.sessionToken(host.viewContext))
            .buildAsync()
        controllerFuture = future
        future.addListener({
            if (controllerFuture !== future || future.isCancelled) return@addListener
            runCatching { future.get() }
                .onSuccess {
                    controller = it
                    it.addListener(playerListener)
                    playerView.player = it
                    status.visibility = if (it.playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                    updateTimeline()
                    scheduleHide()
                }
                .onFailure {
                    status.visibility = View.VISIBLE
                    status.text = "Could not connect to the player\n${it.message.orEmpty()}"
                }
        }, ContextCompat.getMainExecutor(host.viewContext))
    }

    private fun releaseController() {
        playerView.player = null
        controller?.removeListener(playerListener)
        controller?.release()
        controller = null
        controllerFuture?.takeUnless { it.isDone }?.cancel(true)
        controllerFuture = null
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // The bar fills only while the video plays: no next episode behind a pause.
            if (isPlaying) upNext.resume() else upNext.pause()
            playButton.setIcon(if (isPlaying) PlayerControlIcon.PAUSE else PlayerControlIcon.PLAY)
            playButton.contentDescription = if (isPlaying) "Pause" else "Play"
            if (!suppressPlaybackChrome) showControls()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            status.visibility = when (playbackState) {
                Player.STATE_BUFFERING -> View.VISIBLE
                else -> View.GONE
            }
            if (playbackState == Player.STATE_BUFFERING) status.text = "Buffering…"
            if (playbackState == Player.STATE_ENDED) showNextCountdown()
            updateTimeline()
        }

        override fun onPlayerError(error: PlaybackException) {
            val current = plan
            if (current != null && current.playMethod != "Transcode") {
                status.visibility = View.VISIBLE
                status.setTextColor(Color.WHITE)
                status.text = "Trying a compatible Jellyfin transcode…"
                return
            }
            status.visibility = View.VISIBLE
            status.setTextColor(colors.dangerText)
            status.text = "Playback stopped\n${error.message ?: error.errorCodeName}\nPress B to return"
            setControls(true)
        }
    }

    private fun togglePlay() {
        if (CastPlaybackCoordinator.isActive) {
            CastPlaybackCoordinator.togglePlay()
            showControls()
            return
        }
        val value = controller ?: return
        if (value.isPlaying) value.pause() else value.play()
        showControls()
    }

    private fun moveControllerFocus(direction: Direction) {
        val focused = root.findFocus()
        if (upNext.visibility == View.VISIBLE && (upNext.hasFocus() || !controlsVisible)) {
            // The card's two buttons, side by side.
            when {
                !upNext.hasFocus() -> upNext.playNow.requestFocus()
                direction == Direction.LEFT -> upNext.playNow.requestFocus()
                direction == Direction.RIGHT -> upNext.watchCredits.requestFocus()
                direction == Direction.UP && controlsVisible -> playButton.requestFocus()
            }
            return
        }
        if (focused === skipPill && direction == Direction.LEFT && controlsVisible) {
            playButton.requestFocus()
            scheduleHide()
            return
        }
        if (!controlsVisible) {
            showControls()
            playButton.requestFocus()
            return
        }
        // Top to bottom: the top bar, the middle row (play and its neighbours), the timeline.
        val top = listOf<View>(closeButton, tracksButton, chaptersButton, optionsButton, castButton, lockButton, pipButton)
            .filter { it.visibility == View.VISIBLE && it.isEnabled }
        val playback = listOf<View>(previousButton, rewindButton, playButton, forwardButton, nextButton, skipPill)
            .filter { it.visibility == View.VISIBLE && it.isEnabled }
        when {
            focused === seekBar -> when (direction) {
                Direction.LEFT, Direction.RIGHT -> seekTimeline(direction)
                Direction.UP -> playButton.requestFocus()
                Direction.DOWN -> Unit
            }
            focused in top -> when (direction) {
                Direction.LEFT, Direction.RIGHT -> moveWithin(top, focused, direction)
                Direction.DOWN -> playButton.requestFocus()
                Direction.UP -> Unit
            }
            focused in playback -> when (direction) {
                Direction.LEFT, Direction.RIGHT -> moveWithin(playback, focused, direction)
                Direction.UP -> (top.firstOrNull { it === tracksButton } ?: top.firstOrNull())?.requestFocus()
                Direction.DOWN -> seekBar.requestFocus()
            }
            else -> playButton.requestFocus()
        }
        scheduleHide()
    }

    private fun moveWithin(buttons: List<View>, focused: View?, direction: Direction) {
        if (buttons.isEmpty()) return
        val current = buttons.indexOf(focused).coerceAtLeast(0)
        val delta = if (direction == Direction.LEFT) -1 else 1
        buttons.getOrNull((current + delta).coerceIn(0, buttons.lastIndex))?.requestFocus()
    }

    private fun seekTimeline(direction: Direction) {
        if (CastPlaybackCoordinator.isActive) {
            val end = plan?.durationMillis ?: return
            val base = if (padTimelineSeeking) pendingPreviewPosition else CastPlaybackCoordinator.positionMillis
            val target = PlaybackRules.clampSeek(base + if (direction == Direction.LEFT) -configuredSeekMillis() else configuredSeekMillis(), end)
            padTimelineSeeking = true
            CastPlaybackCoordinator.seekTo(target)
            setSeekBarTarget(target, end)
            showSeekPreview(target, showDelta = false)
            handler.removeCallbacks(hideSeekPreview)
            handler.postDelayed(hideSeekPreview, 900L)
            return
        }
        val value = controller ?: return
        val end = value.duration.takeIf { it > 0 } ?: plan?.durationMillis ?: return
        val base = if (padTimelineSeeking) pendingPreviewPosition else value.currentPosition.coerceAtLeast(0)
        val delta = if (direction == Direction.LEFT) -configuredSeekMillis() else configuredSeekMillis()
        val target = PlaybackRules.clampSeek(base + delta, end)
        padTimelineSeeking = true
        value.seekTo(target)
        setSeekBarTarget(target, end)
        showSeekPreview(target, showDelta = false)
        handler.removeCallbacks(hideSeekPreview)
        handler.postDelayed(hideSeekPreview, 900L)
    }

    private fun seekBy(delta: Long, showChrome: Boolean = true) {
        if (CastPlaybackCoordinator.isActive) {
            val end = plan?.durationMillis ?: Long.MAX_VALUE
            CastPlaybackCoordinator.seekTo(PlaybackRules.clampSeek(CastPlaybackCoordinator.positionMillis + delta, end))
            if (showChrome) showControls()
            return
        }
        val value = controller ?: return
        val end = value.duration.takeIf { it > 0 } ?: plan?.durationMillis ?: Long.MAX_VALUE
        value.seekTo(PlaybackRules.clampSeek(value.currentPosition + delta, end))
        if (showChrome) showControls()
    }

    private fun handleDoubleTap(side: PlayerGestureView.Side) {
        if (side == PlayerGestureView.Side.CENTER) {
            togglePlay()
            return
        }
        val value = controller ?: return
        val delta = configuredSeekMillis() * if (side == PlayerGestureView.Side.LEFT) -1 else 1
        val end = value.duration.takeIf { it > 0 } ?: plan?.durationMillis ?: return
        val target = PlaybackRules.clampSeek(value.currentPosition + delta, end)
        seekBy(delta, showChrome = false)
        showGestureFeedback("${PlayerLabels.signedTime(delta)}  ·  ${Fmt.clock(target)}", side)
    }

    private fun beginHorizontalScrub() {
        val value = controller ?: return
        scrubStartMillis = value.currentPosition.coerceAtLeast(0)
        scrubTargetMillis = scrubStartMillis
        scrubWasPlaying = value.isPlaying
        suppressPlaybackChrome = true
        seekingByTouch = true
        value.pause()
        setControls(true)
        setSeekBarTarget(scrubTargetMillis, value.duration.takeIf { it > 0 } ?: plan?.durationMillis ?: 0)
        showSeekPreview(scrubTargetMillis, showDelta = true)
    }

    private fun updateHorizontalScrub(fraction: Float) {
        val value = controller ?: return
        val end = value.duration.takeIf { it > 0 } ?: plan?.durationMillis ?: return
        scrubTargetMillis = PlaybackRules.scrubTarget(scrubStartMillis, fraction, end)
        setSeekBarTarget(scrubTargetMillis, end)
        showSeekPreview(scrubTargetMillis, showDelta = true)
    }

    private fun finishHorizontalScrub(cancelled: Boolean) {
        val value = controller
        if (!cancelled) value?.seekTo(scrubTargetMillis)
        seekingByTouch = false
        if (scrubWasPlaying) value?.play()
        suppressPlaybackChrome = false
        handler.removeCallbacks(hideSeekPreview)
        handler.postDelayed(hideSeekPreview, 450L)
        showControls()
    }

    private fun beginVerticalGesture(side: PlayerGestureView.Side) {
        handler.removeCallbacks(hideControls)
        if (side == PlayerGestureView.Side.LEFT) {
            brightnessStart = videoBrightness
        } else {
            volumeStart = audioManager().getStreamVolume(AudioManager.STREAM_MUSIC)
            lastGestureVolume = volumeStart
        }
    }

    private fun updateVerticalGesture(side: PlayerGestureView.Side, fraction: Float) {
        if (side == PlayerGestureView.Side.LEFT) {
            val next = (brightnessStart + fraction).coerceIn(0.02f, 1f)
            videoBrightness = next
            videoDimmer.alpha = PlayerBrightnessPolicy.overlayAlpha(next)
            showLevelFeedback(PlayerLevelView.Kind.BRIGHTNESS, next, side)
        } else {
            val manager = audioManager()
            val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val next = (volumeStart + fraction * max).roundToInt().coerceIn(0, max)
            // The Pocket DS exposes fifteen media-volume steps. Motion events
            // arrive far more frequently, so avoid repeatedly writing the same
            // hardware level while a finger is between two real steps.
            if (lastGestureVolume != next) {
                manager.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
                lastGestureVolume = next
            }
            showLevelFeedback(PlayerLevelView.Kind.VOLUME, next.toFloat() / max, side)
        }
    }

    private fun finishVerticalGesture(
        side: PlayerGestureView.Side,
        @Suppress("UNUSED_PARAMETER") cancelled: Boolean
    ) {
        if (side == PlayerGestureView.Side.RIGHT) lastGestureVolume = null
        handler.removeCallbacks(hideLevelFeedback)
        handler.postDelayed(hideLevelFeedback, 700L)
        scheduleHide()
    }

    private fun audioManager(): AudioManager =
        requireNotNull(host.viewContext.getSystemService(AudioManager::class.java))

    private fun showGestureFeedback(text: String, side: PlayerGestureView.Side) {
        handler.removeCallbacks(hideGestureFeedback)
        gestureFeedback.text = text
        gestureFeedback.visibility = View.VISIBLE
        (gestureFeedback.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
            params.gravity = Gravity.CENTER_VERTICAL or when (side) {
                PlayerGestureView.Side.LEFT -> Gravity.START
                PlayerGestureView.Side.RIGHT -> Gravity.END
                PlayerGestureView.Side.CENTER -> Gravity.CENTER_HORIZONTAL
            }
            params.leftMargin = dp(34)
            params.rightMargin = dp(34)
            gestureFeedback.layoutParams = params
        }
        handler.postDelayed(hideGestureFeedback, 850L)
    }

    private fun showLevelFeedback(kind: PlayerLevelView.Kind, value: Float, side: PlayerGestureView.Side) {
        handler.removeCallbacks(hideLevelFeedback)
        levelFeedback.show(kind, value)
        (levelFeedback.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
            params.gravity = Gravity.CENTER_VERTICAL or if (side == PlayerGestureView.Side.LEFT) {
                Gravity.START
            } else {
                Gravity.END
            }
            params.leftMargin = dp(30)
            params.rightMargin = dp(30)
            levelFeedback.layoutParams = params
        }
    }

    private fun showSeekPreview(targetMillis: Long, showDelta: Boolean) {
        handler.removeCallbacks(hideSeekPreview)
        seekPreview.visibility = View.VISIBLE
        seekPreviewTime.text = Fmt.clock(targetMillis)
        seekPreviewDelta.visibility = if (showDelta) View.VISIBLE else View.GONE
        if (showDelta) seekPreviewDelta.text = PlayerLabels.signedTime(targetMillis - scrubStartMillis)
        pendingPreviewPosition = targetMillis
        updateSeekPreviewAnchor(targetMillis)
        handler.removeCallbacks(loadPreviewImage)
        handler.postDelayed(loadPreviewImage, if (plan?.trickplay == null) 180L else 45L)
    }

    private fun loadTrickplayImage(positionMillis: Long) {
        val info = plan?.trickplay
        val frame = info?.let { PlaybackRules.trickplayFrame(positionMillis, it) }
        if (info == null || frame == null || info.tileUrl.isEmpty()) {
            loadExtractedPreview(positionMillis)
            return
        }
        if (frame.thumbnailIndex == lastPreviewThumbnail) return
        lastPreviewThumbnail = frame.thumbnailIndex
        previewRequest?.dispose()
        val requestedThumbnail = frame.thumbnailIndex
        val request = ImageRequest.Builder(host.viewContext)
            .data(api.playbackUrl("${info.tileUrl}/${frame.tileIndex}"))
            .allowHardware(false)
            .bitmapConfig(Bitmap.Config.RGB_565)
            // A missing frame keeps the last one: the preview is a picture or just the time.
            .target(
                onSuccess = { drawable ->
                    if (lastPreviewThumbnail != requestedThumbnail) return@target
                    val sheet = drawable.toBitmap()
                    val cellWidth = sheet.width / info.tileWidth
                    val cellHeight = sheet.height / info.tileHeight
                    val left = frame.column * cellWidth
                    val top = frame.row * cellHeight
                    if (cellWidth > 0 && cellHeight > 0 &&
                        left + cellWidth <= sheet.width && top + cellHeight <= sheet.height
                    ) {
                        showPreviewFrame(BitmapDrawable(host.viewContext.resources,
                            Bitmap.createBitmap(sheet, left, top, cellWidth, cellHeight)))
                    }
                }
            )
            .build()
        previewRequest = imageLoader().enqueue(request)
    }

    private fun loadExtractedPreview(positionMillis: Long) {
        val previewUrl = plan?.previewUrl.orEmpty()
        if (previewUrl.isEmpty()) return
        val bucket = (positionMillis.coerceAtLeast(0) / 5_000L) * 5_000L
        val previewKey = -(bucket / 5_000L).toInt() - 2
        if (previewKey == lastPreviewThumbnail) return
        lastPreviewThumbnail = previewKey
        previewRequest?.dispose()
        val request = ImageRequest.Builder(host.viewContext)
            .data(api.playbackUrl(previewUrl) + "?positionMillis=$bucket")
            .allowHardware(false)
            .bitmapConfig(Bitmap.Config.RGB_565)
            .target(
                onSuccess = { drawable ->
                    if (lastPreviewThumbnail == previewKey) showPreviewFrame(drawable)
                }
            )
            .build()
        previewRequest = imageLoader().enqueue(request)
    }

    /**
     * Frames arrive about a second apart when the hub extracts them, and each
     * request used to blank the preview to a grey box, or "Preview unavailable"
     * when one failed, so a drag flickered. The last good frame stays until the
     * next one arrives; before the first, the preview is just the time.
     */
    private fun showPreviewFrame(drawable: android.graphics.drawable.Drawable) {
        seekPreviewImage.setImageDrawable(drawable)
        seekPreviewFrame.visibility = View.VISIBLE
    }

    private fun setSeekBarTarget(targetMillis: Long, durationMillis: Long) {
        if (durationMillis <= 0) return
        seekBar.progress = ((targetMillis.toDouble() / durationMillis) * seekBar.max)
            .roundToInt().coerceIn(0, seekBar.max)
    }

    private fun updateSeekPreviewAnchor(targetMillis: Long) {
        seekBar.post {
            if (!::chrome.isInitialized || seekBar.width <= 0 || root.width <= 0) return@post
            val end = controller?.duration?.takeIf { it > 0 } ?: plan?.durationMillis ?: return@post
            val rootLocation = IntArray(2)
            val barLocation = IntArray(2)
            root.getLocationInWindow(rootLocation)
            seekBar.getLocationInWindow(barLocation)
            val trackLeft = barLocation[0] - rootLocation[0] + seekBar.paddingLeft
            val trackWidth = (seekBar.width - seekBar.paddingLeft - seekBar.paddingRight).coerceAtLeast(1)
            val fraction = (targetMillis.toDouble() / end).coerceIn(0.0, 1.0)
            val center = trackLeft + (trackWidth * fraction).roundToInt()
            val params = seekPreview.layoutParams as FrameLayout.LayoutParams
            params.gravity = Gravity.BOTTOM or Gravity.START
            params.leftMargin = (center - seekPreview.width / 2)
                .coerceIn(dp(8), (root.width - seekPreview.width - dp(8)).coerceAtLeast(dp(8)))
            params.bottomMargin = (root.height - (barLocation[1] - rootLocation[1]) + dp(3)).coerceAtLeast(dp(8))
            seekPreview.layoutParams = params
        }
    }

    private fun imageLoader(): ImageLoader =
        Artwork.loader(api, host.viewContext)

    private fun updateTimeline() {
        if (CastPlaybackCoordinator.isActive) {
            val current = CastPlaybackCoordinator.positionMillis
            val end = CastPlaybackCoordinator.activePlan?.durationMillis ?: 0
            showTimes(current, end)
            playButton.setIcon(if (CastPlaybackCoordinator.isPlaying) PlayerControlIcon.PAUSE else PlayerControlIcon.PLAY)
            playButton.contentDescription = if (CastPlaybackCoordinator.isPlaying) "Pause on TV" else "Play on TV"
            if (!seekingByTouch && !padTimelineSeeking && end > 0) setSeekBarTarget(current, end)
            return
        }
        val value = controller ?: return
        val current = value.currentPosition.coerceAtLeast(0)
        val end = value.duration.takeIf { it > 0 } ?: plan?.durationMillis ?: 0
        showTimes(current, end)
        if (!seekingByTouch && !padTimelineSeeking) {
            seekBar.max = 10_000
            seekBar.progress = if (end > 0) ((current.toDouble() / end) * 10_000).toInt().coerceIn(0, 10_000) else 0
        }
        updateSegmentSkip(current)
        if (value.playbackState != Player.STATE_ENDED) updateUpNext(current, end)
    }

    /**
     * The Skip intro pill while an intro, recap, preview or ad plays; with
     * Settings › Playback › Skip intros automatically, intros and recaps skip
     * themselves once.
     */
    private fun updateSegmentSkip(positionMillis: Long) {
        if (!::chrome.isInitialized) return
        val segment = PlaybackEnhancements.skipPrompt(plan?.segments.orEmpty(), positionMillis)
        val id = segment?.id.orEmpty()
        if (id == activeSegmentId) return
        activeSegmentId = id
        if (segment != null && UpNext.skipsAutomatically(segment.type) && id !in autoSkipped &&
            PlaybackSettings.autoSkipIntro(host.viewContext)) {
            autoSkipped += id
            controller?.seekTo(segment.endMillis)
            showGestureFeedback("Skipped ${segment.type.lowercase()}", PlayerGestureView.Side.CENTER)
            skipPill.visibility = View.GONE
            return
        }
        val label = segment?.let { UpNext.skipLabel(it.type) }
        if (label == null) {
            if (skipPill.hasFocus()) (if (controlsVisible) playButton else root).requestFocus()
            skipPill.visibility = View.GONE
            return
        }
        skipPill.text = label
        skipPill.contentDescription = label
        skipPill.visibility = View.VISIBLE
        // With the controls away, A means Skip while the pill is up.
        if (!controlsVisible && upNext.visibility != View.VISIBLE) skipPill.requestFocus()
    }

    private fun skipSegment() {
        val segment = PlaybackEnhancements.skipPrompt(plan?.segments.orEmpty(), controller?.currentPosition ?: 0L) ?: return
        controller?.seekTo(segment.endMillis)
        if (skipPill.hasFocus()) (if (controlsVisible) playButton else root).requestFocus()
        skipPill.visibility = View.GONE
    }

    /** Shows the up-next card when its moment comes, and puts it away after a seek back. */
    private fun updateUpNext(positionMillis: Long, durationMillis: Long) {
        if (plan?.nextItem == null || upNextDismissed) return
        val shows = UpNext.showsCard(positionMillis, upNextAt, durationMillis)
        if (shows && upNext.visibility != View.VISIBLE) showUpNext()
        else if (!shows && upNext.visibility == View.VISIBLE && positionMillis < (upNextAt ?: 0L)) hideUpNext()
    }

    private fun showUpNext() {
        val next = plan?.nextItem ?: return
        upNext.bind(next)
        upNext.visibility = View.VISIBLE
        upNext.start()
        if (controller?.isPlaying == false) upNext.pause()
        if (!controlsVisible || root.findFocus() == null || root.findFocus() === root) upNext.playNow.requestFocus()
    }

    private fun hideUpNext() {
        upNext.stop()
        if (upNext.hasFocus()) (if (controlsVisible) playButton else root).requestFocus()
        upNext.visibility = View.GONE
    }

    /** Watch credits, or B: away until the video ends, when it comes back to count down. */
    private fun dismissUpNext() {
        upNextDismissed = true
        hideUpNext()
    }

    private fun syncServicePlan() {
        val active = PlaybackService.currentPlan() ?: return
        val current = plan ?: return
        if (active.sessionId != current.sessionId || active == current) return
        plan = active
        prepareDynamicSubtitle(active)
        updateControlLabels(active)
        duration.text = PlayerLabels.remainingLine(controller?.currentPosition ?: 0L, active.durationMillis)
    }

    /**
     * Audio & subtitles: every audio track, then Off and every subtitle track,
     * then subtitle timing and look. One list, where two tabs hid the other
     * half; the cursor starts on whichever kind you changed last.
     */
    private fun showTrackSheet() {
        if (CastPlaybackCoordinator.isActive) {
            showCastTracks(menuState.trackTab)
            return
        }
        if (subtitleOffsetOverlay.isOpen) subtitleOffsetOverlay.onPad(PadAction.Back)
        val current = plan ?: return
        choiceOverlay.resetBody()
        choiceOverlay.open("Audio & subtitles", onDismiss = ::showControls)
        var audioRow: View? = null
        var subtitleRow: View? = null
        choiceOverlay.section("Audio")
        if (current.audioTracks.isEmpty()) choiceOverlay.choice("No selectable audio tracks") { choiceOverlay.cancel() }
        current.audioTracks.forEach { track ->
            val copy = TrackPresentation.of(track)
            val active = track.index == current.selectedAudioIndex
            val row = choiceOverlay.choice(copy.title, copy.detail, selected = active) {
                menuState.selectTrackTab("audio")
                choiceOverlay.dismiss()
                changeSelection(audio = track.index)
            }
            if (active) audioRow = row
        }
        choiceOverlay.section("Subtitles")
        val off = current.selectedSubtitleIndex == null || current.selectedSubtitleIndex == -1
        val offRow = choiceOverlay.choice("Off", selected = off) {
            menuState.selectTrackTab("subtitles")
            choiceOverlay.dismiss()
            changeSelection(subtitle = -1)
        }
        if (off) subtitleRow = offRow
        current.subtitleTracks.forEach { track ->
            val copy = TrackPresentation.of(track)
            val active = track.index == current.selectedSubtitleIndex
            val row = choiceOverlay.choice(copy.title, copy.detail, selected = active) {
                menuState.selectTrackTab("subtitles")
                choiceOverlay.dismiss()
                changeSelection(subtitle = track.index)
            }
            if (active) subtitleRow = row
        }
        choiceOverlay.startGroup()
        if (selectedSubtitleSupportsOffset(current)) {
            choiceOverlay.setting("Subtitle timing", PlayerLabels.subtitleOffset(subtitleOffsetMillis)) { showSubtitleOffsetSheet() }
        }
        choiceOverlay.setting("Subtitle look", PlayerLabels.subtitleLook(subtitleLook)) { showSubtitleAppearanceSheet() }
        choiceOverlay.note("Timing is kept for this series. The look applies to every video, as in Settings › Subtitles.")
        choiceOverlay.focusBody(if (menuState.trackTab == "audio") audioRow ?: subtitleRow else subtitleRow ?: audioRow)
        handler.removeCallbacks(hideControls)
    }

    private fun showSubtitleOffsetSheet() {
        val current = plan ?: return
        if (!selectedSubtitleSupportsOffset(current)) {
            host.notify("Subtitle timing is available for text subtitles")
            return
        }
        choiceOverlay.dismiss()
        subtitleOffsetOverlay.show(
            subtitleOffsetMillis,
            onChange = { value ->
                subtitleOffsetMillis = value
                subtitleOffsetDirty = true
                if (dynamicSubtitleTimeline != null) {
                    renderDynamicSubtitle()
                } else {
                    // If local parsing fails, commit once when the overlay
                    // closes instead of reloading playback for every slider tick.
                    pendingFallbackSubtitleOffset = true
                }
                plan?.let(::updateControlLabels)
            },
            onClose = {
                persistSubtitleOffset()
                if (dynamicSubtitleTimeline == null && pendingFallbackSubtitleOffset) {
                    PlaybackService.setSubtitleOffset(host.viewContext, subtitleOffsetMillis)
                    pendingFallbackSubtitleOffset = false
                }
                showControls()
                if (controlPanels.none { it.hasFocus() }) playButton.requestFocus()
            }
        )
        scheduleHide()
    }

    private fun restoreSubtitleOffset(value: PlaybackPrepareResponse) {
        val userId = HubSettings.userId(host.viewContext)
        val scope = value.item.seriesId.ifEmpty { value.item.id }
        val preferenceScope = "$userId\u0000$scope"
        if (subtitleOffsetPreferenceScope == preferenceScope) return
        subtitleOffsetMillis = PlaybackPreferences.get(
            host.viewContext,
            userId,
            scope
        ).subtitleOffsetMillis
        subtitleOffsetPreferenceScope = preferenceScope
        subtitleOffsetDirty = false
    }

    private fun persistSubtitleOffset() {
        if (!subtitleOffsetDirty) return
        val current = plan ?: return
        PlaybackPreferences.rememberSubtitleOffset(
            host.viewContext,
            HubSettings.userId(host.viewContext),
            current,
            subtitleOffsetMillis
        )
        subtitleOffsetDirty = false
    }

    private fun rememberPlaybackPosition() {
        val current = plan ?: return
        val position = controller?.currentPosition?.coerceAtLeast(0) ?: return
        PlaybackProgressStore.remember(
            host.viewContext,
            HubSettings.userId(host.viewContext),
            current.item,
            position,
            current.durationMillis
        )
    }

    private fun prepareDynamicSubtitle(value: PlaybackPrepareResponse) {
        val track = value.subtitleTracks.firstOrNull { it.index == value.selectedSubtitleIndex }
            ?.takeIf { it.external && it.externalUrl.isNotEmpty() }
        val key = track?.let { "${value.sessionId}:${it.index}:${it.externalUrl}" }.orEmpty()
        if (key == subtitleSourceKey) return

        subtitleSourceKey = key
        subtitleJob?.cancel()
        subtitleJob = null
        dynamicSubtitleTimeline = null
        lastDynamicCues = emptyList()
        dynamicSubtitleView.setCues(emptyList())
        dynamicSubtitleView.visibility = View.GONE
        playerView.subtitleView?.visibility = View.VISIBLE
        pendingFallbackSubtitleOffset = false
        if (track == null) return

        subtitleJob = scope.launch {
            val bytesResult = if (value.offline) {
                runCatching {
                    val path = android.net.Uri.parse(track.externalUrl).path.orEmpty()
                    kotlinx.coroutines.withContext(Dispatchers.IO) { java.io.File(path).readBytes() }
                }.fold(
                    onSuccess = { HubResult.Ok(it) },
                    onFailure = {
                        HubResult.Failed(
                            com.pocketds.hub.net.FailureKind.UNKNOWN,
                            "Local subtitle is unavailable"
                        )
                    }
                )
            } else api.playbackBytes(track.externalUrl)
            when (val result = bytesResult) {
                is HubResult.Ok -> {
                    val parsed = runCatching {
                        kotlinx.coroutines.withContext(Dispatchers.Default) {
                            parseDynamicSubtitle(result.value, track.codec)
                        }
                    }.getOrElse {
                        DebugLog.log("player", "subtitle parse failed: ${it.message}")
                        SubtitleTimeline(emptyList())
                    }
                    if (subtitleSourceKey != key) return@launch
                    if (!parsed.isEmpty) {
                        dynamicSubtitleTimeline = parsed
                        playerView.subtitleView?.visibility = View.INVISIBLE
                        dynamicSubtitleView.visibility = View.VISIBLE
                        pendingFallbackSubtitleOffset = false
                        renderDynamicSubtitle()
                        DebugLog.log("player", "dynamic subtitle timing ready for ${track.codec}")
                    }
                }
                is HubResult.Failed -> {
                    if (subtitleSourceKey != key) return@launch
                    DebugLog.log("player", "dynamic subtitle unavailable: ${result.message}")
                    if (pendingFallbackSubtitleOffset) {
                        PlaybackService.setSubtitleOffset(host.viewContext, subtitleOffsetMillis)
                        pendingFallbackSubtitleOffset = false
                    }
                }
            }
            subtitleJob = null
        }
    }

    private fun renderDynamicSubtitle() {
        val timeline = dynamicSubtitleTimeline ?: return
        val at = controller?.currentPosition?.coerceAtLeast(0) ?: return
        val cues = timeline.valuesAt(at, subtitleOffsetMillis)
        if (cues != lastDynamicCues) {
            lastDynamicCues = cues
            dynamicSubtitleView.setCues(cues)
        }
    }

    private fun selectedSubtitleSupportsOffset(value: PlaybackPrepareResponse): Boolean =
        value.subtitleTracks.firstOrNull { it.index == value.selectedSubtitleIndex }?.external == true

    private fun showPlaybackSheet() = showPlaybackPanel()

    private fun showCastPanel() {
        choiceOverlay.resetBody()
        choiceOverlay.open("Playing on ${CastPlaybackCoordinator.deviceName}", onDismiss = ::showControls)
        choiceOverlay.choice("Move to Pocket DS", "Continue here at the TV position") {
            choiceOverlay.dismiss()
            moveCastToDevice()
        }
        choiceOverlay.choice("Stop on TV", "End playback on the receiver") {
            choiceOverlay.dismiss()
            CastPlaybackCoordinator.stop()
            host.back()
        }
        choiceOverlay.choice("TV stream", "H.264/AAC · up to 20 Mbps") { choiceOverlay.dismiss() }
        choiceOverlay.focusBody()
    }

    private fun showSubtitleAppearanceSheet() {
        val looks = SubtitleStyle.entries.flatMap { style -> SubtitleSize.entries.map { subtitleLook.copy(style = style, size = it) } }
        choiceOverlay.pickValue(
            "Subtitle look", "For every video. Also in Settings › Subtitles.",
            looks, subtitleLook, PlayerLabels::subtitleLook,
            onCancel = ::showTrackSheet
        ) { picked ->
            subtitleLook = picked
            SubtitleSettings.save(host.viewContext, picked)
            applySubtitleAppearance()
            showControls()
        }
        handler.removeCallbacks(hideControls)
    }

    private fun applySubtitleAppearance() {
        listOfNotNull(playerView.subtitleView, dynamicSubtitleView).forEach { SubtitleLooks.apply(it, subtitleLook) }
        subtitleFraction = -1f
        placeSubtitles()
    }

    /** The look's own height, or just above the timeline while it shows if the look asks for that. */
    private fun placeSubtitles() {
        val covered = if (controlsVisible && controllerPanel.height > 0) root.height - controllerPanel.top else 0
        val fraction = PlaybackEnhancements.subtitlePlacement(subtitleLook, covered, root.height)
        if (fraction == subtitleFraction) return
        subtitleFraction = fraction
        listOfNotNull(playerView.subtitleView, dynamicSubtitleView).forEach { it.setBottomPaddingFraction(fraction) }
    }

    /**
     * This video: what can change for what is playing, each row with its
     * current value and opening its own list. Back from that list returns
     * here. Settings that apply to every video are pointed to, not repeated.
     */
    private fun showPlaybackPanel() {
        if (CastPlaybackCoordinator.isActive) {
            showCastPanel()
            return
        }
        val current = plan ?: return
        choiceOverlay.resetBody()
        choiceOverlay.open("This video", onDismiss = ::showControls)
        choiceOverlay.setting("Quality", PlayerLabels.qualityValue(currentQuality().label, current.height, current.offline)) { showQualitySheet() }
        if (current.sources.size > 1) {
            val source = current.sources.firstOrNull { it.id == current.selectedMediaSourceId } ?: current.sources.first()
            choiceOverlay.setting("Version", source.name.ifEmpty { source.container.uppercase() }) { showVersionSheet() }
        }
        choiceOverlay.setting("Speed", PlayerLabels.speed(playbackSpeed)) { showSpeedSheet() }
        choiceOverlay.setting("Aspect", PlayerLabels.aspect(playbackAspect)) { showAspectSheet() }
        if (selectedSubtitleSupportsOffset(current)) {
            choiceOverlay.setting("Subtitle timing", PlayerLabels.subtitleOffset(subtitleOffsetMillis)) { showSubtitleOffsetSheet() }
        }
        choiceOverlay.setting("Stream", current.playMethod.ifEmpty { "Playback" }) { showStreamDetails() }
        choiceOverlay.note("These change only this video. Skip distance, intros, up next and how subtitles look live in Settings › Playback and Subtitles.")
        choiceOverlay.focusBody()
        handler.removeCallbacks(hideControls)
    }

    private fun currentQuality() = PlaybackRules.qualities.firstOrNull { it.bitrate == selectedQuality } ?: PlaybackRules.qualities.first()

    private fun showQualitySheet() {
        val current = plan ?: return
        if (current.offline) {
            host.notify("A downloaded video plays its original file")
            return
        }
        choiceOverlay.pickValue(
            "Quality", "Lower uses less of your connection; Original plays the file as it is.",
            PlaybackRules.qualities, currentQuality(), { it.label }, onCancel = ::showPlaybackPanel
        ) { picked ->
            selectedQuality = picked.bitrate
            changeSelection(quality = selectedQuality)
        }
        handler.removeCallbacks(hideControls)
    }

    private fun showVersionSheet() {
        val current = plan ?: return
        val active = current.sources.firstOrNull { it.id == current.selectedMediaSourceId } ?: current.sources.firstOrNull() ?: return
        choiceOverlay.pickValue(
            "Version", "", current.sources, active,
            { it.name.ifEmpty { it.container.uppercase() } },
            { PlayerLabels.sourceDetail(it.container, it.bitrate) },
            onCancel = ::showPlaybackPanel
        ) { picked -> changeSelection(source = picked.id) }
        handler.removeCallbacks(hideControls)
    }

    /** How it is being delivered: method, size, codecs, bitrate, and why the server converts it. */
    private fun showStreamDetails() {
        val current = plan ?: return
        choiceOverlay.resetBody()
        choiceOverlay.open("Stream", onDismiss = ::showPlaybackPanel)
        choiceOverlay.body.addView(TextView(host.viewContext).apply {
            text = PlayerLabels.diagnostic(current).replace(" · ", "\n")
            textSize = 14f
            setLineSpacing(0f, 1.25f)
            setTextColor(colors.primaryText)
            setPadding(dp(4), dp(4), dp(4), dp(16))
            setTextIsSelectable(true)
        })
        choiceOverlay.focusBody()
        handler.removeCallbacks(hideControls)
    }

    private fun moveCastToDevice() {
        val remote = CastPlaybackCoordinator.activePlan ?: return
        if (selectionJob?.isActive == true) return
        val at = CastPlaybackCoordinator.positionMillis
        status.visibility = View.VISIBLE
        status.text = "Preparing playback on Pocket DS…"
        selectionJob = scope.launch {
            val body = PlaybackCapabilitiesProbe.prepare(host.viewContext, "resume", at).copy(
                mediaSourceId = remote.selectedMediaSourceId.takeIf { it.isNotEmpty() },
                audioStreamIndex = remote.selectedAudioIndex,
                subtitleStreamIndex = remote.selectedSubtitleIndex
            )
            when (val result = api.preparePlayback(remote.item.id, body)) {
                is HubResult.Ok -> {
                    val local = result.value.copy(positionMillis = at)
                    plan = local
                    serviceLoaded = true
                    pipButton.visibility = View.VISIBLE
                    PlaybackService.load(host.viewContext, local, subtitleOffsetMillis = subtitleOffsetMillis)
                    connectController()
                    restoreSubtitleOffset(local)
                    prepareDynamicSubtitle(local)
                    updateControlLabels(local)
                    CastPlaybackCoordinator.stop()
                    status.visibility = View.GONE
                    host.notify("Playing on Pocket DS")
                }
                is HubResult.Failed -> {
                    status.visibility = View.GONE
                    host.notify(result.message)
                }
            }
            selectionJob = null
        }
    }

    /** Chapters: a frame from each, where it starts, how long, and what it is; A jumps there. */
    private fun showChapterSheet() {
        val durationMillis = controller?.duration?.takeIf { it > 0 } ?: plan?.durationMillis ?: 0
        val chapters = PlaybackEnhancements.chapters(plan?.chapters.orEmpty(), durationMillis)
        if (chapters.isEmpty()) {
            host.notify("This item has no chapter markers")
            showControls()
            return
        }
        val at = controller?.currentPosition ?: 0L
        val now = chapters.indexOfLast { it.positionMillis <= at }.coerceAtLeast(0)
        choiceOverlay.resetBody()
        choiceOverlay.open("Chapters", onDismiss = ::showControls)
        var selected: View? = null
        chapters.forEachIndexed { index, chapter ->
            val end = chapters.getOrNull(index + 1)?.positionMillis ?: durationMillis
            val kind = PlaybackEnhancements.segmentAt(plan?.segments.orEmpty(), chapter.positionMillis)
                ?.type?.let(PlayerLabels::segmentKind)
            val row = choiceOverlay.choice(
                chapter.name, PlayerLabels.chapterDetail(chapter.positionMillis, end, kind),
                selected = index == now, leading = chapterFrame(chapter.positionMillis, end)
            ) {
                choiceOverlay.dismiss()
                controller?.seekTo(chapter.positionMillis)
                showControls()
            }
            if (index == now) selected = row
        }
        choiceOverlay.focusBody(selected)
        handler.removeCallbacks(hideControls)
    }

    /** A chapter's picture, from the hub's frame a little way in; a plain tile when it has none (a downloaded file). */
    private fun chapterFrame(startMillis: Long, endMillis: Long): View = ImageView(host.viewContext).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = ThemeGradientDrawable.rounded(dp(8).toFloat(), colors.posterPlaceholder)
        clipToOutline = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val previewUrl = plan?.previewUrl.orEmpty()
        val at = PlaybackEnhancements.chapterFrameMillis(startMillis, endMillis)
        Artwork.bind(this, imageLoader(), previewUrl.takeIf(String::isNotEmpty)?.let { api.playbackUrl(it) + "?positionMillis=$at" }, opaque = true) {
            size(dp(96), dp(54))
        }
    }

    private fun showSpeedSheet() {
        choiceOverlay.pickValue(
            "Playback speed", "Changes apply without reloading the video.",
            PlaybackEnhancements.speeds, playbackSpeed, PlayerLabels::speed, onCancel = ::showPlaybackPanel
        ) { picked ->
            playbackSpeed = picked
            controller?.setPlaybackSpeed(playbackSpeed)
            showControls()
        }
        handler.removeCallbacks(hideControls)
    }

    private fun showAspectSheet() {
        choiceOverlay.pickValue(
            "Aspect", "Fit keeps the whole picture visible.",
            PlaybackAspect.entries, playbackAspect, PlayerLabels::aspect, onCancel = ::showPlaybackPanel
        ) { picked ->
            playbackAspect = picked
            applyAspect()
            showControls()
        }
        handler.removeCallbacks(hideControls)
    }

    private fun applyAspect() {
        playerView.resizeMode = when (playbackAspect) {
            PlaybackAspect.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            PlaybackAspect.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            PlaybackAspect.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            PlaybackAspect.ORIGINAL -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
        }
    }

    private fun changeSelection(
        source: String? = null,
        audio: Int? = null,
        subtitle: Int? = null,
        quality: Int? = null,
        forceTranscode: Boolean? = null
    ) {
        val current = plan ?: return
        val value = controller
        val wasPlaying = if (forceTranscode == true) true else value?.isPlaying != false
        val at = value?.currentPosition?.coerceAtLeast(0) ?: current.positionMillis
        if (current.offline) {
            if (source != null || quality != null || forceTranscode == true) {
                host.notify("This downloaded file is already using its original quality")
                showControls()
                return
            }
            val updated = current.copy(
                positionMillis = at,
                selectedAudioIndex = audio ?: current.selectedAudioIndex,
                selectedSubtitleIndex = if (subtitle != null) subtitle.takeIf { it >= 0 }
                    else current.selectedSubtitleIndex
            )
            plan = updated
            remember(updated)
            PlaybackService.load(host.viewContext, updated, wasPlaying, subtitleOffsetMillis)
            prepareDynamicSubtitle(updated)
            updateControlLabels(updated)
            status.visibility = View.GONE
            return
        }
        value?.pause()
        status.visibility = View.VISIBLE
        status.setTextColor(Color.WHITE)
        status.text = "Applying playback option…"
        selectionJob?.cancel()
        selectionJob = scope.launch {
            when (val result = api.selectPlayback(
                current.sessionId,
                PlaybackSelectBody(
                    positionMillis = at,
                    mediaSourceId = source,
                    audioStreamIndex = audio,
                    subtitleStreamIndex = subtitle,
                    maxBitrate = quality,
                    forceTranscode = forceTranscode
                )
            )) {
                is HubResult.Ok -> {
                    plan = result.value
                    remember(result.value)
                    PlaybackService.load(host.viewContext, result.value, wasPlaying, subtitleOffsetMillis)
                    prepareDynamicSubtitle(result.value)
                    updateControlLabels(result.value)
                    status.visibility = View.GONE
                }
                is HubResult.Failed -> {
                    status.visibility = View.GONE
                    host.notify(result.message)
                    if (wasPlaying) value?.play()
                }
            }
            selectionJob = null
        }
    }

    private fun remember(value: PlaybackPrepareResponse) {
        PlaybackPreferences.remember(
            host.viewContext,
            HubSettings.userId(host.viewContext),
            value,
            value.audioTracks.firstOrNull { it.index == value.selectedAudioIndex },
            value.subtitleTracks.firstOrNull { it.index == value.selectedSubtitleIndex }
        )
    }

    /** The video ended: the card counts down to the next episode, even after Watch credits. */
    private fun showNextCountdown() {
        if (plan?.nextItem == null) {
            setControls(true)
            return
        }
        upNextDismissed = false
        if (upNext.visibility == View.VISIBLE && upNext.counting) {
            upNext.resume()
            return
        }
        showUpNext()
        upNext.resume()
    }

    private fun playNext() = playAdjacent(plan?.nextItem, "next")

    private fun playPrevious() = playAdjacent(plan?.previousItem, "previous")

    private fun playAdjacent(target: com.pocketds.hub.model.PlaybackItem?, direction: String) {
        val old = plan ?: return
        val adjacent = target ?: return
        if (CastPlaybackCoordinator.isActive) {
            status.visibility = View.VISIBLE
            status.text = "Opening $direction episode on TV…"
            val candidate = old.copy(
                item = adjacent, selectedMediaSourceId = "",
                selectedAudioIndex = null, selectedSubtitleIndex = null
            )
            selectionJob?.cancel()
            selectionJob = scope.launch {
                val error = CastPlaybackCoordinator.transfer(
                    host.viewContext, api, candidate, 0L, startMode = "restart"
                )
                if (error == null) {
                    plan = CastPlaybackCoordinator.activePlan
                    showRemotePlayback()
                } else {
                    status.visibility = View.GONE
                    host.notify(error)
                }
                selectionJob = null
            }
            return
        }
        hideUpNext()
        status.visibility = View.VISIBLE
        status.text = "Opening $direction episode…"
        if (old.offline) {
            val local = OfflineRepository.get(host.viewContext).playbackPlan(adjacent.id, "restart")
            if (local != null) {
                plan = local
                serviceLoaded = true
                PlaybackService.load(host.viewContext, local, subtitleOffsetMillis = subtitleOffsetMillis)
                prepareDynamicSubtitle(local)
                updateControlLabels(local)
                duration.text = PlayerLabels.remainingLine(controller?.currentPosition ?: 0L, local.durationMillis)
                status.visibility = View.GONE
                return
            }
        }
        selectionJob = scope.launch {
            if (!old.offline) api.deletePlayback(old.sessionId)
            when (val result = api.preparePlayback(
                adjacent.id,
                PlaybackCapabilitiesProbe.prepare(host.viewContext, "restart")
            )) {
                is HubResult.Ok -> {
                    plan = applyRememberedSelection(result.value)
                    serviceLoaded = true
                    PlaybackService.load(
                        host.viewContext,
                        requireNotNull(plan),
                        subtitleOffsetMillis = subtitleOffsetMillis
                    )
                    prepareDynamicSubtitle(requireNotNull(plan))
                    updateControlLabels(requireNotNull(plan))
                    duration.text = PlayerLabels.remainingLine(controller?.currentPosition ?: 0L, requireNotNull(plan).durationMillis)
                    status.visibility = View.GONE
                }
                is HubResult.Failed -> {
                    status.setTextColor(colors.dangerText)
                    status.text = result.message + "\nPress B to return"
                }
            }
            selectionJob = null
        }
    }

    /** What the controls do; PlayerChrome only builds them. */
    private inner class ChromeActions : PlayerChrome.Actions {
        override fun showTracks() = showTrackSheet()
        override fun showChapters() = showChapterSheet()
        override fun showOptions() = showPlaybackSheet()
        override fun toggleLock() {
            touchLocked = !touchLocked
            chrome.setLocked(touchLocked)
            host.notify(if (touchLocked) "Touch controls locked" else "Touch controls unlocked")
            showControls()
        }
        override fun enterPictureInPicture() {
            setControls(false)
            if (!host.enterPictureInPicture(playerView)) showControls()
        }
        override fun close() { host.back() }
        override fun playPrevious() = this@PlayerScreen.playPrevious()
        override fun rewind() = seekBy(-configuredSeekMillis())
        override fun togglePlay() = this@PlayerScreen.togglePlay()
        override fun forward() = seekBy(configuredSeekMillis())
        override fun playNext() = this@PlayerScreen.playNext()
        override fun controlFocused() = showControls()
    }

    /** Dragging the timeline: pause, preview the frame, seek on release. */
    private inner class TimelineListener : SeekBar.OnSeekBarChangeListener {
        override fun onStartTrackingTouch(seekBar: SeekBar) {
            seekingByTouch = true
            suppressPlaybackChrome = true
            if (CastPlaybackCoordinator.isActive) {
                scrubStartMillis = CastPlaybackCoordinator.positionMillis
                showSeekPreview(scrubStartMillis, showDelta = false)
                return
            }
            timelineWasPlaying = controller?.isPlaying == true
            controller?.pause()
            val current = controller?.currentPosition?.coerceAtLeast(0) ?: 0
            scrubStartMillis = current
            showSeekPreview(current, showDelta = false)
        }

        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            if (!fromUser) return
            val end = controller?.duration?.takeIf { it > 0 } ?: plan?.durationMillis ?: 0
            showSeekPreview((end * progress) / 10_000L, showDelta = false)
        }

        override fun onStopTrackingTouch(seekBar: SeekBar) {
            val end = controller?.duration?.takeIf { it > 0 } ?: plan?.durationMillis ?: 0
            if (CastPlaybackCoordinator.isActive) {
                CastPlaybackCoordinator.seekTo((end * seekBar.progress) / 10_000L)
            } else controller?.seekTo((end * seekBar.progress) / 10_000L)
            seekingByTouch = false
            if (timelineWasPlaying) controller?.play()
            suppressPlaybackChrome = false
            handler.removeCallbacks(hideSeekPreview)
            handler.postDelayed(hideSeekPreview, 450L)
            showControls()
        }
    }

    private fun updateControlLabels(value: PlaybackPrepareResponse) {
        // A new item starts with no frame rather than the last one's.
        lastPreviewThumbnail = -1
        seekPreviewImage.setImageDrawable(null)
        seekPreviewFrame.visibility = View.GONE
        activeSegmentId = ""
        autoSkipped.clear()
        skipPill.visibility = View.GONE
        upNextDismissed = false
        upNextAt = value.nextItem?.let { UpNext.cardAt(PlaybackSettings.nextTiming(host.viewContext), value.segments, value.durationMillis) }
        if (upNext.visibility == View.VISIBLE) hideUpNext()
        // Bound now, at the start, so the next episode's still has arrived by the time the
        // card shows: bound at that moment, the card sat on its placeholder for seconds.
        value.nextItem?.let(upNext::bind)
        seekBar.marks = if (value.durationMillis > 0) PlaybackEnhancements.chapters(value.chapters, value.durationMillis)
            .map { it.positionMillis.toFloat() / value.durationMillis } else emptyList()
        showTitle(value.item, value.offline)
        chaptersButton.visibility = if (value.chapters.isEmpty()) View.GONE else View.VISIBLE
        val audio = value.audioTracks.firstOrNull { it.index == value.selectedAudioIndex }
        val subtitle = value.subtitleTracks.firstOrNull { it.index == value.selectedSubtitleIndex }
        tracksButton.contentDescription = "Audio and subtitles. " +
            (audio?.let { "Audio ${it.label}. " } ?: "") +
            (subtitle?.let { "Subtitles ${it.label}, ${PlayerLabels.subtitleOffset(subtitleOffsetMillis)}" } ?: "Subtitles off")
        // Invisible rather than gone: the row keeps its shape, so Play stays in the middle.
        previousButton.visibility = if (value.previousItem == null) View.INVISIBLE else View.VISIBLE
        previousButton.contentDescription = value.previousItem?.let { "Play previous episode, ${it.displayTitle()}" }
            ?: "Previous episode unavailable"
        nextButton.visibility = if (value.nextItem == null) View.INVISIBLE else View.VISIBLE
        nextButton.contentDescription = value.nextItem?.let { "Play next episode, ${it.displayTitle()}" }
            ?: "Next episode unavailable"
        controller?.setPlaybackSpeed(playbackSpeed)
        applyAspect()
        applySubtitleAppearance()
    }


    private fun showControls() {
        setControls(true)
        scheduleHide()
    }

    private fun inControls(view: View): Boolean {
        var v: View? = view
        while (v != null) {
            if (controlPanels.any { it === v }) return true
            v = v.parent as? View
        }
        return false
    }

    /** The title bar: the series or film, and under it the episode (and Offline). */
    private fun showTitle(item: com.pocketds.hub.model.PlaybackItem, offline: Boolean) {
        titleView.text = PlayerLabels.title(item)
        chrome.subtitleView.text = PlayerLabels.subtitle(item, offline)
        chrome.subtitleView.visibility = if (chrome.subtitleView.text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    /** "5:34 · Part A" under the timeline's start, "−22:53" under its end. */
    private fun showTimes(current: Long, end: Long) {
        val chapter = plan?.chapters.orEmpty().lastOrNull { it.positionMillis <= current }?.name
        position.text = PlayerLabels.positionLine(current, chapter)
        duration.text = PlayerLabels.remainingLine(current, end)
    }

    private fun setControls(visible: Boolean) {
        controlsVisible = visible
        controlPanels.forEach { it.visibility = if (visible) View.VISIBLE else View.GONE }
        // The pill and the card sit above the timeline while it shows.
        (skipPill.layoutParams as FrameLayout.LayoutParams).let {
            it.bottomMargin = dp(if (visible) SKIP_BOTTOM_SHOWN_DP else SKIP_BOTTOM_HIDDEN_DP)
            skipPill.layoutParams = it
        }
        (upNext.layoutParams as FrameLayout.LayoutParams).let {
            it.bottomMargin = dp(if (visible) UP_NEXT_BOTTOM_DP else SKIP_BOTTOM_HIDDEN_DP)
            upNext.layoutParams = it
        }
        placeSubtitles()
        if (!visible) {
            handler.removeCallbacks(hideControls)
            if (controlPanels.any { it.hasFocus() }) root.requestFocus()
        }
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideControls)
        if (controller?.isPlaying == true && !choiceOverlay.isOpen) handler.postDelayed(hideControls, 3_500L)
    }

    private fun configuredSeekSeconds(): Int = PlaybackSettings.seekSeconds(host.viewContext)

    private fun configuredSeekMillis(): Long = configuredSeekSeconds() * 1_000L

    private fun dp(value: Int) = Styler.dpInt(host.viewContext, value.toFloat())

    private companion object {
        const val SUBTITLE_TICK_MILLIS = 50L
        const val SKIP_BOTTOM_HIDDEN_DP = 28
        const val SKIP_BOTTOM_SHOWN_DP = 132
        const val UP_NEXT_BOTTOM_DP = 132
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
