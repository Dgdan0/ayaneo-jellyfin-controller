package com.pocketds.hub.reader

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.EpubPositionBody
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Theme
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject
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
    private val onProgressChanged: () -> Unit = {}
) : Screen {
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
    private lateinit var colors: PocketColors
    private val controls = mutableListOf<View>()
    private var focusedControl = 0
    private var controlsVisible = true

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val progressScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loadJob: Job? = null
    private var locatorJob: Job? = null
    private var saveJob: Job? = null
    private var navigator: EpubNavigatorFragment? = null
    private var publication: Publication? = null
    private var latestLocator: Locator? = null
    private var preferences = EpubReaderPreferences()
    private lateinit var preferenceState: EpubPreferenceState
    private var pageIndex = 0
    private var pageCount = 0
    private val json = Json { ignoreUnknownKeys = true }

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        colors = Theme.colors(host.viewContext)
        root = FrameLayout(host.viewContext).apply { setBackgroundColor(Color.BLACK) }
        navigatorContainer = FrameLayout(host.viewContext).apply {
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
        preferences = loadPreferences()
        preferenceState = EpubPreferenceState(preferences)
        overlay = ChoiceOverlay(host.viewContext, colors, ringVisible)
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        focusedControl = controls.indexOfLast { it.contentDescription == "Next page" }.coerceAtLeast(0)
        setControlsVisible(true)
        return root
    }

    override fun onShow() {
        if (navigator == null && loadJob?.isActive != true) openBook()
    }

    override fun onHide() {
        saveCurrent(immediate = true)
        loadJob?.cancel()
    }

    override fun onDestroyView() {
        saveCurrent(immediate = true, closeAfterSave = true)
        locatorJob?.cancel()
        removeNavigator()
        publication?.close()
        publication = null
        uiScope.cancel()
        controls.clear()
    }

    override fun onAppBackgrounded() = saveCurrent(immediate = true)

    override fun onSystemBack(): Boolean {
        if (::overlay.isInitialized && overlay.isOpen) {
            overlay.onPad(PadAction.Back)
            return true
        }
        return false
    }

    override fun hints(): List<ButtonHint> = if (::overlay.isInitialized && overlay.isOpen) {
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
        if (::overlay.isInitialized && overlay.onPad(action)) {
            host.refreshHints()
            return true
        }
        when (action) {
            PadAction.Menu -> setControlsVisible(!controlsVisible)
            PadAction.Back -> if (controlsVisible) setControlsVisible(false) else host.back()
            PadAction.Activate -> if (controlsVisible) controls.getOrNull(focusedControl)?.performClick() else turn(1)
            PadAction.Primary -> host.notify("Bookmark saved locally is the next EPUB reader slice")
            PadAction.Secondary -> {
                setControlsVisible(true)
                showTableOfContents()
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
            val cache = EpubPackageCache(File(host.viewContext.cacheDir, "reading-epub"))
            if (forceDownload) cache.completeFile(workId, sourceItemId).delete()
            val file = if (cache.isComplete(workId, sourceItemId)) {
                cache.completeFile(workId, sourceItemId)
            } else {
                val temporary = cache.temporaryFile(workId, sourceItemId).apply { delete() }
                loading.text = "Downloading book…"
                when (val result = api.downloadReadingEpub(workId, sourceItemId, temporary)) {
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
            val saved = when (val result = api.readingEpubPosition(workId, sourceItemId)) {
                is HubResult.Ok -> result.value.locator?.let { Locator.fromJSON(JSONObject(it.toString())) }
                is HubResult.Failed -> null
            }
            runCatching { attachNavigator(file, saved) }
                .onFailure { showFailure("This EPUB could not be opened") }
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
        latestLocator = fragment.currentLocator.value
        locatorJob = uiScope.launch {
            fragment.currentLocator.drop(1).collect { locator ->
                latestLocator = locator
                scheduleSave()
            }
        }
        loading.visibility = View.GONE
        updatePosition()
        root.post { controls.getOrNull(focusedControl)?.requestFocus() }
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
        val didMove = if (delta >= 0) navigator?.goForward(animated = true) else navigator?.goBackward(animated = true)
        if (didMove == false) host.notify(if (delta >= 0) "End of book" else "Start of book")
    }

    private fun changeChapter(direction: Direction) {
        repeat(4) { if (direction == Direction.UP) navigator?.goBackward() else navigator?.goForward() }
    }

    private fun scheduleSave() = saveCurrent(immediate = false)

    private fun saveCurrent(immediate: Boolean, closeAfterSave: Boolean = false) {
        val locator = latestLocator ?: return
        saveJob?.cancel()
        saveJob = progressScope.launch {
            if (!immediate) delay(1_200)
            val raw = json.parseToJsonElement(locator.toJSON().toString()).jsonObject
            if (api.saveReadingEpubPosition(
                    workId, sourceItemId,
                    EpubPositionBody(raw, System.currentTimeMillis())
                ) is HubResult.Ok
            ) onProgressChanged()
            if (closeAfterSave) progressScope.cancel()
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
            showTableOfContents()
        }))
        topBar.addView(control("Aa", "Reading appearance", { showAppearance() }, 17f))
    }

    private fun buildBottomBar() {
        bottomBar = LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(5), dp(10), dp(5))
            setBackgroundColor(0xD9141518.toInt())
        }
        root.addView(bottomBar, FrameLayout.LayoutParams(MATCH, dp(58), Gravity.BOTTOM))
        bottomBar.addView(control("‹", "Previous page", { turn(-1) }, 31f))
        position = TextView(host.viewContext).apply {
            text = "Opening…"
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        bottomBar.addView(position, LinearLayout.LayoutParams(0, MATCH, 1f))
        bottomBar.addView(control("›", "Next page", { turn(1) }, 31f))
    }

    private fun control(glyph: String, label: String, click: () -> Unit, textSize: Float = 24f): TextView =
        TextView(host.viewContext).apply {
            text = glyph
            this.textSize = textSize
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

    private fun showTableOfContents() {
        val book = publication ?: return host.notify("The book is still opening")
        val links = flattenLinks(book.tableOfContents.ifEmpty { book.readingOrder })
        if (links.isEmpty()) return host.notify("This book has no table of contents")
        val currentHref = latestLocator?.href?.toString()
        val start = links.indexOfFirst { it.second.href.toString() == currentHref }.coerceAtLeast(0)
        overlay.show(
            title = "Table of contents",
            subtitle = "${links.size} sections",
            choices = links.mapIndexed { index, (depth, link) ->
                ChoiceOverlay.Choice(
                    id = index.toString(),
                    label = "  ".repeat(depth) + (link.title ?: "Section ${index + 1}"),
                    detail = if (link.href.toString() == currentHref) "Current section" else ""
                )
            },
            startIndex = start,
            onCancel = { host.refreshHints() }
        ) { id ->
            val link = links.getOrNull(id.toIntOrNull() ?: -1)?.second ?: return@show
            book.locatorFromLink(link)?.let { navigator?.go(it, animated = false) }
            host.refreshHints()
        }
        host.refreshHints()
    }

    private fun flattenLinks(links: List<Link>, depth: Int = 0): List<Pair<Int, Link>> = buildList {
        links.forEach { link ->
            add(depth to link)
            addAll(flattenLinks(link.children, depth + 1))
        }
    }

    private fun showAppearance(startAt: String = "theme") {
        val choices = listOf(
            ChoiceOverlay.Choice("theme", "Page colour", preferences.theme.name.lowercase().replaceFirstChar { it.uppercase() }),
            ChoiceOverlay.Choice("font-smaller", "Smaller text", "${(preferences.fontScale * 100).toInt()}%"),
            ChoiceOverlay.Choice("font-larger", "Larger text", "${(preferences.fontScale * 100).toInt()}%"),
            ChoiceOverlay.Choice("line-height", "Line spacing", "${(preferences.lineHeight * 100).toInt()}%"),
            ChoiceOverlay.Choice("margins", "Page margins", "${(preferences.pageMargins * 100).toInt()}%"),
            ChoiceOverlay.Choice("columns", "Columns", preferences.columns.name.lowercase().replaceFirstChar { it.uppercase() }),
            ChoiceOverlay.Choice("font", "Typeface", when (preferences.fontFamily) {
                "serif" -> "Serif"
                "sans-serif" -> "Sans serif"
                else -> "Publisher"
            }),
            ChoiceOverlay.Choice("publisher", "Publisher styling", if (preferences.publisherStyles) "On" else "Off"),
            ChoiceOverlay.Choice("done", "Done", "Save these reading settings")
        )
        overlay.show(
            title = "Reading appearance",
            subtitle = "Changes preview immediately",
            choices = choices,
            startIndex = choices.indexOfFirst { it.id == startAt }.coerceAtLeast(0),
            onCancel = {
                preferenceState.cancel()
                preferences = preferenceState.visible
                applyPreferences()
                host.refreshHints()
            }
        ) { id ->
            if (id == "done") {
                preferenceState.commit()
                preferences = preferenceState.saved
                persistPreferences(preferences)
                preferenceState.markPersisted()
                host.refreshHints()
                return@show
            }
            preferences = when (id) {
                "theme" -> EpubPreferenceAdjuster.nextTheme(preferences)
                "font-smaller" -> EpubPreferenceAdjuster.changeFontSize(preferences, -0.1f)
                "font-larger" -> EpubPreferenceAdjuster.changeFontSize(preferences, 0.1f)
                "line-height" -> EpubPreferenceAdjuster.changeLineHeight(
                    preferences,
                    if (preferences.lineHeight >= 1.8f) -0.8f else 0.1f
                )
                "margins" -> EpubPreferenceAdjuster.changeMargins(
                    preferences,
                    if (preferences.pageMargins >= 1.8f) -1.3f else 0.2f
                )
                "columns" -> EpubPreferenceAdjuster.nextColumns(preferences)
                "font" -> preferences.copy(fontFamily = when (preferences.fontFamily) {
                    "publisher" -> "serif"
                    "serif" -> "sans-serif"
                    else -> "publisher"
                })
                "publisher" -> preferences.copy(publisherStyles = !preferences.publisherStyles)
                else -> preferences
            }
            preferenceState.preview(preferences)
            applyPreferences()
            showAppearance(id)
        }
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
            publisherStyles = store.getBoolean("publisherStyles", true)
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
        if (::topBar.isInitialized) topBar.visibility = if (visible) View.VISIBLE else View.GONE
        if (::bottomBar.isInitialized) bottomBar.visibility = if (visible) View.VISIBLE else View.GONE
        if (::navigatorContainer.isInitialized) {
            val insets = EpubChromePolicy.insets(visible)
            navigatorContainer.layoutParams = (navigatorContainer.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = dp(insets.topDp)
                bottomMargin = dp(insets.bottomDp)
            }
        }
        if (!visible) root.findFocus()?.clearFocus() else root.post { controls.getOrNull(focusedControl)?.requestFocus() }
        if (::host.isInitialized) host.refreshHints()
    }

    private fun moveControlFocus(direction: Direction) {
        if (controls.isEmpty()) return
        focusedControl = when (direction) {
            Direction.LEFT, Direction.UP -> (focusedControl - 1).coerceAtLeast(0)
            Direction.RIGHT, Direction.DOWN -> (focusedControl + 1).coerceAtMost(controls.lastIndex)
        }
        controls[focusedControl].requestFocus()
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

    private fun fragmentTag() = "epub:$workId:$sourceItemId"
    private fun dp(value: Int) = Styler.dpInt(host.viewContext, value.toFloat())

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
