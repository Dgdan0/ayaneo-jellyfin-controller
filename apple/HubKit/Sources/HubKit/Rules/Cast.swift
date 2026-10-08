import Foundation

// Google Cast to the TV (#44), as the Pocket casts (`CastPlaybackCoordinator`,
// `CastTransferPolicy`): the video moves to a hub session of its own for the
// TV, prepared as H.264 and AAC, and the TV's receiver fetches the session's
// grant addresses (`/v1/cast/<grant>/…`, no token, CORS for the receiver)
// from a public HTTPS hub address, since the TV is not on the tailnet. The
// phone is the remote, and the TV session reports as any playback does
// (`PlaybackReporter`), so the hub keeps the watch.

/// Where the TV fetches what it plays.
public enum CastAddress {
    /// Google's Default Media Receiver: no registration in the Cast console.
    public static let receiverAppId = "CC1AD845"

    /// `resource` (a `/v1/cast/…` path) on `base`, as the TV reaches it; nil
    /// when the TV could not: not HTTPS, a user or query in the address, or
    /// a host only this network or the tailnet knows (localhost, `.local`,
    /// private and tailnet ranges, a `.ts.net` name).
    public static func receiverURL(base: String, resource: String) -> URL? {
        guard resource.hasPrefix("/v1/cast/"), !resource.hasPrefix("//"), problem(base: base) == nil,
              let parsed = URLComponents(string: trimmed(base)) else { return nil }
        return URL(string: (parsed.string ?? "") + resource)
    }

    /// Why the TV cannot use `base`, in words; nil when it can.
    public static func problem(base: String) -> String? {
        let text = trimmed(base)
        if text.isEmpty { return "Set a public HTTPS address for TV playback in Settings › Ayaneo Hub" }
        guard let parsed = URLComponents(string: text), parsed.scheme?.lowercased() == "https",
              let host = parsed.host?.lowercased(), !host.isEmpty,
              parsed.user == nil, parsed.password == nil, parsed.query == nil, parsed.fragment == nil else {
            return "The address for TV playback must be a public HTTPS address"
        }
        if host.hasSuffix(".ts.net") {
            return "The TV is not on the tailnet: set the public address for TV playback in Settings › Ayaneo Hub"
        }
        let local = host == "localhost" || host.hasSuffix(".local") || host == "::1" || host == "[::1]"
            || host.hasPrefix("127.") || host.hasPrefix("10.") || host.hasPrefix("192.168.")
            || host.hasPrefix("172.") || host.hasPrefix("100.") || host.hasPrefix("169.254.")
        return local ? "The TV cannot reach this address: set a public HTTPS address for TV playback" : nil
    }

    /// The address the TV is sent to: the one set for TV playback, else the hub's.
    public static func base(tvAddress: String, hubAddress: String) -> String {
        let tv = trimmed(tvAddress)
        return tv.isEmpty ? trimmed(hubAddress) : tv
    }

    static func trimmed(_ base: String) -> String {
        var text = base.trimmingCharacters(in: .whitespacesAndNewlines)
        while text.hasSuffix("/") { text.removeLast() }
        return text
    }
}

/// A subtitle the TV can show: a WebVTT side track the hub converts.
public struct CastTextTrack: Equatable, Sendable {
    /// The Cast track id: the Jellyfin stream index plus 1,000.
    public var id: Int
    public var subtitleIndex: Int
    public var name: String
    public var language: String
    public var url: URL
}

/// What the TV is asked to play.
public struct CastLoad: Equatable, Sendable {
    public var url: URL
    public var contentType: String
    public var title: String
    public var subtitle: String
    public var artwork: URL?
    public var durationMillis: Int64
    public var startMillis: Int64
    public var tracks: [CastTextTrack]
    public var activeTrackIds: [Int]
}

public enum CastPlan {
    /// The TV stream: one H.264 and AAC conversion at 20 Mbps, so the default
    /// receiver plays every file the library holds.
    public static let maxBitrate = 20_000_000
    public static let trackIdOffset = 1_000

    /// The TV's session, asked for at `positionMillis` with the version,
    /// audio and subtitles playing here; the device is this one's, "to Google TV".
    public static func prepareBody(_ plan: PlaybackPrepareResponse, positionMillis: Int64,
                                   startMode: PlaybackStartMode = .resume, device: PlaybackDevice) -> PlaybackPrepareBody {
        PlaybackPrepareBody(
            startMode: startMode, positionMillis: max(0, positionMillis),
            mediaSourceId: plan.selectedMediaSourceId.isEmpty ? nil : plan.selectedMediaSourceId,
            audioStreamIndex: plan.selectedAudioIndex, subtitleStreamIndex: plan.selectedSubtitleIndex,
            maxBitrate: maxBitrate, forceTranscode: true,
            device: PlaybackDevice(id: device.id + "-cast", name: device.name + " to Google TV", version: device.version),
            capabilities: PlaybackCapabilities(width: 1_920, height: 1_080, maxAudioChannels: 2,
                                               videoCodecs: ["h264"], audioCodecs: ["aac", "mp3"]))
    }

    /// The subtitles the TV can show: external text (SRT or WebVTT), which
    /// the hub sends as WebVTT. Embedded and picture subtitles stay here.
    public static func textSubtitles(_ plan: PlaybackPrepareResponse) -> [PlaybackTrack] {
        plan.subtitleTracks.filter { $0.external && ["srt", "subrip", "vtt", "webvtt"].contains($0.codec.lowercased()) }
    }

    /// The side tracks for the TV, each at the grant's address on `base`.
    public static func tracks(_ plan: PlaybackPrepareResponse, grant: PlaybackGrant, base: String) -> [CastTextTrack] {
        textSubtitles(plan).compactMap { track in
            guard let path = grant.subtitleUrls[String(track.index)],
                  let url = CastAddress.receiverURL(base: base, resource: path) else { return nil }
            let language = track.language.trimmingCharacters(in: .whitespaces)
            return CastTextTrack(id: track.index + trackIdOffset, subtitleIndex: track.index,
                                 name: track.label.isEmpty ? (language.isEmpty ? "Subtitles" : language) : track.label,
                                 language: language.isEmpty ? "und" : language, url: url)
        }
    }

    /// The tracks showing on the TV for `subtitleIndex`: none for Off or a track it cannot show.
    public static func activeTrackIds(_ tracks: [CastTextTrack], subtitleIndex: Int?) -> [Int] {
        guard let subtitleIndex, let track = tracks.first(where: { $0.subtitleIndex == subtitleIndex }) else { return [] }
        return [track.id]
    }

    /// Where the TV starts: where this device was when it moved, even where
    /// the hub's plan says 0:00 (it puts a title's first half-minute back to
    /// the start); a restart (the next episode) at its beginning.
    public static func startMillis(planned: Int64, asked: Int64, mode: PlaybackStartMode) -> Int64 {
        mode == .restart ? 0 : max(0, max(planned, asked))
    }

    /// A conversion is HLS; a file played as it is keeps its own type.
    public static func contentType(_ plan: PlaybackPrepareResponse) -> String {
        plan.mediaUrl.contains("/hls/") || plan.playMethod.lowercased() == "transcode"
            ? "application/vnd.apple.mpegurl"
            : (plan.mimeType.isEmpty ? "video/mp4" : plan.mimeType)
    }

    /// Everything the TV is asked to play, or why it cannot be.
    public static func load(_ plan: PlaybackPrepareResponse, grant: PlaybackGrant, base: String,
                            artwork: URL? = nil) -> Result<CastLoad, CastFailure> {
        guard let url = CastAddress.receiverURL(base: base, resource: grant.mediaUrl) else {
            return .failure(CastFailure(CastAddress.problem(base: base) ?? "The TV cannot reach this stream"))
        }
        let tracks = tracks(plan, grant: grant, base: base)
        return .success(CastLoad(url: url, contentType: contentType(plan), title: PlayerLabels.title(plan.item),
                                 subtitle: PlayerLabels.subtitle(plan.item), artwork: artwork,
                                 durationMillis: plan.durationMillis, startMillis: plan.positionMillis,
                                 tracks: tracks, activeTrackIds: activeTrackIds(tracks, subtitleIndex: plan.selectedSubtitleIndex)))
    }
}

public struct CastFailure: Error, Equatable, Sendable {
    public var message: String
    public init(_ message: String) { self.message = message }
}

/// The TV's player, as its receiver reports it.
public enum CastPlayerState: Equatable, Sendable {
    case loading, buffering, playing, paused
    /// Nothing playing: `finished` when the video played to its end.
    case idle(finished: Bool)
}

/// Where the Cast button and the player stand (`CastPresentation`).
public enum CastConnection: Equatable, Sendable {
    /// No Cast device has been seen.
    case unavailable
    /// A device can be chosen.
    case available
    case connecting(String)
    case connected(String)

    public var deviceName: String? {
        switch self {
        case .connecting(let name), .connected(let name): name
        default: nil
        }
    }
}

public enum CastPresentation {
    /// The Cast button's name for VoiceOver and the UI tests.
    public static func buttonLabel(_ connection: CastConnection) -> String {
        switch connection {
        case .unavailable: "Cast, no TV found"
        case .available: "Cast to a TV"
        case .connecting(let name): "Connecting to \(name)"
        case .connected(let name): "Casting to \(name)"
        }
    }

    /// Lit while connected; dimmed while nothing can be chosen.
    public static func lit(_ connection: CastConnection) -> Bool {
        if case .connected = connection { return true }
        return false
    }

    /// "Playing on Living room TV" over the picture's place.
    public static func playingOn(_ device: String) -> String {
        "Playing on " + (device.isEmpty ? "TV" : device)
    }

    /// Back from the TV: "Move to this iPhone", or iPad.
    public static func moveHere(pad: Bool) -> String { "Move to this " + (pad ? "iPad" : "iPhone") }

    /// The step the player shows while the video moves.
    public static let preparing = "Preparing TV playback…"

    /// The TV's player state, for the chrome: playing, and waiting.
    public static func playing(_ state: CastPlayerState) -> Bool { state == .playing }
    public static func waiting(_ state: CastPlayerState) -> Bool { state == .loading || state == .buffering }
}
