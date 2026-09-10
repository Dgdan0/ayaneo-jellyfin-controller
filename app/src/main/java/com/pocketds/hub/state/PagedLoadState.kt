package com.pocketds.hub.state

/**
 * Small, framework-free paging guard shared by Library grids and episode lists.
 * It prevents duplicate requests while RecyclerView is rebinding the same end
 * of a page and remembers the failed page so Refresh retries it directly.
 */
class PagedLoadState(private val prefetchAhead: Int = 6) {
    var loadedPage: Int = 0
        private set
    var totalPages: Int = 1
        private set
    var loadingPage: Int? = null
        private set
    var failedPage: Int? = null
        private set

    fun reset() {
        loadedPage = 0
        totalPages = 1
        loadingPage = null
        failedPage = null
    }

    fun initial(): Int? = begin(1)

    fun next(lastVisible: Int, itemCount: Int): Int? {
        if (itemCount <= 0 || lastVisible < itemCount - prefetchAhead) return null
        val candidate = failedPage ?: (loadedPage + 1)
        if (candidate > totalPages && loadedPage > 0) return null
        return begin(candidate)
    }

    fun retry(): Int? = when {
        failedPage != null -> begin(requireNotNull(failedPage))
        loadedPage == 0 -> begin(1)
        else -> null
    }

    /** Make a lifecycle-cancelled request eligible for the normal retry path. */
    fun cancelLoading() {
        loadingPage?.let { failedPage = it }
        loadingPage = null
    }

    fun complete(page: Int, serverTotalPages: Int) {
        if (loadingPage != page) return
        loadedPage = maxOf(loadedPage, page)
        totalPages = maxOf(1, serverTotalPages)
        loadingPage = null
        failedPage = null
    }

    fun fail(page: Int) {
        if (loadingPage != page) return
        loadingPage = null
        failedPage = page
    }

    private fun begin(page: Int): Int? {
        if (page < 1 || loadingPage != null) return null
        if (loadedPage > 0 && page <= loadedPage && failedPage != page) return null
        loadingPage = page
        return page
    }
}
