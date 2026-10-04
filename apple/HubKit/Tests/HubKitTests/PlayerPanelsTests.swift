import Foundation
import Testing
@testable import HubKit

/// Android's `PlaybackEnhancementsTest`, case for case.
struct PlaybackEnhancementsTests {
    @Test func subtitlesRiseAboveTheControlsOnlyWhileTheyShow() {
        let large = SubtitleSize.large.bottomFraction
        // Hidden controls: the look's own place.
        #expect(PlaybackEnhancements.subtitleLift(large, covered: 0, height: 1080) == large)
        // A 216 px panel on a 1080 px picture covers 20%; subtitles sit 2% above it.
        #expect(abs(PlaybackEnhancements.subtitleLift(large, covered: 216, height: 1080) - 0.22) < 1e-4)
        // Already clear of a short panel: unchanged.
        #expect(PlaybackEnhancements.subtitleLift(large, covered: 54, height: 1080) == large)
        // Before layout, or a panel taller than sense: no lift, or a capped one.
        #expect(PlaybackEnhancements.subtitleLift(large, covered: 216, height: 0) == large)
        #expect(PlaybackEnhancements.subtitleLift(large, covered: 1000, height: 1080) == 0.6)
    }

    @Test func subtitlesDefaultToAnOutlineThatStaysPutWhenTheControlsShow() {
        let look = SubtitleLook()
        #expect(look.style == .outline)
        #expect(look.size == .medium)
        #expect(PlaybackEnhancements.subtitlePlacement(look, covered: 216, height: 1080) == look.size.bottomFraction)
        let lifted = SubtitleLook(liftWithControls: true)
        #expect(abs(PlaybackEnhancements.subtitlePlacement(lifted, covered: 216, height: 1080) - 0.22) < 1e-4)
        #expect(PlayerLabels.subtitleLook(look) == "Outline · Medium")
    }

    @Test func chaptersAreOrderedClampedAndHaveStableNavigation() {
        let chapters = PlaybackEnhancements.chapters([
            PlaybackChapter(id: "late", name: "Credits", positionMillis: 800_000),
            PlaybackChapter(id: "bad", name: "Bad", positionMillis: -1),
            PlaybackChapter(id: "early", name: "Opening", positionMillis: 10_000),
            PlaybackChapter(id: "duplicate", name: "Duplicate", positionMillis: 10_000),
        ], durationMillis: 900_000)
        #expect(chapters.map(\.name) == ["Opening", "Credits"])
        #expect(PlaybackEnhancements.nextChapter(chapters, positionMillis: 10_001)?.positionMillis == 800_000)
        #expect(PlaybackEnhancements.previousChapter(chapters, positionMillis: 800_000)?.positionMillis == 10_000)
        #expect(PlaybackEnhancements.nextChapter(chapters, positionMillis: 800_000) == nil)
    }

    @Test func theSkipPromptIsThereOnlyWhileMeaningfullyBeforeTheEnd() {
        let segment = PlaybackSegment(id: "intro", type: "Intro", startMillis: 30_000, endMillis: 90_000)
        #expect(PlaybackEnhancements.skipPrompt([segment], positionMillis: 30_000) == segment)
        #expect(PlaybackEnhancements.skipPrompt([segment], positionMillis: 88_500) == segment)
        #expect(PlaybackEnhancements.skipPrompt([segment], positionMillis: 89_500) == nil)
        #expect(PlaybackEnhancements.skipPrompt([segment], positionMillis: 90_000) == nil)
    }

    @Test func playbackSpeedHasASafeDefault() {
        #expect(PlaybackEnhancements.defaultSpeed == 1)
        #expect(PlaybackEnhancements.speeds == [0.5, 0.75, 1, 1.25, 1.5, 2])
    }

    @Test func aChaptersPictureComesFromInsideItOnTheHubsFrameGrid() {
        // A quarter of the way into a 93-second opening, rounded down to five-second frames.
        #expect(PlaybackEnhancements.chapterFrameMillis(startMillis: 238_000, endMillis: 331_000) == 260_000)
        // A four-minute cold open: thirty seconds in, past the fade from black.
        #expect(PlaybackEnhancements.chapterFrameMillis(startMillis: 0, endMillis: 238_000) == 30_000)
        // A twelve-second chapter stays inside itself.
        #expect(PlaybackEnhancements.chapterFrameMillis(startMillis: 0, endMillis: 12_000) == 0)
        // The last chapter, with no end known.
        #expect(PlaybackEnhancements.chapterFrameMillis(startMillis: 1_616_000, endMillis: 0) == 1_630_000)
    }

    @Test func aSegmentBelongsToTheChapterItStartsWith() {
        let intro = PlaybackSegment(type: "Intro", startMillis: 238_500, endMillis: 331_000)
        #expect(PlaybackEnhancements.segmentAt([intro], startMillis: 238_000) == intro)
        #expect(PlaybackEnhancements.segmentAt([intro], startMillis: 0) == nil)
    }
}

/// Android's `SubtitleTimelineTest`, case for case.
struct SubtitleTimelineTests {
    let timeline = SubtitleTimeline([
        SubtitleWindow(10_000, 12_000, ["first"]),
        SubtitleWindow(11_000, 13_000, ["overlap"]),
        SubtitleWindow(20_000, 21_000, ["last"]),
    ])

    @Test func aPositiveDelayShowsLinesLaterWithoutChangingTheirTimes() {
        #expect(timeline.values(at: 11_999, offsetMillis: 2_000) == [])
        #expect(timeline.values(at: 12_000, offsetMillis: 2_000) == ["first"])
        #expect(timeline.values(at: 13_500, offsetMillis: 2_000) == ["first", "overlap"])
    }

    @Test func aNegativeDelayShowsLinesEarlier() {
        #expect(timeline.values(at: 9_500, offsetMillis: -500) == ["first"])
        #expect(timeline.values(at: 19_000, offsetMillis: -1_000) == ["last"])
    }

    @Test func aLinesEndIsExclusiveAndEmptySpansAreIgnored() {
        let value = SubtitleTimeline([SubtitleWindow(5, 5, ["invalid"]), SubtitleWindow(5, 10, ["valid"])])
        #expect(value.values(at: 9, offsetMillis: 0) == ["valid"])
        #expect(value.values(at: 10, offsetMillis: 0) == [])
    }
}

/// Reading the hub's subtitle files.
struct SubtitleParserTests {
    @Test func srtReadsItsBlocksAndDropsItsMarkup() {
        let srt = "\u{FEFF}1\r\n00:00:01,000 --> 00:00:03,500\r\n<i>Where are we?</i>\r\n{\\an8}Up here.\r\n\r\n"
            + "2\r\n00:01:02,250 --> 00:01:04,000\r\nKon!\r\n\r\n"
        let windows = SubtitleParser.parse(srt, codec: "subrip")
        #expect(windows == [SubtitleWindow(1_000, 3_500, ["Where are we?\nUp here."]),
                            SubtitleWindow(62_250, 64_000, ["Kon!"])])
    }

    @Test func aBrokenBlockIsSkippedNotTheFile() {
        let srt = "1\n00:00:01,000 --> nonsense\nLost\n\n2\n00:00:05,000 --> 00:00:06,000\nKept\n\n3\nno timing at all\n\n"
            + "4\n00:00:09,000 --> 00:00:08,000\nBackwards\n"
        #expect(SubtitleParser.parse(srt, codec: "srt") == [SubtitleWindow(5_000, 6_000, ["Kept"])])
    }

    @Test func webvttSkipsItsHeaderAndNotesAndReadsShortTimes() {
        let vtt = "WEBVTT\n\nNOTE written by hand\n\nintro\n00:01.000 --> 00:02.500 line:90% align:center\n"
            + "<v Ichigo>Tom &amp; Jerry</v>\n\n01:00:00.000 --> 01:00:01.000\n<c.yellow>Late</c>\n"
        #expect(SubtitleParser.parse(vtt, codec: "webvtt") == [SubtitleWindow(1_000, 2_500, ["Tom & Jerry"]),
                                                               SubtitleWindow(3_600_000, 3_601_000, ["Late"])])
    }

    @Test func assKeepsTheWordsInItsOwnFieldOrderAndDropsTheStyling() {
        let ass = """
        [Script Info]
        Title: Death Parade

        [V4+ Styles]
        Format: Name, Fontname
        Style: Default,Arial

        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        Comment: 0,0:00:00.00,0:00:05.00,Default,,0,0,0,,not shown
        Dialogue: 0,0:00:01.50,0:00:04.00,Default,,0,0,0,,{\\i1}Welcome, guests,{\\i0}\\Nto Quindecim.
        Dialogue: 0,0:00:05.00,0:00:06.00,Default,,0,0,0,,{\\p1}m 0 0 l 100 0 100 100{\\p0}
        Dialogue: 0,0:01:02.25,0:01:03.00,Default,,0,0,0,,Bartender\\hDecim
        """
        #expect(SubtitleParser.parse(ass, codec: "ass") == [SubtitleWindow(1_500, 4_000, ["Welcome, guests,\nto Quindecim."]),
                                                           SubtitleWindow(62_250, 63_000, ["Bartender Decim"])])
    }

    @Test func anUnnamedCodecIsReadByItsContents() {
        #expect(SubtitleParser.format(codec: "", text: "WEBVTT\n\n00:01.000 --> 00:02.000\nHi") == .webvtt)
        #expect(SubtitleParser.format(codec: "text", text: "[Script Info]\nTitle: x") == .ass)
        #expect(SubtitleParser.format(codec: "", text: "1\n00:00:01,000 --> 00:00:02,000\nHi") == .srt)
        #expect(SubtitleParser.format(codec: "pgssub", text: "") == nil)
        #expect(SubtitleParser.parse("garbage", codec: "pgssub").isEmpty)
    }

    @Test func clockReadsEveryFormsTimes() {
        #expect(SubtitleParser.clock("01:02:03,456") == 3_723_456)
        #expect(SubtitleParser.clock("02:03.4") == 123_400)
        #expect(SubtitleParser.clock("1:02:03.45") == 3_723_450)
        #expect(SubtitleParser.clock("1:62:03.45") == nil)
        #expect(SubtitleParser.clock("soon") == nil)
    }
}

/// Android's `PlayerMenuPolicyTest`, case for case.
struct PlayerMenuPolicyTests {
    @Test func tracksStartOnSubtitlesAndRememberTheLastKind() {
        var state = PlayerMenuState()
        #expect(state.trackTab == "subtitles")
        state.selectTrackTab("audio")
        #expect(state.trackTab == "audio")
        state.selectTrackTab("unknown")
        #expect(state.trackTab == "audio")
    }

    @Test func timingLabelsExplainTheDirectionAndTheRange() {
        #expect(SubtitleTimingPolicy.label(-800) == "\u{2212}0.8 s · Earlier")
        #expect(SubtitleTimingPolicy.label(0) == "0.0 s · In sync")
        #expect(SubtitleTimingPolicy.label(800) == "+0.8 s · Later")
        #expect(SubtitleTimingPolicy.clamp(-8_000, wide: false) == -5_000)
        #expect(SubtitleTimingPolicy.clamp(80_000, wide: true) == 60_000)
        #expect(SubtitleTimingPolicy.progressToOffset(51, wide: false) == 100)
        #expect(SubtitleTimingPolicy.offsetToProgress(-60_000, wide: true) == 0)
        #expect(SubtitleTimingPolicy.short(1_500) == "+1.5 s")
        #expect(SubtitleTimingPolicy.short(0) == "0.0 s")
    }
}

/// Android's track naming cases (`VisualPolishPolicyTest`).
struct TrackPresentationTests {
    @Test func generatedLabelsDoNotRepeatLanguageCodecOrLayout() {
        let track = PlaybackTrack(label: "Surround - English - AAC - 5.1 - Default", language: "eng", codec: "aac",
                                  channels: 6, isDefault: true)
        #expect(TrackPresentation.of(track).detail == "5.1 surround · AAC · Default")
    }

    @Test func anAlternateAudioDescriptionStaysDistinguishable() {
        let track = PlaybackTrack(label: "English - Director commentary - AAC", language: "eng", codec: "aac", channels: 2)
        #expect(TrackPresentation.of(track).detail == "Stereo · AAC · Director commentary")
    }

    @Test func languageAndAccessibilityComeBeforeCodecDetails() {
        let track = PlaybackTrack(label: "English - ASS - Full", language: "eng", codec: "ass", forced: true,
                                  hearingImpaired: true)
        let value = TrackPresentation.of(track)
        #expect(value.title == "English")
        #expect(value.detail.contains("Forced") && value.detail.contains("SDH") && value.detail.contains("ASS"))
    }

    @Test func aTrackWithoutALanguageKeepsItsUsefulLabel() {
        #expect(TrackPresentation.of(PlaybackTrack(label: "Director commentary")).title == "Director commentary")
        #expect(TrackPresentation.of(PlaybackTrack(language: "heb", channels: 2)).detail.contains("Stereo"))
        #expect(TrackPresentation.of(PlaybackTrack(language: "jpn")).title == "Japanese")
    }
}

/// What a person chose last, meeting a new session.
struct PlaybackChoicesTests {
    let plan = PlaybackPrepareResponse(
        item: PlaybackItem(id: "e1", seriesId: "s1"),
        audioTracks: [PlaybackTrack(index: 1, language: "eng", isDefault: true), PlaybackTrack(index: 2, language: "jpn")],
        subtitleTracks: [PlaybackTrack(index: 3, language: "eng", external: true, externalUrl: "/v1/playback/sessions/x/subtitles/3"),
                         PlaybackTrack(index: 4, language: "heb", isDefault: true),
                         PlaybackTrack(index: 5, language: "spa", codec: "PGSSUB")],
        selectedAudioIndex: 1, selectedSubtitleIndex: nil)

    @Test func episodesShareTheSeriesChoiceAndAFilmKeepsItsOwn() {
        #expect(PlaybackChoices.scope(PlaybackItem(id: "e1", seriesId: "s1")) == "s1")
        #expect(PlaybackChoices.scope(PlaybackItem(id: "m1")) == "m1")
    }

    @Test func nothingChosenAsksForNothing() {
        #expect(PlaybackChoices.wanted(plan, PlaybackSelection()) == nil)
    }

    @Test func theChosenLanguagesAreAskedForByTheirStreams() throws {
        let chosen = PlaybackSelection(audioLanguage: "JPN", subtitlesEnabled: true, subtitleLanguage: "heb")
        let wanted = try #require(PlaybackChoices.wanted(plan, chosen))
        #expect(wanted.audio == 2 && wanted.subtitle == 4)
        // Subtitles on without a match fall to the default track.
        let fallback = try #require(PlaybackChoices.wanted(plan, PlaybackSelection(subtitlesEnabled: true, subtitleLanguage: "fra")))
        #expect(fallback.subtitle == 4)
        // Off is -1.
        var on = plan
        on.selectedSubtitleIndex = 3
        #expect(PlaybackChoices.wanted(on, PlaybackSelection(subtitlesEnabled: false))?.subtitle == -1)
    }

    @Test func aSessionsTracksBecomeTheChoiceAndTheDelayStays() {
        var playing = plan
        playing.selectedAudioIndex = 2
        playing.selectedSubtitleIndex = 3
        let next = PlaybackChoices.remembering(playing, in: PlaybackSelection(subtitleOffsetMillis: 1_500))
        #expect(next == PlaybackSelection(audioLanguage: "jpn", subtitlesEnabled: true, subtitleLanguage: "eng",
                                          subtitleOffsetMillis: 1_500))
        #expect(PlaybackChoices.remembering(plan, in: PlaybackSelection()).subtitlesEnabled == false)
    }

    @Test func theSameAddressIsTheSameStreamOnlyForTheSameVersionAndMethod() {
        let direct = PlaybackPrepareResponse(mediaUrl: "/v1/playback/sessions/x/stream", playMethod: "DirectPlay",
                                             selectedMediaSourceId: "a", selectedSubtitleIndex: 3)
        var subtitles = direct
        subtitles.selectedSubtitleIndex = 4
        #expect(PlaybackChoices.sameStream(direct, subtitles))
        // Another version of a file played as it is keeps the session's address.
        var version = direct
        version.selectedMediaSourceId = "b"
        #expect(!PlaybackChoices.sameStream(direct, version))
        var converted = direct
        converted.playMethod = "Transcode"
        converted.mediaUrl = "/v1/playback/sessions/x/hls/abc"
        #expect(!PlaybackChoices.sameStream(direct, converted))
        var lower = converted
        lower.mediaUrl = "/v1/playback/sessions/x/hls/def"
        #expect(!PlaybackChoices.sameStream(converted, lower))
        #expect(!PlaybackChoices.sameStream(PlaybackPrepareResponse(), PlaybackPrepareResponse()))
    }

    @Test func onlyATextTrackServedAsAFileIsDrawnByTheApp() {
        var playing = plan
        playing.selectedSubtitleIndex = 3
        #expect(PlaybackChoices.drawnSubtitle(playing)?.index == 3)
        playing.selectedSubtitleIndex = 5
        #expect(PlaybackChoices.drawnSubtitle(playing) == nil)
        playing.selectedSubtitleIndex = nil
        #expect(PlaybackChoices.drawnSubtitle(playing) == nil)
    }

    @Test func aPreviewFrameAndASubtitleFileAreTheSessionsOwn() async throws {
        #expect(HubEndpoints.playbackPreview("/v1/playback/sessions/s1/preview", positionMillis: 260_000)
                == "/v1/playback/sessions/s1/preview?positionMillis=260000")
        let transport = ScriptedTransport([.init(status: 200, body: "1\n00:00:01,000 --> 00:00:02,000\nHi\n")])
        let hub = HubClient(credentials: HubCredentials(baseURL: "https://hub.example", token: String(repeating: "t", count: 43),
                                                        userId: "44444444444444444444444444444444"),
                            screens: transport, now: { 0 }, sleep: { _ in })
        let bytes = try await hub.data(HubEndpoints.playbackFile("/v1/playback/sessions/s1/subtitles/3", user: "55555555555555555555555555555555"))
        #expect(String(decoding: bytes, as: UTF8.self).contains("-->"))
        let sent = try #require(await transport.sent.first)
        #expect(sent.url?.path == "/v1/playback/sessions/s1/subtitles/3")
        #expect(sent.value(forHTTPHeaderField: "X-Jellyfin-User") == "55555555555555555555555555555555")
    }
}
