import HubKit
import SwiftUI

/// A title you do not have, on its way in (the prototype's `pgRequest`;
/// Android `screens/discover/MediaDetailScreen`): the backdrop fading into the
/// page, where it is, its name and facts, its pipeline as glass chips with the
/// stage under way pulsing amber, the story, then Request, Find release and
/// the trailer as real buttons, its seasons, and the cast, each opening their
/// films and series. While a stage is under way it reads itself again every
/// four seconds.
struct MediaTitleView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.glassMetrics) private var metrics
    @Environment(\.glassAccent) private var accent
    @Environment(\.openRoute) private var openRoute
    @Environment(\.openURL) private var openURL
    let route: MediaRoute

    @State private var detail: MediaDetail?
    @State private var status = StatusMessage("")
    @State private var expanded = false
    @State private var requestOpen = false
    /// What the hub said about a request just made, until the next read.
    @State private var notice = ""
    /// A new read after a request, which also starts the polling again.
    @State private var reloads = 0
    @State private var debugApplied = false

    private var backdrop: String {
        guard let detail else { return "" }
        return detail.media.backdrop.isEmpty ? detail.media.poster : detail.media.backdrop
    }

    private var availability: String {
        guard let detail else { return "" }
        return model.requested.availability(key: detail.media.key, hub: detail.availability)
    }

    private var canRequest: Bool {
        guard let detail else { return false }
        return detail.canRequest && availability == detail.availability
    }

    var body: some View {
        GeometryReader { proxy in
            // A small Mac window lays the words out as a phone turned sideways does.
            let page = metrics.forTitlePage(size: proxy.size, safe: proxy.safeAreaInsets)
            ScrollView {
                ZStack(alignment: .top) {
                    FadedArtwork.title(backdrop)
                        .frame(height: page.short ? proxy.size.height + proxy.safeAreaInsets.top
                               : (page.compact ? 470 : 590))
                        .frame(maxWidth: .infinity)
                        .clipped()
                    VStack(alignment: .leading, spacing: 0) {
                        header(page)
                            .padding(.top, page.short ? proxy.safeAreaInsets.top + 10
                                     : max(page.compact ? 290 : 236, proxy.safeAreaInsets.top + 120))
                            .padding(.horizontal, metrics.margin)
                        StatusLine(message: shownStatus) { reloads += 1 }
                            .padding(.horizontal, metrics.margin)
                            .padding(.top, 10)
                        if let detail {
                            seasons(detail)
                            cast(detail)
                        }
                    }
                    // A controller goes down the page (#46): Read more, the actions, the cast.
                    .padGroup("page", .column, members: padColumn, prefix: false)
                }
                .padding(.bottom, 28)
            }
            .ignoresSafeArea(edges: .top)
            .padPage("media:\(route.key)")
        }
        .ambientArtwork(backdrop)
        .refreshable { reloads += 1 }
        .task(id: "\(route.key)·\(reloads)") { await follow() }
        .sheet(isPresented: $requestOpen) {
            RequestSheet(key: route.key, fallbackTitle: detail?.media.title ?? route.title) { reply in
                model.recordRequest(key: route.key, availability: reply.availability, requestId: reply.requestId)
                notice = reply.message.isEmpty ? "Requested \(detail?.media.title ?? route.title)" : reply.message
                reloads += 1
            }
        }
    }

    private var shownStatus: StatusMessage {
        if !status.text.isEmpty { return status }
        return notice.isEmpty ? StatusMessage("") : StatusMessage(notice)
    }

    /// The page's lines for a controller, top to bottom (#46).
    private var padColumn: [String] {
        guard let detail else { return [] }
        return (detail.overview.isEmpty ? [] : ["read-more"]) + ["actions"] + (detail.cast.isEmpty ? [] : ["cast"])
    }

    // MARK: Header

    private func header(_ page: GlassMetrics) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            let eyebrow = TitleFacts.eyebrow(availability)
            Text(detail == nil ? " " : eyebrow.text)
                .font(HubType.body(12.5, weight: .bold, relativeTo: .caption))
                .tracking(1.75)
                .foregroundStyle(eyebrow.accent ? accent.tint : .white.opacity(0.72))
            Text(detail?.media.title ?? route.title)
                .font(HubType.heading(page.heroTitle, weight: .heavy))
                .tracking(-0.02 * page.heroTitle)
                .foregroundStyle(.white)
                .lineLimit(2)
                .minimumScaleFactor(0.6)
            if let detail {
                let facts = TitleFacts.facts(detail)
                if !facts.isEmpty {
                    Text(factsLine(facts))
                        .font(HubType.body(15, relativeTo: .subheadline))
                }
                PipelineStrip(stages: detail.pipeline.stages)
                if PipelineLines.showsSummary(detail.pipeline) {
                    Text(detail.pipeline.summary)
                        .font(HubType.body(14, weight: .bold, relativeTo: .subheadline))
                        .foregroundStyle(Color(argb: PipelineLines.summaryColor(availability: availability)))
                }
                if !detail.overview.isEmpty { overview(detail.overview) }
                actions(detail).padding(.top, 4)
            }
        }
        .frame(maxWidth: 860, alignment: .leading)
        .fixedSize(horizontal: false, vertical: true)
    }

    private func overview(_ text: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(text)
                .font(HubType.body(15, relativeTo: .body))
                .foregroundStyle(.white.opacity(0.86))
                .lineLimit(expanded ? nil : 2)
                .frame(maxWidth: 620, alignment: .leading)
            Button(expanded ? "Collapse description" : "Read more") { expanded.toggle() }
                .font(HubType.body(13, weight: .bold, relativeTo: .footnote))
                .foregroundStyle(.white.opacity(0.7))
                .buttonStyle(.plain)
                .padFocusable("read-more", ring: .rounded(4)) { expanded.toggle() }
        }
    }

    /// Request while the hub offers it, Find release always (the hub says
    /// when Radarr or Sonarr does not have the title yet), and the trailer
    /// when there is one.
    private func actions(_ detail: MediaDetail) -> some View {
        HStack(spacing: metrics.small ? 8 : 10) {
            if canRequest {
                Button {
                    requestOpen = true
                } label: {
                    Label("Request", systemImage: "plus")
                }
                .buttonStyle(PrimaryPillStyle())
                .padFocusable("request") { requestOpen = true }
            }
            Button {
                findRelease(detail)
            } label: {
                Label("Find release", systemImage: "magnifyingglass")
            }
            .buttonStyle(GlassPillStyle())
            .padFocusable("find-release") { findRelease(detail) }
            if let url = URL(string: detail.trailerUrl), !detail.trailerUrl.isEmpty {
                if metrics.small && canRequest {
                    GlassRoundButton(systemImage: "play.rectangle", label: "Trailer", size: 42, pad: "trailer") { openURL(url) }
                } else {
                    Button {
                        openURL(url)
                    } label: {
                        Label("Trailer", systemImage: "play.rectangle")
                    }
                    .buttonStyle(GlassPillStyle())
                    .padFocusable("trailer") { openURL(url) }
                }
            }
        }
        .padGroup("actions", .row, members: (canRequest ? ["request"] : []) + ["find-release"]
                  + (URL(string: detail.trailerUrl) != nil && !detail.trailerUrl.isEmpty ? ["trailer"] : []), prefix: false)
    }

    /// A film's releases at once; a series' season and aired episodes first.
    private func findRelease(_ detail: MediaDetail) {
        if detail.isSeries {
            openRoute(.releaseTargets(ReleaseTargetsRoute(key: detail.media.key, title: detail.media.title,
                                                          seasons: detail.seasonList, poster: detail.media.poster)))
        } else {
            openRoute(.releases(ReleasesRoute(key: detail.media.key, heading: detail.media.title, season: nil)))
        }
    }

    // MARK: Seasons and cast

    @ViewBuilder private func seasons(_ detail: MediaDetail) -> some View {
        if detail.isSeries, !detail.seasonList.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(detail.seasonList) { season in
                        Text(TitleFacts.seasonPill(season))
                            .font(HubType.body(14, weight: .semibold, relativeTo: .subheadline))
                            .foregroundStyle(.white)
                            .lineLimit(1)
                            .padding(.horizontal, 15)
                            .padding(.vertical, 9)
                            .glassPanel(Capsule())
                    }
                }
                .padding(.horizontal, metrics.margin)
                .padding(.top, 16)
                .padding(.bottom, 2)
            }
        }
    }

    @ViewBuilder private func cast(_ detail: MediaDetail) -> some View {
        if !detail.cast.isEmpty {
            Text("Cast")
                .font(HubType.body(metrics.rowTitle, weight: .bold, relativeTo: .title3))
                .foregroundStyle(.white)
                .padding(.horizontal, metrics.margin)
                .padding(.top, 22)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: 18) {
                    ForEach(detail.cast) { member in
                        NavigationLink(value: AppRoute.person(PersonRoute(id: member.id, name: member.name))) {
                            PersonCard(person: LibraryPerson(personId: String(member.id), name: member.name,
                                                             role: member.character, image: member.profile))
                        }
                        .buttonStyle(.plain)
                        .accessibilityHint("Opens their films and series")
                        .padFocusable("\(member.id)", ring: .rounded(12)) {
                            openRoute(.person(PersonRoute(id: member.id, name: member.name)))
                        }
                    }
                }
                .padding(.horizontal, metrics.margin)
                .padding(.top, 14)
                .padding(.bottom, 18)
            }
            .padGroup("cast", .row, members: detail.cast.map { "\($0.id)" }, strip: true)
        }
    }

    // MARK: Loading

    /// Reads the title, then again every four seconds while a stage is under
    /// way, backing off after failures (`PipelineLines.nextPoll`).
    private func follow() async {
        var failures = 0
        while !Task.isCancelled {
            if await load() {
                failures = 0
            } else {
                failures += 1
            }
            let moving = detail.map { PipelineLines.isMoving($0.pipeline) } ?? false
            guard let delay = PipelineLines.nextPoll(moving: moving, failures: failures) else { return }
            try? await Task.sleep(for: delay)
        }
    }

    private func load() async -> Bool {
        if detail == nil { status = StatusText.loading("the title", refreshing: false) }
        do {
            let response = try await model.hub.fetch(HubEndpoints.mediaDetail(key: route.key), as: MediaDetail.self)
            if response != detail { detail = response }
            #if DEBUG
            // scripts/mac.sh: HUB_SHEET=request opens the form, find Find release, once.
            if !debugApplied, let sheet = ProcessInfo.processInfo.environment["HUB_SHEET"] {
                debugApplied = true
                if sheet == "request" && canRequest { requestOpen = true }
                if sheet == "find" { findRelease(response) }
            }
            #endif
            status = StatusText.caveat(response.cache, unavailable: response.partial.map(\.service))
            return true
        } catch {
            if error.kind == .cancelled { return true }
            status = StatusText.failed(error.message, kind: error.kind, hasData: detail != nil)
            return false
        }
    }
}

/// A title's pipeline as glass chips (`.pipe`, `.stage`; Android
/// `PipelineChip`), side by side and scrolling when the window is narrow.
struct PipelineStrip: View {
    let stages: [PipelineStage]

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(stages) { stage in
                    PipelineChip(stage: stage)
                }
            }
            .padding(.vertical, 6)
            .padding(.horizontal, 1)
        }
        .scrollClipDisabled()
    }
}

/// One stage: a dot in the accent when done, amber and pulsing while under
/// way, red when stuck, faint while pending; the short name, and the percent
/// only on the stage under way.
struct PipelineChip: View {
    let stage: PipelineStage
    @Environment(\.glassAccent) private var accent

    static let amber = Color(argb: 0xFFF2_B544)
    static let alarm = Color(argb: 0xFFFF_5A5F)

    private var tone: PipelineTone { PipelineTone.of(stage.state) }

    var body: some View {
        HStack(spacing: 8) {
            PipelineDot(tone: tone)
            Text(PipelineLines.chip(stage))
                .font(HubType.body(13, weight: .bold, relativeTo: .footnote))
                .foregroundStyle(tone == .pending ? Color.white.opacity(0.55) : .white)
                .lineLimit(1)
                .monospacedDigit()
        }
        .padding(.horizontal, 13)
        .padding(.vertical, 8)
        .glassPanel(Capsule())
        .overlay {
            switch tone {
            case .active: Capsule().strokeBorder(Self.amber, lineWidth: 1)
            case .failed: Capsule().strokeBorder(Self.alarm, lineWidth: 1)
            default: EmptyView()
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(PipelineLines.spoken(stage))
    }
}

/// The chip's dot. Under way it pulses: an amber halo swelling to 19 points
/// and back, 1.4 s a breath; still under Reduce Motion.
struct PipelineDot: View {
    let tone: PipelineTone
    @Environment(\.glassAccent) private var accent
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var breathing = false

    private var color: Color {
        switch tone {
        case .done: accent.tint
        case .active: PipelineChip.amber
        case .failed: PipelineChip.alarm
        case .pending: .white.opacity(0.3)
        }
    }

    var body: some View {
        Circle()
            .fill(color)
            .frame(width: 9, height: 9)
            .background {
                if tone == .active {
                    Circle()
                        .fill(PipelineChip.amber.opacity(0.28))
                        .frame(width: 19, height: 19)
                        .scaleEffect(breathing || reduceMotion ? 1 : 9 / 19)
                        .onAppear {
                            guard !reduceMotion else { return }
                            withAnimation(.easeInOut(duration: 0.7).repeatForever(autoreverses: true)) { breathing = true }
                        }
                }
            }
    }
}
