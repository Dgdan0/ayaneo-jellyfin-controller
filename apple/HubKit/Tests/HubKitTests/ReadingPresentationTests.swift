import Foundation
import Testing
@testable import HubKit

/// What a book page opens, a library sorts by, Discover shows and a request
/// and a transfer say (#25): Android's presentation tests.
struct ReadingPresentationTests {
    // MARK: ReadingWorkPresentation

    @Test func aKavitaComicsReadOpensAChapterRatherThanItsSeriesEdition() {
        let work = ReadingWork(kind: "comic", editions: [ReadingEdition(source: "kavita", sourceItemId: "91", kind: "comic",
                                                                        availability: "available")],
                               sections: [ReadingSection(items: [ReadingSectionItem(sourceItemId: "901", kind: "comic", availability: "")])])
        #expect(ReadingWorkPresentation.primaryRead(work)?.sourceItemId == "901")
    }

    @Test func kavitaComicsAndMangaOpenThePagesAndEverythingElseTheEbookReader() {
        #expect(ReadingWorkPresentation.opensPages(ReadingWork(kind: "comic"), source: "kavita"))
        #expect(ReadingWorkPresentation.opensPages(ReadingWork(kind: "manga"), source: "kavita"))
        #expect(!ReadingWorkPresentation.opensPages(ReadingWork(kind: "book"), source: "kavita"))
        #expect(!ReadingWorkPresentation.opensPages(ReadingWork(kind: "ebook"), source: "storyteller"))
        #expect(!ReadingWorkPresentation.opensPages(ReadingWork(kind: "comic"), source: "storyteller"))
    }

    @Test func thePublicationOpenedIsTheIssueOnThePageElseOneMadeFromTheContinue() {
        let issue = ReadingSectionItem(sourceItemId: "901", title: "Issue 51", number: "51", kind: "comic", pageCount: 24)
        let work = ReadingWork(id: "rw_ff", kind: "comic", title: "Fantastic Four", artwork: "/cover",
                               sections: [ReadingSection(items: [issue])],
                               continueAt: ReadingContinue(sourceItemId: "902", title: "Issue 52", number: "52", artwork: "/52"))
        #expect(ReadingWorkPresentation.publication(work, sourceItemId: "901") == issue)
        let continued = ReadingWorkPresentation.publication(work, sourceItemId: "902")
        #expect(continued.title == "Issue 52" && continued.number == "52" && continued.artwork == "/52")
        #expect(continued.workId == "rw_ff" && continued.kind == "comic")
        let unknown = ReadingWorkPresentation.publication(work, sourceItemId: "999")
        #expect(unknown.title == "Fantastic Four" && unknown.artwork == "/cover" && unknown.sourceItemId == "999")
    }

    @Test func aBookResumesItsContinuedReadableEdition() {
        let work = ReadingWork(id: "golden-son", entityType: "work", kind: "ebook", title: "Golden Son",
                               editions: [ReadingEdition(source: "storyteller", sourceItemId: "edition-a", kind: "ebook", format: "epub",
                                                         availability: "available"),
                                          ReadingEdition(source: "storyteller", sourceItemId: "edition-b", kind: "ebook", format: "epub",
                                                         availability: "available")],
                               progress: ReadingProgress(percentage: 0.47),
                               continueAt: ReadingContinue(source: "storyteller", sourceItemId: "edition-b", percentage: 0.47))
        let action = ReadingWorkPresentation.primaryRead(work)
        #expect(action?.sourceItemId == "edition-b")
        #expect(action?.source == "storyteller")
        #expect(action?.label == "Resume · 47%")
    }

    @Test func anUnstartedBookSaysReadAndAFinishedOneReadAgain() {
        let edition = ReadingEdition(source: "storyteller", sourceItemId: "epub-1", kind: "ebook", availability: "available")
        let work = ReadingWork(id: "book", kind: "ebook", editions: [edition])
        #expect(ReadingWorkPresentation.primaryRead(work)?.label == "Read book")
        var done = work
        done.progress = ReadingProgress(percentage: 1, completed: true)
        #expect(ReadingWorkPresentation.primaryRead(done)?.label == "Read again")
    }

    @Test func seriesAndAudioOnlyBooksHaveNothingToRead() {
        let audio = ReadingEdition(source: "storyteller", sourceItemId: "audio-1", kind: "audiobook", availability: "available")
        #expect(ReadingWorkPresentation.primaryRead(ReadingWork(entityType: "collection", editions: [audio])) == nil)
        #expect(ReadingWorkPresentation.primaryRead(ReadingWork(kind: "audiobook", editions: [audio])) == nil)
    }

    @Test func anAudioPlaceCannotReplaceAnEbookToRead() {
        let work = ReadingWork(kind: "ebook",
                               editions: [ReadingEdition(source: "storyteller", sourceItemId: "ebook-1", kind: "ebook", availability: "available")],
                               progress: ReadingProgress(percentage: 0.35),
                               sections: [ReadingSection(items: [ReadingSectionItem(sourceItemId: "audio-1", workId: "book", kind: "audiobook")])],
                               continueAt: ReadingContinue(source: "kavita", sourceItemId: "audio-1", kind: "audiobook"))
        let action = ReadingWorkPresentation.primaryRead(work)
        #expect(action?.sourceItemId == "ebook-1" && action?.source == "storyteller")
    }

    @Test func aBooksEbookAudioAndReadAlongAreApart() {
        let paired = ReadingWork(kind: "book", editions: [
            ReadingEdition(source: "storyteller", sourceItemId: "42", kind: "ebook", availability: "available"),
            ReadingEdition(source: "storyteller", sourceItemId: "42", kind: "audiobook", availability: "available"),
            ReadingEdition(source: "storyteller", sourceItemId: "42", kind: "readaloud", availability: "available"),
        ])
        #expect(ReadingWorkPresentation.primaryRead(paired)?.sourceItemId == "42")
        #expect(ReadingWorkPresentation.primaryListen(paired)?.sourceItemId == "42")
        #expect(ReadingWorkPresentation.readAlongEdition(paired)?.sourceItemId == "42")
        #expect(ReadingWorkPresentation.audiobooks(paired).count == 1)
        var unaligned = paired
        unaligned.editions.removeLast()
        #expect(ReadingWorkPresentation.readAlongEdition(unaligned) == nil)
        var audioOnly = paired
        audioOnly.editions = [paired.editions[1]]
        #expect(ReadingWorkPresentation.primaryRead(audioOnly) == nil)
        #expect(ReadingWorkPresentation.primaryListen(audioOnly)?.sourceItemId == "42")
    }

    @Test func severalNarrationsStayChoosableAndOnlyAlignedOnesReadAlong() {
        let work = ReadingWork(editions: [
            ReadingEdition(source: "storyteller", sourceItemId: "1", kind: "audiobook", narrator: "Narrator A", availability: "available"),
            ReadingEdition(source: "storyteller", sourceItemId: "2", kind: "audiobook", narrator: "Narrator B", availability: "available"),
            ReadingEdition(source: "storyteller", sourceItemId: "1", kind: "readaloud", availability: "available"),
        ])
        #expect(ReadingWorkPresentation.audiobooks(work).map(\.sourceItemId) == ["1", "2"])
        #expect(ReadingWorkPresentation.readAlongEditions(work).map(\.sourceItemId) == ["1"])
    }

    @Test func theContinueCoverComesFromTheBookBeingReadBeforeTheSeries() {
        let work = ReadingWork(artwork: "/series",
                               sections: [ReadingSection(items: [ReadingSectionItem(sourceItemId: "11", workId: "rw_one", title: "Red Rising",
                                                                                    artwork: "/book-one")])],
                               continueAt: ReadingContinue(workId: "rw_one", sourceItemId: "11", title: "Red Rising"))
        #expect(ReadingWorkPresentation.continueArtwork(work) == "/book-one")
        var explicit = work
        explicit.continueAt?.artwork = "/explicit"
        #expect(ReadingWorkPresentation.continueArtwork(explicit) == "/explicit")
    }

    @Test func missingBooksShowButCannotBeOpened() {
        #expect(ReadingWorkPresentation.canOpen(ReadingSectionItem(workId: "rw_one", availability: "available")))
        #expect(!ReadingWorkPresentation.canOpen(ReadingSectionItem(title: "Golden Son", availability: "missing")))
    }

    @Test func aComicRunNamesTheIssueToContinue() {
        let issue51 = ReadingSectionItem(sourceItemId: "8959", title: "51", number: "51", kind: "comic", availability: "")
        let work = ReadingWork(id: "ff", kind: "comic",
                               editions: [ReadingEdition(source: "kavita", sourceItemId: "series-9", kind: "comic", availability: "available")],
                               progress: ReadingProgress(percentage: 0.0002), sections: [ReadingSection(items: [issue51])],
                               continueAt: ReadingContinue(source: "kavita", sourceItemId: "8959", number: "51", kind: "comic"))
        #expect(ReadingWorkPresentation.primaryRead(work)?.label == "Continue · Issue 51")
        #expect(ReadingBookFacts.progress(work) == "On issue 51 · 1% read")
        var fresh = work
        fresh.progress = nil
        fresh.continueAt = nil
        #expect(ReadingWorkPresentation.primaryRead(fresh)?.label == "Start · Issue 51")
        // The real run's issue opens through it.
        let real = try? ReadingVectors.decode(ReadingWork.self, ReadingVectors.amazingAdultFantasy)
        #expect(real.flatMap(ReadingWorkPresentation.primaryRead)?.label == "Continue · Issue 7")
        #expect(real.flatMap(ReadingWorkPresentation.primaryRead)?.sourceItemId == "8338")
    }

    // MARK: ReadingFormatMenu

    private let text = ReadingEdition(source: "storyteller", sourceItemId: "text", kind: "ebook", availability: "available")
    private let alice = ReadingEdition(source: "storyteller", sourceItemId: "alice", kind: "audiobook", narrator: "Alice",
                                       availability: "available")
    private var bob: ReadingEdition {
        var edition = alice
        edition.sourceItemId = "bob"
        edition.narrator = "Bob"
        return edition
    }
    private let aligned = ReadingEdition(source: "storyteller", sourceItemId: "alice", kind: "readaloud", availability: "available")

    private func book(_ editions: ReadingEdition...) -> ReadingWork {
        ReadingWork(id: "book", entityType: "work", kind: "ebook", title: "Test", editions: editions)
    }

    @Test func trackedQueuedUnknownAndUnfinishedDownloadsCannotBeOpened() {
        for state in ["", "unknown", "queued", "downloading", "downloaded", "processing", "tracked", "failed"] {
            var t = text
            t.availability = state
            var a = alice
            a.availability = state
            #expect(ReadingFormatMenu.forWork(book(t, a)).options.isEmpty, "\(state) must not be playable")
        }
    }

    @Test func formatsAreOptionsAndTheirStateIsWords() {
        let menu = ReadingFormatMenu.forWork(book(text, alice, bob, aligned))
        #expect(menu.options.map(\.choice.mode) == [.read, .listen, .listen, .readAlong])
        #expect(menu.options.filter { $0.choice.mode == .listen }.map(\.narration) == ["Alice", "Bob"])
        #expect(menu.availability == "Ebook ready  ·  Audiobook ready  ·  Read along ready")
    }

    @Test func theMainButtonSaysContinueForABookOpenedBeforeElseWhatItOpens() throws {
        let menu = ReadingFormatMenu.forWork(book(text, alice, bob, aligned))
        let first = try #require(menu.defaultChoice)
        #expect(menu.entryLabel(first, remembered: false, preview: nil) == "Read")
        #expect(menu.entryLabel(first, remembered: true, preview: nil) == "Continue")
        let bobs = try #require(menu.options.first { $0.choice.audio?.sourceItemId == "bob" && $0.choice.mode == .listen })
        #expect(menu.entryLabel(bobs.choice, remembered: true, preview: bobs.choice) == "Listen · Bob")
        let along = try #require(menu.options.first { $0.choice.mode == .readAlong })
        #expect(menu.entryLabel(along.choice, remembered: false, preview: along.choice) == "Read along · Alice")
        // Read's own words win: a book being read says how far.
        var started = book(text)
        started.progress = ReadingProgress(percentage: 0.49)
        let resume = ReadingFormatMenu.forWork(started)
        #expect(resume.entryLabel(try #require(resume.defaultChoice), remembered: false, preview: nil) == "Resume · 49%")
        let audioOnly = ReadingFormatMenu.forWork(book(alice))
        #expect(audioOnly.entryLabel(try #require(audioOnly.defaultChoice), remembered: false, preview: nil) == "Listen")
    }

    @Test func aReadAlongStillAligningCannotBeOpened() {
        var aligning = aligned
        aligning.availability = "processing"
        let menu = ReadingFormatMenu.forWork(book(text, alice, aligning))
        #expect(menu.availability.contains("Read along aligning"))
        #expect(!menu.options.contains { $0.choice.mode == .readAlong })
    }

    @Test func previewingAnotherNarrationLeavesTheRememberedOne() {
        let remembered = ReadingEntryPreference(mode: .listen, audioSourceItemId: "alice")
        let menu = ReadingFormatMenu.forWork(book(text, alice, bob), remembered: remembered)
        #expect(menu.options.first { $0.choice.audio?.sourceItemId == "bob" } != nil)
        #expect(menu.defaultChoice?.audio?.sourceItemId == "alice")
    }

    @Test func aMissingNarrationFallsBackToTheText() {
        var gone = alice
        gone.availability = "missing"
        let menu = ReadingFormatMenu.forWork(book(text, gone), remembered: ReadingEntryPreference(mode: .listen, audioSourceItemId: "alice"))
        #expect(menu.defaultChoice?.mode == .read)
        #expect(menu.options.count == 1)
    }

    @Test func comicsAndMangaKeepTheirOwnWords() {
        var comic = text
        comic.kind = "comic"
        let comicMenu = ReadingFormatMenu.forWork(book(comic))
        #expect(comicMenu.options.first?.label == "Read comic" && comicMenu.availability == "Comic ready")
        var manga = text
        manga.kind = "manga"
        let mangaMenu = ReadingFormatMenu.forWork(book(manga))
        #expect(mangaMenu.options.first?.label == "Read manga" && mangaMenu.availability == "Manga ready")
        let kavita = ReadingWork(kind: "comic", editions: [ReadingEdition(source: "kavita", sourceItemId: "91", kind: "comic",
                                                                          availability: "available")],
                                 sections: [ReadingSection(items: [ReadingSectionItem(sourceItemId: "901", kind: "comic", availability: "")])])
        let menu = ReadingFormatMenu.forWork(kavita)
        #expect(menu.options.first?.choice.text?.sourceItemId == "901" && menu.options.first?.label == "Read comic")
    }

    // MARK: ReadingEntryChoice

    private let audioA = ReadingEdition(source: "storyteller", sourceItemId: "audio-a", kind: "audiobook", availability: "available")
    private let audioB = ReadingEdition(source: "storyteller", sourceItemId: "audio-b", kind: "audiobook", availability: "available")
    private let alignedA = ReadingEdition(source: "storyteller", sourceItemId: "audio-a", kind: "readaloud", availability: "available")

    @Test func theFirstEntryChoosesTextWhenThereIsSomeElseAudio() {
        #expect(ReadingEntryChoice.choose(book(text, audioA), preference: nil)?.mode == .read)
        #expect(ReadingEntryChoice.choose(book(audioA), preference: nil)?.mode == .listen)
        #expect(ReadingEntryChoice.choose(book(), preference: nil) == nil)
    }

    @Test func theLastWayAndNarrationComeBackWhileTheyAreThere() {
        let work = book(text, audioA, audioB, alignedA)
        let listen = ReadingEntryChoice.choose(work, preference: ReadingEntryPreference(mode: .listen, audioSourceItemId: "audio-b"))
        #expect(listen?.mode == .listen && listen?.audio?.sourceItemId == "audio-b")
        let along = ReadingEntryChoice.choose(work, preference: ReadingEntryPreference(mode: .readAlong, audioSourceItemId: "audio-a"))
        #expect(along?.mode == .readAlong && along?.aligned?.sourceItemId == "audio-a")
        #expect(ReadingEntryChoice.availableModes(work) == [.read, .listen, .readAlong])
        // Read along takes exactly the narration that owns the aligned edition.
        let other = ReadingEntryChoice.choose(work, preference: ReadingEntryPreference(mode: .readAlong, audioSourceItemId: "audio-b"))
        #expect(other?.mode == .read && other?.aligned == nil)
    }

    @Test func aGoneNarrationFallsBackWithoutBorrowingAnotherAlignment() {
        let selected = ReadingEntryChoice.choose(book(text, audioB, alignedA),
                                                 preference: ReadingEntryPreference(mode: .readAlong, audioSourceItemId: "audio-a"))
        #expect(selected?.mode == .read && selected?.aligned == nil)
        let audioOnly = ReadingEntryChoice.choose(book(audioB), preference: ReadingEntryPreference(mode: .readAlong, audioSourceItemId: "audio-a"))
        #expect(audioOnly?.mode == .listen && audioOnly?.audio?.sourceItemId == "audio-b")
    }

    @Test func aMissingEbookFallsBackToAudioAndAnotherEditionStillReads() {
        var missing = text
        missing.availability = "missing"
        #expect(ReadingEntryChoice.choose(book(missing, audioA), preference: ReadingEntryPreference(mode: .read))?.mode == .listen)
        var old = text
        old.sourceItemId = "old-text"
        old.availability = "missing"
        var new = text
        new.sourceItemId = "new-text"
        let selected = ReadingEntryChoice.choose(book(old, new), preference: nil)
        #expect(selected?.mode == .read && selected?.text?.sourceItemId == "new-text")
    }

    @Test func aRememberedWayIsKeptAsWords() {
        let preference = ReadingEntryPreference(mode: .listen, audioSourceItemId: "3726292328809367")
        #expect(ReadingEntryPreference.decode(preference.encoded) == preference)
        #expect(ReadingEntryPreference.decode("READ|") == ReadingEntryPreference(mode: .read))
        #expect(ReadingEntryPreference.decode("SING|x") == nil && ReadingEntryPreference.decode(nil) == nil)
    }

    // MARK: ReadingFormatStatus

    private func edition(_ kind: String, _ state: String = "available", id: String = "1") -> ReadingEdition {
        ReadingEdition(source: "storyteller", sourceItemId: id, kind: kind, availability: state)
    }

    @Test func onlyVerifiedSynchronizedPairsAreReady() {
        func states(_ editions: ReadingEdition...) -> [FormatReadiness] {
            ReadingFormatStatus.forWork(ReadingWork(editions: editions)).map(\.readiness)
        }
        #expect(states(edition("ebook"), edition("audiobook")) == [.ready, .ready, .missing])
        #expect(states(edition("ebook"), edition("audiobook"), edition("readaloud")).last == .ready)
        #expect(states(edition("readaloud")).last == .unknown)
        #expect(states(edition("readaloud", "processing")).last == .pending)
    }

    @Test func lookupFailuresStayUnknownAndSeriesHaveNoFormatRow() {
        #expect(ReadingFormatStatus.forWork(ReadingWork(entityType: "collection")).isEmpty)
        #expect(ReadingFormatStatus.unknown().allSatisfy { $0.readiness == .unknown })
        #expect(ReadingFormatStatus.forWork(ReadingWork(editions: [edition("audiobook"), edition("audiobook", id: "2")])).count == 3)
    }

    // MARK: Sorting a library

    @Test func aLibraryOffersTheSortsItsServerHonours() throws {
        let libraries = try ReadingVectors.decode(ReadingLibrariesResponse.self, ReadingVectors.libraries).libraries
        #expect(ReadingSortFields.forLibrary(libraries[0]).map(\.id) == ["title", "series", "author", "added", "last_read"])
        #expect(ReadingSortFields.forLibrary(libraries[2]).map(\.id) == ["title", "series", "added", "last_read"])
        #expect(ReadingSortFields.forLibrary(ReadingLibrary(id: "x", title: "X")).map(\.id) == ["title"])
        #expect(SortPreference(field: "last_read", ascending: false).directionLabel == "Most recently read first")
        #expect(SortPreference(field: "title", ascending: true).directionLabel == "A to Z")
        #expect(!SortPreference.forField("last_read").ascending && SortPreference.forField("author").ascending)
        #expect(ReadingLibraryTiles.summary(libraries) == "3 libraries from Storyteller and Kavita")
        var names = ReadingLibraryNames()
        names.remember(libraries)
        #expect(names.name(of: "kavita:2") == "My Marvelous Year" && names.name(of: "kavita:9") == nil)
    }

    // MARK: Discover and search

    @Test func underAllEachRowSaysWhatItHoldsAndAnEmptyRowIsLeftOut() {
        let items = [ReadingItem(key: "a", title: "Atomic Habits")]
        let rows = [
            ReadingDiscoverRow(id: "trending", title: "Trending now", contentType: ReadingType.ebook, items: items),
            ReadingDiscoverRow(id: "librivox", title: "Free audiobooks", contentType: ReadingType.audiobook),
            ReadingDiscoverRow(id: "trending", title: "Trending now", contentType: ReadingType.manga, items: items),
            ReadingDiscoverRow(id: "popular", title: "Popular", contentType: ReadingType.manga, items: items),
            ReadingDiscoverRow(id: "fresh", title: "New light novels", contentType: ReadingType.lightNovel, items: items),
        ]
        #expect(ReadingDiscoverRows.shown(rows, filter: ReadingType.all).map(\.title)
                == ["Trending now · Ebooks", "Trending now · Manga", "Popular · Manga", "New light novels"])
        #expect(ReadingDiscoverRows.shown(rows.filter { $0.contentType == ReadingType.manga }, filter: ReadingType.manga).map(\.title)
                == ["Trending now", "Popular"])
    }

    @Test func theFirstTitleIsFeaturedOnlyWithACoverANameAndSomethingToSay() {
        let full = ReadingItem(key: "a", title: "Atomic Habits", author: "James Clear", cover: "/v1/img/x")
        let rest = ReadingItem(key: "b", title: "Deep Work")
        #expect(ReadingDiscoverRows.feature([full, rest])?.key == "a")
        #expect(ReadingDiscoverRows.shelf([full, rest]).map(\.key) == ["b"])
        var bare = full
        bare.cover = ""
        #expect(ReadingDiscoverRows.feature([bare, rest]) == nil)
        #expect(ReadingDiscoverRows.shelf([bare, rest]).count == 2)
        var silent = full
        silent.author = ""
        #expect(ReadingDiscoverRows.feature([silent]) == nil)
        silent.description = "A book about habits."
        #expect(ReadingDiscoverRows.feature([silent])?.key == "a")
    }

    @Test func closeResultsShowFirstAndBroaderOnesOnRequest() {
        let close = [ReadingItem(title: "Light Bringer"), ReadingItem(title: "Light Bringer: A Red Rising Novel")]
        let broader = [ReadingItem(title: "The Black Prism"), ReadingItem(title: "And They Found Dragons")]
        let initial = ReadingSearchPresentation(close: close, broader: broader)
        #expect(initial.visibleResults == close && initial.hasBroaderResults)
        #expect(initial.summary == "2 close matches · 2 broader results")
        #expect(initial.toggledBroader().visibleResults == close + broader)
        #expect(initial.toggledBroader().toggledBroader().visibleResults == close)
        let onlyBroader = ReadingSearchPresentation.forResults(close: [], broader: broader)
        #expect(onlyBroader.summary == "2 broader results · no close matches")
        #expect(onlyBroader.visibleResults == broader && !onlyBroader.canToggle)
        #expect(!ReadingSearchPresentation().hasBroaderResults)
    }

    // MARK: Requests

    @Test func theFormStartsOnTheDefaultProfileAndSendsWithoutGrabbing() throws {
        let options = ReadingRequestOptions(key: "reading:abc", title: "Red Rising", author: "Pierce Brown",
                                            modes: [ReadingRequestMode(id: "single", label: "This book"),
                                                    ReadingRequestMode(id: "series", label: "Choose books from series", requiresSeriesPreview: true)],
                                            qualityProfiles: [ReadingQualityProfile(id: 3, label: "Any"),
                                                              ReadingQualityProfile(id: 7, label: "English EPUB", isDefault: true)])
        var draft = ReadingRequestDraft(options: options)
        #expect(draft.profile?.id == 7 && draft.mode?.id == "single" && draft.usable)
        #expect(draft.submitLabel == "Choose release" && !draft.needsSeriesPreview)
        #expect(draft.body() == ReadingCreateRequestBody(key: "reading:abc", mode: "single", qualityProfileId: 7, monitoring: "none"))
        draft.modeIndex = 1
        #expect(draft.submitLabel == "Review books" && draft.needsSeriesPreview)
        #expect(draft.body(seriesId: "OL100L", bookIds: ["OL1W"])?.bookIds == ["OL1W"])
        #expect(draft.heading(fallbackTitle: "x") == "Download Red Rising")
        #expect(draft.subtitle == "Pierce Brown · You choose the torrent next; automatic grabbing stays off.")
        #expect(!ReadingRequestDraft(options: ReadingRequestOptions(modes: options.modes)).usable)
        #expect(ReadingRequestDraft.scopeDetail(ReadingSeriesPreview(author: "Pierce Brown", books: [ReadingSeriesPreviewBook(id: "1")]))
                == "1 book · Pierce Brown")
    }

    @Test func aSeriesSelectionTicksOnlyMissingBooks() {
        let books = [ReadingSeriesPreviewBook(id: "1", inLibrary: true), ReadingSeriesPreviewBook(id: "2"),
                     ReadingSeriesPreviewBook(id: "3", selected: false)]
        var selection = ReadingSeriesSelection(books: books)
        #expect(selection.selectedIds == ["2"])
        let owned = selection.toggle(0)
        #expect(!owned)
        let ticked = selection.toggle(2)
        #expect(ticked && selection.selectedIds == ["2", "3"])
        selection.toggleAllMissing()
        #expect(selection.selectedIds.isEmpty)
        selection.toggleAllMissing()
        #expect(selection.selectedIds == ["2", "3"])
    }

    @Test func aRequestsStateComesFromItsTransfers() {
        let manual = ReadingAcquisitionState.from(seriesId: 42, downloads: [], manualSelection: true)
        #expect(manual.stage == .awaitingChoice && manual.nextAction == "Choose release")
        let searching = ReadingAcquisitionState.from(seriesId: 11, downloads: [])
        #expect(searching.stage == .searching && !searching.terminal && searching.message.contains("No transfer"))
        let importing = ReadingAcquisitionState.from(seriesId: 11, downloads: [ReadingDownloadItem(seriesId: 11, title: "Recursion",
                                                                                                   status: "completed", progressPercent: 100)])
        #expect(importing.stage == .importing && !importing.message.contains("Ready"))
        let mine = ReadingAcquisitionState.from(seriesId: 11, downloads: [
            ReadingDownloadItem(seriesId: 10, title: "Other book", status: "failed"),
            ReadingDownloadItem(seriesId: 11, title: "Recursion", status: "downloading", progressPercent: 42),
        ])
        #expect(mine.stage == .downloading && mine.message == "Downloading · 42%")
        let imported = ReadingAcquisitionState.from(seriesId: 11, downloads: [ReadingDownloadItem(seriesId: 11, title: "Recursion",
                                                                                                  status: "imported")])
        #expect(imported.stage == .imported && imported.terminal && imported.nextAction == "Open Library")
        let failed = ReadingAcquisitionState.from(seriesId: 11, downloads: [ReadingDownloadItem(seriesId: 11, title: "Recursion",
                                                                                                status: "failed", failed: true)])
        #expect(failed.stage == .failed && failed.terminal && failed.message.contains("Transfers"))
    }

    @Test func anEarlierRequestIsFoundOnlyFromOneExactTitleAndKind() {
        let rows = [
            ReadingDownloadItem(seriesId: 11, contentType: "ebook", title: "Recursion", releaseTitle: "Recursion by Blake Crouch EPUB",
                                status: "imported"),
            ReadingDownloadItem(seriesId: 12, contentType: "ebook", title: "Recursive Book", status: "imported"),
        ]
        #expect(ReadingAcquisitionState.findSeriesId(title: "Recursion", author: "Blake Crouch", contentType: "ebook", downloads: rows) == 11)
        #expect(ReadingAcquisitionState.findSeriesId(title: "Recursion", author: "Tony Ballantyne", contentType: "ebook", downloads: rows) == 0)
        #expect(ReadingAcquisitionState.findSeriesId(title: "Recursion", author: "", contentType: "ebook", downloads: rows) == 0)
        #expect(ReadingAcquisitionState.findSeriesId(title: "Recursion", author: "Blake Crouch", contentType: "audiobook", downloads: rows) == 0)
        let twice = rows + [ReadingDownloadItem(seriesId: 13, contentType: "ebook", title: "Recursion",
                                                releaseTitle: "Recursion Blake Crouch EPUB")]
        #expect(ReadingAcquisitionState.findSeriesId(title: "Recursion", author: "Blake Crouch", contentType: "ebook", downloads: twice) == 0)
        #expect(ReadingRequestActionPolicy.showAction(tracked: false, canRequest: true, hasReleaseTargets: false))
        #expect(!ReadingRequestActionPolicy.showAction(tracked: true, canRequest: true, hasReleaseTargets: false))
        #expect(ReadingRequestActionPolicy.showAction(tracked: true, canRequest: false, hasReleaseTargets: true))
    }

    @Test func theReleasePickerSaysWhatItFoundAndWhy() {
        #expect(ReadingReleasePickerPolicy.summary(total: 0, outsideProfile: 0, wrongFormat: 0, indexerErrors: 0, showingSaved: false)
                == "No releases found · Search again later")
        #expect(ReadingReleasePickerPolicy.summary(total: 12, outsideProfile: 3, wrongFormat: 1, indexerErrors: 2, showingSaved: true)
                == "12 releases · 3 outside profile · 1 wrong format · 2 indexer errors · live search failed; showing saved results")
        #expect(ReadingReleasePickerPolicy.summary(total: 0, outsideProfile: 0, wrongFormat: 0, indexerErrors: 1, showingSaved: false)
                == "No releases yet · 1 indexer errors · Try Search again")
        #expect(ReadingReleasePickerPolicy.hasTargetChooser(2) && !ReadingReleasePickerPolicy.hasTargetChooser(1))
        let release = ReadingRelease(id: "r", title: "Red Rising EPUB", indexer: "Indexer", sizeBytes: 4_300_000, seeders: 12,
                                     freeleech: true, format: "EPUB", formatStatus: "compatible")
        #expect(ReadingReleasePickerPolicy.info(release) == "4.1 MB · 12 seeds · Indexer · freeleech · EPUB · matches request")
        #expect(ReadingReleasePickerPolicy.warning(release).isEmpty)
        var wrong = release
        wrong.formatStatus = "incompatible"
        #expect(!wrong.canGrab && ReadingReleasePickerPolicy.warning(wrong) == "Wrong format for this request")
        var owned = release
        owned.ownership = "in-library"
        #expect(ReadingReleasePickerPolicy.warning(owned) == "In library")
    }

    // MARK: Transfers

    @Test func transfersSayTheirStateInAWordAndInFull() {
        #expect(ReadingTransferSummary.stageLabel("completed", failed: false) == "Downloaded · adding to Library")
        #expect(ReadingTransferSummary.stageLabel("imported", failed: false) == "Ready in Library")
        #expect(!ReadingTransferSummary.showProgress("completed", failed: false))
        #expect(ReadingTransferSummary.showProgress("downloading", failed: false))
        #expect(ReadingTransferSummary.chipLabel("downloading", failed: true) == "Failed")
        #expect(ReadingTransferSummary.chipLabel("imported", failed: false) == "In library")
        #expect(ReadingTransferSummary.chipLabel("completed", failed: false) == "Importing")
        #expect(ReadingTransferSummary.chipLabel("retry_pending", failed: false) == "Retry ready")
        #expect(ReadingTransferSummary.chipLabel("awaiting_choice", failed: false) == "Awaiting choice")
        #expect(ReadingTransferSummary.fallback("imported", failed: false) == "Ready in Library")
        #expect(ReadingTransferSummary.fallback("completed", failed: false) == "Adding to Library")
        #expect(ReadingTransferSummary.fallback("failed", failed: true) == "Needs attention")
        #expect(ReadingTransferSummary.fallback("queued", failed: false) == "Waiting for BookKeeprr")
        #expect(ReadingTransferSummary.tone("imported", failed: false) == .good)
        #expect(ReadingTransferSummary.tone("importing", failed: false) == .waiting)
        #expect(ReadingTransferSummary.tone("queued", failed: true) == .bad)
    }

    @Test func thePagesLineSaysNothingYetWhatNeedsAttentionOrHowMany() {
        #expect(ReadingTransferSummary.status([]) == "No book transfers yet.")
        let queued = ReadingDownloadItem(id: "1", status: "queued")
        #expect(ReadingTransferSummary.status([queued]) == "1 BookKeeprr transfer")
        #expect(ReadingTransferSummary.status([queued, queued]) == "2 BookKeeprr transfers")
        let failed = ReadingDownloadItem(id: "2", status: "failed", failed: true)
        #expect(ReadingTransferSummary.status([queued, failed]) == "1 transfer needs attention")
        #expect(ReadingTransferSummary.status([failed, failed]) == "2 transfers need attention")
    }

    // MARK: PollSchedule

    @Test func transfersAreAskedFastWhileMovingOrSettlingAndBackOffAfterFailures() {
        #expect(PollSchedule.next(active: true, failures: 0) == .seconds(2))
        #expect(PollSchedule.next(active: false, failures: 0) == .seconds(10))
        #expect(PollSchedule.next(active: false, failures: 0, settling: true) == .seconds(2))
        #expect(PollSchedule.next(active: true, failures: 1) == .seconds(5))
        #expect(PollSchedule.next(active: true, failures: 2) == .seconds(15))
        #expect(PollSchedule.next(active: true, failures: 9) == .seconds(30))
        let quiet = PollCadence(active: .seconds(15), idle: nil)
        #expect(PollSchedule.next(active: false, failures: 0, cadence: quiet) == nil)
        #expect(PollSchedule.next(active: false, failures: 1, cadence: quiet) == .seconds(15))
    }

    @Test func transfersGroupByKindWithoutReorderingAKind() {
        let items = [ReadingDownloadItem(id: "comic", contentType: "comic"), ReadingDownloadItem(id: "book-a", contentType: "ebook"),
                     ReadingDownloadItem(id: "manga", contentType: "manga"), ReadingDownloadItem(id: "book-b", contentType: "ebook")]
        #expect(ReadingTransferSummary.grouped(items).map(\.id) == ["book-a", "book-b", "comic", "manga"])
        #expect(ReadingTransferSummary.groupLabel("ebook") == "Books")
        let moving = ReadingDownloadItem(status: "downloading", downloadSpeedBytesPerSecond: 4_096, etaSeconds: 90, sizeBytes: 12_345)
        #expect(ReadingTransferSummary.line(moving) == "12.1 KB · 4.0 KB/s · 1m left")
        #expect(ReadingTransferSummary.line(ReadingDownloadItem(status: "imported")) == "Ready in Library")
        #expect(ReadingTransferSummary.line(ReadingDownloadItem(status: "queued")) == "Queued · Waiting for BookKeeprr")
        #expect(ReadingTransferSummary.summary(items + [ReadingDownloadItem(status: "downloading"), ReadingDownloadItem(failed: true)])
                == "1 downloading · 1 failed")
    }
}
