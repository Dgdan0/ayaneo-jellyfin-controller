package com.pocketds.hub.reader

import com.pocketds.hub.input.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderFocusGraphTest {
    @Test
    fun `every visible book control is reachable from initial focus`() {
        val graph = ReaderFocusGraph(ReaderProfile.BOOK)
        val reached = reachable(graph, graph.initial)

        assertEquals(graph.controls.toSet(), reached)
    }

    @Test
    fun `read along adds an audio control without breaking reachability`() {
        val graph = ReaderFocusGraph(ReaderProfile.READ_ALONG)
        val reached = reachable(graph, graph.initial)

        assertTrue(ReaderControl.AUDIO in graph.controls)
        assertEquals(graph.controls.toSet(), reached)
    }

    @Test
    fun `focus stops at row edges instead of wrapping unexpectedly`() {
        val graph = ReaderFocusGraph(ReaderProfile.COMIC)

        assertEquals(
            ReaderControl.CLOSE,
            graph.move(ReaderControl.CLOSE, Direction.LEFT)
        )
        assertEquals(
            ReaderControl.NEXT,
            graph.move(ReaderControl.NEXT, Direction.RIGHT)
        )
    }

    private fun reachable(graph: ReaderFocusGraph, start: ReaderControl): Set<ReaderControl> {
        val pending = ArrayDeque<ReaderControl>()
        val seen = linkedSetOf<ReaderControl>()
        pending += start
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!seen.add(current)) continue
            Direction.entries.forEach { direction ->
                val next = graph.move(current, direction)
                if (next !in seen) pending += next
            }
        }
        return seen
    }
}
