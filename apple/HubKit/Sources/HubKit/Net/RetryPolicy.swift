import Foundation

/// Whether and when to try again. A port of Android's `net/RetryPolicy`.
///
/// Two rules carry the weight, and both are about not making things worse:
///
/// - **A rejected credential is never retried.** It cannot start working, and
///   on the hub five failures in a minute earn this device a 15-minute ban.
/// - **A non-idempotent call is never retried.** A timeout does not mean it did
///   not happen. Retrying a request submission that actually succeeded leaves a
///   duplicate to clean up by hand.
public enum RetryPolicy {
    public static let maxAttempts = 3

    /// Base delays before jitter. The total stays well inside the call timeout.
    static let backoffMillis: [Int64] = [400, 1_200, 3_000]

    /// - Parameter attempt: 1 for the first failure.
    /// - Returns: how long to wait, or nil to give up.
    public static func delayMillis<R: RandomNumberGenerator>(
        attempt: Int, kind: FailureKind, idempotent: Bool, using random: inout R
    ) -> Int64? {
        guard idempotent, kind.isRetryable, attempt >= 1, attempt < maxAttempts else { return nil }
        let base = backoffMillis[min(attempt - 1, backoffMillis.count - 1)]
        // Jitter so several screens recovering at once do not all hit the hub on
        // the same tick.
        let jitter = Int64.random(in: -base / 4...base / 4, using: &random)
        return max(50, base + jitter)
    }

    public static func delayMillis(attempt: Int, kind: FailureKind, idempotent: Bool) -> Int64? {
        var random = SystemRandomNumberGenerator()
        return delayMillis(attempt: attempt, kind: kind, idempotent: idempotent, using: &random)
    }

    /// The worst-case total spent sleeping, for checking it fits the budget.
    public static var worstCaseDelayMillis: Int64 {
        backoffMillis.prefix(maxAttempts - 1).reduce(0, +) * 5 / 4
    }
}
