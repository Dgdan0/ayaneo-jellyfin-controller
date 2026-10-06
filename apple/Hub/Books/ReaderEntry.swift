import HubKit
import SwiftUI

// Where a book page meets a reader (#25; APPLE_PLAN.md, "The readers"). A page
// never pushes a reader: it asks `\.read` with a `ReadRequest`, and the shell
// shows `ReaderHost` over the whole window and its bars, as it shows the video
// player. Phase 3's comic reader and phase 4's ebook reader go in `ReaderHost`.

/// What a book page asks to read.
enum ReadRequest: Equatable, Identifiable {
    /// A comic issue or a manga volume, in the page reader (phase 3): the work
    /// as the page loaded it, with its volumes and their issues, and the issue.
    case pages(work: ReadingWork, publication: ReadingSectionItem)
    /// An EPUB, alone or read along with its narration (phase 4).
    case ebook(work: ReadingWork, sourceItemId: String, readAlong: Bool)

    var id: String {
        switch self {
        case .pages(let work, let publication): "pages:\(work.id):\(publication.sourceItemId)"
        case .ebook(let work, let sourceItemId, let readAlong): "ebook:\(work.id):\(sourceItemId):\(readAlong)"
        }
    }

    var work: ReadingWork {
        switch self {
        case .pages(let work, _), .ebook(let work, _, _): work
        }
    }

    /// What a Read button opens: the pages of a Kavita comic or manga, else
    /// the ebook (`ReadingWorkPresentation.opensPages`).
    static func read(_ work: ReadingWork, _ read: PrimaryRead) -> ReadRequest {
        ReadingWorkPresentation.opensPages(work, source: read.source)
            ? .pages(work: work, publication: ReadingWorkPresentation.publication(work, sourceItemId: read.sourceItemId))
            : .ebook(work: work, sourceItemId: read.sourceItemId, readAlong: false)
    }
}

/// Opens a reader over the window, and closes it.
struct ReadAction {
    var open: @MainActor (ReadRequest) -> Void = { _ in }
    var close: @MainActor () -> Void = {}

    @MainActor func callAsFunction(_ request: ReadRequest) { open(request) }
}

extension EnvironmentValues {
    @Entry var read = ReadAction()
    /// Changes each time a reader closes, so the pages behind it read their
    /// progress again (as `playbackClosed` does for video).
    @Entry var readerClosed = 0
}

/// The reader on show over the whole window.
struct ReaderHost: View {
    let request: ReadRequest

    var body: some View {
        switch request {
        case .pages(let work, let publication):
            // Phase 3: ComicReaderView(work: work, publication: publication)
            ReaderComingView(work: work, artwork: publication.artwork.isEmpty ? work.artwork : publication.artwork,
                             what: work.kind == "manga" ? "Manga" : "Comics",
                             place: [ReadingBookFacts.issueTitle(publication, kind: work.kind),
                                     ReadingBookFacts.comicLine(publication.progress)].joined(separator: " · "),
                             keeper: "Kavita")
        case .ebook(let work, _, let readAlong):
            ReaderComingView(work: work, artwork: work.artwork, what: readAlong ? "Read along" : "Ebooks",
                             place: ReadingBookFacts.progress(work) ?? "Not started",
                             keeper: work.editions.contains { $0.source == "storyteller" } ? "Storyteller" : "Kavita")
        }
    }
}

/// Until a reader is built: the book over its own colours, what comes next,
/// and where the place is kept, with Close.
struct ReaderComingView: View {
    let work: ReadingWork
    let artwork: String
    /// "Comics", "Manga", "Ebooks", "Read along".
    let what: String
    /// "Issue 51 · 1% read", "49% · page 363 of 735".
    let place: String
    /// The server that keeps the place.
    let keeper: String
    @Environment(AppModel.self) private var model
    @Environment(\.read) private var read
    @Environment(\.horizontalSizeClass) private var sizeClass

    var body: some View {
        ZStack {
            AmbientBackground(path: artwork, palette: model.colors.palette(for: artwork))
                .ignoresSafeArea()
            ViewThatFits(in: .vertical) {
                card(cover: sizeClass == .compact ? 150 : 190)
                card(cover: 96)
                ScrollView { card(cover: 96) }
            }
            .padding(20)
        }
        .environment(\.glassPalette, model.colors.palette(for: artwork))
        .task { model.colors.want([artwork]) }
        .accessibilityIdentifier("reader-coming")
    }

    private func card(cover: CGFloat) -> some View {
        VStack(spacing: 14) {
            BookCover(path: artwork, square: work.kind == "audiobook", width: 480)
                .frame(width: cover)
                .shadow(color: .black.opacity(0.5), radius: 24, y: 24)
            Text(ReadingBookFacts.eyebrow(work).uppercased())
                .font(HubType.body(12, weight: .bold, relativeTo: .caption))
                .tracking(1.6)
                .foregroundStyle(.white.opacity(0.7))
            Text(work.title)
                .font(HubType.heading(28, weight: .heavy, relativeTo: .title))
                .foregroundStyle(.white)
            Text("\(what) open here once the reader is built. Your place is kept on \(keeper): \(place).")
                .font(HubType.body(15, relativeTo: .body))
                .foregroundStyle(.white.opacity(0.8))
                .frame(maxWidth: 420)
            Button("Close") { read.close() }
                .buttonStyle(PrimaryPillStyle())
                .keyboardShortcut(.cancelAction)
                .padding(.top, 6)
                .accessibilityIdentifier("reader-close")
        }
        .multilineTextAlignment(.center)
        .padding(28)
        .glassPanel(RoundedRectangle(cornerRadius: 26, style: .continuous))
        .frame(maxWidth: 520)
    }
}
