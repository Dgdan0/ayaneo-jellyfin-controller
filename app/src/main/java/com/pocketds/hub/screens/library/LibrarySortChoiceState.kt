package com.pocketds.hub.screens.library

/** Saved sort choice stays distinct from the row currently under the D-pad cursor. */
data class LibrarySortChoiceState(val field: String, val ascending: Boolean) {
    fun isFieldSelected(candidate: String): Boolean = candidate == field

    fun isDirectionSelected(candidateField: String, candidateAscending: Boolean): Boolean =
        candidateField == field && candidateAscending == ascending

    fun directionStartIndex(candidateField: String): Int =
        if (candidateField == field && !ascending) 1 else 0
}
