package com.pocketds.hub.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.GestureDetector
import android.view.MotionEvent
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
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.input.Stick
import com.pocketds.hub.model.ReadingPublicationManifest
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.settings.ComfortSettings
import com.pocketds.hub.settings.DomainPreferences
import com.pocketds.hub.ui.AppIcon
import com.pocketds.hub.ui.ChoiceOverlay
import com.pocketds.hub.ui.ComfortLayerView
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.OverlayButtons
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.ScreenComfort
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
import kotlin.math.min
import kotlin.math.roundToInt

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
 *
 * The controls are bars of the cover's glass floating over the page,
 * which keeps its size (X7, the owner's choice). Pages either side stay
 * decoded on surfaces behind the one shown ([PageSurface], C3), so a turn
 * swaps to a page already drawn and a jump keeps the page you were on until
 * the next is ready. Comfort (X3) dims and warms the whole reader.
 *
 * The paper border round a page is found on the hub's small thumbnail of it
 * ([PageBounds]) and left out of the fit and the steps, unless the series
 * turns Trim margins off (#18, C5). A finger does what the keys do (C7): a tap
 * in the outer third reads on or back, in the middle it shows the controls,
 * and a swipe across turns the page while it is not zoomed ([ComicTouch]).
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

    /** Glass: the bars take the colours of the issue's own cover. */
    override val pageArtwork: String? get() = IssueCover.path(currentSourceItemId)

    private lateinit var host: ScreenHost
    private lateinit var root: FrameLayout
    /** The surfaces the page is drawn on; [image] is the one in front. */
    private lateinit var surface: PageSurface
    private val image: SubsamplingScaleImageView get() = surface.front.view
    private lateinit var loading: TextView
    private lateinit var bars: ReaderBars
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
    /** "Loading page 5": shown over the page you were on while the next decodes, if that takes a moment. */
    private lateinit var waitPill: TextView
    private lateinit var pageMap: PageMapView
    private lateinit var endCard: EndOfIssueCard
    private lateinit var pageGrid: PageGridView
    private lateinit var comfortLayer: ComfortLayerView
    private var previewJob: Job? = null
    private var repository: ReaderPageRepository? = null

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var progress: ReadingProgress
    private lateinit var readingSession: ReadingProgress.Session
    private lateinit var manifestCache: ReadingManifestCache
    private var visibleCheckpoint: Pair<ReadingCheckpointKey, ReadingLocation>? = null
    private var checkpointErrorShown = false
    private var manifestJob: Job? = null
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
    private lateinit var reading: ComicView
    /** A zoom past the fit, kept from page to page and issue to issue. */
    private var zoom = carriedZoom
    /** Pages as they decoded, which outrank the manifest's sizes for the steps. */
    private val measured = HashMap<Int, Pair<Int, Int>>()
    /** Each page's content inside its paper (C5), as its thumbnail showed it, and the ones being looked at. */
    private val contents = HashMap<PageKey, PageContent>()
    private val measuring = HashMap<PageKey, Job>()
    /** Where the next page to show opens: going back, at its end. */
    private var arriveAtEnd = false
    /** The page asked for and not yet shown; it shows the moment its surface is ready. */
    private var pending: PageKey? = null
    /** Which way you are reading: the neighbour decoded first. */
    private var forward = true
    /** L3 held: the view to go back to when it is let go. */
    private var magnified: Pair<Float, PointF>? = null
    /** The end card has somewhere to go on to. */
    private var endHasNext = false

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        val context = host.viewContext
        progress = ReadingProgress.get(context)
        readingSession = progress.session()
        manifestCache = ReadingManifestCache(java.io.File(context.cacheDir, "reading-manifests"))
        colors = Theme.colors(context)
        reading = DomainPreferences.comicView(context, workId)
        repository = (api as? HubClient)?.let { ReaderPageRepository(context, readingSession.api, readingSession.identity) }
        root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        surface = PageSurface(context, PageSlots.COUNT, ::pageView)
        surface.slots.forEach(::listen)
        root.addView(surface, FrameLayout.LayoutParams(MATCH, MATCH))
        loading = TextView(context).apply {
            text = "Opening publication…"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(0x55000000)
        }
        root.addView(loading, FrameLayout.LayoutParams(MATCH, MATCH))
        pageMap = PageMapView(context).apply {
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
        bars = ReaderBars(context, colors, ReaderBars.ROW_DP, ReaderBars.ROW_DP) { onPad(it) }
        buildTopBar()
        buildBottomBar()
        root.addView(bars.top, bars.topParams())
        root.addView(bars.bottom, bars.bottomParams())
        previewCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; visibility = View.GONE
            setPadding(dp(8), dp(8), dp(8), dp(8))
            OverlayButtons.panel(this, 14f)
        }
        previewImage = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        previewLabel = TextView(context).apply { textSize = 12f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        previewCard.addView(previewImage, LinearLayout.LayoutParams(dp(88), dp(112)))
        previewCard.addView(previewLabel)
        root.addView(previewCard, FrameLayout.LayoutParams(dp(104), WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = bars.bottomHeight + dp(8)
        })
        regionHint = hintPill()
        root.addView(regionHint, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply { bottomMargin = dp(24) })
        waitPill = hintPill()
        root.addView(waitPill, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = dp(24) })
        endCard = EndOfIssueCard(context, colors) { onPad(it) }
        root.addView(endCard, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply { bottomMargin = dp(28) })
        // C4: every page as the hub's thumbnail; the cursor is the grid's own.
        pageGrid = PageGridView(context, colors, com.pocketds.hub.ui.Artwork.loader(api, context), ringVisible).apply {
            onPick = { page -> jumpTo(page) }
            // Closed with B: back to the controls it was opened from, on Pages.
            onClosed = { setControlsVisible(true) }
        }
        root.addView(pageGrid, FrameLayout.LayoutParams(MATCH, MATCH))
        options = ChoiceOverlay(context, colors, ringVisible, sidePanel = true)
        root.addView(options, FrameLayout.LayoutParams(MATCH, MATCH))
        // Over everything the reader draws, controls and sheets too, as a backlight would dim.
        comfortLayer = ComfortLayerView(context)
        root.addView(comfortLayer, FrameLayout.LayoutParams(MATCH, MATCH))
        comfortLayer.apply(ComfortSettings.load(context))
        // The owner's choice (X7): the bars float over a comic, whose page keeps its size.
        pagePreview = ReaderPagePreviewController(root, surface, bars.top, bars.bottom, listOf(options), makesRoom = false)
        focusedControl = ReaderControlFocusPolicy.initialIndex(focusables.size) ?: 0
        OverlayButtons.light(thirdsButton, reading.fit == ComicFit.THIRDS)
        thirdsButton.isSelected = reading.fit == ComicFit.THIRDS
        setControlsVisible(false)
        return root
    }

    /** One surface's view: the same tiled image view for each, told apart by [listen]. */
    private fun pageView(context: Context) = SubsamplingScaleImageView(context).apply {
        setBackgroundColor(Color.BLACK)
        setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE)
        setPanLimit(SubsamplingScaleImageView.PAN_LIMIT_INSIDE)
        setMaxScale(6f)
        setDoubleTapZoomDpi(240)
        setDoubleTapZoomDuration(180)
        // C7: taps and swipes as the keys; the view keeps its own drag, pinch and double tap.
        val gestures = PageGestures(this)
        setOnTouchListener { _, event -> gestures.onTouch(event); false }
    }

    /**
     * A finger on the page in front (C7, [ComicTouch]): a tap, once it is
     * sure not to be the first of a double tap, and a fling across. A gesture
     * with a second finger, or one in which the page's scale changed (a pinch,
     * a double tap and drag), turns nothing.
     */
    private inner class PageGestures(private val view: SubsamplingScaleImageView) : GestureDetector.SimpleOnGestureListener() {
        private val detector = GestureDetector(view.context, this)
        private var pinched = false
        private var startScale = 0f

        fun onTouch(event: MotionEvent) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { pinched = false; startScale = view.scale }
                MotionEvent.ACTION_POINTER_DOWN -> pinched = true
            }
            detector.onTouchEvent(event)
        }

        override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
            if (view !== image || pinched) return false
            when (ComicTouch.tap(event.x, view.width, rtl(), controlsVisible)) {
                ComicTouch.Tap.BACK -> moveReadingFlow(false)
                ComicTouch.Tap.FORWARD -> moveReadingFlow(true)
                ComicTouch.Tap.CONTROLS -> toggleControls()
            }
            return true
        }

        override fun onFling(start: MotionEvent?, end: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            val from = start ?: return false
            if (view !== image || !view.isReady || endCard.isOpen) return false
            val zoomed = zoom.active || view.scale > baseScale(view) * (1f + ComicZoom.CLOSE)
            val scaled = startScale > 0f && abs(view.scale - startScale) > startScale * 0.01f
            val turn = ComicTouch.swipe(end.x - from.x, end.y - from.y, view.width, rtl(), zoomed, pinched || scaled)
            if (turn != 0) turnWholePage(turn)
            return turn != 0
        }
    }

    private fun listen(slot: PageSurface.Slot) {
        slot.view.setOnImageEventListener(object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
            override fun onReady() = onSlotReady(slot)

            override fun onImageLoadError(e: Exception) = onSlotFailed(slot, "This page could not be decoded")

            override fun onTileLoadError(e: Exception) {
                if (slot === surface.front) showPageError("Part of this page could not be decoded")
            }
        })
        // A pinch or a double tap on the page shown is a zoom of your own, kept from page to page.
        slot.view.setOnStateChangedListener(object : SubsamplingScaleImageView.OnStateChangedListener {
            override fun onScaleChanged(newScale: Float, origin: Int) {
                if (slot !== surface.front) return
                if (origin != SubsamplingScaleImageView.ORIGIN_TOUCH && origin != SubsamplingScaleImageView.ORIGIN_DOUBLE_TAP_ZOOM) return
                if (magnified != null || !image.isReady) return
                val center = image.center ?: return
                setZoom(ComicZoom.of(newScale, baseScale(), center.x, image.sWidth.toFloat()), snap = false)
            }

            override fun onCenterChanged(newCenter: PointF, origin: Int) {
                if (slot !== surface.front || !zoom.active || magnified != null || image.sWidth <= 0) return
                if (origin == SubsamplingScaleImageView.ORIGIN_TOUCH || origin == SubsamplingScaleImageView.ORIGIN_FLING)
                    zoom = zoom.copy(anchorX = (newCenter.x / image.sWidth).coerceIn(0f, 1f))
            }
        })
    }

    override fun onShow() {
        comfortLayer.apply(ComfortSettings.load(host.viewContext))
        if (manifest == null && manifestJob?.isActive != true) {
            loadManifest(currentSourceItemId, startAtEnd = openAtEnd)
        } else if (manifest != null && !showsCurrentPage()) {
            loadPage()
        } else if (manifest != null) {
            planNeighbours()
        }
    }

    override fun onHide() {
        saveCurrent(immediate = true)
        manifestJob?.cancel()
        // Pages still on their way stop; the ones decoded stay for when the reader comes back.
        surface.slots.filter { it !== surface.front && !it.ready }.forEach { it.clear() }
        previewJob?.cancel()
        endJob?.cancel()
        // A copy: a cancelled look may finish (and leave the map) inside cancel() itself.
        measuring.values.toList().forEach { it.cancel() }
        measuring.clear()
        com.pocketds.hub.ui.Artwork.bind(previewImage, com.pocketds.hub.ui.Artwork.loader(api, host.viewContext), null)
        regionHint.removeCallbacks(hideRegionHint)
        waitPill.removeCallbacks(showWait)
        pageMap.dismiss()
        magnify(false)
        options.dismiss()
        previewCard.visibility = View.GONE
    }

    override fun onDestroyView() {
        pagePreview.dispose()
        saveCurrent(immediate = true)
        surface.recycleAll()
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
        if (pageGrid.onPad(action)) return true
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
        if (pageGrid.isOpen) { pageGrid.hide(); return true }
        return false
    }

    /**
     * The player's look over the page: a round Close, the series with the
     * issue and page under it, round steps between issues and zoom, and the
     * tools that open something named in words; Thirds lit white while on.
     */
    private fun buildTopBar() {
        val row = bars.topRow
        // The bar is a row of 44dp controls, floating with its own corners.
        row.setPadding(dp(4), 0, dp(4), 0)
        row.addView(round(AppIcon.CLOSE, "Close reader") { host.back() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        row.addView(LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.VERTICAL
            titleView = TextView(context).apply {
                text = title
                Type.apply(this, Type.Role.HEADING, 15f)
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            addView(titleView)
            subtitleView = TextView(context).apply {
                textSize = 11.5f
                setTextColor(ReaderBars.SOFT_TEXT)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(1), 0, 0)
            }
            addView(subtitleView)
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(8); marginEnd = dp(8) })
        row.addView(round(AppIcon.PREVIOUS_ITEM, "Previous issue") { movePublication(-1) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        thirdsButton = pill("Thirds", "Read each page in thirds", AppIcon.THIRDS, ::toggleThirds)
        row.addView(thirdsButton, LinearLayout.LayoutParams(WRAP, dp(44)).apply { marginStart = dp(2) })
        row.addView(round(AppIcon.ZOOM_OUT, "Zoom out") { zoom(.8f) }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(2) })
        row.addView(round(AppIcon.ZOOM_IN, "Zoom in") { zoom(1.25f) }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(2) })
        row.addView(pill("Display", "Reading options", AppIcon.APPEARANCE) { showReadingOptions() }, LinearLayout.LayoutParams(WRAP, dp(44)).apply { marginStart = dp(2) })
        row.addView(round(AppIcon.COMFORT, "Comfort") { showComfort() }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(2) })
        row.addView(round(AppIcon.PAD, "Keys") { showKeys() }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(2) })
        row.addView(round(AppIcon.NEXT_ITEM, "Next issue") { movePublication(1) }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(2) })
    }

    private fun buildBottomBar() {
        bars.bottomRow.setPadding(dp(4), 0, dp(4), 0)
        val navigation = LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bars.bottomRow.addView(navigation, LinearLayout.LayoutParams(MATCH, MATCH))
        navigation.addView(round(AppIcon.PREVIOUS, "Previous page") { turnWholePage(-1) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        seek = SeekBar(host.viewContext).apply {
            max = 1
            contentDescription = "Publication position"
            // The prototype's white line on a faint track.
            progressTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.argb(64, 255, 255, 255))
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
                    previewJob?.cancel(); previewCard.visibility = View.GONE
                    state?.seek(seekBar.progress)
                    arriveAtEnd = false
                    forward = true
                    loadPage()
                }
            })
        }
        focusables += seek
        navigation.addView(seek, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
            marginStart = dp(6)
            marginEnd = dp(6)
        })
        positionView = TextView(host.viewContext).apply {
            textSize = 12f
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setTextColor(ReaderBars.SOFT_TEXT)
        }
        navigation.addView(positionView, LinearLayout.LayoutParams(WRAP, MATCH).apply { marginEnd = dp(6) })
        navigation.addView(round(AppIcon.PAGES, "Pages") { showPages() }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(2) })
        // The last control registered is the forward page (ReaderControlFocusPolicy).
        navigation.addView(round(AppIcon.NEXT, "Next page") { turnWholePage(1) }, LinearLayout.LayoutParams(dp(44), dp(44)))
    }

    /** A quiet glass pill over the page: "Part 2 of 3", "Loading page 5". */
    private fun hintPill() = TextView(host.viewContext).apply {
        textSize = 12f; setTextColor(Color.WHITE); setPadding(dp(12), dp(6), dp(12), dp(6)); visibility = View.GONE
        OverlayButtons.panel(this, 999f)
    }

    private fun round(icon: AppIcon, label: String, click: () -> Unit): View =
        register(OverlayButtons.round(host.viewContext, colors.focusRing, icon, label, click))

    private fun pill(label: String, description: String, icon: AppIcon? = null, click: () -> Unit): TextView =
        register(OverlayButtons.pill(host.viewContext, colors.focusRing, label, description, icon, onTap = click))

    /** In the pad's order through the controls, remembering which one had focus. */
    private fun <T : View> register(view: T): T = view.apply {
        FocusDecorator.attach(this, ringVisible, scale = false)
        FocusDecorator.listen(this, ringVisible) { v, focused ->
            if (focused) focusedControl = focusables.indexOf(v).coerceAtLeast(0)
        }
        focusables += this
    }

    private fun refreshKeys() {
        if (::bars.isInitialized) bars.keys.setHints(ReaderPadMap.hints(padState().copy(controlsVisible = true)))
    }

    private fun showKeys() = ReaderKeys.show(options, padState().copy(controlsVisible = false))

    private fun showComfort() = ComfortSheet.show(options, colors, ReaderKind.COMIC, ::applyComfort)

    private fun applyComfort(value: ScreenComfort) = comfortLayer.apply(value)

    private fun loadManifest(sourceItemId: String, startAtEnd: Boolean = false) {
        manifestJob?.cancel()
        generation++
        val requestGeneration = generation
        val changed = sourceItemId != currentSourceItemId
        currentSourceItemId = sourceItemId
        if (changed) host.pageArtworkChanged()
        pendingStartAtEnd = startAtEnd
        pending = null
        waitPill.removeCallbacks(showWait)
        waitPill.visibility = View.GONE
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
        contents.keys.removeAll { it.publication != value.sourceItemId }
        titleView.text = ReaderTitleFormatter.heading(value.seriesTitle, value.title, title)
        issueName = ReaderTitleFormatter.issue(value.kind, value.seriesTitle, value.title, value.number)
        val start = if (startAtEnd) value.pageCount - 1 else value.currentPage
        // The third you were on comes back when the issue opens where you left it.
        val step = if (startAtEnd) Int.MAX_VALUE
            else DomainPreferences.comicPlace(host.viewContext, workId)?.stepFor(value.sourceItemId, start, stepsFor(start)) ?: 0
        arriveAtEnd = startAtEnd
        forward = !startAtEnd
        state = PagedImageState(value.pageCount, start, ::stepsFor, step)
        seek.max = max(1, value.pageCount - 1)
        loadPage()
        host.refreshHints()
    }

    // ------------------------------------------------------- the surfaces

    /** The page the state is on is the one showing, decoded. */
    private fun showsCurrentPage(): Boolean {
        val key = currentKey() ?: return false
        return surface.front.key == key && surface.front.ready
    }

    private fun currentKey(): PageKey? {
        val value = manifest ?: return null
        val position = state ?: return null
        return PageKey(value.sourceItemId, position.pageIndex.coerceIn(0, value.pageCount - 1))
    }

    /**
     * Shows the state's page: at once when a surface behind holds it decoded,
     * else as soon as it decodes, the page before staying on screen until then
     * rather than going to black (C3).
     */
    private fun loadPage() {
        val key = currentKey() ?: return
        if (repository == null) {
            loading.text = "The real reader requires a configured Hub connection"
            loading.visibility = View.VISIBLE
            return
        }
        magnify(false)
        updatePosition()
        pending = key
        measureContent(key)
        val slot = surface.slotFor(key)
        if (slot != null && slot.ready) {
            reveal(slot)
            return
        }
        if (slot == null) {
            val wanted = PageSlots.wanted(key.page, manifest?.pageCount ?: 0, forward).map { PageKey(key.publication, it) }
            surface.spare(wanted)?.let { decodeInto(it, key) }
        }
        waitFor(key.page)
    }

    /** A page on its way: nothing on screen yet says so at once; a page on screen stays, and says so if it takes a while. */
    private fun waitFor(page: Int) {
        waitPill.removeCallbacks(showWait)
        if (!surface.front.ready) {
            loading.text = "Loading page ${page + 1}…"
            loading.visibility = View.VISIBLE
            refreshKeys()
            return
        }
        waitPill.text = "Loading page ${page + 1}…"
        waitPill.postDelayed(showWait, WAIT_MS)
    }

    private val showWait = Runnable { if (::waitPill.isInitialized && pending != null) waitPill.visibility = View.VISIBLE }

    private fun decodeInto(slot: PageSurface.Slot, key: PageKey) {
        val pages = repository ?: return
        slot.clear()
        slot.key = key
        slot.job = uiScope.launch {
            try {
                val file = pages.obtain(readingSession.api.readingPublicationPageUrl(workId, key.publication, key.page))
                if (slot.key != key) return@launch
                surface.load(slot, key, file.absolutePath)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                if (slot.key != key) return@launch
                slot.key = null
                if (key == pending) showPageError("Page ${key.page + 1} could not be loaded")
            }
        }
    }

    private fun onSlotReady(slot: PageSurface.Slot) {
        val key = slot.key ?: return
        // A page of an issue already left behind: its surface goes to the next page wanted.
        if (key.publication != manifest?.sourceItemId) return
        slot.ready = true
        measured[key.page] = slot.view.sWidth to slot.view.sHeight
        if (key == pending) reveal(slot) else preposition(slot)
    }

    private fun onSlotFailed(slot: PageSurface.Slot, message: String) {
        val key = slot.key
        slot.ready = false
        slot.key = null
        if (key != null && key == pending) showPageError(message)
        else if (slot === surface.front) showPageError(message)
    }

    /** The page asked for, decoded: to the front, placed as it opens, its place saved. */
    private fun reveal(slot: PageSurface.Slot) {
        val position = state ?: return
        val key = slot.key ?: return
        pending = null
        magnified = null
        waitPill.removeCallbacks(showWait)
        waitPill.visibility = View.GONE
        surface.show(slot)
        loading.visibility = View.GONE
        refreshKeys()
        // The decoded size outranks the manifest's: the steps may change, and
        // a page reached going back still opens on its last one.
        if (arriveAtEnd) position.jump(key.page, Int.MAX_VALUE) else position.refit()
        applyViewport()
        updatePosition()
        visibleCheckpoint = readingSession.key(workId, key.publication, "pages") to ReadingLocation(pageIndex = key.page)
        saveCurrent(immediate = false)
        planNeighbours()
    }

    /**
     * The pages either side, decoded behind the one shown ([PageSlots]): the
     * next the way you are reading first. A surface already holding one keeps
     * it; one holding nothing wanted is given back.
     */
    private fun planNeighbours() {
        val value = manifest ?: return
        val page = state?.pageIndex ?: return
        val wanted = PageSlots.wanted(page, value.pageCount, forward).map { PageKey(value.sourceItemId, it) }
        wanted.forEach(::measureContent)
        val plan = PageSlots.assign(surface.slots.map { it.key }, wanted)
        surface.slots.forEachIndexed { index, slot ->
            val target = plan[index]
            if (slot === surface.front || slot.key == target) return@forEachIndexed
            if (target == null) slot.clear() else decodeInto(slot, target)
        }
        prefetchBeyond(page)
    }

    /** The page after the next, as bytes only, so its decode starts from the disk. */
    private fun prefetchBeyond(page: Int) {
        val value = manifest ?: return
        val pageRepository = repository ?: return
        val candidate = if (forward) page + 2 else page - 2
        if (candidate !in 0 until value.pageCount) return
        uiScope.launch(Dispatchers.IO) {
            runCatching { pageRepository.obtain(readingSession.api.readingPublicationPageUrl(workId, value.sourceItemId, candidate)) }
        }
    }

    /** A page either side, decoded behind: placed now as it will open, so the swap shows it exactly so. */
    private fun preposition(slot: PageSurface.Slot) {
        val key = slot.key ?: return
        if (!slot.ready || slot === surface.front) return
        val current = state?.pageIndex ?: return
        val (scale, center) = placement(slot.view, step = 0, atEnd = key.page < current) ?: return
        slot.view.setScaleAndCenter(scale, center)
    }

    private fun prepositionAll() = surface.slots.forEach(::preposition)

    private fun showPageError(message: String) {
        waitPill.removeCallbacks(showWait)
        waitPill.visibility = View.GONE
        loading.text = "$message\nSelect retries"
        loading.visibility = View.VISIBLE
        refreshKeys()
    }

    // ------------------------------------------------------------ the page

    /** How many steps a page takes: thirds from its content's shape (C2, C5), one while zoomed or not in thirds. */
    private fun stepsFor(page: Int): Int {
        if (reading.fit != ComicFit.THIRDS || zoom.active) return 1
        val (width, height) = pageSize(page) ?: return DEFAULT_STEPS
        val content = contentFor(manifest?.sourceItemId?.let { PageKey(it, page) })
        return ViewportStepPlanner.count((width * content.width).roundToInt(), (height * content.height).roundToInt(), viewWidth(), viewHeight())
    }

    // ------------------------------------------------- the content (C5)

    /** A page's content inside its paper, while Trim margins is on and its thumbnail has been looked at; else all of it. */
    private fun contentFor(key: PageKey?): PageContent =
        if (!reading.trim || key == null) PageContent.WHOLE else contents[key] ?: PageContent.WHOLE

    private fun contentOf(view: SubsamplingScaleImageView): PageContent = contentFor(surface.slotOf(view)?.key)

    /**
     * Looks at [key]'s page on the hub's thumbnail, [PageBounds.THUMB_WIDTH]
     * across (a few kilobytes, never the scan), for the paper round it. A page
     * on screen is placed again once its content is known, unless it was moved
     * or zoomed meanwhile.
     */
    private fun measureContent(key: PageKey) {
        if (!reading.trim || key in contents || measuring[key]?.isActive == true) return
        val pages = repository ?: return
        measuring[key] = uiScope.launch {
            val found = try {
                val file = pages.obtain(readingSession.api.readingPublicationThumbUrl(workId, key.publication, key.page, PageBounds.THUMB_WIDTH))
                kotlinx.coroutines.withContext(Dispatchers.Default) { contentOfThumbnail(file.path) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } finally {
                measuring.remove(key)
            }
            contents[key] = found ?: PageContent.WHOLE
            if (found == null || !found.trimmed || key.publication != manifest?.sourceItemId) return@launch
            val slot = surface.slotFor(key) ?: return@launch
            if (slot === surface.front) {
                if (slot.ready && !zoom.active && magnified == null && key == currentKey()) {
                    state?.refit()
                    applyViewport(animated = true)
                    updatePosition()
                }
            } else preposition(slot)
        }
    }

    /** The content of the thumbnail at [path], decoded small whatever size the hub sent. */
    private fun contentOfThumbnail(path: String): PageContent? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= PageBounds.THUMB_WIDTH) sample *= 2
        val decoded = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return null
        val width = PageBounds.THUMB_WIDTH.coerceAtMost(decoded.width)
        val height = (decoded.height.toLong() * width / decoded.width).toInt().coerceAtLeast(1)
        val small = if (decoded.width == width) decoded else Bitmap.createScaledBitmap(decoded, width, height, true)
        val pixels = IntArray(width * height)
        small.getPixels(pixels, 0, width, 0, 0, width, height)
        if (small !== decoded) small.recycle()
        decoded.recycle()
        return PageBounds.detect(IntArray(pixels.size) { PageBounds.luma(pixels[it]) }, width, height)
    }

    private fun pageSize(page: Int): Pair<Int, Int>? = measured[page]
        ?: manifest?.pages?.firstOrNull { it.index == page }?.takeIf { it.width > 0 && it.height > 0 }?.let { it.width to it.height }

    private fun viewWidth(): Int = image.width.takeIf { it > 0 } ?: root.width.takeIf { it > 0 } ?: root.resources.displayMetrics.widthPixels
    private fun viewHeight(): Int = image.height.takeIf { it > 0 } ?: root.height.takeIf { it > 0 } ?: root.resources.displayMetrics.heightPixels

    /** The scale the fit itself reads at: the whole page, or its width; its content's, while trimmed (C5). */
    private fun baseScale(view: SubsamplingScaleImageView = image): Float =
        if (reading.fit == ComicFit.WHOLE) wholeScale(view) else fitWidthScale(view)

    private fun wholeScale(view: SubsamplingScaleImageView = image): Float {
        val content = contentOf(view)
        if (view.sWidth <= 0 || view.sHeight <= 0 || !content.trimmed) return view.minScale
        return min(view.width / (view.sWidth * content.width), view.height / (view.sHeight * content.height)).toFloat()
            .coerceIn(view.minScale, view.maxScale)
    }

    private fun fitWidthScale(view: SubsamplingScaleImageView = image): Float =
        if (view.sWidth <= 0) view.minScale
        else (view.width / (view.sWidth * contentOf(view).width)).toFloat().coerceIn(view.minScale, view.maxScale)

    private fun rtl(): Boolean = (reading.direction ?: manifest?.direction) == "rtl"

    /**
     * Where [view] puts its page, by the zoom kept, else the fit and [step]:
     * at the top, or [atEnd] at the bottom (the last step) for a page reached
     * going back.
     */
    private fun placement(view: SubsamplingScaleImageView, step: Int, atEnd: Boolean): Pair<Float, PointF>? {
        if (!view.isReady || view.sWidth <= 0 || view.sHeight <= 0) return null
        val width = view.sWidth.toFloat()
        val height = view.sHeight.toFloat()
        if (zoom.active) {
            val scale = zoom.scaleFor(baseScale(view), view.minScale, view.maxScale)
            val (x, y) = zoom.center(width, height, view.width / scale, view.height / scale, atEnd)
            return scale to PointF(x, y)
        }
        // The content is the page (C5): its own box inside the paper, the whole page when untrimmed.
        val content = contentOf(view)
        val left = (content.left * width).toFloat()
        val top = (content.top * height).toFloat()
        val contentWidth = (content.width * width).toFloat()
        val contentHeight = (content.height * height).toFloat()
        val middleX = left + contentWidth / 2f
        return when (reading.fit) {
            ComicFit.WHOLE -> wholeScale(view) to PointF(middleX, top + contentHeight / 2f)
            ComicFit.WIDTH -> {
                val scale = fitWidthScale(view)
                val visible = view.height / scale
                val y = when {
                    visible >= contentHeight -> top + contentHeight / 2f
                    atEnd -> top + contentHeight - visible / 2f
                    else -> top + visible / 2f
                }
                scale to PointF(middleX, y)
            }
            ComicFit.THIRDS -> {
                val steps = ViewportStepPlanner.fitWidth(contentWidth.roundToInt(), contentHeight.roundToInt(), view.width, view.height)
                val index = if (atEnd) steps.lastIndex else step.coerceIn(0, steps.lastIndex)
                fitWidthScale(view) to PointF(middleX, top + ((steps[index].top + steps[index].bottom) / 2 * contentHeight).toFloat())
            }
        }
    }

    /** Places the page shown as the fit, the zoom and the step say, and says where it is. */
    private fun applyViewport(animated: Boolean = false) {
        if (!image.isReady) return
        val position = state ?: return
        val atEnd = arriveAtEnd
        arriveAtEnd = false
        val (scale, center) = placement(image, position.viewportIndex, atEnd) ?: return
        place(scale, center, animated)
        if (zoom.active) {
            showFreeMap(scale, center)
            return
        }
        if (reading.fit != ComicFit.THIRDS) return
        val content = contentOf(image)
        val steps = ViewportStepPlanner.fitWidth((image.sWidth * content.width).roundToInt(), (image.sHeight * content.height).roundToInt(),
            image.width, image.height)
        val index = position.viewportIndex.coerceIn(0, steps.lastIndex)
        // With the controls open the bar says which part; the pill and the map would sit under it.
        if (steps.size > 1 && !controlsVisible) {
            regionHint.text = ReaderTitleFormatter.part(index + 1, steps.size)
            regionHint.visibility = View.VISIBLE
            regionHint.removeCallbacks(hideRegionHint)
            regionHint.postDelayed(hideRegionHint, PageMapView.SHOW_MS)
            pageMap.showStep(image.sWidth, image.sHeight, steps.map(content::onPage), index)
        }
        manifest?.let { DomainPreferences.setComicPlace(host.viewContext, workId, ComicPlace(it.sourceItemId, position.pageIndex, index)) }
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
        if (reading.fit == ComicFit.THIRDS && !zoom.active) {
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
        // At the fit, over the content (C5); zoomed in, over the whole page.
        val content = if (zoom.active) PageContent.WHOLE else contentOf(image)
        val target = if (horizontal) {
            ComicPanPolicy.step(center.x, (image.sWidth * content.width).roundToInt(), image.width / image.scale, sign,
                from = (image.sWidth * content.left).toFloat())?.let { PointF(it, center.y) }
        } else {
            ComicPanPolicy.step(center.y, (image.sHeight * content.height).roundToInt(), image.height / image.scale, sign,
                from = (image.sHeight * content.top).toFloat())?.let { PointF(center.x, it) }
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
        if (!::surface.isInitialized) return
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
            forward = delta > 0
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
                forward = true
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
                forward = false
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

    private fun toggleThirds() = setFit(if (reading.fit == ComicFit.THIRDS) ComicFit.WHOLE else ComicFit.THIRDS)

    /** A fit chosen for this series: kept for it, the zoom let go, the page placed again. */
    private fun setFit(fit: ComicFit) {
        reading = reading.copy(fit = fit)
        DomainPreferences.setComicView(host.viewContext, workId, reading)
        zoom = ComicZoom()
        val value = manifest
        val page = state?.pageIndex ?: value?.currentPage ?: 0
        if (value != null) state = PagedImageState(value.pageCount, page, ::stepsFor, 0)
        // Lit while on, like a filter: white.
        OverlayButtons.light(thirdsButton, fit == ComicFit.THIRDS)
        thirdsButton.isSelected = fit == ComicFit.THIRDS
        applyViewport()
        updatePosition()
        prepositionAll()
        host.refreshHints()
    }

    /** Trim margins for this series (C5), kept for it; the page placed again, by its content or all of it. */
    private fun setTrim(on: Boolean) {
        reading = reading.copy(trim = on)
        DomainPreferences.setComicView(host.viewContext, workId, reading)
        val value = manifest
        val page = state?.pageIndex ?: value?.currentPage ?: 0
        if (value != null) state = PagedImageState(value.pageCount, page, ::stepsFor, 0)
        if (on) currentKey()?.let(::measureContent)
        applyViewport()
        updatePosition()
        prepositionAll()
        planNeighbours()
    }

    private fun setDirection(direction: String?) {
        reading = reading.copy(direction = direction)
        DomainPreferences.setComicView(host.viewContext, workId, reading)
        applyViewport()
        prepositionAll()
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
        if (!next.active && reading.fit == ComicFit.THIRDS && image.isReady) {
            val content = contentOf(image)
            val steps = ViewportStepPlanner.fitWidth((image.sWidth * content.width).roundToInt(), (image.sHeight * content.height).roundToInt(),
                image.width, image.height)
            val y = (((image.center?.y ?: 0f) / image.sHeight.coerceAtLeast(1)) - content.top) / content.height
            val nearest = steps.indices.minByOrNull { abs((steps[it].top + steps[it].bottom) / 2 - y) } ?: 0
            position.jump(position.pageIndex, nearest)
            if (snap) applyViewport(animated = true)
        } else position.refit()
        updatePosition()
        prepositionAll()
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
                options.choice(fit.label, selected = reading.fit == fit) { setFit(fit); showReadingOptions(tab) }
            }
            options.startGroup()
            options.choice("Trim margins", "Leave the paper round each page out, so the page reads larger",
                selected = reading.trim) { setTrim(!reading.trim); showReadingOptions(tab) }
            options.section("Every series")
            options.choice("Open every series this way", "New series open as ${everySeries.label.lowercase()}",
                selected = everySeries == reading.fit) {
                DomainPreferences.setComicDefaultFit(context, reading.fit)
                showReadingOptions(tab)
            }
        } else {
            val library = manifest?.direction ?: "ltr"
            options.choice("As the library reads", if (library == "rtl") "Right to left" else "Left to right",
                selected = reading.direction == null) { setDirection(null); showReadingOptions(tab) }
            options.choice("Left to right", selected = reading.direction == "ltr") { setDirection("ltr"); showReadingOptions(tab) }
            options.choice("Right to left", selected = reading.direction == "rtl") { setDirection("rtl"); showReadingOptions(tab) }
        }
        options.focusBody()
    }

    /**
     * The scrubber's preview: the page's thumbnail from the hub (C4), a few
     * kilobytes rather than the whole scan, asked for once the thumb rests.
     */
    private fun showPagePreview(page: Int) {
        previewJob?.cancel()
        previewLabel.text = "Page ${page + 1}"; previewCard.visibility = View.VISIBLE
        val value = manifest ?: return
        previewJob = uiScope.launch {
            delay(PREVIEW_REST_MS)
            if (seek.progress != page || previewCard.visibility != View.VISIBLE) return@launch
            com.pocketds.hub.ui.Artwork.bind(previewImage, com.pocketds.hub.ui.Artwork.loader(api, host.viewContext), thumbnail(value, page),
                onMissing = { previewLabel.text = "Page ${page + 1} · preview unavailable" })
        }
    }

    private fun thumbnail(value: ReadingPublicationManifest, page: Int): String =
        readingSession.api.readingPublicationThumbUrl(workId, value.sourceItemId, page, PageGrid.THUMB_WIDTH)

    /** The Pages grid (C4): every page of the issue, the one you are on under the cursor; the bars step aside. */
    private fun showPages() {
        val value = manifest ?: return
        val position = state ?: return
        setControlsVisible(false)
        pageGrid.show("Pages", ReaderTitleFormatter.subtitle(issueName, position.pageIndex + 1, value.pageCount),
            value.pageCount, position.pageIndex) { page -> thumbnail(value, page) }
        refreshKeys()
        host.refreshHints()
    }

    /** A page chosen in the grid: it opens at its top, the controls out of the way. */
    private fun jumpTo(page: Int) {
        val position = state ?: return
        setControlsVisible(false)
        if (page == position.pageIndex) return
        forward = page > position.pageIndex
        position.seek(page)
        arriveAtEnd = false
        loadPage()
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
        if (visible) {
            pageMap.dismiss()
            regionHint.removeCallbacks(hideRegionHint)
            regionHint.visibility = View.GONE
        }
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
        /** Before a page's size is known, a comic page's three. */
        const val DEFAULT_STEPS = 3
        /** The right stick at full push: screens a second. */
        const val GLIDE = 1.2f
        /** L3 held: how much closer. */
        const val MAGNIFY = 2f
        /** How long a page may take before "Loading page 5" says so over the page you were on. */
        const val WAIT_MS = 300L
        /** How long the scrubber's thumb rests before its page's thumbnail is asked for. */
        const val PREVIEW_REST_MS = 120L
    }
}
