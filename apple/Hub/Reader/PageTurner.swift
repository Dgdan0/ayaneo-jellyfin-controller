#if os(iOS)
import HubKit
import UIKit
import WebKit

/// Makes every page turn asked for, one at a time (#64, `PageTurnQueue`).
///
/// A swipe while nothing moves is Readium's own: the page follows the finger.
/// From its end until every page has stopped, Readium's scroll views take no
/// more swipes (their pans are off), and this turner's own pan hears them
/// instead, alongside everything else on the page. Each is queued with the
/// turns from the keys, the margins and the voice (`turn(_:)`), and the queue
/// is made through Readium's own turns once the page has stopped: animated
/// for the last, at once while more wait.
@MainActor
final class PageTurner: NSObject, UIGestureRecognizerDelegate {
    private weak var host: UIView?
    /// Scrolling, not paging: the scroll views are left alone.
    private let scrolling: () -> Bool
    private let scrollViews: () -> [UIScrollView]
    private let perform: (PageTurnQueue.Turn, Bool) async -> Bool
    private var scrolls: Bool { scrolling() }

    private let pan = UIPanGestureRecognizer()
    private var queue = PageTurnQueue()
    private var working = false
    /// Readium's swipe is still sliding the page.
    private var sliding = false
    private var pansOff = false
    /// Where Readium's swipe started (its part's page view and page), and where it was going:
    /// stopping the page view's scrolling can stop its slide part-way, which is then finished.
    private var slideFrom: (view: UIScrollView, page: Int)?
    private var slideTo: (view: UIScrollView, page: Int)?
    /// Each waiting turn's answer, in order.
    private var answers: [CheckedContinuation<Bool, Never>?] = []

    init(host: UIView, scrolls: @escaping () -> Bool, scrollViews: @escaping () -> [UIScrollView],
         perform: @escaping (PageTurnQueue.Turn, Bool) async -> Bool) {
        self.host = host
        self.scrolling = scrolls
        self.scrollViews = scrollViews
        self.perform = perform
        super.init()
        pan.addTarget(self, action: #selector(panned(_:)))
        pan.delegate = self
        pan.cancelsTouchesInView = false
        pan.delaysTouchesBegan = false
        pan.delaysTouchesEnded = false
        host.addGestureRecognizer(pan)
    }

    /// A turn from the keys, the margins or the voice: made after those before it.
    /// True when the page moved; false at either end of the book.
    func turn(_ turn: PageTurnQueue.Turn) async -> Bool {
        await withCheckedContinuation { continuation in
            enqueue(turn, answer: continuation)
        }
    }

    // MARK: Swipes

    @objc private func panned(_ recognizer: UIPanGestureRecognizer) {
        if recognizer.state == .began {
            slideFrom = working || sliding || pansOff ? nil : page()
            return
        }
        guard !scrolls, recognizer.state == .ended, let view = recognizer.view else { return }
        let moved = recognizer.translation(in: view)
        let speed = recognizer.velocity(in: view)
        guard let turn = PageTurnQueue.swipe(dx: moved.x, dy: moved.y, vx: speed.x, width: view.bounds.width) else { return }
        if !working && queue.isEmpty && !sliding && !pansOff {
            // Readium turned this one, the page with the finger: no more swipes are its until the page stops.
            if let from = slideFrom {
                slideTo = (from.view, min(max(from.page + (turn == .right ? 1 : -1), 0), pages(from.view) - 1))
            }
            sliding = true
            setPans(on: false)
            Task { await settle() }
            return
        }
        enqueue(turn, answer: nil)
    }

    /// Heard beside Readium's own gestures, never instead of them.
    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                           shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }

    // MARK: The queue

    private func enqueue(_ turn: PageTurnQueue.Turn, answer: CheckedContinuation<Bool, Never>?) {
        queue.add(turn)
        answers.append(answer)
        guard !working else { return }
        working = true
        if !scrolls { setPans(on: false) }
        Task { await work() }
    }

    private func work() async {
        while true {
            await settled()
            await finishSlide()
            guard let next = queue.next() else { break }
            let answer = answers.isEmpty ? nil : answers.removeFirst()
            let went = await perform(next.turn, next.animated)
            answer?.resume(returning: went)
        }
        working = false
        await settle()
    }

    /// Readium's swipe has stopped: its scroll views take swipes again, unless turns wait.
    private func settle() async {
        await settled()
        await finishSlide()
        sliding = false
        if !working && queue.isEmpty { setPans(on: true) }
    }

    /// Until no scroll view is dragged, slides or moves: two still frames running.
    private func settled() async {
        var still = 0
        var last = offsets()
        for _ in 0..<120 {
            try? await Task.sleep(for: .milliseconds(16))
            let now = offsets()
            let moving = scrollViews().contains { $0.isDragging || $0.isDecelerating || $0.isTracking }
            still = !moving && now == last ? still + 1 : 0
            last = now
            if still >= 2 { return }
        }
    }

    private func offsets() -> [CGFloat] { scrollViews().map { $0.contentOffset.x } }

    /// Readium's slide, stopped part-way when its page view stopped taking the
    /// finger, goes on to the page it was going to; one that got there, or
    /// left for the next part, is left as it is.
    private func finishSlide() async {
        guard let target = slideTo else { return }
        slideTo = nil
        let view = target.view
        let width = view.bounds.width
        guard view.window != nil, width > 0 else { return }
        let at = view.contentOffset.x / width
        guard abs(at - at.rounded()) > 0.02 else { return }
        view.setContentOffset(CGPoint(x: CGFloat(target.page) * width, y: view.contentOffset.y), animated: true)
        await settled()
    }

    /// The part on screen's page view and the page it shows.
    private func page() -> (view: UIScrollView, page: Int)? {
        guard let host else { return nil }
        let views = scrollViews().filter { $0.superview is WKWebView && $0.window != nil && $0.bounds.width > 0 }
        let shown = views.max { visible($0, in: host) < visible($1, in: host) }
        guard let shown else { return nil }
        return (shown, Int((shown.contentOffset.x / shown.bounds.width).rounded()))
    }

    private func pages(_ view: UIScrollView) -> Int {
        view.bounds.width > 0 ? max(1, Int((view.contentSize.width / view.bounds.width).rounded())) : 1
    }

    private func visible(_ view: UIScrollView, in host: UIView) -> CGFloat {
        let frame = view.convert(view.bounds, to: host).intersection(host.bounds)
        return frame.isNull ? 0 : frame.width * frame.height
    }

    private func setPans(on: Bool) {
        pansOff = !on
        let views = scrollViews()
        for view in views {
            // A part's own page (a web view's) stops taking the finger; Readium
            // never sets that one. Its paging between parts sets its own, so only its pan goes.
            if view.superview is WKWebView { view.isScrollEnabled = on } else { view.panGestureRecognizer.isEnabled = on }
        }
    }
}
#endif
