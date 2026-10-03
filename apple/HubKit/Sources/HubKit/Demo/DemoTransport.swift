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
        let (status, body): (Int, String) = switch (request.httpMethod ?? "GET", path) {
        case ("GET", "/v1/health"): (200, Self.health)
        case ("GET", "/v1/users"): (200, Self.users)
        case ("POST", "/v1/manage/jellyfin/scan"), ("POST", "/v1/manage/reading/scan"):
            (202, #"{"ok":true,"action":"scan_library"}"#)
        default: (404, #"{"error":{"code":"not_found","message":"Not in the demo hub yet"}}"#)
        }
        let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1",
                                       headerFields: ["Content-Type": "application/json"])!
        return (Data(body.utf8), response)
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
}
