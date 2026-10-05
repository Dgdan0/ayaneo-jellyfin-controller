package com.pocketds.hub.reader

import com.pocketds.hub.model.ReadingAudioPosition
import com.pocketds.hub.model.ReadingAudioTrack
import com.pocketds.hub.state.Fmt
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * A listening place as the hub keeps it (#19, A4): a track of the manifest, by
 * its id, which survives a new order, and a moment in it. It is the `audio`
 * checkpoint's [ReadingLocation]: local first, then the durable outbox, the
 * conflict sheet and a write checked against the place last read (`expected`),
 * as a book's page is.
 *
 * A place is kept exactly as the hub will read it back ([canonical]), because
 * the outbox decides by equality: a place the hub returned differently from
 * how it was sent would look like another device moving the book. So a moment
 * past a track's end is its end, and the last two seconds of the book are the
 * book finished, which the hub writes as the end of its last track.
 *
 * The hub stamps every write with its own clock. Nothing here reads a
 * timestamp: whether a place is newer is the outbox's question (a local write
 * not yet sent wins) and the base's (what this device last read).
 */
data class AudioPlace(val trackId: String, val offsetMs: Long, val completed: Boolean = false) {
    init { require(trackId.isNotBlank()); require(offsetMs >= 0) }

    fun location(): ReadingLocation = ReadingLocation(locator = buildJsonObject {
        put(TRACK_ID, trackId); put(OFFSET_MS, offsetMs); put(COMPLETED, completed)
    })

    /** Where to open: the part and the moment, or the start of the book once it is finished. */
    fun openAt(tracks: List<ReadingAudioTrack>): Pair<Int, Long> {
        if (completed) return 0 to 0L
        val part = tracks.indexOfFirst { it.id == trackId }
        if (part < 0) return 0 to 0L
        val length = tracks[part].durationMs
        return part to if (length > 0) offsetMs.coerceIn(0, length) else offsetMs
    }

    companion object {
        const val KIND = "audio"
        private const val TRACK_ID = "trackId"
        private const val OFFSET_MS = "offsetMs"
        private const val COMPLETED = "completed"
        /** The hub's own rule: the last two seconds of the last track are the end of the book. */
        const val FINISHED_MS = 2_000L

        /** The place a checkpoint holds, or null for a location of another kind. */
        fun of(location: ReadingLocation?): AudioPlace? {
            val locator = location?.locator ?: return null
            val track = (locator[TRACK_ID] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
            val offset = (locator[OFFSET_MS] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 } ?: return null
            return AudioPlace(track, offset, (locator[COMPLETED] as? JsonPrimitive)?.booleanOrNull == true)
        }

        /** The hub's answer as a place: what it sent, never its timestamp or how exact it is. */
        fun fromServer(position: ReadingAudioPosition): AudioPlace? =
            position.trackId.takeIf(String::isNotBlank)?.let { AudioPlace(it, position.offsetMs.coerceAtLeast(0), position.completed) }

        /**
         * [part] and [offsetMs] on the player as the place the hub will read
         * back: within the track's length, and the book finished in the last
         * [FINISHED_MS] of its last track (written as that track's end).
         */
        fun canonical(tracks: List<ReadingAudioTrack>, part: Int, offsetMs: Long, completed: Boolean = false): AudioPlace? {
            if (tracks.isEmpty()) return null
            val last = tracks.last()
            val finished = { AudioPlace(last.id, last.durationMs.coerceAtLeast(0), true) }
            if (completed) return finished()
            val track = tracks.getOrNull(part) ?: return null
            val offset = if (track.durationMs > 0) offsetMs.coerceIn(0, track.durationMs) else offsetMs.coerceAtLeast(0)
            if (part == tracks.lastIndex && track.durationMs > 0 && offset >= track.durationMs - FINISHED_MS) return finished()
            return AudioPlace(track.id, offset)
        }

        /**
         * The write the outbox sends: the place, and `expected`, the place this
         * device last read from the hub (null when it read none), which the hub
         * checks before it writes. An unknown base sends no expectation.
         */
        fun body(local: AudioPlace, base: AudioPlace?, baseKnown: Boolean): JsonObject = buildJsonObject {
            put(TRACK_ID, local.trackId)
            put(OFFSET_MS, local.offsetMs)
            put(COMPLETED, local.completed)
            if (baseKnown) put("expected", base?.let { buildJsonObject { put(TRACK_ID, it.trackId); put(OFFSET_MS, it.offsetMs) } } ?: JsonNull)
        }

        /** A place in words for the choose-which sheet: "Part 3 of 8 · 1:02:13", "Finished". */
        fun label(location: ReadingLocation?, tracks: List<ReadingAudioTrack>): String {
            val place = of(location) ?: return location?.label().orEmpty()
            if (place.completed) return "Finished"
            val part = tracks.indexOfFirst { it.id == place.trackId }
            if (part < 0) return Fmt.clock(place.offsetMs)
            return "Part ${part + 1} of ${tracks.size} · ${Fmt.clock(place.offsetMs)}"
        }

        /** Some of the book has been heard: a moment past its start, or the end. */
        fun started(location: ReadingLocation): Boolean {
            val place = of(location) ?: return false
            return place.completed || place.offsetMs > 0
        }

        /**
         * The track a place this device kept by part number (the ZIP's order) is
         * on (#19): the track of the same size when the old order is known and
         * the size is one track's alone, else the same number.
         */
        fun legacyTrack(part: Int, partSizes: List<Long>, tracks: List<ReadingAudioTrack>): Int? {
            if (tracks.isEmpty()) return null
            val size = partSizes.getOrNull(part)?.takeIf { it > 0 }
            val bySize = size?.let { wanted -> tracks.indices.filter { tracks[it].bytes == wanted } }
            if (bySize != null && bySize.size == 1) return bySize.single()
            return part.takeIf { it in tracks.indices }
        }
    }
}
