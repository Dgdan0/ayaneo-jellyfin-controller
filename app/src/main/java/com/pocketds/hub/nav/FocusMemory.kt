package com.pocketds.hub.nav

/**
 * Where the selection was, per list, so backing out of a detail screen lands on
 * the item you opened rather than at the top.
 *
 * The clamping is the substance. A list is rarely the same length when you come
 * back to it -- a search re-runs, a download finishes and drops out of the queue,
 * a library page returns fewer rows -- and a remembered index of 40 against a
 * list of 12 must land on the last item, never crash and never silently reset to
 * the top.
 */
class FocusMemory {

    private val indices = HashMap<String, Int>()

    fun remember(key: String, index: Int) {
        if (index < 0) indices.remove(key) else indices[key] = index
    }

    /**
     * @return the index to focus, clamped into the current list, or -1 when the
     *   list is empty and there is nothing to focus at all.
     */
    fun restore(key: String, itemCount: Int): Int {
        if (itemCount <= 0) return -1
        val remembered = indices[key] ?: return 0
        return remembered.coerceIn(0, itemCount - 1)
    }

    /** The list is about to be replaced by something unrelated -- a new query. */
    fun forget(key: String) {
        indices.remove(key)
    }

    fun clear() = indices.clear()
}
