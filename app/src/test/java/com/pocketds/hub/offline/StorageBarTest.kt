package com.pocketds.hub.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The storage bar's four segments, its words, and when it shows (#48). */
class StorageBarTest {
    private val gb = 1_000_000_000L

    @Test fun `other apps, JellyHub, what is coming and what is free fill the whole line`() {
        // A 100 GB volume: 30 free, 20 of JellyHub's own files, so 50 are other apps; 5 coming.
        val model = StorageBar.of(100 * gb, 30 * gb, 20 * gb, 5 * gb)
        val s = model.segments
        assertEquals(0.5, s.other, 1e-9)
        assertEquals(0.2, s.app, 1e-9)
        assertEquals(0.05, s.coming, 1e-9)
        assertEquals(0.25, s.free, 1e-9)
        assertEquals(1.0, s.other + s.app + s.coming + s.free, 1e-9)
        assertFalse(model.overflow)
        assertEquals(25 * gb, model.freeAfter)
    }

    @Test fun `a preview is added to what is coming`() {
        val model = StorageBar.of(100 * gb, 30 * gb, 20 * gb, 5 * gb, adding = 10 * gb)
        assertEquals(0.15, model.segments.coming, 1e-9)
        assertEquals(0.15, model.segments.free, 1e-9)
        assertEquals(15 * gb, model.freeAfter)
    }

    @Test fun `what does not fit takes all that is free, and says so`() {
        val model = StorageBar.of(100 * gb, 10 * gb, 20 * gb, 25 * gb)
        assertTrue(model.overflow)
        assertEquals(0.1, model.segments.coming, 1e-9)
        assertEquals(0.0, model.segments.free, 1e-9)
        assertEquals(-15 * gb, model.freeAfter)
    }

    @Test fun `figures that cannot be true are brought inside the volume`() {
        // More of JellyHub's files than there is room taken, and free over the total.
        val model = StorageBar.of(100 * gb, 120 * gb, 50 * gb, 0)
        assertEquals(0.0, model.segments.app, 1e-9)
        assertEquals(1.0, model.segments.free, 1e-9)
        val unknown = StorageBar.of(0, 0, 0, 0)
        assertEquals(0.0, unknown.segments.free, 0.0)
    }

    @Test fun `the line says what is coming and what is here`() {
        assertEquals("2 coming · 1 on this Pocket", StorageBar.label(2, 1, "Pocket"))
        assertEquals("3 on this Pocket", StorageBar.label(0, 3, "Pocket"))
        assertEquals("2 coming", StorageBar.label(2, 0, "Pocket"))
        assertEquals("Nothing on this Pocket yet", StorageBar.label(0, 0, "Pocket"))
    }

    @Test fun `a preview says what it adds and what would be left`() {
        val adding = DownloadChoice(listOf("a", "b", "c"), 4_100_000_000L)
        assertEquals("Adds 3 · 3.8 GB · 12.0 GB free after", StorageBar.label(1, 2, "Pocket", adding, 12L shl 30))
        assertEquals("Adds 3 · 3.8 GB · not enough room", StorageBar.label(1, 2, "Pocket", adding, -1L))
        // Nothing to add: the plain line.
        assertEquals("1 coming · 2 on this Pocket", StorageBar.label(1, 2, "Pocket", DownloadChoice.NONE, 5L))
    }

    @Test fun `the bar rises with the first download and goes three seconds after the last`() {
        val visible = StorageBar.Visibility()
        assertFalse("nothing coming, nothing shown", visible.update(0, 0))
        assertTrue("a download starts", visible.update(1_000, 2))
        assertTrue("still two coming", visible.update(9_000, 2))
        assertTrue("the last finishes at 10 s: it stays", visible.update(10_000, 0))
        assertTrue(visible.update(12_900, 0))
        assertEquals(13_000L, visible.hidesAt())
        assertFalse("three seconds after the last, it fades", visible.update(13_000, 0))
        assertEquals(null, visible.hidesAt())
        assertTrue("and rises again with the next", visible.update(20_000, 1))
    }

    @Test fun `select mode and the choices panel keep the bar though nothing is coming`() {
        val visible = StorageBar.Visibility()
        assertTrue(visible.update(0, 0, forced = true))
        assertTrue(visible.update(2_000, 0, forced = true))
        assertTrue("leaving it, the bar waits as it does after a download", visible.update(3_000, 0))
        assertFalse(visible.update(5_000, 0))
    }
}
