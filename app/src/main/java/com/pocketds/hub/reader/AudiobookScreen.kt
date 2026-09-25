package com.pocketds.hub.reader

import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.ReadingEdition
import com.pocketds.hub.screens.library.ReadingEntryMode
import com.pocketds.hub.screens.library.ReadingEntryPreferences
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.playback.PlayerControlIcon
import com.pocketds.hub.playback.PlayerIconButton
import com.pocketds.hub.ui.ChoiceOverlay
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Theme
import com.pocketds.hub.ui.activateOnTap
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Storyteller audiobook player. Its archive is temporary; narration progress is device-local. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudiobookScreen(
    private val api: HubApi,
    private val workId: String,
    private val edition: ReadingEdition,
    override val title: String,
    private val ringVisible: () -> Boolean,
    private val narrations: List<ReadingEdition>,
    private val ebook: ReadingEdition?,
    private val alignedOptions: List<ReadingEdition>,
    private val onProgressChanged: () -> Unit = {}
) : Screen {
    override val contentDomain = com.pocketds.hub.state.ContentMode.BOOKS
    override val immersive = true
    private lateinit var host: ScreenHost
    private lateinit var root: FrameLayout
    private lateinit var colors: PocketColors
    private lateinit var status: TextView
    private lateinit var position: TextView
    private lateinit var partTitle: TextView
    private lateinit var timeline: SeekBar
    private lateinit var playButton: PlayerIconButton
    private lateinit var overlay: ChoiceOverlay
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loadJob: Job? = null
    private var updateJob: Job? = null
    private var player: ExoPlayer? = null
    private var parts: List<AudiobookPart> = emptyList()
    private var initialized = false
    private val controls = mutableListOf<View>()
    private var focusedControl = 0
    private val aligned: ReadingEdition? get() = alignedOptions.firstOrNull { it.sourceItemId == edition.sourceItemId }

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        colors = Theme.colors(host.viewContext)
        root = FrameLayout(host.viewContext).apply { setBackgroundColor(colors.background) }
        val content = LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(12), dp(22), dp(18))
        }
        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))
        content.addView(TextView(host.viewContext).apply {
            text = title
            textSize = 25f
            setTextColor(colors.primaryText)
            gravity = Gravity.CENTER
            maxLines = 2
        }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        partTitle = TextView(host.viewContext).apply {
            textSize = 14f; setTextColor(colors.mutedText); gravity = Gravity.CENTER
        }
        content.addView(partTitle, LinearLayout.LayoutParams(MATCH, dp(30)))
        status = TextView(host.viewContext).apply {
            text = "Preparing audiobook…"
            textSize = 13f; setTextColor(colors.mutedText); gravity = Gravity.CENTER
        }
        content.addView(status, LinearLayout.LayoutParams(MATCH, dp(36)))
        timeline = SeekBar(host.viewContext).apply {
            max = 1000
            contentDescription = "Audiobook position"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) player?.let { audio ->
                        if (audio.duration > 0) position.text = clock(audio.duration * value / 1000) + " / " + clock(audio.duration)
                    }
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    player?.let { audio -> if (audio.duration > 0) {
                        audio.seekTo(audio.duration * (bar?.progress ?: 0) / 1000)
                        savePosition()
                    } }
                }
            })
        }
        content.addView(timeline, LinearLayout.LayoutParams(MATCH, dp(48)))
        position = TextView(host.viewContext).apply {
            text = "0:00 / 0:00"; textSize = 13f; setTextColor(colors.primaryText); gravity = Gravity.CENTER
        }
        content.addView(position, LinearLayout.LayoutParams(MATCH, dp(26)))
        val transport = LinearLayout(host.viewContext).apply { gravity = Gravity.CENTER }
        content.addView(transport, LinearLayout.LayoutParams(MATCH, dp(58)))
        transport.addView(icon(PlayerControlIcon.PREVIOUS, "Previous part") { previous() })
        transport.addView(icon(PlayerControlIcon.REWIND, "Back 10 seconds") { jump(-10_000) })
        playButton = icon(PlayerControlIcon.PLAY, "Play audiobook") { toggle() }
        transport.addView(playButton)
        transport.addView(icon(PlayerControlIcon.FORWARD, "Forward 10 seconds") { jump(10_000) })
        transport.addView(icon(PlayerControlIcon.NEXT, "Next part") { next() })
        val actions = LinearLayout(host.viewContext).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        content.addView(actions, LinearLayout.LayoutParams(MATCH, dp(58)))
        if (ebook != null || narrations.size > 1) actions.addView(action("Reading & listening") { showReadingModes() })
        actions.addView(action("Close") { host.back() })
        overlay = ChoiceOverlay(host.viewContext, colors, ringVisible, sidePanel = true)
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        return root
    }

    override fun onShow() {
        ReadingEntryPreferences.put(host.viewContext, workId, ReadingEntryMode.LISTEN, edition.sourceItemId)
        if (!initialized && loadJob?.isActive != true) load()
        else startUpdates()
    }

    override fun onHide() {
        player?.pause()
        savePosition()
        updateJob?.cancel()
        loadJob?.cancel()
        if (::overlay.isInitialized) overlay.dismiss()
    }

    override fun onDestroyView() {
        savePosition()
        player?.release(); player = null
        scope.cancel()
        controls.clear()
    }

    override fun onAppBackgrounded() { player?.pause(); savePosition() }
    override fun onSystemBack(): Boolean = if (overlay.isOpen) { overlay.dismiss(); true } else false
    override fun hints(): List<ButtonHint> = listOf(ButtonHint.activate("Choose"), ButtonHint.back("Close audiobook"))
    override fun requestInitialFocus(): Boolean = playButton.requestFocus()

    override fun onPad(action: PadAction): Boolean {
        if (overlay.onPad(action)) return true
        return when (action) {
            PadAction.Back -> { host.back(); true }
            PadAction.Activate -> { controls.getOrNull(focusedControl)?.performClick(); true }
            is PadAction.Step -> {
                val step = if (action.direction == Direction.LEFT || action.direction == Direction.UP) -1 else 1
                focusedControl = (focusedControl + step).coerceIn(0, controls.lastIndex.coerceAtLeast(0))
                controls.getOrNull(focusedControl)?.requestFocus()
                true
            }
            else -> false
        }
    }

    private fun load() {
        loadJob = scope.launch {
            status.text = "Downloading audiobook for temporary playback…"
            val identity = ReadingProgress.get(host.viewContext).session().identity
            val directory = File(host.viewContext.cacheDir, "reading-audio/$identity/${ReadingCheckpointKey.digest(workId + edition.sourceItemId)}")
            directory.mkdirs()
            val archive = File(directory, "book.zip")
            if (!AudiobookArchive.hasPlayableAudio(archive)) {
                val temporary = File(directory, "book.part")
                when (val result = api.downloadReadingAudiobook(workId, edition.sourceItemId, temporary)) {
                    is HubResult.Ok -> {
                        if (!temporary.renameTo(archive)) { status.text = "Could not save audiobook"; return@launch }
                    }
                    is HubResult.Failed -> { status.text = result.message; return@launch }
                }
            }
            status.text = "Preparing audio parts…"
            parts = try { withContext(Dispatchers.IO) {
                AudiobookArchive.extract(archive, File(directory, "parts")) { coroutineContext.ensureActive() }
            } } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                status.text = error.message ?: "Could not open audiobook"
                return@launch
            }
            val context = host.viewContext
            val audio = ExoPlayer.Builder(context.applicationContext).build()
            audio.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            audio.setHandleAudioBecomingNoisy(true)
            audio.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playButton.setIcon(if (isPlaying) PlayerControlIcon.PAUSE else PlayerControlIcon.PLAY)
                    playButton.contentDescription = if (isPlaying) "Pause audiobook" else "Play audiobook"
                    if (!isPlaying) savePosition()
                }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { refresh() ; savePosition() }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) refresh()
                    if (playbackState == Player.STATE_ENDED) savePosition(completed = true)
                }
            })
            audio.setMediaItems(parts.map { MediaItem.fromUri(Uri.fromFile(it.file)) })
            player = audio
            val saved = context.getSharedPreferences("audiobook_positions", 0)
            val key = positionKey(identity)
            val index = saved.getInt("$key:part", 0).coerceIn(0, parts.lastIndex)
            val offset = saved.getLong("$key:ms", 0).coerceAtLeast(0)
            audio.seekTo(index, offset)
            audio.prepare()
            initialized = true
            status.text = ""
            refresh()
            startUpdates()
        }
    }

    private fun startUpdates() {
        updateJob?.cancel()
        updateJob = scope.launch { while (true) { refresh(); delay(500) } }
    }

    private fun refresh() {
        val audio = player ?: return
        val index = audio.currentMediaItemIndex.coerceIn(0, parts.lastIndex.coerceAtLeast(0))
        partTitle.text = "Part ${index + 1} of ${parts.size} · ${parts.getOrNull(index)?.title.orEmpty()}"
        val duration = audio.duration.coerceAtLeast(0)
        position.text = "${clock(audio.currentPosition)} / ${clock(duration)}"
        if (!timeline.isPressed && duration > 0) timeline.progress = (audio.currentPosition * 1000 / duration).toInt().coerceIn(0, 1000)
    }

    private fun toggle() { player?.let { if (it.isPlaying) it.pause() else it.play() } }
    private fun jump(delta: Long) { player?.let { it.seekTo((it.currentPosition + delta).coerceIn(0, it.duration.coerceAtLeast(0))); savePosition() } }
    private fun previous() { player?.let { if (it.currentPosition > 3000) it.seekTo(0) else it.seekToPreviousMediaItem(); savePosition() } }
    private fun next() { player?.let { it.seekToNextMediaItem(); savePosition() } }

    private fun savePosition(completed: Boolean = false) {
        val audio = player ?: return
        if (!initialized) return
        val identity = ReadingProgress.get(host.viewContext).session().identity
        val key = positionKey(identity)
        host.viewContext.getSharedPreferences("audiobook_positions", 0).edit()
            .putInt("$key:part", if (completed) 0 else audio.currentMediaItemIndex.coerceAtLeast(0))
            .putLong("$key:ms", if (completed) 0 else audio.currentPosition.coerceAtLeast(0))
            .apply()
        onProgressChanged()
    }

    private fun positionKey(identity: String) = ReadingCheckpointKey.digest("$identity:$workId:${edition.sourceItemId}")

    private fun openReader(readAlong: Boolean) {
        val target = if (readAlong) aligned else ebook
        if (target == null) return
        savePosition()
        host.back()
        host.push(EpubReaderScreen(api, workId, target.sourceItemId, title, ringVisible,
            onProgressChanged, readAlong = readAlong, readAlongAvailable = aligned != null,
            alignedEditions = alignedOptions, audioEditions = narrations,
            ebookSourceItemId = ebook?.sourceItemId ?: target.sourceItemId))
    }

    private fun showReadingModes() {
        val choices = buildList {
            if (ebook != null) add(ChoiceOverlay.Choice("read", "Read", "Open ebook at your saved place"))
            if (aligned != null) add(ChoiceOverlay.Choice("along", "Read along", "Synchronized text and narration"))
            if (narrations.size > 1) add(ChoiceOverlay.Choice("narration", "Narration", "Choose audiobook edition"))
        }
        overlay.show("Reading & listening", "Switch format for $title", choices) { selected ->
            when (selected) {
                "read" -> openReader(false)
                "along" -> openReader(true)
                "narration" -> chooseNarration()
            }
        }
    }

    private fun chooseNarration() {
        overlay.show("Audiobook edition", "Choose a narration of $title", narrations.mapIndexed { index, option ->
            ChoiceOverlay.Choice(index.toString(), option.narrator.ifBlank { "Audiobook ${index + 1}" },
                option.format.ifBlank { "Audio" }, selected = option.sourceItemId == edition.sourceItemId)
        }) { selected ->
            val next = narrations.getOrNull(selected.toIntOrNull() ?: -1) ?: return@show
            if (next.sourceItemId == edition.sourceItemId) return@show
            savePosition()
            host.back()
            host.push(AudiobookScreen(api, workId, next, title, ringVisible, narrations, ebook,
                alignedOptions, onProgressChanged))
        }
    }

    private fun icon(value: PlayerControlIcon, label: String, click: () -> Unit): PlayerIconButton =
        PlayerIconButton(host.viewContext, value).apply {
            contentDescription = label
            layoutParams = LinearLayout.LayoutParams(dp(55), dp(55))
            Styler.makeFocusable(this)
            FocusDecorator.attach(this, ringVisible, scale = false)
            setOnFocusChangeListener { view, focused ->
                FocusDecorator.refresh(view, focused && ringVisible())
                if (focused) focusedControl = controls.indexOf(view).coerceAtLeast(0)
            }
            activateOnTap(click)
            controls += this
        }

    private fun action(label: String, click: () -> Unit): TextView = TextView(host.viewContext).apply {
        text = label; textSize = 13f; setTextColor(colors.primaryText)
        gravity = Gravity.CENTER; minimumHeight = dp(48)
        setPadding(dp(14), 0, dp(14), 0)
        background = Styler.cardBackground(context, colors, 10f)
        Styler.makeFocusable(this)
        FocusDecorator.attach(this, ringVisible, scale = false)
        setOnFocusChangeListener { view, focused ->
            FocusDecorator.refresh(view, focused && ringVisible())
            if (focused) focusedControl = controls.indexOf(view).coerceAtLeast(0)
        }
        activateOnTap(click)
        controls += this
    }

    private fun dp(value: Int) = Styler.dpInt(host.viewContext, value.toFloat())
    private fun clock(ms: Long): String {
        val seconds = ms.coerceAtLeast(0) / 1000
        return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
        else "%d:%02d".format(seconds / 60, seconds % 60)
    }

    private companion object { const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT }
}
