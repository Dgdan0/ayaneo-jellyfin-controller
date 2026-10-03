import Foundation
import Testing
@testable import HubKit

/// A seeded generator, so jitter is repeatable (Kotlin's `Random(seed)`).
struct SplitMix64: RandomNumberGenerator {
    var state: UInt64
    init(_ seed: UInt64) { state = seed }
    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
}

/// The cases of Android's `RetryPolicyTest`.
struct RetryPolicyTests {
    func delay(_ attempt: Int, _ kind: FailureKind, _ idempotent: Bool = true, seed: UInt64 = 0) -> Int64? {
        var random = SplitMix64(seed)
        return RetryPolicy.delayMillis(attempt: attempt, kind: kind, idempotent: idempotent, using: &random)
    }

    @Test func aTransientFailureIsRetried() {
        #expect(delay(1, .timeout) != nil)
    }

    @Test func aRejectedCredentialIsNeverRetried() {
        // It cannot start working, and hammering it earns a 15-minute ban.
        #expect(delay(1, .unauthorized) == nil)
    }

    @Test func aNonIdempotentCallIsNeverRetried() {
        // A timeout does not mean it did not happen.
        #expect(delay(1, .timeout, false) == nil)
        #expect(delay(1, .server, false) == nil)
    }

    @Test func a4xxIsNeverRetried() {
        #expect(delay(1, .notFound) == nil)
        #expect(delay(1, .badResponse) == nil)
    }

    @Test func itGivesUpAfterTheAttemptLimit() {
        #expect(delay(1, .timeout) != nil)
        #expect(delay(2, .timeout) != nil)
        #expect(delay(3, .timeout) == nil)
        #expect(delay(99, .timeout) == nil)
    }

    @Test func theDelayGrowsBetweenAttempts() throws {
        let first = try #require(delay(1, .timeout, seed: 1))
        let second = try #require(delay(2, .timeout, seed: 1))
        #expect(first < second)
    }

    @Test func jitterKeepsTheDelayPositiveAndNearTheBase() throws {
        for seed in 0..<200 {
            let value = try #require(delay(1, .timeout, seed: UInt64(seed)))
            #expect(value > 0)
            #expect((250...550).contains(value), "delay \(value) strayed too far from 400ms")
        }
    }

    @Test func theWholeRetryLadderFitsInsideTheCallTimeout() {
        // The call budget is 45 s; if the sleeps alone could exceed it, the last
        // attempt would be cancelled before it ever left the device.
        #expect(RetryPolicy.worstCaseDelayMillis < 45_000)
    }

    @Test func anAttemptNumberBelowOneIsRefused() {
        #expect(delay(0, .timeout) == nil)
        #expect(delay(-5, .timeout) == nil)
    }
}

/// The cases of Android's `HubFailuresTest`, with URLError in place of JVM
/// exception class names.
struct FailureTests {
    @Test func httpStatusCodesMapToTheirKinds() {
        #expect(FailureKind.of(status: 401) == .unauthorized)
        #expect(FailureKind.of(status: 404) == .notFound)
        #expect(FailureKind.of(status: 429) == .rateLimited)
        #expect(FailureKind.of(status: 503) == .upstreamDown)
        #expect(FailureKind.of(status: 500) == .server)
        #expect(FailureKind.of(status: 400) == .badResponse)
    }

    @Test func aMissingScopeDoesNotSendTheUserToFixTheToken() {
        #expect(FailureKind.of(status: 403) == .forbidden)
        #expect(!FailureKind.forbidden.isRetryable)
        #expect(!FailureKind.banned.isRetryable)
    }

    @Test func transportErrorsMapToTheirKinds() {
        #expect(FailureKind.of(error: URLError(.timedOut)) == .timeout)
        #expect(FailureKind.of(error: URLError(.cannotFindHost)) == .noNetwork)
        #expect(FailureKind.of(error: URLError(.cannotConnectToHost)) == .noNetwork)
        #expect(FailureKind.of(error: URLError(.notConnectedToInternet)) == .noNetwork)
        #expect(FailureKind.of(error: URLError(.serverCertificateUntrusted)) == .badResponse)
    }

    @Test func aDecodeFailureIsABadResponseNotANetworkFault() {
        let error = DecodingError.dataCorrupted(.init(codingPath: [], debugDescription: "x"))
        #expect(FailureKind.of(error: error) == .badResponse)
    }

    @Test func anythingUnrecognisedIsUnknownRatherThanMislabelled() {
        struct Weird: Error {}
        #expect(FailureKind.of(error: Weird()) == .unknown)
        #expect(FailureKind.of(status: 302) == .unknown)
    }

    @Test func onlyTheTransientKindsAreRetryable() {
        #expect(FailureKind.timeout.isRetryable)
        #expect(FailureKind.noNetwork.isRetryable)
        #expect(FailureKind.server.isRetryable)
        #expect(FailureKind.upstreamDown.isRetryable)
        #expect(!FailureKind.unauthorized.isRetryable)
        #expect(!FailureKind.notFound.isRetryable)
        #expect(!FailureKind.badResponse.isRetryable)
    }

    @Test func everyKindHasAMessageAPersonCouldActOn() {
        for kind in FailureKind.allCases {
            #expect(!kind.message.isEmpty)
            #expect(!kind.message.contains("_"))
        }
    }

    @Test func theHubsOwnWordingWinsUnlessItIsBlank() {
        #expect(HubFailure(.forbidden, message: "this device may not control downloads").message
            == "this device may not control downloads")
        #expect(HubFailure(.forbidden, message: "  ").message == FailureKind.forbidden.message)
    }
}
