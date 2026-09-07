package api

import (
	"context"
	"net/http"
	"strconv"
	"sync"
	"time"

	"ayaneohub/internal/adapters/jellyfin"
	"ayaneohub/internal/cache"
)

// The Home screen: what you were watching, then what is next.
//
// Ordered by how soon you would act on the row, which is not the order a media
// server would give you:
//
//  1. **Favourites** — things you deliberately marked. Measured empty on this
//     install, so the row hides itself rather than showing a blank strip.
//  2. **Continue watching** — 14 part-watched items here, all episodes.
//  3. **Next up** — you finished an episode and the next exists. 22 here.
//     Jellyfin's own endpoint, never derived from watch state: it handles
//     specials, gaps and season boundaries.
//  4. **Recently added.**
//
// All four in one request, fetched concurrently, for the same reason Discover
// is: four sequential round trips to build one screen is the difference between
// instant and sluggish on a phone connection.

const homeRowLimit = 24

type HomeResponse struct {
	Rows    []DiscoverRow `json:"rows"`
	Partial []Partial     `json:"partial"`
	Cache   CacheInfo     `json:"cache"`
}

func (s *Server) handleHome(w http.ResponseWriter, r *http.Request) {
	if s.jellyfin == nil {
		writeError(w, r, http.StatusServiceUnavailable, Error{
			Code: CodeUpstreamDown, Service: "jellyfin",
			Message: "Jellyfin is not configured",
		})
		return
	}
	if s.jellyfin.UserID() == "" {
		writeError(w, r, http.StatusServiceUnavailable, Error{
			Code:    "misconfigured",
			Service: "jellyfin",
			Message: "no Jellyfin user is configured — set services.jellyfin.user_id",
		})
		return
	}

	ctx, cancel := timeoutFor(r, s.cfg.Server.RequestTimeout.OrDefault(25*time.Second))
	defer cancel()

	type rowSpec struct {
		id    string
		title string
		fetch func(context.Context) ([]jellyfin.Item, error)
	}
	specs := []rowSpec{
		{"favourites", "Favourites", func(ctx context.Context) ([]jellyfin.Item, error) {
			page, err := s.jellyfin.Favorites(ctx, homeRowLimit)
			return itemsOf(page), err
		}},
		{"continue", "Continue watching", func(ctx context.Context) ([]jellyfin.Item, error) {
			page, err := s.jellyfin.Resume(ctx, homeRowLimit)
			return itemsOf(page), err
		}},
		{"nextup", "Next up", func(ctx context.Context) ([]jellyfin.Item, error) {
			page, err := s.jellyfin.NextUp(ctx, homeRowLimit)
			return itemsOf(page), err
		}},
		{"latest", "Recently added", func(ctx context.Context) ([]jellyfin.Item, error) {
			return s.jellyfin.Latest(ctx, homeRowLimit)
		}},
	}

	type fetched struct {
		items []jellyfin.Item
		meta  cache.Meta
		err   error
	}
	results := make([]fetched, len(specs))

	var wg sync.WaitGroup
	for i, spec := range specs {
		wg.Add(1)
		go func(i int, spec rowSpec) {
			defer wg.Done()
			items, meta, err := cache.Fetch(ctx, s.cache, "home:"+spec.id, cache.UserData,
				func(ctx context.Context) ([]jellyfin.Item, error) {
					return spec.fetch(ctx)
				})
			results[i] = fetched{items: items, meta: meta, err: err}
		}(i, spec)
	}
	wg.Wait()

	out := HomeResponse{Rows: make([]DiscoverRow, 0, len(specs)), Partial: []Partial{}}
	var oldest time.Duration
	anyHit := true
	for i, spec := range specs {
		got := results[i]
		if got.err != nil {
			out.Partial = append(out.Partial, Partial{
				Service: "jellyfin",
				Reason:  "row_unavailable",
				Affects: []string{"rows." + spec.id},
				Message: spec.title + " could not be loaded",
			})
			continue
		}
		// An empty row is dropped, not sent empty. Favourites is empty on this
		// install and a blank labelled strip is worse than no strip.
		if len(got.items) == 0 {
			continue
		}
		out.Rows = append(out.Rows, DiscoverRow{
			ID:         spec.id,
			Title:      spec.title,
			Page:       1,
			TotalPages: 1,
			Items:      s.itemsToHits(got.items),
		})
		if got.meta.Age > oldest {
			oldest = got.meta.Age
		}
		if !got.meta.Hit {
			anyHit = false
		}
	}

	out.Cache = CacheInfo{Hit: anyHit, AgeSeconds: int(oldest.Seconds())}
	writeJSON(w, http.StatusOK, out)
}

// LibraryView is one of Jellyfin's top-level folders.
type LibraryView struct {
	ID   string `json:"id"`
	Name string `json:"name"`
	// "movies" or "tvshows", from Jellyfin's CollectionType.
	Kind string `json:"kind"`
}

type LibraryResponse struct {
	Views   []LibraryView `json:"views"`
	Partial []Partial     `json:"partial"`
	Cache   CacheInfo     `json:"cache"`
}

// handleLibrary lists the library's top level.
//
// Five folders on this install: Anime, Marvel Movies, Marvel TV, Movies, Shows.
// Two of those are collections rather than the whole library, which is exactly
// why this is read from the server rather than assumed to be "Movies" and "TV".
func (s *Server) handleLibrary(w http.ResponseWriter, r *http.Request) {
	if s.jellyfin == nil {
		writeError(w, r, http.StatusServiceUnavailable, Error{
			Code: CodeUpstreamDown, Service: "jellyfin",
			Message: "Jellyfin is not configured",
		})
		return
	}
	ctx, cancel := timeoutFor(r, s.cfg.Server.RequestTimeout.OrDefault(20*time.Second))
	defer cancel()

	views, meta, err := cache.Fetch(ctx, s.cache, "library:views", cache.Metadata,
		func(ctx context.Context) ([]jellyfin.Item, error) {
			return s.jellyfin.Views(ctx)
		})
	if err != nil {
		writeUpstreamError(w, r, "jellyfin", err)
		return
	}

	out := LibraryResponse{
		Views:   make([]LibraryView, 0, len(views)),
		Partial: []Partial{},
		Cache:   cacheInfoFrom(meta),
	}
	for _, view := range views {
		// Music and photo folders are skipped: this app has nothing to do with
		// them and offering a tab that leads nowhere is worse than omitting it.
		if view.CollectionType != "movies" && view.CollectionType != "tvshows" {
			continue
		}
		out.Views = append(out.Views, LibraryView{
			ID: view.ID, Name: view.Name, Kind: view.CollectionType,
		})
	}
	writeJSON(w, http.StatusOK, out)
}

type LibraryItemsResponse struct {
	ViewID     string      `json:"viewId"`
	Title      string      `json:"title"`
	Page       int         `json:"page"`
	TotalPages int         `json:"totalPages"`
	Total      int         `json:"total"`
	Items      []SearchHit `json:"items"`
	Partial    []Partial   `json:"partial"`
	Cache      CacheInfo   `json:"cache"`
}

const libraryPageSize = 60

// handleLibraryItems pages one folder.
func (s *Server) handleLibraryItems(w http.ResponseWriter, r *http.Request) {
	if s.jellyfin == nil {
		writeError(w, r, http.StatusServiceUnavailable, Error{
			Code: CodeUpstreamDown, Service: "jellyfin",
			Message: "Jellyfin is not configured",
		})
		return
	}
	viewID := r.PathValue("viewId")
	// Straight into an upstream query parameter, so it is checked rather than
	// trusted: Jellyfin ids are 32 hex characters and nothing else.
	if !isHex32(viewID) {
		writeError(w, r, http.StatusBadRequest, Error{
			Code: CodeInvalidRequest, Message: "bad view id",
		})
		return
	}
	page, _ := strconv.Atoi(r.URL.Query().Get("page"))
	if page < 1 {
		page = 1
	}
	if page > 500 {
		writeError(w, r, http.StatusBadRequest, Error{
			Code: CodeInvalidRequest, Message: "page must be 500 or less",
		})
		return
	}

	ctx, cancel := timeoutFor(r, s.cfg.Server.RequestTimeout.OrDefault(20*time.Second))
	defer cancel()

	cacheKey := "library:" + viewID + ":" + strconv.Itoa(page)
	result, meta, err := cache.Fetch(ctx, s.cache, cacheKey, cache.LibraryPage,
		func(ctx context.Context) (*jellyfin.ItemsPage, error) {
			return s.jellyfin.Items(ctx, jellyfin.ItemsQuery{
				ParentID:   viewID,
				Recursive:  true,
				Types:      "Movie,Series",
				Fields:     "ProviderIds",
				SortBy:     "SortName",
				SortOrder:  "Ascending",
				Limit:      libraryPageSize,
				StartIndex: (page - 1) * libraryPageSize,
			})
		})
	if err != nil {
		writeUpstreamError(w, r, "jellyfin", err)
		return
	}

	totalPages := (result.TotalRecordCount + libraryPageSize - 1) / libraryPageSize
	if totalPages < 1 {
		totalPages = 1
	}
	writeJSON(w, http.StatusOK, LibraryItemsResponse{
		ViewID:     viewID,
		Page:       page,
		TotalPages: totalPages,
		Total:      result.TotalRecordCount,
		Items:      s.itemsToHits(result.Items),
		Partial:    []Partial{},
		Cache:      cacheInfoFrom(meta),
	})
}

func itemsOf(page *jellyfin.ItemsPage) []jellyfin.Item {
	if page == nil {
		return nil
	}
	return page.Items
}

func isHex32(s string) bool {
	if len(s) != 32 {
		return false
	}
	for _, c := range s {
		if !((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) {
			return false
		}
	}
	return true
}
