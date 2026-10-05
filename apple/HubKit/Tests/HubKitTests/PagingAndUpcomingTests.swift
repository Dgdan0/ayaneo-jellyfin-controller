import Foundation
import Testing
@testable import HubKit

/// Android's `PagedLoadStateTest`, `RowPagingTest` and
/// `UpcomingPresentationTest`, case for case.
struct PagingAndUpcomingTests {
    @Test func oneFirstPageAndNothingWhileItLoads() {
        var state = PagedLoadState()
        #expect(state.initial() == 1)
        #expect(state.initial() == nil)
        #expect(state.next(lastVisible: 59, count: 60) == nil)
    }

    @Test func theNextPageSixCardsFromTheEnd() {
        var state = PagedLoadState(prefetchAhead: 6)
        _ = state.initial()
        state.complete(page: 1, total: 3)
        #expect(state.next(lastVisible: 52, count: 60) == nil)
        #expect(state.next(lastVisible: 54, count: 60) == 2)
    }

    @Test func aFailedPageIsTheOneTriedAgain() {
        var state = PagedLoadState()
        _ = state.initial()
        state.complete(page: 1, total: 3)
        #expect(state.next(lastVisible: 59, count: 60) == 2)
        state.fail(page: 2)
        #expect(state.retry() == 2)
        #expect(state.loadedPage == 1)
    }

    @Test func nothingPastTheLastPage() {
        var state = PagedLoadState()
        _ = state.initial()
        state.complete(page: 1, total: 1)
        #expect(state.next(lastVisible: 59, count: 60) == nil)
        #expect(state.retry() == nil)
    }

    @Test func aCancelledPageIsAskedForAgain() {
        var first = PagedLoadState()
        #expect(first.initial() == 1)
        first.cancelLoading()
        #expect(first.initial() == 1)

        var later = PagedLoadState()
        _ = later.initial()
        later.complete(page: 1, total: 3)
        #expect(later.next(lastVisible: 59, count: 60) == 2)
        later.cancelLoading()
        #expect(later.retry() == 2)
        #expect(later.loadedPage == 1)
    }

    @Test func eachRowPagesOnItsOwn() {
        var rows = RowPaging(prefetchAhead: 6)
        #expect(rows.next("trending", page: 1, totalPages: 5, lastVisible: 15, count: 20) == 2)
        #expect(rows.next("trending", page: 1, totalPages: 5, lastVisible: 16, count: 20) == nil)
        rows.complete("trending", page: 2, total: 5)
        #expect(rows.next("trending", page: 2, totalPages: 5, lastVisible: 35, count: 40) == 3)
        #expect(rows.next("films", page: 1, totalPages: 5, lastVisible: 15, count: 20) == 2)
        #expect(rows.next("series", page: 1, totalPages: 5, lastVisible: 15, count: 20) == 2)
        #expect(rows.next("other", page: 1, totalPages: 5, lastVisible: 3, count: 20) == nil)
        #expect(rows.next("last", page: 1, totalPages: 1, lastVisible: 19, count: 20) == nil)
        rows.fail("films", page: 2)
        #expect(rows.next("films", page: 1, totalPages: 5, lastVisible: 15, count: 20) == 2)
        rows.clear()
        #expect(rows.next("films", page: 1, totalPages: 5, lastVisible: 15, count: 20) == 2)
    }

    @Test func aSeedIsTakenOnlyWhenItIsNewer() {
        var state = PagedLoadState(prefetchAhead: 6)
        state.seed(page: 2, total: 5)
        #expect(state.loadedPage == 2)
        state.seed(page: 1, total: 5)
        #expect(state.loadedPage == 2)
        #expect(state.next(lastVisible: 15, count: 20) == 3)
        state.seed(page: 3, total: 5)
        #expect(state.loadedPage == 2)
    }

    // MARK: Upcoming

    @Test func weeksRunMondayToSunday() {
        let friday = "2026-09-25"
        let thisWeek = UpcomingPresentation.range(today: friday, week: 0)
        #expect(thisWeek.start == "2026-09-21" && thisWeek.endExclusive == "2026-09-28")
        #expect(UpcomingPresentation.range(today: friday, week: 1).start == "2026-09-28")
        #expect(UpcomingPresentation.range(today: friday, week: -1).endExclusive == thisWeek.start)
        #expect(UpcomingPresentation.days(thisWeek).count == 7)
        #expect(UpcomingPresentation.days(UpcomingPresentation.range(today: friday, week: 1)).first == "2026-09-28")
        #expect(UpcomingPresentation.range(today: "2026-12-31", week: 1).start == "2027-01-04")
        // A Sunday belongs to the week that began the Monday before.
        #expect(UpcomingPresentation.range(today: "2026-10-04", week: 0).start == "2026-09-28")
        #expect(UpcomingPresentation.range(today: "2026-09-28", week: 0).start == "2026-09-28")
    }

    @Test func weekLabels() {
        let today = "2026-10-02"
        #expect(UpcomingPresentation.weekLabel(today: today, week: -1) == "Last week")
        #expect(UpcomingPresentation.weekLabel(today: today, week: 0) == "This week · 28 Sep – 4 Oct")
        #expect(UpcomingPresentation.weekLabel(today: today, week: 1) == "Next week · 5 – 11 Oct")
        #expect(UpcomingPresentation.rangeLabel(UpcomingPresentation.range(today: "2026-12-30", week: 0))
                == "28 Dec 2026 – 3 Jan 2027")
        #expect(UpcomingPresentation.dayHeading("2026-10-02") == "Fri 2 Oct")
        #expect(UpcomingPresentation.dayTag("2026-10-02", today: today) == "Today")
        #expect(UpcomingPresentation.dayTag("2026-10-03", today: today) == "Tomorrow")
        #expect(UpcomingPresentation.dayTag("2026-10-04", today: today) == nil)
    }

    private func episode(_ id: String, season: Int, date: String, at: String = "", hasFile: Bool = false,
                         key: String = "tmdb:series:2") -> CalendarItem {
        CalendarItem(id: id, service: "sonarr", media: MediaRef(type: "series", title: "Show", key: key), date: date, at: at,
                     releaseType: "Episode", season: season, episode: Int(id) ?? 0, hasFile: hasFile)
    }

    @Test func aSeasonsEpisodesOnOneDayAreOneGroup() {
        let groups = UpcomingPresentation.groups([
            episode("2", season: 1, date: "2026-09-25"), episode("1", season: 1, date: "2026-09-25"),
            episode("3", season: 2, date: "2026-09-25"), episode("4", season: 1, date: "2026-09-26"),
        ])
        #expect(groups.count == 3)
        #expect(groups[0].items.map(\.episode) == [1, 2])
        #expect(groups[0].label == "Season 1 · 2 episodes")
        #expect(groups[0].id == "1|2")

        let film = MediaRef(type: "movie", title: "A film", key: "tmdb:movie:4")
        let separate = UpcomingPresentation.groups([
            CalendarItem(id: "radarr:movie:4:Digital release", media: film, date: "2026-09-25", releaseType: "Digital release"),
            CalendarItem(id: "radarr:movie:4:In cinemas", media: film, date: "2026-09-25", releaseType: "In cinemas"),
            CalendarItem(id: "x", date: "2026-09-25"), CalendarItem(id: "y", date: "2026-09-25"),
        ])
        #expect(separate.count == 4)
        #expect(separate.first { $0.first.id.hasSuffix("In cinemas") }?.label == "In cinemas")
    }

    @Test func groupsComeByDayThenTimeThenTitle() {
        let later = episode("1", season: 1, date: "2026-10-02", at: "2026-10-02T20:00:00Z", key: "tmdb:series:1")
        let earlier = episode("2", season: 1, date: "2026-10-02", at: "2026-10-02T18:00:00Z", key: "tmdb:series:2")
        let untimed = episode("3", season: 1, date: "2026-10-02", key: "tmdb:series:3")
        let yesterday = episode("4", season: 1, date: "2026-10-01", key: "tmdb:series:4")
        let groups = UpcomingPresentation.groups([untimed, later, earlier, yesterday])
        #expect(groups.map(\.first.id) == ["4", "2", "1", "3"])
    }

    @Test func whereAReleaseIs() throws {
        let utc = try #require(TimeZone(identifier: "UTC"))
        let now = try #require(ISO8601DateFormatter().date(from: "2026-10-02T12:00:00Z"))
        func state(_ at: String, file: Bool = false) -> ReleaseState {
            UpcomingPresentation.state(episode("1", season: 2, date: String(at.prefix(10)), at: at, hasFile: file),
                                       now: now, zone: utc)
        }
        #expect(state("2026-10-02T20:00:00Z") == .soon)
        #expect(state("2026-10-02T04:00:00Z") == .aired)
        #expect(state("2026-09-30T04:00:00Z") == .missing)
        #expect(state("2026-09-30T04:00:00Z", file: true) == .inLibrary)
        let group = UpcomingGroup(id: "g", items: [
            episode("1", season: 2, date: "2026-09-30", at: "2026-09-30T04:00:00Z", hasFile: true),
            episode("2", season: 2, date: "2026-09-30", at: "2026-09-30T05:00:00Z"),
        ])
        #expect(UpcomingPresentation.state(group, now: now, zone: utc) == .missing)
        let one = UpcomingGroup(id: "s2e6", items: [episode("6", season: 2, date: "2026-10-02")])
        #expect(one.label == "S2E6")
        // A day alone counts from its start, in the zone asked for.
        #expect(UpcomingPresentation.state(episode("1", season: 1, date: "2026-10-03"), now: now, zone: utc) == .soon)
        #expect(UpcomingPresentation.state(episode("1", season: 1, date: "2026-10-02"), now: now, zone: utc) == .aired)
        #expect(ReleaseState.missing.label == "Missing" && ReleaseState.soon.label == "Soon")
    }

    @Test func timesAndThePreviewsDate() throws {
        let utc = try #require(TimeZone(identifier: "UTC"))
        let single = UpcomingGroup(id: "a", items: [episode("1", season: 1, date: "2026-10-02", at: "2026-10-02T21:00:00Z")])
        #expect(UpcomingPresentation.timeLabel(single, zone: utc) == "21:00")
        let two = UpcomingGroup(id: "b", items: [
            episode("1", season: 1, date: "2026-10-02", at: "2026-10-02T21:00:00Z"),
            episode("2", season: 1, date: "2026-10-02", at: "2026-10-02T21:00:00Z"),
            episode("3", season: 1, date: "2026-10-02", at: "2026-10-02T22:00:00Z"),
        ])
        #expect(UpcomingPresentation.timeLabel(two, zone: utc) == "21:00 – 22:00")
        let none = UpcomingGroup(id: "c", items: [episode("1", season: 1, date: "2026-10-02")])
        #expect(UpcomingPresentation.timeLabel(none, zone: utc) == "Time not announced")
        #expect(UpcomingPresentation.previewDate(single, zone: utc) == "Friday 2 October · 21:00")
        let jerusalem = try #require(TimeZone(identifier: "Asia/Jerusalem"))
        #expect(UpcomingPresentation.timeLabel(single, zone: jerusalem) == "00:00")
        #expect(UpcomingPresentation.count([single, two]) == "4 releases")
        #expect(UpcomingPresentation.count([none]) == "1 release")
    }

    @Test func thePreviewKeepsItsChoiceThenStartsFromToday() {
        let groups = UpcomingPresentation.groups([
            episode("1", season: 1, date: "2026-09-29", key: "tmdb:series:1"),
            episode("2", season: 1, date: "2026-10-02", key: "tmdb:series:2"),
            episode("3", season: 1, date: "2026-10-03", key: "tmdb:series:3"),
        ])
        #expect(UpcomingPresentation.selected(groups, previous: "3", week: 0, today: "2026-10-02")?.id == "3")
        #expect(UpcomingPresentation.selected(groups, previous: "gone", week: 0, today: "2026-10-02")?.id == "2")
        #expect(UpcomingPresentation.selected(groups, previous: nil, week: 1, today: "2026-10-02")?.id == "1")
        #expect(UpcomingPresentation.selected([], previous: nil, week: 0, today: "2026-10-02") == nil)
    }
}
