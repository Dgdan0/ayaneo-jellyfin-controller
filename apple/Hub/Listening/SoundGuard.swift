import HubKit

/// One sound at a time (#25 phase 2; Android's `AudioArbiter`): the video,
/// an audiobook and a book's read-along narration never play over each
/// other. Each player says when it starts and stops; a start pauses whatever
/// else was playing, through the way of pausing it each gave. HubKit's
/// `AudioArbiter` decides. One for the app: the shell's players and a
/// reader's narration share it.
@MainActor
final class SoundGuard {
    static let shared = SoundGuard()

    private var arbiter = AudioArbiter()
    private var pausers: [AudioSource: () -> Void] = [:]

    /// How to pause `source` when something else starts.
    func pause(_ source: AudioSource, with action: @escaping () -> Void) {
        pausers[source] = action
    }

    /// `source` is gone (a reader closed with its narration): nothing to pause, nothing playing.
    func forget(_ source: AudioSource) {
        pausers[source] = nil
        arbiter.stop(source)
    }

    func started(_ source: AudioSource) {
        for other in arbiter.start(source) { pausers[other]?() }
    }

    func stopped(_ source: AudioSource) {
        arbiter.stop(source)
    }
}
