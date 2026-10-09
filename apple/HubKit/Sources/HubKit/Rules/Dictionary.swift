import Foundation
import SQLite3

// The dictionary card (#62), the same on the Pocket and the iPad and iPhone:
// Open English WordNet 2025, offline, and the Pocket's lookup rules so a word
// or a phrase finds the same entry on both. The Pocket's `DictionaryTerms`
// (ReadAlongSelection.kt), `PhraseLookup` and `OfflineEnglishDictionary`.

public struct DictionaryDefinition: Equatable, Hashable, Sendable {
    public let partOfSpeech: String
    public let text: String

    public init(partOfSpeech: String, text: String) {
        self.partOfSpeech = partOfSpeech
        self.text = text
    }
}

/// The dictionary's answer: `headword` is the term it has an entry for, empty
/// with no `definitions` when it has none of the terms asked.
public struct DictionaryEntry: Equatable, Sendable {
    public let requested: String
    public let headword: String
    public let definitions: [DictionaryDefinition]

    public init(requested: String, headword: String, definitions: [DictionaryDefinition]) {
        self.requested = requested
        self.headword = headword
        self.definitions = definitions
    }
}

/// A word's base forms to look up, the word as written first: "stories" is
/// also "story", "running" "runn" and "run", "walked" "walk" and "walke".
public enum DictionaryTerms {
    public static func candidates(_ raw: String) -> [String] {
        var chars = Array(raw.precomposedStringWithCanonicalMapping.lowercased())
        func kept(_ c: Character) -> Bool { c.isLetter || c == "'" || c == "\u{2019}" }
        while let first = chars.first, !kept(first) { chars.removeFirst() }
        while let last = chars.last, !kept(last) { chars.removeLast() }
        let word = String(chars).replacingOccurrences(of: "\u{2019}", with: "'")
        guard word.contains(where: \.isLetter), word.utf16.count <= 80 else { return [] }
        var out = [word]
        func dropLast(_ text: String, _ count: Int) -> String { String(text.dropLast(count)) }
        // Lengths and the doubled letter in UTF-16 units, as the Pocket's Kotlin counts them.
        func doubled(_ root: String) -> Bool {
            let units = Array(root.utf16)
            return units.count >= 2 && units[units.count - 1] == units[units.count - 2]
        }
        let length = word.utf16.count
        if word.hasSuffix("ies") && length > 4 {
            out.append(dropLast(word, 3) + "y")
        } else if word.hasSuffix("ing") && length > 5 {
            let root = dropLast(word, 3)
            out.append(root)
            out.append(doubled(root) ? dropLast(root, 1) : root + "e")
        } else if word.hasSuffix("ed") && length > 4 {
            let root = dropLast(word, 2)
            out.append(root)
            if doubled(root) { out.append(dropLast(root, 1)) }
            out.append(dropLast(word, 1))
        } else if word.hasSuffix("es") && length > 4 {
            out.append(dropLast(word, 2))
            out.append(dropLast(word, 1))
        } else if word.hasSuffix("s") && length > 3 {
            out.append(dropLast(word, 1))
        }
        var seen = Set<String>()
        return out.filter { seen.insert($0).inserted }
    }
}

/// What a selection is looked up as (#62). One word by its base forms
/// (`DictionaryTerms`). A phrase ("take off", "in spite of", "pulled off") is
/// tried first as one entry, the first word in its base forms and the rest as
/// written; when the dictionary has no such entry the answer says so and shows
/// the first word that has one, instead of quietly showing a single word the
/// person did not ask about.
public enum PhraseLookup {
    public struct Answer: Equatable, Sendable {
        /// What was selected.
        public let requested: String
        /// The dictionary's answer, nil when it has none.
        public let entry: DictionaryEntry?
        /// The word or phrase the definitions are of.
        public let shown: String
        /// The phrase the dictionary has no entry for: "No entry for “…”."
        public let missed: String?
        public let isPhrase: Bool

        public var found: Bool { !(entry?.definitions.isEmpty ?? true) }
        /// A phrase the dictionary knows, or any single word: the card opens at once.
        public var opensCard: Bool { !isPhrase || (found && missed == nil) }
        /// The line under the heading when what is shown is not what was selected.
        public var note: String? {
            missed.map { "No entry for \u{201C}\($0)\u{201D}." + (found ? " Showing \u{201C}\(shown)\u{201D}." : "") }
        }
    }

    /// A phrase longer than this is not looked up as one entry.
    public static let maxPhraseWords = 6

    /// Made per call, as `FootnoteText`'s are: a stored NSRegularExpression is not Sendable.
    private static var word: NSRegularExpression {
        try! NSRegularExpression(pattern: "[\\p{L}\\p{M}\\p{N}][\\p{L}\\p{M}\\p{N}'\u{2019}\\-]*")
    }

    /// The words of a selection, without the punctuation round them.
    public static func words(_ selection: String) -> [String] {
        let range = NSRange(selection.startIndex..., in: selection)
        return word.matches(in: selection, range: range).compactMap { match -> String? in
            guard let found = Range(match.range, in: selection) else { return nil }
            let trimmed = String(selection[found]).trimmingCharacters(in: CharacterSet(charactersIn: "'\u{2019}-"))
            return trimmed.isEmpty ? nil : trimmed
        }
    }

    /// The entries to try for the phrase as one: its first word in each base form, then the rest, all lower case.
    public static func phraseTerms(_ words: [String]) -> [String] {
        guard words.count >= 2 else { return [] }
        let rest = words.dropFirst().map { $0.lowercased() }.joined(separator: " ")
        return DictionaryTerms.candidates(words[0]).map { $0 + " " + rest }
    }

    public static func answer(_ selection: String,
                              lookup: (_ requested: String, _ terms: [String]) async -> DictionaryEntry) async -> Answer {
        let words = words(selection)
        let text = words.joined(separator: " ")
        let trimmed = selection.trimmingCharacters(in: .whitespacesAndNewlines)
        if words.isEmpty {
            return Answer(requested: trimmed, entry: nil, shown: trimmed, missed: trimmed.isEmpty ? nil : trimmed, isPhrase: false)
        }
        if words.count == 1 {
            let entry = await lookup(words[0], DictionaryTerms.candidates(words[0]))
            return entry.definitions.isEmpty
                ? Answer(requested: words[0], entry: nil, shown: words[0], missed: words[0], isPhrase: false)
                : Answer(requested: words[0], entry: entry, shown: entry.headword, missed: nil, isPhrase: false)
        }
        let whole = await lookup(text, phraseTerms(words))
        if !whole.definitions.isEmpty {
            return Answer(requested: text, entry: whole, shown: whole.headword, missed: nil, isPhrase: true)
        }
        for word in words {
            let entry = await lookup(word, DictionaryTerms.candidates(word))
            if !entry.definitions.isEmpty {
                return Answer(requested: text, entry: entry, shown: entry.headword, missed: text, isPhrase: true)
            }
        }
        return Answer(requested: text, entry: nil, shown: text, missed: text, isPhrase: true)
    }
}

/// Open English WordNet 2025 as SQLite (the Pocket's own file, bundled
/// read-only): up to six senses of the first term it has, each with its part
/// of speech. Read with SQLite's C API, opened immutable, so it needs no
/// writable copy; call it off the main thread.
public final class OfflineEnglishDictionary: @unchecked Sendable {
    /// The index the Pocket's lookups were built for.
    public static let indexRevision = "2"
    public static let senses = 6

    private let url: URL
    private let lock = NSLock()
    private var database: OpaquePointer?
    private var statement: OpaquePointer?
    private var opened = false
    private var cache: [String: DictionaryEntry] = [:]
    private var order: [String] = []

    public init(url: URL) {
        self.url = url
    }

    deinit {
        sqlite3_finalize(statement)
        sqlite3_close(database)
    }

    /// Whether the file is the dictionary the lookups were made for.
    public var isReady: Bool { lock.withLock { open() } }

    public func lookup(_ word: String) -> DictionaryEntry { lookupTerms(word, DictionaryTerms.candidates(word)) }

    /// The first of `terms` the dictionary has an entry for (a word's base
    /// forms, or a phrase's); `requested` is what was selected.
    public func lookupTerms(_ requested: String, _ terms: [String]) -> DictionaryEntry {
        lock.withLock {
            let key = requested + "\u{0}" + terms.joined(separator: "|")
            if let hit = cache[key] { return hit }
            var answer = DictionaryEntry(requested: requested, headword: "", definitions: [])
            if open() {
                for term in terms {
                    let found = definitions(term)
                    if !found.isEmpty {
                        answer = DictionaryEntry(requested: requested, headword: term, definitions: found)
                        break
                    }
                }
            }
            remember(key, answer)
            return answer
        }
    }

    private func remember(_ key: String, _ entry: DictionaryEntry) {
        cache[key] = entry
        order.append(key)
        if order.count > 128 { cache[order.removeFirst()] = nil }
    }

    private func open() -> Bool {
        if opened { return statement != nil }
        opened = true
        var components = URLComponents()
        components.scheme = "file"
        components.path = url.path
        components.queryItems = [URLQueryItem(name: "immutable", value: "1"), URLQueryItem(name: "mode", value: "ro")]
        guard let uri = components.string,
              sqlite3_open_v2(uri, &database, SQLITE_OPEN_READONLY | SQLITE_OPEN_URI | SQLITE_OPEN_NOMUTEX, nil) == SQLITE_OK,
              revision() == Self.indexRevision,
              sqlite3_prepare_v2(database, "SELECT part_of_speech, definition FROM definitions WHERE lemma = ? ORDER BY rank LIMIT 6",
                                 -1, &statement, nil) == SQLITE_OK
        else {
            sqlite3_close(database)
            database = nil
            statement = nil
            return false
        }
        return true
    }

    private func revision() -> String? {
        var query: OpaquePointer?
        defer { sqlite3_finalize(query) }
        guard sqlite3_prepare_v2(database, "SELECT value FROM metadata WHERE key = 'index_revision'", -1, &query, nil) == SQLITE_OK,
              sqlite3_step(query) == SQLITE_ROW, let text = sqlite3_column_text(query, 0) else { return nil }
        return String(cString: text)
    }

    private func definitions(_ term: String) -> [DictionaryDefinition] {
        guard let statement else { return [] }
        defer {
            sqlite3_reset(statement)
            sqlite3_clear_bindings(statement)
        }
        let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
        guard sqlite3_bind_text(statement, 1, term, -1, transient) == SQLITE_OK else { return [] }
        var out: [DictionaryDefinition] = []
        while sqlite3_step(statement) == SQLITE_ROW {
            let part = sqlite3_column_text(statement, 0).map { String(cString: $0) } ?? ""
            let text = sqlite3_column_text(statement, 1).map { String(cString: $0) } ?? ""
            out.append(DictionaryDefinition(partOfSpeech: part, text: text))
        }
        return out
    }
}
