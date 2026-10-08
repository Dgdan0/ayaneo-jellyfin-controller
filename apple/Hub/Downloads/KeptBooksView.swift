import HubKit
import SwiftUI

/// The Books side's Downloads (#43): the books, audiobooks and comics this
/// device keeps to read and hear offline (#37's caches, listed by
/// `ReadingKeptShelf`), newest first, each with what is kept and its room.
/// One opens its page; Remove offline copy asks first, as on the book's page.
/// A book the system has since cleared from the caches leaves the list.
struct KeptBooksView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.glassMetrics) private var metrics
    @Environment(\.openRoute) private var openRoute
    /// What is kept and its room, for the page's line.
    @Binding var summary: String

    @State private var books: [ReadingKeptBook] = []
    @State private var sizes: [String: Int64] = [:]
    @State private var loaded = false
    @State private var removing: ReadingKeptBook?
    @State private var reloads = 0

    /// The books with something still on this device (their room not yet known counts).
    private var shown: [ReadingKeptBook] { books.filter { (sizes[$0.workId] ?? 1) > 0 } }

    var body: some View {
        Group {
            if loaded && shown.isEmpty {
                GlassEmpty(title: "No books kept yet",
                           detail: "A book you read or hear is kept here to read offline: ebooks, audiobooks and the comics you open.")
                    .padding(.horizontal, metrics.margin)
                    .padding(.top, 18)
            } else {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: metrics.poster, maximum: metrics.poster * 1.4),
                                             spacing: metrics.gap, alignment: .top)],
                          alignment: .leading, spacing: metrics.gap) {
                    ForEach(shown) { book in
                        Button {
                            openRoute(.book(BookRoute(workId: book.workId, title: book.title)))
                        } label: {
                            VStack(alignment: .leading, spacing: 8) {
                                BookCover(path: book.artwork, square: book.isAudiobookOnly, width: 360)
                                    .litArtwork(corner: 9)
                                CardCaption(title: book.title, detail: caption(book))
                            }
                            .contentShape(Rectangle())
                            .accessibilityElement(children: .combine)
                        }
                        .buttonStyle(GlassCardStyle())
                        .accessibilityIdentifier("kept-book-" + book.workId)
                        .contextMenu {
                            Button(role: .destructive) {
                                removing = book
                            } label: {
                                Label("Remove offline copy", systemImage: "trash")
                            }
                        }
                        // Ⓨ is the hold's Remove offline copy (#46).
                        .padFocusable(book.workId, ring: .card, hold: { removing = book }) {
                            openRoute(.book(BookRoute(workId: book.workId, title: book.title)))
                        }
                    }
                }
                .padGroup("kept", .grid(columns: 0), members: shown.map(\.workId))
                .padding(.horizontal, metrics.margin)
                .padding(.top, 20)
                SheetNote(text: "Kept in this device's caches: the system may clear them when the device is short of room. "
                          + "Hold a book for Remove offline copy.")
                    .padding(.horizontal, metrics.margin)
                    .padding(.top, 14)
            }
        }
        .task(id: "\(model.address)·\(model.userId)·\(reloads)") { await load() }
        .alert("Remove offline copy?", isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }),
               presenting: removing) { book in
            // The harmless answer in the cancel role: without one, iOS 26 adds a Cancel of its own.
            Button("Keep offline copy", role: .cancel) { removing = nil }
            Button("Remove from this device", role: .destructive) {
                removing = nil
                Task {
                    await ReadingOffline.remove(workId: book.workId, sourceItemIds: book.sourceItemIds, app: model)
                    reloads += 1
                }
            }
        } message: { book in
            Text("\(book.title) · \(Fmt.bytes(sizes[book.workId] ?? 0)) on this device. Removes downloaded text, audio and cached comic pages for this title. Server files, bookmarks and reading progress are kept.")
        }
    }

    /// "Ebook · Audiobook · 120 MB".
    private func caption(_ book: ReadingKeptBook) -> String {
        [book.formats, sizes[book.workId].map(Fmt.bytes) ?? ""].filter { !$0.isEmpty }.joined(separator: " · ")
    }

    /// The shelf, then each book's room; a book with nothing left here is forgotten.
    private func load() async {
        let shelf = ReadingOffline.shelf(app: model)
        books = shelf.list()
        var found: [String: Int64] = [:]
        for book in books {
            let bytes = await ReadingOffline.bytes(workId: book.workId, sourceItemIds: book.sourceItemIds, app: model)
            found[book.workId] = bytes
            if bytes == 0 { shelf.remove(workId: book.workId) }
        }
        sizes = found
        loaded = true
        let kept = shown
        let total = kept.reduce(0) { $0 + (found[$1.workId] ?? 0) }
        summary = (kept.count == 1 ? "1 book" : "\(kept.count) books") + " · \(Fmt.bytes(total)) on this device"
    }
}
