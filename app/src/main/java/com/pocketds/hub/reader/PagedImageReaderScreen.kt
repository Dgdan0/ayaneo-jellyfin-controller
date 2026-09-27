package com.pocketds.hub.reader

import com.pocketds.hub.ui.ThemeGradientDrawable
import android.graphics.Color
import android.graphics.PointF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import coil.request.ImageRequest
import com.pocketds.hub.ui.ChoiceOverlay
import com.pocketds.hub.ui.AppIcon
import com.pocketds.hub.ui.AppIconDrawable
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.ReadingPublicationManifest
import com.pocketds.hub.model.ReadingPublicationPage
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Theme
import com.pocketds.hub.ui.activateOnTap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

/** Real Kavita comic/manga reader backed by sanitized Hub page routes. */
class PagedImageReaderScreen(
    private val api: HubApi,
    private val workId: String,
    private val initialSourceItemId: String,
    initialTitle: String,
    private val ringVisible: () -> Boolean,
    private val onProgressChanged: () -> Unit = {},
    private val readingList: List<com.pocketds.hub.model.ServerReadingListEntry> = emptyList(),
    private val readingListIndex: Int = -1,
    private val openAtEnd: Boolean = false
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
    private var previewJob: Job? = null
    private var previewRequest: coil.request.Disposable? = null
    private var directionOverride: String? = null
    private var fitWidth = false
    private var repository: ReaderPageRepository? = null

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var progress: ReadingProgress
    private lateinit var readingSession: ReadingProgress.Session
    private lateinit var manifestCache: ReadingManifestCache
    private var visibleCheckpoint: Pair<ReadingCheckpointKey, ReadingLocation>? = null
    private var checkpointErrorShown = false
    private var manifestJob: Job? = null
    private var pageJob: Job? = null
    private var generation = 0L
    private var manifest: ReadingPublicationManifest? = null
    private var state: PagedImageState? = null
    private var currentSourceItemId = initialSourceItemId
    private var controlsVisible = true
    private var thirdsEnabled = false
    private var suppressSeek = false
    private var pendingStartAtEnd = false
    private val focusables = mutableListOf<View>()
    private var focusedControl = 0

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        progress = ReadingProgress.get(host.viewContext)
        readingSession = progress.session()
        manifestCache = ReadingManifestCache(java.io.File(host.viewContext.cacheDir,"reading-manifests"))
        colors = Theme.colors(host.viewContext)
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
                    applyViewport()
                    val publication = manifest ?: return
                    val page = this@PagedImageReaderScreen.state?.pageIndex ?: return
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
        buildTopBar()
        buildBottomBar()
        previewCard = LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; visibility = View.GONE
            setPadding(dp(8),dp(8),dp(8),dp(8)); setBackgroundColor(0xEE141518.toInt())
        }
        previewImage = ImageView(host.viewContext).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        previewLabel = TextView(host.viewContext).apply { textSize = 12f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        previewCard.addView(previewImage, LinearLayout.LayoutParams(dp(88),dp(112)))
        previewCard.addView(previewLabel)
        root.addView(previewCard, FrameLayout.LayoutParams(dp(104),WRAP,Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin=dp(72) })
        regionHint = TextView(host.viewContext).apply {
            textSize=12f;setTextColor(Color.WHITE);setPadding(dp(12),dp(6),dp(12),dp(6));setBackgroundColor(0xB3141518.toInt());visibility=View.GONE
        }
        root.addView(regionHint,FrameLayout.LayoutParams(WRAP,WRAP,Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply {bottomMargin=dp(80)})
        options = ChoiceOverlay(host.viewContext, colors, ringVisible, sidePanel=true)
        root.addView(options,FrameLayout.LayoutParams(MATCH,MATCH))
        pagePreview = ReaderPagePreviewController(root, image, topBar, bottomBar, listOf(options))
        focusedControl = ReaderControlFocusPolicy.initialIndex(focusables.size) ?: 0
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
        previewRequest?.dispose()
        regionHint.removeCallbacks(hideRegionHint)
        options.dismiss()
        previewCard.visibility=View.GONE
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

    override fun hints(): List<ButtonHint> = listOf(
        ButtonHint.activate(if (controlsVisible) "Choose" else "Forward"),
        ButtonHint.back(if (controlsVisible) "Hide controls" else "Backward"),
        ButtonHint.primary("Next page"),
        ButtonHint.secondary("Previous page")
    )

    override fun onPad(action: PadAction): Boolean {
        if(options.onPad(action)) return true
        when (action) {
            PadAction.Menu -> toggleControls()
            PadAction.Back -> if (controlsVisible) setControlsVisible(false) else moveReadingFlow(false)
            PadAction.Activate -> if (controlsVisible) {
                focusables.getOrNull(focusedControl)?.performClick()
            } else {
                moveReadingFlow(true)
            }
            PadAction.Primary -> turnWholePage(1)
            PadAction.Secondary -> turnWholePage(-1)
            PadAction.Refresh -> if (loading.visibility == View.VISIBLE) {
                if (manifest == null) loadManifest(currentSourceItemId) else loadPage()
            } else host.back()
            is PadAction.Section -> zoom(if (action.delta > 0) 1.2f else 1f / 1.2f)
            is PadAction.Page -> zoom(if (action.direction == Direction.DOWN) 1.35f else 1f / 1.35f)
            is PadAction.Step -> if (controlsVisible) moveControlFocus(action.direction) else scrollDirection(action.direction)
        }
        return true
    }

    // Android edge-back leaves the reader; physical B follows the reading flow.
    override fun onSystemBack(): Boolean { if(options.isOpen) { options.cancel(); return true }; return false }

    private fun buildTopBar() {
        topBar = LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(5), dp(8), dp(5))
            setBackgroundColor(0xD9141518.toInt())
        }
        root.addView(topBar, FrameLayout.LayoutParams(MATCH, dp(58), Gravity.TOP))
        topBar.addView(control("×", "Close reader", { host.back() }))
        topBar.addView(control("↶", "Previous issue", { movePublication(-1) }))
        titleView = TextView(host.viewContext).apply {
            text = title
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.WHITE)
            maxLines = 1
            setPadding(dp(8), 0, dp(8), 0)
        }
        topBar.addView(titleView, LinearLayout.LayoutParams(0, MATCH, 1f))
        thirdsButton = control("⅓", "Toggle reading in thirds", ::toggleThirds)
        topBar.addView(thirdsButton)
        topBar.addView(control("zoom-out", "Zoom out", { zoom(.8f) }))
        topBar.addView(control("zoom-in", "Zoom in", { zoom(1.25f) }))
        topBar.addView(control("options", "Reading options", ::showReadingOptions))
        topBar.addView(control("↷", "Next issue", { movePublication(1) }))
    }

    private fun buildBottomBar() {
        bottomBar = LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(5), dp(10), dp(5))
            setBackgroundColor(0xD9141518.toInt())
        }
        root.addView(bottomBar, FrameLayout.LayoutParams(MATCH, dp(66), Gravity.BOTTOM))
        bottomBar.addView(control("‹", "Previous page", { turnWholePage(-1) }))
        seek = SeekBar(host.viewContext).apply {
            max = 1
            contentDescription = "Publication position"
            Styler.makeFocusable(this)
            FocusDecorator.attach(this, ringVisible, scale = false)
            setOnFocusChangeListener { view, focused ->
                FocusDecorator.refresh(view, focused && ringVisible())
                if (focused) focusedControl = focusables.indexOf(view).coerceAtLeast(0)
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

                override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser && !suppressSeek) { positionView.text = "Page ${value + 1} of ${seekBar.max + 1}"; showPagePreview(value) }
                }

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    previewJob?.cancel();previewRequest?.dispose();previewCard.visibility=View.GONE
                    state?.seek(seekBar.progress)
                    loadPage()
                    scheduleSave()
                }
            })
        }
        focusables += seek
        bottomBar.addView(seek, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            marginStart = dp(5)
            marginEnd = dp(5)
        })
        positionView = TextView(host.viewContext).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }
        bottomBar.addView(positionView, LinearLayout.LayoutParams(dp(128), MATCH))
        bottomBar.addView(control("›", "Next page", { turnWholePage(1) }))
    }

    private fun control(glyph: String, label: String, click: () -> Unit): TextView =
        TextView(host.viewContext).apply {
            val icon=when(glyph){ "×"->AppIcon.CLOSE; "↶"->AppIcon.PREVIOUS_ITEM; "↷"->AppIcon.NEXT_ITEM; "⅓"->AppIcon.THIRDS; "‹"->AppIcon.PREVIOUS; "›"->AppIcon.NEXT; "zoom-in"->AppIcon.ZOOM_IN; "zoom-out"->AppIcon.ZOOM_OUT; else->AppIcon.SETTINGS }
            setCompoundDrawables(AppIconDrawable(icon,Color.WHITE).apply{setBounds(0,0,dp(22),dp(22))},null,null,null)
            setPadding(dp(15),0,dp(15),0)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = label
            background = controlBackground()
            Styler.makeFocusable(this)
            FocusDecorator.attach(this, ringVisible, scale = false)
            setOnFocusChangeListener { view, focused ->
                FocusDecorator.refresh(view, focused && ringVisible())
                if (focused) focusedControl = focusables.indexOf(view).coerceAtLeast(0)
            }
            activateOnTap(click)
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(48)).apply { marginEnd = dp(3) }
            focusables += this
        }

    private fun loadManifest(sourceItemId: String, startAtEnd: Boolean = false) {
        manifestJob?.cancel()
        pageJob?.cancel()
        generation++
        val requestGeneration = generation
        currentSourceItemId = sourceItemId
        pendingStartAtEnd = startAtEnd
        loading.text = "Opening publication…"
        loading.visibility = View.VISIBLE
        manifestJob = uiScope.launch {
            when (val result = readingSession.api.readingPublication(workId, sourceItemId)) {
                is HubResult.Ok -> {
                    if (requestGeneration != generation) return@launch
                    val key = readingSession.key(workId, sourceItemId, "pages")
                    runCatching { manifestCache.save(key,result.value) }
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
                    val key=readingSession.key(workId,sourceItemId,"pages")
                    val cached=manifestCache.read(key)
                    val local=runCatching { progress.store.read(key) }.getOrNull()
                    if(cached!=null && local?.local?.pageIndex!=null) {
                        val completion = ReadingCompletionRepository.get(host.viewContext)
                        val choice=if (completion.shouldStartAtBeginning(workId))
                            ReadingResume(ReadingLocation(pageIndex = completion.pageResume(workId, local.local.pageIndex!!)))
                        else chooseReadingResume(options,progress,key,ReadingResume(local.local,local.conflicted))
                        if(choice!=null && requestGeneration==generation) {
                            applyManifest(cached.copy(currentPage=choice.location?.pageIndex ?: cached.currentPage),pendingStartAtEnd)
                            host.notify("Using cached pages · reading progress is saved on this device")
                            return@launch
                        }
                    }
                    loading.text = result.message + "\nSelect retries"
                    loading.visibility = View.VISIBLE
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
        titleView.text = ReaderTitleFormatter.format(value.seriesTitle, value.title, title)
        val start = if (startAtEnd) value.pageCount - 1 else value.currentPage
        state = PagedImageState(value.pageCount, start, if (thirdsEnabled) 3 else 1)
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
    }

    private fun moveReadingFlow(forward: Boolean) {
        if (thirdsEnabled) {
            if (forward) advance() else retreat()
            return
        }
        if (image.isReady) {
            val horizontal = image.sWidth > image.sHeight
            val rtl = (directionOverride ?: manifest?.direction) == "rtl"
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
            Direction.LEFT -> if ((directionOverride ?: manifest?.direction) == "rtl") advance() else retreat()
            Direction.RIGHT -> if ((directionOverride ?: manifest?.direction) == "rtl") retreat() else advance()
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
        return true
    }

    private fun turnWholePage(delta: Int) {
        val position = state ?: return
        if (position.turnPage(delta)) {
            loadPage()
            scheduleSave()
        } else {
            movePublication(delta)
        }
    }

    private fun advance() {
        val position = state ?: return
        if (position.advance()) {
            if (position.viewportIndex == 0) {
                loadPage()
                scheduleSave()
            } else {
                applyViewport(animated = true)
                updatePosition()
            }
            return
        }
        movePublication(1)
    }

    private fun retreat() {
        val position = state ?: return
        val previousPage = position.pageIndex
        if (position.retreat()) {
            if (position.pageIndex != previousPage) {
                loadPage()
            } else {
                applyViewport(animated = true)
                updatePosition()
            }
            scheduleSave()
            return
        }
        movePublication(-1)
    }

    private fun movePublication(delta: Int) {
        if (readingList.isNotEmpty()) {
            val index=readingListIndex.takeIf { it in readingList.indices }
                ?: readingList.indexOfFirst { it.workId==workId && it.sourceItemId==currentSourceItemId }
            val target=readingList.getOrNull(index+delta)
            if(index<0 || target==null) { host.notify(if(delta<0) "Start of reading list" else "End of reading list");return }
            saveCurrent(immediate=true)
            host.back()
            host.push(PagedImageReaderScreen(api,target.workId,target.sourceItemId,"${target.seriesTitle} · ${target.title}",ringVisible,onProgressChanged,readingList,index+delta,openAtEnd=delta<0))
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

    private fun toggleThirds() {
        val value = manifest ?: return
        val page = state?.pageIndex ?: value.currentPage
        thirdsEnabled = !thirdsEnabled
        state = PagedImageState(value.pageCount, page, if (thirdsEnabled) 3 else 1)
        thirdsButton.setCompoundDrawables(AppIconDrawable(AppIcon.THIRDS,if(thirdsEnabled)colors.accent else Color.WHITE).apply{setBounds(0,0,dp(22),dp(22))},null,null,null)
        thirdsButton.isSelected=thirdsEnabled
        if (thirdsEnabled) applyViewport() else { image.resetScaleAndCenter();applyViewport() }
        updatePosition()
        host.refreshHints()
    }

    private fun applyViewport(animated: Boolean = false) {
        if (!image.isReady) return
        if (!thirdsEnabled) { if(fitWidth) image.setScaleAndCenter((image.width.toFloat()/image.sWidth).coerceIn(image.minScale,image.maxScale),PointF(image.sWidth/2f,image.sHeight/2f));return }
        val value = manifest ?: return
        val position = state ?: return
        val page = value.pages.getOrNull(position.pageIndex) ?: ReadingPublicationPage(index = position.pageIndex)
        val sourceWidth = (if (page.width > 0) page.width else image.sWidth).coerceAtLeast(1)
        val sourceHeight = (if (page.height > 0) page.height else image.sHeight).coerceAtLeast(1)
        val axis = if (page.isWide || sourceWidth > sourceHeight) ViewportAxis.HORIZONTAL else ViewportAxis.VERTICAL
        val direction = if ((directionOverride ?: value.direction) == "rtl") PageDirection.RTL else PageDirection.LTR
        val viewport = ViewportStepPlanner.steps(axis, direction)[position.viewportIndex]
        val center = PointF(
            ((viewport.left + viewport.right) * 0.5 * sourceWidth).toFloat(),
            ((viewport.top + viewport.bottom) * 0.5 * sourceHeight).toFloat()
        )
        val widthScale = image.width / ((viewport.right - viewport.left) * sourceWidth).toFloat()
        val heightScale = image.height / ((viewport.bottom - viewport.top) * sourceHeight).toFloat()
        val scale = max(image.minScale, if (axis == ViewportAxis.HORIZONTAL) widthScale else heightScale)
            .coerceAtMost(image.maxScale)
        if (animated) image.animateScaleAndCenter(scale, center)
            ?.withDuration(180)?.withInterruptible(true)?.start()
        else image.setScaleAndCenter(scale, center)
        regionHint.text="Region ${position.viewportIndex+1} of 3";regionHint.visibility=View.VISIBLE
        regionHint.removeCallbacks(hideRegionHint);regionHint.postDelayed(hideRegionHint,1200)
    }

    private val hideRegionHint=Runnable { if(::regionHint.isInitialized)regionHint.visibility=View.GONE }

    private fun showReadingOptions(tab:String="display") {
        val rtl=(directionOverride ?: manifest?.direction)=="rtl"
        options.resetBody()
        options.open("Reading options")
        options.tabs(listOf("display" to "Display", "flow" to "Flow"),tab,::showReadingOptions)
        if(tab=="display") {
            options.choice("Fit whole page",selected=!fitWidth && !thirdsEnabled){
                if(thirdsEnabled)toggleThirds();fitWidth=false;image.resetScaleAndCenter();applyViewport();showReadingOptions(tab)
            }
            options.choice("Fit page width",selected=fitWidth && !thirdsEnabled){
                if(thirdsEnabled)toggleThirds();fitWidth=true;image.resetScaleAndCenter();applyViewport();showReadingOptions(tab)
            }
            options.choice("Read in thirds",selected=thirdsEnabled){toggleThirds();showReadingOptions(tab)}
        } else {
            options.choice("Left to right",selected=!rtl){directionOverride="ltr";applyViewport();showReadingOptions(tab)}
            options.choice("Right to left",selected=rtl){directionOverride="rtl";applyViewport();showReadingOptions(tab)}
        }
        options.focusBody()
    }

    private fun showPagePreview(page:Int) {
        previewJob?.cancel()
        previewRequest?.dispose()
        previewImage.setImageDrawable(null);previewLabel.text="Page ${page+1}";previewCard.visibility=View.VISIBLE
        val value=manifest ?: return
        val repo=repository ?: return
        previewJob=uiScope.launch {
            delay(160)
            try {
                val file=repo.obtain(readingSession.api.readingPublicationPageUrl(workId,value.sourceItemId,page))
                if (seek.progress!=page || previewCard.visibility!=View.VISIBLE) return@launch
                previewRequest=(api as? HubClient)?.imageLoader?.enqueue(ImageRequest.Builder(host.viewContext).data(file).size(dp(88),dp(112)).target(previewImage).build())
            } catch (_: kotlinx.coroutines.CancellationException) { /* A newer scrub target replaced this one. */ }
            catch (_: Exception) { previewLabel.text="Page ${page+1} · preview unavailable" }
        }
    }

    private fun zoom(factor: Float) {
        if (!image.isReady) return
        val center = image.center ?: PointF(image.sWidth / 2f, image.sHeight / 2f)
        image.animateScaleAndCenter((image.scale * factor).coerceIn(image.minScale, image.maxScale), center)
            ?.withDuration(180)?.withInterruptible(true)?.start()
    }

    private fun scheduleSave() {
        // The requested page may still be downloading. onReady saves the page actually displayed.
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
        positionView.text = buildString {
            append("Page ${position.pageIndex + 1} of ${value.pageCount}")
            if (thirdsEnabled) append(" · ${position.viewportIndex + 1}/3")
        }
    }

    private fun toggleControls() = setControlsVisible(!controlsVisible)

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        pagePreview.setControlsVisible(visible)
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

    private fun dp(value: Int): Int = Styler.dpInt(host.viewContext, value.toFloat())

    private companion object {
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
