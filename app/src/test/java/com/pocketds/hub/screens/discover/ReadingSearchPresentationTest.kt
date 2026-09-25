package com.pocketds.hub.screens.discover

import com.pocketds.hub.model.ReadingItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingSearchPresentationTest {
    private val close = listOf(ReadingItem(title = "Light Bringer"), ReadingItem(title = "Light Bringer: A Red Rising Novel"))
    private val broader = listOf(ReadingItem(title = "The Black Prism"), ReadingItem(title = "And They Found Dragons"))

    @Test fun closeResultsShowFirstAndBroaderSuggestionsNeedAnExplicitToggle() {
        val initial = ReadingSearchPresentation(close, broader)
        assertEquals(close, initial.visibleResults())
        assertTrue(initial.hasBroaderResults)
        assertEquals("2 close matches · 2 broader results", initial.summary())

        val expanded = initial.toggleBroader()
        assertEquals(close + broader, expanded.visibleResults())
        assertEquals(close, expanded.toggleBroader().visibleResults())
    }

    @Test fun noCloseMatchStillLetsUserInspectBroaderResults() {
        val initial = ReadingSearchPresentation.forResults(emptyList(), broader)
        assertEquals("2 broader results · no close matches", initial.summary())
        assertEquals(broader, initial.visibleResults())
        assertFalse(initial.canToggle)
        assertFalse(ReadingSearchPresentation().hasBroaderResults)
    }
}
