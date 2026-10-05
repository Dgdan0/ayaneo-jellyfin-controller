package com.pocketds.hub.reader

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.ReadingEdition
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.playback.PlayerControlIcon
import com.pocketds.hub.playback.PlayerIconButton
import com.pocketds.hub.playback.PlayerLabels
import com.pocketds.hub.screens.library.ReadingEntryMode
import com.pocketds.hub.screens.library.ReadingEntryPreferences
import com.pocketds.hub.settings.PlaybackSettings
import com.pocketds.hub.state.Fmt
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Storyteller audiobook player. Its archive is temporary; narration progress
 * is device-local. Its keys are [ReaderPadMap]'s (#16): Ⓑ leaves, Ⓧ plays or
 * pauses, L1 and R1 change part, L2 and R2 jump by the player's seek step;
 * every key stays here. A row along the foot says what the keys do, and Keys
 * lists them all.
 *
 * The book plays on [ReadingAudio]'s service (A1): leaving this screen, or
 * the screen going off, does not stop it, and a mini player in the top bar
 * brings you back. Stop ends it. The listening controls (A2) are the speed,
 * kept per book, a sleep timer that fades and steps back when it fires, the
 * time left in the part and the book, and the parts to jump between.
 */
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
    private lateinit var left: TextView
    private lateinit var partTitle: TextView
    private lateinit var timeline: SeekBar
    private lateinit var playButton: PlayerIconButton
    private lateinit var speedButton: TextView
    private lateinit var sleepButton: TextView
    private lateinit var rewindButton: PlayerIconButton
    private lateinit var forwardButton: PlayerIconButton
    private lateinit var keys: com.pocketds.hub.nav.HintBarView
    private lateinit var overlay: ChoiceOverlay
    private lateinit var comfortLayer: com.pocketds.hub.ui.ComfortLayerView
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loadJob: Job? = null
    private var watchJob: Job? = null
    private var listening = ListeningState()
    private val controls = mutableListOf<View>()
    private var focusedControl = 0
    private val aligned: ReadingEdition? get() = alignedOptions.firstOrNull { it.sourceItemId == edition.sourceItemId }

    /** This screen's book is the one on the player. */
    private val mine: Boolean get() = listening.book?.let { it.workId == workId && it.sourceItemId == edition.sourceItemId } == true

    /** The jump of the transport's ±, L2 and R2: the player's own setting. */
    private val seekSeconds: Int get() = PlaybackSettings.seekSeconds(host.viewContext)

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        colors = Theme.colors(host.viewContext)
        root = FrameLayout(host.viewContext).apply { setBackgroundColor(colors.background) }
        val content = LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(12), dp(22), dp(10))
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
            textSize = 14f; setTextColor(colors.mutedText); gravity = Gravity.CENTER; maxLines = 1
        }
        content.addView(partTitle, LinearLayout.LayoutParams(MATCH, dp(26)))
        status = TextView(host.viewContext).apply {
            text = "Preparing audiobook…"
            textSize = 13f; setTextColor(colors.mutedText); gravity = Gravity.CENTER
        }
        content.addView(status, LinearLayout.LayoutParams(MATCH, dp(24)))
        timeline = SeekBar(host.viewContext).apply {
            max = 1000
            contentDescription = "Audiobook position"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser && listening.partMs > 0) position.text = Fmt.clock(listening.partMs * value / 1000) + " / " + Fmt.clock(listening.partMs)
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    if (mine && listening.partMs > 0) ReadingAudio.seekTo(listening.part, listening.partMs * (bar?.progress ?: 0) / 1000)
                }
            })
        }
        content.addView(timeline, LinearLayout.LayoutParams(MATCH, dp(44)))
        position = TextView(host.viewContext).apply {
            text = "0:00 / 0:00"; textSize = 13f; setTextColor(colors.primaryText); gravity = Gravity.CENTER
        }
        content.addView(position, LinearLayout.LayoutParams(MATCH, dp(22)))
        left = TextView(host.viewContext).apply {
            textSize = 12f; setTextColor(colors.mutedText); gravity = Gravity.CENTER
        }
        content.addView(left, LinearLayout.LayoutParams(MATCH, dp(20)))
        val transport = LinearLayout(host.viewContext).apply { gravity = Gravity.CENTER }
        content.addView(transport, LinearLayout.LayoutParams(MATCH, dp(58)))
        transport.addView(icon(PlayerControlIcon.PREVIOUS, "Previous part") { act { ReadingAudio.part(-1) } })
        rewindButton = icon(PlayerControlIcon.REWIND, "Back") { act { ReadingAudio.seekBy(-seekSeconds * 1_000L) } }
        transport.addView(rewindButton)
        playButton = icon(PlayerControlIcon.PLAY, "Play audiobook") { act { ReadingAudio.toggle() } }
        transport.addView(playButton)
        forwardButton = icon(PlayerControlIcon.FORWARD, "Forward") { act { ReadingAudio.seekBy(seekSeconds * 1_000L) } }
        transport.addView(forwardButton)
        transport.addView(icon(PlayerControlIcon.NEXT, "Next part") { act { ReadingAudio.part(1) } })
        val actions = LinearLayout(host.viewContext).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        content.addView(actions, LinearLayout.LayoutParams(MATCH, dp(54)))
        if (ebook != null || narrations.size > 1) actions.addView(action("Reading & listening") { showReadingModes() })
        actions.addView(action("Parts") { act { showParts() } })
        speedButton = action("Speed 1×") { act { showSpeeds() } }
        actions.addView(speedButton)
        sleepButton = action("Sleep") { act { showSleep() } }
        actions.addView(sleepButton)
        actions.addView(action("Comfort") { showComfort() })
        actions.addView(action("Keys") { showKeys() })
        actions.addView(action("Stop") { stopListening() })
        // What the keys do: the app's own hint bar is hidden while a reader is open.
        keys = ReaderKeys.row(host.viewContext, colors) { onPad(it) }
        root.addView(keys, FrameLayout.LayoutParams(MATCH, Styler.dpInt(host.viewContext, ReaderKeys.ROW_DP.toFloat()),
            Gravity.BOTTOM))
        (content.layoutParams as FrameLayout.LayoutParams).bottomMargin = Styler.dpInt(host.viewContext, ReaderKeys.ROW_DP.toFloat())
        overlay = ChoiceOverlay(host.viewContext, colors, ringVisible, sidePanel = true)
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        // Comfort (#16, X3): the same dim and warmth as every reader, over the whole screen.
        comfortLayer = com.pocketds.hub.ui.ComfortLayerView(host.viewContext)
        root.addView(comfortLayer, FrameLayout.LayoutParams(MATCH, MATCH))
        comfortLayer.apply(com.pocketds.hub.settings.ComfortSettings.load(host.viewContext))
        refreshSeekLabels()
        return root
    }

    private fun padState() = ReaderPadState(ReaderKind.AUDIOBOOK, controlsVisible = true,
        loading = !mine, seekSeconds = seekSeconds)

    private fun showKeys() = ReaderKeys.show(overlay, padState())

    private fun showComfort() = ComfortSheet.show(overlay, colors, ReaderKind.AUDIOBOOK, comfortLayer::apply)

    override fun onShow() {
        comfortLayer.apply(com.pocketds.hub.settings.ComfortSettings.load(host.viewContext))
        ReadingEntryPreferences.put(host.viewContext, workId, ReadingEntryMode.LISTEN, edition.sourceItemId)
        refreshSeekLabels()
        watch()
        val playing = ReadingAudio.state.value.book
        if (playing?.workId == workId && playing.sourceItemId == edition.sourceItemId) return
        if (loadJob?.isActive != true) load()
    }

    override fun onHide() {
        // The book plays on (A1): only this screen stops watching it.
        watchJob?.cancel()
        if (::overlay.isInitialized) overlay.dismiss()
        onProgressChanged()
    }

    override fun onDestroyView() {
        scope.cancel()
        controls.clear()
    }

    override fun onSystemBack(): Boolean = if (overlay.isOpen) { overlay.dismiss(); true } else false
    override fun hints(): List<ButtonHint> = ReaderPadMap.hints(padState())
    override fun requestInitialFocus(): Boolean = playButton.requestFocus()

    override fun onPad(action: PadAction): Boolean {
        // Any button while the sleep timer fades keeps you listening (A2).
        ReadingAudio.touched()
        if (overlay.onPad(action)) return true
        when (val command = ReaderPadMap.command(padState(), action)) {
            ReaderCommand.Leave -> host.back()
            ReaderCommand.Choose -> controls.getOrNull(focusedControl)?.performClick()
            is ReaderCommand.Focus -> {
                val step = if (command.direction == Direction.LEFT || command.direction == Direction.UP) -1 else 1
                focusedControl = (focusedControl + step).coerceIn(0, controls.lastIndex.coerceAtLeast(0))
                controls.getOrNull(focusedControl)?.requestFocus()
            }
            ReaderCommand.PlayPause -> ReadingAudio.toggle()
            is ReaderCommand.Chapter -> ReadingAudio.part(command.delta)
            is ReaderCommand.Seek -> ReadingAudio.seekBy(command.seconds * 1_000L)
            ReaderCommand.Formats -> if (ebook != null || narrations.size > 1) showReadingModes()
            ReaderCommand.Keys -> showKeys()
            ReaderCommand.Retry -> if (loadJob?.isActive != true && !mine) load()
            // Taken here: nothing reaches the app, whose shoulders and triggers would switch tabs.
            else -> Unit
        }
        return true
    }

    /** A control pressed by touch counts as a button too. */
    private inline fun act(block: () -> Unit) {
        ReadingAudio.touched()
        block()
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
            val parts = try { withContext(Dispatchers.IO) {
                AudiobookArchive.extract(archive, File(directory, "parts")) { coroutineContext.ensureActive() }
            } } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                status.text = error.message ?: "Could not open audiobook"
                return@launch
            }
            val again = { AudiobookScreen(api, workId, edition, title, ringVisible, narrations, ebook, alignedOptions) }
            ReadingAudio.open(host.viewContext, ReadingAudioBook(workId, edition.sourceItemId, title, parts,
                ReadingCheckpointKey.digest("$identity:$workId:${edition.sourceItemId}"), again))
            status.text = ""
        }
    }

    /** The player's state, while this screen shows. */
    private fun watch() {
        watchJob?.cancel()
        watchJob = scope.launch { ReadingAudio.state.collect(::render) }
    }

    private fun render(value: ListeningState) {
        val before = mine
        listening = value
        if (!mine) {
            if (before) status.text = "Stopped"
            return
        }
        status.text = ""
        val parts = value.book?.parts.orEmpty()
        partTitle.text = "Part ${value.part + 1} of ${parts.size} · ${parts.getOrNull(value.part)?.title.orEmpty()}"
        position.text = "${Fmt.clock(value.positionMs)} / ${Fmt.clock(value.partMs)}"
        left.text = if (value.partMs > 0) PlayerLabels.timeLeft(value.partLeftMs, value.bookLeftMs) else ""
        if (!timeline.isPressed && value.partMs > 0) timeline.progress = (value.positionMs * 1000 / value.partMs).toInt().coerceIn(0, 1000)
        playButton.setIcon(if (value.playing) PlayerControlIcon.PAUSE else PlayerControlIcon.PLAY)
        playButton.contentDescription = if (value.playing) "Pause audiobook" else "Play audiobook"
        speedButton.text = "Speed ${PlayerLabels.rate(value.speed)}"
        sleepButton.text = PlayerLabels.sleep(value.sleep)
        if (::keys.isInitialized && before != mine) keys.setHints(ReaderPadMap.hints(padState()))
    }

    private fun refreshSeekLabels() {
        val seconds = seekSeconds
        rewindButton.contentDescription = "Back $seconds seconds"
        forwardButton.contentDescription = "Forward $seconds seconds"
        if (::keys.isInitialized) keys.setHints(ReaderPadMap.hints(padState()))
    }

    /** The parts, each with its length once read, the one playing ticked: choose one to jump to. */
    private fun showParts() {
        val book = listening.book ?: return
        val choices = book.parts.mapIndexed { index, part ->
            ChoiceOverlay.Choice(index.toString(), "${index + 1}. ${part.title}",
                listening.partsMs.getOrNull(index)?.let { Fmt.clock(it) }.orEmpty(), selected = index == listening.part)
        }
        overlay.show("Parts", "${book.title} · ${book.parts.size} parts", choices, startIndex = listening.part) { id ->
            id.toIntOrNull()?.let { ReadingAudio.seekTo(it, 0) }
        }
    }

    private fun showSpeeds() {
        if (!mine) return
        overlay.pickValue("Speed", "Kept for this book", Listening.SPEEDS, Listening.SPEEDS.minByOrNull { kotlin.math.abs(it - listening.speed) } ?: 1f,
            label = PlayerLabels::rate) { ReadingAudio.setSpeed(it) }
    }

    /** Off, minutes of listening, or the end of the part; it fades over its last half minute. */
    private fun showSleep() {
        if (!mine) return
        val choices = listOf(ChoiceOverlay.Choice("off", "Off", selected = listening.sleep == null)) +
            SleepChoice.ALL.mapIndexed { index, choice ->
                ChoiceOverlay.Choice(index.toString(), PlayerLabels.sleepChoice(choice), selected = listening.sleep?.choice == choice)
            }
        overlay.show("Sleep timer", "Fades over its last half minute, then steps back so you hear that again", choices) { id ->
            ReadingAudio.setSleep(id.toIntOrNull()?.let(SleepChoice.ALL::getOrNull))
        }
    }

    /** Stop: the book comes off the player, its place kept, and the screen closes. */
    private fun stopListening() {
        if (mine) ReadingAudio.stop()
        host.back()
    }

    private fun openReader(readAlong: Boolean) {
        val target = if (readAlong) aligned else ebook
        if (target == null) return
        // Reading takes over from listening: the book stops, its place kept.
        if (mine) ReadingAudio.stop()
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
            if (mine) ReadingAudio.stop()
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
            FocusDecorator.listen(this, ringVisible) { view, focused ->
                if (focused) focusedControl = controls.indexOf(view).coerceAtLeast(0)
            }
            activateOnTap(click)
            controls += this
        }

    private fun action(label: String, click: () -> Unit): TextView = TextView(host.viewContext).apply {
        text = label; textSize = 13f; setTextColor(colors.primaryText)
        gravity = Gravity.CENTER; minimumHeight = dp(46)
        setPadding(dp(13), 0, dp(13), 0)
        background = Styler.cardBackground(context, colors, 10f)
        Styler.makeFocusable(this)
        FocusDecorator.attach(this, ringVisible, scale = false)
        FocusDecorator.listen(this, ringVisible) { view, focused ->
            if (focused) focusedControl = controls.indexOf(view).coerceAtLeast(0)
        }
        activateOnTap(click)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(3); marginEnd = dp(3)
        }
        controls += this
    }

    private fun dp(value: Int) = Styler.dpInt(host.viewContext, value.toFloat())

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
