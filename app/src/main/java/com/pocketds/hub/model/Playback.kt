package com.pocketds.hub.model

import kotlinx.serialization.Serializable

@Serializable
data class SeriesPlayTargetResponse(
    val seriesId: String = "",
    val kind: String = "start",
    val item: LibraryItem = LibraryItem()
)

@Serializable
data class PlaybackPrepareBody(
    val startMode: String = "resume",
    val positionMillis: Long = 0,
    val mediaSourceId: String? = null,
    val audioStreamIndex: Int? = null,
    val subtitleStreamIndex: Int? = null,
    val maxBitrate: Int = 0,
    val forceTranscode: Boolean = false,
    val device: PlaybackDevice = PlaybackDevice(),
    val capabilities: PlaybackCapabilities = PlaybackCapabilities()
)

@Serializable
data class PlaybackDevice(
    val id: String = "",
    val name: String = "Pocket DS",
    val version: String = "0.1"
)

@Serializable
data class PlaybackCapabilities(
    val width: Int = 0,
    val height: Int = 0,
    val maxAudioChannels: Int = 2,
    val videoCodecs: List<String> = listOf("h264"),
    val audioCodecs: List<String> = listOf("aac", "mp3"),
    val hdrTypes: List<String> = emptyList()
)

@Serializable
data class PlaybackPrepareResponse(
    val sessionId: String = "",
    val item: PlaybackItem = PlaybackItem(),
    val positionMillis: Long = 0,
    val durationMillis: Long = 0,
    val mediaUrl: String = "",
    val mimeType: String = "video/*",
    val playMethod: String = "",
    val transcodeReason: String = "",
    val bitrate: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val frameRate: Double = 0.0,
    val hdr: String = "",
    val videoCodec: String = "",
    val audioCodec: String = "",
    val sources: List<PlaybackSource> = emptyList(),
    val audioTracks: List<PlaybackTrack> = emptyList(),
    val subtitleTracks: List<PlaybackTrack> = emptyList(),
    val selectedMediaSourceId: String = "",
    val selectedAudioIndex: Int? = null,
    val selectedSubtitleIndex: Int? = null,
    val previousItem: PlaybackItem? = null,
    val nextItem: PlaybackItem? = null,
    val trickplay: PlaybackTrickplay? = null,
    val previewUrl: String = "",
    val offline: Boolean = false,
    val offlineDownloadId: String = ""
)

@Serializable
data class PlaybackTrickplay(
    val tileUrl: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val tileWidth: Int = 0,
    val tileHeight: Int = 0,
    val thumbnailCount: Int = 0,
    val intervalMillis: Long = 0
)

@Serializable
data class PlaybackItem(
    val id: String = "",
    val type: String = "",
    val title: String = "",
    val seriesTitle: String = "",
    val seriesId: String = "",
    val seasonId: String = "",
    val seasonNumber: Int = 0,
    val episodeNumber: Int = 0
) {
    fun displayTitle(): String = when {
        seriesTitle.isNotEmpty() && seasonNumber > 0 && episodeNumber > 0 ->
            "$seriesTitle · S${seasonNumber}E${episodeNumber} · $title"
        seriesTitle.isNotEmpty() -> "$seriesTitle · $title"
        else -> title
    }
}

@Serializable
data class PlaybackSource(
    val id: String = "",
    val name: String = "",
    val container: String = "",
    val sizeBytes: Long = 0,
    val bitrate: Int = 0
)

@Serializable
data class PlaybackTrack(
    val index: Int = -1,
    val type: String = "",
    val label: String = "",
    val language: String = "",
    val codec: String = "",
    val channels: Int = 0,
    val channelLayout: String = "",
    val default: Boolean = false,
    val forced: Boolean = false,
    val hearingImpaired: Boolean = false,
    val external: Boolean = false,
    val externalUrl: String = ""
)

@Serializable
data class PlaybackSelectBody(
    val positionMillis: Long = 0,
    val mediaSourceId: String? = null,
    val audioStreamIndex: Int? = null,
    val subtitleStreamIndex: Int? = null,
    val maxBitrate: Int? = null,
    val forceTranscode: Boolean? = null
)

@Serializable
data class PlaybackEventBody(
    val type: String,
    val sequence: Long,
    val positionMillis: Long,
    val paused: Boolean = false,
    val muted: Boolean = false,
    val volume: Int = 100
)
