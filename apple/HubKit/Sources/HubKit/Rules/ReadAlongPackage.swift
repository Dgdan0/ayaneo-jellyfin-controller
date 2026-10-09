import Foundation

/// An EPUB 3 edition's media overlays as read along's timeline (#16, #19):
/// the package's reading order, each document's SMIL, and in it each
/// sentence's words (`text`) and where it is spoken (`audio`). Only
/// resources inside the package are accepted, and nothing is written.
/// Android's `ReadAlongPackage`, held to its tests.
public enum ReadAlongPackage {
    /// The most of one XML document read.
    static let xmlLimit = 4 * 1_024 * 1_024
    /// The most sentences a narration may hold.
    static let segmentLimit = 200_000
    /// The most words an edition read word by word may hold (#66): a 50-hour book has about half a million.
    static let wordLimit = 2_000_000

    /// The narration's timeline from the edition at `url`.
    public static func read(_ url: URL, requireAudio: Bool = true) throws(ReadAlongError) -> ReadAlongTimeline {
        let data: Data
        do {
            data = try Data(contentsOf: url, options: .mappedIfSafe)
        } catch {
            throw ReadAlongError("The read-along edition could not be read")
        }
        return try read(data, requireAudio: requireAudio)
    }

    /// The narration's timeline. `requireAudio` false reads the edition
    /// without its audio (#19: the hub's slim edition), whose narration
    /// streams from the audiobook's tracks: its SMIL still names the audio
    /// files, which are not in the archive.
    public static func read(_ data: Data, requireAudio: Bool = true) throws(ReadAlongError) -> ReadAlongTimeline {
        let zip = try ZipArchive(data)
        let container = try xml(zip, "META-INF/container.xml")
        guard let rootfile = container.named("rootfile").first?.attributes["full-path"] else {
            throw ReadAlongError("No EPUB package")
        }
        let packagePath = try resolve("", rootfile).path
        let opf = try xml(zip, packagePath)
        var manifest: [String: XMLElements.Element] = [:]
        for item in opf.named("item") { manifest[item.attributes["id"] ?? ""] = item }
        var segments: [ReadAlongSegment] = []
        // Word by word (#66): each par's sentence, from the `<seq epub:textref="doc#sentence">` round it.
        var sentenceOf: [String] = []
        var nested = false
        for ref in opf.named("itemref") {
            guard let chapter = manifest[ref.attributes["idref"] ?? ""] else { continue }
            let overlayId = chapter.attributes["media-overlay"] ?? ""
            guard !overlayId.trimmingCharacters(in: .whitespaces).isEmpty else { continue }
            guard let overlay = manifest[overlayId] else { throw ReadAlongError("Missing media overlay") }
            let smilPath = try resolve(packagePath, overlay.attributes["href"] ?? "").path
            let smil = try xml(zip, smilPath)
            for par in smil.named("par") {
                guard let text = smil.child(of: par, named: "text"), let audio = smil.child(of: par, named: "audio") else { continue }
                let words = try resolve(smilPath, text.attributes["src"] ?? "")
                guard !words.fragment.trimmingCharacters(in: .whitespaces).isEmpty else {
                    throw ReadAlongError("Narrated text needs a fragment")
                }
                let audioHref = try resolve(smilPath, audio.attributes["src"] ?? "").path
                guard zip.contains(words.path), !requireAudio || zip.contains(audioHref) else {
                    throw ReadAlongError("Missing narration resource")
                }
                let beginText = audio.attributes["clipBegin"] ?? ""
                let begin = try clock(beginText.trimmingCharacters(in: .whitespaces).isEmpty ? "0s" : beginText)
                let end = try clock(audio.attributes["clipEnd"] ?? "")
                // Word alignment can emit a boundary of no length for a word
                // it did not match, and an older aligner a clip that ends
                // before it begins (the hub mends those it serves now, but an
                // edition kept from before still has them). Neither has audio
                // to light: that one sentence is skipped and the rest of the
                // edition still plays.
                if end <= begin { continue }
                let sentence = try Self.sentence(of: par, in: smil, smilPath: smilPath, href: words.path)
                if sentence != nil { nested = true }
                // In the key's form, as every page href is compared (#61).
                segments.append(ReadAlongSegment(textHref: BookHref.normalized(words.path), fragment: words.fragment,
                                                 audioHref: audioHref,
                                                 beginMs: begin, endMs: end))
                sentenceOf.append(sentence ?? words.fragment)
                guard segments.count <= (nested ? wordLimit : segmentLimit) else {
                    throw ReadAlongError("Narration timeline is too large")
                }
            }
        }
        guard !segments.isEmpty else { throw ReadAlongError("This edition has no aligned narration") }
        // Word by word (#66): stretches that run forward, each sentence its words.
        if nested {
            let built = wordStretches(segments, sentenceOf: sentenceOf)
            return ReadAlongTimeline(tracks: built.tracks, words: built.words)
        }
        // A stretch is one audio file read on: a new file, or a clip earlier
        // than the one before, starts the next.
        var tracks: [ReadAlongTrack] = []
        var group: [ReadAlongSegment] = []
        for segment in segments {
            if let previous = group.last, previous.audioHref != segment.audioHref || segment.beginMs < previous.endMs {
                tracks.append(ReadAlongTrack(audioHref: previous.audioHref, segments: group))
                group = []
            }
            group.append(segment)
        }
        if let first = group.first { tracks.append(ReadAlongTrack(audioHref: first.audioHref, segments: group)) }
        return ReadAlongTimeline(tracks: tracks)
    }

    /// How far a word edition's sentence may begin before the one ahead of
    /// it has ended and still be heard in the same stretch (#66; the Pocket's
    /// `WORD_OVERLAP_MS`). Voices overlap in a dramatization and the aligner's
    /// word ends run on a little (The Final Empire, Dramatized: 227 of 258 such
    /// places by a second or less, the largest 5.8 s), while a chapter told out
    /// of order goes back an hour. A new stretch would play the overlap twice.
    static let wordOverlapMs: Int64 = 10_000

    /// A word edition's stretches and each sentence's words (#66, the
    /// contract's rule and the Pocket's `stretches`): the pars in the order
    /// the text reads them, one sentence's run of them in one file at a time.
    /// A new stretch where the file changes or a run begins before the
    /// stretch so far has ended, a run with words only when by more than
    /// `wordOverlapMs`. A stretch with words runs forward: each par begins and
    /// ends no earlier than the one before, so the highlight only goes on and
    /// nothing is heard twice. Each sentence is then one segment, from its
    /// first word's beginning to its last word's end; a par the pack could not
    /// cut into words stays the sentence it is.
    static func wordStretches(_ pars: [ReadAlongSegment], sentenceOf: [String])
        -> (tracks: [ReadAlongTrack], words: [String: [ReadAlongWord]]) {
        struct Run {
            let pars: [ReadAlongSegment]
            let sentence: String
            let isWords: Bool
        }
        var runs: [Run] = []
        var at = 0
        while at < pars.count {
            let first = pars[at]
            var until = at + 1
            while until < pars.count, pars[until].audioHref == first.audioHref, pars[until].textHref == first.textHref,
                  sentenceOf[until] == sentenceOf[at] { until += 1 }
            let isWords = (at..<until).contains { sentenceOf[$0] != pars[$0].fragment }
            runs.append(Run(pars: Array(pars[at..<until]), sentence: sentenceOf[at], isWords: isWords))
            at = until
        }
        var tracks: [ReadAlongTrack] = []
        var words: [String: [ReadAlongWord]] = [:]
        var group: [Run] = []
        var groupEnd = Int64.min
        var groupWords = false
        func close() {
            guard let audio = group.first?.pars.first?.audioHref else { return }
            var begin = Int64.min
            var end = Int64.min
            var sentences: [ReadAlongSegment] = []
            for run in group {
                let forwarded = run.pars.map { par -> ReadAlongSegment in
                    guard groupWords else { return par }
                    begin = max(begin, par.beginMs)
                    end = max(end, par.endMs)
                    return ReadAlongSegment(textHref: par.textHref, fragment: par.fragment, audioHref: par.audioHref,
                                            beginMs: begin, endMs: end)
                }
                guard run.isWords, let first = forwarded.first, let last = forwarded.last else {
                    sentences += forwarded
                    continue
                }
                words[ReadAlongTimeline.wordKey(first.textHref, run.sentence, audio: first.audioHref), default: []]
                    += forwarded.map { ReadAlongWord(fragment: $0.fragment, beginMs: $0.beginMs, endMs: $0.endMs) }
                sentences.append(ReadAlongSegment(textHref: first.textHref, fragment: run.sentence, audioHref: first.audioHref,
                                                  beginMs: first.beginMs, endMs: last.endMs))
            }
            tracks.append(ReadAlongTrack(audioHref: audio, segments: sentences))
            group = []
            groupEnd = .min
            groupWords = false
        }
        for run in runs {
            let runBegin = run.pars.map(\.beginMs).min() ?? 0
            let leeway = run.isWords ? wordOverlapMs : 0
            if let last = group.last, last.pars[0].audioHref != run.pars[0].audioHref || runBegin < groupEnd - leeway { close() }
            group.append(run)
            groupEnd = max(groupEnd, run.pars.map(\.endMs).max() ?? 0)
            groupWords = groupWords || run.isWords
        }
        close()
        return (tracks, words)
    }

    /// The sentence a word's par belongs to (#66): the fragment of the
    /// nearest `<seq epub:textref="doc#sentence">` round it, when that names
    /// the word's own document; nil for a par that is a sentence itself.
    static func sentence(of par: XMLElements.Element, in smil: XMLElements, smilPath: String,
                         href: String) throws(ReadAlongError) -> String? {
        var parent = par.parent
        while let index = parent {
            let element = smil.elements[index]
            if element.name == "seq", let reference = element.attributes["epub:textref"] ?? element.attributes["textref"],
               reference.contains("#") {
                let resolved = try resolve(smilPath, reference)
                return resolved.path == href && !resolved.fragment.isEmpty ? resolved.fragment : nil
            }
            parent = element.parent
        }
        return nil
    }

    /// A SMIL clock value in milliseconds: "01:02:03.250", "npt=1.25s",
    /// "1250ms", "2min", "1h", "3.5s" or "3.5". A negative, unfinished or
    /// absurd time (a year or more) is refused.
    static func clock(_ raw: String) throws(ReadAlongError) -> Int64 {
        var text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.hasPrefix("npt=") { text.removeFirst(4) }
        func number(_ value: Substring) throws(ReadAlongError) -> Double {
            guard let parsed = Double(value.trimmingCharacters(in: .whitespaces)) else {
                throw ReadAlongError("Invalid narration time")
            }
            return parsed
        }
        let seconds: Double
        if text.contains(":") {
            var parts: [Double] = []
            for part in text.split(separator: ":", omittingEmptySubsequences: false) { parts.append(try number(part)) }
            guard (2...3).contains(parts.count), parts.allSatisfy({ $0 >= 0 && $0.isFinite }) else {
                throw ReadAlongError("Invalid narration time")
            }
            seconds = parts.reduce(0) { $0 * 60 + $1 }
        } else if text.hasSuffix("ms") {
            seconds = try number(text.dropLast(2)) / 1_000
        } else if text.hasSuffix("min") {
            seconds = try number(text.dropLast(3)) * 60
        } else if text.hasSuffix("h") {
            seconds = try number(text.dropLast(1)) * 3_600
        } else {
            seconds = try number(text.hasSuffix("s") ? text.dropLast(1) : Substring(text))
        }
        guard seconds.isFinite, seconds >= 0, seconds < 365.0 * 24 * 3_600 else { throw ReadAlongError("Invalid narration time") }
        return Int64(seconds * 1_000)
    }

    /// `relative` from the document at `base`: a path inside the package and
    /// its fragment, both percent-decoded. Anything that leaves the package is
    /// refused: a scheme, a host, a query, a path from the root, a `..` past
    /// the top, a backslash.
    static func resolve(_ base: String, _ relative: String) throws(ReadAlongError) -> (path: String, fragment: String) {
        let outside = ReadAlongError("External narration resource")
        guard !relative.trimmingCharacters(in: .whitespaces).isEmpty, !relative.contains("\\") else { throw outside }
        var reference = Substring(relative)
        var fragment = ""
        if let hash = reference.firstIndex(of: "#") {
            fragment = String(reference[reference.index(after: hash)...])
            reference = reference[..<hash]
        }
        // A scheme ("https:", "file:") is a colon before any slash; a host is "//".
        let head = reference.prefix { $0 != "/" }
        guard !head.contains(":"), !reference.contains("?"), !reference.hasPrefix("/") else { throw outside }
        var segments: [Substring]
        if reference.isEmpty {
            // Only a fragment: the document itself.
            segments = base.split(separator: "/", omittingEmptySubsequences: false)
        } else {
            segments = Array(base.split(separator: "/", omittingEmptySubsequences: false).dropLast())
            segments += reference.split(separator: "/", omittingEmptySubsequences: false)
        }
        var path: [Substring] = []
        for (index, segment) in segments.enumerated() {
            switch segment {
            case ".":
                // A trailing "." names its folder, which is no document.
                if index == segments.count - 1 { path.append("") }
            case "..":
                guard !path.isEmpty else { throw outside }
                path.removeLast()
                if index == segments.count - 1 { path.append("") }
            default:
                path.append(segment)
            }
        }
        guard let decoded = path.joined(separator: "/").removingPercentEncoding,
              let decodedFragment = fragment.removingPercentEncoding,
              !decoded.trimmingCharacters(in: .whitespaces).isEmpty, !decoded.hasPrefix("/"), !decoded.contains("\\"),
              !decoded.split(separator: "/", omittingEmptySubsequences: false).contains(where: { $0 == "." || $0 == ".." }) else {
            throw outside
        }
        return (decoded, decodedFragment)
    }

    /// An XML document of the package, parsed. One that declares a document
    /// type or an entity is refused, as an external entity could reach outside it.
    static func xml(_ zip: ZipArchive, _ path: String) throws(ReadAlongError) -> XMLElements {
        let data = try zip.read(path, limit: xmlLimit)
        let text = String(decoding: data, as: UTF8.self).lowercased()
        guard !text.contains("<!doctype"), !text.contains("<!entity") else {
            throw ReadAlongError("External XML entities are forbidden")
        }
        return try XMLElements(data)
    }
}

/// An XML document's elements in document order, each with its attributes,
/// its parent and its depth: enough to find a package's items and a SMIL's
/// pars, and what is directly inside a par.
final class XMLElements: NSObject, XMLParserDelegate {
    struct Element {
        /// The element's name without its namespace prefix.
        let name: String
        let attributes: [String: String]
        /// Its place in document order, and its parent's.
        let index: Int
        let parent: Int?
        let depth: Int
    }

    private(set) var elements: [Element] = []
    private var open: [Int] = []

    init(_ data: Data) throws(ReadAlongError) {
        super.init()
        let parser = XMLParser(data: data)
        parser.shouldProcessNamespaces = true
        parser.shouldResolveExternalEntities = false
        parser.delegate = self
        guard parser.parse() else { throw ReadAlongError("An EPUB document is not valid XML") }
    }

    /// Every element of `name`, in document order.
    func named(_ name: String) -> [Element] { elements.filter { $0.name == name } }

    /// The first element of `name` directly inside `element`: what follows it
    /// in document order, until its own elements end.
    func child(of element: Element, named name: String) -> Element? {
        var index = element.index + 1
        while index < elements.count, elements[index].depth > element.depth {
            if elements[index].parent == element.index && elements[index].name == name { return elements[index] }
            index += 1
        }
        return nil
    }

    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?, qualifiedName: String?,
                attributes: [String: String] = [:]) {
        elements.append(Element(name: elementName, attributes: attributes, index: elements.count, parent: open.last,
                                depth: open.count))
        open.append(elements.count - 1)
    }

    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName: String?) {
        _ = open.popLast()
    }
}
