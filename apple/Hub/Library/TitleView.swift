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
    @Environment(\.horizontalSizeClass) private var sizeClass
    let route: TitleRoute

    @State private var item: HubKit.LibraryItem?
    @State private var status = StatusMessage("")
    @State private var saving = false
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
    /// Counts up each time the episodes start over, so an answer to an earlier ask is not taken for this one's.
    @State private var episodeGeneration = 0
    @State private var offline = OfflineLibrary.shared
    /// A series' downloads: the corner of each card, the season button, the choices and select mode (#48).
    @State private var downloads = SeriesDownloadsModel()
    /// The choices behind the round download button: a side panel, or a sheet on a phone.
    @State private var panelOpen = false
    /// Counts up to scroll to the episodes, as select mode starts.
    @State private var scrollBelow = 0
    @State private var removing: OfflineRemoval?

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
        TitlePage(backdrop: FadedArtwork.title(backdropPath), scrollToBelow: scrollBelow) { page in
            header(page)
        } below: {
            StatusLine(message: status) { Task { await load() } }
                .padding(.horizontal, metrics.margin)
                .padding(.top, 8)
            if downloads.selecting {
                // Select mode: the top is Cancel, how many, and Select season.
                SelectTopBar(count: downloads.ticked.count, seasonAllTicked: downloads.seasonAllTicked(seasonId),
                             cancel: { withAnimation(.snappy) { downloads.cancelSelecting() } },
                             toggleSeason: { downloads.toggleSeason(seasonId) })
                    .padding(.horizontal, metrics.margin)
                    .padding(.top, 14)
                tabContent
            } else if item != nil, !tabs.isEmpty {
                UnderlineTabs(tabs: tabs, selection: Binding(get: { tab }, set: { tab = $0; tabChosen = true }))
                    .padding(.horizontal, metrics.margin)
                    .padding(.top, 14)
                tabContent
            }
        }
        .ambientArtwork(backdropPath)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if downloads.selecting {
                SelectBottomBar(total: SeriesDownloads.total(downloads.ticked, among: downloads.episodes),
                                bytes: SeriesDownloads.size(downloads.tickedEpisodes),
                                canDownload: !downloads.ticked.isEmpty) {
                    withAnimation(.snappy) { downloads.downloadTicked() }
                }
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        // A download started shows how far it is, and how full the device is, until a few seconds after it ends.
        .downloadBar(scope: route.itemId, suppressed: downloads.selecting)
        .overlay { sidePanel }
        .sheet(isPresented: Binding(get: { panelOpen && sizeClass == .compact }, set: { if !$0 { panelOpen = false } })) {
            panel(inSheet: true)
                .presentationBackground { GlassSheetFill() }
                .presentationCornerRadius(32)
                #if os(iOS)
                .presentationDetents([.fraction(0.92), .large])
                #endif
        }
        .offlineRemoval($removing)
        #if DEBUG
        // For the UI tests: which of this series' episodes are on the device, as the ends of their ids ("e1,s2e2").
        // The strip builds only the cards at the screen, so a card's own corner cannot always be read.
        .overlay(alignment: .topLeading) {
            Text("[" + offline.rows(scope: route.itemId).filter { $0.state == .complete }
                .map { String($0.itemId.dropFirst(33)) }.sorted().joined(separator: ",") + "]")
                .font(.system(size: 1))
                .opacity(0.01)
                .allowsHitTesting(false)
                .accessibilityIdentifier("downloaded-episodes")
        }
        #endif
        .onChange(of: downloads.problem) { _, problem in
            if let problem { status = StatusMessage(problem, tone: .error) }
        }
        #if DEBUG
        // HUB_SERIES_DOWNLOADS, steps joined by +: keepready turns Keep ready on (3), panel opens the choices,
        // select[:e1,s2e2] starts select mode with those episodes ticked.
        .task(id: downloads.isLoaded) {
            guard downloads.isLoaded, let launch = ProcessInfo.processInfo.environment["HUB_SERIES_DOWNLOADS"] else { return }
            for step in launch.split(separator: "+") {
                if step == "keepready" {
                    downloads.setKeepReady(KeepReady.defaultCount)
                } else if step == "reload" {
                    // As a pull down does: the page reads the hub again.
                    try? await Task.sleep(for: .seconds(2))
                    await load()
                } else if step == "panel" {
                    setPanel(true)
                } else if step.hasPrefix("select") {
                    startSelecting()
                    let suffixes = step.dropFirst("select:".count).split(separator: ",")
                    downloads.ticked = Set(suffixes.map { route.itemId + "-" + $0 })
                }
            }
        }
        #endif
        .refreshable { await load() }
        .task(id: model.userId) { await load() }
        .onChange(of: playbackClosed) { _, _ in Task { await refreshAfterPlayback() } }
        // Something was deleted from the server below this page (#34): an
        // episode of this series, and what is left is read again.
        .onChange(of: model.libraryChanges) { _, _ in Task { await load() } }
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

    /// The prototype's `.dhead`, as `TitleHeader` draws it.
    private func header(_ page: GlassMetrics) -> some View {
        TitleHeader(title: item?.title ?? route.title, originalTitle: item?.originalTitle ?? "", page: page,
                    facts: item.map(DetailLines.facts) ?? "", state: item.map(DetailLines.state) ?? "",
                    overview: item?.overview ?? "") {
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
        } actions: {
            if let item { actions(item).padding(.top, 4) }
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
            if item.type != "season" {
                DownloadButton(item: item, size: metrics.small ? 42 : 46,
                               openChoices: item.type == "series" ? { setPanel(true) } : nil)
            }
            more(item)
        }
    }

    /// The round "…" (Android's More actions): subtitles for a film or an
    /// episode, a release search for a series, and deleting from the server
    /// last, in its own words (#34).
    private func more(_ item: HubKit.LibraryItem) -> some View {
        Menu {
            if LibraryUpkeep.offersSubtitles(item) {
                Button {
                    openRoute(.subtitles(SubtitlesRoute(itemId: item.id, title: LibraryUpkeep.pageTitle(item))))
                } label: {
                    Label("Find subtitles", systemImage: "captions.bubble")
                }
            }
            if LibraryUpkeep.offersReleases(item) {
                Button {
                    findRelease(item, season: seasons.first { $0.id == seasonId }?.indexNumber)
                } label: {
                    Label("Find release", systemImage: "magnifyingglass")
                }
            }
            if LibraryUpkeep.offersDeleting(item) {
                Divider()
                Button(role: .destructive) {
                    openRoute(.removal(RemovalRoute(kind: "video", id: item.id, title: LibraryUpkeep.pageTitle(item))))
                } label: {
                    Label(RemovalLines.heading, systemImage: "trash")
                }
            }
        } label: {
            let size: CGFloat = metrics.small ? 42 : 46
            Image(systemName: "ellipsis")
                .font(.system(size: size * 0.4, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: size, height: size)
                .glassPanel(Circle())
                .contentShape(Circle())
        }
        .menuStyle(.button)
        .buttonStyle(.plain)
        .accessibilityLabel("More actions")
        .accessibilityIdentifier("title-more")
    }

    /// A series' seasons and aired episodes to search for releases, on the
    /// season given; a series the hub could not name on TMDB says so instead.
    private func findRelease(_ series: HubKit.LibraryItem, season: Int? = nil) {
        guard let key = LibraryUpkeep.releaseKey(series) else {
            status = StatusMessage(LibraryUpkeep.noMatchWords, tone: .warning)
            return
        }
        let options = seasons.map { SeasonOption(number: $0.indexNumber, name: $0.title, year: $0.year, image: $0.poster) }
        openRoute(.releaseTargets(ReleaseTargetsRoute(key: key, title: series.title, seasons: options, poster: series.poster,
                                                      startSeason: season)))
    }

    /// One episode's releases, without choosing it again on the targets page.
    private func findRelease(of episode: HubKit.LibraryItem, in series: HubKit.LibraryItem) {
        guard let key = LibraryUpkeep.releaseKey(series) else {
            status = StatusMessage(LibraryUpkeep.noMatchWords, tone: .warning)
            return
        }
        openRoute(.releases(ReleasesRoute(key: key, heading: LibraryUpkeep.pageTitle(episode),
                                          season: episode.seasonNumber, episode: episode.indexNumber)))
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
            SeasonPills(seasons.map { SeasonPill(id: $0.id, title: seasonTitle($0), selected: $0.id == seasonId,
                                                 ring: downloads.selecting ? ringFor($0) : nil) },
                        choose: { id in
                            guard id != seasonId else { return }
                            seasonId = id
                            Task { await loadEpisodes(reset: true) }
                        }, menu: { id in
                            if let season = seasons.first(where: { $0.id == id }) { seasonMenu(season) }
                        }, trailing: {
                            seasonButton
                        })
            if episodes.isEmpty && !loadingEpisodes && !seasons.isEmpty {
                Text("No episodes in this season yet.")
                    .font(HubType.body(15, relativeTo: .subheadline))
                    .foregroundStyle(.white.opacity(0.66))
                    .padding(.horizontal, metrics.margin)
                    .padding(.top, 14)
            }
            // An episode plays, as the prototype's and Android's do; its own page is in the menu.
            // In select mode a tap ticks it instead.
            EpisodeStrip(episodes, reveal: target?.item.id,
                         play: { episode in
                             if downloads.selecting {
                                 downloads.toggle(episode.id)
                             } else {
                                 play(request(for: episode, mode: DetailLines.startMode(episode)))
                             }
                         },
                         hint: { episode in
                             downloads.selecting ? "Ticks the episode to download"
                                 : (episode.positionSeconds > 0 ? "Resumes the episode" : "Plays the episode")
                         },
                         identifier: { "episode-" + $0.id },
                         reached: { index in
                             if index >= episodes.count - 3 { Task { await loadEpisodes(reset: false) } }
                         },
                         actions: { cardActions($0) },
                         card: { episode in
                             EpisodeCard(episode: episode, upNext: episode.id == target?.item.id,
                                         selected: downloads.selecting && downloads.ticked.contains(episode.id),
                                         dimmed: downloads.selecting && !downloads.tickable.contains(episode.id))
                         },
                         menu: { episode in episodeMenu(episode) },
                         overlay: { episode in corner(episode) })
        }
    }

    /// The button after the season pills: "Season 2 · 4.9 GB", or "Season 2 on this iPad".
    @ViewBuilder private var seasonButton: some View {
        if !downloads.isLoaded, !downloads.selecting, !seasons.isEmpty, sizeClass == .compact {
            // Where it will be, so the episodes below do not jump down when the hub's listing arrives.
            Color.clear.frame(height: 36)
        } else if downloads.isLoaded, !downloads.selecting, let season = seasons.first(where: { $0.id == seasonId }) {
            let name = season.title.isEmpty ? EpisodeLabel.season(season.indexNumber) : season.title
            SeasonDownloadButton(words: downloads.seasonButton(season.id, name: name),
                                 done: downloads.missing(season: season.id).isEmpty) {
                downloads.downloadSeason(season.id)
            }
        }
    }

    /// A card's corner: its download, as a button of its own; a tick circle in select mode.
    @ViewBuilder private func corner(_ episode: HubKit.LibraryItem) -> some View {
        if downloads.selecting {
            SelectTick(ticked: downloads.ticked.contains(episode.id), possible: downloads.tickable.contains(episode.id))
        } else if downloads.canDownload(episode.id) {
            DownloadBadgeButton(id: episode.id, badge: downloads.badge(episode.id)) { downloads.tap(episode.id) }
        }
    }

    /// What a card offers on a hold, as plain actions: the menu, VoiceOver and a controller read this list.
    private func cardActions(_ episode: HubKit.LibraryItem) -> [CardAction] {
        guard !downloads.selecting else { return [] }
        var out: [CardAction] = []
        switch downloads.badge(episode.id) {
        case .none where downloads.canDownload(episode.id):
            out.append(CardAction("download", "Download", systemImage: "arrow.down.circle") { downloads.start([episode.id]) })
        case .waiting, .moving:
            out.append(CardAction("stop", "Stop download", systemImage: "xmark.circle") { downloads.tap(episode.id) })
        case .failed:
            out.append(CardAction("retry", "Try download again", systemImage: "arrow.clockwise") { downloads.tap(episode.id) })
        case .downloaded:
            out.append(CardAction("remove", "Remove download", systemImage: "trash", destructive: true) { askRemove(episode) })
        default: break
        }
        if downloads.isLoaded {
            out.append(CardAction("select", "Select episodes", systemImage: "checkmark.circle") { startSelecting(ticking: episode.id) })
        }
        return out
    }

    private func askRemove(_ episode: HubKit.LibraryItem) {
        guard let row = offline.row(forItem: episode.id) else { return }
        removing = OfflineRemoval(id: row.id, title: "Remove \(episode.title)?",
                                  detail: "\(Fmt.bytes(row.totalBytes)) gone from this device. The library keeps it on the PC.") {
            offline.remove(row.id)
        }
    }

    // MARK: The choices and select mode

    private func setPanel(_ open: Bool) {
        withAnimation(.snappy) { panelOpen = open }
    }

    /// Select mode on the episodes, scrolled into view; `id` is ticked as it starts.
    private func startSelecting(ticking id: String? = nil) {
        tab = .episodes
        tabChosen = true
        withAnimation(.snappy) {
            panelOpen = false
            downloads.beginSelecting(ticking: id)
        }
        scrollBelow += 1
    }

    private func panel(inSheet: Bool) -> some View {
        SeriesDownloadPanel(downloads: downloads, close: { setPanel(false) },
                            chooseEpisodes: { startSelecting() })
    }

    /// On an iPad or a Mac: a panel at the right, over a scrim that closes it.
    @ViewBuilder private var sidePanel: some View {
        if panelOpen && sizeClass != .compact {
            ZStack(alignment: .trailing) {
                Color.black.opacity(0.4)
                    .ignoresSafeArea()
                    .onTapGesture { setPanel(false) }
                    .accessibilityHidden(true)
                panel(inSheet: false)
                    .frame(width: 400)
                    .frame(maxHeight: .infinity)
                    .background { GlassSheetFill() }
                    .clipShape(UnevenRoundedRectangle(topLeadingRadius: 28, bottomLeadingRadius: 28, style: .continuous))
                    .shadow(color: .black.opacity(0.4), radius: 24)
                    .transition(.move(edge: .trailing))
            }
            .transition(.opacity)
        }
    }

    /// The ring on a season's pill in select mode: how much of it is ticked.
    private func ringFor(_ season: HubKit.LibraryItem) -> Double? {
        let ticks = downloads.ticks(season.id)
        return ticks.of > 0 ? Double(ticks.ticked) / Double(ticks.of) : nil
    }

    /// A season's pill, held: its episodes to download, and its releases to find.
    @ViewBuilder private func seasonMenu(_ season: HubKit.LibraryItem) -> some View {
        if item != nil {
            // Every episode of the season not here yet, at once: the shortcut to the season button (#43, #48).
            Button {
                downloads.downloadSeason(season.id)
            } label: {
                Label("Download season", systemImage: "arrow.down.circle")
            }
            .disabled(!downloads.isLoaded)
        }
        if let item, LibraryUpkeep.offersReleases(item) {
            Button {
                findRelease(item, season: season.indexNumber)
            } label: {
                Label("Find release for \(season.title.isEmpty ? EpisodeLabel.season(season.indexNumber) : season.title)",
                      systemImage: "magnifyingglass")
            }
        }
    }

    @ViewBuilder private func episodeMenu(_ episode: HubKit.LibraryItem) -> some View {
        // Where it is: on this device, in the download mark (not a tick, which is watched).
        if downloads.badge(episode.id) == .downloaded {
            Button {} label: { Label("On this device", systemImage: DownloadedMark.symbol) }
                .disabled(true)
        }
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
        CardActionButtons(actions: cardActions(episode))
        Button {
            openRoute(.title(TitleRoute(itemId: episode.id, title: episode.title)))
        } label: {
            Label("Episode details", systemImage: "info.circle")
        }
        if LibraryUpkeep.offersSubtitles(episode) {
            Button {
                openRoute(.subtitles(SubtitlesRoute(itemId: episode.id, title: LibraryUpkeep.pageTitle(episode))))
            } label: {
                Label("Find subtitles", systemImage: "captions.bubble")
            }
        }
        if let item, LibraryUpkeep.offersReleases(item) {
            Button {
                findRelease(of: episode, in: item)
            } label: {
                Label("Find release", systemImage: "magnifyingglass")
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

    /// The prototype's `.people`: a round portrait for each, name and part
    /// under it. One the hub names on TMDB opens their films and series, as
    /// the request-side page's cast does (#27); one it cannot name offers nothing.
    private func castTab(_ item: HubKit.LibraryItem) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            LazyHStack(alignment: .top, spacing: 18) {
                ForEach(DetailLines.cast(item)) { person in
                    if person.tmdbId > 0 {
                        NavigationLink(value: AppRoute.person(PersonRoute(id: person.tmdbId, name: person.name))) {
                            PersonCard(person: person)
                        }
                        .buttonStyle(.plain)
                        .accessibilityHint("Opens their films and series")
                    } else {
                        PersonCard(person: person)
                    }
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
        if downloads.selecting {
            let ticks = downloads.ticks(season.id)
            return ticks.of > 0 ? name + " · \(ticks.ticked)/\(ticks.of)" : name
        }
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
                downloads.setSeries(id: response.item.id, title: response.item.title)
                async let downloadsLoaded: Void = downloads.load(model.hub, seriesId: response.item.id)
                await loadTarget()
                await loadSeasons()
                await downloadsLoaded
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
        // What is watched now says what Keep ready does, and the sizes of what is left.
        await downloads.load(model.hub, seriesId: response.item.id)
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
        await loadEpisodes(reset: true, keepVisible: true)
    }

    /// `keepVisible`: the page is read again, so the cards shown stay until the new ones arrive (a pull
    /// that is let go halfway never leaves the strip empty); a season chosen starts from nothing.
    private func loadEpisodes(reset: Bool, keepVisible: Bool = false) async {
        if reset {
            // Whatever was on its way for the season before is let go.
            episodeGeneration += 1
            loadingEpisodes = false
            if !keepVisible { episodes = [] }
            episodePage = 0
            episodePages = 1
        }
        guard !seasonId.isEmpty, !loadingEpisodes, episodePage < episodePages else { return }
        let generation = episodeGeneration
        loadingEpisodes = true
        defer { if generation == episodeGeneration { loadingEpisodes = false } }
        let season = seasonId
        let ask = HubEndpoints.libraryEpisodes(seriesId: route.itemId, seasonId: season, page: episodePage + 1)
        var answer = try? await model.hub.fetch(ask, as: LibraryItemList.self)
        // An ask shared with a page read that was let go can fail for both: once more, if this one was not let go.
        if answer == nil, !Task.isCancelled { answer = try? await model.hub.fetch(ask, as: LibraryItemList.self) }
        guard let response = answer, season == seasonId, generation == episodeGeneration else { return }
        if episodePage == 0 { episodes = response.items } else { episodes += response.items }
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
