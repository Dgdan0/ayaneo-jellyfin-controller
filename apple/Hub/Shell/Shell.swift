import HubKit
import Observation
import SwiftUI
#if os(iOS)
import UIKit
#elseif os(macOS)
import AppKit
#endif

/// The app's places, in the Android app's order (`HubActivity.sectionTitles`).
/// The first five are the sections in the capsule or the tab bar; the last
/// three open from the icons (iPad, Mac) or the avatar's sheet (iPhone).
enum AppSection: String, Hashable, CaseIterable {
    case home, discover, library, downloads, activity, notifications, services, settings

    static let sections: [AppSection] = [.home, .discover, .library, .downloads, .activity]

    var title: String { rawValue.capitalized }

    var systemImage: String {
        switch self {
        case .home: "house"
        case .discover: "safari"
        case .library: "books.vertical"
        case .downloads: "arrow.down.to.line"
        case .activity: "waveform.path.ecg"
        case .notifications: "bell"
        case .services: "server.rack"
        case .settings: "gearshape"
        }
    }

    /// The five sections show Media or Books; Notifications, Services and
    /// Settings are the same on both sides.
    var followsSide: Bool { Self.sections.contains(self) }
}

/// A page pushed onto a section's stack.
enum AppRoute: Hashable {
    case title(TitleRoute)
    case folder(FolderRoute)
    case monitor
    /// A title Jellyseerr knows that is not in the library (#17).
    case media(MediaRoute)
    case person(PersonRoute)
    case releaseTargets(ReleaseTargetsRoute)
    case releases(ReleasesRoute)
    // The Books side (#25).
    case readingLibrary(ReadingLibraryRoute)
    case book(BookRoute)
    case author(AuthorRoute)
    case missingBook(MissingBookRoute)
    case bookRequest(BookRequestRoute)
    case readingReleases(ReadingReleasesRoute)
    case listen(ListenRoute)
    /// Kavita's reading lists, or one of them (#37).
    case readingLists(ReadingListsRoute)
    // Activity (#29).
    case transfers(TransfersRoute)
    case speedLimits
    /// Settings › Fonts and licences (#38).
    case licence(LicenceRoute)
    // Library upkeep (#34).
    case subtitles(SubtitlesRoute)
    case removal(RemovalRoute)
    // Downloads for watching away from the hub (#5).
    case offlineTitle(OfflineTitleRoute)
    case offlinePicker(OfflinePickerRoute)
    /// The Downloads page on its queue, from a title's Download button.
    case offlineQueue

    /// What the back pill calls this page from the one above it.
    var name: String {
        switch self {
        case .title(let route): route.title
        case .folder(let route): route.name
        case .monitor: "Server monitor"
        case .media(let route): route.title
        case .person(let route): route.name
        case .releaseTargets: "Find releases"
        case .releases: "Releases"
        case .readingLibrary(let route): route.library.title
        case .book(let route): route.title
        case .author(let route): route.name
        case .missingBook(let route): route.item.title
        case .bookRequest(let route): route.item.title
        case .readingReleases: "Releases"
        case .listen(let route): route.title
        case .readingLists(let route): route.list?.title ?? "Reading lists"
        case .transfers: "Transfers"
        case .speedLimits: "Speed limits"
        case .licence(let route): Licences.all.first { $0.id == route.id }?.name ?? "Licence"
        case .subtitles: "Subtitles"
        case .removal: RemovalLines.heading
        case .offlineTitle(let route): route.title
        case .offlinePicker: "Download episodes"
        case .offlineQueue: "Downloads"
        }
    }
}

/// One navigation stack per section and side. Each is kept while the app
/// runs, so going back to a section finds it where it was left, as on Android.
struct StackKey: Hashable {
    let side: AppSide?
    let section: AppSection

    init(side: AppSide, section: AppSection) {
        self.side = section.followsSide ? side : nil
        self.section = section
    }

    /// The ambient's name for the stack.
    var id: String { side.map { "\($0.rawValue):\(section.rawValue)" } ?? section.rawValue }
}

/// Pushes a page onto the stack the asking page is in, or puts another in
/// its place.
struct OpenRouteAction {
    var push: @MainActor (AppRoute) -> Void = { _ in }
    /// Swaps the page on top for `route` without a push, so Back still goes
    /// where it went: another library chosen from a library's own capsule.
    var replace: @MainActor (AppRoute) -> Void = { _ in }
    /// Takes the top `count` pages off the stack: a page that finished its
    /// work and the one that led to it (a deleted title's two).
    var pop: @MainActor (Int) -> Void = { _ in }

    @MainActor func callAsFunction(_ route: AppRoute) { push(route) }
}

extension EnvironmentValues {
    @Entry var openRoute = OpenRouteAction()
    @Entry var selectSection = SelectSectionAction()
}

/// Goes to one of the sections, as its tab does: a request's Open Library and
/// Open Transfers (#25).
struct SelectSectionAction {
    var select: @MainActor (AppSection) -> Void = { _ in }

    @MainActor func callAsFunction(_ section: AppSection) { select(section) }
}

/// What the shell shows besides the pages: the profiles, the bell's count
/// and the services' one-line summary for the iPhone's sheet.
@MainActor
@Observable
final class ShellModel {
    private(set) var users: [HubUser] = []
    /// The services' notifications and what has been seen of them (#36): the
    /// bell's count and the Notifications page are one answer.
    let notifications = NotificationsModel()
    private(set) var servicesSummary = ""

    /// The bell's count: what is unread, as Android's header badge counts.
    var attention: Int { notifications.unread }

    /// Android's header badge cadence: it only has to notice new trouble eventually.
    static let attentionEvery: Duration = .seconds(60)

    /// Nil when the profiles came back; the failure otherwise, for a sheet
    /// that wants to say so.
    @discardableResult
    func loadUsers(_ hub: HubClient) async -> HubFailure? {
        do {
            users = try await hub.fetch(HubEndpoints.users, as: UsersResponse.self).users
            return nil
        } catch {
            return error
        }
    }

    /// Asks for the notifications again, for the bell's count.
    func refreshAttention(_ app: AppModel) async {
        await notifications.refresh(app)
    }

    func refreshServices(_ hub: HubClient, address: String) async {
        guard let health = try? await hub.fetch(HubEndpoints.health, as: HealthResponse.self) else { return }
        servicesSummary = ServiceRows.summary(ServiceRows.rows(health: health, address: address)).text
    }
}

/// Sizes the shell is laid out by: the prototype's `.bar`, `.tabbar` and
/// `--px`, below the status bar rather than over it.
struct ShellMetrics {
    let wide: Bool
    let safe: EdgeInsets
    /// A phone turned sideways (`GlassMetrics.short`).
    var short = false
    /// An audiobook on the player, away from its own page: the mini player shows.
    var miniPlayer = false

    /// The round icons, the avatar and the back pill.
    var control: CGFloat { wide ? 44 : 40 }
    /// From the top of the safe area to the bar: below the status bar, and on
    /// the Mac below the band its hidden title bar leaves for the window buttons.
    var barTop: CGFloat { wide ? 8 : 2 }
    var margin: CGFloat { wide ? 44 : 20 }
    /// Where pages start, under the bar; they scroll up beneath it.
    var topInset: CGFloat { barTop + control + (wide ? 12 : 10) }
    /// A short window's bar is compact, as the system's is on a phone turned
    /// sideways: the words beside the icons.
    var tabBarHeight: CGFloat { short ? 46 : 66 }
    /// From the bottom of the screen: the prototype's 22 on an iPhone with a
    /// home indicator, never closer than 12.
    var tabBarBottom: CGFloat { max(safe.bottom - 12, 12) }
    /// The mini player's height (`ListeningMiniPlayer`).
    static let miniPlayerHeight: CGFloat = 56
    /// From the bottom of the screen to the mini player: over the tab bar on
    /// a phone, near the foot of the window on an iPad and the Mac.
    var miniPlayerBottom: CGFloat { wide ? max(safe.bottom, 16) : tabBarBottom + tabBarHeight + 8 }
    /// Room under the pages for the tab bar and the mini player, beyond the safe area.
    var bottomInset: CGFloat {
        let bars = wide ? 0 : max(0, tabBarBottom + tabBarHeight + 10 - safe.bottom)
        guard miniPlayer else { return bars }
        return max(bars, miniPlayerBottom + Self.miniPlayerHeight + 10 - safe.bottom)
    }
}

/// The Glass shell (GLASS_PLAN.md, "Navigation per device"): the page in
/// colour behind everything, each section's stack of pages, and the bars.
///
/// Wide windows (iPad, Mac) get the capsule of sections centred at the top,
/// Media/Books or the back pill on its left and the icons and avatar on its
/// right. Narrow ones (iPhone, an iPad mini in portrait) get the sections in
/// a tab bar at the bottom, Media/Books or a round back button at the top
/// left, and the bell and avatar at the top right. Pages scroll under both.
struct MainView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.scenePhase) private var scenePhase
    @SceneStorage("section") private var section: AppSection = .home
    @SceneStorage("side") private var side: AppSide = .media

    @Environment(\.horizontalSizeClass) private var sizeClass

    @State private var paths: [StackKey: [AppRoute]] = [:]
    @State private var opened: [StackKey] = []
    @State private var ambient = AmbientModel()
    @State private var shell = ShellModel()
    /// The player, over the whole window while something plays.
    @State private var player = PlayerModel()
    /// The Books side's lists, ways and orders (#25).
    @State private var books = BooksModel()
    /// The accent each side wears, chosen in Settings (#38).
    @State private var accents = AccentModel.shared
    /// A book being read, over the whole window (#25 phases 3 and 4).
    @State private var reading: ReadRequest?
    /// Counts the readers closed, so pages read their progress again.
    @State private var readersClosed = 0
    /// The audiobook player, one for the app (#25 phase 2).
    @State private var listening = ListeningModel.shared
    /// One sound at a time: the video and an audiobook.
    @State private var sounds = SoundGuard.shared
    @State private var profilesOpen = false
    @State private var sheetPlaces = false
    /// The Mac's window buttons sit over the page under its hidden title bar:
    /// how far across and down they reach, so Media/Books keeps clear of them.
    @State private var windowButtons = CGSize.zero
    /// Debug builds: HUB_SHEET=profiles opens the avatar's sheet at launch.
    @State private var debugSheet = false
    /// Debug builds: HUB_PLAY=<item id> opens the player at launch.
    @State private var debugPlay = ""
    /// Debug builds: HUB_TITLE=<item id>[|subtitles|removal] opens that library title, and on from it, on the first section's stack (#34).
    @State private var debugTitle = ""
    /// Debug builds: HUB_DOWNLOAD=<item id> downloads that film or episode at launch, and
    /// HUB_DOWNLOAD=remove:<item id> takes it off this device (#5).
    @State private var debugDownload = ""

    private var key: StackKey { StackKey(side: side, section: section) }
    /// Something over the pages and bars: the player or a reader.
    private var covered: Bool { player.isOpen || reading != nil }
    /// The book playing, everywhere but on its own page and under the player or a reader.
    private var showsMiniPlayer: Bool {
        guard let book = listening.book, !covered else { return false }
        if case .listen(let route)? = pages.last, book.isSame(workId: route.workId, sourceItemId: route.sourceItemId) {
            return false
        }
        return true
    }
    private var pages: [AppRoute] { paths[key] ?? [] }

    var body: some View {
        GeometryReader { proxy in
            let metrics = ShellMetrics(wide: ShellLayout.isWide(width: proxy.size.width), safe: proxy.safeAreaInsets,
                                       short: ShellLayout.isShort(height: proxy.size.height + proxy.safeAreaInsets.top
                                                                  + proxy.safeAreaInsets.bottom),
                                       miniPlayer: showsMiniPlayer)
            ZStack(alignment: .top) {
                AmbientBackground(path: ambient.displayed, palette: model.colors.palette(for: ambient.displayed))
                    .ignoresSafeArea()
                ForEach(opened, id: \.self) { stackKey in
                    let shown = stackKey == key
                    stack(stackKey, metrics: metrics)
                        // The section shown fades in and settles, the prototype's
                        // `fadein`; the one left goes at once, so two pages never
                        // show through each other.
                        .opacity(shown ? 1 : 0)
                        .offset(y: shown ? 0 : 10)
                        .animation(shown ? .easeOut(duration: 0.28) : nil, value: shown)
                        .allowsHitTesting(shown)
                        // A section out of sight keeps its pages but not its
                        // keyboard shortcuts.
                        .disabled(!shown || covered)
                        .accessibilityHidden(!shown || covered)
                }
                topBar(metrics)
                    .accessibilityHidden(covered)
                if !metrics.wide {
                    ShellTabBar(section: section, short: metrics.short, select: select)
                        // An iPad mini in portrait is wider than a phone: the
                        // bar keeps a phone's proportions, centred.
                        .frame(maxWidth: 520)
                        .padding(.horizontal, 14)
                        .padding(.bottom, metrics.tabBarBottom)
                        .frame(maxHeight: .infinity, alignment: .bottom)
                        .ignoresSafeArea(edges: .bottom)
                        .accessibilityHidden(covered)
                }
                if showsMiniPlayer {
                    ListeningMiniPlayer(open: openListening)
                        .padding(.horizontal, 14)
                        .padding(.bottom, metrics.miniPlayerBottom)
                        .frame(maxHeight: .infinity, alignment: .bottom)
                        .ignoresSafeArea(edges: .bottom)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
                // Over the bars too; the pages under it keep their places.
                if player.isOpen {
                    PlayerView(player: player)
                        .transition(.opacity)
                        .zIndex(1)
                }
                if let reading, !player.isOpen {
                    ReaderHost(request: reading)
                        .transition(.opacity)
                        .zIndex(2)
                }
            }
            .animation(.easeOut(duration: 0.25), value: player.isOpen)
            .animation(.easeOut(duration: 0.25), value: showsMiniPlayer)
            .animation(.easeOut(duration: 0.25), value: reading?.id)
            #if DEBUG && os(macOS)
            .onChange(of: windowButtons) { _, _ in
                let line = "safe area top \(metrics.safe.top), bar from \(metrics.safe.top + metrics.barTop), "
                    + "media/books moved right by \(windowButtonsInset(metrics))\n"
                FileHandle.standardError.write(Data(line.utf8))
            }
            #endif
            #if DEBUG
            .task(id: debugPlay) {
                let itemId = debugPlay
                guard !itemId.isEmpty else { return }
                // Once Home has had a moment to settle under it. Layout can
                // start this twice; only the first still finds the id.
                try? await Task.sleep(for: .milliseconds(800))
                guard !Task.isCancelled, debugPlay == itemId else { return }
                debugPlay = ""
                player.open(PlayRequest(itemId: itemId), app: model)
            }
            .task(id: debugDownload) {
                let itemId = debugDownload
                guard !itemId.isEmpty else { return }
                // Once the downloads are attached to the hub.
                try? await Task.sleep(for: .milliseconds(1500))
                guard !Task.isCancelled, debugDownload == itemId else { return }
                debugDownload = ""
                // Its own task: clearing the id ends this one.
                let hub = model.hub
                Task {
                    // "remove:<id>" takes it off this device again.
                    if itemId.hasPrefix("remove:") {
                        let target = String(itemId.dropFirst("remove:".count))
                        if let row = OfflineLibrary.shared.row(forItem: target) { OfflineLibrary.shared.remove(row.id) }
                        NSLog("offline: debug removal of %@", target)
                        return
                    }
                    let item = try? await hub.fetch(HubEndpoints.libraryItem(itemId), as: LibraryItemResponse.self).item
                    let title = item.map { $0.seriesTitle.isEmpty ? $0.title : $0.seriesTitle } ?? "Download"
                    let problem = await OfflineLibrary.shared.download(itemIds: [itemId], title: title, seriesId: item?.seriesId ?? "")
                    NSLog("offline: debug download of %@: %@", itemId, problem ?? "queued")
                }
            }
            .task(id: debugTitle) {
                let itemId = debugTitle
                guard !itemId.isEmpty else { return }
                // Once the section has settled under it.
                try? await Task.sleep(for: .milliseconds(500))
                guard !Task.isCancelled, debugTitle == itemId else { return }
                debugTitle = ""
                // "<id>|subtitles" or "<id>|removal" goes on to that page.
                let parts = itemId.split(separator: "|", maxSplits: 1).map(String.init)
                var pages: [AppRoute] = [.title(TitleRoute(itemId: parts[0], title: "Title"))]
                switch parts.count > 1 ? parts[1] : "" {
                case "subtitles": pages.append(.subtitles(SubtitlesRoute(itemId: parts[0], title: "Bleach · S1E2 · A Second Look")))
                case "removal": pages.append(.removal(RemovalRoute(kind: "video", id: parts[0], title: "Bleach")))
                default: break
                }
                paths[key, default: []].append(contentsOf: pages)
            }
            .task(id: debugSheet) {
                guard debugSheet else { return }
                // After the first layout settles, so the sheet is the one for
                // this width.
                try? await Task.sleep(for: .milliseconds(600))
                debugSheet = false
                openProfiles(wide: metrics.wide)
            }
            #endif
        }
        // The keyboard rises over the tab bar, as it does over the system's.
        .ignoresSafeArea(.keyboard)
        .environment(ambient)
        .environment(\.play, PlayAction { request in
            sounds.started(.video)
            player.open(request, app: model)
        })
        .environment(\.playbackClosed, player.closedCount)
        .environment(books)
        .environment(shell.notifications)
        .environment(accents)
        .environment(\.appSide, side)
        .environment(\.read, ReadAction(open: { request in reading = request }, close: {
            reading = nil
            readersClosed += 1
        }))
        .environment(\.readerClosed, readersClosed)
        // A book marked unread starts again; a place kept forgets the mark (#37).
        .environment(\.readingMarks, ReadingMarks(startsFresh: { [books] in books.completion.startsAtBeginning($0) },
                                                   kept: { [books] in books.readerKept($0) }))
        .environment(\.selectSection, SelectSectionAction { target in select(target) })
        .onChange(of: "\(model.address)\u{0}\(model.userId)", initial: true) { _, _ in
            books.use(address: model.address, userId: model.userId)
            accents.use(address: model.address, userId: model.userId, demo: model.isDemo)
            HomeLayoutModel.shared.use(demo: model.isDemo)
        }
        // Listening places a closed app left unsent go now, and again for
        // another profile; a book of another profile leaves the player.
        .task(id: "\(model.address)\u{0}\(model.userId)") { await listening.flushPending(app: model) }
        // Downloads (#5): the transfers a previous launch left are found again,
        // the queue goes on for this profile, and watches made offline are sent.
        .task(id: "\(model.address)\u{0}\(model.userId)\u{0}\(model.connectionChanges)") {
            OfflineLibrary.shared.attach(model)
        }
        .onAppear {
            sounds.pause(.video) { [player] in if player.isPlaying { player.togglePlay() } }
            sounds.pause(.audiobook) { ListeningModel.shared.pause() }
            listening.onStart = { [sounds] in sounds.started(.audiobook) }
            listening.onKept = { [books] workId in books.readerKept(workId) }
        }
        .onChange(of: player.isPlaying) { _, playing in playing ? sounds.started(.video) : sounds.stopped(.video) }
        #if DEBUG
        .overlay(alignment: .bottomLeading) {
            if ProcessInfo.processInfo.environment["HUB_DEBUG_NOW_PLAYING"] == "1" {
                Text(NowPlaying.shared.summary)
                    .font(.caption2)
                    .opacity(0.02)
                    .allowsHitTesting(false)
                    .accessibilityIdentifier("debug-now-playing")
            }
        }
        #endif
        .onChange(of: listening.playing) { _, playing in playing ? sounds.started(.audiobook) : sounds.stopped(.audiobook) }
        .environment(\.glassPalette, model.colors.palette(for: ambient.displayed))
        .environment(\.glassAccent, accents.accent(side))
        .onChange(of: key, initial: true) { _, latest in open(latest) }
        .onChange(of: ambient.displayed, initial: true) { _, path in model.colors.want([path]) }
        // Closing the window (the Mac's, or an iPad's in the app switcher)
        // ends what plays in it, as Back would; minimising it does not.
        .onDisappear { player.close() }
        .task(id: model.userId) { await shell.loadUsers(model.hub) }
        .task(id: "\(scenePhase == .active)·\(model.address)·\(model.connectionChanges)") {
            guard scenePhase == .active else { return }
            while !Task.isCancelled {
                await shell.refreshAttention(model)
                try? await Task.sleep(for: ShellModel.attentionEvery)
            }
        }
        .sheet(isPresented: $profilesOpen) {
            // A sheet takes the environment from where it is attached, which is
            // outside the values set above.
            AccountSheet(shell: shell, places: sheetPlaces) { place in
                profilesOpen = false
                select(place)
            }
            .environment(\.glassAccent, accents.accent(side))
            .environment(\.glassPalette, model.colors.palette(for: ambient.displayed))
        }
        #if DEBUG
        .onAppear(perform: applyDebugLaunch)
        #endif
    }

    // MARK: Pages

    private func stack(_ stackKey: StackKey, metrics: ShellMetrics) -> some View {
        NavigationStack(path: Binding(get: { paths[stackKey] ?? [] }, set: { paths[stackKey] = $0 })) {
            root(stackKey)
                .shellPage(metrics)
                #if os(iOS)
                .background { SwipeBack() }
                #endif
                .navigationDestination(for: AppRoute.self) { route in
                    destination(route).shellPage(metrics)
                }
        }
        .environment(\.shellStack, stackKey.id)
        .environment(\.glassMetrics, GlassMetrics(compact: sizeClass == .compact, short: metrics.short,
                                                  margin: metrics.margin))
        .environment(\.openRoute, OpenRouteAction(
            push: { route in paths[stackKey, default: []].append(route) },
            replace: { route in
                guard var path = paths[stackKey], !path.isEmpty else { return }
                path[path.count - 1] = route
                var swap = Transaction()
                swap.disablesAnimations = true
                withTransaction(swap) { paths[stackKey] = path }
            },
            pop: { count in
                guard var path = paths[stackKey], !path.isEmpty else { return }
                path.removeLast(min(max(count, 0), path.count))
                paths[stackKey] = path
            }))
    }

    @ViewBuilder private func root(_ stackKey: StackKey) -> some View {
        switch (stackKey.side, stackKey.section) {
        case (.media?, .home): HomeView()
        case (.media?, .discover): DiscoverView()
        case (.media?, .library): LibraryView()
        case (.media?, .activity): ActivityView()
        case (.media?, .downloads): DownloadsView()
        case (.books?, .home): BooksHomeView()
        case (.books?, .discover): BooksDiscoverView()
        case (.books?, .library): BooksLibraryView()
        case (.books?, .activity): BooksActivityView()
        case (_, .notifications): NotificationsView()
        case (_, .services): ServicesView()
        case (_, .settings): SettingsView()
        default: ComingNextView(side: stackKey.side, section: stackKey.section)
        }
    }

    @ViewBuilder private func destination(_ route: AppRoute) -> some View {
        switch route {
        case .title(let title): TitleView(route: title)
        case .folder(let folder): FolderView(route: folder)
        case .monitor: ServerMonitorView()
        case .media(let media): MediaTitleView(route: media)
        case .person(let person): PersonView(route: person)
        case .releaseTargets(let targets): ReleaseTargetsView(route: targets)
        case .releases(let releases): ReleasesView(route: releases)
        case .readingLibrary(let library): ReadingLibraryView(route: library)
        case .book(let book): BookView(route: book)
        case .author(let author): AuthorView(route: author)
        case .missingBook(let missing): MissingBookView(route: missing)
        case .bookRequest(let request): BookRequestView(route: request)
        case .readingReleases(let releases): ReadingReleasesView(route: releases)
        case .listen(let listen): AudiobookView(workId: listen.workId, sourceItemId: listen.sourceItemId, title: listen.title)
        case .readingLists(let lists): ReadingListsView(route: lists)
        case .transfers(let transfers): TransfersView(route: transfers)
        case .speedLimits: SpeedLimitsView()
        case .licence(let licence): LicenceView(route: licence)
        case .subtitles(let subtitles): SubtitlesView(route: subtitles)
        case .removal(let removal): RemovalView(route: removal)
        case .offlineTitle(let offline): OfflineTitleView(route: offline)
        case .offlinePicker(let picker): OfflinePickerView(route: picker)
        case .offlineQueue: DownloadsView(startOn: .queue)
        }
    }

    // MARK: Bars

    @ViewBuilder private func topBar(_ metrics: ShellMetrics) -> some View {
        let back = pages.isEmpty ? nil : ShellLayout.backTitle(pages: pages.map(\.name), root: section.title)
        VStack(spacing: 0) {
            Group {
                if metrics.wide {
                    WideBar(side: $side, section: section, backTitle: back, attention: shell.attention,
                            avatar: avatar, leadingInset: windowButtonsInset(metrics),
                            select: select, back: goBack, openProfiles: { openProfiles(wide: true) })
                } else {
                    PhoneBar(side: $side, section: section, backTitle: back, attention: shell.attention,
                             avatar: avatar, select: select, back: goBack, openProfiles: { openProfiles(wide: false) })
                }
            }
            .padding(.horizontal, metrics.margin)
            .padding(.top, metrics.barTop)
            Spacer(minLength: 0)
        }
        .background(alignment: .top) {
            ZStack(alignment: .top) {
                // Keeps the bar readable over whatever scrolls beneath it.
                LinearGradient(colors: [.black.opacity(0.42), .clear], startPoint: .top, endPoint: .bottom)
                    .frame(height: metrics.safe.top + metrics.barTop + metrics.control + 24)
                    .allowsHitTesting(false)
                #if os(macOS)
                // With the title bar hidden, the band behind the bar is where the
                // window is dragged from, and the window buttons are measured.
                Color.clear
                    .frame(height: metrics.safe.top + metrics.topInset)
                    .contentShape(Rectangle())
                    .gesture(WindowDragGesture())
                    .allowsWindowActivationEvents(true)
                    .background { WindowButtonsReader(corner: $windowButtons) }
                #endif
            }
            .ignoresSafeArea(edges: .top)
        }
    }

    /// Room before Media/Books on the Mac: only when the bar reaches up beside
    /// the window buttons. Below them it needs none.
    private func windowButtonsInset(_ metrics: ShellMetrics) -> CGFloat {
        guard metrics.safe.top + metrics.barTop < windowButtons.height + 6 else { return 0 }
        return max(0, windowButtons.width + 14 - metrics.margin)
    }

    private var avatar: AvatarLook {
        let current = Profiles.current(shell.users, chosen: model.userId)
        let name = current?.name ?? model.userName
        let color = current.flatMap { Profiles.color(of: $0.id, in: shell.users) }
        // No name yet: the glass circle keeps its person glyph rather than "?".
        return AvatarLook(name: name, initial: name.isEmpty ? "" : Profiles.initial(name),
                          color: color.map(Color.init(argb:)))
    }

    // MARK: Moving about

    private func open(_ stackKey: StackKey) {
        if !opened.contains(stackKey) {
            // A section's first visit fades in as a revisit does.
            withAnimation(.easeOut(duration: 0.28)) { opened.append(stackKey) }
        }
        ambient.select(stackKey.id)
    }

    /// A section's own button again goes back to its first page, as the
    /// prototype's tabs do.
    private func select(_ target: AppSection) {
        if target == section {
            paths[key] = []
        } else {
            section = target
        }
    }

    /// The mini player: the book's own page, on top of wherever you are.
    private func openListening() {
        guard let book = listening.book else { return }
        paths[key, default: []].append(.listen(ListenRoute(workId: book.workId, sourceItemId: book.sourceItemId,
                                                           title: book.title)))
    }

    private func goBack() {
        guard var path = paths[key], !path.isEmpty else { return }
        path.removeLast()
        paths[key] = path
    }

    private func openProfiles(wide: Bool) {
        sheetPlaces = !wide
        profilesOpen = true
    }

    #if DEBUG
    /// scripts/mac.sh opens a chosen place for screenshots: HUB_SECTION=library,
    /// HUB_SIDE=books, HUB_SHEET=profiles, HUB_PLAY=<item id>, HUB_TITLE=<item id>. (HUB_OPEN is Home's.)
    private func applyDebugLaunch() {
        let environment = ProcessInfo.processInfo.environment
        if let name = environment["HUB_SECTION"], let chosen = AppSection(rawValue: name) { section = chosen }
        if let name = environment["HUB_SIDE"], let chosen = AppSide(rawValue: name) { side = chosen }
        if environment["HUB_SHEET"] == "profiles" { debugSheet = true }
        if let itemId = environment["HUB_PLAY"], !itemId.isEmpty { debugPlay = itemId }
        if let itemId = environment["HUB_TITLE"], !itemId.isEmpty { debugTitle = itemId }
        if let itemId = environment["HUB_DOWNLOAD"], !itemId.isEmpty { debugDownload = itemId }
    }
    #endif
}

/// Every page in the shell: clear, so the Glass page shows through (a
/// navigation stack otherwise paints the system background behind each page),
/// without the system's own bar, because the shell draws the back pill, the
/// sections and the icons itself, and with room for those bars, which it
/// scrolls under.
///
/// The room is made on each page rather than once around the stack: a
/// navigation stack hosts its pages in UIKit, which does not carry a SwiftUI
/// inset across.
struct ShellPage: ViewModifier {
    let metrics: ShellMetrics

    func body(content: Content) -> some View {
        content
            .safeAreaInset(edge: .top, spacing: 0) { Color.clear.frame(height: metrics.topInset) }
            .safeAreaInset(edge: .bottom, spacing: 0) { Color.clear.frame(height: metrics.bottomInset) }
            #if os(iOS)
            // The Mac's stack paints nothing behind its pages (and has no
            // such placement), so this is the iPad's and iPhone's alone.
            .containerBackground(.clear, for: .navigation)
            .toolbar(.hidden, for: .navigationBar)
            #else
            .toolbar(.hidden, for: .windowToolbar)
            .navigationBarBackButtonHidden(true)
            #endif
    }
}

extension View {
    func shellPage(_ metrics: ShellMetrics) -> some View { modifier(ShellPage(metrics: metrics)) }
}

#if os(iOS)
/// Hiding the system's navigation bar also turns off UIKit's swipe from the
/// left edge to go back. This puts it back for every page above a stack's
/// first: it sits behind the first page and answers for its stack's gesture.
struct SwipeBack: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> Probe { Probe() }
    func updateUIViewController(_ controller: Probe, context: Context) {}

    final class Probe: UIViewController, UIGestureRecognizerDelegate {
        override func viewDidAppear(_ animated: Bool) {
            super.viewDidAppear(animated)
            navigationController?.interactivePopGestureRecognizer?.delegate = self
        }

        func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
            (navigationController?.viewControllers.count ?? 0) > 1
        }
    }
}
#endif

#if os(macOS)
/// Where the Mac window's close, minimise and zoom buttons are, measured from
/// the window's top left: how far across they end and how far down. With the
/// title bar hidden they sit over the page, and Media/Books keeps clear of
/// them. Debug builds print what they found, since the Mac's screen cannot be
/// captured from the PC that builds it.
struct WindowButtonsReader: NSViewRepresentable {
    @Binding var corner: CGSize

    func makeNSView(context: Context) -> Probe { Probe { corner = $0 } }
    func updateNSView(_ view: Probe, context: Context) {}

    final class Probe: NSView {
        let report: (CGSize) -> Void

        init(report: @escaping (CGSize) -> Void) {
            self.report = report
            super.init(frame: .zero)
        }

        required init?(coder: NSCoder) { nil }

        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            guard let window, let zoom = window.standardWindowButton(.zoomButton) else { return }
            let frame = zoom.convert(zoom.bounds, to: nil)
            let height = window.contentView?.bounds.height ?? 0
            let corner = CGSize(width: frame.maxX, height: height - frame.minY)
            #if DEBUG
            // Standard error, which is not buffered: scripts/mac.sh keeps it in a file.
            let layout = window.contentLayoutRect
            let line = "window buttons end at x \(frame.maxX), from \(height - frame.maxY) to \(corner.height) "
                + "below the top; content below the title bar starts \(height - layout.maxY) down\n"
            FileHandle.standardError.write(Data(line.utf8))
            #endif
            let report = report
            DispatchQueue.main.async { report(corner) }
        }
    }
}
#endif
