import AVFoundation
import AVKit
import HubKit
import SwiftUI

extension PlaybackAspect {
    /// How AVPlayerLayer draws it: Original keeps the picture's own shape
    /// inside a frame of that shape (`pictureRect`).
    var gravity: AVLayerVideoGravity {
        switch self {
        case .fit, .original: .resizeAspect
        case .fill: .resize
        case .zoom: .resizeAspectFill
        }
    }
}

/// The picture: AVPlayer drawn into an `AVPlayerLayer` as This video › Aspect
/// says (letterboxed until changed), with nothing of its own on top (the
/// chrome is SwiftUI). `ready` is told when the
/// first frame is on screen, so the title's picture standing in can go, and
/// `layer` is handed the layer, which picture in picture is built on.
#if os(iOS)
struct VideoSurface: UIViewRepresentable {
    let player: AVPlayer
    var gravity = AVLayerVideoGravity.resizeAspect
    let ready: (Bool) -> Void
    let layer: (AVPlayerLayer) -> Void

    func makeUIView(context: Context) -> Surface {
        let view = Surface()
        view.playerLayer.player = player
        view.playerLayer.videoGravity = gravity
        view.onReady = ready
        return view
    }

    func updateUIView(_ view: Surface, context: Context) {
        view.onReady = ready
        if view.playerLayer.player !== player { view.playerLayer.player = player }
        if view.playerLayer.videoGravity != gravity { view.playerLayer.videoGravity = gravity }
        layer(view.playerLayer)
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
    var gravity = AVLayerVideoGravity.resizeAspect
    let ready: (Bool) -> Void
    let layer: (AVPlayerLayer) -> Void

    func makeNSView(context: Context) -> Surface {
        let view = Surface()
        view.playerLayer.player = player
        view.playerLayer.videoGravity = gravity
        view.onReady = ready
        return view
    }

    func updateNSView(_ view: Surface, context: Context) {
        view.onReady = ready
        if view.playerLayer.player !== player { view.playerLayer.player = player }
        if view.playerLayer.videoGravity != gravity { view.playerLayer.videoGravity = gravity }
        layer(view.playerLayer)
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

#if os(iOS)
/// The system's AirPlay picker for the round Cast button, its own glyph
/// clear: the button draws an icon the size of its neighbours' under it, and
/// the picker takes the taps. AVPlayer hands a receiver the grant's address,
/// which needs no token (`PlaybackGrant`).
struct RoutePicker: UIViewRepresentable {
    let player: AVPlayer

    func makeUIView(context: Context) -> AVRoutePickerView {
        let view = AVRoutePickerView()
        view.tintColor = .clear
        view.activeTintColor = .clear
        view.prioritizesVideoDevices = true
        view.backgroundColor = .clear
        return view
    }

    func updateUIView(_ view: AVRoutePickerView, context: Context) {}
}
#else
struct RoutePicker: NSViewRepresentable {
    let player: AVPlayer

    func makeNSView(context: Context) -> AVRoutePickerView {
        let view = AVRoutePickerView()
        view.player = player
        view.isRoutePickerButtonBordered = false
        for state in [AVRoutePickerView.ButtonState.normal, .normalHighlighted, .active, .activeHighlighted] {
            view.setRoutePickerButtonColor(.clear, for: state)
        }
        return view
    }

    func updateNSView(_ view: AVRoutePickerView, context: Context) {
        if view.player !== player { view.player = player }
    }
}
#endif
