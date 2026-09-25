package com.pocketds.hub.reader

import kotlinx.serialization.json.*

/** Standard text locator plus an optional exact narration offset understood by this app. */
object ReadAlongLocation {
    fun resume(locator: JsonObject, timeline: ReadAlongTimeline): ReadAlongPosition? {
        val locations = locator["locations"] as? JsonObject ?: return null
        val href = (locator["href"] as? JsonPrimitive)?.contentOrNull ?: return null
        val fragments = (locations["fragments"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        val saved = locations["pocketdsAudio"] as? JsonObject
        val track = (saved?.get("track") as? JsonPrimitive)?.intOrNull
        val offset = (saved?.get("offsetMs") as? JsonPrimitive)?.longOrNull
        if (track != null && offset != null) {
            val audio = timeline.tracks.getOrNull(track)
            val segment = if (audio != null && offset in 0..audio.durationMs) {
                timeline.active(track, offset) ?: audio.segments.lastOrNull { it.endMs <= audio.startMs + offset }
            } else null
            if (segment?.textHref == href && segment.fragment in fragments) return ReadAlongPosition(track, offset)
        }
        return fragments.firstNotNullOfOrNull { timeline.find(href, it) }
    }

    fun save(locator: JsonObject, timeline: ReadAlongTimeline, point: ReadAlongPosition, completed: Boolean): JsonObject {
        val track = timeline.tracks.getOrNull(point.track) ?: return locator
        val segment = timeline.active(point.track, point.offsetMs)
            ?: track.segments.lastOrNull { it.endMs <= track.startMs + point.offsetMs }
            ?: track.segments.first()
        val locations = (locator["locations"] as? JsonObject).orEmpty().toMutableMap()
        locations.remove("cssSelector")
        locations["fragments"] = JsonArray(listOf(JsonPrimitive(segment.fragment)))
        locations["pocketdsAudio"] = buildJsonObject { put("track", point.track); put("offsetMs", point.offsetMs) }
        if (completed) locations["totalProgression"] = JsonPrimitive(1.0)
        return JsonObject(locator.toMutableMap().apply {
            put("href", JsonPrimitive(segment.textHref)); put("locations", JsonObject(locations)); remove("text")
        })
    }
}
