import Foundation
import Testing
@testable import HubKit

/// Where the comic reader puts a page (#25, phase 3): two pages side by side
/// in a wide window held sideways, and the fit, thirds, kept zoom, pans and
/// pinches Android's reader works out in its screen.
struct ComicLayoutTests {
    private func portrait(_ index: Int) -> PageDimension { PageDimension(index: index, width: 1_200, height: 1_800) }
    private func near(_ a: Double, _ b: Double, _ tolerance: Double = 1e-6) -> Bool { abs(a - b) < tolerance }

    @Test func twoPagesStandSideBySideOnlyInAWideWindowHeldSideways() {
        #expect(ComicSpreads.shown(width: 1_366, height: 1_024, wide: true))
        #expect(ComicSpreads.shown(width: 1_280, height: 1_000, wide: true))
        #expect(!ComicSpreads.shown(width: 1_024, height: 1_366, wide: true))
        #expect(!ComicSpreads.shown(width: 1_180, height: 1_000, wide: true))
        // A phone turned sideways is not a wide window.
        #expect(!ComicSpreads.shown(width: 956, height: 440, wide: false))
        #expect(!ComicSpreads.shown(width: 0, height: 0, wide: true))
    }

    @Test func aPageAtATimeIsAUnitPerPage() {
        let units = ComicUnits.single(pageCount: 3)
        #expect(units.units.map(\.reading) == [[0], [1], [2]])
        #expect(units.unit(containing: 2) == 2)
        #expect(units.unit(containing: 9) == 2)
        #expect(units.units[1].place == 1)
        #expect(units.slotCount == 3)
        #expect(units.wantedPages(around: 1, forward: true) == [1, 2, 0])
        #expect(ComicUnits.single(pageCount: 0).units.count == 1)
    }

    @Test func spreadsPairThePagesAfterTheCoverAndKeepTheLastShownAsThePlace() {
        let units = ComicUnits.spreads((0...4).map(portrait), pageCount: 5, direction: .ltr)
        #expect(units.units.map(\.onScreen) == [[0], [1, 2], [3, 4]])
        #expect(units.unit(containing: 2) == 1)
        #expect(units.unit(containing: 4) == 2)
        // The last page of the last spread finishes the issue, and opens it again on that spread.
        #expect(units.units[2].place == 4)
        #expect(units.unit(containing: units.units[2].place) == 2)
        #expect(units.widest == 2)
        #expect(units.slotCount == 6)
        // Decoded ahead: the spread shown, the next one, then the one before.
        #expect(units.wantedPages(around: 1, forward: true) == [1, 2, 3, 4, 0])
        #expect(units.wantedPages(around: 1, forward: false) == [1, 2, 0, 3, 4])
    }

    @Test func mangaSpreadsStandRightToLeftAndUnknownSizesPairAsUpright() {
        let rtl = ComicUnits.spreads((0...2).map(portrait), pageCount: 3, direction: .rtl)
        #expect(rtl.units[1].onScreen == [2, 1])
        #expect(rtl.units[1].reading == [1, 2])
        // Sizes the hub did not give count as upright pages.
        let unknown = ComicUnits.spreads([], pageCount: 4, direction: .ltr)
        #expect(unknown.units.map(\.reading) == [[0], [1, 2], [3]])
        // A wide page stays alone.
        let wide = ComicUnits.spreads([portrait(0), portrait(1), PageDimension(index: 2, width: 2_400, height: 1_400),
                                       portrait(3), portrait(4)], pageCount: 5, direction: .ltr)
        #expect(wide.units.map(\.reading) == [[0], [1], [2], [3, 4]])
    }

    @Test func aSpreadsPagesStandAtOneHeightAndItsContentRunsAcrossBoth() {
        let pair = ComicUnitLayout.of([
            .init(page: 1, width: 1_000, height: 1_500, content: PageContent(left: 0.05, top: 0.04, right: 0.95, bottom: 0.96)),
            .init(page: 2, width: 800, height: 1_000),
        ])
        #expect(pair.height == 1_500)
        #expect(pair.width == 2_200)
        #expect(pair.placed.map(\.x) == [0, 1_000])
        #expect(pair.placed[1].width == 1_200)
        #expect(near(pair.content.left, 50 / 2_200.0))
        #expect(near(pair.content.right, 1))
        #expect(near(pair.content.top, 0))
        #expect(near(pair.content.bottom, 1))
        let single = ComicUnitLayout.of([.init(page: 0, width: 1_000, height: 1_500,
                                               content: PageContent(left: 0.05, top: 0.04, right: 0.95, bottom: 0.96))])
        #expect(near(single.content.left, 0.05) && near(single.content.top, 0.04))
        #expect(near(single.content.right, 0.95) && near(single.content.bottom, 0.96))
        // A page whose size is not known yet lays out nothing.
        #expect(ComicUnitLayout.of([.init(page: 0, width: 0, height: 0)]).placed.isEmpty)
    }

    /// A US comic page on the Pocket's screen, a point to a pixel.
    private let comic = ComicFrame(width: 1_988, height: 3_056, viewWidth: 1_920, viewHeight: 1_080, screenScale: 1)

    @Test func theFitsScalesAndTheLimits() {
        #expect(near(comic.minScale, 1_080 / 3_056.0))
        #expect(near(comic.fitWidthScale, 1_920 / 1_988.0))
        #expect(near(comic.wholeScale, comic.minScale))
        #expect(comic.maxScale == 6)
        #expect(comic.steps.count == 3)
        #expect(near(comic.baseScale(.whole), comic.minScale))
        #expect(near(comic.baseScale(.thirds), comic.fitWidthScale))
    }

    @Test func thirdsPlaceEachStepAcrossTheWidthTopToBottom() {
        let visible = 1_080 / comic.fitWidthScale
        let first = comic.placement(fit: .thirds, step: 0, atEnd: false, zoom: ComicZoom())
        #expect(near(first.scale, comic.fitWidthScale))
        #expect(near(first.x, 994))
        #expect(near(first.y, visible / 2))
        let last = comic.placement(fit: .thirds, step: 2, atEnd: false, zoom: ComicZoom())
        #expect(near(last.y, 3_056 - visible / 2))
        // Reached going back, a page opens on its last step; a step past the last is the last.
        #expect(comic.placement(fit: .thirds, step: 0, atEnd: true, zoom: ComicZoom()) == last)
        #expect(comic.placement(fit: .thirds, step: 9, atEnd: false, zoom: ComicZoom()) == last)
        #expect(comic.nearestStep(ComicCamera(scale: first.scale, x: 994, y: 1_528)) == 1)
        let shown = comic.visible(first)
        #expect(near(shown.left, 0) && near(shown.top, 0) && near(shown.right, 1))
        #expect(near(shown.bottom, visible / 3_056))
    }

    @Test func aWholePageIsCentredAndTheWidthOpensAtItsTopOrGoingBackItsFoot() {
        let whole = comic.placement(fit: .whole, step: 0, atEnd: false, zoom: ComicZoom())
        #expect(near(whole.scale, comic.minScale))
        #expect(near(whole.x, 994) && near(whole.y, 1_528))
        let visible = 1_080 / comic.fitWidthScale
        #expect(near(comic.placement(fit: .width, step: 0, atEnd: false, zoom: ComicZoom()).y, visible / 2))
        #expect(near(comic.placement(fit: .width, step: 0, atEnd: true, zoom: ComicZoom()).y, 3_056 - visible / 2))
    }

    @Test func aTrimmedPageFitsAndStepsByItsContent() {
        let trimmed = ComicFrame(width: 1_988, height: 3_056, viewWidth: 1_920, viewHeight: 1_080,
                                 content: PageContent(left: 0.05, top: 0.04, right: 0.95, bottom: 0.96), screenScale: 1)
        #expect(near(trimmed.fitWidthScale, 1_920 / (1_988 * 0.9)))
        #expect(near(trimmed.wholeScale, 1_080 / (3_056 * 0.92)))
        // The first step starts at the content's top, not the paper's.
        let first = trimmed.placement(fit: .thirds, step: 0, atEnd: false, zoom: ComicZoom())
        #expect(near(first.y - 1_080 / first.scale / 2, 0.04 * 3_056))
        #expect(near(first.x, 994))
    }

    @Test func aKeptZoomOpensTheNextPageAtTheSamePlaceAcrossAtItsTop() {
        let zoom = ComicZoom(factor: 1.5, anchorX: 0.7)
        let camera = comic.placement(fit: .thirds, step: 0, atEnd: false, zoom: zoom)
        #expect(near(camera.scale, comic.fitWidthScale * 1.5))
        let visibleWidth = 1_920 / camera.scale
        let visibleHeight = 1_080 / camera.scale
        // 70% across is past where the view can go: as far right as it can.
        #expect(near(camera.x, 1_988 - visibleWidth / 2))
        #expect(near(camera.y, visibleHeight / 2))
        #expect(near(comic.placement(fit: .thirds, step: 0, atEnd: true, zoom: zoom).y, 3_056 - visibleHeight / 2))
    }

    @Test func thePageNeverPullsAwayFromTheViewsEdges() {
        let fit = comic.fitWidthScale
        let visible = 1_080 / fit
        let clamped = comic.clamp(ComicCamera(scale: fit, x: 0, y: 0))
        #expect(near(clamped.x, 994) && near(clamped.y, visible / 2))
        // Smaller than the whole page fits is the whole page, centred.
        let small = comic.clamp(ComicCamera(scale: 0.01, x: 10, y: 10))
        #expect(near(small.scale, comic.minScale) && near(small.x, 994) && near(small.y, 1_528))
        #expect(comic.clamp(ComicCamera(scale: 100, x: 994, y: 1_528)).scale == comic.maxScale)
    }

    @Test func theDPadPansAScreenAtATimeAndAZoomedSpreadReadsRowByRow() {
        let first = comic.placement(fit: .width, step: 0, atEnd: false, zoom: ComicZoom())
        let visible = 1_080 / first.scale
        let down = comic.pan(.down, from: first, zoomed: false)
        #expect(down.map { near($0.y, visible / 2 + visible * 0.82) } == true)
        // At its width a page has nowhere to go across.
        #expect(comic.pan(.right, from: first, zoomed: false) == nil)
        #expect(comic.readingFlow(forward: true, rtl: false, from: first, zoomed: false) == down)

        let spread = ComicFrame(width: 3_976, height: 3_056, viewWidth: 1_920, viewHeight: 1_080, screenScale: 1)
        let start = ComicCamera(scale: 1, x: 960, y: 540)
        #expect(spread.readingFlow(forward: true, rtl: false, from: start, zoomed: true)
                .map { near($0.x, 960 + 1_920 * 0.82) && near($0.y, 540) } == true)
        // At the end of a row: the start of the next.
        let rowEnd = ComicCamera(scale: 1, x: 3_976 - 960, y: 540)
        let next = spread.readingFlow(forward: true, rtl: false, from: rowEnd, zoomed: true)
        #expect(next.map { near($0.x, 960) && near($0.y, 540 + 1_080 * 0.82) } == true)
        // Right to left the next row starts at the right.
        let rtlRowEnd = ComicCamera(scale: 1, x: 960, y: 540)
        #expect(spread.readingFlow(forward: true, rtl: true, from: rtlRowEnd, zoomed: true)
                .map { near($0.x, 3_976 - 960) && near($0.y, 540 + 1_080 * 0.82) } == true)
        // At the very end there is nowhere left: the page turns.
        #expect(spread.readingFlow(forward: true, rtl: false, from: ComicCamera(scale: 1, x: 3_016, y: 2_516),
                                   zoomed: true) == nil)
    }

    @Test func aPinchKeepsWhatIsUnderTheFingersAndADragFollowsTheFinger() {
        let spread = ComicFrame(width: 3_976, height: 3_056, viewWidth: 1_920, viewHeight: 1_080, screenScale: 1)
        let start = ComicCamera(scale: 1, x: 960, y: 540)
        let pinched = spread.pinched(start, magnification: 2, anchorX: 1_920, anchorY: 1_080)
        #expect(near(pinched.scale, 2) && near(pinched.x, 1_440) && near(pinched.y, 810))
        let held = spread.point(atX: 1_920, y: 1_080, camera: pinched)
        #expect(near(held.x, 1_920) && near(held.y, 1_080))
        // A finger moving left and up brings in what is right of and below the view.
        let dragged = spread.dragged(start, dx: -100, dy: -50)
        #expect(near(dragged.x, 1_060) && near(dragged.y, 590))
        // Not past the unit's left edge, where the view already is.
        #expect(near(spread.dragged(start, dx: 100, dy: 0).x, 960))
        let glided = spread.glide(dx: 0.5, dy: 0, from: start)
        #expect(near(glided.x, 960 + 0.5 * ComicFrame.glideRate * 1_920))
        let closer = spread.zoomed(by: 1.2, from: start)
        #expect(near(closer.scale, 1.2) && near(closer.x, 960) && near(closer.y, 540))
    }

    @Test func aDoubleTapLooksCloserRoundThePointTapped() {
        let fit = comic.placement(fit: .thirds, step: 0, atEnd: false, zoom: ComicZoom())
        let closer = comic.doubleTapped(atX: 960, y: 540, camera: fit, fit: .thirds)
        #expect(near(closer.scale, comic.fitWidthScale * ComicFrame.doubleTapZoom))
        #expect(near(closer.x, fit.x) && near(closer.y, fit.y))
        #expect(comic.isZoomed(closer, fit: .thirds))
        #expect(!comic.isZoomed(fit, fit: .thirds))
        // The zoom that keeps it for the next page.
        let zoom = ComicZoom.of(scale: closer.scale, base: comic.baseScale(.thirds), centerX: closer.x, pageWidth: 1_988)
        #expect(zoom.active && near(zoom.factor, ComicFrame.doubleTapZoom))
    }
}
