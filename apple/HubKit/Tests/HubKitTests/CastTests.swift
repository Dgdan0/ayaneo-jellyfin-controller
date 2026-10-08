import Foundation
import Testing
@testable import HubKit

/// Google Cast to the TV (#44), held to the Pocket's rules
/// (`CastTransferPolicy`, `CastPlaybackCoordinator`): the address the TV is
/// sent to, the TV's session, its subtitles, and the demo hub's TV session.
struct CastTests {
    // MARK: The address the TV fetches from

    @Test func theTVIsSentOnlyToAPublicHTTPSAddress() {
        let base = "https://myjellydan.duckdns.org:55886/"
        #expect(CastAddress.receiverURL(base: base, resource: "/v1/cast/abc/stream")?.absoluteString
                == "https://myjellydan.duckdns.org:55886/v1/cast/abc/stream")
        #expect(CastAddress.problem(base: base) == nil)
        // Only the hub's cast paths, never another of its routes or another host.
        #expect(CastAddress.receiverURL(base: base, resource: "/v1/playback/sessions/s/stream") == nil)
        #expect(CastAddress.receiverURL(base: base, resource: "//evil.example/v1/cast/x") == nil)
        // Places the TV cannot reach, or a token in the address.
        for refused in ["http://myjellydan.duckdns.org", "https://localhost:8791", "https://media.local", "https://127.0.0.1",
                        "https://10.100.102.8", "https://192.168.1.4", "https://172.16.0.2", "https://100.101.102.103",
                        "https://user:pass@hub.example", "https://hub.example/?token=1", "", "not an address"] {
            #expect(CastAddress.problem(base: refused) != nil, "\(refused) was taken")
            #expect(CastAddress.receiverURL(base: refused, resource: "/v1/cast/abc/stream") == nil)
        }
        // The tailnet's name too: the TV is not on the tailnet, and is told so.
        let tailnet = CastAddress.problem(base: "https://ayaneo-media-pc.tail737e96.ts.net")
        #expect(tailnet?.contains("tailnet") == true)
        // The address set for TV playback wins; without one, the hub's own.
        #expect(CastAddress.base(tvAddress: " https://tv.example/ ", hubAddress: "https://hub.example") == "https://tv.example")
        #expect(CastAddress.base(tvAddress: "", hubAddress: "https://hub.example/") == "https://hub.example")
        #expect(CastAddress.receiverAppId == "CC1AD845")
    }

    // MARK: The TV's session

    private static func plan(subtitle: Int? = 3, audio: Int? = 2, mediaUrl: String = "/v1/playback/sessions/s/hls/x/main.m3u8",
                             playMethod: String = "Transcode") -> PlaybackPrepareResponse {
        var plan = PlaybackPrepareResponse(sessionId: "s", item: PlaybackItem(id: "e5", title: "Beat the Invisible Enemy!",
                                                                           seriesTitle: "Bleach", seasonNumber: 1,
                                                                           episodeNumber: 5),
                                           positionMillis: 61_000, durationMillis: 1_440_000, mediaUrl: mediaUrl,
                                           mimeType: "video/x-matroska", playMethod: playMethod)
        plan.selectedMediaSourceId = "src"
        plan.selectedAudioIndex = audio
        plan.selectedSubtitleIndex = subtitle
        plan.subtitleTracks = [
            PlaybackTrack(index: 3, type: "subtitle", label: "English", language: "eng", codec: "srt", external: true),
            PlaybackTrack(index: 4, type: "subtitle", language: "heb", codec: "WebVTT", external: true),
            PlaybackTrack(index: 5, type: "subtitle", label: "Signs", language: "eng", codec: "ass", external: true),
            PlaybackTrack(index: 6, type: "subtitle", label: "PGS", language: "eng", codec: "pgssub"),
            PlaybackTrack(index: 7, type: "subtitle", label: "Embedded", language: "eng", codec: "subrip"),
        ]
        return plan
    }

    @Test func theTVsSessionIsAConversionForTheTVFromThisPlace() throws {
        let device = PlaybackDevice(id: "hub-apple-1", name: "iPhone", version: "0.2.0")
        let body = CastPlan.prepareBody(Self.plan(), positionMillis: 61_234, device: device)
        #expect(body.startMode == .resume && body.positionMillis == 61_234)
        #expect(body.mediaSourceId == "src" && body.audioStreamIndex == 2 && body.subtitleStreamIndex == 3)
        #expect(body.forceTranscode && body.maxBitrate == 20_000_000)
        #expect(body.device == PlaybackDevice(id: "hub-apple-1-cast", name: "iPhone to Google TV", version: "0.2.0"))
        #expect(body.capabilities == PlaybackCapabilities(width: 1_920, height: 1_080, maxAudioChannels: 2,
                                                          videoCodecs: ["h264"], audioCodecs: ["aac", "mp3"]))
        // The next episode on the TV starts at its beginning, with its own tracks.
        var next = Self.plan(subtitle: nil, audio: nil)
        next.selectedMediaSourceId = ""
        let restart = CastPlan.prepareBody(next, positionMillis: -5, startMode: .restart, device: device)
        #expect(restart.startMode == .restart && restart.positionMillis == 0 && restart.mediaSourceId == nil)
        #expect(restart.audioStreamIndex == nil && restart.subtitleStreamIndex == nil)
        // The TV starts where this device was, though the hub said 0:00 for a title's first half-minute.
        #expect(CastPlan.startMillis(planned: 0, asked: 12_400, mode: .resume) == 12_400)
        #expect(CastPlan.startMillis(planned: 61_000, asked: 60_900, mode: .resume) == 61_000)
        #expect(CastPlan.startMillis(planned: 61_000, asked: 5_000, mode: .restart) == 0)
        // As the hub reads it.
        let sent = try #require(try JSONSerialization.jsonObject(with: JSONEncoder().encode(body)) as? [String: Any])
        #expect(sent["forceTranscode"] as? Bool == true && sent["maxBitrate"] as? Int == 20_000_000)
    }

    @Test func theTVShowsTextSubtitlesAsSideTracksAtTheGrantsAddresses() throws {
        let plan = Self.plan()
        #expect(CastPlan.textSubtitles(plan).map(\.index) == [3, 4])
        let grant = PlaybackGrant(mediaUrl: "/v1/cast/g/hls/x/main.m3u8", mimeType: "application/x-mpegURL",
                                  subtitleUrls: ["3": "/v1/cast/g/subtitles/3", "4": "/v1/cast/g/subtitles/4",
                                                 "5": "/v1/cast/g/subtitles/5"])
        let base = "https://hub.example"
        let tracks = CastPlan.tracks(plan, grant: grant, base: base)
        #expect(tracks.map(\.id) == [1_003, 1_004])
        #expect(tracks.map(\.name) == ["English", "heb"])
        #expect(tracks.map(\.language) == ["eng", "heb"])
        #expect(tracks.first?.url.absoluteString == "https://hub.example/v1/cast/g/subtitles/3")
        #expect(CastPlan.activeTrackIds(tracks, subtitleIndex: 3) == [1_003])
        #expect(CastPlan.activeTrackIds(tracks, subtitleIndex: 5).isEmpty)
        #expect(CastPlan.activeTrackIds(tracks, subtitleIndex: nil).isEmpty)
        // A track without a grant address is not offered to the TV.
        #expect(CastPlan.tracks(plan, grant: PlaybackGrant(mediaUrl: "/v1/cast/g/stream"), base: base).isEmpty)

        let load = try CastPlan.load(plan, grant: grant, base: base).get()
        #expect(load.url.absoluteString == "https://hub.example/v1/cast/g/hls/x/main.m3u8")
        #expect(load.contentType == "application/vnd.apple.mpegurl")
        #expect(load.title == "Bleach" && load.subtitle == "S1E5 · Beat the Invisible Enemy!")
        #expect(load.startMillis == 61_000 && load.durationMillis == 1_440_000)
        #expect(load.activeTrackIds == [1_003] && load.artwork == nil)
        // A file played as it is keeps its type; an address the TV cannot reach says why.
        #expect(CastPlan.contentType(Self.plan(mediaUrl: "/v1/playback/sessions/s/stream", playMethod: "DirectPlay"))
                == "video/x-matroska")
        if case .failure(let failure) = CastPlan.load(plan, grant: grant, base: "https://pc.tail1.ts.net") {
            #expect(failure.message.contains("tailnet"))
        } else {
            Issue.record("A tailnet address was sent to the TV")
        }
    }

    // MARK: The button and the player

    @Test func theButtonAndThePlayerSayWhereThingsStand() {
        #expect(CastPresentation.buttonLabel(.unavailable) == "Cast, no TV found")
        #expect(CastPresentation.buttonLabel(.available) == "Cast to a TV")
        #expect(CastPresentation.buttonLabel(.connecting("Living room TV")) == "Connecting to Living room TV")
        #expect(CastPresentation.buttonLabel(.connected("Living room TV")) == "Casting to Living room TV")
        #expect(CastPresentation.lit(.connected("TV")) && !CastPresentation.lit(.connecting("TV")) && !CastPresentation.lit(.available))
        #expect(CastConnection.connected("Den").deviceName == "Den" && CastConnection.available.deviceName == nil)
        #expect(CastPresentation.playingOn("Living room TV") == "Playing on Living room TV")
        #expect(CastPresentation.playingOn("") == "Playing on TV")
        #expect(CastPresentation.moveHere(pad: false) == "Move to this iPhone")
        #expect(CastPresentation.moveHere(pad: true) == "Move to this iPad")
        #expect(CastPresentation.playing(.playing) && !CastPresentation.playing(.paused))
        #expect(CastPresentation.waiting(.buffering) && CastPresentation.waiting(.loading) && !CastPresentation.waiting(.paused))
    }

    /// The TV reports through the player's own rule: started where it began,
    /// progress every ten seconds of play, paused and unpaused, a seek, and
    /// stopped once, where it was or at the end.
    @Test func theTVsSessionReportsAsAnyPlaybackDoes() {
        var reporter = PlaybackReporter()
        #expect(reporter.playingChanged(true, positionMillis: 61_000, nowMillis: 0)?.type == "started")
        #expect(reporter.tick(playing: true, positionMillis: 66_000, nowMillis: 5_000) == nil)
        #expect(reporter.tick(playing: true, positionMillis: 71_000, nowMillis: 10_000)?.type == "progress")
        #expect(reporter.playingChanged(false, positionMillis: 72_000, nowMillis: 11_000)?.type == "paused")
        #expect(reporter.seeked(positionMillis: 120_000, paused: true)?.type == "seek")
        #expect(reporter.playingChanged(true, positionMillis: 120_000, nowMillis: 12_000)?.type == "unpaused")
        let stop = reporter.stop(positionMillis: 130_000)
        #expect(stop?.type == "stopped" && stop?.positionMillis == 130_000)
        #expect(reporter.stop(positionMillis: 131_000) == nil)
    }

    // MARK: The demo hub's TV session

    @Test func theDemoHubPreparesAndGrantsATVSessionApartFromThisDevicesOwn() async throws {
        let hub = HubClient(credentials: HubCredentials(baseURL: DemoTransport.address, token: DemoTransport.token),
                            screens: DemoTransport(), sleep: { _ in })
        let device = PlaybackDevice(id: "hub-apple-test", name: "iPhone", version: "0")
        let here = try await hub.fetch(HubEndpoints.preparePlayback(
            itemId: "demo-e5", body: PlaybackPrepareBody(startMode: .resume, device: device,
                                                          capabilities: PlaybackCapabilities(width: 1_920, height: 1_080, maxAudioChannels: 2,
                                                                                             videoCodecs: ["h264"], audioCodecs: ["aac"],
                                                                                             containers: ["mp4"], hlsSegments: "fmp4")),
            user: ""), as: PlaybackPrepareResponse.self)
        var playing = here
        playing.selectedSubtitleIndex = 4
        let tv = try await hub.fetch(HubEndpoints.preparePlayback(
            itemId: "demo-e5", body: CastPlan.prepareBody(playing, positionMillis: 90_000, device: device), user: ""),
                                     as: PlaybackPrepareResponse.self)
        #expect(tv.sessionId != here.sessionId)
        #expect(tv.selectedSubtitleIndex == 4 && tv.playMethod == "Transcode")
        let grant = try await hub.fetch(HubEndpoints.playbackGrant(sessionId: tv.sessionId, user: ""), as: PlaybackGrant.self)
        let load = try CastPlan.load(tv, grant: grant, base: DemoTransport.address).get()
        #expect(load.url.absoluteString.hasPrefix(DemoTransport.address + "/v1/cast/"))
        #expect(load.tracks.map(\.id) == [1_003, 1_004] && load.activeTrackIds == [1_004])
        // This device's own grant is still the stream it plays.
        let own = try await hub.fetch(HubEndpoints.playbackGrant(sessionId: here.sessionId, user: ""), as: PlaybackGrant.self)
        #expect(!own.mediaUrl.hasPrefix("/v1/cast/"))
    }
}
