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

/// The key a decoded image is kept under: the hub path asked for and how
/// large it was decoded, since the Glass page keeps a tiny copy of a picture
/// a card shows full size.
func artworkMemoryKey(_ request: String, maxPixels: Int) -> String {
    request + "#\(maxPixels)"
}

/// The one image loader: a hub image path as asked for ("/v1/img/…", already
/// `sized` or `smallest`) through `HubClient.image`, so the credential gate and
/// the artwork cache apply to it (Android's `ui/Artwork.bindHub`), decoded to
/// at most `maxPixels` off the main actor and kept in memory. Nil when it
/// cannot be had.
@MainActor
func loadArtwork(_ hub: HubClient, request: String, maxPixels: Int) async -> DecodedArtwork? {
    guard !request.isEmpty else { return nil }
    let key = artworkMemoryKey(request, maxPixels: maxPixels)
    if let cached = ArtworkMemory.shared.get(key) { return cached }
    guard let data = try? await hub.image(request), !Task.isCancelled else { return nil }
    let decoded = await Task.detached(priority: .userInitiated) { decodeArtwork(data, maxPixels: maxPixels) }.value
    guard let decoded, !Task.isCancelled else { return nil }
    ArtworkMemory.shared.set(key, decoded)
    return decoded
}

/// Any hub image ("/v1/img/…"), through `loadArtwork`. The placeholder colour
/// shows until it arrives, and stays when the path is empty. It fills
/// whatever frame it is given.
struct ArtworkView: View {
    @Environment(AppModel.self) private var model
    let path: String
    /// The pixel width to ask the hub for; it snaps to its nearest size.
    let width: Int
    var placeholder: Color = .placeholder
    /// Keeps the picture until the next one has loaded and cross-fades to it:
    /// for a hero that changes as focus runs along a row, which would
    /// otherwise flash its placeholder between titles.
    var keepsPrevious = false

    @State private var shown: Shown?

    private struct Shown {
        let key: String
        let image: DecodedArtwork
    }

    /// Twice the width covers a 2:3 poster's height.
    private var maxPixels: Int { width * 2 }
    private var request: String { path.isEmpty ? "" : HubEndpoints.sized(path, width: width) }
    private var key: String { request.isEmpty ? "" : artworkMemoryKey(request, maxPixels: maxPixels) }

    var body: some View {
        // The image is an overlay so a fill never changes the frame it was given.
        placeholder
            .overlay {
                if let shown {
                    Image(decorative: shown.image.image, scale: 1)
                        .resizable()
                        .scaledToFill()
                        .id(shown.key)
                        .transition(.opacity)
                }
            }
            .clipped()
            // A fill reaches past the frame it was given, and clipping hides it
            // without stopping it taking taps: a portrait cover behind a wide
            // library tile took the taps meant for the tile above it.
            .contentShape(Rectangle())
            .task(id: key) { await load() }
    }

    private func load() async {
        let fade = Animation.easeOut(duration: keepsPrevious ? 0.3 : 0.2)
        guard !key.isEmpty else {
            withAnimation(keepsPrevious ? fade : nil) { shown = nil }
            return
        }
        // A picture already decoded shows at once, with no fade unless it
        // replaces another.
        if let cached = ArtworkMemory.shared.get(key) {
            withAnimation(keepsPrevious && shown != nil ? fade : nil) { shown = Shown(key: key, image: cached) }
            return
        }
        if !keepsPrevious { shown = nil }
        let wanted = key
        guard let decoded = await loadArtwork(model.hub, request: request, maxPixels: maxPixels) else { return }
        withAnimation(fade) { shown = Shown(key: wanted, image: decoded) }
    }
}
