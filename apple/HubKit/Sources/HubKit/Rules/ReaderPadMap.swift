import Foundation

// One controller language for every reader (#16, X1; #25): Android's
// `reader/ReaderPadMap.kt`, key for key, with its test cases in
// `ReaderPadMapTests`. A game controller on an iPad or a Mac (GameController)
// and a keyboard (`ReaderKeyboard`) both turn into these actions, so the keys,
// the hint row and the Controls sheet come from one table. The comic reader
// (phase 3) uses it first; the book and audiobook readers take the same.

/// A direction on the D-pad or a stick.
public enum PadDirection: String, CaseIterable, Sendable {
    case up, down, left, right
}

public enum PadStick: String, Sendable {
    case left, right
}

/// What a controller press means, before a screen decides what it does:
/// Android's `input/PadAction`.
public enum PadAction: Equatable, Sendable {
    /// Ⓐ.
    case activate
    /// Ⓑ.
    case back
    /// Ⓧ.
    case primary
    /// Ⓨ.
    case secondary
    /// L1 (-1) and R1 (1).
    case section(Int)
    /// L2 (up) and R2 (down).
    case page(PadDirection)
    /// The D-pad, or the left stick pushed.
    case step(PadDirection)
    /// Start.
    case menu
    /// Select.
    case refresh
    /// The right stick: how far it moved, in stick-seconds.
    case pan(dx: Double, dy: Double)
    /// A stick pressed in (`down`) or let go.
    case click(PadStick, down: Bool = true)
}

/// Which reader the keys are for (#16). Read-along is a book with narration.
public enum ReaderKind: Sendable {
    case comic, book, audiobook
}

/// What a reader is showing, as far as its keys are concerned.
public struct ReaderPadState: Equatable, Sendable {
    public var kind: ReaderKind
    /// Comics: the controls over the page. Books: the menu, the page shrunk inside it.
    public var controlsVisible: Bool
    /// Books: continuous scrolling rather than pages.
    public var scrolling: Bool
    /// Books: narration plays alongside the text (read along).
    public var narration: Bool
    /// Still opening, or it failed: Select tries again.
    public var loading: Bool
    /// Audiobooks: how far L2 and R2 jump.
    public var seekSeconds: Int

    public init(_ kind: ReaderKind, controlsVisible: Bool = false, scrolling: Bool = false, narration: Bool = false,
                loading: Bool = false, seekSeconds: Int = 10) {
        self.kind = kind
        self.controlsVisible = controlsVisible
        self.scrolling = scrolling
        self.narration = narration
        self.loading = loading
        self.seekSeconds = seekSeconds
    }
}

/// What a key does in a reader, whichever reader it is. Each reader carries these out its own way.
public enum ReaderCommand: Equatable, Sendable {
    /// On through the reading: a comic's next third or page, a book's next page.
    case forward
    /// Back through the reading.
    case backward
    /// A whole page on or back, past any thirds.
    case page(Int)
    /// A book's chapter, an audiobook's part.
    case chapter(Int)
    case zoom(Double)
    /// Comics, the D-pad: across the page, turning at its edge going sideways.
    case move(PadDirection)
    /// The right stick: a comic pans, a scrolling book scrolls; amounts in stick-seconds.
    case glide(dx: Double, dy: Double)
    /// A scrolling book, the D-pad: a part of a screen up or down.
    case scroll(PadDirection)
    /// Comics, L3 held: a closer look until it is let go.
    case magnifier(Bool)
    /// Read along: back to the sentence being read aloud.
    case followNarration
    /// Books, L3: the bottom corner says where you are the next way (#42, Kindle's reading progress).
    case nextPlace
    /// Read along, L1 and R1: the sentence before or after (#16, A5).
    case sentence(Int)
    /// Comics: show or hide the controls. Books: open the menu, or go back to the page.
    case controls(Bool)
    case leave
    /// Press the control in focus.
    case choose
    /// Move between the controls.
    case focus(PadDirection)
    case bookmark
    case contents
    /// A comic's Display sheet, a book's Appearance.
    case display
    /// The Controls sheet: every key and what it does here.
    case keys
    case playPause
    case seek(Int)
    /// Audiobooks: the other ways to read this book.
    case formats
    case retry
    /// Taken by the reader and left alone: never handed on to the app (a tab switch would close it).
    case ignore
}

/// One line of the Controls sheet: the keys, drawn as caps, and what they do.
public struct ReaderKeyLine: Equatable, Sendable {
    public var keys: [String]
    public var does: String

    public init(_ keys: [String], _ does: String) {
        self.keys = keys
        self.does = does
    }
}

/// A key in the hint row inside a reader's controls: its cap, what it does,
/// and the action a tap on the chip sends, as the key would.
public struct ReaderHint: Equatable, Sendable {
    public var glyph: String
    public var label: String
    public var action: PadAction

    public init(_ glyph: String, _ label: String, _ action: PadAction) {
        self.glyph = glyph
        self.label = label
        self.action = action
    }
}

/// One controller language for every reader (#16, X1), as the owner decided
/// on 2026-10-04: comics read on with Ⓐ and back with Ⓑ and leave with Select;
/// a book's Ⓑ opens the menu (the page shrinks inside it) and Ⓑ again leaves;
/// an audiobook's Ⓑ leaves. Every key is the reader's: none is handed to the
/// app, whose shoulder buttons would switch tabs and close the reader.
///
/// The same table names the keys, so the hint row inside the controls and the
/// Controls sheet can never say something the keys do not do. Pure, so tested.
public enum ReaderPadMap {
    /// L1 and R1 zoom a comic by this much, L2 and R2 by `bigZoom`.
    public static let smallZoom = 1.2
    public static let bigZoom = 1.35

    public static func command(_ state: ReaderPadState, _ action: PadAction) -> ReaderCommand {
        switch state.kind {
        case .comic: comic(state, action)
        case .book: book(state, action)
        case .audiobook: audiobook(state, action)
        }
    }

    private static func comic(_ state: ReaderPadState, _ action: PadAction) -> ReaderCommand {
        switch action {
        case .activate: state.controlsVisible ? .choose : .forward
        case .back: state.controlsVisible ? .controls(false) : .backward
        case .primary: .page(1)
        case .secondary: .page(-1)
        case .section(let delta): .zoom(delta > 0 ? smallZoom : 1 / smallZoom)
        case .page(let direction): .zoom(direction == .down ? bigZoom : 1 / bigZoom)
        case .step(let direction): state.controlsVisible ? .focus(direction) : .move(direction)
        case .menu: .controls(!state.controlsVisible)
        case .refresh: state.loading ? .retry : .leave
        case .pan(let dx, let dy): .glide(dx: dx, dy: dy)
        case .click(let stick, let down):
            if stick == .left { .magnifier(down) } else if down { .keys } else { .ignore }
        }
    }

    private static func book(_ state: ReaderPadState, _ action: PadAction) -> ReaderCommand {
        switch action {
        case .activate: return state.controlsVisible ? .choose : .forward
        case .back: return state.controlsVisible ? .leave : .controls(true)
        case .primary: return .bookmark
        case .secondary: return .contents
        // Read along, the shoulders step through the narration a sentence at a time (A5).
        case .section(let delta): return state.narration && !state.controlsVisible ? .sentence(delta) : .page(delta)
        // Held: the reader asks for a deliberate hold before a chapter jumps.
        case .page(let direction): return .chapter(direction == .down ? 1 : -1)
        case .step(let direction):
            if state.controlsVisible { return .focus(direction) }
            if direction == .left { return .page(-1) }
            if direction == .right { return .page(1) }
            return state.scrolling ? .scroll(direction) : .controls(true)
        case .menu: return .controls(!state.controlsVisible)
        case .refresh: return state.loading ? .retry : .display
        case .pan(let dx, let dy): return state.scrolling && !state.controlsVisible ? .glide(dx: dx, dy: dy) : .ignore
        case .click(let stick, let down):
            if !down { return .ignore }
            if stick == .right { return .keys }
            // L3 is the voice's in read along; else the corner's, on the page.
            if state.narration { return .followNarration }
            return state.controlsVisible ? .ignore : .nextPlace
        }
    }

    private static func audiobook(_ state: ReaderPadState, _ action: PadAction) -> ReaderCommand {
        switch action {
        case .activate: .choose
        case .back: .leave
        case .primary: .playPause
        case .secondary: .formats
        case .section(let delta): .chapter(delta)
        case .page(let direction): .seek(direction == .down ? state.seekSeconds : -state.seekSeconds)
        case .step(let direction): .focus(direction)
        case .menu: .keys
        case .refresh: state.loading ? .retry : .ignore
        case .pan: .ignore
        case .click(let stick, let down): stick == .right && down ? .keys : .ignore
        }
    }

    /// What `command` is called on a key cap's line, in this reader; empty for `.ignore`.
    public static func describe(_ kind: ReaderKind, _ command: ReaderCommand) -> String {
        switch command {
        case .forward: kind == .book ? "Next page" : "Forward"
        case .backward: "Back"
        case .page(let delta): delta > 0 ? "Next page" : "Previous page"
        case .chapter(let delta):
            if kind == .audiobook { delta > 0 ? "Next part" : "Previous part" } else { delta > 0 ? "Next chapter" : "Previous chapter" }
        case .zoom(let factor): factor > 1 ? "Zoom in" : "Zoom out"
        case .move: "Move around the page"
        case .glide: kind == .comic ? "Pan" : "Scroll"
        case .scroll: "Scroll"
        case .magnifier: "Magnifier, while held"
        case .followNarration: "Back to the narration"
        case .nextPlace: "Next reading progress"
        case .sentence(let delta): delta > 0 ? "Next sentence" : "Previous sentence"
        case .controls(let visible):
            if kind == .book { visible ? "Menu" : "Back to the page" } else { visible ? "Controls" : "Hide controls" }
        case .leave: kind == .book ? "Leave the book" : "Leave"
        case .choose: "Choose"
        case .focus: "Move between controls"
        case .bookmark: "Bookmark"
        case .contents: "Contents"
        case .display: kind == .book ? "Appearance" : "Display"
        case .keys: "Keys"
        case .playPause: "Play or pause"
        case .seek(let seconds): seconds < 0 ? "Back \(-seconds) s" : "Forward \(seconds) s"
        case .formats: "Reading and listening"
        case .retry: "Retry"
        case .ignore: ""
        }
    }

    /// The hint row inside the controls: what the main keys do now. Each chip is also a button.
    public static func hints(_ state: ReaderPadState) -> [ReaderHint] {
        hintKeys(state.kind).compactMap { key in
            let label = describe(state.kind, command(state, key.action))
            return label.isEmpty ? nil : ReaderHint(key.glyph, label, key.action)
        }
    }

    /// The Controls sheet: every key and what it does while reading, the
    /// controls closed. A key that does nothing here is left out; the D-pad
    /// takes two lines when its sides and its ends differ (a book).
    public static func sheet(_ kind: ReaderKind, state: ReaderPadState? = nil) -> [ReaderKeyLine] {
        var reading = state ?? ReaderPadState(kind)
        reading.controlsVisible = kind == .audiobook
        reading.loading = false
        var lines: [ReaderKeyLine] = []
        func said(_ action: PadAction) -> String { describe(kind, command(reading, action)) }
        func line(_ keys: [String], _ action: PadAction) {
            let does = said(action)
            if !does.isEmpty { lines.append(ReaderKeyLine(keys, does)) }
        }
        func pair(_ first: (String, PadAction), _ second: (String, PadAction)) {
            let a = said(first.1)
            let b = said(second.1)
            if a.isEmpty && b.isEmpty { return }
            if a == b {
                lines.append(ReaderKeyLine([first.0, second.0], a))
            } else {
                line([first.0], first.1)
                line([second.0], second.1)
            }
        }
        line([a], .activate)
        line([b], .back)
        line([x], .primary)
        line([y], .secondary)
        let sides = said(.step(.right))
        let ends = said(.step(.down))
        if sides == ends {
            lines.append(ReaderKeyLine([dpad], sides))
        } else {
            let left = said(.step(.left))
            lines.append(ReaderKeyLine([dpadSides], left == sides ? sides : lowercaseAfterFirst(left + ", " + sides)))
            lines.append(ReaderKeyLine([dpadEnds], ends))
        }
        pair((l1, .section(-1)), (r1, .section(1)))
        pair((l2, .page(.up)), (r2, .page(.down)))
        line([rightStick], .pan(dx: 0, dy: 1))
        line([l3], .click(.left))
        line([r3], .click(.right))
        line([start], .menu)
        line([select], .refresh)
        return lines
    }

    /// "Previous page, Next page" reads "Previous page, next page".
    static func lowercaseAfterFirst(_ text: String) -> String {
        guard let comma = text.range(of: ", ") else { return text }
        let rest = text[comma.upperBound...]
        return String(text[..<comma.upperBound]) + rest.prefix(1).lowercased() + rest.dropFirst()
    }

    // The caps as the hint bar draws them: letters in circles, names in pills.
    public static let a = "Ⓐ"
    public static let b = "Ⓑ"
    public static let x = "Ⓧ"
    public static let y = "Ⓨ"
    public static let start = "⏵"
    public static let select = "⟳"
    public static let l1 = "L1"
    public static let r1 = "R1"
    public static let l2 = "L2"
    public static let r2 = "R2"
    public static let l3 = "L3"
    public static let r3 = "R3"
    public static let dpad = "D-pad"
    public static let dpadSides = "D-pad ← →"
    public static let dpadEnds = "D-pad ↑ ↓"
    public static let rightStick = "Right stick"

    /// The keys the hint row names, per reader, in the order the row shows them.
    private static func hintKeys(_ kind: ReaderKind) -> [(glyph: String, action: PadAction)] {
        switch kind {
        case .comic:
            [(a, .activate), (b, .back), (x, .primary), (y, .secondary), (select, .refresh), (r3, .click(.right))]
        case .book:
            [(a, .activate), (b, .back), (x, .primary), (y, .secondary), (start, .menu), (select, .refresh),
             (r3, .click(.right))]
        case .audiobook:
            [(a, .activate), (b, .back), (x, .primary), (l1, .section(-1)), (r1, .section(1)), (l2, .page(.up)),
             (r2, .page(.down)), (r3, .click(.right))]
        }
    }
}

/// A key on a hardware keyboard, as a reader reads it: an iPad's or a Mac's.
public enum ReaderKey: String, CaseIterable, Sendable {
    case space, returnKey, delete, escape, left, right, up, down, pageUp, pageDown, minus, plus
}

/// A keyboard speaks the controller's language (APPLE_PLAN.md, "Input"):
/// Space and Return are Ⓐ, Delete is Ⓑ, the arrows the D-pad, Page Down and
/// Page Up Ⓧ and Ⓨ, and - and = the zoom shoulders. Escape always leaves,
/// closing what is open over the page first: a keyboard has no Select.
public enum ReaderKeyboard {
    /// The controller action a key stands for; nil for Escape, which leaves.
    public static func action(_ key: ReaderKey) -> PadAction? {
        switch key {
        case .space, .returnKey: .activate
        case .delete: .back
        case .left: .step(.left)
        case .right: .step(.right)
        case .up: .step(.up)
        case .down: .step(.down)
        case .pageDown: .primary
        case .pageUp: .secondary
        case .minus: .section(-1)
        case .plus: .section(1)
        case .escape: nil
        }
    }

    /// The keyboard's lines of the Controls sheet, as `ReaderPadMap.sheet`
    /// words them: what each key does while reading, the controls closed.
    public static func sheet(_ kind: ReaderKind, state: ReaderPadState? = nil) -> [ReaderKeyLine] {
        var reading = state ?? ReaderPadState(kind)
        reading.controlsVisible = kind == .audiobook
        reading.loading = false
        let groups: [([String], [ReaderKey])] = [
            (["Space", "Return"], [.space, .returnKey]), (["Delete"], [.delete]), (["Arrows"], [.left, .right, .up, .down]),
            (["Page Down"], [.pageDown]), (["Page Up"], [.pageUp]), (["−"], [.minus]), (["="], [.plus]),
        ]
        func said(_ key: ReaderKey) -> String {
            Self.action(key).map { ReaderPadMap.describe(kind, ReaderPadMap.command(reading, $0)) } ?? ""
        }
        var lines: [ReaderKeyLine] = []
        for (caps, keys) in groups {
            if keys.count == 4 {
                // The arrows, as the D-pad's lines: one when every way does the
                // same, else the sides ("Previous page, next page") and the ends.
                let sides = said(.right)
                let ends = said(.down)
                if sides == ends {
                    if !sides.isEmpty { lines.append(ReaderKeyLine(caps, sides)) }
                } else {
                    let left = said(.left)
                    let across = left == sides ? sides : ReaderPadMap.lowercaseAfterFirst(left + ", " + sides)
                    if !across.isEmpty { lines.append(ReaderKeyLine(["←", "→"], across)) }
                    if !ends.isEmpty { lines.append(ReaderKeyLine(["↑", "↓"], ends)) }
                }
                continue
            }
            let does = keys.first.map(said) ?? ""
            if !does.isEmpty { lines.append(ReaderKeyLine(caps, does)) }
        }
        lines.append(ReaderKeyLine(["Escape"], ReaderPadMap.describe(kind, .leave)))
        return lines
    }
}
