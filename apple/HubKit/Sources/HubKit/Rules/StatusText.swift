import Foundation

public enum StatusTone: Sendable { case normal, warning, error }

/// A screen's status line: the words, their tone, and whether the screen should
/// offer to try again.
public struct StatusMessage: Equatable, Sendable {
    public var text: String
    public var tone: StatusTone
    /// Android appends "Select retries" for its gamepad; here the screen shows a
    /// Try again button instead, under the same rule.
    public var offersRetry: Bool

    public init(_ text: String, tone: StatusTone = .normal, offersRetry: Bool = false) {
        self.text = text
        self.tone = tone
        self.offersRetry = offersRetry
    }
}

/// What a screen's status line says, and in which tone: the only place that
/// decides it. A port of Android's `state/StatusText`, held to its tests.
///
/// Screens pass what happened; the wording follows from it. Retrying is never
/// offered for a rejected token, where it only moves the device toward the
/// hub's ban.
public enum StatusText {
    private static let separator = " · "

    public static func loading(_ what: String, refreshing: Bool) -> StatusMessage {
        StatusMessage(refreshing ? "Refreshing \(what)…" : "Loading \(what)…")
    }

    /// A loaded screen's line: its summary plus the caveat, if any.
    public static func loaded(_ summary: String, cache: CacheInfo = CacheInfo(), unavailable: [String] = []) -> StatusMessage {
        loaded(summary, caveat: caveat(cache, unavailable: unavailable))
    }

    public static func loaded(_ summary: String, caveat: StatusMessage) -> StatusMessage {
        StatusMessage([summary, caveat.text].filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
            .joined(separator: separator), tone: caveat.tone)
    }

    /// Only what the reader needs to be warned about; empty for fresh, complete
    /// data. Services that did not answer are named, so a partial result never
    /// reads as the whole.
    public static func caveat(_ cache: CacheInfo, unavailable: [String] = []) -> StatusMessage {
        var parts: [String] = []
        // Fresh data, a cache hit inside its TTL included, needs no caveat: the
        // TTLs exist precisely so that it is still true.
        if cache.degraded {
            parts.append("couldn't refresh, showing data from \(age(cache.ageSeconds))")
        } else if cache.stale {
            parts.append("updated \(age(cache.ageSeconds))")
        }
        var services: [String] = []
        for service in unavailable where !service.trimmingCharacters(in: .whitespaces).isEmpty && !services.contains(service) {
            services.append(service)
        }
        if !services.isEmpty {
            parts.append(services.map(ServiceNames.display).joined(separator: ", ") + " unavailable")
        }
        let warning = cache.degraded || !services.isEmpty
        return StatusMessage(parts.joined(separator: separator), tone: warning ? .warning : .normal)
    }

    /// - Parameters:
    ///   - message: the hub's own wording, more specific than anything the app could say.
    ///   - hasData: whether the screen is still showing an earlier result.
    ///   - canRetry: false on a screen with no retry action.
    public static func failed(_ message: String, kind: FailureKind, hasData: Bool, canRetry: Bool = true) -> StatusMessage {
        var parts = [message]
        if hasData { parts.append("showing earlier results") }
        return StatusMessage(parts.joined(separator: separator), tone: .error,
                             offersRetry: canRetry && kind.isRetryable)
    }

    public static func age(_ seconds: Int) -> String {
        switch seconds {
        case ..<60: "moments ago"
        case ..<3_600: "\(seconds / 60) min ago"
        case ..<86_400: "\(seconds / 3_600) h ago"
        default: "\(seconds / 86_400) d ago"
        }
    }
}
