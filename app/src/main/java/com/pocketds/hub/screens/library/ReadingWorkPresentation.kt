package com.pocketds.hub.screens.library

import com.pocketds.hub.model.ReadingSectionItem
import com.pocketds.hub.model.ReadingEdition
import com.pocketds.hub.model.ReadingWork
import kotlin.math.roundToInt

/** Pure detail-screen decisions kept independently testable from Android Views. */
data class ReadingWorkPresentation private constructor(
    val description: String,
    val descriptionExpanded: Boolean
) {
    data class PrimaryRead(val sourceItemId: String, val source: String, val label: String)
    fun toggleDescription(): ReadingWorkPresentation = copy(descriptionExpanded = !descriptionExpanded)

    companion object {
        fun audiobooks(work: ReadingWork): List<ReadingEdition> = work.editions.filter {
            it.sourceItemId.isNotBlank() && it.kind == "audiobook" && it.availability == "available"
        }.distinctBy { it.source to it.sourceItemId }

        fun primaryListen(work: ReadingWork): ReadingEdition? =
            if (work.entityType == "collection") null else audiobooks(work).firstOrNull()

        fun readAlongEditions(work: ReadingWork): List<ReadingEdition> =
            if (work.entityType == "collection") emptyList() else work.editions.filter {
                it.sourceItemId.isNotBlank() && it.kind == "readaloud" && it.availability == "available" &&
                    audiobooks(work).any { audio -> audio.source == it.source && audio.sourceItemId == it.sourceItemId }
            }.distinctBy { it.source to it.sourceItemId }

        fun readAlongEdition(work: ReadingWork): ReadingEdition? = readAlongEditions(work).firstOrNull()

        fun primaryRead(work: ReadingWork): PrimaryRead? {
            if (work.entityType == "collection") return null
            val playable = work.editions.filter {
                it.sourceItemId.isNotBlank() && it.kind in setOf("book", "ebook", "comic", "manga") &&
                    it.availability == "available"
            }
            val continued = work.continueAt?.takeIf { point ->
                point.sourceItemId.isNotBlank() && point.kind in setOf("book", "ebook", "comic", "manga") && (
                    playable.any { it.sourceItemId == point.sourceItemId } ||
                        work.sections.any { section -> section.items.any {
                            it.sourceItemId == point.sourceItemId && it.isAvailable &&
                                it.kind in setOf("book", "ebook", "comic", "manga")
                        } }
                    )
            }
            val first = playable.firstOrNull()
            val section = work.sections.asSequence().flatMap { it.items.asSequence() }
                .firstOrNull { it.isAvailable && it.kind in setOf("book", "ebook", "comic", "manga") }
            val id = continued?.sourceItemId ?: first?.sourceItemId ?: section?.sourceItemId ?: return null
            val source = playable.firstOrNull { it.sourceItemId == id }?.source?.takeIf { it.isNotBlank() }
                ?: continued?.source?.takeIf { it.isNotBlank() }
                ?: if (section?.sourceItemId == id) "kavita" else first?.source.orEmpty()
            val progress = work.progress
            val label = when {
                progress?.completed == true -> "Read again"
                progress != null && progress.percentage > 0 -> "Resume · ${(progress.percentage * 100).roundToInt()}%"
                else -> "Read book"
            }
            return PrimaryRead(id, source.ifBlank { "kavita" }, label)
        }

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
