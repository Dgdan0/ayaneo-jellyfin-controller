#if os(iOS)
import HubKit
import SwiftUI

/// Kindle's corners while reading (#42): the time at the top right, where
/// you are at the bottom left (a tap shows the next way of saying it), how
/// far through the book at the bottom right. Quiet, small, in the page's own
/// ink, in the strips Readium keeps at the top and bottom of the page
/// (`PageInfo.strip`), so never on the text; the screen shows them only
/// while the bars and sheets are away. Reading along, the narration's pill
/// sits at the bottom right with the percentage beside it.
struct BookReaderCorners: View {
    let reader: BookReaderModel
    let layout: ComicReaderLayout
    @Environment(\.verticalSizeClass) private var vertical

    var body: some View {
        let strip = PageInfo.strip(compactHeight: vertical == .compact)
        // As Readium takes them: the window's safe area can make a strip deeper.
        let top = max(layout.safe.top, strip.top)
        let bottom = max(layout.safe.bottom, strip.bottom)
        let rendering = reader.rendering
        let ink = Color(argb: EpubPagePalette.argb(rendering.text) ?? 0xFF3E_3526)
        let info = reader.pageInfo
        let corners = reader.corners
        let pill = playing
        ZStack {
            if reader.preferences.scrolls {
                // Scrolling, the text passes through the strips: the page's colour keeps them clear.
                let page = Color(argb: EpubPagePalette.argb(rendering.background) ?? 0xFFEF_E2C6)
                VStack(spacing: 0) {
                    page.frame(height: info.clock ? top : 0)
                    Spacer(minLength: 0)
                    page.frame(height: corners.place != nil || (corners.percent != nil && pill == nil) ? bottom : 0)
                }
                .allowsHitTesting(false)
            }
            VStack(spacing: 0) {
                HStack {
                    Spacer(minLength: 0)
                    if info.clock {
                        TimelineView(.everyMinute) { context in
                            Text(PageInfo.clock(context.date))
                                .accessibilityLabel("Time, " + PageInfo.clock(context.date))
                                .accessibilityIdentifier("book-corner-clock")
                        }
                        .allowsHitTesting(false)
                    }
                }
                .frame(height: top)
                Spacer(minLength: 0)
                HStack(spacing: 10) {
                    if let place = corners.place {
                        Button { reader.nextPlace() } label: {
                            Text(place)
                                .frame(maxHeight: .infinity)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("book-corner-place")
                        .accessibilityHint("Shows the next way of saying where you are")
                    }
                    Spacer(minLength: 0)
                    if pill == nil, let percent = corners.percent {
                        percentLabel(percent)
                    }
                }
                .frame(height: bottom)
            }
            .padding(.horizontal, max(layout.side, 22))
            .font(.system(size: 11, weight: .medium).monospacedDigit())
            .foregroundStyle(ink.opacity(0.6))
            .lineLimit(1)
            if let pill {
                HStack(spacing: 10) {
                    if let percent = corners.percent {
                        percentLabel(percent)
                            .font(.system(size: 11, weight: .medium).monospacedDigit())
                            .foregroundStyle(ink.opacity(0.6))
                    }
                    ReadAlongPill(reading: pill.reading, narration: pill.narration) { reader.setControls(true) }
                }
                .padding(.horizontal, layout.side + 4)
                .padding(.bottom, layout.bottom + 6)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
            }
        }
    }

    /// The narration's pill shows while it plays (#19).
    private var playing: (reading: ReadAlongReader, narration: NarrationModel)? {
        guard let reading = reader.readAlong, let narration = reading.narration, narration.playing else { return nil }
        return (reading, narration)
    }

    private func percentLabel(_ percent: String) -> some View {
        Text(percent)
            .allowsHitTesting(false)
            .accessibilityLabel(percent + " of the book")
            .accessibilityIdentifier("book-corner-percent")
    }
}
#endif
