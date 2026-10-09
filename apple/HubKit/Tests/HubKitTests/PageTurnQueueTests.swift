import Testing
@testable import HubKit

/// Every turn asked for, made in order (#64).
struct PageTurnQueueTests {
    @Test func turnsAreMadeInOrderTheLastAnimatedTheRestAtOnce() {
        var queue = PageTurnQueue()
        #expect(queue.next() == nil)
        queue.add(.right)
        queue.add(.right)
        queue.add(.left)
        #expect(queue.next().map { $0.turn == .right && $0.animated == false } == true, "more wait: at once, to keep up with the hand")
        #expect(queue.next().map { $0.turn == .right && $0.animated == false } == true)
        #expect(queue.next().map { $0.turn == .left && $0.animated == true } == true, "the last one slides")
        #expect(queue.isEmpty && queue.next() == nil)
        // One alone slides, as a single swipe does.
        queue.add(.forward)
        #expect(queue.next().map { $0.turn == .forward && $0.animated == true } == true)
    }

    @Test func aSwipeIsAFlickOrALongEnoughDrag() {
        // A finger going left brings the page on the right.
        #expect(PageTurnQueue.swipe(dx: -40, dy: 3, vx: -1_200, width: 400) == .right)
        #expect(PageTurnQueue.swipe(dx: 40, dy: -3, vx: 1_200, width: 400) == .left)
        // Slow, it must cover a good part of the page.
        #expect(PageTurnQueue.swipe(dx: -160, dy: 0, vx: -50, width: 400) == .right)
        #expect(PageTurnQueue.swipe(dx: -60, dy: 0, vx: -50, width: 400) == nil, "a nudge")
        // Up and down more than across is no turn.
        #expect(PageTurnQueue.swipe(dx: -60, dy: 120, vx: -900, width: 400) == nil)
        // Flung back the way it came: the drag decides, as a paging view does.
        #expect(PageTurnQueue.swipe(dx: -200, dy: 0, vx: 600, width: 400) == .right)
        #expect(PageTurnQueue.swipe(dx: 0, dy: 0, vx: 0, width: 400) == nil, "a tap")
    }
}
