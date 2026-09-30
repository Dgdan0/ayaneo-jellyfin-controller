package com.pocketds.hub.screens.library

import com.pocketds.hub.settings.DomainPreferences
import com.pocketds.hub.settings.SortPreference
import com.pocketds.hub.ui.LibrarySortPanel
import com.pocketds.hub.ui.CenteredIconTextView
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pocketds.hub.input.HorizontalMode
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.LibraryResponse
import com.pocketds.hub.model.LibraryView
import com.pocketds.hub.model.ReadingLibrariesResponse
import com.pocketds.hub.model.ReadingLibrary
import com.pocketds.hub.model.SearchHit
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ContentModeScreen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.state.LibraryGridSizing
import com.pocketds.hub.state.ContentMode
import com.pocketds.hub.state.PagedLoadState
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.settings.ContentModeSettings
import com.pocketds.hub.ui.LibraryCardView
import com.pocketds.hub.ui.LibraryTileSizing
import com.pocketds.hub.ui.LibraryArtworkRefresh
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.PosterCardView
import com.pocketds.hub.ui.ChoiceOverlay
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Theme
import com.pocketds.hub.ui.activateOnTap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import java.time.LocalDate
import com.pocketds.hub.state.StatusMessage
import com.pocketds.hub.state.StatusText
import com.pocketds.hub.ui.showStatus

/** Media and reading folders exactly as their servers name and order them. */
class LibraryScreen(
    private val api: HubApi,
    private val ringVisible: () -> Boolean
) : Screen, ContentModeScreen {
    override val title = "Library"
    override val horizontalMode = HorizontalMode.GRID

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mediaAdapter = ViewAdapter()
    private val readingAdapter = ReadingViewAdapter()
    private lateinit var colors: PocketColors
    private lateinit var heading: TextView
    private lateinit var mediaTools: LinearLayout
    private lateinit var status: TextView
    private lateinit var list: RecyclerView
    private lateinit var searchBox: EditText
    private lateinit var favourites: TextView
    private var host: ScreenHost? = null
    private var loadJob: Job? = null
    private var mode = ContentMode.MEDIA
    private var selectedMedia = 0
    private var selectedBooks = 0
    private var loadGeneration = 0
    private var mediaArtworkDay = ""
    private var readingArtworkDay = ""

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        colors = Theme.colors(host.viewContext)
        mode = ContentModeSettings.get(host.viewContext)
        return LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.background)
            val header=LinearLayout(context).apply {orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL;setPadding(dp(24),dp(8),dp(24),0)}
            addView(header)
            heading = TextView(context).apply {
                text = headingText()
                textSize = 22f
                setTextColor(colors.primaryText)
                setPadding(0,0,0,0)
            }
            header.addView(heading,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
            mediaTools = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(12), dp(2), dp(12), dp(7))
                visibility = if (mode == ContentMode.MEDIA) View.VISIBLE else View.GONE
                searchBox = EditText(context).apply {
                    hint = "Search your Jellyfin library"
                    textSize = 13f
                    setSingleLine()
                    imeOptions = EditorInfo.IME_ACTION_SEARCH
                    setTextColor(colors.primaryText)
                    setHintTextColor(colors.mutedText)
                    background = Styler.chipBackground(context, colors)
                    setPadding(dp(12), dp(5), dp(12), dp(5))
                    Styler.makeFocusable(this)
                    setOnEditorActionListener { _, actionId, _ ->
                        if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                            openSearch(text.toString())
                            true
                        } else false
                    }
                    setOnKeyListener { _, keyCode, event ->
                        if (event.action == android.view.KeyEvent.ACTION_UP &&
                            (keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                                keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                            openSearch(text.toString())
                            true
                        } else false
                    }
                    FocusDecorator.attach(this, ringVisible, scale = false)
                    setOnFocusChangeListener { view, _ ->
                        FocusDecorator.refresh(view, ringVisible())
                        host.refreshHints()
                    }
                }
                addView(searchBox, LinearLayout.LayoutParams(0, dp(48), 1f))
                favourites = TextView(context).apply {
                    text = "★  Favourites"
                    textSize = 13f
                    gravity = android.view.Gravity.CENTER
                    setTextColor(colors.primaryText)
                    background = Styler.cardBackground(context, colors)
                    setPadding(dp(14), 0, dp(14), 0)
                    Styler.makeFocusable(this)
                    FocusDecorator.attach(this, ringVisible, scale = false)
                    setOnFocusChangeListener { view, _ ->
                        FocusDecorator.refresh(view, ringVisible())
                        host.refreshHints()
                    }
                    activateOnTap { openFavourites() }
                }
                addView(favourites, LinearLayout.LayoutParams(WRAP, dp(48)).apply {
                    marginStart = dp(8)
                })
            }
            addView(mediaTools)
            status = TextView(context).apply {
                textSize = 11f
                setTextColor(colors.mutedText)
                setPadding(dp(16), 0, dp(16), dp(8))
            }
            addView(status)
            list = RecyclerView(context).apply {
                layoutManager = GridLayoutManager(context, LIBRARY_COLUMNS)
                adapter = activeAdapter()
                setItemViewCacheSize(LIBRARY_COLUMNS * 2)
                clipToPadding = false
                clipChildren = false
                setPadding(dp(16), dp(12), dp(16), dp(20))
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

    override fun onShow() {
        val stored = host?.viewContext?.let(ContentModeSettings::get) ?: mode
        if (stored != mode) switchMode(stored, persist = false)
        if (loadJob?.isActive != true &&
            (activeAdapter().itemCount == 0 || LibraryArtworkRefresh.needed(loadedArtworkDay(), LocalDate.now().toString()))) {
            load(force = activeAdapter().itemCount > 0)
        }
        else restoreFocus()
    }

    override fun onHide() {
        rememberSelection()
        scope.coroutineContext.cancelChildren()
        loadJob = null
    }

    override fun onDestroyView() {
        scope.cancel()
        host = null
    }

    override fun requestInitialFocus(): Boolean {
        val count = activeAdapter().itemCount
        if (!::list.isInitialized || count == 0) return false
        val target = selectedIndex().coerceIn(0, count - 1)
        list.scrollToPosition(target)
        list.post { list.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus() }
        return true
    }

    override fun hints() = listOf(
        ButtonHint.activate(
            when {
                ::searchBox.isInitialized && searchBox.hasFocus() -> "Search"
                ::favourites.isInitialized && favourites.hasFocus() -> "Favourites"
                else -> "Open"
            }
        ),
        ButtonHint.secondary(if (mode == ContentMode.MEDIA) "Search" else "Books search"),
        ButtonHint.refresh()
    )

    override fun onPad(action: PadAction): Boolean = when (action) {
        PadAction.Activate -> focusedView()?.let { open(it) } != null
        PadAction.Secondary -> {
            if (mode == ContentMode.MEDIA) searchBox.requestFocus()
            else host?.switchSection(-1)
            true
        }
        PadAction.Refresh -> { load(force = true); true }
        else -> false
    }

    private fun load(force: Boolean = false) {
        if (loadJob?.isActive == true) return
        val generation = ++loadGeneration
        val requestedMode = mode
        status.showStatus(
            StatusText.loading(if (mode == ContentMode.MEDIA) "libraries" else "reading libraries", refreshing = force),
            colors
        )
        loadJob = scope.launch {
            if (requestedMode == ContentMode.MEDIA) {
                when (val result = api.library()) {
                    is HubResult.Ok -> if (generation == loadGeneration && requestedMode == mode) {
                        renderMedia(result.value)
                    }
                    is HubResult.Failed -> if (generation == loadGeneration && requestedMode == mode) {
                        renderFailure(result)
                    }
                }
            } else {
                when (val result = api.readingLibraries()) {
                    is HubResult.Ok -> if (generation == loadGeneration && requestedMode == mode) {
                        renderReading(result.value)
                    }
                    is HubResult.Failed -> if (generation == loadGeneration && requestedMode == mode) {
                        renderFailure(result)
                    }
                }
            }
            loadJob = null
        }
    }

    private fun renderFailure(result: HubResult.Failed) {
        status.showStatus(StatusText.failed(result.message, result.kind, hasData = activeAdapter().itemCount > 0), colors)
    }

    private fun renderMedia(body: LibraryResponse) {
        mediaAdapter.submit(body.views)
        mediaArtworkDay = LocalDate.now().toString()
        status.showStatus(
            if (body.views.isEmpty()) StatusMessage("No movie or TV libraries were found.")
            else StatusText.loaded("${body.views.size} libraries", body.cache, body.partial.map { it.service }),
            colors
        )
        restoreFocus()
        // The list is empty when showCurrent first draws the bar. Refresh after
        // its first focus settles as well, because this device can complete the
        // RecyclerView layout after the host's posted refresh.
        host?.refreshHints()
    }

    private fun renderReading(body: ReadingLibrariesResponse) {
        readingAdapter.submit(body.libraries + if(body.libraries.any { it.source=="kavita" }) listOf(
            ReadingLibrary(id="kavita:reading-lists",source="kavita",kind="reading_list",title="Reading lists")
        ) else emptyList())
        readingArtworkDay = LocalDate.now().toString()
        status.showStatus(
            if (body.libraries.isEmpty()) StatusMessage("No reading libraries were found.")
            else StatusText.loaded("${body.libraries.size} reading libraries", body.cache, body.partial.map { it.service }),
            colors
        )
        restoreFocus()
        host?.refreshHints()
    }

    private fun restoreFocus() {
        if (activeAdapter().itemCount == 0) return
        requestInitialFocus()
    }

    private fun focusedPosition(): Int {
        val focused = list.focusedChild ?: return -1
        return list.getChildAdapterPosition(focused)
    }
    private fun focusedView(): Any? = when (mode) {
        ContentMode.MEDIA -> mediaAdapter.at(focusedPosition())
        ContentMode.BOOKS -> readingAdapter.at(focusedPosition())
    }

    private fun open(view: Any) {
        rememberSelection()
        when (view) {
            is LibraryView -> host?.push(LibraryGridScreen(api, view, ringVisible))
            is ReadingLibrary -> if(view.id=="kavita:reading-lists") host?.push(ServerReadingListsScreen(api,ringVisible)) else host?.push(ReadingLibraryGridScreen(api, view, ringVisible))
        }
    }

    private fun switchMode(next: ContentMode, persist: Boolean) {
        if (next == mode) return
        rememberSelection()
        scope.coroutineContext.cancelChildren()
        loadJob = null
        loadGeneration++
        mode = next
        if (persist) ContentModeSettings.set(requireNotNull(host).viewContext, mode)
        heading.text = headingText()
        mediaTools.visibility = if (mode == ContentMode.MEDIA) View.VISIBLE else View.GONE
        list.adapter = activeAdapter()
        status.setTextColor(colors.mutedText)
        if (activeAdapter().itemCount == 0 || LibraryArtworkRefresh.needed(loadedArtworkDay(), LocalDate.now().toString())) {
            load(force = activeAdapter().itemCount > 0)
        }
        else {
            status.text = if (mode == ContentMode.MEDIA) "${mediaAdapter.itemCount} libraries"
            else "${readingAdapter.itemCount} reading libraries"
            restoreFocus()
        }
        host?.refreshHints()
    }

    override fun selectContentMode(mode: ContentMode) = switchMode(mode, persist = true)

    private fun headingText(): String = if (mode == ContentMode.MEDIA) {
        "Your Jellyfin libraries"
    } else {
        "Your reading libraries"
    }

    private fun activeAdapter(): RecyclerView.Adapter<*> = when (mode) {
        ContentMode.MEDIA -> mediaAdapter
        ContentMode.BOOKS -> readingAdapter
    }

    private fun loadedArtworkDay(): String = if (mode == ContentMode.MEDIA) mediaArtworkDay else readingArtworkDay

    private fun selectedIndex(): Int = if (mode == ContentMode.MEDIA) selectedMedia else selectedBooks

    private fun rememberSelection() {
        val position = focusedPosition().takeIf { it >= 0 } ?: return
        if (mode == ContentMode.MEDIA) selectedMedia = position else selectedBooks = position
    }

    private fun openSearch(raw: String) {
        val query = raw.trim()
        if (query.length < 2) {
            host?.notify("Type at least two characters")
            return
        }
        host?.push(
            LibraryGridScreen(
                api,
                LibraryView(id = query, name = "Search · $query", kind = "search"),
                ringVisible
            )
        )
    }

    private fun openFavourites() {
        host?.push(
            LibraryGridScreen(
                api,
                LibraryView(id = "favorites", name = "Favourites", kind = "favorites"),
                ringVisible
            )
        )
    }

    private inner class ViewAdapter : RecyclerView.Adapter<ViewHolder>() {
        private val values = mutableListOf<LibraryView>()
        fun submit(next: List<LibraryView>) { values.clear(); values.addAll(next); notifyDataSetChanged() }
        fun at(position: Int): LibraryView? = values.getOrNull(position)
        override fun getItemCount() = values.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val row = LibraryCardView(parent.context, colors).apply {
                layoutParams = RecyclerView.LayoutParams(MATCH, WRAP).apply {
                    setMargins(dp(12), dp(10), dp(12), dp(10))
                }
                FocusDecorator.attach(this, ringVisible)
                setOnFocusChangeListener { _, focused ->
                    FocusDecorator.refresh(this, ringVisible())
                    if (focused) {
                        selectedMedia = list.getChildAdapterPosition(this)
                        host?.refreshHints()
                    }
                }
                activateOnTap { (getTag(TAG_VIEW) as? LibraryView)?.let(::open) }
            }
            return ViewHolder(row)
        }
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val value = values[position]
            val row = holder.itemView as LibraryCardView
            row.setTag(TAG_VIEW, value)
            val client = api as? HubClient
            row.bind(value, client?.imageLoader ?: coil.ImageLoader(row.context), api::imageUrl)
        }
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
            val row = LibraryCardView(parent.context, colors).apply {
                layoutParams = RecyclerView.LayoutParams(MATCH, WRAP).apply {
                    setMargins(dp(12), dp(10), dp(12), dp(10))
                }
                FocusDecorator.attach(this, ringVisible)
                setOnFocusChangeListener { _, focused ->
                    FocusDecorator.refresh(this, ringVisible())
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
            val row = holder.itemView as LibraryCardView
            row.setTag(TAG_READING_VIEW, value)
            val client = api as? HubClient
            row.bindReading(
                value,
                client?.imageLoader ?: coil.ImageLoader(row.context),
                api::imageUrl
            )
        }
    }

    private class ViewHolder(view: View) : RecyclerView.ViewHolder(view)
    private fun dp(value: Int) = Styler.dpInt(requireNotNull(host).viewContext, value.toFloat())

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val LIBRARY_COLUMNS = 3
        const val TAG_VIEW = -0x7fffffe1
        const val TAG_READING_VIEW = -0x7fffffe0
    }
}

/** One server folder, alphabetically paged sixty titles at a time. */
class LibraryGridScreen(
    private val api: HubApi,
    private val library: LibraryView,
    private val ringVisible: () -> Boolean
) : Screen {
    override val contentDomain = com.pocketds.hub.state.ContentMode.MEDIA
    override val title = library.name
    override val horizontalMode = HorizontalMode.GRID

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var libraryRevision = MediaLibraryChanges.revision
    private val paging = PagedLoadState(PREFETCH_AHEAD)
    private val adapter = ItemAdapter()
    private lateinit var colors: PocketColors
    private lateinit var sortControl: CenteredIconTextView
    private lateinit var status: TextView
    private lateinit var grid: RecyclerView
    private lateinit var overlay: ChoiceOverlay
    private var host: ScreenHost? = null
    private var loadJob: Job? = null
    private var selected = 0
    private var selectedItemId = ""
    private var refreshing = false
    private var sortKey = "name"
    private var sortAscending = true
    private var loadGeneration = 0
    private var refreshOnReturn = false

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        val remembered=DomainPreferences.sort(host.viewContext,ContentMode.MEDIA,SORT_FIELDS.map { it.first },"name")
        sortKey=remembered.field;sortAscending=remembered.ascending
        colors = Theme.colors(host.viewContext)
        val root = FrameLayout(host.viewContext).apply { setBackgroundColor(colors.background) }
        val content = LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.VERTICAL
            status = TextView(context).apply {
                textSize = 11f
                setTextColor(colors.mutedText)
                setPadding(dp(12), dp(6), dp(12), dp(4))
            }
            val toolbar=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL;setPadding(dp(16),dp(2),dp(20),dp(2)) }
            toolbar.addView(status,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
            sortControl=LibrarySortPanel.control(context,colors,::showSortPanel).apply {
                text=LibrarySortPanel.label(SORT_FIELDS,SortPreference(sortKey,sortAscending))
                contentDescription="Sort library, $text"
            }
            if(library.kind in setOf("search","favorites"))sortControl.visibility=View.GONE
            toolbar.addView(sortControl)
            addView(toolbar)
            grid = RecyclerView(context).apply {
                layoutManager = GridLayoutManager(context, MAX_COLUMNS)
                adapter = this@LibraryGridScreen.adapter
                setItemViewCacheSize(MAX_COLUMNS * 3)
                clipToPadding = false
                clipChildren = false
                setPadding(dp(16), dp(12), dp(16), dp(20))
                layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
                addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
                        if(refreshing) return
                        val manager = view.layoutManager as GridLayoutManager
                        paging.next(
                            manager.findLastVisibleItemPosition(),
                            this@LibraryGridScreen.adapter.itemCount
                        )?.let(::loadPage)
                    }
                })
                addOnLayoutChangeListener { view, left, _, right, _, oldLeft, _, oldRight, _ ->
                    if (right - left == oldRight - oldLeft) return@addOnLayoutChangeListener
                    val columns = LibraryGridSizing.columns(
                        widthPx = view.width,
                        horizontalPaddingPx = view.paddingLeft + view.paddingRight,
                        density = resources.displayMetrics.density,
                        maxColumns = MAX_COLUMNS
                    )
                    val manager = layoutManager as GridLayoutManager
                    if (manager.spanCount != columns) manager.spanCount = columns
                }
            }
            addView(grid)
        }
        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))
        overlay = ChoiceOverlay(host.viewContext, colors, ringVisible, sidePanel=true)
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        return root
    }

    override fun onShow() {
        if(libraryRevision != MediaLibraryChanges.revision) {libraryRevision=MediaLibraryChanges.revision;reload();return}
        val saved=DomainPreferences.sort(requireNotNull(host).viewContext,ContentMode.MEDIA,SORT_FIELDS.map { it.first },"name")
        if(saved!=SortPreference(sortKey,sortAscending)) { applySort(saved); return }
        if (refreshOnReturn && library.kind in setOf("search", "favorites")) {
            refreshOnReturn = false
            reload()
        } else if (paging.loadedPage == 0 && loadJob?.isActive != true) {
            (paging.retry() ?: paging.initial())?.let(::loadPage)
        } else restoreFocus()
    }

    override fun onHide() {
        loadGeneration++
        selected = focusedPosition().takeIf { it >= 0 } ?: selected
        selectedItemId = focusedHit()?.jellyfinItemId ?: selectedItemId
        if (::overlay.isInitialized && overlay.isOpen) overlay.dismiss()
        paging.cancelLoading()
        scope.coroutineContext.cancelChildren()
        loadJob = null
    }

    override fun onDestroyView() { scope.cancel(); host = null }

    override fun requestInitialFocus(): Boolean {
        if (::overlay.isInitialized && overlay.isOpen) return true
        if (!::grid.isInitialized || adapter.itemCount == 0) return if(::sortControl.isInitialized) sortControl.requestFocus() else false
        val target = selected.coerceIn(0, adapter.itemCount - 1)
        grid.scrollToPosition(target)
        grid.post { grid.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus() }
        return true
    }

    override fun hints() = if (::overlay.isInitialized && overlay.isOpen) {
        listOf(ButtonHint.activate("Choose"), ButtonHint.back("Cancel"))
    } else {
        buildList {
            add(ButtonHint.activate("Details"))
            add(ButtonHint.back())
            if (library.kind !in setOf("search", "favorites")) add(ButtonHint.secondary("Sort"))
            add(ButtonHint.refresh())
        }
    }

    override fun onPad(action: PadAction): Boolean {
        if (::overlay.isInitialized && overlay.onPad(action)) {
            host?.refreshHints()
            return true
        }
        return when (action) {
            PadAction.Activate -> focusedHit()?.let(::open) != null
            PadAction.Secondary -> {
                if (library.kind !in setOf("search", "favorites")) {
                    showSortPanel()
                    true
                } else false
            }
            PadAction.Refresh -> {
                if (loadJob?.isActive != true) {
                    val retry = paging.retry()
                    if (retry != null) loadPage(retry) else reload()
                }
                true
            }
            else -> false
        }
    }

    private fun reload(resetSelection: Boolean = false) {
        loadGeneration++
        loadJob?.cancel()
        loadJob = null
        paging.reset()
        if (resetSelection) {
            selected = 0
            selectedItemId = ""
        } else {
            selectedItemId = focusedHit()?.jellyfinItemId ?: selectedItemId
        }
        refreshing = true
        paging.initial()?.let(::loadPage)
    }

    private fun loadPage(page: Int) {
        if (loadJob?.isActive == true) return
        val generation = loadGeneration
        status.setTextColor(colors.mutedText)
        status.text = when {
            refreshing -> "Refreshing ${library.name}…"
            adapter.itemCount == 0 -> "Loading ${library.name}…"
            else -> "Loading more…"
        }
        loadJob = scope.launch {
            val request = when (library.kind) {
                "search" -> api.librarySearch(library.id, page)
                "favorites" -> api.libraryFavorites(page)
                else -> api.libraryItems(
                    library.id,
                    page,
                    sortKey,
                    if (sortAscending) "asc" else "desc"
                )
            }
            when (val result = request) {
                is HubResult.Ok -> {
                    if (generation != loadGeneration) return@launch
                    paging.complete(page, result.value.totalPages)
                    if (page == 1 && refreshing) {
                        adapter.replace(result.value.items)
                        selected = adapter.indexOf(selectedItemId).takeIf { it >= 0 } ?: 0
                        refreshing = false
                    } else adapter.append(result.value.items)
                    val empty = result.value.items.isEmpty() && adapter.itemCount == 0
                    status.showStatus(
                        when {
                            empty && library.kind == "favorites" -> StatusMessage("No favourites yet.")
                            empty && library.kind == "search" -> StatusMessage("No Jellyfin matches.")
                            empty -> StatusMessage("This library is empty.")
                            else -> StatusText.loaded(
                                when (library.kind) {
                                    "search" -> "${adapter.itemCount} of ${result.value.total} matches"
                                    "favorites" -> "${adapter.itemCount} of ${result.value.total} favourites"
                                    else -> "${adapter.itemCount} of ${result.value.total} · ${sortLabel()}"
                                },
                                result.value.cache,
                                result.value.partial.map { it.service }
                            )
                        },
                        colors
                    )
                    if (page == 1 && !overlay.isOpen) restoreFocus()
                    host?.refreshHints()
                }
                is HubResult.Failed -> {
                    if (generation != loadGeneration) return@launch
                    paging.fail(page)
                    status.showStatus(StatusText.failed(result.message, result.kind, hasData = adapter.itemCount > 0), colors)
                    host?.refreshHints()
                }
            }
            loadJob = null
        }
    }

    private fun applySort(value:SortPreference) {
        sortKey=value.field;sortAscending=value.ascending
        DomainPreferences.setSort(requireNotNull(host).viewContext,ContentMode.MEDIA,value)
        sortControl.text=LibrarySortPanel.label(SORT_FIELDS,value)
        sortControl.contentDescription="Sort library, ${sortControl.text}"
        reload(resetSelection=true)
    }
    private fun showSortPanel() {
        LibrarySortPanel.show(overlay,sortControl,SORT_FIELDS,SortPreference(sortKey,sortAscending),::applySort,{host?.refreshHints()})
        host?.refreshHints()
    }

    private fun sortLabel(): String {
        val field = SORT_FIELDS.firstOrNull { it.first == sortKey }?.second ?: "Name"
        return "$field ${if (sortAscending) "ascending" else "descending"}"
    }

    private fun restoreFocus() { if (adapter.itemCount > 0) requestInitialFocus() }
    private fun focusedPosition(): Int {
        val focused = grid.focusedChild ?: return -1
        return grid.getChildAdapterPosition(focused)
    }
    private fun focusedHit(): SearchHit? = adapter.at(focusedPosition())
    private fun open(hit: SearchHit) {
        if (hit.jellyfinItemId.isEmpty()) {
            host?.notify("This Jellyfin item no longer exists")
            return
        }
        selected = focusedPosition().coerceAtLeast(0)
        refreshOnReturn = true
        host?.push(LibraryDetailScreen(api, hit.jellyfinItemId, hit.media.title, hit.media.type, ringVisible))
    }

    private inner class ItemAdapter : RecyclerView.Adapter<ItemHolder>() {
        private val values = mutableListOf<SearchHit>()
        fun at(position: Int) = values.getOrNull(position)
        fun indexOf(itemId: String) = values.indexOfFirst { it.jellyfinItemId == itemId }
        fun replace(next: List<SearchHit>) {
            values.clear()
            values.addAll(next.distinctBy { it.jellyfinItemId })
            notifyDataSetChanged()
        }
        fun append(next: List<SearchHit>) {
            val known = values.asSequence().map { it.jellyfinItemId }.toHashSet()
            val added = next.filter { known.add(it.jellyfinItemId) }
            val start = values.size
            values.addAll(added)
            if (added.isNotEmpty()) notifyItemRangeInserted(start, added.size)
        }
        override fun getItemCount() = values.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemHolder {
            val card = PosterCardView(parent.context, colors, POSTER_DP).apply {
                layoutParams = RecyclerView.LayoutParams(dp(CARD_DP), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp(8), dp(8), dp(8), dp(8))
                }
                FocusDecorator.attach(this, ringVisible)
                setOnFocusChangeListener { _, focused ->
                    FocusDecorator.refresh(this, ringVisible())
                    if (focused) {
                        selected = grid.getChildAdapterPosition(this)
                        selectedItemId = (getTag(TAG_HIT) as? SearchHit)?.jellyfinItemId.orEmpty()
                        host?.refreshHints()
                    }
                }
                activateOnTap { (getTag(TAG_HIT) as? SearchHit)?.let(::open) }
            }
            return ItemHolder(card)
        }
        override fun onBindViewHolder(holder: ItemHolder, position: Int) {
            val hit = values[position]
            val card = holder.itemView as PosterCardView
            card.setTag(TAG_HIT, hit)
            val client = api as? HubClient
            card.bind(
                hit,
                client?.imageLoader ?: coil.ImageLoader(card.context),
                api::imageUrl,
                showAvailability = false
            )
        }
    }

    private class ItemHolder(view: View) : RecyclerView.ViewHolder(view)
    private fun dp(value: Int) = Styler.dpInt(requireNotNull(host).viewContext, value.toFloat())

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val MAX_COLUMNS = 7
        const val PREFETCH_AHEAD = 6
        const val POSTER_DP = 150f
        const val CARD_DP = 104
        const val TAG_HIT = -0x7fffffe2
        val SORT_FIELDS = listOf(
            "name" to "Name",
            "release" to "Release date",
            "added" to "Date added",
            "year" to "Year",
            "rating" to "Rating",
            "played" to "Last played",
            "parental" to "Parental rating"
        )
    }
}
