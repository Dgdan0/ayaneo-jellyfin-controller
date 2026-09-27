package com.pocketds.hub.input

/** One action per physical pull; selected screens require a deliberate 600 ms hold. */
internal class TriggerHoldGate(private val delayMs: Long = 600L) {
    private data class Press(val context: Any?, val started: Long, var done: Boolean)
    private val presses = mutableMapOf<Pair<Int, Direction>, Press>()

    fun down(device: Int, direction: Direction, now: Long, context: Any?): Boolean {
        val key = device to direction
        if (key in presses) return false
        presses[key] = Press(context, now, done = context == null)
        return context == null
    }

    fun up(device: Int, direction: Direction) { presses.remove(device to direction) }

    fun tick(now: Long, context: Any?): List<Direction> = buildList {
        presses.forEach { (key, press) ->
            if (!press.done) {
                if (press.context !== context) press.done = true
                else if (now - press.started >= delayMs) {
                    press.done = true
                    add(key.second)
                }
            }
        }
    }

    fun cancelPending() { presses.values.forEach { it.done = true } }
    fun pending(): Boolean = presses.values.any { !it.done }
    fun reset() { presses.clear() }
}
