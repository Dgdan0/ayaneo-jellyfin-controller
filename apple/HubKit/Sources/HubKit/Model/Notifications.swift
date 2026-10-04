import Foundation

/// `GET /v1/notifications`: Sonarr, Radarr and Bazarr history, and the reading
/// services', with their current health warnings. Android's `model/Notifications`.
public struct NotificationsResponse: Decodable, Equatable, Sendable {
    public var generatedAt: String
    /// What needs attention now: services the hub could not reach, and active
    /// warnings and errors. The hub decides it; the Glass bell shows it.
    public var attentionCount: Int
    public var sections: [NotificationSection]
    public var partial: [Partial]
    public var cache: CacheInfo

    public init(generatedAt: String = "", attentionCount: Int = 0, sections: [NotificationSection] = [],
                partial: [Partial] = [], cache: CacheInfo = CacheInfo()) {
        self.generatedAt = generatedAt
        self.attentionCount = attentionCount
        self.sections = sections
        self.partial = partial
        self.cache = cache
    }

    enum CodingKeys: String, CodingKey { case generatedAt, attentionCount, sections, partial, cache }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(generatedAt: c.value(.generatedAt, ""), attentionCount: c.value(.attentionCount, 0),
                  sections: c.value(.sections, []), partial: c.value(.partial, []), cache: c.value(.cache, CacheInfo()))
    }
}

/// One service's column: "up", "degraded", "unavailable" or "disabled".
public struct NotificationSection: Decodable, Equatable, Sendable {
    public var service: String
    public var state: String
    public var items: [ServiceNotice]

    public init(service: String, state: String = "disabled", items: [ServiceNotice] = []) {
        self.service = service
        self.state = state
        self.items = items
    }

    enum CodingKeys: String, CodingKey { case service, state, items }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(service: c.value(.service, ""), state: c.value(.state, "disabled"), items: c.value(.items, []))
    }
}

public struct ServiceNotice: Decodable, Equatable, Sendable, Identifiable {
    /// Stable across reads, for seen state.
    public var id: String
    public var service: String
    public var kind: String
    /// "info", "success", "warning" or "error".
    public var severity: String
    public var title: String
    public var detail: String
    public var occurredAt: String
    public var timeLabel: String
    /// A current health problem, pinned above the history.
    public var active: Bool

    public init(id: String, service: String = "", kind: String = "", severity: String = "info", title: String = "",
                detail: String = "", occurredAt: String = "", timeLabel: String = "", active: Bool = false) {
        self.id = id
        self.service = service
        self.kind = kind
        self.severity = severity
        self.title = title
        self.detail = detail
        self.occurredAt = occurredAt
        self.timeLabel = timeLabel
        self.active = active
    }

    enum CodingKeys: String, CodingKey { case id, service, kind, severity, title, detail, occurredAt, timeLabel, active }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(id: c.value(.id, ""), service: c.value(.service, ""), kind: c.value(.kind, ""),
                  severity: c.value(.severity, "info"), title: c.value(.title, ""), detail: c.value(.detail, ""),
                  occurredAt: c.value(.occurredAt, ""), timeLabel: c.value(.timeLabel, ""), active: c.value(.active, false))
    }
}
