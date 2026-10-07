#if os(iOS)
import HubKit
import Observation
import SwiftUI

/// Read along in the book reader (#16, #19, #21; Android's read-along half
/// of `EpubReaderScreen`): the narration with the page. The sentence spoken
/// glows, and the page follows the voice while it plays, unless you turn
/// away to read on your own ("Reading"), until "Return to narrated
/// sentence". With the narration quiet, a page turned by hand is read on its
/// own: the narration's place is let go and Play starts from that page. The
/// place the reader keeps is the sentence being read, a text locator
/// (`ReadAlongLocation`), held while Readium reports its first pages
/// (`ReadAlongSession`).
///
/// The reader hands in what it knows of its page as closures: the glow, the
/// scripts, going to a sentence, the page's file, keeping the place now.
@MainActor
@Observable
final class ReadAlongReader {
    /// The narration, once it is ready; nil without one.
    private(set) var narration: NarrationModel?
    /// The page turns with the voice.
    private(set) var following = true
    /// Play starts from the page: the place was not a sentence of the
    /// narration, or the book was read on without it.
    private(set) var matchToPage = false
    /// What the dock says under the reader's own words, when there is no narration.
    private(set) var note = ""

    /// Lights the sentence spoken, or nothing.
    @ObservationIgnored var highlight: @MainActor (ReadAlongSegment?) -> Void = { _ in }
    /// Whether the element of that id is on the page's screen now.
    @ObservationIgnored var onScreen: @MainActor (String) async -> Bool = { _ in true }
    /// The first of those ids on the page's screen, if any.
    @ObservationIgnored var firstOnScreen: @MainActor ([String]) async -> String? = { _ in nil }
    /// Takes the page to a place (a locator, as JSON), as the voice moves on.
    @ObservationIgnored var go: @MainActor (String) async -> Bool = { _ in false }
    /// The file of the part on the page.
    @ObservationIgnored var pageHref: @MainActor () -> String? = { nil }
    /// Keep the place now: the narration moved it.
    @ObservationIgnored var keepPlace: @MainActor () -> Void = {}
    @ObservationIgnored var say: @MainActor (String) -> Void = { _ in }

    @ObservationIgnored private var session = ReadAlongSession()
    @ObservationIgnored private var completed = false
    /// Where the page was last taken to follow the voice, and when: the page
    /// arriving there is not a turn by hand, however late Readium says so.
    @ObservationIgnored private var followed: (href: String, at: ContinuousClock.Instant)?
    /// How long after following the voice a page reported there is still that.
    static let followSettles: Duration = .seconds(3)

    // MARK: Opening

    /// The book is opening at `locator`: no page it reports is kept until the narration is ready.
    func beginOpen() { session.beginOpen() }

    /// The narration is ready, at the sentence `locator` names; Play starts
    /// from the page when it names none.
    func start(_ prepared: NarrationModel.Narration, workId: String, token: String, at locator: String?) {
        let resume = locator.flatMap { ReadAlongLocation.resume($0, prepared.timeline) }
        matchToPage = locator != nil && resume == nil
        let narration = NarrationModel(prepared, workId: workId, token: token, initial: resume)
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
        self.narration = narration
        following = true
        session.ready(resume)
        if let segment = narration.segment { highlight(segment) }
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

    /// "Following", "Reading", or "Alignment unavailable" on a page the narration never reaches.
    var followLabel: String {
        guard let narration else { return "" }
        return ReadAlongFollow.label(following: following, narrated: pageHref().map(narration.timeline.narrates) ?? true)
    }

    /// Time left while the page follows the voice: the narration's own.
    var timeLeft: TimeLeft? {
        guard let narration, following else { return nil }
        return TimeLeft.ofNarration(narration.timeline, narration.position, speed: narration.speed)
    }

    /// The sentence spoken changed: it glows, and the page follows it there
    /// when it is off the screen.
    private func spoken(_ segment: ReadAlongSegment?) {
        highlight(segment)
        guard let segment, following else { return }
        Task {
            if pageHref() == segment.textHref, await onScreen(segment.fragment) { return }
            guard let locator = BookLocator.canonical(["href": segment.textHref, "type": "application/xhtml+xml",
                                                       "locations": ["fragments": [segment.fragment]]]) else { return }
            followed = (segment.textHref, .now)
            _ = await go(locator)
        }
    }

    /// The page moved to `href`, by hand or by the voice: by hand while the
    /// voice reads, the page stops following it; quiet, the book is read on its own.
    func pageMoved(to href: String) {
        guard let narration else { return }
        if let followed, followed.href == href, ContinuousClock.now - followed.at < Self.followSettles { return }
        if narration.playing {
            following = false
        } else {
            readOnAlone()
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
        guard let narration else { return }
        following = true
        if matchToPage {
            listenFromPage(play: false)
            return
        }
        let segment = narration.timeline.active(track: narration.position.track, offsetMs: narration.position.offsetMs)
            ?? narration.segment
        spoken(segment)
    }

    /// Play or pause; from the page when the narration's place was let go.
    func togglePlay() {
        guard let narration else { return }
        if matchToPage && !narration.playing {
            listenFromPage(play: true)
        } else {
            following = true
            narration.toggle()
        }
    }

    /// The sentence before or after (L1 and R1), the page with it.
    func stepSentence(_ delta: Int) {
        guard let narration else { return }
        matchToPage = false
        following = true
        if !narration.stepSentence(delta) { say(delta > 0 ? "The narration's last sentence" : "The narration's first sentence") }
    }

    /// The narration to the first narrated sentence on the page.
    func listenFromPage(play: Bool) {
        guard let narration else { return }
        Task {
            // A book just opened has not said where its page is yet (seen on
            // the simulator: over a second), and a page being laid out shows
            // nothing: it is asked again, for up to six seconds.
            var href = ""
            var fragment: String?
            for attempt in 0..<15 {
                if attempt > 0 { try? await Task.sleep(for: .milliseconds(400)) }
                href = pageHref() ?? ""
                let ids = narration.timeline.fragments(in: href)
                guard !ids.isEmpty else {
                    #if DEBUG
                    NSLog("readalong: listen from the page: %@ has no narrated sentence", href)
                    #endif
                    continue
                }
                fragment = await firstOnScreen(ids)
                #if DEBUG
                NSLog("readalong: listen from the page: %@, %ld sentences, on screen %@", href, ids.count, fragment ?? "none")
                #endif
                if fragment != nil { break }
            }
            guard let fragment, let target = narration.timeline.find(href: href, fragment: fragment) else {
                say("No narrated sentence on this page. Turn to a narrated page and try again.")
                return
            }
            matchToPage = false
            completed = false
            following = true
            narration.seek(to: target)
            session.record(target)
            keepPlace()
            if play && !narration.playing { narration.play() }
        }
    }

    // MARK: Leaving

    /// The app went to the background: the narration stops, its place kept.
    func pause() { narration?.pause() }

    func release() {
        narration?.release()
        narration = nil
    }
}
#endif
