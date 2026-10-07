import Foundation
import Testing
@testable import HubKit

/// The page curl's rules (#32): where it starts, when, and its leaves.
struct ComicCurlTests {
    @Test func aCurlStartsOnlyAtTheOuterEdgesAndMangaGoesOnFromTheLeft() {
        // An iPhone's page, 440 wide: 55 points at each edge.
        #expect(ComicCurl.zoneWidth(440) == 55)
        #expect(ComicCurl.zoneWidth(200) == 44)
        #expect(ComicCurl.zoneWidth(1366) == 80)
        #expect(ComicCurl.edge(x: 430, width: 440, rtl: false) == .forward)
        #expect(ComicCurl.edge(x: 10, width: 440, rtl: false) == .backward)
        #expect(ComicCurl.edge(x: 220, width: 440, rtl: false) == nil)
        #expect(ComicCurl.edge(x: 384, width: 440, rtl: false) == nil)
        // Right to left, the next page is on the left.
        #expect(ComicCurl.edge(x: 10, width: 440, rtl: true) == .forward)
        #expect(ComicCurl.edge(x: 430, width: 440, rtl: true) == .backward)
        #expect(ComicCurl.edge(x: 10, width: 0, rtl: false) == nil)
    }

    @Test func aZoomedPagePansAndThirdsTurnOnlyAtTheirLastStep() {
        #expect(ComicCurl.allowed(.forward, zoomed: false, thirds: false, step: 0, steps: 1))
        #expect(!ComicCurl.allowed(.forward, zoomed: true, thirds: false, step: 0, steps: 1))
        #expect(!ComicCurl.allowed(.backward, zoomed: true, thirds: false, step: 0, steps: 1))
        // In thirds: on from the last step, back from the first.
        #expect(!ComicCurl.allowed(.forward, zoomed: false, thirds: true, step: 1, steps: 3))
        #expect(ComicCurl.allowed(.forward, zoomed: false, thirds: true, step: 2, steps: 3))
        #expect(ComicCurl.allowed(.backward, zoomed: false, thirds: true, step: 0, steps: 3))
        #expect(!ComicCurl.allowed(.backward, zoomed: false, thirds: true, step: 2, steps: 3))
        // A page short enough for one step turns from both edges.
        #expect(ComicCurl.allowed(.forward, zoomed: false, thirds: true, step: 0, steps: 1))
    }

    @Test func onePageAloneHasABackAndASpreadTurnsAsABook() {
        // One leaf at the outer spine: UIKit asks for its back as it turns.
        #expect(ComicCurl.shown(unit: 3, spreads: false) == [.init(unit: 3, side: .front)])
        #expect(ComicCurl.after(.init(unit: 3, side: .front), units: 5) == .init(unit: 3, side: .back))
        #expect(ComicCurl.after(.init(unit: 3, side: .back), units: 5) == .init(unit: 4, side: .front))
        #expect(ComicCurl.after(.init(unit: 4, side: .back), units: 5) == nil)
        #expect(ComicCurl.before(.init(unit: 3, side: .front), units: 5) == .init(unit: 2, side: .back))
        #expect(ComicCurl.before(.init(unit: 0, side: .front), units: 5) == nil)
        #expect(ComicCurl.before(.init(unit: 3, side: .back), units: 5) == .init(unit: 3, side: .front))
        // A spread: its second half's back is the next spread's first half.
        #expect(ComicCurl.shown(unit: 1, spreads: true) == [.init(unit: 1, side: .first), .init(unit: 1, side: .second)])
        #expect(ComicCurl.after(.init(unit: 1, side: .second), units: 3) == .init(unit: 2, side: .first))
        #expect(ComicCurl.after(.init(unit: 2, side: .second), units: 3) == nil)
        #expect(ComicCurl.before(.init(unit: 1, side: .first), units: 3) == .init(unit: 0, side: .second))
        #expect(ComicCurl.unit(showing: ComicCurl.shown(unit: 2, spreads: true)) == 2)
    }

    @Test func aLeafDrawsItsHalfOfTheSpreadReadingEitherWay() {
        #expect(ComicCurl.span(.front, rtl: false) == 0...1)
        #expect(ComicCurl.span(.back, rtl: true) == 0...1)
        #expect(ComicCurl.span(.first, rtl: false) == 0...0.5)
        #expect(ComicCurl.span(.second, rtl: false) == 0.5...1)
        // Right to left the first half is on the right.
        #expect(ComicCurl.span(.first, rtl: true) == 0.5...1)
        #expect(ComicCurl.span(.second, rtl: true) == 0...0.5)
    }
}
