// App Store Connect from the command line, for `scripts/mac.sh testflight`:
// the app's builds, their processing, and its TestFlight group.
//
//   swift asc.swift builds [count]           the newest builds, every platform
//   swift asc.swift wait <build> [minutes]   until the build is VALID on iOS and macOS
//   swift asc.swift group <name> [build]     the builds a TestFlight group has, or
//                                            until it has that build on iOS and macOS
//   swift asc.swift notes <build> [file]     the build's "What to Test" in the TestFlight
//                                            app (en-US), on iOS and macOS: the file's
//                                            text set and read back, or what it says now
//   swift asc.swift cert <type> <csr> <out>  a new certificate (DISTRIBUTION,
//                                            MAC_INSTALLER_DISTRIBUTION) for a CSR, as DER
//   swift asc.swift profile <type> <bundle id> <certificate serial> <name> <out>
//                                            an App Store profile (IOS_APP_STORE,
//                                            MAC_APP_STORE) for that certificate, made
//                                            once and then reused while it holds it
//   swift asc.swift certificates             the team's certificates
//   swift asc.swift profiles                 the team's profiles and their certificates
//   swift asc.swift crashes [id]             the crashes testers shared from TestFlight's
//                                            prompt, newest first (device, OS, build, when),
//                                            or that one's crash log. Read only; a tester's
//                                            email is never printed
//
// It reads ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_PATH and ASC_APP_ID from the
// environment (`~/.appstoreconnect/jellyhub.env` on the Mac, never the repo),
// signs a 20-minute ES256 token with the key where it lies, and prints
// nothing of either.
import CryptoKit
import Foundation

let environment = ProcessInfo.processInfo.environment
// Each line as it is printed, through ssh too: `wait` reports every 30 seconds.
setvbuf(stdout, nil, _IOLBF, 0)

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
    await call("GET", path)
}

/// The answer's status and body, whatever the status.
func send(_ method: String, _ path: String, body: [String: Any]? = nil) async -> (Int, [String: Any]) {
    guard let url = URL(string: "https://api.appstoreconnect.apple.com" + path) else { fail("bad path \(path)") }
    var request = URLRequest(url: url)
    request.httpMethod = method
    request.setValue("Bearer " + token(), forHTTPHeaderField: "Authorization")
    if let body {
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try? JSONSerialization.data(withJSONObject: body)
    }
    guard let (data, response) = try? await URLSession.shared.data(for: request) else { fail("App Store Connect did not answer") }
    let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
    return ((response as? HTTPURLResponse)?.statusCode ?? 0, json)
}

func problems(_ json: [String: Any]) -> String {
    (json["errors"] as? [[String: Any]] ?? []).map { "\($0["title"] ?? ""): \($0["detail"] ?? "")" }.joined(separator: "; ")
}

func call(_ method: String, _ path: String, body: [String: Any]? = nil) async -> [String: Any] {
    let (status, json) = await send(method, path, body: body)
    if status >= 400 { fail("App Store Connect answered \(status): \(problems(json))") }
    return json
}

/// A profile's certificate ids, as `include=certificates` links them.
func certificateIds(_ profile: [String: Any]) -> [String] {
    let relationships = profile["relationships"] as? [String: Any] ?? [:]
    let linked = (relationships["certificates"] as? [String: Any])?["data"] as? [[String: Any]] ?? []
    return linked.compactMap { $0["id"] as? String }
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

/// The TestFlight app's "What to Test" is a build's beta localization; the
/// tester sees the one in their language, and en-US is the one we write.
let notesLocale = "en-US"
/// The most App Store Connect takes in "What to Test".
let notesLimit = 4_000

/// A build's en-US localization: its id and its "What to Test", if it has one yet.
func notesLocalization(_ build: String) async -> (id: String, text: String?)? {
    let json = await get("/v1/builds/\(build)/betaBuildLocalizations?limit=50"
        + "&fields[betaBuildLocalizations]=locale,whatsNew")
    for item in json["data"] as? [[String: Any]] ?? [] {
        let attributes = item["attributes"] as? [String: Any] ?? [:]
        if attributes["locale"] as? String == notesLocale, let id = item["id"] as? String {
            return (id, attributes["whatsNew"] as? String)
        }
    }
    return nil
}

func changeNotes(_ localization: String, _ text: String) async {
    _ = await call("PATCH", "/v1/betaBuildLocalizations/\(localization)", body: [
        "data": ["type": "betaBuildLocalizations", "id": localization, "attributes": ["whatsNew": text]],
    ])
}

/// The build's "What to Test": its en-US localization changed, or made when
/// it has none. App Store Connect may make one itself as processing ends, so
/// a refusal to make a second is answered by changing that one.
func setNotes(_ build: Build, _ text: String) async {
    if let existing = await notesLocalization(build.id) {
        await changeNotes(existing.id, text)
        return
    }
    let (status, json) = await send("POST", "/v1/betaBuildLocalizations", body: [
        "data": [
            "type": "betaBuildLocalizations",
            "attributes": ["locale": notesLocale, "whatsNew": text],
            "relationships": ["build": ["data": ["type": "builds", "id": build.id]]],
        ],
    ])
    if status == 409, let made = await notesLocalization(build.id) {
        await changeNotes(made.id, text)
    } else if status >= 400 {
        fail("App Store Connect answered \(status) to \(build.platform)'s notes: \(problems(json))")
    }
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
    guard let name = arguments.dropFirst().first else { fail("asc.swift group <name> [build]") }
    let app = need("ASC_APP_ID")
    let groups = await get("/v1/betaGroups?filter[app]=\(app)&filter[name]=\(name)&fields[betaGroups]=name,isInternalGroup")
    guard let group = (groups["data"] as? [[String: Any]])?.first, let id = group["id"] as? String else {
        fail("no TestFlight group named \(name)")
    }
    if let number = arguments.dropFirst(2).first {
        // Automatic distribution puts each platform's build in the group once it is processed.
        for attempt in 1...10 {
            let found = await builds(limit: 10, filter: "&filter[betaGroups]=\(id)&filter[version]=\(number)")
            let platforms = Set(found.map(\.platform)).sorted().joined(separator: " and ")
            if found.contains(where: { $0.platform == "IOS" }) && found.contains(where: { $0.platform == "MAC_OS" }) {
                print("group \(name) has \(number) for \(platforms)")
                break
            }
            if attempt == 10 { fail("group \(name) has \(number) for \(platforms.isEmpty ? "neither platform" : "only " + platforms) after five minutes") }
            try? await Task.sleep(for: .seconds(30))
        }
    } else {
        let linked = await get("/v1/betaGroups/\(id)/builds?limit=20&fields[builds]=version,processingState")
        let numbers = (linked["data"] as? [[String: Any]] ?? []).compactMap { ($0["attributes"] as? [String: Any])?["version"] as? String }
        print("group \(name): \(numbers.isEmpty ? "no builds" : numbers.joined(separator: ", "))")
    }
case "notes":
    let rest = Array(arguments.dropFirst())
    guard let number = rest.first else { fail("asc.swift notes <build> [file]") }
    // One build per platform under the number, and each has its own notes.
    let found = await builds(limit: 10, filter: "&filter[version]=\(number)")
    guard !found.isEmpty else { fail("App Store Connect has no build \(number)") }
    guard rest.count > 1 else {
        for build in found {
            let notes = await notesLocalization(build.id)
            print("\(build.platform) \(number): \(notes?.text ?? "no What to Test")")
        }
        exit(0)
    }
    guard let read = try? String(contentsOfFile: rest[1], encoding: .utf8) else { fail("could not read \(rest[1])") }
    let text = read.replacingOccurrences(of: "\r\n", with: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
    guard !text.isEmpty else { fail("the notes in \(rest[1]) are empty") }
    guard text.count <= notesLimit else { fail("the notes are \(text.count) characters; What to Test takes \(notesLimit)") }
    for build in found { await setNotes(build, text) }
    // Read back, as the TestFlight app will show them.
    for build in found {
        let shown = await notesLocalization(build.id)?.text ?? ""
        guard shown.trimmingCharacters(in: .whitespacesAndNewlines) == text else {
            fail("\(build.platform) \(number) does not show the notes it was given")
        }
    }
    let platforms = found.map(\.platform).sorted().joined(separator: " and ")
    print("What to Test set on \(number) for \(platforms), \(text.count) characters")
case "cert":
    let rest = Array(arguments.dropFirst())
    guard rest.count == 3, let csr = try? String(contentsOfFile: rest[1], encoding: .utf8) else {
        fail("asc.swift cert <type> <csr.pem> <out.cer>")
    }
    let made = await call("POST", "/v1/certificates", body: [
        "data": ["type": "certificates", "attributes": ["certificateType": rest[0], "csrContent": csr]],
    ])
    let attributes = (made["data"] as? [String: Any])?["attributes"] as? [String: Any] ?? [:]
    guard let content = attributes["certificateContent"] as? String, let der = Data(base64Encoded: content) else {
        fail("App Store Connect sent no certificate")
    }
    guard (try? der.write(to: URL(fileURLWithPath: rest[2]))) != nil else { fail("could not write \(rest[2])") }
    print("\(attributes["name"] ?? rest[0]), serial \(attributes["serialNumber"] ?? "?"), until \(attributes["expirationDate"] ?? "?")")
case "profile":
    let rest = Array(arguments.dropFirst())
    guard rest.count == 5 else { fail("asc.swift profile <type> <bundle id> <certificate serial> <name> <out>") }
    let (type, identifier, serial, name, out) = (rest[0], rest[1], rest[2], rest[3], rest[4])
    let bundles = await get("/v1/bundleIds?filter[identifier]=\(identifier)&fields[bundleIds]=identifier")
    guard let bundle = (bundles["data"] as? [[String: Any]])?.first(where: {
        (($0["attributes"] as? [String: Any])?["identifier"] as? String) == identifier
    })?["id"] as? String else { fail("no bundle id \(identifier)") }
    // openssl writes a serial with a leading zero that App Store Connect may leave out.
    func plain(_ serial: String) -> String { String(serial.uppercased().drop(while: { $0 == "0" })) }
    let certificates = await get("/v1/certificates?limit=200&fields[certificates]=serialNumber")
    guard let certificate = (certificates["data"] as? [[String: Any]] ?? []).first(where: {
        plain((($0["attributes"] as? [String: Any])?["serialNumber"] as? String) ?? "") == plain(serial)
    })?["id"] as? String else { fail("no certificate with serial \(serial)") }
    // By type, with the name matched here: App Store Connect's name filter returned
    // nothing for "JellyHub iOS App Store" made a minute before, so a second run asked
    // for a duplicate and was refused.
    let existing = await get("/v1/profiles?filter[profileType]=\(type)&limit=200&include=certificates"
        + "&fields[profiles]=name,profileState,profileContent,certificates")
    var content: String?
    for profile in existing["data"] as? [[String: Any]] ?? [] {
        let attributes = profile["attributes"] as? [String: Any] ?? [:]
        guard attributes["name"] as? String == name else { continue }
        if content == nil, attributes["profileState"] as? String == "ACTIVE", certificateIds(profile).contains(certificate) {
            content = attributes["profileContent"] as? String
        } else if let id = profile["id"] as? String {
            // Made for a certificate this Mac no longer signs with, expired, or a second copy.
            _ = await call("DELETE", "/v1/profiles/\(id)")
        }
    }
    if content == nil {
        let made = await call("POST", "/v1/profiles", body: [
            "data": [
                "type": "profiles",
                "attributes": ["name": name, "profileType": type],
                "relationships": [
                    "bundleId": ["data": ["type": "bundleIds", "id": bundle]],
                    "certificates": ["data": [["type": "certificates", "id": certificate]]],
                ],
            ],
        ])
        content = ((made["data"] as? [String: Any])?["attributes"] as? [String: Any])?["profileContent"] as? String
    }
    guard let content, let data = Data(base64Encoded: content) else { fail("App Store Connect sent no profile") }
    guard (try? data.write(to: URL(fileURLWithPath: out))) != nil else { fail("could not write \(out)") }
    print("\(name): \(type) for \(identifier)")
case "certificates":
    let json = await get("/v1/certificates?limit=200&fields[certificates]=name,certificateType,serialNumber,expirationDate")
    for item in json["data"] as? [[String: Any]] ?? [] {
        let attributes = item["attributes"] as? [String: Any] ?? [:]
        print("\(attributes["certificateType"] ?? "?") \(attributes["name"] ?? "?") serial \(attributes["serialNumber"] ?? "?") "
            + "until \(attributes["expirationDate"] ?? "?") id \(item["id"] ?? "?")")
    }
case "profiles":
    let json = await get("/v1/profiles?limit=200&include=certificates&fields[profiles]=name,profileType,profileState,expirationDate,certificates"
        + "&fields[certificates]=serialNumber")
    for item in json["data"] as? [[String: Any]] ?? [] {
        let attributes = item["attributes"] as? [String: Any] ?? [:]
        print("\(attributes["name"] ?? "?"): \(attributes["profileType"] ?? "?") \(attributes["profileState"] ?? "?") "
            + "until \(attributes["expirationDate"] ?? "?") id \(item["id"] ?? "?") certificates \(certificateIds(item).joined(separator: ","))")
    }
case "crashes":
    if let id = arguments.dropFirst().first {
        let json = await get("/v1/betaFeedbackCrashSubmissions/\(id)/crashLog")
        let attributes = (json["data"] as? [String: Any])?["attributes"] as? [String: Any] ?? [:]
        print(attributes["logText"] as? String ?? "App Store Connect sent no log for \(id)")
    } else {
        let app = need("ASC_APP_ID")
        let path = "/v1/apps/\(app)/betaFeedbackCrashSubmissions?limit=25"
        // Newest first with each build's number where the API takes it; plainly where it does not.
        var (status, json) = await send("GET", path + "&sort=-createdDate&include=build&fields[builds]=version")
        if status >= 400 { (status, json) = await send("GET", path) }
        if status >= 400 { fail("App Store Connect answered \(status): \(problems(json))") }
        var numbers: [String: String] = [:]
        for item in json["included"] as? [[String: Any]] ?? [] {
            numbers[item["id"] as? String ?? ""] = (item["attributes"] as? [String: Any])?["version"] as? String
        }
        let items = json["data"] as? [[String: Any]] ?? []
        if items.isEmpty { print("no crashes shared from TestFlight") }
        for item in items {
            let attributes = item["attributes"] as? [String: Any] ?? [:]
            let build = ((item["relationships"] as? [String: Any])?["build"] as? [String: Any])?["data"] as? [String: Any]
            let number = numbers[build?["id"] as? String ?? ""] ?? "?"
            let comment = (attributes["comment"] as? String).map { " \"\($0)\"" } ?? ""
            print("\(attributes["createdDate"] ?? "?") \(attributes["deviceModel"] ?? "?") \(attributes["devicePlatform"] ?? "")"
                + " \(attributes["osVersion"] ?? "?") build \(number) id \(item["id"] ?? "?")\(comment)")
        }
    }
default:
    fail("swift asc.swift builds | wait <build> | group <name> | notes <build> [file] | cert <type> <csr> <out> | profile ... "
        + "| certificates | profiles | crashes [id]")
}
