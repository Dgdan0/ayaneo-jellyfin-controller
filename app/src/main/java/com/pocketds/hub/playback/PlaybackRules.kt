package com.pocketds.hub.playback

import com.pocketds.hub.model.PlaybackTrickplay
import kotlin.math.roundToLong

object PlaybackRules {
    data class Quality(val label: String, val bitrate: Int)

    val qualities = listOf(
        Quality("Original", 0),
        Quality("40 Mbps", 40_000_000),
        Quality("20 Mbps", 20_000_000),
        Quality("10 Mbps", 10_000_000),
        Quality("5 Mbps", 5_000_000),
        Quality("2 Mbps", 2_000_000)
    )

    /**
     * A change to what plays (a track, the quality, a conversion, a version) as the
     * hub's select takes it, naming the version playing unless the change is another
     * version. Jellyfin applies an audio or subtitle stream index only with the media
     * source it belongs to; without one it converted the default track again, so a
     * language chosen in the player never reached the sound (#24, measured on 10.11.8).
     */
    fun selection(
        plan: com.pocketds.hub.model.PlaybackPrepareResponse,
        positionMillis: Long = plan.positionMillis,
        mediaSourceId: String? = null,
        audioStreamIndex: Int? = null,
        subtitleStreamIndex: Int? = null,
        maxBitrate: Int? = null,
        forceTranscode: Boolean? = null
    ) = com.pocketds.hub.model.PlaybackSelectBody(
        positionMillis = positionMillis,
        mediaSourceId = mediaSourceId ?: plan.selectedMediaSourceId.takeIf(String::isNotEmpty),
        audioStreamIndex = audioStreamIndex,
        subtitleStreamIndex = subtitleStreamIndex,
        maxBitrate = maxBitrate,
        forceTranscode = forceTranscode
    )

    fun seekStep(repeatCount: Int): Long = when {
        repeatCount >= 12 -> 60_000L
        repeatCount >= 5 -> 30_000L
        else -> 10_000L
    }

    fun clampSeek(position: Long, duration: Long): Long =
        position.coerceIn(0, duration.coerceAtLeast(0))

    /** A player teardown must never be confused with naturally reaching the end. */
    fun reachedNaturalEnd(positionMillis: Long, durationMillis: Long): Boolean =
        durationMillis > 0 && positionMillis >= durationMillis - 1_000L

    /** A full-width swipe scans a useful window without making short swipes too coarse. */
    fun scrubTarget(startMillis: Long, dragFraction: Float, durationMillis: Long): Long {
        if (durationMillis <= 0) return 0
        val window = minOf(durationMillis, (durationMillis / 3).coerceIn(120_000L, 1_200_000L))
        val delta = (dragFraction.coerceIn(-1f, 1f) * window).roundToLong()
        return clampSeek(startMillis + delta, durationMillis)
    }

    data class TrickplayFrame(
        val thumbnailIndex: Int,
        val tileIndex: Int,
        val column: Int,
        val row: Int
    )

    fun trickplayFrame(positionMillis: Long, info: PlaybackTrickplay): TrickplayFrame? {
        if (
            info.intervalMillis <= 0 || info.thumbnailCount <= 0 ||
            info.tileWidth <= 0 || info.tileHeight <= 0
        ) return null
        val thumbnail = (positionMillis.coerceAtLeast(0) / info.intervalMillis)
            .toInt()
            .coerceIn(0, info.thumbnailCount - 1)
        val perTile = info.tileWidth * info.tileHeight
        val offset = thumbnail % perTile
        return TrickplayFrame(
            thumbnail,
            thumbnail / perTile,
            offset % info.tileWidth,
            offset / info.tileWidth
        )
    }
}
