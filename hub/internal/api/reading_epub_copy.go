package api

// The reading copy of an EPUB (#41).
//
// Both readers hand the text size and "Two pages" straight to Readium CSS, which
// scales the root font size and applies two explicit columns only at 60em. A book
// whose stylesheet sets its sizes in `medium`, `small`, px or pt (A Game of
// Thrones: `p.* { font-size: medium }` 42 times) does not follow the root, and the
// Pocket (853dp), every iPhone and an iPad mini upright are narrower than 60em. So
// the hub serves each Storyteller EPUB as a reading copy: the same book with its
// absolute font sizes and line heights as rem (a line height left in px would no
// longer fit the text it is set for: the drop cap of A Game of Thrones, 80px on a
// 70px line, is clipped at a text size of 150%), one <style> that gives two
// columns from 30em, and the package's language on each document that names none
// (without a language the browsers do not hyphenate)
// (readingdomain.WriteReadingEPUB, which says what and why). Places are unchanged,
// so saved locators and the read-along alignment still match.
//
// Two routes serve it, both from the file on this PC (found through
// media_removal_roots, as the audio is): the ebook (`…/file`, `?format=ebook`) and the
// read-along edition without its audio (`?format=readaloud&audio=omit`,
// reading_audio_slim.go). The copy is planned once per path, size and modified time
// and kept; what is kept is small whatever the book weighs, because an entry that is
// only copied (a page of illustrations) is read from the file when it is sent.
// Everything a file route offers is here: Range, HEAD, conditional requests, a strong
// ETag of the bytes sent and `X-Reading-Content-Hash` of the same bytes.

import (
	"context"
	"encoding/hex"
	"errors"
	"log/slog"
	"net/http"
	"strconv"

	"ayaneohub/internal/cache"
	readingdomain "ayaneohub/internal/reading"
)

// What the hub will keep in memory of one copy: its headers, the stylesheets and
// documents it rewrote, and the small entries copied as they were. Measured over
// the 122 EPUBs of this library, the most any holds is 4.0 MB (Dark Age); Rhythm of
// War, which is 112 MB of illustrations, holds 1.4 MB, and its pictures are read from
// the file as they are sent. A copy that would hold more than this is not made, and
// the file is served as Storyteller has it.
var maxEPUBCopyBytes = int64(64 << 20)

// epubCopy is a planned copy, with the hash of the bytes it is made of.
type epubCopy struct {
	plan *readingdomain.EPUBCopy
	// hash is the hex SHA-256 of the copy.
	hash string
}

// epubCopyKey names a copy as its file was when it was read: a different size or
// time is a different book.
func epubCopyKey(kind string, file readingdomain.MediaFile) string {
	return "epub-copy:" + kind + ":" + file.Path + "\x00" + strconv.FormatInt(file.Size, 10) + "\x00" + strconv.FormatInt(file.ModTime.UnixNano(), 10)
}

// epubCopyOf plans the copy of an open EPUB, or finds it planned. ctx is the
// budget of the request, which stops waiting when it ends; the planning itself, which
// others may be waiting for, carries on within audioPlanTimeout. kind and book are for
// the log (never the path).
func (s *Server) epubCopyOf(ctx context.Context, file readingdomain.MediaFile, options readingdomain.CopyOptions, kind string, book int64) (*epubCopy, error) {
	options.MaxHeld = maxEPUBCopyBytes
	built, _, err := cache.Fetch(ctx, s.cache, epubCopyKey(kind, file), cache.ReadingEPUBCopy,
		func(fetchCtx context.Context) (*epubCopy, error) {
			// Whoever asks first builds it for everyone asking while it is built.
			buildCtx, cancel := context.WithTimeout(context.WithoutCancel(fetchCtx), audioPlanTimeout)
			defer cancel()
			plan, err := readingdomain.PlanReadingEPUB(contextReaderAt{ReaderAt: file, ctx: buildCtx}, file.Size, options)
			if err != nil {
				return nil, err
			}
			report := plan.Report
			slog.Info("built a reading copy", "kind", kind, "book", book, "bytes", plan.Size, "held", plan.Held(),
				"fontSizes", report.FontSizes, "lineHeights", report.LineHeights, "styled", report.Styled, "languages", report.Languages, "fontsDecoded", report.FontsDecoded, "edited", report.Edited,
				"omitted", len(report.Omitted), "left", len(report.Left), "fixedLayout", report.FixedLayout)
			return &epubCopy{plan: plan, hash: hex.EncodeToString(plan.SHA256[:])}, nil
		})
	return built, err
}

// serveEPUBCopy sends a copy from the file it was planned from.
func (s *Server) serveEPUBCopy(w http.ResponseWriter, r *http.Request, copied *epubCopy, file readingdomain.MediaFile, byteRange string) {
	// A book of illustrations is a hundred megabytes and the reader may be a phone on
	// a slow link: the server's write timeout (two minutes, for the whole response) is
	// replaced by one that only a stall can reach, as for a track. A reader that stops
	// reading is let go, and with it the file.
	out, err := streamUntilStalled(w, s.audioStall)
	if err != nil {
		writeError(w, r, http.StatusInternalServerError, Error{Code: CodeInternal, Message: "the book could not be prepared", Retryable: true})
		return
	}
	header := out.Header()
	header.Set("Content-Type", "application/epub+zip")
	// A validator of the bytes sent: the same book is the same tag, whatever the
	// file it was made from is called or where it is.
	header.Set("ETag", `"`+copied.hash[:32]+`"`)
	header.Set("Cache-Control", "private, no-store")
	header.Set("X-Content-Type-Options", "nosniff")
	header.Set("X-Reading-Content-Hash", "sha256:"+copied.hash)
	if byteRange != "" {
		// What was checked is what the file server reads.
		r.Header.Set("Range", byteRange)
	}
	http.ServeContent(out, r, "", file.ModTime, copied.plan.Reader(file))
}

// serveReadingCopy answers the ebook route (`…/file`, `?format=ebook`) from the
// reading copy of the book's file on this PC. It reports whether it answered; when
// it did not, nothing has been written and the caller passes Storyteller's file
// through exactly as it always did. The reason is logged, and names no path.
func (s *Server) serveReadingCopy(w http.ResponseWriter, r *http.Request, ctx context.Context, bookID int64, byteRange string) bool {
	unavailable := func(reason string) bool {
		slog.Warn("ebook reading copy unavailable, passing Storyteller's file through", "book", bookID, "reason", reason, "requestId", RequestIDFrom(r.Context()))
		return false
	}
	record, _, err := s.storytellerBookRecord(ctx, bookID)
	if err != nil {
		return unavailable("no_record")
	}
	book := s.reconcileStorytellerBook(*record)
	if book.Ebook == nil || book.Ebook.Missing || book.Ebook.Filepath == "" {
		return unavailable("no_path")
	}
	file, reason := s.openStorytellerEPUB(book.Ebook.Filepath)
	if reason != "" {
		return unavailable(reason)
	}
	defer file.Close()
	copied, err := s.epubCopyOf(ctx, file, readingdomain.CopyOptions{Restyle: true}, "ebook", book.ID)
	if err != nil {
		switch {
		case errors.Is(err, readingdomain.ErrCopyTooLarge):
			return unavailable("too_large")
		case errors.Is(err, readingdomain.ErrNotAnEPUB):
			return unavailable("not_an_epub")
		case ctx.Err() != nil || errors.Is(err, context.DeadlineExceeded) || errors.Is(err, context.Canceled):
			return unavailable("too_slow")
		}
		return unavailable("unreadable")
	}
	s.serveEPUBCopy(w, r, copied, file, byteRange)
	return true
}
