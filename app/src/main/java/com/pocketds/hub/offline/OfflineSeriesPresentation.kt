package com.pocketds.hub.offline

import com.pocketds.hub.model.LibraryItem
import com.pocketds.hub.model.SeriesPlayTargetResponse

/**
 * Presentation rules shared by Offline's series screen and JVM tests.
 *
 * Jellyfin remains the authority for the selected user's Continue/Next item
 * whenever it can be reached. The device may play it only when the precise
 * episode is present in the local catalog. A network failure deliberately
 * falls back to the best local continuation rather than blanking the page.
 */
data class OfflineSeriesViewState(
    val seasons: List<OfflineCatalogSeason>,
    val suggestion: OfflineSeriesSuggestion?
)

sealed interface OfflineSeriesSuggestion {
    val kind: String
    val playableOffline: Boolean

    data class Available(
        val download: OfflineDownload,
        override val kind: String,
        val positionMillis: Long = 0L
    ) : OfflineSeriesSuggestion {
        override val playableOffline = true
    }

    data class NotDownloaded(
        val item: LibraryItem,
        override val kind: String,
        val message: String
    ) : OfflineSeriesSuggestion {
        override val playableOffline = false
    }
}

object OfflineSeriesPresentation {
    /** Prefer fresh Jellyfin metadata, but keep the last known series artwork and overview for offline use. */
    fun detail(
        remote: LibraryItem?,
        remembered: LibraryItem?,
        manifest: LibraryItem?
    ): LibraryItem? = remote?.takeIf { it.id.isNotEmpty() }
        ?: remembered?.takeIf { it.id.isNotEmpty() }
        ?: manifest?.takeIf { it.id.isNotEmpty() }

    fun resolve(
        rows: List<OfflineDownload>,
        progress: Map<String, OfflineCatalogProgress>,
        serverTarget: SeriesPlayTargetResponse?
    ): OfflineSeriesViewState {
        val seriesId = rows.firstOrNull()?.manifest?.item?.seriesId.orEmpty()
        val seasons = if (seriesId.isEmpty()) emptyList() else OfflineCatalog.seasons(seriesId, rows)
        val target = serverTarget?.takeIf { it.item.id.isNotEmpty() }
        if (target != null) {
            val local = rows.firstOrNull { it.manifest.item.id == target.item.id }
            if (local != null) {
                val position = progress[target.item.id]?.resumePosition().orZero()
                return OfflineSeriesViewState(seasons, OfflineSeriesSuggestion.Available(
                    download = local,
                    kind = target.kind.normalisedKind(),
                    positionMillis = position
                ))
            }
            return OfflineSeriesViewState(seasons, OfflineSeriesSuggestion.NotDownloaded(
                item = target.item,
                kind = target.kind.normalisedKind(),
                message = "${episodeCode(target.item)} is not downloaded on this device"
            ))
        }

        val local = OfflineCatalog.playTarget(rows, progress)
        val suggestion = local?.let {
            OfflineSeriesSuggestion.Available(
                download = it.row,
                kind = it.kind.name.lowercase(),
                positionMillis = it.positionMillis
            )
        }
        return OfflineSeriesViewState(seasons, suggestion)
    }

    private fun String.normalisedKind() = lowercase().takeIf { it in setOf("resume", "next", "start") } ?: "start"
    private fun Long?.orZero() = this ?: 0L
    private fun episodeCode(item: LibraryItem): String = when {
        item.seasonNumber > 0 && item.indexNumber > 0 -> "S${item.seasonNumber}E${item.indexNumber} · ${item.title}"
        item.indexNumber > 0 -> "Episode ${item.indexNumber} · ${item.title}"
        else -> item.title.ifBlank { "This episode" }
    }
}
