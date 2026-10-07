import Foundation

/// The book page's words (#39, the owner's layout "1"): what you can do (the
/// formats), what is about you (your stars, when you finished, your shelves)
/// and the labels (genres), each said apart.
public enum BookPage {
    // MARK: Right of the cover

    /// Under the author: "2006 · 541 pages · 24 h 39 min · 4.5 from readers".
    public static func facts(_ work: ReadingWork) -> String {
        var out: [String] = []
        if work.year > 0 { out.append(String(work.year)) }
        let pages = ReadingBookFacts.textPages(work)
        if pages > 0 { out.append("\(pages) pages") }
        if let audio = work.editions.first(where: { $0.kind == "audiobook" }), audio.durationMs > 0 {
            let runtime = Fmt.runtime(audio.durationMs / 1_000)
            if !runtime.isEmpty { out.append(runtime) }
        }
        if let readers = community(work.community) { out.append(readers) }
        return out.joined(separator: " · ")
    }

    /// "4.5 from readers"; nil when nobody's rating is known.
    public static func community(_ community: ReadingCommunity?) -> String? {
        guard let community, community.rating > 0 else { return nil }
        // The hub sends two decimals at most: 4.45 is 4.5, whatever binary makes of it.
        let tenths = ((community.rating * 100).rounded() / 10).rounded() / 10
        return String(format: "%.1f", locale: Locale(identifier: "en_US_POSIX"), tenths) + " from readers"
    }

    /// The labels, one quiet line: "Fantasy · Epic fantasy · Magic systems".
    public static func genres(_ work: ReadingWork) -> String {
        work.genres.map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }.joined(separator: " · ")
    }

    /// A format as the row shows it: its name, and whether it opens.
    public struct Format: Equatable, Sendable, Identifiable {
        /// "audiobook", "ebook", "readaloud".
        public let kind: String
        public let label: String
        public let readiness: FormatReadiness

        public var id: String { kind }
        public var opens: Bool { readiness == .ready }
    }

    /// Audiobook, Ebook, Read along, in that order; none for a series or a comic.
    public static func formats(_ work: ReadingWork) -> [Format] {
        guard !work.isSeries, ReadingBookFacts.kindTag(work.kind) == nil else { return [] }
        let statuses = ReadingFormatStatus.forWork(work)
        return ["audiobook", "ebook", "readaloud"].compactMap { kind in
            statuses.first { $0.kind == kind }.map { Format(kind: kind, label: $0.label, readiness: $0.readiness) }
        }
    }

    /// What a grey format says when it is tapped.
    public static func unavailable(_ format: Format) -> String {
        switch format.readiness {
        case .pending: format.kind == "readaloud" ? "Read along is still being aligned" : "\(format.label) is on its way"
        case .unknown: "\(format.label) cannot be checked just now"
        default: "This book has no \(format.label.lowercased())"
        }
    }

    /// The Resume button, where you are: "Resume · Chapter 14 · 32%", "Resume
    /// · 32%" when the place names no chapter; nil before starting and once finished.
    public static func resume(_ work: ReadingWork, chapter: String?) -> String? {
        guard let progress = work.progress, !progress.completed, progress.percentage > 0 else { return nil }
        let named = chapter?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return (["Resume", named.isEmpty ? nil : named, Fmt.readingPercentLabel(progress.percentage)] as [String?])
            .compactMap { $0 }.joined(separator: " · ")
    }

    /// The chapter the ebook was left at, for the Resume button: the title
    /// of the latest ebook place this device kept (Readium's locator names
    /// it), when no audiobook place came after it; nil otherwise.
    public static func chapter(ebook: [ReadingCheckpoint], audio: [ReadingCheckpoint]) -> String? {
        guard let latest = ebook.max(by: { $0.updatedAt < $1.updatedAt }),
              latest.updatedAt >= (audio.map(\.updatedAt).max() ?? 0),
              case .string(let title)? = (latest.local ?? latest.remote)?.locator?["title"] else { return nil }
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    // MARK: Under the cover, you

    /// "Finished Sep 2025 · 2nd time", "Finished Oct 2026 · rate it?", "2nd
    /// time"; nil when there is nothing to say.
    public static func youLine(_ you: ReadingYou?) -> String? {
        guard let you else { return nil }
        var out: [String] = []
        if let finished = you.finished.flatMap(finishedLabel) { out.append(finished) }
        if let times = you.readCount.flatMap(timesLabel) { out.append(times) }
        if you.finished != nil && you.rating == nil { out.append("rate it?") }
        return out.isEmpty ? nil : out.joined(separator: " · ")
    }

    /// "Finished Sep 2025" for "2025-09"; nil for anything else.
    public static func finishedLabel(_ month: String) -> String? {
        guard let date = FinishDate(month) else { return nil }
        return "Finished \(shortMonths[date.month - 1]) \(date.year)"
    }

    /// "2nd time", "3rd time", "11th time"; nil for a first read.
    public static func timesLabel(_ count: Int) -> String? {
        guard count > 1 else { return nil }
        let suffix: String
        switch (count % 10, count % 100) {
        case (_, 11...13): suffix = "th"
        case (1, _): suffix = "st"
        case (2, _): suffix = "nd"
        case (3, _): suffix = "rd"
        default: suffix = "th"
        }
        return "\(count)\(suffix) time"
    }

    /// Your shelves in the accent: "cosmere · favorites"; nil for none.
    public static func shelves(_ you: ReadingYou?) -> String? {
        let names = (you?.shelves ?? []).map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        return names.isEmpty ? nil : names.joined(separator: " · ")
    }

    /// "Your rating, 4 of 5", or "Not rated".
    public static func ratingLabel(_ rating: Int?) -> String {
        guard let rating else { return "Not rated" }
        return "Your rating, \(rating) of 5"
    }

    /// A star tapped: that rating, or none when it is the rating already given.
    public static func rating(tapping star: Int, current: Int?) -> ReadingYouChange.Value<Int> {
        star == current ? .clear : .set(min(max(star, 1), 5))
    }

    /// Marked finished: a first read leaves the count to the hub; a book
    /// finished before is read once more.
    public static func finishing(_ you: ReadingYou?, on date: FinishDate) -> ReadingYouChange {
        let again = you?.finished != nil
        return ReadingYouChange(finished: .set(date.value),
                                readCount: again ? .set(min((you?.readCount ?? 1) + 1, 99)) : .keep)
    }

    /// Undone at once: back to what was there.
    public static func undoing(_ before: ReadingYou?) -> ReadingYouChange {
        ReadingYouChange(finished: before?.finished.map { .set($0) } ?? .clear,
                         readCount: before?.readCount.map { .set($0) } ?? .clear)
    }

    // MARK: When did you finish?

    public static let months = ["January", "February", "March", "April", "May", "June", "July", "August", "September",
                                "October", "November", "December"]
    static let shortMonths = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]

    /// A month a book was finished in: from January 1900 to this month.
    public struct FinishDate: Equatable, Sendable {
        public var month: Int
        public var year: Int

        public init(month: Int, year: Int) {
            self.month = min(max(month, 1), 12)
            self.year = year
        }

        /// "2025-09".
        public init?(_ value: String) {
            let parts = value.split(separator: "-")
            guard parts.count == 2, parts[0].count == 4, let year = Int(parts[0]), let month = Int(parts[1]),
                  (1...12).contains(month) else { return nil }
            self.init(month: month, year: year)
        }

        /// This month: what the panel opens on.
        public static func current(_ now: Date = Date(), calendar: Calendar = .current) -> FinishDate {
            let parts = calendar.dateComponents([.year, .month], from: now)
            return FinishDate(month: parts.month ?? 1, year: parts.year ?? 2000)
        }

        /// "2026-10", as the hub takes it.
        public var value: String { String(format: "%04d-%02d", year, month) }

        /// The years to choose from, this one first, back to 1900.
        public static func years(now: FinishDate) -> [Int] { Array((1900...max(now.year, 1900)).reversed()) }

        /// The months to choose from in `year`: none after this one.
        public static func months(in year: Int, now: FinishDate) -> [Int] {
            year >= now.year ? Array(1...now.month) : Array(1...12)
        }

        /// Never after `now`, never before January 1900.
        public func clamped(to now: FinishDate) -> FinishDate {
            if year > now.year || (year == now.year && month > now.month) { return now }
            if year < 1900 { return FinishDate(month: 1, year: 1900) }
            return self
        }
    }
}
