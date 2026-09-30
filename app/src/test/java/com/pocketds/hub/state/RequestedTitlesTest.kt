package com.pocketds.hub.state

import com.pocketds.hub.model.MediaRef
import com.pocketds.hub.model.SearchHit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestedTitlesTest {

    @After
    fun reset() = RequestedTitles.clear()

    private fun hit(key: String, availability: String = "not_in_library") = SearchHit(
        media = MediaRef(key = key, title = key),
        availability = availability,
        actions = listOf("request", "detail")
    )

    @Test
    fun `a requested title stops offering Request on every card`() {
        RequestedTitles.record("movie:1", "processing", 42)
        val shown = RequestedTitles.apply(hit("movie:1"))
        assertEquals("processing", shown.availability)
        assertEquals(42, shown.requestId)
        assertFalse(shown.canRequest)
    }

    @Test
    fun `other titles are untouched`() {
        RequestedTitles.record("movie:1", "requested", 1)
        assertEquals(hit("movie:2"), RequestedTitles.apply(hit("movie:2")))
    }

    @Test
    fun `the hub wins once it reports progress`() {
        // Later the title is in the library; the session record must not pin it.
        RequestedTitles.record("movie:1", "requested", 1)
        assertEquals(hit("movie:1", "available"), RequestedTitles.apply(hit("movie:1", "available")))
    }

    @Test
    fun `an empty answer still reads as requested`() {
        RequestedTitles.record("tv:5", "", 3)
        assertEquals("requested", RequestedTitles.apply(hit("tv:5")).availability)
    }

    @Test
    fun `recording bumps the revision`() {
        val before = RequestedTitles.revision
        RequestedTitles.record("movie:1", "requested", 1)
        assertTrue(RequestedTitles.revision > before)
    }
}
