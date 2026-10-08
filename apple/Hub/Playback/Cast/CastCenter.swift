import Foundation
import HubKit
import Observation
#if canImport(GoogleCast) && os(iOS)
@preconcurrency import GoogleCast
#endif

/// The TV's player, whatever drives it: Google's Cast SDK on an iPhone and an
/// iPad, a stand-in with the demo hub (`-demo`) and in the UI tests. Called
/// on the main thread, as the SDK is.
@MainActor
protocol CastReceiver: AnyObject {
    var deviceName: String { get }
    var state: CastPlayerState { get }
    var positionMillis: Int64 { get }
    /// Whether the TV took `load`.
    func load(_ load: CastLoad) async -> Bool
    func play()
    func pause()
    func seek(to millis: Int64)
    func setActiveTracks(_ ids: [Int])
    func stop()
}

/// Cast on this device (#44): whether a TV can be chosen, the one connected,
/// and its player. One for the app; the Mac has no Cast SDK and keeps AirPlay.
@MainActor
@Observable
final class CastCenter {
    static let shared = CastCenter()

    private(set) var connection = CastConnection.unavailable
    /// The connected TV's player; nil while none is.
    private(set) var receiver: (any CastReceiver)?
    /// Cast exists on this device: the Cast button is offered.
    private(set) var supported = false
    /// The stand-in TV (`-demo`): the button connects to it directly.
    private(set) var stands = false

    @ObservationIgnored private var started = false
    #if canImport(GoogleCast) && os(iOS)
    @ObservationIgnored private var google: GoogleCastLink?
    #endif

    /// Once, as the app starts: the SDK set up with Google's Default Media
    /// Receiver. It looks for TVs once a video is opened (`look`), so the
    /// Local Network question comes then, not at launch. With the demo hub,
    /// the stand-in TV only where a debug launch asks for it
    /// (HUB_CAST=standin, the UI tests; HUB_CAST=connected, already connected,
    /// for screenshots): the other tests' players stay as they were.
    func start(demo: Bool) {
        guard !started else { return }
        started = true
        #if DEBUG
        // HUB_CAST=google: Google's SDK even with the demo hub, as a real
        // launch sets it up, so a UI test opens the app the owner opens.
        let realSDK = ProcessInfo.processInfo.environment["HUB_CAST"] == "google"
        #else
        let realSDK = false
        #endif
        if demo && !realSDK {
            #if DEBUG
            let asked = ProcessInfo.processInfo.environment["HUB_CAST"] ?? ""
            if asked == "standin" || asked == "connected" {
                stands = true
                supported = true
                connection = .available
                if asked == "connected" { toggleStandIn() }
            }
            #endif
            return
        }
        #if canImport(GoogleCast) && os(iOS)
        // A build number the SDK cannot read stops the app (`CastSDKVersion`):
        // without Cast, the app still opens.
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? ""
        guard CastSDKVersion.accepts(build) else {
            NSLog("cast: build number %@ is not one Google's SDK reads; Cast is left out", build)
            return
        }
        supported = true
        google = GoogleCastLink(center: self)
        #endif
    }

    /// A video opened: the TVs on the network are looked for, and the Cast
    /// button shows once one is found, as the Pocket's does.
    func look() {
        #if canImport(GoogleCast) && os(iOS)
        google?.look()
        #endif
    }

    /// The Cast button is offered: Cast exists here and a TV has been found.
    var offered: Bool { supported && connection != .unavailable }

    /// The Cast SDK's word on where things stand.
    fileprivate func update(_ connection: CastConnection, receiver: (any CastReceiver)?) {
        if connection != self.connection { self.connection = connection }
        if (receiver as AnyObject?) !== (self.receiver as AnyObject?) { self.receiver = receiver }
    }

    /// Stops the TV and lets it go.
    func disconnect() {
        if stands {
            receiver?.stop()
            update(.available, receiver: nil)
            return
        }
        #if canImport(GoogleCast) && os(iOS)
        google?.disconnect()
        #endif
    }

    /// The list of TVs, or the connected one's Stop casting: what the Cast
    /// button opens, for VoiceOver's action on it.
    func presentChooser() {
        if stands { return toggleStandIn() }
        #if canImport(GoogleCast) && os(iOS)
        GCKCastContext.sharedInstance().presentCastDialog()
        #endif
    }

    // MARK: The stand-in TV

    /// The demo's Cast button: connects to the living-room TV, or lets it go.
    func toggleStandIn() {
        guard stands else { return }
        if case .connected = connection {
            disconnect()
        } else {
            update(.connected(StandInReceiver.name), receiver: StandInReceiver())
        }
    }
}

/// The demo's TV: it takes any load the hub's grant names, plays a clock of
/// its own, and does what the remote asks. Nothing is fetched.
@MainActor
final class StandInReceiver: CastReceiver {
    static let name = "Living room TV"

    let deviceName = StandInReceiver.name
    private(set) var state = CastPlayerState.idle(finished: false)
    private var anchor: Int64 = 0
    private var since: ContinuousClock.Instant?
    private var duration: Int64 = 0

    var positionMillis: Int64 {
        guard state == .playing, let since else { return anchor }
        let elapsed = since.duration(to: .now)
        let played = anchor + Int64(elapsed.components.seconds * 1_000 + elapsed.components.attoseconds / 1_000_000_000_000_000)
        return duration > 0 ? min(played, duration) : played
    }

    func load(_ load: CastLoad) async -> Bool {
        guard load.url.absoluteString.contains("/v1/cast/") else { return false }
        try? await Task.sleep(for: .milliseconds(400))
        duration = load.durationMillis
        anchor = load.startMillis
        since = .now
        state = .playing
        return true
    }

    func play() {
        anchor = positionMillis
        since = .now
        state = .playing
    }

    func pause() {
        anchor = positionMillis
        since = nil
        state = .paused
    }

    func seek(to millis: Int64) {
        anchor = max(0, millis)
        if state == .playing { since = .now }
    }

    func setActiveTracks(_ ids: [Int]) {}

    func stop() {
        anchor = positionMillis
        since = nil
        state = .idle(finished: false)
    }
}

#if canImport(GoogleCast) && os(iOS)
/// The Cast SDK: its context set up once, its sessions followed, and the
/// connected TV's remote media client as a `CastReceiver`.
@MainActor
private final class GoogleCastLink: NSObject, GCKSessionManagerListener {
    private weak var center: CastCenter?
    private var receiver: GoogleCastReceiver?
    private var stateObserver: NSObjectProtocol?

    init(center: CastCenter) {
        self.center = center
        super.init()
        let criteria = GCKDiscoveryCriteria(applicationID: kGCKDefaultMediaReceiverApplicationID)
        let options = GCKCastOptions(discoveryCriteria: criteria)
        options.physicalVolumeButtonsWillControlDeviceVolume = true
        options.suspendSessionsWhenBackgrounded = false
        GCKCastContext.setSharedInstanceWith(options)
        GCKCastContext.sharedInstance().sessionManager.add(self)
        stateObserver = NotificationCenter.default.addObserver(forName: .gckCastStateDidChange, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.refresh() }
        }
        refresh()
    }

    func disconnect() {
        GCKCastContext.sharedInstance().sessionManager.endSessionAndStopCasting(true)
    }

    func look() {
        let discovery = GCKCastContext.sharedInstance().discoveryManager
        if !discovery.discoveryActive { discovery.startDiscovery() }
    }

    private func refresh() {
        let context = GCKCastContext.sharedInstance()
        let session = context.sessionManager.currentCastSession
        let name = session?.device.friendlyName ?? "TV"
        let connection: CastConnection = switch context.castState {
        case .noDevicesAvailable: .unavailable
        case .notConnected: .available
        case .connecting: .connecting(name)
        case .connected: .connected(name)
        @unknown default: .available
        }
        if case .connected = connection, let client = session?.remoteMediaClient {
            if receiver?.client !== client { receiver = GoogleCastReceiver(client: client, deviceName: name) }
        } else {
            receiver = nil
        }
        center?.update(connection, receiver: receiver)
    }

    nonisolated func sessionManager(_ sessionManager: GCKSessionManager, didStart session: GCKCastSession) {
        MainActor.assumeIsolated { refresh() }
    }

    nonisolated func sessionManager(_ sessionManager: GCKSessionManager, didResumeCastSession session: GCKCastSession) {
        MainActor.assumeIsolated { refresh() }
    }

    nonisolated func sessionManager(_ sessionManager: GCKSessionManager, didEnd session: GCKSession, withError error: (any Error)?) {
        MainActor.assumeIsolated { refresh() }
    }

    nonisolated func sessionManager(_ sessionManager: GCKSessionManager, didFailToStart session: GCKSession, withError error: any Error) {
        MainActor.assumeIsolated { refresh() }
    }
}

/// The connected TV's player through the SDK's remote media client.
@MainActor
private final class GoogleCastReceiver: NSObject, CastReceiver, GCKRequestDelegate {
    let client: GCKRemoteMediaClient
    let deviceName: String
    private var pending: [ObjectIdentifier: CheckedContinuation<Bool, Never>] = [:]

    init(client: GCKRemoteMediaClient, deviceName: String) {
        self.client = client
        self.deviceName = deviceName
    }

    var state: CastPlayerState {
        guard let status = client.mediaStatus else { return .idle(finished: false) }
        switch status.playerState {
        case .playing: return .playing
        case .paused: return .paused
        case .buffering: return .buffering
        case .loading: return .loading
        case .idle: return .idle(finished: status.idleReason == .finished)
        case .unknown: return .loading
        @unknown default: return .loading
        }
    }

    var positionMillis: Int64 { Int64((client.approximateStreamPosition() * 1_000).rounded()) }

    func load(_ load: CastLoad) async -> Bool {
        let metadata = GCKMediaMetadata(metadataType: .movie)
        metadata.setString(load.title, forKey: kGCKMetadataKeyTitle)
        if !load.subtitle.isEmpty { metadata.setString(load.subtitle, forKey: kGCKMetadataKeySubtitle) }
        if let artwork = load.artwork { metadata.addImage(GCKImage(url: artwork, width: 1_280, height: 720)) }
        let tracks = load.tracks.compactMap { track in
            GCKMediaTrack(identifier: track.id, contentIdentifier: track.url.absoluteString, contentType: "text/vtt",
                          type: .text, textSubtype: .subtitles, name: track.name, languageCode: track.language,
                          customData: nil)
        }
        let builder = GCKMediaInformationBuilder(contentURL: load.url)
        builder.streamType = .buffered
        builder.contentType = load.contentType
        builder.metadata = metadata
        builder.streamDuration = TimeInterval(load.durationMillis) / 1_000
        builder.mediaTracks = tracks
        let request = GCKMediaLoadRequestDataBuilder()
        request.mediaInformation = builder.build()
        request.startTime = TimeInterval(load.startMillis) / 1_000
        request.autoplay = true
        request.activeTrackIDs = load.activeTrackIds.map { NSNumber(value: $0) }
        let sent = client.loadMedia(with: request.build())
        sent.delegate = self
        return await withCheckedContinuation { continuation in
            pending[ObjectIdentifier(sent)] = continuation
        }
    }

    func play() { client.play() }
    func pause() { client.pause() }

    func seek(to millis: Int64) {
        let options = GCKMediaSeekOptions()
        options.interval = TimeInterval(max(0, millis)) / 1_000
        client.seek(with: options)
    }

    func setActiveTracks(_ ids: [Int]) { client.setActiveTrackIDs(ids.map { NSNumber(value: $0) }) }
    func stop() { client.stop() }

    private func finish(_ request: GCKRequest, _ accepted: Bool) {
        pending.removeValue(forKey: ObjectIdentifier(request))?.resume(returning: accepted)
    }

    nonisolated func requestDidComplete(_ request: GCKRequest) {
        MainActor.assumeIsolated { finish(request, true) }
    }

    nonisolated func request(_ request: GCKRequest, didFailWithError error: GCKError) {
        MainActor.assumeIsolated { finish(request, false) }
    }

    nonisolated func request(_ request: GCKRequest, didAbortWith abortReason: GCKRequestAbortReason) {
        MainActor.assumeIsolated { finish(request, false) }
    }
}
#endif
