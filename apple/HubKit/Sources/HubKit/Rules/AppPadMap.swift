import Foundation

// A game controller outside the readers: the shell's and the player's
// buttons (the Pocket's `GamepadMap` and `SectionStacks.switchWithin`). The
// readers keep `ReaderPadMap`; whichever screen is on top takes the pad.

/// What the shell does with a press when no player or reader is open.
public enum ShellPadCommand: Equatable, Sendable {
    /// Ⓑ: the profile picker closed, else the page under this one.
    case back
    /// L1 and R1: the section before or after this one.
    case section(Int)
}

public enum ShellPadMap {
    public static func command(_ action: PadAction) -> ShellPadCommand? {
        switch action {
        case .back: .back
        case .section(let delta) where delta != 0: .section(delta)
        default: nil
        }
    }
}

/// L1 and R1 go round the content sections (Home, Discover, Library,
/// Downloads, Activity) and never into Notifications, Services or Settings:
/// from one of those, R1 is the first section and L1 the last. The Pocket's
/// `SectionStacks.switchWithin`, held to its test case.
public enum SectionCycle {
    /// The index to show, or nil to stay. `current` is nil on a page that is
    /// not one of the `count` content sections.
    public static func next(current: Int?, delta: Int, count: Int) -> Int? {
        guard delta != 0, count > 1 else { return nil }
        guard let current, (0..<count).contains(current) else { return delta > 0 ? 0 : count - 1 }
        let next = ((current + delta) % count + count) % count
        return next == current ? nil : next
    }
}

/// What the player does with a press: its keys' commands, as the keyboard
/// has them, and what a controller adds.
public enum PlayerPadCommand: Equatable, Sendable {
    case key(PlayerKey)
    /// Ⓨ: Audio & subtitles.
    case openTracks
    /// Ⓑ with a panel open: that panel closed.
    case closePanel
    /// Ⓑ with none: playing stops and the player goes, as its Back does.
    case leave
    case previous
    /// Menu: the controls shown, or hidden again.
    case toggleControls
}

public enum PlayerPadMap {
    /// With a panel open only Ⓑ is the player's (the panel's rows are touched
    /// or clicked for now). The lock is against stray touches: a controller's
    /// presses go on through it, as the keyboard's do.
    public static func command(_ action: PadAction, panelOpen: Bool) -> PlayerPadCommand? {
        if panelOpen { return action == .back ? .closePanel : nil }
        switch action {
        case .activate: return .key(.playPause)
        case .back: return .leave
        case .step(.left): return .key(.back)
        case .step(.right): return .key(.forward)
        case .step(.up): return .key(.volumeUp)
        case .step(.down): return .key(.volumeDown)
        case .primary: return .key(.subtitles)
        case .secondary: return .openTracks
        case .section(let delta) where delta < 0: return .previous
        case .section(let delta) where delta > 0: return .key(.next)
        case .page(.up): return .key(.slower)
        case .page(.down): return .key(.faster)
        case .menu: return .toggleControls
        case .refresh: return .key(.skip)
        default: return nil
        }
    }
}

/// A script of presses for the UI tests (`HUB_PAD`, Debug builds): names
/// separated by commas, as a controller's buttons are printed on it.
public enum PadScript {
    public static func actions(_ script: String) -> [PadAction] {
        script.split(separator: ",").compactMap { action(String($0).trimmingCharacters(in: .whitespaces)) }
    }

    public static func action(_ name: String) -> PadAction? {
        switch name.uppercased() {
        case "A": .activate
        case "B": .back
        case "X": .primary
        case "Y": .secondary
        case "L1": .section(-1)
        case "R1": .section(1)
        case "L2": .page(.up)
        case "R2": .page(.down)
        case "UP": .step(.up)
        case "DOWN": .step(.down)
        case "LEFT": .step(.left)
        case "RIGHT": .step(.right)
        case "MENU", "START": .menu
        case "OPTIONS", "SELECT": .refresh
        default: nil
        }
    }
}
