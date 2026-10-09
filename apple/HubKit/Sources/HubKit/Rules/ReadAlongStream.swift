import Foundation

/// Where a stretch of read along's narration is heard (#19): a hub track
/// (`url`, which only the hub's paths build; a tone the demo writes) and
/// where in it the stretch's audio file begins (`startMs`, the file's start
/// within the track). The player clips each stretch from `startMs` plus its
/// first sentence's begin.
public struct NarrationSource: Equatable, Sendable {
    public let url: String
    public let startMs: Int64
    /// The audiobook's own key for the track's bytes (`AudiobookStream.cacheKey`).
    public let cacheKey: String

    public init(url: String, startMs: Int64 = 0, cacheKey: String = "") {
        self.url = url
        self.startMs = startMs
        self.cacheKey = cacheKey
    }

    /// Where the stretch is heard in its source: from its first sentence to its last one's end.
    public func clip(_ track: ReadAlongTrack) -> (fromMs: Int64, toMs: Int64) {
        (startMs + track.startMs, startMs + track.startMs + track.durationMs)
    }
}

/// How read along gets its words and its narration (#19).
public enum NarrationPlan: Equatable, Sendable {
    /// The edition without its audio, the narration streamed from the audiobook's tracks.
    case stream(ReadingAudioManifest)
    /// The whole edition, its audio inside: what the hub cannot stream.
    case whole
    /// The hub could not be asked.
    case unreachable(HubFailure)
}

/// Why read along's narration cannot be put together.
public struct ReadAlongError: Error, Equatable, Sendable {
    public let message: String

    public init(_ message: String) {
        self.message = message
    }
}

/// Read along streamed (#19): the slim edition's words and SMIL, and the
/// narration from the audiobook's own tracks, mapped by the hub, so the audio
/// is neither downloaded twice (293 MB of Dark Matter's 294 was audio) nor
/// taken out of the edition on the device. Android's `ReadAlongStream`.
public enum ReadAlongStream {
    /// What to open: the stream when the hub mapped the edition's audio onto
    /// the tracks; the whole edition when it cannot stream this book (an
    /// edition it could not map, a missing route, `audio_not_streamable`);
    /// what is here when it could not be asked.
    public static func plan(_ answer: Result<ReadingAudioManifest, HubFailure>) -> NarrationPlan {
        switch answer {
        case .success(let manifest):
            let mapped = manifest.aligned && !(manifest.alignment?.audio.isEmpty ?? true) && AudiobookStream.playable(manifest)
            return mapped ? .stream(manifest) : .whole
        case .failure(let failure):
            return AudiobookStream.downloadsWhole(failure) ? .whole : .unreachable(failure)
        }
    }

    /// Each stretch of `timeline` where it is heard: the track the hub mapped
    /// its audio file to, under the manifest's revision (`url`, which only the
    /// hub's paths build), and where in the track that file begins. Fails when
    /// the edition names a file the hub did not map: a sentence heard from the
    /// wrong place is worse than no narration.
    public static func sources(_ timeline: ReadAlongTimeline, manifest: ReadingAudioManifest, sourceItemId: String,
                               url: (Int) -> String) throws(ReadAlongError) -> [NarrationSource] {
        var mapped: [String: ReadingAlignedAudio] = [:]
        for audio in manifest.alignment?.audio ?? [] { mapped[trimmed(audio.href)] = audio }
        var out: [NarrationSource] = []
        for stretch in timeline.tracks {
            guard let aligned = mapped[trimmed(stretch.audioHref)] else {
                throw ReadAlongError("The hub did not map \(stretch.audioHref)")
            }
            guard manifest.tracks.indices.contains(aligned.track) else {
                throw ReadAlongError("The hub mapped \(stretch.audioHref) to no track")
            }
            let track = manifest.tracks[aligned.track]
            out.append(NarrationSource(url: url(track.index), startMs: max(0, aligned.startMs),
                                       cacheKey: AudiobookStream.cacheKey(sourceItemId: sourceItemId, track: track)))
        }
        return out
    }

    /// `timeline` as the mapped audio can play it: a sentence that begins at
    /// or past the end of its file's audio is skipped, and one that runs past
    /// it ends there, rather than the whole edition refused or the voice
    /// reading into the next file's words. A file's audio is its window of the
    /// track the hub mapped it to: from its `startMs` to the next file mapped
    /// to that track, else to the track's end. A file the hub did not map, or
    /// on a track of unknown length, is left as it is (`sources` refuses the
    /// first). A stretch left with no sentence goes; nil when none is left.
    public static func fitted(_ timeline: ReadAlongTimeline, manifest: ReadingAudioManifest) -> ReadAlongTimeline? {
        let files = manifest.alignment?.audio ?? []
        func length(_ href: String) -> Int64? {
            guard let file = files.first(where: { trimmed($0.href) == trimmed(href) }),
                  manifest.tracks.indices.contains(file.track) else { return nil }
            let start = max(0, file.startMs)
            if let next = files.filter({ $0.track == file.track && $0.startMs > file.startMs }).map(\.startMs).min() {
                return next - start
            }
            let duration = manifest.tracks[file.track].durationMs
            return duration > start ? duration - start : nil
        }
        var tracks: [ReadAlongTrack] = []
        for track in timeline.tracks {
            guard let end = length(track.audioHref) else {
                tracks.append(track)
                continue
            }
            let kept = track.segments.compactMap { segment -> ReadAlongSegment? in
                guard segment.beginMs < end else { return nil }
                return ReadAlongSegment(textHref: segment.textHref, fragment: segment.fragment, audioHref: segment.audioHref,
                                        beginMs: segment.beginMs, endMs: min(segment.endMs, end))
            }
            if !kept.isEmpty { tracks.append(ReadAlongTrack(audioHref: track.audioHref, segments: kept)) }
        }
        return tracks.isEmpty ? nil : ReadAlongTimeline(tracks: tracks, words: timeline.words)
    }

    private static func trimmed(_ href: String) -> String {
        String(href.drop { $0 == "/" })
    }
}

extension AudiobookStream {
    /// The hub cannot stream this book, so the whole edition is what there is:
    /// it said so (`audio_not_streamable`), or it has no such route.
    public static func downloadsWhole(_ failure: HubFailure) -> Bool {
        failure.code == notStreamable || failure.kind == .notFound
    }
}

/// How the narration is played (#19): the stretches heard one after another
/// from one source make one run, which plays on without a stop. An edition
/// cuts a track's audio into files (Dark Matter's second track is two), and
/// each file is a stretch, so most of a track is one run. A new source, a
/// gap of more than `joinGapMs` or a step back in time (narration spoken out
/// of order) starts the next run, which the player loads or seeks to.
public struct NarrationRuns: Equatable, Sendable {
    public struct Run: Equatable, Sendable {
        public let url: String
        public let cacheKey: String
        /// The stretches it plays, in order.
        public let stretches: Range<Int>
        /// Where in its source it starts and ends.
        public let fromMs: Int64
        public let toMs: Int64
    }

    /// A pause this long between two stretches of one source is played through.
    public static let joinGapMs: Int64 = 2_000

    public let runs: [Run]
    /// Each stretch's place in its source, and its run.
    private let fromMs: [Int64]
    private let durationMs: [Int64]
    private let runOf: [Int]

    /// `sources` are the stretches' own, one each.
    public init(_ timeline: ReadAlongTimeline, sources: [NarrationSource]) {
        precondition(sources.count == timeline.tracks.count, "A source for every stretch")
        var runs: [Run] = []
        var runOf: [Int] = []
        var from: [Int64] = []
        var length: [Int64] = []
        var start = 0
        for index in timeline.tracks.indices {
            let clip = sources[index].clip(timeline.tracks[index])
            from.append(clip.fromMs)
            length.append(timeline.tracks[index].durationMs)
            if index > start {
                let before = sources[index - 1].clip(timeline.tracks[index - 1])
                let joins = sources[index].url == sources[index - 1].url && clip.fromMs >= before.toMs
                    && clip.fromMs - before.toMs <= Self.joinGapMs
                if !joins {
                    runs.append(Self.run(start..<index, sources: sources, from: from, length: length))
                    start = index
                }
            }
            runOf.append(runs.count)
        }
        if !timeline.tracks.isEmpty { runs.append(Self.run(start..<timeline.tracks.count, sources: sources, from: from, length: length)) }
        self.runs = runs
        self.runOf = runOf
        fromMs = from
        durationMs = length
    }

    private static func run(_ stretches: Range<Int>, sources: [NarrationSource], from: [Int64], length: [Int64]) -> Run {
        let last = stretches.upperBound - 1
        return Run(url: sources[stretches.lowerBound].url, cacheKey: sources[stretches.lowerBound].cacheKey,
                   stretches: stretches, fromMs: from[stretches.lowerBound], toMs: from[last] + length[last])
    }

    /// The run that plays `stretch`.
    public func run(of stretch: Int) -> Int? { runOf.indices.contains(stretch) ? runOf[stretch] : nil }

    /// Where in its run's source a place is heard: the run and the moment.
    public func time(of position: ReadAlongPosition) -> (run: Int, ms: Int64)? {
        guard let run = run(of: position.track) else { return nil }
        return (run, fromMs[position.track] + min(max(position.offsetMs, 0), durationMs[position.track]))
    }

    /// The place a moment of run `run`'s source is: the last stretch begun by
    /// then, and how far into it, no further than its end in a pause after it.
    public func position(run: Int, ms: Int64) -> ReadAlongPosition {
        guard runs.indices.contains(run) else { return ReadAlongPosition(track: 0, offsetMs: 0) }
        let stretches = runs[run].stretches
        let stretch = stretches.last { fromMs[$0] <= ms } ?? stretches.lowerBound
        return ReadAlongPosition(track: stretch, offsetMs: min(max(ms - fromMs[stretch], 0), durationMs[stretch]))
    }
}

extension ReadAlongTimeline {
    /// A jump by `deltaMs` of narration from `position`, across stretches, and
    /// no further than either end (Android's `ReadAlongPlayback.jump`): a
    /// stretch's end is a moment before it, so the place stays in it.
    public func jump(_ position: ReadAlongPosition, by deltaMs: Int64) -> ReadAlongPosition {
        guard tracks.indices.contains(position.track) else { return position }
        var target = position.track
        var offset = position.offsetMs + deltaMs
        while offset < 0 && target > 0 {
            target -= 1
            offset += tracks[target].durationMs
        }
        while offset >= tracks[target].durationMs && target < tracks.count - 1 {
            offset -= tracks[target].durationMs
            target += 1
        }
        return ReadAlongPosition(track: target, offsetMs: min(max(offset, 0), max(tracks[target].durationMs, 1) - 1))
    }
}
