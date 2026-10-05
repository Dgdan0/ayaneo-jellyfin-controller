package com.pocketds.hub.screens.library

import com.pocketds.hub.model.ReadingSectionItem
import com.pocketds.hub.model.ReadingEdition
import com.pocketds.hub.model.ReadingWork

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
            // Kavita's series edition identifies the series, while the page
            // reader accepts chapter IDs. The hero Read action must use a
            // chapter from the volume list, just like tapping an issue row.
            val kavitaComic = work.kind in setOf("comic", "manga") &&
                playable.any { it.source == "kavita" }
            fun readableChapter(item: ReadingSectionItem): Boolean =
                item.kind in setOf("book", "ebook", "comic", "manga") &&
                    (item.isAvailable || (kavitaComic && item.sourceItemId.isNotBlank() &&
                        (item.availability.isBlank() || item.availability.equals("available", ignoreCase = true))))
            val continued = work.continueAt?.takeIf { point ->
                point.sourceItemId.isNotBlank() && point.kind in setOf("book", "ebook", "comic", "manga") && (
                    (!kavitaComic && playable.any { it.sourceItemId == point.sourceItemId }) ||
                        work.sections.any { section -> section.items.any {
                            it.sourceItemId == point.sourceItemId && readableChapter(it)
                        } }
                    )
            }
            val first = playable.firstOrNull()
            val section = work.sections.asSequence().flatMap { it.items.asSequence() }
                .firstOrNull(::readableChapter)
            val id = continued?.sourceItemId ?: if (kavitaComic) section?.sourceItemId
                else first?.sourceItemId ?: section?.sourceItemId ?: return null
            if (id.isNullOrBlank()) return null
            val source = playable.firstOrNull { it.sourceItemId == id }?.source?.takeIf { it.isNotBlank() }
                ?: continued?.source?.takeIf { it.isNotBlank() }
                ?: if (section?.sourceItemId == id) "kavita" else first?.source.orEmpty()
            val progress = work.progress
            // A comic run: the issue you are on, since one page of 4,437 is "0%".
            val issue = if (work.kind in setOf("comic", "manga")) work.sections.asSequence().flatMap { it.items.asSequence() }
                .firstOrNull { it.sourceItemId == id }?.let { ReadingBookFacts.issueTitle(it, work.kind) } else null
            val label = when {
                progress?.completed == true -> "Read again"
                issue != null && (progress?.percentage ?: 0.0) > 0 -> "Continue · $issue"
                issue != null -> "Start · $issue"
                progress != null && progress.percentage > 0 -> "Resume · ${com.pocketds.hub.state.Fmt.readingPercentLabel(progress.percentage)}"
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
