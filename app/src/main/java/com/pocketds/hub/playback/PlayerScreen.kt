package com.pocketds.hub.playback

import com.pocketds.hub.ui.ThemeGradientDrawable
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.media.AudioManager
import android.media.audiofx.AudioEffect
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
import com.google.android.gms.cast.framework.CastButtonFactory
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
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.offline.OfflineRepository
import com.pocketds.hub.settings.HubSettings
import com.pocketds.hub.settings.PlaybackSettings
import com.pocketds.hub.ui.TrackPresentation
import com.pocketds.hub.ui.ChoiceOverlay
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Theme
import com.pocketds.hub.ui.activateOnTap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs
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
    private lateinit var gestureView: PlayerGestureView
    private lateinit var topPanel: LinearLayout
    private lateinit var controllerPanel: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var status: TextView
    private lateinit var position: TextView
    private lateinit var duration: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var playButton: PlayerIconButton
    private lateinit var tracksButton: PlayerIconButton
    private val menuState = PlayerMenuState()
    private lateinit var optionsButton: PlayerIconButton
    private lateinit var castButton: MediaRouteButton
    private lateinit var lockButton: PlayerIconButton
    private lateinit var pipButton: PlayerIconButton
    private lateinit var closeButton: PlayerIconButton
    private lateinit var previousButton: PlayerIconButton
    private lateinit var rewindButton: PlayerIconButton
    private lateinit var forwardButton: PlayerIconButton
    private lateinit var skipButton: PlayerIconButton
    private lateinit var nextButton: PlayerIconButton
    private lateinit var choiceOverlay: ChoiceOverlay
    private lateinit var subtitleOffsetOverlay: SubtitleOffsetOverlay
    private lateinit var nextPanel: LinearLayout
    private lateinit var nextText: TextView
    private lateinit var seekPreview: LinearLayout
    private lateinit var seekPreviewImage: ImageView
    private lateinit var seekPreviewUnavailable: TextView
    private lateinit var seekPreviewTime: TextView
    private lateinit var seekPreviewDelta: TextView
    private lateinit var gestureFeedback: TextView
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
    private val countdown = NextEpisodeCountdown(15)
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
    private var subtitleAppearance = PlaybackEnhancements.defaultSubtitleAppearance
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
    private val nextTick = object : Runnable {
        override fun run() {
            if (!countdown.active) return
            if (countdown.elapse()) {
                playNext()
                return
            }
            val next = plan?.nextItem
            nextText.text = "Next: ${next?.displayTitle().orEmpty()} · ${countdown.remaining}"
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        colors = Theme.colors(host.viewContext)
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
            setApplyEmbeddedStyles(true)
            setApplyEmbeddedFontSizes(true)
            setUserDefaultStyle()
            setUserDefaultTextSize()
            setBottomPaddingFraction(0.08f)
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
        topPanel = buildTopController()
        root.addView(topPanel, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))
        controllerPanel = buildController()
        root.addView(controllerPanel, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        seekPreview = buildSeekPreview()
        root.addView(
            seekPreview,
            FrameLayout.LayoutParams(dp(190), WRAP, Gravity.BOTTOM or Gravity.START).apply {
                bottomMargin = dp(122)
            }
        )
        gestureFeedback = buildGestureFeedback()
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
        nextPanel = buildNextPanel()
        root.addView(
            nextPanel,
            FrameLayout.LayoutParams(dp(560), WRAP, Gravity.BOTTOM or Gravity.END).apply {
                setMargins(dp(20), dp(20), dp(20), dp(28))
            }
        )
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
        handler.removeCallbacks(nextTick)
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
        if (nextPanel.visibility == View.VISIBLE) { cancelNext(); return true }
        if (controlsVisible) { setControls(false); return true }
        host.back()
        return true
    }

    override fun onPictureInPictureModeChanged(active: Boolean) {
        if (active) {
            handler.removeCallbacks(hideControls)
            choiceOverlay.dismiss()
            topPanel.visibility = View.GONE
            controllerPanel.visibility = View.GONE
            nextPanel.visibility = View.GONE
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
                if (controlsVisible && focused is PlayerIconButton) focused.performClick()
                else if (controlsVisible && focused === castButton) castButton.performClick()
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
                    nextPanel.visibility == View.VISIBLE -> cancelNext()
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
        duration.text = Fmt.clock(current.durationMillis)
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
        titleView.text = plan?.item?.displayTitle().orEmpty()
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
        if (!controlsVisible) {
            showControls()
            playButton.requestFocus()
            return
        }
        val focused = root.findFocus()
        val top = listOf<View>(tracksButton, castButton, optionsButton, lockButton, pipButton, closeButton)
            .filter { it.visibility == View.VISIBLE && it.isEnabled }
        val playback = listOf<View>(previousButton, rewindButton, playButton, forwardButton, skipButton, nextButton)
            .filter { it.visibility == View.VISIBLE && it.isEnabled }
        when {
            focused === seekBar -> when (direction) {
                Direction.LEFT, Direction.RIGHT -> seekTimeline(direction)
                Direction.UP -> top.firstOrNull()?.requestFocus()
                Direction.DOWN -> playButton.requestFocus()
            }
            focused in top -> when (direction) {
                Direction.LEFT, Direction.RIGHT -> moveWithin(top, focused, direction)
                Direction.DOWN -> seekBar.requestFocus()
                Direction.UP -> Unit
            }
            focused in playback -> when (direction) {
                Direction.LEFT, Direction.RIGHT -> moveWithin(playback, focused, direction)
                Direction.UP -> seekBar.requestFocus()
                Direction.DOWN -> Unit
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
        showGestureFeedback("${signedTime(delta)}  ·  ${Fmt.clock(target)}", side)
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
        if (showDelta) seekPreviewDelta.text = signedTime(targetMillis - scrubStartMillis)
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
        seekPreviewImage.visibility = View.VISIBLE
        seekPreviewUnavailable.visibility = View.GONE
        if (frame.thumbnailIndex == lastPreviewThumbnail) return
        lastPreviewThumbnail = frame.thumbnailIndex
        previewRequest?.dispose()
        seekPreviewImage.setImageDrawable(ColorDrawable(Color.rgb(28, 30, 36)))
        val requestedThumbnail = frame.thumbnailIndex
        val request = ImageRequest.Builder(host.viewContext)
            .data(api.playbackUrl("${info.tileUrl}/${frame.tileIndex}"))
            .allowHardware(false)
            .bitmapConfig(Bitmap.Config.RGB_565)
            .target(
                onError = {
                    if (lastPreviewThumbnail == requestedThumbnail) {
                        seekPreviewImage.setImageDrawable(ColorDrawable(Color.rgb(28, 30, 36)))
                        seekPreviewUnavailable.visibility = View.VISIBLE
                    }
                },
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
                        seekPreviewImage.setImageBitmap(
                            Bitmap.createBitmap(sheet, left, top, cellWidth, cellHeight)
                        )
                        seekPreviewUnavailable.visibility = View.GONE
                    }
                }
            )
            .build()
        previewRequest = imageLoader().enqueue(request)
    }

    private fun loadExtractedPreview(positionMillis: Long) {
        val previewUrl = plan?.previewUrl.orEmpty()
        if (previewUrl.isEmpty()) {
            seekPreviewImage.setImageDrawable(ColorDrawable(Color.rgb(28, 30, 36)))
            seekPreviewUnavailable.visibility = View.VISIBLE
            return
        }
        val bucket = (positionMillis.coerceAtLeast(0) / 5_000L) * 5_000L
        val previewKey = -(bucket / 5_000L).toInt() - 2
        if (previewKey == lastPreviewThumbnail) return
        lastPreviewThumbnail = previewKey
        previewRequest?.dispose()
        seekPreviewImage.setImageDrawable(ColorDrawable(Color.rgb(28, 30, 36)))
        seekPreviewUnavailable.visibility = View.GONE
        val request = ImageRequest.Builder(host.viewContext)
            .data(api.playbackUrl(previewUrl) + "?positionMillis=$bucket")
            .allowHardware(false)
            .bitmapConfig(Bitmap.Config.RGB_565)
            .target(
                onError = {
                    if (lastPreviewThumbnail == previewKey) seekPreviewUnavailable.visibility = View.VISIBLE
                },
                onSuccess = { drawable ->
                    if (lastPreviewThumbnail != previewKey) return@target
                    seekPreviewImage.setImageDrawable(drawable)
                    seekPreviewUnavailable.visibility = View.GONE
                }
            )
            .build()
        previewRequest = imageLoader().enqueue(request)
    }

    private fun setSeekBarTarget(targetMillis: Long, durationMillis: Long) {
        if (durationMillis <= 0) return
        seekBar.progress = ((targetMillis.toDouble() / durationMillis) * seekBar.max)
            .roundToInt().coerceIn(0, seekBar.max)
    }

    private fun updateSeekPreviewAnchor(targetMillis: Long) {
        seekBar.post {
            if (!::seekPreview.isInitialized || seekBar.width <= 0 || root.width <= 0) return@post
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
        (api as? HubClient)?.imageLoader ?: ImageLoader(host.viewContext)

    private fun signedTime(deltaMillis: Long): String {
        val sign = if (deltaMillis < 0) "−" else "+"
        return sign + Fmt.clock(abs(deltaMillis))
    }

    private fun updateTimeline() {
        if (CastPlaybackCoordinator.isActive) {
            val current = CastPlaybackCoordinator.positionMillis
            val end = CastPlaybackCoordinator.activePlan?.durationMillis ?: 0
            position.text = Fmt.clock(current)
            duration.text = Fmt.clock(end)
            playButton.setIcon(if (CastPlaybackCoordinator.isPlaying) PlayerControlIcon.PAUSE else PlayerControlIcon.PLAY)
            playButton.contentDescription = if (CastPlaybackCoordinator.isPlaying) "Pause on TV" else "Play on TV"
            if (!seekingByTouch && !padTimelineSeeking && end > 0) setSeekBarTarget(current, end)
            return
        }
        val value = controller ?: return
        val current = value.currentPosition.coerceAtLeast(0)
        val end = value.duration.takeIf { it > 0 } ?: plan?.durationMillis ?: 0
        position.text = Fmt.clock(current)
        duration.text = Fmt.clock(end)
        if (!seekingByTouch && !padTimelineSeeking) {
            seekBar.max = 10_000
            seekBar.progress = if (end > 0) ((current.toDouble() / end) * 10_000).toInt().coerceIn(0, 10_000) else 0
        }
        updateSegmentSkip(current)
    }

    private fun updateSegmentSkip(positionMillis: Long) {
        if (!::skipButton.isInitialized) return
        val segment = PlaybackEnhancements.skipPrompt(plan?.segments.orEmpty(), positionMillis)
        val id = segment?.id.orEmpty()
        if (id == activeSegmentId) return
        activeSegmentId = id
        skipButton.visibility = if (segment == null) View.GONE else View.VISIBLE
        if (segment != null) {
            skipButton.contentDescription = "Skip ${segment.type.ifBlank { "segment" }}"
            showGestureFeedback("Skip ${segment.type.ifBlank { "segment" }}", PlayerGestureView.Side.CENTER)
        }
    }

    private fun syncServicePlan() {
        val active = PlaybackService.currentPlan() ?: return
        val current = plan ?: return
        if (active.sessionId != current.sessionId || active == current) return
        plan = active
        prepareDynamicSubtitle(active)
        updateControlLabels(active)
        duration.text = Fmt.clock(active.durationMillis)
    }

    private fun showTrackSheet() = showTracks(menuState.trackTab)

    private fun showTracks(tab: String) {
        if (CastPlaybackCoordinator.isActive) {
            showCastTracks(tab)
            return
        }
        if (subtitleOffsetOverlay.isOpen) subtitleOffsetOverlay.onPad(PadAction.Back)
        val current = plan ?: return
        menuState.selectTrackTab(tab)
        choiceOverlay.resetBody()
        choiceOverlay.open("Audio & subtitles", onDismiss = ::showControls)
        choiceOverlay.tabs(listOf("audio" to "Audio", "subtitles" to "Subtitles"), menuState.trackTab, ::showTracks)
        var selected: View? = null
        if (tab == "subtitles") {
            val off = current.selectedSubtitleIndex == null || current.selectedSubtitleIndex == -1
            val row = choiceOverlay.choice("Off", selected = off) { choiceOverlay.dismiss(); changeSelection(subtitle = -1) }
            if (off) selected = row
        }
        val tracks = if (tab == "audio") current.audioTracks else current.subtitleTracks
        tracks.forEach { track ->
            val copy = TrackPresentation.of(track)
            val active = track.index == if (tab == "audio") current.selectedAudioIndex else current.selectedSubtitleIndex
            val row = choiceOverlay.choice(copy.title, copy.detail, selected = active) {
                choiceOverlay.dismiss()
                if (tab == "audio") changeSelection(audio = track.index) else changeSelection(subtitle = track.index)
            }
            if (active) selected = row
        }
        if (tab == "audio" && tracks.isEmpty()) choiceOverlay.choice("No selectable audio tracks") { choiceOverlay.cancel() }
        if (tab == "subtitles" && selectedSubtitleSupportsOffset(current)) {
            addSheetSection("TIMING")
            choiceOverlay.choice("Adjust subtitle timing", subtitleOffsetLabel(subtitleOffsetMillis)) { showSubtitleOffsetSheet() }
        }
        if (tab == "subtitles") {
            addSheetSection("APPEARANCE")
            choiceOverlay.choice("Subtitle appearance", subtitleAppearanceLabel(subtitleAppearance)) { showSubtitleAppearanceSheet() }
        }
        choiceOverlay.focusBody(selected)
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
                if (!topPanel.hasFocus() && !controllerPanel.hasFocus()) playButton.requestFocus()
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

    private fun subtitleOffsetLabel(offsetMillis: Long): String = when {
        offsetMillis == 0L -> "No offset"
        offsetMillis < 0 -> "%.1f seconds earlier".format(abs(offsetMillis) / 1_000.0)
        else -> "%.1f seconds later".format(offsetMillis / 1_000.0)
    }

    private fun showPlaybackSheet() = showPlaybackPanel("quality")

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

    /** A small uppercase heading between groups of rows in a player side panel. */
    private fun addSheetSection(label: String) {
        choiceOverlay.body.addView(TextView(host.viewContext).apply {
            text = label
            textSize = 11f
            letterSpacing = 0.12f
            setTextColor(colors.mutedText)
            setPadding(dp(10), dp(20), dp(10), dp(6))
        })
    }

    private fun showSubtitleAppearanceSheet() {
        val choices = PlaybackEnhancements.subtitleAppearances.map { appearance ->
            ChoiceOverlay.Choice(
                appearance.name,
                subtitleAppearanceLabel(appearance),
                if (appearance == subtitleAppearance) "Selected" else ""
            )
        }
        val selected = choices.indexOfFirst { it.id == subtitleAppearance.name }.coerceAtLeast(0)
        choiceOverlay.show("Subtitle appearance", "Changes apply without reloading the video.", choices, selected, ::showControls) { id ->
            subtitleAppearance = SubtitleAppearance.valueOf(id)
            applySubtitleAppearance()
            showControls()
        }
        handler.removeCallbacks(hideControls)
    }

    private fun subtitleAppearanceLabel(value: SubtitleAppearance) = when (value) {
        SubtitleAppearance.SYSTEM -> "System"
        SubtitleAppearance.LARGE -> "Large"
        SubtitleAppearance.HIGH_CONTRAST -> "High contrast"
    }

    private fun applySubtitleAppearance() {
        val targets = listOfNotNull(playerView.subtitleView, dynamicSubtitleView)
        targets.forEach { view ->
            when (subtitleAppearance) {
                SubtitleAppearance.SYSTEM -> {
                    view.setApplyEmbeddedStyles(true)
                    view.setApplyEmbeddedFontSizes(true)
                    view.setUserDefaultStyle()
                    view.setUserDefaultTextSize()
                    view.setBottomPaddingFraction(0.08f)
                }
                SubtitleAppearance.LARGE -> {
                    view.setApplyEmbeddedStyles(false)
                    view.setApplyEmbeddedFontSizes(false)
                    view.setStyle(CaptionStyleCompat.DEFAULT)
                    view.setFractionalTextSize(0.075f)
                    view.setBottomPaddingFraction(0.11f)
                }
                SubtitleAppearance.HIGH_CONTRAST -> {
                    view.setApplyEmbeddedStyles(false)
                    view.setApplyEmbeddedFontSizes(false)
                    view.setStyle(CaptionStyleCompat(
                        Color.WHITE,
                        Color.argb(185, 0, 0, 0),
                        Color.TRANSPARENT,
                        CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                        Color.BLACK,
                        null
                    ))
                    view.setFractionalTextSize(0.062f)
                    view.setBottomPaddingFraction(0.09f)
                }
            }
        }
    }

    private fun showPlaybackPanel(tab: String) {
        if (CastPlaybackCoordinator.isActive) {
            showCastPanel()
            return
        }
        val current = plan ?: return
        choiceOverlay.resetBody()
        choiceOverlay.open("Playback", onDismiss = ::showControls)
        choiceOverlay.tabs(
            listOf("quality" to "Quality", "source" to "Version", "view" to "View", "info" to "Info"),
            tab,
            ::showPlaybackPanel
        )
        var selected: View? = null
        when (tab) {
            "quality" -> if (current.offline) {
                selected = choiceOverlay.choice("Original", "Downloaded file · no network required", selected = true) { choiceOverlay.cancel() }
            } else PlaybackRules.qualities.forEach { quality ->
                val active = quality.bitrate == selectedQuality
                val row = choiceOverlay.choice(quality.label, selected = active) {
                    choiceOverlay.dismiss()
                    selectedQuality = quality.bitrate
                    changeSelection(quality = selectedQuality)
                }
                if (active) selected = row
            }
            "source" -> current.sources.forEach { source ->
                val active = source.id == current.selectedMediaSourceId
                val row = choiceOverlay.choice(source.name.ifEmpty { source.container.uppercase() }, sourceDetail(source.container, source.bitrate), selected = active) {
                    choiceOverlay.dismiss(); changeSelection(source = source.id)
                }
                if (active) selected = row
            }
            "view" -> {
                val chapterCount = current.chapters.size
                choiceOverlay.choice(
                    "Chapters",
                    if (chapterCount == 0) "Unavailable" else "$chapterCount markers"
                ) { showChapterSheet() }
                choiceOverlay.choice("Speed", speedLabel(playbackSpeed)) { showSpeedSheet() }
                choiceOverlay.choice("Aspect", aspectLabel(playbackAspect)) { showAspectSheet() }
            }
            else -> choiceOverlay.body.addView(TextView(host.viewContext).apply {
                text = diagnostic(current); textSize = 14f; setTextColor(colors.primaryText)
                setPadding(dp(10), dp(12), dp(10), dp(16)); setTextIsSelectable(true)
            })
        }
        choiceOverlay.focusBody(selected)
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

    private fun showChapterSheet() {
        val durationMillis = controller?.duration?.takeIf { it > 0 } ?: plan?.durationMillis ?: 0
        val chapters = PlaybackEnhancements.chapters(plan?.chapters.orEmpty(), durationMillis)
        if (chapters.isEmpty()) {
            host.notify("This item has no chapter markers")
            showControls()
            return
        }
        val at = controller?.currentPosition ?: 0L
        val choices = chapters.map { chapter ->
            ChoiceOverlay.Choice(chapter.positionMillis.toString(), chapter.name, Fmt.clock(chapter.positionMillis))
        }
        val selected = chapters.indexOfLast { it.positionMillis <= at }.coerceAtLeast(0)
        choiceOverlay.show("Chapters", "Jump to a chapter.", choices, selected, ::showControls) { id ->
            controller?.seekTo(id.toLong())
            showControls()
        }
        handler.removeCallbacks(hideControls)
    }

    private fun showSpeedSheet() {
        val choices = PlaybackEnhancements.speeds.map { speed ->
            ChoiceOverlay.Choice(speed.toString(), speedLabel(speed), if (speed == playbackSpeed) "Selected" else "")
        }
        val selected = choices.indexOfFirst { it.id == playbackSpeed.toString() }.coerceAtLeast(0)
        choiceOverlay.show("Playback speed", "Changes apply without reloading the video.", choices, selected, ::showControls) { id ->
            playbackSpeed = id.toFloat()
            controller?.setPlaybackSpeed(playbackSpeed)
            showControls()
        }
        handler.removeCallbacks(hideControls)
    }

    private fun showAspectSheet() {
        val choices = PlaybackAspect.entries.map { aspect ->
            ChoiceOverlay.Choice(aspect.name, aspectLabel(aspect), if (aspect == playbackAspect) "Selected" else "")
        }
        val selected = choices.indexOfFirst { it.id == playbackAspect.name }.coerceAtLeast(0)
        choiceOverlay.show("Aspect", "Fit keeps the whole picture visible.", choices, selected, ::showControls) { id ->
            playbackAspect = PlaybackAspect.valueOf(id)
            applyAspect()
            showControls()
        }
        handler.removeCallbacks(hideControls)
    }

    private fun speedLabel(value: Float) = if (value == 1f) "Normal" else "${value}×"

    private fun aspectLabel(value: PlaybackAspect) = when (value) {
        PlaybackAspect.FIT -> "Fit"
        PlaybackAspect.FILL -> "Fill"
        PlaybackAspect.ZOOM -> "Zoom"
        PlaybackAspect.ORIGINAL -> "Original aspect"
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

    private fun showNextCountdown() {
        if (plan?.nextItem == null) {
            setControls(true)
            return
        }
        handler.removeCallbacks(nextTick)
        countdown.start()
        nextPanel.visibility = View.VISIBLE
        nextText.text = "Next: ${plan?.nextItem?.displayTitle().orEmpty()} · ${countdown.remaining}"
        handler.postDelayed(nextTick, 1_000L)
    }

    private fun cancelNext() {
        handler.removeCallbacks(nextTick)
        countdown.cancel()
        nextPanel.visibility = View.GONE
        setControls(true)
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
        handler.removeCallbacks(nextTick)
        countdown.cancel()
        nextPanel.visibility = View.GONE
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
                duration.text = Fmt.clock(local.durationMillis)
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
                    duration.text = Fmt.clock(requireNotNull(plan).durationMillis)
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

    private fun buildTopController(): LinearLayout = LinearLayout(host.viewContext).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(22), dp(14), dp(16), dp(18))
        background = ThemeGradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(225, 0, 0, 0), Color.TRANSPARENT)
        )
        titleView = TextView(context).apply {
            text = plan?.item?.displayTitle().orEmpty()
            textSize = 18f
            setTextColor(Color.WHITE)
            maxLines = 2
            setTypeface(typeface, Typeface.BOLD)
        }
        addView(titleView, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(10) })
        tracksButton = control(PlayerControlIcon.TRACKS, "Audio and subtitles") { showTrackSheet() }
        addView(tracksButton)
        castButton = MediaRouteButton(context).apply {
            contentDescription = "Play on a TV"
            isFocusable = true
            isFocusableInTouchMode = true
            CastButtonFactory.setUpMediaRouteButton(context, this)
        }
        addView(castButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        optionsButton = control(
            PlayerControlIcon.OPTIONS, "Playback options, chapters, speed and aspect"
        ) { showPlaybackSheet() }
        addView(optionsButton)
        lockButton = control(PlayerControlIcon.UNLOCK, "Lock touch controls") {
            touchLocked = !touchLocked
            lockButton.setIcon(if (touchLocked) PlayerControlIcon.LOCK else PlayerControlIcon.UNLOCK)
            lockButton.contentDescription = if (touchLocked) "Unlock touch controls" else "Lock touch controls"
            host.notify(if (touchLocked) "Touch controls locked" else "Touch controls unlocked")
            showControls()
        }
        addView(lockButton)
        pipButton = control(PlayerControlIcon.PICTURE_IN_PICTURE, "Open picture in picture") {
            setControls(false)
            if (!host.enterPictureInPicture(playerView)) showControls()
        }
        addView(pipButton)
        closeButton = control(PlayerControlIcon.CLOSE, "Close playback") {
            host.back()
        }
        addView(closeButton)
    }

    private fun buildController(): LinearLayout = LinearLayout(host.viewContext).apply {
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
                setOnFocusChangeListener { _, focused -> if (focused) showControls() }
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
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
                })
            }
            addView(seekBar, LinearLayout.LayoutParams(0, WRAP, 1f))
            duration = timeText("0:00")
            addView(duration, LinearLayout.LayoutParams(dp(64), WRAP))
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER
            previousButton = control(PlayerControlIcon.PREVIOUS, "Play previous episode") { playPrevious() }
            addView(previousButton)
            val seekSeconds = configuredSeekSeconds()
            rewindButton = control(PlayerControlIcon.REWIND, "Jump back $seekSeconds seconds") {
                seekBy(-configuredSeekMillis())
            }
            addView(rewindButton)
            playButton = control(PlayerControlIcon.PLAY, "Play") { togglePlay() }
            addView(playButton)
            forwardButton = control(PlayerControlIcon.FORWARD, "Jump forward $seekSeconds seconds") {
                seekBy(configuredSeekMillis())
            }
            addView(forwardButton)
            skipButton = control(PlayerControlIcon.SKIP, "Skip current segment") {
                val segment = PlaybackEnhancements.skipPrompt(
                    plan?.segments.orEmpty(), controller?.currentPosition ?: 0L
                ) ?: return@control
                controller?.seekTo(segment.endMillis)
                skipButton.visibility = View.GONE
            }.apply { visibility = View.GONE }
            addView(skipButton)
            nextButton = control(PlayerControlIcon.NEXT, "Play next episode") { playNext() }
            addView(nextButton)
        }, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun buildSeekPreview(): LinearLayout = LinearLayout(host.viewContext).apply {
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
        addView(FrameLayout(context).apply {
            seekPreviewImage = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageDrawable(ColorDrawable(Color.rgb(28, 30, 36)))
            }
            addView(seekPreviewImage, FrameLayout.LayoutParams(MATCH, MATCH))
            seekPreviewUnavailable = TextView(context).apply {
                text = "Preview unavailable"
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(Color.argb(185, 255, 255, 255))
            }
            addView(seekPreviewUnavailable, FrameLayout.LayoutParams(MATCH, MATCH))
        }, LinearLayout.LayoutParams(MATCH, dp(94)))
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
                setTextColor(this@PlayerScreen.colors.accent)
                setPadding(dp(12), 0, 0, 0)
            }
            addView(seekPreviewDelta)
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(7) })
    }

    private fun buildGestureFeedback(): TextView = TextView(host.viewContext).apply {
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

    private fun buildNextPanel(): LinearLayout = LinearLayout(host.viewContext).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        background = ThemeGradientDrawable().apply {
            cornerRadius = Styler.dp(context, 14f)
            setColor(Color.argb(235, 25, 25, 30))
            setStroke(dp(2), this@PlayerScreen.colors.accent)
        }
        setPadding(dp(18), dp(16), dp(18), dp(16))
        nextText = TextView(context).apply { textSize = 16f; setTextColor(Color.WHITE) }
        addView(nextText)
        addView(LinearLayout(context).apply {
            gravity = Gravity.END
            addView(control(PlayerControlIcon.CLOSE, "Cancel next episode") { cancelNext() })
            addView(control(PlayerControlIcon.NEXT, "Play next episode now") { playNext() })
        })
    }

    private fun control(
        icon: PlayerControlIcon,
        description: String,
        action: () -> Unit
    ) =
        PlayerIconButton(host.viewContext, icon).apply {
            contentDescription = description
            // Player controls sit directly on the video. A focused control gets
            // a thin, high-contrast ring but never becomes an opaque blue tile.
            background = playerBareButtonBackground()
            Styler.makeFocusable(this)
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            activateOnTap(action)
            setOnFocusChangeListener { _, focused -> if (focused) showControls() }
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(7) }
        }

    private fun playerBareButtonBackground(): StateListDrawable {
        fun face(fill: Int, strokeWidth: Int = 0, strokeColor: Int = 0) = ThemeGradientDrawable().apply {
            cornerRadius = Styler.dp(host.viewContext, 11f)
            setColor(fill)
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }
        return StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_focused),
                face(Color.argb(80, 0, 0, 0), dp(2), colors.focusRing)
            )
            addState(intArrayOf(android.R.attr.state_pressed), face(Color.argb(75, 0, 0, 0)))
            addState(intArrayOf(), face(Color.TRANSPARENT))
        }
    }

    private fun updateControlLabels(value: PlaybackPrepareResponse) {
        lastPreviewThumbnail = -1
        activeSegmentId = ""
        titleView.text = if (value.offline) "${value.item.displayTitle()}  ·  Offline" else value.item.displayTitle()
        val audio = value.audioTracks.firstOrNull { it.index == value.selectedAudioIndex }
        val subtitle = value.subtitleTracks.firstOrNull { it.index == value.selectedSubtitleIndex }
        tracksButton.contentDescription = "Audio and subtitles. " +
            (audio?.let { "Audio ${it.label}. " } ?: "") +
            (subtitle?.let { "Subtitles ${it.label}, ${subtitleOffsetLabel(subtitleOffsetMillis)}" } ?: "Subtitles off")
        previousButton.visibility = if (value.previousItem == null) View.GONE else View.VISIBLE
        previousButton.contentDescription = value.previousItem?.let { "Play previous episode, ${it.displayTitle()}" }
            ?: "Previous episode unavailable"
        nextButton.visibility = if (value.nextItem == null) View.GONE else View.VISIBLE
        nextButton.contentDescription = value.nextItem?.let { "Play next episode, ${it.displayTitle()}" }
            ?: "Next episode unavailable"
        controller?.setPlaybackSpeed(playbackSpeed)
        applyAspect()
        applySubtitleAppearance()
    }

    private fun timeText(value: String) = TextView(host.viewContext).apply {
        text = value
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
    }

    private fun showControls() {
        setControls(true)
        scheduleHide()
    }

    private fun setControls(visible: Boolean) {
        controlsVisible = visible
        topPanel.visibility = if (visible) View.VISIBLE else View.GONE
        controllerPanel.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) {
            handler.removeCallbacks(hideControls)
            if (topPanel.hasFocus() || controllerPanel.hasFocus()) root.requestFocus()
        }
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideControls)
        if (controller?.isPlaying == true && !choiceOverlay.isOpen) handler.postDelayed(hideControls, 3_500L)
    }

    private fun diagnostic(value: PlaybackPrepareResponse): String = buildList {
        add(value.playMethod.ifEmpty { "Playback" })
        if (value.width > 0) add("${value.width}×${value.height}")
        if (value.videoCodec.isNotEmpty()) add(value.videoCodec.uppercase())
        if (value.audioCodec.isNotEmpty()) add(value.audioCodec.uppercase())
        if (value.frameRate > 0) add("%.2f fps".format(value.frameRate))
        if (value.bitrate > 0) add(Fmt.mbps(value.bitrate.toLong()))
        if (value.hdr.isNotEmpty()) add(value.hdr)
        if (value.transcodeReason.isNotEmpty()) add(value.transcodeReason)
    }.joinToString(" · ")

    private fun trackDetail(codec: String, channels: Int) = buildList {
        if (codec.isNotEmpty()) add(codec.uppercase())
        if (channels > 0) add("$channels channels")
    }.joinToString(" · ")

    private fun selectedDetail(detail: String, selected: Boolean): String = buildList {
        if (selected) add("Selected")
        if (detail.isNotEmpty()) add(detail)
    }.joinToString(" · ")

    private fun sourceDetail(container: String, bitrate: Int) = buildList {
        if (container.isNotEmpty()) add(container.uppercase())
        if (bitrate > 0) add(Fmt.mbps(bitrate.toLong()))
    }.joinToString(" · ")


    private fun configuredSeekSeconds(): Int = PlaybackSettings.seekSeconds(host.viewContext)

    private fun configuredSeekMillis(): Long = configuredSeekSeconds() * 1_000L

    private fun dp(value: Int) = Styler.dpInt(host.viewContext, value.toFloat())

    private companion object {
        const val SUBTITLE_TICK_MILLIS = 50L
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
