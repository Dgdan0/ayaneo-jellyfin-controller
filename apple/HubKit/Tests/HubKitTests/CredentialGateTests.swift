import Testing
@testable import HubKit

/// The cases of Android's `CredentialGateTest`, one for one.
struct CredentialGateTests {
    var gate = CredentialGate()

    @Test func aNewTokenMayBeSentButOneRequestAtATime() {
        #expect(gate.block(for: "abc", nowMillis: 0) == nil)
        #expect(gate.needsProbe("abc"))
    }

    @Test mutating func one401BlocksEveryLaterRequestWithThatToken() {
        gate.observe(token: "abc", status: 401, retryAfterSeconds: nil, nowMillis: 0)
        #expect(gate.block(for: "abc", nowMillis: 0) == .rejected)
        #expect(gate.block(for: "abc", nowMillis: 60 * 60_000) == .rejected)
    }

    @Test mutating func editingTheTokenAllowsOneNewAttempt() {
        gate.observe(token: "abc", status: 401, retryAfterSeconds: nil, nowMillis: 0)
        #expect(gate.block(for: "abd", nowMillis: 0) == nil)
        #expect(gate.needsProbe("abd"))
    }

    @Test mutating func a403IsAMissingScopeNotAWrongToken() {
        // "this device is not allowed to control downloads": the same token
        // still reads everything else.
        gate.observe(token: "abc", status: 403, retryAfterSeconds: nil, nowMillis: 0)
        #expect(gate.block(for: "abc", nowMillis: 0) == nil)
        #expect(!gate.needsProbe("abc"))
    }

    @Test mutating func successLiftsTheProbeAndClearsAnOldRejection() {
        gate.observe(token: "abc", status: 401, retryAfterSeconds: nil, nowMillis: 0)
        gate.observe(token: "abc", status: 200, retryAfterSeconds: nil, nowMillis: 0)
        #expect(gate.block(for: "abc", nowMillis: 0) == nil)
        #expect(!gate.needsProbe("abc"))
    }

    @Test mutating func aLater401ForAnAcceptedTokenRejectsItAgain() {
        // The token was revoked on the hub.
        gate.observe(token: "abc", status: 200, retryAfterSeconds: nil, nowMillis: 0)
        gate.observe(token: "abc", status: 401, retryAfterSeconds: nil, nowMillis: 0)
        #expect(gate.block(for: "abc", nowMillis: 0) == .rejected)
        #expect(gate.needsProbe("abc"))
    }

    @Test mutating func aBanHoldsEveryRequestUntilItExpires() throws {
        gate.observe(token: "abc", status: 429, retryAfterSeconds: 900, nowMillis: 1_000)
        let block = try #require(gate.block(for: "abc", nowMillis: 61_000))
        #expect(block.remainingSeconds == 840)
        #expect(block.message == "The Hub is refusing this device for 14 min after failed sign-ins")
        #expect(gate.block(for: "abc", nowMillis: 901_000) == nil)
    }

    @Test mutating func theOrdinaryRateLimitIsNotABan() {
        gate.observe(token: "abc", status: 429, retryAfterSeconds: 2, nowMillis: 0)
        #expect(gate.block(for: "abc", nowMillis: 0) == nil)
        gate.observe(token: "abc", status: 429, retryAfterSeconds: nil, nowMillis: 0)
        #expect(gate.block(for: "abc", nowMillis: 0) == nil)
    }

    @Test mutating func aNewTokenMayTestTheConnectionDuringABan() {
        // Restarting the hub clears its bans; typing the token again is how
        // the user asks to try.
        gate.observe(token: "abc", status: 429, retryAfterSeconds: 900, nowMillis: 0)
        #expect(gate.block(for: "xyz", nowMillis: 0) == nil)
    }

    @Test mutating func missingCredentialsAreLeftToTheCaller() {
        gate.observe(token: "", status: 401, retryAfterSeconds: nil, nowMillis: 0)
        #expect(gate.block(for: "", nowMillis: 0) == nil)
        #expect(!gate.needsProbe(""))
    }
}
