package com.pocketds.hub.screens.library

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
import com.pocketds.hub.model.SearchHit
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.state.LibraryGridSizing
import com.pocketds.hub.state.PagedLoadState
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.LibraryCardView
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

/** The Jellyfin folders exactly as the server names and orders them. */
class LibraryScreen(
    private val api: HubApi,
    private val ringVisible: () -> Boolean
) : Screen {
    override val title = "Library"
    override val horizontalMode = HorizontalMode.GRID

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val adapter = ViewAdapter()
    private lateinit var colors: PocketColors
    private lateinit var status: TextView
    private lateinit var list: RecyclerView
    private lateinit var searchBox: EditText
    private lateinit var favourites: TextView
    private var host: ScreenHost? = null
    private var loadJob: Job? = null
    private var selected = 0

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        colors = Theme.colors(host.viewContext)
        return LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.background)
            addView(TextView(context).apply {
                text = "Your Jellyfin libraries"
                textSize = 18f
                setTextColor(colors.primaryText)
                setPadding(dp(16), dp(12), dp(16), dp(4))
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(12), dp(2), dp(12), dp(7))
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
                    FocusDecorator.attach(this, ringVisible)
                    setOnFocusChangeListener { view, _ ->
                        FocusDecorator.refresh(view, ringVisible())
                        host.refreshHints()
                    }
                }
                addView(searchBox, LinearLayout.LayoutParams(0, dp(43), 1f))
                favourites = TextView(context).apply {
                    text = "★  Favourites"
                    textSize = 13f
                    gravity = android.view.Gravity.CENTER
                    setTextColor(colors.primaryText)
                    background = Styler.cardBackground(context, colors)
                    setPadding(dp(14), 0, dp(14), 0)
                    Styler.makeFocusable(this)
                    FocusDecorator.attach(this, ringVisible)
                    setOnFocusChangeListener { view, _ ->
                        FocusDecorator.refresh(view, ringVisible())
                        host.refreshHints()
                    }
                    activateOnTap { openFavourites() }
                }
                addView(favourites, LinearLayout.LayoutParams(WRAP, dp(43)).apply {
                    marginStart = dp(8)
                })
            })
            status = TextView(context).apply {
                textSize = 11f
                setTextColor(colors.mutedText)
                setPadding(dp(16), 0, dp(16), dp(8))
            }
            addView(status)
            list = RecyclerView(context).apply {
                layoutManager = GridLayoutManager(context, LIBRARY_COLUMNS)
                adapter = this@LibraryScreen.adapter
                setItemViewCacheSize(LIBRARY_COLUMNS * 2)
                clipToPadding = false
                clipChildren = false
                setPadding(dp(12), dp(2), dp(12), dp(84))
                layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            }
            addView(list)
        }
    }

    override fun onShow() {
        if (adapter.itemCount == 0 && loadJob?.isActive != true) load()
        else restoreFocus()
    }

    override fun onHide() {
        selected = focusedPosition().takeIf { it >= 0 } ?: selected
        scope.coroutineContext.cancelChildren()
        loadJob = null
    }

    override fun onDestroyView() {
        scope.cancel()
        host = null
    }

    override fun requestInitialFocus(): Boolean {
        if (!::list.isInitialized || adapter.itemCount == 0) return false
        val target = selected.coerceIn(0, adapter.itemCount - 1)
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
        ButtonHint.secondary("Search"),
        ButtonHint("⟳", "Refresh (Select)", PadAction.Refresh)
    )

    override fun onPad(action: PadAction): Boolean = when (action) {
        PadAction.Activate -> focusedView()?.let { open(it) } != null
        PadAction.Secondary -> {
            searchBox.requestFocus()
            true
        }
        PadAction.Refresh -> { load(force = true); true }
        else -> false
    }

    private fun load(force: Boolean = false) {
        if (loadJob?.isActive == true) return
        if (force) status.text = "Refreshing…" else status.text = "Asking Jellyfin…"
        loadJob = scope.launch {
            when (val result = api.library()) {
                is HubResult.Ok -> render(result.value)
                is HubResult.Failed -> {
                    status.setTextColor(colors.dangerText)
                    status.text = result.message + " · Select retries"
                }
            }
        }
    }

    private fun render(body: LibraryResponse) {
        adapter.submit(body.views)
        status.setTextColor(if (body.partial.isEmpty()) colors.mutedText else colors.badgePending)
        status.text = when {
            body.views.isEmpty() -> "No movie or TV libraries were found."
            body.partial.isNotEmpty() -> body.partial.joinToString(" · ") { it.message }
            body.cache.stale -> "Showing cached libraries"
            else -> "${body.views.size} libraries"
        }
        restoreFocus()
        // The list is empty when showCurrent first draws the bar. Refresh after
        // its first focus settles as well, because this device can complete the
        // RecyclerView layout after the host's posted refresh.
        host?.refreshHints()
    }

    private fun restoreFocus() {
        if (adapter.itemCount == 0) return
        requestInitialFocus()
    }

    private fun focusedPosition(): Int {
        val focused = list.focusedChild ?: return -1
        return list.getChildAdapterPosition(focused)
    }
    private fun focusedView(): LibraryView? = adapter.at(focusedPosition())

    private fun open(view: LibraryView) {
        selected = focusedPosition().coerceAtLeast(0)
        host?.push(LibraryGridScreen(api, view, ringVisible))
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
                layoutParams = RecyclerView.LayoutParams(dp(LIBRARY_CARD_DP), dp(LIBRARY_CARD_DP)).apply {
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                }
                FocusDecorator.attach(this, ringVisible)
                setOnFocusChangeListener { _, focused ->
                    FocusDecorator.refresh(this, ringVisible())
                    if (focused) {
                        selected = list.getChildAdapterPosition(this)
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

    private class ViewHolder(view: View) : RecyclerView.ViewHolder(view)
    private fun dp(value: Int) = Styler.dpInt(requireNotNull(host).viewContext, value.toFloat())

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val LIBRARY_COLUMNS = 3
        const val LIBRARY_CARD_DP = 176
        const val TAG_VIEW = -0x7fffffe1
    }
}

/** One server folder, alphabetically paged sixty titles at a time. */
class LibraryGridScreen(
    private val api: HubApi,
    private val library: LibraryView,
    private val ringVisible: () -> Boolean
) : Screen {
    override val title = library.name
    override val horizontalMode = HorizontalMode.GRID

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val paging = PagedLoadState(PREFETCH_AHEAD)
    private val adapter = ItemAdapter()
    private lateinit var colors: PocketColors
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
        colors = Theme.colors(host.viewContext)
        val root = FrameLayout(host.viewContext).apply { setBackgroundColor(colors.background) }
        val content = LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.VERTICAL
            status = TextView(context).apply {
                textSize = 11f
                setTextColor(colors.mutedText)
                setPadding(dp(12), dp(6), dp(12), dp(4))
            }
            addView(status)
            grid = RecyclerView(context).apply {
                layoutManager = GridLayoutManager(context, MAX_COLUMNS)
                adapter = this@LibraryGridScreen.adapter
                setItemViewCacheSize(MAX_COLUMNS * 3)
                clipToPadding = false
                clipChildren = false
                setPadding(dp(6), 0, dp(6), dp(84))
                layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
                addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
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
        overlay = ChoiceOverlay(host.viewContext, colors, ringVisible)
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        return root
    }

    override fun onShow() {
        if (refreshOnReturn && library.kind in setOf("search", "favorites")) {
            refreshOnReturn = false
            reload()
        } else if (paging.loadedPage == 0 && loadJob?.isActive != true) {
            (paging.retry() ?: paging.initial())?.let(::loadPage)
        } else restoreFocus()
    }

    override fun onHide() {
        selected = focusedPosition().takeIf { it >= 0 } ?: selected
        selectedItemId = focusedHit()?.jellyfinItemId ?: selectedItemId
        if (::overlay.isInitialized && overlay.isOpen) overlay.dismiss()
        paging.cancelLoading()
        scope.coroutineContext.cancelChildren()
        loadJob = null
    }

    override fun onDestroyView() { scope.cancel(); host = null }

    override fun requestInitialFocus(): Boolean {
        if (!::grid.isInitialized || adapter.itemCount == 0) return false
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
            add(ButtonHint("⟳", "Refresh (Select)", PadAction.Refresh))
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
                    showSortFields()
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
                    status.setTextColor(
                        if (result.value.partial.isEmpty()) colors.mutedText else colors.badgePending
                    )
                    status.text = when {
                        result.value.items.isEmpty() && adapter.itemCount == 0 && library.kind == "favorites" ->
                            "No favourites yet."
                        result.value.items.isEmpty() && adapter.itemCount == 0 && library.kind == "search" ->
                            "No Jellyfin matches."
                        result.value.items.isEmpty() && adapter.itemCount == 0 -> "This library is empty."
                        result.value.cache.stale -> "${adapter.itemCount} of ${result.value.total} · cached"
                        library.kind == "search" -> "${adapter.itemCount} of ${result.value.total} matches"
                        library.kind == "favorites" -> "${adapter.itemCount} of ${result.value.total} favourites"
                        else -> "${adapter.itemCount} of ${result.value.total} · ${sortLabel()}"
                    }
                    if (page == 1) restoreFocus()
                    host?.refreshHints()
                }
                is HubResult.Failed -> {
                    if (generation != loadGeneration) return@launch
                    paging.fail(page)
                    status.setTextColor(colors.dangerText)
                    status.text = result.message + if (adapter.itemCount == 0) " · Select retries"
                        else " · showing previous items · Select retries"
                    host?.refreshHints()
                }
            }
            loadJob = null
        }
    }

    private fun showSortFields() {
        overlay.show(
            title = "Sort ${library.name}",
            subtitle = "Choose what the library is ordered by",
            choices = SORT_FIELDS.map { (id, label) ->
                ChoiceOverlay.Choice(id, label, if (id == sortKey) "Currently selected" else "")
            },
            startIndex = SORT_FIELDS.indexOfFirst { it.first == sortKey }.coerceAtLeast(0),
            onCancel = { host?.refreshHints() }
        ) { picked -> showSortDirection(picked) }
        host?.refreshHints()
    }

    private fun showSortDirection(field: String) {
        overlay.show(
            title = "Sort direction",
            subtitle = SORT_FIELDS.firstOrNull { it.first == field }?.second.orEmpty(),
            choices = listOf(
                ChoiceOverlay.Choice("asc", "Ascending", "A to Z, oldest or lowest first"),
                ChoiceOverlay.Choice("desc", "Descending", "Z to A, newest or highest first")
            ),
            startIndex = if (sortAscending) 0 else 1,
            onCancel = { host?.refreshHints() }
        ) { direction ->
            val ascending = direction == "asc"
            val changed = field != sortKey || ascending != sortAscending
            sortKey = field
            sortAscending = ascending
            if (changed) reload(resetSelection = true)
            host?.refreshHints()
        }
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
                    setMargins(dp(4), dp(4), dp(4), dp(6))
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
