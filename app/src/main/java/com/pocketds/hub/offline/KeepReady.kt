package com.pocketds.hub.offline

/**
 * What Keep ready does to a series, as the owner decided it (#48): the next N
 * unwatched episodes are on the device, and each one it fetched goes only when
 * the episode after it has been watched. Pure, so the whole policy is one test.
 *
 * - The episodes wanted are the next [Input.count] unwatched ones from the play
 *   target, the target included. Ones the owner downloaded count towards that
 *   number, and are never removed.
 * - An episode Keep ready fetched is removed only once the episode after it is
 *   finished. For A, B, C: finishing A fetches D and A stays (the owner may fall
 *   asleep with B playing); finishing B fetches E and removes A. The one just
 *   finished is therefore always still there.
 * - "Finished" is the server's watched state, so finishing B on another device
 *   counts, and an episode marked unwatched again is kept.
 * - Nothing is removed while something plays; the tidy-up waits for the player
 *   to be left, or for the next launch.
 */
object KeepReady {

    data class Input(
        val episodes: List<SeriesEpisode>,
        /** The episode Play would start; null when the page does not know. */
        val targetId: String?,
        /** The N of "Keep the next N ready". */
        val count: Int,
        /** Every episode the device has or is fetching, whoever asked for it. */
        val onDevice: Set<String>,
        /** The ones Keep ready itself fetched: only these are ever removed. */
        val owned: Set<String>,
        val playing: Boolean
    )

    data class Plan(val download: List<String>, val remove: List<String>) {
        val isEmpty: Boolean get() = download.isEmpty() && remove.isEmpty()
    }

    fun plan(input: Input): Plan {
        val wanted = SeriesDownloadChoices.keepReadySet(input.episodes, input.targetId, input.count)
        val download = wanted.filter { it.id !in input.onDevice }.map { it.id }
        return Plan(download, if (input.playing) emptyList() else removable(input, wanted.map { it.id }.toSet()))
    }

    private fun removable(input: Input, wanted: Set<String>): List<String> {
        val inOrder = SeriesDownloadChoices.ordered(input.episodes).filter { it.available }
        return inOrder.withIndex().filter { (index, episode) ->
            val next = inOrder.getOrNull(index + 1)
            episode.id in input.owned && episode.id in input.onDevice && episode.id !in wanted &&
                episode.played && next?.played == true
        }.map { it.value.id }
    }
}
