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
    @Environment(\.openRoute) private var openRoute
    @Environment(\.play) private var play
    @Environment(\.playbackClosed) private var playbackClosed
    let route: TitleRoute

    @State private var item: HubKit.LibraryItem?
    @State private var status = StatusMessage("")
    @State private var saving = false
    @State private var expanded = false
    @State private var tab: TitleTab = .episodes
    /// Whether the person has picked a tab: until then the first one shows,
    /// even when More like this arrives after Cast.
    @State private var tabChosen = false

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
                    // The prototype's `.dart`: 590 tall on an iPad, 470 on an
                    // iPhone; on a phone turned sideways, what can be seen.
                    FadedArtwork.title(backdropPath)
                        .frame(height: metrics.short ? proxy.size.height + proxy.safeAreaInsets.top
                               : (metrics.compact ? 470 : 590))
                        .frame(maxWidth: .infinity)
                        .clipped()
                    VStack(alignment: .leading, spacing: 0) {
                        // On a short screen the words start under the bars,
                        // so Play is never below the fold.
                        header
                            .padding(.top, metrics.short ? proxy.safeAreaInsets.top + 10
                                     : max(metrics.compact ? 290 : 236, proxy.safeAreaInsets.top + 120))
                            .padding(.horizontal, metrics.margin)
                        StatusLine(message: status) { Task { await load() } }
                            .padding(.horizontal, metrics.margin)
                            .padding(.top, 8)
                        if item != nil, !tabs.isEmpty {
                            UnderlineTabs(tabs: tabs, selection: Binding(get: { tab }, set: { tab = $0; tabChosen = true }))
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
        .onChange(of: playbackClosed) { _, _ in Task { await refreshAfterPlayback() } }
        .onChange(of: tabs.map(\.id)) { _, ids in
            if let first = ids.first, !tabChosen || !ids.contains(tab) { tab = first }
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

    /// The main pill, Start over beside it when there is a place to start
    /// over from, then watched and favourite as round glass buttons.
    private func actions(_ item: HubKit.LibraryItem) -> some View {
        HStack(spacing: metrics.small ? 8 : 10) {
            if item.type != "season" {
                Button {
                    playMain(item)
                } label: {
                    Label(playLabel(item), systemImage: "play.fill")
                }
                .buttonStyle(PrimaryPillStyle())
                .disabled(item.type == "series" && target == nil)
                if DetailLines.offersStartOver(item) {
                    // A glass pill on an iPad or a Mac (the prototype's `.bg`), a
                    // round button where a phone's row has no room for the words.
                    if metrics.small {
                        GlassRoundButton(systemImage: "arrow.counterclockwise", label: "Start over", size: 42) {
                            play(request(for: item, mode: .restart))
                        }
                    } else {
                        Button {
                            play(request(for: item, mode: .restart))
                        } label: {
                            Label("Start over", systemImage: "arrow.counterclockwise")
                        }
                        .buttonStyle(GlassPillStyle())
                    }
                }
                GlassRoundButton(systemImage: item.played ? "eye.fill" : "eye",
                                 label: item.played ? "Mark unwatched" : "Mark watched", on: item.played,
                                 size: metrics.small ? 42 : 46) {
                    Task { await change(.played(!item.played)) }
                }
                .disabled(saving)
            }
            GlassRoundButton(systemImage: item.favorite ? "star.fill" : "star",
                             label: item.favorite ? "Remove from favourites" : "Favourite", on: item.favorite,
                             size: metrics.small ? 42 : 46) {
                Task { await change(.favorite(!item.favorite)) }
            }
            .disabled(saving)
        }
    }

    /// Play: a movie or an episode where it was left, or from the start
    /// without a saved position; a series its part-watched, next or first episode.
    private func playMain(_ item: HubKit.LibraryItem) {
        if item.type == "series" {
            guard let target else { return }
            play(PlayRequest(itemId: target.item.id, mode: DetailLines.startMode(target), title: item.title,
                             backdrop: backdropPath))
        } else {
            play(request(for: item, mode: DetailLines.startMode(item)))
        }
    }

    /// The player opens under the series' name for an episode, as it will show it.
    private func request(for item: HubKit.LibraryItem, mode: PlaybackStartMode) -> PlayRequest {
        let backdrop = item.id == route.itemId ? backdropPath : (item.thumb.isEmpty ? backdropPath : item.thumb)
        return PlayRequest(itemId: item.id, mode: mode, title: item.seriesTitle.isEmpty ? item.title : item.seriesTitle,
                           backdrop: backdrop)
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
                            // An episode plays, as the prototype's and Android's
                            // do; its own page is in the menu.
                            Button {
                                play(request(for: episode, mode: DetailLines.startMode(episode)))
                            } label: {
                                EpisodeCard(episode: episode, upNext: episode.id == target?.item.id)
                                    .frame(width: metrics.episode)
                            }
                            .buttonStyle(GlassCardStyle())
                            .accessibilityHint(episode.positionSeconds > 0 ? "Resumes the episode" : "Plays the episode")
                            .contextMenu { episodeMenu(episode) }
                            .onAppear {
                                if index >= episodes.count - 3 { Task { await loadEpisodes(reset: false) } }
                            }
                        }
                    }
                    .padding(.top, 12)
                    .padding(.bottom, 16)
                }
                // Margins rather than padding, so the episode scrolled to below
                // stops at the page's margin, not against the screen's edge.
                .contentMargins(.horizontal, metrics.margin, for: .scrollContent)
                // The strip opens at the episode Play starts, as Android's does,
                // rather than at episode 1 of a half-watched season.
                .onChange(of: episodes.map(\.id)) { _, ids in
                    guard let id = target?.item.id, ids.contains(id), revealedTarget != id, ids.first != id else { return }
                    revealedTarget = id
                    reader.scrollTo(id, anchor: .leading)
                }
            }
        }
    }

    @ViewBuilder private func episodeMenu(_ episode: HubKit.LibraryItem) -> some View {
        Button {
            play(request(for: episode, mode: DetailLines.startMode(episode)))
        } label: {
            Label(episode.positionSeconds > 0 ? "Resume" : "Play", systemImage: "play.fill")
        }
        if DetailLines.offersStartOver(episode) {
            Button {
                play(request(for: episode, mode: .restart))
            } label: {
                Label("Start over", systemImage: "arrow.counterclockwise")
            }
        }
        Button {
            openRoute(.title(TitleRoute(itemId: episode.id, title: episode.title)))
        } label: {
            Label("Episode details", systemImage: "info.circle")
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

    /// Back from the player: the title, a series' next episode and the
    /// episodes already loaded read again and swapped in whole, so the strip
    /// and the page keep their places.
    private func refreshAfterPlayback() async {
        guard let response = try? await model.hub.fetch(HubEndpoints.libraryItem(route.itemId), as: LibraryItemResponse.self)
        else { return }
        item = response.item
        guard response.item.type == "series" else { return }
        await loadTarget()
        let season = seasonId
        let pages = episodePage
        guard !season.isEmpty, pages > 0 else { return }
        var fresh: [HubKit.LibraryItem] = []
        for page in 1...pages {
            guard let list = try? await model.hub.fetch(
                HubEndpoints.libraryEpisodes(seriesId: route.itemId, seasonId: season, page: page), as: LibraryItemList.self)
            else { return }
            fresh += list.items
        }
        guard season == seasonId, !loadingEpisodes else { return }
        episodes = fresh
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
