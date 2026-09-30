package com.pocketds.hub.state

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class FmtTest {

    @Test
    fun `bytes use binary units`() {
        assertEquals("0 B", Fmt.bytes(0))
        assertEquals("512 B", Fmt.bytes(512))
        assertEquals("1.0 KB", Fmt.bytes(1024))
        assertEquals("1.0 MB", Fmt.bytes(1024L * 1024))
        assertEquals("1.5 GB", Fmt.bytes(1024L * 1024 * 1536))
    }

    @Test
    fun `a negative size does not produce nonsense`() {
        assertEquals("0 B", Fmt.bytes(-1))
    }

    @Test
    fun `large values drop the decimal rather than reading as noise`() {
        // "1023.7 MB" is harder to read at a glance than "1023 MB".
        assertEquals("1023 MB", Fmt.bytes(1024L * 1024 * 1023))
    }

    @Test
    fun `sizes never pick up the device locale's decimal comma`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("1.5 GB", Fmt.bytes(1024L * 1024 * 1536))
            assertEquals("12.3 Mbps", Fmt.mbps(12_345_678))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `zero speed is a dash, not zero`() {
        // "0 B/s" next to a queued torrent reads as broken; a dash reads as idle.
        assertEquals("—", Fmt.speed(0))
        assertEquals("—", Fmt.speed(-5))
        assertEquals("4.0 MB/s", Fmt.speed(4L * 1024 * 1024))
    }

    @Test
    fun `an unknown eta is a dash`() {
        // The hub sends -1 when qBittorrent had nothing to estimate from.
        assertEquals("—", Fmt.eta(-1))
    }

    @Test
    fun `eta reads in the largest sensible unit`() {
        assertEquals("45s", Fmt.eta(45))
        assertEquals("8m", Fmt.eta(8 * 60))
        assertEquals("2h 30m", Fmt.eta(150 * 60))
        assertEquals("3d 2h", Fmt.eta((3 * 24 + 2) * 3600L))
    }

    @Test
    fun `eta of zero is zero seconds, not a dash`() {
        assertEquals("0s", Fmt.eta(0))
    }

    @Test
    fun `percent clamps nothing but refuses to invent a number`() {
        assertEquals("0%", Fmt.percent(0.0))
        assertEquals("63%", Fmt.percent(0.6312))
        assertEquals("100%", Fmt.percent(1.0))
        assertEquals("—", Fmt.percent(-1.0))
    }

    @Test
    fun `clock shows minutes and seconds under an hour`() {
        assertEquals("0:00", Fmt.clock(0))
        assertEquals("0:09", Fmt.clock(9_999))
        assertEquals("17:12", Fmt.clock((17 * 60 + 12) * 1_000L))
    }

    @Test
    fun `clock carries hours instead of letting minutes run past sixty`() {
        // The offline copies printed 75:30 for this; online showed 1:15:30.
        assertEquals("1:15:30", Fmt.clock((75 * 60 + 30) * 1_000L))
        assertEquals("10:00:00", Fmt.clock(10 * 3_600_000L))
    }

    @Test
    fun `a negative position is the start, not a negative time`() {
        assertEquals("0:00", Fmt.clock(-5_000))
    }

    @Test
    fun `runtime reads in minutes until it needs hours`() {
        assertEquals("24 min", Fmt.runtime(24 * 60))
        assertEquals("59 min", Fmt.runtime(59 * 60 + 59))
        assertEquals("1h 0m", Fmt.runtime(3_600))
        assertEquals("2h 5m", Fmt.runtime(2 * 3_600 + 5 * 60))
    }

    @Test
    fun `an unknown runtime is empty so callers can drop the field`() {
        assertEquals("", Fmt.runtime(0))
        assertEquals("", Fmt.runtime(-1))
    }

    @Test
    fun `bitrate is megabits with one decimal`() {
        assertEquals("40.0 Mbps", Fmt.mbps(40_000_000))
        assertEquals("2.5 Mbps", Fmt.mbps(2_500_000))
        assertEquals("", Fmt.mbps(0))
    }
}
