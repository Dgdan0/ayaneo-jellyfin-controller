import Foundation

/// The Glass shell's decisions that are not drawing (GLASS_PLAN.md, "Navigation
/// per device").
public enum ShellLayout {
    /// The narrowest window that holds the sections capsule centred between
    /// Media/Books and the icons: 44 of margin each side, Media/Books about
    /// 192 wide, the capsule about 460 and the three icons with the avatar
    /// about 206, with a little room either side of the capsule. Narrower,
    /// the sections move to a tab bar at the bottom, as on the iPhone; an iPad
    /// mini in portrait (744) is closer to a phone than to the 13-inch.
    public static let wideMinimum = 980.0

    /// The top capsule (iPad and Mac) rather than the bottom tab bar.
    public static func isWide(width: Double) -> Bool { width >= wideMinimum }

    /// What the back pill says: the page under the one shown, or the
    /// section's own name when the first page is pushed.
    public static func backTitle(pages: [String], root: String) -> String {
        pages.count >= 2 ? pages[pages.count - 2] : root
    }
}

/// Which artwork the Glass page shows (GLASS_PLAN.md, "Pages report their
/// artwork"). Each page with artwork registers it under its stack, a section's
/// navigation stack, while it is on screen.
///
/// The newest registration in the stack being shown wins, so a pushed title
/// page covers Home, and Home is back when the title is popped. A page that
/// changes its artwork keeps its place, so Home changing its hero underneath a
/// title page does not show through. Empty artwork (a page still loading, or a
/// title without any) does not count, and a stack with nothing keeps what was
/// last shown, so the page never drops to plain dark between two pictures.
public struct AmbientStack: Equatable, Sendable {
    public struct Entry: Equatable, Sendable {
        public let token: UUID
        public let stack: String
        public var path: String
    }

    /// Oldest first.
    public private(set) var entries: [Entry] = []
    /// The stack on screen.
    public private(set) var current = ""
    /// The artwork shown last, kept while a page without any is on screen.
    public private(set) var lastShown = ""

    public init() {}

    /// What the page shows now.
    public var displayed: String {
        let own = artwork(stack: current)
        return own.isEmpty ? lastShown : own
    }

    /// The newest non-empty artwork registered under `stack`.
    public func artwork(stack: String) -> String {
        entries.last { $0.stack == stack && !$0.path.isEmpty }?.path ?? ""
    }

    public mutating func show(_ path: String, token: UUID, stack: String) {
        if let index = entries.firstIndex(where: { $0.token == token }) {
            entries[index].path = path
        } else {
            entries.append(Entry(token: token, stack: stack, path: path))
        }
        remember()
    }

    public mutating func remove(token: UUID) {
        entries.removeAll { $0.token == token }
        remember()
    }

    public mutating func select(stack: String) {
        current = stack
        remember()
    }

    private mutating func remember() {
        let own = artwork(stack: current)
        if !own.isEmpty { lastShown = own }
    }
}
