package com.pocketds.hub.screens.offline

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import coil.ImageLoader
import coil.request.ImageRequest
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.offline.OfflineCatalogProgress
import com.pocketds.hub.offline.OfflineCatalogSeason
import com.pocketds.hub.offline.OfflineDownload
import com.pocketds.hub.offline.OfflineDownloadService
import com.pocketds.hub.offline.OfflineRepository
import com.pocketds.hub.ui.ChoiceOverlay
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Theme
import com.pocketds.hub.ui.activateOnTap

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
    private var receiverRegistered = false
    private val changedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = render()
    }

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
            row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(2), dp(5), dp(16), dp(80)) }
            addView(HorizontalScrollView(context).apply {
                isFocusable = false; isHorizontalScrollBarEnabled = false; clipToPadding = false; addView(row)
            }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }, FrameLayout.LayoutParams(MATCH, MATCH))
        overlay = ChoiceOverlay(host.viewContext, colors, ringVisible)
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
        if (focusedEpisode() != null) { add(ButtonHint.activate("Play")); add(ButtonHint.primary("Remove")) }
        add(ButtonHint.back())
    }

    override fun onPad(action: PadAction): Boolean {
        if (overlay.onPad(action)) return true
        if (action == PadAction.Refresh) { render(force = true); return true }
        val episode = focusedEpisode() ?: return false
        return when (action) {
            PadAction.Activate -> { host.playItem(episode.manifest.item.id); true }
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
        lateinit var image: ImageView
        return LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.VERTICAL; background = Styler.cardBackground(context, colors, cornerDp = 10f)
            setPadding(dp(5), dp(5), dp(5), dp(6)); tag = TaggedEpisode(download)
            Styler.makeFocusable(this); descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            addView(image, LinearLayout.LayoutParams(MATCH, dp(125)))
            addView(TextView(context).apply {
                text = "E${item.indexNumber} · ${item.title}"; textSize = 13f; maxLines = 1; setTextColor(colors.primaryText)
                setPadding(dp(2), dp(5), dp(2), 0)
            })
            addView(TextView(context).apply {
                text = episodeStatus(item.runtimeSeconds, progress); textSize = 10f; maxLines = 1; setTextColor(colors.mutedText)
                setPadding(dp(2), 0, dp(2), 0)
            })
            layoutParams = LinearLayout.LayoutParams(dp(230), dp(193)).apply { marginEnd = dp(9) }
            contentDescription = "Episode ${item.indexNumber}, ${item.title}, ${episodeStatus(item.runtimeSeconds, progress)}"
            FocusDecorator.attach(this, ringVisible)
            setOnFocusChangeListener { view, hasFocus ->
                FocusDecorator.refresh(view, ringVisible())
                if (hasFocus) { selectedId = download.id; host.refreshHints() }
            }
            activateOnTap { host.playItem(item.id) }
            loadArtwork(image, download)
        }
    }

    private fun confirmRemove(download: OfflineDownload) {
        overlay.show("Remove ${download.manifest.item.title}?", "The local episode and downloaded subtitles will be deleted.",
            listOf(ChoiceOverlay.Choice("keep", "Keep episode"), ChoiceOverlay.Choice("remove", "Remove", danger = true)),
            onCancel = host::refreshHints) {
            if (it == "remove") OfflineDownloadService.remove(host.viewContext, download.id)
            host.refreshHints()
        }
        host.refreshHints()
    }

    private fun loadArtwork(view: ImageView, download: OfflineDownload) {
        view.setImageDrawable(ColorDrawable(colors.posterPlaceholder))
        val local = sequenceOf("thumb", "poster", "backdrop").map { repository.artworkFile(download, it) }
            .firstOrNull { it.isFile && it.length() > 0 } ?: return
        ((api as? HubClient)?.imageLoader ?: ImageLoader(view.context)).enqueue(
            ImageRequest.Builder(view.context).data(local).target(view).bitmapConfig(Bitmap.Config.RGB_565).build()
        )
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
    private fun unregister() {
        if (receiverRegistered) { host.viewContext.unregisterReceiver(changedReceiver); receiverRegistered = false }
    }
    private fun episodeStatus(runtimeSeconds: Int, progress: OfflineCatalogProgress?): String = when {
        progress?.isComplete() == true -> "Watched"
        progress?.resumePosition()?.let { it > 0L } == true -> "Resume · ${time(progress.resumePosition())}"
        runtimeSeconds > 0 -> runtime(runtimeSeconds)
        else -> "Downloaded"
    }
    private fun seasonName(number: Int) = if (number == 0) "Specials" else "Season $number"
    private fun runtime(seconds: Int) = if (seconds >= 3_600) "%dh %02dm".format(seconds / 3_600, seconds % 3_600 / 60) else "%dm".format(seconds / 60)
    private fun time(millis: Long) = "%d:%02d".format(millis / 60_000L, millis / 1_000L % 60L)
    private fun dp(value: Int) = Styler.dpInt(host.viewContext, value.toFloat())
    private data class TaggedEpisode(val row: OfflineDownload)
    private companion object { const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT }
}
