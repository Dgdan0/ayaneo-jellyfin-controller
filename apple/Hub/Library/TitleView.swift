import HubKit
import SwiftUI

/// A movie, series, season or episode from the library, in the Glass shape
/// (the prototype's `pgTitle`; Android `screens/library/LibraryDetailScreen`):
/// the backdrop filling the top under the bars and fading into the page, the
/// name, facts and progress over it, Resume or Play with round watched and
/// favourite buttons, then Episodes, More like this, Cast and Details as tabs.
struct TitleView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.glassMetrics) private var metrics
    @Environment(\.glassAccent) private var accent
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
                ZStack(alignment: .top) {
                    // The prototype's `.dart`: 590 tall on an iPad, 470 on an iPhone.
                    FadedArtwork.title(backdropPath)
                        .frame(height: metrics.compact ? 470 : 590)
                        .frame(maxWidth: .infinity)
                        .clipped()
                    VStack(alignment: .leading, spacing: 0) {
                        header
                            .padding(.top, max(metrics.compact ? 290 : 236, proxy.safeAreaInsets.top + 120))
                            .padding(.horizontal, metrics.margin)
                        StatusLine(message: status) { Task { await load() } }
                            .padding(.horizontal, metrics.margin)
                            .padding(.top, 8)
                        if item != nil, !tabs.isEmpty {
                            UnderlineTabs(tabs: tabs, selection: $tab)
                                .padding(.horizontal, metrics.margin)
                                .padding(.top, 14)
                            tabContent
                        }
                    }
                }
                .padding(.bottom, 28)
            }
            .ignoresSafeArea(edges: .top)
        }
        .ambientArtwork(backdropPath)
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

    /// The prototype's `.dhead`: lines 10 apart, at most 860 wide.
    private var header: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let item, item.type == "episode", !item.seriesTitle.isEmpty {
                NavigationLink(value: AppRoute.title(TitleRoute(itemId: item.seriesId, title: item.seriesTitle))) {
                    Text(item.seriesTitle.uppercased())
                        .font(HubType.body(12.5, weight: .bold, relativeTo: .caption))
                        .tracking(1.75)
                        .foregroundStyle(accent.tint)
                }
                .buttonStyle(.plain)
                .disabled(item.seriesId.isEmpty)
            }
            Text(item?.title ?? route.title)
                .font(HubType.heading(metrics.heroTitle, weight: .heavy))
                .tracking(-0.02 * metrics.heroTitle)
                .foregroundStyle(.white)
                .lineLimit(2)
                .minimumScaleFactor(0.6)
            if let item {
                if !item.originalTitle.isEmpty,
                   item.originalTitle.caseInsensitiveCompare(item.title) != .orderedSame {
                    Text(item.originalTitle)
                        .font(HubType.body(15, relativeTo: .subheadline))
                        .foregroundStyle(.white.opacity(0.66))
                }
                let facts = DetailLines.facts(item)
                if !facts.isEmpty {
                    // Wraps rather than truncates, as the prototype's `.facts` does.
                    Text(factsLine(facts.components(separatedBy: "  ·  ")))
                        .font(HubType.body(15, relativeTo: .subheadline))
                }
                let state = DetailLines.state(item)
                if !state.isEmpty {
                    Text(state)
                        .font(HubType.body(14, weight: .bold, relativeTo: .subheadline))
                        .foregroundStyle(accent.tint)
                }
                if !item.overview.isEmpty { overview(item.overview) }
                actions(item).padding(.top, 4)
            }
        }
        .frame(maxWidth: 860, alignment: .leading)
        // Laid over the backdrop in a stack, the lines were offered one line's
        // height each and truncated; their own height lets them wrap.
        .fixedSize(horizontal: false, vertical: true)
    }

    private func overview(_ text: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(text)
                .font(HubType.body(15, relativeTo: .body))
                .foregroundStyle(.white.opacity(0.86))
                .lineLimit(expanded ? nil : 2)
                .frame(maxWidth: 620, alignment: .leading)
            Button(expanded ? "Collapse description" : "Read more") { expanded.toggle() }
                .font(HubType.body(13, weight: .bold, relativeTo: .footnote))
                .foregroundStyle(.white.opacity(0.7))
                .buttonStyle(.plain)
        }
    }

    /// The main pill, then watched and favourite as round glass buttons.
    private func actions(_ item: HubKit.LibraryItem) -> some View {
        HStack(spacing: metrics.compact ? 8 : 10) {
            if item.type != "season" {
                Button {
                    status = StatusMessage("Playing on Apple devices comes next")
                } label: {
                    Label(playLabel(item), systemImage: "play.fill")
                }
                .buttonStyle(PrimaryPillStyle())
                .disabled(item.type == "series" && target == nil)
                GlassRoundButton(systemImage: item.played ? "eye.fill" : "eye",
                                 label: item.played ? "Mark unwatched" : "Mark watched", on: item.played,
                                 size: metrics.compact ? 42 : 46) {
                    Task { await change(.played(!item.played)) }
                }
                .disabled(saving)
            }
            GlassRoundButton(systemImage: item.favorite ? "star.fill" : "star",
                             label: item.favorite ? "Remove from favourites" : "Favourite", on: item.favorite,
                             size: metrics.compact ? 42 : 46) {
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
        VStack(alignment: .leading, spacing: 0) {
            // The prototype's `.pills`: one glass pill per season.
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
                .padding(.horizontal, metrics.margin)
                .padding(.top, 14)
                .padding(.bottom, 2)
            }
            if episodes.isEmpty && !loadingEpisodes && !seasons.isEmpty {
                Text("No episodes in this season yet.")
                    .font(HubType.body(15, relativeTo: .subheadline))
                    .foregroundStyle(.white.opacity(0.66))
                    .padding(.horizontal, metrics.margin)
                    .padding(.top, 14)
            }
            ScrollViewReader { reader in
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(alignment: .top, spacing: metrics.gap) {
                        ForEach(Array(episodes.enumerated()), id: \.element.id) { index, episode in
                            NavigationLink(value: AppRoute.title(TitleRoute(itemId: episode.id, title: episode.title))) {
                                EpisodeCard(episode: episode, upNext: episode.id == target?.item.id)
                                    .frame(width: metrics.episode)
                            }
                            .buttonStyle(GlassCardStyle())
                            .onAppear {
                                if index >= episodes.count - 3 { Task { await loadEpisodes(reset: false) } }
                            }
                        }
                    }
                    .padding(.horizontal, metrics.margin)
                    .padding(.top, 12)
                    .padding(.bottom, 16)
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
            LazyHStack(alignment: .top, spacing: metrics.gap) {
                ForEach(similar) { hit in
                    NavigationLink(value: AppRoute.title(TitleRoute(itemId: hit.jellyfinItemId, title: hit.media.title))) {
                        PosterCard(hit: hit).frame(width: metrics.poster)
                    }
                    .buttonStyle(GlassCardStyle())
                    .disabled(hit.jellyfinItemId.isEmpty)
                }
            }
            .padding(.horizontal, metrics.margin)
            .padding(.top, 12)
            .padding(.bottom, 16)
        }
    }

    /// The prototype's `.people`: a round portrait for each, name and part under it.
    private func castTab(_ item: HubKit.LibraryItem) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            LazyHStack(alignment: .top, spacing: 18) {
                ForEach(DetailLines.cast(item)) { person in
                    PersonCard(person: person)
                }
            }
            .padding(.horizontal, metrics.margin)
            .padding(.top, 16)
            .padding(.bottom, 18)
        }
    }

    /// The prototype's `.dl`: small capitals over each value, in columns.
    private func detailsTab(_ item: HubKit.LibraryItem) -> some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 230), spacing: 30, alignment: .topLeading)],
                  alignment: .leading, spacing: 16) {
            ForEach(DetailLines.details(item), id: \.label) { fact in
                VStack(alignment: .leading, spacing: 4) {
                    GlassLabel(text: fact.label)
                    Text(fact.value)
                        .font(HubType.body(15, relativeTo: .subheadline))
                        .foregroundStyle(.white)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .accessibilityElement(children: .combine)
            }
        }
        .padding(.horizontal, metrics.margin)
        .padding(.top, 18)
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

/// One episode in a season's strip (`.card.ep`; Android `ui/EpisodeCardView`):
/// its 16:9 still with the progress inside, UP NEXT on the one Play starts, a
/// tick on a watched one, "5. Title" and "22 min · 69% watched" under it.
struct EpisodeCard: View {
    let episode: HubKit.LibraryItem
    let upNext: Bool
    @Environment(\.glassMetrics) private var metrics

    private var watched: Bool { ResumeRules.showsWatched(played: episode.played, progress: episode.progress) }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Color.clear
                .aspectRatio(16 / 9, contentMode: .fit)
                .overlay { ArtworkView(path: episode.thumb.isEmpty ? episode.poster : episode.thumb, width: 480) }
                .overlay { ArtworkProgress(progress: watched ? 0 : episode.progress) }
                .overlay { PlayDisc() }
                .clipShape(RoundedRectangle(cornerRadius: metrics.radius, style: .continuous))
                .overlay(alignment: .topLeading) {
                    if upNext && !watched { UpNextTag().padding(8) }
                }
                .overlay(alignment: .topTrailing) {
                    if watched {
                        WatchBadge(played: episode.played, progress: episode.progress, unplayedCount: 0, favorite: false)
                            .padding(6)
                    }
                }
                .litArtwork(corner: metrics.radius)
            CardCaption(title: DetailLines.episodeTitle(episode), detail: DetailLines.episodeMeta(episode))
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

/// One of the cast (`.person`): a round portrait with its shadow, or their
/// initials when there is no picture, then the name and the part.
struct PersonCard: View {
    let person: LibraryPerson

    private var initials: String {
        person.name.split(separator: " ").prefix(2).compactMap(\.first).map { String($0).uppercased() }.joined()
    }

    var body: some View {
        VStack(spacing: 6) {
            Group {
                if person.image.isEmpty {
                    Text(initials)
                        .font(HubType.heading(28, weight: .heavy, relativeTo: .title2))
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .background(Color.white.opacity(0.12))
                } else {
                    ArtworkView(path: person.image, width: 240)
                }
            }
            .frame(width: 92, height: 92)
            .clipShape(Circle())
            .shadow(color: .black.opacity(0.35), radius: 11, y: 10)
            Text(person.name)
                .font(HubType.body(14, weight: .bold, relativeTo: .subheadline))
                .foregroundStyle(.white)
            if !person.role.isEmpty {
                Text(person.role)
                    .font(HubType.body(12, relativeTo: .caption))
                    .foregroundStyle(.white.opacity(0.62))
            }
        }
        .lineLimit(2)
        .multilineTextAlignment(.center)
        .frame(width: 112)
        .accessibilityElement(children: .combine)
    }
}
