package com.pocketds.hub.screens.offline

import com.pocketds.hub.playback.ResumeRules
import com.pocketds.hub.ui.FocusScrollView
import com.pocketds.hub.ui.Artwork
import com.pocketds.hub.ui.EpisodeCardView
import com.pocketds.hub.ui.EpisodeLabel
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.OfflinePrepareBody
import com.pocketds.hub.model.OfflinePrepareItem
import com.pocketds.hub.model.OfflineSelectionItem
import com.pocketds.hub.model.OfflineSelectionResponse
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.PageArtwork
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.offline.OfflineDownloadService
import com.pocketds.hub.offline.OfflineRepository
import com.pocketds.hub.settings.OfflineSettings
import com.pocketds.hub.ui.AppIcon
import com.pocketds.hub.ui.ChoiceOverlay
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.PillButton
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Theme
import com.pocketds.hub.ui.Type
import com.pocketds.hub.ui.activateOnTap
import com.pocketds.hub.ui.glass.GlassColors
import com.pocketds.hub.ui.glass.GlassHeading
import com.pocketds.hub.ui.textWeight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import com.pocketds.hub.state.Fmt
import com.pocketds.hub.state.StatusText
import com.pocketds.hub.ui.showSummary

/**
 * Controller-first episode picker used by series and season download actions.
 *
 * On Glass (#22): the series as the page's heading with the count and Download
 * as the white pill, the page's own line under it, each season under a glass
 * heading over its episode tiles, the quick choices on the side sheet, and the
 * page in the series' colours.
 */
class OfflineSelectionScreen(
    private val api: HubApi,
    private val seriesId: String,
    private val fallbackTitle: String,
    private val seasonId: String = "",
    private val ringVisible: () -> Boolean
) : Screen {
    override val title = "Choose downloads"
    override val focusOnShow = true
    /** The series' picture, the same one its title page shows, so the page keeps its colours. */
    override val pageArtwork: String?
        get() = catalog?.series?.let { PageArtwork.title(it.backdrop, it.poster.ifBlank { it.thumb }) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var host: ScreenHost
    private lateinit var colors: PocketColors
    private lateinit var root: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var counter: TextView
    private lateinit var overlay: ChoiceOverlay
    private val selected = linkedSetOf<String>()
    private val cards = linkedMapOf<String, EpisodeCard>()
    private var catalog: OfflineSelectionResponse? = null
    private var loadJob: Job? = null
    private var prepareJob: Job? = null
    private var initialMenuShown = false

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        colors = Theme.colors(host.viewContext)
        root = FrameLayout(host.viewContext)
        val page = LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.VERTICAL
            // The words on the page's 22dp edge; the rows keep 10dp of it inside
            // themselves, so the first tile's ring and lift are not cut off.
            setPadding(dp(12), dp(6), dp(12), dp(6))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, dp(10), 0)
                // The series, as the release picker names its title under its page's name.
                addView(TextView(context).apply {
                    text = fallbackTitle
                    textSize = 21f; typeface = Type.display(context, 800); includeFontPadding = false
                    setTextColor(colors.primaryText)
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, WRAP, 1f))
                counter = TextView(context).apply {
                    textSize = 12.5f; textWeight(600)
                    setTextColor(GlassColors.FACTS)
                    gravity = Gravity.END
                }
                addView(counter, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(12); marginEnd = dp(10) })
                // The main action, the white pill: what X does from anywhere on the page.
                addView(PillButton.create(context, colors, "Download", AppIcon.DOWNLOAD, primary = true, heightDp = PILL_DP).apply {
                    contentDescription = "Download selected episodes"
                    FocusDecorator.attach(this, ringVisible, scale = false)
                    FocusDecorator.listen(this, ringVisible) { _, _ -> host.refreshHints() }
                    activateOnTap { confirmSelection() }
                }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = -dp(PillButton.RING_DP.toInt()) })
            })
            status = TextView(context).apply { textSize = 12f; setPadding(dp(10), dp(4), dp(10), dp(4)) }
            addView(status)
            content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            addView(FocusScrollView(context).apply {
                clipToPadding = false
                setPadding(0, 0, 0, dp(76))
                addView(content)
            }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
        root.addView(page, FrameLayout.LayoutParams(MATCH, MATCH))
        // The quick choices and the count open as the side sheet, beside the tiles they choose.
        overlay = ChoiceOverlay(host.viewContext, colors, ringVisible, sidePanel = true)
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        updateCounter()
        return root
    }

    override fun onShow() {
        if (catalog == null && loadJob?.isActive != true) load()
    }

    override fun onHide() {
        scope.coroutineContext.cancelChildren(); loadJob = null; prepareJob = null
        if (::overlay.isInitialized) overlay.dismiss()
    }
    override fun onDestroyView() { scope.cancel() }

    override fun hints(): List<ButtonHint> = if (::overlay.isInitialized && overlay.isOpen) {
        listOf(ButtonHint.activate("Choose"), ButtonHint.back("Cancel"))
    } else buildList {
        add(ButtonHint.activate("Select episode"))
        add(ButtonHint.secondary("Select season"))
        add(ButtonHint("Start", "Select all", PadAction.Menu))
        add(ButtonHint.primary("Download selected"))
        add(ButtonHint.back())
    }

    override fun onPad(action: PadAction): Boolean {
        if (overlay.onPad(action)) return true
        return when (action) {
            PadAction.Activate -> focusedCard()?.let { toggle(it.item.item.id); true } ?: false
            PadAction.Secondary -> focusedCard()?.let { card ->
                val ids = visibleSeasons().firstOrNull { it.season.id == card.seasonId }
                    ?.episodes?.filter { it.available && !alreadyStored(it.item.id) }?.map { it.item.id }.orEmpty()
                val select = ids.any { it !in selected }
                ids.forEach { if (select) selected += it else selected -= it }
                refreshCards(); true
            } ?: false
            PadAction.Menu -> {
                val ids = selectableItems().map { it.item.id }
                val select = ids.any { it !in selected }
                selected.clear(); if (select) selected.addAll(ids)
                refreshCards(); true
            }
            PadAction.Primary -> { confirmSelection(); true }
            PadAction.Refresh -> { load(); true }
            else -> false
        }
    }

    override fun requestInitialFocus(): Boolean = cards.values.firstOrNull()?.view?.requestFocus() == true

    private fun load() {
        loadJob?.cancel()
        status.showSummary(StatusText.loading("available episodes", refreshing = false), colors)
        loadJob = scope.launch {
            when (val result = api.offlineSelection(seriesId)) {
                is HubResult.Ok -> render(result.value)
                is HubResult.Failed -> status.showSummary(StatusText.failed(result.message, result.kind, hasData = false), colors)
            }
            loadJob = null
        }
    }

    private fun render(value: OfflineSelectionResponse) {
        catalog = value
        host.pageArtworkChanged()
        content.removeAllViews(); cards.clear(); selected.clear()
        val shown = value.seasons.filter { seasonId.isEmpty() || it.season.id == seasonId }
        shown.forEach { season ->
            val available = season.episodes.count { it.available }
            content.addView(GlassHeading.create(host.viewContext,
                season.season.title.ifBlank { EpisodeLabel.season(season.season.seasonNumber) },
                "$available episode${if (available == 1) "" else "s"}").apply {
                setPadding(dp(10), dp(10), dp(10), dp(2))
            })
            val recycler = RecyclerView(host.viewContext).apply {
                layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
                clipChildren = false; clipToPadding = false
                setPadding(dp(10), 0, dp(10), 0)
                adapter = EpisodeAdapter(season.season.id, season.episodes)
                layoutParams = LinearLayout.LayoutParams(MATCH, dp(190))
            }
            content.addView(recycler)
        }
        status.showSummary(StatusText.loaded("Original files · embedded audio and subtitles included · external subtitles saved beside them"), colors)
        updateCounter()
        if (!initialMenuShown) {
            initialMenuShown = true
            showQuickChoices()
        } else requestInitialFocus()
    }

    private fun showQuickChoices() {
        val all = selectableItems().map { it.item }
        overlay.show(
            "What should be downloaded?",
            "You can review and change the selected episodes before the download starts.",
            listOf(
                ChoiceOverlay.Choice("all", "All available episodes", Fmt.bytes(all.sumOf(::sizeOf))),
                ChoiceOverlay.Choice("unwatched", "All unwatched episodes"),
                ChoiceOverlay.Choice("next", "Next unwatched episode"),
                ChoiceOverlay.Choice("next_x", "Next episodes…"),
                ChoiceOverlay.Choice("manual", "Choose episodes")
            ),
            onCancel = { requestInitialFocus(); host.refreshHints() }
        ) { choice ->
            when (choice) {
                "all" -> selectItems(all)
                "unwatched" -> selectItems(all.filter { !it.played })
                "next" -> nextItems(1).let { items ->
                    if (items.isEmpty()) host.notify("No unwatched episode is available")
                    else selectItems(items)
                }
                "next_x" -> if (nextItems(1).isEmpty()) {
                    host.notify("No unwatched episode is available")
                } else showNextCount()
                else -> Unit
            }
            requestInitialFocus(); host.refreshHints()
        }
        host.refreshHints()
    }

    private fun showNextCount() {
        val counts = listOf(2, 3, 5, 10, 20)
        overlay.show(
            "How many next episodes?", "Only available, unwatched episodes are counted.",
            counts.map { ChoiceOverlay.Choice(it.toString(), "$it episodes") },
            onCancel = { requestInitialFocus(); host.refreshHints() }
        ) { choice ->
            val items = nextItems(choice.toInt())
            selectItems(items); requestInitialFocus(); host.refreshHints()
        }
    }

    private fun selectItems(items: List<com.pocketds.hub.model.LibraryItem>) {
        selected.clear(); selected.addAll(items.map { it.id }); refreshCards()
    }

    private fun toggle(id: String) {
        if (alreadyStored(id)) {
            host.notify("This episode is already downloaded or queued")
            return
        }
        if (!selected.add(id)) selected.remove(id)
        refreshCards()
    }

    private fun refreshCards() {
        cards.values.forEach { it.render(it.item.item.id in selected) }
        updateCounter(); host.refreshHints()
    }

    private fun updateCounter() {
        if (!::counter.isInitialized) return
        val lookup = availableItems().associateBy { it.item.id }
        val bytes = selected.sumOf { id -> lookup[id]?.estimatedSizeBytes ?: 0L }
        counter.text = "${selected.size} selected · ${Fmt.bytes(bytes)}"
    }

    private fun confirmSelection() {
        if (selected.isEmpty()) {
            host.notify("Choose at least one episode")
            return
        }
        val lookup = availableItems().associateBy { it.item.id }
        val bytes = selected.sumOf { lookup[it]?.estimatedSizeBytes ?: 0L }
        val location = OfflineSettings.selectedStorage(host.viewContext)
        if (location == null) {
            host.notify("The selected download location is unavailable")
            return
        }
        val available = location.availableBytes
        val source = selected.firstOrNull()?.let { lookup[it]?.sources?.firstOrNull() }
        val quality = source?.name?.ifBlank { source.container.uppercase() }.orEmpty()
        overlay.show(
            "Download ${selected.size} episode${if (selected.size == 1) "" else "s"}?",
            "${Fmt.bytes(bytes)} selected · ${Fmt.bytes(available)} free\n" +
                "${location.label} · private app storage · Original" +
                    if (quality.isEmpty()) "" else " · $quality",
            listOf(
                ChoiceOverlay.Choice("download", "Add to download manager"),
                ChoiceOverlay.Choice("cancel", "Keep choosing")
            ),
            onCancel = host::refreshHints
        ) { if (it == "download") prepare() else requestInitialFocus() }
        host.refreshHints()
    }

    private fun prepare() {
        if (prepareJob?.isActive == true) return
        val value = catalog ?: return
        val chosen = availableItems().filter { it.item.id in selected }
        val batchKey = "offline-${System.currentTimeMillis()}-${seriesId.take(8)}"
        status.showSummary(StatusText.loaded("Preparing secure download links…"), colors)
        prepareJob = scope.launch {
            val body = OfflinePrepareBody(
                batchKey, seriesId,
                chosen.map { OfflinePrepareItem("$batchKey-${it.item.id.take(12)}", it.item.id) }
            )
            when (val result = api.prepareOffline(body)) {
                is HubResult.Ok -> {
                    val count = OfflineRepository.get(host.viewContext).enqueue(
                        value.series.title.ifBlank { fallbackTitle }, seriesId, result.value.items
                    )
                    if (count > 0) {
                        OfflineDownloadService.start(host.viewContext)
                        host.notify("Added $count episode${if (count == 1) "" else "s"} to downloads")
                        host.back()
                    } else {
                        host.notify("Those episodes are already downloaded or queued")
                        status.showSummary(StatusText.loaded("Nothing new was added"), colors)
                    }
                }
                is HubResult.Failed -> {
                    status.showSummary(StatusText.failed(result.message, result.kind, hasData = true, canRetry = false), colors)
                    host.notify(result.message)
                }
            }
            prepareJob = null
        }
    }

    private fun focusedCard(): EpisodeCard? = cards.values.firstOrNull { it.view.hasFocus() }
    private fun sizeOf(item: com.pocketds.hub.model.LibraryItem) =
        availableItems().firstOrNull { it.item.id == item.id }?.estimatedSizeBytes ?: 0L
    private fun visibleSeasons() = catalog?.seasons?.filter { seasonId.isEmpty() || it.season.id == seasonId }.orEmpty()
    private fun availableItems() = visibleSeasons().flatMap { it.episodes }.filter { it.available }
    private fun selectableItems() = availableItems().filterNot { alreadyStored(it.item.id) }
    private fun alreadyStored(itemId: String) = OfflineRepository.get(host.viewContext).forItem(itemId) != null
    private fun nextItems(count: Int): List<com.pocketds.hub.model.LibraryItem> {
        val values = selectableItems().map { it.item }
        val target = catalog?.playTargetId.orEmpty()
        val start = values.indexOfFirst { it.id == target }.takeIf { it >= 0 }
            ?: values.indexOfFirst { !it.played }.coerceAtLeast(0)
        return values.drop(start).filter { !it.played || it.id == target }.take(count)
    }

    private inner class EpisodeAdapter(
        private val season: String,
        private val values: List<OfflineSelectionItem>
    ) : RecyclerView.Adapter<EpisodeHolder>() {
        override fun getItemCount() = values.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpisodeHolder {
            val card = EpisodeCard(parent, season)
            return EpisodeHolder(card.view, card)
        }
        override fun onBindViewHolder(holder: EpisodeHolder, position: Int) {
            holder.card.bind(values[position])
            cards[values[position].item.id] = holder.card
        }
        override fun onViewRecycled(holder: EpisodeHolder) {
            holder.card.item.item.id.takeIf { it.isNotEmpty() }?.let { if (cards[it] === holder.card) cards.remove(it) }
        }
    }

    private inner class EpisodeCard(parent: ViewGroup, val seasonId: String) {
        lateinit var item: OfflineSelectionItem
        val view = EpisodeCardView(parent.context, colors, ringVisible, compact = true).apply {
            layoutParams = RecyclerView.LayoutParams(dp(EpisodeCardView.COMPACT_WIDTH_DP), WRAP)
                .apply { marginEnd = dp(9); topMargin = dp(3) }
            onFocused = { host.refreshHints() }
            onActivate = { if (::item.isInitialized && item.available) toggle(item.item.id) }
        }

        fun bind(value: OfflineSelectionItem) {
            item = value
            val local = OfflineRepository.get(host.viewContext).forItem(value.item.id)
            view.bind(
                EpisodeCardView.Model(
                    title = EpisodeLabel.of(value.item.seasonNumber, value.item.indexNumber, value.item.title),
                    meta = if (value.available) {
                        buildList {
                            add(Fmt.bytes(value.estimatedSizeBytes))
                            if (local != null) add(local.state.wire.replaceFirstChar { it.uppercase() })
                            ResumeRules.watchLabel(value.item.played, value.item.progress)?.let(::add)
                        }.joinToString(" · ")
                    } else "Unavailable",
                    still = api.imageUrl(value.item.thumb.ifEmpty { value.item.poster }).takeIf(String::isNotEmpty),
                    progress = value.item.progress,
                    available = value.available
                ),
                Artwork.loader(api, view.context)
            )
            view.isEnabled = value.available
            render(value.item.id in selected)
        }

        fun render(isSelected: Boolean) {
            val stored = ::item.isInitialized && alreadyStored(item.item.id)
            view.setMarked(isSelected || stored)
            view.contentDescription = "${EpisodeLabel.of(item.item.seasonNumber, item.item.indexNumber, item.item.title)}, " +
                when { stored -> "already downloaded or queued"; isSelected -> "selected"; else -> "not selected" }
        }
    }

    private class EpisodeHolder(view: View, val card: EpisodeCard) : RecyclerView.ViewHolder(view)

    private fun dp(value: Int) = Styler.dpInt(host.viewContext, value.toFloat())

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        /** The prototype's Pocket pill, as a title page's. */
        const val PILL_DP = 31f
    }
}
