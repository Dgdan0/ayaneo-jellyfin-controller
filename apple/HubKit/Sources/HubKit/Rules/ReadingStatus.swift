import Foundation

/// A book's reading status (#63; the Pocket's `ReadingStatus`, case for
/// case): one word for where it stands for this profile, and the one owner of
/// what that word is, what it shows and what choosing it does.
///
/// - `want`: on the Want to Read list. The list is this device's own, so
///   choosing Want to read adds the book to it and any other status takes it off.
/// - `reading`: Continue reading and Home show it.
/// - `finished`: the ✓ on the cover. It asks the month, as Mark finished did.
/// - `not-reading`: put down. Off Continue reading and Home; the place is kept.
///
/// The hub sends one effective status on every book (`ReadingWork.status`,
/// `ReadingSectionItem.status`) and the page trusts it. A hub before it sends
/// none, and `derive` works it out the way the hub's `effectiveReadingStatus`
/// does, so the tick and Continue reading are right either way.
public enum ReadingStatus {
    public static let want = "want"
    public static let reading = "reading"
    public static let finished = "finished"
    public static let notReading = "not-reading"

    /// In the order the picker shows them.
    public static let choices = [want, reading, finished, notReading]

    /// The statuses that take a book off the Want to Read list.
    public static let offTheList: Set<String> = [reading, finished, notReading]

    public static func known(_ status: String) -> Bool { choices.contains(status) }

    public static func label(_ status: String) -> String {
        switch status {
        case want: "Want to read"
        case reading: "Reading"
        case finished: "Finished"
        case notReading: "Not reading"
        default: ""
        }
    }

    /// What choosing it does, under its name in the picker.
    public static func detail(_ status: String) -> String {
        switch status {
        case want: "Keep it on your Want to read list"
        case reading: "Show it in Continue reading"
        case finished: "Say when; puts the ✓ on its cover"
        case notReading: "Take it off Continue reading and Home; your place stays"
        default: ""
        }
    }

    // MARK: The one status of a book

    public static func of(_ work: ReadingWork) -> String { of(work.status, work.you, work.progress) }

    public static func of(_ item: ReadingSectionItem) -> String { of(item.status, nil, item.progress) }

    private static func of(_ status: String, _ you: ReadingYou?, _ progress: ReadingProgress?) -> String {
        known(status) ? status : derive(you, progress)
    }

    /// The hub's `effectiveReadingStatus`, for a hub that sends none: what the
    /// person chose, else finished (a month, an import that says read, a place
    /// at the end), then the import's to-read shelf, then reading (a place
    /// begun, or an import that says currently reading). Empty where there is
    /// nothing to say.
    public static func derive(_ you: ReadingYou?, _ progress: ReadingProgress?) -> String {
        if let chosen = you?.chosen, known(chosen) { return settle(chosen, progress) }
        if let you, !(you.finished ?? "").isEmpty || you.status == "read" { return finished }
        if progress?.completed == true { return finished }
        if you?.status == "to-read" { return want }
        if let progress, progress.percentage > 0 { return reading }
        if you?.status == "currently-reading" { return reading }
        return ""
    }

    /// The status `chosen` comes to for a book at `progress`: a book wanted and
    /// then opened is being read (a place begun, short of the end), as the
    /// Want to Read list always had it. Not reading is the one choice a place
    /// does not move.
    public static func settle(_ chosen: String, _ progress: ReadingProgress?) -> String {
        if chosen == want, let progress, progress.percentage > 0, !progress.completed { return reading }
        return chosen
    }

    /// The ✓ on a cover: read to the end, marked Finished, or imported as read.
    public static func isFinished(_ work: ReadingWork) -> Bool { of(work) == finished }

    public static func isFinished(_ item: ReadingSectionItem) -> Bool { of(item) == finished }

    /// Whether Continue reading and Home show a book: it has a place, and was
    /// neither put down nor finished. A book chosen as Reading stays after the
    /// end of it (reading it again).
    public static func continues(_ work: ReadingWork) -> Bool { continues(of(work), work.progress) }

    public static func continues(_ item: ReadingSectionItem) -> Bool { continues(of(item), item.progress) }

    private static func continues(_ status: String, _ progress: ReadingProgress?) -> Bool {
        guard let place = progress, place.percentage > 0 else { return false }
        switch status {
        case notReading, finished: return false
        case reading: return true
        default: return !place.completed
        }
    }

    // MARK: The menu row

    /// "Reading status · Reading"; just "Reading status" while the book has none.
    public static func rowLabel(_ status: String) -> String {
        let name = label(status)
        return name.isEmpty ? "Reading status" : "Reading status · \(name)"
    }

    /// Under the row: what the status means for this book.
    public static func rowDetail(_ status: String, _ you: ReadingYou?) -> String {
        switch status {
        case want: return "On your Want to read list"
        case reading: return "In Continue reading"
        case finished:
            guard let month = you?.finished.flatMap(BookPage.finishedLabel) else { return "Finished · say when" }
            return "\(month) · change the date"
        case notReading: return "Off Continue reading and Home; your place stays"
        default: return "Want to read, reading, finished or not reading"
        }
    }

    // MARK: Choosing

    public enum Action: Equatable, Sendable {
        /// It is the status the book has: nothing to do.
        case nothing
        /// Write it, and do what it does here.
        case set
        /// Finished asks the month first, finished already or not: that is how a date is put right.
        case askMonth
    }

    public static func action(_ next: String, current: String) -> Action {
        if next == finished { return .askMonth }
        return next == current ? .nothing : .set
    }

    public enum LocalRead: Equatable, Sendable { case mark, unmark, keep }

    /// What choosing a status does on this device besides writing it.
    public struct Effects: Equatable, Sendable {
        /// The book is on the Want to Read list afterwards, and off it for any other status.
        public let wantList: Bool
        /// The hub has no route that marks a book read in Kavita or Storyteller,
        /// so Finished marks it read on this device as the page's read toggle
        /// does, and leaving Finished takes that mark away.
        public let localRead: LocalRead

        public init(wantList: Bool, localRead: LocalRead) {
            self.wantList = wantList
            self.localRead = localRead
        }
    }

    public static func effects(_ next: String, wasFinished: Bool) -> Effects {
        let local: LocalRead = next == finished && !wasFinished ? .mark : next != finished && wasFinished ? .unmark : .keep
        return Effects(wantList: next == want, localRead: local)
    }

    // MARK: Under the cover

    /// What "you" says under the cover (the month finished, how many times): a
    /// book that is not finished does not carry the month it was once finished,
    /// nor an import's "read", though its count of readings stays.
    public static func youForLine(_ work: ReadingWork) -> ReadingYou? {
        guard var you = work.you else { return nil }
        if of(work) == finished { return you }
        you.finished = nil
        if you.status == "read" { you.status = nil }
        return you
    }
}
