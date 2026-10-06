import Foundation

/// The page an issue is on, sent to Kavita through the hub (#25, phase 3).
///
/// The page shown is wanted; at most one save is on its way; each names the
/// page the hub last had (`expectedPage`), so a page read on another device
/// since is never overwritten. The hub then answers 409, and the issue sends
/// nothing more until it is opened again, where the hub's page is the
/// person's. Opening an issue at the hub's own page sends nothing, so reading
/// writes progress only when the page changes. The place kept on the device
/// and its outbox come with the Books side's checkpoints (`READING_CHECKPOINTS.md`);
/// a comic's page goes through those once both are on one branch.
public struct ComicProgressOutbox: Equatable, Sendable {
    /// The page the hub has: the manifest's, then each save's.
    public private(set) var saved: Int
    /// The page to save next; nil when nothing is waiting.
    public private(set) var wanted: Int?
    /// The save on its way.
    public private(set) var sending: Int?
    /// The hub refused a save: the place moved on another device.
    public private(set) var conflicted = false

    public init(saved: Int) {
        self.saved = max(0, saved)
    }

    /// A page is on screen.
    public mutating func show(_ page: Int) {
        wanted = max(0, page)
    }

    /// Something is waiting to be saved.
    public var pending: Bool { !conflicted && (wanted.map { $0 != saved } ?? false) }

    /// The save to send now: nil while one is on its way, when the page shown
    /// is the one the hub has, and after a conflict.
    public mutating func next() -> ReadingPublicationProgressBody? {
        guard !conflicted, sending == nil, let page = wanted else { return nil }
        guard page != saved else {
            wanted = nil
            return nil
        }
        sending = page
        wanted = nil
        return ReadingPublicationProgressBody(pageIndex: page, expectedPage: saved)
    }

    /// The hub answered the save on its way: saved, refused because the place
    /// moved elsewhere (`conflict`), or not reached, when it waits to go again.
    public mutating func answered(ok: Bool, conflict: Bool = false) {
        guard let page = sending else { return }
        sending = nil
        if ok {
            saved = page
            if wanted == page { wanted = nil }
        } else if conflict {
            conflicted = true
        } else if wanted == nil {
            wanted = page
        }
    }
}
