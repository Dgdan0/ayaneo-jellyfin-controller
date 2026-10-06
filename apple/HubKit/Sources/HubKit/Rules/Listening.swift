import Foundation

// Listening's arithmetic (#25 phase 2): the speeds, the time left as heard,
// a jump across parts, the sleep timer with its fade and the step back after
// it, how often the place goes to the hub, and one sound at a time. Ports of
// Android's `Listening`, `SleepTimer`, `SmartRewind`, `SyncThrottle` and
// `AudioArbiter`, held to their tests.

/// The listening controls' numbers.
public enum Listening {
    /// The speeds a book plays at: three quarters to three, the common steps between.
    public static let speeds: [Float] = [0.75, 1, 1.1, 1.25, 1.5, 1.75, 2, 2.5, 3]

    /// The speed after `speed`, round to the start after the last.
    public static func nextSpeed(_ speed: Float) -> Float {
        let index = speeds.firstIndex { abs($0 - speed) < 0.01 } ?? -1
        return speeds[(index + 1) % speeds.count]
    }

    public static func clampSpeed(_ speed: Float) -> Float {
        min(max(speed, speeds[0]), speeds[speeds.count - 1])
    }

    /// How long `mediaMs` of a recording takes to hear at `speed`.
    public static func heard(_ mediaMs: Int64, speed: Float) -> Int64 {
        Int64(Double(max(0, mediaMs)) / Double(clampSpeed(speed)))
    }

    /// Left in the part playing, as heard.
    public static func partLeft(positionMs: Int64, partMs: Int64, speed: Float) -> Int64 {
        heard(partMs - positionMs, speed: speed)
    }

    /// Left in the book, as heard: the rest of `part` and every part after it;
    /// nil while any of those lengths is unknown.
    public static func bookLeft(part: Int, positionMs: Int64, partsMs: [Int64?], speed: Float) -> Int64? {
        let rest = partsMs.dropFirst(max(0, part))
        guard !rest.isEmpty, rest.allSatisfy({ ($0 ?? 0) > 0 }) else { return nil }
        return heard(rest.reduce(0) { $0 + ($1 ?? 0) } - max(0, positionMs), speed: speed)
    }

    /// A jump by `deltaMs` of recording from `positionMs` of `part`, across
    /// parts (Android's `ReadingAudio.seekBy`): back into the part before, on
    /// into the next, and no further than a length that is not yet known.
    /// `currentPartMs` is the player's own length of the part playing.
    public static func jump(part: Int, positionMs: Int64, by deltaMs: Int64, partsMs: [Int64?],
                            currentPartMs: Int64? = nil) -> (part: Int, offsetMs: Int64) {
        var target = part
        var offset = positionMs + deltaMs
        while offset < 0 && target > 0 {
            target -= 1
            offset += partsMs.indices.contains(target) ? (partsMs[target] ?? 0) : 0
        }
        while target < partsMs.count - 1 {
            let known = partsMs[target].flatMap { $0 > 0 ? $0 : nil }
            let length = known ?? (target == part ? currentPartMs.flatMap { $0 > 0 ? $0 : nil } : nil)
            guard let length, offset >= length else { break }
            offset -= length
            target += 1
        }
        return (target, max(0, offset))
    }
}

/// What the sleep timer was set to: minutes of listening, or the end of the part playing.
public enum SleepChoice: Equatable, Hashable, Sendable {
    case minutes(Int)
    case endOfPart

    public static let all: [SleepChoice] = [.minutes(5), .minutes(15), .minutes(30), .minutes(45), .minutes(60), .endOfPart]
}

/// The sleep timer. It counts only while something plays; over its last
/// `fadeMs` the volume falls to nothing, and when it runs out playback pauses
/// and steps back over what faded. Any control while it fades keeps you
/// listening: minutes start again, the end of a part becomes the next one's.
public struct SleepTimer: Equatable, Sendable {
    public static let fadeMs: Int64 = 30_000

    public let choice: SleepChoice
    public let remainingMs: Int64
    public let skipParts: Int

    public init(choice: SleepChoice, remainingMs: Int64, skipParts: Int = 0) {
        self.choice = choice
        self.remainingMs = remainingMs
        self.skipParts = skipParts
    }

    public static func start(_ choice: SleepChoice, partLeftHeardMs: Int64) -> SleepTimer {
        switch choice {
        case .minutes(let minutes): SleepTimer(choice: choice, remainingMs: Int64(minutes) * 60_000)
        case .endOfPart: SleepTimer(choice: choice, remainingMs: partLeftHeardMs)
        }
    }

    /// Faded, and pausing now.
    public var runsOut: Bool { remainingMs <= 0 }

    /// In its last `fadeMs`.
    public var fading: Bool { remainingMs > 0 && remainingMs < Self.fadeMs }

    /// Stops as the part playing ends: the player moving on is the moment.
    public var endsWithPart: Bool { choice == .endOfPart && skipParts == 0 }

    /// How loud while it counts: full, then falling over the fade.
    public var volume: Float { min(max(Float(remainingMs) / Float(Self.fadeMs), 0), 1) }

    /// `elapsedMs` of listening later. A minutes timer counts down; the end of
    /// a part is read from the player, unless it was carried past one.
    public func tick(elapsedMs: Int64, partLeftHeardMs: Int64) -> SleepTimer {
        switch choice {
        case .minutes:
            return SleepTimer(choice: choice, remainingMs: remainingMs - max(0, elapsedMs), skipParts: skipParts)
        case .endOfPart:
            return SleepTimer(choice: choice, remainingMs: skipParts > 0 ? remainingMs - max(0, elapsedMs) : partLeftHeardMs,
                              skipParts: skipParts)
        }
    }

    /// A control while it fades: carry on listening.
    public func extended(partLeftHeardMs: Int64, nextPartHeardMs: Int64?) -> SleepTimer {
        switch choice {
        case .minutes(let minutes):
            return SleepTimer(choice: choice, remainingMs: Int64(minutes) * 60_000, skipParts: skipParts)
        case .endOfPart:
            // Still awake at the end of this part: stop at the end of the next one.
            return SleepTimer(choice: choice, remainingMs: partLeftHeardMs + (nextPartHeardMs ?? 0), skipParts: skipParts + 1)
        }
    }

    /// The part changed under a timer carried past it: count to the end of the new one.
    public func partChanged(partLeftHeardMs: Int64) -> SleepTimer {
        guard choice == .endOfPart, skipParts > 0 else { return self }
        return SleepTimer(choice: choice, remainingMs: partLeftHeardMs, skipParts: skipParts - 1)
    }
}

/// Where to pick up after the sleep timer stopped playback: back over what faded.
public enum SmartRewind {
    public static let afterSleepMs = SleepTimer.fadeMs

    public static func afterSleep(_ positionMs: Int64) -> Int64 { max(0, positionMs - afterSleepMs) }
}

/// At most one sync every `intervalMs`, and the last one asked for always
/// runs: the listening place goes to the hub no faster than every 15 seconds.
/// The clock is the caller's, any that only moves forward.
public struct SyncThrottle: Sendable {
    public let intervalMs: Int64
    private var last: Int64?

    public init(intervalMs: Int64) {
        self.intervalMs = intervalMs
    }

    /// How long a sync asked for at `now` waits: none for the first, else
    /// until `intervalMs` after the last.
    public func waitFor(now: Int64) -> Int64 {
        guard let last else { return 0 }
        return max(0, last + intervalMs - now)
    }

    /// A sync ran at `now`.
    public mutating func ran(now: Int64) { last = now }
}

/// What the app can be heard playing.
public enum AudioSource: Hashable, Sendable {
    case video, narration, audiobook
}

/// One sound at a time: video, a book's narration and an audiobook never play
/// over each other. Each player says when it starts and stops; a start
/// answers the others that were playing, for the caller to pause.
public struct AudioArbiter: Sendable {
    private var playing: [AudioSource] = []

    public init() {}

    /// What is sounding now, the latest last.
    public var sounding: Set<AudioSource> { Set(playing) }

    /// `source` starts: everything else that was playing must pause, and no longer counts as playing.
    public mutating func start(_ source: AudioSource) -> Set<AudioSource> {
        let others = Set(playing.filter { $0 != source })
        playing = [source]
        return others
    }

    public mutating func stop(_ source: AudioSource) {
        playing.removeAll { $0 == source }
    }
}

extension PlayerLabels {
    /// A listening speed: "1×", "1.25×", "0.75×", never "2.0×".
    public static func rate(_ value: Float) -> String {
        let text: String
        if value == value.rounded() {
            text = String(Int(value))
        } else {
            var decimals = String(format: "%.2f", locale: Locale(identifier: "en_US_POSIX"), Double(value))
            while decimals.hasSuffix("0") { decimals.removeLast() }
            if decimals.hasSuffix(".") { decimals.removeLast() }
            text = decimals
        }
        return text + "×"
    }

    /// What is left as heard at the speed playing: "12 min left in part · 4h
    /// 10m in book"; the book's part drops while its lengths are unknown.
    public static func timeLeft(partLeftMs: Int64, bookLeftMs: Int64?) -> String {
        var parts = ["\(Fmt.runtime(max(partLeftMs / 1_000, 60))) left in part"]
        if let bookLeftMs { parts.append("\(Fmt.runtime(max(bookLeftMs / 1_000, 60))) in book") }
        return parts.joined(separator: " · ")
    }

    /// The sleep timer on its control: "Sleep", "Sleep · 14:32", "Sleep · end of part", fading.
    public static func sleep(_ timer: SleepTimer?) -> String {
        guard let timer else { return "Sleep" }
        if timer.fading { return "Sleep · fading" }
        if timer.choice == .endOfPart && timer.skipParts == 0 { return "Sleep · end of part" }
        return "Sleep · " + Fmt.clock(timer.remainingMs)
    }

    /// A sleep timer to choose: "15 minutes", "1 hour", "End of this part".
    public static func sleepChoice(_ choice: SleepChoice) -> String {
        switch choice {
        case .minutes(let minutes): minutes == 60 ? "1 hour" : "\(minutes) minutes"
        case .endOfPart: "End of this part"
        }
    }
}
