import Foundation

/// One line of a reader's settings sheet as a controller walks it (#25;
/// Android walks its sheets with the D-pad): a single row, a few choices side
/// by side, or a value that left and right change.
public enum SheetLine: Equatable, Sendable {
    case row
    case choices(Int)
    case value

    /// How many places across it has.
    public var width: Int {
        if case .choices(let count) = self { return max(count, 1) }
        return 1
    }
}

/// Where a controller's ring is in a settings sheet: up and down go from line
/// to line, keeping across where the next line has room; left and right go
/// across a line's choices, or change a value line by a step. Ⓐ presses what
/// the ring is on, and Ⓑ closes the sheet.
public struct SheetWalk: Equatable, Sendable {
    public var line: Int
    public var column: Int

    public init(line: Int = 0, column: Int = 0) {
        self.line = line
        self.column = column
    }

    public enum Step: Equatable, Sendable {
        /// The ring moved here.
        case moved(SheetWalk)
        /// Left (-1) or right (1) on a value line: change it.
        case adjust(Int)
        /// Nothing that way.
        case stay
    }

    /// The ring kept inside `lines`, as the lines change under it (another tab, a narrower window).
    public func clamped(to lines: [SheetLine]) -> SheetWalk {
        guard !lines.isEmpty else { return SheetWalk() }
        let line = min(max(self.line, 0), lines.count - 1)
        return SheetWalk(line: line, column: min(max(column, 0), lines[line].width - 1))
    }

    public static func step(_ walk: SheetWalk, _ direction: PadDirection, lines: [SheetLine]) -> Step {
        guard !lines.isEmpty else { return .stay }
        var at = walk.clamped(to: lines)
        switch direction {
        case .up, .down:
            let next = at.line + (direction == .down ? 1 : -1)
            guard lines.indices.contains(next) else { return at == walk ? .stay : .moved(at) }
            at.line = next
            at.column = min(at.column, lines[next].width - 1)
            return .moved(at)
        case .left, .right:
            let delta = direction == .right ? 1 : -1
            switch lines[at.line] {
            case .value: return .adjust(delta)
            case .row: return at == walk ? .stay : .moved(at)
            case .choices(let count):
                let next = at.column + delta
                guard next >= 0, next < count else { return at == walk ? .stay : .moved(at) }
                at.column = next
                return .moved(at)
            }
        }
    }

    /// `value` moved by `steps` of `step` and kept in `range`, on the step's grid
    /// (as the sliders keep it).
    public static func nudge(_ value: Double, steps: Int, step: Double = 0.05, in range: ClosedRange<Double>) -> Double {
        // Rounded to a millionth, so 19 steps of 0.05 are 0.95 and not 0.9500000000000001.
        let moved = (((value / step).rounded() + Double(steps)) * step * 1_000_000).rounded() / 1_000_000
        return min(max(moved, range.lowerBound), range.upperBound)
    }
}

// MARK: The Comfort lines, in every reader

/// Comfort's lines (#37): brightness and warmth as values, and for a book the
/// black page and the screen kept on while narrating as rows.
public enum ComfortLine: Hashable, Sendable {
    case brightness, warmth, blackPage, awake

    public static func lines(book: Bool) -> [ComfortLine] {
        book ? [.brightness, .warmth, .blackPage, .awake] : [.brightness, .warmth]
    }

    public var shape: SheetLine { self == .brightness || self == .warmth ? .value : .row }

    /// Ⓐ on a row: the black page or the screen kept on, the other way.
    public func press(_ comfort: ScreenComfort) -> ScreenComfort {
        var next = comfort
        switch self {
        case .blackPage: next.blackPage.toggle()
        case .awake: next.awakeWhileNarrating.toggle()
        case .brightness, .warmth: break
        }
        return next
    }

    /// Left or right on a value: five per cent, as the slider steps.
    public func adjust(_ comfort: ScreenComfort, by delta: Int) -> ScreenComfort {
        var next = comfort
        switch self {
        case .brightness:
            next.brightness = SheetWalk.nudge(comfort.brightness, steps: delta, in: ScreenComfort.minBrightness...1)
        case .warmth:
            next.warmth = SheetWalk.nudge(comfort.warmth, steps: delta, in: 0...1)
        case .blackPage, .awake: break
        }
        return next
    }
}

// MARK: The comic reader's Reading options

/// The comic reader's Reading options, top to bottom (`ComicReaderSheetView`):
/// the fits, Trim margins, the three directions, every series, and on a
/// narrow window the issue's own rows; then Comfort.
public enum ComicDisplayLine: Hashable, Sendable {
    case fit(ComicFit)
    case trim
    /// nil: as the library reads.
    case direction(String?)
    case everySeries
    case previousIssue, nextIssue, keys
    case comfort(ComfortLine)

    public static func lines(narrow: Bool) -> [ComicDisplayLine] {
        var lines: [ComicDisplayLine] = ComicFit.allCases.map { .fit($0) }
        lines += [.trim, .direction(nil), .direction("ltr"), .direction("rtl"), .everySeries]
        if narrow { lines += [.previousIssue, .nextIssue, .keys] }
        return lines + ComfortLine.lines(book: false).map { .comfort($0) }
    }

    public var shape: SheetLine {
        if case .comfort(let line) = self { return line.shape }
        return .row
    }
}

// MARK: The ebook reader's Appearance

/// Appearance's tabs (`BookAppearanceSheet`).
public enum BookAppearancePage: Int, CaseIterable, Sendable {
    case font, layout, themes, comfort
}

/// Appearance's lines on each tab, the tabs themselves first: what each
/// holds, and what Ⓐ or left and right do to the book's preferences.
public enum BookAppearanceLine: Hashable, Sendable {
    case tabs
    case typeface, size, onePage
    case columns, margins, spacing, automaticColumns, scroll, publisher, justified, hyphenation
    /// Reset text style (#42): the defaults' typography in one press.
    case resetTextStyle
    /// Page info (#42): the corners' clock and percentage, and what the bottom left says.
    case clock, percentage, place(PageInfoPlace)
    /// The page colours in rows of two.
    case themes(Int)
    case systemColours
    case comfort(ComfortLine)

    /// The page colours, two to a row, as the tab shows them.
    public static var themeRows: [[(theme: EpubTheme, label: String)]] {
        stride(from: 0, to: EpubAppearance.themes.count, by: 2).map {
            Array(EpubAppearance.themes[$0..<min($0 + 2, EpubAppearance.themes.count)])
        }
    }

    public static func lines(_ page: BookAppearancePage) -> [BookAppearanceLine] {
        let body: [BookAppearanceLine]
        switch page {
        case .font: body = [.typeface, .size, .onePage]
        case .layout:
            body = [.columns, .margins, .spacing, .automaticColumns, .scroll, .publisher, .justified, .hyphenation,
                    .resetTextStyle, .clock, .percentage] + PageInfoPlace.allCases.map { .place($0) }
        case .themes: body = themeRows.indices.map { .themes($0) } + [.systemColours]
        case .comfort: body = ComfortLine.lines(book: true).map { .comfort($0) }
        }
        return [.tabs] + body
    }

    public var shape: SheetLine {
        switch self {
        case .tabs: .choices(BookAppearancePage.allCases.count)
        case .typeface: .choices(EpubAppearance.typefaces.count)
        case .columns: .choices(2)
        case .margins: .choices(EpubAppearance.margins.count)
        case .spacing: .choices(EpubAppearance.spacing.count)
        case .themes(let row): .choices(Self.themeRows.indices.contains(row) ? Self.themeRows[row].count : 1)
        case .size: .value
        case .comfort(let line): line.shape
        case .onePage, .automaticColumns, .scroll, .publisher, .justified, .hyphenation, .resetTextStyle,
             .systemColours, .clock, .percentage, .place: .row
        }
    }

    /// The preferences after Ⓐ on `column` of this line; nil for a line that
    /// is not the book's preferences (the tabs, Comfort, a value).
    public func press(_ value: EpubReaderPreferences, column: Int = 0) -> EpubReaderPreferences? {
        switch self {
        case .typeface:
            guard EpubAppearance.typefaces.indices.contains(column) else { return nil }
            return EpubAppearance.typeface(value, EpubAppearance.typefaces[column].id)
        case .onePage:
            return EpubLayoutPolicy.selectOnePage(value, !value.onePagePerScreen)
        case .columns:
            return EpubLayoutPolicy.selectColumns(value, column == 0 ? .one : .two)
        case .margins:
            guard EpubAppearance.margins.indices.contains(column) else { return nil }
            var next = value
            next.pageMargins = EpubAppearance.margins[column].amount
            return next
        case .spacing:
            guard EpubAppearance.spacing.indices.contains(column) else { return nil }
            return EpubAppearance.lineSpacing(value, EpubAppearance.spacing[column].amount)
        case .automaticColumns:
            return EpubLayoutPolicy.selectColumns(value, .auto)
        case .scroll:
            return EpubLayoutPolicy.selectScroll(value, !value.scroll)
        case .publisher:
            var next = value
            next.publisherStyles.toggle()
            return next
        case .justified:
            return EpubAppearance.justified(value)
        case .hyphenation:
            return EpubAppearance.hyphenated(value)
        case .resetTextStyle:
            return EpubAppearance.resetTextStyle(value)
        case .themes(let row):
            guard Self.themeRows.indices.contains(row), Self.themeRows[row].indices.contains(column) else { return nil }
            var next = value
            next.theme = Self.themeRows[row][column].theme
            return next
        case .systemColours:
            var next = value
            next.theme = .system
            return next
        case .tabs, .size, .comfort, .clock, .percentage, .place:
            return nil
        }
    }

    /// The corners after Ⓐ on this line (#42); nil for a line that is not Page info.
    public func press(_ info: PageInfoPreferences) -> PageInfoPreferences? {
        var next = info
        switch self {
        case .clock: next.clock.toggle()
        case .percentage: next.percentage.toggle()
        case .place(let place): next.place = place
        default: return nil
        }
        return next
    }

    /// Left or right on the font size: a size smaller or larger.
    public func adjust(_ value: EpubReaderPreferences, by delta: Int) -> EpubReaderPreferences? {
        self == .size ? EpubAppearance.fontSize(value, steps: delta) : nil
    }
}
