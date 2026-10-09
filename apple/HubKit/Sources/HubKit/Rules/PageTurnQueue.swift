import Foundation

/// Every page turn asked for is made, one at a time, however fast they come
/// (#64).
///
/// Readium's pages are a paging scroll view, and a swipe that lands while the
/// last turn still slides is measured from wherever the page has got to, so
/// quick swipes lost turns: ten swipes on and back again (seven on, three
/// back) went one or two pages instead of four. The reader therefore leaves a
/// swipe to the scroll view only while nothing is moving. One that comes
/// during a turn is queued here, with every turn from the keys, the margins
/// and the voice, and the queue makes them in order: animated when nothing
/// waits behind, at once while more do, so a burst keeps up with the hand.
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
