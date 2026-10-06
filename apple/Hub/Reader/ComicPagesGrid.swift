import HubKit
import SwiftUI

/// The Pages grid (#16, C4): every page of the issue as the hub's thumbnail,
/// the one you are on outlined, over the whole reader. A tap opens a page at
/// its top; with a controller the cursor moves by `PageGrid` (Ⓐ opens, Ⓑ
/// closes, L2 and R2 a screenful of rows), and a keyboard's arrows and Return
/// do the same.
struct ComicPagesGrid: View {
    let reader: ComicReaderModel
    let layout: ComicReaderLayout
    @Environment(\.glassAccent) private var accent

    private var columns: Int { PageGrid.columns(width: layout.size.width - layout.side * 2) }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 12) {
                GlassRoundButton(systemImage: "xmark", label: "Close pages", size: layout.round) { reader.closeGrid() }
                VStack(alignment: .leading, spacing: 2) {
                    Text("Pages")
                        .font(HubType.heading(22, weight: .heavy, relativeTo: .title2))
                        .accessibilityAddTraits(.isHeader)
                    Text(reader.subtitle)
                        .font(HubType.body(13, relativeTo: .footnote))
                        .foregroundStyle(.white.opacity(0.7))
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, layout.side + 8)
            .padding(.top, layout.top + 6)
            ScrollViewReader { scroller in
                ScrollView {
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 12), count: columns), spacing: 16) {
                        ForEach(0..<reader.pageCount, id: \.self) { page in
                            cell(page)
                                .id(page)
                        }
                    }
                    .padding(.horizontal, layout.side + 8)
                    .padding(.bottom, layout.bottom + 16)
                }
                .scrollIndicators(.hidden)
                .onAppear { scroller.scrollTo(reader.currentPage, anchor: .center) }
                .onChange(of: reader.gridCursor) { _, page in
                    withAnimation(.easeOut(duration: 0.2)) { scroller.scrollTo(page) }
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background { GlassSheetFill().ignoresSafeArea() }
        .onAppear { reader.gridColumns = columns }
        .onChange(of: columns) { _, count in reader.gridColumns = count }
        .onGeometryChange(for: Int.self) { proxy in
            // A screenful of rows, for L2 and R2.
            max(1, Int(proxy.size.height / 220))
        } action: { rows in
            reader.gridRows = rows
        }
        .accessibilityElement(children: .contain)
        .accessibilityAddTraits(.isModal)
    }

    private func cell(_ page: Int) -> some View {
        let current = page == reader.currentPage
        let cursor = reader.controllerActive && page == reader.gridCursor
        return Button { reader.jump(to: page) } label: {
            VStack(spacing: 6) {
                ComicThumb(path: HubEndpoints.readingPublicationThumb(workId: reader.workId,
                                                                     sourceItemId: reader.manifest?.sourceItemId ?? "",
                                                                     page: page, width: PageGrid.thumbWidth))
                    .aspectRatio(aspect(page), contentMode: .fit)
                    .clipShape(RoundedRectangle(cornerRadius: 7, style: .continuous))
                    .overlay {
                        RoundedRectangle(cornerRadius: 7, style: .continuous)
                            .strokeBorder(current ? accent.tint : cursor ? .white : .clear, lineWidth: 3)
                    }
                Text("\(page + 1)")
                    .font(HubType.body(12.5, weight: current ? .bold : .medium, relativeTo: .caption))
                    .monospacedDigit()
                    .foregroundStyle(current ? accent.tint : .white.opacity(0.8))
            }
            .padding(4)
            .background {
                if cursor { RoundedRectangle(cornerRadius: 10, style: .continuous).fill(.white.opacity(0.12)) }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Page \(page + 1)")
        .accessibilityAddTraits(current ? .isSelected : [])
    }

    /// A page's shape as the manifest gives it: a comic page's 2:3 until it does.
    private func aspect(_ page: Int) -> CGFloat {
        guard let size = reader.manifest?.pages.first(where: { $0.index == page }), size.width > 0, size.height > 0 else {
            return 1 / PageGrid.thumbAspect
        }
        return CGFloat(size.width) / CGFloat(size.height)
    }
}
