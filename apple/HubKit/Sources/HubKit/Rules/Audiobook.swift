import Foundation

// An audiobook the hub streams (#25 phase 2, the hub's #19): its manifest as
// the player's parts, the Parts sheet and the steps through it, and the
// listening place as the hub keeps it. Ports of Android's `AudiobookStream`,
// `AudiobookContents`, `AudiobookArchive.partLabel` and `AudioPlace`, held to
// their tests. Apple has no whole-book download: a book the hub cannot stream
// says so.

/// One track on the player.
public struct AudiobookPart: Equatable, Sendable {
    public let title: String
    /// The track's address under the manifest's revision.
    public let url: String
    public let durationMs: Int64?
    public let bytes: Int64?
    public let trackId: String
    /// What the track's bytes are kept under on this device.
    public let cacheKey: String

    public init(title: String, url: String, durationMs: Int64? = nil, bytes: Int64? = nil, trackId: String = "",
                cacheKey: String = "") {
        self.title = title
        self.url = url
        self.durationMs = durationMs
        self.bytes = bytes
        self.trackId = trackId
        self.cacheKey = cacheKey
    }
}

/// The manifest as parts. Storyteller rebuilt a ZIP of the whole book on every
/// request; the hub serves each track by Range, so a book starts at once.
public enum AudiobookStream {
    /// The hub's code for a book whose files it cannot read.
    public static let notStreamable = "audio_not_streamable"
    /// The hub's code for a track asked for under an old revision: read the manifest again.
    public static let changed = "audio_changed"
    /// How many times in a row the manifest is read again before the player
    /// gives up on a book whose files keep changing.
    public static let maxReloads = 2

    /// The parts to play, in the manifest's order: each track's address under
    /// the manifest's revision (from `url`, which only the hub's paths build),
    /// its length and size, and the key its bytes are kept under.
    public static func parts(_ manifest: ReadingAudioManifest, sourceItemId: String,
                             url: (Int) -> String) -> [AudiobookPart] {
        manifest.tracks.map { track in
            AudiobookPart(title: track.title.isEmpty ? "Track \(track.index + 1)" : track.title,
                          url: url(track.index),
                          durationMs: track.durationMs > 0 ? track.durationMs : nil,
                          bytes: track.bytes > 0 ? track.bytes : nil,
                          trackId: track.id,
                          cacheKey: cacheKey(sourceItemId: sourceItemId, track: track))
        }
    }

    /// The file, by its id and its validator; not the address, whose revision
    /// also changes when the book's read-along edition does.
    public static func cacheKey(sourceItemId: String, track: ReadingAudioTrack) -> String {
        let etag = track.etag.trimmingCharacters(in: CharacterSet(charactersIn: "\""))
        return cachePrefix(sourceItemId) + track.id + ":" + (etag.isEmpty ? "b\(track.bytes)" : etag)
    }

    /// Every kept track of a book starts with this.
    public static func cachePrefix(_ sourceItemId: String) -> String { "reading-audio:\(sourceItemId):" }

    /// A manifest that can be played: a revision, and tracks that each have an id and a place in the route.
    public static func playable(_ manifest: ReadingAudioManifest) -> Bool {
        !manifest.revision.trimmingCharacters(in: .whitespaces).isEmpty && !manifest.tracks.isEmpty
            && manifest.tracks.allSatisfy { !$0.id.trimmingCharacters(in: .whitespaces).isEmpty && $0.index >= 0 }
    }

    /// A part's name without its audio extension ("01 Opening.wav" is "01
    /// Opening"); a name with a dot of its own keeps it. Android's
    /// `AudiobookArchive.partLabel`.
    public static func partLabel(_ title: String) -> String {
        guard let dot = title.lastIndex(of: ".") else { return title }
        let suffix = title[title.index(after: dot)...].lowercased()
        guard audioExtensions.contains(suffix), title.count > suffix.count + 1 else { return title }
        return String(title[..<dot])
    }

    private static let audioExtensions: Set<String> = ["mp3", "m4a", "m4b", "aac", "flac", "ogg", "opus", "wav"]
}

/// What an audiobook's contents list, its steps go through and its line,
/// times and timeline measure (#19, #31): the chapters the hub found, else
/// the tracks. Android's `AudiobookContents`, held to its tests.
///
/// - The book's own chapters (`source` "book"): the read-along edition's
///   table of contents, each where its narration starts, so a chapter lasts
///   from its moment to the next one's, across the tracks, and the last to
///   the end of the book. The voice before the first chapter (the credits)
///   belongs to it, so the book's first entry starts with the book.
/// - Chapter marks inside the tracks (two or more of a file's own, never file
///   names; an older hub's chapters, which say no source, are these): an
///   entry never leaves its file. A track without marks stays one entry
///   among chapters, and a track whose first mark comes after its start
///   keeps that opening as an entry of its own.
///
/// A book without chapters is its parts, as it always was.
public enum AudiobookContents {
    public struct Entry: Equatable, Sendable {
        public let title: String
        public let part: Int
        public let startMs: Int64
        public let durationMs: Int64?
        /// False for a part's own opening or a whole track among chapters.
        public let chapter: Bool

        public init(title: String, part: Int, startMs: Int64, durationMs: Int64?, chapter: Bool = false) {
            self.title = title
            self.part = part
            self.startMs = startMs
            self.durationMs = durationMs
            self.chapter = chapter
        }
    }

    /// An opening shorter than this before a track's first mark is not an entry of its own.
    public static let leadMs: Int64 = 1_000
    /// Back from further into an entry than this goes to its own start, as a player's Previous does.
    public static let restartMs: Int64 = 3_000

    public static func entries(_ parts: [AudiobookPart], partsMs: [Int64?], chapters: [ReadingAudioChapter]) -> [Entry] {
        // The player's measured length counts before the manifest's; a length of nothing is none.
        let lengths = parts.indices.map { part -> Int64? in
            let measured = partsMs.indices.contains(part) ? partsMs[part].flatMap { $0 > 0 ? $0 : nil } : nil
            return measured ?? parts[part].durationMs.flatMap { $0 > 0 ? $0 : nil }
        }
        let book = chapters.filter(\.fromBook)
        if !book.isEmpty, let entries = bookEntries(lengths: lengths, chapters: book) { return entries }
        return markEntries(parts, lengths: lengths, chapters: chapters.filter { !$0.fromBook })
    }

    /// The book's chapters across the tracks, in the order they are heard (of
    /// two at one moment, the one the hub listed first), or nil when none is a
    /// place in them. Each lasts until the next starts, the last until the
    /// book ends: unknown while a track it runs across has no length.
    private static func bookEntries(lengths: [Int64?], chapters: [ReadingAudioChapter]) -> [Entry]? {
        var seen = Set<[Int64]>()
        let marks = chapters.enumerated()
            .filter { lengths.indices.contains($0.element.track) && $0.element.startMs >= 0
                && (lengths[$0.element.track] == nil || $0.element.startMs < lengths[$0.element.track]!) }
            .sorted { ($0.element.track, $0.element.startMs, $0.offset) < ($1.element.track, $1.element.startMs, $1.offset) }
            .map(\.element)
            .filter { seen.insert([Int64($0.track), $0.startMs]).inserted }
        guard !marks.isEmpty else { return nil }
        let last = lengths.count - 1
        return marks.enumerated().map { index, mark in
            // The credits before the first chapter are its: the book's first entry starts with the book.
            let part = index == 0 ? 0 : mark.track
            let startMs = index == 0 ? 0 : mark.startMs
            let duration: Int64?
            if index + 1 < marks.count {
                duration = Listening.distance(fromPart: part, fromMs: startMs, toPart: marks[index + 1].track,
                                              toMs: marks[index + 1].startMs, partsMs: lengths)
            } else {
                duration = lengths[last].flatMap { Listening.distance(fromPart: part, fromMs: startMs, toPart: last, toMs: $0, partsMs: lengths) }
            }
            return Entry(title: name(mark, index), part: part, startMs: startMs, durationMs: duration, chapter: true)
        }
    }

    /// Marks inside their files: each track on its own, as the parts were before the book's own chapters.
    private static func markEntries(_ parts: [AudiobookPart], lengths: [Int64?], chapters: [ReadingAudioChapter]) -> [Entry] {
        parts.indices.flatMap { part -> [Entry] in
            let title = AudiobookStream.partLabel(parts[part].title)
            let length = lengths[part]
            var seen = Set<Int64>()
            // In time, and of two at one moment the one the hub listed first.
            let marks = chapters.enumerated()
                .filter { $0.element.track == part && $0.element.startMs >= 0 && (length == nil || $0.element.startMs < length!) }
                .sorted { ($0.element.startMs, $0.offset) < ($1.element.startMs, $1.offset) }
                .map(\.element)
                .filter { seen.insert($0.startMs).inserted }
            guard let first = marks.first else { return [Entry(title: title, part: part, startMs: 0, durationMs: length)] }
            var out: [Entry] = []
            if first.startMs >= leadMs { out.append(Entry(title: title, part: part, startMs: 0, durationMs: first.startMs)) }
            for (index, mark) in marks.enumerated() {
                // A first mark a moment in is the track's start: every track's first entry starts at 0.
                let start = index == 0 && mark.startMs < leadMs ? 0 : mark.startMs
                let end = index + 1 < marks.count ? marks[index + 1].startMs : length
                out.append(Entry(title: name(mark, index), part: part, startMs: start, durationMs: end.map { $0 - start },
                                 chapter: true))
            }
            return out
        }
    }

    private static func name(_ chapter: ReadingAudioChapter, _ index: Int) -> String {
        chapter.title.trimmingCharacters(in: .whitespaces).isEmpty ? "Chapter \(index + 1)" : chapter.title
    }

    /// What an entry is called: "chapter" where the book has chapters, else "part".
    public static func noun(_ entries: [Entry]) -> String { entries.contains(where: \.chapter) ? "chapter" : "part" }

    /// The entry playing at `positionMs` of `part`: the last one begun, wherever its track began.
    public static func current(_ entries: [Entry], part: Int, positionMs: Int64) -> Int {
        max(0, entries.lastIndex { $0.part < part || ($0.part == part && $0.startMs <= positionMs) } ?? -1)
    }

    /// Where the steps go from `part` at `positionMs`: forward, the next
    /// entry, or nothing at the end; back, the entry's own start once
    /// `restartMs` into it, else the one before. How far into an entry that
    /// began in an earlier track is counted across the tracks (`partsMs`), and
    /// is taken as well in while a length it needs is unknown.
    public static func step(_ entries: [Entry], part: Int, positionMs: Int64, delta: Int, partsMs: [Int64?] = []) -> Entry? {
        guard !entries.isEmpty, delta != 0 else { return nil }
        let here = current(entries, part: part, positionMs: positionMs)
        if delta > 0 { return entries.indices.contains(here + delta) ? entries[here + delta] : nil }
        let entry = entries[here]
        let into = Listening.distance(fromPart: entry.part, fromMs: entry.startMs, toPart: part, toMs: positionMs, partsMs: partsMs)
            ?? Int64.max
        if into > restartMs { return entry }
        return entries.indices.contains(here + delta) ? entries[here + delta] : entry
    }

    /// Whether the entry playing at the end of `part`, `lengthMs` in, ends
    /// there: the next part begins another. A part always does; a chapter that
    /// runs on into the next track does not, so a timer for the end of the
    /// chapter goes on through the change of track.
    public static func endsWithPart(_ entries: [Entry], part: Int, lengthMs: Int64) -> Bool {
        entries.isEmpty || current(entries, part: part, positionMs: lengthMs) != current(entries, part: part + 1, positionMs: 0)
    }

    /// What the line under the title, its two times, the timeline, the time
    /// left and the lock screen measure (#31): the entry playing, a chapter
    /// across tracks or a part, while its length is known (`measuresEntry`);
    /// else the part playing, by the player's own length, the entry still named.
    public struct Span: Equatable, Sendable {
        /// The entry's place in the contents, or -1 when there is none.
        public let entry: Int
        public let title: String
        /// Where what is measured starts: the entry's, or the part's.
        public let part: Int
        public let startMs: Int64
        /// How far into it, within its length.
        public let positionMs: Int64
        public let durationMs: Int64
        public let measuresEntry: Bool

        public init(entry: Int, title: String, part: Int, startMs: Int64, positionMs: Int64, durationMs: Int64,
                    measuresEntry: Bool) {
            self.entry = entry
            self.title = title
            self.part = part
            self.startMs = startMs
            self.positionMs = positionMs
            self.durationMs = durationMs
            self.measuresEntry = measuresEntry
        }

        /// What is left of it, of the recording.
        public var leftMs: Int64 { max(0, durationMs - positionMs) }
    }

    /// The span at `positionMs` of `part`; `partMs` is the player's length of
    /// the part playing, which counts before the manifest's (`partsMs`).
    public static func span(_ entries: [Entry], part: Int, positionMs: Int64, partMs: Int64, partsMs: [Int64?]) -> Span {
        let position = max(0, positionMs)
        // The part's own length, as the player has it, or none while the player has none.
        func ofPart(_ entry: Int, _ title: String) -> Span {
            Span(entry: entry, title: title, part: part, startMs: 0, positionMs: partMs > 0 ? min(position, partMs) : position,
                 durationMs: max(0, partMs), measuresEntry: false)
        }
        guard !entries.isEmpty else { return ofPart(-1, "") }
        var lengths = partsMs
        if partMs > 0 && lengths.indices.contains(part) { lengths[part] = partMs }
        let here = current(entries, part: part, positionMs: position)
        let entry = entries[here]
        let total: Int64?
        if entries.indices.contains(here + 1) {
            let next = entries[here + 1]
            total = Listening.distance(fromPart: entry.part, fromMs: entry.startMs, toPart: next.part, toMs: next.startMs,
                                       partsMs: lengths)
        } else {
            let last = lengths.count - 1
            total = last >= 0 ? lengths[last].flatMap {
                Listening.distance(fromPart: entry.part, fromMs: entry.startMs, toPart: last, toMs: $0, partsMs: lengths)
            } : nil
        }
        let into = Listening.distance(fromPart: entry.part, fromMs: entry.startMs, toPart: part, toMs: position, partsMs: lengths)
        guard let total, total > 0, let into else { return ofPart(here, entry.title) }
        return Span(entry: here, title: entry.title, part: entry.part, startMs: entry.startMs, positionMs: min(into, total),
                    durationMs: total, measuresEntry: true)
    }

    /// Where `ms` into `span` is: its part and the moment in it, across tracks
    /// when the span is a chapter's.
    public static func place(_ span: Span, at ms: Int64, partsMs: [Int64?]) -> (part: Int, offsetMs: Int64) {
        let into = min(max(0, ms), max(0, span.durationMs))
        guard span.measuresEntry else { return (span.part, into) }
        return Listening.jump(part: span.part, positionMs: span.startMs, by: into, partsMs: partsMs)
    }
}

/// A listening place as the hub keeps it: a track of the manifest, by its id,
/// which survives a new order, and a moment in it. It is the `audio`
/// checkpoint's location: kept here first, then sent through the outbox
/// checked against the place last read (`expected`).
///
/// A place is kept exactly as the hub will read it back (`canonical`),
/// because the outbox decides by equality: a moment past a track's end is its
/// end, and the last two seconds of the book are the book finished, which the
/// hub writes as the end of its last track. Nothing here reads a timestamp.
///
/// This device also keeps beside a place how far through the whole book it
/// is (`progress`, 0 to 1; #30), so a place still waiting to be sent shows as
/// a share of the book on its page and on Books Home before the hub has it: a
/// place is a track and a moment, and says nothing of that by itself. It is a
/// note, not part of the place. The hub never reads it back and `body` never
/// sends it, so the outbox compares places by track, moment and finish alone
/// (`samePlace`); compared whole, the hub's own reading of the place just
/// sent would look like another device moving the book.
public struct AudioPlace: Equatable, Hashable, Sendable {
    public let trackId: String
    public let offsetMs: Int64
    public let completed: Bool

    public init(_ trackId: String, _ offsetMs: Int64, completed: Bool = false) {
        precondition(!trackId.trimmingCharacters(in: .whitespaces).isEmpty && offsetMs >= 0, "A place is a track and a moment in it")
        self.trackId = trackId
        self.offsetMs = offsetMs
        self.completed = completed
    }

    /// The checkpoint kind a listening place is kept under.
    public static let kind = "audio"
    /// The hub's own rule: the last two seconds of the last track are the end of the book.
    public static let finishedMs: Int64 = 2_000

    /// How far through the book, beside the place on this device (#30): not the hub's, so never sent.
    static let progressKey = "progress"

    /// The place as a location. `progress` is how far through the whole book
    /// it is, kept beside the track and the moment on this device alone; it is
    /// left out where it is not known, and for the hub's own reading of a
    /// place, which has none.
    public func location(progress: Double? = nil) -> ReadingLocation {
        var locator: [String: JSONValue] = ["trackId": .string(trackId), "offsetMs": .int(offsetMs), "completed": .bool(completed)]
        if let progress, progress.isFinite { locator[Self.progressKey] = .double(min(max(progress, 0), 1)) }
        return ReadingLocation(locator: locator)
    }

    /// How far through the whole book this place is, 0 to 1: 1 once it is
    /// finished, else the tracks before its own and its moment over every
    /// track's length (`Listening.bookProgress`). Nil when a track's length is
    /// unknown or its track is not in `tracks`.
    public func progress(_ tracks: [ReadingAudioTrack]) -> Double? {
        if completed { return 1 }
        return Listening.bookProgress(part: tracks.firstIndex { $0.id == trackId } ?? -1, positionMs: offsetMs,
                                      partsMs: tracks.map { $0.durationMs > 0 ? $0.durationMs : nil })
    }

    /// Where to open: the part and the moment, or the start of the book once it is finished.
    public func openAt(_ tracks: [ReadingAudioTrack]) -> (part: Int, offsetMs: Int64) {
        if completed { return (0, 0) }
        guard let part = tracks.firstIndex(where: { $0.id == trackId }) else { return (0, 0) }
        let length = tracks[part].durationMs
        return (part, length > 0 ? min(max(offsetMs, 0), length) : offsetMs)
    }

    /// The place a checkpoint holds, or nil for a location of another kind.
    public static func of(_ location: ReadingLocation?) -> AudioPlace? {
        guard let locator = location?.locator,
              let track = locator["trackId"]?.stringValue, !track.trimmingCharacters(in: .whitespaces).isEmpty,
              let offset = locator["offsetMs"]?.int64Value, offset >= 0 else { return nil }
        return AudioPlace(track, offset, completed: locator["completed"]?.boolValue == true)
    }

    /// The hub's answer as a place: what it sent, never its timestamp or how exact it was.
    public static func fromServer(_ position: ReadingAudioPosition) -> AudioPlace? {
        guard !position.trackId.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
        return AudioPlace(position.trackId, max(0, position.offsetMs), completed: position.completed)
    }

    /// `part` and `offsetMs` on the player as the place the hub will read
    /// back: within the track, and the book finished in the last two seconds
    /// of its last track (written as that track's end).
    public static func canonical(_ tracks: [ReadingAudioTrack], part: Int, offsetMs: Int64, completed: Bool = false) -> AudioPlace? {
        guard let last = tracks.last else { return nil }
        let finished = AudioPlace(last.id, max(0, last.durationMs), completed: true)
        if completed { return finished }
        guard tracks.indices.contains(part) else { return nil }
        let track = tracks[part]
        let offset = track.durationMs > 0 ? min(max(offsetMs, 0), track.durationMs) : max(0, offsetMs)
        if part == tracks.count - 1 && track.durationMs > 0 && offset >= track.durationMs - finishedMs { return finished }
        return AudioPlace(track.id, offset)
    }

    /// What the outbox keeps for the player at `part` and `offsetMs` (#30):
    /// the place as the hub will read it back (`canonical`), and how far
    /// through the book it is beside it. Nil where there is no place.
    public static func kept(_ tracks: [ReadingAudioTrack], part: Int, offsetMs: Int64, completed: Bool = false) -> ReadingLocation? {
        canonical(tracks, part: part, offsetMs: offsetMs, completed: completed).map { $0.location(progress: $0.progress(tracks)) }
    }

    /// How far through the whole book a kept `location` says the listener
    /// is, 0 to 1 (#30): 1 once it is finished, else the fraction kept beside
    /// it. Nil when it says nothing: a place an older build kept, or a
    /// location of another kind. Not 0, which is the start.
    public static func progressOf(_ location: ReadingLocation?) -> Double? {
        guard let place = of(location) else { return nil }
        if place.completed { return 1 }
        guard let kept = location?.locator?[progressKey]?.doubleValue, kept.isFinite else { return nil }
        return min(max(kept, 0), 1)
    }

    /// Whether two locations are the same place: the same track, moment and
    /// finish, whatever else this device keeps beside them (#30). The
    /// outbox's compare. Anything that is not a place compares as it is.
    public static func samePlace(_ one: ReadingLocation?, _ other: ReadingLocation?) -> Bool {
        guard let a = of(one) else { return one == other }
        guard let b = of(other) else { return false }
        return a == b
    }

    /// The write the outbox sends: the place, and `expected`, the place this
    /// device last read from the hub (null when it read none), which the hub
    /// checks before it writes. An unknown base sends no expectation; no clock
    /// is ever sent.
    public static func body(_ local: AudioPlace, base: AudioPlace?, baseKnown: Bool) -> JSONValue {
        var fields: [String: JSONValue] = ["trackId": .string(local.trackId), "offsetMs": .int(local.offsetMs),
                                           "completed": .bool(local.completed)]
        if baseKnown {
            fields["expected"] = base.map { .object(["trackId": .string($0.trackId), "offsetMs": .int($0.offsetMs)]) } ?? .null
        }
        return .object(fields)
    }

    /// The write for an `audio` checkpoint in the outbox, or nil for one that holds no place.
    public static func body(_ checkpoint: ReadingCheckpoint) -> JSONValue? {
        guard let local = of(checkpoint.local) else { return nil }
        return body(local, base: of(checkpoint.base), baseKnown: checkpoint.baseKnown)
    }

    /// A place in words for the sheet that asks which: "Part 3 of 8 · 1:02:13", "Finished".
    public static func label(_ location: ReadingLocation?, tracks: [ReadingAudioTrack]) -> String {
        guard let place = of(location) else { return location?.label() ?? "" }
        if place.completed { return "Finished" }
        guard let part = tracks.firstIndex(where: { $0.id == place.trackId }) else { return Fmt.clock(place.offsetMs) }
        return "Part \(part + 1) of \(tracks.count) · \(Fmt.clock(place.offsetMs))"
    }

    /// Some of the book has been heard: a moment past its start, or the end.
    public static func started(_ location: ReadingLocation) -> Bool {
        guard let place = of(location) else { return false }
        return place.completed || place.offsetMs > 0
    }
}

extension AudioPlace {
    /// A place the hub only worked out from a reader's page in a book it
    /// cannot align is a guess: the player asks before going there. Only when
    /// the place chosen is that answer itself.
    public static func asksBeforeJumping(to place: AudioPlace?, answered: ReadingAudioPosition?) -> Bool {
        guard let place, let answered, !answered.exact else { return false }
        return place == fromServer(answered)
    }

    /// The question it asks: "Listen from where you were reading?"
    public static func estimatePrompt(_ place: AudioPlace, tracks: [ReadingAudioTrack]) -> ReadingResumePrompt {
        ReadingResumePrompt(title: "Listen from where you were reading?",
                            message: "Worked out from your place in the book, so it may be a little off",
                            choices: [.init(id: "there", label: "Listen from there", detail: label(place.location(), tracks: tracks)),
                                      .init(id: "start", label: "Start at the beginning", detail: "")])
    }
}
