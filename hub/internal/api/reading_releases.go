package api

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"ayaneohub/internal/adapters/bookkeeprr"
)

// Only opaque, short-lived tickets leave the Hub. Magnet links, tracker URLs,
// and indexer credentials remain on the PC.
type readingReleaseTicket struct {
	Owner        string
	SeriesID     int
	ReleaseID    int
	Title        string
	ContentType  bookkeeprr.ContentType
	Interactive  *bookkeeprr.InteractiveResult
	FormatStatus string
	Expires      time.Time
}

type readingReleaseTickets struct {
	mu    sync.Mutex
	items map[string]readingReleaseTicket
}

func newReadingReleaseTickets() *readingReleaseTickets {
	return &readingReleaseTickets{items: make(map[string]readingReleaseTicket)}
}

func (s *readingReleaseTickets) put(ticket readingReleaseTicket) (string, error) {
	var bytes [16]byte
	if _, err := rand.Read(bytes[:]); err != nil {
		return "", err
	}
	id := "br_" + hex.EncodeToString(bytes[:])
	s.mu.Lock()
	defer s.mu.Unlock()
	for key, value := range s.items {
		if time.Now().After(value.Expires) {
			delete(s.items, key)
		}
	}
	for len(s.items) >= 500 {
		oldestID := ""
		var oldest time.Time
		for key, value := range s.items {
			if oldestID == "" || value.Expires.Before(oldest) {
				oldestID, oldest = key, value.Expires
			}
		}
		delete(s.items, oldestID)
	}
	ticket.Expires = time.Now().Add(15 * time.Minute)
	s.items[id] = ticket
	return id, nil
}

func (s *readingReleaseTickets) take(id, owner string, seriesID int) (readingReleaseTicket, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	ticket, ok := s.items[id]
	if !ok || ticket.Owner != owner || ticket.SeriesID != seriesID || time.Now().After(ticket.Expires) {
		return readingReleaseTicket{}, false
	}
	delete(s.items, id)
	return ticket, true
}

func (s *readingReleaseTickets) clear(owner string, seriesID int) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for id, ticket := range s.items {
		if ticket.Owner == owner && ticket.SeriesID == seriesID {
			delete(s.items, id)
		}
	}
}

type ReadingRelease struct {
	ID           string  `json:"id"`
	Title        string  `json:"title"`
	Indexer      string  `json:"indexer,omitempty"`
	SizeBytes    int64   `json:"sizeBytes"`
	Seeders      int     `json:"seeders"`
	Leechers     int     `json:"leechers"`
	Score        float64 `json:"score"`
	Ownership    string  `json:"ownership"`
	Rejected     bool    `json:"rejected"`
	Reason       string  `json:"reason,omitempty"`
	Freeleech    bool    `json:"freeleech,omitempty"`
	Format       string  `json:"format,omitempty"`
	FormatStatus string  `json:"formatStatus"`
}

type ReadingReleaseResponse struct {
	SeriesID int              `json:"seriesId"`
	Releases []ReadingRelease `json:"releases"`
	Errors   []string         `json:"errors"`
}

func (s *Server) readingReleaseSeries(w http.ResponseWriter, r *http.Request) (int, bool) {
	if !s.requireReadingRequest(w, r) || !s.requireBookKeeprrRequests(w, r) {
		return 0, false
	}
	seriesID, err := strconv.Atoi(r.PathValue("seriesId"))
	if err != nil || seriesID <= 0 {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "invalid reading series id"})
		return 0, false
	}
	return seriesID, true
}

func (s *Server) handleReadingReleases(w http.ResponseWriter, r *http.Request) {
	seriesID, ok := s.readingReleaseSeries(w, r)
	if !ok {
		return
	}
	ctx, cancel := timeoutFor(r, 20*time.Second)
	defer cancel()
	series, err := s.bookkeeprr.SeriesByID(ctx, seriesID)
	if err != nil {
		writeUpstreamError(w, r, "bookkeeprr", err)
		return
	}
	if !validReadingReleaseType(series.ContentType) {
		writeError(w, r, http.StatusBadGateway, Error{Code: CodeInternal, Message: "BookKeeprr series has no supported content type"})
		return
	}
	upstream, err := s.bookkeeprr.Releases(ctx, seriesID)
	if err != nil {
		writeUpstreamError(w, r, "bookkeeprr", err)
		return
	}
	s.readingReleaseTickets.clear(TokenFrom(r.Context()).Label, seriesID)
	out := ReadingReleaseResponse{SeriesID: seriesID, Releases: []ReadingRelease{}, Errors: []string{}}
	for _, candidate := range upstream.Releases {
		if candidate.ID <= 0 || candidate.Title == "" {
			continue
		}
		format, formatStatus := readingReleaseFormat(candidate.Title, nil, string(series.ContentType))
		id, err := s.readingReleaseTickets.put(readingReleaseTicket{Owner: TokenFrom(r.Context()).Label, SeriesID: seriesID,
			ReleaseID: candidate.ID, Title: candidate.Title, ContentType: series.ContentType, FormatStatus: formatStatus})
		if err != nil {
			writeError(w, r, http.StatusServiceUnavailable, Error{Code: CodeInternal, Message: "too many release choices; try again shortly"})
			return
		}
		out.Releases = append(out.Releases, ReadingRelease{ID: id, Title: candidate.Title, Indexer: candidate.IndexerName,
			SizeBytes: candidate.SizeBytes, Seeders: candidate.Seeders, Leechers: candidate.Leechers,
			Score: candidate.Score, Ownership: candidate.Ownership,
			Rejected: formatStatus == "incompatible" || candidate.RejectionReason != "" || candidate.RejectedAt != nil,
			Reason:   readingReleaseReason(formatStatus, series.ContentType, candidate.RejectionReason), Format: format, FormatStatus: formatStatus})
	}
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) handleReadingReleaseSearch(w http.ResponseWriter, r *http.Request) {
	seriesID, ok := s.readingReleaseSeries(w, r)
	if !ok {
		return
	}
	ctx, cancel := timeoutFor(r, 35*time.Second)
	defer cancel()
	series, err := s.bookkeeprr.SeriesByID(ctx, seriesID)
	if err != nil {
		writeUpstreamError(w, r, "bookkeeprr", err)
		return
	}
	if !validReadingReleaseType(series.ContentType) {
		writeError(w, r, http.StatusBadGateway, Error{Code: CodeInternal, Message: "BookKeeprr series has no supported content type"})
		return
	}
	upstream, err := s.bookkeeprr.InteractiveSearch(ctx, seriesID)
	if err != nil {
		writeUpstreamError(w, r, "bookkeeprr", err)
		return
	}
	s.readingReleaseTickets.clear(TokenFrom(r.Context()).Label, seriesID)
	out := ReadingReleaseResponse{SeriesID: seriesID, Releases: []ReadingRelease{}, Errors: []string{}}
	for _, result := range upstream.Results {
		if result.Item.Title == "" || result.Item.Link == "" {
			continue
		}
		copy := result
		format, formatStatus := readingReleaseFormat(result.Item.Title, result.Parsed, string(series.ContentType))
		id, err := s.readingReleaseTickets.put(readingReleaseTicket{Owner: TokenFrom(r.Context()).Label, SeriesID: seriesID,
			ReleaseID: result.ReleaseID, Title: result.Item.Title, ContentType: series.ContentType,
			Interactive: &copy, FormatStatus: formatStatus})
		if err != nil {
			writeError(w, r, http.StatusServiceUnavailable, Error{Code: CodeInternal, Message: "too many release choices; try again shortly"})
			return
		}
		out.Releases = append(out.Releases, ReadingRelease{ID: id, Title: result.Item.Title, Indexer: result.Item.IndexerName,
			SizeBytes: result.Item.SizeBytes, Seeders: result.Item.Seeders, Leechers: result.Item.Leechers,
			Score: result.MatchResult.Score, Ownership: result.Ownership, Rejected: formatStatus == "incompatible" || !result.MatchResult.Matches,
			Reason: readingReleaseReason(formatStatus, series.ContentType, result.MatchResult.Reason), Freeleech: result.Item.Freeleech,
			Format: format, FormatStatus: formatStatus})
	}
	for _, issue := range upstream.Errors {
		out.Errors = append(out.Errors, safeReadingIndexerError(issue.Message))
	}
	writeJSON(w, http.StatusOK, out)
}

func safeReadingIndexerError(message string) string {
	value := strings.ToLower(message)
	switch {
	case strings.Contains(value, "429"), strings.Contains(value, "rate limit"):
		return "Indexer rate limited (HTTP 429)"
	case strings.Contains(value, "401"), strings.Contains(value, "403"), strings.Contains(value, "unauthorized"):
		return "Indexer authentication failed"
	case strings.Contains(value, "timeout"), strings.Contains(value, "timed out"):
		return "Indexer timed out"
	default:
		return "Indexer unavailable"
	}
}

func (s *Server) handleReadingReleaseGrab(w http.ResponseWriter, r *http.Request) {
	seriesID, ok := s.readingReleaseSeries(w, r)
	if !ok {
		return
	}
	var body struct {
		ID string `json:"id"`
	}
	decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<10))
	decoder.DisallowUnknownFields()
	if decoder.Decode(&body) != nil || !strings.HasPrefix(body.ID, "br_") {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "invalid release choice"})
		return
	}
	ticket, ok := s.readingReleaseTickets.take(body.ID, TokenFrom(r.Context()).Label, seriesID)
	if !ok {
		writeError(w, r, http.StatusNotFound, Error{Code: CodeNotFound, Message: "release choice expired; search again"})
		return
	}
	parsed := json.RawMessage(nil)
	if ticket.Interactive != nil {
		parsed = ticket.Interactive.Parsed
	}
	_, verifiedStatus := readingReleaseFormat(ticket.Title, parsed, string(ticket.ContentType))
	if ticket.FormatStatus == "incompatible" || verifiedStatus == "incompatible" {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "release format is incompatible with the requested content type"})
		return
	}
	ctx, cancel := timeoutFor(r, 30*time.Second)
	defer cancel()
	var grabbed *bookkeeprr.GrabbedRelease
	var err error
	if ticket.ReleaseID > 0 {
		grabbed, err = s.bookkeeprr.GrabRelease(ctx, ticket.ReleaseID)
	} else if ticket.Interactive != nil {
		grabbed, err = s.bookkeeprr.InteractiveGrab(ctx, seriesID, *ticket.Interactive)
	} else {
		err = fmt.Errorf("release choice is invalid")
	}
	if err != nil {
		writeUpstreamError(w, r, "bookkeeprr", err)
		return
	}
	writeJSON(w, http.StatusAccepted, map[string]any{"ok": true, "action": "grab", "status": grabbed.Status, "message": "Download queued in BookKeeprr"})
}

var readingFormatToken = regexp.MustCompile(`(?i)(?:^|[^a-z0-9])(epub|pdf|mobi|azw3?|fb2|djvu|mp3|m4[ab]|aac|flac|ogg|wav|wma|cbz|cbr|cb7|cbt)(?:$|[^a-z0-9])`)

func validReadingReleaseType(kind bookkeeprr.ContentType) bool {
	_, err := bookkeeprr.ParseContentType(string(kind), false)
	return err == nil
}

func readingReleaseReason(status string, kind bookkeeprr.ContentType, upstream string) string {
	if status == "incompatible" {
		return "Wrong file format for " + string(kind) + " request"
	}
	return upstream
}

// Unknown formats remain reviewable. A known wrong format must never be queued,
// even when the upstream indexer considers the release a match.
func readingReleaseFormat(title string, parsed json.RawMessage, requestKind string) (string, string) {
	parsedFormat := ""
	if len(parsed) > 0 {
		var metadata struct {
			Format    string `json:"format"`
			Extension string `json:"extension"`
		}
		if json.Unmarshal(parsed, &metadata) == nil {
			parsedFormat = strings.ToUpper(strings.TrimPrefix(strings.TrimSpace(metadata.Format), "."))
			if parsedFormat == "" {
				parsedFormat = strings.ToUpper(strings.TrimPrefix(strings.TrimSpace(metadata.Extension), "."))
			}
		}
	}
	titleFormat := ""
	if match := readingFormatToken.FindStringSubmatch(title); len(match) > 1 {
		titleFormat = strings.ToUpper(match[1])
	}
	format := parsedFormat
	if format == "" {
		format = titleFormat
	}
	if format == "" {
		return "", "unknown"
	}
	expected := "text"
	switch requestKind {
	case "audiobook":
		expected = "audio"
	case "comic", "manga":
		expected = "images"
	case "ebook", "light_novel":
		expected = "text"
	default:
		return format, "unknown"
	}
	for _, candidate := range []string{parsedFormat, titleFormat} {
		if candidate == "" {
			continue
		}
		category := readingFormatCategory(candidate)
		if category != "" && category != expected {
			return candidate, "incompatible"
		}
	}
	if readingFormatCategory(format) == expected {
		return format, "compatible"
	}
	return format, "unknown"
}

func readingFormatCategory(format string) string {
	switch format {
	case "EPUB", "PDF", "MOBI", "AZW", "AZW3", "FB2", "DJVU":
		return "text"
	case "MP3", "M4A", "M4B", "AAC", "FLAC", "OGG", "WAV", "WMA":
		return "audio"
	case "CBZ", "CBR", "CB7", "CBT":
		return "images"
	default:
		return ""
	}
}
