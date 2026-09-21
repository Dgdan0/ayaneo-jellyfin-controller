package com.pocketds.hub.screens.library

import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.KeyEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
import com.pocketds.hub.reader.PagedImageReaderScreen
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.state.LibraryGridSizing
import com.pocketds.hub.state.PagedLoadState
import com.pocketds.hub.state.StableItemFocus
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
    override val title = library.title
    override val horizontalMode = HorizontalMode.GRID

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val paging = PagedLoadState(PREFETCH_AHEAD)
    private val adapter = WorkAdapter()
    private lateinit var colors: PocketColors
    private lateinit var status: TextView
    private lateinit var grid: RecyclerView
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
                adapter = this@ReadingLibraryGridScreen.adapter
                setItemViewCacheSize(MAX_COLUMNS * 3)
                clipToPadding = false
                clipChildren = false
                setPadding(dp(16), dp(12), dp(16), dp(84))
                layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
                addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
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
            addView(grid)
        }
        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))
        overlay = ChoiceOverlay(host.viewContext, colors, ringVisible)
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        return root
    }

    override fun onShow() {
        if (paging.loadedPage == 0 && loadJob?.isActive != true) {
            (paging.retry() ?: paging.initial())?.let(::loadPage)
        } else {
            restoreFocus()
        }
    }

    override fun onHide() {
        val position = focusedPosition()
        focusedWork()?.let { focusState.remember(position, it.id) }
        if (::overlay.isInitialized && overlay.isOpen) overlay.dismiss()
        paging.cancelLoading()
        scope.coroutineContext.cancelChildren()
        loadJob = null
    }

    override fun onDestroyView() {
        scope.cancel()
        host = null
    }

    override fun requestInitialFocus(): Boolean {
        if (!::grid.isInitialized || adapter.itemCount == 0) return false
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
            PadAction.Activate -> focusedWork()?.let(::open) != null
            PadAction.Secondary -> {
                showSortFields()
                true
            }
            PadAction.Refresh -> {
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
        paging.initial()?.let(::loadPage)
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
            title = "Sort ${library.title}",
            subtitle = "Choose what the library is ordered by",
            choices = sortFields.map { (id, label) ->
                ChoiceOverlay.Choice(id, label, if (id == sortKey) "Currently selected" else "")
            },
            startIndex = sortFields.indexOfFirst { it.first == sortKey }.coerceAtLeast(0),
            onCancel = { host?.refreshHints() }
        ) { showSortDirection(it) }
        host?.refreshHints()
    }

    private fun showSortDirection(field: String) {
        val suggestedAscending = if (field == sortKey) sortAscending
        else ReadingSortFields.defaultAscending(field)
        overlay.show(
            title = "Sort direction",
            subtitle = sortFields.firstOrNull { it.first == field }?.second.orEmpty(),
            choices = listOf(
                ChoiceOverlay.Choice("asc", "Ascending", "A to Z, oldest or lowest first"),
                ChoiceOverlay.Choice("desc", "Descending", "Z to A, newest or highest first")
            ),
            startIndex = if (suggestedAscending) 0 else 1,
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
        val field = sortFields.firstOrNull { it.first == sortKey }?.second ?: "Title"
        return "$field ${if (sortAscending) "ascending" else "descending"}"
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
                work,
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
    override val title = initialTitle
    override val focusOnShow = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var colors: PocketColors
    private lateinit var status: TextView
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private var host: ScreenHost? = null
    private var loadJob: Job? = null
    private var hasChildLinks = false
    private var presentation = ReadingWorkPresentation.initial("")
    private var descriptionBox: ScrollView? = null
    private var descriptionText: TextView? = null
    private var descriptionToggle: TextView? = null
    private var descriptionHasOverflow = false
    private val actionViews = linkedMapOf<String, View>()
    @Volatile private var refreshOnShow = false
    @Volatile private var visible = false

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        colors = Theme.colors(host.viewContext)
        return LinearLayout(host.viewContext).apply {
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
                setPadding(dp(16), dp(8), dp(16), dp(84))
                Styler.makeFocusable(this)
                content = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                }
                addView(content, ViewGroup.LayoutParams(MATCH, WRAP))
            }
            addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
    }

    override fun onShow() {
        visible = true
        if (presentation.descriptionExpanded) {
            presentation = ReadingWorkPresentation.initial(presentation.description)
            applyDescriptionPresentation(requestReadingFocus = false)
        }
        if (refreshOnShow && loadJob?.isActive != true) {
            refreshOnShow = false
            load(force = true)
        } else if (content.childCount == 0 && loadJob?.isActive != true) {
            load()
        }
    }

    override fun onHide() {
        visible = false
        presentation = ReadingWorkPresentation.initial(presentation.description)
        applyDescriptionPresentation(requestReadingFocus = false)
        scope.coroutineContext.cancelChildren()
        loadJob = null
    }

    override fun onDestroyView() {
        scope.cancel()
        host = null
    }

    override fun requestInitialFocus(): Boolean = ::scroll.isInitialized && scroll.requestFocus()

    override fun hints() = buildList {
        if (hasChildLinks) add(ButtonHint.activate("Open"))
        add(ButtonHint.back())
        add(ButtonHint("⟳", "Refresh (Select)", PadAction.Refresh))
    }

    override fun onPad(action: PadAction): Boolean = when (action) {
        PadAction.Refresh -> {
            load(force = true)
            true
        }
        else -> false
    }

    private fun load(force: Boolean = false) {
        if (loadJob?.isActive == true) return
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

    private fun render(work: ReadingWork) {
        val previouslyFocusedSource = actionViews.entries.firstOrNull { it.value.hasFocus() }?.key
        content.removeAllViews()
        actionViews.clear()
        hasChildLinks = false
        presentation = ReadingWorkPresentation.initial(work.overview)
        descriptionBox = null
        descriptionText = null
        descriptionToggle = null
        descriptionHasOverflow = false
        content.addView(hero(work))
        work.continueAt?.let { point ->
            content.addView(sectionTitle("Continue reading"))
            content.addView(continueCard(work, point))
        }
        if (work.editions.isNotEmpty()) {
            content.addView(sectionTitle("Available editions"))
            work.editions.forEach { content.addView(editionCard(it)) }
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
        val preferredSource = ReadingWorkPresentation.preferredActionSource(
            continueSourceItemId = work.continueAt?.sourceItemId.orEmpty(),
            readableSourceItemIds = actionViews.keys.toList(),
            previouslyFocusedSourceItemId = previouslyFocusedSource
        )
        content.post {
            if (visible) actionViews[preferredSource]?.requestFocus()
        }
        status.setTextColor(if (work.partial.isEmpty()) colors.mutedText else colors.badgePending)
        status.text = when {
            work.partial.isNotEmpty() -> work.partial.joinToString(" · ") { it.message }
            work.cache.stale -> "Showing cached details"
            work.entityType == "collection" -> {
                val missing = work.sections.sumOf { section -> section.items.count { !it.isAvailable } }
                buildList {
                    add("${work.bookCount} book${if (work.bookCount == 1) "" else "s"} available")
                    if (missing > 0) add("$missing missing")
                }.joinToString(" · ")
            }
            else -> "${work.editions.size} edition${if (work.editions.size == 1) "" else "s"} available"
        }
        scroll.scrollTo(0, 0)
        host?.refreshHints()
    }

    private fun hero(work: ReadingWork): View = LinearLayout(requireNotNull(host).viewContext).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.TOP
        val cover = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageDrawable(ColorDrawable(colors.posterPlaceholder))
            contentDescription = "${work.title} cover"
        }
        addView(cover, LinearLayout.LayoutParams(dp(118), dp(177)).apply { marginEnd = dp(18) })
        val client = api as? HubClient
        val url = api.imageUrl(work.artwork)
        if (url.isNotEmpty()) {
            (client?.imageLoader ?: ImageLoader(context)).enqueue(
                ImageRequest.Builder(context)
                    .data(url)
                    .target(cover)
                    .bitmapConfig(Bitmap.Config.RGB_565)
                    .build()
            )
        }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = work.title
                textSize = 25f
                setTextColor(colors.primaryText)
            })
            if (work.subtitle.isNotBlank()) addView(TextView(context).apply {
                text = work.subtitle
                textSize = 14f
                setTextColor(colors.accent)
            })
            val metadata = buildList {
                if (work.year > 0) add(work.year.toString())
                if (work.genres.isNotEmpty()) add(work.genres.joinToString(", "))
                progressText(work.progress)?.let(::add)
            }.joinToString(" · ")
            if (metadata.isNotBlank()) addView(TextView(context).apply {
                text = metadata
                textSize = 12f
                setTextColor(colors.mutedText)
                setPadding(0, dp(5), 0, 0)
            })
            if (work.overview.isNotBlank()) addView(descriptionPanel(work.overview))
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
    }

    private fun descriptionPanel(overview: String): View = LinearLayout(requireNotNull(host).viewContext).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(9), 0, 0)
        descriptionBox = ScrollView(context).apply box@{
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            isFocusable = false
            isFocusableInTouchMode = false
            descriptionText = TextView(context).apply {
                text = overview
                textSize = 13f
                setTextColor(colors.primaryText)
                maxLines = DESCRIPTION_COLLAPSED_LINES
                ellipsize = TextUtils.TruncateAt.END
                post {
                    val currentLayout = layout
                    descriptionHasOverflow = currentLayout != null && currentLayout.lineCount > 0 &&
                        currentLayout.getEllipsisCount(currentLayout.lineCount - 1) > 0
                    descriptionToggle?.visibility = if (descriptionHasOverflow) View.VISIBLE else View.GONE
                }
            }
            addView(descriptionText, ViewGroup.LayoutParams(MATCH, WRAP))
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN || !presentation.descriptionExpanded) {
                    return@setOnKeyListener false
                }
                val direction = when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> -1
                    KeyEvent.KEYCODE_DPAD_DOWN -> 1
                    else -> 0
                }
                if (direction == 0 || !canScrollVertically(direction)) return@setOnKeyListener false
                smoothScrollBy(0, direction * dp(DESCRIPTION_SCROLL_STEP_DP))
                true
            }
        }
        addView(descriptionBox, LinearLayout.LayoutParams(MATCH, WRAP))
        descriptionToggle = TextView(context).apply {
            textSize = 11f
            setTextColor(colors.mutedText)
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = Styler.cardBackground(context, colors, cornerDp = 7f)
            Styler.makeFocusable(this)
            FocusDecorator.attach(this, ringVisible, scale = false)
            activateOnTap {
                presentation = presentation.toggleDescription()
                applyDescriptionPresentation(requestReadingFocus = presentation.descriptionExpanded)
            }
            visibility = View.INVISIBLE
        }
        addView(descriptionToggle, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(5) })
        applyDescriptionPresentation(requestReadingFocus = false)
    }

    private fun applyDescriptionPresentation(requestReadingFocus: Boolean) {
        val box = descriptionBox ?: return
        val copy = descriptionText ?: return
        val toggle = descriptionToggle ?: return
        val expanded = presentation.descriptionExpanded
        copy.maxLines = if (expanded) Int.MAX_VALUE else DESCRIPTION_COLLAPSED_LINES
        copy.ellipsize = if (expanded) null else TextUtils.TruncateAt.END
        box.layoutParams = (box.layoutParams ?: LinearLayout.LayoutParams(MATCH, WRAP)).apply {
            width = MATCH
            height = if (expanded) dp(DESCRIPTION_EXPANDED_DP) else WRAP
        }
        box.isFocusable = expanded
        box.isFocusableInTouchMode = expanded
        toggle.text = if (expanded) "Collapse" else "Read more…"
        toggle.visibility = if (descriptionHasOverflow || expanded) View.VISIBLE else View.INVISIBLE
        box.scrollTo(0, 0)
        box.requestLayout()
        if (requestReadingFocus) box.post { box.requestFocus() }
    }

    private fun continueCard(work: ReadingWork, point: ReadingContinue): View =
        LinearLayout(requireNotNull(host).viewContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Styler.cardBackground(context, colors)
            setPadding(dp(8), dp(8), dp(12), dp(8))
            val cover = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageDrawable(ColorDrawable(colors.posterPlaceholder))
                contentDescription = "${point.title} cover"
            }
            addView(cover, LinearLayout.LayoutParams(dp(CONTINUE_COVER_WIDTH_DP), dp(CONTINUE_COVER_HEIGHT_DP)).apply {
                marginEnd = dp(12)
            })
            val artwork = ReadingWorkPresentation.continueArtwork(work)
            val url = api.imageUrl(artwork)
            if (url.isNotEmpty()) {
                ((api as? HubClient)?.imageLoader ?: ImageLoader(context)).enqueue(
                    ImageRequest.Builder(context).data(url).target(cover)
                        .bitmapConfig(Bitmap.Config.RGB_565).build()
                )
            }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = point.title
                    textSize = 15f
                    setTextColor(colors.primaryText)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                })
                addView(TextView(context).apply {
                    text = buildList {
                        if (point.number.isNotBlank()) add("Book ${point.number}")
                        if (point.percentage > 0) add("${(point.percentage * 100).roundToInt()}% read")
                    }.joinToString(" · ")
                    textSize = 12f
                    setTextColor(colors.mutedText)
                    setPadding(0, dp(5), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (canReadPublication(work.kind, point.sourceItemId) && point.source == "kavita") {
                hasChildLinks = true
                Styler.makeFocusable(this)
                FocusDecorator.attach(this, ringVisible)
                activateOnTap { openPublication(work, point.sourceItemId, point.title) }
                actionViews.putIfAbsent(point.sourceItemId, this)
            }
        }

    private fun editionCard(edition: ReadingEdition): View = infoCard(
        edition.kind.replaceFirstChar { it.uppercase() },
        buildList {
            if (edition.format.isNotBlank()) add(edition.format.uppercase())
            if (edition.pageCount > 0) add("${edition.pageCount} pages")
            if (edition.durationMs > 0) add(durationText(edition.durationMs))
            if (edition.narrator.isNotBlank()) add("Narrated by ${edition.narrator}")
            add(edition.source.replaceFirstChar { it.uppercase() })
        }.joinToString(" · ")
    )

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
            FocusDecorator.attach(this, ringVisible)
            activateOnTap { openPublication(work, item.sourceItemId, item.title) }
            actionViews.putIfAbsent(item.sourceItemId, this)
        }
    }

    private fun canReadPublication(kind: String, sourceItemId: String): Boolean =
        sourceItemId.isNotBlank() && kind in setOf("comic", "manga")

    private fun openPublication(work: ReadingWork, sourceItemId: String, publicationTitle: String) {
        host?.push(
            PagedImageReaderScreen(
                api = api,
                workId = work.id,
                initialSourceItemId = sourceItemId,
                initialTitle = publicationTitle.ifBlank { work.title },
                ringVisible = ringVisible,
                onProgressChanged = ::requestRefreshAfterReading
            )
        )
    }

    private fun requestRefreshAfterReading() {
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
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                section.items.forEach { item ->
                    addView(PosterCardView(context, colors, CHILD_POSTER_DP).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(CHILD_CARD_DP), WRAP).apply {
                            marginEnd = dp(10)
                        }
                        if (ReadingWorkPresentation.canOpen(item)) {
                            FocusDecorator.attach(this, ringVisible)
                        }
                        bindReadingWork(
                            ReadingWork(
                                id = item.workId,
                                kind = item.kind,
                                title = item.title,
                                authors = item.authors,
                                artwork = item.artwork,
                                progress = item.progress,
                                bookCount = 1
                            ),
                            (api as? HubClient)?.imageLoader ?: ImageLoader(context),
                            api::imageUrl
                        )
                        setReadingAvailability(item.isAvailable)
                        if (ReadingWorkPresentation.canOpen(item)) {
                            activateOnTap {
                                host?.push(ReadingWorkScreen(api, item.workId, item.title, ringVisible))
                            }
                        } else {
                            contentDescription = "${item.title}, missing"
                        }
                    })
                }
            })
        }

    private fun sectionTitle(text: String): TextView = TextView(requireNotNull(host).viewContext).apply {
        this.text = text
        textSize = 17f
        setTextColor(colors.primaryText)
        setPadding(0, dp(18), 0, dp(7))
    }

    private fun infoCard(title: String, subtitle: String): TextView =
        TextView(requireNotNull(host).viewContext).apply {
            text = if (subtitle.isBlank()) title else "$title\n$subtitle"
            textSize = 13f
            setTextColor(colors.primaryText)
            background = Styler.cardBackground(context, colors)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(6) }
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
        const val CHILD_CARD_DP = 100
        const val CHILD_POSTER_DP = 145f
        const val DESCRIPTION_COLLAPSED_LINES = 3
        const val DESCRIPTION_EXPANDED_DP = 118
        const val DESCRIPTION_SCROLL_STEP_DP = 44
        const val CONTINUE_COVER_WIDTH_DP = 68
        const val CONTINUE_COVER_HEIGHT_DP = 102
    }
}
