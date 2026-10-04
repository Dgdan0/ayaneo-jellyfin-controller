import HubKit
import SwiftUI

/// Home: a hero for one title over rows of cards, for the chosen Jellyfin
/// profile. Android's `screens/home/HomeScreen` with `HomeHero`; touch-first,
/// so a tap opens a title and a pointer resting on a card (or focus on it)
/// shows it in the hero, and its artwork becomes the Glass page.
struct HomeView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openRoute) private var openRoute

    @State private var rows: [HomeRow] = []
    @State private var status = StatusMessage("")
    @State private var loading = false
    @State private var selection: HeroPick?
    /// Item details by id: the runtime and certification a Home card lacks.
    @State private var details: [String: HubKit.LibraryItem] = [:]
    /// Debug builds: scripts/mac.sh opens a row's first title for screenshots
    /// (HUB_OPEN=latest), once.
    @State private var debugOpened = false

    private var hero: HeroContent? {
        guard let pick = selection ?? firstPick else { return nil }
        return HomeHero.from(rowId: pick.rowId, rowTitle: pick.rowTitle, hit: pick.hit,
                             detail: details[pick.hit.jellyfinItemId])
    }

    private var firstPick: HeroPick? {
        rows.first.flatMap { row in row.items.first.map { HeroPick(rowId: row.id, rowTitle: row.title, hit: $0) } }
    }

    var body: some View {
        GeometryReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    if let hero {
                        HeroView(content: hero, topInset: proxy.safeAreaInsets.top) {
                            status = StatusMessage("Playing on Apple devices comes next")
                        }
                    } else {
                        Color.clear.frame(height: proxy.safeAreaInsets.top)
                    }
                    StatusLine(message: status) { Task { await load() } }
                        .padding(.horizontal, 24)
                    ForEach(rows) { row in
                        HomeRowView(row: row) { hit in
                            selection = HeroPick(rowId: row.id, rowTitle: row.title, hit: hit)
                        }
                    }
                }
                .padding(.bottom, 24)
            }
            .ignoresSafeArea(edges: .top)
        }
        .ambientArtwork(hero?.backdrop ?? "")
        .refreshable { await load() }
        // A new profile is a new Home: everything reloads under its name.
        .task(id: model.userId) {
            details = [:]
            selection = nil
            await load()
        }
        .task(id: hero?.itemId) { await loadHeroDetail() }
    }

    private func load() async {
        guard !loading else { return }
        loading = true
        defer { loading = false }
        status = StatusText.loading("Home", refreshing: !rows.isEmpty)
        do {
            let home = try await model.hub.fetch(HubEndpoints.home, as: HomeResponse.self)
            rows = HomeHero.ordered(home.rows)
            // Any card can become the hero, so ask for every hero's colours
            // now: the page re-tints the moment a card is chosen.
            model.colors.want(rows.flatMap { row in
                row.items.map { HomeHero.from(rowId: row.id, rowTitle: row.title, hit: $0).backdrop }
            })
            #if DEBUG
            if let rowId = ProcessInfo.processInfo.environment["HUB_OPEN"], !debugOpened,
               let hit = rows.first(where: { $0.id == rowId })?.items.first {
                debugOpened = true
                openRoute(.title(TitleRoute(itemId: hit.jellyfinItemId, title: hit.media.title)))
            }
            #endif
            let unavailable = home.partial.map(\.service)
            status = rows.isEmpty && unavailable.isEmpty
                ? StatusMessage("Nothing to continue or show yet.")
                : StatusText.caveat(home.cache, unavailable: unavailable)
        } catch {
            if error.kind == .cancelled { return }
            status = StatusText.failed(error.message, kind: error.kind, hasData: !rows.isEmpty)
        }
    }

    /// Waits for the hero to settle on a title before asking for its details,
    /// as Android waits for focus to rest.
    private func loadHeroDetail() async {
        guard let id = hero?.itemId, !id.isEmpty, details[id] == nil else { return }
        try? await Task.sleep(for: .milliseconds(220))
        guard !Task.isCancelled,
              let response = try? await model.hub.fetch(HubEndpoints.libraryItem(id), as: LibraryItemResponse.self)
        else { return }
        details[id] = response.item
    }
}

/// The card the hero is showing and the row it came from.
struct HeroPick: Equatable {
    let rowId: String
    let rowTitle: String
    let hit: MediaHit
}

/// The top of Home (Android `HomeHeroView`): the title's backdrop fading into
/// the page, an accent eyebrow, the name, its facts, how much is left, then
/// Resume or Play and Details. Every line keeps its place, so nothing jumps
/// when the hero changes title.
struct HeroView: View {
    let content: HeroContent
    let topInset: CGFloat
    let play: () -> Void
    @Environment(\.horizontalSizeClass) private var sizeClass

    var body: some View {
        BackdropHeader(path: content.backdrop, topInset: topInset) {
            VStack(alignment: .leading, spacing: 8) {
                Text(content.eyebrow.isEmpty ? " " : content.eyebrow)
                    .font(HubType.body(13, weight: .bold, relativeTo: .caption))
                    .tracking(1.2)
                    .foregroundStyle(Color.accentColor)
                Text(content.title)
                    .font(HubType.heading(sizeClass == .compact ? 32 : 44, weight: .heavy))
                    .foregroundStyle(Color.ink)
                    .lineLimit(2)
                    .minimumScaleFactor(0.7)
                Text(content.meta.isEmpty ? " " : content.meta.joined(separator: "  ·  "))
                    .font(HubType.body(15, relativeTo: .subheadline))
                    .foregroundStyle(Color.muted)
                    .lineLimit(1)
                // Held open for an unstarted title, so the buttons never move.
                HStack(spacing: 12) {
                    ProgressView(value: content.progress)
                        .tint(Color.accentColor)
                        .frame(width: 180)
                    Text(content.progressLabel)
                        .font(HubType.body(14, relativeTo: .caption))
                        .foregroundStyle(Color.muted)
                }
                .opacity(content.progress > 0 ? 1 : 0)
                HStack(spacing: 12) {
                    if content.canPlay {
                        Button(action: play) {
                            Label(content.playLabel, systemImage: "play.fill")
                        }
                        .buttonStyle(PrimaryPillStyle())
                    }
                    if !content.itemId.isEmpty {
                        NavigationLink(value: AppRoute.title(TitleRoute(itemId: content.itemId, title: content.title))) {
                            Label("Details", systemImage: "info.circle")
                        }
                        .buttonStyle(GlassPillStyle())
                    }
                }
                .padding(.top, 6)
            }
            .frame(maxWidth: 620, alignment: .leading)
        }
        .animation(.easeInOut(duration: 0.2), value: content.itemId)
    }
}

/// One Home row: its title, then the cards side by side.
struct HomeRowView: View {
    let row: HomeRow
    let preview: (MediaHit) -> Void

    private var landscape: Bool { HomeHero.isLandscape(rowId: row.id) }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(row.title)
                .font(HubType.heading(22, weight: .semibold, relativeTo: .title2))
                .foregroundStyle(Color.ink)
                .padding(.horizontal, 24)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: 14) {
                    ForEach(row.items) { hit in
                        NavigationLink(value: AppRoute.title(TitleRoute(itemId: hit.jellyfinItemId, title: hit.media.title))) {
                            if landscape {
                                LandscapeCard(hit: hit).frame(width: 260)
                            } else {
                                PosterCard(hit: hit, caption: false).frame(width: 120)
                            }
                        }
                        .buttonStyle(.plain)
                        .disabled(hit.jellyfinItemId.isEmpty)
                        .previewsWhenFocused { preview(hit) }
                    }
                }
                .padding(.horizontal, 24)
            }
        }
    }
}

/// A pointer resting on a card, or keyboard or controller focus on it, shows
/// it: in the hero, and through it on the Glass page (GLASS_PLAN.md, "On the
/// iPad a resting pointer counts as focus").
struct PreviewsWhenFocused: ViewModifier {
    let preview: () -> Void
    @FocusState private var focused: Bool

    func body(content: Content) -> some View {
        content
            .focused($focused)
            .onHover { inside in if inside { preview() } }
            .onChange(of: focused) { _, now in if now { preview() } }
    }
}

extension View {
    func previewsWhenFocused(_ preview: @escaping () -> Void) -> some View {
        modifier(PreviewsWhenFocused(preview: preview))
    }
}
