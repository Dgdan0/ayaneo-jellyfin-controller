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
}
