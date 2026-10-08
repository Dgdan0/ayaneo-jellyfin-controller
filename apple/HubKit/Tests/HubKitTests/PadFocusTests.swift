import CoreGraphics
import Foundation
import Testing
@testable import HubKit

/// The focus engine (#46, C) on the Pocket's own cases: `FocusGuard`'s tests
/// as Android has them, then rows by position (`StripNav`), rows of rows
/// (`RowStep`), a grid, where Back returns (`FocusPlace`), the ring
/// (`InputModeTracker`) and how far to scroll.
struct PadFocusTests {
    // MARK: The guard (Android's FocusGuardTest)

    /// An 82dp card at 2.25 density is ~185px wide, ~330px tall.
    private func card(_ x: CGFloat, y: CGFloat = 300) -> CGRect {
        CGRect(x: x, y: y, width: 185, height: 330)
    }

    @Test func theGuardTakesTheNextCardAndRefusesAWrap() {
        #expect(PadGuard.accepts(.right, from: card(0), to: card(195)))
        #expect(PadGuard.accepts(.left, from: card(195), to: card(0)))
        // The bug it exists for: right on the last card loaded found the first.
        #expect(!PadGuard.accepts(.right, from: card(1500), to: card(0)))
        #expect(!PadGuard.accepts(.left, from: card(0), to: card(1500)))
        // A grid still refuses a wrap within the same line.
        #expect(!PadGuard.accepts(.right, from: card(1700), to: card(0), mode: .grid))
    }

    @Test func leftAndRightStayInTheirBandUnlessTheLineWrapsOnAGrid() {
        // Off the end of a row: nothing, by default.
        #expect(!PadGuard.accepts(.right, from: card(1500, y: 300), to: card(0, y: 700)))
        // Never up to the tab bar, grid or not.
        let tab = CGRect(x: 300, y: 60, width: 200, height: 90)
        #expect(!PadGuard.accepts(.right, from: card(1500, y: 300), to: tab))
        #expect(!PadGuard.accepts(.right, from: card(1500, y: 300), to: tab, mode: .grid))
        // A grid reads on below, and back above.
        #expect(PadGuard.accepts(.right, from: card(1700, y: 300), to: card(0, y: 700), mode: .grid))
        #expect(PadGuard.accepts(.left, from: card(0, y: 700), to: card(1700, y: 300), mode: .grid))
        // Along a tab bar is the same band.
        let discover = CGRect(x: 20, y: 60, width: 200, height: 90)
        let library = CGRect(x: 240, y: 60, width: 160, height: 90)
        #expect(PadGuard.accepts(.right, from: discover, to: library))
        #expect(PadGuard.accepts(.left, from: library, to: discover))
        // Up and down are never second-guessed.
        #expect(PadGuard.accepts(.up, from: card(600, y: 300), to: card(0, y: 700)))
        #expect(PadGuard.accepts(.down, from: card(600, y: 300), to: card(0, y: 0)))
    }

    @Test func theSlackTakesAMisalignedNeighbourButNotAWrap() {
        let scaled = CGRect(x: 1000, y: 300, width: 200, height: 340)
        let next = CGRect(x: 995, y: 305, width: 185, height: 330)
        #expect(PadGuard.accepts(.right, from: scaled, to: next))
        // Half a card of tolerance, not half a screen.
        #expect(!PadGuard.accepts(.right, from: card(1500), to: card(1200)))
    }

    @Test func sharingHeightIsTheSameLineAndTouchingIsNot() {
        #expect(PadGuard.overlapsVertically(card(0, y: 300), card(900, y: 300)))
        #expect(PadGuard.overlapsVertically(card(0, y: 300), card(900, y: 500)))
        #expect(!PadGuard.overlapsVertically(card(0, y: 300), card(900, y: 700)))
        #expect(!PadGuard.overlapsVertically(CGRect(x: 0, y: 100, width: 185, height: 300),
                                             CGRect(x: 0, y: 400, width: 185, height: 300)))
    }

    // MARK: A Home page: tabs, a hero's buttons, rows of cards

    /// Tabs at the top (in no group); Play and Details; Continue watching (ten
    /// cards, six drawn by the lazy row), an empty Favourites, Recently added
    /// (twenty posters, ten drawn).
    private static func home(drawn: Bool = true) -> PadMap {
        var frames: [String: CGRect] = [
            "t-home": CGRect(x: 0, y: 0, width: 80, height: 40),
            "t-library": CGRect(x: 100, y: 0, width: 80, height: 40),
            "play": CGRect(x: 20, y: 200, width: 100, height: 40),
            "details": CGRect(x: 140, y: 200, width: 100, height: 40),
        ]
        if drawn {
            for i in 0..<6 { frames["c\(i)"] = CGRect(x: 20 + CGFloat(i) * 188, y: 300, width: 176, height: 120) }
        }
        for i in 0..<10 { frames["l\(i)"] = CGRect(x: 20 + CGFloat(i) * 92, y: 480, width: 82, height: 150) }
        return PadMap(frames: frames, groups: [
            PadGroup("tabs", .row, members: ["t-home", "t-library"]),
            PadGroup("hero", .row, members: ["play", "details"]),
            PadGroup("continue", .row, members: (0..<10).map { "c\($0)" }),
            PadGroup("favourites", .row, members: []),
            PadGroup("latest", .row, members: (0..<20).map { "l\($0)" }),
            PadGroup("rows", .column, members: ["hero", "continue", "favourites", "latest"]),
        ])
    }

    @Test func aRowMovesByPositionToACardNotYetDrawnAndStopsAtItsEnds() {
        let map = Self.home()
        #expect(PadFocus.step(from: "c0", .right, in: map) == .to("c1"))
        // The lazy row has not drawn c6: still next, for the page to scroll in.
        #expect(PadFocus.step(from: "c5", .right, in: map) == .to("c6"))
        // At either end the press is the row's, and does nothing.
        #expect(PadFocus.step(from: "c9", .right, in: map) == .stay)
        #expect(PadFocus.step(from: "c0", .left, in: map) == .stay)
        #expect(PadFocus.step(from: "details", .right, in: map) == .stay)
    }

    @Test func aRowOfRowsMovesOnFromTheEndOfTheFirst() {
        // A title's header: its buttons, then its icons, one line.
        let map = PadMap(frames: [
            "play": CGRect(x: 0, y: 0, width: 80, height: 40), "trailer": CGRect(x: 90, y: 0, width: 80, height: 40),
            "watched": CGRect(x: 200, y: 0, width: 40, height: 40), "more": CGRect(x: 250, y: 0, width: 40, height: 40),
        ], groups: [
            PadGroup("buttons", .row, members: ["play", "trailer"]),
            PadGroup("icons", .row, members: ["watched", "more"]),
            PadGroup("header", .row, members: ["buttons", "icons"]),
        ])
        #expect(PadFocus.step(from: "trailer", .right, in: map) == .to("watched"))
        #expect(PadFocus.step(from: "watched", .left, in: map) == .to("trailer"))
        #expect(PadFocus.step(from: "more", .right, in: map) == .stay)
        #expect(PadFocus.step(from: "play", .left, in: map) == .stay)
        #expect(PadFocus.step(from: "play", .down, in: map) == .leave)
    }

    @Test func upAndDownGoRowByRowPastAnEmptyOneToTheCardNearestTheOneLeft() {
        let map = Self.home()
        // Play is over the first card.
        #expect(PadFocus.step(from: "play", .down, in: map) == .to("c0"))
        // Past the empty Favourites, to the poster nearest the card left.
        #expect(PadFocus.step(from: "c3", .down, in: map) == .to("l7"))
        #expect(PadFocus.step(from: "l7", .up, in: map) == .to("c3"))
        #expect(PadFocus.step(from: "c0", .up, in: map) == .to("play"))
        // Nothing below the last row.
        #expect(PadFocus.step(from: "l7", .down, in: map) == .leave)
    }

    @Test func upFromTheFirstRowReachesTheTabsAndDownComesBack() {
        let map = Self.home()
        #expect(PadFocus.step(from: "play", .up, in: map) == .to("t-home"))
        #expect(PadFocus.step(from: "details", .up, in: map) == .to("t-library"))
        #expect(PadFocus.step(from: "t-home", .down, in: map) == .to("play"))
        #expect(PadFocus.step(from: "t-home", .right, in: map) == .to("t-library"))
        #expect(PadFocus.step(from: "t-library", .right, in: map) == .stay)
    }

    @Test func aRowScrolledAwayIsEnteredWhereItWasLeft() {
        // A lazy page has not drawn Continue watching.
        let map = Self.home(drawn: false)
        #expect(PadFocus.step(from: "l2", .up, in: map) == .to("c0"))
        var memory = PadMemory()
        memory.focused("c4", page: "home", in: Self.home())
        #expect(memory.member(in: "continue") == "c4")
        #expect(PadFocus.step(from: "l2", .up, in: map, memory: memory) == .to("c4"))
    }

    @Test func withNothingFocusedAPressTakesThePagesFirstItem() {
        let map = Self.home()
        #expect(PadFocus.first(in: map) == "t-home")
        #expect(PadFocus.step(from: nil, .down, in: map) == .to("t-home"))
        #expect(PadFocus.step(from: "gone", .left, in: map) == .to("t-home"))
        // Nothing laid out yet: the first member of the outermost group.
        #expect(PadFocus.first(in: PadMap(groups: Self.home().groups)) == "t-home")
        #expect(PadFocus.step(from: nil, .down, in: PadMap()) == .leave)
    }

    // MARK: A grid (Library)

    /// A Sort button over a grid of seventeen posters seven across.
    private static func library(columns: Int = 7, grouped: Bool = true) -> PadMap {
        var frames: [String: CGRect] = ["sort": CGRect(x: 300, y: 0, width: 100, height: 40)]
        for i in 0..<17 {
            frames["g\(i)"] = CGRect(x: CGFloat(i % 7) * 100, y: 60 + CGFloat(i / 7) * 160, width: 90, height: 150)
        }
        let groups = grouped ? [
            PadGroup("controls", .row, members: ["sort"]),
            PadGroup("grid", .grid(columns: columns), members: (0..<17).map { "g\($0)" }),
            PadGroup("page", .column, members: ["controls", "grid"]),
        ] : []
        return PadMap(frames: frames, groups: groups)
    }

    @Test func aGridWrapsAcrossItsLinesAndStopsAtItsEnds() {
        for map in [Self.library(), Self.library(columns: 0)] {
            #expect(PadFocus.step(from: "g6", .right, in: map) == .to("g7"))
            #expect(PadFocus.step(from: "g7", .left, in: map) == .to("g6"))
            #expect(PadFocus.step(from: "g0", .left, in: map) == .stay)
            #expect(PadFocus.step(from: "g16", .right, in: map) == .stay)
            // A line at a time, and the last poster when the last line is shorter.
            #expect(PadFocus.step(from: "g3", .down, in: map) == .to("g10"))
            #expect(PadFocus.step(from: "g10", .down, in: map) == .to("g16"))
            #expect(PadFocus.step(from: "g13", .down, in: map) == .to("g16"))
            #expect(PadFocus.step(from: "g10", .up, in: map) == .to("g3"))
            // Past the last line: off the page.
            #expect(PadFocus.step(from: "g16", .down, in: map) == .leave)
            // Above the first line, the Sort button; and back to the poster under it.
            #expect(PadFocus.step(from: "g2", .up, in: map) == .to("sort"))
            #expect(PadFocus.step(from: "sort", .down, in: map) == .to("g3"))
        }
    }

    @Test func aPageThatIsAGridWithoutSayingSoWrapsByLooking() {
        var map = Self.library(grouped: false)
        // Confined: right off the end of a line does nothing; never up to Sort.
        #expect(PadFocus.step(from: "g6", .right, in: map) == .leave)
        #expect(PadFocus.step(from: "g1", .right, in: map) == .to("g2"))
        map.horizontal = .grid
        #expect(PadFocus.step(from: "g6", .right, in: map) == .to("g7"))
        #expect(PadFocus.step(from: "g7", .left, in: map) == .to("g6"))
        // Back past the first poster is the line above: the Sort button.
        #expect(PadFocus.step(from: "g0", .left, in: map) == .to("sort"))
        #expect(PadFocus.step(from: "g16", .right, in: map) == .leave)
        // Up and down by looking: the nearest line, the poster nearest across.
        #expect(PadFocus.step(from: "g3", .down, in: map) == .to("g10"))
        #expect(PadFocus.step(from: "g10", .down, in: map) == .to("g16"))
        #expect(PadFocus.step(from: "g3", .up, in: map) == .to("sort"))
    }

    @Test func aListBesideASideBarGoesAcrossInItsBand() {
        // Settings on an iPad: places on the left, a column of rows on the right.
        let map = PadMap(frames: [
            "general": CGRect(x: 0, y: 0, width: 200, height: 44),
            "playback": CGRect(x: 0, y: 50, width: 200, height: 44),
            "row-1": CGRect(x: 240, y: 0, width: 400, height: 44),
            "row-2": CGRect(x: 240, y: 50, width: 400, height: 44),
            "row-3": CGRect(x: 240, y: 100, width: 400, height: 44),
        ], groups: [
            PadGroup("places", .column, members: ["general", "playback"]),
            PadGroup("rows", .column, members: ["row-1", "row-2", "row-3"]),
        ])
        #expect(PadFocus.step(from: "row-2", .left, in: map) == .to("playback"))
        #expect(PadFocus.step(from: "playback", .right, in: map) == .to("row-2"))
        // Nothing in the band to the left of the third row.
        #expect(PadFocus.step(from: "row-3", .left, in: map) == .leave)
        #expect(PadFocus.step(from: "row-1", .down, in: map) == .to("row-2"))
        // Down past the last place looks below, as Android's search does: the
        // guard never second-guesses up and down.
        #expect(PadFocus.step(from: "playback", .down, in: map) == .to("row-3"))
        #expect(PadFocus.step(from: "row-3", .down, in: map) == .leave)
    }

    // MARK: Where focus returns (FocusPlace)

    @Test func backAndATabReturnToThePlaceWhileThePageStillHasIt() {
        let map = Self.home()
        var memory = PadMemory()
        // A page never shown: its first item.
        #expect(memory.returning(to: "home", in: map) == "t-home")
        memory.focused("l7", page: "home", in: map)
        memory.focused("g3", page: "library", in: Self.library())
        #expect(memory.returning(to: "home", in: map) == "l7")
        #expect(memory.returning(to: "library", in: Self.library()) == "g3")
        // A card the lazy row has let go of is still the place: the page scrolls back to it.
        var scrolled = map
        scrolled.frames["l7"] = nil
        #expect(memory.returning(to: "home", in: scrolled) == "l7")
        // Gone from the page (removed, or another profile's rows): its first item.
        let other = PadMap(frames: ["play": CGRect(x: 0, y: 0, width: 10, height: 10)])
        #expect(memory.returning(to: "home", in: other) == "play")
        memory.forget(page: "home")
        #expect(memory.returning(to: "home", in: map) == "t-home")
        // Each group round the place remembers the member it was in.
        #expect(memory.member(in: "grid") == "g3")
        #expect(memory.member(in: "page") == "grid")
    }

    // MARK: The ring (InputModeTracker)

    @Test func theRingFollowsTheLastInputAndStartsHidden() {
        var input = PadInput()
        // A touch is the default here; the ring waits for a controller or a keyboard.
        #expect(input.mode == .pointer && !input.showsRing)
        var changes = [input.directional()]
        #expect(input.showsRing)
        // A stream of presses is one change, so nothing redraws per event.
        changes.append(input.directional())
        changes.append(input.pointer())
        changes.append(input.pointer())
        #expect(changes == [true, false, true, false])
        #expect(!input.showsRing)
        // The Pocket's start, for a device whose pad is the default.
        #expect(PadInput(mode: .directional).showsRing)
    }

    // MARK: Scrolling to it

    @Test func aScrollMovesAsLittleAsItTakes() {
        // In view: nothing.
        #expect(PadReveal.offset(showing: 100...200, at: 0, length: 300) == nil)
        // Below: its end at the viewport's end; above: its start at the start.
        #expect(PadReveal.offset(showing: 250...400, at: 0, length: 300) == 100)
        #expect(PadReveal.offset(showing: 50...100, at: 80, length: 300) == 50)
        // Longer than the viewport: its start.
        #expect(PadReveal.offset(showing: 100...600, at: 0, length: 300) == 100)
        // Never past the content's end, nor before its start.
        #expect(PadReveal.offset(showing: 900...1_000, at: 0, length: 300, content: 950) == 650)
        #expect(PadReveal.offset(showing: -20...40, at: 100, length: 300, content: 950) == 0)
    }

    @Test func aRowsHeadingShowsOverItAndAPinnedRowRestsAtTheTop() {
        let visible = CGRect(x: 0, y: 150, width: 400, height: 300)
        // A card above the viewport, its row's heading 28 points over it.
        #expect(PadReveal.origin(showing: CGRect(x: 0, y: 100, width: 100, height: 100), in: visible, above: 28)
                == CGPoint(x: 0, y: 72))
        // Below: its foot at the viewport's foot, with the margin.
        #expect(PadReveal.origin(showing: CGRect(x: 0, y: 500, width: 100, height: 100), in: visible, margin: 10)
                == CGPoint(x: 0, y: 310))
        // Showing already: no scroll.
        #expect(PadReveal.origin(showing: CGRect(x: 10, y: 200, width: 100, height: 100), in: visible, above: 28) == nil)
        // A strip: across only.
        let strip = CGRect(x: 0, y: 0, width: 400, height: 150)
        #expect(PadReveal.origin(showing: CGRect(x: 380, y: 0, width: 100, height: 150), in: strip, margin: 20)
                == CGPoint(x: 100, y: 0))
        // Pinned (Home's rows): the focused row's heading at the top, even when it shows.
        #expect(PadReveal.origin(showing: CGRect(x: 0, y: 200, width: 100, height: 100), in: visible, above: 28, pin: true)
                == CGPoint(x: 0, y: 172))
        #expect(PadReveal.origin(showing: CGRect(x: 0, y: 178, width: 100, height: 100), in: visible, above: 28, pin: true)
                == nil)
        // The row to pin is the focused card's, round what it has drawn.
        let home = Self.home()
        #expect(home.parent(of: "c3")?.id == "continue")
        #expect(home.frame("continue") == CGRect(x: 20, y: 300, width: 1_116, height: 120))
        #expect(home.frame("favourites") == nil)
        // The last row cannot rest higher than the content allows.
        #expect(PadReveal.origin(showing: CGRect(x: 0, y: 900, width: 100, height: 100), in: visible, above: 28, pin: true,
                                 content: CGSize(width: 400, height: 1_000)) == CGPoint(x: 0, y: 700))
    }
}
