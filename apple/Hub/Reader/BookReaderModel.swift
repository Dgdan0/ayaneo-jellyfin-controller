#if os(iOS)
import Foundation
import HubKit
import Observation
import UIKit

/// A control in the book's menu, in the order the pad moves through them.
enum BookControl: Hashable {
    case close, contents, bookmark, appearance, keys, previous, returnPlace, next, slider

    var label: String {
        switch self {
        case .close: "Close the book"
        case .contents: "Contents"
        case .bookmark: "Bookmark"
        case .appearance: "Appearance"
        case .keys: "Keys"
        case .previous: "Previous page"
        case .returnPlace: "Return to previous place"
        case .next: "Next page"
        case .slider: "Where in the book"
        }
    }

    var systemImage: String {
        switch self {
        case .close: "xmark"
        case .contents: "list.bullet"
        case .bookmark: "bookmark"
        case .appearance: "textformat.size"
        case .keys: "gamecontroller"
        case .previous: "chevron.left"
        case .returnPlace: "arrow.uturn.backward"
        case .next: "chevron.right"
        case .slider: "slider.horizontal.below.rectangle"
        }
    }
}

/// The ebook reader (#25, phase 4): Android's `EpubReaderScreen` without its
/// read along, which comes after phase 2. It downloads the EPUB once
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
        case contents, bookmarks, appearance, keys
    }

    enum AppearanceTab: Hashable {
        case font, layout, themes
    }

    let workId: String
    let sourceItemId: String
    let title: String
    let cover: String

    private(set) var phase: Phase = .opening("Opening the book…")
    /// Readium's navigator, shown by the screen once the book is open.
    private(set) var controller: UIViewController?
    private(set) var controlsVisible = false
    var sheet: Sheet?
    var appearanceTab = AppearanceTab.font
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
    /// The slider dragged, or moved with the pad: where it would go.
    var browsing: Double?
    /// Where a link, the contents or the slider was followed from.
    private(set) var returnPlace: String?
    private(set) var preferences: EpubReaderPreferences
    /// The control the pad is on while the menu is open.
    var focusedControl = BookControl.contents
    /// The contents or bookmark line the pad is on while that sheet is open.
    var sheetCursor = 0
    var controllerActive = false
    /// Set to leave: the screen closes the reader.
    var leaving = false
    /// The device is in dark mode: system colours follow it.
    var systemDark = false {
        didSet { if oldValue != systemDark && preferences.theme == .system { navigator.submit(rendering) } }
    }

    @ObservationIgnored private let hub: HubClient
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

    init(app: AppModel, work: ReadingWork, sourceItemId: String, defaults: UserDefaults = .standard) {
        hub = app.hub
        workId = work.id
        self.sourceItemId = sourceItemId
        title = work.title
        cover = work.artwork
        // The place goes through the reading outbox, as the listening place
        // does: the demo's in a folder of its own (`ListeningStore`).
        places = CheckpointBookPlaces(hub: app.hub, store: ListeningStore.shared,
                                      key: CheckpointBookPlaces.key(address: app.address, userId: app.userId,
                                                                    workId: work.id, sourceItemId: sourceItemId))
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("reading-epub", isDirectory: true)
        cache = EpubPackageCache(root: EpubPackageCache.folder(base: caches, address: app.address, userId: app.userId))
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("epub-bookmarks", isDirectory: true)
        bookmarkStore = EpubBookmarks(root: support, scope: EpubBookmarks.scope(address: app.address, userId: app.userId),
                                      workId: work.id, sourceItemId: sourceItemId)
        self.defaults = defaults
        let saved = EpubAppearanceStore.load(defaults)
        preferences = saved
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
    }

    var padState: ReaderPadState {
        ReaderPadState(.book, controlsVisible: controlsVisible, scrolling: preferences.scrolls, loading: phase != .reading)
    }

    var rendering: EpubRendering { EpubRendering(preferences, systemDark: systemDark) }

    // MARK: Opening

    func start(force: Bool = false) {
        loadTask?.cancel()
        pending = nil
        let kept = !force && cache.isComplete(workId: workId, sourceItemId: sourceItemId)
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

    private func open(force: Bool) async {
        let file: URL
        do {
            file = try await bookFile(force: force)
        } catch let failure as HubFailure {
            if failure.kind != .cancelled { phase = .failed(failure.message) }
            return
        } catch {
            phase = .failed("The book could not be kept on this device")
            return
        }
        guard !Task.isCancelled else { return }
        phase = .opening("Opening \(title)…")
        let loaded: BookNavigator.Loaded
        do {
            loaded = try await BookNavigator.load(file: file)
        } catch {
            guard !Task.isCancelled else { return }
            // A file Readium cannot open is not kept: the next try downloads it again.
            cache.remove(workId: workId, sourceItemId: sourceItemId)
            phase = .failed("This EPUB could not be opened")
            return
        }
        guard !Task.isCancelled else { return }
        let opening = await places.opening()
        guard !Task.isCancelled else { return }
        opened(loaded, opening)
    }

    private func bookFile(force: Bool) async throws -> URL {
        if force { cache.remove(workId: workId, sourceItemId: sourceItemId) }
        if cache.isComplete(workId: workId, sourceItemId: sourceItemId) {
            cache.touch(workId: workId, sourceItemId: sourceItemId)
            return cache.completeFile(workId: workId, sourceItemId: sourceItemId)
        }
        let data = try await hub.data(HubEndpoints.readingEpubFile(workId: workId, sourceItemId: sourceItemId))
        let file = try cache.install(workId: workId, sourceItemId: sourceItemId) { try data.write(to: $0) }
        cache.prune(keeping: file)
        return file
    }

    private func show(_ loaded: BookNavigator.Loaded, at locator: String?) {
        sections = loaded.sections
        contents = loaded.contents
        readingOrder = loaded.readingOrder
        place = nil
        firstPlace = nil
        moved = false
        do {
            controller = try navigator.makeController(loaded, at: locator, rendering: rendering)
        } catch {
            phase = .failed("This EPUB could not be opened")
            return
        }
        refreshBookmarks()
        phase = .reading
    }

    // MARK: Leaving

    /// The app went to the background: what is waiting goes now.
    func flushPlace() {
        let places = places
        let last = moved ? place?.json : nil
        flushTask?.cancel()
        Task {
            if let last { await places.reached(last) }
            await places.flush()
        }
    }

    func stop() {
        loadTask?.cancel()
        noticeTask?.cancel()
        flushPlace()
        navigator.close()
        controller = nil
    }

    // MARK: Where the page is

    private func placed(_ new: BookPlaceOnPage) {
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
        if moved { keepSoon() }
    }

    private func updateLines() {
        guard let place else { return }
        let progress = sections.progress(href: place.href, progression: place.progression,
                                         totalProgression: place.totalProgression)
        bookProgress = progress ?? 0
        currentContentsRow = contents.firstIndex { $0.href == place.href }
        let title = place.title ?? currentContentsRow.map { contents[$0].title }
        positionLine = BookSections.line(title: title, page: preferences.scrolls ? nil : navigator.pageInPart(),
                                         progress: progress)
        timeLeft = sections.timeLeft(href: place.href, progression: place.progression,
                                     minutesPerPosition: pace.minutesPerPosition(prior: paceStore.prior()))?.label() ?? ""
    }

    /// Once the reading pauses, the place it reached goes to the keeper, which sends it.
    private func keepSoon() {
        flushTask?.cancel()
        flushTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(Self.flushSeconds))
            guard !Task.isCancelled, let self, let json = self.place?.json else { return }
            let places = self.places
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
        let previous = place?.json
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
        [[.close, .contents, .bookmark, .appearance, .keys],
         returnPlace == nil ? [.previous, .next] : [.previous, .returnPlace, .next],
         [.slider]]
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
        case .bookmark: toggleBookmark()
        case .appearance: openSheet(.appearance)
        case .keys: openSheet(.keys)
        case .previous: read(-1)
        case .returnPlace: returnToPrevious()
        case .next: read(1)
        case .slider: if let browsing { seek(browsing) }
        }
    }

    // MARK: Sheets

    func openSheet(_ next: Sheet) {
        controlsVisible = true
        footnote = nil
        switch next {
        case .contents: sheetCursor = currentContentsRow ?? 0
        case .bookmarks:
            refreshBookmarks()
            sheetCursor = 0
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
            sheet = nil
        case .step(let direction) where open == .contents || open == .bookmarks:
            let count = open == .contents ? contents.count : bookmarks.count
            switch direction {
            case .up: sheetCursor = max(0, sheetCursor - 1)
            case .down: sheetCursor = max(0, min(count - 1, sheetCursor + 1))
            case .left, .right: openSheet(open == .contents ? .bookmarks : .contents)
            }
        case .activate where open == .contents:
            if contents.indices.contains(sheetCursor) { openContents(contents[sheetCursor]) }
        case .activate where open == .bookmarks:
            if bookmarks.indices.contains(sheetCursor) { openBookmark(bookmarks[sheetCursor]) }
        case .secondary where open == .contents:
            sheet = nil
        case .click(.right, true):
            openSheet(.keys)
        default:
            break
        }
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
