// App Store Connect from the command line, for `scripts/mac.sh testflight`:
// the app's builds, their processing, and its TestFlight group.
//
//   swift asc.swift builds [count]           the newest builds, every platform
//   swift asc.swift wait <build> [minutes]   until the build is VALID on iOS and macOS
//   swift asc.swift group <name>             the builds a TestFlight group has
//
// It reads ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_PATH and ASC_APP_ID from the
// environment (`~/.appstoreconnect/jellyhub.env` on the Mac, never the repo),
// signs a 20-minute ES256 token with the key where it lies, and prints
// nothing of either.
import CryptoKit
import Foundation

let environment = ProcessInfo.processInfo.environment

func fail(_ message: String) -> Never {
    FileHandle.standardError.write(Data((message + "\n").utf8))
    exit(1)
}

func need(_ name: String) -> String {
    guard let value = environment[name], !value.isEmpty else { fail("\(name) is not set") }
    return value
}

func base64url(_ data: Data) -> String {
    data.base64EncodedString()
        .replacingOccurrences(of: "+", with: "-")
        .replacingOccurrences(of: "/", with: "_")
        .replacingOccurrences(of: "=", with: "")
}

func token() -> String {
    let path = (need("ASC_KEY_PATH") as NSString).expandingTildeInPath
    guard let pem = try? String(contentsOfFile: path, encoding: .utf8),
          let key = try? P256.Signing.PrivateKey(pemRepresentation: pem) else { fail("the API key could not be read") }
    let now = Int(Date().timeIntervalSince1970)
    let header: [String: Any] = ["alg": "ES256", "kid": need("ASC_KEY_ID"), "typ": "JWT"]
    let claims: [String: Any] = ["iss": need("ASC_ISSUER_ID"), "iat": now, "exp": now + 1_200, "aud": "appstoreconnect-v1"]
    guard let headerData = try? JSONSerialization.data(withJSONObject: header),
          let claimsData = try? JSONSerialization.data(withJSONObject: claims) else { fail("the token could not be made") }
    let unsigned = base64url(headerData) + "." + base64url(claimsData)
    guard let signature = try? key.signature(for: Data(unsigned.utf8)) else { fail("the token could not be signed") }
    return unsigned + "." + base64url(signature.rawRepresentation)
}

func get(_ path: String) async -> [String: Any] {
    guard let url = URL(string: "https://api.appstoreconnect.apple.com" + path) else { fail("bad path \(path)") }
    var request = URLRequest(url: url)
    request.setValue("Bearer " + token(), forHTTPHeaderField: "Authorization")
    guard let (data, response) = try? await URLSession.shared.data(for: request) else { fail("App Store Connect did not answer") }
    let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
    if let status = (response as? HTTPURLResponse)?.statusCode, status >= 400 {
        let errors = (json["errors"] as? [[String: Any]] ?? []).map { "\($0["title"] ?? ""): \($0["detail"] ?? "")" }
        fail("App Store Connect answered \(status): \(errors.joined(separator: "; "))")
    }
    return json
}

struct Build {
    let id: String
    let number: String
    let version: String
    let platform: String
    let state: String
    let uploaded: String
}

/// The builds with their marketing version and platform, newest first.
func builds(limit: Int, filter: String = "") async -> [Build] {
    let app = need("ASC_APP_ID")
    let json = await get("/v1/builds?filter[app]=\(app)\(filter)&sort=-uploadedDate&limit=\(limit)"
        + "&include=preReleaseVersion&fields[builds]=version,processingState,uploadedDate,preReleaseVersion"
        + "&fields[preReleaseVersions]=version,platform")
    var versions: [String: (String, String)] = [:]
    for item in json["included"] as? [[String: Any]] ?? [] {
        let attributes = item["attributes"] as? [String: Any] ?? [:]
        versions[item["id"] as? String ?? ""] = (attributes["version"] as? String ?? "?", attributes["platform"] as? String ?? "?")
    }
    return (json["data"] as? [[String: Any]] ?? []).map { item in
        let attributes = item["attributes"] as? [String: Any] ?? [:]
        let relation = ((item["relationships"] as? [String: Any])?["preReleaseVersion"] as? [String: Any])?["data"] as? [String: Any]
        let (version, platform) = versions[relation?["id"] as? String ?? ""] ?? ("?", "?")
        return Build(id: item["id"] as? String ?? "", number: attributes["version"] as? String ?? "?", version: version,
                     platform: platform, state: attributes["processingState"] as? String ?? "?",
                     uploaded: attributes["uploadedDate"] as? String ?? "")
    }
}

func line(_ build: Build) -> String {
    "\(build.platform) \(build.version) (\(build.number)) \(build.state) \(build.uploaded)"
}

let arguments = Array(CommandLine.arguments.dropFirst())
switch arguments.first {
case "builds":
    for build in await builds(limit: Int(arguments.dropFirst().first ?? "") ?? 6) { print(line(build)) }
case "wait":
    guard let number = arguments.dropFirst().first else { fail("asc.swift wait <build> [minutes]") }
    let minutes = Double(arguments.dropFirst(2).first ?? "") ?? 45
    let deadline = Date().addingTimeInterval(minutes * 60)
    while true {
        let found = await builds(limit: 10, filter: "&filter[version]=\(number)")
        let states = found.map { "\($0.platform) \($0.state)" }.joined(separator: ", ")
        print("\(ISO8601DateFormatter().string(from: Date())) \(number): \(states.isEmpty ? "not seen yet" : states)")
        if found.contains(where: { $0.state == "FAILED" || $0.state == "INVALID" }) { fail("a build did not pass processing") }
        let valid = Set(found.filter { $0.state == "VALID" }.map(\.platform))
        if valid.contains("IOS") && valid.contains("MAC_OS") { break }
        if Date() > deadline { fail("still processing after \(Int(minutes)) minutes") }
        try? await Task.sleep(for: .seconds(30))
    }
case "group":
    guard let name = arguments.dropFirst().first else { fail("asc.swift group <name>") }
    let app = need("ASC_APP_ID")
    let groups = await get("/v1/betaGroups?filter[app]=\(app)&filter[name]=\(name)&fields[betaGroups]=name,isInternalGroup")
    guard let group = (groups["data"] as? [[String: Any]])?.first, let id = group["id"] as? String else {
        fail("no TestFlight group named \(name)")
    }
    let linked = await get("/v1/betaGroups/\(id)/builds?limit=20&fields[builds]=version,processingState")
    let numbers = (linked["data"] as? [[String: Any]] ?? []).compactMap { ($0["attributes"] as? [String: Any])?["version"] as? String }
    print("group \(name): \(numbers.isEmpty ? "no builds" : numbers.joined(separator: ", "))")
default:
    fail("swift asc.swift builds [count] | wait <build> [minutes] | group <name>")
}
