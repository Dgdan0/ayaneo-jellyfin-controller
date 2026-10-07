import Foundation
import Testing
@testable import HubKit

/// An aligned audiobook's chapters from its read-along edition (#31): the
/// contents list them by title across the tracks, the steps go by chapter,
/// and the line under the player, its times and the time left are the
/// chapter's, not the file's. Dark Matter's shape: long tracks with no marks
/// of their own, chapters that start in one and end in the next.
struct AudiobookChaptersTests {
    // Three tracks: ten minutes, twenty minutes, five minutes.
    private let parts = [AudiobookPart(title: "Track 01/03", url: "0", durationMs: 600_000),
                         AudiobookPart(title: "Track 02/03", url: "1", durationMs: 1_200_000),
                         AudiobookPart(title: "Track 03/03", url: "2", durationMs: 300_000)]
    private var lengths: [Int64?] { parts.map(\.durationMs) }

    private func book(_ title: String, _ track: Int, _ startMs: Int64) -> ReadingAudioChapter {
        ReadingAudioChapter(title: title, startMs: startMs, track: track, source: ReadingAudioChapter.book)
    }

    /// The prologue a little in, after the credits; Two starts in the first
    /// track and ends in the second; Three runs from the second into the third.
    private var chapters: [ReadingAudioChapter] {
        [book("Prologue", 0, 20_000), book("One", 0, 200_000), book("Two", 0, 500_000), book("Three", 1, 900_000),
         book("Four", 2, 120_000)]
    }

    // MARK: Where they come from

    @Test func aChapterSaysWhereItComesFromAndAnOlderHubsAreMarks() throws {
        let json = #"""
        {"revision":"r1","tracks":[{"index":0,"id":"t_000000000001","durationMs":1000}],
         "chapters":[{"title":"One","startMs":0,"track":0,"source":"book"},{"title":"Mark","startMs":5,"track":0,"source":"marks"},
                     {"title":"Old","startMs":9,"track":0}]}
        """#
        let manifest = try JSONDecoder().decode(ReadingAudioManifest.self, from: Data(json.utf8))
        #expect(manifest.chapters.map(\.source) == ["book", "marks", ""])
        #expect(manifest.chapters.map(\.fromBook) == [true, false, false])
    }

    // MARK: The contents

    @Test func theBooksChaptersAreTheContentsAcrossTheTracks() {
        let entries = AudiobookContents.entries(parts, partsMs: lengths, chapters: chapters)
        #expect(entries.map(\.title) == ["Prologue", "One", "Two", "Three", "Four"])
        // The credits before the prologue are the prologue's: the book's first entry starts with it.
        #expect(entries.map(\.part) == [0, 0, 0, 1, 2])
        #expect(entries.map(\.startMs) == [0, 200_000, 500_000, 900_000, 120_000])
        // Two is the rest of the first track and the first fifteen minutes of
        // the second; Three the rest of the second and two minutes of the third.
        #expect(entries.map(\.durationMs) == [200_000, 300_000, 1_000_000, 420_000, 180_000])
        #expect(entries.compactMap(\.durationMs).reduce(0, +) == 2_100_000, "the chapters are the whole book")
        // No entry is a file's: the tracks' names are gone from the contents.
        #expect(!entries.contains { $0.title.hasPrefix("Track") })
    }

    @Test func aChapterThatCannotBePlayedToIsLeftOutAndTheRestKeepTheirOrder() {
        let odd = [book("Three", 1, 900_000), book("Past the end", 1, 5_000_000), book("No track", 7, 0),
                   book("One", 0, 200_000), book("One again", 0, 200_000), book("", 2, 120_000)]
        let entries = AudiobookContents.entries(parts, partsMs: lengths, chapters: odd)
        #expect(entries.map(\.title) == ["One", "Three", "Chapter 3"])
        #expect(entries.map(\.startMs) == [0, 900_000, 120_000])
    }

    @Test func aChaptersLengthIsUnknownWhileATrackItCrossesIs() {
        let unmeasured = [parts[0], AudiobookPart(title: "Track 02/03", url: "1"), parts[2]]
        let entries = AudiobookContents.entries(unmeasured, partsMs: [600_000, nil, 300_000], chapters: chapters)
        // Two needs only the first track's length; Three ends in the third and needs the second's.
        #expect(entries.map(\.durationMs) == [200_000, 300_000, 1_000_000, nil, 180_000])
    }

    @Test func marksStillListTheirTracksAndAnOlderHubsChaptersAreMarks() {
        let marks = [ReadingAudioChapter(title: "A", startMs: 0, track: 1, source: ReadingAudioChapter.marks),
                     ReadingAudioChapter(title: "B", startMs: 300_000, track: 1)]
        let entries = AudiobookContents.entries(parts, partsMs: lengths, chapters: marks)
        #expect(entries.map(\.title) == ["Track 01/03", "A", "B", "Track 03/03"])
        #expect(entries.map(\.durationMs) == [600_000, 300_000, 900_000, 300_000])
    }

    @Test func theContentsAreChaptersOrParts() {
        #expect(AudiobookContents.noun(chapters) == "chapter")
        #expect(AudiobookContents.noun([]) == "part")
        #expect(PlayerLabels.timeLeft(partLeftMs: 720_000, bookLeftMs: 15_000_000, unit: "chapter")
                == "12 min left in chapter · 4h 10m in book")
        #expect(PlayerLabels.timeLeft(partLeftMs: 720_000, bookLeftMs: nil) == "12 min left in part")
    }

    // MARK: The steps

    @Test func theChapterPlayingIsTheLastBegunWhicheverTrackItBeganIn() {
        let entries = AudiobookContents.entries(parts, partsMs: lengths, chapters: chapters)
        #expect(AudiobookContents.current(entries, part: 0, positionMs: 5_000) == 0)
        #expect(AudiobookContents.current(entries, part: 0, positionMs: 550_000) == 2)
        // Eleven minutes into the second track is still Two, begun in the first.
        #expect(AudiobookContents.current(entries, part: 1, positionMs: 660_000) == 2)
        #expect(AudiobookContents.current(entries, part: 2, positionMs: 60_000) == 3)
        #expect(AudiobookContents.current(entries, part: 2, positionMs: 200_000) == 4)
    }

    @Test func theStepsGoByChapterAcrossTheTracks() {
        let entries = AudiobookContents.entries(parts, partsMs: lengths, chapters: chapters)
        // Forward from inside Two, in the second track: Three, also in the second.
        #expect(AudiobookContents.step(entries, part: 1, positionMs: 60_000, delta: 1, partsMs: lengths)?.title == "Three")
        // Forward from Three, in the third track: Four.
        #expect(AudiobookContents.step(entries, part: 2, positionMs: 10_000, delta: 1, partsMs: lengths)?.title == "Four")
        #expect(AudiobookContents.step(entries, part: 2, positionMs: 200_000, delta: 1, partsMs: lengths) == nil)
        // Back from a minute into the second track, well into Two: Two's own start, in the first track.
        let restart = AudiobookContents.step(entries, part: 1, positionMs: 60_000, delta: -1, partsMs: lengths)
        #expect(restart?.title == "Two")
        #expect(restart?.part == 0 && restart?.startMs == 500_000)
        // Back from a moment into Three: the chapter before it, Two.
        #expect(AudiobookContents.step(entries, part: 1, positionMs: 901_000, delta: -1, partsMs: lengths)?.title == "Two")
    }

    @Test func backFromAMomentPastATrackBoundaryCountsTheChapterAcrossIt() {
        // Three begins a second before the end of the second track.
        let late = [book("One", 0, 0), book("Two", 0, 300_000), book("Three", 1, 1_199_000)]
        let entries = AudiobookContents.entries(parts, partsMs: lengths, chapters: late)
        // A second into the third track is two seconds into Three: back is Two.
        #expect(AudiobookContents.step(entries, part: 2, positionMs: 1_000, delta: -1, partsMs: lengths)?.title == "Two")
        // Five seconds in is Three's own start, in the track before.
        let restart = AudiobookContents.step(entries, part: 2, positionMs: 4_000, delta: -1, partsMs: lengths)
        #expect(restart?.title == "Three" && restart?.part == 1)
        // Without the lengths it cannot count, and it is taken as well in.
        #expect(AudiobookContents.step(entries, part: 2, positionMs: 1_000, delta: -1)?.title == "Three")
    }

    // MARK: The line and the time left

    @Test func theLineIsTheChaptersAcrossTheTracks() {
        let entries = AudiobookContents.entries(parts, partsMs: lengths, chapters: chapters)
        // A minute into the second track is six minutes into Two, of its sixteen and two thirds.
        let span = AudiobookContents.span(entries, part: 1, positionMs: 60_000, partMs: 1_200_000, partsMs: lengths)
        #expect(span.entry == 2 && span.title == "Two")
        #expect(span.part == 0 && span.startMs == 500_000)
        #expect(span.positionMs == 160_000)
        #expect(span.durationMs == 1_000_000)
        #expect(span.leftMs == 840_000)
        // Its time left as heard at one and a half times.
        #expect(Listening.heard(span.leftMs, speed: 1.5) == 560_000)
    }

    @Test func aMomentOnTheLineIsAPlaceInATrack() {
        let entries = AudiobookContents.entries(parts, partsMs: lengths, chapters: chapters)
        let span = AudiobookContents.span(entries, part: 1, positionMs: 60_000, partMs: 1_200_000, partsMs: lengths)
        // A minute into Two is still the first track; two minutes in is twenty seconds into the second.
        let early = AudiobookContents.place(span, at: 60_000, partsMs: lengths)
        #expect(early.part == 0 && early.offsetMs == 560_000)
        let later = AudiobookContents.place(span, at: 120_000, partsMs: lengths)
        #expect(later.part == 1 && later.offsetMs == 20_000)
        // Past either end of the line is its ends: Two's start, and Three's.
        let start = AudiobookContents.place(span, at: -5, partsMs: lengths)
        #expect(start.part == 0 && start.offsetMs == 500_000)
        let end = AudiobookContents.place(span, at: 9_999_999, partsMs: lengths)
        #expect(end.part == 1 && end.offsetMs == 900_000)
    }

    @Test func withoutChaptersOrALengthTheLineIsThePart() {
        let plain = AudiobookContents.entries(parts, partsMs: lengths, chapters: [])
        let part = AudiobookContents.span(plain, part: 1, positionMs: 60_000, partMs: 1_200_000, partsMs: lengths)
        #expect(part.entry == 1 && part.part == 1 && part.startMs == 0)
        #expect(part.positionMs == 60_000 && part.durationMs == 1_200_000)
        let there = AudiobookContents.place(part, at: 90_000, partsMs: lengths)
        #expect(there.part == 1 && there.offsetMs == 90_000)
        // Three's length is not known while the second track's is not: the part, by the player's own length.
        let unmeasured = [parts[0], AudiobookPart(title: "Track 02/03", url: "1"), parts[2]]
        let entries = AudiobookContents.entries(unmeasured, partsMs: [600_000, nil, 300_000], chapters: chapters)
        let fallback = AudiobookContents.span(entries, part: 1, positionMs: 950_000, partMs: 1_150_000,
                                              partsMs: [600_000, nil, 300_000])
        #expect(fallback.entry == nil && fallback.part == 1 && fallback.startMs == 0)
        #expect(fallback.positionMs == 950_000 && fallback.durationMs == 1_150_000)
        // Two's is known, and a minute into the second track is its own line.
        let two = AudiobookContents.span(entries, part: 1, positionMs: 60_000, partMs: 1_150_000, partsMs: [600_000, nil, 300_000])
        #expect(two.entry == 2 && two.positionMs == 160_000 && two.durationMs == 1_000_000)
        let none = AudiobookContents.span([], part: 0, positionMs: 70_000, partMs: 0, partsMs: [])
        #expect(none.entry == nil && none.positionMs == 0 && none.durationMs == 0)
    }

    // MARK: The demo hub

    @Test func theDemosDarkMatterIsAlignedWithChaptersThatRunOnAcrossItsTracks() throws {
        let data = try JSONSerialization.data(withJSONObject: DemoReading.manifestFields(DemoReading.audiobooks[0]))
        let manifest = try JSONDecoder().decode(ReadingAudioManifest.self, from: data)
        #expect(manifest.aligned)
        #expect(manifest.alignment?.audio.map(\.track) == [0, 1, 2, 2])
        let fromBook = manifest.chapters.allSatisfy { $0.fromBook }
        #expect(fromBook)
        let demoParts = AudiobookStream.parts(manifest, sourceItemId: manifest.sourceItemId) { "\($0)" }
        let entries = AudiobookContents.entries(demoParts, partsMs: demoParts.map(\.durationMs), chapters: manifest.chapters)
        #expect(entries.map(\.title) == ["One", "Two", "Three", "Four"])
        #expect(entries.map(\.part) == [0, 0, 1, 2])
        #expect(entries.map(\.startMs) == [0, 52_000, 20_000, 15_000])
        #expect(entries.map(\.durationMs) == [52_000, 58_000, 70_000, 45_000])
        // The demo's place, half a minute into the second track, is ten seconds into Three.
        let span = AudiobookContents.span(entries, part: 1, positionMs: 30_000, partMs: 75_000,
                                          partsMs: demoParts.map(\.durationMs))
        #expect(span.title == "Three" && span.positionMs == 10_000 && span.leftMs == 60_000)
    }
}
