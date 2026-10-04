import HubKit
import SwiftUI

/// Where a title card leads: the title's own page.
struct TitleRoute: Hashable {
    let itemId: String
    let title: String
}

/// A 2:3 poster (`.card.post`) with its mark and progress, and optionally its
/// title and second line under it. It takes the width it is given, so one card
/// serves rows and the grid. Put it in a `GlassCardStyle` button for its ring.
struct PosterCard: View {
    let hit: MediaHit
    var caption = true
    @Environment(\.glassMetrics) private var metrics

    private var progress: Double {
        ResumeRules.showsWatched(played: hit.played, progress: hit.progress) ? 0 : hit.progress
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Color.clear
                .aspectRatio(2 / 3, contentMode: .fit)
                .overlay { ArtworkView(path: hit.media.poster, width: 360) }
                .overlay { ArtworkProgress(progress: progress) }
                .clipShape(RoundedRectangle(cornerRadius: metrics.radius, style: .continuous))
                .overlay(alignment: .topTrailing) {
                    WatchBadge(played: hit.played, progress: hit.progress, unplayedCount: hit.unplayedCount,
                               favorite: hit.favorite)
                        .padding(6)
                }
                .litArtwork(corner: metrics.radius)
            if caption {
                CardCaption(title: hit.media.title, detail: hit.subtitle)
            }
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

/// Continue watching and Next up (`.card.tile`): the episode's still, or a
/// film's backdrop, at 16:9 with the progress inside it and a play disc when
/// lit, the series name and "S1E4 · Title" under it.
struct LandscapeCard: View {
    let hit: MediaHit
    @Environment(\.glassMetrics) private var metrics

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Color.clear
                .aspectRatio(16 / 9, contentMode: .fit)
                .overlay { ArtworkView(path: hit.media.backdrop.isEmpty ? hit.media.poster : hit.media.backdrop, width: 640) }
                .overlay {
                    ArtworkProgress(progress: ResumeRules.showsWatched(played: hit.played, progress: hit.progress) ? 0 : hit.progress)
                }
                .overlay { PlayDisc() }
                .clipShape(RoundedRectangle(cornerRadius: metrics.radius, style: .continuous))
                .overlay(alignment: .topTrailing) {
                    WatchBadge(played: hit.played, progress: hit.progress, unplayedCount: 0, favorite: false)
                        .padding(6)
                }
                .litArtwork(corner: metrics.radius)
            CardCaption(title: hit.media.title, detail: hit.subtitle)
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}
