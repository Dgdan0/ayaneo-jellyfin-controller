package com.pocketds.hub.screens.library

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
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
import com.pocketds.hub.settings.ContentModeSettings
import com.pocketds.hub.ui.LibraryArtworkRefresh
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.ChoiceOverlay
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Theme
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
 * Library (#11): both sides open on the prototype's root, a fanned tile per
 * library ([LibraryRootView]), Movies and TV with search and Favourites; a
 * tile pushes that library's page ([LibraryFolderScreen]) or, on Books, the
 * reading library's own page. The libraries come in the hub's order, which
 * the roots arrange (#15).
 */
class LibraryScreen(
    private val api: HubApi,
    private val ringVisible: () -> Boolean
) : Screen, ContentModeScreen {
    override val title = "Library"
    override val horizontalMode = HorizontalMode.GRID
    override val showsOwnTitle = true
    /** The tile in focus tints the page. */
    override val pageArtwork: String?
        get() = if (mode == ContentMode.MEDIA && ::root.isInitialized) root.artwork else null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** Saves of an order, which hiding the screen must not cancel half way (#15). */
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var colors: PocketColors
    private lateinit var mediaContent: LinearLayout
    private lateinit var booksContent: LinearLayout
    /** Movies and TV: the root of tiles. */
    private lateinit var root: LibraryRootView
    /** Books: the root of tiles. */
    private lateinit var booksRoot: LibraryRootView
    /** Each side's order, as the hub keeps it for the profile (#15). */
    private lateinit var mediaOrder: LibraryOrderEditor
    private lateinit var booksOrder: LibraryOrderEditor
    private var readingLibraries: List<ReadingLibrary> = emptyList()
    private lateinit var overlay: ChoiceOverlay
    private var host: ScreenHost? = null
    private var loadJob: Job? = null
    private var mode = ContentMode.MEDIA
    private var loadGeneration = 0
    private var mediaArtworkDay = ""
    private var readingArtworkDay = ""
    private var views: List<LibraryView> = emptyList()

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        colors = Theme.colors(host.viewContext)
        mode = ContentModeSettings.get(host.viewContext)
        val page = FrameLayout(host.viewContext).apply { setBackgroundColor(colors.background) }
        overlay = ChoiceOverlay(host.viewContext, colors, ringVisible, sidePanel = true)
        mediaOrder = orderEditor(ContentMode.MEDIA)
        booksOrder = orderEditor(ContentMode.BOOKS)
        mediaContent = LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (mode == ContentMode.MEDIA) View.VISIBLE else View.GONE
        }
        buildMedia(host)
        page.addView(mediaContent, FrameLayout.LayoutParams(MATCH, MATCH))
        booksContent = LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (mode == ContentMode.BOOKS) View.VISIBLE else View.GONE
        }
        buildBooks(host)
        page.addView(booksContent, FrameLayout.LayoutParams(MATCH, MATCH))
        page.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        return page
    }

    /** One side's order: a move shows on its root, a failed save says so, A to Z reads the hub's again. */
    private fun orderEditor(side: ContentMode) = LibraryOrderEditor(side, api, saveScope,
        onOrder = { ids ->
            if (side == ContentMode.MEDIA) views = LibraryOrder.inOrder(views, ids) { it.id }
            (if (side == ContentMode.MEDIA) root else booksRoot).reorder(ids)
            host?.refreshHints()
        },
        onFailed = { message -> host?.notify("The new order could not be saved · $message") },
        onReset = { if (side == mode) load(force = true) }
    )

    private fun buildBooks(host: ScreenHost) {
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

    private fun buildMedia(host: ScreenHost) {
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

    /** A library's page, its capsule holding every library and Favourites. */
    private fun openFolder(view: LibraryView) {
        if (views.isEmpty()) return
        host?.push(LibraryFolderScreen(api, views, view.id, ringVisible))
    }

    override fun onShow() {
        val stored = host?.viewContext?.let(ContentModeSettings::get) ?: mode
        if (stored != mode) switchMode(stored, persist = false)
        if (mode == ContentMode.MEDIA) {
            // An order saved elsewhere (Settings › Libraries) since these were drawn is read again.
            if (views.isEmpty() || mediaOrder.stale || LibraryArtworkRefresh.needed(mediaArtworkDay, LocalDate.now().toString())) {
                if (loadJob?.isActive != true) load(force = views.isNotEmpty())
            } else restoreFocus()
            return
        }
        if (loadJob?.isActive != true &&
            (readingLibraries.isEmpty() || booksOrder.stale || LibraryArtworkRefresh.needed(readingArtworkDay, LocalDate.now().toString()))) {
            load(force = readingLibraries.isNotEmpty())
        } else restoreFocus()
    }

    override fun onHide() {
        if (::root.isInitialized) root.onHide()
        if (::booksRoot.isInitialized) booksRoot.onHide()
        if (::overlay.isInitialized && overlay.isOpen) overlay.dismiss()
        scope.coroutineContext.cancelChildren()
        loadJob = null
    }

    override fun onDestroyView() {
        scope.cancel()
        saveScope.cancel()
        host = null
    }

    /** The root on show while it is being arranged, if one is. */
    private val arranging: LibraryRootView?
        get() = if (mode == ContentMode.MEDIA) root.takeIf { it.arranging } else booksRoot.takeIf { it.arranging }

    /** Back while arranging puts a lifted tile back, or finishes, rather than leaving the app. */
    override fun onSystemBack(): Boolean = arranging?.back() == true

    override fun requestInitialFocus(): Boolean {
        if (mode == ContentMode.MEDIA) {
            if (::overlay.isInitialized && overlay.isOpen) return true
            return root.requestInitialFocus() || views.isEmpty() || root.search.requestFocus()
        }
        return booksRoot.requestInitialFocus()
    }

    override fun hints(): List<ButtonHint> = when {
        ::overlay.isInitialized && overlay.isOpen -> listOf(ButtonHint.activate("Choose"), ButtonHint.back("Cancel"))
        mode == ContentMode.BOOKS -> booksRoot.hints() ?: listOf(ButtonHint.activate("Open"), ButtonHint.refresh())
        else -> root.hints() ?: mediaHints()
    }

    /** Movies and TV, not arranging. */
    private fun mediaHints(): List<ButtonHint> = when {
        root.search.hasFocus() -> listOf(ButtonHint.activate("Search"), ButtonHint.refresh())
        else -> listOf(ButtonHint.activate("Open"), ButtonHint.secondary("Search"), ButtonHint.refresh())
    }

    override fun onPad(action: PadAction): Boolean {
        if (::overlay.isInitialized && overlay.onPad(action)) { host?.refreshHints(); return true }
        if (mode == ContentMode.BOOKS) {
            // Arranging, and the moves between Arrange and the tiles; A on a tile opens it.
            if (booksRoot.onPad(action)) { host?.refreshHints(); return true }
            return when (action) {
                PadAction.Refresh -> { load(force = true); true }
                else -> false
            }
        }
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

    private fun load(force: Boolean = false) {
        if (loadJob?.isActive == true) return
        val generation = ++loadGeneration
        val requestedMode = mode
        if (mode == ContentMode.BOOKS) booksRoot.status.showStatus(StatusText.loading("reading libraries", refreshing = force), colors)
        else root.status.showStatus(StatusText.loading("libraries", refreshing = force), colors)
        loadJob = scope.launch {
            if (requestedMode == ContentMode.MEDIA) {
                when (val result = api.library()) {
                    is HubResult.Ok -> if (generation == loadGeneration && requestedMode == mode) renderMedia(result.value)
                    is HubResult.Failed -> if (generation == loadGeneration && requestedMode == mode) {
                        root.status.showStatus(StatusText.failed(result.message, result.kind, hasData = views.isNotEmpty()), colors)
                    }
                }
            } else {
                when (val result = api.readingLibraries()) {
                    is HubResult.Ok -> if (generation == loadGeneration && requestedMode == mode) renderReading(result.value)
                    is HubResult.Failed -> if (generation == loadGeneration && requestedMode == mode) {
                        booksRoot.status.showStatus(StatusText.failed(result.message, result.kind, hasData = readingLibraries.isNotEmpty()), colors)
                    }
                }
            }
            loadJob = null
        }
    }

    private fun renderMedia(body: LibraryResponse) {
        mediaArtworkDay = LocalDate.now().toString()
        // In the hub's order (#15), Favourites after them; never sorted here.
        views = body.views + LibraryView(id = FAVOURITES, name = "Favourites", kind = "favorites")
        mediaOrder.show(body.views.map { it.id }, body.order)
        if (body.views.isEmpty()) {
            root.status.showStatus(StatusText.notice("No movie or TV libraries were found."), colors)
            return
        }
        // The counts and how old they are go in the root's own line; the chip is for failures.
        root.status.showStatus(com.pocketds.hub.state.StatusMessage(""), colors)
        val first = !root.hasLibraries
        root.show(body.views.map { view ->
            LibraryRootView.Tile(view.id, LibraryTiles.fan(view).firstOrNull() ?: view.image.ifBlank { null }) { it.bind(view, api) }
        }, body.order)
        root.summarize(StatusText.loaded(LibraryTiles.summary(body.views), body.cache, body.partial.map { it.service }))
        host?.prefetchArtwork(body.views.mapNotNull { LibraryTiles.fan(it).firstOrNull() ?: it.image.ifBlank { null } })
        if (first) restoreFocus()
        host?.refreshHints()
    }

    private fun renderReading(body: ReadingLibrariesResponse) {
        // In the hub's order (#15), never sorted here; reading lists stay last and are not arranged.
        readingLibraries = body.libraries
        booksOrder.show(body.libraries.map { it.id }, body.order)
        val lists = if (body.libraries.any { it.source == "kavita" }) listOf(READING_LISTS_LIBRARY) else emptyList()
        readingArtworkDay = LocalDate.now().toString()
        booksRoot.show((body.libraries + lists).map { library ->
            LibraryRootView.Tile(library.id, library.artwork.ifBlank { null }, fixed = library.id == READING_LISTS) { it.bind(library, api) }
        }, body.order)
        booksRoot.summarize(StatusText.loaded(LibraryTiles.readingSummary(body.libraries), body.cache, body.partial.map { it.service }))
        booksRoot.status.showStatus(if (body.libraries.isEmpty()) StatusText.notice("No reading libraries were found.")
            else com.pocketds.hub.state.StatusMessage(""), colors)
        restoreFocus()
        host?.refreshHints()
    }

    private fun restoreFocus() {
        if (mode == ContentMode.BOOKS && readingLibraries.isEmpty()) return
        requestInitialFocus()
    }

    private fun open(view: ReadingLibrary) {
        if (view.id == READING_LISTS) host?.push(ServerReadingListsScreen(api, ringVisible))
        else host?.push(ReadingLibraryGridScreen(api, view, ringVisible))
    }

    private fun switchMode(next: ContentMode, persist: Boolean) {
        if (next == mode) return
        // Arranging one side ends when the other is shown; a library still lifted is put down and saved.
        arranging?.finishArranging()
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

    private fun openSearch(raw: String) {
        val query = raw.trim()
        if (query.length < 2) {
            host?.notify("Type at least two characters")
            return
        }
        host?.push(LibraryGridScreen(api, LibraryView(id = query, name = "Search · $query", kind = "search"), ringVisible))
    }

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val FAVOURITES = "favorites"
        /** Kavita's reading lists, a tile after the libraries; not a library, so never arranged. */
        private const val READING_LISTS = "kavita:reading-lists"
        private val READING_LISTS_LIBRARY = ReadingLibrary(id = READING_LISTS, source = "kavita", kind = "reading_list", title = "Reading lists")
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
