import HubKit
import SwiftUI

/// A series' release search, before it starts (Android `SeasonReleasePicker`
/// and `ReleaseTargetsScreen` on one page): its seasons as pills, the chosen
/// season to search whole, and each episode that has aired, to search alone.
struct ReleaseTargetsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.glassMetrics) private var metrics
    let route: ReleaseTargetsRoute

    @State private var season: Int?
    @State private var targets: ReleaseTargetsResponse?
    @State private var status = StatusMessage("")
    @State private var loads = 0

    private var chosen: Int {
        season ?? (route.seasons.first { $0.number > 0 } ?? route.seasons.first)?.number ?? 1
    }

    private var seasonTitle: String {
        if let targets, targets.season == chosen, !targets.seasonTitle.isEmpty { return targets.seasonTitle }
        if let option = route.seasons.first(where: { $0.number == chosen }) { return RequestDraft.seasonName(option) }
        return EpisodeLabel.season(chosen)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                PageHeading(title: "Find releases") {
                    Text(route.title + " · then choose the whole season or one aired episode")
                        .font(HubType.body(14, relativeTo: .subheadline))
                        .foregroundStyle(.white.opacity(0.66))
                }
                .padding(.horizontal, metrics.margin)
                .padding(.top, 4)
                if route.seasons.count > 1 {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(route.seasons) { option in
                                ChoicePill(title: TitleFacts.seasonPill(option), selected: option.number == chosen) {
                                    season = option.number
                                }
                            }
                        }
                        .padding(.horizontal, metrics.margin)
                        .padding(.top, 14)
                        .padding(.bottom, 2)
                    }
                }
                StatusLine(message: status) { loads += 1 }
                    .padding(.horizontal, metrics.margin)
                    .padding(.top, 12)
                seasonCard
                    .padding(.horizontal, metrics.margin)
                    .padding(.top, 14)
                if let targets, targets.season == chosen, !targets.episodes.isEmpty {
                    Text("Released episodes")
                        .font(HubType.body(metrics.rowTitle, weight: .bold, relativeTo: .title3))
                        .foregroundStyle(.white)
                        .padding(.horizontal, metrics.margin)
                        .padding(.top, 22)
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: metrics.small ? 220 : 270), spacing: metrics.gap,
                                                 alignment: .top)], alignment: .leading, spacing: 18) {
                        ForEach(targets.episodes) { target in
                            NavigationLink(value: AppRoute.releases(ReleasesRoute(
                                key: route.key, heading: ReleaseTargetLines.heading(series: route.title, target: target),
                                season: target.season, episode: target.episode))) {
                                TargetEpisodeCard(target: target)
                            }
                            .buttonStyle(GlassCardStyle())
                        }
                    }
                    .padding(.horizontal, metrics.margin)
                    .padding(.top, 12)
                }
            }
            .padding(.bottom, 28)
        }
        .ambientArtwork(targets?.seasonImage.isEmpty == false ? targets!.seasonImage : route.poster)
        .refreshable { loads += 1 }
        .task(id: "\(chosen)·\(loads)") { await load() }
    }

    /// The whole season (`Search the entire season`), with its poster.
    private var seasonCard: some View {
        NavigationLink(value: AppRoute.releases(ReleasesRoute(
            key: route.key, heading: ReleaseTargetLines.heading(series: route.title, seasonTitle: seasonTitle),
            season: chosen))) {
            HStack(spacing: 16) {
                ArtworkView(path: targets?.season == chosen && !(targets?.seasonImage.isEmpty ?? true)
                            ? targets!.seasonImage : route.poster, width: 240)
                    .frame(width: 76, height: 114)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                VStack(alignment: .leading, spacing: 6) {
                    Text(seasonTitle)
                        .font(HubType.heading(21, weight: .heavy, relativeTo: .title3))
                        .foregroundStyle(.white)
                    Label("Search the entire season", systemImage: "magnifyingglass")
                        .font(HubType.body(14, weight: .semibold, relativeTo: .subheadline))
                        .foregroundStyle(.white.opacity(0.75))
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right")
                    .foregroundStyle(.white.opacity(0.5))
            }
            .padding(12)
            .frame(maxWidth: 520, alignment: .leading)
            .glassPanel(RoundedRectangle(cornerRadius: 18, style: .continuous))
            .litRing(corner: 18)
            .contentShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        }
        .buttonStyle(GlassCardStyle())
    }

    private func load() async {
        let wanted = chosen
        status = StatusMessage("Loading released episodes…")
        do {
            let response = try await model.hub.fetch(HubEndpoints.releaseTargets(key: route.key, season: wanted),
                                                     as: ReleaseTargetsResponse.self)
            guard wanted == chosen else { return }
            targets = response
            status = StatusText.loaded(ReleaseTargetLines.status(aired: response.episodes.count),
                                       caveat: StatusText.caveat(response.cache, unavailable: response.partial.map(\.service)))
        } catch {
            if error.kind == .cancelled { return }
            status = StatusText.failed(error.message, kind: error.kind, hasData: targets != nil)
        }
    }
}

/// An aired episode to search for alone: its still, "S1E4 · Title", when it
/// aired and how long it is, and its story.
struct TargetEpisodeCard: View {
    let target: ReleaseEpisodeTarget
    @Environment(\.glassMetrics) private var metrics

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Color.clear
                .aspectRatio(16 / 9, contentMode: .fit)
                .overlay { ArtworkView(path: target.image, width: 480) }
                .clipShape(RoundedRectangle(cornerRadius: metrics.radius, style: .continuous))
                .litArtwork(corner: metrics.radius)
            CardCaption(title: ReleaseTargetLines.title(target), detail: ReleaseTargetLines.meta(target))
            if !target.overview.isEmpty {
                Text(target.overview)
                    .font(HubType.body(13, relativeTo: .caption))
                    .foregroundStyle(.white.opacity(0.64))
                    .lineLimit(2)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

/// An interactive search (Android `ReleasesScreen`): every indexer is asked,
/// which takes a while, and the releases come in the hub's order, those
/// Radarr or Sonarr would take first. A release is grabbed after a
/// confirmation; a rejected one only as an override, and one outside the
/// searched season or episode not at all.
struct ReleasesView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.glassMetrics) private var metrics
    let route: ReleasesRoute

    @State private var response: ReleasesResponse?
    @State private var status = StatusMessage("")
    @State private var searching = false
    @State private var searches = 0
    @State private var hideRejected = false
    @State private var confirming: Release?
    @State private var blocked: Release?
    @State private var grabbing = false

    private var shown: [Release] {
        let all = response?.releases ?? []
        return hideRejected ? all.filter { !$0.rejected } : all
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack(alignment: .top, spacing: 12) {
                    PageHeading(title: route.heading) {
                        HStack(spacing: 10) {
                            if searching { ProgressView().controlSize(.small).tint(.white) }
                            StatusLine(message: status)
                        }
                    }
                }
                .padding(.horizontal, metrics.margin)
                .padding(.top, 4)
                HStack(spacing: 10) {
                    Button {
                        searches += 1
                    } label: {
                        Label("Search again", systemImage: "arrow.clockwise")
                    }
                    .buttonStyle(GlassControlStyle())
                    .disabled(searching || grabbing)
                    Button {
                        hideRejected.toggle()
                    } label: {
                        Label(hideRejected ? "Show rejected" : "Hide rejected",
                              systemImage: hideRejected ? "eye" : "eye.slash")
                    }
                    .buttonStyle(GlassControlStyle())
                    .disabled(response == nil)
                }
                .padding(.horizontal, metrics.margin)
                .padding(.top, 14)
                LazyVStack(spacing: 8) {
                    ForEach(shown) { release in
                        Button {
                            if release.scopeBlocked { blocked = release } else { confirming = release }
                        } label: {
                            ReleaseRow(release: release)
                        }
                        .buttonStyle(GlassCardStyle())
                        .disabled(grabbing)
                    }
                }
                .padding(.horizontal, metrics.margin)
                .padding(.top, 14)
            }
            .padding(.bottom, 28)
        }
        .ambientArtwork("")
        .task(id: searches) { await search() }
        .alert(blocked.map { _ in ReleaseLines.blockedTitle(season: route.season ?? 0, episode: route.episode ?? 0) } ?? "",
               isPresented: Binding(get: { blocked != nil }, set: { if !$0 { blocked = nil } })) {
            Button("Choose another release", role: .cancel) { blocked = nil }
        } message: {
            Text(ReleaseLines.blockedDetail)
        }
        .confirmationDialog(confirming?.title ?? "", isPresented: Binding(get: { confirming != nil },
                                                                          set: { if !$0 { confirming = nil } }),
                            titleVisibility: .visible, presenting: confirming) { release in
            Button("Cancel", role: .cancel) {}
            if release.rejected {
                Button("Grab anyway", role: .destructive) { grab(release) }
            } else {
                Button("Grab this release") { grab(release) }
            }
        } message: { release in
            Text(ReleaseLines.confirmDetail(release)
                 + (release.rejected ? "\nOverrides what \(release.indexer) and your profile decided" : "\n" + release.indexer))
        }
    }

    /// One search when the page opens, and one for each Search again (the hub
    /// keeps the last for five minutes).
    private func search() async {
        guard !searching else { return }
        searching = true
        defer { searching = false }
        response = nil
        status = StatusMessage(ReleaseLines.searching)
        do {
            let found = try await model.hub.fetch(HubEndpoints.releases(key: route.key, season: route.season,
                                                                        episode: route.episode), as: ReleasesResponse.self)
            response = found
            let line = ReleaseLines.status(total: found.releases.count, accepted: found.accepted)
            let caveat = StatusText.caveat(found.cache)
            status = found.accepted == 0 && !found.releases.isEmpty
                ? StatusMessage([line, caveat.text].filter { !$0.isEmpty }.joined(separator: " · "), tone: .warning)
                : StatusText.loaded(line, caveat: caveat)
        } catch {
            if error.kind == .cancelled { return }
            status = StatusText.failed(error.message, kind: error.kind, hasData: false, canRetry: false)
        }
    }

    private func grab(_ release: Release) {
        confirming = nil
        guard !grabbing else { return }
        grabbing = true
        status = StatusMessage("Sending to the download client…")
        Task {
            defer { grabbing = false }
            do throws(HubFailure) {
                let reply = try await model.hub.fetch(
                    HubEndpoints.grab(key: route.key, GrabBody(releaseId: release.id, season: route.season,
                                                               episode: route.episode)), as: GrabReply.self)
                status = StatusMessage(ReleaseLines.grabbed(reply, release: release))
            } catch {
                status = StatusText.failed(error.message, kind: error.kind, hasData: true, canRetry: false)
            }
        }
    }
}

/// One release (`ReleasesScreen`'s row): a square with its resolution, green
/// when Radarr or Sonarr would take it; its name; its figures; and their
/// reasons for refusing it, in red.
struct ReleaseRow: View {
    let release: Release

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Text(ReleaseLines.tile(release.quality))
                .font(HubType.chrome(11.5, weight: .bold))
                .lineLimit(1)
                .minimumScaleFactor(0.7)
                .foregroundStyle(release.rejected ? Color.white.opacity(0.8) : .white)
                .frame(width: 52, height: 44)
                .background(release.rejected ? Color.white.opacity(0.12) : Color(argb: 0xE61C_965C),
                            in: RoundedRectangle(cornerRadius: 11, style: .continuous))
            VStack(alignment: .leading, spacing: 4) {
                Text(release.title)
                    .font(HubType.body(14, weight: .semibold, relativeTo: .subheadline))
                    .foregroundStyle(.white)
                    .lineLimit(1)
                    .truncationMode(.middle)
                Text(ReleaseLines.figures(release))
                    .font(HubType.body(12.5, relativeTo: .caption))
                    .foregroundStyle(.white.opacity(0.62))
                    .lineLimit(2)
                let reasons = ReleaseLines.rejections(release)
                if !reasons.isEmpty {
                    Text(reasons)
                        .font(HubType.body(12.5, relativeTo: .caption))
                        .foregroundStyle(Color(argb: 0xFFFF_7A7A))
                        .lineLimit(2)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassPanel(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .litRing(corner: 14)
        .contentShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .accessibilityElement(children: .combine)
    }
}
