package com.pocketds.hub.reader

import android.graphics.RectF
import org.json.JSONObject

/**
 * What is selected on the page (#62), as the card's actions need it: the words, the quote that anchors a highlight made of them
 * ([AnnotationQuote]), the file they are in ([DocumentPath]'s spelling), Readium's locator for them (a hint kept beside a
 * highlight) and where they are on the screen, for the card to stand beside.
 */
class ReaderSelection(
    val text: String,
    val quote: AnnotationQuote,
    val document: String,
    val locator: JSONObject,
    val rect: RectF
)

/** The Highlights tab's filters (#62): all of them, one colour, or the ones with a note. */
enum class HighlightFilter(val label: String, val description: String) {
    ALL("All", "All highlights"),
    YELLOW("Yellow", "Yellow highlights"),
    BLUE("Blue", "Blue highlights"),
    PINK("Pink", "Pink highlights"),
    GREEN("Green", "Green highlights"),
    NOTES("With notes", "Highlights with notes");

    fun apply(list: List<ReadingAnnotation>): List<ReadingAnnotation> = when (this) {
        ALL -> list
        NOTES -> list.filter { it.hasNote }
        else -> list.filter { it.color == name.lowercase() }
    }
}
