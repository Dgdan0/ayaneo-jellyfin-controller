import Foundation

// Books Home's rows (#25): Android's `screens/home/ReadingShelves`, held to
// its tests. The person's own lists and Want to Read are kept on the device,
// per hub and profile, as on the Pocket; nothing here touches the hub.

/// A book on a person's list, with enough of it to show offline.
public struct ReadingListEntry: Codable, Equatable, Sendable, Hashable {
    public var workId: String
    public var title: String
    public var artwork: String
    public var kind: String
    public var series: String
    /// How far through it was when last seen, 0…1.
    public var lastProgress: Double?
    /// When this device last saw it read (milliseconds); 0 when unknown.
    public var lastReadAt: Int64

    public init(workId: String, title: String, artwork: String = "", kind: String = "book", series: String = "",
                lastProgress: Double? = nil, lastReadAt: Int64 = 0) {
        self.workId = workId
        self.title = title
        self.artwork = artwork
        self.kind = kind
        self.series = series
        self.lastProgress = lastProgress
        self.lastReadAt = lastReadAt
    }

    /// The entry for a book, as it stands now.
    public static func from(_ work: ReadingWork) -> ReadingListEntry {
        ReadingListEntry(workId: work.id, title: work.title, artwork: work.artwork, kind: work.kind, series: work.series,
                         lastProgress: work.progress?.completed == true ? 1 : work.progress?.percentage)
    }

    enum CodingKeys: String, CodingKey { case workId, title, artwork, kind, series, lastProgress, lastReadAt }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(workId: c.value(.workId, ""), title: c.value(.title, ""), artwork: c.value(.artwork, ""),
                  kind: c.value(.kind, "book"), series: c.value(.series, ""), lastProgress: c.optional(.lastProgress),
                  lastReadAt: c.value(.lastReadAt, 0))
    }
}

/// A list the person made.
public struct ReadingList: Codable, Equatable, Sendable, Identifiable, Hashable {
    public var id: String
    public var name: String
    public var items: [ReadingListEntry]
    public var updatedAt: Int64

    public init(id: String, name: String, items: [ReadingListEntry] = [], updatedAt: Int64 = 0) {
        self.id = id
        self.name = name
        self.items = items
        self.updatedAt = updatedAt
    }

    enum CodingKeys: String, CodingKey { case id, name, items, updatedAt }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(id: c.value(.id, ""), name: c.value(.name, ""), items: c.value(.items, []),
                  updatedAt: c.value(.updatedAt, 0))
    }
}

/// Want to Read and the person's own lists: Android's `ReadingListsState`.
/// Work ids are the hub's stable ids; the entries' snapshots keep a list
/// showing while the hub cannot be reached.
public struct ReadingListsState: Codable, Equatable, Sendable {
    public static let wantToReadId = "want-to-read"

    public var wantToRead: [ReadingListEntry]
    public var lists: [ReadingList]

    public init(wantToRead: [ReadingListEntry] = [], lists: [ReadingList] = []) {
        self.wantToRead = wantToRead
        self.lists = lists
    }

    public static func nowMillis() -> Int64 { Int64(Date().timeIntervalSince1970 * 1_000) }

    public func create(_ name: String, id: String = UUID().uuidString, at: Int64 = nowMillis()) -> ReadingListsState {
        let clean = String(name.trimmingCharacters(in: .whitespacesAndNewlines).prefix(80))
        guard !clean.isEmpty, !lists.contains(where: { $0.id == id }) else { return self }
        var next = self
        next.lists.append(ReadingList(id: id, name: clean, updatedAt: at))
        return next
    }

    public func rename(_ id: String, to name: String, at: Int64 = nowMillis()) -> ReadingListsState {
        let clean = String(name.trimmingCharacters(in: .whitespacesAndNewlines).prefix(80))
        guard !clean.isEmpty else { return self }
        var next = self
        next.lists = lists.map { list in
            guard list.id == id else { return list }
            var renamed = list
            renamed.name = clean
            renamed.updatedAt = at
            return renamed
        }
        return next
    }

    public func delete(_ id: String) -> ReadingListsState {
        var next = self
        next.lists.removeAll { $0.id == id }
        return next
    }

    /// Adds a book to a list, once. Want to Read takes only a book not yet started.
    public func add(_ id: String, _ entry: ReadingListEntry, at: Int64 = nowMillis()) -> ReadingListsState {
        guard !entry.workId.trimmingCharacters(in: .whitespaces).isEmpty else { return self }
        var next = self
        if id == Self.wantToReadId {
            next.wantToRead = wantToRead.filter { $0.workId != entry.workId } + ((entry.lastProgress ?? 0) > 0 ? [] : [entry])
            return next
        }
        next.lists = lists.map { list in
            guard list.id == id else { return list }
            var changed = list
            changed.items = list.items.filter { $0.workId != entry.workId } + [entry]
            changed.updatedAt = at
            return changed
        }
        return next
    }

    public func remove(_ id: String, workId: String, at: Int64 = nowMillis()) -> ReadingListsState {
        var next = self
        if id == Self.wantToReadId {
            next.wantToRead = wantToRead.filter { $0.workId != workId }
            return next
        }
        next.lists = lists.map { list in
            guard list.id == id else { return list }
            var changed = list
            changed.items = list.items.filter { $0.workId != workId }
            changed.updatedAt = at
            return changed
        }
        return next
    }

    /// Moves a book `delta` places along its list, not past either end.
    public func move(_ id: String, workId: String, by delta: Int, at: Int64 = nowMillis()) -> ReadingListsState {
        func shifted(_ items: [ReadingListEntry]) -> [ReadingListEntry] {
            guard let index = items.firstIndex(where: { $0.workId == workId }) else { return items }
            let target = index + delta
            guard items.indices.contains(target) else { return items }
            var moved = items
            moved.insert(moved.remove(at: index), at: target)
            return moved
        }
        var next = self
        if id == Self.wantToReadId {
            next.wantToRead = shifted(wantToRead)
            return next
        }
        next.lists = lists.map { list in
            guard list.id == id else { return list }
            var changed = list
            changed.items = shifted(list.items)
            changed.updatedAt = at
            return changed
        }
        return next
    }

    /// What the hub says of a book's progress. Starting a book takes it off
    /// Want to Read for good; a list keeps it and notes when it was read.
    /// An undated reading (`at` 0) does not pretend it happened just now.
    public func recordProgress(_ workId: String, percentage: Double, at: Int64 = nowMillis()) -> ReadingListsState {
        guard percentage.isFinite, (0...1).contains(percentage) else { return self }
        func observed(_ entry: ReadingListEntry) -> ReadingListEntry {
            guard entry.workId == workId, entry.lastProgress != percentage else { return entry }
            var seen = entry
            seen.lastProgress = percentage
            if percentage > 0 && at > 0 { seen.lastReadAt = max(entry.lastReadAt, at) }
            return seen
        }
        var next = self
        next.wantToRead = wantToRead.filter { !($0.workId == workId && percentage > 0) }.map(observed)
        next.lists = lists.map { list in
            var changed = list
            changed.items = list.items.map(observed)
            return changed
        }
        return next
    }

    public func encoded() -> Data {
        (try? JSONEncoder().encode(self)) ?? Data("{}".utf8)
    }

    /// Unreadable or missing data is an empty state, never a failure.
    public static func decode(_ data: Data?) -> ReadingListsState {
        guard let data, let state = try? JSONDecoder().decode(ReadingListsState.self, from: data) else { return ReadingListsState() }
        return state
    }

    enum CodingKeys: String, CodingKey { case wantToRead, lists }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(wantToRead: c.value(.wantToRead, []), lists: c.value(.lists, []))
    }
}

/// One row of Books Home.
public struct ReadingShelfRow: Equatable, Sendable, Identifiable {
    public let id: String
    public let title: String
    public let items: [ReadingWork]
    /// The first book of a list not yet finished: where its row opens.
    public let nextIndex: Int
    public let readCount: Int
    public let activity: Int64
    public let hasReadingActivity: Bool

    public init(id: String, title: String, items: [ReadingWork], nextIndex: Int = 0, readCount: Int = 0, activity: Int64 = 0,
                hasReadingActivity: Bool = false) {
        self.id = id
        self.title = title
        self.items = items
        self.nextIndex = nextIndex
        self.readCount = readCount
        self.activity = activity
        self.hasReadingActivity = hasReadingActivity
    }

    /// Want to Read and the person's own lists hold what they put there.
    public var isOwnList: Bool { !ReadingShelves.builtIn.contains(id) }
}

/// Books Home's rows, from what the libraries said. Android's `ReadingShelves`.
public enum ReadingShelves {
    public static let currentlyReading = "currently-reading"
    public static let nextInSeriesId = "next-in-series"
    public static let comics = "comics"
    public static let recentlyAdded = "recently-added"
    /// Rows the app fills itself; every other row is one of the person's own lists.
    public static let builtIn: Set<String> = [currentlyReading, nextInSeriesId, comics, ReadingListsState.wantToReadId,
                                              recentlyAdded]
    /// The most covers a fan holds.
    public static let fanCoverLimit = 4

    /// Every book being read, newest first: series are opened up into their books.
    public static func current(_ works: [ReadingWork]) -> [ReadingWork] {
        let books = works.flatMap { work -> [ReadingWork] in
            guard work.isSeries else { return [work] }
            return work.sections.flatMap(\.items).compactMap { child in
                child.workId.trimmingCharacters(in: .whitespaces).isEmpty ? nil : asWork(child, of: work)
            }
        }
        var seen = Set<String>()
        let reading = books.filter { work in
            guard !work.id.trimmingCharacters(in: .whitespaces).isEmpty, let p = work.progress, !p.completed,
                  p.percentage > 0 else { return false }
            return seen.insert(work.id).inserted
        }
        return reading.enumerated()
            .sorted { a, b in
                let ta = timestamp(a.element.progress?.updatedAt), tb = timestamp(b.element.progress?.updatedAt)
                return ta != tb ? ta > tb : a.offset < b.offset
            }
            .map(\.element)
    }

    public static func isComic(_ work: ReadingWork) -> Bool { work.kind == "comic" || work.kind == "manga" }

    /// One card per series, the book of it read last: Red Rising read in three
    /// places at once filled a row with three Red Rising cards. `reads` is
    /// newest first.
    public static func onePerSeries(_ reads: [ReadingWork]) -> [ReadingWork] {
        var seen = Set<String>()
        return reads.filter { $0.series.trimmingCharacters(in: .whitespaces).isEmpty || seen.insert($0.series).inserted }
    }

    /// The number of the book being read in a series, or empty.
    public static func onNumber(_ series: ReadingWork) -> String {
        if let number = series.continueAt?.number, !number.trimmingCharacters(in: .whitespaces).isEmpty { return number }
        let books = series.sections.flatMap(\.items)
        return books.last { ($0.progress?.percentage ?? 0) > 0 && $0.progress?.completed != true }?.number ?? ""
    }

    /// A series' covers for a fan: the book being read in front, then its
    /// first books in order; up to four.
    public static func fanCovers(_ series: ReadingWork) -> [String] {
        let books = series.sections.flatMap(\.items)
        let on = onNumber(series)
        let front = series.continueAt.map(\.artwork).flatMap { $0.isEmpty ? nil : $0 }
            ?? books.first { $0.number == on }?.artwork ?? ""
        var seen = Set<String>()
        let covers = ([front] + books.map(\.artwork))
            .filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty && seen.insert($0).inserted }
            .prefix(fanCoverLimit)
        if !covers.isEmpty { return Array(covers) }
        return series.artwork.isEmpty ? [] : [series.artwork]
    }

    /// A series on Books Home: its covers fanned, front first, and "6 books · on #6".
    public struct SeriesShelfItem: Equatable, Sendable, Identifiable {
        public let id: String
        public let title: String
        public let covers: [String]
        public let line: String
        /// Its fan (#54): three of its books about the one you are on, as the Series view stands them.
        public let plan: SeriesFan.Plan?

        public init(id: String, title: String, covers: [String], line: String, plan: SeriesFan.Plan? = nil) {
            self.id = id
            self.title = title
            self.covers = covers
            self.line = line
            self.plan = plan
        }
    }

    /// The series being read, last read first: a book started and not every
    /// book finished.
    public static func yourSeries(_ collections: [ReadingWork]) -> [SeriesShelfItem] {
        var seen = Set<String>()
        let found: [(Int64, SeriesShelfItem)] = collections.compactMap { series in
            guard series.isSeries, seen.insert(series.id).inserted else { return nil }
            let books = series.sections.flatMap(\.items)
            guard books.contains(where: { ($0.progress?.percentage ?? 0) > 0 || $0.progress?.completed == true }) else { return nil }
            if !books.isEmpty && books.allSatisfy({ $0.progress?.completed == true }) { return nil }
            let on = onNumber(series)
            let count = series.bookCount > 0 ? series.bookCount : books.count
            let line = [count > 0 ? "\(count) \(count == 1 ? "book" : "books")" : nil, on.isEmpty ? nil : "on #\(on)"]
                .compactMap { $0 }.joined(separator: " · ")
            let last = books.isEmpty ? timestamp(series.progress?.updatedAt)
                : books.map { timestamp($0.progress?.updatedAt) }.max() ?? 0
            return (last, SeriesShelfItem(id: series.id, title: series.title, covers: fanCovers(series), line: line,
                                          plan: SeriesFan.plan(series, maxSlots: SeriesFan.smallSlots)))
        }
        return stableSortedDescending(found).map(\.1)
    }

    /// For each series with nothing in progress: the first available,
    /// unfinished book after the last one finished. Most recently finished
    /// series first.
    public static func nextInSeries(_ collections: [ReadingWork]) -> [ReadingWork] {
        let found: [(Int64, ReadingWork)] = collections.compactMap { series in
            guard series.isSeries else { return nil }
            let books = series.sections.flatMap(\.items)
            if books.contains(where: { if let p = $0.progress { !p.completed && p.percentage > 0 } else { false } }) {
                return nil
            }
            guard let last = books.lastIndex(where: { $0.progress?.completed == true }) else { return nil }
            guard let next = books.dropFirst(last + 1).first(where: { $0.isAvailable && $0.progress?.completed != true })
            else { return nil }
            return (timestamp(books[last].progress?.updatedAt), asWork(next, of: series))
        }
        return stableSortedDescending(found).map(\.1)
    }

    /// A person's list as a row: its books in their order, the first unread
    /// one where the row opens.
    public static func listRow(_ list: ReadingList, resolved: [String: ReadingWork]) -> ReadingShelfRow {
        let items = list.items.map { resolved[$0.workId] ?? snapshot($0) }
        let next = items.firstIndex { $0.progress?.completed != true } ?? max(items.count - 1, 0)
        let serverActivity = items.map { timestamp($0.progress?.updatedAt) }.max() ?? 0
        let localActivity = list.items.map(\.lastReadAt).max() ?? 0
        let readActivity = max(serverActivity, localActivity)
        return ReadingShelfRow(id: list.id, title: list.name, items: items, nextIndex: next,
                               readCount: items.filter { $0.progress?.completed == true }.count,
                               activity: readActivity > 0 ? readActivity : list.updatedAt,
                               hasReadingActivity: readActivity > 0)
    }

    /// Books Home's rows, in order: Currently reading (one per series, comics
    /// apart), Next in series, Comics and manga, Want to Read, the person's
    /// lists (read most recently first), Recently added.
    public static func rows(current works: [ReadingWork], state: ReadingListsState, resolved: [String: ReadingWork],
                            next: [ReadingWork] = [], recent: [ReadingWork] = []) -> [ReadingShelfRow] {
        let now = current(works)
        let comicRows = now.filter(isComic)
        let comicsTitle = comicRows.allSatisfy { $0.kind == "manga" } ? "Manga"
            : comicRows.allSatisfy { $0.kind == "comic" } ? "Comics" : "Comics and manga"
        let readingIds = Set(now.map(\.id))
        let builtInRows = [
            ReadingShelfRow(id: currentlyReading, title: "Currently reading", items: onePerSeries(now.filter { !isComic($0) })),
            ReadingShelfRow(id: nextInSeriesId, title: "Next in series", items: next.filter { !readingIds.contains($0.id) }),
            ReadingShelfRow(id: comics, title: comicsTitle, items: comicRows),
        ].filter { !$0.items.isEmpty }
        let wanted = state.wantToRead.map { resolved[$0.workId] ?? snapshot($0) }.filter { work in
            if let p = work.progress, p.completed || p.percentage > 0 { return false }
            return !readingIds.contains(work.id)
        }
        let wantRow = ReadingShelfRow(id: ReadingListsState.wantToReadId, title: "Want to Read", items: wanted)
        let lists = state.lists.map { listRow($0, resolved: resolved) }.enumerated().sorted { a, b in
            if a.element.hasReadingActivity != b.element.hasReadingActivity { return a.element.hasReadingActivity }
            if a.element.activity != b.element.activity { return a.element.activity > b.element.activity }
            if a.element.title != b.element.title { return a.element.title < b.element.title }
            return a.offset < b.offset
        }.map(\.element)
        let added = recent.isEmpty ? [] : [ReadingShelfRow(id: recentlyAdded, title: "Recently added", items: recent)]
        return builtInRows + [wantRow] + lists + added
    }

    /// A reading time from the hub in milliseconds: seconds or milliseconds
    /// since 1970, an ISO 8601 instant, or Storyteller's "2026-09-27
    /// 03:16:47", which is UTC with a space for the T and no zone. Unparsed,
    /// every read counted as never and Red Rising showed book 1 over book 6.
    public static func timestamp(_ raw: String?) -> Int64 {
        guard let text = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty else { return 0 }
        if let number = Int64(text) { return number < 10_000_000_000 ? number * 1_000 : number }
        if let date = iso8601(text) { return Int64((date.timeIntervalSince1970 * 1_000).rounded()) }
        let zoneless = text.replacingOccurrences(of: " ", with: "T") + "Z"
        if let date = iso8601(zoneless) { return Int64((date.timeIntervalSince1970 * 1_000).rounded()) }
        return 0
    }

    private static func iso8601(_ text: String) -> Date? {
        if let date = try? Date(text, strategy: Date.ISO8601FormatStyle()) { return date }
        return try? Date(text, strategy: Date.ISO8601FormatStyle(includingFractionalSeconds: true))
    }

    /// A book of a series as a work of its own, for a card.
    static func asWork(_ item: ReadingSectionItem, of series: ReadingWork) -> ReadingWork {
        ReadingWork(id: item.workId, libraryId: series.libraryId, entityType: "work", kind: item.kind, title: item.title,
                    authors: item.authors, series: series.title, seriesIndex: Double(item.number) ?? 0,
                    artwork: item.artwork.isEmpty ? series.artwork : item.artwork, progress: item.progress)
    }

    /// A list entry the hub has not answered for: what the device last saw.
    static func snapshot(_ entry: ReadingListEntry) -> ReadingWork {
        ReadingWork(id: entry.workId, kind: entry.kind, title: entry.title, series: entry.series, artwork: entry.artwork,
                    progress: entry.lastProgress.map { ReadingProgress(percentage: $0, completed: $0 >= 0.999) })
    }

    /// Kotlin's stable `sortedByDescending`.
    private static func stableSortedDescending<T>(_ items: [(Int64, T)]) -> [(Int64, T)] {
        items.enumerated().sorted { a, b in
            a.element.0 != b.element.0 ? a.element.0 > b.element.0 : a.offset < b.offset
        }.map(\.element)
    }
}
