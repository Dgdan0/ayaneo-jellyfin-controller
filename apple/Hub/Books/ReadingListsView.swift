import HubKit
import SwiftUI

/// Kavita's reading lists, or one of them (#37; Android's
/// `ServerReadingListsScreen`): kept apart from the device's own lists, which
/// the person edits. The lists go by title; a list's issues go in Kavita's
/// order, numbered, and each opens the comic reader, which goes on along the
/// list from run to run.
struct ReadingListsRoute: Hashable {
    /// Nil: every list. Else the list whose issues are shown.
    let list: ServerReadingList?
    /// The picture behind the page: Kavita's library's.
    var artwork = ""
}

struct ReadingListsView: View {
    let route: ReadingListsRoute
    @Environment(AppModel.self) private var model
    @Environment(\.glassMetrics) private var metrics
    @Environment(\.read) private var read
    @Environment(\.readerClosed) private var readerClosed
    @Environment(\.openRoute) private var openRoute

    @State private var lists: [ServerReadingList]?
    @State private var entries: [ServerReadingListEntry]?
    @State private var status = StatusMessage("")
    @State private var reloads = 0

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                PageHeading(title: route.list?.title ?? "Reading lists") {
                    if status.text.isEmpty {
                        Text(summary)
                            .font(HubType.body(14, relativeTo: .subheadline))
                            .foregroundStyle(.white.opacity(0.66))
                    } else {
                        StatusLine(message: status) { reloads += 1 }
                    }
                }
                .padding(.horizontal, metrics.margin)
                .padding(.top, 4)
                if let summary = route.list?.summary, !summary.isEmpty {
                    Text(summary)
                        .font(HubType.body(15, relativeTo: .body))
                        .foregroundStyle(.white.opacity(0.82))
                        .frame(maxWidth: 620, alignment: .leading)
                        .padding(.horizontal, metrics.margin)
                        .padding(.top, 8)
                }
                VStack(alignment: .leading, spacing: 10) {
                    if route.list == nil {
                        ForEach(lists ?? []) { list in
                            NavigationLink(value: AppRoute.readingLists(ReadingListsRoute(list: list, artwork: route.artwork))) {
                                ReadingListRow(title: list.title, detail: ServerReadingLists.listDetail(list))
                            }
                            .buttonStyle(GlassCardStyle())
                            .accessibilityIdentifier("reading-list-\(list.id)")
                            .padFocusable("\(list.id)", ring: .card, scroll: list.id) {
                                openRoute(.readingLists(ReadingListsRoute(list: list, artwork: route.artwork)))
                            }
                        }
                    } else {
                        let shown = entries ?? []
                        ForEach(Array(shown.enumerated()), id: \.element.id) { index, entry in
                            Button {
                                if let run = ReadingListRun(title: route.list?.title ?? "", entries: shown, index: index) {
                                    read(.list(run))
                                }
                            } label: {
                                ReadingListRow(title: ServerReadingLists.entryTitle(index, entry),
                                               detail: ServerReadingLists.entryDetail(entry),
                                               cover: IssueCover.path(entry.sourceItemId) ?? entry.artwork,
                                               finished: entry.progress?.completed == true)
                            }
                            .buttonStyle(GlassCardStyle())
                            .accessibilityLabel(ServerReadingLists.entryLabel(index, entry))
                            .accessibilityIdentifier("reading-list-entry-\(index)")
                            .padFocusable("\(index)", ring: .card) {
                                if let run = ReadingListRun(title: route.list?.title ?? "", entries: shown, index: index) {
                                    read(.list(run))
                                }
                            }
                        }
                    }
                }
                // A controller goes down the lists, or the list's issues, one by one (#46).
                .padGroup("rows", .column, members: route.list == nil ? (lists ?? []).map { "\($0.id)" }
                          : (entries ?? []).indices.map { "\($0)" })
                .frame(maxWidth: 720, alignment: .leading)
                .padding(.horizontal, metrics.margin)
                .padding(.top, 16)
                .padding(.bottom, 30)
            }
        }
        .padPage(route.list.map { "reading-list:\($0.id)" } ?? "reading-lists")
        .ambientArtwork(route.artwork)
        .refreshable { await load() }
        // Again when a reader closes: the issues read show how far they went.
        .task(id: "\(model.userId)·\(reloads)·\(readerClosed)") { await load() }
    }

    private var summary: String {
        route.list == nil ? lists.map { ServerReadingLists.listsStatus($0.count) } ?? ""
            : entries.map { ServerReadingLists.entriesStatus($0.count) } ?? ""
    }

    private func load() async {
        let hasData = route.list == nil ? lists != nil : entries != nil
        if !hasData { status = StatusText.loading("reading lists", refreshing: false) }
        do {
            if let list = route.list {
                let response = try await model.hub.fetch(HubEndpoints.serverReadingList(list.id), as: ServerReadingListResponse.self)
                entries = ServerReadingLists.ordered(response.items)
                model.colors.want(response.items.map(\.artwork))
            } else {
                let response = try await model.hub.fetch(HubEndpoints.serverReadingLists, as: ServerReadingListsResponse.self)
                lists = ServerReadingLists.sorted(response.lists)
            }
            status = StatusMessage("")
        } catch {
            if error.kind == .cancelled { return }
            status = StatusText.failed(error.message, kind: error.kind, hasData: hasData)
        }
    }
}

/// A list or an issue as a glass row (the prototype's `.lrow`): an issue's
/// cover, the words, and a chevron.
struct ReadingListRow: View {
    let title: String
    let detail: String
    var cover: String?
    var finished = false

    var body: some View {
        HStack(spacing: 14) {
            if let cover {
                BookCover(path: cover, width: 160)
                    .frame(width: 42)
                    .shadow(color: .black.opacity(0.4), radius: 6, y: 6)
                    .opacity(finished ? 0.6 : 1)
            } else {
                Image(systemName: "list.number")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(.white.opacity(0.8))
                    .frame(width: 42, height: 42)
                    .glassPanel(Circle())
            }
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(HubType.body(15, weight: .bold, relativeTo: .subheadline))
                    .foregroundStyle(.white)
                    .lineLimit(2)
                if !detail.isEmpty {
                    Text(detail)
                        .font(HubType.body(13, relativeTo: .caption))
                        .foregroundStyle(.white.opacity(0.66))
                        .lineLimit(1)
                }
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
