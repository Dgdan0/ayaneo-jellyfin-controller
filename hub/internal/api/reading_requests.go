package api

import (
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"ayaneohub/internal/adapters/bookkeeprr"
	"ayaneohub/internal/httpx"
)

type readingCandidateStore struct {
	mu       sync.Mutex
	items    map[string]bookkeeprr.Item
	order    []string
	maxItems int
}

func newReadingCandidateStore(maxItems int) *readingCandidateStore {
	return &readingCandidateStore{items: map[string]bookkeeprr.Item{}, maxItems: maxItems}
}

func (s *readingCandidateStore) put(key string, item bookkeeprr.Item) {
	if s == nil || key == "" {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, exists := s.items[key]; !exists {
		s.order = append(s.order, key)
	}
	s.items[key] = item
	for len(s.order) > s.maxItems {
		oldest := s.order[0]
		s.order = s.order[1:]
		delete(s.items, oldest)
	}
}

func (s *readingCandidateStore) get(key string) (bookkeeprr.Item, bool) {
	if s == nil {
		return bookkeeprr.Item{}, false
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	item, ok := s.items[key]
	return item, ok
}

type ReadingRequestMode struct {
	ID                 string `json:"id"`
	Label              string `json:"label"`
	RequiresTotalBooks bool   `json:"requiresTotalBooks"`
}

type ReadingQualityProfile struct {
	ID                    int    `json:"id"`
	Label                 string `json:"label"`
	Default               bool   `json:"default"`
	PreferCompleteBatches bool   `json:"preferCompleteBatches"`
}

type ReadingRequestOptions struct {
	Key             string                  `json:"key"`
	ContentType     string                  `json:"contentType"`
	Title           string                  `json:"title"`
	Author          string                  `json:"author,omitempty"`
	Modes           []ReadingRequestMode    `json:"modes"`
	QualityProfiles []ReadingQualityProfile `json:"qualityProfiles"`
	Monitoring      []string                `json:"monitoring"`
}

type readingCreateRequestBody struct {
	Key              string `json:"key"`
	Mode             string `json:"mode"`
	TotalBooks       int    `json:"totalBooks,omitempty"`
	QualityProfileID int    `json:"qualityProfileId"`
	Monitoring       string `json:"monitoring,omitempty"`
}

type ReadingRequestResponse struct {
	RequestID string `json:"requestId"`
	SeriesID  int    `json:"seriesId"`
	State     string `json:"state"`
	Message   string `json:"message"`
}

type ReadingDownloadItem struct {
	ID               string   `json:"id"`
	SeriesID         int      `json:"seriesId,omitempty"`
	ContentType      string   `json:"contentType,omitempty"`
	Title            string   `json:"title"`
	ReleaseTitle     string   `json:"releaseTitle,omitempty"`
	Status           string   `json:"status"`
	ProgressPercent  int      `json:"progressPercent"`
	DownloadSpeedBPS int64    `json:"downloadSpeedBytesPerSecond,omitempty"`
	ETASeconds       int64    `json:"etaSeconds,omitempty"`
	SizeBytes        int64    `json:"sizeBytes,omitempty"`
	AddedAt          string   `json:"addedAt,omitempty"`
	CompletedAt      string   `json:"completedAt,omitempty"`
	ImportedAt       string   `json:"importedAt,omitempty"`
	Failed           bool     `json:"failed"`
	Actions          []string `json:"actions"`
}

type ReadingDownloadsResponse struct {
	Items []ReadingDownloadItem `json:"items"`
}

func validReadingCandidateKey(key string) bool {
	digest, ok := strings.CutPrefix(key, "reading:")
	if !ok || len(digest) != 32 {
		return false
	}
	_, err := hex.DecodeString(digest)
	return err == nil
}

func (s *Server) requireReadingRequest(w http.ResponseWriter, r *http.Request) bool {
	if !s.requireReading(w, r) {
		return false
	}
	if TokenFrom(r.Context()).HasScope("request") {
		return true
	}
	writeError(w, r, http.StatusForbidden, Error{
		Code: CodeForbiddenScope, Message: "this device is not allowed to request books",
	})
	return false
}

func (s *Server) readingCandidate(w http.ResponseWriter, r *http.Request, key string) (bookkeeprr.Item, bool) {
	if !validReadingCandidateKey(key) {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "invalid reading item key"})
		return bookkeeprr.Item{}, false
	}
	item, ok := s.readingCandidates.get(key)
	if !ok {
		writeError(w, r, http.StatusNotFound, Error{
			Code: CodeNotFound, Message: "this reading result expired; search for it again",
		})
		return bookkeeprr.Item{}, false
	}
	return item, true
}

func (s *Server) requireBookKeeprrRequests(w http.ResponseWriter, r *http.Request) bool {
	if s.bookkeeprr == nil {
		writeError(w, r, http.StatusServiceUnavailable, Error{
			Code: CodeUpstreamDown, Service: "bookkeeprr", Message: "BookKeeprr is not configured",
		})
		return false
	}
	if !s.bookkeeprr.CanRequest() {
		writeError(w, r, http.StatusServiceUnavailable, Error{
			Code: "upstream_misconfigured", Service: "bookkeeprr",
			Message: "BookKeeprr needs a Hub service-account username and password before requests can be added",
		})
		return false
	}
	return true
}

func (s *Server) handleReadingRequestOptions(w http.ResponseWriter, r *http.Request) {
	if !s.requireReadingRequest(w, r) || !s.requireBookKeeprrRequests(w, r) {
		return
	}
	key := strings.TrimSpace(r.URL.Query().Get("key"))
	item, ok := s.readingCandidate(w, r, key)
	if !ok {
		return
	}
	ctx, cancel := timeoutFor(r, s.cfg.Server.RequestTimeout.OrDefault(20*time.Second))
	defer cancel()
	profiles, err := s.bookkeeprr.QualityProfiles(ctx)
	if err != nil {
		writeUpstreamError(w, r, "bookkeeprr", err)
		return
	}
	out := ReadingRequestOptions{
		Key: key, ContentType: string(item.ContentType), Title: item.Title, Author: item.Author,
		Modes: readingRequestModes(item.ContentType), QualityProfiles: make([]ReadingQualityProfile, 0, len(profiles)),
		Monitoring: []string{"all", "none"},
	}
	for _, profile := range profiles {
		out.QualityProfiles = append(out.QualityProfiles, ReadingQualityProfile{
			ID: profile.ID, Label: profile.Name, Default: profile.IsDefault,
			PreferCompleteBatches: profile.PreferCompleteBatches,
		})
	}
	writeJSON(w, http.StatusOK, out)
}

func readingRequestModes(kind bookkeeprr.ContentType) []ReadingRequestMode {
	switch kind {
	case bookkeeprr.TypeEbook:
		return []ReadingRequestMode{
			{ID: "single", Label: "This book"},
			{ID: "series", Label: "Entire series", RequiresTotalBooks: true},
		}
	case bookkeeprr.TypeAudiobook:
		return []ReadingRequestMode{{ID: "single", Label: "This audiobook"}}
	default:
		return []ReadingRequestMode{{ID: "series", Label: "This series"}}
	}
}

func (s *Server) handleReadingCreateRequest(w http.ResponseWriter, r *http.Request) {
	if !s.requireReadingRequest(w, r) || !s.requireBookKeeprrRequests(w, r) {
		return
	}
	var body readingCreateRequestBody
	decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 8<<10))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&body); err != nil {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "invalid reading request body"})
		return
	}
	item, ok := s.readingCandidate(w, r, strings.TrimSpace(body.Key))
	if !ok {
		return
	}
	payload, err := readingCreatePayload(item, body)
	if err != nil {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: err.Error()})
		return
	}
	ctx, cancel := timeoutFor(r, s.cfg.Server.RequestTimeout.OrDefault(25*time.Second))
	defer cancel()
	profiles, err := s.bookkeeprr.QualityProfiles(ctx)
	if err != nil {
		writeUpstreamError(w, r, "bookkeeprr", err)
		return
	}
	validProfile := false
	for _, profile := range profiles {
		if profile.ID == body.QualityProfileID {
			validProfile = true
			break
		}
	}
	if !validProfile {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "unknown BookKeeprr quality profile"})
		return
	}
	created, err := s.bookkeeprr.CreateSeries(ctx, payload)
	if err != nil {
		var upstream *httpx.Error
		if errors.As(err, &upstream) && upstream.Status == http.StatusConflict {
			writeError(w, r, http.StatusConflict, Error{
				Code: "already_exists", Service: "bookkeeprr", Message: "this title is already in the reading library",
			})
			return
		}
		writeUpstreamError(w, r, "bookkeeprr", err)
		return
	}
	writeJSON(w, http.StatusAccepted, ReadingRequestResponse{
		RequestID: fmt.Sprintf("bookkeeprr:series:%d", created.ID), SeriesID: created.ID,
		State: "accepted", Message: "BookKeeprr is searching for releases",
	})
}

func readingCreatePayload(item bookkeeprr.Item, body readingCreateRequestBody) (bookkeeprr.CreateSeriesRequest, error) {
	if body.QualityProfileID <= 0 {
		return bookkeeprr.CreateSeriesRequest{}, fmt.Errorf("qualityProfileId must be positive")
	}
	monitoring := strings.TrimSpace(strings.ToLower(body.Monitoring))
	if monitoring == "" {
		monitoring = "all"
	}
	if monitoring != "all" && monitoring != "none" {
		return bookkeeprr.CreateSeriesRequest{}, fmt.Errorf("monitoring must be all or none")
	}
	request := bookkeeprr.CreateSeriesRequest{
		ContentType: item.ContentType, QualityProfileID: body.QualityProfileID, Monitoring: monitoring,
		CoverURL: item.CoverURL, Description: item.Description, Author: item.Author, Year: item.Year,
	}
	switch item.ContentType {
	case bookkeeprr.TypeEbook:
		if body.Mode != "single" && body.Mode != "series" {
			return request, fmt.Errorf("mode must be single or series for an ebook")
		}
		if body.Mode == "series" && (body.TotalBooks < 1 || body.TotalBooks > 200) {
			return request, fmt.Errorf("totalBooks must be between 1 and 200 for a series")
		}
		request.Flow = body.Mode
		request.OLID = item.Sources.OpenLibrary
		if request.OLID == "" {
			request.OLID = item.SourceID
		}
		request.ISBN = item.ISBN
		request.Title = item.Title
		request.TotalVolumes = body.TotalBooks
	case bookkeeprr.TypeAudiobook:
		if body.Mode != "single" {
			return request, fmt.Errorf("mode must be single for an audiobook")
		}
		request.Title = item.Title
		request.ASIN = item.Sources.Audnex
		if request.ASIN == "" && item.Source == "audnex" {
			request.ASIN = item.SourceID
		}
	case bookkeeprr.TypeComic:
		if body.Mode != "series" {
			return request, fmt.Errorf("mode must be series for comics")
		}
		request.TitleEnglish = item.Title
		request.Publisher = item.Author
		request.StartYear = item.Year
		request.Status = "releasing"
		if item.Sources.Comicvine != nil {
			request.ComicvineID = *item.Sources.Comicvine
		} else if item.Source == "comicvine" {
			request.ComicvineID, _ = positiveReadingID(item.SourceID, "")
		}
		if request.ComicvineID <= 0 {
			return request, fmt.Errorf("comic result has no ComicVine id")
		}
	case bookkeeprr.TypeManga:
		if body.Mode != "series" {
			return request, fmt.Errorf("mode must be series for manga")
		}
		request.TitleEnglish = item.Title
		request.Status = "releasing"
		request.AnilistID = item.Sources.Anilist
		request.MalID = item.Sources.Mal
		request.MangadexID = item.Sources.Mangadex
		if request.AnilistID == nil && item.Source == "anilist" {
			request.AnilistID = readingIDPointer(item.SourceID, "")
		}
		if request.MalID == nil {
			request.MalID = item.MalID
		}
		if request.MalID == nil && item.Source == "mal" {
			request.MalID = readingIDPointer(item.SourceID, "mal:")
		}
		if request.MangadexID == "" && item.Source == "mangadex" {
			request.MangadexID = item.SourceID
		}
	case bookkeeprr.TypeLightNovel:
		if body.Mode != "series" {
			return request, fmt.Errorf("mode must be series for light novels")
		}
		request.TitleEnglish = item.Title
		request.Status = "releasing"
		request.AnilistID = item.Sources.Anilist
		request.NovelUpdatesSlug = item.Sources.NovelUpdates
		if request.AnilistID == nil && item.Source == "anilist" {
			request.AnilistID = readingIDPointer(item.SourceID, "")
		}
		if request.NovelUpdatesSlug == "" && item.Source == "novelupdates" {
			request.NovelUpdatesSlug = strings.TrimPrefix(item.SourceID, "nu:")
		}
		if request.AnilistID == nil && request.NovelUpdatesSlug == "" {
			return request, fmt.Errorf("light novel result has no supported provider id")
		}
	default:
		return request, fmt.Errorf("unsupported reading content type")
	}
	return request, nil
}

func positiveReadingID(raw, prefix string) (int, error) {
	raw = strings.TrimPrefix(strings.TrimSpace(raw), prefix)
	id, err := strconv.Atoi(raw)
	if err != nil || id <= 0 {
		return 0, fmt.Errorf("invalid provider id")
	}
	return id, nil
}

func readingIDPointer(raw, prefix string) *int {
	id, err := positiveReadingID(raw, prefix)
	if err != nil {
		return nil
	}
	return &id
}

func (s *Server) handleReadingDownloads(w http.ResponseWriter, r *http.Request) {
	if !s.requireReading(w, r) {
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
	downloads, err := s.bookkeeprr.Downloads(ctx)
	if err != nil {
		writeUpstreamError(w, r, "bookkeeprr", err)
		return
	}
	items, err := s.readingTransfers.reconcile(downloads.Downloads)
	if err != nil {
		writeError(w, r, http.StatusInternalServerError, Error{Code: CodeInternal, Message: "could not save reading transfer state"})
		return
	}
	canControl := TokenFrom(r.Context()).HasScope("request") && s.bookkeeprr.CanRequest()
	for i := range items {
		items[i].Actions = readingTransferActions(items[i], canControl)
	}
	writeJSON(w, http.StatusOK, ReadingDownloadsResponse{Items: items})
}

func readingDownloadItem(id string, download bookkeeprr.Download) ReadingDownloadItem {
	item := ReadingDownloadItem{
		ID: id, Status: download.Status, AddedAt: download.AddedAt,
		Failed: download.Status == "failed" || download.Error != nil, Actions: []string{},
	}
	if download.Progress != nil {
		item.ProgressPercent = int(math.Round(math.Max(0, math.Min(1, *download.Progress)) * 100))
	}
	if download.DownloadSpeed != nil {
		item.DownloadSpeedBPS = *download.DownloadSpeed
	}
	if download.ETA != nil {
		item.ETASeconds = *download.ETA
	}
	if download.SizeBytes != nil {
		item.SizeBytes = *download.SizeBytes
	}
	if download.CompletedAt != nil {
		item.CompletedAt = *download.CompletedAt
	}
	if download.ImportedAt != nil {
		item.ImportedAt = *download.ImportedAt
	}
	if download.Release != nil {
		item.ReleaseTitle = download.Release.Title
		item.Title = download.Release.Title
	}
	if download.Series != nil {
		item.SeriesID = download.Series.ID
		item.ContentType = string(download.Series.ContentType)
		item.Title = download.Series.Title
	}
	if item.Title == "" {
		item.Title = "Reading download"
	}
	return item
}

func readingTransferActions(item ReadingDownloadItem, canControl bool) []string {
	if !canControl {
		return []string{}
	}
	if item.Failed || item.Status == "retry_pending" {
		return []string{"retry", "cancel"}
	}
	switch item.Status {
	case "queued", "downloading":
		return []string{"cancel"}
	default:
		return []string{}
	}
}

func validReadingTransferID(id string) bool {
	digest, ok := strings.CutPrefix(id, "rt_")
	if !ok || len(digest) != 32 {
		return false
	}
	_, err := hex.DecodeString(digest)
	return err == nil
}

func (s *Server) readingTransferForAction(w http.ResponseWriter, r *http.Request) (string, readingTransferRecord, bool) {
	if !s.requireReadingRequest(w, r) || !s.requireBookKeeprrRequests(w, r) {
		return "", readingTransferRecord{}, false
	}
	id := strings.TrimSpace(r.PathValue("transferId"))
	if !validReadingTransferID(id) {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "invalid reading transfer id"})
		return "", readingTransferRecord{}, false
	}
	record, err := s.readingTransfers.get(id)
	if errors.Is(err, errReadingTransferNotFound) {
		writeError(w, r, http.StatusNotFound, Error{Code: CodeNotFound, Message: "reading transfer not found"})
		return "", readingTransferRecord{}, false
	}
	if err != nil {
		writeError(w, r, http.StatusInternalServerError, Error{Code: CodeInternal, Message: "could not load reading transfer state"})
		return "", readingTransferRecord{}, false
	}
	return id, record, true
}

func (s *Server) handleReadingDownloadCancel(w http.ResponseWriter, r *http.Request) {
	id, record, ok := s.readingTransferForAction(w, r)
	if !ok {
		return
	}
	if record.Public.Status == "retrying" {
		writeError(w, r, http.StatusConflict, Error{Code: CodeInvalidRequest, Message: "this transfer is already retrying"})
		return
	}
	if !record.Public.Failed && record.Public.Status != "retry_pending" && record.Public.Status != "queued" && record.Public.Status != "downloading" {
		writeError(w, r, http.StatusConflict, Error{Code: CodeInvalidRequest, Message: "completed or imported reading transfers cannot be canceled"})
		return
	}
	ctx, cancel := timeoutFor(r, s.cfg.Server.RequestTimeout.OrDefault(20*time.Second))
	defer cancel()
	if record.QBTHash != "" {
		if err := s.bookkeeprr.CancelDownload(ctx, record.QBTHash); err != nil {
			writeUpstreamError(w, r, "bookkeeprr", err)
			return
		}
	}
	if err := s.readingTransfers.markCanceled(id); err != nil {
		writeError(w, r, http.StatusInternalServerError, Error{Code: CodeInternal, Message: "could not save reading transfer state"})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "action": "cancel"})
}

func (s *Server) handleReadingDownloadRetry(w http.ResponseWriter, r *http.Request) {
	id, record, ok := s.readingTransferForAction(w, r)
	if !ok {
		return
	}
	ctx, cancel := timeoutFor(r, s.cfg.Server.RequestTimeout.OrDefault(25*time.Second))
	defer cancel()

	// Before repeating a pending grab, reconcile an earlier response that may
	// have been lost after BookKeeprr accepted it.
	if record.RetryPending && record.QBTHash == "" {
		if downloads, err := s.bookkeeprr.Downloads(ctx); err == nil {
			_, _ = s.readingTransfers.reconcile(downloads.Downloads)
			if current, getErr := s.readingTransfers.get(id); getErr == nil && !current.RetryPending {
				writeJSON(w, http.StatusOK, map[string]any{"ok": true, "action": "retry", "state": "reconciled"})
				return
			}
		}
	}
	record, err := s.readingTransfers.beginRetry(id)
	if errors.Is(err, errReadingTransferBusy) || errors.Is(err, errReadingTransferState) {
		writeError(w, r, http.StatusConflict, Error{Code: CodeInvalidRequest, Message: err.Error()})
		return
	}
	if err != nil {
		writeError(w, r, http.StatusInternalServerError, Error{Code: CodeInternal, Message: "could not save retry state"})
		return
	}
	if record.ReleaseID <= 0 {
		_ = s.readingTransfers.abortRetry(id)
		writeError(w, r, http.StatusConflict, Error{Code: CodeInvalidRequest, Message: "this failed transfer has no release to retry"})
		return
	}
	if record.QBTHash != "" {
		if err := s.bookkeeprr.CancelDownload(ctx, record.QBTHash); err != nil {
			_ = s.readingTransfers.abortRetry(id)
			writeUpstreamError(w, r, "bookkeeprr", err)
			return
		}
		if err := s.readingTransfers.markRetryPending(id, record.UpstreamID); err != nil {
			writeError(w, r, http.StatusInternalServerError, Error{Code: CodeInternal, Message: "could not save retry state"})
			return
		}
	}
	grabbed, err := s.bookkeeprr.GrabRelease(ctx, record.ReleaseID)
	if err != nil {
		_ = s.readingTransfers.markRetryPending(id, record.UpstreamID)
		writeUpstreamError(w, r, "bookkeeprr", err)
		return
	}
	if err := s.readingTransfers.completeRetry(id, grabbed); err != nil {
		writeError(w, r, http.StatusInternalServerError, Error{Code: CodeInternal, Message: "the release was grabbed but its transfer state could not be saved"})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "action": "retry", "state": "queued"})
}
