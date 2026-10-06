import HubKit
import SwiftUI

/// The reader's keys (#16, X1): a game controller's and a keyboard's, both
/// through `ReaderPadMap`, so they do what the hint row and the Keys sheet
/// say. Every key stays in the reader.
extension ComicReaderModel {
    /// A pad action: the end card's, the grid's or a sheet's while one is
    /// open, the reading's otherwise.
    func pad(_ action: PadAction) {
        // Letting go of L3 ends the magnifier, whatever opened meanwhile.
        if case .click(.left, false) = action { return magnify(false) }
        if endCard != nil { return endCardPad(action) }
        if gridOpen { return gridPad(action) }
        if sheet != nil {
            if action == .back || action == .refresh { sheet = nil }
            return
        }
        switch ReaderPadMap.command(padState, action) {
        case .forward: forward()
        case .backward: backward()
        case .page(let delta): turnPage(delta)
        case .zoom(let factor): zoom(by: factor)
        case .move(let direction): move(direction)
        case .glide(let dx, let dy): glide(dx: dx, dy: dy)
        case .magnifier(let on): magnify(on)
        case .controls(let visible): setControls(visible)
        case .leave: leaving = true
        case .choose: if let control = focusedControl { choose(control) }
        case .focus(let direction): moveControlFocus(direction)
        case .display: sheet = .display
        case .keys: sheet = .keys
        case .retry: retry()
        default: break
        }
    }

    /// Close leaves through the view, which knows its host; everything else is the model's.
    func choose(_ control: ComicControl) {
        if control == .close { leaving = true } else { perform(control) }
    }

    /// The end card: Ⓐ goes on to the next issue, Ⓑ stays on the last page,
    /// Select leaves.
    private func endCardPad(_ action: PadAction) {
        switch action {
        case .activate: if endCard?.canContinue == true { movePublication(1) }
        case .back: stayOnLastPage()
        case .refresh: leaving = true
        default: break
        }
    }

    /// The Pages grid: the cursor moves by `PageGrid`, Ⓐ opens its page, Ⓑ closes.
    private func gridPad(_ action: PadAction) {
        let count = pageCount
        switch action {
        case .activate: jump(to: gridCursor)
        case .back, .refresh: closeGrid()
        case .step(let direction): gridCursor = PageGrid.move(gridCursor, direction, columns: gridColumns, count: count)
        case .page(let direction):
            gridCursor = PageGrid.page(gridCursor, delta: direction == .down ? 1 : -1, columns: gridColumns,
                                       rows: gridRows, count: count)
        default: break
        }
    }

    func closeGrid() {
        gridOpen = false
        setControls(true)
    }

    /// A hardware key. Escape closes what is open over the page, then
    /// leaves; the rest stand for the controller's keys (`ReaderKeyboard`).
    func key(_ key: ReaderKey) {
        guard key == .escape else {
            if let action = ReaderKeyboard.action(key) { pad(action) }
            return
        }
        if sheet != nil {
            sheet = nil
        } else if gridOpen {
            closeGrid()
        } else if endCard != nil {
            stayOnLastPage()
        } else {
            leaving = true
        }
    }
}
