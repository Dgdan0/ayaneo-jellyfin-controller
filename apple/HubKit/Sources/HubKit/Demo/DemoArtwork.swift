import CoreGraphics
import Foundation
import ImageIO

/// The demo library's pictures (`/v1/img/jf/<id>/<kind>`), drawn here: a
/// colour for each title from its id, so a title page has a backdrop to fade,
/// a poster and a still for each episode, and a download keeps them beside its
/// file as a real one does. Nothing real is read.
enum DemoArtwork {
    static func answer(method: String, path: String, query: String, body: Data?) -> DemoTransport.Answer? {
        guard method == "GET", path.hasPrefix("/v1/img/jf/") else { return nil }
        let parts = path.split(separator: "/").map(String.init)
        guard parts.count >= 5 else { return nil }
        let id = parts[3], kind = parts[4].lowercased()
        // "<series>-e2", or "<series>-s2e1" past the first season.
        let still = id.dropFirst(32).contains("e")
        let size: (width: Int, height: Int) = kind == "backdrop" ? (1280, 720) : (still ? (640, 360) : (400, 600))
        let data = picture(id: id, width: size.width, height: size.height)
        return data.isEmpty ? nil : DemoTransport.Answer(200, data: data, type: "image/jpeg")
    }

    /// A gradient from the title's colour to near black, a light band across
    /// it and a ring, so the fade and the lighting of a card show.
    static func picture(id: String, width: Int, height: Int) -> Data {
        // The series' colour, a little different for each of its episodes.
        let title = String(id.prefix(32))
        let suffix = id.dropFirst(32)
        let season = Double(suffix.hasPrefix("-s") ? Int(suffix.dropFirst(2).prefix { $0.isNumber }) ?? 1 : 1)
        let episode = Double(suffix.split(separator: "e").last.flatMap { Int($0) } ?? 0) + (season - 1) * 4
        var hash: UInt64 = 0xcbf2_9ce4_8422_2325
        for byte in title.utf8 { hash = (hash ^ UInt64(byte)) &* 0x100_0000_01b3 }
        let hue = (Double(hash % 360) / 360 + episode * 0.025).truncatingRemainder(dividingBy: 1)
        let space = CGColorSpaceCreateDeviceRGB()
        guard let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                                      space: space, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return Data() }
        let top = color(hue: hue, saturation: 0.5, brightness: 0.78)
        let bottom = color(hue: (hue + 0.1).truncatingRemainder(dividingBy: 1), saturation: 0.75, brightness: 0.22)
        if let gradient = CGGradient(colorsSpace: space, colors: [top, bottom] as CFArray, locations: [0, 1]) {
            context.drawLinearGradient(gradient, start: CGPoint(x: 0, y: height), end: .zero, options: [])
        }
        let side = Double(min(width, height))
        context.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 0.12))
        context.fillEllipse(in: CGRect(x: Double(width) * 0.62 - side * 0.45, y: Double(height) * 0.55 - side * 0.45,
                                       width: side * 0.9, height: side * 0.9))
        context.setStrokeColor(CGColor(red: 1, green: 1, blue: 1, alpha: 0.22))
        context.setLineWidth(max(2, side / 90))
        context.strokeEllipse(in: CGRect(x: Double(width) * 0.62 - side * 0.28, y: Double(height) * 0.55 - side * 0.28,
                                         width: side * 0.56, height: side * 0.56))
        guard let image = context.makeImage() else { return Data() }
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(data, "public.jpeg" as CFString, 1, nil) else { return Data() }
        CGImageDestinationAddImage(destination, image, nil)
        CGImageDestinationFinalize(destination)
        return data as Data
    }

    private static func color(hue: Double, saturation: Double, brightness: Double) -> CGColor {
        let sector = hue * 6, index = Int(sector) % 6, fraction = sector - Double(Int(sector))
        let p = brightness * (1 - saturation), q = brightness * (1 - saturation * fraction)
        let t = brightness * (1 - saturation * (1 - fraction))
        let (red, green, blue) = switch index {
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
