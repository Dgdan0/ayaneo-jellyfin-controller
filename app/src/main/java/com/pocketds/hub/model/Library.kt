package com.pocketds.hub.model

import kotlinx.serialization.Serializable

@Serializable
data class LibraryView(
    val id: String = "",
    val name: String = "",
    val kind: String = "",
    val image: String = ""
)

@Serializable
data class LibraryResponse(
    val views: List<LibraryView> = emptyList(),
    val partial: List<PartialFailure> = emptyList(),
    val cache: CacheInfo = CacheInfo()
)

@Serializable
data class LibraryItemsResponse(
    val viewId: String = "",
    val title: String = "",
    val page: Int = 1,
    val totalPages: Int = 1,
    val total: Int = 0,
    val sortedBy: String = "name",
    val sortOrder: String = "asc",
    val items: List<SearchHit> = emptyList(),
    val partial: List<PartialFailure> = emptyList(),
    val cache: CacheInfo = CacheInfo()
)

@Serializable
data class LibraryItem(
    val id: String = "",
    val type: String = "unknown",
    val mediaKey: String = "",
    val title: String = "",
    val subtitle: String = "",
    val seriesTitle: String = "",
    val seriesId: String = "",
    val seasonId: String = "",
    val parentId: String = "",
    val year: Int = 0,
    val indexNumber: Int = 0,
    val seasonNumber: Int = 0,
    val overview: String = "",
    val originalTitle: String = "",
    val premiereDate: String = "",
    val runtimeSeconds: Int = 0,
    val rating: Double = 0.0,
    val criticRating: Double = 0.0,
    val officialRating: String = "",
    val genres: List<String> = emptyList(),
    val studios: List<String> = emptyList(),
    val people: List<LibraryPerson> = emptyList(),
    val mediaVersions: List<LibraryMediaVersion> = emptyList(),
    val played: Boolean = false,
    val favorite: Boolean = false,
    val unplayedCount: Int = 0,
    val progress: Double = 0.0,
    val positionSeconds: Int = 0,
    val poster: String = "",
    val thumb: String = "",
    val backdrop: String = ""
)

@Serializable
data class LibraryPerson(
    val id: String = "",
    val name: String = "",
    val role: String = "",
    val type: String = "",
    val image: String = ""
)

@Serializable
data class LibraryMediaTrack(
    val index: Int = 0,
    val type: String = "",
    val codec: String = "",
    val profile: String = "",
    val language: String = "",
    val title: String = "",
    val channels: Int = 0,
    val bitrate: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val frameRate: Double = 0.0,
    val hdr: String = "",
    val default: Boolean = false,
    val forced: Boolean = false
)

@Serializable
data class LibraryMediaVersion(
    val id: String = "",
    val name: String = "",
    val container: String = "",
    val sizeBytes: Long = 0,
    val bitrate: Int = 0,
    val tracks: List<LibraryMediaTrack> = emptyList()
)

@Serializable
data class LibraryStateRequest(
    val played: Boolean? = null,
    val favorite: Boolean? = null
)

@Serializable
data class LibraryItemResponse(
    val item: LibraryItem = LibraryItem(),
    val partial: List<PartialFailure> = emptyList(),
    val cache: CacheInfo = CacheInfo()
)

@Serializable
data class LibrarySeasonsResponse(
    val seriesId: String = "",
    val items: List<LibraryItem> = emptyList(),
    val partial: List<PartialFailure> = emptyList(),
    val cache: CacheInfo = CacheInfo()
)

@Serializable
data class LibraryEpisodesResponse(
    val seriesId: String = "",
    val seasonId: String = "",
    val page: Int = 1,
    val totalPages: Int = 1,
    val total: Int = 0,
    val items: List<LibraryItem> = emptyList(),
    val partial: List<PartialFailure> = emptyList(),
    val cache: CacheInfo = CacheInfo()
)
