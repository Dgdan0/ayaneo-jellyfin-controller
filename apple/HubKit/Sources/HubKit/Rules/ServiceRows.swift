import Foundation

/// One card on the Services screen: the hub, the server monitor, or a service.
public struct ServiceRow: Equatable, Sendable, Identifiable {
    public enum Kind: Equatable, Sendable { case hub, monitor, service }

    /// How a state is coloured: the badge palette's green, amber, muted or red.
    public enum Tone: Equatable, Sendable { case available, pending, muted, danger }

    public var id: String
    public var kind: Kind
    public var name: String
    /// "checking", "up", "down", "misconfigured", "disabled" or "overview".
    public var state: String
    public var detail: String
    /// The asset with the service's own logo, or nil for the app's mark.
    public var logo: String?
    public var dashboardURL: String

    public var stateWord: String { ServiceRows.stateWord(state) }
    public var tone: Tone { ServiceRows.tone(state) }
    /// Whether the state is drawn with a dot; "Open" on the monitor is not a state.
    public var showsDot: Bool { state != "overview" }
    /// Jellyfin and the reading servers can be asked to rescan their library.
    public var canScan: Bool { kind == .service && ServiceRows.scannable.contains(id) && state == "up" }
}

/// What the Services screen shows, from `/v1/health`. The rules of Android's
/// `screens/manage/ManageScreen`: the hub first, then the server monitor, then
/// every service in `ServiceNames` order.
public enum ServiceRows {
    static let scannable: Set<String> = ["jellyfin", "kavita", "storyteller"]
    static let logos: Set<String> = [
        "jellyfin", "jellyseerr", "prowlarr", "sonarr", "radarr", "readarr", "bazarr", "cleanuparr", "qbittorrent",
    ]

    public static func rows(health: HealthResponse?, address: String) -> [ServiceRow] {
        var rows = [hubRow(health?.hub, address: address, state: health == nil ? "checking" : "up"), monitorRow]
        for service in (health?.services ?? []).sorted(by: { ServiceNames.rank($0.name) < ServiceNames.rank($1.name) }) {
            rows.append(row(service))
        }
        return rows
    }

    /// The rows when health could not be read: the hub row stays, so the
    /// connection can still be edited, and says it is unavailable.
    public static func failedRows(address: String) -> [ServiceRow] {
        [hubRow(nil, address: address, state: "down"), monitorRow]
    }

    public static func hubRow(_ hub: HubInfo?, address: String, state: String) -> ServiceRow {
        var parts = [address.isEmpty ? "No address configured" : address]
        if let hub {
            if !hub.version.isEmpty { parts.append(version(hub.version)) }
            // Android writes "up " with nothing after it when the uptime is unknown.
            if hub.uptimeSeconds > 0 { parts.append("up " + Fmt.uptime(hub.uptimeSeconds)) }
            parts.append("\(hub.tokenCount) access token" + (hub.tokenCount == 1 ? "" : "s"))
        }
        return ServiceRow(id: "hub", kind: .hub, name: "Ayaneo Hub", state: state,
                          detail: parts.joined(separator: " · "), logo: nil, dashboardURL: "")
    }

    public static let monitorRow = ServiceRow(
        id: "monitor", kind: .monitor, name: "Server monitor", state: "overview",
        detail: "CPU, memory, disk space, containers and current playback", logo: nil, dashboardURL: "")

    public static func row(_ service: ServiceHealth) -> ServiceRow {
        var parts: [String] = []
        if !service.version.isEmpty { parts.append(version(service.version)) }
        if service.latencyMs > 0 { parts.append("\(service.latencyMs) ms") }
        if !service.lastError.isEmpty { parts.append(service.lastError) }
        parts += service.notes.filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
        let id = service.name.lowercased()
        return ServiceRow(
            id: id, kind: .service, name: ServiceNames.display(service.name), state: service.state,
            detail: parts.isEmpty ? "No additional information" : parts.joined(separator: " · "),
            logo: logos.contains(id) ? "logo_" + id : nil, dashboardURL: service.dashboardUrl)
    }

    /// The hub strips qBittorrent's own "v5.0.4" to "5.0.4"; this keeps a "v"
    /// that ever slips through from being doubled.
    static func version(_ value: String) -> String {
        value.lowercased().hasPrefix("v") ? value : "v" + value
    }

    public static func stateWord(_ state: String) -> String {
        switch state {
        case "overview": "Open"
        case "up": "Running"
        case "checking": "Checking"
        case "disabled": "Disabled"
        case "misconfigured": "Needs setup"
        case "down": "Unavailable"
        case "": "Unknown"
        default: state.prefix(1).uppercased() + state.dropFirst()
        }
    }

    public static func tone(_ state: String) -> ServiceRow.Tone {
        switch state {
        case "up": .available
        case "misconfigured": .pending
        case "disabled", "overview", "checking": .muted
        default: .danger
        }
    }

    /// The line under the heading once health has loaded.
    public static func summary(_ rows: [ServiceRow]) -> StatusMessage {
        let problems = rows.filter { $0.state == "down" || $0.state == "misconfigured" }.count
        if problems == 0 {
            return StatusMessage("All \(rows.filter { $0.state == "up" }.count) running")
        }
        return StatusMessage("\(problems) service" + (problems == 1 ? " needs" : "s need") + " attention", tone: .warning)
    }
}
