package com.pocketds.hub.offline

import com.pocketds.hub.state.Fmt

/**
 * The storage bar's maths and words (#48), pure so the one shared view
 * ([com.pocketds.hub.ui.StorageBarView]) only draws what this says. One line,
 * split into other apps, JellyHub (what is on the device), what is coming or
 * being added (the theme's accent) and what is free, from the chosen offline
 * root's real capacity.
 */
object StorageBar {

    /** The four segments, as shares of the whole line: they sum to 1 (or are all 0 for an unknown disk). */
    data class Segments(val other: Double, val app: Double, val coming: Double, val free: Double)

    /** [overflow]: what is coming does not fit in what is free; [freeAfter] is then negative. */
    data class Model(val segments: Segments, val overflow: Boolean, val freeAfter: Long)

    /**
     * @param total the volume's capacity
     * @param free what is free now
     * @param onDevice bytes of JellyHub's own files, partial ones included
     * @param coming what the queue still has to fetch
     * @param adding what a choice about to be made would add (the preview), 0 when none
     */
    fun of(total: Long, free: Long, onDevice: Long, coming: Long, adding: Long = 0L): Model {
        if (total <= 0L) return Model(Segments(0.0, 0.0, 0.0, 0.0), false, 0L)
        val freeNow = free.coerceIn(0L, total)
        val app = onDevice.coerceIn(0L, total - freeNow)
        val other = (total - freeNow - app).coerceAtLeast(0L)
        val incoming = (coming + adding).coerceAtLeast(0L)
        val fits = incoming.coerceAtMost(freeNow)
        val freeAfter = freeNow - fits
        val share = { bytes: Long -> bytes.toDouble() / total }
        return Model(Segments(share(other), share(app), share(fits), share(freeAfter)), incoming > freeNow, freeNow - incoming)
    }

    /**
     * The line beside the bar: "2 coming · 1 on this Pocket". Nothing coming reads only what is here; a preview reads
     * what it would add and how much would be left.
     */
    fun label(coming: Int, onDevice: Int, device: String, adding: DownloadChoice? = null, freeAfter: Long = 0L): String {
        if (adding != null && !adding.isEmpty) {
            val left = if (freeAfter < 0) "not enough room" else "${Fmt.bytes(freeAfter)} free after"
            return "Adds ${adding.count} · ${Fmt.bytes(adding.bytes)} · $left"
        }
        val parts = buildList {
            if (coming > 0) add("$coming coming")
            if (onDevice > 0) add("$onDevice on this $device")
        }
        return parts.joinToString(" · ").ifEmpty { "Nothing on this $device yet" }
    }

    /** How long the bar stays after the last download finishes, before it fades. */
    const val LINGER_MS = 3_000L

    /**
     * When the bar shows (#48): it rises as a download starts or while a choice is being made, and goes about three
     * seconds after the last one finishes. Fed the time and whether anything is coming; says whether to show.
     */
    class Visibility(private val lingerMs: Long = LINGER_MS) {
        private var lastActive = Long.MIN_VALUE
        private var wasComing = false
        var shown = false
            private set

        /**
         * [forced]: select mode or the choices panel is open, which keep the bar. The moment the last download finishes
         * is the moment [coming] is seen to reach none, and the three seconds count from there.
         */
        fun update(nowMs: Long, coming: Int, forced: Boolean = false): Boolean {
            if (coming > 0 || forced || wasComing) lastActive = nowMs
            wasComing = coming > 0
            shown = lastActive != Long.MIN_VALUE && nowMs - lastActive < lingerMs
            return shown
        }

        /** The time at which [update] will say hidden if nothing else happens, or null when it is hidden already. */
        fun hidesAt(): Long? = if (shown) lastActive + lingerMs else null
    }
}
