import HubKit
import SwiftUI

/// A movie, series, season or episode from the library, in the 2026-10
/// redesign's shape (Android `screens/library/LibraryDetailScreen` with
/// `DetailHeaderView`): the backdrop fading in behind the name, facts and
/// progress, Resume or Play with round watched and favourite buttons, then
/// Episodes, More like this, Cast and Details as tabs.
struct TitleView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.horizontalSizeClass) private var sizeClass
    let route: TitleRoute

    @State private var item: HubKit.LibraryItem?
    @State private var status = StatusMessage("")
    @State private var saving = false
    @State private var expanded = false
    @State private var tab: TitleTab = .episodes

    @State private var target: SeriesPlayTarget?
    @State private var targetFailed = false
    @State private var similar: [MediaHit] = []
    @State private var seasons: [HubKit.LibraryItem] = []
    @State private var seasonId = ""
    @State private var episodes: [HubKit.LibraryItem] = []
    @State private var episodePage = 0
    @State private var episodePages = 1
    @State private var loadingEpisodes = false
    @State private var revealedTarget = ""

    enum TitleTab: Hashable { case episodes, similar, cast, details }

    private var compact: Bool { sizeClass == .compact }

    /// Episodes for a series; More like this when there is any; Cast when
    /// there is a cast; Details always.
    private var tabs: [(id: TitleTab, title: String)] {
        guard let item else { return [] }
        var out: [(id: TitleTab, title: String)] = []
        if item.type == "series" { out.append((.episodes, "Episodes")) }
        if !similar.isEmpty { out.append((.similar, "More like this")) }
        if !DetailLines.cast(item).isEmpty { out.append((.cast, "Cast")) }
        out.append((.details, "Details"))
        return out
    }

    var body: some View {
        GeometryReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    header(topInset: proxy.safeAreaInsets.top)
                    VStack(alignment: .leading, spacing: 16) {
                        StatusLine(message: status) { Task { await load() } }
                        if item != nil, !tabs.isEmpty {
                            UnderlineTabs(tabs: tabs, selection: $tab)
                            tabContent
                        }
                    }
                    .padding(.horizontal, 24)
                }
                .padding(.bottom, 28)
            }
            .ignoresSafeArea(edges: .top)
        }
        .background(Color.surface)
        .underTheBar()
        .refreshable { await load() }
        .task(id: model.userId) { await load() }
        .onChange(of: tabs.map(\.id)) { _, ids in
            if !ids.contains(tab), let first = ids.first { tab = first }
        }
    }

    // MARK: Header

    private var backdropPath: String {
        guard let item else { return "" }
        if !item.backdrop.isEmpty { return item.backdrop }
        return item.type == "episode" ? item.thumb : item.poster
    }

    private func header(topInset: CGFloat) -> some View {
        BackdropHeader(path: backdropPath, topInset: topInset) {
            VStack(alignment: .leading, spacing: 8) {
                if let item, item.type == "episode", !item.seriesTitle.isEmpty {
                    NavigationLink(value: TitleRoute(itemId: item.seriesId, title: item.seriesTitle)) {
                        Text(item.seriesTitle.uppercased())
                            .font(HubType.body(13, weight: .bold, relativeTo: .caption))
                            .tracking(1.2)
                            .foregroundStyle(Color.accentColor)
                    }
                    .buttonStyle(.plain)
                    .disabled(item.seriesId.isEmpty)
                }
                Text(item?.title ?? route.title)
                    .font(HubType.heading(compact ? 32 : 44, weight: .heavy))
                    .foregroundStyle(Color.ink)
                    .lineLimit(2)
                    .minimumScaleFactor(0.7)
                if let item {
                    if !item.originalTitle.isEmpty,
                       item.originalTitle.caseInsensitiveCompare(item.title) != .orderedSame {
                        Text(item.originalTitle)
                            .font(HubType.body(15, relativeTo: .subheadline))
                            .foregroundStyle(Color.muted)
                    }
                    let facts = DetailLines.facts(item)
                    if !facts.isEmpty {
                        Text(facts)
                            .font(HubType.body(15, relativeTo: .subheadline))
                            .foregroundStyle(Color.muted)
                    }
                    let state = DetailLines.state(item)
                    if !state.isEmpty {
                        Text(state)
                            .font(HubType.body(15, weight: .medium, relativeTo: .subheadline))
                            .foregroundStyle(Color.accentColor)
                    }
                    if !item.overview.isEmpty { overview(item.overview) }
                    actions(item).padding(.top, 6)
                }
            }
            .frame(maxWidth: 640, alignment: .leading)
        }
    }

    private func overview(_ text: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(text)
                .font(HubType.body(15, relativeTo: .body))
                .foregroundStyle(Color.ink.opacity(0.85))
                .lineLimit(expanded ? nil : 2)
            Button(expanded ? "Collapse description" : "Read more") { expanded.toggle() }
                .font(HubType.body(14, weight: .semibold, relativeTo: .subheadline))
                .foregroundStyle(Color.muted)
                .buttonStyle(.plain)
        }
    }

    /// The main pill, then watched and favourite as round buttons.
    private func actions(_ item: HubKit.LibraryItem) -> some View {
        HStack(spacing: 12) {
            if item.type != "season" {
                Button {
                    status = StatusMessage("Playing on Apple devices comes next")
                } label: {
                    Label(playLabel(item), systemImage: "play.fill")
                }
                .buttonStyle(AccentPillStyle())
                .disabled(item.type == "series" && target == nil)
                RoundIconButton(systemImage: item.played ? "eye.fill" : "eye",
                                label: item.played ? "Mark unwatched" : "Mark watched", on: item.played) {
                    Task { await change(.played(!item.played)) }
                }
                .disabled(saving)
            }
            RoundIconButton(systemImage: item.favorite ? "star.fill" : "star",
                            label: item.favorite ? "Remove from favourites" : "Favourite", on: item.favorite) {
                Task { await change(.favorite(!item.favorite)) }
            }
            .disabled(saving)
        }
    }

    private func playLabel(_ item: HubKit.LibraryItem) -> String {
        guard item.type == "series" else { return DetailLines.playLabel(item) }
        if let target { return DetailLines.seriesPlayLabel(target) }
        return targetFailed ? "No episode to play" : "Finding next episode…"
    }

    // MARK: Tabs

    @ViewBuilder private var tabContent: some View {
        if let item {
            switch tab {
            case .episodes: episodesTab
            case .similar: similarTab
            case .cast: castTab(item)
            case .details: detailsTab(item)
            }
        }
    }

    @ViewBuilder private var episodesTab: some View {
        VStack(alignment: .leading, spacing: 14) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(seasons) { season in
                        ChoicePill(title: seasonTitle(season), selected: season.id == seasonId) {
                            guard season.id != seasonId else { return }
                            seasonId = season.id
                            Task { await loadEpisodes(reset: true) }
                        }
                    }
                }
            }
            if episodes.isEmpty && !loadingEpisodes && !seasons.isEmpty {
                Text("No episodes in this season yet.")
                    .font(HubType.body(15, relativeTo: .subheadline))
                    .foregroundStyle(Color.muted)
            }
            ScrollViewReader { reader in
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(alignment: .top, spacing: 16) {
                        ForEach(Array(episodes.enumerated()), id: \.element.id) { index, episode in
                            NavigationLink(value: TitleRoute(itemId: episode.id, title: episode.title)) {
                                EpisodeCard(episode: episode, upNext: episode.id == target?.item.id)
                                    .frame(width: compact ? 220 : 260)
                            }
                            .buttonStyle(.plain)
                            .onAppear {
                                if index >= episodes.count - 3 { Task { await loadEpisodes(reset: false) } }
                            }
                        }
                    }
                }
                // The strip opens at the episode Play starts, as Android's does,
                // rather than at episode 1 of a half-watched season.
                .onChange(of: episodes.map(\.id)) { _, ids in
                    guard let id = target?.item.id, ids.contains(id), revealedTarget != id else { return }
                    revealedTarget = id
                    reader.scrollTo(id, anchor: .leading)
                }
            }
        }
    }

    private var similarTab: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            LazyHStack(alignment: .top, spacing: 14) {
                ForEach(similar) { hit in
                    NavigationLink(value: TitleRoute(itemId: hit.jellyfinItemId, title: hit.media.title)) {
                        PosterCard(hit: hit, caption: false).frame(width: compact ? 104 : 128)
                    }
                    .buttonStyle(.plain)
                    .disabled(hit.jellyfinItemId.isEmpty)
                }
            }
        }
    }

    private func castTab(_ item: HubKit.LibraryItem) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            LazyHStack(alignment: .top, spacing: 16) {
                ForEach(DetailLines.cast(item)) { person in
                    VStack(spacing: 6) {
                        Color.clear
                            .frame(width: 84, height: 84)
                            .overlay { ArtworkView(path: person.image, width: 240) }
                            .clipShape(Circle())
                        Text(person.name)
                            .font(HubType.body(13, weight: .medium, relativeTo: .caption))
                            .foregroundStyle(Color.ink)
                        if !person.role.isEmpty {
                            Text(person.role)
                                .font(HubType.body(12, relativeTo: .caption2))
                                .foregroundStyle(Color.muted)
                        }
                    }
                    .lineLimit(2)
                    .multilineTextAlignment(.center)
                    .frame(width: 100)
                    .accessibilityElement(children: .combine)
                }
            }
        }
    }

    private func detailsTab(_ item: HubKit.LibraryItem) -> some View {
        Grid(alignment: .leadingFirstTextBaseline, horizontalSpacing: 16, verticalSpacing: 8) {
            ForEach(DetailLines.details(item), id: \.label) { fact in
                GridRow {
                    Text(fact.label)
                        .font(HubType.body(15, relativeTo: .subheadline))
                        .foregroundStyle(Color.muted)
                    Text(fact.value)
                        .font(HubType.body(15, relativeTo: .subheadline))
                        .foregroundStyle(Color.ink)
                }
            }
        }
    }

    /// "Season 2 · 10 episodes" on the chosen pill, as on Android.
    private func seasonTitle(_ season: HubKit.LibraryItem) -> String {
        let name = season.title.isEmpty ? EpisodeLabel.season(season.indexNumber) : season.title
        guard season.id == seasonId, !episodes.isEmpty, !loadingEpisodes, episodePage >= episodePages else { return name }
        return name + " · \(episodes.count) episode" + (episodes.count == 1 ? "" : "s")
    }

    // MARK: Loading and saving

    private func load() async {
        status = item == nil ? StatusText.loading("the title", refreshing: false) : StatusMessage("")
        do {
            let response = try await model.hub.fetch(HubEndpoints.libraryItem(route.itemId), as: LibraryItemResponse.self)
            item = response.item
            status = StatusMessage("")
            if response.item.type == "series" {
                await loadTarget()
                await loadSeasons()
            }
            await loadSimilar()
        } catch {
            if error.kind == .cancelled { return }
            status = StatusText.failed(error.message, kind: error.kind, hasData: item != nil)
        }
    }

    private func loadSimilar() async {
        if let page = try? await model.hub.fetch(HubEndpoints.librarySimilar(itemId: route.itemId), as: LibraryPage.self) {
            similar = page.items
        }
    }

    private func loadTarget() async {
        do {
            target = try await model.hub.fetch(HubEndpoints.seriesPlayTarget(seriesId: route.itemId), as: SeriesPlayTarget.self)
            targetFailed = false
        } catch {
            if error.kind != .cancelled { targetFailed = true }
        }
    }

    private func loadSeasons() async {
        guard let response = try? await model.hub.fetch(HubEndpoints.librarySeasons(seriesId: route.itemId),
                                                        as: LibraryItemList.self) else { return }
        seasons = response.items
        // The season of the episode Play starts; else the first real season.
        if seasonId.isEmpty || !seasons.contains(where: { $0.id == seasonId }) {
            let targetSeason = target?.item.seasonId ?? ""
            seasonId = seasons.first(where: { $0.id == targetSeason })?.id
                ?? (seasons.first { $0.indexNumber > 0 } ?? seasons.first)?.id ?? ""
        }
        await loadEpisodes(reset: true)
    }

    private func loadEpisodes(reset: Bool) async {
        if reset {
            episodes = []
            episodePage = 0
            episodePages = 1
        }
        guard !seasonId.isEmpty, !loadingEpisodes, episodePage < episodePages else { return }
        loadingEpisodes = true
        defer { loadingEpisodes = false }
        let season = seasonId
        guard let response = try? await model.hub.fetch(
            HubEndpoints.libraryEpisodes(seriesId: route.itemId, seasonId: season, page: episodePage + 1),
            as: LibraryItemList.self), season == seasonId else { return }
        episodes += response.items
        episodePage = response.page
        episodePages = max(1, response.totalPages)
    }

    /// Optimistic, as on Android: the page changes at once and goes back if
    /// Jellyfin refuses. Marking watched clears the saved position.
    private func change(_ change: LibraryStateChange) async {
        guard let before = item else { return }
        var after = before
        if let played = change.played {
            after.played = played
            after.progress = 0
            after.positionSeconds = 0
        }
        if let favorite = change.favorite { after.favorite = favorite }
        item = after
        saving = true
        defer { saving = false }
        status = StatusMessage("Saving to Jellyfin…")
        do {
            let response = try await model.hub.fetch(
                HubEndpoints.libraryState(itemId: before.id, body: change.body()), as: LibraryItemResponse.self)
            item = response.item
            status = StatusMessage("")
        } catch {
            item = before
            status = StatusText.failed(error.message, kind: error.kind, hasData: true, canRetry: false)
        }
    }
}

/// One episode in a season's strip (Android `ui/EpisodeCardView`): its 16:9
/// still with progress, UP NEXT on the one Play starts, "5. Title" and
/// "22 min · 69% watched" under it.
struct EpisodeCard: View {
    let episode: HubKit.LibraryItem
    let upNext: Bool

    private var watched: Bool { ResumeRules.showsWatched(played: episode.played, progress: episode.progress) }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Color.clear
                .aspectRatio(16 / 9, contentMode: .fit)
                .overlay { ArtworkView(path: episode.thumb.isEmpty ? episode.poster : episode.thumb, width: 480) }
                .overlay(alignment: .bottom) { ProgressStrip(progress: watched ? 0 : episode.progress) }
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                .overlay(alignment: .topTrailing) {
                    if upNext && !watched {
                        Text("UP NEXT")
                            .font(HubType.body(11, weight: .bold, relativeTo: .caption2))
                            .tracking(0.8)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .foregroundStyle(Color.accentInk)
                            .background(Color.accentColor, in: Capsule())
                            .padding(6)
                    } else {
                        WatchBadge(played: episode.played, progress: episode.progress, unplayedCount: 0, favorite: false)
                            .padding(6)
                    }
                }
            Text(DetailLines.episodeTitle(episode))
                .font(HubType.body(15, weight: .semibold, relativeTo: .subheadline))
                .foregroundStyle(Color.ink)
                .lineLimit(1)
            let meta = DetailLines.episodeMeta(episode)
            Text(meta.isEmpty ? " " : meta)
                .font(HubType.body(13, relativeTo: .caption))
                .foregroundStyle(Color.muted)
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}
