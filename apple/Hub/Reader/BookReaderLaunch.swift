import HubKit
import SwiftUI

#if DEBUG
/// Debug builds, against the demo hub only: HUB_BOOK=<work id>/<edition id>
/// opens the ebook reader over the app at launch, as a book's page will
/// through the Books side's `ReaderHost` (for example
/// `rw_demo_recursion/demo-rw_demo_recursion`, a book not started, or
/// `rw_demo_rr6/rr6`, Light Bringer half read). Reading writes the place to
/// the hub, so a real hub never gets it. HUB_BOOK_CHROME=pinned opens the
/// menu; HUB_BOOK_AT and HUB_BOOK_SHEET are `BookReaderScreen`'s.
/// HUB_BOOK_READALONG=1 reads along (`rw_demo_darkmatter/demo-dm`, whose
/// read-along edition and narration the demo hub serves: `DemoReadAlong`).
struct BookReaderDebugLaunch: ViewModifier {
    struct Opened: Equatable {
        let work: ReadingWork
        let sourceItemId: String
        let readAlong: Bool
    }

    @Environment(AppModel.self) private var model
    @State private var opened: Opened?

    func body(content: Content) -> some View {
        content
            .overlay {
                if let opened {
                    BookReaderView(work: opened.work, sourceItemId: opened.sourceItemId, readAlong: opened.readAlong)
                        .environment(\.closeReader, CloseReaderAction { self.opened = nil })
                        .transition(.opacity)
                }
            }
            .animation(.easeOut(duration: 0.25), value: opened == nil)
            .task { await launch() }
    }

    private func launch() async {
        guard let target = ProcessInfo.processInfo.environment["HUB_BOOK"], !target.isEmpty else { return }
        guard model.isDemo else {
            FileHandle.standardError.write(Data("HUB_BOOK opens the reader only with -demo\n".utf8))
            return
        }
        let parts = target.split(separator: "/", maxSplits: 1).map(String.init)
        guard parts.count == 2 else { return }
        // After the shell's first layout, so the reader comes in over it.
        try? await Task.sleep(for: .milliseconds(700))
        guard let work = try? await model.hub.fetch(HubEndpoints.readingWork(parts[0]), as: ReadingWork.self) else { return }
        opened = Opened(work: work, sourceItemId: parts[1],
                        readAlong: ProcessInfo.processInfo.environment["HUB_BOOK_READALONG"] == "1")
    }
}
#endif
