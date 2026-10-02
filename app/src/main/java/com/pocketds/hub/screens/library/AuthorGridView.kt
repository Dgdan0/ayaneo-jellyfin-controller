package com.pocketds.hub.screens.library

import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pocketds.hub.model.ReadingAuthor
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.state.LibraryGridSizing
import com.pocketds.hub.state.StatusText
import com.pocketds.hub.ui.Artwork
import com.pocketds.hub.ui.DetailArtworkCardView
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.InitialsDrawable
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.activateOnTap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The Authors view of a book library: one poster per author, their portrait or
 * initials, with "2 series · 6 books" under the name. A opens the author page,
 * where each series is a row. It replaced a list of author rows that showed
 * about one author per screen.
 */
class AuthorGridView(
    context: Context,
    private val api: HubApi,
    private val libraryId: String,
    private val colors: PocketColors,
    private val ringVisible: () -> Boolean,
    private val onStatus: (String, Boolean) -> Unit,
    private val onReady: () -> Unit,
    private val onOpen: (ReadingAuthor) -> Unit
) : RecyclerView(context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val authors = mutableListOf<ReadingAuthor>()
    private val loader = Artwork.loader(api, context)
    private var job: Job? = null
    private var generation = 0
    private var direction = ""
    private var requested = "asc"
    private var page = 0
    private var totalPages = 0
    private var selected = ""
    private var visible = false

    init {
        layoutManager = GridLayoutManager(context, 6)
        adapter = Cards()
        clipToPadding = false; clipChildren = false
        setPadding(dp(16), dp(12), dp(16), dp(84))
        addOnScrollListener(object : OnScrollListener() {
            override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
                val last = (layoutManager as GridLayoutManager).findLastVisibleItemPosition()
                if (last >= authors.size - 6 && page < totalPages && direction == requested) load(page + 1)
            }
        })
        addOnLayoutChangeListener { view, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left == oldRight - oldLeft) return@addOnLayoutChangeListener
            val columns = LibraryGridSizing.columns(view.width, view.paddingLeft + view.paddingRight,
                resources.displayMetrics.density, MAX_COLUMNS)
            (layoutManager as GridLayoutManager).spanCount = columns
        }
    }

    fun show(ascending: Boolean, force: Boolean = false) {
        val next = if (ascending) "asc" else "desc"
        visible = true
        if (force || page == 0 || next != direction) { requested = next; load(1) } else onReady()
    }

    fun hide() { visible = false; generation++; job?.cancel(); job = null }
    fun destroy() { hide(); scope.cancel() }

    fun restoreFocus(): Boolean {
        if (authors.isEmpty()) return false
        val index = authors.indexOfFirst { it.id == selected }.coerceAtLeast(0)
        scrollToPosition(index)
        post { findViewHolderForAdapterPosition(index)?.itemView?.requestFocus() }
        return true
    }

    private fun load(next: Int) {
        if (!visible || (job?.isActive == true && next != 1)) return
        job?.cancel()
        val token = ++generation
        val order = requested
        if (authors.isEmpty()) onStatus("Loading authors…", false)
        job = scope.launch {
            when (val result = api.readingAuthors(libraryId, next, order)) {
                is HubResult.Ok -> if (visible && token == generation) {
                    if (next == 1) authors.clear()
                    result.value.authors.forEach { author -> if (authors.none { it.id == author.id }) authors.add(author) }
                    page = next; totalPages = result.value.totalPages; direction = order
                    adapter?.notifyDataSetChanged(); onReady()
                    onStatus(if (authors.isEmpty()) "No authors in this library"
                        else StatusText.loaded("${result.value.total} authors", result.value.cache).text, false)
                    if (next == 1) restoreFocus()
                }
                is HubResult.Failed -> if (visible && token == generation) {
                    onStatus(StatusText.failed(result.message, result.kind, hasData = authors.isNotEmpty()).text, true)
                }
            }
        }
    }

    private inner class Cards : Adapter<ViewHolder>() {
        override fun getItemCount() = authors.size
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): ViewHolder =
            object : ViewHolder(DetailArtworkCardView(context, colors, ringVisible).apply {
                portrait(PORTRAIT_DP)
                layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(dp(6), dp(6), dp(6), dp(10)) }
            }) {}

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val author = authors[position]
            val card = holder.itemView as DetailArtworkCardView
            card.titleView.text = author.name
            card.subtitleView.text = AuthorLabels.shelf(author.seriesCount, author.bookCount, author.total)
            card.contentDescription = "${author.name}, ${card.subtitleView.text}"
            Artwork.bind(card.image, loader, author.artwork.takeIf { it.isNotBlank() }?.let(api::imageUrl),
                onMissing = { card.image.setImageDrawable(InitialsDrawable(author.name, colors)) }, opaque = true)
            FocusDecorator.listen(card, ringVisible) { _, focused -> if (focused) selected = author.id }
            card.activateOnTap { selected = author.id; onOpen(author) }
        }
    }

    private fun dp(value: Int) = Styler.dpInt(context, value.toFloat())

    private companion object {
        const val MAX_COLUMNS = 7
        /** A writer is a round portrait, or their initials in one. */
        const val PORTRAIT_DP = 100
    }
}

/** Pure wording for an author's shelf. */
object AuthorLabels {
    /** "2 series · 6 books", "2 books", "1 series". Older hubs send only the item total. */
    fun shelf(seriesCount: Int, bookCount: Int, total: Int): String {
        val books = if (bookCount > 0) bookCount else total
        return buildList {
            if (seriesCount > 0) add(if (seriesCount == 1) "1 series" else "$seriesCount series")
            if (books > 0) add(if (books == 1) "1 book" else "$books books")
        }.joinToString(" · ")
    }

    /**
     * How far through their books you are: "3 in progress · 1 finished",
     * "All 6 finished"; null before any is started, so the line says nothing.
     */
    fun reading(progress: List<com.pocketds.hub.model.ReadingProgress?>): String? {
        val finished = progress.count { it?.completed == true }
        val going = progress.count { it != null && !it.completed && it.percentage > 0 }
        if (progress.isNotEmpty() && finished == progress.size) return "All $finished finished"
        return listOfNotNull(
            "$going in progress".takeIf { going > 0 },
            "$finished finished".takeIf { finished > 0 }
        ).joinToString(" · ").ifEmpty { null }
    }
}
