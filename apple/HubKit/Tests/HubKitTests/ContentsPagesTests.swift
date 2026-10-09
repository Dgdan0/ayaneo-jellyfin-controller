import Testing
@testable import HubKit

/// Contents' page numbers (#55): the page an entry starts on, in the corners' count.
struct ContentsPagesTests {
    // Three files: a quarter of the book, a half, and the last quarter; 10, 25 and 15 of Readium's positions.
    private let sections = BookSections(sections: [
        .init(href: "OEBPS/a.xhtml", start: 0, size: 10),
        .init(href: "OEBPS/b.xhtml", start: 0.25, size: 25),
        .init(href: "OEBPS/c.xhtml", start: 0.75, size: 15),
    ])

    @Test func withTheBooksOwnPagesAnEntryIsThePageItsStartFallsOn() {
        #expect(ContentsPages.page(sections: sections, bookPages: 400, href: "OEBPS/a.xhtml", share: 0) == 1)
        #expect(ContentsPages.page(sections: sections, bookPages: 400, href: "OEBPS/b.xhtml", share: 0) == 100)
        #expect(ContentsPages.page(sections: sections, bookPages: 400, href: "OEBPS/b.xhtml#part2", share: 0.5) == 200)
        #expect(ContentsPages.page(sections: sections, bookPages: 400, href: "OEBPS/c.xhtml", share: 0) == 300)
        // The corners' count: the same page the reader says when it opens there.
        for (href, share) in [("OEBPS/b.xhtml", 0.3), ("OEBPS/c.xhtml", 0.0)] {
            let progress = sections.progress(href: href, progression: share, totalProgression: nil)!
            let corner = PageInfo.pageInBook(PageInfo.Reading(bookPages: 400, progress: progress))
            #expect(corner?.page == ContentsPages.page(sections: sections, bookPages: 400, href: href, share: share))
        }
    }

    @Test func withoutThemItIsTheReadiumPositionItStartsOnPlusOne() {
        #expect(ContentsPages.page(sections: sections, bookPages: 0, href: "OEBPS/a.xhtml", share: 0) == 1)
        #expect(ContentsPages.page(sections: sections, bookPages: 0, href: "OEBPS/b.xhtml", share: 0) == 11)
        #expect(ContentsPages.page(sections: sections, bookPages: 0, href: "OEBPS/b.xhtml", share: 0.4) == 21)
        #expect(ContentsPages.page(sections: sections, bookPages: 0, href: "OEBPS/c.xhtml", share: 0) == 36)
    }

    @Test func aNumberThatCannotBeWorkedOutIsLeftOut() {
        #expect(ContentsPages.page(sections: sections, bookPages: 400, href: "OEBPS/notes.xhtml", share: 0) == nil)
        #expect(ContentsPages.page(sections: BookSections(sections: []), bookPages: 0, href: "OEBPS/a.xhtml", share: 0) == nil)
    }

    @Test func anEntryIntoTheMiddleOfAFileStartsAtItsShareOfTheText() {
        let html = """
            <html><head><title>Part One and Two</title><style>p { color: red }</style></head>
            <body><h1>One</h1><p>aaaa   aaaa</p>
            <h2 id="part2">Two</h2><p>bb&amp;b</p></body></html>
            """
        // Before "Two": "One" and "aaaaaaaa", 11 letters, of 11 + "Two" + "bb&b" (an entity one letter), 18.
        #expect(ContentsPages.share(html: html, anchor: "part2") == 11.0 / 18.0)
        #expect(ContentsPages.share(html: html, anchor: "") == 0, "an entry that opens the file")
        #expect(ContentsPages.share(html: html, anchor: "missing") == nil)
        #expect(ContentsPages.shares(html: html, anchors: ["part2", "", "missing", "part2"]) == ["part2": 11.0 / 18.0, "": 0],
                "several lines into one file, its text read once")
        #expect(ContentsPages.share(html: "<body><p id='a.b'>x</p><p id='a-b'>y</p></body>", anchor: "a-b") == 0.5,
                "single quotes, and an id that looks like a pattern")
        #expect(ContentsPages.share(html: "<body><p data-id='x'>x</p><p id='x'>y</p></body>", anchor: "x") == 0.5,
                "another attribute ending in id is not the id")
    }
}
