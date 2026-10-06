import CoreGraphics
import CoreText
import Foundation
import ImageIO
import Synchronization

/// The demo hub's comic and manga pages (`-demo`, #25 phase 3): the runs the
/// Books demo lists (`DemoReading`: Fantastic Four at issue 51, Amazing Adult
/// Fantasy, Chainsaw Man), by the same ids, with every page drawn here. Each
/// page has paper round it, as a scan does, so trimming has something to
/// find; the middle page of an issue is a two-page spread scanned as one;
/// manga read right to left. The place in an issue answers as the hub's does:
/// a save naming a page the hub no longer has (`expectedPage`) is refused with
/// 409. Nothing real is read or written.
enum DemoComics {
    struct Run: Sendable {
        let workId: String
        let series: String
        /// "comic" or "manga".
        let kind: String
        let issues: [(number: Int, pages: Int)]
        /// The issue being read and its page, as the Books demo's run shows it.
        let reading: (number: Int, page: Int)?
    }

    static let runs: [Run] = [
        Run(workId: "rw_demo_ff", series: "Fantastic Four", kind: "comic",
            issues: (1...12).map { ($0, 36) } + (51...56).map { ($0, 24) }, reading: (51, 1)),
        Run(workId: "rw_demo_aaf", series: "Amazing Adult Fantasy", kind: "comic", issues: [(7, 36)], reading: (7, 1)),
        Run(workId: "rw_demo_csm", series: "Chainsaw Man", kind: "manga", issues: (1...8).map { ($0, 22) }, reading: nil),
    ]

    /// An issue's id, as the Books demo's run page names it.
    static func issueId(_ run: Run, _ number: Int) -> String { "\(run.workId)-\(number)" }

    /// The pages saved this run, by issue.
    private static let places = Mutex<[String: Int]>([:])

    static func answer(method: String, path: String, query: String, body: Data?) -> DemoTransport.Answer? {
        let parts = path.split(separator: "/").map(String.init)
        // /v1/reading/works/{work}/publications/{issue}[/pages/{n}[/thumb] | /progress]
        guard parts.count >= 6, parts[0] == "v1", parts[1] == "reading", parts[2] == "works", parts[4] == "publications",
              let run = runs.first(where: { $0.workId == parts[3] }) else { return nil }
        let route = parts.count > 6 ? parts[6] : ""
        guard route.isEmpty || route == "pages" || route == "progress" else { return nil }
        guard let index = run.issues.firstIndex(where: { issueId(run, $0.number) == parts[5] }) else {
            return failure(404, "not_found", "publication does not belong to this work")
        }
        let issue = run.issues[index]
        switch (method, parts.count, route) {
        case ("GET", 6, _):
            return manifest(run, index: index)
        case ("GET", 8, "pages"), ("GET", 9, "pages"):
            guard let page = Int(parts[7]), page >= 0 else { return failure(400, "invalid_request", "invalid reading page") }
            guard page < issue.pages else {
                return failure(400, "invalid_request", "reading page is outside this publication")
            }
            if parts.count == 8 { return image(run, number: issue.number, pages: issue.pages, page: page, width: nil) }
            guard parts[8] == "thumb" else { return nil }
            var width = 160
            if let asked = value("w", in: query) {
                guard let number = Int(asked) else { return failure(400, "invalid_request", "w must be a number of pixels") }
                width = min(512, max(64, number))
            }
            return image(run, number: issue.number, pages: issue.pages, page: page, width: width)
        case ("POST", 7, "progress"):
            return progress(run, index: index, body: body)
        default:
            return nil
        }
    }

    /// A page's size: a comic page 1000 x 1538 (a US comic's 1988 x 3056
    /// shape), a manga page 700 x 1000; the middle page is a spread.
    static func size(_ run: Run, pages: Int, page: Int) -> (width: Int, height: Int) {
        let (width, height) = run.kind == "manga" ? (700, 1_000) : (1_000, 1_538)
        return page == pages / 2 ? (width * 2, height) : (width, height)
    }

    /// The page the issue is on: the one saved this run, else the Books demo's.
    static func place(_ run: Run, number: Int) -> Int {
        places.withLock { $0[issueId(run, number)] } ?? (run.reading?.number == number ? run.reading?.page ?? 0 : 0)
    }

    private static func manifest(_ run: Run, index: Int) -> DemoTransport.Answer {
        let issue = run.issues[index]
        let pages = (0..<issue.pages).map { page -> [String: Any] in
            let size = size(run, pages: issue.pages, page: page)
            var fields: [String: Any] = ["index": page, "width": size.width, "height": size.height]
            if size.width > size.height { fields["isWide"] = true }
            return fields
        }
        var fields: [String: Any] = [
            "workId": run.workId, "source": "kavita", "sourceItemId": issueId(run, issue.number), "kind": run.kind,
            "title": "\(issue.number)", "seriesTitle": run.series, "number": "\(issue.number)",
            "pageCount": issue.pages, "currentPage": place(run, number: issue.number),
            "direction": run.kind == "manga" ? "rtl" : "ltr", "pages": pages, "doublePairs": [String: Int](),
        ]
        if index > 0 { fields["previousSourceItemId"] = issueId(run, run.issues[index - 1].number) }
        if index + 1 < run.issues.count { fields["nextSourceItemId"] = issueId(run, run.issues[index + 1].number) }
        return json(fields)
    }

    /// The hub's `handleReadingPublicationProgress`: only `pageIndex` and
    /// `expectedPage`, a stale `expectedPage` refused, a page past the end refused.
    private static func progress(_ run: Run, index: Int, body: Data?) -> DemoTransport.Answer {
        let issue = run.issues[index]
        guard let body, let fields = try? JSONSerialization.jsonObject(with: body) as? [String: Any],
              Set(fields.keys).isSubset(of: ["pageIndex", "expectedPage"]),
              let pageIndex = fields["pageIndex"] as? Int, pageIndex >= 0 else {
            return failure(400, "invalid_request", "invalid reading progress")
        }
        let expected = fields["expectedPage"] as? Int
        let current = place(run, number: issue.number)
        if let expected, expected != current {
            return failure(409, "reading_position_conflict",
                           "Reading progress changed on another device. Choose which position to continue from.")
        }
        guard pageIndex < issue.pages else {
            return failure(400, "invalid_request", "reading progress is outside this publication")
        }
        places.withLock { $0[issueId(run, issue.number)] = pageIndex }
        return json(["ok": true, "action": "save_reading_progress"])
    }

    // MARK: Drawing

    private static func image(_ run: Run, number: Int, pages: Int, page: Int, width: Int?) -> DemoTransport.Answer {
        DemoTransport.Answer(200, data: draw(run, number: number, pages: pages, page: page, width: width), type: "image/jpeg")
    }

    /// The page at its own size, or `width` across (never wider than it is).
    static func draw(_ run: Run, number: Int, pages: Int, page: Int, width requested: Int?) -> Data {
        let size = size(run, pages: pages, page: page)
        let scale = requested.map { min(1, Double($0) / Double(size.width)) } ?? 1
        let width = max(1, Int((Double(size.width) * scale).rounded()))
        let height = max(1, Int((Double(size.height) * scale).rounded()))
        guard let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                                      space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return Data() }
        // Drawn in the page's own units, from its top left.
        context.scaleBy(x: CGFloat(scale), y: CGFloat(scale))
        context.translateBy(x: 0, y: CGFloat(size.height))
        context.scaleBy(x: 1, y: -1)
        let pageWidth = CGFloat(size.width), pageHeight = CGFloat(size.height)
        let manga = run.kind == "manga"
        let single = CGFloat(manga ? 700 : 1_000)
        // Paper, then the art inside its margins.
        context.setFillColor(CGColor(red: 0.953, green: 0.937, blue: 0.894, alpha: 1))
        context.fill(CGRect(x: 0, y: 0, width: pageWidth, height: pageHeight))
        let art = CGRect(x: single * 0.05, y: pageHeight * 0.04, width: pageWidth - single * 0.1,
                         height: pageHeight * 0.91)
        let ink = CGColor(red: 0.08, green: 0.08, blue: 0.1, alpha: 1)
        let label = manga ? "\(run.series.uppercased()) · CHAPTER \(number)" : "\(run.series.uppercased()) #\(number)"
        if page == 0 {
            // The cover: one panel, the run's name and the number large.
            panel(art, hue: hue(run, number, 0, 0), in: context)
            text(label, size: single * 0.075, at: CGPoint(x: art.midX, y: art.minY + art.height * 0.16), color: ink, in: context)
            text(manga ? "\(number)" : "#\(number)", size: single * 0.34, at: CGPoint(x: art.midX, y: art.midY + art.height * 0.08),
                 color: ink, in: context)
        } else {
            let gutter = single * 0.022
            let rows: [CGFloat] = page == pages / 2 ? [0.62, 0.38] : [[0.3, 0.4, 0.3], [0.42, 0.28, 0.3], [0.26, 0.38, 0.36]][page % 3]
            var y = art.minY
            var count = 0
            var biggest = CGRect.zero
            for (row, share) in rows.enumerated() {
                let rowHeight = art.height * share - (row == rows.count - 1 ? 0 : gutter)
                let columns = (page + row) % 3 + 1
                let cell = (art.width - gutter * CGFloat(columns - 1)) / CGFloat(columns)
                for column in 0..<columns {
                    // Manga panels run right to left.
                    let place = manga ? columns - 1 - column : column
                    let rect = CGRect(x: art.minX + CGFloat(place) * (cell + gutter), y: y, width: cell, height: rowHeight)
                    count += 1
                    panel(rect, hue: hue(run, number, page, count), in: context)
                    text("\(count)", size: single * 0.04, at: CGPoint(x: rect.minX + single * 0.045, y: rect.minY + single * 0.05),
                         color: ink, in: context, centered: false)
                    if rect.width * rect.height > biggest.width * biggest.height { biggest = rect }
                }
                y += rowHeight + gutter
            }
            // The page's number large, and the run under it, in the largest panel.
            text("\(page + 1)", size: min(biggest.height * 0.6, single * 0.3), at: CGPoint(x: biggest.midX, y: biggest.midY),
                 color: ink, in: context)
            text(label, size: single * 0.035, at: CGPoint(x: biggest.midX, y: biggest.maxY - single * 0.05), color: ink,
                 in: context)
        }
        guard let image = context.makeImage() else { return Data() }
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(data, "public.jpeg" as CFString, 1, nil) else { return Data() }
        CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: 0.82] as CFDictionary)
        CGImageDestinationFinalize(destination)
        return data as Data
    }

    /// A panel: a colour of its own, a darker disc in it, and a black border.
    private static func panel(_ rect: CGRect, hue: Double, in context: CGContext) {
        context.setFillColor(color(hue: hue, saturation: 0.42, brightness: 0.9))
        context.fill(rect)
        let disc = min(rect.width, rect.height) * 0.42
        context.setFillColor(color(hue: (hue + 0.5).truncatingRemainder(dividingBy: 1), saturation: 0.35, brightness: 0.72))
        context.fillEllipse(in: CGRect(x: rect.maxX - disc * 1.2, y: rect.minY + disc * 0.25, width: disc, height: disc))
        context.setStrokeColor(CGColor(red: 0.05, green: 0.05, blue: 0.07, alpha: 1))
        context.setLineWidth(max(2, rect.width * 0.012))
        context.stroke(rect)
    }

    private static func hue(_ run: Run, _ number: Int, _ page: Int, _ panel: Int) -> Double {
        let seed = Double(DemoReading.fnv(run.workId) % 997) / 997
        return (seed + Double(number) * 0.13 + Double(page) * 0.071 + Double(panel) * 0.19).truncatingRemainder(dividingBy: 1)
    }

    /// Words drawn upright in the page's flipped units, centred on `point` or
    /// starting there.
    private static func text(_ string: String, size: CGFloat, at point: CGPoint, color: CGColor, in context: CGContext,
                             centered: Bool = true) {
        let font = CTFontCreateWithName("Helvetica-Bold" as CFString, size, nil)
        let attributes = [kCTFontAttributeName: font, kCTForegroundColorAttributeName: color] as CFDictionary
        guard let attributed = CFAttributedStringCreate(nil, string as CFString, attributes) else { return }
        let line = CTLineCreateWithAttributedString(attributed)
        let bounds = CTLineGetBoundsWithOptions(line, .useOpticalBounds)
        context.saveGState()
        context.textMatrix = CGAffineTransform(scaleX: 1, y: -1)
        let x = centered ? point.x - bounds.width / 2 - bounds.minX : point.x
        context.textPosition = CGPoint(x: x, y: point.y + bounds.minY + bounds.height / 2)
        CTLineDraw(line, context)
        context.restoreGState()
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

    // MARK: Answers

    private static func json(_ fields: [String: Any]) -> DemoTransport.Answer {
        DemoTransport.Answer(200, data: (try? JSONSerialization.data(withJSONObject: fields)) ?? Data("{}".utf8),
                             type: "application/json")
    }

    private static func failure(_ status: Int, _ code: String, _ message: String) -> DemoTransport.Answer {
        DemoTransport.Answer(status, #"{"error":{"code":"\#(code)","message":"\#(message)"}}"#)
    }

    private static func value(_ name: String, in query: String) -> String? {
        query.split(separator: "&").first { $0.hasPrefix(name + "=") }.map { String($0.dropFirst(name.count + 1)) }
    }
}
