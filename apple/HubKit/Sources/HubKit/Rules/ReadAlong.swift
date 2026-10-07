import Foundation

// Read along (#25 phase 4, Android's #16 and #19): the narration of a
// read-along edition as a timeline of sentences, where a moment of it is, the
// sentence steps, what the dock and the page say, and the place as a text
// locator. Ports of Android's `ReadAlongTimeline`, `ReadAlongFollow`,
// `ReadAlongDockText`, `ReadAlongLocation` and `ReadAlongSession`, held to
// their tests.

/// A sentence the narration speaks: where its words are (`textHref`, a path
/// inside the EPUB, and the element's id), and when in which audio file it
/// is spoken.
public struct ReadAlongSegment: Equatable, Hashable, Sendable {
    public let textHref: String
    public let fragment: String
    public let audioHref: String
    public let beginMs: Int64
    public let endMs: Int64

    public init(textHref: String, fragment: String, audioHref: String, beginMs: Int64, endMs: Int64) {
        self.textHref = textHref
        self.fragment = fragment
        self.audioHref = audioHref
        self.beginMs = beginMs
        self.endMs = endMs
    }
}

/// A moment of the narration: a stretch of it (`track`) and how far into
/// the stretch, counted from its first sentence.
public struct ReadAlongPosition: Equatable, Hashable, Sendable {
    public let track: Int
    public let offsetMs: Int64

    public init(track: Int, offsetMs: Int64) {
        self.track = track
        self.offsetMs = offsetMs
    }
}

/// A stretch of the narration: sentences one after another in one audio
/// file. It is heard from its first sentence's start to its last one's end.
public struct ReadAlongTrack: Equatable, Sendable {
    public let audioHref: String
    public let segments: [ReadAlongSegment]

    public init(audioHref: String, segments: [ReadAlongSegment]) {
        precondition(!segments.isEmpty, "A stretch of narration has a sentence")
        self.audioHref = audioHref
        self.segments = segments
    }

    public var startMs: Int64 { segments[0].beginMs }
    public var durationMs: Int64 { segments[segments.count - 1].endMs - startMs }
}

/// The narration, stretch by stretch, in the order the book is read.
public struct ReadAlongTimeline: Equatable, Sendable {
    public let tracks: [ReadAlongTrack]

    /// Further into a sentence than this, back goes to its start rather than the sentence before.
    public static let restartMs: Int64 = 1_500

    public init(tracks: [ReadAlongTrack]) {
        self.tracks = tracks
    }

    /// The sentence spoken at `offsetMs` into stretch `track`, or nil in a
    /// pause between two: the wrong sentence is never lit. A book aligned
    /// word by word holds hundreds of thousands, and this is asked on every
    /// tick of the player, so it is a binary search.
    public func active(track: Int, offsetMs: Int64) -> ReadAlongSegment? {
        guard tracks.indices.contains(track) else { return nil }
        let value = tracks[track]
        let absolute = value.startMs + offsetMs
        var low = 0
        var high = value.segments.count - 1
        while low <= high {
            let middle = (low + high) / 2
            if value.segments[middle].beginMs <= absolute { low = middle + 1 } else { high = middle - 1 }
        }
        guard high >= 0, absolute < value.segments[high].endMs else { return nil }
        return value.segments[high]
    }

    /// Where the sentence `fragment` of `href` starts.
    public func find(href: String, fragment: String) -> ReadAlongPosition? {
        for (index, track) in tracks.enumerated() {
            if let segment = track.segments.first(where: { $0.textHref == href && $0.fragment == fragment }) {
                return ReadAlongPosition(track: index, offsetMs: segment.beginMs - track.startMs)
            }
        }
        return nil
    }

    /// The sentence steps (L1 and R1 read along): where the sentence `delta`
    /// away from `position` begins, across stretches. Back from more than
    /// `restartMs` into a sentence goes to its own start first, as a player's
    /// Previous does. Nil past either end.
    public func step(_ position: ReadAlongPosition, delta: Int) -> ReadAlongPosition? {
        let all = tracks.enumerated().flatMap { index, track in track.segments.map { (index, $0) } }
        guard !all.isEmpty, delta != 0, tracks.indices.contains(position.track) else { return nil }
        let absolute = tracks[position.track].startMs + position.offsetMs
        // The sentence playing, or the last one begun before a pause.
        let here = all.lastIndex { $0.0 < position.track || ($0.0 == position.track && $0.1.beginMs <= absolute) } ?? -1
        let target: Int
        if here < 0 {
            guard delta > 0 else { return nil }
            target = delta - 1
        } else if delta < 0 && all[here].0 == position.track && absolute - all[here].1.beginMs > Self.restartMs {
            target = here + delta + 1
        } else {
            target = here + delta
        }
        guard all.indices.contains(target) else { return nil }
        let (index, segment) = all[target]
        return ReadAlongPosition(track: index, offsetMs: segment.beginMs - tracks[index].startMs)
    }

    /// Some sentence of `href`'s text is narrated: the page can be followed.
    public func narrates(_ href: String) -> Bool {
        tracks.contains { track in track.segments.contains { $0.textHref == href } }
    }
}

/// What a read-along page says about the narration: the page turns with the
/// voice, you have turned away to read on your own while it plays, or this
/// part of the book has no narration to follow.
public enum ReadAlongFollow {
    public static func label(following: Bool, narrated: Bool) -> String {
        if !narrated { return "Alignment unavailable" }
        return following ? "Following" : "Reading"
    }
}

/// The dock's words and its line (#21's lower bar).
public enum ReadAlongDockText {
    /// "4:13 of 27:05" in the stretch playing, and which of how many when there are several.
    public static func time(_ position: ReadAlongPosition, _ timeline: ReadAlongTimeline) -> String {
        guard timeline.tracks.indices.contains(position.track) else { return "0:00 of 0:00" }
        let track = timeline.tracks[position.track]
        let clock = "\(Fmt.clock(min(max(position.offsetMs, 0), track.durationMs))) of \(Fmt.clock(track.durationMs))"
        return timeline.tracks.count > 1 ? "\(clock) · part \(position.track + 1) of \(timeline.tracks.count)" : clock
    }

    /// How far through the stretch playing, 0 to 1.
    public static func fraction(_ position: ReadAlongPosition, _ timeline: ReadAlongTimeline) -> Double {
        guard timeline.tracks.indices.contains(position.track) else { return 0 }
        let track = timeline.tracks[position.track]
        guard track.durationMs > 0 else { return 0 }
        return min(max(Double(position.offsetMs) / Double(track.durationMs), 0), 1)
    }

    /// The dock's heading: "Read along", and where the page stands while it plays.
    public static func heading(follow: String) -> String {
        follow.trimmingCharacters(in: .whitespaces).isEmpty ? "Read along" : "Read along · " + follow
    }
}

/// Read along's place is a standard text locator: the sentence being read,
/// as any reader of the book understands it (#19). The hub turns a
/// listener's place into the sentence spoken there and a sentence into a
/// moment of the audiobook, so the place follows you between reading along
/// and listening. The private `pocketdsAudio` offset an older Pocket build
/// added is neither written nor read. Locators are Readium's JSON, as the
/// ebook reader keeps them (`BookLocator`).
public enum ReadAlongLocation {
    /// Where the narration resumes: the start of the sentence the locator names.
    public static func resume(_ locator: String, _ timeline: ReadAlongTimeline) -> ReadAlongPosition? {
        guard let object = BookLocator.object(locator), let href = object["href"] as? String,
              let locations = object["locations"] as? [String: Any] else { return nil }
        let fragments = (locations["fragments"] as? [Any])?.compactMap { $0 as? String } ?? []
        for fragment in fragments {
            if let position = timeline.find(href: href, fragment: fragment) { return position }
        }
        return nil
    }

    /// The page's locator moved to the sentence playing at `point`, finished
    /// when `completed`: its part and fragment, no stale selector, no text,
    /// no private offset. A point the timeline does not hold leaves it as it was.
    public static func save(_ locator: String, _ timeline: ReadAlongTimeline, point: ReadAlongPosition,
                            completed: Bool) -> String {
        guard timeline.tracks.indices.contains(point.track), var object = BookLocator.object(locator) else { return locator }
        let track = timeline.tracks[point.track]
        let segment = timeline.active(track: point.track, offsetMs: point.offsetMs)
            ?? track.segments.last { $0.endMs <= track.startMs + point.offsetMs }
            ?? track.segments[0]
        var locations = object["locations"] as? [String: Any] ?? [:]
        locations.removeValue(forKey: "cssSelector")
        locations.removeValue(forKey: "pocketdsAudio")
        locations["fragments"] = [segment.fragment]
        if completed { locations["totalProgression"] = 1.0 }
        object["href"] = segment.textHref
        object["locations"] = locations
        object.removeValue(forKey: "text")
        return BookLocator.canonical(object) ?? locator
    }
}

/// Keeps the narration's place while Readium delivers its first page
/// callbacks on opening: those are the page Readium chose, not the reader
/// moving, and saving them would replace the place the narration opened at.
public struct ReadAlongSession: Sendable {
    private var preparing = false
    private var retained: ReadAlongPosition?

    public init() {}

    public mutating func beginOpen() { preparing = true }

    /// The narration is ready, at `resume` (nil for none).
    public mutating func ready(_ resume: ReadAlongPosition?) {
        retained = resume
        preparing = false
    }

    public mutating func endOpenIfPending() { preparing = false }

    /// A page may be saved: not while opening, and a page without narration
    /// may not replace a narration place kept.
    public func canSavePage(narrationAvailable: Bool = true) -> Bool {
        !preparing && (narrationAvailable || retained == nil)
    }

    /// The narration's place to save with the page: the moment playing, else the one kept.
    public func pointForSave(_ playing: ReadAlongPosition?) -> ReadAlongPosition? { playing ?? retained }

    public mutating func record(_ point: ReadAlongPosition) { retained = point }

    /// Reading on without the narration: its place is no longer kept.
    public mutating func switchToText() { retained = nil }
}

/// The sentence being read, as Readium draws it (#16, X7): a soft wash of
/// the accent with a glow round it, as the prototype's read-along has,
/// instead of Readium's flat box. The tint is the Books accent, handed in
/// with each highlight. Pure strings, so a test pins them; the reader gives
/// them to Readium as its decoration template. Android's `ReadAlongGlow`.
public enum ReadAlongGlow {
    public static let className = "pocket-narration"
    /// How strong the wash under the words, the ring round them and the glow beyond.
    public static let wash = 0.28
    public static let ring = 0.22
    public static let glow = 0.42

    /// Readium lays one of these over each line of the sentence. `tint` is 0xRRGGBB.
    public static func element(tint: UInt32) -> String {
        #"<div class="\#(className)" style="\#(style(tint: tint))"></div>"#
    }

    public static func style(tint: UInt32) -> String {
        "background-color: \(rgba(tint, wash)) !important; "
            + "box-shadow: 0 0 0 3px \(rgba(tint, ring)), 0 0 14px 4px \(rgba(tint, glow)) !important;"
    }

    /// The room round the words and the soft corners, as Readium's own highlight has.
    public static let stylesheet = ".\(className) { margin-left: -3px; padding-right: 6px; margin-top: -1px; padding-bottom: 2px; "
        + "border-radius: 5px; box-sizing: border-box; }"

    public static func rgba(_ color: UInt32, _ alpha: Double) -> String {
        "rgba(\((color >> 16) & 0xFF), \((color >> 8) & 0xFF), \(color & 0xFF), "
            + String(format: "%.2f", locale: Locale(identifier: "en_US_POSIX"), alpha) + ")"
    }
}

extension ReadAlongTimeline {
    /// The narrated sentences of `href`, in the order they are read, each
    /// once: what "Listen from this page" looks for on the page.
    public func fragments(in href: String) -> [String] {
        var seen = Set<String>()
        return tracks.flatMap(\.segments).filter { $0.textHref == href && seen.insert($0.fragment).inserted }.map(\.fragment)
    }
}

/// What the page is asked while it reads along (Android's `EpubReaderScreen`):
/// scripts run in the page's own web view. Ids go in as JSON, so an id with a
/// quote or a backslash in it cannot break out of its string.
public enum ReadAlongPageScript {
    /// True while the element `fragment` is on screen: the page follows the
    /// voice only when the sentence spoken is not.
    public static func visible(_ fragment: String) -> String {
        "(function(){var e=document.getElementById(\(json(fragment)));if(!e)return false;"
            + "var r=e.getBoundingClientRect();return r.bottom>0&&r.top<innerHeight&&r.right>0&&r.left<innerWidth;})()"
    }

    /// The first of `ids` on screen, or null: where "Listen from this page" starts.
    public static func firstVisible(_ ids: [String]) -> String {
        "(function(){var ids=\(json(ids));for(var i=0;i<ids.length;i++){var e=document.getElementById(ids[i]);"
            + "if(e){var r=e.getBoundingClientRect();if(r.bottom>0&&r.top<innerHeight&&r.right>0&&r.left<innerWidth)return ids[i];}}"
            + "return null;})()"
    }

    private static func json(_ value: Any) -> String {
        guard JSONSerialization.isValidJSONObject([value]),
              let data = try? JSONSerialization.data(withJSONObject: [value], options: [.withoutEscapingSlashes]),
              let text = String(data: data, encoding: .utf8) else { return "null" }
        // The value alone, out of the array it was written in.
        return String(text.dropFirst().dropLast())
    }
}

extension TimeLeft {
    /// Reading along with the page following the voice (#18, E3): what the
    /// narration has left to say in the chapter being read (the sentence's
    /// file) and in the book, at its speed. Nil for a moment the timeline
    /// does not hold. Android's `TimeLeft.ofNarration`.
    public static func ofNarration(_ timeline: ReadAlongTimeline, _ position: ReadAlongPosition, speed: Float) -> TimeLeft? {
        guard timeline.tracks.indices.contains(position.track) else { return nil }
        let track = timeline.tracks[position.track]
        let now = track.startMs + max(0, position.offsetMs)
        guard let chapter = (timeline.active(track: position.track, offsetMs: position.offsetMs)
            ?? track.segments.first { $0.endMs > now })?.textHref else { return nil }
        var inChapter: Int64 = 0
        var inBook: Int64 = 0
        for (index, value) in timeline.tracks.enumerated() where index >= position.track {
            for segment in value.segments {
                let from = index == position.track ? max(segment.beginMs, now) : segment.beginMs
                let left = segment.endMs - from
                guard left > 0 else { continue }
                inBook += left
                if segment.textHref == chapter { inChapter += left }
            }
        }
        return TimeLeft(chapterMs: Listening.heard(inChapter, speed: speed), bookMs: Listening.heard(inBook, speed: speed))
    }
}
