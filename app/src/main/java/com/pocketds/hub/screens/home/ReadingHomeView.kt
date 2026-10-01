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
import com.pocketds.hub.model.ReadingWork
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.reader.ReadingProgress
import com.pocketds.hub.reader.ReadingProgressPresentation
import com.pocketds.hub.reader.ReadingCompletionRepository
import com.pocketds.hub.screens.library.ReadingWorkScreen
import com.pocketds.hub.ui.ChoiceOverlay
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.PosterCardView
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.activateOnTap
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Books Home: what you are reading (one card per series), the next book of a
 * series you finished, comics apart, Want to Read, your own lists, then what
 * was added lately.
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
    private lateinit var greeting: TextView
    private val createButton: TextView
    private val profileButton: TextView
    var onChooseProfile: (() -> Unit)? = null
    private var job: Job? = null
    private var generation = 0
    private var rows: List<ReadingShelfRow> = emptyList()
    private var selectedRow = ""
    private var selectedWork = ""
    private var focusedListHeader: String? = null
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
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(5), dp(24), dp(3))
        }
        header.addView(TextView(context).apply {
            greeting = this
            text = HomeHeaderLabel.forUser(com.pocketds.hub.settings.HubSettings.userName(context))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            textSize = 23f
            setTextColor(colors.primaryText)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        profileButton = TextView(context).apply {
            text = "Choose profile"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(colors.accent)
            minimumHeight = dp(48)
            setPadding(dp(10), 0, dp(10), 0)
            background = Styler.chipBackground(context, colors)
            visibility = if (com.pocketds.hub.settings.HubSettings.userId(context).isBlank()) View.VISIBLE else View.GONE
            Styler.makeFocusable(this)
            FocusDecorator.attach(this, ringVisible, scale = false)
            activateOnTap { onChooseProfile?.invoke() }
        }
        header.addView(profileButton, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(8) })
        createButton = TextView(context).apply {
            text = "＋  New list"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(colors.primaryText)
            background = Styler.chipBackground(context, colors)
            minimumHeight = dp(48)
            setPadding(dp(14), 0, dp(14), 0)
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
        header.addView(createButton)
        column.addView(header)
        status = TextView(context).apply {
            textSize = 11f
            setTextColor(colors.mutedText)
            setPadding(dp(24), 0, dp(24), 0)
        }
        column.addView(status)
        scroll = FocusScrollView(context).apply {
            isFillViewport = true
            clipToPadding = false
            content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                setPadding(0, dp(4), 0, dp(28))
            }
            addView(content)
        }
        column.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        addView(overlay, LayoutParams(MATCH, MATCH))
        render(ReadingShelves.rows(emptyList(), ReadingListsRepository.get(context), emptyMap()))
    }

    fun setUserName(name:String?) {
        greeting.text = HomeHeaderLabel.forUser(name)
        profileButton.visibility = if (name.isNullOrBlank()) View.VISIBLE else View.GONE
    }

    fun onShow() {
        greeting.text = HomeHeaderLabel.forUser(com.pocketds.hub.settings.HubSettings.userName(context))
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
    } else listOf(
        ButtonHint.activate(if (profileButton.hasFocus()) "Choose profile" else if (createButton.hasFocus()) "Create list" else "Details"),
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
                if (profileButton.hasFocus()) { profileButton.performClick(); true }
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
        status.visibility = View.VISIBLE
        status.text = if (rows.any { it.items.isNotEmpty() }) "Refreshing reading progress…" else "Loading reading…"
        job = scope.launch {
            val libraries = when (val response = api.readingLibraries()) {
                is HubResult.Ok -> response.value.libraries
                is HubResult.Failed -> {
                    if (request == generation) status.text = "${response.message} · showing saved lists"
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
            recent = added.sortedByDescending { ReadingShelves.timestamp(it.addedAt) }.take(RECENT_LIMIT)
            render(ReadingShelves.rows(current, state, observed, next, recent))
            status.text = when {
                failures > 0 -> "Some reading progress is unavailable · showing saved items where possible"
                current.isEmpty() && ids.isEmpty() && state.lists.isEmpty() -> "Start a book in Library, or make a reading list."
                else -> ""
            }
            status.visibility = if (status.text.isBlank()) View.GONE else View.VISIBLE
            job = null
        }
    }

    private fun render(next: List<ReadingShelfRow>) {
        if (rows == next && content.childCount > 0) return
        val scrollY = scroll.scrollY
        val hadFocus = findFocus() != null
        rows = next
        cards.clear()
        headerActions.clear()
        content.removeAllViews()
        next.forEach { row -> content.addView(buildRow(row)) }
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
            setPadding(dp(24), dp(5), dp(24), 0)
        }
        val title = TextView(context).apply {
            text = when {
                row.id in ReadingShelves.BUILT_IN -> row.title
                else -> "${row.title}   ·   ${row.readCount}/${row.items.size} read"
            }
            textSize = 18f
            setTextColor(colors.primaryText)
            setPadding(0, dp(4), 0, dp(2))
        }
        header.addView(title, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (row.id !in ReadingShelves.BUILT_IN) {
            val manage = TextView(context).apply {
                text = "⋯"
                textSize = 22f
                gravity = Gravity.CENTER
                contentDescription = "Manage ${row.title}"
                background = Styler.chipBackground(context, colors)
                minimumWidth = dp(48)
                minimumHeight = dp(48)
                Styler.makeFocusable(this)
                FocusDecorator.attach(this, ringVisible, scale = false)
                activateOnTap { showListManagement(row) }
                FocusDecorator.listen(this, ringVisible) { view, focused ->
                    if (focused) { focusedListHeader = row.id; host.refreshHints() }
                }
            }
            headerActions[row.id] = manage
            header.addView(manage, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        addView(header)
        if (row.items.isEmpty()) {
            addView(TextView(context).apply {
                text = if (row.id == ReadingListsState.WANT_TO_READ) "Add a book from its details to keep it here."
                    else "Add a book from its details to start this list."
                textSize = 12f
                setTextColor(colors.mutedText)
                setPadding(dp(24), dp(8), dp(24), dp(14))
            })
            return@apply
        }
        val strip = FocusHorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            clipChildren = false
            setPadding(dp(16), dp(6), dp(16), dp(6))
        }
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
        }
        strip.addView(line)
        row.items.forEach { work ->
            val card = PosterCardView(context, colors, 150f).apply {
                layoutParams = LinearLayout.LayoutParams(dp(105), WRAP).apply {
                    setMargins(dp(8), dp(5), dp(8), dp(5))
                }
                bindReadingWork(work, loader, api::imageUrl)
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

    private fun shelves() = ReadingShelves.rows(current, ReadingListsRepository.get(context), observed, next, recent)

    private fun focusedWork(): ReadingWork? = rows.firstOrNull { it.id == selectedRow }
        ?.items?.firstOrNull { it.id == selectedWork }

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
    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val RECENT_LIMIT = 12
    }
}
