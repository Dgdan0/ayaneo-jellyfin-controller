package com.pocketds.hub.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

object ReadingType {
    const val ALL = "all"
    const val EBOOK = "ebook"
    const val AUDIOBOOK = "audiobook"
    const val COMIC = "comic"
    const val MANGA = "manga"
    const val LIGHT_NOVEL = "light_novel"

    val filters = listOf(
        ALL to "All",
        EBOOK to "Books",
        AUDIOBOOK to "Audiobooks",
        COMIC to "Comics",
        MANGA to "Manga",
        LIGHT_NOVEL to "Light novels"
    )

    fun label(wire: String): String = filters.firstOrNull { it.first == wire }?.second
        ?: wire.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

@Serializable
data class ReadingItem(
    val key: String = "",
    val contentType: String = "ebook",
    val title: String = "",
    val author: String = "",
    val year: Int = 0,
    val isbn: String = "",
    val source: String = "",
    val sourceId: String = "",
    val cover: String = "",
    val description: String = "",
    val inLibrary: Boolean = false,
    val actions: List<String> = emptyList()
) {
    val subtitle: String
        get() = buildList {
            if (author.isNotBlank()) add(author)
            if (year > 0) add(year.toString())
            if (isEmpty()) add(ReadingType.label(contentType))
        }.joinToString(" · ")
}

@Serializable
data class ReadingDiscoverRow(
    val id: String = "",
    val title: String = "",
    val meta: String = "",
    val contentType: String = "ebook",
    val page: Int = 1,
    val hasMore: Boolean = false,
    val items: List<ReadingItem> = emptyList()
)

@Serializable
data class ReadingDiscoverResponse(
    val rows: List<ReadingDiscoverRow> = emptyList(),
    val partial: List<PartialFailure> = emptyList(),
    val cache: CacheInfo = CacheInfo()
)

@Serializable
data class ReadingSearchResponse(
    val query: String = "",
    val contentType: String = ReadingType.ALL,
    val results: List<ReadingItem> = emptyList(),
    val partial: List<PartialFailure> = emptyList(),
    val cache: CacheInfo = CacheInfo()
)

@Serializable
data class ReadingRequestMode(
    val id: String = "",
    val label: String = "",
    val requiresTotalBooks: Boolean = false
)

@Serializable
data class ReadingQualityProfile(
    val id: Int = 0,
    val label: String = "",
    val default: Boolean = false,
    val preferCompleteBatches: Boolean = false
)

@Serializable
data class ReadingRequestOptions(
    val key: String = "",
    val contentType: String = "ebook",
    val title: String = "",
    val author: String = "",
    val modes: List<ReadingRequestMode> = emptyList(),
    val qualityProfiles: List<ReadingQualityProfile> = emptyList(),
    val monitoring: List<String> = emptyList()
) {
    val defaultProfileIndex: Int
        get() = qualityProfiles.indexOfFirst { it.default }.coerceAtLeast(0)
}

@Serializable
data class ReadingCreateRequestBody(
    val key: String,
    val mode: String,
    val totalBooks: Int = 0,
    val qualityProfileId: Int,
    val monitoring: String = "all"
)

@Serializable
data class ReadingRequestResponse(
    val requestId: String = "",
    val seriesId: Int = 0,
    val state: String = "",
    val message: String = ""
)

@Serializable
data class ReadingDownloadItem(
    val id: String = "",
    val seriesId: Int = 0,
    val contentType: String = "",
    val title: String = "",
    val releaseTitle: String = "",
    val status: String = "",
    val progressPercent: Int = 0,
    val downloadSpeedBytesPerSecond: Long = 0,
    val etaSeconds: Long = 0,
    val sizeBytes: Long = 0,
    val addedAt: String = "",
    val completedAt: String = "",
    val importedAt: String = "",
    val failed: Boolean = false
) {
    val progress: Double get() = progressPercent.coerceIn(0, 100) / 100.0
    val isActive: Boolean
        get() = status == "queued" || status == "downloading" || status == "importing"
}

@Serializable
data class ReadingDownloadsResponse(
    val items: List<ReadingDownloadItem> = emptyList()
) {
    val anyActive: Boolean get() = items.any { it.isActive }
}

@Serializable
data class ReadingLibrary(
    val id: String = "",
    val source: String = "",
    val kind: String = "book",
    val title: String = "",
    val artwork: String = "",
    val capabilities: List<String> = emptyList()
)

@Serializable
data class ReadingLibrariesResponse(
    val libraries: List<ReadingLibrary> = emptyList(),
    val partial: List<PartialFailure> = emptyList(),
    val cache: CacheInfo = CacheInfo()
)

@Serializable
data class ReadingProgress(
    val percentage: Double = 0.0,
    val completed: Boolean = false,
    val current: Int = 0,
    val total: Int = 0,
    val updatedAt: String = ""
)

@Serializable
data class ReadingEdition(
    val id: String = "",
    val workId: String = "",
    val source: String = "",
    val sourceItemId: String = "",
    val kind: String = "book",
    val format: String = "",
    val identifiers: Map<String, String> = emptyMap(),
    val narrator: String = "",
    val pageCount: Int = 0,
    val durationMs: Long = 0,
    val availability: String = ""
)

@Serializable
data class ReadingSectionItem(
    val sourceItemId: String = "",
    val workId: String = "",
    val title: String = "",
    val number: String = "",
    val kind: String = "book",
    val artwork: String = "",
    val authors: List<String> = emptyList(),
    val pageCount: Int = 0,
    val progress: ReadingProgress? = null
)

@Serializable
data class ReadingSection(
    val id: String = "",
    val title: String = "",
    val number: Double = 0.0,
    val items: List<ReadingSectionItem> = emptyList()
)

@Serializable
data class ReadingContinue(
    val source: String = "",
    val sourceItemId: String = "",
    val title: String = "",
    val number: String = "",
    val percentage: Double = 0.0
)

@Serializable
data class ReadingWork(
    val id: String = "",
    val libraryId: String = "",
    val entityType: String = "work",
    val kind: String = "book",
    val title: String = "",
    val sortTitle: String = "",
    val authors: List<String> = emptyList(),
    val series: String = "",
    val seriesIndex: Double = 0.0,
    val overview: String = "",
    val artwork: String = "",
    val genres: List<String> = emptyList(),
    val year: Int = 0,
    val addedAt: String = "",
    val bookCount: Int = 0,
    val languages: List<String> = emptyList(),
    val editions: List<ReadingEdition> = emptyList(),
    val progress: ReadingProgress? = null,
    val availability: List<String> = emptyList(),
    val sections: List<ReadingSection> = emptyList(),
    @SerialName("continue") val continueAt: ReadingContinue? = null,
    val partial: List<PartialFailure> = emptyList(),
    val cache: CacheInfo = CacheInfo()
) {
    val byline: String get() = authors.joinToString(", ")

    val subtitle: String
        get() = buildList {
            if (series.isNotBlank()) add(series)
            if (byline.isNotBlank()) add(byline)
            if (isEmpty() && year > 0) add(year.toString())
            if (isEmpty()) add(ReadingType.label(kind))
        }.joinToString(" · ")
}

@Serializable
data class ReadingLibraryItemsResponse(
    val libraryId: String = "",
    val page: Int = 1,
    val pageSize: Int = 60,
    val total: Int = 0,
    val totalPages: Int = 0,
    val hasMore: Boolean = false,
    val items: List<ReadingWork> = emptyList(),
    val partial: List<PartialFailure> = emptyList(),
    val cache: CacheInfo = CacheInfo()
)
