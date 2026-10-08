package api

// The read-along edition without its audio. An aligned EPUB is mostly sound
// (293 MB, of which 0.96 MB is text and SMIL), and an app that streams the
// narration from the hub's own tracks wants the words first. This serves the same
// edition with its audio entries left out: every other entry as it was, copied
// across without being unpacked, the audio never read.
//
// It is the reading copy of reading_epub_copy.go with the audio taken out in the
// same pass, so the words get the same text size and columns the ebook does, and
// its SMIL says what the hub reads from it: a sentence past the end of its audio
// has no length (readingdomain's epub_narration.go), which both apps leave out,
// where they refuse one that ends before it begins.
// The result is small, so it is kept under the edition's path, size and time, and
// served from there with everything a file route offers (Range, HEAD, conditional
// requests, a strong validator).
//
// The whole edition, its audio inside (`?format=readaloud`), is the same copy with
// the audio kept, for an app the hub cannot stream a book to. Storyteller's file
// route hashes the whole file before the first byte of every answer, a Range or a
// HEAD among them: measured 21.6 s for Dune's 1.2 GB edition and 11.6 s for its
// first kilobyte, so a download that resumed or was checked waited that long each
// time. The hub plans its copy once per path, size and time (9 s for the largest,
// 1.6 GB) and answers at once from then on.

import (
	"context"
	"errors"
	"log/slog"
	"net/http"

	readingdomain "ayaneohub/internal/reading"
)

var slimUnavailableMessages = map[string]string{
	audioReasonUnmapped:   "The hub has no access to this book's read-along edition.",
	audioReasonMissing:    "This book's read-along edition is missing on the server.",
	audioReasonLayout:     "This book's read-along edition is too large for the hub to prepare.",
	alignReasonUnreadable: "The hub cannot read this book's read-along edition.",
}

// serveSlimReadaloud answers `?format=readaloud&audio=omit`. ctx is the budget
// for finding the edition and, when it has not been done for this file, reading
// its text.
func (s *Server) serveSlimReadaloud(w http.ResponseWriter, r *http.Request, ctx context.Context, bookID int64, byteRange string) {
	record, _, err := s.storytellerBookRecord(ctx, bookID)
	if err != nil {
		writeStorytellerError(w, r, err)
		return
	}
	book := s.reconcileStorytellerBook(*record)
	if !book.Readaloud.Available() {
		writeError(w, r, http.StatusNotFound, Error{Code: CodeNotFound, Message: "this work has no available EPUB edition"})
		return
	}
	file, reason := s.openReadaloudEdition(book)
	if reason != "" {
		s.writeSlimUnavailable(w, r, book.ID, reason)
		return
	}
	defer file.Close()

	copied, err := s.epubCopyOf(ctx, file, readingdomain.CopyOptions{OmitAudio: true, Restyle: true, MendNarration: true}, "slim", book.ID)
	if err != nil {
		switch {
		case errors.Is(err, readingdomain.ErrCopyTooLarge):
			s.writeSlimUnavailable(w, r, book.ID, audioReasonLayout)
		case ctx.Err() != nil || errors.Is(err, context.DeadlineExceeded) || errors.Is(err, context.Canceled):
			writeEditionNotReady(w, r)
		default:
			s.writeSlimUnavailable(w, r, book.ID, alignReasonUnreadable)
		}
		return
	}
	s.serveEPUBCopy(w, r, copied, file, byteRange)
}

// writeEditionNotReady: the edition is still being read, and a retry soon finds
// the copy made, since the planning goes on for everyone after the request gives up.
func writeEditionNotReady(w http.ResponseWriter, r *http.Request) {
	writeError(w, r, http.StatusServiceUnavailable, Error{Code: CodeUpstreamDown, Message: "The hub could not read this book's read-along edition in time", Retryable: true})
}

// serveWholeReadaloud answers `?format=readaloud` from the reading copy of the
// whole edition on this PC, its audio kept and its SMIL mended as the slim
// edition's is. It reports whether it answered; when it did not (the edition is
// not mapped or not there, the copy would hold too much), nothing has been
// written and Storyteller's file goes through as it always did. The reason is
// logged, and names no path.
func (s *Server) serveWholeReadaloud(w http.ResponseWriter, r *http.Request, ctx context.Context, bookID int64, byteRange string) bool {
	unavailable := func(reason string) bool {
		slog.Warn("read-along reading copy unavailable, passing Storyteller's file through", "book", bookID, "reason", reason, "requestId", RequestIDFrom(r.Context()))
		return false
	}
	record, _, err := s.storytellerBookRecord(ctx, bookID)
	if err != nil {
		return unavailable("no_record")
	}
	book := s.reconcileStorytellerBook(*record)
	if !book.Readaloud.Available() {
		return unavailable("no_edition")
	}
	file, reason := s.openReadaloudEdition(book)
	if reason != "" {
		return unavailable(reason)
	}
	defer file.Close()
	copied, err := s.epubCopyOf(ctx, file, readingdomain.CopyOptions{Restyle: true, MendNarration: true}, "readaloud", book.ID)
	switch {
	case err == nil:
		s.serveEPUBCopy(w, r, copied, file, byteRange)
		return true
	case errors.Is(err, readingdomain.ErrCopyTooLarge):
		return unavailable(audioReasonLayout)
	case ctx.Err() != nil || errors.Is(err, context.DeadlineExceeded) || errors.Is(err, context.Canceled):
		// Storyteller would take as long again before its first byte; the copy is
		// still being made, and the retry finds it.
		writeEditionNotReady(w, r)
		return true
	}
	return unavailable(alignReasonUnreadable)
}

func (s *Server) writeSlimUnavailable(w http.ResponseWriter, r *http.Request, bookID int64, reason string) {
	// The book and the reason; never a path.
	slog.Info("read-along edition cannot be served without its audio", "book", bookID, "reason", reason, "requestId", RequestIDFrom(r.Context()))
	writeError(w, r, http.StatusConflict, Error{Code: codeAudioNotStreamable, Reason: reason, Message: slimUnavailableMessages[reason]})
}
