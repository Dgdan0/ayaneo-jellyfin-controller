package com.pocketds.hub.screens.library

import com.pocketds.hub.offline.OfflineChanges
import com.pocketds.hub.playback.ResumeRules
import com.pocketds.hub.ui.FocusScrollView
import com.pocketds.hub.ui.Artwork
import com.pocketds.hub.ui.EpisodeLabel
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.ImageLoader
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.LibraryItem
import com.pocketds.hub.model.LibraryItemResponse
import com.pocketds.hub.model.LibrarySeasonsResponse
import com.pocketds.hub.model.LibraryStateRequest
import com.pocketds.hub.model.SeriesPlayTargetResponse
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.nav.TopBarView
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubEndpoints
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.playback.PlaybackProgressStore
import com.pocketds.hub.offline.OfflineRepository
import com.pocketds.hub.screens.discover.ReleaseTargetsScreen
import com.pocketds.hub.settings.HubSettings
import com.pocketds.hub.ui.AppIcon
import com.pocketds.hub.ui.BlobSegmentedView
import com.pocketds.hub.ui.CastRowView
import com.pocketds.hub.ui.ChoiceOverlay
import com.pocketds.hub.ui.DetailHeaderView
import com.pocketds.hub.ui.DetailOverviewView
import com.pocketds.hub.ui.DetailStyler
import com.pocketds.hub.ui.DetailActions
import com.pocketds.hub.ui.DetailSnapshotStore
import com.pocketds.hub.ui.FactsGridView
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.FocusHorizontalScrollView
import com.pocketds.hub.ui.MediaActionIcon
import com.pocketds.hub.ui.MediaActionIconDrawable
import com.pocketds.hub.ui.CenteredIconTextView
import com.pocketds.hub.ui.PillButton
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Theme
import com.pocketds.hub.ui.activateOnTap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.pocketds.hub.state.Fmt
import com.pocketds.hub.state.StatusText
import com.pocketds.hub.ui.showStatus

/**
 * A movie, series or episode from Jellyfin: its backdrop and words, Play and
 * the state toggles, then tabs.
 *
 * A series opens on Episodes: its seasons as a row of choices with that
 * season's episodes right under them, the one Play would start marked UP NEXT.
 * Cast is the people as faces; Details is the facts and the file. The tabs
 * switch as focus moves along them.
 */
class LibraryDetailScreen(
    private val api: HubApi,
    private val itemId: String,
    private val fallbackTitle: String,
    private val expectedType: String,
    private val ringVisible: () -> Boolean
) : Screen {
    override val contentDomain = com.pocketds.hub.state.ContentMode.MEDIA
    override val title = fallbackTitle
    override val focusOnShow = true
    override val drawsUnderTopBar = true
    override val showsOwnTitle = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var colors: PocketColors
    private lateinit var scroll: FocusScrollView
    private lateinit var heading: TextView
    private lateinit var originalTitle: TextView
    private lateinit var meta: TextView
    private lateinit var progress: TextView
    private lateinit var overview: DetailOverviewView
    private lateinit var header: DetailHeaderView
    private lateinit var overlay: ChoiceOverlay
    private lateinit var moreAction: TextView
    private var lastFocusKey: String? = null
    private var watchProgressLabel = ""
    private lateinit var status: TextView
    private lateinit var actions: LinearLayout
    private lateinit var playAction: TextView
    private lateinit var restartAction: TextView
    private lateinit var optionsAction: TextView
    private lateinit var watchedAction: TextView
    private lateinit var favoriteAction: TextView
    private lateinit var downloadAction: TextView
    private lateinit var tabs: BlobSegmentedView
    private lateinit var tabRow: View
    private lateinit var episodesPanel: LinearLayout
    private lateinit var seasonBlob: BlobSegmentedView
    private lateinit var episodes: SeasonEpisodesView
    private lateinit var cast: CastRowView
    private lateinit var similar: com.pocketds.hub.ui.PosterStripView
    private var similarHits: List<com.pocketds.hub.model.SearchHit> = emptyList()
    private var similarJob: Job? = null
    private lateinit var facts: FactsGridView
    private var host: ScreenHost? = null
    private var item: LibraryItem? = null
    /** Redraws the download button while a transfer moves; see renderDownload. */
    private val offlineChanges = OfflineChanges { item?.let(::renderDownload) }
    private var itemJob: Job? = null
    private var seasonsJob: Job? = null
    private var targetJob: Job? = null
    private var stateJob: Job? = null
    private var returnRefreshJob: Job? = null
    private var seriesTarget: SeriesPlayTargetResponse? = null
    private var seasonList: List<LibraryItem> = emptyList()
    private val seasonTotals = HashMap<String, Int>()
    private var selectedSeasonId = ""
    private var selectedTab = ""
    /** Until a tab is chosen, the first one wins, including one that arrives late. */
    private var tabChosen = false
    private var staleTargetRetries = 0

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        colors = Theme.colors(host.viewContext)
        val root = FrameLayout(host.viewContext).apply { setBackgroundColor(colors.background) }
        scroll = FocusScrollView(host.viewContext, revealAbove = dp(56)).apply {
            isFillViewport = true; clipChildren = false
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; clipChildren = false
                header = DetailHeaderView(context, colors, ringVisible).apply { topInsetDp = TopBarView.HEIGHT_DP.toInt() }
                heading = header.titleView; heading.text = fallbackTitle
                originalTitle = header.subtitleView; meta = header.metadataView; progress = header.stateView
                overview = header.overview; actions = header.actions
                playAction = PillButton.create(context, colors, "Play", AppIcon.PLAY, primary = true, heightDp = 40f).apply {
                    tag = ACTION_PLAY
                    FocusDecorator.attach(this, ringVisible, scale = false)
                    FocusDecorator.listen(this, ringVisible) { _, focused -> if (focused) scroll.smoothScrollTo(0, 0); host.refreshHints() }
                    activateOnTap { performAction(ACTION_PLAY) }
                }
                watchedAction = actionButton("Mark watched", ACTION_WATCHED)
                favoriteAction = actionButton("Favourite", ACTION_FAVORITE)
                downloadAction = actionButton("Download", ACTION_DOWNLOAD)
                moreAction = actionButton("More actions", ACTION_MORE)
                restartAction = actionButton("Start over", ACTION_RESTART)
                optionsAction = actionButton("Audio & subtitles", ACTION_OPTIONS)
                actions.addView(playAction, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = -dp(PillButton.RING_DP.toInt()); marginEnd = dp(6) })
                listOf(watchedAction, favoriteAction, downloadAction, moreAction, restartAction, optionsAction).forEach { actions.addView(it) }
                actions.visibility = View.GONE
                addView(header, LinearLayout.LayoutParams(MATCH, WRAP))
                status = TextView(context).apply {
                    textSize = 11f; setTextColor(colors.mutedText); setPadding(dp(24), 0, dp(24), dp(4))
                }
                addView(status)
                tabs = BlobSegmentedView(context, colors, ringVisible, BlobSegmentedView.Style.UNDERLINE).apply {
                    textSp = 13f
                    padXDp = 6f
                    growDp = 0f
                    heightDp = 38f
                    followFocus = true
                    onPick = { id -> tabChosen = true; showTab(id) }
                    onOptionFocused = { liftToTabs(); host.refreshHints() }
                }
                tabRow = FrameLayout(context).apply {
                    visibility = View.GONE
                    addView(tabs, FrameLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(18) })
                    // A hairline under the tabs, the full width of the words.
                    addView(View(context).apply {
                        setBackgroundColor(androidx.core.graphics.ColorUtils.setAlphaComponent(colors.primaryText, 0x16))
                    }, FrameLayout.LayoutParams(MATCH, dp(1), android.view.Gravity.BOTTOM).apply { marginStart = dp(24); marginEnd = dp(24) })
                }
                addView(tabRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
                episodesPanel = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL; clipChildren = false; visibility = View.GONE
                    seasonBlob = BlobSegmentedView(context, colors, ringVisible, BlobSegmentedView.Style.PILL).apply {
                        heightDp = 32f
                        onPick = { id -> seasonList.firstOrNull { it.id == id }?.let(::selectSeason) }
                        onOptionFocused = { id -> lastFocusKey = "season:$id"; liftToTabs(); host.refreshHints() }
                    }
                    addView(FocusHorizontalScrollView(context).apply {
                        isHorizontalScrollBarEnabled = false; clipToPadding = false
                        setPadding(dp(24), dp(14), dp(24), dp(2))
                        addView(seasonBlob)
                    }, LinearLayout.LayoutParams(MATCH, WRAP))
                    episodes = SeasonEpisodesView(context, api, colors, ringVisible, scope).apply {
                        onPlay = { episode -> host.playItem(episode.id, if (episode.positionSeconds > 0) "resume" else "restart") }
                        onFocusedEpisode = { lastFocusKey = "episode"; liftToTabs(); host.refreshHints() }
                        onTotal = { seasonId, total -> seasonTotals[seasonId] = total; labelSeasons() }
                    }
                    addView(episodes, LinearLayout.LayoutParams(MATCH, WRAP))
                }
                addView(episodesPanel, LinearLayout.LayoutParams(MATCH, WRAP))
                similar = com.pocketds.hub.ui.PosterStripView(context, colors, ringVisible).apply {
                    visibility = View.GONE
                    onOpen = { hit -> if (hit.jellyfinItemId.isNotEmpty()) host.push(LibraryDetailScreen(api, hit.jellyfinItemId, hit.media.title, hit.media.type, ringVisible)) }
                    onFocused = { lastFocusKey = "similar"; liftToTabs(); host.refreshHints() }
                }
                addView(similar, LinearLayout.LayoutParams(MATCH, WRAP))
                cast = CastRowView(context, colors, ringVisible).apply {
                    visibility = View.GONE
                    onFocused = { lastFocusKey = "cast"; liftToTabs(); host.refreshHints() }
                }
                addView(cast, LinearLayout.LayoutParams(MATCH, WRAP))
                facts = FactsGridView(context, colors, ringVisible).apply {
                    visibility = View.GONE
                    onFocused = { lastFocusKey = "facts"; host.refreshHints() }
                }
                addView(facts, LinearLayout.LayoutParams(MATCH, WRAP))
                addView(View(context), LinearLayout.LayoutParams(MATCH, dp(16)))
            }, ViewGroup.LayoutParams(MATCH, WRAP))
        }
        root.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        // Once the backdrop has scrolled away, the tabs above need solid ground.
        scroll.setOnScrollChangeListener { _, _, y, _, _ -> host.setTopBarOverArtwork(y < dp(24)) }
        overlay = ChoiceOverlay(host.viewContext, colors, ringVisible)
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        overview.onChanged = { host.refreshHints() }
        return root
    }

    override fun onShow() {
        overview.collapse()
        scroll.post { host?.setTopBarOverArtwork(scroll.scrollY < dp(24)) }
        host?.viewContext?.let(offlineChanges::start)
        item?.let(::renderDownload)
        val returning = item != null
        if (!returning && itemJob?.isActive != true) loadItem()
        if (expectedType == "series" && seasonList.isEmpty() && seasonsJob?.isActive != true) loadSeasons()
        if (item?.type == "series" && seriesTarget == null && targetJob?.isActive != true) loadPlayTarget()
        if (returning) {
            // Android can auto-focus the nearest view while the retained page
            // becomes visible. Restore the user's explicit selection.
            header.post { if (header.isShown) requestInitialFocus() }
            applyPendingPlaybackProgress()
            invalidateFinishedSeriesTarget()
            if (episodesPanel.visibility == View.VISIBLE) episodes.refreshAfterPlayback()
            returnRefreshJob?.cancel()
            returnRefreshJob = scope.launch {
                delay(RETURN_REFRESH_DELAY_MILLIS)
                loadItem()
                if (item?.type == "series" || expectedType == "series") {
                    loadSeasons()
                    loadPlayTarget()
                }
                returnRefreshJob = null
            }
        }
    }

    override fun onHide() {
        when {
            episodes.hasFocus() -> lastFocusKey = "episode"
            seasonBlob.hasFocus() -> seasonBlob.focusedId?.let { lastFocusKey = "season:$it" }
            tabs.hasFocus() -> lastFocusKey = "tabs"
            else -> listOf(playAction, watchedAction, favoriteAction, downloadAction, moreAction).firstOrNull { it.hasFocus() }
                ?.let { lastFocusKey = it.tag as? String }
        }
        episodes.cancel()
        scope.coroutineContext.cancelChildren()
        itemJob = null
        seasonsJob = null
        targetJob = null
        stateJob = null
        returnRefreshJob = null
        offlineChanges.stop()
    }

    override fun onDestroyView() { offlineChanges.stop(); scope.cancel(); host = null }

    override fun requestInitialFocus(): Boolean {
        val key = lastFocusKey
        when {
            key == "episode" && episodesPanel.visibility == View.VISIBLE && episodes.focusEpisode() -> return true
            key?.startsWith("season:") == true && episodesPanel.visibility == View.VISIBLE &&
                seasonBlob.focus(key.removePrefix("season:")) -> return true
            key == "tabs" && tabRow.visibility == View.VISIBLE && tabs.focus() -> return true
            key == "cast" && cast.visibility == View.VISIBLE -> cast.first?.let { return it.requestFocus() }
            key == "similar" && similar.visibility == View.VISIBLE && similar.focusFirst() -> return true
            key == "facts" && facts.visibility == View.VISIBLE -> facts.first?.let { return it.requestFocus() }
        }
        listOf(playAction, watchedAction, favoriteAction, downloadAction, moreAction)
            .firstOrNull { it.tag == key && it.visibility == View.VISIBLE && it.isEnabled }
            ?.let { return it.requestFocus() }
        if (::playAction.isInitialized && playAction.visibility == View.VISIBLE && playAction.isEnabled) {
            return playAction.requestFocus()
        }
        return tabRow.visibility == View.VISIBLE && tabs.focus()
    }

    override fun hints(): List<ButtonHint> = buildList {
        if (overlay.isOpen) { add(ButtonHint.activate("Choose")); add(ButtonHint.back("Close menu")); return@buildList }
        if (overview.hasFocus()) {
            overview.actionHint?.let { add(ButtonHint.activate(it)) }
            add(ButtonHint.back(if (overview.expanded) "Collapse description" else "Back"))
            return@buildList
        }
        val value = item
        when {
            episodes.hasFocus() -> {
                val episode = episodes.focusedEpisode
                add(ButtonHint.activate(if ((episode?.positionSeconds ?: 0) > 0) "Resume" else "Play"))
                add(ButtonHint.primary("Episode details"))
                if (!value?.mediaKey.isNullOrEmpty()) add(ButtonHint.secondary("Find releases"))
            }
            seasonBlob.hasFocus() -> {
                add(ButtonHint.activate("Show season"))
                add(ButtonHint.primary("Download season"))
                if (!value?.mediaKey.isNullOrEmpty()) add(ButtonHint.secondary("Find releases"))
            }
            similar.hasFocus() -> add(ButtonHint.activate("Details"))
            tabs.hasFocus() || cast.hasFocus() || facts.hasFocus() -> Unit
            else -> {
                val focusedAction = listOf(playAction, favoriteAction, downloadAction, moreAction, restartAction, optionsAction, watchedAction)
                    .firstOrNull { it.visibility == View.VISIBLE && it.hasFocus() }
                if (focusedAction != null) add(ButtonHint.activate(focusedAction.contentDescription.toString()))
                else when (value?.type) {
                    "movie", "episode" -> add(ButtonHint.activate(if (canResume(value)) "Resume" else "Play"))
                    "series" -> if (seriesTarget != null) add(ButtonHint.activate(playAction.contentDescription.toString()))
                }
                if (value?.type == "movie" || value?.type == "episode") {
                    add(ButtonHint.primary("Audio & subtitles"))
                    add(ButtonHint.secondary("Start over"))
                }
            }
        }
        add(ButtonHint.back())
        add(ButtonHint.refresh())
    }

    override fun onPad(action: PadAction): Boolean {
        if (overlay.onPad(action) || overview.onPad(action)) return true
        return when (action) {
            is PadAction.Step -> step(action.direction)
            PadAction.Activate -> when {
                actions.hasFocus() -> { actions.findFocus()?.performClick(); true }
                else -> false
            }
            PadAction.Primary -> when {
                episodes.hasFocus() -> episodes.focusedEpisode?.let { openEpisode(it) } != null
                seasonBlob.hasFocus() -> focusedSeason()?.let { season ->
                    host?.downloadItem(LibraryItem(id = itemId, type = "series", title = item?.title ?: fallbackTitle), season.id)
                } != null
                item?.type == "movie" || item?.type == "episode" -> {
                    host?.openPlaybackOptions(itemId, if (canResume(item)) "resume" else "restart")
                    true
                }
                else -> false
            }
            PadAction.Secondary -> when {
                episodes.hasFocus() -> selectedSeason()?.let { openReleaseTargets(it, episodes.focusedEpisode?.indexNumber ?: 0) } != null
                seasonBlob.hasFocus() -> focusedSeason()?.let { openReleaseTargets(it, 0) } != null
                item?.type == "movie" || item?.type == "episode" -> {
                    host?.playItem(itemId, "restart")
                    true
                }
                else -> false
            }
            PadAction.Refresh -> {
                loadItem()
                if (item?.type == "series" || expectedType == "series") {
                    loadSeasons()
                    loadPlayTarget()
                    episodes.reload()
                }
                true
            }
            else -> false
        }
    }

    /**
     * The page reads top to bottom: buttons, tabs, the tab's content. Down and
     * Up go to the remembered or chosen thing in the next band rather than
     * whatever happens to sit under the focused button.
     */
    private fun step(direction: Direction): Boolean = when {
        actions.hasFocus() && (direction == Direction.LEFT || direction == Direction.RIGHT) -> {
            moveActionFocus(if (direction == Direction.LEFT) -1 else 1); true
        }
        actions.hasFocus() && direction == Direction.DOWN -> tabRow.visibility == View.VISIBLE && tabs.focus()
        tabs.hasFocus() && direction == Direction.UP -> playAction.takeIf { it.visibility == View.VISIBLE && it.isEnabled }?.requestFocus() ?: false
        tabs.hasFocus() && direction == Direction.DOWN -> focusPanel()
        seasonBlob.hasFocus() && direction == Direction.UP -> tabs.focus()
        seasonBlob.hasFocus() && direction == Direction.DOWN -> episodes.focusEpisode()
        episodes.hasFocus() && direction == Direction.UP -> seasonBlob.focus(selectedSeasonId)
        (cast.hasFocus() || facts.hasFocus() || similar.hasFocus()) && direction == Direction.UP && !factsHasRowAbove() -> tabs.focus()
        else -> false
    }

    /** In the facts grid, Up from a second-row card stays in the grid. */
    private fun factsHasRowAbove(): Boolean {
        if (!facts.hasFocus()) return false
        val focused = facts.findFocus() ?: return false
        val row = focused.parent as? View ?: return false
        return facts.indexOfChild(row) > 0
    }

    /**
     * Moving into the tabs slides the page up until they sit under the top bar,
     * so the season row and its episodes are on screen whole; Play and the
     * toggles bring the backdrop back.
     */
    private fun liftToTabs() {
        if (tabRow.visibility != View.VISIBLE || tabRow.height == 0) return
        val target = (tabRow.top - dp(TopBarView.HEIGHT_DP.toInt() + 4)).coerceAtLeast(0)
        if (scroll.scrollY < target) scroll.post { scroll.smoothScrollTo(0, target) }
    }

    private fun focusPanel(): Boolean = when (selectedTab) {
        TAB_EPISODES -> seasonBlob.focus(selectedSeasonId)
        TAB_CAST -> cast.first?.requestFocus() == true
        TAB_SIMILAR -> similar.focusFirst()
        TAB_DETAILS -> facts.first?.requestFocus() == true
        else -> false
    }

    private fun moveActionFocus(delta: Int) {
        // The row's own order, so moving along it cannot disagree with what is drawn.
        val available = (0 until actions.childCount).map(actions::getChildAt)
            .filter { it.visibility == View.VISIBLE && it.isEnabled && it.isFocusable }
        val current = available.indexOfFirst { it.hasFocus() }
        if (current < 0) return
        available.getOrNull(current + delta)?.requestFocus()
    }

    private fun loadItem() {
        if (itemJob?.isActive == true) return
        status.showStatus(StatusText.loading("details", refreshing = item != null), colors)
        itemJob = scope.launch {
            when (val result = api.libraryItem(itemId)) {
                is HubResult.Ok -> render(result.value)
                is HubResult.Failed -> {
                    status.visibility = View.VISIBLE
                    status.showStatus(StatusText.failed(result.message, result.kind, hasData = item != null), colors)
                }
            }
            itemJob = null
        }
    }

    private fun render(body: LibraryItemResponse) {
        val context = requireNotNull(host).viewContext
        val value = PlaybackProgressStore.applyTo(
            context,
            HubSettings.userId(context),
            body.item
        )
        item = value
        DetailSnapshotStore.saveItem(context, value)
        heading.text = value.title.ifEmpty { fallbackTitle }
        originalTitle.text = value.originalTitle
        originalTitle.visibility = if (
            value.originalTitle.isNotBlank() && !value.originalTitle.equals(value.title, ignoreCase = true)
        ) View.VISIBLE else View.GONE
        meta.text = buildList {
            if (value.type == "episode") add(value.subtitle.ifEmpty { EpisodeLabel.code(value.seasonNumber, value.indexNumber) })
            if (value.year > 0) add(value.year.toString())
            if (value.officialRating.isNotEmpty()) add(value.officialRating)
            if (value.runtimeSeconds > 0) add(Fmt.runtime(value.runtimeSeconds.toLong()))
            if (value.rating > 0) add("★ %.1f".format(value.rating))
            addAll(value.genres.take(3))
        }.filter(String::isNotBlank).let { com.pocketds.hub.ui.Bidi.join(it, "  ·  ") }
        progress.text = buildList {
            val watch = ResumeRules.watchLabel(value.played, value.progress)
            when {
                watch != null -> add(if (ResumeRules.showsWatched(value.played, value.progress)) "✓ $watch" else watch)
                value.unplayedCount > 0 -> add("${value.unplayedCount} unwatched")
            }
            if (canResume(value)) add("Continue at ${Fmt.clock(value.positionSeconds.toLong() * 1_000)}")
            if (value.favorite) add("★ Favourite")
        }.joinToString(" · ")
        progress.visibility = if (progress.text.isNullOrBlank()) View.GONE else View.VISIBLE
        watchProgressLabel = progress.text.toString()
        overview.bind(value.overview)
        // Fresh details need no line; only a caveat earns one.
        status.showStatus(StatusText.caveat(body.cache, body.partial.map { it.service }), colors)
        status.visibility = if (status.text.isNullOrBlank()) View.GONE else View.VISIBLE
        val landscapePath = value.backdrop.ifBlank { if (value.type == "episode") value.thumb else "" }
        header.bindArtwork(value.type, landscapePath.takeIf { it.isNotBlank() }?.let { api.imageUrl(HubEndpoints.sized(it, ART_WIDTH_PX)) },
            value.poster.ifBlank { value.thumb }.takeIf { it.isNotBlank() }?.let(api::imageUrl), imageLoader())
        renderActions(value)
        renderTabs(value)
        if (value.type == "series" && seasonList.isEmpty() && seasonsJob?.isActive != true) loadSeasons()
        if (value.type == "series" && seriesTarget == null && targetJob?.isActive != true) loadPlayTarget()
    }

    /** Episodes for a series; Cast and Details whenever there is something in them. */
    private fun renderTabs(value: LibraryItem) {
        val people = MediaFacts.cast(value)
        cast.bind(people.map { CastRowView.Person(it.id, it.name, it.role, it.image.takeIf(String::isNotBlank)?.let(api::imageUrl)) }, imageLoader())
        facts.bind(MediaFacts.facts(value).map { FactsGridView.Fact(it.label, it.value) })
        if (similarHits.isEmpty() && similarJob?.isActive != true && value.type in setOf("movie", "series")) loadSimilar()
        val options = buildList {
            if (value.type == "series") add(BlobSegmentedView.Option(TAB_EPISODES, "Episodes"))
            if (similarHits.isNotEmpty()) add(BlobSegmentedView.Option(TAB_SIMILAR, "More like this"))
            if (people.isNotEmpty()) add(BlobSegmentedView.Option(TAB_CAST, "Cast"))
            add(BlobSegmentedView.Option(TAB_DETAILS, "Details"))
        }
        val keep = selectedTab.takeIf { id -> tabChosen && options.any { it.id == id } } ?: options.first().id
        if (tabs.optionIds != options.map { it.id }) tabs.setOptions(options, keep)
        tabRow.visibility = View.VISIBLE
        showTab(keep)
    }

    private fun showTab(id: String) {
        selectedTab = id
        tabs.select(id)
        episodesPanel.visibility = if (id == TAB_EPISODES) View.VISIBLE else View.GONE
        cast.visibility = if (id == TAB_CAST) View.VISIBLE else View.GONE
        similar.visibility = if (id == TAB_SIMILAR) View.VISIBLE else View.GONE
        facts.visibility = if (id == TAB_DETAILS) View.VISIBLE else View.GONE
        host?.refreshHints()
    }

    /** More like this arrives after the page; its tab appears when it has something in it. */
    private fun loadSimilar() {
        similarJob = scope.launch {
            val hits = (api.librarySimilar(itemId) as? HubResult.Ok)?.value?.items.orEmpty()
            similarHits = hits
            similar.bind(hits, imageLoader(), api::imageUrl)
            item?.takeIf { hits.isNotEmpty() }?.let(::renderTabs)
        }
    }

    private fun renderActions(value: LibraryItem) {
        val playable = value.type == "movie" || value.type == "episode"
        actions.visibility = if (value.type in setOf("movie", "episode", "series")) View.VISIBLE else View.GONE
        val visibleActions = DetailActions.forType(value.type).visible
        listOf(playAction, restartAction, optionsAction, watchedAction, moreAction, favoriteAction, downloadAction).forEach {
            it.visibility = if (it.tag in visibleActions) View.VISIBLE else View.GONE
        }
        watchedAction.contentDescription = if (value.played) "Mark unwatched" else "Mark watched"
        setActionIcon(watchedAction, if (value.played) MediaActionIcon.WATCHED else MediaActionIcon.UNWATCHED)
        favoriteAction.contentDescription = if (value.favorite) "Remove from favourites" else "Add to favourites"
        setActionIcon(favoriteAction, if (value.favorite) MediaActionIcon.FAVOURITE else MediaActionIcon.NOT_FAVOURITE)
        renderDownload(value)
        playAction.visibility = View.VISIBLE
        playAction.isEnabled = playable || seriesTarget != null
        playAction.alpha = if (playAction.isEnabled) 1f else .55f
        val playLabel = when {
            playable && canResume(value) -> "Resume · ${Fmt.clock(value.positionSeconds.toLong() * 1_000)}"
            playable -> "Play"
            seriesTarget == null -> "Finding next episode…"
            else -> seriesActionLabel(requireNotNull(seriesTarget))
        }
        playAction.text = playLabel
        playAction.contentDescription = playLabel
        restartAction.contentDescription = "Start over"
        optionsAction.contentDescription = "Audio & subtitles"
        host?.refreshHints()
    }

    /** The offline line and the download button: arrow, a filling ring while it transfers, solid when done. */
    private fun renderDownload(value: LibraryItem) {
        val context = host?.viewContext ?: return
        val repository = OfflineRepository.get(context)
        val local = if (value.type == "movie" || value.type == "episode") repository.forItem(value.id) else null
        val downloaded = local?.state == com.pocketds.hub.offline.OfflineState.COMPLETE
        progress.text = listOf(watchProgressLabel, if (downloaded) "Available offline · ${repository.playbackPlan(value.id, "resume")?.subtitleTracks?.size ?: 0} saved subtitle tracks" else if (local != null) "Offline download: ${local.state.wire}" else "")
            .filter(String::isNotBlank).distinct().joinToString(" · ")
        progress.visibility = if (progress.text.isBlank()) View.GONE else View.VISIBLE
        downloadAction.contentDescription = when {
            downloaded -> "Downloaded"
            local != null -> "Download ${local.state.wire}, ${(local.progress * 100).toInt()} percent"
            else -> "Download"
        }
        when {
            downloaded -> setActionIcon(downloadAction, MediaActionIcon.DOWNLOADED)
            local != null -> setActionIcon(downloadAction, MediaActionIcon.DOWNLOADING, local.progress.toFloat())
            else -> setActionIcon(downloadAction, MediaActionIcon.DOWNLOAD)
        }
    }

    private fun loadPlayTarget() {
        if (targetJob?.isActive == true) return
        targetJob = scope.launch {
            when (val result = api.seriesPlayTarget(itemId)) {
                is HubResult.Ok -> {
                    // The description takes focus only because Play was not ready
                    // yet; that is not a choice to keep.
                    val alreadyFocusedContent = actions.hasFocus() || tabs.hasFocus() || episodesPanel.hasFocus() || cast.hasFocus() || facts.hasFocus()
                    if (isFinishedCheckpoint(result.value.item.id)) {
                        // Jellyfin processes Stop asynchronously. Do not offer the just-finished
                        // episode as Resume while the server advances its Next Up state.
                        seriesTarget = null
                        item?.let(::renderActions)
                        status.setTextColor(colors.mutedText)
                        status.text = "Updating next episode…"
                        status.visibility = View.VISIBLE
                        if (staleTargetRetries++ < MAX_STALE_TARGET_RETRIES) {
                            scope.launch {
                                delay(STALE_TARGET_RETRY_MILLIS)
                                loadPlayTarget()
                            }
                        }
                        return@launch
                    }
                    staleTargetRetries = 0
                    val resolved = withPendingPlaybackProgress(result.value)
                    seriesTarget = resolved
                    DetailSnapshotStore.saveTarget(requireNotNull(host).viewContext, itemId, resolved)
                    episodes.setTarget(resolved.item.id)
                    if (selectedSeasonId.isEmpty()) chooseDefaultSeason()
                    item?.let(::renderActions)
                    if (!alreadyFocusedContent) playAction.post { playAction.requestFocus() }
                }
                is HubResult.Failed -> {
                    playAction.isEnabled = false
                    playAction.alpha = .55f
                    playAction.text = "No episode to play"
                    playAction.contentDescription = "No playable episode"
                }
            }
            targetJob = null
        }
    }

    private fun applyPendingPlaybackProgress() {
        val context = host?.viewContext ?: return
        item?.let { current ->
            val resolved = PlaybackProgressStore.applyTo(
                context,
                HubSettings.userId(context),
                current
            )
            if (resolved != current) render(LibraryItemResponse(item = resolved))
        }
        seriesTarget?.let { current ->
            val resolved = withPendingPlaybackProgress(current)
            if (resolved != current) {
                seriesTarget = resolved
                item?.let(::renderActions)
            }
        }
    }

    private fun withPendingPlaybackProgress(target: SeriesPlayTargetResponse): SeriesPlayTargetResponse {
        val context = host?.viewContext ?: return target
        if (isFinishedCheckpoint(target.item.id)) return target
        val resolved = PlaybackProgressStore.applyTo(
            context,
            HubSettings.userId(context),
            target.item
        )
        return if (resolved == target.item) target else target.copy(kind = "resume", item = resolved)
    }

    private fun isFinishedCheckpoint(itemId: String): Boolean {
        val context = host?.viewContext ?: return false
        return PlaybackProgressStore.isRecentlyComplete(context, HubSettings.userId(context), itemId)
    }

    private fun invalidateFinishedSeriesTarget() {
        val current = seriesTarget ?: return
        if (!isFinishedCheckpoint(current.item.id)) return
        seriesTarget = null
        staleTargetRetries = 0
        item?.let(::renderActions)
    }

    private fun performAction(action: String) {
        when (action) {
            ACTION_MORE -> {
                val value = item ?: return
                val choices = DetailActions.forType(value.type).overflow.map { key ->
                    ChoiceOverlay.Choice(key, when (key) {
                        ACTION_RESTART -> "Start over"
                        ACTION_OPTIONS -> "Audio & subtitles"
                        else -> if (value.played) "Mark unwatched" else "Mark watched"
                    })
                } + (if (value.type in setOf("movie", "episode")) listOf(ChoiceOverlay.Choice("subtitles", "Find / update subtitles", "Search providers, rate matches and update saved copies")) else emptyList()) + listOf(
                    ChoiceOverlay.Choice("offline-remove","Remove offline copy","Only this device; keep server media and progress"),
                    ChoiceOverlay.Choice("server-remove","Delete from server…","Review the files before confirming",danger=true))
                overlay.show("More actions", value.title, choices,
                    onCancel = { moreAction.requestFocus(); host?.refreshHints() },
                    onPick = { moreAction.requestFocus(); performAction(it); host?.refreshHints() })
                host?.refreshHints()
            }
            ACTION_PLAY -> {
                val value = item ?: return
                if (value.type == "series") {
                    val target = seriesTarget ?: return
                    host?.playItem(target.item.id, if (target.kind == "resume") "resume" else "restart")
                } else {
                    host?.playItem(value.id, if (canResume(value)) "resume" else "restart")
                }
            }
            ACTION_RESTART -> host?.playItem(itemId, "restart")
            "subtitles" -> host?.push(SubtitleScreen(api,itemId,item?.title.orEmpty(),ringVisible))
            ACTION_OPTIONS -> host?.openPlaybackOptions(itemId, if (canResume(item)) "resume" else "restart")
            ACTION_WATCHED -> item?.let { updateState(played = !it.played) }
            ACTION_FAVORITE -> item?.let { updateState(favorite = !it.favorite) }
            "offline-remove" -> item?.let {removeOfflineVideo(requireNotNull(host),overlay,it)}
            "server-remove" -> host?.push(MediaRemovalScreen(api,"video",itemId,ringVisible))
            ACTION_DOWNLOAD -> item?.let { value ->
                val existing = if (value.type in setOf("movie", "episode")) {
                    OfflineRepository.get(requireNotNull(host).viewContext).forItem(value.id)
                } else null
                if (existing != null) host?.notify("This item is ${existing.state.wire} in Offline")
                else host?.downloadItem(value)
            }
        }
    }

    private fun openEpisode(episode: LibraryItem) {
        lastFocusKey = "episode"
        host?.push(LibraryDetailScreen(api, episode.id, episode.title, "episode", ringVisible))
    }

    private fun updateState(played: Boolean? = null, favorite: Boolean? = null) {
        if (stateJob?.isActive == true) return
        val previous = item ?: return
        if (played != null) {
            val context = requireNotNull(host).viewContext
            PlaybackProgressStore.forget(context, HubSettings.userId(context))
        }
        val optimistic = previous.copy(
            played = played ?: previous.played,
            favorite = favorite ?: previous.favorite,
            progress = if (played == true) 0.0 else previous.progress,
            positionSeconds = if (played == true) 0 else previous.positionSeconds
        )
        render(LibraryItemResponse(item = optimistic))
        status.setTextColor(colors.mutedText)
        status.text = "Saving to Jellyfin…"
        status.visibility = View.VISIBLE
        stateJob = scope.launch {
            when (val result = api.updateLibraryState(
                itemId,
                LibraryStateRequest(played = played, favorite = favorite)
            )) {
                is HubResult.Ok -> {
                    render(result.value)
                    if (result.value.item.type == "series" && played != null) {
                        seriesTarget = null
                        loadPlayTarget()
                        episodes.reload()
                    }
                }
                is HubResult.Failed -> {
                    render(LibraryItemResponse(item = previous))
                    status.setTextColor(colors.dangerText)
                    status.text = result.message
                    host?.notify(result.message)
                }
            }
            stateJob = null
        }
    }

    private fun actionButton(label: String, action: String) = CenteredIconTextView(requireNotNull(host).viewContext).apply {
        text = ""
        textSize = 14f
        DetailStyler.action(this, colors)
        tag = action
        contentDescription = label
        compoundDrawablePadding = 0
        setActionIcon(this, iconForAction(action))
        layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(8) }
        FocusDecorator.attach(this, ringVisible, scale = false)
        FocusDecorator.listen(this, ringVisible) { _, focused -> if (focused) scroll.smoothScrollTo(0, 0); host?.refreshHints() }
        activateOnTap { performAction(action) }
    }

    private fun iconForAction(action: String): MediaActionIcon = when (action) {
        ACTION_RESTART -> MediaActionIcon.START_OVER
        ACTION_OPTIONS -> MediaActionIcon.OPTIONS
        ACTION_WATCHED -> MediaActionIcon.UNWATCHED
        ACTION_FAVORITE -> MediaActionIcon.NOT_FAVOURITE
        ACTION_DOWNLOAD -> MediaActionIcon.DOWNLOAD
        else -> MediaActionIcon.MORE
    }

    private fun setActionIcon(view: TextView, icon: MediaActionIcon, progress: Float = 0f) {
        val drawable = MediaActionIconDrawable.of(view.context, icon, colors, progress)
        if (view is CenteredIconTextView) view.setCenteredIcon(drawable, dp(20))
        else view.setCompoundDrawablesRelativeWithIntrinsicBounds(drawable, null, null, null)
    }

    private fun seriesActionLabel(target: SeriesPlayTargetResponse): String {
        val episode = target.item
        val code = EpisodeLabel.code(episode.seasonNumber, episode.indexNumber).takeIf { it.isNotEmpty() }
            ?.let { " $it" }.orEmpty()
        return when (target.kind) {
            "resume" -> "Resume$code"
            else -> "Play$code"
        }
    }

    // The position was already judged -- by the hub when it saved the stop, or
    // by ResumeRules in the checkpoint applyTo overlaid -- so judging it again
    // with another rule could only disagree with Home's Continue watching row.
    // A watched item with a position is a rewatch, and resumes like Jellyfin's
    // own clients (ResumeRules.showsWatched); "Mark watched" clears the position.
    private fun canResume(value: LibraryItem?): Boolean =
        value != null && value.positionSeconds > 0

    private fun loadSeasons() {
        if (seasonsJob?.isActive == true) return
        seasonsJob = scope.launch {
            when (val result = api.librarySeasons(itemId)) {
                is HubResult.Ok -> renderSeasons(result.value)
                is HubResult.Failed -> {
                    status.visibility = View.VISIBLE
                    status.showStatus(StatusText.failed("Seasons · ${result.message}", result.kind, hasData = false), colors)
                }
            }
            seasonsJob = null
        }
    }

    private fun renderSeasons(body: LibrarySeasonsResponse) {
        val focused = seasonBlob.focusedId
        seasonList = body.items
        if (seasonList.isEmpty()) {
            episodesPanel.visibility = View.GONE
            return
        }
        if (seasonList.none { it.id == selectedSeasonId }) selectedSeasonId = ""
        seasonBlob.setOptions(seasonList.map { BlobSegmentedView.Option(it.id, seasonName(it)) }, selectedSeasonId.ifEmpty { null })
        if (selectedSeasonId.isEmpty()) chooseDefaultSeason() else labelSeasons()
        focused?.let(seasonBlob::focus)
        if (selectedTab == TAB_EPISODES) episodesPanel.visibility = View.VISIBLE
        host?.refreshHints()
    }

    /** The season of the episode Play would start, else the first that is not Specials. */
    private fun chooseDefaultSeason() {
        if (seasonList.isEmpty()) return
        val targetSeason = seriesTarget?.item?.seasonNumber
        val season = seasonList.firstOrNull { it.seasonNumber == targetSeason && targetSeason != null }
            ?: seasonList.firstOrNull { it.seasonNumber > 0 } ?: seasonList.first()
        selectSeason(season)
    }

    private fun selectSeason(season: LibraryItem) {
        selectedSeasonId = season.id
        seasonBlob.select(season.id)
        labelSeasons()
        episodes.show(itemId, season, seriesTarget?.item?.id.orEmpty())
    }

    /** "Season 2", and on the chosen one its count once known: "Season 2 · 10 episodes". */
    private fun labelSeasons() {
        seasonList.forEach { season ->
            val total = seasonTotals[season.id]
            val name = seasonName(season)
            seasonBlob.relabel(season.id, if (season.id == selectedSeasonId && total != null && total > 0)
                "$name · $total episode${if (total == 1) "" else "s"}" else name)
        }
    }

    private fun seasonName(season: LibraryItem) = season.title.ifEmpty { EpisodeLabel.season(season.seasonNumber) }

    private fun selectedSeason() = seasonList.firstOrNull { it.id == selectedSeasonId }
    private fun focusedSeason() = seasonBlob.focusedId?.let { id -> seasonList.firstOrNull { it.id == id } }

    private fun openReleaseTargets(season: LibraryItem, episodeNumber: Int) {
        val value = item ?: return
        if (value.mediaKey.isEmpty()) {
            host?.notify("This series has no TMDB match, so Sonarr releases cannot be linked safely")
            return
        }
        host?.push(
            ReleaseTargetsScreen(
                api, value.mediaKey, value.title, season.seasonNumber,
                season.poster.ifEmpty { season.thumb }, episodeNumber, ringVisible
            )
        )
    }

    private fun imageLoader(): ImageLoader =
        Artwork.loader(api, requireNotNull(host).viewContext)

    private fun dp(value: Int) = Styler.dpInt(requireNotNull(host).viewContext, value.toFloat())

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val ACTION_MORE = "more"
        const val ACTION_PLAY = "play"
        const val ACTION_RESTART = "restart"
        const val ACTION_OPTIONS = "options"
        const val ACTION_WATCHED = "watched"
        const val ACTION_FAVORITE = "favorite"
        const val ACTION_DOWNLOAD = "download"
        const val TAB_EPISODES = "episodes"
        const val TAB_CAST = "cast"
        const val TAB_DETAILS = "details"
        const val TAB_SIMILAR = "similar"
        const val ART_WIDTH_PX = 1920
        const val RETURN_REFRESH_DELAY_MILLIS = 450L
        const val STALE_TARGET_RETRY_MILLIS = 700L
        const val MAX_STALE_TARGET_RETRIES = 3
    }
}
