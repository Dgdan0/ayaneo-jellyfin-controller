import CoreGraphics
import Testing
@testable import HubKit

/// This video › Aspect (#33): where the picture is drawn for each choice.
struct PlaybackAspectTests {
    private let screen = CGSize(width: 1600, height: 900)
    /// A film in 2.4:1 and an old show in 4:3.
    private let film = CGSize(width: 1920, height: 800)
    private let square = CGSize(width: 1440, height: 1080)

    private func close(_ a: CGRect, _ b: CGRect) -> Bool {
        abs(a.minX - b.minX) < 0.01 && abs(a.minY - b.minY) < 0.01 && abs(a.width - b.width) < 0.01 && abs(a.height - b.height) < 0.01
    }

    @Test func fitKeepsTheWholePictureAndFillStretchesIt() {
        let fit = PlaybackAspect.fit.pictureRect(video: film, in: screen)
        #expect(close(fit, CGRect(x: 0, y: 116.667, width: 1600, height: 666.667)))
        #expect(close(PlaybackAspect.fit.pictureRect(video: square, in: screen), CGRect(x: 200, y: 0, width: 1200, height: 900)))
        #expect(PlaybackAspect.fill.pictureRect(video: film, in: screen) == CGRect(x: 0, y: 0, width: 1600, height: 900))
    }

    @Test func zoomFillsThePlayerAndOriginalKeepsItsShapeAtTheHeight() {
        let zoom = PlaybackAspect.zoom.pictureRect(video: film, in: screen)
        #expect(zoom.height == 900 && zoom.width == 2160 && zoom.minX == -280)
        let original = PlaybackAspect.original.pictureRect(video: film, in: screen)
        #expect(original == zoom)
        // A 4:3 picture at the player's height is pillarboxed, as Fit has it.
        #expect(close(PlaybackAspect.original.pictureRect(video: square, in: screen), CGRect(x: 200, y: 0, width: 1200, height: 900)))
        // Subtitles stay on the part of the picture on screen.
        #expect(PlaybackAspect.zoom.visibleRect(video: film, in: screen) == CGRect(x: 0, y: 0, width: 1600, height: 900))
    }

    @Test func untilTheVideosSizeIsKnownItIsTheWholePlayer() {
        for aspect in PlaybackAspect.allCases {
            #expect(aspect.pictureRect(video: .zero, in: screen) == CGRect(x: 0, y: 0, width: 1600, height: 900))
        }
        #expect(PlaybackAspect.standard == .fit)
        #expect(PlaybackAspect.allCases.map(PlayerLabels.aspect) == ["Fit", "Fill", "Zoom", "Original aspect"])
    }
}
