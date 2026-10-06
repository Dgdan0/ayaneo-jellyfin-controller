import Foundation

// Reading a book with the keys and the sticks, and the page making room for
// the menu (#25, phase 4): Android's `reader/BookScroll.kt` (#18, E1) and
// `reader/ReaderPagePreview.kt` (#16, X7), held to their test cases in
// `BookReaderRulesTests`.

/// Reading a scrolling book with the D-pad, the arrow keys and the right
/// stick, in the whole points a web view scrolls by.
///
/// The right stick asks for a little each frame: at a gentle push that is
/// less than a point, which rounded down to nothing, so the text stood still
/// until the stick was pushed hard. The part of a point left over is carried
/// into the next frame instead.
///
/// Readium scrolls each part of a book (each of its files) in a web view of
/// its own, so scrolling stopped dead at the foot of a chapter. Scrolling on
/// goes into the next part: the D-pad at once, the stick once it has pushed
/// on past the end by `edgePx`, so a glide that only reaches the end does not
/// tip into the next chapter. Past the top goes back a part.
public struct BookScroll: Sendable {
    public enum Move: Equatable, Sendable {
        /// Scroll the part on screen by this much, down when positive.
        case by(Int)
        case nextPart
        case previousPart
        case stay
    }

    private let edgePx: Double
    private var carry = 0.0
    private var pushed = 0.0

    public init(edgePx: Double) {
        self.edgePx = edgePx
    }

    /// The right stick: `px` this frame, down when positive. `canDown` and
    /// `canUp` say whether the part on screen can scroll that way.
    public mutating func glide(_ px: Double, canDown: Bool, canUp: Bool) -> Move {
        if px == 0 || px.isNaN { return .stay }
        let blocked = px > 0 ? !canDown : !canUp
        if blocked {
            carry = 0
            // A push against the end builds up in one direction; turning back starts again.
            pushed = pushed != 0 && (pushed > 0) == (px > 0) ? pushed + px : px
            if abs(pushed) < edgePx { return .stay }
            pushed = 0
            return px > 0 ? .nextPart : .previousPart
        }
        pushed = 0
        let total = carry + px
        let whole = Int(total)
        carry = total - Double(whole)
        return whole == 0 ? .stay : .by(whole)
    }

    /// The D-pad: `px` at once, down when positive; at the end of a part, the part after (or before).
    public mutating func step(_ px: Int, canDown: Bool, canUp: Bool) -> Move {
        reset()
        if px == 0 { return .stay }
        if px > 0 && !canDown { return .nextPart }
        if px < 0 && !canUp { return .previousPart }
        return .by(px)
    }

    /// A new part on screen, or the reader moved some other way: nothing carried, no push built up.
    public mutating func reset() {
        carry = 0
        pushed = 0
    }

    /// The D-pad's step: a third of the screen, eased over `stepMs` rather than jumped.
    public static let stepShare = 1.0 / 3
    public static let stepMs = 160
    /// The stick at full tilt: a screen and a half a second.
    public static let glideScreensPerSecond = 1.5
    /// How far past the end the stick must push before the next part: half a screen.
    public static let edgeScreens = 0.5
    /// Space reads on by a screen, keeping a line of the last in sight.
    public static let screenShare = 0.9
}

/// Where the page goes while the menu is open (the owner's choice for books,
/// 2026-10-05): scaled down and moved, never laid out again, so the book's
/// pagination and the reader's measured window stay as they were.
public struct ReaderPageTransform: Equatable, Sendable {
    public let scale: Double
    public let x: Double
    public let y: Double

    public init(scale: Double, x: Double, y: Double) {
        self.scale = scale
        self.x = x
        self.y = y
    }

    public static let identity = ReaderPageTransform(scale: 1, x: 0, y: 0)
}

/// Fits the page, as it is already drawn, between the menu's bars and beside
/// an open sheet (Android's `ReaderPagePreview`).
public enum ReaderPagePreview {
    public static func fit(width: Double, height: Double, top: Double = 0, bottom: Double = 0, right: Double = 0,
                           margin: Double = 0) -> ReaderPageTransform {
        guard width > 0, height > 0 else { return .identity }
        let left = min(max(margin, 0), width - 1)
        let upper = min(max(top + margin, 0), height - 1)
        let availableWidth = min(max(width - right - margin - left.rounded(.towardZero), 1), width)
        let availableHeight = min(max(height - bottom - margin - upper.rounded(.towardZero), 1), height)
        let scale = min(1, availableWidth / width, availableHeight / height)
        return ReaderPageTransform(
            scale: scale,
            x: min(left + (availableWidth - width * scale) / 2, width - width * scale),
            y: min(upper + (availableHeight - height * scale) / 2, height - height * scale))
    }
}
