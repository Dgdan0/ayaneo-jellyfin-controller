import Foundation
import HubKit
import Observation

/// The app's one connection to the hub and what it is configured with. Every
/// screen goes through `hub`, so the credential rules hold everywhere (the
/// Android client's `HubClient.shared`).
@MainActor
@Observable
final class AppModel {
    private(set) var address: String
    private(set) var hasToken: Bool
    private(set) var userId: String
    private(set) var userName: String
    /// Running against fixtures (`-demo`), not a real hub.
    let isDemo: Bool

    let hub: HubClient
    @ObservationIgnored private let defaults: UserDefaults

    var isConfigured: Bool { !address.isEmpty && hasToken }

    private static let addressKey = "hub.address"
    private static let userIdKey = "hub.userId"
    private static let userNameKey = "hub.userName"

    init(defaults: UserDefaults = .standard, environment: [String: String] = ProcessInfo.processInfo.environment,
         arguments: [String] = ProcessInfo.processInfo.arguments) {
        self.defaults = defaults
        isDemo = arguments.contains("-demo")
        if isDemo {
            address = DemoTransport.address
            hasToken = true
            userId = ""
            userName = ""
            hub = HubClient(credentials: HubCredentials(baseURL: DemoTransport.address, token: DemoTransport.token),
                            screens: DemoTransport())
            return
        }
        #if DEBUG
        // scripts/mac.sh seeds a simulator or the Mac app from apple/dev.env, the
        // way dev.sh seed does on the Pocket DS. Debug builds only.
        if let url = environment["HUB_URL"], let token = environment["HUB_TOKEN"], !url.isEmpty, !token.isEmpty {
            defaults.set(HubEndpoints.normaliseBase(url), forKey: Self.addressKey)
            Keychain.token = token.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        #endif
        let token = Keychain.token ?? ""
        let storedAddress = defaults.string(forKey: Self.addressKey) ?? ""
        let storedUser = defaults.string(forKey: Self.userIdKey) ?? ""
        address = storedAddress
        hasToken = !token.isEmpty
        userId = storedUser
        userName = defaults.string(forKey: Self.userNameKey) ?? ""
        hub = HubClient(
            credentials: HubCredentials(baseURL: storedAddress, token: token, userId: storedUser),
            screens: URLSessionTransport.screens(),
            slow: URLSessionTransport.slow(),
            artwork: URLSessionTransport.artwork())
    }

    /// Saves first, then the caller tests: Android's "Save and test", so a
    /// failed test still leaves the address saved for the next try.
    func saveConnection(address newAddress: String, token newToken: String) async {
        let normalised = HubEndpoints.normaliseBase(newAddress)
        if !isDemo {
            defaults.set(normalised, forKey: Self.addressKey)
            Keychain.token = newToken
        }
        address = normalised
        hasToken = !newToken.isEmpty
        await hub.update(HubCredentials(baseURL: normalised, token: newToken, userId: userId))
    }

    func storedToken() -> String {
        isDemo ? DemoTransport.token : (Keychain.token ?? "")
    }

    /// The Jellyfin profile this device watches as (`X-Jellyfin-User`).
    func selectUser(id: String, name: String) async {
        userId = id
        userName = name
        defaults.set(id, forKey: Self.userIdKey)
        defaults.set(name, forKey: Self.userNameKey)
        var credentials = await hub.current
        credentials.userId = id
        await hub.update(credentials)
    }
}
