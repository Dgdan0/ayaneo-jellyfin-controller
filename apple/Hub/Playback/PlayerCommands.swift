import HubKit
import SwiftUI

/// What the open player tells the Playback menu (#33): what its commands say
/// now, and the way to carry one out, the player's own keys' way.
struct PlayerKeys {
    /// No panel is open over the video and its controls are not locked.
    let enabled: Bool
    let playing: Bool
    let muted: Bool
    /// "Skip intro", "Skip credits"; nil when there is nothing to skip.
    let skipTitle: String?
    let hasNext: Bool
    let subtitlesOn: Bool
    let seekSeconds: Int
    let press: (PlayerKey) -> Void
}

extension FocusedValues {
    /// Set by the player while a video is open in the window.
    @Entry var playerKeys: PlayerKeys?
}

/// The Playback menu (#33): the Mac's menu bar, and the iPad's, name the
/// player's keys while a video is open, each greyed out when it can do
/// nothing. Space, S, N, M, C, [, ] and F are the player's own keys; the
/// jumps and the volume take ⌘ here, so the arrows stay free for anything else.
struct PlayerCommands: Commands {
    @FocusedValue(\.playerKeys) private var keys

    var body: some Commands {
        CommandMenu("Playback") {
            let on = keys?.enabled == true
            let seconds = keys?.seekSeconds ?? ListeningSettings.seekSeconds
            Button(keys?.playing == true ? "Pause" : "Play") { keys?.press(.playPause) }
                .keyboardShortcut(.space, modifiers: [])
                .disabled(!on)
            Button("Back \(seconds) seconds") { keys?.press(.back) }
                .keyboardShortcut(.leftArrow, modifiers: .command)
                .disabled(!on)
            Button("Forward \(seconds) seconds") { keys?.press(.forward) }
                .keyboardShortcut(.rightArrow, modifiers: .command)
                .disabled(!on)
            Divider()
            Button(keys?.skipTitle ?? "Skip intro") { keys?.press(.skip) }
                .keyboardShortcut("s", modifiers: [])
                .disabled(!on || keys?.skipTitle == nil)
            Button("Next episode") { keys?.press(.next) }
                .keyboardShortcut("n", modifiers: [])
                .disabled(!on || keys?.hasNext != true)
            Divider()
            Button(keys?.subtitlesOn == true ? "Turn subtitles off" : "Turn subtitles on") { keys?.press(.subtitles) }
                .keyboardShortcut("c", modifiers: [])
                .disabled(!on)
            Button("Slower") { keys?.press(.slower) }
                .keyboardShortcut("[", modifiers: [])
                .disabled(!on)
            Button("Faster") { keys?.press(.faster) }
                .keyboardShortcut("]", modifiers: [])
                .disabled(!on)
            Divider()
            Button("Volume up") { keys?.press(.volumeUp) }
                .keyboardShortcut(.upArrow, modifiers: .command)
                .disabled(!on)
            Button("Volume down") { keys?.press(.volumeDown) }
                .keyboardShortcut(.downArrow, modifiers: .command)
                .disabled(!on)
            Button(keys?.muted == true ? "Sound on" : "Mute") { keys?.press(.mute) }
                .keyboardShortcut("m", modifiers: [])
                .disabled(!on)
            #if os(macOS)
            Divider()
            Button("Full screen") { keys?.press(.fullScreen) }
                .keyboardShortcut("f", modifiers: [])
                .disabled(!on)
            #endif
        }
    }
}
