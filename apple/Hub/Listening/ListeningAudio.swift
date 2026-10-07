import AVFoundation
import Foundation
import HubKit

/// What the audiobook player and read along's narration share (#25): a
/// track's asset with the bearer in its header, never in its address; the
/// demo's tracks as tones written on this device; and the audio session.
enum ListeningAudio {
    /// A hub track's asset. The hub takes the token only in its header.
    static func asset(_ url: URL, token: String) -> AVURLAsset {
        url.isFileURL ? AVURLAsset(url: url)
            : AVURLAsset(url: url, options: ["AVURLAssetHTTPHeaderFieldsKey": ["Authorization": "Bearer " + token]])
    }

    /// An item that keeps a voice's pitch at any speed.
    static func item(_ url: URL, token: String) -> AVPlayerItem {
        let item = AVPlayerItem(asset: asset(url, token: token))
        item.audioTimePitchAlgorithm = .timeDomain
        return item
    }

    /// The demo's tracks as tones, one file each, written once a run by
    /// track: AVPlayer cannot fetch from the demo hub, which answers only the
    /// app's own requests. The addresses by the tracks' `index`.
    static func demoFiles(_ manifest: ReadingAudioManifest, sourceItemId: String) async -> [Int: String] {
        let folder = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("demo-audio", isDirectory: true)
        let tracks = manifest.tracks
        return await Task.detached(priority: .utility) { () -> [Int: String] in
            try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            var urls: [Int: String] = [:]
            for track in tracks {
                let file = folder.appendingPathComponent("\(sourceItemId)-\(track.index).wav")
                if !FileManager.default.fileExists(atPath: file.path) {
                    let tone = 220 + 55 * Double(track.index % 4)
                    try? DemoAudio.wav(milliseconds: track.durationMs, frequency: tone).write(to: file)
                }
                urls[track.index] = file.absoluteString
            }
            return urls
        }.value
    }

    /// Where a track is heard from: the hub's route under the manifest's
    /// revision, or the demo's tone.
    static func trackAddress(_ index: Int, manifest: ReadingAudioManifest, workId: String, sourceItemId: String,
                             address: String, demo: [Int: String]?) -> String {
        if let demo { return demo[index] ?? "" }
        return address + HubEndpoints.readingAudioTrack(workId: workId, sourceItemId: sourceItemId, index: index,
                                                        revision: manifest.revision)
    }

    /// The players holding the audio session: it is given back only when none does.
    @MainActor private static var holders: Set<AudioSource> = []

    /// Plays with the ring switch on silent, and goes on with the screen off.
    @MainActor static func activateSession(for source: AudioSource) {
        holders.insert(source)
        #if os(iOS)
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio)
        try? AVAudioSession.sharedInstance().setActive(true)
        #endif
    }

    /// `source` is done with sound; the session goes back once nothing else holds it,
    /// so the audiobook leaving cannot silence a book's narration.
    @MainActor static func deactivateSession(for source: AudioSource) {
        holders.remove(source)
        guard holders.isEmpty else { return }
        #if os(iOS)
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        #endif
    }
}
