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

    /// What the back pill calls this page from the one above it.
    var name: String {
        switch self {
        case .title(let route): route.title
        case .folder(let route): route.name
        case .monitor: "Server monitor"
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

    @MainActor func callAsFunction(_ route: AppRoute) { push(route) }
}

extension EnvironmentValues {
    @Entry var openRoute = OpenRouteAction()
}

/// What the shell shows besides the pages: the profiles, the bell's count
/// and the services' one-line summary for the iPhone's sheet.
@MainActor
@Observable
final class ShellModel {
    private(set) var users: [HubUser] = []
    private(set) var attention = 0
    private(set) var servicesSummary = ""

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

    /// The hub's own count of what needs attention: services it cannot reach,
    /// and active warnings and errors.
    func refreshAttention(_ hub: HubClient) async {
        guard let response = try? await hub.fetch(HubEndpoints.notifications(), as: NotificationsResponse.self) else { return }
        attention = response.attentionCount
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
    /// Room under the pages for the tab bar, beyond the safe area.
    var bottomInset: CGFloat { wide ? 0 : max(0, tabBarBottom + tabBarHeight + 10 - safe.bottom) }
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
    @State private var profilesOpen = false
    @State private var sheetPlaces = false
    /// The Mac's window buttons sit over the page under its hidden title bar:
    /// how far across and down they reach, so Media/Books keeps clear of them.
    @State private var windowButtons = CGSize.zero
    /// Debug builds: HUB_SHEET=profiles opens the avatar's sheet at launch.
    @State private var debugSheet = false
    /// Debug builds: HUB_PLAY=<item id> opens the player at launch.
    @State private var debugPlay = ""

    private var key: StackKey { StackKey(side: side, section: section) }
    private var pages: [AppRoute] { paths[key] ?? [] }

    var body: some View {
        GeometryReader { proxy in
            let metrics = ShellMetrics(wide: ShellLayout.isWide(width: proxy.size.width), safe: proxy.safeAreaInsets,
                                       short: ShellLayout.isShort(height: proxy.size.height + proxy.safeAreaInsets.top
                                                                  + proxy.safeAreaInsets.bottom))
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
                        .disabled(!shown || player.isOpen)
                        .accessibilityHidden(!shown || player.isOpen)
                }
                topBar(metrics)
                    .accessibilityHidden(player.isOpen)
                if !metrics.wide {
                    ShellTabBar(section: section, short: metrics.short, select: select)
                        // An iPad mini in portrait is wider than a phone: the
                        // bar keeps a phone's proportions, centred.
                        .frame(maxWidth: 520)
                        .padding(.horizontal, 14)
                        .padding(.bottom, metrics.tabBarBottom)
                        .frame(maxHeight: .infinity, alignment: .bottom)
                        .ignoresSafeArea(edges: .bottom)
                        .accessibilityHidden(player.isOpen)
                }
                // Over the bars too; the pages under it keep their places.
                if player.isOpen {
                    PlayerView(player: player)
                        .transition(.opacity)
                        .zIndex(1)
                }
            }
            .animation(.easeOut(duration: 0.25), value: player.isOpen)
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
        .environment(\.play, PlayAction { request in player.open(request, app: model) })
        .environment(\.playbackClosed, player.closedCount)
        .environment(\.glassPalette, model.colors.palette(for: ambient.displayed))
        .environment(\.glassAccent, AccentPreset.defaultFor(side))
        .onChange(of: key, initial: true) { _, latest in open(latest) }
        .onChange(of: ambient.displayed, initial: true) { _, path in model.colors.want([path]) }
        // Closing the window (the Mac's, or an iPad's in the app switcher)
        // ends what plays in it, as Back would; minimising it does not.
        .onDisappear { player.close() }
        .task(id: model.userId) { await shell.loadUsers(model.hub) }
        .task(id: scenePhase == .active) {
            guard scenePhase == .active else { return }
            while !Task.isCancelled {
                await shell.refreshAttention(model.hub)
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
            .environment(\.glassAccent, AccentPreset.defaultFor(side))
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
            }))
    }

    @ViewBuilder private func root(_ stackKey: StackKey) -> some View {
        switch (stackKey.side, stackKey.section) {
        case (.media?, .home): HomeView()
        case (.media?, .library): LibraryView()
        case (_, .services): ServicesView()
        default: ComingNextView(side: stackKey.side, section: stackKey.section)
        }
    }

    @ViewBuilder private func destination(_ route: AppRoute) -> some View {
        switch route {
        case .title(let title): TitleView(route: title)
        case .folder(let folder): FolderView(route: folder)
        case .monitor: ComingNextView(title: "Server monitor", systemImage: "cpu",
                                      detail: "CPU, memory, disk space, containers and current playback.")
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
    /// HUB_SIDE=books, HUB_SHEET=profiles, HUB_PLAY=<item id>. (HUB_OPEN is Home's.)
    private func applyDebugLaunch() {
        let environment = ProcessInfo.processInfo.environment
        if let name = environment["HUB_SECTION"], let chosen = AppSection(rawValue: name) { section = chosen }
        if let name = environment["HUB_SIDE"], let chosen = AppSide(rawValue: name) { side = chosen }
        if environment["HUB_SHEET"] == "profiles" { debugSheet = true }
        if let itemId = environment["HUB_PLAY"], !itemId.isEmpty { debugPlay = itemId }
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
