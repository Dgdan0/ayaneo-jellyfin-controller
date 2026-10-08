import HubKit
import SwiftUI

/// Artwork that fades into the Glass page through a mask, with the shade for
/// the words over it inside the same mask: Android's `FadedImageView`. A fade
/// into a colour ended in a band of near-black, and a shade of its own would
/// stop in a line across the page (GLASS_PLAN.md, "Title pages").
///
/// It keeps its picture until the next has loaded and cross-fades, so a hero
/// following focus never flashes empty.
struct FadedArtwork<Picture: View>: View {
    /// Where the words sit, so the shade darkens that side.
    enum Shade {
        /// From the leading edge: this dark there, gone by `until` across.
        case leading(Double, until: Double)
        /// From the bottom edge: this dark there, gone by `until` up.
        case bottom(Double, until: Double)
    }

    /// Solid down to this fraction of the height, gone by `goneBy`.
    let solidUntil: Double
    let goneBy: Double
    let shade: Shade
    /// The picture: a hub image (`ArtworkView`), or one kept on the device.
    let picture: Picture

    init(solidUntil: Double, goneBy: Double, shade: Shade, @ViewBuilder picture: () -> Picture) {
        self.solidUntil = solidUntil
        self.goneBy = goneBy
        self.shade = shade
        self.picture = picture()
    }

    /// A title's page (`.dart`): solid to 30%, gone by 94%, the shade at 60% gone by
    /// 72% across.
    static func titleFade(@ViewBuilder _ picture: () -> Picture) -> FadedArtwork {
        FadedArtwork(solidUntil: 0.3, goneBy: 0.94, shade: .leading(0.6, until: 0.72), picture: picture)
    }

    var body: some View {
        picture
            .overlay { shadeView }
            .mask {
                LinearGradient(stops: [.init(color: .black, location: solidUntil), .init(color: .clear, location: goneBy)],
                               startPoint: .top, endPoint: .bottom)
            }
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }

    @ViewBuilder private var shadeView: some View {
        switch shade {
        case .leading(let opacity, let until):
            LinearGradient(stops: [.init(color: .black.opacity(opacity), location: 0), .init(color: .clear, location: until)],
                           startPoint: .leading, endPoint: .trailing)
        case .bottom(let opacity, let until):
            LinearGradient(stops: [.init(color: .black.opacity(opacity), location: 0), .init(color: .clear, location: until)],
                           startPoint: .bottom, endPoint: .top)
        }
    }
}

extension FadedArtwork where Picture == ArtworkView {
    /// Home's hero: solid to 40% of the way down, gone by 97%, a shade half
    /// black at the left gone by 62% across (the prototype's `.hero`).
    static func hero(_ path: String, centred: Bool) -> FadedArtwork {
        FadedArtwork(solidUntil: 0.4, goneBy: 0.97,
                     shade: centred ? .bottom(0.45, until: 0.55) : .leading(0.5, until: 0.62)) {
            ArtworkView(path: path, width: 1920, placeholder: .clear, keepsPrevious: true)
        }
    }

    /// A title's page: solid to 30%, gone by 94%, the shade at 60% gone by
    /// 72% across (`.dart`).
    static func title(_ path: String) -> FadedArtwork {
        titleFade { ArtworkView(path: path, width: 1920, placeholder: .clear, keepsPrevious: true) }
    }
}

/// Facts as one line, each part separated by a faint dot (`.meta`, `.facts`).
func factsLine(_ parts: [String]) -> AttributedString {
    var line = AttributedString()
    for (index, part) in parts.enumerated() {
        if index > 0 {
            var dot = AttributedString("  ·  ")
            dot.foregroundColor = Color.white.opacity(0.45)
            line += dot
        }
        var words = AttributedString(part)
        words.foregroundColor = Color.white.opacity(0.82)
        line += words
    }
    return line
}
