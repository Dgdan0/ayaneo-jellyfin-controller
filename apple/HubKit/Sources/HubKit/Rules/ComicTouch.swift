import Foundation

/// What a finger does on a comic's page (#18, C7), as the keys do it: a tap
/// in the outer third reads on or back (Ⓐ and Ⓑ), a tap in the middle shows
/// or hides the controls (Start), and a swipe across turns the page while the
/// page is not zoomed. Android's `reader/ComicTouch.kt`, with its test cases.
///
/// The page keeps everything else: a drag pans, a pinch and a double tap
/// zoom. So a swipe turns nothing while the page is zoomed past its fit (it
/// pans there), nothing that was part of a pinch, and nothing mostly up or
/// down (that scrolls down a page read at its width). Right to left, the
/// sides swap: the next page is on the left.
public enum ComicTouch {
    public enum Tap: Sendable { case back, controls, forward }

    /// A swipe must cross this much of the view, and be this much more across than down.
    public static let swipeShare = 0.12
    public static let swipeSlope = 1.5

    /// A tap at `x` across a view `width` wide. With the controls showing, any tap on the page hides them.
    public static func tap(x: Double, width: Double, rtl: Bool, controlsVisible: Bool) -> Tap {
        if controlsVisible || width <= 0 { return .controls }
        let third = width / 3
        if x < third { return rtl ? .forward : .back }
        if x > width - third { return rtl ? .back : .forward }
        return .controls
    }

    /// A swipe that moved `dx` by `dy` across a view `width` wide: +1 for the
    /// next page, -1 for the one before, 0 to leave it to the page.
    /// `zoomed`: the page is past its fit, so a drag pans it. `pinched`: a
    /// second finger was down during the gesture, or the scale changed in it.
    public static func swipe(dx: Double, dy: Double, width: Double, rtl: Bool, zoomed: Bool, pinched: Bool) -> Int {
        if zoomed || pinched || width <= 0 { return 0 }
        if abs(dx) < width * swipeShare || abs(dx) < abs(dy) * swipeSlope { return 0 }
        // A finger moving left brings the next page in from the right, reading left to right.
        let towardLeft = dx < 0
        return towardLeft != rtl ? 1 : -1
    }
}
