import HubKit
import SwiftUI

/// The ebook reader (#25, phase 4), over the whole window: what Read opens
/// for a book's EPUB, as the Books side asks for it
/// (`ReadRequest.ebook(work:sourceItemId:readAlong:)`). `ReaderHost` shows it
/// and sets `\.closeReader`; debug builds can open it at launch against the
/// demo hub (`BookReaderDebugLaunch`). Readium's navigator is UIKit, so it
/// reads on the iPad and the iPhone; the Mac says ebooks come later.
/// Read along (`readAlong`) arrives after phase 2: until then the book opens
/// to be read.
struct BookReaderView: View {
    let work: ReadingWork
    let sourceItemId: String
    var readAlong = false
    @Environment(AppModel.self) private var model
    #if os(iOS)
    @State private var reader: BookReaderModel?
    #endif

    var body: some View {
        #if os(iOS)
        ZStack {
            Color.black.ignoresSafeArea()
            if let reader {
                BookReaderScreen(reader: reader)
            }
        }
        .task {
            guard reader == nil else { return }
            let opened = BookReaderModel(app: model, work: work, sourceItemId: sourceItemId)
            reader = opened
            opened.start()
            #if DEBUG
            if ProcessInfo.processInfo.environment["HUB_BOOK_CHROME"] == "pinned" { opened.setControls(true) }
            #endif
        }
        #else
        BookReaderUnavailable(work: work)
        #endif
    }
}

/// The Mac until it has a reader of its own: Readium's navigator is UIKit.
struct BookReaderUnavailable: View {
    let work: ReadingWork
    @Environment(\.closeReader) private var closeReader

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VStack(spacing: 12) {
                Text(work.title)
                    .font(HubType.heading(26, weight: .heavy, relativeTo: .title))
                    .multilineTextAlignment(.center)
                Text("Ebooks open on the iPad and the iPhone for now. The Mac reads them in a later build.")
                    .font(HubType.body(15, relativeTo: .body))
                    .foregroundStyle(.white.opacity(0.75))
                    .multilineTextAlignment(.center)
                Button("Close") { closeReader() }
                    .buttonStyle(GlassPillStyle())
                    .keyboardShortcut(.cancelAction)
                    .padding(.top, 6)
            }
            .padding(28)
            .frame(maxWidth: 440)
            .glassPanel(RoundedRectangle(cornerRadius: 24, style: .continuous))
            .padding(20)
        }
        .foregroundStyle(.white)
    }
}

#if os(iOS)
/// The reader laid out: the page (Readium's navigator), the menu round it
/// with the page making room, and whatever opens over them, with the
/// keyboard's and a controller's keys.
struct BookReaderScreen: View {
    /// The reader's own coordinates: the screen's, safe area and all.
    nonisolated static let space = "book-reader"

    let reader: BookReaderModel
    @Environment(AppModel.self) private var model
    @Environment(\.closeReader) private var closeReader
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.colorScheme) private var colorScheme
    @State private var pad = ReaderPadInput()
    @State private var topBar = CGRect.zero
    @State private var bottomBar = CGRect.zero
    @FocusState private var keys: Bool

    var body: some View {
        GeometryReader { proxy in
            let safe = proxy.safeAreaInsets
            let screen = CGSize(width: proxy.size.width + safe.leading + safe.trailing,
                                height: proxy.size.height + safe.top + safe.bottom)
            let layout = ComicReaderLayout(size: screen, safe: safe)
            ZStack {
                // Beside the page shrunk for the menu: a tap there goes back to the page.
                Color.black
                    .contentShape(Rectangle())
                    .onTapGesture { if reader.controlsVisible && reader.sheet == nil { reader.setControls(false) } }
                    .accessibilityHidden(true)
                page(layout)
                BookReaderStatus(reader: reader, leave: leave)
                if reader.controlsVisible && reader.sheet == nil && reader.phase == .reading {
                    BookReaderBars(reader: reader, layout: layout, leave: leave, topBar: $topBar, bottomBar: $bottomBar)
                        .transition(.opacity)
                }
                if let note = reader.footnote {
                    FootnoteCard(text: note, compact: layout.phone, follow: { reader.followNote() },
                                 close: { reader.closeNote() })
                        .transition(.opacity)
                }
                if let notice = reader.notice {
                    ComicPill(text: notice)
                        .padding(.horizontal, layout.side + 8)
                        .padding(.top, layout.top + (reader.controlsVisible ? layout.round + 30 : 12))
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                        .allowsHitTesting(false)
                        .transition(.opacity)
                }
                if let sheet = reader.sheet {
                    BookReaderSheetView(reader: reader, sheet: sheet, layout: layout)
                        .id(sheet == .bookmarks ? BookReaderModel.Sheet.contents : sheet)
                }
            }
            .coordinateSpace(.named(Self.space))
            .ignoresSafeArea()
            .animation(.easeOut(duration: 0.2), value: reader.controlsVisible)
            .animation(.easeOut(duration: 0.2), value: reader.footnote)
            .animation(.easeOut(duration: 0.2), value: reader.notice)
        }
        // Over the page the glass is dark whatever the book shows, tinted by
        // its cover, and the accent is Books' gold.
        .environment(\.glassPalette, model.colors.palette(for: reader.cover))
        .environment(\.glassOverVideo, true)
        .environment(\.glassAccent, AccentPreset.defaultFor(.books))
        .onChange(of: reader.cover, initial: true) { _, path in
            if !path.isEmpty { model.colors.want([path]) }
        }
        .foregroundStyle(.white)
        .statusBarHidden(true)
        .persistentSystemOverlays(.hidden)
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
            reader.systemDark = colorScheme == .dark
            pad.start { action in reader.pad(action) }
        }
        .onDisappear {
            pad.stop()
            reader.stop()
        }
        .onChange(of: pad.connected, initial: true) { _, connected in reader.controllerActive = connected }
        .onChange(of: colorScheme) { _, scheme in reader.systemDark = scheme == .dark }
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

    /// The book, shrunk and moved (never laid out again) to make room for the
    /// menu's bars, or for a sheet beside it.
    @ViewBuilder private func page(_ layout: ComicReaderLayout) -> some View {
        if let controller = reader.controller {
            let fit = pageFit(layout)
            BookPageHost(controller: controller)
                .id(ObjectIdentifier(controller))
                .frame(width: layout.size.width, height: layout.size.height)
                .clipShape(RoundedRectangle(cornerRadius: fit.scale < 1 ? 14 / fit.scale : 0, style: .continuous))
                .scaleEffect(fit.scale, anchor: .topLeading)
                .offset(x: fit.x, y: fit.y)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                .animation(.easeOut(duration: 0.18), value: fit)
                .accessibilityIdentifier("book-page")
        }
    }

    private func pageFit(_ layout: ComicReaderLayout) -> ReaderPageTransform {
        let beside = reader.sheet != nil && !ReaderSheetFrame<EmptyView>.fromBottom(layout.size)
        let menu = reader.controlsVisible && reader.sheet == nil && reader.phase == .reading
        guard menu || beside else { return .identity }
        // Until the bars have been measured, about where they will be.
        let top = !menu ? 0 : topBar.height > 0 ? topBar.maxY : layout.top + layout.round + 12
        let bottom = !menu ? 0 : bottomBar.height > 0 ? max(0, layout.size.height - bottomBar.minY) : layout.bottom + 100
        let right = beside ? ReaderSheetFrame<EmptyView>.width(layout.size, safe: layout.safe) : 0
        return ReaderPagePreview.fit(width: layout.size.width, height: layout.size.height, top: top, bottom: bottom,
                                     right: right, margin: 12)
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
    }

    #if DEBUG
    /// Debug builds, for screenshots: HUB_BOOK_AT=<percent> goes that far
    /// into the book, HUB_BOOK_SHEET=menu|contents|bookmarks|appearance|keys
    /// opens the menu or a sheet, once the book has opened.
    private func debugTour() async {
        guard reader.phase == .reading else { return }
        let environment = ProcessInfo.processInfo.environment
        try? await Task.sleep(for: .milliseconds(900))
        guard !Task.isCancelled else { return }
        if let percent = environment["HUB_BOOK_AT"].flatMap(Double.init) {
            reader.seek(percent / 100)
            try? await Task.sleep(for: .milliseconds(900))
        }
        switch environment["HUB_BOOK_SHEET"] {
        case "menu": reader.setControls(true)
        case "contents": reader.openSheet(.contents)
        case "bookmarks": reader.openSheet(.bookmarks)
        case "appearance": reader.openSheet(.appearance)
        case "keys": reader.openSheet(.keys)
        default: break
        }
    }
    #endif
}

/// Readium's navigator in the SwiftUI page.
struct BookPageHost: UIViewControllerRepresentable {
    let controller: UIViewController

    func makeUIViewController(context: Context) -> UIViewController {
        // What the UI tests find the page by, on the UIKit view as well as
        // SwiftUI's. Loaded here, as it is about to be shown, never earlier:
        // Readium lays the book out for the size its view first has.
        controller.view.accessibilityIdentifier = "book-page"
        return controller
    }

    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}
#endif
