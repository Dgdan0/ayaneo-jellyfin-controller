package com.pocketds.hub.screens.library

import com.pocketds.hub.model.ReadingSectionItem
import com.pocketds.hub.model.ReadingWork

/** Pure detail-screen decisions kept independently testable from Android Views. */
data class ReadingWorkPresentation private constructor(
    val description: String,
    val descriptionExpanded: Boolean
) {
    fun toggleDescription(): ReadingWorkPresentation = copy(descriptionExpanded = !descriptionExpanded)

    companion object {
        fun initial(description: String) = ReadingWorkPresentation(description, descriptionExpanded = false)

        fun continueArtwork(work: ReadingWork): String {
            val point = work.continueAt ?: return ""
            if (point.artwork.isNotBlank()) return point.artwork
            return work.sections.asSequence().flatMap { it.items.asSequence() }
                .firstOrNull {
                    (point.workId.isNotBlank() && it.workId == point.workId) ||
                        (point.sourceItemId.isNotBlank() && it.sourceItemId == point.sourceItemId)
                }?.artwork.orEmpty().ifBlank { work.artwork }
        }

        fun canOpen(item: ReadingSectionItem): Boolean = item.isAvailable

        fun preferredActionSource(
            continueSourceItemId: String,
            readableSourceItemIds: List<String>,
            previouslyFocusedSourceItemId: String?
        ): String? {
            if (previouslyFocusedSourceItemId in readableSourceItemIds) {
                return previouslyFocusedSourceItemId
            }
            if (continueSourceItemId.isNotBlank() && continueSourceItemId in readableSourceItemIds) {
                return continueSourceItemId
            }
            return readableSourceItemIds.firstOrNull()
        }
    }
}
