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
    var partLeftMs: Int64 { Listening.partLeft(positionMs: positionMs, partMs: partMs, speed: speed) }
    var bookLeftMs: Int64? { Listening.bookLeft(part: part, positionMs: positionMs, partsMs: partsMs, speed: speed) }
    /// What the line, its times and the lock screen measure (#31): the
    /// chapter playing, across tracks when it is the book's own; else the part.
    var span: AudiobookContents.Span {
        AudiobookContents.span(contents, part: part, positionMs: positionMs, partMs: partMs, partsMs: partsMs)
    }
    /// Left of the chapter (or part) playing, as heard.
    var spanLeftMs: Int64 { Listening.heard(span.leftMs, speed: speed) }
    /// "chapter" where the hub found chapters, else "part".
    var noun: String { AudiobookContents.noun(book?.manifest.chapters ?? []) }
    /// The tracks' lengths, the part playing by the player's own.
    private var lengths: [Int64?] {
        var known = partsMs
        if partMs > 0 && known.indices.contains(part) { known[part] = partMs }
        return known
    }

    /// Pauses whatever else plays (the video) when the book starts: set by the shell.
    @ObservationIgnored var onStart: (() -> Void)?

    @ObservationIgnored private let player = AVQueuePlayer()
    @ObservationIgnored private let store = ListeningStore.shared
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
        setUpRemoteCommands()
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

    /// Sets the sleep timer, or nil to cancel it.
    func setSleep(_ choice: SleepChoice?) {
        player.volume = 1
        lastTick = .now
        sleep = choice.map { SleepTimer.start($0, partLeftHeardMs: partLeftMs) }
        player.actionAtItemEnd = sleep?.endsWithPart == true ? .pause : .advance
    }

    /// Any control while the sleep timer fades keeps you listening.
    func touched() {
        guard let timer = sleep, timer.fading else { return }
        let next = partsMs.indices.contains(part + 1) ? partsMs[part + 1].map { Listening.heard($0, speed: speed) } : nil
        player.volume = 1
        sleep = timer.extended(partLeftHeardMs: partLeftMs, nextPartHeardMs: next)
        player.actionAtItemEnd = sleep?.endsWithPart == true ? .pause : .advance
    }

    /// Takes the book off the player, its place kept.
    func stop() {
        guard book != nil else { return }
        save()
        poll?.cancel()
        poll = nil
        player.pause()
        player.removeAllItems()
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
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
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
        guard let book, book.parts.indices.contains(index), let url = URL(string: book.parts[index].url) else { return nil }
        let item = ListeningAudio.item(url, token: token)
        indexes[ObjectIdentifier(item)] = index
        return item
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
        player.actionAtItemEnd = sleep?.endsWithPart == true ? .pause : .advance
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
            timer = timer.tick(elapsedMs: elapsedMs, partLeftHeardMs: partLeftMs)
            if timer.runsOut {
                // Asleep: pause, then back over what faded so it is heard again.
                sleep = nil
                player.pause()
                playing = false
                player.volume = 1
                player.actionAtItemEnd = .advance
                seek(part: part, offsetMs: SmartRewind.afterSleep(positionMs))
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

    /// The queue moved on to the next part: the one after is queued, the
    /// sleep timer counts to the new part's end, and the place is kept.
    private func partChanged(to index: Int) {
        part = index
        positionMs = 0
        partMs = book?.parts.indices.contains(index) == true ? (book?.parts[index].durationMs ?? 0) : 0
        if let last = player.items().last, let lastIndex = indexes[ObjectIdentifier(last)], lastIndex == index,
           let next = item(index + 1) {
            player.insert(next, after: last)
        }
        sleep = sleep?.partChanged(partLeftHeardMs: Listening.heard(partMs, speed: speed))
        player.actionAtItemEnd = sleep?.endsWithPart == true ? .pause : .advance
        save()
        updateNowPlaying()
    }

    /// A part played to its end: under an end-of-part timer, stop and step
    /// back over what faded; the last part, the book is finished.
    private func itemEnded(_ ended: ObjectIdentifier?) {
        guard let ended, let index = indexes[ended], let book else { return }
        if let timer = sleep, timer.endsWithPart {
            sleep = nil
            playing = false
            player.volume = 1
            player.actionAtItemEnd = .advance
            let length = book.parts[index].durationMs ?? partMs
            seek(part: index, offsetMs: SmartRewind.afterSleep(length))
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

    private func setUpRemoteCommands() {
        let center = MPRemoteCommandCenter.shared()
        center.playCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.play() }
            return .success
        }
        center.pauseCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.pause() }
            return .success
        }
        center.togglePlayPauseCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.toggle() }
            return .success
        }
        center.skipForwardCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.seek(by: Int64(ListeningSettings.seekSeconds) * 1_000) }
            return .success
        }
        center.skipBackwardCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.seek(by: -Int64(ListeningSettings.seekSeconds) * 1_000) }
            return .success
        }
        center.nextTrackCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.step(1) }
            return .success
        }
        center.previousTrackCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.step(-1) }
            return .success
        }
        center.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let event = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            let target = Int64(event.positionTime * 1_000)
            // The lock screen's line is the chapter's, as the player's own is.
            Task { @MainActor in self?.seek(inSpan: target) }
            return .success
        }
        enableCommands(false)
    }

    private func enableCommands(_ on: Bool) {
        let center = MPRemoteCommandCenter.shared()
        let interval = [NSNumber(value: ListeningSettings.seekSeconds)]
        center.skipForwardCommand.preferredIntervals = interval
        center.skipBackwardCommand.preferredIntervals = interval
        for command in [center.playCommand, center.pauseCommand, center.togglePlayPauseCommand, center.skipForwardCommand,
                        center.skipBackwardCommand, center.nextTrackCommand, center.previousTrackCommand,
                        center.changePlaybackPositionCommand] {
            command.isEnabled = on
        }
    }

    /// Now Playing: the book, its author, the chapter (or part), the cover,
    /// and where in the chapter, across tracks when it is the book's own (#31).
    private func updateNowPlaying(position: Bool = false) {
        guard let book else { return }
        let center = MPNowPlayingInfoCenter.default()
        let span = self.span
        if position, var info = center.nowPlayingInfo,
           (info[MPNowPlayingInfoPropertyChapterNumber] as? Int) == (span.entry ?? part) {
            // Four times a second only the moment changes, while the chapter is the same.
            info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = Double(span.positionMs) / 1_000
            info[MPMediaItemPropertyPlaybackDuration] = Double(span.durationMs) / 1_000
            info[MPNowPlayingInfoPropertyPlaybackRate] = playing ? Double(speed) : 0
            center.nowPlayingInfo = info
            return
        }
        let named = span.entry != nil ? span.title
            : book.parts.indices.contains(part) ? AudiobookStream.partLabel(book.parts[part].title) : ""
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: book.title,
            MPMediaItemPropertyArtist: book.author,
            MPMediaItemPropertyAlbumTitle: named,
            MPMediaItemPropertyPlaybackDuration: Double(span.durationMs) / 1_000,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: Double(span.positionMs) / 1_000,
            MPNowPlayingInfoPropertyPlaybackRate: playing ? Double(speed) : 0,
            MPNowPlayingInfoPropertyDefaultPlaybackRate: Double(speed),
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue,
            MPNowPlayingInfoPropertyChapterNumber: span.entry ?? part,
            MPNowPlayingInfoPropertyChapterCount: span.entry != nil ? contents.count : book.parts.count,
        ]
        if let nowPlayingArt { info[MPMediaItemPropertyArtwork] = nowPlayingArt }
        center.nowPlayingInfo = info
        #if os(macOS)
        center.playbackState = playing ? .playing : .paused
        #endif
    }

    private func loadArtwork() {
        guard let book, let hub, !book.artwork.isEmpty else { return }
        let path = book.artwork
        Task {
            guard let data = try? await hub.image(path), self.book?.artwork == path,
                  let art = Self.artwork(data) else { return }
            nowPlayingArt = art
            updateNowPlaying()
        }
    }

    /// Made away from the main actor: the lock screen asks for the picture
    /// on a queue of its own.
    nonisolated private static func artwork(_ data: Data) -> MPMediaItemArtwork? {
        #if os(iOS)
        guard let image = UIImage(data: data) else { return nil }
        #else
        guard let image = NSImage(data: data) else { return nil }
        #endif
        return MPMediaItemArtwork(boundsSize: image.size) { _ in image }
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
