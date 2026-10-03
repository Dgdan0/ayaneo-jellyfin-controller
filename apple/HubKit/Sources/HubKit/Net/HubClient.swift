import Foundation

/// Sends one HTTP request. `URLSessionTransport` in the app, a scripted fake in
/// tests, so the client's rules are tested without a network.
public protocol HubTransport: Sendable {
    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse)
}

public struct URLSessionTransport: HubTransport {
    let session: URLSession

    public init(session: URLSession) {
        self.session = session
    }

    /// Screens: 30 s without a byte, 45 s for the whole call (Android's read
    /// and call timeouts). JSON is `no-store` at the hub, so no HTTP cache.
    public static func screens() -> URLSessionTransport {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource = 45
        config.urlCache = nil
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSessionTransport(session: URLSession(configuration: config))
    }

    /// Interactive release search, grabs and scans run long upstream: 150 s / 180 s.
    public static func slow() -> URLSessionTransport {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 150
        config.timeoutIntervalForResource = 180
        config.urlCache = nil
        return URLSessionTransport(session: URLSession(configuration: config))
    }

    /// Artwork: the hub marks images immutable for 30 days, so an on-disk cache
    /// answers most of a returning visit without a request.
    public static func artwork(cacheDirectory: URL? = nil) -> URLSessionTransport {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 20
        config.httpMaximumConnectionsPerHost = 8
        config.urlCache = URLCache(memoryCapacity: 32 << 20, diskCapacity: 512 << 20, directory: cacheDirectory)
        config.requestCachePolicy = .useProtocolCachePolicy
        return URLSessionTransport(session: URLSession(configuration: config))
    }

    public func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        return (data, http)
    }
}

/// Where the hub is, which token to send and which Jellyfin profile is watching.
public struct HubCredentials: Equatable, Sendable {
    public var baseURL: String
    public var token: String
    /// The Jellyfin user id sent as `X-Jellyfin-User`; empty for the hub's default.
    public var userId: String

    public init(baseURL: String, token: String, userId: String = "") {
        self.baseURL = baseURL
        self.token = token
        self.userId = userId
    }
}

/// The one way the app talks to the hub. A port of Android's `HubClient.shared`.
///
/// Every request, artwork included, passes the `CredentialGate`: after one 401
/// nothing more is sent with that token, a ban is waited out on the device, and
/// a token never yet accepted sends one request at a time. GETs are retried by
/// `RetryPolicy`; nothing else is, because a timeout does not mean it did not
/// happen.
public actor HubClient {
    public static let userHeader = "X-Jellyfin-User"

    private var credentials: HubCredentials
    private var gate = CredentialGate()
    private let screens: any HubTransport
    private let slow: any HubTransport
    private let artwork: any HubTransport
    private let now: @Sendable () -> Int64
    private let sleep: @Sendable (Int64) async throws -> Void

    private var probing = false
    private var probeQueue: [CheckedContinuation<Void, Never>] = []

    public init(
        credentials: HubCredentials,
        screens: any HubTransport,
        slow: (any HubTransport)? = nil,
        artwork: (any HubTransport)? = nil,
        now: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1_000) },
        sleep: @escaping @Sendable (Int64) async throws -> Void = { try await Task.sleep(for: .milliseconds($0)) }
    ) {
        self.credentials = credentials
        self.screens = screens
        self.slow = slow ?? screens
        self.artwork = artwork ?? screens
        self.now = now
        self.sleep = sleep
    }

    public func update(_ credentials: HubCredentials) {
        self.credentials = credentials
    }

    public var current: HubCredentials { credentials }

    /// Why a request would not be sent right now, for a screen that wants to
    /// say so before trying.
    public func block() -> CredentialGate.Block? {
        gate.block(for: credentials.token, nowMillis: now())
    }

    /// Sends `request` and decodes the answer.
    public func fetch<T: Decodable & Sendable>(_ request: HubRequest, as type: T.Type = T.self) async throws(HubFailure) -> T {
        let data = try await perform(request, transport: request.slow ? slow : screens)
        do {
            return try JSONDecoder().decode(T.self, from: data)
        } catch {
            throw HubFailure(.badResponse)
        }
    }

    /// Sends `request` for its effect; the body, if any, is ignored.
    public func send(_ request: HubRequest) async throws(HubFailure) {
        _ = try await perform(request, transport: request.slow ? slow : screens)
    }

    /// The bytes of a hub image path ("/v1/img/jf/..."), through the artwork
    /// transport and its cache.
    public func image(_ hubPath: String) async throws(HubFailure) -> Data {
        try await perform(HubRequest(hubPath), transport: artwork)
    }

    private func perform(_ request: HubRequest, transport: any HubTransport) async throws(HubFailure) -> Data {
        let creds = credentials
        if creds.baseURL.isEmpty {
            throw HubFailure(.unauthorized, message: "No Hub address is configured")
        }
        if creds.token.isEmpty {
            throw HubFailure(.unauthorized, message: "No Hub access token — open Services > Ayaneo Hub")
        }
        guard let url = URL(string: HubEndpoints.join(creds.baseURL, request.path)) else {
            throw HubFailure(.badResponse, message: "The Hub address is not a valid URL")
        }
        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = request.method.rawValue
        urlRequest.setValue("Bearer " + creds.token, forHTTPHeaderField: "Authorization")
        if !creds.userId.isEmpty {
            urlRequest.setValue(creds.userId, forHTTPHeaderField: Self.userHeader)
        }
        if request.method != .get {
            urlRequest.setValue("no-store, no-cache", forHTTPHeaderField: "Cache-Control")
            if let body = request.body {
                urlRequest.httpBody = body
                urlRequest.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
            }
        }

        var attempt = 0
        while true {
            attempt += 1
            let failure: HubFailure
            do {
                let (data, response) = try await sendThroughGate(urlRequest, token: creds.token, transport: transport)
                if (200...299).contains(response.statusCode) { return data }
                failure = HubFailure(.of(status: response.statusCode), message: Self.hubMessage(data),
                                     status: response.statusCode)
            } catch let blocked as HubFailure {
                throw blocked
            } catch {
                let kind = FailureKind.of(error: error)
                if kind == .cancelled { throw HubFailure(.cancelled) }
                failure = HubFailure(kind)
            }
            guard let delay = RetryPolicy.delayMillis(attempt: attempt, kind: failure.kind,
                                                      idempotent: request.idempotent) else {
                throw failure
            }
            do {
                try await sleep(delay)
            } catch {
                throw HubFailure(.cancelled)
            }
        }
    }

    /// One request, held behind the gate: refused outright when the token is
    /// rejected or banned, and serialised while the token is unproven.
    private func sendThroughGate(
        _ request: URLRequest, token: String, transport: any HubTransport
    ) async throws -> (Data, HTTPURLResponse) {
        if let block = gate.block(for: token, nowMillis: now()) {
            throw HubFailure(block.kind, message: block.message)
        }
        let serialised = gate.needsProbe(token)
        if serialised {
            await acquireProbe()
        }
        defer {
            if serialised { releaseProbe() }
        }
        // Whatever the request ahead in the queue learned applies to this one.
        if let block = gate.block(for: token, nowMillis: now()) {
            throw HubFailure(block.kind, message: block.message)
        }
        let (data, response) = try await transport.send(request)
        let retryAfter = response.value(forHTTPHeaderField: "Retry-After").flatMap { Int64($0.trimmingCharacters(in: .whitespaces)) }
        gate.observe(token: token, status: response.statusCode, retryAfterSeconds: retryAfter, nowMillis: now())
        return (data, response)
    }

    private func acquireProbe() async {
        if !probing {
            probing = true
            return
        }
        await withCheckedContinuation { probeQueue.append($0) }
    }

    private func releaseProbe() {
        if probeQueue.isEmpty {
            probing = false
        } else {
            probeQueue.removeFirst().resume()
        }
    }

    static func hubMessage(_ data: Data) -> String? {
        guard let body = try? JSONDecoder().decode(HubErrorBody.self, from: data) else { return nil }
        return body.error?.message
    }
}
