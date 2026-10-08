package com.pocketds.hub.offline

import com.pocketds.hub.model.OfflineSelectionResponse

/**
 * One episode of a series, as the download choices see it (#48): what the hub's
 * selection says of it and nothing of the device, which [SeriesDownloadChoices]
 * is told separately as the set it already has.
 */
data class SeriesEpisode(
    val id: String,
    val seasonId: String,
    val season: Int,
    val number: Int,
    val played: Boolean,
    val sizeBytes: Long,
    /** The server has a file to download. */
    val available: Boolean = true
) {
    val isSpecial: Boolean get() = season == 0
}

/** What a choice would add: which episodes, and how much they weigh. */
data class DownloadChoice(val ids: List<String>, val bytes: Long) {
    val count: Int get() = ids.size
    val isEmpty: Boolean get() = ids.isEmpty()

    companion object { val NONE = DownloadChoice(emptyList(), 0L) }
}

/**
 * The series page's ways to download, each as the episodes it would add and
 * their size (#48). Pure, so the quick taps, the smart choices panel and select
 * mode all count the same way, and a test pins every number. These are the rules
 * the old episode-selection screen had (next unwatched from the play target, all
 * unwatched, all available), moved here so the new pieces share them.
 *
 * Whatever the device already has, or is fetching, is never added again; and an
 * episode the server has no file for is never added.
 */
object SeriesDownloadChoices {

    fun from(selection: OfflineSelectionResponse): List<SeriesEpisode> = selection.seasons.flatMap { season ->
        season.episodes.map {
            SeriesEpisode(
                id = it.item.id, seasonId = season.season.id,
                season = it.item.seasonNumber.takeIf { n -> n > 0 } ?: season.season.seasonNumber,
                number = it.item.indexNumber, played = it.item.played,
                sizeBytes = it.estimatedSizeBytes, available = it.available
            )
        }
    }

    /** Playing order: season by season, the specials last, as Next up goes. */
    fun ordered(episodes: List<SeriesEpisode>): List<SeriesEpisode> =
        episodes.sortedWith(compareBy({ if (it.season == 0) Int.MAX_VALUE else it.season }, { it.number }))

    /** The episodes that can be fetched: there is a file, and the device does not have it. */
    private fun wanted(episodes: List<SeriesEpisode>, have: Set<String>) =
        ordered(episodes).filter { it.available && it.id !in have }

    private fun choice(list: List<SeriesEpisode>) = DownloadChoice(list.map { it.id }, list.sumOf { it.sizeBytes })

    /**
     * The next [count] episodes to watch, from the one to play next: the unwatched ones in order, the specials left out
     * unless the play target is one. Ones the device already has count towards [count] without being fetched again.
     */
    fun keepReady(episodes: List<SeriesEpisode>, targetId: String?, count: Int, have: Set<String>): DownloadChoice =
        choice(keepReadySet(episodes, targetId, count).filter { it.id !in have })

    /** The episodes Keep ready wants on the device, whether it has them or not. */
    fun keepReadySet(episodes: List<SeriesEpisode>, targetId: String?, count: Int): List<SeriesEpisode> {
        val inOrder = ordered(episodes).filter { it.available }
        val target = inOrder.firstOrNull { it.id == targetId }
        val pool = if (target?.isSpecial == true) inOrder else inOrder.filterNot { it.isSpecial }
        val start = pool.indexOfFirst { it.id == targetId }.takeIf { it >= 0 } ?: pool.indexOfFirst { !it.played }.takeIf { it >= 0 } ?: return emptyList()
        return pool.drop(start).filter { !it.played }.take(count.coerceAtLeast(0))
    }

    /** The unwatched rest of the season Play would start in. */
    fun restOfSeason(episodes: List<SeriesEpisode>, seasonId: String, have: Set<String>): DownloadChoice =
        choice(wanted(episodes, have).filter { it.seasonId == seasonId && !it.played })

    fun everythingUnwatched(episodes: List<SeriesEpisode>, have: Set<String>): DownloadChoice =
        choice(wanted(episodes, have).filter { !it.played })

    fun wholeSeries(episodes: List<SeriesEpisode>, have: Set<String>): DownloadChoice =
        choice(wanted(episodes, have))

    /** What "Season 2 · 4.9 GB" would fetch: every episode of it not on the device yet. */
    fun season(episodes: List<SeriesEpisode>, seasonId: String, have: Set<String>): DownloadChoice =
        choice(wanted(episodes, have).filter { it.seasonId == seasonId })

    /** The season of the episode Play would start, for "Rest of Season X"; else the first that is not Specials. */
    fun targetSeason(episodes: List<SeriesEpisode>, targetId: String?): SeriesEpisode? {
        val inOrder = ordered(episodes)
        return inOrder.firstOrNull { it.id == targetId } ?: inOrder.firstOrNull { !it.played && !it.isSpecial }
            ?: inOrder.firstOrNull { !it.isSpecial } ?: inOrder.firstOrNull()
    }

    /** "3 episodes · 4.1 GB", or "Nothing left to get". */
    fun detail(choice: DownloadChoice, format: (Long) -> String): String =
        if (choice.isEmpty) "Nothing left to get"
        else "${choice.count} episode${if (choice.count == 1) "" else "s"} · ${format(choice.bytes)}"

    /** The words of the season button: "Season 2 · 4.9 GB", or "Season 2 on this Pocket" when nothing is left to get. */
    fun seasonButton(seasonName: String, choice: DownloadChoice, anyAvailable: Boolean, device: String, format: (Long) -> String): String = when {
        !choice.isEmpty -> "$seasonName · ${format(choice.bytes)}"
        anyAvailable -> "$seasonName on this $device"
        else -> "$seasonName unavailable"
    }
}
