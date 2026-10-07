import HubKit
import SwiftUI

/// An audiobook's screen (#25 phase 2; the Pocket's `AudiobookScreen` in
/// Glass): the square cover large beside "Audiobook · Book 6 · Red Rising",
/// the title, who wrote it and who reads it, the part playing and the time
/// left in the accent; then the glass dock with the line and its times, the
/// part steps either side of the jumps and the white Play; then Parts, Speed,
/// Sleep and Stop as glass pills. The book plays on when this page is left,
/// and the mini player brings it back.
///
/// Opening a book puts it on the player at its place, paused. Another
/// device's place is a question, and so is a place the hub only worked out
/// from a reader's page.
struct AudiobookView: View {
    let workId: String
    let sourceItemId: String
    let title: String

    @Environment(AppModel.self) private var model
    @Environment(\.glassMetrics) private var metrics
    @Environment(\.glassAccent) private var accent

    @State private var listening = ListeningModel.shared
    @State private var work: ReadingWork?
    @State private var status = StatusMessage("")
    @State private var opening: ListeningModel.Opening?
    @State private var scrub: Double?
    @State private var reloads = 0

    private var mine: Bool { listening.book?.isSame(workId: workId, sourceItemId: sourceItemId) == true }
    private var seekSeconds: Int { ListeningSettings.seekSeconds }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                head
                    .padding(.horizontal, metrics.margin)
                    .padding(.top, 8)
                dock
                    .padding(.horizontal, metrics.margin)
                    .padding(.top, 18)
                tools
                    .padding(.horizontal, metrics.margin)
                    .padding(.top, 12)
            }
            .frame(maxWidth: 900)
            .frame(maxWidth: .infinity)
            .padding(.bottom, 28)
        }
        .ambientArtwork(work?.artwork ?? "")
        .task(id: "\(workId)·\(sourceItemId)·\(reloads)") { await open() }
        .confirmationDialog(opening?.prompt?.title ?? "", isPresented: Binding(
            get: { opening?.prompt != nil }, set: { if !$0 { cancelOpening() } }), titleVisibility: .visible) {
            // The opening as it was asked: closing the sheet may clear it before a choice runs.
            if let asked = opening, let prompt = asked.prompt {
                ForEach(prompt.choices) { choice in
                    Button(choice.detail.isEmpty ? choice.label : "\(choice.label) · \(choice.detail)") {
                        answer(choice.id, to: asked)
                    }
                }
            }
            Button("Not now", role: .cancel) { cancelOpening() }
        } message: {
            Text(opening?.prompt?.message ?? "")
        }
        .confirmationDialog(conflictPrompt?.title ?? "", isPresented: Binding(
            get: { mine && listening.conflicted }, set: { if !$0 { listening.postponeConflict() } }),
            titleVisibility: .visible) {
            if let conflictPrompt {
                ForEach(conflictPrompt.choices) { choice in
                    Button(choice.detail.isEmpty ? choice.label : "\(choice.label) · \(choice.detail)") {
                        listening.resolveConflict(keepLocal: choice.id == "local")
                    }
                }
            }
            Button("Decide later", role: .cancel) { listening.postponeConflict() }
        } message: {
            Text(conflictPrompt?.message ?? "")
        }
    }

    private var conflictPrompt: ReadingResumePrompt? { mine && listening.conflicted ? listening.conflictPrompt() : nil }

    // MARK: The head

    private var head: some View {
        let layout = metrics.centred ? AnyLayout(VStackLayout(alignment: .leading, spacing: 18))
            : AnyLayout(HStackLayout(alignment: .center, spacing: metrics.short ? 22 : 30))
        return layout {
            BookCover(path: work?.artwork ?? "", square: true, width: 600)
                .frame(width: metrics.short ? 150 : metrics.centred ? 220 : 260)
                .shadow(color: .black.opacity(0.5), radius: 26, y: 24)
                .frame(maxWidth: metrics.centred ? .infinity : nil)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 8) {
                Text(ReadingBookFacts.listeningEyebrow(work).uppercased())
                    .font(HubType.body(12.5, weight: .bold, relativeTo: .caption))
                    .tracking(1.75)
                    .foregroundStyle(.white.opacity(0.72))
                Text(title)
                    .font(HubType.heading(metrics.heroTitle, weight: .heavy))
                    .tracking(-0.02 * metrics.heroTitle)
                    .foregroundStyle(.white)
                    .lineLimit(2)
                    .minimumScaleFactor(0.55)
                let line = ReadingBookFacts.listeningLine(work, narrator: narrator)
                if !line.isEmpty {
                    Text(line)
                        .font(HubType.body(15, relativeTo: .subheadline))
                        .foregroundStyle(.white.opacity(0.82))
                }
                if mine {
                    if let entry = currentEntry {
                        // The chapter playing (#31), else the part.
                        Text(entry.title)
                            .font(HubType.body(14, relativeTo: .subheadline))
                            .foregroundStyle(.white.opacity(0.72))
                            .accessibilityIdentifier("listen-entry")
                    }
                    Text(listening.span.durationMs > 0
                         ? PlayerLabels.timeLeft(entryLeftMs: listening.spanLeftMs, bookLeftMs: listening.bookLeftMs,
                                                 noun: listening.noun)
                         : "")
                        .font(HubType.body(14, weight: .bold, relativeTo: .subheadline))
                        .foregroundStyle(accent.tint)
                        .accessibilityIdentifier("listen-time-left")
                }
                StatusLine(message: shownStatus) { reloads += 1 }
            }
            .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var narrator: String {
        (mine ? listening.book?.manifest.narrator.nilIfEmpty : nil)
            ?? work?.editions.first { $0.sourceItemId == sourceItemId }?.narrator ?? ""
    }

    private var currentEntry: AudiobookContents.Entry? {
        let entries = listening.contents
        guard !entries.isEmpty else { return nil }
        return entries[AudiobookContents.current(entries, part: listening.part, positionMs: listening.positionMs)]
    }

    private var shownStatus: StatusMessage {
        if mine && !listening.problem.isEmpty { return StatusMessage(listening.problem, tone: .error) }
        return status
    }

    // MARK: The dock

    /// The read-along dock's glass player: the line, then the times either
    /// side of the steps, the jumps and the white Play. The line and its times
    /// are the chapter's where the book has chapters, across its tracks (#31),
    /// else the part's.
    private var dock: some View {
        let span = listening.span
        let noun = listening.noun
        return VStack(spacing: 10) {
            PlayerTimeline(positionMillis: mine ? span.positionMs : 0, durationMillis: mine ? span.durationMs : 0,
                           bufferedMillis: 0, scrub: $scrub,
                           seek: { listening.seek(inSpan: $0) },
                           adjust: { listening.seek(by: $0) })
                .disabled(!mine)
            HStack(spacing: metrics.small ? 8 : 12) {
                Text(mine ? Fmt.clock(scrub.map { Int64($0 * Double(span.durationMs)) } ?? span.positionMs) : "0:00")
                    .font(HubType.chrome(13, weight: .bold))
                    .monospacedDigit()
                    .frame(maxWidth: .infinity, alignment: .leading)
                PlayerSkipButton(systemImage: "backward.end.fill", text: nil, label: "Previous \(noun)", size: 44) { listening.step(-1) }
                PlayerSkipButton(systemImage: Self.jumpSymbol(seekSeconds, forward: false), text: nil,
                                 label: "Back \(seekSeconds) seconds", size: 44) { listening.seek(by: -Int64(seekSeconds) * 1_000) }
                PlayerPlayDisc(playing: mine && listening.playing, buffering: false, size: 58) { listening.toggle() }
                    .accessibilityIdentifier("listen-play")
                PlayerSkipButton(systemImage: Self.jumpSymbol(seekSeconds, forward: true), text: nil,
                                 label: "Forward \(seekSeconds) seconds", size: 44) { listening.seek(by: Int64(seekSeconds) * 1_000) }
                PlayerSkipButton(systemImage: "forward.end.fill", text: nil, label: "Next \(noun)", size: 44) { listening.step(1) }
                Text(mine ? PlayerLabels.remainingLine(positionMillis: span.positionMs, durationMillis: span.durationMs) : "")
                    .font(HubType.chrome(12.5, weight: .semibold))
                    .foregroundStyle(.white.opacity(0.7))
                    .monospacedDigit()
                    .frame(maxWidth: .infinity, alignment: .trailing)
            }
            .disabled(!mine)
        }
        .padding(.horizontal, 16)
        .padding(.top, 14)
        .padding(.bottom, 12)
        .glassPanel(RoundedRectangle(cornerRadius: 22, style: .continuous))
    }

    /// The SF Symbol with the step written in it: 5, 10, 15 and 30 all have one.
    static func jumpSymbol(_ seconds: Int, forward: Bool) -> String {
        (forward ? "goforward." : "gobackward.") + String(seconds)
    }

    // MARK: The tools

    private var tools: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                Menu {
                    let current = currentEntry
                    // Headed as the Pocket's sheet is: "Dark Matter · 15 chapters" (#31), else parts.
                    Section(listening.book.map { "\($0.title) · \(listening.contents.count) \(listening.noun)s" } ?? "") {
                        ForEach(Array(listening.contents.enumerated()), id: \.offset) { _, entry in
                            Button {
                                listening.seek(part: entry.part, offsetMs: entry.startMs)
                            } label: {
                                if entry == current {
                                    Label(entry.title, systemImage: "speaker.wave.2")
                                } else {
                                    Text(entry.durationMs.map { "\(entry.title) · \(Fmt.clock($0))" } ?? entry.title)
                                }
                            }
                        }
                    }
                } label: {
                    Label(listening.noun == "chapter" ? "Chapters" : "Parts", systemImage: "list.bullet")
                }
                .accessibilityIdentifier("listen-contents")
                .menuStyle(.button)
                .buttonStyle(GlassControlStyle())
                Menu {
                    ForEach(Listening.speeds, id: \.self) { value in
                        Button {
                            listening.setSpeed(value)
                        } label: {
                            if abs(value - listening.speed) < 0.01 {
                                Label(PlayerLabels.rate(value), systemImage: "checkmark")
                            } else {
                                Text(PlayerLabels.rate(value))
                            }
                        }
                    }
                } label: {
                    Label("Speed \(PlayerLabels.rate(listening.speed))", systemImage: "gauge.with.dots.needle.67percent")
                }
                .menuStyle(.button)
                .buttonStyle(GlassControlStyle())
                Menu {
                    // The end of the chapter where the book has chapters (#31), else of the part.
                    ForEach(SleepChoice.all, id: \.self) { choice in
                        Button(PlayerLabels.sleepChoice(choice, noun: listening.noun)) { listening.setSleep(choice) }
                    }
                    if listening.sleep != nil {
                        Button("Turn off", role: .destructive) { listening.setSleep(nil) }
                    }
                } label: {
                    Label(PlayerLabels.sleep(listening.sleep, noun: listening.noun), systemImage: "moon.zzz")
                }
                .menuStyle(.button)
                .buttonStyle(GlassControlStyle())
                Button {
                    listening.stop()
                } label: {
                    Label("Stop", systemImage: "stop.fill")
                }
                .buttonStyle(GlassControlStyle())
                .accessibilityIdentifier("listen-stop")
            }
            .disabled(!mine)
        }
        .scrollClipDisabled()
    }

    // MARK: Opening

    private func open() async {
        if work == nil {
            work = try? await model.hub.fetch(HubEndpoints.readingWork(workId), as: ReadingWork.self)
        }
        guard !mine else { return }
        guard let work else {
            status = StatusMessage("This audiobook could not be read · Try again", tone: .error, offersRetry: true)
            return
        }
        status = StatusMessage("Preparing audiobook…")
        do {
            let found = try await listening.prepare(work: work, sourceItemId: sourceItemId, app: model)
            status = StatusMessage("")
            if found.prompt == nil {
                listening.start(found, play: false)
            } else {
                opening = found
            }
        } catch {
            if error.kind == .cancelled { return }
            status = StatusText.failed(error.message, kind: error.kind, hasData: false)
        }
    }

    private func answer(_ choice: String, to current: ListeningModel.Opening) {
        status = StatusMessage("")
        let next = listening.answer(current, choice: choice)
        if next.prompt == nil {
            opening = nil
            listening.start(next, play: false)
        } else {
            // A second question (a guessed place): asked once the first has gone.
            opening = nil
            Task {
                try? await Task.sleep(for: .milliseconds(350))
                opening = next
            }
        }
    }

    private func cancelOpening() {
        guard opening != nil else { return }
        opening = nil
        status = StatusMessage("Choose where to listen from · Try again", offersRetry: true)
    }
}

/// The book playing, over the pages while it plays (Android's mini player):
/// its cover, its title, the chapter playing where the book has chapters
/// (#31) and the time left of the book, Play or Pause, and a tap back to its
/// page.
struct ListeningMiniPlayer: View {
    let open: () -> Void
    @State private var listening = ListeningModel.shared

    var body: some View {
        if let book = listening.book {
            HStack(spacing: 12) {
                Button(action: open) {
                    HStack(spacing: 12) {
                        BookCover(path: book.artwork, square: true, width: 120)
                            .frame(width: 40)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(book.title)
                                .font(HubType.body(14, weight: .bold, relativeTo: .subheadline))
                                .foregroundStyle(.white)
                            Text(detail)
                                .font(HubType.body(12, relativeTo: .caption))
                                .foregroundStyle(.white.opacity(0.7))
                                .accessibilityIdentifier("mini-detail")
                        }
                        .lineLimit(1)
                        Spacer(minLength: 0)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(listening.chapter.map { "\(book.title), \($0), open" } ?? "\(book.title), open")
                PlayerPlayDisc(playing: listening.playing, buffering: false, size: 40) { listening.toggle() }
                    .accessibilityIdentifier("mini-play")
            }
            .padding(.leading, 8)
            .padding(.trailing, 8)
            .padding(.vertical, 8)
            .frame(maxWidth: 420)
            .glassPanel(Capsule())
            // A container, so its buttons keep their own identifiers.
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("mini-player")
        }
    }
}

extension ListeningMiniPlayer {
    /// Under the title: the chapter playing, and what is left of the book
    /// ("Chapter Two · 4h 10m left"), or of the part while the book's length
    /// is not known yet.
    private var detail: String {
        let span = listening.span
        let left = listening.bookLeftMs ?? (span.durationMs > 0 ? listening.spanLeftMs : nil)
        return [listening.chapter ?? "", PlayerLabels.leftLine(left)].filter { !$0.isEmpty }.joined(separator: " · ")
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
