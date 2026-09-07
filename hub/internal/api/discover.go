package api

import (
	"context"
	"net/http"
	"strconv"
	"sync"
	"time"

	"ayaneohub/internal/adapters/jellyseerr"
	"ayaneohub/internal/cache"
)

// The Discover screen: rows of posters, the shape Infuse and Findroid use.
//
// Every row arrives in **one** request. That is the point: the handheld is often
// on a phone connection, and four sequential round trips to build one screen is
// the difference between instant and sluggish. The rows are fetched from
// Jellyseerr concurrently here, where the latency is a LAN hop.
//
// Once H2 lands, Favourites / Continue watching / Next up go **above** these,
// because something you already started matters more than something TMDB is
// promoting.

// discoverRow describes a feed. Order is display order.
var discoverRows = []struct {
	ID    string
	Title string
	Kind  jellyseerr.DiscoverKind
}{
	{"trending", "Trending now", jellyseerr.DiscoverTrending},
	{"movies", "Popular films", jellyseerr.DiscoverMovies},
	{"tv", "Popular series", jellyseerr.DiscoverTV},
	{"upcoming", "Coming soon", jellyseerr.DiscoverUpcoming},
}

type DiscoverRow struct {
	ID    string `json:"id"`
	Title string `json:"title"`
	Page  int    `json:"page"`
	// Jellyseerr reports six-figure totals for these feeds, so this is really
	// "there is more" rather than a number anyone will reach.
	TotalPages int         `json:"totalPages"`
	Items      []SearchHit `json:"items"`
}

type DiscoverResponse struct {
	Rows    []DiscoverRow `json:"rows"`
	Partial []Partial     `json:"partial"`
	Cache   CacheInfo     `json:"cache"`
}

// handleDiscover returns every row at once.
func (s *Server) handleDiscover(w http.ResponseWriter, r *http.Request) {
	client := s.jellyseerr
	if client == nil {
		writeError(w, r, http.StatusServiceUnavailable, Error{
			Code: CodeUpstreamDown, Service: "jellyseerr",
			Message: "Jellyseerr is not configured",
		})
		return
	}

	ctx, cancel := timeoutFor(r, s.cfg.Server.RequestTimeout.OrDefault(25*time.Second))
	defer cancel()

	scopes := TokenFrom(r.Context()).Scopes

	type fetched struct {
		index int
		body  *jellyseerr.SearchResponse
		meta  cache.Meta
		err   error
	}
	results := make([]fetched, len(discoverRows))

	var wg sync.WaitGroup
	for i, row := range discoverRows {
		wg.Add(1)
		go func(i int, id string, kind jellyseerr.DiscoverKind) {
			defer wg.Done()
			body, meta, err := cache.Fetch(ctx, s.cache, "discover:"+id+":1", cache.Discover,
				func(ctx context.Context) (*jellyseerr.SearchResponse, error) {
					return client.Discover(ctx, kind, 1)
				})
			results[i] = fetched{index: i, body: body, meta: meta, err: err}
		}(i, row.ID, row.Kind)
	}
	wg.Wait()

	out := DiscoverResponse{
		Rows:    make([]DiscoverRow, 0, len(discoverRows)),
		Partial: []Partial{},
	}
	var oldest time.Duration
	anyHit := true
	for i, row := range discoverRows {
		got := results[i]
		if got.err != nil {
			// A row that failed is named rather than silently missing, and the
			// rest of the screen still renders. This screen requires nothing in
			// particular to be useful.
			out.Partial = append(out.Partial, Partial{
				Service: "jellyseerr",
				Reason:  "row_unavailable",
				Affects: []string{"rows." + row.ID},
				Message: row.Title + " could not be loaded",
			})
			continue
		}
		items := make([]SearchHit, 0, len(got.body.Results))
		for _, result := range got.body.Results {
			// Person results come back in the trending feed and have no poster
			// or availability; a cast member is not something you can request.
			if result.MediaType == "person" {
				continue
			}
			items = append(items, hitFrom(result, scopes, imagePrefix))
		}
		if len(items) == 0 {
			continue
		}
		out.Rows = append(out.Rows, DiscoverRow{
			ID:         row.ID,
			Title:      row.Title,
			Page:       got.body.Page,
			TotalPages: got.body.TotalPages,
			Items:      items,
		})
		if got.meta.Age > oldest {
			oldest = got.meta.Age
		}
		if !got.meta.Hit {
			anyHit = false
		}
	}

	if len(out.Rows) == 0 {
		writeError(w, r, http.StatusServiceUnavailable, Error{
			Code: CodeUpstreamDown, Service: "jellyseerr",
			Message: "Could not load anything to show", Retryable: true,
		})
		return
	}

	// The oldest row wins, so "cached 4 minutes ago" is never optimistic.
	out.Cache = CacheInfo{Hit: anyHit, AgeSeconds: int(oldest.Seconds())}
	writeJSON(w, http.StatusOK, out)
}

// handleDiscoverRow pages one row, which is what makes the grid endless.
func (s *Server) handleDiscoverRow(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("row")
	var kind jellyseerr.DiscoverKind
	title := ""
	for _, row := range discoverRows {
		if row.ID == id {
			kind = row.Kind
			title = row.Title
			break
		}
	}
	if kind == "" {
		writeError(w, r, http.StatusNotFound, Error{
			Code: CodeInvalidRequest, Message: "unknown row " + id,
		})
		return
	}
	page, _ := strconv.Atoi(r.URL.Query().Get("page"))
	if page < 1 {
		page = 1
	}
	// A cap, because "page" arrives over the network and an unbounded value
	// would have the hub walking TMDB forever on someone else's behalf.
	if page > 500 {
		writeError(w, r, http.StatusBadRequest, Error{
			Code: CodeInvalidRequest, Message: "page must be 500 or less",
		})
		return
	}

	client := s.jellyseerr
	if client == nil {
		writeError(w, r, http.StatusServiceUnavailable, Error{
			Code: CodeUpstreamDown, Service: "jellyseerr",
			Message: "Jellyseerr is not configured",
		})
		return
	}

	ctx, cancel := timeoutFor(r, s.cfg.Server.RequestTimeout.OrDefault(20*time.Second))
	defer cancel()

	cacheKey := "discover:" + id + ":" + strconv.Itoa(page)
	body, meta, err := cache.Fetch(ctx, s.cache, cacheKey, cache.Discover,
		func(ctx context.Context) (*jellyseerr.SearchResponse, error) {
			return client.Discover(ctx, kind, page)
		})
	if err != nil {
		writeUpstreamError(w, r, "jellyseerr", err)
		return
	}

	scopes := TokenFrom(r.Context()).Scopes
	items := make([]SearchHit, 0, len(body.Results))
	for _, result := range body.Results {
		if result.MediaType == "person" {
			continue
		}
		items = append(items, hitFrom(result, scopes, imagePrefix))
	}

	writeJSON(w, http.StatusOK, DiscoverResponse{
		Rows: []DiscoverRow{{
			ID:         id,
			Title:      title,
			Page:       body.Page,
			TotalPages: body.TotalPages,
			Items:      items,
		}},
		Partial: []Partial{},
		Cache:   cacheInfoFrom(meta),
	})
}
