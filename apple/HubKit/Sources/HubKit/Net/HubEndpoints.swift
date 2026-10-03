import Foundation

/// A request reduced to plain values, so "which path, which query, how is it
/// encoded" is a pure function pinned by tests. Mirrors Android's `HubRequest`.
public struct HubRequest: Equatable, Sendable {
    public enum Method: String, Sendable { case get = "GET", post = "POST", delete = "DELETE" }

    /// The path and query, relative to the hub address ("/v1/health").
    public let path: String
    public let method: Method
    public let body: Data?
    /// The longer budget for slow upstream work (interactive search, scans).
    public let slow: Bool

    public init(_ path: String, method: Method = .get, body: Data? = nil, slow: Bool = false) {
        self.path = path
        self.method = method
        self.body = body
        self.slow = slow
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

    public static func librarySearch(_ query: String, page: Int = 1) -> HubRequest {
        HubRequest("/v1/library/search?q=" + encode(query) + (page > 1 ? "&page=\(page)" : ""))
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

    private static let tmdbImages = "/v1/img/tmdb/"

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
