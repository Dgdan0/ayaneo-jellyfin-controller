import Foundation

/// `GET /v1/health`: the hub itself and every configured service, probed
/// concurrently. There is no cache block: this is always live.
public struct HealthResponse: Decodable, Equatable, Sendable {
    public var hub: HubInfo
    public var services: [ServiceHealth]

    public init(hub: HubInfo, services: [ServiceHealth]) {
        self.hub = hub
        self.services = services
    }

    enum CodingKeys: String, CodingKey { case hub, services }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(hub: c.value(.hub, HubInfo()), services: c.value(.services, []))
    }
}

public struct HubInfo: Decodable, Equatable, Sendable {
    public var version: String
    public var uptimeSeconds: Int64
    public var tokenCount: Int

    public init(version: String = "", uptimeSeconds: Int64 = 0, tokenCount: Int = 0) {
        self.version = version
        self.uptimeSeconds = uptimeSeconds
        self.tokenCount = tokenCount
    }

    enum CodingKeys: String, CodingKey { case version, uptimeSeconds, tokenCount }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(version: c.value(.version, ""), uptimeSeconds: c.value(.uptimeSeconds, 0),
                  tokenCount: c.value(.tokenCount, 0))
    }
}

public struct ServiceHealth: Decodable, Equatable, Sendable, Identifiable {
    /// The service id: "jellyfin", "qbittorrent", ...
    public var name: String
    /// "up", "down", "misconfigured" or "disabled".
    public var state: String
    /// The service's own web page on the tailnet, when it has one.
    public var dashboardUrl: String
    public var latencyMs: Int64
    public var version: String
    public var notes: [String]
    public var lastError: String

    public var id: String { name }

    public init(name: String, state: String, dashboardUrl: String = "", latencyMs: Int64 = 0,
                version: String = "", notes: [String] = [], lastError: String = "") {
        self.name = name
        self.state = state
        self.dashboardUrl = dashboardUrl
        self.latencyMs = latencyMs
        self.version = version
        self.notes = notes
        self.lastError = lastError
    }

    enum CodingKeys: String, CodingKey { case name, state, dashboardUrl, latencyMs, version, notes, lastError }

    // checkedAt is RFC 3339 with nanoseconds, which .iso8601 cannot read; the
    // Android client does not read it either.
    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(name: c.value(.name, ""), state: c.value(.state, ""), dashboardUrl: c.value(.dashboardUrl, ""),
                  latencyMs: c.value(.latencyMs, 0), version: c.value(.version, ""),
                  notes: c.value(.notes, []), lastError: c.value(.lastError, ""))
    }
}

/// `GET /v1/users`: the enabled Jellyfin profiles, and which one this device
/// is using (from `X-Jellyfin-User`, or the hub's default).
public struct UsersResponse: Decodable, Equatable, Sendable {
    public var users: [HubUser]
    public var partial: [Partial]
    public var cache: CacheInfo

    public init(users: [HubUser], partial: [Partial] = [], cache: CacheInfo = CacheInfo()) {
        self.users = users
        self.partial = partial
        self.cache = cache
    }

    enum CodingKeys: String, CodingKey { case users, partial, cache }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(users: c.value(.users, []), partial: c.value(.partial, []), cache: c.value(.cache, CacheInfo()))
    }
}

public struct HubUser: Decodable, Equatable, Sendable, Identifiable {
    public var id: String
    public var name: String
    public var selected: Bool

    public init(id: String, name: String, selected: Bool = false) {
        self.id = id
        self.name = name
        self.selected = selected
    }

    enum CodingKeys: String, CodingKey { case id, name, selected }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(id: c.value(.id, ""), name: c.value(.name, ""), selected: c.value(.selected, false))
    }
}
