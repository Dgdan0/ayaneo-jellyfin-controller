package com.pocketds.hub.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PadNamesTest {

    @Test
    fun `names the face buttons`() {
        assertEquals("BUTTON_A", PadNames.keyName(96))
        assertEquals("BUTTON_B", PadNames.keyName(97))
        assertEquals("BUTTON_X", PadNames.keyName(99))
        assertEquals("BUTTON_Y", PadNames.keyName(100))
    }

    @Test
    fun `an unmapped key code renders as its number`() {
        // A vendor emitting something we have never seen is the whole point of
        // the probe screen, so it has to be legible rather than "unknown".
        assertEquals("KEYCODE_9001", PadNames.keyName(9001))
    }

    @Test
    fun `a gamepad source decomposes into every class it carries`() {
        // Real controllers report several at once; a device that is gamepad but
        // NOT joystick is the signal that the sticks are not in stick mode.
        val source = PadNames.SOURCE_GAMEPAD or PadNames.SOURCE_JOYSTICK or PadNames.SOURCE_KEYBOARD
        assertEquals(listOf("keyboard", "gamepad", "joystick"), PadNames.sourceNames(source))
    }

    @Test
    fun `source membership is a mask test not equality`() {
        // SOURCE_JOYSTICK is 0x01000010: a naive == against a combined source
        // reports absent when it is plainly present.
        val combined = PadNames.SOURCE_GAMEPAD or PadNames.SOURCE_JOYSTICK
        assertTrue(PadNames.has(combined, PadNames.SOURCE_JOYSTICK))
        assertTrue(PadNames.has(combined, PadNames.SOURCE_GAMEPAD))
        assertFalse(PadNames.has(combined, PadNames.SOURCE_TOUCHSCREEN))
    }

    @Test
    fun `dpad and keyboard share low bits without being confused`() {
        // SOURCE_DPAD is 0x201 and SOURCE_KEYBOARD is 0x101; both end in 0x01,
        // which is the trap a sloppy mask test falls into.
        assertFalse(PadNames.has(PadNames.SOURCE_KEYBOARD, PadNames.SOURCE_DPAD))
        assertTrue(PadNames.has(PadNames.SOURCE_DPAD, PadNames.SOURCE_DPAD))
    }

    @Test
    fun `an unknown source still shows the raw value`() {
        assertEquals("0x00000000", PadNames.describeSource(0))
    }

    @Test
    fun `names the axes including both trigger spellings`() {
        assertEquals("X", PadNames.axisName(0))
        assertEquals("HAT_X", PadNames.axisName(15))
        assertEquals("LTRIGGER", PadNames.axisName(17))
        assertEquals("BRAKE", PadNames.axisName(23))
        assertEquals("GAS", PadNames.axisName(22))
        assertEquals("AXIS_99", PadNames.axisName(99))
    }

    @Test
    fun `watched axes cover both trigger spellings and the hat`() {
        val watched = PadNames.WATCHED_AXES.toSet()
        // Which spelling this device uses is unknown, so all of them are watched.
        assertTrue(watched.containsAll(listOf(
            PadNames.AXIS_LTRIGGER, PadNames.AXIS_RTRIGGER,
            PadNames.AXIS_BRAKE, PadNames.AXIS_GAS,
            PadNames.AXIS_HAT_X, PadNames.AXIS_HAT_Y
        )))
    }

    @Test
    fun `watched axes have no duplicates`() {
        assertEquals(PadNames.WATCHED_AXES.size, PadNames.WATCHED_AXES.toSet().size)
    }
}
