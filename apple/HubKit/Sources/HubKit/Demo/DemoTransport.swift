import Foundation
import Synchronization

/// Answers hub requests from fixtures, for the `-demo` launch argument, SwiftUI
/// previews and layout screenshots taken without a reachable hub. The fixtures
/// follow the live stack measured on 2026-09-07 (see CLAUDE.md), so screens
/// are laid out against realistic names and lengths. Never used for a
/// configured hub.
public struct DemoTransport: HubTransport {
    public static let address = "https://demo.hub.invalid"
    public static let token = String(repeating: "d", count: 43)

    /// An outage of the reading servers (#37, debug builds' UI tests): every
    /// issue's page list, page and place goes unanswered, as with no network,
    /// except a page this run already served asked for from the device's
    /// cache only, which the demo stands in for.
    static let outage = Mutex(false)
    private static let served = Mutex<Set<String>>([])

    public static func beginOutage() { outage.withLock { $0 = true } }
    public static func endOutage() { outage.withLock { $0 = false } }

    public init() {}

    public func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        try await Task.sleep(for: .milliseconds(250))
        let path = request.url?.path ?? ""
        let method = request.httpMethod ?? "GET"
        let query = request.url?.query ?? ""
        if path.contains("/publications/") {
            if request.cachePolicy == .returnCacheDataDontLoad {
                guard Self.served.withLock({ $0.contains(path) }) else { throw URLError(.resourceUnavailable) }
            } else if Self.outage.withLock({ $0 }) {
                throw URLError(.notConnectedToInternet)
            }
        }
        // Offline downloads first: their video is written once per run, which takes a moment (#5).
        let offline = await DemoOffline.answer(method: method, path: path, query: query, body: request.httpBody)
        let answer = offline
            ?? DemoActivity.answer(method: method, path: path, query: query, body: request.httpBody)
            ?? DemoPlayback.answer(method: method, path: path, query: query, body: request.httpBody)
            ?? DemoMedia.answer(method: method, path: path, query: query, body: request.httpBody)
            ?? DemoComics.answer(method: method, path: path, query: query, body: request.httpBody)
            ?? DemoReadAlong.answer(method: method, path: path, query: query, body: request.httpBody)
            ?? DemoBooks.answer(method: method, path: path, query: query, body: request.httpBody)
            ?? DemoReading.answer(method: method, path: path, query: query, body: request.httpBody)
            ?? Self.fixture(method: method, path: path)
        if method == "GET", answer.status == 200, path.contains("/publications/"), path.contains("/pages/") {
            _ = Self.served.withLock { $0.insert(path) }
        }
        let response = HTTPURLResponse(url: request.url!, statusCode: answer.status, httpVersion: "HTTP/1.1",
                                       headerFields: answer.headers.merging(["Content-Type": answer.type]) { _, type in type })!
        return (answer.body, response)
    }

    /// A demo answer: its status, bytes and their type, and any other headers
    /// (an offline file's ETag, a Retry-After).
    struct Answer {
        let status: Int
        let body: Data
        var type = "application/json"
        var headers: [String: String] = [:]

        init(_ status: Int, _ text: String, type: String = "application/json") {
            self.status = status
            body = Data(text.utf8)
            self.type = type
        }

        init(_ status: Int, data: Data, type: String) {
            self.status = status
            body = data
            self.type = type
        }
    }

    static func fixture(method: String, path: String) -> Answer {
        switch (method, path) {
        case ("GET", "/v1/health"): Answer(200, health)
        case ("GET", "/v1/users"): Answer(200, users)
        case ("GET", "/v1/notifications"): Answer(200, notifications)
        case ("POST", "/v1/manage/jellyfin/scan"), ("POST", "/v1/manage/reading/scan"):
            Answer(202, #"{"ok":true,"action":"scan_library"}"#)
        default: Answer(404, #"{"error":{"code":"not_found","message":"Not in the demo hub yet"}}"#)
        }
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
