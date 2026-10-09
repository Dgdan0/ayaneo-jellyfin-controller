package com.pocketds.hub.reader

import com.pocketds.hub.model.ReadingAudioManifest
import com.pocketds.hub.net.HubResult

/**
 * Where a stretch of read along's narration is heard (#19, A3): a [uri] (a
 * hub track, or a file taken out of the whole edition) and where in it the
 * stretch's audio file begins ([startMs], the chunk's start within the track).
 * The player clips each stretch at `startMs` plus its first sentence's begin.
 */
data class NarrationSource(val uri: String, val startMs: Long = 0, val cacheKey: String = "")

/** How read along gets its words and its narration (#19). */
sealed interface NarrationPlan {
    /** The edition without its audio, the narration streamed from the audiobook's tracks. */
    data class Stream(val manifest: ReadingAudioManifest) : NarrationPlan
    /** The whole edition, its audio taken out of it: what the hub cannot stream. */
    data object Whole : NarrationPlan
    /** The hub could not be asked: whatever this device already has. */
    data class Unreachable(val failure: HubResult.Failed) : NarrationPlan
}

/**
 * Read along streamed (#19): the slim edition's words and SMIL, and the
 * narration from the audiobook's own tracks, mapped by the hub, so the audio
 * is no longer downloaded twice (293 MB of Dark Matter's 294 was audio) nor
 * taken out of the edition on the device.
 */
object ReadAlongStream {
    /**
     * What to open: the stream when the hub mapped the edition's audio onto
     * the tracks; the whole edition when it cannot stream this book (409, a
     * missing route, an edition it could not map); what is here when it could
     * not be asked.
     */
    fun plan(answer: HubResult<ReadingAudioManifest>): NarrationPlan = when (answer) {
        is HubResult.Ok -> {
            val manifest = answer.value
            if (manifest.aligned && manifest.alignment?.audio?.isNotEmpty() == true && AudiobookStream.playable(manifest))
                NarrationPlan.Stream(manifest) else NarrationPlan.Whole
        }
        is HubResult.Failed -> if (AudiobookStream.downloadsWhole(answer)) NarrationPlan.Whole else NarrationPlan.Unreachable(answer)
    }

    /**
     * The [timeline] as the audio the hub mapped can play it: a sentence that begins at or past the end of its
     * file's audio is skipped, and one that runs past it ends there, rather than the voice reading on into the next
     * file's words (or the edition refused). A file's audio is its window of the track the hub mapped it to: from its
     * `startMs` to where the next file mapped to that track begins, else to the track's end. A file the hub did not
     * map, or on a track of unknown length, is left as it is ([sources] refuses the first). A stretch left with no
     * sentence goes; null when none is left, which is an edition with no narration. For an edition kept on the
     * device before the hub mended its clips, and for any the hub cannot mend.
     */
    fun fitted(timeline: ReadAlongTimeline, manifest: ReadingAudioManifest): ReadAlongTimeline? {
        val files = manifest.alignment?.audio.orEmpty()
        fun length(href: String): Long? {
            val file = files.firstOrNull { DocumentPath.name(it.href) == DocumentPath.name(href) } ?: return null
            val track = manifest.tracks.getOrNull(file.track) ?: return null
            val start = file.startMs.coerceAtLeast(0)
            files.filter { it.track == file.track && it.startMs > file.startMs }.minOfOrNull { it.startMs }?.let { return it - start }
            return if (track.durationMs > start) track.durationMs - start else null
        }
        val tracks = timeline.tracks.mapNotNull { stretch ->
            val end = length(stretch.audioHref) ?: return@mapNotNull stretch
            val kept = stretch.segments.filter { it.beginMs < end }.map { if (it.endMs > end) it.copy(endMs = end) else it }
            if (kept.isEmpty()) null else ReadAlongTrack(stretch.audioHref, kept)
        }
        return if (tracks.isEmpty()) null else ReadAlongTimeline(tracks)
    }

    /**
     * Each stretch of the [timeline] where it is heard: the track the hub
     * mapped its audio file to, under the manifest's revision ([url], which
     * only the hub's endpoints build), and where in the track that file begins.
     * Fails when the edition names a file the hub did not map: a sentence
     * heard from the wrong place is worse than no narration.
     */
    fun sources(timeline: ReadAlongTimeline, manifest: ReadingAudioManifest, sourceItemId: String, url: (index: Int) -> String): List<NarrationSource> {
        // The hub names a file as the archive does, decoded; the timeline's is DocumentPath's spelling of the same name (#61).
        val mapped = manifest.alignment?.audio.orEmpty().associateBy { DocumentPath.name(it.href) }
        return timeline.tracks.map { stretch ->
            val aligned = mapped[DocumentPath.name(stretch.audioHref)] ?: error("The hub did not map ${stretch.audioHref}")
            val track = manifest.tracks.getOrNull(aligned.track) ?: error("The hub mapped ${stretch.audioHref} to no track")
            NarrationSource(url(track.index), aligned.startMs.coerceAtLeast(0), AudiobookStream.cacheKey(sourceItemId, track))
        }
    }
}
