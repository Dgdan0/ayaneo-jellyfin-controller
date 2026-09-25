package com.pocketds.hub.reader

import org.junit.Assert.*
import org.junit.Test

class ReadAlongSelectionTest {
    @Test fun selectionResumesOnlyNarrationItPaused() {
        val gate = NarrationSelectionGate()
        assertTrue(gate.begin(playing = true))
        assertFalse(gate.begin(playing = false))
        assertTrue(gate.dismiss())
        assertFalse(gate.dismiss())
        assertFalse(gate.begin(playing = false))
        assertFalse(gate.dismiss())
    }

    @Test fun explicitPlayAndLeavingReaderCancelAutomaticResume() {
        val gate = NarrationSelectionGate()
        gate.begin(playing = true)
        gate.playFromSelection()
        assertFalse(gate.dismiss())
        gate.begin(playing = true)
        gate.cancel()
        assertFalse(gate.dismiss())
    }

    @Test fun selectedAncestorFindsContainingNarratedSentence() {
        val timeline = ReadAlongTimeline(listOf(ReadAlongTrack("audio1", listOf(
            ReadAlongSegment("chapter.xhtml", "sentence-1", "audio1", 1000, 3000),
            ReadAlongSegment("chapter.xhtml", "sentence-2", "audio1", 3000, 6000)
        ))))
        assertEquals(ReadAlongPosition(0, 2000), ReadAlongSelectionTarget.find(timeline,
            "chapter.xhtml", listOf("word-span", "sentence-2", "chapter")))
        assertNull(ReadAlongSelectionTarget.find(timeline, "other.xhtml", listOf("sentence-2")))
        assertNull(ReadAlongSelectionTarget.find(timeline, "chapter.xhtml", listOf("unknown")))
    }

    @Test fun dictionaryCandidatesHandleCommonInflectionsAndPunctuation() {
        assertEquals(listOf("stories", "story"), DictionaryTerms.candidates("“Stories,”"))
        assertEquals(listOf("running", "runn", "run"), DictionaryTerms.candidates("running"))
        assertEquals(listOf("walked", "walk", "walke"), DictionaryTerms.candidates("walked"))
        assertEquals(emptyList<String>(), DictionaryTerms.candidates("!?"))
    }
}
