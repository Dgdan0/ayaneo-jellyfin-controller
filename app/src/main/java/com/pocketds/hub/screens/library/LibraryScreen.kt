package com.pocketds.hub.screens.library

import com.pocketds.hub.ui.Artwork
import com.pocketds.hub.settings.Prefs
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.HorizontalMode
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.LibraryResponse
import com.pocketds.hub.model.LibraryView
import com.pocketds.hub.model.ReadingLibrariesResponse
import com.pocketds.hub.model.ReadingLibrary
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ContentModeScreen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.state.ContentMode
import com.pocketds.hub.ui.AppIcon
import com.pocketds.hub.ui.AppIconDrawable
import com.pocketds.hub.ui.BlobSegmentedView
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.FocusHorizontalScrollView
import com.pocketds.hub.settings.ContentModeSettings
import com.pocketds.hub.ui.LibraryCardView
import com.pocketds.hub.ui.LibraryTileSizing
import com.pocketds.hub.ui.LibraryArtworkRefresh
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.ChoiceOverlay
import com.pocketds.hub.ui.ScrimDrawable
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Theme
import com.pocketds.hub.ui.ThemeGradientDrawable
import com.pocketds.hub.ui.Type
import com.pocketds.hub.ui.activateOnTap
import com.pocketds.hub.ui.typeRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import java.time.LocalDate
import com.pocketds.hub.state.StatusText
import com.pocketds.hub.ui.showStatus

/**
 * Library. Movies and TV: the server's libraries as a row of chips across the
 * top, the chosen one's posters right under them, sorted on the same row.
 * More libraries than fit scroll sideways under a darkened right edge with a
 * › that says so. Books: the reading libraries as cards, each opening its own
 * page.
 *
 * Glass (#11): both sides open on the prototype's root instead, a fanned tile
 * per library ([LibraryRootView]), Movies and TV with search and Favourites; a
 * tile pushes that library's page ([LibraryFolderScreen]). The libraries come
 * in the hub's order, which the roots arrange (#15).
 */
class LibraryScreen(
    private val api: HubApi,
    private val ringVisible: () -> Boolean
) : Screen, ContentModeScreen {
    override val title = "Library"
    override val horizontalMode = HorizontalMode.GRID
    override val showsOwnTitle = true
    /** Glass: the tile in focus tints the page. */
    override val pageArtwork: String?
        get() = if (glass && mode == ContentMode.MEDIA && ::root.isInitialized) root.artwork else null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** Saves of an order, which hiding the screen must not cancel half way (#15). */
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val readingAdapter = ReadingViewAdapter()
    private lateinit var colors: PocketColors
    private lateinit var mediaContent: LinearLayout
    private lateinit var booksContent: LinearLayout
    private lateinit var heading: TextView
    private lateinit var status: TextView
    private lateinit var list: RecyclerView
    private lateinit var chips: BlobSegmentedView
    private lateinit var chipScroll: FocusHorizontalScrollView
    private lateinit var edge: View
    private lateinit var searchButton: View
    private lateinit var searchBox: EditText
    private lateinit var gridView: LibraryGridView
    /** Glass: the root of tiles, in place of the chips and grid. */
    private lateinit var root: LibraryRootView
    /** Glass: the Books root of tiles, in place of the cards. */
    private lateinit var booksRoot: LibraryRootView
    /** Each side's order, as the hub keeps it for the profile (#15). */
    private lateinit var mediaOrder: LibraryOrderEditor
    private lateinit var booksOrder: LibraryOrderEditor
    private var readingLibraries: List<ReadingLibrary> = emptyList()
    private var glass = false
    private lateinit var overlay: ChoiceOverlay
    private var host: ScreenHost? = null
    private var loadJob: Job? = null
    private var mode = ContentMode.MEDIA
    private var selectedBooks = 0
    private var loadGeneration = 0
    private var mediaArtworkDay = ""
    private var readingArtworkDay = ""
    private var views: List<LibraryView> = emptyList()

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        colors = Theme.colors(host.viewContext)
        glass = Theme.onGlass(colors)
        mode = ContentModeSettings.get(host.viewContext)
        val page = FrameLayout(host.viewContext).apply { setBackgroundColor(colors.background) }
        overlay = ChoiceOverlay(host.viewContext, colors, ringVisible, sidePanel = true)
        mediaOrder = orderEditor(ContentMode.MEDIA)
        booksOrder = orderEditor(ContentMode.BOOKS)
        mediaContent = LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (mode == ContentMode.MEDIA) View.VISIBLE else View.GONE
        }
        if (glass) buildGlassMedia(host) else buildMedia(host)
        page.addView(mediaContent, FrameLayout.LayoutParams(MATCH, MATCH))
        booksContent = LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (mode == ContentMode.BOOKS) View.VISIBLE else View.GONE
        }
        if (glass) buildGlassBooks(host) else buildBooks(host)
        page.addView(booksContent, FrameLayout.LayoutParams(MATCH, MATCH))
        page.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        return page
    }

    /** One side's order: a move shows on its root, a failed save says so, A to Z reads the hub's again. */
    private fun orderEditor(side: ContentMode) = LibraryOrderEditor(side, api, saveScope,
        onOrder = { ids ->
            if (side == ContentMode.MEDIA) views = LibraryOrder.inOrder(views, ids) { it.id }
            if (glass) (if (side == ContentMode.MEDIA) root else booksRoot).reorder(ids)
            host?.refreshHints()
        },
        onFailed = { message -> host?.notify("The new order could not be saved · $message") },
        onReset = { if (side == mode) load(force = true) }
    )

    private fun buildGlassBooks(host: ScreenHost) {
        booksRoot = LibraryRootView(
            host.viewContext, api, colors, ringVisible,
            heading = "Your reading libraries",
            withSearch = false,
            order = booksOrder,
            onOpen = { id -> readingLibraryOf(id)?.let(::open) },
            onFocusChanged = { host.refreshHints() }
        )
        booksContent.addView(booksRoot, LinearLayout.LayoutParams(MATCH, 0, 1f))
    }

    private fun readingLibraryOf(id: String): ReadingLibrary? =
        if (id == READING_LISTS) READING_LISTS_LIBRARY else readingLibraries.firstOrNull { it.id == id }

    /** Classic: the reading libraries as cards. */
    private fun buildBooks(host: ScreenHost) {
        booksContent.apply {
            heading = TextView(context).apply {
                text = "Your reading libraries"
                setTextColor(colors.primaryText)
                typeRole(Type.Role.SCREEN)
                setPadding(dp(24), dp(10), dp(24), dp(2))
            }
            addView(heading)
            status = TextView(context).apply {
                textSize = 11f
                setTextColor(colors.mutedText)
                setPadding(dp(24), 0, dp(24), dp(4))
            }
            addView(status)
            list = RecyclerView(context).apply {
                layoutManager = GridLayoutManager(context, LIBRARY_COLUMNS)
                adapter = readingAdapter
                setItemViewCacheSize(LIBRARY_COLUMNS * 2)
                clipToPadding = false
                clipChildren = false
                setPadding(dp(16), dp(8), dp(16), dp(20))
                layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
                addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
                    val widthDp = ((right - left) / resources.displayMetrics.density).toInt()
                    val columns = LibraryTileSizing.columnsFor(widthDp)
                    (layoutManager as? GridLayoutManager)?.let { if (it.spanCount != columns) it.spanCount = columns }
                }
            }
            addView(list)
        }
    }

    private fun buildGlassMedia(host: ScreenHost) {
        root = LibraryRootView(
            host.viewContext, api, colors, ringVisible,
            heading = "Your libraries",
            withSearch = true,
            order = mediaOrder,
            onOpen = { id -> views.firstOrNull { it.id == id }?.let(::openFolder) },
            onFavourites = { views.firstOrNull { it.kind == FAVOURITES }?.let(::openFolder) },
            onSearch = ::openSearch,
            onFocusChanged = { host.refreshHints() }
        )
        mediaContent.addView(root, LinearLayout.LayoutParams(MATCH, 0, 1f))
    }

    /** Glass: a library's page, its capsule holding every library and Favourites. */
    private fun openFolder(view: LibraryView) {
        if (views.isEmpty()) return
        host?.push(LibraryFolderScreen(api, views, view.id, ringVisible))
    }

    private fun buildMedia(host: ScreenHost) {
        val context = host.viewContext
        gridView = LibraryGridView(context, api, colors, ringVisible, host) { overlay }.apply {
            // A late page must not pull focus out of the chips or the search box.
            wantsFocus = { !chips.hasFocus() && !searchBox.hasFocus() && !searchButton.hasFocus() }
        }
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), dp(20), dp(2))
        }
        val chipFrame = FrameLayout(context)
        chips = BlobSegmentedView(context, colors, ringVisible, BlobSegmentedView.Style.PILL).apply {
            onPick = { id -> views.firstOrNull { it.id == id }?.let(::showLibrary) }
            onOptionFocused = { host.refreshHints() }
        }
        chipScroll = FocusHorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(24), dp(4), dp(64), dp(4))
            addView(chips)
            setOnScrollChangeListener { _, _, _, _, _ -> syncEdge() }
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> syncEdge() }
        }
        chipFrame.addView(chipScroll, FrameLayout.LayoutParams(MATCH, WRAP))
        // The darker right edge and a ›: there are more libraries than fit.
        edge = FrameLayout(context).apply {
            background = ScrimDrawable(colors, ScrimDrawable.Edge.RIGHT, listOf(0f to 1f, .3f to .85f, 1f to 0f))
            addView(ImageView(context).apply {
                setImageDrawable(AppIconDrawable(AppIcon.NEXT, colors.primaryText))
                val pad = dp(5)
                setPadding(pad, pad, pad, pad)
                background = ThemeGradientDrawable.oval(androidx.core.graphics.ColorUtils.setAlphaComponent(colors.primaryText, 0x1F))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER_VERTICAL or Gravity.END).apply { marginEnd = dp(6) })
            isClickable = true
            setOnClickListener { chipScroll.smoothScrollBy(chipScroll.width / 2, 0) }
            visibility = View.GONE
        }
        chipFrame.addView(edge, FrameLayout.LayoutParams(dp(76), MATCH, Gravity.END))
        bar.addView(chipFrame, LinearLayout.LayoutParams(0, WRAP, 1f))
        searchButton = TextView(context).apply {
            contentDescription = "Search your Jellyfin library"
            val icon = AppIconDrawable(AppIcon.SEARCH, colors.primaryText).apply { setBounds(0, 0, dp(18), dp(18)) }
            setCompoundDrawables(icon, null, null, null)
            gravity = Gravity.CENTER
            setPadding(dp(11), 0, dp(11), 0)
            minimumHeight = dp(40)
            background = Styler.chipBackground(context, colors)
            Styler.makeFocusable(this)
            FocusDecorator.attach(this, ringVisible, scale = false)
            FocusDecorator.listen(this, ringVisible) { _, _ -> host.refreshHints() }
            activateOnTap { openSearchBox() }
        }
        bar.addView(searchButton, LinearLayout.LayoutParams(WRAP, dp(40)).apply { marginEnd = dp(8) })
        bar.addView(gridView.sortControls)
        mediaContent.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
        searchBox = EditText(context).apply {
            hint = "Search your Jellyfin library"
            textSize = 13f
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setTextColor(colors.primaryText)
            setHintTextColor(colors.mutedText)
            background = Styler.chipBackground(context, colors)
            setPadding(dp(14), dp(5), dp(14), dp(5))
            visibility = View.GONE
            Styler.makeFocusable(this)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) { openSearch(text.toString()); true } else false
            }
            setOnKeyListener { _, keyCode, event ->
                if (event.action == android.view.KeyEvent.ACTION_UP &&
                    (keyCode == android.view.KeyEvent.KEYCODE_ENTER || keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                    openSearch(text.toString()); true
                } else false
            }
            FocusDecorator.attach(this, ringVisible, scale = false)
            FocusDecorator.listen(this, ringVisible) { _, _ -> host.refreshHints() }
        }
        mediaContent.addView(searchBox, LinearLayout.LayoutParams(MATCH, dp(44)).apply { setMargins(dp(24), dp(4), dp(24), dp(2)) })
        mediaContent.addView(gridView.status.apply { setPadding(dp(26), dp(2), dp(24), 0) })
        mediaContent.addView(gridView, LinearLayout.LayoutParams(MATCH, 0, 1f))
    }

    private fun syncEdge() {
        if (!::edge.isInitialized) return
        edge.visibility = if (chipScroll.canScrollHorizontally(1)) View.VISIBLE else View.GONE
    }

    private fun showLibrary(view: LibraryView) {
        host?.viewContext?.let { Prefs.of(it).edit().putString(KEY_LAST_LIBRARY, view.id).apply() }
        chips.select(view.id)
        gridView.show(view)
        host?.refreshHints()
    }

    private fun openSearchBox() {
        searchBox.visibility = View.VISIBLE
        searchBox.requestFocus()
        (searchBox.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(searchBox, InputMethodManager.SHOW_IMPLICIT)
        host?.refreshHints()
    }

    private fun closeSearchBox(): Boolean {
        if (searchBox.visibility != View.VISIBLE) return false
        searchBox.visibility = View.GONE
        searchButton.requestFocus()
        host?.refreshHints()
        return true
    }

    override fun onShow() {
        val stored = host?.viewContext?.let(ContentModeSettings::get) ?: mode
        if (stored != mode) switchMode(stored, persist = false)
        if (mode == ContentMode.MEDIA) {
            // An order saved elsewhere (Settings › Libraries) since these were drawn is read again.
            if (views.isEmpty() || mediaOrder.stale || LibraryArtworkRefresh.needed(mediaArtworkDay, LocalDate.now().toString())) {
                if (loadJob?.isActive != true) load(force = views.isNotEmpty())
            } else {
                if (!glass) gridView.onShow()
                restoreFocus()
            }
            return
        }
        if (loadJob?.isActive != true &&
            (readingLibraries.isEmpty() || booksOrder.stale || LibraryArtworkRefresh.needed(readingArtworkDay, LocalDate.now().toString()))) {
            load(force = readingLibraries.isNotEmpty())
        } else restoreFocus()
    }

    override fun onHide() {
        rememberSelection()
        if (::root.isInitialized) root.onHide()
        if (::booksRoot.isInitialized) booksRoot.onHide()
        if (::gridView.isInitialized) gridView.onHide()
        if (::overlay.isInitialized && overlay.isOpen) overlay.dismiss()
        scope.coroutineContext.cancelChildren()
        loadJob = null
    }

    override fun onDestroyView() {
        if (::gridView.isInitialized) gridView.destroy()
        scope.cancel()
        saveScope.cancel()
        host = null
    }

    /** The root on show while it is being arranged, if one is. */
    private val arranging: LibraryRootView?
        get() = when {
            !glass -> null
            mode == ContentMode.MEDIA -> root.takeIf { it.arranging }
            else -> booksRoot.takeIf { it.arranging }
        }

    /** Back while arranging puts a lifted tile back, or finishes, rather than leaving the app. */
    override fun onSystemBack(): Boolean = arranging?.back() == true

    override fun requestInitialFocus(): Boolean {
        if (mode == ContentMode.MEDIA) {
            if (::overlay.isInitialized && overlay.isOpen) return true
            if (glass) return root.requestInitialFocus() || views.isEmpty() || root.search.requestFocus()
            // While the first posters load, focus waits for them rather than
            // settling on the chips, where a late page could no longer claim it.
            if (gridView.requestInitialFocus()) return true
            if (views.isEmpty() || gridView.loading) return true
            return chips.focus()
        }
        if (glass) return booksRoot.requestInitialFocus()
        val count = readingAdapter.itemCount
        if (!::list.isInitialized || count == 0) return false
        val target = selectedBooks.coerceIn(0, count - 1)
        list.scrollToPosition(target)
        list.post { list.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus() }
        return true
    }

    override fun hints(): List<ButtonHint> = when {
        ::overlay.isInitialized && overlay.isOpen -> listOf(ButtonHint.activate("Choose"), ButtonHint.back("Cancel"))
        glass && mode == ContentMode.BOOKS -> booksRoot.hints() ?: listOf(ButtonHint.activate("Open"), ButtonHint.refresh())
        mode == ContentMode.BOOKS -> listOf(ButtonHint.activate("Open"), ButtonHint.refresh())
        glass -> root.hints() ?: mediaHints()
        chips.hasFocus() -> listOf(ButtonHint.activate("Show library"), ButtonHint.refresh())
        searchBox.hasFocus() -> listOf(ButtonHint.activate("Search"), ButtonHint.back("Close search"))
        searchButton.hasFocus() -> listOf(ButtonHint.activate("Search"))
        gridView.sortControls.hasFocus() -> listOf(ButtonHint.activate("Change"), ButtonHint.refresh())
        else -> gridView.hints()
    }

    /** Glass Movies and TV, not arranging. */
    private fun mediaHints(): List<ButtonHint> = when {
        root.search.hasFocus() -> listOf(ButtonHint.activate("Search"), ButtonHint.refresh())
        else -> listOf(ButtonHint.activate("Open"), ButtonHint.secondary("Search"), ButtonHint.refresh())
    }

    override fun onPad(action: PadAction): Boolean {
        if (::overlay.isInitialized && overlay.onPad(action)) { host?.refreshHints(); return true }
        if (mode == ContentMode.BOOKS) {
            // Glass: arranging, and the moves between Arrange and the tiles; A on a tile opens it.
            if (glass && booksRoot.onPad(action)) { host?.refreshHints(); return true }
            return when (action) {
                PadAction.Activate -> !glass && readingAdapter.at(focusedPosition())?.let { open(it) } != null
                PadAction.Refresh -> { load(force = true); true }
                else -> false
            }
        }
        if (glass) {
            // Arranging, and the moves between the search row, Arrange and the tiles.
            if (root.onPad(action)) { host?.refreshHints(); return true }
            return when {
                // A on the search field types or searches; on an EditText a click does nothing visible.
                action == PadAction.Activate && root.search.hasFocus() -> {
                    if (root.search.text.isNullOrBlank()) root.focusSearch() else openSearch(root.search.text.toString())
                    true
                }
                action == PadAction.Secondary -> { root.focusSearch(); host?.refreshHints(); true }
                action == PadAction.Refresh -> { load(force = true); true }
                else -> false
            }
        }
        return when {
            action == PadAction.Back && closeSearchBox() -> true
            action is PadAction.Step && action.direction == Direction.UP && gridView.grid.hasFocus() && gridView.inFirstRow() ->
                chips.focus(gridView.library?.id)
            action is PadAction.Step && action.direction == Direction.DOWN && (chips.hasFocus() || searchButton.hasFocus() ||
                gridView.sortControls.hasFocus()) -> gridView.requestInitialFocus()
            action == PadAction.Refresh && (chips.hasFocus() || !gridView.hasItems) -> { load(force = true); true }
            else -> gridView.onPad(action)
        }
    }

    private fun load(force: Boolean = false) {
        if (loadJob?.isActive == true) return
        val generation = ++loadGeneration
        val requestedMode = mode
        if (mode == ContentMode.BOOKS) booksStatus.showStatus(StatusText.loading("reading libraries", refreshing = force), colors)
        else mediaStatus.showStatus(StatusText.loading("libraries", refreshing = force), colors)
        loadJob = scope.launch {
            if (requestedMode == ContentMode.MEDIA) {
                when (val result = api.library()) {
                    is HubResult.Ok -> if (generation == loadGeneration && requestedMode == mode) renderMedia(result.value)
                    is HubResult.Failed -> if (generation == loadGeneration && requestedMode == mode) {
                        mediaStatus.showStatus(StatusText.failed(result.message, result.kind, hasData = views.isNotEmpty()), colors)
                    }
                }
            } else {
                when (val result = api.readingLibraries()) {
                    is HubResult.Ok -> if (generation == loadGeneration && requestedMode == mode) renderReading(result.value)
                    is HubResult.Failed -> if (generation == loadGeneration && requestedMode == mode) {
                        booksStatus.showStatus(StatusText.failed(result.message, result.kind, hasData = readingLibraries.isNotEmpty()), colors)
                    }
                }
            }
            loadJob = null
        }
    }

    /** Where the media side says it is loading or failed: the root's line in Glass, the grid's in Classic. */
    private val mediaStatus: TextView get() = if (glass) root.status else gridView.status
    private val booksStatus: TextView get() = if (glass) booksRoot.status else status

    private fun renderMedia(body: LibraryResponse) {
        mediaArtworkDay = LocalDate.now().toString()
        // In the hub's order (#15), Favourites after them; never sorted here.
        views = body.views + LibraryView(id = FAVOURITES, name = "Favourites", kind = "favorites")
        mediaOrder.show(body.views.map { it.id }, body.order)
        if (body.views.isEmpty()) {
            mediaStatus.showStatus(StatusText.notice("No movie or TV libraries were found."), colors)
            return
        }
        if (glass) {
            // The counts and how old they are go in the root's own line; the chip is for failures.
            mediaStatus.showStatus(com.pocketds.hub.state.StatusMessage(""), colors)
            val first = !root.hasLibraries
            root.show(body.views.map { view ->
                LibraryRootView.Tile(view.id, LibraryTiles.fan(view).firstOrNull() ?: view.image.ifBlank { null }) { it.bind(view, api) }
            }, body.order)
            root.summarize(StatusText.loaded(LibraryTiles.summary(body.views), body.cache, body.partial.map { it.service }))
            host?.prefetchArtwork(body.views.mapNotNull { LibraryTiles.fan(it).firstOrNull() ?: it.image.ifBlank { null } })
            if (first) restoreFocus()
            host?.refreshHints()
            return
        }
        val remembered = host?.viewContext?.let { Prefs.of(it).getString(KEY_LAST_LIBRARY, null) }
        val chosen = views.firstOrNull { it.id == (gridView.library?.id ?: remembered) } ?: views.first()
        chips.setOptions(views.map { BlobSegmentedView.Option(it.id, if (it.kind == "favorites") "★ Favourites" else it.name, it.name) }, chosen.id)
        chipScroll.post { syncEdge(); chips.optionView(chosen.id)?.let { chipScroll.smoothScrollTo((it.left - dp(40)).coerceAtLeast(0), 0) } }
        if (gridView.library?.id == chosen.id) gridView.onShow() else gridView.show(chosen)
        host?.refreshHints()
    }

    private fun renderReading(body: ReadingLibrariesResponse) {
        // In the hub's order (#15), never sorted here; reading lists stay last and are not arranged.
        readingLibraries = body.libraries
        booksOrder.show(body.libraries.map { it.id }, body.order)
        val lists = if (body.libraries.any { it.source == "kavita" }) listOf(READING_LISTS_LIBRARY) else emptyList()
        readingArtworkDay = LocalDate.now().toString()
        if (glass) {
            booksRoot.show((body.libraries + lists).map { library ->
                LibraryRootView.Tile(library.id, library.artwork.ifBlank { null }, fixed = library.id == READING_LISTS) { it.bind(library, api) }
            }, body.order)
            booksRoot.summarize(StatusText.loaded(LibraryTiles.readingSummary(body.libraries), body.cache, body.partial.map { it.service }))
            booksStatus.showStatus(if (body.libraries.isEmpty()) StatusText.notice("No reading libraries were found.")
                else com.pocketds.hub.state.StatusMessage(""), colors)
        } else {
            readingAdapter.submit(body.libraries + lists)
            status.showStatus(
                if (body.libraries.isEmpty()) StatusText.notice("No reading libraries were found.")
                else StatusText.loaded("${body.libraries.size} reading libraries", body.cache, body.partial.map { it.service }),
                colors
            )
        }
        restoreFocus()
        host?.refreshHints()
    }

    private fun restoreFocus() {
        if (mode == ContentMode.BOOKS && readingLibraries.isEmpty()) return
        requestInitialFocus()
    }

    private fun focusedPosition(): Int {
        val focused = list.focusedChild ?: return -1
        return list.getChildAdapterPosition(focused)
    }

    private fun open(view: ReadingLibrary) {
        rememberSelection()
        if (view.id == READING_LISTS) host?.push(ServerReadingListsScreen(api, ringVisible))
        else host?.push(ReadingLibraryGridScreen(api, view, ringVisible))
    }

    private fun switchMode(next: ContentMode, persist: Boolean) {
        if (next == mode) return
        rememberSelection()
        // Arranging one side ends when the other is shown; a library still lifted is put down and saved.
        arranging?.finishArranging()
        if (mode == ContentMode.MEDIA && ::gridView.isInitialized) gridView.onHide()
        scope.coroutineContext.cancelChildren()
        loadJob = null
        loadGeneration++
        mode = next
        if (persist) ContentModeSettings.set(requireNotNull(host).viewContext, mode)
        mediaContent.visibility = if (mode == ContentMode.MEDIA) View.VISIBLE else View.GONE
        booksContent.visibility = if (mode == ContentMode.BOOKS) View.VISIBLE else View.GONE
        onShow()
        host?.refreshHints()
    }

    override fun selectContentMode(mode: ContentMode) = switchMode(mode, persist = true)

    private fun rememberSelection() {
        if (mode != ContentMode.BOOKS || glass || !::list.isInitialized) return
        val position = focusedPosition().takeIf { it >= 0 } ?: return
        selectedBooks = position
    }

    private fun openSearch(raw: String) {
        val query = raw.trim()
        if (query.length < 2) {
            host?.notify("Type at least two characters")
            return
        }
        if (!glass) closeSearchBox()
        host?.push(LibraryGridScreen(api, LibraryView(id = query, name = "Search · $query", kind = "search"), ringVisible))
    }

    private inner class ReadingViewAdapter : RecyclerView.Adapter<ViewHolder>() {
        private val values = mutableListOf<ReadingLibrary>()

        fun submit(next: List<ReadingLibrary>) {
            values.clear()
            values.addAll(next)
            notifyDataSetChanged()
        }

        fun at(position: Int): ReadingLibrary? = values.getOrNull(position)
        override fun getItemCount() = values.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            // Classic only: Glass shows a Books root of tiles instead.
            val row = LibraryCardView(parent.context, colors).apply {
                layoutParams = RecyclerView.LayoutParams(MATCH, WRAP).apply { setMargins(dp(12), dp(10), dp(12), dp(10)) }
                FocusDecorator.attach(this, ringVisible)
                FocusDecorator.listen(this, ringVisible) { _, focused ->
                    if (focused) {
                        selectedBooks = list.getChildAdapterPosition(this)
                        host?.refreshHints()
                    }
                }
                activateOnTap { (getTag(TAG_READING_VIEW) as? ReadingLibrary)?.let(::open) }
            }
            return ViewHolder(row)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val value = values[position]
            holder.itemView.setTag(TAG_READING_VIEW, value)
            (holder.itemView as LibraryCardView).let { row -> row.bindReading(value, Artwork.loader(api, row.context), api::imageUrl) }
        }
    }

    private class ViewHolder(view: View) : RecyclerView.ViewHolder(view)
    private fun dp(value: Int) = Styler.dpInt(requireNotNull(host).viewContext, value.toFloat())

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val LIBRARY_COLUMNS = 3
        private const val TAG_READING_VIEW = -0x7fffffe0
        private const val FAVOURITES = "favorites"
        /** Kavita's reading lists, a tile after the libraries; not a library, so never arranged. */
        private const val READING_LISTS = "kavita:reading-lists"
        private val READING_LISTS_LIBRARY = ReadingLibrary(id = READING_LISTS, source = "kavita", kind = "reading_list", title = "Reading lists")
        /** The library the Classic page opens on; the Glass library page keeps it too. */
        internal const val KEY_LAST_LIBRARY = "library_last_view"
    }
}

/**
 * A search's or a folder's posters on a page of their own. [searchIn] keeps a
 * search inside one library (#14).
 */
class LibraryGridScreen(
    private val api: HubApi,
    private val library: LibraryView,
    private val ringVisible: () -> Boolean,
    private val searchIn: String = ""
) : Screen {
    override val contentDomain = ContentMode.MEDIA
    override val title = library.name
    override val horizontalMode = HorizontalMode.GRID

    private lateinit var gridView: LibraryGridView
    private lateinit var overlay: ChoiceOverlay

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        val colors = Theme.colors(host.viewContext)
        val root = FrameLayout(host.viewContext).apply { setBackgroundColor(colors.background) }
        overlay = ChoiceOverlay(host.viewContext, colors, ringVisible, sidePanel = true)
        gridView = LibraryGridView(host.viewContext, api, colors, ringVisible, host) { overlay }
        gridView.searchIn = searchIn
        val content = LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.VERTICAL
            val toolbar = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(Styler.dpInt(context, 24f), Styler.dpInt(context, 4f), Styler.dpInt(context, 20f), 0)
                addView(gridView.status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(gridView.sortControls)
            }
            addView(toolbar)
            addView(gridView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        root.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        gridView.show(library)
        return root
    }

    override fun onShow() = gridView.onShow()
    override fun onHide() {
        gridView.onHide()
        if (overlay.isOpen) overlay.dismiss()
    }
    override fun onDestroyView() = gridView.destroy()
    override fun requestInitialFocus(): Boolean = (::overlay.isInitialized && overlay.isOpen) || gridView.requestInitialFocus() ||
        gridView.sortControls.fieldButton.takeIf { it.isShown }?.requestFocus() == true

    override fun hints() = if (::overlay.isInitialized && overlay.isOpen) listOf(ButtonHint.activate("Choose"), ButtonHint.back("Cancel"))
        else gridView.hints() + ButtonHint.back()

    override fun onPad(action: PadAction): Boolean = overlay.onPad(action) || gridView.onPad(action)
}
