import Foundation
import Testing
@testable import HubKit

/// Android's `PlayerLabelsTest`, case for case, then the chapter the position
/// line names.
struct PlayerLabelsTests {
    @Test func decimalsDoNotFollowTheDeviceLocale() {
        #expect(PlayerLabels.subtitleOffset(-1_500) == "1.5 seconds earlier")
        #expect(PlayerLabels.subtitleOffset(300) == "0.3 seconds later")
        #expect(PlayerLabels.subtitleOffset(0) == "No offset")
        let plan = PlaybackPrepareResponse(playMethod: "DirectPlay", width: 1920, height: 1080, frameRate: 23.976,
                                           videoCodec: "hevc")
        #expect(PlayerLabels.diagnostic(plan) == "Direct play · 1920×1080 · HEVC · 23.98 fps")
    }

    @Test func seekDeltasCarryTheirSign() {
        #expect(PlayerLabels.signedTime(10_000) == "+0:10")
        #expect(PlayerLabels.signedTime(-30_000) == "\u{2212}0:30")
    }

    @Test func normalSpeedReadsAsAWord() {
        #expect(PlayerLabels.speed(1) == "Normal")
        #expect(PlayerLabels.speed(1.5) == "1.5×")
    }

    @Test func theTitleBarNamesTheSeriesAndUnderItTheEpisode() {
        let episode = PlaybackItem(title: "Somewhere Not Here", seriesTitle: "Vinland Saga", seasonNumber: 1, episodeNumber: 1)
        #expect(PlayerLabels.title(episode) == "Vinland Saga")
        #expect(PlayerLabels.subtitle(episode, offline: false) == "S1E1 · Somewhere Not Here")
        #expect(PlayerLabels.subtitle(episode, offline: true) == "S1E1 · Somewhere Not Here · Offline")
        let film = PlaybackItem(title: "Gran Torino")
        #expect(PlayerLabels.title(film) == "Gran Torino")
        #expect(PlayerLabels.subtitle(film, offline: false) == "")
    }

    @Test func underTheTimelineThePositionWithItsChapterAndTheTimeLeft() {
        #expect(PlayerLabels.positionLine(positionMillis: 334_000, chapterName: "Part A") == "5:34 · Part A")
        // "Chapter 2" says nothing the marks on the timeline do not.
        #expect(PlayerLabels.positionLine(positionMillis: 334_000, chapterName: "Chapter 2") == "5:34")
        #expect(PlayerLabels.positionLine(positionMillis: 334_000, chapterName: nil) == "5:34")
        #expect(PlayerLabels.remainingLine(positionMillis: 334_000, durationMillis: 1_707_000) == "\u{2212}22:53")
        #expect(PlayerLabels.remainingLine(positionMillis: 0, durationMillis: 0) == "")
    }

    @Test func thisVideosQualityRowSaysTheChoiceAndTheHeightDelivered() {
        #expect(PlayerLabels.qualityValue("Original", height: 1080, offline: false) == "Original · 1080p")
        #expect(PlayerLabels.qualityValue("10 Mbps", height: 0, offline: false) == "10 Mbps")
        #expect(PlayerLabels.qualityValue("Original", height: 1080, offline: true) == "Original · downloaded")
    }

    @Test func aChaptersLineSaysWhereItStartsHowLongAndWhatItIs() {
        #expect(PlayerLabels.chapterDetail(startMillis: 238_000, endMillis: 331_000, kind: "Intro") == "3:58 · 2 min · Intro")
        #expect(PlayerLabels.chapterDetail(startMillis: 0, endMillis: 45_000, kind: nil) == "0:00 · 45 s")
        // The last chapter has no end to measure to.
        #expect(PlayerLabels.chapterDetail(startMillis: 1_616_000, endMillis: 0, kind: nil) == "26:56")
        #expect(PlayerLabels.segmentKind("Outro") == "Credits")
        #expect(PlayerLabels.segmentKind("Unknown") == nil)
    }

    @Test func theStreamRowSaysHowItPlaysInWords() {
        #expect(PlayerLabels.playMethod("DirectPlay") == "Direct play")
        #expect(PlayerLabels.playMethod("DirectStream") == "Direct stream")
        #expect(PlayerLabels.playMethod("Transcode") == "Converting")
        #expect(PlayerLabels.playMethod("") == "Playback")
    }

    @Test func thePositionLineNamesTheChapterThatHasLastStarted() {
        let chapters = [PlaybackChapter(id: "0", name: "Opening", positionMillis: 0),
                        PlaybackChapter(id: "1", name: "Part A", positionMillis: 90_000),
                        PlaybackChapter(id: "2", name: "Part B", positionMillis: 655_000)]
        #expect(PlayerLabels.chapterName(at: 334_000, in: chapters) == "Part A")
        #expect(PlayerLabels.chapterName(at: 655_000, in: chapters) == "Part B")
        #expect(PlayerLabels.chapterName(at: 0, in: chapters) == "Opening")
        #expect(PlayerLabels.chapterName(at: 334_000, in: []) == nil)
    }
}

/// Android's `UpNextTest`, case for case.
struct UpNextTests {
    let duration: Int64 = 1_360_000
    let segments = [PlaybackSegment(id: "op", type: "Intro", startMillis: 199_000, endMillis: 289_000),
                    PlaybackSegment(id: "ed", type: "Outro", startMillis: 1_207_000, endMillis: 1_297_000)]

    @Test func theCardWaitsForTheCreditsOrComesTwentySecondsBeforeTheEnd() {
        #expect(UpNext.cardAt(.credits, segments: segments, durationMillis: duration) == 1_207_000)
        #expect(UpNext.cardAt(.credits, segments: [], durationMillis: duration) == 1_340_000)
        #expect(UpNext.cardAt(.beforeEnd, segments: segments, durationMillis: duration) == 1_340_000)
        #expect(UpNext.cardAt(.never, segments: segments, durationMillis: duration) == nil)
    }

    @Test func anEndingThemeInTheFirstHalfIsNotTheCredits() {
        let early = [PlaybackSegment(id: "ed", type: "Outro", startMillis: 100_000, endMillis: 190_000)]
        #expect(UpNext.cardAt(.credits, segments: early, durationMillis: duration) == 1_340_000)
    }

    @Test func aVeryShortVideoGetsNoCard() {
        #expect(UpNext.cardAt(.beforeEnd, segments: [], durationMillis: 30_000) == nil)
    }

    @Test func theCardShowsFromItsStartUntilTheVideoEnds() {
        #expect(!UpNext.showsCard(positionMillis: 1_206_999, cardAt: 1_207_000, durationMillis: duration))
        #expect(UpNext.showsCard(positionMillis: 1_207_000, cardAt: 1_207_000, durationMillis: duration))
        #expect(!UpNext.showsCard(positionMillis: duration, cardAt: 1_207_000, durationMillis: duration))
        #expect(!UpNext.showsCard(positionMillis: 1_300_000, cardAt: nil, durationMillis: duration))
    }

    @Test func introsRecapsPreviewsAndAdsGetASkipButtonCreditsDoNot() {
        #expect(UpNext.skipLabel("Intro") == "Skip intro")
        #expect(UpNext.skipLabel("recap") == "Skip recap")
        #expect(UpNext.skipLabel("Commercial") == "Skip ad")
        #expect(UpNext.skipLabel("Outro") == nil)
        #expect(UpNext.skipsAutomatically("Intro"))
        #expect(!UpNext.skipsAutomatically("Preview"))
    }

    /// Skip intros automatically (#33): an intro or a recap once, only when asked.
    @Test func anIntroSkipsItselfOnceWhenSettingsSaySo() {
        let intro = segments[0]
        #expect(UpNext.autoSkip(intro, enabled: true, skipped: []) == intro)
        #expect(UpNext.autoSkip(intro, enabled: false, skipped: []) == nil)
        #expect(UpNext.autoSkip(intro, enabled: true, skipped: [UpNext.skipKey(intro)]) == nil)
        #expect(UpNext.autoSkip(nil, enabled: true, skipped: []) == nil)
        let preview = PlaybackSegment(id: "pv", type: "Preview", startMillis: 0, endMillis: 30_000)
        #expect(UpNext.autoSkip(preview, enabled: true, skipped: []) == nil)
        let recap = PlaybackSegment(type: "Recap", startMillis: 5_000, endMillis: 60_000)
        #expect(UpNext.skipKey(recap) == "recap@5000")
        #expect(UpNext.autoSkip(recap, enabled: true, skipped: ["recap@5000"]) == nil)
        #expect(UpNext.skippedNotice(intro) == "Skipped intro")
        #expect(NextEpisodeTiming.allCases.map { $0.label } == ["When credits start", "20 s before the end", "Never"])
    }
}

/// Android's `PlaybackRulesTest`, case for case.
struct PlaybackRulesTests {
    @Test func aHeldSeekAcceleratesInBoundedStages() {
        #expect(PlaybackRules.seekStep(repeatCount: 0) == 10_000)
        #expect(PlaybackRules.seekStep(repeatCount: 5) == 30_000)
        #expect(PlaybackRules.seekStep(repeatCount: 12) == 60_000)
    }

    @Test func aSeekNeverEscapesTheItem() {
        #expect(PlaybackRules.clampSeek(-1_000, durationMillis: 100_000) == 0)
        #expect(PlaybackRules.clampSeek(35_000, durationMillis: 100_000) == 35_000)
        #expect(PlaybackRules.clampSeek(101_000, durationMillis: 100_000) == 100_000)
    }

    @Test func onlyARealFinalPositionCountsAsNaturalCompletion() {
        #expect(!PlaybackRules.reachedNaturalEnd(positionMillis: 180_000, durationMillis: 3_188_000))
        #expect(!PlaybackRules.reachedNaturalEnd(positionMillis: 3_186_999, durationMillis: 3_188_000))
        #expect(PlaybackRules.reachedNaturalEnd(positionMillis: 3_187_000, durationMillis: 3_188_000))
    }

    @Test func aHorizontalScrubIsProportionalAndClamped() {
        #expect(PlaybackRules.scrubTarget(startMillis: 600_000, dragFraction: 0.5, durationMillis: 4_200_000) == 1_200_000)
        #expect(PlaybackRules.scrubTarget(startMillis: 10_000, dragFraction: -1, durationMillis: 4_200_000) == 0)
        #expect(PlaybackRules.scrubTarget(startMillis: 4_190_000, dragFraction: 1, durationMillis: 4_200_000) == 4_200_000)
    }

    @Test func aTrickplayPositionMapsIntoItsSpriteTile() {
        let info = PlaybackTrickplay(width: 320, height: 180, tileWidth: 4, tileHeight: 3, thumbnailCount: 30,
                                     intervalMillis: 10_000)
        #expect(PlaybackRules.trickplayFrame(positionMillis: 145_000, info: info)
                == PlaybackRules.TrickplayFrame(thumbnailIndex: 14, tileIndex: 1, column: 2, row: 0))
        #expect(PlaybackRules.trickplayFrame(positionMillis: .max, info: info)?.thumbnailIndex == 29)
    }

    @Test func theQualityCapsMatchThePlayerContract() {
        #expect(PlaybackRules.qualities.map(\.bitrate) == [0, 40_000_000, 20_000_000, 10_000_000, 5_000_000, 2_000_000])
    }
}

/// Android's `PlaybackRules.selection` cases (#24): every select names the
/// version playing, or the one asked for.
struct PlaybackSelectionTests {
    let plan = PlaybackPrepareResponse(positionMillis: 5_000, selectedMediaSourceId: "source-1")

    @Test func aTrackChangeNamesTheVersionPlaying() {
        let audio = PlaybackRules.selection(plan, positionMillis: 61_000, audioStreamIndex: 2)
        #expect(audio == PlaybackSelectBody(positionMillis: 61_000, mediaSourceId: "source-1", audioStreamIndex: 2))
        let subtitles = PlaybackRules.selection(plan, subtitleStreamIndex: -1)
        #expect(subtitles.mediaSourceId == "source-1" && subtitles.positionMillis == 5_000)
        #expect(PlaybackRules.selection(plan, maxBitrate: 5_000_000).mediaSourceId == "source-1")
        #expect(PlaybackRules.selection(plan, forceTranscode: true).mediaSourceId == "source-1")
    }

    @Test func anotherVersionIsNamedItselfAndAPlanWithoutOneNamesNone() {
        #expect(PlaybackRules.selection(plan, mediaSourceId: "source-2").mediaSourceId == "source-2")
        #expect(PlaybackRules.selection(PlaybackPrepareResponse(), audioStreamIndex: 1).mediaSourceId == nil)
    }
}

/// The player's gestures on the picture (#24).
struct PlayerGestureTests {
    @Test func aDoubleTapSeeksBackOnTheLeftHalfAndOnOnTheRight() {
        #expect(PlayerGestures.doubleTapSeek(x: 100, width: 1_000) == -10_000)
        #expect(PlayerGestures.doubleTapSeek(x: 499.9, width: 1_000) == -10_000)
        #expect(PlayerGestures.doubleTapSeek(x: 500, width: 1_000) == 10_000)
        #expect(PlayerGestures.doubleTapSeek(x: 900, width: 1_000, step: 30_000) == 30_000)
        #expect(PlayerGestures.seekFeedback(deltaMillis: -10_000, targetMillis: 754_000) == "\u{2212}0:10  ·  12:34")
    }

    @Test func theFirstMovementDecidesBetweenAScrubAndALevel() {
        #expect(PlayerGestures.drag(dx: 2, dy: -40) == .upDown)
        #expect(PlayerGestures.drag(dx: 30, dy: -40) == .upDown)
        #expect(PlayerGestures.drag(dx: -40, dy: 30) == .across)
        // Exactly diagonal reads across, as on the Pocket.
        #expect(PlayerGestures.drag(dx: 25, dy: 25) == .across)
        // Within a tap's wobble nothing is decided yet.
        #expect(PlayerGestures.drag(dx: 0, dy: 8) == nil)
        #expect(PlayerGestures.drag(dx: 10, dy: -10) == nil)
    }

    @Test func aDragAcrossTheWholePictureCoversAThirdOfTheVideo() {
        // Ten minutes: the whole width is 200 seconds, so 40% of it is 80.
        #expect(PlayerGestures.scrubTarget(startMillis: 60_000, dx: 400, width: 1_000, durationMillis: 600_000) == 140_000)
        #expect(PlayerGestures.scrubTarget(startMillis: 60_000, dx: -1_000, width: 1_000, durationMillis: 600_000) == 0)
        // Two hours: at most twenty minutes, and never past the end.
        #expect(PlayerGestures.scrubTarget(startMillis: 0, dx: 500, width: 1_000, durationMillis: 7_200_000) == 600_000)
        #expect(PlayerGestures.scrubTarget(startMillis: 7_100_000, dx: 900, width: 1_000, durationMillis: 7_200_000)
                == 7_200_000)
        // A short clip: at least two minutes, but no more than it lasts.
        #expect(PlayerGestures.scrubTarget(startMillis: 0, dx: 1_000, width: 1_000, durationMillis: 90_000) == 90_000)
        #expect(PlayerGestures.scrubTarget(startMillis: 0, dx: 300, width: 0, durationMillis: 600_000) == 0)
        #expect(PlayerGestures.scrubLabel(targetMillis: 140_000, deltaMillis: 80_000) == "2:20, +1:20")
        #expect(PlayerGestures.scrubLabel(targetMillis: 20_000, deltaMillis: -40_000) == "0:20, \u{2212}0:40")
        #expect(PlayerGestures.scrubLabel(targetMillis: 140_000, deltaMillis: nil) == "2:20")
    }

    @Test func theScrubPreviewSitsOverItsPointOfTheTimelineInsideTheScreen() {
        // A 600-point track from x 100 on an 800-point screen, a 200-point card.
        #expect(PlayerGestures.scrubCardCenter(share: 0.5, trackMinX: 100, trackWidth: 600, cardWidth: 200,
                                               screenWidth: 800) == 400)
        // At either end it stops 8 points inside the screen.
        #expect(PlayerGestures.scrubCardCenter(share: 0, trackMinX: 100, trackWidth: 600, cardWidth: 200,
                                               screenWidth: 800) == 108)
        #expect(PlayerGestures.scrubCardCenter(share: 1.2, trackMinX: 100, trackWidth: 600, cardWidth: 200,
                                               screenWidth: 800) == 692)
        // A screen narrower than the card: its middle.
        #expect(PlayerGestures.scrubCardCenter(share: 0.9, trackMinX: 0, trackWidth: 150, cardWidth: 200,
                                               screenWidth: 150) == 75)
    }

    @Test func upRaisesALevelAndSixtyPercentOfTheHeightIsAllOfIt() {
        #expect(PlayerGestures.level(start: 0.5, dy: -300, height: 1_000) == 1)
        #expect(abs(PlayerGestures.level(start: 0.5, dy: 60, height: 1_000) - 0.4) < 1e-9)
        #expect(PlayerGestures.level(start: 0.1, dy: 600, height: 1_000) == 0)
        #expect(PlayerGestures.level(start: 0.1, dy: 600, height: 1_000, floor: PlayerGestures.brightnessFloor) == 0.02)
        #expect(PlayerGestures.percent(0.456) == "46%")
        #expect(PlayerGestures.percent(1.2) == "100%")
    }
}

/// What a session tells the hub, and when (Android's `PlaybackService`).
struct PlaybackReporterTests {
    @Test func theFirstPlayIsStartedAndEveryLaterOneUnpaused() {
        var reporter = PlaybackReporter()
        // A pause before anything played says nothing.
        #expect(reporter.playingChanged(false, positionMillis: 0, nowMillis: 0) == nil)
        #expect(reporter.playingChanged(true, positionMillis: 0, nowMillis: 0)
                == PlaybackEventBody(type: "started", sequence: 1, positionMillis: 0))
        #expect(reporter.playingChanged(false, positionMillis: 4_000, nowMillis: 4_000)
                == PlaybackEventBody(type: "paused", sequence: 2, positionMillis: 4_000, paused: true))
        #expect(reporter.playingChanged(true, positionMillis: 4_000, nowMillis: 9_000)?.type == "unpaused")
        #expect(reporter.sequence == 3)
    }

    @Test func progressComesEveryTenSecondsOfPlayCountedFromTheStart() {
        var reporter = PlaybackReporter()
        #expect(reporter.tick(playing: true, positionMillis: 0, nowMillis: 20_000) == nil)
        _ = reporter.playingChanged(true, positionMillis: 0, nowMillis: 1_000)
        #expect(reporter.tick(playing: true, positionMillis: 9_000, nowMillis: 10_000) == nil)
        #expect(reporter.tick(playing: true, positionMillis: 10_000, nowMillis: 11_000)
                == PlaybackEventBody(type: "progress", sequence: 2, positionMillis: 10_000))
        #expect(reporter.tick(playing: true, positionMillis: 15_000, nowMillis: 16_000) == nil)
        #expect(reporter.tick(playing: false, positionMillis: 20_000, nowMillis: 26_000) == nil)
        #expect(reporter.tick(playing: true, positionMillis: 20_000, nowMillis: 26_000)?.positionMillis == 20_000)
    }

    @Test func aSeekIsTheirsOnlyOnceStartedAndSaysWhetherItIsPaused() {
        var reporter = PlaybackReporter()
        // Where playback starts is in the first report, not a seek.
        #expect(reporter.seeked(positionMillis: 754_000, paused: true) == nil)
        _ = reporter.playingChanged(true, positionMillis: 754_000, nowMillis: 0)
        #expect(reporter.seeked(positionMillis: 764_000, paused: false)
                == PlaybackEventBody(type: "seek", sequence: 2, positionMillis: 764_000))
        #expect(reporter.seeked(positionMillis: 744_000, paused: true)?.paused == true)
    }

    @Test func theEndStopsAtTheDurationAndNothingFollowsIt() {
        var reporter = PlaybackReporter()
        _ = reporter.playingChanged(true, positionMillis: 0, nowMillis: 0)
        #expect(reporter.reachedEnd(durationMillis: 1_360_000)
                == PlaybackEventBody(type: "stopped", sequence: 2, positionMillis: 1_360_000))
        #expect(reporter.reachedEnd(durationMillis: 1_360_000) == nil)
        #expect(reporter.playingChanged(false, positionMillis: 1_360_000, nowMillis: 1) == nil)
        #expect(reporter.stop(positionMillis: 1_360_000) == nil)
        #expect(reporter.tick(playing: true, positionMillis: 1_360_000, nowMillis: 60_000) == nil)
    }

    @Test func leavingStopsWhereItWasOnceAndNotAtAllIfItNeverStarted() {
        var never = PlaybackReporter()
        #expect(never.stop(positionMillis: 0) == nil)
        var reporter = PlaybackReporter()
        _ = reporter.playingChanged(true, positionMillis: 0, nowMillis: 0)
        #expect(reporter.stop(positionMillis: 12_345)
                == PlaybackEventBody(type: "stopped", sequence: 2, positionMillis: 12_345, paused: true))
        #expect(reporter.stop(positionMillis: 12_345) == nil)
        #expect(reporter.seeked(positionMillis: 0, paused: true) == nil)
    }

    @Test func playingAgainAfterTheEndStartsAgain() {
        var reporter = PlaybackReporter()
        _ = reporter.playingChanged(true, positionMillis: 0, nowMillis: 0)
        _ = reporter.reachedEnd(durationMillis: 600_000)
        #expect(reporter.playingChanged(true, positionMillis: 0, nowMillis: 5)?.type == "started")
    }

    @Test func aPositionBeforeTheStartIsSentAsZero() {
        var reporter = PlaybackReporter()
        #expect(reporter.playingChanged(true, positionMillis: -40, nowMillis: 0)?.positionMillis == 0)
    }
}

/// The playback bodies and answers, as the hub writes and reads them.
struct PlaybackModelTests {
    @Test func aPreparedSessionReadsEveryFieldThePlayerUses() throws {
        let json = #"""
        {"sessionId":"52e44ba5bbdc8de6f22763317a78608a",
         "item":{"id":"e5","type":"episode","title":"Beat the Invisible Enemy!","seriesTitle":"Bleach",
                 "seriesId":"s1","seasonId":"se1","seasonNumber":1,"episodeNumber":5},
         "positionMillis":0,"durationMillis":1440000,
         "mediaUrl":"/v1/playback/sessions/52e44ba5bbdc8de6f22763317a78608a/hls/abc","mimeType":"application/x-mpegURL",
         "playMethod":"Transcode","transcodeReason":"ContainerNotSupported","bitrate":4200000,"width":1920,"height":1080,
         "frameRate":23.976,"videoCodec":"h264","audioCodec":"aac",
         "sources":[{"id":"src","name":"Bleach S01E05","container":"mkv","sizeBytes":734003200,"bitrate":4200000}],
         "audioTracks":[{"index":1,"type":"audio","label":"Japanese · AAC · stereo","language":"jpn","codec":"aac",
                         "channels":2,"default":true}],
         "subtitleTracks":[{"index":3,"type":"subtitle","label":"Hebrew","language":"heb","codec":"subrip",
                            "external":true,"externalUrl":"/v1/playback/sessions/52e/subtitles/3"}],
         "selectedMediaSourceId":"src","selectedAudioIndex":1,"selectedSubtitleIndex":3,
         "previousItem":{"id":"e4","title":"Kon the Stuffed Lion","seriesTitle":"Bleach","seasonNumber":1,"episodeNumber":4},
         "nextItem":{"id":"e6","title":"Fury of the Shinigami","seriesTitle":"Bleach","seasonNumber":1,"episodeNumber":6},
         "previewUrl":"/v1/playback/sessions/52e/preview",
         "chapters":[{"id":"0","name":"Opening","positionMillis":0},{"id":"1","name":"Part A","positionMillis":90000}],
         "segments":[{"id":"ed","type":"Outro","startMillis":1300000,"endMillis":1390000}],
         "somethingNew":true}
        """#
        let plan = try JSONDecoder().decode(PlaybackPrepareResponse.self, from: Data(json.utf8))
        #expect(plan.sessionId == "52e44ba5bbdc8de6f22763317a78608a")
        #expect(plan.item.seriesTitle == "Bleach" && plan.item.episodeNumber == 5)
        #expect(plan.durationMillis == 1_440_000)
        #expect(plan.playMethod == "Transcode" && plan.frameRate == 23.976)
        #expect(plan.audioTracks.first?.isDefault == true)
        #expect(plan.subtitleTracks.first?.externalUrl == "/v1/playback/sessions/52e/subtitles/3")
        #expect(plan.selectedSubtitleIndex == 3)
        #expect(plan.previousItem?.episodeNumber == 4)
        #expect(plan.nextItem?.title == "Fury of the Shinigami")
        #expect(plan.chapters.map(\.name) == ["Opening", "Part A"])
        #expect(plan.segments.first?.type == "Outro")
        #expect(plan.trickplay == nil)
        // Nothing at all is still a plan, with every field at its default.
        #expect(try JSONDecoder().decode(PlaybackPrepareResponse.self, from: Data("{}".utf8)) == PlaybackPrepareResponse())
    }

    @Test func theAVPlayerProfileIsTheOneIssueTwoDescribes() throws {
        let device = PlaybackDevice(id: "apple-1", name: PlaybackProfile.deviceName("iPad"), version: "0.1.0")
        let body = PlaybackPrepareBody(startMode: .resume, device: device,
                                       capabilities: PlaybackProfile.capabilities(width: 2_752, height: 2_064, hevc: true))
        let sent = try #require(try JSONSerialization.jsonObject(with: HubEndpoints.json(body)) as? [String: Any])
        #expect(sent["startMode"] as? String == "resume")
        // The hub's own defaults stay out of the body.
        #expect(sent["positionMillis"] == nil && sent["maxBitrate"] == nil && sent["forceTranscode"] == nil)
        #expect((sent["device"] as? [String: Any])?["name"] as? String == "JellyHub for iPad")
        let capabilities = try #require(sent["capabilities"] as? [String: Any])
        #expect(capabilities["containers"] as? [String] == ["mp4", "m4v", "mov"])
        #expect(capabilities["hlsSegments"] as? String == "fmp4")
        #expect(capabilities["videoCodecs"] as? [String] == ["h264", "hevc"])
        #expect(capabilities["audioCodecs"] as? [String] == ["aac", "ac3", "eac3", "alac", "flac", "mp3"])
        #expect(capabilities["maxAudioChannels"] as? Int == 6)
        #expect(capabilities["width"] as? Int == 2_752)
        // Without a hardware HEVC decoder Jellyfin converts HEVC to H.264.
        #expect(PlaybackProfile.capabilities(width: 0, height: 0, hevc: false).videoCodecs == ["h264"])
        // The hub refuses a screen larger than 8K.
        #expect(PlaybackProfile.capabilities(width: 12_288, height: 6_912, hevc: true).width == 7_680)
    }

    @Test func aStartOverAndAForcedConversionSayWhatTheyAre() throws {
        let body = PlaybackPrepareBody(startMode: .restart, positionMillis: 5_000, forceTranscode: true,
                                       device: PlaybackDevice(id: "d", name: "n", version: "v"),
                                       capabilities: PlaybackProfile.capabilities(width: 1, height: 1, hevc: false))
        let sent = try #require(try JSONSerialization.jsonObject(with: HubEndpoints.json(body)) as? [String: Any])
        #expect(sent["startMode"] as? String == "restart")
        #expect(sent["positionMillis"] as? Int == 5_000)
        #expect(sent["forceTranscode"] as? Bool == true)
    }

    @Test func anEventIsTheHubsShape() {
        let body = PlaybackEventBody(type: "stopped", sequence: 3, positionMillis: 12_000, paused: true)
        #expect(String(decoding: HubEndpoints.json(body), as: UTF8.self)
                == #"{"muted":false,"paused":true,"positionMillis":12000,"sequence":3,"type":"stopped","volume":100}"#)
    }

    @Test func aGrantsAddressJoinsTheHubUnlessItIsWhole() throws {
        let grant = try JSONDecoder().decode(PlaybackGrant.self, from: Data(#"""
        {"mediaUrl":"/v1/cast/Zm9v/hls/YmFy","mimeType":"application/x-mpegURL","subtitleUrls":{"3":"/v1/cast/Zm9v/subtitles/3"},
         "expiresAt":"2026-10-04T20:00:00Z"}
        """#.utf8))
        #expect(grant.address(base: "https://hub.example/") == "https://hub.example/v1/cast/Zm9v/hls/YmFy")
        #expect(grant.subtitleUrls["3"] == "/v1/cast/Zm9v/subtitles/3")
        #expect(PlaybackGrant(mediaUrl: "https://cdn.example/a.m3u8").address(base: "https://hub.example")
                == "https://cdn.example/a.m3u8")
    }

    @Test func anEpisodeReadsSeriesCodeAndTitle() {
        #expect(PlaybackItem(title: "Fury", seriesTitle: "Bleach", seasonNumber: 1, episodeNumber: 6).displayTitle
                == "Bleach · S1E6 · Fury")
        #expect(PlaybackItem(title: "Special", seriesTitle: "Bleach").displayTitle == "Bleach · Special")
        #expect(PlaybackItem(title: "Gran Torino").displayTitle == "Gran Torino")
    }
}

/// The session's paths, and the profile each call is for.
struct PlaybackEndpointTests {
    let body = PlaybackPrepareBody(startMode: .resume, device: PlaybackDevice(id: "d", name: "n", version: "v"),
                                   capabilities: PlaybackProfile.capabilities(width: 1, height: 1, hevc: false))

    @Test func everySessionCallNamesItsSessionAndTheProfileThatOpenedIt() {
        let prepare = HubEndpoints.preparePlayback(itemId: "2d47480c", body: body, user: "u1")
        #expect(prepare.path == "/v1/playback/items/2d47480c/prepare")
        #expect(prepare.method == .post && prepare.user == "u1" && !prepare.idempotent)
        #expect(HubEndpoints.playbackGrant(sessionId: "s1", user: "u1").path == "/v1/playback/sessions/s1/cast-grant")
        let event = HubEndpoints.playbackEvent(sessionId: "s1", body: PlaybackEventBody(type: "started", sequence: 1,
                                                                                         positionMillis: 0), user: "")
        #expect(event.path == "/v1/playback/sessions/s1/events" && event.method == .post && event.user == "")
        #expect(HubEndpoints.selectPlayback(sessionId: "s1", body: PlaybackSelectBody(positionMillis: 0), user: "u1").path
                == "/v1/playback/sessions/s1/select")
        let close = HubEndpoints.closePlayback(sessionId: "s1", user: "u1")
        #expect(close.path == "/v1/playback/sessions/s1" && close.method == .delete && close.body == nil)
    }

    @Test func aFallbackToConversionSendsOnlyWhatChanges() {
        let select = PlaybackSelectBody(positionMillis: 61_000, forceTranscode: true)
        #expect(String(decoding: HubEndpoints.json(select), as: UTF8.self) == #"{"forceTranscode":true,"positionMillis":61000}"#)
    }
}

/// How Play starts from a title's page (Android's `canResume`, `restart`).
struct PlayStartTests {
    @Test func aSavedPositionResumesAndStartOverSitsBesideIt() {
        var film = LibraryItem(id: "m1", type: "movie", title: "Gran Torino")
        #expect(DetailLines.startMode(film) == .restart)
        #expect(!DetailLines.offersStartOver(film))
        film.positionSeconds = 754
        #expect(DetailLines.startMode(film) == .resume)
        #expect(DetailLines.offersStartOver(film))
        // A series' button is its next episode's; it has no position of its own.
        #expect(!DetailLines.offersStartOver(LibraryItem(id: "s1", type: "series", title: "Bleach", positionSeconds: 30)))
    }

    @Test func aSeriesResumesItsPartWatchedEpisodeAndStartsAnyOtherFromTheBeginning() {
        let episode = LibraryItem(id: "e5", type: "episode", title: "Beat the Invisible Enemy!")
        #expect(DetailLines.startMode(SeriesPlayTarget(kind: "resume", item: episode)) == .resume)
        #expect(DetailLines.startMode(SeriesPlayTarget(kind: "next", item: episode)) == .restart)
        #expect(DetailLines.startMode(SeriesPlayTarget(kind: "start", item: episode)) == .restart)
    }
}

/// The demo hub's player, which the layout work and the UI tests run against.
struct DemoPlaybackTests {
    @Test func theDemoPlaysItsTestStreamWithEpisodesEitherSideAndTakesEveryCall() async throws {
        let hub = HubClient(credentials: HubCredentials(baseURL: DemoTransport.address, token: DemoTransport.token),
                            screens: DemoTransport(), sleep: { _ in })
        let body = PlaybackPrepareBody(startMode: .resume, device: PlaybackDevice(id: "d", name: "n", version: "v"),
                                       capabilities: PlaybackProfile.capabilities(width: 1, height: 1, hevc: false))
        let plan = try await hub.fetch(HubEndpoints.preparePlayback(itemId: "demo-e5", body: body, user: ""),
                                       as: PlaybackPrepareResponse.self)
        #expect(plan.item.title == "Beat the Invisible Enemy!")
        #expect(plan.previousItem?.episodeNumber == 4 && plan.nextItem?.episodeNumber == 6)
        #expect(plan.sessionId.count == 32)
        let grant = try await hub.fetch(HubEndpoints.playbackGrant(sessionId: plan.sessionId, user: ""), as: PlaybackGrant.self)
        #expect(grant.address(base: DemoTransport.address) == DemoPlayback.stream)
        try await hub.send(HubEndpoints.playbackEvent(sessionId: plan.sessionId,
                                                      body: PlaybackEventBody(type: "started", sequence: 1, positionMillis: 0),
                                                      user: ""))
        try await hub.send(HubEndpoints.closePlayback(sessionId: plan.sessionId, user: ""))
    }

    /// So the UI tests that change a track prove the app names its version (#24).
    @Test func theDemoRefusesATrackWithoutTheVersionItBelongsTo() async throws {
        #expect(!DemoPlayback.namesItsVersion(HubEndpoints.json(PlaybackSelectBody(positionMillis: 0, audioStreamIndex: 2))))
        #expect(DemoPlayback.namesItsVersion(HubEndpoints.json(
            PlaybackSelectBody(positionMillis: 0, mediaSourceId: "demo-1080", audioStreamIndex: 2))))
        #expect(DemoPlayback.namesItsVersion(HubEndpoints.json(PlaybackSelectBody(positionMillis: 0, maxBitrate: 2_000_000))))
    }

    /// So the UI tests that play prove the app sends #2's profile.
    @Test func theDemoRefusesAPrepareWithoutAVPlayersContainersAndFMP4() async throws {
        let hub = HubClient(credentials: HubCredentials(baseURL: DemoTransport.address, token: DemoTransport.token),
                            screens: DemoTransport(), sleep: { _ in })
        var media3 = PlaybackProfile.capabilities(width: 1, height: 1, hevc: false)
        media3.containers = []
        media3.hlsSegments = ""
        let body = PlaybackPrepareBody(startMode: .resume, device: PlaybackDevice(id: "d", name: "n", version: "v"),
                                       capabilities: media3)
        await #expect(throws: HubFailure.self) {
            _ = try await hub.fetch(HubEndpoints.preparePlayback(itemId: "demo-e5", body: body, user: ""),
                                    as: PlaybackPrepareResponse.self)
        }
        #expect(DemoPlayback.namesAVPlayer(HubEndpoints.json(PlaybackPrepareBody(
            startMode: .resume, device: PlaybackDevice(id: "d", name: "n", version: "v"),
            capabilities: PlaybackProfile.capabilities(width: 1, height: 1, hevc: false)))))
    }

    @Test func aDemoSessionKeepsEachChangeAsTheHubDoes() async throws {
        let hub = HubClient(credentials: HubCredentials(baseURL: DemoTransport.address, token: DemoTransport.token),
                            screens: DemoTransport(), sleep: { _ in })
        let body = PlaybackPrepareBody(startMode: .resume, device: PlaybackDevice(id: "d", name: "n", version: "v"),
                                       capabilities: PlaybackProfile.capabilities(width: 1, height: 1, hevc: false))
        let plan = try await hub.fetch(HubEndpoints.preparePlayback(itemId: "demo-e4", body: body, user: ""),
                                       as: PlaybackPrepareResponse.self)
        #expect(plan.selectedSubtitleIndex == nil && plan.selectedAudioIndex == 1)
        let subtitles = try await hub.fetch(HubEndpoints.selectPlayback(
            sessionId: plan.sessionId, body: PlaybackRules.selection(plan, positionMillis: 5_000, subtitleStreamIndex: 4), user: ""),
            as: PlaybackPrepareResponse.self)
        #expect(subtitles.item.episodeNumber == 4 && subtitles.selectedSubtitleIndex == 4)
        #expect(PlaybackChoices.sameStream(plan, subtitles))
        let audio = try await hub.fetch(HubEndpoints.selectPlayback(
            sessionId: plan.sessionId, body: PlaybackRules.selection(plan, positionMillis: 5_000, audioStreamIndex: 2), user: ""),
            as: PlaybackPrepareResponse.self)
        #expect(audio.selectedAudioIndex == 2 && audio.selectedSubtitleIndex == 4)
        // A lower quality is a conversion at an address of its own.
        let lower = try await hub.fetch(HubEndpoints.selectPlayback(
            sessionId: plan.sessionId, body: PlaybackRules.selection(plan, positionMillis: 5_000, maxBitrate: 5_000_000), user: ""),
            as: PlaybackPrepareResponse.self)
        #expect(lower.playMethod == "Transcode" && !PlaybackChoices.sameStream(audio, lower))
        // Its subtitle files read as subtitles, and a chapter's frame is a picture.
        let track = try #require(PlaybackChoices.drawnSubtitle(audio))
        let file = try await hub.data(HubEndpoints.playbackFile(track.externalUrl, user: ""))
        #expect(SubtitleParser.parse(String(decoding: file, as: UTF8.self), codec: track.codec).count == 150)
        let frame = try await hub.image(HubEndpoints.playbackPreview(plan.previewUrl, positionMillis: 90_000))
        #expect(frame.starts(with: [0xFF, 0xD8]))
        try await hub.send(HubEndpoints.closePlayback(sessionId: plan.sessionId, user: ""))
    }

    @Test func theDemoSubtitlesReadInEveryFormat() {
        #expect(SubtitleParser.parse(DemoPlayback.subtitles(track: 3), codec: "srt").first?.values == ["Demo subtitle 1"])
        #expect(SubtitleParser.parse(DemoPlayback.subtitles(track: 4), codec: "webvtt").count == 150)
        let signs = SubtitleParser.parse(DemoPlayback.subtitles(track: 5), codec: "ass")
        #expect(signs.count == 30)
        #expect(signs.first?.values == ["Sign 1, on the wall"])
    }
}
