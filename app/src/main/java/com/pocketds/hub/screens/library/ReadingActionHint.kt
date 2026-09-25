package com.pocketds.hub.screens.library

/** Short controller labels for reading-detail actions whose glyphs have no visible text. */
object ReadingActionHint {
    fun label(key: String, text: String = "", description: String = ""): String = when (key) {
        "entry" -> text.substringBefore(" · ").ifBlank { "Open" }
        "format" -> "Change format"
        "list:want" -> if (description.startsWith("Remove ")) "Remove from Want to Read" else "Add to Want to Read"
        "list:more" -> "More actions"
        else -> "Open"
    }
}
