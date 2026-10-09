import Testing
@testable import HubKit

/// The page a swipe is heading to, kept apart from the scroll view still sliding there (#64, fluid).
struct PageIntentTests {
    private func swipe(_ intent: inout PageIntent, base: Int, pages: ClosedRange<Int> = 0...19, forward: Bool = true) -> PageIntent.Outcome {
        // A flick across 80 points, as quick swipes are.
        intent.release(part: 1, base: base, pages: pages, dx: forward ? -80 : 80, dy: 2, vx: forward ? -900 : 900, width: 400)
    }

    @Test func tenSwipesOnLandTenPagesOnEvenWhenEachLandsOnASlide() {
        var intent = PageIntent()
        var shown = 2
        for step in 1...10 {
            // Each finger lands while the page is still a page behind the one it heads to.
            let base = intent.base(part: 1, shown: shown, moving: step > 1)
            #expect(base == (step == 1 ? 2 : 2 + step - 1))
            #expect(swipe(&intent, base: base) == .page(2 + step))
            shown = base
        }
        #expect(intent.page == 12)
    }

    @Test func atRestTheBaseIsThePageShown() {
        var intent = PageIntent()
        _ = swipe(&intent, base: 4)
        #expect(intent.base(part: 1, shown: 5, moving: false) == 5, "nothing moves: what is shown counts")
        #expect(intent.base(part: 2, shown: 0, moving: true) == 0, "another part is another account")
        #expect(intent.base(part: 1, shown: 4, moving: true) == 5, "moving: the page it was heading to")
    }

    @Test func aDragThatTurnsNothingGoesBackToThePageItWasHeadingTo() {
        var intent = PageIntent()
        #expect(swipe(&intent, base: 6) == .page(7))
        // A finger lands on that slide, drags 40 points slowly and lifts: not a turn.
        let base = intent.base(part: 1, shown: 6, moving: true)
        #expect(intent.release(part: 1, base: base, pages: 0...19, dx: -40, dy: 0, vx: -30, width: 400) == .page(7), "not to 6, the nearest")
        #expect(intent.page == 7)
    }

    @Test func backIsOnePageBackFromTheBase() {
        var intent = PageIntent()
        #expect(swipe(&intent, base: 9, forward: false) == .page(8))
        let base = intent.base(part: 1, shown: 9, moving: true)
        #expect(swipe(&intent, base: base, forward: false) == .page(7))
    }

    @Test func aSwipeFromEitherEndOfThePartLeavesIt() {
        var intent = PageIntent()
        #expect(swipe(&intent, base: 19) == .leaves(.right))
        #expect(intent.base(part: 1, shown: 19, moving: true) == 19, "the account is not carried to a part it did not count")
        #expect(swipe(&intent, base: 0, forward: false) == .leaves(.left))
        // A base outside the part is held inside it.
        #expect(swipe(&intent, base: 40, forward: false) == .page(18))
    }

    @Test func aBookReadFromTheRightCountsInNegativeOffsets() {
        var intent = PageIntent()
        let pages = -9 ... 0
        // The finger going left brings the page with the higher offset, as it does everywhere.
        #expect(swipe(&intent, base: -5, pages: pages) == .page(-4))
        #expect(swipe(&intent, base: -4, pages: pages, forward: false) == .page(-5))
        #expect(swipe(&intent, base: 0, pages: pages) == .leaves(.right))
        #expect(swipe(&intent, base: -9, pages: pages, forward: false) == .leaves(.left))
    }

    @Test func aTurnThatWasNotAFingersMovesTheAccount() {
        var intent = PageIntent()
        intent.rest(part: 3, page: 11)
        #expect(intent.base(part: 3, shown: 10, moving: true) == 11)
        intent.forget()
        #expect(intent.base(part: 3, shown: 10, moving: true) == 10)
    }
}
