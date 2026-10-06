import Foundation
import ImageIO
import Testing
@testable import HubKit

/// The page reader's routes, its answers and the place it sends (#25, phase 3),
/// and the demo hub's comics that answer them as the hub does.
struct ComicRoutesTests {
    @Test func thePublicationItsPagesAndTheirThumbnailsAreAskedForByTheirIds() {
        let manifest = HubEndpoints.readingPublication(workId: "rw_9f2", sourceItemId: "8358")
        #expect(manifest.path == "/v1/reading/works/rw_9f2/publications/8358")
        #expect(manifest.method == .get && manifest.slow)
        #expect(HubEndpoints.readingPublicationPage(workId: "rw_9f2", sourceItemId: "8358", page: 4)
                == "/v1/reading/works/rw_9f2/publications/8358/pages/4")
        #expect(HubEndpoints.readingPublicationPage(workId: "rw 9", sourceItemId: "a/b", page: -1)
                == "/v1/reading/works/rw%209/publications/a%2Fb/pages/0")
        #expect(HubEndpoints.readingPublicationThumb(workId: "rw_9f2", sourceItemId: "8358", page: 4, width: 240)
                == "/v1/reading/works/rw_9f2/publications/8358/pages/4/thumb?w=240")
        // The hub keeps a thumbnail between 64 and 512 across.
        #expect(HubEndpoints.readingPublicationThumb(workId: "w", sourceItemId: "1", page: 0, width: 20).hasSuffix("?w=64"))
        #expect(HubEndpoints.readingPublicationThumb(workId: "w", sourceItemId: "1", page: 0, width: 900).hasSuffix("?w=512"))
    }

    @Test func thePlaceIsSentWithThePageTheHubLastHad() {
        let save = HubEndpoints.saveReadingPublicationProgress(workId: "rw_9f2", sourceItemId: "8358",
                                                               ReadingPublicationProgressBody(pageIndex: 5, expectedPage: 3))
        #expect(save.path == "/v1/reading/works/rw_9f2/publications/8358/progress")
        #expect(save.method == .post && !save.idempotent)
        #expect(save.body.map { String(decoding: $0, as: UTF8.self) } == #"{"expectedPage":3,"pageIndex":5}"#)
        // Without one, the field is left out: the hub refuses fields it does not know, never a missing one.
        let unconditional = HubEndpoints.saveReadingPublicationProgress(workId: "w", sourceItemId: "1",
                                                                        ReadingPublicationProgressBody(pageIndex: 5))
        #expect(unconditional.body.map { String(decoding: $0, as: UTF8.self) } == #"{"pageIndex":5}"#)
    }

    @Test func theManifestReadsAsTheHubWritesItAndDefaultsWhatIsMissing() throws {
        let json = #"""
        {"workId":"rw_9f2","source":"kavita","sourceItemId":"8358","kind":"manga","title":"Chapter 11",
         "seriesTitle":"Chainsaw Man","number":"11","pageCount":3,"currentPage":7,"direction":"rtl",
         "pages":[{"index":0,"width":1400,"height":2000},{"index":1,"width":2800,"height":2000,"isWide":true},{"index":2}],
         "doublePairs":{},"previousSourceItemId":"8357","nextSourceItemId":"8359"}
        """#
        let manifest = try JSONDecoder().decode(ReadingPublicationManifest.self, from: Data(json.utf8))
        #expect(manifest.kind == "manga" && manifest.pageCount == 3 && manifest.nextSourceItemId == "8359")
        #expect(manifest.pages[1].isWide && manifest.pages[2].width == 0)
        #expect(manifest.dimensions.map(\.isWide) == [false, true, false])
        // A place past the end opens on the last page.
        #expect(manifest.startPage == 2)
        #expect(manifest.pageDirection(chosen: nil) == .rtl)
        #expect(manifest.pageDirection(chosen: "ltr") == .ltr)
        let bare = try JSONDecoder().decode(ReadingPublicationManifest.self, from: Data("{}".utf8))
        #expect(bare.kind == "comic" && bare.direction == "ltr" && bare.pages.isEmpty && bare.startPage == 0)
    }
}

/// What the reader sends, and when (`ComicProgressOutbox`).
struct ComicProgressTests {
    @Test func openingOnTheHubsOwnPageSendsNothing() {
        var outbox = ComicProgressOutbox(saved: 3)
        outbox.show(3)
        #expect(!outbox.pending)
        #expect(outbox.next() == nil)
    }

    @Test func aNewPageIsSentOneAtATimeWithThePageTheHubHas() {
        var outbox = ComicProgressOutbox(saved: 3)
        outbox.show(5)
        #expect(outbox.pending)
        #expect(outbox.next() == ReadingPublicationProgressBody(pageIndex: 5, expectedPage: 3))
        // One on its way: the next waits for it.
        outbox.show(6)
        #expect(outbox.next() == nil)
        outbox.answered(ok: true)
        #expect(outbox.saved == 5)
        #expect(outbox.next() == ReadingPublicationProgressBody(pageIndex: 6, expectedPage: 5))
        outbox.answered(ok: true)
        #expect(!outbox.pending && outbox.next() == nil)
    }

    @Test func goingBackToTheSavedPageWhileASaveIsOnItsWaySendsItAgain() {
        var outbox = ComicProgressOutbox(saved: 3)
        outbox.show(5)
        _ = outbox.next()
        outbox.show(3)
        outbox.answered(ok: true)
        #expect(outbox.next() == ReadingPublicationProgressBody(pageIndex: 3, expectedPage: 5))
        // Back to the saved page before anything was sent: nothing goes.
        var unsent = ComicProgressOutbox(saved: 3)
        unsent.show(5)
        unsent.show(3)
        #expect(unsent.next() == nil)
    }

    @Test func aPlaceMovedElsewhereStopsTheSavesAndAFailureTriesAgain() {
        var outbox = ComicProgressOutbox(saved: 3)
        outbox.show(5)
        _ = outbox.next()
        outbox.answered(ok: false, conflict: true)
        #expect(outbox.conflicted)
        outbox.show(6)
        #expect(!outbox.pending && outbox.next() == nil)

        var offline = ComicProgressOutbox(saved: 3)
        offline.show(5)
        _ = offline.next()
        offline.answered(ok: false)
        #expect(offline.next() == ReadingPublicationProgressBody(pageIndex: 5, expectedPage: 3))
    }
}

/// The demo hub's comics answer as the hub's routes do, so the reader's UI
/// tests prove what it sends without touching a real place in a real issue.
struct DemoComicsTests {
    private let hub = HubClient(credentials: HubCredentials(baseURL: DemoTransport.address, token: DemoTransport.token),
                                screens: DemoTransport(), sleep: { _ in })

    private func size(_ data: Data) -> (Int, Int)? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? Int,
              let height = properties[kCGImagePropertyPixelHeight] as? Int else { return nil }
        return (width, height)
    }

    @Test func anIssueHasItsPagesItsPlaceAndTheIssuesEitherSide() async throws {
        let issue = try await hub.fetch(HubEndpoints.readingPublication(workId: "rw_demo_ff", sourceItemId: "rw_demo_ff-51"),
                                        as: ReadingPublicationManifest.self)
        #expect(issue.seriesTitle == "Fantastic Four" && issue.title == "51" && issue.number == "51")
        #expect(issue.pageCount == 24 && issue.pages.count == 24)
        #expect(issue.currentPage == 1)
        #expect(issue.direction == "ltr" && issue.source == "kavita")
        // The volumes run on into each other.
        #expect(issue.previousSourceItemId == "rw_demo_ff-12" && issue.nextSourceItemId == "rw_demo_ff-52")
        // The middle page is a spread.
        #expect(issue.pages[12].isWide && !issue.pages[11].isWide)
        let manga = try await hub.fetch(HubEndpoints.readingPublication(workId: "rw_demo_csm", sourceItemId: "rw_demo_csm-1"),
                                        as: ReadingPublicationManifest.self)
        #expect(manga.direction == "rtl" && manga.previousSourceItemId.isEmpty && manga.nextSourceItemId == "rw_demo_csm-2")
        await #expect(throws: HubFailure.self) {
            _ = try await hub.fetch(HubEndpoints.readingPublication(workId: "rw_demo_ff", sourceItemId: "rw_demo_ff-99"),
                                    as: ReadingPublicationManifest.self)
        }
    }

    @Test func pagesAndThumbnailsArePicturesOfTheirSize() async throws {
        let page = try await hub.image(HubEndpoints.readingPublicationPage(workId: "rw_demo_ff", sourceItemId: "rw_demo_ff-52",
                                                                           page: 3))
        #expect(size(page).map { $0 == (1_000, 1_538) } == true)
        let spread = try await hub.image(HubEndpoints.readingPublicationPage(workId: "rw_demo_ff",
                                                                             sourceItemId: "rw_demo_ff-52", page: 12))
        #expect(size(spread).map { $0 == (2_000, 1_538) } == true)
        let thumb = try await hub.image(HubEndpoints.readingPublicationThumb(workId: "rw_demo_ff", sourceItemId: "rw_demo_ff-52",
                                                                             page: 3, width: 240))
        #expect(size(thumb).map { $0.0 == 240 } == true)
        await #expect(throws: HubFailure.self) {
            _ = try await hub.image(HubEndpoints.readingPublicationPage(workId: "rw_demo_ff", sourceItemId: "rw_demo_ff-52",
                                                                        page: 24))
        }
    }

    @Test func thePaperRoundADemoPageIsFoundOnItsThumbnail() async throws {
        let thumb = try await hub.image(HubEndpoints.readingPublicationThumb(workId: "rw_demo_csm", sourceItemId: "rw_demo_csm-2",
                                                                             page: 3, width: PageBounds.thumbWidth))
        let content = try #require(PageBounds.content(ofThumbnail: thumb))
        #expect(content.trimmed)
        #expect(content.left > 0.02 && content.left < 0.06)
        #expect(content.right > 0.94 && content.right < 0.98)
        #expect(content.top > 0.015 && content.top < 0.05)
        #expect(content.bottom > 0.93 && content.bottom < 0.99)
        #expect(PageBounds.content(ofThumbnail: Data("not a picture".utf8)) == nil)
    }

    @Test func thePlaceIsSavedAndAStaleOneRefusedAsTheHubDoes() async throws {
        let work = "rw_demo_ff", issue = "rw_demo_ff-55"
        try await hub.send(HubEndpoints.saveReadingPublicationProgress(workId: work, sourceItemId: issue,
                                                                       ReadingPublicationProgressBody(pageIndex: 3, expectedPage: 0)))
        let after = try await hub.fetch(HubEndpoints.readingPublication(workId: work, sourceItemId: issue),
                                        as: ReadingPublicationManifest.self)
        #expect(after.currentPage == 3)
        // Another device moved it: a save naming the old page is refused.
        do {
            try await hub.send(HubEndpoints.saveReadingPublicationProgress(workId: work, sourceItemId: issue,
                                                                           ReadingPublicationProgressBody(pageIndex: 4, expectedPage: 0)))
            Issue.record("a stale save was taken")
        } catch {
            #expect(error.status == 409)
        }
        // Past the end, and with a field the hub does not know.
        do {
            try await hub.send(HubEndpoints.saveReadingPublicationProgress(workId: work, sourceItemId: issue,
                                                                           ReadingPublicationProgressBody(pageIndex: 99, expectedPage: 3)))
            Issue.record("a page past the end was taken")
        } catch {
            #expect(error.status == 400)
        }
        let unknown = HubRequest("/v1/reading/works/\(work)/publications/\(issue)/progress", method: .post,
                                 body: Data(#"{"pageIndex":2,"page":2}"#.utf8))
        do {
            try await hub.send(unknown)
            Issue.record("a field the hub does not know was taken")
        } catch {
            #expect(error.status == 400)
        }
    }
}
