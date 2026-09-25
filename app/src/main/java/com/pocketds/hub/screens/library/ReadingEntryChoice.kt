package com.pocketds.hub.screens.library

import com.pocketds.hub.model.ReadingEdition
import com.pocketds.hub.model.ReadingWork

enum class ReadingEntryMode { READ, LISTEN, READ_ALONG }

data class ReadingEntryPreference(val mode: ReadingEntryMode, val audioSourceItemId: String = "")

data class ReadingEntryChoice(
    val mode: ReadingEntryMode,
    val text: ReadingWorkPresentation.PrimaryRead?,
    val audio: ReadingEdition?,
    val aligned: ReadingEdition?
) {
    companion object {
        fun availableModes(work: ReadingWork): Set<ReadingEntryMode> = buildSet {
            if (readTarget(work) != null) add(ReadingEntryMode.READ)
            if (ReadingWorkPresentation.primaryListen(work) != null) add(ReadingEntryMode.LISTEN)
            if (readTarget(work) != null && ReadingWorkPresentation.readAlongEditions(work).isNotEmpty()) {
                add(ReadingEntryMode.READ_ALONG)
            }
        }

        fun choose(work: ReadingWork, preference: ReadingEntryPreference?): ReadingEntryChoice? {
            val text = readTarget(work)
            val audio = ReadingWorkPresentation.audiobooks(work)
            val aligned = if (text == null) emptyList() else ReadingWorkPresentation.readAlongEditions(work)
            val preferredAudio = audio.firstOrNull { it.sourceItemId == preference?.audioSourceItemId }
                ?: audio.firstOrNull()
            val preferredAligned = when {
                preference == null || preference.audioSourceItemId.isBlank() -> aligned.firstOrNull()
                else -> aligned.firstOrNull { it.sourceItemId == preference.audioSourceItemId }
            }
            val preferredMode = preference?.mode ?: if (text != null) ReadingEntryMode.READ else ReadingEntryMode.LISTEN
            val mode = when (preferredMode) {
                ReadingEntryMode.READ -> if (text != null) ReadingEntryMode.READ else if (preferredAudio != null) ReadingEntryMode.LISTEN else null
                ReadingEntryMode.LISTEN -> if (preferredAudio != null) ReadingEntryMode.LISTEN else if (text != null) ReadingEntryMode.READ else null
                ReadingEntryMode.READ_ALONG -> if (preferredAligned != null) ReadingEntryMode.READ_ALONG
                    else if (text != null) ReadingEntryMode.READ else if (preferredAudio != null) ReadingEntryMode.LISTEN else null
            } ?: return null
            return ReadingEntryChoice(mode, text, preferredAudio, if (mode == ReadingEntryMode.READ_ALONG) preferredAligned else null)
        }

        private fun readTarget(work: ReadingWork): ReadingWorkPresentation.PrimaryRead? =
            ReadingWorkPresentation.primaryRead(work)
    }
}
