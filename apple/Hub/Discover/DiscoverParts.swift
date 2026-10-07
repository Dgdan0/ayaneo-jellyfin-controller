import HubKit
import SwiftUI

// The request side's shared parts (#17): where a card leads, the
// availability chip, the poster with it, and a glass search field.

/// A title Jellyseerr knows, by its media key ("tmdb:series:5920").
struct MediaRoute: Hashable, Identifiable {
    let key: String
    let title: String

    var id: String { key }
}

/// A performer's films and series.
struct PersonRoute: Hashable {
    let id: Int
    let name: String
}

/// A series' aired episodes, before a release search of one of them.
struct ReleaseTargetsRoute: Hashable {
    let key: String
    let title: String
    let seasons: [SeasonOption]
    let poster: String
    /// The season to open on, when the page was reached from one (a Library
    /// series' season or episode, #34); nil opens on the first real season.
    var startSeason: Int?
}

/// An interactive release search: a film, a series' season, or one episode.
struct ReleasesRoute: Hashable {
    let key: String
    /// "Gran Torino", "Last Seen · Season 1", "Last Seen · S1E4 · Title".
    let heading: String
    /// Nil for a film. A series always names one (0 is Specials).
    let season: Int?
    var episode: Int?
}

extension MediaHit {
    /// A title in the Jellyfin library opens its library page, with Play; one
    /// you do not have opens on its way in (the prototype's `postCard`).
    var route: AppRoute {
        jellyfinItemId.isEmpty
            ? .media(MediaRoute(key: media.key, title: media.title))
            : .title(TitleRoute(itemId: jellyfinItemId, title: media.title))
    }

    /// The page's picture while the card is in focus: its backdrop, else its poster.
    var pageArtwork: String { media.backdrop.isEmpty ? media.poster : media.backdrop }
}

/// Where a title is, on its poster's corner (`.av`): In library, Partial, On
/// the way, Requested, Downloading. Nothing for a title not in the library,
/// or one whose state is not known.
struct AvailabilityChip: View {
    let availability: String

    var body: some View {
        if let label = Availability.label(availability), let tone = Availability.tone(availability) {
            Text(label)
                .font(HubType.chrome(10.5, weight: .bold))
                .lineLimit(1)
                .padding(.horizontal, 8)
                .padding(.vertical, 5)
                .foregroundStyle(Color(argb: Availability.ink(tone)))
                .background(Color(argb: Availability.fill(tone)), in: Capsule())
                .overlay { Capsule().strokeBorder(Color(argb: GlassColors.edge), lineWidth: 1) }
                .accessibilityLabel(label)
        }
    }
}

/// A Jellyseerr title's poster (`.card.post`): its chip at the top left, a
/// download's progress inside it, and optionally its name and "2008 · Movie"
/// under it. Put it in a `GlassCardStyle` button for its ring.
struct DiscoverPoster: View {
    let hit: MediaHit
    var caption = false
    @Environment(\.glassMetrics) private var metrics

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Color.clear
                .aspectRatio(2 / 3, contentMode: .fit)
                .overlay { ArtworkView(path: hit.media.poster, width: 360) }
                .overlay { ArtworkProgress(progress: hit.progress) }
                .clipShape(RoundedRectangle(cornerRadius: metrics.radius, style: .continuous))
                .overlay(alignment: .topLeading) { AvailabilityChip(availability: hit.availability).padding(6) }
                .litArtwork(corner: metrics.radius)
            if caption {
                CardCaption(title: hit.media.title, detail: hit.subtitle)
            }
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityLabel(hit.media.title)
    }
}

/// A glass search field (the prototype's `.search`): the shell hides the
/// system bar that `.searchable` lives in. One opened by a button takes the
/// keyboard at once (`autofocus`).
struct GlassSearchField: View {
    let placeholder: String
    @Binding var query: String
    var autofocus = false
    var onSubmit: () -> Void = {}
    @FocusState private var focused: Bool

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(.white.opacity(0.8))
            TextField(placeholder, text: $query)
                .textFieldStyle(.plain)
                .font(HubType.body(15))
                .autocorrectionDisabled()
                .focused($focused)
                .onAppear { if autofocus { focused = true } }
                .onSubmit(onSubmit)
                #if os(iOS)
                .textInputAutocapitalization(.never)
                .submitLabel(.search)
                #endif
            if !query.isEmpty {
                Button {
                    query = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.white.opacity(0.6))
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Clear the search")
            }
        }
        .padding(.horizontal, 16)
        .frame(height: 44)
        .frame(maxWidth: 460)
        .glassPanel(Capsule())
    }
}

/// A row of a page's cards (`.row`): its title, then the cards side by side,
/// with room above and below for a lit card's lift and ring.
struct CardRow<Content: View>: View {
    let title: String
    @ViewBuilder let content: Content
    @Environment(\.glassMetrics) private var metrics

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(title)
                .font(HubType.body(metrics.rowTitle, weight: .bold, relativeTo: .title3))
                .foregroundStyle(.white)
                .padding(.horizontal, metrics.margin)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: metrics.gap) { content }
                    .padding(.horizontal, metrics.margin)
                    .padding(.top, 12)
                    .padding(.bottom, 16)
            }
        }
        .padding(.top, 8)
    }
}

/// A calm glass card for a page with nothing to show (`.empty`).
struct GlassEmpty: View {
    let title: String
    let detail: String

    var body: some View {
        VStack(spacing: 6) {
            Text(title)
                .font(HubType.body(17, weight: .bold, relativeTo: .headline))
                .foregroundStyle(.white)
            Text(detail)
                .font(HubType.body(15, relativeTo: .subheadline))
                .foregroundStyle(.white.opacity(0.75))
        }
        .multilineTextAlignment(.center)
        .frame(maxWidth: .infinity)
        .padding(28)
        .glassPanel(RoundedRectangle(cornerRadius: 22, style: .continuous))
    }
}
