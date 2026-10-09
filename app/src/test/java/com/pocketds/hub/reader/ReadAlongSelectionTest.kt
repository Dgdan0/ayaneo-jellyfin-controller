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

    @Test fun leavingTheReaderCancelsAutomaticResume() {
        val gate = NarrationSelectionGate()
        gate.begin(playing = true)
        gate.cancel()
        assertFalse(gate.dismiss())
    }

    @Test fun dictionaryCandidatesHandleCommonInflectionsAndPunctuation() {
        assertEquals(listOf("stories", "story"), DictionaryTerms.candidates("“Stories,”"))
        assertEquals(listOf("running", "runn", "run"), DictionaryTerms.candidates("running"))
        assertEquals(listOf("walked", "walk", "walke"), DictionaryTerms.candidates("walked"))
        assertEquals(emptyList<String>(), DictionaryTerms.candidates("!?"))
    }
}
