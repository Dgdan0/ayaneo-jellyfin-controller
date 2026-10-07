import Foundation
import Testing
@testable import HubKit

/// An aligned audiobook's own chapters (#31). The hub lists the read-along
/// edition's table of contents as places in the tracks (`source` "book"), so
/// a chapter can start in one track and run on into the next: the contents,
/// the steps, the line under the title, its times and the time left go by
/// the chapter, counted across tracks, and a book without chapters is read as
/// it always was. Android's `AudiobookChaptersTest`, case for case.
struct AudiobookChaptersTests {
    // Three tracks of 75 minutes, as Dark Matter's are.
    private let lengths: [Int64?] = [4_500_000, 4_500_000, 4_500_000]
    private var parts: [AudiobookPart] {
        lengths.enumerated().map { index, length in
            AudiobookPart(title: "Track 0\(index + 1)", url: "https://hub/t/\(index)", durationMs: length)
        }
    }

    private func book(_ title: String, _ track: Int, _ startMs: Int64) -> ReadingAudioChapter {
        ReadingAudioChapter(title: title, startMs: startMs, track: track, source: ReadingAudioChapter.book)
    }

    private func with(_ chapters: [ReadingAudioChapter], source: String) -> [ReadingAudioChapter] {
        chapters.map { ReadingAudioChapter(title: $0.title, startMs: $0.startMs, track: $0.track, source: source) }
    }

    // Two starts in the first track and ends in the second, Four from the second into the third.
    private var chapters: [ReadingAudioChapter] {
        [book("Chapter One", 0, 0), book("Chapter Two", 0, 3_492_550), book("Chapter Three", 1, 1_070_930),
         book("Chapter Four", 1, 2_521_240), book("Chapter Five", 2, 100_000)]
    }

    private var entries: [AudiobookContents.Entry] { AudiobookContents.entries(parts, partsMs: lengths, chapters: chapters) }

    private func at(_ place: (part: Int, offsetMs: Int64)) -> String { "\(place.part)@\(place.offsetMs)" }

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

    @Test func theContentsListTheBooksChaptersByTitleEachLastingToTheNextAcrossTheTracks() {
        let found = entries
        #expect(found.map(\.title) == ["Chapter One", "Chapter Two", "Chapter Three", "Chapter Four", "Chapter Five"])
        #expect(found.map(\.part) == [0, 0, 1, 1, 2])
        #expect(found.map(\.startMs) == [0, 3_492_550, 1_070_930, 2_521_240, 100_000])
        // Two starts in the first track and lasts into the second; Four into the third; Five to the book's end.
        #expect(found.map(\.durationMs) == [3_492_550, 2_078_380, 1_450_310, 2_078_760, 4_400_000])
        // Between them, the whole book, with no file's name in the way.
        #expect(found.compactMap(\.durationMs).reduce(0, +) == 13_500_000)
        #expect(!found.contains { $0.title.hasPrefix("Track") })
        #expect(found.allSatisfy { $0.chapter })
    }

    @Test func theVoiceBeforeTheFirstChapterBelongsToItSoTheBooksFirstEntryStartsWithTheBook() {
        // The Final Empire opens with a minute of credits before its Prologue.
        let credits = [book("Prologue", 0, 66_990), book("Chapter 1", 0, 2_219_720), book("Chapter 2", 0, 3_482_090)]
        let found = AudiobookContents.entries(parts, partsMs: lengths, chapters: credits)
        #expect(found.map(\.title) == ["Prologue", "Chapter 1", "Chapter 2"])
        #expect(found.map(\.startMs) == [0, 2_219_720, 3_482_090])
        #expect(found[0].durationMs == 2_219_720)
        // The last lasts to the end of the book, across the tracks after it.
        let toTheEnd: Int64 = 4_500_000 - 3_482_090 + 2 * 4_500_000
        #expect(found[2].durationMs == toTheEnd)
        // Where the first chapter is in a later track, the book still starts at its beginning.
        let late = AudiobookContents.entries(parts, partsMs: lengths, chapters: [book("One", 1, 200_000), book("Two", 1, 900_000)])
        #expect(late.map { "\($0.part)@\($0.startMs)" } == ["0@0", "1@900000"])
        let intoTheSecond: Int64 = 4_500_000 + 900_000
        #expect(late[0].durationMs == intoTheSecond)
    }

    @Test func chaptersArePutInTheOrderTheyAreHeardOneToAMomentAndThoseOutsideTheirTrackAreLeftOut() {
        let jumbled = [book("Four", 2, 100_000), book("Two", 0, 3_000_000), book("One", 0, 0), book("Twin of two", 0, 3_000_000),
                       book("Past the end", 1, 9_999_999), book("Nowhere", 7, 5), book("Before", 0, -5), book("", 1, 2_000_000)]
        let found = AudiobookContents.entries(parts, partsMs: lengths, chapters: jumbled)
        // Of two at one moment the one the hub listed first stays; an untitled one is named by its place in the list.
        #expect(found.map(\.title) == ["One", "Two", "Chapter 3", "Four"])
        #expect(found.map { "\($0.part)@\($0.startMs)" } == ["0@0", "0@3000000", "1@2000000", "2@100000"])
    }

    @Test func theContentsListChaptersByTitleWhateverTheirSource() {
        let titles = Set(chapters.map(\.title))
        // The book's own, and the file marks of an older hub, which sends no source: both by title.
        let marks = with(chapters, source: ReadingAudioChapter.marks)
        let unlabelled = with(chapters, source: "")
        for list in [chapters, marks, unlabelled] {
            let found = AudiobookContents.entries(parts, partsMs: lengths, chapters: list)
            #expect(titles.isSubset(of: Set(found.map(\.title))))
            #expect(AudiobookContents.noun(found) == "chapter")
        }
        // Marks stay inside their file: a track's opening before its first mark is an entry of its own.
        let inFiles = AudiobookContents.entries(parts, partsMs: lengths, chapters: marks)
        #expect(inFiles.map(\.title) == ["Chapter One", "Chapter Two", "Track 02", "Chapter Three", "Chapter Four", "Track 03",
                                         "Chapter Five"])
        #expect(inFiles == AudiobookContents.entries(parts, partsMs: lengths, chapters: unlabelled))
        // The book's own cross the files: none of them is named for one.
        #expect(entries.count == 5)
    }

    @Test func aBooksChaptersWinWhereAHubMixesThemWithMarks() {
        let mixed = chapters + [ReadingAudioChapter(title: "A mark", startMs: 1_000_000, track: 1, source: ReadingAudioChapter.marks)]
        #expect(AudiobookContents.entries(parts, partsMs: lengths, chapters: mixed) == entries)
    }

    @Test func aChaptersLengthIsUnknownWhileATrackItRunsAcrossHasNone() {
        let unmeasured = parts.map { AudiobookPart(title: $0.title, url: $0.url) }
        let unknown = AudiobookContents.entries(unmeasured, partsMs: [nil, nil, nil], chapters: chapters)
        #expect(unknown.map(\.title) == ["Chapter One", "Chapter Two", "Chapter Three", "Chapter Four", "Chapter Five"])
        #expect(unknown.map { "\($0.part)@\($0.startMs)" } == ["0@0", "0@3492550", "1@1070930", "1@2521240", "2@100000"])
        // One and Three lie within one track; the rest run across, or to the end of, a length not known.
        #expect(unknown.map(\.durationMs) == [3_492_550, nil, 1_450_310, nil, nil])
        // The manifest's length counts when the player has none of its own, as for the parts.
        let known = AudiobookContents.entries(parts, partsMs: [nil, nil, nil], chapters: chapters)
        #expect(known.map(\.durationMs) == entries.map(\.durationMs))
    }

    @Test func aBookWithoutChaptersIsItsPartsAndChaptersThatFitNowhereAreNoChapters() {
        let plain = AudiobookContents.entries(parts, partsMs: lengths, chapters: [])
        #expect(plain.map(\.title) == ["Track 01", "Track 02", "Track 03"])
        #expect(plain.map(\.durationMs) == [4_500_000, 4_500_000, 4_500_000])
        #expect(AudiobookContents.noun(plain) == "part")
        #expect(AudiobookContents.noun([]) == "part")
        // Marks past the end of their track, or in a track the book does not have, are not chapters.
        let stray = AudiobookContents.entries(parts, partsMs: lengths, chapters: [book("Gone", 9, 5), book("Late", 0, 99_999_999)])
        #expect(stray == plain)
        #expect(AudiobookContents.noun(stray) == "part")
    }

    // MARK: Playing, and the steps

    @Test func theChapterPlayingIsTheLastBegunWhereverItsTrackBegan() {
        #expect(AudiobookContents.current(entries, part: 0, positionMs: 5_000) == 0)
        #expect(AudiobookContents.current(entries, part: 0, positionMs: 4_499_999) == 1)
        // Two began in the first track and is still playing in the second.
        #expect(AudiobookContents.current(entries, part: 1, positionMs: 0) == 1)
        #expect(AudiobookContents.current(entries, part: 1, positionMs: 1_070_929) == 1)
        #expect(AudiobookContents.current(entries, part: 1, positionMs: 1_070_930) == 2)
        #expect(AudiobookContents.current(entries, part: 2, positionMs: 99_999) == 3)
        #expect(AudiobookContents.current(entries, part: 2, positionMs: 100_000) == 4)
    }

    @Test func nextAndPreviousGoByChapterAcrossATracksEnd() {
        // Forward from inside Two, in the second track: Three. From the last: nothing.
        #expect(AudiobookContents.step(entries, part: 1, positionMs: 500_000, delta: 1, partsMs: lengths)?.title == "Chapter Three")
        #expect(AudiobookContents.step(entries, part: 2, positionMs: 4_000_000, delta: 1, partsMs: lengths) == nil)
        // Forward in the first track's last seconds: Two began there, and Three is in the next track.
        let next = AudiobookContents.step(entries, part: 0, positionMs: 4_499_000, delta: 1, partsMs: lengths)
        #expect(next?.title == "Chapter Three" && next?.part == 1 && next?.startMs == 1_070_930)
        // Back from well into Two, now in the second track: Two's own start, in the first. Not One, and not Three.
        let back = AudiobookContents.step(entries, part: 1, positionMs: 500_000, delta: -1, partsMs: lengths)
        #expect(back?.title == "Chapter Two" && back?.part == 0 && back?.startMs == 3_492_550)
        // From the start of Two: the one before.
        #expect(AudiobookContents.step(entries, part: 0, positionMs: 3_492_550 + 2_000, delta: -1, partsMs: lengths)?.title
                == "Chapter One")
        // At the first: its own start, and nothing before it.
        #expect(AudiobookContents.step(entries, part: 0, positionMs: 100, delta: -1, partsMs: lengths)?.title == "Chapter One")
        #expect(AudiobookContents.step([], part: 0, positionMs: 0, delta: -1, partsMs: lengths) == nil)
    }

    @Test func backFromThreeSecondsInRestartsTheChapterTheSecondsCountedAcrossTheTracksEnd() {
        // Two starts a second and a half before the first track ends.
        let near = AudiobookContents.entries(parts, partsMs: lengths,
                                             chapters: [book("One", 0, 0), book("Two", 0, 4_498_500), book("Three", 1, 600_000)])
        // A second into the next track is two and a half seconds into Two: back goes to One.
        #expect(AudiobookContents.step(near, part: 1, positionMs: 1_000, delta: -1, partsMs: lengths)?.title == "One")
        // Three seconds exactly is still not further in, as the part steps were.
        #expect(AudiobookContents.step(near, part: 1, positionMs: 1_500, delta: -1, partsMs: lengths)?.title == "One")
        // Past three seconds back restarts Two, which began in the other track.
        let restart = AudiobookContents.step(near, part: 1, positionMs: 1_600, delta: -1, partsMs: lengths)
        #expect(restart?.title == "Two" && restart?.part == 0 && restart?.startMs == 4_498_500)
        // A track's length not known: it is taken as well in, so back restarts the chapter rather than skipping it.
        #expect(AudiobookContents.step(near, part: 1, positionMs: 1_000, delta: -1)?.title == "Two")
        #expect(AudiobookContents.step(near, part: 1, positionMs: 1_000, delta: -1, partsMs: [nil, nil, nil])?.title == "Two")
    }

    // MARK: What the line and its times measure

    @Test func theLineItsTimesAndTheTimelineAreTheChaptersCountedAcrossTracks() {
        // Five hundred seconds into the second track is in Two, which began 1007 seconds before this track did.
        let span = AudiobookContents.span(entries, part: 1, positionMs: 500_000, partMs: 4_500_000, partsMs: lengths)
        #expect(span.entry == 1)
        #expect(span.title == "Chapter Two")
        #expect(span.positionMs == 1_507_450)
        #expect(span.durationMs == 2_078_380)
        #expect(span.leftMs == 570_930)
        #expect(span.measuresEntry)
        // In the first track, from its start.
        let first = AudiobookContents.span(entries, part: 0, positionMs: 4_000_000, partMs: 4_500_000, partsMs: lengths)
        #expect(first.title == "Chapter Two")
        #expect(first.positionMs == 507_450)
        #expect(first.leftMs == 1_570_930)
    }

    @Test func thePlayersOwnLengthOfTheTrackPlayingCountsBeforeTheManifests() {
        // Five runs to where the player says the book ends.
        let last = AudiobookContents.span(entries, part: 2, positionMs: 1_000_000, partMs: 4_500_400, partsMs: lengths)
        #expect(last.entry == 4)
        #expect(last.positionMs == 900_000)
        #expect(last.durationMs == 4_400_400)
        // A player that has not read the length yet: the manifest's.
        #expect(AudiobookContents.span(entries, part: 2, positionMs: 1_000_000, partMs: 0, partsMs: lengths).durationMs == 4_400_000)
    }

    @Test func withoutChaptersTheLineAndItsTimesAreThePartsAsTheyWere() {
        let plain = AudiobookContents.entries(parts, partsMs: lengths, chapters: [])
        let span = AudiobookContents.span(plain, part: 1, positionMs: 12_345, partMs: 4_500_321, partsMs: lengths)
        #expect(span.entry == 1)
        #expect(span.title == "Track 02")
        #expect(span.positionMs == 12_345)
        #expect(span.durationMs == 4_500_321)
        #expect(span.leftMs == 4_487_976)
        // Nothing known of the part yet: the position as the player has it.
        let opening = AudiobookContents.span(plain, part: 0, positionMs: 2_000, partMs: 0, partsMs: [nil, nil, nil])
        #expect(opening.positionMs == 2_000 && opening.durationMs == 0)
        #expect(!opening.measuresEntry)
        // No book on the player at all.
        let none = AudiobookContents.span([], part: 0, positionMs: 0, partMs: 0, partsMs: [])
        #expect(none.entry == -1)
        #expect(none.title == "")
    }

    @Test func aChapterWhoseLengthIsNotKnownYetIsNamedAndMeasuredByThePart() {
        let unmeasured = parts.map { AudiobookPart(title: $0.title, url: $0.url) }
        let unknown = AudiobookContents.entries(unmeasured, partsMs: [nil, nil, nil], chapters: chapters)
        let span = AudiobookContents.span(unknown, part: 1, positionMs: 500_000, partMs: 4_500_000, partsMs: [nil, nil, nil])
        // Two began in the first track, whose length is not known: the chapter is named, the part's time is told.
        #expect(span.title == "Chapter Two")
        #expect(!span.measuresEntry)
        #expect(span.positionMs == 500_000)
        #expect(span.durationMs == 4_500_000)
        #expect(at(AudiobookContents.place(span, at: 250_000, partsMs: [nil, nil, nil])) == "1@250000")
    }

    @Test func aMomentInTheChapterIsAPlaceInTheTracks() {
        let span = AudiobookContents.span(entries, part: 1, positionMs: 500_000, partMs: 4_500_000, partsMs: lengths)
        #expect(at(AudiobookContents.place(span, at: 1_507_450, partsMs: lengths)) == "1@500000")
        #expect(at(AudiobookContents.place(span, at: 0, partsMs: lengths)) == "0@3492550")
        #expect(at(AudiobookContents.place(span, at: 1_000_000, partsMs: lengths)) == "0@4492550")
        #expect(at(AudiobookContents.place(span, at: 1_007_450, partsMs: lengths)) == "1@0")
        // Not before its start, and not past its end, which is where the next one begins.
        #expect(at(AudiobookContents.place(span, at: -5, partsMs: lengths)) == "0@3492550")
        #expect(at(AudiobookContents.place(span, at: 9_999_999, partsMs: lengths)) == "1@1070930")
        // A part's: the moment in it.
        let plain = AudiobookContents.entries(parts, partsMs: lengths, chapters: [])
        let part = AudiobookContents.span(plain, part: 1, positionMs: 5_000, partMs: 4_500_000, partsMs: lengths)
        #expect(at(AudiobookContents.place(part, at: 77_000, partsMs: lengths)) == "1@77000")
    }

    // MARK: Where a track's end is a chapter's

    @Test func aTracksEndEndsTheChapterOnlyWhenTheNextTrackBeginsAnother() {
        // Two runs on past the first track's end, and Four past the second's.
        #expect(!AudiobookContents.endsWithPart(entries, part: 0, lengthMs: 4_500_000))
        #expect(!AudiobookContents.endsWithPart(entries, part: 1, lengthMs: 4_500_000))
        // Chapters that begin where their tracks do.
        let aligned = AudiobookContents.entries(parts, partsMs: lengths,
                                                chapters: [book("One", 0, 0), book("Two", 1, 0), book("Three", 2, 0)])
        #expect(AudiobookContents.endsWithPart(aligned, part: 0, lengthMs: 4_500_000))
        #expect(AudiobookContents.endsWithPart(aligned, part: 1, lengthMs: 4_500_000))
        // A part always ends where it does.
        let plain = AudiobookContents.entries(parts, partsMs: lengths, chapters: [])
        #expect(AudiobookContents.endsWithPart(plain, part: 0, lengthMs: 4_500_000))
        #expect(AudiobookContents.endsWithPart([], part: 0, lengthMs: 4_500_000))
        // Marks stay in their file, which begins an entry of its own: every file's end is one's.
        let marks = AudiobookContents.entries(parts, partsMs: lengths, chapters: with(chapters, source: ReadingAudioChapter.marks))
        #expect(AudiobookContents.endsWithPart(marks, part: 0, lengthMs: 4_500_000))
        #expect(AudiobookContents.endsWithPart(marks, part: 1, lengthMs: 4_500_000))
    }

    // MARK: The words

    @Test func theWordsSayChapterWhereTheBookHasChaptersAndPartWhereItDoesNot() {
        #expect(PlayerLabels.timeLeft(entryLeftMs: 12 * 60_000, bookLeftMs: 15_000_000, noun: "chapter")
                == "12 min left in chapter · 4h 10m in book")
        #expect(PlayerLabels.timeLeft(entryLeftMs: 12 * 60_000, bookLeftMs: nil, noun: "chapter") == "12 min left in chapter")
        #expect(PlayerLabels.timeLeft(entryLeftMs: 12 * 60_000, bookLeftMs: 15_000_000, noun: "part")
                == "12 min left in part · 4h 10m in book")
        #expect(PlayerLabels.timeLeft(entryLeftMs: 10_000, bookLeftMs: nil, noun: "chapter") == "1 min left in chapter")
        let timer = SleepTimer.start(.endOfPart, entryLeftHeardMs: 90_000)
        #expect(PlayerLabels.sleep(timer, noun: "chapter") == "Sleep · end of chapter")
        #expect(PlayerLabels.sleep(timer) == "Sleep · end of part")
        #expect(PlayerLabels.sleep(nil, noun: "chapter") == "Sleep")
        #expect(PlayerLabels.sleep(timer.tick(elapsedMs: 1_000, entryLeftHeardMs: 20_000), noun: "chapter") == "Sleep · fading")
        // A carried one counts down, in either wording.
        #expect(PlayerLabels.sleep(timer.extended(entryLeftHeardMs: 20_000, nextEntryHeardMs: 300_000), noun: "chapter")
                == "Sleep · 5:20")
        #expect(PlayerLabels.sleepChoice(.endOfPart, noun: "chapter") == "End of this chapter")
        #expect(PlayerLabels.sleepChoice(.endOfPart, noun: "part") == "End of this part")
        #expect(PlayerLabels.sleepChoice(.minutes(15), noun: "chapter") == "15 minutes")
        #expect(PlayerLabels.sleepChoice(.minutes(60), noun: "chapter") == "1 hour")
    }

    @Test func theMiniPlayerSaysHowLongIsLeftOfTheBook() {
        #expect(PlayerLabels.leftLine(15_000_000) == "4h 10m left")
        #expect(PlayerLabels.leftLine(4_000) == "1 min left")
        #expect(PlayerLabels.leftLine(nil) == "")
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
        let found = AudiobookContents.entries(demoParts, partsMs: demoParts.map(\.durationMs), chapters: manifest.chapters)
        #expect(found.map(\.title) == ["One", "Two", "Three", "Four"])
        #expect(found.map(\.part) == [0, 0, 1, 2])
        #expect(found.map(\.startMs) == [0, 52_000, 20_000, 15_000])
        #expect(found.map(\.durationMs) == [52_000, 58_000, 70_000, 45_000])
        // The demo's place, half a minute into the second track, is ten seconds into Three.
        let span = AudiobookContents.span(found, part: 1, positionMs: 30_000, partMs: 75_000, partsMs: demoParts.map(\.durationMs))
        #expect(span.title == "Three" && span.positionMs == 10_000 && span.leftMs == 60_000)
        // Two runs on through the first track's end; Three into the third's.
        #expect(!AudiobookContents.endsWithPart(found, part: 0, lengthMs: 90_000))
        #expect(!AudiobookContents.endsWithPart(found, part: 1, lengthMs: 75_000))
    }
}
