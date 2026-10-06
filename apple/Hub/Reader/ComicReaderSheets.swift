import HubKit
import SwiftUI

/// The comic reader's sheets, in the readers' frame (`ReaderSheetFrame`):
/// Display chooses how this series reads and how every new one opens; Keys
/// lists what every key does here.
struct ComicReaderSheetView: View {
    let reader: ComicReaderModel
    let sheet: ComicReaderModel.Sheet
    let layout: ComicReaderLayout

    var body: some View {
        ReaderSheetFrame(title: sheet == .display ? "Reading options" : "Keys",
                         subtitle: sheet == .display ? reader.heading : "What the keys do while you read a comic",
                         size: layout.size, safe: layout.safe, headingId: "comic-sheet-heading", close: close) { _ in
            if sheet == .display { display } else { keys }
        }
    }

    // MARK: Display

    @ViewBuilder private var display: some View {
        let view = reader.view
        SheetLabel(text: "This series")
        SheetGroup {
            ForEach(ComicFit.allCases, id: \.self) { fit in
                SheetRow(title: fit.label, checked: view.fit == fit) { reader.setFit(fit) }
            }
        }
        SheetGroup {
            SheetRow(title: "Trim margins", detail: "Leave the paper round each page out, so the page reads larger",
                     checked: view.trim) { reader.setTrim(!view.trim) }
        }
        SheetLabel(text: "Reading direction")
        SheetGroup {
            SheetRow(title: "As the library reads",
                     detail: reader.manifest?.direction == "rtl" ? "Right to left" : "Left to right",
                     checked: view.direction == nil) { reader.setDirection(nil) }
            SheetRow(title: "Left to right", checked: view.direction == "ltr") { reader.setDirection("ltr") }
            SheetRow(title: "Right to left", checked: view.direction == "rtl") { reader.setDirection("rtl") }
        }
        SheetLabel(text: "Every series")
        SheetGroup {
            let every = ComicReaderSettings.defaultFit
            SheetRow(title: "Open every series this way", detail: "New series open as \(every.label.lowercased())",
                     checked: every == view.fit) { reader.openEverySeriesThisWay() }
        }
        if layout.tier == .narrow {
            // The narrow bar leaves these out.
            SheetLabel(text: "This issue")
            SheetGroup {
                SheetRow(title: "Previous issue", chevron: true) {
                    reader.sheet = nil
                    reader.movePublication(-1)
                }
                SheetRow(title: "Next issue", chevron: true) {
                    reader.sheet = nil
                    reader.movePublication(1)
                }
                SheetRow(title: "Keys", chevron: true) { reader.sheet = .keys }
            }
        }
    }

    // MARK: Keys

    @ViewBuilder private var keys: some View {
        SheetLabel(text: "Game controller")
        ReaderKeyLines(lines: ReaderPadMap.sheet(.comic))
        SheetNote(text: "With the controls open, A presses the control in focus and B closes them. Select always leaves.")
        SheetLabel(text: "Keyboard")
        ReaderKeyLines(lines: ReaderKeyboard.sheet(.comic))
        SheetLabel(text: "Touch")
        ReaderKeyLines(lines: touch)
    }

    /// What a finger does, the way this issue reads.
    private var touch: [ReaderKeyLine] {
        let rtl = reader.rtl
        return [
            ReaderKeyLine([rtl ? "Left third" : "Right third"], "Forward"),
            ReaderKeyLine([rtl ? "Right third" : "Left third"], "Back"),
            ReaderKeyLine(["Middle"], "Controls"),
            ReaderKeyLine(["Swipe across"], "Turn the page"),
            ReaderKeyLine(["Pinch", "Double tap"], "Zoom"),
            ReaderKeyLine(["Drag"], "Move around the page"),
        ]
    }

    /// Once the sheet has slid away: unless another opened meanwhile.
    private func close() {
        if reader.sheet == sheet { reader.sheet = nil }
    }
}
