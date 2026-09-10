package api

import (
	"context"
	"encoding/json"
	"net/http"
	"strconv"
	"strings"
	"time"

	"ayaneohub/internal/adapters/jellyfin"
	"ayaneohub/internal/cache"
)

const libraryEpisodePageSize = 60

// LibraryItem is Jellyfin-native identity and metadata. It deliberately does
// not require a TMDB match: home videos and titles with incomplete metadata are
// still first-class library entries.
type LibraryItem struct {
	ID              string                `json:"id"`
	Type            string                `json:"type"`
	MediaKey        string                `json:"mediaKey,omitempty"`
	Title           string                `json:"title"`
	Subtitle        string                `json:"subtitle,omitempty"`
	SeriesTitle     string                `json:"seriesTitle,omitempty"`
	SeriesID        string                `json:"seriesId,omitempty"`
	SeasonID        string                `json:"seasonId,omitempty"`
	ParentID        string                `json:"parentId,omitempty"`
	Year            int                   `json:"year,omitempty"`
	IndexNumber     int                   `json:"indexNumber,omitempty"`
	SeasonNumber    int                   `json:"seasonNumber,omitempty"`
	Overview        string                `json:"overview,omitempty"`
	OriginalTitle   string                `json:"originalTitle,omitempty"`
	PremiereDate    string                `json:"premiereDate,omitempty"`
	RuntimeSeconds  int                   `json:"runtimeSeconds,omitempty"`
	Rating          float64               `json:"rating,omitempty"`
	CriticRating    float64               `json:"criticRating,omitempty"`
	OfficialRating  string                `json:"officialRating,omitempty"`
	Genres          []string              `json:"genres"`
	Studios         []string              `json:"studios"`
	People          []LibraryPerson       `json:"people"`
	MediaVersions   []LibraryMediaVersion `json:"mediaVersions"`
	Played          bool                  `json:"played"`
	Favorite        bool                  `json:"favorite"`
	UnplayedCount   int                   `json:"unplayedCount,omitempty"`
	Progress        float64               `json:"progress,omitempty"`
	PositionSeconds int                   `json:"positionSeconds,omitempty"`
	Poster          string                `json:"poster,omitempty"`
	Thumb           string                `json:"thumb,omitempty"`
	Backdrop        string                `json:"backdrop,omitempty"`
}

type LibraryPerson struct {
	ID    string `json:"id,omitempty"`
	Name  string `json:"name"`
	Role  string `json:"role,omitempty"`
	Type  string `json:"type,omitempty"`
	Image string `json:"image,omitempty"`
}

type LibraryMediaTrack struct {
	Index     int     `json:"index"`
	Type      string  `json:"type"`
	Codec     string  `json:"codec,omitempty"`
	Profile   string  `json:"profile,omitempty"`
	Language  string  `json:"language,omitempty"`
	Title     string  `json:"title,omitempty"`
	Channels  int     `json:"channels,omitempty"`
	Bitrate   int     `json:"bitrate,omitempty"`
	Width     int     `json:"width,omitempty"`
	Height    int     `json:"height,omitempty"`
	FrameRate float64 `json:"frameRate,omitempty"`
	HDR       string  `json:"hdr,omitempty"`
	Default   bool    `json:"default"`
	Forced    bool    `json:"forced"`
}

type LibraryMediaVersion struct {
	ID        string              `json:"id"`
	Name      string              `json:"name,omitempty"`
	Container string              `json:"container,omitempty"`
	SizeBytes int64               `json:"sizeBytes,omitempty"`
	Bitrate   int                 `json:"bitrate,omitempty"`
	Tracks    []LibraryMediaTrack `json:"tracks"`
}

type LibraryStateBody struct {
	Played   *bool `json:"played"`
	Favorite *bool `json:"favorite"`
}

type LibraryItemResponse struct {
	Item    LibraryItem `json:"item"`
	Partial []Partial   `json:"partial"`
	Cache   CacheInfo   `json:"cache"`
}

type LibrarySeasonsResponse struct {
	SeriesID string        `json:"seriesId"`
	Items    []LibraryItem `json:"items"`
	Partial  []Partial     `json:"partial"`
	Cache    CacheInfo     `json:"cache"`
}

type LibraryEpisodesResponse struct {
	SeriesID   string        `json:"seriesId"`
	SeasonID   string        `json:"seasonId"`
	Page       int           `json:"page"`
	TotalPages int           `json:"totalPages"`
	Total      int           `json:"total"`
	Items      []LibraryItem `json:"items"`
	Partial    []Partial     `json:"partial"`
	Cache      CacheInfo     `json:"cache"`
}

func (s *Server) handleLibraryRoute(w http.ResponseWriter, r *http.Request) {
	parts := strings.Split(strings.Trim(r.PathValue("path"), "/"), "/")
	switch {
	case len(parts) == 1 && parts[0] == "search":
		s.handleLibrarySearch(w, r)
	case len(parts) == 1 && parts[0] == "favorites":
		s.handleLibraryFavorites(w, r)
	case len(parts) == 2 && parts[1] == "items":
		r.SetPathValue("viewId", parts[0])
		s.handleLibraryItems(w, r)
	case len(parts) == 2 && parts[0] == "items":
		r.SetPathValue("itemId", parts[1])
		s.handleLibraryItem(w, r)
	case len(parts) == 3 && parts[0] == "series" && parts[2] == "seasons":
		r.SetPathValue("seriesId", parts[1])
		s.handleLibrarySeasons(w, r)
	case len(parts) == 3 && parts[0] == "series" && parts[2] == "episodes":
		r.SetPathValue("seriesId", parts[1])
		s.handleLibraryEpisodes(w, r)
	default:
		writeError(w, r, http.StatusNotFound, Error{Code: CodeNotFound, Message: "no such library endpoint"})
	}
}

func (s *Server) handleLibraryState(w http.ResponseWriter, r *http.Request) {
	if !TokenFrom(r.Context()).HasScope("play") {
		writeError(w, r, http.StatusForbidden, Error{
			Code: CodeForbiddenScope, Message: "this token cannot change Jellyfin profile state",
		})
		return
	}
	client, ok := s.jellyfinForRequest(w, r)
	if !ok {
		return
	}
	itemID := r.PathValue("itemId")
	if !isHex32(itemID) {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "bad item id"})
		return
	}
	var body LibraryStateBody
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1024)).Decode(&body); err != nil ||
		(body.Played == nil) == (body.Favorite == nil) {
		writeError(w, r, http.StatusBadRequest, Error{
			Code: CodeInvalidRequest, Message: "set exactly one of played or favorite",
		})
		return
	}
	ctx, cancel := timeoutFor(r, s.cfg.Server.RequestTimeout.OrDefault(20*time.Second))
	defer cancel()
	var err error
	if body.Played != nil {
		err = client.SetPlayed(ctx, itemID, *body.Played)
	} else {
		err = client.SetFavorite(ctx, itemID, *body.Favorite)
	}
	if err != nil {
		writeUpstreamError(w, r, "jellyfin", err)
		return
	}
	s.invalidateLibraryUser(client.UserID())
	item, err := client.Item(ctx, itemID)
	if err != nil {
		writeUpstreamError(w, r, "jellyfin", err)
		return
	}
	writeJSON(w, http.StatusOK, LibraryItemResponse{
		Item: libraryItemFrom(*item), Partial: []Partial{}, Cache: CacheInfo{},
	})
}

func (s *Server) invalidateLibraryUser(userID string) {
	s.cache.InvalidatePrefix("home:" + userID + ":")
	s.cache.InvalidatePrefix("library:" + userID + ":")
	s.cache.InvalidatePrefix("library:item:" + userID + ":")
	s.cache.InvalidatePrefix("library:seasons:" + userID + ":")
	s.cache.InvalidatePrefix("library:episodes:" + userID + ":")
	s.cache.InvalidatePrefix("library:favorites:" + userID + ":")
	s.cache.InvalidatePrefix("library:search:" + userID + ":")
}

func (s *Server) handleLibrarySearch(w http.ResponseWriter, r *http.Request) {
	query := strings.TrimSpace(r.URL.Query().Get("q"))
	if len([]rune(query)) < 2 || len([]rune(query)) > 100 {
		writeError(w, r, http.StatusBadRequest, Error{
			Code: CodeInvalidRequest, Message: "library search must be 2 to 100 characters",
		})
		return
	}
	s.handleLibraryCollection(w, r, "search", "Search results", query, "")
}

func (s *Server) handleLibraryFavorites(w http.ResponseWriter, r *http.Request) {
	s.handleLibraryCollection(w, r, "favorites", "Favourites", "", "IsFavorite")
}

func (s *Server) handleLibraryCollection(
	w http.ResponseWriter, r *http.Request, id, title, searchTerm, filters string,
) {
	client, ok := s.jellyfinForRequest(w, r)
	if !ok {
		return
	}
	pageNumber, _ := strconv.Atoi(r.URL.Query().Get("page"))
	if pageNumber < 1 {
		pageNumber = 1
	}
	if pageNumber > 500 {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "page must be 500 or less"})
		return
	}
	ctx, cancel := timeoutFor(r, s.cfg.Server.RequestTimeout.OrDefault(20*time.Second))
	defer cancel()
	key := "library:" + id + ":" + client.UserID() + ":" + strings.ToLower(searchTerm) + ":" + strconv.Itoa(pageNumber)
	result, meta, err := cache.Fetch(ctx, s.cache, key, cache.UserData,
		func(ctx context.Context) (*jellyfin.ItemsPage, error) {
			return client.Items(ctx, jellyfin.ItemsQuery{
				Recursive: true, Types: "Movie,Series", Filters: filters, SearchTerm: searchTerm,
				Fields: "ProviderIds", SortBy: "SortName", SortOrder: "Ascending",
				Limit: libraryPageSize, StartIndex: (pageNumber - 1) * libraryPageSize,
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
		ViewID: id, Title: title, Page: pageNumber, TotalPages: totalPages,
		Total: result.TotalRecordCount, SortedBy: "name", SortOrder: "asc",
		Items: s.itemsToHits(result.Items), Partial: []Partial{}, Cache: cacheInfoFrom(meta),
	})
}

const jellyfinUserHeader = "X-Jellyfin-User"

func (s *Server) jellyfinForRequest(
	w http.ResponseWriter, r *http.Request,
) (*jellyfin.Client, bool) {
	if s.jellyfin == nil {
		writeError(w, r, http.StatusServiceUnavailable, Error{
			Code: CodeUpstreamDown, Service: "jellyfin", Message: "Jellyfin is not configured",
		})
		return nil, false
	}
	userID := strings.TrimSpace(r.Header.Get(jellyfinUserHeader))
	if userID != "" && !isHex32(userID) {
		writeError(w, r, http.StatusBadRequest, Error{
			Code: CodeInvalidRequest, Message: "bad Jellyfin user id",
		})
		return nil, false
	}
	if userID == "" {
		userID = s.jellyfin.UserID()
	}
	if userID == "" {
		writeError(w, r, http.StatusServiceUnavailable, Error{
			Code: "misconfigured", Service: "jellyfin",
			Message: "no Jellyfin user is configured — set services.jellyfin.user_id",
		})
		return nil, false
	}
	w.Header().Add("Vary", jellyfinUserHeader)
	return s.jellyfin.ForUser(userID), true
}

func (s *Server) handleLibraryItem(w http.ResponseWriter, r *http.Request) {
	jellyfinClient, ok := s.jellyfinForRequest(w, r)
	if !ok {
		return
	}
	itemID := r.PathValue("itemId")
	if !isHex32(itemID) {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "bad item id"})
		return
	}

	ctx, cancel := timeoutFor(r, s.cfg.Server.RequestTimeout.OrDefault(20*time.Second))
	defer cancel()
	cacheKey := "library:item:" + jellyfinClient.UserID() + ":" + itemID
	item, meta, err := cache.Fetch(ctx, s.cache, cacheKey, cache.UserData,
		func(ctx context.Context) (*jellyfin.Item, error) { return jellyfinClient.Item(ctx, itemID) })
	if err != nil {
		writeUpstreamError(w, r, "jellyfin", err)
		return
	}
	writeJSON(w, http.StatusOK, LibraryItemResponse{
		Item: libraryItemFrom(*item), Partial: []Partial{}, Cache: cacheInfoFrom(meta),
	})
}

func (s *Server) handleLibrarySeasons(w http.ResponseWriter, r *http.Request) {
	jellyfinClient, ok := s.jellyfinForRequest(w, r)
	if !ok {
		return
	}
	seriesID := r.PathValue("seriesId")
	if !isHex32(seriesID) {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "bad series id"})
		return
	}

	ctx, cancel := timeoutFor(r, s.cfg.Server.RequestTimeout.OrDefault(20*time.Second))
	defer cancel()
	cacheKey := "library:seasons:" + jellyfinClient.UserID() + ":" + seriesID
	page, meta, err := cache.Fetch(ctx, s.cache, cacheKey, cache.UserData,
		func(ctx context.Context) (*jellyfin.ItemsPage, error) { return jellyfinClient.Seasons(ctx, seriesID) })
	if err != nil {
		writeUpstreamError(w, r, "jellyfin", err)
		return
	}
	writeJSON(w, http.StatusOK, LibrarySeasonsResponse{
		SeriesID: seriesID, Items: libraryItemsFrom(page.Items),
		Partial: []Partial{}, Cache: cacheInfoFrom(meta),
	})
}

func (s *Server) handleLibraryEpisodes(w http.ResponseWriter, r *http.Request) {
	jellyfinClient, ok := s.jellyfinForRequest(w, r)
	if !ok {
		return
	}
	seriesID := r.PathValue("seriesId")
	seasonID := r.URL.Query().Get("seasonId")
	if !isHex32(seriesID) || !isHex32(seasonID) {
		writeError(w, r, http.StatusBadRequest, Error{
			Code: CodeInvalidRequest, Message: "bad series or season id",
		})
		return
	}
	pageNumber, _ := strconv.Atoi(r.URL.Query().Get("page"))
	if pageNumber < 1 {
		pageNumber = 1
	}
	if pageNumber > 500 {
		writeError(w, r, http.StatusBadRequest, Error{
			Code: CodeInvalidRequest, Message: "page must be 500 or less",
		})
		return
	}

	ctx, cancel := timeoutFor(r, s.cfg.Server.RequestTimeout.OrDefault(20*time.Second))
	defer cancel()
	key := "library:episodes:" + jellyfinClient.UserID() + ":" + seriesID + ":" +
		seasonID + ":" + strconv.Itoa(pageNumber)
	page, meta, err := cache.Fetch(ctx, s.cache, key, cache.UserData,
		func(ctx context.Context) (*jellyfin.ItemsPage, error) {
			return jellyfinClient.Episodes(ctx, seriesID, seasonID, libraryEpisodePageSize,
				(pageNumber-1)*libraryEpisodePageSize)
		})
	if err != nil {
		writeUpstreamError(w, r, "jellyfin", err)
		return
	}
	totalPages := (page.TotalRecordCount + libraryEpisodePageSize - 1) / libraryEpisodePageSize
	if totalPages < 1 {
		totalPages = 1
	}
	writeJSON(w, http.StatusOK, LibraryEpisodesResponse{
		SeriesID: seriesID, SeasonID: seasonID, Page: pageNumber,
		TotalPages: totalPages, Total: page.TotalRecordCount,
		Items: libraryItemsFrom(page.Items), Partial: []Partial{}, Cache: cacheInfoFrom(meta),
	})
}

func libraryItemsFrom(items []jellyfin.Item) []LibraryItem {
	out := make([]LibraryItem, 0, len(items))
	for _, item := range items {
		out = append(out, libraryItemFrom(item))
	}
	return out
}

func libraryItemFrom(item jellyfin.Item) LibraryItem {
	out := LibraryItem{
		ID: item.ID, Type: libraryType(item.Type), Title: item.Name,
		Subtitle: item.Subtitle(), SeriesTitle: item.SeriesName, SeriesID: item.SeriesID, SeasonID: item.SeasonID,
		ParentID: item.ParentID, Year: item.ProductionYear, IndexNumber: item.IndexNumber,
		SeasonNumber: item.ParentIndexNumber, Overview: item.Overview,
		OriginalTitle: item.OriginalTitle, PremiereDate: item.PremiereDate,
		RuntimeSeconds: item.RuntimeSeconds(), Rating: item.CommunityRating,
		CriticRating: item.CriticRating, OfficialRating: item.OfficialRating, Genres: item.Genres,
		Progress: item.Progress(), PositionSeconds: item.PositionSeconds(),
		Studios: []string{}, People: []LibraryPerson{}, MediaVersions: []LibraryMediaVersion{},
	}
	if item.ProviderIds != nil && item.ProviderIds.Tmdb != "" && (item.Type == "Movie" || item.Type == "Series") {
		if tmdbID, err := strconv.Atoi(item.ProviderIds.Tmdb); err == nil && tmdbID > 0 {
			out.MediaKey = mediaKey(libraryType(item.Type), tmdbID)
		}
	}
	if item.Type == "Season" {
		out.SeasonNumber = item.IndexNumber
	}
	if out.Genres == nil {
		out.Genres = []string{}
	}
	if item.UserData != nil {
		out.Played = item.UserData.Played
		out.Favorite = item.UserData.IsFavorite
		out.UnplayedCount = item.UserData.UnplayedItemCount
	}
	for _, studio := range item.Studios {
		if strings.TrimSpace(studio.Name) != "" {
			out.Studios = append(out.Studios, studio.Name)
		}
	}
	for _, person := range item.People {
		if strings.TrimSpace(person.Name) == "" {
			continue
		}
		mapped := LibraryPerson{ID: person.ID, Name: person.Name, Role: person.Role, Type: person.Type}
		if person.ID != "" && person.PrimaryImageTag != "" {
			mapped.Image = jellyfinImagePrefix + "/" + person.ID + "/Primary?tag=" + person.PrimaryImageTag
		}
		out.People = append(out.People, mapped)
	}
	for _, source := range item.MediaSources {
		version := LibraryMediaVersion{
			ID: source.ID, Name: source.Name, Container: source.Container,
			SizeBytes: source.Size, Bitrate: source.Bitrate, Tracks: []LibraryMediaTrack{},
		}
		for _, stream := range source.MediaStreams {
			version.Tracks = append(version.Tracks, LibraryMediaTrack{
				Index: stream.Index, Type: strings.ToLower(stream.Type), Codec: stream.Codec,
				Profile: stream.Profile, Language: stream.Language, Title: stream.DisplayTitle,
				Channels: stream.Channels, Bitrate: stream.Bitrate, Width: stream.Width,
				Height: stream.Height, FrameRate: stream.AverageFrameRate,
				HDR: stream.VideoRangeType, Default: stream.IsDefault, Forced: stream.IsForced,
			})
		}
		out.MediaVersions = append(out.MediaVersions, version)
	}
	if tag := item.PosterTag(); tag != "" {
		out.Poster = jellyfinImagePrefix + "/" + item.PosterItemID() + "/Primary?tag=" + tag
	}
	if tag := item.ImageTags["Thumb"]; tag != "" {
		out.Thumb = jellyfinImagePrefix + "/" + item.ID + "/Thumb?tag=" + tag
	} else if tag := item.ImageTags["Primary"]; tag != "" && item.Type == "Episode" {
		out.Thumb = jellyfinImagePrefix + "/" + item.ID + "/Primary?tag=" + tag
	}
	if len(item.BackdropImageTags) > 0 {
		out.Backdrop = jellyfinImagePrefix + "/" + item.ID + "/Backdrop?tag=" + item.BackdropImageTags[0]
	}
	return out
}

func libraryType(upstream string) string {
	switch upstream {
	case "Movie":
		return "movie"
	case "Series":
		return "series"
	case "Season":
		return "season"
	case "Episode":
		return "episode"
	default:
		return "unknown"
	}
}
