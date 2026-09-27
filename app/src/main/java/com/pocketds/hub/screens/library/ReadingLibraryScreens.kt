package com.pocketds.hub.screens.library

import com.pocketds.hub.settings.DomainPreferences
import com.pocketds.hub.settings.SortPreference
import com.pocketds.hub.ui.LibrarySortPanel
import com.pocketds.hub.ui.CenteredIconTextView
import com.pocketds.hub.state.ContentMode
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.KeyEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.EditText
import android.app.AlertDialog
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.request.ImageRequest
import com.pocketds.hub.input.HorizontalMode
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.ReadingEdition
import com.pocketds.hub.model.ReadingContinue
import com.pocketds.hub.model.ReadingLibrary
import com.pocketds.hub.model.ReadingProgress
import com.pocketds.hub.model.ReadingSection
import com.pocketds.hub.model.ReadingSectionItem
import com.pocketds.hub.model.ReadingWork
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
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.state.LibraryGridSizing
import com.pocketds.hub.state.PagedLoadState
import com.pocketds.hub.state.StableItemFocus
import com.pocketds.hub.ui.DetailHeaderView
import com.pocketds.hub.ui.DetailArtworkCardView
import com.pocketds.hub.ui.ContinuationCardView
import com.pocketds.hub.ui.DetailLayout
import com.pocketds.hub.ui.DetailStyler
import com.pocketds.hub.ui.AppIcon
import com.pocketds.hub.ui.AppIconDrawable
import com.pocketds.hub.ui.MediaActionIcon
import com.pocketds.hub.ui.MediaActionIconDrawable
import com.pocketds.hub.ui.ChoiceOverlay
import com.pocketds.hub.ui.FocusDecorator
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
import kotlin.math.roundToInt

/** One Kavita or Storyteller library, paged through the Hub's normalized model. */
class ReadingLibraryGridScreen(
    private val api: HubApi,
    private val library: ReadingLibrary,
    private val ringVisible: () -> Boolean
) : Screen {
    override val contentDomain = com.pocketds.hub.state.ContentMode.BOOKS
    override val title = library.title
    override val horizontalMode get() = if(sortKey=="author") HorizontalMode.CONFINED else HorizontalMode.GRID

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var libraryRevision = MediaLibraryChanges.revision
    private val paging = PagedLoadState(PREFETCH_AHEAD)
    private val adapter = WorkAdapter()
    private lateinit var colors: PocketColors
    private lateinit var sortControl: CenteredIconTextView
    private lateinit var status: TextView
    private lateinit var grid: RecyclerView
    private lateinit var authorShelves:AuthorShelvesView
    private lateinit var overlay: ChoiceOverlay
    private var host: ScreenHost? = null
    private var loadJob: Job? = null
    private val focusState = StableItemFocus()
    private val sortFields = ReadingSortFields.forLibrary(library)
    private var sortKey = if (sortFields.any { it.first == "series" }) "series" else "title"
    private var sortAscending = true
    private var loadGeneration = 0
    private var refreshing = false

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        val remembered=DomainPreferences.sort(host.viewContext,ContentMode.BOOKS,sortFields.map { it.first },if (sortFields.any { it.first == "series" }) "series" else "title")
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
                text=LibrarySortPanel.label(sortFields,SortPreference(sortKey,sortAscending))
                contentDescription="Sort library, $text"
            }
            toolbar.addView(sortControl)
            addView(toolbar)
            grid = RecyclerView(context).apply {
                layoutManager = GridLayoutManager(context, MAX_COLUMNS)
                adapter = this@ReadingLibraryGridScreen.adapter
                setItemViewCacheSize(MAX_COLUMNS * 3)
                clipToPadding = false
                clipChildren = false
                setPadding(dp(16), dp(12), dp(16), dp(84))
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
            authorShelves=AuthorShelvesView(context,api,library.id,colors,ringVisible,
                {message,failed->status.text=message;status.setTextColor(if(failed)colors.dangerText else colors.mutedText)},
                {if(sortKey=="author"){grid.visibility=View.GONE;authorShelves.visibility=View.VISIBLE}},
                {work->host.push(ReadingWorkScreen(api,work.id,work.title,ringVisible))}).apply {visibility=View.GONE}
            shelfArea.addView(authorShelves,FrameLayout.LayoutParams(MATCH,MATCH))
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
        if(saved!=SortPreference(sortKey,sortAscending)) { applySort(saved); return }
        if(sortKey=="author"){authorShelves.show(sortAscending);return}
        if (paging.loadedPage > 0 && ::grid.isInitialized) adapter.notifyDataSetChanged()
        if (paging.loadedPage == 0 && loadJob?.isActive != true) {
            (paging.retry() ?: paging.initial())?.let(::loadPage)
        } else {
            restoreFocus()
        }
    }

    override fun onHide() {
        loadGeneration++
        if(::authorShelves.isInitialized)authorShelves.hide()
        val position = focusedPosition()
        focusedWork()?.let { focusState.remember(position, it.id) }
        if (::overlay.isInitialized && overlay.isOpen) overlay.dismiss()
        paging.cancelLoading()
        scope.coroutineContext.cancelChildren()
        loadJob = null
    }

    override fun onDestroyView() {
        if(::authorShelves.isInitialized)authorShelves.destroy()
        scope.cancel()
        host = null
    }

    override fun requestInitialFocus(): Boolean {
        if (::overlay.isInitialized && overlay.isOpen) return true
        if(::authorShelves.isInitialized && authorShelves.visibility==View.VISIBLE)return authorShelves.restoreFocus()
        if (!::grid.isInitialized || adapter.itemCount == 0) return if(::sortControl.isInitialized) sortControl.requestFocus() else false
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
            ButtonHint.activate("Details"),
            ButtonHint.back(),
            ButtonHint.secondary("Sort"),
            ButtonHint("⟳", "Refresh (Select)", PadAction.Refresh)
        )
    }

    override fun onPad(action: PadAction): Boolean {
        if (::overlay.isInitialized && overlay.onPad(action)) {
            host?.refreshHints()
            return true
        }
        return when (action) {
            PadAction.Activate -> if(authorShelves.visibility==View.VISIBLE) false else focusedWork()?.let(::open) != null
            PadAction.Secondary -> {
                showSortPanel()
                true
            }
            PadAction.Refresh -> {
                if(sortKey=="author"){authorShelves.show(sortAscending,force=true);return true}
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
        if(sortKey=="author")authorShelves.show(sortAscending,force=true) else {authorShelves.hide();paging.initial()?.let(::loadPage)}
    }

    private fun loadPage(page: Int) {
        if (loadJob?.isActive == true) return
        val generation = loadGeneration
        status.setTextColor(colors.mutedText)
        status.text = when {
            refreshing -> "Refreshing ${library.title}…"
            adapter.itemCount == 0 -> "Loading ${library.title}…"
            else -> "Loading more…"
        }
        loadJob = scope.launch {
            when (val result = api.readingLibraryItems(
                library.id,
                page,
                sortKey,
                if (sortAscending) "asc" else "desc"
            )) {
                is HubResult.Ok -> {
                    if (generation != loadGeneration) return@launch
                    paging.complete(page, result.value.totalPages)
                    if(page==1){authorShelves.visibility=View.GONE;grid.visibility=View.VISIBLE}
                    if (page == 1 && refreshing) {
                        adapter.replace(result.value.items)
                        refreshing = false
                    } else if (page == 1 && adapter.itemCount == 0) {
                        adapter.replace(result.value.items)
                    } else {
                        adapter.append(result.value.items)
                    }
                    status.setTextColor(
                        if (result.value.partial.isEmpty()) colors.mutedText else colors.badgePending
                    )
                    status.text = when {
                        result.value.items.isEmpty() && adapter.itemCount == 0 -> "This reading library is empty."
                        result.value.cache.stale -> "${adapter.itemCount} of ${result.value.total} · cached"
                        else -> "${adapter.itemCount} of ${result.value.total} · ${sortLabel()}"
                    }
                    if (page == 1 && !overlay.isOpen) restoreFocus()
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

    private fun applySort(value:SortPreference) {
        sortKey=value.field;sortAscending=value.ascending
        DomainPreferences.setSort(requireNotNull(host).viewContext,ContentMode.BOOKS,value)
        sortControl.text=LibrarySortPanel.label(sortFields,value)
        sortControl.contentDescription="Sort library, ${sortControl.text}"
        reload(resetSelection=true)
    }
    private fun showSortPanel() {
        LibrarySortPanel.show(overlay,sortControl,sortFields,SortPreference(sortKey,sortAscending),::applySort,{host?.refreshHints()})
        host?.refreshHints()
    }

    private fun sortLabel(): String {
        val field = sortFields.firstOrNull { it.first == sortKey }?.second ?: "Title"
        return "$field · ${ReadingSortFields.directionLabel(sortKey, sortAscending)}"
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
            val card = PosterCardView(parent.context, colors, POSTER_DP).apply {
                layoutParams = RecyclerView.LayoutParams(dp(CARD_DP), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp(8), dp(8), dp(8), dp(8))
                }
                FocusDecorator.attach(this, ringVisible)
                setOnFocusChangeListener { _, focused ->
                    FocusDecorator.refresh(this, ringVisible())
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
            val client = api as? HubClient
            card.bindReadingWork(
                ReadingCompletionRepository.get(card.context).project(work),
                client?.imageLoader ?: ImageLoader(card.context),
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
        const val CARD_DP = 104
        const val POSTER_DP = 150f
        const val TAG_WORK = -0x7fffffdf
    }
}

/** Read-only work detail until the reader manifest milestone adds Open/Read actions. */
class ReadingWorkScreen(
    private val api: HubApi,
    private val workId: String,
    initialTitle: String,
    private val ringVisible: () -> Boolean
) : Screen {
    override val contentDomain = com.pocketds.hub.state.ContentMode.BOOKS
    override val title = initialTitle
    override val focusOnShow = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var colors: PocketColors
    private lateinit var status: TextView
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private var host: ScreenHost? = null
    private var loadJob: Job? = null
    private var hasChildLinks = false
    private lateinit var detailHeader: DetailHeaderView
    private lateinit var listOverlay: ChoiceOverlay
    private var lastActionKey: String? = null
    private val actionViews = linkedMapOf<String, View>()
    @Volatile private var refreshOnShow = false
    private var lastWork: ReadingWork? = null
    private var previewFormatWorkId = ""
    private var previewFormat: ReadingEntryChoice? = null
    private val completionSession = ReadingCompletionSession()
    @Volatile private var visible = false

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        colors = Theme.colors(host.viewContext)
        val main = LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.background)
            status = TextView(context).apply {
                textSize = 11f
                setTextColor(colors.mutedText)
                setPadding(dp(16), dp(6), dp(16), dp(4))
            }
            addView(status)
            scroll = ScrollView(context).apply {
                isFillViewport = true
                clipToPadding = false
                setPadding(0, 0, 0, dp(16))
                isFocusable = false
                isFocusableInTouchMode = false
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
        }
    }

    override fun onShow() {
        visible = true
        if (::detailHeader.isInitialized) detailHeader.overview.collapse()
        if (::listOverlay.isInitialized && listOverlay.isOpen) listOverlay.dismiss()
        lastWork?.let(::render)
        if (actionViews.isNotEmpty()) scroll.post { if (scroll.isShown) requestInitialFocus() }
        if (refreshOnShow && loadJob?.isActive != true) {
            refreshOnShow = false
            load(force = true)
        } else if (content.childCount == 0 && loadJob?.isActive != true) {
            load()
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
        host = null
    }

    override fun requestInitialFocus(): Boolean =
        actionViews[DetailLayout.restoreFocus(lastActionKey,
            actionViews.keys.filterNot { it.startsWith("list:") } + actionViews.keys.filter { it.startsWith("list:") })]?.requestFocus() == true

    override fun hints() = buildList {
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
            add(ButtonHint.activate(ReadingActionHint.label(focusedAction.key,
                text = (view as? TextView)?.text?.toString().orEmpty(),
                description = view.contentDescription?.toString().orEmpty())))
        } else if (hasChildLinks) add(ButtonHint.activate("Open"))
        add(ButtonHint.back())
        add(ButtonHint("⟳", "Refresh (Select)", PadAction.Refresh))
    }

    private fun attachActionFocus(view: TextView) {
        FocusDecorator.attach(view, ringVisible, scale = false)
        view.setOnFocusChangeListener { focused, _ ->
            FocusDecorator.refresh(focused, ringVisible())
            host?.refreshHints()
        }
    }

    override fun onPad(action: PadAction): Boolean {
        if (::listOverlay.isInitialized && listOverlay.isOpen) {
            val handled = listOverlay.onPad(action)
            if (handled) host?.refreshHints()
            return handled
        }
        if (::detailHeader.isInitialized && detailHeader.overview.onPad(action)) return true
        return when (action) {
        PadAction.Refresh -> {
            load(force = true)
            true
        }
        else -> false
    }
    }

    override fun onSystemBack(): Boolean {
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
        status.setTextColor(colors.mutedText)
        status.text = if (force) "Refreshing…" else "Loading details…"
        loadJob = scope.launch {
            when (val result = api.readingWork(workId)) {
                is HubResult.Ok -> render(result.value)
                is HubResult.Failed -> {
                    status.setTextColor(colors.dangerText)
                    status.text = result.message + " · Select retries"
                }
            }
            loadJob = null
        }
    }

    private fun render(source: ReadingWork) {
        lastWork = source
        val checkpoints = com.pocketds.hub.reader.ReadingProgress.get(requireNotNull(host).viewContext)
        val work = ReadingCompletionRepository.get(requireNotNull(host).viewContext).project(
            com.pocketds.hub.reader.ReadingProgressPresentation.project(source,
                checkpoints.store.pending(checkpoints.session().identity)))
        val previouslyFocusedSource = actionViews.entries.firstOrNull { it.value.hasFocus() }?.key ?: lastActionKey
        val previousScrollY = scroll.scrollY
        content.removeAllViews()
        actionViews.clear()
        hasChildLinks = false
        content.addView(hero(work))
        val primaryRead = ReadingWorkPresentation.primaryRead(work)
        if (work.entityType == "collection") work.continueAt?.let { point ->
            detailHeader.continuation.addView(continueCard(work, point))
        }
        if (work.editions.isNotEmpty() && primaryRead == null) {
            content.addView(sectionTitle("Editions"))
            work.editions.forEach { content.addView(editionCard(work, it)) }
        }
        work.sections.forEach { section ->
            content.addView(sectionTitle(section.title))
            if (work.entityType == "collection" && section.items.isNotEmpty()) {
                hasChildLinks = hasChildLinks || section.items.any(ReadingWorkPresentation::canOpen)
                content.addView(bookRow(section))
            } else {
                section.items.forEach { content.addView(sectionItemCard(work, it)) }
            }
        }
        val preferredSource = previouslyFocusedSource?.takeIf { it.startsWith("list:") && it in actionViews }
            ?: ReadingWorkPresentation.preferredActionSource(
            continueSourceItemId = work.continueAt?.sourceItemId.orEmpty(),
            readableSourceItemIds = actionViews.keys.filterNot { it.startsWith("list:") },
            previouslyFocusedSourceItemId = previouslyFocusedSource
        ) ?: previouslyFocusedSource?.takeIf { it in actionViews }
        content.post {
            if (visible) {
                scroll.scrollTo(0, previousScrollY)
                actionViews[preferredSource]?.requestFocus()
            }
        }
        status.setTextColor(if (work.partial.isEmpty()) colors.mutedText else colors.badgePending)
        status.text = when {
            work.partial.isNotEmpty() -> work.partial.joinToString(" · ") { it.message }
            work.cache.stale -> "Showing cached details"
            else -> ""
        }
        status.visibility = if (status.text.isNullOrBlank()) View.GONE else View.VISIBLE
        host?.refreshHints()
    }

    private fun hero(work: ReadingWork): View = DetailHeaderView(requireNotNull(host).viewContext, colors, ringVisible).apply {
        detailHeader = this
        compact = true
        overview.onChanged = { host?.refreshHints() }
        titleView.text = work.title
        subtitleView.visibility = View.GONE
        metadataView.text = buildList {
            if (work.authors.isNotEmpty()) add(work.authors.joinToString(", "))
            if (work.entityType == "collection") {
                add("${work.bookCount} available")
                val missing = work.sections.sumOf { section -> section.items.count { !it.isAvailable } }
                if (missing > 0) add("$missing missing")
            } else if (work.year > 0) add(work.year.toString())
            if (work.genres.isNotEmpty()) add(work.genres.joinToString(", "))
            progressText(work.progress)?.let(::add)
        }.joinToString(" · ")
        overview.bind(work.overview)
        bindArtwork("book", null, work.artwork.takeIf { it.isNotBlank() }?.let(api::imageUrl),
            (api as? HubClient)?.imageLoader ?: ImageLoader(context))
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
            stateView.visibility = View.GONE
            stateView.isFocusable = false
            entry?.let { choice ->
                val modeName = when (choice.mode) {
                    ReadingEntryMode.READ -> "reading"
                    ReadingEntryMode.LISTEN -> "listening"
                    ReadingEntryMode.READ_ALONG -> "read along"
                }
                val primary = CenteredIconTextView(context).apply {
                    text = if (remembered != null && previewFormat == null) "Continue" else when (choice.mode) {
                        ReadingEntryMode.READ -> choice.text?.label?.takeUnless { it == "Read book" }
                            ?: selectedOption?.label ?: "Read"
                        ReadingEntryMode.LISTEN -> if (previewFormat != null) "Listen · ${selectedOption?.detail.orEmpty()}" else "Listen"
                        ReadingEntryMode.READ_ALONG -> if (previewFormat != null) "Read along · ${selectedOption?.narration.orEmpty()}" else "Read along"
                    }
                    contentDescription = "$text ${work.title}, $modeName"
                    textSize = 14f
                    DetailStyler.action(this, colors, primary = true)
                    setCenteredIcon(
                        AppIconDrawable(when (choice.mode) {
                            ReadingEntryMode.READ -> AppIcon.BOOK
                            ReadingEntryMode.LISTEN -> AppIcon.HEADPHONES
                            ReadingEntryMode.READ_ALONG -> AppIcon.READ_ALONG
                        }, colors.accentText), dp(20), dp(8))
                    setPadding(dp(16), 0, dp(16), 0)
                    attachActionFocus(this)
                    activateOnTap { launchEntry(work, previewFormat ?: choice) }
                }
                actions.addView(primary, LinearLayout.LayoutParams(WRAP, dp(48)).apply { marginEnd = dp(8) })
                actionViews["entry"] = primary
                hasChildLinks = true
            }
            if (formatMenu.options.size > 1) {
                val changeFormat = TextView(context).apply {
                    text = "Change format"
                    textSize = 12f
                    contentDescription = "Change reading format or narration"
                    DetailStyler.action(this, colors)
                    setPadding(dp(12), 0, dp(12), 0)
                    attachActionFocus(this)
                    activateOnTap { showFormatMenu(work, formatMenu) }
                }
                actions.addView(changeFormat, LinearLayout.LayoutParams(WRAP, dp(48)).apply { marginEnd = dp(8) })
                actionViews["format"] = changeFormat
            }
            val read = CenteredIconTextView(context).apply {
                text = ""
                contentDescription = if (work.progress?.completed == true) "Mark ${work.title} unread" else "Mark ${work.title} read"
                textSize = 13f
                DetailStyler.action(this, colors, primary = false)
                setCenteredIcon(
                    MediaActionIconDrawable(context,
                        if (work.progress?.completed == true) MediaActionIcon.WATCHED else MediaActionIcon.UNWATCHED,
                        colors.primaryText), dp(21))
                setPadding(dp(12), 0, dp(12), 0)
                attachActionFocus(this)
                activateOnTap {
                    val context = requireNotNull(host).viewContext
                    val changed = ReadingCompletionRepository.update(context) { current ->
                        if (work.progress?.completed == true) completionSession.unmark(current, work.id)
                        else completionSession.markRead(current, work.id)
                    }
                    ReadingListsRepository.update(context) { state ->
                        state.recordProgress(work.id, changed.project(requireNotNull(lastWork)).progress?.percentage ?: 0.0)
                    }
                    render(requireNotNull(lastWork))
                    host?.notify(when {
                        changed.isRead(work.id) -> "Marked as read"
                        changed.shouldStartAtBeginning(work.id) -> "Marked unread · next read starts at the beginning"
                        else -> "Previous reading position restored"
                    })
                }
            }
            val wanted = ReadingListsRepository.get(context).wantToRead.any { it.workId == work.id }
            val want = CenteredIconTextView(context).apply {
                text = ""
                contentDescription = if (wanted) "Remove ${work.title} from Want to Read" else "Add ${work.title} to Want to Read"
                textSize = 13f
                DetailStyler.action(this, colors, primary = false)
                setCenteredIcon(
                    AppIconDrawable(if (wanted) AppIcon.BOOKMARK_FILLED else AppIcon.BOOKMARK,
                        colors.primaryText), dp(21))
                setPadding(dp(12), 0, dp(12), 0)
                attachActionFocus(this)
                activateOnTap {
                    val next = ReadingListsRepository.update(context) { state ->
                        if (state.wantToRead.any { it.workId == work.id }) state.remove(ReadingListsState.WANT_TO_READ, work.id)
                        else state.add(ReadingListsState.WANT_TO_READ, ReadingListEntry.from(work))
                    }
                    val selected = next.wantToRead.any { it.workId == work.id }
                    contentDescription = if (selected) "Remove ${work.title} from Want to Read"
                        else "Add ${work.title} to Want to Read"
                    setCenteredIcon(
                        AppIconDrawable(if (selected) AppIcon.BOOKMARK_FILLED else AppIcon.BOOKMARK,
                            colors.primaryText), dp(21))
                    host?.notify(if (selected) "Added to Want to Read" else "Removed from Want to Read")
                    host?.refreshHints()
                }
            }
            actions.addView(want, LinearLayout.LayoutParams(WRAP, dp(48)).apply { marginEnd = dp(8) })
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
                    DetailStyler.action(this, colors)
                    setCenteredIcon(MediaActionIconDrawable(context,
                        MediaActionIcon.MORE, colors.primaryText), dp(21))
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
                actions.addView(editions, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) })
                actionViews["list:more"] = editions
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

    private fun continueCard(work: ReadingWork, point: ReadingContinue): View =
        ContinuationCardView(requireNotNull(host).viewContext, colors, ringVisible, portrait = true).apply {
            bind("Continue reading", buildList {
                add(point.title)
                if (point.number.isNotBlank()) add("Book ${point.number}")
                if (point.percentage > 0) add("${(point.percentage * 100).roundToInt()}% read")
            }.joinToString(" · "), point.percentage, point.percentage >= 1.0)
            val url = ReadingWorkPresentation.continueArtwork(work).takeIf { it.isNotBlank() }?.let(api::imageUrl)
            DetailStyler.image(image, url, (api as? HubClient)?.imageLoader ?: ImageLoader(context))
            onFocused = { lastActionKey = point.sourceItemId; host?.refreshHints() }
            if (canReadPublication(work.kind, point.sourceItemId)) {
                hasChildLinks = true
                activateOnTap { openPublication(work, point.sourceItemId, point.title, point.source) }
                actionViews.putIfAbsent(point.sourceItemId, this)
            } else { isFocusable = false; isClickable = false }
        }

    private fun editionCard(work: ReadingWork, edition: ReadingEdition): View = infoCard(
        edition.kind.replaceFirstChar { it.uppercase() },
        buildList {
            if (edition.format.isNotBlank()) add(edition.format.uppercase())
            if (edition.pageCount > 0) add("${edition.pageCount} pages")
            if (edition.durationMs > 0) add(durationText(edition.durationMs))
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
                ebookSourceItemId = work.editions.firstOrNull { it.kind == "ebook" }?.sourceItemId ?: sourceItemId
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
            onProgressChanged = ::requestRefreshAfterReading
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
        HorizontalScrollView(requireNotNull(host).viewContext).apply {
            isFocusable = false; isFocusableInTouchMode = false
            isHorizontalScrollBarEnabled = false; clipToPadding = false; clipChildren = false
            val clearance = DetailLayout.focusClearance(DetailLayout.posterCardHeight(120, resources.configuration.fontScale)).coerceAtLeast(10)
            setPadding(dp(24), dp(clearance), dp(24), dp(clearance))
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; clipChildren = false
                section.items.forEach { item ->
                    addView(DetailArtworkCardView(context, colors, ringVisible).apply {
                        artworkHeight(120)
                        layoutParams = LinearLayout.LayoutParams(dp(88), WRAP).apply { marginEnd = dp(14) }
                        titleView.text = item.title; titleView.minLines = 2
                        subtitleView.text = buildList {
                            if (item.number.isNotBlank()) add("Book ${item.number}")
                            if (!item.isAvailable) add("Missing") else progressText(item.progress)?.removeSuffix(" read")?.let(::add)
                        }.joinToString(" · ")
                        subtitleView.maxLines = 1
                        available(item.isAvailable)
                        contentDescription = "${item.title}, ${subtitleView.text}"
                        DetailStyler.image(image, item.artwork.takeIf { it.isNotBlank() }?.let(api::imageUrl),
                            (api as? HubClient)?.imageLoader ?: ImageLoader(context))
                        if (ReadingWorkPresentation.canOpen(item)) {
                            val key = "book:${item.workId}"
                            actionViews[key] = this
                            setOnFocusChangeListener { view, focused ->
                                FocusDecorator.refresh(view, ringVisible())
                                if (focused) { lastActionKey = key; host?.refreshHints() }
                            }
                            activateOnTap { host?.push(ReadingWorkScreen(api, item.workId, item.title, ringVisible)) }
                        } else {
                            val key="missing:${item.number}:${item.title}"
                            actionViews[key]=this;hasChildLinks=true
                            setOnFocusChangeListener { view,focused->FocusDecorator.refresh(view,ringVisible());if(focused){lastActionKey=key;host?.refreshHints()} }
                            activateOnTap { host?.push(MissingReadingItemScreen(api,item,ringVisible)) }
                        }
                    })
                }
            })
        }

    private fun sectionTitle(text: String): TextView = TextView(requireNotNull(host).viewContext).apply {
        this.text = text
        textSize = 17f
        setTextColor(colors.primaryText)
        setPadding(dp(24), dp(14), dp(24), dp(2))
    }

    private fun infoCard(title: String, subtitle: String): TextView =
        TextView(requireNotNull(host).viewContext).apply {
            text = if (subtitle.isBlank()) title else "$title\n$subtitle"
            textSize = 13f
            setTextColor(colors.primaryText)
            background = Styler.cardBackground(context, colors)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(24), dp(6), dp(24), dp(6)) }
        }

    private fun progressText(progress: ReadingProgress?): String? = progress?.let {
        when {
            it.completed -> "Completed"
            it.percentage > 0 -> "${(it.percentage * 100).roundToInt()}% read"
            else -> null
        }
    }

    private fun durationText(durationMs: Long): String {
        val minutes = durationMs / 60_000
        val hours = minutes / 60
        val rest = minutes % 60
        return if (hours > 0) "${hours}h ${rest}m" else "${minutes}m"
    }

    private fun dp(value: Int) = Styler.dpInt(requireNotNull(host).viewContext, value.toFloat())

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
