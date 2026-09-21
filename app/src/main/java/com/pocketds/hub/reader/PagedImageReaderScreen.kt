package com.pocketds.hub.reader

import android.graphics.Color
import android.graphics.PointF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
    private val onProgressChanged: () -> Unit = {}
) : Screen {
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
    private var repository: ReaderPageRepository? = null

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val progressScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var manifestJob: Job? = null
    private var pageJob: Job? = null
    private var saveJob: Job? = null
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
        colors = Theme.colors(host.viewContext)
        repository = (api as? HubClient)?.let { ReaderPageRepository(host.viewContext, it) }
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
        focusedControl = ReaderControlFocusPolicy.initialIndex(focusables.size) ?: 0
        setControlsVisible(true)
        return root
    }

    override fun onShow() {
        if (manifest == null && manifestJob?.isActive != true) {
            loadManifest(currentSourceItemId)
        } else if (manifest != null && !image.isReady && pageJob?.isActive != true) {
            loadPage()
        }
    }

    override fun onHide() {
        saveCurrent(immediate = true)
        manifestJob?.cancel()
        pageJob?.cancel()
    }

    override fun onDestroyView() {
        image.recycle()
        uiScope.cancel()
        repository = null
        focusables.clear()
    }

    override fun onAppBackgrounded() = saveCurrent(immediate = true)

    override fun hints(): List<ButtonHint> = listOf(
        ButtonHint.activate(if (controlsVisible) "Choose" else "Next page"),
        ButtonHint.back(if (controlsVisible) "Hide controls" else "Close reader"),
        ButtonHint.primary(if (thirdsEnabled) "Whole page" else "Read in thirds"),
        ButtonHint.secondary("Navigator")
    )

    override fun onPad(action: PadAction): Boolean {
        when (action) {
            PadAction.Menu -> toggleControls()
            PadAction.Back -> if (controlsVisible) setControlsVisible(false) else host.back()
            PadAction.Activate -> if (controlsVisible) {
                focusables.getOrNull(focusedControl)?.performClick()
            } else {
                advance()
            }
            PadAction.Primary -> toggleThirds()
            PadAction.Secondary -> {
                setControlsVisible(true)
                focusedControl = focusables.indexOf(seek).coerceAtLeast(0)
                focusables.getOrNull(focusedControl)?.requestFocus()
            }
            PadAction.Refresh -> loadPage()
            is PadAction.Section -> if (action.delta > 0) advance() else retreat()
            is PadAction.Page -> zoom(if (action.direction == Direction.UP) 1.25f else 0.8f)
            is PadAction.Step -> if (controlsVisible) moveControlFocus(action.direction) else navigate(action.direction)
        }
        return true
    }

    // Android edge-back should leave the reader in one gesture. Physical B is
    // handled above and first dismisses chrome.
    override fun onSystemBack(): Boolean = false

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
        bottomBar.addView(control("‹", "Previous page", ::retreat))
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
                    if (fromUser && !suppressSeek) positionView.text = "Page ${value + 1} of ${seekBar.max + 1}"
                }

                override fun onStopTrackingTouch(seekBar: SeekBar) {
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
        bottomBar.addView(control("›", "Next page", ::advance))
    }

    private fun control(glyph: String, label: String, click: () -> Unit): TextView =
        TextView(host.viewContext).apply {
            text = glyph
            textSize = 24f
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
            when (val result = api.readingPublication(workId, sourceItemId)) {
                is HubResult.Ok -> {
                    if (requestGeneration != generation) return@launch
                    applyManifest(result.value, pendingStartAtEnd)
                }
                is HubResult.Failed -> if (requestGeneration == generation) {
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
        val pageUrl = api.readingPublicationPageUrl(workId, value.sourceItemId, page)
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
                    pageRepository.obtain(api.readingPublicationPageUrl(workId, value.sourceItemId, candidate))
                }
            }
        }
    }

    private fun showPageError(message: String) {
        loading.text = "$message\nSelect retries"
        loading.visibility = View.VISIBLE
    }

    private fun navigate(direction: Direction) {
        val rtl = manifest?.direction == "rtl"
        when (direction) {
            Direction.LEFT -> if (rtl) advance() else retreat()
            Direction.RIGHT -> if (rtl) retreat() else advance()
            Direction.UP -> retreat()
            Direction.DOWN -> advance()
        }
    }

    private fun advance() {
        val position = state ?: return
        if (position.advance()) {
            if (position.viewportIndex == 0) {
                loadPage()
                scheduleSave()
            } else {
                applyViewport()
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
                applyViewport()
                updatePosition()
            }
            scheduleSave()
            return
        }
        movePublication(-1)
    }

    private fun movePublication(delta: Int) {
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
        thirdsButton.setTextColor(if (thirdsEnabled) colors.accent else Color.WHITE)
        if (thirdsEnabled) applyViewport() else image.resetScaleAndCenter()
        updatePosition()
        host.refreshHints()
    }

    private fun applyViewport() {
        if (!thirdsEnabled || !image.isReady) return
        val value = manifest ?: return
        val position = state ?: return
        val page = value.pages.getOrNull(position.pageIndex) ?: ReadingPublicationPage(index = position.pageIndex)
        val sourceWidth = (if (page.width > 0) page.width else image.sWidth).coerceAtLeast(1)
        val sourceHeight = (if (page.height > 0) page.height else image.sHeight).coerceAtLeast(1)
        val axis = if (page.isWide || sourceWidth > sourceHeight) ViewportAxis.HORIZONTAL else ViewportAxis.VERTICAL
        val direction = if (value.direction == "rtl") PageDirection.RTL else PageDirection.LTR
        val viewport = ViewportStepPlanner.steps(axis, direction)[position.viewportIndex]
        val center = PointF(
            ((viewport.left + viewport.right) * 0.5 * sourceWidth).toFloat(),
            ((viewport.top + viewport.bottom) * 0.5 * sourceHeight).toFloat()
        )
        val widthScale = image.width / ((viewport.right - viewport.left) * sourceWidth).toFloat()
        val heightScale = image.height / ((viewport.bottom - viewport.top) * sourceHeight).toFloat()
        val scale = max(image.minScale, if (axis == ViewportAxis.HORIZONTAL) widthScale else heightScale)
            .coerceAtMost(image.maxScale)
        image.setScaleAndCenter(scale, center)
    }

    private fun zoom(factor: Float) {
        if (!image.isReady) return
        val center = image.center ?: PointF(image.sWidth / 2f, image.sHeight / 2f)
        image.setScaleAndCenter((image.scale * factor).coerceIn(image.minScale, image.maxScale), center)
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        val value = manifest ?: return
        val page = state?.pageIndex ?: return
        saveJob = progressScope.launch {
            delay(700)
            persist(value.sourceItemId, page)
        }
    }

    private fun saveCurrent(immediate: Boolean) {
        saveJob?.cancel()
        val value = manifest ?: return
        val page = state?.pageIndex ?: return
        saveJob = progressScope.launch {
            if (!immediate) delay(700)
            persist(value.sourceItemId, page)
        }
    }

    private suspend fun persist(sourceItemId: String, page: Int) {
        if (api.saveReadingPublicationProgress(workId, sourceItemId, page) is HubResult.Ok) {
            onProgressChanged()
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
        topBar.visibility = if (visible) View.VISIBLE else View.GONE
        bottomBar.visibility = if (visible) View.VISIBLE else View.GONE
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
        fun face(fill: Int, stroke: Int = 0): GradientDrawable = GradientDrawable().apply {
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
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
