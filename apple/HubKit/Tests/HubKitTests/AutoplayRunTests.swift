import Testing
@testable import HubKit

/// "Still watching?" (#48): Android's `UpNextTest` cases for `AutoplayRun`,
/// case for case, then the line the question shows.
struct AutoplayRunTests {
    @Test func threeEpisodesStartByThemselvesTheFourthAsks() {
        var run = AutoplayRun()
        // The owner started the first episode: the next three follow by themselves.
        for number in 1...3 {
            #expect(run.mayAutoplay, "autoplay number \(number)")
            run.autoplayed()
        }
        #expect(!run.mayAutoplay, "before the fourth, it asks")
        #expect(run.run == 3)
    }

    @Test func anyInputPutsTheCountBackSoAPersonWhoIsThereIsNeverAsked() {
        var run = AutoplayRun()
        run.autoplayed()
        run.autoplayed()
        run.input()
        #expect(run.run == 0)
        for _ in 1...3 {
            #expect(run.mayAutoplay)
            run.autoplayed()
        }
        #expect(!run.mayAutoplay)
        // Keep watching is somebody there: the count starts again.
        run.input()
        #expect(run.mayAutoplay)
        #expect(run.run == 0)
    }

    @Test func theLimitCanBeGivenAndIsThree() {
        #expect(AutoplayRun.limit == 3)
        var one = AutoplayRun(limit: 1)
        #expect(one.mayAutoplay)
        one.autoplayed()
        #expect(!one.mayAutoplay)
        #expect(AutoplayRun.question == "Still watching?")
        #expect(AutoplayRun.keepWatching == "Keep watching")
        #expect(AutoplayRun.stop == "Stop")
    }

    @Test func aRunCanStartPartWayButNeverBelowNone() {
        #expect(!AutoplayRun(run: 3).mayAutoplay)
        #expect(AutoplayRun(run: -2).run == 0)
    }

    @Test func theLineSaysHowManyAndWhatIsNext() {
        let next = PlaybackItem(id: "e4", type: "episode", title: "Dead Weight", seasonNumber: 1, episodeNumber: 4)
        #expect(AutoplayRun.line(episodes: 3, next: next) == "Paused after 3 episodes in a row.\nNext: S1E4 · Dead Weight")
        #expect(AutoplayRun.line(episodes: 1, next: nil) == "Paused after 1 episode in a row.")
    }
}
