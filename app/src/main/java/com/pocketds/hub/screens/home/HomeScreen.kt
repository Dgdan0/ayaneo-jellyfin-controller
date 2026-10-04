package com.pocketds.hub.screens.home

import com.pocketds.hub.ui.Artwork
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.HorizontalMode
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.DiscoverRow
import com.pocketds.hub.model.HomeResponse
import com.pocketds.hub.model.JellyfinUser
import com.pocketds.hub.model.LibraryItem
import com.pocketds.hub.model.SearchHit
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ContentModeScreen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.screens.library.LibraryDetailScreen
import com.pocketds.hub.settings.HubSettings
import com.pocketds.hub.settings.ContentModeSettings
import com.pocketds.hub.state.ContentMode
import com.pocketds.hub.state.JobSlot
import com.pocketds.hub.ui.LandscapeCardView
import com.pocketds.hub.ui.pinFocusedRows
import com.pocketds.hub.ui.activateOnTap
import com.pocketds.hub.ui.ChoiceOverlay
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.PosterCardView
import com.pocketds.hub.ui.SidePanelView
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Theme
import com.pocketds.hub.ui.Type
import com.pocketds.hub.ui.textWeight
import com.pocketds.hub.ui.typeRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.pocketds.hub.state.StatusMessage
import com.pocketds.hub.state.StatusText
import com.pocketds.hub.ui.showStatus

/**
 * Home: a hero for whatever card is focused, over rows of your Jellyfin titles
 * (continue watching, next up, recently added, favourites).
 *
 * The hero is the streaming-app shape the redesign settled on. It changes the
 * moment focus moves, from what the card already knows, and fills in the
 * overview once the item's details arrive. Play and Details sit in it; Up from
 * the first row reaches them, and Down goes back to the card you came from.
 */
class HomeScreen(
    private val api: HubApi,
    private val ringVisible: () -> Boolean
) : Screen, ContentModeScreen {

    override val title = "Home"
    override val horizontalMode = HorizontalMode.CONFINED
    override val drawsUnderTopBar: Boolean get() = mode == ContentMode.MEDIA
    override val showsOwnTitle = true
    /** The focused card's artwork, which the hero is already showing; on Books Home, the cover in focus. */
    override val pageArtwork: String? get() = when {
        mode == ContentMode.BOOKS -> if (::readingHome.isInitialized) readingHome.pageArtwork else null
        ::hero.isInitialized -> hero.content?.backdrop
        else -> null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val adapter = RowsAdapter()
    private lateinit var colors: PocketColors
    private lateinit var status: TextView
    private lateinit var hero: HomeHeroView
    private lateinit var rows: RecyclerView
    /** Classic's list of names, or Glass's card of profile tiles (ProfilePickerView). */
    private lateinit var userOverlay: SidePanelView
    /** The Glass home (GLASS_PLAN.md): the prototype's hero, tiles, posters and profile card. */
    private var glass = false
    private lateinit var mediaContent: FrameLayout
    private lateinit var readingHome: ReadingHomeView
    private var mode = ContentMode.MEDIA
    private var host: ScreenHost? = null
    private var loadJob: Job? = null
    private var userJob: Job? = null
    private var returnRefreshJob: Job? = null
    private var heroJob: Job? = null
    private val playSlot = JobSlot()
    private var users: List<JellyfinUser> = emptyList()
    private var loadGeneration = 0
    private var wantsFocus = false
    private var selectedRowId = ""
    private var selectedItemId = ""
    private var selectedItemPosition = 0
    /** The card the hero is showing, and the row it came from. */
    private var heroHit: SearchHit? = null
    private var heroRow: DiscoverRow? = null
    /** Overviews and runtimes already fetched for the hero, by Jellyfin item id. */
    private val heroDetails = HashMap<String, LibraryItem>()
    /** The hub's own rows, and the ones Home builds itself (Coming up, a library's newest). */
    private var hubRows: List<DiscoverRow> = emptyList()
    private val extraRows = LinkedHashMap<String, DiscoverRow>()
    private var extrasJob: Job? = null

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        colors = Theme.colors(host.viewContext)
        glass = Theme.onGlass(colors)
        mode = ContentModeSettings.get(host.viewContext)
        val frame = FrameLayout(host.viewContext).apply { setBackgroundColor(colors.background) }

        mediaContent = FrameLayout(host.viewContext).apply {
            visibility = if (mode == ContentMode.MEDIA) View.VISIBLE else View.GONE
            clipChildren = false
        }
        frame.addView(mediaContent, FrameLayout.LayoutParams(MATCH, MATCH))

        hero = HomeHeroView(host.viewContext, colors, api, ringVisible, glass).apply {
            visibility = View.INVISIBLE
            onPlay = ::playHero
            onDetails = { heroHit?.let(::open) }
            onButtonFocused = { host.refreshHints() }
        }
        mediaContent.addView(hero, FrameLayout.LayoutParams(MATCH, dp(if (glass) HomeHeroView.GLASS_HEIGHT_DP else HERO_DP)))

        status = TextView(host.viewContext).apply {
            textSize = 12f
            setTextColor(colors.mutedText)
            gravity = Gravity.END
        }
        mediaContent.addView(status, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP).apply {
            topMargin = dp(if (glass) HomeHeroView.GLASS_TOP_DP else HomeHeroView.TOP_DP); leftMargin = dp(24); rightMargin = dp(24)
        })

        rows = RecyclerView(host.viewContext).apply {
            adapter = this@HomeScreen.adapter
            // The focused row always rests at the top, so the hero above it
            // stays one size whichever row you are on.
            pinFocusedRows(if (glass) GLASS_SHORTEST_ROW_DP else SHORTEST_ROW_DP)
            clipChildren = false
            setItemViewCacheSize(8)
            addOnChildAttachStateChangeListener(
                object : RecyclerView.OnChildAttachStateChangeListener {
                    override fun onChildViewAttachedToWindow(view: View) {
                        if (!wantsFocus) return
                        val target = this@HomeScreen.adapter.rowIndex(selectedRowId)
                            .takeIf { it >= 0 } ?: 0
                        if (getChildAdapterPosition(view) != target) return
                        wantsFocus = false
                        (view as? PosterRowView)?.focusItem(selectedItemId, selectedItemPosition)
                    }

                    override fun onChildViewDetachedFromWindow(view: View) = Unit
                }
            )
        }
        // Rows that scroll up are clipped at the list's top edge rather than
        // drawn over the hero's words: this frame clips, the list does not.
        val rowsFrame = FrameLayout(host.viewContext).apply { addView(rows, FrameLayout.LayoutParams(MATCH, MATCH)) }
        mediaContent.addView(rowsFrame, FrameLayout.LayoutParams(MATCH, MATCH).apply {
            topMargin = dp(if (glass) GLASS_ROWS_TOP_DP else ROWS_TOP_DP)
        })
        // Glass rows run straight onto the page and are cut by the hero's faded
        // foot and the hint bar, as the prototype's are: a fade to the page
        // colour would lay a dark band over the artwork's colours there.
        if (!glass) {
            // What scrolls up out of the rows fades instead of leaving a sliver of
            // the row above. Only while a row is cut at the top edge: at rest the
            // focused row starts exactly there, and the fade would dim its heading.
            val topFade = View(host.viewContext).apply {
                background = com.pocketds.hub.ui.ScrimDrawable(colors, com.pocketds.hub.ui.ScrimDrawable.Edge.TOP, listOf(0f to 1f, 1f to 0f))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                visibility = View.INVISIBLE
            }
            mediaContent.addView(topFade, FrameLayout.LayoutParams(MATCH, dp(18), Gravity.TOP).apply { topMargin = dp(ROWS_TOP_DP) })
            rows.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
                    val cut = (0 until view.childCount).map(view::getChildAt).firstOrNull { it.bottom > 0 }?.let { it.top < 0 } == true
                    topFade.visibility = if (cut) View.VISIBLE else View.INVISIBLE
                }
            })
            // The next row's heading peeks in under a fade rather than being cut.
            mediaContent.addView(View(host.viewContext).apply {
                background = com.pocketds.hub.ui.ScrimDrawable(colors, com.pocketds.hub.ui.ScrimDrawable.Edge.BOTTOM, listOf(0f to 1f, 1f to 0f))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(MATCH, dp(26), Gravity.BOTTOM))
        }

        readingHome = ReadingHomeView(host.viewContext, api, host, colors, ringVisible).apply {
            onChooseProfile = { if (users.isEmpty()) loadUsers(openWhenReady = true) else showUsers() }
            visibility = if (mode == ContentMode.BOOKS) View.VISIBLE else View.GONE
        }
        frame.addView(readingHome, FrameLayout.LayoutParams(MATCH, MATCH))

        userOverlay = if (glass) ProfilePickerView(host.viewContext, colors, ringVisible) else ChoiceOverlay(host.viewContext, colors, ringVisible)
        frame.addView(userOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
        return frame
    }

    override fun onShow() {
        if (users.isEmpty() && userJob?.isActive != true) loadUsers(openWhenReady=false)
        val stored = host?.viewContext?.let(ContentModeSettings::get) ?: mode
        if (stored != mode) {
            switchMode(stored, persist = false)
            return
        }
        if (mode == ContentMode.BOOKS) {
            readingHome.onShow()
            readingHome.requestInitialFocus()
            return
        }
        if (adapter.itemCount == 0 && loadJob?.isActive != true) load()
        else {
            requestInitialFocus()
            returnRefreshJob?.cancel()
            returnRefreshJob = scope.launch {
                delay(RETURN_REFRESH_DELAY_MILLIS)
                load(force = true)
                returnRefreshJob = null
            }
        }
    }

    override fun onHide() {
        loadGeneration++
        if (::readingHome.isInitialized) readingHome.onHide()
        if (::userOverlay.isInitialized && userOverlay.isOpen) userOverlay.dismiss()
        scope.coroutineContext.cancelChildren()
        loadJob = null
        userJob = null
        returnRefreshJob = null
        heroJob = null
        extrasJob = null
    }

    override fun onDestroyView() {
        if (::readingHome.isInitialized) readingHome.destroy()
        scope.cancel()
        host = null
    }

    override fun hints() = if (::userOverlay.isInitialized && userOverlay.isOpen) {
        listOf(ButtonHint.activate("Choose"), ButtonHint.back("Cancel"))
    } else if (mode == ContentMode.BOOKS) readingHome.hints() else if (heroHasFocus()) {
        // The pill's own words, so A says "Play S2E1" where the Glass pill does.
        listOf(ButtonHint.activate(if (hero.play.isFocused) hero.play.text.toString() else "Details"),
            ButtonHint.secondary("Profiles"), ButtonHint.refresh())
    } else {
        listOfNotNull(
            ButtonHint.activate("Details"),
            focusedHit()?.takeIf { it.jellyfinItemId.isNotEmpty() }
                ?.let { ButtonHint.primary(if (it.progress > 0 && !it.played) "Resume" else "Play") },
            ButtonHint.secondary("Profiles"),
            ButtonHint.refresh()
        )
    }

    override fun onPad(action: PadAction): Boolean {
        if (::userOverlay.isInitialized && userOverlay.onPad(action)) return true
        if (mode == ContentMode.BOOKS) return readingHome.onPad(action)
        return when (action) {
            PadAction.Activate -> focusedHit()?.let(::open) != null
            PadAction.Primary -> focusedHit()?.takeIf { it.jellyfinItemId.isNotEmpty() }?.let { play(it) } != null
            PadAction.Secondary -> {
                if (users.isEmpty()) loadUsers(openWhenReady = true) else showUsers()
                true
            }
            PadAction.Refresh -> {
                load(force = true)
                true
            }
            // Down from Play or Details returns to the card the hero is showing,
            // not whichever card happens to sit below the button; Up from the
            // first row always lands on Play.
            is PadAction.Step -> when {
                action.direction == Direction.DOWN && heroHasFocus() -> requestInitialFocus()
                action.direction == Direction.UP && inFirstRow() -> hero.play.requestFocus()
                else -> false
            }
            else -> false
        }
    }

    private fun heroHasFocus() = ::hero.isInitialized && hero.hasFocus()

    private fun inFirstRow(): Boolean {
        if (!::rows.isInitialized || hero.visibility != View.VISIBLE) return false
        val focused = rows.findFocus() ?: return false
        return rows.findContainingItemView(focused)?.let(rows::getChildAdapterPosition) == 0
    }

    override fun requestInitialFocus(): Boolean {
        if (mode == ContentMode.BOOKS) return readingHome.requestInitialFocus()
        if (!::rows.isInitialized || adapter.itemCount == 0) {
            wantsFocus = true
            return false
        }
        val rowIndex = adapter.rowIndex(selectedRowId).takeIf { it >= 0 } ?: 0
        wantsFocus = true
        rows.scrollToPosition(rowIndex)
        rows.post {
            val row = rows.findViewHolderForAdapterPosition(rowIndex)?.itemView as? PosterRowView
            if (row != null) {
                wantsFocus = false
                row.focusItem(selectedItemId, selectedItemPosition)
            }
        }
        return true
    }

    private fun switchMode(next: ContentMode, persist: Boolean = true) {
        if (persist) host?.viewContext?.let { ContentModeSettings.set(it, next) }
        if (next == mode) return
        if (mode == ContentMode.BOOKS) readingHome.onHide()
        else {
            loadGeneration++
            loadJob?.cancel()
            returnRefreshJob?.cancel()
        }
        mode = next
        mediaContent.visibility = if (next == ContentMode.MEDIA) View.VISIBLE else View.GONE
        readingHome.visibility = if (next == ContentMode.BOOKS) View.VISIBLE else View.GONE
        // Media runs under the see-through tabs; books start below them.
        host?.refreshChrome()
        if (next == ContentMode.BOOKS) readingHome.onShow()
        else if (adapter.itemCount == 0) load() else requestInitialFocus()
        host?.refreshHints()
    }

    override fun selectContentMode(mode: ContentMode) = switchMode(mode)

    private fun loadUsers(openWhenReady: Boolean) {
        userJob?.cancel()
        if (openWhenReady) {
            status.setTextColor(colors.mutedText)
            status.text = "Loading Jellyfin users…"
        }
        userJob = scope.launch {
            when (val result = api.users()) {
                is HubResult.Ok -> {
                    users = result.value.users
                    val selected = users.firstOrNull { it.selected }
                        ?: users.firstOrNull { it.id == HubSettings.userId(status.context) }
                    readingHome.setUserName(selected?.name)
                    if (selected == null) {
                        status.text = "Choose a profile with Y"
                        status.contentDescription = "Choose a Jellyfin profile with Y"
                    } else if (openWhenReady) status.text = ""
                    if (openWhenReady) showUsers()
                }
                is HubResult.Failed -> if (openWhenReady) {
                    status.setTextColor(colors.dangerText)
                    status.text = result.message
                    host?.notify(result.message)
                }
            }
            userJob = null
            host?.refreshHints()
        }
    }

    private fun showUsers() {
        if (users.isEmpty()) {
            host?.notify("No enabled Jellyfin users were found")
            return
        }
        (userOverlay as? ProfilePickerView)?.let { picker ->
            picker.show(users, onCancel = { host?.refreshHints() }) { picked ->
                host?.selectJellyfinUser(picked.id, picked.name)
            }
            host?.refreshHints()
            return
        }
        val selectedIndex = users.indexOfFirst { it.selected }.coerceAtLeast(0)
        (userOverlay as ChoiceOverlay).show(
            title = "Who is watching?",
            subtitle = "Continue Watching, Next Up and progress use this profile.",
            choices = users.map {
                ChoiceOverlay.Choice(
                    it.id,
                    it.name,
                    if (it.selected) "Current profile" else ""
                )
            },
            startIndex = selectedIndex,
            onCancel = { host?.refreshHints() }
        ) { pickedID ->
            val picked = users.firstOrNull { it.id == pickedID } ?: return@show
            host?.selectJellyfinUser(picked.id, picked.name)
        }
        host?.refreshHints()
    }

    private fun load(force: Boolean = false) {
        loadGeneration++
        val generation = loadGeneration
        loadJob?.cancel()
        status.showStatus(StatusText.loading("your Jellyfin home", refreshing = force), colors)
        wantsFocus = adapter.itemCount == 0
        loadExtras(generation)
        loadJob = scope.launch {
            when (val result = api.home()) {
                is HubResult.Ok -> {
                    if (generation != loadGeneration) return@launch
                    render(result.value)
                }
                is HubResult.Failed -> {
                    if (generation != loadGeneration) return@launch
                    status.showStatus(
                        StatusText.failed(result.message, result.kind, hasData = adapter.itemCount > 0), colors
                    )
                    status.visibility = View.VISIBLE
                    host?.refreshHints()
                }
            }
            loadJob = null
        }
    }

    private fun render(body: HomeResponse) {
        hubRows = if (body.partial.isNotEmpty()) HomeRows.merge(body.rows, hubRows) else body.rows
        submitRows()
        // Fresh, complete rows need no line at all; only a caveat earns one.
        val unavailable = body.partial.map { it.service }
        status.showStatus(
            when {
                body.rows.isEmpty() && adapter.itemCount == 0 -> StatusMessage("Nothing to continue or show yet.")
                StatusText.caveat(body.cache, unavailable).text.isEmpty() -> StatusMessage("")
                else -> StatusText.loaded("${adapter.itemCount} rows", body.cache, unavailable)
            },
            colors
        )
        status.visibility = if (status.text.isNullOrBlank()) View.GONE else View.VISIBLE
        // The hero shows the remembered card, or the first one, before any focus lands.
        val row = adapter.row(selectedRowId) ?: adapter.row(0)
        val hit = row?.items?.firstOrNull { it.jellyfinItemId == selectedItemId } ?: row?.items?.firstOrNull()
        if (row != null && hit != null) showHero(row, hit) else hero.bind(null)
        requestInitialFocus()
        host?.refreshHints()
    }

    private fun submitRows() {
        val context = host?.viewContext ?: return
        adapter.submit(HomeRows.ordered(hubRows + extraRows.values,
            com.pocketds.hub.settings.HomeRowSettings.order(context), com.pocketds.hub.settings.HomeRowSettings.hidden(context)))
    }

    /**
     * Coming up and any library rows chosen in Settings › Home, fetched beside
     * the hub's rows and slotted in as they arrive. A failure leaves the row out.
     */
    private fun loadExtras(generation: Int) {
        val context = host?.viewContext ?: return
        val order = com.pocketds.hub.settings.HomeRowSettings.order(context)
        val hidden = com.pocketds.hub.settings.HomeRowSettings.hidden(context)
        extrasJob?.cancel()
        extraRows.keys.retainAll((order - hidden).toSet())
        extrasJob = scope.launch {
            if (HomeRows.UPCOMING in order && HomeRows.UPCOMING !in hidden) launch {
                val today = java.time.LocalDate.now()
                val result = api.calendar(today.toString(), today.plusDays(UPCOMING_DAYS).toString(), java.time.ZoneId.systemDefault().id)
                if (generation != loadGeneration) return@launch
                (result as? HubResult.Ok)?.value?.let { extraRows[HomeRows.UPCOMING] = HomeRows.upcoming(it.items, today); patchRows() }
            }
            val wanted = HomeRows.wantedLibraries(order, hidden)
            if (wanted.isEmpty()) return@launch
            val views = (api.library() as? HubResult.Ok)?.value?.views.orEmpty().associateBy { it.id }
            wanted.forEach { viewId ->
                val view = views[viewId] ?: return@forEach
                launch {
                    val items = (api.libraryItems(viewId, 1, "added", "desc") as? HubResult.Ok)?.value?.items ?: return@launch
                    if (generation != loadGeneration) return@launch
                    val rowId = HomeRows.libraryRowId(viewId)
                    extraRows[rowId] = DiscoverRow(id = rowId, title = HomeRows.libraryRowTitle(view.name), items = items.take(LIBRARY_ROW_SIZE))
                    patchRows()
                }
            }
        }
    }

    /** A late row slots in without moving the focus or the hero off the card you are on. */
    private fun patchRows() {
        if (hubRows.isEmpty()) return
        val focused = rows.findFocus() != null
        submitRows()
        if (focused) requestInitialFocus()
    }

    private fun showHero(row: DiscoverRow, hit: SearchHit) {
        heroRow = row
        heroHit = hit
        val id = hit.jellyfinItemId
        hero.bind(HomeHero.from(row.id, row.title, hit, heroDetails[id]))
        host?.pageArtworkChanged()
        if (id.isEmpty() || heroDetails.containsKey(id)) return
        heroJob?.cancel()
        heroJob = scope.launch {
            // Running along a row asks for nothing until focus rests.
            delay(HERO_DETAIL_DELAY_MILLIS)
            val detail = (api.libraryItem(id) as? HubResult.Ok)?.value?.item ?: return@launch
            heroDetails[id] = detail
            val current = heroHit
            if (current?.jellyfinItemId == id) {
                hero.bind(HomeHero.from(row.id, row.title, current, detail))
                host?.pageArtworkChanged()
            }
        }
    }

    private fun focusedHit(): SearchHit? =
        if (::rows.isInitialized) rows.findFocus()?.getTag(TAG_HIT) as? SearchHit else null

    private fun playHero() {
        heroHit?.let(::play)
    }

    /** A series plays its resume, next or first episode; anything else plays itself. */
    private fun play(hit: SearchHit) {
        val host = host ?: return
        if (hit.jellyfinItemId.isEmpty()) {
            host.notify("This Jellyfin item is no longer available")
            return
        }
        if (hit.media.type != "series") {
            host.playItem(hit.jellyfinItemId)
            return
        }
        playSlot.launch(scope) {
            when (val result = api.seriesPlayTarget(hit.jellyfinItemId)) {
                is HubResult.Ok -> result.value.item.id.takeIf(String::isNotEmpty)?.let(host::playItem)
                    ?: host.notify("Nothing to play in ${hit.media.title} yet")
                is HubResult.Failed -> host.notify(result.message)
            }
        }
    }

    private fun open(hit: SearchHit) {
        if (hit.jellyfinItemId.isEmpty() && hit.media.key.isNotEmpty()) {
            // Coming up: not in the library yet, so its request-side page.
            host?.push(com.pocketds.hub.screens.discover.MediaDetailScreen(api, hit.media.key, hit.media.title, ringVisible))
            return
        }
        if (hit.jellyfinItemId.isEmpty()) {
            host?.notify("This Jellyfin item is no longer available")
            return
        }
        host?.push(
            LibraryDetailScreen(
                api,
                hit.jellyfinItemId,
                hit.media.title,
                hit.media.type,
                ringVisible
            )
        )
    }

    private inner class RowsAdapter : RecyclerView.Adapter<RowHolder>() {
        private val values = mutableListOf<DiscoverRow>()

        init {
            setHasStableIds(true)
        }

        fun rowIndex(rowId: String): Int = values.indexOfFirst { it.id == rowId }
        fun row(rowId: String): DiscoverRow? = values.firstOrNull { it.id == rowId }
        fun row(index: Int): DiscoverRow? = values.getOrNull(index)

        fun submit(next: List<DiscoverRow>) {
            if (next == values) return
            values.clear()
            values.addAll(next)
            notifyDataSetChanged()
        }

        override fun getItemId(position: Int): Long = values[position].id.hashCode().toLong()
        override fun getItemCount() = values.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            RowHolder(PosterRowView(parent.context))

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            (holder.itemView as PosterRowView).bind(values[position])
        }
    }

    private inner class PosterRowView(context: android.content.Context) : LinearLayout(context) {
        private val label: TextView
        private val strip: RecyclerView
        private val stripAdapter = StripAdapter()
        private var row: DiscoverRow? = null
        private var pendingFocus = -1

        init {
            orientation = VERTICAL
            clipChildren = false
            label = TextView(context).apply {
                if (glass) {
                    // The prototype's row title: Figtree, bold, 14.
                    textSize = 14f
                    textWeight(700)
                    setPadding(dp(GLASS_EDGE_DP), dp(4), dp(GLASS_EDGE_DP), dp(2))
                } else {
                    typeRole(Type.Role.HEADING)
                    setPadding(dp(24), dp(6), dp(24), dp(2))
                }
                setTextColor(colors.primaryText)
            }
            addView(label)
            strip = RecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
                adapter = stripAdapter
                isFocusable = false
                clipToPadding = false
                clipChildren = false
                setItemViewCacheSize(8)
                // The first card lines up with the hero's words; the focus lift
                // still has room inside the edge.
                val edge = if (glass) GLASS_EDGE_DP else 24
                setPadding(dp(edge - CARD_GAP_DP / 2), 0, dp(edge - CARD_GAP_DP / 2), 0)
                addOnChildAttachStateChangeListener(
                    object : RecyclerView.OnChildAttachStateChangeListener {
                        override fun onChildViewAttachedToWindow(view: View) {
                            if (pendingFocus < 0 || getChildAdapterPosition(view) != pendingFocus) return
                            pendingFocus = -1
                            view.post { view.requestFocus() }
                        }

                        override fun onChildViewDetachedFromWindow(view: View) = Unit
                    }
                )
            }
            addView(strip, LayoutParams(MATCH, WRAP))
        }

        fun bind(next: DiscoverRow) {
            val changedRow = row?.id != next.id
            row = next
            label.text = next.title
            stripAdapter.submit(next.items)
            if (changedRow) strip.scrollToPosition(0)
        }

        fun focusItem(itemId: String, fallbackPosition: Int) {
            if (stripAdapter.itemCount == 0) return
            val target = stripAdapter.indexOf(itemId).takeIf { it >= 0 }
                ?: fallbackPosition.coerceIn(0, stripAdapter.itemCount - 1)
            pendingFocus = target
            strip.scrollToPosition(target)
            strip.post {
                strip.findViewHolderForAdapterPosition(target)?.itemView?.let {
                    pendingFocus = -1
                    it.requestFocus()
                }
            }
        }

        private inner class StripAdapter : RecyclerView.Adapter<CardHolder>() {
            private val items = mutableListOf<SearchHit>()

            init {
                setHasStableIds(true)
            }

            fun indexOf(itemId: String) = items.indexOfFirst { it.jellyfinItemId == itemId }

            fun submit(next: List<SearchHit>) {
                if (items == next) return
                items.clear()
                items.addAll(next)
                notifyDataSetChanged()
            }

            override fun getItemId(position: Int): Long =
                items[position].jellyfinItemId.hashCode().toLong()

            override fun getItemCount() = items.size

            override fun getItemViewType(position: Int): Int =
                if (HomeRows.landscape(row?.id.orEmpty())) CARD_LANDSCAPE else CARD_POSTER

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardHolder {
                val margin = dp(CARD_GAP_DP / 2)
                val card: View = if (viewType == CARD_LANDSCAPE) {
                    LandscapeCardView(parent.context, colors, glass).apply {
                        layoutParams = RecyclerView.LayoutParams(dp(if (glass) GLASS_TILE_DP else LANDSCAPE_CARD_DP), WRAP).apply {
                            setMargins(margin, margin, margin, margin)
                        }
                    }
                } else {
                    PosterCardView(parent.context, colors, if (glass) GLASS_POSTER_DP else POSTER_DP, captions = false, glass = glass).apply {
                        layoutParams = RecyclerView.LayoutParams(dp(if (glass) GLASS_POSTER_CARD_DP else POSTER_CARD_DP), WRAP).apply {
                            setMargins(margin, margin, margin, margin)
                        }
                    }
                }
                FocusDecorator.attach(card, ringVisible)
                return CardHolder(card)
            }

            override fun onBindViewHolder(holder: CardHolder, position: Int) {
                val hit = items[position]
                val card = holder.itemView
                val loader = Artwork.loader(api, card.context)
                when (card) {
                    is PosterCardView -> {
                        card.bind(hit, loader, api::imageUrl, showAvailability = false)
                        if (row?.id == HomeRows.UPCOMING) {
                            val day = hit.subtitle.substringBefore(" · ")
                            if (glass) card.setDayChip(day) else card.setCornerTag(day)
                        }
                    }
                    is LandscapeCardView -> card.bind(hit, loader, api::imageUrl)
                }
                card.setTag(TAG_HIT, hit)
                // The page's colours for this card, asked for before focus gets here.
                host?.prefetchArtwork(listOf(HomeHero.backdrop(hit, heroDetails[hit.jellyfinItemId])).filter(String::isNotBlank))
                card.activateOnTap { open(hit) }
                FocusDecorator.listen(card, ringVisible) { _, focused ->
                    if (focused) {
                        val current = row ?: return@listen
                        selectedRowId = current.id
                        selectedItemId = hit.jellyfinItemId
                        selectedItemPosition = holder.bindingAdapterPosition
                            .takeIf { it != RecyclerView.NO_POSITION }
                            ?: items.indexOfFirst { it.jellyfinItemId == hit.jellyfinItemId }.coerceAtLeast(0)
                        showHero(current, hit)
                        host?.refreshHints()
                    }
                }
            }
        }
    }

    private class RowHolder(view: View) : RecyclerView.ViewHolder(view)
    private class CardHolder(view: View) : RecyclerView.ViewHolder(view)

    private fun dp(value: Int) = Styler.dpInt(requireNotNull(host).viewContext, value.toFloat())

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        /** The hero's art; the rows start over its faded lower edge. */
        const val HERO_DP = 262
        const val ROWS_TOP_DP = 218
        /** A landscape row: its heading, a 16:9 still and two lines under it. */
        const val SHORTEST_ROW_DP = 160f
        const val POSTER_DP = 150f
        const val POSTER_CARD_DP = 100
        const val LANDSCAPE_CARD_DP = 176
        const val CARD_GAP_DP = 12
        /**
         * Glass, the prototype's Pocket home: rows from the hero's 204dp foot,
         * 22dp page edges, 186dp tiles and 82 x 123dp posters.
         */
        const val GLASS_ROWS_TOP_DP = 204
        const val GLASS_EDGE_DP = 22
        const val GLASS_TILE_DP = 186
        const val GLASS_POSTER_CARD_DP = 82
        const val GLASS_POSTER_DP = 123f
        const val GLASS_SHORTEST_ROW_DP = 150f
        const val CARD_POSTER = 0
        const val CARD_LANDSCAPE = 1
        const val TAG_HIT = -0x7fffffe0
        const val RETURN_REFRESH_DELAY_MILLIS = 450L
        const val HERO_DETAIL_DELAY_MILLIS = 220L
        const val UPCOMING_DAYS = 14L
        const val LIBRARY_ROW_SIZE = 20
    }
}
