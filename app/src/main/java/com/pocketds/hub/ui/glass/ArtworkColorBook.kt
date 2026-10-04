package com.pocketds.hub.ui.glass

/**
 * What the app knows about each artwork's colours, and what to ask the hub
 * next. Pure, so the asking rules are pinned by JVM tests; [ArtworkColors] is
 * the Android side that sends the requests and keeps the file.
 *
 * Keyed by the hub image path exactly as a screen shows it. The hub already
 * treats one picture at two widths as one answer, so asking twice costs it
 * nothing.
 */
class ArtworkColorBook(
    private val capacity: Int = 2000,
    /** When to ask again about artwork the hub reported pending, or a request that failed. */
    private val retryAfterMs: LongArray = longArrayOf(3_000, 10_000, 30_000)
) {
    private class Retry(var attempts: Int, var dueAt: Long)

    /** Access order, so the least recently used colours are the ones dropped. */
    private val known = LinkedHashMap<String, ArtworkPalette>(64, 0.75f, true)
    private val missing = HashSet<String>()
    private val waiting = HashMap<String, Retry>()
    private val asking = HashSet<String>()

    fun palette(src: String): ArtworkPalette? = known[src]

    /** The hub cannot read it, or asking stopped being worth it this session. */
    fun isMissing(src: String): Boolean = src in missing

    /**
     * Which of [sources] to ask for now, at most [limit]: not known, not
     * missing, not already being asked, and past any retry wait. They count as
     * being asked from here until [answered] or [failed].
     */
    fun toAsk(sources: Collection<String>, nowMs: Long, limit: Int = MAX_PER_REQUEST): List<String> {
        val out = ArrayList<String>()
        for (src in sources) {
            if (out.size >= limit) break
            if (src.isBlank() || src in known || src in missing || src in asking || src in out) continue
            val retry = waiting[src]
            if (retry != null && retry.dueAt > nowMs) continue
            out += src
        }
        asking += out
        return out
    }

    /**
     * Records the hub's answer to [asked]. Returns the sources that now have
     * colours. Anything asked but in neither list is treated as pending.
     */
    fun answered(
        asked: List<String>,
        colors: Map<String, ArtworkPalette>,
        missingNow: Collection<String>,
        nowMs: Long
    ): List<String> {
        val got = ArrayList<String>()
        for (src in asked) {
            asking -= src
            val palette = colors[src]
            when {
                palette != null -> {
                    remember(src, palette)
                    waiting -= src
                    got += src
                }
                src in missingNow -> {
                    missing += src
                    waiting -= src
                }
                else -> later(src, nowMs)
            }
        }
        return got
    }

    /** The request itself failed: everything in it waits and is asked again. */
    fun failed(asked: List<String>, nowMs: Long) {
        for (src in asked) {
            asking -= src
            later(src, nowMs)
        }
    }

    /** Sources whose retry wait is over and are not yet being asked. */
    fun due(nowMs: Long): List<String> =
        waiting.filter { (src, retry) -> retry.dueAt <= nowMs && src !in asking }.keys.toList()

    /** When the next waiting source falls due, or null when nothing waits. */
    fun nextDueAt(): Long? = waiting.values.minOfOrNull { it.dueAt }

    /** Least recently used first, for the device's file. Iterating does not reorder. */
    fun snapshot(): List<Pair<String, ArtworkPalette>> = known.entries.map { it.key to it.value }

    /** Colours kept from an earlier run; anything learnt since wins. */
    fun restore(entries: List<Pair<String, ArtworkPalette>>) {
        for ((src, palette) in entries) {
            if (src.isNotBlank() && src !in known) remember(src, palette)
        }
    }

    private fun remember(src: String, palette: ArtworkPalette) {
        known[src] = palette
        missing -= src
        while (known.size > capacity) {
            val eldest = known.keys.first()
            known.remove(eldest)
        }
    }

    private fun later(src: String, nowMs: Long) {
        val retry = waiting.getOrPut(src) { Retry(0, nowMs) }
        if (retry.attempts >= retryAfterMs.size) {
            // Pending through every wait: stop asking this session, as for missing.
            waiting -= src
            missing += src
            return
        }
        retry.dueAt = nowMs + retryAfterMs[retry.attempts]
        retry.attempts++
    }

    companion object {
        /** The hub's limit per request. */
        const val MAX_PER_REQUEST = 60
    }
}
