import Foundation

/// A book this device keeps to read or hear offline (#43): which work, how it
/// is shown, and what of it was kept. #37's caches hold the files and name
/// them by digests, so nothing else says which books they are; the Books
/// side's Downloads lists these.
public struct ReadingKeptBook: Codable, Equatable, Sendable, Identifiable {
    public var workId: String
    public var title: String
    public var artwork: String
    /// What was kept, first kept first: "ebook", "readalong", "audiobook", "comic", "manga".
    public var kinds: [String]
    public var sourceItemIds: [String]
    /// When it was last kept, Unix milliseconds: the newest first.
    public var keptAt: Int64

    public var id: String { workId }

    public init(workId: String, title: String, artwork: String = "", kinds: [String] = [], sourceItemIds: [String] = [],
                keptAt: Int64 = 0) {
        self.workId = workId
        self.title = title
        self.artwork = artwork
        self.kinds = kinds
        self.sourceItemIds = sourceItemIds
        self.keptAt = keptAt
    }

    /// An audiobook alone is drawn square, as its cover is.
    public var isAudiobookOnly: Bool { kinds == ["audiobook"] }

    /// "Ebook · Audiobook", "Read along", "Comic · 3 issues": what is here, in words.
    public var formats: String {
        var words: [String] = []
        for kind in kinds {
            let word: String = switch kind {
            case "ebook": "Ebook"
            case "readalong": "Read along"
            case "audiobook": "Audiobook"
            case "manga": "Manga"
            default: "Comic"
            }
            if !words.contains(word) { words.append(word) }
        }
        let comics = kinds.contains { $0 == "comic" || $0 == "manga" }
        if comics, sourceItemIds.count > 1 { words.append("\(sourceItemIds.count) issues") }
        return words.joined(separator: " · ")
    }

    /// The shelf with this keep added: the work's newest title and artwork,
    /// its kind and edition or issue once each, and the time it was kept.
    public static func record(_ books: [ReadingKeptBook], workId: String, title: String, artwork: String, kind: String,
                              sourceItemId: String, now: Int64) -> [ReadingKeptBook] {
        guard !workId.isEmpty, !sourceItemId.isEmpty else { return books }
        var shelf = books
        var book = shelf.first { $0.workId == workId } ?? ReadingKeptBook(workId: workId, title: title)
        if !title.isEmpty { book.title = title }
        if !artwork.isEmpty { book.artwork = artwork }
        if !kind.isEmpty, !book.kinds.contains(kind) { book.kinds.append(kind) }
        if !book.sourceItemIds.contains(sourceItemId) { book.sourceItemIds.append(sourceItemId) }
        book.keptAt = max(book.keptAt, now)
        shelf.removeAll { $0.workId == workId }
        shelf.append(book)
        return shelf.sorted { $0.keptAt > $1.keptAt }
    }
}

/// The kept books on this device for one hub and profile, a small JSON file
/// beside the caches it describes (#43).
public final class ReadingKeptShelf: @unchecked Sendable {
    public let file: URL
    private let lock = NSLock()

    public init(file: URL) {
        self.file = file
    }

    /// The newest kept first.
    public func list() -> [ReadingKeptBook] {
        lock.lock()
        defer { lock.unlock() }
        return read()
    }

    public func record(workId: String, title: String, artwork: String, kind: String, sourceItemId: String, now: Int64) {
        lock.lock()
        defer { lock.unlock() }
        let before = read()
        let after = ReadingKeptBook.record(before, workId: workId, title: title, artwork: artwork, kind: kind,
                                           sourceItemId: sourceItemId, now: now)
        if after != before { write(after) }
    }

    public func remove(workId: String) {
        lock.lock()
        defer { lock.unlock() }
        let before = read()
        let after = before.filter { $0.workId != workId }
        if after != before { write(after) }
    }

    private func read() -> [ReadingKeptBook] {
        guard let data = try? Data(contentsOf: file),
              let books = try? JSONDecoder().decode([ReadingKeptBook].self, from: data) else { return [] }
        return books.sorted { $0.keptAt > $1.keptAt }
    }

    private func write(_ books: [ReadingKeptBook]) {
        guard let data = try? JSONEncoder().encode(books) else { return }
        try? FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? data.write(to: file, options: .atomic)
    }
}
