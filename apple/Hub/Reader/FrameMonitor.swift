#if DEBUG && os(iOS)
import QuartzCore
import UIKit

/// The frames the screen drew while reading along, for #66's smoothness
/// check: a frame later than one and a half of its interval is a hitch, and
/// the hitch time is how much later than its interval it came, per second
/// (Instruments' hitch time ratio). Only with HUB_DEBUG_FRAMES=1; reset as
/// the narration starts to play.
@MainActor
final class FrameMonitor: NSObject {
    static let shared = FrameMonitor()

    private var link: CADisplayLink?
    private var last: CFTimeInterval = 0
    private var frames = 0
    private var hitches = 0
    private var hitchTime: Double = 0
    private var total: Double = 0
    private var worst: Double = 0

    func start() {
        guard link == nil, ProcessInfo.processInfo.environment["HUB_DEBUG_FRAMES"] == "1" else { return }
        let link = CADisplayLink(target: self, selector: #selector(tick(_:)))
        link.add(to: .main, forMode: .common)
        self.link = link
    }

    func reset() {
        last = 0
        frames = 0
        hitches = 0
        hitchTime = 0
        total = 0
        worst = 0
    }

    @objc private func tick(_ link: CADisplayLink) {
        defer { last = link.timestamp }
        guard last > 0 else { return }
        let gap = link.timestamp - last
        let interval = link.duration > 0 ? link.duration : 1.0 / 60
        frames += 1
        total += gap
        worst = max(worst, gap)
        if gap > interval * 1.5 {
            hitches += 1
            hitchTime += gap - interval
        }
    }

    /// "frames 3600 hitches 2 hitch 1.2ms/s worst 33.4ms".
    var summary: String {
        guard link != nil else { return "frames off" }
        let ratio = total > 0 ? hitchTime / total * 1_000 : 0
        return String(format: "frames %d hitches %d hitch %.1fms/s worst %.1fms", frames, hitches, ratio, worst * 1_000)
    }
}
#endif
