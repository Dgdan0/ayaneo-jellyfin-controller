import Foundation

/// What the Activity dashboard says, decided away from the views (#29;
/// Android's `screens/downloads/ActivityDashboard`, held to its tests).
///
/// The page answers "is anything wrong, what is moving, what is coming, and
/// is every service up" in one glance, so each card is a short, ordered
/// selection from a larger feed: all the transfers, the calendar and the
/// services are one press away.
public enum ActivityDashboard {
    /// "1 transfer needs attention", "3 transfers need attention".
    public static func needAttention(_ count: Int) -> String {
        count == 1 ? "1 transfer needs attention" : "\(count) transfers need attention"
    }

    /// The line under the heading: "Nothing downloading · 1 thing needs
    /// attention · all 11 services up". A part not yet loaded says nothing
    /// rather than a guess.
    public static func headline(_ activity: ActivityResponse?, attention: Int, health: HealthResponse?) -> String {
        var parts: [String] = []
        if let activity {
            let moving = activity.items.filter { $0.stage == Stages.downloading && !$0.isBroken }.count
            parts.append(moving == 0 ? "Nothing downloading" : "\(moving) downloading")
        }
        if attention > 0 {
            parts.append(attention == 1 ? "1 thing needs attention" : "\(attention) things need attention")
        }
        if let health {
            let shown = health.services.filter { $0.state != "disabled" }
            let notUp = shown.filter { $0.state != "up" }.count
            switch notUp {
            case 0: parts.append("all \(shown.count) services up")
            case 1: parts.append("1 service not responding")
            default: parts.append("\(notUp) services not responding")
            }
        }
        return parts.joined(separator: " · ")
    }

    /// Under this share free, a disk is worth a warning.
    public static let lowSpaceFraction = 0.10

    public static func lowSpace(_ disk: HostDisk) -> Bool {
        disk.totalBytes > 0 && Double(disk.availableBytes) / Double(disk.totalBytes) < lowSpaceFraction
    }

    /// The transfers worth a row: moving or waiting, not seeding or finished,
    /// and not broken, which are listed as what needs attention.
    public static func transfers(_ activity: ActivityResponse, limit: Int = 3) -> [ActivityItem] {
        Array(activity.items.filter { !$0.isBroken && moving.contains($0.stage) }.prefix(limit))
    }

    private static let moving: Set<String> = [Stages.downloading, Stages.importing, Stages.queued, Stages.stopped]

    /// "1.0 GB of 2.0 GB · 4m left", or what the transfer is waiting on.
    public static func transferLine(_ item: ActivityItem) -> String {
        var line = ""
        if item.sizeBytes > 0 {
            if item.remainingBytes > 0 && item.remainingBytes < item.sizeBytes {
                line += Fmt.bytes(item.sizeBytes - item.remainingBytes) + " of "
            }
            line += Fmt.bytes(item.sizeBytes)
        }
        let tail: String
        if item.stage == Stages.downloading && item.etaSeconds >= 0 {
            tail = Fmt.eta(item.etaSeconds) + " left"
        } else if item.stage == Stages.downloading {
            tail = Fmt.speed(item.speedBps)
        } else {
            tail = Stages.label(item.stage)
        }
        return line.isEmpty ? tail : line + " · " + tail
    }

    /// One thing that needs a look: a broken transfer (its page explains),
    /// a service that is down, or a disk nearly full.
    public struct Attention: Equatable, Sendable, Identifiable {
        public let id: String
        public let title: String
        public let detail: String
        /// Set for a transfer, whose page explains what is wrong.
        public let transferId: String

        public init(id: String, title: String, detail: String, transferId: String = "") {
            self.id = id
            self.title = title
            self.detail = detail
            self.transferId = transferId
        }
    }

    /// Broken transfers first, then services that are down, then nearly full disks.
    public static func attention(_ activity: ActivityResponse?, health: HealthResponse?, disks: [HostDisk]) -> [Attention] {
        let transfers = (activity?.items ?? []).filter(\.isBroken).map { item in
            Attention(id: "transfer:" + item.id, title: item.headline,
                      detail: [item.diagnosis?.title, item.arr?.problem, item.warnings.first.map(TransferPresentation.warningLabel)]
                          .compactMap { $0 }.first { !$0.trimmingCharacters(in: .whitespaces).isEmpty } ?? Stages.label(item.stage),
                      transferId: item.id)
        }
        let services = (health?.services ?? []).filter { $0.state == "down" || $0.state == "misconfigured" }
            .sorted { ServiceNames.rank($0.name) < ServiceNames.rank($1.name) }
            .map { service in
                let name = ServiceNames.display(service.name)
                return Attention(
                    id: "service:" + service.name,
                    title: service.state == "down" ? "\(name) isn't responding" : "\(name) needs setting up",
                    detail: service.name == "qbittorrent"
                        ? "Nothing can download, and transfers can't be shown, until it's running again."
                        : (service.lastError.trimmingCharacters(in: .whitespaces).isEmpty ? "The hub can't reach it." : service.lastError))
            }
        let full = disks.filter(lowSpace).map { disk in
            Attention(id: "disk:" + disk.name, title: "\(diskName(disk.name)) is nearly full",
                      detail: "\(Fmt.bytes(disk.availableBytes)) free of \(Fmt.bytes(disk.totalBytes)). New downloads may fail.")
        }
        return transfers + services + full
    }

    /// "E:\" is "E:", "/mnt/media/" is "/mnt/media".
    public static func diskName(_ name: String) -> String {
        var trimmed = Substring(name)
        while let last = trimmed.last, last == "\\" || last == "/", trimmed.count > 1 { trimmed = trimmed.dropLast() }
        return String(trimmed)
    }

    /// "10.11.8 · 12 ms" when up; otherwise what is wrong.
    public static func serviceMeta(_ service: ServiceHealth) -> String {
        switch service.state {
        case "up": [shortVersion(service.version), "\(service.latencyMs) ms"].filter { !$0.isEmpty }.joined(separator: " · ")
        case "down": "Not responding"
        case "misconfigured": "Needs setup"
        case "disabled": "Off"
        default: service.state.prefix(1).uppercased() + service.state.dropFirst()
        }
    }

    /// Radarr's "6.4.4.10685" is "6.4.4" and qBittorrent's "v5.0.4" is "5.0.4": a row reads one way.
    public static func shortVersion(_ version: String) -> String {
        let plain = version.hasPrefix("v") ? String(version.dropFirst()) : version
        return plain.split(separator: ".", omittingEmptySubsequences: false).prefix(3).joined(separator: ".")
    }

    /// "All 11 up", or how many are not.
    public static func servicesSummary(_ health: HealthResponse) -> String {
        let shown = health.services.filter { $0.state != "disabled" }
        let notUp = shown.filter { $0.state != "up" }.count
        return notUp == 0 ? "All \(shown.count) up" : "\(notUp) not responding"
    }

    /// A dashboard address naming the media PC's loopback would look for the
    /// service on this device: it cannot be opened from here.
    public static func reachable(_ url: String) -> Bool {
        guard let host = URL(string: url)?.host(percentEncoded: false)?.lowercased(), !host.isEmpty else { return false }
        return host != "localhost" && host != "::1" && host != "[::1]" && !host.hasPrefix("127.")
    }

    // MARK: The agenda

    public struct AgendaEntry: Equatable, Sendable, Identifiable {
        public let id: String
        public let media: MediaRef
        /// "Today · Fri 2 Oct", "Missed · Thu 24 Sep".
        public let heading: String
        /// "S2E6 · 07:00".
        public let line: String
        public let state: ReleaseState
    }

    /// The days the agenda asks `/v1/calendar` for: a fortnight back for
    /// anything missed, a little over two weeks ahead. The hub serves 31 at most.
    public static func agendaRange(now: Date, zone: TimeZone) -> (start: String, end: String) {
        let today = UpcomingPresentation.today(now: now, zone: zone)
        return (UpcomingPresentation.add(days: -14, to: today), UpcomingPresentation.add(days: 17, to: today))
    }

    /// A short agenda around today: anything that aired in the last two weeks
    /// and never arrived, then the next releases from yesterday on, one per
    /// title per day, soonest first.
    public static func agenda(_ items: [CalendarItem], now: Date, zone: TimeZone, limit: Int = 5,
                              missedDays: Int = 14) -> [AgendaEntry] {
        let today = UpcomingPresentation.today(now: now, zone: zone)
        let yesterday = UpcomingPresentation.add(days: -1, to: today)
        let earliest = UpcomingPresentation.add(days: -missedDays, to: today)
        var seen = Set<String>()
        let dated = items.filter { UpcomingPresentation.date($0.date) != nil }
            .sorted { ($0.date, $0.at, $0.season, $0.episode) < ($1.date, $1.at, $1.season, $1.episode) }
            .filter { seen.insert($0.date + ":" + ($0.media.key.isEmpty ? $0.id : $0.media.key)).inserted }
        func state(_ item: CalendarItem) -> ReleaseState { UpcomingPresentation.state(item, now: now, zone: zone) }
        let missed = dated.filter { $0.date < yesterday && $0.date >= earliest && state($0) == .missing }.suffix(2)
        let coming = dated.filter { $0.date >= yesterday }
        let time = DateFormatter()
        time.locale = Locale(identifier: "en_US_POSIX")
        time.timeZone = zone
        time.dateFormat = "HH:mm"
        return (Array(missed) + coming).prefix(limit).map { item in
            let current = state(item)
            let code = EpisodeLabel.code(season: item.season, episode: item.episode)
            let what = item.media.type == "series" && !code.isEmpty ? code : item.releaseType
            let at = instant(item.at).map { time.string(from: $0) } ?? ""
            return AgendaEntry(
                id: item.id.isEmpty ? item.date + ":" + item.media.key : item.id,
                media: item.media,
                heading: heading(item.date, today: today, missed: current == .missing && item.date < yesterday),
                line: [what, at].filter { !$0.isEmpty }.joined(separator: " · "),
                state: current)
        }
    }

    /// "Today · Fri 2 Oct", "Yesterday · Thu 1 Oct", "Missed · Thu 24 Sep", or just "Wed 7 Oct".
    public static func heading(_ day: String, today: String, missed: Bool = false) -> String {
        let label = UpcomingPresentation.dayHeading(day)
        let relative: String
        if missed {
            relative = "Missed"
        } else if day == today {
            relative = "Today"
        } else if day == UpcomingPresentation.add(days: -1, to: today) {
            relative = "Yesterday"
        } else if day == UpcomingPresentation.add(days: 1, to: today) {
            relative = "Tomorrow"
        } else {
            relative = ""
        }
        return relative.isEmpty ? label : relative + " · " + label
    }

    private static func instant(_ text: String) -> Date? {
        guard !text.isEmpty else { return nil }
        if let date = ISO8601DateFormatter().date(from: text) { return date }
        let fractional = ISO8601DateFormatter()
        fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return fractional.date(from: text)
    }
}
