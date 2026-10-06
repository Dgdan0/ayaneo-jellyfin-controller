import HubKit
import SwiftUI

/// The reader's pictures (#16, C3 to C5): the units the issue is read in, each
/// unit's pages laid side by side, the pages decoded round the one shown
/// (`PageSlots`), and the paper round each found on its thumbnail
/// (`PageBounds`).
extension ComicReaderModel {
    /// The largest a page is decoded: a 1988 x 3056 scan whole, a larger one
    /// to 4096 on its long side, which still reads at the closest zoom.
    nonisolated static let pageMaxPixels = 4_096

    // MARK: Units

    /// Two pages side by side in a wide window held sideways (`ComicSpreads`), one at a time otherwise.
    var spreadsShown: Bool {
        ComicSpreads.shown(width: viewSize.width, height: viewSize.height, wide: ShellLayout.isWide(width: viewSize.width))
    }

    func makeUnits(for manifest: ReadingPublicationManifest) -> ComicUnits {
        guard spreadsShown else { return .single(pageCount: manifest.pageCount) }
        let dimensions = (0..<manifest.pageCount).map { page -> PageDimension in
            let key = PageKey(manifest.sourceItemId, page)
            if let size = measured[key] {
                return PageDimension(index: page, width: Int(size.width), height: Int(size.height))
            }
            return manifest.pages.first { $0.index == page }?.dimension ?? PageDimension(index: page, width: 0, height: 0)
        }
        return .spreads(dimensions, pageCount: manifest.pageCount, direction: manifest.pageDirection(chosen: view.direction))
    }

    /// A page's size: as it decoded, else as the manifest gives it.
    func pageSize(_ key: PageKey) -> CGSize? {
        if let size = measured[key] { return size }
        guard key.publication == manifest?.sourceItemId,
              let page = manifest?.pages.first(where: { $0.index == key.page }), page.width > 0, page.height > 0 else { return nil }
        return CGSize(width: page.width, height: page.height)
    }

    /// The content inside a page's paper, while Trim margins is on and its thumbnail has been looked at.
    func content(_ key: PageKey) -> PageContent {
        view.trim ? contents[key] ?? .whole : .whole
    }

    /// `pages` of `publication` side by side, when every one's size is known.
    func layout(pages: [Int], publication: String) -> ComicUnitLayout? {
        var parts: [ComicUnitLayout.Page] = []
        for page in pages {
            let key = PageKey(publication, page)
            guard let size = pageSize(key) else { return nil }
            parts.append(ComicUnitLayout.Page(page: page, width: size.width, height: size.height, content: content(key)))
        }
        let layout = ComicUnitLayout.of(parts)
        return layout.placed.isEmpty ? nil : layout
    }

    func frame(for layout: ComicUnitLayout) -> ComicFrame? {
        guard viewSize.width > 0, viewSize.height > 0 else { return nil }
        return ComicFrame(width: layout.width, height: layout.height, viewWidth: viewSize.width, viewHeight: viewSize.height,
                          content: view.trim ? layout.content : .whole, screenScale: screenScale)
    }

    func frame(unit: Int) -> ComicFrame? {
        guard let manifest, units.units.indices.contains(unit),
              let layout = layout(pages: units.units[unit].onScreen, publication: manifest.sourceItemId) else { return nil }
        return frame(for: layout)
    }

    /// The unit on screen in the view, its content as now known.
    var shownFrame: ComicFrame? {
        guard let shown, let layout = layout(pages: shown.unit.onScreen, publication: shown.publication) else { return nil }
        return frame(for: layout)
    }

    /// The view changed shape, a page decoded at another size, or the series
    /// reads another way: the units again, the same page shown, placed anew.
    func reshapeKeepingPage() {
        guard let manifest else { return }
        let page = currentPage
        let next = makeUnits(for: manifest)
        if next != units {
            units = next
            slots = Array(repeating: nil, count: units.slotCount)
            state = PagedImageState(pageCount: units.units.count, startPage: units.unit(containing: page), stepsFor: stepsFor)
        } else {
            state.refit()
        }
        let unit = units.units[state.pageIndex]
        if let shown, shown.publication == manifest.sourceItemId, shown.unit == unit,
           let layout = layout(pages: unit.onScreen, publication: manifest.sourceItemId) {
            self.shown = Shown(publication: shown.publication, unit: unit, layout: layout)
            placeStep(animated: false)
            planNeighbours()
        } else {
            loadUnit()
        }
    }

    // MARK: Showing a unit

    /// Shows the state's unit: at once when its pages are decoded, else as
    /// soon as they are, the unit before staying on screen until then rather
    /// than going black (C3).
    func loadUnit() {
        guard let manifest, phase == .reading else { return }
        let unit = state.pageIndex
        let keys = units.units[unit].onScreen.map { PageKey(manifest.sourceItemId, $0) }
        magnify(false)
        planNeighbours()
        if keys.allSatisfy({ images[$0] != nil }) {
            reveal(unit)
        } else {
            pendingUnit = unit
            waited(for: unit)
        }
    }

    /// The unit asked for, decoded: shown, placed as it opens, its place kept.
    func reveal(_ unit: Int) {
        guard let manifest, units.units.indices.contains(unit),
              let layout = layout(pages: units.units[unit].onScreen, publication: manifest.sourceItemId) else { return }
        pendingUnit = nil
        waited(for: nil)
        magnified = nil
        shown = Shown(publication: manifest.sourceItemId, unit: units.units[unit], layout: layout)
        // The decoded sizes outrank the manifest's: the steps may change, and
        // a unit reached going back still opens on its last one.
        let atEnd = arriveAtEnd
        arriveAtEnd = false
        if atEnd { state.jump(page: unit, step: .max) } else { state.refit() }
        if let frame = shownFrame {
            look(at: frame.placement(fit: view.fit, step: state.viewportIndex, atEnd: atEnd, zoom: zoom), animated: false)
        }
        savePlace()
        flashMap()
        planNeighbours()
        pruneImages()
    }

    // MARK: Decoding ahead (#16, C3)

    /// The pages either side, decoded behind the one shown (`PageSlots`): the
    /// next the way you are reading first. A slot already holding one keeps
    /// it; the rest take what is missing. The unit after those is fetched as
    /// bytes only, so its decode starts from the disk.
    func planNeighbours() {
        guard let manifest, phase == .reading else { return }
        let wanted = units.wantedPages(around: state.pageIndex, forward: readingForward)
            .map { PageKey(manifest.sourceItemId, $0) }
        if slots.count != units.slotCount { slots = Array(repeating: nil, count: units.slotCount) }
        slots = PageSlots.assign(held: slots, wanted: wanted)
        for key in wanted {
            if images[key] == nil && loads[key] == nil { load(key) }
            measure(key)
        }
        let beyond = state.pageIndex + (readingForward ? 2 : -2)
        if units.units.indices.contains(beyond) {
            for page in units.units[beyond].onScreen {
                let path = HubEndpoints.readingPublicationPage(workId: workId, sourceItemId: manifest.sourceItemId, page: page)
                Task.detached(priority: .utility) { [hub] in _ = try? await hub.image(path) }
            }
        }
    }

    /// Pictures neither in a slot nor on screen are let go.
    func pruneImages() {
        let kept = Set(slots.compactMap { $0 })
            .union(shown.map { shown in shown.unit.onScreen.map { PageKey(shown.publication, $0) } } ?? [])
        for key in images.keys where !kept.contains(key) { images[key] = nil }
        for (key, task) in loads where !kept.contains(key) {
            task.cancel()
            loads[key] = nil
        }
    }

    private func load(_ key: PageKey) {
        let path = HubEndpoints.readingPublicationPage(workId: workId, sourceItemId: key.publication, page: key.page)
        loads[key] = Task { [weak self, hub] in
            let data: Data
            do throws(HubFailure) {
                data = try await hub.image(path)
            } catch {
                guard !Task.isCancelled, let self else { return }
                self.loads[key] = nil
                if self.pendingUnitHolds(key) {
                    self.phase = .failed("Page \(key.page + 1) could not be loaded. \(error.message)")
                }
                return
            }
            let decoded = await Task.detached(priority: .userInitiated) {
                decodeArtwork(data, maxPixels: Self.pageMaxPixels)
            }.value
            guard !Task.isCancelled, let self else { return }
            self.loads[key] = nil
            guard let decoded else {
                if self.pendingUnitHolds(key) { self.phase = .failed("Page \(key.page + 1) could not be decoded") }
                return
            }
            self.decoded(key, decoded)
        }
    }

    private func pendingUnitHolds(_ key: PageKey) -> Bool {
        guard let unit = pendingUnit, units.units.indices.contains(unit), key.publication == manifest?.sourceItemId else {
            return false
        }
        return units.units[unit].onScreen.contains(key.page)
    }

    private func decoded(_ key: PageKey, _ image: DecodedArtwork) {
        guard key.publication == manifest?.sourceItemId else { return }
        images[key] = image
        let size = CGSize(width: image.image.width, height: image.image.height)
        let before = pageSize(key)
        measured[key] = size
        // A page that turns out wide, or upright, pairs differently.
        if spreadsShown, let before, (before.width > before.height) != (size.width > size.height) {
            return reshapeKeepingPage()
        }
        if let unit = pendingUnit, units.units.indices.contains(unit),
           units.units[unit].onScreen.allSatisfy({ images[PageKey(key.publication, $0)] != nil }) {
            reveal(unit)
        }
    }

    // MARK: The paper round a page (#18, C5)

    /// Looks at `key`'s page on the hub's thumbnail, `PageBounds.thumbWidth`
    /// across (a few kilobytes, never the scan), for the paper round it. The
    /// page on screen is placed again once its content is known, unless it
    /// was moved or zoomed meanwhile.
    func measure(_ key: PageKey) {
        guard view.trim, contents[key] == nil, measuring[key] == nil else { return }
        let path = HubEndpoints.readingPublicationThumb(workId: workId, sourceItemId: key.publication, page: key.page,
                                                        width: PageBounds.thumbWidth)
        measuring[key] = Task { [weak self, hub] in
            let data = try? await hub.image(path)
            let found = await Task.detached(priority: .utility) {
                data.flatMap { PageBounds.content(ofThumbnail: $0) }
            }.value
            guard !Task.isCancelled, let self else { return }
            self.measuring[key] = nil
            self.contents[key] = found ?? .whole
            guard let found, found.trimmed, let shown = self.shown, shown.publication == key.publication,
                  shown.unit.onScreen.contains(key.page), !self.zoom.active, self.magnified == nil,
                  self.gestureIsIdle else { return }
            self.state.refit()
            self.placeStep(animated: true)
        }
    }
}
