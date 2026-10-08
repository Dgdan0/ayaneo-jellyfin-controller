import Foundation

// Where a page's text sits (#47, READER_TYPOGRAPHY_PLAN.md A3). Kindle keeps
// the same outer margin in either turn of the device and a narrow gap between
// two columns, so they read as one open book. Readium pads every column on
// both sides, so its gap is twice the outer margin: the app asks it for half
// the gap (`gutter`), and insets the whole page by the rest (`inset`).
//
// With M the outer margin, G the gap and P = G / 2 the gutter:
//   iPad:   M 90, G 48, P 24, inset 66 (a Balanced 744 pt page is 564 pt of text)
//   iPhone: M 24, G 24, P 12, inset 12

public enum EpubGeometry {
    /// An iPad's window narrower than this is laid out as a phone's (Slide Over).
    public static let tabletMinWidth = 600.0

    /// Readium's page gutter in CSS pixels: half the gap between two columns.
    /// It is given once, when the navigator is made, and holds while it lives.
    public static func gutter(tablet: Bool) -> Double { tablet ? 24 : 12 }

    private static let tabletMargins: [(amount: Double, points: Double)] = [(0.5, 48), (1, 90), (1.7, 120)]
    private static let phoneMargins: [(amount: Double, points: Double)] = [(0.5, 16), (1, 24), (1.7, 36)]

    /// The outer margin for the Narrow, Balanced and Wide choices (`pageMargins`
    /// 0.5, 1 and 1.7): Kindle's 90 points on a tablet's wide window, 24 on a phone.
    /// A value between two choices lies between their margins.
    public static func outerMargin(pageMargins: Double, tablet: Bool, width: Double) -> Double {
        let table = tablet && width >= tabletMinWidth ? tabletMargins : phoneMargins
        let amount = min(max(pageMargins, table[0].amount), table[table.count - 1].amount)
        for index in 1..<table.count where amount <= table[index].amount {
            let low = table[index - 1], high = table[index]
            return low.points + (high.points - low.points) * (amount - low.amount) / (high.amount - low.amount)
        }
        return table[table.count - 1].points
    }

    /// How far in the page's view is set on each side: the outer margin less
    /// the gutter Readium keeps itself, never below nothing.
    public static func inset(pageMargins: Double, tablet: Bool, width: Double, gutter: Double) -> Double {
        max(0, outerMargin(pageMargins: pageMargins, tablet: tablet, width: width) - gutter)
    }
}
