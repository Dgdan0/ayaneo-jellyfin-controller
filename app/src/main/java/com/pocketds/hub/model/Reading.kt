package com.pocketds.hub.model

import kotlinx.serialization.Serializable

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
