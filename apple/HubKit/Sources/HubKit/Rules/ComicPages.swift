import Foundation

// The comic and manga reader's pages (#25, phase 3): Android's
// `reader/PagedImageState.kt`, rule for rule, with its test cases in
// `ComicPagesTests`. Sizes are Doubles here: a page in its pixels, the view in
// points, where Android has both in pixels.

/// Which way a publication reads: Kavita's manga libraries right to left.
public enum PageDirection: String, Sendable {
    case ltr, rtl, topToBottom
}

/// A page's size, as the hub's manifest gives it or as it decoded.
public struct PageDimension: Equatable, Sendable {
    public var index: Int
    public var width: Int
    public var height: Int
    /// A two-page spread scanned as one: wider than tall unless the hub says.
    public var isWide: Bool

    public init(index: Int, width: Int, height: Int, isWide: Bool? = nil) {
        self.index = index
        self.width = width
        self.height = height
        self.isWide = isWide ?? (width > height)
    }
}

/// Pages shown side by side: a left one, a right one, or one alone.
public struct PageSpread: Equatable, Sendable {
    public var leftPage: Int?
    public var rightPage: Int?
    public var direction: PageDirection

    public init(leftPage: Int?, rightPage: Int?, direction: PageDirection) {
        self.leftPage = leftPage
        self.rightPage = rightPage
        self.direction = direction
    }

    /// The pages in the order they are read: right first for manga.
    public var readingOrder: [Int] {
        switch direction {
        case .rtl: [rightPage, leftPage].compactMap { $0 }
        case .ltr, .topToBottom: [leftPage, rightPage].compactMap { $0 }
        }
    }
}

public enum SpreadPlanner {
    /// Builds visual spreads while keeping page zero isolated as a cover and
    /// wide scans isolated so neither page is shrunk beside one.
    public static func plan(_ pages: [PageDimension], direction: PageDirection, coverAlone: Bool = true) -> [PageSpread] {
        guard !pages.isEmpty else { return [] }
        let ordered = pages.sorted { $0.index < $1.index }
        var result: [PageSpread] = []
        var cursor = 0
        if coverAlone {
            result.append(single(ordered[0].index, direction))
            cursor = 1
        }
        while cursor < ordered.count {
            let current = ordered[cursor]
            let next = cursor + 1 < ordered.count ? ordered[cursor + 1] : nil
            guard let next, !current.isWide, !next.isWide else {
                result.append(single(current.index, direction))
                cursor += 1
                continue
            }
            result.append(direction == .rtl
                ? PageSpread(leftPage: next.index, rightPage: current.index, direction: direction)
                : PageSpread(leftPage: current.index, rightPage: next.index, direction: direction))
            cursor += 2
        }
        return result
    }

    private static func single(_ page: Int, _ direction: PageDirection) -> PageSpread {
        direction == .rtl ? PageSpread(leftPage: nil, rightPage: page, direction: direction)
            : PageSpread(leftPage: page, rightPage: nil, direction: direction)
    }
}

/// A part of a page, as fractions of it: 0 to 1 across from the left and
/// down from the top.
public struct NormalizedViewport: Equatable, Sendable {
    public var left: Double
    public var top: Double
    public var right: Double
    public var bottom: Double

    public init(left: Double, top: Double, right: Double, bottom: Double) {
        self.left = left
        self.top = top
        self.right = right
        self.bottom = bottom
    }

    public static let whole = NormalizedViewport(left: 0, top: 0, right: 1, bottom: 1)
}

/// Reading a page in thirds (#16, C2): the page fitted to the view's width and
/// read down it, every step moving on by at most 1 - `overlap` of a screen, so
/// nothing is cropped and each step shows a little of the one before. How many
/// steps comes from the page's shape and the screen's, not a constant three:
/// on a 16:9 screen a comic or manga page takes 3, a two-page spread 2, and a
/// page whose height fits at that width 1. Steps run top to bottom whatever
/// the reading direction.
public enum ViewportStepPlanner {
    public static let overlap = 0.12
    /// A long strip still ends: past this the steps space out rather than multiply.
    public static let maxSteps = 48

    /// How many steps a `pageWidth` by `pageHeight` page takes in a `viewWidth` by `viewHeight` view.
    public static func count(pageWidth: Double, pageHeight: Double, viewWidth: Double, viewHeight: Double,
                             overlap: Double = overlap) -> Int {
        guard pageWidth > 0, pageHeight > 0, viewWidth > 0, viewHeight > 0 else { return 1 }
        let visible = viewHeight * pageWidth / viewWidth
        let ratio = pageHeight / visible
        if ratio <= 1 + 1e-9 { return 1 }
        let steps = (1 + (ratio - 1) / (1 - overlap) - 1e-9).rounded(.up)
        return Int(min(Double(maxSteps), max(2, steps)))
    }

    /// The steps down the page, as fractions of it, first to last.
    public static func fitWidth(pageWidth: Double, pageHeight: Double, viewWidth: Double, viewHeight: Double,
                                overlap: Double = overlap) -> [NormalizedViewport] {
        let steps = count(pageWidth: pageWidth, pageHeight: pageHeight, viewWidth: viewWidth, viewHeight: viewHeight,
                          overlap: overlap)
        if steps == 1 { return [.whole] }
        let visible = min(1, viewHeight * pageWidth / viewWidth / pageHeight)
        return (0..<steps).map { index in
            let top = Double(index) * (1 - visible) / Double(steps - 1)
            return NormalizedViewport(left: 0, top: top, right: 1, bottom: top + visible)
        }
    }
}

/// Source-pixel movement for controller navigation within a comic page. The
/// range moved over is `sourceSize` long from `from`: the whole page, or its
/// content inside the paper border while that is trimmed (#18, C5).
public enum ComicPanPolicy {
    public static func edge(sourceSize: Double, visibleSize: Double, high: Bool, from: Double = 0) -> Double {
        let halfVisible = visibleSize / 2
        if sourceSize <= visibleSize { return from + sourceSize / 2 }
        return from + (high ? sourceSize - halfVisible : halfVisible)
    }

    /// Where the middle of the view moves to, `distance` of a screen in
    /// `direction` (1 or -1), or nil when it cannot move more than a pixel.
    public static func step(center: Double, sourceSize: Double, visibleSize: Double, direction: Int,
                            distance: Double = 0.82, from: Double = 0) -> Double? {
        let sign = direction < 0 ? -1.0 : 1.0
        let halfVisible = visibleSize / 2
        let lower = from + halfVisible
        let upper = from + sourceSize - halfVisible
        if upper <= lower + 1 { return nil }
        let next = min(upper, max(lower, center + sign * visibleSize * distance))
        return abs(next - center) > 1 ? next : nil
    }
}

/// Where in an issue: the page, and the step down it when it is read in steps.
/// Each page takes as many steps as `stepsFor` says (#16, C2: from its shape),
/// so moving back onto a page lands on its last step.
///
/// The reader's pages here are its units: a page, or two side by side on an
/// iPad held sideways (`ComicUnits`).
public struct PagedImageState {
    public let pageCount: Int
    private let stepsFor: (Int) -> Int
    public private(set) var pageIndex: Int
    public private(set) var viewportIndex: Int

    /// A publication needs at least one page; Android refuses none, and here
    /// it is one, so a bad manifest cannot stop the app.
    public init(pageCount: Int, startPage: Int = 0, stepsFor: @escaping (Int) -> Int = { _ in 1 }, startStep: Int = 0) {
        self.pageCount = max(1, pageCount)
        self.stepsFor = stepsFor
        let page = min(max(startPage, 0), self.pageCount - 1)
        pageIndex = page
        viewportIndex = min(max(startStep, 0), max(1, stepsFor(page)) - 1)
    }

    /// Every page the same number of steps.
    public init(pageCount: Int, startPage: Int, viewportSteps: Int) {
        self.init(pageCount: pageCount, startPage: startPage, stepsFor: { _ in viewportSteps })
    }

    /// How many steps the current page takes.
    public var viewportSteps: Int { steps(pageIndex) }

    private func steps(_ page: Int) -> Int { max(1, stepsFor(page)) }

    /// The current page's step count changed (it decoded at another size, a zoom began): stay inside it.
    public mutating func refit() {
        viewportIndex = min(max(viewportIndex, 0), viewportSteps - 1)
    }

    /// A page and a step on it, as a reading list or a saved place asks.
    public mutating func jump(page: Int, step: Int) {
        pageIndex = min(max(page, 0), pageCount - 1)
        viewportIndex = min(max(step, 0), viewportSteps - 1)
    }

    public var progression: Double { pageCount == 1 ? 1 : Double(pageIndex) / Double(pageCount - 1) }
    public var completed: Bool { pageIndex == pageCount - 1 }

    public mutating func seek(_ page: Int) {
        pageIndex = min(max(page, 0), pageCount - 1)
        viewportIndex = 0
    }

    /// X/Y skip all viewport steps, including when the current page is split into thirds.
    @discardableResult
    public mutating func turnPage(_ delta: Int) -> Bool {
        guard delta != 0 else { return false }
        let next = pageIndex + (delta < 0 ? -1 : 1)
        guard (0..<pageCount).contains(next) else { return false }
        pageIndex = next
        viewportIndex = delta > 0 ? 0 : steps(next) - 1
        return true
    }

    @discardableResult
    public mutating func advance() -> Bool {
        if viewportIndex < viewportSteps - 1 {
            viewportIndex += 1
            return true
        }
        guard pageIndex < pageCount - 1 else { return false }
        pageIndex += 1
        viewportIndex = 0
        return true
    }

    @discardableResult
    public mutating func retreat() -> Bool {
        if viewportIndex > 0 {
            viewportIndex -= 1
            return true
        }
        guard pageIndex > 0 else { return false }
        pageIndex -= 1
        viewportIndex = steps(pageIndex) - 1
        return true
    }
}

/// The small map of a page in a corner (#16, C2): a tap on it goes to the
/// step under the finger, or the nearest one.
public enum ComicPageMap {
    /// How long the map and "Part 2 of 3" show after a step or a pan.
    public static let showMillis = 1_400

    /// The step a tap `y` down the page (0 to 1) chooses: the first band
    /// holding it, else the band whose middle is nearest. Nil with fewer than two.
    public static func step(at y: Double, steps: [NormalizedViewport]) -> Int? {
        guard steps.count > 1 else { return nil }
        let point = min(1, max(0, y))
        if let inside = steps.firstIndex(where: { point >= $0.top && point <= $0.bottom }) { return inside }
        return steps.indices.min { abs((steps[$0].top + steps[$0].bottom) / 2 - point) < abs((steps[$1].top + steps[$1].bottom) / 2 - point) }
    }
}
