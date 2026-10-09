import HubKit
import SwiftUI

/// Books Home (#25; the prototype's `pgBHome`, Android's `ReadingHomeView`):
/// the book read last at full cover size with Resume reading and Details,
/// the other books being read as glass rows, your series as fans of their
/// covers, then Next in series, Comics and manga, Want to Read, the person's
/// own lists and Recently added. The page takes the colours of the cover in
/// focus or under the pointer.
///
/// It asks what Android asks: the libraries, each library by last read (and
/// by date added where it can), every series being read in full, and the
/// books on the person's lists. Want to Read and the lists are kept on this
/// device (`BooksModel`).
struct BooksHomeView: View {
    @Environment(AppModel.self) private var model
    @Environment(BooksModel.self) private var books
    @Environment(\.glassMetrics) private var metrics
    @Environment(\.openRoute) private var openRoute
    @Environment(\.readerClosed) private var readerClosed

    /// Every book and series the libraries say is being read, a series in full.
    @State private var candidates: [ReadingWork] = []
    /// The books with a place this device kept and the hub has not had yet,
    /// as this device has them, the latest first (#30).
    @State private var unsent: [ReadingWork] = []
    /// Shown before: coming back to Home reads it again.
    @State private var appeared = false
    @State private var returns = 0
    /// The books on the person's lists, as the hub last said.
    @State private var observed: [String: ReadingWork] = [:]
    @State private var recent: [ReadingWork] = []
    /// The book on the hero with its own page: its length, for "page 363 of 735".
    @State private var heroDetails: [String: ReadingWork] = [:]
    @State private var status = StatusMessage("")
    @State private var loading = false
    @State private var lit: String?
    @State private var naming: ListNaming?
    @State private var deleting: ReadingShelfRow?

    /// A list being named: a new one, or one renamed.
    struct ListNaming: Identifiable {
        let id = UUID()
        let listId: String?
        var name: String
        /// A book to put on a new list once it is made.
        var adding: ReadingWork?
    }

    private static let recentLimit = 12

    /// The shelves, each book as the person marked it (#37): one marked read leaves Continue reading.
    private var rows: [ReadingShelfRow] {
        let marked = candidates.map(books.project)
        return ReadingShelves.rows(current: unsent.map(books.project) + marked, state: books.lists,
                                   resolved: observed.mapValues(books.project),
                                   next: ReadingShelves.nextInSeries(marked), recent: recent)
    }

    private var series: [ReadingShelves.SeriesShelfItem] { ReadingShelves.yourSeries(candidates.map(books.project)) }

    var body: some View {
        let rows = rows
        let reading = rows.first { $0.id == ReadingShelves.currentlyReading }
        let hero = reading?.items.first
        let shelves = rows.filter { $0.id != ReadingShelves.currentlyReading }
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                if let reading, let hero {
                    ContinueReadingHero(work: heroWork(hero), open: { openRoute($0) }) { lit = hero.artwork }
                        .padding(.horizontal, metrics.margin)
                        .padding(.top, 14)
                    let others = Array(reading.items.dropFirst())
                    if !others.isEmpty { alsoReading(others) }
                } else {
                    Color.clear.frame(height: 4)
                }
                StatusLine(message: status) { Task { await load() } }
                    .padding(.horizontal, metrics.margin)
                    .padding(.top, 10)
                if !series.isEmpty { yourSeries }
                ForEach(shelves) { row in
                    shelf(row)
                }
                Button {
                    naming = ListNaming(listId: nil, name: "")
                } label: {
                    Label("New list", systemImage: "plus")
                }
                .buttonStyle(GlassControlStyle())
                .padFocusable("new-list") { naming = ListNaming(listId: nil, name: "") }
                .padding(.horizontal, metrics.margin)
                .padding(.top, 14)
                .accessibilityIdentifier("books-new-list")
            }
            // A controller goes row by row (#46): the books also being read, your
            // series, the shelves, then New list; the hero's are above them.
            .padGroup("rows", .column, members: ["also", "series"]
                      + shelves.flatMap { shelf -> [String] in
                          let own = shelf.isOwnList && shelf.id != ReadingListsState.wantToReadId
                          return (own ? ["list-actions-\(shelf.id)"] : []) + (shelf.items.isEmpty ? [] : ["shelf-\(shelf.id)"])
                      } + ["new-list"], prefix: false)
            .padding(.bottom, 28)
        }
        .padPage("books-home")
        .ambientArtwork(lit ?? hero?.artwork ?? rows.first?.items.first?.artwork ?? "")
        .refreshable { await load() }
        .task(id: "\(model.userId)·\(readerClosed)·\(returns)") { await load() }
        .onAppear {
            if appeared { returns += 1 }
            appeared = true
        }
        .task(id: hero?.id) { if let hero { await loadHeroDetail(hero) } }
        // Ⓑ closes them (#46).
        .padCloses(naming != nil) { naming = nil }
        .padCloses(deleting != nil) { deleting = nil }
        .alert(naming?.listId == nil ? "New reading list" : "Rename reading list", isPresented: Binding(
            get: { naming != nil }, set: { if !$0 { naming = nil } })) {
            TextField("List name", text: Binding(get: { naming?.name ?? "" }, set: { naming?.name = $0 }))
            Button("Cancel", role: .cancel) { naming = nil }
            Button(naming?.listId == nil ? "Create" : "Save") { saveNaming() }
        }
        .alert("Delete \(deleting?.title ?? "")?", isPresented: Binding(
            get: { deleting != nil }, set: { if !$0 { deleting = nil } }), presenting: deleting) { list in
            // The harmless answer has the cancel role: Escape presses it, and iOS 26 shows it
            // (as the last answer, its own place for it). Without it iOS adds a Cancel of its own.
            Button("Keep the list", role: .cancel) { deleting = nil }
            Button("Delete list", role: .destructive) {
                deleting = nil
                books.updateLists { $0.delete(list.id) }
            }
        } message: { _ in
            Text("The books remain in your library.")
        }
    }

    /// The hero's book, with its length from its own page once that has arrived.
    private func heroWork(_ hero: ReadingWork) -> ReadingWork {
        guard var detail = heroDetails[hero.id] else { return hero }
        detail.progress = hero.progress ?? detail.progress
        return detail
    }

    // MARK: Also reading

    /// "Also reading 2", then the others as glass rows (`.also`, `.mini`).
    private func alsoReading(_ others: [ReadingWork]) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            RowHeading(title: "Also reading", count: "\(others.count)")
                .padding(.horizontal, metrics.margin)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: metrics.small ? 260 : 300), spacing: 14)], spacing: 14) {
                ForEach(others) { work in
                    NavigationLink(value: AppRoute.book(BookRoute(workId: work.id, title: work.title))) {
                        AlsoReadingRow(work: work)
                    }
                    .buttonStyle(GlassCardStyle())
                    .previewsWhenFocused { lit = work.artwork }
                    .padFocusable(work.id, ring: .card, menu: { listMenu(work, row: nil) }) {
                        openRoute(.book(BookRoute(workId: work.id, title: work.title)))
                    }
                }
            }
            .padGroup("also", .grid(columns: 0), members: others.map(\.id))
            .padding(.horizontal, metrics.margin)
            .padding(.top, 10)
        }
        .padding(.top, 18)
    }

    // MARK: Your series

    private var yourSeries: some View {
        VStack(alignment: .leading, spacing: 0) {
            RowHeading(title: "Your series")
                .padding(.horizontal, metrics.margin)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: metrics.small ? 4 : 10) {
                    ForEach(series) { item in
                        let width: CGFloat = metrics.small ? 80 : 96
                        NavigationLink(value: AppRoute.book(BookRoute(workId: item.id, title: item.title))) {
                            VStack(alignment: .leading, spacing: 10) {
                                CoverFan(covers: item.covers, coverWidth: width)
                                CardCaption(title: item.title, detail: item.line)
                                    .padding(.leading, CoverFan.inset(coverWidth: width))
                                    .frame(width: CoverFan.size(coverWidth: width).width, alignment: .leading)
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(GlassCardStyle())
                        .previewsWhenFocused { lit = item.covers.first }
                        .accessibilityLabel("\(item.title), \(item.line)")
                        .padFocusable(item.id, ring: .card) { openRoute(.book(BookRoute(workId: item.id, title: item.title))) }
                    }
                }
                .padding(.horizontal, max(0, metrics.margin - CoverFan.inset(coverWidth: metrics.small ? 80 : 96)))
                .padding(.top, 18)
                .padding(.bottom, 18)
            }
            .padGroup("series", .row, members: series.map(\.id), strip: true)
        }
        .padding(.top, 18)
    }

    // MARK: The rows

    @ViewBuilder private func shelf(_ row: ReadingShelfRow) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .center, spacing: 10) {
                RowHeading(title: row.title, count: row.isOwnList && row.id != ReadingListsState.wantToReadId
                           ? "\(row.readCount)/\(row.items.count) read" : nil)
                Spacer(minLength: 8)
                if row.isOwnList && row.id != ReadingListsState.wantToReadId { listActions(row) }
            }
            .padding(.horizontal, metrics.margin)
            if row.items.isEmpty {
                Text(row.id == ReadingListsState.wantToReadId ? "Add a book from its details to keep it here."
                                                              : "Add a book from its details to start this list.")
                    .font(HubType.body(14, relativeTo: .subheadline))
                    .foregroundStyle(.white.opacity(0.6))
                    .padding(.horizontal, metrics.margin)
                    .padding(.top, 8)
                    .padding(.bottom, 12)
            } else {
                ScrollViewReader { reader in
                    ScrollView(.horizontal, showsIndicators: false) {
                        LazyHStack(alignment: .top, spacing: metrics.gap) {
                            ForEach(Array(row.items.enumerated()), id: \.offset) { index, work in
                                NavigationLink(value: AppRoute.book(BookRoute(workId: work.id, title: work.title))) {
                                    BookCard(work: work, showKind: row.id == ReadingShelves.comics)
                                        .frame(width: metrics.poster)
                                }
                                .buttonStyle(GlassCardStyle())
                                .previewsWhenFocused { lit = work.artwork }
                                .id(index)
                                .padFocusable("\(index)", ring: .card, scroll: index, menu: { listMenu(work, row: row) }) {
                                    openRoute(.book(BookRoute(workId: work.id, title: work.title)))
                                }
                            }
                        }
                        .padding(.top, 12)
                        .padding(.bottom, 16)
                    }
                    .contentMargins(.horizontal, metrics.margin, for: .scrollContent)
                    .padGroup("shelf-\(row.id)", .row, members: row.items.indices.map { "\($0)" }, strip: true,
                              scrollIds: row.items.indices.map { AnyHashable($0) })
                    // A list opens at its next unread book, as on the Pocket.
                    .onAppear { if row.isOwnList && row.nextIndex > 0 { reader.scrollTo(row.nextIndex, anchor: .leading) } }
                }
            }
        }
        .padding(.top, 8)
    }

    /// A list's own menu: rename it, delete it.
    private func listActions(_ row: ReadingShelfRow) -> some View {
        Menu {
            Button {
                naming = ListNaming(listId: row.id, name: row.title)
            } label: {
                Label("Rename list", systemImage: "pencil")
            }
            Button(role: .destructive) {
                askDelete(row)
            } label: {
                Label("Delete list", systemImage: "trash")
            }
        } label: {
            Image(systemName: "ellipsis")
                .font(.system(size: 15, weight: .bold))
                .frame(width: 36, height: 36)
                .glassPanel(Circle())
                .contentShape(Circle())
        }
        .menuStyle(.button)
        .buttonStyle(.plain)
        .accessibilityLabel("Manage \(row.title)")
        // A controller's Ⓐ: the same two as the menu's (#46).
        .padFocusable("list-actions-\(row.id)", ring: .circle) {
            PadFocusCenter.shared.present(PadMenu(title: row.title, choices: [
                PadChoice(id: "rename", title: "Rename list", systemImage: "pencil") {
                    naming = ListNaming(listId: row.id, name: row.title)
                },
                PadChoice(id: "delete", title: "Delete list", systemImage: "trash", role: .destructive) { askDelete(row) },
            ]))
        }
    }

    /// Delete a list, asked first: the panel while the ring is in use, else the alert.
    private func askDelete(_ row: ReadingShelfRow) {
        let asked = PadFocusCenter.shared.confirm(PadMenu(title: "Delete \(row.title)?", message: "The books remain in your library.",
                                                          choices: [
            PadChoice(id: "keep", title: "Keep the list"),
            PadChoice(id: "delete", title: "Delete list", role: .destructive) { books.updateLists { $0.delete(row.id) } },
        ]))
        if !asked { deleting = row }
    }

    /// A book's list actions, on a long press, a secondary click or Ⓨ:
    /// Android's Ⓨ "List actions".
    private func listMenu(_ work: ReadingWork, row: ReadingShelfRow?) -> PadMenu {
        var choices: [PadChoice] = []
        let started = (work.progress?.percentage ?? 0) > 0 || work.progress?.completed == true
        if row?.id != ReadingListsState.wantToReadId && !started && !books.isWanted(work.id) {
            choices.append(PadChoice(id: "want", title: "Add to Want to Read", systemImage: "bookmark") {
                books.updateLists { $0.add(ReadingListsState.wantToReadId, ReadingListEntry.from(work)) }
                // Want to read is the reading status too (#63): the hub is told.
                if books.isWanted(work.id) { writeStatus(work, ReadingStatus.want) }
            })
        }
        if let row, row.isOwnList {
            choices.append(PadChoice(id: "remove", title: "Remove from \(row.title)", systemImage: "minus.circle") {
                books.updateLists { $0.remove(row.id, workId: work.id) }
                if row.id == ReadingListsState.wantToReadId && ReadingStatus.of(work) == ReadingStatus.want { writeStatus(work, nil) }
            })
            if row.id != ReadingListsState.wantToReadId {
                choices.append(PadChoice(id: "earlier", title: "Move earlier", systemImage: "arrow.left") {
                    books.updateLists { $0.move(row.id, workId: work.id, by: -1) }
                })
                choices.append(PadChoice(id: "later", title: "Move later", systemImage: "arrow.right") {
                    books.updateLists { $0.move(row.id, workId: work.id, by: 1) }
                })
            }
        }
        choices.append(ReadingListChoices.addToList(work, books: books) { naming = ListNaming(listId: nil, name: "", adding: work) })
        return PadMenu(title: work.title, choices: choices)
    }

    /// Tells the hub the book's reading status, nil taking the choice back, as the book page does (#63).
    private func writeStatus(_ work: ReadingWork, _ next: String?) {
        var change = ReadingYouChange(status: .clear)
        if let next { change.status = .set(next) }
        let request = HubEndpoints.readingYou(work.id, change)
        let hub = model.hub
        Task {
            do throws(HubFailure) {
                _ = try await hub.fetch(request, as: ReadingYouResponse.self)
            } catch {
                guard error.kind != .cancelled else { return }
                status = StatusMessage("Your reading status could not be saved · " + error.message, tone: .warning)
            }
        }
    }

    private func saveNaming() {
        guard let naming else { return }
        let name = naming.name.trimmingCharacters(in: .whitespacesAndNewlines)
        defer { self.naming = nil }
        guard !name.isEmpty else { return }
        if let listId = naming.listId {
            books.updateLists { $0.rename(listId, to: name) }
        } else {
            let id = UUID().uuidString
            books.updateLists { state in
                let made = state.create(name, id: id)
                return naming.adding.map { made.add(id, ReadingListEntry.from($0)) } ?? made
            }
        }
    }

    // MARK: Loading

    private func load() async {
        guard !loading else { return }
        loading = true
        defer { loading = false }
        let hasData = !candidates.isEmpty
        status = StatusText.loading(hasData ? "reading progress" : "reading", refreshing: hasData)
        let hub = model.hub
        let libraries: [ReadingLibrary]
        do {
            libraries = try await hub.fetch(HubEndpoints.readingLibraries, as: ReadingLibrariesResponse.self).libraries
        } catch {
            if error.kind == .cancelled { return }
            status = StatusText.failed(error.message, kind: error.kind, hasData: hasData)
            return
        }
        books.remember(libraries)
        // Each library by last read, and the book libraries by date added, at once.
        let lastRead = await fetchEach(libraries.map {
            HubEndpoints.readingLibraryItems(libraryId: $0.id, sort: "last_read", direction: "desc")
        }, hub: hub, as: ReadingLibraryItemsResponse.self)
        let addedLibraries = libraries.filter { $0.kind == "book" && $0.capabilities.contains("sort:added") }
        let added = await fetchEach(addedLibraries.map {
            HubEndpoints.readingLibraryItems(libraryId: $0.id, sort: "added", direction: "desc")
        }, hub: hub, as: ReadingLibraryItemsResponse.self)
        guard !Task.isCancelled else { return }
        var failures = lastRead.filter { $0 == nil }.count + added.filter { $0 == nil }.count
        let summaries = lastRead.compactMap { $0 }.flatMap(\.items)
        // A series being read is read in full, to know which of its books it is.
        let reading = summaries.filter { $0.isSeries && $0.progress != nil && $0.progress?.completed != true }
        let details = await fetchEach(reading.map { HubEndpoints.readingWork($0.id) }, hub: hub, as: ReadingWork.self)
        failures += details.filter { $0 == nil }.count
        var found: [ReadingWork] = []
        var detailIndex = 0
        for work in summaries {
            if work.isSeries && work.progress != nil && work.progress?.completed != true {
                if let detail = details[detailIndex] { found.append(detail) }
                detailIndex += 1
            } else if !work.isSeries {
                found.append(work)
            }
        }
        // The books on the lists the libraries did not just name.
        var fresh: [String: ReadingWork] = [:]
        for work in ReadingShelves.current(found) { fresh[work.id] = work }
        for work in summaries where !work.isSeries { fresh[work.id] = work }
        let listed = Array(Set(books.lists.wantToRead.map(\.workId) + books.lists.lists.flatMap { $0.items.map(\.workId) }))
            .filter { fresh[$0] == nil }
        let listedWorks = await fetchEach(listed.map(HubEndpoints.readingWork), hub: hub, as: ReadingWork.self)
        failures += listedWorks.filter { $0 == nil }.count
        for work in listedWorks.compactMap({ $0 }) { fresh[work.id] = work }
        // A place this device kept and the hub has not had yet (#30): its book
        // read in full and shown as this device has it, read at the moment it was kept.
        let scope = ReadingCheckpointKey.scope(address: model.address, userId: model.userId)
        let pending = ListeningStore.shared.pending(scope: scope).filter { !$0.conflicted }
        var pendingIds: [String] = []
        for checkpoint in pending.sorted(by: { $0.updatedAt > $1.updatedAt }) where !pendingIds.contains(checkpoint.key.workId) {
            pendingIds.append(checkpoint.key.workId)
        }
        let pendingWorks = await fetchEach(pendingIds.map(HubEndpoints.readingWork), hub: hub, as: ReadingWork.self)
        failures += pendingWorks.filter { $0 == nil }.count
        var shownUnsent: [ReadingWork] = []
        for (id, fetched) in zip(pendingIds, pendingWorks) {
            guard let fetched else { continue }
            let forWork = pending.filter { $0.key.workId == id }
            var shown = ReadingProgressPresentation.project(fetched, pending: forWork)
            if let kept = forWork.map(\.updatedAt).max(), shown.progress != nil {
                shown.progress?.updatedAt = Self.stamp(kept)
            }
            fresh[id] = shown
            shownUnsent.append(shown)
        }
        guard !Task.isCancelled else { return }
        books.observe(Array(fresh.values))
        unsent = shownUnsent
        candidates = found
        observed = fresh
        recent = Array(added.compactMap { $0 }.flatMap { $0.items.prefix(Self.recentLimit) }
            .sorted { ReadingShelves.timestamp($0.addedAt) > ReadingShelves.timestamp($1.addedAt) }
            .prefix(Self.recentLimit))
        model.colors.want(rows.flatMap { $0.items.map(\.artwork) } + series.compactMap(\.covers.first))
        #if DEBUG
        applyDebugOpen()
        #endif
        if failures > 0 {
            status = StatusMessage("Some reading progress is unavailable · showing saved items where possible", tone: .warning)
        } else if ReadingShelves.current(found).isEmpty && books.lists.wantToRead.isEmpty && books.lists.lists.isEmpty {
            status = StatusMessage("Start a book in Library, or make a reading list.")
        } else {
            status = StatusMessage("")
        }
    }

    #if DEBUG
    /// scripts/mac.sh opens a Books page for screenshots, once a launch:
    /// HUB_OPEN=book:<work id>, entry:<work id> (as Resume reading does),
    /// author:<library id>|<author id>|<name>, missing:<work id> (that
    /// series' first book the library lacks), listen:<work id>|<audiobook id>
    /// (its page), or player:<work id>|<audiobook id> (on the player, paused,
    /// with only the mini player showing it). The demo hub's places only.
    @MainActor private static var debugOpened = false

    private func applyDebugOpen() {
        guard !Self.debugOpened, let open = ProcessInfo.processInfo.environment["HUB_OPEN"], !open.isEmpty else { return }
        let parts = open.split(separator: ":", maxSplits: 1).map(String.init)
        guard parts.count == 2 else { return }
        Self.debugOpened = true
        let value = parts[1]
        switch parts[0] {
        case "book", "entry":
            // With its title, as a card opens it: the page above names it on its back button.
            let entry = parts[0] == "entry"
            Task {
                let title = (try? await model.hub.fetch(HubEndpoints.readingWork(value), as: ReadingWork.self))?.title ?? ""
                openRoute(.book(BookRoute(workId: value, title: title, openEntry: entry)))
            }
        case "author":
            let fields = value.split(separator: "|").map(String.init)
            guard fields.count == 3 else { return }
            openRoute(.author(AuthorRoute(libraryId: fields[0], id: fields[1], name: fields[2])))
        case "listen", "player":
            let fields = value.split(separator: "|").map(String.init)
            guard fields.count == 2, model.isDemo else { return }
            let page = parts[0] == "listen"
            Task {
                guard let work = try? await model.hub.fetch(HubEndpoints.readingWork(fields[0]), as: ReadingWork.self) else { return }
                if page {
                    openRoute(.listen(ListenRoute(workId: work.id, sourceItemId: fields[1], title: work.title)))
                } else if let opening = try? await ListeningModel.shared.prepare(work: work, sourceItemId: fields[1], app: model) {
                    ListeningModel.shared.start(opening, play: false)
                }
            }
        case "missing":
            Task {
                guard let series = try? await model.hub.fetch(HubEndpoints.readingWork(value), as: ReadingWork.self),
                      let item = series.sections.flatMap(\.items).first(where: { !$0.isAvailable }) else { return }
                openRoute(.missingBook(MissingBookRoute(item: item)))
            }
        default:
            Self.debugOpened = false
        }
    }
    #endif

    /// This device's moment, in the hub's words (ISO 8601), for the shelves' order.
    private static func stamp(_ millis: Int64) -> String {
        ISO8601DateFormatter().string(from: Date(timeIntervalSince1970: Double(millis) / 1_000))
    }

    /// The hero's own page, once per book, for its length.
    private func loadHeroDetail(_ hero: ReadingWork) async {
        guard heroDetails[hero.id] == nil,
              let detail = try? await model.hub.fetch(HubEndpoints.readingWork(hero.id), as: ReadingWork.self) else { return }
        heroDetails[hero.id] = detail
    }
}

/// Several hub reads at once, each answer in its place (nil where it failed).
func fetchEach<T: Decodable & Sendable>(_ requests: [HubRequest], hub: HubClient, as type: T.Type) async -> [T?] {
    await withTaskGroup(of: (Int, T?).self) { group in
        for (index, request) in requests.enumerated() {
            group.addTask { (index, try? await hub.fetch(request, as: T.self)) }
        }
        var answers = [T?](repeating: nil, count: requests.count)
        for await (index, answer) in group { answers[index] = answer }
        return answers
    }
}

/// The top of Books Home (`.reading`; Android's `ContinueReadingView`): the
/// book read last at full cover size with the words beside its foot, its
/// place in its series with the number in the accent, how far through, then
/// Resume reading in the accent and Details. On a phone held upright the
/// cover sits over the words, centred.
struct ContinueReadingHero: View {
    let work: ReadingWork
    /// A controller's Ⓐ on the cover or a pill (#46): its page.
    var open: (AppRoute) -> Void = { _ in }
    let preview: () -> Void
    @Environment(\.glassMetrics) private var metrics
    @Environment(\.glassAccent) private var accent

    private var fraction: Double {
        guard let p = work.progress else { return 0 }
        return p.completed ? 1 : p.percentage
    }

    var body: some View {
        let layout = metrics.centred ? AnyLayout(VStackLayout(alignment: .center, spacing: 18))
            : AnyLayout(HStackLayout(alignment: .bottom, spacing: metrics.small ? 20 : 30))
        layout {
            NavigationLink(value: AppRoute.book(BookRoute(workId: work.id, title: work.title))) {
                BookCover(path: work.artwork, square: work.kind == "audiobook", width: 480)
                    .frame(width: metrics.short ? 120 : metrics.centred ? 170 : 196)
                    .shadow(color: .black.opacity(0.55), radius: 30, y: 34)
                    .litArtwork(corner: 12)
            }
            .buttonStyle(GlassCardStyle())
            .previewsWhenFocused(preview)
            .accessibilityLabel("Details for \(work.title)")
            .padFocusable("hero-cover", ring: .card) { open(.book(BookRoute(workId: work.id, title: work.title))) }
            words
        }
        .frame(maxWidth: .infinity, alignment: metrics.centred ? .center : .leading)
    }

    private var words: some View {
        VStack(alignment: metrics.centred ? .center : .leading, spacing: 10) {
            Text("CONTINUE READING")
                .font(HubType.body(12.5, weight: .bold, relativeTo: .caption))
                .tracking(1.75)
                .foregroundStyle(.white.opacity(0.72))
            Text(work.title)
                .font(HubType.heading(metrics.heroTitle, weight: .heavy))
                .tracking(-0.02 * metrics.heroTitle)
                .foregroundStyle(.white)
                .lineLimit(2)
                .minimumScaleFactor(0.6)
            let place = seriesPlaceLine(work, accent: accent.tint)
            if !place.characters.isEmpty {
                Text(place)
                    .font(HubType.body(15, relativeTo: .subheadline))
                    .lineLimit(1)
            }
            HStack(spacing: 12) {
                HeroProgress(progress: fraction, accent: accent.tint)
                    .frame(width: metrics.small ? 110 : 260)
                Text(ReadingBookFacts.progress(work) ?? "")
                    .font(HubType.body(13, relativeTo: .caption))
                    .foregroundStyle(.white.opacity(0.82))
                    .monospacedDigit()
                    .lineLimit(1)
            }
            .frame(height: 18)
            HStack(spacing: 10) {
                NavigationLink(value: AppRoute.book(BookRoute(workId: work.id, title: work.title, openEntry: true))) {
                    Label("Resume reading", systemImage: "book")
                }
                .buttonStyle(PrimaryPillStyle(accent: accent))
                .accessibilityIdentifier("books-resume")
                .padFocusable("hero-resume") {
                    open(.book(BookRoute(workId: work.id, title: work.title, openEntry: true)))
                }
                NavigationLink(value: AppRoute.book(BookRoute(workId: work.id, title: work.title))) {
                    Label("Details", systemImage: "info.circle")
                }
                .buttonStyle(GlassPillStyle())
                .padFocusable("hero-details") { open(.book(BookRoute(workId: work.id, title: work.title))) }
            }
            .padding(.top, 4)
        }
        .multilineTextAlignment(metrics.centred ? .center : .leading)
        .fixedSize(horizontal: false, vertical: true)
    }
}

/// A book also being read (`.mini`): its cover, its title, "Blake Crouch ·
/// 3%" over a bar in the accent, and its formats when it has more than one.
struct AlsoReadingRow: View {
    let work: ReadingWork
    @Environment(\.glassAccent) private var accent

    private var fraction: Double {
        guard let p = work.progress else { return 0 }
        return p.completed ? 1 : p.percentage
    }

    var body: some View {
        HStack(spacing: 14) {
            BookCover(path: work.artwork, square: work.kind == "audiobook", width: 160)
                .frame(width: 52)
                .shadow(color: .black.opacity(0.4), radius: 8, y: 8)
            VStack(alignment: .leading, spacing: 4) {
                Text(work.title)
                    .font(HubType.body(15, weight: .bold, relativeTo: .subheadline))
                    .foregroundStyle(.white)
                Text(ReadingBookFacts.miniLine(work))
                    .font(HubType.body(13, relativeTo: .caption))
                    .foregroundStyle(.white.opacity(0.64))
                HeroProgress(progress: fraction, accent: accent.tint)
                    .padding(.top, 3)
            }
            .lineLimit(1)
            let formats = ReadingBookFacts.formats(work)
            if formats.count > 1 { FormatMarks(formats: formats) }
        }
        .padding(.leading, 10)
        .padding(.trailing, 14)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassPanel(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .litRing(corner: 18)
        .contentShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .accessibilityElement(children: .combine)
    }
}

/// "Add to a list": each of the person's lists, ticked where the book is on
/// it, and a new list. One list of choices for a menu, a context menu and Ⓨ.
enum ReadingListChoices {
    @MainActor
    static func addToList(_ work: ReadingWork, books: BooksModel, newList: @escaping () -> Void) -> PadChoice {
        let lists = books.lists.lists.map { list in
            let included = list.items.contains { $0.workId == work.id }
            return PadChoice(id: "list-\(list.id)", title: list.name, checked: included) {
                books.updateLists { included ? $0.remove(list.id, workId: work.id) : $0.add(list.id, ReadingListEntry.from(work)) }
            }
        }
        return PadChoice(id: "add-to-list", title: "Add to a list", systemImage: "list.bullet",
                         children: lists + [PadChoice(id: "new-list", title: "New list", systemImage: "plus", action: newList)])
    }
}

/// "Add to a list" as a menu.
struct ReadingListsMenu: View {
    let work: ReadingWork
    let newList: () -> Void
    @Environment(BooksModel.self) private var books

    var body: some View {
        PadChoicesMenu(choices: [ReadingListChoices.addToList(work, books: books, newList: newList)])
    }
}
