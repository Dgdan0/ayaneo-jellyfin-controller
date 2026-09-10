package api

import (
	"context"
	"crypto/sha256"
	"encoding/binary"
	"net/http"
	"strconv"
	"strings"
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
	jellyfinClient, ok := s.jellyfinForRequest(w, r)
	if !ok {
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
			page, err := jellyfinClient.Favorites(ctx, homeRowLimit)
			return itemsOf(page), err
		}},
		{"continue", "Continue watching", func(ctx context.Context) ([]jellyfin.Item, error) {
			page, err := jellyfinClient.Resume(ctx, homeRowLimit)
			return itemsOf(page), err
		}},
		{"nextup", "Next up", func(ctx context.Context) ([]jellyfin.Item, error) {
			page, err := jellyfinClient.NextUp(ctx, homeRowLimit)
			return itemsOf(page), err
		}},
		{"latest", "Recently added", func(ctx context.Context) ([]jellyfin.Item, error) {
			return jellyfinClient.Latest(ctx, homeRowLimit)
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
			cacheKey := "home:" + jellyfinClient.UserID() + ":" + spec.id
			items, meta, err := cache.Fetch(ctx, s.cache, cacheKey, cache.UserData,
				func(ctx context.Context) ([]jellyfin.Item, error) {
					return spec.fetch(ctx)
				})
			results[i] = fetched{items: items, meta: meta, err: err}
		}(i, spec)
	}
	wg.Wait()

	// Home represents the next useful action for a show. While an episode is
	// partly watched, that show belongs only in Continue watching; once the
	// episode is completed Jellyfin removes it from Resume and its next episode
	// can appear in Next up. Resume can also return several unfinished episodes
	// from one show, so keep the first (Jellyfin's most recent) card per series.
	if results[1].err == nil {
		results[1].items = distinctHomeSeries(results[1].items)
		if results[2].err == nil {
			results[2].items = excludeResumableSeries(results[1].items, results[2].items)
		}
	}

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
		hits := s.homeRowHits(spec.id, got.items)
		if len(hits) == 0 {
			continue
		}
		out.Rows = append(out.Rows, DiscoverRow{
			ID:         spec.id,
			Title:      spec.title,
			Page:       1,
			TotalPages: 1,
			Items:      hits,
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

func (s *Server) homeRowHits(rowID string, items []jellyfin.Item) []SearchHit {
	if rowID != "latest" {
		return s.itemsToHits(items)
	}

	// Recently Added is a title row: movies stay movies, while a newly added
	// episode represents its parent series. Keep Jellyfin's recency order and
	// collapse multiple episodes from the same batch into one series card.
	out := make([]SearchHit, 0, len(items))
	seen := make(map[string]bool, len(items))
	for _, item := range items {
		title := item
		switch item.Type {
		case "Movie":
			// Already a title-level item.
		case "Episode":
			if item.SeriesID == "" || item.SeriesName == "" {
				continue
			}
			title = jellyfin.Item{
				ID:             item.SeriesID,
				Name:           item.SeriesName,
				Type:           "Series",
				ProductionYear: item.ProductionYear,
				ImageTags: map[string]string{
					"Primary": item.SeriesPrimaryImageTag,
				},
			}
		default:
			continue
		}
		key := strings.ToLower(title.Type + ":" + title.ID)
		if seen[key] {
			continue
		}
		seen[key] = true
		out = append(out, s.itemToHit(title))
		if len(out) == homeRowLimit {
			break
		}
	}
	return out
}

func distinctHomeSeries(items []jellyfin.Item) []jellyfin.Item {
	out := make([]jellyfin.Item, 0, len(items))
	seen := make(map[string]bool, len(items))
	for _, item := range items {
		key := homeItemIdentity(item)
		if seen[key] {
			continue
		}
		seen[key] = true
		out = append(out, item)
	}
	return out
}

func excludeResumableSeries(
	resume []jellyfin.Item, nextUp []jellyfin.Item,
) []jellyfin.Item {
	resumable := make(map[string]bool, len(resume))
	for _, item := range resume {
		if key := homeSeriesIdentity(item); key != "" {
			resumable[key] = true
		}
	}
	out := make([]jellyfin.Item, 0, len(nextUp))
	for _, item := range nextUp {
		if key := homeSeriesIdentity(item); key != "" && resumable[key] {
			continue
		}
		out = append(out, item)
	}
	return out
}

func homeItemIdentity(item jellyfin.Item) string {
	if key := homeSeriesIdentity(item); key != "" {
		return key
	}
	return "item:" + strings.ToLower(item.ID)
}

func homeSeriesIdentity(item jellyfin.Item) string {
	if strings.EqualFold(item.Type, "Episode") && item.SeriesID != "" {
		return "series:" + strings.ToLower(item.SeriesID)
	}
	return ""
}

// LibraryView is one of Jellyfin's top-level folders.
type LibraryView struct {
	ID   string `json:"id"`
	Name string `json:"name"`
	// "movies" or "tvshows", from Jellyfin's CollectionType.
	Kind string `json:"kind"`
	// The view's own Primary image when one exists; otherwise one contained
	// title selected deterministically for the media PC's current day.
	Image string `json:"image,omitempty"`
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
	jellyfinClient, ok := s.jellyfinForRequest(w, r)
	if !ok {
		return
	}
	ctx, cancel := timeoutFor(r, s.cfg.Server.RequestTimeout.OrDefault(20*time.Second))
	defer cancel()

	viewsKey := "library:views:" + jellyfinClient.UserID()
	views, meta, err := cache.Fetch(ctx, s.cache, viewsKey, cache.Metadata,
		func(ctx context.Context) ([]jellyfin.Item, error) {
			return jellyfinClient.Views(ctx)
		})
	if err != nil {
		writeUpstreamError(w, r, "jellyfin", err)
		return
	}

	filtered := make([]jellyfin.Item, 0, len(views))
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
		filtered = append(filtered, view)
		out.Views = append(out.Views, LibraryView{
			ID: view.ID, Name: view.Name, Kind: view.CollectionType,
		})
	}

	// A library selector is opened often, so its artwork should feel alive but
	// must not reshuffle while the user navigates back and forth. Each folder's
	// fallback is therefore selected from its Jellyfin contents by a hash of the
	// date and folder id. The folder's own image always wins; that preserves
	// deliberately curated art such as the Marvel libraries on this server.
	day := time.Now().Format("2006-01-02")
	errs := make([]error, len(filtered))
	var wg sync.WaitGroup
	for i, view := range filtered {
		wg.Add(1)
		go func(i int, view jellyfin.Item) {
			defer wg.Done()
			out.Views[i].Image, errs[i] = s.libraryViewArtwork(ctx, jellyfinClient, view, day)
		}(i, view)
	}
	wg.Wait()
	for i, err := range errs {
		if err == nil {
			continue
		}
		out.Partial = append(out.Partial, Partial{
			Service: "jellyfin", Reason: "artwork_unavailable",
			Affects: []string{"views." + out.Views[i].ID + ".image"},
			Message: out.Views[i].Name + " artwork could not be loaded",
		})
	}
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) libraryViewArtwork(
	ctx context.Context, client *jellyfin.Client, view jellyfin.Item, day string,
) (string, error) {
	if tag := view.PosterTag(); tag != "" {
		images, _, err := cache.Fetch(ctx, s.cache, "library:view-images:"+view.ID, cache.Metadata,
			func(ctx context.Context) ([]jellyfin.ImageInfo, error) {
				return client.Images(ctx, view.ID)
			})
		// If Jellyfin cannot describe the source, retaining its current art is
		// safer than throwing away a possible user choice.
		if err != nil || hasExplicitLibraryArtwork(images) {
			return jellyfinImagePrefix + "/" + view.ID + "/Primary?tag=" + tag, nil
		}
	}

	artKey := "library:art:" + client.UserID() + ":" + day + ":" + view.ID
	item, _, err := cache.Fetch(ctx, s.cache, artKey, cache.Metadata,
		func(ctx context.Context) (*jellyfin.Item, error) {
			first, err := client.Items(ctx, jellyfin.ItemsQuery{
				ParentID: view.ID, Recursive: true, Types: "Movie,Series",
				SortBy: "SortName", SortOrder: "Ascending", Limit: 1,
			})
			if err != nil || first.TotalRecordCount == 0 || len(first.Items) == 0 {
				return nil, err
			}
			start := dailyLibraryArtworkIndex(day, view.ID, first.TotalRecordCount)
			if start == 0 && first.Items[0].PosterTag() != "" {
				return &first.Items[0], nil
			}
			page, err := client.Items(ctx, jellyfin.ItemsQuery{
				ParentID: view.ID, Recursive: true, Types: "Movie,Series",
				SortBy: "SortName", SortOrder: "Ascending", Limit: 12, StartIndex: start,
			})
			if err != nil {
				return nil, err
			}
			for i := range page.Items {
				if page.Items[i].PosterTag() != "" {
					return &page.Items[i], nil
				}
			}
			// A run of items without artwork near the end should not leave the
			// folder blank when the beginning has usable posters.
			if start > 0 {
				page, err = client.Items(ctx, jellyfin.ItemsQuery{
					ParentID: view.ID, Recursive: true, Types: "Movie,Series",
					SortBy: "SortName", SortOrder: "Ascending", Limit: 12,
				})
				if err != nil {
					return nil, err
				}
				for i := range page.Items {
					if page.Items[i].PosterTag() != "" {
						return &page.Items[i], nil
					}
				}
			}
			return &first.Items[0], nil
		})
	if err != nil || item == nil {
		return "", err
	}
	if tag := item.PosterTag(); tag != "" {
		return jellyfinImagePrefix + "/" + item.PosterItemID() + "/Primary?tag=" + tag, nil
	}
	return "", nil
}

func hasExplicitLibraryArtwork(images []jellyfin.ImageInfo) bool {
	for _, image := range images {
		if image.ImageType != "Primary" || image.Path == "" {
			continue
		}
		path := strings.ToLower(strings.ReplaceAll(image.Path, "\\", "/"))
		// Jellyfin 10.11 generates view collages here. Images beside the
		// configured collection (for example root/default/Marvel/folder.jpg)
		// are deliberate library art and must win over rotation.
		return !strings.Contains(path, "/metadata/library/")
	}
	return false
}

func dailyLibraryArtworkIndex(day, viewID string, total int) int {
	if total <= 1 {
		return 0
	}
	sum := sha256.Sum256([]byte(day + ":" + viewID))
	return int(binary.BigEndian.Uint64(sum[:8]) % uint64(total))
}

type LibraryItemsResponse struct {
	ViewID     string      `json:"viewId"`
	Title      string      `json:"title"`
	Page       int         `json:"page"`
	TotalPages int         `json:"totalPages"`
	Total      int         `json:"total"`
	SortedBy   string      `json:"sortedBy"`
	SortOrder  string      `json:"sortOrder"`
	Items      []SearchHit `json:"items"`
	Partial    []Partial   `json:"partial"`
	Cache      CacheInfo   `json:"cache"`
}

const libraryPageSize = 60

// handleLibraryItems pages one folder.
func (s *Server) handleLibraryItems(w http.ResponseWriter, r *http.Request) {
	jellyfinClient, ok := s.jellyfinForRequest(w, r)
	if !ok {
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
	sortKey, sortBy, ok := librarySort(r.URL.Query().Get("sort"))
	if !ok {
		writeError(w, r, http.StatusBadRequest, Error{
			Code: CodeInvalidRequest, Message: "unknown library sort",
		})
		return
	}
	orderKey, sortOrder, ok := librarySortOrder(r.URL.Query().Get("order"))
	if !ok {
		writeError(w, r, http.StatusBadRequest, Error{
			Code: CodeInvalidRequest, Message: "unknown library sort order",
		})
		return
	}

	ctx, cancel := timeoutFor(r, s.cfg.Server.RequestTimeout.OrDefault(20*time.Second))
	defer cancel()

	cacheKey := "library:" + jellyfinClient.UserID() + ":" + viewID + ":" +
		sortKey + ":" + orderKey + ":" + strconv.Itoa(page)
	result, meta, err := cache.Fetch(ctx, s.cache, cacheKey, cache.LibraryPage,
		func(ctx context.Context) (*jellyfin.ItemsPage, error) {
			return jellyfinClient.Items(ctx, jellyfin.ItemsQuery{
				ParentID:   viewID,
				Recursive:  true,
				Types:      "Movie,Series",
				Fields:     "ProviderIds",
				SortBy:     sortBy,
				SortOrder:  sortOrder,
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
		SortedBy:   sortKey,
		SortOrder:  orderKey,
		Items:      s.itemsToHits(result.Items),
		Partial:    []Partial{},
		Cache:      cacheInfoFrom(meta),
	})
}

func librarySort(value string) (key, jellyfinValue string, ok bool) {
	switch value {
	case "", "name":
		return "name", "SortName", true
	case "release":
		return "release", "PremiereDate", true
	case "added":
		return "added", "DateCreated", true
	case "year":
		return "year", "ProductionYear", true
	case "rating":
		return "rating", "CommunityRating", true
	case "parental":
		return "parental", "OfficialRating", true
	case "played":
		return "played", "DatePlayed", true
	default:
		return "", "", false
	}
}

func librarySortOrder(value string) (key, jellyfinValue string, ok bool) {
	switch value {
	case "", "asc":
		return "asc", "Ascending", true
	case "desc":
		return "desc", "Descending", true
	default:
		return "", "", false
	}
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
