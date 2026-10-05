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

// requireSingleByteRange reads the Range and If-Range headers of a file
// request. One byte range, at most, is what a player or a resumed download asks
// for; anything else is answered 400 here, so a multipart answer is never built
// (by Storyteller's server for an EPUB, by http.ServeContent for a track).
func requireSingleByteRange(w http.ResponseWriter, r *http.Request) (byteRange, ifRange string, ok bool) {
	byteRange = strings.TrimSpace(r.Header.Get("Range"))
	if byteRange != "" && !singleByteRange.MatchString(byteRange) {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "only one valid byte range may be requested"})
		return "", "", false
	}
	ifRange = strings.TrimSpace(r.Header.Get("If-Range"))
	if len(ifRange) > 512 {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "invalid If-Range validator"})
		return "", "", false
	}
	return byteRange, ifRange, true
}

// ReadingEpubPosition is a book's place as a reader of its text sees it. When the
// place was written by a listener, on a book with a read-along edition, locator
// is the sentence being spoken there (an older reader opens it as it opens its
// own), and audio is the audio place it came from.
type ReadingEpubPosition struct {
	WorkID       string                `json:"workId"`
	SourceItemID string                `json:"sourceItemId"`
	Locator      json.RawMessage       `json:"locator"`
	Timestamp    int64                 `json:"timestamp,omitempty"`
	UpdatedAt    string                `json:"updatedAt,omitempty"`
	Audio        *ReadingAudioPosition `json:"audio,omitempty"`
}

func (s *Server) handleReadingEpubFile(w http.ResponseWriter, r *http.Request) {
	if !s.requireReading(w, r) {
		return
	}
	format := r.URL.Query().Get("format")
	if format != "" && format != "ebook" && format != "readaloud" && format != "audiobook" {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "invalid publication format"})
		return
	}
	byteRange, ifRange, ok := requireSingleByteRange(w, r)
	if !ok {
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
	open := s.storyteller.OpenEbook
	if format == "readaloud" {
		open = s.storyteller.OpenReadaloud
	} else if format == "audiobook" {
		open = s.storyteller.OpenAudiobook
	}
	response, err := open(r.Context(), bookID, byteRange, ifRange)
	if err != nil {
		writeUpstreamError(w, r, "storyteller", err)
		return
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK && response.StatusCode != http.StatusPartialContent {
		writeError(w, r, http.StatusBadGateway, Error{Code: CodeUpstreamDown, Service: "storyteller", Message: "Storyteller returned an invalid publication response", Retryable: true})
		return
	}
	if err := prepareLongStream(w); err != nil {
		writeError(w, r, http.StatusInternalServerError, Error{Code: CodeInternal, Message: "the EPUB stream could not be prepared", Retryable: true})
		return
	}
	for _, name := range []string{"Content-Length", "Content-Range", "Accept-Ranges", "ETag", "Last-Modified"} {
		if value := response.Header.Get(name); value != "" {
			w.Header().Set(name, value)
		}
	}
	contentType := strings.ToLower(strings.TrimSpace(strings.Split(response.Header.Get("Content-Type"), ";")[0]))
	if format == "audiobook" {
		if contentType != "application/zip" && contentType != "application/octet-stream" {
			contentType = "application/zip"
		}
	} else if contentType != "application/epub+zip" && contentType != "application/octet-stream" {
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
		position, err := s.currentStorytellerPosition(ctx, bookID)
		if err != nil {
			writeUpstreamError(w, r, "storyteller", err)
			return
		}
		if position == nil {
			writeJSON(w, http.StatusOK, ReadingEpubPosition{WorkID: workID, SourceItemID: sourceItemID, Locator: json.RawMessage("null")})
			return
		}
		shown := ReadingEpubPosition{
			WorkID: workID, SourceItemID: sourceItemID, Locator: position.Locator,
			Timestamp: position.Timestamp, UpdatedAt: position.UpdatedAt,
		}
		if sentence, audio := s.textPlaceOfAudio(ctx, bookID, position); sentence != nil {
			shown.Locator, shown.Audio = sentence, audio
		}
		writeJSON(w, http.StatusOK, shown)
		return
	}

	var body struct {
		Locator         json.RawMessage `json:"locator"`
		Timestamp       int64           `json:"timestamp"`
		CheckBase       bool            `json:"checkBase"`
		ExpectedLocator json.RawMessage `json:"expectedLocator"`
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
	unlock := lockReadingCheckpoint("storyteller", strconv.FormatInt(bookID, 10))
	defer unlock()
	if body.CheckBase {
		current, err := s.currentStorytellerPosition(ctx, bookID)
		if err != nil {
			writeUpstreamError(w, r, "storyteller", err)
			return
		}
		var locator json.RawMessage
		if current != nil {
			locator = current.Locator
		}
		if !sameReadingLocator(body.ExpectedLocator, locator) {
			// A reader of the text was shown the sentence an audio place comes to,
			// not the locator the table holds: either is the place it last saw.
			shown, _ := s.textPlaceOfAudio(ctx, bookID, current)
			if shown == nil || !sameReadingLocator(body.ExpectedLocator, shown) {
				writePositionChanged(w, r)
				return
			}
		}
	}
	if err := s.storyteller.SavePosition(ctx, bookID, body.Locator, body.Timestamp); err != nil {
		var upstream *httpx.Error
		if errors.As(err, &upstream) && upstream.Status == http.StatusConflict {
			writeStorytellerNewerPosition(w, r)
			return
		}
		writeUpstreamError(w, r, "storyteller", err)
		return
	}
	s.invalidateStorytellerWork(sourceItemID)
	s.cache.Invalidate("reading:storyteller:books")
	writeJSON(w, http.StatusOK, struct {
		OK     bool   `json:"ok"`
		Action string `json:"action"`
	}{OK: true, Action: "save_epub_position"})
}

// resolveStorytellerEbook is resolveStorytellerBook for the routes that serve an
// EPUB: the book must have the edition the URL asks for. Which edition that is
// is read from the URL here (the format, or a /position), and only here; a
// route that wants another, such as audio, resolves the book and decides itself.
func (s *Server) resolveStorytellerEbook(w http.ResponseWriter, r *http.Request, ctx context.Context) (int64, bool) {
	book, _, ok := s.resolveStorytellerBook(w, r, ctx, "EPUB publication")
	if !ok {
		return 0, false
	}
	available := book.Ebook != nil && !book.Ebook.Missing
	if r.URL.Query().Get("format") == "readaloud" {
		available = book.Readaloud.Available()
	} else if r.URL.Query().Get("format") == "audiobook" {
		available = book.Audiobook != nil && !book.Audiobook.Missing
	} else if strings.HasSuffix(r.URL.Path, "/position") {
		available = available || book.Readaloud.Available()
	}
	if !available {
		writeError(w, r, http.StatusNotFound, Error{Code: CodeNotFound, Message: "this work has no available EPUB edition"})
		return 0, false
	}
	return book.ID, true
}

// resolveStorytellerBook finds the Storyteller book a publication URL names and
// checks that it belongs to the work in the URL: directly, through its series,
// or as another edition of the same title. It answers 400, 404 or 503 itself
// when it cannot. what names the thing in the 400.
func (s *Server) resolveStorytellerBook(w http.ResponseWriter, r *http.Request, ctx context.Context, what string) (storyteller.Book, cache.Meta, bool) {
	workID := strings.TrimSpace(r.PathValue("workId"))
	bookID, err := strconv.ParseInt(strings.TrimSpace(r.PathValue("sourceItemId")), 10, 64)
	if !validReadingWorkID(workID) || err != nil || bookID <= 0 {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "invalid " + what})
		return storyteller.Book{}, cache.Meta{}, false
	}
	if s.storyteller == nil {
		writeError(w, r, http.StatusServiceUnavailable, Error{Code: CodeUpstreamDown, Service: "storyteller", Message: "Storyteller is unavailable", Retryable: true})
		return storyteller.Book{}, cache.Meta{}, false
	}
	binding, found := s.readingCatalog.Resolve(workID)
	if !found {
		writeError(w, r, http.StatusNotFound, Error{Code: CodeNotFound, Message: "reading work not found"})
		return storyteller.Book{}, cache.Meta{}, false
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
	var meta cache.Meta
	if direct {
		loaded, loadMeta, loadErr := s.storytellerBookRecord(ctx, bookID)
		if loadErr != nil {
			writeUpstreamError(w, r, "storyteller", loadErr)
			return storyteller.Book{}, cache.Meta{}, false
		}
		book, meta = *loaded, loadMeta
	} else if seriesSource != "" {
		books, listMeta, loadErr := cache.Fetch(ctx, s.cache, "reading:storyteller:books", cache.LibraryPage, s.storyteller.Books)
		if loadErr != nil {
			writeUpstreamError(w, r, "storyteller", loadErr)
			return storyteller.Book{}, cache.Meta{}, false
		}
		meta = listMeta
		for _, candidate := range books {
			source, _, grouped := storytellerSeriesSource(s.reconcileStorytellerBook(candidate))
			if candidate.ID == bookID && grouped && source == seriesSource {
				book = candidate
				break
			}
		}
	}
	if book.ID != bookID && !direct && s.storyteller != nil {
		books, _, loadErr := cache.Fetch(ctx, s.cache, "reading:storyteller:books", cache.LibraryPage, s.storyteller.Books)
		if loadErr == nil {
			var requested storyteller.Book
			for _, candidate := range books {
				if candidate.ID == bookID {
					requested = candidate
					break
				}
			}
			if requested.ID > 0 {
				for _, source := range binding.Sources {
					if source.Source != "storyteller" {
						continue
					}
					for _, base := range books {
						if strconv.FormatInt(base.ID, 10) == source.SourceID && sameStorytellerEditionWork(base, requested) {
							book = requested
							break
						}
					}
				}
			}
		}
	}
	if book.ID != bookID {
		writeError(w, r, http.StatusNotFound, Error{Code: CodeNotFound, Message: "publication does not belong to this work"})
		return storyteller.Book{}, cache.Meta{}, false
	}
	return s.reconcileStorytellerBook(book), meta, true
}

// storytellerBookRecord is one Storyteller book from its own endpoint, held a day
// and cleared with everything else the hub knows of Storyteller. A series or a
// shelf is built from the list endpoint, which is not the place to read an
// audiobook's folder and manifest from.
func (s *Server) storytellerBookRecord(ctx context.Context, id int64) (*storyteller.Book, cache.Meta, error) {
	return cache.Fetch(ctx, s.cache, storytellerWorkKey(strconv.FormatInt(id, 10)), cache.Metadata, func(fetchCtx context.Context) (*storyteller.Book, error) {
		return s.storyteller.Book(fetchCtx, id)
	})
}

func storytellerWorkKey(sourceItemID string) string {
	return "reading:storyteller:work:" + sourceItemID
}

// What a refused write of a reading position says, the same for the EPUB route and
// the audio one so an app has one conflict to know: another device moved the place
// since the app last looked (it chose to be asked), or Storyteller itself holds a
// newer one.
const codeReadingPositionConflict = "reading_position_conflict"

func writePositionChanged(w http.ResponseWriter, r *http.Request) {
	writeError(w, r, http.StatusConflict, Error{Code: codeReadingPositionConflict, Message: "Reading progress changed on another device. Choose which position to continue from."})
}

func writeStorytellerNewerPosition(w http.ResponseWriter, r *http.Request) {
	writeError(w, r, http.StatusConflict, Error{Code: codeReadingPositionConflict, Service: "storyteller", Message: "a newer reading position already exists"})
}

// currentStorytellerPosition is the account's place in a book, or nil when it has
// none (Storyteller answers 404 for a book never opened).
func (s *Server) currentStorytellerPosition(ctx context.Context, bookID int64) (*storyteller.PositionRecord, error) {
	record, err := s.storyteller.Position(ctx, bookID)
	if err != nil {
		var upstream *httpx.Error
		if errors.As(err, &upstream) && upstream.Status == http.StatusNotFound {
			return nil, nil
		}
		return nil, err
	}
	return record, nil
}

// textPlaceOfAudio is what a reader of the text is shown of a place a listener
// wrote, on a book whose read-along edition has been mapped to its audio: the
// sentence being spoken there, as a locator the reader opens, and the audio place
// it is read from. Nothing otherwise (a text place, a book with no edition, an
// edition that cannot be mapped, a place the hub only guesses at), and then the
// reader is shown the stored locator, as it always was. It reads and never
// writes, and it fails quietly: the EPUB route does not depend on the audio.
func (s *Server) textPlaceOfAudio(ctx context.Context, bookID int64, position *storyteller.PositionRecord) (json.RawMessage, *ReadingAudioPosition) {
	if position == nil || !looksLikeAudioLocator(position.Locator) {
		return nil, nil
	}
	record, _, err := s.storytellerBookRecord(ctx, bookID)
	if err != nil {
		return nil, nil
	}
	book := s.reconcileStorytellerBook(*record)
	if !book.Readaloud.Available() || book.Audiobook == nil || book.Audiobook.Missing {
		return nil, nil
	}
	plan, _, err := s.audioPlanFor(ctx, book)
	if err != nil || plan.alignment == nil || plan.positionProblem() != "" {
		return nil, nil
	}
	audio := plan.position(position)
	if audio == nil || !audio.Exact || audio.Form != "audio" || audio.Sentence == nil {
		return nil, nil
	}
	sentence := struct {
		Href      string `json:"href"`
		Type      string `json:"type"`
		Locations struct {
			Fragments        []string `json:"fragments"`
			TotalProgression float64  `json:"totalProgression"`
		} `json:"locations"`
	}{Href: audio.Sentence.Href, Type: "application/xhtml+xml"}
	sentence.Locations.Fragments = []string{audio.Sentence.Fragment}
	// How far through the book, in the order the audio plays it.
	sentence.Locations.TotalProgression = share(audio.GlobalMs, plan.totalMs)
	locator, err := json.Marshal(sentence)
	if err != nil {
		return nil, nil
	}
	return locator, audio
}

// looksLikeAudioLocator is the cheap look that keeps the audio files unread for
// a text place: Storyteller's audio apps write a media type and a "t=" fragment.
func looksLikeAudioLocator(raw json.RawMessage) bool {
	var locator struct {
		Type      string `json:"type"`
		Locations struct {
			Fragments []string `json:"fragments"`
		} `json:"locations"`
	}
	if json.Unmarshal(raw, &locator) != nil {
		return false
	}
	if strings.HasPrefix(strings.ToLower(locator.Type), "audio/") {
		return true
	}
	for _, fragment := range locator.Locations.Fragments {
		if strings.HasPrefix(fragment, "t=") {
			return true
		}
	}
	return false
}

// invalidateStorytellerPosition drops what carries a book's place: its record and
// Storyteller's list, which the shelves read it from. Not the audiobook's track
// list, which a place does not change.
func (s *Server) invalidateStorytellerPosition(sourceItemID string) {
	s.cache.Invalidate(storytellerWorkKey(sourceItemID))
	s.cache.Invalidate("reading:storyteller:books")
}

// invalidateStorytellerWork drops what the hub holds of one Storyteller book: its
// record, and the audiobook track list read from the disk on its account.
func (s *Server) invalidateStorytellerWork(sourceItemID string) {
	s.cache.Invalidate(storytellerWorkKey(sourceItemID))
	s.cache.Invalidate(audioPlanKey(sourceItemID))
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
