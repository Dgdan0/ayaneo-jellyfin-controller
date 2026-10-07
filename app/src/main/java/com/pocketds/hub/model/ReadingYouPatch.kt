package com.pocketds.hub.model

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** What one key of `PATCH /v1/reading/works/{id}/you` does (#39): leave it, set it, or clear it. */
sealed interface YouEdit<out T> {
    /** The key is left out of the body, and the hub leaves the value alone. */
    data object Keep : YouEdit<Nothing>
    /** The key is sent as `null`, and the hub forgets the value. */
    data object Clear : YouEdit<Nothing>
    /** The key is sent with this value. */
    data class To<T>(val value: T) : YouEdit<T>
}

/**
 * The body of a write to what this profile says about a book (#39): a rating (1 to 5), a
 * finish month ("YYYY-MM") and a read count, each kept, set or cleared. Built here, never by
 * hand, because the hub reads a key that is present as "set", `null` as "clear" and an absent
 * key as "leave", which a data class encoded with explicit nulls off cannot say.
 */
data class ReadingYouPatch(
    val rating: YouEdit<Int> = YouEdit.Keep,
    val finished: YouEdit<String> = YouEdit.Keep,
    val readCount: YouEdit<Int> = YouEdit.Keep
) {
    /** The hub refuses an empty body. */
    val isEmpty: Boolean get() = rating is YouEdit.Keep && finished is YouEdit.Keep && readCount is YouEdit.Keep

    /** Ratings and counts are JSON numbers: the hub refuses `"4"`. */
    fun toJson(): String = buildJsonObject {
        when (val edit = rating) { YouEdit.Keep -> Unit; YouEdit.Clear -> put("rating", JsonNull); is YouEdit.To -> put("rating", JsonPrimitive(edit.value)) }
        when (val edit = finished) { YouEdit.Keep -> Unit; YouEdit.Clear -> put("finished", JsonNull); is YouEdit.To -> put("finished", JsonPrimitive(edit.value)) }
        when (val edit = readCount) { YouEdit.Keep -> Unit; YouEdit.Clear -> put("readCount", JsonNull); is YouEdit.To -> put("readCount", JsonPrimitive(edit.value)) }
    }.toString()
}
