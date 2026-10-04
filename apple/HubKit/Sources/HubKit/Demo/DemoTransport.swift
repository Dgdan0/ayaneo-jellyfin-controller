import Foundation

/// Answers hub requests from fixtures, for the `-demo` launch argument, SwiftUI
/// previews and layout screenshots taken without a reachable hub. The fixtures
/// follow the live stack measured on 2026-09-07 (see CLAUDE.md), so screens
/// are laid out against realistic names and lengths. Never used for a
/// configured hub.
public struct DemoTransport: HubTransport {
    public static let address = "https://demo.hub.invalid"
    public static let token = String(repeating: "d", count: 43)

    public init() {}

    public func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        try await Task.sleep(for: .milliseconds(250))
        let path = request.url?.path ?? ""
        let method = request.httpMethod ?? "GET"
        let (status, body) = Self.playback(method: method, path: path) ?? Self.fixture(method: method, path: path)
        let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1",
                                       headerFields: ["Content-Type": "application/json"])!
        return (Data(body.utf8), response)
    }

    static func fixture(method: String, path: String) -> (Int, String) {
        switch (method, path) {
        case ("GET", "/v1/health"): (200, health)
        case ("GET", "/v1/users"): (200, users)
        case ("GET", "/v1/notifications"): (200, notifications)
        case ("POST", "/v1/manage/jellyfin/scan"), ("POST", "/v1/manage/reading/scan"):
            (202, #"{"ok":true,"action":"scan_library"}"#)
        default: (404, #"{"error":{"code":"not_found","message":"Not in the demo hub yet"}}"#)
        }
    }

    /// Apple's public HLS test stream (fMP4, H.264 and AAC): the demo's
    /// picture, so the player runs with no hub and writes no watch history.
    static let demoStream = "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8"

    /// The player's calls: a session for any item ("demo-e5" is Bleach S1E5,
    /// with episodes either side), its grant the test stream, every event and
    /// the close accepted.
    static func playback(method: String, path: String) -> (Int, String)? {
        let parts = path.split(separator: "/").map(String.init)
        guard parts.count >= 4, parts[0] == "v1", parts[1] == "playback" else { return nil }
        let ok = #"{"ok":true}"#
        switch (method, parts[2], parts.count == 5 ? parts[4] : "") {
        case ("POST", "items", "prepare"): return (200, plan(itemId: parts[3]))
        case ("POST", "sessions", "select"): return (200, plan(itemId: "demo-e5"))
        case ("POST", "sessions", "cast-grant"):
            return (200, #"{"mediaUrl":"\#(demoStream)","mimeType":"application/x-mpegURL","subtitleUrls":{}}"#)
        case ("POST", "sessions", "events"), ("DELETE", "sessions", ""): return (200, ok)
        default: return nil
        }
    }

    private static let episodes = [4: "Cursed Parakeet", 5: "Beat the Invisible Enemy!", 6: "Fight to the Death! Ichigo vs. Ichigo"]

    private static func plan(itemId: String) -> String {
        let number = Int(itemId.split(separator: "e").last ?? "") ?? 5
        func item(_ n: Int) -> String {
            let title = episodes[n] ?? "Episode \(n)"
            return #"{"id":"demo-e\#(n)","type":"episode","title":"\#(title)","seriesTitle":"Bleach","seriesId":"demo-bleach","seasonNumber":1,"episodeNumber":\#(n)}"#
        }
        let previous = number > 1 ? #","previousItem":\#(item(number - 1))"# : ""
        return #"""
        {"sessionId":"dddddddddddddddddddddddddddddd\#(String(format: "%02d", number % 100))","item":\#(item(number)),
         "positionMillis":0,"durationMillis":0,"mediaUrl":"/v1/playback/sessions/demo/hls/demo",
         "mimeType":"application/x-mpegURL","playMethod":"DirectStream","videoCodec":"h264","audioCodec":"aac",
         "audioTracks":[{"index":1,"type":"audio","label":"Japanese · AAC · stereo","language":"jpn","codec":"aac","default":true}],
         "subtitleTracks":[],"nextItem":\#(item(number + 1))\#(previous),
         "chapters":[{"id":"0","name":"Opening","positionMillis":0},{"id":"1","name":"Part A","positionMillis":90000},
                     {"id":"2","name":"Part B","positionMillis":290000},{"id":"3","name":"Ending","positionMillis":560000}],
         "segments":[]}
        """#
    }

    static let health = #"""
    {"hub":{"version":"0.9.4","uptimeSeconds":363600,"tokenCount":4},
     "services":[
      {"name":"bazarr","state":"up","dashboardUrl":"https://ayaneo-media-pc.tail737e96.ts.net:6767","latencyMs":18,"version":"1.6.0"},
      {"name":"jellyfin","state":"up","dashboardUrl":"https://ayaneo-media-pc.tail737e96.ts.net:8920","latencyMs":41,"version":"10.11.8"},
      {"name":"jellyseerr","state":"up","dashboardUrl":"https://ayaneo-media-pc.tail737e96.ts.net:5055","latencyMs":9,"version":"2.7.3"},
      {"name":"kavita","state":"up","latencyMs":22,"version":"0.8.7"},
      {"name":"qbittorrent","state":"up","dashboardUrl":"https://ayaneo-media-pc.tail737e96.ts.net:8080","latencyMs":6,"version":"5.0.4"},
      {"name":"radarr","state":"up","dashboardUrl":"https://ayaneo-media-pc.tail737e96.ts.net:7878","latencyMs":14,"version":"6.3.0.10514"},
      {"name":"sonarr","state":"up","dashboardUrl":"https://ayaneo-media-pc.tail737e96.ts.net:8989","latencyMs":15,"version":"4.0.19.2979"},
      {"name":"storyteller","state":"misconfigured","lastError":"no API key configured"}
     ]}
    """#

    static let users = #"""
    {"users":[{"id":"44444444444444444444444444444444","name":"Dgdan","selected":true},
              {"id":"55555555555555555555555555555555","name":"Adirimo"},
              {"id":"66666666666666666666666666666666","name":"Hadas"},
              {"id":"77777777777777777777777777777777","name":"Horim"}],
     "partial":[],"cache":{"hit":true,"ageSeconds":3,"stale":false}}
    """#

    /// One active warning and one service the hub cannot reach: two need attention.
    static let notifications = #"""
    {"generatedAt":"2026-10-04T09:00:00Z","attentionCount":2,
     "sections":[
      {"service":"sonarr","state":"up","items":[
        {"id":"sonarr:health:demo","service":"sonarr","kind":"health","severity":"warning",
         "title":"IndexerLongTermStatusCheck","detail":"Indexers unavailable due to failures for more than 6 hours","active":true}]},
      {"service":"storyteller","state":"unavailable","items":[]}],
     "partial":[],"cache":{"hit":false,"ageSeconds":0,"stale":false}}
    """#
}
