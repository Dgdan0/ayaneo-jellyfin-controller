import CoreGraphics

/// How the picture fills the player (#33; Android's `PlaybackAspect` and This
/// video › Aspect): Fit keeps the whole picture, Fill stretches it to the
/// player, Zoom fills the player and cuts the edges off, and Original keeps
/// its own shape at the player's height. For the video playing only: every
/// video opens at Fit, as on the Pocket.
public enum PlaybackAspect: String, CaseIterable, Sendable {
    case fit, fill, zoom, original

    public static let standard = PlaybackAspect.fit

    /// Where the picture is drawn in a player `container` big, for a video of
    /// `video` size. Zoomed or at its own shape it may overflow the player,
    /// which cuts it off; until the video's size is known, the whole player.
    public func pictureRect(video: CGSize, in container: CGSize) -> CGRect {
        let whole = CGRect(origin: .zero, size: container)
        guard video.width > 0, video.height > 0, container.width > 0, container.height > 0 else { return whole }
        let scale: CGFloat
        switch self {
        case .fill: return whole
        case .fit: scale = min(container.width / video.width, container.height / video.height)
        case .zoom: scale = max(container.width / video.width, container.height / video.height)
        case .original: scale = container.height / video.height
        }
        let width = video.width * scale, height = video.height * scale
        return CGRect(x: (container.width - width) / 2, y: (container.height - height) / 2, width: width, height: height)
    }

    /// The part of the picture inside the player: where drawn subtitles go.
    public func visibleRect(video: CGSize, in container: CGSize) -> CGRect {
        pictureRect(video: video, in: container).intersection(CGRect(origin: .zero, size: container))
    }
}

extension PlayerLabels {
    /// "Fit", "Fill", "Zoom", "Original aspect".
    public static func aspect(_ value: PlaybackAspect) -> String {
        switch value {
        case .fit: "Fit"
        case .fill: "Fill"
        case .zoom: "Zoom"
        case .original: "Original aspect"
        }
    }

    public static let aspectNote = "Fit keeps the whole picture visible."
}
