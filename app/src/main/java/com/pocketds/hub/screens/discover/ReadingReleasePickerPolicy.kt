package com.pocketds.hub.screens.discover

object ReadingReleasePickerPolicy {
    fun hasTargetChooser(targetCount: Int): Boolean = targetCount > 1
    fun hasSearchAction(targetCount: Int): Boolean = targetCount > 0

    fun summary(total: Int, outsideProfile: Int, wrongFormat: Int, indexerErrors: Int, showingSaved: Boolean): String = buildString {
        append(if (total == 0 && indexerErrors == 0) "No releases found · Search again later"
            else if (total == 0) "No releases yet" else "$total releases")
        if (outsideProfile > 0) append(" · $outsideProfile outside profile")
        if (wrongFormat > 0) append(" · $wrongFormat wrong format")
        if (indexerErrors > 0) append(" · $indexerErrors indexer errors")
        if (total == 0 && indexerErrors > 0) append(" · Try Search again")
        if (showingSaved) append(" · live search failed; showing saved results")
    }
}
