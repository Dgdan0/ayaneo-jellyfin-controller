import Foundation
import Testing
@testable import HubKit

/// Kavita's reading lists (#37): read as the hub sends them, worded as the
/// Pocket words them, and walked across runs.
struct ServerReadingListsTests {
    private func entry(_ id: Int, order: Int, series: String = "Fantastic Four", title: String = "Issue #1",
                       volume: String = "", pages: Int = 36, progress: ReadingProgress? = nil) -> ServerReadingListEntry {
        ServerReadingListEntry(id: id, order: order, workId: "rw_\(id)", sourceItemId: "\(8000 + id)", title: title,
                               seriesTitle: series, volume: volume, pageCount: pages, progress: progress)
    }

    @Test func aListAndItsIssuesAreReadAsTheHubSendsThem() throws {
        let lists = try JSONDecoder().decode(ServerReadingListsResponse.self, from: Data(
            #"{"lists":[{"id":19,"title":"My Marvelous Year 1962","itemCount":2,"promoted":false}]}"#.utf8))
        #expect(lists.lists == [ServerReadingList(id: 19, title: "My Marvelous Year 1962", itemCount: 2)])
        let list = try JSONDecoder().decode(ServerReadingListResponse.self, from: Data(#"""
            {"list":{"id":19,"title":"My Marvelous Year 1962","itemCount":2,"promoted":false},
             "items":[{"id":1,"order":0,"workId":"rw_ff","sourceItemId":"8421","title":"Issue #1","seriesTitle":"Fantastic Four",
                       "kind":"comic","artwork":"/v1/img/reading/kavita-series/2016","pageCount":36,
                       "progress":{"percentage":0.25,"completed":false,"current":9,"total":36}},
                      {"id":2,"order":1,"workId":"rw_aaf","sourceItemId":"8338","title":"Issue #7",
                       "seriesTitle":"Amazing Adult Fantasy","kind":"comic","artwork":"","pageCount":0}]}
            """#.utf8))
        #expect(list.items.map(\.sourceItemId) == ["8421", "8338"])
        #expect(list.items[0].progress?.percentage == 0.25)
        #expect(list.items[1].progress == nil && list.items[1].volume.isEmpty)
        let issue = list.items[0].publication
        #expect(issue.sourceItemId == "8421" && issue.workId == "rw_ff" && issue.kind == "comic" && issue.pageCount == 36)
    }

    @Test func listsGoByTitleAndIssuesByKavitasOrder() {
        let lists = [ServerReadingList(id: 1, title: "zeta"), ServerReadingList(id: 2, title: "Alpha"),
                     ServerReadingList(id: 3, title: "beta")]
        #expect(ServerReadingLists.sorted(lists).map(\.id) == [2, 3, 1])
        let entries = [entry(1, order: 2), entry(2, order: 0), entry(3, order: 1), entry(4, order: 1)]
        #expect(ServerReadingLists.ordered(entries).map(\.id) == [2, 3, 4, 1])
    }

    @Test func theRowsAreWordedAsThePocketWordsThem() {
        #expect(ServerReadingLists.listsStatus(3) == "3 Kavita lists · title order")
        #expect(ServerReadingLists.listsStatus(1) == "1 Kavita list · title order")
        #expect(ServerReadingLists.listsStatus(0) == "No reading lists are available in Kavita.")
        #expect(ServerReadingLists.entriesStatus(12) == "12 issues · Kavita reading order")
        #expect(ServerReadingLists.entriesStatus(0) == "This reading list is empty.")
        #expect(ServerReadingLists.listDetail(ServerReadingList(id: 1, title: "A", itemCount: 1)) == "1 issue")
        #expect(ServerReadingLists.listDetail(ServerReadingList(id: 1, title: "A", itemCount: 4)) == "4 issues")

        #expect(ServerReadingLists.entryTitle(0, entry(1, order: 0)) == "1. Fantastic Four · Issue #1")
        #expect(ServerReadingLists.entryDetail(entry(1, order: 0)) == "36 pages")
        let started = entry(1, order: 0, volume: "2", progress: ReadingProgress(percentage: 0.25, current: 9, total: 36))
        #expect(ServerReadingLists.entryDetail(started) == "Volume 2 · 36 pages · 25% read")
        let read = entry(1, order: 0, progress: ReadingProgress(percentage: 1, completed: true))
        #expect(ServerReadingLists.entryDetail(read) == "36 pages · Read")
        // Kavita's loose issues sit in volume 0, or -100000: no volume to name.
        #expect(ServerReadingLists.entryDetail(entry(1, order: 0, volume: "0")) == "36 pages")
        #expect(ServerReadingLists.entryDetail(entry(1, order: 0, volume: "-100000", pages: 1)) == "1 page")
        #expect(ServerReadingLists.entryLabel(2, read) == "3. Fantastic Four · Issue #1, 36 pages · Read")
    }

    @Test func aListIsWalkedAcrossRunsAndStopsAtItsEnds() throws {
        let entries = [entry(1, order: 0), entry(2, order: 1, series: "Amazing Adult Fantasy", title: "Issue #7"),
                       entry(3, order: 2, title: "Issue #2")]
        #expect(ReadingListRun(title: "Empty", entries: [], index: 0) == nil)
        let run = try #require(ReadingListRun(title: "Marvel's first year", entries: entries, index: 0))
        #expect(run.current.id == 1 && run.neighbour(-1) == nil && run.moved(-1) == nil)
        #expect(run.neighbour(1)?.seriesTitle == "Amazing Adult Fantasy")
        let second = try #require(run.moved(1))
        #expect(second.current.workId == "rw_2" && second.position == "2 of 3 in Marvel's first year")
        let last = try #require(second.moved(1))
        #expect(last.moved(1) == nil && last.neighbour(-2)?.id == 1)
        #expect(ReadingListRun(title: "A", entries: entries, index: 9)?.index == 2)
        #expect(ReadingListRun.edge(forward: true) == "End of reading list")
        #expect(ReadingListRun.edge(forward: false) == "Start of reading list")
    }

    @Test func theDemoHubAnswersAsTheHubDoes() async throws {
        let hub = HubClient(credentials: HubCredentials(baseURL: DemoTransport.address, token: DemoTransport.token),
                            screens: DemoTransport(), sleep: { _ in })
        let lists = try await hub.fetch(HubEndpoints.serverReadingLists, as: ServerReadingListsResponse.self)
        #expect(lists.lists.map(\.title) == ["Marvel's first year", "Chainsaw Man to start"])
        let first = try #require(lists.lists.first)
        let list = try await hub.fetch(HubEndpoints.serverReadingList(first.id), as: ServerReadingListResponse.self)
        #expect(list.list == first && list.items.count == first.itemCount)
        #expect(list.items.map(\.seriesTitle) == ["Fantastic Four", "Amazing Adult Fantasy", "Fantastic Four", "Fantastic Four"])
        // Every issue opens in its own run, as the reader asks for it.
        for item in list.items {
            let manifest = try await hub.fetch(HubEndpoints.readingPublication(workId: item.workId, sourceItemId: item.sourceItemId),
                                               as: ReadingPublicationManifest.self)
            #expect(manifest.pageCount == item.pageCount)
        }
        await #expect(throws: HubFailure.self) {
            _ = try await hub.fetch(HubEndpoints.serverReadingList(99), as: ServerReadingListResponse.self)
        }
    }
}
