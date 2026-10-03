import HubKit
import SwiftUI

/// The Jellyfin library: a pill per folder plus Favourites, a poster grid
/// sorted on the hub, and search. Android's `screens/library/LibraryScreen`.
struct LibraryView: View {
    @Environment(AppModel.self) private var model
    /// The folder last open, kept between launches (Android's `KEY_LAST_LIBRARY`).
    @AppStorage("library.last") private var lastFolder = ""

    @State private var folders: [LibraryFolder] = []
    @State private var status = StatusMessage("")
    @State private var query = ""
    @State private var sorts: [String: SortPreference] = [:]

    private static let favourites = "favourites"

    private var selectedId: String {
        if lastFolder == Self.favourites || folders.contains(where: { $0.id == lastFolder }) { return lastFolder }
        return folders.first?.id ?? ""
    }

    /// What the grid shows: a search while one is typed, else the chosen place.
    private var source: GridSource? {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.count >= 2 { return .search(trimmed) }
        if selectedId == Self.favourites { return .favourites }
        guard let folder = folders.first(where: { $0.id == selectedId }) else { return nil }
        return .folder(id: folder.id, name: folder.name, sort: sort(for: folder.id))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if query.trimmingCharacters(in: .whitespaces).isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(folders) { folder in
                            ChoicePill(title: folder.name, selected: folder.id == selectedId) { lastFolder = folder.id }
                        }
                        if !folders.isEmpty {
                            ChoicePill(title: "★ Favourites", selected: selectedId == Self.favourites) {
                                lastFolder = Self.favourites
                            }
                        }
                    }
                    .padding(.horizontal, 20)
                    .padding(.vertical, 10)
                }
            } else if query.trimmingCharacters(in: .whitespaces).count < 2 {
                Text("Type at least two characters")
                    .font(HubType.body(15, relativeTo: .subheadline))
                    .foregroundStyle(Color.muted)
                    .padding(20)
            }
            StatusLine(message: status) { Task { await loadFolders() } }
                .padding(.horizontal, 20)
            if let source {
                LibraryGrid(source: source)
                    .id(source)
            } else {
                Spacer()
            }
        }
        .background(Color.surface)
        .navigationTitle("Library")
        .searchable(text: $query, prompt: "Search your Jellyfin library")
        .toolbar {
            if case .folder(let id, _, let current) = source {
                ToolbarItemGroup(placement: .primaryAction) {
                    Menu {
                        Picker("Sort by", selection: Binding(
                            get: { current.field },
                            set: { setSort(SortPreference.forField($0), for: id) })) {
                            ForEach(SortPreference.mediaFields, id: \.id) { field in
                                Text(field.label).tag(field.id)
                            }
                        }
                    } label: {
                        Label(SortPreference.label(for: current.field), systemImage: "line.3.horizontal.decrease")
                    }
                    Button {
                        setSort(SortPreference(field: current.field, ascending: !current.ascending), for: id)
                    } label: {
                        Label(current.directionLabel, systemImage: current.ascending ? "arrow.up" : "arrow.down")
                            .labelStyle(.titleAndIcon)
                    }
                    .help("Sort direction")
                }
            }
        }
        .navigationDestination(for: TitleRoute.self) { TitleView(route: $0) }
        .task(id: model.userId) { await loadFolders() }
    }

    private func sort(for folderId: String) -> SortPreference {
        sorts[folderId] ?? SortPreference.decode(UserDefaults.standard.string(forKey: "library.sort." + folderId),
                                                 fallback: "name")
    }

    private func setSort(_ value: SortPreference, for folderId: String) {
        sorts[folderId] = value
        UserDefaults.standard.set(value.encoded, forKey: "library.sort." + folderId)
    }

    private func loadFolders() async {
        if folders.isEmpty { status = StatusText.loading("libraries", refreshing: false) }
        do {
            let response = try await model.hub.fetch(HubEndpoints.library, as: LibraryResponse.self)
            folders = response.views
            status = folders.isEmpty ? StatusMessage("No Jellyfin libraries were found") : StatusMessage("")
        } catch {
            if error.kind == .cancelled { return }
            status = StatusText.failed(error.message, kind: error.kind, hasData: !folders.isEmpty)
        }
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

/// A poster grid that loads the next page of 60 as its end comes into view.
struct LibraryGrid: View {
    @Environment(AppModel.self) private var model
    let source: GridSource

    @State private var items: [MediaHit] = []
    @State private var page = 0
    @State private var totalPages = 1
    @State private var total = 0
    @State private var loading = false
    @State private var status = StatusMessage("")

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                StatusLine(message: status) { Task { await loadNext() } }
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 104, maximum: 170), spacing: 14, alignment: .top)],
                          alignment: .leading, spacing: 20) {
                    ForEach(Array(items.enumerated()), id: \.element.id) { index, hit in
                        NavigationLink(value: TitleRoute(itemId: hit.jellyfinItemId, title: hit.media.title)) {
                            PosterCard(hit: hit)
                        }
                        .buttonStyle(.plain)
                        .disabled(hit.jellyfinItemId.isEmpty)
                        .onAppear {
                            // Six cards before the end, as on Android.
                            if index >= items.count - 6 { Task { await loadNext() } }
                        }
                    }
                }
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
        }
        .refreshable { await reload() }
        .task(id: model.userId) { await reload() }
    }

    private func reload() async {
        items = []
        page = 0
        totalPages = 1
        total = 0
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
