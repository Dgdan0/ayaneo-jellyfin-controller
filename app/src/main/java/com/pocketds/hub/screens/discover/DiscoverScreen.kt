package com.pocketds.hub.screens.discover

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pocketds.hub.debug.DebugLog
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.DiscoverRow
import com.pocketds.hub.model.SearchHit
import com.pocketds.hub.model.ReadingDiscoverRow
import com.pocketds.hub.model.ReadingItem
import com.pocketds.hub.model.ReadingType
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.FailureKind
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.settings.ContentModeSettings
import com.pocketds.hub.state.ContentMode
import com.pocketds.hub.state.ContentModeMemory
import com.pocketds.hub.ui.ContentModeToggleView
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.FormOverlay
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.PosterCardView
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Theme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch

/**
 * Browse and search — the Infuse/Findroid shape.
 *
 * Opens with rows of posters rather than an empty search box. That is not
 * decoration: a blank screen with a text field is the least inviting thing an
 * app can show on a device with no keyboard attached, and "what's on" is a more
 * common question than "find me this exact title".
 *
 * All four rows come back in **one** hub request. Four sequential round trips
 * to build one screen is the difference between instant and sluggish over a
 * phone connection — the hub fans out to Jellyseerr concurrently instead, where
 * the hop is local. Measured: 99ms for all four.
 *
 * Rows page as you reach their end, so a row is effectively endless — upstream
 * reports 58,798 pages of popular films.
 */
class DiscoverScreen(
    private val api: HubApi,
    private val ringVisible: () -> Boolean
) : Screen {

    override val title: String = "Discover"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var colors: PocketColors
    private lateinit var searchBox: EditText
    private lateinit var modeToggle: ContentModeToggleView
    private lateinit var readingFilters: HorizontalScrollView
    private val readingFilterButtons = mutableMapOf<String, TextView>()
    private lateinit var statusLine: TextView
    private lateinit var rowsList: RecyclerView
    private lateinit var resultsGrid: RecyclerView
    private lateinit var readingRowsList: RecyclerView
    private lateinit var readingResultsGrid: RecyclerView
    private val rowsAdapter = RowsAdapter()
    private val resultsAdapter = HitAdapter()
    private val readingRowsAdapter = ReadingRowsAdapter()
    private val readingResultsAdapter = ReadingHitAdapter()
    private lateinit var form: FormOverlay
    private lateinit var flow: RequestFlow

    private var host: ScreenHost? = null
    private data class ModeState(
        var query: String = "",
        var searching: Boolean = false,
        var focusedKey: String = ""
    )
    private val modeStates = ContentModeMemory<ModeState>().apply {
        remember(ContentMode.MEDIA, ModeState())
        remember(ContentMode.BOOKS, ModeState())
    }
    private var mode = ContentMode.MEDIA
    private var readingType = ReadingType.ALL
    private val readingRowsByType = mutableMapOf<String, List<ReadingDiscoverRow>>()
    private val activeState: ModeState get() = checkNotNull(modeStates.recall(mode))
    private var lastQuery: String
        get() = activeState.query
        set(value) { activeState.query = value }
    private var searching: Boolean
        get() = activeState.searching
        set(value) { activeState.searching = value }
    private var rowsJob: Job? = null
    private var readingRowsJob: Job? = null

    /**
     * Set while waiting for content to arrive so the first card can be focused
     * the moment it exists.
     *
     * A plain `post { getChildAt(0)?.requestFocus() }` after the data lands is
     * not enough: post runs before the RecyclerView has laid out its children,
     * so there is nothing to focus and the selection stays wherever it was --
     * on the tab bar. Every directional press is then correctly refused by the
     * focus guard, which reads exactly like the pad being dead. Measured: 20
     * right-presses, 20 refusals, focus never once on a card.
     */
    private var focusTarget: RecyclerView? = null

    /** Row id -> job, so two flings at one row do not both fetch its next page. */
    private val rowLoads = mutableMapOf<String, Job>()
    private val readingRowLoads = mutableMapOf<String, Job>()

    /**
     * Highest page already asked for, per row.
     *
     * The job map alone is not enough. A cached page comes back in 7ms, so the
     * job is finished before the next scroll event arrives, while the row object
     * the strip is holding has not been re-bound yet -- and page 2 gets fetched
     * twice. Measured exactly that, 48ms apart.
     */
    private val requestedPages = mutableMapOf<String, Int>()
    private val readingRequestedPages = mutableMapOf<String, Int>()

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        val context = host.viewContext
        colors = Theme.colors(context)
        mode = ContentModeSettings.get(context)

        val frame = android.widget.FrameLayout(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.background)
        }
        frame.addView(root, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))

        modeToggle = ContentModeToggleView(context, colors).apply {
            select(mode)
            onModeSelected = ::switchMode
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                val horizontal = Styler.dpInt(context, 10f)
                setMargins(horizontal, Styler.dpInt(context, 5f), horizontal, 0)
            }
        }
        root.addView(modeToggle)

        readingFilters = HorizontalScrollView(context).apply {
            isFocusable = false
            isHorizontalScrollBarEnabled = false
            visibility = if (mode == ContentMode.BOOKS) View.VISIBLE else View.GONE
            addView(buildReadingFilters())
        }
        root.addView(readingFilters, LinearLayout.LayoutParams(MATCH, WRAP))

        searchBox = EditText(context).apply {
            hint = "Search"
            textSize = 14f
            setTextColor(colors.primaryText)
            setHintTextColor(colors.mutedText)
            background = Styler.chipBackground(context, colors)
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            val h = Styler.dpInt(context, 12f)
            val v = Styler.dpInt(context, 6f)
            setPadding(h, v, h, v)
            // Focusing this opens the IME, which on this device is the sibling
            // keyboard project's panel on the bottom screen.
            Styler.makeFocusable(this)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    runSearch(text.toString())
                    true
                } else {
                    false
                }
            }
            // The editor action only fires when the IME sends one. A hardware
            // ENTER, or a keyboard that sends a plain key code, has to be caught
            // separately or typing a query appears to do nothing.
            setOnKeyListener { _, keyCode, event ->
                if (event.action == android.view.KeyEvent.ACTION_UP &&
                    (keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                        keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)
                ) {
                    runSearch(text.toString())
                    true
                } else {
                    false
                }
            }
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                val m = Styler.dpInt(context, 10f)
                setMargins(m, Styler.dpInt(context, 6f), m, 0)
            }
        }
        root.addView(searchBox)

        statusLine = TextView(context).apply {
            textSize = 11f
            setTextColor(colors.mutedText)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                Styler.dpInt(context, 12f), Styler.dpInt(context, 3f),
                Styler.dpInt(context, 12f), Styler.dpInt(context, 3f)
            )
        }
        root.addView(statusLine)

        rowsList = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = rowsAdapter
            // A row's focused card is scaled up and its ring must not be clipped
            // by the row above.
            clipToPadding = false
            clipChildren = false
            setItemViewCacheSize(6)
            // Top padding the height of a row label, with clipToPadding off.
            //
            // This is what keeps "Trending now" on screen, and it is the only
            // approach that also scrolls *smoothly*. RecyclerView brings a
            // focused card inside the **padded** bounds, so a label sitting
            // directly above that card lands in the padding band -- which is
            // still drawn, because clipToPadding is false.
            //
            // The two overrides tried before this were both worse.
            // requestChildRectangleOnScreen is never consulted on the focus
            // path at all, so expanding its rectangle did nothing. Correcting
            // the position in requestChildFocus did work, but only after
            // RecyclerView had already scrolled: two scrolls per press, the
            // second an instant jump, which is the stutter you noticed.
            setPadding(0, Styler.dpInt(context, 28f), 0, Styler.dpInt(context, 84f))
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            addOnChildAttachStateChangeListener(claimFocusOnFirstChild(this))
        }
        root.addView(rowsList)

        resultsGrid = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, SEARCH_COLUMNS)
            adapter = resultsAdapter
            setHasFixedSize(true)
            // Recycling the focused view loses focus to the void, and the next
            // directional press then does nothing.
            setItemViewCacheSize(SEARCH_COLUMNS * 3)
            clipToPadding = false
            clipChildren = false
            visibility = View.GONE
            setPadding(
                Styler.dpInt(context, 6f), 0,
                Styler.dpInt(context, 6f), Styler.dpInt(context, 84f)
            )
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
                    maybeLoadMoreResults()
                }
            })
            addOnChildAttachStateChangeListener(claimFocusOnFirstChild(this))
        }
        root.addView(resultsGrid)

        readingRowsList = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = readingRowsAdapter
            clipToPadding = false
            clipChildren = false
            setItemViewCacheSize(6)
            setPadding(0, Styler.dpInt(context, 28f), 0, Styler.dpInt(context, 84f))
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            visibility = View.GONE
            addOnChildAttachStateChangeListener(claimFocusOnFirstChild(this))
        }
        root.addView(readingRowsList)

        readingResultsGrid = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, SEARCH_COLUMNS)
            adapter = readingResultsAdapter
            setHasFixedSize(true)
            setItemViewCacheSize(SEARCH_COLUMNS * 3)
            clipToPadding = false
            clipChildren = false
            visibility = View.GONE
            setPadding(
                Styler.dpInt(context, 6f), 0,
                Styler.dpInt(context, 6f), Styler.dpInt(context, 84f)
            )
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            addOnChildAttachStateChangeListener(claimFocusOnFirstChild(this))
        }
        root.addView(readingResultsGrid)

        form = FormOverlay(context, colors, ringVisible)
        frame.addView(form, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))

        flow = RequestFlow(
            api = api,
            scope = scope,
            overlay = { form },
            onStatus = { text, isError ->
                statusLine.setTextColor(if (isError) colors.dangerText else colors.mutedText)
                statusLine.text = text
            },
            onNotify = { host.notify(it) },
            onHintsChanged = { host.refreshHints() }
        )

        applyModeVisibility()

        return frame
    }

    private fun buildReadingFilters(): View {
        val bar = LinearLayout(host?.viewContext ?: throw IllegalStateException("host missing")).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(
                Styler.dpInt(context, 10f), Styler.dpInt(context, 4f),
                Styler.dpInt(context, 10f), 0
            )
        }
        ReadingType.filters.forEach { (wire, label) ->
            val chip = TextView(bar.context).apply {
                text = label
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(colors.primaryText)
                val horizontal = Styler.dpInt(context, 11f)
                val vertical = Styler.dpInt(context, 5f)
                setPadding(horizontal, vertical, horizontal, vertical)
                contentDescription = "Show $label"
                Styler.makeFocusable(this)
                isClickable = true
                setOnClickListener { selectReadingType(wire) }
            }
            readingFilterButtons[wire] = chip
            bar.addView(chip, LinearLayout.LayoutParams(WRAP, WRAP).apply {
                marginEnd = Styler.dpInt(bar.context, 5f)
            })
        }
        updateReadingFilterStyles()
        return bar
    }

    private fun updateReadingFilterStyles() {
        readingFilterButtons.forEach { (wire, button) ->
            val selected = wire == readingType
            button.background = Styler.chipBackground(button.context, colors, selected)
            button.setTextColor(if (selected) colors.accentText else colors.primaryText)
        }
    }

    private fun selectReadingType(type: String) {
        if (type == readingType) return
        readingType = type
        activeState.focusedKey = ""
        updateReadingFilterStyles()
        readingRowLoads.values.forEach(Job::cancel)
        readingRowLoads.clear()
        readingRequestedPages.clear()
        if (searching) {
            runSearch(lastQuery, force = true)
        } else {
            val cached = readingRowsByType[type]
            if (cached != null) {
                readingRowsAdapter.submit(cached)
                applyModeVisibility()
                focusTarget = readingRowsList
                readingRowsList.scrollToPosition(0)
            } else {
                loadReadingRows(force = true)
            }
        }
    }

    private fun switchMode(next: ContentMode) {
        if (mode == next) return
        mode = next
        ContentModeSettings.set(host?.viewContext ?: return, mode)
        modeToggle.select(mode)
        searchBox.setText(lastQuery)
        readingFilters.visibility = if (mode == ContentMode.BOOKS) View.VISIBLE else View.GONE
        applyModeVisibility()
        statusLine.setTextColor(colors.mutedText)
        if (searching) {
            val count = if (mode == ContentMode.MEDIA) resultsAdapter.itemCount else readingResultsAdapter.itemCount
            statusLine.text = "$count results"
            if (count == 0 && lastQuery.isNotBlank()) runSearch(lastQuery, force = true)
        } else if (mode == ContentMode.MEDIA) {
            statusLine.text = "${rowsAdapter.itemCount} rows"
            if (rowsAdapter.itemCount == 0) loadRows()
        } else {
            statusLine.text = "${readingRowsAdapter.itemCount} rows"
            if (readingRowsAdapter.itemCount == 0) loadReadingRows()
        }
        activeList().post {
            restoreContentFocus()
            host?.refreshHints()
        }
    }

    private fun applyModeVisibility() {
        rowsList.visibility = if (mode == ContentMode.MEDIA && !searching) View.VISIBLE else View.GONE
        resultsGrid.visibility = if (mode == ContentMode.MEDIA && searching) View.VISIBLE else View.GONE
        readingRowsList.visibility = if (mode == ContentMode.BOOKS && !searching) View.VISIBLE else View.GONE
        readingResultsGrid.visibility = if (mode == ContentMode.BOOKS && searching) View.VISIBLE else View.GONE
        if (::readingFilters.isInitialized) {
            readingFilters.visibility = if (mode == ContentMode.BOOKS) View.VISIBLE else View.GONE
        }
    }

    private fun activeList(): RecyclerView = when {
        mode == ContentMode.MEDIA && searching -> resultsGrid
        mode == ContentMode.MEDIA -> rowsList
        searching -> readingResultsGrid
        else -> readingRowsList
    }

    private fun restoreContentFocus(): Boolean {
        val key = activeState.focusedKey
        if (key.isNotEmpty()) {
            findContentKey(activeList(), key)?.let { if (it.requestFocus()) return true }
        }
        return activeList().getChildAt(0)?.requestFocus() == true
    }

    private fun findContentKey(root: ViewGroup, key: String): View? {
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            val media = child.getTag(TAG_HIT) as? SearchHit
            if (media?.media?.key == key) return child
            val reading = child.getTag(TAG_READING_ITEM) as? ReadingItem
            if (reading?.key == key) return child
            if (child is ViewGroup) findContentKey(child, key)?.let { return it }
        }
        return null
    }

    /**
     * Load on first show -- and on any later show that finds nothing loaded.
     *
     * The condition is "do I have data", not "have I ever tried". A once-only
     * flag left this screen permanently empty: the activity is paused and
     * resumed once during startup on this device, onHide cancels the in-flight
     * request, and the flag then blocked the retry. The screen sat on "Loading…"
     * forever with no error, because nothing had actually failed.
     */
    /**
     * Takes focus as soon as a list has something to focus.
     *
     * Attached rather than posted, because attachment is the event that
     * actually guarantees a child exists.
     */
    private fun claimFocusOnFirstChild(owner: RecyclerView) =
        object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                if (focusTarget !== owner || owner.visibility != View.VISIBLE) return
                focusTarget = null
                view.post {
                    view.requestFocus()
                    host?.refreshHints()
                }
            }

            override fun onChildViewDetachedFromWindow(view: View) = Unit
        }

    override fun onShow() {
        val stored = host?.viewContext?.let(ContentModeSettings::get) ?: mode
        if (stored != mode) switchMode(stored)
        if (searching) {
            val empty = if (mode == ContentMode.MEDIA) resultsAdapter.itemCount == 0
            else readingResultsAdapter.itemCount == 0
            if (empty && lastQuery.isNotBlank()) runSearch(lastQuery, force = true)
            return
        }
        if (mode == ContentMode.MEDIA) {
            if (rowsAdapter.itemCount == 0 && rowsJob?.isActive != true) loadRows()
        } else if (readingRowsAdapter.itemCount == 0 && readingRowsJob?.isActive != true) {
            loadReadingRows()
        }
    }

    override fun onHide() {
        if (form.isOpen) {
            form.dismiss()
            host?.refreshHints()
        }
        // Backing out must kill the in-flight search and every poster load it
        // started. This is the reason coroutines are in this project at all.
        scope.coroutineContext.cancelChildren()
        rowLoads.clear()
        requestedPages.clear()
        readingRowLoads.clear()
        readingRequestedPages.clear()
        focusTarget = null
    }

    override fun onDestroyView() {
        scope.cancel()
        host = null
    }

    override fun hints(): List<ButtonHint> {
        if (form.isOpen) {
            return listOf(ButtonHint.activate("Change"), ButtonHint.back("Cancel"))
        }
        val hints = mutableListOf<ButtonHint>()
        hints.add(
            ButtonHint.activate(
                if (::searchBox.isInitialized && searchBox.hasFocus()) "Search" else "Open"
            )
        )
        if (mode == ContentMode.BOOKS) {
            hints.add(ButtonHint.secondary("Search box"))
            if (searching) hints.add(ButtonHint.back("Browse"))
            return hints
        }
        val hit = focusedHit()
        // Ⓧ opens the request *form* on the card you are looking at. It used to
        // open the detail screen, so requesting took two presses and a second
        // menu -- which is not what "request" reads as.
        if (hit != null && !hit.canRequest) {
            hints.add(ButtonHint.primary(if (hit.canPlay) "In library" else "—").copy(enabled = false))
        } else {
            hints.add(ButtonHint.primary(if (flow.busy) "Requesting…" else "Request"))
        }
        // Ⓨ is PadAction.Secondary. It was wired to Refresh, which is the Select
        // button -- so the chip said Ⓨ and the Y button did nothing.
        hints.add(ButtonHint.secondary("Search box"))
        if (searching) hints.add(ButtonHint.back("Browse"))
        return hints
    }

    /**
     * Rows are confined; the search grid wraps.
     *
     * The two halves of this screen genuinely want different behaviour, which
     * is why this is a property rather than a constant somewhere.
     */
    override val horizontalMode: com.pocketds.hub.input.HorizontalMode
        get() = if (searching) {
            com.pocketds.hub.input.HorizontalMode.GRID
        } else {
            com.pocketds.hub.input.HorizontalMode.CONFINED
        }

    override fun requestInitialFocus(): Boolean {
        val list = activeList()
        return list.getChildAt(0)?.requestFocus() == true
    }

    override fun onPad(action: PadAction): Boolean = when {
        form.onPad(action) -> {
            host?.refreshHints()
            true
        }
        // A on the search box submits. Without this, Activate calls performClick
        // on an EditText, which does nothing visible and looks like a dead button.
        action == PadAction.Activate && searchBox.hasFocus() -> {
            runSearch(searchBox.text.toString())
            true
        }
        action == PadAction.Secondary -> {
            searchBox.requestFocus()
            host?.refreshHints()
            true
        }
        // X opens the request form for the card under the cursor. Not a
        // one-press request -- that would be an irreversible action sitting
        // under a thumbstick -- but not a detour through the detail screen
        // either, which is what it used to be.
        action == PadAction.Primary -> {
            if (mode == ContentMode.BOOKS) {
                false
            } else {
                val hit = focusedHit()
                when {
                    hit == null -> false
                    hit.canRequest -> {
                        flow.start(hit.media.key, hit.media.title)
                        true
                    }
                    else -> {
                        host?.notify(hit.media.title + " is already in your library")
                        true
                    }
                }
            }
        }
        // Back leaves search results and returns to browsing, rather than
        // popping the whole section.
        action == PadAction.Back && searching -> {
            showBrowse()
            true
        }
        action == PadAction.Refresh -> {
            if (searching) {
                runSearch(lastQuery, force = true)
            } else if (mode == ContentMode.MEDIA) {
                loadRows(force = true)
            } else {
                loadReadingRows(force = true)
            }
            true
        }
        else -> false
    }

    /** The hit the selection is on, so X can act without opening anything. */
    private fun focusedHit(): SearchHit? {
        if (mode != ContentMode.MEDIA) return null
        val focused = activeList().findFocus() ?: return null
        return focused.getTag(TAG_HIT) as? SearchHit
    }

    private fun openDetail(hit: SearchHit) {
        host?.push(MediaDetailScreen(api, hit.media.key, hit.media.title, ringVisible))
    }

    private fun openReadingDetail(item: ReadingItem) {
        host?.push(ReadingDetailScreen(api, item))
    }

    // ---- browse ------------------------------------------------------------

    private fun loadRows(force: Boolean = false) {
        statusLine.setTextColor(colors.mutedText)
        statusLine.text = "Loading…"
        if (force) rowsAdapter.submit(emptyList())
        focusTarget = rowsList
        rowsJob?.cancel()
        rowsJob = scope.launch {
            when (val result = api.discover()) {
                is HubResult.Ok -> {
                    val body = result.value
                    rowsAdapter.submit(body.rows)
                    if (mode == ContentMode.MEDIA && !searching) {
                        statusLine.text = buildString {
                            append(body.rows.size).append(" rows")
                            if (body.cache.hit && body.cache.ageSeconds > 0) {
                                append(" · cached ").append(body.cache.ageSeconds).append("s ago")
                            }
                            if (body.partial.isNotEmpty()) {
                                append(" · ").append(body.partial.joinToString(", ") { it.message })
                            }
                        }
                        host?.refreshHints()
                    }
                    // Focus is claimed by claimFocusOnFirstChild once a row is
                    // actually attached; asking here would be too early.
                }
                is HubResult.Failed -> if (mode == ContentMode.MEDIA && !searching) {
                    showFailure(result.kind, result.message)
                }
            }
        }
    }

    /**
     * Fetch the next page of one row.
     *
     * Keyed per row and guarded by an active job, because focus sweeping right
     * fires this on every step and four concurrent fetches of page 2 would all
     * append the same twenty titles.
     */
    private fun loadMoreRow(row: DiscoverRow) {
        if (!row.hasMore) return
        if (rowLoads[row.id]?.isActive == true) return
        val next = row.page + 1
        if (next <= (requestedPages[row.id] ?: 0)) return
        requestedPages[row.id] = next
        rowLoads[row.id] = scope.launch {
            DebugLog.log("net", "discover ${row.id} page $next")
            when (val result = api.discoverRow(row.id, next)) {
                is HubResult.Ok -> {
                    val fetched = result.value.rows.firstOrNull() ?: return@launch
                    rowsAdapter.append(row.id, fetched)
                }
                is HubResult.Failed -> {
                    // Let it be retried: a failed page must not permanently cap
                    // how far this row can scroll.
                    requestedPages[row.id] = next - 1
                    DebugLog.log("net", "discover ${row.id} page $next failed: ${result.message}")
                }
            }
        }
    }

    private fun loadReadingRows(force: Boolean = false) {
        val requestedType = readingType
        statusLine.setTextColor(colors.mutedText)
        statusLine.text = "Loading ${ReadingType.label(requestedType).lowercase()}…"
        if (force) readingRowsAdapter.submit(emptyList())
        focusTarget = readingRowsList
        readingRowsJob?.cancel()
        readingRowsJob = scope.launch {
            when (val result = api.readingDiscover(requestedType)) {
                is HubResult.Ok -> {
                    if (requestedType != readingType) return@launch
                    val body = result.value
                    readingRowsByType[requestedType] = body.rows
                    readingRowsAdapter.submit(body.rows)
                    if (mode == ContentMode.BOOKS && !searching) {
                        statusLine.text = buildString {
                            append(body.rows.size).append(" rows")
                            if (body.cache.hit && body.cache.ageSeconds > 0) {
                                append(" · cached ").append(body.cache.ageSeconds).append("s ago")
                            }
                            if (body.partial.isNotEmpty()) {
                                append(" · ").append(body.partial.joinToString(", ") { it.message })
                            }
                        }
                        host?.refreshHints()
                    }
                }
                is HubResult.Failed -> if (mode == ContentMode.BOOKS && !searching && requestedType == readingType) {
                    showFailure(result.kind, result.message)
                }
            }
        }
    }

    private fun loadMoreReadingRow(row: ReadingDiscoverRow) {
        if (!row.hasMore) return
        val loadKey = row.contentType + ":" + row.id
        if (readingRowLoads[loadKey]?.isActive == true) return
        val next = row.page + 1
        if (next <= (readingRequestedPages[loadKey] ?: 0)) return
        readingRequestedPages[loadKey] = next
        readingRowLoads[loadKey] = scope.launch {
            when (val result = api.readingDiscoverRow(row.id, row.contentType, next)) {
                is HubResult.Ok -> {
                    val fetched = result.value.rows.firstOrNull() ?: return@launch
                    readingRowsAdapter.append(loadKey, fetched)
                    readingRowsByType[readingType] = readingRowsAdapter.snapshot()
                }
                is HubResult.Failed -> {
                    readingRequestedPages[loadKey] = next - 1
                    DebugLog.log("net", "reading discover $loadKey page $next failed: ${result.message}")
                }
            }
        }
    }

    private fun showBrowse() {
        searching = false
        lastQuery = ""
        searchBox.setText("")
        applyModeVisibility()
        val count = if (mode == ContentMode.MEDIA) rowsAdapter.itemCount else readingRowsAdapter.itemCount
        statusLine.text = "$count rows"
        // Children are already attached here, so this one can focus directly.
        activeList().post {
            activeList().getChildAt(0)?.requestFocus()
            host?.refreshHints()
        }
    }

    // ---- search ------------------------------------------------------------

    private fun runSearch(query: String, force: Boolean = false) {
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            statusLine.text = "Type at least two characters."
            return
        }
        if (trimmed == lastQuery && !force && searching) return
        val requestedMode = mode
        val requestedType = readingType
        lastQuery = trimmed
        searching = true
        searchPage = 1
        searchTotalPages = 1
        applyModeVisibility()
        statusLine.setTextColor(colors.mutedText)
        statusLine.text = "Searching…"
        host?.refreshHints()
        // Supersede whatever was in flight; the old query's results are no
        // longer what anyone is looking at.
        scope.coroutineContext.cancelChildren()
        rowLoads.clear()
        requestedPages.clear()
        focusTarget = if (requestedMode == ContentMode.MEDIA) resultsGrid else readingResultsGrid
        scope.launch {
            DebugLog.log("net", "search \"$trimmed\"")
            if (requestedMode == ContentMode.BOOKS) {
                when (val result = api.readingSearch(trimmed, requestedType)) {
                    is HubResult.Ok -> {
                        if (requestedType != readingType || modeStates.recall(ContentMode.BOOKS)?.query != trimmed) {
                            return@launch
                        }
                        val body = result.value
                        readingResultsAdapter.submit(body.results)
                        if (mode == ContentMode.BOOKS) {
                            statusLine.text = buildString {
                                append(body.results.size).append(" results")
                                if (body.cache.hit) append(" · cached")
                                if (body.partial.isNotEmpty()) append(" · some sources unavailable")
                            }
                            host?.refreshHints()
                        }
                    }
                    is HubResult.Failed -> if (mode == ContentMode.BOOKS && modeStates.recall(ContentMode.BOOKS)?.query == trimmed) {
                        showFailure(result.kind, result.message)
                    }
                }
                return@launch
            }
            when (val result = api.search(trimmed)) {
                is HubResult.Ok -> {
                    if (modeStates.recall(ContentMode.MEDIA)?.query != trimmed) return@launch
                    val body = result.value
                    searchPage = body.page
                    searchTotalPages = body.totalPages
                    resultsAdapter.submit(body.results)
                    if (mode == ContentMode.MEDIA) {
                        statusLine.text = buildString {
                            append(body.totalResults).append(" results")
                            if (body.cache.hit) {
                                append(" · cached")
                                if (body.cache.ageSeconds > 0) {
                                    append(" ").append(body.cache.ageSeconds).append("s ago")
                                }
                            }
                            if (body.cache.degraded) append(" · hub degraded")
                        }
                        host?.refreshHints()
                    }
                }
                is HubResult.Failed -> if (mode == ContentMode.MEDIA && modeStates.recall(ContentMode.MEDIA)?.query == trimmed) {
                    showFailure(result.kind, result.message)
                }
            }
        }
    }

    private var searchPage = 1
    private var searchTotalPages = 1
    private var loadingMoreResults = false

    private fun maybeLoadMoreResults() {
        if (mode != ContentMode.MEDIA || !searching || loadingMoreResults || searchPage >= searchTotalPages) return
        val manager = resultsGrid.layoutManager as? GridLayoutManager ?: return
        val last = manager.findLastVisibleItemPosition()
        if (last < resultsAdapter.itemCount - SEARCH_COLUMNS * 2) return
        loadingMoreResults = true
        val query = modeStates.recall(ContentMode.MEDIA)?.query.orEmpty()
        scope.launch {
            try {
                val next = searchPage + 1
                when (val result = api.search(query, next)) {
                    is HubResult.Ok -> {
                        if (modeStates.recall(ContentMode.MEDIA)?.query != query) return@launch
                        searchPage = result.value.page
                        resultsAdapter.append(result.value.results)
                    }
                    is HubResult.Failed ->
                        DebugLog.log("net", "search page $next failed: ${result.message}")
                }
            } finally {
                loadingMoreResults = false
            }
        }
    }

    private fun showFailure(kind: FailureKind, message: String) {
        statusLine.setTextColor(colors.dangerText)
        statusLine.text = message
        DebugLog.log("net", "failed: $kind — $message")
    }

    // ---- adapters ----------------------------------------------------------

    private fun newCard(parent: ViewGroup, posterHeight: Float, width: Int): PosterCardView =
        PosterCardView(parent.context, colors, posterHeight).apply {
            layoutParams = RecyclerView.LayoutParams(width, WRAP).apply {
                val m = Styler.dpInt(parent.context, 8f)
                setMargins(m, m, m, m)
            }
            FocusDecorator.attach(this, ringVisible)
        }

    private fun bindCard(card: PosterCardView, hit: SearchHit) {
        val client = api as? HubClient
        card.bind(
            hit,
            client?.imageLoader ?: coil.ImageLoader(card.context)
        ) { path -> api.imageUrl(path) }
        card.setTag(TAG_HIT, hit)
        card.setOnClickListener { openDetail(hit) }
        card.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                modeStates.recall(ContentMode.MEDIA)?.focusedKey = hit.media.key
                host?.refreshHints()
            }
        }
    }

    private fun bindReadingCard(card: PosterCardView, item: ReadingItem) {
        val client = api as? HubClient
        card.bindReading(
            item,
            client?.imageLoader ?: coil.ImageLoader(card.context)
        ) { path -> api.imageUrl(path) }
        card.setTag(TAG_READING_ITEM, item)
        card.setOnClickListener { openReadingDetail(item) }
        card.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                modeStates.recall(ContentMode.BOOKS)?.focusedKey = item.key
                host?.refreshHints()
            }
        }
    }

    private inner class ReadingRowsAdapter : RecyclerView.Adapter<ReadingRowHolder>() {
        private val rows = mutableListOf<ReadingDiscoverRow>()

        fun submit(next: List<ReadingDiscoverRow>) {
            rows.clear()
            rows.addAll(next)
            notifyDataSetChanged()
        }

        fun snapshot(): List<ReadingDiscoverRow> = rows.toList()

        fun append(loadKey: String, fetched: ReadingDiscoverRow) {
            val index = rows.indexOfFirst { it.contentType + ":" + it.id == loadKey }
                .takeIf { it >= 0 } ?: return
            val existing = rows[index]
            val seen = existing.items.mapTo(HashSet()) { it.key }
            val fresh = fetched.items.filter { seen.add(it.key) }
            rows[index] = existing.copy(
                page = fetched.page,
                hasMore = fetched.hasMore,
                items = existing.items + fresh
            )
            notifyItemChanged(index, PAYLOAD_MORE)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReadingRowHolder =
            ReadingRowHolder(ReadingPosterRowView(parent.context, colors))

        override fun onBindViewHolder(holder: ReadingRowHolder, position: Int) {
            (holder.itemView as ReadingPosterRowView).bind(rows[position])
        }

        override fun onBindViewHolder(
            holder: ReadingRowHolder,
            position: Int,
            payloads: MutableList<Any>
        ) {
            if (payloads.contains(PAYLOAD_MORE)) {
                (holder.itemView as ReadingPosterRowView).appendOnly(rows[position])
            } else {
                onBindViewHolder(holder, position)
            }
        }

        override fun getItemCount(): Int = rows.size
    }

    private class ReadingRowHolder(view: View) : RecyclerView.ViewHolder(view)

    private inner class ReadingPosterRowView(
        context: android.content.Context,
        colors: PocketColors
    ) : LinearLayout(context) {
        private val label: TextView
        private val strip: RecyclerView
        private val stripAdapter = ReadingStripAdapter()
        private var current: ReadingDiscoverRow? = null

        init {
            orientation = VERTICAL
            clipChildren = false
            label = TextView(context).apply {
                textSize = 13f
                setTextColor(colors.primaryText)
                setPadding(
                    Styler.dpInt(context, 12f), Styler.dpInt(context, 6f),
                    Styler.dpInt(context, 12f), Styler.dpInt(context, 1f)
                )
            }
            addView(label)
            strip = RecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
                adapter = stripAdapter
                isFocusable = false
                clipToPadding = false
                clipChildren = false
                setItemViewCacheSize(8)
                setPadding(Styler.dpInt(context, 16f), 0, Styler.dpInt(context, 16f), 0)
                addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
                        val manager = view.layoutManager as? LinearLayoutManager ?: return
                        val row = current ?: return
                        if (manager.findLastVisibleItemPosition() >= stripAdapter.itemCount - PREFETCH_AHEAD) {
                            loadMoreReadingRow(row)
                        }
                    }
                })
            }
            addView(strip, LayoutParams(MATCH, WRAP))
        }

        fun bind(row: ReadingDiscoverRow) {
            current = row
            label.text = row.title
            stripAdapter.submit(row.items)
            strip.scrollToPosition(0)
        }

        fun appendOnly(row: ReadingDiscoverRow) {
            current = row
            stripAdapter.submit(row.items)
        }

        private inner class ReadingStripAdapter : RecyclerView.Adapter<CardHolder>() {
            private val items = mutableListOf<ReadingItem>()

            fun submit(next: List<ReadingItem>) {
                val added = next.size - items.size
                if (added > 0 && next.take(items.size).map { it.key } == items.map { it.key }) {
                    val from = items.size
                    items.addAll(next.drop(from))
                    notifyItemRangeInserted(from, added)
                    return
                }
                items.clear()
                items.addAll(next)
                notifyDataSetChanged()
            }

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardHolder =
                CardHolder(newCard(parent, ROW_POSTER_DP, Styler.dpInt(parent.context, ROW_CARD_DP)))

            override fun onBindViewHolder(holder: CardHolder, position: Int) {
                bindReadingCard(holder.itemView as PosterCardView, items[position])
            }

            override fun getItemCount(): Int = items.size
        }
    }

    private inner class ReadingHitAdapter : RecyclerView.Adapter<CardHolder>() {
        private val items = mutableListOf<ReadingItem>()

        fun submit(next: List<ReadingItem>) {
            items.clear()
            items.addAll(next)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardHolder =
            CardHolder(newCard(parent, GRID_POSTER_DP, MATCH))

        override fun onBindViewHolder(holder: CardHolder, position: Int) {
            bindReadingCard(holder.itemView as PosterCardView, items[position])
        }

        override fun getItemCount(): Int = items.size
    }

    /** The vertical list of rows. */
    private inner class RowsAdapter : RecyclerView.Adapter<RowHolder>() {
        private val rows = mutableListOf<DiscoverRow>()

        fun submit(next: List<DiscoverRow>) {
            rows.clear()
            rows.addAll(next)
            notifyDataSetChanged()
        }

        /**
         * Append a fetched page to one row.
         *
         * notifyItemChanged on the row, not the whole list: rebuilding all four
         * rows would throw focus out of the one being scrolled, which on a
         * gamepad is the cursor.
         */
        fun append(rowId: String, fetched: DiscoverRow) {
            val index = rows.indexOfFirst { it.id == rowId }.takeIf { it >= 0 } ?: return
            val existing = rows[index]
            val seen = existing.items.mapTo(HashSet()) { it.media.key }
            // TMDB's paged feeds re-list titles as popularity shifts between
            // requests, and a duplicate poster in a row looks like a bug.
            val fresh = fetched.items.filter { seen.add(it.media.key) }
            rows[index] = existing.copy(
                page = fetched.page,
                totalPages = fetched.totalPages,
                items = existing.items + fresh
            )
            notifyItemChanged(index, PAYLOAD_MORE)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder =
            RowHolder(PosterRowView(parent.context, colors))

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            (holder.itemView as PosterRowView).bind(rows[position])
        }

        override fun onBindViewHolder(
            holder: RowHolder,
            position: Int,
            payloads: MutableList<Any>
        ) {
            if (payloads.contains(PAYLOAD_MORE)) {
                // Only the strip's adapter changed; rebuilding the whole row
                // would reset its horizontal scroll to the left edge.
                (holder.itemView as PosterRowView).appendOnly(rows[position])
            } else {
                onBindViewHolder(holder, position)
            }
        }

        override fun getItemCount(): Int = rows.size
    }

    private class RowHolder(view: View) : RecyclerView.ViewHolder(view)

    /** One titled strip of posters. */
    private inner class PosterRowView(
        context: android.content.Context,
        colors: PocketColors
    ) : LinearLayout(context) {

        private val label: TextView
        private val strip: RecyclerView
        private val stripAdapter = StripAdapter()

        init {
            orientation = VERTICAL
            clipChildren = false
            label = TextView(context).apply {
                textSize = 13f
                setTextColor(colors.primaryText)
                setPadding(
                    Styler.dpInt(context, 12f), Styler.dpInt(context, 6f),
                    Styler.dpInt(context, 12f), Styler.dpInt(context, 1f)
                )
            }
            addView(label)

            strip = RecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
                adapter = stripAdapter
                // ScrollView-family views are focusable by default and become
                // invisible focus stops. A RecyclerView is not, but its focus
                // must pass through to the cards either way.
                isFocusable = false
                clipToPadding = false
                clipChildren = false
                setItemViewCacheSize(8)
                setPadding(Styler.dpInt(context, 16f), 0, Styler.dpInt(context, 16f), 0)
                addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
                        val manager = view.layoutManager as? LinearLayoutManager ?: return
                        val row = current ?: return
                        if (manager.findLastVisibleItemPosition() >=
                            stripAdapter.itemCount - PREFETCH_AHEAD
                        ) {
                            loadMoreRow(row)
                        }
                    }
                })
            }
            addView(strip, LayoutParams(MATCH, WRAP))
        }

        private var current: DiscoverRow? = null

        fun bind(row: DiscoverRow) {
            current = row
            label.text = row.title
            stripAdapter.submit(row.items)
            strip.scrollToPosition(0)
        }

        fun appendOnly(row: DiscoverRow) {
            current = row
            stripAdapter.submit(row.items)
        }

        private inner class StripAdapter : RecyclerView.Adapter<CardHolder>() {
            private val items = mutableListOf<SearchHit>()

            fun submit(next: List<SearchHit>) {
                val added = next.size - items.size
                if (added > 0 && next.take(items.size).map { it.media.key } ==
                    items.map { it.media.key }
                ) {
                    // A pure append. notifyItemRangeInserted keeps every existing
                    // card -- and therefore the focused one -- exactly where it is.
                    val from = items.size
                    items.addAll(next.drop(from))
                    notifyItemRangeInserted(from, added)
                    return
                }
                items.clear()
                items.addAll(next)
                notifyDataSetChanged()
            }

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardHolder =
                CardHolder(
                    newCard(parent, ROW_POSTER_DP, Styler.dpInt(parent.context, ROW_CARD_DP))
                )

            override fun onBindViewHolder(holder: CardHolder, position: Int) {
                bindCard(holder.itemView as PosterCardView, items[position])
            }

            override fun getItemCount(): Int = items.size
        }
    }

    /** The search results grid. */
    private inner class HitAdapter : RecyclerView.Adapter<CardHolder>() {
        private val items = mutableListOf<SearchHit>()

        fun submit(next: List<SearchHit>) {
            items.clear()
            items.addAll(next)
            notifyDataSetChanged()
        }

        fun append(next: List<SearchHit>) {
            val seen = items.mapTo(HashSet()) { it.media.key }
            val fresh = next.filter { seen.add(it.media.key) }
            if (fresh.isEmpty()) return
            val from = items.size
            items.addAll(fresh)
            notifyItemRangeInserted(from, fresh.size)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardHolder =
            CardHolder(newCard(parent, GRID_POSTER_DP, MATCH))

        override fun onBindViewHolder(holder: CardHolder, position: Int) {
            bindCard(holder.itemView as PosterCardView, items[position])
        }

        override fun getItemCount(): Int = items.size
    }

    private class CardHolder(view: View) : RecyclerView.ViewHolder(view)

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        /**
         * Card geometry, measured against this hardware.
         *
         * The usable area is 853 x 456 dp (1920x1026 at density 2.25). After the
         * tab bar, hint bar, search field and status line there are roughly
         * 340dp of vertical space, so a row has to fit inside ~170dp for two to
         * be visible at once -- which is the Findroid look and was the point of
         * shrinking these. A 2:3 poster at 108dp tall is 72dp wide, plus a title
         * line and margins.
         */
        const val ROW_POSTER_DP = 150f
        const val ROW_CARD_DP = 104f

        /** Search results get a little more room, since there is no row label. */
        const val GRID_POSTER_DP = 150f
        const val SEARCH_COLUMNS = 7

        /** Start fetching the next page this many cards from the end. */
        const val PREFETCH_AHEAD = 6

        const val PAYLOAD_MORE = "more"
        const val TAG_HIT = -0x7ffffff5
        const val TAG_READING_ITEM = -0x7ffffff4
    }
}
