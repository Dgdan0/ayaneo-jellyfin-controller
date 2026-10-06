import Foundation

extension KeyedDecodingContainer {
    /// A field that may be missing, null or the wrong type, read as `fallback`.
    /// The Android client decodes the same way (every field defaulted, bad
    /// values coerced), so a hub that adds or loosens a field breaks neither.
    func value<T: Decodable>(_ key: Key, _ fallback: T) -> T {
        (try? decodeIfPresent(T.self, forKey: key)) ?? fallback
    }

    func optional<T: Decodable>(_ key: Key) -> T? {
        try? decodeIfPresent(T.self, forKey: key)
    }
}

/// How old a response is, from the hub's cache: "showing results from 4 minutes
/// ago" rather than silently presenting stale data as current.
public struct CacheInfo: Decodable, Equatable, Sendable {
    public var hit: Bool
    public var ageSeconds: Int
    public var stale: Bool
    public var degraded: Bool

    public init(hit: Bool = false, ageSeconds: Int = 0, stale: Bool = false, degraded: Bool = false) {
        self.hit = hit
        self.ageSeconds = ageSeconds
        self.stale = stale
        self.degraded = degraded
    }

    enum CodingKeys: String, CodingKey { case hit, ageSeconds, stale, degraded }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(hit: c.value(.hit, false), ageSeconds: c.value(.ageSeconds, 0),
                  stale: c.value(.stale, false), degraded: c.value(.degraded, false))
    }
}

/// A service that did not answer while the rest of a screen loaded.
public struct Partial: Decodable, Equatable, Sendable {
    public var service: String
    public var reason: String
    public var message: String

    public init(service: String, reason: String = "", message: String = "") {
        self.service = service
        self.reason = reason
        self.message = message
    }

    enum CodingKeys: String, CodingKey { case service, reason, message }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(service: c.value(.service, ""), reason: c.value(.reason, ""), message: c.value(.message, ""))
    }
}

/// The hub's error envelope: `{"error":{"code","service","reason","message","retryable","retryAfterSeconds"},"requestId"}`.
struct HubErrorBody: Decodable {
    struct Detail: Decodable {
        var code: String?
        var service: String?
        /// Narrows a code with several causes, for the app to branch on.
        var reason: String?
        var message: String?
        var retryable: Bool?
        var retryAfterSeconds: Int64?
    }
    var error: Detail?
}
