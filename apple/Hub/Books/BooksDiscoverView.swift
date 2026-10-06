import HubKit
import SwiftUI

/// Books Discover (#25; the prototype's `pgBDiscover`, Android's
/// `DiscoverScreen` in Books): the kinds in a glass capsule beside the search,
/// a featured title, then BookKeeprr's rows, each paging on as its end comes
/// into view. Typing two letters turns the page into results, the close
/// matches first and the broader ones on request. A title opens its request
/// page, or its book page when the library already has it.
struct BooksDiscoverView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.glassMetrics) private var metrics
    @Environment(\.openRoute) private var openRoute

    @SceneStorage("books.discover.type") private var type = ReadingType.all
    @State private var rowsByType: [String: [ReadingDiscoverRow]] = [:]
    @State private var status = StatusMessage("")
    @State private var loading = false
    @State private var paging = RowPaging(prefetchAhead: 6)
    @State private var query = ""
    @State private var lit: String?
    @State private var debugOpened = false

    private var searchText: String { query.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var rows: [ReadingDiscoverRow] { ReadingDiscoverRows.shown(rowsByType[type] ?? [], filter: type) }
    private var featured: ReadingItem? { rows.first.flatMap { ReadingDiscoverRows.feature($0.items) } }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                controls
                    .padding(.horizontal, metrics.margin)
                    .padding(.top, 4)
                if searchText.count >= 2 {
                    BooksSearchGrid(query: searchText, type: type)
                        .id("\(type)·\(searchText)")
                } else if !searchText.isEmpty {
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
        .ambientArtwork(searchText.isEmpty ? (lit ?? featured?.cover ?? "") : "")
        .refreshable { if searchText.isEmpty { await load(force: true) } }
        .task(id: type) { if rowsByType[type] == nil { await load(force: false) } }
    }

    // MARK: Controls

    @ViewBuilder private var controls: some View {
        let kinds = ScrollView(.horizontal, showsIndicators: false) {
            GlassCapsulePicker(items: ReadingType.filters.map { GlassCapsulePicker<String>.Item(id: $0.id, title: $0.label) },
                               selection: type) { chosen in select(chosen) }
        }
        .scrollClipDisabled()
        let search = GlassSearchField(placeholder: "Search books, comics and audio", query: $query)
        if metrics.compact {
            VStack(alignment: .leading, spacing: 10) {
                kinds
                search
            }
        } else {
            // The field's own width follows its words, so typing could tip the
            // row over and move the field (and its keyboard) mid-word: it is
            // measured at a set width instead.
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 10) {
                    kinds.fixedSize()
                    search.frame(minWidth: 220, idealWidth: 260, maxWidth: 460)
                }
                VStack(alignment: .leading, spacing: 10) {
                    kinds
                    search
                }
            }
        }
    }

    private func select(_ chosen: String) {
        guard chosen != type else { return }
        type = chosen
        paging.clear()
        lit = nil
    }

    // MARK: Rows

    @ViewBuilder private var browsing: some View {
        StatusLine(message: status) { Task { await load(force: true) } }
            .padding(.horizontal, metrics.margin)
            .padding(.top, 10)
        if let featured {
            NavigationLink(value: AppRoute.bookRequest(BookRequestRoute(item: featured))) {
                ReadingFeatureCard(item: featured)
            }
            .buttonStyle(GlassCardStyle())
            .previewsWhenFocused { lit = featured.cover }
            .padding(.horizontal, metrics.margin)
            .padding(.top, 16)
            .padding(.bottom, 6)
        }
        ForEach(Array(rows.enumerated()), id: \.element.rowKey) { index, row in
            let items = index == 0 ? ReadingDiscoverRows.shelf(row.items) : row.items
            CardRow(title: row.title) {
                ForEach(Array(items.enumerated()), id: \.element.id) { position, item in
                    NavigationLink(value: AppRoute.bookRequest(BookRequestRoute(item: item))) {
                        ReadingItemCard(item: item)
                            .frame(width: metrics.poster)
                    }
                    .buttonStyle(GlassCardStyle())
                    .previewsWhenFocused { lit = item.cover }
                    .onAppear { pageIfNeeded(row, lastVisible: position, count: items.count) }
                }
            }
        }
    }

    private func load(force: Bool) async {
        guard !loading else { return }
        loading = true
        defer { loading = false }
        let asked = type
        status = StatusText.loading(ReadingType.label(asked).lowercased(), refreshing: rowsByType[asked] != nil)
        do {
            let response = try await model.hub.fetch(HubEndpoints.readingDiscover(type: asked), as: ReadingDiscoverResponse.self)
            rowsByType[asked] = response.rows
            if asked == type { paging.clear() }
            model.colors.want(response.rows.flatMap { $0.items.prefix(10).map(\.cover) })
            status = ReadingDiscoverRows.shown(response.rows, filter: asked).isEmpty ? StatusMessage("Nothing to show yet.")
                : StatusText.caveat(response.cache, unavailable: response.partial.map(\.service))
            #if DEBUG
            applyDebugOpen()
            #endif
        } catch {
            if error.kind == .cancelled { return }
            status = StatusText.failed(error.message, kind: error.kind, hasData: rowsByType[asked] != nil)
        }
    }

    /// Six cards from a row's end, its next page, while BookKeeprr has more.
    private func pageIfNeeded(_ row: ReadingDiscoverRow, lastVisible: Int, count: Int) {
        let asked = type
        let key = row.rowKey
        guard let next = paging.next(key, page: row.page, totalPages: row.hasMore ? row.page + 1 : row.page,
                                     lastVisible: lastVisible, count: count) else { return }
        Task {
            do throws(HubFailure) {
                let response = try await model.hub.fetch(HubEndpoints.readingDiscoverRow(row.id, type: row.contentType, page: next),
                                                         as: ReadingDiscoverResponse.self)
                guard let page = response.rows.first, var list = rowsByType[asked],
                      let index = list.firstIndex(where: { $0.rowKey == key }) else {
                    paging.fail(key, page: next)
                    return
                }
                let known = Set(list[index].items.map(\.id))
                list[index].items += page.items.filter { !known.contains($0.id) }
                list[index].page = page.page
                list[index].hasMore = page.hasMore
                rowsByType[asked] = list
                paging.complete(key, page: next, total: page.hasMore ? next + 1 : next)
            } catch {
                paging.fail(key, page: next)
            }
        }
    }

    #if DEBUG
    /// scripts/mac.sh: HUB_OPEN=search:<words> types a search; request opens
    /// the first title that can be requested; a title's key opens it. Once.
    private func applyDebugOpen() {
        guard !debugOpened, let open = ProcessInfo.processInfo.environment["HUB_OPEN"], !open.isEmpty else { return }
        debugOpened = true
        if open.hasPrefix("search:") {
            query = String(open.dropFirst("search:".count))
            return
        }
        if open.hasPrefix("releases:") {
            let fields = open.dropFirst("releases:".count).split(separator: "|").map(String.init)
            if let seriesId = fields.first.flatMap(Int.init) {
                openRoute(.readingReleases(ReadingReleasesRoute(targets: [ReadingRequestTarget(seriesId: seriesId,
                                                                                              title: fields.last ?? "")])))
            }
            return
        }
        let all = rows.flatMap(\.items)
        let item = open == "request" ? all.first(where: \.canRequest) : all.first { $0.key == open }
        if let item { openRoute(.bookRequest(BookRequestRoute(item: item))) }
    }
    #endif
}

/// A title BookKeeprr knows, as a card: its cover (square for an audiobook),
/// "Tracked" when BookKeeprr already follows it, its name and its line.
struct ReadingItemCard: View {
    let item: ReadingItem

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            BookCover(path: item.cover, square: item.contentType == ReadingType.audiobook)
                .overlay(alignment: .topLeading) {
                    if item.inLibrary { CoverPill(text: "Tracked").padding(6) }
                }
                .litArtwork(corner: 9)
            CardCaption(title: item.title, detail: item.subtitle)
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

/// The first title, large (`.feat`): its cover beside its kind, name, author
/// and what it is about; on a phone held upright the words go under it.
struct ReadingFeatureCard: View {
    let item: ReadingItem
    @Environment(\.glassMetrics) private var metrics

    private static let corner: CGFloat = 26

    var body: some View {
        let layout = metrics.centred ? AnyLayout(VStackLayout(alignment: .leading, spacing: 16))
            : AnyLayout(HStackLayout(alignment: .center, spacing: 24))
        layout {
            BookCover(path: item.cover, square: item.contentType == ReadingType.audiobook, width: 480)
                .frame(width: metrics.centred ? 120 : 150)
                .shadow(color: .black.opacity(0.45), radius: 18, y: 14)
            VStack(alignment: .leading, spacing: 8) {
                Text("FEATURED · \(ReadingType.one(item.contentType).uppercased())")
                    .font(HubType.body(12, weight: .bold, relativeTo: .caption))
                    .tracking(1.6)
                    .foregroundStyle(.white.opacity(0.72))
                Text(item.title)
                    .font(HubType.heading(metrics.small ? 26 : 34, weight: .heavy, relativeTo: .title))
                    .foregroundStyle(.white)
                    .lineLimit(2)
                if !item.subtitle.isEmpty {
                    Text(item.subtitle)
                        .font(HubType.body(14, relativeTo: .subheadline))
                        .foregroundStyle(.white.opacity(0.8))
                }
                if !item.description.isEmpty {
                    Text(item.description)
                        .font(HubType.body(14.5, relativeTo: .body))
                        .foregroundStyle(.white.opacity(0.78))
                        .lineLimit(3)
                        .frame(maxWidth: 560, alignment: .leading)
                }
            }
            .multilineTextAlignment(.leading)
            Spacer(minLength: 0)
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassPanel(RoundedRectangle(cornerRadius: Self.corner, style: .continuous))
        .litRing(corner: Self.corner)
        .contentShape(RoundedRectangle(cornerRadius: Self.corner, style: .continuous))
        .accessibilityElement(children: .combine)
    }
}

/// Books Discover's results: the close matches, then the broader ones on request.
struct BooksSearchGrid: View {
    @Environment(AppModel.self) private var model
    @Environment(\.glassMetrics) private var metrics
    let query: String
    let type: String

    @State private var results = ReadingSearchPresentation()
    @State private var caveat = StatusMessage("")
    @State private var status = StatusMessage("")
    @State private var reloads = 0

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 10) {
                StatusLine(message: status) { reloads += 1 }
                Spacer(minLength: 8)
                if results.canToggle {
                    Button(results.showBroader ? "Close matches" : "Show broader") {
                        results = results.toggledBroader()
                        status = StatusText.loaded(results.summary, caveat: caveat)
                    }
                    .buttonStyle(GlassControlStyle())
                    .accessibilityHint(results.showBroader ? "Shows close matches only" : "Adds broader matches")
                }
            }
            .padding(.horizontal, metrics.margin)
            .padding(.top, 10)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: metrics.small ? 100 : 112, maximum: 180),
                                         spacing: metrics.small ? 12 : 18, alignment: .top)],
                      alignment: .leading, spacing: 20) {
                ForEach(results.visibleResults) { item in
                    NavigationLink(value: AppRoute.bookRequest(BookRequestRoute(item: item))) {
                        ReadingItemCard(item: item)
                    }
                    .buttonStyle(GlassCardStyle())
                }
            }
            .padding(.horizontal, metrics.margin)
            .padding(.top, 14)
        }
        .task(id: reloads) { await search() }
    }

    private func search() async {
        // Typing settles before the hub is asked.
        try? await Task.sleep(for: .milliseconds(350))
        guard !Task.isCancelled else { return }
        status = StatusText.loading("results", refreshing: !results.visibleResults.isEmpty)
        do {
            let response = try await model.hub.fetch(HubEndpoints.readingSearch(query, type: type), as: ReadingSearchResponse.self)
            results = ReadingSearchPresentation.forResults(close: response.results, broader: response.broaderResults)
            caveat = StatusText.caveat(response.cache, unavailable: response.partial.map(\.service))
            status = StatusText.loaded(results.summary, caveat: caveat)
        } catch {
            if error.kind == .cancelled { return }
            status = StatusText.failed(error.message, kind: error.kind, hasData: !results.visibleResults.isEmpty)
        }
    }
}

// MARK: A title to request

/// A title BookKeeprr knows (Android's `ReadingDetailScreen`). One the library
/// already has opens as its book page, in place. Otherwise: its cover, "EBOOK
/// · NOT IN YOUR LIBRARY", who wrote it, what it is about, and Find a download
/// in the accent, which opens the request sheet; once requested, the releases
/// to choose, and where the transfer has got to, read again every 15 seconds.
struct BookRequestView: View {
    @Environment(AppModel.self) private var model
    @Environment(BooksModel.self) private var books
    @Environment(\.glassMetrics) private var metrics
    @Environment(\.glassAccent) private var accent
    @Environment(\.openRoute) private var openRoute
    @Environment(\.selectSection) private var selectSection
    let route: BookRequestRoute

    /// The library's own work for this title, once the hub has said.
    @State private var resolved: String?
    @State private var resolving = true
    @State private var record: BooksModel.RequestRecord?
    @State private var acquisition: ReadingAcquisitionState?
    @State private var status = StatusMessage("")
    @State private var expanded = false
    @State private var sheetOpen = false
    @State private var follow = 0

    private var item: ReadingItem { route.item }
    private var requested: Bool { (record?.seriesId ?? 0) > 0 || !(record?.targets.isEmpty ?? true) }
    private var targets: [ReadingRequestTarget] { record?.targets ?? [] }

    var body: some View {
        Group {
            if let resolved {
                BookView(route: BookRoute(workId: resolved, title: item.title))
            } else {
                page
            }
        }
        .task(id: item.id) { await resolve() }
    }

    private var page: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                header
                    .padding(.horizontal, metrics.margin)
                    .padding(.top, 6)
            }
            .padding(.bottom, 28)
        }
        .ambientArtwork(item.cover)
        .task(id: "\(item.id)·\(follow)") { await followRequest() }
        .sheet(isPresented: $sheetOpen) {
            BookRequestSheet(item: item) { response in
                let saved = BooksModel.RequestRecord(seriesId: response.seriesId > 0 ? response.seriesId
                                                         : response.targets.first?.seriesId ?? 0,
                                                     targets: response.targets)
                books.rememberRequest(item.key, saved)
                record = saved
                status = StatusMessage(response.message.isEmpty ? "BookKeeprr is searching for \(item.title)" : response.message)
                follow += 1
                if !response.targets.isEmpty {
                    openRoute(.readingReleases(ReadingReleasesRoute(targets: response.targets)))
                }
            }
            .environment(books)
        }
    }

    @ViewBuilder private var header: some View {
        let layout = metrics.centred ? AnyLayout(VStackLayout(alignment: .leading, spacing: 18))
            : AnyLayout(HStackLayout(alignment: .top, spacing: metrics.short ? 22 : 34))
        let square = item.contentType == ReadingType.audiobook
        layout {
            BookCover(path: item.cover, square: square, width: 600)
                .frame(width: metrics.short ? (square ? 150 : 120) : metrics.centred ? (square ? 210 : 170) : (square ? 260 : 220))
                .shadow(color: .black.opacity(0.5), radius: 26, y: 26)
                .frame(maxWidth: metrics.centred ? .infinity : nil)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 10) {
                Text("\(ReadingType.one(item.contentType)) · \(item.inLibrary ? "Tracked in BookKeeprr" : "Not in your library")"
                    .uppercased())
                    .font(HubType.body(12.5, weight: .bold, relativeTo: .caption))
                    .tracking(1.75)
                    .foregroundStyle(.white.opacity(0.72))
                Text(item.title)
                    .font(HubType.heading(metrics.heroTitle, weight: .heavy))
                    .tracking(-0.02 * metrics.heroTitle)
                    .foregroundStyle(.white)
                    .lineLimit(3)
                    .minimumScaleFactor(0.55)
                let facts = ([item.author.isEmpty ? nil : item.author, item.year > 0 ? String(item.year) : nil,
                              ReadingType.one(item.contentType)] as [String?]).compactMap { $0 }
                Text(facts.joined(separator: " · "))
                    .font(HubType.body(15, relativeTo: .subheadline))
                    .foregroundStyle(.white.opacity(0.82))
                if [ReadingType.ebook, ReadingType.audiobook].contains(item.contentType) {
                    FormatChips(statuses: ReadingFormatStatus.unknown())
                }
                if !item.description.isEmpty {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(item.description)
                            .font(HubType.body(15, relativeTo: .body))
                            .foregroundStyle(.white.opacity(0.86))
                            .lineLimit(expanded ? nil : 3)
                            .frame(maxWidth: 640, alignment: .leading)
                        Button(expanded ? "Collapse description" : "Read more") { expanded.toggle() }
                            .font(HubType.body(13, weight: .bold, relativeTo: .footnote))
                            .foregroundStyle(.white.opacity(0.7))
                            .buttonStyle(.plain)
                    }
                }
                if !resolving, ReadingRequestActionPolicy.showAction(tracked: item.inLibrary, canRequest: item.canRequest,
                                                                     hasReleaseTargets: !targets.isEmpty) {
                    Button(action: act) {
                        Label(actionLabel, systemImage: requested ? "arrow.down.circle" : "magnifyingglass")
                    }
                    .buttonStyle(PrimaryPillStyle(accent: accent))
                    .padding(.top, 4)
                    .accessibilityIdentifier("book-request")
                }
                StatusLine(message: shownStatus) { follow += 1 }
                let source = ([item.source.isEmpty ? nil : ReadingType.source(item.source),
                               item.isbn.isEmpty ? nil : "ISBN \(item.isbn)"] as [String?]).compactMap { $0 }
                if !source.isEmpty {
                    Text(source.joined(separator: " · "))
                        .font(HubType.body(12.5, relativeTo: .caption))
                        .foregroundStyle(.white.opacity(0.55))
                        .padding(.top, 6)
                }
            }
            .frame(maxWidth: 760, alignment: .leading)
            .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var shownStatus: StatusMessage {
        if let acquisition {
            return StatusMessage(acquisition.message, tone: acquisition.stage == .failed ? .error : .normal)
        }
        return status
    }

    /// Find a download; once requested, Choose release while a release is
    /// still to choose, Open Library once imported, else Open Transfers.
    private var actionLabel: String {
        if let acquisition { return acquisition.nextAction }
        if requested { return targets.isEmpty ? "Open Transfers" : "Choose release" }
        return "Find a download"
    }

    private func act() {
        if requested && acquisition?.stage == .imported {
            selectSection(.library)
        } else if requested && (acquisition == nil || acquisition?.stage == .awaitingChoice) && !targets.isEmpty {
            openRoute(.readingReleases(ReadingReleasesRoute(targets: targets)))
        } else if requested {
            selectSection(.activity)
        } else {
            sheetOpen = true
        }
    }

    /// An ebook or audiobook the library already has is its book page.
    private func resolve() async {
        record = books.requestRecord(item.key)
        defer {
            resolving = false
            #if DEBUG
            // scripts/mac.sh: HUB_SHEET=request opens the request sheet once the page is there.
            if resolved == nil, item.canRequest, !requested, ProcessInfo.processInfo.environment["HUB_SHEET"] == "request" {
                sheetOpen = true
            }
            #endif
        }
        guard [ReadingType.ebook, ReadingType.audiobook].contains(item.contentType) else { return }
        if let answer = try? await model.hub.fetch(HubEndpoints.readingResolve(source: item.source, sourceId: item.sourceId,
                                                                               isbn: item.isbn), as: ReadingResolveResponse.self),
           answer.resolved, !answer.workId.isEmpty {
            resolved = answer.workId
        }
    }

    /// Where the request has got to, every 15 seconds until it lands or fails.
    /// An earlier request is found from its exact title, kind and author.
    private func followRequest() async {
        guard item.canRequest || requested else { return }
        while !Task.isCancelled {
            if let downloads = try? await model.hub.fetch(HubEndpoints.readingDownloads, as: ReadingDownloadsResponse.self) {
                var seriesId = record?.seriesId ?? 0
                if seriesId == 0 {
                    seriesId = ReadingAcquisitionState.findSeriesId(title: item.title, author: item.author,
                                                                    contentType: item.contentType, downloads: downloads.items)
                    if seriesId > 0 {
                        let found = BooksModel.RequestRecord(seriesId: seriesId, targets: targets)
                        books.rememberRequest(item.key, found)
                        record = found
                    }
                }
                guard seriesId > 0 else { return }
                let state = ReadingAcquisitionState.from(seriesId: seriesId, downloads: downloads.items,
                                                         manualSelection: !targets.isEmpty)
                acquisition = state
                if state.terminal { return }
            } else if requested {
                status = StatusMessage("Download status unavailable · check Transfers", tone: .warning)
            }
            try? await Task.sleep(for: .seconds(15))
        }
    }
}

/// The request as a glass sheet (Android's `ReadingRequestFlow`): what to
/// download and the quality, then for a series the books to ask for, and
/// nothing sent until its last button. Automatic grabbing stays off; the
/// release is chosen next.
struct BookRequestSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(\.glassAccent) private var accent
    let item: ReadingItem
    let requested: (ReadingRequestResponse) -> Void

    private enum Step {
        case form
        case scopes(ReadingSeriesPreviewResponse, chosen: Int)
        case books(ReadingSeriesPreview, ReadingSeriesSelection, from: ReadingSeriesPreviewResponse?)
    }

    @State private var draft: ReadingRequestDraft?
    @State private var step = Step.form
    @State private var status = StatusMessage("")
    @State private var working = false
    @State private var loads = 0

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
                .padding(.horizontal, 20)
                .padding(.top, 22)
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    StatusLine(message: status) { loads += 1 }
                    content
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 12)
            }
            .scrollBounceBehavior(.basedOnSize)
            footer
                .padding(.horizontal, 20)
                .padding(.bottom, 24)
                .padding(.top, 6)
        }
        .foregroundStyle(.white)
        .presentationBackground { GlassSheetFill() }
        .presentationCornerRadius(sizeClass == .compact ? 32 : 28)
        #if os(iOS)
        .presentationDetents(sizeClass == .compact ? [.medium, .large] : [.large])
        #else
        .frame(minWidth: 480, minHeight: 540)
        #endif
        .task(id: loads) { await loadOptions() }
    }

    // MARK: Parts

    private var header: some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(alignment: .leading, spacing: 5) {
                Text(heading)
                    .font(HubType.heading(23, weight: .heavy, relativeTo: .title2))
                    .lineLimit(2)
                if !subheading.isEmpty {
                    Text(subheading)
                        .font(HubType.body(13, relativeTo: .footnote))
                        .foregroundStyle(.white.opacity(0.65))
                }
            }
            Spacer(minLength: 8)
            if case .form = step {
                GlassRoundButton(systemImage: "xmark", label: "Close", size: 38) { dismiss() }
                    .disabled(working)
            } else {
                GlassRoundButton(systemImage: "chevron.left", label: "Back", size: 38) { back() }
                    .disabled(working)
            }
        }
    }

    private var heading: String {
        switch step {
        case .form: draft?.heading(fallbackTitle: item.title) ?? "Download \(item.title)"
        case .scopes: "Choose the collection"
        case .books(let scope, _, _): scope.name
        }
    }

    private var subheading: String {
        switch step {
        case .form: draft?.subtitle ?? ""
        case .scopes: "This book belongs to more than one verified series list."
        case .books(let scope, let selection, _):
            ([scope.author.isEmpty ? nil : scope.author, scope.ordering.isEmpty ? nil : "publication order",
              "\(selection.selectedIds.count) selected · \(selection.books.filter { !$0.inLibrary }.count) missing"] as [String?])
                .compactMap { $0 }.joined(separator: " · ")
        }
    }

    @ViewBuilder private var content: some View {
        switch step {
        case .form:
            if let draft, draft.usable { form(draft) }
        case .scopes(let response, let chosen):
            group {
                ForEach(Array(response.scopes.enumerated()), id: \.offset) { index, scope in
                    if index > 0 { Divider().overlay(Color.white.opacity(0.08)) }
                    tickRow(label: scope.name, detail: ReadingRequestDraft.scopeDetail(scope), on: index == chosen) {
                        step = .scopes(response, chosen: index)
                    }
                }
            }
        case .books(let scope, let selection, let from):
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 104, maximum: 140), spacing: 12, alignment: .top)],
                      alignment: .leading, spacing: 16) {
                ForEach(Array(selection.books.enumerated()), id: \.offset) { index, book in
                    Button {
                        var next = selection
                        next.toggle(index)
                        step = .books(scope, next, from: from)
                    } label: {
                        PreviewBookCard(book: book, selected: selection.isSelected(index))
                    }
                    .buttonStyle(.plain)
                    .disabled(book.inLibrary)
                }
            }
        }
    }

    @ViewBuilder private var footer: some View {
        switch step {
        case .form:
            Button(action: submitForm) {
                Label(working ? "Working…" : (draft?.submitLabel ?? "Choose release"), systemImage: "arrow.down.to.line")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(PrimaryPillStyle(accent: accent))
            .disabled(working || !(draft?.usable ?? false))
        case .scopes(let response, let chosen):
            let scope = response.scopes[min(chosen, response.scopes.count - 1)]
            Button {
                step = .books(scope, ReadingSeriesSelection(books: scope.books), from: response)
            } label: {
                Text("Review \(ReadingBookFacts.plural(scope.books.count, "book"))").frame(maxWidth: .infinity)
            }
            .buttonStyle(PrimaryPillStyle(accent: accent))
        case .books(let scope, let selection, let from):
            HStack(spacing: 10) {
                Button("Select missing") {
                    var next = selection
                    next.toggleAllMissing()
                    step = .books(scope, next, from: from)
                }
                .buttonStyle(GlassPillStyle())
                Button {
                    guard let body = draft?.body(seriesId: scope.seriesId, bookIds: selection.selectedIds) else { return }
                    Task { await submit(body) }
                } label: {
                    Text(working ? "Working…" : "Download \(selection.selectedIds.count)").frame(maxWidth: .infinity)
                }
                .buttonStyle(PrimaryPillStyle(accent: accent))
                .disabled(working || selection.selectedIds.isEmpty)
            }
        }
    }

    private func form(_ draft: ReadingRequestDraft) -> some View {
        group {
            choiceRow(label: "What to download", detail: draft.mode?.requiresSeriesPreview == true ? "You tick the series' books next" : "",
                      value: draft.mode?.label ?? "") {
                Picker("What to download", selection: binding(\.modeIndex)) {
                    ForEach(draft.options.modes.indices, id: \.self) { index in
                        Text(draft.options.modes[index].label).tag(index)
                    }
                }
            }
            Divider().overlay(Color.white.opacity(0.08))
            choiceRow(label: "Quality profile", detail: "", value: draft.profile?.label ?? "") {
                Picker("Quality profile", selection: binding(\.profileIndex)) {
                    ForEach(draft.options.qualityProfiles.indices, id: \.self) { index in
                        Text(draft.options.qualityProfiles[index].label).tag(index)
                    }
                }
            }
        }
    }

    private func binding(_ path: WritableKeyPath<ReadingRequestDraft, Int>) -> Binding<Int> {
        Binding(get: { draft?[keyPath: path] ?? 0 }, set: { draft?[keyPath: path] = $0 })
    }

    private func group<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        VStack(spacing: 0) { content() }
            .glassPanel(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    private func choiceRow<Choices: View>(label: String, detail: String, value: String,
                                          @ViewBuilder choices: () -> Choices) -> some View {
        Menu {
            choices()
        } label: {
            HStack(spacing: 12) {
                rowWords(label: label, detail: detail)
                Spacer(minLength: 8)
                Text(value)
                    .font(HubType.body(15, weight: .semibold, relativeTo: .subheadline))
                    .lineLimit(1)
                Image(systemName: "chevron.up.chevron.down")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(.white.opacity(0.62))
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 13)
            .contentShape(Rectangle())
        }
        .menuStyle(.button)
        .buttonStyle(.plain)
        .accessibilityValue(value)
    }

    private func tickRow(label: String, detail: String, on: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                rowWords(label: label, detail: detail)
                Spacer(minLength: 8)
                Image(systemName: "checkmark")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(on ? accent.tint : .white.opacity(0.25))
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 13)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(on ? .isSelected : [])
    }

    private func rowWords(label: String, detail: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(HubType.body(15, weight: .bold, relativeTo: .subheadline))
            if !detail.isEmpty {
                Text(detail)
                    .font(HubType.body(12.5, relativeTo: .caption))
                    .foregroundStyle(.white.opacity(0.6))
            }
        }
    }

    // MARK: Loading and sending

    private func back() {
        switch step {
        case .form: dismiss()
        case .scopes: step = .form
        case .books(_, _, let from):
            if let from { step = .scopes(from, chosen: 0) } else { step = .form }
        }
        status = StatusMessage("")
    }

    private func loadOptions() async {
        guard draft == nil else { return }
        status = StatusMessage("Loading download choices…")
        do {
            let options = try await model.hub.fetch(HubEndpoints.readingRequestOptions(key: item.key), as: ReadingRequestOptions.self)
            let next = ReadingRequestDraft(options: options)
            draft = next
            status = next.usable ? StatusMessage("") : StatusMessage("BookKeeprr has no usable quality profile", tone: .error)
        } catch {
            if error.kind == .cancelled { return }
            status = StatusText.failed(error.message, kind: error.kind, hasData: false)
        }
    }

    private func submitForm() {
        guard let draft, let body = draft.body() else { return }
        if draft.needsSeriesPreview {
            Task { await loadPreview() }
        } else {
            Task { await submit(body) }
        }
    }

    private func loadPreview() async {
        working = true
        defer { working = false }
        status = StatusMessage("Verifying the series books…")
        do {
            let response = try await model.hub.fetch(HubEndpoints.readingSeriesPreview(key: item.key),
                                                     as: ReadingSeriesPreviewResponse.self)
            switch response.scopes.count {
            case 0: status = StatusMessage("No verified books were found for this series", tone: .error)
            case 1:
                status = StatusMessage("")
                step = .books(response.scopes[0], ReadingSeriesSelection(books: response.scopes[0].books), from: nil)
            default:
                status = StatusMessage("")
                step = .scopes(response, chosen: 0)
            }
        } catch {
            if error.kind == .cancelled { return }
            status = StatusText.failed(error.message, kind: error.kind, hasData: false)
        }
    }

    private func submit(_ body: ReadingCreateRequestBody) async {
        guard !working else { return }
        working = true
        defer { working = false }
        status = StatusMessage(body.bookIds.isEmpty ? "Starting BookKeeprr search…" : "Starting \(body.bookIds.count) book searches…")
        do {
            let response = try await model.hub.fetch(HubEndpoints.createReadingRequest(body), as: ReadingRequestResponse.self)
            dismiss()
            requested(response)
        } catch {
            if error.kind == .cancelled { return }
            status = StatusText.failed(error.message, kind: error.kind, hasData: false, canRetry: false)
        }
    }
}

/// A book of a series to tick in the request sheet: its cover, "Book 2" with
/// its tick (or "Tracked" when BookKeeprr has it), and its title.
struct PreviewBookCard: View {
    let book: ReadingSeriesPreviewBook
    let selected: Bool
    @Environment(\.glassAccent) private var accent

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            BookCover(path: book.cover, width: 240)
                .overlay(alignment: .topTrailing) {
                    if selected {
                        Image(systemName: "checkmark")
                            .font(.system(size: 11, weight: .heavy))
                            .foregroundStyle(accent.inkColor)
                            .frame(width: 22, height: 22)
                            .background(accent.tint, in: Circle())
                            .padding(6)
                    }
                }
                .overlay { BookShape().strokeBorder(selected ? accent.tint : .clear, lineWidth: 3) }
            Text(book.inLibrary ? "Tracked" : (book.position > 0 ? "Book \(book.position)" : " "))
                .font(HubType.body(11.5, weight: .bold, relativeTo: .caption2))
                .foregroundStyle(selected ? accent.tint : .white.opacity(0.6))
            Text(book.title)
                .font(HubType.body(12.5, weight: .semibold, relativeTo: .caption))
                .foregroundStyle(.white)
                .lineLimit(2)
        }
        .opacity(book.inLibrary ? 0.56 : 1)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Book \(book.position), \(book.title)")
        .accessibilityValue(book.inLibrary ? "Tracked in BookKeeprr" : selected ? "Selected" : "Not selected")
    }
}

// MARK: Choosing a release

/// BookKeeprr's interactive search for the books a request named (Android's
/// `ReadingReleasePickerScreen`): which book (when several), the releases
/// with their size, seeders, indexer and format, those outside the profile
/// or of the wrong format said so, and a confirmation before one is grabbed.
struct ReadingReleasesView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.glassMetrics) private var metrics
    let route: ReadingReleasesRoute

    @State private var targetIndex = 0
    @State private var releases: [ReadingRelease] = []
    @State private var status = StatusMessage("")
    @State private var searching = false
    @State private var grabbing = false
    @State private var confirming: ReadingRelease?
    @State private var searches = 0

    private var target: ReadingRequestTarget? {
        route.targets.indices.contains(targetIndex) ? route.targets[targetIndex] : nil
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                PageHeading(title: "Choose a release") { EmptyView() }
                HStack(spacing: 10) {
                    if ReadingReleasePickerPolicy.hasTargetChooser(route.targets.count) {
                        Menu {
                            ForEach(Array(route.targets.enumerated()), id: \.offset) { index, item in
                                Button {
                                    choose(index)
                                } label: {
                                    if index == targetIndex { Label(item.title, systemImage: "checkmark") } else { Text(item.title) }
                                }
                            }
                        } label: {
                            Text("\(targetIndex + 1) of \(route.targets.count) · \(target?.title ?? "") ▾").lineLimit(1)
                        }
                        .menuStyle(.button)
                        .buttonStyle(GlassControlStyle())
                    } else if let target {
                        Text(target.title)
                            .font(HubType.body(17, weight: .bold, relativeTo: .headline))
                            .foregroundStyle(.white)
                    }
                    Spacer(minLength: 8)
                    Button {
                        searches += 1
                    } label: {
                        Label("Search again", systemImage: "arrow.clockwise")
                    }
                    .buttonStyle(GlassControlStyle())
                    .disabled(searching || grabbing)
                }
                StatusLine(message: status) { searches += 1 }
                LazyVStack(spacing: 8) {
                    ForEach(releases) { release in
                        Button {
                            pick(release)
                        } label: {
                            ReadingReleaseRow(release: release)
                        }
                        .buttonStyle(GlassCardStyle())
                    }
                }
            }
            .padding(.horizontal, metrics.margin)
            .padding(.top, 4)
            .padding(.bottom, 28)
        }
        .task(id: "\(targetIndex)·\(searches)") { await search() }
        .confirmationDialog(confirming?.title ?? "", isPresented: Binding(get: { confirming != nil },
                                                                           set: { if !$0 { confirming = nil } }),
                            titleVisibility: .visible) {
            if let release = confirming, release.canGrab {
                Button(release.rejected ? "Download anyway" : "Download this release") {
                    Task { await grab(release) }
                }
            }
            Button("Choose another", role: .cancel) { confirming = nil }
        } message: {
            if let release = confirming { Text(confirmText(release)) }
        }
    }

    private func choose(_ index: Int) {
        guard !grabbing, index != targetIndex else { return }
        targetIndex = index
        releases = []
    }

    private func pick(_ release: ReadingRelease) {
        if release.ownership != "none" && !release.ownership.isEmpty {
            status = StatusMessage(release.ownership == "in-library" ? "Already in the library" : "Already downloading")
            return
        }
        confirming = release
    }

    private func confirmText(_ release: ReadingRelease) -> String {
        var text = "\(Fmt.bytes(release.sizeBytes)) · \(release.seeders) seeders · \(release.indexer)\n\(release.formatLabel)"
        if !release.canGrab {
            text += " · " + (release.reason.isEmpty ? "This format cannot be used for this request" : release.reason)
        } else if release.rejected {
            text += "\nOutside profile: " + (release.reason.isEmpty ? "BookKeeprr did not match this release" : release.reason)
        }
        return text
    }

    /// A live search of every indexer; when that fails, what BookKeeprr saved.
    private func search() async {
        guard let target, target.seriesId > 0 else {
            status = StatusMessage("No BookKeeprr series is available for this title", tone: .error)
            return
        }
        searching = true
        defer { searching = false }
        status = StatusMessage("Searching indexers for \(target.title)…")
        let live: Result<ReadingReleasesResponse, HubFailure>
        do {
            live = .success(try await model.hub.fetch(HubEndpoints.searchReadingReleases(seriesId: target.seriesId),
                                                      as: ReadingReleasesResponse.self))
        } catch {
            if error.kind == .cancelled { return }
            live = .failure(error)
        }
        let result: Result<ReadingReleasesResponse, HubFailure>
        if case .failure = live {
            do {
                result = .success(try await model.hub.fetch(HubEndpoints.readingReleases(seriesId: target.seriesId),
                                                            as: ReadingReleasesResponse.self))
            } catch {
                if error.kind == .cancelled { return }
                result = .failure(error)
            }
        } else {
            result = live
        }
        guard !Task.isCancelled else { return }
        switch result {
        case .success(let response):
            releases = response.releases
            let outside = response.releases.filter { $0.rejected && $0.canGrab }.count
            let wrong = response.releases.filter { !$0.canGrab }.count
            var showingSaved = false
            if case .failure = live { showingSaved = true }
            status = StatusMessage(ReadingReleasePickerPolicy.summary(total: response.releases.count, outsideProfile: outside,
                                                                      wrongFormat: wrong, indexerErrors: response.errors.count,
                                                                      showingSaved: showingSaved))
        case .failure(let failure):
            if case .failure(let first) = live, first.message != failure.message {
                status = StatusMessage("\(first.message) · \(failure.message)", tone: .error, offersRetry: true)
            } else {
                status = StatusText.failed(failure.message, kind: failure.kind, hasData: !releases.isEmpty)
            }
        }
    }

    private func grab(_ release: ReadingRelease) async {
        guard !grabbing, let target else { return }
        grabbing = true
        defer { grabbing = false }
        confirming = nil
        status = StatusMessage("Sending your choice to BookKeeprr…")
        do {
            try await model.hub.send(HubEndpoints.grabReadingRelease(seriesId: target.seriesId, releaseId: release.id))
            status = StatusMessage("Download queued · track it in Books activity")
            if let index = releases.firstIndex(where: { $0.id == release.id }) { releases[index].ownership = "downloading" }
        } catch {
            if error.kind == .cancelled { return }
            status = StatusMessage(error.message + " · Search again to retry", tone: .error)
        }
    }
}

/// A release in the picker: its name, "4.1 MB · 12 seeds · Indexer · EPUB ·
/// matches request", and in red why it is outside the profile, the wrong
/// format, or already here.
struct ReadingReleaseRow: View {
    let release: ReadingRelease

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(release.title)
                .font(HubType.body(14.5, weight: .bold, relativeTo: .subheadline))
                .foregroundStyle(.white)
                .lineLimit(2)
                .multilineTextAlignment(.leading)
            Text(ReadingReleasePickerPolicy.info(release))
                .font(HubType.body(12.5, relativeTo: .caption))
                .foregroundStyle(.white.opacity(0.66))
            let warning = ReadingReleasePickerPolicy.warning(release)
            if !warning.isEmpty {
                Text(warning)
                    .font(HubType.body(12.5, weight: .semibold, relativeTo: .caption))
                    .foregroundStyle(Color(argb: 0xFFFF_8A80))
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 11)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassPanel(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .litRing(corner: 14)
        .contentShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .accessibilityElement(children: .combine)
    }
}
