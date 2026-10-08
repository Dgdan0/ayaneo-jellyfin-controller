import HubKit
import SwiftUI
import UniformTypeIdentifiers
#if os(iOS)
import UIKit
#endif

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
/// a fan of three of its posters, which the hub chooses (#13). Typing two letters turns the page into
/// search results. Android's `screens/library/LibraryScreen`.
///
/// The tiles come in the profile's order, which every device shares (#15).
/// Holding a tile (a secondary click on the Mac) or Arrange starts arranging:
/// the tiles wiggle, each shows its grip, and one dragged over another takes
/// its place; Done ends it.
struct LibraryView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.glassMetrics) private var metrics
    @Environment(\.openRoute) private var openRoute

    @State private var folders: [LibraryFolder] = []
    @State private var order = "name"
    @State private var editor = LibraryOrderEditor(side: .media)
    @State private var arranging = false
    @State private var dragging: String?
    /// Debug builds: HUB_OPEN=Anime (a library's name) opens that library, once.
    @State private var debugOpened = false
    @State private var status = StatusMessage("")
    @State private var query = ""
    /// The tile a pointer rests on or focus is on, whose picture the page takes.
    @State private var lit: String?

    private var searchText: String { query.trimmingCharacters(in: .whitespacesAndNewlines) }

    private var summary: String {
        guard !folders.isEmpty else { return "" }
        let count = "\(folders.count) librar" + (folders.count == 1 ? "y" : "ies")
        // The hub counts each library's films and series (#13); an older hub does not.
        let titles = folders.compactMap(\.total).reduce(0, +)
        return titles > 0 ? count + (titles == 1 ? " · 1 title" : " · \(titles) titles") : count
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 10) {
                    GlassSearchField(placeholder: "Search your Jellyfin library", query: $query, pad: "search")
                    NavigationLink(value: AppRoute.folder(.favourites)) {
                        Label("Favourites", systemImage: "star")
                    }
                    .buttonStyle(GlassControlStyle())
                    .padFocusable("favourites") { openRoute(.folder(.favourites)) }
                }
                .padGroup("top", .row, members: ["search", "favourites"], prefix: false)
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
                    HStack(alignment: .top, spacing: 12) {
                        PageHeading(title: "Your libraries") {
                            if !editor.notice.isEmpty {
                                Text(editor.notice)
                                    .font(HubType.body(14, weight: .semibold, relativeTo: .subheadline))
                                    .foregroundStyle(Color.pending)
                            } else if arranging {
                                Text("Drag a library to move it. Every device shows this order.")
                                    .font(HubType.body(14, relativeTo: .subheadline))
                                    .foregroundStyle(.white.opacity(0.66))
                            } else if status.text.isEmpty {
                                Text(summary)
                                    .font(HubType.body(14, relativeTo: .subheadline))
                                    .foregroundStyle(.white.opacity(0.66))
                            } else {
                                StatusLine(message: status) { Task { await loadFolders() } }
                            }
                        }
                        if folders.count > 1 {
                            Button {
                                arranging ? finishArranging() : startArranging()
                            } label: {
                                if arranging {
                                    Label("Done", systemImage: "checkmark")
                                } else {
                                    Label {
                                        Text("Arrange")
                                    } icon: {
                                        GripMark(dot: 2.6)
                                    }
                                }
                            }
                            .buttonStyle(GlassControlStyle())
                            .accessibilityIdentifier("arrange-libraries")
                            .padFocusable("arrange") { arranging ? finishArranging() : startArranging() }
                        }
                    }
                    .padding(.horizontal, metrics.margin)
                    .padding(.top, 18)
                    // Phone sizes three across a phone turned sideways, one
                    // across one held upright.
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: metrics.small ? 230 : 300), spacing: metrics.gap)],
                              spacing: metrics.gap) {
                        ForEach(Array(shownFolders.enumerated()), id: \.element.id) { index, folder in
                            tile(folder, index: index)
                        }
                    }
                    // A controller goes through the libraries as a grid (#46).
                    .padGroup("libraries", .grid(columns: 0), members: arranging ? [] : shownFolders.map(\.id))
                    .padding(.horizontal, metrics.margin)
                    .padding(.top, 14)
                    .padding(.bottom, 30)
                    .onDrop(of: [.text], delegate: LibraryDropFallback(dragging: $dragging, editor: editor, model: model))
                }
            }
            // Down the page (#46): the search and Favourites, Arrange, the libraries or the matches.
            .padGroup("page", .column, members: searchText.count >= 2 ? ["top", "grid"]
                      : ["top"] + (folders.count > 1 ? ["arrange"] : []) + ["libraries"], prefix: false)
        }
        .padPage("media-libraries")
        .ambientArtwork(searchText.count >= 2 ? "" : (lit ?? folders.first.map(art(of:)) ?? ""))
        .refreshable { await loadFolders() }
        .task(id: "\(model.userId)·\(model.libraryOrderChanges)") { await loadFolders() }
        .onDisappear { if arranging { finishArranging() } }
        .onChange(of: searchText.isEmpty) { _, empty in if !empty && arranging { finishArranging() } }
    }

    /// While arranging, the order on screen is the editor's: a drag shows
    /// before the hub has it.
    private var shownFolders: [LibraryFolder] {
        guard arranging else { return folders }
        var byId: [String: LibraryFolder] = [:]
        for folder in folders where byId[folder.id] == nil { byId[folder.id] = folder }
        return editor.libraries.compactMap { byId[$0.id] }
    }

    @ViewBuilder private func tile(_ folder: LibraryFolder, index: Int) -> some View {
        let tile = LibraryTile(folder: folder, posters: LibraryFan.posters(folder), background: art(of: folder),
                               arranging: arranging)
        if arranging {
            tile
                .jiggle(dragging != folder.id, index: index)
                .opacity(dragging == folder.id ? 0.55 : 1)
                .arrangeable(folder.id, dragging: $dragging, editor: editor, model: model)
                .accessibilityAddTraits(.isButton)
                .accessibilityHint("Drag to move this library")
                .accessibilityAction(named: "Move earlier") { editor.step(folder.id, by: -1, model: model) }
                .accessibilityAction(named: "Move later") { editor.step(folder.id, by: 1, model: model) }
        } else {
            NavigationLink(value: AppRoute.folder(FolderRoute(id: folder.id, name: folder.name))) { tile }
                .buttonStyle(GlassCardStyle())
                .previewsWhenFocused { lit = art(of: folder) }
                #if os(iOS)
                // Holding a tile starts arranging, as holding an app's icon does.
                .simultaneousGesture(LongPressGesture(minimumDuration: 0.5).onEnded { _ in startArranging() })
                #else
                .contextMenu {
                    Button("Arrange libraries", action: startArranging)
                }
                #endif
                .accessibilityAction(named: "Arrange libraries", startArranging)
                .padFocusable(folder.id, ring: .card) { openRoute(.folder(FolderRoute(id: folder.id, name: folder.name))) }
        }
    }

    private func startArranging() {
        guard !arranging, folders.count > 1 else { return }
        editor.adopt(folders.map { ArrangedLibrary(id: $0.id, title: $0.name, kind: LibraryKind.label($0.kind),
                                                   art: art(of: $0)) },
                     custom: LibraryOrder.isCustom(order))
        #if os(iOS)
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        #endif
        withAnimation(.easeOut(duration: 0.2)) { arranging = true }
    }

    private func finishArranging() {
        editor.commit(model: model)
        dragging = nil
        withAnimation(.easeOut(duration: 0.2)) { arranging = false }
        // The hub's own list again, in the order it now keeps.
        Task { await loadFolders() }
    }

    /// A tile's picture: the library's own (its folder art, or a title chosen
    /// for the day), else the middle poster of its fan.
    private func art(of folder: LibraryFolder) -> String {
        if !folder.image.isEmpty { return folder.image }
        let fan = LibraryFan.posters(folder)
        return fan.count > 1 ? fan[1] : (fan.first ?? "")
    }

    private func loadFolders() async {
        if folders.isEmpty { status = StatusText.loading("libraries", refreshing: false) }
        do {
            let response = try await model.hub.fetch(HubEndpoints.library, as: LibraryResponse.self)
            folders = response.views
            order = response.order
            status = folders.isEmpty ? StatusMessage("No Jellyfin libraries were found") : StatusMessage("")
            #if DEBUG
            // scripts/mac.sh: HUB_SHEET=arrange starts arranging, once; arrange-move
            // also drops the last library first and saves, as a drag would.
            if let sheet = ProcessInfo.processInfo.environment["HUB_SHEET"], sheet.hasPrefix("arrange"), !debugOpened {
                debugOpened = true
                startArranging()
                if sheet == "arrange-move", let last = folders.last, let first = folders.first, last.id != first.id {
                    editor.preview(last.id, over: first.id)
                    editor.commit(model: model)
                }
            }
            if let name = ProcessInfo.processInfo.environment["HUB_OPEN"], !debugOpened,
               let folder = folders.first(where: { $0.name.caseInsensitiveCompare(name) == .orderedSame }) {
                debugOpened = true
                openRoute(.folder(FolderRoute(id: folder.id, name: folder.name)))
            }
            #endif
        } catch {
            if error.kind == .cancelled { return }
            status = StatusText.failed(error.message, kind: error.kind, hasData: !folders.isEmpty)
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
    /// While the libraries are arranged: the grip in the tile's corner.
    var arranging = false
    /// What kind of library it is, when not Jellyfin's: a reading library's (#25).
    var kindLabel: String?
    @Environment(\.glassMetrics) private var metrics

    private static let corner: CGFloat = 24

    private var kind: String { kindLabel ?? LibraryKind.label(folder.kind) }

    var body: some View {
        Color.clear
            .aspectRatio(metrics.small ? 2 : 1.6, contentMode: .fit)
            .overlay {
                ArtworkView(path: background, width: 360, placeholder: .white.opacity(0.06))
                    .blur(radius: 18, opaque: true)
                    .saturation(1.2)
                    .colorMultiply(Color(white: 0.8))
            }
            .overlay { GeometryReader { fan(in: $0.size) } }
            .overlay(alignment: .bottom) { label }
            .overlay(alignment: .topLeading) {
                if arranging {
                    GripMark()
                        .frame(width: 38, height: 38)
                        .glassPanel(Circle())
                        .padding(12)
                        .transition(.scale.combined(with: .opacity))
                }
            }
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
/// capsule with Favourites, the round search, Sort and its direction, then the
/// poster grid with unwatched counts or a tick. Another library from the
/// capsule takes this page's place, so Back still returns to the Library page.
///
/// The search looks inside the library on show (#14): two letters turn the
/// grid into its matches. Favourites is not a library, so its search, like the
/// Library page's, looks through everything.
struct FolderView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openRoute) private var navigation
    @Environment(\.glassMetrics) private var metrics
    let route: FolderRoute

    @State private var folders: [LibraryFolder] = []
    @State private var sort = SortPreference.forField("name")
    @State private var refreshes = 0
    @State private var searching = false
    @State private var query = ""
    /// Debug builds: HUB_SHEET=search:<words> opens the search with them, once.
    @State private var debugSearched = false

    private var source: GridSource {
        route == .favourites ? .favourites : .folder(id: route.id, name: route.name, sort: sort)
    }

    private var searchText: String { query.trimmingCharacters(in: .whitespacesAndNewlines) }

    /// The library the search looks in: none on Favourites.
    private var searchSource: GridSource {
        route == .favourites ? .search(searchText) : .search(searchText, viewId: route.id, library: route.name)
    }

    private var searchPlaceholder: String {
        route == .favourites ? "Search your Jellyfin library" : "Search \(route.name)"
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                controls
                    .padding(.horizontal, metrics.margin)
                    .padding(.top, 4)
                if searching {
                    GlassSearchField(placeholder: searchPlaceholder, query: $query, autofocus: !debugSearched, pad: "search")
                        .padding(.horizontal, metrics.margin)
                        .padding(.top, 12)
                }
                if searching && searchText.count >= 2 {
                    LibraryGrid(source: searchSource)
                        .id("\(refreshes)·\(String(describing: searchSource))")
                } else if searching && !searchText.isEmpty {
                    Text("Type at least two characters")
                        .font(HubType.body(15, relativeTo: .subheadline))
                        .foregroundStyle(.white.opacity(0.66))
                        .padding(.horizontal, metrics.margin)
                        .padding(.top, 20)
                } else {
                    LibraryGrid(source: source)
                        .id("\(refreshes)·\(String(describing: source))")
                }
            }
            // A controller goes down the page (#46): the libraries, the controls, the search, the grid.
            .padGroup("folder", .column, members: ["libraries", "controls"] + (searching ? ["search"] : []) + ["grid"],
                      prefix: false)
        }
        .padPage("folder:\(route.id)")
        .refreshable { refreshes += 1 }
        .onAppear {
            sort = LibrarySorts.sort(for: route.id)
            #if DEBUG
            // scripts/mac.sh: HUB_SHEET=search:the opens this page's search with "the".
            if !debugSearched, let sheet = ProcessInfo.processInfo.environment["HUB_SHEET"], sheet.hasPrefix("search:") {
                debugSearched = true
                searching = true
                query = String(sheet.dropFirst("search:".count))
            }
            #endif
        }
        // The capsule follows the libraries' order, as the Library page does (#15).
        .task(id: "\(model.userId)·\(model.libraryOrderChanges)") { await loadFolders() }
    }

    /// The round search: open, it takes the keyboard; pressed again, it closes
    /// and the library's titles come back.
    private var searchButton: some View {
        GlassRoundButton(systemImage: "magnifyingglass", label: searchPlaceholder, on: searching, size: 42,
                         pad: "search-button") {
            withAnimation(.easeInOut(duration: 0.2)) {
                searching.toggle()
                if !searching { query = "" }
            }
        }
    }

    @ViewBuilder private var controls: some View {
        let capsule = GlassCapsulePicker(
            items: folders.map { GlassCapsulePicker<String>.Item(id: $0.id, title: $0.name) }
                + [GlassCapsulePicker<String>.Item(id: FolderRoute.favourites.id, title: "Favourites", systemImage: "star.fill")],
            selection: route.id, pad: "libraries") { id in
                let name = folders.first(where: { $0.id == id })?.name ?? FolderRoute.favourites.name
                navigation.replace(.folder(FolderRoute(id: id, name: name)))
            }
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 10) {
                capsule
                Spacer(minLength: 0)
                searchButton
                if route != .favourites { sortControls }
            }
            VStack(alignment: .leading, spacing: 10) {
                ScrollView(.horizontal, showsIndicators: false) { capsule }
                HStack(spacing: 10) {
                    searchButton
                    if route != .favourites { sortControls }
                }
            }
        }
        // The search and the order: a row after the libraries.
        .padGroup("controls", .row, members: ["search-button"] + (route == .favourites ? [] : ["sort-field", "sort-direction"]),
                  prefix: false)
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
            // A menu cannot be opened for a controller: Ⓐ shows the fields as rows the ring walks (#46).
            .padFocusable("sort-field") {
                PadFocusCenter.shared.present(PadMenu(title: "Sort by", choices: SortPreference.mediaFields.map { field in
                    PadChoice(id: field.id, title: field.label, checked: field.id == sort.field) {
                        setSort(SortPreference.forField(field.id))
                    }
                }))
            }
            Button {
                setSort(SortPreference(field: sort.field, ascending: !sort.ascending))
            } label: {
                Label(sort.directionLabel, systemImage: sort.ascending ? "arrow.up" : "arrow.down")
            }
            .buttonStyle(GlassControlStyle())
            .accessibilityHint("Reverses the sort")
            .padFocusable("sort-direction") { setSort(SortPreference(field: sort.field, ascending: !sort.ascending)) }
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

/// What a grid lists. Equal sources share a grid; a new one starts from page 1.
enum GridSource: Hashable {
    case folder(id: String, name: String, sort: SortPreference)
    case favourites
    /// Everything, or with a `viewId` only that library (#14), which the
    /// count line names.
    case search(String, viewId: String = "", library: String = "")

    func request(page: Int) -> HubRequest {
        switch self {
        case .folder(let id, _, let sort): HubEndpoints.libraryItems(viewId: id, page: page, sort: sort.field, order: sort.order)
        case .favourites: HubEndpoints.libraryFavorites(page: page)
        case .search(let query, let viewId, _): HubEndpoints.librarySearch(query, page: page, viewId: viewId)
        }
    }

    /// Android's grid status lines: "179 titles", "12 of 40 matches",
    /// "2 matches in Anime".
    func summary(loaded: Int, total: Int) -> String {
        switch self {
        case .folder:
            return total == 0 ? "This library is empty." : "\(total) titles"
        case .favourites:
            if total == 0 { return "No favourites yet. Star a title on its page." }
            return loaded < total ? "\(loaded) of \(total) favourites" : "\(total) favourites"
        case .search(_, _, let library):
            if total == 0 { return library.isEmpty ? "No Jellyfin matches." : "No matches in \(library)." }
            let count = loaded < total ? "\(loaded) of \(total) matches" : (total == 1 ? "1 match" : "\(total) matches")
            return library.isEmpty ? count : count + " in " + library
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
    @Environment(\.openRoute) private var openRoute
    let source: GridSource
    /// Debug builds: HUB_SHEET=first opens the first grid's first title, once a launch.
    @MainActor private static var debugOpenedFirst = false

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
            LazyVGrid(columns: [GridItem(.adaptive(minimum: metrics.small ? 100 : 112, maximum: 180),
                                         spacing: metrics.small ? 12 : 18, alignment: .top)],
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
                    .padFocusable(hit.jellyfinItemId.isEmpty ? nil : hit.id, ring: .card) {
                        openRoute(.title(TitleRoute(itemId: hit.jellyfinItemId, title: hit.media.title)))
                    }
                }
            }
            // A controller goes through the titles as a grid (#46).
            .padGroup("grid", .grid(columns: 0), members: items.filter { !$0.jellyfinItemId.isEmpty }.map(\.id))
            .padding(.horizontal, metrics.margin)
            .padding(.top, 14)
            .padding(.bottom, 26)
        }
        .ambientArtwork(lit ?? items.first?.media.poster ?? "")
        // A title deleted from the server (#34) is read out of the grid.
        .task(id: "\(model.userId)·\(model.libraryChanges)") { await reload() }
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
            #if DEBUG
            // scripts/mac.sh: HUB_OPEN=Shows HUB_SHEET=first opens Shows' first title.
            if !Self.debugOpenedFirst, ProcessInfo.processInfo.environment["HUB_SHEET"] == "first",
               let first = items.first, !first.jellyfinItemId.isEmpty {
                Self.debugOpenedFirst = true
                openRoute(.title(TitleRoute(itemId: first.jellyfinItemId, title: first.media.title)))
            }
            #endif
        } catch {
            if error.kind == .cancelled { return }
            status = StatusText.failed(error.message, kind: error.kind, hasData: !items.isEmpty)
        }
    }
}
