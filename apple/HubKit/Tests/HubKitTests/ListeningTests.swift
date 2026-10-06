import Foundation
import Testing
@testable import HubKit

/// Listening's arithmetic (#25 phase 2): Android's `ListeningTest`,
/// `SyncThrottleTest` and the listening labels of `PlayerLabelsTest`.
struct ListeningTests {
    @Test func oneSoundAtATime() {
        var arbiter = AudioArbiter()
        let first = arbiter.start(.audiobook)
        #expect(first.isEmpty)
        // Video starts: the audiobook pauses.
        let video = arbiter.start(.video)
        #expect(video == [.audiobook])
        #expect(arbiter.sounding == [.video])
        // Narration over video: the video pauses.
        let narration = arbiter.start(.narration)
        #expect(narration == [.video])
        // A player restarting itself pauses nothing.
        let again = arbiter.start(.narration)
        #expect(again.isEmpty)
        arbiter.stop(.narration)
        #expect(arbiter.sounding.isEmpty)
        let book = arbiter.start(.audiobook)
        #expect(book.isEmpty)
        // A stop for something not playing changes nothing.
        arbiter.stop(.video)
        #expect(arbiter.sounding == [.audiobook])
    }

    @Test func speedsRunFromThreeQuartersToThree() {
        #expect(Listening.nextSpeed(1) == 1.1)
        #expect(Listening.nextSpeed(3) == 0.75)
        #expect(Listening.nextSpeed(1.3) == 0.75)
        #expect(Listening.clampSpeed(9) == 3)
        #expect(Listening.clampSpeed(0.2) == 0.75)
    }

    @Test func timeLeftIsAsHeardAtTheSpeedPlaying() {
        #expect(Listening.partLeft(positionMs: 30_000, partMs: 60_000, speed: 1) == 30_000)
        #expect(Listening.partLeft(positionMs: 30_000, partMs: 60_000, speed: 2) == 15_000)
        // The rest of part 1 and all of part 2, at one and a half.
        #expect(Listening.bookLeft(part: 1, positionMs: 30_000, partsMs: [60_000, 60_000, 60_000], speed: 1.5) == 60_000)
        // A length still being read: no book total yet.
        #expect(Listening.bookLeft(part: 0, positionMs: 0, partsMs: [60_000, nil], speed: 1) == nil)
        #expect(PlayerLabels.timeLeft(partLeftMs: 59_000, bookLeftMs: 7_500_000) == "1 min left in part · 2h 5m in book")
        #expect(PlayerLabels.timeLeft(partLeftMs: 12 * 60_000, bookLeftMs: nil) == "12 min left in part")
    }

    @Test func aMinutesTimerCountsWhilePlayingFadesOverItsLastHalfMinuteAndRunsOut() {
        var timer = SleepTimer.start(.minutes(15), partLeftHeardMs: 0)
        #expect(timer.remainingMs == 15 * 60_000)
        #expect(timer.volume == 1)
        timer = timer.tick(elapsedMs: 15 * 60_000 - 15_000, partLeftHeardMs: 999_999)
        #expect(timer.fading)
        #expect(abs(timer.volume - 0.5) < 0.001)
        #expect(PlayerLabels.sleep(timer) == "Sleep · fading")
        // A control while it fades: the fifteen minutes start again.
        timer = timer.extended(partLeftHeardMs: 0, nextPartHeardMs: nil)
        #expect(timer.remainingMs == 15 * 60_000)
        #expect(PlayerLabels.sleep(timer) == "Sleep · 15:00")
        timer = timer.tick(elapsedMs: 16 * 60_000, partLeftHeardMs: 0)
        #expect(timer.runsOut)
        #expect(timer.volume == 0)
    }

    @Test func theEndOfAPartIsReadFromThePlayerAndAControlCarriesItToTheNextPartsEnd() {
        var timer = SleepTimer.start(.endOfPart, partLeftHeardMs: 90_000)
        #expect(PlayerLabels.sleep(timer) == "Sleep · end of part")
        // The player moving on is the moment it stops, not a tick that might fall after it.
        #expect(timer.endsWithPart)
        #expect(!SleepTimer.start(.minutes(5), partLeftHeardMs: 0).endsWithPart)
        timer = timer.tick(elapsedMs: 1_000, partLeftHeardMs: 20_000)
        #expect(timer.fading)
        timer = timer.extended(partLeftHeardMs: 20_000, nextPartHeardMs: 300_000)
        #expect(timer.remainingMs == 320_000)
        #expect(timer.skipParts == 1)
        #expect(!timer.endsWithPart)
        #expect(!timer.fading)
        // While carried, it counts down itself rather than reading this part's end.
        timer = timer.tick(elapsedMs: 10_000, partLeftHeardMs: 10_000)
        #expect(timer.remainingMs == 310_000)
        // The next part begins: count to its end again.
        timer = timer.partChanged(partLeftHeardMs: 300_000)
        #expect(timer.skipParts == 0)
        #expect(timer.endsWithPart)
        #expect(timer.remainingMs == 300_000)
        #expect(PlayerLabels.sleepChoice(.endOfPart) == "End of this part")
        #expect(PlayerLabels.sleepChoice(.minutes(60)) == "1 hour")
        #expect(PlayerLabels.sleepChoice(.minutes(15)) == "15 minutes")
        #expect(PlayerLabels.sleep(nil) == "Sleep")
    }

    @Test func stoppingForSleepStepsBackOverWhatFaded() {
        #expect(SmartRewind.afterSleep(120_000) == 90_000)
        #expect(SmartRewind.afterSleep(10_000) == 0)
    }

    @Test func theFirstSyncRunsAtOnceTheNextWaitsOutTheIntervalAndNoneWaitsLonger() {
        var throttle = SyncThrottle(intervalMs: 15_000)
        #expect(throttle.waitFor(now: 1_000) == 0)
        throttle.ran(now: 1_000)
        // A pause two seconds later waits for the rest of the fifteen.
        #expect(throttle.waitFor(now: 3_000) == 13_000)
        // Asked again later, it waits only what is left: the last asked for runs then.
        #expect(throttle.waitFor(now: 15_000) == 1_000)
        #expect(throttle.waitFor(now: 16_000) == 0)
        #expect(throttle.waitFor(now: 60_000) == 0)
        throttle.ran(now: 60_000)
        #expect(throttle.waitFor(now: 60_000) == 15_000)
    }

    @Test func aListeningSpeedHasNoNought() {
        #expect(PlayerLabels.rate(1) == "1×")
        #expect(PlayerLabels.rate(1.25) == "1.25×")
        #expect(PlayerLabels.rate(0.75) == "0.75×")
        #expect(PlayerLabels.rate(1.1) == "1.1×")
        #expect(PlayerLabels.rate(2) == "2×")
    }

    @Test func aJumpCrossesPartsAndStopsAtALengthNotYetKnown() {
        let parts: [Int64?] = [60_000, 120_000, 30_000]
        func at(_ place: (part: Int, offsetMs: Int64)) -> String { "\(place.part)@\(place.offsetMs)" }
        // Within a part.
        #expect(at(Listening.jump(part: 1, positionMs: 50_000, by: 10_000, partsMs: parts)) == "1@60000")
        // Back past a part's start: into the end of the one before.
        #expect(at(Listening.jump(part: 1, positionMs: 5_000, by: -10_000, partsMs: parts)) == "0@55000")
        // On past a part's end: into the next.
        #expect(at(Listening.jump(part: 0, positionMs: 55_000, by: 10_000, partsMs: parts)) == "1@5000")
        // Never before the book's start, and the last part holds whatever is left.
        #expect(at(Listening.jump(part: 0, positionMs: 3_000, by: -10_000, partsMs: parts)) == "0@0")
        #expect(at(Listening.jump(part: 2, positionMs: 25_000, by: 30_000, partsMs: parts)) == "2@55000")
        // A length not yet known stops the jump there, unless the player knows the part playing.
        #expect(at(Listening.jump(part: 0, positionMs: 55_000, by: 10_000, partsMs: [nil, 120_000])) == "0@65000")
        #expect(at(Listening.jump(part: 0, positionMs: 55_000, by: 10_000, partsMs: [nil, 120_000],
                                  currentPartMs: 60_000)) == "1@5000")
    }
}
