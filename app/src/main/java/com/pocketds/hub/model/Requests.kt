package com.pocketds.hub.model

import kotlinx.serialization.Serializable

/**
 * What can be chosen when requesting a title, and what came back from an
 * interactive search.
 *
 * Same rule as the rest of the wire format: every field defaults, because an
 * installed APK outlives the hub version it was built against.
 */

@Serializable
data class RequestOption(
    val id: Int = 0,
    val label: String = "",
    val detail: String = "",
    val default: Boolean = false
)

@Serializable
data class RootFolderOption(
    val id: Int = 0,
    val path: String = "",
    /** Last two path segments -- one is not enough to tell two "Movies" apart. */
    val label: String = "",
    val freeSpaceBytes: Long = 0,
    val default: Boolean = false
)

@Serializable
data class SeasonOption(
    val number: Int = 0,
    val name: String = "",
    val episodeCount: Int = 0,
    val year: Int = 0
)

@Serializable
data class RequestOptions(
    val key: String = "",
    val type: String = "movie",
    val title: String = "",
    val service: String = "",
    val serverId: Int = 0,
    val serverName: String = "",
    val has4k: Boolean = false,
    val profiles: List<RequestOption> = emptyList(),
    val rootFolders: List<RootFolderOption> = emptyList(),
    val tags: List<RequestOption> = emptyList(),
    val seasons: List<SeasonOption> = emptyList(),
    val partial: List<PartialFailure> = emptyList(),
    val cache: CacheInfo = CacheInfo()
) {
    val defaultProfileIndex: Int get() = profiles.indexOfFirst { it.default }.coerceAtLeast(0)
    val defaultFolderIndex: Int get() = rootFolders.indexOfFirst { it.default }.coerceAtLeast(0)
}

/**
 * One candidate release.
 *
 * Note what is *not* here: no guid, no download URL. The hub strips both --
 * every one of them carried the Prowlarr API key in clear text -- and hands
 * over an opaque [id] instead, which is also what stops a token asking the hub
 * to fetch an arbitrary address.
 */
@Serializable
data class Release(
    val id: String = "",
    val title: String = "",
    val indexer: String = "",
    val quality: String = "",
    val protocol: String = "",
    val sizeBytes: Long = 0,
    val seeders: Int = 0,
    val leechers: Int = 0,
    val ageDays: Int = 0,
    val releaseGroup: String = "",
    val languages: List<String> = emptyList(),
    val freeleech: Boolean = false,
    val score: Int = 0,
    val rejected: Boolean = false,
    /** The *arr's own sentences, verbatim. Never paraphrased. */
    val rejections: List<String> = emptyList()
)

@Serializable
data class ReleasesResponse(
    val key: String = "",
    val title: String = "",
    val service: String = "",
    val season: Int = 0,
    val releases: List<Release> = emptyList(),
    val accepted: Int = 0,
    val partial: List<PartialFailure> = emptyList(),
    val cache: CacheInfo = CacheInfo()
)

@Serializable
data class GrabBody(
    val releaseId: String,
    val season: Int = 0
)

@Serializable
data class GrabResponse(
    val ok: Boolean = false,
    val title: String = "",
    val quality: String = "",
    val service: String = ""
)

/**
 * One row of the Discover screen. The Infuse/Findroid shape: a title and a
 * horizontal strip of posters that pages as you reach its end.
 */
@Serializable
data class DiscoverRow(
    val id: String = "",
    val title: String = "",
    val page: Int = 1,
    /** Six-figure totals upstream, so really just "there is more". */
    val totalPages: Int = 1,
    val items: List<SearchHit> = emptyList()
) {
    val hasMore: Boolean get() = page < totalPages
}

@Serializable
data class DiscoverResponse(
    val rows: List<DiscoverRow> = emptyList(),
    val partial: List<PartialFailure> = emptyList(),
    val cache: CacheInfo = CacheInfo()
)
