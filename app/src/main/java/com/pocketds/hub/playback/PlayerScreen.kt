package com.pocketds.hub.playback

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
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

/** Full-screen controller-first Media3 playback for a movie or episode. */
@UnstableApi
class PlayerScreen(
    private val api: HubApi,
    private val itemId: String,
    private val startMode: String,
    initialPlan: PlaybackPrepareResponse? = null,
    private val ringVisible: () -> Boolean
) : Screen {
    override val title = initialPlan?.item?.title ?: "Player"
    override val immersive = true
    override val focusOnShow = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var host: ScreenHost
    private lateinit var colors: PocketColors
    private lateinit var root: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var dynamicSubtitleView: SubtitleView
    private lateinit var topPanel: LinearLayout
    private lateinit var controllerPanel: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var status: TextView
    private lateinit var position: TextView
    private lateinit var duration: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var playButton: PlayerIconButton
    private lateinit var audioButton: PlayerIconButton
    private lateinit var subtitleButton: PlayerIconButton
    private lateinit var optionsButton: PlayerIconButton
    private lateinit var pipButton: PlayerIconButton
    private lateinit var closeButton: PlayerIconButton
    private lateinit var previousButton: PlayerIconButton
    private lateinit var rewindButton: PlayerIconButton
    private lateinit var forwardButton: PlayerIconButton
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
    private var brightnessStart = 0.5f
    private var volumeStart = 0
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
        root.addView(
            PlayerGestureView(host.viewContext, object : PlayerGestureView.Listener {
                override fun onSingleTap() {
                    if (controlsVisible) setControls(false) else showControls()
                }

                override fun onDoubleTap(side: PlayerGestureView.Side) = handleDoubleTap(side)
                override fun onHorizontalStart() = beginHorizontalScrub()
                override fun onHorizontalMove(fraction: Float) = updateHorizontalScrub(fraction)
                override fun onHorizontalEnd(cancelled: Boolean) = finishHorizontalScrub(cancelled)
                override fun onVerticalStart(side: PlayerGestureView.Side) = beginVerticalGesture(side)
                override fun onVerticalMove(side: PlayerGestureView.Side, fraction: Float) =
                    updateVerticalGesture(side, fraction)
                override fun onVerticalEnd(side: PlayerGestureView.Side, cancelled: Boolean) =
                    finishVerticalGesture(side, cancelled)
            }),
            FrameLayout.LayoutParams(MATCH, MATCH)
        )
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

        choiceOverlay = ChoiceOverlay(host.viewContext, colors, ringVisible)
        root.addView(choiceOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
        subtitleOffsetOverlay = SubtitleOffsetOverlay(host.viewContext, colors, ringVisible)
        root.addView(subtitleOffsetOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
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
        handler.removeCallbacks(uiTick)
        handler.post(uiTick)
        handler.removeCallbacks(subtitleTick)
        handler.post(subtitleTick)
        if (plan == null) prepare() else beginPlayback()
        root.post { playButton.requestFocus() }
    }

    override fun onHide() {
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
        PlaybackService.pause(host.viewContext)
    }

    /** Android system Back includes the AYANEO edge-swipe gesture. */
    override fun onSystemBack(): Boolean {
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
                else togglePlay()
                true
            }
            is PadAction.Step -> { moveControllerFocus(action.direction); true }
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
        restoreSubtitleOffset(current)
        prepareDynamicSubtitle(current)
        updateControlLabels(current)
        duration.text = time(current.durationMillis)
        status.setTextColor(Color.WHITE)
        status.text = "Opening ${current.playMethod.lowercase().ifEmpty { "media" }}…"
        if (!serviceLoaded) {
            serviceLoaded = true
            PlaybackService.load(host.viewContext, current, subtitleOffsetMillis = subtitleOffsetMillis)
        }
        connectController()
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
        val top = listOf(audioButton, subtitleButton, optionsButton, pipButton, closeButton)
            .filter { it.visibility == View.VISIBLE && it.isEnabled }
        val playback = listOf(previousButton, rewindButton, playButton, forwardButton, nextButton)
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

    private fun moveWithin(buttons: List<PlayerIconButton>, focused: View?, direction: Direction) {
        if (buttons.isEmpty()) return
        val current = buttons.indexOf(focused).coerceAtLeast(0)
        val delta = if (direction == Direction.LEFT) -1 else 1
        buttons.getOrNull((current + delta).coerceIn(0, buttons.lastIndex))?.requestFocus()
    }

    private fun seekTimeline(direction: Direction) {
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
        showGestureFeedback("${signedTime(delta)}  ·  ${time(target)}", side)
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
            brightnessStart = currentBrightness()
        } else {
            volumeStart = audioManager().getStreamVolume(AudioManager.STREAM_MUSIC)
        }
    }

    private fun updateVerticalGesture(side: PlayerGestureView.Side, fraction: Float) {
        if (side == PlayerGestureView.Side.LEFT) {
            val next = (brightnessStart + fraction).coerceIn(0.02f, 1f)
            val activity = host.viewContext as? Activity ?: return
            activity.window.attributes = activity.window.attributes.apply { screenBrightness = next }
            showLevelFeedback(PlayerLevelView.Kind.BRIGHTNESS, next, side)
        } else {
            val manager = audioManager()
            val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val next = (volumeStart + fraction * max).roundToInt().coerceIn(0, max)
            manager.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
            showLevelFeedback(PlayerLevelView.Kind.VOLUME, next.toFloat() / max, side)
        }
    }

    private fun finishVerticalGesture(
        @Suppress("UNUSED_PARAMETER") side: PlayerGestureView.Side,
        @Suppress("UNUSED_PARAMETER") cancelled: Boolean
    ) {
        handler.removeCallbacks(hideLevelFeedback)
        handler.postDelayed(hideLevelFeedback, 700L)
        scheduleHide()
    }

    private fun currentBrightness(): Float {
        val activity = host.viewContext as? Activity ?: return 0.5f
        val windowValue = activity.window.attributes.screenBrightness
        if (windowValue >= 0f) return windowValue.coerceIn(0.02f, 1f)
        return runCatching {
            Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        }.getOrDefault(0.5f).coerceIn(0.02f, 1f)
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
        seekPreviewTime.text = time(targetMillis)
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
        return sign + time(abs(deltaMillis))
    }

    private fun updateTimeline() {
        val value = controller ?: return
        val current = value.currentPosition.coerceAtLeast(0)
        val end = value.duration.takeIf { it > 0 } ?: plan?.durationMillis ?: 0
        position.text = time(current)
        duration.text = time(end)
        if (!seekingByTouch && !padTimelineSeeking) {
            seekBar.max = 10_000
            seekBar.progress = if (end > 0) ((current.toDouble() / end) * 10_000).toInt().coerceIn(0, 10_000) else 0
        }
    }

    private fun syncServicePlan() {
        val active = PlaybackService.currentPlan() ?: return
        val current = plan ?: return
        if (active.sessionId != current.sessionId || active == current) return
        plan = active
        prepareDynamicSubtitle(active)
        updateControlLabels(active)
        duration.text = time(active.durationMillis)
    }

    private fun showTrackSheet() {
        val current = plan ?: return
        val choices = mutableListOf<ChoiceOverlay.Choice>()
        current.audioTracks.forEach {
            choices += ChoiceOverlay.Choice(
                "audio:${it.index}",
                "Audio · ${it.label}",
                selectedDetail(trackDetail(it.codec, it.channels), it.index == current.selectedAudioIndex)
            )
        }
        choices += ChoiceOverlay.Choice(
            "subtitle:-1",
            "Subtitles · Off",
            if (current.selectedSubtitleIndex == null || current.selectedSubtitleIndex == -1) "Selected" else ""
        )
        current.subtitleTracks.forEach {
            choices += ChoiceOverlay.Choice(
                "subtitle:${it.index}",
                "Subtitles · ${it.label}",
                selectedDetail(trackDetail(it.codec, 0), it.index == current.selectedSubtitleIndex)
            )
        }
        if (selectedSubtitleSupportsOffset(current)) {
            choices += ChoiceOverlay.Choice("offset", "Subtitle timing", subtitleOffsetLabel(subtitleOffsetMillis))
        }
        val selected = choices.indexOfFirst {
            it.id == "audio:${current.selectedAudioIndex}" || it.id == "subtitle:${current.selectedSubtitleIndex}"
        }.coerceAtLeast(0)
        choiceOverlay.show("Audio and subtitles", "Changing a track resumes from the current position.", choices, selected, ::showControls) { id ->
            if (id == "offset") {
                showSubtitleOffsetSheet()
                return@show
            }
            val pieces = id.split(':')
            if (pieces.first() == "audio") changeSelection(audio = pieces.last().toInt())
            else changeSelection(subtitle = pieces.last().toInt())
        }
        handler.removeCallbacks(hideControls)
    }

    private fun showAudioSheet() {
        val current = plan ?: return
        if (current.audioTracks.isEmpty()) {
            host.notify("No selectable audio tracks")
            return
        }
        val choices = current.audioTracks.map {
            ChoiceOverlay.Choice(
                it.index.toString(),
                it.label,
                selectedDetail(trackDetail(it.codec, it.channels), it.index == current.selectedAudioIndex)
            )
        }
        val selected = current.audioTracks.indexOfFirst { it.index == current.selectedAudioIndex }.coerceAtLeast(0)
        choiceOverlay.show(
            "Audio",
            "Choose the audio track for this playback.",
            choices,
            selected,
            ::showControls
        ) { changeSelection(audio = it.toInt()) }
        handler.removeCallbacks(hideControls)
    }

    private fun showSubtitleSheet() {
        val current = plan ?: return
        val off = current.selectedSubtitleIndex == null || current.selectedSubtitleIndex == -1
        val choices = mutableListOf(
            ChoiceOverlay.Choice("-1", "Off", if (off) "Selected" else "")
        )
        choices += current.subtitleTracks.map {
            ChoiceOverlay.Choice(
                it.index.toString(),
                it.label,
                selectedDetail(trackDetail(it.codec, 0), it.index == current.selectedSubtitleIndex)
            )
        }
        if (selectedSubtitleSupportsOffset(current)) {
            choices += ChoiceOverlay.Choice("offset", "Timing", subtitleOffsetLabel(subtitleOffsetMillis))
        }
        val selected = if (off) 0 else choices.indexOfFirst { it.id == current.selectedSubtitleIndex.toString() }.coerceAtLeast(0)
        choiceOverlay.show(
            "Subtitles",
            "Choose a subtitle track or turn subtitles off.",
            choices,
            selected,
            ::showControls
        ) {
            if (it == "offset") showSubtitleOffsetSheet()
            else changeSelection(subtitle = it.toInt())
        }
        handler.removeCallbacks(hideControls)
    }

    private fun showSubtitleOffsetSheet() {
        val current = plan ?: return
        if (!selectedSubtitleSupportsOffset(current)) {
            host.notify("Subtitle timing is available for text subtitles")
            return
        }
        choiceOverlay.dismiss()
        setControls(false)
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
            }
        )
        handler.removeCallbacks(hideControls)
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

    private fun showPlaybackSheet() {
        val current = plan ?: return
        val choices = mutableListOf(
            ChoiceOverlay.Choice("info", "Playback information", diagnostic(current))
        )
        current.sources.forEach { source ->
            choices += ChoiceOverlay.Choice("source:${source.id}", "Version · ${source.name.ifEmpty { source.container.uppercase() }}", sourceDetail(source.container, source.bitrate))
        }
        if (!current.offline) PlaybackRules.qualities.forEach { quality ->
            choices += ChoiceOverlay.Choice("quality:${quality.bitrate}", "Quality · ${quality.label}", if (quality.bitrate == selectedQuality) "Selected" else "")
        }
        choiceOverlay.show("Playback", diagnostic(current), choices, 0, ::showControls) { id ->
            when {
                id == "info" -> {
                    host.notify(diagnostic(current))
                    showControls()
                }
                id.startsWith("source:") -> changeSelection(source = id.removePrefix("source:"))
                id.startsWith("quality:") -> {
                    selectedQuality = id.removePrefix("quality:").toInt()
                    changeSelection(quality = selectedQuality)
                }
            }
        }
        handler.removeCallbacks(hideControls)
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
                duration.text = time(local.durationMillis)
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
                    duration.text = time(requireNotNull(plan).durationMillis)
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
        background = GradientDrawable(
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
        audioButton = control(PlayerControlIcon.AUDIO, "Choose audio track", bare = true) { showAudioSheet() }
        addView(audioButton)
        subtitleButton = control(PlayerControlIcon.SUBTITLES, "Choose subtitles", bare = true) { showSubtitleSheet() }
        addView(subtitleButton)
        optionsButton = control(
            PlayerControlIcon.OPTIONS, "Quality, version and stream information", bare = true
        ) { showPlaybackSheet() }
        addView(optionsButton)
        pipButton = control(PlayerControlIcon.PICTURE_IN_PICTURE, "Open picture in picture", bare = true) {
            setControls(false)
            if (!host.enterPictureInPicture(playerView)) showControls()
        }
        addView(pipButton)
        closeButton = control(PlayerControlIcon.CLOSE, "Close playback", prominent = true, bare = true) {
            host.back()
        }
        addView(closeButton)
    }

    private fun buildController(): LinearLayout = LinearLayout(host.viewContext).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(26), dp(18), dp(26), dp(20))
        background = GradientDrawable(
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
                        controller?.seekTo((end * seekBar.progress) / 10_000L)
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
            playButton = control(PlayerControlIcon.PLAY, "Play", prominent = true) { togglePlay() }
            addView(playButton)
            forwardButton = control(PlayerControlIcon.FORWARD, "Jump forward $seekSeconds seconds") {
                seekBy(configuredSeekMillis())
            }
            addView(forwardButton)
            nextButton = control(PlayerControlIcon.NEXT, "Play next episode") { playNext() }
            addView(nextButton)
        }, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun buildSeekPreview(): LinearLayout = LinearLayout(host.viewContext).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        visibility = View.GONE
        isClickable = false
        background = GradientDrawable().apply {
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
        background = GradientDrawable().apply {
            cornerRadius = Styler.dp(context, 18f)
            setColor(Color.argb(225, 22, 24, 29))
            setStroke(dp(1), Color.argb(110, 255, 255, 255))
        }
    }

    private fun buildNextPanel(): LinearLayout = LinearLayout(host.viewContext).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        background = GradientDrawable().apply {
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
        prominent: Boolean = false,
        bare: Boolean = false,
        action: () -> Unit
    ) =
        PlayerIconButton(host.viewContext, icon).apply {
            contentDescription = description
            background = if (bare) playerBareButtonBackground() else playerButtonBackground(prominent)
            Styler.makeFocusable(this)
            minimumWidth = dp(48)
            minimumHeight = dp(44)
            activateOnTap(action)
            setOnFocusChangeListener { _, focused -> if (focused) showControls() }
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(7) }
        }

    private fun playerBareButtonBackground(): StateListDrawable {
        fun face(fill: Int, strokeWidth: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
            cornerRadius = Styler.dp(host.viewContext, 11f)
            setColor(fill)
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }
        return StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_focused),
                face(Color.TRANSPARENT, dp(2), colors.focusRing)
            )
            addState(intArrayOf(android.R.attr.state_pressed), face(Color.argb(75, 0, 0, 0)))
            addState(intArrayOf(), face(Color.TRANSPARENT))
        }
    }

    private fun playerButtonBackground(prominent: Boolean): StateListDrawable {
        fun face(fill: Int, strokeWidth: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
            cornerRadius = Styler.dp(host.viewContext, 12f)
            setColor(fill)
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }
        val base = if (prominent) colors.accent else Color.argb(205, 36, 38, 46)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), face(Color.argb(255, 24, 25, 31)))
            addState(
                intArrayOf(android.R.attr.state_focused),
                face(if (prominent) colors.accent else Color.argb(245, 50, 53, 64), dp(3), colors.focusRing)
            )
            addState(intArrayOf(), face(base, dp(1), Color.argb(100, 255, 255, 255)))
        }
    }

    private fun updateControlLabels(value: PlaybackPrepareResponse) {
        lastPreviewThumbnail = -1
        titleView.text = if (value.offline) "${value.item.displayTitle()}  ·  Offline" else value.item.displayTitle()
        val audio = value.audioTracks.firstOrNull { it.index == value.selectedAudioIndex }
        val subtitle = value.subtitleTracks.firstOrNull { it.index == value.selectedSubtitleIndex }
        audioButton.contentDescription = audio?.let { "Audio, ${it.label}" } ?: "Choose audio track"
        subtitleButton.contentDescription = subtitle?.let {
            "Subtitles, ${it.label}, ${subtitleOffsetLabel(subtitleOffsetMillis)}"
        } ?: "Subtitles off"
        previousButton.visibility = if (value.previousItem == null) View.GONE else View.VISIBLE
        previousButton.contentDescription = value.previousItem?.let { "Play previous episode, ${it.displayTitle()}" }
            ?: "Previous episode unavailable"
        nextButton.visibility = if (value.nextItem == null) View.GONE else View.VISIBLE
        nextButton.contentDescription = value.nextItem?.let { "Play next episode, ${it.displayTitle()}" }
            ?: "Next episode unavailable"
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
        if (value.bitrate > 0) add("%.1f Mbps".format(value.bitrate / 1_000_000.0))
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
        if (bitrate > 0) add("%.1f Mbps".format(bitrate / 1_000_000.0))
    }.joinToString(" · ")

    private fun time(milliseconds: Long): String {
        val total = milliseconds.coerceAtLeast(0) / 1_000
        val hours = total / 3_600
        val minutes = (total % 3_600) / 60
        val seconds = total % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
            else "%d:%02d".format(minutes, seconds)
    }

    private fun configuredSeekSeconds(): Int = PlaybackSettings.seekSeconds(host.viewContext)

    private fun configuredSeekMillis(): Long = configuredSeekSeconds() * 1_000L

    private fun dp(value: Int) = Styler.dpInt(host.viewContext, value.toFloat())

    private companion object {
        const val SUBTITLE_TICK_MILLIS = 50L
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
