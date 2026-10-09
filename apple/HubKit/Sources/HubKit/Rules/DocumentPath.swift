import Foundation

/// The one spelling of a content document's path (#61, #62; the Pocket's
/// `DocumentPath`), what a highlight's `document` is written in so every app
/// finds it: decoded, in Unicode NFC, with no fragment and no leading slash.
/// `of` makes it from an encoded spelling (Readium's, a locator's), `name`
/// from a path already decoded (a zip entry). Decoding is lenient, as the
/// Pocket's: a `%` that is not an escape stays, and a run of escapes that is
/// not UTF-8 is left as it was written, so no spelling is lost to a
/// replacement character.
public enum DocumentPath {
    /// The document an encoded `href` names: decoded, NFC, no fragment, no leading slash.
    public static func of(_ href: String) -> String {
        let path = href.split(separator: "#", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? ""
        return name(decode(path))
    }

    /// A path that is already decoded, in the same form: NFC, no leading slash.
    public static func name(_ path: String) -> String {
        String(path.drop { $0 == "/" }).precomposedStringWithCanonicalMapping
    }

    /// Percent-decoded as UTF-8, leniently.
    public static func decode(_ encoded: String) -> String {
        guard encoded.contains("%") else { return encoded }
        let units = Array(encoded.utf8)
        var out = ""
        var i = 0
        func escape(_ at: Int) -> UInt8? {
            guard at + 2 < units.count, units[at] == UInt8(ascii: "%"),
                  let high = hex(units[at + 1]), let low = hex(units[at + 2]) else { return nil }
            return high << 4 | low
        }
        var plain: [UInt8] = []
        func flush() {
            out += String(decoding: plain, as: UTF8.self)
            plain.removeAll()
        }
        while i < units.count {
            guard escape(i) != nil else {
                plain.append(units[i])
                i += 1
                continue
            }
            flush()
            let start = i
            var raw: [UInt8] = []
            while i < units.count, let byte = escape(i) {
                raw.append(byte)
                i += 3
            }
            var at = 0
            while at < raw.count {
                let lead = raw[at]
                let length = lead < 0x80 ? 1 : (0xC2...0xDF).contains(lead) ? 2 : (0xE0...0xEF).contains(lead) ? 3
                    : (0xF0...0xF4).contains(lead) ? 4 : 0
                let whole = length > 0 && at + length <= raw.count && (1..<max(length, 1)).allSatisfy { raw[at + $0] & 0xC0 == 0x80 }
                if whole, let piece = String(validating: raw[at..<(at + length)], as: UTF8.self) {
                    out += piece
                    at += length
                } else {
                    // Not UTF-8: the escape as it was written.
                    let from = start + at * 3
                    out += String(decoding: units[from..<(from + 3)], as: UTF8.self)
                    at += 1
                }
            }
        }
        flush()
        return out
    }

    private static func hex(_ unit: UInt8) -> UInt8? {
        switch unit {
        case UInt8(ascii: "0")...UInt8(ascii: "9"): unit - UInt8(ascii: "0")
        case UInt8(ascii: "a")...UInt8(ascii: "f"): unit - UInt8(ascii: "a") + 10
        case UInt8(ascii: "A")...UInt8(ascii: "F"): unit - UInt8(ascii: "A") + 10
        default: nil
        }
    }
}
