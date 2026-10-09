package com.pocketds.hub.reader

import com.pocketds.hub.model.ReadingAudioManifest
import com.pocketds.hub.ui.ChoiceOverlay
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * "You listened further on <device>" (#62): the one question, asked when a listening session begins and the hub holds a place another
 * device wrote that is further on than this device's own. Go there takes it; Stay here keeps ours, and the hub's place becomes ours.
 * [ListenedFurther] holds the rule; this finds the two places in either kind of book and says where and when.
 */
object AwayPrompt {
    /** The audiobook: [ours] is the place this device had settled on, [theirs] the hub's, by the manifest's tracks. */
    fun forAudio(manifest: ReadingAudioManifest, ours: AudioPlace?, theirs: AudioPlace?, writer: ReadingProgress.RemoteWriter?, nowMs: Long): ListenedFurther.Away? {
        if (ours == null || theirs == null || writer == null || ours == theirs) return null
        val total = manifest.tracks.sumOf { it.durationMs }.takeIf { it > 0 } ?: return null
        val here = ours.progress(manifest.tracks) ?: return null
        val there = theirs.progress(manifest.tracks) ?: return null
        return ListenedFurther.decide(here * total, there * total, inMs = true, byThisDevice = writer.byThisDevice, device = writer.device,
            where = chapterAt(manifest, theirs), ageMs = nowMs - writer.stampMs)
    }

    /** A book's text: the places are Readium locators, compared by how far through the book they are. */
    fun forText(ours: ReadingLocation?, theirs: ReadingLocation?, writer: ReadingProgress.RemoteWriter?, nowMs: Long): ListenedFurther.Away? {
        if (writer == null) return null
        val here = share(ours) ?: return null
        val there = share(theirs) ?: return null
        val title = (theirs?.locator?.get("title") as? JsonPrimitive)?.contentOrNull.orEmpty()
        return ListenedFurther.decide(here, there, inMs = false, byThisDevice = writer.byThisDevice, device = writer.device, where = title, ageMs = nowMs - writer.stampMs)
    }

    private fun share(location: ReadingLocation?): Double? =
        (((location?.locator?.get("locations") as? JsonObject)?.get("totalProgression")) as? JsonPrimitive)?.doubleOrNull

    /** The chapter (or the part) the place is in, by the manifest's own chapters. */
    fun chapterAt(manifest: ReadingAudioManifest, place: AudioPlace): String {
        val part = manifest.tracks.indexOfFirst { it.id == place.trackId }.takeIf { it >= 0 } ?: return ""
        val chapters = manifest.chapters.filter { it.track == part && it.startMs <= place.offsetMs }.maxByOrNull { it.startMs }
        return chapters?.title?.takeIf(String::isNotBlank) ?: "Part ${part + 1}"
    }

    /** Asks it: true to go there, false to stay. Ⓑ is Stay here: nothing moves unless it is chosen. [quote] is the sentence there, when it is known. */
    suspend fun ask(overlay: ChoiceOverlay, away: ListenedFurther.Away, quote: String?): Boolean = suspendCancellableCoroutine { continuation ->
        overlay.ask(ListenedFurther.title(away), ListenedFurther.detail(away),
            listOf(ChoiceOverlay.Choice("go", "Go there", quote?.let { "“${it.take(160)}”" }.orEmpty()), ChoiceOverlay.Choice("stay", "Stay here")),
            startIndex = 0, onCancel = { if (continuation.isActive) continuation.resume(false) }) { id ->
            if (continuation.isActive) continuation.resume(id == "go")
        }
        continuation.invokeOnCancellation { overlay.post { overlay.dismiss() } }
    }
}
