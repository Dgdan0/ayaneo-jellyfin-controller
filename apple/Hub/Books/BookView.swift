import HubKit
import SwiftUI

/// A book, a series or a comic run (#25; the prototype's `pgBook` and
/// `pgBSeries`, Android's `ReadingWorkScreen`).
///
/// A book: its cover (square for an audiobook), "Book 6 · Red Rising", its
/// facts, its author and series as links, the formats with the missing ones
/// dimmed, how far through, the story, then Read, Listen or Read along in the
/// accent with Change format, Want to Read and the lists, and "More in" its
/// series. A series: its fan, Continue · Book 6 and the continue card, its
/// books in reading order. A comic run: Continue · Issue 51, its volumes as
/// pills and each volume's issues, which open the comic reader.
struct BookView: View {
    @Environment(AppModel.self) private var model
    @Environment(BooksModel.self) private var books
    @Environment(\.glassMetrics) private var metrics
    @Environment(\.glassAccent) private var accent
    @Environment(\.openRoute) private var openRoute
    @Environment(\.read) private var read
    @Environment(\.readerClosed) private var readerClosed
    let route: BookRoute

    /// The book as the hub sent it; shown as the person marked it (`work`).
    @State private var loaded: ReadingWork?
    /// Unread straight after read gives back what was there; leaving the page ends that.
    @State private var completionSession = ReadingCompletionSession()
    /// The book's series, for "More in"; its id, so another book's never shows.
    @State private var series: (id: String, items: [ReadingSectionItem])?
    @State private var status = StatusMessage("")
    @State private var expanded = false
    /// A format chosen with Change format and not yet opened.
    @State private var preview: ReadingEntryChoice?
    @State private var volume = 0
    @State private var entryOpened = false
    @State private var naming = false
    @State private var listName = ""
    @State private var notice = ""
    /// Remove offline copy, asked about: the book and how much this device keeps of it.
    @State private var removing: (work: ReadingWork, bytes: Int64)?
    @State private var reloads = 0
    @State private var lit: String?
    /// Shown before: coming back to the page (from the audiobook's) reads it again.
    @State private var appeared = false

    private var work: ReadingWork? { loaded.map(books.project) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Group {
                    if let work {
                        header(work)
                    } else {
                        Text(route.title)
                            .font(HubType.heading(metrics.pageTitle, weight: .heavy, relativeTo: .largeTitle))
                            .foregroundStyle(.white)
                    }
                }
                .padding(.horizontal, metrics.margin)
                .padding(.top, 6)
                StatusLine(message: shownStatus) { reloads += 1 }
                    .padding(.horizontal, metrics.margin)
                    .padding(.top, 10)
                if let work {
                    if work.isSeries, let point = work.continueAt {
                        SeriesContinueCard(series: work, point: point) { continueSeries(work, point) }
                            .padding(.horizontal, metrics.margin)
                            .padding(.top, 16)
                    }
                    sections(work)
                    moreInSeries(work)
                }
            }
            .padding(.bottom, 28)
        }
        .ambientArtwork(lit ?? work?.artwork ?? "")
        .refreshable { reloads += 1 }
        .task(id: "\(route.workId)·\(model.userId)·\(readerClosed)·\(reloads)") { await load() }
        .onAppear {
            if appeared { reloads += 1 }
            appeared = true
        }
        .onDisappear { completionSession.leave() }
        .alert("New reading list", isPresented: $naming) {
            TextField("List name", text: $listName)
            Button("Cancel", role: .cancel) {}
            Button("Create") { createList() }
        }
        .alert("Remove offline copy?", isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } })) {
            Button("Keep offline copy", role: .cancel) {}
            Button("Remove from this device", role: .destructive) { removeOffline() }
        } message: {
            if let removing {
                Text("\(removing.work.title) · \(Fmt.bytes(removing.bytes)) on this device. Removes downloaded text, audio and cached comic pages for this title. Server files, bookmarks and reading progress are kept.")
            }
        }
    }

    private var shownStatus: StatusMessage {
        status.text.isEmpty && !notice.isEmpty ? StatusMessage(notice) : status
    }

    // MARK: The header

    @ViewBuilder private func header(_ work: ReadingWork) -> some View {
        if metrics.centred {
            VStack(alignment: .leading, spacing: 18) {
                cover(work).frame(maxWidth: .infinity)
                words(work)
            }
        } else {
            HStack(alignment: .top, spacing: metrics.short ? 22 : 34) {
                cover(work)
                words(work)
            }
        }
    }

    @ViewBuilder private func cover(_ work: ReadingWork) -> some View {
        if work.isSeries {
            CoverFan(covers: ReadingShelves.fanCovers(work), coverWidth: metrics.short ? 80 : metrics.centred ? 96 : 118)
        } else {
            let square = work.kind == "audiobook"
            BookCover(path: work.artwork, square: square, width: 600)
                .overlay(alignment: .bottomLeading) {
                    if let kind = ReadingBookFacts.kindTag(work.kind) { CoverPill(text: kind).padding(8) }
                }
                .frame(width: metrics.short ? (square ? 150 : 120) : metrics.centred ? (square ? 210 : 170) : (square ? 260 : 220))
                .shadow(color: .black.opacity(0.5), radius: 26, y: 26)
                .accessibilityHidden(true)
        }
    }

    private func words(_ work: ReadingWork) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(ReadingBookFacts.eyebrow(work, library: books.libraryNames.name(of: work.libraryId) ?? "").uppercased())
                .font(HubType.body(12.5, weight: .bold, relativeTo: .caption))
                .tracking(1.75)
                .foregroundStyle(.white.opacity(0.72))
            Text(work.title)
                .font(HubType.heading(metrics.heroTitle, weight: .heavy))
                .tracking(-0.02 * metrics.heroTitle)
                .foregroundStyle(.white)
                .lineLimit(3)
                .minimumScaleFactor(0.55)
            let line = work.isSeries ? ReadingBookFacts.seriesLine(work) : ReadingBookFacts.line(work, progress: nil)
            if !line.isEmpty {
                Text(line)
                    .font(HubType.body(15, relativeTo: .subheadline))
                    .foregroundStyle(.white.opacity(0.82))
            }
            links(work)
            if !work.isSeries && ReadingBookFacts.kindTag(work.kind) == nil {
                let statuses = ReadingFormatStatus.forWork(work)
                if !statuses.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) { FormatChips(statuses: statuses) }
                        .scrollClipDisabled()
                }
            }
            progressRow(work)
            if !work.overview.isEmpty { overview(work.overview) }
            actions(work).padding(.top, 4)
        }
        .frame(maxWidth: 760, alignment: .leading)
        .fixedSize(horizontal: false, vertical: true)
    }

    /// The author and the series as glass links: "Pierce Brown", "Red Rising #6".
    @ViewBuilder private func links(_ work: ReadingWork) -> some View {
        let libraryId = work.libraryId.isEmpty ? HubEndpoints.storytellerBooks : work.libraryId
        if !work.authorRefs.isEmpty || (!work.isSeries && !work.seriesId.isEmpty) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(work.authorRefs, id: \.id) { author in
                        NavigationLink(value: AppRoute.author(AuthorRoute(libraryId: libraryId, id: author.id, name: author.name))) {
                            Label(author.name, systemImage: "person")
                        }
                        .buttonStyle(LinkPillStyle())
                        .accessibilityHint("Opens their books")
                    }
                    if !work.isSeries && !work.seriesId.isEmpty {
                        NavigationLink(value: AppRoute.book(BookRoute(workId: work.seriesId, title: work.series))) {
                            Label {
                                Text(seriesLink(work))
                            } icon: {
                                Image(systemName: "books.vertical")
                            }
                        }
                        .buttonStyle(LinkPillStyle())
                        .accessibilityLabel("Series \(work.series)" + (work.seriesNumber.isEmpty ? "" : ", book \(work.seriesNumber)"))
                    }
                }
            }
            .scrollClipDisabled()
        }
    }

    private func seriesLink(_ work: ReadingWork) -> AttributedString {
        var text = AttributedString(work.series.isEmpty ? "Series" : work.series)
        if !work.seriesNumber.isEmpty {
            var number = AttributedString("  #" + work.seriesNumber)
            number.foregroundColor = accent.tint
            text += number
        }
        return text
    }

    @ViewBuilder private func progressRow(_ work: ReadingWork) -> some View {
        let fraction = work.progress.map { $0.completed ? 1 : $0.percentage } ?? 0
        let label = (work.isSeries ? ReadingBookFacts.seriesProgress(work) : ReadingBookFacts.progress(work)) ?? ""
        if fraction > 0 || !label.isEmpty {
            HStack(spacing: 12) {
                // The words keep their room; the bar takes what is left, up to its own length.
                HeroProgress(progress: fraction, accent: accent.tint)
                    .frame(minWidth: 60, maxWidth: metrics.small ? 120 : 220)
                    .accessibilityHidden(true)
                Text(label)
                    .font(HubType.body(13, relativeTo: .caption))
                    .foregroundStyle(.white.opacity(0.82))
                    .monospacedDigit()
                    .lineLimit(1)
                    .fixedSize()
            }
            .frame(height: 18)
            .accessibilityElement(children: .combine)
        }
    }

    private func overview(_ text: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(text)
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

    // MARK: The actions

    @ViewBuilder private func actions(_ work: ReadingWork) -> some View {
        if work.isSeries {
            if let point = work.continueAt {
                Button {
                    continueSeries(work, point)
                } label: {
                    Label(ReadingBookFacts.continueLabel(point, kind: work.kind), systemImage: "book")
                }
                .buttonStyle(PrimaryPillStyle(accent: accent))
                .accessibilityIdentifier("book-entry")
            }
        } else {
            let remembered = books.entryPreference(work.id)
            let menu = ReadingFormatMenu.forWork(work, remembered: remembered)
            // The words of Change format give way to its mark where the row would not fit.
            ViewThatFits(in: .horizontal) {
                actionRow(work, menu: menu, remembered: remembered, compact: false)
                actionRow(work, menu: menu, remembered: remembered, compact: true)
            }
        }
    }

    private func actionRow(_ work: ReadingWork, menu: ReadingFormatMenu, remembered: ReadingEntryPreference?,
                           compact: Bool) -> some View {
        HStack(spacing: metrics.small ? 8 : 10) {
            if let choice = preview ?? menu.defaultChoice {
                Button {
                    launch(work, choice, remembered: remembered)
                } label: {
                    Label(menu.entryLabel(choice, remembered: remembered != nil, preview: preview),
                          systemImage: Self.icon(choice.mode))
                        .lineLimit(1)
                }
                .buttonStyle(PrimaryPillStyle(accent: accent))
                .fixedSize()
                .accessibilityIdentifier("book-entry")
            }
            if menu.options.count > 1 {
                Menu {
                    let current = menu.option(for: preview ?? menu.defaultChoice)?.key
                    ForEach(menu.options) { option in
                        Button {
                            preview = option.choice
                        } label: {
                            if option.key == current {
                                Label("\(option.label) · \(option.detail)", systemImage: "checkmark")
                            } else {
                                Text("\(option.label) · \(option.detail)")
                            }
                        }
                    }
                } label: {
                    if compact {
                        Image(systemName: "arrow.left.arrow.right")
                            .font(.system(size: 17, weight: .bold))
                            .foregroundStyle(.white)
                            .frame(width: 46, height: 46)
                            .glassPanel(Circle())
                            .contentShape(Circle())
                    } else {
                        Text("Change format").lineLimit(1)
                    }
                }
                .menuStyle(.button)
                .modifier(FormatMenuStyle(compact: compact))
                .fixedSize()
                .accessibilityLabel("Change format")
                .accessibilityHint("Choose ebook, audiobook or read along")
            }
            if !work.isSeries {
                // Read or unread by hand (#37): restored at once if undone here, else started again.
                let read = work.progress?.completed == true
                GlassRoundButton(systemImage: read ? "checkmark.circle.fill" : "checkmark.circle",
                                 label: read ? "Mark \(work.title) unread" : "Mark \(work.title) read", on: read, size: 46) {
                    toggleRead(work)
                }
                .accessibilityIdentifier("book-read")
            }
            let wanted = books.isWanted(work.id)
            GlassRoundButton(systemImage: wanted ? "bookmark.fill" : "bookmark",
                             label: wanted ? "Remove from Want to Read" : "Add to Want to Read",
                             on: wanted, size: 46) {
                notice = books.toggleWanted(work) ? "Added to Want to Read" : "Removed from Want to Read"
            }
            .accessibilityIdentifier("book-want")
            Menu {
                ReadingListsMenu(work: work) {
                    listName = ""
                    naming = true
                }
                Button {
                    askRemoveOffline(work)
                } label: {
                    Label("Remove offline copy", systemImage: "trash")
                }
                // Last, and in its own words: a preview and a confirmation follow (#34).
                Divider()
                Button(role: .destructive) {
                    openRoute(.removal(RemovalRoute(kind: "reading", id: work.id, title: work.title)))
                } label: {
                    Label(RemovalLines.heading, systemImage: "trash")
                }
                .accessibilityIdentifier("book-delete")
            } label: {
                Image(systemName: "ellipsis")
                    .font(.system(size: 18, weight: .bold))
                    .foregroundStyle(.white)
                    .frame(width: 46, height: 46)
                    .glassPanel(Circle())
                    .contentShape(Circle())
            }
            .menuStyle(.button)
            .buttonStyle(.plain)
            .accessibilityLabel("More actions for \(work.title)")
        }
    }

    static func icon(_ mode: ReadingEntryMode) -> String {
        switch mode {
        case .read: "book"
        case .listen: "headphones"
        case .readAlong: "text.bubble"
        }
    }

    /// Opens a book the way it was chosen, and remembers that way (Android's
    /// `launchEntry`): reading keeps the narration listened to last.
    private func launch(_ work: ReadingWork, _ choice: ReadingEntryChoice, remembered: ReadingEntryPreference?) {
        // Opening a reader ends the undo window, as leaving the page does.
        completionSession.leave()
        switch choice.mode {
        case .read:
            guard let text = choice.text else { return }
            books.setEntryPreference(ReadingEntryPreference(mode: .read, audioSourceItemId: remembered?.audioSourceItemId ?? ""),
                                     for: work.id)
            read(ReadRequest.read(work, text))
        case .listen:
            guard let audio = choice.audio else { return }
            books.setEntryPreference(ReadingEntryPreference(mode: .listen, audioSourceItemId: audio.sourceItemId), for: work.id)
            openRoute(.listen(ListenRoute(workId: work.id, sourceItemId: audio.sourceItemId, title: work.title)))
        case .readAlong:
            guard let aligned = choice.aligned else { return }
            books.setEntryPreference(ReadingEntryPreference(mode: .readAlong, audioSourceItemId: aligned.sourceItemId),
                                     for: work.id)
            read(.ebook(work: work, sourceItemId: aligned.sourceItemId, readAlong: true))
        }
        preview = nil
    }

    /// A series' Continue: the book being read on its own page, opened as it
    /// was last; a comic run's issue straight in the reader.
    private func continueSeries(_ work: ReadingWork, _ point: ReadingContinue) {
        if !point.workId.isEmpty && point.workId != work.id {
            openRoute(.book(BookRoute(workId: point.workId, title: point.title, openEntry: true)))
        } else if ReadingWorkPresentation.canRead(kind: point.kind, sourceItemId: point.sourceItemId) {
            read(ReadRequest.read(work, PrimaryRead(sourceItemId: point.sourceItemId, source: point.source, label: point.title)))
        }
    }

    // MARK: Its books and issues

    @ViewBuilder private func sections(_ work: ReadingWork) -> some View {
        let shown = work.sections.filter { !$0.items.isEmpty }
        if ReadingBookFacts.kindTag(work.kind) != nil && !shown.isEmpty {
            issues(work, volumes: shown)
        } else {
            ForEach(Array(shown.enumerated()), id: \.offset) { _, section in
                VStack(alignment: .leading, spacing: 0) {
                    RowHeading(title: work.isSeries && shown.count == 1 ? "In reading order" : section.title,
                               count: work.isSeries ? nil : "\(section.items.count)")
                        .padding(.horizontal, metrics.margin)
                    SeriesBookStrip(items: section.items, current: work.id)
                }
                .padding(.top, 20)
            }
        }
    }

    /// A comic run's volumes as pills over the chosen one's issues (`.vols`).
    private func issues(_ work: ReadingWork, volumes: [ReadingSection]) -> some View {
        let chosen = volumes.indices.contains(volume) ? volume : 0
        let section = volumes[chosen]
        return VStack(alignment: .leading, spacing: 0) {
            if volumes.count > 1 {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(Array(volumes.enumerated()), id: \.offset) { index, item in
                            ChoicePill(title: "\(item.title) · \(ReadingBookFacts.plural(item.items.count, work.kind == "manga" ? "chapter" : "issue"))",
                                       selected: index == chosen) { volume = index }
                        }
                    }
                    .padding(.horizontal, metrics.margin)
                    .padding(.vertical, 2)
                }
                .padding(.top, 18)
            }
            RowHeading(title: section.title, count: ReadingBookFacts.plural(section.items.count,
                                                                            work.kind == "manga" ? "chapter" : "issue"))
                .padding(.horizontal, metrics.margin)
                .padding(.top, 16)
            IssueStrip(work: work, items: section.items) { item in
                lit = item.artwork.isEmpty ? work.artwork : item.artwork
            }
            .id("\(work.id)·\(chosen)")
        }
        .task(id: work.id) {
            // The volume with the issue being read, else the first.
            if let point = work.continueAt,
               let index = volumes.firstIndex(where: { $0.items.contains { $0.sourceItemId == point.sourceItemId } }) {
                volume = index
            }
        }
    }

    @ViewBuilder private func moreInSeries(_ work: ReadingWork) -> some View {
        if !work.isSeries, let series, series.id == work.seriesId, series.items.count > 1 {
            VStack(alignment: .leading, spacing: 0) {
                RowHeading(title: "More in \(work.series)")
                    .padding(.horizontal, metrics.margin)
                SeriesBookStrip(items: series.items.map(books.completion.project), current: work.id)
            }
            .padding(.top, 20)
        }
    }

    // MARK: Loading

    private func load() async {
        if work == nil { status = StatusText.loading("details", refreshing: false) }
        do {
            let fetched = try await model.hub.fetch(HubEndpoints.readingWork(route.workId), as: ReadingWork.self)
            // The places this device kept and the hub has not had yet (#30).
            let scope = ReadingCheckpointKey.scope(address: model.address, userId: model.userId)
            let response = ReadingProgressPresentation.project(fetched, pending: ListeningStore.shared.pending(scope: scope))
            if response != loaded { loaded = response }
            books.observe([response])
            model.colors.want([response.artwork])
            status = StatusText.caveat(response.cache, unavailable: response.partial.map(\.service))
            if route.openEntry && !entryOpened {
                entryOpened = true
                openEntry(response)
            }
            if !response.isSeries && !response.seriesId.isEmpty {
                let seriesId = response.seriesId
                if let whole = try? await model.hub.fetch(HubEndpoints.readingWork(seriesId), as: ReadingWork.self) {
                    series = (seriesId, whole.sections.flatMap(\.items))
                }
            }
        } catch {
            if error.kind == .cancelled { return }
            status = StatusText.failed(error.message, kind: error.kind, hasData: work != nil)
        }
    }

    /// Marks the book read, or unread: undone at once, its earlier place
    /// comes back; later, its next read starts at the beginning.
    // MARK: Remove offline copy (#37)

    private func askRemoveOffline(_ work: ReadingWork) {
        Task {
            let bytes = await ReadingOffline.bytes(work, app: model)
            if bytes > 0 {
                removing = (work, bytes)
            } else {
                notice = "No offline copy is kept on this device"
            }
        }
    }

    private func removeOffline() {
        guard let work = removing?.work else { return }
        removing = nil
        Task {
            await ReadingOffline.remove(work, app: model)
            notice = "Offline copy removed · reading progress kept"
        }
    }

    private func toggleRead(_ work: ReadingWork) {
        var session = completionSession
        books.updateCompletion { current in
            work.progress?.completed == true ? session.unmark(current, work.id) : session.markRead(current, work.id)
        }
        completionSession = session
        books.updateLists { $0.recordProgress(work.id, percentage: books.project(loaded ?? work).progress?.percentage ?? 0) }
        notice = books.completion.notice(work.id)
    }

    /// Resume reading from Home: the main button's own choice, once.
    private func openEntry(_ work: ReadingWork) {
        if work.isSeries {
            if let point = work.continueAt { continueSeries(work, point) }
            return
        }
        let remembered = books.entryPreference(work.id)
        guard let choice = ReadingFormatMenu.forWork(work, remembered: remembered).defaultChoice else { return }
        launch(work, choice, remembered: remembered)
    }

    private func createList() {
        let name = listName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty, let work else { return }
        let id = UUID().uuidString
        books.updateLists { $0.create(name, id: id).add(id, ReadingListEntry.from(work)) }
        notice = "Added to \(name)"
    }
}

/// Change format: a glass pill with its words, or a round glass mark.
private struct FormatMenuStyle: ViewModifier {
    let compact: Bool

    func body(content: Content) -> some View {
        if compact {
            content.buttonStyle(.plain)
        } else {
            content.buttonStyle(GlassPillStyle())
        }
    }
}

/// A link under a book's title: a small glass pill (`.chip`).
struct LinkPillStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(HubType.body(13, weight: .semibold, relativeTo: .footnote))
            .labelStyle(LinkPillLabel())
            .lineLimit(1)
            .foregroundStyle(.white)
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .glassPanel(Capsule())
            .brightness(configuration.isPressed ? 0.1 : 0)
            #if os(iOS)
            .hoverEffect(.highlight)
            #endif
    }

    private struct LinkPillLabel: LabelStyle {
        func makeBody(configuration: Configuration) -> some View {
            HStack(spacing: 6) {
                configuration.icon.font(.system(size: 11, weight: .semibold)).foregroundStyle(.white.opacity(0.72))
                configuration.title
            }
        }
    }
}

/// "Continue reading · Light Bringer" on a series' page (`.cont`): the book's
/// cover, which book, how far and which page, a bar in the accent, and a
/// white play disc.
struct SeriesContinueCard: View {
    let series: ReadingWork
    let point: ReadingContinue
    let action: () -> Void
    @Environment(\.glassAccent) private var accent

    private var pages: Int {
        series.sections.lazy.flatMap(\.items).first { $0.sourceItemId == point.sourceItemId || (!point.workId.isEmpty && $0.workId == point.workId) }?
            .pageCount ?? 0
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 14) {
                BookCover(path: ReadingWorkPresentation.continueArtwork(series), width: 160)
                    .frame(width: 44)
                    .shadow(color: .black.opacity(0.4), radius: 6, y: 6)
                VStack(alignment: .leading, spacing: 4) {
                    Text("Continue reading · \(point.title)")
                        .font(HubType.body(15, weight: .bold, relativeTo: .subheadline))
                        .foregroundStyle(.white)
                    Text(ReadingBookFacts.continueLine(point, kind: series.kind, pages: pages))
                        .font(HubType.body(13, relativeTo: .caption))
                        .foregroundStyle(.white.opacity(0.66))
                    HeroProgress(progress: point.percentage, accent: accent.tint)
                        .padding(.top, 3)
                        .accessibilityHidden(true)
                }
                .lineLimit(1)
                Spacer(minLength: 8)
                Image(systemName: "play.fill")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(Color.glassInk)
                    .frame(width: 42, height: 42)
                    .background(.white, in: Circle())
            }
            .padding(.leading, 10)
            .padding(.trailing, 12)
            .padding(.vertical, 10)
            .frame(maxWidth: 560, alignment: .leading)
            .glassPanel(RoundedRectangle(cornerRadius: 18, style: .continuous))
            .litRing(corner: 18)
            .contentShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        }
        .buttonStyle(GlassCardStyle())
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Continue reading \(point.title), \(ReadingBookFacts.continueLine(point, kind: series.kind, pages: pages))")
    }
}

/// A comic volume's issues (or a manga volume's chapters) as covers, made
/// only as they scroll into view, opening at the issue being read, else the
/// first not finished (Android's `IssueStrip`). Each opens the comic reader.
struct IssueStrip: View {
    let work: ReadingWork
    let items: [ReadingSectionItem]
    let preview: (ReadingSectionItem) -> Void
    @Environment(\.glassMetrics) private var metrics
    @Environment(\.read) private var read

    private var start: Int {
        items.firstIndex { ($0.progress?.percentage ?? 0) > 0 && $0.progress?.completed != true }
            ?? items.firstIndex { $0.progress?.completed != true } ?? 0
    }

    var body: some View {
        ScrollViewReader { reader in
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: metrics.gap) {
                    ForEach(Array(items.enumerated()), id: \.offset) { index, item in
                        let kind = item.kind.isEmpty ? work.kind : item.kind
                        Button {
                            read(.pages(work: work, publication: item))
                        } label: {
                            // At its own height: a lazy row offers every card the first
                            // one's, and a two-line caption then shrank its cover.
                            IssueCard(item: item, kind: work.kind, fallback: work.artwork)
                                .frame(width: metrics.small ? 92 : 104)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .buttonStyle(GlassCardStyle())
                        .disabled(!ReadingWorkPresentation.canRead(kind: kind, sourceItemId: item.sourceItemId))
                        .previewsWhenFocused { preview(item) }
                        .id(index)
                    }
                }
                .padding(.top, 12)
                .padding(.bottom, 16)
            }
            .contentMargins(.horizontal, metrics.margin, for: .scrollContent)
            .onAppear { if start > 0 { reader.scrollTo(start, anchor: .leading) } }
        }
    }
}

/// An issue's card: its cover with "#51" on it, "Issue 51", and "36 pages ·
/// Reading" (the accent while it is being read).
struct IssueCard: View {
    let item: ReadingSectionItem
    let kind: String
    let fallback: String
    @Environment(\.glassAccent) private var accent

    private var progress: Double {
        guard let p = item.progress else { return 0 }
        return p.completed ? 1 : p.percentage
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            BookCover(path: item.artwork.isEmpty ? fallback : item.artwork, width: 240)
                .overlay { BookProgressBar(fraction: progress) }
                .overlay(alignment: .bottomTrailing) {
                    if !item.number.isEmpty && progress == 0 {
                        Text("#" + item.number)
                            .font(HubType.chrome(10, weight: .bold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 3)
                            .background(.black.opacity(0.62), in: RoundedRectangle(cornerRadius: 6, style: .continuous))
                            .padding(6)
                    }
                }
                .overlay(alignment: .topTrailing) { if item.progress?.completed == true { ReadTick().padding(6) } }
                .litArtwork(corner: 9)
            Text(ReadingBookFacts.issueTitle(item, kind: kind))
                .font(HubType.body(13, weight: .bold, relativeTo: .footnote))
                .foregroundStyle(.white)
                .lineLimit(1)
            // "24 pages · Not started" is wider than a cover: two lines, not "Not st…".
            Text(ReadingBookFacts.issueLine(item))
                .font(HubType.body(11.5, relativeTo: .caption2))
                .foregroundStyle(progress > 0 && progress < 1 ? accent.tint : .white.opacity(0.6))
                .lineLimit(2)
                .fixedSize(horizontal: false, vertical: true)
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}
