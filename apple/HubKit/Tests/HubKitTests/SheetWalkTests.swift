import Foundation
import Testing
@testable import HubKit

/// A controller walking the readers' settings sheets (#25).
struct SheetWalkTests {
    @Test func upAndDownGoFromLineToLineKeepingAcrossWhereThereIsRoom() {
        let lines: [SheetLine] = [.choices(4), .choices(3), .row, .value]
        var walk = SheetWalk(line: 0, column: 3)
        // Down onto three choices: as far across as that line goes.
        guard case .moved(let second) = SheetWalk.step(walk, .down, lines: lines) else { Issue.record("no move"); return }
        #expect(second == SheetWalk(line: 1, column: 2))
        walk = second
        guard case .moved(let row) = SheetWalk.step(walk, .down, lines: lines) else { Issue.record("no move"); return }
        #expect(row == SheetWalk(line: 2, column: 0))
        // Nothing above the first line or below the last.
        #expect(SheetWalk.step(SheetWalk(), .up, lines: lines) == .stay)
        #expect(SheetWalk.step(SheetWalk(line: 3), .down, lines: lines) == .stay)
        #expect(SheetWalk.step(SheetWalk(), .down, lines: []) == .stay)
    }

    @Test func leftAndRightGoAcrossChoicesAndChangeAValue() {
        let lines: [SheetLine] = [.choices(3), .row, .value]
        #expect(SheetWalk.step(SheetWalk(line: 0, column: 1), .right, lines: lines) == .moved(SheetWalk(line: 0, column: 2)))
        #expect(SheetWalk.step(SheetWalk(line: 0, column: 2), .right, lines: lines) == .stay)
        #expect(SheetWalk.step(SheetWalk(line: 0, column: 0), .left, lines: lines) == .stay)
        #expect(SheetWalk.step(SheetWalk(line: 1), .right, lines: lines) == .stay)
        #expect(SheetWalk.step(SheetWalk(line: 2), .left, lines: lines) == .adjust(-1))
        #expect(SheetWalk.step(SheetWalk(line: 2), .right, lines: lines) == .adjust(1))
        // Lines that changed under the ring (another tab) bring it back inside.
        #expect(SheetWalk(line: 9, column: 5).clamped(to: lines) == SheetWalk(line: 2, column: 0))
        #expect(SheetWalk.step(SheetWalk(line: 9, column: 5), .up, lines: lines) == .moved(SheetWalk(line: 1, column: 0)))
    }

    @Test func aValueMovesOnTheSlidersGridAndStaysInItsRange() {
        #expect(SheetWalk.nudge(0.5, steps: 1, in: 0...1) == 0.55)
        #expect(abs(SheetWalk.nudge(0.52, steps: -1, in: 0...1) - 0.45) < 1e-9)
        #expect(SheetWalk.nudge(0.98, steps: 1, in: 0...1) == 1)
        #expect(SheetWalk.nudge(0.12, steps: -1, in: 0.1...1) == 0.1)
    }

    @Test func comfortsLinesPressAndChange() {
        let comfort = ScreenComfort(brightness: 1, warmth: 0)
        #expect(ComfortLine.lines(book: false) == [.brightness, .warmth])
        #expect(ComfortLine.lines(book: true) == [.brightness, .warmth, .awake])
        #expect(ComfortLine.brightness.adjust(comfort, by: -1).brightness == 0.95)
        #expect(ComfortLine.warmth.adjust(comfort, by: 1).warmth == 0.05)
        #expect(ComfortLine.warmth.adjust(comfort, by: -1).warmth == 0)
        #expect(!ComfortLine.awake.press(comfort).awakeWhileNarrating)
        #expect(ComfortLine.brightness.press(comfort) == comfort)
    }

    @Test func theComicReadersOptionsTopToBottom() {
        let wide = ComicDisplayLine.lines(narrow: false)
        #expect(wide == [.fit(.whole), .fit(.width), .fit(.thirds), .trim, .direction(nil), .direction("ltr"), .direction("rtl"),
                         .everySeries, .comfort(.brightness), .comfort(.warmth)])
        // A narrow window adds the issue's own rows before Comfort.
        let narrow = ComicDisplayLine.lines(narrow: true)
        #expect(narrow.count == wide.count + 3)
        #expect(narrow[8...10] == [.previousIssue, .nextIssue, .keys])
        #expect(narrow.map(\.shape).filter { $0 == .value }.count == 2)
    }

    @Test func appearancesTabsAndWhatEachLineDoes() {
        let brightness = BookAppearanceLine.comfort(.brightness)
        // Brightness is fixed at the bottom of every page (#47); Spacing is a page of Font's, with its own way back.
        #expect(BookAppearanceLine.lines(.font) == [.tabs, .typeface, .size, .spacingPage, .onePage, brightness])
        #expect(BookAppearanceLine.lines(.comfort) == [.tabs, .comfort(.warmth), .comfort(.awake), brightness])
        // Themes ends on its page of the read-along highlight (#66), which has its own way back.
        #expect(Array(BookAppearanceLine.lines(.themes).suffix(3)) == [.systemColours, .highlightPage, brightness])
        #expect(BookAppearanceLine.lines(.spacing) == [.back, .spacing, .margins, brightness])
        #expect(BookAppearanceLine.lines(.highlight)
            == [.back, .highlightThemes, .highlightColours, .highlightTrail, .highlightDefault, brightness])
        #expect(BookAppearanceLine.highlightThemes.shape == .choices(5) && BookAppearanceLine.highlightColours.shape == .choices(8))
        #expect(BookAppearanceLine.highlightTrail.shape == .value && BookAppearanceLine.highlightDefault.shape == .row)
        #expect(BookAppearanceLine.highlightPage.press(EpubReaderPreferences()) == nil, "the model opens the page")
        for page in BookAppearancePage.allCases { #expect(BookAppearanceLine.lines(page).last == brightness, "\(page)") }
        #expect(BookAppearancePage.tabs == [.font, .layout, .themes, .comfort])
        #expect(BookAppearanceLine.tabs.shape == .choices(4))
        #expect(BookAppearanceLine.size.shape == .value && brightness.shape == .value)
        #expect(BookAppearanceLine.spacingPage.shape == .row && BookAppearanceLine.back.shape == .row)
        #expect(BookAppearanceLine.spacingPage.press(EpubReaderPreferences()) == nil, "the model opens the page")
        #expect(BookAppearanceLine.typeface.shape == .choices(6))
        #expect(BookAppearanceLine.typeface.press(EpubReaderPreferences(), column: 2)?.fontFamily == "charter")
        #expect(BookAppearanceLine.typeface.press(EpubReaderPreferences(), column: 0)?.publisherStyles == true)
        let value = EpubReaderPreferences()
        // Two pages from the columns' second choice, and back to one from the first.
        #expect(BookAppearanceLine.columns.press(value, column: 1)?.columns == .two)
        #expect(BookAppearanceLine.columns.press(value, column: 0)?.columns == .one)
        #expect(BookAppearanceLine.automaticColumns.press(value)?.columns == .auto)
        #expect(BookAppearanceLine.scroll.press(value)?.scroll == !value.scroll)
        #expect(BookAppearanceLine.margins.press(value, column: 0)?.pageMargins == EpubAppearance.margins[0].amount)
        #expect(BookAppearanceLine.margins.press(value, column: 9) == nil)
        let rows = BookAppearanceLine.themeRows
        #expect(rows.flatMap { $0 }.count == EpubAppearance.themes.count)
        #expect(BookAppearanceLine.themes(1).press(value, column: 0)?.theme == rows[1][0].theme)
        #expect(BookAppearanceLine.systemColours.press(value)?.theme == .system)
        // Hyphenation, and Page info's rows, are lines a controller reaches on Layout (#42).
        let layout = BookAppearanceLine.lines(.layout)
        #expect(!layout.contains(.margins) && !layout.contains(.spacing), "they are Spacing's now")
        #expect(Array(layout.dropLast().suffix(9)) == [.hyphenation, .resetTextStyle, .clock, .percentage, .place(.pageInBook),
                                            .place(.pageInChapter), .place(.chapterTimeLeft), .place(.bookTimeLeft),
                                            .place(.none)])
        #expect(BookAppearanceLine.resetTextStyle.shape == .row)
        #expect(BookAppearanceLine.resetTextStyle.press(value) == EpubAppearance.resetTextStyle(value))
        #expect(BookAppearanceLine.resetTextStyle.press(PageInfoPreferences()) == nil)
        #expect(BookAppearanceLine.hyphenation.press(value)?.hyphens == !value.hyphens)
        #expect(BookAppearanceLine.clock.press(value) == nil && BookAppearanceLine.clock.shape == .row)
        let info = PageInfoPreferences()
        #expect(BookAppearanceLine.clock.press(info)?.clock == false)
        #expect(BookAppearanceLine.percentage.press(info)?.percentage == false)
        #expect(BookAppearanceLine.place(.none).press(info)?.place == PageInfoPlace.none)
        #expect(BookAppearanceLine.justified.press(info) == nil)
        #expect(BookAppearanceLine.tabs.press(value) == nil)
        // Left and right on the size: a size smaller or larger.
        #expect(BookAppearanceLine.size.adjust(value, by: 1) == EpubAppearance.fontSize(value, steps: 1))
        #expect(BookAppearanceLine.onePage.adjust(value, by: 1) == nil)
    }
}
