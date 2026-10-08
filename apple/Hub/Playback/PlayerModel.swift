import AVFoundation
import AVKit
import HubKit
import MediaPlayer
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
/// `DELETE` the session. Leaving is the only way out: Back, the next episode,
/// quitting, closing the Mac's window, and on iPhone and iPad going to the
/// background without picture in picture. So a session is never left for the
/// hub's 30-minute cleanup to save.
///
/// A title downloaded for this profile plays from its file instead, wherever
/// it is opened (#5; Android's local-first playback): nothing is sent to the
/// hub for it, its tracks are the file's own and chosen here, and its watch is
/// kept on the device every fifteen seconds, on a pause and on leaving, then
/// sent with the offline sync (`OfflineLibrary`).
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
    /// What the subtitles the app draws say now (`PlaybackChoices.drawnSubtitle`).
    private(set) var subtitleLines: [String] = []
    /// The live delay of those subtitles, kept per profile and series or film.
    private(set) var subtitleOffsetMillis: Int64 = 0
    /// How subtitles look, in every video.
    private(set) var subtitleLook = PlaybackMemory.look()
    private(set) var speed: Float = PlaybackEnhancements.defaultSpeed
    /// The bitrate cap This video chose; 0 is the original.
    private(set) var maxBitrate = 0
    /// How the picture fills the player, This video › Aspect (#33): Fit
    /// until changed, for as long as the player is open.
    private(set) var aspect = PlaybackAspect.standard
    /// The segment a Skip button is for now.
    private(set) var skipSegment: PlaybackSegment?
    /// M turned the player's sound off (#33).
    private(set) var muted = false
    /// A track, quality or version change on its way to the hub.
    private(set) var applying = false
    /// A short word about something that did not work, shown for a moment.
    private(set) var notice: String?
    private(set) var pipPossible = false
    private(set) var pipActive = false
    /// Whether this device has picture in picture at all (the iPhone
    /// simulator has not); where it has not, the button is not offered.
    let pipSupported = AVPictureInPictureController.isPictureInPictureSupported()
    /// The video plays on an AirPlay receiver, not in this window.
    private(set) var externalActive = false
    /// The video plays on a TV through Google Cast (#44): the chrome is its
    /// remote, and the session is the TV's (`CastPlayback`).
    private(set) var casting = false
    /// The picture's own size, for where drawn subtitles go when it is
    /// letterboxed (a phone held upright).
    private(set) var presentationSize = CGSize.zero
    /// Which kind the Audio & subtitles panel starts on: the one changed last.
    private(set) var menu = PlayerMenuState()
    /// The panel the debug tour has open (`HUB_PLAY_TOUR`), for PlayerView to
    /// show. Outside `#if DEBUG`: @Observable does not track a property inside
    /// one. Nothing sets it in a release build.
    private(set) var debugPanel: String?
    /// The length AVPlayer found, for a plan that names none.
    private var itemDuration: Int64 = 0

    var isOpen: Bool { request != nil }

    /// Playing a download from its file, not a session on the hub.
    var isOffline: Bool { plan.map { OfflinePlayback.isOffline($0.sessionId) } ?? false }

    var durationMillis: Int64 {
        if let plan, plan.durationMillis > 0 { return plan.durationMillis }
        return itemDuration
    }

    /// In order, inside the video, each with a name.
    var chapters: [PlaybackChapter] {
        PlaybackEnhancements.chapters(plan?.chapters ?? [], durationMillis: durationMillis)
    }

    /// The selected subtitles are a text file the app draws, so they can be
    /// moved in time; a picture track is burned in by the hub.
    var drawsSubtitles: Bool { plan.flatMap(PlaybackChoices.drawnSubtitle) != nil }

    /// Settings › Playback (#33), read as each video opens: when the next
    /// episode's card comes, and whether intros and recaps skip themselves.
    @ObservationIgnored private var nextTiming = NextEpisodeTiming.credits
    @ObservationIgnored private var autoSkipIntro = false
    /// The segments skipped by themselves in this video: going back into one brings its button.
    @ObservationIgnored private var autoSkipped: Set<String> = []
    /// The subtitles C turned off in this video, for C to turn on again.
    @ObservationIgnored private var subtitlesTurnedOff: Int?

    @ObservationIgnored let player = AVPlayer()
    @ObservationIgnored private var hub: HubClient?
    /// What a move to the TV needs: the hub, the profile, the TV's address.
    @ObservationIgnored private weak var app: AppModel?
    /// Moved back here, or stopped on the TV, while the TV stays connected:
    /// the video is not sent there again until it connects anew.
    @ObservationIgnored private var stayHere = false
    @ObservationIgnored private var outbox: PlaybackOutbox?
    @ObservationIgnored private var baseURL = ""
    /// The profile that opened the session; every later call is for it.
    @ObservationIgnored private var user = ""
    /// A real hub's sessions are written down until closed (`OpenSessions`);
    /// the demo hub's need no closing.
    @ObservationIgnored private var recordsSessions = false
    @ObservationIgnored private var reporter = PlaybackReporter()
    /// What this profile last chose for the title's series or film.
    @ObservationIgnored private var selection = PlaybackSelection()
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
    /// Where the next item starts instead of its plan's position: a track or
    /// quality change goes on from where it was, which the hub would turn into
    /// 0:00 in a title's first 30 seconds.
    @ObservationIgnored private var startOverride: Int64?
    @ObservationIgnored private var playAfterLoad = true
    @ObservationIgnored private var subtitleTimeline: SubtitleTimeline<String>?
    @ObservationIgnored private var subtitleKey = ""
    @ObservationIgnored private var subtitleTask: Task<Void, Never>?
    @ObservationIgnored private var noticeTask: Task<Void, Never>?
    @ObservationIgnored private var subtitleClock: Any?
    @ObservationIgnored private var endObserver: NSObjectProtocol?
    @ObservationIgnored private var quitObserver: NSObjectProtocol?
    @ObservationIgnored private var pip: AVPictureInPictureController?
    @ObservationIgnored private var pipObserver: PictureInPictureObserver?
    @ObservationIgnored private var inBackground = false
    /// The lock screen's picture of what plays (#33).
    @ObservationIgnored private var nowPlayingArt: MPMediaItemArtwork?
    @ObservationIgnored private var nowPlayingArtPath = ""
    @ObservationIgnored private let origin = ContinuousClock.now
    /// When a download's watch was last kept on the device.
    @ObservationIgnored private var offlineSavedAt = ContinuousClock.now
    #if os(iOS)
    @ObservationIgnored private var backgroundWork: UIBackgroundTaskIdentifier = .invalid
    #endif
    #if DEBUG
    @ObservationIgnored private var debugFromEndUsed = false
    @ObservationIgnored private var debugTourStarted = false
    #endif

    init() {
        // AirPlay sends the video itself to the receiver: the grant's
        // addresses need no token, so the receiver can fetch them.
        player.allowsExternalPlayback = true
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
        // Ten times a second on the video's own clock: drawn subtitles follow
        // the picture, and stand still with it.
        subtitleClock = player.addPeriodicTimeObserver(forInterval: CMTime(value: 1, timescale: 10), queue: .main) { [weak self] time in
            let millis = time.isNumeric ? Int64((CMTimeGetSeconds(time) * 1_000).rounded()) : 0
            MainActor.assumeIsolated { self?.showSubtitles(at: millis) }
        }
        NowPlaying.shared.register(.video, self)
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
        self.app = app
        stayHere = false
        hub = app.hub
        baseURL = app.address
        user = app.userId
        recordsSessions = !app.isDemo
        outbox = outbox ?? PlaybackOutbox(hub: app.hub)
        backdrop = request.backdrop
        opens += 1
        aspect = .standard
        // Settings › Subtitles may have changed it since the window began.
        subtitleLook = PlaybackMemory.look()
        speed = PlaybackEnhancements.defaultSpeed
        player.defaultRate = speed
        startSession()
        activateAudio()
        // The lock screen, Control Center and the media keys are the video's while it is open (#33).
        NowPlaying.shared.take(.video, commands: nowPlayingCommands)
        // The TVs on the network, for the Cast button (#44).
        CastCenter.shared.look()
        poll = Task { [weak self] in
            while !Task.isCancelled {
                self?.step()
                try? await Task.sleep(for: .milliseconds(250))
            }
        }
        // Playing on the TV already: the player opens as its remote.
        if let tv = CastPlayback.shared.plan, tv.item.id == request.itemId {
            becomeRemote(tv)
            return
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
        if casting {
            // Leaving the remote: the TV plays on, and reports as it plays.
            casting = false
            plan = nil
        }
        poll?.cancel()
        poll = nil
        #if os(iOS)
        restoreBrightness()
        #endif
        if let pip, pip.isPictureInPictureActive { pip.stopPictureInPicture() }
        pip = nil
        pipObserver = nil
        pipActive = false
        pipPossible = false
        endSession()
        player.replaceCurrentItem(with: nil)
        request = nil
        nowPlayingArt = nil
        nowPlayingArtPath = ""
        NowPlaying.shared.release(.video)
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
        if casting, var tv = CastPlayback.shared.plan {
            // On the TV, from its beginning, with its own tracks.
            tv.item = target
            tv.selectedMediaSourceId = ""
            tv.selectedAudioIndex = nil
            tv.selectedSubtitleIndex = nil
            if !target.seriesId.isEmpty { backdrop = "/v1/img/jf/\(target.seriesId)/Backdrop" }
            request = PlayRequest(itemId: target.id, mode: .restart, title: PlayerLabels.title(target), backdrop: backdrop)
            return moveToTV(tv, at: 0, mode: .restart)
        }
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
        startOverride = nil
        playAfterLoad = true
        maxBitrate = 0
        skipSegment = nil
        nextTiming = PlaybackSettings.nextTiming
        autoSkipIntro = PlaybackSettings.autoSkipIntro
        autoSkipped = []
        subtitlesTurnedOff = nil
        applying = false
        subtitleKey = ""
        subtitleTask?.cancel()
        subtitleTimeline = nil
        subtitleLines = []
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
        guard let plan else { return }
        if OfflinePlayback.isOffline(plan.sessionId) {
            rememberOffline(completed: atEnd)
            OfflineLibrary.shared.syncSoon()
            self.plan = nil
            return
        }
        guard let outbox else { return }
        outbox.event(reporter.stop(positionMillis: positionMillis, muted: player.isMuted), session: plan.sessionId, user: user)
        outbox.close(session: plan.sessionId, user: user)
        self.plan = nil
    }

    private func prepare(itemId: String, mode: PlaybackStartMode, series: Bool, generation: Int) async {
        guard let hub else { return }
        let user = user
        let offline = OfflineLibrary.shared
        do throws(HubFailure) {
            var itemId = itemId
            var mode = mode
            if series {
                do throws(HubFailure) {
                    let target = try await hub.fetch(HubEndpoints.seriesPlayTarget(seriesId: itemId), as: SeriesPlayTarget.self)
                    itemId = target.item.id
                    mode = DetailLines.startMode(target)
                } catch {
                    // Away from the hub, a downloaded series goes on from its downloads.
                    guard error.kind != .cancelled, let local = offline.playTarget(seriesId: itemId, userId: user) else { throw error }
                    itemId = local.row.itemId
                    mode = .resume
                }
            }
            // Downloaded for this profile: from its file, with nothing asked of the hub.
            if let local = offline.localPlan(itemId: itemId, mode: mode, userId: user) {
                loadOffline(local, generation: generation)
                return
            }
            let body = PlaybackPrepareBody(startMode: mode, device: PlaybackDeviceInfo.device(),
                                           capabilities: PlaybackDeviceInfo.capabilities())
            var plan = try await hub.fetch(HubEndpoints.preparePlayback(itemId: itemId, body: body, user: user),
                                           as: PlaybackPrepareResponse.self)
            // Open on the hub from now on: written down, so a crash or a
            // forced quit cannot leave it for the hub's 30-minute cleanup.
            if recordsSessions { PlaybackMemory.sessionOpened(plan.sessionId, user: user) }
            selection = PlaybackMemory.selection(user: user, scope: PlaybackChoices.scope(plan.item))
            subtitleOffsetMillis = selection.subtitleOffsetMillis
            // This profile's last audio and subtitles for the series or film,
            // asked for before the first frame, as Android does.
            if let wanted = wantedTracks(plan) {
                let choose = PlaybackRules.selection(plan, audioStreamIndex: wanted.audio,
                                                     subtitleStreamIndex: wanted.subtitle)
                if let chosen = try? await hub.fetch(HubEndpoints.selectPlayback(sessionId: plan.sessionId, body: choose, user: user),
                                                     as: PlaybackPrepareResponse.self) {
                    plan = chosen
                }
            }
            try await load(plan, generation: generation)
        } catch {
            #if DEBUG
            NSLog("playback: opening %@ failed: %@ (%@)", itemId, error.message, String(describing: error.status))
            #endif
            guard generation == self.generation else { return }
            phase = .failed(error.kind == .notFound ? "This title is no longer in Jellyfin." : error.message)
        }
    }

    /// Plays a download from its file. This profile's last audio and subtitle
    /// languages for the series or film are chosen before the first frame, as
    /// for a stream; the file's own options are selected when it is ready.
    private func loadOffline(_ local: PlaybackPrepareResponse, generation: Int) {
        guard generation == self.generation else { return }
        var plan = local
        selection = PlaybackMemory.selection(user: user, scope: PlaybackChoices.scope(plan.item))
        subtitleOffsetMillis = selection.subtitleOffsetMillis
        if let wanted = wantedTracks(plan) {
            if let audio = wanted.audio, plan.audioTracks.contains(where: { $0.index == audio }) { plan.selectedAudioIndex = audio }
            let subtitle = wanted.subtitle ?? -1
            plan.selectedSubtitleIndex = plan.subtitleTracks.contains { $0.index == subtitle } ? subtitle : nil
        }
        self.plan = plan
        planShown()
        guard let url = URL(string: plan.mediaUrl) else {
            phase = .failed("This download cannot be found on this device.")
            return
        }
        startedItem = nil
        failedItem = nil
        player.replaceCurrentItem(with: AVPlayerItem(url: url))
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
        planShown()
        guard let url = URL(string: grant.address(base: baseURL)) else {
            throw HubFailure(.badResponse, message: "The hub sent an address that cannot be played")
        }
        startedItem = nil
        failedItem = nil
        let item = AVPlayerItem(url: url)
        player.replaceCurrentItem(with: item)
        prepareSubtitles(plan)
    }

    // MARK: Playing

    func togglePlay() {
        if casting {
            CastPlayback.shared.togglePlay()
            isPlaying = CastPlayback.shared.isPlaying
            updateNowPlaying()
            return
        }
        if isPlaying || player.timeControlStatus == .waitingToPlayAtSpecifiedRate {
            player.pause()
            isPlaying = false
        } else {
            if atEnd { seek(to: 0) }
            player.play()
            isPlaying = true
            // Played again after the audiobook: the lock screen is the video's again.
            NowPlaying.shared.take(.video, commands: nowPlayingCommands)
        }
        updateNowPlaying()
    }

    func seek(by deltaMillis: Int64) { seek(to: positionMillis + deltaMillis) }

    func seek(to target: Int64) {
        if casting {
            CastPlayback.shared.seek(to: target)
            positionMillis = CastPlayback.shared.positionMillis
            return
        }
        guard plan != nil, player.currentItem != nil else { return }
        let clamped = PlaybackRules.clampSeek(target, durationMillis: durationMillis)
        positionMillis = clamped
        seekTarget = clamped
        showSubtitles(at: clamped)
        player.seek(to: CMTime(value: clamped, timescale: 1_000), toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] finished in
            Task { @MainActor in self?.seekFinished(at: clamped, finished: finished) }
        }
    }

    /// Past the intro (or recap, preview, ad) the Skip button is for.
    func skip() {
        guard let segment = skipSegment else { return }
        skipSegment = nil
        seek(to: segment.endMillis)
    }

    func setAspect(_ value: PlaybackAspect) {
        aspect = value
    }

    func setSpeed(_ value: Float) {
        speed = value
        player.defaultRate = value
        if isPlaying { player.rate = value }
        updateNowPlaying()
    }

    private func seekFinished(at target: Int64, finished: Bool) {
        guard seekTarget == target else { return }
        seekTarget = nil
        guard finished, let plan, let outbox else { return }
        if atEnd && !PlaybackRules.reachedNaturalEnd(positionMillis: target, durationMillis: durationMillis) {
            atEnd = false
        }
        if !isOffline {
            outbox.event(reporter.seeked(positionMillis: target, paused: !isPlaying, muted: player.isMuted),
                         session: plan.sessionId, user: user)
        }
        updateUpNext()
    }

    // MARK: A download's watch

    /// Kept here every fifteen seconds while it plays and on each pause; the
    /// hub hears of it with the offline sync.
    private func reportOffline(_ status: AVPlayer.TimeControlStatus) {
        switch status {
        case .playing:
            if !reportedPlaying {
                reportedPlaying = true
                offlineSavedAt = .now
            } else if offlineSavedAt.duration(to: .now) >= .seconds(15) {
                rememberOffline(completed: false)
            }
        case .paused where reportedPlaying:
            reportedPlaying = false
            // Reaching the end pauses too; the end keeps itself.
            if !atEnd { rememberOffline(completed: false) }
        default:
            break
        }
    }

    /// Where a download is, kept for this profile once it has played.
    private func rememberOffline(completed: Bool) {
        guard let plan, OfflinePlayback.isOffline(plan.sessionId), startedItem != nil else { return }
        offlineSavedAt = .now
        let duration = durationMillis
        guard duration > 0 else { return }
        let position = completed ? duration : positionMillis
        OfflineLibrary.shared.remember(itemId: plan.item.id, userId: user, positionMillis: position, durationMillis: duration,
                                       completed: completed || ResumeRules.isFinished(positionMillis: position,
                                                                                      durationMillis: duration))
    }

    /// Four times a second while the player is open: what AVPlayer is doing,
    /// what the chrome shows, what the hub is told.
    private func step() {
        if casting { return stepRemote() }
        let possible = pip?.isPictureInPicturePossible ?? false
        if possible != pipPossible {
            pipPossible = possible
            #if DEBUG
            NSLog("playback: picture in picture %@", possible ? "possible" : "not possible")
            #endif
        }
        if player.isExternalPlaybackActive != externalActive { externalActive = player.isExternalPlaybackActive }
        guard let item = player.currentItem else { return }
        if item.presentationSize != presentationSize, item.presentationSize.width > 0 {
            presentationSize = item.presentationSize
        }
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
        // Each only when it changed: an @Observable property tells its readers
        // on every assignment, equal or not, and this runs four times a second.
        if item.duration.isNumeric, millis(item.duration) != itemDuration { itemDuration = millis(item.duration) }
        if let range = item.loadedTimeRanges.last?.timeRangeValue, millis(CMTimeRangeGetEnd(range)) != bufferedMillis {
            bufferedMillis = millis(CMTimeRangeGetEnd(range))
        }
        let status = player.timeControlStatus
        if (status == .waitingToPlayAtSpecifiedRate) != isBuffering { isBuffering = status == .waitingToPlayAtSpecifiedRate }
        let wasPlaying = isPlaying
        if (status == .playing) != isPlaying { isPlaying = status == .playing }
        let time = player.currentTime()
        if seekTarget == nil, time.isNumeric, max(0, millis(time)) != positionMillis { positionMillis = max(0, millis(time)) }
        let prompt = plan.flatMap { PlaybackEnhancements.skipPrompt($0.segments, positionMillis: positionMillis) }
        if prompt != skipSegment { skipSegment = prompt }
        // Skip intros automatically (#33): once the video plays from where it
        // starts, an intro or recap playing skips itself, once.
        if startedItem == id, seekTarget == nil,
           let skip = UpNext.autoSkip(prompt, enabled: autoSkipIntro, skipped: autoSkipped) {
            autoSkipped.insert(UpNext.skipKey(skip))
            skipSegment = nil
            seek(to: skip.endMillis)
            show(notice: UpNext.skippedNotice(skip))
        }
        report(status)
        updateUpNext()
        if isPlaying != wasPlaying {
            updateNowPlaying()
        } else {
            NowPlaying.shared.publishPosition(.video, elapsedSeconds: Double(positionMillis) / 1_000,
                                              rate: isPlaying ? Double(speed) : 0)
        }
    }

    /// The item is ready: to its start position, then playing.
    private func start(_ item: AVPlayerItem) {
        phase = .playing
        defer { updateNowPlaying() }
        var startAt = startOverride ?? plan?.positionMillis ?? 0
        startOverride = nil
        #if DEBUG
        // The first title only: the next episode starts at its beginning.
        if let fromEnd = Self.debugSeconds("HUB_PLAY_FROM_END"), item.duration.isNumeric, !debugFromEndUsed {
            debugFromEndUsed = true
            startAt = max(0, millis(item.duration) - Int64(fromEnd * 1_000))
        }
        startDebugTour()
        #endif
        applyAudioChoice(to: item)
        if isOffline { applyLegibleChoice(to: item) }
        castIfConnected()
        let play = playAfterLoad
        playAfterLoad = true
        guard startAt > 0 else {
            if play { player.play() }
            return
        }
        // A constant for the closure: Xcode 27's Swift will not send a `var`
        // the main actor's task could still see changing.
        let target = startAt
        positionMillis = target
        seekTarget = target
        player.seek(to: CMTime(value: target, timescale: 1_000), toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] _ in
            Task { @MainActor in
                // Where playback starts is not a seek the person made.
                if self?.seekTarget == target { self?.seekTarget = nil }
                if play { self?.player.play() }
            }
        }
    }

    /// A file AVPlayer cannot open is asked for again as a conversion once,
    /// from where it was, as Android does; after that the failure is shown.
    private func failed(_ error: (any Error)?) {
        guard let plan, let hub else { return }
        let reason = error?.localizedDescription ?? "This video could not be played."
        guard !isOffline else {
            // A download has no conversion to fall back on.
            phase = .failed("This download could not be played. Remove it and download it again. (\(reason))")
            return
        }
        guard !fallbackTried, plan.playMethod.lowercased() != "transcode" else {
            phase = .failed(reason)
            return
        }
        fallbackTried = true
        phase = .opening
        let at = max(positionMillis, plan.positionMillis)
        let body = PlaybackRules.selection(plan, positionMillis: at, forceTranscode: true)
        let request = HubEndpoints.selectPlayback(sessionId: plan.sessionId, body: body, user: user)
        let generation = generation
        Task {
            do {
                let converted = try await hub.fetch(request, as: PlaybackPrepareResponse.self)
                startOverride = at
                try await load(converted, generation: generation)
            } catch {
                if generation == self.generation { phase = .failed(reason) }
            }
        }
    }

    private func report(_ status: AVPlayer.TimeControlStatus) {
        if isOffline {
            reportOffline(status)
            return
        }
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
        if isOffline {
            rememberOffline(completed: true)
        } else {
            outbox.event(reporter.reachedEnd(durationMillis: durationMillis, muted: player.isMuted),
                         session: plan.sessionId, user: user)
        }
        // The card counts down to the next episode, even after Watch credits.
        if let next = plan.nextItem {
            upNextDismissed = false
            if upNext == nil { upNext = UpNextCard(item: next, fraction: 0) }
            startCountdown()
        }
    }

    // MARK: Google Cast (#44)

    /// The TV connected, or was already when the video became ready: the
    /// video moves there at this moment, unless it was just brought back here.
    func castIfConnected() {
        guard CastCenter.shared.receiver != nil, !stayHere, !casting, !isOffline, phase == .playing,
              let plan, !CastPlayback.shared.preparing else { return }
        moveToTV(plan, at: positionMillis, mode: .resume)
    }

    /// A new connection: whatever was decided for the last one is forgotten.
    func castConnectionChanged(_ connection: CastConnection) {
        if case .connected = connection {
            stayHere = false
            castIfConnected()
        }
    }

    /// Prepared for the TV and loaded there; once the TV has it, what played
    /// here (or the TV's earlier session) ends and the player is its remote.
    private func moveToTV(_ current: PlaybackPrepareResponse, at position: Int64, mode: PlaybackStartMode) {
        guard let app, !CastPlayback.shared.preparing else { return }
        show(notice: CastPresentation.preparing)
        let generation = generation
        Task {
            let error = await CastPlayback.shared.transfer(current, positionMillis: position, startMode: mode, app: app)
            guard request != nil else { return }
            if let error {
                show(notice: error)
                return
            }
            guard let tv = CastPlayback.shared.plan else { return }
            // Left for another title meanwhile: the TV plays on without this player.
            guard generation == self.generation || casting else { return }
            becomeRemote(tv)
            show(notice: CastPresentation.playingOn(CastPlayback.shared.deviceName))
        }
    }

    /// The player as the TV's remote: this device's session ends (stopped
    /// where it was, then closed) and its picture goes.
    private func becomeRemote(_ tv: PlaybackPrepareResponse) {
        if !casting {
            if pipActive { pip?.stopPictureInPicture() }
            endSession()
            player.replaceCurrentItem(with: nil)
        }
        casting = true
        plan = tv
        phase = .playing
        readyForDisplay = false
        upNext = nil
        skipSegment = nil
        subtitleTask?.cancel()
        subtitleTimeline = nil
        subtitleKey = ""
        subtitleLines = []
        positionMillis = CastPlayback.shared.positionMillis
        isPlaying = CastPlayback.shared.isPlaying
        planShown()
    }

    /// Four times a second while casting: the TV's place and state for the
    /// chrome; when the TV stopped (its end, Stop casting, the TV gone), the
    /// player follows.
    private func stepRemote() {
        let cast = CastPlayback.shared
        guard let tv = cast.plan else { return castEnded() }
        if tv != plan { plan = tv }
        if cast.positionMillis != positionMillis { positionMillis = cast.positionMillis }
        let playing = cast.isPlaying
        let waiting = CastPresentation.waiting(cast.state)
        if waiting != isBuffering { isBuffering = waiting }
        if playing != isPlaying {
            isPlaying = playing
            updateNowPlaying()
        } else {
            NowPlaying.shared.publishPosition(.video, elapsedSeconds: Double(positionMillis) / 1_000, rate: isPlaying ? 1 : 0)
        }
    }

    /// The TV stopped on its own: at the video's end the player closes; let
    /// go of (Stop casting, the TV turned off), it goes on here, paused where
    /// the TV was.
    private func castEnded() {
        casting = false
        let ended = CastPlayback.shared.ended
        guard ended?.finished != true, let itemId = plan?.item.id else {
            plan = nil
            close()
            return
        }
        resumeHere(itemId: itemId, at: ended?.positionMillis ?? positionMillis, play: false)
    }

    /// Move to this iPhone or iPad: the TV stops and the video goes on here where it was.
    func moveHere() {
        guard casting, let itemId = plan?.item.id else { return }
        let at = CastPlayback.shared.positionMillis
        casting = false
        CastPlayback.shared.stop()
        resumeHere(itemId: itemId, at: at, play: true)
    }

    /// Stop on TV: the TV stops and the player closes.
    func stopOnTV() {
        guard casting else { return }
        casting = false
        CastPlayback.shared.stop()
        plan = nil
        close()
    }

    private func resumeHere(itemId: String, at position: Int64, play: Bool) {
        stayHere = true
        plan = nil
        startSession()
        startOverride = position
        playAfterLoad = play
        positionMillis = position
        activateAudio()
        let generation = generation
        Task { await prepare(itemId: itemId, mode: .resume, series: false, generation: generation) }
    }

    // MARK: The lock screen, Control Center, AirPods and the media keys (#33)

    /// Next and previous only where the plan has them.
    private var nowPlayingCommands: NowPlaying.Commands {
        NowPlaying.Commands(next: plan?.nextItem != nil, previous: plan?.previousItem != nil)
    }

    /// A new plan: its episode's neighbours for the commands, its title, and its picture.
    private func planShown() {
        NowPlaying.shared.update(.video, commands: nowPlayingCommands)
        updateNowPlaying()
        loadNowPlayingArt()
    }

    /// What plays, for the lock screen: an episode's code and name over its
    /// series, a film's name; how long, where, how fast; and its picture.
    private func updateNowPlaying() {
        guard let plan else { return }
        let name = PlayerLabels.title(plan.item)
        let episode = PlayerLabels.subtitle(plan.item)
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: episode.isEmpty ? name : episode,
            MPMediaItemPropertyArtist: episode.isEmpty ? "" : name,
            MPMediaItemPropertyPlaybackDuration: Double(durationMillis) / 1_000,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: Double(positionMillis) / 1_000,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? Double(speed) : 0,
            MPNowPlayingInfoPropertyDefaultPlaybackRate: Double(speed),
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.video.rawValue,
        ]
        if let nowPlayingArt { info[MPMediaItemPropertyArtwork] = nowPlayingArt }
        NowPlaying.shared.publish(.video, info: info, playing: isPlaying)
    }

    /// The title's wide picture, as the player shows it while it opens.
    private func loadNowPlayingArt() {
        guard let hub, !backdrop.isEmpty, backdrop != nowPlayingArtPath else { return }
        let path = backdrop
        nowPlayingArtPath = path
        Task {
            guard let data = try? await hub.image(HubEndpoints.sized(path, width: 640)),
                  nowPlayingArtPath == path, let art = NowPlaying.artwork(data) else { return }
            nowPlayingArt = art
            updateNowPlaying()
        }
    }

    // MARK: Volume and brightness (the picture's up-and-down drags, #24)

    /// The player's own volume, 0…1: iOS lets an app set no other.
    var volume: Double { Double(player.volume) }

    func setVolume(_ value: Double) {
        player.volume = Float(min(max(value, 0), 1))
        // Turned up, it is heard again.
        if value > 0, player.isMuted {
            player.isMuted = false
            muted = false
        }
    }

    // MARK: The keyboard and the Playback menu (#33)

    /// M: the player's sound off, or back on.
    func toggleMute() {
        player.isMuted.toggle()
        muted = player.isMuted
        show(notice: muted ? "Muted" : "Sound on")
    }

    /// C: subtitles off, or back on as they were (`PlaybackChoices.toggledSubtitle`).
    func toggleSubtitles() {
        guard let plan else { return }
        guard let target = PlaybackChoices.toggledSubtitle(plan, last: subtitlesTurnedOff,
                                                           language: selection.subtitleLanguage) else {
            return show(notice: "This video has no subtitles")
        }
        if target < 0 { subtitlesTurnedOff = plan.selectedSubtitleIndex }
        chooseSubtitle(target)
        show(notice: PlayerLabels.subtitlesNotice(plan.subtitleTracks.first { $0.index == target }))
    }

    /// [ and ]: a speed down or up.
    func stepSpeed(faster: Bool) {
        setSpeed(PlayerKeyboard.speed(speed, faster: faster))
        show(notice: PlayerLabels.speedNotice(speed))
    }

    /// What S does now: "Skip intro" (or recap, preview, ad), "Skip credits"
    /// while they play or the next episode's card is up; nil otherwise.
    var skipTitle: String? {
        if let skipSegment { return UpNext.skipLabel(skipSegment.type) }
        return upNext != nil || creditsPlaying != nil ? "Skip credits" : nil
    }

    /// S: past the intro its button is for; at the credits, the next episode,
    /// or the credits' end where there is none.
    func skipKey() {
        if skipSegment != nil { return skip() }
        if upNext != nil, plan?.nextItem != nil { return playNext() }
        guard let credits = creditsPlaying else { return }
        if plan?.nextItem != nil { playNext() } else { seek(to: credits.endMillis) }
    }

    private var creditsPlaying: PlaybackSegment? {
        plan?.segments.first {
            $0.type.caseInsensitiveCompare("Outro") == .orderedSame
                && positionMillis >= $0.startMillis && positionMillis < $0.endMillis
        }
    }

    #if os(iOS)
    /// The screen's brightness before the first drag, put back on leaving, so
    /// the setting lasts only while watching, as the Pocket's dimming does.
    @ObservationIgnored private var brightnessBefore: Double?

    private var screen: UIScreen? {
        (UIApplication.shared.connectedScenes.first { $0 is UIWindowScene } as? UIWindowScene)?.screen
    }

    var brightness: Double { Double(screen?.brightness ?? 0.5) }

    func setBrightness(_ value: Double) {
        guard let screen else { return }
        if brightnessBefore == nil { brightnessBefore = Double(screen.brightness) }
        screen.brightness = CGFloat(min(max(value, 0), 1))
    }

    private func restoreBrightness() {
        guard let before = brightnessBefore else { return }
        brightnessBefore = nil
        screen?.brightness = CGFloat(before)
    }
    #endif

    // MARK: Tracks, quality and version (This video, Audio & subtitles)

    func chooseAudio(_ track: PlaybackTrack) {
        menu.selectTrackTab("audio")
        guard track.index != plan?.selectedAudioIndex else { return }
        if casting, var tv = CastPlayback.shared.plan {
            // The TV stream carries one audio track: it moves again with the other.
            tv.selectedAudioIndex = track.index
            return moveToTV(tv, at: positionMillis, mode: .resume)
        }
        if isOffline {
            chooseOffline { $0.selectedAudioIndex = track.index }
            return
        }
        change(PlaybackSelectBody(positionMillis: 0, audioStreamIndex: track.index))
    }

    /// -1 turns subtitles off.
    func chooseSubtitle(_ index: Int) {
        menu.selectTrackTab("subtitles")
        guard index != (plan?.selectedSubtitleIndex ?? -1) else { return }
        if casting {
            CastPlayback.shared.selectSubtitle(index < 0 ? nil : index)
            plan = CastPlayback.shared.plan
            return
        }
        if isOffline {
            chooseOffline { $0.selectedSubtitleIndex = index < 0 ? nil : index }
            return
        }
        change(PlaybackSelectBody(positionMillis: 0, subtitleStreamIndex: index))
    }

    /// A download's tracks are all in its file: chosen there, remembered for
    /// the series or film, with nothing asked of the hub.
    private func chooseOffline(_ edit: (inout PlaybackPrepareResponse) -> Void) {
        guard var next = plan else { return }
        edit(&next)
        plan = next
        remember(next)
        guard let item = player.currentItem else { return }
        applyAudioChoice(to: item)
        applyLegibleChoice(to: item)
    }

    func chooseQuality(_ bitrate: Int) {
        guard !isOffline else { return }
        guard bitrate != maxBitrate else { return }
        let before = maxBitrate
        maxBitrate = bitrate
        change(PlaybackSelectBody(positionMillis: 0, maxBitrate: bitrate)) { [weak self] in self?.maxBitrate = before }
    }

    func chooseSource(_ id: String) {
        guard !isOffline, id != plan?.selectedMediaSourceId else { return }
        change(PlaybackSelectBody(positionMillis: 0, mediaSourceId: id))
    }

    /// Asks the hub for the same session with another track, quality or
    /// version, from where it is now. A new stream starts there; the same
    /// stream (the file played as it is, or only the drawn subtitles changed)
    /// plays on untouched.
    private func change(_ body: PlaybackSelectBody, failed: @escaping @MainActor () -> Void = {}) {
        guard let plan, let hub, !applying else { return }
        applying = true
        let at = positionMillis
        // Named with the version playing (#24): Jellyfin applies a track only
        // with the source it belongs to.
        let body = PlaybackRules.selection(plan, positionMillis: at, mediaSourceId: body.mediaSourceId,
                                           audioStreamIndex: body.audioStreamIndex,
                                           subtitleStreamIndex: body.subtitleStreamIndex, maxBitrate: body.maxBitrate,
                                           forceTranscode: body.forceTranscode)
        let wasPlaying = isPlaying
        let generation = generation
        let request = HubEndpoints.selectPlayback(sessionId: plan.sessionId, body: body, user: user)
        Task {
            defer { if generation == self.generation { applying = false } }
            do throws(HubFailure) {
                let next = try await hub.fetch(request, as: PlaybackPrepareResponse.self)
                guard generation == self.generation else { return }
                remember(next)
                if PlaybackChoices.sameStream(plan, next), let item = player.currentItem {
                    self.plan = next
                    prepareSubtitles(next)
                    applyAudioChoice(to: item)
                } else {
                    player.pause()
                    // The hub would start a title's first 30 seconds again at 0:00.
                    startOverride = at
                    playAfterLoad = wasPlaying
                    try await load(next, generation: generation)
                }
            } catch {
                guard generation == self.generation else { return }
                failed()
                if error.kind != .cancelled { show(notice: error.message) }
            }
        }
    }

    /// A file played as it is, with several audio tracks, plays the one chosen:
    /// the file's track at its place when that is its language (two English
    /// dubs are told apart by place, not language), else the first of its
    /// language. A converted stream carries only the one chosen.
    private func applyAudioChoice(to item: AVPlayerItem) {
        guard let plan, plan.audioTracks.count > 1,
              let chosen = plan.audioTracks.first(where: { $0.index == plan.selectedAudioIndex }) else { return }
        let position = plan.audioTracks.firstIndex(of: chosen) ?? 0
        let asset = item.asset
        Task {
            guard let group = try? await asset.loadMediaSelectionGroup(for: .audible) else { return }
            let options = group.options
            // Jellyfin writes ISO 639-2 ("jpn"), AVFoundation BCP 47 ("ja").
            func code(_ tag: String) -> String? { Locale.Language(identifier: tag).languageCode?.identifier(.alpha2) }
            func language(_ option: AVMediaSelectionOption) -> String? {
                (option.extendedLanguageTag ?? option.locale?.identifier).flatMap(code)
            }
            let wanted = code(chosen.language)
            let atPlace = position < options.count ? options[position] : nil
            let pick: AVMediaSelectionOption?
            if let atPlace, wanted == nil || language(atPlace) == nil || language(atPlace) == wanted {
                pick = atPlace
            } else {
                pick = options.first { wanted != nil && language($0) == wanted } ?? atPlace
            }
            if let pick, self.player.currentItem === item { item.select(pick, in: group) }
        }
    }

    /// A download's subtitles are its file's own text tracks, in the order of
    /// the plan's: the chosen one by its place (its language checked), none
    /// when subtitles are off, which is how a download starts.
    private func applyLegibleChoice(to item: AVPlayerItem) {
        guard let plan else { return }
        let position = OfflinePlayback.optionPosition(plan.subtitleTracks, index: plan.selectedSubtitleIndex)
        let chosen = position.map { plan.subtitleTracks[$0] }
        let asset = item.asset
        Task {
            guard let group = try? await asset.loadMediaSelectionGroup(for: .legible) else { return }
            guard self.player.currentItem === item else { return }
            guard let position, let chosen else {
                item.select(nil, in: group)
                return
            }
            // The file's text tracks only: not a forced-only or automatic option.
            let options = group.options.filter { !$0.hasMediaCharacteristic(.containsOnlyForcedSubtitles) }
            func code(_ tag: String) -> String? { Locale.Language(identifier: tag).languageCode?.identifier(.alpha2) }
            let wanted = code(chosen.language)
            var pick = position < options.count ? options[position] : nil
            if let wanted, let candidate = pick, let tag = candidate.extendedLanguageTag ?? candidate.locale?.identifier,
               code(tag) != wanted {
                // Not the language its place says: the first of that language instead.
                pick = options.first { option in
                    guard let tag = option.extendedLanguageTag ?? option.locale?.identifier else { return false }
                    return code(tag) == wanted
                } ?? pick
            }
            item.select(pick, in: group)
        }
    }

    private func remember(_ plan: PlaybackPrepareResponse) {
        selection = PlaybackChoices.remembering(plan, in: selection)
        PlaybackMemory.save(selection, user: user, scope: PlaybackChoices.scope(plan.item))
    }

    // MARK: Subtitles the app draws

    /// Reads the selected text track once into a timeline, so a new delay
    /// redraws at once without touching the stream.
    private func prepareSubtitles(_ plan: PlaybackPrepareResponse) {
        let track = PlaybackChoices.drawnSubtitle(plan)
        let key = track.map { "\(plan.sessionId):\($0.index):\($0.externalUrl)" } ?? ""
        guard key != subtitleKey else { return }
        subtitleKey = key
        subtitleTask?.cancel()
        subtitleTimeline = nil
        subtitleLines = []
        guard let track, let hub else { return }
        let request = HubEndpoints.playbackFile(track.externalUrl, user: user)
        let codec = track.codec
        subtitleTask = Task {
            guard let bytes = try? await hub.data(request), !Task.isCancelled, subtitleKey == key else { return }
            let windows = await Task.detached(priority: .userInitiated) {
                SubtitleParser.parse(String(decoding: bytes, as: UTF8.self), codec: codec)
            }.value
            guard subtitleKey == key else { return }
            subtitleTimeline = SubtitleTimeline(windows)
            showSubtitles(at: positionMillis)
            #if DEBUG
            NSLog("playback: %d subtitle lines read (%@)", windows.count, codec)
            #endif
        }
    }

    private func showSubtitles(at millis: Int64) {
        // The TV draws its own subtitles (#44).
        guard let subtitleTimeline, !casting else {
            if !subtitleLines.isEmpty { subtitleLines = [] }
            return
        }
        let lines = subtitleTimeline.values(at: millis, offsetMillis: subtitleOffsetMillis)
        if lines != subtitleLines { subtitleLines = lines }
    }

    /// The slider moves this; it redraws at once.
    func setSubtitleOffset(_ value: Int64) {
        subtitleOffsetMillis = SubtitleTimingPolicy.clamp(value, wide: true)
        showSubtitles(at: positionMillis)
    }

    /// Kept for this profile and the series or film, when the slider lets go.
    func commitSubtitleOffset() {
        guard let plan else { return }
        selection.subtitleOffsetMillis = subtitleOffsetMillis
        PlaybackMemory.save(selection, user: user, scope: PlaybackChoices.scope(plan.item))
    }

    func setSubtitleLook(_ look: SubtitleLook) {
        subtitleLook = look
        PlaybackMemory.save(look)
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

    // MARK: Picture in picture

    /// The video's layer, from the surface: picture in picture needs it.
    func attach(_ layer: AVPlayerLayer) {
        // The surface hands its layer over on every update; a new surface
        // (the player opened again) brings a new one.
        guard pipSupported, request != nil, pip?.playerLayer !== layer,
              let controller = AVPictureInPictureController(playerLayer: layer) else { return }
        let observer = PictureInPictureObserver(model: self)
        controller.delegate = observer
        #if os(iOS)
        // Leaving the app while it plays carries on in a small window.
        controller.canStartPictureInPictureAutomaticallyFromInline = true
        #endif
        pip = controller
        pipObserver = observer
    }

    func togglePictureInPicture() {
        guard let pip else { return }
        if pip.isPictureInPictureActive { pip.stopPictureInPicture() } else { pip.startPictureInPicture() }
    }

    fileprivate func pictureInPicture(active: Bool) {
        pipActive = active
        guard !active, inBackground, request != nil else { return }
        // The small window closed while the app is away: playback is over.
        // Its own button back to the app also stops it, as the app returns,
        // so that case is given a moment to say so first.
        beginBackgroundWork()
        Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(700))
            guard let self else { return }
            if self.inBackground && !self.pipActive && self.request != nil { self.close() } else { self.endBackgroundWork() }
        }
    }

    /// The small window's button back to the app.
    fileprivate func pictureInPictureRestoring() {
        inBackground = false
    }

    // MARK: The app around it

    /// iPhone and iPad: leaving the app ends playback unless picture in
    /// picture carries it on. It may still be starting as the app leaves, so
    /// the decision waits a moment for it. The Mac never calls this: a
    /// minimised window or a hidden app plays on.
    func sceneChanged(background: Bool) {
        inBackground = background
        guard background, request != nil else { return }
        if pipActive || casting { return }
        beginBackgroundWork()
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(1))
            guard let self else { return }
            if self.inBackground && !self.pipActive { self.close() } else { self.endBackgroundWork() }
        }
    }

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

    private func show(notice text: String) {
        notice = text
        noticeTask?.cancel()
        noticeTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(3))
            guard !Task.isCancelled else { return }
            self?.notice = nil
        }
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

    // MARK: Debug launches (scripts/mac.sh)

    #if DEBUG
    /// HUB_PLAY_EXIT=25 leaves the player that many seconds after it opened,
    /// through Back's own path, so a screenshot run against the real hub never
    /// leaves a session open.
    private func exitAfterDebugDelay() {
        guard let seconds = Self.debugSeconds("HUB_PLAY_EXIT") else { return }
        let opened = opens
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(seconds))
            guard let self, self.opens == opened else { return }
            self.close()
        }
    }

    /// HUB_PLAY_SUBTITLE=eng turns on that language's subtitles when the title
    /// opens without them, as choosing them in the panel would.
    private func debugSubtitle(_ plan: PlaybackPrepareResponse) -> (audio: Int?, subtitle: Int?)? {
        guard let language = ProcessInfo.processInfo.environment["HUB_PLAY_SUBTITLE"], !language.isEmpty,
              plan.selectedSubtitleIndex == nil || plan.selectedSubtitleIndex == -1,
              let track = plan.subtitleTracks.first(where: { $0.language.caseInsensitiveCompare(language) == .orderedSame })
        else { return nil }
        return (plan.selectedAudioIndex, track.index)
    }

    /// HUB_PLAY_TOUR=1 opens each panel in turn once the video plays, 4 s
    /// apart, then closes them, for screenshots: Audio & subtitles, its
    /// subtitle timing, This video and Chapters. The first title only.
    private func startDebugTour() {
        guard ProcessInfo.processInfo.environment["HUB_PLAY_TOUR"] == "1", !debugTourStarted else { return }
        debugTourStarted = true
        let opened = opens
        Task { [weak self] in
            for (index, panel) in ["tracks", "timing", "video", "chapters", "none"].enumerated() {
                try? await Task.sleep(for: .seconds(index == 0 ? 2.5 : 4))
                guard let self, self.opens == opened, self.request != nil else { return }
                self.debugPanel = panel
            }
        }
    }

    /// Which tracks to ask for before the first frame: HUB_PLAY_SUBTITLE's,
    /// else what this profile chose last.
    private func wantedTracks(_ plan: PlaybackPrepareResponse) -> (audio: Int?, subtitle: Int?)? {
        debugSubtitle(plan) ?? PlaybackChoices.wanted(plan, selection)
    }

    static func debugSeconds(_ name: String) -> Double? {
        ProcessInfo.processInfo.environment[name].flatMap(Double.init).flatMap { $0 > 0 ? $0 : nil }
    }
    #else
    private func wantedTracks(_ plan: PlaybackPrepareResponse) -> (audio: Int?, subtitle: Int?)? {
        PlaybackChoices.wanted(plan, selection)
    }
    #endif
}

extension PlayerModel: NowPlayingClient {
    func remote(_ command: RemoteCommand) {
        switch command {
        case .play: if !isPlaying { togglePlay() }
        case .pause: if isPlaying || player.timeControlStatus == .waitingToPlayAtSpecifiedRate { togglePlay() }
        case .toggle: togglePlay()
        case .skip(let forward): seek(by: (forward ? 1 : -1) * Int64(ListeningSettings.seekSeconds) * 1_000)
        case .step(let delta): delta > 0 ? playNext() : playPrevious()
        case .seek(let millis): seek(to: millis)
        }
    }

    func publishNowPlaying() { updateNowPlaying() }
}

/// Picture in picture's news, carried to the model on the main actor.
private final class PictureInPictureObserver: NSObject, AVPictureInPictureControllerDelegate, @unchecked Sendable {
    private weak var model: PlayerModel?

    init(model: PlayerModel) {
        self.model = model
    }

    func pictureInPictureControllerWillStartPictureInPicture(_ controller: AVPictureInPictureController) {
        Task { @MainActor [weak self] in self?.model?.pictureInPicture(active: true) }
    }

    func pictureInPictureControllerDidStopPictureInPicture(_ controller: AVPictureInPictureController) {
        Task { @MainActor [weak self] in self?.model?.pictureInPicture(active: false) }
    }

    func pictureInPictureController(_ controller: AVPictureInPictureController,
                                    failedToStartPictureInPictureWithError error: any Error) {
        Task { @MainActor [weak self] in self?.model?.pictureInPicture(active: false) }
    }

    /// Back from the small window: the player is still there underneath.
    func pictureInPictureController(_ controller: AVPictureInPictureController,
                                    restoreUserInterfaceForPictureInPictureStopWithCompletionHandler completionHandler: @escaping (Bool) -> Void) {
        Task { @MainActor [weak self] in self?.model?.pictureInPictureRestoring() }
        completionHandler(true)
    }
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

    /// Once the hub has closed it (or no longer has it), it is crossed off
    /// the sessions left open (`PlaybackMemory`), from this task: quitting
    /// waits on the main thread for it.
    func close(session: String, user: String) {
        enqueue(HubEndpoints.closePlayback(sessionId: session, user: user), attempts: 1) { closed in
            if closed { PlaybackMemory.sessionClosed(session) }
        }
    }

    private func enqueue(_ request: HubRequest, attempts: Int, done: (@Sendable (Bool) -> Void)? = nil) {
        let previous = tail
        let hub = hub
        tail = Task.detached {
            await previous?.value
            let sent = await Self.send(request, hub: hub, attempts: attempts)
            done?(sent)
        }
    }

    /// Whether the hub took it, or answered that it no longer has the session.
    private nonisolated static func send(_ request: HubRequest, hub: HubClient, attempts: Int) async -> Bool {
        let pauses: [Int] = [1_000, 3_000]
        for attempt in 0..<attempts {
            do throws(HubFailure) {
                try await hub.send(request)
                return true
            } catch {
                if error.kind == .notFound { return true }
                guard attempt < attempts - 1 else { return false }
                try? await Task.sleep(for: .milliseconds(pauses[min(attempt, pauses.count - 1)]))
            }
        }
        return false
    }
}
