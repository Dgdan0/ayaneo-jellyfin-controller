package com.pocketds.hub.offline

import android.content.Context
import com.pocketds.hub.model.OfflinePrepareBody
import com.pocketds.hub.model.OfflinePrepareItem
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.settings.OfflineSettings
import java.util.UUID

/**
 * How anything gets onto the persistent download queue (#48): ask the hub for a
 * grant for each item, put the manifests on the queue, and start the foreground
 * service. A film or an episode from its page, a quick tap on a card, a season,
 * the smart choices, select mode and Keep ready all come here, so one rule says
 * what "already downloaded or queued" means and how a batch is named. The old
 * episode-selection screen and the host each had a copy of this.
 */
object OfflineQueueing {

    sealed interface Result {
        data class Queued(val count: Int) : Result
        /** Every one of them was on the device or on the queue already. */
        data object AlreadyThere : Result
        data class Failed(val message: String) : Result
    }

    suspend fun queue(
        context: Context,
        api: HubApi,
        title: String,
        seriesId: String,
        itemIds: List<String>,
        origin: String = OfflineRepository.ORIGIN_OWN
    ): Result {
        if (itemIds.isEmpty()) return Result.AlreadyThere
        val repository = OfflineRepository.get(context)
        if (OfflineSettings.selectedStorage(context) == null) return Result.Failed("Choose an available download location in Settings")
        val batchKey = "offline-${System.currentTimeMillis()}-${(seriesId.ifEmpty { itemIds.first() }).take(8)}-${UUID.randomUUID().toString().take(4)}"
        val body = OfflinePrepareBody(batchKey, seriesId, itemIds.map { OfflinePrepareItem("$batchKey-${it.take(12)}", it) })
        return when (val result = api.prepareOffline(body)) {
            is HubResult.Ok -> {
                val count = repository.enqueue(title, seriesId, result.value.items, origin)
                if (count > 0) { OfflineDownloadService.start(context); Result.Queued(count) } else Result.AlreadyThere
            }
            is HubResult.Failed -> Result.Failed(result.message)
        }
    }

    /** What to tell the person: "Added 3 episodes to downloads", or why not. */
    fun words(result: Result, what: String = "episode"): String = when (result) {
        is Result.Queued -> "Added ${result.count} $what${if (result.count == 1) "" else "s"} to downloads"
        Result.AlreadyThere -> "Already downloaded or on its way"
        is Result.Failed -> result.message
    }
}
