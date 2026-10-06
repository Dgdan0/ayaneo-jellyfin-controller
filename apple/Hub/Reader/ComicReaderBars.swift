import HubKit
import SwiftUI

/// The reader's sizes: the prototype's bars, inset from the screen's edges
/// with their own corners (Android's `ReaderBars`: 8 in, 16 round), and which
/// controls fit the width.
struct ComicReaderLayout {
    enum Tier { case wide, medium, narrow }

    let size: CGSize
    let safe: EdgeInsets

    var phone: Bool { min(size.width, size.height) < 500 }
    var side: CGFloat { max(8, max(safe.leading, safe.trailing) + 4) }
    var top: CGFloat {
        #if os(macOS)
        // Below the window buttons, which sit over the hidden title bar.
        safe.top + 8
        #else
        max(8, safe.top + 2)
        #endif
    }
    var bottom: CGFloat { max(8, safe.bottom) }
    var round: CGFloat { phone ? 40 : 44 }
    var corner: CGFloat { 16 }

    /// Everything as pills and icons from 900 points; the issues and the
    /// keys as icons from 600; below that the closest few, the rest in the
    /// Display sheet.
    var tier: Tier { size.width >= 900 ? .wide : size.width >= 600 ? .medium : .narrow }

    /// The controls the bars show, in the order the pad moves through them.
    var controls: [ComicControl] {
        switch tier {
        case .wide: [.close, .previousIssue, .thirds, .zoomOut, .zoomIn, .display, .keys, .nextIssue,
                     .previousPage, .pages, .nextPage]
        case .medium: [.close, .previousIssue, .thirds, .display, .keys, .nextIssue, .previousPage, .pages, .nextPage]
        case .narrow: [.close, .thirds, .display, .previousPage, .pages, .nextPage]
        }
    }

    /// The scrub preview's width.
    var previewWidth: CGFloat { phone ? 112 : 136 }
}

/// The controls over the page (#16, X7): a bar of the cover's glass along the
/// top (Close, the run over the issue and page, the issues either side,
/// Thirds lit while on, zoom, Display and Keys) and one along the foot
/// (the pages either side, the scrubber, where you are, Pages). With a game
/// controller connected the keys' hints run under the foot, each a button
/// too, and a ring shows the control the pad is on.
struct ComicReaderBars: View {
    let reader: ComicReaderModel
    let layout: ComicReaderLayout
    let leave: () -> Void
    /// The page under the scrubber's thumb while it is dragged, and where its track is.
    @Binding var scrubbing: Int?
    @Binding var scrubTrack: CGRect

    var body: some View {
        VStack(spacing: 0) {
            topBar
                .padding(.horizontal, layout.side)
                .padding(.top, layout.top)
            Spacer(minLength: 0)
            VStack(spacing: 6) {
                bottomBar
                if reader.controllerActive {
                    hintRow
                }
            }
            .padding(.horizontal, layout.side)
            .padding(.bottom, layout.bottom)
        }
    }

    private var topBar: some View {
        HStack(spacing: 6) {
            control(.close)
            VStack(alignment: .leading, spacing: 1) {
                Text(reader.heading)
                    .font(HubType.body(layout.phone ? 15 : 16, weight: .bold, relativeTo: .headline))
                    .lineLimit(1)
                Text(reader.subtitle)
                    .font(HubType.body(layout.phone ? 11.5 : 12.5, relativeTo: .caption))
                    .foregroundStyle(.white.opacity(0.72))
                    .lineLimit(1)
                    .accessibilityIdentifier("comic-subtitle")
            }
            .padding(.horizontal, 6)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
            ForEach(layout.controls.filter { ![.close, .previousPage, .pages, .nextPage].contains($0) }, id: \.self) { item in
                control(item)
            }
        }
        .padding(6)
        .glassPanel(RoundedRectangle(cornerRadius: layout.corner, style: .continuous))
    }

    private var bottomBar: some View {
        HStack(spacing: 8) {
            control(.previousPage)
            ComicScrubber(page: reader.currentPage, count: reader.pageCount, scrubbing: $scrubbing,
                          track: { scrubTrack = $0 }) { page in
                reader.touchedControls()
                reader.jump(to: page)
            }
            if layout.tier != .narrow {
                Text(reader.position)
                    .font(HubType.body(12.5, relativeTo: .caption))
                    .monospacedDigit()
                    .foregroundStyle(.white.opacity(0.78))
                    .lineLimit(1)
                    .fixedSize()
                    .accessibilityHidden(true)
            }
            control(.pages)
            control(.nextPage)
        }
        .padding(6)
        .glassPanel(RoundedRectangle(cornerRadius: layout.corner, style: .continuous))
    }

    /// What the controller's main keys do now (`ReaderPadMap.hints`); a tap
    /// on a chip does it, as the key would.
    private var hintRow: some View {
        var state = reader.padState
        state.controlsVisible = true
        return HStack(spacing: 14) {
            ForEach(ReaderPadMap.hints(state), id: \.glyph) { hint in
                Button { reader.pad(hint.action) } label: {
                    HStack(spacing: 5) {
                        Text(hint.glyph).font(HubType.chrome(13, weight: .bold))
                        Text(hint.label).font(HubType.chrome(12, weight: .semibold))
                    }
                    .lineLimit(1)
                }
                .buttonStyle(.plain)
                .foregroundStyle(.white.opacity(0.85))
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 7)
        .glassPanel(Capsule())
    }

    /// A control as the bar shows it: a pill on a wide bar where it has a
    /// word, a round icon otherwise; with a controller, a ring while the pad is on it.
    @ViewBuilder private func control(_ item: ComicControl) -> some View {
        let focused = reader.controllerActive && reader.controlsVisible && reader.focusedControl == item
        Group {
            switch item {
            case .thirds where layout.tier == .wide:
                ChoicePill(title: "Thirds", selected: reader.view.fit == .thirds, systemImage: item.systemImage) {
                    reader.perform(item)
                }
                .accessibilityLabel(item.label)
            case .display where layout.tier == .wide:
                ChoicePill(title: "Display", selected: false, systemImage: item.systemImage) { reader.perform(item) }
                    .accessibilityLabel(item.label)
            case .close:
                GlassRoundButton(systemImage: item.systemImage, label: item.label, size: layout.round) { leave() }
            default:
                GlassRoundButton(systemImage: item.systemImage, label: item.label,
                                 on: item == .thirds && reader.view.fit == .thirds, size: layout.round) {
                    reader.perform(item)
                }
                .disabled(!enabled(item))
                .opacity(enabled(item) ? 1 : 0.45)
            }
        }
        .overlay {
            if focused {
                Capsule().strokeBorder(.white, lineWidth: 2.5).padding(-4).allowsHitTesting(false)
            }
        }
    }

    private func enabled(_ item: ComicControl) -> Bool {
        switch item {
        case .previousIssue: !(reader.manifest?.previousSourceItemId.isEmpty ?? true)
        case .nextIssue: !(reader.manifest?.nextSourceItemId.isEmpty ?? true)
        default: reader.manifest != nil
        }
    }
}

/// Where in the issue (`.pl-line`'s look: a white line on a faint track and a
/// white thumb with a soft ring): dragging shows the page under the thumb,
/// with its thumbnail over it, and letting go opens it.
struct ComicScrubber: View {
    let page: Int
    let count: Int
    @Binding var scrubbing: Int?
    /// Where its track is in the reader, for the preview over it.
    var track: (CGRect) -> Void = { _ in }
    let open: (Int) -> Void

    var body: some View {
        GeometryReader { geometry in
            let width = max(1, geometry.size.width)
            let shown = scrubbing ?? page
            let share = count > 1 ? Double(shown) / Double(count - 1) : 1
            ZStack(alignment: .leading) {
                Capsule().fill(.white.opacity(0.25)).frame(height: 5)
                Capsule().fill(.white).frame(width: max(5, width * share), height: 5)
                Circle()
                    .fill(.white)
                    .frame(width: 16, height: 16)
                    .background { Circle().fill(.white.opacity(0.25)).padding(-4) }
                    .offset(x: width * share - 8)
            }
            .frame(maxHeight: .infinity)
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0)
                .onChanged { value in
                    guard count > 0 else { return }
                    let target = Self.page(at: value.location.x, width: width, count: count)
                    if scrubbing != target { scrubbing = target }
                }
                .onEnded { value in
                    guard count > 0 else { return }
                    scrubbing = nil
                    open(Self.page(at: value.location.x, width: width, count: count))
                })
            .onGeometryChange(for: CGRect.self) { proxy in
                proxy.frame(in: .named(ComicReaderScreen.space))
            } action: { frame in
                track(frame)
            }
        }
        .frame(height: 32)
        .frame(minWidth: 60)
        .accessibilityElement()
        .accessibilityLabel("Page")
        .accessibilityValue(count > 0 ? "\(page + 1) of \(count)" : "")
        .accessibilityIdentifier("comic-scrubber")
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: open(min(count - 1, page + 1))
            case .decrement: open(max(0, page - 1))
            @unknown default: break
            }
        }
    }

    static func page(at x: CGFloat, width: CGFloat, count: Int) -> Int {
        guard count > 1 else { return 0 }
        return Int((min(max(x / width, 0), 1) * Double(count - 1)).rounded())
    }
}
