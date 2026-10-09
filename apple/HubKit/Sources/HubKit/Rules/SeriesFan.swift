import Foundation

/// The Books library's Series view as fans of covers (#54): the owner's rule,
/// decided from mockups with real covers, as the Pocket's `SeriesFan`. Pure,
/// so each part is tested: which books stand in the fan, which is lit, how
/// each slot leans and darkens, the caption, the bar under it and where a tap
/// goes. The views only draw the `Plan`.
///
/// - **Five fixed slots**, the angles symmetrical about the middle; a series of
///   fewer books has that many slots.
/// - The book you are on takes the middle slot when it has two books before
///   and two after it. Near the start (#1, #2) or the end the fan keeps its
///   shape and shows the first or last five, and your book is lit in its own
///   slot at that slot's angle: an accent edge, a little bigger, raised and on
///   top. The others are layered by distance from it and darken slightly with it.
/// - Not started: nothing lit, books 1 to 5, the first in front. Finished:
///   nothing lit, the same five, and the finished tick on the front book.
/// - Books you do not have are in the fan, dimmed, and in the bar as outlines;
///   the hub lists only the main numbered books that are out as missing.
/// - Every slot is tall; an audiobook-only book's square sits at the slot's
///   bottom. No format marks on the fan.
///
/// One direction everywhere: the first book in front on the left, fanning
/// right to the last. The Series view, Books Home's series and a series page's
/// header all stand a `Plan` from `plan`; the smaller two have room for
/// `smallSlots` (the same rule, centred on the book you are on).
public enum SeriesFan {
    /// The most books a fan shows.
    public static let slots = 5
    /// Where there is room for less (Books Home's series, a series page's
    /// header): three slots, the book you are on in the middle when it has a
    /// book each side.
    public static let smallSlots = 3
    /// A series longer than this has one continuous bar with a mark, not a part for each book.
    public static let continuousAfter = 25
    /// How far each slot leans from the one before it, in degrees: -16, -8, 0, 8, 16 for five.
    public static let angleStep = 8.0
    /// How far apart slots stand, as a fraction of a cover's width.
    public static let stepFraction = 0.42
    /// How much a slot darkens for each slot between it and the front, and the most it darkens.
    public static let shadeStep = 0.14
    public static let maxShade = 0.5
    /// The lit book is a little bigger and raised, as a fraction of a cover's size.
    public static let litScale = 1.1
    public static let litRaise = 0.06
    /// The most the fan opens with focus: its angles and the room between slots grow by this, when there is room.
    public static let maxOpening = 1.22
    /// How far past its card a fan may open, in points.
    public static let spreadMargin = 3.0

    public enum Part: Equatable, Sendable { case read, on, toRead, missing }

    /// One slot of the fan: a book, and how it stands.
    public struct Slot: Equatable, Sendable {
        public let book: ReadingSeriesBook
        /// The book's place among the series' books (0 for the first).
        public let index: Int
        /// The book you are on.
        public let lit: Bool
        /// The slot on top: the lit one, else the first.
        public let front: Bool
        public let angle: Double
        /// Slots from the middle: -2 to 2 for five.
        public let offset: Double
        /// Higher stands in front.
        public let layer: Int
        /// How much it is darkened, 0 for the lit book.
        public let shade: Double
        /// A book you do not have.
        public let dimmed: Bool
        /// An audiobook-only book: a square cover at the slot's bottom.
        public let square: Bool
    }

    public enum Bar: Equatable, Sendable {
        /// A part for each book.
        case segments([Part])
        /// One line: `read` of it in the accent, a mark where you are on (nil with nothing on).
        case continuous(read: Double, mark: Double?)
    }

    /// What a tap does.
    public enum Target: Equatable, Sendable {
        /// The series page, at the book numbered `number` (the lit one), or from its start.
        case openSeries(number: String?)
        /// The request page of a book you do not have.
        case request(ReadingSeriesBook)
    }

    public struct Plan: Equatable, Sendable {
        public let slots: [Slot]
        public let caption: String
        public let bar: Bar
        /// Some book is read or on.
        public let started: Bool
        /// Every book you have is read: nothing is lit, the bar is all read and the front book carries the tick.
        public let finished: Bool
        public let target: Target
    }

    /// Whether a library item is a series with books to fan.
    public static func hasFan(_ item: ReadingWork) -> Bool {
        item.entityType == "collection" && !item.seriesBooks.isEmpty
    }

    /// The books a series' fan stands on, in order. A series page (and Books
    /// Home's series) carries its books in its sections with the progress of
    /// each: the one you are on is lit, those finished are read, those you do
    /// not have are dimmed. The library's listing has no sections and sends
    /// the hub's own list, `seriesBooks`. A series with neither is its one cover.
    public static func books(of series: ReadingWork) -> [ReadingSeriesBook] {
        let items = series.sections.flatMap(\.items)
        if items.isEmpty {
            if !series.seriesBooks.isEmpty { return series.seriesBooks }
            return series.artwork.isEmpty ? [] : [ReadingSeriesBook(title: series.title, cover: series.artwork, owned: true)]
        }
        let on = ReadingShelves.onNumber(series)
        var litFound = false
        return items.map { item in
            let read = item.progress?.completed == true
            let lit = !read && !litFound && !on.trimmingCharacters(in: .whitespaces).isEmpty && item.number == on
            if lit { litFound = true }
            return ReadingSeriesBook(number: item.number, title: item.title, cover: item.artwork,
                                     kind: ReadingBookFacts.coverShape(item) == .square ? "audiobook" : "book",
                                     owned: item.isAvailable, released: true,
                                     state: lit ? "on" : read ? "read" : "")
        }
    }

    /// The fan of `series`: up to `maxSlots` (an odd number) of its books, the
    /// one you are on lit and, when it has `maxSlots / 2` books each side, in
    /// the middle slot. Nil for a series with no books.
    public static func plan(_ series: ReadingWork, maxSlots: Int = SeriesFan.slots) -> Plan? {
        precondition(maxSlots >= 1 && maxSlots % 2 == 1, "a fan has an odd number of slots: \(maxSlots)")
        let books = books(of: series)
        guard !books.isEmpty else { return nil }
        let lit = books.firstIndex { $0.state == "on" }
        let owned = books.filter(\.owned)
        let finished = !owned.isEmpty && owned.allSatisfy { $0.state == "read" }
        let started = books.contains { $0.state == "on" || $0.state == "read" }
        let size = min(maxSlots, books.count)
        let start = lit.map { min(max($0 - maxSlots / 2, 0), books.count - size) } ?? 0
        let front = lit.map { $0 - start } ?? 0
        let centre = Double(size - 1) / 2
        let slots = (0..<size).map { slot -> Slot in
            let index = start + slot
            let book = books[index]
            let distance = abs(slot - front)
            return Slot(book: book, index: index, lit: index == lit, front: slot == front,
                        angle: (Double(slot) - centre) * angleStep, offset: Double(slot) - centre,
                        // Nearer the front is higher; of two at one distance the later book is.
                        layer: (size - distance) * 2 + (slot > front ? 1 : 0),
                        shade: distance == 0 ? 0 : min(maxShade, Double(distance) * shadeStep),
                        dimmed: !book.owned, square: book.kind == "audiobook")
        }
        let parts = books.map { book -> Part in
            if !book.owned { return .missing }
            switch book.state {
            case "on": return .on
            case "read": return .read
            default: return .toRead
            }
        }
        let bar: Bar = books.count <= continuousAfter
            ? .segments(parts)
            : .continuous(read: Double(finished ? books.count : books.filter { $0.state == "read" }.count) / Double(books.count),
                          mark: lit.map { (Double($0) + 0.5) / Double(books.count) })
        let frontBook = slots[front].book
        let litNumber = lit.map { books[$0].number } ?? ""
        let target: Target = frontBook.owned
            ? .openSeries(number: litNumber.trimmingCharacters(in: .whitespaces).isEmpty ? nil : litNumber)
            : .request(frontBook)
        return Plan(slots: slots, caption: caption(count: books.count, on: litNumber, finished: finished),
                    bar: bar, started: started, finished: finished, target: target)
    }

    /// "6 books · on #1", "1 book", "8 books · finished".
    public static func caption(count: Int, on number: String, finished: Bool) -> String {
        var parts: [String] = []
        if count > 0 { parts.append("\(count) \(count == 1 ? "book" : "books")") }
        if finished {
            parts.append("finished")
        } else if !number.trimmingCharacters(in: .whitespaces).isEmpty {
            parts.append("on #\(number)")
        }
        return parts.joined(separator: " · ")
    }

    // MARK: Geometry

    /// A tall cover `width` across is this high; a square one as high as it is wide.
    public static func coverHeight(_ width: Double, square: Bool) -> Double { square ? width : width * 1.5 }

    /// How far the fan opens: 1 at rest, and with focus as much as
    /// `maxOpening` allows without its covers reaching further from the
    /// fan's middle than `halfWidth`. A fan already as wide as its card does not open.
    public static func opening(focused: Bool, plan: Plan, cover: Double, halfWidth: Double) -> Double {
        guard focused else { return 1 }
        let steps = Int(((maxOpening - 1) * 100).rounded())
        for step in stride(from: steps, through: 0, by: -1) {
            let opening = 1 + Double(step) / 100
            if reach(plan, cover: cover, opening: opening) <= halfWidth { return opening }
        }
        return 1
    }

    /// How far from the fan's middle `plan`'s covers `cover` across reach,
    /// opened by `opening`: the corners of each cover after it leans about its
    /// foot (and is made bigger, when it is the lit one).
    public static func reach(_ plan: Plan, cover: Double, opening: Double) -> Double {
        plan.slots.map { slot in
            let height = coverHeight(cover, square: slot.square)
            let scale = slot.lit ? litScale : 1
            let lean = slot.angle * opening * .pi / 180
            let foot = slot.offset * stepFraction * cover * opening
            // The pivot is the middle of the cover's foot; y runs down, so the top corners are at -height.
            return [(-cover / 2, 0.0), (cover / 2, 0.0), (-cover / 2, -height), (cover / 2, -height)].map { x, y in
                abs(foot + scale * (x * cos(lean) - y * sin(lean)))
            }.max() ?? 0
        }.max() ?? 0
    }

    /// How far a leaning lit cover's top corner rises over where it would
    /// stand upright, at the outermost of `slots` with the fan fully opened:
    /// the room the fan's box leaves over its covers.
    public static func leanRise(cover: Double, slots: Int = SeriesFan.slots) -> Double {
        let height = coverHeight(cover, square: false)
        let lean = Double(slots - 1) / 2 * angleStep * maxOpening * .pi / 180
        return max(0, litScale * (height * cos(lean) + cover / 2 * sin(lean) - height))
    }

    /// How wide a fan of `slots` covers `cover` across is at its widest, at
    /// rest: the book you are on lit at an outer slot, bigger and leaning.
    public static func fanWidth(cover: Double, slots: Int) -> Double {
        let outer = Double(max(slots - 1, 0)) / 2
        let lean = outer * angleStep * .pi / 180
        let corner = cover / 2 * cos(lean) + coverHeight(cover, square: false) * sin(lean)
        return 2 * (outer * stepFraction * cover + litScale * corner)
    }

    /// The room across a fan of `slots` covers `cover` wide needs, at rest.
    public static func width(cover: Double, slots: Int) -> Double {
        cover + Double(max(slots - 1, 0)) * cover * stepFraction
    }

    /// The most slots (five, three or one) a fan of covers `cover` across has room for in `available`.
    public static func slotsFor(cover: Double, available: Double) -> Int {
        [slots, smallSlots, 1].first { fanWidth(cover: cover, slots: $0) <= available } ?? 1
    }
}
