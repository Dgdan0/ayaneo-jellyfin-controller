import HubKit
import SwiftUI

/// A book of a series the library does not have (Android's
/// `MissingReadingItemScreen`): its cover dimmed, which book it is, and Find
/// this book, which searches BookKeeprr's ebooks for it and lists the
/// editions, each opening its request page. Nothing is requested here.
struct MissingBookView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.glassMetrics) private var metrics
    @Environment(\.glassAccent) private var accent
    @Environment(\.openRoute) private var openRoute
    let route: MissingBookRoute

    @State private var editions: [ReadingItem]?
    @State private var searching = false
    @State private var status = StatusMessage("")

    private var item: ReadingSectionItem { route.item }
    /// The book is in the library; only its ebook is missing (#39).
    private var ebookOnly: Bool { route.lacking == "ebook" }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                header
                    .padding(.horizontal, metrics.margin)
                    .padding(.top, 6)
                StatusLine(message: status) { Task { await search() } }
                    .padding(.horizontal, metrics.margin)
                    .padding(.top, 10)
                if let editions, !editions.isEmpty {
                    RowHeading(title: "Choose an edition", count: "\(editions.count)")
                        .padding(.horizontal, metrics.margin)
                        .padding(.top, 20)
                    Text("Check the title and author before requesting.")
                        .font(HubType.body(14, relativeTo: .subheadline))
                        .foregroundStyle(.white.opacity(0.66))
                        .padding(.horizontal, metrics.margin)
                        .padding(.top, 4)
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: metrics.small ? 260 : 320), spacing: 12)], spacing: 12) {
                        ForEach(editions) { found in
                            NavigationLink(value: AppRoute.bookRequest(BookRequestRoute(item: found))) {
                                EditionRow(item: found)
                            }
                            .buttonStyle(GlassCardStyle())
                            .padFocusable("\(found.id)", ring: .card) { openRoute(.bookRequest(BookRequestRoute(item: found))) }
                        }
                    }
                    .padGroup("editions", .grid(columns: 0), members: editions.map { "\($0.id)" })
                    .padding(.horizontal, metrics.margin)
                    .padding(.top, 12)
                }
            }
            .padding(.bottom, 28)
        }
        .padPage("missing-book:\(item.workId)\(item.title)")
        .ambientArtwork(item.artwork)
    }

    @ViewBuilder private var header: some View {
        let layout = metrics.centred ? AnyLayout(VStackLayout(alignment: .leading, spacing: 18))
            : AnyLayout(HStackLayout(alignment: .top, spacing: metrics.short ? 22 : 34))
        layout {
            BookCover(path: item.artwork, width: 480, dimmed: true)
                .frame(width: metrics.short ? 110 : metrics.centred ? 150 : 200)
                .frame(maxWidth: metrics.centred ? .infinity : nil)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 10) {
                Text((ebookOnly ? ["Ebook", "Not in your library"] as [String?]
                      : [item.number.isEmpty ? nil : "Book \(item.number)", "Not in your library"] as [String?])
                    .compactMap { $0 }.joined(separator: " · ").uppercased())
                    .font(HubType.body(12.5, weight: .bold, relativeTo: .caption))
                    .tracking(1.75)
                    .foregroundStyle(.white.opacity(0.72))
                Text(item.title)
                    .font(HubType.heading(metrics.heroTitle, weight: .heavy))
                    .tracking(-0.02 * metrics.heroTitle)
                    .foregroundStyle(.white)
                    .lineLimit(3)
                    .minimumScaleFactor(0.55)
                if !item.authors.isEmpty {
                    Text(item.authors.joined(separator: ", "))
                        .font(HubType.body(15, relativeTo: .subheadline))
                        .foregroundStyle(.white.opacity(0.82))
                }
                Text(ebookOnly
                     ? "Your library has this book without an ebook. Search for an edition to see its details and request it."
                     : "This book is part of the series but is not in your library. Search for an edition to see its details and request it.")
                    .font(HubType.body(15, relativeTo: .body))
                    .foregroundStyle(.white.opacity(0.82))
                    .frame(maxWidth: 560, alignment: .leading)
                Button {
                    Task { await search() }
                } label: {
                    Label(searching ? "Searching editions" : "Find this book", systemImage: "magnifyingglass")
                }
                .buttonStyle(PrimaryPillStyle(accent: accent))
                .disabled(searching)
                .padFocusable("find") { if !searching { Task { await search() } } }
                .padding(.top, 4)
                .accessibilityIdentifier("find-this-book")
            }
            .fixedSize(horizontal: false, vertical: true)
        }
    }

    /// Its title and first author among BookKeeprr's ebooks: the close
    /// matches, else the broader ones.
    private func search() async {
        guard !searching else { return }
        searching = true
        defer { searching = false }
        status = StatusMessage("Searching editions…")
        let query = [item.title, item.authors.first ?? ""].filter { !$0.isEmpty }.joined(separator: " ")
        do {
            let response = try await model.hub.fetch(HubEndpoints.readingSearch(query, type: ReadingType.ebook),
                                                     as: ReadingSearchResponse.self)
            let found = response.results.isEmpty ? response.broaderResults : response.results
            editions = found
            status = found.isEmpty ? StatusMessage("No matching editions found") : StatusMessage("")
        } catch {
            if error.kind == .cancelled { return }
            status = StatusText.failed(error.message, kind: error.kind, hasData: false)
        }
    }
}

/// A title BookKeeprr found, as a glass row: its cover, its name and who
/// wrote it, and its kind.
struct EditionRow: View {
    let item: ReadingItem

    var body: some View {
        HStack(spacing: 14) {
            BookCover(path: item.cover, square: item.contentType == ReadingType.audiobook, width: 160)
                .frame(width: 46)
                .shadow(color: .black.opacity(0.4), radius: 6, y: 6)
            VStack(alignment: .leading, spacing: 4) {
                Text(item.title)
                    .font(HubType.body(15, weight: .bold, relativeTo: .subheadline))
                    .foregroundStyle(.white)
                    .lineLimit(2)
                Text(item.subtitle)
                    .font(HubType.body(13, relativeTo: .caption))
                    .foregroundStyle(.white.opacity(0.66))
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.right")
                .font(.system(size: 13, weight: .bold))
                .foregroundStyle(.white.opacity(0.5))
        }
        .padding(.leading, 10)
        .padding(.trailing, 14)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassPanel(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .litRing(corner: 16)
        .contentShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .accessibilityElement(children: .combine)
    }
}

/// One author (Android's `ReadingAuthorScreen`): a round portrait in a white
/// ring, "2 series · 6 books" and how far through them, then each series as a
/// glass pill over its books in reading order, then the books outside a
/// series. Every page of the author's shelf is read.
struct AuthorView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.glassMetrics) private var metrics
    @Environment(\.readerClosed) private var readerClosed
    let route: AuthorRoute

    @State private var items: [ReadingWork]?
    @State private var artwork = ""
    @State private var shelf = ""
    @State private var status = StatusMessage("")
    @State private var reloads = 0
    @Environment(\.openRoute) private var openRoute

    var body: some View {
        let works = items ?? []
        let series = works.filter(\.isSeries)
        let books = works.filter { !$0.isSeries }
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                header(series: series, books: books)
                    .padding(.horizontal, metrics.margin)
                    .padding(.top, 6)
                StatusLine(message: status) { reloads += 1 }
                    .padding(.horizontal, metrics.margin)
                    .padding(.top, 8)
                ForEach(series) { collection in
                    VStack(alignment: .leading, spacing: 6) {
                        NavigationLink(value: AppRoute.book(BookRoute(workId: collection.id, title: collection.title))) {
                            Label {
                                Text(collection.title + (collection.bookCount > 0
                                    ? " · " + ReadingBookFacts.plural(collection.bookCount, "book") : ""))
                            } icon: {
                                Image(systemName: "chevron.right")
                            }
                            .labelStyle(TrailingIconLabel())
                        }
                        .buttonStyle(LinkPillStyle())
                        .accessibilityLabel("Open series \(collection.title)")
                        .padFocusable("series-\(collection.id)") {
                            openRoute(.book(BookRoute(workId: collection.id, title: collection.title)))
                        }
                        .padding(.horizontal, metrics.margin)
                        if let line = ReadingBookFacts.seriesProgress(collection) {
                            Text(line)
                                .font(HubType.body(13, relativeTo: .caption))
                                .foregroundStyle(.white.opacity(0.62))
                                .padding(.horizontal, metrics.margin + 4)
                        }
                        let members = collection.sections.first?.items ?? []
                        SeriesBookStrip(items: members.isEmpty ? [SeriesBookLabels.item(of: collection)] : members,
                                        pad: "strip-\(collection.id)")
                    }
                    .padding(.top, 18)
                }
                if !books.isEmpty {
                    VStack(alignment: .leading, spacing: 0) {
                        RowHeading(title: series.isEmpty ? "Books" : "Other books")
                            .padding(.horizontal, metrics.margin)
                        SeriesBookStrip(items: books.map(SeriesBookLabels.item(of:)), pad: "others")
                    }
                    .padding(.top, 20)
                }
            }
            // A controller goes series by series, its link then its books, then the other books (#46).
            .padGroup("author", .column,
                      members: series.flatMap { ["series-\($0.id)", "strip-\($0.id)"] } + (books.isEmpty ? [] : ["others"]),
                      prefix: false)
            .padding(.bottom, 28)
        }
        .padPage("author:\(route.id)")
        .ambientArtwork(artwork.isEmpty ? (works.first?.artwork ?? "") : artwork)
        .refreshable { reloads += 1 }
        .task(id: "\(route.id)·\(model.userId)·\(readerClosed)·\(reloads)") { await load() }
    }

    private func header(series: [ReadingWork], books: [ReadingWork]) -> some View {
        // Under the name: the shelf, then how far through it you are.
        let progress = series.flatMap { ($0.sections.first?.items ?? []).map(\.progress) } + books.map(\.progress)
        let line = [shelf.isEmpty ? nil : shelf, items == nil ? nil : AuthorLabels.reading(progress)]
            .compactMap { $0 }.joined(separator: " · ")
        let layout = metrics.centred ? AnyLayout(VStackLayout(alignment: .center, spacing: 14))
            : AnyLayout(HStackLayout(alignment: .center, spacing: 26))
        return layout {
            AuthorPortrait(name: route.name, artwork: artwork, size: metrics.small ? 112 : 150)
            VStack(alignment: metrics.centred ? .center : .leading, spacing: 8) {
                Text("AUTHOR")
                    .font(HubType.body(12.5, weight: .bold, relativeTo: .caption))
                    .tracking(1.75)
                    .foregroundStyle(.white.opacity(0.72))
                Text(route.name)
                    .font(HubType.heading(metrics.heroTitle, weight: .heavy))
                    .tracking(-0.02 * metrics.heroTitle)
                    .foregroundStyle(.white)
                    .lineLimit(2)
                    .minimumScaleFactor(0.6)
                if !line.isEmpty {
                    Text(line)
                        .font(HubType.body(15, relativeTo: .subheadline))
                        .foregroundStyle(.white.opacity(0.82))
                }
            }
            .multilineTextAlignment(metrics.centred ? .center : .leading)
        }
        .frame(maxWidth: .infinity, alignment: metrics.centred ? .center : .leading)
    }

    private func load() async {
        if artwork.isEmpty { artwork = route.artwork }
        if shelf.isEmpty { shelf = AuthorLabels.shelf(seriesCount: route.seriesCount, bookCount: route.bookCount, total: route.total) }
        status = StatusText.loading(route.name, refreshing: items != nil)
        var collected: [ReadingWork] = []
        var page = 1
        var pages = 1
        do {
            while page <= pages {
                let response = try await model.hub.fetch(
                    HubEndpoints.readingAuthors(libraryId: route.libraryId, page: page, direction: "asc", authorId: route.id),
                    as: ReadingAuthorsResponse.self)
                let author = response.authors.first
                collected += author?.items ?? []
                pages = author?.totalPages ?? 1
                if let author {
                    shelf = AuthorLabels.shelf(seriesCount: author.seriesCount, bookCount: author.bookCount, total: author.total)
                    // Opened from a book's link, only the name was known.
                    if artwork.isEmpty && !author.artwork.isEmpty { artwork = author.artwork }
                }
                page += 1
            }
        } catch {
            if error.kind == .cancelled { return }
            status = StatusText.failed(error.message, kind: error.kind, hasData: items != nil)
            return
        }
        var seen = Set<String>()
        let works = collected.filter { seen.insert($0.id).inserted }
        items = works
        model.colors.want([artwork] + works.prefix(12).map(\.artwork))
        status = works.isEmpty ? StatusMessage("No books by \(route.name) in this library") : StatusMessage("")
    }
}

/// An author's portrait on their page: their picture or initials, in a
/// white ring (`.authface` large).
struct AuthorPortrait: View {
    let name: String
    let artwork: String
    let size: CGFloat

    var body: some View {
        Group {
            if artwork.isEmpty {
                Text(AuthorFace.initials(name))
                    .font(HubType.heading(size * 0.32, weight: .heavy, relativeTo: .largeTitle))
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(AuthorFace.gradient(name))
            } else {
                ArtworkView(path: artwork, width: 480)
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
        .overlay { Circle().strokeBorder(.white, lineWidth: 4) }
        .shadow(color: .black.opacity(0.45), radius: 20, y: 16)
        .accessibilityHidden(true)
    }
}

/// A label with its icon after its words: "Mistborn · 3 books ›".
struct TrailingIconLabel: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 6) {
            configuration.title
            configuration.icon.font(.system(size: 11, weight: .bold)).foregroundStyle(.white.opacity(0.6))
        }
    }
}
