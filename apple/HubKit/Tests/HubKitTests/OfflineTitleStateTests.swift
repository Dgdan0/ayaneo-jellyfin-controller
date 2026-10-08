import Foundation
import Testing
@testable import HubKit

/// The Download button on a title's page (#5): what it shows and says.
struct OfflineTitleStateTests {
    @Test func aFilmIsNothingComingFailedOrDownloaded() {
        #expect(OfflineTitleState.of(nil) == .none)
        #expect(OfflineTitleState.of(OfflineTests.movie("m", "Dune", state: .complete)) == .downloaded)
        #expect(OfflineTitleState.of(OfflineTests.movie("m", "Dune", state: .failed, done: 0)) == .failed)
        #expect(OfflineTitleState.of(OfflineTests.movie("m", "Dune", state: .downloading, done: 25, total: 100)) == .coming(0.25))
        #expect(OfflineTitleState.of(OfflineTests.movie("m", "Dune", state: .paused, done: 50, total: 100)) == .coming(0.5))
        // On the PC, the bar is how far the PC is.
        var preparing = OfflineTests.movie("m", "Dune", state: .preparing, done: 0)
        preparing.hubPercent = 40
        #expect(OfflineTitleState.of(preparing) == .coming(0.4))
    }

    @Test func aSeriesIsComingWhileAnyEpisodeIs() {
        let done = OfflineTests.episode("e1", "s", "Bleach", "s1", 1, 1)
        var moving = OfflineTests.episode("e2", "s", "Bleach", "s1", 1, 2)
        moving.state = .downloading
        moving.bytesDownloaded = 50
        var failed = OfflineTests.episode("e3", "s", "Bleach", "s1", 1, 3)
        failed.state = .failed
        #expect(OfflineTitleState.of(series: []) == .none)
        #expect(OfflineTitleState.of(series: [done]) == .downloaded)
        #expect(OfflineTitleState.of(series: [done, moving]) == .coming(0.5))
        #expect(OfflineTitleState.of(series: [done, failed]) == .failed)
        #expect(OfflineTitleState.of(series: [done, moving, failed]) == .coming(0.5))
        #expect(OfflineTitleState.seriesLine([done, moving]) == "1 episode on this device")
        #expect(OfflineTitleState.seriesLine([moving]) == nil)
    }

    @Test func itsWords() {
        #expect(OfflineTitleState.none.label == "Download")
        #expect(OfflineTitleState.coming(0.404).label == "Downloading · 40%")
        #expect(OfflineTitleState.failed.label == "Download failed")
        #expect(OfflineTitleState.downloaded.label == "Downloaded")
        #expect(OfflineTitleState.confirmTitle("Dune: Part Two") == "Download Dune: Part Two?")
        #expect(OfflineTitleState.confirmDetail(free: 128 * 1_073_741_824).hasSuffix("128 GB free on this device."))
    }
}
