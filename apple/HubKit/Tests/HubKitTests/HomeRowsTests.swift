import Foundation
import Testing
@testable import HubKit

/// Home's rows (#35): Android's `HomeRowsTest` case for case, then Settings ›
/// Home's editing, what it stores and what Home asks the hub for.
struct HomeRowsTests {
    private func row(_ id: String, _ items: Int = 1) -> HomeRow {
        HomeRow(id: id, title: id, items: (0..<items).map { MediaHit(media: MediaRef(title: id), jellyfinItemId: "\(id)\($0)") })
    }

    @Test func rowsFollowTheChosenOrderHiddenAndEmptyOnesDropUnknownOnesGoLast() {
        let rows = [row("favourites"), row("latest"), row("continue"), row("nextup", 0), row("newthing")]
        #expect(HomeRows.ordered(rows).map(\.id) == ["continue", "latest", "favourites", "newthing"])
        #expect(HomeRows.ordered(rows, order: ["favourites", "continue", "latest"], hidden: ["latest"]).map(\.id)
            == ["favourites", "continue", "newthing"])
    }

    @Test func theHubSendingFavouritesFirstDoesNotPutItFirst() {
        // The test this replaces (`HomeHero.ordered`): Home's order is Android's, not the hub's.
        let rows = ["favourites", "continue", "nextup", "latest", "something-new"].map { row($0) }
        #expect(HomeRows.ordered(rows).map(\.id) == ["continue", "nextup", "latest", "favourites", "something-new"])
    }

    @Test func aRowTheHubCouldNotRefreshKeepsItsPlace() {
        let previous = [row("continue"), row("nextup"), row("latest")]
        let merged = HomeRows.ordered(HomeRows.merge(next: [row("continue"), row("latest")], previous: previous))
        #expect(merged.map(\.id) == ["continue", "nextup", "latest"])
        // The new answer's row wins over the kept one of the same name.
        let fresh = HomeRows.merge(next: [row("continue", 3)], previous: [row("continue", 1)])
        #expect(fresh.count == 1 && fresh[0].items.count == 3)
    }

    @Test func comingUpShowsEachTitleOnceAtItsNextReleaseNotYetOnDisk() {
        func episode(_ key: String, _ date: String, _ season: Int, _ number: Int, hasFile: Bool = false) -> CalendarItem {
            CalendarItem(id: "\(key)\(number)", media: MediaRef(type: "series", title: key, key: key), date: date,
                         at: "\(date)T04:00:00Z", releaseType: "Episode", season: season, episode: number,
                         episodeTitle: "Ep \(number)", overview: "o", hasFile: hasFile)
        }
        let row = HomeRows.upcoming([
            episode("dark", "2026-10-09", 2, 7), episode("dark", "2026-10-02", 2, 6, hasFile: true),
            episode("lanterns", "2026-10-05", 1, 8), episode("dark", "2026-10-03", 2, 6),
        ], today: "2026-10-02")
        #expect(row.id == "upcoming" && row.title == "Coming up")
        #expect(row.items.map(\.media.title) == ["dark", "lanterns"])
        #expect(row.items[0].subtitle == "Tomorrow · S2E6")
        #expect(row.items[1].subtitle == "Mon · S1E8")
        #expect(row.items[0].overview == "Ep 6 — o")
        // Not in the library, so there is no Jellyfin item to play: its key opens the request side.
        #expect(row.items.allSatisfy { $0.jellyfinItemId.isEmpty && !$0.media.key.isEmpty })
    }

    @Test func aFilmIsNamedByItsReleaseAndANumberlessEpisodeByItsKind() {
        let film = CalendarItem(id: "f", media: MediaRef(type: "movie", title: "Dune: Part Two", key: "tmdb:movie:1"),
                                date: "2026-10-04", releaseType: "Digital release")
        let bare = CalendarItem(id: "s", media: MediaRef(type: "series", title: "Lanterns", key: "tmdb:series:2"),
                                date: "2026-10-04", releaseType: "Episode")
        let items = HomeRows.upcoming([film, bare], today: "2026-10-02").items
        #expect(items.map(\.subtitle) == ["Sun · Digital release", "Sun · Episode"])
    }

    @Test func aCardWithoutAKeyIsOnceOnItsIdAndAnUnreadableDayIsLeftOut() {
        let a = CalendarItem(id: "a", media: MediaRef(title: "A"), date: "2026-10-03")
        let b = CalendarItem(id: "a", media: MediaRef(title: "A again"), date: "2026-10-04")
        let c = CalendarItem(id: "c", media: MediaRef(title: "C"), date: "soon")
        #expect(HomeRows.upcoming([a, b, c], today: "2026-10-02").items.map(\.media.title) == ["A"])
    }

    @Test func daysReadAsTodayTomorrowAWeekdayThenADate() {
        let today = "2026-10-02"
        #expect(HomeRows.dayLabel("2026-10-02", today: today) == "Today")
        #expect(HomeRows.dayLabel("2026-10-03", today: today) == "Tomorrow")
        #expect(HomeRows.dayLabel("2026-10-08", today: today) == "Thu")
        #expect(HomeRows.dayLabel("2026-10-09", today: today) == "9 Oct")
        #expect(HomeRows.dayLabel("2026-10-01", today: today) == "Out now")
        #expect(HomeRows.dayTag("Tomorrow · S2E6") == "Tomorrow" && HomeRows.dayTag("Fri") == "Fri")
    }

    @Test func comingUpAsksForTodayAndTheTwoWeeksAhead() {
        let range = HomeRows.upcomingRange(today: "2026-10-02")
        #expect(range.start == "2026-10-02" && range.end == "2026-10-16")
        #expect(HomeRows.upcomingDays == 14 && HomeRows.libraryRowSize == 20)
    }

    @Test func anOlderStoredOrderGainsNewBuiltInRowsAndLibraryRowsAreFetchedOnlyWhenShown() {
        #expect(HomeRows.complete(["latest", "continue"]) == ["latest", "continue", "nextup", "favourites", "upcoming"])
        #expect(HomeRows.complete(["latest", "latest", "continue"]).filter { $0 == "latest" }.count == 1)
        let order = ["continue", HomeRows.libraryRowId("anime"), HomeRows.libraryRowId("docs")]
        #expect(HomeRows.wantedLibraries(order: order, hidden: [HomeRows.libraryRowId("docs")]) == ["anime"])
        #expect(HomeRows.libraryViewId("library:abc") == "abc" && HomeRows.libraryViewId("latest") == nil)
        #expect(HomeRows.libraryRowTitle("Anime") == "From Anime")
        #expect(HomeRows.builtIn.map(HomeRows.builtInTitle)
            == ["Continue watching", "Next up", "Recently added", "Favourites", "Coming up"])
    }
}

struct HomeLayoutTests {
    private let anime = HomeLibrary(id: "a1", name: "Anime")
    private let shows = HomeLibrary(id: "e5", name: "Shows")

    @Test func theDefaultLayoutShowsTheFiveBuiltInRowsInAndroidsOrder() {
        let rows = HomeLayoutEditor.rows(HomeLayout(), libraries: [])
        #expect(rows.map(\.title) == ["Continue watching", "Next up", "Recently added", "Favourites", "Coming up"])
        #expect(rows.allSatisfy { $0.shown })
        #expect(rows.first?.canMoveUp == false && rows.last?.canMoveDown == false && rows[2].canMoveUp && rows[2].canMoveDown)
    }

    @Test func aLibrarysRowIsListedHiddenUntilItIsTurnedOn() {
        let layout = HomeLayout()
        let rows = HomeLayoutEditor.rows(layout, libraries: [anime, shows])
        #expect(rows.suffix(2).map(\.title) == ["From Anime", "From Shows"])
        #expect(rows.suffix(2).allSatisfy { !$0.shown && $0.detail == "The newest titles in that library" })
        // Home itself asks for none of them yet.
        #expect(layout.wantedLibraries.isEmpty)

        let on = HomeLayoutEditor.setShown(layout, libraries: [anime, shows], id: HomeRows.libraryRowId("a1"), shown: true)
        #expect(on.wantedLibraries == ["a1"])
        #expect(on.order.suffix(2) == [HomeRows.libraryRowId("a1"), HomeRows.libraryRowId("e5")])
        // The other library is now known to the order, and stays hidden.
        #expect(on.hidden == [HomeRows.libraryRowId("e5")])
        let after = HomeLayoutEditor.rows(on, libraries: [anime, shows])
        #expect(after.filter(\.shown).count == 6)
    }

    @Test func aRowIsHiddenAndShownAgainWithoutLosingItsPlace() {
        var layout = HomeLayoutEditor.setShown(HomeLayout(), libraries: [], id: "latest", shown: false)
        #expect(layout.hidden == ["latest"] && layout.order == HomeRows.defaultOrder)
        #expect(HomeLayoutEditor.rows(layout, libraries: []).first { $0.id == "latest" }?.shown == false)
        layout = HomeLayoutEditor.setShown(layout, libraries: [], id: "latest", shown: true)
        #expect(layout.hidden.isEmpty)
    }

    @Test func aRowMovesOnePlaceAtATimeAndStopsAtTheEnds() {
        var layout = HomeLayout()
        layout = HomeLayoutEditor.move(layout, libraries: [], id: "favourites", by: -1)
        #expect(layout.order == ["continue", "nextup", "favourites", "latest", "upcoming"])
        layout = HomeLayoutEditor.move(layout, libraries: [], id: "continue", by: 1)
        #expect(layout.order == ["nextup", "continue", "favourites", "latest", "upcoming"])
        #expect(HomeLayoutEditor.move(layout, libraries: [], id: "nextup", by: -1) == layout, "already first")
        #expect(HomeLayoutEditor.move(layout, libraries: [], id: "upcoming", by: 1) == layout, "already last")
        #expect(HomeLayoutEditor.move(layout, libraries: [], id: "nothing", by: 1) == layout)
    }

    @Test func movingNamesTheLibraryRowsTooAndKeepsWhatWasHidden() {
        let layout = HomeLayout()
        let moved = HomeLayoutEditor.move(layout, libraries: [anime], id: HomeRows.libraryRowId("a1"), by: -2)
        #expect(moved.order == ["continue", "nextup", "latest", HomeRows.libraryRowId("a1"), "favourites", "upcoming"])
        #expect(moved.hidden == [HomeRows.libraryRowId("a1")], "still off: moving is not turning on")
        #expect(HomeRows.ordered([row("latest")], order: moved.order, hidden: moved.hidden).map(\.id) == ["latest"])
    }

    @Test func aLibraryThatIsGoneKeepsItsPlaceButIsNotListed() {
        let layout = HomeLayout(order: ["continue", HomeRows.libraryRowId("gone"), "latest"], hidden: [])
        let rows = HomeLayoutEditor.rows(layout, libraries: [])
        #expect(!rows.contains { $0.id == HomeRows.libraryRowId("gone") })
        #expect(rows.first { $0.id == "latest" }?.canMoveUp == true)
        let moved = HomeLayoutEditor.move(layout, libraries: [], id: "latest", by: -1)
        #expect(moved.order.firstIndex(of: "latest")! < moved.order.firstIndex(of: "nextup")!)
        #expect(moved.order.contains(HomeRows.libraryRowId("gone")), "its place is kept in the order")
    }

    @Test func whatHomeMustAskForAgainIsOnlyWhatItFetches() {
        let base = HomeLayout()
        #expect(base.fetchKey == "upcoming|")
        #expect(HomeLayoutEditor.move(base, libraries: [], id: "latest", by: -1).fetchKey == base.fetchKey, "a move fetches nothing")
        #expect(HomeLayoutEditor.setShown(base, libraries: [], id: "latest", shown: false).fetchKey == base.fetchKey)
        #expect(HomeLayoutEditor.setShown(base, libraries: [], id: "upcoming", shown: false).fetchKey == "|")
        let withAnime = HomeLayoutEditor.setShown(base, libraries: [anime], id: HomeRows.libraryRowId("a1"), shown: true)
        #expect(withAnime.fetchKey == "upcoming|a1")
    }

    @Test func theLayoutIsKeptAsAndroidKeepsItAndOlderStoredOnesAreCompleted() throws {
        let suite = "home-rows-\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        #expect(HomeRowSettings.layout(defaults) == HomeLayout())
        let chosen = HomeLayout(order: ["latest", "continue", HomeRows.libraryRowId("a1")], hidden: ["favourites", HomeRows.libraryRowId("a1")])
        HomeRowSettings.save(chosen, in: defaults)
        #expect(defaults.string(forKey: "home.rowOrder") == chosen.order.joined(separator: ","))
        #expect(HomeRowSettings.layout(defaults) == chosen)
        // A stored order from before Coming up gains it.
        defaults.set("latest,continue,nextup,favourites", forKey: "home.rowOrder")
        #expect(HomeRowSettings.layout(defaults).order == ["latest", "continue", "nextup", "favourites", "upcoming"])
        defaults.set("", forKey: "home.rowsHidden")
        #expect(HomeRowSettings.layout(defaults).hidden.isEmpty)
    }

    private func row(_ id: String) -> HomeRow { HomeRow(id: id, title: id, items: [MediaHit(media: MediaRef(title: id))]) }
}

struct DemoHomeTests {
    private let hub = HubClient(credentials: HubCredentials(baseURL: DemoTransport.address, token: DemoTransport.token),
                                screens: DemoTransport())

    @Test func theDemosHomeHasItsRowsAndNoEmptyOne() async throws {
        let home = try await hub.fetch(HubEndpoints.home, as: HomeResponse.self)
        #expect(home.rows.map(\.id) == ["continue", "nextup", "latest"], "Favourites is empty and so is not sent")
        #expect(home.rows.allSatisfy { !$0.items.isEmpty })
        #expect(home.rows[0].items.allSatisfy { $0.progress > 0 && !$0.jellyfinItemId.isEmpty })
        #expect(home.rows[1].items.allSatisfy { $0.media.type == "episode" && $0.subtitle.hasPrefix("S1E") })
        #expect(HomeRows.ordered(home.rows.reversed()).map(\.id) == ["continue", "nextup", "latest"])
    }

    @Test func theDemosCalendarAnswersOnlyTheDaysAskedFor() async throws {
        let zone = TimeZone(identifier: "UTC")!
        let today = UpcomingPresentation.today(now: Date(), zone: zone)
        let range = HomeRows.upcomingRange(today: today)
        let calendar = try await hub.fetch(HubEndpoints.calendar(start: range.start, end: range.end, timezone: zone.identifier),
                                           as: CalendarResponse.self)
        #expect(calendar.items.allSatisfy { $0.date >= range.start && $0.date < range.end })
        let row = HomeRows.upcoming(calendar.items, today: today)
        #expect(row.items.map(\.media.title) == ["Dark Matter", "Shōgun", "Dune: Part Two", "The Bear"])
        #expect(row.items.map { HomeRows.dayTag($0.subtitle) } == ["Today", "Tomorrow", HomeRows.dayLabel(UpcomingPresentation.add(days: 3, to: today), today: today),
                                                                   HomeRows.dayLabel(UpcomingPresentation.add(days: 6, to: today), today: today)])
        // The month Activity asks for still has what it had: what aired, what is missing, what is coming.
        let month = ActivityDashboard.agendaRange(now: Date(), zone: zone)
        let all = try await hub.fetch(HubEndpoints.calendar(start: month.start, end: month.end, timezone: zone.identifier), as: CalendarResponse.self)
        #expect(all.items.count == 6)
    }
}
