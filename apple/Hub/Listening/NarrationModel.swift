import AVFoundation
import HubKit
import Observation
import SwiftUI

/// Read along's narration (#25 phase 4; Android's `ReadAlongPlayback`),
/// owned by its reader. It plays the narration from the audiobook's own
/// tracks, each stretch clipped to where it is spoken and the stretches of
/// one track played as one run without a stop (`NarrationRuns`), and says
/// which sentence is spoken (`onSegment`: the highlight, and the page
/// following the voice). It never starts by itself; it hands its place to
/// the reader (`onSave`, which keeps it as the sentence through
/// `ReadAlongLocation`) every ten seconds while it plays and on every pause,
/// jump and step; it pauses for a call; and it takes its turn with the video
/// and the audiobook (`SoundGuard`). Its speed is the book's, the
/// audiobook's own. `release()` before letting it go.
@MainActor
@Observable
final class NarrationModel {
    /// A book's narration, ready to play.
    struct Narration: Sendable {
        let timeline: ReadAlongTimeline
        let sources: [NarrationSource]
        let manifest: ReadingAudioManifest
    }

    let timeline: ReadAlongTimeline
    /// On, or about to be once a seek or the next track has loaded: the
    /// dock's Play and the page's pill go by this.
    private(set) var playing = false
    private(set) var position: ReadAlongPosition
    /// The sentence spoken now; nil in a pause between two.
    private(set) var segment: ReadAlongSegment?
    private(set) var speed: Float
    /// Played to its end.
    private(set) var completed = false

    @ObservationIgnored var onSegment: ((ReadAlongSegment?) -> Void)?
    /// The place to keep, and whether the narration is finished.
    @ObservationIgnored var onSave: ((ReadAlongPosition, Bool) -> Void)?
    @ObservationIgnored var onError: (() -> Void)?

    @ObservationIgnored private let runs: NarrationRuns
    @ObservationIgnored private let workId: String
    @ObservationIgnored private let token: String
    @ObservationIgnored private let player = AVPlayer()
    /// The run on the player.
    @ObservationIgnored private var run: Int?
    /// Where to go and whether to play, once the run's item can.
    @ObservationIgnored private var pending: (ms: Int64, play: Bool)?
    @ObservationIgnored private var readyItem: ObjectIdentifier?
    @ObservationIgnored private var failedItem: ObjectIdentifier?
    /// The latest seek; the player's clock is not read while one is under way.
    @ObservationIgnored private var seeks = 0
    @ObservationIgnored private var seeking = false
    @ObservationIgnored private var poll: Task<Void, Never>?
    @ObservationIgnored private var lastSave = ContinuousClock.now
    @ObservationIgnored private var observers: [any NSObjectProtocol] = []
    @ObservationIgnored private var released = false

    /// The narration at `initial` (the sentence the place names), paused.
    init(_ narration: Narration, workId: String, token: String, initial: ReadAlongPosition?) {
        timeline = narration.timeline
        runs = NarrationRuns(narration.timeline, sources: narration.sources)
        self.workId = workId
        self.token = token
        speed = ListeningSettings.speed(for: workId)
        position = initial ?? ReadAlongPosition(track: 0, offsetMs: 0)
        player.actionAtItemEnd = .pause
        player.defaultRate = speed
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: AVPlayerItem.didPlayToEndTimeNotification, object: nil,
                                            queue: .main) { [weak self] note in
            let ended = (note.object as AnyObject?).map(ObjectIdentifier.init)
            MainActor.assumeIsolated { self?.runEnded(ended) }
        })
        #if os(iOS)
        observers.append(center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil,
                                            queue: .main) { [weak self] note in
            let began = (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt) == AVAudioSession.InterruptionType.began.rawValue
            MainActor.assumeIsolated { if began { self?.pause() } }
        })
        #endif
        // Video or the audiobook starting pauses the narration.
        SoundGuard.shared.pause(.narration) { [weak self] in self?.pause() }
        load(position, play: false)
        segment = timeline.active(track: position.track, offsetMs: position.offsetMs)
        poll = Task { [weak self] in
            while !Task.isCancelled {
                self?.tick()
                try? await Task.sleep(for: .milliseconds(100))
            }
        }
    }

    // MARK: Controls

    func toggle() { playing ? pause() : play() }

    func play() {
        guard !released else { return }
        if completed {
            // Finished: Play starts the narration again.
            completed = false
            load(ReadAlongPosition(track: 0, offsetMs: 0), play: true)
        }
        playing = true
        lastSave = .now
        ListeningAudio.activateSession(for: .narration)
        SoundGuard.shared.started(.narration)
        if var start = pending {
            start.play = true
            pending = start
        } else if player.currentItem == nil || player.currentItem?.status == .failed {
            // A stream that failed is tried again from where it stood.
            run = nil
            load(position, play: true)
        } else {
            player.playImmediately(atRate: speed)
        }
    }

    /// Paused, its place handed to the reader.
    func pause() {
        guard !released, playing else { return }
        player.pause()
        playing = false
        if var start = pending {
            start.play = false
            pending = start
        }
        SoundGuard.shared.stopped(.narration)
        save()
    }

    /// To `target`, playing on if it was.
    func seek(to target: ReadAlongPosition) {
        guard !released else { return }
        completed = false
        load(target, play: playing)
        let now = timeline.active(track: position.track, offsetMs: position.offsetMs)
        segment = now
        onSegment?(now)
    }

    /// By `deltaMs` of narration, across stretches (−10 and +10).
    func jump(by deltaMs: Int64) {
        seek(to: timeline.jump(position, by: deltaMs))
        save()
    }

    /// The sentence before or after (L1 and R1), its highlight and the page
    /// with it. False at either end of the book.
    @discardableResult
    func stepSentence(_ delta: Int) -> Bool {
        guard let target = timeline.step(position, delta: delta) else { return false }
        seek(to: target)
        save()
        return true
    }

    /// The book's speed, the audiobook's too.
    func setSpeed(_ value: Float) {
        speed = Listening.clampSpeed(value)
        ListeningSettings.setSpeed(speed, for: workId)
        player.defaultRate = speed
        if playing && pending == nil && !seeking { player.rate = speed }
    }

    /// The reader is closing: paused, its place handed over, nothing left playing.
    func release() {
        guard !released else { return }
        pause()
        released = true
        poll?.cancel()
        poll = nil
        for observer in observers { NotificationCenter.default.removeObserver(observer) }
        observers = []
        player.replaceCurrentItem(with: nil)
        SoundGuard.shared.forget(.narration)
        ListeningAudio.deactivateSession(for: .narration)
    }

    // MARK: The player

    /// The run holding `target` onto the player at its moment, playing or not.
    private func load(_ target: ReadAlongPosition, play: Bool) {
        guard let time = runs.time(of: target) else { return }
        position = runs.position(run: time.run, ms: time.ms)
        let entry = runs.runs[time.run]
        // The run ends where its last sentence does: the player stops there and the next run is loaded.
        let end = CMTime(value: entry.toMs, timescale: 1_000)
        if let item = player.currentItem, item.status != .failed,
           run == time.run || (item.asset as? AVURLAsset)?.url.absoluteString == entry.url {
            // The same source: a seek, not a load (another run of one track is only another end).
            run = time.run
            item.forwardPlaybackEndTime = end
            if pending != nil {
                pending = (time.ms, play)
            } else {
                seekPlayer(time.ms, play: play)
            }
            return
        }
        guard let url = URL(string: entry.url) else {
            fail()
            return
        }
        let item = ListeningAudio.item(url, token: token)
        item.forwardPlaybackEndTime = end
        player.pause()
        player.replaceCurrentItem(with: item)
        run = time.run
        readyItem = nil
        failedItem = nil
        pending = (time.ms, play)
    }

    private func seekPlayer(_ ms: Int64, play: Bool) {
        seeks += 1
        let generation = seeks
        seeking = true
        player.seek(to: CMTime(value: ms, timescale: 1_000), toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] _ in
            Task { @MainActor in
                guard let self, generation == self.seeks else { return }
                self.seeking = false
                if play && self.playing && self.player.rate == 0 { self.player.playImmediately(atRate: self.speed) }
            }
        }
    }

    /// Ten times a second: the item's state, where the narration is, the
    /// sentence spoken, and the place every ten seconds while it plays.
    private func tick() {
        guard !released, let item = player.currentItem, let run else { return }
        let id = ObjectIdentifier(item)
        switch item.status {
        case .readyToPlay where readyItem != id:
            readyItem = id
            if let start = pending {
                pending = nil
                seekPlayer(start.ms, play: start.play)
            }
        case .failed where failedItem != id:
            failedItem = id
            fail()
            return
        default:
            break
        }
        guard pending == nil, !seeking else { return }
        let time = player.currentTime()
        if time.isNumeric {
            position = runs.position(run: run, ms: Int64((CMTimeGetSeconds(time) * 1_000).rounded()))
        }
        let now = timeline.active(track: position.track, offsetMs: position.offsetMs)
        if now != segment {
            segment = now
            onSegment?(now)
        }
        if playing && ContinuousClock.now - lastSave >= .seconds(10) { save() }
    }

    /// A run played to its end: the next, or the end of the book's narration.
    private func runEnded(_ ended: ObjectIdentifier?) {
        guard !released, let ended, ended == player.currentItem.map(ObjectIdentifier.init), let run else { return }
        if runs.runs.indices.contains(run + 1) {
            load(runs.position(run: run + 1, ms: runs.runs[run + 1].fromMs), play: playing)
            return
        }
        let last = timeline.tracks.count - 1
        position = ReadAlongPosition(track: last, offsetMs: timeline.tracks[last].durationMs)
        playing = false
        completed = true
        segment = nil
        onSegment?(nil)
        SoundGuard.shared.stopped(.narration)
        save()
    }

    private func fail() {
        player.pause()
        let was = playing
        playing = false
        if was { SoundGuard.shared.stopped(.narration) }
        onError?()
    }

    private func save() {
        lastSave = .now
        onSave?(position, completed)
    }

    // MARK: Opening

    /// What read along opens (#19): the edition without its audio, kept with
    /// this hub's and profile's books, and its narration, streamed from the
    /// audiobook's tracks when the hub mapped them. Without a mapping, or
    /// without the hub (the edition kept here then), the book opens to be
    /// read, and `note` says why.
    struct Opening: Sendable {
        let edition: URL
        let narration: Narration?
        let note: String
    }

    static func prepare(app: AppModel, workId: String, sourceItemId: String) async throws(ReadAlongError) -> Opening {
        let answer: Result<ReadingAudioManifest, HubFailure>
        do {
            answer = .success(try await app.hub.fetch(HubEndpoints.readingAudioManifest(workId: workId, sourceItemId: sourceItemId),
                                                     as: ReadingAudioManifest.self))
        } catch {
            if error.kind == .cancelled { throw ReadAlongError(error.message) }
            answer = .failure(error)
        }
        let plan = ReadAlongStream.plan(answer)
        let cache = ReadAlongEdition.cache(app: app)
        if case .unreachable(let failure) = plan {
            guard cache.isComplete(workId: workId, sourceItemId: sourceItemId) else { throw ReadAlongError(failure.message) }
            return Opening(edition: cache.completeFile(workId: workId, sourceItemId: sourceItemId), narration: nil,
                           note: "The narration needs the hub. You can read this book meanwhile.")
        }
        let edition = try await ReadAlongEdition.file(app: app, cache: cache, workId: workId, sourceItemId: sourceItemId)
        guard case .stream(let manifest) = plan else {
            return Opening(edition: edition, narration: nil,
                           note: "The hub cannot stream this book's narration yet. You can read it meanwhile.")
        }
        let tones = app.isDemo ? await ListeningAudio.demoFiles(manifest, sourceItemId: sourceItemId) : nil
        let address = app.address
        do {
            let narration = try await Task.detached(priority: .userInitiated) { () throws -> Narration in
                let timeline = try ReadAlongPackage.read(edition, requireAudio: false)
                let sources = try ReadAlongStream.sources(timeline, manifest: manifest, sourceItemId: sourceItemId) { index in
                    ListeningAudio.trackAddress(index, manifest: manifest, workId: workId, sourceItemId: sourceItemId,
                                                address: address, demo: tones)
                }
                return Narration(timeline: timeline, sources: sources, manifest: manifest)
            }.value
            return Opening(edition: edition, narration: narration, note: "")
        } catch {
            return Opening(edition: edition, narration: nil,
                           note: "Aligned narration could not be opened. You can still read this book.")
        }
    }
}

/// The read-along edition without its audio (#19), downloaded once and kept
/// with this hub's and profile's books, beside the ebooks (`aligned-slim`,
/// as on the Pocket). A partial download is never opened.
@MainActor
enum ReadAlongEdition {
    static func cache(app: AppModel) -> EpubPackageCache {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("reading-epub", isDirectory: true)
        return EpubPackageCache(root: EpubPackageCache.folder(base: caches, address: app.address, userId: app.userId)
            .appendingPathComponent("aligned-slim", isDirectory: true))
    }

    /// The edition, from the cache or the hub; `force` downloads it again.
    static func file(app: AppModel, cache: EpubPackageCache, workId: String, sourceItemId: String,
                     force: Bool = false) async throws(ReadAlongError) -> URL {
        if force { cache.remove(workId: workId, sourceItemId: sourceItemId) }
        if cache.isComplete(workId: workId, sourceItemId: sourceItemId) {
            cache.touch(workId: workId, sourceItemId: sourceItemId)
            return cache.completeFile(workId: workId, sourceItemId: sourceItemId)
        }
        let data: Data
        do {
            data = try await app.hub.data(HubEndpoints.readingEpubFile(workId: workId, sourceItemId: sourceItemId,
                                                                       format: "readaloud", omitAudio: true))
        } catch {
            throw ReadAlongError(error.message)
        }
        do {
            let file = try cache.install(workId: workId, sourceItemId: sourceItemId) { try data.write(to: $0) }
            cache.prune(keeping: file)
            return file
        } catch {
            throw ReadAlongError("The read-along edition could not be kept on this device")
        }
    }
}
