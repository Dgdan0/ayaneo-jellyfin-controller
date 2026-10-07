import HubKit
import Observation
import SwiftUI

/// A reader's way out (#25). The host that shows a reader sets it; the Books
/// side's `ReaderHost` hands it `read.close()`.
struct CloseReaderAction {
    var close: @MainActor () -> Void = {}

    @MainActor func callAsFunction() { close() }
}

extension EnvironmentValues {
    @Entry var closeReader = CloseReaderAction()
}

/// How each series is read and where its issues were left, on this device
/// (Android's `DomainPreferences.comicView`, `comicPlace` and
/// `comicDefaultFit`), stored as Android encodes them.
@MainActor
enum ComicReaderSettings {
    private static let defaults = UserDefaults.standard

    static var defaultFit: ComicFit {
        get { ComicFit.fromStored(defaults.string(forKey: "comic.defaultFit"), fallback: ComicView.defaultFit) }
        set { defaults.set(newValue.stored, forKey: "comic.defaultFit") }
    }

    static func view(workId: String) -> ComicView {
        ComicView.decode(defaults.string(forKey: "comic.view." + workId), fallback: defaultFit)
    }

    static func setView(_ view: ComicView, workId: String) {
        defaults.set(view.encode(), forKey: "comic.view." + workId)
    }

    static func place(workId: String) -> ComicPlace? {
        ComicPlace.decode(defaults.string(forKey: "comic.place." + workId))
    }

    static func setPlace(_ place: ComicPlace, workId: String) {
        defaults.set(place.encode(), forKey: "comic.place." + workId)
    }
}

/// The comic and manga reader's state (#25, phase 3; Android's
/// `PagedImageReaderScreen`): the issue and its pages, where it is in them,
/// how the series reads, the page on screen and where the view looks at it,
/// the pages decoded either side, and the place sent to Kavita.
///
/// What a unit is, how it is fitted, stepped, zoomed and panned is HubKit's
/// (`ComicUnits`, `ComicFrame`, `PagedImageState`, `ComicZoom`); this carries
/// those out and keeps the pictures.
@MainActor
@Observable
final class ComicReaderModel {
    enum Phase: Equatable {
        case opening
        case failed(String)
        case reading
    }

    enum Sheet: Hashable { case display, keys }

    struct EndCard: Equatable {
        var heading: String
        var next: String
        var canContinue: Bool
    }

    struct StepShown: Equatable {
        var index: Int
        var count: Int
    }

    /// The unit on screen, with the issue it belongs to and its pages laid side by side.
    struct Shown: Equatable {
        var publication: String
        var unit: ComicUnits.Unit
        var layout: ComicUnitLayout
    }

    let hub: HubClient
    let workId: String
    /// The run the issue belongs to, when the page that opened it had it:
    /// the names and covers of the issues either side, without asking again.
    let work: ReadingWork?

    private(set) var issue: ReadingSectionItem
    var phase: Phase = .opening
    private(set) var manifest: ReadingPublicationManifest?
    private(set) var view: ComicView
    /// A zoom of your own, kept from page to page and issue to issue.
    private(set) var zoom = ComicZoom()
    var units = ComicUnits.single(pageCount: 1)
    var state = PagedImageState(pageCount: 1)
    var shown: Shown?
    /// Where the view looks at the unit shown.
    var camera = ComicCamera(scale: 1, x: 0, y: 0)

    var controlsVisible = true
    var gridOpen = false
    /// The grid's cursor for a controller or a keyboard, and its shape as laid out.
    var gridCursor = 0
    var gridColumns = 4
    var gridRows = 3
    var sheet: Sheet?
    /// Select, Escape or Close asked to leave: the view tells its host.
    var leaving = false
    private(set) var endCard: EndCard?
    /// A word for a moment: "This is the last issue".
    private(set) var notice: String?
    /// "Loading page 5…", over the page still shown, when the next takes a while.
    private(set) var waitingFor: Int?
    /// "Part 2 of 3", and the small map of the page, for a moment after a step or a pan.
    private(set) var stepShown: StepShown?
    private(set) var mapShown = false

    /// The view's size and the screen's pixels in a point, from the page.
    private(set) var viewSize = CGSize.zero
    var screenScale: CGFloat = 2

    /// A game controller is connected: the controls show a cursor and the keys' hints.
    var controllerActive = false
    /// The controls in the order the pad moves through them, as the bars lay them out.
    var controls: [ComicControl] = []
    var focusedControl: ComicControl?

    // The pictures: decoded pages by page, which slot holds which, and what is on its way.
    var images: [PageKey: DecodedArtwork] = [:]
    var slots: [PageKey?] = []
    @ObservationIgnored var loads: [PageKey: Task<Void, Never>] = [:]
    @ObservationIgnored var measured: [PageKey: CGSize] = [:]
    @ObservationIgnored var contents: [PageKey: PageContent] = [:]
    @ObservationIgnored var measuring: [PageKey: Task<Void, Never>] = [:]

    /// The unit asked for and not yet shown.
    @ObservationIgnored var pendingUnit: Int?
    /// A unit reached going back opens at its end.
    @ObservationIgnored var arriveAtEnd = false
    /// Which way the reading goes: the neighbour decoded first.
    @ObservationIgnored var readingForward = true
    @ObservationIgnored private var outbox = ComicProgressOutbox(saved: 0)
    /// An issue that would not open, for Try again.
    @ObservationIgnored private var failedOpen: FailedOpen?
    private struct FailedOpen {
        let sourceItemId: String
        let atEnd: Bool
        let moving: Bool
    }
    /// The reading has moved since the issue opened: only then is a place
    /// sent, so opening an issue, even on a spread, writes nothing.
    @ObservationIgnored var moved = false
    @ObservationIgnored private var saving: Task<Void, Never>?
    @ObservationIgnored private var opening: Task<Void, Never>?
    @ObservationIgnored private var hints: Task<Void, Never>?
    @ObservationIgnored private var waiting: Task<Void, Never>?
    @ObservationIgnored private var noticeHiding: Task<Void, Never>?
    @ObservationIgnored private var controlsHiding: Task<Void, Never>?
    @ObservationIgnored private var endLookup: Task<Void, Never>?
    /// The view when a drag or a pinch began; a drag a pinch joined turns and glides nothing.
    @ObservationIgnored private var dragStart: ComicCamera?
    @ObservationIgnored private var pinchStart: ComicCamera?
    @ObservationIgnored private var dragSpoiled = false
    /// The view before L3 looked closer.
    @ObservationIgnored var magnified: ComicCamera?
    /// Debug builds: HUB_READ_CHROME=pinned keeps the controls up.
    @ObservationIgnored var controlsPinned = false

    // The page curl (#32, `ComicReaderCurl`): iOS's, while its view is there.
    /// The curl's view is on screen and may turn the page.
    var curlAvailable = false
    /// A curl is under way: the page under it is the curl's, not the canvas's.
    var curling = false
    /// The turn the keys or a controller asked the curl to play.
    var curlTurn: CurlTurn?
    @ObservationIgnored var curlTurns = 0

    /// What VoiceOver, the UI tests and the hint row read off the reader.
    var padState: ReaderPadState {
        ReaderPadState(.comic, controlsVisible: controlsVisible, loading: phase != .reading)
    }

    init(hub: HubClient, work: ReadingWork?, workId: String, publication: ReadingSectionItem) {
        self.hub = hub
        self.work = work
        self.workId = workId
        issue = publication
        view = ComicReaderSettings.view(workId: workId)
    }

    // MARK: Words

    /// The run's name: "Fantastic Four".
    var heading: String {
        ReaderTitleFormatter.heading(manifest?.seriesTitle ?? work?.title ?? "", manifest?.title ?? issue.title,
                                     fallback: issue.title)
    }

    /// "Issue 51", "Chapter 3".
    var issueName: String {
        ReaderTitleFormatter.issue(kind: manifest?.kind ?? issue.kind, seriesTitle: manifest?.seriesTitle ?? work?.title ?? "",
                                   publicationTitle: manifest?.title ?? issue.title, number: manifest?.number ?? issue.number)
    }

    /// Under the heading: "Issue 51 · Page 2 of 24".
    var subtitle: String {
        guard let manifest else { return issueName }
        return ReaderTitleFormatter.subtitle(issueName, page: currentPage + 1, pageCount: manifest.pageCount)
    }

    /// Beside the scrubber: "Page 2 of 24 · Part 1 of 3".
    var position: String {
        guard let manifest else { return "" }
        return ReaderTitleFormatter.subtitle("", page: currentPage + 1, pageCount: manifest.pageCount,
                                             part: state.viewportIndex + 1, parts: state.viewportSteps)
    }

    /// The page the reading is on: the last shown of the unit, as the place is.
    var currentPage: Int { units.units[min(state.pageIndex, units.units.count - 1)].place }

    var pageCount: Int { manifest?.pageCount ?? 0 }

    var rtl: Bool { manifest?.pageDirection(chosen: view.direction) == .rtl }

    /// The issue's cover, for the glass's colours: the run's own picture of it, else Kavita's by its id.
    var cover: String {
        issue.artwork.isEmpty ? IssueCover.path(issue.sourceItemId) ?? "" : issue.artwork
    }

    // MARK: Opening

    func start() {
        guard manifest == nil, opening == nil else { return }
        open(issue.sourceItemId, atEnd: false)
    }

    /// Try again: the issue that would not open, else the page that would not load.
    func retry() {
        if let failed = failedOpen {
            open(failed.sourceItemId, atEnd: failed.atEnd, moving: failed.moving)
        } else {
            phase = .reading
            loadUnit()
        }
    }

    /// The issue `sourceItemId`, at its place or, reached going back, at its
    /// end; `moving` when the reading went on into it, whose place is then sent.
    func open(_ sourceItemId: String, atEnd: Bool, moving: Bool = false) {
        opening?.cancel()
        failedOpen = nil
        endLookup?.cancel()
        endCard = nil
        if sourceItemId != issue.sourceItemId {
            issue = work.map { ReadingWorkPresentation.publication($0, sourceItemId: sourceItemId) }
                ?? ReadingSectionItem(sourceItemId: sourceItemId, workId: workId, kind: issue.kind)
        }
        phase = .opening
        let request = HubEndpoints.readingPublication(workId: workId, sourceItemId: sourceItemId)
        opening = Task { [weak self, hub] in
            do throws(HubFailure) {
                let manifest = try await hub.fetch(request, as: ReadingPublicationManifest.self)
                guard !Task.isCancelled else { return }
                self?.apply(manifest, atEnd: atEnd, moving: moving)
            } catch {
                guard !Task.isCancelled, error.kind != .cancelled else { return }
                self?.failedOpen = FailedOpen(sourceItemId: sourceItemId, atEnd: atEnd, moving: moving)
                self?.phase = .failed(error.message)
            }
            self?.opening = nil
        }
    }

    private func apply(_ manifest: ReadingPublicationManifest, atEnd: Bool, moving: Bool) {
        guard manifest.pageCount > 0 else {
            failedOpen = FailedOpen(sourceItemId: manifest.sourceItemId, atEnd: atEnd, moving: moving)
            phase = .failed("This issue has no pages to read")
            return
        }
        self.manifest = manifest
        outbox = ComicProgressOutbox(saved: manifest.currentPage)
        units = makeUnits(for: manifest)
        slots = Array(repeating: nil, count: units.slotCount)
        let page = atEnd ? manifest.pageCount - 1 : manifest.startPage
        let unit = units.unit(containing: page)
        let step = atEnd ? Int.max
            : ComicReaderSettings.place(workId: workId)?.stepFor(manifest.sourceItemId, page: units.units[unit].place,
                                                                   steps: steps(unit: unit)) ?? 0
        state = PagedImageState(pageCount: units.units.count, startPage: unit, stepsFor: stepsFor, startStep: step)
        arriveAtEnd = atEnd
        readingForward = !atEnd
        moved = moving
        phase = .reading
        loadUnit()
        hideControlsSoon()
    }

    /// The steps the state asks for, read on the main actor where it is used.
    var stepsFor: (Int) -> Int {
        { [weak self] unit in MainActor.assumeIsolated { self?.steps(unit: unit) ?? 1 } }
    }

    /// How many steps a unit takes: thirds from its content's shape (C2, C5),
    /// one while zoomed or at another fit; a comic page's three before its size is known.
    func steps(unit: Int) -> Int {
        guard view.fit == .thirds, !zoom.active else { return 1 }
        guard let frame = frame(unit: unit) else { return ComicFrame.defaultSteps }
        return frame.steps.count
    }

    // MARK: Moving through the reading

    /// Ⓐ, a tap on the outer third the reading goes to, Space.
    func forward() {
        guard phase == .reading else { return }
        if view.fit != .thirds || zoom.active, let frame = shownFrame,
           let next = frame.readingFlow(forward: true, rtl: rtl, from: camera, zoomed: zoom.active) {
            look(at: next, animated: true)
            flashMap()
            return
        }
        advance()
    }

    /// Ⓑ, a tap on the other outer third, Delete.
    func backward() {
        guard phase == .reading else { return }
        if view.fit != .thirds || zoom.active, let frame = shownFrame,
           let next = frame.readingFlow(forward: false, rtl: rtl, from: camera, zoomed: zoom.active) {
            look(at: next, animated: true)
            flashMap()
            return
        }
        retreat()
    }

    private func advance() {
        moved = true
        let unit = state.pageIndex
        guard state.advance() else { return showEndCard() }
        if state.pageIndex != unit {
            arriveAtEnd = false
            readingForward = true
            loadUnit()
        } else {
            placeStep(animated: true)
        }
    }

    private func retreat() {
        moved = true
        let unit = state.pageIndex
        guard state.retreat() else { return movePublication(-1) }
        if state.pageIndex != unit {
            arriveAtEnd = true
            readingForward = false
            loadUnit()
        } else {
            placeStep(animated: true)
        }
    }

    /// Ⓧ and Ⓨ, a swipe, the bar's arrows: a whole page past any thirds.
    func turnPage(_ delta: Int) {
        guard phase == .reading else { return }
        moved = true
        if state.turnPage(delta) {
            arriveAtEnd = delta < 0
            readingForward = delta > 0
            loadUnit()
        } else if delta > 0 {
            showEndCard()
        } else {
            movePublication(-1)
        }
    }

    /// The D-pad: across the page, turning at its edge going sideways; up and
    /// down only ever pan.
    func move(_ direction: PadDirection) {
        guard phase == .reading else { return }
        if let frame = shownFrame, let next = frame.pan(direction, from: camera, zoomed: zoom.active) {
            look(at: next, animated: true)
            flashMap()
            return
        }
        switch direction {
        case .left: rtl ? advance() : retreat()
        case .right: rtl ? retreat() : advance()
        case .up, .down: break
        }
    }

    /// A page chosen in the grid or on the scrubber: it opens at its top.
    func jump(to page: Int) {
        guard phase == .reading else { return }
        let unit = units.unit(containing: page)
        gridOpen = false
        guard unit != state.pageIndex else { return }
        moved = true
        readingForward = unit > state.pageIndex
        state.seek(unit)
        arriveAtEnd = false
        loadUnit()
    }

    /// The issue before or after, from the manifest, or why there is none.
    func movePublication(_ delta: Int) {
        guard let manifest else { return }
        endCard = nil
        let target = delta < 0 ? manifest.previousSourceItemId : manifest.nextSourceItemId
        guard !target.isEmpty else {
            return say(delta < 0 ? "This is the first issue" : "This is the last issue")
        }
        flushPlace()
        open(target, atEnd: delta < 0, moving: true)
    }

    // MARK: The end of an issue (#16, C6)

    private func showEndCard() {
        guard let manifest else { return }
        controlsVisible = false
        mapShown = false
        stepShown = nil
        flushPlace()
        let series = manifest.seriesTitle.isEmpty ? (work?.title ?? "") : manifest.seriesTitle
        let heading = EndOfIssue.heading(series: series, title: manifest.title, number: manifest.number)
        guard !manifest.nextSourceItemId.isEmpty else {
            endCard = EndCard(heading: heading, next: EndOfIssue.next(currentSeries: series, nextSeries: nil, nextTitle: "",
                                                                        nextNumber: "", readingList: false),
                              canContinue: false)
            return
        }
        let next = manifest.nextSourceItemId
        if let work, work.sections.contains(where: { $0.items.contains { $0.sourceItemId == next } }) {
            let item = ReadingWorkPresentation.publication(work, sourceItemId: next)
            endCard = EndCard(heading: heading, next: EndOfIssue.next(currentSeries: series, nextSeries: series,
                                                                        nextTitle: item.title, nextNumber: item.number,
                                                                        readingList: false), canContinue: true)
            return
        }
        endCard = EndCard(heading: heading, next: "Next: the next issue", canContinue: true)
        let request = HubEndpoints.readingPublication(workId: workId, sourceItemId: next)
        endLookup = Task { [weak self, hub] in
            guard let following = try? await hub.fetch(request, as: ReadingPublicationManifest.self),
                  let self, !Task.isCancelled, self.endCard != nil else { return }
            self.endCard?.next = EndOfIssue.next(currentSeries: series,
                                                 nextSeries: following.seriesTitle.isEmpty ? series : following.seriesTitle,
                                                 nextTitle: following.title, nextNumber: following.number, readingList: false)
        }
    }

    func stayOnLastPage() {
        endCard = nil
        endLookup?.cancel()
    }

    // MARK: How the series reads

    func setFit(_ fit: ComicFit) {
        view.fit = fit
        ComicReaderSettings.setView(view, workId: workId)
        zoom = ComicZoom()
        reshapeKeepingPage()
    }

    func toggleThirds() { setFit(view.fit == .thirds ? .whole : .thirds) }

    func setTrim(_ on: Bool) {
        view.trim = on
        ComicReaderSettings.setView(view, workId: workId)
        reshapeKeepingPage()
        planNeighbours()
    }

    func setDirection(_ direction: String?) {
        view.direction = direction
        ComicReaderSettings.setView(view, workId: workId)
        // Right to left the pairs stand the other way round.
        reshapeKeepingPage()
    }

    func openEverySeriesThisWay() {
        ComicReaderSettings.defaultFit = view.fit
    }

    // MARK: Zoom

    /// The zoom keys and buttons: closer or further round the middle of the view, and kept.
    func zoom(by factor: Double) {
        guard let frame = shownFrame else { return }
        let target = frame.zoomed(by: factor, from: camera)
        look(at: target, animated: true)
        setZoom(ComicZoom.of(scale: target.scale, base: frame.baseScale(view.fit), centerX: target.x, pageWidth: frame.width),
                snap: true)
        flashMap()
    }

    /// A zoom of your own, kept for the pages after. Beginning one in thirds
    /// makes this page one step; ending one goes back to the step nearest.
    func setZoom(_ next: ComicZoom, snap: Bool) {
        let was = zoom.active
        zoom = next
        guard was != next.active else { return }
        if !next.active, view.fit == .thirds, let frame = shownFrame {
            state.jump(page: state.pageIndex, step: frame.nearestStep(camera))
            if snap { placeStep(animated: true) }
        } else {
            state.refit()
        }
    }

    /// L3 held: twice as close round the middle of the view; let go, back to where it was.
    func magnify(_ on: Bool) {
        guard let frame = shownFrame else { return }
        if on {
            guard magnified == nil else { return }
            magnified = camera
            look(at: frame.clamp(ComicCamera(scale: camera.scale * ComicFrame.magnify, x: camera.x, y: camera.y)),
                 animated: true)
        } else if let before = magnified {
            magnified = nil
            look(at: before, animated: true)
        }
    }

    /// The right stick: the page moves under it, smoothly.
    func glide(dx: Double, dy: Double) {
        guard let frame = shownFrame else { return }
        camera = frame.glide(dx: dx, dy: dy, from: camera)
        keepAnchor()
        flashMap()
    }

    // MARK: Touch

    /// A tap on the page: the outer thirds read on or back, the middle shows or hides the controls.
    func tapped(x: Double) {
        guard endCard == nil else { return stayOnLastPage() }
        switch ComicTouch.tap(x: x, width: viewSize.width, rtl: rtl, controlsVisible: controlsVisible) {
        case .back: backward()
        case .forward: forward()
        case .controls: setControls(!controlsVisible)
        }
    }

    /// A double tap: zoomed, back to the fit; else closer round the point tapped.
    func doubleTapped(at point: CGPoint) {
        guard let frame = shownFrame, phase == .reading else { return }
        if zoom.active || frame.isZoomed(camera, fit: view.fit) {
            zoom = ComicZoom()
            state.jump(page: state.pageIndex, step: view.fit == .thirds ? frame.nearestStep(camera) : 0)
            placeStep(animated: true)
            return
        }
        let target = frame.doubleTapped(atX: point.x, y: point.y, camera: camera, fit: view.fit)
        look(at: target, animated: true)
        setZoom(ComicZoom.of(scale: target.scale, base: frame.baseScale(view.fit), centerX: target.x, pageWidth: frame.width),
                snap: false)
    }

    /// No finger is moving the page.
    var gestureIsIdle: Bool { dragStart == nil && pinchStart == nil }

    func pinchChanged(magnification: Double, anchor: CGPoint) {
        guard let frame = shownFrame, phase == .reading else { return }
        if pinchStart == nil { pinchStart = camera }
        if dragStart != nil { dragSpoiled = true }
        camera = frame.pinched(pinchStart ?? camera, magnification: magnification, anchorX: anchor.x, anchorY: anchor.y)
    }

    func pinchEnded() {
        guard pinchStart != nil else { return }
        pinchStart = nil
        guard let frame = shownFrame else { return }
        let next = ComicZoom.of(scale: camera.scale, base: frame.baseScale(view.fit), centerX: camera.x, pageWidth: frame.width)
        setZoom(next, snap: true)
        // Pinched back to the fit: the fit exactly.
        if !next.active { placeStep(animated: true) }
    }

    func dragChanged(_ translation: CGSize) {
        guard let frame = shownFrame, phase == .reading else { return }
        if dragStart == nil {
            dragStart = camera
            dragSpoiled = pinchStart != nil
        }
        guard !dragSpoiled, pinchStart == nil, let start = dragStart else { return }
        camera = frame.dragged(start, dx: translation.width, dy: translation.height)
    }

    /// A drag let go: a swipe across turns the page (#18, C7); anything else
    /// glides on a little as it was going, and in thirds the step nearest is
    /// where Ⓐ goes on from.
    func dragEnded(_ translation: CGSize, predicted: CGSize) {
        defer {
            dragStart = nil
            dragSpoiled = false
        }
        guard let frame = shownFrame, let start = dragStart, !dragSpoiled else { return }
        let zoomed = zoom.active || frame.isZoomed(start, fit: view.fit)
        let turn = ComicTouch.swipe(dx: translation.width, dy: translation.height, width: viewSize.width, rtl: rtl,
                                    zoomed: zoomed, pinched: false)
        if turn != 0 {
            camera = start
            return turnPage(turn)
        }
        look(at: frame.dragged(start, dx: predicted.width, dy: predicted.height), animated: true, duration: 0.32)
        keepAnchor()
        if view.fit == .thirds, !zoom.active {
            state.jump(page: state.pageIndex, step: frame.nearestStep(camera))
            savePlace()
        }
        flashMap()
    }

    /// The page map's band, tapped: that step.
    func pickStep(_ step: Int) {
        state.jump(page: state.pageIndex, step: step)
        placeStep(animated: true)
    }

    // MARK: The controls

    func setControls(_ visible: Bool) {
        controlsHiding?.cancel()
        controlsVisible = visible
        if visible {
            mapShown = false
            stepShown = nil
            focusedControl = focusedControl ?? controls.last
        }
    }

    /// Open, the controls go a moment after the page shows, so the page is read whole.
    private func hideControlsSoon() {
        controlsHiding?.cancel()
        guard !controlsPinned else { return }
        controlsHiding = Task { [weak self] in
            try? await Task.sleep(for: .seconds(3))
            guard !Task.isCancelled, let self, self.sheet == nil, !self.gridOpen else { return }
            withAnimation(.easeOut(duration: 0.2)) { self.controlsVisible = false }
        }
    }

    func touchedControls() { controlsHiding?.cancel() }

    func moveControlFocus(_ direction: PadDirection) {
        guard !controls.isEmpty else { return }
        let index = focusedControl.flatMap { controls.firstIndex(of: $0) }
            ?? ReaderControlFocusPolicy.initialIndex(controlCount: controls.count) ?? 0
        let next = direction == .left || direction == .up ? index - 1 : index + 1
        focusedControl = controls[min(max(next, 0), controls.count - 1)]
    }

    func perform(_ control: ComicControl) {
        touchedControls()
        switch control {
        case .close: break
        case .previousIssue: movePublication(-1)
        case .thirds: toggleThirds()
        case .zoomOut: zoom(by: 0.8)
        case .zoomIn: zoom(by: 1.25)
        case .display: sheet = .display
        case .keys: sheet = .keys
        case .nextIssue: movePublication(1)
        case .previousPage: turnPage(-1)
        case .pages: openGrid()
        case .nextPage: turnPage(1)
        }
    }

    func openGrid() {
        guard manifest != nil else { return }
        controlsVisible = false
        gridCursor = currentPage
        gridOpen = true
    }

    // MARK: Words for a moment

    func say(_ words: String) {
        noticeHiding?.cancel()
        withAnimation(.easeOut(duration: 0.2)) { notice = words }
        noticeHiding = Task { [weak self] in
            try? await Task.sleep(for: .seconds(3))
            guard !Task.isCancelled else { return }
            withAnimation(.easeOut(duration: 0.2)) { self?.notice = nil }
        }
    }

    /// "Part 2 of 3" and the page's map, for a moment: only with the controls
    /// out of the way, which would cover them.
    func flashMap() {
        guard !controlsVisible, shown != nil else { return }
        hints?.cancel()
        let steps = state.viewportSteps
        stepShown = steps > 1 && !zoom.active ? StepShown(index: state.viewportIndex, count: steps) : nil
        mapShown = true
        hints = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(ComicPageMap.showMillis))
            guard !Task.isCancelled else { return }
            withAnimation(.easeOut(duration: 0.25)) {
                self?.mapShown = false
                self?.stepShown = nil
            }
        }
    }

    // MARK: The view

    /// The page's size, from the canvas: turned, resized, or first laid out
    /// after the issue came. The units again (spreads in a wide window held
    /// sideways), the same page, placed anew.
    func viewChanged(size: CGSize) {
        guard size.width > 0, size.height > 0, size != viewSize else { return }
        viewSize = size
        guard manifest != nil, phase == .reading else { return }
        reshapeKeepingPage()
    }

    /// Looks at `target`, sliding there when `animated`.
    func look(at target: ComicCamera, animated: Bool, duration: Double = 0.18) {
        if animated {
            withAnimation(.easeOut(duration: duration)) { camera = target }
        } else {
            camera = target
        }
    }

    /// A pan of a zoomed page moves where the next page opens across.
    private func keepAnchor() {
        guard zoom.active, let frame = shownFrame, frame.width > 0 else { return }
        zoom.anchorX = min(1, max(0, camera.x / frame.width))
    }

    /// Places the unit shown as the fit, the zoom and the step say, and keeps the place.
    func placeStep(animated: Bool) {
        guard let frame = shownFrame else { return }
        look(at: frame.placement(fit: view.fit, step: state.viewportIndex, atEnd: false, zoom: zoom), animated: animated)
        savePlace()
        flashMap()
    }

    // MARK: The place (#25: Kavita's, through the hub)

    /// The page on screen is the place, kept on the device with its third,
    /// and sent once it has rested a moment.
    func savePlace() {
        guard let manifest else { return }
        let page = currentPage
        ComicReaderSettings.setPlace(ComicPlace(manifest.sourceItemId, page: page, step: state.viewportIndex), workId: workId)
        guard moved else { return }
        outbox.show(page)
        saving?.cancel()
        guard outbox.pending else { return }
        saving = Task { [weak self] in
            try? await Task.sleep(for: .seconds(3))
            guard !Task.isCancelled else { return }
            self?.sendPlace()
        }
    }

    /// Leaving, a new issue, the end of one, the app going away: now.
    func flushPlace() {
        saving?.cancel()
        sendPlace()
    }

    private func sendPlace() {
        guard let manifest, let body = outbox.next() else { return }
        let request = HubEndpoints.saveReadingPublicationProgress(workId: workId, sourceItemId: manifest.sourceItemId, body)
        let publication = manifest.sourceItemId
        Task { [weak self, hub] in
            var ok = false
            var conflict = false
            do throws(HubFailure) {
                try await hub.send(request)
                ok = true
            } catch {
                conflict = error.status == 409
            }
            guard let self, self.manifest?.sourceItemId == publication else { return }
            self.outbox.answered(ok: ok, conflict: conflict)
            if conflict {
                self.say("This issue was read on another device since, so your page here was not saved")
            } else if ok, self.outbox.pending {
                // Read on while that one was on its way: the newer page now.
                self.sendPlace()
            } else if !ok {
                // Not reached: again in a while, never in a loop.
                self.saving?.cancel()
                self.saving = Task { [weak self] in
                    try? await Task.sleep(for: .seconds(15))
                    guard !Task.isCancelled else { return }
                    self?.sendPlace()
                }
            }
        }
    }

    /// The reader goes: its place is sent, and nothing else goes on.
    func stop() {
        flushPlace()
        for task in [opening, hints, waiting, noticeHiding, controlsHiding, endLookup] { task?.cancel() }
        for task in loads.values { task.cancel() }
        for task in measuring.values { task.cancel() }
        loads = [:]
        measuring = [:]
    }

    func waited(for unit: Int?) {
        waiting?.cancel()
        guard let unit else {
            waitingFor = nil
            return
        }
        // Nothing on screen yet: at once. A page on screen stays, and says so if this takes a while.
        guard shown != nil else {
            waitingFor = unit
            return
        }
        waiting = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(300))
            guard !Task.isCancelled, let self, self.pendingUnit == unit else { return }
            self.waitingFor = unit
        }
    }
}

/// The controls on the bars, in the order the pad moves through them.
enum ComicControl: Hashable, CaseIterable {
    case close, previousIssue, thirds, zoomOut, zoomIn, display, keys, nextIssue
    case previousPage, pages, nextPage

    var label: String {
        switch self {
        case .close: "Close reader"
        case .previousIssue: "Previous issue"
        case .thirds: "Read each page in thirds"
        case .zoomOut: "Zoom out"
        case .zoomIn: "Zoom in"
        case .display: "Reading options"
        case .keys: "Keys"
        case .nextIssue: "Next issue"
        case .previousPage: "Previous page"
        case .pages: "Pages"
        case .nextPage: "Next page"
        }
    }

    var systemImage: String {
        switch self {
        case .close: "xmark"
        case .previousIssue: "backward.end.fill"
        case .thirds: "rectangle.split.1x2"
        case .zoomOut: "minus.magnifyingglass"
        case .zoomIn: "plus.magnifyingglass"
        case .display: "textformat.size"
        case .keys: "gamecontroller"
        case .nextIssue: "forward.end.fill"
        case .previousPage: "chevron.left"
        case .pages: "square.grid.3x3"
        case .nextPage: "chevron.right"
        }
    }
}
