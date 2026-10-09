package com.pocketds.hub.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The turns of a burst of quick swipes, counted by the reader (#64): ten swipes are ten pages, whatever the pager did in between. */
class TurnLedgerTest {
    private fun place(item: Int, pages: Int = 60) = PageTurns.Place(item, pages)

    @Test fun `the first flick counts from where the pager is`() {
        val ledger = TurnLedger()
        assertNull(ledger.expected(0))
        assertEquals(17, ledger.flick(place(16), +1, 1_000))
        assertEquals(15, TurnLedger().flick(place(16), -1, 1_000))
    }

    @Test fun `ten flicks in a row are ten pages, though the pager lost some`() {
        val ledger = TurnLedger()
        var now = 1_000L
        // The pager is where it was as each finger lands, never where the account says: it undid or ignored the last turn.
        var pager = 16
        val landed = (1..10).map {
            ledger.expected(now)?.let { pager = it }
            val target = ledger.flick(place(pager), +1, now)!!
            now += 120
            target
        }
        assertEquals((17..26).toList(), landed)
    }

    @Test fun `a mixed burst nets what was sent`() {
        val ledger = TurnLedger()
        var now = 0L
        var page = 30
        for (move in listOf(1, 1, -1, 1, 1, 1, -1, 1, -1, 1)) {
            page = ledger.flick(place(page), move, now)!!
            now += 80
        }
        assertEquals(34, page)
        assertEquals(34, ledger.expected(now))
    }

    @Test fun `the account is the burst's until no flick has come for a while, then the pager's own page is the start`() {
        val ledger = TurnLedger()
        assertEquals(11, ledger.flick(place(10), +1, 0))
        assertEquals(11, ledger.expected(599))
        assertNull(ledger.expected(600))
        // A new burst after the pause counts from where the pager is, even if that is not where the last one ended.
        assertEquals(41, ledger.flick(place(40), +1, 5_000))
    }

    @Test fun `a flick past either end of the file is the pager of files' and ends the burst`() {
        val ledger = TurnLedger()
        assertEquals(59, ledger.flick(place(58, pages = 60), +1, 0))
        assertNull(ledger.flick(place(59, pages = 60), +1, 100))
        assertNull(ledger.expected(150))
        assertNull(TurnLedger().flick(place(0), -1, 0))
        // The one that follows starts afresh from the pager.
        assertEquals(1, ledger.flick(place(2), -1, 200))
    }

    @Test fun `anything else that moves the page ends the account`() {
        val ledger = TurnLedger()
        ledger.flick(place(10), +1, 0)
        ledger.end()
        assertNull(ledger.expected(10))
        assertFalse(ledger.stillWanted(11, 10))
    }

    @Test fun `the page a flick went to is wanted until a later flick or a while has passed`() {
        val ledger = TurnLedger()
        val first = ledger.flick(place(10), +1, 0)!!
        assertTrue(ledger.stillWanted(first, 100))
        assertTrue(ledger.stillWanted(first, TurnLedger.SETTLE_MS - 1))
        assertFalse(ledger.stillWanted(first, TurnLedger.SETTLE_MS))
        // A later flick makes the earlier target stale.
        val second = ledger.flick(place(11), +1, 200)!!
        assertFalse(ledger.stillWanted(first, 250))
        assertTrue(ledger.stillWanted(second, 250))
    }
}
