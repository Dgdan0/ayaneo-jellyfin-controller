import Foundation

/// The page a reader's swipes are turning to, kept by the reader and not read
/// back from the scroll view that is still sliding there (#64, fluid).
///
/// A page that follows the finger and slides on after it lets go is a scroll
/// view in motion. Asked "which page is this?" while it moves, it answers with
/// wherever it has got to, so a swipe that lands on a slide still under way
/// would be counted from the page behind the one the last swipe was going to,
/// and a quick burst of swipes lost turns. The account is therefore kept
/// here, in pages of the part on screen: where the last swipe was heading
/// (`intended`), and so, when a finger lands on a moving page, the `base` its
/// own swipe is counted from, and the page it ends on from that base and what
/// the finger did (`release`): one page on or back when it was a swipe
/// (`PageTurnQueue.swipe`'s thresholds), the base again when it was not, so a
/// drag too short to turn goes back to the page the page was heading to, not
/// to whichever is nearest.
///
/// Pages are counted as the scroll view's offset counts them, whichever way
/// the book reads: a finger going left brings the page with the higher offset
/// (a book read from the right has its pages at offsets -n...0). A part (a chapter file) is told apart by an opaque number, since a swipe on
/// the last page of one moves Readium's outer paging view to the next part and
/// its pages are another account.
public struct PageIntent: Equatable, Sendable {
    /// What a swipe comes to.
    public enum Outcome: Equatable, Sendable {
        /// Ends on this page of the part on screen.
        case page(Int)
        /// Goes past an end of the part, to the page view of the next one
        /// (`.right`, the page on the right) or the one before (`.left`):
        /// Readium's paging between parts makes that turn.
        case leaves(PageTurnQueue.Turn)
    }

    /// The part the account is of, and the page that part was last heading to.
    public private(set) var part: Int?
    public private(set) var page = 0

    public init() {}

    /// The page a swipe counts from, as a finger lands on `part` showing
    /// `shown` (rounded from its offset). While something is `moving` and the
    /// account is of this part, it is the page the last swipe was heading to;
    /// at rest, or in another part, it is the page shown.
    public func base(part: Int, shown: Int, moving: Bool) -> Int {
        moving && self.part == part ? page : shown
    }

    /// The page a finger that lands on a page `base` of the part's `pages`
    /// and lifts having moved `dx`, `dy` points at `vx` points a second across
    /// a page `width` wide ends on; the account follows it. The account is
    /// forgotten when the swipe leaves the part, which the next part's takes over.
    public mutating func release(part: Int, base: Int, pages: ClosedRange<Int>, dx: Double, dy: Double, vx: Double, width: Double) -> Outcome {
        let base = min(max(base, pages.lowerBound), pages.upperBound)
        let swipe = PageTurnQueue.swipe(dx: dx, dy: dy, vx: vx, width: width)
        var target = base
        switch swipe {
        case .right: target = base + 1
        case .left: target = base - 1
        default: break
        }
        guard pages.contains(target) else {
            self.part = nil
            return .leaves(swipe ?? .right)
        }
        self.part = part
        page = target
        return .page(target)
    }

    /// A turn that was not a finger's (a key, a margin tap, the voice) has
    /// left the part showing `page`: the account is that page.
    public mutating func rest(part: Int, page: Int) {
        self.part = part
        self.page = page
    }

    /// Nothing is known of where the page is going: the page shown counts.
    public mutating func forget() {
        part = nil
    }
}
