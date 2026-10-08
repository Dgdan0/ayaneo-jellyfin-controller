import Foundation
import Testing
@testable import HubKit

/// A series' downloads on its own page (#48): a card's corner, "Season 2 ·
/// 4.9 GB", the panel's choices and their sizes, and select mode's ticks.
struct SeriesDownloadsTests {
    /// Season 1 of three episodes and season 2 of four, the first of season 1
    /// watched, a gigabyte each; Specials after them.
    private func series(playedThrough: Int = 1) -> [DownloadEpisode] {
        var out: [DownloadEpisode] = []
        var number = 0
        for (season, count) in [(1, 3), (2, 4)] {
            for index in 1...count {
                number += 1
                out.append(DownloadEpisode(id: "e\(number)", seasonId: "s\(season)", season: season, index: index,
                                           played: number <= playedThrough, bytes: 1 << 30))
            }
        }
        out.append(DownloadEpisode(id: "sp1", seasonId: "s0", season: 0, index: 1, bytes: 1 << 29))
        return out
    }

    private func names(_ season: Int) -> String { season == 0 ? "Specials" : "Season \(season)" }

    // MARK: The hub's listing

    @Test func theHubsListingBecomesEpisodesInTheOrderTheyAreWatchedWithSpecialsLast() {
        func entry(_ id: String, season: Int, index: Int, played: Bool = false, available: Bool = true) -> OfflineSelectionItem {
            OfflineSelectionItem(item: LibraryItem(id: id, type: "episode", title: id, seasonId: "s\(season)", indexNumber: index,
                                                   seasonNumber: season, played: played),
                                 estimatedSizeBytes: 500, available: available)
        }
        // Jellyfin lists Specials first.
        let selection = OfflineSelectionResponse(
            series: LibraryItem(id: "x", type: "series", title: "X"),
            seasons: [OfflineSelectionSeason(season: LibraryItem(id: "s0", type: "season", title: "Specials"),
                                             episodes: [entry("sp", season: 0, index: 1)]),
                      OfflineSelectionSeason(season: LibraryItem(id: "s2", type: "season", title: "Season 2"),
                                             episodes: [entry("b", season: 2, index: 1)]),
                      OfflineSelectionSeason(season: LibraryItem(id: "s1", type: "season", title: "Season 1"),
                                             episodes: [entry("a2", season: 1, index: 2, available: false),
                                                        entry("a1", season: 1, index: 1, played: true)])])
        let episodes = SeriesDownloads.episodes(from: selection)
        #expect(episodes.map(\.id) == ["a1", "a2", "b", "sp"])
        #expect(episodes[0].played && episodes[0].bytes == 500 && episodes[0].seasonId == "s1")
        #expect(!episodes[1].available)
    }

    // MARK: Sizes

    @Test func whatIsMissingSkipsWhatIsHereComingOrCannotBeMade() {
        var list = series()
        list[1].available = false
        let missing = SeriesDownloads.missing(list) { $0 == "e3" }
        #expect(!missing.map(\.id).contains("e3") && !missing.map(\.id).contains("e2"))
        #expect(missing.count == list.count - 2)
        #expect(SeriesDownloads.size(missing) == Int64(missing.count - 1) * (1 << 30) + (1 << 29))
    }

    @Test func sizesAreAddedAndSaidTheWayTheQueueSaysThem() {
        let two = Array(series().prefix(2))
        #expect(SeriesDownloads.countLine(two) == "2 episodes · 2.0 GB")
        #expect(SeriesDownloads.countLine([two[0]]) == "1 episode · 1.0 GB")
        #expect(SeriesDownloads.size([]) == 0)
    }

    // MARK: The season button

    @Test func theSeasonButtonNamesWhatItWouldGetAndSaysWhenThereIsNothingLeft() {
        let season2 = series().filter { $0.season == 2 }
        #expect(SeriesDownloads.seasonButton(name: "Season 2", missing: season2, device: "iPad") == "Season 2 · 4.0 GB")
        // One of them is here already: only the rest is counted.
        let rest = SeriesDownloads.missing(season2) { $0 == "e4" }
        #expect(SeriesDownloads.seasonButton(name: "Season 2", missing: rest, device: "iPad") == "Season 2 · 3.0 GB")
        #expect(SeriesDownloads.seasonButton(name: "Season 2", missing: [], device: "iPad") == "Season 2 on this iPad")
        #expect(SeriesDownloads.seasonButton(name: "Season 2", missing: [], device: "iPhone") == "Season 2 on this iPhone")
    }

    @Test func theDeviceIsNamedAsTheOwnerWouldName() {
        #expect(SeriesDownloads.deviceWord(idiom: "pad") == "iPad")
        #expect(SeriesDownloads.deviceWord(idiom: "phone") == "iPhone")
        #expect(SeriesDownloads.deviceWord(idiom: "mac") == "Mac")
        #expect(SeriesDownloads.deviceWord(idiom: "tv") == "device")
    }

    // MARK: A card's corner

    @Test func aCardsCornerFollowsTheDownload() {
        func row(_ state: OfflineState, done: Int64 = 0) -> OfflineRow {
            OfflineTests.row("r", LibraryItem(id: "e1", type: "episode", title: "E1"), state: state, done: done, total: 100)
        }
        #expect(SeriesDownloads.badge(nil) == .none)
        // Asked for, not yet in the store: a clock at once, not a wait for the hub.
        #expect(SeriesDownloads.badge(nil, requesting: true) == .waiting)
        #expect(SeriesDownloads.badge(row(.queued)) == .waiting)
        #expect(SeriesDownloads.badge(row(.preparing)) == .waiting)
        #expect(SeriesDownloads.badge(row(.waiting)) == .waiting)
        #expect(SeriesDownloads.badge(row(.paused)) == .waiting)
        #expect(SeriesDownloads.badge(row(.downloading, done: 40)) == .moving(0.4))
        #expect(SeriesDownloads.badge(row(.downloading, done: 0)) == .moving(0.03), "a ring that has begun shows")
        #expect(SeriesDownloads.badge(row(.complete, done: 100)) == .downloaded)
        #expect(SeriesDownloads.badge(row(.failed)) == .failed)
    }

    @Test func aRingOrAClockCanBeStoppedAndNothingElseCan() {
        #expect(SeriesDownloads.Badge.waiting.isComing && SeriesDownloads.Badge.moving(0.2).isComing)
        #expect(!SeriesDownloads.Badge.none.isComing && !SeriesDownloads.Badge.downloaded.isComing
                && !SeriesDownloads.Badge.failed.isComing)
        #expect(SeriesDownloads.Badge.moving(0.456).label == "Downloading 46 percent. Stop")
        #expect(SeriesDownloads.Badge.none.label == "Download")
        #expect(SeriesDownloads.Badge.downloaded.label == "Downloaded")
    }

    // MARK: The choices

    @Test func theChoicesCountAndSizeWhatEachWouldAdd() {
        // e1 watched; Play starts e2, in season 1.
        let choices = SeriesDownloads.choices(series(), playTargetId: "e2", count: 3, have: { _ in false }, seasonName: names)
        #expect(choices.map(\.id) == ["keep-ready", "rest-of-season", "everything-unwatched", "whole-series"])
        #expect(choices[0].title == "Keep the next 3 ready")
        #expect(choices[0].ids == ["e2", "e3", "e4"], "from where Play starts, across the season's end")
        #expect(choices[1].title == "Rest of Season 1")
        #expect(choices[1].ids == ["e2", "e3"])
        #expect(choices[2].ids == ["e2", "e3", "e4", "e5", "e6", "e7", "sp1"], "unwatched, Specials too")
        #expect(choices[3].title == "Whole series")
        #expect(choices[3].ids.count == 8)
        #expect(choices[1].line == "2 episodes · 2.0 GB")
        #expect(choices[3].bytes == 7 * (1 << 30) + (1 << 29))
    }

    @Test func whatIsHereAlreadyIsNotCountedAgainButItCountsTowardsKeepReady() {
        let have: Set<String> = ["e2", "e4"]
        let choices = SeriesDownloads.choices(series(), playTargetId: "e2", count: 3, have: have.contains, seasonName: names)
        // The window is e2, e3, e4: two are here, one is to get.
        #expect(choices[0].ids == ["e3"])
        #expect(choices[1].ids == ["e3"])
        #expect(!choices[3].ids.contains("e2") && !choices[3].ids.contains("e4"))
    }

    @Test func aChoiceWithNothingToAddSaysSoAndIsEmpty() {
        let all = Set(series().map(\.id))
        let choices = SeriesDownloads.choices(series(), playTargetId: "e2", count: 3, have: all.contains, seasonName: names)
        #expect(choices.allSatisfy { $0.episodes.isEmpty })
        #expect(choices[0].line == "Nothing left to get")
    }

    @Test func restOfSeasonIsTheSeasonPlayIsInFromWhereItStarts() {
        // Play starts in season 2 (e5): the rest is e5, e6, e7.
        let choices = SeriesDownloads.choices(series(playedThrough: 4), playTargetId: "e5", count: 3,
                                              have: { _ in false }, seasonName: names)
        #expect(choices[1].title == "Rest of Season 2")
        #expect(choices[1].ids == ["e5", "e6", "e7"])
    }

    @Test func withNoPlayTargetTheFirstUnwatchedEpisodeStarts() {
        let choices = SeriesDownloads.choices(series(playedThrough: 2), playTargetId: "", count: 2, have: { _ in false },
                                              seasonName: names)
        #expect(choices[0].ids == ["e3", "e4"])
        #expect(choices[1].ids == ["e3"])
    }

    // MARK: Select mode

    @Test func onlyWhatIsNotHereCanBeTicked() {
        let list = series()
        let have: Set<String> = ["e2"]
        let tickable = Set(SeriesDownloads.tickable(list, have: have.contains).map(\.id))
        #expect(!tickable.contains("e2") && tickable.contains("e3"))
        var ticked = SeriesDownloads.toggled([], "e3", tickable: tickable)
        #expect(ticked == ["e3"])
        ticked = SeriesDownloads.toggled(ticked, "e2", tickable: tickable)
        #expect(ticked == ["e3"], "one that is here cannot be ticked")
        ticked = SeriesDownloads.toggled(ticked, "e3", tickable: tickable)
        #expect(ticked.isEmpty, "a second tap takes the tick off")
    }

    @Test func selectSeasonTicksTheSeasonAndThenUnselectsIt() {
        let list = series()
        let have: Set<String> = ["e1"]
        let tickable = Set(SeriesDownloads.tickable(list, have: have.contains).map(\.id))
        let season1 = list.filter { $0.season == 1 }
        var ticked = SeriesDownloads.toggledSeason([], season: season1, tickable: tickable)
        #expect(ticked == ["e2", "e3"], "what is here is left alone")
        #expect(SeriesDownloads.seasonAllTicked(ticked, season: season1, tickable: tickable))
        // Ticks in another season survive taking this one off.
        ticked.insert("e5")
        ticked = SeriesDownloads.toggledSeason(ticked, season: season1, tickable: tickable)
        #expect(ticked == ["e5"])
        #expect(!SeriesDownloads.seasonAllTicked(ticked, season: season1, tickable: tickable))
    }

    @Test func aSeasonWithNothingTickableIsNeverAllTicked() {
        let season1 = series().filter { $0.season == 1 }
        #expect(!SeriesDownloads.seasonAllTicked([], season: season1, tickable: []))
    }

    @Test func thePillsCountTicksOfWhatCanBeTicked() {
        let list = series()
        let have: Set<String> = ["e5"]
        let tickable = Set(SeriesDownloads.tickable(list, have: have.contains).map(\.id))
        let season2 = list.filter { $0.season == 2 }
        let counts = SeriesDownloads.seasonTicks(["e4", "e6", "e1"], season: season2, tickable: tickable)
        #expect(counts.ticked == 2 && counts.of == 3, "e5 is here, so three can be ticked and e1 is another season's")
    }

    @Test func theTopAndTheBottomSayHowManyAndHowBig() {
        let list = series()
        #expect(SeriesDownloads.selectedWords(10) == "10 selected")
        #expect(SeriesDownloads.total([], among: list) == "Nothing selected")
        #expect(SeriesDownloads.total(["e1", "e2"], among: list) == "2 episodes · 2.0 GB")
    }
}
