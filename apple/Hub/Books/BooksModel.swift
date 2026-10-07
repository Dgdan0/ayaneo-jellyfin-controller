import CryptoKit
import Foundation
import HubKit
import Observation

/// What the Books side keeps beside the hub (#25), one per window: the
/// libraries' names, the person's own lists and Want to Read, the books
/// marked read or unread (#37), the way each book was last opened, and each
/// library view's order. Lists and the way a
/// book opens are kept per hub and profile, as on the Pocket, so another
/// profile on this device never sees them; the token is no part of that, so
/// a new token keeps them.
@MainActor
@Observable
final class BooksModel {
    private(set) var libraryNames = ReadingLibraryNames()
    private(set) var lists = ReadingListsState()
    /// Counts the changes to the lists, for a page that shows them.
    private(set) var listsRevision = 0
    /// Books marked read or unread by hand (#37).
    private(set) var completion = ReadingCompletionState()

    @ObservationIgnored private let defaults: UserDefaults
    @ObservationIgnored private var identity = ""

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    /// The hub and profile the person reads as; called whenever either changes.
    func use(address: String, userId: String) {
        let next = Self.digest(Self.trimmed(address) + "\u{0}" + userId)
        guard next != identity else { return }
        identity = next
        lists = ReadingListsState.decode(defaults.data(forKey: listsKey))
        listsRevision += 1
        // The demo hub forgets its places each launch, and so its marks too.
        if ProcessInfo.processInfo.arguments.contains("-demo") && !Self.demoMarksForgotten {
            Self.demoMarksForgotten = true
            defaults.removeObject(forKey: completionKey)
        }
        completion = ReadingCompletionState.decode(defaults.data(forKey: completionKey))
    }

    private static var demoMarksForgotten = false

    // MARK: Read and unread (#37)

    /// Changes the marks and keeps them.
    func updateCompletion(_ change: (ReadingCompletionState) -> ReadingCompletionState) {
        let next = change(completion)
        guard next != completion else { return }
        completion = next
        defaults.set(next.encoded(), forKey: completionKey)
    }

    /// `work` as the person marked it.
    func project(_ work: ReadingWork) -> ReadingWork { completion.project(work) }

    /// A reader kept a place in the book: its own progress speaks again.
    func readerKept(_ workId: String) {
        updateCompletion { $0.clear(workId) }
    }

    private var completionKey: String { "books.completion." + identity }

    // MARK: Library names

    func remember(_ libraries: [ReadingLibrary]) {
        libraryNames.remember(libraries)
    }

    // MARK: Lists

    /// Changes the lists and keeps them.
    func updateLists(_ change: (ReadingListsState) -> ReadingListsState) {
        let next = change(lists)
        guard next != lists else { return }
        lists = next
        defaults.set(next.encoded(), forKey: listsKey)
        listsRevision += 1
    }

    func isWanted(_ workId: String) -> Bool { lists.wantToRead.contains { $0.workId == workId } }

    /// Want to Read on or off for a book; true when it is now on.
    @discardableResult
    func toggleWanted(_ work: ReadingWork) -> Bool {
        if isWanted(work.id) {
            updateLists { $0.remove(ReadingListsState.wantToReadId, workId: work.id) }
            return false
        }
        updateLists { $0.add(ReadingListsState.wantToReadId, ReadingListEntry.from(work)) }
        return isWanted(work.id)
    }

    /// What the hub says of books on the lists, as marked here: a started book leaves Want to Read.
    func observe(_ works: [ReadingWork]) {
        let ids = Set(lists.wantToRead.map(\.workId) + lists.lists.flatMap { $0.items.map(\.workId) })
        let seen = works.filter { ids.contains($0.id) }.map(completion.project)
        guard !seen.isEmpty else { return }
        updateLists { state in
            seen.reduce(state) { next, work in
                guard let progress = work.progress else { return next }
                return next.recordProgress(work.id, percentage: progress.completed ? 1 : progress.percentage,
                                           at: ReadingShelves.timestamp(progress.updatedAt))
            }
        }
    }

    // MARK: How a book was last opened

    func entryPreference(_ workId: String) -> ReadingEntryPreference? {
        ReadingEntryPreference.decode(defaults.string(forKey: "books.entry." + Self.digest(identity + "\u{0}" + workId)))
    }

    func setEntryPreference(_ preference: ReadingEntryPreference, for workId: String) {
        defaults.set(preference.encoded, forKey: "books.entry." + Self.digest(identity + "\u{0}" + workId))
    }

    // MARK: Requests made here

    /// What a request left to come back to (Android keeps both per title):
    /// BookKeeprr's series, and the books whose release is still to choose.
    struct RequestRecord: Codable, Equatable {
        var seriesId: Int
        var targets: [ReadingRequestTarget]
    }

    func requestRecord(_ key: String) -> RequestRecord? {
        guard let data = defaults.data(forKey: Self.requestKey(key)) else { return nil }
        return try? JSONDecoder().decode(RequestRecord.self, from: data)
    }

    func rememberRequest(_ key: String, _ record: RequestRecord) {
        defaults.set(try? JSONEncoder().encode(record), forKey: Self.requestKey(key))
    }

    private static func requestKey(_ key: String) -> String { "books.request." + digest(key) }

    // MARK: A library's order and view

    /// Series, Authors or Books, where a library can show all three.
    enum LibraryView: String, CaseIterable {
        case series, authors, books

        var title: String { rawValue.capitalized }
    }

    var libraryView: LibraryView {
        get { LibraryView(rawValue: defaults.string(forKey: "books.view") ?? "") ?? .series }
        set { defaults.set(newValue.rawValue, forKey: "books.view") }
    }

    /// The order of the Series view (Android's `DomainPreferences.sort` for Books).
    func seriesSort(fields: [String]) -> SortPreference {
        stored("books.sort", fields: fields, fallback: fields.contains("series") ? "series" : "title")
    }

    func setSeriesSort(_ value: SortPreference) { defaults.set(value.encoded, forKey: "books.sort") }

    /// The order of the Books view (`DomainPreferences.bookSort`).
    func bookSort(fields: [String]) -> SortPreference { stored("books.bookSort", fields: fields, fallback: "title") }

    func setBookSort(_ value: SortPreference) { defaults.set(value.encoded, forKey: "books.bookSort") }

    private func stored(_ key: String, fields: [String], fallback: String) -> SortPreference {
        let value = SortPreference.decode(defaults.string(forKey: key), fallback: fallback)
        return fields.contains(value.field) ? value : SortPreference.forField(fields.contains(fallback) ? fallback : fields.first ?? "title")
    }

    // MARK: Plumbing

    private var listsKey: String { "books.lists." + identity }

    private static func trimmed(_ address: String) -> String {
        var text = Substring(address)
        while text.hasSuffix("/") { text = text.dropLast() }
        return String(text)
    }

    private static func digest(_ text: String) -> String {
        SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}
