import HubKit
import SwiftUI

/// Discover (the prototype's `pgDiscover`; Android `screens/discover/
/// DiscoverScreen`): Discover and Upcoming in a glass capsule, the search
/// beside it, then a featured title and Jellyseerr's rows, each paging on as
/// its end comes into view. Typing two letters turns the page into a grid of
/// results. A card in focus colours the page; a card you have opens its
/// library page, one you do not opens on its way in (`MediaHit.route`).
struct DiscoverView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.glassMetrics) private var metrics
    @Environment(\.openRoute) private var openRoute

    enum Tab: String { case discover, upcoming }

    @SceneStorage("discover.tab") private var tab: Tab = .discover
    @State private var rows: [DiscoverRow] = []
    @State private var status = StatusMessage("")
    @State private var loading = false
    @State private var paging = RowPaging(prefetchAhead: 6)
    @State private var query = ""
    @State private var lit: String?
    @State private var week = 0
    /// A card's Request, from its menu.
    @State private var requesting: MediaRoute?
    /// Debug builds: HUB_OPEN is applied once (see `applyDebugOpen`).
    @State private var debugOpened = false

    private var searchText: String { query.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var searching: Bool { tab == .discover && !searchText.isEmpty }
    private var featured: MediaHit? { rows.first.flatMap { DiscoverFeature.feature($0.items) } }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                controls
                    .padding(.horizontal, metrics.margin)
                    .padding(.top, 4)
                if tab == .upcoming {
                    UpcomingView(week: $week)
                } else if searchText.count >= 2 {
                    DiscoverSearchGrid(query: searchText)
                        .id(searchText)
                } else if searching {
                    Text("Type at least two characters.")
                        .font(HubType.body(15, relativeTo: .subheadline))
                        .foregroundStyle(.white.opacity(0.66))
                        .padding(.horizontal, metrics.margin)
                        .padding(.top, 20)
                } else {
                    browsing
                }
            }
            .padding(.bottom, 24)
        }
        .ambientArtwork(searching || tab == .upcoming ? "" : (lit ?? featured?.pageArtwork ?? ""))
        .refreshable { if tab == .discover && !searching { await load(force: true) } }
        .task { if rows.isEmpty && !loading { await load(force: false) } }
        .sheet(item: $requesting) { route in
            RequestSheet(key: route.key, fallbackTitle: route.title) { _ in }
        }
    }

    // MARK: Controls

    /// Discover and Upcoming, then the search (or, on Upcoming, its weeks):
    /// side by side where there is room, the second under the first on a phone.
    @ViewBuilder private var controls: some View {
        let tabs = GlassCapsulePicker(
            items: [GlassCapsulePicker<Tab>.Item(id: .discover, title: "Discover"),
                    GlassCapsulePicker<Tab>.Item(id: .upcoming, title: "Upcoming")],
            selection: tab) { chosen in tab = chosen }
            .fixedSize()
        let second = Group {
            if tab == .upcoming {
                ScrollView(.horizontal, showsIndicators: false) {
                    GlassCapsulePicker(items: [-1, 0, 1].map { offset in
                        GlassCapsulePicker<Int>.Item(id: offset, title: UpcomingPresentation.weekLabel(today: today, week: offset))
                    }, selection: week) { chosen in week = chosen }
                }
                .scrollClipDisabled()
            } else {
                GlassSearchField(placeholder: "Search films and series", query: $query)
            }
        }
        if metrics.compact {
            VStack(alignment: .leading, spacing: 10) {
                tabs
                second
            }
        } else {
            HStack(spacing: 10) {
                tabs
                second
                Spacer(minLength: 0)
            }
        }
    }

    private var today: String { UpcomingPresentation.today(now: .now, zone: .current) }

    // MARK: Rows

    @ViewBuilder private var browsing: some View {
        StatusLine(message: status) { Task { await load(force: true) } }
            .padding(.horizontal, metrics.margin)
            .padding(.top, 10)
        if let featured {
            NavigationLink(value: featured.route) {
                FeaturedCard(hit: model.requested.apply(featured))
            }
            .buttonStyle(GlassCardStyle())
            .previewsWhenFocused { lit = featured.pageArtwork }
            .padding(.horizontal, metrics.margin)
            .padding(.top, 16)
            .padding(.bottom, 6)
        }
        ForEach(Array(rows.enumerated()), id: \.element.id) { index, row in
            let items = index == 0 ? DiscoverFeature.shelf(row.items) : row.items
            CardRow(title: row.title) {
                ForEach(Array(items.enumerated()), id: \.element.media.key) { position, hit in
                    card(hit)
                        .frame(width: metrics.poster)
                        .onAppear { pageIfNeeded(row, lastVisible: position, count: items.count) }
                }
            }
        }
    }

    private func card(_ hit: MediaHit) -> some View {
        let shown = model.requested.apply(hit)
        return NavigationLink(value: hit.route) {
            DiscoverPoster(hit: shown)
        }
        .buttonStyle(GlassCardStyle())
        .previewsWhenFocused { lit = hit.pageArtwork }
        .contextMenu {
            if shown.canRequest {
                Button {
                    requesting = MediaRoute(key: hit.media.key, title: hit.media.title)
                } label: {
                    Label("Request", systemImage: "plus")
                }
            }
        }
    }

    // MARK: Loading

    private func load(force: Bool) async {
        guard !loading else { return }
        loading = true
        defer { loading = false }
        status = StatusText.loading("Discover", refreshing: !rows.isEmpty)
        do {
            let response = try await model.hub.fetch(HubEndpoints.discover, as: DiscoverResponse.self)
            rows = response.rows
            paging.clear()
            model.colors.want(rows.flatMap { $0.items.prefix(12).map(\.pageArtwork) })
            status = rows.isEmpty ? StatusMessage("Nothing to show yet.")
                : StatusText.caveat(response.cache, unavailable: response.partial.map(\.service))
            #if DEBUG
            applyDebugOpen()
            #endif
        } catch {
            if error.kind == .cancelled { return }
            status = StatusText.failed(error.message, kind: error.kind, hasData: !rows.isEmpty)
        }
    }

    #if DEBUG
    /// scripts/mac.sh opens a state for screenshots, once: HUB_OPEN=upcoming,
    /// search:<words>, request (the first title to request), moving (the
    /// first on its way), a media key, releases:<key>[@<season>], person:<id>.
    private func applyDebugOpen() {
        guard !debugOpened, let open = ProcessInfo.processInfo.environment["HUB_OPEN"], !open.isEmpty else { return }
        debugOpened = true
        if open == "upcoming" {
            tab = .upcoming
        } else if open.hasPrefix("search:") {
            query = String(open.dropFirst("search:".count))
        } else if open.hasPrefix("releases:") {
            let target = open.dropFirst("releases:".count).split(separator: "@").map(String.init)
            openRoute(.releases(ReleasesRoute(key: target[0], heading: "Releases",
                                              season: target.count > 1 ? Int(target[1]) : nil)))
        } else if open.hasPrefix("person:"), let id = Int(open.dropFirst("person:".count)) {
            openRoute(.person(PersonRoute(id: id, name: "")))
        } else {
            let all = rows.flatMap(\.items)
            let hit: MediaHit? = switch open {
            case "request": all.first { $0.canRequest }
            case "moving": all.first { ["processing", "downloading", "requested"].contains($0.availability) }
            default: all.first { $0.media.key == open }
            }
            let key = hit?.media.key ?? (open.hasPrefix("tmdb:") ? open : "")
            if !key.isEmpty { openRoute(.media(MediaRoute(key: key, title: hit?.media.title ?? ""))) }
        }
    }
    #endif

    /// Six cards from a row's end, its next page; the hub stops at page 500.
    private func pageIfNeeded(_ row: DiscoverRow, lastVisible: Int, count: Int) {
        guard let next = paging.next(row.id, page: row.page, totalPages: min(row.totalPages, 500),
                                     lastVisible: lastVisible, count: count) else { return }
        Task {
            do throws(HubFailure) {
                let response = try await model.hub.fetch(HubEndpoints.discoverRow(row.id, page: next), as: DiscoverResponse.self)
                guard let page = response.rows.first, let index = rows.firstIndex(where: { $0.id == row.id }) else {
                    paging.fail(row.id, page: next)
                    return
                }
                let known = Set(rows[index].items.map(\.media.key))
                rows[index].items += page.items.filter { !known.contains($0.media.key) }
                rows[index].page = page.page
                rows[index].totalPages = page.totalPages
                paging.complete(row.id, page: next, total: min(page.totalPages, 500))
            } catch {
                paging.fail(row.id, page: next)
            }
        }
    }
}

/// The first title of the first row, large (the prototype's `.feat`): its
/// picture beside where it is, its name, facts and story; on a phone the
/// words go under the picture.
struct FeaturedCard: View {
    let hit: MediaHit
    @Environment(\.glassMetrics) private var metrics
    @Environment(\.glassAccent) private var accent

    private static let corner: CGFloat = 26

    var body: some View {
        Group {
            if metrics.compact {
                VStack(alignment: .leading, spacing: 0) {
                    picture.aspectRatio(16 / 9, contentMode: .fit)
                    words
                }
            } else {
                // A phone held sideways has about 400 points of height: a
                // wider picture leaves the first row in view under it.
                FeaturedSplit(pictureRatio: metrics.short ? 2.8 : 2) {
                    picture
                    words
                }
            }
        }
        .glassPanel(RoundedRectangle(cornerRadius: Self.corner, style: .continuous))
        .clipShape(RoundedRectangle(cornerRadius: Self.corner, style: .continuous))
        .litRing(corner: Self.corner)
        .contentShape(RoundedRectangle(cornerRadius: Self.corner, style: .continuous))
        .accessibilityElement(children: .combine)
    }

    private var picture: some View {
        Color.clear
            .overlay { ArtworkView(path: hit.pageArtwork, width: 960) }
            .clipped()
    }

    private var words: some View {
        VStack(alignment: .leading, spacing: 9) {
            Text(kicker)
                .font(HubType.body(12.5, weight: .bold, relativeTo: .caption))
                .tracking(1.75)
                .lineLimit(1)
            Text(hit.media.title)
                .font(HubType.heading(metrics.heroTitle * 0.62, weight: .heavy))
                .foregroundStyle(.white)
                .lineLimit(2)
            let meta = DiscoverFeature.meta(hit)
            if !meta.isEmpty {
                Text(meta)
                    .font(HubType.body(15, relativeTo: .subheadline))
                    .foregroundStyle(.white.opacity(0.82))
            }
            if !hit.overview.isEmpty {
                Text(hit.overview)
                    .font(HubType.body(15, relativeTo: .body))
                    .foregroundStyle(.white.opacity(0.78))
                    .lineLimit(2)
            }
        }
        .multilineTextAlignment(.leading)
        .padding(.horizontal, metrics.small ? 18 : 26)
        .padding(.vertical, metrics.small ? 16 : 22)
    }

    /// "FEATURED · NOT IN YOUR LIBRARY": the lead in soft white, where it is in the accent.
    private var kicker: AttributedString {
        var lead = AttributedString("FEATURED · ")
        lead.foregroundColor = Color.white.opacity(0.72)
        var mark = AttributedString(DiscoverFeature.mark(hit.availability))
        mark.foregroundColor = accent.tint
        return lead + mark
    }
}

/// Search results as a grid of captioned posters (`.grid`), paging on 14
/// cards from the end, the closest titles first (the hub ranks them). It asks
/// once typing pauses.
struct DiscoverSearchGrid: View {
    @Environment(AppModel.self) private var model
    @Environment(\.glassMetrics) private var metrics
    let query: String

    @State private var items: [MediaHit] = []
    @State private var paging = PagedLoadState(prefetchAhead: 14)
    @State private var total = 0
    @State private var loaded = false
    @State private var status = StatusMessage("")
    @State private var lit: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if loaded && items.isEmpty && status.tone != .error {
                GlassEmpty(title: "Nothing called “\(query)”", detail: "Try another spelling, or search by the original title.")
                    .padding(.horizontal, metrics.margin)
                    .padding(.top, 24)
            } else {
                StatusLine(message: status) { Task { await loadNext(lastVisible: items.count, retry: true) } }
                    .padding(.horizontal, metrics.margin)
                    .padding(.top, 12)
            }
            LazyVGrid(columns: [GridItem(.adaptive(minimum: metrics.small ? 100 : 112, maximum: 180),
                                         spacing: metrics.small ? 12 : 18, alignment: .top)],
                      alignment: .leading, spacing: 20) {
                ForEach(Array(items.enumerated()), id: \.element.media.key) { index, hit in
                    NavigationLink(value: hit.route) {
                        DiscoverPoster(hit: model.requested.apply(hit), caption: true)
                    }
                    .buttonStyle(GlassCardStyle())
                    .previewsWhenFocused { lit = hit.pageArtwork }
                    .onAppear { Task { await loadNext(lastVisible: index, retry: false) } }
                }
            }
            .padding(.horizontal, metrics.margin)
            .padding(.top, 14)
        }
        .ambientArtwork(lit ?? items.first?.pageArtwork ?? "")
        .task {
            // Once typing pauses: every letter would otherwise be a search.
            try? await Task.sleep(for: .milliseconds(400))
            guard !Task.isCancelled, let first = paging.initial() else { return }
            await fetch(page: first)
        }
    }

    private func loadNext(lastVisible: Int, retry: Bool) async {
        let page = retry ? paging.retry() : paging.next(lastVisible: lastVisible, count: items.count)
        guard let page else { return }
        await fetch(page: page)
    }

    private func fetch(page: Int) async {
        if items.isEmpty { status = StatusMessage("Searching…") }
        do {
            let response = try await model.hub.fetch(HubEndpoints.search(query, page: page), as: SearchResponse.self)
            let known = Set(items.map(\.media.key))
            items += response.results.filter { !known.contains($0.media.key) }
            total = response.totalResults
            paging.complete(page: page, total: max(1, response.totalPages))
            loaded = true
            let count = total == 1 ? "1 result" : "\(total) results"
            status = items.isEmpty ? StatusMessage("") : StatusText.loaded(count + " for “\(query)”", cache: response.cache)
        } catch {
            if error.kind == .cancelled {
                paging.cancelLoading()
                return
            }
            paging.fail(page: page)
            loaded = true
            status = StatusText.failed(error.message, kind: error.kind, hasData: !items.isEmpty)
        }
    }
}

/// The featured card's two halves (`.feat`'s `1.15fr 1fr`): the picture 16:8
/// (or wider, by `pictureRatio`) at 1.15 parts of the width, the words in the
/// rest, centred down its height.
struct FeaturedSplit: Layout {
    var pictureRatio: CGFloat = 2
    private static let pictureShare: CGFloat = 1.15 / 2.15

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? 900
        let picture = width * Self.pictureShare
        let words = subviews.count > 1
            ? subviews[1].sizeThatFits(ProposedViewSize(width: width - picture, height: nil)).height : 0
        return CGSize(width: width, height: max(picture / pictureRatio, words))
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard subviews.count > 1 else { return }
        let picture = bounds.width * Self.pictureShare
        subviews[0].place(at: bounds.origin, proposal: ProposedViewSize(width: picture, height: bounds.height))
        let wordsWidth = bounds.width - picture
        let words = subviews[1].sizeThatFits(ProposedViewSize(width: wordsWidth, height: nil)).height
        subviews[1].place(at: CGPoint(x: bounds.minX + picture, y: bounds.minY + max(0, (bounds.height - words) / 2)),
                          proposal: ProposedViewSize(width: wordsWidth, height: words))
    }
}
