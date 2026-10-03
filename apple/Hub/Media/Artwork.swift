import HubKit
import ImageIO
import SwiftUI

/// A decoded image, boxed so it can travel from the decoding task to the main
/// actor and sit in an `NSCache`.
final class DecodedArtwork: @unchecked Sendable {
    let image: CGImage

    init(_ image: CGImage) {
        self.image = image
    }
}

/// Decoded artwork in memory, keyed by sized hub path, so a row scrolled back
/// into view does not decode the same poster again. The bytes themselves also
/// sit in the artwork transport's disk cache: the hub marks images immutable.
@MainActor
final class ArtworkMemory {
    static let shared = ArtworkMemory()
    private let cache = NSCache<NSString, DecodedArtwork>()

    init() {
        cache.countLimit = 400
    }

    func get(_ key: String) -> DecodedArtwork? { cache.object(forKey: key as NSString) }
    func set(_ key: String, _ value: DecodedArtwork) { cache.setObject(value, forKey: key as NSString) }
}

/// Decodes to at most `maxPixels` on the long side, off the main actor: a
/// 60-poster Library page decoded on the main thread stutters while it scrolls.
func decodeArtwork(_ data: Data, maxPixels: Int) -> DecodedArtwork? {
    guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
    let options: [CFString: Any] = [
        kCGImageSourceCreateThumbnailFromImageAlways: true,
        kCGImageSourceThumbnailMaxPixelSize: maxPixels,
        kCGImageSourceCreateThumbnailWithTransform: true,
        kCGImageSourceShouldCacheImmediately: true,
    ]
    guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
    return DecodedArtwork(image)
}

/// Any hub image ("/v1/img/…"), loaded through `HubClient.image`, so the
/// credential gate and the artwork cache apply to it (Android's
/// `ui/Artwork.bindHub`). The placeholder colour shows until it arrives, and
/// stays when the path is empty. It fills whatever frame it is given.
struct ArtworkView: View {
    @Environment(AppModel.self) private var model
    let path: String
    /// The pixel width to ask the hub for; it snaps to its nearest size.
    let width: Int

    @State private var image: DecodedArtwork?

    private var key: String { path.isEmpty ? "" : HubEndpoints.sized(path, width: width) }

    var body: some View {
        // The image is an overlay so a fill never changes the frame it was given.
        Color.placeholder
            .overlay {
                if let image {
                    Image(decorative: image.image, scale: 1)
                        .resizable()
                        .scaledToFill()
                        .transition(.opacity)
                }
            }
            .clipped()
            .task(id: key) { await load() }
    }

    private func load() async {
        let request = key
        guard !request.isEmpty else {
            image = nil
            return
        }
        if let cached = ArtworkMemory.shared.get(request) {
            image = cached
            return
        }
        image = nil
        guard let data = try? await model.hub.image(request), !Task.isCancelled else { return }
        // Twice the width covers a 2:3 poster's height.
        let maxPixels = width * 2
        let decoded = await Task.detached(priority: .userInitiated) { decodeArtwork(data, maxPixels: maxPixels) }.value
        guard let decoded, !Task.isCancelled else { return }
        ArtworkMemory.shared.set(request, decoded)
        withAnimation(.easeOut(duration: 0.2)) { image = decoded }
    }
}
