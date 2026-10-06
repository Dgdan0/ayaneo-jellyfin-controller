import HubKit
import SwiftUI

// Glass's cards and the marks on them (GLASS_PLAN.md, "Focus and pointer").
// The numbers are the prototype's `.card`, `.pic`, `.pbar`, `.num`, `.tag` and
// `.play`, with its iPad and iPhone sizes.

/// Sizes a page lays itself out by: the prototype's `--px`, `--tile`,
/// `--post`, `--ep`, `--gap` and `--r`, and its type sizes. The shell sets
/// them for every page: phone-sized cards where the width class is compact or
/// the window is short, and the bar's own margin, so the page lines up with
/// Media/Books.
struct GlassMetrics: Equatable {
    /// Narrow: a phone held upright, an iPad in a narrow Split View.
    var compact = false
    /// Short: a phone turned sideways, about 400 points tall (an iPhone Pro
    /// Max is regular width that way, but has a phone's height). A hero fits
    /// the height it has.
    var short = false
    var margin: CGFloat = 44

    /// Phone sizes.
    var small: Bool { compact || short }
    /// A hero's words centred over the picture's foot: a phone held upright.
    var centred: Bool { compact && !short }

    var tile: CGFloat { small ? 250 : 296 }
    var poster: CGFloat { small ? 104 : 126 }
    var episode: CGFloat { small ? 236 : 270 }
    var gap: CGFloat { small ? 14 : 18 }
    var radius: CGFloat { small ? 16 : 18 }
    var heroTitle: CGFloat { short ? 34 : (compact ? 40 : 56) }
    var pageTitle: CGFloat { small ? 30 : 32 }
    var rowTitle: CGFloat { small ? 18 : 19 }
}

extension GlassMetrics {
    /// A title page's (TitleView, MediaTitleView): phone-sideways placement
    /// and title where the words must start under the bars
    /// (`ShellLayout.titleWordsUnderBars`), else these.
    func forTitlePage(size: CGSize, safe: EdgeInsets) -> GlassMetrics {
        var page = self
        page.short = ShellLayout.titleWordsUnderBars(width: size.width, height: size.height + safe.top + safe.bottom,
                                                     short: short)
        return page
    }
}

extension EnvironmentValues {
    @Entry var glassMetrics = GlassMetrics()
    /// Whether the card this view belongs to is lit: a pointer resting on it,
    /// or keyboard or controller focus on it.
    @Entry var cardLit = false
}

/// A card's button. It lights the card while a pointer rests on it or focus is
/// on it, for its artwork's ring and lift (`litArtwork`); on the iPad a
/// resting pointer counts as focus (GLASS_PLAN.md).
struct GlassCardStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        GlassCardBody(configuration: configuration)
    }
}

private struct GlassCardBody: View {
    let configuration: ButtonStyleConfiguration
    @Environment(\.isFocused) private var focused
    @State private var hovering = false

    var body: some View {
        configuration.label
            .environment(\.cardLit, hovering || focused)
            .opacity(configuration.isPressed ? 0.85 : 1)
            .onHover { hovering = $0 }
    }
}

/// The artwork of a lit card: a 3 pt white ring standing outside it, a deeper
/// shadow and a small lift (`.card:hover .pic`).
struct LitArtwork: ViewModifier {
    let corner: CGFloat
    @Environment(\.cardLit) private var lit

    func body(content: Content) -> some View {
        content
            .overlay {
                RoundedRectangle(cornerRadius: corner + 3, style: .continuous)
                    .strokeBorder(Color.white, lineWidth: 3)
                    .padding(-3)
                    .opacity(lit ? 1 : 0)
            }
            .shadow(color: .black.opacity(lit ? 0.45 : 0), radius: 19, y: 11)
            .scaleEffect(lit ? 1.035 : 1)
            .offset(y: lit ? -3 : 0)
            .animation(.easeOut(duration: 0.22), value: lit)
    }
}

/// A lit row or dashboard card: the ring alone, with no lift (`.scard:hover`).
struct LitRing: ViewModifier {
    let corner: CGFloat
    @Environment(\.cardLit) private var lit

    func body(content: Content) -> some View {
        content
            .overlay {
                RoundedRectangle(cornerRadius: corner + 3, style: .continuous)
                    .strokeBorder(Color.white, lineWidth: 3)
                    .padding(-3)
                    .opacity(lit ? 1 : 0)
                    .animation(.easeOut(duration: 0.18), value: lit)
            }
    }
}

extension View {
    func litArtwork(corner: CGFloat) -> some View { modifier(LitArtwork(corner: corner)) }
    func litRing(corner: CGFloat) -> some View { modifier(LitRing(corner: corner)) }
}

/// How far into a title you are, as a white bar on a faint track inside the
/// artwork's lower edge (`.pbar`), 6% in from its sides and foot. Nothing for
/// a title not started or finished.
struct ArtworkProgress: View {
    let progress: Double

    var body: some View {
        if progress > 0 {
            GeometryReader { geometry in
                let inset = geometry.size.width * 0.06
                ZStack(alignment: .leading) {
                    Capsule().fill(.white.opacity(0.28))
                    Capsule().fill(.white)
                        .frame(width: max(4, (geometry.size.width - inset * 2) * min(1, progress)))
                }
                .frame(height: 4)
                .padding(.horizontal, inset)
                .frame(maxHeight: .infinity, alignment: .bottom)
                .padding(.bottom, inset)
            }
            .accessibilityLabel("\(Int(progress * 100))% watched")
        }
    }
}

/// The mark in a card's corner (`.num`): a tick in the accent when watched,
/// else how many episodes are unwatched on a white pill, else a star for a
/// favourite on the same pill.
struct WatchBadge: View {
    let played: Bool
    let progress: Double
    let unplayedCount: Int
    let favorite: Bool
    @Environment(\.glassAccent) private var accent

    var body: some View {
        if ResumeRules.showsWatched(played: played, progress: progress) {
            Image(systemName: "checkmark")
                .font(.system(size: 11, weight: .heavy))
                .foregroundStyle(accent.inkColor)
                .frame(width: 22, height: 22)
                .background(accent.tint, in: Circle())
                .accessibilityLabel("Watched")
        } else if unplayedCount > 0 {
            pill(Text("\(unplayedCount)").font(HubType.chrome(11, weight: .bold)))
                .accessibilityLabel("\(unplayedCount) unwatched")
        } else if favorite {
            pill(Image(systemName: "star.fill").font(.system(size: 10, weight: .bold)))
                .accessibilityLabel("Favourite")
        }
    }

    private func pill(_ content: some View) -> some View {
        content
            .foregroundStyle(Color.glassInk)
            .padding(.horizontal, 7)
            .frame(minWidth: 24, minHeight: 22)
            .background(.white.opacity(0.9), in: Capsule())
    }
}

/// UP NEXT on the episode Play starts (`.tag`).
struct UpNextTag: View {
    @Environment(\.glassAccent) private var accent

    var body: some View {
        Text("UP NEXT")
            .font(HubType.chrome(10, weight: .bold))
            .tracking(0.8)
            .padding(.horizontal, 8)
            .padding(.vertical, 5)
            .foregroundStyle(accent.inkColor)
            .background(accent.tint, in: Capsule())
    }
}

/// The play disc a lit tile shows in its middle (`.play`): where a press
/// would start it.
struct PlayDisc: View {
    @Environment(\.cardLit) private var lit

    var body: some View {
        Image(systemName: "play.fill")
            .font(.system(size: 17, weight: .bold))
            .foregroundStyle(.white)
            .offset(x: 1)
            .frame(width: 46, height: 46)
            .glassPanel(Circle())
            .opacity(lit ? 1 : 0)
            .animation(.easeOut(duration: 0.2), value: lit)
            .accessibilityHidden(true)
    }
}

/// The words under a card (`.c1`, `.c2`): its title in bold, a second line
/// softer.
struct CardCaption: View {
    let title: String
    let detail: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(HubType.body(15, weight: .bold, relativeTo: .subheadline))
                .foregroundStyle(.white)
            if !detail.isEmpty {
                Text(detail)
                    .font(HubType.body(13, relativeTo: .caption))
                    .foregroundStyle(.white.opacity(0.64))
            }
        }
        .lineLimit(1)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
