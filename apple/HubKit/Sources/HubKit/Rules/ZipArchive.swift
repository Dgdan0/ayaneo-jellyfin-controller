import Compression
import Foundation

/// A ZIP archive held in memory, an EPUB: its entries by name, each read out
/// whole, stored or deflated, and checked against its CRC. Read along needs
/// only an edition's package, contents and SMIL, which are small, so an entry
/// is read only up to a limit. No ZIP64, no encryption: an EPUB has neither.
struct ZipArchive {
    struct Entry {
        let name: String
        let method: UInt16
        let flags: UInt16
        let compressedSize: Int
        let size: Int
        let crc: UInt32
        let localOffset: Int
    }

    private let bytes: [UInt8]
    let entries: [String: Entry]

    init(_ data: Data) throws(ReadAlongError) {
        bytes = [UInt8](data)
        entries = try Self.directory(bytes)
    }

    func contains(_ name: String) -> Bool { entries[name] != nil }

    /// The entry's bytes, at most `limit`; an empty entry is refused.
    func read(_ name: String, limit: Int) throws(ReadAlongError) -> Data {
        guard let entry = entries[name] else { throw ReadAlongError("Missing EPUB document") }
        guard entry.size > 0, entry.size <= limit else { throw ReadAlongError("An EPUB document is empty or too large") }
        guard entry.flags & 0x1 == 0 else { throw ReadAlongError("An EPUB document is encrypted") }
        let local = entry.localOffset
        guard local >= 0, local + 30 <= bytes.count, Self.u32(bytes, local) == 0x0403_4B50 else {
            throw ReadAlongError("The EPUB archive is damaged")
        }
        let start = local + 30 + Int(Self.u16(bytes, local + 26)) + Int(Self.u16(bytes, local + 28))
        guard start <= bytes.count, entry.compressedSize <= bytes.count - start else {
            throw ReadAlongError("The EPUB archive is damaged")
        }
        let packed = bytes[start..<(start + entry.compressedSize)]
        let out: [UInt8]
        switch entry.method {
        case 0:
            guard entry.compressedSize == entry.size else { throw ReadAlongError("The EPUB archive is damaged") }
            out = Array(packed)
        case 8:
            out = try Self.inflate(Array(packed), size: entry.size)
        default:
            throw ReadAlongError("An EPUB document is compressed in a way this app cannot read")
        }
        guard Self.crc32(out) == entry.crc else { throw ReadAlongError("The EPUB archive is damaged") }
        return Data(out)
    }

    // MARK: The directory

    private static func directory(_ bytes: [UInt8]) throws(ReadAlongError) -> [String: Entry] {
        // The end record is in the last 22 bytes and a comment of at most 64 KB.
        guard bytes.count >= 22 else { throw ReadAlongError("Not an EPUB archive") }
        var end = -1
        var at = bytes.count - 22
        let lowest = max(0, bytes.count - 22 - 0xFFFF)
        while at >= lowest {
            if u32(bytes, at) == 0x0605_4B50 {
                end = at
                break
            }
            at -= 1
        }
        guard end >= 0 else { throw ReadAlongError("Not an EPUB archive") }
        let count = Int(u16(bytes, end + 10))
        let size = Int(u32(bytes, end + 12))
        let offset = Int(u32(bytes, end + 16))
        guard count != 0xFFFF, size != 0xFFFF_FFFF, offset != 0xFFFF_FFFF, offset >= 0, size >= 0,
              offset <= end, size <= end - offset else {
            throw ReadAlongError("The EPUB archive is damaged")
        }
        var entries: [String: Entry] = [:]
        var cursor = offset
        for _ in 0..<count {
            guard cursor + 46 <= end, u32(bytes, cursor) == 0x0201_4B50 else { throw ReadAlongError("The EPUB archive is damaged") }
            let nameLength = Int(u16(bytes, cursor + 28))
            let extraLength = Int(u16(bytes, cursor + 30))
            let commentLength = Int(u16(bytes, cursor + 32))
            let next = cursor + 46 + nameLength + extraLength + commentLength
            guard next <= end else { throw ReadAlongError("The EPUB archive is damaged") }
            let name = String(decoding: bytes[(cursor + 46)..<(cursor + 46 + nameLength)], as: UTF8.self)
            let entry = Entry(name: name, method: u16(bytes, cursor + 10), flags: u16(bytes, cursor + 8),
                              compressedSize: Int(u32(bytes, cursor + 20)), size: Int(u32(bytes, cursor + 24)),
                              crc: u32(bytes, cursor + 16), localOffset: Int(u32(bytes, cursor + 42)))
            // A directory is not a document; the first of two entries of one name stands.
            if !name.hasSuffix("/") && entries[name] == nil { entries[name] = entry }
            cursor = next
        }
        return entries
    }

    // MARK: Reading

    /// Raw DEFLATE (RFC 1951), which is what Apple's zlib codec reads.
    private static func inflate(_ packed: [UInt8], size: Int) throws(ReadAlongError) -> [UInt8] {
        guard !packed.isEmpty else { throw ReadAlongError("The EPUB archive is damaged") }
        // One byte to spare, so a document longer than the directory says shows as one.
        var out = [UInt8](repeating: 0, count: size + 1)
        let written = out.withUnsafeMutableBufferPointer { target in
            packed.withUnsafeBufferPointer { source in
                compression_decode_buffer(target.baseAddress!, target.count, source.baseAddress!, source.count, nil,
                                          COMPRESSION_ZLIB)
            }
        }
        guard written == size else { throw ReadAlongError("The EPUB archive is damaged") }
        out.removeLast()
        return out
    }

    static func u16(_ bytes: [UInt8], _ at: Int) -> UInt16 {
        UInt16(bytes[at]) | UInt16(bytes[at + 1]) << 8
    }

    static func u32(_ bytes: [UInt8], _ at: Int) -> UInt32 {
        UInt32(bytes[at]) | UInt32(bytes[at + 1]) << 8 | UInt32(bytes[at + 2]) << 16 | UInt32(bytes[at + 3]) << 24
    }

    private static let table: [UInt32] = (0..<256).map { index in
        var value = UInt32(index)
        for _ in 0..<8 { value = value & 1 != 0 ? 0xEDB8_8320 ^ (value >> 1) : value >> 1 }
        return value
    }

    static func crc32(_ bytes: [UInt8]) -> UInt32 {
        var crc: UInt32 = 0xFFFF_FFFF
        for byte in bytes { crc = table[Int((crc ^ UInt32(byte)) & 0xFF)] ^ (crc >> 8) }
        return crc ^ 0xFFFF_FFFF
    }
}
