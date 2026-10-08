#if os(iOS)
import HubKit
import SwiftUI
import UIKit

/// Kindle's corners while reading (#42, #47): the book's title at the top
/// centre in small capitals, the time at the top right, where you are at the
/// bottom left (a tap shows the next way of saying it), how far through the
/// book at the bottom right. About 13 points, regular, in the page's own full
/// ink, lined up with the text's edges, on Kindle's baselines, in the strips
/// Readium keeps at the top and bottom of the page (`PageInfo.strip`), so
/// never on the text; the screen shows them only while the bars and sheets are
/// away. Reading along, they are the same while the narration plays (#49): a
/// tap on the page brings its dock.
struct BookReaderCorners: View {
    let reader: BookReaderModel
    let layout: ComicReaderLayout
    @Environment(\.verticalSizeClass) private var vertical

    /// Room kept at each side of the title for the clock, so it never reaches it.
    private static let clockRoom: CGFloat = 84
    /// How far a corner's line reaches above and below its baseline, so a line can be set by its baseline:
    /// the alignment guide for the baseline answered the line's bottom here.
    private static let ascent = UIFont.systemFont(ofSize: PageInfo.cornerSize).ascender
    private static let descent = -UIFont.systemFont(ofSize: PageInfo.cornerSize).descender
    /// The room round the bottom left's words, which is its tap.
    private static let tapRoom: CGFloat = 14

    var body: some View {
        let tablet = BookNavigator.isTablet
        let strip = PageInfo.strip(compactHeight: vertical == .compact && !BookNavigator.forcedTablet, tablet: tablet)
        // As Readium takes them: the window's safe area can make a strip deeper.
        let top = max(layout.safe.top, strip.top)
        let bottom = max(layout.safe.bottom, strip.bottom)
        let baselines = PageInfo.baselines(tablet: tablet, strip: (top, bottom))
        // The text's edge: Kindle's outer margin, on a phone or a tablet.
        let margin = CGFloat(reader.outerMargin(width: layout.size.width))
        let rendering = reader.rendering
        let ink = Color(argb: EpubPagePalette.argb(rendering.text) ?? 0xFF5A_4931)
        let info = reader.pageInfo
        let corners = reader.corners
        let title = PageInfo.title(reader.title)
        ZStack {
            if reader.preferences.scrolls {
                // Scrolling, the text passes through the strips: the page's colour keeps them clear.
                let page = Color(argb: EpubPagePalette.argb(rendering.background) ?? 0xFFFC_F0D9)
                VStack(spacing: 0) {
                    page.frame(height: info.clock || !title.isEmpty ? top : 0)
                    Spacer(minLength: 0)
                    page.frame(height: corners.place != nil || corners.percent != nil ? bottom : 0)
                }
                .allowsHitTesting(false)
            }
            VStack(spacing: 0) {
                // The top line: its baseline is `baselines.top` down the screen.
                Color.clear
                    .frame(height: CGFloat(baselines.top))
                    .overlay(alignment: .bottom) {
                        if !title.isEmpty {
                            Text(title.lowercased())
                                .font(.system(size: PageInfo.cornerSize).smallCaps())
                                .tracking(0.9)
                                .lineLimit(1)
                                .truncationMode(.tail)
                                .frame(maxWidth: max(0, layout.size.width - 2 * (margin + (info.clock ? Self.clockRoom : 0))))
                                .fixedSize(horizontal: false, vertical: true)
                                // Its bottom is at the strip's: lowered by what hangs below the baseline.
                                .offset(y: Self.descent)
                                .allowsHitTesting(false)
                                .accessibilityLabel("Reading " + title)
                                .accessibilityIdentifier("book-corner-title")
                        }
                    }
                    .overlay(alignment: .bottomTrailing) {
                        if info.clock {
                            TimelineView(.everyMinute) { context in
                                Text(PageInfo.clock(context.date))
                                    .accessibilityLabel("Time, " + PageInfo.clock(context.date))
                                    .accessibilityIdentifier("book-corner-clock")
                            }
                            .offset(y: Self.descent)
                            .allowsHitTesting(false)
                        }
                    }
                Spacer(minLength: 0)
                // The bottom line: its baseline is `baselines.bottom` up the screen.
                Color.clear
                    .frame(height: CGFloat(baselines.bottom))
                    .overlay(alignment: .topLeading) {
                        if let place = corners.place {
                            Button { reader.nextPlace() } label: {
                                Text(place)
                                    .padding(.vertical, Self.tapRoom)
                                    .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            // The baseline is at the frame's top: raised by the tap's room and the line's ascent.
                            .offset(y: -(Self.tapRoom + Self.ascent))
                            .accessibilityIdentifier("book-corner-place")
                            .accessibilityHint("Shows the next way of saying where you are")
                        }
                    }
                    .overlay(alignment: .topTrailing) {
                        if let percent = corners.percent {
                            percentLabel(percent)
                                .offset(y: -Self.ascent)
                        }
                    }
            }
            .padding(.horizontal, margin)
            .font(.system(size: PageInfo.cornerSize).monospacedDigit())
            .foregroundStyle(ink)
            .lineLimit(1)
        }
    }

    private func percentLabel(_ percent: String) -> some View {
        Text(percent)
            .allowsHitTesting(false)
            .accessibilityLabel(percent + " of the book")
            .accessibilityIdentifier("book-corner-percent")
    }
}
#endif
