package com.pocketds.hub.offline

import com.pocketds.hub.model.LibraryItem
import com.pocketds.hub.ui.DetailSnapshot

data class OfflineDetailPresentation(
    val playable: OfflineCatalogPlayTarget? = null,
    val missing: LibraryItem? = null,
    val localSuggestion: Boolean = false
) {
    companion object {
        fun resolve(rows: List<OfflineDownload>, progress: Map<String, OfflineCatalogProgress>, snapshot: DetailSnapshot?): OfflineDetailPresentation {
            val latestLocal = progress.values.maxOfOrNull { it.updatedAtMillis } ?: 0L
            val known = snapshot?.target
            // A local session after this snapshot is newer evidence. Do not overwrite it
            // with a server recommendation merely because the offline screen was opened.
            if (known != null && snapshot.targetRecordedAt >= latestLocal && !known.item.played &&
                progress[known.item.id]?.isComplete() != true) {
                val row = rows.firstOrNull { it.manifest.item.id == known.item.id }
                    ?: return OfflineDetailPresentation(missing = known.item)
                // The snapshot chooses episode identity, not a new playback checkpoint.
                // Match OfflineRepository.playbackPlan so the time on this card is the
                // position actually played by the local file, without a covert sync write.
                val savedPosition = progress[known.item.id]?.resumePosition()
                    ?: OfflineCatalogProgress(row.manifest.item.positionSeconds * 1000L,
                        row.manifest.item.runtimeSeconds * 1000L, row.updatedAt).resumePosition()
                return OfflineDetailPresentation(playable = OfflineCatalogPlayTarget(row,
                    if (savedPosition > 0) OfflineCatalogPlayTarget.Kind.RESUME else OfflineCatalogPlayTarget.Kind.NEXT,
                    savedPosition), localSuggestion = true)
            }
            return OfflineDetailPresentation(playable = OfflineCatalog.playTarget(rows, progress), localSuggestion = true)
        }
    }
}
