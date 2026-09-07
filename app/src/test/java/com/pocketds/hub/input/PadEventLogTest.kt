package com.pocketds.hub.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PadEventLogTest {

    private fun rec(keyCode: Int, down: Boolean = true, repeat: Int = 0, at: Long = 0L) =
        KeyRecord(
            down = down, keyCode = keyCode, scanCode = 0,
            source = PadNames.SOURCE_GAMEPAD, deviceId = 1,
            repeatCount = repeat, atMs = at
        )

    @Test
    fun `keeps records newest last`() {
        val log = PadEventLog()
        log.add(rec(96))
        log.add(rec(97))
        assertEquals(listOf(96, 97), log.snapshot().map { it.keyCode })
    }

    @Test
    fun `evicts the oldest past capacity`() {
        val log = PadEventLog(capacity = 3)
        (1..5).forEach { log.add(rec(it)) }
        assertEquals(3, log.size)
        assertEquals(listOf(3, 4, 5), log.snapshot().map { it.keyCode })
    }

    @Test
    fun `recent returns newest first for rendering`() {
        val log = PadEventLog()
        log.add(rec(96))
        log.add(rec(97))
        log.add(rec(99))
        assertEquals(listOf(99, 97), log.recent(2).map { it.keyCode })
    }

    @Test
    fun `recent asking for more than exists returns what there is`() {
        val log = PadEventLog()
        log.add(rec(96))
        assertEquals(1, log.recent(50).size)
    }

    @Test
    fun `clear empties it`() {
        val log = PadEventLog()
        log.add(rec(96))
        log.clear()
        assertEquals(0, log.size)
        assertTrue(log.snapshot().isEmpty())
    }

    @Test
    fun `a down event reads with its name and codes`() {
        val line = PadFormat.line(rec(96))
        assertTrue(line, line.startsWith("DOWN BUTTON_A"))
        assertTrue(line, line.contains("code=96"))
        assertTrue(line, line.contains("dev=1"))
        assertTrue(line, line.contains("gamepad"))
    }

    @Test
    fun `an auto-repeat is marked so held-key behaviour is visible`() {
        // Whether the platform is already repeating for us decides whether the
        // D-pad needs its own repeat logic at all.
        assertTrue(PadFormat.line(rec(19, repeat = 3)).contains("x3"))
        // Not contains("x0"): that also matches the 0x00000401 source hex.
        assertFalse(PadFormat.line(rec(19, repeat = 0)).contains("DPAD_UP x"))
    }

    @Test
    fun `up and down line up in the same columns`() {
        // The scrollback is read at a glance while events stream past, so DOWN
        // and UP are padded to the same width.
        val down = PadFormat.line(rec(96, down = true))
        val up = PadFormat.line(rec(96, down = false))
        assertEquals(down.indexOf("BUTTON_A"), up.indexOf("BUTTON_A"))
    }

    @Test
    fun `axis values are signed and fixed width so a column does not jitter`() {
        assertEquals("+0.500", PadFormat.axisValue(0.5f))
        assertEquals("-0.500", PadFormat.axisValue(-0.5f))
        assertEquals("+0.000", PadFormat.axisValue(0f))
        assertEquals(
            PadFormat.axisValue(0.5f).length,
            PadFormat.axisValue(-0.25f).length
        )
    }
}
