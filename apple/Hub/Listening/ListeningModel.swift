import AVFoundation
import HubKit
import MediaPlayer
import Observation
import SwiftUI
#if os(iOS)
import UIKit
#elseif os(macOS)
import AppKit
#endif

/// What Settings › Playback keeps for listening (#25 phase 2): the jump's
/// length, which the video player can take too, and each book's speed.
enum ListeningSettings {
    static let seekChoices = [5, 10, 15, 30]
    private static let seekKey = "playback.seekSeconds"

    /// 5, 10, 15 or 30 seconds; 10 until chosen (Android's `PlaybackSettings.seekSeconds`).
    static var seekSeconds: Int {
        get {
            let stored = UserDefaults.standard.integer(forKey: seekKey)
            return seekChoices.contains(stored) ? stored : 10
        }
        set { UserDefaults.standard.set(newValue, forKey: seekKey) }
    }

    static func speed(for workId: String) -> Float {
        let stored = UserDefaults.standard.float(forKey: "listen.speed." + workId)
        return stored > 0 ? Listening.clampSpeed(stored) : 1
    }

    static func setSpeed(_ speed: Float, for workId: String) {
        UserDefaults.standard.set(speed, forKey: "listen.speed." + workId)
    }
}

/// Where the listening places live on this device: one file per book under
/// Application Support, kept per hub and profile by their keys.
///
/// The demo hub (`-demo`, as `AppModel` reads it) forgets every place when
/// the app starts again, so the demo's places start again too, in a folder
/// of their own that never meets a real hub's: kept across launches, every
/// demo place would be another device's on the next.
enum ListeningStore {
    static let shared: ReadingCheckpointStore = {
        let files = FileManager.default
        if ProcessInfo.processInfo.arguments.contains("-demo") {
            let demo = files.temporaryDirectory.appendingPathComponent("demo-reading-checkpoints", isDirectory: true)
            try? files.removeItem(at: demo)
            return ReadingCheckpointStore(root: demo)
        }
        let base = files.urls(for: .applicationSupportDirectory, in: .userDomainMask).first ?? files.temporaryDirectory
        return ReadingCheckpointStore(root: base.appendingPathComponent("reading-checkpoints", isDirectory: true))
    }()
}

/// The audiobook player (#25 phase 2; Android's `ReadingAudio`): one for the
/// app, so a book plays on while the app is browsed, behind the lock screen
/// and with the screen off. It streams the hub's tracks with the bearer
/// (the current part and the next one queued, so a change of part plays on),
/// keeps the place here every 15 seconds while playing and on every pause,
/// jump and change of part, sends it to the hub no faster than every 15
/// seconds through the outbox, runs the sleep timer with its fade and the
/// step back after it, answers the lock screen and the media keys, and says
/// when it starts so a video can pause. A 412 from a track means the book's
/// files changed: its manifest is read again and the place kept, twice at
/// most in a row.
@MainActor
@Observable
final class ListeningModel {
    static let shared = ListeningModel()

    /// An audiobook on the player.
    struct Book: Equatable {
        let workId: String
        let sourceItemId: String
        let title: String
        let author: String
        let artwork: String
        let manifest: ReadingAudioManifest
        let parts: [AudiobookPart]
        let key: ReadingCheckpointKey

        func isSame(workId: String, sourceItemId: String) -> Bool {
            self.workId == workId && self.sourceItemId == sourceItemId
        }
    }

    /// What opening a book found: where it plays from, and the question to
    /// answer first, if any.
    struct Opening {
        enum Question { case place, estimate }

        let book: Book
        var place: AudioPlace?
        /// The hub's own answer, for the question about a place it worked out.
        let answered: ReadingAudioPosition?
        var prompt: ReadingResumePrompt?
        var question: Question?
    }

    private(set) var book: Book?
    private(set) var playing = false
    private(set) var part = 0
    private(set) var positionMs: Int64 = 0
    private(set) var partMs: Int64 = 0
    private(set) var speed: Float = 1
    private(set) var sleep: SleepTimer?
    /// Why it stopped, when it could not go on; empty otherwise.
    private(set) var problem = ""
    /// Another device moved the place while this one listened: the book's
    /// screen asks which to keep.
    private(set) var conflicted = false

    /// What the contents sheet lists and the steps go through: the book's
    /// chapters across the tracks, the marks inside them, else the tracks.
    /// Worked out once a book is on the player.
    private(set) var contents: [AudiobookContents.Entry] = []

    var partsMs: [Int64?] { book?.parts.map(\.durationMs) ?? [] }
    var bookLeftMs: Int64? { Listening.bookLeft(part: part, positionMs: positionMs, partsMs: partsMs, speed: speed) }
    /// What the line, its times, the time left and the lock screen measure
    /// (#31): the chapter playing, across tracks when it is the book's own;
    /// else the part.
    var span: AudiobookContents.Span {
        AudiobookContents.span(contents, part: part, positionMs: positionMs, partMs: partMs, partsMs: partsMs)
    }
    /// Left of the chapter (or part) playing, as heard.
    var spanLeftMs: Int64 { Listening.heard(span.leftMs, speed: speed) }
    /// "chapter" where the book has chapters (#31), else "part".
    var noun: String { AudiobookContents.noun(contents) }
    /// The chapter playing, for the mini player and the lock screen; nil for a book of parts.
    var chapter: String? {
        let title = span.title
        return noun == "chapter" && !title.isEmpty ? title : nil
    }
    /// The tracks' lengths, the part playing by the player's own.
    private var lengths: [Int64?] {
        var known = partsMs
        if partMs > 0 && known.indices.contains(part) { known[part] = partMs }
        return known
    }

    /// Pauses whatever else plays (the video) when the book starts: set by the shell.
    @ObservationIgnored var onStart: (() -> Void)?
    /// A place was kept for a book (its work): a mark of read or unread is forgotten (#37).
    @ObservationIgnored var onKept: ((String) -> Void)?

    @ObservationIgnored private let player = AVQueuePlayer()
    @ObservationIgnored private let store = ListeningStore.shared
    /// The tracks kept on this device, and the one fetching them (#37).
    @ObservationIgnored private let tracks = ListeningTracks.shared
    @ObservationIgnored private var indexes: [ObjectIdentifier: Int] = [:]
    @ObservationIgnored private var hub: HubClient?
    @ObservationIgnored private var address = ""
    @ObservationIgnored private var token = ""
    @ObservationIgnored private var demo = false
    @ObservationIgnored private var poll: Task<Void, Never>?
    @ObservationIgnored private var syncWait: Task<Void, Never>?
    @ObservationIgnored private var syncing = false
    @ObservationIgnored private var syncAgain = false
    @ObservationIgnored private var throttle = SyncThrottle(intervalMs: 15_000)
    @ObservationIgnored private var lastSave = ContinuousClock.now
    @ObservationIgnored private var lastTick = ContinuousClock.now
    /// Where to start and whether to play, once the first item can.
    @ObservationIgnored private var pendingStart: (offsetMs: Int64, play: Bool)?
    @ObservationIgnored private var startedItem: ObjectIdentifier?
    @ObservationIgnored private var failedItem: ObjectIdentifier?
    @ObservationIgnored private var reloads = 0
    /// The last part played to its end.
    @ObservationIgnored private var finished = false
    @ObservationIgnored private var reloading = false
    @ObservationIgnored private var nowPlayingArt: MPMediaItemArtwork?
    @ObservationIgnored private var observers: [any NSObjectProtocol] = []

    private init() {
        player.actionAtItemEnd = .advance
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: AVPlayerItem.didPlayToEndTimeNotification, object: nil,
                                            queue: .main) { [weak self] note in
            let ended = (note.object as AnyObject?).map(ObjectIdentifier.init)
            MainActor.assumeIsolated { self?.itemEnded(ended) }
        })
        #if os(iOS)
        observers.append(center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil,
                                            queue: .main) { [weak self] note in
            let began = (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt) == AVAudioSession.InterruptionType.began.rawValue
            MainActor.assumeIsolated { if began { self?.interrupted() } }
        })
        #endif
        NowPlaying.shared.register(.audiobook, self)
    }

    // MARK: Opening a book

    /// Reads a book's manifest and its place, and says what to ask before it
    /// plays. A book the hub cannot stream says so: Apple has no whole-book
    /// download.
    func prepare(work: ReadingWork, sourceItemId: String, app: AppModel) async throws(HubFailure) -> Opening {
        connect(app)
        let hub = app.hub
        let manifest = try await hub.fetch(HubEndpoints.readingAudioManifest(workId: work.id, sourceItemId: sourceItemId),
                                           as: ReadingAudioManifest.self)
        guard AudiobookStream.playable(manifest) else {
            throw HubFailure(.badResponse, message: "This audiobook cannot be streamed.")
        }
        let parts = await partsFor(manifest, workId: work.id, sourceItemId: sourceItemId)
        let key = ReadingCheckpointKey(scope: ReadingCheckpointKey.scope(address: app.address, userId: app.userId),
                                       workId: work.id, sourceItemId: sourceItemId, kind: AudioPlace.kind)
        let book = Book(workId: work.id, sourceItemId: sourceItemId, title: work.title,
                        author: (work.authors.isEmpty ? work.authorRefs.map(\.name) : work.authors).joined(separator: ", "),
                        artwork: work.artwork, manifest: manifest, parts: parts, key: key)
        let answered = try? await hub.fetch(HubEndpoints.readingAudioPosition(workId: work.id, sourceItemId: sourceItemId),
                                            as: ReadingAudioPositionResponse.self)
        let remote: RemoteReadingPosition = answered.map { .available($0.position.flatMap(AudioPlace.fromServer)?.location()) }
            ?? .unavailable
        let resume: ReadingResume
        do {
            resume = try store.reconcile(key, remote)
        } catch {
            throw HubFailure(.unknown, message: "Your listening place could not be read. It has been kept.")
        }
        var opening = Opening(book: book, place: AudioPlace.of(resume.location), answered: answered?.position)
        if let prompt = ReadingResumePrompt.make(resume, checkpoint: try? store.read(key),
                                                 describe: { AudioPlace.label($0, tracks: manifest.tracks) }) {
            opening.prompt = prompt
            opening.question = .place
        } else {
            askAboutEstimate(&opening)
        }
        Task { await self.flushPending(scope: key.scope) }
        return opening
    }

    /// An answer to the opening's question: which place, or whether to go to
    /// one the hub worked out.
    func answer(_ opening: Opening, choice: String) -> Opening {
        var next = opening
        next.prompt = nil
        next.question = nil
        switch (opening.question, choice) {
        case (.place?, "local"):
            next.place = AudioPlace.of(try? store.chooseLocal(opening.book.key, now: Self.nowMillis()).local)
            Task { await self.flushPending(scope: opening.book.key.scope) }
        case (.place?, "server"):
            next.place = AudioPlace.of(try? store.chooseRemote(opening.book.key).local)
            askAboutEstimate(&next)
        case (.place?, _):
            next.place = nil
        case (.estimate?, "there"):
            break
        default:
            next.place = nil
        }
        return next
    }

    private func askAboutEstimate(_ opening: inout Opening) {
        guard let place = opening.place, AudioPlace.asksBeforeJumping(to: place, answered: opening.answered) else { return }
        opening.prompt = AudioPlace.estimatePrompt(place, tracks: opening.book.manifest.tracks)
        opening.question = .estimate
    }

    /// The book onto the player at its place, playing or not. The same book
    /// already there is left as it is.
    func start(_ opening: Opening, play: Bool) {
        if let book, book.isSame(workId: opening.book.workId, sourceItemId: opening.book.sourceItemId) {
            if play && !playing { self.play() }
            return
        }
        if book != nil { stop() }
        book = opening.book
        contents = Self.contents(of: opening.book)
        problem = ""
        conflicted = false
        reloads = 0
        finished = false
        speed = ListeningSettings.speed(for: opening.book.workId)
        let start = opening.place?.openAt(opening.book.manifest.tracks) ?? (part: 0, offsetMs: 0)
        load(part: start.part, offsetMs: start.offsetMs, play: play)
        loadArtwork()
        startPolling()
        enableCommands(true)
        updateNowPlaying()
    }

    /// The parts with their addresses: the hub's tracks, or in the demo
    /// generated tones written on this device.
    private func partsFor(_ manifest: ReadingAudioManifest, workId: String, sourceItemId: String) async -> [AudiobookPart] {
        let tones = demo ? await ListeningAudio.demoFiles(manifest, sourceItemId: sourceItemId) : nil
        let base = address
        return AudiobookStream.parts(manifest, sourceItemId: sourceItemId) { index in
            ListeningAudio.trackAddress(index, manifest: manifest, workId: workId, sourceItemId: sourceItemId,
                                        address: base, demo: tones)
        }
    }

    // MARK: Controls

    func toggle() { playing ? pause() : play() }

    func play() {
        guard book != nil else { return }
        touched()
        activateSession()
        onStart?()
        // Played again after a video: the lock screen is the book's again (#33).
        enableCommands(true)
        if finished {
            // Finished: the next listen starts the book again.
            finished = false
            load(part: 0, offsetMs: 0, play: true)
        } else if !problem.isEmpty || player.currentItem == nil || player.currentItem?.status == .failed {
            // A stream that failed is tried again from where it stood.
            problem = ""
            load(part: part, offsetMs: positionMs, play: true)
        } else {
            player.playImmediately(atRate: speed)
        }
        playing = true
        lastTick = .now
        updateNowPlaying()
    }

    func pause() {
        guard book != nil else { return }
        player.pause()
        playing = false
        save()
        updateNowPlaying()
    }

    /// A jump within the book by `deltaMs` of recording, across parts.
    func seek(by deltaMs: Int64) {
        guard book != nil else { return }
        touched()
        let target = Listening.jump(part: part, positionMs: positionMs, by: deltaMs, partsMs: partsMs,
                                    currentPartMs: partMs > 0 ? partMs : nil)
        seek(part: target.part, offsetMs: target.offsetMs)
    }

    /// To `ms` into the chapter (or part) playing: the line under the player
    /// and the lock screen's.
    func seek(inSpan ms: Int64) {
        guard book != nil else { return }
        touched()
        let target = AudiobookContents.place(span, at: ms, partsMs: lengths)
        seek(part: target.part, offsetMs: target.offsetMs)
    }

    func seek(part target: Int, offsetMs: Int64) {
        guard let book, book.parts.indices.contains(target) else { return }
        if target == part, player.currentItem != nil {
            positionMs = max(0, offsetMs)
            player.seek(to: CMTime(value: positionMs, timescale: 1_000), toleranceBefore: .zero, toleranceAfter: .zero)
        } else {
            load(part: target, offsetMs: offsetMs, play: playing)
        }
        save()
        updateNowPlaying()
    }

    /// The entry before (from its own start once three seconds in, counted
    /// across tracks) or the next: a chapter where the hub found chapters, else a part.
    func step(_ delta: Int) {
        touched()
        guard let entry = AudiobookContents.step(contents, part: part, positionMs: positionMs, delta: delta,
                                                 partsMs: lengths) else { return }
        seek(part: entry.part, offsetMs: entry.startMs)
    }

    func setSpeed(_ value: Float) {
        guard let book else { return }
        touched()
        speed = Listening.clampSpeed(value)
        ListeningSettings.setSpeed(speed, for: book.workId)
        player.defaultRate = speed
        if playing { player.rate = speed }
        updateNowPlaying()
    }

    /// Sets the sleep timer, or nil to cancel it. The end of the part is the
    /// end of the chapter where the book has chapters (#31), which it counts
    /// to by its place in the contents.
    func setSleep(_ choice: SleepChoice?) {
        player.volume = 1
        lastTick = .now
        let now = span
        sleep = choice.map { SleepTimer.start($0, entryLeftHeardMs: Listening.heard(now.leftMs, speed: speed), entry: now.entry) }
        player.actionAtItemEnd = itemEndAction()
    }

    /// Any control while the sleep timer fades keeps you listening: to the
    /// end of the next chapter (or part) when it was counting to this one's.
    func touched() {
        guard let timer = sleep, timer.fading else { return }
        let now = span
        let next = contents.indices.contains(now.entry + 1)
            ? contents[now.entry + 1].durationMs.map { Listening.heard($0, speed: speed) } : nil
        player.volume = 1
        sleep = timer.extended(entryLeftHeardMs: Listening.heard(now.leftMs, speed: speed), nextEntryHeardMs: next)
        player.actionAtItemEnd = itemEndAction()
    }

    /// What the queue does at a track's end: it stops there only for a sleep
    /// timer counting to the end of an entry that ends with the track. A
    /// chapter that runs on into the next track plays on through the change (#31).
    private func itemEndAction() -> AVPlayer.ActionAtItemEnd {
        guard let timer = sleep, timer.endsWithPart else { return .advance }
        let length = partMs > 0 ? partMs : (partsMs.indices.contains(part) ? partsMs[part] ?? 0 : 0)
        return AudiobookContents.endsWithPart(contents, part: part, lengthMs: length) ? .pause : .advance
    }

    /// Takes the book off the player, its place kept.
    func stop() {
        guard book != nil else { return }
        save()
        poll?.cancel()
        poll = nil
        player.pause()
        player.removeAllItems()
        tracks.stop()
        indexes = [:]
        pendingStart = nil
        startedItem = nil
        failedItem = nil
        book = nil
        contents = []
        playing = false
        sleep = nil
        problem = ""
        conflicted = false
        nowPlayingArt = nil
        enableCommands(false)
        deactivateSession()
    }

    /// Another device's place, chosen or not, once the question was answered on the book's screen.
    func resolveConflict(keepLocal: Bool) {
        guard let book else { return }
        conflicted = false
        if keepLocal {
            _ = try? store.chooseLocal(book.key, now: Self.nowMillis())
            scheduleSync()
        } else if let place = AudioPlace.of(try? store.chooseRemote(book.key).local) {
            let start = place.openAt(book.manifest.tracks)
            load(part: start.part, offsetMs: start.offsetMs, play: playing)
        }
    }

    /// The question left for later: asked again after the next sync.
    func postponeConflict() { conflicted = false }

    /// The two places of the question, in words: this device's, then the hub's.
    func conflictPrompt() -> ReadingResumePrompt? {
        guard let book, let checkpoint = try? store.read(book.key) else { return nil }
        return ReadingResumePrompt.make(ReadingResume(checkpoint.local, conflict: true), checkpoint: checkpoint) {
            AudioPlace.label($0, tracks: book.manifest.tracks)
        }
    }

    // MARK: The player

    private func item(_ index: Int) -> AVPlayerItem? {
        guard let book, book.parts.indices.contains(index) else { return nil }
        // A track kept on this device plays from there (#37), else from the hub.
        let kept = track(index).flatMap { tracks.kept($0, in: trackCache) }
        guard let url = kept ?? URL(string: book.parts[index].url) else { return nil }
        let item = ListeningAudio.item(url, token: token)
        indexes[ObjectIdentifier(item)] = index
        return item
    }

    // MARK: Tracks kept on the device (#37)

    private var trackCache: AudioTrackCache { ListeningTracks.cache(address: address) }

    private func track(_ index: Int) -> ListeningTracks.Track? {
        guard let book else { return nil }
        return ListeningTracks.track(index, manifest: book.manifest, workId: book.workId, sourceItemId: book.sourceItemId)
    }

    /// The part playing kept on the device, then the next fetched ahead: one
    /// at a time, once the part has started, so the stream comes first.
    private func keepAhead() {
        guard let hub else { return }
        let wanted = [part, part + 1].compactMap { track($0) }
        let playing = (player.currentItem?.asset as? AVURLAsset)?.url
        tracks.keep(wanted, hub: hub, cache: trackCache, playing: playing?.isFileURL == true ? playing : nil) { [weak self] kept in
            self?.trackKept(kept)
        }
    }

    /// A track came to be kept: the part queued next from the hub is queued
    /// from the device instead, so the change of part plays at once.
    private func trackKept(_ kept: ListeningTracks.Track) {
        let items = player.items()
        guard items.count > 1, let current = player.currentItem, items[0] === current,
              let index = indexes[ObjectIdentifier(items[1])], track(index) == kept,
              (items[1].asset as? AVURLAsset)?.url.isFileURL == false, let local = item(index) else { return }
        indexes[ObjectIdentifier(items[1])] = nil
        player.remove(items[1])
        player.insert(local, after: current)
    }

    private func load(part target: Int, offsetMs: Int64, play: Bool) {
        player.pause()
        player.removeAllItems()
        indexes = [:]
        startedItem = nil
        failedItem = nil
        guard let first = item(target) else { return }
        player.insert(first, after: nil)
        if let next = item(target + 1) { player.insert(next, after: first) }
        part = target
        positionMs = max(0, offsetMs)
        partMs = book?.parts[target].durationMs ?? 0
        player.defaultRate = speed
        player.volume = sleep?.volume ?? 1
        player.actionAtItemEnd = itemEndAction()
        pendingStart = (positionMs, play)
        playing = play
    }

    private func startPolling() {
        poll?.cancel()
        lastTick = .now
        lastSave = .now
        poll = Task { [weak self] in
            while !Task.isCancelled {
                self?.tick()
                try? await Task.sleep(for: .milliseconds(250))
            }
        }
    }

    /// Four times a second while a book is on the player: what it is doing,
    /// the sleep timer, and the place every 15 seconds.
    private func tick() {
        guard book != nil, let item = player.currentItem else { return }
        let id = ObjectIdentifier(item)
        switch item.status {
        case .readyToPlay where startedItem != id:
            startedItem = id
            itemReady(item)
        case .failed where failedItem != id:
            failedItem = id
            itemFailed(item)
        default:
            break
        }
        if let index = indexes[id], index != part { partChanged(to: index) }
        if pendingStart == nil {
            let time = player.currentTime()
            if time.isNumeric { positionMs = max(0, Int64((CMTimeGetSeconds(time) * 1_000).rounded())) }
            if item.duration.isNumeric, item.duration.seconds > 0 {
                partMs = Int64((item.duration.seconds * 1_000).rounded())
            }
            let moving = player.rate > 0 || player.timeControlStatus == .waitingToPlayAtSpecifiedRate
            if moving != playing {
                playing = moving
                if !moving { save() }
            }
        }
        let now = ContinuousClock.now
        let elapsed = (now - lastTick).components
        let elapsedMs = elapsed.seconds * 1_000 + elapsed.attoseconds / 1_000_000_000_000_000
        lastTick = now
        if var timer = sleep, playing {
            // The end of the chapter, across tracks, or of the part; which one it is tells the timer when it ended.
            let now = span
            timer = timer.tick(elapsedMs: elapsedMs, entryLeftHeardMs: Listening.heard(now.leftMs, speed: speed), entry: now.entry)
            if timer.runsOut {
                // Asleep: pause, then back over what faded so it is heard again, into the track before if need be.
                sleep = nil
                player.pause()
                playing = false
                player.volume = 1
                player.actionAtItemEnd = .advance
                let back = SmartRewind.afterSleep(part: part, positionMs: positionMs, partsMs: lengths)
                seek(part: back.part, offsetMs: back.offsetMs)
            } else {
                player.volume = timer.volume
                sleep = timer
            }
        }
        if playing && now - lastSave >= .seconds(15) { save() }
        updateNowPlaying(position: true)
    }

    /// The first item can play: to its start, then playing if asked.
    private func itemReady(_ item: AVPlayerItem) {
        reloads = 0
        keepAhead()
        guard let start = pendingStart, indexes[ObjectIdentifier(item)] == part else { return }
        pendingStart = nil
        let play = start.play
        let finish: @MainActor () -> Void = { [weak self] in
            guard let self, play else { return }
            self.activateSession()
            self.onStart?()
            self.player.playImmediately(atRate: self.speed)
        }
        guard start.offsetMs > 0 else {
            finish()
            return
        }
        player.seek(to: CMTime(value: start.offsetMs, timescale: 1_000), toleranceBefore: .zero, toleranceAfter: .zero) { _ in
            Task { @MainActor in finish() }
        }
    }

    /// A track the player could not read: a book whose files changed is read
    /// again at the same place; anything else stops with a word on why.
    private func itemFailed(_ item: AVPlayerItem) {
        let status = item.errorLog()?.events.last?.errorStatusCode ?? 0
        playing = false
        if status == 412 || status == 409 {
            reload()
        } else {
            problem = status > 0 ? "The audiobook stopped (the hub answered \(status)) · Play tries again"
                : "The audiobook stopped · Play tries again"
        }
    }

    private func reload() {
        guard let book, let hub, !reloading else { return }
        guard reloads < AudiobookStream.maxReloads else {
            problem = "This audiobook's files changed. Open it again to listen."
            return
        }
        reloading = true
        reloads += 1
        let place = AudioPlace.canonical(book.manifest.tracks, part: part, offsetMs: positionMs)
        let wasPlaying = pendingStart?.play ?? true
        Task {
            defer { reloading = false }
            guard let manifest = try? await hub.fetch(HubEndpoints.readingAudioManifest(workId: book.workId,
                                                                                       sourceItemId: book.sourceItemId),
                                                      as: ReadingAudioManifest.self),
                  AudiobookStream.playable(manifest), self.book == book else {
                if self.book == book { problem = "This audiobook can no longer be streamed." }
                return
            }
            let parts = await partsFor(manifest, workId: book.workId, sourceItemId: book.sourceItemId)
            let reread = Book(workId: book.workId, sourceItemId: book.sourceItemId, title: book.title, author: book.author,
                              artwork: book.artwork, manifest: manifest, parts: parts, key: book.key)
            self.book = reread
            contents = Self.contents(of: reread)
            let start = place?.openAt(manifest.tracks) ?? (part: part, offsetMs: positionMs)
            load(part: start.part, offsetMs: start.offsetMs, play: wasPlaying)
        }
    }

    /// The queue moved on to the next part: the one after is queued and the
    /// place is kept. A sleep timer carried past an end counts to the next
    /// one when its entry begins, which its tick sees.
    private func partChanged(to index: Int) {
        part = index
        positionMs = 0
        partMs = book?.parts.indices.contains(index) == true ? (book?.parts[index].durationMs ?? 0) : 0
        if let last = player.items().last, let lastIndex = indexes[ObjectIdentifier(last)], lastIndex == index,
           let next = item(index + 1) {
            player.insert(next, after: last)
        }
        player.actionAtItemEnd = itemEndAction()
        save()
        updateNowPlaying()
        keepAhead()
    }

    /// A part played to its end: under an end-of-part timer whose entry ends
    /// with it, stop and step back over what faded; the last part, the book
    /// is finished.
    private func itemEnded(_ ended: ObjectIdentifier?) {
        guard let ended, let index = indexes[ended], let book else { return }
        let length = book.parts[index].durationMs ?? partMs
        if let timer = sleep, timer.endsWithPart, AudiobookContents.endsWithPart(contents, part: index, lengthMs: length) {
            sleep = nil
            playing = false
            player.volume = 1
            player.actionAtItemEnd = .advance
            let back = SmartRewind.afterSleep(part: index, positionMs: length, partsMs: partsMs)
            seek(part: back.part, offsetMs: back.offsetMs)
            return
        }
        if index == book.parts.count - 1 {
            playing = false
            finished = true
            positionMs = book.parts[index].durationMs ?? positionMs
            save(completed: true)
            updateNowPlaying()
        }
    }

    private func interrupted() {
        guard playing else { return }
        playing = false
        save()
        updateNowPlaying()
    }

    // MARK: The place

    /// Kept here first, with how far through the book it is beside it, so
    /// the book's page and Books Home show it before the hub has it (#30);
    /// then to the hub, the place alone, no faster than every 15 seconds (the
    /// last always goes). Finishing writes the book finished.
    private func save(completed: Bool = false) {
        guard let book else { return }
        lastSave = .now
        guard let kept = AudioPlace.kept(book.manifest.tracks, part: part, offsetMs: positionMs, completed: completed),
              (try? store.save(book.key, kept, now: Self.nowMillis())) != nil else { return }
        onKept?(book.workId)
        scheduleSync()
    }

    private func scheduleSync() {
        syncWait?.cancel()
        let wait = throttle.waitFor(now: Self.uptimeMillis())
        syncWait = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(wait))
            guard !Task.isCancelled else { return }
            self?.runSync()
        }
    }

    private func runSync() {
        guard let book else { return }
        let key = book.key
        if syncing {
            syncAgain = true
            return
        }
        syncing = true
        throttle.ran(now: Self.uptimeMillis())
        Task {
            let result = await self.sync(key)
            syncing = false
            if result == .conflict, self.book?.key == key { conflicted = true }
            if syncAgain {
                syncAgain = false
                scheduleSync()
            }
        }
    }

    /// One book's place through the outbox: read the hub's first, ask rather
    /// than overwrite when it moved, send based on the place last read.
    private func sync(_ key: ReadingCheckpointKey) async -> CheckpointSyncResult? {
        guard let hub else { return nil }
        let sync = ReadingCheckpointSync(store: store, fetch: { key in
            do {
                let answer = try await hub.fetch(HubEndpoints.readingAudioPosition(workId: key.workId, sourceItemId: key.sourceItemId),
                                                 as: ReadingAudioPositionResponse.self)
                return .available(answer.position.flatMap(AudioPlace.fromServer)?.location())
            } catch {
                return .unavailable
            }
        }, send: { checkpoint in
            guard let body = AudioPlace.body(checkpoint) else { return false }
            do {
                try await hub.send(HubEndpoints.saveReadingAudioPosition(workId: checkpoint.key.workId,
                                                                         sourceItemId: checkpoint.key.sourceItemId,
                                                                         body: body.encoded()))
                return true
            } catch {
                return false
            }
        })
        return try? await sync.sync(key)
    }

    /// The hub the places go to, and how the tracks are asked for.
    private func connect(_ app: AppModel) {
        hub = app.hub
        address = app.address
        token = app.storedToken()
        demo = app.isDemo
    }

    /// The places a closed app left unsent for this hub and profile, sent at
    /// launch and when the profile changes. A book of another hub or profile
    /// leaves the player first: its places are not this profile's.
    func flushPending(app: AppModel) async {
        let scope = ReadingCheckpointKey.scope(address: app.address, userId: app.userId)
        if let book, book.key.scope != scope { stop() }
        if book == nil { connect(app) }
        await flushPending(scope: scope)
    }

    /// Every listening place this hub and profile still has to send: those a
    /// closed app left behind.
    func flushPending(scope: String) async {
        for checkpoint in store.pending(scope: scope) where checkpoint.key.kind == AudioPlace.kind {
            if checkpoint.key == book?.key { continue }
            _ = await sync(checkpoint.key)
        }
    }

    // MARK: The lock screen and the audio session

    /// The book on the player is in front of the lock screen (`NowPlaying`,
    /// shared with the video player, #33), or no longer has anything there.
    private func enableCommands(_ on: Bool) {
        if on {
            NowPlaying.shared.take(.audiobook, commands: NowPlaying.Commands(next: true, previous: true))
        } else {
            NowPlaying.shared.release(.audiobook)
        }
    }

    /// Now Playing: the book, its author, the chapter (or part), the cover,
    /// and where in the chapter, across tracks when it is the book's own (#31).
    private func updateNowPlaying(position: Bool = false) {
        guard let book else { return }
        let span = self.span
        if position {
            // Behind the video, nothing of the book's moves on the lock screen.
            guard let info = NowPlaying.shared.info(.audiobook) else { return }
            if (info[MPNowPlayingInfoPropertyChapterNumber] as? Int) == (span.entry >= 0 ? span.entry : part) {
                // Four times a second only the moment changes, while the chapter is the same.
                NowPlaying.shared.publishPosition(.audiobook, elapsedSeconds: Double(span.positionMs) / 1_000,
                                                  rate: playing ? Double(speed) : 0,
                                                  durationSeconds: Double(span.durationMs) / 1_000)
                return
            }
        }
        // The chapter playing under the book's title, where the lock screen shows it (#31); else the author and the part.
        let partLabel = book.parts.indices.contains(part) ? AudiobookStream.partLabel(book.parts[part].title) : ""
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: book.title,
            MPMediaItemPropertyArtist: chapter ?? book.author,
            MPMediaItemPropertyAlbumTitle: chapter != nil ? book.author : partLabel,
            MPMediaItemPropertyPlaybackDuration: Double(span.durationMs) / 1_000,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: Double(span.positionMs) / 1_000,
            MPNowPlayingInfoPropertyPlaybackRate: playing ? Double(speed) : 0,
            MPNowPlayingInfoPropertyDefaultPlaybackRate: Double(speed),
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue,
            MPNowPlayingInfoPropertyChapterNumber: span.entry >= 0 ? span.entry : part,
            MPNowPlayingInfoPropertyChapterCount: span.entry >= 0 ? contents.count : book.parts.count,
        ]
        if let nowPlayingArt { info[MPMediaItemPropertyArtwork] = nowPlayingArt }
        NowPlaying.shared.publish(.audiobook, info: info, playing: playing)
    }

    private func loadArtwork() {
        guard let book, let hub, !book.artwork.isEmpty else { return }
        let path = book.artwork
        Task {
            guard let data = try? await hub.image(path), self.book?.artwork == path,
                  let art = NowPlaying.artwork(data) else { return }
            nowPlayingArt = art
            updateNowPlaying()
        }
    }

    private func activateSession() { ListeningAudio.activateSession(for: .audiobook) }

    private func deactivateSession() { ListeningAudio.deactivateSession(for: .audiobook) }

    /// A book's contents, by the manifest's lengths.
    private static func contents(of book: Book) -> [AudiobookContents.Entry] {
        AudiobookContents.entries(book.parts, partsMs: book.parts.map(\.durationMs), chapters: book.manifest.chapters)
    }

    // MARK: Clocks

    /// The wall clock, only to order this device's own saves; never compared with the hub's.
    private static func nowMillis() -> Int64 { Int64(Date().timeIntervalSince1970 * 1_000) }

    /// A clock that only moves forward, for the throttle.
    private static func uptimeMillis() -> Int64 { Int64(ProcessInfo.processInfo.systemUptime * 1_000) }
}

extension ListeningModel: NowPlayingClient {
    func remote(_ command: RemoteCommand) {
        switch command {
        case .play: play()
        case .pause: pause()
        case .toggle: toggle()
        case .skip(let forward): seek(by: (forward ? 1 : -1) * Int64(ListeningSettings.seekSeconds) * 1_000)
        case .step(let delta): step(delta)
        // The lock screen's line is the chapter's, as the player's own is (#31).
        case .seek(let millis): seek(inSpan: millis)
        }
    }

    func publishNowPlaying() { updateNowPlaying() }
}
