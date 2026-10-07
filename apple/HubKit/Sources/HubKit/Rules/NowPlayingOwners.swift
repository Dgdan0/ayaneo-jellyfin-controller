/// Which player the lock screen, Control Center, AirPods and the Mac's media
/// keys speak for (#33): of the players with something on them, the one that
/// played last. A video opened over a paused audiobook takes them; when the
/// video closes, the audiobook has them again. One sound at a time is
/// `AudioArbiter`'s; this only says whose title and buttons are shown.
public struct NowPlayingOwners: Sendable, Equatable {
    /// Those with something on them, the one in front last.
    private var holders: [AudioSource] = []

    public init() {}

    /// The player the commands go to and whose title is shown; nil when none has anything on it.
    public var current: AudioSource? { holders.last }

    /// `source` plays, or has just been given something to play: it is in front.
    public mutating func take(_ source: AudioSource) {
        holders.removeAll { $0 == source }
        holders.append(source)
    }

    /// `source` has nothing on it any more: the one behind it, if any, is in front again.
    public mutating func release(_ source: AudioSource) {
        holders.removeAll { $0 == source }
    }

    /// Whether `source` still has something on it.
    public func holds(_ source: AudioSource) -> Bool { holders.contains(source) }
}
