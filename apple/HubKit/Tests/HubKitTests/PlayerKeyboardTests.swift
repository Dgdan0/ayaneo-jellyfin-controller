import Testing
@testable import HubKit

/// The video player's keys (#33).
struct PlayerKeyboardTests {
    @Test func lettersEitherCaseAndTheBracketsAreThePlayers() {
        #expect(PlayerKeyboard.command(" ") == .playPause)
        #expect(PlayerKeyboard.command("m") == .mute)
        #expect(PlayerKeyboard.command("M") == .mute)
        #expect(PlayerKeyboard.command("c") == .subtitles)
        #expect(PlayerKeyboard.command("[") == .slower)
        #expect(PlayerKeyboard.command("]") == .faster)
        #expect(PlayerKeyboard.command("S") == .skip)
        #expect(PlayerKeyboard.command("n") == .next)
        #expect(PlayerKeyboard.command("f") == .fullScreen)
        #expect(PlayerKeyboard.command("x") == nil)
        #expect(PlayerKeyboard.command("") == nil)
        for letter in PlayerKeyboard.letters {
            #expect(PlayerKeyboard.command(String(letter)) != nil)
        }
    }

    @Test func theVolumeMovesATenthAtATimeBetweenNothingAndAll() {
        #expect(PlayerKeyboard.volume(0.5, up: true) == 0.6)
        #expect(PlayerKeyboard.volume(0.6, up: false) == 0.5)
        #expect(PlayerKeyboard.volume(0.95, up: true) == 1)
        #expect(PlayerKeyboard.volume(1, up: true) == 1)
        #expect(PlayerKeyboard.volume(0.04, up: false) == 0)
        // A drag left it between tenths: the next tenth, not a tenth past it.
        #expect(PlayerKeyboard.volume(0.33, up: true) == 0.4)
    }

    @Test func theSpeedStepsThroughThePlayersAndStaysAtTheEnds() {
        #expect(PlayerKeyboard.speed(1, faster: true) == 1.25)
        #expect(PlayerKeyboard.speed(1, faster: false) == 0.75)
        #expect(PlayerKeyboard.speed(2, faster: true) == 2)
        #expect(PlayerKeyboard.speed(0.5, faster: false) == 0.5)
        #expect(PlayerLabels.speedNotice(1.25) == "Speed 1.25×")
        #expect(PlayerLabels.speedNotice(1) == "Speed Normal")
    }

    @Test func subtitlesTurnOffAndBackOnToTheTrackTurnedOffLast() {
        let english = PlaybackTrack(index: 3, type: "subtitle", language: "eng")
        let hebrew = PlaybackTrack(index: 4, type: "subtitle", language: "heb")
        let signs = PlaybackTrack(index: 5, type: "subtitle", label: "Signs", language: "eng", forced: true)
        var plan = PlaybackPrepareResponse()
        plan.subtitleTracks = [signs, english, hebrew]
        plan.selectedSubtitleIndex = 4
        #expect(PlaybackChoices.toggledSubtitle(plan, last: nil, language: "") == -1)
        plan.selectedSubtitleIndex = nil
        #expect(PlaybackChoices.toggledSubtitle(plan, last: 4, language: "eng") == 4)
        #expect(PlaybackChoices.toggledSubtitle(plan, last: nil, language: "heb") == 4)
        // More than the forced signs, when nothing says which.
        #expect(PlaybackChoices.toggledSubtitle(plan, last: nil, language: "") == 3)
        plan.selectedSubtitleIndex = -1
        #expect(PlaybackChoices.toggledSubtitle(plan, last: 99, language: "") == 3)
        plan.subtitleTracks = []
        #expect(PlaybackChoices.toggledSubtitle(plan, last: nil, language: "") == nil)
        #expect(PlayerLabels.subtitlesNotice(nil) == "Subtitles off")
        #expect(PlayerLabels.subtitlesNotice(english) == "Subtitles: English")
    }
}
