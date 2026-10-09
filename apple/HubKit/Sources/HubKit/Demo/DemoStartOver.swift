import Foundation
import Synchronization

/// The demo hub's Start over (#60), as `hub/internal/api/reading_startover.go`
/// answers it.
///
/// `POST /v1/reading/works/{id}/start-over` keeps a stamp for the work and:
/// - forgets its places: the ebook's, the audiobook's and a comic's pages;
/// - takes away the finished month;
/// - answers the stamp.
///
/// The work's page, both position routes and a comic's page list then say
/// `resetAt`, and its progress is gone from every list. A write that names an
/// older start over (`resetSeen`) is refused as `reading_position_reset`.
/// Nothing real is read or written.
enum DemoStartOver {
    private static let stamps = Mutex<[String: Int64]>([:])

    /// When `workId` was last started over, 0 for never.
    static func stamp(_ workId: String) -> Int64 {
        stamps.withLock { $0[workId] } ?? 0
    }

    /// A write made from a place since started over: it names a start over
    /// older than the work's. One that names none is not checked, as the hub
    /// does not check an older app's.
    static func refuses(_ workId: String, _ fields: [String: Any]) -> Bool {
        let at = stamp(workId)
        guard at > 0, let seen = (fields["resetSeen"] as? NSNumber)?.int64Value else { return false }
        return seen < at
    }

    static func refusal() -> DemoTransport.Answer {
        let body: [String: Any] = ["error": ["code": ReadingResets.code,
                                             "message": "This book was started over on another device. Your old place is gone."]]
        return DemoTransport.Answer(409, data: (try? JSONSerialization.data(withJSONObject: body)) ?? Data("{}".utf8),
                                    type: "application/json")
    }

    static func answer(method: String, path: String, body: Data?) -> DemoTransport.Answer? {
        let parts = path.split(separator: "/").map(String.init)
        guard method == "POST", parts.count == 5, parts[0] == "v1", parts[1] == "reading", parts[2] == "works",
              parts[4] == "start-over" else { return nil }
        let workId = parts[3]
        guard DemoReading.knows(workId) else {
            let failure: [String: Any] = ["error": ["code": "not_found", "message": "reading work not found"]]
            return DemoTransport.Answer(404, data: (try? JSONSerialization.data(withJSONObject: failure)) ?? Data("{}".utf8),
                                        type: "application/json")
        }
        let at = stamps.withLock { all -> Int64 in
            let next = max(Int64(Date().timeIntervalSince1970 * 1_000), (all[workId] ?? 0) + 1)
            all[workId] = next
            return next
        }
        DemoBooks.forget(workId: workId)
        DemoReading.forgetListening(workId: workId)
        DemoComics.forget(workId: workId)
        let you = DemoBookPage.unfinish(workId: workId)
        let reply: [String: Any] = ["ok": true, "action": "start_over", "workId": workId, "resetAt": at, "you": you ?? NSNull()]
        return DemoTransport.Answer(200, data: (try? JSONSerialization.data(withJSONObject: reply)) ?? Data("{}".utf8),
                                    type: "application/json")
    }
}
