import Foundation

// Kindle's corners while reading an ebook (#42): the time at the top right,
// where you are at the bottom left, how far through the book at the bottom
// right. Each can be turned off in Appearance, kept on the device with the
// rest of the reader's look; a tap on the bottom left shows the next way of
// saying where you are, as on Kindle. The words and the order are here; the
// reader brings the pages and the time left it already has.

/// What the bottom left says.
public enum PageInfoPlace: String, CaseIterable, Sendable {
    case pageInBook, pageInChapter, chapterTimeLeft, bookTimeLeft, none

    /// Its name in Appearance.
    public var title: String {
        switch self {
        case .pageInBook: "Page in book"
        case .pageInChapter: "Page in chapter"
        case .chapterTimeLeft: "Time left in chapter"
        case .bookTimeLeft: "Time left in book"
        case .none: "None"
        }
    }

    /// The ways a tap moves through, in order. None is chosen in Appearance
    /// only: a corner tapped to nothing could not be tapped back.
    public static let cycle: [PageInfoPlace] = [.pageInBook, .pageInChapter, .chapterTimeLeft, .bookTimeLeft]

    /// A tap on the corner: the next way in the cycle that can be said now
    /// (`available`), past any that cannot (pages while scrolling); itself
    /// when no other can.
    public func next(available: (PageInfoPlace) -> Bool) -> PageInfoPlace {
        let start = Self.cycle.firstIndex(of: self) ?? -1
        for step in 1...Self.cycle.count {
            let candidate = Self.cycle[(start + step + Self.cycle.count) % Self.cycle.count]
            if candidate != self && available(candidate) { return candidate }
        }
        return self
    }
}

/// Which corners show.
public struct PageInfoPreferences: Equatable, Sendable {
    /// The time, top right.
    public var clock: Bool
    /// Where you are, bottom left.
    public var place: PageInfoPlace
    /// How far through the book, bottom right.
    public var percentage: Bool

    public init(clock: Bool = true, place: PageInfoPlace = .pageInBook, percentage: Bool = true) {
        self.clock = clock
        self.place = place
        self.percentage = percentage
    }
}

/// The corners' choices in UserDefaults, beside the reader's look (`EpubAppearanceStore`).
public enum PageInfoStore {
    static let prefix = "epub.pageInfo."

    public static func load(_ defaults: UserDefaults) -> PageInfoPreferences {
        func flag(_ key: String, _ fallback: Bool) -> Bool {
            defaults.object(forKey: prefix + key) == nil ? fallback : defaults.bool(forKey: prefix + key)
        }
        let place = defaults.string(forKey: prefix + "place").flatMap(PageInfoPlace.init(rawValue:)) ?? .pageInBook
        return PageInfoPreferences(clock: flag("clock", true), place: place, percentage: flag("percentage", true))
    }

    public static func save(_ value: PageInfoPreferences, to defaults: UserDefaults) {
        defaults.set(value.clock, forKey: prefix + "clock")
        defaults.set(value.place.rawValue, forKey: prefix + "place")
        defaults.set(value.percentage, forKey: prefix + "percentage")
    }
}

/// The bottom corners' words for a place on the page.
public struct PageInfoCorners: Equatable, Sendable {
    /// Bottom left: "Page 213 of 765", "12 min left in chapter"; nil for none.
    public var place: String?
    /// Bottom right: "28%"; nil when turned off or not known yet.
    public var percent: String?

    public init(place: String? = nil, percent: String? = nil) {
        self.place = place
        self.percent = percent
    }
}

public enum PageInfo {
    /// Where the reader is, as the corners can say it.
    public struct Reading: Equatable, Sendable {
        /// The book's pages as the hub counts them (the edition's
        /// `pageCount`, as the book's page says "735 pages"); 0 when unknown.
        public var bookPages: Int
        /// How far through the book, 0 to 1.
        public var progress: Double?
        /// Where the chapter on the page starts and ends in the book, 0 to 1.
        public var chapter: ClosedRange<Double>?
        /// Readium's positions, for a book the hub has no page count for:
        /// the position reached in the book and in the chapter, of how many.
        public var positionInBook: (index: Int, count: Int)?
        public var positionInChapter: (index: Int, count: Int)?
        /// The pace's (or the narration's) time left.
        public var timeLeft: TimeLeft?

        public init(bookPages: Int = 0, progress: Double? = nil, chapter: ClosedRange<Double>? = nil,
                    positionInBook: (index: Int, count: Int)? = nil, positionInChapter: (index: Int, count: Int)? = nil,
                    timeLeft: TimeLeft? = nil) {
            self.bookPages = bookPages
            self.progress = progress
            self.chapter = chapter
            self.positionInBook = positionInBook
            self.positionInChapter = positionInChapter
            self.timeLeft = timeLeft
        }

        public static func == (lhs: Reading, rhs: Reading) -> Bool {
            lhs.bookPages == rhs.bookPages && lhs.progress == rhs.progress && lhs.chapter == rhs.chapter
                && lhs.positionInBook?.index == rhs.positionInBook?.index && lhs.positionInBook?.count == rhs.positionInBook?.count
                && lhs.positionInChapter?.index == rhs.positionInChapter?.index
                && lhs.positionInChapter?.count == rhs.positionInChapter?.count && lhs.timeLeft == rhs.timeLeft
        }
    }

    /// The page in the book, from one: the hub's page count at the place
    /// read, rounded as the book's page rounds it ("page 363 of 735"), so a
    /// book of 765 pages never reads "Page 1500 of 3400"; Readium's
    /// positions only when the hub has no count.
    public static func pageInBook(_ reading: Reading) -> (page: Int, count: Int)? {
        if reading.bookPages > 0, let progress = reading.progress {
            return (ReadingBookFacts.page(of: progress, pages: reading.bookPages), reading.bookPages)
        }
        return reading.positionInBook.flatMap(counted)
    }

    /// The page in the chapter, from one: the chapter's share of the book's
    /// pages, from the page it starts on to the one before the next starts;
    /// its positions when the hub has no count.
    public static func pageInChapter(_ reading: Reading) -> (page: Int, count: Int)? {
        let pages = reading.bookPages
        if pages > 0, let progress = reading.progress, let chapter = reading.chapter {
            let first = ReadingBookFacts.page(of: chapter.lowerBound, pages: pages)
            let last = chapter.upperBound >= 1 ? pages
                : max(first, ReadingBookFacts.page(of: chapter.upperBound, pages: pages) - 1)
            let page = ReadingBookFacts.page(of: progress, pages: pages)
            return (min(max(page - first + 1, 1), last - first + 1), last - first + 1)
        }
        return reading.positionInChapter.flatMap(counted)
    }

    /// The book's pages as the hub counts them: the edition being read, else
    /// the longest text edition (what the book's page says, "735 pages"); 0 when unknown.
    public static func bookPages(_ work: ReadingWork, sourceItemId: String) -> Int {
        work.editions.first { $0.sourceItemId == sourceItemId && $0.kind != "audiobook" && $0.pageCount > 0 }?.pageCount
            ?? ReadingBookFacts.textPages(work)
    }

    /// The bottom left's words for `place`, or nil when it cannot be said yet.
    public static func label(_ place: PageInfoPlace, _ reading: Reading) -> String? {
        switch place {
        case .pageInBook: pageInBook(reading).map { "Page \($0.page) of \($0.count)" }
        case .pageInChapter: pageInChapter(reading).map { "Page \($0.page) of \($0.count) in chapter" }
        case .chapterTimeLeft: reading.timeLeft?.chapterLabel()
        case .bookTimeLeft: reading.timeLeft?.bookLabel()
        case .none: nil
        }
    }

    /// Both bottom corners, as `preferences` choose.
    public static func corners(_ preferences: PageInfoPreferences, _ reading: Reading) -> PageInfoCorners {
        PageInfoCorners(place: label(preferences.place, reading),
                        percent: preferences.percentage ? reading.progress.map { Fmt.readingPercentLabel($0) } : nil)
    }

    /// A tap on the bottom left, or L3: the next way that can be said where
    /// the reader is. None stays none: it was chosen in Appearance.
    public static func next(_ preferences: PageInfoPreferences, _ reading: Reading) -> PageInfoPreferences {
        guard preferences.place != .none else { return preferences }
        var next = preferences
        next.place = preferences.place.next { label($0, reading) != nil }
        return next
    }

    /// The time, as the device writes it: 12 or 24 hours as its settings say.
    public static func clock(_ date: Date, locale: Locale = .autoupdatingCurrent,
                             timeZone: TimeZone = .autoupdatingCurrent) -> String {
        // A skeleton ("jmm"), so the hour follows the locale's hour cycle, which the device's setting decides.
        date.formatted(Date.FormatStyle(locale: locale, timeZone: timeZone).hour().minute())
    }

    /// The strips Readium keeps at the top and bottom of a page (its content
    /// inset), where the corners sit, off the text: a phone sideways, or any
    /// screen short of height, keeps less. The window's safe area can make them deeper.
    public static func strip(compactHeight: Bool) -> (top: Double, bottom: Double) {
        compactHeight ? (34, 34) : (62, 62)
    }

    /// A position of so many, counted from one and never past the count.
    static func counted(_ position: (index: Int, count: Int)) -> (page: Int, count: Int)? {
        guard position.count > 0 else { return nil }
        return (min(max(position.index, 0), position.count - 1) + 1, position.count)
    }
}

extension BookSections {
    /// The position reached in the whole book, of all of them (the pages
    /// the slider and the pace count): the corners' pages when the hub has
    /// no page count.
    public func positionInBook(href: String, progression: Double) -> (index: Int, count: Int)? {
        let count = sizes.reduce(0, +)
        guard count > 0, let position = position(href: href, progression: progression) else { return nil }
        return (min(max(Int(position.rounded(.down)), 0), count - 1), count)
    }

    /// The position reached in the part, of its positions.
    public func positionInChapter(href: String, progression: Double) -> (index: Int, count: Int)? {
        guard let index = index(of: href), sections[index].size > 0 else { return nil }
        let size = sections[index].size
        return (min(max(Int((Double(size) * TimeLeft.clamped(progression)).rounded(.down)), 0), size - 1), size)
    }

    /// Where the part starts and ends in the whole book, 0 to 1.
    public func span(href: String) -> ClosedRange<Double>? {
        guard let index = index(of: href) else { return nil }
        let start = sections[index].start
        let end = index + 1 < sections.count ? sections[index + 1].start : 1
        return min(start, end)...max(start, end)
    }
}

extension TimeLeft {
    /// "12 min left in chapter"; a minute at least.
    public func chapterLabel() -> String { "\(Self.span(chapterMs)) left in chapter" }

    /// "4h 10m left in book"; a minute at least.
    public func bookLabel() -> String { "\(Self.span(bookMs)) left in book" }

    static func span(_ ms: Int64) -> String { Fmt.runtime(max(ms / 1_000, 60)) }
}

extension EpubPagePalette {
    /// "#3E3526" as ARGB, opaque; nil for anything else.
    public static func argb(_ hex: String) -> UInt32? {
        let digits = hex.hasPrefix("#") ? String(hex.dropFirst()) : hex
        guard digits.count == 6, let value = UInt32(digits, radix: 16) else { return nil }
        return 0xFF00_0000 | value
    }
}
