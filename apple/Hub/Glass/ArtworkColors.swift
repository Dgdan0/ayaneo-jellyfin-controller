import Foundation
import HubKit
import Observation

/// The artwork colours views read, kept on the main actor. HubKit's
/// `ArtworkColorStore` does the asking, the batching and the file; this is its
/// copy for SwiftUI, filled from the file at launch and by each answer after.
@MainActor
@Observable
final class ArtworkColors {
    private(set) var palettes: [String: ArtworkPalette] = [:]
    @ObservationIgnored private let store: ArtworkColorStore
    @ObservationIgnored private var listening: Task<Void, Never>?

    init(hub: HubClient, file: URL?) {
        store = ArtworkColorStore(file: file) { sources in
            try await hub.fetch(HubEndpoints.artworkColors(sources), as: ArtworkColorsResponse.self)
        }
        listening = Task { [store] in
            merge(await store.restore())
            for await batch in store.updates {
                merge(batch)
            }
        }
    }

    /// Where the colours are kept between launches, beside the app's other
    /// support files.
    static var file: URL? {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first?
            .appendingPathComponent(ArtworkColorStore.fileName)
    }

    /// The colours of `path`, or the neutral Glass ones until the hub has
    /// answered (and for artwork it cannot read).
    func palette(for path: String) -> ArtworkPalette {
        palettes[path] ?? .neutral
    }

    /// Asks for the colours of artwork a screen shows or is about to. Asks
    /// made in the same few frames share one request.
    func want(_ paths: [String]) {
        let unknown = paths.filter { !$0.isEmpty && palettes[$0] == nil }
        guard !unknown.isEmpty else { return }
        Task { [store] in await store.request(unknown) }
    }

    private func merge(_ batch: [String: ArtworkPalette]) {
        guard !batch.isEmpty else { return }
        palettes.merge(batch) { _, latest in latest }
    }
}
