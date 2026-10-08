package com.pocketds.hub.offline

import android.content.Context
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.settings.HubSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Carries out [KeepReady]'s plan for every series that has it on (#48): asks the
 * hub which episodes are watched, fetches the next ones on the persistent queue
 * and, when nothing is playing, removes the ones Keep ready fetched whose next
 * episode is finished. It runs at launch, after the player is left, and when a
 * series page is shown again; an unreachable hub just means the next time.
 */
object KeepReadyRunner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Mutex()
    private var players = 0

    /** The player screen is open: nothing is removed from under it. */
    fun playerOpened() { players++ }
    fun playerClosed() { players = (players - 1).coerceAtLeast(0) }
    val playing: Boolean get() = players > 0

    /** Runs in the background, and not twice at once. */
    fun requestRun(context: Context, afterMs: Long = 0L) {
        val app = context.applicationContext
        scope.launch {
            if (afterMs > 0) delay(afterMs)
            run(app, HubClient.shared(app))
        }
    }

    suspend fun run(context: Context, api: HubApi) = lock.withLock {
        if (!HubSettings.isConfigured(context)) return@withLock
        val repository = OfflineRepository.get(context)
        repository.keepReadySeries().forEach { (seriesId, count) -> tidy(context, api, repository, seriesId, count) }
    }

    private suspend fun tidy(context: Context, api: HubApi, repository: OfflineRepository, seriesId: String, count: Int) {
        val selection = (api.offlineSelection(seriesId) as? HubResult.Ok)?.value ?: return
        val episodes = SeriesDownloadChoices.from(selection)
        val rows = repository.forItems(episodes.map { it.id })
        val plan = KeepReady.plan(KeepReady.Input(
            episodes = episodes,
            targetId = selection.playTargetId.ifBlank { null },
            count = count,
            onDevice = rows.keys,
            owned = rows.filterValues { it.origin == OfflineRepository.ORIGIN_KEEP_READY }.keys,
            playing = playing
        ))
        // Room first, then the next ones.
        plan.remove.mapNotNull { rows[it] }.forEach { row ->
            if (row.state == OfflineState.COMPLETE) repository.remove(row.id) else OfflineDownloadService.remove(context, row.id)
        }
        if (plan.download.isNotEmpty()) {
            OfflineQueueing.queue(context, api, selection.series.title, seriesId, plan.download, OfflineRepository.ORIGIN_KEEP_READY)
        }
    }
}
