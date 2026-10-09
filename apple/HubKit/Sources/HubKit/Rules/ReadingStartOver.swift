import Foundation

/// Start over (#60): a book back to not started, in every format and on every
/// device (Android's `ReadingStartOver`).
///
/// Mark read and Mark unread only ever changed this device's marks. The place
/// itself lives in Storyteller, in Kavita, and in each device's own
/// checkpoint, outbox and kept copy, and nothing touched it.
///
/// This is the one owner of three things:
/// - when a book offers Start over;
/// - what it asks first, with the harmless answer first;
/// - what it says when it is done.
///
/// The hub does the book's side (`POST /v1/reading/works/{id}/start-over`),
/// and `ReadingResets` this device's.
public enum ReadingStartOver {
    public static let action = "Start over"
    /// The harmless answer, first.
    public static let keep = "Keep my place"
    /// Start over's line in the ⋯ menu.
    public static let menuDetail = "Forget your place and start again"

    /// Mark unread is only the undo of a finish marked here: the place stays (#60).
    public static let unmark = "Mark unread"
    public static let unmarkDetail = "Take away the finish; your place stays"
    public static let unmarked = "Finish taken away · your place stays"
    /// A finish reached by reading is taken back by Start over alone.
    public static let finishedByReading = "Finished by reading · Start over in ⋯ takes it back"

    /// Offered while the book has a place to forget or a finish to take away.
    public static func offered(hasPlace: Bool, finished: Bool) -> Bool { hasPlace || finished }

    /// Whether the book has a place: the hub says it was started, or this device `kept` one, even unsent.
    public static func hasPlace(_ work: ReadingWork, kept: Bool) -> Bool {
        if kept { return true }
        guard let progress = work.progress else { return work.continueAt != nil }
        return progress.completed || progress.percentage > 0 || work.continueAt != nil
    }

    public static func confirmTitle(_ work: ReadingWork) -> String { "Start \(work.title) over?" }

    /// What it forgets and what stays, in the words of the formats the book has.
    public static func confirmDetail(_ work: ReadingWork) -> String {
        if ReadingShelves.isComic(work) {
            return "Your place is forgotten and every issue is unread again, on every device. Your lists stay."
        }
        let names = ReadingBookFacts.formats(work).map { $0 == "readaloud" ? "read along" : $0 }
        let place: String
        switch names.count {
        case 0: place = "Your place"
        case 1: place = "Your place in the \(names[0])"
        default: place = "Your place in the \(names.dropLast().joined(separator: ", ")) and \(names.last ?? "")"
        }
        return "\(place) is forgotten on every device. Your rating, notes and bookmarks stay."
    }

    public static func done() -> String { "Started over · back to not started" }
    public static func failed(_ message: String) -> String { "Start over could not be done · \(message)" }
}

/// The answer to `POST /v1/reading/works/{id}/start-over`: the hub's stamp
/// for it, and what this profile has of the book now.
public struct ReadingStartOverResponse: Decodable, Equatable, Sendable {
    public var workId: String
    public var resetAt: Int64
    public var you: ReadingYou?

    public init(workId: String = "", resetAt: Int64 = 0, you: ReadingYou? = nil) {
        self.workId = workId
        self.resetAt = resetAt
        self.you = you
    }

    enum CodingKeys: String, CodingKey { case workId, resetAt, you }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(workId: c.value(.workId, ""), resetAt: c.value(.resetAt, 0), you: c.optional(.you))
    }
}

extension HubEndpoints {
    /// Start over (#60): the whole work back to not started, in every format, on every device.
    public static func readingStartOver(_ workId: String) -> HubRequest {
        HubRequest("/v1/reading/works/" + encode(workId) + "/start-over", method: .post, body: Data("{}".utf8))
    }
}
