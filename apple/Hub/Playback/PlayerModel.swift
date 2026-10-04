import AVFoundation
import HubKit
import Observation
import SwiftUI
#if os(iOS)
import UIKit
#elseif os(macOS)
import AppKit
#endif

/// What a page asks the player to play.
struct PlayRequest: Equatable {
    /// A movie or an episode; with `series`, the series whose episode the hub
    /// picks (part-watched, next or first).
    var itemId: String
    var mode: PlaybackStartMode = .resume
    var series = false
    /// Shown while it opens: the title's name and its backdrop.
    var title = ""
    var backdrop = ""
}

/// Opens the player from any page (`@Environment(\.play)`).
struct PlayAction {
    var open: @MainActor (PlayRequest) -> Void = { _ in }

    @MainActor func callAsFunction(_ request: PlayRequest) { open(request) }
}

extension EnvironmentValues {
    @Entry var play = PlayAction()
    /// Counts playbacks that have ended and reached the hub. A page that shows
    /// progress reads it again when this changes, in place, keeping its scroll.
    @Entry var playbackClosed = 0
}

/// The up-next card near the end of an episode: what plays next and how full
/// its bar is (`UpNext.countdownMillis` to fill).
struct UpNextCard: Equatable {
    let item: PlaybackItem
    var fraction: Double
}

/// The player: AVPlayer, the hub session it plays and everything the chrome
/// shows. One per window, owned by the shell.
///
/// A session's life is Android's (`PlaybackService`): prepare with what
/// AVPlayer can open, then stream it, report started, paused, unpaused, seeks
/// and progress every ten seconds, and leave with stopped where it was, then
/// `DELETE` the session. Leaving is the only way out, and it is taken on Back,
/// on the next episode, when the app goes to the background and before it
/// quits, so a session is never left for the hub's 30-minute cleanup to save.
@MainActor
@Observable
final class PlayerModel {
    enum Phase: Equatable {
        case opening
        case playing
        case failed(String)
    }

    /// Set while the player is on screen.
    private(set) var request: PlayRequest?
    private(set) var phase: Phase = .opening
    private(set) var plan: PlaybackPrepareResponse?
    private(set) var positionMillis: Int64 = 0
    private(set) var bufferedMillis: Int64 = 0
    private(set) var isPlaying = false
    private(set) var isBuffering = false
    /// The first frame is on screen; until then the title's picture stands in.
    private(set) var readyForDisplay = false
    /// The video played to its end.
    private(set) var atEnd = false
    private(set) var upNext: UpNextCard?
    /// The picture behind the player while it opens.
    private(set) var backdrop = ""
    private(set) var closedCount = 0
    /// The length AVPlayer found, for a plan that names none.
    private var itemDuration: Int64 = 0

    var isOpen: Bool { request != nil }

    var durationMillis: Int64 {
        if let plan, plan.durationMillis > 0 { return plan.durationMillis }
        return itemDuration
    }

    /// Settings › Playback has no Apple page yet: Android's default.
    let nextTiming = NextEpisodeTiming.credits

    @ObservationIgnored let player = AVPlayer()
    @ObservationIgnored private var hub: HubClient?
    @ObservationIgnored private var outbox: PlaybackOutbox?
    @ObservationIgnored private var baseURL = ""
    /// The profile that opened the session; every later call is for it.
    @ObservationIgnored private var user = ""
    @ObservationIgnored private var reporter = PlaybackReporter()
    /// Bumped by every open, close and change of episode, so an answer for a
    /// session the person has already left is closed instead of played.
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var opens = 0
    @ObservationIgnored private var poll: Task<Void, Never>?
    @ObservationIgnored private var countdown: Task<Void, Never>?
    @ObservationIgnored private var startedItem: ObjectIdentifier?
    @ObservationIgnored private var failedItem: ObjectIdentifier?
    @ObservationIgnored private var fallbackTried = false
    @ObservationIgnored private var reportedPlaying = false
    @ObservationIgnored private var upNextDismissed = false
    @ObservationIgnored private var seekTarget: Int64?
    @ObservationIgnored private var endObserver: NSObjectProtocol?
    @ObservationIgnored private var quitObserver: NSObjectProtocol?
    @ObservationIgnored private let origin = ContinuousClock.now
    #if os(iOS)
    @ObservationIgnored private var backgroundWork: UIBackgroundTaskIdentifier = .invalid
    #endif
    #if DEBUG
    @ObservationIgnored private var debugFromEndUsed = false
    #endif

    init() {
        #if os(iOS)
        let quitting = UIApplication.willTerminateNotification
        #else
        let quitting = NSApplication.willTerminateNotification
        #endif
        quitObserver = NotificationCenter.default.addObserver(forName: quitting, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.closeBeforeQuitting() }
        }
        endObserver = NotificationCenter.default.addObserver(
            forName: AVPlayerItem.didPlayToEndTimeNotification, object: nil, queue: .main
        ) { [weak self] note in
            let item = (note.object as AnyObject?).map(ObjectIdentifier.init)
            MainActor.assumeIsolated { self?.reachedEnd(of: item) }
        }
    }

    // MARK: Opening and leaving

    func open(_ request: PlayRequest, app: AppModel) {
        #if DEBUG
        NSLog("playback: open '%@' (%@)", request.itemId, request.mode.rawValue)
        #endif
        // Nothing to ask the hub for; a card without a Jellyfin item has no Play.
        guard !request.itemId.isEmpty else { return }
        if self.request != nil { close() }
        self.request = request
        hub = app.hub
        baseURL = app.address
        user = app.userId
        outbox = outbox ?? PlaybackOutbox(hub: app.hub)
        backdrop = request.backdrop
        opens += 1
        startSession()
        activateAudio()
        poll = Task { [weak self] in
            while !Task.isCancelled {
                self?.step()
                try? await Task.sleep(for: .milliseconds(250))
            }
        }
        let generation = generation
        Task { await prepare(itemId: request.itemId, mode: request.mode, series: request.series, generation: generation) }
        #if DEBUG
        exitAfterDebugDelay()
        #endif
    }

    /// Back, the background, quitting: the one way out. Stopped where it was,
    /// then the session is closed; pages reread their progress once the hub
    /// has both.
    func close() {
        guard request != nil else { return }
        #if DEBUG
        NSLog("playback: close '%@'", plan?.sessionId ?? "no session")
        #endif
        poll?.cancel()
        poll = nil
        endSession()
        player.replaceCurrentItem(with: nil)
        request = nil
        deactivateAudio()
        let pending = outbox?.tail
        beginBackgroundWork()
        Task { [weak self] in
            await pending?.value
            self?.closedCount += 1
            self?.endBackgroundWork()
        }
    }

    /// After a failure: the same title again, in a new session (the failed
    /// one is closed first).
    func retry() {
        guard let request else { return }
        endSession()
        player.replaceCurrentItem(with: nil)
        startSession()
        let generation = generation
        Task { await prepare(itemId: request.itemId, mode: request.mode, series: request.series, generation: generation) }
    }

    func playNext() { playAdjacent(plan?.nextItem) }

    func playPrevious() { playAdjacent(plan?.previousItem) }

    /// The video ended or Play now: this session closes and the next one opens
    /// from its beginning, as on Android.
    private func playAdjacent(_ target: PlaybackItem?) {
        guard let target, request != nil else { return }
        endSession()
        player.replaceCurrentItem(with: nil)
        startSession()
        if !target.seriesId.isEmpty { backdrop = "/v1/img/jf/\(target.seriesId)/Backdrop" }
        // What plays now, so Try again after a failure opens this episode.
        request = PlayRequest(itemId: target.id, mode: .restart, title: PlayerLabels.title(target), backdrop: backdrop)
        let generation = generation
        Task { await prepare(itemId: target.id, mode: .restart, series: false, generation: generation) }
    }

    private func startSession() {
        generation += 1
        phase = .opening
        plan = nil
        reporter = PlaybackReporter()
        positionMillis = 0
        bufferedMillis = 0
        itemDuration = 0
        isPlaying = false
        isBuffering = false
        readyForDisplay = false
        atEnd = false
        upNext = nil
        upNextDismissed = false
        startedItem = nil
        failedItem = nil
        fallbackTried = false
        reportedPlaying = false
        seekTarget = nil
    }

    /// Reports the stop (when it started and the end has not already said
    /// so) and closes the session, in that order, whatever happens next.
    private func endSession() {
        generation += 1
        countdown?.cancel()
        countdown = nil
        upNext = nil
        let time = player.currentTime()
        if seekTarget == nil, time.isNumeric, player.currentItem?.status == .readyToPlay {
            positionMillis = max(0, millis(time))
        }
        player.pause()
        guard let plan, let outbox else { return }
        outbox.event(reporter.stop(positionMillis: positionMillis, muted: player.isMuted), session: plan.sessionId, user: user)
        outbox.close(session: plan.sessionId, user: user)
        self.plan = nil
    }

    private func prepare(itemId: String, mode: PlaybackStartMode, series: Bool, generation: Int) async {
        guard let hub else { return }
        let user = user
        do {
            var itemId = itemId
            var mode = mode
            if series {
                let target = try await hub.fetch(HubEndpoints.seriesPlayTarget(seriesId: itemId), as: SeriesPlayTarget.self)
                itemId = target.item.id
                mode = DetailLines.startMode(target)
            }
            let body = PlaybackPrepareBody(startMode: mode, device: PlaybackDeviceInfo.device(),
                                           capabilities: PlaybackDeviceInfo.capabilities())
            let plan = try await hub.fetch(HubEndpoints.preparePlayback(itemId: itemId, body: body, user: user),
                                           as: PlaybackPrepareResponse.self)
            try await load(plan, generation: generation)
        } catch {
            #if DEBUG
            NSLog("playback: opening %@ failed: %@ (%@)", itemId, error.message, String(describing: error.status))
            #endif
            guard generation == self.generation else { return }
            phase = .failed(error.kind == .notFound ? "This title is no longer in Jellyfin." : error.message)
        }
    }

    /// Plays `plan` from a grant's address. A session the person has left in
    /// the meantime is closed at once rather than played.
    private func load(_ plan: PlaybackPrepareResponse, generation: Int) async throws(HubFailure) {
        guard let hub, let outbox else { return }
        guard generation == self.generation else {
            outbox.close(session: plan.sessionId, user: user)
            return
        }
        let grant: PlaybackGrant
        do {
            grant = try await hub.fetch(HubEndpoints.playbackGrant(sessionId: plan.sessionId, user: user), as: PlaybackGrant.self)
        } catch {
            if generation == self.generation { self.plan = plan }
            throw error
        }
        guard generation == self.generation else {
            outbox.close(session: plan.sessionId, user: user)
            return
        }
        self.plan = plan
        guard let url = URL(string: grant.address(base: baseURL)) else {
            throw HubFailure(.badResponse, message: "The hub sent an address that cannot be played")
        }
        startedItem = nil
        failedItem = nil
        let item = AVPlayerItem(url: url)
        player.replaceCurrentItem(with: item)
    }

    // MARK: Playing

    func togglePlay() {
        if isPlaying || player.timeControlStatus == .waitingToPlayAtSpecifiedRate {
            player.pause()
            isPlaying = false
        } else {
            if atEnd { seek(to: 0) }
            player.play()
            isPlaying = true
        }
    }

    func seek(by deltaMillis: Int64) { seek(to: positionMillis + deltaMillis) }

    func seek(to target: Int64) {
        guard plan != nil, player.currentItem != nil else { return }
        let clamped = PlaybackRules.clampSeek(target, durationMillis: durationMillis)
        positionMillis = clamped
        seekTarget = clamped
        player.seek(to: CMTime(value: clamped, timescale: 1_000), toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] finished in
            Task { @MainActor in self?.seekFinished(at: clamped, finished: finished) }
        }
    }

    private func seekFinished(at target: Int64, finished: Bool) {
        guard seekTarget == target else { return }
        seekTarget = nil
        guard finished, let plan, let outbox else { return }
        if atEnd && !PlaybackRules.reachedNaturalEnd(positionMillis: target, durationMillis: durationMillis) {
            atEnd = false
        }
        outbox.event(reporter.seeked(positionMillis: target, paused: !isPlaying, muted: player.isMuted),
                     session: plan.sessionId, user: user)
        updateUpNext()
    }

    /// Four times a second while the player is open: what AVPlayer is doing,
    /// what the chrome shows, what the hub is told.
    private func step() {
        guard let item = player.currentItem else { return }
        let id = ObjectIdentifier(item)
        switch item.status {
        case .readyToPlay where startedItem != id:
            startedItem = id
            start(item)
        case .failed where failedItem != id:
            failedItem = id
            failed(item.error)
        default:
            break
        }
        if item.duration.isNumeric { itemDuration = millis(item.duration) }
        if let range = item.loadedTimeRanges.last?.timeRangeValue {
            bufferedMillis = millis(CMTimeRangeGetEnd(range))
        }
        let status = player.timeControlStatus
        isBuffering = status == .waitingToPlayAtSpecifiedRate
        isPlaying = status == .playing
        let time = player.currentTime()
        if seekTarget == nil, time.isNumeric { positionMillis = max(0, millis(time)) }
        report(status)
        updateUpNext()
    }

    /// The item is ready: to its start position, then playing.
    private func start(_ item: AVPlayerItem) {
        phase = .playing
        var startAt = plan?.positionMillis ?? 0
        #if DEBUG
        // The first title only: the next episode starts at its beginning.
        if let fromEnd = Self.debugSeconds("HUB_PLAY_FROM_END"), item.duration.isNumeric, !debugFromEndUsed {
            debugFromEndUsed = true
            startAt = max(0, millis(item.duration) - Int64(fromEnd * 1_000))
        }
        #endif
        guard startAt > 0 else {
            player.play()
            return
        }
        positionMillis = startAt
        seekTarget = startAt
        player.seek(to: CMTime(value: startAt, timescale: 1_000), toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] _ in
            Task { @MainActor in
                // Where playback starts is not a seek the person made.
                if self?.seekTarget == startAt { self?.seekTarget = nil }
                self?.player.play()
            }
        }
    }

    /// A file AVPlayer cannot open is asked for again as a conversion once,
    /// from where it was, as Android does; after that the failure is shown.
    private func failed(_ error: (any Error)?) {
        guard let plan, let hub else { return }
        let reason = error?.localizedDescription ?? "This video could not be played."
        guard !fallbackTried, plan.playMethod.lowercased() != "transcode" else {
            phase = .failed(reason)
            return
        }
        fallbackTried = true
        phase = .opening
        let body = PlaybackSelectBody(positionMillis: max(positionMillis, plan.positionMillis), forceTranscode: true)
        let request = HubEndpoints.selectPlayback(sessionId: plan.sessionId, body: body, user: user)
        let generation = generation
        Task {
            do {
                let converted = try await hub.fetch(request, as: PlaybackPrepareResponse.self)
                try await load(converted, generation: generation)
            } catch {
                if generation == self.generation { phase = .failed(reason) }
            }
        }
    }

    private func report(_ status: AVPlayer.TimeControlStatus) {
        guard let plan, let outbox else { return }
        let muted = player.isMuted
        let now = elapsedMillis()
        switch status {
        case .playing where !reportedPlaying:
            reportedPlaying = true
            outbox.event(reporter.playingChanged(true, positionMillis: positionMillis, nowMillis: now, muted: muted),
                         session: plan.sessionId, user: user)
        case .paused where reportedPlaying:
            reportedPlaying = false
            // Reaching the end pauses too; the end reports itself.
            if !PlaybackRules.reachedNaturalEnd(positionMillis: positionMillis, durationMillis: durationMillis) {
                outbox.event(reporter.playingChanged(false, positionMillis: positionMillis, nowMillis: now, muted: muted),
                             session: plan.sessionId, user: user)
            }
        default:
            break
        }
        if status == .playing {
            outbox.event(reporter.tick(playing: true, positionMillis: positionMillis, nowMillis: now, muted: muted),
                         session: plan.sessionId, user: user)
        }
    }

    private func reachedEnd(of item: ObjectIdentifier?) {
        guard let item, let current = player.currentItem, ObjectIdentifier(current) == item,
              let plan, let outbox, !atEnd else { return }
        atEnd = true
        isPlaying = false
        positionMillis = durationMillis
        outbox.event(reporter.reachedEnd(durationMillis: durationMillis, muted: player.isMuted),
                     session: plan.sessionId, user: user)
        // The card counts down to the next episode, even after Watch credits.
        if let next = plan.nextItem {
            upNextDismissed = false
            if upNext == nil { upNext = UpNextCard(item: next, fraction: 0) }
            startCountdown()
        }
    }

    // MARK: Up next

    func watchCredits() {
        upNextDismissed = true
        countdown?.cancel()
        countdown = nil
        upNext = nil
    }

    private func updateUpNext() {
        guard let plan, let next = plan.nextItem, !upNextDismissed, !atEnd else { return }
        let duration = durationMillis
        let cardAt = UpNext.cardAt(nextTiming, segments: plan.segments, durationMillis: duration)
        let shows = UpNext.showsCard(positionMillis: positionMillis, cardAt: cardAt, durationMillis: duration)
        if shows && upNext == nil {
            upNext = UpNextCard(item: next, fraction: 0)
            startCountdown()
        } else if !shows, upNext != nil, let cardAt, positionMillis < cardAt {
            // Seeked back before the card's time.
            countdown?.cancel()
            countdown = nil
            upNext = nil
        }
    }

    /// One continuous fill rather than seconds counted down; it stands still
    /// while the video is paused, so the next episode never starts behind a pause.
    private func startCountdown() {
        guard countdown == nil else { return }
        countdown = Task { [weak self] in
            var last = ContinuousClock.now
            while true {
                do {
                    try await Task.sleep(for: .milliseconds(50))
                } catch {
                    return // cancelled: the card went, or another countdown replaced this one
                }
                guard let self else { return }
                guard var card = self.upNext else {
                    self.countdown = nil
                    return
                }
                let now = ContinuousClock.now
                let elapsed = now - last
                last = now
                if self.isPlaying || self.atEnd {
                    let seconds = Double(elapsed.components.seconds) + Double(elapsed.components.attoseconds) / 1e18
                    card.fraction = min(1, card.fraction + seconds * 1_000 / Double(UpNext.countdownMillis))
                    self.upNext = card
                }
                if card.fraction >= 1 {
                    self.countdown = nil
                    self.playNext()
                    return
                }
            }
        }
    }

    func setReadyForDisplay(_ ready: Bool) {
        if readyForDisplay != ready { readyForDisplay = ready }
    }

    // MARK: The app around it

    /// Quitting: the stop and the close are sent, and the quit waits for them
    /// for at most two and a half seconds.
    private func closeBeforeQuitting() {
        guard request != nil else { return }
        close()
        guard let pending = outbox?.tail else { return }
        let done = DispatchSemaphore(value: 0)
        Task.detached {
            await pending.value
            done.signal()
        }
        _ = done.wait(timeout: .now() + 2.5)
    }

    private func activateAudio() {
        #if os(iOS)
        // Plays with the ring switch on silent, as video apps do.
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        try? AVAudioSession.sharedInstance().setActive(true)
        #endif
    }

    private func deactivateAudio() {
        #if os(iOS)
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        #endif
    }

    /// Leaving for the background still finishes telling the hub.
    private func beginBackgroundWork() {
        #if os(iOS)
        guard backgroundWork == .invalid else { return }
        backgroundWork = UIApplication.shared.beginBackgroundTask(withName: "Closing playback") { [weak self] in
            MainActor.assumeIsolated { self?.endBackgroundWork() }
        }
        #endif
    }

    private func endBackgroundWork() {
        #if os(iOS)
        guard backgroundWork != .invalid else { return }
        UIApplication.shared.endBackgroundTask(backgroundWork)
        backgroundWork = .invalid
        #endif
    }

    private func millis(_ time: CMTime) -> Int64 {
        guard time.isNumeric else { return 0 }
        return Int64((CMTimeGetSeconds(time) * 1_000).rounded())
    }

    private func elapsedMillis() -> Int64 {
        let elapsed = origin.duration(to: .now)
        return elapsed.components.seconds * 1_000 + elapsed.components.attoseconds / 1_000_000_000_000_000
    }

    #if DEBUG
    /// scripts/mac.sh: HUB_PLAY_EXIT=25 leaves the player that many seconds
    /// after it opened, through Back's own path, so a screenshot run against
    /// the real hub never leaves a session open.
    private func exitAfterDebugDelay() {
        guard let seconds = Self.debugSeconds("HUB_PLAY_EXIT") else { return }
        let opened = opens
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(seconds))
            guard let self, self.opens == opened else { return }
            self.close()
        }
    }

    static func debugSeconds(_ name: String) -> Double? {
        ProcessInfo.processInfo.environment[name].flatMap(Double.init).flatMap { $0 > 0 ? $0 : nil }
    }
    #endif
}

/// A session's events and its close, sent one after another in the order
/// they happened, away from the main thread. An event is tried three times,
/// one and then three seconds apart, as Android does; the close once. The hub
/// ignores a sequence number it has seen, so a retried event is never applied
/// twice.
@MainActor
final class PlaybackOutbox {
    private let hub: HubClient
    /// The last call queued; awaiting it waits for everything before it.
    private(set) var tail: Task<Void, Never>?

    init(hub: HubClient) {
        self.hub = hub
    }

    func event(_ body: PlaybackEventBody?, session: String, user: String) {
        guard let body else { return }
        enqueue(HubEndpoints.playbackEvent(sessionId: session, body: body, user: user), attempts: 3)
    }

    func close(session: String, user: String) {
        enqueue(HubEndpoints.closePlayback(sessionId: session, user: user), attempts: 1)
    }

    private func enqueue(_ request: HubRequest, attempts: Int) {
        let previous = tail
        let hub = hub
        tail = Task.detached {
            await previous?.value
            await Self.send(request, hub: hub, attempts: attempts)
        }
    }

    private nonisolated static func send(_ request: HubRequest, hub: HubClient, attempts: Int) async {
        let pauses: [Int] = [1_000, 3_000]
        for attempt in 0..<attempts {
            do {
                try await hub.send(request)
                return
            } catch {
                guard attempt < attempts - 1 else { return }
                try? await Task.sleep(for: .milliseconds(pauses[min(attempt, pauses.count - 1)]))
            }
        }
    }
}
