import Foundation

// Listening's arithmetic (#25 phase 2): the speeds, the time left as heard,
// the recording between two places and a jump across parts (#31: a chapter of
// the book's own can start in one part and run on into the next), the sleep
// timer with its fade and the step back after it, how often the place goes to
// the hub, and one sound at a time. Ports of Android's `Listening`,
// `SleepTimer`, `SmartRewind`, `SyncThrottle` and `AudioArbiter`, held to
// their tests.

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

    /// Left in the book, as heard: the rest of `part` and every part after it;
    /// nil while any of those lengths is unknown.
    public static func bookLeft(part: Int, positionMs: Int64, partsMs: [Int64?], speed: Float) -> Int64? {
        let rest = partsMs.dropFirst(max(0, part))
        guard !rest.isEmpty, rest.allSatisfy({ ($0 ?? 0) > 0 }) else { return nil }
        return heard(rest.reduce(0) { $0 + ($1 ?? 0) } - max(0, positionMs), speed: speed)
    }

    /// How far through the book `positionMs` into `part` is, 0 to 1: the parts
    /// before it and the moment in it, over every part (#30). Of the
    /// recording, not of the time it takes to hear, so a speed does not move
    /// it (Storyteller's `totalProgression` is the same sum). Nil while any
    /// part's length is unknown or there is no such part: a share nobody
    /// could count is not 0.
    public static func bookProgress(part: Int, positionMs: Int64, partsMs: [Int64?]) -> Double? {
        guard partsMs.indices.contains(part), partsMs.allSatisfy({ ($0 ?? 0) > 0 }) else { return nil }
        let lengths = partsMs.map { $0 ?? 0 }
        let heard = lengths.prefix(part).reduce(0, +) + min(max(positionMs, 0), lengths[part])
        return min(max(Double(heard) / Double(lengths.reduce(0, +)), 0), 1)
    }

    /// How much of the recording lies from `fromMs` into `fromPart` to a
    /// later place, `toMs` into `toPart`, across the parts between (#31): the
    /// rest of the first, every part after it up to the last, and the start of
    /// that one. Nil when the place comes first, and while the length of a
    /// part it crosses is unknown or none: nothing to say, which is not 0.
    /// Within one part no length is needed.
    public static func distance(fromPart: Int, fromMs: Int64, toPart: Int, toMs: Int64, partsMs: [Int64?]) -> Int64? {
        if fromPart == toPart { return toMs >= fromMs ? toMs - fromMs : nil }
        guard fromPart < toPart, fromPart >= 0, toPart <= partsMs.count else { return nil }
        var total = toMs - fromMs
        for part in fromPart..<toPart {
            guard let length = partsMs[part], length > 0 else { return nil }
            total += length
        }
        return total >= 0 ? total : nil
    }

    /// A jump by `deltaMs` of recording from `positionMs` of `part`, across
    /// parts (Android's `Listening.jump`): back into the part before, on into
    /// the next, and no further than a length that is not known yet (the part
    /// playing is the one the player knows, `currentPartMs`); going back, the
    /// start of the part after one of unknown length is as far as it can
    /// count. The transport's jumps, the step back over what faded and where a
    /// moment of a chapter that spans parts is all walk this way.
    public static func jump(part: Int, positionMs: Int64, by deltaMs: Int64, partsMs: [Int64?],
                            currentPartMs: Int64? = nil) -> (part: Int, offsetMs: Int64) {
        var target = part
        var offset = positionMs + deltaMs
        while offset < 0 && target > 0 {
            // Back through a part whose length is not known would land anywhere, the book's start among it.
            guard partsMs.indices.contains(target - 1), let length = partsMs[target - 1], length > 0 else { return (target, 0) }
            target -= 1
            offset += length
        }
        while target < partsMs.count - 1 {
            let known = partsMs.indices.contains(target) ? partsMs[target].flatMap { $0 > 0 ? $0 : nil } : nil
            let length = known ?? (target == part ? currentPartMs.flatMap { $0 > 0 ? $0 : nil } : nil)
            guard let length, offset >= length else { break }
            offset -= length
            target += 1
        }
        return (target, max(0, offset))
    }
}

/// What the sleep timer was set to: minutes of listening, or the end of what
/// is playing, the chapter where the book has chapters (#31), else the part.
public enum SleepChoice: Equatable, Hashable, Sendable {
    case minutes(Int)
    case endOfPart

    public static let all: [SleepChoice] = [.minutes(5), .minutes(15), .minutes(30), .minutes(45), .minutes(60), .endOfPart]
}

/// The sleep timer. It counts only while something plays; over its last
/// `fadeMs` the volume falls to nothing, and when it runs out playback pauses
/// and steps back over what faded. Any control while it fades keeps you
/// listening: minutes start again, the end of a part becomes the next one's.
///
/// "The end of a part" is the end of the entry playing, a chapter where the
/// book has chapters and else a part (#31): `entry` is its place in the
/// contents, so the timer knows when what it counts to has changed under it.
public struct SleepTimer: Equatable, Sendable {
    public static let fadeMs: Int64 = 30_000
    /// The timer has not been told which entry it counts to.
    public static let noEntry = -1
    /// Within this of the end at the last tick, and in a later entry now: the end passed between the ticks.
    public static let crossedSlackMs: Int64 = 1_000

    public let choice: SleepChoice
    public let remainingMs: Int64
    public let skipParts: Int
    public let entry: Int

    public init(choice: SleepChoice, remainingMs: Int64, skipParts: Int = 0, entry: Int = SleepTimer.noEntry) {
        self.choice = choice
        self.remainingMs = remainingMs
        self.skipParts = skipParts
        self.entry = entry
    }

    public static func start(_ choice: SleepChoice, entryLeftHeardMs: Int64, entry: Int = noEntry) -> SleepTimer {
        switch choice {
        case .minutes(let minutes): SleepTimer(choice: choice, remainingMs: Int64(minutes) * 60_000, entry: entry)
        case .endOfPart: SleepTimer(choice: choice, remainingMs: entryLeftHeardMs, entry: entry)
        }
    }

    /// Faded, and pausing now.
    public var runsOut: Bool { remainingMs <= 0 }

    /// In its last `fadeMs`.
    public var fading: Bool { remainingMs > 0 && remainingMs < Self.fadeMs }

    /// Stops as the entry playing ends: where it ends with its part, the
    /// player moving on is the moment, however the last moments fell between two ticks.
    public var endsWithPart: Bool { choice == .endOfPart && skipParts == 0 }

    /// How loud while it counts: full, then falling over the fade.
    public var volume: Float { min(max(Float(remainingMs) / Float(Self.fadeMs), 0), 1) }

    /// `elapsedMs` of listening later, with `entryLeftHeardMs` left of the
    /// entry playing (a chapter, else a part) and `entry` its place in the
    /// contents (nil: the one it had). A minutes timer counts down; the end of
    /// an entry is read from the player.
    ///
    /// A chapter ends between two ticks, where the player reports nothing (a
    /// chapter inside a file has no event), so the timer remembers which entry
    /// it counted to: finding itself in a later one when it was within a tick
    /// of the end, it has played on past it, and it is asleep. Any other move,
    /// a jump to another chapter or back, is followed.
    public func tick(elapsedMs: Int64, entryLeftHeardMs: Int64, entry: Int? = nil) -> SleepTimer {
        let elapsed = max(0, elapsedMs)
        let now = entry ?? self.entry
        switch choice {
        case .minutes:
            return SleepTimer(choice: choice, remainingMs: remainingMs - elapsed, skipParts: skipParts, entry: now)
        case .endOfPart:
            let moved = self.entry != Self.noEntry && now != self.entry
            if skipParts > 0 {
                // Carried past an end: it counts itself down until the entry it was carried to begins, then to that one's end.
                guard moved else {
                    return SleepTimer(choice: choice, remainingMs: remainingMs - elapsed, skipParts: skipParts, entry: self.entry)
                }
                let next = partChanged(entryLeftHeardMs: entryLeftHeardMs)
                return SleepTimer(choice: choice, remainingMs: next.remainingMs, skipParts: next.skipParts, entry: now)
            }
            if moved && now > self.entry && remainingMs <= elapsed + Self.crossedSlackMs {
                return SleepTimer(choice: choice, remainingMs: 0, skipParts: skipParts, entry: now)
            }
            return SleepTimer(choice: choice, remainingMs: entryLeftHeardMs, skipParts: skipParts, entry: now)
        }
    }

    /// A control while it fades: carry on listening.
    public func extended(entryLeftHeardMs: Int64, nextEntryHeardMs: Int64?) -> SleepTimer {
        switch choice {
        case .minutes(let minutes):
            return SleepTimer(choice: choice, remainingMs: Int64(minutes) * 60_000, skipParts: skipParts, entry: entry)
        case .endOfPart:
            // Still awake at the end of this entry: stop at the end of the next one.
            return SleepTimer(choice: choice, remainingMs: entryLeftHeardMs + (nextEntryHeardMs ?? 0), skipParts: skipParts + 1,
                              entry: entry)
        }
    }

    /// The entry changed under a timer carried past it: count to the end of the new one.
    public func partChanged(entryLeftHeardMs: Int64) -> SleepTimer {
        guard choice == .endOfPart, skipParts > 0 else { return self }
        return SleepTimer(choice: choice, remainingMs: entryLeftHeardMs, skipParts: skipParts - 1, entry: entry)
    }
}

/// Where to pick up after the sleep timer stopped playback: back over what faded.
public enum SmartRewind {
    public static let afterSleepMs = SleepTimer.fadeMs

    /// `positionMs` into `part`, back by `afterSleepMs` of recording: into
    /// the part before when the part began less than that ago (a chapter can
    /// end just past a track's start, and what faded was in the track before).
    public static func afterSleep(part: Int, positionMs: Int64, partsMs: [Int64?]) -> (part: Int, offsetMs: Int64) {
        Listening.jump(part: part, positionMs: positionMs, by: -afterSleepMs, partsMs: partsMs)
    }
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
    /// 10m in book", or "in chapter" where the book has chapters (#31,
    /// `noun`); the book's part drops while its lengths are unknown.
    public static func timeLeft(entryLeftMs: Int64, bookLeftMs: Int64?, noun: String = "part") -> String {
        var parts = ["\(Fmt.runtime(max(entryLeftMs / 1_000, 60))) left in \(noun)"]
        if let bookLeftMs { parts.append("\(Fmt.runtime(max(bookLeftMs / 1_000, 60))) in book") }
        return parts.joined(separator: " · ")
    }

    /// The mini player's time, "4h 10m left", and nothing while no length is known.
    public static func leftLine(_ leftMs: Int64?) -> String {
        leftMs.map { "\(Fmt.runtime(max($0 / 1_000, 60))) left" } ?? ""
    }

    /// The sleep timer on its control: "Sleep", "Sleep · 14:32", "Sleep · end of part" (or chapter), fading.
    public static func sleep(_ timer: SleepTimer?, noun: String = "part") -> String {
        guard let timer else { return "Sleep" }
        if timer.fading { return "Sleep · fading" }
        if timer.choice == .endOfPart && timer.skipParts == 0 { return "Sleep · end of \(noun)" }
        return "Sleep · " + Fmt.clock(timer.remainingMs)
    }

    /// A sleep timer to choose: "15 minutes", "1 hour", "End of this part" (or chapter).
    public static func sleepChoice(_ choice: SleepChoice, noun: String = "part") -> String {
        switch choice {
        case .minutes(let minutes): minutes == 60 ? "1 hour" : "\(minutes) minutes"
        case .endOfPart: "End of this \(noun)"
        }
    }
}
