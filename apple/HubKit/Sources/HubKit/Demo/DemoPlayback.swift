import CoreGraphics
import Foundation
import ImageIO
import Synchronization

/// The player's calls in the demo hub (`-demo`): a session for any item
/// ("demo-e5" is Bleach S1E5, with episodes either side) whose grant is
/// Apple's public test stream, so the player runs with no hub and writes no
/// watch history. It has the panels' material: two audio tracks, subtitles
/// as SRT, WebVTT and ASS files, two versions, chapters with frames, an intro
/// to skip and the credits. A change of track, quality or version is kept
/// per session, as the hub keeps it, so a second change keeps the first.
///
/// Stricter than the hub on two points, so the UI tests that play prove what
/// the app sends: it refuses a prepare that does not name AVPlayer's
/// containers and ask for fMP4 HLS (#2), which the hub would serve with the
/// Pocket's Media3 profile; and a select that changes a track without naming
/// the version it belongs to (#24), which Jellyfin takes and then converts the
/// default track again.
enum DemoPlayback {
    /// fMP4 HLS, H.264 and AAC, about ten minutes.
    static let stream = "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8"

    /// What a session has chosen.
    struct Choice: Sendable {
        var audio = 1
        var subtitle: Int?
        var bitrate = 0
        var source = "demo-1080"

        mutating func apply(_ body: Data?) {
            guard let body, let fields = try? JSONSerialization.jsonObject(with: body) as? [String: Any] else { return }
            if let value = fields["audioStreamIndex"] as? Int { audio = value }
            if let value = fields["subtitleStreamIndex"] as? Int { subtitle = value < 0 ? nil : value }
            if let value = fields["maxBitrate"] as? Int { bitrate = value }
            if let value = fields["mediaSourceId"] as? String { source = value }
        }
    }

    private static let choices = Mutex<[String: Choice]>([:])
    /// The library item each session was prepared for, so a subtitle downloaded
    /// for it (#34) is among the tracks its player lists.
    private static let sessionItems = Mutex<[String: String]>([:])

    static func answer(method: String, path: String, query: String, body: Data?) -> DemoTransport.Answer? {
        let parts = path.split(separator: "/").map(String.init)
        guard parts.count >= 4, parts[0] == "v1", parts[1] == "playback" else { return nil }
        let ok = DemoTransport.Answer(200, #"{"ok":true}"#)
        switch (method, parts[2], parts.count >= 5 ? parts[4] : "") {
        case ("POST", "items", "prepare"):
            // The TV's session (#44) asks for H.264 and AAC, not AVPlayer's containers.
            let cast = isCast(body)
            guard namesAVPlayer(body) || cast else {
                return DemoTransport.Answer(400, #"{"error":{"code":"invalid_request","message":"Name AVPlayer's containers and ask for fMP4 HLS (#2)"}}"#)
            }
            let number = Int(parts[3].split(separator: "e").last ?? "") ?? 5
            var choice = Choice()
            choice.apply(body)
            let session = sessionId(number, cast: cast)
            choices.withLock { $0[session] = choice }
            sessionItems.withLock { $0[session] = parts[3] }
            return DemoTransport.Answer(200, plan(number: number, choice: choice, item: parts[3], cast: cast))
        case ("POST", "sessions", "select"):
            guard namesItsVersion(body) else {
                return DemoTransport.Answer(400, #"{"error":{"code":"invalid_request","message":"Name the version a track belongs to (#24)"}}"#)
            }
            let session = parts[3]
            let choice = choices.withLock { all in
                var choice = all[session] ?? Choice()
                choice.apply(body)
                all[session] = choice
                return choice
            }
            let item = sessionItems.withLock { $0[session] } ?? ""
            return DemoTransport.Answer(200, plan(number: Int(session.suffix(2)) ?? 5, choice: choice, item: item,
                                                  cast: session.hasPrefix("c")))
        case ("POST", "sessions", "cast-grant") where parts[3].hasPrefix("c"):
            // The TV's grant, as the hub's: its own paths, and WebVTT for the text subtitles.
            let grant = "/v1/cast/demo-grant-" + parts[3]
            return DemoTransport.Answer(200, #"{"mediaUrl":"\#(grant)/hls/master","mimeType":"application/x-mpegURL","subtitleUrls":{"3":"\#(grant)/subtitles/3","4":"\#(grant)/subtitles/4"}}"#)
        case ("POST", "sessions", "cast-grant"):
            return DemoTransport.Answer(200, #"{"mediaUrl":"\#(stream)","mimeType":"application/x-mpegURL","subtitleUrls":{}}"#)
        case ("GET", "sessions", "subtitles") where parts.count == 6:
            return DemoTransport.Answer(200, subtitles(track: Int(parts[5]) ?? 3), type: "text/plain; charset=utf-8")
        case ("GET", "sessions", "preview"):
            let position = query.split(separator: "&").first { $0.hasPrefix("positionMillis=") }
                .flatMap { Int64($0.dropFirst("positionMillis=".count)) } ?? 0
            return DemoTransport.Answer(200, data: frame(positionMillis: position), type: "image/jpeg")
        case ("POST", "sessions", "events"):
            return ok
        case ("DELETE", "sessions", ""):
            choices.withLock { $0[parts[3]] = nil }
            sessionItems.withLock { $0[parts[3]] = nil }
            return ok
        default:
            return nil
        }
    }

    /// A prepare from this app: AVPlayer's containers (mp4 among them, no
    /// Matroska) and fMP4 segments, the profile #2 describes.
    static func namesAVPlayer(_ body: Data?) -> Bool {
        guard let body, let fields = try? JSONSerialization.jsonObject(with: body) as? [String: Any],
              let capabilities = fields["capabilities"] as? [String: Any],
              let containers = capabilities["containers"] as? [String] else { return false }
        return containers.contains("mp4") && !containers.contains("mkv")
            && (capabilities["hlsSegments"] as? String)?.lowercased() == "fmp4"
    }

    /// A prepare for the TV (#44): this device's id with "-cast", converted.
    static func isCast(_ body: Data?) -> Bool {
        guard let body, let fields = try? JSONSerialization.jsonObject(with: body) as? [String: Any],
              let device = fields["device"] as? [String: Any] else { return false }
        return ((device["id"] as? String) ?? "").hasSuffix("-cast") && fields["forceTranscode"] as? Bool == true
    }

    /// A select changing the audio or the subtitles names the media source
    /// they belong to, as Jellyfin needs to apply them.
    static func namesItsVersion(_ body: Data?) -> Bool {
        guard let body, let fields = try? JSONSerialization.jsonObject(with: body) as? [String: Any] else { return false }
        let changesTrack = fields["audioStreamIndex"] != nil || fields["subtitleStreamIndex"] != nil
        return !changesTrack || !((fields["mediaSourceId"] as? String) ?? "").isEmpty
    }

    private static let episodes = [4: "Cursed Parakeet", 5: "Beat the Invisible Enemy!", 6: "Fight to the Death! Ichigo vs. Ichigo"]

    /// The TV's sessions start with "c", this device's with "d".
    private static func sessionId(_ number: Int, cast: Bool = false) -> String {
        String(repeating: cast ? "c" : "d", count: 30) + String(format: "%02d", number % 100)
    }

    private static func plan(number: Int, choice: Choice, item itemId: String = "", cast: Bool = false) -> String {
        func item(_ n: Int) -> String {
            let title = episodes[n] ?? "Episode \(n)"
            return #"{"id":"demo-e\#(n)","type":"episode","title":"\#(title)","seriesTitle":"Bleach","seriesId":"demo-bleach","seasonNumber":1,"episodeNumber":\#(n)}"#
        }
        let session = sessionId(number, cast: cast)
        let previous = number > 1 ? #","previousItem":\#(item(number - 1))"# : ""
        let subtitle = choice.subtitle.map { #","selectedSubtitleIndex":\#($0)"# } ?? ""
        // A lower quality is a conversion, with an address of its own, so the
        // player opens it again where it was; the original is played as it is.
        let converted = choice.bitrate > 0 || cast
        let media = converted ? "/v1/playback/sessions/\(session)/hls/demo-\(choice.bitrate)" : "/v1/playback/sessions/\(session)/stream"
        let method = converted ? "Transcode" : "DirectStream"
        let reason = converted ? #","transcodeReason":"ContainerBitrateExceedsLimit""# : ""
        let height = choice.source == "demo-720" ? 720 : 1080
        let file = "/v1/playback/sessions/\(session)/subtitles"
        // What Bazarr downloaded for this item in this run: external tracks after the three that every item has.
        let downloaded = DemoUpkeep.downloadedTracks(for: itemId).enumerated().map { offset, track in
            let flags = (track.forced ? #","forced":true"# : "") + (track.hi ? #","hearingImpaired":true"# : "")
            return #",{"index":\#(10 + offset),"type":"subtitle","language":"\#(track.code)","codec":"srt","external":true,"#
                + #""externalUrl":"\#(file)/\#(10 + offset)"\#(flags)}"#
        }.joined()
        return #"""
        {"sessionId":"\#(session)","item":\#(item(number)),
         "positionMillis":0,"durationMillis":0,"mediaUrl":"\#(media)",
         "mimeType":"application/x-mpegURL","playMethod":"\#(method)"\#(reason),"videoCodec":"h264","audioCodec":"aac",
         "width":\#(height * 16 / 9),"height":\#(height),"frameRate":23.976,"bitrate":\#(converted ? choice.bitrate : 8_000_000),
         "sources":[{"id":"demo-1080","name":"1080p","container":"mkv","bitrate":8000000},
                    {"id":"demo-720","name":"720p","container":"mp4","bitrate":3500000}],
         "selectedMediaSourceId":"\#(choice.source)",
         "audioTracks":[{"index":1,"type":"audio","label":"Japanese - Stereo","language":"jpn","codec":"aac","channels":2,"default":true},
                        {"index":2,"type":"audio","label":"English - Stereo","language":"eng","codec":"aac","channels":2}],
         "selectedAudioIndex":\#(choice.audio)\#(subtitle),
         "subtitleTracks":[{"index":3,"type":"subtitle","language":"eng","codec":"srt","external":true,"externalUrl":"\#(file)/3"},
                           {"index":4,"type":"subtitle","language":"heb","codec":"webvtt","external":true,"externalUrl":"\#(file)/4"},
                           {"index":5,"type":"subtitle","label":"Signs","language":"eng","codec":"ass","forced":true,"external":true,"externalUrl":"\#(file)/5"}\#(downloaded)],
         "nextItem":\#(item(number + 1))\#(previous),
         "previewUrl":"/v1/playback/sessions/\#(session)/preview",
         "chapters":[{"id":"0","name":"Opening","positionMillis":0},{"id":"1","name":"Part A","positionMillis":90000},
                     {"id":"2","name":"Part B","positionMillis":290000},{"id":"3","name":"Ending","positionMillis":560000}],
         "segments":[{"id":"intro","type":"Intro","startMillis":0,"endMillis":85000},
                     {"id":"credits","type":"Outro","startMillis":560000,"endMillis":600000}]}
        """#
    }

    /// A line every four seconds for the stream's ten minutes, numbered so a
    /// timing change can be seen; every third has a second line.
    static func subtitles(track: Int) -> String {
        let cues = (0..<150).map { index -> (start: Int, end: Int, number: Int) in
            (index * 4_000 + 500, index * 4_000 + 3_700, index + 1)
        }
        func clock(_ millis: Int, comma: Bool) -> String {
            String(format: "%02d:%02d:%02d%@%03d", millis / 3_600_000, millis / 60_000 % 60, millis / 1_000 % 60,
                   comma ? "," : ".", millis % 1_000)
        }
        switch track {
        case 4:
            return "WEBVTT\n\n" + cues.map { cue in
                let second = cue.number % 3 == 0 ? "\nשורה שנייה, לבדיקת שתי שורות" : ""
                return "\(clock(cue.start, comma: false)) --> \(clock(cue.end, comma: false))\nכתובית הדגמה \(cue.number)\(second)\n"
            }.joined(separator: "\n")
        case 5:
            let events = cues.filter { $0.number % 5 == 1 }.map { cue in
                let start = String(format: "%d:%02d:%02d.%02d", cue.start / 3_600_000, cue.start / 60_000 % 60,
                                   cue.start / 1_000 % 60, cue.start % 1_000 / 10)
                let end = String(format: "%d:%02d:%02d.%02d", cue.end / 3_600_000, cue.end / 60_000 % 60,
                                 cue.end / 1_000 % 60, cue.end % 1_000 / 10)
                return "Dialogue: 0,\(start),\(end),Sign,,0,0,0,,{\\an8\\b1}Sign \(cue.number), on the wall"
            }
            return "[Script Info]\nScriptType: v4.00+\n\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"
                + events.joined(separator: "\n") + "\n"
        default:
            return cues.map { cue in
                let second = cue.number % 3 == 0 ? "\n<i>A second line, to see two stack</i>" : ""
                return "\(cue.number)\n\(clock(cue.start, comma: true)) --> \(clock(cue.end, comma: true))\nDemo subtitle \(cue.number)\(second)\n"
            }.joined(separator: "\n")
        }
    }

    /// A frame for a chapter's picture: a colour from the position, as the
    /// hub's would differ from chapter to chapter, with a pale disc.
    static func frame(positionMillis: Int64) -> Data {
        let width = 240, height = 135
        let space = CGColorSpaceCreateDeviceRGB()
        guard let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                                      space: space, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return Data() }
        let hue = Double(positionMillis % 600_000) / 600_000
        let top = color(hue: hue, saturation: 0.55, brightness: 0.6)
        let bottom = color(hue: (hue + 0.08).truncatingRemainder(dividingBy: 1), saturation: 0.7, brightness: 0.22)
        if let gradient = CGGradient(colorsSpace: space, colors: [top, bottom] as CFArray, locations: [0, 1]) {
            context.drawLinearGradient(gradient, start: CGPoint(x: 0, y: height), end: .zero, options: [])
        }
        context.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 0.2))
        context.fillEllipse(in: CGRect(x: width / 2 - 34, y: height / 2 - 34, width: 68, height: 68))
        guard let image = context.makeImage() else { return Data() }
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(data, "public.jpeg" as CFString, 1, nil) else { return Data() }
        CGImageDestinationAddImage(destination, image, nil)
        CGImageDestinationFinalize(destination)
        return data as Data
    }

    private static func color(hue: Double, saturation: Double, brightness: Double) -> CGColor {
        let sector = hue * 6, index = Int(sector) % 6, fraction = sector - Double(Int(sector))
        let p = brightness * (1 - saturation), q = brightness * (1 - saturation * fraction)
        let t = brightness * (1 - saturation * (1 - fraction))
        let (red, green, blue) = switch index {
        case 0: (brightness, t, p)
        case 1: (q, brightness, p)
        case 2: (p, brightness, t)
        case 3: (p, q, brightness)
        case 4: (t, p, brightness)
        default: (brightness, p, q)
        }
        return CGColor(red: red, green: green, blue: blue, alpha: 1)
    }
}
