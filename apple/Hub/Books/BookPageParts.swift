import HubKit
import SwiftUI

// The book page's own pieces (#39, the owner's layout "1"): what is about
// you under the cover, the formats as what you can do, and the Finished panel.

/// Under the cover: your stars (a tap rates, the same star again takes the
/// rating away), "Finished Sep 2025 · 2nd time", and your shelves in the accent.
struct BookYouBlock: View {
    let you: ReadingYou?
    let rate: (Int) -> Void
    @Environment(\.glassAccent) private var accent

    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            let rating = you?.rating ?? 0
            HStack(spacing: 2) {
                ForEach(1...5, id: \.self) { star in
                    Button { rate(star) } label: {
                        Image(systemName: star <= rating ? "star.fill" : "star")
                            .font(.system(size: 19, weight: .semibold))
                            .foregroundStyle(star <= rating ? accent.tint : Color.white.opacity(0.42))
                            .frame(width: 30, height: 30)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(star == 1 ? "1 star" : "\(star) stars")
                    .accessibilityValue(star <= rating ? "Given" : "")
                    .accessibilityIdentifier("book-star-\(star)")
                }
            }
            .accessibilityElement(children: .contain)
            .accessibilityLabel(BookPage.ratingLabel(you?.rating))
            .accessibilityIdentifier("book-stars")
            if let line = BookPage.youLine(you) {
                Text(line)
                    .font(HubType.body(13.5, weight: .semibold, relativeTo: .footnote))
                    .foregroundStyle(.white.opacity(0.78))
                    .accessibilityIdentifier("book-you-line")
            }
            if let shelves = BookPage.shelves(you) {
                Text(shelves)
                    .font(HubType.body(13.5, weight: .semibold, relativeTo: .footnote))
                    .foregroundStyle(accent.tint)
                    .accessibilityLabel("Your shelves: " + shelves)
                    .accessibilityIdentifier("book-shelves")
            }
        }
    }
}

/// What you can do: Audiobook, Ebook and Read along, each an icon and its
/// name, in the accent when it opens and a quiet grey when it does not.
struct BookFormatsRow: View {
    let formats: [BookPage.Format]
    let open: (BookPage.Format) -> Void
    @Environment(\.glassAccent) private var accent

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 20) {
                ForEach(formats) { format in
                    Button { open(format) } label: {
                        Label(format.label, systemImage: Self.symbol(format.kind))
                            .font(HubType.body(15, weight: .bold, relativeTo: .subheadline))
                            .lineLimit(1)
                            .padding(.vertical, 6)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(format.opens ? accent.tint : Color.white.opacity(0.36))
                    .accessibilityLabel("\(format.label), \(format.readiness.description)")
                    .accessibilityHint(format.opens ? "Opens at your place" : "")
                    .accessibilityIdentifier("book-format-\(format.kind)")
                }
            }
        }
        .scrollClipDisabled()
    }

    static func symbol(_ kind: String) -> String {
        switch kind {
        case "audiobook": "headphones"
        case "readaloud": BookView.icon(.readAlong)
        default: "book"
        }
    }
}

/// "When did you finish?": a month and a year, this month to begin with and
/// none after it, then Mark finished (#39).
struct FinishedPanel: View {
    let finish: (BookPage.FinishDate) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var date = BookPage.FinishDate.current()
    private let now = BookPage.FinishDate.current()

    var body: some View {
        NavigationStack {
            VStack(spacing: 14) {
                // The question as a heading: between Cancel and Mark finished a phone's bar cut it off.
                Text("When did you finish?")
                    .font(HubType.heading(21, weight: .heavy, relativeTo: .title3))
                    .foregroundStyle(.white)
                    .accessibilityAddTraits(.isHeader)
                HStack(spacing: 12) {
                    Picker("Month", selection: $date.month) {
                        ForEach(BookPage.FinishDate.months(in: date.year, now: now), id: \.self) { month in
                            Text(BookPage.months[month - 1]).tag(month)
                        }
                    }
                    .accessibilityIdentifier("finish-month")
                    Picker("Year", selection: $date.year) {
                        ForEach(BookPage.FinishDate.years(now: now), id: \.self) { year in
                            Text(String(year)).tag(year)
                        }
                    }
                    .accessibilityIdentifier("finish-year")
                }
                .pickerStyle(.menu)
                .labelsHidden()
                .tint(.white)
            }
            .frame(maxWidth: .infinity)
            .frame(maxHeight: .infinity, alignment: .top)
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Mark finished") {
                        finish(date.clamped(to: now))
                        dismiss()
                    }
                    .accessibilityIdentifier("finish-confirm")
                }
            }
        }
        .onChange(of: date.year) { _, _ in date = date.clamped(to: now) }
        .presentationDetents([.height(220)])
        #if os(macOS)
        .frame(minWidth: 360, minHeight: 160)
        #endif
    }
}
