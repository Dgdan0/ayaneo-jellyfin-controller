package com.pocketds.hub.screens.discover

import com.pocketds.hub.ui.FocusHorizontalScrollView
import com.pocketds.hub.ui.Artwork
import com.pocketds.hub.ui.ThemeGradientDrawable
import android.view.Gravity
import android.graphics.drawable.InsetDrawable
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
import com.pocketds.hub.nav.ContentModeScreen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.FailureKind
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.settings.ContentModeSettings
import com.pocketds.hub.state.ContentMode
import com.pocketds.hub.state.ContentModeMemory
import com.pocketds.hub.ui.DiscoverFeatureCardView
import com.pocketds.hub.ui.ReadingCategoryTabView
import com.pocketds.hub.ui.AppIcon
import com.pocketds.hub.ui.AppIconDrawable
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.ShelfFocusNavigator
import com.pocketds.hub.ui.ShelfFocusLane
import com.pocketds.hub.ui.ShelfFocusRow
import com.pocketds.hub.ui.FormOverlay
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.pinFocusedRows
import com.pocketds.hub.ui.useResponsivePosterColumns
import com.pocketds.hub.ui.activateOnTap
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
import com.pocketds.hub.state.StatusMessage
import com.pocketds.hub.state.StatusText
import com.pocketds.hub.state.RequestedTitles
import com.pocketds.hub.state.PagedLoadState
import com.pocketds.hub.state.RowPaging
import com.pocketds.hub.ui.showStatus
import com.pocketds.hub.ui.textWeight

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
) : Screen, ContentModeScreen {

    override val title: String = "Discover"

    /**
     * Glass: the card in focus, or on Upcoming the release in the preview;
     * the search box and the tabs keep the page as it is.
     */
    override val pageArtwork: String?
        get() = when {
            !::colors.isInitialized || mode != ContentMode.MEDIA -> null
            upcomingActive -> upcoming.pageArtwork
            else -> focusedHit()?.let(::artworkOf)
        }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var colors: PocketColors
    /** Glass (#11): the prototype's Discover; a styling switch only. */
    private var glass = false
    private lateinit var searchBox: EditText
    private lateinit var readingFilters: HorizontalScrollView
    /** Discover | Upcoming, at the start of the search row. */
    private lateinit var tabs: com.pocketds.hub.ui.BlobSegmentedView
    /** Upcoming lives here as a tab: the calendar screen, embedded. */
    private val upcoming = UpcomingScreen(api, ringVisible, embedded = true)
    private lateinit var upcomingView: View
    private lateinit var topRow: LinearLayout
    private lateinit var searchStatus: View
    private var tab = TAB_DISCOVER
    private val readingFilterButtons = mutableMapOf<String, ReadingCategoryTabView>()
    /** Glass: the book filters as a glass capsule beside the search, as the prototype's Books Discover. */
    private var readingCapsule: com.pocketds.hub.ui.BlobSegmentedView? = null
    private lateinit var statusLine: TextView
    private lateinit var broaderSearchButton: TextView
    private lateinit var rowsList: RecyclerView
    private lateinit var resultsGrid: RecyclerView
    private lateinit var readingRowsList: RecyclerView
    private lateinit var readingResultsGrid: RecyclerView
    private val rowsAdapter = RowsAdapter()
    private var requestedRevision = RequestedTitles.revision
    private val shelfNavigation = ShelfFocusNavigator()
    private val resultsAdapter = HitAdapter()
    private val readingRowsAdapter = ReadingRowsAdapter()
    private val readingResultsAdapter = ReadingHitAdapter()
    private var readingSearchPresentation = ReadingSearchPresentation()
    private var readingSearchCaveat = StatusMessage("")
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
    private val rowPaging = RowPaging(PREFETCH_AHEAD)
    private val readingRowPaging = RowPaging(PREFETCH_AHEAD)

    /**
     * Highest page already asked for, per row.
     *
     * The job map alone is not enough. A cached page comes back in 7ms, so the
     * job is finished before the next scroll event arrives, while the row object
     * the strip is holding has not been re-bound yet -- and page 2 gets fetched
     * twice. Measured exactly that, 48ms apart.
     */

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        val context = host.viewContext
        colors = Theme.colors(context)
        glass = Theme.onGlass(colors)
        mode = ContentModeSettings.get(context)

        val frame = android.widget.FrameLayout(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.background)
        }
        frame.addView(root, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))

        readingFilters = FocusHorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            visibility = if (mode == ContentMode.BOOKS) View.VISIBLE else View.GONE
            // Glass: the filters are a capsule in the row with the search, built there.
            if (!glass) addView(buildReadingFilters())
        }
        if (!glass) root.addView(readingFilters, LinearLayout.LayoutParams(MATCH, WRAP))

        tabs = com.pocketds.hub.ui.BlobSegmentedView(context, colors, ringVisible).apply {
            heightDp = if (glass) 34f else 38f
            textSp = if (glass) 12f else 12.5f
            // Glass: a capsule of the page's glass, as the prototype's is.
            if (glass) useGlassTrack() else trackColor = colors.cardSurface
            // A picks: the tabs sit beside the search box, and passing through
            // them on the way there should not swap the page.
            setOptions(listOf(com.pocketds.hub.ui.BlobSegmentedView.Option(TAB_DISCOVER, "Discover"),
                com.pocketds.hub.ui.BlobSegmentedView.Option(TAB_UPCOMING, "Upcoming")), TAB_DISCOVER)
            onPick = ::showTab
            onOptionFocused = { host.refreshHints() }
        }

        searchBox = EditText(context).apply {
            hint = if (mode == ContentMode.BOOKS) "Search books, comics and audio" else "Search films and series"
            // Glass: the prototype's search, a pill of the page's glass 34dp of the 48dp target.
            if (glass) com.pocketds.hub.ui.glass.GlassSearchField.style(this, colors, pillDp = 34f, targetDp = 48f) else {
                textSize = 14f
                setTextColor(colors.primaryText)
                setHintTextColor(colors.mutedText)
                background = InsetDrawable(ThemeGradientDrawable().apply {
                    cornerRadius = Styler.dp(context, 12f)
                    setColor(this@DiscoverScreen.colors.cardSurface)
                    setStroke(Styler.dpInt(context, 1f), this@DiscoverScreen.colors.stripBackground)
                }, 0, Styler.dpInt(context, 4f), 0, Styler.dpInt(context, 4f))
                setCompoundDrawablesRelative(AppIconDrawable(AppIcon.SEARCH, colors.mutedText).apply {
                    val size = Styler.dpInt(context, 18f)
                    setBounds(0, 0, size, size)
                }, null, null, null)
                compoundDrawablePadding = Styler.dpInt(context, 9f)
                setSingleLine()
                val h = Styler.dpInt(context, 12f)
                val v = Styler.dpInt(context, 6f)
                setPadding(h, v, h, v)
            }
            imeOptions = EditorInfo.IME_ACTION_SEARCH
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
        searchBox.minimumHeight=Styler.dpInt(context,48f)
        root.addView(LinearLayout(context).apply {
            topRow = this
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            setPadding(Styler.dpInt(context, 16f), Styler.dpInt(context, 4f), Styler.dpInt(context, 16f), 0)
            addView(tabs, LinearLayout.LayoutParams(WRAP, WRAP).apply {
                marginStart = Styler.dpInt(context, 8f)
                marginEnd = Styler.dpInt(context, 2f)
            })
            if (glass) {
                // Books: All, Ebooks, Audiobooks... as one glass capsule, in the
                // filters' own scroller so a narrow screen can still reach them all.
                readingCapsule = com.pocketds.hub.ui.BlobSegmentedView(context, colors, ringVisible).apply {
                    heightDp = 34f
                    textSp = 12f
                    useGlassTrack()
                    setOptions(ReadingType.filters.map { (wire, label) ->
                        com.pocketds.hub.ui.BlobSegmentedView.Option(wire, label, "Show $label")
                    }, readingType)
                    onPick = ::selectReadingType
                    onOptionFocused = { host.refreshHints() }
                }
                readingFilters.addView(readingCapsule)
                readingFilters.clipToPadding = false
                addView(readingFilters, LinearLayout.LayoutParams(WRAP, WRAP).apply {
                    marginStart = Styler.dpInt(context, 8f)
                    marginEnd = Styler.dpInt(context, 2f)
                })
            }
            addView(searchBox, LinearLayout.LayoutParams(0, WRAP, 1f))
        }, LinearLayout.LayoutParams(MATCH, WRAP))

        val searchStatusRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusLine = TextView(context).apply {
            textSize = 11f
            setTextColor(colors.mutedText)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                Styler.dpInt(context, 26f), Styler.dpInt(context, 3f),
                Styler.dpInt(context, 12f), Styler.dpInt(context, 3f)
            )
        }
        searchStatusRow.addView(statusLine, LinearLayout.LayoutParams(0, WRAP, 1f))
        broaderSearchButton = TextView(context).apply {
            if (glass) com.pocketds.hub.ui.PillButton.control(this, colors) else {
                textSize = 11f
                setTextColor(colors.primaryText)
                setPadding(Styler.dpInt(context, 9f), Styler.dpInt(context, 5f),
                    Styler.dpInt(context, 9f), Styler.dpInt(context, 5f))
                background = Styler.chipBackground(context, colors)
            }
            Styler.makeFocusable(this)
            FocusDecorator.attach(this, ringVisible, scale = false)
            activateOnTap { toggleBroaderReadingResults() }
            visibility = View.GONE
        }
        searchStatusRow.addView(broaderSearchButton, LinearLayout.LayoutParams(WRAP, WRAP).apply {
            marginEnd = Styler.dpInt(context, 10f)
        })
        root.addView(searchStatusRow)
        searchStatus = searchStatusRow
        upcomingView = upcoming.onCreateView(host, root).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }
        root.addView(upcomingView)
        // Upcoming's week switch takes the search box's place beside the tabs.
        topRow.addView(upcoming.weekSwitch, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = Styler.dpInt(context, 8f) })

        rowsList = RecyclerView(context).apply {
            adapter = rowsAdapter
            // The focused row rests at the top, as on Home, so each row comes
            // to the same place rather than wherever its cards first fit.
            pinFocusedRows(if (glass) GLASS_SHORTEST_ROW_DP else SHORTEST_ROW_DP)
            // A row's focused card is scaled up and its ring must not be clipped
            // by the row above.
            clipChildren = false
            setItemViewCacheSize(6)
            // No top padding: the focused row's own label sits above its cards,
            // and a band there would show a sliver of the row before it.
            //
            // The pinning is PinnedRowsLayoutManager's requestChildRectangleOnScreen,
            // the LayoutManager's, which RecyclerView.requestChildFocus does call.
            // The View-level override tried here before is never consulted on the
            // focus path, which is why that attempt did nothing; correcting the
            // position in requestChildFocus scrolled twice per press.
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            addOnChildAttachStateChangeListener(claimFocusOnFirstChild(this))
        }
        root.addView(rowsList)

        resultsGrid = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, SEARCH_COLUMNS)
            useResponsivePosterColumns(SEARCH_COLUMNS)
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
                Styler.dpInt(context, 6f), Styler.dpInt(context, 20f)
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
            setPadding(0, Styler.dpInt(context, 10f), 0, Styler.dpInt(context, 20f))
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            visibility = View.GONE
            addOnChildAttachStateChangeListener(claimFocusOnFirstChild(this))
        }
        root.addView(readingRowsList)

        readingResultsGrid = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, SEARCH_COLUMNS)
            useResponsivePosterColumns(SEARCH_COLUMNS)
            adapter = readingResultsAdapter
            setHasFixedSize(true)
            setItemViewCacheSize(SEARCH_COLUMNS * 3)
            clipToPadding = false
            clipChildren = false
            visibility = View.GONE
            setPadding(
                Styler.dpInt(context, 6f), 0,
                Styler.dpInt(context, 6f), Styler.dpInt(context, 20f)
            )
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            addOnChildAttachStateChangeListener(claimFocusOnFirstChild(this))
        }
        root.addView(readingResultsGrid)

        form = FormOverlay(context, colors, ringVisible, glass = Theme.onGlass(colors))
        frame.addView(form, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))

        flow = RequestFlow(
            api = api,
            scope = scope,
            overlay = { form },
            onStatus = { text, isError ->
                statusLine.showStatus(StatusMessage(text, if (isError) com.pocketds.hub.state.StatusTone.ERROR else com.pocketds.hub.state.StatusTone.NORMAL), colors)
            },
            onNotify = { host.notify(it) },
            onHintsChanged = { host.refreshHints() },
            onRequested = ::rebindRequestedCards
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
            val chip = ReadingCategoryTabView(bar.context, colors, label).apply {
                activateOnTap { selectReadingType(wire) }
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
            button.select(selected)
        }
        readingCapsule?.select(readingType)
    }

    private fun selectReadingType(type: String) {
        if (type == readingType) return
        readingType = type
        activeState.focusedKey = ""
        updateReadingFilterStyles()
        readingRowPaging.clear()
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
                // A different book type must not show the previous type's rows
                // while it loads; Refresh, by contrast, keeps them.
                readingRowsAdapter.submit(emptyList())
                loadReadingRows(force = true)
            }
        }
    }

    private fun switchMode(next: ContentMode) {
        shelfNavigation.cancel()
        if (mode == next) return
        mode = next
        ContentModeSettings.set(host?.viewContext ?: return, mode)
        searchBox.hint = if (mode == ContentMode.BOOKS) "Search books, comics and audio" else "Search films and series"
        searchBox.setText(lastQuery)
        readingFilters.visibility = if (mode == ContentMode.BOOKS) View.VISIBLE else View.GONE
        applyModeVisibility()
        if (searching) {
            val count = if (mode == ContentMode.MEDIA) resultsAdapter.itemCount else readingResultsAdapter.itemCount
            statusLine.showStatus(
                if (mode == ContentMode.BOOKS) StatusText.loaded(readingSearchPresentation.summary(), readingSearchCaveat)
                else StatusMessage("$count results"),
                colors
            )
            if (count == 0 && lastQuery.isNotBlank()) runSearch(lastQuery, force = true)
        } else if (mode == ContentMode.MEDIA) {
            statusLine.showStatus(StatusText.loaded("${rowsAdapter.itemCount} rows"), colors)
            if (rowsAdapter.itemCount == 0) loadRows()
        } else {
            statusLine.showStatus(StatusText.loaded("${readingRowsAdapter.itemCount} rows"), colors)
            if (readingRowsAdapter.itemCount == 0) loadReadingRows()
        }
        activeList().post {
            restoreContentFocus()
            host?.refreshHints()
        }
    }

    override fun selectContentMode(mode: ContentMode) = switchMode(mode)

    private fun applyModeVisibility() {
        val upcomingTab = mode == ContentMode.MEDIA && tab == TAB_UPCOMING
        tabs.visibility = if (mode == ContentMode.MEDIA) View.VISIBLE else View.GONE
        searchBox.visibility = if (upcomingTab) View.GONE else View.VISIBLE
        upcoming.weekSwitch.visibility = if (upcomingTab) View.VISIBLE else View.GONE
        searchStatus.visibility = if (upcomingTab) View.GONE else View.VISIBLE
        upcomingView.visibility = if (upcomingTab) View.VISIBLE else View.GONE
        rowsList.visibility = if (mode == ContentMode.MEDIA && !searching && !upcomingTab) View.VISIBLE else View.GONE
        resultsGrid.visibility = if (mode == ContentMode.MEDIA && searching && !upcomingTab) View.VISIBLE else View.GONE
        readingRowsList.visibility = if (mode == ContentMode.BOOKS && !searching) View.VISIBLE else View.GONE
        readingResultsGrid.visibility = if (mode == ContentMode.BOOKS && searching) View.VISIBLE else View.GONE
        broaderSearchButton.visibility = if (mode == ContentMode.BOOKS && searching && readingSearchPresentation.canToggle)
            View.VISIBLE else View.GONE
        broaderSearchButton.text = if (readingSearchPresentation.showBroader) "Close matches" else "Show broader"
        broaderSearchButton.contentDescription = if (readingSearchPresentation.showBroader) "Show close book matches only"
            else "Show broader book search results"
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

    private fun focusLanes(): List<ShelfFocusLane> {
        val width = (host!!.viewContext.resources.configuration.screenWidthDp - 180).coerceAtLeast(0)
        return if (mode == ContentMode.MEDIA) rowsAdapter.focusLanes(width) else readingRowsAdapter.focusLanes(width)
    }

    private fun restoreContentFocus(): Boolean {
        val key = activeState.focusedKey
        if (key.isNotEmpty()) {
            findContentKey(activeList(), key)?.let { if (it.requestFocus()) return true }
            if (!searching) focusLanes().firstOrNull { key in it.keys }?.let {
                shelfNavigation.focus(activeList(), it, key)
                return true
            }
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

    /**
     * Redraws the cards in place after a request, here or on a detail page
     * opened from here. A payload keeps each card's holder, and so its focus.
     */
    private fun rebindRequestedCards() {
        if (requestedRevision == RequestedTitles.revision) return
        requestedRevision = RequestedTitles.revision
        rowsAdapter.notifyItemRangeChanged(0, rowsAdapter.itemCount, PAYLOAD_STATE)
        resultsAdapter.notifyItemRangeChanged(0, resultsAdapter.itemCount, PAYLOAD_STATE)
        host?.refreshHints()
    }

    /**
     * Upcoming is a tab here rather than a page you push and back out of: the
     * calendar of what your monitored films and series bring next, beside what
     * there is to discover.
     */
    private fun showTab(id: String) {
        if (id == tab) return
        tab = id
        tabs.select(id)
        if (tab == TAB_UPCOMING) upcoming.onShow() else upcoming.onHide()
        applyModeVisibility()
        host?.refreshHints()
    }

    private val upcomingActive: Boolean get() = mode == ContentMode.MEDIA && tab == TAB_UPCOMING

    override fun onShow() {
        rebindRequestedCards()
        val stored = host?.viewContext?.let(ContentModeSettings::get) ?: mode
        if (stored != mode) switchMode(stored)
        if (upcomingActive) { upcoming.onShow(); return }
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
        upcoming.onHide()
        shelfNavigation.cancel()
        if (form.isOpen) {
            form.dismiss()
            host?.refreshHints()
        }
        // Backing out must kill the in-flight search and every poster load it
        // started. This is the reason coroutines are in this project at all.
        scope.coroutineContext.cancelChildren()
        rowPaging.clear()
        readingRowPaging.clear()
        searchPaging.cancelLoading()
        focusTarget = null
    }

    override fun onDestroyView() {
        upcoming.onDestroyView()
        scope.cancel()
        host = null
    }

    override fun hints(): List<ButtonHint> {
        if (form.isOpen) {
            return listOf(ButtonHint.activate("Change"), ButtonHint.back("Cancel"))
        }
        if (::tabs.isInitialized && tabs.hasFocus()) return listOf(ButtonHint.activate("Show"), ButtonHint.refresh())
        if (upcomingActive) return upcoming.hints()
        val hints = mutableListOf<ButtonHint>()
        hints.add(
            ButtonHint.activate(
                if (::searchBox.isInitialized && searchBox.hasFocus()) "Search" else "Open"
            )
        )
        if (mode == ContentMode.BOOKS) {
            hints.add(ButtonHint.secondary("Search box"))
            if (searching && readingSearchPresentation.canToggle) {
                hints.add(ButtonHint.primary(if (readingSearchPresentation.showBroader) "Close matches" else "Broader results"))
            }
            if (searching) hints.add(ButtonHint.back("Browse"))
            return hints
        }
        val hit = focusedHit()
        // Ⓧ opens the request *form* on the card you are looking at. It used to
        // open the detail screen, so requesting took two presses and a second
        // menu -- which is not what "request" reads as. With no card in focus
        // (the search field) it does nothing, so it is not offered: the search
        // field once said "Ⓧ Request" on the Pocket.
        when {
            hit == null -> if (flow.busy) hints.add(ButtonHint.primary("Requesting…").copy(enabled = false))
            !hit.canRequest -> hints.add(ButtonHint.primary(if (hit.canPlay) "In library" else "—").copy(enabled = false))
            else -> hints.add(ButtonHint.primary(if (flow.busy) "Requesting…" else "Request"))
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
        if (upcomingActive) return upcoming.requestInitialFocus()
        // A card that is no longer there gives way to the first row, not to
        // whatever is first in the view (the tabs).
        return restoreContentFocus() || focusLanes().firstOrNull()?.let { shelfNavigation.focus(activeList(), it); true } == true
    }

    override fun onPad(action: PadAction): Boolean = when {
        form.onPad(action) -> {
            host?.refreshHints()
            true
        }
        // Down from the tabs goes into whichever tab is showing.
        action is PadAction.Step && action.direction == com.pocketds.hub.input.Direction.DOWN && tabs.hasFocus() ->
            if (upcomingActive) upcoming.requestInitialFocus()
            else { focusLanes().firstOrNull()?.let { shelfNavigation.focus(activeList(), it) }; true }
        // Left from the start of the search box goes to the tab you are on.
        action is PadAction.Step && action.direction == com.pocketds.hub.input.Direction.LEFT && searchBox.hasFocus() &&
            searchBox.selectionStart == 0 && tabs.visibility == View.VISIBLE -> tabs.focus(tab)
        upcomingActive && !tabs.hasFocus() -> upcoming.onPad(action)
        action is PadAction.Step && !searching &&
            shelfNavigation.step(activeList(), focusLanes(), action.direction, searchBox) -> true
        action is PadAction.Step && !searching && action.direction == com.pocketds.hub.input.Direction.DOWN &&
            searchBox.hasFocus() -> {
                focusLanes().firstOrNull()?.let { shelfNavigation.focus(activeList(), it) }
                true
            }
        // A on the search box submits. Without this, Activate calls performClick
        // on an EditText, which does nothing visible and looks like a dead button.
        action == PadAction.Activate && searchBox.hasFocus() -> {
            runSearch(searchBox.text.toString())
            true
        }
        action == PadAction.Activate && broaderSearchButton.hasFocus() -> {
            toggleBroaderReadingResults()
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
                if (searching && readingSearchPresentation.canToggle) {
                    toggleBroaderReadingResults()
                    true
                } else false
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
        host?.push(ReadingDetailScreen(api, item, ringVisible))
    }

    // ---- browse ------------------------------------------------------------

    private fun loadRows(force: Boolean = false) {
        shelfNavigation.cancel()
        // Refresh keeps the rows on screen until new ones arrive; clearing them
        // first turned a failed refresh into a blank screen.
        statusLine.showStatus(StatusText.loading("Discover", refreshing = rowsAdapter.itemCount > 0), colors)
        focusTarget = rowsList
        rowsJob?.cancel()
        rowsJob = scope.launch {
            when (val result = api.discover(force)) {
                is HubResult.Ok -> {
                    val body = result.value
                    rowsAdapter.submit(body.rows)
                    // Glass: the colours of what the rows show, asked for in one go.
                    host?.prefetchArtwork(body.rows.flatMap { row -> row.items.take(PREFETCH_COLOURS).map(::artworkOf) }.filter(String::isNotBlank))
                    if (mode == ContentMode.MEDIA && !searching) {
                        statusLine.showStatus(
                            StatusText.loaded("${body.rows.size} rows", body.cache, body.partial.map { it.service }),
                            colors
                        )
                        host?.refreshHints()
                    }
                    // Focus is claimed by claimFocusOnFirstChild once a row is
                    // actually attached; asking here would be too early.
                }
                is HubResult.Failed -> if (mode == ContentMode.MEDIA && !searching) {
                    showFailure(result.kind, result.message, hasData = rowsAdapter.itemCount > 0)
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
    private fun loadMoreRow(row: DiscoverRow, lastVisible: Int, itemCount: Int) {
        val next = rowPaging.next(row.id, row.page, row.totalPages, lastVisible, itemCount) ?: return
        // Scroll callbacks can run during RecyclerView layout. A cached response
        // must still wait until that layout finishes before notifying adapters.
        scope.launch(Dispatchers.Main) {
            DebugLog.log("net", "discover ${row.id} page $next")
            when (val result = api.discoverRow(row.id, next)) {
                is HubResult.Ok -> {
                    val fetched = result.value.rows.firstOrNull()
                    if (fetched == null) {
                        rowPaging.fail(row.id, next)
                        return@launch
                    }
                    rowPaging.complete(row.id, next, fetched.totalPages)
                    rowsAdapter.append(row.id, fetched)
                }
                is HubResult.Failed -> {
                    // A failed page must not permanently cap how far this row can scroll.
                    rowPaging.fail(row.id, next)
                    DebugLog.log("net", "discover ${row.id} page $next failed: ${result.message}")
                }
            }
        }
    }

    private fun loadReadingRows(force: Boolean = false) {
        shelfNavigation.cancel()
        val requestedType = readingType
        statusLine.showStatus(
            StatusText.loading(ReadingType.label(requestedType).lowercase(), refreshing = readingRowsAdapter.itemCount > 0),
            colors
        )
        focusTarget = readingRowsList
        readingRowsJob?.cancel()
        readingRowsJob = scope.launch {
            when (val result = api.readingDiscover(requestedType, force)) {
                is HubResult.Ok -> {
                    if (requestedType != readingType) return@launch
                    val body = result.value
                    val shown = ReadingDiscoverRows.shown(body.rows, requestedType)
                    readingRowsByType[requestedType] = shown
                    readingRowsAdapter.submit(shown)
                    if (mode == ContentMode.BOOKS && !searching) {
                        statusLine.showStatus(
                            StatusText.loaded("${shown.size} rows", body.cache, body.partial.map { it.service }),
                            colors
                        )
                        host?.refreshHints()
                    }
                }
                is HubResult.Failed -> if (mode == ContentMode.BOOKS && !searching && requestedType == readingType) {
                    showFailure(result.kind, result.message, hasData = readingRowsAdapter.itemCount > 0)
                }
            }
        }
    }

    private fun loadMoreReadingRow(row: ReadingDiscoverRow, lastVisible: Int, itemCount: Int) {
        val requestedType = readingType
        val loadKey = row.contentType + ":" + row.id
        val total = if (row.hasMore) row.page + 1 else row.page
        val next = readingRowPaging.next(loadKey, row.page, total, lastVisible, itemCount) ?: return
        scope.launch(Dispatchers.Main) {
            when (val result = api.readingDiscoverRow(row.id, row.contentType, next)) {
                is HubResult.Ok -> {
                    val fetched = result.value.rows.firstOrNull() ?: return@launch
                    // A cached response can complete inline from onScrolled during
                    // RecyclerView layout. Adapter notifications must run later.
                    readingRowsList.post {
                        if (host == null || mode != ContentMode.BOOKS || searching || readingType != requestedType) {
                            readingRowPaging.fail(loadKey, next)
                            return@post
                        }
                        readingRowPaging.complete(loadKey, next, if (fetched.hasMore) next + 1 else next)
                        readingRowsAdapter.append(loadKey, fetched)
                        readingRowsByType[requestedType] = readingRowsAdapter.snapshot()
                    }
                }
                is HubResult.Failed -> {
                    readingRowPaging.fail(loadKey, next)
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
        statusLine.showStatus(StatusText.loaded("$count rows"), colors)
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
            statusLine.showStatus(StatusText.notice("Type at least two characters."), colors)
            return
        }
        if (trimmed == lastQuery && !force && searching) return
        val requestedMode = mode
        val requestedType = readingType
        if (requestedMode == ContentMode.BOOKS) {
            readingSearchPresentation = ReadingSearchPresentation()
            readingSearchCaveat = StatusMessage("")
        }
        lastQuery = trimmed
        searching = true
        applyModeVisibility()
        statusLine.showStatus(StatusMessage("Searching…"), colors)
        host?.refreshHints()
        // Supersede whatever was in flight; the old query's results are no
        // longer what anyone is looking at.
        scope.coroutineContext.cancelChildren()
        rowPaging.clear()
        searchPaging.reset()
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
                        readingSearchPresentation = ReadingSearchPresentation.forResults(body.results, body.broaderResults)
                        readingSearchCaveat = StatusText.caveat(body.cache, body.partial.map { it.service })
                        readingResultsAdapter.submit(readingSearchPresentation.visibleResults())
                        if (mode == ContentMode.BOOKS) {
                            statusLine.showStatus(StatusText.loaded(readingSearchPresentation.summary(), readingSearchCaveat), colors)
                            applyModeVisibility()
                            host?.refreshHints()
                        }
                    }
                    is HubResult.Failed -> if (mode == ContentMode.BOOKS && modeStates.recall(ContentMode.BOOKS)?.query == trimmed) {
                        showFailure(result.kind, result.message, hasData = readingResultsAdapter.itemCount > 0)
                    }
                }
                return@launch
            }
            when (val result = api.search(trimmed)) {
                is HubResult.Ok -> {
                    if (modeStates.recall(ContentMode.MEDIA)?.query != trimmed) return@launch
                    val body = result.value
                    searchPaging.seed(body.page, body.totalPages)
                    resultsAdapter.submit(body.results)
                    if (mode == ContentMode.MEDIA) {
                        statusLine.showStatus(StatusText.loaded("${body.totalResults} results", body.cache), colors)
                        host?.refreshHints()
                    }
                }
                is HubResult.Failed -> if (mode == ContentMode.MEDIA && modeStates.recall(ContentMode.MEDIA)?.query == trimmed) {
                    showFailure(result.kind, result.message, hasData = resultsAdapter.itemCount > 0)
                }
            }
        }
    }

    private fun toggleBroaderReadingResults() {
        if (mode != ContentMode.BOOKS || !searching || !readingSearchPresentation.canToggle) return
        val buttonHadFocus = broaderSearchButton.hasFocus()
        readingSearchPresentation = readingSearchPresentation.toggleBroader()
        readingResultsAdapter.submit(readingSearchPresentation.visibleResults())
        statusLine.showStatus(StatusText.loaded(readingSearchPresentation.summary(), readingSearchCaveat), colors)
        applyModeVisibility()
        readingResultsGrid.post {
            if (buttonHadFocus) broaderSearchButton.requestFocus() else restoreContentFocus()
            host?.refreshHints()
        }
    }

    private val searchPaging = PagedLoadState(SEARCH_COLUMNS * 2)

    private fun maybeLoadMoreResults() {
        if (mode != ContentMode.MEDIA || !searching) return
        val manager = resultsGrid.layoutManager as? GridLayoutManager ?: return
        val next = searchPaging.next(manager.findLastVisibleItemPosition(), resultsAdapter.itemCount) ?: return
        val query = modeStates.recall(ContentMode.MEDIA)?.query.orEmpty()
        scope.launch {
            when (val result = api.search(query, next)) {
                is HubResult.Ok -> {
                    if (modeStates.recall(ContentMode.MEDIA)?.query != query) return@launch
                    searchPaging.complete(next, result.value.totalPages)
                    resultsAdapter.append(result.value.results)
                }
                is HubResult.Failed -> {
                    searchPaging.fail(next)
                    DebugLog.log("net", "search page $next failed: ${result.message}")
                }
            }
        }
    }

    private fun showFailure(kind: FailureKind, message: String, hasData: Boolean) {
        statusLine.showStatus(StatusText.failed(message, kind, hasData), colors)
        DebugLog.log("net", "failed: $kind — $message")
    }

    // ---- adapters ----------------------------------------------------------

    /**
     * [glassCard]: Glass posters (media only; Books keep theirs until their
     * milestone), caption-less in a row as the prototype's are.
     */
    private fun newCard(parent: ViewGroup, posterHeight: Float, width: Int, glassCard: Boolean = false, captions: Boolean = true): PosterCardView =
        PosterCardView(parent.context, colors, posterHeight, captions = captions, glass = glassCard).apply {
            layoutParams = RecyclerView.LayoutParams(width, WRAP).apply {
                val m = Styler.dpInt(parent.context, if (glassCard) (if (captions) 5f else 6f) else 8f)
                setMargins(m, m, m, m)
            }
            FocusDecorator.attach(this, ringVisible)
        }

    /** The picture a hit gives the Glass page: its backdrop, else its poster. */
    private fun artworkOf(hit: SearchHit): String = hit.media.backdrop.ifBlank { hit.media.poster }

    private fun bindCard(card: PosterCardView, fromHub: SearchHit) {
        val hit = RequestedTitles.apply(fromHub)
        card.bind(
            hit,
            Artwork.loader(api, card.context)
        ) { path -> api.imageUrl(path) }
        card.setTag(TAG_HIT, hit)
        card.activateOnTap { openDetail(hit) }
        FocusDecorator.listen(card, ringVisible) { _, hasFocus ->
            if (hasFocus) {
                modeStates.recall(ContentMode.MEDIA)?.focusedKey = hit.media.key
                host?.refreshHints()
            }
        }
    }

    private fun bindReadingCard(card: PosterCardView, item: ReadingItem) {
        card.bindReading(
            item,
            Artwork.loader(api, card.context)
        ) { path -> api.imageUrl(path) }
        card.setTag(TAG_READING_ITEM, item)
        card.activateOnTap { openReadingDetail(item) }
        FocusDecorator.listen(card, ringVisible) { _, hasFocus ->
            if (hasFocus) {
                modeStates.recall(ContentMode.BOOKS)?.focusedKey = item.key
                host?.refreshHints()
            }
        }
    }

    private inner class ReadingRowsAdapter : RecyclerView.Adapter<ReadingRowHolder>() {
        private val rows = mutableListOf<ReadingDiscoverRow>()

        fun focusLanes(width: Int): List<ShelfFocusLane> = rows.flatMapIndexed { index, row ->
            // Glass's Books Discover has no featured card: a cover cropped wide read badly.
            val feature = if (index == 0 && !glass) DiscoverFeaturePolicy.readingFeature(row, width) else null
            val id = "books:${row.contentType}:${row.id}"
            buildList {
                if (feature != null) add(ShelfFocusLane("$id:feature", index, true, listOf(feature.key)))
                val items = if (feature != null) row.items.drop(1) else row.items
                if (items.isNotEmpty()) add(ShelfFocusLane(id, index, false, items.map { it.key }))
            }
        }

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
            (holder.itemView as ReadingPosterRowView).bind(rows[position], position == 0)
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
    ) : LinearLayout(context), ShelfFocusRow, com.pocketds.hub.ui.PinnedRowsLayoutManager.Anchor {
        private val feature = DiscoverFeatureCardView(context, colors, ringVisible, glass)
        private val label: TextView
        private val strip: RecyclerView
        override val featureFocusView: View get() = feature
        override val posterFocusList: RecyclerView get() = strip
        override val shelfHeadingView: View get() = label
        /** On the featured card the row's top rests at the top; in the posters, the label does. */
        override fun pinOffset(focused: android.graphics.Rect): Int =
            if (feature.visibility == View.VISIBLE && focused.top < label.top) 0 else label.top
        private val stripAdapter = ReadingStripAdapter()
        private var current: ReadingDiscoverRow? = null
        private var featuredRow = false
        private val featureWidthDp get() = (resources.configuration.screenWidthDp - 180).coerceAtLeast(0)

        init {
            orientation = VERTICAL
            clipChildren = false
            feature.visibility = View.GONE
            addView(feature, LayoutParams(MATCH, WRAP).apply {
                if (glass) setMargins(Styler.dpInt(context, 22f), Styler.dpInt(context, 8f), Styler.dpInt(context, 22f), Styler.dpInt(context, 6f))
                else setMargins(Styler.dpInt(context, 18f), Styler.dpInt(context, 5f),
                    Styler.dpInt(context, 18f), Styler.dpInt(context, 9f))
            })
            label = TextView(context).apply {
                // Glass: a row title as Home's are, bold Figtree.
                if (glass) { textSize = 14f; textWeight(700) }
                else com.pocketds.hub.ui.Type.apply(this, com.pocketds.hub.ui.Type.Role.HEADING, 15f)
                setTextColor(colors.primaryText)
                setPadding(
                    Styler.dpInt(context, if (glass) 22f else 24f), Styler.dpInt(context, 8f),
                    Styler.dpInt(context, 12f), Styler.dpInt(context, 2f)
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
                // Glass: the first cover lines up with the label, its 5dp margin inside the 22dp edge.
                val edge = Styler.dpInt(context, if (glass) 17f else 16f)
                setPadding(edge, 0, edge, 0)
                addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
                        val manager = view.layoutManager as? LinearLayoutManager ?: return
                        val row = current ?: return
                        loadMoreReadingRow(row, manager.findLastVisibleItemPosition(), stripAdapter.itemCount)
                    }
                })
            }
            addView(strip, LayoutParams(MATCH, WRAP))
        }

        fun bind(row: ReadingDiscoverRow, first: Boolean) {
            current = row
            featuredRow = first
            val selected = if (first && !glass) DiscoverFeaturePolicy.readingFeature(row, featureWidthDp) else null
            feature.visibility = if (selected == null) View.GONE else View.VISIBLE
            selected?.let { item ->
                feature.bind(item.title, item.subtitle, item.description, api.imageUrl(item.cover),
                    landscape = false, loader = Artwork.loader(api, context))
                feature.setTag(TAG_READING_ITEM, item)
                feature.activateOnTap { openReadingDetail(item) }
                FocusDecorator.listen(feature, ringVisible) { _, focused ->
                    if (focused) { modeStates.recall(ContentMode.BOOKS)?.focusedKey = item.key; host?.refreshHints() }
                }
            }
            label.text = row.title
            stripAdapter.submit(if (selected == null) row.items else row.items.drop(1))
            strip.scrollToPosition(0)
        }

        fun appendOnly(row: ReadingDiscoverRow) {
            current = row
            stripAdapter.submit(if (featuredRow && !glass) DiscoverFeaturePolicy.readingShelf(row, featureWidthDp) else row.items)
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
                // Glass: the prototype's book cards, 82dp covers with their captions under them.
                CardHolder(if (glass) newCard(parent, GLASS_ROW_POSTER_DP, Styler.dpInt(parent.context, GLASS_ROW_CARD_DP), glassCard = true)
                    else newCard(parent, ROW_POSTER_DP, Styler.dpInt(parent.context, ROW_CARD_DP)))

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
            CardHolder(newCard(parent, GRID_POSTER_DP, MATCH, glassCard = glass))

        override fun onBindViewHolder(holder: CardHolder, position: Int) {
            bindReadingCard(holder.itemView as PosterCardView, items[position])
        }

        override fun getItemCount(): Int = items.size
    }

    /** The vertical list of rows. */
    private inner class RowsAdapter : RecyclerView.Adapter<RowHolder>() {
        private val rows = mutableListOf<DiscoverRow>()

        fun focusLanes(width: Int): List<ShelfFocusLane> = rows.flatMapIndexed { index, row ->
            val feature = if (index == 0) DiscoverFeaturePolicy.mediaFeature(row, width) else null
            val id = "media:${row.id}"
            buildList {
                if (feature != null) add(ShelfFocusLane("$id:feature", index, true, listOf(feature.media.key)))
                val items = if (feature != null) row.items.drop(1) else row.items
                if (items.isNotEmpty()) add(ShelfFocusLane(id, index, false, items.map { it.media.key }))
            }
        }

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
            (holder.itemView as PosterRowView).bind(rows[position], position == 0)
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
            } else if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_STATE }) {
                (holder.itemView as PosterRowView).rebindCards()
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
    ) : LinearLayout(context), ShelfFocusRow, com.pocketds.hub.ui.PinnedRowsLayoutManager.Anchor {
        private val feature = DiscoverFeatureCardView(context, colors, ringVisible, glass)

        private val label: TextView
        private val strip: RecyclerView
        override val featureFocusView: View get() = feature
        override val posterFocusList: RecyclerView get() = strip
        override val shelfHeadingView: View get() = label
        /** On the featured card the row's top rests at the top; in the posters, the label does. */
        override fun pinOffset(focused: android.graphics.Rect): Int =
            if (feature.visibility == View.VISIBLE && focused.top < label.top) 0 else label.top
        private val stripAdapter = StripAdapter()
        private var featuredRow = false
        private val featureWidthDp get() = (resources.configuration.screenWidthDp - 180).coerceAtLeast(0)

        init {
            orientation = VERTICAL
            clipChildren = false
            feature.visibility = View.GONE
            addView(feature, LayoutParams(MATCH, WRAP).apply {
                if (glass) setMargins(Styler.dpInt(context, 22f), Styler.dpInt(context, 8f), Styler.dpInt(context, 22f), Styler.dpInt(context, 6f))
                else setMargins(Styler.dpInt(context, 18f), Styler.dpInt(context, 5f),
                    Styler.dpInt(context, 18f), Styler.dpInt(context, 9f))
            })
            label = TextView(context).apply {
                // Glass: a row title as Home's are, bold Figtree.
                if (glass) { textSize = 14f; textWeight(700) }
                else com.pocketds.hub.ui.Type.apply(this, com.pocketds.hub.ui.Type.Role.HEADING, 15f)
                setTextColor(colors.primaryText)
                setPadding(
                    Styler.dpInt(context, if (glass) 22f else 24f), Styler.dpInt(context, 8f),
                    Styler.dpInt(context, 12f), Styler.dpInt(context, 2f)
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
                        loadMoreRow(row, manager.findLastVisibleItemPosition(), stripAdapter.itemCount)
                    }
                })
            }
            addView(strip, LayoutParams(MATCH, WRAP))
        }

        private var current: DiscoverRow? = null

        fun bind(row: DiscoverRow, first: Boolean) {
            current = row
            featuredRow = first
            val selected = if (first) DiscoverFeaturePolicy.mediaFeature(row, featureWidthDp) else null
            feature.visibility = if (selected == null) View.GONE else View.VISIBLE
            selected?.let { hit ->
                val image = hit.media.backdrop.ifBlank { hit.media.poster }
                feature.bind(hit.media.title, hit.subtitle.ifBlank { hit.media.year.takeIf { it > 0 }?.toString().orEmpty() },
                    hit.overview, api.imageUrl(image), hit.media.backdrop.isNotBlank(),
                    Artwork.loader(api, context),
                    mark = com.pocketds.hub.model.Availability.fromWire(RequestedTitles.apply(hit).availability).label.ifEmpty { "Not in your library" })
                feature.setTag(TAG_HIT, hit)
                feature.activateOnTap { openDetail(hit) }
                FocusDecorator.listen(feature, ringVisible) { _, focused ->
                    if (focused) { modeStates.recall(ContentMode.MEDIA)?.focusedKey = hit.media.key; host?.refreshHints() }
                }
            }
            label.text = row.title
            stripAdapter.submit(if (selected == null) row.items else row.items.drop(1))
            strip.scrollToPosition(0)
        }

        fun appendOnly(row: DiscoverRow) {
            current = row
            stripAdapter.submit(if (featuredRow) DiscoverFeaturePolicy.mediaShelf(row, featureWidthDp) else row.items)
        }

        /** Same cards, fresh badges: keeps the strip's scroll and its focused card. */
        fun rebindCards() {
            stripAdapter.notifyItemRangeChanged(0, stripAdapter.itemCount, PAYLOAD_STATE)
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
                    if (glass) newCard(parent, GLASS_ROW_POSTER_DP, Styler.dpInt(parent.context, GLASS_ROW_CARD_DP), glassCard = true, captions = false)
                    else newCard(parent, ROW_POSTER_DP, Styler.dpInt(parent.context, ROW_CARD_DP))
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
            CardHolder(newCard(parent, GRID_POSTER_DP, MATCH, glassCard = glass))

        override fun onBindViewHolder(holder: CardHolder, position: Int) {
            bindCard(holder.itemView as PosterCardView, items[position])
        }

        override fun getItemCount(): Int = items.size
    }

    private class CardHolder(view: View) : RecyclerView.ViewHolder(view)

    private companion object {
        const val TAB_DISCOVER = "discover"
        const val TAB_UPCOMING = "upcoming"
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        /** A poster row: its label and a 118dp poster with two lines under it. */
        const val SHORTEST_ROW_DP = 170f

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

        /** Glass: the prototype's caption-less 82 x 123dp posters, and a row's shortest. */
        const val GLASS_ROW_POSTER_DP = 123f
        const val GLASS_ROW_CARD_DP = 82f
        const val GLASS_SHORTEST_ROW_DP = 160f
        /** How many cards of each row to ask the page colours for when the rows arrive. */
        const val PREFETCH_COLOURS = 12

        /** Start fetching the next page this many cards from the end. */
        const val PREFETCH_AHEAD = 6

        const val PAYLOAD_MORE = "more"
        const val PAYLOAD_STATE = "state"
        const val TAG_HIT = -0x7ffffff5
        const val TAG_READING_ITEM = -0x7ffffff4
    }
}
