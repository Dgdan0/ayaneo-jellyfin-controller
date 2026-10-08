#if os(iOS)
import HubKit
import SwiftUI

/// The book's sheets, in the readers' frame (`ReaderSheetFrame`), the page
/// shrunk beside them so a change of appearance shows as it is made:
/// Contents (with the bookmarks beside it), Search, Appearance and Keys.
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
            case .search: BookSearchSheet(reader: reader, proxy: proxy)
            case .appearance: BookAppearanceSheet(reader: reader, proxy: proxy)
            case .keys:
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

    private var title: String {
        switch sheet {
        case .contents, .bookmarks: "Contents"
        case .search: "Search this book"
        case .appearance: "Appearance"
        case .keys: "Keys"
        }
    }

    private var subtitle: String {
        switch sheet {
        case .contents, .bookmarks: reader.title
        case .search: "Find a passage in this edition"
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
    private func cursor(_ index: Int) -> some View {
        Color.clear.readerRing(reader.controllerActive && index == reader.sheetCursor)
    }

    // MARK: Keys

    @ViewBuilder private var keys: some View {
        SheetLabel(text: "Game controller").id(ReaderKeysPart.controller.id)
        ReaderKeyLines(lines: ReaderPadMap.sheet(.book, state: reader.padState))
        SheetNote(text: "With the menu open, A presses the control in focus and B leaves the book.")
        SheetLabel(text: "Keyboard").id(ReaderKeysPart.keyboard.id)
        ReaderKeyLines(lines: ReaderKeyboard.sheet(.book, state: reader.padState))
        SheetLabel(text: "Touch").id(ReaderKeysPart.touch.id)
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
    let proxy: ScrollViewProxy

    private var value: EpubReaderPreferences { reader.preferences }

    var body: some View {
        HStack(spacing: 8) {
            tab("Font", .font)
            tab("Layout", .layout)
            tab("Themes", .themes)
            tab("Comfort", .comfort)
        }
        .id(Self.id(.tabs))
        switch reader.appearanceTab {
        case .font: font
        case .layout: layout
        case .themes: themes
        case .comfort: ComfortControls(book: true, ring: {
            if case .comfort(let line) = ringed?.line { return line }
            return nil
        }())
        }
        Color.clear.frame(height: 0)
            .onAppear { reader.appearanceWalk = SheetWalk() }
            .onChange(of: reader.appearanceWalk) { _, _ in
                guard let line = ringed?.line else { return }
                withAnimation(.easeOut(duration: 0.15)) { proxy.scrollTo(Self.id(line), anchor: .center) }
            }
    }

    private func tab(_ title: String, _ id: BookReaderModel.AppearanceTab) -> some View {
        let column = BookReaderModel.AppearanceTab.allCases.firstIndex(of: id) ?? 0
        return ChoicePill(title: title, selected: reader.appearanceTab == id) { reader.pressAppearance(.tabs, column: column) }
            .readerRing(isRinged(.tabs, column), corner: 20)
    }

    /// The line and choice a controller's ring is on, when a controller is in use (#25).
    private var ringed: (line: BookAppearanceLine, column: Int)? {
        guard reader.controllerActive else { return nil }
        let lines = BookAppearanceLine.lines(reader.appearanceTab.page)
        let walk = reader.appearanceWalk.clamped(to: lines.map(\.shape))
        return (lines[walk.line], walk.column)
    }

    private func isRinged(_ line: BookAppearanceLine, _ column: Int) -> Bool {
        guard let ringed else { return false }
        return ringed.line == line && ringed.column == column
    }

    /// Where the sheet scrolls to bring `line` into view.
    static func id(_ line: BookAppearanceLine) -> String {
        if case .comfort(let comfort) = line { return ComfortControls.id(comfort) }
        return "appearance-\(line)"
    }

    /// A choice among a line's tiles, pressed through the model as Ⓐ presses it.
    private func tile<Sample: View>(_ line: BookAppearanceLine, _ column: Int, _ label: String, selected: Bool,
                                    @ViewBuilder sample: () -> Sample) -> some View {
        AppearanceTile(label: label, selected: selected) { reader.pressAppearance(line, column: column) } sample: { sample() }
            .readerRing(isRinged(line, column), corner: 14)
    }

    /// A row of Appearance, pressed through the model.
    private func row(_ line: BookAppearanceLine, _ title: String, detail: String = "", value: String = "",
                     checked: Bool = false) -> some View {
        SheetRow(title: title, detail: detail, value: value, checked: checked) { reader.pressAppearance(line) }
            .readerRing(ringed?.line == line)
            .id(Self.id(line))
    }

    // MARK: Font

    @ViewBuilder private var font: some View {
        SheetLabel(text: "Typeface")
        HStack(spacing: 8) {
            ForEach(Array(EpubAppearance.typefaces.enumerated()), id: \.element.id) { column, face in
                tile(.typeface, column, face.label, selected: value.fontFamily == face.id) {
                    Text("Aa")
                        .font(.system(size: 26, weight: .regular, design: face.id == "sans-serif" ? .default : .serif))
                }
            }
        }
        .id(Self.id(.typeface))
        SheetLabel(text: "Size")
        SheetGroup {
            HStack(spacing: 12) {
                GlassRoundButton(systemImage: "textformat.size.smaller", label: "Smaller", size: 40) {
                    reader.adjustAppearance(.size, by: -1)
                }
                .disabled(value.fontScale <= EpubAppearance.fontScales.lowerBound)
                Text(EpubAppearance.fontSizeLabel(value.fontScale))
                    .font(HubType.body(17, weight: .bold, relativeTo: .body))
                    .monospacedDigit()
                    .frame(maxWidth: .infinity)
                    .accessibilityLabel("Font size \(EpubAppearance.fontSizeLabel(value.fontScale))")
                GlassRoundButton(systemImage: "textformat.size.larger", label: "Larger", size: 40) {
                    reader.adjustAppearance(.size, by: 1)
                }
                .disabled(value.fontScale >= EpubAppearance.fontScales.upperBound)
            }
            .padding(10)
            .readerRing(ringed?.line == .size)
        }
        .id(Self.id(.size))
        SheetGroup {
            row(.onePage, "One full page per screen", detail: "One column, no scrolling", checked: value.onePagePerScreen)
        }
        SheetNote(text: "An ebook's pages follow its font and the screen: they are not the printed book's page numbers.")
    }

    // MARK: Layout

    @ViewBuilder private var layout: some View {
        SheetLabel(text: "Columns")
        HStack(spacing: 8) {
            ForEach(Array([(EpubColumns.one, "One page"), (EpubColumns.two, "Two pages")].enumerated()), id: \.offset) {
                column, choice in
                tile(.columns, column, choice.1, selected: value.columns == choice.0) {
                    PageSample(columns: choice.0 == .two ? 2 : 1)
                }
            }
        }
        .id(Self.id(.columns))
        SheetLabel(text: "Margins")
        HStack(spacing: 8) {
            ForEach(Array(EpubAppearance.margins.enumerated()), id: \.offset) { column, margin in
                tile(.margins, column, margin.label, selected: EpubAppearance.same(value.pageMargins, margin.amount)) {
                    PageSample(margin: margin.amount * 0.15)
                }
            }
        }
        .id(Self.id(.margins))
        SheetLabel(text: "Line spacing")
        HStack(spacing: 8) {
            ForEach(Array(EpubAppearance.spacing.enumerated()), id: \.offset) { index, spacing in
                tile(.spacing, index, spacing.label, selected: EpubAppearance.same(value.lineHeight, spacing.amount)) {
                    PageSample(spacing: 5 + Double(index) * 3)
                }
            }
        }
        .id(Self.id(.spacing))
        SheetGroup {
            row(.automaticColumns, "Automatic columns", detail: "Two side by side where the window is wide",
                checked: value.columns == .auto)
            row(.scroll, "Continuous scrolling", value: value.scroll ? "On" : "Off")
            row(.publisher, "Publisher styling", value: value.publisherStyles ? "On" : "Off")
            row(.justified, "Justified text", value: value.textAlignment == "justify" ? "On" : "Off")
        }
    }

    // MARK: Themes

    @ViewBuilder private var themes: some View {
        SheetLabel(text: "Page colour")
        ForEach(Array(BookAppearanceLine.themeRows.enumerated()), id: \.offset) { rowIndex, row in
            HStack(spacing: 8) {
                ForEach(Array(row.enumerated()), id: \.element.label) { column, theme in
                    let palette = EpubPagePalette.of(theme.theme) ?? (0xFFFF_FFFF, 0xFF00_0000)
                    tile(.themes(rowIndex), column, theme.label, selected: value.theme == theme.theme) {
                        Text("Aa  The story\ncontinues.")
                            .font(.system(size: 14, design: .serif))
                            .multilineTextAlignment(.center)
                            .foregroundStyle(Color(argb: palette.ink))
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                            .background(Color(argb: palette.page))
                    }
                }
            }
            .id(Self.id(.themes(rowIndex)))
        }
        SheetGroup {
            row(.systemColours, "Use system colours", detail: "Paper by day, Night in dark mode", checked: value.theme == .system)
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
/// Search this book (#37; Android's search sheet): a word or phrase, then
/// the passages with it, each under its chapter with the match in bold. A
/// passage opens its page with the match marked; what was found stays for
/// the next time the sheet opens, so the next passage is a step away.
struct BookSearchSheet: View {
    @Bindable var reader: BookReaderModel
    let proxy: ScrollViewProxy
    @FocusState private var typing: Bool
    @Environment(\.glassAccent) private var accent

    var body: some View {
        HStack(spacing: 8) {
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass")
                    .foregroundStyle(.white.opacity(0.6))
                TextField("Word or phrase", text: $reader.searchText)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.search)
                    .focused($typing)
                    .onSubmit { reader.runSearch() }
                    .onChange(of: reader.searchText) { _, text in
                        if text.count > BookSearch.longestQuery { reader.searchText = String(text.prefix(BookSearch.longestQuery)) }
                    }
                    .accessibilityLabel("Search this book")
                    .accessibilityIdentifier("book-search-field")
            }
            .padding(.horizontal, 12)
            .frame(minHeight: 44)
            .glassPanel(Capsule())
            Button {
                typing = false
                reader.runSearch()
            } label: {
                Text("Search")
            }
            .buttonStyle(PrimaryPillStyle(accent: accent))
            .disabled(reader.searchState == .searching)
            .accessibilityIdentifier("book-search-go")
        }
        .onAppear {
            // A new search starts typing; one with results keeps them in view.
            if reader.searchState == .idle { typing = true }
        }
        results
    }

    @ViewBuilder private var results: some View {
        switch reader.searchState {
        case .idle:
            SheetNote(text: "A word or phrase as it is written in this edition. The first 100 passages are shown.")
        case .searching:
            HStack(spacing: 10) {
                ProgressView().tint(.white)
                Text(BookSearch.searching)
                    .font(HubType.body(14, relativeTo: .subheadline))
                    .foregroundStyle(.white.opacity(0.75))
            }
            .padding(.vertical, 6)
            .accessibilityElement(children: .combine)
        case .problem(let problem):
            SheetGroup {
                SheetRow(title: problem.title, detail: problem.detail, chevron: problem != .notSearchable) {
                    if problem != .notSearchable { reader.runSearch() }
                }
            }
        case .found(let hits):
            SheetLabel(text: BookSearch.summary(hits.count))
                .accessibilityIdentifier("book-search-summary")
            if !hits.isEmpty {
                SheetGroup {
                    ForEach(Array(hits.enumerated()), id: \.element.id) { index, hit in
                        Button { reader.openFound(hit) } label: { row(hit) }
                            .buttonStyle(SheetRowStyle())
                            .overlay { cursor(index) }
                            .accessibilityLabel(hit.label)
                            .accessibilityIdentifier("book-search-hit-\(index)")
                            .id(hit.id)
                    }
                }
                .onChange(of: reader.sheetCursor) { _, cursor in
                    if hits.indices.contains(cursor) {
                        withAnimation(.easeOut(duration: 0.15)) { proxy.scrollTo(hits[cursor].id, anchor: .center) }
                    }
                }
            }
        }
    }

    private func row(_ hit: BookSearchHit) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(hit.chapter)
                .font(HubType.body(12.5, weight: .semibold, relativeTo: .caption))
                .foregroundStyle(.white.opacity(0.6))
                .lineLimit(1)
            Text(passage(hit))
                .font(HubType.body(14.5, relativeTo: .body))
                .foregroundStyle(.white.opacity(0.78))
                .lineLimit(3)
                .multilineTextAlignment(.leading)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 14)
        .padding(.vertical, 11)
        .contentShape(Rectangle())
    }

    /// The passage with its match in bold, white.
    private func passage(_ hit: BookSearchHit) -> AttributedString {
        var line = AttributedString(hit.before)
        var match = AttributedString(hit.match)
        match.font = HubType.body(14.5, weight: .bold, relativeTo: .body)
        match.foregroundColor = .white
        line.append(match)
        line.append(AttributedString(hit.after))
        return line
    }

    @ViewBuilder private func cursor(_ index: Int) -> some View {
        if reader.controllerActive && index == reader.sheetCursor {
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .strokeBorder(.white, lineWidth: 2)
                .padding(2)
                .allowsHitTesting(false)
        }
    }
}
#endif
