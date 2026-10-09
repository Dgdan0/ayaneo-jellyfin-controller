import Foundation
import Synchronization

/// The demo hub's book page (#39), as the hub's contract has it: what readers
/// make of a book (`community`), the profile's own reading of it (`you`,
/// from a pretend Goodreads export) and its genres, on the book's own page
/// only; and `PATCH …/you`, checked as the hub checks it. What is set here
/// lasts for the launch, as the demo's places do.
enum DemoBookPage {
    struct Community {
        let rating: Double
        let count: Int?
        let source: String
    }

    struct You: Equatable {
        var rating: Int?
        var finished: String?
        var readCount: Int?
        var shelves: [String] = []
        var status: String?
        var source = "goodreads"
        /// The reading status chosen in an app (#63).
        var chosen: String?

        var isEmpty: Bool {
            rating == nil && finished == nil && readCount == nil && status == nil && shelves.isEmpty && chosen == nil
        }

        var reading: ReadingYou {
            ReadingYou(rating: rating, finished: finished, readCount: readCount, shelves: shelves, status: status, source: source,
                       chosen: chosen)
        }
    }

    static let community: [String: Community] = [
        "rw_demo_darkmatter": Community(rating: 4.12, count: 512_340, source: "hardcover"),
        "rw_demo_rr6": Community(rating: 4.47, count: 98_211, source: "hardcover"),
        "rw_demo_alloy": Community(rating: 4.2, count: nil, source: "goodreads"),
    ]

    static let genres: [String: [String]] = [
        "rw_demo_darkmatter": ["Science fiction", "Thriller", "Multiverse"],
        "rw_demo_rr6": ["Science fiction", "Space opera", "Dystopia"],
        "rw_demo_alloy": ["Fantasy", "Steampunk", "Magic systems"],
    ]

    /// The pretend export: The Alloy of Law read twice and rated, Dark Matter shelved.
    private static let imported: [String: You] = [
        "rw_demo_alloy": You(rating: 4, finished: "2025-09", readCount: 2, shelves: ["cosmere", "favorites"], status: "read"),
        "rw_demo_darkmatter": You(shelves: ["favorites"], status: "currently-reading"),
    ]

    private static let yours = Mutex<[String: You]>(imported)

    /// The book's own page gains them; nothing else does.
    static func decorate(_ fields: [String: Any], workId: String) -> [String: Any] {
        var out = fields
        if let found = community[workId] {
            var value: [String: Any] = ["rating": found.rating, "source": found.source]
            if let count = found.count { value["count"] = count }
            out["community"] = value
        }
        if let more = genres[workId] { out["genres"] = more }
        if let you = yours.withLock({ $0[workId] }), !you.isEmpty { out["you"] = answer(you) }
        return out
    }

    /// A book's reading status as the hub stamps it on every work and series
    /// book (#63, `effectiveReadingStatus`, which HubKit's `derive` mirrors);
    /// empty where there is nothing to say.
    static func status(workId: String, percentage: Double, completed: Bool = false) -> String {
        let you = yours.withLock { $0[workId] }?.reading
        let place = percentage > 0 || completed ? ReadingProgress(percentage: percentage, completed: completed) : nil
        return ReadingStatus.derive(you, place)
    }

    /// `PATCH /v1/reading/works/{id}/you`: a key present sets, null clears,
    /// an absent one is left; numbers must be numbers; a month after this one
    /// is refused; a first finish counts one read.
    static func patch(workId: String, body: Data?, known: Bool) -> DemoTransport.Answer {
        guard known else { return failure(404, "not_found", "No such book") }
        guard let body, let object = try? JSONSerialization.jsonObject(with: body) as? [String: Any], !object.isEmpty,
              Set(object.keys).isSubset(of: ["rating", "finished", "readCount", "status"]) else {
            return failure(400, "invalid_request", "Send rating, finished, readCount or status")
        }
        var next = yours.withLock { $0[workId] } ?? You(source: "app")
        if let value = object["rating"] {
            if value is NSNull {
                next.rating = nil
            } else if let number = value as? NSNumber, !isBool(number), let rating = Int(exactly: number), (1...5).contains(rating) {
                next.rating = rating
            } else {
                return failure(400, "invalid_request", "rating is a number from 1 to 5")
            }
        }
        if let value = object["readCount"] {
            if value is NSNull {
                next.readCount = nil
            } else if let number = value as? NSNumber, !isBool(number), let count = Int(exactly: number), (1...99).contains(count) {
                next.readCount = count
            } else {
                return failure(400, "invalid_request", "readCount is a number from 1 to 99")
            }
        }
        if let value = object["finished"] {
            if value is NSNull {
                next.finished = nil
                if next.status == "read" { next.status = nil }
            } else if let month = value as? String, let date = BookPage.FinishDate(month),
                      date.year >= 1900, date == date.clamped(to: BookPage.FinishDate.current()) {
                next.finished = date.value
                if object["readCount"] == nil && next.readCount == nil { next.readCount = 1 }
                if next.status != "currently-reading" { next.status = "read" }
            } else {
                return failure(400, "invalid_request", "finished is a month, YYYY-MM, no later than this one")
            }
        }
        if let value = object["status"] {
            if value is NSNull {
                next.chosen = nil
            } else if let status = value as? String, ReadingStatus.known(status) {
                next.chosen = status
                // Finished by status is this month and one time read, as Mark finished was (#63).
                if status == ReadingStatus.finished && object["finished"] == nil && next.finished == nil {
                    next.finished = BookPage.FinishDate.current().value
                    if next.readCount == nil { next.readCount = 1 }
                    if next.status != "currently-reading" { next.status = "read" }
                }
            } else {
                return failure(400, "invalid_request", "status is want, reading, finished or not-reading")
            }
        }
        next.source = "app"
        let kept = next
        yours.withLock { $0[workId] = kept }
        var reply: [String: Any] = ["workId": workId, "you": kept.isEmpty ? NSNull() as Any : answer(kept) as Any]
        let progress = DemoReading.progressOf(workId)
        let status = ReadingStatus.derive(kept.reading, progress)
        if !status.isEmpty { reply["status"] = status }
        return DemoTransport.Answer(200, data: (try? JSONSerialization.data(withJSONObject: reply)) ?? Data("{}".utf8),
                                    type: "application/json")
    }

    /// Start over (#60): the finished month goes, and nothing else of you. What is left, nil for nothing.
    static func unfinish(workId: String) -> [String: Any]? {
        let kept = yours.withLock { all -> You? in
            guard var you = all[workId] else { return nil }
            you.finished = nil
            if you.status == "read" { you.status = nil }
            // A status chosen as finished goes with the finish (#63); the others say nothing of the place and stay.
            if you.chosen == ReadingStatus.finished { you.chosen = nil }
            all[workId] = you
            return you
        }
        guard let kept, !kept.isEmpty else { return nil }
        return answer(kept)
    }

    private static func answer(_ you: You) -> [String: Any] {
        var out: [String: Any] = ["shelves": you.shelves, "source": you.source]
        if let rating = you.rating { out["rating"] = rating }
        if let finished = you.finished { out["finished"] = finished }
        if let count = you.readCount { out["readCount"] = count }
        if let status = you.status { out["status"] = status }
        if let chosen = you.chosen { out["chosen"] = chosen }
        return out
    }

    private static func isBool(_ number: NSNumber) -> Bool {
        CFGetTypeID(number) == CFBooleanGetTypeID()
    }

    private static func failure(_ status: Int, _ code: String, _ message: String) -> DemoTransport.Answer {
        DemoTransport.Answer(status, #"{"error":{"code":"\#(code)","message":"\#(message)"}}"#)
    }
}
