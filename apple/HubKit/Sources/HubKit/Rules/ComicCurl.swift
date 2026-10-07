import Foundation

/// A comic or manga page that curls like paper on the iPhone and the iPad
/// (#32): where a curl may start, and the leaves `UIPageViewController` turns,
/// pure so the rules are tested here and the view only carries them out.
///
/// A curl starts only from the outer edge of the page, and only at its
/// normal size: a zoomed page pans, and a page read in thirds turns only from
/// the step it turns at. Reading right to left (manga), the next page is on
/// the left, so the curl goes on from the left edge. Two pages side by side
/// turn as a book with the spine between them, each spread's two halves a
/// leaf of their own; one page alone has a back, the page itself mirrored
/// and faded, as Apple Books shows it.
public enum ComicCurl {
    public enum Edge: Equatable, Sendable { case forward, backward }

    /// The outer edge a curl starts from: an eighth of the page's width, at
    /// least 44 points (a finger) and at most 80.
    public static func zoneWidth(_ width: Double) -> Double { min(max(width * 0.125, 44), 80) }

    /// Which outer edge a touch at `x` across a page `width` wide is on; nil
    /// in between, where a touch is the page's own (taps, pans, swipes).
    public static func edge(x: Double, width: Double, rtl: Bool) -> Edge? {
        guard width > 0 else { return nil }
        let zone = zoneWidth(width)
        if x <= zone { return rtl ? .forward : .backward }
        if x >= width - zone { return rtl ? .backward : .forward }
        return nil
    }

    /// Whether a curl may start from `edge`: never on a zoomed page, and on a
    /// page read in thirds only from the last step going on or the first going back.
    public static func allowed(_ edge: Edge, zoomed: Bool, thirds: Bool, step: Int, steps: Int) -> Bool {
        if zoomed { return false }
        guard thirds, steps > 1 else { return true }
        return edge == .forward ? step >= steps - 1 : step <= 0
    }

    /// One leaf of the curl: a unit's front or back (one page alone), or the
    /// first or second half of a spread, in reading order.
    public struct Leaf: Hashable, Sendable {
        public enum Side: Hashable, Sendable { case front, back, first, second }

        public let unit: Int
        public let side: Side

        public init(unit: Int, side: Side) {
            self.unit = unit
            self.side = side
        }
    }

    /// The leaves on show for `unit`: its front alone (its back is asked for
    /// as the page turns; UIKit refuses two at the outer spine), or the
    /// spread's two halves either side of the middle one.
    public static func shown(unit: Int, spreads: Bool) -> [Leaf] {
        spreads ? [Leaf(unit: unit, side: .first), Leaf(unit: unit, side: .second)] : [Leaf(unit: unit, side: .front)]
    }

    /// The leaf after `leaf` in a book of `units` units: a front's back, then
    /// the next unit's front; a spread's second half, then the next spread's first.
    public static func after(_ leaf: Leaf, units: Int) -> Leaf? {
        switch leaf.side {
        case .front: return Leaf(unit: leaf.unit, side: .back)
        case .first: return Leaf(unit: leaf.unit, side: .second)
        case .back: return leaf.unit + 1 < units ? Leaf(unit: leaf.unit + 1, side: .front) : nil
        case .second: return leaf.unit + 1 < units ? Leaf(unit: leaf.unit + 1, side: .first) : nil
        }
    }

    /// The leaf before `leaf`.
    public static func before(_ leaf: Leaf, units: Int) -> Leaf? {
        switch leaf.side {
        case .back: return Leaf(unit: leaf.unit, side: .front)
        case .second: return Leaf(unit: leaf.unit, side: .first)
        case .front: return leaf.unit > 0 && leaf.unit - 1 < units ? Leaf(unit: leaf.unit - 1, side: .back) : nil
        case .first: return leaf.unit > 0 && leaf.unit - 1 < units ? Leaf(unit: leaf.unit - 1, side: .second) : nil
        }
    }

    /// The unit a curl ended on, from the leaves it shows.
    public static func unit(showing leaves: [Leaf]) -> Int? { leaves.first?.unit }

    /// Which part of the unit's picture a leaf draws, across the page: all of
    /// it, or the half its side stands for. A spread read right to left has
    /// its first half on the right.
    public static func span(_ side: Leaf.Side, rtl: Bool) -> ClosedRange<Double> {
        switch side {
        case .front, .back: return 0...1
        case .first: return rtl ? 0.5...1 : 0...0.5
        case .second: return rtl ? 0...0.5 : 0.5...1
        }
    }
}
