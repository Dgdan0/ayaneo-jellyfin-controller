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
    /// Counts the library orders saved from this device (#15): a page that drew
    /// the libraries reads them again when it changes.
    private(set) var libraryOrderChanges = 0
    /// Titles requested on this device this session: cards show it at once,
    /// before the hub's next read does (Android's `RequestedTitles`).
    private(set) var requested = RequestedTitles()
    /// Counts the connections saved from this device: what holds the token
    /// outside the client (the downloads' background session) takes the new one.
    private(set) var connectionChanges = 0

    let hub: HubClient
    /// Each artwork's Glass colours, asked of the hub and kept on the device.
    let colors: ArtworkColors
    /// The last answers Home and Discover opened with (#38). The demo hub keeps
    /// its own, apart from a real hub's, and forgets them at launch when a
    /// debug run asks (HUB_FORGET_ANSWERS=1).
    @ObservationIgnored private(set) lazy var answers: AnswerKeeper = {
        let keeper = isDemo ? AnswerKeeper.standard(named: "last-answers-demo") : AnswerKeeper.standard()
        #if DEBUG
        if ProcessInfo.processInfo.environment["HUB_FORGET_ANSWERS"] == "1" { keeper.removeAll() }
        #endif
        return keeper
    }()
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
            colors = ArtworkColors(hub: hub, file: nil)
            return
        }
        var seeded: String?
        #if DEBUG
        // scripts/mac.sh seeds a simulator or the Mac app from apple/dev.env, the
        // way dev.sh seed does on the Pocket DS. Debug builds only.
        if let url = environment["HUB_URL"], let token = environment["HUB_TOKEN"], !url.isEmpty, !token.isEmpty {
            defaults.set(HubEndpoints.normaliseBase(url), forKey: Self.addressKey)
            seeded = token.trimmingCharacters(in: .whitespacesAndNewlines)
            #if os(iOS)
            // A simulator's own Keychain. The Mac app's is the user's login
            // keychain, which a launch over SSH leaves alone: there the seed
            // is used for this run only.
            Keychain.token = seeded
            #endif
        }
        #endif
        let token = seeded ?? Keychain.token ?? ""
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
        colors = ArtworkColors(hub: hub, file: ArtworkColors.file)
        // Sessions an earlier launch left open are closed before anything plays.
        if isConfigured {
            let hub = hub
            Task { await PlaybackMemory.closeLeftovers(hub: hub) }
        }
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
        connectionChanges += 1
    }

    func storedToken() -> String {
        isDemo ? DemoTransport.token : (Keychain.token ?? "")
    }

    func libraryOrderChanged() {
        libraryOrderChanges += 1
    }

    func recordRequest(key: String, availability: String, requestId: Int) {
        requested.record(key: key, availability: availability, requestId: requestId)
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
