package com.pocketds.hub.input

/**
 * One key event, reduced to plain values at the View boundary.
 *
 * [scanCode] and [deviceId] are carried because they are how you tell two
 * physically different buttons apart when the firmware maps them to the same
 * key code, and how you tell the D-pad's two possible sources apart.
 */
data class KeyRecord(
    val down: Boolean,
    val keyCode: Int,
    val scanCode: Int,
    val source: Int,
    val deviceId: Int,
    val repeatCount: Int,
    val atMs: Long
)

/**
 * A bounded scrollback of what the pad has sent, newest last.
 *
 * Separate from DebugLog because this one is read while it is being written to,
 * at a glance, on a screen held at arm's length -- so it keeps structured
 * records rather than pre-formatted strings, and the screen decides how much of
 * each to show.
 */
class PadEventLog(private val capacity: Int = 200) {

    private val entries = ArrayDeque<KeyRecord>()

    fun add(record: KeyRecord) {
        if (entries.size >= capacity) entries.removeFirst()
        entries.addLast(record)
    }

    /** Newest last. */
    fun snapshot(): List<KeyRecord> = entries.toList()

    /** Newest first, at most [count]. What the screen renders. */
    fun recent(count: Int): List<KeyRecord> =
        entries.toList().asReversed().take(count)

    val size: Int get() = entries.size

    fun clear() = entries.clear()
}

/**
 * How a record reads on screen. Pure so the exact wording is pinned by a test
 * rather than discovered on the device.
 */
object PadFormat {

    fun line(record: KeyRecord): String {
        val action = if (record.down) "DOWN" else "UP  "
        val repeat = if (record.repeatCount > 0) " x${record.repeatCount}" else ""
        return "$action ${PadNames.keyName(record.keyCode)}$repeat" +
            "  code=${record.keyCode} scan=${record.scanCode} dev=${record.deviceId}" +
            "  ${PadNames.describeSource(record.source)}"
    }

    /** Signed, fixed width, so a column of these does not jitter as values change. */
    fun axisValue(value: Float): String = "%+.3f".format(value)

    fun axisLine(reading: AxisReading): String =
        "%-9s %s   min %s  max %s  |max| %s  n=%d".format(
            reading.name,
            axisValue(reading.current),
            axisValue(reading.min),
            axisValue(reading.max),
            axisValue(reading.maxAbs),
            reading.samples
        )
}
