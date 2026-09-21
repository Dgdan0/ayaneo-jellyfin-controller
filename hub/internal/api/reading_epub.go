package api

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"

	"ayaneohub/internal/adapters/storyteller"
	"ayaneohub/internal/cache"
	"ayaneohub/internal/httpx"
)

var singleByteRange = regexp.MustCompile(`^bytes=(?:[0-9]+-[0-9]*|-[0-9]+)$`)

type ReadingEpubPosition struct {
	WorkID       string          `json:"workId"`
	SourceItemID string          `json:"sourceItemId"`
	Locator      json.RawMessage `json:"locator"`
	Timestamp    int64           `json:"timestamp,omitempty"`
	UpdatedAt    string          `json:"updatedAt,omitempty"`
}

func (s *Server) handleReadingEpubFile(w http.ResponseWriter, r *http.Request) {
	if !s.requireReading(w, r) {
		return
	}
	byteRange := strings.TrimSpace(r.Header.Get("Range"))
	if byteRange != "" && !singleByteRange.MatchString(byteRange) {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "only one valid byte range may be requested"})
		return
	}
	ifRange := strings.TrimSpace(r.Header.Get("If-Range"))
	if len(ifRange) > 512 {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "invalid If-Range validator"})
		return
	}
	ctx, cancel := timeoutFor(r, s.cfg.Server.RequestTimeout.OrDefault(25*time.Second))
	bookID, ok := s.resolveStorytellerEbook(w, r, ctx)
	cancel()
	if !ok {
		return
	}
	// The file transfer uses the request context rather than the metadata
	// timeout. Closing the Android request immediately cancels Storyteller.
	response, err := s.storyteller.OpenEbook(r.Context(), bookID, byteRange, ifRange)
	if err != nil {
		writeUpstreamError(w, r, "storyteller", err)
		return
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK && response.StatusCode != http.StatusPartialContent {
		writeError(w, r, http.StatusBadGateway, Error{Code: CodeUpstreamDown, Service: "storyteller", Message: "Storyteller returned an invalid EPUB response", Retryable: true})
		return
	}
	for _, name := range []string{"Content-Length", "Content-Range", "Accept-Ranges", "ETag", "Last-Modified"} {
		if value := response.Header.Get(name); value != "" {
			w.Header().Set(name, value)
		}
	}
	contentType := strings.ToLower(strings.TrimSpace(strings.Split(response.Header.Get("Content-Type"), ";")[0]))
	if contentType != "application/epub+zip" && contentType != "application/octet-stream" {
		contentType = "application/epub+zip"
	}
	w.Header().Set("Content-Type", contentType)
	w.Header().Set("Cache-Control", "private, no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	if hash := strings.TrimSpace(response.Header.Get("X-Storyteller-Hash")); hash != "" && len(hash) <= 256 {
		w.Header().Set("X-Reading-Content-Hash", hash)
	}
	w.WriteHeader(response.StatusCode)
	_, _ = io.CopyBuffer(w, response.Body, make([]byte, 128<<10))
}

func (s *Server) handleReadingEpubPosition(w http.ResponseWriter, r *http.Request) {
	if !s.requireReading(w, r) {
		return
	}
	ctx, cancel := timeoutFor(r, s.cfg.Server.RequestTimeout.OrDefault(25*time.Second))
	defer cancel()
	bookID, ok := s.resolveStorytellerEbook(w, r, ctx)
	if !ok {
		return
	}
	workID, sourceItemID := r.PathValue("workId"), r.PathValue("sourceItemId")
	if r.Method == http.MethodGet {
		position, err := s.storyteller.Position(ctx, bookID)
		if err != nil {
			var upstream *httpx.Error
			if errors.As(err, &upstream) && upstream.Status == http.StatusNotFound {
				writeJSON(w, http.StatusOK, ReadingEpubPosition{WorkID: workID, SourceItemID: sourceItemID, Locator: json.RawMessage("null")})
				return
			}
			writeUpstreamError(w, r, "storyteller", err)
			return
		}
		writeJSON(w, http.StatusOK, ReadingEpubPosition{
			WorkID: workID, SourceItemID: sourceItemID, Locator: position.Locator,
			Timestamp: position.Timestamp, UpdatedAt: position.UpdatedAt,
		})
		return
	}

	var body struct {
		Locator   json.RawMessage `json:"locator"`
		Timestamp int64           `json:"timestamp"`
	}
	decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 64<<10))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&body); err != nil || !validReadiumLocator(body.Locator) {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "invalid EPUB reading position"})
		return
	}
	if body.Timestamp <= 0 {
		body.Timestamp = time.Now().UnixMilli()
	}
	if err := s.storyteller.SavePosition(ctx, bookID, body.Locator, body.Timestamp); err != nil {
		var upstream *httpx.Error
		if errors.As(err, &upstream) && upstream.Status == http.StatusConflict {
			writeError(w, r, http.StatusConflict, Error{Code: "reading_position_conflict", Service: "storyteller", Message: "a newer reading position already exists"})
			return
		}
		writeUpstreamError(w, r, "storyteller", err)
		return
	}
	s.cache.Invalidate("reading:storyteller:work:" + sourceItemID)
	s.cache.Invalidate("reading:storyteller:books")
	writeJSON(w, http.StatusOK, struct {
		OK     bool   `json:"ok"`
		Action string `json:"action"`
	}{OK: true, Action: "save_epub_position"})
}

func (s *Server) resolveStorytellerEbook(w http.ResponseWriter, r *http.Request, ctx context.Context) (int64, bool) {
	workID := strings.TrimSpace(r.PathValue("workId"))
	bookID, err := strconv.ParseInt(strings.TrimSpace(r.PathValue("sourceItemId")), 10, 64)
	if !validReadingWorkID(workID) || err != nil || bookID <= 0 {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "invalid EPUB publication"})
		return 0, false
	}
	if s.storyteller == nil {
		writeError(w, r, http.StatusServiceUnavailable, Error{Code: CodeUpstreamDown, Service: "storyteller", Message: "Storyteller is unavailable", Retryable: true})
		return 0, false
	}
	binding, found := s.readingCatalog.Resolve(workID)
	if !found {
		writeError(w, r, http.StatusNotFound, Error{Code: CodeNotFound, Message: "reading work not found"})
		return 0, false
	}
	direct := false
	seriesSource := ""
	for _, source := range binding.Sources {
		if source.Source == "storyteller" && source.SourceID == strconv.FormatInt(bookID, 10) {
			direct = true
		}
		if source.Source == "storyteller-series" {
			seriesSource = source.SourceID
		}
	}
	var book storyteller.Book
	if direct {
		loaded, _, loadErr := cache.Fetch(ctx, s.cache, "reading:storyteller:work:"+strconv.FormatInt(bookID, 10), cache.Metadata, func(fetchCtx context.Context) (*storyteller.Book, error) {
			return s.storyteller.Book(fetchCtx, bookID)
		})
		if loadErr != nil {
			writeUpstreamError(w, r, "storyteller", loadErr)
			return 0, false
		}
		book = *loaded
	} else if seriesSource != "" {
		books, _, loadErr := cache.Fetch(ctx, s.cache, "reading:storyteller:books", cache.LibraryPage, s.storyteller.Books)
		if loadErr != nil {
			writeUpstreamError(w, r, "storyteller", loadErr)
			return 0, false
		}
		for _, candidate := range books {
			source, _, grouped := storytellerSeriesSource(s.reconcileStorytellerBook(candidate))
			if candidate.ID == bookID && grouped && source == seriesSource {
				book = candidate
				break
			}
		}
	}
	if book.ID != bookID {
		writeError(w, r, http.StatusNotFound, Error{Code: CodeNotFound, Message: "publication does not belong to this work"})
		return 0, false
	}
	book = s.reconcileStorytellerBook(book)
	if book.Ebook == nil || book.Ebook.Missing {
		writeError(w, r, http.StatusNotFound, Error{Code: CodeNotFound, Message: "this work has no available EPUB edition"})
		return 0, false
	}
	return bookID, true
}

func validReadiumLocator(raw json.RawMessage) bool {
	if len(raw) < 2 || len(raw) > 48<<10 || !json.Valid(raw) || raw[0] != '{' {
		return false
	}
	var locator struct {
		Href      string          `json:"href"`
		Locations json.RawMessage `json:"locations"`
	}
	if json.Unmarshal(raw, &locator) != nil || strings.TrimSpace(locator.Href) == "" {
		return false
	}
	return len(locator.Locations) >= 2 && locator.Locations[0] == '{'
}
