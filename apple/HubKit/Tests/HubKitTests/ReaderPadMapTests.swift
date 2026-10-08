import Foundation
import Testing
@testable import HubKit

/// Android's `ReaderPadMapTest`, case for case: one controller language for
/// every reader (#16, X1), then the keyboard that speaks it (#25).
struct ReaderPadMapTests {
    private let comic = ReaderPadState(.comic)
    private let book = ReaderPadState(.book)
    private let audiobook = ReaderPadState(.audiobook, controlsVisible: true)

    /// Every action the pad can send, both clicks pressed and let go.
    private var everything: [PadAction] {
        [.activate, .back, .primary, .secondary, .section(-1), .section(1), .page(.up), .page(.down), .menu, .refresh,
         .pan(dx: 0.1, dy: -0.2), .click(.left), .click(.left, down: false), .click(.right), .click(.right, down: false)]
            + PadDirection.allCases.map { .step($0) }
    }

    private func on(_ state: ReaderPadState, _ action: PadAction) -> ReaderCommand { ReaderPadMap.command(state, action) }

    private func with(_ state: ReaderPadState, _ change: (inout ReaderPadState) -> Void) -> ReaderPadState {
        var copy = state
        change(&copy)
        return copy
    }

    @Test func readAlongTheShouldersStepThroughSentences() {
        let narrating = ReaderPadState(.book, narration: true)
        #expect(on(narrating, .section(1)) == .sentence(1))
        #expect(on(narrating, .section(-1)) == .sentence(-1))
        // In the menu, or without narration, they still turn pages.
        #expect(on(with(narrating) { $0.controlsVisible = true }, .section(1)) == .page(1))
        #expect(on(book, .section(1)) == .page(1))
        let sheet = ReaderPadMap.sheet(.book, state: narrating)
        #expect(sheet.contains { $0.keys == [ReaderPadMap.l1] && $0.does == "Previous sentence" })
        #expect(sheet.contains { $0.keys == [ReaderPadMap.r1] && $0.does == "Next sentence" })
    }

    @Test func comicsReadOnWithABackWithBAndLeaveWithSelect() {
        #expect(on(comic, .activate) == .forward)
        #expect(on(comic, .back) == .backward)
        #expect(on(comic, .refresh) == .leave)
        #expect(on(comic, .primary) == .page(1))
        #expect(on(comic, .secondary) == .page(-1))
        #expect(on(comic, .step(.left)) == .move(.left))
        // With the controls open B closes them first, and A presses the control in focus.
        let open = with(comic) { $0.controlsVisible = true }
        #expect(on(open, .back) == .controls(false))
        #expect(on(open, .activate) == .choose)
        #expect(on(open, .step(.up)) == .focus(.up))
        // A page that failed to open: Select tries again rather than leaving.
        #expect(on(with(comic) { $0.loading = true }, .refresh) == .retry)
    }

    @Test func aBooksBOpensTheMenuAndBAgainLeavesTheBook() {
        #expect(on(book, .back) == .controls(true))
        #expect(on(with(book) { $0.controlsVisible = true }, .back) == .leave)
        #expect(on(book, .activate) == .forward)
        #expect(on(book, .section(-1)) == .page(-1))
        #expect(on(book, .page(.down)) == .chapter(1))
        #expect(on(book, .refresh) == .display)
    }

    @Test func anAudiobooksBLeavesAndItsShouldersChangePartRatherThanTheTab() {
        #expect(on(audiobook, .back) == .leave)
        #expect(on(audiobook, .section(-1)) == .chapter(-1))
        #expect(on(audiobook, .section(1)) == .chapter(1))
        #expect(on(audiobook, .page(.up)) == .seek(-10))
        #expect(on(with(audiobook) { $0.seekSeconds = 30 }, .page(.down)) == .seek(30))
        #expect(on(audiobook, .primary) == .playPause)
    }

    @Test func theRightStickPansAComicAndScrollsOnlyAScrollingBook() {
        #expect(on(comic, .pan(dx: 0.1, dy: -0.2)) == .glide(dx: 0.1, dy: -0.2))
        #expect(on(book, .pan(dx: 0, dy: 0.5)) == .ignore)
        let scrolling = with(book) { $0.scrolling = true }
        #expect(on(scrolling, .pan(dx: 0, dy: 0.5)) == .glide(dx: 0, dy: 0.5))
        // A scrolling book's D-pad scrolls up and down, and still turns sideways.
        #expect(on(scrolling, .step(.down)) == .scroll(.down))
        #expect(on(scrolling, .step(.right)) == .page(1))
        #expect(on(book, .step(.down)) == .controls(true))
    }

    @Test func l3IsAComicsMagnifierWhileHeldAndR3OpensTheKeysEverywhere() {
        #expect(on(comic, .click(.left)) == .magnifier(true))
        #expect(on(comic, .click(.left, down: false)) == .magnifier(false))
        for state in [comic, book, audiobook] {
            #expect(on(state, .click(.right)) == .keys)
            #expect(on(state, .click(.right, down: false)) == .ignore)
        }
        #expect(on(with(book) { $0.narration = true }, .click(.left)) == .followNarration)
        // On the page, L3 shows where you are the next way (#42); over the menu, nothing.
        #expect(on(book, .click(.left)) == .nextPlace)
        #expect(on(book, .click(.left, down: false)) == .ignore)
        #expect(on(with(book) { $0.controlsVisible = true }, .click(.left)) == .ignore)
    }

    @Test func theHintRowNamesOnlyWhatTheKeysDoNow() {
        func labels(_ state: ReaderPadState) -> [String: String] {
            Dictionary(ReaderPadMap.hints(state).map { ($0.glyph, $0.label) }, uniquingKeysWith: { first, _ in first })
        }
        let comicHints = labels(with(comic) { $0.controlsVisible = true })
        #expect(comicHints[ReaderPadMap.a] == "Choose")
        #expect(comicHints[ReaderPadMap.b] == "Hide controls")
        #expect(comicHints[ReaderPadMap.select] == "Leave")
        #expect(comicHints[ReaderPadMap.r3] == "Keys")
        let bookHints = labels(with(book) { $0.controlsVisible = true })
        #expect(bookHints[ReaderPadMap.b] == "Leave the book")
        #expect(bookHints[ReaderPadMap.start] == "Back to the page")
        #expect(ReaderPadMap.hints(audiobook).first { $0.glyph == ReaderPadMap.l1 }?.label == "Previous part")
        // Each chip does what it says when tapped: the label is its action's.
        for state in [with(comic) { $0.controlsVisible = true }, with(book) { $0.controlsVisible = true }, audiobook] {
            for hint in ReaderPadMap.hints(state) {
                #expect(!hint.label.isEmpty)
                #expect(ReaderPadMap.describe(state.kind, ReaderPadMap.command(state, hint.action)) == hint.label)
            }
        }
    }

    @Test func theControlsSheetListsEveryKeyThatDoesSomethingAsTheKeysDoIt() {
        func lines(_ kind: ReaderKind, _ state: ReaderPadState? = nil) -> [String: String] {
            Dictionary(ReaderPadMap.sheet(kind, state: state).map { ($0.keys.joined(separator: " "), $0.does) },
                       uniquingKeysWith: { first, _ in first })
        }
        let comicSheet = lines(.comic)
        #expect(comicSheet[ReaderPadMap.a] == "Forward")
        #expect(comicSheet[ReaderPadMap.b] == "Back")
        #expect(comicSheet[ReaderPadMap.select] == "Leave")
        #expect(comicSheet[ReaderPadMap.dpad] == "Move around the page")
        #expect(comicSheet[ReaderPadMap.rightStick] == "Pan")
        #expect(comicSheet[ReaderPadMap.l3] == "Magnifier, while held")
        #expect(comicSheet[ReaderPadMap.l1] == "Zoom out")
        let bookSheet = lines(.book)
        #expect(bookSheet[ReaderPadMap.b] == "Menu")
        #expect(bookSheet[ReaderPadMap.dpadSides] == "Previous page, next page")
        #expect(bookSheet[ReaderPadMap.dpadEnds] == "Menu")
        // Paged, the stick does nothing, so it is not listed; L3 is the corner's.
        #expect(bookSheet[ReaderPadMap.rightStick] == nil)
        #expect(bookSheet[ReaderPadMap.l3] == "Next reading progress")
        let scrolling = lines(.book, ReaderPadState(.book, scrolling: true, narration: true))
        #expect(scrolling[ReaderPadMap.dpadEnds] == "Scroll")
        #expect(scrolling[ReaderPadMap.rightStick] == "Scroll")
        #expect(scrolling[ReaderPadMap.l3] == "Back to the narration")
        let audioSheet = lines(.audiobook)
        #expect(audioSheet[ReaderPadMap.b] == "Leave")
        #expect(audioSheet[ReaderPadMap.dpad] == "Move between controls")
        for kind in [ReaderKind.comic, .book, .audiobook] {
            for line in ReaderPadMap.sheet(kind) { #expect(!line.does.isEmpty && !line.keys.isEmpty) }
        }
    }

    @Test func everyKeyIsAReadersOwnInEveryReaderAndState() {
        let states = [comic, with(comic) { $0.controlsVisible = true }, book, with(book) { $0.controlsVisible = true },
                      with(book) { $0.scrolling = true }, audiobook]
        for state in states {
            for action in everything { _ = ReaderPadMap.command(state, action) }
        }
        // A shoulder is never left to the app, which would read it as a tab switch.
        #expect(states.allSatisfy { ReaderPadMap.command($0, .section(1)) != .ignore })
    }

    @Test func aKeyboardSpeaksTheControllersLanguage() {
        #expect(ReaderKeyboard.action(.space) == .activate)
        #expect(ReaderKeyboard.action(.returnKey) == .activate)
        #expect(ReaderKeyboard.action(.delete) == .back)
        #expect(ReaderKeyboard.action(.left) == .step(.left))
        #expect(ReaderKeyboard.action(.pageDown) == .primary)
        #expect(ReaderKeyboard.action(.pageUp) == .secondary)
        #expect(ReaderKeyboard.action(.minus) == .section(-1))
        #expect(ReaderKeyboard.action(.plus) == .section(1))
        // Escape leaves, closing what is open first: the reader carries it out.
        #expect(ReaderKeyboard.action(.escape) == nil)
        let sheet = Dictionary(ReaderKeyboard.sheet(.comic).map { ($0.keys.joined(separator: " "), $0.does) },
                               uniquingKeysWith: { first, _ in first })
        #expect(sheet["Space Return"] == "Forward")
        #expect(sheet["Delete"] == "Back")
        #expect(sheet["Arrows"] == "Move around the page")
        #expect(sheet["Page Down"] == "Next page")
        #expect(sheet["Page Up"] == "Previous page")
        #expect(sheet["\u{2212}"] == "Zoom out")
        #expect(sheet["="] == "Zoom in")
        #expect(sheet["Escape"] == "Leave")
    }

    @Test func aBooksArrowsSayTheSidesAndTheEndsApartAsTheDPadDoes() {
        let sheet = Dictionary(ReaderKeyboard.sheet(.book).map { ($0.keys.joined(separator: " "), $0.does) },
                               uniquingKeysWith: { first, _ in first })
        // "Arrows: Previous page" told half of it.
        #expect(sheet["Arrows"] == nil)
        #expect(sheet["← →"] == "Previous page, next page")
        #expect(sheet["↑ ↓"] == "Menu")
        // The controller's lines say the same of the D-pad.
        let pad = Dictionary(ReaderPadMap.sheet(.book).map { ($0.keys.joined(separator: " "), $0.does) },
                             uniquingKeysWith: { first, _ in first })
        #expect(pad.values.contains("Previous page, next page"))
    }
}
