import Foundation

// Where the comic reader puts a page (#25, phase 3). Android's reader places
// a page with SubsamplingScaleImageView's scale and centre, and works out the
// fit, the thirds, a kept zoom and its pans in its screen; here that arithmetic
// is HubKit's, so it is tested. A unit is what shows at once: one page, or two
// side by side on an iPad held sideways, where Android shows one.

/// Whether two pages show side by side: a wide window (an iPad held sideways,
/// a Mac window) at least `minimumAspect` times as wide as tall, which two
/// portrait comic pages (1.3 times as wide as tall together) nearly fill.
public enum ComicSpreads {
    public static let minimumAspect = 1.25

    public static func shown(width: Double, height: Double, wide: Bool) -> Bool {
        wide && width > 0 && height > 0 && width / height >= minimumAspect
    }
}

/// The issue as the reader steps through it: a page at a time, or spreads.
public struct ComicUnits: Equatable, Sendable {
    public struct Unit: Equatable, Sendable {
        /// The pages as they stand on screen, left to right.
        public var onScreen: [Int]
        /// The pages in the order they are read.
        public var reading: [Int]

        public init(onScreen: [Int], reading: [Int]) {
            self.onScreen = onScreen
            self.reading = reading
        }

        /// The page kept as the place: the last one shown, so the last spread
        /// finishes the issue, and the same spread opens again.
        public var place: Int { reading.max() ?? 0 }
    }

    public let units: [Unit]
    public let pageCount: Int

    public init(units: [Unit], pageCount: Int) {
        self.units = units.isEmpty ? [Unit(onScreen: [0], reading: [0])] : units
        self.pageCount = max(1, pageCount)
    }

    /// One page at a time.
    public static func single(pageCount: Int) -> ComicUnits {
        let count = max(1, pageCount)
        return ComicUnits(units: (0..<count).map { Unit(onScreen: [$0], reading: [$0]) }, pageCount: count)
    }

    /// Two side by side where `SpreadPlanner` pairs them: the cover alone, a
    /// wide page alone, the rest in pairs. A page of unknown size counts as
    /// an upright one.
    public static func spreads(_ pages: [PageDimension], pageCount: Int, direction: PageDirection) -> ComicUnits {
        let count = max(1, pageCount)
        let dimensions = (0..<count).map { index in
            pages.first { $0.index == index } ?? PageDimension(index: index, width: 0, height: 0)
        }
        let units = SpreadPlanner.plan(dimensions, direction: direction).map { spread in
            Unit(onScreen: [spread.leftPage, spread.rightPage].compactMap { $0 }, reading: spread.readingOrder)
        }
        return ComicUnits(units: units, pageCount: count)
    }

    /// How many pages one unit may hold.
    public var widest: Int { units.map(\.onScreen.count).max() ?? 1 }

    /// The unit showing `page`.
    public func unit(containing page: Int) -> Int {
        let clamped = min(max(page, 0), pageCount - 1)
        return units.firstIndex { $0.reading.contains(clamped) }
            ?? units.indices.min { abs((units[$0].reading.first ?? 0) - clamped) < abs((units[$1].reading.first ?? 0) - clamped) }
            ?? 0
    }

    /// The pages to keep decoded round `unit` (`PageSlots`): its own, then
    /// the next unit's the way you are reading, then the one before's.
    public func wantedPages(around unit: Int, forward: Bool) -> [Int] {
        PageSlots.wanted(current: unit, pageCount: units.count, forward: forward).flatMap { units[$0].reading }
    }

    /// Slots enough for three units.
    public var slotCount: Int { PageSlots.count * widest }
}

/// A unit's pages side by side at one height: where each sits in the unit's
/// pixels, the unit's size, and its content inside the paper.
public struct ComicUnitLayout: Equatable, Sendable {
    public struct Placed: Equatable, Sendable {
        public var page: Int
        public var x: Double
        public var y: Double
        public var width: Double
        public var height: Double
    }

    public struct Page: Sendable {
        public var page: Int
        public var width: Double
        public var height: Double
        public var content: PageContent

        public init(page: Int, width: Double, height: Double, content: PageContent = .whole) {
            self.page = page
            self.width = width
            self.height = height
            self.content = content
        }
    }

    public var placed: [Placed]
    public var width: Double
    public var height: Double
    /// From the left of the first page's content to the right of the last's,
    /// and the highest top and lowest bottom between them.
    public var content: PageContent

    /// `pages` left to right, each scaled to the tallest's height.
    public static func of(_ pages: [Page]) -> ComicUnitLayout {
        let shown = pages.filter { $0.width > 0 && $0.height > 0 }
        guard !shown.isEmpty, shown.count == pages.count else {
            return ComicUnitLayout(placed: [], width: 0, height: 0, content: .whole)
        }
        let height = shown.map(\.height).max() ?? 0
        var x = 0.0
        var placed: [Placed] = []
        for page in shown {
            let width = page.width * height / page.height
            placed.append(Placed(page: page.page, x: x, y: 0, width: width, height: height))
            x += width
        }
        guard let first = shown.first, let last = shown.last, let firstPlace = placed.first, let lastPlace = placed.last,
              x > 0 else {
            return ComicUnitLayout(placed: placed, width: x, height: height, content: .whole)
        }
        let content = PageContent(
            left: (firstPlace.x + first.content.left * firstPlace.width) / x,
            top: shown.map(\.content.top).min() ?? 0,
            right: (lastPlace.x + last.content.right * lastPlace.width) / x,
            bottom: shown.map(\.content.bottom).max() ?? 1)
        return ComicUnitLayout(placed: placed, width: x, height: height, content: content)
    }
}

/// Where the view looks: how large (points per pixel of the unit) and the
/// middle of the view on the unit, in its pixels. SubsamplingScaleImageView's
/// `scale` and `center`, which Android's reader places by.
public struct ComicCamera: Equatable, Sendable {
    public var scale: Double
    public var x: Double
    public var y: Double

    public init(scale: Double, x: Double, y: Double) {
        self.scale = scale
        self.x = x
        self.y = y
    }
}

/// A unit in the view: how large it reads at each fit, where a step or a kept
/// zoom puts it, and how far it may move. Android's `placement`,
/// `wholeScale`, `fitWidthScale`, `panWithinPage`, `wrapReadingFlow`, `glide`
/// and `magnify`, with SubsamplingScaleImageView's limits: the whole unit
/// fits at the smallest, and it never pulls away from the view's edges.
public struct ComicFrame: Equatable, Sendable {
    /// The unit, in its pixels.
    public var width: Double
    public var height: Double
    /// The view, in points.
    public var viewWidth: Double
    public var viewHeight: Double
    public var content: PageContent
    /// Pixels in a point on this screen, for the closest look.
    public var screenScale: Double

    public init(width: Double, height: Double, viewWidth: Double, viewHeight: Double, content: PageContent = .whole,
                screenScale: Double = 2) {
        self.width = width
        self.height = height
        self.viewWidth = viewWidth
        self.viewHeight = viewHeight
        self.content = content
        self.screenScale = screenScale
    }

    /// Before a page's size is known, a comic page's three.
    public static let defaultSteps = 3
    /// The right stick at full push: screens a second.
    public static let glideRate = 1.2
    /// L3 held: how much closer.
    public static let magnify = 2.0
    /// A double tap: how far past the fit.
    public static let doubleTapZoom = 2.5

    private var ready: Bool { width > 0 && height > 0 && viewWidth > 0 && viewHeight > 0 }

    /// The whole unit in the view.
    public var minScale: Double { ready ? min(viewWidth / width, viewHeight / height) : 1 }

    /// Android's six screen pixels to a page pixel, and never less than
    /// two and a half times the fit.
    public var maxScale: Double {
        let fit = ready ? viewWidth / (width * max(content.width, 0.01)) : 1
        return max(6 / max(1, screenScale), 2.5 * max(minScale, fit))
    }

    public func clampScale(_ scale: Double) -> Double { min(maxScale, max(minScale, scale)) }

    /// The fit's own scale for the whole page: its content's, while trimmed.
    public var wholeScale: Double {
        guard ready, content.trimmed else { return minScale }
        return clampScale(min(viewWidth / (width * content.width), viewHeight / (height * content.height)))
    }

    /// The content's width across the view.
    public var fitWidthScale: Double {
        guard ready else { return minScale }
        return clampScale(viewWidth / (width * content.width))
    }

    public func baseScale(_ fit: ComicFit) -> Double { fit == .whole ? wholeScale : fitWidthScale }

    /// The steps down the content when it is read in thirds.
    public var steps: [NormalizedViewport] {
        guard ready else { return [.whole] }
        return ViewportStepPlanner.fitWidth(pageWidth: width * content.width, pageHeight: height * content.height,
                                            viewWidth: viewWidth, viewHeight: viewHeight)
    }

    /// Past the fit: a drag pans rather than turns.
    public func isZoomed(_ camera: ComicCamera, fit: ComicFit) -> Bool {
        camera.scale > baseScale(fit) * (1 + ComicZoom.close)
    }

    /// Where the unit sits by the zoom kept, else the fit and `step`: at the
    /// top, or `atEnd` at the bottom (the last step) for a unit reached going back.
    public func placement(fit: ComicFit, step: Int, atEnd: Bool, zoom: ComicZoom) -> ComicCamera {
        guard ready else { return ComicCamera(scale: 1, x: 0, y: 0) }
        if zoom.active {
            let scale = zoom.scaleFor(base: baseScale(fit), min: minScale, max: maxScale)
            let center = zoom.center(pageWidth: width, pageHeight: height, visibleWidth: viewWidth / scale,
                                     visibleHeight: viewHeight / scale, atEnd: atEnd)
            return clamp(ComicCamera(scale: scale, x: center.x, y: center.y))
        }
        // The content is the page (C5): its own box inside the paper, the whole page when untrimmed.
        let left = content.left * width
        let top = content.top * height
        let contentWidth = content.width * width
        let contentHeight = content.height * height
        let middleX = left + contentWidth / 2
        switch fit {
        case .whole:
            return clamp(ComicCamera(scale: wholeScale, x: middleX, y: top + contentHeight / 2))
        case .width:
            let scale = fitWidthScale
            let visible = viewHeight / scale
            let y = visible >= contentHeight ? top + contentHeight / 2
                : atEnd ? top + contentHeight - visible / 2 : top + visible / 2
            return clamp(ComicCamera(scale: scale, x: middleX, y: y))
        case .thirds:
            let steps = self.steps
            let index = atEnd ? steps.count - 1 : min(max(step, 0), steps.count - 1)
            let middle = (steps[index].top + steps[index].bottom) / 2
            return clamp(ComicCamera(scale: fitWidthScale, x: middleX, y: top + middle * contentHeight))
        }
    }

    /// Within the limits: no smaller than the whole unit, no closer than
    /// `maxScale`, centred where it is smaller than the view, and its edges
    /// never inside the view's where it is larger.
    public func clamp(_ camera: ComicCamera) -> ComicCamera {
        guard ready else { return camera }
        let scale = clampScale(camera.scale)
        let visibleWidth = viewWidth / scale
        let visibleHeight = viewHeight / scale
        let x = visibleWidth >= width ? width / 2 : min(width - visibleWidth / 2, max(visibleWidth / 2, camera.x))
        let y = visibleHeight >= height ? height / 2 : min(height - visibleHeight / 2, max(visibleHeight / 2, camera.y))
        return ComicCamera(scale: scale, x: x, y: y)
    }

    /// The step whose middle is nearest the middle of the view: where a zoom
    /// ending in thirds goes back to.
    public func nearestStep(_ camera: ComicCamera) -> Int {
        let steps = self.steps
        guard ready, content.height > 0 else { return 0 }
        let y = (camera.y / height - content.top) / content.height
        func distance(_ index: Int) -> Double { abs((steps[index].top + steps[index].bottom) / 2 - y) }
        return steps.indices.min { distance($0) < distance($1) } ?? 0
    }

    /// The part of the unit on screen, as fractions of it: the page map's frame.
    public func visible(_ camera: ComicCamera) -> NormalizedViewport {
        guard ready, camera.scale > 0 else { return .whole }
        let halfWidth = viewWidth / camera.scale / 2
        let halfHeight = viewHeight / camera.scale / 2
        return NormalizedViewport(left: (camera.x - halfWidth) / width, top: (camera.y - halfHeight) / height,
                                  right: (camera.x + halfWidth) / width, bottom: (camera.y + halfHeight) / height)
    }

    /// A screen's step across the page, or nil at its edge (the D-pad): over
    /// the content at the fit, over the whole unit zoomed in.
    public func pan(_ direction: PadDirection, from camera: ComicCamera, zoomed: Bool) -> ComicCamera? {
        guard ready, camera.scale > 0 else { return nil }
        let area = zoomed ? PageContent.whole : content
        let sign = direction == .left || direction == .up ? -1 : 1
        if direction == .left || direction == .right {
            guard let x = ComicPanPolicy.step(center: camera.x, sourceSize: width * area.width,
                                              visibleSize: viewWidth / camera.scale, direction: sign,
                                              from: width * area.left) else { return nil }
            return clamp(ComicCamera(scale: camera.scale, x: x, y: camera.y))
        }
        guard let y = ComicPanPolicy.step(center: camera.y, sourceSize: height * area.height,
                                          visibleSize: viewHeight / camera.scale, direction: sign,
                                          from: height * area.top) else { return nil }
        return clamp(ComicCamera(scale: camera.scale, x: camera.x, y: y))
    }

    /// At the end of a row (a wide unit) or a column (a tall one) when both
    /// ways overflow: the start of the next, so a zoomed spread is read whole.
    public func wrap(forward: Bool, rtl: Bool, from camera: ComicCamera) -> ComicCamera? {
        guard ready, camera.scale > 0 else { return nil }
        let visibleWidth = viewWidth / camera.scale
        let visibleHeight = viewHeight / camera.scale
        if width > height {
            guard let y = ComicPanPolicy.step(center: camera.y, sourceSize: height, visibleSize: visibleHeight,
                                              direction: forward ? 1 : -1) else { return nil }
            let x = ComicPanPolicy.edge(sourceSize: width, visibleSize: visibleWidth, high: forward == rtl)
            return clamp(ComicCamera(scale: camera.scale, x: x, y: y))
        }
        guard let x = ComicPanPolicy.step(center: camera.x, sourceSize: width, visibleSize: visibleWidth,
                                          direction: forward == rtl ? -1 : 1) else { return nil }
        let y = ComicPanPolicy.edge(sourceSize: height, visibleSize: visibleHeight, high: !forward)
        return clamp(ComicCamera(scale: camera.scale, x: x, y: y))
    }

    /// Ⓐ and Ⓑ at a fit other than thirds, or zoomed in: on across a wide
    /// unit the way it reads, or down a tall one, then the next row or column;
    /// nil when the unit is read and the page should turn.
    public func readingFlow(forward: Bool, rtl: Bool, from camera: ComicCamera, zoomed: Bool) -> ComicCamera? {
        let first: PadDirection = width > height ? (forward == rtl ? .left : .right) : (forward ? .down : .up)
        return pan(first, from: camera, zoomed: zoomed) ?? wrap(forward: forward, rtl: rtl, from: camera)
    }

    /// The right stick: `dx` and `dy` are stick-seconds, at most `glideRate` screens a second.
    public func glide(dx: Double, dy: Double, from camera: ComicCamera) -> ComicCamera {
        guard ready, camera.scale > 0 else { return camera }
        return clamp(ComicCamera(scale: camera.scale, x: camera.x + dx * Self.glideRate * viewWidth / camera.scale,
                                 y: camera.y + dy * Self.glideRate * viewHeight / camera.scale))
    }

    /// The zoom keys and buttons: closer or further round the middle of the view.
    public func zoomed(by factor: Double, from camera: ComicCamera) -> ComicCamera {
        clamp(ComicCamera(scale: camera.scale * factor, x: camera.x, y: camera.y))
    }

    /// The unit's point under `x`, `y` in the view.
    public func point(atX x: Double, y: Double, camera: ComicCamera) -> (x: Double, y: Double) {
        guard camera.scale > 0 else { return (camera.x, camera.y) }
        return (camera.x + (x - viewWidth / 2) / camera.scale, camera.y + (y - viewHeight / 2) / camera.scale)
    }

    /// A pinch that has scaled `magnification` times about `anchorX`, `anchorY`
    /// in the view since it began at `start`: what was under the fingers stays there.
    public func pinched(_ start: ComicCamera, magnification: Double, anchorX: Double, anchorY: Double) -> ComicCamera {
        guard ready, start.scale > 0 else { return start }
        let held = point(atX: anchorX, y: anchorY, camera: start)
        let scale = clampScale(start.scale * magnification)
        return clamp(ComicCamera(scale: scale, x: held.x - (anchorX - viewWidth / 2) / scale,
                                 y: held.y - (anchorY - viewHeight / 2) / scale))
    }

    /// A finger dragged `dx`, `dy` points since `start`: the unit follows it.
    public func dragged(_ start: ComicCamera, dx: Double, dy: Double) -> ComicCamera {
        guard start.scale > 0 else { return start }
        return clamp(ComicCamera(scale: start.scale, x: start.x - dx / start.scale, y: start.y - dy / start.scale))
    }

    /// A double tap at `x`, `y` in the view, the fit not zoomed: `doubleTapZoom`
    /// times the fit round the point tapped. (Zoomed, a double tap goes back to the fit.)
    public func doubleTapped(atX x: Double, y: Double, camera: ComicCamera, fit: ComicFit) -> ComicCamera {
        let target = point(atX: x, y: y, camera: camera)
        return clamp(ComicCamera(scale: clampScale(baseScale(fit) * Self.doubleTapZoom), x: target.x, y: target.y))
    }
}
