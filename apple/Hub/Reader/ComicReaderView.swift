import HubKit
import SwiftUI

/// The comic and manga reader (#25, phase 3), over the whole window: what a
/// comic run's issue or a manga volume opens. The Books side's `ReaderHost`
/// shows it for `ReadRequest.pages(work:publication:)` and sets
/// `\.closeReader`; debug builds can open it at launch against the demo hub
/// (`ComicReaderDebugLaunch`).
struct ComicReaderView: View {
    private let work: ReadingWork?
    private let workId: String
    private let publication: ReadingSectionItem
    private let list: ReadingListRun?
    @Environment(AppModel.self) private var model
    @Environment(\.readingMarks) private var marks
    @State private var reader: ComicReaderModel?

    /// The issue `publication` of `work`, the run its page loaded.
    init(work: ReadingWork, publication: ReadingSectionItem) {
        self.work = work
        workId = work.id
        self.publication = publication
        list = nil
    }

    /// Kavita's reading list `list`, at its issue open (#37): each issue in its own run.
    init(list: ReadingListRun) {
        work = nil
        workId = list.current.workId
        publication = list.current.publication
        self.list = list
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if let reader {
                ComicReaderScreen(reader: reader)
            }
        }
        .task {
            guard reader == nil else { return }
            let opened = ComicReaderModel(hub: model.hub, work: work, workId: workId, publication: publication)
            opened.list = list
            // The issues' page lists kept for an outage (#37).
            opened.manifests = ReadingOffline.manifests(app: model)
            opened.scope = ReadingOffline.scope(app: model)
            // Marked unread: the issue opens at its first page. A page sent forgets the mark (#37).
            opened.startsFresh = marks.startsFresh(workId)
            let keeper = marks.kept
            opened.onKept = { runId in keeper(runId) }
            #if DEBUG
            opened.controlsPinned = ProcessInfo.processInfo.environment["HUB_READ_CHROME"] == "pinned"
            #endif
            reader = opened
            opened.start()
        }
    }
}

/// The reader laid out: the page, the bars over it, and whatever opens over
/// those, with the keyboard's and a controller's keys.
struct ComicReaderScreen: View {
    /// The reader's own coordinates: the screen's, safe area and all.
    nonisolated static let space = "comic-reader"

    let reader: ComicReaderModel
    @Environment(AppModel.self) private var model
    @Environment(\.closeReader) private var closeReader
    @Environment(\.scenePhase) private var scenePhase
    @State private var pad = ReaderPadInput()
    @State private var comfort = ReaderComfort.shared
    @State private var scrubbing: Int?
    @State private var scrubTrack = CGRect.zero
    @FocusState private var keys: Bool

    var body: some View {
        GeometryReader { proxy in
            let safe = proxy.safeAreaInsets
            let screen = CGSize(width: proxy.size.width + safe.leading + safe.trailing,
                                height: proxy.size.height + safe.top + safe.bottom)
            let layout = ComicReaderLayout(size: screen, safe: safe)
            ZStack {
                Color.black
                #if os(iOS)
                // Under the page: the curl, which the page lets reach its outer edges (#32).
                if let snapshot = curlSnapshot {
                    ComicCurlView(reader: reader, snapshot: snapshot)
                }
                #endif
                ComicPageCanvas(reader: reader)
                ComicReaderStatus(reader: reader, leave: leave)
                hints(layout)
                // Up while an issue opens too, so Close is always there to touch.
                if reader.controlsVisible {
                    ComicReaderBars(reader: reader, layout: layout, leave: leave, scrubbing: $scrubbing,
                                    scrubTrack: $scrubTrack)
                        .transition(.opacity)
                        .onAppear { reader.controls = layout.controls }
                        .onChange(of: layout.tier) { _, _ in reader.controls = layout.controls }
                }
                if let page = scrubbing {
                    scrubPreview(page, layout: layout, screen: screen)
                }
                if let card = reader.endCard {
                    ComicEndCard(card: card, compact: layout.phone, next: { reader.movePublication(1) },
                                 stay: { reader.stayOnLastPage() }, leave: leave)
                        .padding(.horizontal, layout.side + 8)
                        .padding(.bottom, layout.bottom + 24)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
                if let notice = reader.notice {
                    ComicPill(text: notice)
                        .padding(.horizontal, layout.side + 8)
                        .padding(.top, layout.top + (reader.controlsVisible ? layout.round + 30 : 12))
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                        .transition(.opacity)
                }
                if reader.gridOpen {
                    ComicPagesGrid(reader: reader, layout: layout)
                        .transition(.opacity)
                }
                if let sheet = reader.sheet {
                    ComicReaderSheetView(reader: reader, sheet: sheet, layout: layout)
                        .id(sheet)
                }
                // Comfort over the whole reader, pages and controls (#37).
                ComfortLayer(comfort: comfort.value)
                #if os(macOS)
                // The hidden title bar's band still moves the window.
                Color.clear
                    .frame(height: safe.top)
                    .contentShape(Rectangle())
                    .gesture(WindowDragGesture())
                    .allowsWindowActivationEvents(true)
                    .frame(maxHeight: .infinity, alignment: .top)
                #endif
            }
            .coordinateSpace(.named(Self.space))
            .ignoresSafeArea()
            .animation(.easeOut(duration: 0.2), value: reader.controlsVisible)
            .animation(.easeOut(duration: 0.25), value: reader.endCard)
            .animation(.easeOut(duration: 0.2), value: reader.gridOpen)
        }
        // Over the page the glass is dark whatever it shows, tinted by the
        // issue's cover, and the accent is Books' gold.
        .environment(\.glassPalette, model.colors.palette(for: reader.cover))
        .environment(\.glassOverVideo, true)
        .environment(\.glassAccent, AccentModel.shared.books)
        .onChange(of: reader.cover, initial: true) { _, path in
            if !path.isEmpty { model.colors.want([path]) }
        }
        .foregroundStyle(.white)
        #if os(iOS)
        .statusBarHidden(true)
        .persistentSystemOverlays(.hidden)
        // The edges are the curl's (#32): the system's own edge swipes wait for a second one.
        .defersSystemGestures(on: .all)
        #endif
        .focusable()
        .focused($keys)
        .focusEffectDisabled()
        .onKeyPress(.space) { press(.space) }
        .onKeyPress(.return) { press(.returnKey) }
        .onKeyPress(.delete) { press(.delete) }
        .onKeyPress(.escape) { press(.escape) }
        .onKeyPress(.leftArrow) { press(.left) }
        .onKeyPress(.rightArrow) { press(.right) }
        .onKeyPress(.upArrow) { press(.up) }
        .onKeyPress(.downArrow) { press(.down) }
        .onKeyPress(.pageUp) { press(.pageUp) }
        .onKeyPress(.pageDown) { press(.pageDown) }
        .onKeyPress(characters: CharacterSet(charactersIn: "-=+")) { press in
            self.press(press.characters == "-" ? .minus : .plus)
        }
        .onAppear {
            keys = true
            pad.start { action in reader.padWithCurl(action) }
        }
        .onDisappear {
            pad.stop()
            reader.stop()
        }
        // The page keeps the keyboard as the controls come and go: on an iPad
        // the bars' going left the focus nowhere, and the next key was lost.
        .onChange(of: reader.controlsVisible) { _, _ in keys = true }
        .onChange(of: pad.connected, initial: true) { _, connected in reader.controllerActive = connected }
        .onChange(of: reader.leaving) { _, leaving in
            if leaving { leave() }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase != .active { reader.flushPlace() }
        }
        #if DEBUG
        .task(id: reader.phase == .reading) { await debugTour() }
        #endif
        .accessibilityAddTraits(.isModal)
    }

    #if os(iOS)
    /// What the curl shows, read here so a change to any of it reaches the curl.
    private var curlSnapshot: ComicCurlSnapshot? {
        guard let shown = reader.shown, let unit = reader.units.units.firstIndex(of: shown.unit) else { return nil }
        return ComicCurlSnapshot(publication: shown.publication, unit: unit, unitCount: reader.units.units.count,
                                 spreads: reader.units.widest > 1, rtl: reader.rtl, size: reader.viewSize,
                                 camera: reader.camera, fit: reader.view.fit, decoded: reader.images.count,
                                 turn: reader.curlTurn)
    }
    #endif

    /// "Part 2 of 3" at the foot and the page's map in a corner, for a moment
    /// after a step or a pan, while the controls are out of the way.
    @ViewBuilder private func hints(_ layout: ComicReaderLayout) -> some View {
        if !reader.controlsVisible, reader.endCard == nil {
            if let step = reader.stepShown {
                ComicPill(text: ReaderTitleFormatter.part(step.index + 1, of: step.count))
                    .padding(.bottom, layout.bottom + 16)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
                    .transition(.opacity)
            }
            if reader.mapShown {
                ComicPageMapView(reader: reader)
                    .padding(.top, layout.top + 8)
                    .padding(.trailing, layout.side + 8)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
                    .transition(.opacity)
            }
        }
    }

    /// The scrubber's preview over the point of its track the page is at.
    private func scrubPreview(_ page: Int, layout: ComicReaderLayout, screen: CGSize) -> some View {
        let width = layout.previewWidth
        let share = reader.pageCount > 1 ? Double(page) / Double(reader.pageCount - 1) : 0
        let center = scrubTrack.width > 0
            ? PlayerGestures.scrubCardCenter(share: share, trackMinX: scrubTrack.minX, trackWidth: scrubTrack.width,
                                             cardWidth: width, screenWidth: screen.width)
            : screen.width / 2
        let above = scrubTrack.height > 0 ? max(0, screen.height - scrubTrack.minY + 14) : layout.bottom + 90
        let path = HubEndpoints.readingPublicationThumb(workId: reader.workId,
                                                        sourceItemId: reader.manifest?.sourceItemId ?? "", page: page,
                                                        width: PageGrid.thumbWidth)
        return ComicScrubPreview(path: path, page: page, width: width)
            .padding(.bottom, above)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
            .offset(x: center - width / 2)
    }

    private func press(_ key: ReaderKey) -> KeyPress.Result {
        reader.key(key)
        return .handled
    }

    private func leave() {
        reader.leaving = false
        pad.stop()
        reader.stop()
        closeReader()
        #if DEBUG
        // HUB_DEMO_OUTAGE=after-close, the demo hub only: the reading servers go once a reader has closed (#37).
        if model.isDemo, ProcessInfo.processInfo.environment["HUB_DEMO_OUTAGE"] == "after-close" { DemoTransport.beginOutage() }
        #endif
    }

    #if DEBUG
    /// Debug builds, for screenshots: HUB_READ_FIT=whole|width|thirds reads
    /// the run that way (kept, as Display keeps it), HUB_READ_PAGE=<n> opens
    /// that page, HUB_READ_SHEET=display|keys|pages|end opens a sheet, the
    /// Pages grid or the end card once the issue has opened.
    private func debugTour() async {
        guard reader.phase == .reading else { return }
        let environment = ProcessInfo.processInfo.environment
        try? await Task.sleep(for: .milliseconds(600))
        guard !Task.isCancelled else { return }
        if let fit = environment["HUB_READ_FIT"].flatMap(ComicFit.init(rawValue:)), fit != reader.view.fit {
            reader.setFit(fit)
            try? await Task.sleep(for: .milliseconds(300))
        }
        if let page = environment["HUB_READ_PAGE"].flatMap(Int.init) { reader.jump(to: max(0, page - 1)) }
        switch environment["HUB_READ_SHEET"] {
        case "display": reader.sheet = .display
        case "keys": reader.sheet = .keys
        case "pages": reader.openGrid()
        case "end":
            reader.jump(to: max(0, reader.pageCount - 1))
            try? await Task.sleep(for: .milliseconds(900))
            reader.turnPage(1)
        default: break
        }
    }
    #endif
}
