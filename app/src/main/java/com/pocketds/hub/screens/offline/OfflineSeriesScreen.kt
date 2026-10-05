package com.pocketds.hub.screens.offline

import com.pocketds.hub.offline.OfflineChanges
import com.pocketds.hub.ui.FocusHorizontalScrollView
import com.pocketds.hub.ui.FocusScrollView
import com.pocketds.hub.ui.Artwork
import com.pocketds.hub.ui.EpisodeLabel
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.pocketds.hub.ui.ChoiceOverlay
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.offline.OfflineCatalog
import com.pocketds.hub.offline.OfflineCatalogPlayTarget
import com.pocketds.hub.offline.OfflineCatalogSeason
import com.pocketds.hub.offline.OfflineDetailPresentation
import com.pocketds.hub.offline.OfflineDownload
import com.pocketds.hub.offline.OfflineRepository
import com.pocketds.hub.ui.AppIcon
import com.pocketds.hub.ui.CenteredIconTextView
import com.pocketds.hub.ui.ContinuationCardView
import com.pocketds.hub.ui.DetailArtworkCardView
import com.pocketds.hub.ui.DetailHeaderView
import com.pocketds.hub.ui.DetailLayout
import com.pocketds.hub.ui.DetailSnapshotStore
import com.pocketds.hub.ui.DetailStyler
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.FocusPlace
import com.pocketds.hub.ui.MediaActionIcon
import com.pocketds.hub.ui.MediaActionIconDrawable
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.PillButton
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Theme
import com.pocketds.hub.ui.activateOnTap
import com.pocketds.hub.ui.glass.GlassHeading
import com.pocketds.hub.ui.showStatus
import com.pocketds.hub.nav.PageArtwork
import com.pocketds.hub.state.Fmt
import com.pocketds.hub.state.StatusText

/**
 * A downloaded series: the online detail anatomy, backed only by downloaded
 * files and scoped display snapshots.
 *
 * On Glass (#22) it is a series page as Books has one: the cover beside the
 * words, Play or Resume as the white pill with More as a round glass toggle,
 * the next episode as a continue card, notices as quiet chips and the seasons
 * under a glass heading, on a page in the series' colours.
 */
class OfflineSeriesScreen(
    private val api: HubApi,
    private val seriesId: String,
    private val seriesTitle: String,
    private val ringVisible: () -> Boolean
) : Screen {
    override val title = seriesTitle
    override val focusOnShow = true
    /** The header names the series, as a book page's does. */
    override val showsOwnTitle = true
    /** The series' backdrop, as the player asks for it, so its colours are likely known offline. */
    override val pageArtwork: String? get() = PageArtwork.backdrop(seriesId)
    private lateinit var host: ScreenHost
    private lateinit var colors: PocketColors
    private lateinit var repository: OfflineRepository
    private lateinit var content: LinearLayout
    private lateinit var overlay: ChoiceOverlay
    private lateinit var scroll: FocusScrollView
    private var header: DetailHeaderView? = null
    private var selectedKey = ""
    /** The page's own view: where FocusPlace keeps its place. */
    private lateinit var pageView: View
    private var renderedSignature = ""
    private val offlineChanges = OfflineChanges { render() }

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        colors = Theme.colors(host.viewContext)
        repository = OfflineRepository.get(host.viewContext)
        content = LinearLayout(host.viewContext).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        scroll = FocusScrollView(host.viewContext).apply {
            isFillViewport = true
            clipToPadding = false; clipChildren = false; setPadding(0, 0, 0, dp(18))
            addView(content, ViewGroup.LayoutParams(MATCH, WRAP))
        }
        val root = FrameLayout(host.viewContext).also { pageView = it }
        root.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        overlay = ChoiceOverlay(host.viewContext, colors, ringVisible, sidePanel = true)
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        render(force = true)
        return root
    }

    override fun onShow() {
        offlineChanges.start(host.viewContext)
        header?.overview?.collapse()
        render()
        scroll.post { if (scroll.isShown) requestInitialFocus() }
    }
    override fun onHide() {
        ((host.viewContext as? android.app.Activity)?.currentFocus?.tag as? TaggedKey)?.let { selectedKey = it.key }
        overlay.dismiss()
        unregister()
    }
    override fun onDestroyView() = unregister()
    override fun requestInitialFocus(): Boolean =
        findTagged(content, selectedKey)?.requestFocus() == true || firstFocusable(content)?.requestFocus() == true


    override fun hints(): List<ButtonHint> = buildList {
        if (overlay.isOpen) { add(ButtonHint.activate("Choose")); add(ButtonHint.back("Cancel")); return@buildList }
        if (header?.overview?.hasFocus() == true) {
            header?.overview?.actionHint?.let { add(ButtonHint.activate(it)) }
            add(ButtonHint.back(if (header?.overview?.expanded == true) "Collapse description" else "Back"))
            return@buildList
        }
        when ((host.viewContext as? android.app.Activity)?.currentFocus?.tag) {
            is TaggedTarget -> { add(ButtonHint.activate("Play")); add(ButtonHint.secondary("More actions")) }
            is TaggedMore -> add(ButtonHint.activate("More actions"))
            is TaggedSeason -> add(ButtonHint.activate("Open season"))
        }
        add(ButtonHint.back())
    }
    override fun onPad(action: PadAction): Boolean {
        if (overlay.onPad(action)) return true
        if (header?.overview?.onPad(action) == true) return true
        if (action == PadAction.Refresh) { render(force = true); return true }
        if (action == PadAction.Secondary) {
            val target = ((host.viewContext as? android.app.Activity)?.currentFocus?.tag as? TaggedTarget)?.target ?: return false
            openMore(target)
            return true
        }
        if (action != PadAction.Activate) return false
        return when (val tag = (host.viewContext as? android.app.Activity)?.currentFocus?.tag) {
            is TaggedTarget -> { host.playItem(tag.target.row.manifest.item.id, resumeMode(tag.target)); true }
            is TaggedMore -> { openMore(tag.target); true }
            is TaggedSeason -> { host.push(OfflineSeasonScreen(api, seriesTitle, tag.season, ringVisible)); true }
            else -> false
        }
    }

    private fun openMore(target: OfflineCatalogPlayTarget) {
        selectedKey = ((host.viewContext as? android.app.Activity)?.currentFocus?.tag as? TaggedKey)?.key ?: "more"
        showOfflineTitleMenu(host, api, overlay, target.row, ringVisible)
    }

    private fun render(force: Boolean = false) {
        if (!::content.isInitialized) return
        val seasons = OfflineCatalog.seasons(seriesId, repository.completed())
        val rows = seasons.flatMap { it.rows }
        val progress = repository.playbackProgress(rows.map { it.manifest.item.id })
        val snapshot = DetailSnapshotStore.read(host.viewContext, seriesId)
        val signature = snapshot.hashCode().toString() + rows.joinToString("|") { row ->
            val value = progress[row.manifest.item.id]
            "${row.id}:${row.updatedAt}:${value?.positionMillis}:${value?.durationMillis}"
        }
        if (!force && signature == renderedSignature) return
        renderedSignature = signature
        ((host.viewContext as? android.app.Activity)?.currentFocus?.tag as? TaggedKey)?.key?.let { selectedKey = it }
        val oldScroll = scroll.scrollY
        content.removeAllViews()
        // The glass header with the series' cover beside the words, as a
        // series page in Books has it: this page is not drawn under the bar.
        val detail = DetailHeaderView(host.viewContext, colors, ringVisible).apply { book = true }
        header = detail
        // Focus in the header shows all of it: the title stays over the overview (#23).
        scroll.revealWhole = detail
        detail.overview.onChanged = { host.refreshHints() }
        detail.titleView.text = snapshot?.item?.title?.ifBlank { seriesTitle } ?: seriesTitle
        detail.metadataView.text = "${rows.size} downloaded episodes · ${Fmt.bytes(rows.sumOf { it.totalBytes })} · Offline"
        detail.overview.bind(snapshot?.item?.overview.orEmpty())
        detail.bindArtwork("series", null, rows.firstOrNull()?.let { artwork(it, "poster") }, imageLoader())
        content.addView(detail, LinearLayout.LayoutParams(MATCH, WRAP))
        if (rows.isEmpty()) {
            content.addView(message("No episodes from this series remain on the device."))
            return
        }
        val presentation = OfflineDetailPresentation.resolve(rows, progress, snapshot)
        var continueCard: ContinuationCardView? = null
        presentation.playable?.let { target ->
            // The white pill names the episode, as a series' Play does online.
            detail.actions.addView(PillButton.create(host.viewContext, colors, targetLabel(target), AppIcon.PLAY,
                primary = true, heightDp = PILL_DP).apply {
                tag = TaggedTarget(target)
                contentDescription = "${targetLabel(target)}, ${target.row.manifest.item.title}"
                FocusDecorator.attach(this, ringVisible, scale = false)
                activateOnTap { host.playItem(target.row.manifest.item.id, resumeMode(target)) }
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = -dp(PillButton.RING_DP.toInt()); marginEnd = dp(2) })
            detail.actions.addView(CenteredIconTextView(host.viewContext).apply {
                text = ""
                DetailStyler.glassToggle(this, colors)
                setCenteredIcon(MediaActionIconDrawable.onGlass(context, MediaActionIcon.MORE, colors), dp(16))
                tag = TaggedMore(target)
                contentDescription = "More actions for ${target.row.manifest.item.title}"
                FocusDecorator.attach(this, ringVisible, scale = false)
                activateOnTap { openMore(target) }
            }, LinearLayout.LayoutParams(dp(DetailStyler.GLASS_TOGGLE_VIEW_DP), dp(DetailStyler.GLASS_TOGGLE_VIEW_DP)))
            content.addView(ContinuationCardView(host.viewContext, colors, ringVisible).also { continueCard = it }.apply {
                val item = target.row.manifest.item
                bind(targetLabel(target), buildList {
                    add(item.title)
                    if (target.positionMillis > 0) add(Fmt.clock(target.positionMillis))
                    if (presentation.localSuggestion) add("On this device")
                }.joinToString(" · "), if (item.runtimeSeconds > 0) target.positionMillis / (item.runtimeSeconds * 1000.0) else 0.0, false)
                tag = TaggedTarget(target, "continue")
                onFocused = { selectedKey = "continue"; host.refreshHints() }
                DetailStyler.image(image, artwork(target.row, "thumb"), imageLoader())
                activateOnTap { host.playItem(item.id, resumeMode(target)) }
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(EDGE_DP), dp(10), dp(EDGE_DP), dp(2)) })
        }
        presentation.missing?.let { item ->
            content.addView(message("Last known next episode: ${episodeCode(item)} · ${item.title}\nNot downloaded. Choose from the available seasons below."))
        }
        if (presentation.playable == null && presentation.missing == null) {
            content.addView(message("You have finished the downloaded episodes. Choose a season to watch again."))
        }
        content.addView(GlassHeading.create(host.viewContext, "Downloaded seasons", seasons.size.toString()).apply {
            setPadding(dp(EDGE_DP), dp(14), dp(EDGE_DP), 0)
        })
        content.addView(FocusHorizontalScrollView(host.viewContext).apply {
            isHorizontalScrollBarEnabled = false; clipToPadding = false; clipChildren = false
            val clearance = DetailLayout.focusClearance(DetailLayout.posterCardHeight(156, resources.configuration.fontScale)).coerceAtLeast(10)
            setPadding(dp(EDGE_DP), dp(clearance), dp(EDGE_DP), dp(clearance))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; clipChildren = false
                seasons.forEach { addView(seasonCard(it)) }
                // Down from the continue card is the first season (#23).
                continueCard?.downTo(getChildAt(0))
            })
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        // Rebuilt while away (an episode removed on the season page): the place is the new view.
        FocusPlace.mark(pageView, findTagged(content, selectedKey))
        content.post { scroll.scrollTo(0, oldScroll); findTagged(content, selectedKey)?.requestFocus() }
    }

    private fun seasonCard(season: OfflineCatalogSeason): View =
        DetailArtworkCardView(host.viewContext, colors, ringVisible).apply {
            titleView.text = seasonName(season.number)
            subtitleView.text = "${season.rows.size} downloaded episodes"
            tag = TaggedSeason(season)
            layoutParams = LinearLayout.LayoutParams(dp(112), WRAP).apply { marginEnd = dp(12) }
            contentDescription = "${seasonName(season.number)}, ${season.rows.size} downloaded episodes"
            FocusDecorator.listen(this, ringVisible) { view, focused ->
                if (focused) { selectedKey = (view.tag as TaggedKey).key; host.refreshHints() }
            }
            activateOnTap { host.push(OfflineSeasonScreen(api, seriesTitle, season, ringVisible)) }
            DetailStyler.image(image, artwork(season.rows.first(), "season"), imageLoader())
        }

    private fun artwork(row: OfflineDownload, preferred: String) = sequenceOf(preferred, "poster", "thumb", "backdrop")
        .map { repository.artworkFile(row, it) }.firstOrNull { it.isFile && it.length() > 0 }
    private fun imageLoader() = Artwork.loader(api, host.viewContext)
    /** Something to tell, as the quiet glass chip every page uses for it. */
    private fun message(text: String) = TextView(host.viewContext).apply {
        textSize = 12f
        showStatus(StatusText.notice(text), colors)
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(EDGE_DP), dp(12), dp(EDGE_DP), dp(4)) }
    }
    private fun unregister() = offlineChanges.stop()
    private fun findTagged(root: ViewGroup, key: String): View? {
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            if ((child.tag as? TaggedKey)?.key == key) return child
            if (child is ViewGroup) findTagged(child, key)?.let { return it }
        }
        return null
    }
    private fun firstFocusable(root: ViewGroup): View? {
        findTagged(root, "play")?.let { return it }
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            if (child.tag is TaggedSeason) return child
            if (child is ViewGroup) firstFocusable(child)?.let { return it }
        }
        return null
    }
    private fun targetLabel(target: OfflineCatalogPlayTarget) = when (target.kind) {
        OfflineCatalogPlayTarget.Kind.RESUME -> "Resume ${episodeCode(target.row.manifest.item)}"
        else -> "Play ${episodeCode(target.row.manifest.item)}"
    }
    private fun resumeMode(target: OfflineCatalogPlayTarget) = if (target.kind == OfflineCatalogPlayTarget.Kind.RESUME) "resume" else "restart"
    private fun episodeCode(item: com.pocketds.hub.model.LibraryItem) =
        EpisodeLabel.code(item.seasonNumber, item.indexNumber).ifEmpty { item.title }
    private fun seasonName(number: Int) = EpisodeLabel.season(number)
    private fun dp(value: Int) = Styler.dpInt(host.viewContext, value.toFloat())
    private sealed interface TaggedKey { val key: String }
    private data class TaggedTarget(val target: OfflineCatalogPlayTarget, override val key: String = "play") : TaggedKey
    private data class TaggedMore(val target: OfflineCatalogPlayTarget) : TaggedKey { override val key = "more" }
    private data class TaggedSeason(val season: OfflineCatalogSeason) : TaggedKey { override val key = "season:${season.key}" }
    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        /** The page's edge, as a title or book page has it, and its pills' height. */
        const val EDGE_DP = 22
        const val PILL_DP = 31f
    }
}
