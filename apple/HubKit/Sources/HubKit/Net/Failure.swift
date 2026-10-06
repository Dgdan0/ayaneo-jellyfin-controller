import Foundation

/// Why a call failed, in terms the UI can act on. Mirrors Android's `FailureKind`.
///
/// The distinction that matters most is `.unauthorized` versus everything else:
/// it is the only one where retrying is pointless and the fix is a trip to the
/// hub connection screen.
public enum FailureKind: String, CaseIterable, Sendable {
    case noNetwork
    case timeout
    case unauthorized
    /// The token works but lacks the scope for this action.
    case forbidden
    /// The hub has banned this source after failed sign-ins; waiting is the only fix.
    case banned
    case rateLimited
    case notFound
    case upstreamDown
    case server
    case badResponse
    case cancelled
    case unknown

    public var isRetryable: Bool {
        switch self {
        case .noNetwork, .timeout, .server, .upstreamDown, .rateLimited: true
        default: false
        }
    }

    /// What to put on screen. Short, because it goes in a one-line status.
    public var message: String {
        switch self {
        case .noNetwork: "Can't reach the hub"
        case .timeout: "The hub took too long"
        case .unauthorized: "The hub rejected this device — check the token"
        case .forbidden: "This device isn't allowed to do that"
        case .banned: "The hub is refusing this device after failed sign-ins"
        case .rateLimited: "Too many requests — slow down"
        case .notFound: "Not found"
        case .upstreamDown: "A service behind the hub is down"
        case .server: "The hub hit an error"
        case .badResponse: "The hub sent something unexpected"
        case .cancelled: "Cancelled"
        case .unknown: "Something went wrong"
        }
    }

    /// Classification of an HTTP status. 429 is rate limiting even when it is
    /// the hub's ban answer: treating it as unauthorized would send the user to
    /// fix a token that is perfectly correct.
    public static func of(status: Int) -> FailureKind {
        switch status {
        case 401: .unauthorized
        // The hub answers 403 only for a missing scope; the token is fine.
        case 403: .forbidden
        case 404: .notFound
        case 429: .rateLimited
        case 502, 503, 504: .upstreamDown
        case 500...599: .server
        case 400...499: .badResponse
        default: .unknown
        }
    }

    /// Classification of a transport error. A decode failure is a bad response,
    /// not a network fault: one is "the hub changed shape", the other is "the
    /// Wi-Fi dropped".
    public static func of(error: any Error) -> FailureKind {
        if error is DecodingError { return .badResponse }
        if error is CancellationError { return .cancelled }
        guard let urlError = error as? URLError else { return .unknown }
        switch urlError.code {
        case .timedOut: return .timeout
        case .cancelled: return .cancelled
        case .cannotFindHost, .cannotConnectToHost, .notConnectedToInternet,
             .networkConnectionLost, .dnsLookupFailed, .internationalRoamingOff,
             .dataNotAllowed, .cannotLoadFromNetwork:
            return .noNetwork
        case .secureConnectionFailed, .serverCertificateHasBadDate, .serverCertificateUntrusted,
             .serverCertificateHasUnknownRoot, .serverCertificateNotYetValid,
             .clientCertificateRejected, .clientCertificateRequired,
             .badServerResponse, .cannotParseResponse, .cannotDecodeContentData,
             .cannotDecodeRawData:
            return .badResponse
        default: return .unknown
        }
    }
}

/// A failed call: what kind, and the sentence to show. The hub's own wording
/// wins when it sent one, because it is more specific than anything the client
/// could say.
public struct HubFailure: Error, Equatable, Sendable {
    public let kind: FailureKind
    public let message: String
    public let status: Int?
    /// The hub's own name for it (`grant_expired`, `offline_preparing`), when it sent one.
    public let code: String
    /// What narrows the code (`queued`, `no_ffmpeg`, `source_missing`), for the app to branch on.
    public let reason: String
    /// Whether the hub said trying again can help; nil when it did not say.
    public let retryable: Bool?
    /// How long the hub asked to wait (`Retry-After`, or the body's `retryAfterSeconds`).
    public let retryAfterSeconds: Int64?

    public init(_ kind: FailureKind, message: String? = nil, status: Int? = nil, code: String = "", reason: String = "",
                retryable: Bool? = nil, retryAfterSeconds: Int64? = nil) {
        self.kind = kind
        let trimmed = message?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        self.message = trimmed.isEmpty ? kind.message : trimmed
        self.status = status
        self.code = code
        self.reason = reason
        self.retryable = retryable
        self.retryAfterSeconds = retryAfterSeconds
    }

    /// A hub answer that was not a success, read the way the client reads its
    /// own: the kind from the status, and the hub's sentence, code and reason
    /// from its error body (`{"error":{"code","reason","message","retryable"}}`).
    /// For a transfer that went around the client, such as a download in the
    /// background session, which is handed the body as a file.
    public static func answer(status: Int, body: Data, retryAfter: String? = nil) -> HubFailure {
        let detail = (try? JSONDecoder().decode(HubErrorBody.self, from: body))?.error
        let header = retryAfter.flatMap { Int64($0.trimmingCharacters(in: .whitespaces)) }
        return HubFailure(.of(status: status), message: detail?.message, status: status, code: detail?.code ?? "",
                          reason: detail?.reason ?? "", retryable: detail?.retryable,
                          retryAfterSeconds: header ?? detail?.retryAfterSeconds)
    }
}
