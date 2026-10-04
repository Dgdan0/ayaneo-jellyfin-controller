import AVFoundation
import SwiftUI

/// The picture: AVPlayer drawn into an `AVPlayerLayer`, letterboxed, with
/// nothing of its own on top (the chrome is SwiftUI). `ready` is told when the
/// first frame is on screen, so the title's picture standing in can go.
#if os(iOS)
struct VideoSurface: UIViewRepresentable {
    let player: AVPlayer
    let ready: (Bool) -> Void

    func makeUIView(context: Context) -> Surface {
        let view = Surface()
        view.playerLayer.player = player
        view.onReady = ready
        return view
    }

    func updateUIView(_ view: Surface, context: Context) {
        view.onReady = ready
        if view.playerLayer.player !== player { view.playerLayer.player = player }
    }

    final class Surface: UIView {
        override class var layerClass: AnyClass { AVPlayerLayer.self }
        var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
        var onReady: ((Bool) -> Void)?
        private var observation: NSKeyValueObservation?

        override init(frame: CGRect) {
            super.init(frame: frame)
            backgroundColor = .clear
            isUserInteractionEnabled = false
            playerLayer.videoGravity = .resizeAspect
            observation = playerLayer.observe(\.isReadyForDisplay, options: [.initial, .new]) { [weak self] layer, _ in
                let ready = layer.isReadyForDisplay
                Task { @MainActor in self?.onReady?(ready) }
            }
        }

        required init?(coder: NSCoder) { nil }
    }
}
#else
struct VideoSurface: NSViewRepresentable {
    let player: AVPlayer
    let ready: (Bool) -> Void

    func makeNSView(context: Context) -> Surface {
        let view = Surface()
        view.playerLayer.player = player
        view.onReady = ready
        return view
    }

    func updateNSView(_ view: Surface, context: Context) {
        view.onReady = ready
        if view.playerLayer.player !== player { view.playerLayer.player = player }
    }

    final class Surface: NSView {
        let playerLayer = AVPlayerLayer()
        var onReady: ((Bool) -> Void)?
        private var observation: NSKeyValueObservation?

        override init(frame: CGRect) {
            super.init(frame: frame)
            // A layer-hosting view: the layer is set before asking for one.
            layer = playerLayer
            wantsLayer = true
            playerLayer.videoGravity = .resizeAspect
            playerLayer.backgroundColor = .clear
            observation = playerLayer.observe(\.isReadyForDisplay, options: [.initial, .new]) { [weak self] layer, _ in
                let ready = layer.isReadyForDisplay
                Task { @MainActor in self?.onReady?(ready) }
            }
        }

        required init?(coder: NSCoder) { nil }

        override func layout() {
            super.layout()
            playerLayer.frame = bounds
        }

        /// Taps belong to the chrome above.
        override func hitTest(_ point: NSPoint) -> NSView? { nil }
    }
}
#endif
