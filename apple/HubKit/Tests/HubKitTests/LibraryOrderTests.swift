import Foundation
import Testing
@testable import HubKit

/// Arranging libraries (#15): the moves, the save queue, and the hub's shapes.
struct LibraryOrderTests {
    @Test func aMoveTakesOneOutAndPutsItInElsewhere() {
        let ids = ["a", "b", "c", "d"]
        #expect(LibraryOrder.move(ids, from: 0, to: 2) == ["b", "c", "a", "d"])
        #expect(LibraryOrder.move(ids, from: 3, to: 0) == ["d", "a", "b", "c"])
        #expect(LibraryOrder.move(ids, from: 1, to: 1) == ids)
        // Clamped to the list; nothing to move gives the list back.
        #expect(LibraryOrder.move(ids, from: 0, to: 9) == ["b", "c", "d", "a"])
        #expect(LibraryOrder.move(ids, from: 2, to: -3) == ["c", "a", "b", "d"])
        #expect(LibraryOrder.move(ids, from: 7, to: 0) == ids)
        #expect(LibraryOrder.move([], from: 0, to: 0) == [])
    }

    @Test func draggingOverAnotherTakesItsPlace() {
        let ids = ["anime", "marvel-movies", "marvel-tv", "movies", "shows"]
        #expect(LibraryOrder.move(ids, id: "shows", over: "anime") == ["shows", "anime", "marvel-movies", "marvel-tv", "movies"])
        #expect(LibraryOrder.move(ids, id: "anime", over: "movies") == ["marvel-movies", "marvel-tv", "movies", "anime", "shows"])
        #expect(LibraryOrder.move(ids, id: "gone", over: "anime") == ids)
    }

    @Test func aStepMovesOnePlaceAndStopsAtTheEnds() {
        let ids = ["a", "b", "c"]
        #expect(LibraryOrder.step(ids, id: "b", by: -1) == ["b", "a", "c"])
        #expect(LibraryOrder.step(ids, id: "b", by: 1) == ["a", "c", "b"])
        #expect(LibraryOrder.step(ids, id: "a", by: -1) == ids)
        #expect(LibraryOrder.step(ids, id: "c", by: 1) == ids)
    }

    @Test func itemsFollowTheIdsAndTheRestKeepTheirPlaceAfter() {
        let items = ["x", "a", "b", "y", "c"]
        #expect(LibraryOrder.inOrder(items, ids: ["c", "a", "b"], id: { $0 }) == ["c", "a", "b", "x", "y"])
        #expect(LibraryOrder.inOrder(items, ids: [], id: { $0 }) == items)
        #expect(LibraryOrder.inOrder(items, ids: ["gone", "b", "b"], id: { $0 }) == ["b", "x", "a", "y", "c"])
    }

    @Test func theNoticeNamesTheReason() {
        #expect(LibraryOrder.failureNotice("The Hub could not be reached")
                == "The new order could not be saved · The Hub could not be reached")
        #expect(LibraryOrder.failureNotice("  ") == "The new order could not be saved")
        #expect(LibraryOrder.isCustom("custom") && !LibraryOrder.isCustom("name") && !LibraryOrder.isCustom(""))
    }

    @Test func oneSaveAtATimeAndOnlyTheNewestWaits() {
        var queue = LibraryOrderQueue()
        #expect(queue.isIdle)
        #expect(queue.submit(["b", "a", "c"]) == ["b", "a", "c"])
        #expect(queue.submit(["b", "c", "a"]) == nil)
        #expect(queue.submit(["c", "b", "a"]) == nil)
        #expect(queue.answered(ok: true) == ["c", "b", "a"])
        #expect(queue.answered(ok: true) == nil)
        #expect(queue.isIdle)
    }

    @Test func afterAFailureNothingWaitingIsSent() {
        var queue = LibraryOrderQueue()
        _ = queue.submit(["b", "a"])
        _ = queue.submit(["a", "b"])
        #expect(queue.answered(ok: false) == nil)
        #expect(queue.isIdle)
        // Back to A to Z is an empty order, sent like any other.
        #expect(queue.submit([]) == [])
    }

    /// Android's `LibraryOrderTest` cases for `inOrder`.
    @Test func theCapsuleFollowsTheRootsOrder() {
        let views = ["films", "shows", "anime", "favorites"]
        #expect(LibraryOrder.inOrder(views, ids: ["anime", "films", "shows"], id: { $0 }) == ["anime", "films", "shows", "favorites"])
        #expect(LibraryOrder.inOrder(views, ids: ["shows", "gone", "shows", "films"], id: { $0 }) == ["shows", "films", "anime", "favorites"])
        #expect(LibraryOrder.inOrder(views, ids: [], id: { $0 }) == views)
    }

    @Test func theHubsShapes() throws {
        let media = try JSONDecoder().decode(LibraryResponse.self, from: Data(#"""
        {"views":[{"id":"v2","name":"Shows","kind":"tvshows"},{"id":"v1","name":"Anime","kind":"tvshows"}],
         "order":"custom","partial":[],"cache":{"hit":false}}
        """#.utf8))
        #expect(media.views.map(\.id) == ["v2", "v1"])
        #expect(media.order == "custom")
        // An older hub sends no order: A to Z, as it always listed them.
        let older = try JSONDecoder().decode(LibraryResponse.self, from: Data(#"{"views":[]}"#.utf8))
        #expect(older.order == "name")

        let books = try JSONDecoder().decode(ReadingLibrariesResponse.self, from: Data(#"""
        {"libraries":[{"id":"storyteller:books","source":"storyteller","kind":"books","title":"Books & Audiobooks",
                       "capabilities":["read"]},
                      {"id":"kavita:2","source":"kavita","kind":"comic","title":"Manga","artwork":"/v1/img/reading/kavita-library/2"}],
         "order":"name","partial":[],"cache":{"hit":true}}
        """#.utf8))
        #expect(books.libraries.map(\.id) == ["storyteller:books", "kavita:2"])
        #expect(books.libraries[1].title == "Manga")
        #expect(books.order == "name")

        let reply = try JSONDecoder().decode(LibraryOrderReply.self, from: Data(#"""
        {"side":"books","ids":["storyteller:books","kavita:2"],"order":"custom"}
        """#.utf8))
        #expect(reply == LibraryOrderReply(side: "books", ids: ["storyteller:books", "kavita:2"], order: "custom"))
    }

    @Test func theSaveIsAPutOfTheWholeOrder() throws {
        let request = HubEndpoints.saveLibraryOrder(side: .media, ids: ["v2", "v1"])
        #expect(request.path == "/v1/library/order")
        #expect(request.method == .put)
        #expect(!request.idempotent)
        #expect(String(decoding: try #require(request.body), as: UTF8.self) == #"{"ids":["v2","v1"],"side":"media"}"#)
        let reset = HubEndpoints.saveLibraryOrder(side: .books, ids: [])
        #expect(String(decoding: try #require(reset.body), as: UTF8.self) == #"{"ids":[],"side":"books"}"#)
        #expect(HubEndpoints.readingLibraries.path == "/v1/reading/libraries")
    }
}
