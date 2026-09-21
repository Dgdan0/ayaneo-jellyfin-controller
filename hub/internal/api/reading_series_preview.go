package api

import (
	"context"
	"fmt"
	"net/http"
	"strings"
	"sync"
	"time"

	"ayaneohub/internal/adapters/bookkeeprr"
	"ayaneohub/internal/adapters/openlibrary"
)

type ReadingSeriesPreviewBook struct {
	ID        string `json:"id"`
	Title     string `json:"title"`
	AuthorID  string `json:"authorId,omitempty"`
	Author    string `json:"author,omitempty"`
	Year      int    `json:"year,omitempty"`
	ISBN      string `json:"isbn,omitempty"`
	Cover     string `json:"cover,omitempty"`
	Position  int    `json:"position"`
	InLibrary bool   `json:"inLibrary"`
	Selected  bool   `json:"selected"`
	CoverURL  string `json:"-"`
}

type ReadingSeriesPreview struct {
	Key            string                     `json:"key"`
	SeriesID       string                     `json:"seriesId"`
	Name           string                     `json:"name"`
	Description    string                     `json:"description,omitempty"`
	AuthorID       string                     `json:"authorId,omitempty"`
	Author         string                     `json:"author,omitempty"`
	AuthorImage    string                     `json:"authorImage,omitempty"`
	Ordering       string                     `json:"ordering"`
	Books          []ReadingSeriesPreviewBook `json:"books"`
	AuthorImageURL string                     `json:"-"`
	// FullBooks survives the exact-selection step so the durable acquisition
	// manifest can still describe the complete verified series. Books remains
	// the subset the user actually asked BookKeeprr to fetch.
	FullBooks []ReadingSeriesPreviewBook `json:"-"`
}

type ReadingSeriesPreviewResponse struct {
	Key    string                 `json:"key"`
	Scopes []ReadingSeriesPreview `json:"scopes"`
}

type readingSeriesPreviewStore struct {
	mu       sync.Mutex
	items    map[string]ReadingSeriesPreview
	order    []string
	maxItems int
}

func newReadingSeriesPreviewStore(maxItems int) *readingSeriesPreviewStore {
	return &readingSeriesPreviewStore{items: map[string]ReadingSeriesPreview{}, maxItems: maxItems}
}

func (s *readingSeriesPreviewStore) put(key string, preview ReadingSeriesPreview) {
	if s == nil || key == "" {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	preview.Books = append([]ReadingSeriesPreviewBook(nil), preview.Books...)
	storeKey := readingSeriesPreviewKey(key, preview.SeriesID)
	if _, exists := s.items[storeKey]; !exists {
		s.order = append(s.order, storeKey)
	}
	s.items[storeKey] = preview
	for len(s.order) > s.maxItems {
		oldest := s.order[0]
		s.order = s.order[1:]
		delete(s.items, oldest)
	}
}

func (s *readingSeriesPreviewStore) selected(key, seriesID string, ids []string) (ReadingSeriesPreview, error) {
	if s == nil || len(ids) == 0 {
		return ReadingSeriesPreview{}, fmt.Errorf("select at least one book")
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	preview, ok := s.items[readingSeriesPreviewKey(key, seriesID)]
	if !ok {
		return ReadingSeriesPreview{}, fmt.Errorf("series preview expired; load it again")
	}
	wanted := make(map[string]bool, len(ids))
	for _, id := range ids {
		id = strings.TrimSpace(id)
		if id == "" || wanted[id] {
			return ReadingSeriesPreview{}, fmt.Errorf("invalid book selection")
		}
		wanted[id] = true
	}
	fullBooks := append([]ReadingSeriesPreviewBook(nil), preview.Books...)
	selected := make([]ReadingSeriesPreviewBook, 0, len(wanted))
	for _, book := range preview.Books {
		if wanted[book.ID] {
			selected = append(selected, book)
			delete(wanted, book.ID)
		}
	}
	if len(wanted) > 0 {
		return ReadingSeriesPreview{}, fmt.Errorf("book selection is outside the verified series roster")
	}
	preview.FullBooks = fullBooks
	preview.Books = selected
	return preview, nil
}

func readingSeriesPreviewKey(key, seriesID string) string { return key + "|" + seriesID }

func (s *Server) handleReadingSeriesPreview(w http.ResponseWriter, r *http.Request) {
	if !s.requireReadingRequest(w, r) || !s.requireBookKeeprrRequests(w, r) {
		return
	}
	key := strings.TrimSpace(r.URL.Query().Get("key"))
	item, ok := s.readingCandidate(w, r, key)
	if !ok {
		return
	}
	if item.ContentType != bookkeeprr.TypeEbook {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "series preview is available for ebooks"})
		return
	}
	workID := item.Sources.OpenLibrary
	if workID == "" && item.Source == "openlibrary" {
		workID = item.SourceID
	}
	ctx, cancel := timeoutFor(r, s.cfg.Server.RequestTimeout.OrDefault(25*time.Second))
	defer cancel()
	resolvedScopes, err := s.openlibrary.SeriesPreviews(ctx, openlibrary.Candidate{
		WorkID: workID, ISBN: item.ISBN, Title: item.Title, Author: item.Author,
	})
	if err != nil {
		writeError(w, r, http.StatusUnprocessableEntity, Error{
			Code: CodeInvalidRequest, Service: "openlibrary",
			Message: "Could not verify a complete series list for this book", Retryable: true,
		})
		return
	}
	response := ReadingSeriesPreviewResponse{Key: key, Scopes: make([]ReadingSeriesPreview, 0, len(resolvedScopes))}
	for _, resolved := range resolvedScopes {
		preview := ReadingSeriesPreview{
			Key: key, SeriesID: resolved.ID, Name: resolved.Name, Description: resolved.Description,
			AuthorID: resolved.AuthorID, Author: resolved.Author, AuthorImageURL: resolved.AuthorImageURL,
			Ordering: "publication", Books: make([]ReadingSeriesPreviewBook, 0, len(resolved.Books)),
		}
		if token := s.images.registerReadingCover(resolved.AuthorImageURL); token != "" {
			preview.AuthorImage = "/v1/img/reading/" + token
		}
		owned := s.readingOwnedSeriesEntries(ctx, resolved.Name)
		for _, book := range resolved.Books {
			inLibrary := owned[normalizeReadingProviderRef(book.WorkID)]
			if book.WorkID == workID && item.InLibrary {
				inLibrary = true
			}
			entry := ReadingSeriesPreviewBook{
				ID: book.WorkID, Title: book.Title, AuthorID: book.AuthorID, Author: book.Author,
				Year: book.Year, ISBN: book.ISBN, Position: book.Position, Selected: !inLibrary,
				CoverURL: book.CoverURL, InLibrary: inLibrary,
			}
			if token := s.images.registerReadingCover(book.CoverURL); token != "" {
				entry.Cover = "/v1/img/reading/" + token
			}
			preview.Books = append(preview.Books, entry)
		}
		s.readingSeriesPreviews.put(key, preview)
		response.Scopes = append(response.Scopes, preview)
	}
	writeJSON(w, http.StatusOK, response)
}

func (s *Server) readingOwnedSeriesEntries(ctx context.Context, name string) map[string]bool {
	// Keep preview useful if BookKeeprr's optional parent view is temporarily
	// unavailable. Ownership only affects the initial checkbox state.
	owned := map[string]bool{}
	list, err := s.bookkeeprr.BookSeries(ctx, bookkeeprr.TypeEbook)
	if err != nil {
		return owned
	}
	for _, candidate := range list.BookSeries {
		if normalizeReadingIdentity(candidate.Name) != normalizeReadingIdentity(name) {
			continue
		}
		detail, detailErr := s.bookkeeprr.BookSeriesDetail(ctx, candidate.ID)
		if detailErr != nil {
			return owned
		}
		for _, book := range detail.Books {
			if book.Owned {
				owned[normalizeReadingProviderRef(book.ExternalRef)] = true
			}
		}
		return owned
	}
	return owned
}

func normalizeReadingProviderRef(value string) string {
	value = strings.TrimSpace(strings.TrimSuffix(value, ".json"))
	return strings.TrimPrefix(value, "/works/")
}
