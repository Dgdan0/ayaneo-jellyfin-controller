package com.pocketds.hub.nav

/**
 * One back stack per top-level section, with L1/R1 moving between them.
 *
 * The point is that each section keeps its own place. Going Library -> a series
 * -> season 3, then R1 to Downloads and R1 back, lands you on season 3 again
 * rather than at the top of Library. A single shared stack cannot do that, and
 * re-fetching to rebuild it over a slow link is exactly the thing this app is
 * trying to avoid.
 *
 * Switching wraps, because on a gamepad the shoulder buttons are a ring and
 * hitting a wall at either end feels broken.
 */
class SectionStacks(val sectionCount: Int) {

    init {
        require(sectionCount > 0) { "need at least one section" }
    }

    private val stacks = List(sectionCount) { ScreenStack() }

    var current: Int = 0
        private set

    fun stack(index: Int = current): ScreenStack = stacks[index]

    fun push(screen: StackScreen) = stacks[current].push(screen)

    /** @return false at a section root, so B there does nothing. */
    fun back(): Boolean = stacks[current].pop()

    val depth: Int get() = stacks[current].depth

    /**
     * @param delta -1 for L1, +1 for R1. Wraps at both ends.
     * @return true if the section actually changed.
     */
    fun switch(delta: Int): Boolean {
        if (sectionCount == 1) return false
        val next = Math.floorMod(current + delta, sectionCount)
        if (next == current) return false
        stacks[current].hideTop()
        current = next
        stacks[current].showTop()
        return true
    }

    /** Jump straight to a section, for tapping a tab. */
    fun select(index: Int): Boolean {
        require(index in 0 until sectionCount) { "no section $index" }
        if (index == current) return false
        stacks[current].hideTop()
        current = index
        stacks[current].showTop()
        return true
    }

    fun clear() = stacks.forEach { it.clear() }
}
