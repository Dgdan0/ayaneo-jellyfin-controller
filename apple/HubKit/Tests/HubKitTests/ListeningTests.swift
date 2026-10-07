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
        #expect(Listening.heard(60_000 - 30_000, speed: 1) == 30_000)
        #expect(Listening.heard(60_000 - 30_000, speed: 2) == 15_000)
        // The rest of part 1 and all of part 2, at one and a half.
        #expect(Listening.bookLeft(part: 1, positionMs: 30_000, partsMs: [60_000, 60_000, 60_000], speed: 1.5) == 60_000)
        // A length still being read: no book total yet.
        #expect(Listening.bookLeft(part: 0, positionMs: 0, partsMs: [60_000, nil], speed: 1) == nil)
        #expect(PlayerLabels.timeLeft(entryLeftMs: 59_000, bookLeftMs: 7_500_000) == "1 min left in part · 2h 5m in book")
        #expect(PlayerLabels.timeLeft(entryLeftMs: 12 * 60_000, bookLeftMs: nil) == "12 min left in part")
    }

    @Test func aMinutesTimerCountsWhilePlayingFadesOverItsLastHalfMinuteAndRunsOut() {
        var timer = SleepTimer.start(.minutes(15), entryLeftHeardMs: 0)
        #expect(timer.remainingMs == 15 * 60_000)
        #expect(timer.volume == 1)
        timer = timer.tick(elapsedMs: 15 * 60_000 - 15_000, entryLeftHeardMs: 999_999)
        #expect(timer.fading)
        #expect(abs(timer.volume - 0.5) < 0.001)
        #expect(PlayerLabels.sleep(timer) == "Sleep · fading")
        // A control while it fades: the fifteen minutes start again.
        timer = timer.extended(entryLeftHeardMs: 0, nextEntryHeardMs: nil)
        #expect(timer.remainingMs == 15 * 60_000)
        #expect(PlayerLabels.sleep(timer) == "Sleep · 15:00")
        timer = timer.tick(elapsedMs: 16 * 60_000, entryLeftHeardMs: 0)
        #expect(timer.runsOut)
        #expect(timer.volume == 0)
    }

    @Test func theEndOfAPartIsReadFromThePlayerAndAControlCarriesItToTheNextPartsEnd() {
        var timer = SleepTimer.start(.endOfPart, entryLeftHeardMs: 90_000)
        #expect(PlayerLabels.sleep(timer) == "Sleep · end of part")
        // The player moving on is the moment it stops, not a tick that might fall after it.
        #expect(timer.endsWithPart)
        #expect(!SleepTimer.start(.minutes(5), entryLeftHeardMs: 0).endsWithPart)
        timer = timer.tick(elapsedMs: 1_000, entryLeftHeardMs: 20_000)
        #expect(timer.fading)
        timer = timer.extended(entryLeftHeardMs: 20_000, nextEntryHeardMs: 300_000)
        #expect(timer.remainingMs == 320_000)
        #expect(timer.skipParts == 1)
        #expect(!timer.endsWithPart)
        #expect(!timer.fading)
        // While carried, it counts down itself rather than reading this part's end.
        timer = timer.tick(elapsedMs: 10_000, entryLeftHeardMs: 10_000)
        #expect(timer.remainingMs == 310_000)
        // The next part begins: count to its end again.
        timer = timer.partChanged(entryLeftHeardMs: 300_000)
        #expect(timer.skipParts == 0)
        #expect(timer.endsWithPart)
        #expect(timer.remainingMs == 300_000)
        #expect(PlayerLabels.sleepChoice(.endOfPart) == "End of this part")
        #expect(PlayerLabels.sleepChoice(.minutes(60)) == "1 hour")
        #expect(PlayerLabels.sleepChoice(.minutes(15)) == "15 minutes")
        #expect(PlayerLabels.sleep(nil) == "Sleep")
    }

    private func at(_ place: (part: Int, offsetMs: Int64)) -> String { "\(place.part)@\(place.offsetMs)" }

    @Test func stoppingForSleepStepsBackOverWhatFadedIntoThePartBeforeWhenItBeganLessThanThatAgo() {
        let lengths: [Int64?] = [600_000, 1_200_000]
        #expect(at(SmartRewind.afterSleep(part: 1, positionMs: 120_000, partsMs: lengths)) == "1@90000")
        #expect(at(SmartRewind.afterSleep(part: 0, positionMs: 10_000, partsMs: lengths)) == "0@0")
        // A chapter that ends five seconds into a track: what faded was in the track before it (#31).
        #expect(at(SmartRewind.afterSleep(part: 1, positionMs: 5_000, partsMs: lengths)) == "0@575000")
        // A length it cannot count back through: as far as the start of the part.
        #expect(at(SmartRewind.afterSleep(part: 1, positionMs: 5_000, partsMs: [nil, 1_200_000])) == "1@0")
        #expect(at(SmartRewind.afterSleep(part: 1, positionMs: 5_000, partsMs: [])) == "1@0")
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

    /// Within a billionth: how far through a book is a ratio of whole numbers.
    private func near(_ value: Double?, _ expected: Double) -> Bool {
        guard let value else { return false }
        return abs(value - expected) < 1e-9
    }

    @Test func howFarThroughTheBookIsOfTheRecordingWhateverTheSpeed() {
        let parts: [Int64?] = [600_000, 1_200_000, 300_000]
        #expect(Listening.bookProgress(part: 0, positionMs: 0, partsMs: parts) == 0)
        // The first part and five minutes of the second, of thirty-five minutes in all.
        #expect(near(Listening.bookProgress(part: 1, positionMs: 300_000, partsMs: parts), 900_000.0 / 2_100_000))
        #expect(Listening.bookProgress(part: 2, positionMs: 300_000, partsMs: parts) == 1)
        // A player runs a little past a part's length, and may report a moment before its start.
        #expect(Listening.bookProgress(part: 2, positionMs: 999_999, partsMs: parts) == 1)
        #expect(near(Listening.bookProgress(part: 1, positionMs: -5, partsMs: parts), 600_000.0 / 2_100_000))
        // A length still being read, or a part the book does not have: nothing to say, which is not 0.
        #expect(Listening.bookProgress(part: 0, positionMs: 0, partsMs: [600_000, nil]) == nil)
        #expect(Listening.bookProgress(part: 0, positionMs: 0, partsMs: [600_000, 0]) == nil)
        #expect(Listening.bookProgress(part: 3, positionMs: 0, partsMs: parts) == nil)
        #expect(Listening.bookProgress(part: -1, positionMs: 0, partsMs: parts) == nil)
        #expect(Listening.bookProgress(part: 0, positionMs: 0, partsMs: []) == nil)
    }

    @Test func aJumpCrossesPartsAndStopsAtALengthNotYetKnown() {
        let parts: [Int64?] = [60_000, 120_000, 30_000]
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

    // MARK: Across the parts (#31)

    private let across: [Int64?] = [600_000, 1_200_000, 300_000]

    @Test func theRecordingBetweenTwoPlacesCountsThePartsBetweenThem() {
        #expect(Listening.distance(fromPart: 1, fromMs: 10_000, toPart: 1, toMs: 15_000, partsMs: across) == 5_000)
        #expect(Listening.distance(fromPart: 1, fromMs: 10_000, toPart: 1, toMs: 10_000, partsMs: across) == 0)
        // The rest of the first, all of the second, and the start of the third.
        let rest: Int64 = 590_000 + 1_200_000 + 20_000
        #expect(Listening.distance(fromPart: 0, fromMs: 10_000, toPart: 2, toMs: 20_000, partsMs: across) == rest)
        #expect(Listening.distance(fromPart: 1, fromMs: 0, toPart: 2, toMs: 0, partsMs: across) == 1_200_000)
        // Not backwards.
        #expect(Listening.distance(fromPart: 1, fromMs: 15_000, toPart: 1, toMs: 10_000, partsMs: across) == nil)
        #expect(Listening.distance(fromPart: 2, fromMs: 0, toPart: 1, toMs: 0, partsMs: across) == nil)
        // A length it needs not known: nothing to say, which is not 0. Within one part, none is needed.
        #expect(Listening.distance(fromPart: 0, fromMs: 10_000, toPart: 2, toMs: 20_000, partsMs: [600_000, nil, 300_000]) == nil)
        #expect(Listening.distance(fromPart: 0, fromMs: 10_000, toPart: 1, toMs: 20_000, partsMs: [0, 1_200_000, 300_000]) == nil)
        #expect(Listening.distance(fromPart: 1, fromMs: 10_000, toPart: 1, toMs: 15_000, partsMs: [nil, nil, nil]) == 5_000)
    }

    @Test func aJumpGoesAcrossThePartsBackIntoTheOneBeforeAndOnIntoTheNext() {
        #expect(at(Listening.jump(part: 0, positionMs: 580_000, by: 15_000, partsMs: across)) == "0@595000")
        // On into the next part, and back into the one before.
        #expect(at(Listening.jump(part: 0, positionMs: 595_000, by: 10_000, partsMs: across)) == "1@5000")
        #expect(at(Listening.jump(part: 1, positionMs: 5_000, by: -10_000, partsMs: across)) == "0@595000")
        // Over a whole part.
        #expect(at(Listening.jump(part: 1, positionMs: 1_100_000, by: 200_000, partsMs: across)) == "2@100000")
        // Back over a whole part, and the start of the one before.
        #expect(at(Listening.jump(part: 2, positionMs: 1_000, by: -1_202_000, partsMs: across)) == "0@599000")
        // Not before the book's start, and not through a part whose length is not known.
        #expect(at(Listening.jump(part: 0, positionMs: 5_000, by: -15_000, partsMs: across)) == "0@0")
        #expect(at(Listening.jump(part: 1, positionMs: 5_000, by: -10_000, partsMs: [nil, 1_200_000, 300_000])) == "1@0")
        #expect(at(Listening.jump(part: 2, positionMs: 5_000, by: -1_500_000, partsMs: [600_000, nil, 300_000])) == "2@0")
        // A length not yet known stops it, unless it is the part playing, which the player knows.
        #expect(at(Listening.jump(part: 0, positionMs: 650_000, by: 50_000, partsMs: [nil, 1_200_000, 300_000])) == "0@700000")
        #expect(at(Listening.jump(part: 0, positionMs: 650_000, by: 50_000, partsMs: [nil, 1_200_000, 300_000],
                                  currentPartMs: 600_000)) == "1@100000")
    }

    // MARK: The sleep timer by the chapter (#31)

    // Three tracks of 100 seconds, and a chapter that starts 30 seconds before the first track's end and ends 30 seconds into the next.
    private let tracks: [Int64?] = [100_000, 100_000, 100_000]
    private var bookEntries: [AudiobookContents.Entry] {
        AudiobookContents.entries(tracks.enumerated().map { AudiobookPart(title: "Track \($0.offset)", url: "u\($0.offset)",
                                                                          durationMs: $0.element) },
                                  partsMs: tracks,
                                  chapters: [ReadingAudioChapter(title: "One", startMs: 0, track: 0, source: "book"),
                                             ReadingAudioChapter(title: "Two", startMs: 70_000, track: 0, source: "book"),
                                             ReadingAudioChapter(title: "Three", startMs: 30_000, track: 1, source: "book")])
    }

    /// What is left of the chapter playing, as heard, and which it is, counted as the player would.
    private func span(_ entries: [AudiobookContents.Entry], _ part: Int, _ position: Int64) -> AudiobookContents.Span {
        AudiobookContents.span(entries, part: part, positionMs: position, partMs: tracks[part]!, partsMs: tracks)
    }

    @Test func theEndOfAChapterThatRunsIntoTheNextTrackIsTheChaptersEndNotTheTracks() {
        let entries = bookEntries
        // Listening to Two from 80 seconds into the first track: 50 seconds to its end, 30 seconds into the second.
        var part = 0
        var position: Int64 = 80_000
        var timer = SleepTimer.start(.endOfPart, entryLeftHeardMs: span(entries, part, position).leftMs,
                                     entry: span(entries, part, position).entry)
        #expect(timer.remainingMs == 50_000)
        #expect(timer.entry == 1)
        var ticks = 0
        var stoppedAt: String?
        var leftAtTheBoundary = Int64.max
        while stoppedAt == nil && ticks < 1_000 {
            ticks += 1
            // Half a second of listening; the player goes on into the next track as the first one ends.
            position += 500
            if part == 0 && position >= 100_000 {
                part = 1
                position -= 100_000
            }
            let now = span(entries, part, position)
            if part == 1 && position == 0 { leftAtTheBoundary = now.leftMs }
            timer = timer.tick(elapsedMs: 500, entryLeftHeardMs: now.leftMs, entry: now.entry)
            if timer.runsOut { stoppedAt = "\(part)@\(position)" }
        }
        // It did not stop with the first track, which is where the part's end would have been.
        #expect(leftAtTheBoundary == 30_000)
        #expect(stoppedAt == "1@30000")
        // The queue plays on through that change of track: the chapter does not end with it.
        #expect(!AudiobookContents.endsWithPart(entries, part: 0, lengthMs: 100_000))
    }

    @Test func aChapterThatEndedBetweenTwoTicksIsTheEndAndAMoveToAnotherChapterIsFollowed() {
        // Half a second from the end at the last tick, and now in the next chapter: it ran out.
        let about = SleepTimer(choice: .endOfPart, remainingMs: 300, entry: 1)
        #expect(about.tick(elapsedMs: 500, entryLeftHeardMs: 1_450_000, entry: 2).runsOut)
        // The same move, a long way from the end: a jump to the next chapter, so it counts to that one's end.
        let far = SleepTimer(choice: .endOfPart, remainingMs: 600_000, entry: 1)
        let followed = far.tick(elapsedMs: 500, entryLeftHeardMs: 1_450_000, entry: 2)
        #expect(!followed.runsOut)
        #expect(followed.remainingMs == 1_450_000)
        #expect(followed.entry == 2)
        // Back to an earlier chapter, even from the last moment of this one.
        let back = about.tick(elapsedMs: 500, entryLeftHeardMs: 900_000, entry: 0)
        #expect(!back.runsOut)
        #expect(back.remainingMs == 900_000)
        // A timer that has not been told which chapter it is in learns it from the first tick.
        let unknown = SleepTimer(choice: .endOfPart, remainingMs: 300)
        #expect(unknown.entry == -1)
        let learnt = unknown.tick(elapsedMs: 500, entryLeftHeardMs: 1_450_000, entry: 3)
        #expect(!learnt.runsOut)
        #expect(learnt.entry == 3)
        // Minutes do not care which chapter it is.
        let minutes = SleepTimer(choice: .minutes(5), remainingMs: 100_000, entry: 1).tick(elapsedMs: 500, entryLeftHeardMs: 1_450_000,
                                                                                         entry: 2)
        #expect(minutes.remainingMs == 99_500)
    }

    @Test func aControlCarriesTheEndOfTheChapterOnToTheNextChaptersEnd() {
        var timer = SleepTimer.start(.endOfPart, entryLeftHeardMs: 20_000, entry: 1)
        #expect(timer.fading)
        timer = timer.extended(entryLeftHeardMs: 20_000, nextEntryHeardMs: 300_000)
        #expect(timer.remainingMs == 320_000)
        #expect(timer.skipParts == 1)
        // While carried it counts itself, whichever track the player is in.
        timer = timer.tick(elapsedMs: 10_000, entryLeftHeardMs: 10_000, entry: 1)
        #expect(timer.remainingMs == 310_000)
        #expect(!timer.endsWithPart)
        // The next chapter begins: it counts to that one's end.
        timer = timer.tick(elapsedMs: 500, entryLeftHeardMs: 295_000, entry: 2)
        #expect(timer.remainingMs == 295_000)
        #expect(timer.skipParts == 0)
        #expect(timer.entry == 2)
        #expect(timer.endsWithPart)
    }
}
