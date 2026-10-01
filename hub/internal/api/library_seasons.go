package api

import (
	"context"

	"ayaneohub/internal/adapters/jellyfin"
)

// withoutFolderSeasons drops a "season" that is really a folder.
//
// Jellyfin turns any unrecognised folder inside a series into a season with no
// number, named after the folder. Avatar arrived as Series/"Avatar - The Last
// Airbender (2005 - 2008) [1080p]"/Book One - Water/..., and the wrapper showed
// up as a fourth season holding all 54 episodes again. Such a season has no
// number, and every episode in it carries a season number the series already
// lists; that is the test. Specials (episodes numbered season 0) and an
// unnumbered season with episodes found nowhere else are kept, since hiding
// those would hide something watchable.
func withoutFolderSeasons(
	ctx context.Context, seasons []jellyfin.Item,
	episodesOf func(ctx context.Context, seasonID string) ([]jellyfin.Item, error),
) []jellyfin.Item {
	numbered := map[int]bool{}
	for _, season := range seasons {
		if season.IndexNumber > 0 {
			numbered[season.IndexNumber] = true
		}
	}
	if len(numbered) == 0 {
		return seasons
	}
	kept := make([]jellyfin.Item, 0, len(seasons))
	for _, season := range seasons {
		if season.IndexNumber == 0 && duplicatesNumberedSeasons(ctx, season.ID, numbered, episodesOf) {
			continue
		}
		kept = append(kept, season)
	}
	return kept
}

func duplicatesNumberedSeasons(
	ctx context.Context, seasonID string, numbered map[int]bool,
	episodesOf func(ctx context.Context, seasonID string) ([]jellyfin.Item, error),
) bool {
	episodes, err := episodesOf(ctx, seasonID)
	if err != nil || len(episodes) == 0 {
		return false
	}
	for _, episode := range episodes {
		if !numbered[episode.ParentIndexNumber] {
			return false
		}
	}
	return true
}
