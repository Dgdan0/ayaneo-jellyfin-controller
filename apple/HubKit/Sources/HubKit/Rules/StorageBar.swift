import Foundation

// The storage bar (#48): one line split into other apps, JellyHub, what is
// coming or being added, and free, drawn from the device's real capacity
// and free space. The page's quick taps, the choices panel and select mode all
// draw this one (`StorageBarView`); this is its arithmetic and its words.

public struct StorageBar: Equatable, Sendable {
    public enum Part: String, CaseIterable, Sendable { case other, app, coming, free }

    public var capacity: Int64
    /// What other apps and the system hold.
    public var other: Int64
    /// What JellyHub holds: its downloads on the device.
    public var app: Int64
    /// What is on its way or being added, which will leave free space.
    public var coming: Int64
    public var free: Int64

    /// `free` is the device's free space now; `app` what JellyHub holds;
    /// `coming` the bytes still to arrive of what is on its way, and `adding`
    /// what the person is about to add (a choice's preview). What is coming
    /// takes its place out of free space, never more than there is.
    public init(capacity: Int64, free: Int64, app: Int64, coming: Int64 = 0, adding: Int64 = 0) {
        let capacity = max(capacity, 1)
        let free = min(max(free, 0), capacity)
        let app = min(max(app, 0), capacity - free)
        self.capacity = capacity
        self.app = app
        self.other = capacity - free - app
        self.coming = min(max(coming + adding, 0), free)
        self.free = free - self.coming
    }

    public func bytes(_ part: Part) -> Int64 {
        switch part {
        case .other: other
        case .app: app
        case .coming: coming
        case .free: free
        }
    }

    /// Each part's width in points over `width`: in proportion to its bytes,
    /// except that what is coming is never thinner than `minimumComing`
    /// (a few points, or a small download could not be seen), taken from
    /// free space, or from other apps when free space has none to give.
    public func widths(over width: Double, minimumComing: Double = 6) -> [Part: Double] {
        var out: [Part: Double] = [:]
        for part in Part.allCases { out[part] = width * Double(bytes(part)) / Double(capacity) }
        if coming > 0, let comingWidth = out[.coming], comingWidth < minimumComing {
            var need = minimumComing - comingWidth
            for donor in [Part.free, .other] {
                let take = min(need, out[donor] ?? 0)
                out[donor, default: 0] -= take
                need -= take
            }
            out[.coming] = minimumComing - need
        }
        return out
    }

    /// "12 GB free of 256 GB".
    public var words: String { "\(Fmt.bytes(free)) free of \(Fmt.bytes(capacity))" }
}

/// What the bar says over its line: "2 coming · 1 on this iPad".
public enum StorageBarWords {
    public static func line(coming: Int, onDevice: Int, device: String) -> String {
        switch (coming > 0, onDevice > 0) {
        case (true, true): "\(coming) coming · \(onDevice) on this \(device)"
        case (true, false): "\(coming) coming"
        case (false, true): "\(onDevice) on this \(device)"
        case (false, false): "Nothing downloaded"
        }
    }

    /// The preview under a choice: "Adds 1.2 GB · 96 GB free after".
    public static func preview(adding: Int64, free: Int64) -> String {
        adding <= 0 ? "Nothing to add" : "Adds \(Fmt.bytes(adding)) · \(Fmt.bytes(max(free - adding, 0))) free after"
    }
}

/// When the bar is up: from the first download on its way until a few
/// seconds after the last one finishes. The clock is the caller's, so a test
/// moves it instead of waiting.
public struct StorageBarVisibility: Equatable, Sendable {
    public static let lingerSeconds = 3.0

    private var coming = 0
    private var hideAt: Date?

    public init() {}

    /// The number coming now, at `now`: starting shows the bar, the last one
    /// finishing sets the time it goes.
    public mutating func update(coming: Int, now: Date) {
        if coming > 0 {
            hideAt = nil
        } else if self.coming > 0 {
            hideAt = now.addingTimeInterval(Self.lingerSeconds)
        }
        self.coming = coming
    }

    public func isVisible(at now: Date) -> Bool {
        if coming > 0 { return true }
        guard let hideAt else { return false }
        return now < hideAt
    }

    /// When to look again: the moment it fades, if it is waiting to.
    public var nextChange: Date? { coming > 0 ? nil : hideAt }
}
