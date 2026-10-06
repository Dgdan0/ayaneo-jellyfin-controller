import HubKit
import SwiftUI

/// The reader's sheets, as the player's (`PlayerSheet`): glass at the right
/// edge over a dimmed page, or from the bottom where a side sheet would cover
/// most of it. Display chooses how this series reads and how every new one
/// opens; Keys lists what every key does here. Like the player's, it comes in
/// without a transition and then slides, so no row is laid out on SwiftUI's
/// own animation thread (two crashes on 2026-10-04).
struct ComicReaderSheetView: View {
    let reader: ComicReaderModel
    let sheet: ComicReaderModel.Sheet
    let layout: ComicReaderLayout
    @State private var shown = false
    @Environment(\.glassAccent) private var accent

    private var bottom: Bool { layout.size.width < 600 }
    private var width: CGFloat { bottom ? layout.size.width : min(400 + layout.safe.trailing, layout.size.width - 40) }

    var body: some View {
        ZStack(alignment: bottom ? .bottom : .trailing) {
            Color.black.opacity(shown ? 0.4 : 0)
                .contentShape(Rectangle())
                .onTapGesture { close() }
                .accessibilityLabel("Close")
                .accessibilityAddTraits(.isButton)
                .accessibilityAction { close() }
            panel
                .offset(x: shown || bottom ? 0 : 60, y: shown || !bottom ? 0 : 80)
                .opacity(shown ? 1 : 0)
        }
        .task {
            await Task.yield()
            withAnimation(.easeOut(duration: 0.26)) { shown = true }
        }
    }

    private var panel: some View {
        let shape = UnevenRoundedRectangle(topLeadingRadius: bottom ? 32 : 0, topTrailingRadius: bottom ? 32 : 0,
                                           style: .continuous)
        return VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top, spacing: 12) {
                VStack(alignment: .leading, spacing: 5) {
                    Text(sheet == .display ? "Reading options" : "Keys")
                        .font(HubType.heading(23, weight: .heavy, relativeTo: .title2))
                        .accessibilityAddTraits(.isHeader)
                        .accessibilityIdentifier("comic-sheet-heading")
                    Text(sheet == .display ? reader.heading : "What the keys do while you read a comic")
                        .font(HubType.body(13, weight: .medium, relativeTo: .footnote))
                        .foregroundStyle(.white.opacity(0.65))
                }
                Spacer(minLength: 0)
                GlassRoundButton(systemImage: "xmark", label: "Close", size: 38) { close() }
            }
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    if sheet == .display { display } else { keys }
                }
                .padding(.bottom, 4)
            }
            .scrollIndicators(.hidden)
            .scrollBounceBehavior(.basedOnSize)
        }
        .padding(.top, 22 + (bottom ? 0 : layout.safe.top))
        .padding(.leading, 20 + (bottom ? layout.safe.leading : 0))
        .padding(.trailing, 20 + layout.safe.trailing)
        .padding(.bottom, (bottom ? 22 : 30) + layout.safe.bottom)
        .frame(width: width, alignment: .leading)
        .frame(height: bottom ? layout.size.height * 0.72 : layout.size.height, alignment: .top)
        .background { GlassSheetFill().clipShape(shape) }
        .overlay(alignment: .leading) {
            if !bottom { Rectangle().fill(Color(argb: GlassColors.edge)).frame(width: 1) }
        }
        .contentShape(shape)
        .frame(maxHeight: .infinity, alignment: bottom ? .bottom : .top)
        .accessibilityElement(children: .contain)
        .accessibilityAddTraits(.isModal)
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
                    close()
                    reader.movePublication(-1)
                }
                SheetRow(title: "Next issue", chevron: true) {
                    close()
                    reader.movePublication(1)
                }
                SheetRow(title: "Keys", chevron: true) { reader.sheet = .keys }
            }
        }
    }

    // MARK: Keys

    @ViewBuilder private var keys: some View {
        SheetLabel(text: "Game controller")
        lines(ReaderPadMap.sheet(.comic))
        SheetNote(text: "With the controls open, A presses the control in focus and B closes them. Select always leaves.")
        SheetLabel(text: "Keyboard")
        lines(ReaderKeyboard.sheet(.comic))
        SheetLabel(text: "Touch")
        lines(touch)
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

    private func lines(_ lines: [ReaderKeyLine]) -> some View {
        SheetGroup {
            ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                HStack(spacing: 12) {
                    HStack(spacing: 4) {
                        ForEach(line.keys, id: \.self) { key in
                            Text(key)
                                .font(HubType.chrome(12.5, weight: .bold))
                                .padding(.horizontal, 8)
                                .padding(.vertical, 4)
                                .background(Capsule().fill(.white.opacity(0.14)))
                        }
                    }
                    Spacer(minLength: 8)
                    Text(line.does)
                        .font(HubType.body(14, relativeTo: .subheadline))
                        .foregroundStyle(.white.opacity(0.85))
                        .multilineTextAlignment(.trailing)
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 11)
                .sheetDivider()
                .accessibilityElement(children: .combine)
            }
        }
    }

    private func close() {
        withAnimation(.easeIn(duration: 0.2)) { shown = false }
        let closing = sheet
        Task {
            try? await Task.sleep(for: .milliseconds(210))
            if reader.sheet == closing { reader.sheet = nil }
        }
    }
}
