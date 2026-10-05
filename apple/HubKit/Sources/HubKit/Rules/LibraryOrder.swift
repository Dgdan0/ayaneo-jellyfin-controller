import Foundation

/// Arranging libraries (#15). The hub keeps each profile's order and sends the
/// lists in it; the app moves one library at a time, shows the move at once,
/// saves the whole order, and never sorts a list itself. Android's
/// `LibraryOrder`.
public enum LibraryOrder {
    /// `ids` with the one at `from` taken out and put in at `to`, which is
    /// clamped to the list. Out of range `from`, or no move, gives `ids` back.
    public static func move(_ ids: [String], from: Int, to: Int) -> [String] {
        guard ids.indices.contains(from) else { return ids }
        let target = min(max(0, to), ids.count - 1)
        guard target != from else { return ids }
        var out = ids
        let moved = out.remove(at: from)
        out.insert(moved, at: target)
        return out
    }

    /// `ids` with `id` put where `target` is now: dragging one library over
    /// another takes its place, and the libraries between slide along.
    public static func move(_ ids: [String], id: String, over target: String) -> [String] {
        guard let from = ids.firstIndex(of: id), let to = ids.firstIndex(of: target) else { return ids }
        return move(ids, from: from, to: to)
    }

    /// One place earlier (`by: -1`) or later (`by: 1`): VoiceOver's and the
    /// keyboard's way to move, where a finger drags.
    public static func step(_ ids: [String], id: String, by offset: Int) -> [String] {
        guard let from = ids.firstIndex(of: id) else { return ids }
        return move(ids, from: from, to: from + offset)
    }

    /// `items` in the order of `ids`: those not named keep their own order after
    /// the rest. For showing a list as it was arranged before the hub has it.
    public static func inOrder<T>(_ items: [T], ids: [String], id: (T) -> String) -> [T] {
        var rank: [String: Int] = [:]
        for (index, value) in ids.enumerated() where rank[value] == nil { rank[value] = index }
        let named = items.filter { rank[id($0)] != nil }.sorted { rank[id($0)]! < rank[id($1)]! }
        return named + items.filter { rank[id($0)] == nil }
    }

    /// Whether the hub's list follows the profile's own order rather than A to Z.
    public static func isCustom(_ order: String) -> Bool { order == "custom" }

    /// The notice after a save the hub refused, with the order back as it was.
    public static func failureNotice(_ reason: String) -> String {
        let trimmed = reason.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "The new order could not be saved" : "The new order could not be saved · " + trimmed
    }
}

/// One save out at a time, and only the newest order waiting behind it: a
/// second and third move while the first is saving send one order, the third.
/// Android's `LibraryOrderQueue`.
public struct LibraryOrderQueue: Equatable, Sendable {
    public private(set) var sending: [String]?
    public private(set) var waiting: [String]?

    public init() {}

    public var isIdle: Bool { sending == nil && waiting == nil }

    /// The order to send now, or nil when one is already out: then this one
    /// waits, in place of any that was waiting.
    public mutating func submit(_ ids: [String]) -> [String]? {
        if sending == nil {
            sending = ids
            return ids
        }
        waiting = ids
        return nil
    }

    /// The save out has been answered. On success the waiting order, if any,
    /// is the next to send; after a failure nothing more goes, since the page
    /// returns to what the hub has.
    public mutating func answered(ok: Bool) -> [String]? {
        let next = ok ? waiting : nil
        waiting = nil
        sending = next
        return next
    }
}
