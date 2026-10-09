package com.pocketds.hub.reader

import kotlinx.serialization.json.*

/**
 * Read along's place is a standard text locator: the sentence being read, as
 * any reader of the book understands it (#19). The hub turns a listener's place
 * into the sentence spoken there and a sentence into a moment of the audiobook,
 * so the place follows you between reading along and listening; the private
 * `pocketdsAudio` offset this app once added is neither written nor read.
 */
object ReadAlongLocation {
    /**
     * Where the narration resumes: the start of the sentence the locator names. The locator's href is in whatever spelling it
     * was saved in (Readium's, percent-encoded, or an older build's decoded one), so it is made [DocumentPath]'s first (#61).
     */
    fun resume(locator: JsonObject, timeline: ReadAlongTimeline): ReadAlongPosition? {
        val locations = locator["locations"] as? JsonObject ?: return null
        val href = (locator["href"] as? JsonPrimitive)?.contentOrNull?.let(DocumentPath::of) ?: return null
        val fragments = (locations["fragments"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        return fragments.firstNotNullOfOrNull { timeline.find(href, it) }
    }

    /**
     * How far through its part of the book, and through the book, the sentence at [point] is (#49), from the share
     * of that part's narration that comes before it: what the page says when there is a page to ask. With the
     * screen off there is none, and a book listened to for an hour would keep saying where it was when the screen
     * went. [start] and [end] are where the part begins and ends in the book, 0 to 1. Null when the part is not narrated.
     */
    fun estimate(timeline: ReadAlongTimeline, point: ReadAlongPosition, start: Double, end: Double): Estimate? {
        val track = timeline.tracks.getOrNull(point.track) ?: return null
        val now = track.startMs + point.offsetMs.coerceAtLeast(0)
        val href = (timeline.active(point.track, point.offsetMs) ?: track.segments.lastOrNull { it.endMs <= now } ?: track.segments.firstOrNull())
            ?.textHref ?: return null
        var before = 0L
        var total = 0L
        timeline.tracks.forEachIndexed { index, value ->
            value.segments.forEach { segment ->
                if (segment.textHref != href) return@forEach
                val length = (segment.endMs - segment.beginMs).coerceAtLeast(0)
                total += length
                if (index < point.track) before += length
                else if (index == point.track) before += (minOf(now, segment.endMs) - segment.beginMs).coerceIn(0, length)
            }
        }
        if (total <= 0) return null
        val inPart = (before.toDouble() / total).coerceIn(0.0, 1.0)
        return Estimate(href, inPart, (start + (end - start) * inPart).coerceIn(0.0, 1.0))
    }

    data class Estimate(val href: String, val progression: Double, val totalProgression: Double)

    /** [locator] with how far through its part and the book it is, as [estimate] says. */
    fun withProgress(locator: JsonObject, estimate: Estimate): JsonObject {
        val locations = (locator["locations"] as? JsonObject).orEmpty().toMutableMap()
        locations["progression"] = JsonPrimitive(estimate.progression)
        locations["totalProgression"] = JsonPrimitive(estimate.totalProgression)
        return JsonObject(locator.toMutableMap().apply { put("locations", JsonObject(locations)) })
    }

    /**
     * The page's locator moved to the sentence playing at [point], finished when [completed]. The sentence's document is
     * written as [spell] says (the book's own spelling of it, which Readium resolves; the reader supplies it from the
     * publication): the timeline holds [DocumentPath]'s decoded one, which is not a valid href when it has a space in it (#61).
     */
    fun save(locator: JsonObject, timeline: ReadAlongTimeline, point: ReadAlongPosition, completed: Boolean,
             spell: (String) -> String = DocumentPath::encode): JsonObject {
        val track = timeline.tracks.getOrNull(point.track) ?: return locator
        val segment = timeline.active(point.track, point.offsetMs)
            ?: track.segments.lastOrNull { it.endMs <= track.startMs + point.offsetMs }
            ?: track.segments.first()
        val locations = (locator["locations"] as? JsonObject).orEmpty().toMutableMap()
        locations.remove("cssSelector")
        locations.remove("pocketdsAudio")
        locations["fragments"] = JsonArray(listOf(JsonPrimitive(segment.fragment)))
        if (completed) locations["totalProgression"] = JsonPrimitive(1.0)
        return JsonObject(locator.toMutableMap().apply {
            put("href", JsonPrimitive(spell(segment.textHref))); put("locations", JsonObject(locations)); remove("text")
        })
    }
}
