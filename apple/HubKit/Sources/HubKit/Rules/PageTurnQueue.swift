import Foundation

/// The turns that are not a finger's, made one at a time in the order they
/// were asked for (#64), and the rule that says what a swipe is.
///
/// A key, a tap in the margin and the read-along voice each ask the reader for
/// "the next page". However fast they come, every one is made: they wait here
/// and go through Readium's own turns in order, animated when nothing waits
/// behind and at once while more do, so a burst keeps up with the hand.
///
/// A finger is not queued. The page view follows it and is never switched
/// off; `PageIntent` keeps the account of where its swipes are heading, and
/// this type's `swipe` is the one rule for whether a drag was a turn
/// (a flick, or a drag of enough of the page's width). A swipe that goes past
/// an end of a part is the only finger's turn that reaches the queue, because
/// Readium's outer paging view makes that one.
public struct PageTurnQueue: Equatable, Sendable {
    /// A turn: on or back in the book (the keys, the margins, the voice), or
    /// the page to the left or right on screen (a swipe), whichever way the book reads.
    public enum Turn: Equatable, Sendable {
        case forward, backward, left, right
    }

    public private(set) var pending: [Turn] = []

    public init() {}

    public mutating func add(_ turn: Turn) {
        pending.append(turn)
    }

    public var isEmpty: Bool { pending.isEmpty }

    /// The next turn to make, animated only when none waits behind it.
    public mutating func next() -> (turn: Turn, animated: Bool)? {
        guard !pending.isEmpty else { return nil }
        let turn = pending.removeFirst()
        return (turn, pending.isEmpty)
    }

    /// Fastest a flick is taken as a turn, in points a second, as a paging scroll view takes one.
    public static let flickSpeed = 250.0
    /// The share of the page's width a slow drag must cover to turn.
    public static let dragShare = 0.35

    /// What a horizontal swipe asks for: a drag of `dx` (and `dy`) points
    /// ending at `vx` points a second across a page `width` wide. A finger
    /// going left brings the page on the right. Nil for a drag that turns
    /// nothing: too short and too slow, or more up and down than across.
    public static func swipe(dx: Double, dy: Double, vx: Double, width: Double) -> Turn? {
        guard abs(dx) > abs(dy), dx != 0 else { return nil }
        if abs(vx) >= flickSpeed, (vx < 0) == (dx < 0) { return vx < 0 ? .right : .left }
        guard width > 0, abs(dx) >= width * dragShare else { return nil }
        return dx < 0 ? .right : .left
    }
}
