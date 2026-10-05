package com.pocketds.hub.screens.offline

import com.pocketds.hub.offline.OfflineChanges
import com.pocketds.hub.ui.FocusHorizontalScrollView
import com.pocketds.hub.ui.Artwork
import com.pocketds.hub.ui.EpisodeCardView
import com.pocketds.hub.ui.EpisodeLabel
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.offline.OfflineCatalogProgress
import com.pocketds.hub.offline.OfflineCatalogSeason
import com.pocketds.hub.offline.OfflineDownload
import com.pocketds.hub.offline.OfflineDownloadService
import com.pocketds.hub.offline.OfflineRepository
import com.pocketds.hub.ui.ChoiceOverlay
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Theme
import com.pocketds.hub.ui.Type
import com.pocketds.hub.ui.showStatus
import com.pocketds.hub.ui.showSummary
import com.pocketds.hub.nav.PageArtwork
import com.pocketds.hub.state.Fmt
import com.pocketds.hub.state.StatusText

/**
 * One locally available season, mirroring the online horizontal episode page.
 *
 * On Glass (#22): the season as the page's own heading over a quiet line, the
 * episodes as the glass tiles with the accent tick on a watched one, More on
 * the side sheet as on the series page, and the page in the series' colours.
 */
class OfflineSeasonScreen(
    private val api: HubApi,
    private val seriesTitle: String,
    private val season: OfflineCatalogSeason,
    private val ringVisible: () -> Boolean
) : Screen {
    override val title = "${seriesTitle} · ${seasonName(season.number)}"
    override val focusOnShow = true
    override val showsOwnTitle = true
    /** The series' backdrop, as on the series page, so moving between them keeps the page's colours. */
    override val pageArtwork: String?
        get() = season.rows.firstOrNull()?.manifest?.item?.let { PageArtwork.backdrop(it.id, it.seriesId) }

    private lateinit var host: ScreenHost
    private lateinit var colors: PocketColors
    private lateinit var repository: OfflineRepository
    private lateinit var row: LinearLayout
    private lateinit var summary: TextView
    /** Why the row is empty, once its last episode is removed: the quiet glass chip. */
    private lateinit var empty: TextView
    private lateinit var overlay: ChoiceOverlay
    private var selectedId = ""
    /** Whether the page is in front: focus the host moves while hiding it is not the person's (see OfflineSeriesScreen). */
    private var shown = false
    private var renderedSignature = ""
    private val offlineChanges = OfflineChanges { render() }

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        colors = Theme.colors(host.viewContext)
        repository = OfflineRepository.get(host.viewContext)
        val root = FrameLayout(host.viewContext)
        root.addView(LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.VERTICAL
            // The words on the page's 22dp edge; the row keeps 10dp of it inside
            // its scroller, so the first tile's ring and lift are not cut off.
            setPadding(dp(12), dp(8), dp(12), 0)
            // The page's own heading, as "On your Pocket" is drawn.
            addView(TextView(context).apply {
                text = seasonName(season.number); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                textSize = 21f; typeface = Type.display(context, 800); includeFontPadding = false
                setTextColor(colors.primaryText)
                setPadding(dp(10), 0, dp(10), 0)
            })
            summary = TextView(context).apply { textSize = 12f; setPadding(dp(10), dp(6), dp(10), dp(4)) }
            addView(summary)
            empty = TextView(context).apply { textSize = 12f; visibility = View.GONE }
            addView(empty, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = dp(10); topMargin = dp(10) })
            row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false
                setPadding(dp(10), dp(10), dp(16), dp(16))
            }
            addView(FocusHorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                clipToPadding = false; clipChildren = false; addView(row)
            }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }, FrameLayout.LayoutParams(MATCH, MATCH))
        // More opens as the side sheet, as it does on the series page; removing asks on the centred card.
        overlay = ChoiceOverlay(host.viewContext, colors, ringVisible, sidePanel = true)
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        render(force = true)
        return root
    }

    override fun onShow() {
        offlineChanges.start(host.viewContext)
        // Before it is in front again: what has focus now is not where the person was here.
        render(force = true)
        shown = true
    }

    override fun onHide() {
        focusedEpisode()?.let { selectedId = it.id }
        shown = false
        unregister()
        if (::overlay.isInitialized) overlay.dismiss()
    }
    override fun onDestroyView() = unregister()

    override fun requestInitialFocus(): Boolean =
        findEpisode(row, selectedId)?.requestFocus() == true || firstFocusable(row)?.requestFocus() == true

    override fun hints(): List<ButtonHint> = if (overlay.isOpen) {
        listOf(ButtonHint.activate("Choose"), ButtonHint.back("Cancel"))
    } else buildList {
        if (focusedEpisode() != null) { add(ButtonHint.activate("Play")); add(ButtonHint.secondary("More actions")); add(ButtonHint.primary("Remove")) }
        add(ButtonHint.back())
    }

    override fun onPad(action: PadAction): Boolean {
        if (overlay.onPad(action)) return true
        if (action == PadAction.Refresh) { render(force = true); return true }
        val episode = focusedEpisode() ?: return false
        return when (action) {
            PadAction.Activate -> { host.playItem(episode.manifest.item.id); true }
            PadAction.Secondary -> { showOfflineTitleMenu(host, api, overlay, episode, ringVisible); true }
            PadAction.Primary -> { confirmRemove(episode); true }
            else -> false
        }
    }

    private fun render(force: Boolean = false) {
        if (!::row.isInitialized || overlay.isOpen) return
        val currentRows = repository.completed()
            .filter { it.manifest.item.seriesId == season.rows.firstOrNull()?.manifest?.item?.seriesId }
            .filter { it.manifest.item.seasonId == season.key || it.manifest.item.seasonNumber == season.number }
            .sortedBy { it.manifest.item.indexNumber }
        val progress = repository.playbackProgress(currentRows.map { it.manifest.item.id })
        val signature = currentRows.joinToString("|") { item ->
            val value = progress[item.manifest.item.id]
            "${item.id}:${item.updatedAt}:${value?.positionMillis}:${value?.durationMillis}"
        }
        if (!force && signature == renderedSignature) return
        renderedSignature = signature
        if (shown) ((host.viewContext as? android.app.Activity)?.currentFocus?.tag as? TaggedEpisode)?.row?.id?.let { selectedId = it }
        row.removeAllViews()
        summary.showSummary(StatusText.loaded(listOf(seriesTitle,
            "${currentRows.size} downloaded episode${if (currentRows.size == 1) "" else "s"}", "available offline")
            .filter(String::isNotBlank).joinToString(" · ")), colors)
        empty.showStatus(StatusText.notice(if (currentRows.isEmpty()) "No episodes from this season remain on this device." else ""), colors)
        currentRows.forEach { row.addView(episodeCard(it, progress[it.manifest.item.id])) }
        row.post { findEpisode(row, selectedId)?.requestFocus() }
    }

    private fun episodeCard(download: OfflineDownload, progress: OfflineCatalogProgress?): View {
        val item = download.manifest.item
        val status = episodeStatus(item.runtimeSeconds, progress)
        return EpisodeCardView(host.viewContext, colors, ringVisible).apply {
            tag = TaggedEpisode(download)
            layoutParams = LinearLayout.LayoutParams(dp(EpisodeCardView.WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = dp(9) }
            onFocused = { if (shown) selectedId = download.id; host.refreshHints() }
            // The page's first layout after coming back restores its default focus: this episode.
            isFocusedByDefault = download.id == selectedId
            onActivate = { host.playItem(item.id) }
            // More actions stay on Y and its hint chip, as everywhere else.
            val watched = progress?.takeUnless { it.isComplete() }?.takeIf { it.durationMillis > 0 }
                ?.let { it.positionMillis.toDouble() / it.durationMillis } ?: 0.0
            bind(
                EpisodeCardView.Model(
                    title = EpisodeLabel.of(item.seasonNumber, item.indexNumber, item.title),
                    meta = status,
                    still = localStill(download),
                    progress = watched,
                    overview = item.overview,
                    watched = progress?.isComplete() == true,
                    description = "Episode ${item.indexNumber}, ${item.title}, $status"
                ),
                Artwork.loader(api, context)
            )
        }
    }

    /** The still saved with the download; this screen must work with no hub at all. */
    private fun localStill(download: OfflineDownload): Any? =
        sequenceOf("thumb", "poster", "backdrop").map { repository.artworkFile(download, it) }
            .firstOrNull { it.isFile && it.length() > 0 }

    private fun confirmRemove(download: OfflineDownload) {
        overlay.confirm("Remove ${download.manifest.item.title}?", "The local episode and downloaded subtitles will be deleted.",
            action = "Remove", keep = "Keep episode", onCancel = host::refreshHints) {
            OfflineDownloadService.remove(host.viewContext, download.id)
            host.refreshHints()
        }
        host.refreshHints()
    }


    private fun focusedEpisode(): OfflineDownload? =
        ((host.viewContext as? android.app.Activity)?.currentFocus?.tag as? TaggedEpisode)?.row
    private fun findEpisode(root: ViewGroup, id: String): View? {
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            if ((child.tag as? TaggedEpisode)?.row?.id == id) return child
            if (child is ViewGroup) findEpisode(child, id)?.let { return it }
        }
        return null
    }
    private fun firstFocusable(root: ViewGroup): View? {
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            if (child.isFocusable && child.visibility == View.VISIBLE) return child
            if (child is ViewGroup) firstFocusable(child)?.let { return it }
        }
        return null
    }
    private fun unregister() = offlineChanges.stop()
    private fun episodeStatus(runtimeSeconds: Int, progress: OfflineCatalogProgress?): String = when {
        progress?.isComplete() == true -> "Watched"
        progress?.resumePosition()?.let { it > 0L } == true -> "Resume · ${Fmt.clock(progress.resumePosition())}"
        runtimeSeconds > 0 -> Fmt.runtime(runtimeSeconds.toLong())
        else -> "Downloaded"
    }
    private fun seasonName(number: Int) = EpisodeLabel.season(number)
    private fun dp(value: Int) = Styler.dpInt(host.viewContext, value.toFloat())
    private data class TaggedEpisode(val row: OfflineDownload)
    private companion object { const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT }
}
