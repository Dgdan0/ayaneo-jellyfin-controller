import HubKit
import SwiftUI

/// Where a title card leads: the title's own page.
struct TitleRoute: Hashable {
    let itemId: String
    let title: String
}

/// The round mark on a card's artwork, as on Android: a tick when watched,
/// else how many episodes are unwatched, else a star for a favourite.
struct WatchBadge: View {
    let played: Bool
    let progress: Double
    let unplayedCount: Int
    let favorite: Bool

    var body: some View {
        if ResumeRules.showsWatched(played: played, progress: progress) {
            mark(Image(systemName: "checkmark").font(.system(size: 11, weight: .heavy)), fill: .available)
                .accessibilityLabel("Watched")
        } else if unplayedCount > 0 {
            mark(Text("\(unplayedCount)").font(HubType.body(12, weight: .bold, relativeTo: .caption)), fill: .accentColor)
                .accessibilityLabel("\(unplayedCount) unwatched")
        } else if favorite {
            mark(Image(systemName: "star.fill").font(.system(size: 10, weight: .bold)), fill: .accentColor)
                .accessibilityLabel("Favourite")
        }
    }

    private func mark(_ content: some View, fill: Color) -> some View {
        content
            .foregroundStyle(.white)
            .frame(minWidth: 24, minHeight: 24)
            .padding(.horizontal, 2)
            .background(fill, in: Capsule())
            .shadow(color: .black.opacity(0.25), radius: 2, y: 1)
    }
}

/// Watch progress along the bottom edge of artwork. Nothing when the title is
/// finished or not started.
struct ProgressStrip: View {
    let progress: Double

    var body: some View {
        if progress > 0 {
            GeometryReader { geometry in
                ZStack(alignment: .leading) {
                    Rectangle().fill(.black.opacity(0.45))
                    Rectangle().fill(Color.accentColor).frame(width: geometry.size.width * min(1, progress))
                }
            }
            .frame(height: 4)
            .accessibilityLabel("\(Int(progress * 100))% watched")
        }
    }
}

/// A 2:3 poster with its badge and progress, and optionally the title under it.
/// It takes the width it is given, so one card serves rows and the grid.
struct PosterCard: View {
    let hit: MediaHit
    var caption = true

    private var progress: Double {
        ResumeRules.showsWatched(played: hit.played, progress: hit.progress) ? 0 : hit.progress
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Color.clear
                .aspectRatio(2 / 3, contentMode: .fit)
                .overlay { ArtworkView(path: hit.media.poster, width: 360) }
                .overlay(alignment: .bottom) { ProgressStrip(progress: progress) }
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                .overlay(alignment: .topTrailing) {
                    WatchBadge(played: hit.played, progress: hit.progress, unplayedCount: hit.unplayedCount,
                               favorite: hit.favorite)
                        .padding(6)
                }
            if caption {
                VStack(alignment: .leading, spacing: 1) {
                    Text(hit.media.title)
                        .font(HubType.body(14, weight: .medium, relativeTo: .subheadline))
                        .foregroundStyle(Color.ink)
                    if !hit.subtitle.isEmpty {
                        Text(hit.subtitle)
                            .font(HubType.body(12, relativeTo: .caption))
                            .foregroundStyle(Color.muted)
                    }
                }
                .lineLimit(1)
            }
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

/// Continue watching and Next up: the episode's still (or a film's backdrop)
/// at 16:9, the series name and "S1E4 · Title" under it.
struct LandscapeCard: View {
    let hit: MediaHit

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Color.clear
                .aspectRatio(16 / 9, contentMode: .fit)
                .overlay { ArtworkView(path: hit.media.backdrop.isEmpty ? hit.media.poster : hit.media.backdrop, width: 640) }
                .overlay(alignment: .bottom) {
                    ProgressStrip(progress: ResumeRules.showsWatched(played: hit.played, progress: hit.progress) ? 0 : hit.progress)
                }
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                .overlay(alignment: .topTrailing) {
                    WatchBadge(played: hit.played, progress: hit.progress, unplayedCount: 0, favorite: false)
                        .padding(6)
                }
            VStack(alignment: .leading, spacing: 1) {
                Text(hit.media.title)
                    .font(HubType.body(15, weight: .semibold, relativeTo: .subheadline))
                    .foregroundStyle(Color.ink)
                Text(hit.subtitle)
                    .font(HubType.body(13, relativeTo: .caption))
                    .foregroundStyle(Color.muted)
            }
            .lineLimit(1)
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

/// A pill that picks one of a few: a library, a season.
struct ChoicePill: View {
    let title: String
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(HubType.body(15, weight: selected ? .semibold : .medium, relativeTo: .subheadline))
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .foregroundStyle(selected ? Color.accentInk : Color.ink)
                .background(selected ? Color.accentColor : Color.card, in: Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}
