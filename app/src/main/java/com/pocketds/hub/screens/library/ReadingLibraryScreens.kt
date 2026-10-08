package com.pocketds.hub.screens.library

import com.pocketds.hub.ui.CoverFanView

import com.pocketds.hub.ui.textWeight

import com.pocketds.hub.ui.ProgressLine.showFraction

import com.pocketds.hub.ui.PillButton

import com.pocketds.hub.ui.BlobSegmentedView

import com.pocketds.hub.ui.LibrarySortControls
import com.pocketds.hub.ui.FocusHorizontalScrollView
import com.pocketds.hub.ui.FocusScrollView
import com.pocketds.hub.ui.Artwork
import com.pocketds.hub.settings.DomainPreferences
import com.pocketds.hub.settings.SortPreference
import com.pocketds.hub.ui.CenteredIconTextView
import com.pocketds.hub.state.ContentMode
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.EditText
import android.app.AlertDialog
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pocketds.hub.input.HorizontalMode
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.ReadingEdition
import com.pocketds.hub.model.ReadingContinue
import com.pocketds.hub.model.ReadingLibrary
import com.pocketds.hub.model.ReadingProgress
import com.pocketds.hub.model.ReadingSection
import com.pocketds.hub.model.ReadingAuthor
import com.pocketds.hub.model.ReadingSectionItem
import com.pocketds.hub.model.ReadingWork
import com.pocketds.hub.model.ReadingYou
import com.pocketds.hub.model.ReadingYouPatch
import com.pocketds.hub.model.YouEdit
import kotlinx.coroutines.sync.withLock
import com.pocketds.hub.state.FormModel
import com.pocketds.hub.ui.FormOverlay
import com.pocketds.hub.ui.StarRatingView
import java.time.YearMonth
import com.pocketds.hub.screens.home.ReadingListEntry
import com.pocketds.hub.screens.home.ReadingListsRepository
import com.pocketds.hub.screens.home.ReadingListsState
import com.pocketds.hub.reader.PagedImageReaderScreen
import com.pocketds.hub.reader.EpubReaderScreen
import com.pocketds.hub.reader.AudiobookScreen
import com.pocketds.hub.reader.ReadingCompletionRepository
import com.pocketds.hub.reader.ReadingCompletionSession
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.state.LibraryGridSizing
import com.pocketds.hub.state.PagedLoadState
import com.pocketds.hub.state.StableItemFocus
import com.pocketds.hub.ui.DetailHeaderView
import com.pocketds.hub.ui.DetailArtworkCardView
import com.pocketds.hub.ui.DetailLayout
import com.pocketds.hub.ui.DetailStyler
import com.pocketds.hub.ui.AppIcon
import com.pocketds.hub.ui.AppIconDrawable
import com.pocketds.hub.ui.MediaActionIcon
import com.pocketds.hub.ui.MediaActionIconDrawable
import com.pocketds.hub.ui.ChoiceOverlay
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.FocusPlace
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.PosterCardView
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
import com.pocketds.hub.state.Fmt
import com.pocketds.hub.state.StatusText
import com.pocketds.hub.ui.showStatus

/**
 * One Kavita or Storyteller library, paged through the Hub's normalized model.
 *
 * It is the prototype's library page (`.pg-blibf`): the library's name,
 * Series | Authors | Books as a glass capsule with Sort at the right, the count
 * under them, and seven columns of glass covers (round portraits for Authors).
 */
class ReadingLibraryGridScreen(
    private val api: HubApi,
    private val library: ReadingLibrary,
    private val ringVisible: () -> Boolean
) : Screen {
    override val contentDomain = com.pocketds.hub.state.ContentMode.BOOKS
    override val title = library.title
    override val horizontalMode get() = if(view==VIEW_AUTHORS) HorizontalMode.CONFINED else HorizontalMode.GRID
    /** The library's name heads the page. */
    override val showsOwnTitle = true
    override val pageArtwork: String? get() = if (::grid.isInitialized) focusedWork()?.artwork?.takeIf(String::isNotBlank) else null
    /** The page's own line under the controls, "12 of 12 · Title · A to Z". */
    private lateinit var summary: TextView

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var libraryRevision = MediaLibraryChanges.revision
    private val paging = PagedLoadState(PREFETCH_AHEAD)
    private val adapter = WorkAdapter()
    private lateinit var colors: PocketColors
    private lateinit var sortControls: LibrarySortControls
    private lateinit var status: TextView
    private lateinit var grid: RecyclerView
    private lateinit var authorGrid:AuthorGridView
    private var viewSwitch: BlobSegmentedView? = null
    private lateinit var screenRoot: FrameLayout
    private lateinit var toolbarRow: LinearLayout
    /** Series, Authors or Books: the library grouped into series, its writers, or every book on its own. */
    private var view = VIEW_SERIES
    private lateinit var overlay: ChoiceOverlay
    private var host: ScreenHost? = null
    private var loadJob: Job? = null
    private val focusState = StableItemFocus()
    private val sortFields = ReadingSortFields.forLibrary(library)
    private var sortKey = if (sortFields.any { it.first == "series" }) "series" else "title"
    /** Author is a view, not a sort, in Series: Series | Authors | Books switches it and that sort list leaves it out. */
    private val canGroupByAuthor = sortFields.any { it.first == "author" }
    private val gridFields = sortFields.filter { it.first != "author" }
    private var seriesSort = SortPreference.forField(if (sortFields.any { it.first == "series" }) "series" else "title")
    private var sortAscending = true
    private var booksSort = SortPreference.forField("title")
    private var loadGeneration = 0
    private var refreshing = false

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        val remembered=DomainPreferences.sort(host.viewContext,ContentMode.BOOKS,sortFields.map { it.first },if (sortFields.any { it.first == "series" }) "series" else "title")
        sortKey=remembered.field;sortAscending=remembered.ascending
        if (canGroupByAuthor) {
            booksSort = DomainPreferences.bookSort(host.viewContext, sortFields.map { it.first }, "title")
            view = when {
                DomainPreferences.readingView(host.viewContext) == VIEW_BOOKS -> VIEW_BOOKS
                sortKey == "author" -> VIEW_AUTHORS
                else -> VIEW_SERIES
            }
            if (view == VIEW_BOOKS) { sortKey = booksSort.field; sortAscending = booksSort.ascending }
        }
        colors = Theme.colors(host.viewContext)
        val root = FrameLayout(host.viewContext).apply { setBackgroundColor(colors.background) }
        screenRoot = root
        val content = LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.VERTICAL
            status = TextView(context).apply {
                textSize = 11f
                setTextColor(colors.mutedText)
                setPadding(dp(EDGE_DP), dp(4), dp(EDGE_DP), 0)
            }
            summary = TextView(context).apply {
                textSize = 12f
                setTextColor(com.pocketds.hub.ui.glass.GlassColors.QUIET)
                setPadding(dp(EDGE_DP), dp(6), dp(EDGE_DP), 0)
                visibility = View.GONE
            }
            addView(TextView(context).apply {
                // The prototype's page heading (`.phead h1`): Bricolage at its heaviest.
                text = library.title
                textSize = 21f
                typeface = com.pocketds.hub.ui.Type.display(context, 800)
                includeFontPadding = false
                setTextColor(colors.primaryText)
                setPadding(dp(EDGE_DP), dp(8), dp(EDGE_DP), 0)
            })
            val ring = dp(PillButton.RING_DP.toInt())
            val toolbar=LinearLayout(context).apply {
                gravity=Gravity.CENTER_VERTICAL
                clipChildren = false
                setPadding(dp(EDGE_DP) - ring, dp(6), dp(EDGE_DP) - ring, 0)
            }
            toolbarRow = toolbar
            // The capsule at the left and Sort at the right, as the prototype's controls.
            if (canGroupByAuthor) toolbar.addView(viewSwitch(context), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = ring
            })
            toolbar.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
            sortControls=LibrarySortControls(context,colors,if (view == VIEW_BOOKS) sortFields else gridFields,SortPreference(sortKey,sortAscending),
                {this@ReadingLibraryGridScreen.overlay},::applySort) {host?.refreshHints()}
            toolbar.addView(sortControls)
            showGrouping()
            addView(toolbar)
            addView(summary); addView(status)
            grid = RecyclerView(context).apply {
                layoutManager = GridLayoutManager(context, MAX_COLUMNS)
                adapter = this@ReadingLibraryGridScreen.adapter
                setItemViewCacheSize(MAX_COLUMNS * 3)
                clipToPadding = false
                clipChildren = false
                // The covers' 5dp margins inside the page's 22dp edge.
                setPadding(dp(EDGE_DP - 5), dp(6), dp(EDGE_DP - 5), dp(84))
                layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
                addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
                        if(refreshing) return
                        val manager = view.layoutManager as GridLayoutManager
                        paging.next(
                            manager.findLastVisibleItemPosition(),
                            this@ReadingLibraryGridScreen.adapter.itemCount
                        )
                            ?.let(::loadPage)
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
            val shelfArea=FrameLayout(context)
            shelfArea.addView(grid,FrameLayout.LayoutParams(MATCH,MATCH))
            authorGrid=AuthorGridView(context,api,library.id,colors,ringVisible,
                {message,failed->
                    // The count is the page's own line; a failure is news, as the chip.
                    status.showStatus(com.pocketds.hub.state.StatusMessage(if (failed) message else "",
                        if (failed) com.pocketds.hub.state.StatusTone.ERROR else com.pocketds.hub.state.StatusTone.NORMAL), colors)
                    if (!failed) showSummary(message)
                },
                {if(view==VIEW_AUTHORS){grid.visibility=View.GONE;authorGrid.visibility=View.VISIBLE}},
                {author->host.push(ReadingAuthorScreen(api,library.id,author,ringVisible))}).apply {visibility=View.GONE}
            shelfArea.addView(authorGrid,FrameLayout.LayoutParams(MATCH,MATCH))
            addView(shelfArea,LinearLayout.LayoutParams(MATCH,0,1f))
        }
        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))
        overlay = ChoiceOverlay(host.viewContext, colors, ringVisible, sidePanel = true)
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        return root
    }

    override fun onShow() {
        if(libraryRevision != MediaLibraryChanges.revision) {libraryRevision=MediaLibraryChanges.revision;reload();return}
        val saved=DomainPreferences.sort(requireNotNull(host).viewContext,ContentMode.BOOKS,sortFields.map { it.first },if (sortFields.any { it.first == "series" }) "series" else "title")
        if(view!=VIEW_BOOKS && saved!=SortPreference(sortKey,sortAscending)) { applySort(saved); return }
        if(view==VIEW_AUTHORS){authorGrid.show(sortAscending);return}
        if (paging.loadedPage > 0 && ::grid.isInitialized) adapter.notifyDataSetChanged()
        if (paging.loadedPage == 0 && loadJob?.isActive != true) {
            (paging.retry() ?: paging.initial())?.let(::loadPage)
        } else {
            restoreFocus()
        }
    }

    override fun onHide() {
        loadGeneration++
        if(::authorGrid.isInitialized)authorGrid.hide()
        val position = focusedPosition()
        focusedWork()?.let { focusState.remember(position, it.id) }
        if (::overlay.isInitialized && overlay.isOpen) overlay.dismiss()
        paging.cancelLoading()
        scope.coroutineContext.cancelChildren()
        loadJob = null
    }

    override fun onDestroyView() {
        if(::authorGrid.isInitialized)authorGrid.destroy()
        scope.cancel()
        host = null
    }

    override fun requestInitialFocus(): Boolean {
        if (::overlay.isInitialized && overlay.isOpen) return true
        if(::authorGrid.isInitialized && authorGrid.visibility==View.VISIBLE)return authorGrid.restoreFocus()
        if (!::grid.isInitialized || adapter.itemCount == 0) return if(::sortControls.isInitialized) sortControls.fieldButton.requestFocus() else false
        val target = focusState.resolve(adapter.ids())
        if (target < 0) return false
        grid.scrollToPosition(target)
        grid.post { grid.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus() }
        return true
    }

    override fun hints() = if (::overlay.isInitialized && overlay.isOpen) {
        listOf(ButtonHint.activate("Choose"), ButtonHint.back("Cancel"))
    } else {
        listOf(
            ButtonHint.activate(if (viewSwitch?.hasFocus() == true) "Show" else "Details"),
            ButtonHint.back(),
            ButtonHint.secondary("Sort"),
            ButtonHint.refresh()
        )
    }

    override fun onPad(action: PadAction): Boolean {
        if (::overlay.isInitialized && overlay.onPad(action)) {
            host?.refreshHints()
            return true
        }
        return when (action) {
            is PadAction.Step -> action.direction == com.pocketds.hub.input.Direction.UP && upToToolbar()
            PadAction.Activate -> if(authorGrid.visibility==View.VISIBLE) false else focusedWork()?.let(::open) != null
            PadAction.Secondary -> {
                sortControls.showFields()
                true
            }
            PadAction.Refresh -> {
                if(view==VIEW_AUTHORS){authorGrid.show(sortAscending,force=true);return true}
                if (loadJob?.isActive != true) paging.retry()?.let(::loadPage) ?: reload()
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
            focusState.reset()
        } else {
            val position = focusedPosition()
            focusedWork()?.let { focusState.remember(position, it.id) }
        }
        refreshing = true
        if(view==VIEW_AUTHORS)authorGrid.show(sortAscending,force=true) else {authorGrid.hide();paging.initial()?.let(::loadPage)}
    }

    private fun loadPage(page: Int) {
        if (loadJob?.isActive == true) return
        val generation = loadGeneration
        status.showStatus(when {
            refreshing -> StatusText.loading(library.title, refreshing = true)
            adapter.itemCount == 0 -> StatusText.loading(library.title, refreshing = false)
            else -> StatusText.loading("more", refreshing = false)
        }, colors)
        loadJob = scope.launch {
            when (val result = api.readingLibraryItems(
                library.id,
                page,
                sortKey,
                if (sortAscending) "asc" else "desc",
                if (view == VIEW_BOOKS) "works" else ""
            )) {
                is HubResult.Ok -> {
                    if (generation != loadGeneration) return@launch
                    paging.complete(page, result.value.totalPages)
                    if(page==1){authorGrid.visibility=View.GONE;grid.visibility=View.VISIBLE}
                    if (page == 1 && refreshing) {
                        adapter.replace(result.value.items)
                        refreshing = false
                    } else if (page == 1 && adapter.itemCount == 0) {
                        adapter.replace(result.value.items)
                    } else {
                        adapter.append(result.value.items)
                    }
                    val line = "${adapter.itemCount} of ${result.value.total} · ${sortLabel()}"
                    status.showStatus(
                        when {
                            result.value.items.isEmpty() && adapter.itemCount == 0 -> StatusText.notice("This reading library is empty.")
                            // The count is the page's own line; the chip says only what is news.
                            else -> StatusText.caveat(result.value.cache, result.value.partial.map { it.service })
                        },
                        colors
                    )
                    if (adapter.itemCount > 0) showSummary(line)
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
        val context = requireNotNull(host).viewContext
        when {
            view == VIEW_BOOKS -> { booksSort = value; DomainPreferences.setBookSort(context, value) }
            else -> {
                if (value.field != "author") seriesSort = value
                DomainPreferences.setSort(context,ContentMode.BOOKS,value)
            }
        }
        sortKey=value.field;sortAscending=value.ascending
        sortControls.fields = if (view == VIEW_BOOKS) sortFields else gridFields
        sortControls.update(value)
        showGrouping()
        reload(resetSelection=true)
    }

    /**
     * Up from the top row goes to the view switch and sort. The switch sits at
     * the right, so a plain focus search from a card on the left found the tabs
     * above it instead and the switch could not be reached.
     */
    private fun upToToolbar(): Boolean {
        val focused = screenRoot.findFocus() ?: return false
        if (isInside(focused, toolbarRow)) return false
        val above = android.view.FocusFinder.getInstance().findNextFocus(screenRoot, focused, View.FOCUS_UP)
        // A card above is the app's ordinary move; only the last step up is taken here.
        if (above != null && !isInside(above, toolbarRow)) return false
        return viewSwitch?.focus() ?: sortControls.fieldButton.requestFocus()
    }

    private fun isInside(view: View, parent: View): Boolean {
        var v: View? = view
        while (v != null) { if (v === parent) return true; v = v.parent as? View }
        return false
    }

    /** Glass: the page's own line, always shown in plain words. */
    private fun showSummary(line: String) {
        summary.text = line
        summary.visibility = if (line.isBlank()) View.GONE else View.VISIBLE
    }

    /** Series (the default), Authors, or Books, as a glass capsule; each keeps its own order. */
    private fun viewSwitch(context: android.content.Context) = BlobSegmentedView(context, colors, ringVisible).apply {
        heightDp = PillButton.CONTROL_DP
        textSp = 12f
        useGlassTrack()
        setOptions(listOf(
            BlobSegmentedView.Option(VIEW_SERIES, "Series"),
            BlobSegmentedView.Option(VIEW_AUTHORS, "Authors"),
            BlobSegmentedView.Option(VIEW_BOOKS, "Books", "Every book on its own")
        ), view)
        onPick = ::showView
        onOptionFocused = { host?.refreshHints() }
    }.also { viewSwitch = it }

    private fun showView(next: String) {
        if (next == view) return
        view = next
        viewSwitch?.select(next)
        DomainPreferences.setReadingView(requireNotNull(host).viewContext, next)
        applySort(when (next) {
            VIEW_AUTHORS -> SortPreference("author", true)
            VIEW_BOOKS -> booksSort
            else -> seriesSort
        })
        host?.refreshHints()
    }

    private fun showGrouping() {
        sortControls.fieldButton.visibility = if (view == VIEW_AUTHORS) View.GONE else View.VISIBLE
    }

    private fun sortLabel(): String {
        val field = sortFields.firstOrNull { it.first == sortKey }?.second ?: "Title"
        return "$field · ${SortPreference(sortKey, sortAscending).directionLabel()}"
    }

    private fun focusedPosition(): Int {
        val focused = grid.focusedChild ?: return -1
        return grid.getChildAdapterPosition(focused)
    }

    private fun focusedWork(): ReadingWork? = adapter.at(focusedPosition())
    private fun restoreFocus() { if (adapter.itemCount > 0) requestInitialFocus() }

    private fun open(work: ReadingWork) {
        focusState.pin(focusedPosition().coerceAtLeast(0), work.id)
        host?.push(ReadingWorkScreen(api, work.id, work.title, ringVisible))
    }

    private inner class WorkAdapter : RecyclerView.Adapter<WorkHolder>() {
        private val values = mutableListOf<ReadingWork>()
        fun at(position: Int): ReadingWork? = values.getOrNull(position)
        fun ids(): List<String> = values.map { it.id }
        fun replace(next: List<ReadingWork>) {
            values.clear()
            values.addAll(next.distinctBy { it.id })
            notifyDataSetChanged()
        }
        fun append(next: List<ReadingWork>) {
            val known = values.asSequence().map { it.id }.toHashSet()
            val added = next.filter { known.add(it.id) }
            val start = values.size
            values.addAll(added)
            if (added.isNotEmpty()) notifyItemRangeInserted(start, added.size)
        }
        override fun getItemCount() = values.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WorkHolder {
            val card = PosterCardView(parent.context, colors).apply {
                // Seven columns of covers filling their cells, as the media grid's.
                layoutParams = RecyclerView.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp(5), dp(6), dp(5), dp(6))
                }
                FocusDecorator.attach(this, ringVisible)
                FocusDecorator.listen(this, ringVisible) { _, focused ->
                    if (focused) {
                        val position = grid.getChildAdapterPosition(this)
                        val itemId = (getTag(TAG_WORK) as? ReadingWork)?.id.orEmpty()
                        focusState.confirmRestored(position, itemId)
                        host?.refreshHints()
                    }
                }
                activateOnTap { (getTag(TAG_WORK) as? ReadingWork)?.let(::open) }
            }
            return WorkHolder(card)
        }

        override fun onBindViewHolder(holder: WorkHolder, position: Int) {
            val work = values[position]
            val card = holder.itemView as PosterCardView
            card.setTag(TAG_WORK, work)
            card.bindReadingWork(
                ReadingCompletionRepository.get(card.context).project(work),
                Artwork.loader(api, card.context),
                api::imageUrl
            )
        }
    }

    private class WorkHolder(view: View) : RecyclerView.ViewHolder(view)
    private fun dp(value: Int) = Styler.dpInt(requireNotNull(host).viewContext, value.toFloat())

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val MAX_COLUMNS = 7
        const val PREFETCH_AHEAD = 6
        /** The prototype's Pocket grid, 22dp edges. */
        const val EDGE_DP = 22
        const val TAG_WORK = -0x7fffffdf
        const val VIEW_SERIES = "series"
        const val VIEW_AUTHORS = "authors"
        const val VIEW_BOOKS = "books"
    }
}

/** Read-only work detail until the reader manifest milestone adds Open/Read actions. */
class ReadingWorkScreen(
    private val api: HubApi,
    private val workId: String,
    initialTitle: String,
    private val ringVisible: () -> Boolean,
    /** Resume reading from Home: open the reader as the page arrives, as its main button would. Back returns here. */
    private val openReader: Boolean = false
) : Screen {
    private var readerOpened = false
    override val contentDomain = com.pocketds.hub.state.ContentMode.BOOKS
    override val title = initialTitle
    override val focusOnShow = true
    /** The page's own heading is the title beside the cover. */
    override val showsOwnTitle = true
    /** The page takes the cover's colours; a series', the book being read. */
    override val pageArtwork: String?
        get() = lastWork?.let { work -> work.continueAt?.artwork?.takeIf(String::isNotBlank) ?: work.artwork.takeIf(String::isNotBlank) }
    /** The comic volume whose issues are shown, by its place in the run. */
    private var selectedVolume = -1
    private var libraryNamesAsked = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var colors: PocketColors
    private lateinit var status: TextView
    private lateinit var scroll: FocusScrollView
    private lateinit var content: LinearLayout
    private var host: ScreenHost? = null
    private var loadJob: Job? = null
    private var hasChildLinks = false
    private lateinit var detailHeader: DetailHeaderView
    private lateinit var listOverlay: ChoiceOverlay
    /** "When did you finish?" (#39): a small centred card over the page. */
    private lateinit var finishedPanel: FormOverlay
    /**
     * Writes of what this profile says of the book (a rating, a finish month) are not the page's own
     * jobs: leaving the page must not cancel one that was asked for, so they run here (#39).
     */
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** What the page had before this visit marked the book finished, so Mark unread can put it back (#39). */
    private var finishedFrom: ReadingYou? = null
    private var finishedMarked = false
    /** Writes of "you" go one at a time and in the order they were asked (a mutex is fair). */
    private val youLock = kotlinx.coroutines.sync.Mutex()
    private var youPending = 0
    private var lastActionKey: String? = null
    private val actionViews = linkedMapOf<String, View>()
    /** A series' continue card, when it shows one. */
    private var continueView: com.pocketds.hub.ui.ContinuationCardView? = null
    @Volatile private var refreshOnShow = false
    private var lastWork: ReadingWork? = null
    /** A book's series and its books, for the strip under the book. */
    private var seriesBooks: Pair<String, List<ReadingSectionItem>>? = null
    private var seriesJob: Job? = null
    private var previewFormatWorkId = ""
    private var previewFormat: ReadingEntryChoice? = null
    private val completionSession = ReadingCompletionSession()
    @Volatile private var visible = false
    /** The page's own view: where FocusPlace keeps the place Back returns to. */
    private lateinit var pageView: View

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        colors = Theme.colors(host.viewContext)
        // The book page (GLASS_PLAN.md): the cover beside the words, glass actions and rows.
        val main = LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.background)
            status = TextView(context).apply {
                textSize = 11f
                setTextColor(colors.mutedText)
                setPadding(dp(EDGE_DP), dp(6), dp(EDGE_DP), dp(2))
            }
            addView(status)
            scroll = FocusScrollView(context).apply {
                isFillViewport = true
                clipToPadding = false
                setPadding(0, 0, 0, dp(16))
                clipChildren = false
                content = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    clipChildren = false
                }
                addView(content, ViewGroup.LayoutParams(MATCH, WRAP))
            }
            addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
        return FrameLayout(host.viewContext).apply {
            addView(main, FrameLayout.LayoutParams(MATCH, MATCH))
            listOverlay = ChoiceOverlay(context, colors, ringVisible, sidePanel = true)
            addView(listOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
            finishedPanel = FormOverlay(context, colors, ringVisible, side = ContentMode.BOOKS, centred = true)
            addView(finishedPanel, FrameLayout.LayoutParams(MATCH, MATCH))
        }.also { pageView = it }
    }

    override fun onShow() {
        visible = true
        if (::detailHeader.isInitialized) detailHeader.overview.collapse()
        if (::listOverlay.isInitialized && listOverlay.isOpen) listOverlay.dismiss()
        if (::finishedPanel.isInitialized && finishedPanel.isOpen) finishedPanel.dismiss()
        // Drawn again on coming back: the place Back returns to moves to its new view,
        // and the host puts focus there (#23).
        lastWork?.let(::render)
        // A page kept on the stack reads its work again whenever it comes back (#21): a read-along
        // edition that finished aligning while it waited, a format added or gone, a place moved by a
        // reader. The work route is no-store; the page keeps showing what it had until the answer.
        if (loadJob?.isActive != true) {
            val shown = content.childCount > 0
            refreshOnShow = false
            load(force = shown)
        }
    }

    override fun onHide() {
        visible = false
        completionSession.leave()
        actionViews.entries.firstOrNull { it.value.hasFocus() }?.key?.let { lastActionKey = it }
        if (::detailHeader.isInitialized) detailHeader.overview.collapse()
        scope.coroutineContext.cancelChildren()
        loadJob = null
    }

    override fun onDestroyView() {
        scope.cancel()
        // A write already asked for finishes; the page is not there to hear how it went (#39).
        host = null
    }

    override fun requestInitialFocus(): Boolean = FocusPlace.focus(pageView) ||
        actionViews[DetailLayout.restoreFocus(lastActionKey,
            actionViews.keys.filterNot { it.startsWith("list:") } + actionViews.keys.filter { it.startsWith("list:") })]?.requestFocus() == true

    override fun hints() = buildList {
        if (::finishedPanel.isInitialized && finishedPanel.isOpen) {
            add(ButtonHint.activate("Choose")); add(ButtonHint.back("Cancel")); return@buildList
        }
        if (::listOverlay.isInitialized && listOverlay.isOpen) {
            add(ButtonHint.activate("Choose")); add(ButtonHint.back("Cancel")); return@buildList
        }
        if (::detailHeader.isInitialized && detailHeader.overview.hasFocus()) {
            detailHeader.overview.actionHint?.let { add(ButtonHint.activate(it)) }
            add(ButtonHint.back(if (detailHeader.overview.expanded) "Collapse description" else "Back"))
            return@buildList
        }
        val focusedAction = actionViews.entries.firstOrNull { it.value.isShown && it.value.hasFocus() }
        if (focusedAction != null) {
            val view = focusedAction.value
            // Your stars say what Ⓐ would do with the cursor where it stands (#39).
            add(ButtonHint.activate((view as? StarRatingView)?.let { ReadingStars.hint(it.rating, it.cursor) }
                ?: ReadingActionHint.label(focusedAction.key,
                    text = (view as? TextView)?.text?.toString().orEmpty(),
                    description = view.contentDescription?.toString().orEmpty())))
        } else if (hasChildLinks) add(ButtonHint.activate("Open"))
        add(ButtonHint.back())
        add(ButtonHint.refresh())
    }

    private fun attachActionFocus(view: TextView) {
        FocusDecorator.attach(view, ringVisible, scale = false)
        FocusDecorator.listen(view, ringVisible) { focused, _ ->
            host?.refreshHints()
        }
    }

    override fun onPad(action: PadAction): Boolean {
        if (::finishedPanel.isInitialized && finishedPanel.isOpen) {
            val handled = finishedPanel.onPad(action)
            if (handled) host?.refreshHints()
            return handled
        }
        if (::listOverlay.isInitialized && listOverlay.isOpen) {
            val handled = listOverlay.onPad(action)
            if (handled) host?.refreshHints()
            return handled
        }
        if (::detailHeader.isInitialized && detailHeader.overview.onPad(action)) return true
        // Left and right move the cursor along your stars, and Ⓐ rates with it (#39).
        if (::detailHeader.isInitialized && detailHeader.ratingView.onPad(action)) return true
        return when (action) {
        PadAction.Refresh -> {
            load(force = true)
            true
        }
        else -> false
    }
    }

    override fun onSystemBack(): Boolean {
        if (::finishedPanel.isInitialized && finishedPanel.isOpen) {
            finishedPanel.dismiss()
            host?.refreshHints()
            return true
        }
        if (::listOverlay.isInitialized && listOverlay.isOpen) {
            listOverlay.dismiss()
            host?.refreshHints()
            return true
        }
        return false
    }

    private fun load(force: Boolean = false) {
        if (loadJob?.isActive == true) return
        status.visibility = View.VISIBLE
        status.showStatus(StatusText.loading("details", refreshing = force), colors)
        loadJob = scope.launch {
            when (val result = api.readingWork(workId)) {
                is HubResult.Ok -> render(result.value)
                is HubResult.Failed ->
                    status.showStatus(StatusText.failed(result.message, result.kind, hasData = force), colors)
            }
            loadJob = null
        }
    }

    /** The page drawn from [source]; the place Back returns to moves to its new view (#23). */
    private fun render(source: ReadingWork) = FocusPlace.across(pageView) { draw(source) }

    /** [source] as this device sees it: a place not yet sent counted, a book marked read here finished. */
    private fun projected(source: ReadingWork): ReadingWork {
        val context = requireNotNull(host).viewContext
        val checkpoints = com.pocketds.hub.reader.ReadingProgress.get(context)
        return ReadingCompletionRepository.get(context).project(
            com.pocketds.hub.reader.ReadingProgressPresentation.project(source,
                checkpoints.store.pending(checkpoints.session().identity)))
    }

    private fun draw(source: ReadingWork) {
        lastWork = source
        val work = projected(source)
        val previouslyFocusedSource = actionViews.entries.firstOrNull { it.value.hasFocus() }?.key ?: lastActionKey
        // A comic's issue: its strip opens at it, and focus goes back to it once the strip has its cards.
        val issueKey = previouslyFocusedSource?.takeIf(IssueStrip::isTag)
        val previousScrollY = scroll.scrollY
        content.removeAllViews()
        actionViews.clear()
        continueView = null
        hasChildLinks = false
        content.addView(hero(work))
        val primaryRead = ReadingWorkPresentation.primaryRead(work)
        if (work.entityType == "collection") work.continueAt?.let { point ->
            continueButton(work, point)?.let {
                detailHeader.actions.addView(it, 0, LinearLayout.LayoutParams(WRAP, WRAP).apply {
                    // The pill lines up with the words, its ring in the room left of it.
                    marginStart = -dp(PillButton.RING_DP.toInt()); marginEnd = dp(2)
                })
            }
            // The book being read as a card of its own under the series.
            continueCard(work, point)?.let { card -> continueView = card; content.addView(card) }
        }
        if (ReadingBookFacts.kindTag(work.kind) != null && work.libraryId.isNotBlank() &&
            ReadingLibraryNames.of(work.libraryId) == null) loadLibraryNames()
        if (work.editions.isNotEmpty() && primaryRead == null) {
            content.addView(sectionTitle("Editions"))
            work.editions.forEach { content.addView(editionCard(work, it)) }
        }
        val volumes = ReadingBookFacts.kindTag(work.kind) != null && work.entityType != "collection" &&
            work.sections.count { it.items.isNotEmpty() } > 1
        if (volumes) content.addView(volumeChips(work))
        work.sections.forEachIndexed { index, section ->
            // One volume's issues at a time, picked with the chips above.
            if (volumes && index != selectedVolume) return@forEachIndexed
            // A series' one list of books is its reading order.
            content.addView(sectionTitle(if (work.entityType == "collection" && work.sections.size == 1) "In reading order" else section.title,
                if (ReadingBookFacts.kindTag(work.kind) != null) ReadingBookFacts.length(work.copy(sections = listOf(section))).firstOrNull() else null))
            if (work.entityType == "collection" && section.items.isNotEmpty()) {
                hasChildLinks = hasChildLinks || section.items.any(ReadingWorkPresentation::canOpen)
                content.addView(bookRow(section))
            } else if (ReadingBookFacts.kindTag(work.kind) != null && section.items.isNotEmpty()) {
                // A comic's issues, a manga's chapters: covers in a strip, opening the reader.
                hasChildLinks = true
                content.addView(IssueStrip.create(requireNotNull(host).viewContext, colors, ringVisible, api,
                    section.items, work.kind, work.artwork, openAt = issueKey?.let(IssueStrip::sourceOf),
                    onFocused = { item -> lastActionKey = IssueStrip.tag(item) }) { item ->
                    if (canReadPublication(item.kind.ifBlank { work.kind }, item.sourceItemId))
                        openPublication(work, item.sourceItemId, ReadingBookFacts.issueTitle(item, work.kind), "kavita")
                })
            } else {
                section.items.forEach { content.addView(sectionItemCard(work, it)) }
            }
        }
        if (work.entityType != "collection" && work.seriesId.isNotBlank()) {
            val books = seriesBooks?.takeIf { it.first == work.seriesId }?.second
            if (books == null) loadSeries(work.seriesId)
            else if (books.size > 1) {
                content.addView(sectionTitle("More in ${work.series}"))
                content.addView(seriesStrip(work, books))
            }
        }
        // The actions carry their keys, so the place Back returns to finds its new view (#23).
        actionViews.forEach { (key, view) -> view.tag = key }
        // Down from the continue card is the book being read, else the first in the row (#26).
        continueView?.downTo(work.continueAt?.let { actionViews["book:${it.workId}"] }
            ?: actionViews.entries.firstOrNull { it.key.startsWith("book:") || it.key.startsWith("missing:") }?.value)
        val preferredSource = previouslyFocusedSource?.takeIf { it.startsWith("list:") && it in actionViews }
            ?: ReadingWorkPresentation.preferredActionSource(
            continueSourceItemId = work.continueAt?.sourceItemId.orEmpty(),
            readableSourceItemIds = actionViews.keys.filterNot { it.startsWith("list:") },
            previouslyFocusedSourceItemId = previouslyFocusedSource
        ) ?: previouslyFocusedSource?.takeIf { it in actionViews }
        content.post {
            if (visible) {
                scroll.scrollTo(0, previousScrollY)
                (issueKey?.let { content.findViewWithTag<View>(it) } ?: actionViews[preferredSource])?.requestFocus()
                if (openReader && !readerOpened) {
                    readerOpened = true
                    actionViews["entry"]?.performClick()
                }
            }
        }
        status.showStatus(StatusText.caveat(work.cache, work.partial.map { it.service }), colors)
        status.visibility = if (status.text.isNullOrBlank()) View.GONE else View.VISIBLE
        host?.refreshHints()
        host?.pageArtworkChanged()
    }

    private fun hero(work: ReadingWork): View = DetailHeaderView(requireNotNull(host).viewContext, colors, ringVisible).apply {
        detailHeader = this
        // Focus in the header shows all of it: the title stays over the overview (#23).
        scroll.revealWhole = this
        // The prototype's book page: the cover at the left, "BOOK 6 · RED RISING" over the title.
        book = true
        // A book of its own takes the owner's layout "1" (#39): formats, stars under the cover, genres on a line.
        reading = ReadingBookPage.isBook(work)
        squareCover = work.kind == com.pocketds.hub.model.ReadingType.AUDIOBOOK
        eyebrowView.text = ReadingBookFacts.eyebrow(work, ReadingLibraryNames.of(work.libraryId).orEmpty())
        overview.onChanged = { host?.refreshHints() }
        titleView.text = work.title
        subtitleView.visibility = View.GONE
        metadataView.text = if (ReadingBookPage.isBook(work)) ReadingBookPage.facts(work)
        else if (work.entityType != "collection") ReadingBookFacts.line(work, null)
        else buildList {
            // Linked writers are chips under the title; how far through is the bar below.
            if (work.authorRefs.isEmpty() && work.authors.isNotEmpty()) add(work.authors.joinToString(", "))
            add("${work.bookCount} ${if (work.bookCount == 1) "book" else "books"}")
            val missing = work.sections.sumOf { section -> section.items.count { !it.isAvailable } }
            if (missing > 0) add("$missing missing")
            if (work.genres.isNotEmpty()) add(work.genres.joinToString(", "))
        }.joinToString(" · ")
        overview.bind(work.overview)
        val fraction = work.progress?.let { if (it.completed) 1.0 else it.percentage } ?: 0.0
        showProgress(fraction)
        progressLabel.text = (if (work.entityType == "collection") ReadingBookFacts.seriesProgress(work) else ReadingBookFacts.progress(work)).orEmpty()
        progressRow.visibility = if (fraction > 0 || progressLabel.text.isNotEmpty()) View.VISIBLE else View.GONE
        bookLinks(work, links)
        if (work.entityType == "collection") {
            val coverDp = CoverFanView.COVER_DP
            val (width, height) = CoverFanView.sizeDp(coverDp)
            // In from the page's edge by how far its outer cover leans, or the screen cuts it.
            replacePoster(CoverFanView(context, colors, coverDp).apply {
                bind(com.pocketds.hub.screens.home.ReadingShelves.fanCovers(work), Artwork.loader(api, context), api::imageUrl)
            }, width, height, startDp = CoverFanView.LEAN_DP)
        }
        bindArtwork("book", null, work.artwork.takeIf { it.isNotBlank() }?.let(api::imageUrl),
            Artwork.loader(api, context))
        if (ReadingBookPage.isBook(work)) { bookPage(this, work); return@apply }
        if (work.entityType != "collection") {
            val remembered = ReadingEntryPreferences.get(context, work.id)
            val formatMenu = ReadingFormatMenu.forWork(work, remembered)
            if (previewFormatWorkId != work.id) {
                previewFormatWorkId = work.id
                previewFormat = null
            }
            val previewKey = previewFormat?.let { ReadingFormatMenu.Option(it, "", "").key }
            val selectedOption = formatMenu.options.firstOrNull { it.key == previewKey }
            val entry = selectedOption?.choice ?: formatMenu.defaultChoice.also { previewFormat = null }
            formatStatus.bind(ReadingFormatStatus.forWork(work))
            // Ebook, audiobook and read-along say nothing about a comic.
            if (ReadingBookFacts.kindTag(work.kind) != null) formatStatus.visibility = View.GONE
            stateView.visibility = View.GONE
            stateView.isFocusable = false
            entry?.let { choice ->
                val modeName = when (choice.mode) {
                    ReadingEntryMode.READ -> "reading"
                    ReadingEntryMode.LISTEN -> "listening"
                    ReadingEntryMode.READ_ALONG -> "read along"
                }
                val label = if (remembered != null && previewFormat == null) "Continue" else when (choice.mode) {
                    ReadingEntryMode.READ -> choice.text?.label?.takeUnless { it == "Read book" }
                        ?: selectedOption?.label ?: "Read"
                    ReadingEntryMode.LISTEN -> if (previewFormat != null) "Listen · ${selectedOption?.detail.orEmpty()}" else "Listen"
                    ReadingEntryMode.READ_ALONG -> if (previewFormat != null) "Read along · ${selectedOption?.narration.orEmpty()}" else "Read along"
                }
                val icon = when (choice.mode) {
                    ReadingEntryMode.READ -> AppIcon.BOOK
                    ReadingEntryMode.LISTEN -> AppIcon.HEADPHONES
                    ReadingEntryMode.READ_ALONG -> AppIcon.READ_ALONG
                }
                // The Books side's main action, gold (PillButton.mainFace).
                val primary: TextView = PillButton.create(context, colors, label, icon, primary = true,
                    heightDp = PILL_DP, side = ContentMode.BOOKS)
                primary.apply {
                    contentDescription = "$label ${work.title}, $modeName"
                    attachActionFocus(this)
                    activateOnTap { launchEntry(work, previewFormat ?: choice) }
                }
                actions.addView(primary, LinearLayout.LayoutParams(WRAP, WRAP).apply {
                    marginStart = -dp(PillButton.RING_DP.toInt()); marginEnd = dp(2)
                })
                actionViews["entry"] = primary
                hasChildLinks = true
            }
            if (formatMenu.options.size > 1) {
                val changeFormat = PillButton.create(context, colors, "Change format", heightDp = PILL_DP)
                changeFormat.apply {
                    contentDescription = "Change reading format or narration"
                    attachActionFocus(this)
                    activateOnTap { showFormatMenu(work, formatMenu) }
                }
                actions.addView(changeFormat, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(2) })
                actionViews["format"] = changeFormat
            }
            val read = CenteredIconTextView(context).apply {
                text = ""
                contentDescription = if (work.progress?.completed == true) "Mark ${work.title} unread" else "Mark ${work.title} read"
                textSize = 13f
                DetailStyler.action(this, colors, primary = false)
                setCenteredIcon(
                    MediaActionIconDrawable.of(context,
                        if (work.progress?.completed == true) MediaActionIcon.WATCHED else MediaActionIcon.UNWATCHED,
                        colors), dp(21))
                setPadding(dp(12), 0, dp(12), 0)
                attachActionFocus(this)
                activateOnTap { toggleRead(work) }
            }
            val wanted = ReadingListsRepository.get(context).wantToRead.any { it.workId == work.id }
            val want = CenteredIconTextView(context).apply {
                text = ""
                contentDescription = if (wanted) "Remove ${work.title} from Want to Read" else "Add ${work.title} to Want to Read"
                textSize = 13f
                DetailStyler.glassToggle(this, colors, lit = wanted)
                bookmark(this, wanted)
                attachActionFocus(this)
                activateOnTap {
                    val selected = toggleWant(work)
                    contentDescription = if (selected) "Remove ${work.title} from Want to Read"
                        else "Add ${work.title} to Want to Read"
                    bookmark(this, selected)
                    host?.refreshHints()
                }
            }
            actions.addView(want, glassToggleParams())
            actionViews["list:want"] = want
            val lists = CenteredIconTextView(context).apply {
                text = ""
                contentDescription = "Add ${work.title} to a reading list"
                textSize = 13f
                DetailStyler.action(this, colors, primary = false)
                setCenteredIcon(AppIconDrawable(AppIcon.CONTENTS, colors.primaryText), dp(21))
                setPadding(dp(12), 0, dp(12), 0)
                FocusDecorator.attach(this, ringVisible, scale = false)
                activateOnTap { showReadingLists(work) }
            }
            run {
                val editions = CenteredIconTextView(context).apply {
                    text = ""
                    contentDescription = "More actions for ${work.title}"
                    textSize = 21f
                    DetailStyler.glassToggle(this, colors)
                    setCenteredIcon(MediaActionIconDrawable.onGlass(context, MediaActionIcon.MORE, colors), dp(16))
                    attachActionFocus(this)
                    activateOnTap {
                        listOverlay.show("More actions", work.title, buildList {
                            add(ChoiceOverlay.Choice("read", read.contentDescription.toString()))
                            add(ChoiceOverlay.Choice("lists", "Reading lists"))
                            add(ChoiceOverlay.Choice("offline-remove", "Remove offline copy", "Only this device; keep server files and progress"))
                            add(ChoiceOverlay.Choice("server-remove", "Delete from server…", "Review the files before confirming", danger = true))
                        }, onCancel = { actionViews["list:more"]?.requestFocus(); host?.refreshHints() }) { selected ->
                            when (selected) {
                                "read" -> read.performClick()
                                "lists" -> lists.performClick()
                                "offline-remove" -> removeOfflineReading(requireNotNull(host),listOverlay,work,scope)
                                "server-remove" -> { refreshOnShow=true;host?.push(MediaRemovalScreen(api,"reading",work.id,ringVisible)) }
                            }
                            host?.refreshHints()
                        }
                        host?.refreshHints()
                    }
                }
                actions.addView(editions, glassToggleParams())
                actionViews["list:more"] = editions
            }
        }
    }

    /**
     * A book's page under the owner's layout "1" (#39): the formats as a row of icon and name, the Resume
     * pill with where you are and a round ⋯, the genres on one quiet line; under the cover your stars, when
     * you finished and your shelves. What the words say is [ReadingBookPage]'s; this only places them.
     */
    private fun bookPage(header: DetailHeaderView, work: ReadingWork) {
        val context = requireNotNull(host).viewContext
        val remembered = ReadingEntryPreferences.get(context, work.id)
        val formatMenu = ReadingFormatMenu.forWork(work, remembered)
        if (previewFormatWorkId != work.id) {
            previewFormatWorkId = work.id
            previewFormat = null
        }
        val previewKey = previewFormat?.let { ReadingFormatMenu.Option(it, "", "").key }
        val selectedOption = formatMenu.options.firstOrNull { it.key == previewKey }
        val choice = selectedOption?.choice ?: formatMenu.defaultChoice.also { previewFormat = null }
        // The formats are chips of their own now; the status line and the state say nothing more.
        header.formatStatus.visibility = View.GONE
        header.stateView.visibility = View.GONE
        header.stateView.isFocusable = false
        header.genresView.text = ReadingBookPage.genres(work)
        header.genresView.visibility = if (header.genresView.text.isNullOrBlank()) View.GONE else View.VISIBLE
        // A narration picked from the menu is the one the audiobook chip opens.
        val chips = ReadingFormatChips.of(work,
            previewFormat?.audio?.let { ReadingEntryPreference(ReadingEntryMode.LISTEN, it.sourceItemId) } ?: remembered)
        header.formatRow.bind(chips,
            onOpen = { chip -> chip.choice?.let { launchEntry(work, it) } },
            onQuiet = { chip -> host?.notify(chip.note) },
            onFocused = { chip -> lastActionKey = "list:format:${chip.kind}"; host?.refreshHints() })
        chips.filter { it.ready }.forEach { chip ->
            header.formatRow.chipViews[chip.kind]?.let { actionViews["list:format:${chip.kind}"] = it; hasChildLinks = true }
        }
        choice?.let { picked ->
            val modeName = when (picked.mode) {
                ReadingEntryMode.READ -> "reading"
                ReadingEntryMode.LISTEN -> "listening"
                ReadingEntryMode.READ_ALONG -> "read along"
            }
            val narration = when {
                previewFormat == null -> ""
                picked.mode == ReadingEntryMode.LISTEN -> selectedOption?.detail.orEmpty()
                picked.mode == ReadingEntryMode.READ_ALONG -> selectedOption?.narration.orEmpty()
                else -> ""
            }
            val label = ReadingResumeLabel.of(picked.mode, work.progress, resumeChapter(work, picked), narration)
            val icon = when (picked.mode) {
                ReadingEntryMode.READ -> AppIcon.BOOK
                ReadingEntryMode.LISTEN -> AppIcon.HEADPHONES
                ReadingEntryMode.READ_ALONG -> AppIcon.READ_ALONG
            }
            // The Books side's main action, gold (PillButton.mainFace).
            val primary: TextView = PillButton.create(context, colors, label, icon, primary = true,
                heightDp = PILL_DP, side = ContentMode.BOOKS)
            primary.apply {
                contentDescription = "$label ${work.title}, $modeName"
                attachActionFocus(this)
                activateOnTap { launchEntry(work, previewFormat ?: picked) }
            }
            header.actions.addView(primary, LinearLayout.LayoutParams(WRAP, WRAP).apply {
                marginStart = -dp(PillButton.RING_DP.toInt()); marginEnd = dp(2)
            })
            actionViews["entry"] = primary
            hasChildLinks = true
        }
        val more = CenteredIconTextView(context).apply {
            text = ""
            contentDescription = "More actions for ${work.title}"
            textSize = 21f
            DetailStyler.glassToggle(this, colors)
            setCenteredIcon(MediaActionIconDrawable.onGlass(context, MediaActionIcon.MORE, colors), dp(16))
            attachActionFocus(this)
            activateOnTap { showMore(work) }
        }
        header.actions.addView(more, glassToggleParams())
        actionViews["list:more"] = more
        // Your stars: Ⓐ or a tap rates, and the hub keeps it.
        header.ratingView.onRate = { rating -> rate(work, rating) }
        header.ratingView.onCursor = {
            if (header.ratingView.isFocused) lastActionKey = "list:rating"
            host?.refreshHints()
        }
        actionViews["list:rating"] = header.ratingView
        bindYou(work)
    }

    /** The chapter the text was left in, from this device's own saved place; null for anything else (#39). */
    private fun resumeChapter(work: ReadingWork, choice: ReadingEntryChoice): String? {
        val sourceItemId = when (choice.mode) {
            ReadingEntryMode.READ -> choice.text?.sourceItemId
            ReadingEntryMode.READ_ALONG -> choice.aligned?.sourceItemId
            ReadingEntryMode.LISTEN -> null
        }?.takeIf(String::isNotBlank) ?: return null
        val progress = com.pocketds.hub.reader.ReadingProgress.get(requireNotNull(host).viewContext)
        val checkpoint = runCatching { progress.store.read(progress.session().key(work.id, sourceItemId, "epub")) }.getOrNull()
        return ReadingResumeLabel.chapter(checkpoint?.local?.locator)
    }

    /** Your stars, when you finished and your shelves, under the cover (#39). */
    private fun bindYou(work: ReadingWork) {
        val header = detailHeader
        header.ratingView.rating = work.you?.rating ?: 0
        header.ratingView.visibility = View.VISIBLE
        val finished = ReadingBookPage.finished(work.you, finished = work.progress?.completed == true)
        header.finishedView.text = finished.orEmpty()
        header.finishedView.visibility = if (finished == null) View.GONE else View.VISIBLE
        val shelves = ReadingBookPage.shelves(work.you)
        header.shelvesView.text = shelves.orEmpty()
        header.shelvesView.visibility = if (shelves == null) View.GONE else View.VISIBLE
        header.requestLayout()
    }

    /** What the page shows of "you", changed at once; the hub's word follows (#39). */
    private fun setYou(you: ReadingYou?) {
        val shown = lastWork ?: return
        val next = shown.copy(you = you)
        lastWork = next
        if (visible && host != null && ::detailHeader.isInitialized) bindYou(projected(next))
    }

    private fun rate(work: ReadingWork, rating: Int?) {
        val shown = lastWork ?: return
        setYou((shown.you ?: ReadingYou()).copy(rating = rating ?: 0))
        host?.notify(if (rating == null) "Rating removed" else "Rated $rating ${if (rating == 1) "star" else "stars"}")
        saveYou(work.id, ReadingYouEdits.rate(rating), "Your rating")
    }

    /**
     * One write of "you" to the hub, in the order it was asked. It runs on the page's own scope, which
     * outlives the page: leaving straight after rating must not lose the rating. A failure says so and
     * reads the page again, so what is shown is what the hub holds.
     */
    private fun saveYou(workId: String, patch: ReadingYouPatch, what: String) {
        youPending++
        saves.launch {
            val result = youLock.withLock { api.updateReadingYou(workId, patch) }
            youPending--
            when (result) {
                is HubResult.Ok -> if (youPending == 0 && lastWork?.id == workId) setYou(result.value.you)
                is HubResult.Failed -> {
                    host?.notify("$what could not be saved · ${result.message}")
                    if (youPending == 0 && visible) load(force = true)
                }
            }
        }
    }

    /** The round ⋯: what [ReadingMoreMenu] offers for the state this book is in. */
    private fun showMore(work: ReadingWork) {
        val context = requireNotNull(host).viewContext
        val you = lastWork?.you
        val finished = work.progress?.completed == true
        val wanted = ReadingListsRepository.get(context).wantToRead.any { it.workId == work.id }
        val narrations = ReadingWorkPresentation.audiobooks(work).size > 1 || ReadingWorkPresentation.readAlongEditions(work).size > 1
        listOverlay.show("More actions", work.title,
            ReadingMoreMenu.entries(you, finished, wanted, narrations).map {
                ChoiceOverlay.Choice(it.id, it.label, it.detail, danger = it.danger)
            },
            onCancel = { actionViews["list:more"]?.requestFocus(); host?.refreshHints() }) { id ->
            when (id) {
                ReadingMoreMenu.FINISHED -> showFinished(work, finished)
                ReadingMoreMenu.UNREAD -> markUnread(work)
                ReadingMoreMenu.NARRATION -> showFormatMenu(work, ReadingFormatMenu.forWork(work, ReadingEntryPreferences.get(context, work.id)))
                ReadingMoreMenu.WANT -> toggleWant(work)
                ReadingMoreMenu.LISTS -> showReadingLists(work)
                ReadingMoreMenu.OFFLINE -> removeOfflineReading(requireNotNull(host), listOverlay, work, scope)
                ReadingMoreMenu.SERVER -> { refreshOnShow = true; host?.push(MediaRemovalScreen(api, "reading", work.id, ringVisible)) }
            }
            host?.refreshHints()
        }
        host?.refreshHints()
    }

    /**
     * "When did you finish?": a small centred card with Month and Year, this month unless the book is
     * already finished (then the month kept, to put right), Cancel and Mark finished.
     */
    private fun showFinished(work: ReadingWork, finishedNow: Boolean) {
        val now = YearMonth.now()
        val kept = lastWork?.you?.takeIf { finishedNow }
        val model = FormModel(ReadingFinished.rows(ReadingFinished.preset(kept, now), now))
        finishedPanel.show("When did you finish?", work.title, model,
            onCancel = { actionViews["list:more"]?.requestFocus(); host?.refreshHints() },
            // A month later than this one is this one: the rows are drawn again as the hub would take them.
            onChanged = { form ->
                if (ReadingFinished.needsSettling(form.rows(), now))
                    form.replace(ReadingFinished.rows(ReadingFinished.chosen(form.rows(), now), now))
            }
        ) { id, form ->
            when (id) {
                ReadingFinished.CANCEL -> finishedPanel.dismiss()
                ReadingFinished.MARK -> {
                    val month = ReadingFinished.chosen(form.rows(), now)
                    finishedPanel.dismiss()
                    markFinished(work, month, finishedNow)
                }
            }
            actionViews["list:more"]?.requestFocus()
            host?.refreshHints()
        }
        host?.refreshHints()
    }

    /**
     * Mark finished in [month]: the date and the count go to the hub, and the book is marked read here
     * as the page's read toggle always did (the hub has no route that marks a book read in Kavita or
     * Storyteller). The page keeps what it had, so Mark unread can put it back.
     */
    private fun markFinished(work: ReadingWork, month: YearMonth, finishedNow: Boolean) {
        val shown = lastWork ?: return
        val before = shown.you
        if (!finishedMarked) { finishedMarked = true; finishedFrom = before }
        val patch = ReadingYouEdits.finish(before, month, finishedNow)
        val count = (patch.readCount as? YouEdit.To)?.value ?: (before?.readCount ?: 0).coerceAtLeast(1)
        setYou((before ?: ReadingYou()).copy(finished = month.toString(), readCount = count, status = "read"))
        saveYou(work.id, patch, "The date you finished")
        if (!finishedNow) markReadHere(work, read = true)
        lastActionKey = "list:more"
        lastWork?.let(::render)
        host?.notify("Marked finished · ${ReadingBookPage.monthLabel(month.toString())}")
    }

    /** Mark unread: starts again from the beginning here, and undoes the finish this visit made, if it made one. */
    private fun markUnread(work: ReadingWork) {
        if (finishedMarked) {
            val from = finishedFrom
            ReadingYouEdits.unfinish(lastWork?.you, from)?.let { patch ->
                setYou((lastWork?.you ?: ReadingYou()).copy(finished = from?.finished.orEmpty(), readCount = from?.readCount ?: 0,
                    status = from?.status.orEmpty()))
                saveYou(work.id, patch, "The date you finished")
            }
            finishedMarked = false
            finishedFrom = null
        }
        lastActionKey = "list:more"
        toggleRead(work)
    }

    /** Marks the book read on this device, or unread again: the hub has no route for it. */
    private fun markReadHere(work: ReadingWork, read: Boolean): com.pocketds.hub.reader.ReadingCompletionState {
        val context = requireNotNull(host).viewContext
        val changed = ReadingCompletionRepository.update(context) { current ->
            if (read) completionSession.markRead(current, work.id) else completionSession.unmark(current, work.id)
        }
        ReadingListsRepository.update(context) { state ->
            state.recordProgress(work.id, changed.project(requireNotNull(lastWork)).progress?.percentage ?: 0.0)
        }
        return changed
    }

    private fun toggleRead(work: ReadingWork) {
        val changed = markReadHere(work, read = work.progress?.completed != true)
        render(requireNotNull(lastWork))
        host?.notify(when {
            changed.isRead(work.id) -> "Marked as read"
            changed.shouldStartAtBeginning(work.id) -> "Marked unread · next read starts at the beginning"
            else -> "Previous reading position restored"
        })
    }

    /** Want to read on or off; whether it is on now. */
    private fun toggleWant(work: ReadingWork): Boolean {
        val next = ReadingListsRepository.update(requireNotNull(host).viewContext) { state ->
            if (state.wantToRead.any { it.workId == work.id }) state.remove(ReadingListsState.WANT_TO_READ, work.id)
            else state.add(ReadingListsState.WANT_TO_READ, ReadingListEntry.from(work))
        }
        val selected = next.wantToRead.any { it.workId == work.id }
        host?.notify(if (selected) "Added to Want to Read" else "Removed from Want to Read")
        return selected
    }

    /** Want to Read's mark: dark on the toggle's white face while on, white on glass while off. */
    private fun bookmark(view: CenteredIconTextView, selected: Boolean) {
        val icon = if (selected) AppIcon.BOOKMARK_FILLED else AppIcon.BOOKMARK
        (view.background as? com.pocketds.hub.ui.glass.GlassButtonBackground)?.lit = selected
        view.setCenteredIcon(AppIconDrawable(icon, if (selected) com.pocketds.hub.ui.glass.GlassColors.INK else android.graphics.Color.WHITE), dp(16))
    }

    private fun glassToggleParams() = LinearLayout.LayoutParams(dp(DetailStyler.GLASS_TOGGLE_VIEW_DP), dp(DetailStyler.GLASS_TOGGLE_VIEW_DP)).apply { marginEnd = dp(2) }

    /**
     * A series' page: the book being read as its own continue card under the
     * series (`ContinuationCardView`, the prototype's `.cont`), "Continue
     * reading · Light Bringer", where in it, a bar in the accent and a gold
     * play disc. A opens it where it was left, as Continue does.
     */
    private fun continueCard(work: ReadingWork, point: ReadingContinue): com.pocketds.hub.ui.ContinuationCardView? {
        if (!canReadPublication(work.kind, point.sourceItemId)) return null
        val context = requireNotNull(host).viewContext
        val pages = work.sections.flatMap { it.items }.firstOrNull { it.workId == point.workId && it.workId.isNotBlank() }?.pageCount ?: 0
        val line = ReadingBookFacts.continueLine(point, work.kind, pages)
        return com.pocketds.hub.ui.ContinuationCardView(context, colors, ringVisible, portrait = true, side = ContentMode.BOOKS).apply {
            bind("Continue reading · ${point.title}", line, point.percentage, completed = false)
            Artwork.bind(image, Artwork.loader(api, context), point.artwork.takeIf(String::isNotBlank)?.let(api::imageUrl), opaque = true)
            val key = "continue:${point.sourceItemId}"
            onFocused = { lastActionKey = key; host?.refreshHints() }
            actionViews[key] = this
            hasChildLinks = true
            activateOnTap { openPublication(work, point.sourceItemId, point.title, point.source) }
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(EDGE_DP), dp(10), dp(EDGE_DP), dp(2)) }
        }
    }

    /**
     * A comic run of several volumes: each volume as a glass chip,
     * "Volume 1961 · 147 issues", the one shown white; picking one shows its
     * issues in place of the last.
     */
    private fun volumeChips(work: ReadingWork): View {
        val context = requireNotNull(host).viewContext
        val sections = work.sections.withIndex().filter { it.value.items.isNotEmpty() }
        if (selectedVolume !in sections.map { it.index }) {
            selectedVolume = sections.firstOrNull { (_, section) -> section.items.any { val p = it.progress; p != null && !p.completed && p.percentage > 0 } }?.index
                ?: sections.firstOrNull { (_, section) -> section.items.any { it.progress?.completed != true } }?.index
                ?: sections.first().index
        }
        val chips = BlobSegmentedView(context, colors, ringVisible, BlobSegmentedView.Style.CHIPS).apply {
            heightDp = 32f; textSp = 12f; padXDp = 11f; growDp = 0f
            setOptions(sections.map { (index, section) ->
                BlobSegmentedView.Option(index.toString(),
                    listOfNotNull(section.title.takeIf(String::isNotBlank), ReadingBookFacts.length(work.copy(sections = listOf(section))).firstOrNull())
                        .joinToString(" · "))
            }, selectedVolume.toString())
            onPick = { id ->
                id.toIntOrNull()?.let { picked ->
                    if (picked != selectedVolume) {
                        selectedVolume = picked
                        // The page is drawn again; focus comes back to this chip by its key.
                        lastActionKey = "list:volume:$picked"
                        render(requireNotNull(lastWork))
                    }
                }
            }
            onOptionFocused = { id -> lastActionKey = "list:volume:$id"; host?.refreshHints() }
        }
        sections.forEach { (index, _) -> chips.optionView(index.toString())?.let { actionViews["list:volume:$index"] = it } }
        return FocusHorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false; clipToPadding = false; clipChildren = false
            setPadding(dp(EDGE_DP - 3), dp(10), dp(EDGE_DP - 3), dp(2))
            addView(chips)
        }
    }

    /** A comic's page names its library ("Comic · My Marvelous Year"); asked once when nobody listed them yet. */
    private fun loadLibraryNames() {
        if (libraryNamesAsked) return
        libraryNamesAsked = true
        scope.launch {
            val libraries = (api.readingLibraries() as? HubResult.Ok)?.value?.libraries ?: return@launch
            ReadingLibraryNames.remember(libraries)
            val work = lastWork ?: return@launch
            if (visible && ::detailHeader.isInitialized) ReadingLibraryNames.of(work.libraryId)?.let {
                detailHeader.eyebrowView.text = ReadingBookFacts.eyebrow(work, it)
                detailHeader.requestLayout()
            }
        }
    }

    private fun showFormatMenu(work: ReadingWork, menu: ReadingFormatMenu) {
        val selected = previewFormat ?: menu.defaultChoice
        val selectedKey = selected?.let { ReadingFormatMenu.Option(it, "", "").key }
        listOverlay.show("Choose format", work.title,
            menu.options.map { option ->
                ChoiceOverlay.Choice(option.key, option.label, option.detail, selected = option.key == selectedKey)
            },
            startIndex = menu.options.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0),
            onCancel = { actionViews["format"]?.requestFocus(); host?.refreshHints() }
        ) { key ->
            previewFormat = menu.options.firstOrNull { it.key == key }?.choice
            render(work)
            actionViews["entry"]?.post { actionViews["entry"]?.requestFocus() }
            host?.refreshHints()
        }
        host?.refreshHints()
    }

    private fun launchEntry(work: ReadingWork, choice: ReadingEntryChoice) {
        when (choice.mode) {
            ReadingEntryMode.READ -> choice.text?.let { openPublication(work, it.sourceItemId, work.title, it.source) }
            ReadingEntryMode.LISTEN -> choice.audio?.let { openAudiobook(work, it) }
            ReadingEntryMode.READ_ALONG -> choice.aligned?.let {
                openPublication(work, it.sourceItemId, work.title, it.source, readAlong = true)
            }
        }
    }

    private fun showReadingLists(work: ReadingWork) {
        val state = ReadingListsRepository.get(requireNotNull(host).viewContext)
        listOverlay.show("Reading lists", "Choose a list for ${work.title}",
            state.lists.map { list ->
                val included = list.items.any { it.workId == work.id }
                ChoiceOverlay.Choice(list.id, list.name, if (included) "In list · select to remove" else "${list.items.size} books", selected = included)
            } + ChoiceOverlay.Choice("create", "＋  Create new list"),
            onCancel = { host?.refreshHints() }) { id ->
            if (id == "create") {
                val input = EditText(requireNotNull(host).viewContext).apply { hint = "List name"; setSingleLine() }
                AlertDialog.Builder(requireNotNull(host).viewContext).setTitle("New reading list").setView(input)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Create") { _, _ ->
                        val name = input.text.toString().trim()
                        if (name.isBlank()) { host?.notify("Enter a list name"); return@setPositiveButton }
                        val newId = java.util.UUID.randomUUID().toString()
                        ReadingListsRepository.update(input.context) {
                            it.create(name, newId).add(newId, ReadingListEntry.from(work))
                        }
                        host?.notify("Added to $name")
                    }.show()
            } else {
                val list = state.lists.firstOrNull { it.id == id } ?: return@show
                val included = list.items.any { it.workId == work.id }
                ReadingListsRepository.update(requireNotNull(host).viewContext) {
                    if (included) it.remove(id, work.id) else it.add(id, ReadingListEntry.from(work))
                }
                host?.notify(if (included) "Removed from ${list.name}" else "Added to ${list.name}")
            }
            host?.refreshHints()
        }
        host?.refreshHints()
    }

    /** "Continue #6" on a series page: the book being read, opened where it was left. */
    private fun continueButton(work: ReadingWork, point: ReadingContinue): View? {
        if (!canReadPublication(work.kind, point.sourceItemId)) return null
        val label = if (point.number.isBlank()) "Continue reading" else "Continue · Book ${point.number}"
        return PillButton.create(requireNotNull(host).viewContext, colors, label, AppIcon.BOOK, primary = true,
            heightDp = PILL_DP, side = ContentMode.BOOKS).apply {
            contentDescription = "Continue reading ${point.title}"
            FocusDecorator.attach(this, ringVisible, scale = false)
            FocusDecorator.listen(this, ringVisible) { _, focused -> if (focused) { lastActionKey = point.sourceItemId; host?.refreshHints() } }
            hasChildLinks = true
            activateOnTap { openPublication(work, point.sourceItemId, point.title, point.source) }
            actionViews.putIfAbsent(point.sourceItemId, this)
        }
    }

    private fun editionCard(work: ReadingWork, edition: ReadingEdition): View = infoCard(
        edition.kind.replaceFirstChar { it.uppercase() },
        buildList {
            if (edition.format.isNotBlank()) add(edition.format.uppercase())
            if (edition.pageCount > 0) add("${edition.pageCount} pages")
            if (edition.durationMs > 0) add(Fmt.runtime(edition.durationMs / 1_000))
            if (edition.narrator.isNotBlank()) add("Narrated by ${edition.narrator}")
            add(edition.source.replaceFirstChar { it.uppercase() })
        }.joinToString(" · ")
    ).apply {
        if (edition.source == "storyteller" && edition.kind == "ebook" && edition.availability == "available" && edition.sourceItemId.isNotBlank()) {
            hasChildLinks = true
            Styler.makeFocusable(this)
            FocusDecorator.attach(this, ringVisible, scale = false)
            activateOnTap { openPublication(work, edition.sourceItemId, work.title, edition.source) }
            actionViews.putIfAbsent(edition.sourceItemId, this)
        }
    }

    private fun sectionItemCard(work: ReadingWork, item: ReadingSectionItem): View = infoCard(
        buildString {
            if (item.number.isNotBlank()) append(item.number).append(" · ")
            append(item.title)
        },
        buildList {
            if (item.pageCount > 0) add("${item.pageCount} pages")
            progressText(item.progress)?.let(::add)
        }.joinToString(" · ")
    ).apply {
        if (canReadPublication(item.kind.ifBlank { work.kind }, item.sourceItemId)) {
            hasChildLinks = true
            Styler.makeFocusable(this)
            FocusDecorator.attach(this, ringVisible, scale = false)
            activateOnTap { openPublication(work, item.sourceItemId, item.title, "kavita") }
            actionViews.putIfAbsent(item.sourceItemId, this)
        }
    }

    private fun canReadPublication(kind: String, sourceItemId: String): Boolean =
        sourceItemId.isNotBlank() && kind in setOf("comic", "manga", "book", "ebook")

    private fun openPublication(work: ReadingWork, sourceItemId: String, publicationTitle: String, source: String, readAlong: Boolean = false) {
        host?.viewContext?.let { context ->
            val rememberedAudio = ReadingEntryPreferences.get(context, work.id)?.audioSourceItemId.orEmpty()
            ReadingEntryPreferences.put(context, work.id,
                if (readAlong) ReadingEntryMode.READ_ALONG else ReadingEntryMode.READ,
                if (readAlong) sourceItemId else rememberedAudio)
        }
        host?.push(
            if (source == "storyteller" || work.kind in setOf("book", "ebook")) EpubReaderScreen(
                api = api,
                workId = work.id,
                sourceItemId = sourceItemId,
                title = publicationTitle.ifBlank { work.title },
                ringVisible = ringVisible,
                onProgressChanged = ::requestRefreshAfterReading,
                readAlong = readAlong,
                readAlongAvailable = ReadingWorkPresentation.readAlongEditions(work).any { it.sourceItemId == sourceItemId },
                alignedEditions = ReadingWorkPresentation.readAlongEditions(work),
                audioEditions = ReadingWorkPresentation.audiobooks(work),
                ebookSourceItemId = work.editions.firstOrNull { it.kind == "ebook" }?.sourceItemId ?: sourceItemId,
                bookPages = ReadingBookFacts.pages(work)
            ) else PagedImageReaderScreen(
                api = api,
                workId = work.id,
                initialSourceItemId = sourceItemId,
                initialTitle = publicationTitle.ifBlank { work.title },
                ringVisible = ringVisible,
                onProgressChanged = ::requestRefreshAfterReading
            )
        )
    }

    private fun openAudiobook(work: ReadingWork, edition: com.pocketds.hub.model.ReadingEdition) {
        host?.viewContext?.let { ReadingEntryPreferences.put(it, work.id, ReadingEntryMode.LISTEN, edition.sourceItemId) }
        host?.push(AudiobookScreen(
            api = api, workId = work.id, edition = edition, title = work.title, ringVisible = ringVisible,
            narrations = ReadingWorkPresentation.audiobooks(work),
            ebook = work.editions.firstOrNull { it.kind == "ebook" && it.availability == "available" },
            alignedOptions = ReadingWorkPresentation.readAlongEditions(work),
            onProgressChanged = ::requestRefreshAfterReading,
            work = work
        ))
    }

    private fun requestRefreshAfterReading() {
        host?.let { ReadingCompletionRepository.update(it.viewContext) { state -> state.clear(workId) } }
        refreshOnShow = true
        scope.launch {
            if (visible && loadJob?.isActive != true) {
                refreshOnShow = false
                load(force = true)
            }
        }
    }

    private fun bookRow(section: ReadingSection): View =
        SeriesBookStrip.create(requireNotNull(host).viewContext, colors, ringVisible, api, section.items) { card, item ->
            if (ReadingWorkPresentation.canOpen(item)) {
                val key = "book:${item.workId}"
                actionViews[key] = card
                FocusDecorator.listen(card, ringVisible) { _, focused ->
                    if (focused) { lastActionKey = key; host?.refreshHints() }
                }
                card.activateOnTap { host?.push(ReadingWorkScreen(api, item.workId, item.title, ringVisible)) }
            } else {
                val key = "missing:${item.number}:${item.title}"
                actionViews[key] = card; hasChildLinks = true
                FocusDecorator.listen(card, ringVisible) { _, focused -> if (focused) { lastActionKey = key; host?.refreshHints() } }
                card.activateOnTap { host?.push(MissingReadingItemScreen(api, item, ringVisible)) }
            }
        }

    /**
     * "Pierce Brown ›" and "Red Rising ›" under a book's actions: its author's
     * page and its series page. Keys start "list:" so they never outrank Read
     * for first focus.
     */
    /**
     * The author and the series as chips under the title, each opening its
     * page: "Pierce Brown", and "Red Rising #6" with the number in the accent.
     */
    private fun bookLinks(work: ReadingWork, into: LinearLayout) {
        into.removeAllViews()
        into.visibility = if (work.authorRefs.isEmpty() && work.seriesId.isBlank()) View.GONE else View.VISIBLE
        val context = requireNotNull(host).viewContext
        fun link(key: String, label: CharSequence, icon: AppIcon, description: String, open: () -> Unit) =
            PillButton.create(context, colors, description, icon, heightDp = 26f).apply {
                text = label
                textSize = 12f
                contentDescription = description
                FocusDecorator.attach(this, ringVisible, scale = false)
                FocusDecorator.listen(this, ringVisible) { _, focused -> if (focused) { lastActionKey = key; host?.refreshHints() } }
                actionViews[key] = this
                hasChildLinks = true
                activateOnTap { open() }
            }
        val libraryId = work.libraryId.ifBlank { "storyteller:books" }
        work.authorRefs.forEach { ref ->
            into.addView(link("list:author:${ref.id}", ref.name, AppIcon.PERSON, "Open author ${ref.name}") {
                host?.push(ReadingAuthorScreen(api, libraryId, ReadingAuthor(id = ref.id, name = ref.name), ringVisible))
            }, LinearLayout.LayoutParams(WRAP, WRAP))
        }
        if (work.seriesId.isNotBlank()) {
            val label = android.text.SpannableStringBuilder(work.series.ifBlank { "Series" }).apply {
                if (work.seriesNumber.isNotBlank()) {
                    val from = length
                    append("  #").append(work.seriesNumber)
                    setSpan(android.text.style.ForegroundColorSpan(colors.accent), from, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), from, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            into.addView(link("list:series", label, AppIcon.SERIES, "Open series ${work.series}, book ${work.seriesNumber}") {
                host?.push(ReadingWorkScreen(api, work.seriesId, work.series, ringVisible))
            }, LinearLayout.LayoutParams(WRAP, WRAP))
        }
    }

    /** The book's series in order, this book marked and the rest a press away. */
    private fun seriesStrip(work: ReadingWork, books: List<ReadingSectionItem>): View =
        SeriesBookStrip.create(requireNotNull(host).viewContext, colors, ringVisible, api, books) { card, item ->
            if (item.workId == work.id) {
                // "Book 6 · This book" wrapped; its number is plain from its neighbours.
                card.subtitleView.text = "This book"
                card.isFocusable = false
                card.isFocusableInTouchMode = false
                return@create
            }
            val key = if (ReadingWorkPresentation.canOpen(item)) "list:book:${item.workId}" else "list:missing:${item.number}:${item.title}"
            actionViews[key] = card
            hasChildLinks = true
            FocusDecorator.listen(card, ringVisible) { _, focused -> if (focused) { lastActionKey = key; host?.refreshHints() } }
            card.activateOnTap {
                if (ReadingWorkPresentation.canOpen(item)) host?.push(ReadingWorkScreen(api, item.workId, item.title, ringVisible))
                else host?.push(MissingReadingItemScreen(api, item, ringVisible))
            }
        }

    private fun loadSeries(seriesId: String) {
        if (seriesJob?.isActive == true) return
        seriesJob = scope.launch {
            val series = (api.readingWork(seriesId) as? HubResult.Ok)?.value ?: return@launch
            seriesBooks = seriesId to series.sections.flatMap { it.items }
            if (visible) lastWork?.let(::render)
        }
    }

    /** A section's heading, a row's (`.row h3`), with a quiet count ("Volume 1961  147 issues"). */
    private fun sectionTitle(text: String, count: String? = null): TextView =
        com.pocketds.hub.ui.glass.GlassHeading.create(requireNotNull(host).viewContext, text, count).apply {
            setPadding(dp(EDGE_DP), dp(10), dp(EDGE_DP), dp(0))
        }

    private fun infoCard(title: String, subtitle: String): TextView =
        TextView(requireNotNull(host).viewContext).apply {
            text = if (subtitle.isBlank()) title else "$title\n$subtitle"
            textSize = 13f
            setTextColor(colors.primaryText)
            // A row of the page's glass, as the prototype's lists are.
            com.pocketds.hub.ui.glass.GlassPanelDrawable.attach(this, Styler.dp(context, 13f))
            setPadding(dp(12), dp(9), dp(12), dp(9))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(EDGE_DP), dp(6), dp(EDGE_DP), dp(6)) }
        }

    private fun progressText(progress: ReadingProgress?): String? = progress?.let {
        when {
            it.completed -> "Completed"
            it.percentage > 0 -> "${Fmt.readingPercentLabel(it.percentage)} read"
            else -> null
        }
    }

    private fun dp(value: Int) = Styler.dpInt(requireNotNull(host).viewContext, value.toFloat())

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        /** The prototype's Pocket book page: 22dp edges and 31dp pills. */
        const val EDGE_DP = 22
        const val PILL_DP = 31f
    }
}
