import Foundation

/// A notification for one of this device's downloads that finished or failed
/// (#43; the Pocket's offline alerts). One per download and outcome, so a
/// second look at the same outcome replaces it rather than adding another.
public struct OfflineAlert: Equatable, Sendable {
    public let id: String
    public let rowId: String
    /// The notifications of one batch (a series' episodes) group together.
    public let batchId: String
    public let title: String
    public let body: String
    public let failed: Bool
}

public enum OfflineAlerts {
    /// What each download was when last looked at.
    public static func states(_ rows: [OfflineRow]) -> [String: OfflineState] {
        Dictionary(rows.map { ($0.id, $0.state) }) { first, _ in first }
    }

    /// The downloads that became finished or failed since `before`; nil
    /// before the first look, which alerts nothing (a download finished
    /// before the app opened was not just finished).
    public static func changes(before: [String: OfflineState]?, rows: [OfflineRow]) -> [OfflineAlert] {
        guard let before else { return [] }
        return rows.compactMap { row in
            guard before[row.id] != row.state else { return nil }
            switch row.state {
            case .complete:
                return OfflineAlert(id: "offline:\(row.id):complete", rowId: row.id, batchId: row.batchId,
                                    title: "Downloaded", body: "\(name(row)) is ready to watch offline.", failed: false)
            case .failed:
                let why = row.error.isEmpty ? "Open Downloads to try again." : row.error
                return OfflineAlert(id: "offline:\(row.id):failed", rowId: row.id, batchId: row.batchId,
                                    title: "Download failed", body: "\(name(row)) · \(why)", failed: true)
            default:
                return nil
            }
        }
    }

    /// "Inception", "Bleach · S1E2 · A Second Look".
    public static func name(_ row: OfflineRow) -> String {
        let item = row.manifest.item
        guard !item.seriesId.isEmpty else { return item.title }
        let episode = EpisodeLabel.of(season: item.seasonNumber, episode: item.indexNumber, title: item.title)
        return item.seriesTitle.isEmpty ? episode : "\(item.seriesTitle) · \(episode)"
    }
}
