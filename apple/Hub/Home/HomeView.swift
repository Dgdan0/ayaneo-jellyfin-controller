import HubKit
import SwiftUI

/// Home: a hero for one title over rows of cards, for the chosen Jellyfin
/// profile. Android's `screens/home/HomeScreen` with `HomeHero`; touch-first,
/// so a tap opens a title and a pointer resting on a card (or focus on it)
/// shows it in the hero, and its artwork becomes the Glass page.
struct HomeView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openRoute) private var openRoute
    @Environment(\.glassMetrics) private var metrics
    @Environment(\.play) private var play
    @Environment(\.playbackClosed) private var playbackClosed

    @State private var rows: [HomeRow] = []
    /// The profile the rows belong to.
    @State private var rowsOwner: String?
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
                VStack(alignment: .leading, spacing: 0) {
                    if let hero {
                        HeroView(content: hero, topInset: proxy.safeAreaInsets.top,
                                 visibleHeight: proxy.size.height + proxy.safeAreaInsets.top) {
                            // As Android's Home: the hub decides where it starts, and
                            // a series plays its part-watched, next or first episode.
                            play(PlayRequest(itemId: hero.itemId, series: hero.type == "series",
                                             title: hero.title, backdrop: hero.backdrop))
                        }
                    } else {
                        Color.clear.frame(height: proxy.safeAreaInsets.top)
                    }
                    StatusLine(message: status) { Task { await load() } }
                        .padding(.horizontal, metrics.margin)
                        .padding(.top, 8)
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
            // Another profile's rows are not this one's to show while it loads.
            if let owner = rowsOwner, owner != model.userId { rows = [] }
            rowsOwner = model.userId
            await load()
        }
        .task(id: hero?.itemId) { await loadHeroDetail() }
        // Back from the player: the rows read again in place, the hero on the
        // same card with its new progress.
        .onChange(of: playbackClosed) { _, _ in
            Task {
                await load()
                if let pick = selection {
                    selection = rows.first { $0.id == pick.rowId }
                        .flatMap { row in
                            row.items.first { $0.id == pick.hit.id }
                                .map { HeroPick(rowId: row.id, rowTitle: row.title, hit: $0) }
                        }
                }
            }
        }
    }

    private func load() async {
        guard !loading else { return }
        loading = true
        defer { loading = false }
        status = StatusText.loading("Home", refreshing: !rows.isEmpty)
        // The last answer for this profile first, while the new one is asked for (#38).
        if rows.isEmpty, let kept = await model.hub.lastAnswer(HubEndpoints.home, as: HomeResponse.self, keeper: model.answers) {
            rows = HomeHero.ordered(kept.value.rows)
            model.colors.want(rows.flatMap { row in
                row.items.map { HomeHero.from(rowId: row.id, rowTitle: row.title, hit: $0).backdrop }
            })
            status = LastAnswer.status(ageSeconds: kept.ageSeconds)
        }
        do {
            let home = try await model.hub.fetchKept(HubEndpoints.home, as: HomeResponse.self, keeper: model.answers) {
                LastAnswer.worthKeeping(rows: $0.rows.count, unavailable: $0.partial.count)
            }
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

/// The top of Home (Android `HomeHeroView`, the prototype's `.hero`): the
/// title's backdrop across the whole width, under the bars, fading into the
/// page; an eyebrow with its code in the accent, the name, its facts, how much
/// is left, then Resume or Play and Details. Every line keeps its place, so
/// nothing jumps when the hero changes title. On a phone the words are centred
/// over the picture's foot.
struct HeroView: View {
    let content: HeroContent
    let topInset: CGFloat
    /// From the top of the screen to the tab bar: what can be seen at once.
    var visibleHeight: CGFloat = .infinity
    let play: () -> Void
    @Environment(\.glassMetrics) private var metrics
    @Environment(\.glassAccent) private var accent

    /// The prototype's 480 on an iPad and 560 on an iPhone, measured from the
    /// top of the screen, and never so short that the words meet the bar. On
    /// a short screen (a phone turned sideways) it fits what can be seen, the
    /// next row's name showing under it, so Play is never below the fold.
    private var height: CGFloat {
        let tall = max(metrics.compact ? 560 : 480, topInset + 330)
        guard metrics.short else { return tall }
        return max(topInset + 230, min(tall, visibleHeight - 40))
    }

    var body: some View {
        ZStack(alignment: metrics.centred ? .bottom : .bottomLeading) {
            FadedArtwork.hero(content.backdrop, centred: metrics.centred)
            words
                .padding(.horizontal, metrics.margin)
                .padding(.bottom, 14)
        }
        .frame(maxWidth: .infinity)
        .frame(height: height)
        .clipped()
    }

    private var words: some View {
        VStack(alignment: metrics.centred ? .center : .leading, spacing: metrics.short ? 7 : 10) {
            Text(eyebrow)
                .font(HubType.body(12.5, weight: .bold, relativeTo: .caption))
                .tracking(1.75)
                .lineLimit(1)
            Text(content.title)
                .font(HubType.heading(metrics.heroTitle, weight: .heavy))
                .tracking(-0.02 * metrics.heroTitle)
                .foregroundStyle(.white)
                .lineLimit(2)
                .minimumScaleFactor(0.6)
            Text(content.meta.isEmpty ? AttributedString(" ") : factsLine(content.meta))
                .font(HubType.body(15, relativeTo: .subheadline))
                .lineLimit(1)
            // Held open for an unstarted title, so the buttons never move.
            HStack(spacing: 12) {
                HeroProgress(progress: content.progress, accent: accent.tint)
                    .frame(width: metrics.small ? 110 : 180)
                Text(content.progressLabel)
                    .font(HubType.body(13, relativeTo: .caption))
                    .foregroundStyle(.white.opacity(0.82))
                    .monospacedDigit()
            }
            .frame(height: 18)
            .opacity(content.progress > 0 ? 1 : 0)
            HStack(spacing: 10) {
                if content.canPlay {
                    Button(action: play) {
                        Label(content.playAction.isEmpty ? content.playLabel : content.playAction, systemImage: "play.fill")
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
            .padding(.top, 4)
        }
        .multilineTextAlignment(metrics.centred ? .center : .leading)
        .frame(maxWidth: metrics.centred ? .infinity : 660, alignment: metrics.centred ? .center : .leading)
        // Over the picture in a stack, a long title was offered one line's
        // height; its own height lets it take the second line it is allowed.
        .fixedSize(horizontal: false, vertical: true)
        .animation(.easeInOut(duration: 0.2), value: content.itemId)
    }

    /// "CONTINUE WATCHING · S3E4": the lead in soft white, the episode's code
    /// (or Coming up's day) in the accent.
    private var eyebrow: AttributedString {
        let mark = content.eyebrowMark
        var lead = content.eyebrow
        if !mark.isEmpty, lead.hasSuffix(mark) {
            lead = String(lead.dropLast(mark.count))
            if lead.hasSuffix(" · ") { lead = String(lead.dropLast(3)) }
        }
        var line = AttributedString(lead.isEmpty && mark.isEmpty ? " " : lead)
        line.foregroundColor = Color.white.opacity(0.72)
        if !mark.isEmpty {
            var code = AttributedString((lead.isEmpty ? "" : " · ") + mark)
            code.foregroundColor = accent.tint
            line += code
        }
        return line
    }
}

/// How far in, on the hero: the accent on a faint track (`.prog .b`).
struct HeroProgress: View {
    let progress: Double
    let accent: Color

    var body: some View {
        GeometryReader { geometry in
            ZStack(alignment: .leading) {
                Capsule().fill(.white.opacity(0.25))
                Capsule().fill(accent).frame(width: geometry.size.width * min(1, max(0, progress)))
            }
        }
        .frame(height: 4)
        .accessibilityLabel("\(Int(progress * 100))% watched")
    }
}

/// One Home row (`.row`): its title, then the cards side by side, with room
/// above and below for a lit card's lift and ring.
struct HomeRowView: View {
    let row: HomeRow
    let preview: (MediaHit) -> Void
    @Environment(\.glassMetrics) private var metrics
    @Environment(\.play) private var play

    private var landscape: Bool { HomeHero.isLandscape(rowId: row.id) }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(row.title)
                .font(HubType.body(metrics.rowTitle, weight: .bold, relativeTo: .title3))
                .foregroundStyle(.white)
                .padding(.horizontal, metrics.margin)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: metrics.gap) {
                    ForEach(row.items) { hit in
                        NavigationLink(value: AppRoute.title(TitleRoute(itemId: hit.jellyfinItemId, title: hit.media.title))) {
                            if landscape {
                                LandscapeCard(hit: hit).frame(width: metrics.tile)
                            } else {
                                PosterCard(hit: hit, caption: false).frame(width: metrics.poster)
                            }
                        }
                        .buttonStyle(GlassCardStyle())
                        .disabled(hit.jellyfinItemId.isEmpty)
                        .previewsWhenFocused { preview(hit) }
                        .contextMenu { playMenu(hit) }
                    }
                }
                .padding(.horizontal, metrics.margin)
                .padding(.top, 12)
                .padding(.bottom, 16)
            }
        }
        .padding(.top, 8)
    }

    /// A long press or a secondary click on a card: play it, or start a
    /// part-watched one over (the title page has both as buttons).
    @ViewBuilder private func playMenu(_ hit: MediaHit) -> some View {
        if !hit.jellyfinItemId.isEmpty {
            let series = hit.media.type == "series"
            let backdrop = HomeHero.from(rowId: row.id, rowTitle: row.title, hit: hit).backdrop
            let watching = hit.progress > 0 && !ResumeRules.showsWatched(played: hit.played, progress: hit.progress)
            Button {
                play(PlayRequest(itemId: hit.jellyfinItemId, series: series, title: hit.media.title, backdrop: backdrop))
            } label: {
                Label(watching ? "Resume" : "Play", systemImage: "play.fill")
            }
            if watching && !series {
                Button {
                    play(PlayRequest(itemId: hit.jellyfinItemId, mode: .restart, title: hit.media.title, backdrop: backdrop))
                } label: {
                    Label("Start over", systemImage: "arrow.counterclockwise")
                }
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
