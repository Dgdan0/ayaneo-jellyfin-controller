import HubKit

/// One sound at a time (#25 phase 2; Android's `AudioArbiter`): the video
/// and an audiobook never play over each other. Each player says when it
/// starts and stops; a start pauses whatever else was playing, through the
/// way of pausing it the shell gave. HubKit's `AudioArbiter` decides.
@MainActor
final class SoundGuard {
    private var arbiter = AudioArbiter()
    private var pausers: [AudioSource: () -> Void] = [:]

    /// How to pause `source` when something else starts.
    func pause(_ source: AudioSource, with action: @escaping () -> Void) {
        pausers[source] = action
    }

    func started(_ source: AudioSource) {
        for other in arbiter.start(source) { pausers[other]?() }
    }

    func stopped(_ source: AudioSource) {
        arbiter.stop(source)
    }
}
