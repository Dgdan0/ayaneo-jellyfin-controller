import Foundation
import Synchronization

/// The demo hub's ebook routes (#25, phase 4), answered as the hub answers
/// them (`hub/internal/api/reading_epub.go`): `…/file` sends a demo book's
/// EPUB (`DemoEpub`), and `…/position` keeps its place, refusing a place
/// based on one another device has since moved with 409, as the hub does.
/// The demo's books are the Books demo's (`DemoReading`): any work with an
/// ebook edition, under its edition's id or the id its series lists it by.
/// Nothing real is read or written.
public enum DemoBooks {
    struct Book {
        let workId: String
        let title: String
        let author: String
        let progress: Double
        let sourceItemIds: Set<String>
    }

    private struct Place {
        var locator: String?
        var timestamp: Int64
        var updatedAt: String
    }

    private static let places = Mutex<[String: Place]>([:])
    private static let files = Mutex<[String: Data]>([:])

    static func answer(method: String, path: String, query: String, body: Data?) -> DemoTransport.Answer? {
        let parts = path.split(separator: "/").map(String.init)
        guard parts.count == 7, parts[0] == "v1", parts[1] == "reading", parts[2] == "works", parts[4] == "publications",
              parts[6] == "file" || parts[6] == "position", let book = book(parts[3]) else { return nil }
        let sourceItemId = parts[5]
        guard book.sourceItemIds.contains(sourceItemId) else {
            return failure(404, "not_found", "publication does not belong to this work")
        }
        switch (method, parts[6]) {
        case ("GET", "file"):
            let format = value("format", in: query) ?? ""
            guard format.isEmpty || format == "ebook" else { return failure(404, "not_found", "Not in the demo hub yet") }
            return DemoTransport.Answer(200, data: epub(book), type: "application/epub+zip")
        case ("GET", "position"):
            let place = place(book, sourceItemId)
            var fields: [String: Any] = ["workId": book.workId, "sourceItemId": sourceItemId,
                                         "locator": place.locator.flatMap(BookLocator.object) ?? NSNull()]
            if place.timestamp > 0 { fields["timestamp"] = place.timestamp }
            if !place.updatedAt.isEmpty { fields["updatedAt"] = place.updatedAt }
            return json(fields)
        case ("POST", "position"):
            return save(book, sourceItemId, body: body)
        default:
            return nil
        }
    }

    /// Another device moved the place (for the tests and the demo): the next
    /// place sent from this one, based on the old, is refused with 409.
    public static func moveElsewhere(workId: String, sourceItemId: String, locator: String?) {
        let key = workId + "/" + sourceItemId
        places.withLock { $0[key] = Place(locator: locator, timestamp: 1_790_000_000_000, updatedAt: "2026-10-06 09:00:00") }
    }

    /// The place the demo hub keeps for a book, as JSON, for the tests.
    public static func place(workId: String, sourceItemId: String) -> String? {
        guard let book = book(workId) else { return nil }
        return place(book, sourceItemId).locator
    }

    // MARK: Plumbing

    private static func save(_ book: Book, _ sourceItemId: String, body: Data?) -> DemoTransport.Answer {
        let known: Set<String> = ["locator", "timestamp", "checkBase", "expectedLocator"]
        guard let body, let fields = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any],
              Set(fields.keys).isSubset(of: known), let object = fields["locator"] as? [String: Any],
              let locator = BookLocator.canonical(object), BookLocator.valid(locator) else {
            return failure(400, "invalid_request", "invalid EPUB reading position")
        }
        let key = book.workId + "/" + sourceItemId
        let current = place(book, sourceItemId)
        if fields["checkBase"] as? Bool == true {
            let expected = (fields["expectedLocator"] as? [String: Any]).flatMap(BookLocator.canonical)
            guard BookLocator.same(expected, current.locator) else {
                return failure(409, "reading_position_conflict",
                               "Reading progress changed on another device. Choose which position to continue from.")
            }
        }
        let timestamp = (fields["timestamp"] as? NSNumber)?.int64Value ?? 0
        places.withLock {
            $0[key] = Place(locator: locator, timestamp: timestamp > 0 ? timestamp : Int64(Date().timeIntervalSince1970 * 1_000),
                            updatedAt: "")
        }
        return json(["ok": true, "action": "save_epub_position"])
    }

    /// The place kept, or, the first time, where the Books demo says the book is read to.
    private static func place(_ book: Book, _ sourceItemId: String) -> Place {
        let key = book.workId + "/" + sourceItemId
        return places.withLock { places in
            if let place = places[key] { return place }
            let seeded = book.progress > 0
                ? BookLocator.canonical(DemoEpub.locator(identifier: book.workId, at: book.progress)) : nil
            let place = Place(locator: seeded, timestamp: seeded == nil ? 0 : 1_789_000_000_000,
                              updatedAt: seeded == nil ? "" : "2026-10-04 21:16:47")
            places[key] = place
            return place
        }
    }

    private static func epub(_ book: Book) -> Data {
        if let made = files.withLock({ $0[book.workId] }) { return made }
        let made = DemoEpub.make(title: book.title, author: book.author, identifier: book.workId)
        files.withLock { $0[book.workId] = made }
        return made
    }

    /// A demo work with an ebook edition, as the Books demo describes it.
    static func book(_ workId: String) -> Book? {
        guard let work = fields("/v1/reading/works/" + HubEndpoints.encode(workId)) else { return nil }
        let editions = work["editions"] as? [[String: Any]] ?? []
        var ids = Set(editions.filter { $0["kind"] as? String == "ebook" }.compactMap { $0["sourceItemId"] as? String })
        guard !ids.isEmpty else { return nil }
        // The id its series lists it by, which a series' Resume reading opens.
        if let seriesId = work["seriesId"] as? String, !seriesId.isEmpty,
           let series = fields("/v1/reading/works/" + HubEndpoints.encode(seriesId)) {
            for section in series["sections"] as? [[String: Any]] ?? [] {
                for item in section["items"] as? [[String: Any]] ?? [] where item["workId"] as? String == workId {
                    if let id = item["sourceItemId"] as? String, !id.isEmpty { ids.insert(id) }
                }
            }
        }
        let progress = ((work["progress"] as? [String: Any])?["percentage"] as? NSNumber)?.doubleValue ?? 0
        return Book(workId: workId, title: work["title"] as? String ?? "", author: (work["authors"] as? [String] ?? []).first ?? "",
                    progress: progress, sourceItemIds: ids)
    }

    private static func fields(_ path: String) -> [String: Any]? {
        guard let answer = DemoReading.answer(method: "GET", path: path, query: "", body: nil), answer.status == 200
        else { return nil }
        return (try? JSONSerialization.jsonObject(with: answer.body)) as? [String: Any]
    }

    private static func json(_ fields: [String: Any]) -> DemoTransport.Answer {
        DemoTransport.Answer(200, data: (try? JSONSerialization.data(withJSONObject: fields)) ?? Data("{}".utf8),
                             type: "application/json")
    }

    private static func failure(_ status: Int, _ code: String, _ message: String) -> DemoTransport.Answer {
        DemoTransport.Answer(status, #"{"error":{"code":"\#(code)","message":"\#(message)"}}"#)
    }

    private static func value(_ name: String, in query: String) -> String? {
        query.split(separator: "&").first { $0.hasPrefix(name + "=") }.map { String($0.dropFirst(name.count + 1)) }
    }
}
