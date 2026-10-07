#if os(iOS)
import HubKit
import SwiftUI

/// The book's menu (#16, X7, the owner's way for books): a bar of the
/// cover's glass along the top (Close, the title over the time left,
/// Contents, Bookmark, Appearance and Keys) and the lower bar (the pages
/// either side, where you are, Return to previous place when there is one,
/// and the book's slider), the page shrunk between them. With a game
/// controller connected the keys' hints run under the lower bar, and a ring
/// shows the control the pad is on. Reading along, the narration's dock
/// (`ReadAlongDock`) stands in the lower bar's place, as on the Pocket (#21).
struct BookReaderBars: View {
    let reader: BookReaderModel
    let layout: ComicReaderLayout
    let leave: () -> Void
    /// Where the bars are, for the page to make room for them.
    @Binding var topBar: CGRect
    @Binding var bottomBar: CGRect

    var body: some View {
        VStack(spacing: 0) {
            top
                .padding(.horizontal, layout.side)
                .padding(.top, layout.top)
                .onGeometryChange(for: CGRect.self) { proxy in
                    proxy.frame(in: .named(BookReaderScreen.space))
                } action: { frame in
                    topBar = frame
                }
            Spacer(minLength: 0)
            VStack(spacing: 6) {
                // Reading along, the narration's dock is the lower bar (#21).
                if let reading = reader.readAlong, let narration = reading.narration {
                    ReadAlongDock(narration: narration, layout: layout, follow: reading.followLabel,
                                  focused: reader.controllerActive ? reader.focusedControl.dock : nil,
                                  onPlay: { reading.togglePlay() }, onFollow: { reading.follow() })
                } else {
                    BookLowerBar(reader: reader, layout: layout)
                }
                if reader.controllerActive {
                    ReaderHintRow(hints: hints) { reader.pad($0) }
                }
            }
            .padding(.horizontal, layout.side)
            .padding(.bottom, layout.bottom)
            .onGeometryChange(for: CGRect.self) { proxy in
                proxy.frame(in: .named(BookReaderScreen.space))
            } action: { frame in
                bottomBar = frame
            }
        }
    }

    /// What the main keys do with the menu open.
    private var hints: [ReaderHint] {
        var state = reader.padState
        state.controlsVisible = true
        return ReaderPadMap.hints(state)
    }

    private var top: some View {
        HStack(spacing: 6) {
            BookControlButton(reader: reader, control: .close, size: layout.round, action: leave)
            VStack(alignment: .leading, spacing: 1) {
                Text(reader.title)
                    .font(HubType.body(layout.phone ? 15 : 16, weight: .bold, relativeTo: .headline))
                    .lineLimit(1)
                if !reader.timeLeft.isEmpty {
                    Text(reader.timeLeft)
                        .font(HubType.body(layout.phone ? 11.5 : 12.5, relativeTo: .caption))
                        .foregroundStyle(.white.opacity(0.72))
                        .lineLimit(1)
                }
            }
            .padding(.horizontal, 6)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("book-heading")
            ForEach([BookControl.contents, .bookmark, .appearance, .keys], id: \.self) { control in
                BookControlButton(reader: reader, control: control, size: layout.round) { reader.choose(control) }
            }
        }
        .padding(6)
        .glassPanel(RoundedRectangle(cornerRadius: layout.corner, style: .continuous))
    }
}

/// The menu's lower bar: the pages either side of where you are, Return to
/// previous place when a link or a jump left one, and the slider.
struct BookLowerBar: View {
    let reader: BookReaderModel
    let layout: ComicReaderLayout

    var body: some View {
        VStack(spacing: 2) {
            HStack(spacing: 8) {
                BookControlButton(reader: reader, control: .previous, size: layout.round) { reader.choose(.previous) }
                Text(reader.browsing.map(BookSections.browseLine) ?? reader.positionLine)
                    .font(HubType.body(12.5, relativeTo: .caption))
                    .monospacedDigit()
                    .foregroundStyle(.white.opacity(0.8))
                    .lineLimit(2)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity)
                    .accessibilityIdentifier("book-position")
                if reader.returnPlace != nil {
                    if layout.phone {
                        BookControlButton(reader: reader, control: .returnPlace, size: layout.round) {
                            reader.returnToPrevious()
                        }
                    } else {
                        ChoicePill(title: "Return to previous place", selected: false,
                                   systemImage: BookControl.returnPlace.systemImage) { reader.returnToPrevious() }
                            .overlay { BookFocusRing(shown: reader.controllerActive && reader.focusedControl == .returnPlace) }
                            .accessibilityIdentifier("book-return")
                    }
                }
                BookControlButton(reader: reader, control: .next, size: layout.round) { reader.choose(.next) }
            }
            BookSlider(progress: reader.bookProgress,
                       browsing: Binding(get: { reader.browsing }, set: { reader.browsing = $0 }),
                       focused: reader.controllerActive && reader.focusedControl == .slider) { reader.seek($0) }
                .padding(.horizontal, 10)
        }
        .padding(6)
        .glassPanel(RoundedRectangle(cornerRadius: layout.corner, style: .continuous))
    }
}

/// A round control of the menu, with the pad's ring while it is on it.
struct BookControlButton: View {
    let reader: BookReaderModel
    let control: BookControl
    let size: CGFloat
    let action: () -> Void

    var body: some View {
        let marked = control == .bookmark && reader.bookmarked
        GlassRoundButton(systemImage: marked ? "bookmark.fill" : control.systemImage,
                         label: control == .bookmark ? (marked ? "Remove bookmark" : "Add bookmark") : control.label,
                         on: marked, size: size, action: action)
            .overlay { BookFocusRing(shown: reader.controllerActive && reader.controlsVisible && reader.focusedControl == control) }
            .accessibilityIdentifier("book-" + String(describing: control))
    }
}

/// The ring round the control a controller is on.
struct BookFocusRing: View {
    let shown: Bool

    var body: some View {
        if shown {
            Capsule().strokeBorder(.white, lineWidth: 2.5).padding(-4).allowsHitTesting(false)
        }
    }
}

/// Where in the book (`.pl-line`'s look: a white line on a faint track and a
/// white thumb with a soft ring): dragged, the line under it says where it
/// would go; let go, it goes.
struct BookSlider: View {
    let progress: Double
    @Binding var browsing: Double?
    let focused: Bool
    let seek: (Double) -> Void

    var body: some View {
        GeometryReader { geometry in
            let width = max(1, geometry.size.width)
            let shown = min(max(browsing ?? progress, 0), 1)
            ZStack(alignment: .leading) {
                Capsule().fill(.white.opacity(0.25)).frame(height: 5)
                Capsule().fill(.white).frame(width: max(5, width * shown), height: 5)
                Circle()
                    .fill(.white)
                    .frame(width: 16, height: 16)
                    .background { Circle().fill(.white.opacity(0.25)).padding(-4) }
                    .offset(x: width * shown - 8)
            }
            .frame(maxHeight: .infinity)
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0)
                .onChanged { value in browsing = min(max(value.location.x / width, 0), 1) }
                .onEnded { value in
                    let target = min(max(value.location.x / width, 0), 1)
                    browsing = nil
                    seek(target)
                })
        }
        .frame(height: 30)
        .overlay {
            if focused {
                Capsule().strokeBorder(.white, lineWidth: 2).padding(-3).allowsHitTesting(false)
            }
        }
        .accessibilityElement()
        .accessibilityLabel("Where in the book")
        .accessibilityValue(Fmt.readingPercentLabel(progress))
        .accessibilityIdentifier("book-slider")
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: seek(min(1, progress + 0.05))
            case .decrement: seek(max(0, progress - 0.05))
            @unknown default: break
            }
        }
    }
}
/// Reading along with the menu away (#16, A5): the narration plays, and
/// where the page stands with it, "Following · 1×". A tap brings the menu
/// and its dock back.
struct ReadAlongPill: View {
    let reading: ReadAlongReader
    let narration: NarrationModel
    let open: () -> Void

    var body: some View {
        Button(action: open) {
            Label("\(reading.followLabel) · \(PlayerLabels.rate(narration.speed))", systemImage: "play.fill")
                .font(HubType.body(13, weight: .bold, relativeTo: .footnote))
                .foregroundStyle(.white)
                .padding(.horizontal, 14)
                .padding(.vertical, 9)
                .glassPanel(Capsule())
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("readalong-pill")
    }
}
#endif
