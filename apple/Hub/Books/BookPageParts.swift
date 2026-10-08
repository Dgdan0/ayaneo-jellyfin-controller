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
                    .padFocusable("\(star)", ring: .circle) { rate(star) }
                }
            }
            .padGroup("stars", .row, members: (1...5).map { "\($0)" })
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
                    .padFocusable(format.kind, ring: .rounded(8)) { open(format) }
                }
            }
            .padding(.horizontal, 2)
        }
        .scrollClipDisabled()
        .padGroup("formats", .row, members: formats.map(\.kind), strip: true)
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
    /// A controller's Ⓐ on the month or the year: their choices (#46).
    @State private var choosing: Part?
    private let now = BookPage.FinishDate.current()

    enum Part: String, Identifiable {
        case month, year
        var id: String { rawValue }
    }

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
                    .padFocusable("month", scrolls: false) { choosing = .month }
                    Picker("Year", selection: $date.year) {
                        ForEach(BookPage.FinishDate.years(now: now), id: \.self) { year in
                            Text(String(year)).tag(year)
                        }
                    }
                    .accessibilityIdentifier("finish-year")
                    .padFocusable("year", scrolls: false) { choosing = .year }
                }
                .pickerStyle(.menu)
                .labelsHidden()
                #if os(iOS)
                .tint(.white)
                #endif
                // The answers are the app's pills, the harmless one first: a Mac
                // sheet's toolbar is the window's own (white boxes on the glass),
                // and a controller reaches what is in the sheet, not its bar (#46).
                // On the iPad and iPhone Return presses what the ring is on, and
                // Escape is the sheet's Back (`padPage`), so neither is a shortcut there.
                HStack(spacing: 10) {
                    Button("Cancel") { dismiss() }
                        .buttonStyle(GlassPillStyle())
                        #if os(macOS)
                        .keyboardShortcut(.cancelAction)
                        #endif
                        .padFocusable("cancel", scrolls: false) { dismiss() }
                    Button("Mark finished") { markFinished() }
                        .buttonStyle(PrimaryPillStyle())
                        #if os(macOS)
                        .keyboardShortcut(.defaultAction)
                        #endif
                        .accessibilityIdentifier("finish-confirm")
                        .padFocusable("finish", scrolls: false) { markFinished() }
                }
                .padding(.top, 6)
            }
            .padding(.top, 18)
            .frame(maxWidth: .infinity)
            .frame(maxHeight: .infinity, alignment: .top)
            #if os(iOS)
            .toolbar(.hidden, for: .navigationBar)
            #endif
        }
        .onChange(of: date.year) { _, _ in date = date.clamped(to: now) }
        .padPage("finished", modal: true) { dismiss() }
        .confirmationDialog(choosing == .year ? "Year" : "Month", isPresented: Binding(
            get: { choosing != nil }, set: { if !$0 { choosing = nil } }), presenting: choosing) { part in
            switch part {
            case .month:
                ForEach(BookPage.FinishDate.months(in: date.year, now: now), id: \.self) { month in
                    Button(BookPage.months[month - 1]) { date.month = month }
                }
            case .year:
                ForEach(BookPage.FinishDate.years(now: now), id: \.self) { year in
                    Button(String(year)) { date.year = year }
                }
            }
        }
        // The app's sheets are glass (the request form, the profiles).
        .presentationBackground { GlassSheetFill() }
        .presentationDetents([.height(250)])
        #if os(macOS)
        .frame(minWidth: 360, minHeight: 170)
        #endif
    }

    private func markFinished() {
        finish(date.clamped(to: now))
        dismiss()
    }
}
