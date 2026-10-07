package com.pocketds.hub.reader

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.ReadingAudioManifest
import com.pocketds.hub.model.ReadingAudioTrack
import com.pocketds.hub.model.ReadingEdition
import com.pocketds.hub.model.ReadingWork
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.playback.PlayerControlIcon
import com.pocketds.hub.playback.PlayerIconButton
import com.pocketds.hub.playback.PlayerLabels
import com.pocketds.hub.screens.library.ReadingBookFacts
import com.pocketds.hub.screens.library.ReadingEntryMode
import com.pocketds.hub.screens.library.ReadingEntryPreferences
import com.pocketds.hub.settings.PlaybackSettings
import com.pocketds.hub.state.Fmt
import com.pocketds.hub.ui.AppIcon
import com.pocketds.hub.ui.Artwork
import com.pocketds.hub.ui.ChoiceOverlay
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.OverlayButtons
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Theme
import com.pocketds.hub.ui.ThemeGradientDrawable
import com.pocketds.hub.ui.Type
import com.pocketds.hub.ui.activateOnTap
import com.pocketds.hub.ui.glass.AmbientLayerView
import com.pocketds.hub.ui.glass.GlassColors
import com.pocketds.hub.ui.glass.GlassPage
import com.pocketds.hub.ui.typeRole
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
import kotlin.coroutines.resume

/**
 * Storyteller audiobook player. Its tracks stream from the hub and its place
 * follows you through the hub (#19); a book the hub cannot read is downloaded
 * whole and keeps its place on this device. Its keys are [ReaderPadMap]'s (#16): Ⓑ leaves, Ⓧ plays or
 * pauses, L1 and R1 change part, L2 and R2 jump by the player's seek step;
 * every key stays here. A row along the foot says what the keys do, and Keys
 * lists them all.
 *
 * The book plays on [ReadingAudio]'s service (A1): leaving this screen, or
 * the screen going off, does not stop it, and a mini player in the top bar
 * brings you back. Stop ends it. The listening controls (A2) are the speed,
 * kept per book, a sleep timer that fades and steps back when it fires, the
 * time left in the part and the book, and the parts to jump between. Where the
 * book has chapters (#31) the line under the title, its two times, the timeline,
 * the steps, the time left and the sleep timer's end are the chapter's, which
 * can run on from one track into the next; everything says "chapter" then.
 *
 * It is the book page and the read-along dock together (#11): the cover
 * large beside the eyebrow and the title, on a page tinted by the cover, then
 * the dock's glass player (the line and its times, the white Play between the
 * jumps) and the tools as glass pills.
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
    private val onProgressChanged: () -> Unit = {},
    /** The book, when the caller has it: its cover and its place in a series. Read from the hub otherwise. */
    work: ReadingWork? = null
) : Screen {
    override val contentDomain = com.pocketds.hub.state.ContentMode.BOOKS
    override val immersive = true
    /** The page takes the cover's colours, as the player and the other readers take theirs. */
    override val pageArtwork: String? get() = book?.artwork?.takeIf(String::isNotBlank)
    private var book: ReadingWork? = work
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
    private lateinit var partsButton: TextView
    private lateinit var previousButton: ImageView
    private lateinit var nextButton: ImageView
    /** "chapter" where the book has chapters, else "part": what the words call a step and the sheet. */
    private var noun = "part"
    /** The jumps, "−15" and "+15" written on their discs. */
    private lateinit var rewindButton: TextView
    private lateinit var forwardButton: TextView
    private lateinit var keys: com.pocketds.hub.nav.HintBarView
    private lateinit var overlay: ChoiceOverlay
    private lateinit var comfortLayer: com.pocketds.hub.ui.ComfortLayerView
    // The page, the cover and the words beside it, and what is left of the part.
    private lateinit var ambient: AmbientLayerView
    private lateinit var cover: ImageView
    private lateinit var eyebrow: TextView
    private lateinit var facts: TextView
    private lateinit var remaining: TextView
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loadJob: Job? = null
    private var watchJob: Job? = null
    private var bookJob: Job? = null
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
        val context = host.viewContext
        colors = Theme.colors(context)
        root = FrameLayout(context).apply { setBackgroundColor(colors.background) }
        partTitle = TextView(context).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        status = TextView(context).apply { text = "Preparing audiobook…" }
        timeline = SeekBar(context).apply {
            max = 1000
            contentDescription = "Audiobook position"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                // What the timeline spans is the chapter playing, across tracks, or the part (#31).
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    val span = listening.span
                    if (fromUser && span.durationMs > 0) position.text = Fmt.clock(span.durationMs * value / 1000) + " / " + Fmt.clock(span.durationMs)
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    val span = listening.span
                    if (mine && span.durationMs > 0) ReadingAudio.seekInSpan(span.durationMs * (bar?.progress ?: 0) / 1000)
                }
            })
        }
        position = TextView(context).apply { text = "0:00 / 0:00" }
        left = TextView(context)
        build(context)
        // What the keys do: the app's own hint bar is hidden while a reader is open.
        keys = ReaderKeys.row(context, colors) { onPad(it) }
        root.addView(keys, FrameLayout.LayoutParams(MATCH, Styler.dpInt(context, ReaderKeys.ROW_DP.toFloat()), Gravity.BOTTOM))
        overlay = ChoiceOverlay(context, colors, ringVisible, sidePanel = true)
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        // Comfort (#16, X3): the same dim and warmth as every reader, over the whole screen.
        comfortLayer = com.pocketds.hub.ui.ComfortLayerView(context)
        root.addView(comfortLayer, FrameLayout.LayoutParams(MATCH, MATCH))
        comfortLayer.apply(com.pocketds.hub.settings.ComfortSettings.load(context))
        refreshSeekLabels()
        showBook()
        return root
    }

    /**
     * The cover large beside its words, over the cover's own page (the app
     * hides its page behind a full-screen reader, so this one draws it: the
     * cover small and blurred, in its colours); under them the read-along
     * dock's glass player, its line and times, the parts' steps either side of
     * the jumps and the white Play; then the tools as glass pills.
     */
    private fun build(context: Context) {
        val ring = colors.focusRing
        ambient = AmbientLayerView(context, api).also { page ->
            root.addView(page, FrameLayout.LayoutParams(MATCH, MATCH))
            GlassPage.follow(page) { palette -> page.show(pageArtwork, palette) }
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(GLASS_EDGE_DP), dp(18), dp(GLASS_EDGE_DP), dp(8))
        }
        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH).apply { bottomMargin = dp(ReaderKeys.ROW_DP) })
        // The cover beside the words, as on the book's own page, but large.
        val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        content.addView(head, LinearLayout.LayoutParams(MATCH, 0, 1f))
        cover = SquareCover(context, dp(GLASS_COVER_DP)).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = ThemeGradientDrawable.rounded(Styler.dp(context, GLASS_COVER_CORNER_DP), colors.posterPlaceholder)
            clipToOutline = true
            elevation = Styler.dp(context, 14f)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        head.addView(cover, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, MATCH).apply { marginEnd = dp(26) })
        val words = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        head.addView(words, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        eyebrow = TextView(context).apply {
            typeRole(Type.Role.EYEBROW, 10.5f)
            setTextColor(GlassColors.EYEBROW)
            isAllCaps = true
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }.also(words::addView)
        words.addView(TextView(context).apply {
            text = title
            typeRole(Type.Role.HERO)
            setTextColor(Color.WHITE)
            setLineSpacing(0f, .95f)
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(MATCH, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        facts = TextView(context).apply {
            textSize = 12.5f; setTextColor(GlassColors.FACTS); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }.also { words.addView(it, LinearLayout.LayoutParams(MATCH, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }) }
        partTitle.apply { textSize = 12.5f; setTextColor(GlassColors.FACTS) }
        words.addView(partTitle, LinearLayout.LayoutParams(MATCH, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        // How long is left, in the accent, as a title page's watch line is.
        left.apply { textSize = 12.5f; typeface = Type.text(context, 700); setTextColor(colors.accent); maxLines = 1 }
        words.addView(left, LinearLayout.LayoutParams(MATCH, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        status.apply { textSize = 12f; setTextColor(GlassColors.QUIET) }
        words.addView(status, LinearLayout.LayoutParams(MATCH, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        // The read-along dock's glass player: the line, then the times either side of the transport.
        val dock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(6))
            OverlayButtons.panel(this, 18f)
        }
        content.addView(dock, LinearLayout.LayoutParams(MATCH, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        timeline.apply {
            progressTintList = ColorStateList.valueOf(Color.WHITE)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = ColorStateList.valueOf(GlassColors.TRACK)
            // Room for the thumb at either end, and the times under the line start where it does.
            setPadding(dp(LINE_INSET_DP), 0, dp(LINE_INSET_DP), 0)
        }
        dock.addView(timeline, LinearLayout.LayoutParams(MATCH, dp(26)))
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(LINE_INSET_DP), 0, dp(LINE_INSET_DP), 0)
        }
        dock.addView(row, LinearLayout.LayoutParams(MATCH, dp(58)))
        position.apply { textSize = 13f; typeface = Type.text(context, 700); setTextColor(Color.WHITE) }
        row.addView(position, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        previousButton = register(OverlayButtons.round(context, ring, AppIcon.PREVIOUS_ITEM, "Previous part") { act { ReadingAudio.part(-1) } })
        row.addView(previousButton, LinearLayout.LayoutParams(dp(44), dp(44)))
        rewindButton = register(OverlayButtons.jump(context, ring, "−$seekSeconds", "Back") { act { ReadingAudio.seekBy(-seekSeconds * 1_000L) } })
        row.addView(rewindButton, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(10) })
        playButton = PlayerIconButton(context, PlayerControlIcon.PLAY).apply {
            contentDescription = "Play audiobook"
            setIconColor(OverlayButtons.PLAY_INK, halo = false)
            background = OverlayButtons.playFace(context, ring)
            Styler.makeFocusable(this)
            activateOnTap { act { ReadingAudio.toggle() } }
        }
        row.addView(register(playButton), LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginStart = dp(12); marginEnd = dp(12) })
        forwardButton = register(OverlayButtons.jump(context, ring, "+$seekSeconds", "Forward") { act { ReadingAudio.seekBy(seekSeconds * 1_000L) } })
        row.addView(forwardButton, LinearLayout.LayoutParams(dp(44), dp(44)))
        nextButton = register(OverlayButtons.round(context, ring, AppIcon.NEXT_ITEM, "Next part") { act { ReadingAudio.part(1) } })
        row.addView(nextButton, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(10) })
        remaining = TextView(context).apply {
            textSize = 12f; setTextColor(ReaderBars.SOFT_TEXT); gravity = Gravity.END
        }.also { row.addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
        // The tools, as glass pills in a row under the player.
        val tools = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        content.addView(tools, LinearLayout.LayoutParams(MATCH, dp(44)).apply { topMargin = dp(8) })
        fun pill(label: String, description: String, icon: AppIcon?, click: () -> Unit): TextView =
            register(OverlayButtons.pill(context, ring, label, description, icon, click)).also {
                tools.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginStart = dp(3); marginEnd = dp(3) })
            }
        if (ebook != null || narrations.size > 1) pill("Reading & listening", "Reading & listening", AppIcon.READ_ALONG) { showReadingModes() }
        partsButton = pill("Parts", "Parts", AppIcon.CONTENTS) { act { showParts() } }
        speedButton = pill("Speed 1×", "Speed", AppIcon.SPEED) { act { showSpeeds() } }
        sleepButton = pill("Sleep", "Sleep timer", AppIcon.SLEEP) { act { showSleep() } }
        pill("Comfort", "Comfort", AppIcon.COMFORT) { showComfort() }
        pill("Keys", "Keys", AppIcon.PAD) { showKeys() }
        pill("Stop", "Stop", AppIcon.STOP) { stopListening() }
    }

    /** In the pad's order through the controls, remembering which one had focus. */
    private fun <T : View> register(view: T): T = view.apply {
        FocusDecorator.attach(this, ringVisible, scale = false)
        FocusDecorator.listen(this, ringVisible) { view, focused ->
            if (focused) focusedControl = controls.indexOf(view).coerceAtLeast(0)
        }
        controls += this
    }

    private fun padState() = ReaderPadState(ReaderKind.AUDIOBOOK, controlsVisible = true,
        loading = !mine, seekSeconds = seekSeconds, chapters = noun == "chapter")

    private fun showKeys() = ReaderKeys.show(overlay, padState())

    private fun showComfort() = ComfortSheet.show(overlay, colors, ReaderKind.AUDIOBOOK, comfortLayer::apply)

    override fun onShow() {
        comfortLayer.apply(com.pocketds.hub.settings.ComfortSettings.load(host.viewContext))
        ReadingEntryPreferences.put(host.viewContext, workId, ReadingEntryMode.LISTEN, edition.sourceItemId)
        refreshSeekLabels()
        watch()
        readBook()
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

    /**
     * The book onto the player (#19): streamed from the hub's tracks when it
     * can read them, at the place kept for it (asked about when another device
     * moved it, or when the hub only worked it out from a reader's page);
     * otherwise downloaded whole as before, when the hub cannot read its files
     * (409) or has no such route (404).
     */
    private fun load() {
        loadJob = scope.launch {
            status.text = "Preparing audiobook…"
            when (val manifest = api.readingAudioManifest(workId, edition.sourceItemId)) {
                is HubResult.Ok -> if (AudiobookStream.playable(manifest.value)) stream(manifest.value) else downloadWhole()
                is HubResult.Failed -> if (AudiobookStream.downloadsWhole(manifest)) downloadWhole()
                    else status.text = "${manifest.message} · Select retries"
            }
        }
    }

    private suspend fun stream(manifest: ReadingAudioManifest) {
        val context = host.viewContext
        val progress = ReadingProgress.get(context)
        val session = progress.session()
        val key = session.key(workId, edition.sourceItemId, AudioPlace.KIND)
        withContext(Dispatchers.IO) { retireDevicePlace(context, progress, session, key, manifest.tracks) }
        status.text = "Finding your place…"
        val resume = try { progress.resume(session, key) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { status.text = "Your listening place could not be read. It has been kept · Select retries"; return }
        val chosen = chooseReadingResume(overlay, progress, key, resume) { AudioPlace.label(it, manifest.tracks) }
            ?: run { status.text = "Choose where to listen from · Select retries"; return }
        var place = AudioPlace.of(chosen.location)
        // A place the hub worked out from a reader's page in a book it cannot align is a guess: ask first.
        val answered = progress.lastAudioPosition(key)
        if (place != null && answered != null && !answered.exact && place == AudioPlace.fromServer(answered) &&
            !listenFromEstimate(place, manifest)) place = null
        val (part, offset) = place?.openAt(manifest.tracks) ?: (0 to 0L)
        ReadingAudio.open(context, streamedAudiobook(api, workId, edition.sourceItemId, title, key, manifest, reopen()), part, offset)
        status.text = ""
    }

    /**
     * The place this device kept before the hub kept one (`audiobook_positions`,
     * by part number in the ZIP's order): moved into the outbox once, its part
     * found among the hub's tracks by size where the ZIP is still here, then
     * gone. A checkpoint already there wins and the old place just goes.
     */
    private fun retireDevicePlace(context: Context, progress: ReadingProgress, session: ReadingProgress.Session,
        key: ReadingCheckpointKey, tracks: List<ReadingAudioTrack>) {
        val legacy = ReadingCheckpointKey.digest("${session.identity}:$workId:${edition.sourceItemId}")
        val saved = ReadingAudio.positions(context)
        if (!saved.contains("$legacy:part") && !saved.contains("$legacy:ms")) return
        val part = saved.getInt("$legacy:part", 0)
        val offset = saved.getLong("$legacy:ms", 0)
        if (part > 0 || offset > 0) {
            val sizes = AudiobookArchive.partSizes(File(zipDirectory(session.identity), "book.zip"))
            AudioPlace.legacyTrack(part, sizes, tracks)?.let { AudioPlace.kept(tracks, it, offset) }
                ?.let { progress.store.seed(key, it, System.currentTimeMillis()) }
        }
        saved.edit().remove("$legacy:part").remove("$legacy:ms").apply()
    }

    /** Whether to go to a place the hub only worked out: "Listen from there" first, Ⓑ the same. */
    private suspend fun listenFromEstimate(place: AudioPlace, manifest: ReadingAudioManifest): Boolean =
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            val where = AudioPlace.label(place.location(), manifest.tracks)
            overlay.ask("Listen from where you were reading?",
                "Worked out from your place in the book, so it may be a little off",
                listOf(ChoiceOverlay.Choice("there", "Listen from there", where), ChoiceOverlay.Choice("start", "Start at the beginning")),
                onCancel = { if (continuation.isActive) continuation.resume(true) }) { id ->
                if (continuation.isActive) continuation.resume(id == "there")
            }
        }

    /** The whole book downloaded and taken out of its ZIP: what the hub cannot stream (#19). */
    private suspend fun downloadWhole() {
        status.text = "Downloading audiobook for temporary playback…"
        val identity = ReadingProgress.get(host.viewContext).session().identity
        val directory = zipDirectory(identity)
        directory.mkdirs()
        val archive = File(directory, "book.zip")
        if (!AudiobookArchive.hasPlayableAudio(archive)) {
            val temporary = File(directory, "book.part")
            when (val result = api.downloadReadingAudiobook(workId, edition.sourceItemId, temporary)) {
                is HubResult.Ok -> {
                    if (!temporary.renameTo(archive)) { status.text = "Could not save audiobook"; return }
                }
                is HubResult.Failed -> { status.text = result.message; return }
            }
        }
        status.text = "Preparing audio parts…"
        val parts = try { withContext(Dispatchers.IO) {
            AudiobookArchive.extract(archive, File(directory, "parts")) { coroutineContext.ensureActive() }
        } } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            status.text = error.message ?: "Could not open audiobook"
            return
        }
        ReadingAudio.open(host.viewContext, ReadingAudioBook(workId, edition.sourceItemId, title, parts,
            ReadingCheckpointKey.digest("$identity:$workId:${edition.sourceItemId}"), reopen = reopen()))
        status.text = ""
    }

    private fun zipDirectory(identity: String) =
        File(host.viewContext.cacheDir, "reading-audio/$identity/${ReadingCheckpointKey.digest(workId + edition.sourceItemId)}")

    /** This screen again, for the mini player. */
    private fun reopen(): () -> Screen = { AudiobookScreen(api, workId, edition, title, ringVisible, narrations, ebook, alignedOptions, work = book) }

    /** The screen shows the book's cover and its place in a series: read it once when the caller had no copy. */
    private fun readBook() {
        if (book != null || bookJob?.isActive == true) return
        bookJob = scope.launch {
            val found = (api.readingWork(workId) as? HubResult.Ok)?.value ?: return@launch
            book = found
            showBook()
            host.pageArtworkChanged()
        }
    }

    /** The cover, the page behind it, and the words over and under the title. */
    private fun showBook() {
        val context = host.viewContext
        eyebrow.text = ReadingBookFacts.listeningEyebrow(book)
        facts.text = ReadingBookFacts.listeningLine(book, edition.narrator)
        facts.visibility = if (facts.text.isNullOrBlank()) View.GONE else View.VISIBLE
        val path = pageArtwork
        Artwork.bind(cover, Artwork.loader(api, context), path?.let(api::imageUrl))
        ambient.show(path, GlassPage.palette(context))
    }

    /** The player's state, while this screen shows. */
    private fun watch() {
        watchJob?.cancel()
        watchJob = scope.launch { ReadingAudio.state.collect(::render) }
    }

    private fun render(value: ListeningState) {
        val before = mine
        val nounBefore = noun
        listening = value
        if (!mine) {
            if (before) status.text = "Stopped"
            return
        }
        // Why it stopped, when the stream failed or the book's files changed; nothing while it plays.
        status.text = value.problem
        noun = value.noun
        // The chapter playing, across tracks where the book has chapters (#31), else the part.
        val span = value.span
        partTitle.text = if (noun == "chapter") span.title else {
            val parts = value.book?.parts.orEmpty()
            val contents = value.contents
            val entry = contents.getOrNull(AudiobookContents.current(contents, value.part, value.positionMs))
            "Part ${value.part + 1} of ${parts.size} · ${entry?.title ?: parts.getOrNull(value.part)?.title?.let(AudiobookArchive::partLabel).orEmpty()}"
        }
        position.text = "${Fmt.clock(span.positionMs)} / ${Fmt.clock(span.durationMs)}"
        remaining.text = if (span.durationMs > 0) "−" + Fmt.clock(span.leftMs) else ""
        left.text = if (span.durationMs > 0) PlayerLabels.timeLeft(value.spanLeftMs, value.bookLeftMs, noun) else ""
        if (!timeline.isPressed && span.durationMs > 0) timeline.progress = (span.positionMs * 1000 / span.durationMs).toInt().coerceIn(0, 1000)
        playButton.setIcon(if (value.playing) PlayerControlIcon.PAUSE else PlayerControlIcon.PLAY)
        playButton.contentDescription = if (value.playing) "Pause audiobook" else "Play audiobook"
        speedButton.text = "Speed ${PlayerLabels.rate(value.speed)}"
        sleepButton.text = PlayerLabels.sleep(value.sleep, noun)
        if (nounBefore != noun) nameSteps()
        if (::keys.isInitialized && (before != mine || nounBefore != noun)) keys.setHints(ReaderPadMap.hints(padState()))
    }

    /** What the steps and the sheet are called: "chapter" where the book has chapters (#31), else "part". */
    private fun nameSteps() {
        previousButton.contentDescription = "Previous $noun"
        nextButton.contentDescription = "Next $noun"
        partsButton.text = if (noun == "chapter") "Chapters" else "Parts"
        partsButton.contentDescription = partsButton.text
    }

    private fun refreshSeekLabels() {
        val seconds = seekSeconds
        rewindButton.contentDescription = "Back $seconds seconds"
        forwardButton.contentDescription = "Forward $seconds seconds"
        // The jump is written on its disc.
        rewindButton.text = "−$seconds"
        forwardButton.text = "+$seconds"
        if (::keys.isInitialized) keys.setHints(ReaderPadMap.hints(padState()))
    }

    /**
     * The book's chapters (#19, #31), else the parts, each with its length, the
     * one playing ticked: choose one to jump to. A chapter of the book's own can
     * begin in one track and run on into the next, and is listed by its title.
     */
    private fun showParts() {
        val book = listening.book ?: return
        val entries = listening.contents
        val current = AudiobookContents.current(entries, listening.part, listening.positionMs)
        val choices = entries.mapIndexed { index, entry ->
            ChoiceOverlay.Choice(index.toString(), "${index + 1}. ${entry.title}",
                entry.durationMs?.let { Fmt.clock(it) }.orEmpty(), selected = index == current)
        }
        val noun = listening.noun
        overlay.show(if (noun == "chapter") "Chapters" else "Parts",
            "${book.title} · ${entries.size} ${noun}s", choices, startIndex = current) { id ->
            id.toIntOrNull()?.let(entries::getOrNull)?.let { ReadingAudio.seekTo(it.part, it.startMs) }
        }
    }

    private fun showSpeeds() {
        if (!mine) return
        overlay.pickValue("Speed", "Kept for this book", Listening.SPEEDS, Listening.SPEEDS.minByOrNull { kotlin.math.abs(it - listening.speed) } ?: 1f,
            label = PlayerLabels::rate) { ReadingAudio.setSpeed(it) }
    }

    /** Off, minutes of listening, or the end of the chapter (else the part); it fades over its last half minute. */
    private fun showSleep() {
        if (!mine) return
        val choices = listOf(ChoiceOverlay.Choice("off", "Off", selected = listening.sleep == null)) +
            SleepChoice.ALL.mapIndexed { index, choice ->
                ChoiceOverlay.Choice(index.toString(), PlayerLabels.sleepChoice(choice, listening.noun), selected = listening.sleep?.choice == choice)
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
                alignedOptions, onProgressChanged, book))
        }
    }

    private fun dp(value: Int) = Styler.dpInt(host.viewContext, value.toFloat())

    /**
     * The cover, as tall as the room beside the player allows and never more
     * than [maxPx]: square, as an audiobook's cover is (GLASS_PLAN.md).
     */
    private class SquareCover(context: Context, private val maxPx: Int) : ImageView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val height = MeasureSpec.getSize(heightMeasureSpec)
            val side = if (height > 0) minOf(height, maxPx) else maxPx
            setMeasuredDimension(side, side)
        }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        /** The Pocket's page edge, as the book page's. */
        const val GLASS_EDGE_DP = 28
        /** The cover at its largest: about half the screen's height. */
        const val GLASS_COVER_DP = 236
        const val GLASS_COVER_CORNER_DP = 11f
        /** The line's ends inside the player, the thumb's room. */
        const val LINE_INSET_DP = 10
    }
}

/**
 * A streamed book for the player (#19): its tracks under the manifest's
 * revision, its place's checkpoint, and how it reads its manifest again when
 * the hub says its files changed. Built from values alone, so the player
 * holds no screen.
 */
internal fun streamedAudiobook(api: HubApi, workId: String, sourceItemId: String, title: String, key: ReadingCheckpointKey,
    manifest: ReadingAudioManifest, reopen: () -> Screen): ReadingAudioBook =
    ReadingAudioBook(workId, sourceItemId, title,
        AudiobookStream.parts(manifest, sourceItemId) { api.readingAudioTrackUrl(workId, sourceItemId, it, manifest.revision) },
        positionKey = "", checkpoint = key, tracks = manifest.tracks, chapters = manifest.chapters, reopen = reopen,
        reload = {
            when (val fresh = api.readingAudioManifest(workId, sourceItemId)) {
                is HubResult.Ok -> fresh.value.takeIf(AudiobookStream::playable)?.let {
                    streamedAudiobook(api, workId, sourceItemId, title, key, it, reopen)
                }
                is HubResult.Failed -> null
            }
        })
