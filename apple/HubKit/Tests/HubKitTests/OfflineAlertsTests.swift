import Foundation
import Testing
@testable import HubKit

/// The notifications for a download that finished or failed (#43).
struct OfflineAlertsTests {
    @Test func aDownloadThatFinishesOrFailsSaysSoOnceAndTheFirstLookSaysNothing() {
        var film = OfflineTests.movie("m", "Inception", state: .downloading, done: 10, total: 100)
        var episode = OfflineTests.episode("e", "s", "Bleach", "s1", 1, 2)
        episode.state = .preparing
        // The first look at what is there: nothing is news.
        #expect(OfflineAlerts.changes(before: nil, rows: [film, episode]).isEmpty)
        let before = OfflineAlerts.states([film, episode])

        film.state = .complete
        episode.state = .failed
        episode.error = "The PC could not make this download's MP4."
        let alerts = OfflineAlerts.changes(before: before, rows: [film, episode])
        #expect(alerts.map(\.title) == ["Downloaded", "Download failed"])
        #expect(alerts[0].body == "Inception is ready to watch offline.")
        #expect(alerts[0].id == "offline:m:complete")
        #expect(alerts[1].body == "Bleach · S1E2 · Episode 2 · The PC could not make this download's MP4.")
        #expect(alerts[1].failed)
        #expect(alerts[1].batchId == episode.batchId)

        // Looked at again, the same outcome is not news.
        #expect(OfflineAlerts.changes(before: OfflineAlerts.states([film, episode]), rows: [film, episode]).isEmpty)
        // A failure with no words says where to go.
        episode.error = ""
        #expect(OfflineAlerts.changes(before: before, rows: [episode]).first?.body == "Bleach · S1E2 · Episode 2 · Open Downloads to try again.")
    }

    @Test func movingAlongIsNotNews() {
        var film = OfflineTests.movie("m", "Dune", state: .queued, done: 0)
        let before = OfflineAlerts.states([film])
        for state in [OfflineState.preparing, .downloading, .paused, .waiting] {
            film.state = state
            #expect(OfflineAlerts.changes(before: before, rows: [film]).isEmpty)
        }
    }
}
