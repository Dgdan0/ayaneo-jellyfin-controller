import Foundation

// How long a book has left (#25, phase 4): Android's `reader/ReadingPace.kt`
// and `settings/ReadingPaceSettings.kt` (#18, E3), held to their test cases
// in `BookReaderRulesTests`, so the iPad and the Pocket learn a pace the same
// way and word what is left alike. Read along's time left (the narration's
// own, `TimeLeft.ofNarration` on Android) comes with read along.

/// How fast this person reads a book, learnt from the reading itself, for
/// "12 min left in chapter · 4h 10m in book".
///
/// Readium measures a book in positions: one per 1,024 bytes of each of its
/// files as stored in the EPUB, so a position is an amount of text, about 400
/// words of a novel. Reading on adds the text read and the time it took: a
/// page turned, or a stretch scrolled, once `minStepMs` has passed. A move
/// back, a jump (the contents, a chapter key: more than `maxStepPositions` at
/// once), a long pause (the book put down) or a flick through pages faster
/// than anyone reads adds nothing.
///
/// The pace leans on `priorPositions` positions read at a prior pace (the
/// person's over every book, or `defaultMinutesPerPosition` before there is
/// one), so the first pages of a book do not swing it wildly; the book's own
/// pace wins as it is read. Only the most recent `windowPositions` count, so
/// the pace follows a change of font size.
public struct ReadingPace: Equatable, Sendable {
    public var positions: Double
    public var minutes: Double

    public init(positions: Double = 0, minutes: Double = 0) {
        self.positions = positions
        self.minutes = minutes
    }

    /// About 400 words a position, at 250 words a minute.
    public static let defaultMinutesPerPosition = 1.6
    public static let priorPositions = 6.0
    public static let windowPositions = 120.0
    /// More at once than a page or two is a jump, not reading.
    public static let maxStepPositions = 4.0
    public static let minStepMs: Int64 = 4_000
    /// Longer on one stretch than this is a pause.
    public static let maxStepMs: Int64 = 8 * 60_000
    /// About 1,600 words a minute: flicking through, not reading.
    public static let maxPositionsPerMinute = 4.0

    /// A stretch read: from `from` on to `to`, in `elapsedMs`. `observe` judges it.
    public struct Reading: Equatable, Sendable {
        public let from: Double
        public let to: Double
        public let elapsedMs: Int64

        public init(from: Double, to: Double, elapsedMs: Int64) {
            self.from = from
            self.to = to
            self.elapsedMs = elapsedMs
        }
    }

    /// `elapsedMs` spent reading from `from` on to `to`: counted when it is plausible reading.
    public func observe(from: Double, to: Double, elapsedMs: Int64) -> ReadingPace {
        let advanced = to - from
        if advanced.isNaN || advanced <= 0 || advanced > Self.maxStepPositions { return self }
        if elapsedMs < Self.minStepMs || elapsedMs > Self.maxStepMs { return self }
        let spent = Double(elapsedMs) / 60_000
        if advanced / spent > Self.maxPositionsPerMinute { return self }
        let next = ReadingPace(positions: positions + advanced, minutes: minutes + spent)
        return next.positions <= Self.windowPositions
            ? next
            : ReadingPace(positions: Self.windowPositions, minutes: next.minutes * Self.windowPositions / next.positions)
    }

    /// `reading` counted, if it is plausible reading.
    public func observe(_ reading: Reading) -> ReadingPace {
        observe(from: reading.from, to: reading.to, elapsedMs: reading.elapsedMs)
    }

    /// Minutes a position takes: what was read, leaning on `prior` until there is enough of it.
    public func minutesPerPosition(prior: Double = defaultMinutesPerPosition) -> Double {
        (minutes + prior * Self.priorPositions) / (positions + Self.priorPositions)
    }

    /// Read in this book and in another, as one: the pace over every book.
    public static func + (left: ReadingPace, right: ReadingPace) -> ReadingPace {
        ReadingPace(positions: left.positions + right.positions, minutes: left.minutes + right.minutes)
    }

    /// "12.5000|20.2500", as Android keeps it.
    public func encode() -> String {
        String(format: "%.4f|%.4f", positions, minutes)
    }

    public static func decode(_ raw: String?) -> ReadingPace {
        guard let parts = raw?.split(separator: "|", omittingEmptySubsequences: false), parts.count >= 2,
              let positions = Double(parts[0]), let minutes = Double(parts[1]),
              !positions.isNaN, !minutes.isNaN, positions >= 0, minutes >= 0 else { return ReadingPace() }
        return ReadingPace(positions: positions, minutes: minutes)
    }

    /// Where the reading was last measured from: `at` each new place in the
    /// book, with the time, gives the stretch read since, once there has been
    /// time enough to judge it (`minStepMs`). A move back, a jump or a pause
    /// beyond `maxStepMs` starts again from the new place.
    public struct Tracker: Sendable {
        private var fromPosition = Double.nan
        private var fromMs: Int64 = 0

        public init() {}

        public mutating func at(_ position: Double, nowMs: Int64) -> Reading? {
            if fromPosition.isNaN || position < fromPosition || nowMs - fromMs > ReadingPace.maxStepMs
                || position - fromPosition > ReadingPace.maxStepPositions {
                restart(position, nowMs: nowMs)
                return nil
            }
            // A little scrolled, or a page turned quickly: wait until there is time enough to judge.
            if nowMs - fromMs < ReadingPace.minStepMs { return nil }
            let reading = Reading(from: fromPosition, to: position, elapsedMs: nowMs - fromMs)
            restart(position, nowMs: nowMs)
            return reading
        }

        /// The book shows again (or opened somewhere new): the time away is not reading.
        public mutating func restart(_ position: Double = .nan, nowMs: Int64 = 0) {
            fromPosition = position
            fromMs = nowMs
        }
    }
}

/// How fast this person reads, kept on this device (Android's
/// `ReadingPaceSettings`): per edition of a book, since its positions measure
/// its own files, and over every book, which a book leans on until enough of
/// it has been read.
public struct ReadingPaceStore: @unchecked Sendable {
    private let defaults: UserDefaults
    static let prefix = "reading_pace:"
    static let everyBookKey = "reading_pace"

    public init(defaults: UserDefaults) {
        self.defaults = defaults
    }

    /// The edition's key: "workId:sourceItemId".
    public static func key(workId: String, sourceItemId: String) -> String { workId + ":" + sourceItemId }

    public func book(_ key: String) -> ReadingPace { ReadingPace.decode(defaults.string(forKey: Self.prefix + key)) }

    /// The pace over every book, as minutes a position, leaning on the default until there is one.
    public func prior() -> Double { everyBook().minutesPerPosition() }

    private func everyBook() -> ReadingPace { ReadingPace.decode(defaults.string(forKey: Self.everyBookKey)) }

    /// `reading` counted for this book and for every book; this book's pace now.
    @discardableResult
    public func record(_ key: String, book: ReadingPace, reading: ReadingPace.Reading) -> ReadingPace {
        let next = book.observe(reading)
        if next == book { return book }
        defaults.set(next.encode(), forKey: Self.prefix + key)
        defaults.set(everyBook().observe(reading).encode(), forKey: Self.everyBookKey)
        return next
    }
}

/// How long is left, in the chapter and in the book (#18, E3): from the pace
/// over Readium's positions.
public struct TimeLeft: Equatable, Sendable {
    public let chapterMs: Int64
    public let bookMs: Int64

    public init(chapterMs: Int64, bookMs: Int64) {
        self.chapterMs = chapterMs
        self.bookMs = bookMs
    }

    /// "12 min left in chapter · 4h 10m in book"; a minute at least, as the audiobook's line.
    public func label() -> String {
        "\(Self.span(chapterMs)) left in chapter · \(Self.span(bookMs)) in book"
    }

    /// The positions in each part of the book (`sectionSizes`, in reading
    /// order), the part on screen and how far through it: what is left at
    /// `minutesPerPosition`. Nil before the book's positions are known.
    public static func ofPositions(_ sectionSizes: [Int], section: Int, progression: Double,
                                   minutesPerPosition: Double) -> TimeLeft? {
        guard sectionSizes.indices.contains(section) else { return nil }
        if minutesPerPosition <= 0 || minutesPerPosition.isNaN { return nil }
        let inChapter = Double(sectionSizes[section]) * (1 - clamped(progression))
        let after = sectionSizes.dropFirst(section + 1).reduce(0, +)
        let perPosition = minutesPerPosition * 60_000
        return TimeLeft(chapterMs: Int64(inChapter * perPosition), bookMs: Int64((inChapter + Double(after)) * perPosition))
    }

    /// Where in the whole book, in positions: the parts before and the way through this one.
    public static func position(_ sectionSizes: [Int], section: Int, progression: Double) -> Double? {
        guard sectionSizes.indices.contains(section) else { return nil }
        return Double(sectionSizes.prefix(section).reduce(0, +)) + Double(sectionSizes[section]) * clamped(progression)
    }

    static func clamped(_ value: Double) -> Double {
        value.isNaN ? 0 : min(max(value, 0), 1)
    }
}
