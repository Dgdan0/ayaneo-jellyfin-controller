package com.pocketds.hub.reader

/** A page of one publication: what one of the reader's surfaces holds. */
data class PageKey(val publication: String, val page: Int)

/**
 * Which pages the comic reader keeps decoded, and in which surface (#16, C3).
 *
 * A turn used to recycle the page on screen and show "Loading page N" over
 * black until the next one decoded. Now the page you are on, the next one the
 * way you are reading and the one before stay decoded side by side, so a turn
 * either way swaps to a page already drawn, and a jump keeps the page you were
 * on until the new one is ready. Pure, so a JVM test pins the choices.
 */
object PageSlots {
    /** Surfaces: the page, the next and the one before. */
    const val COUNT = 3

    /**
     * The pages worth holding round [current], most wanted first: it, the next
     * one the way you are going, then the other way.
     */
    fun wanted(current: Int, pageCount: Int, forward: Boolean = true, count: Int = COUNT): List<Int> {
        val order = if (forward) listOf(current, current + 1, current - 1) else listOf(current, current - 1, current + 1)
        return order.filter { it in 0 until pageCount }.distinct().take(count)
    }

    /**
     * What each surface should hold next: one already holding a wanted page
     * keeps it, so nothing decoded is thrown away; the others take the wanted
     * pages left, most wanted first. Null: wanted for nothing, free to recycle.
     */
    fun assign(held: List<PageKey?>, wanted: List<PageKey>): List<PageKey?> {
        val kept = mutableSetOf<PageKey>()
        val result = held.map { key -> key?.takeIf { it in wanted && kept.add(it) } }.toMutableList()
        val missing = wanted.filter { it !in kept }.iterator()
        for (index in result.indices) {
            if (!missing.hasNext()) break
            if (result[index] == null) result[index] = missing.next()
        }
        return result
    }
}
