import Foundation
import Testing
@testable import HubKit

/// An audiobook's manifest as the player's parts, its chapters as the Parts
/// sheet, and the listening place as the hub keeps it (#25 phase 2):
/// Android's `AudiobookStreamTest`, `AudioPlaceTest` and the part label of
/// `AudiobookArchiveTest`.
struct AudiobookTests {
    private let manifest = ReadingAudioManifest(
        workId: "rw_1", sourceItemId: "3726292328809367", revision: "05c8b6c63e2b", totalMs: 1_800_000,
        tracks: [ReadingAudioTrack(index: 0, id: "t_aaaaaaaaaaaa", title: "Track 01", durationMs: 600_000, bytes: 4_800_000,
                                   mime: "audio/mpeg", etag: "\"e1\""),
                 ReadingAudioTrack(index: 1, id: "t_bbbbbbbbbbbb", title: "", durationMs: 1_200_000, bytes: 9_600_000,
                                   mime: "audio/mpeg", etag: "")])

    private func url(_ index: Int) -> String {
        "https://hub/v1/reading/works/rw_1/publications/3726292328809367/audio/tracks/\(index)?rev=05c8b6c63e2b"
    }

    // MARK: The parts

    @Test func eachTrackIsAPartWithItsAddressLengthSizeAndKeyInTheManifestsOrder() {
        let parts = AudiobookStream.parts(manifest, sourceItemId: "3726292328809367", url: url)
        #expect(parts.map(\.url) == [url(0), url(1)])
        #expect(parts.map(\.title) == ["Track 01", "Track 2"])
        #expect(parts.map(\.durationMs) == [600_000, 1_200_000])
        #expect(parts.map(\.bytes) == [4_800_000, 9_600_000])
        #expect(parts.map(\.trackId) == ["t_aaaaaaaaaaaa", "t_bbbbbbbbbbbb"])
        // The bytes are kept by the file (id and validator), not by the address's revision.
        #expect(parts[0].cacheKey == "reading-audio:3726292328809367:t_aaaaaaaaaaaa:e1")
        #expect(parts[1].cacheKey == "reading-audio:3726292328809367:t_bbbbbbbbbbbb:b9600000")
        var revised = manifest
        revised.revision = "0123456789ab"
        #expect(AudiobookStream.parts(revised, sourceItemId: "3726292328809367") { "\($0)" }.map(\.cacheKey) == parts.map(\.cacheKey))
        #expect(parts.allSatisfy { $0.cacheKey.hasPrefix(AudiobookStream.cachePrefix("3726292328809367")) })
    }

    @Test func aManifestPlaysOnlyWithARevisionAndTracksThatHaveIds() {
        #expect(AudiobookStream.playable(manifest))
        var unrevised = manifest
        unrevised.revision = ""
        #expect(!AudiobookStream.playable(unrevised))
        var empty = manifest
        empty.tracks = []
        #expect(!AudiobookStream.playable(empty))
        var nameless = manifest
        nameless.tracks[0].id = ""
        #expect(!AudiobookStream.playable(nameless))
    }

    @Test func aPartIsShownWithoutItsAudioExtension() {
        #expect(AudiobookStream.partLabel("01 Opening.wav") == "01 Opening")
        #expect(AudiobookStream.partLabel("Chapter 1. Intro.MP3") == "Chapter 1. Intro")
        // Only an audio extension goes: a name with a dot of its own keeps it.
        #expect(AudiobookStream.partLabel("Vol. 2") == "Vol. 2")
        #expect(AudiobookStream.partLabel(".mp3") == ".mp3")
    }

    @Test func theDemoHubsManifestReadsAsPartsTooItsTracksAddressedByTheHubsPaths() throws {
        let data = try JSONSerialization.data(withJSONObject: DemoReading.manifestFields(DemoReading.audiobooks[0]))
        let demo = try JSONDecoder().decode(ReadingAudioManifest.self, from: data)
        #expect(AudiobookStream.playable(demo))
        let parts = AudiobookStream.parts(demo, sourceItemId: demo.sourceItemId) {
            HubEndpoints.readingAudioTrack(workId: demo.workId, sourceItemId: demo.sourceItemId, index: $0, revision: demo.revision)
        }
        #expect(parts.count == demo.tracks.count)
        #expect(parts[0].url.hasSuffix("/audio/tracks/0?rev=" + demo.revision))
    }

    // MARK: The Parts sheet

    private var parts: [AudiobookPart] { AudiobookStream.parts(manifest, sourceItemId: "3726292328809367", url: url) }
    private let lengths: [Int64?] = [600_000, 1_200_000]

    @Test func withoutChaptersTheSheetListsTheParts() {
        let entries = AudiobookContents.entries(parts, partsMs: lengths, chapters: [])
        #expect(entries.map(\.title) == ["Track 01", "Track 2"])
        #expect(entries.map(\.part) == [0, 1])
        #expect(entries.map(\.durationMs) == [600_000, 1_200_000])
    }

    @Test func chaptersInsideATrackTakeItsPlaceItsOpeningKept() {
        let chapters = [ReadingAudioChapter(title: "The ridge", startMs: 300_000, track: 1),
                        ReadingAudioChapter(title: "The pines", startMs: 60_000, track: 1),
                        ReadingAudioChapter(title: "", startMs: 900_000, track: 1),
                        ReadingAudioChapter(title: "Past the end", startMs: 5_000_000, track: 1)]
        let entries = AudiobookContents.entries(parts, partsMs: lengths, chapters: chapters)
        #expect(entries.map(\.title) == ["Track 01", "Track 2", "The pines", "The ridge", "Chapter 3"])
        #expect(entries.map(\.startMs) == [0, 0, 60_000, 300_000, 900_000])
        #expect(entries.map(\.durationMs) == [600_000, 60_000, 240_000, 600_000, 300_000])
        // A first mark a moment in is the track's start: no opening entry of half a second.
        let early = AudiobookContents.entries(parts, partsMs: lengths,
                                              chapters: [ReadingAudioChapter(title: "One", startMs: 400, track: 0),
                                                         ReadingAudioChapter(title: "Two", startMs: 200_000, track: 0)])
        #expect(early.map(\.title) == ["One", "Two", "Track 2"])
        #expect(early.map(\.startMs) == [0, 200_000, 0])
    }

    @Test func theEntryPlayingAndTheStepsThroughThem() {
        let entries = AudiobookContents.entries(parts, partsMs: lengths,
                                                chapters: [ReadingAudioChapter(title: "A", startMs: 0, track: 1),
                                                           ReadingAudioChapter(title: "B", startMs: 300_000, track: 1)])
        // Track 01, then A and B inside track 2.
        #expect(AudiobookContents.current(entries, part: 0, positionMs: 5_000) == 0)
        #expect(AudiobookContents.current(entries, part: 1, positionMs: 10_000) == 1)
        #expect(AudiobookContents.current(entries, part: 1, positionMs: 400_000) == 2)
        // Forward: the next entry, nothing past the last.
        #expect(AudiobookContents.step(entries, part: 1, positionMs: 10_000, delta: 1)?.title == "B")
        #expect(AudiobookContents.step(entries, part: 1, positionMs: 400_000, delta: 1) == nil)
        // Back: the entry's own start once three seconds in, else the one before.
        #expect(AudiobookContents.step(entries, part: 1, positionMs: 400_000, delta: -1)?.title == "B")
        #expect(AudiobookContents.step(entries, part: 1, positionMs: 301_000, delta: -1)?.title == "A")
        #expect(AudiobookContents.step(entries, part: 1, positionMs: 1_000, delta: -1)?.title == "Track 01")
        #expect(AudiobookContents.step(entries, part: 0, positionMs: 1_000, delta: -1)?.title == "Track 01")
        #expect(AudiobookContents.step([], part: 0, positionMs: 0, delta: 1) == nil)
    }

    // MARK: The place

    // Three tracks of a book: ten minutes, twenty minutes, five minutes.
    private let tracks = [ReadingAudioTrack(index: 0, id: "t_000000000001", title: "Track 01", durationMs: 600_000, bytes: 4_800_000),
                          ReadingAudioTrack(index: 1, id: "t_000000000002", title: "Track 02", durationMs: 1_200_000, bytes: 9_600_000),
                          ReadingAudioTrack(index: 2, id: "t_000000000003", title: "Track 03", durationMs: 300_000, bytes: 2_400_000)]

    @Test func aPlaceIsALocationOfItsOwnAndReadsBackTheSame() throws {
        let place = AudioPlace("t_000000000002", 61_250)
        let location = place.location()
        #expect(AudioPlace.of(location) == place)
        // Through a file and back, as the store keeps it.
        let stored = try JSONDecoder().decode(ReadingLocation.self, from: JSONEncoder().encode(location))
        #expect(stored == location)
        #expect(AudioPlace.of(stored) == place)
        #expect(AudioPlace.of(ReadingLocation(pageIndex: 4)) == nil, "A book's page is not a place")
        #expect(AudioPlace.of(nil) == nil)
        // Its locator holds the place and nothing else: no clock, no flag of how exact it was.
        #expect(Set(location.locator.map { Array($0.keys) } ?? []) == ["trackId", "offsetMs", "completed"])
    }

    @Test func theHubsAnswerIsAPlaceWhateverItsClockSays() {
        let one = ReadingAudioPosition(trackId: "t_000000000002", track: 1, offsetMs: 61_250, exact: true, timestamp: 1_764_000_000_000)
        var later = one
        later.timestamp = 1_900_000_000_000
        later.form = "text"
        later.exact = false
        #expect(AudioPlace.fromServer(one) == AudioPlace("t_000000000002", 61_250))
        // The timestamp is the hub's clock: two answers at the same place are the same place.
        #expect(AudioPlace.fromServer(one) == AudioPlace.fromServer(later))
        #expect(AudioPlace.fromServer(ReadingAudioPosition()) == nil)
    }

    @Test func aPlaceIsKeptAsTheHubWillReadItBack() {
        // Within the track, as it is.
        #expect(AudioPlace.canonical(tracks, part: 1, offsetMs: 61_250) == AudioPlace("t_000000000002", 61_250))
        // A player's track runs a little past the manifest's length; the hub keeps its end.
        #expect(AudioPlace.canonical(tracks, part: 0, offsetMs: 600_640) == AudioPlace("t_000000000001", 600_000))
        // The last two seconds of the last track are the book finished, written as its end.
        #expect(AudioPlace.canonical(tracks, part: 2, offsetMs: 298_500) == AudioPlace("t_000000000003", 300_000, completed: true))
        #expect(AudioPlace.canonical(tracks, part: 0, offsetMs: 5_000, completed: true)
                == AudioPlace("t_000000000003", 300_000, completed: true))
        // The last two seconds of another track are only its end.
        #expect(AudioPlace.canonical(tracks, part: 0, offsetMs: 599_000) == AudioPlace("t_000000000001", 599_000))
        #expect(AudioPlace.canonical(tracks, part: 7, offsetMs: 0) == nil)
        #expect(AudioPlace.canonical([], part: 0, offsetMs: 0) == nil)
    }

    @Test func aWriteNamesThePlaceItWasBasedOnOrThatThereWasNone() throws {
        let local = AudioPlace("t_000000000002", 90_000)
        let base = AudioPlace("t_000000000002", 61_250)
        let sent = AudioPlace.body(local, base: base, baseKnown: true)
        #expect(sent["trackId"]?.stringValue == "t_000000000002")
        #expect(sent["offsetMs"]?.int64Value == 90_000)
        #expect(sent["completed"]?.boolValue == false)
        #expect(sent["expected"]?["trackId"]?.stringValue == base.trackId)
        #expect(sent["expected"]?["offsetMs"]?.int64Value == base.offsetMs)
        // The hub ignores the app's clock: none is sent.
        #expect(sent["timestamp"] == nil)
        // Nothing was saved when it was read: expected is null, and the hub checks that still holds.
        #expect(AudioPlace.body(local, base: nil, baseKnown: true)["expected"] == JSONValue.null)
        // No base read at all: no expectation, which the hub takes as none to check.
        #expect(AudioPlace.body(local, base: nil, baseKnown: false)["expected"] == nil)
        // On the wire the moment is a whole number, as the hub reads it.
        let wire = try #require(String(data: sent.encoded(), encoding: .utf8))
        #expect(wire.contains("\"offsetMs\":90000"))
    }

    @Test func aPlaceOpensOnItsTrackAndAFinishedBookAtItsStart() {
        func at(_ place: (part: Int, offsetMs: Int64)) -> String { "\(place.part)@\(place.offsetMs)" }
        #expect(at(AudioPlace("t_000000000002", 61_250).openAt(tracks)) == "1@61250")
        #expect(at(AudioPlace("t_000000000003", 300_000, completed: true).openAt(tracks)) == "0@0")
        // A track the book no longer has: its start, never another track's moment.
        #expect(at(AudioPlace("t_00000000dead", 61_250).openAt(tracks)) == "0@0")
        #expect(at(AudioPlace("t_000000000003", 999_999).openAt(tracks)) == "2@300000")
    }

    @Test func aPlaceInWordsForTheSheetThatAsksWhich() {
        #expect(AudioPlace.label(AudioPlace("t_000000000002", 61_250).location(), tracks: tracks) == "Part 2 of 3 · 1:01")
        #expect(AudioPlace.label(AudioPlace("t_000000000003", 300_000, completed: true).location(), tracks: tracks) == "Finished")
        #expect(AudioPlace.label(ReadingLocation(pageIndex: 4), tracks: tracks) == "Page 5")
        #expect(AudioPlace.started(AudioPlace("t_000000000001", 1).location()))
        #expect(!AudioPlace.started(AudioPlace("t_000000000001", 0).location()))
    }

    @Test func aPlaceWorkedOutFromAReadersPageIsAskedAboutBeforeThePlayerGoesThere() {
        let guess = ReadingAudioPosition(trackId: "t_000000000002", track: 1, offsetMs: 61_250, exact: false, form: "text")
        let place = AudioPlace("t_000000000002", 61_250)
        #expect(AudioPlace.asksBeforeJumping(to: place, answered: guess))
        // The hub's own exact place, another place chosen, or none: no question.
        var exact = guess
        exact.exact = true
        #expect(!AudioPlace.asksBeforeJumping(to: place, answered: exact))
        #expect(!AudioPlace.asksBeforeJumping(to: AudioPlace("t_000000000001", 5), answered: guess))
        #expect(!AudioPlace.asksBeforeJumping(to: nil, answered: guess))
        let ask = AudioPlace.estimatePrompt(place, tracks: tracks)
        #expect(ask.title == "Listen from where you were reading?")
        #expect(ask.choices.map(\.label) == ["Listen from there", "Start at the beginning"])
        #expect(ask.choices.first?.detail == "Part 2 of 3 · 1:01")
    }

    // MARK: How far through the book, kept beside a place (#30)

    private func keys(_ location: ReadingLocation?) -> Set<String> {
        Set(location?.locator.map { Array($0.keys) } ?? [])
    }

    private func near(_ value: Double?, _ expected: Double) -> Bool {
        guard let value else { return false }
        return abs(value - expected) < 1e-9
    }

    @Test func aPlaceKeptOnThisDeviceSaysHowFarThroughTheBookItIsBesideItsTrackAndMoment() throws {
        let kept = try #require(AudioPlace.kept(tracks, part: 1, offsetMs: 300_000))
        // The first track and five minutes of the second, of thirty-five minutes in all.
        #expect(near(AudioPlace.progressOf(kept), 900_000.0 / 2_100_000))
        #expect(keys(kept) == ["trackId", "offsetMs", "completed", "progress"])
        // It is still the same place: the fraction is a note beside it, not part of it.
        #expect(AudioPlace.of(kept) == AudioPlace("t_000000000002", 300_000))
        // Through a file and back, as the store keeps it.
        let stored = try JSONDecoder().decode(ReadingLocation.self, from: JSONEncoder().encode(kept))
        #expect(stored == kept)
        // The fraction is of the recording: a part past its length is its end, and the book's start is 0.
        #expect(near(AudioPlace.progressOf(AudioPlace.kept(tracks, part: 0, offsetMs: 600_640)), 600_000.0 / 2_100_000))
        #expect(AudioPlace.progressOf(AudioPlace.kept(tracks, part: 0, offsetMs: 0)) == 0)
        #expect(AudioPlace.kept(tracks, part: 7, offsetMs: 0) == nil, "No such part, no place to keep")
        #expect(AudioPlace.kept([], part: 0, offsetMs: 0) == nil)
    }

    @Test func aPlaceWhoseLengthsAreNotKnownKeepsNoFractionRatherThanAWrongOne() throws {
        var unknown = tracks
        unknown[1].durationMs = 0
        let kept = try #require(AudioPlace.kept(unknown, part: 0, offsetMs: 5_000))
        #expect(AudioPlace.of(kept) == AudioPlace("t_000000000001", 5_000))
        #expect(keys(kept) == ["trackId", "offsetMs", "completed"])
        #expect(AudioPlace.progressOf(kept) == nil)
        // A track the list no longer has: the same.
        #expect(AudioPlace("t_00000000dead", 5_000).progress(tracks) == nil)
        // And a fraction that is not one is never written.
        let place = AudioPlace("t_000000000002", 1)
        #expect(keys(place.location(progress: .nan)) == ["trackId", "offsetMs", "completed"])
        #expect(keys(place.location(progress: .infinity)) == ["trackId", "offsetMs", "completed"])
        #expect(AudioPlace.progressOf(place.location(progress: 7)) == 1)
        #expect(AudioPlace.progressOf(place.location(progress: -1)) == 0)
    }

    @Test func aFinishedBookIsTheWholeOfItWithOrWithoutAFractionBesideIt() throws {
        // The last two seconds of the last track are the book finished, written as 100%.
        let kept = try #require(AudioPlace.kept(tracks, part: 2, offsetMs: 298_500))
        #expect(AudioPlace.of(kept)?.completed == true)
        #expect(AudioPlace.progressOf(kept) == 1)
        #expect(AudioPlace.progressOf(AudioPlace.kept(tracks, part: 0, offsetMs: 5_000, completed: true)) == 1)
        // A finished place an older build kept has no fraction, and is finished all the same.
        #expect(AudioPlace.progressOf(AudioPlace("t_000000000003", 300_000, completed: true).location()) == 1)
        // Even when lengths are unknown the book is done.
        #expect(AudioPlace("t_000000000003", 300_000, completed: true).progress([]) == 1)
    }

    @Test func aPlaceKeptByAnOlderBuildStillReadsAndSaysNothingOfHowFarThroughTheBookItIs() {
        let old = AudioPlace("t_000000000002", 61_250).location()
        #expect(keys(old) == ["trackId", "offsetMs", "completed"])
        #expect(AudioPlace.of(old) == AudioPlace("t_000000000002", 61_250))
        // Not 0%: it was never counted, which is not the same as the start of the book.
        #expect(AudioPlace.progressOf(old) == nil)
        #expect(AudioPlace.progressOf(ReadingLocation(pageIndex: 4)) == nil)
        #expect(AudioPlace.progressOf(nil) == nil)
        // A location of this kind with something else where the fraction goes is not a fraction either.
        let odd = ReadingLocation(locator: ["trackId": .string("t_000000000002"), "offsetMs": .int(1), "completed": .bool(false),
                                            "progress": .string("soon")])
        #expect(AudioPlace.of(odd) == AudioPlace("t_000000000002", 1))
        #expect(AudioPlace.progressOf(odd) == nil)
    }

    @Test func theWriteIsThePlaceAndItsBaseWhateverIsKeptBesideThem() throws {
        let local = AudioPlace("t_000000000002", 90_000)
        let base = AudioPlace("t_000000000002", 61_250)
        let plain = AudioPlace.body(local, base: base, baseKnown: true)
        let key = ReadingCheckpointKey(scope: "profile", workId: "rw_1", sourceItemId: "3726292328809367", kind: AudioPlace.kind)
        let noted = ReadingCheckpoint(key: key, local: local.location(progress: 0.4), base: base.location(progress: 0.3),
                                      baseKnown: true, pending: true)
        let sent = try #require(AudioPlace.body(noted))
        #expect(sent == plain)
        #expect(Set(sent.objectValue.map { Array($0.keys) } ?? []) == ["trackId", "offsetMs", "completed", "expected"])
        #expect(Set(sent["expected"]?.objectValue.map { Array($0.keys) } ?? []) == ["trackId", "offsetMs"])
        let wire = String(decoding: sent.encoded(), as: UTF8.self)
        #expect(!wire.contains("progress"), "The fraction is this device's: the hub is never told")
        // Nothing read yet: still no expectation, with the fraction there or not.
        var unread = noted
        unread.base = nil
        unread.baseKnown = false
        #expect(AudioPlace.body(unread) == AudioPlace.body(local, base: nil, baseKnown: false))
    }

    @Test func twoLocationsAreTheSamePlaceWhateverIsKeptBesideThePlace() {
        let place = AudioPlace("t_000000000002", 61_250)
        #expect(AudioPlace.samePlace(place.location(), place.location()))
        // Kept with a fraction and read back from the hub without one: the hub's own reading of the same place.
        #expect(AudioPlace.samePlace(place.location(progress: 0.4), place.location()))
        #expect(AudioPlace.samePlace(place.location(), place.location(progress: 0.4)))
        #expect(AudioPlace.samePlace(place.location(progress: 0.4), place.location(progress: 0.5)))
        // A different moment, track or finish is another place.
        #expect(!AudioPlace.samePlace(place.location(progress: 0.4), AudioPlace("t_000000000002", 61_251).location(progress: 0.4)))
        #expect(!AudioPlace.samePlace(place.location(), AudioPlace("t_000000000001", 61_250).location()))
        #expect(!AudioPlace.samePlace(place.location(), AudioPlace("t_000000000002", 61_250, completed: true).location()))
        // Nothing, or a location that is not a place of this kind, is not a place.
        #expect(AudioPlace.samePlace(nil, nil))
        #expect(!AudioPlace.samePlace(place.location(), nil))
        #expect(!AudioPlace.samePlace(nil, place.location()))
        #expect(!AudioPlace.samePlace(place.location(), ReadingLocation(pageIndex: 4)))
        #expect(AudioPlace.samePlace(ReadingLocation(pageIndex: 4), ReadingLocation(pageIndex: 4)))
    }

    @Test func aNumberIsTheSameNumberHoweverItWasWritten() throws {
        let whole = try JSONDecoder().decode(JSONValue.self, from: Data(#"{"a":1,"b":1.0,"c":0.5,"d":[true,null,"x"]}"#.utf8))
        #expect(whole["a"] == whole["b"])
        #expect(whole["a"]?.int64Value == 1)
        #expect(whole["c"]?.doubleValue == 0.5)
        #expect(whole["c"]?.int64Value == nil)
        #expect(whole["d"] == .array([.bool(true), .null, .string("x")]))
        #expect(Set([JSONValue.int(2), JSONValue.double(2)]).count == 1)
    }
}
