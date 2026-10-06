import HubKit
import SwiftUI

#if DEBUG
/// Debug builds, against the demo hub only: HUB_READ=<work id>/<issue id>
/// opens the comic reader over the app at launch, as a run's page will
/// through the Books side's `ReaderHost` (for example
/// `rw_demo_ff/rw_demo_ff-51`, Fantastic Four at issue 51, or
/// `rw_demo_csm/rw_demo_csm-1` for manga). Reading writes the place to the
/// hub, so a real hub never gets it. HUB_READ_CHROME=pinned keeps the
/// controls up; HUB_READ_PAGE and HUB_READ_SHEET are `ComicReaderScreen`'s.
struct ComicReaderDebugLaunch: ViewModifier {
    struct Opened {
        let work: ReadingWork
        let issue: ReadingSectionItem
    }

    @Environment(AppModel.self) private var model
    @State private var opened: Opened?

    func body(content: Content) -> some View {
        content
            .overlay {
                if let opened {
                    ComicReaderView(work: opened.work, publication: opened.issue)
                        .environment(\.closeReader, CloseReaderAction { self.opened = nil })
                        .transition(.opacity)
                }
            }
            .animation(.easeOut(duration: 0.25), value: opened == nil)
            .task { await launch() }
    }

    private func launch() async {
        guard let target = ProcessInfo.processInfo.environment["HUB_READ"], !target.isEmpty else { return }
        guard model.isDemo else {
            FileHandle.standardError.write(Data("HUB_READ opens the reader only with -demo\n".utf8))
            return
        }
        let parts = target.split(separator: "/", maxSplits: 1).map(String.init)
        guard parts.count == 2 else { return }
        // After the shell's first layout, so the reader comes in over it.
        try? await Task.sleep(for: .milliseconds(700))
        guard let work = try? await model.hub.fetch(HubEndpoints.readingWork(parts[0]), as: ReadingWork.self) else { return }
        opened = Opened(work: work, issue: ReadingWorkPresentation.publication(work, sourceItemId: parts[1]))
    }
}
#endif
