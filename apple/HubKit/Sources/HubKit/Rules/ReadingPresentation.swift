import Foundation

// What a book page, a library, Books Discover, a request and a transfer show
// and do (#25). Ports of Android's `ReadingWorkPresentation`,
// `ReadingEntryChoice`, `ReadingFormatMenu`, `ReadingFormatStatus`,
// `ReadingSortFields`, `ReadingDiscoverRows`, `ReadingSearchPresentation`,
// `ReadingAcquisitionState`, `ReadingReleasePickerPolicy` and
// `ReadingTransferSummary`, held to their test cases.

/// The edition a Read button opens, and what it says.
public struct PrimaryRead: Equatable, Sendable, Hashable {
    public let sourceItemId: String
    public let source: String
    public let label: String

    public init(sourceItemId: String, source: String, label: String) {
        self.sourceItemId = sourceItemId
        self.source = source
        self.label = label
    }
}

/// A book page's decisions. Android's `ReadingWorkPresentation`.
public enum ReadingWorkPresentation {
    private static let readableKinds: Set<String> = ["book", "ebook", "comic", "manga"]

    /// Its audiobooks that can be played, one per source and id.
    public static func audiobooks(_ work: ReadingWork) -> [ReadingEdition] {
        distinct(work.editions.filter {
            !blank($0.sourceItemId) && $0.kind == "audiobook" && $0.availability == "available"
        })
    }

    /// The audiobook Listen plays; none on a series.
    public static func primaryListen(_ work: ReadingWork) -> ReadingEdition? {
        work.isSeries ? nil : audiobooks(work).first
    }

    /// Read-along editions whose narration is one of its playable audiobooks.
    public static func readAlongEditions(_ work: ReadingWork) -> [ReadingEdition] {
        guard !work.isSeries else { return [] }
        let audio = audiobooks(work)
        return distinct(work.editions.filter { edition in
            !blank(edition.sourceItemId) && edition.kind == "readaloud" && edition.availability == "available"
                && audio.contains { $0.source == edition.source && $0.sourceItemId == edition.sourceItemId }
        })
    }

    public static func readAlongEdition(_ work: ReadingWork) -> ReadingEdition? { readAlongEditions(work).first }

    /// What Read opens and what it says: the edition being read, else the
    /// first readable one. A Kavita comic opens a chapter from its volumes,
    /// never its series edition, and names the issue ("Continue · Issue 51")
    /// since one page of 4,437 is "0%".
    public static func primaryRead(_ work: ReadingWork) -> PrimaryRead? {
        guard !work.isSeries else { return nil }
        let playable = work.editions.filter {
            !blank($0.sourceItemId) && readableKinds.contains($0.kind) && $0.availability == "available"
        }
        let kavitaComic = ["comic", "manga"].contains(work.kind) && playable.contains { $0.source == "kavita" }
        func readableChapter(_ item: ReadingSectionItem) -> Bool {
            readableKinds.contains(item.kind) && (item.isAvailable || (kavitaComic && !blank(item.sourceItemId)
                && (blank(item.availability) || item.availability.caseInsensitiveCompare("available") == .orderedSame)))
        }
        let continued = work.continueAt.flatMap { point -> ReadingContinue? in
            guard !blank(point.sourceItemId), readableKinds.contains(point.kind) else { return nil }
            let byEdition = !kavitaComic && playable.contains { $0.sourceItemId == point.sourceItemId }
            let byChapter = work.sections.contains { section in
                section.items.contains { $0.sourceItemId == point.sourceItemId && readableChapter($0) }
            }
            return byEdition || byChapter ? point : nil
        }
        let first = playable.first
        let section = work.sections.lazy.flatMap(\.items).first(where: readableChapter)
        let id: String?
        if let continued {
            id = continued.sourceItemId
        } else if kavitaComic {
            id = section?.sourceItemId
        } else {
            guard let fallback = first?.sourceItemId ?? section?.sourceItemId else { return nil }
            id = fallback
        }
        guard let id, !blank(id) else { return nil }
        let source = playable.first(where: { $0.sourceItemId == id }).map(\.source).flatMap { blank($0) ? nil : $0 }
            ?? continued.map(\.source).flatMap { blank($0) ? nil : $0 }
            ?? (section?.sourceItemId == id ? "kavita" : first?.source ?? "")
        let progress = work.progress
        let issue: String? = ["comic", "manga"].contains(work.kind)
            ? work.sections.lazy.flatMap(\.items).first(where: { $0.sourceItemId == id })
                .map { ReadingBookFacts.issueTitle($0, kind: work.kind) }
            : nil
        let label: String
        if progress?.completed == true {
            label = "Read again"
        } else if let issue, (progress?.percentage ?? 0) > 0 {
            label = "Continue · \(issue)"
        } else if let issue {
            label = "Start · \(issue)"
        } else if let progress, progress.percentage > 0 {
            label = "Resume · \(Fmt.readingPercentLabel(progress.percentage))"
        } else {
            label = "Read book"
        }
        return PrimaryRead(sourceItemId: id, source: blank(source) ? "kavita" : source, label: label)
    }

    /// The cover of the book being read in a series, else the series' own.
    public static func continueArtwork(_ work: ReadingWork) -> String {
        guard let point = work.continueAt else { return "" }
        if !point.artwork.isEmpty { return point.artwork }
        let match = work.sections.lazy.flatMap(\.items).first {
            (!blank(point.workId) && $0.workId == point.workId) || (!blank(point.sourceItemId) && $0.sourceItemId == point.sourceItemId)
        }
        let art = match?.artwork ?? ""
        return art.isEmpty ? work.artwork : art
    }

    /// A book of a series has a page of its own; a missing one only a search.
    public static func canOpen(_ item: ReadingSectionItem) -> Bool { item.isAvailable }

    /// Whether a reader can open this kind of publication at all.
    public static func canRead(kind: String, sourceItemId: String) -> Bool {
        !blank(sourceItemId) && readableKinds.contains(kind)
    }

    /// Whether a publication opens in the page reader (a Kavita comic or
    /// manga) rather than the ebook reader (a Storyteller edition, a book or
    /// an ebook): Android's `openPublication`.
    public static func opensPages(_ work: ReadingWork, source: String) -> Bool {
        source != "storyteller" && !["book", "ebook"].contains(work.kind)
    }

    /// The issue or volume a page reader opens for an id: the work's own,
    /// else one made from its Continue, or from the work itself.
    public static func publication(_ work: ReadingWork, sourceItemId: String) -> ReadingSectionItem {
        if let item = work.sections.lazy.flatMap(\.items).first(where: { $0.sourceItemId == sourceItemId }) { return item }
        let point = work.continueAt.flatMap { $0.sourceItemId == sourceItemId ? $0 : nil }
        return ReadingSectionItem(sourceItemId: sourceItemId, workId: work.id, title: point?.title ?? work.title,
                                  number: point?.number ?? "", kind: work.kind,
                                  artwork: point.map(\.artwork).flatMap { $0.isEmpty ? nil : $0 } ?? work.artwork,
                                  authors: work.authors, progress: work.progress)
    }

    private static func distinct(_ editions: [ReadingEdition]) -> [ReadingEdition] {
        var seen = Set<String>()
        return editions.filter { seen.insert($0.source + "\u{0}" + $0.sourceItemId).inserted }
    }

    static func blank(_ text: String) -> Bool { text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
}

/// How a book is opened.
public enum ReadingEntryMode: String, Sendable, CaseIterable {
    case read = "READ"
    case listen = "LISTEN"
    case readAlong = "READ_ALONG"
}

/// The way a book was last opened on this device, kept per hub, profile and book.
public struct ReadingEntryPreference: Equatable, Sendable {
    public var mode: ReadingEntryMode
    /// The narration listened to or read along with.
    public var audioSourceItemId: String

    public init(mode: ReadingEntryMode, audioSourceItemId: String = "") {
        self.mode = mode
        self.audioSourceItemId = audioSourceItemId
    }

    /// "LISTEN|3726292328809367", as it is stored.
    public var encoded: String { mode.rawValue + "|" + audioSourceItemId }

    public static func decode(_ raw: String?) -> ReadingEntryPreference? {
        guard let raw else { return nil }
        let parts = raw.split(separator: "|", maxSplits: 1, omittingEmptySubsequences: false).map(String.init)
        guard let mode = parts.first.flatMap(ReadingEntryMode.init(rawValue:)) else { return nil }
        return ReadingEntryPreference(mode: mode, audioSourceItemId: parts.count > 1 ? parts[1] : "")
    }
}

/// What a book's main button opens. Android's `ReadingEntryChoice`.
public struct ReadingEntryChoice: Equatable, Sendable {
    public let mode: ReadingEntryMode
    public let text: PrimaryRead?
    public let audio: ReadingEdition?
    public let aligned: ReadingEdition?

    public init(mode: ReadingEntryMode, text: PrimaryRead?, audio: ReadingEdition?, aligned: ReadingEdition?) {
        self.mode = mode
        self.text = text
        self.audio = audio
        self.aligned = aligned
    }

    public static func availableModes(_ work: ReadingWork) -> Set<ReadingEntryMode> {
        var modes = Set<ReadingEntryMode>()
        let text = ReadingWorkPresentation.primaryRead(work)
        if text != nil { modes.insert(.read) }
        if ReadingWorkPresentation.primaryListen(work) != nil { modes.insert(.listen) }
        if text != nil && !ReadingWorkPresentation.readAlongEditions(work).isEmpty { modes.insert(.readAlong) }
        return modes
    }

    /// The remembered way while it can still be opened, else text when there
    /// is text, else listening. A narration that is gone falls back without
    /// borrowing another narration's alignment.
    public static func choose(_ work: ReadingWork, preference: ReadingEntryPreference?) -> ReadingEntryChoice? {
        let text = ReadingWorkPresentation.primaryRead(work)
        let audio = ReadingWorkPresentation.audiobooks(work)
        let aligned = text == nil ? [] : ReadingWorkPresentation.readAlongEditions(work)
        let preferredAudio = audio.first { $0.sourceItemId == preference?.audioSourceItemId } ?? audio.first
        let preferredAligned: ReadingEdition?
        if let preference, !preference.audioSourceItemId.isEmpty {
            preferredAligned = aligned.first { $0.sourceItemId == preference.audioSourceItemId }
        } else {
            preferredAligned = aligned.first
        }
        let preferredMode = preference?.mode ?? (text != nil ? .read : .listen)
        let mode: ReadingEntryMode?
        switch preferredMode {
        case .read: mode = text != nil ? .read : preferredAudio != nil ? .listen : nil
        case .listen: mode = preferredAudio != nil ? .listen : text != nil ? .read : nil
        case .readAlong: mode = preferredAligned != nil ? .readAlong : text != nil ? .read : preferredAudio != nil ? .listen : nil
        }
        guard let mode else { return nil }
        return ReadingEntryChoice(mode: mode, text: text, audio: preferredAudio,
                                  aligned: mode == .readAlong ? preferredAligned : nil)
    }
}

/// Change format's choices: each way a book can be opened. Previewing one
/// changes what the button opens; only opening it is remembered.
public struct ReadingFormatMenu: Equatable, Sendable {
    public struct Option: Equatable, Sendable, Identifiable {
        public let choice: ReadingEntryChoice
        public let label: String
        public let detail: String
        public let narration: String

        public init(choice: ReadingEntryChoice, label: String, detail: String, narration: String = "") {
            self.choice = choice
            self.label = label
            self.detail = detail
            self.narration = narration
        }

        public var key: String {
            "\(choice.mode.rawValue):\(choice.aligned?.sourceItemId ?? choice.audio?.sourceItemId ?? choice.text?.sourceItemId ?? "")"
        }

        public var id: String { key }
    }

    public let defaultChoice: ReadingEntryChoice?
    public let options: [Option]
    /// "Ebook ready  ·  Audiobook ready  ·  Read along aligning".
    public let availability: String

    public static func forWork(_ work: ReadingWork, remembered: ReadingEntryPreference? = nil) -> ReadingFormatMenu {
        let defaultChoice = ReadingEntryChoice.choose(work, preference: remembered)
        let audio = ReadingWorkPresentation.audiobooks(work)
        let aligned = ReadingWorkPresentation.readAlongEditions(work)
        var seen = Set<String>()
        let readable = work.editions.filter {
            !ReadingWorkPresentation.blank($0.sourceItemId) && ["book", "ebook", "comic", "manga"].contains($0.kind)
                && $0.availability == "available" && !($0.source == "kavita" && ["comic", "manga"].contains($0.kind))
        }.filter { seen.insert($0.source + "\u{0}" + $0.sourceItemId).inserted }
        let primaryText = ReadingWorkPresentation.primaryRead(work)
        var texts = readable.map {
            PrimaryRead(sourceItemId: $0.sourceItemId, source: $0.source, label: primaryText?.label ?? "Read book")
        }
        if texts.isEmpty, let primaryText { texts = [primaryText] }
        var options: [Option] = []
        for (index, text) in texts.enumerated() {
            let edition = index < readable.count ? readable[index] : nil
            let noun = switch edition?.kind ?? work.kind {
            case "comic": "comic"
            case "manga": "manga"
            default: "ebook"
            }
            let format = edition?.format.uppercased() ?? ""
            options.append(Option(choice: ReadingEntryChoice(mode: .read, text: text, audio: defaultChoice?.audio, aligned: nil),
                                  label: "Read \(noun)", detail: format.isEmpty ? text.source.prefix(1).uppercased() + text.source.dropFirst() : format))
        }
        for (index, edition) in audio.enumerated() {
            let narration = ReadingWorkPresentation.blank(edition.narrator) ? "Narration \(index + 1)" : edition.narrator
            options.append(Option(choice: ReadingEntryChoice(mode: .listen, text: primaryText, audio: edition, aligned: nil),
                                  label: "Listen to audiobook", detail: narration, narration: narration))
        }
        if primaryText != nil {
            for (index, edition) in aligned.enumerated() {
                let owner = audio.first { $0.source == edition.source && $0.sourceItemId == edition.sourceItemId }
                let narration = owner.map { ReadingWorkPresentation.blank($0.narrator) ? "Narration \(index + 1)" : $0.narrator }
                    ?? "Narration \(index + 1)"
                options.append(Option(choice: ReadingEntryChoice(mode: .readAlong, text: primaryText, audio: owner, aligned: edition),
                                      label: "Read along", detail: "\(narration) · Synchronized", narration: narration))
            }
        }
        let aligning = work.editions.contains { $0.kind == "readaloud" && !["available", "missing"].contains($0.availability) }
        var ready: [String] = []
        if !texts.isEmpty {
            let kind = readable.first?.kind ?? work.kind
            ready.append(kind == "comic" ? "Comic ready" : kind == "manga" ? "Manga ready" : "Ebook ready")
        }
        if !audio.isEmpty { ready.append("Audiobook ready") }
        if !aligned.isEmpty {
            ready.append("Read along ready")
        } else if aligning {
            ready.append("Read along aligning")
        }
        return ReadingFormatMenu(defaultChoice: defaultChoice, options: options, availability: ready.joined(separator: "  ·  "))
    }

    /// The option a choice is, when the menu has it.
    public func option(for choice: ReadingEntryChoice?) -> Option? {
        guard let choice else { return nil }
        let key = Option(choice: choice, label: "", detail: "").key
        return options.first { $0.key == key }
    }

    /// What the main button says (Android's `ReadingWorkScreen`): "Continue"
    /// for a book opened before, unless another format is being tried;
    /// otherwise Read's own words ("Resume · 49%", "Continue · Issue 51"),
    /// "Listen" or "Read along", naming the narration once one is chosen.
    public func entryLabel(_ choice: ReadingEntryChoice, remembered: Bool, preview: ReadingEntryChoice?) -> String {
        if remembered && preview == nil { return "Continue" }
        let chosen = option(for: preview)
        switch choice.mode {
        case .read:
            if let label = choice.text?.label, label != "Read book" { return label }
            return chosen?.label ?? "Read"
        case .listen:
            return preview != nil ? "Listen · \(chosen?.detail ?? "")" : "Listen"
        case .readAlong:
            return preview != nil ? "Read along · \(chosen?.narration ?? "")" : "Read along"
        }
    }
}

/// Whether a format of a book can be opened.
public enum FormatReadiness: String, Sendable {
    case ready, missing, pending, unknown

    public var description: String {
        switch self {
        case .ready: "available"
        case .missing: "not available"
        case .pending: "in progress"
        case .unknown: "availability unknown"
        }
    }
}

/// A book's three formats as chips, the missing ones dimmed. Android's `ReadingFormatStatus`.
public struct ReadingFormatStatus: Equatable, Sendable, Identifiable {
    public let kind: String
    public let label: String
    public let readiness: FormatReadiness

    public var id: String { kind }

    public init(kind: String, label: String, readiness: FormatReadiness) {
        self.kind = kind
        self.label = label
        self.readiness = readiness
    }

    private static let formats = [("ebook", "Ebook"), ("audiobook", "Audiobook"), ("readaloud", "Read along")]

    /// Every format unknown: a title not in the library yet.
    public static func unknown() -> [ReadingFormatStatus] {
        formats.map { ReadingFormatStatus(kind: $0.0, label: $0.1, readiness: .unknown) }
    }

    public static func forWork(_ work: ReadingWork) -> [ReadingFormatStatus] {
        guard !work.isSeries else { return [] }
        return formats.map { kind, label in
            let editions = work.editions.filter { kind == "ebook" ? ["ebook", "book"].contains($0.kind) : $0.kind == kind }
            let ready = switch kind {
            case "ebook": editions.contains { $0.availability == "available" && !ReadingWorkPresentation.blank($0.sourceItemId) }
            case "audiobook": !ReadingWorkPresentation.audiobooks(work).isEmpty
            default: ReadingWorkPresentation.primaryRead(work) != nil && !ReadingWorkPresentation.readAlongEditions(work).isEmpty
            }
            let state: FormatReadiness
            if ready {
                state = .ready
            } else if editions.contains(where: { ["queued", "processing", "aligning", "downloading", "downloaded", "tracked"].contains($0.availability) }) {
                state = .pending
            } else if !work.partial.isEmpty || editions.contains(where: { !["missing", "failed"].contains($0.availability) }) {
                state = .unknown
            } else {
                state = .missing
            }
            return ReadingFormatStatus(kind: kind, label: label, readiness: state)
        }
    }
}

/// The sorts a reading library offers: those its server advertises.
public enum ReadingSortFields {
    public static let all: [(id: String, label: String)] = [
        ("title", "Title"), ("series", "Series"), ("author", "Author"), ("added", "Date added"), ("last_read", "Last read"),
    ]

    public static func forLibrary(_ library: ReadingLibrary) -> [(id: String, label: String)] {
        let advertised = Set(library.capabilities.filter { $0.hasPrefix("sort:") }.map { String($0.dropFirst("sort:".count)) })
        if advertised.isEmpty { return all.filter { $0.id == "title" } }
        return all.filter { advertised.contains($0.id) }
    }

    public static func label(_ field: String) -> String {
        all.first { $0.id == field }?.label ?? field.capitalized
    }
}

/// The reading libraries' names by id, as the hub last listed them: a comic
/// run's page names its library ("Comic · My Marvelous Year") and its work
/// carries only the id.
public struct ReadingLibraryNames: Equatable, Sendable {
    private var names: [String: String] = [:]

    public init() {}

    public mutating func remember(_ libraries: [ReadingLibrary]) {
        for library in libraries where !library.id.isEmpty && !ReadingWorkPresentation.blank(library.title) {
            names[library.id] = library.title
        }
    }

    public func name(of libraryId: String) -> String? { names[libraryId] }
}

/// The line under "Your reading libraries" (a tile's own words are the
/// app's `LibraryKind.reading`, beside the media side's).
public enum ReadingLibraryTiles {
    /// "3 libraries from Storyteller and Kavita".
    public static func summary(_ libraries: [ReadingLibrary]) -> String {
        let shelves = libraries.filter { $0.kind != "reading_list" }
        var servers: [String] = []
        for source in shelves.map({ $0.source.lowercased() }) where !servers.contains(source) { servers.append(source) }
        let names = servers.map(ServiceNames.display)
        let count = "\(shelves.count) \(shelves.count == 1 ? "library" : "libraries")"
        switch names.count {
        case 0: return count
        case 1: return count + " from " + names[0]
        default: return count + " from " + names.dropLast().joined(separator: ", ") + " and " + names[names.count - 1]
        }
    }
}

/// The rows Books Discover shows. Under All, BookKeeprr names each kind's rows
/// alike, so a row whose name does not say what it holds says it after a dot
/// ("Trending now · Manga"). A row with nothing in it is left out.
public enum ReadingDiscoverRows {
    public static func shown(_ rows: [ReadingDiscoverRow], filter: String) -> [ReadingDiscoverRow] {
        rows.filter { !$0.items.isEmpty }.map { row in
            guard filter == ReadingType.all, !saysWhatItHolds(row) else { return row }
            var named = row
            named.title = "\(row.title) · \(ReadingType.label(row.contentType))"
            return named
        }
    }

    /// The first row's first title, large, when it has a cover, a name and
    /// something to say (Android's `DiscoverFeaturePolicy.readingFeature`;
    /// on Apple a phone stacks the card rather than leaving it out).
    public static func feature(_ items: [ReadingItem]) -> ReadingItem? {
        guard let first = items.first, !ReadingWorkPresentation.blank(first.title), !first.cover.isEmpty,
              !ReadingWorkPresentation.blank(first.author) || !ReadingWorkPresentation.blank(first.description) else { return nil }
        return first
    }

    /// The row without the title featured above it.
    public static func shelf(_ items: [ReadingItem]) -> [ReadingItem] {
        feature(items) == nil ? items : Array(items.dropFirst())
    }

    private static func saysWhatItHolds(_ row: ReadingDiscoverRow) -> Bool {
        let word = row.contentType == ReadingType.lightNovel ? "novel" : row.contentType
        return word.isEmpty || row.title.range(of: word, options: .caseInsensitive) != nil
    }
}

/// A Books search: close matches first, broader ones on request.
public struct ReadingSearchPresentation: Equatable, Sendable {
    public var close: [ReadingItem]
    public var broader: [ReadingItem]
    public var showBroader: Bool

    public init(close: [ReadingItem] = [], broader: [ReadingItem] = [], showBroader: Bool = false) {
        self.close = close
        self.broader = broader
        self.showBroader = showBroader
    }

    /// With no close match, the broader ones show at once.
    public static func forResults(close: [ReadingItem], broader: [ReadingItem]) -> ReadingSearchPresentation {
        ReadingSearchPresentation(close: close, broader: broader, showBroader: close.isEmpty && !broader.isEmpty)
    }

    public var hasBroaderResults: Bool { !broader.isEmpty }
    public var canToggle: Bool { !close.isEmpty && !broader.isEmpty }
    public var visibleResults: [ReadingItem] { showBroader ? close + broader : close }

    public func toggledBroader() -> ReadingSearchPresentation {
        guard canToggle else { return self }
        var next = self
        next.showBroader.toggle()
        return next
    }

    public var summary: String {
        let closeWords = close.count == 1 ? "1 close match" : "\(close.count) close matches"
        let broaderWords = broader.count == 1 ? "1 broader result" : "\(broader.count) broader results"
        if showBroader && close.isEmpty { return broaderWords + " · no close matches" }
        if showBroader { return "\(close.count + broader.count) results · \(broader.count) broader" }
        if close.isEmpty && broader.isEmpty { return "No results" }
        if close.isEmpty { return "No close matches · " + broaderWords }
        if broader.isEmpty { return closeWords }
        return closeWords + " · " + broaderWords
    }
}

/// A request's form: what to download and the quality. The request goes only
/// when it is sent; automatic grabbing stays off (`monitoring: "none"`).
public struct ReadingRequestDraft: Equatable, Sendable {
    public let options: ReadingRequestOptions
    public var modeIndex: Int
    public var profileIndex: Int

    public init(options: ReadingRequestOptions) {
        self.options = options
        modeIndex = 0
        profileIndex = options.defaultProfileIndex
    }

    public var mode: ReadingRequestMode? { options.modes.indices.contains(modeIndex) ? options.modes[modeIndex] : nil }
    public var profile: ReadingQualityProfile? {
        options.qualityProfiles.indices.contains(profileIndex) ? options.qualityProfiles[profileIndex] : nil
    }

    /// Without a mode and a quality profile there is nothing BookKeeprr can do.
    public var usable: Bool { !options.modes.isEmpty && !options.qualityProfiles.isEmpty }

    /// The series' books are ticked before the request goes.
    public var needsSeriesPreview: Bool { mode?.requiresSeriesPreview == true }

    /// The form's last button: "Review books" or "Choose release".
    public var submitLabel: String { needsSeriesPreview ? "Review books" : "Choose release" }

    /// "Download Red Rising".
    public func heading(fallbackTitle: String) -> String {
        "Download " + (options.title.isEmpty ? fallbackTitle : options.title)
    }

    /// "Pierce Brown · You choose the torrent next; automatic grabbing stays off."
    public var subtitle: String {
        [options.author, "You choose the torrent next; automatic grabbing stays off."]
            .filter { !ReadingWorkPresentation.blank($0) }.joined(separator: " · ")
    }

    /// The body to send; `seriesId` and `bookIds` once the series' books are chosen.
    public func body(seriesId: String = "", bookIds: [String] = []) -> ReadingCreateRequestBody? {
        guard let mode, let profile else { return nil }
        return ReadingCreateRequestBody(key: options.key, mode: mode.id, seriesId: seriesId, bookIds: bookIds,
                                        qualityProfileId: profile.id, monitoring: "none")
    }

    /// A collection to choose among several: "6 books · Pierce Brown".
    public static func scopeDetail(_ scope: ReadingSeriesPreview) -> String {
        "\(scope.books.count) \(scope.books.count == 1 ? "book" : "books")"
            + (ReadingWorkPresentation.blank(scope.author) ? "" : " · " + scope.author)
    }
}

/// Which books of a series a request asks for: those not yet in the library,
/// as the hub ticked them, until changed. Android's `ReadingSeriesSelectionModel`.
public struct ReadingSeriesSelection: Equatable, Sendable {
    public let books: [ReadingSeriesPreviewBook]
    private var selected: [Bool]

    public init(books: [ReadingSeriesPreviewBook]) {
        self.books = books
        selected = books.map { $0.selected && !$0.inLibrary }
    }

    public func isSelected(_ index: Int) -> Bool { selected.indices.contains(index) && selected[index] }

    /// A book already in the library cannot be asked for again.
    @discardableResult
    public mutating func toggle(_ index: Int) -> Bool {
        guard books.indices.contains(index), !books[index].inLibrary else { return false }
        selected[index].toggle()
        return true
    }

    /// Every missing book, or none if they are all ticked already.
    public mutating func toggleAllMissing() {
        let missing = books.indices.filter { !books[$0].inLibrary }
        let next = missing.contains { !selected[$0] }
        for index in missing { selected[index] = next }
    }

    public var selectedIds: [String] { books.indices.filter { selected[$0] }.map { books[$0].id } }
}

/// Where a request has got to, from BookKeeprr's transfers rather than its
/// first answer. Android's `ReadingAcquisitionState`.
public struct ReadingAcquisitionState: Equatable, Sendable {
    public enum Stage: Sendable { case awaitingChoice, searching, queued, downloading, importing, imported, failed }

    public let stage: Stage
    public let message: String

    public init(stage: Stage, message: String) {
        self.stage = stage
        self.message = message
    }

    public var terminal: Bool { stage == .imported || stage == .failed }

    public var nextAction: String {
        switch stage {
        case .awaitingChoice: "Choose release"
        case .imported: "Open Library"
        default: "Open Transfers"
        }
    }

    /// An earlier request's series, from a unique exact title and kind whose
    /// release names the author; nothing is guessed when titles collide.
    public static func findSeriesId(title: String, author: String, contentType: String, downloads: [ReadingDownloadItem]) -> Int {
        let who = author.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !who.isEmpty else { return 0 }
        var ids: [Int] = []
        for item in downloads where item.title.caseInsensitiveCompare(title) == .orderedSame
            && item.contentType.caseInsensitiveCompare(contentType) == .orderedSame
            && item.releaseTitle.range(of: who, options: .caseInsensitive) != nil && item.seriesId > 0
            && !ids.contains(item.seriesId) {
            ids.append(item.seriesId)
        }
        return ids.count == 1 ? ids[0] : 0
    }

    public static func from(seriesId: Int, downloads: [ReadingDownloadItem], manualSelection: Bool = false) -> ReadingAcquisitionState {
        let rows = downloads.filter { seriesId > 0 && $0.seriesId == seriesId }
        if rows.isEmpty && manualSelection {
            return ReadingAcquisitionState(stage: .awaitingChoice, message: "Choose a release to start downloading.")
        }
        if rows.isEmpty {
            return ReadingAcquisitionState(stage: .searching,
                                           message: "Request accepted. No transfer has started yet. Open Transfers for the latest status.")
        }
        if rows.contains(where: { $0.failed || $0.status == "failed" }) {
            return ReadingAcquisitionState(stage: .failed, message: "Download needs attention in Transfers")
        }
        if rows.allSatisfy({ $0.status == "imported" }) {
            return ReadingAcquisitionState(stage: .imported, message: "Imported · check Library for your book")
        }
        if let downloading = rows.first(where: { $0.status == "downloading" }) {
            return ReadingAcquisitionState(stage: .downloading,
                                           message: "Downloading · \(min(max(downloading.progressPercent, 0), 100))%")
        }
        if rows.contains(where: { $0.status == "importing" || $0.status == "completed" }) {
            return ReadingAcquisitionState(stage: .importing, message: "Downloaded · adding to Library")
        }
        return ReadingAcquisitionState(stage: .queued, message: "Queued in Transfers")
    }
}

/// BookKeeprr's `inLib` means tracked, which does not mean a file was imported.
public enum ReadingRequestActionPolicy {
    public static func showAction(tracked: Bool, canRequest: Bool, hasReleaseTargets: Bool) -> Bool {
        hasReleaseTargets || (!tracked && canRequest)
    }
}

/// The release picker's words.
public enum ReadingReleasePickerPolicy {
    public static func hasTargetChooser(_ targetCount: Int) -> Bool { targetCount > 1 }
    public static func hasSearchAction(_ targetCount: Int) -> Bool { targetCount > 0 }

    public static func summary(total: Int, outsideProfile: Int, wrongFormat: Int, indexerErrors: Int, showingSaved: Bool) -> String {
        var line = total == 0 && indexerErrors == 0 ? "No releases found · Search again later"
            : total == 0 ? "No releases yet" : "\(total) releases"
        if outsideProfile > 0 { line += " · \(outsideProfile) outside profile" }
        if wrongFormat > 0 { line += " · \(wrongFormat) wrong format" }
        if indexerErrors > 0 { line += " · \(indexerErrors) indexer errors" }
        if total == 0 && indexerErrors > 0 { line += " · Try Search again" }
        if showingSaved { line += " · live search failed; showing saved results" }
        return line
    }

    /// Under a release: "4.1 MB · 12 seeds · Indexer · freeleech · EPUB · matches request".
    public static func info(_ release: ReadingRelease) -> String {
        "\(Fmt.bytes(release.sizeBytes)) · \(release.seeders) seeds · \(release.indexer)"
            + (release.freeleech ? " · freeleech" : "") + " · \(release.formatLabel)"
    }

    /// Why a release may not be what was asked for, or nothing.
    public static func warning(_ release: ReadingRelease) -> String {
        if release.ownership != "none" { return release.ownership.replacingOccurrences(of: "-", with: " ").capitalizedFirst }
        if !release.canGrab { return release.reason.isEmpty ? "Wrong format for this request" : release.reason }
        if release.rejected { return "Outside profile · \(release.reason)" }
        return ""
    }
}

/// A BookKeeprr transfer in words. Android's `ReadingTransferSummary`.
public enum ReadingTransferSummary {
    /// How a transfer's chip is coloured.
    public enum Tone: Sendable { case good, waiting, bad, quiet }

    public static func stageLabel(_ status: String, failed: Bool) -> String {
        if failed || status == "failed" { return "Needs attention" }
        switch status {
        case "imported": return "Ready in Library"
        case "completed", "importing": return "Downloaded · adding to Library"
        case "downloading": return "Downloading"
        case "queued": return "Queued"
        case "retry_pending": return "Retry available"
        case "retrying": return "Retrying"
        default:
            let words = status.replacingOccurrences(of: "_", with: " ").capitalizedFirst
            return words.isEmpty ? "Status unavailable" : words
        }
    }

    /// The state in a word or two, for the chip beside a transfer's title.
    public static func chipLabel(_ status: String, failed: Bool) -> String {
        if failed || status == "failed" { return "Failed" }
        switch status {
        case "imported": return "In library"
        case "completed", "importing": return "Importing"
        case "downloading": return "Downloading"
        case "queued": return "Queued"
        case "retry_pending": return "Retry ready"
        case "retrying": return "Retrying"
        default:
            let words = status.replacingOccurrences(of: "_", with: " ").capitalizedFirst
            return words.isEmpty ? "Unknown" : words
        }
    }

    /// As a media transfer's: failed red, in the library green, on its way amber.
    public static func tone(_ status: String, failed: Bool) -> Tone {
        if failed || status == "failed" { return .bad }
        if status == "imported" { return .good }
        if ["completed", "importing", "retry_pending"].contains(status) { return .waiting }
        return .quiet
    }

    public static func showProgress(_ status: String, failed: Bool) -> Bool {
        !failed && ["downloading", "retrying"].contains(status)
    }

    public static func groupLabel(_ contentType: String) -> String {
        switch contentType {
        case "ebook": "Books"
        case "audiobook": "Audiobooks"
        case "comic": "Comics"
        case "manga": "Manga"
        default: "Other reading"
        }
    }

    /// By kind, keeping each kind's own order.
    public static func grouped(_ items: [ReadingDownloadItem]) -> [ReadingDownloadItem] {
        let order = ["ebook", "audiobook", "comic", "manga"]
        return items.enumerated().sorted { a, b in
            let ia = order.firstIndex(of: a.element.contentType) ?? 4
            let ib = order.firstIndex(of: b.element.contentType) ?? 4
            return ia != ib ? ia < ib : a.offset < b.offset
        }.map(\.element)
    }

    public static func fallback(_ status: String, failed: Bool) -> String {
        if failed || status == "failed" { return "Needs attention" }
        switch status {
        case "imported": return "Ready in Library"
        case "completed", "importing": return "Adding to Library"
        default: return "Waiting for BookKeeprr"
        }
    }

    /// The line under a transfer: its size, speed and time left, else its stage in full.
    public static func line(_ item: ReadingDownloadItem) -> String {
        var parts: [String] = []
        if item.sizeBytes > 0 { parts.append(Fmt.bytes(item.sizeBytes)) }
        if item.downloadSpeedBytesPerSecond > 0 { parts.append(Fmt.speed(item.downloadSpeedBytesPerSecond)) }
        if item.etaSeconds > 0 && item.isActive { parts.append(Fmt.eta(item.etaSeconds) + " left") }
        let stage = stageLabel(item.status, failed: item.failed)
        if parts.isEmpty { parts.append(stage) }
        let more = fallback(item.status, failed: item.failed)
        if parts.count == 1 && more.caseInsensitiveCompare(stage) != .orderedSame { parts.append(more) }
        return parts.joined(separator: " · ")
    }

    /// "1 transfer needs attention" (Android's `ActivityDashboard.needAttention`).
    public static func needAttention(_ count: Int) -> String {
        count == 1 ? "1 transfer needs attention" : "\(count) transfers need attention"
    }

    /// The line under the page's heading: nothing yet, what needs attention,
    /// or how many transfers BookKeeprr has.
    public static func status(_ items: [ReadingDownloadItem]) -> String {
        let failed = items.filter(\.failed).count
        if items.isEmpty { return "No book transfers yet." }
        if failed > 0 { return needAttention(failed) }
        return items.count == 1 ? "1 BookKeeprr transfer" : "\(items.count) BookKeeprr transfers"
    }

    /// The page's summary: "1 downloading · 2 queued · 1 failed".
    public static func summary(_ items: [ReadingDownloadItem]) -> String {
        let downloading = items.filter { $0.status == "downloading" }.count
        let queued = items.filter { $0.status == "queued" }.count
        let importing = items.filter { $0.status == "importing" }.count
        let failed = items.filter(\.failed).count
        var line = "\(downloading) downloading"
        if queued > 0 { line += " · \(queued) queued" }
        if importing > 0 { line += " · \(importing) importing" }
        if failed > 0 { line += " · \(failed) failed" }
        return line
    }
}

extension String {
    /// "Retry pending" from "retry pending": the first letter only.
    var capitalizedFirst: String { prefix(1).uppercased() + dropFirst() }
}
