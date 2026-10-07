import HubKit
import SwiftUI

// Where a book page meets a reader (#25; APPLE_PLAN.md, "The readers"). A page
// never pushes a reader: it asks `\.read` with a `ReadRequest`, and the shell
// shows `ReaderHost` over the whole window and its bars, as it shows the video
// player: phase 3's comic reader, or phase 4's ebook reader.

/// What a book page asks to read.
enum ReadRequest: Equatable, Identifiable {
    /// A comic issue or a manga volume, in the page reader (phase 3): the work
    /// as the page loaded it, with its volumes and their issues, and the issue.
    case pages(work: ReadingWork, publication: ReadingSectionItem)
    /// An EPUB, alone or read along with its narration (phase 4).
    case ebook(work: ReadingWork, sourceItemId: String, readAlong: Bool)
    /// Kavita's reading list at one of its issues (#37), in the page reader,
    /// which goes along the list from run to run.
    case list(ReadingListRun)

    var id: String {
        switch self {
        case .pages(let work, let publication): "pages:\(work.id):\(publication.sourceItemId)"
        case .ebook(let work, let sourceItemId, let readAlong): "ebook:\(work.id):\(sourceItemId):\(readAlong)"
        case .list(let run): "list:\(run.title):\(run.current.id)"
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
    @Environment(\.read) private var read

    var body: some View {
        switch request {
        case .pages(let work, let publication):
            // Phase 3: the comic and manga reader, which leaves through `closeReader`.
            ComicReaderView(work: work, publication: publication)
                .environment(\.closeReader, CloseReaderAction { read.close() })
        case .list(let run):
            ComicReaderView(list: run)
                .environment(\.closeReader, CloseReaderAction { read.close() })
        case .ebook(let work, let sourceItemId, let readAlong):
            // Phase 4: the ebook reader, Readium on the iPad and the iPhone; the
            // Mac says it reads them later. Its place goes through the reading
            // outbox (`CheckpointBookPlaces`), so another device's is never overwritten.
            BookReaderView(work: work, sourceItemId: sourceItemId, readAlong: readAlong)
                .environment(\.closeReader, CloseReaderAction { read.close() })
        }
    }
}
