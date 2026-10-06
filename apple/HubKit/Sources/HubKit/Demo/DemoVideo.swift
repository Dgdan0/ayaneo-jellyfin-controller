import AVFoundation
import CoreGraphics
import CoreText
import CoreVideo
import Foundation
import Synchronization

/// The demo hub's video for offline downloads (#5): a real MP4 that AVPlayer
/// plays, written here once per run with AVAssetWriter, so a download can be
/// tried end to end with no hub and no file in the repository. Twelve
/// seconds at 640 x 360: a colour for each second, its number large, and a
/// bar crossing the picture. H.264, the moov box first, as the hub's Apple
/// files will be.
public enum DemoVideo {
    static let seconds = 12
    static let width = 640
    static let height = 360
    static let framesPerSecond: Int32 = 24

    /// The one writing, which every caller awaits: one encoder at a time.
    private static let writing = Mutex<Task<Data?, Never>?>(nil)

    /// The video's bytes, written the first time they are asked for.
    public static func data() async -> Data? {
        let task = writing.withLock { current in
            if let current { return current }
            let started = Task { await make() }
            current = started
            return started
        }
        let data = await task.value
        // A failed attempt is not kept: the next caller tries again.
        if data == nil { writing.withLock { current in if current == task { current = nil } } }
        return data
    }

    private static func make() async -> Data? {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("jellyhub-demo-\(UUID().uuidString).mp4")
        defer { try? FileManager.default.removeItem(at: file) }
        do {
            try await write(to: file)
        } catch {
            return nil
        }
        guard let data = try? Data(contentsOf: file), !data.isEmpty else { return nil }
        return data
    }

    static func write(to file: URL) async throws {
        let writer = try AVAssetWriter(outputURL: file, fileType: .mp4)
        writer.shouldOptimizeForNetworkUse = true
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: width, AVVideoHeightKey: height,
        ])
        input.expectsMediaDataInRealTime = false
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey as String: width,
            kCVPixelBufferHeightKey as String: height,
        ])
        writer.add(input)
        guard writer.startWriting() else { throw writer.error ?? CocoaError(.fileWriteUnknown) }
        writer.startSession(atSourceTime: .zero)
        let frames = seconds * Int(framesPerSecond)
        for frame in 0..<frames {
            while !input.isReadyForMoreMediaData { try await Task.sleep(for: .milliseconds(2)) }
            guard let pool = adaptor.pixelBufferPool else { throw CocoaError(.fileWriteUnknown) }
            var created: CVPixelBuffer?
            CVPixelBufferPoolCreatePixelBuffer(nil, pool, &created)
            guard let buffer = created else { throw CocoaError(.fileWriteUnknown) }
            draw(frame, of: frames, into: buffer)
            guard adaptor.append(buffer, withPresentationTime: CMTime(value: CMTimeValue(frame), timescale: framesPerSecond))
            else { throw writer.error ?? CocoaError(.fileWriteUnknown) }
        }
        input.markAsFinished()
        await writer.finishWriting()
        guard writer.status == .completed else { throw writer.error ?? CocoaError(.fileWriteUnknown) }
    }

    private static func draw(_ frame: Int, of frames: Int, into buffer: CVPixelBuffer) {
        CVPixelBufferLockBaseAddress(buffer, [])
        defer { CVPixelBufferUnlockBaseAddress(buffer, []) }
        guard let context = CGContext(data: CVPixelBufferGetBaseAddress(buffer), width: width, height: height,
                                      bitsPerComponent: 8, bytesPerRow: CVPixelBufferGetBytesPerRow(buffer),
                                      space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue
                                          | CGBitmapInfo.byteOrder32Little.rawValue) else { return }
        let second = frame / Int(framesPerSecond)
        let hue = CGFloat(second) / CGFloat(seconds)
        context.setFillColor(color(hue: hue, brightness: 0.55))
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        let across = CGFloat(frame) / CGFloat(max(frames - 1, 1)) * CGFloat(width - 12)
        context.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 0.85))
        context.fill(CGRect(x: across, y: 0, width: 12, height: CGFloat(height)))
        let font = CTFontCreateWithName("Helvetica-Bold" as CFString, 150, nil)
        let words = NSAttributedString(string: "\(second + 1)", attributes: [
            NSAttributedString.Key(kCTFontAttributeName as String): font,
            NSAttributedString.Key(kCTForegroundColorAttributeName as String): CGColor(red: 1, green: 1, blue: 1, alpha: 1),
        ])
        let line = CTLineCreateWithAttributedString(words)
        let bounds = CTLineGetBoundsWithOptions(line, .useGlyphPathBounds)
        context.textPosition = CGPoint(x: (CGFloat(width) - bounds.width) / 2 - bounds.minX,
                                       y: (CGFloat(height) - bounds.height) / 2 - bounds.minY)
        CTLineDraw(line, context)
    }

    private static func color(hue: CGFloat, brightness: CGFloat) -> CGColor {
        // HSV to RGB, saturation 0.6.
        let saturation: CGFloat = 0.6
        let sector = (hue * 6).truncatingRemainder(dividingBy: 6)
        let fraction = sector - floor(sector)
        let p = brightness * (1 - saturation)
        let q = brightness * (1 - saturation * fraction)
        let t = brightness * (1 - saturation * (1 - fraction))
        let (red, green, blue): (CGFloat, CGFloat, CGFloat) = switch Int(sector) {
        case 0: (brightness, t, p)
        case 1: (q, brightness, p)
        case 2: (p, brightness, t)
        case 3: (p, q, brightness)
        case 4: (t, p, brightness)
        default: (brightness, p, q)
        }
        return CGColor(red: red, green: green, blue: blue, alpha: 1)
    }
}
