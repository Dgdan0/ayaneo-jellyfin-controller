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
import com.pocketds.hub.state.Fmt

/** One locally available season, mirroring the online horizontal episode page. */
class OfflineSeasonScreen(
    private val api: HubApi,
    private val seriesTitle: String,
    private val season: OfflineCatalogSeason,
    private val ringVisible: () -> Boolean
) : Screen {
    override val title = "${seriesTitle} · ${seasonName(season.number)}"
    override val focusOnShow = true

    private lateinit var host: ScreenHost
    private lateinit var colors: PocketColors
    private lateinit var repository: OfflineRepository
    private lateinit var row: LinearLayout
    private lateinit var summary: TextView
    private lateinit var overlay: ChoiceOverlay
    private var selectedId = ""
    private var renderedSignature = ""
    private val offlineChanges = OfflineChanges { render() }

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        colors = Theme.colors(host.viewContext)
        repository = OfflineRepository.get(host.viewContext)
        val root = FrameLayout(host.viewContext).apply { setBackgroundColor(colors.background) }
        root.addView(LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(11), dp(18), 0)
            addView(TextView(context).apply {
                text = seasonName(season.number); textSize = 23f; maxLines = 1; setTextColor(colors.primaryText)
            })
            summary = TextView(context).apply {
                textSize = 11f; setTextColor(colors.mutedText); setPadding(0, dp(3), 0, dp(7))
            }
            addView(summary)
            row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false
                setPadding(dp(10), dp(10), dp(16), dp(16))
            }
            addView(FocusHorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                clipToPadding = false; clipChildren = false; addView(row)
            }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }, FrameLayout.LayoutParams(MATCH, MATCH))
        overlay = ChoiceOverlay(host.viewContext, colors, ringVisible)
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        render(force = true)
        return root
    }

    override fun onShow() {
        offlineChanges.start(host.viewContext)
        render(force = true)
    }

    override fun onHide() {
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
        ((host.viewContext as? android.app.Activity)?.currentFocus?.tag as? TaggedEpisode)?.row?.id?.let { selectedId = it }
        row.removeAllViews()
        summary.text = "${currentRows.size} downloaded episode${if (currentRows.size == 1) "" else "s"} · available offline"
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
            onFocused = { selectedId = download.id; host.refreshHints() }
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
