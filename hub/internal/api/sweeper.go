package api

import (
	"context"
	"log/slog"
	"strconv"
	"time"

	"ayaneohub/internal/adapters/jellyfin"
	"ayaneohub/internal/index"
)

// How often the provider-id index is rebuilt.
//
// Five minutes rather than the fifteen the plan proposed, because the sweep
// turned out to cost 362ms against this library — cheap enough that the only
// reason not to run it more often is politeness to the server. New imports show
// up within five minutes, which is well inside the time it takes anything to
// finish downloading.
const sweepInterval = 5 * time.Minute

// StartBackground kicks off the work that runs on a timer rather than on a
// request. Cancelled with the context when the hub shuts down.
func (s *Server) StartBackground(ctx context.Context) {
	s.startIndexSweeper(ctx)
}

// startIndexSweeper keeps the Jellyfin provider-id index current.
//
// Runs one sweep immediately so the first request is not answered from an empty
// index, then on a ticker. Failures are logged and retried on the next tick
// rather than being fatal: a Jellyfin that is down should cost the app its
// library rows, not the whole hub.
func (s *Server) startIndexSweeper(ctx context.Context) {
	if s.jellyfin == nil {
		return
	}
	go func() {
		s.sweepIndex(ctx)
		ticker := time.NewTicker(sweepInterval)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				s.sweepIndex(ctx)
			}
		}
	}()
}

func (s *Server) sweepIndex(parent context.Context) {
	ctx, cancel := context.WithTimeout(parent, 60*time.Second)
	defer cancel()

	started := time.Now()
	page, err := s.jellyfin.Sweep(ctx)
	if err != nil {
		slog.Warn("jellyfin index sweep failed", "error", err)
		return
	}

	sources := make([]index.Source, 0, len(page.Items))
	for _, item := range page.Items {
		sources = append(sources, index.Source{
			ItemID:       item.ID,
			Name:         item.Name,
			Year:         item.ProductionYear,
			JellyfinType: item.Type,
			Tmdb:         item.Tmdb(),
			Tvdb:         item.Tvdb(),
			Imdb:         item.Imdb(),
		})
	}
	snapshot := s.index.Rebuild(sources)

	slog.Info("jellyfin index",
		"movies", snapshot.Movies,
		"series", snapshot.Series,
		"tmdbCoverage", int(snapshot.TmdbCoverage()*100),
		"ms", time.Since(started).Milliseconds(),
	)
}

// libraryEntry answers "is this title in Jellyfin?" for a media key.
//
// TMDB first because that is what the app's keys are made of, then TVDB and
// IMDb as fallbacks for a series whose Jellyfin metadata was scraped from a
// different provider.
//
// @return the entry and true, or false when the index has no answer *or* has
// not been built yet -- the caller must not turn "unknown" into "you do not
// have this".
func (s *Server) libraryEntry(key MediaKey, tvdbID int, imdbID string) (index.Entry, bool) {
	if s.index == nil || !s.index.Ready() {
		return index.Entry{}, false
	}
	if entry, ok := s.index.ByTmdb(key.Type, strconv.Itoa(key.ID)); ok {
		return entry, true
	}
	if tvdbID > 0 {
		if entry, ok := s.index.ByTvdb(key.Type, strconv.Itoa(tvdbID)); ok {
			return entry, true
		}
	}
	if imdbID != "" {
		if entry, ok := s.index.ByImdb(key.Type, imdbID); ok {
			return entry, true
		}
	}
	return index.Entry{}, false
}

// itemsToHits renders Jellyfin items as the same SearchHit the app already
// draws, so the library and home rows reuse the poster grid unchanged.
func (s *Server) itemsToHits(items []jellyfin.Item) []SearchHit {
	out := make([]SearchHit, 0, len(items))
	for _, item := range items {
		out = append(out, s.itemToHit(item))
	}
	return out
}

func (s *Server) itemToHit(item jellyfin.Item) SearchHit {
	hit := SearchHit{
		Media: MediaRef{
			Type:  mediaTypeFor(item),
			Title: item.DisplayTitle(),
			Year:  item.ProductionYear,
		},
		Subtitle: item.Subtitle(),
		Overview: item.Overview,
		// It came out of the library, so it is in the library. No index lookup
		// needed and none would be more authoritative.
		Availability:   AvailAvailable,
		Rating:         item.CommunityRating,
		JellyfinItemID: item.ID,
		Progress:       item.Progress(),
		Actions:        []string{"detail"},
	}
	if tmdb := item.Tmdb(); tmdb != "" {
		hit.Media.Key = "tmdb:" + hit.Media.Type + ":" + tmdb
		if n, err := strconv.Atoi(tmdb); err == nil {
			hit.Media.IDs.Tmdb = n
		}
	}
	if tag := item.PosterTag(); tag != "" {
		hit.Media.Poster = jellyfinImagePrefix + "/" + item.PosterItemID() + "/Primary?tag=" + tag
	}
	return hit
}

// mediaTypeFor maps an item to the hub's two-word vocabulary.
//
// An episode reports as its series, because that is what its card shows and
// what tapping it should open.
func mediaTypeFor(item jellyfin.Item) string {
	switch item.Type {
	case "Movie":
		return "movie"
	default:
		return "series"
	}
}
