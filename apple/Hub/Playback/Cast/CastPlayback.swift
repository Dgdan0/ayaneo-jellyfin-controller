import Foundation
import HubKit
import Observation

/// What plays on the TV (#44; the Pocket's `CastPlaybackCoordinator`): a hub
/// session of its own, prepared for the TV (`CastPlan`), whose grant the TV
/// fetches from the public address; the phone is its remote, and it reports
/// as any playback does (`PlaybackReporter`): started where it began,
/// progress every ten seconds, paused and unpaused, seeks, and stopped then
/// closed when it ends, is stopped or the TV goes. It outlives the player
/// screen, as the receiver does.
@MainActor
@Observable
final class CastPlayback {
    static let shared = CastPlayback()

    /// The TV's session; nil while nothing plays there.
    private(set) var plan: PlaybackPrepareResponse?
    private(set) var deviceName = ""
    private(set) var positionMillis: Int64 = 0
    private(set) var state = CastPlayerState.idle(finished: false)
    /// The video on its way to the TV.
    private(set) var preparing = false
    /// Counts the times the TV stopped playing on its own or was let go, for
    /// the player to follow (`ended`).
    private(set) var endings = 0
    /// How it last ended: at the video's end, and where.
    private(set) var ended: (finished: Bool, positionMillis: Int64)?

    var isActive: Bool { plan != nil }
    var isPlaying: Bool { state == .playing }

    @ObservationIgnored private var receiver: (any CastReceiver)?
    @ObservationIgnored private var hub: HubClient?
    @ObservationIgnored private var outbox: PlaybackOutbox?
    @ObservationIgnored private var user = ""
    @ObservationIgnored private var reporter = PlaybackReporter()
    @ObservationIgnored private var poll: Task<Void, Never>?
    @ObservationIgnored private let origin = ContinuousClock.now
    @ObservationIgnored private var stopping = false
    /// A seek on its way: the TV's clock is not read until it lands.
    @ObservationIgnored private var seekTarget: (millis: Int64, at: ContinuousClock.Instant)?
    /// When the TV took the video: a receiver says idle for a moment before it starts.
    @ObservationIgnored private var loadedAt = ContinuousClock.now

    /// Moves `current` to the connected TV at `positionMillis`: a session for
    /// the TV, its grant on the address the TV can reach, and the TV's load.
    /// Only once the TV took it does what played before end (the caller's
    /// session, and an earlier TV session). Returns why it could not.
    func transfer(_ current: PlaybackPrepareResponse, positionMillis: Int64, startMode: PlaybackStartMode = .resume,
                  app: AppModel) async -> String? {
        guard let receiver = CastCenter.shared.receiver else { return "Connect to a Chromecast or Google TV first" }
        let base = CastAddress.base(tvAddress: app.tvAddress, hubAddress: app.address)
        if let problem = CastAddress.problem(base: base) { return problem }
        preparing = true
        defer { preparing = false }
        let hub = app.hub
        let user = app.userId
        let outbox = outbox ?? PlaybackOutbox(hub: hub)
        let body = CastPlan.prepareBody(current, positionMillis: positionMillis, startMode: startMode,
                                        device: PlaybackDeviceInfo.device())
        let prepared: PlaybackPrepareResponse
        let grant: PlaybackGrant
        do throws(HubFailure) {
            prepared = try await hub.fetch(HubEndpoints.preparePlayback(itemId: current.item.id, body: body, user: user),
                                           as: PlaybackPrepareResponse.self)
        } catch {
            return error.message
        }
        do throws(HubFailure) {
            grant = try await hub.fetch(HubEndpoints.playbackGrant(sessionId: prepared.sessionId, user: user), as: PlaybackGrant.self)
        } catch {
            outbox.close(session: prepared.sessionId, user: user)
            return error.message
        }
        // No picture: the hub's artwork needs the token, which the TV never has.
        var load: CastLoad
        switch CastPlan.load(prepared, grant: grant, base: base) {
        case .success(let made): load = made
        case .failure(let failure):
            outbox.close(session: prepared.sessionId, user: user)
            return failure.message
        }
        // At the same moment: the hub puts a title's first half-minute back to
        // 0:00, which a move to the TV must not.
        load.startMillis = CastPlan.startMillis(planned: prepared.positionMillis, asked: positionMillis, mode: startMode)
        guard await receiver.load(load) else {
            outbox.close(session: prepared.sessionId, user: user)
            return "The TV could not open this stream"
        }
        // The TV has it: an earlier TV session ends, this one begins.
        if isActive { finish(stopReceiver: false, finished: false) }
        self.receiver = receiver
        self.hub = hub
        self.outbox = outbox
        self.user = user
        plan = prepared
        deviceName = receiver.deviceName
        self.positionMillis = load.startMillis
        state = .playing
        ended = nil
        stopping = false
        seekTarget = nil
        loadedAt = .now
        reporter = PlaybackReporter()
        outbox.event(reporter.playingChanged(true, positionMillis: load.startMillis, nowMillis: elapsedMillis()),
                     session: prepared.sessionId, user: user)
        startPolling()
        return nil
    }

    // MARK: The remote

    func togglePlay() {
        guard let receiver, isActive else { return }
        if state == .playing { receiver.pause() } else { receiver.play() }
        step()
    }

    func seek(to target: Int64) {
        guard let receiver, let plan else { return }
        let clamped = PlaybackRules.clampSeek(target, durationMillis: plan.durationMillis)
        receiver.seek(to: clamped)
        positionMillis = clamped
        seekTarget = (clamped, .now)
        outbox?.event(reporter.seeked(positionMillis: clamped, paused: state != .playing), session: plan.sessionId, user: user)
    }

    /// Off, or a text subtitle the TV shows; another is not the TV's to show.
    func selectSubtitle(_ index: Int?) {
        guard var plan, let receiver else { return }
        let allowed = index.flatMap { wanted in CastPlan.textSubtitles(plan).contains { $0.index == wanted } ? wanted : nil }
        receiver.setActiveTracks(allowed.map { [$0 + CastPlan.trackIdOffset] } ?? [])
        plan.selectedSubtitleIndex = allowed
        self.plan = plan
    }

    /// Stop on TV: the receiver stops, the session reports where it was and closes.
    func stop() {
        finish(stopReceiver: true, finished: false)
    }

    // MARK: Following the TV

    private func startPolling() {
        poll?.cancel()
        poll = Task { [weak self] in
            while !Task.isCancelled {
                self?.step()
                try? await Task.sleep(for: .milliseconds(500))
            }
        }
    }

    /// Twice a second: where the TV is, what it is doing, and what the hub is told.
    private func step() {
        guard let receiver, let plan, let outbox else { return }
        // The TV let go (the Cast button's own Stop casting, the TV turned off).
        guard CastCenter.shared.receiver === receiver else {
            finish(stopReceiver: false, finished: false)
            return
        }
        let now = receiver.state
        if case .idle(let finished) = now, finished || ContinuousClock.now - loadedAt > .seconds(6) {
            // Ended on the TV: its end, or stopped there.
            if finished { positionMillis = plan.durationMillis }
            finish(stopReceiver: false, finished: finished)
            return
        }
        if let target = seekTarget {
            if ContinuousClock.now - target.at > .seconds(2) || abs(receiver.positionMillis - target.millis) < 1_500 { seekTarget = nil }
        }
        if seekTarget == nil, receiver.positionMillis != positionMillis { positionMillis = max(0, receiver.positionMillis) }
        let was = state
        if now != state { state = now }
        let millis = elapsedMillis()
        if was != .playing, now == .playing {
            outbox.event(reporter.playingChanged(true, positionMillis: positionMillis, nowMillis: millis),
                         session: plan.sessionId, user: user)
        } else if was == .playing, now == .paused {
            outbox.event(reporter.playingChanged(false, positionMillis: positionMillis, nowMillis: millis),
                         session: plan.sessionId, user: user)
        }
        outbox.event(reporter.tick(playing: now == .playing, positionMillis: positionMillis, nowMillis: millis),
                     session: plan.sessionId, user: user)
    }

    /// The end of the TV's session: stopped where it was (or at the end), then closed.
    private func finish(stopReceiver: Bool, finished: Bool) {
        guard !stopping, let plan else { return }
        stopping = true
        poll?.cancel()
        poll = nil
        if stopReceiver { receiver?.stop() }
        let position = positionMillis
        if let outbox {
            let stop = finished ? reporter.reachedEnd(durationMillis: plan.durationMillis)
                : reporter.stop(positionMillis: position)
            outbox.event(stop, session: plan.sessionId, user: user)
            outbox.close(session: plan.sessionId, user: user)
        }
        self.plan = nil
        receiver = nil
        state = .idle(finished: finished)
        ended = (finished, position)
        endings += 1
        stopping = false
    }

    /// Everything sent so far, for a caller that must wait for it.
    var tail: Task<Void, Never>? { outbox?.tail }

    private func elapsedMillis() -> Int64 {
        let elapsed = origin.duration(to: .now)
        return elapsed.components.seconds * 1_000 + elapsed.components.attoseconds / 1_000_000_000_000_000
    }
}
