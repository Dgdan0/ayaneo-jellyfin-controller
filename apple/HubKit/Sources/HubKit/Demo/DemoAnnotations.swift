import Foundation
import Synchronization

/// The demo hub's highlights and notes (#62), kept as the hub keeps them
/// (`reading_annotations.go`, `reading_annotations_store.go`): last write wins
/// on `updatedAt`, every stored version stamped `syncedAt` on the hub's own
/// clock, tombstones kept, and the same refusals. For the launch only, as the
/// demo's places are; the UI tests' only annotations, never a real book's.
enum DemoAnnotations {
    private struct State {
        var books: [String: [String: ReadingAnnotation]] = [:]
        var clock: Int64 = 0
    }

    private static let state = Mutex(State())
    static let limit = 2000
    private static let fields: Set<String> = ["id", "color", "note", "document", "quote", "locator", "createdAt", "updatedAt"]

    static func answer(method: String, path: String, query: String, body: Data?) -> DemoTransport.Answer? {
        let parts = path.split(separator: "/").map(String.init)
        // v1 reading works {id} annotations [{annotationId}]
        guard parts.count >= 5, parts[0] == "v1", parts[1] == "reading", parts[2] == "works", parts[4] == "annotations" else {
            return nil
        }
        let workId = parts[3].removingPercentEncoding ?? parts[3]
        guard DemoReading.knows(workId) else { return failure(404, "not_found", "No such book") }
        let id = parts.count > 5 ? (parts[5].removingPercentEncoding ?? parts[5]) : ""
        switch (method, parts.count) {
        case ("GET", 5): return list(workId, query: query)
        case ("POST", 5), ("PUT", 6): return write(workId, id: id, body: body)
        case ("DELETE", 6): return delete(workId, id: id, query: query)
        default: return failure(405, "method_not_allowed", "Not here")
        }
    }

    private static func now() -> Int64 { Int64(Date().timeIntervalSince1970 * 1_000) }

    private static func list(_ workId: String, query: String) -> DemoTransport.Answer {
        let raw = value("since", in: query)
        let since = raw.isEmpty ? nil : Int64(raw)
        if !raw.isEmpty && (since == nil || since! < 0) { return failure(400, "invalid_request", "since is a syncedAt, a number") }
        let annotations = state.withLock { state -> [ReadingAnnotation] in
            let book = state.books[workId].map { Array($0.values) } ?? []
            if let since { return book.filter { $0.syncedAt > since }.sorted { $0.syncedAt < $1.syncedAt } }
            return book.filter { !$0.deleted }.sorted { $0.createdAt != $1.createdAt ? $0.createdAt < $1.createdAt : $0.id < $1.id }
        }
        return json(["workId": .string(workId), "annotations": .array(annotations.map(encode)), "serverTime": .int(now())])
    }

    private static func write(_ workId: String, id pathId: String, body: Data?) -> DemoTransport.Answer {
        func bad(_ message: String) -> DemoTransport.Answer { failure(400, "invalid_request", message) }
        guard let body, let object = try? JSONSerialization.jsonObject(with: body) as? [String: Any],
              Set(object.keys).isSubset(of: fields) else {
            return bad("body must be a highlight: color, document, quote and, when it has one, note")
        }
        var id = pathId
        let given = object["id"] as? String ?? ""
        if !id.isEmpty && !AnnotationIds.valid(id) { return bad("invalid highlight id") }
        if !id.isEmpty && !given.isEmpty && given != id { return bad("the highlight's id is not the path's") }
        if id.isEmpty { id = given }
        if id.isEmpty { id = AnnotationIds.new() }
        guard AnnotationIds.valid(id) else { return bad("invalid highlight id") }
        let color = object["color"] as? String ?? ""
        guard HighlightColor(rawValue: color) != nil else { return bad("color is yellow, blue, pink or green") }
        let document = (object["document"] as? String ?? "").split(separator: "#", maxSplits: 1, omittingEmptySubsequences: false)
            .first.map { String($0.drop { $0 == "/" }) } ?? ""
        guard !document.isEmpty, document.utf8.count <= 500, !document.split(separator: "/", omittingEmptySubsequences: false)
            .contains(where: { $0 == ".." || $0.isEmpty }) else { return bad("document is a path inside the book") }
        let quoted = object["quote"] as? [String: Any] ?? [:]
        let quote = AnnotationQuote(before: quoted["before"] as? String ?? "", highlight: quoted["highlight"] as? String ?? "",
                                    after: quoted["after"] as? String ?? "")
        guard !quote.highlight.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return bad("a highlight needs the text it marks")
        }
        let note = object["note"] as? String ?? ""
        guard note.utf8.count <= 4000, quote.highlight.utf8.count <= 2000, quote.before.utf8.count <= 1000,
              quote.after.utf8.count <= 1000 else { return bad("the note or the quote is too long") }
        var locator: JSONValue?
        if let hint = object["locator"], !(hint is NSNull) {
            guard let fields = hint as? [String: Any], let data = try? JSONSerialization.data(withJSONObject: fields),
                  data.count <= 8 * 1024, let decoded = try? JSONDecoder().decode(JSONValue.self, from: data) else {
                return bad("locator is Readium's, an object of at most 8 KB")
            }
            locator = decoded
        }
        let clock = now()
        let asked = (object["updatedAt"] as? NSNumber)?.int64Value ?? 0
        let stamp = asked <= 0 || asked > clock + 10 * 60 * 1_000 ? clock : asked
        let created = min(max((object["createdAt"] as? NSNumber)?.int64Value ?? 0, 0), stamp)
        let incoming = ReadingAnnotation(id: id, color: color, note: note, document: document, quote: quote, locator: locator,
                                         createdAt: created, updatedAt: stamp)
        return keep(workId, incoming, now: clock)
    }

    private static func delete(_ workId: String, id: String, query: String) -> DemoTransport.Answer {
        guard AnnotationIds.valid(id) else { return failure(400, "invalid_request", "invalid highlight id") }
        let clock = now()
        var stamp = clock
        let raw = value("updatedAt", in: query)
        if !raw.isEmpty {
            guard let parsed = Int64(raw), parsed > 0 else {
                return failure(400, "invalid_request", "updatedAt is a time in milliseconds")
            }
            stamp = min(parsed, clock)
        }
        return keep(workId, ReadingAnnotation(id: id, color: "", createdAt: stamp, updatedAt: stamp, deleted: true), now: clock)
    }

    private static func keep(_ workId: String, _ incoming: ReadingAnnotation, now clock: Int64) -> DemoTransport.Answer {
        enum Outcome { case kept(ReadingAnnotation, Bool), full }
        let outcome = state.withLock { state -> Outcome in
            var book = state.books[workId] ?? [:]
            var incoming = incoming
            if let held = book[incoming.id] {
                if incoming.updatedAt <= held.updatedAt { return .kept(held, false) }
                incoming.createdAt = held.createdAt
                // A tombstone that was never given an anchor of its own keeps the one it had.
                if incoming.deleted && incoming.document.isEmpty {
                    incoming.document = held.document
                    incoming.quote = held.quote
                    incoming.locator = held.locator
                    incoming.color = held.color
                }
            } else if !incoming.deleted && book.values.filter({ !$0.deleted }).count >= limit {
                return .full
            }
            if incoming.createdAt == 0 { incoming.createdAt = incoming.updatedAt }
            incoming.syncedAt = max(clock, state.clock + 1)
            state.clock = incoming.syncedAt
            book[incoming.id] = incoming
            state.books[workId] = book
            return .kept(incoming, true)
        }
        switch outcome {
        case .full:
            return failure(409, "annotation_limit", "This book holds the most highlights it can keep")
        case .kept(let held, let applied):
            return json(["workId": .string(workId), "annotation": encode(held), "applied": .bool(applied)])
        }
    }

    private static func encode(_ annotation: ReadingAnnotation) -> JSONValue {
        (try? JSONDecoder().decode(JSONValue.self, from: JSONEncoder().encode(annotation))) ?? .null
    }

    private static func json(_ fields: [String: JSONValue]) -> DemoTransport.Answer {
        DemoTransport.Answer(200, data: JSONValue.object(fields).encoded(), type: "application/json")
    }

    private static func failure(_ status: Int, _ code: String, _ message: String) -> DemoTransport.Answer {
        DemoTransport.Answer(status, #"{"error":{"code":"\#(code)","message":"\#(message)"}}"#)
    }

    private static func value(_ name: String, in query: String) -> String {
        for pair in query.split(separator: "&") {
            let kv = pair.split(separator: "=", maxSplits: 1).map(String.init)
            if kv.first == name { return kv.count > 1 ? (kv[1].removingPercentEncoding ?? kv[1]) : "" }
        }
        return ""
    }
}
