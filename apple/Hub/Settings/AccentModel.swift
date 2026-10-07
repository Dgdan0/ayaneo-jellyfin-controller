import Foundation
import HubKit
import Observation

/// The accent each side wears (#38): chosen in Settings › Appearance, kept per
/// hub and profile, and read by the shell for every page, the side picker and
/// the readers. One for the app, as `ListeningModel.shared` is, so a reader
/// opened over the window finds the same Books colour without being handed it.
@MainActor
@Observable
final class AccentModel {
    static let shared = AccentModel()

    private(set) var media = AccentPreset.defaultFor(.media)
    private(set) var books = AccentPreset.defaultFor(.books)

    @ObservationIgnored private var hub = ""
    @ObservationIgnored private var profile = ""
    /// The demo hub keeps its choices for the run only: a test that picks a
    /// colour must not leave it chosen for the next.
    @ObservationIgnored private var memory: [String: AccentPreset]?

    func accent(_ side: AppSide) -> AccentPreset { side == .media ? media : books }

    /// Reads the choices of this hub and profile.
    func use(address: String, userId: String, demo: Bool = false) {
        hub = address
        profile = userId
        if demo {
            if memory == nil { memory = [:] }
        } else {
            memory = nil
        }
        media = stored(.media)
        books = stored(.books)
    }

    func choose(_ preset: AccentPreset, for side: AppSide) {
        if memory != nil {
            memory?[PreferenceScope.key(hub: hub, profile: profile, side: side)] = preset
        } else {
            AccentSettings.set(preset, for: side, hub: hub, profile: profile)
        }
        if side == .media { media = preset } else { books = preset }
    }

    private func stored(_ side: AppSide) -> AccentPreset {
        if let memory { return memory[PreferenceScope.key(hub: hub, profile: profile, side: side)] ?? .defaultFor(side) }
        return AccentSettings.accent(for: side, hub: hub, profile: profile)
    }
}
