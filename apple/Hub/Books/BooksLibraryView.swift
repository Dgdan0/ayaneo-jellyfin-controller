import HubKit
import SwiftUI
#if os(iOS)
import UIKit
#endif

/// The Books side's Library (#25; the prototype's `pgBLibrary`): "Your reading
/// libraries", Storyteller's and Kavita's, as tiles in the profile's own order
/// (#15), each its cover fanned on its picture. Arranged as the media side's
/// are: hold a tile (a secondary click on the Mac) or Arrange, drag, Done.
struct BooksLibraryView: View {
    @Environment(AppModel.self) private var model
    @Environment(BooksModel.self) private var books
    @Environment(\.glassMetrics) private var metrics

    @State private var libraries: [ReadingLibrary] = []
    @State private var order = "name"
    @State private var status = StatusMessage("")
    @State private var editor = LibraryOrderEditor(side: .books)
    @State private var arranging = false
    @State private var dragging: String?
    @State private var lit: String?
    @Environment(\.openRoute) private var openRoute
    /// Debug builds: HUB_OPEN=Manga (a library's name) opens that library, once.
    @State private var debugOpened = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack(alignment: .top, spacing: 12) {
                    PageHeading(title: "Your reading libraries") {
                        if !editor.notice.isEmpty {
                            Text(editor.notice)
                                .font(HubType.body(14, weight: .semibold, relativeTo: .subheadline))
                                .foregroundStyle(Color.pending)
                        } else if arranging {
                            Text("Drag a library to move it. Every device shows this order.")
                                .font(HubType.body(14, relativeTo: .subheadline))
                                .foregroundStyle(.white.opacity(0.66))
                        } else if status.text.isEmpty {
                            Text(ReadingLibraryTiles.summary(libraries))
                                .font(HubType.body(14, relativeTo: .subheadline))
                                .foregroundStyle(.white.opacity(0.66))
                        } else {
                            StatusLine(message: status) { Task { await load() } }
                        }
                    }
                    if libraries.count > 1 {
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
                        .padFocusable("arrange") { arranging ? finishArranging() : startArranging() }
                    }
                }
                .padding(.horizontal, metrics.margin)
                .padding(.top, 4)
                let kavita = arranging ? nil : libraries.first(where: { $0.source == "kavita" })
                LazyVGrid(columns: [GridItem(.adaptive(minimum: metrics.small ? 230 : 300), spacing: metrics.gap)],
                          spacing: metrics.gap) {
                    ForEach(Array(shown.enumerated()), id: \.element.id) { index, library in
                        tile(library, index: index)
                    }
                    if let kavita {
                        readingLists(kavita)
                    }
                }
                // A controller goes through the libraries as a grid (#46).
                .padGroup("libraries", .grid(columns: 0),
                          members: shown.map(\.id) + (kavita == nil ? [] : ["reading-lists"]))
                .padding(.horizontal, metrics.margin)
                .padding(.top, 14)
                .padding(.bottom, 30)
                .onDrop(of: [.text], delegate: LibraryDropFallback(dragging: $dragging, editor: editor, model: model))
            }
        }
        .padPage("books-libraries")
        .ambientArtwork(lit ?? libraries.first?.artwork ?? "")
        .refreshable { await load() }
        .task(id: "\(model.userId)·\(model.libraryOrderChanges)") { await load() }
        .onDisappear { if arranging { finishArranging() } }
    }

    /// While arranging, the editor's order: a drag shows before the hub has it.
    private var shown: [ReadingLibrary] {
        guard arranging else { return libraries }
        return editor.libraries.compactMap { arranged in libraries.first { $0.id == arranged.id } }
    }

    @ViewBuilder private func tile(_ library: ReadingLibrary, index: Int) -> some View {
        // A cover fanned on its own picture; a wide picture is not a cover to fan.
        let tile = LibraryTile(folder: LibraryFolder(id: library.id, name: library.title, kind: library.kind,
                                                     image: library.artwork,
                                                     fan: library.artworkStyle == "poster" && !library.artwork.isEmpty
                                                         ? [library.artwork] : []),
                               posters: library.artworkStyle == "poster" && !library.artwork.isEmpty ? [library.artwork] : [],
                               background: library.artwork, arranging: arranging, kindLabel: LibraryKind.reading(library))
        if arranging {
            tile
                .jiggle(dragging != library.id, index: index)
                .opacity(dragging == library.id ? 0.55 : 1)
                .arrangeable(library.id, dragging: $dragging, editor: editor, model: model)
                .accessibilityAddTraits(.isButton)
                .accessibilityHint("Drag to move this library")
                .accessibilityAction(named: "Move earlier") { editor.step(library.id, by: -1, model: model) }
                .accessibilityAction(named: "Move later") { editor.step(library.id, by: 1, model: model) }
        } else {
            NavigationLink(value: AppRoute.readingLibrary(ReadingLibraryRoute(library: library))) { tile }
                .buttonStyle(GlassCardStyle())
                .previewsWhenFocused { lit = library.artwork }
                #if os(iOS)
                .simultaneousGesture(LongPressGesture(minimumDuration: 0.5).onEnded { _ in startArranging() })
                #else
                .contextMenu { Button("Arrange libraries", action: startArranging) }
                #endif
                .accessibilityAction(named: "Arrange libraries", startArranging)
                // Ⓨ is the hold's Arrange libraries (#46).
                .padFocusable(library.id, ring: .card, hold: startArranging) {
                    openRoute(.readingLibrary(ReadingLibraryRoute(library: library)))
                }
        }
    }

    /// Kavita's reading lists, after the libraries and not among those
    /// arranged (#37; Android's fixed "Reading lists" tile): on the picture
    /// of Kavita's first library.
    private func readingLists(_ kavita: ReadingLibrary) -> some View {
        NavigationLink(value: AppRoute.readingLists(ReadingListsRoute(list: nil, artwork: kavita.artwork))) {
            LibraryTile(folder: LibraryFolder(id: "kavita:reading-lists", name: "Reading lists", kind: "reading_list",
                                              image: kavita.artwork, fan: []),
                        posters: [], background: kavita.artwork, kindLabel: "Kavita · in order")
        }
        .buttonStyle(GlassCardStyle())
        .previewsWhenFocused { lit = kavita.artwork }
        .accessibilityIdentifier("reading-lists")
        .padFocusable("reading-lists", ring: .card) {
            openRoute(.readingLists(ReadingListsRoute(list: nil, artwork: kavita.artwork)))
        }
    }

    private func startArranging() {
        guard !arranging, libraries.count > 1 else { return }
        editor.adopt(libraries.map { ArrangedLibrary(id: $0.id, title: $0.title, kind: LibraryKind.reading($0), art: $0.artwork) },
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
        Task { await load() }
    }

    private func load() async {
        if libraries.isEmpty { status = StatusText.loading("book libraries", refreshing: false) }
        do {
            let response = try await model.hub.fetch(HubEndpoints.readingLibraries, as: ReadingLibrariesResponse.self)
            libraries = response.libraries
            order = response.order
            books.remember(libraries)
            model.colors.want(libraries.map(\.artwork))
            status = libraries.isEmpty ? StatusMessage("No book libraries were found")
                : StatusText.caveat(CacheInfo(), unavailable: response.partial.map(\.service))
            #if DEBUG
            // scripts/mac.sh: HUB_SIDE=books HUB_SECTION=library HUB_OPEN=Manga opens that library, once.
            if let name = ProcessInfo.processInfo.environment["HUB_OPEN"], !debugOpened,
               let library = libraries.first(where: { $0.title.caseInsensitiveCompare(name) == .orderedSame }) {
                debugOpened = true
                openRoute(.readingLibrary(ReadingLibraryRoute(library: library)))
            }
            #endif
        } catch {
            if error.kind == .cancelled { return }
            status = StatusText.failed(error.message, kind: error.kind, hasData: !libraries.isEmpty)
        }
    }

}

/// One reading library (the prototype's `pgBLib`; Android's
/// `ReadingLibraryGridScreen`): its name, Series | Authors | Books where it
/// can show its authors, Sort and its direction, the count line, then its
/// covers 60 a page, or its authors as round portraits. Each view keeps its
/// own order, kept between launches.
struct ReadingLibraryView: View {
    @Environment(AppModel.self) private var model
    @Environment(BooksModel.self) private var books
    @Environment(\.glassMetrics) private var metrics
    let route: ReadingLibraryRoute

    @State private var view: BooksModel.LibraryView = .series
    @State private var sort = SortPreference.forField("title")
    @State private var ready = false


    private var library: ReadingLibrary { route.library }
    private var fields: [(id: String, label: String)] { ReadingSortFields.forLibrary(library) }
    /// Author is a view, not a sort, in Series: the switch changes it, and that sort list leaves it out.
    private var canShowAuthors: Bool { fields.contains { $0.id == "author" } }
    private var gridFields: [(id: String, label: String)] { view == .books ? fields : fields.filter { $0.id != "author" } }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                PageHeading(title: library.title) { EmptyView() }
                    .padding(.horizontal, metrics.margin)
                    .padding(.top, 4)
                controls
                    .padding(.horizontal, metrics.margin)
                    .padding(.top, 10)
                if ready {
                    if view == .authors {
                        AuthorsGrid(libraryId: library.id, ascending: sort.ascending)
                            .id("authors·\(sort.ascending)·\(model.userId)·\(model.libraryChanges)")
                    } else {
                        ReadingWorksGrid(library: library, sort: sort, works: view == .books)
                            .id("\(view.rawValue)·\(sort.encoded)·\(model.userId)·\(model.libraryChanges)")
                    }
                }
            }
            // The views and the order, then the grid (#46).
            .padGroup("library", .column, members: (canShowAuthors ? ["views"] : []) + ["order", "grid"], prefix: false)
        }
        .padPage("library:\(library.id)")
        .onAppear {
            guard !ready else { return }
            view = canShowAuthors ? books.libraryView : .series
            #if DEBUG
            // scripts/mac.sh: HUB_SHEET=view:authors (or series, books) opens a library on that view.
            if let sheet = ProcessInfo.processInfo.environment["HUB_SHEET"], sheet.hasPrefix("view:"),
               let chosen = BooksModel.LibraryView(rawValue: String(sheet.dropFirst(5))), canShowAuthors || chosen != .authors {
                view = chosen
            }
            #endif
            sort = view == .books ? books.bookSort(fields: fields.map(\.id))
                : view == .authors ? SortPreference(field: "author", ascending: true)
                : books.seriesSort(fields: gridFields.map(\.id))
            ready = true
        }
    }

    @ViewBuilder private var controls: some View {
        let capsule = GlassCapsulePicker(
            items: BooksModel.LibraryView.allCases.map { GlassCapsulePicker<BooksModel.LibraryView>.Item(id: $0, title: $0.title) },
            selection: view, pad: "views") { chosen in show(chosen) }
            .fixedSize()
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("library-views")
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 10) {
                if canShowAuthors { capsule }
                Spacer(minLength: 0)
                sortControls
            }
            VStack(alignment: .leading, spacing: 10) {
                if canShowAuthors { capsule }
                sortControls
            }
        }
    }

    /// The field as a menu ("Last read ▾", none on Authors) and the direction
    /// as a control that flips on one press, as the media side's.
    private var sortControls: some View {
        HStack(spacing: 10) {
            if view != .authors {
                Menu {
                    Picker("Sort by", selection: Binding(get: { sort.field }, set: { apply(SortPreference.forField($0)) })) {
                        ForEach(gridFields, id: \.id) { field in
                            Text(field.label).tag(field.id)
                        }
                    }
                } label: {
                    Label(ReadingSortFields.label(sort.field) + " ▾", systemImage: "line.3.horizontal.decrease")
                }
                .menuStyle(.button)
                .buttonStyle(GlassControlStyle())
                .accessibilityIdentifier("library-sort-field")
                .padFocusable("sort-field") {
                    PadFocusCenter.shared.present(PadMenu(title: "Sort by", choices: gridFields.map { field in
                        PadChoice(id: "sort-\(field.id)", title: field.label, checked: field.id == sort.field) {
                            apply(SortPreference.forField(field.id))
                        }
                    }))
                }
            }
            Button {
                apply(SortPreference(field: sort.field, ascending: !sort.ascending))
            } label: {
                Label(sort.directionLabel, systemImage: sort.ascending ? "arrow.up" : "arrow.down")
            }
            .buttonStyle(GlassControlStyle())
            .accessibilityHint("Reverses the order")
            .padFocusable("sort-direction") { apply(SortPreference(field: sort.field, ascending: !sort.ascending)) }
        }
        .padGroup("order", .row, members: view == .authors ? ["sort-direction"] : ["sort-field", "sort-direction"],
                  prefix: false)
        .fixedSize()
    }

    private func show(_ next: BooksModel.LibraryView) {
        guard next != view else { return }
        view = next
        books.libraryView = next
        switch next {
        case .authors: sort = SortPreference(field: "author", ascending: true)
        case .books: sort = books.bookSort(fields: fields.map(\.id))
        case .series: sort = books.seriesSort(fields: fields.filter { $0.id != "author" }.map(\.id))
        }
    }

    private func apply(_ value: SortPreference) {
        sort = value
        switch view {
        case .books: books.setBookSort(value)
        case .series: books.setSeriesSort(value)
        case .authors: break
        }
    }
}

/// A library's covers, 60 a page, the next page asked for six covers from the end.
struct ReadingWorksGrid: View {
    @Environment(AppModel.self) private var model
    @Environment(BooksModel.self) private var books
    @Environment(\.glassMetrics) private var metrics
    let library: ReadingLibrary
    let sort: SortPreference
    /// Every book on its own, rather than series.
    let works: Bool

    @State private var items: [ReadingWork] = []
    @State private var paging = PagedLoadState(prefetchAhead: 6)
    @State private var total = 0
    @State private var status = StatusMessage("")
    @State private var lit: String?
    @Environment(\.openRoute) private var openRoute

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if !items.isEmpty {
                Text("\(items.count) of \(total) · \(ReadingSortFields.label(sort.field)) · \(sort.directionLabel)")
                    .font(HubType.body(13, relativeTo: .footnote))
                    .foregroundStyle(.white.opacity(0.6))
                    .padding(.horizontal, metrics.margin)
                    .padding(.top, 12)
            }
            StatusLine(message: status) { Task { await loadNext(retry: true) } }
                .padding(.horizontal, metrics.margin)
                .padding(.top, 8)
            LazyVGrid(columns: [fans ? GridItem(.adaptive(minimum: Self.fanCell(metrics), maximum: Self.fanCell(metrics) * 1.3),
                                                spacing: metrics.small ? 8 : 14, alignment: .top)
                                     : GridItem(.adaptive(minimum: metrics.small ? 100 : 112, maximum: 180),
                                                spacing: metrics.small ? 12 : 18, alignment: .top)],
                      alignment: .leading, spacing: fans ? 26 : 20) {
                ForEach(Array(items.enumerated()), id: \.element.id) { index, work in
                    card(work)
                        .onAppear {
                            if let page = paging.next(lastVisible: index, count: items.count) { Task { await fetch(page) } }
                        }
                }
            }
            .padGroup("grid", .grid(columns: 0), members: items.map(\.id))
            .padding(.horizontal, metrics.margin)
            .padding(.top, 10)
            .padding(.bottom, 26)
        }
        .ambientArtwork(lit ?? items.first?.artwork ?? library.artwork)
        .task { await loadNext(retry: false) }
    }

    /// The Series view's series as fans of their books (#54); every book on its own stays a cover.
    private var fans: Bool { !works && items.contains(where: SeriesFan.hasFan) }

    /// A fan's cell: five slots of `fanCover` and the room they lean into.
    static func fanCell(_ metrics: GlassMetrics) -> CGFloat { metrics.small ? 168 : 228 }
    static func fanCover(_ metrics: GlassMetrics) -> CGFloat { metrics.small ? 44 : 60 }
    /// A book on its own among the fans: a cover this wide, its foot level with theirs.
    static func loneCover(_ metrics: GlassMetrics) -> CGFloat { fanCover(metrics) * 2.3 }

    /// Where a book stands on its own among the fans, the fans stand at the foot of its cover's place.
    private var fanFoot: CGFloat? {
        items.contains { !SeriesFan.hasFan($0) } ? CGFloat(ReadingBookFacts.tallHeight(Double(Self.loneCover(metrics)))) : nil
    }

    @ViewBuilder private func card(_ work: ReadingWork) -> some View {
        let route = AppRoute.book(BookRoute(workId: work.id, title: work.title))
        if fans, SeriesFan.hasFan(work), let plan = SeriesFan.plan(work) {
            // A tap opens the series at the book you are on, or asks for a front book you do not have.
            let target = fanRoute(work, plan.target)
            NavigationLink(value: target) {
                SeriesFanCard(series: work, plan: plan, cover: Self.fanCover(metrics), width: Self.fanCell(metrics),
                              coverHeight: fanFoot)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(GlassCardStyle())
            .previewsWhenFocused { lit = plan.slots.first(where: \.front)?.book.cover ?? work.artwork }
            .padFocusable(work.id, ring: .card) { openRoute(target) }
        } else {
            NavigationLink(value: route) {
                BookCard(work: books.project(work))
                    .frame(maxWidth: fans ? Self.loneCover(metrics) : .infinity)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(GlassCardStyle())
            .previewsWhenFocused { lit = work.artwork }
            .padFocusable(work.id, ring: .card) { openRoute(route) }
        }
    }

    private func fanRoute(_ series: ReadingWork, _ target: SeriesFan.Target) -> AppRoute {
        switch target {
        case .openSeries(let number):
            return .book(BookRoute(workId: series.id, title: series.title, startNumber: number))
        case .request(let book):
            return .missingBook(MissingBookRoute(item: ReadingSectionItem(
                title: book.title, number: book.number, kind: book.kind, artwork: book.cover, authors: series.authors,
                availability: "missing")))
        }
    }

    private func loadNext(retry: Bool) async {
        guard let page = retry ? paging.retry() : paging.initial() else { return }
        await fetch(page)
    }

    private func fetch(_ page: Int) async {
        if items.isEmpty { status = StatusText.loading(library.title, refreshing: false) }
        do {
            let response = try await model.hub.fetch(
                HubEndpoints.readingLibraryItems(libraryId: library.id, page: page, sort: sort.field, direction: sort.order,
                                                 view: works ? "works" : ""),
                as: ReadingLibraryItemsResponse.self)
            let known = Set(items.map(\.id))
            var seen = known
            items += response.items.filter { seen.insert($0.id).inserted }
            total = response.total
            paging.complete(page: page, total: max(1, response.totalPages))
            model.colors.want(response.items.prefix(20).map(\.artwork))
            status = items.isEmpty ? StatusMessage("This reading library is empty.")
                : StatusText.caveat(response.cache, unavailable: response.partial.map(\.service))
        } catch {
            if error.kind == .cancelled {
                paging.cancelLoading()
                return
            }
            paging.fail(page: page)
            status = StatusText.failed(error.message, kind: error.kind, hasData: !items.isEmpty)
        }
    }
}

/// A library's authors: a round portrait each (initials where there is no
/// picture), the name and "2 series · 6 books" under it (Android's `AuthorGridView`).
struct AuthorsGrid: View {
    @Environment(AppModel.self) private var model
    @Environment(\.glassMetrics) private var metrics
    let libraryId: String
    let ascending: Bool

    @State private var authors: [ReadingAuthor] = []
    @State private var paging = PagedLoadState(prefetchAhead: 6)
    @State private var total = 0
    @State private var status = StatusMessage("")
    @Environment(\.openRoute) private var openRoute

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if !authors.isEmpty {
                Text(total == 1 ? "1 author" : "\(total) authors")
                    .font(HubType.body(13, relativeTo: .footnote))
                    .foregroundStyle(.white.opacity(0.6))
                    .padding(.horizontal, metrics.margin)
                    .padding(.top, 12)
            }
            StatusLine(message: status) { Task { await loadNext(retry: true) } }
                .padding(.horizontal, metrics.margin)
                .padding(.top, 8)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 112, maximum: 150), spacing: 14, alignment: .top)],
                      alignment: .leading, spacing: 20) {
                ForEach(Array(authors.enumerated()), id: \.element.id) { index, author in
                    NavigationLink(value: AppRoute.author(AuthorRoute(libraryId: libraryId, id: author.id, name: author.name,
                                                                      artwork: author.artwork, seriesCount: author.seriesCount,
                                                                      bookCount: author.bookCount, total: author.total))) {
                        AuthorCard(name: author.name, artwork: author.artwork,
                                   line: AuthorLabels.shelf(seriesCount: author.seriesCount, bookCount: author.bookCount,
                                                            total: author.total))
                    }
                    .buttonStyle(GlassCardStyle())
                    .onAppear {
                        if let page = paging.next(lastVisible: index, count: authors.count) { Task { await fetch(page) } }
                    }
                    .padFocusable(author.id, ring: .card) {
                        openRoute(.author(AuthorRoute(libraryId: libraryId, id: author.id, name: author.name,
                                                      artwork: author.artwork, seriesCount: author.seriesCount,
                                                      bookCount: author.bookCount, total: author.total)))
                    }
                }
            }
            .padGroup("grid", .grid(columns: 0), members: authors.map(\.id))
            .padding(.horizontal, metrics.margin)
            .padding(.top, 14)
            .padding(.bottom, 26)
        }
        .task { await loadNext(retry: false) }
    }

    private func loadNext(retry: Bool) async {
        guard let page = retry ? paging.retry() : paging.initial() else { return }
        await fetch(page)
    }

    private func fetch(_ page: Int) async {
        if authors.isEmpty { status = StatusText.loading("authors", refreshing: false) }
        do {
            let response = try await model.hub.fetch(
                HubEndpoints.readingAuthors(libraryId: libraryId, page: page, direction: ascending ? "asc" : "desc"),
                as: ReadingAuthorsResponse.self)
            var seen = Set(authors.map(\.id))
            authors += response.authors.filter { seen.insert($0.id).inserted }
            total = response.total
            paging.complete(page: page, total: max(1, response.totalPages))
            status = authors.isEmpty ? StatusMessage("No authors in this library") : StatusMessage("")
        } catch {
            if error.kind == .cancelled {
                paging.cancelLoading()
                return
            }
            paging.fail(page: page)
            status = StatusText.failed(error.message, kind: error.kind, hasData: !authors.isEmpty)
        }
    }
}

/// A person as a round portrait (`.person`): their picture, or their initials.
struct AuthorCard: View {
    let name: String
    let artwork: String
    let line: String
    var size: CGFloat = 92
    @Environment(\.cardLit) private var lit

    var body: some View {
        VStack(spacing: 6) {
            Group {
                if artwork.isEmpty {
                    Text(AuthorFace.initials(name))
                        .font(HubType.heading(size * 0.3, weight: .heavy, relativeTo: .title2))
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .background(AuthorFace.gradient(name))
                } else {
                    ArtworkView(path: artwork, width: 240)
                }
            }
            .frame(width: size, height: size)
            .clipShape(Circle())
            .overlay { Circle().strokeBorder(.white, lineWidth: 3).padding(-3).opacity(lit ? 1 : 0) }
            .shadow(color: .black.opacity(0.35), radius: 11, y: 10)
            .scaleEffect(lit ? 1.06 : 1)
            .animation(.easeOut(duration: 0.2), value: lit)
            Text(name)
                .font(HubType.body(14, weight: .bold, relativeTo: .subheadline))
                .foregroundStyle(.white)
            if !line.isEmpty {
                Text(line)
                    .font(HubType.body(12, relativeTo: .caption))
                    .foregroundStyle(.white.opacity(0.62))
            }
        }
        .lineLimit(2)
        .multilineTextAlignment(.center)
        .frame(maxWidth: .infinity)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

/// An author with no picture: a gradient of their own, from their name (`.authface`).
enum AuthorFace {
    /// "BS" for Brandon Sanderson.
    static func initials(_ name: String) -> String {
        name.split(separator: " ").prefix(2).compactMap(\.first).map { String($0).uppercased() }.joined()
    }

    static func gradient(_ name: String) -> LinearGradient {
        var hash: UInt64 = 1_469_598_103_934_665_603
        for byte in name.utf8 { hash = (hash ^ UInt64(byte)) &* 1_099_511_628_211 }
        let hue = Double(hash % 360) / 360
        return LinearGradient(colors: [Color(hue: hue, saturation: 0.45, brightness: 0.45),
                                       Color(hue: (hue + 0.11).truncatingRemainder(dividingBy: 1), saturation: 0.55, brightness: 0.25)],
                              startPoint: .topLeading, endPoint: .bottomTrailing)
    }
}
