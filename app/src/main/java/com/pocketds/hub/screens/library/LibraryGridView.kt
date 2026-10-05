package com.pocketds.hub.screens.library

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.LibraryView
import com.pocketds.hub.model.SearchHit
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.settings.DomainPreferences
import com.pocketds.hub.settings.SortPreference
import com.pocketds.hub.state.ContentMode
import com.pocketds.hub.state.HitRefresh
import com.pocketds.hub.state.LibraryGridSizing
import com.pocketds.hub.state.PagedLoadState
import com.pocketds.hub.state.StatusText
import com.pocketds.hub.ui.Artwork
import com.pocketds.hub.ui.ChoiceOverlay
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.LibrarySortControls
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.PosterCardView
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.activateOnTap
import com.pocketds.hub.ui.showStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch

/**
 * One Jellyfin folder (or Favourites, or a search) as a poster grid, paged
 * sixty titles at a time in the server's sort order.
 *
 * A view rather than a screen so the Library tab can show it right under its
 * library chips and swap folders in place; search results still push it in
 * [LibraryGridScreen]. It keeps the place it was at in each folder.
 */
class LibraryGridView(
    context: Context,
    private val api: HubApi,
    private val colors: PocketColors,
    private val ringVisible: () -> Boolean,
    private val host: ScreenHost,
    overlay: () -> ChoiceOverlay
) : FrameLayout(context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var libraryRevision = MediaLibraryChanges.revision
    private val paging = PagedLoadState(PREFETCH_AHEAD)
    private val adapter = ItemAdapter()
    private var loadJob: Job? = null
    private var selected = 0
    private var selectedItemId = ""
    private var refreshing = false
    private var sortKey = "name"
    private var sortAscending = true
    private var loadGeneration = 0
    private var refreshOnReturn = false
    /** Where each loaded page begins in the grid, so a return can patch just that page. */
    private val pageStarts = HashMap<Int, Int>()
    private var patchJob: Job? = null
    /** The place each folder was left at: position and title id. */
    private val places = HashMap<String, Pair<Int, String>>()

    var library: LibraryView? = null
        private set

    val status = TextView(context).apply {
        textSize = 11f
        setTextColor(colors.mutedText)
    }

    val sortControls: LibrarySortControls

    val grid: RecyclerView = RecyclerView(context).apply {
        layoutManager = GridLayoutManager(context, MAX_COLUMNS)
        adapter = this@LibraryGridView.adapter
        setItemViewCacheSize(MAX_COLUMNS * 3)
        isFocusable = false
        clipToPadding = false
        clipChildren = false
        setPadding(dp(17), dp(6), dp(17), dp(20))
        addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
                if (refreshing) return
                val manager = view.layoutManager as GridLayoutManager
                paging.next(manager.findLastVisibleItemPosition(), this@LibraryGridView.adapter.itemCount)?.let(::loadPage)
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

    init {
        val remembered = DomainPreferences.sort(context, ContentMode.MEDIA, SORT_FIELDS.map { it.first }, "name")
        sortKey = remembered.field
        sortAscending = remembered.ascending
        sortControls = LibrarySortControls(context, colors, SORT_FIELDS, remembered, overlay, ::applySort) { host.refreshHints() }
        clipChildren = false
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private val sorted: Boolean get() = library?.kind !in setOf("search", "favorites")

    /** Shows [next], or carries on with it if it is already showing. */
    fun show(next: LibraryView) {
        if (library?.id == next.id && library?.kind == next.kind) return
        library?.let { places[it.id] = (focusedPosition().takeIf { it >= 0 } ?: selected) to (focusedHit()?.jellyfinItemId ?: selectedItemId) }
        library = next
        val place = places[next.id]
        selected = place?.first ?: 0
        selectedItemId = place?.second.orEmpty()
        sortControls.visibility = if (sorted) View.VISIBLE else View.GONE
        loadGeneration++
        loadJob?.cancel()
        loadJob = null
        paging.reset()
        pageStarts.clear()
        adapter.replace(emptyList())
        refreshing = false
        paging.initial()?.let(::loadPage)
    }

    fun onShow() {
        if (library == null) return
        if (libraryRevision != MediaLibraryChanges.revision) { libraryRevision = MediaLibraryChanges.revision; reload(); return }
        val saved = DomainPreferences.sort(context, ContentMode.MEDIA, SORT_FIELDS.map { it.first }, "name")
        if (sorted && saved != SortPreference(sortKey, sortAscending)) { sortControls.update(saved); applySort(saved); return }
        if (refreshOnReturn && !sorted) {
            refreshOnReturn = false
            reload()
        } else if (paging.loadedPage == 0 && loadJob?.isActive != true) {
            (paging.retry() ?: paging.initial())?.let(::loadPage)
        } else if (refreshOnReturn) {
            refreshOnReturn = false
            patchReturnedPage()
        }
    }

    fun onHide() {
        loadGeneration++
        selected = focusedPosition().takeIf { it >= 0 } ?: selected
        selectedItemId = focusedHit()?.jellyfinItemId ?: selectedItemId
        paging.cancelLoading()
        scope.coroutineContext.cancelChildren()
        loadJob = null
    }

    fun destroy() = scope.cancel()

    /**
     * Back from a detail page -- often from playing it -- the card's watched
     * badge and progress are out of date. Search and Favourites reload because
     * their membership can change; a folder keeps its scroll and focus and
     * re-reads only the page the opened title came from.
     */
    private fun patchReturnedPage() {
        val library = library ?: return
        val page = HitRefresh.pageOf(selected, pageStarts) ?: return
        patchJob?.cancel()
        patchJob = scope.launch {
            val result = api.libraryItems(library.id, page, sortKey, if (sortAscending) "asc" else "desc")
            if (result is HubResult.Ok) adapter.patch(HitRefresh.changes(adapter.values(), result.value.items) { it.jellyfinItemId })
        }
    }

    val hasItems: Boolean get() = adapter.itemCount > 0
    /** The first page is still on its way. */
    val loading: Boolean get() = adapter.itemCount == 0 && loadJob?.isActive == true

    fun requestInitialFocus(): Boolean {
        if (adapter.itemCount == 0) return false
        val target = selected.coerceIn(0, adapter.itemCount - 1)
        grid.scrollToPosition(target)
        grid.post { grid.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus() }
        return true
    }

    /** The focused card is on the grid's top line. */
    fun inFirstRow(): Boolean {
        val position = focusedPosition().takeIf { it >= 0 } ?: return false
        return position < (grid.layoutManager as GridLayoutManager).spanCount
    }

    fun hints(): List<ButtonHint> = buildList {
        add(ButtonHint.activate("Details"))
        if (sorted) add(ButtonHint.secondary("Sort"))
        add(ButtonHint.refresh())
    }

    fun onPad(action: PadAction): Boolean = when (action) {
        PadAction.Activate -> grid.hasFocus() && focusedHit()?.let(::open) != null
        PadAction.Secondary -> if (sorted) { sortControls.showFields(); true } else false
        PadAction.Refresh -> {
            if (loadJob?.isActive != true) {
                val retry = paging.retry()
                if (retry != null) loadPage(retry) else reload()
            }
            true
        }
        else -> false
    }

    private fun reload(resetSelection: Boolean = false) {
        loadGeneration++
        loadJob?.cancel()
        loadJob = null
        paging.reset()
        pageStarts.clear()
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
        val library = library ?: return
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
                "search" -> api.librarySearch(library.id, page, searchIn)
                "favorites" -> api.libraryFavorites(page)
                else -> api.libraryItems(library.id, page, sortKey, if (sortAscending) "asc" else "desc")
            }
            when (val result = request) {
                is HubResult.Ok -> {
                    if (generation != loadGeneration) return@launch
                    paging.complete(page, result.value.totalPages)
                    if (page == 1 && (refreshing || adapter.itemCount == 0)) {
                        pageStarts.clear()
                        pageStarts[1] = 0
                        adapter.replace(result.value.items)
                        selected = adapter.indexOf(selectedItemId).takeIf { it >= 0 } ?: selected.coerceAtMost((adapter.itemCount - 1).coerceAtLeast(0))
                        refreshing = false
                    } else {
                        pageStarts[page] = adapter.itemCount
                        adapter.append(result.value.items)
                    }
                    // Their colours before focus reaches them, so the page re-tints at once.
                    host.prefetchArtwork(result.value.items.take(PREFETCH_COLOURS)
                        .mapNotNull { com.pocketds.hub.nav.PageArtwork.title(it.media.backdrop, it.media.poster) })
                    val empty = result.value.items.isEmpty() && adapter.itemCount == 0
                    status.showStatus(
                        when {
                            empty && library.kind == "favorites" -> StatusText.notice("No favourites yet. Star a title on its page.")
                            empty && library.kind == "search" -> StatusText.notice("No Jellyfin matches.")
                            empty -> StatusText.notice("This library is empty.")
                            else -> StatusText.loaded(
                                when (library.kind) {
                                    "search" -> "${adapter.itemCount} of ${result.value.total} matches"
                                    "favorites" -> "${adapter.itemCount} of ${result.value.total} favourites"
                                    else -> "${result.value.total} title${if (result.value.total == 1) "" else "s"}"
                                },
                                result.value.cache,
                                result.value.partial.map { it.service }
                            )
                        },
                        colors
                    )
                    if (page == 1 && wantsFocus()) requestInitialFocus()
                    host.refreshHints()
                }
                is HubResult.Failed -> {
                    if (generation != loadGeneration) return@launch
                    paging.fail(page)
                    status.showStatus(StatusText.failed(result.message, result.kind, hasData = adapter.itemCount > 0), colors)
                    host.refreshHints()
                }
            }
            loadJob = null
        }
    }

    /** Focus goes to the grid after a load unless the person is busy elsewhere on the screen. */
    var wantsFocus: () -> Boolean = { true }

    /** A search's library (#14): its view id keeps the search inside it; blank searches everything. */
    var searchIn: String = ""

    private fun applySort(value: SortPreference) {
        sortKey = value.field
        sortAscending = value.ascending
        DomainPreferences.setSort(context, ContentMode.MEDIA, value)
        reload(resetSelection = true)
    }

    private fun focusedPosition(): Int {
        val focused = grid.focusedChild ?: return -1
        return grid.getChildAdapterPosition(focused)
    }

    private fun focusedHit(): SearchHit? = adapter.at(focusedPosition())

    /**
     * Glass: the picture of the title in focus, for the page behind the grid;
     * the last one focused while focus is on the controls above it.
     */
    val artwork: String?
        get() = (focusedHit() ?: adapter.at(selected))?.let { com.pocketds.hub.nav.PageArtwork.title(it.media.backdrop, it.media.poster) }

    private fun open(hit: SearchHit) {
        if (hit.jellyfinItemId.isEmpty()) {
            host.notify("This Jellyfin item no longer exists")
            return
        }
        selected = focusedPosition().coerceAtLeast(0)
        refreshOnReturn = true
        host.push(LibraryDetailScreen(api, hit.jellyfinItemId, hit.media.title, hit.media.type, ringVisible))
    }

    private inner class ItemAdapter : RecyclerView.Adapter<ItemHolder>() {
        private val values = mutableListOf<SearchHit>()
        fun at(position: Int) = values.getOrNull(position)
        fun values(): List<SearchHit> = values
        fun indexOf(itemId: String) = values.indexOfFirst { it.jellyfinItemId == itemId }
        /** A payload keeps each card's holder, so the focused card stays focused. */
        fun patch(changes: List<IndexedValue<SearchHit>>) = changes.forEach { (position, value) ->
            values[position] = value
            notifyItemChanged(position, PAYLOAD_STATE)
        }
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
            // The prototype's posters, filling the columns, with a title and year under each (#11).
            val card = PosterCardView(parent.context, colors, POSTER_DP, glass = true).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp(5), dp(6), dp(5), dp(6))
                }
                FocusDecorator.attach(this, ringVisible)
                FocusDecorator.listen(this, ringVisible) { _, focused ->
                    if (focused) {
                        selected = grid.getChildAdapterPosition(this)
                        selectedItemId = (getTag(TAG_HIT) as? SearchHit)?.jellyfinItemId.orEmpty()
                        host.refreshHints()
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
            card.bind(hit, Artwork.loader(api, card.context), api::imageUrl, showAvailability = false)
        }
    }

    private class ItemHolder(view: View) : RecyclerView.ViewHolder(view)
    private fun dp(value: Int) = Styler.dpInt(context, value.toFloat())

    companion object {
        const val MAX_COLUMNS = 7
        private const val PREFETCH_AHEAD = 6
        /** How many of a page's titles to ask the page colours for as it arrives. */
        private const val PREFETCH_COLOURS = 21
        private const val PAYLOAD_STATE = "state"
        private const val POSTER_DP = 150f
        private const val TAG_HIT = -0x7fffffe2
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
