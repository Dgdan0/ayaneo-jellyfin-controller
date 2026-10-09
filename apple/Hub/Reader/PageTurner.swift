#if os(iOS)
import HubKit
import UIKit
import WebKit

/// The page follows the finger, always, and every swipe still counts (#64).
///
/// Readium's pages are paging scroll views, and this turner never switches one
/// off: a finger can land on a page that is still sliding, take it where it
/// is, drag it, and let it go, as on any native paging view. What it adds is
/// the account of where the page is *going*, because a scroll view in motion
/// can only say where it has got to, and a swipe counted from there loses a
/// turn (`PageIntent`):
///
///  - a finger lands: the page it counts from is the one the last swipe was
///    heading to (`base`), and a slide in progress is taken in the hand where
///    it is, with the speed it had;
///  - the finger lifts: a swipe (`PageTurnQueue.swipe`'s thresholds) ends one
///    page on or back from that base, anything else on the base itself, and
///    the page glides there from the offset and speed it was let go at, a
///    critically damped spring (no linear stop, no restart), whatever
///    Readium's own deceleration was about to do;
///  - the finger lifts and the page is a part's last or first: Readium's outer
///    paging view makes that turn (it already has, when its own pan took the
///    finger; else it is asked through the queue).
///
/// Turns that are not a finger (the keys, the margins, the voice) are queued
/// (`PageTurnQueue`) and made through Readium's own turns, one at a time, and
/// leave the account at the page they reached. A finger landing during one is
/// not held off.
@MainActor
final class PageTurner: NSObject, UIGestureRecognizerDelegate {
    private weak var host: UIView?
    /// Scrolling, not paging: the scroll views are left alone.
    private let scrolling: () -> Bool
    /// The book is read from the right: its pages are at offsets -n...0.
    private let readsRightToLeft: () -> Bool
    private let scrollViews: () -> [UIScrollView]
    private let perform: (PageTurnQueue.Turn, Bool) async -> Bool
    private var scrolls: Bool { scrolling() }

    private let pan = UIPanGestureRecognizer()
    private let touch = TouchRecognizer()
    private var queue = PageTurnQueue()
    private var working = false
    /// Each waiting turn's answer, in order.
    private var answers: [CheckedContinuation<Bool, Never>?] = []

    private var intent = PageIntent()
    /// The finger on the page now.
    private var finger: Finger?
    /// A page gliding to where its swipe was going.
    private var glide: Glide?
    private var link: CADisplayLink?
    private var linkTarget: LinkTarget?
    /// Lets a release that waits a turn of the run loop find out a finger came down meanwhile.
    private var generation = 0

    /// A finger on the page and what the account says of it.
    private struct Finger {
        let view: UIScrollView
        /// The page it counts from (`PageIntent.base`).
        let base: Int
        /// The pages of this part, as offsets.
        let pages: ClosedRange<Int>
        /// The glide it took in its hand, and the speed that glide had, in points a second.
        let caught: Bool
        let carried: CGFloat
        /// The offset it took the page at.
        let caughtAt: CGFloat
    }

    /// The offset easing to `target`: a critically damped spring from `from`
    /// at `velocity` points a second. It never overshoots: a speed that would
    /// carry it past is held to the one that just reaches it.
    private final class Glide {
        static let omega: CGFloat = 22
        let view: UIScrollView
        let target: CGFloat
        let from: CGFloat
        let velocity: CGFloat
        let start: CFTimeInterval

        init(view: UIScrollView, target: CGFloat, velocity: CGFloat, start: CFTimeInterval) {
            self.view = view
            self.target = target
            self.from = view.contentOffset.x
            self.start = start
            let toward: CGFloat = target >= from ? 1 : -1
            let along = velocity * toward
            // Away from the target, or none: it starts from rest. Faster than a spring that just reaches it: held to that.
            self.velocity = along <= 0 ? 0 : toward * min(along, Glide.omega * abs(target - view.contentOffset.x))
        }

        /// Offset and speed `t` seconds in.
        func state(at t: Double) -> (x: CGFloat, v: CGFloat) {
            let w = Glide.omega
            let d0 = from - target
            let decay = CGFloat(exp(-Double(w) * t))
            let c = velocity + w * d0
            let time = CGFloat(t)
            return (target + (d0 + c * time) * decay, decay * (velocity - w * c * time))
        }
    }

    @MainActor
    private final class LinkTarget: NSObject {
        weak var owner: PageTurner?
        @objc func tick(_ link: CADisplayLink) { owner?.tick(link) }
    }

    #if DEBUG
    private var record = Record()
    #endif

    init(host: UIView, scrolls: @escaping () -> Bool, readsRightToLeft: @escaping () -> Bool,
         scrollViews: @escaping () -> [UIScrollView], perform: @escaping (PageTurnQueue.Turn, Bool) async -> Bool) {
        self.host = host
        self.scrolling = scrolls
        self.readsRightToLeft = readsRightToLeft
        self.scrollViews = scrollViews
        self.perform = perform
        super.init()
        pan.addTarget(self, action: #selector(panned(_:)))
        for recognizer in [pan, touch] as [UIGestureRecognizer] {
            recognizer.delegate = self
            recognizer.cancelsTouchesInView = false
            recognizer.delaysTouchesBegan = false
            recognizer.delaysTouchesEnded = false
            host.addGestureRecognizer(recognizer)
        }
        touch.down = { [weak self] in self?.landed() }
        touch.up = { [weak self] in self?.lifted() }
    }

    /// A turn from the keys, the margins or the voice: made after those before it.
    /// True when the page moved; false at either end of the book.
    func turn(_ turn: PageTurnQueue.Turn) async -> Bool {
        await withCheckedContinuation { continuation in
            enqueue(turn, answer: continuation)
        }
    }

    /// Heard beside Readium's own gestures, never instead of them.
    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                           shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }

    // MARK: A finger

    /// A finger came down: a glide in progress is taken in the hand where it
    /// is (nothing else is touched, so Readium's own pans take the finger and
    /// the page follows it), and the page its swipe counts from is chosen.
    private func landed() {
        guard !scrolls else { return }
        generation &+= 1
        let caught = stopGlide()
        let view = caught?.view ?? page()?.view
        guard let view, view.window != nil, view.bounds.width > 0 else { finger = nil; return }
        let width = view.bounds.width
        let pages = pageRange(view)
        let shown = min(max(Int((view.contentOffset.x / width).rounded()), pages.lowerBound), pages.upperBound)
        // Moving: a glide was taken, a turn of the queue is under way, or Readium's own slide goes on.
        let outerBusy = outerViews().contains { $0.isDragging || $0.isDecelerating }
        let moving = caught != nil || working || view.isDecelerating || outerBusy
        let base = intent.base(part: key(view), shown: shown, moving: moving)
        finger = Finger(view: view, base: base, pages: pages, caught: caught != nil, carried: caught?.speed ?? 0,
                        caughtAt: view.contentOffset.x)
        #if DEBUG
        record.landed(moving: moving, caught: caught != nil, at: view.contentOffset.x, now: CACurrentMediaTime())
        #endif
        runLink()
    }

    @objc private func panned(_ recognizer: UIPanGestureRecognizer) {
        guard !scrolls, let finger, let view = recognizer.view else { return }
        switch recognizer.state {
        case .began:
            #if DEBUG
            record.began(offset: finger.view.contentOffset.x, translation: recognizer.translation(in: view).x)
            #endif
        case .changed:
            #if DEBUG
            record.moved(offset: finger.view.contentOffset.x, translation: recognizer.translation(in: view).x)
            #endif
        case .ended:
            // From the touches themselves: a quick flick can end with the pan's own translation still at zero (seen: dx 0 at 1776 pt/s).
            let speed = recognizer.velocity(in: view)
            release(finger, dx: touch.last.x - touch.start.x, dy: touch.last.y - touch.start.y, vx: speed.x, width: view.bounds.width)
        case .cancelled, .failed:
            release(finger, dx: 0, dy: 0, vx: 0, width: view.bounds.width)
        default:
            break
        }
    }

    /// The finger lifted. Whatever the pan made of it has been handled by now;
    /// one that never became a pan (a tap, a nudge) ends the page on its base.
    private func lifted() {
        let mark = generation
        Task { [weak self] in
            guard let self, mark == generation, let finger else { return }
            release(finger, dx: 0, dy: 0, vx: 0, width: finger.view.bounds.width)
        }
    }

    /// Decides where the page goes and sends it there, one turn of the run loop
    /// on, when the scroll view has begun its own deceleration (which the glide replaces).
    private func release(_ finger: Finger, dx: CGFloat, dy: CGFloat, vx: CGFloat, width: CGFloat) {
        self.finger = nil
        #if DEBUG
        record.released(view: finger.view)
        #endif
        // The queue is making turns (one that crossed into the next part, say): this swipe is made after them,
        // by Readium, or it would be counted from a page that is about to change under it.
        if working, let swipe = PageTurnQueue.swipe(dx: Double(dx), dy: Double(dy), vx: Double(vx), width: Double(width)) {
            intent.forget()
            #if DEBUG
            record.note(release: "queued \(swipe)", now: CACurrentMediaTime())
            #endif
            enqueue(swipe, answer: nil)
            return
        }
        let outcome = intent.release(part: key(finger.view), base: finger.base, pages: finger.pages,
                                     dx: Double(dx), dy: Double(dy), vx: Double(vx), width: Double(width))
        #if DEBUG
        record.note(release: String(format: "dx %.0f dy %.0f vx %.0f base %d -> %@", dx, dy, vx, finger.base, "\(outcome)"), now: CACurrentMediaTime())
        #endif
        let mark = generation
        // The finger's velocity, in the offset's direction.
        let carried = finger.caught && dx == 0 && vx == 0 ? finger.carried : -vx
        Task { [weak self] in
            guard let self, mark == generation else { return }
            switch outcome {
            case .page(let page):
                glideTo(page: page, in: finger.view, speed: carried)
            case .leaves(let turn):
                leave(turn, from: finger)
            }
        }
    }

    /// A swipe from a part's last page on, or first page back. Readium's outer
    /// paging view took the finger when the part could not, and is already on
    /// its way; else the turn is made for it.
    private func leave(_ turn: PageTurnQueue.Turn, from finger: Finger) {
        if outerViews().contains(where: { $0.isDragging || $0.isDecelerating }) { return }
        // The page may have been let go short of its end: it finishes first.
        glideTo(page: turn == .right ? finger.pages.upperBound : finger.pages.lowerBound, in: finger.view, speed: 0)
        enqueue(turn, answer: nil)
    }

    // MARK: Gliding

    private func glideTo(page: Int, in view: UIScrollView, speed: CGFloat) {
        guard view.window != nil, view.bounds.width > 0 else { return }
        let target = CGFloat(page) * view.bounds.width
        // Stops the deceleration Readium's scroll view began: one offset write, which a scroll view takes as the end of its momentum.
        if view.isDecelerating || view.isDragging { view.setContentOffset(view.contentOffset, animated: false) }
        guard abs(view.contentOffset.x - target) > 0.25 || abs(speed) > 1 else { settleIntent(view: view, page: page); return }
        glide = Glide(view: view, target: target, velocity: speed, start: CACurrentMediaTime())
        #if DEBUG
        record.glides(to: target)
        #endif
        runLink()
    }

    /// The glide in the hand: where it is, and how fast it was going.
    private func stopGlide() -> (view: UIScrollView, speed: CGFloat)? {
        guard let glide else { return nil }
        self.glide = nil
        let state = glide.state(at: CACurrentMediaTime() - glide.start)
        return (glide.view, state.v)
    }

    private func runLink() {
        guard link == nil else { return }
        let target = LinkTarget()
        target.owner = self
        let display = CADisplayLink(target: target, selector: #selector(LinkTarget.tick(_:)))
        display.add(to: .main, forMode: .common)
        link = display
        linkTarget = target
    }

    private func tick(_ link: CADisplayLink) {
        let now = link.targetTimestamp
        var arrived = false
        if let current = glide {
            if current.view.window == nil {
                glide = nil
            } else {
                let t = max(0, now - current.start)
                let state = current.state(at: t)
                if abs(state.x - current.target) < 0.3 && abs(state.v) < 4 || t > 1.5 {
                    glide = nil
                    current.view.contentOffset.x = current.target
                    arrived = true
                    settleIntent(view: current.view, page: Int((current.target / max(current.view.bounds.width, 1)).rounded()))
                } else {
                    current.view.contentOffset.x = state.x
                }
            }
        }
        #if DEBUG
        record.frame(views: scrollViews(), busy: glide != nil || finger != nil, now: now)
        if arrived { record.arrived() }
        #endif
        let idle = glide == nil && finger == nil && !working
        #if DEBUG
        if idle && now - record.lastBusy < 0.5 { return }
        #endif
        if idle {
            link.invalidate()
            self.link = nil
            linkTarget = nil
        }
    }

    private func settleIntent(view: UIScrollView, page: Int) {
        intent.rest(part: key(view), page: page)
    }

    // MARK: The queue

    private func enqueue(_ turn: PageTurnQueue.Turn, answer: CheckedContinuation<Bool, Never>?) {
        queue.add(turn)
        answers.append(answer)
        guard !working else { return }
        working = true
        runLink()
        Task { await work() }
    }

    private func work() async {
        while true {
            await settled()
            guard let next = queue.next() else { break }
            let answer = answers.isEmpty ? nil : answers.removeFirst()
            let went = await perform(next.turn, next.animated)
            answer?.resume(returning: went)
        }
        await settled()
        // The turns were Readium's: the account is the page they came to.
        if let shown = page() {
            let range = pageRange(shown.view)
            settleIntent(view: shown.view, page: min(max(shown.page, range.lowerBound), range.upperBound))
        } else {
            intent.forget()
        }
        working = false
    }

    /// Until no scroll view is dragged, slides or moves, and no finger or glide is on the page: two still frames running.
    private func settled() async {
        var still = 0
        var last = offsets()
        for _ in 0..<120 {
            try? await Task.sleep(for: .milliseconds(16))
            let now = offsets()
            let moving = scrollViews().contains { $0.isDragging || $0.isDecelerating || $0.isTracking }
                || glide != nil || finger != nil
            still = !moving && now == last ? still + 1 : 0
            last = now
            if still >= 2 { return }
        }
    }

    private func offsets() -> [CGFloat] { scrollViews().map { $0.contentOffset.x } }

    // MARK: Finding the page

    /// A part's page view is a web view's scroll view; Readium's paging between parts is the other.
    private func outerViews() -> [UIScrollView] {
        scrollViews().filter { !($0.superview is WKWebView) }
    }

    /// The part on screen's page view and the page it shows, as an offset.
    private func page() -> (view: UIScrollView, page: Int)? {
        guard let host else { return nil }
        let views = scrollViews().filter { $0.superview is WKWebView && $0.window != nil && $0.bounds.width > 0 }
        let shown = views.max { visible($0, in: host) < visible($1, in: host) }
        guard let shown else { return nil }
        return (shown, Int((shown.contentOffset.x / shown.bounds.width).rounded()))
    }

    private func pageRange(_ view: UIScrollView) -> ClosedRange<Int> {
        let count = view.bounds.width > 0 ? max(1, Int((view.contentSize.width / view.bounds.width).rounded())) : 1
        return readsRightToLeft() ? -(count - 1) ... 0 : 0 ... count - 1
    }

    private func key(_ view: UIScrollView) -> Int { ObjectIdentifier(view).hashValue }

    private func visible(_ view: UIScrollView, in host: UIView) -> CGFloat {
        let frame = view.convert(view.bounds, to: host).intersection(host.bounds)
        return frame.isNull ? 0 : frame.width * frame.height
    }

    // MARK: Debug

    #if DEBUG
    /// What UI tests read: the locks seen, the follow of a finger that took a
    /// page in motion, and the last glide's frames.
    var debugLine: String { record.line }
    #endif
}

/// Tells the turner a finger came down and went up, without ever recognizing
/// (so it takes nothing from the page's own gestures).
private final class TouchRecognizer: UIGestureRecognizer {
    var down: (() -> Void)?
    var up: (() -> Void)?
    /// Where the finger came down and where it is, in the host's points.
    private(set) var start = CGPoint.zero
    private(set) var last = CGPoint.zero

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
        if numberOfTouches == 1 {
            start = location(in: view)
            last = start
            down?()
        }
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent) {
        last = location(in: view)
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent) {
        last = location(in: view)
        if numberOfTouches <= touches.count { up?(); state = .failed }
    }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent) {
        if numberOfTouches <= touches.count { up?(); state = .failed }
    }
}

#if DEBUG
/// What a UI test cannot see from outside the app, counted frame by frame:
/// whether a page view was ever switched off during a burst, whether a page
/// taken mid-slide moved with the finger from where it was, and whether the
/// last swipe's offset ever stopped before it arrived.
private struct Record {
    // Locks, over the burst now or last.
    var frames = 0
    var innerLocked = 0
    var outerLocked = 0
    var lastBusy: CFTimeInterval = 0
    var landings = 0

    // The finger that took a page in motion.
    var grabs = 0
    var lastGrabJump: CGFloat = 0
    var firstSample: (translation: CGFloat, offset: CGFloat)?
    var lastSample: (translation: CGFloat, offset: CGFloat)?
    var samples = 0
    var caughtAt: CGFloat = 0
    var grabbed = false

    // The last swipe's frames, from its release to its arrival.
    var trace: [CGFloat] = []
    var traceView: UIScrollView?
    var target: CGFloat?
    var lastRelease = ""
    var releasedAt: CFTimeInterval = 0
    var sinceRelease: Int = -1

    mutating func note(release: String, now: CFTimeInterval) {
        lastRelease = release
        releasedAt = now
    }

    mutating func landed(moving: Bool, caught: Bool, at offset: CGFloat, now: CFTimeInterval) {
        if now - lastBusy > 0.8 {
            frames = 0; innerLocked = 0; outerLocked = 0; landings = 0
        }
        landings += 1
        sinceRelease = Int((now - releasedAt) * 1000)
        lastBusy = now
        grabbed = caught
        caughtAt = offset
        firstSample = nil; lastSample = nil; samples = 0
        if caught { grabs += 1 }
        traceView = nil
    }

    mutating func began(offset: CGFloat, translation: CGFloat) {
        if grabbed { lastGrabJump = abs(offset - caughtAt) }
        firstSample = nil
        lastSample = nil
        samples = 0
    }

    mutating func moved(offset: CGFloat, translation: CGFloat) {
        let sample = (translation, offset)
        if firstSample == nil { firstSample = sample }
        lastSample = sample
        samples += 1
    }

    mutating func released(view: UIScrollView) {
        trace = []
        target = nil
        traceView = view
    }

    mutating func glides(to target: CGFloat) {
        self.target = target
    }

    mutating func arrived() {
        traceView = nil
    }

    mutating func frame(views: [UIScrollView], busy: Bool, now: CFTimeInterval) {
        frames += 1
        if busy { lastBusy = now }
        for view in views where !view.isScrollEnabled || !view.panGestureRecognizer.isEnabled {
            if view.superview is WKWebView { innerLocked += 1 } else { outerLocked += 1 }
        }
        if let traceView { trace.append(traceView.contentOffset.x) }
    }

    /// The last swipe: frames from release to arrival, frames where the page
    /// stood still short of its target (a stop), frames where it went the
    /// wrong way (a restart), and how far from the target it ended.
    var swipe: String {
        guard let target, trace.count > 2 else { return "swipe none" }
        var stalls = 0, backs = 0
        for index in 1 ..< trace.count {
            let step = trace[index] - trace[index - 1]
            let toward: CGFloat = target >= trace[index - 1] ? 1 : -1
            if abs(step) < 0.05 && abs(target - trace[index]) > 6 { stalls += 1 }
            if step * toward < -0.5 { backs += 1 }
        }
        return String(format: "swipe frames %d stalls %d backs %d from %.0f to %.0f end %.1f", trace.count, stalls, backs,
                      trace.first ?? 0, target, trace.last ?? 0)
    }

    var line: String {
        var parts = ["turns \(landings) frames \(frames) locked inner \(innerLocked) outer \(outerLocked)"]
        if grabs > 0 {
            var follow = "none"
            if let first = firstSample, let last = lastSample, abs(last.translation - first.translation) > 8 {
                follow = String(format: "%.2f", (last.offset - first.offset) / (last.translation - first.translation))
            }
            parts.append(String(format: "grabs %d jump %.1f follow %@ over %d", grabs, lastGrabJump, follow, samples))
        }
        parts.append(swipe)
        parts.append("last release \(lastRelease), landed +\(sinceRelease)ms after")
        return parts.joined(separator: " · ")
    }
}
#endif
#endif
