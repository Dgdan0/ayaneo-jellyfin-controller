import Foundation
import Testing
@testable import HubKit

/// Android's `PagedImageStateTest`, case for case (#25, phase 3): spreads,
/// thirds, the place in an issue, how a series reads, the kept zoom, the end
/// of an issue, panning and the reader's words.
struct ComicPagesTests {
    private func portrait(_ index: Int) -> PageDimension { PageDimension(index: index, width: 1_200, height: 1_800) }

    @Test func coverIsIsolatedAndLeftToRightPairsReadLeftToRight() {
        let spreads = SpreadPlanner.plan((0...4).map(portrait), direction: .ltr)
        #expect(spreads[0].readingOrder == [0])
        #expect(spreads[1].readingOrder == [1, 2])
        #expect(spreads[1].leftPage == 1)
        #expect(spreads[1].rightPage == 2)
        #expect(spreads[2].readingOrder == [3, 4])
    }

    @Test func mangaPairsReadRightToLeftWithoutSwappingTheirPages() {
        let spreads = SpreadPlanner.plan((0...4).map(portrait), direction: .rtl)
        #expect(spreads[1].leftPage == 2)
        #expect(spreads[1].rightPage == 1)
        #expect(spreads[1].readingOrder == [1, 2])
    }

    @Test func widePagesStayAloneAndDoNotStealANeighbour() {
        let pages = [portrait(0), portrait(1), PageDimension(index: 2, width: 2_400, height: 1_400, isWide: true),
                     portrait(3), portrait(4)]
        #expect(SpreadPlanner.plan(pages, direction: .ltr).map(\.readingOrder) == [[0], [1], [2], [3, 4]])
    }

    @Test func thirdsComeFromThePagesShapeAndTheScreens() {
        // The Pocket's 1920 x 1080 top screen.
        #expect(ViewportStepPlanner.count(pageWidth: 1_988, pageHeight: 3_056, viewWidth: 1_920, viewHeight: 1_080) == 3)
        #expect(ViewportStepPlanner.count(pageWidth: 1_400, pageHeight: 2_000, viewWidth: 1_920, viewHeight: 1_080) == 3)
        #expect(ViewportStepPlanner.count(pageWidth: 3_976, pageHeight: 3_056, viewWidth: 1_920, viewHeight: 1_080) == 2)
        #expect(ViewportStepPlanner.count(pageWidth: 4_000, pageHeight: 1_500, viewWidth: 1_920, viewHeight: 1_080) == 1)
        #expect(ViewportStepPlanner.count(pageWidth: 0, pageHeight: 0, viewWidth: 1_920, viewHeight: 1_080) == 1)
        // A long strip keeps going, a screen at a time less the overlap, up to a limit.
        #expect(ViewportStepPlanner.count(pageWidth: 800, pageHeight: 400_000, viewWidth: 1_920, viewHeight: 1_080)
                == ViewportStepPlanner.maxSteps)
    }

    @Test func thirdsFitTheWidthAndRunTopToBottomOverlappingNeverCropped() {
        let steps = ViewportStepPlanner.fitWidth(pageWidth: 1_988, pageHeight: 3_056, viewWidth: 1_920, viewHeight: 1_080)
        #expect(steps.count == 3)
        for step in steps {
            #expect(abs(step.left) < 0.0001)
            #expect(abs(step.right - 1) < 0.0001)
        }
        #expect(abs(steps[0].top) < 0.0001)
        #expect(abs(steps[2].bottom - 1) < 0.0001)
        // Each shows the screen's share of the page at its width, and a little of the step before.
        let visible = 1_080.0 * 1_988 / 1_920 / 3_056
        for step in steps { #expect(abs(step.bottom - step.top - visible) < 0.0001) }
        #expect(steps[0].bottom - steps[1].top >= visible * ViewportStepPlanner.overlap - 0.0001)
        #expect(ViewportStepPlanner.fitWidth(pageWidth: 4_000, pageHeight: 1_500, viewWidth: 1_920, viewHeight: 1_080)
                == [NormalizedViewport(left: 0, top: 0, right: 1, bottom: 1)])
    }

    @Test func eachPageTakesItsOwnStepsAndGoingBackLandsOnTheLast() {
        // Page 1 is a spread (2 steps), the others comic pages (3).
        var state = PagedImageState(pageCount: 3, startPage: 0, stepsFor: { $0 == 1 ? 2 : 3 })
        state.advance(); state.advance()
        #expect(state.pageIndex == 0 && state.viewportIndex == 2)
        state.advance()
        #expect(state.pageIndex == 1 && state.viewportIndex == 0)
        #expect(state.viewportSteps == 2)
        state.advance(); state.advance()
        #expect(state.pageIndex == 2 && state.viewportIndex == 0)
        state.retreat()
        #expect(state.pageIndex == 1 && state.viewportIndex == 1)
        let moved1 = state.turnPage(-1)
        #expect(moved1)
        #expect(state.pageIndex == 0 && state.viewportIndex == 2)
    }

    @Test func aSavedStepComesBackAndAPageThatChangesSizeKeepsTheStepInsideIt() {
        final class Shape { var spread = false }
        let shape = Shape()
        var state = PagedImageState(pageCount: 4, startPage: 2, stepsFor: { _ in shape.spread ? 2 : 3 }, startStep: 2)
        #expect(state.viewportIndex == 2)
        // It decoded as a spread: two steps, so the third becomes the second.
        shape.spread = true
        state.refit()
        #expect(state.viewportIndex == 1)
        state.jump(page: 3, step: 7)
        #expect(state.pageIndex == 3 && state.viewportIndex == 1)
        // A start step past the page's last is its last.
        #expect(PagedImageState(pageCount: 2, startPage: 1, stepsFor: { _ in 3 }, startStep: .max).viewportIndex == 2)
    }

    @Test func aSeriesKeepsItsFitAndDirectionAndTheDefaultCoversTheRest() {
        #expect(ComicView.decode(nil, fallback: ComicView.defaultFit) == ComicView(.thirds))
        #expect(ComicView.decode(nil, fallback: .whole) == ComicView(.whole))
        let chosen = ComicView(.width, direction: "rtl")
        #expect(ComicView.decode(chosen.encode(), fallback: .thirds) == chosen)
        #expect(ComicView.decode(ComicView(.whole).encode(), fallback: .thirds) == ComicView(.whole, direction: nil))
        // Something unreadable falls back rather than breaking the reader.
        #expect(ComicView.decode("sideways|up", fallback: .thirds) == ComicView(.thirds, direction: nil))
    }

    @Test func trimmingTheMarginsIsOnUnlessASeriesTurnsItOffAndOlderSettingsKeepItOn() {
        #expect(ComicView.decode(nil, fallback: .thirds).trim)
        // As #16 stored them.
        #expect(ComicView.decode("width|rtl", fallback: .thirds).trim)
        #expect(ComicView.decode("thirds|", fallback: .thirds).trim)
        let off = ComicView(.thirds, direction: "rtl", trim: false)
        #expect(ComicView.decode(off.encode(), fallback: .whole) == off)
        #expect(ComicView.decode(ComicView(.whole, trim: false).encode(), fallback: .thirds)
                == ComicView(.whole, direction: nil, trim: false))
        // On is written as #16 wrote it.
        #expect(ComicView(.width, direction: "rtl").encode() == "width|rtl")
        #expect(ComicFit.allCases.map(\.label) == ["Whole page", "Fit page width", "Read in thirds"])
    }

    @Test func theThirdYouWereOnComesBackOnlyOnTheSamePageOfTheSameIssue() {
        let place = ComicPlace("kavita:51", page: 4, step: 2)
        #expect(ComicPlace.decode(place.encode()) == place)
        #expect(place.stepFor("kavita:51", page: 4, steps: 3) == 2)
        #expect(place.stepFor("kavita:51", page: 4, steps: 2) == 1)
        #expect(place.stepFor("kavita:51", page: 5, steps: 3) == 0)
        #expect(place.stepFor("kavita:52", page: 4, steps: 3) == 0)
        #expect(ComicPlace.decode("kavita:51|four|2") == nil)
        #expect(ComicPlace.decode(nil) == nil)
        #expect(ComicPlace.decode("|4|2") == nil)
        #expect(ComicPlace.decode("kavita:51|-1|2") == nil)
    }

    @Test func aZoomStaysForTheNextPageAtTheSamePlaceAcrossAndAtItsTop() {
        // Zoomed to 1.5 times the fit, the middle of the view 70% across a 2000-wide page.
        let zoom = ComicZoom.of(scale: 1.5, base: 1, centerX: 1_400, pageWidth: 2_000)
        #expect(zoom.active)
        #expect(abs(zoom.factor - 1.5) < 0.001)
        #expect(abs(zoom.anchorX - 0.7) < 0.001)
        #expect(abs(zoom.scaleFor(base: 0.8, min: 0.5, max: 6) - 1.2) < 0.001)
        // The next page, 2000 x 3000, of which 1280 x 720 shows: 70% across, at its top.
        let center = zoom.center(pageWidth: 2_000, pageHeight: 3_000, visibleWidth: 1_280, visibleHeight: 720, atEnd: false)
        #expect(abs(center.x - 1_360) < 0.5)   // as far right as the view can go
        #expect(abs(center.y - 360) < 0.5)
        // Going back it opens at the bottom; a page narrower than the view is centred.
        #expect(abs(zoom.center(pageWidth: 2_000, pageHeight: 3_000, visibleWidth: 1_280, visibleHeight: 720, atEnd: true).y
                    - 2_640) < 0.5)
        #expect(abs(zoom.center(pageWidth: 1_000, pageHeight: 3_000, visibleWidth: 1_280, visibleHeight: 720, atEnd: false).x
                    - 500) < 0.5)
        // Pinched back to the fit, the zoom ends.
        #expect(!ComicZoom.of(scale: 1.01, base: 1, centerX: 900, pageWidth: 2_000).active)
    }

    @Test func theEndCardNamesTheIssueAndWhatComesNext() {
        #expect(EndOfIssue.heading(series: "Fantastic Four", title: "Chapter 51", number: "51") == "End of Fantastic Four #51")
        #expect(EndOfIssue.heading(series: "Fantastic Four", title: "Annual 1965", number: "1")
                == "End of Fantastic Four · Annual 1965")
        #expect(EndOfIssue.heading(series: "Saga", title: "7", number: "") == "End of Saga #7")
        #expect(EndOfIssue.next(currentSeries: "Fantastic Four", nextSeries: "Fantastic Four", nextTitle: "Chapter 52",
                                nextNumber: "52", readingList: false) == "Next: #52")
        // A reading list passing to another series names it.
        #expect(EndOfIssue.next(currentSeries: "Fantastic Four", nextSeries: "Spider-Man", nextTitle: "Issue 1",
                                nextNumber: "", readingList: true) == "Next: Spider-Man #1")
        #expect(EndOfIssue.next(currentSeries: "Fantastic Four", nextSeries: nil, nextTitle: "", nextNumber: "",
                                readingList: false) == "That was the last issue")
        #expect(EndOfIssue.next(currentSeries: "Fantastic Four", nextSeries: nil, nextTitle: "", nextNumber: "",
                                readingList: true) == "End of the reading list")
    }

    @Test func pagedNavigationConsumesViewportStepsBeforeChangingPage() {
        var state = PagedImageState(pageCount: 3, startPage: 1, viewportSteps: 3)
        let moved2 = state.advance()
        #expect(moved2)
        #expect(state.pageIndex == 1 && state.viewportIndex == 1)
        let moved3 = state.advance()
        #expect(moved3)
        #expect(state.viewportIndex == 2)
        let moved4 = state.advance()
        #expect(moved4)
        #expect(state.pageIndex == 2 && state.viewportIndex == 0)
        let moved5 = state.advance()
        #expect(moved5)
        let moved6 = state.advance()
        #expect(moved6)
        let moved7 = state.advance()
        #expect(!moved7)
        let moved8 = state.retreat()
        #expect(moved8)
        #expect(state.pageIndex == 2 && state.viewportIndex == 1)
        let moved9 = state.retreat()
        #expect(moved9)
        let moved10 = state.retreat()
        #expect(moved10)
        #expect(state.pageIndex == 1 && state.viewportIndex == 2)
    }

    @Test func wholePageTurnsSkipThirdsAndLandAtTheReadingEdge() {
        var state = PagedImageState(pageCount: 4, startPage: 1, viewportSteps: 3)
        state.advance()
        let moved11 = state.turnPage(1)
        #expect(moved11)
        #expect(state.pageIndex == 2 && state.viewportIndex == 0)
        let moved12 = state.turnPage(-1)
        #expect(moved12)
        #expect(state.pageIndex == 1 && state.viewportIndex == 2)
        state.seek(0)
        let moved13 = state.turnPage(-1)
        #expect(!moved13)
        #expect(state.pageIndex == 0)
        let moved14 = state.turnPage(0)
        #expect(!moved14)
    }

    @Test func controllerPanMovesByAViewportStepAndStopsAtPageEdges() {
        func near(_ value: Double?, _ expected: Double) -> Bool { value.map { abs($0 - expected) < 0.01 } ?? false }
        #expect(near(ComicPanPolicy.step(center: 500, sourceSize: 2_000, visibleSize: 1_000, direction: 1), 1_320))
        #expect(near(ComicPanPolicy.step(center: 1_400, sourceSize: 2_000, visibleSize: 1_000, direction: 1), 1_500))
        #expect(ComicPanPolicy.step(center: 1_500, sourceSize: 2_000, visibleSize: 1_000, direction: 1) == nil)
        #expect(near(ComicPanPolicy.step(center: 900, sourceSize: 2_000, visibleSize: 1_000, direction: -1), 500))
        // Within the content of a trimmed page: 1800 long from 100.
        #expect(near(ComicPanPolicy.step(center: 1_300, sourceSize: 1_800, visibleSize: 1_000, direction: 1, from: 100), 1_400))
        #expect(near(ComicPanPolicy.edge(sourceSize: 1_800, visibleSize: 1_000, high: false, from: 100), 600))
        #expect(near(ComicPanPolicy.edge(sourceSize: 1_800, visibleSize: 1_000, high: true, from: 100), 1_400))
        #expect(ComicPanPolicy.step(center: 500, sourceSize: 2_000, visibleSize: 1_000, direction: -1) == nil)
        #expect(ComicPanPolicy.step(center: 500, sourceSize: 1_000, visibleSize: 1_200, direction: 1) == nil)
        #expect(near(ComicPanPolicy.edge(sourceSize: 2_000, visibleSize: 1_000, high: false), 500))
        #expect(near(ComicPanPolicy.edge(sourceSize: 2_000, visibleSize: 1_000, high: true), 1_500))
    }

    @Test func pageProgressIsZeroBasedAndReachesCompletionOnTheFinalPage() {
        var state = PagedImageState(pageCount: 5, startPage: 2)
        #expect(abs(state.progression - 0.5) < 0.0001)
        state.seek(4)
        #expect(abs(state.progression - 1) < 0.0001)
        #expect(state.completed)
        state.seek(-50)
        #expect(state.pageIndex == 0)
        // A manifest with no pages still opens on one.
        #expect(PagedImageState(pageCount: 0).pageCount == 1)
    }

    @Test func theReaderTitleDoesNotRepeatIdenticalSeriesAndPublicationNames() {
        #expect(ReaderTitleFormatter.format("Daredevil 000.5", "Daredevil 000.5", fallback: "Fallback") == "Daredevil 000.5")
        #expect(ReaderTitleFormatter.format("Daredevil", "000.5", fallback: "Fallback") == "Daredevil · 000.5")
        #expect(ReaderTitleFormatter.format("", "", fallback: "Fallback") == "Fallback")
    }

    @Test func theControlsFirstFocusTheForwardPageAction() {
        #expect(ReaderControlFocusPolicy.initialIndex(controlCount: 7) == 6)
        #expect(ReaderControlFocusPolicy.initialIndex(controlCount: 0) == nil)
    }

    @Test func theReaderHeadsAComicWithItsSeriesAndNamesTheIssueUnderIt() {
        #expect(ReaderTitleFormatter.heading("Fantastic Four", "Chapter 51", fallback: "Fallback") == "Fantastic Four")
        #expect(ReaderTitleFormatter.issue(kind: "comic", seriesTitle: "Fantastic Four", publicationTitle: "Chapter 51",
                                           number: "51") == "Issue 51")
        #expect(ReaderTitleFormatter.issue(kind: "manga", seriesTitle: "Chainsaw Man", publicationTitle: "11",
                                           number: "11") == "Chapter 11")
        #expect(ReaderTitleFormatter.issue(kind: "comic", seriesTitle: "Fantastic Four", publicationTitle: "Annual 1965",
                                           number: "1") == "Annual 1965")
        #expect(ReaderTitleFormatter.issue(kind: "comic", seriesTitle: "Saga", publicationTitle: "Saga", number: "") == "")
        #expect(ReaderTitleFormatter.subtitle("Issue 51", page: 2, pageCount: 24) == "Issue 51 · Page 2 of 24")
        #expect(ReaderTitleFormatter.subtitle("", page: 2, pageCount: 24, part: 1, parts: 3) == "Page 2 of 24 · Part 1 of 3")
        // A page read whole has no parts.
        #expect(ReaderTitleFormatter.subtitle("", page: 2, pageCount: 24, part: 1, parts: 1) == "Page 2 of 24")
    }

    @Test func anIssuesCoverIsKavitasChapterCoverAndNothingElseIsGuessed() {
        #expect(IssueCover.path("kavita-chapter:34") == "/v1/img/reading/kavita-chapter/34")
        // The hub names a Kavita issue by its bare chapter id.
        #expect(IssueCover.path("8358") == "/v1/img/reading/kavita-chapter/8358")
        #expect(IssueCover.path("storyteller:abc") == nil)
        #expect(IssueCover.path("kavita-chapter:") == nil)
        #expect(IssueCover.path("kavita-chapter:12/../x") == nil)
        #expect(IssueCover.path("issue-51") == nil)
        #expect(IssueCover.path("") == nil)
    }

    @Test func aTapOnThePageMapGoesToTheStepUnderIt() {
        let steps = ViewportStepPlanner.fitWidth(pageWidth: 1_988, pageHeight: 3_056, viewWidth: 1_920, viewHeight: 1_080)
        #expect(ComicPageMap.step(at: 0.1, steps: steps) == 0)
        // Where two steps overlap, the first.
        #expect(ComicPageMap.step(at: 0.35, steps: steps) == 0)
        #expect(ComicPageMap.step(at: 0.5, steps: steps) == 1)
        #expect(ComicPageMap.step(at: 0.95, steps: steps) == 2)
        #expect(ComicPageMap.step(at: 2, steps: steps) == 2)
        #expect(ComicPageMap.step(at: 0.5, steps: [.whole]) == nil)
    }
}

/// Android's `ComicTouchTest`: a finger on a comic's page does what the keys do (#18, C7).
struct ComicTouchTests {
    @Test func theOuterThirdsReadOnAndBackAndTheMiddleShowsTheControls() {
        #expect(ComicTouch.tap(x: 100, width: 1_920, rtl: false, controlsVisible: false) == .back)
        #expect(ComicTouch.tap(x: 960, width: 1_920, rtl: false, controlsVisible: false) == .controls)
        #expect(ComicTouch.tap(x: 1_800, width: 1_920, rtl: false, controlsVisible: false) == .forward)
        // Exactly on a line between thirds is the middle's.
        #expect(ComicTouch.tap(x: 640, width: 1_920, rtl: false, controlsVisible: false) == .controls)
    }

    @Test func rightToLeftTheSidesSwap() {
        #expect(ComicTouch.tap(x: 100, width: 1_920, rtl: true, controlsVisible: false) == .forward)
        #expect(ComicTouch.tap(x: 1_800, width: 1_920, rtl: true, controlsVisible: false) == .back)
    }

    @Test func withTheControlsShowingAnyTapOnThePageHidesThem() {
        #expect(ComicTouch.tap(x: 100, width: 1_920, rtl: false, controlsVisible: true) == .controls)
        #expect(ComicTouch.tap(x: 1_800, width: 1_920, rtl: false, controlsVisible: true) == .controls)
    }

    @Test func aSwipeAcrossTurnsThePageTheWayYouRead() {
        #expect(ComicTouch.swipe(dx: -600, dy: 40, width: 1_920, rtl: false, zoomed: false, pinched: false) == 1)
        #expect(ComicTouch.swipe(dx: 600, dy: 40, width: 1_920, rtl: false, zoomed: false, pinched: false) == -1)
        #expect(ComicTouch.swipe(dx: -600, dy: 40, width: 1_920, rtl: true, zoomed: false, pinched: false) == -1)
        #expect(ComicTouch.swipe(dx: 600, dy: 40, width: 1_920, rtl: true, zoomed: false, pinched: false) == 1)
    }

    @Test func aZoomedPageAPinchAShortOrAMostlyVerticalSwipeTurnNothing() {
        #expect(ComicTouch.swipe(dx: -600, dy: 40, width: 1_920, rtl: false, zoomed: true, pinched: false) == 0)
        #expect(ComicTouch.swipe(dx: -600, dy: 40, width: 1_920, rtl: false, zoomed: false, pinched: true) == 0)
        #expect(ComicTouch.swipe(dx: -150, dy: 0, width: 1_920, rtl: false, zoomed: false, pinched: false) == 0)
        #expect(ComicTouch.swipe(dx: -600, dy: 500, width: 1_920, rtl: false, zoomed: false, pinched: false) == 0)
        #expect(ComicTouch.swipe(dx: -600, dy: 0, width: 0, rtl: false, zoomed: false, pinched: false) == 0)
    }
}

/// Android's `PageSlotsTest`: what stays decoded round the page you are on (#16, C3).
struct PageSlotsTests {
    private func key(_ page: Int, _ publication: String = "issue-51") -> PageKey { PageKey(publication, page) }

    @Test func thePageTheNextTheWayYouReadThenTheOneBefore() {
        #expect(PageSlots.wanted(current: 4, pageCount: 20) == [4, 5, 3])
        #expect(PageSlots.wanted(current: 4, pageCount: 20, forward: false) == [4, 3, 5])
        // At either end there is only one neighbour.
        #expect(PageSlots.wanted(current: 0, pageCount: 20) == [0, 1])
        #expect(PageSlots.wanted(current: 19, pageCount: 20) == [19, 18])
        #expect(PageSlots.wanted(current: 0, pageCount: 1) == [0])
        #expect(PageSlots.wanted(current: 4, pageCount: 20, count: 2) == [4, 5])
    }

    @Test func aTurnKeepsWhatIsDecodedAndLoadsOnlyTheNewNeighbour() {
        // On page 4, holding 4, 5 and 3; turn to 5: 4 stays as the page before, 5 is already there.
        let held: [PageKey?] = [key(4), key(5), key(3)]
        let next = PageSlots.assign(held: held, wanted: PageSlots.wanted(current: 5, pageCount: 20).map { key($0) })
        #expect(next == [key(4), key(5), key(6)])
    }

    @Test func turningBackFindsThePageBeforeStillDecoded() {
        let held: [PageKey?] = [key(4), key(5), key(6)]
        let next = PageSlots.assign(held: held,
                                    wanted: PageSlots.wanted(current: 4, pageCount: 20, forward: false).map { key($0) })
        #expect(next == [key(4), key(5), key(3)])
    }

    @Test func aJumpGivesEverySlotButTheWantedOnesAway() {
        let held: [PageKey?] = [key(4), key(5), key(3)]
        #expect(PageSlots.assign(held: held, wanted: PageSlots.wanted(current: 12, pageCount: 20).map { key($0) })
                == [key(12), key(13), key(11)])
    }

    @Test func anotherIssuesPagesAreNeverTakenForThisOnes() {
        let held: [PageKey?] = [key(2, "issue-52"), nil, key(1)]
        #expect(PageSlots.assign(held: held, wanted: [key(0), key(1)]) == [key(0), nil, key(1)])
    }

    @Test func aPageHeldTwiceIsKeptOnce() {
        let held: [PageKey?] = [key(4), key(4), nil]
        #expect(PageSlots.assign(held: held, wanted: [key(4), key(5), key(3)]) == [key(4), key(5), key(3)])
    }
}

/// Android's `PageGridTest`: the Pages grid's arithmetic (#16, C4).
struct PageGridTests {
    @Test func sevenCellsAcrossThePocketNeverFewerThanThree() {
        #expect(PageGrid.columns(width: 853) == 7)
        #expect(PageGrid.columns(width: 200) == 3)
        #expect(PageGrid.columns(width: 3_000) == 9)
    }

    @Test func leftAndRightRunOnAcrossRowsUpAndDownKeepTheColumn() {
        // 24 pages, 7 across: rows 0-6, 7-13, 14-20, 21-23.
        #expect(PageGrid.move(6, .right, columns: 7, count: 24) == 7)
        #expect(PageGrid.move(7, .left, columns: 7, count: 24) == 6)
        #expect(PageGrid.move(3, .down, columns: 7, count: 24) == 10)
        #expect(PageGrid.move(10, .up, columns: 7, count: 24) == 3)
        // The edges hold.
        #expect(PageGrid.move(0, .left, columns: 7, count: 24) == 0)
        #expect(PageGrid.move(23, .right, columns: 7, count: 24) == 23)
        #expect(PageGrid.move(4, .up, columns: 7, count: 24) == 4)
        // Down from a column the short last row lacks lands on its last page.
        #expect(PageGrid.move(19, .down, columns: 7, count: 24) == 23)
        // On the last row, down holds.
        #expect(PageGrid.move(22, .down, columns: 7, count: 24) == 22)
        #expect(PageGrid.move(5, .up, columns: 7, count: 0) == 0)
    }

    @Test func theTriggersMoveAScreenfulOfRows() {
        #expect(PageGrid.page(3, delta: 1, columns: 7, rows: 2, count: 40) == 17)
        #expect(PageGrid.page(17, delta: -1, columns: 7, rows: 2, count: 40) == 3)
        #expect(PageGrid.page(30, delta: 1, columns: 7, rows: 2, count: 40) == 39)
        #expect(PageGrid.page(12, delta: -1, columns: 7, rows: 2, count: 40) == 5)
    }

    @Test func theGridNamesItsOwnKeys() {
        #expect(PageGrid.hints.map(\.label) == ["Open page", "Close", "Earlier pages", "Later pages"])
        #expect(PageGrid.hints.first?.action == .activate)
    }
}

/// Android's `PageBoundsTest`: trimming a comic page's paper border on the
/// hub's small thumbnail (#18, C5).
struct PageBoundsTests {
    /// The same numbers every run (SplitMix64), as Kotlin's `Random(18)` is.
    final class Seeded {
        private var state: UInt64

        init(_ seed: UInt64) { state = seed }

        func next(_ range: Range<Int>) -> Int {
            state &+= 0x9E37_79B9_7F4A_7C15
            var z = state
            z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
            z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
            z ^= z >> 31
            return range.lowerBound + Int(z % UInt64(range.count))
        }
    }

    private let random = Seeded(18)

    /// A thumbnail `width` wide and `height` tall: paper of `paper` luma with
    /// a little grain, and art inside the given margins (in pixels), busy
    /// enough that no line of it passes for paper.
    private func page(_ width: Int, _ height: Int, left: Int, top: Int, right: Int, bottom: Int, paper: Int = 238) -> [Int] {
        (0..<(width * height)).map { i in
            let x = i % width, y = i / width
            if x < left || x >= width - right || y < top || y >= height - bottom {
                return min(255, max(0, paper + random.next(-6..<7)))
            }
            return (x / 3 + y / 5) % 2 == 0 ? 40 + random.next(0..<30) : 150 + random.next(0..<60)
        }
    }

    private func near(_ a: Double, _ b: Double) -> Bool { abs(a - b) < 1e-9 }

    @Test func aWhiteBorderIsFoundOnEverySideOnePixelBackFromTheArt() {
        let content = PageBounds.detect(page(96, 148, left: 5, top: 6, right: 4, bottom: 7), width: 96, height: 148)
        #expect(near(content.left, 4 / 96.0))
        #expect(near(content.top, 5 / 148.0))
        #expect(near(content.right, 1 - 3 / 96.0))
        #expect(near(content.bottom, 1 - 6 / 148.0))
        #expect(content.trimmed)
    }

    @Test func artToTheEdgeIsLeftWhole() {
        #expect(PageBounds.detect(page(96, 148, left: 0, top: 0, right: 0, bottom: 0), width: 96, height: 148) == .whole)
        // Bled on the left and right only: the top and bottom margins still go.
        let content = PageBounds.detect(page(96, 148, left: 0, top: 8, right: 0, bottom: 8), width: 96, height: 148)
        #expect(near(content.left, 0))
        #expect(near(content.right, 1))
        #expect(near(content.top, 7 / 148.0))
    }

    @Test func aFlatColourAtAnEdgeIsArtNotPaper() {
        // A red sky across the top and a mid-grey floor across the foot: no border to trim.
        var pixels = page(96, 148, left: 5, top: 0, right: 5, bottom: 0)
        for y in 0..<12 { for x in 0..<96 { pixels[y * 96 + x] = 96 } }
        for y in 140..<148 { for x in 0..<96 { pixels[y * 96 + x] = 128 } }
        let content = PageBounds.detect(pixels, width: 96, height: 148)
        #expect(near(content.top, 0))
        #expect(near(content.bottom, 1))
        // A pale sky is paper as far as the thumbnail can tell: trimmed, but never past the cap.
        var pale = page(96, 148, left: 0, top: 0, right: 0, bottom: 0)
        for y in 0..<30 { for x in 0..<96 { pale[y * 96 + x] = 232 } }
        #expect(near(PageBounds.detect(pale, width: 96, height: 148).top, PageBounds.maxTrim))
    }

    @Test func aBlackFrameTrimsAsPaperDoes() {
        let content = PageBounds.detect(page(96, 148, left: 6, top: 6, right: 6, bottom: 6, paper: 12), width: 96, height: 148)
        #expect(near(content.left, 5 / 96.0))
        #expect(near(content.right, 1 - 5 / 96.0))
    }

    @Test func neverMoreThanTwelvePercentOfAnAxisSharedAsFound() {
        // 15 and 10 pixels of 96 across: 26% found, 12% taken, three to two.
        let content = PageBounds.detect(page(96, 148, left: 15, top: 4, right: 10, bottom: 4), width: 96, height: 148)
        #expect(near(content.left + (1 - content.right), PageBounds.maxTrim))
        #expect(near(content.left / (1 - content.right), 14.0 / 9.0))
    }

    @Test func aPageOfOnlyPaperASliverOfMarginOrATinyThumbnailIsLeftWhole() {
        #expect(PageBounds.detect(Array(repeating: 240, count: 96 * 148), width: 96, height: 148) == .whole)
        #expect(PageBounds.detect(page(96, 148, left: 1, top: 1, right: 1, bottom: 1), width: 96, height: 148) == .whole)
        #expect(PageBounds.detect(Array(repeating: 240, count: 16), width: 4, height: 4) == .whole)
        #expect(PageBounds.detect(Array(repeating: 0, count: 10), width: 96, height: 148) == .whole)
    }

    @Test func aPageNumberInTheMarginDoesNotStopTheTrim() {
        var pixels = page(96, 148, left: 6, top: 6, right: 6, bottom: 10)
        // Three dark pixels on the second row from the foot: a page number.
        for x in 46...48 { pixels[(148 - 2) * 96 + x] = 30 }
        let content = PageBounds.detect(pixels, width: 96, height: 148)
        #expect(near(content.bottom, 1 - 9 / 148.0))
    }

    @Test func whatTrimmingGainsAtThePocketsScreen() {
        // A 1988 x 3056 scan with 5% of paper at each side and 4% top and bottom.
        let content = PageContent(left: 0.05, top: 0.04, right: 0.95, bottom: 0.96)
        #expect(near(PageBounds.gain(content, pageWidth: 1_988, pageHeight: 3_056, viewWidth: 1_920, viewHeight: 1_080,
                                     fitWidth: true), 1 / 0.9))
        #expect(near(PageBounds.gain(content, pageWidth: 1_988, pageHeight: 3_056, viewWidth: 1_920, viewHeight: 1_080,
                                     fitWidth: false), 1 / 0.92))
        #expect(near(PageBounds.gain(.whole, pageWidth: 1_988, pageHeight: 3_056, viewWidth: 1_920, viewHeight: 1_080,
                                     fitWidth: true), 1))
        #expect(!PageContent.whole.trimmed)
    }

    @Test func aStepDownTheContentIsAStepDownThePageForItsMap() {
        let step = PageContent(left: 0.1, top: 0.2, right: 0.9, bottom: 0.8)
            .onPage(NormalizedViewport(left: 0, top: 0, right: 1, bottom: 0.5))
        #expect(near(step.left, 0.1))
        #expect(near(step.top, 0.2))
        #expect(near(step.right, 0.9))
        #expect(near(step.bottom, 0.5))
    }

    @Test func lumaWeighsGreenMost() {
        #expect(PageBounds.luma(0xFFFF_FFFF) == 255)
        #expect(PageBounds.luma(0xFF00_0000) == 0)
        #expect(PageBounds.luma(0xFF00_FF00) == 149)
    }
}
