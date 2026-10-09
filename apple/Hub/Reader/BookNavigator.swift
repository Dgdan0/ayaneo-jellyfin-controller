#if os(iOS)
@preconcurrency import ReadiumNavigator
@preconcurrency import ReadiumShared
@preconcurrency import ReadiumStreamer
import HubKit
import UIKit
import WebKit

/// Where the page is, as the reader needs it: Readium's locator as JSON, and
/// the parts of it the menu and the pace read.
struct BookPlaceOnPage: Equatable {
    let json: String
    let href: String
    let title: String?
    let progression: Double
    let totalProgression: Double?
}

/// A line of the contents: a part of the book, how deep it sits, and the
/// place it opens (a Readium locator as JSON). `anchor` is the element it
/// points to inside its file (`chapter.xhtml#part2`), "" for a line that
/// opens the file.
struct BookContentsRow: Identifiable, Equatable {
    let id: Int
    let depth: Int
    let title: String
    let href: String
    let locator: String
    var anchor = ""
}

/// The book as Readium has it (#25, phase 4): the EPUB opened from the file
/// the reader downloaded, and Readium's navigator over it. This is the one
/// file that speaks Readium; the model and the views get strings, numbers
/// and closures, so SwiftUI's `Color`, `Link` and `TextAlignment` never meet
/// Readium's.
@MainActor
final class BookNavigator: NSObject {
    /// What opening the file gave: the publication and what the reader reads from it.
    struct Loaded {
        let publication: Publication
        let sections: BookSections
        let contents: [BookContentsRow]
        /// Each part's file, in reading order.
        let readingOrder: [String]
    }

    var onPlace: (BookPlaceOnPage) -> Void = { _ in }
    /// A note reference was followed: the note's words, for the card.
    var onNote: (String) -> Void = { _ in }
    /// A link in the book is being followed (a note's own "Go to the note" too).
    var onFollowLink: () -> Void = {}
    /// The navigator jumped somewhere, for whatever reason.
    var onJump: () -> Void = {}
    /// A link out of the book was tapped; it is not opened.
    var onExternalLink: () -> Void = {}
    /// A tap on the page, how far across it, 0 to 1.
    var onTap: (Double) -> Void = { _ in }
    var onKey: (ReaderKey) -> Void = { _ in }
    /// Part of the book could not be read.
    var onFailure: (String) -> Void = { _ in }

    /// An iPad (or the Mac's wide window): Kindle's wide margins and strips (#47).
    static var isTablet: Bool { forcedTablet || UIDevice.current.userInterfaceIdiom != .phone }

    /// scripts/mac.sh: HUB_BOOK_TABLET=1 lays a phone's reader out as an iPad's, its strips and margins too,
    /// for screenshots of Kindle's page. Debug builds only.
    static var forcedTablet: Bool {
        #if DEBUG
        return ProcessInfo.processInfo.environment["HUB_BOOK_TABLET"] == "1"
        #else
        return false
        #endif
    }

    /// Readium's page gutter in CSS pixels: half the gap between two columns.
    /// Given once, as the navigator is made, so it holds while this one lives
    /// (`EpubGeometry`).
    let gutter = EpubGeometry.gutter(tablet: BookNavigator.isTablet)

    private var publication: Publication?
    private var controller: EPUBNavigatorViewController?
    /// Reading along: the accent the sentence is washed in, and the wash for the page's colours (#52).
    private var narrationTint: UInt32?
    private var narrationWash: UInt32?
    /// The element of the sentence lit, whose words its boxes are trimmed to (#56).
    private var narrationFragment: String?
    /// The book's documents as Readium spells them: what a locator made from
    /// the narration's path names (#61).
    private(set) var hrefs = BookHrefs(readingOrder: [])
    /// Makes the turns, one at a time (#64).
    private var turner: PageTurner?
    /// The note whose card is open: where "Go to the note" goes.
    private var noteLink: ReadiumShared.Link?

    /// Opens the EPUB at `file`: its publication, its parts and their
    /// positions, and its contents. Off the main actor, where Readium does
    /// its reading; only the result comes back.
    nonisolated static func load(file: URL) async throws -> sending Loaded {
        guard let url = FileURL(url: file) else { throw CocoaError(.fileReadInvalidFileName) }
        let http = DefaultHTTPClient()
        let retriever = AssetRetriever(httpClient: http)
        let asset = try await retriever.retrieve(url: url).get()
        let opener = PublicationOpener(parser: DefaultPublicationParser(httpClient: http, assetRetriever: retriever,
                                                                        pdfFactory: DefaultPDFDocumentFactory()))
        let publication = try await opener.open(asset: asset, allowUserInteraction: false).get()
        let positions = (try? await publication.positions().get()) ?? []
        let sections = BookSections(positions: positions.map {
            (href: $0.href.string, totalProgression: $0.locations.totalProgression)
        })
        var links: [(depth: Int, link: ReadiumShared.Link)] = []
        func flatten(_ list: [ReadiumShared.Link], depth: Int) {
            for link in list {
                links.append((depth, link))
                flatten(link.children, depth: depth + 1)
            }
        }
        let toc = (try? await publication.tableOfContents().get()) ?? []
        flatten(toc.isEmpty ? publication.readingOrder : toc, depth: 0)
        var contents: [BookContentsRow] = []
        for (index, entry) in links.enumerated() {
            guard let locator = await publication.locate(entry.link), let json = try? locator.jsonString() else { continue }
            let title = entry.link.title?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            let fragment = entry.link.href.split(separator: "#", maxSplits: 1).dropFirst().first.map(String.init) ?? ""
            contents.append(BookContentsRow(id: index, depth: entry.depth, title: title.isEmpty ? "Section \(index + 1)" : title,
                                            href: BookSections.path(locator.href.string), locator: json,
                                            anchor: fragment.removingPercentEncoding ?? fragment))
        }
        return Loaded(publication: publication, sections: sections, contents: contents,
                      readingOrder: publication.readingOrder.map { BookSections.path($0.href) })
    }

    /// The navigator over `loaded`, at `locator` (JSON) or the beginning,
    /// drawn as `rendering` says. Reading along, the sentence spoken glows
    /// in `narration`'s colour (`ReadAlongHighlight`).
    func makeController(_ loaded: Loaded, at locator: String?, rendering: EpubRendering,
                        narration: UInt32? = nil) throws -> UIViewController {
        let initial = locator.flatMap { try? Locator(jsonString: $0) }
        let templates = narration.map(ReadAlongHighlight.allTemplates) ?? HTMLDecorationTemplate.defaultTemplates()
        let navigator = try EPUBNavigatorViewController(
            publication: loaded.publication, initialLocation: initial,
            config: EPUBNavigatorViewController.Configuration(
                preferences: Self.preferences(rendering), contentInset: Self.strips, decorationTemplates: templates,
                fontFamilyDeclarations: Self.fontFamilies,
                readiumCSSRSProperties: CSSRSProperties(pageGutter: CSSPxLength(gutter))))
        navigator.delegate = self
        publication = loaded.publication
        controller = navigator
        // Every turn asked for is made, however fast they come (#64).
        turner = PageTurner(host: navigator.view, scrolls: { [weak navigator] in navigator?.settings.scroll ?? false },
                            scrollViews: { [weak self] in self?.scrollViews() ?? [] }) { [weak navigator] turn, animated in
            guard let navigator else { return false }
            let options = NavigatorGoOptions(animated: animated)
            switch turn {
            case .forward: return await navigator.goForward(options: options)
            case .backward: return await navigator.goBackward(options: options)
            case .left: return await navigator.goLeft(options: options)
            case .right: return await navigator.goRight(options: options)
            }
        }
        hrefs = BookHrefs(readingOrder: loaded.readingOrder)
        narrationTint = narration
        narrationWash = Self.wash(narration, rendering)
        return navigator
    }

    /// The appearance changed: Readium lays the book out again where it is,
    /// and the sentence's wash follows the page's colours.
    func submit(_ rendering: EpubRendering) {
        controller?.submitPreferences(Self.preferences(rendering))
        narrationWash = Self.wash(narrationTint, rendering)
        fitNarration()
    }

    /// The accent mixed into the page under its ink (`ReadAlongGlow.wash`).
    private static func wash(_ tint: UInt32?, _ rendering: EpubRendering) -> UInt32? {
        guard let tint, let page = EpubPagePalette.argb(rendering.background),
              let ink = EpubPagePalette.argb(rendering.text) else { return nil }
        return ReadAlongGlow.wash(accent: tint, page: page, ink: ink)
    }

    /// On a page, after every turn asked for before it (#64).
    @discardableResult
    func goForward() async -> Bool {
        await turner?.turn(.forward) ?? false
    }

    /// Back a page, after every turn asked for before it (#64).
    @discardableResult
    func goBackward() async -> Bool {
        await turner?.turn(.backward) ?? false
    }

    /// Every scroll view of the page, Readium's paging between parts and each part's own.
    private func scrollViews() -> [UIScrollView] {
        guard let root = controller?.view else { return [] }
        var found: [UIScrollView] = []
        func walk(_ view: UIView) {
            if let scroll = view as? UIScrollView { found.append(scroll) }
            for child in view.subviews { walk(child) }
        }
        walk(root)
        return found
    }

    /// To a place given as a Readium locator's JSON.
    func go(to json: String) async -> Bool {
        guard let controller, let locator = try? Locator(jsonString: json) else { return false }
        return await controller.go(to: locator, options: NavigatorGoOptions(animated: false))
    }

    // MARK: Read along

    /// The sentence spoken glows, or nothing does.
    func highlight(_ segment: ReadAlongSegment?) {
        narrationFragment = segment?.fragment
        controller?.apply(decorations: ReadAlongHighlight.decorations(segment, hrefs: hrefs), in: ReadAlongHighlight.group)
        fitNarration()
    }

    /// The sentence's boxes fitted to its lines in the wash (#52,
    /// `ReadAlongPageScript.fitNarration`). The script stays in the page and
    /// fits Readium's boxes again whenever it lays them out; run again here as
    /// the sentence or the page's colours change, and as a part opens, whose
    /// page has not had it yet.
    func fitNarration() {
        guard let controller, let wash = narrationWash else { return }
        let script = ReadAlongPageScript.fitNarration(wash: wash, fragment: narrationFragment)
        Task { _ = await controller.evaluateJavaScript(script) }
    }

    /// A script's answer from the page on screen (`ReadAlongPageScript`), or nil.
    func evaluate(_ script: String) async -> Any? {
        guard let controller else { return nil }
        let answer = await controller.evaluateJavaScript(script)
        #if DEBUG
        if case .failure(let error) = answer { NSLog("book: a script on the page failed: %@", String(describing: error)) }
        #endif
        return try? answer.get()
    }

    // MARK: Search (#37)

    /// The first `limit` passages with `query` in them, in reading order, from
    /// The HTML of the part `href` (a path, as `BookSections` has it), for
    /// where the contents' lines into it start (#55). Nil when the book has no
    /// such part or it cannot be read.
    func html(of href: String) async -> String? {
        guard let publication,
              let link = publication.readingOrder.first(where: { BookSections.path($0.href) == href }),
              let resource = publication.get(link),
              case .success(let data) = await resource.read() else { return nil }
        return String(decoding: data, as: UTF8.self)
    }

    /// Readium's search of the publication's text (Android's `EpubBookSearch`),
    /// or why there are none. A cancelled search stops between Readium's pages
    /// of results. Readium names no chapter for a result: `chapters` does,
    /// the contents' title of each part by its file.
    func search(_ query: String, chapters: [String: String], limit: Int = BookSearch.limit)
        async -> Result<[BookSearchHit], BookSearch.Problem> {
        guard let publication, publication.isSearchable else { return .failure(.notSearchable) }
        guard case .success(let iterator) = await publication.search(query: query) else { return .failure(.failed) }
        defer { iterator.close() }
        var hits: [BookSearchHit] = []
        while hits.count < limit {
            if Task.isCancelled { return .success(hits) }
            switch await iterator.next() {
            case .success(let page?):
                for locator in page.locators.prefix(limit - hits.count) {
                    guard let json = try? locator.jsonString() else { continue }
                    let words = BookSearch.snippet(before: locator.text.before ?? "", match: locator.text.highlight ?? query,
                                                   after: locator.text.after ?? "")
                    let chapter = locator.title ?? chapters[BookSections.path(locator.href.string)]
                    hits.append(BookSearchHit(id: hits.count, chapter: BookSearch.chapter(chapter), before: words.before,
                                              match: words.match, after: words.after, locator: json))
                }
            case .success(nil):
                return .success(hits)
            case .failure:
                return hits.isEmpty ? .failure(.failed) : .success(hits)
            }
        }
        return .success(hits)
    }

    private static let searchGroup = "search"

    /// The passage a search opened, marked on its page; nil takes the mark away.
    func markFound(_ json: String?) {
        guard let controller else { return }
        let decorations = json.flatMap { try? Locator(jsonString: $0) }.map {
            [Decoration(id: "found", locator: $0, style: .highlight(tint: UIColor(red: 1, green: 0.78, blue: 0.2, alpha: 1)))]
        } ?? []
        controller.apply(decorations: decorations, in: Self.searchGroup)
    }

    /// To the note whose card is open.
    func followNote() async -> Bool {
        guard let controller, let link = noteLink else { return false }
        noteLink = nil
        onFollowLink()
        return await controller.go(to: link, options: NavigatorGoOptions(animated: false))
    }

    /// The web view showing the part on screen: what the keys and the stick
    /// scroll, and what tells the page of a part. Readium gives each part its
    /// own, so it is the one covering most of the navigator.
    func visibleScrollView() -> UIScrollView? {
        guard let root = controller?.view else { return nil }
        var best: (view: WKWebView, area: CGFloat)?
        func walk(_ view: UIView) {
            if let web = view as? WKWebView, !web.isHidden, web.window != nil {
                let frame = web.convert(web.bounds, to: root).intersection(root.bounds)
                let area = frame.isNull ? 0 : frame.width * frame.height
                if area > (best?.area ?? 0) { best = (web, area) }
                return
            }
            for child in view.subviews { walk(child) }
        }
        walk(root)
        return best?.view.scrollView
    }

    /// Which screen of the part is showing, and of how many: a paginated
    /// part scrolls across, a screen at a time.
    func pageInPart() -> (index: Int, count: Int)? {
        guard let view = visibleScrollView(), view.bounds.width > 0 else { return nil }
        let count = Int((view.contentSize.width / view.bounds.width).rounded())
        guard count > 0 else { return nil }
        return (min(max(Int((abs(view.contentOffset.x) / view.bounds.width).rounded()), 0), count - 1), count)
    }

    func close() {
        controller?.delegate = nil
        turner = nil
        controller = nil
        publication = nil
    }

    /// The strips kept at the top and bottom of the page, off the text: the
    /// corners sit there (#42, `PageInfo.strip`, which the corners read too).
    private static var strips: [UIUserInterfaceSizeClass: EPUBContentInsets] {
        let regular = PageInfo.strip(compactHeight: false, tablet: isTablet)
        let compact = forcedTablet ? regular : PageInfo.strip(compactHeight: true, tablet: isTablet)
        return [.compact: (top: CGFloat(compact.top), bottom: CGFloat(compact.bottom)),
                .regular: (top: CGFloat(regular.top), bottom: CGFloat(regular.bottom))]
    }

    /// The faces the app ships (#47), declared to Readium: Literata and Atkinson
    /// Hyperlegible Next are variable fonts, so one file serves every weight.
    /// Apple's own (Charter, Georgia, Iowan Old Style) need only their names.
    private static var fontFamilies: [AnyHTMLFontFamilyDeclaration] {
        func file(_ name: String) -> FileURL? { Bundle.main.url(forResource: name, withExtension: "ttf").flatMap(FileURL.init(url:)) }
        return EpubTypefaces.bundledFiles.compactMap { entry -> AnyHTMLFontFamilyDeclaration? in
            guard let roman = file(entry.roman) else { return nil }
            var faces = [CSSFontFace(file: roman, style: .normal, weight: .variable(200...900))]
            if let italic = entry.italic.flatMap(file) { faces.append(CSSFontFace(file: italic, style: .italic, weight: .variable(200...900))) }
            return CSSFontFamilyDeclaration(fontFamily: FontFamily(rawValue: entry.family), fontFaces: faces)
                .eraseToAnyHTMLFontFamilyDeclaration()
        }
    }

    /// The appearance as Readium's preferences. The margins are the app's
    /// (`EpubGeometry`): Readium is left its own gutter, given with the
    /// navigator, so its padding is exactly half the gap between columns.
    private static func preferences(_ rendering: EpubRendering) -> EPUBPreferences {
        EPUBPreferences(
            backgroundColor: ReadiumNavigator.Color(hex: rendering.background),
            columnCount: rendering.columns == .one ? .one : rendering.columns == .two ? .two : .auto,
            fontFamily: rendering.fontFamily.map { FontFamily(rawValue: $0) },
            fontSize: rendering.fontSize,
            hyphens: rendering.hyphens,
            lineHeight: rendering.lineHeight,
            pageMargins: 1,
            publisherStyles: rendering.publisherStyles,
            scroll: rendering.scroll,
            textAlign: rendering.textAlign == "justify" ? ReadiumNavigator.TextAlignment.justify : .start,
            textColor: ReadiumNavigator.Color(hex: rendering.text),
            theme: rendering.theme == "dark" ? ReadiumNavigator.Theme.dark : rendering.theme == "sepia" ? .sepia : .light)
    }

    /// A key Readium heard, as a reader's key.
    private static func readerKey(_ key: Key) -> ReaderKey? {
        switch key {
        case .space: .space
        case .enter: .returnKey
        case .backspace: .delete
        case .escape: .escape
        case .arrowLeft: .left
        case .arrowRight: .right
        case .arrowUp: .up
        case .arrowDown: .down
        case .pageUp: .pageUp
        case .pageDown: .pageDown
        case .character(let text):
            switch text {
            case "-": .minus
            case "=", "+": .plus
            case "\u{8}", "\u{7F}": .delete
            default: nil
            }
        default: nil
        }
    }
}

extension BookNavigator: EPUBNavigatorDelegate {
    func navigator(_ navigator: Navigator, locationDidChange locator: Locator) {
        fitNarration()
        guard let json = try? locator.jsonString() else { return }
        onPlace(BookPlaceOnPage(json: json, href: BookSections.path(locator.href.string), title: locator.title,
                                progression: locator.locations.progression ?? 0,
                                totalProgression: locator.locations.totalProgression))
    }

    func navigator(_ navigator: Navigator, presentError error: NavigatorError) {}

    func navigator(_ navigator: Navigator, didJumpTo locator: Locator) {
        onJump()
    }

    /// A note opens as a card and the page stays; an empty one is followed.
    func navigator(_ navigator: Navigator, shouldNavigateToNoteAt link: ReadiumShared.Link, content: String,
                   referrer: String?) -> Bool {
        let text = FootnoteText.plain(content)
        if text.isEmpty { return true }
        noteLink = link
        onNote(text)
        return false
    }

    func navigator(_ navigator: VisualNavigator, shouldNavigateToLink link: ReadiumShared.Link) -> Bool {
        onFollowLink()
        return true
    }

    /// Not opened: a book read on the couch should not throw anyone into a browser.
    func navigator(_ navigator: Navigator, presentExternalURL url: URL) {
        onExternalLink()
    }

    func navigator(_ navigator: Navigator, didFailToLoadResourceAt href: RelativeURL, withError error: ReadError) {
        onFailure("Part of this book could not be read")
    }

    func navigator(_ navigator: VisualNavigator, didTapAt point: CGPoint) {
        guard let width = controller?.view.bounds.width, width > 0 else { return }
        onTap(Double(point.x / width))
    }

    func navigator(_ navigator: VisualNavigator, didPressKey event: KeyEvent) {
        #if DEBUG
        NSLog("book: key %@ from the page", String(describing: event.key))
        #endif
        guard event.modifiers.isEmpty || event.modifiers == [.shift], let key = Self.readerKey(event.key) else { return }
        onKey(key)
    }
}
#endif
