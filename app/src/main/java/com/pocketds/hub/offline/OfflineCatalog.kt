package com.pocketds.hub.offline

data class OfflineCatalogEntry(
    val key: String,
    val title: String,
    val isSeries: Boolean,
    val rows: List<OfflineDownload>
)

data class OfflineCatalogSeason(
    val key: String,
    val number: Int,
    val rows: List<OfflineDownload>
)

/** The best locally stored episode to continue without contacting Jellyfin. */
data class OfflineCatalogPlayTarget(
    val row: OfflineDownload,
    val kind: Kind,
    val positionMillis: Long = 0L
) {
    enum class Kind { RESUME, NEXT, START }
}

/** A local watch checkpoint, deliberately independent of the SQLite layer. */
data class OfflineCatalogProgress(
    val positionMillis: Long,
    val durationMillis: Long,
    val updatedAtMillis: Long
) {
    fun isComplete(): Boolean = durationMillis > 0 &&
        positionMillis >= durationMillis - COMPLETE_REMAINING_MILLIS

    fun resumePosition(): Long = if (
        durationMillis > 0 && positionMillis >= MIN_RESUME_MILLIS &&
        durationMillis - positionMillis > COMPLETE_REMAINING_MILLIS
    ) positionMillis else 0L

    private companion object {
        const val MIN_RESUME_MILLIS = 30_000L
        const val COMPLETE_REMAINING_MILLIS = 30_000L
    }
}

/** Pure catalog shaping shared by the UI and JVM tests. */
object OfflineCatalog {
    fun titles(
        completed: List<OfflineDownload>,
        batchTitles: Map<String, String> = emptyMap()
    ): List<OfflineCatalogEntry> = completed
        .groupBy { row -> row.manifest.item.seriesId.ifBlank { row.manifest.item.id } }
        .values
        .map { rows ->
            val ordered = rows.sortedWith(
                compareBy({ it.manifest.item.seasonNumber }, { it.manifest.item.indexNumber })
            )
            val first = ordered.first()
            OfflineCatalogEntry(
                key = first.manifest.item.seriesId.ifBlank { first.manifest.item.id },
                title = first.manifest.item.seriesTitle.ifBlank {
                    batchTitles[first.batchId] ?: first.manifest.item.title
                },
                isSeries = first.manifest.item.seriesId.isNotBlank(),
                rows = ordered
            )
        }
        .sortedBy { it.title.lowercase() }

    fun seasons(seriesId: String, completed: List<OfflineDownload>): List<OfflineCatalogSeason> =
        completed
            .filter { it.manifest.item.seriesId == seriesId }
            .groupBy { row ->
                row.manifest.item.seasonId.ifBlank { "season-${row.manifest.item.seasonNumber}" }
            }
            .map { (key, rows) ->
                OfflineCatalogSeason(
                    key = key,
                    number = rows.first().manifest.item.seasonNumber,
                    rows = rows.sortedBy { it.manifest.item.indexNumber }
                )
            }
            .sortedBy { it.number }

    /**
     * Select a locally downloaded episode using the same 30-second resume
     * window as playback. A recent partial episode wins; otherwise the first
     * locally available episode that has not been finished is Next.
     */
    fun playTarget(
        rows: List<OfflineDownload>,
        progress: Map<String, OfflineCatalogProgress>
    ): OfflineCatalogPlayTarget? {
        val ordered = rows.sortedWith(compareBy(
            { it.manifest.item.seasonNumber },
            { it.manifest.item.indexNumber },
            { it.manifest.item.title.lowercase() }
        ))
        val resumed = ordered.mapNotNull { row ->
            progress[row.manifest.item.id]
                ?.resumePosition()
                ?.takeIf { it > 0L }
                ?.let { position -> row to position }
        }.maxByOrNull { (row, _) -> progress[row.manifest.item.id]?.updatedAtMillis ?: 0L }
        if (resumed != null) return OfflineCatalogPlayTarget(
            resumed.first, OfflineCatalogPlayTarget.Kind.RESUME, resumed.second
        )
        val next = ordered.firstOrNull { row -> !(progress[row.manifest.item.id]?.isComplete() ?: false) }
        return next?.let { row ->
            OfflineCatalogPlayTarget(
                row,
                if (progress.isEmpty()) OfflineCatalogPlayTarget.Kind.START else OfflineCatalogPlayTarget.Kind.NEXT
            )
        }
    }
}
