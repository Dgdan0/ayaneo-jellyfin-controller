import Foundation
import Testing
@testable import HubKit

/// Answers with a scripted status per call and records what was sent.
actor ScriptedTransport: HubTransport {
    struct Reply: Sendable {
        var status: Int
        var body: String = "{}"
        var headers: [String: String] = [:]
        var delayMillis: UInt64 = 0
    }

    private var replies: [Reply]
    private(set) var sent: [URLRequest] = []
    private(set) var inFlight = 0
    private(set) var maxInFlight = 0

    init(_ replies: [Reply]) {
        self.replies = replies
    }

    nonisolated func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        try await answer(request)
    }

    private func answer(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        sent.append(request)
        inFlight += 1
        maxInFlight = max(maxInFlight, inFlight)
        let reply = replies.count > 1 ? replies.removeFirst() : replies[0]
        if reply.delayMillis > 0 { try await Task.sleep(for: .milliseconds(reply.delayMillis)) }
        inFlight -= 1
        let response = HTTPURLResponse(url: request.url!, statusCode: reply.status, httpVersion: "HTTP/1.1",
                                       headerFields: reply.headers)!
        return (Data(reply.body.utf8), response)
    }
}

struct HubClientTests {
    let token = String(repeating: "t", count: 43)
    let healthJSON = #"{"hub":{"version":"0.9.4","uptimeSeconds":3600,"tokenCount":2},"services":[]}"#

    func client(_ transport: ScriptedTransport, userId: String = "") -> HubClient {
        HubClient(credentials: HubCredentials(baseURL: "https://hub.example", token: token, userId: userId),
                  screens: transport, now: { 0 }, sleep: { _ in })
    }

    @Test func everyRequestCarriesTheTokenAndTheChosenProfile() async throws {
        let transport = ScriptedTransport([.init(status: 200, body: healthJSON)])
        let hub = client(transport, userId: "44444444444444444444444444444444")
        _ = try await hub.fetch(HubEndpoints.health, as: HealthResponse.self)
        let sent = try #require(await transport.sent.first)
        #expect(sent.url?.absoluteString == "https://hub.example/v1/health")
        #expect(sent.value(forHTTPHeaderField: "Authorization") == "Bearer " + token)
        #expect(sent.value(forHTTPHeaderField: "X-Jellyfin-User") == "44444444444444444444444444444444")
    }

    @Test func withoutAProfileTheHubsDefaultIsUsed() async throws {
        let transport = ScriptedTransport([.init(status: 200, body: healthJSON)])
        _ = try await client(transport).fetch(HubEndpoints.health, as: HealthResponse.self)
        #expect(await transport.sent.first?.value(forHTTPHeaderField: "X-Jellyfin-User") == nil)
    }

    @Test func oneRejectionStopsAllTrafficWithThatToken() async {
        // Five wrong tokens ban the source for 15 minutes, and retrying through
        // the ban extends it. After the first 401 nothing more may leave.
        let transport = ScriptedTransport([.init(status: 401, body: #"{"error":{"code":"unauthorized","message":"unauthorized"}}"#)])
        let hub = client(transport)
        await #expect(throws: HubFailure.self) { try await hub.send(HubEndpoints.health) }
        for _ in 0..<6 {
            do {
                try await hub.send(HubEndpoints.home)
                Issue.record("a rejected token was sent again")
            } catch {
                #expect(error.kind == .unauthorized)
                #expect(error.message == "This token was rejected — edit it in Services > Ayaneo Hub")
            }
        }
        #expect(await transport.sent.count == 1)
    }

    @Test func aNewTokenGetsOneNewAttempt() async throws {
        let transport = ScriptedTransport([.init(status: 401), .init(status: 200, body: healthJSON)])
        let hub = client(transport)
        await #expect(throws: HubFailure.self) { try await hub.send(HubEndpoints.health) }
        await hub.update(HubCredentials(baseURL: "https://hub.example", token: String(repeating: "u", count: 43)))
        _ = try await hub.fetch(HubEndpoints.health, as: HealthResponse.self)
        #expect(await transport.sent.count == 2)
    }

    @Test func aBanIsWaitedOutOnTheDevice() async {
        let transport = ScriptedTransport([.init(status: 429, body: #"{"error":{"code":"banned","message":"banned"}}"#,
                                                 headers: ["Retry-After": "900"])])
        let hub = client(transport)
        await #expect(throws: HubFailure.self) { try await hub.send(HubEndpoints.health) }
        do {
            try await hub.send(HubEndpoints.home)
            Issue.record("a request went out during a ban")
        } catch {
            #expect(error.kind == .banned)
            #expect(error.message == "The Hub is refusing this device for 15 min after failed sign-ins")
        }
        // The first 429 is not retried into: the gate holds the retry too.
        #expect(await transport.sent.count == 1)
    }

    @Test func aMissingScopeLeavesTheTokenWorking() async throws {
        let transport = ScriptedTransport([
            .init(status: 403, body: #"{"error":{"code":"forbidden_scope","message":"this device may not control downloads"}}"#),
            .init(status: 200, body: healthJSON),
        ])
        let hub = client(transport)
        do {
            try await hub.send(HubEndpoints.scanJellyfinLibrary)
            Issue.record("a 403 succeeded")
        } catch {
            #expect(error.kind == .forbidden)
            #expect(error.message == "this device may not control downloads")
        }
        _ = try await hub.fetch(HubEndpoints.health, as: HealthResponse.self)
        #expect(await transport.sent.count == 2)
    }

    @Test func anUnprovenTokenSendsOneRequestAtATime() async throws {
        // A burst of concurrent poster loads must not reach the ban threshold
        // before the first answer arrives.
        let transport = ScriptedTransport([.init(status: 401, delayMillis: 20)])
        let hub = client(transport)
        await withTaskGroup(of: Void.self) { group in
            for _ in 0..<8 {
                group.addTask { try? await hub.send(HubEndpoints.home) }
            }
        }
        #expect(await transport.maxInFlight == 1)
        #expect(await transport.sent.count == 1)
    }

    @Test func anAcceptedTokenSendsConcurrently() async throws {
        let transport = ScriptedTransport([.init(status: 200, body: healthJSON, delayMillis: 20)])
        let hub = client(transport)
        _ = try await hub.fetch(HubEndpoints.health, as: HealthResponse.self)
        await withTaskGroup(of: Void.self) { group in
            for _ in 0..<6 {
                group.addTask { _ = try? await hub.fetch(HubEndpoints.health, as: HealthResponse.self) }
            }
        }
        #expect(await transport.maxInFlight > 1)
    }

    @Test func aReadIsRetriedThroughATransientFailure() async throws {
        let transport = ScriptedTransport([.init(status: 200, body: healthJSON), .init(status: 503),
                                           .init(status: 200, body: healthJSON)])
        let hub = client(transport)
        _ = try await hub.fetch(HubEndpoints.health, as: HealthResponse.self)
        let health = try await hub.fetch(HubEndpoints.health, as: HealthResponse.self)
        #expect(health.hub.version == "0.9.4")
        #expect(await transport.sent.count == 3)
    }

    @Test func aReadGivesUpAfterThreeAttempts() async {
        let transport = ScriptedTransport([.init(status: 200, body: healthJSON), .init(status: 503)])
        let hub = client(transport)
        _ = try? await hub.fetch(HubEndpoints.health, as: HealthResponse.self)
        do {
            try await hub.send(HubEndpoints.health)
            Issue.record("a 503 succeeded")
        } catch {
            #expect(error.kind == .upstreamDown)
        }
        #expect(await transport.sent.count == 4)
    }

    @Test func aMutationIsNeverRetried() async {
        // A timeout does not mean it did not happen.
        let transport = ScriptedTransport([.init(status: 200, body: healthJSON), .init(status: 503)])
        let hub = client(transport)
        _ = try? await hub.fetch(HubEndpoints.health, as: HealthResponse.self)
        await #expect(throws: HubFailure.self) { try await hub.send(HubEndpoints.scanJellyfinLibrary) }
        #expect(await transport.sent.count == 2)
        #expect(await transport.sent.last?.httpMethod == "POST")
    }

    @Test func nothingIsSentWithoutAnAddressOrAToken() async {
        let transport = ScriptedTransport([.init(status: 200)])
        let hub = HubClient(credentials: HubCredentials(baseURL: "", token: token), screens: transport)
        do {
            try await hub.send(HubEndpoints.health)
        } catch {
            #expect(error.message == "No Hub address is configured")
        }
        await hub.update(HubCredentials(baseURL: "https://hub.example", token: ""))
        do {
            try await hub.send(HubEndpoints.health)
        } catch {
            #expect(error.message == "No Hub access token — open Services > Ayaneo Hub")
        }
        #expect(await transport.sent.isEmpty)
    }

    @Test func aResponseOfTheWrongShapeIsABadResponse() async {
        let transport = ScriptedTransport([.init(status: 200, body: "[1,2,3]")])
        do {
            _ = try await client(transport).fetch(HubEndpoints.health, as: HealthResponse.self)
            Issue.record("an array decoded as health")
        } catch {
            #expect(error.kind == .badResponse)
        }
    }
}

struct ModelDecodingTests {
    @Test func healthReadsEveryFieldAndIgnoresTheRest() throws {
        let json = #"""
        {"hub":{"version":"0.9.4","uptimeSeconds":97200,"tokenCount":3},
         "services":[
          {"name":"jellyfin","state":"up","dashboardUrl":"https://pc.ts.net:8920","latencyMs":41,
           "version":"10.11.8","checkedAt":"2026-10-03T21:04:05.123456789+03:00"},
          {"name":"qbittorrent","state":"up","notes":["reachable; session auth not yet established"],
           "checkedAt":"2026-10-03T21:04:05Z","somethingNew":true},
          {"name":"bazarr","state":"down","lastError":"connection refused","latencyMs":null}
         ]}
        """#
        let health = try JSONDecoder().decode(HealthResponse.self, from: Data(json.utf8))
        #expect(health.hub == HubInfo(version: "0.9.4", uptimeSeconds: 97_200, tokenCount: 3))
        #expect(health.services.map(\.name) == ["jellyfin", "qbittorrent", "bazarr"])
        #expect(health.services[0].dashboardUrl == "https://pc.ts.net:8920")
        #expect(health.services[0].latencyMs == 41)
        #expect(health.services[1].notes == ["reachable; session auth not yet established"])
        #expect(health.services[2].lastError == "connection refused")
        #expect(health.services[2].latencyMs == 0)
    }

    @Test func usersReadTheSelectedProfile() throws {
        let json = #"""
        {"users":[{"id":"44444444444444444444444444444444","name":"Dgdan","selected":true},
                  {"id":"55555555555555555555555555555555","name":"Hadas"}],
         "partial":[{"service":"jellyfin","reason":"row_unavailable","since":"0001-01-01T00:00:00Z"}],
         "cache":{"hit":true,"ageSeconds":4,"stale":false}}
        """#
        let users = try JSONDecoder().decode(UsersResponse.self, from: Data(json.utf8))
        #expect(users.users.map(\.name) == ["Dgdan", "Hadas"])
        #expect(users.users[0].selected)
        #expect(!users.users[1].selected)
        #expect(users.partial == [Partial(service: "jellyfin", reason: "row_unavailable")])
        #expect(users.cache == CacheInfo(hit: true, ageSeconds: 4))
    }
}
