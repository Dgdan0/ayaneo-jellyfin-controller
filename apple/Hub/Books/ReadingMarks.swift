import SwiftUI

/// What the readers ask of a book's mark (#37): whether it was marked unread,
/// so its next read starts at the beginning, and that a place was kept, so
/// its own progress speaks again. The shell answers from `BooksModel`; a
/// reader opened without one (a debug launch) marks nothing.
struct ReadingMarks {
    var startsFresh: @MainActor (String) -> Bool = { _ in false }
    var kept: @MainActor (String) -> Void = { _ in }
}

extension EnvironmentValues {
    @Entry var readingMarks = ReadingMarks()
}
