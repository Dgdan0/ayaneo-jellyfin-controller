package com.pocketds.hub.offline

/**
 * Select mode's ticks (#48): which episodes are ticked, and the words the page
 * says of them. Pure, with no views, so the rules (an episode already here or
 * coming cannot be ticked, ticks survive a change of season, "Select season"
 * ticks the rest and then unticks them) are tested once.
 */
class DownloadSelection {
    private val ticked = linkedSetOf<String>()

    val ids: List<String> get() = ticked.toList()
    val count: Int get() = ticked.size
    val isEmpty: Boolean get() = ticked.isEmpty()
    fun isTicked(id: String) = id in ticked

    /** An episode can be ticked when the server has it and the device neither has it nor is fetching it. */
    fun canTick(episode: SeriesEpisode, have: Set<String>) = episode.available && episode.id !in have

    /** Ticks or unticks one; false (and no change) when it cannot be ticked. */
    fun toggle(episode: SeriesEpisode, have: Set<String>): Boolean {
        if (!canTick(episode, have)) return false
        if (!ticked.add(episode.id)) ticked.remove(episode.id)
        return true
    }

    /**
     * Ⓨ or "Select season": every tickable episode of the season, or, when they all are ticked already, none of them.
     * Returns how many are ticked in it now.
     */
    fun toggleSeason(episodes: List<SeriesEpisode>, seasonId: String, have: Set<String>): Int {
        val tickable = tickableIn(episodes, seasonId, have)
        if (tickable.all { it.id in ticked }) tickable.forEach { ticked -= it.id } else tickable.forEach { ticked += it.id }
        return tickable.count { it.id in ticked }
    }

    /** The ticks of episodes that have since arrived or been queued by another way are dropped. */
    fun prune(have: Set<String>) { ticked.removeAll(have) }

    fun clear() = ticked.clear()

    /** What the ticked episodes weigh. */
    fun bytes(episodes: List<SeriesEpisode>): Long {
        val sizes = episodes.associate { it.id to it.sizeBytes }
        return ticked.sumOf { sizes[it] ?: 0L }
    }

    private fun tickableIn(episodes: List<SeriesEpisode>, seasonId: String, have: Set<String>) =
        episodes.filter { it.seasonId == seasonId && canTick(it, have) }

    /** The season pill's count, "4/14": ticked of those that can be. Null when none can be (nothing to count). */
    fun seasonLabel(episodes: List<SeriesEpisode>, seasonId: String, have: Set<String>): String? {
        val tickable = tickableIn(episodes, seasonId, have)
        if (tickable.isEmpty()) return null
        return "${tickable.count { it.id in ticked }}/${tickable.size}"
    }

    /** The top line, "3 selected". */
    fun line(): String = "${ticked.size} selected"

    /** The bottom bar's words: what the ticked episodes weigh. */
    fun sizeLine(episodes: List<SeriesEpisode>, format: (Long) -> String): String =
        if (ticked.isEmpty()) "Tick the episodes to download" else "${ticked.size} episode${if (ticked.size == 1) "" else "s"} · ${format(bytes(episodes))}"
}
