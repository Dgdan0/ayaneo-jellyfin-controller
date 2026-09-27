package com.pocketds.hub.screens.library

import com.pocketds.hub.model.ReadingWork

/** The picker changes the proposed launch target; only opening it persists a preference. */
data class ReadingFormatMenu(
    val defaultChoice: ReadingEntryChoice?,
    val options: List<Option>,
    val availability: String
) {
    data class Option(val choice: ReadingEntryChoice, val label: String, val detail: String, val narration: String = "") {
        val key: String get() = "${choice.mode}:${choice.aligned?.sourceItemId ?: choice.audio?.sourceItemId ?: choice.text?.sourceItemId}"
    }

    companion object {
        fun forWork(work: ReadingWork, remembered: ReadingEntryPreference? = null): ReadingFormatMenu {
            val default = ReadingEntryChoice.choose(work, remembered)
            val audio = ReadingWorkPresentation.audiobooks(work)
            val aligned = ReadingWorkPresentation.readAlongEditions(work)
            val readable = work.editions.filter {
                it.sourceItemId.isNotBlank() && it.kind in setOf("book", "ebook", "comic", "manga") && it.availability == "available"
            }.filterNot { it.source == "kavita" && it.kind in setOf("comic", "manga") }
                .distinctBy { it.source to it.sourceItemId }
            val primaryText = ReadingWorkPresentation.primaryRead(work)
            val texts = readable.map { edition ->
                ReadingWorkPresentation.PrimaryRead(edition.sourceItemId, edition.source, primaryText?.label ?: "Read book")
            }.ifEmpty { listOfNotNull(primaryText) }
            val options = buildList {
                texts.forEachIndexed { index, text ->
                    val edition = readable.getOrNull(index)
                    val noun = when (edition?.kind ?: work.kind) {
                        "comic" -> "comic"
                        "manga" -> "manga"
                        else -> "ebook"
                    }
                    add(Option(ReadingEntryChoice(ReadingEntryMode.READ, text, default?.audio, null),
                        "Read $noun", edition?.format?.uppercase().orEmpty().ifBlank { text.source.replaceFirstChar(Char::uppercase) }))
                }
                audio.forEachIndexed { index, edition ->
                    val narration = edition.narrator.ifBlank { "Narration ${index + 1}" }
                    add(Option(ReadingEntryChoice(ReadingEntryMode.LISTEN, primaryText, edition, null),
                        "Listen to audiobook", narration, narration))
                }
                if (primaryText != null) aligned.forEachIndexed { index, edition ->
                    val narration = audio.firstOrNull { it.source == edition.source && it.sourceItemId == edition.sourceItemId }
                        ?.narrator?.ifBlank { "Narration ${index + 1}" } ?: "Narration ${index + 1}"
                    add(Option(ReadingEntryChoice(ReadingEntryMode.READ_ALONG, primaryText,
                        audio.firstOrNull { it.source == edition.source && it.sourceItemId == edition.sourceItemId }, edition),
                        "Read along", "$narration · Synchronized", narration))
                }
            }
            val aligning = work.editions.any { it.kind == "readaloud" && it.availability !in setOf("available", "missing") }
            val availability = buildList {
                if (texts.isNotEmpty()) add(when (readable.firstOrNull()?.kind ?: work.kind) {
                    "comic" -> "Comic ready"
                    "manga" -> "Manga ready"
                    else -> "Ebook ready"
                })
                if (audio.isNotEmpty()) add("Audiobook ready")
                if (aligned.isNotEmpty()) add("Read along ready")
                else if (aligning) add("Read along aligning")
            }.joinToString("  ·  ")
            return ReadingFormatMenu(default, options, availability)
        }
    }
}
