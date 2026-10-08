@testable import HubKit
import Testing

/// A controller outside the readers: the shell's L1, R1 and Ⓑ, and the player's buttons.
struct AppPadMapTests {
    /// The Pocket's `shoulder buttons cycle five content sections without entering utility pages`.
    @Test func shouldersGoRoundTheFiveSectionsAndNeverIntoTheUtilityPages() {
        #expect(SectionCycle.next(current: 0, delta: 1, count: 5) == 1)
        #expect(SectionCycle.next(current: 4, delta: 1, count: 5) == 0)
        #expect(SectionCycle.next(current: 0, delta: -1, count: 5) == 4)
        // From Notifications, Services or Settings: R1 the first, L1 the last.
        #expect(SectionCycle.next(current: nil, delta: 1, count: 5) == 0)
        #expect(SectionCycle.next(current: nil, delta: -1, count: 5) == 4)
        #expect(SectionCycle.next(current: 6, delta: 1, count: 5) == 0)
        #expect(SectionCycle.next(current: 2, delta: 0, count: 5) == nil)
        #expect(SectionCycle.next(current: 0, delta: 1, count: 1) == nil)
    }

    @Test func theShellTakesBackAndTheShoulders() {
        #expect(ShellPadMap.command(.back) == .back)
        #expect(ShellPadMap.command(.section(-1)) == .section(-1))
        #expect(ShellPadMap.command(.section(1)) == .section(1))
        #expect(ShellPadMap.command(.activate) == nil)
        #expect(ShellPadMap.command(.step(.down)) == nil)
    }

    @Test func thePlayerMirrorsItsKeys() {
        func command(_ action: PadAction) -> PlayerPadCommand? {
            PlayerPadMap.command(action, panelOpen: false)
        }
        #expect(command(.activate) == .key(.playPause))
        #expect(command(.step(.left)) == .key(.back))
        #expect(command(.step(.right)) == .key(.forward))
        #expect(command(.step(.up)) == .key(.volumeUp))
        #expect(command(.step(.down)) == .key(.volumeDown))
        #expect(command(.primary) == .key(.subtitles))
        #expect(command(.secondary) == .openTracks)
        #expect(command(.section(-1)) == .previous)
        #expect(command(.section(1)) == .key(.next))
        #expect(command(.page(.up)) == .key(.slower))
        #expect(command(.page(.down)) == .key(.faster))
        #expect(command(.menu) == .toggleControls)
        #expect(command(.refresh) == .key(.skip))
        #expect(command(.back) == .leave)
        #expect(command(.pan(dx: 1, dy: 0)) == nil)
    }

    /// A panel open: Ⓑ closes it and nothing else reaches the video.
    @Test func aPanelTakesOnlyBack() {
        #expect(PlayerPadMap.command(.back, panelOpen: true) == .closePanel)
        #expect(PlayerPadMap.command(.activate, panelOpen: true) == nil)
        #expect(PlayerPadMap.command(.step(.left), panelOpen: true) == nil)
        #expect(PlayerPadMap.command(.secondary, panelOpen: true) == nil)
    }

    @Test func aScriptNamesTheButtons() {
        #expect(PadScript.actions("R1, r1,L1,B,a,Y,menu,up,nope") == [.section(1), .section(1), .section(-1), .back, .activate,
                                                                    .secondary, .menu, .step(.up)])
        #expect(PadScript.actions("").isEmpty)
    }
}
