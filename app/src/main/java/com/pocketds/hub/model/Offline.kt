package com.pocketds.hub.model

import kotlinx.serialization.Serializable

@Serializable
data class OfflineSource(
    val id: String = "",
    val name: String = "",
    val container: String = "",
    val mimeType: String = "video/*",
    val sizeBytes: Long = 0,
    val bitrate: Int = 0,
    val tracks: List<PlaybackTrack> = emptyList()
)

@Serializable
data class OfflineSelectionItem(
    val item: LibraryItem = LibraryItem(),
    val sources: List<OfflineSource> = emptyList(),
    val estimatedSizeBytes: Long = 0,
    val available: Boolean = false
)

@Serializable
data class OfflineSelectionSeason(
    val season: LibraryItem = LibraryItem(type = "season"),
    val episodes: List<OfflineSelectionItem> = emptyList()
)

@Serializable
data class OfflineSelectionResponse(
    val series: LibraryItem = LibraryItem(type = "series"),
    val seasons: List<OfflineSelectionSeason> = emptyList(),
    val episodeCount: Int = 0,
    val estimatedSizeBytes: Long = 0,
    val playTargetId: String = ""
)

@Serializable
data class OfflinePrepareBody(
    val batchKey: String,
    val seriesId: String = "",
    val items: List<OfflinePrepareItem>
)

@Serializable
data class OfflinePrepareItem(
    val clientItemKey: String,
    val itemId: String,
    val mediaSourceId: String = ""
)

@Serializable
data class OfflineSubtitle(
    val track: PlaybackTrack = PlaybackTrack(),
    val url: String = ""
)

@Serializable
data class OfflineManifest(
    val grantId: String = "",
    val batchKey: String = "",
    val clientItemKey: String = "",
    val expiresAt: Long = 0,
    val item: LibraryItem = LibraryItem(),
    val source: OfflineSource = OfflineSource(),
    val mediaUrl: String = "",
    val subtitles: List<OfflineSubtitle> = emptyList()
)

@Serializable
data class OfflinePrepareResponse(
    val batchKey: String = "",
    val items: List<OfflineManifest> = emptyList()
)

@Serializable
data class OfflineProgressEvent(
    val clientEventKey: String,
    val itemId: String,
    val positionMillis: Long,
    val durationMillis: Long,
    val completed: Boolean = false,
    val occurredAt: Long
)

@Serializable
data class OfflineProgressSyncBody(val events: List<OfflineProgressEvent>)

@Serializable
data class OfflineProgressResult(
    val clientEventKey: String = "",
    val itemId: String = "",
    val status: String = "",
    val serverPositionMillis: Long = 0
)

@Serializable
data class OfflineProgressSyncResponse(val results: List<OfflineProgressResult> = emptyList())
