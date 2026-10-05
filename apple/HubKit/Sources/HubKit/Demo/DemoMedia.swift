import Foundation
import Synchronization

/// The demo hub's libraries (`-demo`), with an order a PUT changes as the
/// real hub's does (#15): saved ids first, the rest A to Z. The names are the
/// live stack's.
enum DemoMedia {
    struct Folder {
        let id: String
        let name: String
        let kind: String
        let total: Int
    }

    static let folders = [
        Folder(id: "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1", name: "Anime", kind: "tvshows", total: 15),
        Folder(id: "b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2", name: "Marvel Movies", kind: "movies", total: 33),
        Folder(id: "c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3", name: "Marvel TV", kind: "tvshows", total: 12),
        Folder(id: "d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4", name: "Movies", kind: "movies", total: 146),
        Folder(id: "e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5", name: "Shows", kind: "tvshows", total: 43),
    ]

    static let books: [(id: String, source: String, title: String)] = [
        ("storyteller:books", "storyteller", "Books & Audiobooks"),
        ("kavita:2", "kavita", "Manga"),
        ("kavita:3", "kavita", "My Marvelous Year"),
    ]

    private static let saved = Mutex<[String: [String]]>([:])

    static func answer(method: String, path: String, query: String, body: Data?) -> DemoTransport.Answer? {
        switch (method, path) {
        case ("GET", "/v1/library"):
            let (ids, custom) = arranged(folders.map { ($0.id, $0.name) }, side: "media")
            let views = ids.compactMap { id in folders.first { $0.id == id } }.map { folder in
                #"{"id":"\#(folder.id)","name":"\#(folder.name)","kind":"\#(folder.kind)","image":"","fan":[],"total":\#(folder.total)}"#
            }
            return DemoTransport.Answer(200, #"{"views":[\#(views.joined(separator: ","))],"order":"\#(custom ? "custom" : "name")","partial":[]}"#)
        case ("GET", "/v1/reading/libraries"):
            let (ids, custom) = arranged(books.map { ($0.id, $0.title) }, side: "books")
            let list = ids.compactMap { id in books.first { $0.id == id } }.map { book in
                #"{"id":"\#(book.id)","source":"\#(book.source)","kind":"books","title":"\#(book.title)","capabilities":[]}"#
            }
            return DemoTransport.Answer(200, #"{"libraries":[\#(list.joined(separator: ","))],"order":"\#(custom ? "custom" : "name")","partial":[]}"#)
        case ("PUT", "/v1/library/order"):
            guard let body, let fields = try? JSONSerialization.jsonObject(with: body) as? [String: Any],
                  let side = fields["side"] as? String, side == "media" || side == "books",
                  let ids = fields["ids"] as? [String] else {
                return DemoTransport.Answer(400, #"{"error":{"code":"bad_request","message":"Send a side and its ids"}}"#)
            }
            saved.withLock { $0[side] = ids }
            let quoted = ids.map { #""\#($0)""# }.joined(separator: ",")
            return DemoTransport.Answer(200, #"{"side":"\#(side)","ids":[\#(quoted)],"order":"\#(ids.isEmpty ? "name" : "custom")"}"#)
        default:
            return DemoDiscover.answer(method: method, path: path, query: query)
        }
    }

    /// The hub's rule: saved ids first in their order, the rest A to Z by name.
    private static func arranged(_ items: [(id: String, name: String)], side: String) -> ([String], Bool) {
        let order = saved.withLock { $0[side] ?? [] }
        let known = Set(items.map(\.id))
        let first = order.filter { known.contains($0) }
        let rest = items.filter { !first.contains($0.id) }
            .sorted { $0.name.lowercased() < $1.name.lowercased() }.map(\.id)
        return (first + rest, !first.isEmpty)
    }
}
