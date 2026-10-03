import Foundation

/// How each hub service id is spelled on screen ("qBittorrent", "BookKeeprr",
/// never "Qbittorrent") and the order services are listed in. A port of
/// Android's `model/ServiceNames`.
public enum ServiceNames {
    private static let names: [(id: String, display: String)] = [
        ("jellyfin", "Jellyfin"),
        ("jellyseerr", "Jellyseerr"),
        ("prowlarr", "Prowlarr"),
        ("sonarr", "Sonarr"),
        ("radarr", "Radarr"),
        ("readarr", "Readarr"),
        ("qbittorrent", "qBittorrent"),
        ("bazarr", "Bazarr"),
        ("cleanuparr", "Cleanuparr"),
        ("bookkeeprr", "BookKeeprr"),
        ("kavita", "Kavita"),
        ("storyteller", "Storyteller"),
    ]

    public static func display(_ id: String) -> String {
        if let known = names.first(where: { $0.id == id.lowercased() }) { return known.display }
        return id.prefix(1).uppercased() + id.dropFirst()
    }

    /// Where a service sits in a list: playback first, then requests, the
    /// *arrs, the client, books. Unknown ones last.
    public static func rank(_ id: String) -> Int {
        names.firstIndex(where: { $0.id == id.lowercased() }) ?? Int.max
    }
}
