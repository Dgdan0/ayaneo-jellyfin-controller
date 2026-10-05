package api

import (
	"context"
	"io"
	"net/http"
	"regexp"
	"strconv"
	"time"
)

var audioRevisionPattern = regexp.MustCompile(`^[0-9a-f]{12}$`)

// handleReadingAudioTrack serves one track's bytes (the {n} of the manifest)
// from the file itself, with Range, so an app starts playing at once and seeks
// without downloading what lies before.
//
// The file is opened once, by the media mapping's read-only twin, and served
// from that handle: http.ServeContent answers 200, 206, 304 and 416, `If-Range`
// and `HEAD`, with a strong ETag. The one range rule is the EPUB route's.
//
// `rev` is required and must be the manifest's revision. It is checked against
// the held track list and then against the file itself, so a rescan or a swapped
// file cannot play under an old index: 412 audio_changed, and the held list is
// dropped so the next manifest says what is on disk.
func (s *Server) handleReadingAudioTrack(w http.ResponseWriter, r *http.Request) {
	if !s.requireReading(w, r) {
		return
	}
	index, ok := trackIndex(r.PathValue("n"))
	if !ok {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "invalid track number"})
		return
	}
	revision := r.URL.Query().Get("rev")
	if !audioRevisionPattern.MatchString(revision) {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "rev must be the revision of the audiobook's manifest"})
		return
	}
	byteRange, _, ok := requireSingleByteRange(w, r)
	if !ok {
		return
	}
	if byteRange != "" {
		// What was checked is what the file server reads.
		r.Header.Set("Range", byteRange)
	}

	// Finding the book and its track list is metadata, under the usual budget.
	// The transfer below runs under the request's own life: when the app goes,
	// it goes.
	ctx, cancel := timeoutFor(r, s.cfg.Server.RequestTimeout.OrDefault(25*time.Second))
	book, _, ok := s.resolveStorytellerAudiobook(w, r, ctx)
	if !ok {
		cancel()
		return
	}
	plan, _, err := s.audioPlanFor(ctx, book)
	cancel()
	if err != nil {
		s.writeAudioPlanError(w, r, book.ID, err)
		return
	}
	if index >= len(plan.tracks) {
		writeError(w, r, http.StatusNotFound, Error{Code: CodeNotFound, Message: "no such track"})
		return
	}
	if revision != plan.revision {
		writeAudioChanged(w, r)
		return
	}
	track := plan.tracks[index]

	planKey := audioPlanKey(strconv.FormatInt(book.ID, 10))
	file, err := s.openMedia(s.cfg.Server.MediaRemovalRoots, "storyteller", track.remote)
	if err != nil {
		// The held list named a file that is no longer there, or no longer fit
		// to serve.
		s.cache.Invalidate(planKey)
		s.writeAudioPlanError(w, r, book.ID, audioFailureFor(err))
		return
	}
	defer file.Close()
	if file.Size != track.Bytes || file.ModTime.UnixNano() != track.modNano {
		s.cache.Invalidate(planKey)
		writeAudioChanged(w, r)
		return
	}

	out, err := streamUntilStalled(w, s.audioStall)
	if err != nil {
		writeError(w, r, http.StatusInternalServerError, Error{Code: CodeInternal, Message: "the track could not be prepared", Retryable: true})
		return
	}
	header := w.Header()
	header.Set("Content-Type", track.Mime)
	header.Set("ETag", track.ETag)
	header.Set("Accept-Ranges", "bytes")
	header.Set("Cache-Control", "private, max-age=3600")
	header.Set("X-Content-Type-Options", "nosniff")
	// No name: it would come out in a Content-Disposition or a sniffed type, and
	// the file's name stays on the hub.
	http.ServeContent(out, r, "", file.ModTime, contextReadSeeker{ReadSeeker: file, ctx: r.Context()})
}

func writeAudioChanged(w http.ResponseWriter, r *http.Request) {
	writeError(w, r, http.StatusPreconditionFailed, Error{Code: codeAudioChanged, Message: "This audiobook's files have changed. Load its track list again."})
}

// trackIndex is a track number as the manifest writes it: digits, no sign, no
// leading zero.
func trackIndex(text string) (int, bool) {
	n, err := strconv.Atoi(text)
	return n, err == nil && n >= 0 && strconv.Itoa(n) == text
}

// contextReadSeeker stops reading when the request ends. A client that has gone
// would otherwise be found out only by a failed write, which may not come for
// as long as the stall window; the next read ends the copy at once and the
// handle is let go.
type contextReadSeeker struct {
	io.ReadSeeker
	ctx context.Context
}

func (c contextReadSeeker) Read(p []byte) (int, error) {
	if err := c.ctx.Err(); err != nil {
		return 0, err
	}
	return c.ReadSeeker.Read(p)
}
