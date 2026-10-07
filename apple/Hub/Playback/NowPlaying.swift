import HubKit
import MediaPlayer
import Observation
#if os(iOS)
import UIKit
#elseif os(macOS)
import AppKit
#endif

/// A command from the lock screen, Control Center, AirPods, a car or the
/// Mac's media keys, for the player in front.
enum RemoteCommand: Equatable, Sendable {
    case play, pause, toggle
    /// Back or on by the jump Settings › Playback sets.
    case skip(forward: Bool)
    /// The next or previous episode, chapter or part.
    case step(Int)
    /// To this moment of what is playing.
    case seek(Int64)
}

/// What a player gives the lock screen and does with its commands.
@MainActor
protocol NowPlayingClient: AnyObject {
    func remote(_ command: RemoteCommand)
    /// Its title, picture and moment, given again as it comes to the front.
    func publishNowPlaying()
}

/// The lock screen, Control Center, AirPods and the Mac's media keys for the
/// whole app (#33): the system has one set of commands and one Now Playing,
/// which the video player and the audiobook share. Whichever played last of
/// those with something on them is in front (`NowPlayingOwners`): its title
/// is shown and the commands go to it. When it lets go (the video closes,
/// the book leaves the player) the one behind it comes back.
@MainActor
@Observable
final class NowPlaying {
    static let shared = NowPlaying()

    /// The commands a player answers: every one but the steps, which only
    /// where there is a next or previous.
    struct Commands: Equatable {
        var next = false
        var previous = false
    }

    private(set) var owners = NowPlayingOwners()
    /// Debug builds' UI tests read this (`HUB_DEBUG_NOW_PLAYING`): who is in front and its title.
    private(set) var summary = "none"
    @ObservationIgnored private var clients: [AudioSource: WeakClient] = [:]
    @ObservationIgnored private var commands: [AudioSource: Commands] = [:]

    private struct WeakClient {
        weak var client: (any NowPlayingClient)?
    }

    private init() {
        let center = MPRemoteCommandCenter.shared()
        // Handed to the player in front on the main actor; a command is only
        // on while a player is in front (`enable`).
        func on(_ command: MPRemoteCommand, _ make: @escaping @Sendable (MPRemoteCommandEvent) -> RemoteCommand?) {
            command.addTarget { [weak self] event in
                guard let remote = make(event) else { return .commandFailed }
                Task { @MainActor in self?.dispatch(remote) }
                return .success
            }
        }
        on(center.playCommand) { _ in .play }
        on(center.pauseCommand) { _ in .pause }
        on(center.togglePlayPauseCommand) { _ in .toggle }
        on(center.skipForwardCommand) { _ in .skip(forward: true) }
        on(center.skipBackwardCommand) { _ in .skip(forward: false) }
        on(center.nextTrackCommand) { _ in .step(1) }
        on(center.previousTrackCommand) { _ in .step(-1) }
        on(center.changePlaybackPositionCommand) { event in
            (event as? MPChangePlaybackPositionCommandEvent).map { .seek(Int64($0.positionTime * 1_000)) }
        }
        enable(nil)
    }

    private func dispatch(_ command: RemoteCommand) {
        guard let current = owners.current else { return }
        clients[current]?.client?.remote(command)
    }

    /// `source`'s player, for its commands.
    func register(_ source: AudioSource, _ client: any NowPlayingClient) {
        clients[source] = WeakClient(client: client)
    }

    /// `source` plays, or was given something to play: it is in front, with
    /// these commands, and shows its title.
    func take(_ source: AudioSource, commands: Commands = Commands()) {
        self.commands[source] = commands
        let changed = owners.current != source
        owners.take(source)
        enable(commands)
        if changed { clients[source]?.client?.publishNowPlaying() }
    }

    /// Its next or previous came or went (a new episode's plan).
    func update(_ source: AudioSource, commands: Commands) {
        self.commands[source] = commands
        if owners.current == source { enable(commands) }
    }

    /// `source` has nothing on it any more: the one behind it shows its own
    /// again, or nothing does.
    func release(_ source: AudioSource) {
        guard owners.holds(source) else { return }
        let wasFront = owners.current == source
        owners.release(source)
        commands[source] = nil
        guard wasFront else { return }
        if let next = owners.current, let client = clients[next]?.client {
            enable(commands[next] ?? Commands())
            client.publishNowPlaying()
        } else {
            enable(nil)
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            #if os(macOS)
            MPNowPlayingInfoCenter.default().playbackState = .stopped
            #endif
            summary = "none"
        }
    }

    /// `source`'s title, picture and moment, shown if it is in front.
    func publish(_ source: AudioSource, info: [String: Any], playing: Bool) {
        guard owners.current == source else { return }
        let center = MPNowPlayingInfoCenter.default()
        center.nowPlayingInfo = info
        #if os(macOS)
        center.playbackState = playing ? .playing : .paused
        #endif
        let title = info[MPMediaItemPropertyTitle] as? String ?? ""
        let next = "\(source) · \(title)"
        if next != summary { summary = next }
    }

    /// Four times a second only the moment changes.
    func publishPosition(_ source: AudioSource, elapsedSeconds: Double, rate: Double) {
        guard owners.current == source, var info = MPNowPlayingInfoCenter.default().nowPlayingInfo else { return }
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = elapsedSeconds
        info[MPNowPlayingInfoPropertyPlaybackRate] = rate
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        #if os(macOS)
        MPNowPlayingInfoCenter.default().playbackState = rate > 0 ? .playing : .paused
        #endif
    }

    /// The commands on for the player in front, or all off.
    private func enable(_ commands: Commands?) {
        let center = MPRemoteCommandCenter.shared()
        let interval = [NSNumber(value: ListeningSettings.seekSeconds)]
        center.skipForwardCommand.preferredIntervals = interval
        center.skipBackwardCommand.preferredIntervals = interval
        let on = commands != nil
        for command in [center.playCommand, center.pauseCommand, center.togglePlayPauseCommand,
                        center.skipForwardCommand, center.skipBackwardCommand, center.changePlaybackPositionCommand] {
            command.isEnabled = on
        }
        center.nextTrackCommand.isEnabled = commands?.next ?? false
        center.previousTrackCommand.isEnabled = commands?.previous ?? false
    }

    /// A picture for the lock screen, made away from the main actor: the
    /// lock screen asks for it on a queue of its own.
    nonisolated static func artwork(_ data: Data) -> MPMediaItemArtwork? {
        #if os(iOS)
        guard let image = UIImage(data: data) else { return nil }
        #else
        guard let image = NSImage(data: data) else { return nil }
        #endif
        return MPMediaItemArtwork(boundsSize: image.size) { _ in image }
    }
}
