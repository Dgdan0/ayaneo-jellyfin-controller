import Foundation
import Testing
@testable import HubKit

/// Sessions left open by a crash, a forced quit or a relaunch are closed at the
/// next launch, each for its own profile.
struct PlaybackLeftoversTests {
    let t0 = Date(timeIntervalSince1970: 1_800_000_000)
    let a = String(repeating: "a", count: 32)
    let b = String(repeating: "b", count: 32)
    let c = String(repeating: "c", count: 32)

    @Test func aSessionIsKeptOnceUntilItIsClosed() {
        var book = OpenSessions()
        book.opened(a, user: "u1", at: t0)
        book.opened(a, user: "u1", at: t0.addingTimeInterval(5))
        book.opened(b, user: "u2", at: t0)
        book.opened("", user: "u3", at: t0)
        #expect(book.entries.map(\.id) == [a, b])
        book.closed(a)
        book.closed("not one of them")
        #expect(book.leftovers(now: t0).map(\.id) == [b])
        #expect(OpenSessions().leftovers(now: t0).isEmpty)
    }

    @Test func anythingOlderThanTwoHoursTheHubHasClosedItself() {
        var book = OpenSessions()
        book.opened(a, user: "u1", at: t0)
        book.opened(b, user: "u1", at: t0.addingTimeInterval(3 * 3_600))
        let now = t0.addingTimeInterval(3 * 3_600 + 60)
        #expect(book.leftovers(now: now).map(\.id) == [b])
        book.prune(now: now)
        #expect(book.entries.map(\.id) == [b])
    }

    @Test func theBookIsKeptAsJSONAcrossLaunches() throws {
        var book = OpenSessions()
        book.opened(a, user: "u1", at: t0)
        let data = try JSONEncoder().encode(book)
        #expect(try JSONDecoder().decode(OpenSessions.self, from: data) == book)
    }

    @Test func aLeftoverIsClosedForItsOwnProfileAndOneTheHubNoLongerHasCountsAsClosed() async {
        let transport = ScriptedTransport([
            .init(status: 200, body: #"{"ok":true}"#),
            .init(status: 404, body: #"{"error":{"code":"not_found","message":"no such playback session"}}"#),
            .init(status: 503, body: #"{"error":{"code":"upstream_down","message":"jellyfin is not responding"}}"#),
        ])
        let hub = HubClient(credentials: HubCredentials(baseURL: "https://hub.example", token: String(repeating: "t", count: 43),
                                                        userId: "99999999999999999999999999999999"),
                            screens: transport, now: { 0 }, sleep: { _ in })
        let entries = [OpenSessions.Entry(id: a, user: "u1", openedAt: t0),
                       OpenSessions.Entry(id: b, user: "u2", openedAt: t0),
                       OpenSessions.Entry(id: c, user: "u3", openedAt: t0)]
        let done = await PlaybackLeftovers.close(entries, hub: hub)
        // The third could not be closed: it stays for the next launch.
        #expect(done == [a, b])
        let sent = await transport.sent
        #expect(sent.map(\.httpMethod) == ["DELETE", "DELETE", "DELETE"])
        #expect(sent.map { $0.url?.path } == ["/v1/playback/sessions/\(a)", "/v1/playback/sessions/\(b)",
                                              "/v1/playback/sessions/\(c)"])
        #expect(sent.map { $0.value(forHTTPHeaderField: HubClient.userHeader) } == ["u1", "u2", "u3"])
    }
}
