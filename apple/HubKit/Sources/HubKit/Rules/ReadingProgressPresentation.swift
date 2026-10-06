import Foundation

/// A book as this device has it: the places it kept and the hub has not had
/// yet, laid over the hub's answer, which may be older than them (Android's
/// `ReadingProgressPresentation`, held to its tests). A book's page and Books
/// Home show progress through this, so coming back from a reader or the
/// player shows where you are at once rather than after the next sync.
public enum ReadingProgressPresentation {
    /// `work` with the latest place of `pending` that can be told: its
    /// progress (a series keeps its own), where to carry on, and the progress
    /// of the item it is in. A place in a question (`conflicted`) never
    /// pretends to be the answer, and one for another edition changes nothing.
    ///
    /// An audiobook's place counts with the fraction kept beside it (#30): a
    /// finished book is the whole of it, and a place that says nothing of how
    /// far through the book it is (one an older build kept) leaves the hub's
    /// progress as it is, never 0%.
    public static func project(_ work: ReadingWork, pending: [ReadingCheckpoint]) -> ReadingWork {
        let items = work.sections.flatMap(\.items)
        let ids = Set(items.map(\.sourceItemId) + work.editions.map(\.sourceItemId) + [work.continueAt?.sourceItemId].compactMap { $0 })

        // Nil for a place that says nothing of how far through the book it is.
        func view(_ checkpoint: ReadingCheckpoint) -> ReadingProgress? {
            guard let local = checkpoint.local else { return nil }
            let pages = items.first { $0.sourceItemId == checkpoint.key.sourceItemId }?.pageCount
                ?? work.editions.first { $0.sourceItemId == checkpoint.key.sourceItemId }?.pageCount ?? 0
            let fraction: Double
            if checkpoint.key.kind == AudioPlace.kind {
                guard let heard = AudioPlace.progressOf(local) else { return nil }
                fraction = heard
            } else if let total = local.locator?["locations"]?["totalProgression"]?.doubleValue {
                fraction = total
            } else if pages > 0, let page = local.pageIndex {
                fraction = Double(page + 1) / Double(pages)
            } else {
                fraction = 0
            }
            return ReadingProgress(percentage: min(max(fraction, 0), 1), completed: fraction >= 1,
                                   current: local.pageIndex.map { $0 + 1 } ?? 0, total: pages)
        }

        let relevant = pending
            .filter { $0.pending && !$0.conflicted && ids.contains($0.key.sourceItemId) && $0.local != nil }
            .compactMap { checkpoint in view(checkpoint).map { (checkpoint, $0) } }
        // The latest that can be told; of two at once, the first.
        guard let (latest, position) = relevant.max(by: { $0.0.updatedAt < $1.0.updatedAt }) else { return work }
        let item = items.first { $0.sourceItemId == latest.key.sourceItemId }
        let edition = work.editions.first { $0.sourceItemId == latest.key.sourceItemId }
        var shown = work
        if work.entityType != "collection" { shown.progress = position }
        shown.continueAt = ReadingContinue(
            workId: latest.key.workId,
            source: edition?.source ?? (latest.key.kind == "epub" ? "storyteller" : "kavita"),
            sourceItemId: latest.key.sourceItemId, title: item?.title ?? work.title, number: item?.number ?? "",
            percentage: position.percentage, artwork: item?.artwork ?? work.artwork, kind: item?.kind ?? work.kind)
        shown.sections = work.sections.map { section in
            var section = section
            section.items = section.items.map { entry in
                guard let place = relevant.last(where: { $0.0.key.sourceItemId == entry.sourceItemId }) else { return entry }
                var entry = entry
                entry.progress = place.1
                return entry
            }
            return section
        }
        return shown
    }
}
