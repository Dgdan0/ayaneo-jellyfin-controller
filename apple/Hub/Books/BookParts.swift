import HubKit
import SwiftUI

// The Books side's shared parts (#25): where a book leads, its cover and card,
// a series' fan, the formats as chips, and a row of a series' books. Their
// look is the prototype's `.card.book`, `.fan`, `.fmt`, `.mini` and `.cont`
// (GLASS_PLAN.md, "Books Home" and "Books pages"); their words are HubKit's
// `ReadingBookFacts` and `SeriesBookLabels`.

// MARK: Where a book leads

/// One of the reading libraries.
struct ReadingLibraryRoute: Hashable {
    let library: ReadingLibrary
}

/// A book, a series or a comic run. `openEntry` opens it as its main button
/// would, once its page has arrived: Books Home's Resume reading.
struct BookRoute: Hashable {
    let workId: String
    let title: String
    var openEntry = false
}

/// An author's shelf in one library.
struct AuthorRoute: Hashable {
    let libraryId: String
    let id: String
    let name: String
    var artwork = ""
    var seriesCount = 0
    var bookCount = 0
    var total = 0
}

/// A book of a series the library does not have.
struct MissingBookRoute: Hashable {
    let item: ReadingSectionItem
    /// "ebook" when the library has the book without one (#39): only the ebook is missing.
    var lacking = ""
}

/// A title BookKeeprr knows, to request.
struct BookRequestRoute: Hashable {
    let item: ReadingItem
}

/// The releases a request found, one book at a time.
struct ReadingReleasesRoute: Hashable {
    let targets: [ReadingRequestTarget]
}

/// An audiobook to listen to (#25 phase 2): the book and which narration.
struct ListenRoute: Hashable {
    let workId: String
    let sourceItemId: String
    let title: String
}

extension ReadingSectionItem {
    /// Its own page when the library has it, else a search to request it.
    var route: AppRoute {
        isAvailable ? .book(BookRoute(workId: workId, title: title)) : .missingBook(MissingBookRoute(item: self))
    }
}

// MARK: A cover

/// A book's shape: the spine's side square, the fore-edge rounded (`.card.book .pic`).
struct BookShape: InsettableShape {
    var spine: CGFloat = 4
    var edge: CGFloat = 9
    var inset: CGFloat = 0

    func path(in rect: CGRect) -> Path {
        let frame = rect.insetBy(dx: inset, dy: inset)
        return UnevenRoundedRectangle(topLeadingRadius: max(0, spine - inset), bottomLeadingRadius: max(0, spine - inset),
                                      bottomTrailingRadius: max(0, edge - inset), topTrailingRadius: max(0, edge - inset),
                                      style: .continuous).path(in: frame)
    }

    func inset(by amount: CGFloat) -> BookShape {
        var shape = self
        shape.inset += amount
        return shape
    }
}

/// A cover at the width it is given: 2:3, or square for an audiobook, which
/// is the shape an audiobook's cover is.
struct BookCover: View {
    let path: String
    var square = false
    /// The pixel width to ask the hub for; a reading cover comes in the one
    /// size its server has, so this only sizes the decoding.
    var width = 360
    var dimmed = false

    var body: some View {
        Color.clear
            .aspectRatio(square ? 1 : 2 / 3, contentMode: .fit)
            .overlay { ArtworkView(path: path, width: width) }
            .overlay { if dimmed { Color.black.opacity(0.55) } }
            .clipShape(square ? AnyShape(RoundedRectangle(cornerRadius: 9, style: .continuous)) : AnyShape(BookShape()))
    }
}

/// How far into a book, inside its cover's foot (`.pbar`): the accent while
/// it is being read, as Android's `GlassProgressBar` on a book; nothing
/// before it is started or once it is finished.
struct BookProgressBar: View {
    let fraction: Double
    @Environment(\.glassAccent) private var accent

    var body: some View {
        if fraction > 0 && fraction < 1 {
            GeometryReader { geometry in
                let inset = geometry.size.width * 0.06
                ZStack(alignment: .leading) {
                    Capsule().fill(.white.opacity(0.28))
                    Capsule().fill(accent.tint).frame(width: max(4, (geometry.size.width - inset * 2) * fraction))
                }
                .frame(height: 4)
                .padding(.horizontal, inset)
                .frame(maxHeight: .infinity, alignment: .bottom)
                .padding(.bottom, inset)
            }
            .accessibilityLabel("\(Fmt.readingPercent(fraction))% read")
        }
    }
}

/// A word on a cover's foot (`.pic .kind`): "Comic", "Manga", "Audio", "Read along".
struct CoverPill: View {
    let text: String

    var body: some View {
        Text(text)
            .font(HubType.chrome(10, weight: .bold))
            .tracking(0.4)
            .foregroundStyle(.white)
            .padding(.horizontal, 7)
            .padding(.vertical, 4)
            .background(.black.opacity(0.62), in: RoundedRectangle(cornerRadius: 7, style: .continuous))
    }
}

/// A finished book's tick in its corner (`.num.w`).
struct ReadTick: View {
    @Environment(\.glassAccent) private var accent

    var body: some View {
        Image(systemName: "checkmark")
            .font(.system(size: 11, weight: .heavy))
            .foregroundStyle(accent.inkColor)
            .frame(width: 22, height: 22)
            .background(accent.tint, in: Circle())
            .accessibilityLabel("Finished")
    }
}

/// A book on a row or in a grid: its cover with how far in, a word for an
/// unusual kind, and its title and second line under it.
struct BookCard: View {
    let work: ReadingWork
    var caption = true
    /// Books Home's comics and manga carry their kind on the cover.
    var showKind = false
    /// The second line, when not the work's own ("Red Rising #6").
    var detail: String?
    @Environment(\.glassMetrics) private var metrics

    private var progress: Double {
        guard let p = work.progress else { return 0 }
        return p.completed ? 1 : p.percentage
    }

    /// A Storyteller book that is only audio says so on its cover; one with a
    /// read-along edition says that.
    private var pill: String? {
        if showKind, let kind = ReadingBookFacts.kindTag(work.kind) { return kind }
        // A series is many books: no one format speaks for it.
        guard !work.isSeries else { return nil }
        let formats = ReadingBookFacts.formats(work)
        if work.kind == "audiobook" || (!formats.isEmpty && !formats.contains("ebook") && formats.contains("audiobook")) {
            return "Audio"
        }
        if formats.contains("readaloud") { return "Read along" }
        return nil
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            BookCover(path: work.artwork, square: work.kind == "audiobook")
                .overlay { BookProgressBar(fraction: progress) }
                .overlay(alignment: .bottomLeading) {
                    if let pill { CoverPill(text: pill).padding(6).padding(.bottom, progress > 0 && progress < 1 ? 10 : 0) }
                }
                // The tick is for every finished book (#63): read to the end, marked Finished, or imported as read.
                .overlay(alignment: .topTrailing) { if ReadingStatus.isFinished(work) { ReadTick().padding(6) } }
                .litArtwork(corner: 9)
            if caption {
                CardCaption(title: work.title, detail: detail ?? (ReadingBookFacts.kindTag(work.kind) != nil
                                                                  ? ReadingBookFacts.comicLine(work.progress) : work.cardSubtitle))
            }
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

/// A book of a series, or of an author's shelf: its cover, dimmed when the
/// library does not have it, and "#2 · 40% · Audio" under it.
struct SeriesBookCard: View {
    let item: ReadingSectionItem
    /// "This book" in place of its line, on the book's own page.
    var current = false

    private var progress: Double {
        guard let p = item.progress else { return 0 }
        return p.completed ? 1 : p.percentage
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            BookCover(path: item.artwork, square: item.kind == "audiobook", dimmed: !item.isAvailable)
                .overlay { BookProgressBar(fraction: progress) }
                .overlay(alignment: .topTrailing) { if ReadingStatus.isFinished(item) { ReadTick().padding(6) } }
                .litArtwork(corner: 9)
            CardCaption(title: item.title,
                        detail: current ? "This book" : SeriesBookLabels.subtitle(number: item.number, available: item.isAvailable,
                                                                                 progress: item.progress, formats: item.formats,
                                                                                 finished: ReadingStatus.isFinished(item)))
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

/// A series' books in reading order, each opening its page (or, missing, a
/// search to request it). The series page, an author's page and "More in" a
/// series all show a series this way (Android's `SeriesBookStrip`).
struct SeriesBookStrip: View {
    let items: [ReadingSectionItem]
    /// The book whose page this is: shown, not opened.
    var current = ""
    /// Its group's id for a controller's focus (#46): a row of its books, by position.
    var pad: String?
    @Environment(\.glassMetrics) private var metrics
    @Environment(\.openRoute) private var openRoute

    var body: some View {
        let opened = items.indices.filter { current.isEmpty || items[$0].workId != current }
        ScrollView(.horizontal, showsIndicators: false) {
            LazyHStack(alignment: .top, spacing: metrics.gap) {
                ForEach(Array(items.enumerated()), id: \.offset) { index, item in
                    if !current.isEmpty && item.workId == current {
                        SeriesBookCard(item: item, current: true).frame(width: metrics.poster)
                    } else {
                        NavigationLink(value: item.route) {
                            SeriesBookCard(item: item).frame(width: metrics.poster)
                        }
                        .buttonStyle(GlassCardStyle())
                        .padFocusable(pad == nil ? nil : "\(index)", ring: .card, scroll: index) { openRoute(item.route) }
                    }
                }
            }
            .padding(.horizontal, metrics.margin)
            .padding(.top, 12)
            .padding(.bottom, 16)
        }
        .padGroup(pad, .row, members: opened.map { "\($0)" }, strip: true, scrollIds: opened.map { AnyHashable($0) })
    }
}

// MARK: A series' fan

/// A series as a fan of up to four covers leaning about their feet, the book
/// being read on top at the right (`.fan`; Android's `CoverFanView`). Lit, the
/// outer two lean further and the fan lifts.
struct CoverFan: View {
    /// Front first: the book being read, then the series' first books (`ReadingShelves.fanCovers`).
    let covers: [String]
    var coverWidth: CGFloat = 96
    @Environment(\.cardLit) private var lit

    private static let lean: [Double] = [-13, -5, 4, 12]
    private static let step: [CGFloat] = [0, 0.333, 0.667, 0.98]

    /// Room either side for the outer covers' lean: a cover turned 13° about
    /// its foot reaches a third of its width past where it stands.
    static func inset(coverWidth: CGFloat) -> CGFloat { coverWidth * 0.36 }

    /// The fan's own size for a cover this wide, its lean included.
    static func size(coverWidth: CGFloat) -> CGSize {
        CGSize(width: coverWidth * 1.98 + inset(coverWidth: coverWidth) * 2, height: coverWidth * 1.5 + coverWidth * 0.18)
    }

    var body: some View {
        let shown = Array(covers.prefix(4).reversed())
        let size = Self.size(coverWidth: coverWidth)
        ZStack(alignment: .bottomLeading) {
            ForEach(Array(shown.enumerated()), id: \.offset) { index, path in
                let slot = shown.count == 1 ? 1 : index
                BookCover(path: path, width: 240)
                    .frame(width: coverWidth)
                    .shadow(color: .black.opacity(lit ? 0.6 : 0.5), radius: lit ? 13 : 11, x: -6, y: lit ? 12 : 10)
                    .rotationEffect(.degrees(angle(slot, count: shown.count)), anchor: .bottom)
                    .offset(x: Self.inset(coverWidth: coverWidth) + Self.step[slot] * coverWidth, y: -4)
                    .overlay(alignment: .bottomLeading) {
                        if lit && index == shown.count - 1 {
                            BookShape().stroke(.white, lineWidth: 3)
                                .frame(width: coverWidth, height: coverWidth * 1.5)
                                .rotationEffect(.degrees(angle(slot, count: shown.count)), anchor: .bottom)
                                .offset(x: Self.inset(coverWidth: coverWidth) + Self.step[slot] * coverWidth, y: -4)
                        }
                    }
            }
        }
        .frame(width: size.width, height: size.height, alignment: .bottomLeading)
        .offset(y: lit ? -3 : 0)
        .animation(.easeOut(duration: 0.3), value: lit)
        .accessibilityHidden(true)
    }

    private func angle(_ slot: Int, count: Int) -> Double {
        var degrees = Self.lean[slot]
        if lit && slot == 0 && count > 1 { degrees = -18 }
        if lit && slot == 3 { degrees = 17 }
        return degrees
    }
}

// MARK: The formats

/// A format's mark: ebook, audiobook, read along.
enum BookFormatIcon {
    static func of(_ format: String) -> String {
        switch format {
        case "audiobook": "headphones"
        case "readaloud": "text.bubble"
        default: "book"
        }
    }
}

/// A book's three formats as glass chips, a missing one dimmed (`.fmts`).
struct FormatChips: View {
    let statuses: [ReadingFormatStatus]

    var body: some View {
        HStack(spacing: 6) {
            ForEach(statuses) { status in
                HStack(spacing: 6) {
                    Image(systemName: BookFormatIcon.of(status.kind)).font(.system(size: 12, weight: .semibold))
                    Text(status.label).font(HubType.body(12.5, weight: .bold, relativeTo: .caption))
                }
                .lineLimit(1)
                .foregroundStyle(.white)
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .glassPanel(Capsule())
                .opacity(status.readiness == .ready ? 1 : 0.42)
                .accessibilityElement(children: .combine)
                .accessibilityLabel("\(status.label), \(status.readiness.description)")
            }
        }
    }
}

/// A book's formats as small glass marks, on a row of a book also being read.
struct FormatMarks: View {
    let formats: [String]

    var body: some View {
        HStack(spacing: 5) {
            ForEach(formats, id: \.self) { format in
                Image(systemName: BookFormatIcon.of(format))
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 26, height: 22)
                    .glassPanel(Capsule())
            }
        }
        .accessibilityLabel(formats.map(ReadingBookFacts.formatLabel).joined(separator: ", "))
    }
}

// MARK: Lines

/// "Red Rising #6  ·  Pierce Brown", the number in the accent.
func seriesPlaceLine(_ work: ReadingWork, accent: Color) -> AttributedString {
    var line = AttributedString()
    if !work.series.trimmingCharacters(in: .whitespaces).isEmpty {
        var series = AttributedString(work.series)
        series.foregroundColor = .white.opacity(0.82)
        line += series
        if !work.seriesNumber.isEmpty {
            var number = AttributedString(" #" + work.seriesNumber)
            number.foregroundColor = accent
            number.font = HubType.body(15, weight: .bold, relativeTo: .subheadline)
            line += number
        }
    }
    if !work.byline.isEmpty {
        if !line.characters.isEmpty {
            var dot = AttributedString("  ·  ")
            dot.foregroundColor = .white.opacity(0.45)
            line += dot
        }
        var byline = AttributedString(work.byline)
        byline.foregroundColor = .white.opacity(0.82)
        line += byline
    }
    return line
}

/// A row's heading with a quiet count after it ("Also reading 2").
struct RowHeading: View {
    let title: String
    var count: String?
    @Environment(\.glassMetrics) private var metrics

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Text(title)
                .font(HubType.body(metrics.rowTitle, weight: .bold, relativeTo: .title3))
                .foregroundStyle(.white)
            if let count, !count.isEmpty {
                Text(count)
                    .font(HubType.body(13, weight: .semibold, relativeTo: .footnote))
                    .foregroundStyle(.white.opacity(0.58))
            }
        }
    }
}
