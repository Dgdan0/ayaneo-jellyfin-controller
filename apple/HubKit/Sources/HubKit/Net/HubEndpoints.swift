import Foundation

/// A request reduced to plain values, so "which path, which query, how is it
/// encoded" is a pure function pinned by tests. Mirrors Android's `HubRequest`.
public struct HubRequest: Equatable, Sendable {
    public enum Method: String, Sendable { case get = "GET", post = "POST", put = "PUT", delete = "DELETE" }

    /// The path and query, relative to the hub address ("/v1/health").
    public let path: String
    public let method: Method
    public let body: Data?
    /// The longer budget for slow upstream work (interactive search, scans).
    public let slow: Bool
    /// The Jellyfin profile this request is for, when it is not the client's
    /// current one: a playback session belongs to the profile that opened it,
    /// and the hub refuses its events from any other. Empty is the hub's
    /// default profile; nil is whichever profile the client has now.
    public let user: String?

    public init(_ path: String, method: Method = .get, body: Data? = nil, slow: Bool = false, user: String? = nil) {
        self.path = path
        self.method = method
        self.body = body
        self.slow = slow
        self.user = user
    }

    /// GETs are retried; nothing else is (see `RetryPolicy`).
    public var idempotent: Bool { method == .get }
}

/// Every hub path the client knows how to build. A port of Android's
/// `net/HubEndpoints`; the same paths, query rules and encoding.
public enum HubEndpoints {
    public static let health = HubRequest("/v1/health")
    public static let users = HubRequest("/v1/users")
    public static let home = HubRequest("/v1/home")
    public static let library = HubRequest("/v1/library")
    /// The Books side's libraries, Kavita's and Storyteller's, in the profile's order.
    public static let readingLibraries = HubRequest("/v1/reading/libraries")
    public static let monitor = HubRequest("/v1/manage/monitor")

    public static let scanJellyfinLibrary = HubRequest("/v1/manage/jellyfin/scan", method: .post)

    public static func scanReadingLibrary(service: String) -> HubRequest {
        HubRequest("/v1/manage/reading/scan?service=" + encode(service), method: .post)
    }

    /// The defaults (name, ascending, page 1) are left out of the query, as on Android.
    public static func libraryItems(viewId: String, page: Int = 1, sort: String = "name", order: String = "asc") -> HubRequest {
        var query: [String] = []
        if sort != "name" { query.append("sort=" + encode(sort)) }
        if order != "asc" { query.append("order=" + encode(order)) }
        if page > 1 { query.append("page=\(page)") }
        return HubRequest("/v1/library/" + encode(viewId) + "/items" + (query.isEmpty ? "" : "?" + query.joined(separator: "&")))
    }

    /// Keeps a side's libraries in this order for the profile, on every device
    /// (#15). An empty list goes back to A to Z.
    public static func saveLibraryOrder(side: LibrarySide, ids: [String]) -> HubRequest {
        HubRequest("/v1/library/order", method: .put, body: json(LibraryOrderBody(side: side.rawValue, ids: ids)))
    }

    public static func libraryItem(_ itemId: String) -> HubRequest {
        HubRequest("/v1/library/items/" + encode(itemId))
    }

    public static func librarySeasons(seriesId: String) -> HubRequest {
        HubRequest("/v1/library/series/" + encode(seriesId) + "/seasons")
    }

    public static func libraryEpisodes(seriesId: String, seasonId: String, page: Int = 1) -> HubRequest {
        HubRequest("/v1/library/series/" + encode(seriesId) + "/episodes?seasonId=" + encode(seasonId)
            + (page > 1 ? "&page=\(page)" : ""))
    }

    public static func seriesPlayTarget(seriesId: String) -> HubRequest {
        HubRequest("/v1/library/series/" + encode(seriesId) + "/play-target")
    }

    /// `viewId`, when set, keeps the search inside that library (#14); a hub
    /// from before it ignores it and searches everything.
    public static func librarySearch(_ query: String, page: Int = 1, viewId: String = "") -> HubRequest {
        HubRequest("/v1/library/search?q=" + encode(query) + (page > 1 ? "&page=\(page)" : "")
            + (viewId.isEmpty ? "" : "&viewId=" + encode(viewId)))
    }

    public static func libraryFavorites(page: Int = 1) -> HubRequest {
        HubRequest("/v1/library/favorites" + (page > 1 ? "?page=\(page)" : ""))
    }

    public static func librarySimilar(itemId: String) -> HubRequest {
        HubRequest("/v1/library/items/" + encode(itemId) + "/similar")
    }

    public static func libraryState(itemId: String, body: Data) -> HubRequest {
        HubRequest("/v1/library/items/" + encode(itemId) + "/state", method: .post, body: body)
    }

    // MARK: Discover, search and requests (#17)

    /// Every row's first page in one call.
    public static let discover = HubRequest("/v1/discover")

    /// The next page of one row; the hub refuses pages past 500.
    public static func discoverRow(_ rowId: String, page: Int) -> HubRequest {
        HubRequest("/v1/discover/" + encode(rowId) + "?page=\(max(1, page))")
    }

    public static func search(_ query: String, page: Int = 1) -> HubRequest {
        HubRequest("/v1/search?q=" + encode(query) + (page > 1 ? "&page=\(page)" : ""))
    }

    /// A title by its media key ("tmdb:movie:438631").
    public static func mediaDetail(key: String) -> HubRequest {
        HubRequest("/v1/media/" + encode(key))
    }

    /// A performer's films and series, newest first unless by popularity.
    public static func person(id: Int, byPopularity: Bool = false) -> HubRequest {
        HubRequest("/v1/person/\(id)" + (byPopularity ? "?sort=popularity" : ""))
    }

    public static func requestOptions(key: String) -> HubRequest {
        HubRequest("/v1/requests/options?key=" + encode(key))
    }

    /// Never retried: a timeout does not mean it did not happen.
    public static func createRequest(_ body: CreateRequestBody) -> HubRequest {
        HubRequest("/v1/requests", method: .post, body: json(body))
    }

    /// A season's aired episodes, for a release search of one of them.
    public static func releaseTargets(key: String, season: Int) -> HubRequest {
        HubRequest("/v1/media/" + encode(key) + "/release-targets?season=\(max(0, season))")
    }

    /// An interactive search of every indexer: a film, a series' season (0 is
    /// Specials, so a series always names one) or one of its episodes. Slow.
    public static func releases(key: String, season: Int? = nil, episode: Int? = nil) -> HubRequest {
        var query: [String] = []
        if let season { query.append("season=\(max(0, season))") }
        if let episode, episode > 0 { query.append("episode=\(episode)") }
        return HubRequest("/v1/media/" + encode(key) + "/releases" + (query.isEmpty ? "" : "?" + query.joined(separator: "&")),
                          slow: true)
    }

    /// Sends a release to the download client, with the season and episode of
    /// the search that found it. Slow: the hub may search again. Never retried.
    public static func grab(key: String, _ body: GrabBody) -> HubRequest {
        HubRequest("/v1/media/" + encode(key) + "/grab", method: .post, body: json(body), slow: true)
    }

    /// Releases from `start` to `end` (exclusive), "YYYY-MM-DD", in `timezone`.
    public static func calendar(start: String, end: String, timezone: String) -> HubRequest {
        HubRequest("/v1/calendar?start=" + encode(start) + "&end=" + encode(end) + "&timezone=" + encode(timezone))
    }

    // MARK: Playback. Every call after `preparePlayback` names the profile that
    // opened the session (`user`), which the hub checks.

    public static func preparePlayback(itemId: String, body: PlaybackPrepareBody, user: String) -> HubRequest {
        HubRequest("/v1/playback/items/" + encode(itemId) + "/prepare", method: .post, body: json(body), user: user)
    }

    public static func selectPlayback(sessionId: String, body: PlaybackSelectBody, user: String) -> HubRequest {
        HubRequest(session(sessionId) + "/select", method: .post, body: json(body), user: user)
    }

    /// Addresses for the session's stream that need no token (`PlaybackGrant`).
    public static func playbackGrant(sessionId: String, user: String) -> HubRequest {
        HubRequest(session(sessionId) + "/cast-grant", method: .post, user: user)
    }

    public static func playbackEvent(sessionId: String, body: PlaybackEventBody, user: String) -> HubRequest {
        HubRequest(session(sessionId) + "/events", method: .post, body: json(body), user: user)
    }

    /// Ends the session: the hub saves a position it was never told was
    /// stopped, closes Jellyfin's transcode and revokes the session's grants.
    public static func closePlayback(sessionId: String, user: String) -> HubRequest {
        HubRequest(session(sessionId), method: .delete, user: user)
    }

    /// A file the session serves, such as a subtitle track's `externalUrl`.
    public static func playbackFile(_ hubPath: String, user: String) -> HubRequest {
        HubRequest(hubPath, user: user)
    }

    /// A frame of the session's video near `positionMillis`, extracted by the
    /// hub on a five-second grid: a chapter's picture.
    public static func playbackPreview(_ previewUrl: String, positionMillis: Int64) -> String {
        previewUrl + (previewUrl.contains("?") ? "&" : "?") + "positionMillis=\(max(0, positionMillis))"
    }

    private static func session(_ id: String) -> String { "/v1/playback/sessions/" + encode(id) }

    /// A request body: keys sorted, so a body is the same bytes every time.
    static func json<T: Encodable>(_ value: T) -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        return (try? encoder.encode(value)) ?? Data("{}".utf8)
    }

    /// Service history and current warnings, with Android's default history
    /// lengths, so both apps share the hub's cached answer.
    public static func notifications(sonarr: Int = 60, radarr: Int = 20, bazarr: Int = 40) -> HubRequest {
        HubRequest("/v1/notifications?sonarrLimit=\(sonarr)&radarrLimit=\(radarr)&bazarrLimit=\(bazarr)")
    }

    private static let tmdbImages = "/v1/img/tmdb/"

    /// The Glass colours of up to 60 hub image paths, each sent exactly as a
    /// screen shows it (issue #10). Mirrors Android's `HubEndpoints.artworkColors`.
    public static func artworkColors(_ sources: [String]) -> HubRequest {
        HubRequest("/v1/img/colors?" + sources.map { "src=" + encode($0) }.joined(separator: "&"))
    }

    /// A hub image path asking for `width` pixels, which the hub snaps to its
    /// nearest size. Unsized, a Jellyfin image comes back 360 wide: right for a
    /// poster, soft across a whole screen.
    public static func sized(_ hubPath: String, width: Int) -> String {
        // A TMDB image names its size in the path; w1280 is the largest worth
        // fetching (original runs to 4K and several megabytes).
        if hubPath.hasPrefix(tmdbImages) {
            let rest = hubPath.dropFirst(tmdbImages.count)
            guard let slash = rest.firstIndex(of: "/"), slash > rest.startIndex else { return hubPath }
            return tmdbImages + (width > 780 ? "w1280" : "w780") + rest[slash...]
        }
        if hubPath.trimmingCharacters(in: .whitespaces).isEmpty || hubPath.contains("w=") { return hubPath }
        return hubPath + (hubPath.contains("?") ? "&" : "?") + "w=\(width)"
    }

    private static let jellyfinImages = "/v1/img/jf/"
    /// The hub's smallest width bucket for a Jellyfin image.
    private static let smallestJellyfinWidth = 180

    /// The smallest picture the hub serves of `hubPath`, for artwork drawn tiny
    /// or blurred: the Glass page decodes 64 pixels of it. A TMDB image at w92
    /// rather than the w780 `sized` would ask for, a Jellyfin one at w=180,
    /// replacing any width already asked for. Anything else, a reading cover,
    /// comes in the one size its server has. Android's `HubEndpoints.smallest`.
    public static func smallest(_ hubPath: String) -> String {
        if hubPath.hasPrefix(tmdbImages) {
            let rest = hubPath.dropFirst(tmdbImages.count)
            guard let slash = rest.firstIndex(of: "/"), slash > rest.startIndex else { return hubPath }
            return tmdbImages + "w92" + rest[slash...]
        }
        if hubPath.hasPrefix(jellyfinImages) {
            let parts = hubPath.split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false)
            let query = parts.count > 1 ? parts[1].split(separator: "&").map(String.init) : []
            let kept = query.filter { !$0.isEmpty && !$0.hasPrefix("w=") }
            return String(parts[0]) + "?" + (kept + ["w=\(smallestJellyfinWidth)"]).joined(separator: "&")
        }
        return hubPath
    }

    /// What a new device's Address field starts with: the media PC on the
    /// tailnet, through Tailscale Serve. Still editable; a device only pastes
    /// its own token.
    public static let suggestedAddress = "https://ayaneo-media-pc.tail737e96.ts.net"

    /// Trims a trailing slash from the base and guarantees exactly one between
    /// the two halves.
    public static func join(_ base: String, _ path: String) -> String {
        var trimmed = Substring(base)
        while trimmed.hasSuffix("/") { trimmed = trimmed.dropLast() }
        return path.hasPrefix("/") ? trimmed + path : trimmed + "/" + path
    }

    /// Normalises what a person types into an address.
    ///
    /// Android assumes `http://` for a bare host, from when the hub was reached
    /// over a loopback forward; its validation then refuses that for any remote
    /// host. Here a bare host gets `https://` (the Tailscale and DuckDNS routes
    /// are both HTTPS) and only loopback gets `http://`.
    public static func normaliseBase(_ input: String) -> String {
        var trimmed = Substring(input.trimmingCharacters(in: .whitespacesAndNewlines))
        while trimmed.hasSuffix("/") { trimmed = trimmed.dropLast() }
        if trimmed.isEmpty { return "" }
        let lower = trimmed.lowercased()
        if lower.hasPrefix("http://") || lower.hasPrefix("https://") { return String(trimmed) }
        if lower.hasPrefix("127.0.0.1") || lower.hasPrefix("localhost") { return "http://" + trimmed }
        return "https://" + trimmed
    }

    /// Percent-encoding for a path segment or query value: everything except
    /// ASCII letters, digits and `-_.~`. A space is `%20`, never `+`
    /// (Jellyseerr answers a bare 400 to `+`).
    public static func encode(_ value: String) -> String {
        let hex = Array("0123456789ABCDEF")
        var out = ""
        for byte in value.utf8 {
            switch byte {
            case UInt8(ascii: "a")...UInt8(ascii: "z"), UInt8(ascii: "A")...UInt8(ascii: "Z"),
                 UInt8(ascii: "0")...UInt8(ascii: "9"),
                 UInt8(ascii: "-"), UInt8(ascii: "_"), UInt8(ascii: "."), UInt8(ascii: "~"):
                out.append(Character(UnicodeScalar(byte)))
            default:
                out.append("%")
                out.append(hex[Int(byte >> 4)])
                out.append(hex[Int(byte & 0xF)])
            }
        }
        return out
    }
}

/// Checks on the connection form before anything is sent. A port of Android's
/// `state/HubConnectionValidation`.
public enum HubConnectionValidation {
    /// The typed token, trimmed, or the stored one when the field was left
    /// blank: the stored token is never put back into the editable field.
    public static func effectiveToken(stored: String, entered: String) -> String {
        let typed = entered.trimmingCharacters(in: .whitespacesAndNewlines)
        return typed.isEmpty ? stored.trimmingCharacters(in: .whitespacesAndNewlines) : typed
    }

    /// Why this address and token cannot be used, or nil when they can.
    public static func error(normalizedAddress: String, token: String) -> String? {
        let lower = normalizedAddress.lowercased()
        let allowed = (lower.hasPrefix("https://") && lower.count > "https://".count)
            || lower.hasPrefix("http://127.0.0.1") || lower.hasPrefix("http://localhost")
        if !allowed { return "Enter a complete HTTPS address" }
        let trimmed = token.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return "Paste the Hub access token" }
        if trimmed.count < 32 { return "The Hub access token is incomplete" }
        return nil
    }
}
