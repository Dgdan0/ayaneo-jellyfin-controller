import Foundation

/// The demo hub's notifications (#36): every service's column as the hub gives
/// it, a current health warning pinned first, history newest first, one
/// service that cannot be reached, and the history lengths the app asks for.
///
/// Some of it is unread from the start: the app's demo seen list is
/// `baseline`, which has taken the older entries as seen, so the page's dots,
/// its count and the bell's can be tested without waiting for news.
enum DemoNotifications {
    /// Times are an offset before this, so one run's answers agree with each other.
    private static let launch = Date()

    private struct Notice {
        var id: String
        var kind: String
        var severity: String
        var title: String
        var detail = ""
        /// How long before the demo started it happened.
        var minutes = 0
        /// A service's own wording for the time, as Bazarr gives it.
        var label = ""
        var active = false
        /// Whether the demo's seen list already has it.
        var seen = true
    }

    private struct Column {
        var service: String
        var state = "up"
        var notices: [Notice]
    }

    /// Sonarr's older history, so a history length of 20 shows fewer than 60.
    private static let olderSonarr: [Notice] = (0..<17).map { index in
        Notice(id: "sonarr:history:\(8_000 + index)", kind: "downloadFolderImported", severity: "success",
               title: "The Bear S3E\(10 - index % 10) · Imported", detail: "WEBDL-1080p · NTb", minutes: 15_000 + index * 300)
    }

    private static let columns: [Column] = [
        Column(service: "sonarr", notices: [
            Notice(id: "sonarr:health:indexer", kind: "health", severity: "warning", title: "IndexerLongTermStatusCheck",
                   detail: "Indexers unavailable due to failures for more than 6 hours: Torrentleech", active: true),
            Notice(id: "sonarr:history:9001", kind: "downloadFolderImported", severity: "success",
                   title: "Severance S2E9 · The After Hours · Imported", detail: "WEBDL-2160p · ATVP", minutes: 8, seen: false),
            Notice(id: "sonarr:history:9000", kind: "downloadFailed", severity: "error",
                   title: "Last Seen S1E5 · Recovered · Download failed",
                   detail: "Found executable file with extension: '.exe'", minutes: 35, seen: false),
            Notice(id: "sonarr:history:8999", kind: "grabbed", severity: "info", title: "Ted Lasso S4E9 · Grabbed",
                   detail: "WEBDL-1080p · FLUX", minutes: 130, seen: false),
            Notice(id: "sonarr:history:8990", kind: "downloadFolderImported", severity: "success",
                   title: "The Mentalist S1E3 · Red Hair · Imported", detail: "Bluray-1080p · SHORTBREHD", minutes: 1_500),
            Notice(id: "sonarr:history:8988", kind: "grabbed", severity: "info", title: "The Mentalist S1E4 · Red Tide · Grabbed",
                   detail: "Bluray-1080p · SHORTBREHD", minutes: 1_520),
            Notice(id: "sonarr:history:8980", kind: "episodeFileRenamed", severity: "info", title: "Dark Matter S2E5 · Renamed",
                   detail: "Dark Matter - S02E05 - Welcome Back.mkv", minutes: 2_900),
            Notice(id: "sonarr:history:8977", kind: "downloadIgnored", severity: "warning",
                   title: "Shogun S2E1 · Download ignored", detail: "Release was already imported", minutes: 3_100),
            Notice(id: "sonarr:history:8970", kind: "downloadFolderImported", severity: "success",
                   title: "Andor S2E12 · Imported", detail: "WEBDL-2160p · FLUX", minutes: 4_300),
            Notice(id: "sonarr:history:8960", kind: "grabbed", severity: "info", title: "Andor S2E12 · Grabbed",
                   detail: "WEBDL-2160p · FLUX", minutes: 4_330),
            Notice(id: "sonarr:history:8950", kind: "episodeFileDeleted", severity: "warning", title: "Bleach S1E2 · File deleted",
                   detail: "Deleted by user", minutes: 6_000),
            Notice(id: "sonarr:history:8940", kind: "downloadFolderImported", severity: "success",
                   title: "The Bear S4E10 · Imported", detail: "WEBDL-1080p · NTb", minutes: 9_000),
            Notice(id: "sonarr:history:8930", kind: "grabbed", severity: "info", title: "The Bear S4E10 · Grabbed",
                   detail: "WEBDL-1080p · NTb", minutes: 9_030),
            Notice(id: "sonarr:history:8920", kind: "downloadFolderImported", severity: "success",
                   title: "Ted Lasso S4E8 · Imported", detail: "WEBDL-1080p · FLUX", minutes: 12_000),
        ] + olderSonarr),
        Column(service: "radarr", notices: [
            Notice(id: "radarr:history:4100", kind: "grabbed", severity: "info", title: "Dune: Part Two · Grabbed",
                   detail: "WEBDL-2160p · FLUX", minutes: 180, seen: false),
            Notice(id: "radarr:history:4090", kind: "downloadFolderImported", severity: "success", title: "Oppenheimer · Imported",
                   detail: "Bluray-2160p · SURCODE", minutes: 2_000),
            Notice(id: "radarr:history:4080", kind: "movieFileDeleted", severity: "warning", title: "Gran Torino · File deleted",
                   detail: "Deleted by user", minutes: 5_000),
            Notice(id: "radarr:history:4070", kind: "downloadFolderImported", severity: "success", title: "Gran Torino · Imported",
                   detail: "Bluray-1080p · AMIABLE", minutes: 5_400),
        ]),
        Column(service: "bazarr", state: "degraded", notices: [
            Notice(id: "bazarr:episode:a1", kind: "subtitle", severity: "success",
                   title: "Severance S2E8 · Sweet Vitriol · Subtitle downloaded", detail: "Hebrew · OpenSubtitles · 96%",
                   minutes: 20, label: "20 minutes ago", seen: false),
            Notice(id: "bazarr:movie:b2", kind: "subtitle", severity: "success", title: "Gran Torino · Subtitle downloaded",
                   detail: "English · Subscene · 99%", minutes: 1_700, label: "a day ago"),
            Notice(id: "bazarr:episode:c3", kind: "subtitle", severity: "warning",
                   title: "Bleach S1E2 · Subtitle deleted", detail: "English", minutes: 6_000, label: "4 days ago"),
        ]),
        Column(service: "bookkeeprr", notices: [
            Notice(id: "bookkeeprr:1", kind: "grabbed", severity: "info", title: "Light Bringer · Grabbed",
                   detail: "EPUB · Anna's Archive", minutes: 50, seen: false),
            Notice(id: "bookkeeprr:2", kind: "imported", severity: "success", title: "Iron Gold · Imported",
                   detail: "EPUB", minutes: 4_000),
        ]),
        Column(service: "kavita", notices: [
            Notice(id: "kavita:1", kind: "scan", severity: "info", title: "Library scan finished",
                   detail: "Comics · 3 new issues", minutes: 700),
        ]),
        Column(service: "storyteller", state: "unavailable", notices: []),
    ]

    /// The demo app's seen list: every service looked at once, and the older
    /// entries taken as seen, so the newest of each are unread.
    static var baseline: NotificationReadSnapshot {
        NotificationReadSnapshot(initializedServices: Set(columns.map(\.service)),
                                 seenIds: Set(columns.flatMap(\.notices).filter(\.seen).map(\.id)))
    }

    static func answer(method: String, path: String, query: String, body: Data?) -> DemoTransport.Answer? {
        guard method == "GET", path == "/v1/notifications" else { return nil }
        let limits = ["sonarr": limit("sonarrLimit", in: query, 60), "radarr": limit("radarrLimit", in: query, 20),
                      "bazarr": limit("bazarrLimit", in: query, 40)]
        let formatter = ISO8601DateFormatter()
        var attention = 0
        let sections: [[String: Any]] = columns.map { column in
            // Health warnings are not history: the limit counts only history.
            let health = column.notices.filter(\.active)
            let history = column.notices.filter { !$0.active }.prefix(limits[column.service] ?? 40)
            if column.state == "unavailable" { attention += 1 }
            attention += health.filter { $0.severity == "warning" || $0.severity == "error" }.count
            return ["service": column.service, "state": column.state,
                    "items": (health + history).map { notice -> [String: Any] in
                        var item: [String: Any] = ["id": notice.id, "service": column.service, "kind": notice.kind,
                                                    "severity": notice.severity, "title": notice.title, "detail": notice.detail,
                                                    "active": notice.active]
                        if !notice.active { item["occurredAt"] = formatter.string(from: launch.addingTimeInterval(Double(-notice.minutes) * 60)) }
                        if !notice.label.isEmpty { item["timeLabel"] = notice.label }
                        return item
                    }]
        }
        let partial: [[String: Any]] = [
            ["service": "bazarr", "reason": "unreachable", "message": "history unavailable"],
            ["service": "storyteller", "reason": "unreachable", "message": "adapter unavailable"],
        ]
        let object: [String: Any] = ["generatedAt": formatter.string(from: Date()), "attentionCount": attention,
                                     "sections": sections, "partial": partial,
                                     "cache": ["hit": false, "ageSeconds": 0, "stale": false]]
        let data = (try? JSONSerialization.data(withJSONObject: object)) ?? Data("{}".utf8)
        return DemoTransport.Answer(200, data: data, type: "application/json")
    }

    /// The hub's own check: a history length from 1 to 100.
    private static func limit(_ name: String, in query: String, _ fallback: Int) -> Int {
        for pair in query.split(separator: "&") {
            let parts = pair.split(separator: "=", maxSplits: 1).map(String.init)
            if parts.count == 2, parts[0] == name, let value = Int(parts[1]), (1...100).contains(value) { return value }
        }
        return fallback
    }
}

extension DemoTransport {
    /// The seen list the demo app starts each run with (`DemoNotifications.baseline`):
    /// the app is in another module, and the demo's fixtures are not public.
    public static var notificationsSeenAtStart: NotificationReadSnapshot { DemoNotifications.baseline }
}
