#if os(iOS)
import HubKit
import SwiftUI

/// The book's sheets, in the readers' frame (`ReaderSheetFrame`), the page
/// shrunk beside them so a change of appearance shows as it is made:
/// Contents (with the bookmarks beside it), Appearance and Keys.
struct BookReaderSheetView: View {
    let reader: BookReaderModel
    let sheet: BookReaderModel.Sheet
    let layout: ComicReaderLayout

    var body: some View {
        ReaderSheetFrame(title: title, subtitle: subtitle, size: layout.size, safe: layout.safe,
                         headingId: "book-sheet-heading", dims: false, keepsPage: true, previews: sheet == .appearance,
                         close: close) { proxy in
            switch sheet {
            case .contents, .bookmarks: navigator(proxy)
            case .appearance: BookAppearanceSheet(reader: reader)
            case .keys: keys
            }
        }
    }

    private var title: String {
        switch sheet {
        case .contents, .bookmarks: "Contents"
        case .appearance: "Appearance"
        case .keys: "Keys"
        }
    }

    private var subtitle: String {
        switch sheet {
        case .contents, .bookmarks: reader.title
        case .appearance: "Every book · kept as you change it"
        case .keys: "What the keys do while you read a book"
        }
    }

    /// Once the sheet has slid away: unless another opened meanwhile.
    private func close() {
        let same = reader.sheet == sheet || (reader.sheet == .bookmarks && sheet == .contents)
            || (reader.sheet == .contents && sheet == .bookmarks)
        if same { reader.sheet = nil }
    }

    // MARK: Contents and bookmarks

    @ViewBuilder private func navigator(_ proxy: ScrollViewProxy) -> some View {
        HStack(spacing: 8) {
            ChoicePill(title: "Contents", selected: reader.sheet == .contents) { reader.openSheet(.contents) }
            ChoicePill(title: "Bookmarks", selected: reader.sheet == .bookmarks) { reader.openSheet(.bookmarks) }
        }
        if reader.sheet == .bookmarks {
            bookmarks
        } else {
            contents(proxy)
        }
    }

    @ViewBuilder private func contents(_ proxy: ScrollViewProxy) -> some View {
        if reader.contents.isEmpty {
            SheetNote(text: "This book has no contents.")
        } else {
            SheetGroup {
                ForEach(Array(reader.contents.enumerated()), id: \.element.id) { index, row in
                    let current = index == reader.currentContentsRow
                    SheetRow(title: row.title, detail: current ? "Where you are" : "", checked: current) {
                        reader.openContents(row)
                    } leading: {
                        if row.depth > 0 { Color.clear.frame(width: CGFloat(row.depth) * 16, height: 1) }
                    }
                    .overlay { cursor(index) }
                    .id(row.id)
                }
            }
            .onAppear {
                if let row = reader.currentContentsRow { proxy.scrollTo(reader.contents[row].id, anchor: .center) }
            }
            .onChange(of: reader.sheetCursor) { _, cursor in
                if reader.contents.indices.contains(cursor) {
                    withAnimation(.easeOut(duration: 0.15)) { proxy.scrollTo(reader.contents[cursor].id, anchor: .center) }
                }
            }
        }
    }

    @ViewBuilder private var bookmarks: some View {
        if reader.bookmarks.isEmpty {
            SheetNote(text: "No bookmarks in this book yet. Ⓧ, or the bookmark at the top, keeps the page you are on.")
        } else {
            SheetGroup {
                ForEach(Array(reader.bookmarks.enumerated()), id: \.element.anchor) { index, bookmark in
                    HStack(spacing: 0) {
                        SheetRow(title: bookmark.label, chevron: true) { reader.openBookmark(bookmark) }
                        Button {
                            reader.deleteBookmark(bookmark)
                        } label: {
                            Image(systemName: "trash")
                                .font(.system(size: 15, weight: .semibold))
                                .frame(width: 48, height: 48)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .foregroundStyle(.white.opacity(0.7))
                        .accessibilityLabel("Delete the bookmark at \(bookmark.label)")
                    }
                    .overlay { cursor(index) }
                }
            }
        }
    }

    /// The line the controller is on.
    @ViewBuilder private func cursor(_ index: Int) -> some View {
        if reader.controllerActive && index == reader.sheetCursor {
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .strokeBorder(.white, lineWidth: 2)
                .padding(2)
                .allowsHitTesting(false)
        }
    }

    // MARK: Keys

    @ViewBuilder private var keys: some View {
        SheetLabel(text: "Game controller")
        ReaderKeyLines(lines: ReaderPadMap.sheet(.book, state: reader.padState))
        SheetNote(text: "With the menu open, A presses the control in focus and B leaves the book.")
        SheetLabel(text: "Keyboard")
        ReaderKeyLines(lines: ReaderKeyboard.sheet(.book, state: reader.padState))
        SheetLabel(text: "Touch")
        ReaderKeyLines(lines: [
            ReaderKeyLine(["Swipe across"], reader.preferences.scrolls ? "The next part" : "Turn the page"),
            ReaderKeyLine(["Middle"], "Menu"),
            ReaderKeyLine(["A note's number"], "The note, over the page"),
        ])
    }
}

/// Appearance (Android's `EpubAppearancePanel`): Font (the typeface, its
/// size, one page per screen), Layout (columns, margins, line spacing,
/// scrolling, the publisher's styling, justified text), Themes (the page's
/// colours) and Comfort (#37: brightness, warmth, a black page, the screen
/// kept on while narrating). Every change shows at once and is kept.
struct BookAppearanceSheet: View {
    let reader: BookReaderModel

    private var value: EpubReaderPreferences { reader.preferences }

    var body: some View {
        HStack(spacing: 8) {
            tab("Font", .font)
            tab("Layout", .layout)
            tab("Themes", .themes)
            tab("Comfort", .comfort)
        }
        switch reader.appearanceTab {
        case .font: font
        case .layout: layout
        case .themes: themes
        case .comfort: ComfortControls(book: true)
        }
    }

    private func tab(_ title: String, _ id: BookReaderModel.AppearanceTab) -> some View {
        ChoicePill(title: title, selected: reader.appearanceTab == id) { reader.appearanceTab = id }
    }

    private func set(_ next: EpubReaderPreferences) { reader.setPreferences(next) }

    // MARK: Font

    @ViewBuilder private var font: some View {
        SheetLabel(text: "Typeface")
        HStack(spacing: 8) {
            ForEach(EpubAppearance.typefaces, id: \.id) { face in
                AppearanceTile(label: face.label, selected: value.fontFamily == face.id) {
                    set(EpubAppearance.typeface(value, face.id))
                } sample: {
                    Text("Aa")
                        .font(.system(size: 26, weight: .regular, design: face.id == "sans-serif" ? .default : .serif))
                }
            }
        }
        SheetLabel(text: "Size")
        SheetGroup {
            HStack(spacing: 12) {
                GlassRoundButton(systemImage: "textformat.size.smaller", label: "Smaller", size: 40) {
                    set(EpubAppearance.fontSize(value, steps: -1))
                }
                .disabled(value.fontScale <= EpubAppearance.fontScales.lowerBound)
                Text(EpubAppearance.fontSizeLabel(value.fontScale))
                    .font(HubType.body(17, weight: .bold, relativeTo: .body))
                    .monospacedDigit()
                    .frame(maxWidth: .infinity)
                    .accessibilityLabel("Font size \(EpubAppearance.fontSizeLabel(value.fontScale))")
                GlassRoundButton(systemImage: "textformat.size.larger", label: "Larger", size: 40) {
                    set(EpubAppearance.fontSize(value, steps: 1))
                }
                .disabled(value.fontScale >= EpubAppearance.fontScales.upperBound)
            }
            .padding(10)
        }
        SheetGroup {
            SheetRow(title: "One full page per screen", detail: "One column, no scrolling", checked: value.onePagePerScreen) {
                set(EpubLayoutPolicy.selectOnePage(value, !value.onePagePerScreen))
            }
        }
        SheetNote(text: "An ebook's pages follow its font and the screen: they are not the printed book's page numbers.")
    }

    // MARK: Layout

    @ViewBuilder private var layout: some View {
        SheetLabel(text: "Columns")
        HStack(spacing: 8) {
            ForEach([(EpubColumns.one, "One page"), (EpubColumns.two, "Two pages")], id: \.0) { columns, label in
                AppearanceTile(label: label, selected: value.columns == columns) {
                    set(EpubLayoutPolicy.selectColumns(value, columns))
                } sample: {
                    PageSample(columns: columns == .two ? 2 : 1)
                }
            }
        }
        SheetLabel(text: "Margins")
        HStack(spacing: 8) {
            ForEach(EpubAppearance.margins, id: \.label) { margin in
                AppearanceTile(label: margin.label, selected: EpubAppearance.same(value.pageMargins, margin.amount)) {
                    var next = value
                    next.pageMargins = margin.amount
                    set(next)
                } sample: {
                    PageSample(margin: margin.amount * 0.15)
                }
            }
        }
        SheetLabel(text: "Line spacing")
        HStack(spacing: 8) {
            ForEach(Array(EpubAppearance.spacing.enumerated()), id: \.offset) { index, spacing in
                AppearanceTile(label: spacing.label, selected: EpubAppearance.same(value.lineHeight, spacing.amount)) {
                    set(EpubAppearance.lineSpacing(value, spacing.amount))
                } sample: {
                    PageSample(spacing: 5 + Double(index) * 3)
                }
            }
        }
        SheetGroup {
            SheetRow(title: "Automatic columns", detail: "Two side by side where the window is wide",
                     checked: value.columns == .auto) { set(EpubLayoutPolicy.selectColumns(value, .auto)) }
            SheetRow(title: "Continuous scrolling", value: value.scroll ? "On" : "Off") {
                set(EpubLayoutPolicy.selectScroll(value, !value.scroll))
            }
            SheetRow(title: "Publisher styling", value: value.publisherStyles ? "On" : "Off") {
                var next = value
                next.publisherStyles.toggle()
                set(next)
            }
            SheetRow(title: "Justified text", value: value.textAlignment == "justify" ? "On" : "Off") {
                set(EpubAppearance.justified(value))
            }
        }
    }

    // MARK: Themes

    @ViewBuilder private var themes: some View {
        SheetLabel(text: "Page colour")
        let rows = [Array(EpubAppearance.themes.prefix(2)), Array(EpubAppearance.themes.suffix(2))]
        ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
            HStack(spacing: 8) {
                ForEach(row, id: \.label) { theme in
                    let palette = EpubPagePalette.of(theme.theme) ?? (0xFFFF_FFFF, 0xFF00_0000)
                    AppearanceTile(label: theme.label, selected: value.theme == theme.theme) {
                        var next = value
                        next.theme = theme.theme
                        set(next)
                    } sample: {
                        Text("Aa  The story\ncontinues.")
                            .font(.system(size: 14, design: .serif))
                            .multilineTextAlignment(.center)
                            .foregroundStyle(Color(argb: palette.ink))
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                            .background(Color(argb: palette.page))
                    }
                }
            }
        }
        SheetGroup {
            SheetRow(title: "Use system colours", detail: "Paper by day, Night in dark mode", checked: value.theme == .system) {
                var next = value
                next.theme = .system
                set(next)
            }
        }
    }
}

/// A choice shown as what it looks like (Android's sample tiles): a picture,
/// its name under it, the accent round it while chosen.
struct AppearanceTile<Sample: View>: View {
    let label: String
    let selected: Bool
    let action: () -> Void
    @ViewBuilder let sample: Sample
    @Environment(\.glassAccent) private var accent

    var body: some View {
        Button(action: action) {
            VStack(spacing: 6) {
                sample
                    .frame(height: 50)
                    .frame(maxWidth: .infinity)
                    .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
                Text(label)
                    .font(HubType.body(12.5, weight: .semibold, relativeTo: .caption))
                    .lineLimit(1)
            }
            .padding(6)
            .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(.white.opacity(0.08)))
            .overlay {
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .strokeBorder(selected ? accent.tint : .white.opacity(0.14), lineWidth: selected ? 2 : 1)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// Lines of text on a page, for the column, margin and spacing tiles.
struct PageSample: View {
    var columns = 1
    var margin = 0.12
    var spacing = 6.0

    var body: some View {
        Canvas { context, size in
            context.fill(Path(CGRect(origin: .zero, size: size)), with: .color(.white.opacity(0.1)))
            let inset = size.width * margin
            let gutter = columns == 2 ? size.width * 0.08 : 0
            let width = (size.width - inset * 2 - gutter) / Double(columns)
            for column in 0..<columns {
                let x = inset + Double(column) * (width + gutter)
                var y = size.height * 0.15
                while y < size.height * 0.86 {
                    var line = Path()
                    line.move(to: CGPoint(x: x, y: y))
                    line.addLine(to: CGPoint(x: x + width, y: y))
                    context.stroke(line, with: .color(.white.opacity(0.6)), lineWidth: max(1, size.height / 50))
                    y += size.height * spacing / 50
                }
            }
        }
        .accessibilityHidden(true)
    }
}
#endif
