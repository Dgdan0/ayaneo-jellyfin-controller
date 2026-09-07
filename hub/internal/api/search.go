package api

import (
	"context"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"

	"ayaneohub/internal/adapters/jellyseerr"
	"ayaneohub/internal/cache"
)

// handleSearch is the app's entry point for "I want to watch X".
//
// Backed entirely by Jellyseerr, which annotates every result with its own
// mediaInfo -- so "you already have this" and "this is 63% downloaded" come back
// in the same round trip as the search itself. No other adapter is involved.
func (s *Server) handleSearch(w http.ResponseWriter, r *http.Request) {
	query := strings.TrimSpace(r.URL.Query().Get("q"))
	if query == "" {
		writeError(w, r, http.StatusBadRequest, Error{
			Code:    CodeInvalidRequest,
			Message: "q is required",
		})
		return
	}
	page, _ := strconv.Atoi(r.URL.Query().Get("page"))

	client := s.jellyseerr
	if client == nil {
		writeError(w, r, http.StatusServiceUnavailable, Error{
			Code:      CodeUpstreamDown,
			Service:   "jellyseerr",
			Message:   "Jellyseerr is not configured",
			Retryable: false,
		})
		return
	}

	ctx, cancel := timeoutFor(r, s.cfg.Server.RequestTimeout.OrDefault(20*time.Second))
	defer cancel()

	// Cached on the raw upstream response, not on the rendered one: actions
	// depend on the caller's scopes, so a read-only token must not be served a
	// response computed for a token that can request things.
	key := fmt.Sprintf("search:%s:%d", strings.ToLower(query), page)
	found, meta, err := cache.Fetch(ctx, s.cache, key, cache.Search,
		func(ctx context.Context) (*jellyseerr.SearchResponse, error) {
			return client.Search(ctx, query, page)
		})
	if err != nil {
		// Jellyseerr is load-bearing for this screen: with it down there is
		// nothing renderable, so this is one of the few endpoints that genuinely
		// fails rather than degrading.
		writeUpstreamError(w, r, "jellyseerr", err)
		return
	}

	scopes := TokenFrom(r.Context()).Scopes
	out := SearchResponse{
		Query:        query,
		Page:         found.Page,
		TotalPages:   found.TotalPages,
		TotalResults: found.TotalResults,
		Results:      make([]SearchHit, 0, len(found.Results)),
		Partial:      []Partial{},
		Cache:        cacheInfoFrom(meta),
	}
	for _, result := range found.Results {
		// People come back in a multi-search too, and there is nothing sensible
		// to do with a person on a media screen.
		if result.MediaType != "movie" && result.MediaType != "tv" {
			continue
		}
		out.Results = append(out.Results, hitFrom(result, scopes, imagePrefix))
	}

	// Reordered, not filtered. TMDB ranks on popularity-weighted relevance,
	// which occasionally puts a documentary called "The Mentalists" above the
	// series you typed; nothing is dropped, because a near-miss is sometimes
	// exactly what someone meant.
	rankSearchHits(query, out.Results)

	writeJSON(w, http.StatusOK, out)
}
