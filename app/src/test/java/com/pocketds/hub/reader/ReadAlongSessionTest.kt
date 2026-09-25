package com.pocketds.hub.reader

import org.junit.Assert.*
import org.junit.Test

class ReadAlongSessionTest {
    @Test fun reopeningKeepsAudioCheckpointAcrossInitialPageCallbacks() {
        val state = ReadAlongSession()
        state.beginOpen()
        assertFalse(state.canSavePage())
        state.ready(ReadAlongPosition(0, 10_000))
        assertTrue(state.canSavePage())
        assertEquals(ReadAlongPosition(0, 10_000), state.pointForSave(null))
        state.record(ReadAlongPosition(0, 20_000))
        assertEquals(ReadAlongPosition(0, 20_000), state.pointForSave(null))
        state.switchToText()
        assertNull(state.pointForSave(null))
    }

    @Test fun newOpenWithoutNarrationResumeClearsPreviousPoint() {
        val state = ReadAlongSession()
        state.ready(ReadAlongPosition(3, 42_000))
        state.beginOpen()
        assertFalse(state.canSavePage())
        state.ready(null)
        assertNull(state.pointForSave(null))
        assertTrue(state.canSavePage())
    }

    @Test fun disposingPlayerCannotReplaceAudioCheckpointWithTextOnlyPage() {
        val state = ReadAlongSession()
        state.ready(null)
        state.record(ReadAlongPosition(0, 10_000))
        assertTrue(state.canSavePage(narrationAvailable = true))
        assertFalse(state.canSavePage(narrationAvailable = false))
        assertEquals(ReadAlongPosition(0, 10_000), state.pointForSave(null))
        state.switchToText()
        assertTrue(state.canSavePage(narrationAvailable = false))
    }
}
