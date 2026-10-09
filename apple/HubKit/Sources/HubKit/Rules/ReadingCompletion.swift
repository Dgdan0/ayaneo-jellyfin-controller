import Foundation

/// A book marked read or unread by hand (#37; Android's
/// `ReadingCompletionState`), per hub and profile. Read shows the book
/// finished everywhere until a reader saves a place in it again; unread
/// after the undo window ("reset") shows it not started, and its next read
/// starts at the beginning. The hub's own progress is never changed.
public struct ReadingCompletionState: Codable, Equatable, Sendable {
    public var states: [String: String]
    public var updatedAt: [String: Int64]

    static let read = "read"
    static let reset = "reset"

    public init(states: [String: String] = [:], updatedAt: [String: Int64] = [:]) {
        self.states = states
        self.updatedAt = updatedAt
    }

    public func markRead(_ id: String, at: Int64 = ReadingListsState.nowMillis()) -> ReadingCompletionState {
        var next = self
        next.states[id] = Self.read
        next.updatedAt[id] = at
        return next
    }

    public func reset(_ id: String, at: Int64 = ReadingListsState.nowMillis()) -> ReadingCompletionState {
        var next = self
        next.states[id] = Self.reset
        next.updatedAt[id] = at
        return next
    }

    /// Forgotten: a reader saved a place, so the hub's progress speaks again.
    public func clear(_ id: String) -> ReadingCompletionState {
        var next = self
        next.states[id] = nil
        next.updatedAt[id] = nil
        return next
    }

    public func isRead(_ id: String) -> Bool { states[id] == Self.read }

    /// Marked unread: the next read starts at the beginning.
    public func startsAtBeginning(_ id: String) -> Bool { states[id] == Self.reset }

    /// Where an ebook opens: the beginning once it was marked unread.
    public func ebookResume(_ id: String, _ resume: ReadingResume) -> ReadingResume {
        startsAtBeginning(id) ? ReadingResume(nil) : resume
    }

    /// Which page a comic opens at: the first once it was marked unread.
    public func pageResume(_ id: String, _ pageIndex: Int) -> Int {
        startsAtBeginning(id) ? 0 : pageIndex
    }

    /// `work` as the person marked it: its progress, its books' in a series,
    /// and no "continue" into a book marked either way.
    public func project(_ work: ReadingWork) -> ReadingWork {
        var projected = work
        projected.sections = work.sections.map { section in
            var section = section
            section.items = section.items.map(project)
            return section
        }
        let status = states[work.id]
        let collection = work.entityType == "collection"
        if let carry = work.continueAt, states[carry.workId] != nil || (!collection && status != nil) {
            projected.continueAt = nil
        }
        if !collection, let status {
            projected.progress = progress(status, work.progress, updatedAt[work.id])
        }
        return projected
    }

    /// A series' book as the person marked it.
    public func project(_ item: ReadingSectionItem) -> ReadingSectionItem {
        guard let status = states[item.workId] else { return item }
        var item = item
        item.progress = progress(status, item.progress, updatedAt[item.workId])
        return item
    }

    private func progress(_ status: String, _ original: ReadingProgress?, _ time: Int64?) -> ReadingProgress {
        let total = original?.total ?? 0
        guard status == Self.read else { return ReadingProgress(percentage: 0, completed: false, current: 0, total: total) }
        let stamp = time.map { ISO8601DateFormatter().string(from: Date(timeIntervalSince1970: Double($0) / 1_000)) } ?? ""
        return ReadingProgress(percentage: 1, completed: true, current: total, total: total, updatedAt: stamp)
    }

    public func encoded() -> Data { (try? JSONEncoder().encode(self)) ?? Data() }

    public static func decode(_ data: Data?) -> ReadingCompletionState {
        guard let data, let state = try? JSONDecoder().decode(ReadingCompletionState.self, from: data) else {
            return ReadingCompletionState()
        }
        return state
    }

    /// What marking a book says.
    public func notice(_ id: String) -> String {
        if isRead(id) { return "Marked as read" }
        if startsAtBeginning(id) { return "Marked unread · next read starts at the beginning" }
        return "Previous reading position restored"
    }
}

/// The undo window of a book's page: unread straight after read gives back
/// exactly what was there before; once the page has been left, unread starts
/// the book again. The window ends when the page goes, a reader opening
/// included.
public struct ReadingCompletionSession: Sendable {
    private var previous: [String: (state: String?, at: Int64?)] = [:]

    public init() {}

    public mutating func markRead(_ state: ReadingCompletionState, _ id: String) -> ReadingCompletionState {
        if previous[id] == nil { previous[id] = (state.states[id], state.updatedAt[id]) }
        return state.markRead(id)
    }

    /// Mark unread: back to what was there before this visit marked the book
    /// read, else the finish is simply gone. It is not a reset any more (#60):
    /// the place stays, and only Start over, which the hub does for every
    /// device, takes a place away.
    public mutating func unmark(_ state: ReadingCompletionState, _ id: String) -> ReadingCompletionState {
        guard let (prior, time) = previous.removeValue(forKey: id) else { return state.clear(id) }
        guard let prior else { return state.clear(id) }
        var next = state
        next.states[id] = prior
        next.updatedAt[id] = time
        return next
    }

    public mutating func leave() { previous.removeAll() }
}
