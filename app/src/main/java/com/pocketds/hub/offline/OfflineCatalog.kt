package com.pocketds.hub.offline

import com.pocketds.hub.playback.ResumeRules

data class OfflineCatalogEntry(
    val key: String,
    val title: String,
    val isSeries: Boolean,
    val rows: List<OfflineDownload>,
    /** The Jellyfin library, or empty when no manifest or lookup has named one yet. */
    val library: String = ""
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
    fun isComplete(): Boolean = ResumeRules.isFinished(positionMillis, durationMillis)

    fun resumePosition(): Long = ResumeRules.resumePosition(positionMillis, durationMillis)

    companion object {
        /** The server's view of a watch, in the same shape; a played item sits at its end. */
        fun fromServer(positionMillis: Long, durationMillis: Long, played: Boolean, lastPlayedAt: Long) =
            OfflineCatalogProgress(if (played) durationMillis else positionMillis, durationMillis, lastPlayedAt)

        /**
         * A download keeps its own watch unless the server saw a later one --
         * someone carried on on the TV after downloading, or after the last
         * offline session. Without a server date the local watch stands, which
         * is also what an older hub gets.
         */
        fun newer(local: OfflineCatalogProgress, server: OfflineCatalogProgress?): OfflineCatalogProgress =
            if (server != null && server.updatedAtMillis > local.updatedAtMillis) server else local
    }
}

/** Pure catalog shaping shared by the UI and JVM tests. */
object OfflineCatalog {
    fun titles(
        completed: List<OfflineDownload>,
        batchTitles: Map<String, String> = emptyMap(),
        /** Libraries looked up for downloads made before manifests carried one. */
        libraryNames: Map<String, String> = emptyMap()
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
                rows = ordered,
                library = ordered.firstNotNullOfOrNull { it.manifest.item.library?.name?.takeIf(String::isNotBlank) }
                    ?: libraryNames[first.manifest.item.seriesId.ifBlank { first.manifest.item.id }].orEmpty()
            )
        }
        .sortedBy { it.title.lowercase() }

    /**
     * Downloads grouped the way Library shows them, libraries A to Z. A title
     * whose library is not known yet goes under Movies or Series, last.
     */
    fun byLibrary(entries: List<OfflineCatalogEntry>): List<Pair<String, List<OfflineCatalogEntry>>> =
        entries.groupBy { it.library.ifBlank { if (it.isSeries) FALLBACK_SERIES else FALLBACK_MOVIES } }
            .toList()
            .sortedWith(compareBy({ it.second.all { entry -> entry.library.isBlank() } }, { it.first.lowercase() }))

    const val FALLBACK_MOVIES = "Movies"
    const val FALLBACK_SERIES = "Series"

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
     * Select a locally downloaded episode using the same [ResumeRules] as
     * playback. A recent partial episode wins; otherwise the first
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
