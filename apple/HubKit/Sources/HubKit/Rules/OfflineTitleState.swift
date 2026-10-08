import Foundation

/// The Download button on a film's, an episode's or a series' page (#5;
/// Android's `MediaActionIcon` DOWNLOADED and DOWNLOADING): whether the
/// title is on this device, on its way (with how far), failed, or neither,
/// and its words.
public enum OfflineTitleState: Equatable, Sendable {
    case none
    /// Queued, on the PC, moving, waiting or paused: how far, 0 to 1.
    case coming(Double)
    case failed
    case downloaded

    /// One film's or episode's download, in any state, or none.
    public static func of(_ row: OfflineRow?) -> OfflineTitleState {
        guard let row else { return .none }
        switch row.state {
        case .complete: return .downloaded
        case .failed: return .failed
        default: return .coming(OfflineQueueLabels.fraction(row))
        }
    }

    /// A series, from its episodes' downloads: on its way while any is (how
    /// far, by the bytes of all of them), failed when one failed and none is
    /// coming, downloaded when some are and the rest are not wanted.
    public static func of(series rows: [OfflineRow]) -> OfflineTitleState {
        guard !rows.isEmpty else { return .none }
        let coming = rows.filter { $0.state != .complete && $0.state != .failed }
        if !coming.isEmpty {
            let fractions = coming.map(OfflineQueueLabels.fraction)
            return .coming(fractions.reduce(0, +) / Double(fractions.count))
        }
        if rows.contains(where: { $0.state == .failed }) { return .failed }
        return .downloaded
    }

    /// "Download", "Downloading · 40%", "Download failed", "Downloaded":
    /// the button's name for VoiceOver and the words beside it.
    public var label: String {
        switch self {
        case .none: "Download"
        case .coming(let fraction): "Downloading · \(Int((min(max(fraction, 0), 1) * 100).rounded()))%"
        case .failed: "Download failed"
        case .downloaded: "Downloaded"
        }
    }

    /// "3 episodes on this device", "1 episode on this device", or nil when
    /// none has arrived yet.
    public static func seriesLine(_ rows: [OfflineRow]) -> String? {
        let done = rows.filter { $0.state == .complete }.count
        guard done > 0 else { return nil }
        return done == 1 ? "1 episode on this device" : "\(done) episodes on this device"
    }

    /// "Download Dune: Part Two?" over what happens and the room there is:
    /// a film's size is known only once the PC has planned its MP4.
    public static func confirmTitle(_ title: String) -> String { "Download \(title)?" }

    public static func confirmDetail(free: Int64) -> String {
        "The PC makes an MP4 of it for this device first, then it comes here. \(Fmt.bytes(free)) free on this device."
    }
}
