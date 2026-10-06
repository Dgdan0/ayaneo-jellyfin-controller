import CoreGraphics
import Foundation
import ImageIO

extension PageBounds {
    /// The content of a page from the hub's thumbnail of it (Android's
    /// `contentOfThumbnail`): decoded small whatever size the hub sent (it
    /// passes a page it cannot scale through as it is), at most `thumbWidth`
    /// across, its luma read and the paper round it found. Nil when the bytes
    /// are not a picture.
    public static func content(ofThumbnail data: Data) -> PageContent? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceThumbnailMaxPixelSize: thumbWidth * 4,
            kCGImageSourceCreateThumbnailWithTransform: true,
        ]
        guard let decoded = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary),
              decoded.width > 0, decoded.height > 0 else { return nil }
        let width = min(thumbWidth, decoded.width)
        let height = max(1, decoded.height * width / decoded.width)
        var pixels = [UInt8](repeating: 0, count: width * height * 4)
        let drawn = pixels.withUnsafeMutableBytes { buffer -> Bool in
            guard let context = CGContext(data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8,
                                          bytesPerRow: width * 4, space: CGColorSpaceCreateDeviceRGB(),
                                          bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return false }
            context.interpolationQuality = .high
            context.draw(decoded, in: CGRect(x: 0, y: 0, width: width, height: height))
            return true
        }
        guard drawn else { return nil }
        // A bitmap's first row is the picture's top.
        let luma = (0..<(width * height)).map { index in
            let pixel = index * 4
            return (Int(pixels[pixel]) * 299 + Int(pixels[pixel + 1]) * 587 + Int(pixels[pixel + 2]) * 114) / 1_000
        }
        return detect(luma, width: width, height: height)
    }
}
