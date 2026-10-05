package com.pocketds.hub.nav

/**
 * Media and Books keep their own pages (#18): each content tab has a stack
 * per side.
 *
 * A tab's root shows either side; the pages pushed over it belong to one,
 * [sideOf] says which (null for a page that follows the side itself, as a
 * root does, which stays on both). Choosing a side takes the other side's
 * pages off every tab, kept alive as they were (their rows, their place,
 * their focus), and puts back the pages this side left there, so switching
 * back returns each tab to where it was. Since #11 a switch dropped them,
 * and a film's page had to be found again after a look at the books.
 *
 * Pages go back only over the page they were taken from. If the tab has
 * moved on since (its root is the only page that can be under them, so that
 * is rare), they are dropped and returned for the caller to let go of.
 *
 * Only the tab on screen changes what shows: when its top changes, the old
 * top is hidden and the new one shown; the hidden tabs show theirs when they
 * are chosen. Pure, with [StackScreen]s, so tested without Android.
 */
class SidePages<S : Any>(
    private val sections: SectionStacks,
    private val contentCount: Int,
    private val sideOf: (StackScreen) -> S?
) {
    private class Parked(val base: StackScreen?, val pages: List<StackScreen>)

    private val parked = HashMap<Pair<Int, Any>, Parked>()

    /**
     * Shows [side]'s pages on every content tab. Returns the pages let go
     * of for good (already destroyed), whose views the caller removes.
     */
    fun show(side: S): List<StackScreen> {
        val dropped = mutableListOf<StackScreen>()
        for (index in 0 until contentCount.coerceAtMost(sections.sectionCount)) {
            val stack = sections.stack(index)
            val visible = index == sections.current
            val before = stack.peek()
            // The other side's pages, from the top down to this side's, a page of both, or the root.
            var leaving: S? = null
            val taken = ArrayDeque<StackScreen>()
            while (stack.depth > 1) {
                val top = stack.peek() ?: break
                val owner = sideOf(top) ?: break
                if (owner == side || (leaving != null && owner != leaving)) break
                leaving = owner
                stack.park()
                taken.addFirst(top)
            }
            if (leaving != null) {
                parked.put(index to leaving, Parked(stack.peek(), taken.toList()))?.let { dropped += it.pages }
            }
            parked.remove(index to side)?.let { back ->
                if (back.base === stack.peek()) back.pages.forEach(stack::restore) else dropped += back.pages
            }
            val after = stack.peek()
            if (visible && after !== before) {
                before?.onHide()
                after?.onShow()
            }
        }
        dropped.forEach { it.onDestroyView() }
        return dropped
    }

    /** The pages kept for [side] on content tab [index], bottom first: for tests and for letting go. */
    fun parkedFor(index: Int, side: S): List<StackScreen> = parked[index to side]?.pages.orEmpty()

    /** Lets go of every page kept for later (the profile changed, the app is closing); returns them, destroyed. */
    fun clear(): List<StackScreen> {
        val all = parked.values.flatMap { it.pages }
        parked.clear()
        all.forEach { it.onDestroyView() }
        return all
    }
}
