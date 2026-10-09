package com.pocketds.hub.reader

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The body of a write to the hub (#62): only what it accepts (it refuses a field it does not know, so a tombstone's `deleted`
 * and the `syncedAt` it stamped never go back), with the note and the passage cut to what it keeps, and Readium's locator left
 * out when it would not fit (it is a hint only).
 */
object AnnotationBodies {
    const val LOCATOR_BYTES = 8 * 1024

    fun of(a: ReadingAnnotation): String = buildJsonObject {
        put("id", JsonPrimitive(a.id))
        put("color", JsonPrimitive(a.color))
        put("note", JsonPrimitive(AnnotationLimits.clip(a.note, AnnotationLimits.NOTE_BYTES)))
        put("document", JsonPrimitive(a.document))
        put("quote", buildJsonObject {
            put("before", JsonPrimitive(a.quote.before))
            put("highlight", JsonPrimitive(AnnotationLimits.clip(a.quote.highlight, AnnotationLimits.PASSAGE_BYTES)))
            put("after", JsonPrimitive(a.quote.after))
        })
        a.locator?.takeIf { it.toString().toByteArray(Charsets.UTF_8).size <= LOCATOR_BYTES }?.let { put("locator", it) }
        put("createdAt", JsonPrimitive(a.createdAt))
        put("updatedAt", JsonPrimitive(a.updatedAt))
    }.toString()
}
