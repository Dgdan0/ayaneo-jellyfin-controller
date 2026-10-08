import Foundation
import Testing
@testable import HubKit

/// Keep ready (#48), the owner's one-episode buffer: which episodes it
/// downloads, and the only time it removes one.
struct KeepReadyTests {
    /// Season 1 of four and season 2 of three: e1...e7.
    private func series(played: Set<String> = []) -> [DownloadEpisode] {
        var out: [DownloadEpisode] = []
        var number = 0
        for (season, count) in [(1, 4), (2, 3)] {
            for index in 1...count {
                number += 1
                out.append(DownloadEpisode(id: "e\(number)", seasonId: "s\(season)", season: season, index: index,
                                           played: played.contains("e\(number)"), bytes: 100))
            }
        }
        return out
    }

    private func plan(played: Set<String> = [], target: String, count: Int = 3, have: Set<String> = [],
                      complete: Set<String>? = nil, managed: Set<String> = [], playing: Bool = false) -> KeepReady.Plan {
        KeepReady.plan(episodes: series(played: played), playTargetId: target, count: count, have: have.contains,
                       complete: complete ?? have, managed: managed, playing: playing)
    }

    // MARK: The window

    @Test func theNumberRunsFromOneToTenAndThreeIsTheDefault() {
        #expect(KeepReady.range == 1...10 && KeepReady.defaultCount == 3)
        #expect(KeepReady.clamp(0) == 1 && KeepReady.clamp(11) == 10 && KeepReady.clamp(4) == 4)
    }

    @Test func theWindowIsTheNextUnwatchedFromWherePlayStartsAcrossASeasonsEnd() {
        let chain = KeepReady.chain(series(played: ["e1"]))
        #expect(KeepReady.window(chain, playTargetId: "e3", count: 3).map(\.id) == ["e3", "e4", "e5"])
        #expect(KeepReady.window(chain, playTargetId: "e3", count: 1).map(\.id) == ["e3"])
        // Watched ones after the target are not "unwatched ahead".
        let skipped = KeepReady.chain(series(played: ["e1", "e4"]))
        #expect(KeepReady.window(skipped, playTargetId: "e3", count: 3).map(\.id) == ["e3", "e5", "e6"])
    }

    @Test func specialsAreNotInTheChain() {
        var list = series()
        list.append(DownloadEpisode(id: "sp", seasonId: "s0", season: 0, index: 1))
        #expect(KeepReady.chain(list).map(\.id) == ["e1", "e2", "e3", "e4", "e5", "e6", "e7"])
    }

    @Test func aTargetNotInTheChainStartsAtTheFirstUnwatched() {
        let chain = KeepReady.chain(series(played: ["e1", "e2"]))
        #expect(KeepReady.window(chain, playTargetId: "", count: 2).map(\.id) == ["e3", "e4"])
        #expect(KeepReady.window(chain, playTargetId: "gone", count: 2).map(\.id) == ["e3", "e4"])
    }

    @Test func anEpisodeThePCCannotMakeIsLeftOutOfTheWindow() {
        var list = series()
        list[1].available = false
        let chain = KeepReady.chain(list)
        #expect(KeepReady.window(chain, playTargetId: "e1", count: 3).map(\.id) == ["e1", "e3", "e4"])
    }

    // MARK: What it downloads

    @Test func firstItDownloadsTheNextThreeFromWherePlayStarts() {
        let first = plan(target: "e1")
        #expect(first.download.map(\.id) == ["e1", "e2", "e3"] && first.remove.isEmpty)
    }

    @Test func whatIsHereOrComingCountsTowardsTheNumberWhoeverDownloadedIt() {
        // The person downloaded e2 and e3 by hand: only e1 is missing.
        let mixed = plan(target: "e1", have: ["e2", "e3"])
        #expect(mixed.download.map(\.id) == ["e1"])
        // More than enough ahead already: nothing is downloaded and nothing is removed.
        let plenty = plan(target: "e1", have: ["e1", "e2", "e3", "e4", "e5"])
        #expect(plenty.isEmpty)
    }

    // MARK: The owner's A, B, C

    @Test func finishingTheFirstStartsTheNextAndKeepsTheOneJustFinished() {
        // A, B, C are e1, e2, e3, all Keep ready's. A is finished: Play starts B.
        let afterA = plan(played: ["e1"], target: "e2", have: ["e1", "e2", "e3"], managed: ["e1", "e2", "e3"])
        #expect(afterA.download.map(\.id) == ["e4"], "D starts")
        #expect(afterA.remove.isEmpty, "A stays: the one after it is not finished")
    }

    @Test func finishingTheSecondRemovesTheFirstAndStartsTheNext() {
        let afterB = plan(played: ["e1", "e2"], target: "e3", have: ["e1", "e2", "e3", "e4"], managed: ["e1", "e2", "e3", "e4"])
        #expect(afterB.download.map(\.id) == ["e5"], "E starts")
        #expect(afterB.remove == ["e1"], "A goes, and B, the one just finished, stays")
    }

    @Test func fallingAsleepAfterTheFirstLeavesItThere() {
        // A finishes and B autoplays, but B is not finished: A is still on the device.
        let asleep = plan(played: ["e1"], target: "e2", have: ["e1", "e2", "e3", "e4"], managed: ["e1", "e2", "e3", "e4"])
        #expect(asleep.remove.isEmpty)
    }

    // MARK: Only its own, and only when it is safe

    @Test func onlyWhatKeepReadyDownloadedIsEverRemoved() {
        // The person downloaded e1 and e2 by hand; Keep ready did e3 and e4.
        let result = plan(played: ["e1", "e2", "e3"], target: "e4", have: ["e1", "e2", "e3", "e4", "e5", "e6"],
                          managed: ["e3", "e4", "e5", "e6"])
        #expect(!result.remove.contains("e1") && !result.remove.contains("e2"), "the person's own stay")
        #expect(result.remove == [], "e3's next, e4, is not finished")
        let later = plan(played: ["e1", "e2", "e3", "e4"], target: "e5", have: ["e1", "e2", "e3", "e4", "e5", "e6", "e7"],
                         managed: ["e3", "e4", "e5", "e6"])
        #expect(later.remove == ["e3"])
    }

    @Test func nothingIsRemovedWhileSomethingPlays() {
        let playing = plan(played: ["e1", "e2"], target: "e3", have: ["e1", "e2", "e3", "e4"], managed: ["e1", "e2", "e3", "e4"],
                           playing: true)
        #expect(playing.remove.isEmpty)
        #expect(playing.download.map(\.id) == ["e5"], "what to get is still asked")
        let after = plan(played: ["e1", "e2"], target: "e3", have: ["e1", "e2", "e3", "e4"], managed: ["e1", "e2", "e3", "e4"])
        #expect(after.remove == ["e1"], "once the player is left")
    }

    @Test func anEpisodeThatIsNotFinishedOnTheDeviceIsNotRemoved() {
        // e1 is Keep ready's but still coming: only a finished download can be removed.
        let result = plan(played: ["e1", "e2"], target: "e3", have: ["e1", "e3"], complete: ["e3"], managed: ["e1", "e3"])
        #expect(result.remove.isEmpty)
    }

    @Test func anEpisodeMarkedUnwatchedAgainIsKept() {
        // B was finished, so A would go; B is marked unwatched again, and A stays.
        let reset = plan(played: ["e1"], target: "e2", have: ["e1", "e2", "e3"], managed: ["e1", "e2", "e3"])
        #expect(reset.remove.isEmpty)
        // And an unwatched episode is never removed, whatever follows it.
        let unwatched = plan(played: ["e2"], target: "e1", have: ["e1", "e2", "e3"], managed: ["e1", "e2", "e3"])
        #expect(unwatched.remove.isEmpty)
    }

    @Test func finishingOnAnotherDeviceCounts() {
        // The watched state is what the hub says: e1 and e2 are finished, wherever they were.
        let remote = plan(played: ["e1", "e2"], target: "e3", have: ["e1", "e2", "e3"], managed: ["e1", "e2", "e3"])
        #expect(remote.remove == ["e1"])
    }

    @Test func theLastEpisodeOfASeriesHasNoNextAndIsNeverRemoved() {
        let done = plan(played: Set((1...7).map { "e\($0)" }), target: "e7", have: ["e6", "e7"], managed: ["e6", "e7"])
        #expect(done.remove == ["e6"], "e6's next, e7, is finished")
        #expect(!done.remove.contains("e7"))
        #expect(done.download.isEmpty, "nothing unwatched is left to keep ready")
    }

    @Test func theNextAcrossASeasonsEndIsTheNextSeasonsFirst() {
        // e4 ends season 1: it goes once e5, season 2's first, is finished.
        let before = plan(played: ["e3", "e4"], target: "e5", have: ["e4", "e5"], managed: ["e4"])
        #expect(before.remove.isEmpty)
        let after = plan(played: ["e3", "e4", "e5"], target: "e6", have: ["e4", "e5", "e6"], managed: ["e4"])
        #expect(after.remove == ["e4"])
    }

    // MARK: The setting, on the device

    private func store() -> (KeepReadyStore, URL) {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("keep-ready-\(UUID().uuidString).json")
        return (KeepReadyStore(file: file), file)
    }

    @Test func theSettingIsKeptPerSeriesAndPerProfileAndSurvivesARestart() {
        let (store, file) = store()
        defer { try? FileManager.default.removeItem(at: file) }
        #expect(store.count(userId: "u", seriesId: "s") == nil, "off until it is turned on")
        store.enable(userId: "u", seriesId: "s", count: 4)
        store.enable(userId: "v", seriesId: "s", count: 2)
        #expect(store.count(userId: "u", seriesId: "s") == 4 && store.count(userId: "v", seriesId: "s") == 2)
        #expect(store.count(userId: "u", seriesId: "other") == nil)
        let again = KeepReadyStore(file: file)
        #expect(again.count(userId: "u", seriesId: "s") == 4)
        #expect(again.series(userId: "u").map(\.seriesId) == ["s"])
    }

    @Test func theNumberIsHeldBetweenOneAndTen() {
        let (store, file) = store()
        defer { try? FileManager.default.removeItem(at: file) }
        store.enable(userId: "u", seriesId: "s", count: 99)
        #expect(store.count(userId: "u", seriesId: "s") == 10)
        store.enable(userId: "u", seriesId: "s", count: 0)
        #expect(store.count(userId: "u", seriesId: "s") == 1)
    }

    @Test func changingTheNumberKeepsWhatItAlreadyDownloaded() {
        let (store, file) = store()
        defer { try? FileManager.default.removeItem(at: file) }
        store.enable(userId: "u", seriesId: "s", count: 3)
        store.mark(userId: "u", seriesId: "s", ids: ["e1", "e2"])
        store.enable(userId: "u", seriesId: "s", count: 5)
        #expect(store.managed(userId: "u", seriesId: "s") == ["e1", "e2"])
    }

    @Test func turningItOffKeepsTheFilesAndMakesThemThePersons() {
        let (store, file) = store()
        defer { try? FileManager.default.removeItem(at: file) }
        store.enable(userId: "u", seriesId: "s", count: 3)
        store.mark(userId: "u", seriesId: "s", ids: ["e1"])
        store.disable(userId: "u", seriesId: "s")
        #expect(store.count(userId: "u", seriesId: "s") == nil)
        #expect(store.managed(userId: "u", seriesId: "s").isEmpty, "nothing is Keep ready's to remove any more")
        // Turned on again, it starts from nothing: e1 stays the person's own.
        store.enable(userId: "u", seriesId: "s", count: 3)
        #expect(store.managed(userId: "u", seriesId: "s").isEmpty)
    }

    @Test func anEpisodeTheyDownloadByHandIsTakenOffTheList() {
        let (store, file) = store()
        defer { try? FileManager.default.removeItem(at: file) }
        store.enable(userId: "u", seriesId: "s", count: 3)
        store.mark(userId: "u", seriesId: "s", ids: ["e1", "e2"])
        store.unmark(userId: "u", ids: ["e2"])
        #expect(store.managed(userId: "u", seriesId: "s") == ["e1"])
        // Marking a series that has no Keep ready does nothing.
        store.mark(userId: "u", seriesId: "none", ids: ["x"])
        #expect(store.managed(userId: "u", seriesId: "none").isEmpty)
    }

    @Test func whatLeftTheDeviceIsPrunedFromTheList() {
        let (store, file) = store()
        defer { try? FileManager.default.removeItem(at: file) }
        store.enable(userId: "u", seriesId: "s", count: 3)
        store.mark(userId: "u", seriesId: "s", ids: ["e1", "e2", "e3"])
        store.prune(userId: "u", seriesId: "s", stored: ["e2", "e3"])
        #expect(store.managed(userId: "u", seriesId: "s") == ["e2", "e3"])
    }

    @Test func theStoreLivesBesideTheDownloads() {
        let root = OfflineTests.folder()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = OfflineStore(root: root)
        store.keepReady.enable(userId: "u", seriesId: "s", count: 2)
        #expect(OfflineStore(root: root).keepReady.count(userId: "u", seriesId: "s") == 2)
    }
}
