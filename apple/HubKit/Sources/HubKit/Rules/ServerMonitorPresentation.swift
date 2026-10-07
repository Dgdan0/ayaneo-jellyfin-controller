import Foundation

/// The colour of a status dot, by the state a service or a container reports:
/// Android's `DashboardParts.stateColor`, which Activity, the server monitor,
/// Services and Notifications all draw their dots with.
public enum DashboardTone {
    public static func of(_ state: String) -> ServiceRow.Tone {
        switch state.lowercased() {
        case "up", "running", "healthy": .available
        case "misconfigured", "restarting", "paused", "created", "degraded": .pending
        case "disabled", "exited": .muted
        default: .danger
        }
    }
}

/// What the server monitor says (#36; Android's `screens/manage/ServerMonitorScreen`,
/// whose rules were inline there): the media PC's CPU, memory and uptime on
/// cards, then its disks, what is playing and its Docker containers.
public enum ServerMonitorPresentation {
    /// It asks again this often while it is shown.
    public static let refreshSeconds = 15

    /// A figure on its own card: a quiet label, the value large, then a line or a bar.
    public struct Figure: Equatable, Sendable, Identifiable {
        public let label: String
        public let value: String
        public let detail: String
        /// How full, 0...1; nil draws no bar.
        public let fraction: Double?
        public let warning: Bool

        public var id: String { label }
        public var spoken: String { [label, value, detail].filter { !$0.isEmpty }.joined(separator: ", ") }
    }

    /// CPU is amber from this share of the processor.
    public static let cpuWarning = 90.0

    public static func figures(_ host: HostSnapshot) -> [Figure] {
        let total = host.memoryTotalBytes
        let used = max(total - host.memoryAvailableBytes, 0)
        let cpu = host.cpuPercent
        return [
            // A share rounds half up, as Android's format does: 14.5 is 15%.
            Figure(label: "CPU", value: cpu.map { "\(Int($0.rounded(.toNearestOrAwayFromZero)))%" } ?? "—",
                   detail: cpu == nil ? "Unavailable" : "Over a short sample", fraction: cpu.map { $0 / 100 },
                   warning: (cpu ?? 0) >= cpuWarning),
            Figure(label: "Memory", value: total > 0 ? Fmt.bytes(used) : "—",
                   detail: total > 0 ? "of \(Fmt.bytes(total))" : "Unavailable",
                   fraction: total > 0 ? Double(used) / Double(total) : nil, warning: false),
            Figure(label: "Up for", value: Fmt.uptime(host.uptimeSeconds).ifEmpty("—"), detail: "Since the PC last started",
                   fraction: nil, warning: false),
        ]
    }

    /// "Windows 11 Pro host · checked 14:03:22 · refreshes every 15 seconds".
    public static func status(_ monitor: ServerMonitor, zone: TimeZone = .current) -> String {
        [monitor.host.os.ifEmpty("Media PC") + " host", checked(monitor.checkedAt, zone: zone).map { "checked " + $0 },
         "refreshes every \(refreshSeconds) seconds"].compactMap { $0 }.joined(separator: " · ")
    }

    /// When a read failed and the figures on the page are an earlier one's.
    public static func failed(_ message: String) -> String {
        "Could not refresh · \(message) · the figures below may be old"
    }

    private static func checked(_ text: String, zone: TimeZone) -> String? {
        guard let date = ISO8601DateFormatter().date(from: text) else { return nil }
        let format = DateFormatter()
        format.locale = Locale(identifier: "en_US_POSIX")
        format.timeZone = zone
        format.dateFormat = "HH:mm:ss"
        return format.string(from: date)
    }

    // MARK: Disks

    public static func diskCount(_ host: HostSnapshot) -> String { "\(host.disks.count) with space the hub can see" }

    /// A disk read aloud, in words as well as colour.
    public static func spoken(_ disk: HostDisk) -> String {
        "\(ActivityDashboard.diskName(disk.name)), \(Fmt.bytes(disk.availableBytes)) free of \(Fmt.bytes(disk.totalBytes))"
            + (ActivityDashboard.lowSpace(disk) ? ", nearly full" : "")
    }

    // MARK: Playing now

    public static let playingTrailing = "for this profile"

    /// What the card says when there is no session to list: the hub's warning,
    /// else that nothing is playing.
    public static func playingNote(_ monitor: ServerMonitor) -> String? {
        if !monitor.sessionWarning.isEmpty { return monitor.sessionWarning }
        return monitor.sessions.isEmpty ? "Nothing is playing" : nil
    }

    /// "Living Room TV · Jellyfin Android TV · Direct play", or "Paused".
    public static func sessionLine(_ session: HostSession) -> String {
        [session.device, session.client, session.paused ? "Paused" : session.method.ifEmpty("Playing")]
            .filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }.joined(separator: " · ")
    }

    // MARK: Containers

    public struct ContainerRow: Equatable, Sendable, Identifiable {
        public let name: String
        /// "Up 4 days (healthy) · fallenbagel/jellyseerr:2.7.3".
        public let line: String
        /// The state its dot shows: a running container that reports itself
        /// unhealthy is degraded.
        public let state: String

        public var id: String { name }
        public var tone: ServiceRow.Tone { DashboardTone.of(state) }
    }

    /// Running ones first, then by name.
    public static func containers(_ values: [HostContainer]) -> [ContainerRow] {
        values.sorted { ($0.state == "running" ? 0 : 1, $0.name) < ($1.state == "running" ? 0 : 1, $1.name) }.map { container in
            let unhealthy = container.status.range(of: "unhealthy", options: .caseInsensitive) != nil
            return ContainerRow(name: container.name,
                                line: [container.status, container.image].filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
                                    .joined(separator: " · "),
                                state: unhealthy ? "degraded" : container.state)
        }
    }

    /// "3 of 4 running"; nothing when Docker lists none.
    public static func containerCount(_ values: [HostContainer]) -> String {
        values.isEmpty ? "" : "\(values.filter { $0.state == "running" }.count) of \(values.count) running"
    }

    /// What the card says when there is no container to list.
    public static func containerNote(_ monitor: ServerMonitor) -> String? {
        if !monitor.dockerWarning.isEmpty { return monitor.dockerWarning }
        return monitor.containers.isEmpty ? "Docker reports no containers" : nil
    }
}

private extension String {
    func ifEmpty(_ fallback: String) -> String { isEmpty ? fallback : self }
}
