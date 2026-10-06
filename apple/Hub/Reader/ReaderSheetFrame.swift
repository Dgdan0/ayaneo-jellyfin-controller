import HubKit
import SwiftUI

/// A reader's sheet, as the player's (`PlayerSheet`): glass at the right edge
/// over the page, or from the bottom where a side sheet would cover most of
/// it. The comic reader's Display and Keys and the book's Contents,
/// Appearance and Keys are all this frame. Like the player's, it comes in
/// without a transition and then slides, so no row is laid out on SwiftUI's
/// own animation thread (two crashes on 2026-10-04).
struct ReaderSheetFrame<Content: View>: View {
    let title: String
    let subtitle: String
    /// The reader's screen, safe area and all, and its safe area.
    let size: CGSize
    let safe: EdgeInsets
    /// The heading's accessibility identifier, for the UI tests.
    var headingId = "reader-sheet-heading"
    /// A comic's page darkens under the sheet; a book's page has made room beside it instead.
    var dims = true
    /// Asked once the sheet has slid away.
    let close: () -> Void
    /// The sheet's rows, with the proxy of the scroll view they are in.
    @ViewBuilder let content: (ScrollViewProxy) -> Content
    @State private var shown = false

    static func fromBottom(_ size: CGSize) -> Bool { size.width < 600 }

    /// How wide a sheet at the right edge is: what a book's page makes room for.
    static func width(_ size: CGSize, safe: EdgeInsets) -> CGFloat {
        fromBottom(size) ? size.width : min(400 + safe.trailing, size.width - 40)
    }

    var body: some View {
        let bottom = Self.fromBottom(size)
        ZStack(alignment: bottom ? .bottom : .trailing) {
            Color.black.opacity(shown && (dims || bottom) ? 0.4 : 0)
                .contentShape(Rectangle())
                .onTapGesture { dismiss() }
                .accessibilityLabel("Close")
                .accessibilityAddTraits(.isButton)
                .accessibilityAction { dismiss() }
            panel(bottom: bottom)
                .offset(x: shown || bottom ? 0 : 60, y: shown || !bottom ? 0 : 80)
                .opacity(shown ? 1 : 0)
        }
        .task {
            await Task.yield()
            withAnimation(.easeOut(duration: 0.26)) { shown = true }
        }
    }

    private func panel(bottom: Bool) -> some View {
        let shape = UnevenRoundedRectangle(topLeadingRadius: bottom ? 32 : 0, topTrailingRadius: bottom ? 32 : 0,
                                           style: .continuous)
        return VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top, spacing: 12) {
                VStack(alignment: .leading, spacing: 5) {
                    Text(title)
                        .font(HubType.heading(23, weight: .heavy, relativeTo: .title2))
                        .accessibilityAddTraits(.isHeader)
                        .accessibilityIdentifier(headingId)
                    if !subtitle.isEmpty {
                        Text(subtitle)
                            .font(HubType.body(13, weight: .medium, relativeTo: .footnote))
                            .foregroundStyle(.white.opacity(0.65))
                            .lineLimit(2)
                    }
                }
                Spacer(minLength: 0)
                GlassRoundButton(systemImage: "xmark", label: "Close", size: 38) { dismiss() }
            }
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) { content(proxy) }
                        .padding(.bottom, 4)
                }
                .scrollIndicators(.hidden)
                .scrollBounceBehavior(.basedOnSize)
            }
        }
        .padding(.top, 22 + (bottom ? 0 : safe.top))
        .padding(.leading, 20 + (bottom ? safe.leading : 0))
        .padding(.trailing, 20 + safe.trailing)
        .padding(.bottom, (bottom ? 22 : 30) + safe.bottom)
        .frame(width: Self.width(size, safe: safe), alignment: .leading)
        .frame(height: bottom ? size.height * 0.72 : size.height, alignment: .top)
        .background { GlassSheetFill().clipShape(shape) }
        .overlay(alignment: .leading) {
            if !bottom { Rectangle().fill(Color(argb: GlassColors.edge)).frame(width: 1) }
        }
        .contentShape(shape)
        .frame(maxHeight: .infinity, alignment: bottom ? .bottom : .top)
        .accessibilityElement(children: .contain)
        .accessibilityAddTraits(.isModal)
    }

    private func dismiss() {
        withAnimation(.easeIn(duration: 0.2)) { shown = false }
        Task {
            try? await Task.sleep(for: .milliseconds(210))
            close()
        }
    }
}

/// Every key and what it does, as caps beside words: a reader's Keys sheet.
struct ReaderKeyLines: View {
    let lines: [ReaderKeyLine]

    var body: some View {
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
}
