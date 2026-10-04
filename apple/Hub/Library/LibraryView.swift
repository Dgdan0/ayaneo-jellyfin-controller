import HubKit
import SwiftUI

/// A library opened from the Library page, or Favourites.
struct FolderRoute: Hashable {
    let id: String
    let name: String

    static let favourites = FolderRoute(id: "favourites", name: "Favourites")
}

/// Each library's order, kept between launches (Android keeps one per folder).
enum LibrarySorts {
    static func sort(for folderId: String) -> SortPreference {
        SortPreference.decode(UserDefaults.standard.string(forKey: key(folderId)), fallback: "name")
    }

    static func set(_ value: SortPreference, for folderId: String) {
        UserDefaults.standard.set(value.encoded, forKey: key(folderId))
    }

    private static func key(_ folderId: String) -> String { "library.sort." + folderId }
}

/// The Jellyfin library's first page (the prototype's `pgLibrary`): search
/// and Favourites, then a glass tile for each library, its own artwork behind
/// a fan of three of its posters. Typing two letters turns the page into
/// search results. Android's `screens/library/LibraryScreen`.
struct LibraryView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.glassMetrics) private var metrics
    @Environment(\.openRoute) private var openRoute

    @State private var folders: [LibraryFolder] = []
    /// Debug builds: HUB_OPEN=Anime (a library's name) opens that library, once.
    @State private var debugOpened = false
    /// Three posters a library shows on its tile, and how many titles it has:
    /// the top of its first page, in its own order.
    @State private var fans: [String: [String]] = [:]
    @State private var totals: [String: Int] = [:]
    @State private var status = StatusMessage("")
    @State private var query = ""
    /// The tile a pointer rests on or focus is on, whose picture the page takes.
    @State private var lit: String?

    private var searchText: String { query.trimmingCharacters(in: .whitespacesAndNewlines) }

    private var summary: String {
        guard !folders.isEmpty else { return "" }
        let count = "\(folders.count) librar" + (folders.count == 1 ? "y" : "ies")
        let titles = totals.values.reduce(0, +)
        return titles > 0 ? count + " · \(titles) titles" : count
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 10) {
                    LibrarySearchField(query: $query)
                    NavigationLink(value: AppRoute.folder(.favourites)) {
                        Label("Favourites", systemImage: "star")
                    }
                    .buttonStyle(GlassControlStyle())
                }
                .padding(.horizontal, metrics.margin)
                .padding(.top, 4)
                if searchText.count >= 2 {
                    LibraryGrid(source: .search(searchText))
                        .id(searchText)
                } else if !searchText.isEmpty {
                    Text("Type at least two characters")
                        .font(HubType.body(15, relativeTo: .subheadline))
                        .foregroundStyle(.white.opacity(0.66))
                        .padding(.horizontal, metrics.margin)
                        .padding(.top, 20)
                } else {
                    PageHeading(title: "Your libraries") {
                        if status.text.isEmpty {
                            Text(summary)
                                .font(HubType.body(14, relativeTo: .subheadline))
                                .foregroundStyle(.white.opacity(0.66))
                        } else {
                            StatusLine(message: status) { Task { await loadFolders() } }
                        }
                    }
                    .padding(.horizontal, metrics.margin)
                    .padding(.top, 18)
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 300), spacing: 18)], spacing: 18) {
                        ForEach(folders) { folder in
                            NavigationLink(value: AppRoute.folder(FolderRoute(id: folder.id, name: folder.name))) {
                                LibraryTile(folder: folder, posters: fans[folder.id] ?? [], background: art(of: folder))
                            }
                            .buttonStyle(GlassCardStyle())
                            .previewsWhenFocused { lit = art(of: folder) }
                        }
                    }
                    .padding(.horizontal, metrics.margin)
                    .padding(.top, 14)
                    .padding(.bottom, 30)
                }
            }
        }
        .ambientArtwork(searchText.count >= 2 ? "" : (lit ?? folders.first.map(art(of:)) ?? ""))
        .refreshable { await loadFolders() }
        .task(id: model.userId) { await loadFolders() }
    }

    /// A tile's picture: the library's own (its folder art, or a title chosen
    /// for the day), else the middle poster of its fan.
    private func art(of folder: LibraryFolder) -> String {
        if !folder.image.isEmpty { return folder.image }
        let fan = fans[folder.id] ?? []
        return fan.count > 1 ? fan[1] : (fan.first ?? "")
    }

    private func loadFolders() async {
        if folders.isEmpty { status = StatusText.loading("libraries", refreshing: false) }
        do {
            let response = try await model.hub.fetch(HubEndpoints.library, as: LibraryResponse.self)
            folders = response.views
            status = folders.isEmpty ? StatusMessage("No Jellyfin libraries were found") : StatusMessage("")
            #if DEBUG
            if let name = ProcessInfo.processInfo.environment["HUB_OPEN"], !debugOpened,
               let folder = folders.first(where: { $0.name.caseInsensitiveCompare(name) == .orderedSame }) {
                debugOpened = true
                openRoute(.folder(FolderRoute(id: folder.id, name: folder.name)))
            }
            #endif
        } catch {
            if error.kind == .cancelled { return }
            status = StatusText.failed(error.message, kind: error.kind, hasData: !folders.isEmpty)
            return
        }
        await loadFans()
    }

    /// The first page of every library at once, each in its own order: the
    /// hub keeps them for a minute, so opening one is answered from its cache.
    private func loadFans() async {
        let hub = model.hub
        let requests = folders.map { folder -> (String, HubRequest) in
            let sort = LibrarySorts.sort(for: folder.id)
            return (folder.id, HubEndpoints.libraryItems(viewId: folder.id, sort: sort.field, order: sort.order))
        }
        await withTaskGroup(of: (String, [String], Int)?.self) { group in
            for (id, request) in requests {
                group.addTask {
                    guard let page = try? await hub.fetch(request, as: LibraryPage.self) else { return nil }
                    return (id, LibraryFan.posters(page), page.total)
                }
            }
            for await result in group {
                guard let result else { continue }
                fans[result.0] = result.1
                totals[result.0] = result.2
            }
        }
    }
}

/// A library on the Library page (`.lib`): its picture blurred into colour,
/// three of its posters fanned on the right, and a glass label with what kind
/// of library it is and its name.
struct LibraryTile: View {
    let folder: LibraryFolder
    let posters: [String]
    let background: String
    @Environment(\.glassMetrics) private var metrics

    private static let corner: CGFloat = 24

    private var kind: String {
        switch folder.kind {
        case "movies": "Movie library"
        case "tvshows": "TV library"
        default: "Library"
        }
    }

    var body: some View {
        Color.clear
            .aspectRatio(metrics.compact ? 2 : 1.6, contentMode: .fit)
            .overlay {
                ArtworkView(path: background, width: 360, placeholder: .white.opacity(0.06))
                    .blur(radius: 18, opaque: true)
                    .saturation(1.2)
                    .colorMultiply(Color(white: 0.8))
            }
            .overlay { GeometryReader { fan(in: $0.size) } }
            .overlay(alignment: .bottom) { label }
            .clipShape(RoundedRectangle(cornerRadius: Self.corner, style: .continuous))
            .litArtwork(corner: Self.corner)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("\(folder.name), \(kind)")
    }

    /// The prototype's `.stack`: 66% of the tile's height, 9% from its top and
    /// 5% from its right, the three turned 7°, −1° and −8°, the last on top.
    private func fan(in size: CGSize) -> some View {
        let height = size.height * 0.66
        let width = height * 1.3
        let poster = height * 2 / 3
        let right = size.width * 0.05
        let top = size.height * 0.09
        let steps: [CGFloat] = [0, 0.26, 0.52]
        let turns: [Double] = [7, -1, -8]
        return ZStack(alignment: .topLeading) {
            ForEach(Array(posters.prefix(3).enumerated()), id: \.offset) { index, path in
                ArtworkView(path: path, width: 240)
                    .frame(width: poster, height: height)
                    .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
                    .shadow(color: .black.opacity(0.45), radius: 10, x: -8, y: 10)
                    .rotationEffect(.degrees(turns[index]))
                    .position(x: size.width - right - steps[index] * width - poster / 2, y: top + height / 2)
            }
        }
        .frame(width: size.width, height: size.height)
    }

    private var label: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(kind.uppercased())
                .font(HubType.body(11, weight: .bold, relativeTo: .caption2))
                .tracking(1.3)
                .foregroundStyle(.white.opacity(0.72))
            Text(folder.name)
                .font(HubType.heading(23, weight: .heavy, relativeTo: .title2))
                .foregroundStyle(.white)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .glassPanel(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .padding(12)
    }
}

/// One library's titles (the prototype's `pgFolder`): the libraries in a glass
/// capsule with Favourites, Sort and its direction, then the poster grid with
/// unwatched counts or a tick. Another library from the capsule takes this
/// page's place, so Back still returns to the Library page.
struct FolderView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openRoute) private var navigation
    @Environment(\.glassMetrics) private var metrics
    let route: FolderRoute

    @State private var folders: [LibraryFolder] = []
    @State private var sort = SortPreference.forField("name")
    @State private var refreshes = 0

    private var source: GridSource {
        route == .favourites ? .favourites : .folder(id: route.id, name: route.name, sort: sort)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                controls
                    .padding(.horizontal, metrics.margin)
                    .padding(.top, 4)
                LibraryGrid(source: source)
                    .id("\(refreshes)·\(String(describing: source))")
            }
        }
        .refreshable { refreshes += 1 }
        .onAppear { sort = LibrarySorts.sort(for: route.id) }
        .task(id: model.userId) { await loadFolders() }
    }

    @ViewBuilder private var controls: some View {
        let capsule = GlassCapsulePicker(
            items: folders.map { GlassCapsulePicker<String>.Item(id: $0.id, title: $0.name) }
                + [GlassCapsulePicker<String>.Item(id: FolderRoute.favourites.id, title: "Favourites", systemImage: "star.fill")],
            selection: route.id) { id in
                let name = folders.first(where: { $0.id == id })?.name ?? FolderRoute.favourites.name
                navigation.replace(.folder(FolderRoute(id: id, name: name)))
            }
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 10) {
                capsule
                Spacer(minLength: 0)
                if route != .favourites { sortControls }
            }
            VStack(alignment: .leading, spacing: 10) {
                ScrollView(.horizontal, showsIndicators: false) { capsule }
                if route != .favourites { sortControls }
            }
        }
    }

    /// Android's `LibrarySortControls`: the field as a menu ("Name ▾") and the
    /// direction as a control that flips on one press ("↑ A to Z").
    private var sortControls: some View {
        HStack(spacing: 10) {
            Menu {
                Picker("Sort by", selection: Binding(get: { sort.field }, set: { setSort(SortPreference.forField($0)) })) {
                    ForEach(SortPreference.mediaFields, id: \.id) { field in
                        Text(field.label).tag(field.id)
                    }
                }
            } label: {
                Label(SortPreference.label(for: sort.field) + " ▾", systemImage: "line.3.horizontal.decrease")
            }
            .menuStyle(.button)
            .buttonStyle(GlassControlStyle())
            Button {
                setSort(SortPreference(field: sort.field, ascending: !sort.ascending))
            } label: {
                Label(sort.directionLabel, systemImage: sort.ascending ? "arrow.up" : "arrow.down")
            }
            .buttonStyle(GlassControlStyle())
            .accessibilityHint("Reverses the sort")
        }
        .fixedSize()
    }

    private func setSort(_ value: SortPreference) {
        sort = value
        LibrarySorts.set(value, for: route.id)
    }

    private func loadFolders() async {
        guard let response = try? await model.hub.fetch(HubEndpoints.library, as: LibraryResponse.self) else { return }
        folders = response.views
    }
}

/// The glass search field (the prototype's `.search`): the shell hides the
/// system bar that `.searchable` lives in.
struct LibrarySearchField: View {
    @Binding var query: String

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(.white.opacity(0.8))
            TextField("Search your Jellyfin library", text: $query)
                .textFieldStyle(.plain)
                .font(HubType.body(15))
                .autocorrectionDisabled()
                #if os(iOS)
                .textInputAutocapitalization(.never)
                .submitLabel(.search)
                #endif
            if !query.isEmpty {
                Button {
                    query = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.white.opacity(0.6))
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Clear the search")
            }
        }
        .padding(.horizontal, 16)
        .frame(height: 44)
        .frame(maxWidth: 460)
        .glassPanel(Capsule())
    }
}

/// What a grid lists. Equal sources share a grid; a new one starts from page 1.
enum GridSource: Hashable {
    case folder(id: String, name: String, sort: SortPreference)
    case favourites
    case search(String)

    func request(page: Int) -> HubRequest {
        switch self {
        case .folder(let id, _, let sort): HubEndpoints.libraryItems(viewId: id, page: page, sort: sort.field, order: sort.order)
        case .favourites: HubEndpoints.libraryFavorites(page: page)
        case .search(let query): HubEndpoints.librarySearch(query, page: page)
        }
    }

    /// Android's grid status lines: "179 titles", "12 of 40 matches".
    func summary(loaded: Int, total: Int) -> String {
        switch self {
        case .folder:
            return total == 0 ? "This library is empty." : "\(total) titles"
        case .favourites:
            if total == 0 { return "No favourites yet. Star a title on its page." }
            return loaded < total ? "\(loaded) of \(total) favourites" : "\(total) favourites"
        case .search:
            if total == 0 { return "No Jellyfin matches." }
            return loaded < total ? "\(loaded) of \(total) matches" : "\(total) matches"
        }
    }

    var loadingName: String {
        switch self {
        case .folder(_, let name, _): name
        case .favourites: "favourites"
        case .search: "matches"
        }
    }
}

/// A poster grid (`.grid`) that loads the next page of 60 as its end comes
/// into view, with its count line over it. It sits in its page's own scroll
/// view, under the page's controls, and the poster in focus tints the page.
struct LibraryGrid: View {
    @Environment(AppModel.self) private var model
    @Environment(\.glassMetrics) private var metrics
    let source: GridSource

    @State private var items: [MediaHit] = []
    @State private var page = 0
    @State private var totalPages = 1
    @State private var total = 0
    @State private var loading = false
    @State private var status = StatusMessage("")
    @State private var lit: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            StatusLine(message: status) { Task { await loadNext() } }
                .padding(.horizontal, metrics.margin)
                .padding(.top, 10)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: metrics.compact ? 100 : 112, maximum: 180),
                                         spacing: metrics.compact ? 12 : 18, alignment: .top)],
                      alignment: .leading, spacing: 20) {
                ForEach(Array(items.enumerated()), id: \.element.id) { index, hit in
                    NavigationLink(value: AppRoute.title(TitleRoute(itemId: hit.jellyfinItemId, title: hit.media.title))) {
                        PosterCard(hit: hit)
                    }
                    .buttonStyle(GlassCardStyle())
                    .disabled(hit.jellyfinItemId.isEmpty)
                    .previewsWhenFocused { lit = hit.media.poster }
                    .onAppear {
                        // Six cards before the end, as on Android.
                        if index >= items.count - 6 { Task { await loadNext() } }
                    }
                }
            }
            .padding(.horizontal, metrics.margin)
            .padding(.top, 14)
            .padding(.bottom, 26)
        }
        .ambientArtwork(lit ?? items.first?.media.poster ?? "")
        .task(id: model.userId) { await reload() }
    }

    private func reload() async {
        items = []
        page = 0
        totalPages = 1
        total = 0
        lit = nil
        await loadNext()
    }

    private func loadNext() async {
        guard !loading, page < totalPages else { return }
        loading = true
        defer { loading = false }
        status = items.isEmpty ? StatusText.loading(source.loadingName, refreshing: false) : StatusMessage("Loading more…")
        do {
            let next = try await model.hub.fetch(source.request(page: page + 1), as: LibraryPage.self)
            let known = Set(items.map(\.id))
            items += next.items.filter { !known.contains($0.id) }
            page = next.page
            totalPages = max(1, next.totalPages)
            total = next.total
            status = StatusMessage(source.summary(loaded: items.count, total: total))
        } catch {
            if error.kind == .cancelled { return }
            status = StatusText.failed(error.message, kind: error.kind, hasData: !items.isEmpty)
        }
    }
}
