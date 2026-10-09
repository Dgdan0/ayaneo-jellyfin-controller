import HubKit
import SwiftUI

/// A page of the player's sheet. The top three open from the bar; the rest
/// open from a row of one of them, and Back returns there (Android's
/// `PlayerScreen` panels).
enum PlayerPanel: Hashable {
    /// The three in one list, for a window too narrow for their buttons.
    case choose
    case tracks, video, chapters
    case timing, look, quality, version, speed, aspect, stream
}

/// The player's panels as a glass sheet (GLASS_PLAN.md › Player; the
/// prototype's `.sheet`): at the right edge, full height, over a dimmed
/// picture, or from the bottom where the window is too narrow for one beside
/// it (a phone held upright). Its heading has Back for a page opened from a
/// row and a round close; its rows sit in raised groups, a tick on what is
/// chosen and a chevron on what opens another page.
///
/// The pages are the shell's state, not the sheet's, so turning the device
/// keeps the page open, only moving it to the other edge.
///
/// Nothing in it moves while its rows are first laid out. SwiftUI lays out
/// some animation frames on its own thread (`com.apple.SwiftUI.AsyncRenderer`),
/// and a row built there runs main-actor code off the main thread, which
/// Swift 6 stops with a trap: two crashes on 2026-10-04, as a page opened and
/// as the sheet closed. So the sheet comes in without a transition and then
/// slides, a page changes in place, and none of it reads the playing position,
/// which changes four times a second.
struct PlayerSheet: View {
    let player: PlayerModel
    @Binding var panels: [PlayerPanel]
    let layout: PlayerLayout
    @Environment(\.glassAccent) private var accent
    /// The page shown last, which the sheet keeps while it slides away.
    @State private var lastPanel = PlayerPanel.tracks
    /// In place; it slides in once its rows are laid out, and out before it goes.
    @State private var shown = false
    /// The chapter playing when Chapters opened; not followed live.
    @State private var playingChapter = 0

    private var panel: PlayerPanel { panels.last ?? lastPanel }

    var body: some View {
        let bottom = layout.sheetFromBottom
        ZStack(alignment: bottom ? .bottom : .trailing) {
            Color.black.opacity(shown ? 0.4 : 0)
                .contentShape(Rectangle())
                .onTapGesture { close() }
                .accessibilityLabel("Close")
                .accessibilityAddTraits(.isButton)
                .accessibilityAction { close() }
            sheet(bottom: bottom)
                .offset(x: shown || bottom ? 0 : 60, y: shown || !bottom ? 0 : 80)
                .opacity(shown ? 1 : 0)
        }
        .onChange(of: panels, initial: true) { _, now in
            if let last = now.last { lastPanel = last }
            if now.last == .chapters {
                playingChapter = player.chapters.lastIndex { $0.positionMillis <= player.positionMillis } ?? 0
            }
        }
        .task {
            // A frame after its rows are laid out, on the main thread.
            await Task.yield()
            withAnimation(.easeOut(duration: 0.26)) { shown = true }
        }
    }

    private func sheet(bottom: Bool) -> some View {
        let shape = UnevenRoundedRectangle(topLeadingRadius: bottom ? 32 : 0, topTrailingRadius: bottom ? 32 : 0,
                                           style: .continuous)
        return VStack(alignment: .leading, spacing: 12) {
            header
            ScrollViewReader { reader in
                Group {
                    if bottom {
                        // Sized to what it holds, up to most of the screen,
                        // and scrolling past that (the prototype's 84%).
                        ViewThatFits(in: .vertical) {
                            pageBody
                            ScrollView { pageBody }.scrollIndicators(.hidden)
                        }
                    } else {
                        ScrollView { pageBody }
                            .scrollIndicators(.hidden)
                            .scrollBounceBehavior(.basedOnSize)
                    }
                }
                // Each page its own scroll view, at its top or its choice.
                .id(panel)
                .onAppear { scrollToChoice(reader) }
            }
        }
        .padding(.top, 22 + (bottom ? 0 : layout.safe.top))
        .padding(.leading, 20 + (bottom ? layout.safe.leading : 0))
        .padding(.trailing, 20 + layout.safe.trailing)
        .padding(.bottom, (bottom ? 22 : 30) + layout.safe.bottom)
        .frame(width: layout.sheetWidth, alignment: .leading)
        .background { GlassSheetFill().clipShape(shape) }
        .overlay(alignment: .leading) {
            // The prototype's one edge: along the top of a sheet from the
            // bottom, down the side of one from the right.
            if bottom {
                shape.strokeBorder(Color(argb: GlassColors.edge), lineWidth: 1)
                    .padding(.bottom, -2)
                    .allowsHitTesting(false)
            } else {
                Rectangle().fill(Color(argb: GlassColors.edge)).frame(width: 1)
            }
        }
        .contentShape(shape)
        // A sheet from the bottom is as tall as what it holds, up to 84% of
        // the screen; the one at the side runs from top to bottom.
        .frame(height: bottom ? layout.size.height * 0.84 : layout.size.height, alignment: .bottom)
        // A controller moves down its rows and Ⓑ goes back a page, then closes it (#46).
        .environment(\.padSheetRows, true)
        .padPage("player-\(String(describing: panel))", modal: true) { back() }
        .accessibilityElement(children: .contain)
        .accessibilityAddTraits(.isModal)
    }

    // MARK: Heading

    private var header: some View {
        HStack(alignment: .top, spacing: 12) {
            if panels.count > 1 {
                GlassRoundButton(systemImage: "chevron.left", label: "Back", size: 38, pad: "panel-back") { back() }
            }
            VStack(alignment: .leading, spacing: 5) {
                Text(heading.title)
                    .font(HubType.heading(23, weight: .heavy, relativeTo: .title2))
                    .lineLimit(2)
                    .accessibilityAddTraits(.isHeader)
                    .accessibilityIdentifier("panel-heading")
                if !heading.sub.isEmpty {
                    Text(heading.sub)
                        .font(HubType.body(13, weight: .medium, relativeTo: .footnote))
                        .foregroundStyle(.white.opacity(0.65))
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            Spacer(minLength: 0)
            if player.applying {
                ProgressView().tint(.white).padding(.top, 9).accessibilityLabel("Changing")
            }
            GlassRoundButton(systemImage: "xmark", label: "Close", size: 38, pad: "panel-close") { close() }
        }
    }

    private var heading: (title: String, sub: String) {
        let name = player.plan.map { PlayerLabels.title($0.item) } ?? ""
        return switch panel {
        case .choose: ("Playback", name)
        case .tracks: ("Audio & subtitles", name)
        case .video: ("This video", name)
        case .chapters: ("Chapters", name)
        case .timing: ("Subtitle timing", "Moves the subtitles without reloading the video.")
        case .look: ("How subtitles look", "For every video on this device.")
        case .quality: ("Quality", "Lower uses less of your connection; Original plays the file as it is.")
        case .version: ("Version", name)
        case .speed: ("Playback speed", "Changes apply without reloading the video.")
        case .aspect: ("Aspect", PlayerLabels.aspectNote)
        case .stream: ("Stream", "How this video reaches you.")
        }
    }

    // MARK: Pages

    @ViewBuilder private var pageBody: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let plan = player.plan {
                switch panel {
                case .choose: choose
                case .tracks: tracks(plan)
                case .video: video(plan)
                case .chapters: chapters(plan)
                case .timing: timing
                case .look: look
                case .quality: quality
                case .version: versions(plan)
                case .speed: speeds
                case .aspect: aspects
                case .stream: stream(plan)
                }
            } else {
                SheetNote(text: "Nothing is playing yet.")
            }
        }
        .padding(.bottom, 4)
    }

    /// The narrowest window's one round button opens this: the three pages
    /// the wider windows have buttons for, each a row.
    private var choose: some View {
        SheetGroup {
            SheetRow(title: "Audio & subtitles", chevron: true) { open(.tracks) } leading: {
                Image(systemName: "captions.bubble").frame(width: 24)
            }
            SheetRow(title: "Chapters", chevron: true) { open(.chapters) } leading: {
                Image(systemName: "list.bullet").frame(width: 24)
            }
            SheetRow(title: "This video", chevron: true) { open(.video) } leading: {
                Image(systemName: "slider.horizontal.3").frame(width: 24)
            }
        }
    }

    @ViewBuilder private func tracks(_ plan: PlaybackPrepareResponse) -> some View {
        if !plan.audioTracks.isEmpty {
            SheetLabel(text: "Audio")
            SheetGroup {
                ForEach(plan.audioTracks, id: \.index) { track in
                    let shown = TrackPresentation.of(track)
                    SheetRow(title: shown.title, detail: shown.detail, checked: track.index == plan.selectedAudioIndex) {
                        player.chooseAudio(track)
                    }
                    .id("audio-\(track.index)")
                }
            }
            .disabled(player.applying)
        }
        SheetLabel(text: "Subtitles")
        SheetGroup {
            SheetRow(title: "Off", checked: (plan.selectedSubtitleIndex ?? -1) < 0) { player.chooseSubtitle(-1) }
                .id("subtitles--1")
            ForEach(player.casting ? CastPlan.textSubtitles(plan) : plan.subtitleTracks, id: \.index) { track in
                let shown = TrackPresentation.of(track)
                SheetRow(title: shown.title, detail: shown.detail, checked: track.index == plan.selectedSubtitleIndex) {
                    player.chooseSubtitle(track.index)
                }
                .id("subtitles-\(track.index)")
            }
        }
        .disabled(player.applying)
        if player.casting {
            SheetNote(text: "The TV shows subtitles kept as text files (SRT and WebVTT). Another audio track moves the video to the TV again, from where it is.")
        } else {
        SheetGroup {
            // Only subtitles the app draws can move in time; a picture track
            // is burned into the video (Android shows the row the same way).
            if player.drawsSubtitles {
                SheetRow(title: "Subtitle timing", value: SubtitleTimingPolicy.short(player.subtitleOffsetMillis),
                         chevron: true) { open(.timing) }
            }
            SheetRow(title: "How subtitles look", value: PlayerLabels.subtitleLook(player.subtitleLook),
                     chevron: true) { open(.look) }
        }
        }
    }

    @ViewBuilder private func video(_ plan: PlaybackPrepareResponse) -> some View {
        if player.casting {
            cast
        } else {
            device(plan)
        }
    }

    /// Playing on the TV (#44): back to this device where it is, or stop there.
    @ViewBuilder private var cast: some View {
        SheetLabel(text: CastPresentation.playingOn(CastPlayback.shared.deviceName))
        SheetGroup {
            SheetRow(title: CastPresentation.moveHere(pad: Self.onPad), detail: "Goes on here from the TV's place") {
                close()
                player.moveHere()
            }
            .accessibilityIdentifier("player-cast-here")
            SheetRow(title: "Stop on TV", detail: "Ends playback on the TV") {
                close()
                player.stopOnTV()
            }
            .accessibilityIdentifier("player-cast-stop")
        }
        SheetNote(text: "The TV plays a stream made for it: H.264 and AAC, up to 20 Mbps.")
    }

    private static var onPad: Bool {
        #if os(iOS)
        UIDevice.current.userInterfaceIdiom == .pad
        #else
        false
        #endif
    }

    @ViewBuilder private func device(_ plan: PlaybackPrepareResponse) -> some View {
        let quality = PlaybackRules.qualities.first { $0.bitrate == player.maxBitrate } ?? PlaybackRules.qualities[0]
        SheetGroup {
            // A download plays its own file: there is no other quality to ask for.
            SheetRow(title: "Quality", value: PlayerLabels.qualityValue(quality.label, height: plan.height,
                                                                        offline: player.isOffline),
                     chevron: !player.isOffline) { if !player.isOffline { open(.quality) } }
            if plan.sources.count > 1, let source = currentSource(plan) {
                SheetRow(title: "Version", value: sourceName(source), chevron: true) { open(.version) }
            }
            SheetRow(title: "Speed", value: PlayerLabels.speed(player.speed), chevron: true) { open(.speed) }
            SheetRow(title: "Aspect", value: PlayerLabels.aspect(player.aspect), chevron: true) { open(.aspect) }
                .accessibilityIdentifier("player-aspect")
            SheetRow(title: "Stream", value: PlayerLabels.playMethod(plan.playMethod), chevron: true) { open(.stream) }
        }
        SheetNote(text: "These change only this video.")
    }

    @ViewBuilder private func chapters(_ plan: PlaybackPrepareResponse) -> some View {
        let chapters = player.chapters
        if chapters.isEmpty {
            SheetNote(text: "This video has no chapter markers.")
        } else {
            let duration = player.durationMillis
            let current = playingChapter
            SheetGroup {
                ForEach(Array(chapters.enumerated()), id: \.offset) { index, chapter in
                    let end = index + 1 < chapters.count ? chapters[index + 1].positionMillis : duration
                    let kind = PlaybackEnhancements.segmentAt(plan.segments, startMillis: chapter.positionMillis)
                        .flatMap { PlayerLabels.segmentKind($0.type) }
                    let frame = plan.previewUrl.isEmpty ? "" : HubEndpoints.playbackPreview(
                        plan.previewUrl,
                        positionMillis: PlaybackEnhancements.chapterFrameMillis(startMillis: chapter.positionMillis, endMillis: end))
                    SheetRow(title: chapter.name,
                             detail: PlayerLabels.chapterDetail(startMillis: chapter.positionMillis, endMillis: end, kind: kind),
                             checked: index == current) {
                        player.seek(to: chapter.positionMillis)
                        close()
                    } leading: {
                        PreviewFrame(path: frame)
                            .frame(width: 86, height: 48)
                            .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
                    }
                    .id("chapter-\(index)")
                }
            }
        }
    }

    private var timing: some View {
        let offset = player.subtitleOffsetMillis
        let range = Double(PlaybackChoices.maxSubtitleOffsetMillis)
        return VStack(alignment: .leading, spacing: 14) {
            Text(SubtitleTimingPolicy.label(offset))
                .font(HubType.heading(26, weight: .heavy, relativeTo: .title2))
                .monospacedDigit()
                .accessibilityHidden(true)
            Slider(value: Binding(get: { Double(offset) },
                                  set: { player.setSubtitleOffset(Int64(($0 / 100).rounded()) * 100) }),
                   in: -range...range, step: Double(SubtitleTimingPolicy.stepMillis)) { editing in
                if !editing { player.commitSubtitleOffset() }
            }
            .tint(accent.tint)
            .accessibilityLabel("Subtitle timing")
            .accessibilityValue(SubtitleTimingPolicy.label(offset))
            HStack {
                Text("\u{2212}60 s · Earlier")
                Spacer()
                Text("Later · +60 s")
            }
            .font(HubType.body(12, relativeTo: .caption))
            .foregroundStyle(.white.opacity(0.55))
            .accessibilityHidden(true)
            HStack(spacing: 8) {
                step(-1_000, "\u{2212}1 s", label: "One second earlier")
                step(-100, "\u{2212}0.1 s", label: "A tenth of a second earlier")
                step(100, "+0.1 s", label: "A tenth of a second later")
                step(1_000, "+1 s", label: "One second later")
            }
            HStack(spacing: 10) {
                Button("Reset") {
                    player.setSubtitleOffset(0)
                    player.commitSubtitleOffset()
                }
                .buttonStyle(GlassPillStyle())
                .disabled(offset == 0)
                Button("Done") { back() }
                    .buttonStyle(GlassPillStyle())
            }
            SheetNote(text: "Later shows each line after it is spoken. Kept for this series or film on this device.")
        }
        .padding(.horizontal, 4)
    }

    private func step(_ delta: Int64, _ title: String, label: String) -> some View {
        Button {
            player.setSubtitleOffset(player.subtitleOffsetMillis + delta)
            player.commitSubtitleOffset()
        } label: {
            Text(title)
                .font(HubType.body(14, weight: .bold, relativeTo: .subheadline))
                .monospacedDigit()
                .lineLimit(1)
                .fixedSize()
                .frame(maxWidth: .infinity, minHeight: 40)
                .glassPanel(Capsule())
                .contentShape(Capsule())
        }
        .buttonStyle(SheetRowStyle())
        .clipShape(Capsule())
        .accessibilityLabel(label)
        .padFocusable("step:\(label)", ring: .capsule) {
            player.setSubtitleOffset(player.subtitleOffsetMillis + delta)
            player.commitSubtitleOffset()
        }
    }

    @ViewBuilder private var look: some View {
        let current = player.subtitleLook
        SheetLabel(text: "Style")
        SheetGroup {
            ForEach(SubtitleStyle.allCases, id: \.self) { style in
                SheetRow(title: SubtitleLookWords.style(style), detail: SubtitleLookWords.styleDetail(style),
                         checked: current.style == style) {
                    var next = current
                    next.style = style
                    player.setSubtitleLook(next)
                }
            }
        }
        SheetLabel(text: "Size")
        SheetGroup {
            ForEach(SubtitleSize.allCases, id: \.self) { size in
                SheetRow(title: SubtitleLookWords.size(size), checked: current.size == size) {
                    var next = current
                    next.size = size
                    player.setSubtitleLook(next)
                }
            }
        }
        SheetGroup {
            Toggle(isOn: Binding(get: { current.liftWithControls }, set: { lifted in
                var next = current
                next.liftWithControls = lifted
                player.setSubtitleLook(next)
            })) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(SubtitleLookWords.liftTitle)
                        .font(HubType.body(15, weight: .semibold, relativeTo: .body))
                    Text(SubtitleLookWords.liftDetail)
                        .font(HubType.body(12.5, relativeTo: .caption))
                        .foregroundStyle(.white.opacity(0.6))
                }
            }
            .tint(accent.tint)
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .sheetDivider()
        }
    }

    @ViewBuilder private var quality: some View {
        SheetGroup {
            ForEach(PlaybackRules.qualities, id: \.bitrate) { option in
                SheetRow(title: option.label, checked: option.bitrate == player.maxBitrate) {
                    player.chooseQuality(option.bitrate)
                    back()
                }
            }
        }
        .disabled(player.applying)
    }

    @ViewBuilder private func versions(_ plan: PlaybackPrepareResponse) -> some View {
        let current = currentSource(plan)
        SheetGroup {
            ForEach(plan.sources) { source in
                SheetRow(title: sourceName(source),
                         detail: PlayerLabels.sourceDetail(container: source.container, bitrate: source.bitrate),
                         checked: source.id == current?.id) {
                    player.chooseSource(source.id)
                    back()
                }
            }
        }
        .disabled(player.applying)
    }

    @ViewBuilder private var speeds: some View {
        SheetGroup {
            ForEach(PlaybackEnhancements.speeds, id: \.self) { value in
                SheetRow(title: PlayerLabels.speed(value), checked: value == player.speed) {
                    player.setSpeed(value)
                    back()
                }
            }
        }
    }

    /// Fit, Fill, Zoom or Original aspect, for this video (#33).
    @ViewBuilder private var aspects: some View {
        SheetGroup {
            ForEach(PlaybackAspect.allCases, id: \.self) { value in
                SheetRow(title: PlayerLabels.aspect(value), checked: value == player.aspect) {
                    player.setAspect(value)
                    back()
                }
            }
        }
    }

    @ViewBuilder private func stream(_ plan: PlaybackPrepareResponse) -> some View {
        // Android's diagnostic line, one fact to a row.
        let facts = PlayerLabels.diagnostic(plan).components(separatedBy: " · ")
        SheetGroup {
            ForEach(Array(facts.enumerated()), id: \.offset) { _, fact in
                Text(fact)
                    .font(HubType.body(15, weight: .semibold, relativeTo: .body))
                    .textSelection(.enabled)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 13)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .sheetDivider()
            }
        }
    }

    // MARK: Moving between pages

    /// Pages change in place: see the type's note on animation.
    private func open(_ next: PlayerPanel) {
        panels.append(next)
    }

    private func back() {
        if panels.count > 1 { panels.removeLast() } else { close() }
    }

    /// Slides away, then goes; the rows do not change on the way.
    private func close() {
        withAnimation(.easeIn(duration: 0.2)) { shown = false }
        let closing = panels
        Task {
            try? await Task.sleep(for: .milliseconds(210))
            // Unless another panel was opened meanwhile.
            if panels == closing { panels = [] }
        }
    }

    /// Audio & subtitles opens on the kind changed last (Android's
    /// `PlayerMenuState`), Chapters on the chapter playing.
    private func scrollToChoice(_ reader: ScrollViewProxy) {
        guard let plan = player.plan else { return }
        let target: String? = switch panel {
        case .tracks:
            player.menu.trackTab == "audio" ? plan.selectedAudioIndex.map { "audio-\($0)" }
                : "subtitles-\(plan.selectedSubtitleIndex ?? -1)"
        case .chapters:
            "chapter-\(player.chapters.lastIndex { $0.positionMillis <= player.positionMillis } ?? 0)"
        default: nil
        }
        guard let target else { return }
        Task { @MainActor in reader.scrollTo(target, anchor: .center) }
    }

    private func currentSource(_ plan: PlaybackPrepareResponse) -> PlaybackSource? {
        plan.sources.first { $0.id == plan.selectedMediaSourceId } ?? plan.sources.first
    }

    private func sourceName(_ source: PlaybackSource) -> String {
        source.name.isEmpty ? source.container.uppercased() : source.name
    }
}

// MARK: The sheet's parts

/// The small capitals over a group (`.slab`).
struct SheetLabel: View {
    let text: String

    var body: some View {
        GlassLabel(text: text)
            .padding(.horizontal, 4)
            .padding(.top, 8)
            .accessibilityAddTraits(.isHeader)
    }
}

/// Rows in one raised glass card (`.group`), a hairline between them. Each
/// row draws its own along its top (`sheetDivider`); the card starts a point
/// higher and clips, so the first row's is hidden.
///
/// Not `Group(subviews:)`: SwiftUI resolves those subviews while laying out,
/// which its asynchronous renderer does off the main thread, and the
/// main-actor closure inside then trapped (a crash on the iPhone, 2026-10-04,
/// `com.apple.SwiftUI.AsyncRenderer`).
struct SheetGroup<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        VStack(spacing: 0) { content }
            .padding(.top, -1)
            .glassPanel(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

extension View {
    /// The hairline along the top of a row in a `SheetGroup`.
    func sheetDivider() -> some View {
        overlay(alignment: .top) {
            Rectangle().fill(.white.opacity(0.08)).frame(height: 1).allowsHitTesting(false)
        }
    }
}

/// A row (`.opt`): its name and a line under it, then its value, a tick in
/// the accent while chosen, or a chevron when it opens another page.
/// `tabular`: the value is a number in a column down the rows (the
/// contents' pages, #55), last in the row whether the tick is there or not,
/// and said as "page 12".
struct SheetRow<Leading: View>: View {
    let title: String
    var detail = ""
    var value = ""
    var checked = false
    var chevron = false
    /// Its id for a controller's focus (#46).
    var pad: String?
    var tabular = false
    let action: () -> Void
    @ViewBuilder let leading: Leading
    @Environment(\.glassAccent) private var accent
    /// In a sheet whose rows a controller moves through by their words (the player's).
    @Environment(\.padSheetRows) private var padRows

    /// Its id by its words, where the sheet asks for that and none is given.
    private var wordsId: String? { pad == nil && padRows ? "row:\(title)·\(detail)" : nil }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                leading
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(HubType.body(15, weight: .semibold, relativeTo: .body))
                    if !detail.isEmpty {
                        Text(detail)
                            .font(HubType.body(12.5, relativeTo: .caption))
                            .foregroundStyle(.white.opacity(0.6))
                    }
                }
                .multilineTextAlignment(.leading)
                Spacer(minLength: 8)
                if !value.isEmpty && !tabular {
                    Text(value)
                        .font(HubType.body(14, relativeTo: .subheadline))
                        .foregroundStyle(.white.opacity(0.62))
                        .multilineTextAlignment(.trailing)
                }
                if checked {
                    Image(systemName: "checkmark")
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(accent.tint)
                }
                if !value.isEmpty && tabular {
                    Text(value)
                        .font(HubType.body(14, relativeTo: .subheadline).monospacedDigit())
                        .foregroundStyle(.white.opacity(0.62))
                        .accessibilityHidden(true)
                }
                if chevron {
                    Image(systemName: "chevron.right")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.5))
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 13)
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(SheetRowStyle())
        .sheetDivider()
        .accessibilityElement(children: .combine)
        .modifier(SpokenPage(tabular: tabular, page: value))
        .accessibilityAddTraits(checked ? .isSelected : [])
        // Outside the row's one accessibility element: inside it, the focus's
        // clear views made the row a button holding its own button, which
        // VoiceOver and the tests met twice (#46).
        .padFocusable(pad ?? wordsId, ring: .none, press: action)
    }
}

/// A `tabular` row's number, said as its value ("page 12"); other rows keep
/// what their parts say. Which branch is fixed per row, so a number that
/// arrives later does not make the row a new view.
private struct SpokenPage: ViewModifier {
    let tabular: Bool
    let page: String

    @ViewBuilder func body(content: Content) -> some View {
        if tabular { content.accessibilityValue(page.isEmpty ? "" : "page " + page) } else { content }
    }
}

extension SheetRow where Leading == EmptyView {
    init(title: String, detail: String = "", value: String = "", checked: Bool = false, chevron: Bool = false,
         pad: String? = nil, action: @escaping () -> Void) {
        self.init(title: title, detail: detail, value: value, checked: checked, chevron: chevron, pad: pad,
                  action: action) {
            EmptyView()
        }
    }
}

extension EnvironmentValues {
    /// The sheet's rows are focusable by their words (#46): the player's panels,
    /// where every row is a choice and none is given an id of its own.
    @Entry var padSheetRows = false
}

/// A row lights up under a finger or the pointer (`.opt:hover`).
struct SheetRowStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled
    /// A controller's or keyboard's focus on the row (#46): a ring inside it.
    @Environment(\.padLit) private var padLit

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(.white)
            .background(configuration.isPressed ? Color.white.opacity(0.07) : .clear)
            .overlay {
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .strokeBorder(.white, lineWidth: 3)
                    .padding(3)
                    .opacity(padLit ? 1 : 0)
                    .allowsHitTesting(false)
            }
            .opacity(isEnabled ? 1 : 0.55)
            #if os(iOS)
            .hoverEffect(.highlight)
            #endif
    }
}

struct SheetNote: View {
    let text: String

    var body: some View {
        Text(text)
            .font(HubType.body(13, relativeTo: .footnote))
            .foregroundStyle(.white.opacity(0.6))
            .padding(.horizontal, 4)
            .fixedSize(horizontal: false, vertical: true)
    }
}

/// A frame of the playing video from the hub (`previewUrl`): a chapter's
/// picture. A tile until it comes, and a tile when it cannot.
struct PreviewFrame: View {
    @Environment(AppModel.self) private var model
    let path: String
    @State private var image: DecodedArtwork?

    var body: some View {
        Color.white.opacity(0.08)
            .overlay {
                if let image {
                    Image(decorative: image.image, scale: 1)
                        .resizable()
                        .scaledToFill()
                        .transition(.opacity)
                }
            }
            .clipped()
            .task(id: path) {
                guard !path.isEmpty else { return }
                let loaded = await loadArtwork(model.hub, request: path, maxPixels: 240)
                withAnimation(.easeOut(duration: 0.2)) { image = loaded }
            }
            .accessibilityHidden(true)
    }
}
