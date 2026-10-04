package com.pocketds.hub.screens.home

import com.pocketds.hub.ui.FocusHorizontalScrollView
import com.pocketds.hub.ui.FocusScrollView
import com.pocketds.hub.ui.Artwork
import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.ui.RowStep
import com.pocketds.hub.input.Direction
import com.pocketds.hub.model.ReadingWork
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.reader.ReadingProgress
import com.pocketds.hub.reader.ReadingProgressPresentation
import com.pocketds.hub.reader.ReadingCompletionRepository
import com.pocketds.hub.screens.library.ReadingWorkScreen
import com.pocketds.hub.ui.AppIcon
import com.pocketds.hub.ui.AppIconDrawable
import com.pocketds.hub.ui.ChoiceOverlay
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.PillButton
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.PosterCardView
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Theme
import com.pocketds.hub.ui.activateOnTap
import com.pocketds.hub.ui.showStatus
import com.pocketds.hub.ui.glass.GlassColors
import com.pocketds.hub.ui.glass.GlassPanelDrawable
import com.pocketds.hub.ui.glass.GlassProgressBar
import com.pocketds.hub.state.StatusMessage
import com.pocketds.hub.state.StatusText
import com.pocketds.hub.state.StatusTone
import com.pocketds.hub.ui.ProgressLine
import com.pocketds.hub.ui.ProgressLine.showFraction
import com.pocketds.hub.ui.ThemeGradientDrawable
import com.pocketds.hub.ui.Type
import com.pocketds.hub.ui.textWeight
import com.pocketds.hub.ui.typeRole
import com.pocketds.hub.screens.library.ReadingBookFacts
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Books Home: the book you are reading as a card with Resume reading, the
 * others you are reading beside it, your series as fans of their covers, then
 * the next book of a series you finished, comics apart, Want to Read, your own
 * lists and what was added lately.
 *
 * On Glass it is the prototype's (`.pg-bhome`): the book you are reading at
 * full cover size on the page, the others under it as glass rows with their
 * formats, the fans, and rows of glass covers with their captions; the page
 * takes the colours of the cover in focus.
 */
class ReadingHomeView(
    context: Context,
    private val api: HubApi,
    private val host: ScreenHost,
    private val colors: PocketColors,
    private val ringVisible: () -> Boolean
) : FrameLayout(context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val status: TextView
    private val content: LinearLayout
    private val scroll: ScrollView
    private val overlay = ChoiceOverlay(context, colors, ringVisible, sidePanel = true)
    private val loader = Artwork.loader(api, context)
    private val glass = Theme.onGlass(colors)
    private var continueView: ContinueReadingView? = null
    /** Buttons that act themselves on A, rather than opening the selected book's details. */
    private val selfActing = mutableSetOf<View>()
    private var seriesShelf: List<ReadingShelves.SeriesShelfItem> = emptyList()
    private var renderedSeries: List<ReadingShelves.SeriesShelfItem>? = null
    /** Full details of the book on the Continue reading card: its length, for "page 363 of 735". */
    private val heroDetails = mutableMapOf<String, ReadingWork>()
    private val createButton: TextView
    private val profileButton: TextView
    var onChooseProfile: (() -> Unit)? = null
    private var job: Job? = null
    private var generation = 0
    private var rows: List<ReadingShelfRow> = emptyList()
    private var selectedRow = ""
    private var selectedWork = ""
    private var focusedListHeader: String? = null
    /** The front cover of the series in focus under Your series, where no single book is selected. */
    private var focusedSeriesCover: String? = null
    private val cards = mutableMapOf<Pair<String, String>, View>()
    private val headerActions = mutableMapOf<String, View>()
    private var observed: Map<String, ReadingWork> = emptyMap()
    private var current: List<ReadingWork> = emptyList()
    private var next: List<ReadingWork> = emptyList()
    private var recent: List<ReadingWork> = emptyList()

    init {
        setBackgroundColor(colors.background)
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(column, LayoutParams(MATCH, MATCH))
        profileButton = TextView(context).apply {
            text = "Choose profile"
            if (glass) PillButton.control(this, colors, AppIcon.PERSON) else {
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(colors.accent)
                minimumHeight = dp(48)
                setPadding(dp(10), 0, dp(10), 0)
                background = Styler.chipBackground(context, colors)
            }
            visibility = if (com.pocketds.hub.settings.HubSettings.userId(context).isBlank()) View.VISIBLE else View.GONE
            Styler.makeFocusable(this)
            FocusDecorator.attach(this, ringVisible, scale = false)
            activateOnTap { onChooseProfile?.invoke() }
        }
        createButton = TextView(context).apply {
            if (glass) {
                text = "New list"
                PillButton.control(this, colors, AppIcon.ADD)
            } else {
                text = "＋  New list"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(colors.primaryText)
                background = Styler.chipBackground(context, colors)
                minimumHeight = dp(48)
                setPadding(dp(14), 0, dp(14), 0)
            }
            contentDescription = "New list"
            Styler.makeFocusable(this)
            FocusDecorator.attach(this, ringVisible, scale = false)
            FocusDecorator.listen(this, ringVisible) { view, focused ->
                if (focused) focusedListHeader = null
            }
            activateOnTap { promptName("New reading list", "") { name ->
                ReadingListsRepository.update(context) { it.create(name) }
                render(shelves())
            } }
        }
        status = TextView(context).apply {
            textSize = 11f
            setTextColor(colors.mutedText)
            // Glass: a chip at the top right, clear of the words beside the cover.
            if (glass) {
                gravity = Gravity.END
                maxWidth = dp(GLASS_STATUS_MAX_DP); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                visibility = View.GONE
            } else setPadding(dp(24), 0, dp(24), 0)
        }
        if (!glass) column.addView(status)
        // Room for a row's title above its focused cards; "Currently reading"
        // scrolled out of sight when its first card took focus.
        scroll = FocusScrollView(context, revealAbove = dp(36)).apply {
            isFillViewport = true
            clipToPadding = false
            content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                setPadding(0, dp(if (glass) GLASS_TOP_DP else 4), 0, dp(28))
            }
            addView(content)
        }
        column.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        if (glass) addView(status, LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(GLASS_TOP_DP); rightMargin = dp(GLASS_EDGE_DP)
        })
        addView(overlay, LayoutParams(MATCH, MATCH))
        render(ReadingShelves.rows(emptyList(), ReadingListsRepository.get(context), emptyMap()))
    }

    /**
     * The profile name the hub reported. Home's own settings may hold none (no
     * profile picked on this device, the hub's default is used), and re-reading
     * them on every show turned "Hello Dgdan" into "Hello" after opening a book.
     */
    private var reportedName: String? = null

    fun setUserName(name:String?) {
        reportedName = name
        profileButton.visibility = if (name.isNullOrBlank()) View.VISIBLE else View.GONE
    }

    fun onShow() {
        val state = ReadingListsRepository.get(context)
        val completion = ReadingCompletionRepository.get(context)
        render(ReadingShelves.rows(current.map(completion::project), state, observed.mapValues { completion.project(it.value) },
            next.map(completion::project), recent))
        load()
    }

    fun onHide() {
        generation++
        job?.cancel()
        job = null
        if (overlay.isOpen) overlay.dismiss()
    }

    fun destroy() { onHide(); scope.cancel() }

    fun hints(): List<ButtonHint> = if (overlay.isOpen) {
        listOf(ButtonHint.activate("Choose"), ButtonHint.back("Cancel"))
    } else if (findFocus() is SeriesStackView) {
        listOf(ButtonHint.activate("Open series"), ButtonHint.refresh())
    } else listOf(
        ButtonHint.activate(when {
            profileButton.hasFocus() -> "Choose profile"
            createButton.hasFocus() -> "Create list"
            continueView?.resume?.hasFocus() == true -> "Resume reading"
            else -> "Details"
        }),
        ButtonHint.secondary("List actions"),
        ButtonHint.refresh()
    )

    fun onPad(action: PadAction): Boolean {
        if (!hasFocus() && !overlay.isOpen) return false
        if (overlay.isOpen) {
            val handled = overlay.onPad(action)
            if (handled) host.refreshHints()
            return handled
        }
        return when (action) {
            PadAction.Activate -> {
                if (findFocus() in selfActing) false
                else if (profileButton.hasFocus()) { profileButton.performClick(); true }
                else if (createButton.hasFocus()) { createButton.performClick(); true }
                else if (focusedListHeader?.let { headerActions[it]?.hasFocus() } == true) false
                else focusedWork()?.let { host.push(ReadingWorkScreen(api, it.id, it.title, ringVisible)); true } ?: false
            }
            PadAction.Secondary -> {
                focusedListHeader?.takeIf { headerActions[it]?.hasFocus() == true }
                    ?.let { id -> rows.firstOrNull { it.id == id }?.let(::showListManagement) }
                    ?: showActions()
                true
            }
            PadAction.Refresh -> { load(); true }
            // Row by row, past an empty list and to rows scrolled out of sight.
            is PadAction.Step -> action.direction in setOf(Direction.UP, Direction.DOWN) &&
                RowStep.move(content, findFocus(), up = action.direction == Direction.UP)
            else -> false
        }
    }

    fun requestInitialFocus(): Boolean {
        focusedListHeader?.let { id -> headerActions[id]?.let { button ->
            button.post { button.requestFocus() }
            return true
        } }
        val row = rows.firstOrNull { it.id == selectedRow && it.items.any { item -> item.id == selectedWork } }
            ?: rows.firstOrNull { it.id == ReadingShelves.CURRENTLY_READING && it.items.isNotEmpty() }
            ?: rows.firstOrNull { it.id != ReadingListsState.WANT_TO_READ && it.items.isNotEmpty() }
            ?: rows.firstOrNull { it.items.isNotEmpty() }
        if (row == null) return createButton.requestFocus()
        val id = if (row.id == selectedRow && row.items.any { it.id == selectedWork }) selectedWork
            else row.items.getOrNull(row.nextIndex)?.id ?: row.items.first().id
        val card = cards[row.id to id] ?: return false
        card.post { card.requestFocus() }
        return true
    }

    private fun load() {
        generation++
        val request = generation
        job?.cancel()
        showLine(if (rows.any { it.items.isNotEmpty() }) StatusText.loading("reading progress", refreshing = true)
            else StatusText.loading("reading", refreshing = false))
        job = scope.launch {
            val libraries = when (val response = api.readingLibraries()) {
                is HubResult.Ok -> response.value.libraries.also(com.pocketds.hub.screens.library.ReadingLibraryNames::remember)
                is HubResult.Failed -> {
                    if (request == generation) showLine(StatusMessage("${response.message} · showing saved lists", StatusTone.WARNING))
                    job = null
                    return@launch
                }
            }
            val summaries = mutableListOf<ReadingWork>()
            var failures = 0
            for (library in libraries) {
                when (val response = api.readingLibraryItems(library.id, sort = "last_read", direction = "desc")) {
                    is HubResult.Ok -> summaries += response.value.items
                    is HubResult.Failed -> failures++
                }
                if (request != generation) return@launch
            }
            val added = mutableListOf<ReadingWork>()
            for (library in libraries.filter { it.kind == "book" && "sort:added" in it.capabilities }) {
                when (val response = api.readingLibraryItems(library.id, sort = "added", direction = "desc")) {
                    is HubResult.Ok -> added += response.value.items.take(RECENT_LIMIT)
                    is HubResult.Failed -> failures++
                }
                if (request != generation) return@launch
            }
            val candidates = mutableListOf<ReadingWork>()
            for (work in summaries) {
                if (work.entityType == "collection" && work.progress != null && work.progress.completed != true) {
                    // The library card is a series. Resolve its individual books before
                    // deciding what is in progress, including an unfinished first book.
                    when (val detail = api.readingWork(work.id)) {
                        is HubResult.Ok -> candidates += detail.value
                        is HubResult.Failed -> failures++
                    }
                } else if (work.entityType != "collection") candidates += work
                if (request != generation) return@launch
            }
            var state = ReadingListsRepository.get(context)
            val ids = (state.wantToRead + state.lists.flatMap { it.items }).map { it.workId }.distinct()
            val readerProgress = ReadingProgress.get(context)
            val pending = readerProgress.store.pending(readerProgress.session().identity)
                .filter { it.pending && !it.conflicted }
            val pendingIds = pending.sortedByDescending { it.updatedAt }.map { it.key.workId }.distinct()
            val fresh = mutableMapOf<String, ReadingWork>()
            ReadingShelves.current(candidates).forEach { fresh[it.id] = it }
            summaries.filter { it.entityType != "collection" }.forEach { fresh[it.id] = it }
            for (id in (ids + pendingIds).distinct().filter { it !in fresh || it in pendingIds }) {
                when (val response = api.readingWork(id)) {
                    is HubResult.Ok -> {
                        val forWork = pending.filter { it.key.workId == id }
                        val projected = ReadingProgressPresentation.project(response.value, forWork)
                        val localTime = forWork.maxOfOrNull { it.updatedAt }
                        fresh[id] = if (localTime != null && projected.progress != null) projected.copy(
                            progress = projected.progress.copy(updatedAt = Instant.ofEpochMilli(localTime).toString())
                        ) else projected
                    }
                    is HubResult.Failed -> failures++
                }
                if (request != generation) return@launch
            }
            if (request != generation) return@launch
            state = ReadingListsRepository.update(context) { previous ->
                ids.fold(previous) { next, id ->
                    fresh[id]?.progress?.let {
                        next.recordProgress(id, if (it.completed) 1.0 else it.percentage, at = ReadingShelves.timestamp(it.updatedAt))
                    } ?: next
                }
            }
            val completion = ReadingCompletionRepository.get(context)
            observed = fresh.mapValues { completion.project(it.value) }
            current = ReadingShelves.current((pendingIds.mapNotNull(fresh::get) + candidates).map(completion::project))
            next = ReadingShelves.nextInSeries(candidates).map(completion::project)
            seriesShelf = ReadingShelves.yourSeries(candidates)
            recent = added.sortedByDescending { ReadingShelves.timestamp(it.addedAt) }.take(RECENT_LIMIT)
            render(ReadingShelves.rows(current, state, observed, next, recent))
            showLine(when {
                failures > 0 -> StatusMessage("Some reading progress is unavailable · showing saved items where possible", StatusTone.WARNING)
                current.isEmpty() && ids.isEmpty() && state.lists.isEmpty() -> StatusText.notice("Start a book in Library, or make a reading list.")
                else -> StatusMessage("")
            })
            job = null
        }
    }

    /** The status line: on Glass only with news, as the chip; Classic shows every line with words. */
    private fun showLine(message: StatusMessage) {
        status.showStatus(message, colors)
        if (!glass) status.visibility = if (message.text.isBlank()) View.GONE else View.VISIBLE
    }

    private fun render(next: List<ReadingShelfRow>) {
        if (rows == next && renderedSeries == seriesShelf && content.childCount > 0) return
        val scrollY = scroll.scrollY
        val hadFocus = findFocus() != null
        rows = next
        renderedSeries = seriesShelf
        // The page's colours for every cover shown, before focus reaches one.
        host.prefetchArtwork((next.flatMap { row -> row.items.map { it.artwork } } +
            seriesShelf.mapNotNull { it.covers.firstOrNull() }).filter(String::isNotBlank).distinct())
        cards.clear()
        headerActions.clear()
        selfActing.clear()
        continueView = null
        content.removeAllViews()
        val reading = next.any { it.id == ReadingShelves.CURRENTLY_READING }
        next.forEachIndexed { i, row ->
            if (row.id == ReadingShelves.CURRENTLY_READING) content.addView(buildCurrent(row))
            else content.addView(buildRow(row))
            // Your series sits under the book you are reading, or first when nothing is.
            if (row.id == ReadingShelves.CURRENTLY_READING || (!reading && i == 0)) buildSeries()?.let(content::addView)
        }
        listOf(createButton, profileButton).forEach { (it.parent as? ViewGroup)?.removeView(it) }
        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            // Glass: the buttons' own ring room lies outside them, so the pill
            // lines up with the headings above.
            if (glass) setPadding(dp(GLASS_EDGE_DP) - ring(), dp(10), dp(GLASS_EDGE_DP), 0)
            else setPadding(dp(24), dp(14), dp(24), 0)
            addView(createButton)
            addView(profileButton, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(if (glass) 2 else 8) })
        })
        scroll.post {
            scroll.scrollTo(0, scrollY)
            if (hadFocus) requestInitialFocus()
        }
        if (isAttachedToWindow) host.refreshHints()
    }

    private fun buildRow(row: ReadingShelfRow): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (glass) setPadding(dp(GLASS_EDGE_DP), dp(4), dp(GLASS_EDGE_DP) - ring(), 0)
            else setPadding(dp(24), dp(5), dp(24), 0)
        }
        val title = if (glass) heading(row.title, if (row.id in ReadingShelves.BUILT_IN) null else "${row.readCount}/${row.items.size} read")
        else TextView(context).apply {
            text = when {
                row.id in ReadingShelves.BUILT_IN -> row.title
                else -> "${row.title}   ·   ${row.readCount}/${row.items.size} read"
            }
            typeRole(Type.Role.HEADING, 16f)
            setTextColor(colors.primaryText)
            setPadding(0, dp(10), 0, dp(2))
        }
        header.addView(title, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (row.id !in ReadingShelves.BUILT_IN) {
            val manage = TextView(context).apply {
                contentDescription = "Manage ${row.title}"
                if (glass) PillButton.control(this, colors, AppIcon.MORE, round = true) else {
                    text = "⋯"
                    textSize = 22f
                    gravity = Gravity.CENTER
                    background = Styler.chipBackground(context, colors)
                    minimumWidth = dp(48)
                    minimumHeight = dp(48)
                }
                Styler.makeFocusable(this)
                FocusDecorator.attach(this, ringVisible, scale = false)
                activateOnTap { showListManagement(row) }
                FocusDecorator.listen(this, ringVisible) { view, focused ->
                    if (focused) { focusedListHeader = row.id; host.refreshHints() }
                }
            }
            headerActions[row.id] = manage
            header.addView(manage, if (glass) LinearLayout.LayoutParams(WRAP, WRAP) else LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        addView(header)
        if (row.items.isEmpty()) {
            addView(TextView(context).apply {
                text = if (row.id == ReadingListsState.WANT_TO_READ) "Add a book from its details to keep it here."
                    else "Add a book from its details to start this list."
                textSize = 12f
                setTextColor(if (glass) GlassColors.QUIET else colors.mutedText)
                if (glass) setPadding(dp(GLASS_EDGE_DP), dp(6), dp(GLASS_EDGE_DP), dp(12))
                else setPadding(dp(24), dp(8), dp(24), dp(14))
            })
            return@apply
        }
        val strip = FocusHorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            clipChildren = false
            // Glass: the first cover lines up with the heading, 12dp between covers.
            if (glass) setPadding(dp(GLASS_EDGE_DP - GLASS_CARD_GAP_DP / 2), dp(4), dp(GLASS_EDGE_DP - GLASS_CARD_GAP_DP / 2), dp(6))
            else setPadding(dp(16), dp(6), dp(16), dp(6))
        }
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
        }
        strip.addView(line)
        row.items.forEach { work ->
            val card = PosterCardView(context, colors, if (glass) GLASS_POSTER_DP else 150f, glass = glass).apply {
                layoutParams = if (glass) LinearLayout.LayoutParams(dp(GLASS_POSTER_CARD_DP), WRAP).apply {
                    setMargins(dp(GLASS_CARD_GAP_DP / 2), dp(4), dp(GLASS_CARD_GAP_DP / 2), dp(4))
                } else LinearLayout.LayoutParams(dp(105), WRAP).apply {
                    setMargins(dp(8), dp(5), dp(8), dp(5))
                }
                bindReadingWork(work, loader, api::imageUrl, showKind = true)
                FocusDecorator.attach(this, ringVisible)
                activateOnTap { host.push(ReadingWorkScreen(api, work.id, work.title, ringVisible)) }
                FocusDecorator.listen(this, ringVisible) { view, focused ->
                    if (focused) {
                        focusedListHeader = null
                        selectedRow = row.id
                        selectedWork = work.id
                        host.refreshHints()
                    }
                }
            }
            cards[row.id to work.id] = card
            line.addView(card)
        }
        addView(strip)
        if (row.id !in ReadingShelves.BUILT_IN) {
            strip.post {
                val target = line.getChildAt(row.nextIndex) ?: return@post
                strip.scrollTo((target.left - dp(140)).coerceAtLeast(0), 0)
            }
        }
    }

    /**
     * The book read last as the Continue reading card, and every other book in
     * progress as a short list beside it, which scrolls when there are many.
     */
    private fun buildCurrent(row: ReadingShelfRow): View = LinearLayout(context).apply {
        orientation = if (glass) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        clipChildren = false
        clipToPadding = false
        if (glass) setPadding(dp(GLASS_EDGE_DP), 0, dp(GLASS_EDGE_DP), dp(2))
        else setPadding(dp(24), dp(10), dp(24), dp(4))
        val hero = row.items.first()
        val card = ContinueReadingView(context, colors, ringVisible).apply {
            bind(heroDetails[hero.id]?.let { it.copy(progress = hero.progress ?: it.progress) } ?: hero, loader, api::imageUrl)
            resume.activateOnTap { host.push(ReadingWorkScreen(api, hero.id, hero.title, ringVisible, openReader = true)) }
            details.activateOnTap { host.push(ReadingWorkScreen(api, hero.id, hero.title, ringVisible)) }
            listOf(resume, details).forEach { button ->
                FocusDecorator.listen(button, ringVisible) { _, focused ->
                    if (focused) { focusedListHeader = null; selectedRow = row.id; selectedWork = hero.id; host.refreshHints() }
                }
            }
        }
        continueView = card
        selfActing += listOf(card.resume, card.details)
        cards[row.id to hero.id] = card.resume
        addView(card, if (glass) LinearLayout.LayoutParams(MATCH, WRAP) else LinearLayout.LayoutParams(0, WRAP, 1f))
        if (hero.id !in heroDetails) loadHeroDetail(hero)
        val others = row.items.drop(1)
        if (others.isEmpty()) return@apply
        if (glass) {
            // "Also reading 2", then the others two to a line as glass rows.
            addView(heading("Also reading", others.size.toString()), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
            others.chunked(2).forEachIndexed { line, pair ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    clipChildren = false
                    pair.forEachIndexed { i, work ->
                        addView(alsoReading(row, work), LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                            if (i > 0) marginStart = dp(GLASS_ALSO_GAP_DP)
                        })
                    }
                    // An odd one out keeps a column's width rather than the whole line.
                    if (pair.size == 1) addView(View(context), LinearLayout.LayoutParams(0, 0, 1f).apply { marginStart = dp(GLASS_ALSO_GAP_DP) })
                }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(if (line == 0) 6 else GLASS_ALSO_GAP_DP) })
            }
            return@apply
        }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            addView(TextView(context).apply {
                text = "Also reading · ${others.size}"
                isAllCaps = true
                typeRole(Type.Role.EYEBROW)
                setTextColor(colors.mutedText)
                setPadding(dp(4), dp(4), 0, dp(8))
            })
            val list = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                setPadding(dp(4), dp(2), dp(4), dp(4))
            }
            others.forEach { work -> list.addView(alsoReading(row, work)) }
            addView(FocusScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                clipToPadding = false
                clipChildren = false
                addView(list)
            }, LinearLayout.LayoutParams(MATCH, if (others.size > 3) dp(204) else WRAP))
        }, LinearLayout.LayoutParams(dp(228), WRAP).apply { marginStart = dp(12) })
    }

    private fun alsoReading(row: ReadingShelfRow, work: ReadingWork): View = if (glass) glassAlsoReading(row, work) else LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(8), dp(10), dp(8))
        background = Styler.cardBackground(context, colors, cornerDp = 14f)
        contentDescription = listOfNotNull(work.title, work.cardSubtitle, ReadingBookFacts.progress(work)).joinToString(", ")
        addView(android.widget.ImageView(context).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            background = ThemeGradientDrawable.rounded(Styler.dp(context, 5f), colors.posterPlaceholder)
            clipToOutline = true
            Artwork.bind(this, loader, work.artwork.takeIf(String::isNotBlank)?.let(api::imageUrl), opaque = true)
        }, LinearLayout.LayoutParams(dp(40), dp(60)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
            addView(TextView(context).apply {
                text = work.title
                textSize = 12f
                textWeight(600)
                setTextColor(colors.primaryText)
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(TextView(context).apply {
                text = work.cardSubtitle
                textSize = 11f
                setTextColor(colors.mutedText)
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(ProgressLine.create(context, colors).apply {
                showFraction(work.progress?.percentage ?: 0.0)
                visibility = View.VISIBLE
            }, LinearLayout.LayoutParams(MATCH, dp(4)).apply { topMargin = dp(6) })
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        Styler.makeFocusable(this)
        FocusDecorator.attach(this, ringVisible, scale = false)
        FocusDecorator.listen(this, ringVisible) { _, focused ->
            if (focused) { focusedListHeader = null; selectedRow = row.id; selectedWork = work.id; host.refreshHints() }
        }
        activateOnTap { host.push(ReadingWorkScreen(api, work.id, work.title, ringVisible)) }
        cards[row.id to work.id] = this
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) }
    }

    /**
     * Glass "Also reading": the prototype's `.mini`, a glass row with the cover,
     * the title, "Blake Crouch · 3%" over a bar in the accent, and the book's
     * formats as glass chips when it has more than one.
     */
    private fun glassAlsoReading(row: ReadingShelfRow, work: ReadingWork): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        GlassPanelDrawable.attach(this, Styler.dp(context, GLASS_MINI_CORNER_DP))
        setPadding(dp(6), dp(6), dp(10), dp(6))
        val line = ReadingBookFacts.miniLine(work)
        contentDescription = listOf(work.title, line).filter(String::isNotBlank).joinToString(", ")
        addView(android.widget.ImageView(context).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            background = ThemeGradientDrawable.rounded(Styler.dp(context, 5f), colors.posterPlaceholder)
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            Artwork.bind(this, loader, work.artwork.takeIf(String::isNotBlank)?.let(api::imageUrl), opaque = true)
        }, LinearLayout.LayoutParams(dp(GLASS_MINI_THUMB_DP), dp(GLASS_MINI_THUMB_DP * 3 / 2)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = work.title
                textSize = 12.5f
                textWeight(700)
                setTextColor(colors.primaryText)
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            addView(TextView(context).apply {
                text = line
                textSize = 11f
                setTextColor(GlassColors.QUIET)
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) })
            addView(GlassProgressBar(context, colors.accent, GLASS_MINI_TRACK).apply {
                fraction = work.progress?.let { if (it.completed) 1.0 else it.percentage } ?: 0.0
            }, LinearLayout.LayoutParams(MATCH, dp(GlassProgressBar.HEIGHT_DP.toInt())).apply { topMargin = dp(5) })
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(10) })
        val formats = ReadingBookFacts.formats(work)
        if (formats.size > 1) addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            formats.forEachIndexed { i, format ->
                addView(android.widget.FrameLayout(context).apply {
                    GlassPanelDrawable.attach(this, Styler.dp(context, 999f))
                    addView(android.widget.ImageView(context).apply {
                        setImageDrawable(AppIconDrawable(formatIcon(format), android.graphics.Color.WHITE))
                    }, android.widget.FrameLayout.LayoutParams(dp(13), dp(13), Gravity.CENTER))
                }, LinearLayout.LayoutParams(dp(GLASS_FORMAT_W_DP), dp(GLASS_FORMAT_H_DP)).apply { if (i > 0) marginStart = dp(5) })
            }
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
        Styler.makeFocusable(this)
        FocusDecorator.attach(this, ringVisible, scale = false)
        FocusDecorator.listen(this, ringVisible) { _, focused ->
            if (focused) { focusedListHeader = null; selectedRow = row.id; selectedWork = work.id; host.refreshHints() }
        }
        activateOnTap { host.push(ReadingWorkScreen(api, work.id, work.title, ringVisible)) }
        cards[row.id to work.id] = this
    }

    private fun formatIcon(format: String) = when (format) {
        "audiobook" -> AppIcon.HEADPHONES
        "readaloud" -> AppIcon.READ_ALONG
        else -> AppIcon.BOOK
    }

    /** Glass: a row's heading with a quiet count after it ("Also reading 2"). */
    private fun heading(title: String, count: String?): TextView =
        com.pocketds.hub.ui.glass.GlassHeading.create(context, title, count).apply { setPadding(0, dp(4), 0, dp(2)) }

    /** "Your series": each series being read, as a fan of its covers. */
    private fun buildSeries(): View? {
        if (seriesShelf.isEmpty()) return null
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            addView(if (glass) heading("Your series", null).apply {
                setPadding(dp(GLASS_EDGE_DP), dp(10), dp(GLASS_EDGE_DP), dp(2))
            } else TextView(context).apply {
                text = "Your series"
                typeRole(Type.Role.HEADING, 16f)
                setTextColor(colors.primaryText)
                setPadding(dp(24), dp(14), dp(24), dp(2))
            })
            val line = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
            }
            seriesShelf.forEach { item ->
                line.addView(SeriesStackView(context, colors, ringVisible).apply {
                    bind(item, loader, api::imageUrl)
                    activateOnTap { host.push(ReadingWorkScreen(api, item.id, item.title, ringVisible)) }
                    // A series is not a book on a list: Y has nothing to act on here.
                    onFocused = {
                        focusedListHeader = null; selectedRow = ""; selectedWork = ""
                        focusedSeriesCover = item.covers.firstOrNull()
                        host.refreshHints()
                    }
                    selfActing += this
                }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(if (glass) GLASS_FAN_GAP_DP else 22) })
            }
            addView(FocusHorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                clipToPadding = false
                clipChildren = false
                if (glass) setPadding(dp(GLASS_EDGE_DP), dp(8), dp(GLASS_EDGE_DP), dp(6))
                else setPadding(dp(24), dp(10), dp(24), dp(6))
                addView(line)
            })
        }
    }

    /** The hero's own page carries its length; fetched once per book, then the card rebinds. */
    private fun loadHeroDetail(hero: ReadingWork) {
        scope.launch {
            val detail = (api.readingWork(hero.id) as? HubResult.Ok)?.value ?: return@launch
            heroDetails[hero.id] = detail
            continueView?.takeIf { it.work?.id == hero.id }
                ?.bind(detail.copy(progress = hero.progress ?: detail.progress), loader, api::imageUrl)
        }
    }

    private fun shelves() = ReadingShelves.rows(current, ReadingListsRepository.get(context), observed, next, recent)

    private fun focusedWork(): ReadingWork? = rows.firstOrNull { it.id == selectedRow }
        ?.items?.firstOrNull { it.id == selectedWork }

    /** The cover in focus, for the Glass page (Screen.pageArtwork); a list's own buttons keep the last one. */
    val pageArtwork: String?
        get() = focusedWork()?.artwork?.takeIf(String::isNotBlank) ?: focusedSeriesCover

    private fun showListManagement(row: ReadingShelfRow) {
        overlay.show(row.title, "Manage this reading list", listOf(
            ChoiceOverlay.Choice("browse", "Browse books to add"),
            ChoiceOverlay.Choice("rename", "Rename list"),
            ChoiceOverlay.Choice("delete", "Delete list", danger = true)
        ), onCancel = { host.refreshHints() }) { action ->
            when (action) {
                "browse" -> host.switchSection(2)
                "rename" -> promptName("Rename reading list", row.title) { name ->
                    ReadingListsRepository.update(context) { it.rename(row.id, name) }
                    render(shelves())
                }
                "delete" -> AlertDialog.Builder(context).setTitle("Delete ${row.title}?")
                    .setMessage("The books remain in your library.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                        ReadingListsRepository.update(context) { it.delete(row.id) }
                        focusedListHeader = null
                        render(shelves())
                    }.show()
            }
            host.refreshHints()
        }
        host.refreshHints()
    }

    private fun showActions() {
        val row = rows.firstOrNull { it.id == selectedRow } ?: return
        val work = focusedWork() ?: return
        val choices = buildList {
            if (row.id != ReadingListsState.WANT_TO_READ) add(ChoiceOverlay.Choice("want", "Add to Want to Read"))
            // Only Want to Read and the person's own lists hold what they put there.
            val ownList = row.id !in ReadingShelves.BUILT_IN
            if (ownList || row.id == ReadingListsState.WANT_TO_READ) add(ChoiceOverlay.Choice("remove", "Remove from ${row.title}"))
            if (ownList) {
                add(ChoiceOverlay.Choice("earlier", "Move earlier"))
                add(ChoiceOverlay.Choice("later", "Move later"))
                add(ChoiceOverlay.Choice("rename", "Rename list"))
                add(ChoiceOverlay.Choice("delete", "Delete list", danger = true))
            }
        }
        overlay.show("${row.title} · ${work.title}", "Reading list actions", choices,
            onCancel = { host.refreshHints() }) { action ->
            when (action) {
                "want" -> ReadingListsRepository.update(context) { it.add(ReadingListsState.WANT_TO_READ, ReadingListEntry.from(work)) }
                "remove" -> ReadingListsRepository.update(context) { it.remove(row.id, work.id) }
                "earlier", "later" -> ReadingListsRepository.update(context) {
                    it.move(row.id, work.id, if (action == "earlier") -1 else 1)
                }
                "rename" -> promptName("Rename reading list", row.title) { name ->
                    ReadingListsRepository.update(context) { it.rename(row.id, name) }
                    render(shelves())
                }
                "delete" -> AlertDialog.Builder(context).setTitle("Delete ${row.title}?")
                    .setMessage("The books remain in your library.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                        ReadingListsRepository.update(context) { it.delete(row.id) }
                        render(shelves())
                    }.show()
            }
            render(shelves())
        }
        host.refreshHints()
    }

    private fun promptName(title: String, existing: String, onName: (String) -> Unit) {
        val input = EditText(context).apply { setSingleLine(); setText(existing); selectAll(); hint = "List name" }
        AlertDialog.Builder(context).setTitle(title).setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) onName(name) else host.notify("Enter a list name")
            }.show()
    }

    private fun dp(value: Int) = Styler.dpInt(context, value.toFloat())
    private fun ring() = dp(PillButton.RING_DP.toInt())
    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val RECENT_LIMIT = 12
        /**
         * Glass, from the prototype's Pocket Books home: 8dp under the bar,
         * 22dp edges, 82 x 123dp covers 12dp apart, the "Also reading" rows two
         * to a line 8dp apart with 34dp covers and 13dp corners, and the fans
         * 20dp apart.
         */
        const val GLASS_TOP_DP = 8
        const val GLASS_EDGE_DP = 22
        const val GLASS_POSTER_CARD_DP = 82
        const val GLASS_POSTER_DP = 123f
        const val GLASS_CARD_GAP_DP = 12
        const val GLASS_ALSO_GAP_DP = 8
        const val GLASS_MINI_CORNER_DP = 13f
        const val GLASS_MINI_THUMB_DP = 34
        const val GLASS_FORMAT_W_DP = 28
        const val GLASS_FORMAT_H_DP = 22
        const val GLASS_FAN_GAP_DP = 20
        const val GLASS_STATUS_MAX_DP = 260
        /** The track under an "Also reading" bar: white at 18%. */
        const val GLASS_MINI_TRACK = 0x2EFFFFFF
    }
}
