package api

// The read-along edition without its audio. An aligned EPUB is mostly sound
// (293 MB, of which 0.96 MB is text and SMIL), and an app that streams the
// narration from the hub's own tracks wants the words first. This serves the same
// edition with its audio entries left out: every other entry as it was, copied
// across without being unpacked, the audio never read. Storyteller's own file
// route, which serves the whole edition, is untouched.
//
// It is the reading copy of reading_epub_copy.go with the audio taken out in the
// same pass, so the words get the same text size and columns the ebook does.
// The result is small, so it is kept under the edition's path, size and time, and
// served from there with everything a file route offers (Range, HEAD, conditional
// requests, a strong validator).

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

	copied, err := s.epubCopyOf(ctx, file, readingdomain.CopyOptions{OmitAudio: true, Restyle: true}, "slim", book.ID)
	if err != nil {
		switch {
		case errors.Is(err, readingdomain.ErrCopyTooLarge):
			s.writeSlimUnavailable(w, r, book.ID, audioReasonLayout)
		case ctx.Err() != nil || errors.Is(err, context.DeadlineExceeded) || errors.Is(err, context.Canceled):
			writeError(w, r, http.StatusServiceUnavailable, Error{Code: CodeUpstreamDown, Message: "The hub could not read this book's read-along edition in time", Retryable: true})
		default:
			s.writeSlimUnavailable(w, r, book.ID, alignReasonUnreadable)
		}
		return
	}
	s.serveEPUBCopy(w, r, copied, file, byteRange)
}

func (s *Server) writeSlimUnavailable(w http.ResponseWriter, r *http.Request, bookID int64, reason string) {
	// The book and the reason; never a path.
	slog.Info("read-along edition cannot be served without its audio", "book", bookID, "reason", reason, "requestId", RequestIDFrom(r.Context()))
	writeError(w, r, http.StatusConflict, Error{Code: codeAudioNotStreamable, Reason: reason, Message: slimUnavailableMessages[reason]})
}
