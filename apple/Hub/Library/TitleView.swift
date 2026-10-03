import HubKit
import SwiftUI

/// A movie, series, season or episode from the library: its artwork, facts and
/// state, watched and favourite, and for a series its seasons and episodes.
/// Android's `screens/library/LibraryDetailScreen`. Playing is #2's Apple part.
struct TitleView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.horizontalSizeClass) private var sizeClass
    let route: TitleRoute

    @State private var item: HubKit.LibraryItem?
    @State private var status = StatusMessage("")
    @State private var saving = false
    @State private var expanded = false

    @State private var seasons: [HubKit.LibraryItem] = []
    @State private var seasonId = ""
    @State private var episodes: [HubKit.LibraryItem] = []
    @State private var episodePage = 0
    @State private var episodePages = 1
    @State private var loadingEpisodes = false

    private var compact: Bool { sizeClass == .compact }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                header
                VStack(alignment: .leading, spacing: 20) {
                    StatusLine(message: status) { Task { await load() } }
                    if let item {
                        actions(item)
                        if !item.overview.isEmpty { overview(item.overview) }
                        if item.type == "series" { seasonsSection }
                        castSection(item)
                        detailsSection(item)
                    }
                }
                .padding(.horizontal, 20)
            }
            .padding(.bottom, 28)
        }
        .background(Color.surface)
        .navigationTitle(item?.title ?? route.title)
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .refreshable { await load() }
        .task(id: model.userId) { await load() }
    }

    // MARK: Header

    private var backdropPath: String {
        guard let item else { return "" }
        if !item.backdrop.isEmpty { return item.backdrop }
        return item.type == "episode" ? item.thumb : ""
    }

    private var posterPath: String {
        guard let item else { return "" }
        return item.poster.isEmpty ? item.thumb : item.poster
    }

    @ViewBuilder private var header: some View {
        VStack(alignment: .leading, spacing: 0) {
            if !backdropPath.isEmpty {
                ArtworkView(path: backdropPath, width: 1920)
                    .frame(maxWidth: .infinity)
                    .frame(height: compact ? 220 : 340)
                    .overlay {
                        LinearGradient(colors: [.clear, Color.surface.opacity(0.4), Color.surface],
                                       startPoint: .center, endPoint: .bottom)
                    }
            }
            HStack(alignment: .bottom, spacing: 18) {
                if !posterPath.isEmpty, item?.type != "episode" {
                    Color.clear
                        .frame(width: compact ? 96 : 132, height: compact ? 144 : 198)
                        .overlay { ArtworkView(path: posterPath, width: 360) }
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                        .shadow(color: .black.opacity(0.25), radius: 8, y: 4)
                }
                VStack(alignment: .leading, spacing: 6) {
                    if let item, item.type == "episode", !item.seriesTitle.isEmpty {
                        NavigationLink(value: TitleRoute(itemId: item.seriesId, title: item.seriesTitle)) {
                            Text(item.seriesTitle.uppercased())
                                .font(HubType.body(13, weight: .bold, relativeTo: .caption))
                                .tracking(1.1)
                                .foregroundStyle(Color.accentColor)
                        }
                        .buttonStyle(.plain)
                        .disabled(item.seriesId.isEmpty)
                    }
                    Text(item?.title ?? route.title)
                        .font(HubType.heading(compact ? 28 : 36))
                        .foregroundStyle(Color.ink)
                        .lineLimit(2)
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
                                .foregroundStyle(Color.ink)
                        }
                    }
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, backdropPath.isEmpty ? 16 : -60)
        }
    }

    // MARK: Actions

    private func actions(_ item: HubKit.LibraryItem) -> some View {
        HStack(spacing: 12) {
            if item.type != "season" {
                Button {
                    Task { await change(.played(!item.played)) }
                } label: {
                    Label(item.played ? "Mark unwatched" : "Mark watched",
                          systemImage: item.played ? "checkmark.circle.fill" : "checkmark.circle")
                }
            }
            Button {
                Task { await change(.favorite(!item.favorite)) }
            } label: {
                Label(item.favorite ? "Favourite" : "Add to favourites", systemImage: item.favorite ? "star.fill" : "star")
            }
        }
        .font(HubType.body(15, weight: .semibold, relativeTo: .subheadline))
        .buttonStyle(.bordered)
        .buttonBorderShape(.capsule)
        .disabled(saving)
    }

    private func overview(_ text: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(text)
                .font(HubType.body(16))
                .foregroundStyle(Color.ink)
                .lineLimit(expanded ? nil : 3)
                .frame(maxWidth: 760, alignment: .leading)
            Button(expanded ? "Collapse description" : "Read more") { expanded.toggle() }
                .font(HubType.body(14, weight: .semibold, relativeTo: .subheadline))
                .buttonStyle(.borderless)
        }
    }

    // MARK: Seasons and episodes

    @ViewBuilder private var seasonsSection: some View {
        if !seasons.isEmpty {
            VStack(alignment: .leading, spacing: 12) {
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
                if episodes.isEmpty && !loadingEpisodes {
                    Text("No episodes in this season yet.")
                        .font(HubType.body(15, relativeTo: .subheadline))
                        .foregroundStyle(Color.muted)
                }
                LazyVGrid(columns: [GridItem(.adaptive(minimum: compact ? 300 : 340), spacing: 16, alignment: .top)],
                          alignment: .leading, spacing: 16) {
                    ForEach(Array(episodes.enumerated()), id: \.element.id) { index, episode in
                        NavigationLink(value: TitleRoute(itemId: episode.id, title: episode.title)) {
                            EpisodeRow(episode: episode)
                        }
                        .buttonStyle(.plain)
                        .onAppear {
                            if index >= episodes.count - 3 { Task { await loadEpisodes(reset: false) } }
                        }
                    }
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

    // MARK: Cast and details

    @ViewBuilder private func castSection(_ item: HubKit.LibraryItem) -> some View {
        let cast = DetailLines.cast(item)
        if !cast.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                Text("Cast")
                    .font(HubType.heading(22, weight: .semibold, relativeTo: .title2))
                    .foregroundStyle(Color.ink)
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(alignment: .top, spacing: 16) {
                        ForEach(cast) { person in
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
        }
    }

    @ViewBuilder private func detailsSection(_ item: HubKit.LibraryItem) -> some View {
        let facts = DetailLines.details(item)
        if !facts.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                Text("Details")
                    .font(HubType.heading(22, weight: .semibold, relativeTo: .title2))
                    .foregroundStyle(Color.ink)
                Grid(alignment: .leadingFirstTextBaseline, horizontalSpacing: 16, verticalSpacing: 8) {
                    ForEach(facts, id: \.label) { fact in
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
        }
    }

    // MARK: Loading and saving

    private func load() async {
        status = StatusText.loading("the title", refreshing: item != nil)
        do {
            let response = try await model.hub.fetch(HubEndpoints.libraryItem(route.itemId), as: LibraryItemResponse.self)
            item = response.item
            status = StatusMessage("")
            if response.item.type == "series" { await loadSeasons() }
        } catch {
            if error.kind == .cancelled { return }
            status = StatusText.failed(error.message, kind: error.kind, hasData: item != nil)
        }
    }

    private func loadSeasons() async {
        guard let response = try? await model.hub.fetch(HubEndpoints.librarySeasons(seriesId: route.itemId),
                                                        as: LibraryItemList.self) else { return }
        seasons = response.items
        // The first real season; Specials only when there is nothing else.
        if seasonId.isEmpty || !seasons.contains(where: { $0.id == seasonId }) {
            seasonId = (seasons.first { $0.indexNumber > 0 } ?? seasons.first)?.id ?? ""
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

/// One episode in a season: its still with progress, "4. Title", length and
/// how far it was watched, and the start of its overview.
struct EpisodeRow: View {
    let episode: HubKit.LibraryItem

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Color.clear
                .frame(width: 160, height: 90)
                .overlay { ArtworkView(path: episode.thumb.isEmpty ? episode.poster : episode.thumb, width: 480) }
                .overlay(alignment: .bottom) {
                    ProgressStrip(progress: ResumeRules.showsWatched(played: episode.played, progress: episode.progress)
                                  ? 0 : episode.progress)
                }
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                .overlay(alignment: .topTrailing) {
                    WatchBadge(played: episode.played, progress: episode.progress, unplayedCount: 0, favorite: false)
                        .padding(5)
                }
            VStack(alignment: .leading, spacing: 3) {
                Text(DetailLines.episodeTitle(episode))
                    .font(HubType.body(15, weight: .semibold, relativeTo: .subheadline))
                    .foregroundStyle(Color.ink)
                    .lineLimit(2)
                let meta = DetailLines.episodeMeta(episode)
                if !meta.isEmpty {
                    Text(meta)
                        .font(HubType.body(13, relativeTo: .caption))
                        .foregroundStyle(Color.muted)
                }
                if !episode.overview.isEmpty {
                    Text(episode.overview)
                        .font(HubType.body(13, relativeTo: .caption))
                        .foregroundStyle(Color.muted)
                        .lineLimit(2)
                }
            }
            Spacer(minLength: 0)
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}
