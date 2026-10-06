import Foundation

/// How fast a screen asks again: while something moves, and when nothing does
/// (nil: not at all). Android's `PollCadence`.
public struct PollCadence: Equatable, Sendable {
    public let active: Duration
    public let idle: Duration?

    public init(active: Duration, idle: Duration?) {
        self.active = active
        self.idle = idle
    }

    /// A transfers list: bytes in flight are worth two seconds, a quiet queue ten.
    public static let transfers = PollCadence(active: .seconds(2), idle: .seconds(10))
}

/// When to ask the hub again (Android's `PollSchedule`, held to its tests).
/// A page out of sight asks nothing at all, which is its task ending; fast
/// while something moves and for a few seconds after an action, because a
/// service does not flip its state with its reply; and after failures 5, 15,
/// then 30 seconds, short of the hub's ban, never faster than the page's pace.
public enum PollSchedule {
    /// How long to keep asking fast after a retry or a cancel.
    public static let settle: Duration = .seconds(8)

    public static func next(active: Bool, failures: Int, settling: Bool = false,
                            cadence: PollCadence = .transfers) -> Duration? {
        if failures > 0 {
            let backoff: Duration = .seconds([5, 15, 30][min(failures - 1, 2)])
            return max(backoff, cadence.active)
        }
        return active || settling ? cadence.active : cadence.idle
    }
}
