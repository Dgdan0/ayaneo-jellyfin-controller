import Foundation

// Every line a book, a series, an issue or an author says (#25). Ports of
// Android's `ReadingBookFacts`, `SeriesBookLabels`, `AuthorLabels` and
// `Fmt.readingPercent`, held to their test cases, so the Pocket and the
// iPad word a book the same way.

extension Fmt {
    /// How far through a book, as a whole percent: rounded down, 1 to 99
    /// while under way and 100 only when finished, so a book's facts, its
    /// Resume button and its card agree (Android #16: the facts said 1% where
    /// Resume said 2%).
    public static func readingPercent(_ fraction: Double, completed: Bool = false) -> Int {
        if completed { return 100 }
        guard fraction.isFinite, fraction > 0 else { return 0 }
        return min(max(Int(fraction * 100), 1), 99)
    }

    /// `readingPercent` with its sign: "49%".
    public static func readingPercentLabel(_ fraction: Double, completed: Bool = false) -> String {
        "\(readingPercent(fraction, completed: completed))%"
    }
}

/// The lines under and over a book's title. Android's `ReadingBookFacts`.
public enum ReadingBookFacts {
    /// "Book 6 of Red Rising", "Red Rising" without a number, or nil outside a series.
    public static func place(_ work: ReadingWork) -> String? {
        guard !blank(work.series) else { return nil }
        if work.seriesNumber.isEmpty { return work.series }
        return "Book \(work.seriesNumber) of \(work.series)"
    }

    /// A series page's progress: "On #6 · 1 of 6 finished", or nil before any
    /// book is started.
    public static func seriesProgress(_ series: ReadingWork) -> String? {
        let books = series.sections.flatMap(\.items)
        let started = books.contains { ($0.progress?.percentage ?? 0) > 0 || $0.progress?.completed == true }
            || (series.progress?.percentage ?? 0) > 0
        guard started else { return nil }
        let total = books.isEmpty ? series.bookCount : books.count
        let on = ReadingShelves.onNumber(series)
        let finished = books.filter { $0.progress?.completed == true }.count
        return ([on.isEmpty ? nil : "On #\(on)", "\(finished) of \(total) finished"] as [String?])
            .compactMap { $0 }.joined(separator: " · ")
    }

    /// How far through: "49% · page 363 of 735" when the book's length is
    /// known, "49% read" otherwise, "Finished" once done; nil before starting.
    /// A comic run says which issue: "On issue 51 · 1% read".
    public static func progress(_ work: ReadingWork) -> String? {
        guard let p = work.progress else { return nil }
        if p.completed { return "Finished" }
        guard p.percentage > 0 else { return nil }
        if kindTag(work.kind) != nil {
            // A page count across a whole run says nothing.
            var parts: [String] = []
            if let number = work.continueAt?.number, !blank(number) {
                parts.append(work.kind == "manga" ? "On chapter \(number)" : "On issue \(number)")
            }
            parts.append(comicLine(p))
            return parts.joined(separator: " · ")
        }
        let percent = Fmt.readingPercentLabel(p.percentage)
        let pages = textPages(work)
        guard pages > 0 else { return "\(percent) read" }
        return "\(percent) · page \(page(of: p.percentage, pages: pages)) of \(pages)"
    }

    /// Pages of the longest text edition and the length of the audiobook, with
    /// its narrator; a comic run counted in issues (chapters for manga).
    public static func length(_ work: ReadingWork) -> [String] {
        let issues = work.sections.reduce(0) { $0 + $1.items.count }
        if kindTag(work.kind) != nil && issues > 0 {
            return [work.kind == "manga" ? plural(issues, "chapter") : plural(issues, "issue")]
        }
        var out: [String] = []
        let pages = textPages(work)
        if pages > 0 { out.append("\(pages) pages") }
        if let audio = work.editions.first(where: { $0.kind == "audiobook" }) {
            let runtime = Fmt.runtime(audio.durationMs / 1_000)
            if !runtime.isEmpty { out.append(runtime) }
            if !blank(audio.narrator) { out.append("read by \(audio.narrator)") }
        }
        return out
    }

    /// A book's whole line. The author is named here only when the hub sent no
    /// author link (an older hub): otherwise the link names them; a series the
    /// hub links to is a chip of its own.
    public static func line(_ work: ReadingWork, progress: String?) -> String {
        var out: [String] = []
        if work.authorRefs.isEmpty && !work.authors.isEmpty { out.append(work.authors.joined(separator: ", ")) }
        if blank(work.seriesId), let place = place(work) { out.append(place) }
        if work.year > 0 { out.append(String(work.year)) }
        out += length(work)
        if !work.genres.isEmpty { out.append(work.genres.joined(separator: ", ")) }
        if let progress { out.append(progress) }
        return out.joined(separator: " · ")
    }

    /// The pill on a comic's cover: "Comic", "Manga"; nil for a book.
    public static func kindTag(_ kind: String) -> String? {
        switch kind {
        case "comic": "Comic"
        case "manga": "Manga"
        default: nil
        }
    }

    /// Under a comic or manga's cover: "Not started", "1% read", "Finished".
    /// A run of hundreds of issues read one page is "1% read", never 0%.
    public static func comicLine(_ progress: ReadingProgress?) -> String {
        if progress?.completed == true { return "Finished" }
        if let p = progress, p.percentage > 0 { return "\(Fmt.readingPercentLabel(p.percentage)) read" }
        return "Not started"
    }

    /// An issue's name on its card: Kavita's own title when it has one, else
    /// "Issue 7" ("Chapter 11" for manga).
    public static func issueTitle(_ item: ReadingSectionItem, kind: String) -> String {
        let title = item.title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !blank(item.number) else { return title }
        if title.isEmpty || title == item.number { return (kind == "manga" ? "Chapter " : "Issue ") + item.number }
        return title
    }

    /// Under an issue's cover: "36 pages · Not started".
    public static func issueLine(_ item: ReadingSectionItem) -> String {
        ([item.pageCount > 0 ? "\(item.pageCount) pages" : nil, comicLine(item.progress)] as [String?])
            .compactMap { $0 }.joined(separator: " · ")
    }

    /// The line over a book page's title: "Book 6 · Red Rising" in a series,
    /// "Series · Pierce Brown" on a series' page, "Comic · My Marvelous Year"
    /// on a run (its `library`, when known), else "Book" or "Audiobook".
    public static func eyebrow(_ work: ReadingWork, library: String = "") -> String {
        if let kind = kindTag(work.kind) { return [kind, library].filter { !blank($0) }.joined(separator: " · ") }
        if work.isSeries { return ["Series", work.byline].filter { !blank($0) }.joined(separator: " · ") }
        if !blank(work.series) {
            return work.seriesNumber.isEmpty ? work.series : "Book \(work.seriesNumber) · \(work.series)"
        }
        return work.kind == "audiobook" ? "Audiobook" : "Book"
    }

    /// Over an audiobook's title while you listen: "Audiobook · Book 2 ·
    /// Mistborn", or just "Audiobook" outside a series or before the book's
    /// details have arrived.
    public static func listeningEyebrow(_ work: ReadingWork?) -> String {
        guard let work else { return "Audiobook" }
        let own = eyebrow(work)
        return own == "Book" || own == "Audiobook" ? "Audiobook" : "Audiobook · " + own
    }

    /// Under it: who wrote the book and who reads this narration,
    /// "Brandon Sanderson · read by Michael Kramer".
    public static func listeningLine(_ work: ReadingWork?, narrator: String) -> String {
        var out: [String] = []
        if let work {
            let names = (work.authors.isEmpty ? work.authorRefs.map(\.name) : work.authors).filter { !blank($0) }
            if !names.isEmpty { out.append(names.joined(separator: ", ")) }
        }
        let reader = narrator.trimmingCharacters(in: .whitespacesAndNewlines)
        if !reader.isEmpty { out.append("read by \(reader)") }
        return out.joined(separator: " · ")
    }

    /// Under a book also being read on Books Home: "Blake Crouch · 3%",
    /// "Mistborn Original Trilogy #1 · 1%". Started is never 0%.
    public static func miniLine(_ work: ReadingWork) -> String {
        var out: [String] = []
        if !blank(work.cardSubtitle) { out.append(work.cardSubtitle) }
        if let p = work.progress {
            if p.completed {
                out.append("Finished")
            } else if p.percentage > 0 {
                out.append(Fmt.readingPercentLabel(p.percentage))
            }
        }
        return out.joined(separator: " · ")
    }

    /// Under "Continue reading · Light Bringer" on a series' page: "Book 6 ·
    /// 49% · page 363 of 735", where `pages` is that book's length (0 when
    /// unknown); "Issue 51 · 1%" in a comic run.
    public static func continueLine(_ point: ReadingContinue, kind: String, pages: Int) -> String {
        var out: [String] = []
        if !blank(point.number) {
            let noun = switch kind {
            case "comic": "Issue "
            case "manga": "Chapter "
            default: "Book "
            }
            out.append(noun + point.number)
        }
        if point.percentage > 0 { out.append(Fmt.readingPercentLabel(point.percentage)) }
        if pages > 0 && point.percentage > 0 { out.append("page \(page(of: point.percentage, pages: pages)) of \(pages)") }
        return out.joined(separator: " · ")
    }

    /// What a book can be opened as, always in this order: ebook, audiobook,
    /// read along. From the hub's list, else from the editions it has.
    public static func formats(_ work: ReadingWork) -> [String] {
        let known = work.availability.isEmpty
            ? work.editions.filter { $0.availability == "available" }.map { $0.kind == "book" ? "ebook" : $0.kind }
            : work.availability
        return allFormats.filter(known.contains)
    }

    /// The three formats a Storyteller book can have, in their order.
    public static let allFormats = ["ebook", "audiobook", "readaloud"]

    /// "Ebook", "Audiobook", "Read along".
    public static func formatLabel(_ format: String) -> String {
        switch format {
        case "ebook": "Ebook"
        case "audiobook": "Audiobook"
        case "readaloud": "Read along"
        default: ReadingType.label(format)
        }
    }

    private static func textPages(_ work: ReadingWork) -> Int {
        work.editions.filter { $0.kind != "audiobook" }.map(\.pageCount).max() ?? 0
    }

    /// The page a fraction of the way through: 1 to `pages`.
    private static func page(of fraction: Double, pages: Int) -> Int {
        min(max(Int(fraction * Double(pages)), 1), pages)
    }

    static func plural(_ n: Int, _ one: String) -> String { n == 1 ? "1 \(one)" : "\(n) \(one)s" }

    static func blank(_ text: String) -> Bool { text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
}

/// What a book in a series row says under its cover. Android's `SeriesBookLabels`.
public enum SeriesBookLabels {
    /// "Ebook + audio", "Audio", "Read along", or nil for a plain ebook: the
    /// usual case says nothing, so the unusual one stands out.
    public static func formats(_ formats: [String]) -> String? {
        let audio = formats.contains("audiobook")
        let text = formats.contains("ebook")
        if formats.contains("readaloud") { return "Read along" }
        if audio && text { return "Ebook + audio" }
        if audio { return "Audio" }
        return nil
    }

    /// "#2 · 40% · Audio", "#3 · Missing", "Completed".
    public static func subtitle(number: String, available: Bool, progress: ReadingProgress?, formats: [String]) -> String {
        var out: [String] = []
        if !ReadingBookFacts.blank(number) { out.append("#\(number)") }
        if !available {
            out.append("Missing")
        } else if progress?.completed == true {
            out.append("Completed")
        } else if let p = progress, p.percentage > 0 {
            out.append(Fmt.readingPercentLabel(p.percentage))
        }
        if available, let label = self.formats(formats) { out.append(label) }
        return out.joined(separator: " · ")
    }

    /// A book outside any series, as a book of a row.
    public static func item(of work: ReadingWork) -> ReadingSectionItem {
        ReadingSectionItem(workId: work.id, title: work.title, kind: work.kind, artwork: work.artwork, authors: work.authors,
                           progress: work.progress, availability: "available", formats: work.availability)
    }
}

/// An author's shelf in words. Android's `AuthorLabels`.
public enum AuthorLabels {
    /// "2 series · 6 books", "2 books", "1 series". An older hub sends only the total.
    public static func shelf(seriesCount: Int, bookCount: Int, total: Int) -> String {
        let books = bookCount > 0 ? bookCount : total
        var out: [String] = []
        if seriesCount > 0 { out.append(seriesCount == 1 ? "1 series" : "\(seriesCount) series") }
        if books > 0 { out.append(books == 1 ? "1 book" : "\(books) books") }
        return out.joined(separator: " · ")
    }

    /// How far through their books you are: "3 in progress · 1 finished",
    /// "All 6 finished"; nil before any is started.
    public static func reading(_ progress: [ReadingProgress?]) -> String? {
        let finished = progress.filter { $0?.completed == true }.count
        let going = progress.filter { if let p = $0 { !p.completed && p.percentage > 0 } else { false } }.count
        if !progress.isEmpty && finished == progress.count { return "All \(finished) finished" }
        let parts = [going > 0 ? "\(going) in progress" : nil, finished > 0 ? "\(finished) finished" : nil].compactMap { $0 }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }
}
