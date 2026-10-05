package com.pocketds.hub.reader

import com.pocketds.hub.model.ReadingAudioManifest
import com.pocketds.hub.model.ReadingAudioTrack
import com.pocketds.hub.net.FailureKind
import com.pocketds.hub.net.HubResult

/**
 * An audiobook the hub streams (#19, A3): its manifest as the player's parts.
 * Storyteller rebuilt a ZIP of the whole book on every request, and the app
 * downloaded and unpacked all of it before the first sound (2.66 GB for one
 * book); the hub now serves each track by Range, so a book starts at once.
 */
object AudiobookStream {
    /** The hub's code for a book whose files it cannot read: the whole book is downloaded instead. */
    const val NOT_STREAMABLE = "audio_not_streamable"
    /** The hub's code for a track asked for under an old revision: read the manifest again. */
    const val CHANGED = "audio_changed"

    /**
     * The parts to play, in the manifest's order: each track's URL under the
     * manifest's revision (from [url], which only the hub's endpoints build),
     * its length and size, and the key its bytes are kept under.
     */
    fun parts(manifest: ReadingAudioManifest, sourceItemId: String, url: (index: Int) -> String): List<AudiobookPart> =
        manifest.tracks.map { track ->
            AudiobookPart(
                title = track.title.ifBlank { "Track ${track.index + 1}" },
                uri = url(track.index),
                durationMs = track.durationMs.takeIf { it > 0 },
                bytes = track.bytes.takeIf { it > 0 },
                trackId = track.id,
                cacheKey = cacheKey(sourceItemId, track)
            )
        }

    /**
     * The key a track's bytes are kept under on this device: the file, by its
     * id and its validator. Not the URL: its revision also changes when the
     * book's read-along edition does, and the same bytes would be fetched again.
     */
    fun cacheKey(sourceItemId: String, track: ReadingAudioTrack): String =
        "$CACHE_PREFIX$sourceItemId:${track.id}:" + track.etag.trim('"').ifBlank { "b${track.bytes}" }

    /** Every cached track of a book starts with this. */
    fun cachePrefix(sourceItemId: String): String = "$CACHE_PREFIX$sourceItemId:"

    /** A manifest that can be played: a revision, and tracks that each have an id and a place in the route. */
    fun playable(manifest: ReadingAudioManifest): Boolean =
        manifest.revision.isNotBlank() && manifest.tracks.isNotEmpty() &&
            manifest.tracks.all { it.id.isNotBlank() && it.index >= 0 }

    /**
     * Whether a manifest that could not be read means the whole book is to be
     * downloaded: the hub cannot read the book's files, or it is a hub from
     * before the routes (404). Anything else (no network, a busy server) is a
     * failure to show and retry.
     */
    fun downloadsWhole(failure: HubResult.Failed): Boolean =
        failure.code == NOT_STREAMABLE || failure.kind == FailureKind.NOT_FOUND

    private const val CACHE_PREFIX = "reading-audio:"
}
