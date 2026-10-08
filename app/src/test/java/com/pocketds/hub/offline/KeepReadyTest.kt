package com.pocketds.hub.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The owner's one-episode buffer (#48): next N ready, each fetched one gone only when the one after it is finished. */
class KeepReadyTest {
    private fun ep(number: Int, played: Boolean = false, season: Int = 1) =
        SeriesEpisode("e$season$number", "season$season", season, number, played, 100, true)

    /** Episodes 1 to [count] of season 1, the first [watched] of them watched. */
    private fun series(count: Int = 8, watched: Int = 0) = (1..count).map { ep(it, played = it <= watched) }

    private fun plan(
        episodes: List<SeriesEpisode>, target: String?, onDevice: Set<String>, owned: Set<String>,
        count: Int = 3, playing: Boolean = false
    ) = KeepReady.plan(KeepReady.Input(episodes, target, count, onDevice, owned, playing))

    @Test fun `turned on at the start it fetches the next three`() {
        val plan = plan(series(), "e11", emptySet(), emptySet())
        assertEquals(listOf("e11", "e12", "e13"), plan.download)
        assertTrue(plan.remove.isEmpty())
    }

    @Test fun `finishing A fetches D and A stays`() {
        // A, B and C are here, all fetched by Keep ready; A is now watched and B is next.
        val have = setOf("e11", "e12", "e13")
        val plan = plan(series(watched = 1), "e12", have, have)
        assertEquals(listOf("e14"), plan.download)
        assertEquals("A stays: the owner may have fallen asleep with B playing", emptyList<String>(), plan.remove)
    }

    @Test fun `finishing B fetches E and removes A`() {
        val have = setOf("e11", "e12", "e13", "e14")
        val plan = plan(series(watched = 2), "e13", have, have)
        assertEquals(listOf("e15"), plan.download)
        assertEquals(listOf("e11"), plan.remove)
    }

    @Test fun `an episode it fetched stays until the next one is finished, however far on the owner is`() {
        // Watched 1 to 5: 1, 2, 3 and 4 have been followed by a finished episode; 5 has not.
        val have = (1..8).map { "e1$it" }.toSet()
        val plan = plan(series(watched = 5), "e16", have, have)
        assertEquals(listOf("e11", "e12", "e13", "e14"), plan.remove)
    }

    @Test fun `the owner's own downloads are never removed, and count towards N`() {
        // The owner downloaded B themselves; Keep ready fetched A and C.
        val have = setOf("e11", "e12", "e13")
        val owned = setOf("e11", "e13")
        val afterB = plan(series(watched = 2), "e13", have, owned)
        assertEquals("B is the owner's: it stays", listOf("e11"), afterB.remove)
        // Later, with D and E fetched too: B is watched and the owner's, so it stays whatever follows it.
        val later = plan(series(watched = 4), "e15", have + setOf("e14", "e15"), owned + setOf("e14", "e15"))
        assertEquals(listOf("e11", "e13"), later.remove)
        // Owner-downloaded episodes ahead are not fetched again: B and C count among the next three.
        val ahead = plan(series(), "e11", setOf("e12", "e13"), emptySet())
        assertEquals(listOf("e11"), ahead.download)
    }

    @Test fun `nothing is removed while something plays, though the next ones are still fetched`() {
        val have = setOf("e11", "e12", "e13", "e14")
        val playing = plan(series(watched = 2), "e13", have, have, playing = true)
        assertEquals(listOf("e15"), playing.download)
        assertTrue(playing.remove.isEmpty())
        assertEquals(listOf("e11"), plan(series(watched = 2), "e13", have, have, playing = false).remove)
    }

    @Test fun `an episode marked unwatched again is kept, and counts as a next one`() {
        val have = setOf("e11", "e12", "e13", "e14")
        // B was finished and has been marked unwatched: A is followed by an unwatched episode now.
        val episodes = series(watched = 2).map { if (it.id == "e12") it.copy(played = false) else it }
        val plan = plan(episodes, "e12", have, have)
        assertTrue("A is kept: B is not finished any more", plan.remove.isEmpty())
        assertEquals("the three ahead are B, C and D", emptyList<String>(), plan.download)
    }

    @Test fun `finishing on another device counts, because it is the server's watched state`() {
        // The device knows nothing of the viewing; the hub says 1 to 4 are watched.
        val have = setOf("e11", "e12", "e13", "e14")
        val plan = plan(series(watched = 4), "e15", have, have)
        assertEquals(listOf("e11", "e12", "e13"), plan.remove)
        assertEquals(listOf("e15", "e16", "e17"), plan.download)
    }

    @Test fun `the last episode is never removed, since there is no next one to finish`() {
        val episodes = series(count = 3, watched = 3)
        val plan = plan(episodes, null, setOf("e11", "e12", "e13"), setOf("e11", "e12", "e13"))
        assertEquals(listOf("e11", "e12"), plan.remove)
        assertTrue(plan.download.isEmpty())
    }

    @Test fun `watching out of order removes nothing that is still unwatched`() {
        // B was watched but A was not: A is the play target and nothing before the first unwatched episode is lost.
        val have = setOf("e11", "e12", "e13")
        val episodes = series(count = 6).map { if (it.number == 2) it.copy(played = true) else it }
        val plan = plan(episodes, "e11", have, have, count = 5)
        assertTrue(plan.remove.isEmpty())
        assertEquals(listOf("e14", "e15", "e16"), plan.download)
    }

    @Test fun `a series with every episode watched, or none, has nothing to fetch`() {
        assertTrue(plan(series(watched = 8), null, emptySet(), emptySet()).download.isEmpty())
        assertTrue(plan(emptyList(), null, emptySet(), emptySet()).isEmpty)
    }
}
