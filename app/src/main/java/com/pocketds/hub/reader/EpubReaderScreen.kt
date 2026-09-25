package com.pocketds.hub.reader

import com.pocketds.hub.ui.ThemeGradientDrawable
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.pocketds.hub.debug.DebugLog
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.EpubPositionBody
import com.pocketds.hub.model.ReadingEdition
import com.pocketds.hub.screens.library.ReadingEntryMode
import com.pocketds.hub.screens.library.ReadingEntryPreferences
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Theme
import com.pocketds.hub.ui.AppIcon
import com.pocketds.hub.ui.AppIconDrawable
import com.pocketds.hub.ui.ChoiceOverlay
import com.pocketds.hub.ui.activateOnTap
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import com.pocketds.hub.playback.PlaybackService
import com.pocketds.hub.playback.PlayerControlIcon
import com.pocketds.hub.playback.PlayerIconButton
import org.readium.r2.navigator.Decoration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject
import org.json.JSONArray
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.ColumnCount
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.navigator.preferences.Theme as ReadiumTheme
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

/** Native reflowable EPUB reader. Readium renders the book; this screen owns Pocket input and sync. */
@OptIn(ExperimentalReadiumApi::class)
class EpubReaderScreen(
    private val api: HubApi,
    private val workId: String,
    private val sourceItemId: String,
    override val title: String,
    private val ringVisible: () -> Boolean,
    private val onProgressChanged: () -> Unit = {},
    private val readAlong: Boolean = false,
    private val readAlongAvailable: Boolean = false,
    private val alignedEditions: List<ReadingEdition> = emptyList(),
    private val audioEditions: List<ReadingEdition> = emptyList(),
    private val ebookSourceItemId: String = sourceItemId
) : Screen {
    override val contentDomain = com.pocketds.hub.state.ContentMode.BOOKS
    override val immersive = true
    override val focusOnShow = false

    private lateinit var host: ScreenHost
    private lateinit var root: FrameLayout
    private lateinit var navigatorContainer: FrameLayout
    private lateinit var loading: TextView
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var position: TextView
    private lateinit var overlay: ChoiceOverlay
    private lateinit var appearance: EpubAppearancePanel
    private lateinit var pagePreview: ReaderPagePreviewController
    private lateinit var colors: PocketColors
    private val controls = mutableListOf<View>()
    private var focusedControl = 0
    private var controlsVisible = true

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var progress: ReadingProgress
    private lateinit var readingSession: ReadingProgress.Session
    private lateinit var checkpointKey: ReadingCheckpointKey
    private lateinit var bookmarks: EpubBookmarkStore
    private lateinit var bookmarkButton: TextView
    private var checkpointErrorShown = false
    private var loadJob: Job? = null
    private var locatorJob: Job? = null
    private var navigator: EpubNavigatorFragment? = null
    private var publication: Publication? = null
    private var latestLocator: Locator? = null
    private var preferences = EpubReaderPreferences()
    private lateinit var preferenceState: EpubPreferenceState
    private var pageIndex = 0
    private var pageCount = 0
    private val json = Json { ignoreUnknownKeys = true }
    private var narration: ReadAlongPlayback? = null
    private val narrationCheckpoint = ReadAlongSession()
    private var narrationCompleted = false
    private var highlightJob: Job? = null
    private lateinit var narrationDock: ReadAlongDock
    private lateinit var dictionaryCard: DictionaryCard
    private lateinit var dictionary: OfflineEnglishDictionary
    private val selectionGate = NarrationSelectionGate()
    private var selectionJob: Job? = null
    private var dictionaryJob: Job? = null
    private var dockJob: Job? = null
    private var selectedNarrationTarget: ReadAlongPosition? = null
    private var selectionGeneration = 0
    private var matchNarrationToPage = false

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        progress = ReadingProgress.get(host.viewContext)
        readingSession = progress.session()
        checkpointKey = readingSession.key(workId, sourceItemId, "epub")
        bookmarks = EpubBookmarkStore(File(host.viewContext.filesDir, "reading-bookmarks"))
        colors = Theme.colors(host.viewContext)
        root = FrameLayout(host.viewContext).apply { setBackgroundColor(Color.BLACK) }
        navigatorContainer = object : FrameLayout(host.viewContext) {
            private var longPressCheck: Runnable? = null
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    longPressCheck?.let { removeCallbacks(it) }
                    longPressCheck = Runnable { inspectSelection() }.also {
                        postDelayed(it, ViewConfiguration.getLongPressTimeout().toLong() + 80)
                    }
                }
                val inspect = event.actionMasked == MotionEvent.ACTION_UP &&
                    event.eventTime - event.downTime >= ViewConfiguration.getLongPressTimeout()
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    longPressCheck?.let { removeCallbacks(it) }
                    longPressCheck = null
                }
                val consumed = super.dispatchTouchEvent(event)
                if (inspect) postDelayed({ inspectSelection() }, 120)
                return consumed
            }
        }.apply {
            id = View.generateViewId()
            setBackgroundColor(Color.BLACK)
        }
        root.addView(navigatorContainer, FrameLayout.LayoutParams(MATCH, MATCH))
        loading = TextView(host.viewContext).apply {
            text = "Preparing book…"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(0xDD101116.toInt())
        }
        root.addView(loading, FrameLayout.LayoutParams(MATCH, MATCH))
        buildTopBar()
        buildBottomBar()
        buildNarrationDock()
        preferences = loadPreferences()
        preferenceState = EpubPreferenceState(preferences)
        overlay = ChoiceOverlay(host.viewContext, colors, ringVisible, sidePanel = true)
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        appearance = EpubAppearancePanel(host.viewContext, colors, ringVisible)
        root.addView(appearance, FrameLayout.LayoutParams(MATCH, MATCH))
        dictionary = OfflineEnglishDictionary(host.viewContext)
        dictionaryCard = DictionaryCard(host.viewContext).apply {
            onClose = { closeDictionary(resumeNarration = true) }
            onPlay = { playFromSelection() }
        }
        root.addView(dictionaryCard, FrameLayout.LayoutParams(MATCH, MATCH))
        pagePreview = ReaderPagePreviewController(root, navigatorContainer, topBar, bottomBar, listOf(overlay, appearance),
            extraBottom = {
                if (narrationDock.visibility == View.VISIBLE) narrationDock.height +
                    (narrationDock.layoutParams as FrameLayout.LayoutParams).bottomMargin else 0
            })
        narrationDock.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> pagePreview.refresh() }
        focusedControl = controls.indexOfLast { it.contentDescription == "Next page" }.coerceAtLeast(0)
        setControlsVisible(false)
        return root
    }

    override fun onShow() {
        val previousAudio = ReadingEntryPreferences.get(host.viewContext, workId)?.audioSourceItemId.orEmpty()
        ReadingEntryPreferences.put(host.viewContext, workId,
            if (readAlong) ReadingEntryMode.READ_ALONG else ReadingEntryMode.READ,
            if (readAlong) sourceItemId else previousAudio)
        if ((navigator == null || (readAlong && narration == null)) && loadJob?.isActive != true) openBook()
        else if (narration != null) startDockUpdates()
    }

    override fun onHide() {
        closeDictionary(resumeNarration = false)
        narration?.pause()
        dockJob?.cancel()
        if (::appearance.isInitialized && appearance.isOpen) appearance.cancel()
        if (::overlay.isInitialized) overlay.dismiss()
        saveCurrent(immediate = true)
        loadJob?.cancel()
    }

    override fun onDestroyView() {
        closeDictionary(resumeNarration = false)
        saveCurrent(immediate = true)
        narration?.release()
        narration = null
        pagePreview.dispose()
        selectionJob?.cancel()
        dictionaryJob?.cancel()
        dockJob?.cancel()
        locatorJob?.cancel()
        removeNavigator()
        publication?.close()
        publication = null
        uiScope.cancel()
        controls.clear()
    }

    override fun onAppBackgrounded() { narration?.pause(); saveCurrent(immediate = true) }

    override fun onSystemBack(): Boolean {
        if (::dictionaryCard.isInitialized && dictionaryCard.isOpen) { closeDictionary(resumeNarration = true); return true }
        if (::appearance.isInitialized && appearance.isOpen) { appearance.cancel(); return true }
        if (::overlay.isInitialized && overlay.isOpen) {
            overlay.onPad(PadAction.Back)
            return true
        }
        return false
    }

    override fun hints(): List<ButtonHint> = if (::appearance.isInitialized && appearance.isOpen) {
        listOf(ButtonHint.activate("Adjust"), ButtonHint.back("Close appearance"))
    } else if (::dictionaryCard.isInitialized && dictionaryCard.isOpen) {
        listOf(ButtonHint.activate("Choose"), ButtonHint.back("Close definition"))
    } else if (::overlay.isInitialized && overlay.isOpen) {
        listOf(
            ButtonHint.activate("Choose"),
            ButtonHint.back("Cancel")
        )
    } else {
        listOf(
            ButtonHint.activate(if (controlsVisible) "Choose" else "Next page"),
            ButtonHint.back(if (controlsVisible) "Hide controls" else "Close reader"),
            ButtonHint.primary("Bookmark"),
            ButtonHint.secondary("Navigator")
        )
    }

    override fun onPad(action: PadAction): Boolean {
        if (::dictionaryCard.isInitialized && dictionaryCard.onPad(action)) return true
        if (appearance.onPad(action)) return true
        if (::overlay.isInitialized && overlay.onPad(action)) {
            host.refreshHints()
            return true
        }
        when (action) {
            PadAction.Menu -> setControlsVisible(!controlsVisible)
            PadAction.Back -> if (controlsVisible) setControlsVisible(false) else host.back()
            PadAction.Activate -> if (controlsVisible) controls.getOrNull(focusedControl)?.performClick() else turn(1)
            PadAction.Primary -> toggleBookmark()
            PadAction.Secondary -> {
                setControlsVisible(true)
                showNavigator()
            }
            PadAction.Refresh -> if (navigator == null) openBook() else cycleTheme()
            is PadAction.Section -> turn(action.delta)
            is PadAction.Page -> changeChapter(action.direction)
            is PadAction.Step -> if (controlsVisible) moveControlFocus(action.direction) else when (action.direction) {
                Direction.LEFT -> turn(-1)
                Direction.RIGHT -> turn(1)
                Direction.UP, Direction.DOWN -> setControlsVisible(true)
            }
        }
        return true
    }

    private fun openBook(forceDownload: Boolean = false) {
        loadJob?.cancel()
        loading.visibility = View.VISIBLE
        loading.text = "Preparing book…"
        loadJob = uiScope.launch {
            val cache = EpubPackageCache(File(host.viewContext.cacheDir, "reading-epub/${readingSession.identity}" + if (readAlong) "/aligned" else ""))
            if (forceDownload) {
                cache.completeFile(workId, sourceItemId).delete()
                cache.clearDownload(workId, sourceItemId)
            }
            val file = if (cache.isComplete(workId, sourceItemId)) {
                cache.completeFile(workId, sourceItemId)
            } else {
                val temporary = cache.temporaryFile(workId, sourceItemId)
                loading.text = if (readAlong) "Downloading aligned book and narration…" else "Downloading book…"
                when (val result = readingSession.api.downloadReadingEpub(workId, sourceItemId, temporary, readAlong)) {
                    is HubResult.Ok -> runCatching { cache.promote(workId, sourceItemId) }.getOrElse {
                        showFailure("The downloaded EPUB is incomplete")
                        return@launch
                    }
                    is HubResult.Failed -> {
                        showFailure(result.message)
                        return@launch
                    }
                }
            }
            loading.text = "Opening book…"
            val resume = try { progress.resume(readingSession, checkpointKey) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { showFailure("The saved reading position could not be read. It has been preserved."); return@launch }
            val completion = ReadingCompletionRepository.get(host.viewContext)
            val choice = if (completion.shouldStartAtBeginning(workId)) completion.ebookResume(workId, resume)
                else chooseReadingResume(overlay, progress, checkpointKey, resume)
                ?: run { showFailure("Choose a reading position to continue"); return@launch }
            val saved = choice.location?.locator?.let { Locator.fromJSON(JSONObject(it.toString())) }
            if (readAlong) narrationCheckpoint.beginOpen()
            try {
                runCatching { attachNavigator(file, saved) }
                    .onFailure { showFailure("This EPUB could not be opened") }
                if (navigator != null && readAlong) {
                    try { prepareNarration(file, saved) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) {
                        DebugLog.log("reader", "aligned narration setup failed: ${e.javaClass.simpleName}")
                        host.notify("Aligned narration could not be opened. You can still read this book.")
                        loading.visibility = View.GONE
                    }
                }
            } finally {
                if (readAlong) narrationCheckpoint.endOpenIfPending()
            }
        }
    }

    private suspend fun attachNavigator(file: File, initialLocator: Locator?) {
        removeNavigator()
        publication?.close()
        val context = host.viewContext
        val http = DefaultHttpClient()
        val retriever = AssetRetriever(context.contentResolver, http)
        val parser = DefaultPublicationParser(
            context, assetRetriever = retriever, httpClient = http, pdfFactory = null
        )
        val opener = PublicationOpener(publicationParser = parser)
        val asset = retriever.retrieve(file).getOrElse { error(it.toString()) }
        val opened = opener.open(asset, allowUserInteraction = false).getOrElse { error(it.toString()) }
        publication = opened

        val factory = EpubNavigatorFactory(opened).createFragmentFactory(
            initialLocator = initialLocator,
            initialPreferences = readiumPreferences(preferences),
            paginationListener = object : EpubNavigatorFragment.PaginationListener {
                override fun onPageChanged(pageIndex: Int, totalPages: Int, locator: Locator) {
                    this@EpubReaderScreen.pageIndex = pageIndex
                    this@EpubReaderScreen.pageCount = totalPages
                    latestLocator = locator
                    updatePosition()
                    scheduleSave()
                }
            }
        )
        val activity = context as? AppCompatActivity ?: error("EPUB reader requires a fragment host")
        val fragment = factory.instantiate(activity.classLoader, EpubNavigatorFragment::class.java.name)
            as EpubNavigatorFragment
        activity.supportFragmentManager.beginTransaction()
            .add(navigatorContainer.id, fragment, fragmentTag())
            .commitNowAllowingStateLoss()
        navigator = fragment
        fragment.addInputListener(object : org.readium.r2.navigator.input.InputListener {
            override fun onDrag(event: org.readium.r2.navigator.input.DragEvent): Boolean {
                if (event.type == org.readium.r2.navigator.input.DragEvent.Type.End && !matchNarrationToPage)
                    inspectSelection(onNoSelection = { switchToReading() })
                return false
            }
            override fun onTap(event: org.readium.r2.navigator.input.TapEvent): Boolean {
                val horizontal = event.point.x / navigatorContainer.width.coerceAtLeast(1)
                if (!EpubChromePolicy.handlesTap(horizontal, controlsVisible)) { switchToReading(); return false }
                setControlsVisible(!controlsVisible)
                return true
            }
        })
        latestLocator = fragment.currentLocator.value
        refreshBookmarkButton()
        locatorJob = uiScope.launch {
            fragment.currentLocator.drop(1).collect { locator ->
                latestLocator = locator
                refreshBookmarkButton()
                scheduleSave()
            }
        }
        loading.visibility = View.GONE
        updatePosition()
        root.post { if (controlsVisible) controls.getOrNull(focusedControl)?.requestFocus() }
    }

    private fun removeNavigator() {
        val activity = if (::host.isInitialized) host.viewContext as? AppCompatActivity else null
        val fragment = navigator ?: activity?.supportFragmentManager?.findFragmentByTag(fragmentTag())
        if (fragment != null && activity != null) {
            activity.supportFragmentManager.beginTransaction().remove(fragment).commitNowAllowingStateLoss()
        }
        navigator = null
    }

    private fun turn(delta: Int) {
        switchToReading()
        val didMove = if (delta >= 0) navigator?.goForward(animated = true) else navigator?.goBackward(animated = true)
        if (didMove == false) host.notify(if (delta >= 0) "End of book" else "Start of book")
    }

    private fun changeChapter(direction: Direction) {
        switchToReading()
        repeat(4) { if (direction == Direction.UP) navigator?.goBackward() else navigator?.goForward() }
    }

    private fun scheduleSave() = saveCurrent(immediate = false)

    private fun saveCurrent(immediate: Boolean) {
        if (readAlong && !narrationCheckpoint.canSavePage(narration != null)) return
        val locator = latestLocator ?: return
        try {
            val document = locator.toJSON()
            document.optJSONObject("locations")?.remove("pocketdsAudio")
            var raw = json.parseToJsonElement(document.toString()).jsonObject
            val audioPosition = narrationCheckpoint.pointForSave(narration?.takeIf { it.isPlaying }?.position)
            audioPosition?.let { audio -> narration?.timeline?.let { timeline ->
                raw = ReadAlongLocation.save(raw, timeline, audio, narrationCompleted)
            } }
            progress.save(checkpointKey, ReadingLocation(locator = raw))
            if (immediate) progress.requestSync(immediate = true)
            onProgressChanged()
        } catch (_: Exception) {
            if (!checkpointErrorShown) host.notify("Reading position could not be saved on this device")
            checkpointErrorShown = true
        }
    }

    private fun buildTopBar() {
        topBar = LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(5), dp(8), dp(5))
            setBackgroundColor(0xD9141518.toInt())
        }
        root.addView(topBar, FrameLayout.LayoutParams(MATCH, dp(58), Gravity.TOP))
        topBar.addView(control("×", "Close reader", click = { host.back() }))
        topBar.addView(TextView(host.viewContext).apply {
            text = title
            textSize = 15f
            setTextColor(Color.WHITE)
            maxLines = 1
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }, LinearLayout.LayoutParams(0, MATCH, 1f))
        topBar.addView(control("☷", "Table of contents", click = {
            showNavigator()
        }))
        if (audioEditions.isNotEmpty() || alignedEditions.isNotEmpty() || readAlong) {
            topBar.addView(PlayerIconButton(host.viewContext, PlayerControlIcon.AUDIO).apply {
                contentDescription = "Reading and listening"
                layoutParams = LinearLayout.LayoutParams(dp(56), MATCH)
                Styler.makeFocusable(this)
                FocusDecorator.attach(this, ringVisible, scale = false)
                setOnFocusChangeListener { view, focused ->
                    FocusDecorator.refresh(view, focused && ringVisible())
                    if (focused) focusedControl = controls.indexOf(view).coerceAtLeast(0)
                }
                activateOnTap { showReadingModes() }
                controls += this
            })
        }
        bookmarkButton = control("☆", "Add bookmark", click = { toggleBookmark() })
        topBar.addView(bookmarkButton)
        topBar.addView(control("Aa", "Reading appearance", { showAppearance() }))
    }

    private fun buildBottomBar() {
        bottomBar = LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(5), dp(10), dp(5))
            setBackgroundColor(0xD9141518.toInt())
        }
        root.addView(bottomBar, FrameLayout.LayoutParams(MATCH, dp(58), Gravity.BOTTOM))
        bottomBar.addView(control("‹", "Previous page", { turn(-1) }))
        position = TextView(host.viewContext).apply {
            text = "Opening…"
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        bottomBar.addView(position, LinearLayout.LayoutParams(0, MATCH, 1f))
        bottomBar.addView(control("›", "Next page", { turn(1) }))
    }

    private fun buildNarrationDock() {
        narrationDock = ReadAlongDock(host.viewContext).apply {
            onBack = { narration?.jump(-10_000) }
            onPlay = {
                if (PlaybackService.currentPlan() != null) PlaybackService.pause(host.viewContext)
                if (matchNarrationToPage) seekNarrationToPage(play = true) else narration?.toggle()
            }
            onForward = { narration?.jump(10_000) }
            onSpeed = {
                narration?.let { audio ->
                    val speeds = listOf(.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
                    audio.speed = speeds[(speeds.indexOf(audio.speed).coerceAtLeast(0) + 1) % speeds.size]
                    updateDock()
                }
            }
            onFollow = { narration?.let { highlightNarration(it.timeline.active(it.position.track, it.position.offsetMs)) } }
        }
        root.addView(narrationDock, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(12)
        })
        narrationDock.focusableControls.forEach { view ->
            FocusDecorator.attach(view, ringVisible, scale = false)
            view.setOnFocusChangeListener { focusedView, focused ->
                FocusDecorator.refresh(focusedView, focused && ringVisible())
                if (focused) focusedControl = controls.indexOf(focusedView).coerceAtLeast(0)
            }
            controls += view
        }
    }

    private fun control(glyph: String, label: String, click: () -> Unit): TextView =
        TextView(host.viewContext).apply {
            val icon = when (glyph) { "×" -> AppIcon.CLOSE; "☷" -> AppIcon.CONTENTS; "☆" -> AppIcon.BOOKMARK; "Aa" -> AppIcon.APPEARANCE; "‹" -> AppIcon.PREVIOUS; "▣" -> AppIcon.BOOK; else -> AppIcon.NEXT }
            setCompoundDrawables(AppIconDrawable(icon, Color.WHITE).apply { setBounds(0,0,dp(22),dp(22)) },null,null,null)
            setPadding(dp(17),0,dp(17),0)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = label
            background = controlBackground()
            Styler.makeFocusable(this)
            FocusDecorator.attach(this, ringVisible, scale = false)
            setOnFocusChangeListener { view, focused ->
                FocusDecorator.refresh(view, focused && ringVisible())
                if (focused) focusedControl = controls.indexOf(view).coerceAtLeast(0)
            }
            activateOnTap(click)
            layoutParams = LinearLayout.LayoutParams(dp(56), MATCH)
            controls += this
        }

    private fun cycleTheme() {
        preferences = EpubPreferenceAdjuster.nextTheme(preferences)
        applyPreferences()
        host.notify("Reading theme: ${preferences.theme.name.lowercase()}")
    }

    private suspend fun prepareNarration(file: File, saved: Locator?) {
        loading.visibility = View.VISIBLE
        loading.text = "Preparing synchronized narration…"
        val (timeline, files) = withContext(Dispatchers.IO) {
            val context = coroutineContext
            val timeline = ReadAlongPackage.read(file)
            val folder = File(file.parentFile, file.nameWithoutExtension + "-audio")
            timeline to ReadAlongPackage.extractAudio(file, timeline, folder) { context.ensureActive() }
        }
        val resume = saved?.let { ReadAlongLocation.resume(locatorJson(it), timeline) }
        matchNarrationToPage = saved != null && resume == null
        narration?.release()
        narration = ReadAlongPlayback(host.viewContext, timeline, files, resume,
            onSegment = ::highlightNarration,
            onState = { playing ->
                if (playing) narrationCompleted = false
                updateDock()
            },
            onSave = { point, completed -> narrationCheckpoint.record(point); narrationCompleted = completed; saveCurrent(immediate = true) },
            onError = { host.notify("Narration playback failed. Your position is saved; reading is still available.") }
        )
        narrationCheckpoint.ready(resume)
        narrationDock.visibility = if (controlsVisible) View.VISIBLE else View.GONE
        pagePreview.refresh()
        startDockUpdates()
        DebugLog.log("reader", "aligned narration ready: ${timeline.tracks.size} tracks, resumed=${resume != null}")
        loading.visibility = View.GONE
        setControlsVisible(true)
    }

    private fun highlightNarration(segment: ReadAlongSegment?) {
        highlightJob?.cancel()
        highlightJob = uiScope.launch {
            val reader = navigator ?: return@launch
            if (segment == null) { reader.applyDecorations(emptyList(), "readalong"); return@launch }
            val locator = Locator.fromJSON(JSONObject().put("href", segment.textHref).put("type", "application/xhtml+xml")
                .put("locations", JSONObject().put("fragments", org.json.JSONArray().put(segment.fragment)))) ?: return@launch
            val visible = reader.evaluateJavascript("(function(){var e=document.getElementById(${JSONObject.quote(segment.fragment)});if(!e)return false;var r=e.getBoundingClientRect();return r.bottom>0&&r.top<innerHeight&&r.right>0&&r.left<innerWidth;})()") == "true"
            if (reader.currentLocator.value.href.toString() != segment.textHref || !visible) reader.go(locator, animated = false)
            reader.applyDecorations(listOf(Decoration("narration", locator, Decoration.Style.Highlight(0xFFFFC857.toInt(), isActive = true))), "readalong")
        }
    }

    private fun startDockUpdates() {
        dockJob?.cancel()
        dockJob = uiScope.launch {
            while (true) {
                updateDock()
                delay(500)
            }
        }
    }

    private fun updateDock() {
        if (!::narrationDock.isInitialized) return
        val audio = narration ?: return
        narrationDock.update(audio.isPlaying, audio.position, audio.timeline, audio.speed)
    }

    private fun inspectSelection(onNoSelection: (() -> Unit)? = null) {
        selectionJob?.cancel()
        selectionJob = uiScope.launch {
            delay(160)
            val reader = navigator ?: return@launch
            val selection = runCatching { reader.currentSelection() }.getOrNull()
            val word = selection?.locator?.text?.highlight?.trim().orEmpty()
            if (selection == null || word.isBlank() || word.length > 80) {
                if (!dictionaryCard.isOpen) onNoSelection?.invoke()
                return@launch
            }
            if (dictionaryCard.isOpen) return@launch
            val audio = narration
            if (selectionGate.begin(audio?.isPlaying == true)) audio?.pause(settle = false)
            val ancestors = readSelectionAncestors(reader)
            selectedNarrationTarget = audio?.timeline?.let {
                ReadAlongSelectionTarget.find(it, selection.locator.href.toString(), ancestors)
            }
            dictionaryCard.showLoading(word, RectF(selection.rect), audio != null)
            val generation = ++selectionGeneration
            dictionaryJob?.cancel()
            dictionaryJob = uiScope.launch {
                val entry = runCatching { dictionary.lookup(word) }.getOrNull()
                if (generation == selectionGeneration && dictionaryCard.isOpen) {
                    if (entry == null) dictionaryCard.showFailure("Offline dictionary could not be opened")
                    else dictionaryCard.show(entry)
                }
            }
            host.refreshHints()
        }
    }

    private suspend fun readSelectionAncestors(reader: EpubNavigatorFragment): List<String> {
        val script = """(function(){var s=window.getSelection();if(!s||!s.anchorNode)return '[]';
            var e=s.anchorNode.nodeType===1?s.anchorNode:s.anchorNode.parentElement;
            var ids=[];while(e&&ids.length<24){if(e.id)ids.push(e.id);e=e.parentElement;}
            return JSON.stringify(ids);})()"""
        val raw = runCatching { reader.evaluateJavascript(script) }.getOrNull() ?: "[]"
        return runCatching {
            val decoded = if (raw.startsWith('"')) JSONArray("[$raw]").getString(0) else raw
            val values = JSONArray(decoded)
            (0 until values.length()).map { values.getString(it) }
        }.getOrDefault(emptyList())
    }

    private fun closeDictionary(resumeNarration: Boolean) {
        if (!::dictionaryCard.isInitialized) return
        if (!dictionaryCard.isOpen) { selectionGate.cancel(); return }
        selectionGeneration++
        dictionaryJob?.cancel()
        dictionaryCard.dismiss()
        navigator?.clearSelection()
        selectedNarrationTarget = null
        val resume = if (resumeNarration) selectionGate.dismiss() else { selectionGate.cancel(); false }
        if (resume && narration?.isPlaying == false) narration?.toggle()
        host.refreshHints()
    }

    private fun playFromSelection() {
        val audio = narration ?: return closeDictionary(resumeNarration = false)
        val target = selectedNarrationTarget
        selectionGate.playFromSelection()
        closeDictionary(resumeNarration = false)
        if (target == null) {
            host.notify("No exact alignment here; playing from the first sentence on this page")
            seekNarrationToPage(play = true)
            return
        }
        matchNarrationToPage = false
        narrationCompleted = false
        audio.seek(target)
        narrationCheckpoint.record(target)
        highlightNarration(audio.timeline.active(target.track, target.offsetMs))
        saveCurrent(immediate = true)
        if (!audio.isPlaying) audio.toggle()
    }

    private fun showNarrationOptions() {
        val audio = narration ?: return host.notify("Narration is still preparing")
        overlay.show("Narration", "Recorded audiobook · synchronized text", listOf(
            ChoiceOverlay.Choice("here", "Listen from this page", "Moves the narration to the first visible aligned sentence"),
            ChoiceOverlay.Choice("follow", "Return to narration", "Show the sentence currently being read")
        ) + listOf(.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).map { ChoiceOverlay.Choice("speed:$it", "${it}× speed", if (audio.speed == it) "Selected" else "") }) { id ->
            when {
                id.startsWith("speed:") -> audio.speed = id.removePrefix("speed:").toFloat()
                id == "follow" -> highlightNarration(audio.timeline.active(audio.position.track, audio.position.offsetMs))
                id == "here" -> seekNarrationToPage(play = false)
            }
        }
    }

    private fun seekNarrationToPage(play: Boolean) {
        uiScope.launch {
            val audio = narration ?: return@launch
            val reader = navigator ?: return@launch
            val href = reader.currentLocator.value.href.toString()
            val ids = audio.timeline.tracks.flatMap { it.segments }.filter { it.textHref == href }.map { it.fragment }.distinct()
            val result = reader.evaluateJavascript("(function(){var ids=${org.json.JSONArray(ids)};for(var i=0;i<ids.length;i++){var e=document.getElementById(ids[i]);if(e){var r=e.getBoundingClientRect();if(r.bottom>0&&r.top<innerHeight&&r.right>0&&r.left<innerWidth)return ids[i];}}return null;})()")
            val fragment = runCatching { org.json.JSONArray("[$result]").getString(0) }.getOrNull()
            val target = fragment?.let { audio.timeline.find(href, it) }
            if (target == null) host.notify("No aligned sentence on this page. Turn to a narrated page and try again.")
            else {
                matchNarrationToPage = false
                narrationCompleted = false
                audio.seek(target)
                highlightNarration(audio.timeline.active(target.track, target.offsetMs))
                narrationCheckpoint.record(target)
                saveCurrent(immediate = true)
                if (play && !audio.isPlaying) audio.toggle()
            }
        }
    }

    private fun switchToReading() {
        closeDictionary(resumeNarration = false)
        if (narration == null) return
        narration?.pause(settle = false)
        narrationCheckpoint.switchToText()
        narrationCompleted = false
        matchNarrationToPage = true
        highlightNarration(null)
    }

    private fun switchReaderMode(aligned: Boolean) {
        if (aligned == readAlong) return
        if (aligned && alignedEditions.size > 1) return showNarrationChooser()
        val target = if (aligned) alignedEditions.firstOrNull()?.sourceItemId ?: sourceItemId else ebookSourceItemId
        replaceReaderMode(aligned, target)
    }

    private fun showReadingModes() {
        val choices = buildList {
            if (readAlong) add(ChoiceOverlay.Choice("read", "Read", "Ebook without narration"))
            if (audioEditions.isNotEmpty()) add(ChoiceOverlay.Choice("listen", "Listen", "Open audiobook player"))
            if (alignedEditions.isNotEmpty() && !readAlong) add(ChoiceOverlay.Choice("along", "Read along", "Synchronized text and audio"))
            if (readAlong && alignedEditions.size > 1) add(ChoiceOverlay.Choice("narration", "Narration", "Choose synchronized audiobook"))
        }
        if (choices.isEmpty()) return
        overlay.show("Reading & listening", "Switch format for $title", choices) { selected ->
            when (selected) {
                "read" -> switchReaderMode(false)
                "along" -> switchReaderMode(true)
                "narration" -> showNarrationChooser()
                "listen" -> showListeningEditions()
            }
        }
    }

    private fun showListeningEditions() {
        if (audioEditions.size == 1) return openAudioEdition(audioEditions.first())
        overlay.show("Audiobook editions", "Choose a narration", audioEditions.mapIndexed { index, edition ->
            ChoiceOverlay.Choice(index.toString(), edition.narrator.ifBlank { "Audio edition ${index + 1}" },
                edition.format.ifBlank { "Audio" }.uppercase())
        }) { selected -> audioEditions.getOrNull(selected.toIntOrNull() ?: -1)?.let(::openAudioEdition) }
    }

    private fun openAudioEdition(edition: ReadingEdition) {
        saveCurrent(immediate = true)
        narration?.pause()
        host.back()
        host.push(AudiobookScreen(api, workId, edition, title, ringVisible, audioEditions,
            ReadingEdition(source = edition.source, kind = "ebook", sourceItemId = ebookSourceItemId),
            alignedEditions, onProgressChanged))
    }

    private fun showNarrationChooser() {
        if (alignedEditions.isEmpty()) return
        overlay.show("Audiobook narration", "Choose the synchronized edition", alignedEditions.mapIndexed { index, edition ->
            ChoiceOverlay.Choice(index.toString(), edition.narrator.ifBlank { "Narration ${index + 1}" },
                selected = edition.sourceItemId == sourceItemId)
        }) { selected ->
            alignedEditions.getOrNull(selected.toIntOrNull() ?: -1)?.let { choice ->
                if (!readAlong || choice.sourceItemId != sourceItemId) replaceReaderMode(true, choice.sourceItemId)
            }
        }
    }

    private fun replaceReaderMode(aligned: Boolean, targetSourceItemId: String) {
        saveCurrent(immediate = true)
        narration?.pause()
        host.back()
        host.push(EpubReaderScreen(api, workId, targetSourceItemId, title, ringVisible,
            onProgressChanged, readAlong = aligned, readAlongAvailable = readAlongAvailable,
            alignedEditions = alignedEditions, audioEditions = audioEditions, ebookSourceItemId = ebookSourceItemId))
    }

    private fun locatorJson(locator: Locator): kotlinx.serialization.json.JsonObject =
        json.parseToJsonElement(locator.toJSON().toString()).jsonObject

    private fun refreshBookmarkButton() {
        if (!::bookmarkButton.isInitialized || !::bookmarks.isInitialized) return
        val saved = runCatching { latestLocator?.let { bookmarks.contains(checkpointKey, locatorJson(it)) } == true }
            .getOrDefault(false)
        bookmarkButton.contentDescription = if (saved) "Remove bookmark" else "Add bookmark"
        bookmarkButton.setCompoundDrawables(AppIconDrawable(if (saved) AppIcon.BOOKMARK_FILLED else AppIcon.BOOKMARK, Color.WHITE).apply { setBounds(0,0,dp(22),dp(22)) },null,null,null)
    }

    private fun toggleBookmark() {
        val locator = latestLocator ?: return host.notify("The book is still opening")
        try {
            val added = bookmarks.toggle(checkpointKey, locatorJson(locator))
            refreshBookmarkButton()
            host.notify(if (added) "Bookmark added" else "Bookmark removed")
        } catch (_: Exception) { host.notify("Bookmark could not be saved on this device") }
    }

    private fun showNavigator() {
        showTableOfContents()
    }

    private fun showBookmarks() {
        val entries = try { bookmarks.list(checkpointKey) }
            catch (_: Exception) { return host.notify("Bookmarks could not be read on this device") }
        overlay.resetBody()
        overlay.open("Navigator", "${entries.size} saved locations", onDismiss = { host.refreshHints() })
        overlay.tabs(listOf("contents" to "Contents", "bookmarks" to "Bookmarks"), "bookmarks") {
            if (it == "contents") showTableOfContents() else showBookmarks()
        }
        if (entries.isEmpty()) overlay.choice("No bookmarks in this book") { Unit }
        entries.forEach { entry ->
            overlay.choice(entry.label, "Choose to jump or delete") {
            overlay.show("Bookmark", entry.label, listOf(
                ChoiceOverlay.Choice("jump", "Go to bookmark"),
                ChoiceOverlay.Choice("delete", "Delete bookmark", danger = true)
            ), onCancel = { showBookmarks() }) { action ->
                if (action == "delete") {
                    try {
                        bookmarks.remove(checkpointKey, entry.anchor)
                        refreshBookmarkButton()
                        showBookmarks()
                    } catch (_: Exception) { host.notify("Bookmark could not be deleted") }
                } else {
                    val locator = runCatching { Locator.fromJSON(JSONObject(entry.locator.toString())) }.getOrNull()
                    switchToReading()
                    if (locator == null || navigator?.go(locator, animated = false) == false)
                        host.notify("This bookmark could not be opened")
                    else overlay.dismiss()
                }
                host.refreshHints()
            }
            host.refreshHints()
            }
        }
        overlay.focusBody()
        host.refreshHints()
    }

    private fun showTableOfContents() {
        val book = publication ?: return host.notify("The book is still opening")
        val links = flattenLinks(book.tableOfContents.ifEmpty { book.readingOrder })
        val currentHref = latestLocator?.href?.toString()
        overlay.resetBody()
        overlay.open("Navigator", "${links.size} sections", onDismiss = { host.refreshHints() })
        overlay.tabs(listOf("contents" to "Contents", "bookmarks" to "Bookmarks"), "contents") {
            if (it == "bookmarks") showBookmarks() else showTableOfContents()
        }
        var currentRow: View? = null
        if (links.isEmpty()) overlay.choice("This book has no table of contents") { Unit }
        links.forEachIndexed { index, (depth, link) ->
            val active = link.href.toString() == currentHref
            val row = overlay.choice("  ".repeat(depth) + (link.title ?: "Section ${index + 1}"),
                if (active) "Current section" else "", selected = active) {
                switchToReading()
                book.locatorFromLink(link)?.let { navigator?.go(it, animated = false) }
                overlay.dismiss()
                host.refreshHints()
            }
            if (active) currentRow = row
        }
        overlay.focusBody(currentRow)
        host.refreshHints()
    }

    private fun flattenLinks(links: List<Link>, depth: Int = 0): List<Pair<Int, Link>> = buildList {
        links.forEach { link ->
            add(depth to link)
            addAll(flattenLinks(link.children, depth + 1))
        }
    }

    private fun showAppearance() {
        appearance.show(preferences, onChanged = {
            preferences = it
            preferenceState.preview(it)
            if (preferenceState.commit()) {
                persistPreferences(preferenceState.saved)
                preferenceState.markPersisted()
            }
            applyPreferences()
        }, onClose = {
            host.refreshHints()
        })
        host.refreshHints()
    }

    private fun applyPreferences() {
        navigator?.submitPreferences(readiumPreferences(preferences))
    }

    private fun loadPreferences(): EpubReaderPreferences {
        val store = host.viewContext.getSharedPreferences("epub-reader", 0)
        return EpubReaderPreferences(
            theme = runCatching { EpubTheme.valueOf(store.getString("theme", EpubTheme.SEPIA.name)!!) }.getOrDefault(EpubTheme.SEPIA),
            fontFamily = store.getString("fontFamily", "publisher") ?: "publisher",
            fontScale = store.getFloat("fontScale", 1f),
            lineHeight = store.getFloat("lineHeight", 1.25f),
            pageMargins = store.getFloat("pageMargins", 1f),
            columns = runCatching { EpubColumns.valueOf(store.getString("columns", EpubColumns.AUTO.name)!!) }.getOrDefault(EpubColumns.AUTO),
            publisherStyles = store.getBoolean("publisherStyles", true),
            scroll = store.getBoolean("scroll", false),
            textAlignment = store.getString("textAlignment", "start") ?: "start"
        )
    }

    private fun persistPreferences(value: EpubReaderPreferences) {
        host.viewContext.getSharedPreferences("epub-reader", 0).edit()
            .putString("theme", value.theme.name)
            .putString("fontFamily", value.fontFamily)
            .putFloat("fontScale", value.fontScale)
            .putFloat("lineHeight", value.lineHeight)
            .putFloat("pageMargins", value.pageMargins)
            .putString("columns", value.columns.name)
            .putBoolean("publisherStyles", value.publisherStyles)
            .putBoolean("scroll", value.scroll)
            .putString("textAlignment", value.textAlignment)
            .apply()
    }

    private fun readiumPreferences(value: EpubReaderPreferences) = EpubPreferences(
        theme = when (value.theme) {
            EpubTheme.SYSTEM -> null
            EpubTheme.LIGHT -> ReadiumTheme.LIGHT
            EpubTheme.SEPIA -> ReadiumTheme.SEPIA
            EpubTheme.DARK -> ReadiumTheme.DARK
        },
        columnCount = when (value.columns) {
            EpubColumns.AUTO -> ColumnCount.AUTO
            EpubColumns.ONE -> ColumnCount.ONE
            EpubColumns.TWO -> ColumnCount.TWO
        },
        fontFamily = when (value.fontFamily) {
            "serif" -> FontFamily.SERIF
            "sans-serif" -> FontFamily.SANS_SERIF
            "monospace" -> FontFamily.MONOSPACE
            else -> null
        },
        fontSize = value.fontScale.toDouble(),
        lineHeight = value.lineHeight.toDouble(),
        pageMargins = value.pageMargins.toDouble(),
        publisherStyles = value.publisherStyles,
        scroll = value.scroll,
        textAlign = when (value.textAlignment) {
            "justify" -> TextAlign.JUSTIFY
            "center" -> TextAlign.CENTER
            else -> TextAlign.START
        }
    )

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        pagePreview.setControlsVisible(visible)
        if (::narrationDock.isInitialized) {
            val params = narrationDock.layoutParams as FrameLayout.LayoutParams
            params.bottomMargin = dp(if (visible) 66 else 12)
            narrationDock.layoutParams = params
            narrationDock.visibility = if (visible && narration != null) View.VISIBLE else View.GONE
        }
        pagePreview.refresh()
        if (!visible) root.findFocus()?.clearFocus() else root.post { controls.getOrNull(focusedControl)?.requestFocus() }
        if (::host.isInitialized) host.refreshHints()
    }

    private fun moveControlFocus(direction: Direction) {
        if (controls.isEmpty()) return
        val step = if (direction == Direction.LEFT || direction == Direction.UP) -1 else 1
        var next = focusedControl
        while (next + step in controls.indices) {
            next += step
            if (controls[next].isShown) {
                focusedControl = next
                controls[next].requestFocus()
                return
            }
        }
    }

    private fun updatePosition() {
        if (!::position.isInitialized) return
        val progression = latestLocator?.locations?.totalProgression
        position.text = when {
            pageCount > 0 -> "Page ${pageIndex + 1} of $pageCount"
            progression != null -> "${(progression * 100).toInt()}%"
            else -> title
        }
    }

    private fun showFailure(message: String) {
        loading.visibility = View.VISIBLE
        loading.text = "$message\n\nSelect retries"
    }

    private fun controlBackground(): StateListDrawable {
        fun face(fill: Int, stroke: Int = 0): GradientDrawable = ThemeGradientDrawable().apply {
            cornerRadius = Styler.dp(host.viewContext, 9f)
            setColor(fill)
            if (stroke != 0) setStroke(dp(2), stroke)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), face(0x443CDBC9))
            addState(intArrayOf(android.R.attr.state_focused), face(0x2216B9A8, colors.focusRing))
            addState(intArrayOf(), face(Color.TRANSPARENT))
        }
    }

    private fun fragmentTag() = "epub:$workId:$sourceItemId"
    private fun dp(value: Int) = Styler.dpInt(host.viewContext, value.toFloat())

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
