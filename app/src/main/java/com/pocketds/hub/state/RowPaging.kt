package com.pocketds.hub.state

/**
 * One [PagedLoadState] per row of an endless strip screen, seeded with the
 * page each row arrived with.
 *
 * Discover's media rows, its book rows and its search grid each kept their own
 * requested-page map and job map to stop a sweep along a row fetching page 2
 * four times; this is that rule once, and the same one Library grids use.
 */
class RowPaging(private val prefetchAhead: Int) {
    private val rows = HashMap<String, PagedLoadState>()

    /** The page to fetch now for [key], or null. */
    fun next(key: String, loadedPage: Int, totalPages: Int, lastVisible: Int, itemCount: Int): Int? =
        rows.getOrPut(key) { PagedLoadState(prefetchAhead).also { it.seed(loadedPage, totalPages) } }
            .next(lastVisible, itemCount)

    fun complete(key: String, page: Int, totalPages: Int) {
        rows[key]?.complete(page, totalPages)
    }

    /** A failed or discarded page is retried by the next scroll. */
    fun fail(key: String, page: Int) {
        rows[key]?.fail(page)
    }

    /** Forget every row: after a refresh or a new search, or when the screen hides and its requests are cancelled. */
    fun clear() = rows.clear()
}
