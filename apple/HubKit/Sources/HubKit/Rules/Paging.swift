import Foundation

/// Which page of a list to load next, and never the same one twice: Android's
/// `state/PagedLoadState`, held to its tests. A page is asked for once the
/// last card shown is `prefetchAhead` from the end, one page at a time; a
/// failed page is the next one tried, and a cancelled one counts as failed.
public struct PagedLoadState: Equatable, Sendable {
    public let prefetchAhead: Int
    public private(set) var loadedPage = 0
    public private(set) var totalPages = Int.max
    public private(set) var loadingPage: Int?
    public private(set) var failedPage: Int?

    public init(prefetchAhead: Int = 6) {
        self.prefetchAhead = prefetchAhead
    }

    /// The first page.
    public mutating func initial() -> Int? { begin(1) }

    /// A page that arrived some other way (a row's first page in one call).
    public mutating func seed(page: Int, total: Int) {
        guard loadingPage == nil, page > loadedPage else { return }
        loadedPage = page
        totalPages = total
    }

    /// The page to load now that card `lastVisible` of `count` is on screen.
    public mutating func next(lastVisible: Int, count: Int) -> Int? {
        guard count > 0, lastVisible >= count - prefetchAhead else { return nil }
        let candidate = failedPage ?? loadedPage + 1
        if candidate > totalPages && loadedPage > 0 { return nil }
        return begin(candidate)
    }

    public mutating func begin(_ page: Int) -> Int? {
        guard page >= 1, loadingPage == nil else { return nil }
        if page <= loadedPage && page != failedPage { return nil }
        loadingPage = page
        return page
    }

    public mutating func complete(page: Int, total: Int) {
        guard loadingPage == page else { return }
        loadingPage = nil
        loadedPage = max(loadedPage, page)
        totalPages = total
        if failedPage == page { failedPage = nil }
    }

    public mutating func fail(page: Int) {
        if loadingPage == page { loadingPage = nil }
        failedPage = page
    }

    /// A screen left with a page on its way: that page is tried again next.
    public mutating func cancelLoading() {
        guard let page = loadingPage else { return }
        loadingPage = nil
        failedPage = page
    }

    /// The page to try again: the failed one, else the first when nothing has loaded.
    public mutating func retry() -> Int? {
        if let failedPage { return begin(failedPage) }
        return loadedPage == 0 ? begin(1) : nil
    }
}

/// One `PagedLoadState` per row, each seeded with the page its row arrived
/// with: Android's `state/RowPaging`.
public struct RowPaging: Equatable, Sendable {
    public let prefetchAhead: Int
    private var states: [String: PagedLoadState] = [:]

    public init(prefetchAhead: Int = 6) {
        self.prefetchAhead = prefetchAhead
    }

    /// The page of row `key` to load, now that card `lastVisible` of `count` shows.
    public mutating func next(_ key: String, page: Int, totalPages: Int, lastVisible: Int, count: Int) -> Int? {
        var state = states[key] ?? PagedLoadState(prefetchAhead: prefetchAhead)
        state.seed(page: page, total: totalPages)
        let next = state.next(lastVisible: lastVisible, count: count)
        states[key] = state
        return next
    }

    public mutating func complete(_ key: String, page: Int, total: Int) {
        states[key]?.complete(page: page, total: total)
    }

    public mutating func fail(_ key: String, page: Int) {
        states[key]?.fail(page: page)
    }

    public mutating func clear() {
        states = [:]
    }
}
