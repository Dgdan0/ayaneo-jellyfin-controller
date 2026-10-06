import Foundation

// What the comic reader keeps decoded, the Pages grid's arithmetic and the
// paper round a page (#16, C3 to C5): Android's `PageSlots`, `PageGrid` and
// `PageBounds`, with their test cases.

/// A page of one publication: what one of the reader's slots holds.
public struct PageKey: Hashable, Sendable {
    public var publication: String
    public var page: Int

    public init(_ publication: String, _ page: Int) {
        self.publication = publication
        self.page = page
    }
}

/// Which pages the comic reader keeps decoded, and in which slot (#16, C3).
///
/// A turn used to show "Loading page N" over black until the next page
/// decoded. Now the page you are on, the next one the way you are reading and
/// the one before stay decoded side by side, so a turn either way swaps to a
/// page already drawn, and a jump keeps the page you were on until the new one
/// is ready. Pure, so the choices are pinned by tests.
public enum PageSlots {
    /// Slots: the page, the next and the one before.
    public static let count = 3

    /// The pages worth holding round `current`, most wanted first: it, the next
    /// one the way you are going, then the other way.
    public static func wanted(current: Int, pageCount: Int, forward: Bool = true, count: Int = count) -> [Int] {
        let order = forward ? [current, current + 1, current - 1] : [current, current - 1, current + 1]
        var result: [Int] = []
        for page in order where (0..<pageCount).contains(page) && !result.contains(page) {
            result.append(page)
        }
        return Array(result.prefix(count))
    }

    /// What each slot should hold next: one already holding a wanted page
    /// keeps it, so nothing decoded is thrown away; the others take the wanted
    /// pages left, most wanted first. Nil: wanted for nothing, free to reuse.
    public static func assign(held: [PageKey?], wanted: [PageKey]) -> [PageKey?] {
        var kept = Set<PageKey>()
        var result: [PageKey?] = held.map { key in
            guard let key, wanted.contains(key), kept.insert(key).inserted else { return nil }
            return key
        }
        var missing = wanted.filter { !kept.contains($0) }.makeIterator()
        for index in result.indices where result[index] == nil {
            guard let next = missing.next() else { break }
            result[index] = next
        }
        return result
    }
}

/// The Pages grid and the scrubber's preview (#16, C4), the arithmetic only:
/// how wide a thumbnail to ask the hub for, how many columns fit, and where
/// the cursor goes.
public enum PageGrid {
    /// One width for the scrubber's preview and the grid's cells, so a page
    /// the preview fetched is already in the cache for the grid.
    public static let thumbWidth = 240
    /// A cell, thumbnail and gap: seven across the Pocket's 853dp.
    public static let cell = 112.0
    /// A page's shape until its thumbnail says otherwise: a comic page's 2:3.
    public static let thumbAspect = 1.5

    public static func columns(width: Double) -> Int {
        min(9, max(3, Int(width / cell)))
    }

    /// The cell `direction` reaches from `index` in a grid of `columns` and
    /// `count` cells: left and right run on across rows, up and down keep the
    /// column, and the edges hold.
    public static func move(_ index: Int, _ direction: PadDirection, columns: Int, count: Int) -> Int {
        guard count > 0 else { return 0 }
        let from = min(max(index, 0), count - 1)
        let to: Int = switch direction {
        case .left: from - 1
        case .right: from + 1
        case .up: from - columns >= 0 ? from - columns : from
        case .down:
            if from + columns <= count - 1 {
                from + columns
            } else if from / columns < (count - 1) / columns {
                count - 1
            } else {
                from
            }
        }
        return min(max(to, 0), count - 1)
    }

    /// L2 and R2: a screenful of `rows` rows up or down, keeping the column.
    public static func page(_ index: Int, delta: Int, columns: Int, rows: Int, count: Int) -> Int {
        guard count > 0 else { return 0 }
        let target = index + delta * columns * max(1, rows)
        if target < 0 { return index % columns }
        if target > count - 1 { return count - 1 }
        return target
    }

    /// The grid's own key row: it covers the reader's while it is open.
    public static let hints: [ReaderHint] = [
        ReaderHint(ReaderPadMap.a, "Open page", .activate),
        ReaderHint(ReaderPadMap.b, "Close", .back),
        ReaderHint(ReaderPadMap.l2, "Earlier pages", .page(.up)),
        ReaderHint(ReaderPadMap.r2, "Later pages", .page(.down)),
    ]
}

/// Where a comic page's content sits inside its paper, as fractions of the
/// page (#18, C5): 0 to 1 across from the left, 0 to 1 down from the top.
public struct PageContent: Equatable, Sendable {
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

    public static let whole = PageContent(left: 0, top: 0, right: 1, bottom: 1)

    public var width: Double { right - left }
    public var height: Double { bottom - top }
    public var trimmed: Bool { left > 0 || top > 0 || right < 1 || bottom < 1 }

    /// `step` (a fraction of the content) as a fraction of the whole page: for the page's map.
    public func onPage(_ step: NormalizedViewport) -> NormalizedViewport {
        NormalizedViewport(left: left + step.left * width, top: top + step.top * height,
                           right: left + step.right * width, bottom: top + step.bottom * height)
    }
}

/// Finds the plain paper border round a comic page (#18, C5), on the hub's
/// small thumbnail of it (`thumbWidth` pixels across, never the scan), so the
/// reader can treat the content as the page: fitted and read in steps without
/// the margin, which is most of a page's white on these scans.
///
/// Each side on its own: its outermost line must be one colour, and a pale
/// one (paper, from white to yellowed) or a near-black one (a manga's frame);
/// then line after line inward while nearly all of a line stays within
/// `tolerance` of it. Art running to an edge stops that side at once, a flat
/// sky of colour included, a panel's border or a caption stops it where it
/// starts, and a page that is all paper is left whole. One thumbnail pixel
/// goes back on each side trimmed, since scaling down blurs the content's
/// edge into the paper. However much is found, no more than `maxTrim` of the
/// page goes on either axis, so a wide margin on purpose cannot blow the page up.
public enum PageBounds {
    /// How wide the thumbnail asked for is: the hub's thumbnail route, 96 pixels across.
    public static let thumbWidth = 96
    /// Never more than this of the page across, nor down.
    public static let maxTrim = 0.12
    /// Luma levels from the paper's that still count as paper: JPEG noise and paper grain.
    public static let tolerance = 24
    /// How much of a line must be paper: a few stray pixels (a page number's dot) do not end the margin.
    public static let coverage = 0.96
    /// Less than this on an axis is noise, not a margin.
    public static let minTrim = 0.012
    /// A border is paper at least this light, or a frame at most `darkestFrame`; anything between is art.
    public static let palestArt = 185
    public static let darkestFrame = 60

    /// Rec. 601 luma of an ARGB colour, 0 to 255.
    public static func luma(_ argb: UInt32) -> Int {
        (Int(argb >> 16 & 0xFF) * 299 + Int(argb >> 8 & 0xFF) * 587 + Int(argb & 0xFF) * 114) / 1000
    }

    /// The content inside the border of a `width` by `height` thumbnail whose
    /// luma values, row by row, are `luma`. `.whole` when there is no border to trim.
    public static func detect(_ luma: [Int], width: Int, height: Int) -> PageContent {
        guard width >= 8, height >= 8, luma.count >= width * height else { return .whole }
        func row(_ y: Int) -> [Int] { Array(luma[(y * width)..<(y * width + width)]) }
        func column(_ x: Int, _ from: Int, _ to: Int) -> [Int] { (from..<to).map { luma[$0 * width + x] } }

        var top = margin(height) { row($0) }
        var bottom = margin(height) { row(height - 1 - $0) }
        if top + bottom >= height { return .whole }
        // The sides only over the rows left, so a dark band across the top does not hide a white side.
        let firstRow = top
        let endRow = height - bottom
        var left = margin(width) { column($0, firstRow, endRow) }
        var right = margin(width) { column(width - 1 - $0, firstRow, endRow) }
        if left + right >= width { return .whole }
        top = max(0, top - 1)
        bottom = max(0, bottom - 1)
        left = max(0, left - 1)
        right = max(0, right - 1)
        let (x0, x1) = axis(Double(left) / Double(width), Double(right) / Double(width))
        let (y0, y1) = axis(Double(top) / Double(height), Double(bottom) / Double(height))
        return PageContent(left: x0, top: y0, right: 1 - x1, bottom: 1 - y1)
    }

    /// How much bigger the content reads than the page, at a fit to the width
    /// (`fitWidth`) or of the whole page into a `viewWidth` by `viewHeight`
    /// view: what trimming gains.
    public static func gain(_ content: PageContent, pageWidth: Double, pageHeight: Double, viewWidth: Double,
                            viewHeight: Double, fitWidth: Bool) -> Double {
        guard pageWidth > 0, pageHeight > 0, viewWidth > 0, viewHeight > 0, content.width > 0, content.height > 0
        else { return 1 }
        if fitWidth { return 1 / content.width }
        let whole = min(viewWidth / pageWidth, viewHeight / pageHeight)
        let trimmed = min(viewWidth / (pageWidth * content.width), viewHeight / (pageHeight * content.height))
        return trimmed / whole
    }

    /// How many lines from one edge are border: none unless the outermost line is a single colour.
    private static func margin(_ count: Int, _ line: (Int) -> [Int]) -> Int {
        let first = line(0)
        let paper = median(first)
        if (darkestFrame + 1..<palestArt).contains(paper) || !isPaper(first, paper) { return 0 }
        var n = 1
        while n < count && isPaper(line(n), paper) { n += 1 }
        return n
    }

    private static func isPaper(_ values: [Int], _ paper: Int) -> Bool {
        Double(values.count(where: { abs($0 - paper) <= tolerance })) >= Double(values.count) * coverage
    }

    private static func median(_ values: [Int]) -> Int { values.sorted()[values.count / 2] }

    /// Two sides' trims on one axis: noise dropped, and at most `maxTrim` between them, shared as found.
    private static func axis(_ first: Double, _ second: Double) -> (Double, Double) {
        let total = first + second
        if total < minTrim { return (0, 0) }
        if total <= maxTrim { return (first, second) }
        let scale = maxTrim / total
        return (first * scale, second * scale)
    }
}
