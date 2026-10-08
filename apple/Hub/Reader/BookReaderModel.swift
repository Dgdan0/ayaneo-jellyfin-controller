#if os(iOS)
import Foundation
import HubKit
import Observation
import UIKit

/// A control in the book's menu, in the order the pad moves through them.
enum BookControl: Hashable {
    case close, contents, search, bookmark, appearance, keys, previous, returnPlace, next, slider
    /// Read along's dock, the lower bar while the narration is there (#21).
    case narrationBack, narrationPlay, narrationForward, narrationSpeed, narrationFollow

    /// The dock's control, for the pad's ring on it.
    var dock: ReadAlongControl? {
        switch self {
        case .narrationBack: .back
        case .narrationPlay: .play
        case .narrationForward: .forward
        case .narrationSpeed: .speed
        case .narrationFollow: .follow
        default: nil
        }
    }

    var label: String {
        switch self {
        case .close: "Close the book"
        case .contents: "Contents"
        case .search: "Search this book"
        case .bookmark: "Bookmark"
        case .appearance: "Appearance"
        case .keys: "Keys"
        case .previous: "Previous page"
        case .returnPlace: "Return to previous place"
        case .next: "Next page"
        case .slider: "Where in the book"
        case .narrationBack: "Back"
        case .narrationPlay: "Play narration"
        case .narrationForward: "Forward"
        case .narrationSpeed: "Narration speed"
        case .narrationFollow: "Return to narrated sentence"
        }
    }

    var systemImage: String {
        switch self {
        case .close: "xmark"
        case .contents: "list.bullet"
        case .search: "magnifyingglass"
        case .bookmark: "bookmark"
        case .appearance: "textformat.size"
        case .keys: "gamecontroller"
        case .previous: "chevron.left"
        case .returnPlace: "arrow.uturn.backward"
        case .next: "chevron.right"
        case .slider: "slider.horizontal.below.rectangle"
        case .narrationBack: "gobackward"
        case .narrationPlay: "play.fill"
        case .narrationForward: "goforward"
        case .narrationSpeed: "gauge.with.dots.needle.67percent"
        case .narrationFollow: "text.line.first.and.arrowtriangle.forward"
        }
    }
}

/// The ebook reader (#25, phase 4): Android's `EpubReaderScreen`, its read
/// along (`readAlong`, `ReadAlongReader`) with it. It downloads the EPUB once
/// (`EpubPackageCache`), opens it in Readium (`BookNavigator`) where its
/// keeper says the place is, and carries out `ReaderPadMap`'s book commands
/// from a controller, a keyboard and touch: Ⓑ opens the menu with the page
/// making room for it and Ⓑ again leaves, Ⓐ and Space read on, the D-pad
/// turns pages or scrolls, L2 and R2 move by chapter, Ⓧ bookmarks and Ⓨ
/// opens the contents. Under the title it says how long is left, from a
/// pace learnt as you read (`ReadingPace`); a note opens as a card and the
/// page stays; a link followed leaves "Return to previous place" in the menu.
@MainActor
@Observable
final class BookReaderModel {
    enum Phase: Equatable {
        case opening(String)
        case reading
        case failed(String)
        /// Another device moved the place, or the hub could not be asked: which place.
        case choosing(ReadingResumePrompt)
    }

    enum Sheet: Equatable {
        case contents, bookmarks, search, appearance, keys
    }

    /// The search sheet's state (#37): nothing asked yet, searching, what
    /// was found, or why nothing could be.
    enum SearchState: Equatable {
        case idle, searching
        case found([BookSearchHit])
        case problem(BookSearch.Problem)
    }

    enum AppearanceTab: Hashable, CaseIterable {
        case font, layout, themes, comfort

        /// Its lines' page in HubKit's walk (#25).
        var page: BookAppearancePage {
            switch self {
            case .font: .font
            case .layout: .layout
            case .themes: .themes
            case .comfort: .comfort
            }
        }
    }

    let workId: String
    let sourceItemId: String
    let title: String
    let cover: String
    /// Reading along (#16, #19): the read-along edition without its audio,
    /// the narration streamed from the audiobook's tracks, the sentence spoken
    /// glowing and the page following the voice; nil for the ebook alone.
    let readAlong: ReadAlongReader?

    private(set) var phase: Phase = .opening("Opening the book…")
    /// Readium's navigator, shown by the screen once the book is open.
    private(set) var controller: UIViewController?
    private(set) var controlsVisible = false
    var sheet: Sheet? {
        // The search goes when its sheet does; what it found stays for the next time.
        didSet { if sheet != .search { stopSearching() } }
    }
    /// The words typed in the search sheet, and what the search found.
    var searchText = ""
    private(set) var searchState = SearchState.idle
    @ObservationIgnored private var searching: Task<Void, Never>?
    @ObservationIgnored private var searchTimer: Task<Void, Never>?
    /// The passage a search opened is marked until the reading moves on.
    @ObservationIgnored private var markedFound = false
    var appearanceTab = AppearanceTab.font
    /// Spacing, Font's page of line spacing and margins, is open (#47).
    var appearanceSpacing = false
    /// The page of Appearance the sheet shows, and a controller walks.
    var appearancePage: BookAppearancePage { appearanceSpacing ? .spacing : appearanceTab.page }
    /// Where a controller's ring is in Appearance, and which part of Keys it is on (#25).
    var appearanceWalk = SheetWalk()
    var keysPart = 0
    /// A note's words while its card is open.
    private(set) var footnote: String?
    private(set) var notice: String?
    private(set) var contents: [BookContentsRow] = []
    /// The contents line of the part on the page.
    private(set) var currentContentsRow: Int?
    private(set) var bookmarks: [EpubBookmark] = []
    private(set) var bookmarked = false
    /// "12 min left in chapter · 4h 10m in book".
    private(set) var timeLeft = ""
    /// "Four · Page 3 of 12 in chapter · 49% of book".
    private(set) var positionLine = "Opening…"
    private(set) var bookProgress = 0.0
    /// Kindle's corners while reading (#42): which show, kept on this device.
    private(set) var pageInfo: PageInfoPreferences
    /// What the bottom corners say of the page.
    private(set) var corners = PageInfoCorners()
    /// Where the page is, as the corners can say it.
    @ObservationIgnored private var reading = PageInfo.Reading()
    /// The book's pages as the hub counts them: this edition's, else the longest text edition's.
    @ObservationIgnored private let bookPages: Int
    /// The slider dragged, or moved with the pad: where it would go.
    var browsing: Double?
    /// Where a link, the contents or the slider was followed from.
    private(set) var returnPlace: String?
    private(set) var preferences: EpubReaderPreferences
    /// The control the pad is on while the menu is open.
    var focusedControl = BookControl.contents
    /// The contents or bookmark line the pad is on while that sheet is open.
    var sheetCursor = 0
    /// The pad has moved the sheet's cursor: the chapter on the page no longer places it.
    @ObservationIgnored private var cursorMoved = false
    var controllerActive = false
    /// Set to leave: the screen closes the reader.
    var leaving = false
    /// The book was marked unread (#37): it opens at its beginning, whatever place was kept.
    @ObservationIgnored var startsFresh = false
    /// A place was kept: a mark of read or unread is forgotten.
    @ObservationIgnored var onKept: (() -> Void)?
    /// The device is in dark mode: system colours follow it.
    var systemDark = false {
        didSet {
            if oldValue != systemDark && preferences.theme == .system { navigator.submit(rendering) }
        }
    }

    @ObservationIgnored private let hub: HubClient
    @ObservationIgnored private let app: AppModel
    /// The narration read from the edition, until the book is on the page.
    @ObservationIgnored private var prepared: NarrationModel.Narration?
    @ObservationIgnored private let navigator = BookNavigator()
    @ObservationIgnored private let places: any BookPlaceKeeper
    @ObservationIgnored private let cache: EpubPackageCache
    @ObservationIgnored private let bookmarkStore: EpubBookmarks
    @ObservationIgnored private let defaults: UserDefaults
    @ObservationIgnored private var preferenceState: EpubPreferenceState
    @ObservationIgnored private let paceStore: ReadingPaceStore
    @ObservationIgnored private let paceKey: String
    @ObservationIgnored private var pace: ReadingPace
    @ObservationIgnored private var tracker = ReadingPace.Tracker()
    @ObservationIgnored private var scroll = BookScroll(edgePx: 400)
    @ObservationIgnored private var scrollHeight: CGFloat = 0
    @ObservationIgnored private var sections = BookSections(sections: [])
    @ObservationIgnored private var readingOrder: [String] = []
    @ObservationIgnored private var place: BookPlaceOnPage?
    /// The first place drawn: the reading has moved once the page is elsewhere.
    @ObservationIgnored private var firstPlace: BookPlaceOnPage?
    /// Where the book was opened, until the page says where it is: a jump
    /// made before then (the contents, at once) still leaves the way back.
    @ObservationIgnored private var openedAt: String?
    /// The place is kept only once the reading has moved, so opening a book sends nothing.
    @ObservationIgnored private var moved = false
    /// The book opened, waiting for a choice of place.
    @ObservationIgnored private var pending: BookNavigator.Loaded?
    @ObservationIgnored private var loadTask: Task<Void, Never>?
    @ObservationIgnored private var flushTask: Task<Void, Never>?
    @ObservationIgnored private var noticeTask: Task<Void, Never>?
    @ObservationIgnored private var linkOrigin: (place: String, at: Date)?
    @ObservationIgnored private var lastKey: (key: ReaderKey, at: Date)?
    @ObservationIgnored private var conflictSaid = false

    /// A followed link sets "Return to previous place" if the jump comes this soon (Android's LINK_MS).
    static let linkSeconds = 3.0
    /// The place is sent after the reading pauses this long, and when the reader leaves.
    static let flushSeconds = 2.0
    /// How far through a part the page may settle on opening without it counting as reading on.
    static let settles = 0.05

    init(app: AppModel, work: ReadingWork, sourceItemId: String, readAlong: Bool = false, defaults: UserDefaults = .standard) {
        hub = app.hub
        self.app = app
        self.readAlong = readAlong ? ReadAlongReader() : nil
        workId = work.id
        self.sourceItemId = sourceItemId
        title = work.title
        cover = work.artwork
        bookPages = PageInfo.bookPages(work, sourceItemId: sourceItemId)
        // The place goes through the reading outbox, as the listening place
        // does: the demo's in a folder of its own (`ListeningStore`).
        places = CheckpointBookPlaces(hub: app.hub, store: ListeningStore.shared,
                                      key: CheckpointBookPlaces.key(address: app.address, userId: app.userId,
                                                                    workId: work.id, sourceItemId: sourceItemId))
        cache = ReadingOffline.ebooks(app: app)
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("epub-bookmarks", isDirectory: true)
        bookmarkStore = EpubBookmarks(root: support, scope: EpubBookmarks.scope(address: app.address, userId: app.userId),
                                      workId: work.id, sourceItemId: sourceItemId)
        self.defaults = defaults
        let saved = EpubAppearanceStore.load(defaults, startingScale: BookNavigator.isTablet
                                             ? EpubReaderPreferences.tabletScale : EpubReaderPreferences.phoneScale)
        preferences = saved
        pageInfo = PageInfoStore.load(defaults)
        preferenceState = EpubPreferenceState(saved)
        paceStore = ReadingPaceStore(defaults: defaults)
        paceKey = ReadingPaceStore.key(workId: work.id, sourceItemId: sourceItemId)
        pace = paceStore.book(paceKey)
        navigator.onPlace = { [weak self] place in self?.placed(place) }
        navigator.onNote = { [weak self] text in
            self?.sheet = nil
            self?.footnote = text
        }
        navigator.onFollowLink = { [weak self] in self?.followingLink() }
        navigator.onJump = { [weak self] in self?.jumped() }
        navigator.onExternalLink = { [weak self] in self?.say("This link leads out of the book, so it is not opened here") }
        navigator.onTap = { [weak self] fraction in self?.tapped(fraction) }
        navigator.onKey = { [weak self] key in self?.key(key) }
        navigator.onFailure = { [weak self] message in self?.say(message) }
        if let reading = self.readAlong { connect(reading) }
    }

    /// Read along's hold on the page: the glow, the scripts, the page following the voice.
    private func connect(_ reading: ReadAlongReader) {
        let navigator = navigator
        reading.highlight = { [weak self] segment in
            navigator.highlight(segment)
            self?.updateLines()
        }
        reading.onScreen = { fragment in await navigator.evaluate(ReadAlongPageScript.visible(fragment)) as? Bool ?? false }
        reading.firstOnScreen = { ids in await navigator.evaluate(ReadAlongPageScript.firstVisible(ids)) as? String }
        reading.go = { json in await navigator.go(to: json) }
        reading.pageHref = { [weak self] in self?.place?.href }
        reading.keepPlace = { [weak self] in
            self?.moved = true
            self?.keepSoon()
        }
        reading.say = { [weak self] text in self?.say(text) }
    }

    var padState: ReaderPadState {
        ReaderPadState(.book, controlsVisible: controlsVisible, scrolling: preferences.scrolls,
                       narration: readAlong?.narration != nil, loading: phase != .reading)
    }

    /// The page as Appearance chose it. (Comfort dims and warms it over the top, #37.)
    var rendering: EpubRendering { EpubRendering(preferences, systemDark: systemDark) }

    /// The text's edge from the screen's, in points: Kindle's outer margin (#47).
    func outerMargin(width: Double) -> Double {
        EpubGeometry.outerMargin(pageMargins: preferences.pageMargins, tablet: BookNavigator.isTablet, width: width)
    }

    /// How far the page's view is set in from each side: the margin less the gutter Readium keeps.
    func pageInset(width: Double) -> Double {
        EpubGeometry.inset(pageMargins: preferences.pageMargins, tablet: BookNavigator.isTablet, width: width,
                           gutter: navigator.gutter)
    }

    /// A tap or a swipe in the margin, outside what Readium hears: it turns the page, or closes what is open.
    func insetTapped(forward: Bool) {
        if footnote != nil {
            footnote = nil
        } else if sheet != nil {
            sheet = nil
        } else if controlsVisible {
            setControls(false)
        } else {
            read(forward ? 1 : -1)
        }
    }

    // MARK: Opening

    func start(force: Bool = false) {
        loadTask?.cancel()
        pending = nil
        let kept = !force && editionCache.isComplete(workId: workId, sourceItemId: sourceItemId)
        phase = .opening(kept ? "Opening \(title)…" : "Downloading \(title)…")
        loadTask = Task { [weak self] in await self?.open(force: force) }
    }

    /// Try again: the book downloaded again when it was the book that failed.
    func retry() {
        if case .failed = phase { start(force: true) } else { start() }
    }

    /// The answer to which place: "local", "server" or "start".
    func choose(_ choice: String) {
        guard let loaded = pending else { return }
        pending = nil
        let places = places
        phase = .opening("Opening \(title)…")
        loadTask = Task { [weak self] in
            let opening = await places.answer(choice)
            self?.opened(loaded, opening)
        }
    }

    /// Where the keeper says to open, or what it asks first.
    private func opened(_ loaded: BookNavigator.Loaded, _ opening: BookOpening) {
        if startsFresh {
            // Marked unread: the beginning, and no question about which place.
            startsFresh = false
            show(loaded, at: nil)
            return
        }
        switch opening {
        case .at(let locator):
            show(loaded, at: locator)
        case .question(let prompt):
            pending = loaded
            phase = .choosing(prompt)
        case .unavailable(let message):
            phase = .failed(message)
        }
    }

    /// Where the book is kept on this device: the ebooks, or the read-along editions.
    private var editionCache: EpubPackageCache { readAlong == nil ? cache : ReadAlongEdition.cache(app: app) }

    private func open(force: Bool) async {
        let file: URL
        if let readAlong {
            // The edition without its audio, and its narration when the hub maps it.
            do {
                let opening = try await NarrationModel.prepare(app: app, workId: workId, sourceItemId: sourceItemId, force: force)
                file = opening.edition
                prepared = opening.narration
                if opening.narration == nil { readAlong.startWithout(opening.note) }
            } catch {
                if !Task.isCancelled { phase = .failed(error.message) }
                return
            }
        } else {
            do {
                file = try await bookFile(force: force)
            } catch let failure as HubFailure {
                if failure.kind != .cancelled { phase = .failed(failure.message) }
                return
            } catch {
                phase = .failed("The book could not be kept on this device")
                return
            }
        }
        guard !Task.isCancelled else { return }
        phase = .opening("Opening \(title)…")
        let loaded: BookNavigator.Loaded
        do {
            loaded = try await BookNavigator.load(file: file)
        } catch {
            guard !Task.isCancelled else { return }
            // A file Readium cannot open is not kept: the next try downloads it again.
            editionCache.remove(workId: workId, sourceItemId: sourceItemId)
            phase = .failed("This EPUB could not be opened")
            return
        }
        guard !Task.isCancelled else { return }
        // Listed with the books kept on this device (#43).
        ReadingOffline.kept(address: app.address, userId: app.userId, workId: workId, title: title, artwork: cover,
                            kind: readAlong == nil ? "ebook" : "readalong", sourceItemId: sourceItemId)
        let opening = await places.opening()
        guard !Task.isCancelled else { return }
        opened(loaded, opening)
    }

    /// The book on this device, checked with the hub as it opens (#41): the
    /// hub's reading copy replaces one it has since changed, and an outage
    /// opens what is kept.
    private func bookFile(force: Bool) async throws -> URL {
        try await cache.open(workId: workId, sourceItemId: sourceItemId,
                             request: HubEndpoints.readingEpubFile(workId: workId, sourceItemId: sourceItemId),
                             hub: hub, force: force)
    }

    private func show(_ loaded: BookNavigator.Loaded, at locator: String?) {
        sections = loaded.sections
        contents = loaded.contents
        readingOrder = loaded.readingOrder
        place = nil
        firstPlace = nil
        openedAt = locator ?? loaded.readingOrder.first.map { Self.locator($0, progression: 0) }
        moved = false
        readAlong?.beginOpen()
        defer { readAlong?.endOpen() }
        do {
            // Reading along, the sentence spoken glows in the Books accent.
            controller = try navigator.makeController(loaded, at: locator, rendering: rendering,
                                                      narration: readAlong == nil ? nil : AccentPreset.defaultFor(.books).color)
        } catch {
            phase = .failed("This EPUB could not be opened")
            return
        }
        refreshBookmarks()
        phase = .reading
        if let readAlong {
            if let prepared {
                readAlong.start(prepared, workId: workId, token: app.storedToken(), at: locator)
                self.prepared = nil
            } else if !readAlong.note.isEmpty {
                say(readAlong.note)
            }
        }
    }

    // MARK: Leaving

    /// The app went to the background: what is waiting goes now, and the
    /// narration stops, its place kept with it.
    func flushPlace() {
        readAlong?.pause()
        let places = places
        let last = moved && readAlong?.canKeepPage != false ? place.map { keptPlace($0.json) } : nil
        flushTask?.cancel()
        Task {
            if let last { await places.reached(last) }
            await places.flush()
        }
    }

    func stop() {
        stopSearching()
        loadTask?.cancel()
        noticeTask?.cancel()
        flushPlace()
        readAlong?.release()
        navigator.close()
        controller = nil
    }

    /// The place to keep for the page: reading along, the sentence being read.
    private func keptPlace(_ json: String) -> String {
        readAlong?.place(json) ?? json
    }

    // MARK: Where the page is

    private func placed(_ new: BookPlaceOnPage) {
        let previous = place
        place = new
        if let first = firstPlace {
            // Opening, the page settles on the screen that holds the place
            // (a part's page can be a twentieth of it): that is not reading on.
            // A swipe on to another part, or well on through this one, is.
            if !moved && (new.href != first.href || abs(new.progression - first.progression) > Self.settles) { moved = true }
        } else {
            firstPlace = new
        }
        if let position = sections.position(href: new.href, progression: new.progression),
           let reading = tracker.at(position, nowMs: Int64(Date().timeIntervalSince1970 * 1_000)) {
            pace = paceStore.record(paceKey, book: pace, reading: reading)
        }
        updateLines()
        bookmarked = BookLocator.anchor(new.json).map { anchor in bookmarks.contains { $0.anchor == anchor } } ?? false
        // A page moved by hand while reading along: the page stops following the voice, or reads on alone.
        if let readAlong, moved, let previous, previous.href != new.href || abs(previous.progression - new.progression) > 0.0005 {
            readAlong.pageMoved(to: new.href)
        }
        if moved { keepSoon() }
    }

    private func updateLines() {
        guard let place else { return }
        let progress = sections.progress(href: place.href, progression: place.progression,
                                         totalProgression: place.totalProgression)
        bookProgress = progress ?? 0
        currentContentsRow = contents.firstIndex { $0.href == place.href }
        // Contents opened before the page said where it is: the cursor goes to it once it does.
        if sheet == .contents, !cursorMoved, let row = currentContentsRow { sheetCursor = row }
        let title = place.title ?? currentContentsRow.map { contents[$0].title }
        let pageInPart = preferences.scrolls ? nil : navigator.pageInPart()
        positionLine = BookSections.line(title: title, page: pageInPart, progress: progress)
        // Following the voice, the narration's own time left; else the pace's.
        let left = readAlong?.timeLeft ?? sections.timeLeft(href: place.href, progression: place.progression,
                                                            minutesPerPosition: pace.minutesPerPosition(prior: paceStore.prior()))
        timeLeft = left?.label() ?? ""
        reading = PageInfo.Reading(bookPages: bookPages, progress: progress, chapter: sections.span(href: place.href),
                                   positionInBook: sections.positionInBook(href: place.href, progression: place.progression),
                                   positionInChapter: sections.positionInChapter(href: place.href, progression: place.progression),
                                   timeLeft: left)
        let next = PageInfo.corners(pageInfo, reading)
        if next != corners { corners = next }
    }

    // MARK: The corners (#42)

    /// The corners chosen in Appearance, kept on this device.
    func setPageInfo(_ next: PageInfoPreferences) {
        pageInfo = next
        PageInfoStore.save(next, to: defaults)
        corners = PageInfo.corners(next, reading)
    }

    /// A tap on the bottom corner: the next way of saying where you are, as on Kindle.
    func nextPlace() {
        setPageInfo(PageInfo.next(pageInfo, reading))
    }

    /// Once the reading pauses, the place it reached goes to the keeper, which sends it.
    private func keepSoon() {
        flushTask?.cancel()
        flushTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(Self.flushSeconds))
            guard !Task.isCancelled, let self, let page = self.place?.json, self.readAlong?.canKeepPage != false else { return }
            let json = self.keptPlace(page)
            let places = self.places
            self.onKept?()
            await places.reached(json)
            await places.flush()
            if await places.conflicted() { self.conflicted() }
        }
    }

    private func conflicted() {
        guard !conflictSaid else { return }
        conflictSaid = true
        say("Your place changed on another device, so this one stopped sending it")
    }

    // MARK: Keys, a controller and touch

    func pad(_ action: PadAction) {
        if footnote != nil {
            switch action {
            case .activate: followNote()
            case .back: footnote = nil
            default: break
            }
            return
        }
        if sheet != nil {
            sheetPad(action)
            return
        }
        switch phase {
        case .reading:
            perform(ReaderPadMap.command(padState, action))
        case .choosing(let prompt):
            switch action {
            case .activate: if let first = prompt.choices.first { choose(first.id) }
            case .secondary: if prompt.choices.count > 1 { choose(prompt.choices[1].id) }
            case .refresh: retry()
            case .back: leaving = true
            default: break
            }
        case .failed:
            switch action {
            case .activate, .refresh: retry()
            case .back: leaving = true
            default: break
            }
        case .opening:
            if action == .back { leaving = true }
        }
    }

    /// A hardware keyboard's key: Escape closes what is open over the page,
    /// else leaves; the rest are the controller's (`ReaderKeyboard`).
    func key(_ key: ReaderKey) {
        // Readium and the screen can both hear a key: once is enough.
        let now = Date()
        if let last = lastKey, last.key == key, now.timeIntervalSince(last.at) < 0.03 { return }
        lastKey = (key, now)
        if key == .escape {
            if footnote != nil {
                footnote = nil
            } else if sheet == .appearance && appearanceSpacing {
                // Spacing is a page of Font's: Escape goes back to Font first (#47).
                leaveSpacing()
            } else if sheet != nil {
                sheet = nil
            } else {
                leaving = true
            }
            return
        }
        guard let action = ReaderKeyboard.action(key) else { return }
        pad(action)
    }

    private func perform(_ command: ReaderCommand) {
        switch command {
        case .forward: read(1)
        case .backward: read(-1)
        case .page(let delta): read(delta)
        case .chapter(let delta): chapter(delta)
        case .controls(let visible): setControls(visible)
        case .leave: leaving = true
        case .choose: choose(focusedControl)
        case .focus(let direction): moveFocus(direction)
        case .bookmark: toggleBookmark()
        case .contents: openSheet(.contents)
        case .display: openSheet(.appearance)
        case .keys: openSheet(.keys)
        case .retry: retry()
        case .scroll(let direction): step(direction == .down ? 1 : -1, share: BookScroll.stepShare)
        case .glide(_, let dy): glide(dy)
        case .sentence(let delta): readAlong?.stepSentence(delta)
        case .followNarration: readAlong?.follow()
        case .nextPlace: nextPlace()
        default: break
        }
    }

    /// A tap on the page: the middle shows or hides the menu, and any tap closes it.
    private func tapped(_ fraction: Double) {
        if footnote != nil {
            footnote = nil
            return
        }
        if sheet != nil {
            sheet = nil
            return
        }
        guard EpubChromePolicy.handlesTap(fraction, controlsVisible: controlsVisible) else { return }
        setControls(!controlsVisible)
    }

    func setControls(_ visible: Bool) {
        controlsVisible = visible
        browsing = nil
        if visible { updateLines() }
    }

    // MARK: Reading on

    /// On or back a page; while scrolling, a screen, on into the next part at the end of one.
    func read(_ delta: Int) {
        if preferences.scrolls {
            step(delta, share: BookScroll.screenShare)
        } else {
            turn(delta)
        }
    }

    private func turn(_ delta: Int) {
        guard phase == .reading else { return }
        clearFound()
        scroll.reset()
        moved = true
        Task {
            let went = delta >= 0 ? await navigator.goForward() : await navigator.goBackward()
            if !went { say(delta >= 0 ? "End of book" : "Start of book") }
        }
    }

    private func chapter(_ delta: Int) {
        guard let place, let index = readingOrder.firstIndex(of: place.href) else { return }
        guard let next = BookSections.chapter(from: index, delta: delta, count: readingOrder.count) else {
            say(delta > 0 ? "This is the last part" : "This is the first part")
            return
        }
        jump(to: Self.locator(readingOrder[next], progression: 0))
    }

    /// To a place, leaving "Return to previous place" in the menu when `remember` says so.
    func jump(to json: String, remember: Bool = true, then done: (() -> Void)? = nil) {
        clearFound()
        let previous = place?.json ?? openedAt
        tracker.restart()
        scroll.reset()
        moved = true
        linkOrigin = nil
        Task {
            guard await navigator.go(to: json) else {
                say("This place in the book could not be opened")
                return
            }
            if remember, let previous, !BookLocator.same(previous, json) { returnPlace = previous }
            sheet = nil
            browsing = nil
            updateLines()
            done?()
        }
    }

    func returnToPrevious() {
        guard let target = returnPlace else { return }
        jump(to: target, remember: false) { [weak self] in self?.returnPlace = nil }
    }

    /// The slider let go: that far through the book.
    func seek(_ fraction: Double) {
        browsing = nil
        guard let target = sections.seek(fraction) else { return }
        jump(to: Self.locator(target.href, progression: target.progression))
    }

    func openContents(_ row: BookContentsRow) {
        jump(to: row.locator)
    }

    // MARK: Links and notes

    private func followingLink() {
        guard let origin = place?.json else { return }
        linkOrigin = (origin, Date())
        moved = true
        tracker.restart()
    }

    private func jumped() {
        guard let origin = linkOrigin else { return }
        linkOrigin = nil
        guard Date().timeIntervalSince(origin.at) <= Self.linkSeconds, !BookLocator.same(origin.place, place?.json) else { return }
        returnPlace = origin.place
        say("Return to previous place is in the menu")
    }

    func followNote() {
        footnote = nil
        Task {
            if !(await navigator.followNote()) { say("The note could not be opened") }
        }
    }

    func closeNote() {
        footnote = nil
    }

    // MARK: Scrolling

    private func step(_ sign: Int, share: Double) {
        guard let view = navigator.visibleScrollView() else { return }
        prepareScroll(view)
        moved = true
        let move = scroll.step(Int(Double(view.bounds.height) * share) * sign, canDown: canScroll(view, down: true),
                               canUp: canScroll(view, down: false))
        apply(move, to: view, animated: true)
    }

    /// The right stick, in stick-seconds: up to a screen and a half a second.
    private func glide(_ dy: Double) {
        guard let view = navigator.visibleScrollView() else { return }
        prepareScroll(view)
        moved = true
        let move = scroll.glide(dy * BookScroll.glideScreensPerSecond * Double(view.bounds.height),
                                canDown: canScroll(view, down: true), canUp: canScroll(view, down: false))
        apply(move, to: view, animated: false)
    }

    private func prepareScroll(_ view: UIScrollView) {
        if view.bounds.height != scrollHeight {
            scrollHeight = view.bounds.height
            scroll = BookScroll(edgePx: Double(scrollHeight) * BookScroll.edgeScreens)
        }
    }

    private func canScroll(_ view: UIScrollView, down: Bool) -> Bool {
        let top = -view.adjustedContentInset.top
        let bottom = view.contentSize.height - view.bounds.height + view.adjustedContentInset.bottom
        return down ? view.contentOffset.y < bottom - 1 : view.contentOffset.y > top + 1
    }

    private func apply(_ move: BookScroll.Move, to view: UIScrollView, animated: Bool) {
        switch move {
        case .by(let points):
            let top = -view.adjustedContentInset.top
            let bottom = max(top, view.contentSize.height - view.bounds.height + view.adjustedContentInset.bottom)
            let target = min(max(view.contentOffset.y + CGFloat(points), top), bottom)
            if animated {
                UIView.animate(withDuration: Double(BookScroll.stepMs) / 1_000, delay: 0,
                               options: [.curveEaseOut, .beginFromCurrentState, .allowUserInteraction]) {
                    view.contentOffset.y = target
                }
            } else {
                view.contentOffset.y = target
            }
        case .nextPart: turn(1)
        case .previousPart: turn(-1)
        case .stay: break
        }
    }

    // MARK: The menu's controls

    /// The menu's controls by row: the top bar, the page buttons, the slider.
    var controlRows: [[BookControl]] {
        let top: [BookControl] = [.close, .contents, .search, .bookmark, .appearance, .keys]
        if readAlong?.narration != nil {
            return [top, [.narrationBack, .narrationPlay, .narrationForward, .narrationSpeed, .narrationFollow]]
        }
        return [top, returnPlace == nil ? [.previous, .next] : [.previous, .returnPlace, .next], [.slider]]
    }

    private func moveFocus(_ direction: PadDirection) {
        let rows = controlRows
        guard let row = rows.firstIndex(where: { $0.contains(focusedControl) }),
              let column = rows[row].firstIndex(of: focusedControl) else {
            focusedControl = .contents
            return
        }
        switch direction {
        case .left, .right:
            if focusedControl == .slider {
                browse(direction == .right ? 0.01 : -0.01)
                return
            }
            let next = column + (direction == .right ? 1 : -1)
            if rows[row].indices.contains(next) { focusedControl = rows[row][next] }
        case .up, .down:
            let nextRow = row + (direction == .down ? 1 : -1)
            guard rows.indices.contains(nextRow) else { return }
            let share = Double(column) / Double(max(rows[row].count - 1, 1))
            focusedControl = rows[nextRow][Int((share * Double(rows[nextRow].count - 1)).rounded())]
            if focusedControl != .slider { browsing = nil }
        }
    }

    /// The slider moved with the pad: a percent at a time; Ⓐ goes there.
    func browse(_ delta: Double) {
        browsing = min(max((browsing ?? bookProgress) + delta, 0), 1)
    }

    func choose(_ control: BookControl) {
        switch control {
        case .close: leaving = true
        case .contents: openSheet(.contents)
        case .search: openSheet(.search)
        case .bookmark: toggleBookmark()
        case .appearance: openSheet(.appearance)
        case .keys: openSheet(.keys)
        case .previous: read(-1)
        case .returnPlace: returnToPrevious()
        case .next: read(1)
        case .slider: if let browsing { seek(browsing) }
        case .narrationBack: readAlong?.narration?.jump(by: -Int64(ListeningSettings.seekSeconds) * 1_000)
        case .narrationPlay: readAlong?.togglePlay()
        case .narrationForward: readAlong?.narration?.jump(by: Int64(ListeningSettings.seekSeconds) * 1_000)
        case .narrationSpeed: if let narration = readAlong?.narration { narration.setSpeed(Listening.nextSpeed(narration.speed)) }
        case .narrationFollow: readAlong?.follow()
        }
    }

    // MARK: Sheets

    func openSheet(_ next: Sheet) {
        controlsVisible = true
        footnote = nil
        cursorMoved = false
        switch next {
        case .contents: sheetCursor = currentContentsRow ?? 0
        case .bookmarks:
            refreshBookmarks()
            sheetCursor = 0
        case .search: sheetCursor = 0
        case .appearance: appearanceSpacing = false
        default: break
        }
        sheet = next
    }

    /// Contents and Bookmarks: up and down choose a line, Ⓐ opens it, left
    /// and right switch between the two. Every sheet closes with Ⓑ.
    private func sheetPad(_ action: PadAction) {
        guard let open = sheet else { return }
        switch action {
        case .back:
            // Spacing is a page of Font's: Ⓑ goes back to Font before it leaves Appearance (#47).
            if open == .appearance && appearanceSpacing { leaveSpacing() } else { sheet = nil }
        case .step(let direction) where open == .contents || open == .bookmarks:
            let count = open == .contents ? contents.count : bookmarks.count
            switch direction {
            case .up:
                sheetCursor = max(0, sheetCursor - 1)
                cursorMoved = true
            case .down:
                sheetCursor = max(0, min(count - 1, sheetCursor + 1))
                cursorMoved = true
            case .left, .right: openSheet(open == .contents ? .bookmarks : .contents)
            }
        case .activate where open == .contents:
            if contents.indices.contains(sheetCursor) { openContents(contents[sheetCursor]) }
        case .activate where open == .bookmarks:
            if bookmarks.indices.contains(sheetCursor) { openBookmark(bookmarks[sheetCursor]) }
        case .step(let direction) where open == .search:
            // Up and down go through what was found.
            guard case .found(let hits) = searchState, !hits.isEmpty else { return }
            if direction == .up { sheetCursor = max(0, sheetCursor - 1) }
            if direction == .down { sheetCursor = min(hits.count - 1, sheetCursor + 1) }
        case .activate where open == .search:
            if case .found(let hits) = searchState, hits.indices.contains(sheetCursor) {
                openFound(hits[sheetCursor])
            } else if searchState != .searching {
                runSearch()
            }
        case .secondary where open == .contents:
            sheet = nil
        case .click(.right, true):
            openSheet(.keys)
        case .step(let direction) where open == .keys:
            keysPart = ReaderKeysPart.step(keysPart, direction)
        case .step(let direction) where open == .appearance:
            appearanceStep(direction)
        case .section(let delta) where open == .appearance:
            // L1 and R1: the tab before or after.
            let tabs = AppearanceTab.allCases
            let index = (tabs.firstIndex(of: appearanceTab) ?? 0) + delta
            if tabs.indices.contains(index) {
                appearanceSpacing = false
                appearanceTab = tabs[index]
            }
        case .activate where open == .appearance:
            let lines = BookAppearanceLine.lines(appearancePage)
            let walk = appearanceWalk.clamped(to: lines.map(\.shape))
            pressAppearance(lines[walk.line], column: walk.column)
        default:
            break
        }
    }

    /// Appearance (#25): up and down from line to line, the tabs first; left
    /// and right across a line's choices (on the tabs, to the next tab), or
    /// the size and Comfort's values a step.
    private func appearanceStep(_ direction: PadDirection) {
        let lines = BookAppearanceLine.lines(appearancePage)
        switch SheetWalk.step(appearanceWalk, direction, lines: lines.map(\.shape)) {
        case .moved(let walk):
            appearanceWalk = walk
            if lines[walk.line] == .tabs { appearanceTab = AppearanceTab.allCases[walk.column] }
        case .adjust(let delta):
            adjustAppearance(lines[appearanceWalk.clamped(to: lines.map(\.shape)).line], by: delta)
        case .stay:
            break
        }
    }

    /// A line of Appearance pressed, by a finger or by Ⓐ: `column` of its choices.
    func pressAppearance(_ line: BookAppearanceLine, column: Int = 0) {
        switch line {
        case .tabs:
            if AppearanceTab.allCases.indices.contains(column) {
                appearanceSpacing = false
                appearanceTab = AppearanceTab.allCases[column]
            }
        case .spacingPage:
            appearanceSpacing = true
            appearanceWalk = SheetWalk()
        case .back:
            leaveSpacing()
        case .comfort(let comfort):
            ReaderComfort.shared.set(comfort.press(ReaderComfort.shared.value))
        default:
            if let next = line.press(preferences, column: column) {
                setPreferences(next)
            } else if let next = line.press(pageInfo) {
                setPageInfo(next)
            }
        }
    }

    /// Back from Spacing to Font, the ring on the row that opened it.
    func leaveSpacing() {
        appearanceSpacing = false
        let font = BookAppearanceLine.lines(.font)
        appearanceWalk = SheetWalk(line: font.firstIndex(of: .spacingPage) ?? 0)
    }

    /// The line and choice a controller's ring is on in Appearance, when a controller is in use (#25).
    var appearanceRing: (line: BookAppearanceLine, column: Int)? {
        guard controllerActive else { return nil }
        let lines = BookAppearanceLine.lines(appearancePage)
        let walk = appearanceWalk.clamped(to: lines.map(\.shape))
        return (lines[walk.line], walk.column)
    }

    /// A value of Appearance a step down or up: the size, or Comfort's brightness and warmth.
    func adjustAppearance(_ line: BookAppearanceLine, by delta: Int) {
        if case .comfort(let comfort) = line {
            ReaderComfort.shared.set(comfort.adjust(ReaderComfort.shared.value, by: delta))
        } else if let next = line.adjust(preferences, by: delta) {
            setPreferences(next)
        }
    }

    // MARK: Search (#37)

    /// The words typed, searched for in the book: the first 100 passages,
    /// for 30 seconds at most (Android's search sheet).
    func runSearch() {
        guard phase == .reading else { return say("The book is still opening") }
        guard let phrase = BookSearch.query(searchText) else { return say(BookSearch.enterPhrase) }
        stopSearching()
        searchState = .searching
        sheetCursor = 0
        // Each part's title in the contents, the first line for a part that has several.
        let chapters = Dictionary(contents.map { ($0.href, $0.title) }, uniquingKeysWith: { first, _ in first })
        let search = Task { [weak self, navigator] in
            let result = await navigator.search(phrase, chapters: chapters)
            guard !Task.isCancelled, let self else { return }
            self.searchTimer?.cancel()
            switch result {
            case .success(let hits): self.searchState = .found(hits)
            case .failure(let problem): self.searchState = .problem(problem)
            }
        }
        searching = search
        searchTimer = Task { [weak self] in
            try? await Task.sleep(for: .seconds(BookSearch.seconds))
            guard !Task.isCancelled, let self, self.searchState == .searching else { return }
            search.cancel()
            self.searchState = .problem(.tooLong)
        }
    }

    /// A passage found: its page, the passage marked, with "Return to previous place" in the menu.
    func openFound(_ hit: BookSearchHit) {
        jump(to: hit.locator) { [weak self] in
            self?.navigator.markFound(hit.locator)
            self?.markedFound = true
        }
    }

    private func stopSearching() {
        searching?.cancel()
        searchTimer?.cancel()
        searching = nil
        searchTimer = nil
        if searchState == .searching { searchState = .idle }
    }

    private func clearFound() {
        guard markedFound else { return }
        markedFound = false
        navigator.markFound(nil)
    }

    // MARK: Bookmarks

    func toggleBookmark() {
        guard let place else {
            say("The book is still opening")
            return
        }
        do {
            let added = try bookmarkStore.toggle(place.json)
            refreshBookmarks()
            say(added ? "Bookmark added" : "Bookmark removed")
        } catch {
            say("The bookmark could not be kept on this device")
        }
    }

    func openBookmark(_ bookmark: EpubBookmark) {
        jump(to: bookmark.locator)
    }

    func deleteBookmark(_ bookmark: EpubBookmark) {
        do {
            try bookmarkStore.remove(anchor: bookmark.anchor)
        } catch {
            say("The bookmark could not be deleted")
        }
        refreshBookmarks()
        sheetCursor = min(sheetCursor, max(0, bookmarks.count - 1))
    }

    private func refreshBookmarks() {
        do {
            bookmarks = try bookmarkStore.list()
        } catch {
            bookmarks = []
            say("This book's bookmarks could not be read on this device")
        }
        bookmarked = place.flatMap { BookLocator.anchor($0.json) }.map { anchor in bookmarks.contains { $0.anchor == anchor } }
            ?? false
    }

    // MARK: Appearance

    /// Applied at once and kept for every book.
    func setPreferences(_ next: EpubReaderPreferences) {
        guard next != preferences else { return }
        preferences = next
        preferenceState.preview(next)
        if preferenceState.commit() {
            EpubAppearanceStore.save(preferenceState.saved, to: defaults)
            preferenceState.markPersisted()
        }
        scroll.reset()
        tracker.restart()
        navigator.submit(rendering)
    }

    // MARK: Words over the page

    func say(_ text: String) {
        notice = text
        noticeTask?.cancel()
        noticeTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(2.6))
            guard !Task.isCancelled else { return }
            self?.notice = nil
        }
    }

    /// A Readium locator for the start of `href`, or a way through it.
    static func locator(_ href: String, progression: Double) -> String {
        BookLocator.canonical(["href": href, "type": "application/xhtml+xml",
                               "locations": ["progression": progression]]) ?? "{}"
    }
}
#endif
