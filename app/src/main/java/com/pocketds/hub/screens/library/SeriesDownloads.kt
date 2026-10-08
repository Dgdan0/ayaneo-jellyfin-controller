package com.pocketds.hub.screens.library

import android.content.Context
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.FrameLayout
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.LibraryItem
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.offline.DownloadChoice
import com.pocketds.hub.offline.DownloadSelection
import com.pocketds.hub.offline.EpisodeDownloadMarks
import com.pocketds.hub.offline.KeepReadyRunner
import com.pocketds.hub.offline.OfflineDownload
import com.pocketds.hub.offline.OfflineDownloadService
import com.pocketds.hub.offline.OfflineQueueing
import com.pocketds.hub.offline.OfflineRepository
import com.pocketds.hub.offline.OfflineState
import com.pocketds.hub.offline.OfflineTotals
import com.pocketds.hub.offline.SeriesDownloadChoices
import com.pocketds.hub.offline.SeriesEpisode
import com.pocketds.hub.offline.StorageBar
import com.pocketds.hub.settings.OfflineSettings
import com.pocketds.hub.state.Fmt
import com.pocketds.hub.ui.ChoiceOverlay
import com.pocketds.hub.ui.DownloadDockView
import com.pocketds.hub.ui.EpisodeLabel
import com.pocketds.hub.ui.PocketColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Everything a series page does about downloads (#48), in one place so
 * [LibraryDetailScreen] only asks: the quick taps on an episode's corner, the
 * season's button, the smart choices panel, select mode, the storage bar, and
 * Keep ready's switch. It replaces the old episode-selection page; the rules that
 * page had (the next unwatched episodes, all unwatched, all available) are
 * [SeriesDownloadChoices]'s now, and the ticks are [DownloadSelection]'s.
 */
class SeriesDownloads(
    private val context: Context,
    private val api: HubApi,
    private val seriesId: String,
    private val colors: PocketColors,
    private val scope: CoroutineScope,
    ringVisible: () -> Boolean,
    private val host: () -> ScreenHost?,
    private val hooks: Hooks
) {
    /** What the page tells this, and what it is asked to do. */
    interface Hooks {
        fun title(): String
        /** Cards, the season's button and the pills' counts are drawn again. */
        fun marksChanged()
        fun hintsChanged()
        fun play(item: LibraryItem)
        fun findReleases(item: LibraryItem)
        /** The episode Play would start, when the page knows it. */
        fun playTargetId(): String?
        fun seasonName(seasonId: String): String?
        fun canFindReleases(): Boolean
    }

    val dock = DownloadDockView(context, colors, ringVisible)
    val panel = SeriesDownloadPanel(context, colors, ringVisible)
    private val menu = ChoiceOverlay(context, colors, ringVisible, sidePanel = true)

    var episodes: List<SeriesEpisode> = emptyList()
        private set
    private var catalogTarget: String? = null
    private var rows: Map<String, OfflineDownload> = emptyMap()
    private var totals = OfflineTotals(0, 0, 0, 0)
    val selection = DownloadSelection()
    var selecting = false
        private set
    private val visibility = StorageBar.Visibility()
    private var loadJob: Job? = null
    private var refreshJob: Job? = null
    private var hideJob: Job? = null
    private val repository get() = OfflineRepository.get(context)

    /** Every episode the device has or is fetching. */
    private val have: Set<String> get() = rows.keys

    init {
        dock.onOpenDownloads = { host()?.openOfflineManager() }
        dock.onDownload = { queueSelection() }
        panel.onKeepCount = { count -> choices(count) }
        panel.onPreview = { adding -> showPreview(adding) }
        panel.onPick = ::picked
    }

    /** Puts the dock and the sheets on the page, over its content. */
    fun attach(page: ViewGroup, dockMargin: Int) {
        page.addView(dock, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM)
            .apply { setMargins(dockMargin, 0, dockMargin, dockMargin) })
        page.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        page.addView(menu, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    // ------------------------------------------------------------------ what is known

    /** Reads the series' episodes with their sizes and watched state from the hub, then what the device has. */
    fun load() {
        if (loadJob?.isActive == true) return
        loadJob = scope.launch {
            (api.offlineSelection(seriesId) as? HubResult.Ok)?.value?.let { selected ->
                episodes = SeriesDownloadChoices.from(selected)
                catalogTarget = selected.playTargetId.ifBlank { null }
            }
            refreshLocal()
            loadJob = null
        }
    }

    /** A transfer moved: soon, and once, whatever the number of broadcasts. */
    fun changed() {
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch { delay(REFRESH_MS); refreshLocal(); refreshJob = null }
    }

    fun refreshLocal() {
        rows = repository.forItems(episodes.map { it.id })
        selection.prune(have)
        totals = repository.totals()
        updateDock()
        hooks.marksChanged()
    }

    private fun target() = hooks.playTargetId() ?: catalogTarget
    val keepCount: Int get() = repository.keepReady(seriesId) ?: DEFAULT_KEEP
    val keepOn: Boolean get() = repository.keepReady(seriesId) != null

    fun stop() {
        loadJob?.cancel(); refreshJob?.cancel(); hideJob?.cancel()
        loadJob = null; refreshJob = null; hideJob = null
    }

    // ------------------------------------------------------------------ the cards

    fun marksFor(item: LibraryItem): SeasonEpisodesView.CardMarks {
        val row = rows[item.id]
        val episode = episodes.firstOrNull { it.id == item.id }
        if (selecting) {
            val tickable = episode != null && selection.canTick(episode, have)
            return SeasonEpisodesView.CardMarks(null, tick = if (tickable) selection.isTicked(item.id) else null, dimmed = !tickable)
        }
        // A file the server lacks has nothing to download; one on the device shows its state either way.
        if (row == null && episode != null && !episode.available) return SeasonEpisodesView.CardMarks(null)
        val badge = EpisodeDownloadMarks.badge(row?.state, row?.progress ?: 0f)
        return SeasonEpisodesView.CardMarks(badge, EpisodeDownloadMarks.description(row?.state, row?.progress ?: 0f))
    }

    /** A tap on a card's corner, with a finger or the trackpad: straight to the download, no question. */
    fun cornerTapped(item: LibraryItem) {
        val row = rows[item.id]
        when (EpisodeDownloadMarks.tap(row?.state)) {
            EpisodeDownloadMarks.Tap.DOWNLOAD -> download(listOf(item.id))
            EpisodeDownloadMarks.Tap.RETRY -> row?.let { repository.retry(it.id); OfflineDownloadService.start(context) }
            EpisodeDownloadMarks.Tap.STOP -> row?.let { OfflineDownloadService.remove(context, it.id) }
            EpisodeDownloadMarks.Tap.MENU -> openMenu(item)
        }
    }

    /** Ⓨ on a card: Play, Download or Stop or Remove download, Select episodes. */
    fun openMenu(item: LibraryItem) {
        val row = rows[item.id]
        val available = episodes.firstOrNull { it.id == item.id }?.available != false
        val list = EpisodeDownloadMarks.menu(row?.state, item.positionSeconds > 0, available, hooks.canFindReleases())
        menu.show(EpisodeLabel.of(item.seasonNumber, item.indexNumber, item.title).ifBlank { "Episode" }, "",
            list.map { ChoiceOverlay.Choice(it.id, it.label) }, onCancel = { hooks.hintsChanged() }) { id ->
            when (id) {
                EpisodeDownloadMarks.PLAY -> hooks.play(item)
                EpisodeDownloadMarks.DOWNLOAD -> if (row?.state == OfflineState.FAILED) cornerTapped(item) else download(listOf(item.id))
                EpisodeDownloadMarks.STOP -> row?.let { OfflineDownloadService.remove(context, it.id) }
                EpisodeDownloadMarks.REMOVE -> row?.let { removeDownload(it) }
                EpisodeDownloadMarks.SELECT -> enterSelect(item)
                EpisodeDownloadMarks.RELEASES -> hooks.findReleases(item)
            }
            hooks.hintsChanged()
        }
        hooks.hintsChanged()
    }

    private fun removeDownload(row: OfflineDownload) {
        menu.confirm("Remove download?", "${row.manifest.item.title.ifBlank { "This episode" }} leaves this device. Your place in it stays.",
            "Remove download", onCancel = { hooks.hintsChanged() }) {
            repository.remove(row.id)
            host()?.notify("Removed from this device")
            refreshLocal()
        }
    }

    // ------------------------------------------------------------------ the season's button

    /** The season button's words and whether it does anything: "Season 2 · 4.9 GB", or "Season 2 on this Pocket". */
    fun seasonButton(seasonId: String, name: String): Pair<String, Boolean> {
        val choice = SeriesDownloadChoices.season(episodes, seasonId, have)
        val anyAvailable = episodes.any { it.seasonId == seasonId && it.available }
        return SeriesDownloadChoices.seasonButton(name, choice, anyAvailable, DEVICE, Fmt::bytes) to !choice.isEmpty
    }

    fun downloadSeason(seasonId: String) = download(SeriesDownloadChoices.season(episodes, seasonId, have).ids)

    // ------------------------------------------------------------------ the choices panel

    fun openPanel() {
        if (episodes.isEmpty()) { host()?.notify("Looking at the episodes. Try again in a moment."); load(); return }
        panel.show(hooks.title(), choices(keepCount), Fmt::bytes) { hooks.hintsChanged(); updateDock() }
        showPreview(null)
        updateDock()
        hooks.hintsChanged()
    }

    private fun choices(count: Int): SeriesDownloadPanel.Choices {
        val target = SeriesDownloadChoices.targetSeason(episodes, target())
        return SeriesDownloadPanel.Choices(
            keepCount = count,
            keepOn = keepOn,
            keep = SeriesDownloadChoices.keepReady(episodes, target(), count, have),
            restName = target?.let { hooks.seasonName(it.seasonId) ?: EpisodeLabel.season(it.season) } ?: "the season",
            rest = target?.let { SeriesDownloadChoices.restOfSeason(episodes, it.seasonId, have) } ?: DownloadChoice.NONE,
            unwatched = SeriesDownloadChoices.everythingUnwatched(episodes, have),
            whole = SeriesDownloadChoices.wholeSeries(episodes, have)
        )
    }

    private fun picked(pick: SeriesDownloadPanel.Pick, count: Int) {
        val now = choices(count)
        when (pick) {
            SeriesDownloadPanel.Pick.KEEP_READY -> {
                panel.dismiss()
                repository.setKeepReady(seriesId, count)
                host()?.notify("Keeping the next $count ready")
                scope.launch { KeepReadyRunner.run(context, api); refreshLocal() }
            }
            SeriesDownloadPanel.Pick.KEEP_OFF -> {
                panel.dismiss()
                repository.clearKeepReady(seriesId)
                host()?.notify("Keep ready is off. The files stay.")
                hooks.marksChanged()
            }
            SeriesDownloadPanel.Pick.REST_OF_SEASON -> { panel.dismiss(); download(now.rest.ids) }
            SeriesDownloadPanel.Pick.UNWATCHED -> { panel.dismiss(); download(now.unwatched.ids) }
            SeriesDownloadPanel.Pick.WHOLE_SERIES -> { panel.dismiss(); download(now.whole.ids) }
            SeriesDownloadPanel.Pick.CHOOSE -> { panel.dismiss(); enterSelect(null) }
        }
        hooks.hintsChanged()
    }

    private fun showPreview(adding: DownloadChoice?) {
        val model = storageModel(adding?.bytes ?: 0L)
        panel.showStorage(model, StorageBar.label(totals.comingCount, totals.onDeviceCount, DEVICE, adding, model.freeAfter))
    }

    // ------------------------------------------------------------------ select mode

    fun enterSelect(first: LibraryItem?) {
        selecting = true
        selection.clear()
        first?.let { item -> episodes.firstOrNull { it.id == item.id }?.let { selection.toggle(it, have) } }
        updateDock()
        hooks.marksChanged()
        hooks.hintsChanged()
    }

    fun exitSelect() {
        if (!selecting) return
        selecting = false
        selection.clear()
        updateDock()
        hooks.marksChanged()
        hooks.hintsChanged()
    }

    /** A tap or Ⓐ on a card in select mode ticks it; one that cannot be ticked says so. */
    fun tick(item: LibraryItem) {
        val episode = episodes.firstOrNull { it.id == item.id }
        if (episode == null || !selection.toggle(episode, have)) host()?.notify("This episode is already on this $DEVICE or on its way")
        updateDock()
        hooks.marksChanged()
        hooks.hintsChanged()
    }

    fun selectSeason(seasonId: String) {
        selection.toggleSeason(episodes, seasonId, have)
        updateDock()
        hooks.marksChanged()
        hooks.hintsChanged()
    }

    /** The count on a season's pill while choosing: "4/14". */
    fun seasonTicks(seasonId: String): String? = if (selecting) selection.seasonLabel(episodes, seasonId, have) else null

    fun queueSelection() {
        if (selection.isEmpty) { host()?.notify("Choose at least one episode"); return }
        val ids = selection.ids
        exitSelect()
        download(ids)
    }

    // ------------------------------------------------------------------ doing it

    /** Puts [ids] on the queue, as the owner's own downloads. */
    fun download(ids: List<String>) {
        if (ids.isEmpty()) { host()?.notify("Nothing left to get"); return }
        scope.launch {
            val result = OfflineQueueing.queue(context, api, hooks.title(), seriesId, ids)
            host()?.notify(OfflineQueueing.words(result))
            refreshLocal()
        }
    }

    // ------------------------------------------------------------------ the storage bar

    private fun storageModel(adding: Long): StorageBar.Model {
        val storage = OfflineSettings.selectedStorage(context)
        return StorageBar.of(storage?.totalBytes ?: 0L, storage?.availableBytes ?: 0L, totals.onDeviceBytes, totals.comingBytes, adding)
    }

    private fun updateDock() {
        val now = SystemClock.elapsedRealtime()
        // Select mode keeps the bar; the choices panel has its own under its rows, so the page's waits behind it.
        val shown = visibility.update(now, totals.comingCount, forced = selecting)
        dock.show(shown && !panel.isOpen)
        if (shown) {
            val adding = if (selecting) DownloadChoice(selection.ids, selection.bytes(episodes)) else null
            val model = storageModel(adding?.bytes ?: 0L)
            dock.bind(model, StorageBar.label(totals.comingCount, totals.onDeviceCount, DEVICE, adding, model.freeAfter))
            dock.selecting(if (selecting) selection.sizeLine(episodes, Fmt::bytes) else null, ready = !selection.isEmpty)
        }
        hideJob?.cancel()
        visibility.hidesAt()?.let { at ->
            hideJob = scope.launch { delay((at - now).coerceAtLeast(0L) + 60L); updateDock() }
        }
    }

    // ------------------------------------------------------------------ the pad

    /** The sheets first; then select mode's own buttons. */
    fun onPad(action: PadAction): Boolean {
        if (panel.onPad(action)) { hooks.hintsChanged(); return true }
        if (menu.onPad(action)) { hooks.hintsChanged(); return true }
        if (!selecting) return false
        return when (action) {
            PadAction.Back -> { exitSelect(); true }
            PadAction.Menu -> { queueSelection(); true }
            else -> false
        }
    }

    val sheetOpen: Boolean get() = panel.isOpen || menu.isOpen

    /** Android's system Back: a sheet, then select mode. */
    fun onSystemBack(): Boolean = when {
        panel.isOpen -> { panel.cancel(); hooks.hintsChanged(); true }
        menu.isOpen -> { menu.dismiss(); hooks.hintsChanged(); true }
        selecting -> { exitSelect(); true }
        else -> false
    }

    companion object {
        const val DEFAULT_KEEP = 3
        /** "1 on this Pocket". */
        const val DEVICE = "Pocket"
        private const val REFRESH_MS = 250L
    }
}
