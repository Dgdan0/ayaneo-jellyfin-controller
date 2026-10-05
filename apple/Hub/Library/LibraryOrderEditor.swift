import HubKit
import Observation
import SwiftUI

/// A library as the arranging screens show it: its id, name and kind of
/// library, and a picture for its row.
struct ArrangedLibrary: Identifiable, Equatable {
    let id: String
    let title: String
    let kind: String
    let art: String
}

/// One side's libraries while they are arranged (#15), on the Library page or
/// in Settings: Android's `LibraryOrderEditor`. A move shows at once and saves
/// the whole order; one save is out at a time and only the newest waits
/// (`LibraryOrderQueue`). When the hub refuses, the list slides back to what
/// the hub has and says why. Back to A–Z sends an empty order and reads the
/// list again, since the hub answers `ids: []` rather than the list.
@MainActor
@Observable
final class LibraryOrderEditor {
    let side: LibrarySide
    private(set) var libraries: [ArrangedLibrary] = []
    /// The hub keeps this side in the profile's own order.
    private(set) var custom = false
    private(set) var notice = ""
    private(set) var loading = false
    private(set) var status = StatusMessage("")

    /// What the hub has confirmed, to slide back to.
    @ObservationIgnored private var confirmed: [ArrangedLibrary] = []
    /// The order last sent, or confirmed: a drag that ends where it began saves nothing.
    @ObservationIgnored private var lastSaved: [String] = []
    @ObservationIgnored private var queue = LibraryOrderQueue()

    init(side: LibrarySide) {
        self.side = side
    }

    /// The hub's list as it comes; the app never sorts it.
    func load(_ model: AppModel) async {
        guard queue.isIdle else { return }
        loading = true
        defer { loading = false }
        if libraries.isEmpty { status = StatusText.loading(side == .media ? "libraries" : "book libraries", refreshing: false) }
        do {
            switch side {
            case .media:
                let response = try await model.hub.fetch(HubEndpoints.library, as: LibraryResponse.self)
                show(response.views.map { folder in
                    ArrangedLibrary(id: folder.id, title: folder.name, kind: LibraryKind.label(folder.kind),
                                    art: folder.fan.first ?? folder.image)
                }, custom: LibraryOrder.isCustom(response.order))
            case .books:
                let response = try await model.hub.fetch(HubEndpoints.readingLibraries, as: ReadingLibrariesResponse.self)
                show(response.libraries.map { library in
                    ArrangedLibrary(id: library.id, title: library.title, kind: LibraryKind.reading(library),
                                    art: library.artwork)
                }, custom: LibraryOrder.isCustom(response.order))
            }
            status = libraries.isEmpty ? StatusMessage(side == .media ? "No Jellyfin libraries were found"
                                                                       : "No book libraries were found")
                                       : StatusMessage("")
        } catch {
            if error.kind == .cancelled { return }
            status = StatusText.failed(error.message, kind: error.kind, hasData: !libraries.isEmpty)
        }
    }

    private func show(_ list: [ArrangedLibrary], custom: Bool) {
        libraries = list
        confirmed = list
        lastSaved = list.map(\.id)
        self.custom = custom
    }

    /// A list the page has just read itself, taken as the hub's (the Library
    /// page reads the folders with their posters, and arranges those).
    func adopt(_ list: [ArrangedLibrary], custom: Bool) {
        guard queue.isIdle else { return }
        notice = ""
        show(list, custom: custom)
    }

    /// One place earlier or later: VoiceOver's actions.
    func step(_ id: String, by offset: Int, model: AppModel) {
        apply(LibraryOrder.step(libraries.map(\.id), id: id, by: offset), model: model)
    }

    /// A drag passing over another library: shown at once, saved when it is let go.
    func preview(_ id: String, over target: String) {
        let ids = LibraryOrder.move(libraries.map(\.id), id: id, over: target)
        guard ids != libraries.map(\.id) else { return }
        withAnimation(.spring(duration: 0.3)) {
            libraries = LibraryOrder.inOrder(libraries, ids: ids, id: \.id)
        }
    }

    /// Saves what a drag left on screen.
    func commit(model: AppModel) {
        let ids = libraries.map(\.id)
        guard ids != lastSaved else { return }
        save(ids, model: model)
    }

    private func apply(_ ids: [String], model: AppModel) {
        guard ids != libraries.map(\.id) else { return }
        withAnimation(.spring(duration: 0.3)) {
            libraries = LibraryOrder.inOrder(libraries, ids: ids, id: \.id)
        }
        save(ids, model: model)
    }

    private func save(_ ids: [String], model: AppModel) {
        notice = ""
        lastSaved = ids
        guard let now = queue.submit(ids) else { return }
        Task { await send(now, model: model) }
    }

    private func send(_ ids: [String], model: AppModel) async {
        do {
            let reply = try await model.hub.fetch(HubEndpoints.saveLibraryOrder(side: side, ids: ids), as: LibraryOrderReply.self)
            custom = LibraryOrder.isCustom(reply.order)
            model.libraryOrderChanged()
            let next = queue.answered(ok: true)
            if ids.isEmpty {
                // The hub answers an empty list, not the A to Z one: read it.
                if next == nil { await load(model) }
            } else {
                confirmed = LibraryOrder.inOrder(confirmed, ids: ids, id: \.id)
            }
            if let next { await send(next, model: model) }
        } catch {
            _ = queue.answered(ok: false)
            withAnimation(.spring(duration: 0.35)) { libraries = confirmed }
            lastSaved = confirmed.map(\.id)
            notice = LibraryOrder.failureNotice(error.message)
        }
    }

    /// Back to A to Z: an empty order, after which the hub's list is read again.
    func resetToName(model: AppModel) {
        save([], model: model)
    }
}

/// What kind of library a tile or a row names: Android's `LibraryTiles`
/// `kindLabel` and `readingKindLabel`.
enum LibraryKind {
    static func label(_ kind: String) -> String {
        switch kind {
        case "movies": "Movie library"
        case "tvshows": "TV library"
        case "boxsets": "Collections"
        case "homevideos": "Home videos"
        case "music": "Music library"
        default: "Library"
        }
    }

    static func reading(_ library: ReadingLibrary) -> String {
        switch library.kind {
        case "book": "Books & audio"
        case "comic": "Comics"
        case "manga": "Manga"
        case "reading_list": "Kavita"
        default: library.source == "storyteller" ? "Books & audio" : "Books"
        }
    }
}
