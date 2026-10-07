import Foundation

/// What the Notifications page says and shows, decided away from the views
/// (#36; Android's `screens/notifications/NotificationsScreen`, whose rules
/// were inline there and are held here to its wording).
///
/// The hub gives one column per service: Sonarr's, Radarr's and Bazarr's
/// history with their current health warnings, and the reading services'.
/// Each side of the app shows its own three, as the Pocket's two modes do.
public enum NotificationsPresentation {
    public static let mediaServices = ["sonarr", "radarr", "bazarr"]
    public static let bookServices = ["bookkeeprr", "kavita", "storyteller"]

    public static func services(for side: AppSide) -> [String] {
        side == .media ? mediaServices : bookServices
    }

    /// How the other side is named in "2 in Books".
    public static func sideName(_ side: AppSide) -> String {
        side == .media ? "Movies and TV" : "Books"
    }

    /// The column's own line under its name.
    public static func stateLabel(_ state: String) -> String {
        switch state {
        case "up": "Up to date"
        case "degraded": "Partial"
        case "unavailable": "Unavailable"
        case "disabled": "Not configured"
        default: state.prefix(1).uppercased() + state.dropFirst()
        }
    }

    /// What a column with nothing in it says.
    public static func emptyText(_ state: String) -> String {
        switch state {
        case "disabled": "Not configured"
        case "unavailable": "Service unavailable. Refresh tries again."
        case "degraded": "Some activity unavailable"
        default: "No recent activity"
        }
    }

    /// A health warning's title arrives as the check's name, "IndexerLongTermStatusCheck".
    public static func headline(_ notice: ServiceNotice) -> String {
        notice.kind == "health" ? humanizeHealthTitle(notice.title) : notice.title
    }

    /// A space wherever a lower-case letter or digit meets a capital.
    public static func humanizeHealthTitle(_ value: String) -> String {
        var out = ""
        var previous: Character?
        for character in value {
            if let previous, character.isASCII, character.isUppercase, previous.isASCII, previous.isLowercase || previous.isNumber {
                out.append(" ")
            }
            out.append(character)
            previous = character
        }
        return out
    }

    /// "Needs attention now" for a current problem; otherwise when it happened,
    /// as the service worded it, or "12m ago" from its time.
    public static func meta(_ notice: ServiceNotice, now: Date) -> String {
        notice.active ? "Needs attention now" : time(notice, now: now)
    }

    public static func time(_ notice: ServiceNotice, now: Date) -> String {
        if !notice.timeLabel.isEmpty { return notice.timeLabel }
        guard let at = instant(notice.occurredAt) else { return "" }
        let seconds = max(0, Int(now.timeIntervalSince(at)))
        switch seconds {
        case ..<60: return "Just now"
        case ..<3_600: return "\(seconds / 60)m ago"
        case ..<86_400: return "\(seconds / 3_600)h ago"
        case ..<604_800: return "\(seconds / 86_400)d ago"
        default: return "\(seconds / 604_800)w ago"
        }
    }

    private static func instant(_ text: String) -> Date? {
        guard !text.isEmpty else { return nil }
        if let date = ISO8601DateFormatter().date(from: text) { return date }
        let fractional = ISO8601DateFormatter()
        fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return fractional.date(from: text)
    }

    /// The dot's colour: success green, warning amber, error red, and the
    /// accent for plain information.
    public enum Severity: Equatable, Sendable {
        case info, success, warning, error

        public init(_ word: String) {
            switch word {
            case "success": self = .success
            case "warning": self = .warning
            case "error": self = .error
            default: self = .info
            }
        }
    }

    /// One service's column.
    public struct Column: Equatable, Sendable, Identifiable {
        public let service: String
        public let name: String
        public let state: String
        public let stateLabel: String
        public let empty: String
        /// Current health warnings first (pinned), then the history as the hub ordered it.
        public let items: [ServiceNotice]
        public let unread: Int

        public var id: String { service }
        /// What a screen reader says of the unread count.
        public var unreadLabel: String { "\(unread) unread \(name) notifications" }
    }

    /// A service's column from the hub's section (nil: the hub named none, so
    /// it is not configured). A read that could not reach the service keeps
    /// the last good history rather than blanking it; the state line says which
    /// service failed, so keeping it cannot pass for live data.
    public static func column(service: String, section: NotificationSection?, previous: [ServiceNotice],
                              unread: Set<String>) -> Column {
        let state = section?.state ?? "disabled"
        let fresh = section?.items ?? []
        let items = state == "up" || previous.isEmpty || state == "disabled" ? fresh : previous
        // Stable: health warnings keep their order, and so does the history.
        let ordered = items.filter(\.active) + items.filter { !$0.active }
        return Column(service: service, name: ServiceNames.display(service), state: state, stateLabel: stateLabel(state),
                      empty: emptyText(state), items: ordered, unread: ordered.filter { unread.contains($0.id) }.count)
    }

    /// The page's columns for a side, in order.
    public static func columns(side: AppSide, sections: [NotificationSection], previous: [String: [ServiceNotice]],
                               unread: Set<String>) -> [Column] {
        let bySlug = Dictionary(sections.map { ($0.service, $0) }, uniquingKeysWith: { first, _ in first })
        return services(for: side).map { service in
            column(service: service, section: bySlug[service], previous: previous[service] ?? [], unread: unread)
        }
    }

    /// How many unread notifications belong to the services of `side`.
    public static func unread(on side: AppSide, sections: [NotificationSection], unread: Set<String>) -> Int {
        let wanted = Set(services(for: side))
        return sections.filter { wanted.contains($0.service) }.flatMap(\.items).filter { unread.contains($0.id) }.count
    }

    /// The line under the heading. Anything unread is said first, and what is
    /// unread on the other side (a bell counts every service) is named, so the
    /// bell's number is never a mystery.
    public static func summary(_ response: NotificationsResponse, unread: Int, otherSide: AppSide, unreadOnOtherSide: Int) -> String {
        func plural(_ count: Int, _ word: String) -> String { "\(count) \(word)" + (count == 1 ? "" : "s") }
        if response.sections.allSatisfy({ $0.items.isEmpty }) && response.partial.isEmpty {
            return "No recent activity or service warnings."
        }
        if unread > 0 {
            let line = plural(unread, "unread notification")
            return unreadOnOtherSide > 0 ? "\(line) · \(unreadOnOtherSide) in \(sideName(otherSide))" : line
        }
        if unreadOnOtherSide > 0 {
            return "All seen here · \(unreadOnOtherSide) unread in \(sideName(otherSide))"
        }
        if response.attentionCount > 0 {
            return "\(plural(response.attentionCount, "current service issue")) · all seen"
        }
        if !response.partial.isEmpty || response.cache.stale { return "Recent activity" }
        return "Recent activity · all services responding"
    }

    public static func status(_ response: NotificationsResponse, unread: Int, otherSide: AppSide,
                              unreadOnOtherSide: Int) -> StatusMessage {
        StatusText.loaded(summary(response, unread: unread, otherSide: otherSide, unreadOnOtherSide: unreadOnOtherSide),
                          cache: response.cache, unavailable: response.partial.map(\.service))
    }

    /// What Mark all seen answers.
    public static func markAllLine(hadUnread: Bool) -> String {
        hadUnread ? "All notifications marked as seen" : "All notifications are already seen"
    }

    /// A row read aloud: its service, what it says, and when.
    public static func spoken(_ notice: ServiceNotice, unread: Bool, now: Date) -> String {
        let meta = meta(notice, now: now)
        return [unread ? "Unread" : "", ServiceNames.display(notice.service), headline(notice), notice.detail, meta]
            .filter { !$0.isEmpty }.joined(separator: ", ")
    }
}

/// How many recent entries each service's column loads (Settings › Notifications):
/// Android's `NotificationLimits` and `NotificationSettings`. The hub sends
/// that many and a larger one only costs a little more data on each refresh.
public struct NotificationLimits: Equatable, Sendable {
    public var sonarr: Int
    public var radarr: Int
    public var bazarr: Int

    public static let defaultSonarr = 60
    public static let defaultRadarr = 20
    public static let defaultBazarr = 40

    public init(sonarr: Int = defaultSonarr, radarr: Int = defaultRadarr, bazarr: Int = defaultBazarr) {
        self.sonarr = sonarr
        self.radarr = radarr
        self.bazarr = bazarr
    }

    public func limit(for service: String) -> Int? {
        switch service {
        case "sonarr": sonarr
        case "radarr": radarr
        case "bazarr": bazarr
        default: nil
        }
    }
}

public enum NotificationSettings {
    public static let choices = [20, 40, 60, 100]
    /// The services that have a history limit.
    public static let services = ["sonarr", "radarr", "bazarr"]

    private static func key(_ service: String) -> String { "notifications.limit." + service }

    public static func limits(_ defaults: UserDefaults = .standard) -> NotificationLimits {
        func value(_ service: String, _ fallback: Int) -> Int {
            let stored = defaults.integer(forKey: key(service))
            return choices.contains(stored) ? stored : fallback
        }
        return NotificationLimits(sonarr: value("sonarr", NotificationLimits.defaultSonarr),
                                  radarr: value("radarr", NotificationLimits.defaultRadarr),
                                  bazarr: value("bazarr", NotificationLimits.defaultBazarr))
    }

    /// A limit that is not one of the choices, or a service with no history
    /// limit, changes nothing.
    public static func setLimit(_ limit: Int, for service: String, in defaults: UserDefaults = .standard) {
        guard choices.contains(limit), services.contains(service) else { return }
        defaults.set(limit, forKey: key(service))
    }
}

extension HubEndpoints {
    /// Service history and current warnings at the lengths chosen in Settings.
    public static func notifications(_ limits: NotificationLimits) -> HubRequest {
        notifications(sonarr: limits.sonarr, radarr: limits.radarr, bazarr: limits.bazarr)
    }
}

extension PollCadence {
    /// The Notifications page: every 30 seconds while it is shown (Android's
    /// `PollCadence.NOTIFICATIONS`).
    public static let notifications = PollCadence(active: .seconds(30), idle: .seconds(30))
    /// The bell asks once a minute wherever the person is, for as long as the
    /// app is in front (Android's `BADGE`).
    public static let bell = PollCadence(active: .seconds(60), idle: .seconds(60))
    /// The server monitor: every 15 seconds while it is shown.
    public static let monitor = PollCadence(active: .seconds(ServerMonitorPresentation.refreshSeconds),
                                            idle: .seconds(ServerMonitorPresentation.refreshSeconds))
}
