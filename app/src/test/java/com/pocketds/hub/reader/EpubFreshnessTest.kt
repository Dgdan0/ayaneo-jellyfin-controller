package com.pocketds.hub.reader

import com.pocketds.hub.net.FailureKind
import com.pocketds.hub.reader.EpubFreshness.Action
import com.pocketds.hub.reader.EpubFreshness.Answer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpubFreshnessTest {
    private val kept = CopyState.Tagged("\"copy-a\"")

    private fun replied(status: Int, etag: String = "") = Answer.Replied(status, etag)

    @Test fun `a 304 opens the copy that is kept`() {
        assertEquals(Action.OPEN_CACHED, EpubFreshness.decide(kept, replied(304, "\"copy-a\"")))
        assertEquals(Action.OPEN_CACHED, EpubFreshness.decide(kept, replied(304)))
    }

    @Test fun `another tag is another edition`() {
        assertEquals(Action.REPLACE, EpubFreshness.decide(kept, replied(200, "\"copy-b\"")))
        // A resumed transfer of the newer edition answers 206.
        assertEquals(Action.REPLACE, EpubFreshness.decide(kept, replied(206, "\"copy-b\"")))
    }

    @Test fun `a body carrying the tag that is kept costs nothing`() {
        // A server that does not honour If-None-Match must not cost the whole book at every opening.
        assertEquals(Action.OPEN_CACHED, EpubFreshness.decide(kept, replied(200, "\"copy-a\"")))
    }

    @Test fun `a hub that tags nothing leaves the copy alone`() {
        // The pass-through of Storyteller's file, before the hub serves the reading copy or when it cannot.
        assertEquals(Action.OPEN_CACHED, EpubFreshness.decide(kept, replied(200)))
        assertEquals(Action.OPEN_CACHED, EpubFreshness.decide(CopyState.Unrecorded, replied(200)))
        assertEquals(Action.OPEN_CACHED, EpubFreshness.decide(CopyState.Unverifiable, replied(200)))
    }

    @Test fun `a copy with no tag kept is fetched once the hub tags its edition`() {
        assertEquals(Action.REPLACE, EpubFreshness.decide(CopyState.Unrecorded, replied(200, "\"copy-b\"")))
        assertEquals(Action.REPLACE, EpubFreshness.decide(CopyState.Unverifiable, replied(200, "\"copy-b\"")))
    }

    @Test fun `nothing kept is downloaded whatever the hub says`() {
        assertEquals(Action.DOWNLOAD, EpubFreshness.decide(CopyState.Missing, replied(200, "\"copy-b\"")))
        assertEquals(Action.DOWNLOAD, EpubFreshness.decide(CopyState.Missing, replied(304)))
        assertEquals(Action.DOWNLOAD, EpubFreshness.decide(CopyState.Missing, Answer.Failed(FailureKind.NO_NETWORK)))
    }

    @Test fun `a hub that cannot answer leaves every kind of copy to open`() {
        val copies = listOf(kept, CopyState.Unrecorded, CopyState.Unverifiable)
        for (copy in copies) for (kind in FailureKind.entries) {
            assertEquals("$copy after $kind", Action.OPEN_CACHED, EpubFreshness.decide(copy, Answer.Failed(kind)))
        }
    }

    @Test fun `a status that carries no edition leaves the copy alone`() {
        for (status in listOf(204, 301, 400, 401, 403, 404, 409, 429, 500, 502, 503, 504)) {
            assertEquals("status $status", Action.OPEN_CACHED, EpubFreshness.decide(kept, replied(status, "\"copy-b\"")))
        }
    }

    @Test fun `only a kept tag is sent as a condition`() {
        assertEquals("\"copy-a\"", EpubFreshness.condition(kept))
        assertNull(EpubFreshness.condition(CopyState.Unrecorded))
        assertNull(EpubFreshness.condition(CopyState.Unverifiable))
        assertNull(EpubFreshness.condition(CopyState.Missing))
    }

    @Test fun `only a whole strong tag counts`() {
        assertEquals("\"abc\"", EpubFreshness.strong("\"abc\""))
        assertEquals("\"abc\"", EpubFreshness.strong("  \"abc\"  "))
        assertEquals("", EpubFreshness.strong("W/\"abc\""))
        assertEquals("", EpubFreshness.strong("abc"))
        assertEquals("", EpubFreshness.strong("\"abc"))
        assertEquals("", EpubFreshness.strong("\""))
        assertEquals("", EpubFreshness.strong("\"a\nb\""))
        assertEquals("", EpubFreshness.strong(""))
        assertEquals("", EpubFreshness.strong(null))
    }
}
