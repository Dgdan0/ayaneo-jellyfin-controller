package api

// What readers at large think of a book, and what genre it is (#39), for the book page:
// from Hardcover when the hub has a key for it, else from the average rating in the
// owner's Goodreads export. A lookup never fails a page. It is bounded (a first view
// waits two seconds for it, and a slower one finishes behind and is there next time),
// it is kept for days, and after a failure Hardcover is left alone for a few minutes.

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"log/slog"
	"math"
	"net/http"
	"sort"
	"strings"
	"time"

	"ayaneohub/internal/adapters/hardcover"
	"ayaneohub/internal/adapters/storyteller"
	"ayaneohub/internal/cache"
	readingdomain "ayaneohub/internal/reading"
)

const (
	// communityBudget is how long a page waits for Hardcover on a book it has not
	// asked about lately.
	communityBudget = 2 * time.Second
	// communityLookupLimit bounds the lookup itself, which carries on after the page
	// has stopped waiting.
	communityLookupLimit = 15 * time.Second
	// hardcoverPause is how long Hardcover is left alone after it failed or limited the
	// hub: its free keys are limited to 60 requests a minute.
	hardcoverPause = 5 * time.Minute
	// A book has few ISBNs worth asking about.
	communityISBNs = 3
	// The genre line is quiet: Hardcover's genres are added to the work's own until
	// there are this many.
	genreLine = 6
)

// communityInput is what is asked of Hardcover about a work.
type communityInput struct {
	isbns   []string // ISBN-13s
	title   string
	authors []string
}

func (in communityInput) empty() bool { return len(in.isbns) == 0 && in.title == "" }

// communityResult is Hardcover's answer, kept whether or not it had the book, so that a
// book it does not know is not asked about again for days.
type communityResult struct {
	Found bool
	Book  hardcover.Book
}

func (in communityInput) key() string {
	authors := make([]string, 0, len(in.authors))
	for _, author := range in.authors {
		authors = append(authors, authorKey(author))
	}
	sort.Strings(authors)
	full, _ := titleKeys(in.title)
	sum := sha256.Sum256([]byte(strings.Join(in.isbns, ",") + "|" + full + "|" + strings.Join(authors, ",")))
	return "reading:hardcover:" + hex.EncodeToString(sum[:12])
}

func (s *Server) hardcoverPaused() bool { return s.now().UnixNano() < s.hardcoverUntil.Load() }

func (s *Server) pauseHardcover() { s.hardcoverUntil.Store(s.now().Add(hardcoverPause).UnixNano()) }

// lookupHardcover asks Hardcover for the book: by ISBN first, else by title with an
// author that is one of the work's. Nothing found is a result, not an error.
func (s *Server) lookupHardcover(ctx context.Context, in communityInput) (*communityResult, error) {
	for i, isbn := range in.isbns {
		if i >= communityISBNs {
			break
		}
		book, err := s.hardcover.ByISBN(ctx, isbn, readingdomain.ISBN10(isbn))
		if err != nil {
			return nil, err
		}
		if book != nil {
			return &communityResult{Found: true, Book: *book}, nil
		}
	}
	if in.title == "" || len(in.authors) == 0 {
		return &communityResult{}, nil
	}
	clean := readingdomain.GoodreadsTitle(withoutSeriesNote(in.title))
	titles := []string{clean}
	if main := readingdomain.MainTitle(clean); main != "" && main != clean {
		titles = append(titles, main)
	}
	for _, title := range titles {
		books, err := s.hardcover.ByTitle(ctx, title)
		if err != nil {
			return nil, err
		}
		// A book of that title by someone else is another book. The books come in several
		// spellings of the title, so one written as asked (capitals aside) wins over one
		// with "The" added or taken away, however many more ratings that has.
		var other *hardcover.Book
		for i, book := range books {
			if !authorsOverlap(in.authors, authorKeys(book.Authors)) {
				continue
			}
			if strings.EqualFold(book.Title, title) {
				return &communityResult{Found: true, Book: book}, nil
			}
			if other == nil {
				other = &books[i]
			}
		}
		if other != nil {
			return &communityResult{Found: true, Book: *other}, nil
		}
	}
	return &communityResult{}, nil
}

func authorKeys(names []string) []string {
	keys := make([]string, 0, len(names))
	for _, name := range names {
		if key := authorKey(name); key != "" {
			keys = append(keys, key)
		}
	}
	return keys
}

// communityFor is Hardcover's book for a work, or nil: no key, no answer in time, a
// failure, or a book it does not have.
func (s *Server) communityFor(ctx context.Context, in communityInput) *hardcover.Book {
	if s.hardcover == nil || in.empty() {
		return nil
	}
	type answer struct {
		result *communityResult
		err    error
	}
	done := make(chan answer, 1)
	go func() {
		// The lookup is shared by whoever asks while it runs and is kept for the next, so
		// one page giving up must not end it.
		lookup, cancel := context.WithTimeout(context.WithoutCancel(ctx), communityLookupLimit)
		defer cancel()
		result, _, err := cache.Fetch(lookup, s.cache, in.key(), cache.ReadingCommunity, func(fetchCtx context.Context) (*communityResult, error) {
			if s.hardcoverPaused() {
				return nil, errHardcoverPaused
			}
			result, err := s.lookupHardcover(fetchCtx, in)
			if err != nil {
				s.pauseHardcover()
				// The service's words and never the book asked about.
				slog.Warn("hardcover lookup failed; leaving it alone for a few minutes", "err", err)
				return nil, err
			}
			return result, nil
		})
		done <- answer{result, err}
	}()
	wait := s.communityBudget
	if wait <= 0 {
		wait = communityBudget
	}
	select {
	case got := <-done:
		if got.err != nil || got.result == nil || !got.result.Found {
			return nil
		}
		book := got.result.Book
		return &book
	case <-time.After(wait):
	case <-ctx.Done():
	}
	return nil
}

type pausedError struct{}

func (pausedError) Error() string { return "hardcover is being left alone for a few minutes" }

var errHardcoverPaused error = pausedError{}

// communityOf is the rating shown for a work: Hardcover's, else the average in the
// profile's Goodreads export.
func communityOf(book *hardcover.Book, record *youRecord) *ReadingCommunity {
	if book != nil && book.Rating > 0 {
		return &ReadingCommunity{Rating: roundRating(book.Rating), Count: book.RatingsCount, Source: "hardcover"}
	}
	if record != nil && record.Average > 0 {
		return &ReadingCommunity{Rating: roundRating(record.Average), Source: "goodreads"}
	}
	return nil
}

func roundRating(rating float64) float64 { return math.Round(rating*100) / 100 }

// mergeGenres adds a source's genres to a work's own, none twice, until there are
// genreLine of them. The work's own are never cut.
func mergeGenres(own, extra []string) []string {
	out := append([]string{}, own...)
	seen := map[string]bool{}
	for _, genre := range out {
		seen[strings.ToLower(strings.TrimSpace(genre))] = true
	}
	for _, genre := range extra {
		genre = strings.TrimSpace(genre)
		key := strings.ToLower(genre)
		if genre == "" || seen[key] || len(out) >= genreLine {
			continue
		}
		seen[key] = true
		out = append(out, genre)
	}
	return out
}

// addPersonalFields puts what the book page shows beyond the catalog on a work: this
// profile's "you", and the community's rating and genres. Both are additions; a work is
// served whole without them.
func (s *Server) addPersonalFields(ctx context.Context, r *http.Request, work *ReadingWork) {
	var record *youRecord
	if profile, ok := s.readingProfile(r); ok {
		var edit *youEdit
		record, edit = s.readingYou.view(profile, work.ID)
		work.You = mergeYou(record, edit)
	}
	if work.EntityType == "collection" || (work.Kind != "book" && work.Kind != "audiobook") {
		return
	}
	book := s.communityFor(ctx, communityInput{isbns: work.isbns, title: work.Title, authors: work.Authors})
	work.Community = communityOf(book, record)
	if book != nil {
		work.Genres = mergeGenres(work.Genres, book.Genres)
	}
}

// storytellerISBNs are the ISBN-13s a Storyteller book carries: identifiers that say they
// are ISBNs (or an Amazon number, which for a book is its ISBN-10), and any that say
// nothing, when the number is one by its check digit.
func storytellerISBNs(book storyteller.Book) []string {
	var out []string
	for _, identifier := range book.Identifiers {
		value := identifier.Value
		if value == "" {
			value = identifier.Identifier
		}
		kind := strings.ToLower(strings.TrimSpace(identifier.Type))
		if kind == "" {
			kind = strings.ToLower(strings.TrimSpace(identifier.Scheme))
		}
		if kind != "" && !strings.Contains(kind, "isbn") && !strings.Contains(kind, "amazon") {
			continue
		}
		if isbn := readingdomain.ISBN13(value); isbn != "" {
			out = appendUnique(out, isbn)
		}
	}
	return out
}
