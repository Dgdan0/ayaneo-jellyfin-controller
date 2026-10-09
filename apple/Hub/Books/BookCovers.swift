import HubKit
import SwiftUI

// What a book's cover says of its formats, and a series as a fan of its books
// (#54): the Pocket's `FormatMark`, `CoverFanView`, `SeriesBarView` and
// `SeriesFanCardView`. Which shape, which mark and what stands where in a fan
// are HubKit's (`ReadingBookFacts.coverShape` / `formatMark`, `SeriesFan`);
// these only draw them.

// MARK: A book's cover

/// A book's cover where a book is shown on its own (the Books grid, Books
/// Home's rows, an author's or a series' row): tall for an ebook, square for
/// an audiobook, sitting at the foot of a tall cover's place so the covers of
/// a row line up along their feet and their titles on one line. A book that
/// is an ebook and an audiobook carries a small round mark in its top-left
/// corner; the finished tick keeps the top right and the progress bar the
/// foot, and a comic keeps its kind pill.
struct FormatCover: View {
    let path: String
    var shape: ReadingBookFacts.CoverShape = .tall
    var mark: ReadingBookFacts.CoverMark = .none
    var progress: Double = 0
    var finished = false
    /// A comic's or manga's kind ("Comic").
    var pill: String?
    /// A book the library does not have.
    var dimmed = false
    var width = 360

    var body: some View {
        Color.clear
            .aspectRatio(2 / 3, contentMode: .fit)
            .overlay(alignment: .bottom) {
                BookCover(path: path, square: shape == .square, width: width, dimmed: dimmed)
                    .overlay { BookProgressBar(fraction: progress) }
                    .overlay(alignment: .bottomLeading) {
                        if let pill { CoverPill(text: pill).padding(6).padding(.bottom, progress > 0 && progress < 1 ? 10 : 0) }
                    }
                    .overlay(alignment: .topTrailing) { if finished { ReadTick().padding(6) } }
                    .overlay(alignment: .topLeading) { FormatMarkView(mark: mark).padding(6) }
                    .litArtwork(corner: 9)
            }
    }
}

/// The small round mark of a book that is an ebook and an audiobook (#54): a
/// book with sound once it is aligned for read along, headphones until then.
/// An icon and no words, 18 points, on dark glass so it reads over any cover.
struct FormatMarkView: View {
    let mark: ReadingBookFacts.CoverMark
    static let size: CGFloat = 18

    var body: some View {
        if mark != .none {
            ZStack {
                Circle().fill(.black.opacity(0.62))
                glyph.foregroundStyle(.white)
            }
            .frame(width: Self.size, height: Self.size)
            .accessibilityElement()
            .accessibilityLabel(mark.words ?? "")
        }
    }

    @ViewBuilder private var glyph: some View {
        switch mark {
        case .readAlong:
            // A book with sound.
            HStack(spacing: 0.5) {
                Image(systemName: "book.closed.fill").font(.system(size: 7.5, weight: .bold))
                Image(systemName: "wave.3.right").font(.system(size: 5.5, weight: .heavy))
            }
            .offset(x: 0.5)
        case .headphones:
            Image(systemName: "headphones").font(.system(size: 9.5, weight: .bold))
        case .none:
            EmptyView()
        }
    }
}

// MARK: A series' fan

/// A series' books fanned out (#54), the one fan of the app: the Books
/// library's Series view, Books Home's series and the top of a series page.
/// It draws a `SeriesFan.Plan`: the first book in front on the left and the
/// others fanning right, symmetrical slots, the book you are on lit in its
/// slot (an accent edge, bigger, raised, on top), the others layered and
/// darkened by their distance from it, the books you do not have dimmed, and
/// an audiobook's square at its slot's foot. A lit card opens it a little, as
/// far as `room` (the most its covers may reach from its middle) allows.
struct SeriesFanView: View {
    let plan: SeriesFan.Plan
    /// A cover's width.
    let cover: CGFloat
    /// The slots its box is made for: five, or three where there is less room.
    var slots = SeriesFan.slots
    var room: CGFloat = .infinity
    @Environment(\.cardLit) private var lit
    @Environment(\.glassAccent) private var accent

    /// How far the covers stand off the box's foot, so a leaning cover's lowest corner stays inside it.
    private static let foot: CGFloat = 4
    private static let dimmed = 0.42

    /// The box a fan of `slots` covers `cover` across stands in: the slots at
    /// rest, and the lit one raised, bigger and leaning over the tallest cover.
    static func box(cover: CGFloat, slots: Int) -> CGSize {
        let width = SeriesFan.width(cover: cover, slots: slots)
        let tallest = cover * 1.5 * SeriesFan.litScale
        let rise = cover * 1.5 * SeriesFan.litRaise + SeriesFan.leanRise(cover: cover, slots: slots)
        return CGSize(width: ceil(width), height: ceil(tallest + rise + foot))
    }

    var body: some View {
        let size = Self.box(cover: cover, slots: slots)
        let opening = SeriesFan.opening(focused: lit, plan: plan, cover: cover, halfWidth: room)
        ZStack(alignment: .topLeading) {
            ForEach(Array(plan.slots.enumerated()), id: \.offset) { _, slot in
                let height = SeriesFan.coverHeight(cover, square: slot.square)
                let shape = slot.square ? AnyShape(RoundedRectangle(cornerRadius: 7, style: .continuous)) : AnyShape(BookShape(spine: 3, edge: 7))
                BookCover(path: slot.book.cover, square: slot.square, width: 240)
                    .frame(width: cover, height: height)
                    .overlay { if slot.shade > 0 { shape.fill(.black.opacity(slot.shade)) } }
                    .overlay { if slot.lit { shape.stroke(accent.tint, lineWidth: 2) } }
                    .overlay(alignment: .topTrailing) { if slot.front && plan.finished { ReadTick().scaleEffect(0.8).padding(3) } }
                    .opacity(slot.dimmed ? Self.dimmed : 1)
                    .shadow(color: .black.opacity(0.45), radius: 6, x: 0, y: 4)
                    .scaleEffect(slot.lit ? SeriesFan.litScale : 1, anchor: .bottom)
                    .rotationEffect(.degrees(slot.angle * opening), anchor: .bottom)
                    .offset(x: size.width / 2 + slot.offset * SeriesFan.stepFraction * cover * opening - cover / 2,
                            y: size.height - Self.foot - height - (slot.lit ? SeriesFan.litRaise * height : 0))
                    .zIndex(Double(slot.layer))
            }
        }
        .frame(width: size.width, height: size.height, alignment: .topLeading)
        .animation(.easeOut(duration: 0.25), value: lit)
        .accessibilityHidden(true)
    }
}

/// The thin bar under a series' caption (#54): the whole series at a glance,
/// the same width for every series, a part for each book: the accent for one
/// read, white for the one you are on, grey for one to read, an outline for
/// one you do not have. Past `SeriesFan.continuousAfter` books it is one line,
/// the accent as far as you have read, with a white mark where you are.
struct SeriesBar: View {
    let bar: SeriesFan.Bar
    @Environment(\.glassAccent) private var accent

    static let height: CGFloat = 4
    private static let gap: CGFloat = 2
    private static let grey = Color.white.opacity(0.24)
    private static let outline = Color.white.opacity(0.55)

    var body: some View {
        Canvas { context, size in
            let radius = size.height / 2
            switch bar {
            case .segments(let parts):
                guard !parts.isEmpty else { return }
                let gap = min(Self.gap, size.width / CGFloat(parts.count) * 0.4)
                let each = (size.width - gap * CGFloat(parts.count - 1)) / CGFloat(parts.count)
                for (index, part) in parts.enumerated() {
                    let box = CGRect(x: CGFloat(index) * (each + gap), y: 0, width: each, height: size.height)
                    let corner = min(radius, each / 2)
                    switch part {
                    case .missing:
                        context.stroke(Path(roundedRect: box.insetBy(dx: 0.5, dy: 0.5), cornerRadius: corner),
                                       with: .color(Self.outline), lineWidth: 1)
                    case .read: context.fill(Path(roundedRect: box, cornerRadius: corner), with: .color(accent.tint))
                    case .on: context.fill(Path(roundedRect: box, cornerRadius: corner), with: .color(.white))
                    case .toRead: context.fill(Path(roundedRect: box, cornerRadius: corner), with: .color(Self.grey))
                    }
                }
            case .continuous(let read, let mark):
                context.fill(Path(roundedRect: CGRect(origin: .zero, size: size), cornerRadius: radius), with: .color(Self.grey))
                if read > 0 {
                    let width = max(size.height, size.width * read)
                    context.fill(Path(roundedRect: CGRect(x: 0, y: 0, width: width, height: size.height), cornerRadius: radius),
                                 with: .color(accent.tint))
                }
                if let mark {
                    let x = min(max(size.width * mark, 1.5), size.width - 1.5)
                    context.fill(Path(roundedRect: CGRect(x: x - 1.5, y: -1.5, width: 3, height: size.height + 3), cornerRadius: 1.5),
                                 with: .color(.white))
                }
            }
        }
        .frame(height: Self.height)
        .accessibilityHidden(true)
    }
}

/// A series in the Books library's Series view (#54): its fan, its name,
/// "6 books · on #1" and the bar of the whole series. One card: a tap opens
/// the series at the book you are on, or the request page of a front book you
/// do not have (`SeriesFan.Plan.target`).
struct SeriesFanCard: View {
    let series: ReadingWork
    let plan: SeriesFan.Plan
    /// A cover's width.
    let cover: CGFloat
    /// The card's width, which the fan opens no further than.
    let width: CGFloat

    var body: some View {
        let rest = SeriesFanView.box(cover: cover, slots: SeriesFan.slots).width
        VStack(spacing: 0) {
            SeriesFanView(plan: plan, cover: cover, room: width / 2 + SeriesFan.spreadMargin)
            Text(series.title)
                .font(HubType.body(14, weight: .bold, relativeTo: .subheadline))
                .foregroundStyle(.white)
                .padding(.top, 10)
            Text(plan.caption)
                .font(HubType.body(12.5, relativeTo: .caption))
                .foregroundStyle(.white.opacity(0.64))
                .padding(.top, 2)
            SeriesBar(bar: plan.bar)
                .frame(width: max(rest, cover * 2.2))
                .padding(.top, 7)
        }
        .lineLimit(1)
        .frame(width: width)
        .contentShape(Rectangle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(series.title), \(plan.caption)")
    }
}
