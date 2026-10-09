package com.pocketds.hub.reader

import org.junit.Assert.*
import org.junit.Test

class CardPlacementTest {
    @Test fun theCardGoesBelowTheWordsWhenThereIsRoomAndAboveWhenThereIsNot() {
        val bounds = CardPlacement.Box(0f, 56f, 853f, 400f)
        val word = CardPlacement.Box(300f, 100f, 360f, 120f)
        val below = CardPlacement.place(word, 300f, 150f, bounds, gap = 10f)
        assertFalse(below.above)
        assertEquals(130f, below.y, 0.01f)
        assertEquals(180f, below.x, 0.01f)
        val low = CardPlacement.Box(300f, 330f, 360f, 350f)
        val above = CardPlacement.place(low, 300f, 150f, bounds, gap = 10f)
        assertTrue(above.above)
        assertEquals(170f, above.y, 0.01f)
        assertFalse(above.covers)
    }

    @Test fun theCardStaysInsideThePageAtEitherEdge() {
        val bounds = CardPlacement.Box(12f, 56f, 841f, 400f)
        assertEquals(12f, CardPlacement.place(CardPlacement.Box(0f, 100f, 40f, 120f), 300f, 100f, bounds, 10f).x, 0.01f)
        assertEquals(541f, CardPlacement.place(CardPlacement.Box(800f, 100f, 840f, 120f), 300f, 100f, bounds, 10f).x, 0.01f)
    }

    @Test fun aCardWithNoRoomAboveOrBelowGoesBesideANarrowSelection() {
        val bounds = CardPlacement.Box(12f, 12f, 1893f, 945f)
        val word = CardPlacement.Box(178f, 443f, 333f, 512f)
        val place = CardPlacement.place(word, 1053f, 600f, bounds, gap = 22f)
        assertTrue(place.beside)
        assertFalse(place.covers)
        assertEquals(355f, place.x, 0.01f)
        assertEquals(177.5f, place.y, 0.01f)
        assertTrue("never on the words", place.x >= word.right + 22f - 0.01f)
        // On the right edge it goes to the left of the words instead.
        val right = CardPlacement.Box(1500f, 443f, 1700f, 512f)
        val left = CardPlacement.place(right, 1053f, 600f, bounds, gap = 22f)
        assertTrue(left.beside)
        assertEquals(1500f - 22f - 1053f, left.x, 0.01f)
    }

    @Test fun aCardTooTallForEitherSideTakesTheLargerSideAndSaysItCovers() {
        val bounds = CardPlacement.Box(0f, 56f, 853f, 400f)
        val middle = CardPlacement.Box(300f, 200f, 360f, 230f)
        val place = CardPlacement.place(middle, 300f, 400f, bounds, gap = 10f)
        assertTrue(place.covers)
    }
}
