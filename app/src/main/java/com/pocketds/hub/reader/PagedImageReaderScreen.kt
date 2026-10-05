package com.pocketds.hub.reader

import android.graphics.Color
import android.graphics.PointF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import coil.request.ImageRequest
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.input.Stick
import com.pocketds.hub.model.ReadingPublicationManifest
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.HintBarView
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.settings.DomainPreferences
import com.pocketds.hub.ui.AppIcon
import com.pocketds.hub.ui.ChoiceOverlay
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.OverlayButtons
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Theme
import com.pocketds.hub.ui.Type
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

/**
 * The comic and manga reader (Kavita, through the hub's page routes).
 *
 * Its keys are [ReaderPadMap]'s (#16): Ⓐ reads on and Ⓑ back, Select leaves,
 * Start shows the controls, the right stick pans, L3 held is a magnifier, R3
 * lists the keys; every key stays here. The hint row inside the controls and
 * the Controls sheet say the same.
 *
 * How a series is read is remembered per series (#16, C1): its fit (whole
 * page, fit width, or thirds) and direction, falling back to a default for
 * every series, and the third you were on comes back. A zoom stays from page
 * to page: the next page opens at the same zoom and horizontal place, at its
 * top. Thirds are worked out from the page's shape (C2): three for a comic
 * page, two for a spread, with "Part 2 of 3" and a small map of the page. The
 * last page ends on a card naming the next issue (C6).
 */
class PagedImageReaderScreen(
    private val api: HubApi,
    private val workId: String,
    private val initialSourceItemId: String,
    initialTitle: String,
    private val ringVisible: () -> Boolean,
    private val onProgressChanged: () -> Unit = {},
    private val readingList: List<com.pocketds.hub.model.ServerReadingListEntry> = emptyList(),
    private val readingListIndex: Int = -1,
    private val openAtEnd: Boolean = false,
    /** A zoom carried from the issue before, in a reading list, so it persists there too. */
    carriedZoom: ComicZoom = ComicZoom()
) : Screen {
    override val contentDomain = com.pocketds.hub.state.ContentMode.BOOKS
    override val title: String = initialTitle
    override val immersive: Boolean = true
    override val focusOnShow: Boolean = false

    private lateinit var host: ScreenHost
    private lateinit var root: FrameLayout
    private lateinit var image: SubsamplingScaleImageView
    private lateinit var loading: TextView
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var titleView: TextView
    /** Under the title: "Issue 51 · Page 2 of 24". */
    private lateinit var subtitleView: TextView
    private var issueName = ""
    private lateinit var positionView: TextView
    private lateinit var seek: SeekBar
    private lateinit var thirdsButton: TextView
    private lateinit var colors: PocketColors
    private lateinit var options: ChoiceOverlay
    private lateinit var pagePreview: ReaderPagePreviewController
    private lateinit var previewImage: ImageView
    private lateinit var previewLabel: TextView
    private lateinit var previewCard: LinearLayout
    private lateinit var regionHint: TextView
    private lateinit var pageMap: PageMapView
    private lateinit var endCard: EndOfIssueCard
    private lateinit var keyRow: HintBarView
    private var previewJob: Job? = null
    private var previewRequest: coil.request.Disposable? = null
    private var repository: ReaderPageRepository? = null

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var progress: ReadingProgress
    private lateinit var readingSession: ReadingProgress.Session
    private lateinit var manifestCache: ReadingManifestCache
    private var visibleCheckpoint: Pair<ReadingCheckpointKey, ReadingLocation>? = null
    private var checkpointErrorShown = false
    private var manifestJob: Job? = null
    private var pageJob: Job? = null
    private var endJob: Job? = null
    private var generation = 0L
    private var manifest: ReadingPublicationManifest? = null
    private var state: PagedImageState? = null
    private var currentSourceItemId = initialSourceItemId
    private var controlsVisible = true
    private var suppressSeek = false
    private var pendingStartAtEnd = false
    private val focusables = mutableListOf<View>()
    private var focusedControl = 0

    /** This series' fit and direction (C1), read when the screen is made. */
    private lateinit var view: ComicView
    /** A zoom past the fit, kept from page to page and issue to issue. */
    private var zoom = carriedZoom
    /** Pages as they decoded, which outrank the manifest's sizes for the steps. */
    private val measured = HashMap<Int, Pair<Int, Int>>()
    /** Where the next page to load opens: going back, at its end. */
    private var arriveAtEnd = false
    /** L3 held: the view to go back to when it is let go. */
    private var magnified: Pair<Float, PointF>? = null
    /** The end card has somewhere to go on to. */
    private var endHasNext = false

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        progress = ReadingProgress.get(host.viewContext)
        readingSession = progress.session()
        manifestCache = ReadingManifestCache(java.io.File(host.viewContext.cacheDir, "reading-manifests"))
        colors = Theme.colors(host.viewContext)
        view = DomainPreferences.comicView(host.viewContext, workId)
        repository = (api as? HubClient)?.let { ReaderPageRepository(host.viewContext, readingSession.api, readingSession.identity) }
        root = FrameLayout(host.viewContext).apply { setBackgroundColor(Color.BLACK) }
        image = SubsamplingScaleImageView(host.viewContext).apply {
            setBackgroundColor(Color.BLACK)
            setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE)
            setPanLimit(SubsamplingScaleImageView.PAN_LIMIT_INSIDE)
            setMaxScale(6f)
            setDoubleTapZoomDpi(240)
            setDoubleTapZoomDuration(180)
            setOnClickListener { toggleControls() }
            setOnImageEventListener(object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                override fun onReady() {
                    loading.visibility = View.GONE
                    refreshKeys()
                    val position = this@PagedImageReaderScreen.state
                    if (position != null) {
                        // The decoded size outranks the manifest's: the steps may change, and
                        // a page reached going back still opens on its last one.
                        measured[position.pageIndex] = image.sWidth to image.sHeight
                        if (arriveAtEnd) position.jump(position.pageIndex, Int.MAX_VALUE) else position.refit()
                    }
                    applyViewport()
                    updatePosition()
                    val publication = manifest ?: return
                    val page = position?.pageIndex ?: return
                    visibleCheckpoint = readingSession.key(workId, publication.sourceItemId, "pages") to ReadingLocation(pageIndex = page)
                    saveCurrent(immediate = false)
                }

                override fun onImageLoadError(e: Exception) {
                    showPageError("This page could not be decoded")
                }

                override fun onTileLoadError(e: Exception) {
                    showPageError("Part of this page could not be decoded")
                }
            })
            // A pinch or a double tap is a zoom of your own, kept from page to page.
            setOnStateChangedListener(object : SubsamplingScaleImageView.OnStateChangedListener {
                override fun onScaleChanged(newScale: Float, origin: Int) {
                    if (origin != SubsamplingScaleImageView.ORIGIN_TOUCH && origin != SubsamplingScaleImageView.ORIGIN_DOUBLE_TAP_ZOOM) return
                    if (magnified != null || !image.isReady) return
                    val center = image.center ?: return
                    setZoom(ComicZoom.of(newScale, baseScale(), center.x, image.sWidth.toFloat()), snap = false)
                }

                override fun onCenterChanged(newCenter: PointF, origin: Int) {
                    if (!zoom.active || magnified != null || image.sWidth <= 0) return
                    if (origin == SubsamplingScaleImageView.ORIGIN_TOUCH || origin == SubsamplingScaleImageView.ORIGIN_FLING)
                        zoom = zoom.copy(anchorX = (newCenter.x / image.sWidth).coerceIn(0f, 1f))
                }
            })
        }
        root.addView(image, FrameLayout.LayoutParams(MATCH, MATCH))
        loading = TextView(host.viewContext).apply {
            text = "Opening publication…"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(0x55000000)
        }
        root.addView(loading, FrameLayout.LayoutParams(MATCH, MATCH))
        pageMap = PageMapView(host.viewContext).apply {
            onStep = { step ->
                state?.let { position ->
                    position.jump(position.pageIndex, step)
                    applyViewport(animated = true)
                    updatePosition()
                }
            }
        }
        root.addView(pageMap, FrameLayout.LayoutParams(dp(60), dp(96), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(16); marginEnd = dp(16)
        })
        buildTopBar()
        buildBottomBar()
        previewCard = LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; visibility = View.GONE
            setPadding(dp(8), dp(8), dp(8), dp(8)); setBackgroundColor(0xEE141518.toInt())
        }
        previewImage = ImageView(host.viewContext).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        previewLabel = TextView(host.viewContext).apply { textSize = 12f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        previewCard.addView(previewImage, LinearLayout.LayoutParams(dp(88), dp(112)))
        previewCard.addView(previewLabel)
        root.addView(previewCard, FrameLayout.LayoutParams(dp(104), WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(108) })
        regionHint = TextView(host.viewContext).apply {
            textSize = 12f; setTextColor(Color.WHITE); setPadding(dp(12), dp(6), dp(12), dp(6)); visibility = View.GONE
            if (!OverlayButtons.panel(this, 999f)) setBackgroundColor(0xB3141518.toInt())
        }
        root.addView(regionHint, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply { bottomMargin = dp(24) })
        endCard = EndOfIssueCard(host.viewContext, colors) { onPad(it) }
        root.addView(endCard, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply { bottomMargin = dp(28) })
        options = ChoiceOverlay(host.viewContext, colors, ringVisible, sidePanel = true)
        root.addView(options, FrameLayout.LayoutParams(MATCH, MATCH))
        pagePreview = ReaderPagePreviewController(root, image, topBar, bottomBar, listOf(options))
        focusedControl = ReaderControlFocusPolicy.initialIndex(focusables.size) ?: 0
        OverlayButtons.light(thirdsButton, colors, view.fit == ComicFit.THIRDS)
        thirdsButton.isSelected = view.fit == ComicFit.THIRDS
        setControlsVisible(false)
        return root
    }

    override fun onShow() {
        if (manifest == null && manifestJob?.isActive != true) {
            loadManifest(currentSourceItemId, startAtEnd = openAtEnd)
        } else if (manifest != null && !image.isReady && pageJob?.isActive != true) {
            loadPage()
        }
    }

    override fun onHide() {
        saveCurrent(immediate = true)
        manifestJob?.cancel()
        pageJob?.cancel()
        previewJob?.cancel()
        endJob?.cancel()
        previewRequest?.dispose()
        regionHint.removeCallbacks(hideRegionHint)
        pageMap.dismiss()
        magnify(false)
        options.dismiss()
        previewCard.visibility = View.GONE
    }

    override fun onDestroyView() {
        pagePreview.dispose()
        saveCurrent(immediate = true)
        image.recycle()
        uiScope.cancel()
        repository = null
        focusables.clear()
    }

    override fun onAppBackgrounded() = saveCurrent(immediate = true)

    override fun hints(): List<ButtonHint> = ReaderPadMap.hints(padState())

    private fun padState() = ReaderPadState(ReaderKind.COMIC, controlsVisible = controlsVisible,
        loading = ::loading.isInitialized && loading.visibility == View.VISIBLE)

    override fun onPad(action: PadAction): Boolean {
        // Letting go of L3 ends the magnifier, whatever opened over the page meanwhile.
        if (action is PadAction.Click && action.stick == Stick.LEFT && !action.down) {
            magnify(false)
            return true
        }
        if (endCard.isOpen) {
            onEndCard(action)
            return true
        }
        if (options.onPad(action)) return true
        when (val command = ReaderPadMap.command(padState(), action)) {
            ReaderCommand.Forward -> moveReadingFlow(true)
            ReaderCommand.Backward -> moveReadingFlow(false)
            is ReaderCommand.Page -> turnWholePage(command.delta)
            is ReaderCommand.Zoom -> zoom(command.factor)
            is ReaderCommand.Move -> scrollDirection(command.direction)
            is ReaderCommand.Glide -> glide(command.dx, command.dy)
            is ReaderCommand.Magnifier -> magnify(command.on)
            is ReaderCommand.Controls -> setControlsVisible(command.visible)
            ReaderCommand.Leave -> host.back()
            ReaderCommand.Choose -> focusables.getOrNull(focusedControl)?.performClick()
            is ReaderCommand.Focus -> moveControlFocus(command.direction)
            ReaderCommand.Display -> showReadingOptions()
            ReaderCommand.Keys -> showKeys()
            ReaderCommand.Retry -> if (manifest == null) loadManifest(currentSourceItemId) else loadPage()
            else -> Unit
        }
        return true
    }

    // Android edge-back leaves the reader; physical B follows the reading flow.
    override fun onSystemBack(): Boolean {
        if (options.isOpen) { options.cancel(); return true }
        return false
    }

    /**
     * The player's look over the page: a round Close, the series with the
     * issue and page under it, round steps between issues and zoom, and the
     * tools that open something named in words.
     */
    private fun buildTopBar() {
        topBar = LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setBackgroundColor(BAR)
        }
        root.addView(topBar, FrameLayout.LayoutParams(MATCH, dp(60), Gravity.TOP))
        topBar.addView(round(AppIcon.CLOSE, "Close reader") { host.back() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        topBar.addView(LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.VERTICAL
            titleView = TextView(context).apply {
                text = title
                Type.apply(this, Type.Role.HEADING, 17f)
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            addView(titleView)
            subtitleView = TextView(context).apply {
                textSize = 12f
                setTextColor(SOFT_TEXT)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
            }
            addView(subtitleView)
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(10); marginEnd = dp(8) })
        topBar.addView(round(AppIcon.PREVIOUS_ITEM, "Previous issue") { movePublication(-1) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        thirdsButton = pill("Thirds", "Read each page in thirds", ::toggleThirds)
        topBar.addView(thirdsButton, LinearLayout.LayoutParams(WRAP, dp(44)).apply { marginStart = dp(2) })
        topBar.addView(round(AppIcon.ZOOM_OUT, "Zoom out") { zoom(.8f) }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(2) })
        topBar.addView(round(AppIcon.ZOOM_IN, "Zoom in") { zoom(1.25f) }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(2) })
        topBar.addView(pill("Display", "Reading options") { showReadingOptions() }, LinearLayout.LayoutParams(WRAP, dp(44)).apply { marginStart = dp(2) })
        topBar.addView(round(AppIcon.PAD, "Keys") { showKeys() }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(2) })
        topBar.addView(round(AppIcon.NEXT_ITEM, "Next issue") { movePublication(1) }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(2) })
    }

    private fun buildBottomBar() {
        bottomBar = LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(4), dp(14), dp(2))
            setBackgroundColor(BAR)
        }
        root.addView(bottomBar, FrameLayout.LayoutParams(MATCH, dp(BOTTOM_DP), Gravity.BOTTOM))
        val navigation = LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bottomBar.addView(navigation, LinearLayout.LayoutParams(MATCH, dp(52)))
        navigation.addView(round(AppIcon.PREVIOUS, "Previous page") { turnWholePage(-1) }, LinearLayout.LayoutParams(dp(48), dp(48)))
        seek = SeekBar(host.viewContext).apply {
            max = 1
            contentDescription = "Publication position"
            progressTintList = android.content.res.ColorStateList.valueOf(colors.accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.argb(90, 255, 255, 255))
            Styler.makeFocusable(this)
            FocusDecorator.attach(this, ringVisible, scale = false)
            FocusDecorator.listen(this, ringVisible) { view, focused ->
                if (focused) focusedControl = focusables.indexOf(view).coerceAtLeast(0)
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

                override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser && !suppressSeek) { positionView.text = "Page ${value + 1} of ${seekBar.max + 1}"; showPagePreview(value) }
                }

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    previewJob?.cancel(); previewRequest?.dispose(); previewCard.visibility = View.GONE
                    state?.seek(seekBar.progress)
                    arriveAtEnd = false
                    loadPage()
                }
            })
        }
        focusables += seek
        navigation.addView(seek, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            marginStart = dp(8)
            marginEnd = dp(8)
        })
        positionView = TextView(host.viewContext).apply {
            textSize = 12f
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setTextColor(SOFT_TEXT)
        }
        navigation.addView(positionView, LinearLayout.LayoutParams(WRAP, MATCH).apply { marginEnd = dp(10) })
        // The last control registered is the forward page (ReaderControlFocusPolicy).
        navigation.addView(round(AppIcon.NEXT, "Next page") { turnWholePage(1) }, LinearLayout.LayoutParams(dp(48), dp(48)))
        // What the keys do, inside the controls: the app's own hint bar is hidden here.
        keyRow = ReaderKeys.row(host.viewContext, colors) { onPad(it) }
        bottomBar.addView(keyRow, LinearLayout.LayoutParams(MATCH, dp(ReaderKeys.ROW_DP)))
    }

    private fun round(icon: AppIcon, label: String, click: () -> Unit): View =
        register(OverlayButtons.round(host.viewContext, colors.focusRing, icon, label, click))

    private fun pill(label: String, description: String, click: () -> Unit): TextView =
        register(OverlayButtons.pill(host.viewContext, colors.focusRing, label, description, onTap = click))

    /** In the pad's order through the controls, remembering which one had focus. */
    private fun <T : View> register(view: T): T = view.apply {
        FocusDecorator.attach(this, ringVisible, scale = false)
        FocusDecorator.listen(this, ringVisible) { v, focused ->
            if (focused) focusedControl = focusables.indexOf(v).coerceAtLeast(0)
        }
        focusables += this
    }

    private fun refreshKeys() {
        if (::keyRow.isInitialized) keyRow.setHints(ReaderPadMap.hints(padState().copy(controlsVisible = true)))
    }

    private fun showKeys() = ReaderKeys.show(options, padState().copy(controlsVisible = false))

    private fun loadManifest(sourceItemId: String, startAtEnd: Boolean = false) {
        manifestJob?.cancel()
        pageJob?.cancel()
        generation++
        val requestGeneration = generation
        currentSourceItemId = sourceItemId
        pendingStartAtEnd = startAtEnd
        loading.text = "Opening publication…"
        loading.visibility = View.VISIBLE
        refreshKeys()
        manifestJob = uiScope.launch {
            when (val result = readingSession.api.readingPublication(workId, sourceItemId)) {
                is HubResult.Ok -> {
                    if (requestGeneration != generation) return@launch
                    val key = readingSession.key(workId, sourceItemId, "pages")
                    runCatching { manifestCache.save(key, result.value) }
                    try {
                        val resume = progress.resume(readingSession, key)
                        val completion = ReadingCompletionRepository.get(host.viewContext)
                        val choice = if (completion.shouldStartAtBeginning(workId))
                            ReadingResume(ReadingLocation(pageIndex = completion.pageResume(workId, result.value.currentPage)))
                        else chooseReadingResume(options, progress, key, resume)
                            ?: run { showPageError("Choose a reading position to continue"); return@launch }
                        if (requestGeneration != generation) return@launch
                        applyManifest(result.value.copy(currentPage = choice.location?.pageIndex ?: result.value.currentPage), pendingStartAtEnd)
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { showPageError("The saved reading position could not be read. It has been preserved.") }
                }
                is HubResult.Failed -> if (requestGeneration == generation) {
                    val key = readingSession.key(workId, sourceItemId, "pages")
                    val cached = manifestCache.read(key)
                    val local = runCatching { progress.store.read(key) }.getOrNull()
                    if (cached != null && local?.local?.pageIndex != null) {
                        val completion = ReadingCompletionRepository.get(host.viewContext)
                        val choice = if (completion.shouldStartAtBeginning(workId))
                            ReadingResume(ReadingLocation(pageIndex = completion.pageResume(workId, local.local.pageIndex!!)))
                        else chooseReadingResume(options, progress, key, ReadingResume(local.local, local.conflicted))
                        if (choice != null && requestGeneration == generation) {
                            applyManifest(cached.copy(currentPage = choice.location?.pageIndex ?: cached.currentPage), pendingStartAtEnd)
                            host.notify("Using cached pages · reading progress is saved on this device")
                            return@launch
                        }
                    }
                    loading.text = result.message + "\nSelect retries"
                    loading.visibility = View.VISIBLE
                    refreshKeys()
                }
            }
        }
    }

    private fun applyManifest(value: ReadingPublicationManifest, startAtEnd: Boolean) {
        if (value.pageCount <= 0) {
            loading.text = "This publication has no readable pages"
            return
        }
        manifest = value
        measured.clear()
        titleView.text = ReaderTitleFormatter.heading(value.seriesTitle, value.title, title)
        issueName = ReaderTitleFormatter.issue(value.kind, value.seriesTitle, value.title, value.number)
        val start = if (startAtEnd) value.pageCount - 1 else value.currentPage
        // The third you were on comes back when the issue opens where you left it.
        val step = if (startAtEnd) Int.MAX_VALUE
            else DomainPreferences.comicPlace(host.viewContext, workId)?.stepFor(value.sourceItemId, start, stepsFor(start)) ?: 0
        arriveAtEnd = startAtEnd
        state = PagedImageState(value.pageCount, start, ::stepsFor, step)
        seek.max = max(1, value.pageCount - 1)
        loadPage()
        host.refreshHints()
    }

    private fun loadPage() {
        val value = manifest ?: return
        val position = state ?: return
        val page = position.pageIndex.coerceIn(0, value.pageCount - 1)
        val pageUrl = readingSession.api.readingPublicationPageUrl(workId, value.sourceItemId, page)
        val pageRepository = repository
        if (pageRepository == null) {
            loading.text = "The real reader requires a configured Hub connection"
            loading.visibility = View.VISIBLE
            return
        }
        pageJob?.cancel()
        generation++
        val pageGeneration = generation
        magnified = null
        image.recycle()
        loading.text = "Loading page ${page + 1}…"
        loading.visibility = View.VISIBLE
        updatePosition()
        pageJob = uiScope.launch {
            try {
                val file = pageRepository.obtain(pageUrl)
                if (pageGeneration != generation) return@launch
                image.setImage(ImageSource.uri(file.absolutePath))
                prefetchAround(page)
            } catch (e: Exception) {
                if (pageGeneration == generation) showPageError("Page ${page + 1} could not be loaded")
            }
        }
    }

    private fun prefetchAround(page: Int) {
        val value = manifest ?: return
        val pageRepository = repository ?: return
        listOf(page + 1, page - 1).filter { it in 0 until value.pageCount }.forEach { candidate ->
            uiScope.launch(Dispatchers.IO) {
                runCatching {
                    pageRepository.obtain(readingSession.api.readingPublicationPageUrl(workId, value.sourceItemId, candidate))
                }
            }
        }
    }

    private fun showPageError(message: String) {
        loading.text = "$message\nSelect retries"
        loading.visibility = View.VISIBLE
        refreshKeys()
    }

    // ------------------------------------------------------------ the page

    /** How many steps a page takes: thirds from its shape (C2), one while zoomed or not in thirds. */
    private fun stepsFor(page: Int): Int {
        if (view.fit != ComicFit.THIRDS || zoom.active) return 1
        val (width, height) = pageSize(page) ?: return DEFAULT_STEPS
        return ViewportStepPlanner.count(width, height, viewWidth(), viewHeight())
    }

    private fun pageSize(page: Int): Pair<Int, Int>? = measured[page]
        ?: manifest?.pages?.firstOrNull { it.index == page }?.takeIf { it.width > 0 && it.height > 0 }?.let { it.width to it.height }

    private fun viewWidth(): Int = image.width.takeIf { it > 0 } ?: root.width.takeIf { it > 0 } ?: root.resources.displayMetrics.widthPixels
    private fun viewHeight(): Int = image.height.takeIf { it > 0 } ?: root.height.takeIf { it > 0 } ?: root.resources.displayMetrics.heightPixels

    /** The scale the fit itself reads at: the whole page, or its width. */
    private fun baseScale(): Float = if (view.fit == ComicFit.WHOLE) image.minScale else fitWidthScale()

    private fun fitWidthScale(): Float =
        if (image.sWidth <= 0) image.minScale else (image.width.toFloat() / image.sWidth).coerceIn(image.minScale, image.maxScale)

    private fun rtl(): Boolean = (view.direction ?: manifest?.direction) == "rtl"

    /** Places the page as the fit, the zoom and the step say. */
    private fun applyViewport(animated: Boolean = false) {
        if (!image.isReady) return
        val position = state ?: return
        val width = image.sWidth.toFloat()
        val height = image.sHeight.toFloat()
        val atEnd = arriveAtEnd
        arriveAtEnd = false
        if (zoom.active) {
            val scale = zoom.scaleFor(baseScale(), image.minScale, image.maxScale)
            val (x, y) = zoom.center(width, height, image.width / scale, image.height / scale, atEnd)
            place(scale, PointF(x, y), animated)
            showFreeMap(scale, PointF(x, y))
            return
        }
        when (view.fit) {
            ComicFit.WHOLE -> place(image.minScale, PointF(width / 2f, height / 2f), animated)
            ComicFit.WIDTH -> {
                val scale = fitWidthScale()
                val visible = image.height / scale
                val y = when {
                    visible >= height -> height / 2f
                    atEnd -> height - visible / 2f
                    else -> visible / 2f
                }
                place(scale, PointF(width / 2f, y), animated)
            }
            ComicFit.THIRDS -> {
                val steps = ViewportStepPlanner.fitWidth(image.sWidth, image.sHeight, image.width, image.height)
                val index = position.viewportIndex.coerceIn(0, steps.lastIndex)
                val step = steps[index]
                place(fitWidthScale(), PointF(width / 2f, ((step.top + step.bottom) / 2 * height).toFloat()), animated)
                if (steps.size > 1) {
                    regionHint.text = ReaderTitleFormatter.part(index + 1, steps.size)
                    regionHint.visibility = View.VISIBLE
                    regionHint.removeCallbacks(hideRegionHint)
                    regionHint.postDelayed(hideRegionHint, PageMapView.SHOW_MS)
                    if (!controlsVisible) pageMap.showStep(image.sWidth, image.sHeight, steps, index)
                }
                manifest?.let { DomainPreferences.setComicPlace(host.viewContext, workId, ComicPlace(it.sourceItemId, position.pageIndex, index)) }
            }
        }
    }

    private fun place(scale: Float, center: PointF, animated: Boolean) {
        if (animated) image.animateScaleAndCenter(scale, center)?.withDuration(180)?.withInterruptible(true)?.start()
        else image.setScaleAndCenter(scale, center)
    }

    /** The map of the page for a free view: a zoom, a pan with the stick or the D-pad. */
    private fun showFreeMap(scale: Float, center: PointF) {
        if (controlsVisible || image.sWidth <= 0 || image.sHeight <= 0 || scale <= 0f) return
        val halfWidth = image.width / scale / 2f
        val halfHeight = image.height / scale / 2f
        pageMap.showView(image.sWidth, image.sHeight, NormalizedViewport(
            ((center.x - halfWidth) / image.sWidth).toDouble(), ((center.y - halfHeight) / image.sHeight).toDouble(),
            ((center.x + halfWidth) / image.sWidth).toDouble(), ((center.y + halfHeight) / image.sHeight).toDouble()))
    }

    private fun moveReadingFlow(forward: Boolean) {
        if (view.fit == ComicFit.THIRDS && !zoom.active) {
            if (forward) advance() else retreat()
            return
        }
        if (image.isReady) {
            val horizontal = image.sWidth > image.sHeight
            val rtl = rtl()
            val first = if (horizontal) {
                if (forward == rtl) Direction.LEFT else Direction.RIGHT
            } else if (forward) Direction.DOWN else Direction.UP
            if (panWithinPage(first)) return
            // Wrap to the start of the next row or column when both dimensions
            // overflow. Without this, zoomed spreads skip most of the image.
            if (wrapReadingFlow(horizontal, forward, rtl)) return
        }
        if (forward) advance() else retreat()
    }

    private fun scrollDirection(direction: Direction) {
        if (panWithinPage(direction)) return
        // Horizontal input continues the reading flow at the edge of the page.
        // Vertical input only pans; it never unexpectedly turns a page.
        when (direction) {
            Direction.LEFT -> if (rtl()) advance() else retreat()
            Direction.RIGHT -> if (rtl()) retreat() else advance()
            Direction.UP, Direction.DOWN -> Unit
        }
    }

    private fun panWithinPage(direction: Direction): Boolean {
        if (!image.isReady || image.scale <= 0f) return false
        val center = image.center ?: PointF(image.sWidth / 2f, image.sHeight / 2f)
        val horizontal = direction == Direction.LEFT || direction == Direction.RIGHT
        val sign = if (direction == Direction.LEFT || direction == Direction.UP) -1 else 1
        val target = if (horizontal) {
            ComicPanPolicy.step(center.x, image.sWidth, image.width / image.scale, sign)?.let { PointF(it, center.y) }
        } else {
            ComicPanPolicy.step(center.y, image.sHeight, image.height / image.scale, sign)?.let { PointF(center.x, it) }
        } ?: return false
        image.animateCenter(target)?.withDuration(180)?.withInterruptible(true)?.start()
        if (zoom.active) zoom = zoom.copy(anchorX = (target.x / image.sWidth).coerceIn(0f, 1f))
        showFreeMap(image.scale, target)
        return true
    }

    private fun wrapReadingFlow(horizontal: Boolean, forward: Boolean, rtl: Boolean): Boolean {
        if (!image.isReady || image.scale <= 0f) return false
        val center = image.center ?: PointF(image.sWidth / 2f, image.sHeight / 2f)
        val visibleWidth = image.width / image.scale
        val visibleHeight = image.height / image.scale
        val target = if (horizontal) {
            val nextY = ComicPanPolicy.step(center.y, image.sHeight, visibleHeight, if (forward) 1 else -1)
                ?: return false
            val firstX = ComicPanPolicy.edge(image.sWidth, visibleWidth, high = forward == rtl)
            PointF(firstX, nextY)
        } else {
            val nextX = ComicPanPolicy.step(center.x, image.sWidth, visibleWidth, if (forward == rtl) -1 else 1)
                ?: return false
            val firstY = ComicPanPolicy.edge(image.sHeight, visibleHeight, high = !forward)
            PointF(nextX, firstY)
        }
        image.animateCenter(target)?.withDuration(180)?.withInterruptible(true)?.start()
        if (zoom.active) zoom = zoom.copy(anchorX = (target.x / image.sWidth).coerceIn(0f, 1f))
        showFreeMap(image.scale, target)
        return true
    }

    /** The right stick: the page moves under it, smoothly, at most [GLIDE] screens a second. */
    private fun glide(dx: Float, dy: Float) {
        if (!image.isReady || image.scale <= 0f) return
        val center = image.center ?: return
        val scale = image.scale
        val target = PointF(center.x + dx * GLIDE * image.width / scale, center.y + dy * GLIDE * image.height / scale)
        image.setScaleAndCenter(scale, target)
        if (zoom.active) zoom = zoom.copy(anchorX = (target.x / image.sWidth).coerceIn(0f, 1f))
        showFreeMap(scale, target)
    }

    /** L3 held: twice as close round the middle of the view; let go, back to where it was. */
    private fun magnify(on: Boolean) {
        if (!::image.isInitialized) return
        if (on) {
            if (!image.isReady || magnified != null) return
            val center = image.center ?: return
            magnified = image.scale to PointF(center.x, center.y)
            image.animateScaleAndCenter((image.scale * MAGNIFY).coerceAtMost(image.maxScale), center)
                ?.withDuration(140)?.withInterruptible(true)?.start()
            return
        }
        val (scale, center) = magnified ?: return
        magnified = null
        if (image.isReady) image.animateScaleAndCenter(scale, center)?.withDuration(140)?.withInterruptible(true)?.start()
    }

    private fun turnWholePage(delta: Int) {
        val position = state ?: return
        if (position.turnPage(delta)) {
            arriveAtEnd = delta < 0
            loadPage()
        } else if (delta > 0) {
            showEndCard()
        } else {
            movePublication(delta)
        }
    }

    private fun advance() {
        val position = state ?: return
        val page = position.pageIndex
        if (position.advance()) {
            if (position.pageIndex != page) {
                arriveAtEnd = false
                loadPage()
            } else {
                applyViewport(animated = true)
                updatePosition()
            }
            return
        }
        showEndCard()
    }

    private fun retreat() {
        val position = state ?: return
        val page = position.pageIndex
        if (position.retreat()) {
            if (position.pageIndex != page) {
                arriveAtEnd = true
                loadPage()
            } else {
                applyViewport(animated = true)
                updatePosition()
            }
            return
        }
        movePublication(-1)
    }

    // ------------------------------------------------------ between issues

    private fun movePublication(delta: Int) {
        endCard.hide()
        if (readingList.isNotEmpty()) {
            val index = readingListIndex.takeIf { it in readingList.indices }
                ?: readingList.indexOfFirst { it.workId == workId && it.sourceItemId == currentSourceItemId }
            val target = readingList.getOrNull(index + delta)
            if (index < 0 || target == null) { host.notify(if (delta < 0) "Start of reading list" else "End of reading list"); return }
            saveCurrent(immediate = true)
            host.back()
            // Each issue of a list is its own screen; its series' way of reading comes from storage, and the zoom comes along.
            host.push(PagedImageReaderScreen(api, target.workId, target.sourceItemId, "${target.seriesTitle} · ${target.title}", ringVisible,
                onProgressChanged, readingList, index + delta, openAtEnd = delta < 0, carriedZoom = zoom))
            return
        }
        val value = manifest ?: return
        val target = if (delta < 0) value.previousSourceItemId else value.nextSourceItemId
        if (target.isBlank()) {
            host.notify(if (delta < 0) "This is the first publication" else "This is the last publication")
            return
        }
        saveCurrent(immediate = true)
        loadManifest(target, startAtEnd = delta < 0)
    }

    /** The last page, read to its end: the card naming what comes next (C6). */
    private fun showEndCard() {
        val value = manifest ?: return
        setControlsVisible(false)
        pageMap.dismiss()
        // The card sits where the "Part 3 of 3" pill does.
        regionHint.removeCallbacks(hideRegionHint)
        regionHint.visibility = View.GONE
        saveCurrent(immediate = true)
        val series = value.seriesTitle.ifBlank { title }
        val heading = EndOfIssue.heading(series, value.title, value.number)
        endJob?.cancel()
        if (readingList.isNotEmpty()) {
            val index = readingListIndex.takeIf { it in readingList.indices }
                ?: readingList.indexOfFirst { it.workId == workId && it.sourceItemId == currentSourceItemId }
            val next = readingList.getOrNull(index + 1)
            endHasNext = next != null
            endCard.show(heading, EndOfIssue.next(series, next?.seriesTitle, next?.title.orEmpty(), "", readingList = true), endHasNext)
            return
        }
        endHasNext = value.nextSourceItemId.isNotBlank()
        if (!endHasNext) {
            endCard.show(heading, EndOfIssue.next(series, null, "", "", readingList = false), canContinue = false)
            return
        }
        endCard.show(heading, "Next: the next issue", canContinue = true)
        endJob = uiScope.launch {
            val next = (readingSession.api.readingPublication(workId, value.nextSourceItemId) as? HubResult.Ok)?.value ?: return@launch
            if (endCard.isOpen) endCard.updateNext(EndOfIssue.next(series, next.seriesTitle.ifBlank { series }, next.title, next.number, readingList = false))
        }
    }

    private fun onEndCard(action: PadAction) {
        when (action) {
            PadAction.Activate -> if (endHasNext) movePublication(1)
            PadAction.Back -> endCard.hide()
            PadAction.Refresh -> host.back()
            else -> Unit
        }
    }

    // ---------------------------------------------------------- the choices

    private fun toggleThirds() = setFit(if (view.fit == ComicFit.THIRDS) ComicFit.WHOLE else ComicFit.THIRDS)

    /** A fit chosen for this series: kept for it, the zoom let go, the page placed again. */
    private fun setFit(fit: ComicFit) {
        view = view.copy(fit = fit)
        DomainPreferences.setComicView(host.viewContext, workId, view)
        zoom = ComicZoom()
        val value = manifest
        val page = state?.pageIndex ?: value?.currentPage ?: 0
        if (value != null) state = PagedImageState(value.pageCount, page, ::stepsFor, 0)
        // Lit while on, like a filter: the accent on Classic, white on Glass.
        OverlayButtons.light(thirdsButton, colors, fit == ComicFit.THIRDS)
        thirdsButton.isSelected = fit == ComicFit.THIRDS
        applyViewport()
        updatePosition()
        host.refreshHints()
    }

    private fun setDirection(direction: String?) {
        view = view.copy(direction = direction)
        DomainPreferences.setComicView(host.viewContext, workId, view)
        applyViewport()
    }

    /**
     * A zoom of your own, kept for the pages after. Beginning one in thirds
     * makes this page one step; ending one goes back to the step nearest.
     */
    private fun setZoom(next: ComicZoom, snap: Boolean) {
        val was = zoom.active
        zoom = next
        if (was == next.active) return
        val position = state ?: return
        if (!next.active && view.fit == ComicFit.THIRDS && image.isReady) {
            val steps = ViewportStepPlanner.fitWidth(image.sWidth, image.sHeight, image.width, image.height)
            val y = (image.center?.y ?: 0f) / image.sHeight.coerceAtLeast(1)
            val nearest = steps.indices.minByOrNull { abs((steps[it].top + steps[it].bottom) / 2 - y) } ?: 0
            position.jump(position.pageIndex, nearest)
            if (snap) applyViewport(animated = true)
        } else position.refit()
        updatePosition()
    }

    private fun showReadingOptions(tab: String = "display") {
        val context = host.viewContext
        val everySeries = DomainPreferences.comicDefaultFit(context)
        options.resetBody()
        options.open("Reading options")
        options.tabs(listOf("display" to "Display", "flow" to "Flow"), tab, ::showReadingOptions)
        if (tab == "display") {
            options.section("This series")
            ComicFit.entries.forEach { fit ->
                options.choice(fit.label, selected = view.fit == fit) { setFit(fit); showReadingOptions(tab) }
            }
            options.section("Every series")
            options.choice("Open every series this way", "New series open as ${everySeries.label.lowercase()}",
                selected = everySeries == view.fit) {
                DomainPreferences.setComicDefaultFit(context, view.fit)
                showReadingOptions(tab)
            }
        } else {
            val library = manifest?.direction ?: "ltr"
            options.choice("As the library reads", if (library == "rtl") "Right to left" else "Left to right",
                selected = view.direction == null) { setDirection(null); showReadingOptions(tab) }
            options.choice("Left to right", selected = view.direction == "ltr") { setDirection("ltr"); showReadingOptions(tab) }
            options.choice("Right to left", selected = view.direction == "rtl") { setDirection("rtl"); showReadingOptions(tab) }
        }
        options.focusBody()
    }

    private fun showPagePreview(page: Int) {
        previewJob?.cancel()
        previewRequest?.dispose()
        previewImage.setImageDrawable(null); previewLabel.text = "Page ${page + 1}"; previewCard.visibility = View.VISIBLE
        val value = manifest ?: return
        val repo = repository ?: return
        previewJob = uiScope.launch {
            delay(160)
            try {
                val file = repo.obtain(readingSession.api.readingPublicationPageUrl(workId, value.sourceItemId, page))
                if (seek.progress != page || previewCard.visibility != View.VISIBLE) return@launch
                previewRequest = com.pocketds.hub.ui.Artwork.loader(api, host.viewContext)
                    .enqueue(ImageRequest.Builder(host.viewContext).data(file).size(dp(88), dp(112)).target(previewImage).build())
            } catch (_: kotlinx.coroutines.CancellationException) { /* A newer scrub target replaced this one. */ }
            catch (_: Exception) { previewLabel.text = "Page ${page + 1} · preview unavailable" }
        }
    }

    /** The zoom keys and buttons: the scale changes round the middle of the view, and the zoom is kept. */
    private fun zoom(factor: Float) {
        if (!image.isReady) return
        val center = image.center ?: PointF(image.sWidth / 2f, image.sHeight / 2f)
        val target = (image.scale * factor).coerceIn(image.minScale, image.maxScale)
        image.animateScaleAndCenter(target, center)?.withDuration(180)?.withInterruptible(true)?.start()
        setZoom(ComicZoom.of(target, baseScale(), center.x, image.sWidth.toFloat()), snap = true)
        showFreeMap(target, center)
    }

    private fun saveCurrent(immediate: Boolean) {
        val (key, location) = visibleCheckpoint ?: return
        try {
            progress.save(key, location)
            if (immediate) progress.requestSync(immediate = true)
            onProgressChanged()
        } catch (_: Exception) {
            if (!checkpointErrorShown) host.notify("Reading position could not be saved on this device")
            checkpointErrorShown = true
        }
    }

    private fun updatePosition() {
        val value = manifest ?: return
        val position = state ?: return
        suppressSeek = true
        seek.progress = position.pageIndex
        suppressSeek = false
        val steps = position.viewportSteps
        positionView.text = ReaderTitleFormatter.subtitle("", position.pageIndex + 1, value.pageCount, position.viewportIndex + 1, steps)
        subtitleView.text = ReaderTitleFormatter.subtitle(issueName, position.pageIndex + 1, value.pageCount)
    }

    private val hideRegionHint = Runnable { if (::regionHint.isInitialized) regionHint.visibility = View.GONE }

    private fun toggleControls() = setControlsVisible(!controlsVisible)

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        pagePreview.setControlsVisible(visible)
        if (visible) pageMap.dismiss()
        refreshKeys()
        if (!visible) {
            root.findFocus()?.clearFocus()
        } else {
            root.post { focusables.getOrNull(focusedControl)?.requestFocus() }
        }
        host.refreshHints()
    }

    private fun moveControlFocus(direction: Direction) {
        if (focusables.isEmpty()) return
        focusedControl = when (direction) {
            Direction.LEFT, Direction.UP -> (focusedControl - 1).coerceAtLeast(0)
            Direction.RIGHT, Direction.DOWN -> (focusedControl + 1).coerceAtMost(focusables.lastIndex)
        }
        focusables[focusedControl].requestFocus()
    }

    private fun dp(value: Int): Int = Styler.dpInt(host.viewContext, value.toFloat())

    private companion object {
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        /** The navigation row, and the keys' row under it. */
        val BOTTOM_DP = 52 + ReaderKeys.ROW_DP + 6
        /** Before a page's size is known, a comic page's three. */
        const val DEFAULT_STEPS = 3
        /** The right stick at full push: screens a second. */
        const val GLIDE = 1.2f
        /** L3 held: how much closer. */
        const val MAGNIFY = 2f
        /** The bars over the page: the app's ground, nearly opaque. */
        val BAR = Color.argb(235, 10, 13, 18)
        val SOFT_TEXT = Color.rgb(213, 219, 227)
    }
}
