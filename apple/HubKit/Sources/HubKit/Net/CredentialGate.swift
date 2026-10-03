import Foundation

/// What the hub has said about this device's bearer token, shared by every
/// request the app makes. A port of Android's `net/CredentialGate`.
///
/// The hub bans a source after five 401s in a minute for fifteen minutes, and
/// every request during the ban extends it while also refusing the correct
/// token. So one wrong token has to stop *all* traffic after the first 401:
/// screens, artwork and downloads alike.
///
/// Only 401 means the token is wrong. The hub answers 403 for a scope the token
/// lacks, and that token still works for everything else.
///
/// Until a token has been accepted once, the client sends one request at a
/// time (`needsProbe`), so a burst of concurrent poster loads cannot reach the
/// ban threshold before the first answer arrives.
///
/// Plain values in and out, so it is tested without a network.
public struct CredentialGate: Sendable {
    public enum Block: Equatable, Sendable {
        case rejected
        case banned(untilMillis: Int64, nowMillis: Int64)

        public var remainingSeconds: Int64 {
            guard case let .banned(until, now) = self else { return 0 }
            return max(1, (until - now + 999) / 1_000)
        }

        public var message: String {
            switch self {
            case .rejected:
                "This token was rejected — edit it in Services > Ayaneo Hub"
            case .banned:
                "The Hub is refusing this device for \((remainingSeconds + 59) / 60) min after failed sign-ins"
            }
        }

        public var kind: FailureKind {
            switch self {
            case .rejected: .unauthorized
            case .banned: .banned
            }
        }
    }

    /// The ordinary rate limit answers Retry-After: 2; a ban answers the rest of
    /// fifteen minutes. Anything this long is worth waiting out rather than
    /// retrying into.
    public static let banThresholdSeconds: Int64 = 30

    private var rejected = ""
    private var accepted = ""
    private var bannedToken = ""
    private var bannedUntil: Int64 = 0

    public init() {}

    /// Why a request with `token` must not be sent now, or nil to send it.
    public func block(for token: String, nowMillis: Int64) -> Block? {
        if token.isEmpty { return nil }
        if token == rejected { return .rejected }
        // A ban is on the source, not the token, but a newly typed token is the
        // user asking to try again (after restarting the hub, which is how a ban
        // is cleared), so it gets its one attempt.
        if token == bannedToken && nowMillis < bannedUntil {
            return .banned(untilMillis: bannedUntil, nowMillis: nowMillis)
        }
        return nil
    }

    /// True while `token` has never been accepted: send one request at a time.
    public func needsProbe(_ token: String) -> Bool {
        !token.isEmpty && token != accepted
    }

    /// Records what the hub answered to a request that carried `token`.
    public mutating func observe(token: String, status: Int, retryAfterSeconds: Int64?, nowMillis: Int64) {
        if token.isEmpty { return }
        if status == 401 {
            rejected = token
            if accepted == token { accepted = "" }
        } else if status == 429, let retryAfter = retryAfterSeconds, retryAfter >= Self.banThresholdSeconds {
            bannedToken = token
            bannedUntil = nowMillis + retryAfter * 1_000
        } else if (200...399).contains(status) || status == 403 {
            // Both mean the hub verified the token.
            accepted = token
            if rejected == token { rejected = "" }
            if bannedToken == token { bannedUntil = 0 }
        }
    }
}
