import HubKit
import SwiftUI

// The parts of a title's page, as plain values, so the library's own page
// (`TitleView`, from the hub) and a downloaded title's (`OfflineTitleView`,
// from the device) are one shape: the faded backdrop under the bars, the
// name and its lines, the overview, then the seasons and a strip of episode
// cards. Nothing here knows where the words came from.

/// A title's page (the prototype's `pgTitle`): the backdrop filling the top
/// and fading into the page, the header over it, then whatever follows. The
/// `header` is given the page's metrics, which say how large the name is and
/// whether a phone turned sideways starts the words under the bars.
struct TitlePage<Backdrop: View, Header: View, Below: View>: View {
    @Environment(\.glassMetrics) private var metrics
    let backdrop: Backdrop
    /// The page's key for a controller's focus (#46), and its lines top to
    /// bottom (groups and items: "actions", "tabs", "seasons", "episodes").
    let pad: String?
    let padColumn: [String]
    let header: (GlassMetrics) -> Header
    let below: Below

    init(backdrop: Backdrop, pad: String? = nil, padColumn: [String] = [],
         @ViewBuilder header: @escaping (GlassMetrics) -> Header, @ViewBuilder below: () -> Below) {
        self.backdrop = backdrop
        self.pad = pad
        self.padColumn = padColumn
        self.header = header
        self.below = below()
    }

    var body: some View {
        GeometryReader { proxy in
            // A small Mac window lays the words out as a phone turned sideways does.
            let page = metrics.forTitlePage(size: proxy.size, safe: proxy.safeAreaInsets)
            ScrollView {
                ZStack(alignment: .top) {
                    // The prototype's `.dart`: 590 tall on an iPad, 470 on an
                    // iPhone; on a phone turned sideways, what can be seen.
                    backdrop
                        .frame(height: page.short ? proxy.size.height + proxy.safeAreaInsets.top
                               : (page.compact ? 470 : 590))
                        .frame(maxWidth: .infinity)
                        .clipped()
                    VStack(alignment: .leading, spacing: 0) {
                        // On a short screen the words start under the bars,
                        // so Play is never below the fold.
                        header(page)
                            .padding(.top, page.short ? proxy.safeAreaInsets.top + 10
                                     : max(page.compact ? 290 : 236, proxy.safeAreaInsets.top + 120))
                            .padding(.horizontal, metrics.margin)
                        below
                    }
                    // A controller goes down the page line by line (#46).
                    .padGroup(pad == nil ? nil : "page", .column, members: padColumn, prefix: false)
                }
                .padding(.bottom, 28)
            }
            .ignoresSafeArea(edges: .top)
            .modifier(TitlePadPage(key: pad))
        }
    }
}

/// The page a controller moves on, when the page has a key.
private struct TitlePadPage: ViewModifier {
    let key: String?

    func body(content: Content) -> some View {
        if let key { content.padPage(key) } else { content }
    }
}

/// The name and the lines under it, over the backdrop (the prototype's
/// `.dhead`: lines 10 apart, at most 860 wide): an eyebrow above, the name,
/// its original title when it differs, the facts as one line, the state in the
/// accent, the overview, then the actions.
struct TitleHeader<Eyebrow: View, Actions: View>: View {
    @Environment(\.glassAccent) private var accent
    let title: String
    let originalTitle: String
    let page: GlassMetrics
    /// "S2E3  ·  2009  ·  TV-MA  ·  47 min", the parts split at the dots; empty for none.
    let facts: String
    let state: String
    let overview: String
    let eyebrow: Eyebrow
    let actions: Actions

    init(title: String, originalTitle: String = "", page: GlassMetrics, facts: String = "", state: String = "",
         overview: String = "", @ViewBuilder eyebrow: () -> Eyebrow, @ViewBuilder actions: () -> Actions) {
        self.title = title
        self.originalTitle = originalTitle
        self.page = page
        self.facts = facts
        self.state = state
        self.overview = overview
        self.eyebrow = eyebrow()
        self.actions = actions()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            eyebrow
            Text(title)
                .font(HubType.heading(page.heroTitle, weight: .heavy))
                .tracking(-0.02 * page.heroTitle)
                .foregroundStyle(.white)
                .lineLimit(2)
                .minimumScaleFactor(0.6)
            if !originalTitle.isEmpty, originalTitle.caseInsensitiveCompare(title) != .orderedSame {
                Text(originalTitle)
                    .font(HubType.body(15, relativeTo: .subheadline))
                    .foregroundStyle(.white.opacity(0.66))
            }
            if !facts.isEmpty {
                // Wraps rather than truncates, as the prototype's `.facts` does.
                Text(factsLine(facts.components(separatedBy: "  ·  ")))
                    .font(HubType.body(15, relativeTo: .subheadline))
            }
            if !state.isEmpty {
                Text(state)
                    .font(HubType.body(14, weight: .bold, relativeTo: .subheadline))
                    .foregroundStyle(accent.tint)
            }
            if !overview.isEmpty { TitleOverview(text: overview) }
            actions
        }
        .frame(maxWidth: 860, alignment: .leading)
        // Laid over the backdrop in a stack, the lines were offered one line's
        // height each and truncated; their own height lets them wrap.
        .fixedSize(horizontal: false, vertical: true)
    }
}

/// Two lines of the story, and Read more.
struct TitleOverview: View {
    let text: String
    @State private var expanded = false

    var body: some View {
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
}

/// One season's pill: what it says, and whether it is the one shown.
struct SeasonPill: Identifiable, Equatable {
    let id: String
    let title: String
    let selected: Bool
}

/// The prototype's `.pills`: one glass pill per season, a long press asking
/// what that season offers, and a slot after the last pill for a control
/// that belongs to the row (a page's download actions).
struct SeasonPills<Menu: View, Trailing: View>: View {
    @Environment(\.glassMetrics) private var metrics
    let pills: [SeasonPill]
    let choose: (String) -> Void
    let menu: (String) -> Menu
    let trailing: Trailing

    init(_ pills: [SeasonPill], choose: @escaping (String) -> Void, @ViewBuilder menu: @escaping (String) -> Menu,
         @ViewBuilder trailing: () -> Trailing) {
        self.pills = pills
        self.choose = choose
        self.menu = menu
        self.trailing = trailing()
    }

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(pills) { pill in
                    ChoicePill(title: pill.title, selected: pill.selected, pad: pill.id) { choose(pill.id) }
                        .contextMenu { menu(pill.id) }
                }
                trailing
            }
            .padding(.horizontal, metrics.margin)
            .padding(.top, 14)
            .padding(.bottom, 2)
        }
        // A controller goes along the seasons (#46).
        .padGroup("seasons", .row, members: pills.map(\.id), strip: true)
    }
}

extension SeasonPills where Trailing == EmptyView {
    init(_ pills: [SeasonPill], choose: @escaping (String) -> Void, @ViewBuilder menu: @escaping (String) -> Menu) {
        self.init(pills, choose: choose, menu: menu, trailing: { EmptyView() })
    }
}

/// A season's episodes in a strip of cards, as the prototype's and Android's:
/// a press plays, a long press asks what else, and the strip opens at the
/// episode Play starts (`reveal`) rather than at episode 1 of a half-watched
/// season. The cards are plain buttons in the card style, each its own stop.
struct EpisodeStrip<Item: Identifiable, Card: View, Menu: View>: View where Item.ID == String {
    @Environment(\.glassMetrics) private var metrics
    let items: [Item]
    /// The episode the strip opens at; nil opens at the first.
    let reveal: String?
    let play: (Item) -> Void
    /// What VoiceOver says a press does.
    let hint: (Item) -> String
    /// A card's accessibility identifier, for the tests; empty for none.
    let identifier: (Item) -> String
    /// A card scrolled into view, by its place: a longer season asks for its next page.
    let reached: (Int) -> Void
    let card: (Item) -> Card
    let menu: (Item) -> Menu
    @State private var revealed = ""

    init(_ items: [Item], reveal: String?, play: @escaping (Item) -> Void, hint: @escaping (Item) -> String,
         identifier: @escaping (Item) -> String = { _ in "" }, reached: @escaping (Int) -> Void = { _ in },
         @ViewBuilder card: @escaping (Item) -> Card, @ViewBuilder menu: @escaping (Item) -> Menu) {
        self.items = items
        self.reveal = reveal
        self.play = play
        self.hint = hint
        self.identifier = identifier
        self.reached = reached
        self.card = card
        self.menu = menu
    }

    var body: some View {
        ScrollViewReader { reader in
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: metrics.gap) {
                    ForEach(Array(items.enumerated()), id: \.element.id) { index, item in
                        Button {
                            play(item)
                        } label: {
                            card(item).frame(width: metrics.episode)
                        }
                        .buttonStyle(GlassCardStyle())
                        .accessibilityHint(hint(item))
                        .accessibilityIdentifier(identifier(item))
                        .contextMenu { menu(item) }
                        .onAppear { reached(index) }
                        // Ⓐ plays it, as a press does (#46).
                        .padFocusable(item.id, ring: .card) { play(item) }
                    }
                }
                .padding(.top, 12)
                .padding(.bottom, 16)
            }
            // Margins rather than padding, so the episode scrolled to below
            // stops at the page's margin, not against the screen's edge.
            .contentMargins(.horizontal, metrics.margin, for: .scrollContent)
            // A controller goes along the episodes by position (#46).
            .padGroup("episodes", .row, members: items.map(\.id), strip: true)
            .onChange(of: items.map(\.id)) { _, ids in
                guard let id = reveal, ids.contains(id), revealed != id, ids.first != id else { return }
                revealed = id
                reader.scrollTo(id, anchor: .leading)
            }
        }
    }
}

/// One episode in a season's strip (`.card.ep`; Android `ui/EpisodeCardView`):
/// its 16:9 still with the progress inside, UP NEXT on the one Play starts, a
/// tick on a watched one, "5. Title" and "22 min · 69% watched" under it. The
/// still is whatever the page has of it, from the hub or from the device.
/// Two things are left for a page to use: a `badge` in the still's bottom
/// corner (a download's state) and `selected`, which rings the still in the
/// accent while a page is choosing episodes.
struct EpisodeCard<Still: View, Badge: View>: View {
    @Environment(\.glassMetrics) private var metrics
    @Environment(\.glassAccent) private var accent
    let title: String
    let detail: String
    let played: Bool
    /// How far through it is, 0 to 1; a watched one reads as watched, not as part way.
    let progress: Double
    let upNext: Bool
    let selected: Bool
    let still: Still
    let badge: Badge

    init(title: String, detail: String, played: Bool, progress: Double, upNext: Bool, selected: Bool = false,
         @ViewBuilder still: () -> Still, @ViewBuilder badge: () -> Badge) {
        self.title = title
        self.detail = detail
        self.played = played
        self.progress = progress
        self.upNext = upNext
        self.selected = selected
        self.still = still()
        self.badge = badge()
    }

    private var watched: Bool { ResumeRules.showsWatched(played: played, progress: progress) }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Color.clear
                .aspectRatio(16 / 9, contentMode: .fit)
                .overlay { still }
                .overlay { ArtworkProgress(progress: watched ? 0 : progress) }
                .overlay { PlayDisc() }
                .clipShape(RoundedRectangle(cornerRadius: metrics.radius, style: .continuous))
                .overlay(alignment: .topLeading) {
                    if upNext && !watched { UpNextTag().padding(8) }
                }
                .overlay(alignment: .topTrailing) {
                    if watched {
                        WatchBadge(played: played, progress: progress, unplayedCount: 0, favorite: false)
                            .padding(6)
                    }
                }
                .overlay(alignment: .bottomTrailing) { badge.padding(6) }
                .overlay {
                    if selected {
                        RoundedRectangle(cornerRadius: metrics.radius, style: .continuous).strokeBorder(accent.tint, lineWidth: 3)
                    }
                }
                .litArtwork(corner: metrics.radius)
            CardCaption(title: title, detail: detail)
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

extension EpisodeCard where Badge == EmptyView {
    init(title: String, detail: String, played: Bool, progress: Double, upNext: Bool, selected: Bool = false,
         @ViewBuilder still: () -> Still) {
        self.init(title: title, detail: detail, played: played, progress: progress, upNext: upNext, selected: selected,
                  still: still, badge: { EmptyView() })
    }
}

extension EpisodeCard where Still == ArtworkView, Badge == EmptyView {
    /// An episode of the library, its still from the hub.
    init(episode: HubKit.LibraryItem, upNext: Bool) {
        self.init(title: DetailLines.episodeTitle(episode), detail: DetailLines.episodeMeta(episode), played: episode.played,
                  progress: episode.progress, upNext: upNext) {
            ArtworkView(path: episode.thumb.isEmpty ? episode.poster : episode.thumb, width: 480)
        }
    }
}
