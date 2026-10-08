package api

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"strconv"
	"strings"

	"ayaneohub/internal/adapters/storyteller"
	"ayaneohub/internal/cache"
)

// Everything the hub keeps of one Storyteller book, and what it takes to trust it.
//
// Two facts come from two places and used to disagree (#50). Storyteller's book
// list (read every minute) is the truth of which books exist and what state their
// editions are in; a book's own record is the detail built from (an audiobook's
// manifest, its files) and was held for a day. When a read-along went from
// PROCESSING to ALIGNED the shelf said "read along" and the book's page, built from
// the day-old record, did not. And a book deleted and imported again keeps its old
// id in the catalog; Storyteller answers that id with a 500, which the hub reported
// as Storyteller being down, for ever.
//
// So this file is the one place that decides, for a bound book:
//
//   - whether it still exists (the list says so; nothing else does),
//   - whether its cached record still describes it (the stamp of its list entry is
//     the stamp the record was read against, and a record of a book being aligned is
//     kept for seconds, not a day).

// storytellerBooksKey is Storyteller's book list in the hub's cache.
const storytellerBooksKey = "reading:storyteller:books"

// errStorytellerBookGone says Storyteller's list, read now, does not have the book.
var errStorytellerBookGone = errors.New("storyteller: the book is no longer in the library")

// storytellerBooks is Storyteller's book list, every reader's way to it. Reading it
// is also when a record the hub holds is found to be out of date: the list is
// compared with what each held record was read against.
func (s *Server) storytellerBooks(ctx context.Context) ([]storyteller.Book, cache.Meta, error) {
	books, meta, err := cache.Fetch(ctx, s.cache, storytellerBooksKey, cache.LibraryPage, s.storyteller.Books)
	// A copy served because Storyteller is failing says nothing new, and dropping a
	// record then would leave nothing to serve while it is down.
	if err == nil && !meta.FromError {
		s.followStorytellerList(books)
	}
	return books, meta, err
}

// storytellerStamp is what in a list entry changes the record built from the same
// book: when the book was last changed, which editions it has and whether their
// files are there, and the read-along's identity, state and update time. Not how far
// an alignment has got (stage, progress, queue place): that moves every few seconds
// and no page shows it; a record of a book being aligned is short-lived anyway.
func storytellerStamp(book storyteller.Book) string {
	var stamp strings.Builder
	stamp.WriteString(book.UpdatedAt)
	if e := book.Ebook; e != nil {
		fmt.Fprintf(&stamp, "|e:%s:%t", e.UUID, e.Missing)
	}
	if a := book.Audiobook; a != nil {
		fmt.Fprintf(&stamp, "|a:%s:%t", a.UUID, a.Missing)
	}
	if r := book.Readaloud; r != nil {
		fmt.Fprintf(&stamp, "|r:%s:%s:%t:%s", r.UUID, strings.ToUpper(r.Status), r.Missing, r.UpdatedAt)
	}
	return stamp.String()
}

// followStorytellerList drops the record of every book whose list entry no longer
// matches the stamp the record was read against, or that the list no longer has.
// It reports whether it dropped any. The readaloud automation calls it with the
// list it reads itself; every other reader reaches it through storytellerBooks.
func (s *Server) followStorytellerList(books []storyteller.Book) bool {
	s.storytellerMu.Lock()
	defer s.storytellerMu.Unlock()
	if len(s.storytellerStamps) == 0 {
		return false
	}
	listed := make(map[int64]string, len(books))
	for _, book := range books {
		listed[book.ID] = storytellerStamp(book)
	}
	dropped := false
	for id, read := range s.storytellerStamps {
		if now, ok := listed[id]; ok && now == read {
			continue
		}
		delete(s.storytellerStamps, id)
		s.invalidateStorytellerWork(strconv.FormatInt(id, 10))
		dropped = true
	}
	return dropped
}

// rememberStorytellerStamp records what a record was read against. An empty stamp
// (read while the list could not be) is never equal to a real one, so the record is
// read once more as soon as the list is.
func (s *Server) rememberStorytellerStamp(id int64, stamp string) {
	s.storytellerMu.Lock()
	defer s.storytellerMu.Unlock()
	if s.storytellerStamps == nil {
		s.storytellerStamps = map[int64]string{}
	}
	s.storytellerStamps[id] = stamp
}

type storytellerState int

const (
	// The list could not say: it failed, is an old copy served because it failed, or
	// is empty (a library with nothing in it is more likely a Storyteller mid-start
	// than a library that was emptied, and unbinding every work on a guess is the
	// harm to avoid).
	storytellerUnknown storytellerState = iota
	// The list has the book.
	storytellerListed
	// The list was read and the book is not in it.
	storytellerGone
)

// storytellerStanding is what Storyteller's list makes of a book id, and the entry
// when it has one. When the book is gone the hub forgets it on the spot: its caches,
// and its binding in the catalog (the work stays, with its id and identities, so a
// saved link keeps working and a re-import rejoins it). Callers need only leave the
// book out.
func (s *Server) storytellerStanding(ctx context.Context, id int64) (storyteller.Book, storytellerState) {
	books, meta, err := s.storytellerBooks(ctx)
	if err != nil || meta.FromError || len(books) == 0 {
		return storyteller.Book{}, storytellerUnknown
	}
	for _, book := range books {
		if book.ID == id {
			return book, storytellerListed
		}
	}
	s.forgetStorytellerBook(id)
	return storyteller.Book{}, storytellerGone
}

func (s *Server) forgetStorytellerBook(id int64) {
	key := strconv.FormatInt(id, 10)
	s.storytellerMu.Lock()
	delete(s.storytellerStamps, id)
	s.storytellerMu.Unlock()
	s.invalidateStorytellerWork(key)
	removed, err := s.readingCatalog.Unbind("storyteller", key)
	if err != nil {
		slog.Warn("could not unbind a Storyteller book that is gone", "book", id, "error", err)
	} else if removed {
		slog.Info("unbound a Storyteller book that is gone", "book", id)
	}
}

// storytellerBookRecord is one Storyteller book from its own endpoint: the detail a
// page or an audio route is built from. It is errStorytellerBookGone for a book the
// list does not have, without asking Storyteller (whose 500 for such an id says
// nothing a list does not). Otherwise it is held a day (and cleared with everything
// else the hub knows of Storyteller) as long as the list's entry for the book is the
// one it was read against; a book whose read-along is still being made is held for
// seconds. A series or a shelf is built from the list endpoint, which is not the
// place to read an audiobook's folder and manifest from.
func (s *Server) storytellerBookRecord(ctx context.Context, id int64) (*storyteller.Book, cache.Meta, error) {
	entry, state := s.storytellerStanding(ctx, id)
	if state == storytellerGone {
		return nil, cache.Meta{}, errStorytellerBookGone
	}
	spec, stamp := cache.Metadata, ""
	if state == storytellerListed {
		stamp = storytellerStamp(entry)
		if activeReadaloud(entry) {
			spec = cache.ReadingBookActive
		}
	}
	return cache.Fetch(ctx, s.cache, storytellerWorkKey(strconv.FormatInt(id, 10)), spec, func(fetchCtx context.Context) (*storyteller.Book, error) {
		book, err := s.storyteller.Book(fetchCtx, id)
		if err == nil {
			s.rememberStorytellerStamp(id, stamp)
		}
		return book, err
	})
}

func storytellerWorkKey(sourceItemID string) string {
	return "reading:storyteller:work:" + sourceItemID
}

// writeStorytellerError answers a failed read of a Storyteller book: 404 for one
// that is gone, as an upstream failure otherwise.
func writeStorytellerError(w http.ResponseWriter, r *http.Request, err error) {
	if errors.Is(err, errStorytellerBookGone) {
		writeError(w, r, http.StatusNotFound, Error{Code: CodeNotFound, Service: "storyteller", Message: "this book is no longer in Storyteller"})
		return
	}
	writeUpstreamError(w, r, "storyteller", err)
}

// invalidateStorytellerPosition drops what carries a book's place: its record and
// Storyteller's list, which the shelves read it from. Not the audiobook's track
// list, which a place does not change.
func (s *Server) invalidateStorytellerPosition(sourceItemID string) {
	s.cache.Invalidate(storytellerWorkKey(sourceItemID))
	s.cache.Invalidate(storytellerBooksKey)
}

// invalidateStorytellerWork drops what the hub holds of one Storyteller book: its
// record, and the audiobook track list read from the disk on its account.
func (s *Server) invalidateStorytellerWork(sourceItemID string) {
	s.cache.Invalidate(storytellerWorkKey(sourceItemID))
	s.cache.Invalidate(audioPlanKey(sourceItemID))
}
