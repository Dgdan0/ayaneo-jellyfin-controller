package api

// Start over (#60): one route that takes a book back to not started.
//
// Mark read and Mark unread only ever changed a device's own completion state and the hub's
// "you" record. The place itself lives in Storyteller (one place for the ebook, the audiobook and
// the read-along of a title), in Kavita (a comic's pages) and in each device's own checkpoint,
// outbox and downloaded copy, and nothing touched it, so a book "marked unread" was still at its
// percentage, still in Continue reading and still "on #1" in its series.
//
// This route, for the whole work (every Storyteller book of the title, Kavita's series):
//
//   - forgets the place in every format: Kavita is told to mark the series unread (its own
//     mark-unread); Storyteller cannot delete a place, so the hub records a stamp and every reader
//     of a place treats one older than it as gone (reading_reset_store.go);
//   - takes away this profile's finished month, and nothing else of "you": the rating, the
//     read count and the shelves stay (the app keeps its bookmarks and lists itself);
//   - answers the stamp, which every position route and the work's own page repeat as resetAt,
//     so a device drops what it kept of the place, and a write made from the place that went away
//     is refused with its own code rather than the "choose which position" conflict;
//   - drops what the hub held of the books' places and of the shelves built from them.
//
// Nothing is ever written to Storyteller: its place stays as it was and is not read.

import (
	"context"
	"log/slog"
	"net/http"
	"slices"
	"strconv"
	"time"

	"ayaneohub/internal/adapters/storyteller"
	readingdomain "ayaneohub/internal/reading"
)

const (
	actionStartOver = "start_over"
	// codeReadingPositionReset is what a write is refused with when the place it was made from has
	// been started over: the app drops its place and reads the book again, and asks nobody.
	codeReadingPositionReset = "reading_position_reset"
)

// ReadingStartOverResponse answers POST /v1/reading/works/{workId}/start-over. ResetAt is the
// stamp (the hub's milliseconds) a device compares with what it has seen; You is what this
// profile has of the book now, null when nothing is left to say.
type ReadingStartOverResponse struct {
	OK      bool        `json:"ok"`
	Action  string      `json:"action"`
	WorkID  string      `json:"workId"`
	ResetAt int64       `json:"resetAt"`
	You     *ReadingYou `json:"you"`
}

func writePositionReset(w http.ResponseWriter, r *http.Request) {
	writeError(w, r, http.StatusConflict, Error{
		Code:    codeReadingPositionReset,
		Message: "This book was started over on another device. Your old place is gone.",
	})
}

func (s *Server) handleReadingStartOver(w http.ResponseWriter, r *http.Request) {
	if !s.requireReading(w, r) {
		return
	}
	workID := r.PathValue("workId")
	if !validReadingWorkID(workID) {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "invalid reading work id"})
		return
	}
	binding, found := s.readingCatalog.Resolve(workID)
	if !found {
		writeError(w, r, http.StatusNotFound, Error{Code: CodeNotFound, Message: "reading work not found"})
		return
	}
	profile, ok := s.readingProfile(r)
	if !ok {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "bad Jellyfin user id"})
		return
	}
	ctx, cancel := timeoutFor(r, s.cfg.Server.RequestTimeout.OrDefault(25*time.Second))
	defer cancel()

	storytellerIDs, kavitaIDs := s.startOverSources(ctx, binding.Sources)
	if len(storytellerIDs) == 0 && len(kavitaIDs) == 0 {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "this work has no place to start over"})
		return
	}

	// The stamp is after the hub's clock and after every place held now, so that a place stamped by
	// a phone whose clock runs ahead is older than it, and so is every place the hub writes next.
	at := s.now().UnixMilli()
	for _, id := range storytellerIDs {
		raw, err := s.rawStorytellerPosition(ctx, id)
		if err != nil {
			writeUpstreamError(w, r, "storyteller", err)
			return
		}
		if raw != nil && raw.Timestamp >= at {
			at = raw.Timestamp + 1
		}
	}
	// Kavita first: if it refuses, nothing is recorded and the person can try again.
	if len(kavitaIDs) > 0 && s.kavita != nil {
		for _, id := range kavitaIDs {
			if err := s.kavita.MarkSeriesUnread(ctx, id); err != nil {
				writeUpstreamError(w, r, "kavita", err)
				return
			}
		}
	}
	keys := make([]string, 0, len(storytellerIDs)+len(kavitaIDs))
	for _, id := range storytellerIDs {
		keys = append(keys, resetSourceKey("storyteller", strconv.FormatInt(id, 10)))
	}
	for _, id := range kavitaIDs {
		keys = append(keys, resetSourceKey("kavita", strconv.Itoa(id)))
	}
	if err := s.readingResets.record(workID, at, keys); err != nil {
		slog.Error("start over not recorded", "err", err)
		writeError(w, r, http.StatusInternalServerError, Error{Code: CodeInternal, Message: "starting the book over could not be saved"})
		return
	}
	at = s.settleStartOver(ctx, workID, at, storytellerIDs, keys)

	record, edit, err := s.readingYou.update(profile, workID, func(_ *youRecord, edit *youEdit) {
		edit.Finished = &editString{Cleared: true}
		// A status chosen as finished goes with the finish it was; one that says where the person is with the book (want,
		// reading, not reading) stays, as it says nothing of the place (#63).
		if edit.Status != nil && edit.Status.Value == statusFinished {
			edit.Status = nil
		}
	})
	if err != nil {
		slog.Error("reading data not saved", "err", err)
		writeError(w, r, http.StatusInternalServerError, Error{Code: CodeInternal, Message: "your reading data could not be saved"})
		return
	}
	// What the hub holds of the places, of the shelves and the series built from them.
	s.invalidateReadingCatalog()
	w.Header().Add("Vary", jellyfinUserHeader)
	writeJSON(w, http.StatusOK, ReadingStartOverResponse{
		OK: true, Action: actionStartOver, WorkID: workID, ResetAt: at, You: mergeYou(record, edit),
	})
}

// settleStartOver is the second look: a write that was already on its way when the stamp was kept
// may have landed a place stamped after it, and a place stamped by Storyteller's own app with a clock
// ahead may have been written since the first look. Under each book's turn, the stamp is moved to be
// after what is held now; a device that saw the earlier one is refused once more and reads it again.
func (s *Server) settleStartOver(ctx context.Context, workID string, at int64, ids []int64, keys []string) int64 {
	settled := at
	for _, id := range ids {
		func() {
			unlock := lockReadingCheckpoint("storyteller", strconv.FormatInt(id, 10))
			defer unlock()
			if raw, err := s.rawStorytellerPosition(ctx, id); err == nil && raw != nil && raw.Timestamp >= settled {
				settled = raw.Timestamp + 1
			}
		}()
	}
	if settled > at {
		if err := s.readingResets.record(workID, settled, keys); err != nil {
			slog.Warn("start over: the later stamp was not kept", "err", err)
			return at
		}
	}
	return settled
}

// startOverSources are the Storyteller books and Kavita series a work is made of: those it is bound
// to, and the other books of the same title in Storyteller (the audiobook beside the ebook, which
// the work shows as one book and so carries one place).
func (s *Server) startOverSources(ctx context.Context, sources []readingdomain.SourceRef) ([]int64, []int) {
	var storytellerIDs []int64
	var kavitaIDs []int
	for _, source := range sources {
		switch source.Source {
		case "storyteller":
			if id, err := strconv.ParseInt(source.SourceID, 10, 64); err == nil && id > 0 && s.storyteller != nil && !slices.Contains(storytellerIDs, id) {
				storytellerIDs = append(storytellerIDs, id)
			}
		case "kavita":
			if id, err := strconv.Atoi(source.SourceID); err == nil && id > 0 && s.kavita != nil && !slices.Contains(kavitaIDs, id) {
				kavitaIDs = append(kavitaIDs, id)
			}
		}
	}
	if len(storytellerIDs) > 0 {
		books, _, err := s.storytellerBooks(ctx)
		if err == nil {
			var bases []storyteller.Book
			for _, book := range books {
				if slices.Contains(storytellerIDs, book.ID) {
					bases = append(bases, book)
				}
			}
			for _, candidate := range books {
				if !slices.Contains(storytellerIDs, candidate.ID) && anyStorytellerEditionMatch(candidate, bases) {
					storytellerIDs = append(storytellerIDs, candidate.ID)
				}
			}
		}
	}
	slices.Sort(storytellerIDs)
	slices.Sort(kavitaIDs)
	return storytellerIDs, kavitaIDs
}

// hideResetPlaces is a Storyteller list with the place taken off every book that was started over
// since the place was written. The list is shared with the cache: it is copied before it is changed,
// and the list itself is returned when nothing is hidden.
func (s *Server) hideResetPlaces(books []storyteller.Book) []storyteller.Book {
	if !s.readingResets.any() {
		return books
	}
	var shown []storyteller.Book
	for i, book := range books {
		if book.Position == nil || !s.readingResets.gone("storyteller", strconv.FormatInt(book.ID, 10), book.Position.Timestamp) {
			continue
		}
		if shown == nil {
			shown = append([]storyteller.Book(nil), books...)
		}
		shown[i].Position = nil
	}
	if shown == nil {
		return books
	}
	return shown
}

// hideResetPlace is hideResetPlaces for one book's record.
func (s *Server) hideResetPlace(book *storyteller.Book) storyteller.Book {
	shown := *book
	if shown.Position != nil && s.readingResets.gone("storyteller", strconv.FormatInt(shown.ID, 10), shown.Position.Timestamp) {
		shown.Position = nil
	}
	return shown
}

// storytellerPlace is the account's place in a book twice: as it counts (nil when the book was
// started over since it was written) and as Storyteller holds it, which a write is stamped after.
func (s *Server) storytellerPlace(ctx context.Context, bookID int64) (live, raw *storyteller.PositionRecord, err error) {
	raw, err = s.rawStorytellerPosition(ctx, bookID)
	if err != nil {
		return nil, nil, err
	}
	if raw != nil && s.readingResets.gone("storyteller", strconv.FormatInt(bookID, 10), raw.Timestamp) {
		return nil, raw, nil
	}
	return raw, raw, nil
}

// staleAfterReset says a write was made from a place that has since been started over: it carries
// the reset it last saw and that is older than the hub's, or it carries none (an older app) and its
// base is exactly the place that went away. A write from nothing, or from a place written after the
// reset, is not stale, and is judged as it always was.
func (s *Server) staleAfterReset(source, id string, seen *int64, raw *storyteller.PositionRecord, baseIsRaw func() bool) bool {
	at := s.readingResets.source(source, id)
	if at == 0 {
		return false
	}
	if seen != nil {
		return *seen < at
	}
	return raw != nil && raw.Timestamp < at && baseIsRaw != nil && baseIsRaw()
}

// stampAfterReset is nextPositionStamp, never earlier than the reset that keeps the book's older places gone.
func (s *Server) stampAfterReset(bookID int64, raw *storyteller.PositionRecord) int64 {
	stamp := s.nextPositionStamp(raw)
	if at := s.readingResets.source("storyteller", strconv.FormatInt(bookID, 10)); stamp < at {
		stamp = at
	}
	return stamp
}
