package com.pocketds.hub.screens.library

import com.pocketds.hub.model.ReadingWork

enum class FormatReadiness(val description: String) { READY("available"), MISSING("not available"), PENDING("in progress"), UNKNOWN("availability unknown") }
data class ReadingFormatStatus(val kind: String, val label: String, val readiness: FormatReadiness) {
    companion object {
        private val formats = listOf("ebook" to "Ebook", "audiobook" to "Audiobook", "readaloud" to "Read along")
        fun unknown() = formats.map { ReadingFormatStatus(it.first,it.second,FormatReadiness.UNKNOWN) }
        fun forWork(work: ReadingWork): List<ReadingFormatStatus> {
            if (work.entityType == "collection") return emptyList()
            return formats.map { (kind,label) ->
                val editions = work.editions.filter { if (kind == "ebook") it.kind in listOf("ebook","book") else it.kind == kind }
                val ready = when(kind) {
                    "ebook" -> editions.any { it.availability == "available" && it.sourceItemId.isNotBlank() }
                    "audiobook" -> ReadingWorkPresentation.audiobooks(work).isNotEmpty()
                    else -> ReadingWorkPresentation.primaryRead(work) != null && ReadingWorkPresentation.readAlongEditions(work).isNotEmpty()
                }
                val state = when {
                    ready -> FormatReadiness.READY
                    editions.any { it.availability in setOf("queued","processing","aligning","downloading","downloaded","tracked") } -> FormatReadiness.PENDING
                    work.partial.isNotEmpty() || editions.any { it.availability !in setOf("missing","failed") } -> FormatReadiness.UNKNOWN
                    else -> FormatReadiness.MISSING
                }
                ReadingFormatStatus(kind,label,state)
            }
        }
    }
}
