package com.pocketds.hub.screens.offline

import com.pocketds.hub.ui.Artwork
import com.pocketds.hub.ui.EpisodeLabel
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.pocketds.hub.ui.ChoiceOverlay
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
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
import com.pocketds.hub.ui.ContinuationCardView
import com.pocketds.hub.ui.DetailArtworkCardView
import com.pocketds.hub.ui.DetailHeaderView
import com.pocketds.hub.ui.DetailLayout
import com.pocketds.hub.ui.DetailSnapshotStore
import com.pocketds.hub.ui.DetailStyler
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.MediaActionIcon
import com.pocketds.hub.ui.MediaActionIconDrawable
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Theme
import com.pocketds.hub.ui.activateOnTap
import com.pocketds.hub.state.Fmt

/** Shared online detail anatomy, backed only by downloaded files and scoped display snapshots. */
class OfflineSeriesScreen(
    private val api: HubApi,
    private val seriesId: String,
    private val seriesTitle: String,
    private val ringVisible: () -> Boolean
) : Screen {
    override val title = seriesTitle
    override val focusOnShow = true
    private lateinit var host: ScreenHost
    private lateinit var colors: PocketColors
    private lateinit var repository: OfflineRepository
    private lateinit var content: LinearLayout
    private lateinit var overlay: ChoiceOverlay
    private lateinit var scroll: ScrollView
    private var header: DetailHeaderView? = null
    private var selectedKey = ""
    private var renderedSignature = ""
    private var receiverRegistered = false
    private val changedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = render()
    }

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        colors = Theme.colors(host.viewContext)
        repository = OfflineRepository.get(host.viewContext)
        content = LinearLayout(host.viewContext).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        scroll = ScrollView(host.viewContext).apply {
            setBackgroundColor(colors.background); isFocusable = false; isFillViewport = true
            clipToPadding = false; clipChildren = false; setPadding(0, 0, 0, dp(18))
            addView(content, ViewGroup.LayoutParams(MATCH, WRAP))
        }
        val root = FrameLayout(host.viewContext)
        root.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        overlay = ChoiceOverlay(host.viewContext, colors, ringVisible, sidePanel = true)
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        render(force = true)
        return root
    }

    override fun onShow() {
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(host.viewContext, changedReceiver,
                IntentFilter(OfflineRepository.ACTION_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
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
        val detail = DetailHeaderView(host.viewContext, colors, ringVisible)
        header = detail
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
        presentation.playable?.let { target ->
            detail.actions.addView(TextView(host.viewContext).apply {
                DetailStyler.action(this, colors, primary = true)
                text = if (target.kind == OfflineCatalogPlayTarget.Kind.RESUME) Fmt.clock(target.positionMillis) else ""
                setCompoundDrawablesRelativeWithIntrinsicBounds(MediaActionIconDrawable(context, MediaActionIcon.PLAY, colors.accentText), null, null, null)
                compoundDrawablePadding = dp(8); setPadding(dp(16), 0, dp(16), 0)
                layoutParams = LinearLayout.LayoutParams(WRAP, dp(48)); tag = TaggedTarget(target)
                contentDescription = "${targetLabel(target)}, ${target.row.manifest.item.title}"
                FocusDecorator.attach(this, ringVisible, scale = false)
                activateOnTap { host.playItem(target.row.manifest.item.id, resumeMode(target)) }
            })
            detail.actions.addView(TextView(host.viewContext).apply {
                DetailStyler.action(this,colors,primary=false)
                text="⋯"; textSize=22f
                setPadding(dp(16), 0, dp(16), 0)
                layoutParams=LinearLayout.LayoutParams(WRAP,dp(48)).apply { marginStart = dp(8) }
                tag=TaggedMore(target)
                contentDescription="More actions for ${target.row.manifest.item.title}"
                FocusDecorator.attach(this,ringVisible,scale=false)
                activateOnTap { openMore(target) }
            })
            content.addView(ContinuationCardView(host.viewContext, colors, ringVisible).apply {
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
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(24), dp(8), dp(24), 0) })
        }
        presentation.missing?.let { item ->
            content.addView(message("Last known next episode: ${episodeCode(item)} · ${item.title}\nNot downloaded. Choose from the available seasons below."))
        }
        if (presentation.playable == null && presentation.missing == null) {
            content.addView(message("You have finished the downloaded episodes. Choose a season to watch again."))
        }
        content.addView(TextView(host.viewContext).apply {
            text = "Downloaded seasons"; textSize = 17f; setTextColor(colors.primaryText)
            setPadding(dp(24), dp(16), dp(24), dp(2))
        })
        content.addView(HorizontalScrollView(host.viewContext).apply {
            isFocusable = false; isHorizontalScrollBarEnabled = false; clipToPadding = false; clipChildren = false
            val clearance = DetailLayout.focusClearance(DetailLayout.posterCardHeight(156, resources.configuration.fontScale)).coerceAtLeast(10)
            setPadding(dp(24), dp(clearance), dp(24), dp(clearance))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; clipChildren = false
                seasons.forEach { addView(seasonCard(it)) }
            })
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        content.post { scroll.scrollTo(0, oldScroll); findTagged(content, selectedKey)?.requestFocus() }
    }

    private fun seasonCard(season: OfflineCatalogSeason): View =
        DetailArtworkCardView(host.viewContext, colors, ringVisible).apply {
            titleView.text = seasonName(season.number)
            subtitleView.text = "${season.rows.size} downloaded episodes"
            tag = TaggedSeason(season)
            layoutParams = LinearLayout.LayoutParams(dp(112), WRAP).apply { marginEnd = dp(12) }
            contentDescription = "${seasonName(season.number)}, ${season.rows.size} downloaded episodes"
            setOnFocusChangeListener { view, focused ->
                FocusDecorator.refresh(view, ringVisible())
                if (focused) { selectedKey = (view.tag as TaggedKey).key; host.refreshHints() }
            }
            activateOnTap { host.push(OfflineSeasonScreen(api, seriesTitle, season, ringVisible)) }
            DetailStyler.image(image, artwork(season.rows.first(), "season"), imageLoader())
        }

    private fun artwork(row: OfflineDownload, preferred: String) = sequenceOf(preferred, "poster", "thumb", "backdrop")
        .map { repository.artworkFile(row, it) }.firstOrNull { it.isFile && it.length() > 0 }
    private fun imageLoader() = Artwork.loader(api, host.viewContext)
    private fun message(text: String) = TextView(host.viewContext).apply {
        this.text = text; textSize = 13f; setTextColor(colors.mutedText); setPadding(dp(24), dp(12), dp(24), dp(8))
    }
    private fun unregister() { if (receiverRegistered) { host.viewContext.unregisterReceiver(changedReceiver); receiverRegistered = false } }
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
    private companion object { const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT; const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT }
}
