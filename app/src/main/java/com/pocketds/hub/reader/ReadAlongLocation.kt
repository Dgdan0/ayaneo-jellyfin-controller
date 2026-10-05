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
    /** Where the narration resumes: the start of the sentence the locator names. */
    fun resume(locator: JsonObject, timeline: ReadAlongTimeline): ReadAlongPosition? {
        val locations = locator["locations"] as? JsonObject ?: return null
        val href = (locator["href"] as? JsonPrimitive)?.contentOrNull ?: return null
        val fragments = (locations["fragments"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        return fragments.firstNotNullOfOrNull { timeline.find(href, it) }
    }

    /** The page's locator moved to the sentence playing at [point], finished when [completed]. */
    fun save(locator: JsonObject, timeline: ReadAlongTimeline, point: ReadAlongPosition, completed: Boolean): JsonObject {
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
            put("href", JsonPrimitive(segment.textHref)); put("locations", JsonObject(locations)); remove("text")
        })
    }
}
