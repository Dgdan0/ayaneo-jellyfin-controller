package api

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"ayaneohub/internal/adapters/bookkeeprr"
	"ayaneohub/internal/cache"
)

type ReadingItem struct {
	Key         string   `json:"key"`
	ContentType string   `json:"contentType"`
	Title       string   `json:"title"`
	Author      string   `json:"author,omitempty"`
	Year        int      `json:"year,omitempty"`
	ISBN        string   `json:"isbn,omitempty"`
	Source      string   `json:"source"`
	SourceID    string   `json:"sourceId"`
	Cover       string   `json:"cover,omitempty"`
	Description string   `json:"description,omitempty"`
	InLibrary   bool     `json:"inLibrary"`
	Actions     []string `json:"actions"`
}

type ReadingDiscoverRow struct {
	ID          string        `json:"id"`
	Title       string        `json:"title"`
	Meta        string        `json:"meta,omitempty"`
	ContentType string        `json:"contentType"`
	Page        int           `json:"page"`
	HasMore     bool          `json:"hasMore"`
	Items       []ReadingItem `json:"items"`
}

type ReadingDiscoverResponse struct {
	Rows    []ReadingDiscoverRow `json:"rows"`
	Partial []Partial            `json:"partial"`
	Cache   CacheInfo            `json:"cache"`
}

type ReadingSearchResponse struct {
	Query       string        `json:"query"`
	ContentType string        `json:"contentType"`
	Results     []ReadingItem `json:"results"`
	Partial     []Partial     `json:"partial"`
	Cache       CacheInfo     `json:"cache"`
}

func (s *Server) requireReading(w http.ResponseWriter, r *http.Request) bool {
	if TokenFrom(r.Context()).HasScope("reading") {
		return true
	}
	writeError(w, r, http.StatusForbidden, Error{
		Code: CodeForbiddenScope, Message: "this device is not allowed to browse books",
	})
	return false
}

func readingType(r *http.Request, allowAll bool) (bookkeeprr.ContentType, error) {
	raw := r.URL.Query().Get("type")
	if raw == "" && allowAll {
		raw = string(bookkeeprr.TypeAll)
	}
	return bookkeeprr.ParseContentType(raw, allowAll)
}

func (s *Server) handleReadingDiscover(w http.ResponseWriter, r *http.Request) {
	if !s.requireReading(w, r) {
		return
	}
	kind, err := readingType(r, true)
	if err != nil {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "unsupported reading type"})
		return
	}
	if s.bookkeeprr == nil {
		writeError(w, r, http.StatusServiceUnavailable, Error{
			Code: CodeUpstreamDown, Service: "bookkeeprr", Message: "BookKeeprr is not configured",
		})
		return
	}

	ctx, cancel := timeoutFor(r, s.cfg.Server.RequestTimeout.OrDefault(25*time.Second))
	defer cancel()
	types := []bookkeeprr.ContentType{kind}
	if kind == bookkeeprr.TypeAll {
		types = bookkeeprr.ConcreteTypes()
	}

	type fetched struct {
		kind bookkeeprr.ContentType
		body *bookkeeprr.BrowseResponse
		meta cache.Meta
		err  error
	}
	results := make([]fetched, len(types))
	var wg sync.WaitGroup
	for i, contentType := range types {
		wg.Add(1)
		go func(i int, contentType bookkeeprr.ContentType) {
			defer wg.Done()
			body, meta, fetchErr := cache.Fetch(ctx, s.cache, "reading:discover:"+string(contentType), cache.Discover,
				func(fetchCtx context.Context) (*bookkeeprr.BrowseResponse, error) {
					return s.bookkeeprr.Browse(fetchCtx, contentType)
				})
			results[i] = fetched{kind: contentType, body: body, meta: meta, err: fetchErr}
		}(i, contentType)
	}
	wg.Wait()

	out := ReadingDiscoverResponse{Rows: []ReadingDiscoverRow{}, Partial: []Partial{}}
	allCacheHits := true
	var oldest time.Duration
	for _, result := range results {
		if result.err != nil {
			out.Partial = append(out.Partial, Partial{
				Service: "bookkeeprr", Reason: "type_unavailable",
				Affects: []string{"reading." + string(result.kind)},
				Message: readingTypeLabel(result.kind) + " could not be loaded",
			})
			continue
		}
		for _, row := range result.body.Rows {
			out.Rows = append(out.Rows, s.readingRow(row, result.kind))
		}
		if !result.meta.Hit {
			allCacheHits = false
		}
		if result.meta.Age > oldest {
			oldest = result.meta.Age
		}
	}
	if len(out.Rows) == 0 && len(out.Partial) > 0 {
		writeError(w, r, http.StatusServiceUnavailable, Error{
			Code: CodeUpstreamDown, Service: "bookkeeprr", Message: "Could not load books to discover", Retryable: true,
		})
		return
	}
	out.Cache = CacheInfo{Hit: allCacheHits, AgeSeconds: int(oldest.Seconds())}
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) handleReadingDiscoverRow(w http.ResponseWriter, r *http.Request) {
	if !s.requireReading(w, r) {
		return
	}
	kind, err := readingType(r, false)
	if err != nil {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "a concrete reading type is required"})
		return
	}
	page, parseErr := strconv.Atoi(r.URL.Query().Get("page"))
	if parseErr != nil || page < 1 || page > 500 {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "page must be between 1 and 500"})
		return
	}
	rowID := strings.TrimSpace(r.PathValue("row"))
	if rowID == "" || len(rowID) > 160 {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "invalid row"})
		return
	}
	if s.bookkeeprr == nil {
		writeError(w, r, http.StatusServiceUnavailable, Error{
			Code: CodeUpstreamDown, Service: "bookkeeprr", Message: "BookKeeprr is not configured",
		})
		return
	}
	ctx, cancel := timeoutFor(r, s.cfg.Server.RequestTimeout.OrDefault(20*time.Second))
	defer cancel()
	key := fmt.Sprintf("reading:discover:%s:%s:%d", kind, rowID, page)
	body, meta, err := cache.Fetch(ctx, s.cache, key, cache.Discover,
		func(fetchCtx context.Context) (*bookkeeprr.CategoryResponse, error) {
			return s.bookkeeprr.Category(fetchCtx, kind, rowID, page)
		})
	if err != nil {
		writeUpstreamError(w, r, "bookkeeprr", err)
		return
	}
	items := make([]ReadingItem, 0, len(body.Items))
	for _, item := range body.Items {
		items = append(items, s.readingItem(item, kind))
	}
	writeJSON(w, http.StatusOK, ReadingDiscoverResponse{
		Rows: []ReadingDiscoverRow{{
			ID: rowID, Title: rowID, ContentType: string(kind), Page: page,
			HasMore: body.HasMore, Items: items,
		}},
		Partial: []Partial{}, Cache: cacheInfoFrom(meta),
	})
}

func (s *Server) handleReadingSearch(w http.ResponseWriter, r *http.Request) {
	if !s.requireReading(w, r) {
		return
	}
	query := strings.TrimSpace(r.URL.Query().Get("q"))
	if query == "" {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "q is required"})
		return
	}
	kind, err := readingType(r, true)
	if err != nil {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "unsupported reading type"})
		return
	}
	if s.bookkeeprr == nil {
		writeError(w, r, http.StatusServiceUnavailable, Error{
			Code: CodeUpstreamDown, Service: "bookkeeprr", Message: "BookKeeprr is not configured",
		})
		return
	}
	ctx, cancel := timeoutFor(r, s.cfg.Server.RequestTimeout.OrDefault(25*time.Second))
	defer cancel()
	cacheKey := "reading:search:" + string(kind) + ":" + strings.ToLower(query)
	body, meta, err := cache.Fetch(ctx, s.cache, cacheKey, cache.Search,
		func(fetchCtx context.Context) (*bookkeeprr.SearchResponse, error) {
			return s.bookkeeprr.Search(fetchCtx, query, kind)
		})
	if err != nil {
		writeUpstreamError(w, r, "bookkeeprr", err)
		return
	}
	out := ReadingSearchResponse{
		Query: query, ContentType: string(kind), Results: make([]ReadingItem, 0, len(body.Results)),
		Partial: []Partial{}, Cache: cacheInfoFrom(meta),
	}
	for _, item := range body.Results {
		out.Results = append(out.Results, s.readingItem(item, kind))
	}
	for _, providerErr := range body.Errors {
		out.Partial = append(out.Partial, Partial{
			Service: providerErr.Source, Reason: "provider_unavailable", Affects: []string{"results"},
			Message: providerErr.Message,
		})
	}
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) readingRow(row bookkeeprr.BrowseRow, fallback bookkeeprr.ContentType) ReadingDiscoverRow {
	items := make([]ReadingItem, 0, len(row.Items))
	for _, item := range row.Items {
		items = append(items, s.readingItem(item, fallback))
	}
	return ReadingDiscoverRow{
		ID: row.ID, Title: row.Label, Meta: row.Meta, ContentType: string(fallback),
		Page: 1, HasMore: len(items) > 0, Items: items,
	}
}

func (s *Server) readingItem(item bookkeeprr.Item, fallback bookkeeprr.ContentType) ReadingItem {
	kind := item.ContentType
	if _, err := bookkeeprr.ParseContentType(string(kind), false); err != nil {
		kind = fallback
	}
	identity := string(kind) + "\x00" + item.Source + "\x00" + item.SourceID
	digest := sha256.Sum256([]byte(identity))
	out := ReadingItem{
		Key: "reading:" + hex.EncodeToString(digest[:16]), ContentType: string(kind),
		Title: item.Title, Author: item.Author, Year: item.Year, ISBN: item.ISBN,
		Source: item.Source, SourceID: item.SourceID, Description: item.Description,
		InLibrary: item.InLibrary, Actions: []string{"detail"},
	}
	item.ContentType = kind
	s.readingCandidates.put(out.Key, item)
	if !item.InLibrary && s.bookkeeprr != nil && s.bookkeeprr.CanRequest() {
		out.Actions = append(out.Actions, "request")
	}
	if token := s.images.registerReadingCover(item.CoverURL); token != "" {
		out.Cover = "/v1/img/reading/" + token
	}
	return out
}

func readingTypeLabel(kind bookkeeprr.ContentType) string {
	switch kind {
	case bookkeeprr.TypeEbook:
		return "Books"
	case bookkeeprr.TypeAudiobook:
		return "Audiobooks"
	case bookkeeprr.TypeComic:
		return "Comics"
	case bookkeeprr.TypeManga:
		return "Manga"
	case bookkeeprr.TypeLightNovel:
		return "Light novels"
	default:
		return "Reading"
	}
}
