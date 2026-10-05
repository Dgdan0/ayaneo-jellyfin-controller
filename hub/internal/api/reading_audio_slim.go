package api

// The read-along edition without its audio. An aligned EPUB is mostly sound
// (293 MB, of which 0.96 MB is text and SMIL), and an app that streams the
// narration from the hub's own tracks wants the words first. This serves the same
// edition with its audio entries left out: every other entry as it was, copied
// across without being unpacked, the audio never read. Storyteller's own file
// route, which serves the whole edition, is untouched.
//
// The result is small, so it is kept in memory under the edition's path, size and
// time, and served from there with everything a file route offers (Range, HEAD,
// conditional requests, a strong validator).

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"strconv"

	"ayaneohub/internal/cache"
	readingdomain "ayaneohub/internal/reading"
)

// What the hub will hold of one edition's text and SMIL. The edition measured
// is 0.96 MB; an illustrated one may be many times that, and an edition whose
// text alone is more than this is served whole (the app falls back to it).
var maxSlimEPUBBytes = int64(64 << 20)

var errSlimTooLarge = errors.New("the edition's text is more than the hub will hold")

// slimEdition is a read-along edition without its audio.
type slimEdition struct {
	data []byte
	// hash is the hex SHA-256 of data.
	hash string
}

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
		writeUpstreamError(w, r, "storyteller", err)
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

	slim, _, err := cache.Fetch(ctx, s.cache, slimKey(file), cache.ReadingSlimEPUB,
		func(fetchCtx context.Context) (*slimEdition, error) {
			// Whoever asks first builds it for everyone asking while it is built.
			buildCtx, cancel := context.WithTimeout(context.WithoutCancel(fetchCtx), audioPlanTimeout)
			defer cancel()
			return buildSlimEPUB(buildCtx, file)
		})
	if err != nil {
		switch {
		case errors.Is(err, errSlimTooLarge):
			s.writeSlimUnavailable(w, r, book.ID, audioReasonLayout)
		case ctx.Err() != nil || errors.Is(err, context.DeadlineExceeded) || errors.Is(err, context.Canceled):
			writeError(w, r, http.StatusServiceUnavailable, Error{Code: CodeUpstreamDown, Message: "The hub could not read this book's read-along edition in time", Retryable: true})
		default:
			s.writeSlimUnavailable(w, r, book.ID, alignReasonUnreadable)
		}
		return
	}

	header := w.Header()
	header.Set("Content-Type", "application/epub+zip")
	// A validator of the bytes served: the same text is the same tag, whatever
	// the audio it was cut from.
	header.Set("ETag", `"`+slim.hash[:32]+`"`)
	header.Set("Cache-Control", "private, no-store")
	header.Set("X-Content-Type-Options", "nosniff")
	header.Set("X-Reading-Content-Hash", "sha256:"+slim.hash)
	if byteRange != "" {
		// What was checked is what the file server reads.
		r.Header.Set("Range", byteRange)
	}
	http.ServeContent(w, r, "", file.ModTime, bytes.NewReader(slim.data))
}

func (s *Server) writeSlimUnavailable(w http.ResponseWriter, r *http.Request, bookID int64, reason string) {
	// The book and the reason; never a path.
	slog.Info("read-along edition cannot be served without its audio", "book", bookID, "reason", reason, "requestId", RequestIDFrom(r.Context()))
	writeError(w, r, http.StatusConflict, Error{Code: codeAudioNotStreamable, Reason: reason, Message: slimUnavailableMessages[reason]})
}

// slimKey names an edition as it was when it was read.
func slimKey(file readingdomain.MediaFile) string {
	return "slim-epub:" + file.Path + "\x00" + strconv.FormatInt(file.Size, 10) + "\x00" + strconv.FormatInt(file.ModTime.UnixNano(), 10)
}

func buildSlimEPUB(ctx context.Context, file readingdomain.MediaFile) (*slimEdition, error) {
	out := &cappedBuffer{limit: int(maxSlimEPUBBytes)}
	if _, err := readingdomain.WriteSlimEPUB(out, contextReaderAt{ReaderAt: file, ctx: ctx}, file.Size); err != nil {
		if errors.Is(err, io.ErrShortWrite) {
			return nil, errSlimTooLarge
		}
		return nil, err
	}
	sum := sha256.Sum256(out.Bytes())
	return &slimEdition{data: out.Bytes(), hash: hex.EncodeToString(sum[:])}, nil
}
