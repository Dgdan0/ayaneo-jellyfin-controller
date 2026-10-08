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
                         size: layout.size, safe: layout.safe, headingId: "comic-sheet-heading", close: close) { proxy in
            if sheet == .display {
                display
                    .onAppear { openDisplay() }
                    .onChange(of: layout.tier) { _, _ in reader.displayNarrow = layout.tier == .narrow }
                    .onChange(of: reader.displayWalk) { _, walk in
                        let lines = reader.displayLines
                        let line = lines[walk.clamped(to: lines.map(\.shape)).line]
                        withAnimation(.easeOut(duration: 0.15)) { proxy.scrollTo(Self.id(line), anchor: .center) }
                    }
            } else {
                keys
                    .onAppear { reader.keysPart = 0 }
                    .onChange(of: reader.keysPart) { _, part in
                        withAnimation(.easeOut(duration: 0.15)) {
                            proxy.scrollTo(ReaderKeysPart(rawValue: part)?.id ?? "", anchor: .top)
                        }
                    }
            }
        }
    }

    /// The ring starts on the fit this series reads with.
    private func openDisplay() {
        reader.displayNarrow = layout.tier == .narrow
        let lines = reader.displayLines
        reader.displayWalk = SheetWalk(line: lines.firstIndex(of: .fit(reader.view.fit)) ?? 0)
    }

    /// The line the controller's ring is on, when a controller is in use.
    private var ringed: ComicDisplayLine? {
        guard reader.controllerActive else { return nil }
        let lines = reader.displayLines
        return lines[reader.displayWalk.clamped(to: lines.map(\.shape)).line]
    }

    /// Where the sheet scrolls to bring `line` into view.
    static func id(_ line: ComicDisplayLine) -> String {
        if case .comfort(let comfort) = line { return ComfortControls.id(comfort) }
        return "comic-option-\(line)"
    }

    /// A row of Reading options: pressed through the model, as Ⓐ presses it.
    private func row(_ line: ComicDisplayLine, _ title: String, detail: String = "", checked: Bool = false,
                     chevron: Bool = false) -> some View {
        SheetRow(title: title, detail: detail, checked: checked, chevron: chevron) { reader.pressDisplay(line) }
            .readerRing(ringed == line)
            .id(Self.id(line))
    }

    // MARK: Display

    @ViewBuilder private var display: some View {
        let view = reader.view
        SheetLabel(text: "This series")
        SheetGroup {
            ForEach(ComicFit.allCases, id: \.self) { fit in
                row(.fit(fit), fit.label, checked: view.fit == fit)
            }
        }
        SheetGroup {
            row(.trim, "Trim margins", detail: "Leave the paper round each page out, so the page reads larger",
                checked: view.trim)
        }
        SheetLabel(text: "Reading direction")
        SheetGroup {
            row(.direction(nil), "As the library reads",
                detail: reader.manifest?.direction == "rtl" ? "Right to left" : "Left to right", checked: view.direction == nil)
            row(.direction("ltr"), "Left to right", checked: view.direction == "ltr")
            row(.direction("rtl"), "Right to left", checked: view.direction == "rtl")
        }
        SheetLabel(text: "Every series")
        SheetGroup {
            let every = ComicReaderSettings.defaultFit
            row(.everySeries, "Open every series this way", detail: "New series open as \(every.label.lowercased())",
                checked: every == view.fit)
        }
        if layout.tier == .narrow {
            // The narrow bar leaves these out.
            SheetLabel(text: "This issue")
            SheetGroup {
                row(.previousIssue, "Previous issue", chevron: true)
                row(.nextIssue, "Next issue", chevron: true)
                row(.keys, "Keys", chevron: true)
            }
        }
        // Brightness and warmth, for every reader (#37).
        ComfortControls(book: false, heading: true, ring: {
            if case .comfort(let line) = ringed { return line }
            return nil
        }())
    }

    // MARK: Keys

    @ViewBuilder private var keys: some View {
        SheetLabel(text: "Game controller").id(ReaderKeysPart.controller.id)
        ReaderKeyLines(lines: ReaderPadMap.sheet(.comic))
        SheetNote(text: "With the controls open, A presses the control in focus and B closes them. Select always leaves.")
        SheetLabel(text: "Keyboard").id(ReaderKeysPart.keyboard.id)
        ReaderKeyLines(lines: ReaderKeyboard.sheet(.comic))
        SheetLabel(text: "Touch").id(ReaderKeysPart.touch.id)
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
