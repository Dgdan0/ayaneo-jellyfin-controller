import HubKit
import Observation
import SwiftUI

/// Which artwork the Glass page shows, for SwiftUI. The rules are HubKit's
/// `AmbientStack`; pages report their artwork with `.ambientArtwork(_:)` and the
/// shell says which stack is on screen.
@MainActor
@Observable
final class AmbientModel {
    private(set) var stack = AmbientStack()

    var displayed: String { stack.displayed }

    func show(_ path: String, token: UUID, in stackKey: String) {
        stack.show(path, token: token, stack: stackKey)
    }

    func remove(_ token: UUID) {
        stack.remove(token: token)
    }

    func select(_ stackKey: String) {
        stack.select(stack: stackKey)
    }
}

extension EnvironmentValues {
    /// The navigation stack a page belongs to, set by the shell, so the
    /// artwork a page reports is shown only while its section is.
    @Entry var shellStack = ""
}

/// "This is my artwork": the page's picture becomes the Glass page behind it
/// while the page is on screen (GLASS_PLAN.md, "Focus re-tints the page").
struct AmbientArtwork: ViewModifier {
    let path: String
    @Environment(AmbientModel.self) private var ambient: AmbientModel?
    @Environment(\.shellStack) private var stack
    @State private var token = UUID()

    func body(content: Content) -> some View {
        content
            .onAppear { ambient?.show(path, token: token, in: stack) }
            .onChange(of: path) { _, latest in ambient?.show(latest, token: token, in: stack) }
            .onDisappear { ambient?.remove(token) }
    }
}

extension View {
    /// Reports `path` (a hub image path) as this page's artwork.
    func ambientArtwork(_ path: String) -> some View {
        modifier(AmbientArtwork(path: path))
    }
}

/// The Glass page: the artwork in focus, small and heavily blurred, over its
/// dark colour, under a veil that keeps type readable on any picture.
///
/// The prototype's `blur(72px) saturate(1.5) brightness(.62)`. SwiftUI's
/// `brightness` adds to each channel where CSS scales it, so the scaling is a
/// multiply by 62% grey. The picture is decoded at under a hundred pixels: a
/// blur this wide leaves no detail a larger one would keep. A new picture
/// cross-fades in once it has loaded, so a slow one never leaves the page
/// blank, and the dark colour fades with it.
struct AmbientBackground: View {
    @Environment(AppModel.self) private var model
    let path: String
    let palette: ArtworkPalette

    @State private var shown: Shown?

    /// The prototype's 0.6–0.8 s, as Android's `AmbientLayerView.FADE_MS`.
    static let fade = Animation.easeInOut(duration: 0.7)

    private struct Shown {
        let path: String
        let image: DecodedArtwork
    }

    var body: some View {
        GeometryReader { geometry in
            let size = geometry.size
            // Drawn past the edges, as the prototype's `inset: -14%`, so the
            // blur fades into more picture rather than into nothing.
            let bleed = max(size.width, size.height) * 0.14 + 40
            ZStack {
                Color(argb: palette.dark)
                ZStack {
                    if let shown {
                        Image(decorative: shown.image.image, scale: 1)
                            .resizable()
                            .interpolation(.medium)
                            .scaledToFill()
                            .frame(width: size.width + bleed * 2, height: size.height + bleed * 2)
                            .id(shown.path)
                            .transition(.opacity)
                    }
                }
                .frame(width: size.width, height: size.height)
                .blur(radius: 60)
                .saturation(1.5)
                .colorMultiply(Color(white: 0.62))
                veil
            }
            .frame(width: size.width, height: size.height)
            .clipped()
        }
        .animation(Self.fade, value: palette)
        .task(id: path) { await load() }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    /// A faint light at the top left, darkening towards the bottom.
    private var veil: some View {
        ZStack {
            LinearGradient(colors: [.black.opacity(0.14), .black.opacity(0.6)], startPoint: .top, endPoint: .bottom)
            EllipticalGradient(colors: [.white.opacity(0.06), .clear], center: UnitPoint(x: 0.25, y: 0),
                               startRadiusFraction: 0, endRadiusFraction: 1.2)
        }
    }

    private func load() async {
        guard !path.isEmpty else {
            withAnimation(Self.fade) { shown = nil }
            return
        }
        guard shown?.path != path,
              let image = await loadArtwork(model.hub, path: path, width: 180, maxPixels: 96),
              !Task.isCancelled else { return }
        withAnimation(Self.fade) { shown = Shown(path: path, image: image) }
    }
}
