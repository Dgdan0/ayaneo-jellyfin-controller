package com.pocketds.hub.screens.library

/** Short controller labels for reading-detail actions whose glyphs have no visible text. */
object ReadingActionHint {
    fun label(key: String, text: String = "", description: String = ""): String = when (key) {
        "entry" -> text.substringBefore(" · ").ifBlank { "Open" }
        "format" -> "Change format"
        "list:want" -> if (description.startsWith("Remove ")) "Remove from Want to Read" else "Add to Want to Read"
        "list:more" -> "More actions"
        // The formats of a book's page (#39): each opens at your place.
        "list:format:audiobook" -> "Listen"
        "list:format:ebook" -> "Read"
        "list:format:readaloud" -> "Read along"
        else -> when {
            // A comic run's volume chip shows that volume's issues.
            key.startsWith("list:volume:") -> "Show volume"
            key.startsWith("continue:") -> "Continue reading"
            // A button that opens the reader says so: a series' "Continue · Book 6"
            // was offered as "Open", which reads as harmless. Its words, short.
            !key.startsWith("list:") && text.isNotBlank() && '\n' !in text -> text.substringBefore(" · ")
            else -> "Open"
        }
    }
}
