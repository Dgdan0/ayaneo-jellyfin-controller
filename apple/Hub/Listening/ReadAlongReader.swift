#if os(iOS)
import HubKit
import Observation
import SwiftUI

/// Read along in the book reader (#16, #19, #21, #49; Android's read-along
/// half of `EpubReaderScreen`): the narration with the page. The sentence
/// spoken glows, and while the narration plays the voice and the page move
/// each other (`ReadAlongPageFollow`): the voice turns the page as it reaches
/// the next page's first word, inside a sentence too, and a page turned or
/// jumped to by hand takes the voice to its own first word, unless the
/// sentence being spoken is still on it. With the narration quiet, a page
/// turned by hand moves nothing: the narration's place is let go and Play
/// starts from that page's first word. The narration plays on with the
/// screen locked and the app in the background, and back in the app the
/// page catches up with the voice. The place the reader keeps is the
/// sentence being read, a text locator (`ReadAlongLocation`), held while
/// Readium reports its first pages (`ReadAlongSession`).
///
/// The reader hands in what it knows of its page as closures: the glow, the
/// scripts, turning and going, the page's file, keeping the place now.
@MainActor
@Observable
final class ReadAlongReader {
    /// The narration, once it is ready; nil without one.
    private(set) var narration: NarrationModel?
    /// Play starts from the page: the place was not a sentence of the
    /// narration, or the book was read on without it.
    private(set) var matchToPage = false
    /// What the dock says under the reader's own words, when there is no narration.
    private(set) var note = ""
    #if DEBUG
    /// Debug builds' UI tests read these (`HUB_DEBUG_READALONG`): the voice's
    /// last turn of the page, and what the last page turned by hand did.
    private(set) var debugTurn = "none"
    private(set) var debugHand = "none"
    var debugLine: String { "\(debugTurn) · \(debugHand)" }
    #endif

    /// Lights the sentence spoken, or nothing.
    @ObservationIgnored var highlight: @MainActor (ReadAlongSegment?) -> Void = { _ in }
    /// The page script's answer to `ReadAlongPageScript.edges` for those ids.
    @ObservationIgnored var edges: @MainActor ([String]) async -> Any? = { _ in nil }
    /// The page script's answer to `ReadAlongPageScript.firstAfter` for those ids.
    @ObservationIgnored var firstAfter: @MainActor ([String]) async -> Any? = { _ in nil }
    /// Takes the page to a place (a locator, as JSON), as the voice moves on.
    @ObservationIgnored var go: @MainActor (String) async -> Bool = { _ in false }
    /// Turns the page on (1) or back (-1), as a reader turns it.
    @ObservationIgnored var turn: @MainActor (Int) async -> Bool = { _ in false }
    /// The book scrolls rather than turning pages.
    @ObservationIgnored var scrolls: @MainActor () -> Bool = { false }
    /// The file of the part on the page.
    @ObservationIgnored var pageHref: @MainActor () -> String? = { nil }
    /// The title of the part on the page, for the lock screen.
    @ObservationIgnored var chapter: @MainActor () -> String? = { nil }
    /// Keep the place now: the narration moved it.
    @ObservationIgnored var keepPlace: @MainActor () -> Void = {}
    @ObservationIgnored var say: @MainActor (String) -> Void = { _ in }

    @ObservationIgnored private var session = ReadAlongSession()
    @ObservationIgnored private var completed = false
    /// What the page is doing for the voice or after the hand, one thing at a
    /// time, and which: a job cancelled by the background ends late and must
    /// not end the next one.
    @ObservationIgnored private var pageWork: Task<Void, Never>?
    @ObservationIgnored private var pageJob = 0
    /// The voice moved while the page was busy: the page follows it once it is free.
    @ObservationIgnored private var followAgain = false
    /// The page reported a move while it was busy, its own or a hand's: once
    /// free, it is checked as a turn by hand is, which changes nothing when
    /// the page shows the voice.
    @ObservationIgnored private var checkAgain = false
    /// When the page was last moved for the voice: with the narration quiet,
    /// Readium's late reports of that move are not the book read on alone.
    @ObservationIgnored private var movedForVoice: ContinuousClock.Instant?
    /// Where the voice turns the page on screen: its next page's first word.
    @ObservationIgnored private var turnAt: ReadAlongPosition?
    /// The app is in the background: the voice plays on, and the page, which
    /// cannot be asked or moved there, catches up when the app comes back.
    @ObservationIgnored private var away = false
    /// How long after the page moved for the voice a report of a page is still that move, while quiet.
    static let voiceSettles: Duration = .milliseconds(800)

    // MARK: Opening

    /// The book is opening at `locator`: no page it reports is kept until the narration is ready.
    func beginOpen() { session.beginOpen() }

    /// The narration is ready, at the sentence `locator` names; Play starts
    /// from the page when it names none.
    func start(_ prepared: NarrationModel.Narration, workId: String, token: String, at locator: String?,
               book: NarrationModel.Book) {
        let resume = locator.flatMap { ReadAlongLocation.resume($0, prepared.timeline) }
        matchToPage = locator != nil && resume == nil
        let narration = NarrationModel(prepared, workId: workId, token: token, initial: resume, book: book)
        narration.onSegment = { [weak self] segment in self?.spoken(segment) }
        narration.onSave = { [weak self] point, completed in
            guard let self else { return }
            self.session.record(point)
            self.completed = completed
            self.keepPlace()
        }
        narration.onError = { [weak self] in
            self?.say("Narration playback failed. Your place is kept; reading is still available.")
        }
        narration.onTick = { [weak self] now in self?.ticked(now) }
        narration.onRemote = { [weak self] command in self?.remote(command) }
        narration.chapter = { [weak self] in self?.chapter() }
        self.narration = narration
        session.ready(resume)
        if let segment = narration.segment { highlight(segment) }
        #if DEBUG
        // HUB_READALONG_SENTENCE=<id>, debug builds: the narration paused at that sentence, lit and on
        // the page, for UI tests to look at a sentence where they choose (#52).
        if let id = ProcessInfo.processInfo.environment["HUB_READALONG_SENTENCE"],
           let segment = prepared.timeline.tracks.flatMap(\.segments).first(where: { $0.fragment == id }),
           let target = prepared.timeline.begin(of: segment) {
            matchToPage = false
            narration.seek(to: target)
        }
        #endif
    }

    /// The book opened without its narration, and why.
    func startWithout(_ note: String) {
        self.note = note
        session.endOpenIfPending()
    }

    /// The opening ended, however it went.
    func endOpen() { session.endOpenIfPending() }

    // MARK: The place

    /// The page may be kept now: not while opening, and a page without the
    /// narration may not replace the narration's place.
    var canKeepPage: Bool { session.canSavePage(narrationAvailable: narration != nil) }

    /// The page's locator as the place to keep: moved to the sentence being
    /// read while the narration has a place.
    func place(_ pageLocator: String) -> String {
        guard let narration, let point = session.pointForSave(narration.playing ? narration.position : nil) else {
            return pageLocator
        }
        return ReadAlongLocation.save(pageLocator, narration.timeline, point: point, completed: completed)
    }

    // MARK: The voice and the page

    /// "Following" while the page is the narration's, "Alignment unavailable"
    /// on a page the narration never reaches. Reading on alone while the voice
    /// plays elsewhere ("Reading") is no more (#49).
    var followLabel: String {
        guard let narration else { return "" }
        return ReadAlongFollow.label(following: true, narrated: pageHref().map(narration.timeline.narrates) ?? true)
    }

    /// Time left while the voice reads: the narration's own.
    var timeLeft: TimeLeft? {
        guard let narration, !matchToPage else { return nil }
        return TimeLeft.ofNarration(narration.timeline, narration.position, speed: narration.speed)
    }

    /// The sentence spoken changed: it glows, and the page goes to the voice
    /// when it is not on the page.
    private func spoken(_ segment: ReadAlongSegment?) {
        guard !away else { return }
        highlight(segment)
        if segment != nil { followVoice() }
    }

    /// Ten times a second while the voice reads: at the next page's first word, the page turns.
    private func ticked(_ now: ReadAlongPosition) {
        guard !away, pageWork == nil, let turnAt, now >= turnAt else { return }
        self.turnAt = nil
        followVoice()
    }

    /// The page to the voice: a turn when the voice has gone on into the next
    /// page (or back into the one before), else to its sentence; then where
    /// the page on screen ends, for the next turn.
    private func followVoice() {
        guard narration != nil, !away else { return }
        if pageWork != nil {
            followAgain = true
            return
        }
        pageJob += 1
        let job = pageJob
        pageWork = Task { [weak self] in
            await self?.bringPageToVoice()
            self?.pageDone(job, byHand: false, segment: nil)
        }
    }

    private func bringPageToVoice() async {
        guard let narration else { return }
        // A few steps at most: to the sentence, then a page on when the voice is past its page's end.
        var before: ReadAlongPageSpan?
        for step in 0..<4 {
            guard !Task.isCancelled, !away else { return }
            if step > 0 {
                // The page settles after a move before it is measured again.
                try? await Task.sleep(for: .milliseconds(150))
            }
            let span = await measure()
            // A move that left the page where it was (the book's last page): nothing more to do.
            if step > 0, let span, span == before { return }
            before = span
            let now = narration.position
            var move = ReadAlongPageFollow.move(now, on: span)
            // Scrolling, the page has no next page to turn to: it goes to the sentence.
            if scrolls() && (move == .forward || move == .backward) { move = .go }
            switch move {
            case .stay:
                turnAt = scrolls() ? nil : span?.end
                return
            case .forward, .backward:
                movedForVoice = .now
                #if DEBUG
                if move == .forward, let segment = narration.segment, let begin = narration.timeline.begin(of: segment) {
                    debugTurn = "turned in \(segment.fragment) +\(now.offsetMs - begin.offsetMs)ms at \(now.track):\(now.offsetMs)"
                }
                #endif
                guard await turn(move == .forward ? 1 : -1) else { return }
            case .go:
                guard let segment = narration.timeline.sentence(atOrAfter: now),
                      let locator = BookLocator.canonical(["href": segment.textHref, "type": "application/xhtml+xml",
                                                           "locations": ["fragments": [segment.fragment]]]) else { return }
                movedForVoice = .now
                guard await go(locator) else { return }
            }
            movedForVoice = .now
        }
    }

    /// The page on screen as the narration sees it, nil when nothing on it is narrated.
    private func measure() async -> ReadAlongPageSpan? {
        guard let narration, let href = pageHref() else { return nil }
        let ids = narration.timeline.fragments(in: href)
        guard !ids.isEmpty, let edges = ReadAlongPageEdges.parse(await edges(ids)) else { return nil }
        return narration.timeline.span(href: href, edges: edges)
    }

    /// The page moved to `href`, by hand or by the voice: by hand while the
    /// voice reads, the voice goes with it (`ReadAlongPageFollow.handMoved`);
    /// quiet, the book is read on its own. While the voice reads, a report is
    /// never set aside by its time: Readium's late report of a move made for
    /// the voice finds the page showing the voice, which changes nothing,
    /// and a turn by hand just after the voice's is a turn by hand.
    func pageMoved(to href: String) {
        guard let narration else { return }
        if pageWork != nil {
            // Busy: checked once the page is free.
            if narration.playing { checkAgain = true }
            return
        }
        guard narration.playing else {
            // The page moving for the voice is not the book read on alone, however late Readium says so.
            if let at = movedForVoice, ContinuousClock.now - at < Self.voiceSettles { return }
            turnAt = nil
            readOnAlone()
            return
        }
        handMoved()
    }

    private func handMoved() {
        guard let narration, !away, pageWork == nil else { return }
        let speaking = narration.segment
        pageJob += 1
        let job = pageJob
        pageWork = Task { [weak self] in
            await self?.voiceToPage()
            self?.pageDone(job, byHand: true, segment: speaking)
        }
    }

    /// A page turned or jumped to by hand while the voice reads.
    private func voiceToPage() async {
        guard let narration else { return }
        let span = await measure()
        guard !Task.isCancelled, narration.playing else { return }
        let now = narration.position
        let speaking = narration.segment.flatMap { narration.timeline.begin(of: $0) }
        switch ReadAlongPageFollow.handMoved(now, speaking: speaking, on: span) {
        case .stay(let next):
            turnAt = scrolls() ? nil : next
        case .seek(let start):
            // The page's first word, read on from there.
            turnAt = scrolls() ? nil : span?.end
            #if DEBUG
            debugHand = "moved to \(start.track):\(start.offsetMs) from \(now.track):\(now.offsetMs)"
            #endif
            completed = false
            narration.seek(to: start)
            session.record(start)
            keepPlace()
        case .unnarrated:
            // Nothing here for the voice: it stops, and Play starts from the page it is turned to.
            narration.pause()
            readOnAlone()
            say("This page has no narration, so the voice paused")
        }
    }

    /// The page is free again: what the voice did meanwhile is followed, and
    /// a move the page reported meanwhile is checked as a turn by hand.
    private func pageDone(_ job: Int, byHand: Bool, segment: ReadAlongSegment?) {
        guard job == pageJob else { return }
        pageWork = nil
        if !byHand { movedForVoice = .now }
        let follow = followAgain, check = checkAgain
        followAgain = false
        checkAgain = false
        // After a turn by hand the voice is where the page put it; it is followed only once it has moved on.
        if follow && (!byHand || narration?.segment != segment) {
            followVoice()
        } else if check && narration?.playing == true {
            handMoved()
        }
    }

    /// Reading on without the narration: its place is let go, and Play starts from the page.
    private func readOnAlone() {
        guard !matchToPage else { return }
        session.switchToText()
        completed = false
        matchToPage = true
        highlight(nil)
    }

    /// Back to the sentence being read (the dock's button, L3).
    func follow() {
        guard narration != nil else { return }
        if matchToPage {
            listenFromPage(play: false)
            return
        }
        followVoice()
    }

    /// Play or pause; from the page when the narration's place was let go.
    func togglePlay() {
        guard let narration else { return }
        if matchToPage && !narration.playing {
            listenFromPage(play: true)
        } else {
            narration.toggle()
            if narration.playing { followVoice() }
        }
    }

    /// The sentence before or after (L1 and R1), the page with it.
    func stepSentence(_ delta: Int) {
        guard let narration else { return }
        matchToPage = false
        if !narration.stepSentence(delta) { say(delta > 0 ? "The narration's last sentence" : "The narration's first sentence") }
    }

    /// The narration to the first word on the page: inside a sentence the
    /// page begins in the middle of, as the voice would say it there.
    func listenFromPage(play: Bool) {
        guard let narration else { return }
        Task {
            // A book just opened has not said where its page is yet (seen on
            // the simulator: over a second), and a page being laid out shows
            // nothing: it is asked again, for up to six seconds.
            var span: ReadAlongPageSpan?
            var next: ReadAlongPosition?
            for attempt in 0..<15 {
                if attempt > 0 { try? await Task.sleep(for: .milliseconds(400)) }
                span = await measure()
                // A narrated part's page with none of its sentences on it (its
                // heading at a large size, a picture): the next sentence of it.
                if span == nil { next = await sentenceAfterPage() }
                #if DEBUG
                NSLog("readalong: listen from the page: %@, %@", pageHref() ?? "no page", span.map { "\($0.start)" } ?? "nothing narrated")
                #endif
                if span != nil || next != nil { break }
            }
            guard let start = span?.start ?? next else {
                say("No narrated sentence on this page. Turn to a narrated page and try again.")
                return
            }
            matchToPage = false
            completed = false
            narration.seek(to: start)
            turnAt = scrolls() ? nil : span?.end
            session.record(start)
            keepPlace()
            if play && !narration.playing { narration.play() }
            // From a sentence beyond the page, the page goes to it.
            if span == nil { followVoice() }
        }
    }

    /// Where the narration goes on from a page of a narrated part that shows
    /// none of its sentences: the part's first sentence beyond the page, else
    /// the first after the part. Nil for a part with no narration, or a page
    /// that has not been laid out yet.
    private func sentenceAfterPage() async -> ReadAlongPosition? {
        guard let narration, let href = pageHref() else { return nil }
        let ids = narration.timeline.fragments(in: href)
        guard !ids.isEmpty else { return nil }
        // Null: the page is not laid out yet, asked again.
        guard let id = await firstAfter(ids) as? String else { return nil }
        if let segment = narration.timeline.tracks.flatMap(\.segments).first(where: { $0.textHref == href && $0.fragment == id }) {
            return narration.timeline.begin(of: segment)
        }
        return narration.timeline.sentence(after: href).flatMap { narration.timeline.begin(of: $0) }
    }

    // MARK: The lock screen and the background

    /// A command from the lock screen, Control Center or the headphones.
    private func remote(_ command: RemoteCommand) {
        guard let narration else { return }
        switch command {
        case .play:
            if !narration.playing { remotePlay() }
        case .pause: narration.pause()
        case .toggle: if narration.playing { narration.pause() } else { remotePlay() }
        case .skip(let forward): narration.jump(by: (forward ? 1 : -1) * Int64(ListeningSettings.seekSeconds) * 1_000)
        case .step(let delta): stepSentence(delta)
        case .seek(let millis): narration.seek(to: ReadAlongPosition(track: narration.position.track, offsetMs: millis))
        }
    }

    /// Play from the lock screen: from the page when it can be asked, else
    /// where the voice was (the page catches up when the app comes back).
    private func remotePlay() {
        guard let narration else { return }
        if matchToPage && !away {
            listenFromPage(play: true)
        } else {
            matchToPage = false
            narration.play()
        }
    }

    /// The app went to the background, or came back: the voice reads on
    /// either way; back, the sentence glows and the page catches up with it.
    func scene(active: Bool) {
        guard active == away else { return }
        away = !active
        if away {
            pageWork?.cancel()
            pageWork = nil
            pageJob += 1
            followAgain = false
            checkAgain = false
            turnAt = nil
        } else if let narration, !matchToPage {
            highlight(narration.segment)
            followVoice()
        }
    }

    // MARK: Leaving

    func release() {
        pageWork?.cancel()
        pageWork = nil
        pageJob += 1
        narration?.release()
        narration = nil
    }
}
#endif
