package api

// The books of a series, for the library's Series view as a fan of covers (#54): the ones you have, in their
// order, which you have read and which you are on, and the main numbered books you do not have, which the Pocket
// draws dimmed and counts in its bar as outlines. The ones you do not have come from Hardcover's series data,
// asked once for a series and kept for days, and left alone for a few minutes after it fails; without a key or
// without a match the fan is the books you have.

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"log/slog"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"ayaneohub/internal/adapters/hardcover"
	"ayaneohub/internal/adapters/storyteller"
	"ayaneohub/internal/cache"
)

const (
	// hardcoverSpacing is how long one series lookup follows another: Hardcover's free keys are limited to
	// 60 requests a minute, and a library page asks about up to sixty series the first time.
	defaultHardcoverSpacing = 1100 * time.Millisecond
	// seriesBudget is how long a library page waits for the series it has not asked about lately; the rest are
	// there the next time it is read.
	seriesBudget = 2 * time.Second
	// seriesNameLimit caps how many spellings of a series' name one lookup asks for.
	seriesNameLimit = 16
)

// ReadingSeriesBook is a book of a series: one the library has, or a main numbered one it does not. A series
// item lists them in the series' order, for the fan (the apps draw the cover, the number and, from State and
// Owned, the bar under it).
type ReadingSeriesBook struct {
	// Number is its place in the series: "3", or "2.5" for a novella; blank for a book with no place.
	Number string `json:"number,omitempty"`
	Title  string `json:"title"`
	// Cover is a hub image path, or blank for a book with no picture.
	Cover string `json:"cover,omitempty"`
	// Kind is "audiobook" for a book that is only an audiobook (a square cover), else "book".
	Kind  string `json:"kind"`
	Owned bool   `json:"owned"`
	// Released is false for a book announced and not out. Only books that are out are listed as missing.
	Released bool `json:"released"`
	// State is "read" for a book you have finished and "on" for the one you are on: the one you read last,
	// finished or not, until you have finished every book you have, and then none. Blank for the rest.
	State string `json:"state,omitempty"`
}

// ownedSeriesBooks are the books a series has in the library, in order. An ebook and an audiobook of one book are
// one book. books are the series' own, as storytellerCollection holds them.
func ownedSeriesBooks(books []storyteller.Book) []ReadingSeriesBook {
	items := []ReadingSectionItem{}
	for _, book := range books {
		id := strconv.FormatInt(book.ID, 10)
		kind := "book"
		if book.Ebook == nil && book.Readaloud == nil && book.Audiobook != nil {
			kind = "audiobook"
		}
		item := ReadingSectionItem{
			SourceItemID: id, Title: book.Title, Number: storytellerSeriesNumber(book), Kind: kind,
			Artwork: "/v1/img/reading/storyteller/" + id, Progress: storytellerProgress(book.Position),
			Formats: storytellerAvailability(book),
		}
		if same := sameSeriesBook(items, book); same >= 0 {
			mergeSeriesEdition(&items[same], item)
		} else {
			items = append(items, item)
		}
	}
	out := make([]ReadingSeriesBook, 0, len(items))
	for _, item := range items {
		out = append(out, ReadingSeriesBook{Number: item.Number, Title: item.Title, Cover: item.Artwork, Kind: item.Kind, Owned: true, Released: true})
	}
	order := make([]int, len(out))
	for i := range order {
		order[i] = i
	}
	sort.SliceStable(order, func(a, b int) bool { return seriesPlaceLess(out[order[a]].Number, out[order[b]].Number) })
	sorted := make([]ReadingSeriesBook, 0, len(out))
	progress := make([]*ReadingProgress, 0, len(out))
	for _, index := range order {
		sorted = append(sorted, out[index])
		progress = append(progress, items[index].Progress)
	}
	markWhereYouAre(sorted, progress)
	return sorted
}

// markWhereYouAre sets each book's State from how far you are in it.
func markWhereYouAre(books []ReadingSeriesBook, progress []*ReadingProgress) {
	on, stamp := -1, int64(0)
	allRead := len(books) > 0
	for i, p := range progress {
		if p == nil || !p.Completed {
			allRead = false
		}
		if p != nil {
			if at := progressStamp(p); on < 0 || at > stamp {
				on, stamp = i, at
			}
		}
	}
	if allRead {
		on = -1
	}
	for i := range books {
		switch {
		case i == on:
			books[i].State = "on"
		case progress[i] != nil && progress[i].Completed:
			books[i].State = "read"
		}
	}
}

// progressStamp is when a book was read, as a number to compare: Storyteller's updatedAt, or the timestamp it
// sends in its place.
func progressStamp(progress *ReadingProgress) int64 {
	if progress == nil {
		return 0
	}
	if parsed, err := time.Parse(time.RFC3339Nano, progress.UpdatedAt); err == nil {
		return parsed.UnixNano()
	}
	if value, err := strconv.ParseInt(progress.UpdatedAt, 10, 64); err == nil {
		return value
	}
	return 0
}

// seriesPlaceLess orders books by their place in the series, a book with none after those that have one.
func seriesPlaceLess(a, b string) bool {
	x, errA := strconv.ParseFloat(a, 64)
	y, errB := strconv.ParseFloat(b, 64)
	switch {
	case errA != nil && errB != nil:
		return false
	case errA != nil:
		return false
	case errB != nil:
		return true
	}
	return x < y
}

// titlesMatch reports two titles of one book: the same, or one with a subtitle after the other, capitals, "The" and
// punctuation aside.
func titlesMatch(a, b string) bool {
	x, y := withoutLeadingArticle(normalizeReadingIdentity(a)), withoutLeadingArticle(normalizeReadingIdentity(b))
	if x == "" || y == "" {
		return false
	}
	return x == y || strings.HasPrefix(x, y+" ") || strings.HasPrefix(y, x+" ")
}

// mergeSeriesRoster adds to the books a series has those of its main numbered books that are out and that it does
// not have: from Hardcover's series (nil adds nothing). A novella or a part is never added as missing, one you
// own keeps its place, and a book you have under another number (or none) is not missing. cover turns a
// Hardcover picture address into a hub path, or "" for none.
func mergeSeriesRoster(owned []ReadingSeriesBook, roster *hardcover.Series, today time.Time, cover func(string) string) []ReadingSeriesBook {
	if roster == nil {
		return owned
	}
	day := today.Format("2006-01-02")
	numbers := map[string]bool{}
	for _, book := range owned {
		if book.Number != "" {
			numbers[book.Number] = true
		}
	}
	out := append([]ReadingSeriesBook(nil), owned...)
	for _, entry := range roster.Entries {
		if !entry.Main || entry.ReleasedOn == "" || entry.ReleasedOn > day {
			continue
		}
		number := strconv.FormatFloat(entry.Position, 'f', -1, 64)
		if numbers[number] {
			continue
		}
		had := false
		for _, book := range owned {
			if titlesMatch(book.Title, entry.Title) {
				had = true
				break
			}
		}
		if had {
			continue
		}
		out = append(out, ReadingSeriesBook{Number: number, Title: entry.Title, Cover: cover(entry.Cover), Kind: "book", Released: true})
	}
	sort.SliceStable(out, func(a, b int) bool { return seriesPlaceLess(out[a].Number, out[b].Number) })
	return out
}

// pickSeries is the one of Hardcover's series of a name that is this one: by an author the series has, and the
// most of the books you have among its entries (the Red Rising Saga holds all six of yours where the Red Rising
// trilogy holds three), the larger of two that hold as many. None when no series holds a book you have.
func pickSeries(candidates []hardcover.Series, authors []string, owned []ReadingSeriesBook) *hardcover.Series {
	keys := authorKeys(authors)
	var best *hardcover.Series
	bestScore := 0
	for i := range candidates {
		candidate := &candidates[i]
		if len(candidate.Authors) > 0 && !authorsOverlap(candidate.Authors, keys) {
			continue
		}
		score := 0
		for _, book := range owned {
			if !book.Owned {
				continue
			}
			for _, entry := range candidate.Entries {
				if titlesMatch(book.Title, entry.Title) {
					score++
					break
				}
			}
		}
		if score == 0 {
			continue
		}
		if best == nil || score > bestScore || (score == bestScore && candidate.Primary > best.Primary) {
			best, bestScore = candidate, score
		}
	}
	return best
}

// seriesNames are the names Hardcover may call a series: the name as given and in its other spellings, and with
// the word a saga or a series is often called by after it.
func seriesNames(name string) []string {
	base := hardcover.TitleSpellings(name)
	out := append([]string{}, base...)
	seen := map[string]bool{}
	for _, spelling := range out {
		seen[spelling] = true
	}
	for _, spelling := range base {
		for _, suffix := range []string{" Saga", " Series"} {
			if candidate := spelling + suffix; !seen[candidate] && len(out) < seriesNameLimit {
				seen[candidate] = true
				out = append(out, candidate)
			}
		}
	}
	return out
}

// seriesCandidates is what Hardcover said of the series that go by a name, kept whether or not there were any.
type seriesCandidates struct {
	Series []hardcover.Series
}

func seriesKey(names, authors []string) string {
	keys := authorKeys(authors)
	sort.Strings(keys)
	sum := sha256.Sum256([]byte(strings.Join(names, "|") + "||" + strings.Join(keys, ",")))
	return "reading:hardcover:series:" + hex.EncodeToString(sum[:12])
}

// seriesCandidatesFor is Hardcover's series of the name of a series item, or nil: no key, no answer in the time
// the page gives it (it carries on behind and is there next time), a failure.
func (s *Server) seriesCandidatesFor(ctx context.Context, work *ReadingWork) []hardcover.Series {
	if s.hardcover == nil || strings.TrimSpace(work.Title) == "" || len(work.Authors) == 0 {
		return nil
	}
	names := seriesNames(work.Title)
	authors := append([]string(nil), work.Authors...)
	done := make(chan *seriesCandidates, 1)
	go func() {
		// Shared by whoever asks while it runs and kept for the next, so one page giving up must not end it.
		lookup, cancel := context.WithTimeout(context.WithoutCancel(ctx), 60*time.Second)
		defer cancel()
		result, _, err := cache.Fetch(lookup, s.cache, seriesKey(names, authors), cache.ReadingCommunity, func(fetchCtx context.Context) (*seriesCandidates, error) {
			if s.hardcoverPaused() {
				return nil, errHardcoverPaused
			}
			if err := s.paceHardcover(fetchCtx); err != nil {
				return nil, err
			}
			series, err := s.hardcover.SeriesByName(fetchCtx, names)
			if err != nil {
				s.pauseHardcover()
				// The service's words and never the series asked about.
				slog.Warn("hardcover series lookup failed; leaving it alone for a few minutes", "err", err)
				return nil, err
			}
			return &seriesCandidates{Series: series}, nil
		})
		if err != nil {
			result = nil
		}
		done <- result
	}()
	wait := s.communityBudget
	if wait <= 0 {
		wait = seriesBudget
	}
	select {
	case got := <-done:
		if got == nil {
			return nil
		}
		return got.Series
	case <-time.After(wait):
	case <-ctx.Done():
	}
	return nil
}

// paceHardcover makes a series lookup wait its turn: one every hardcoverSpacing, whoever asks.
func (s *Server) paceHardcover(ctx context.Context) error {
	spacing := s.hardcoverSpacing
	if spacing < 0 {
		return nil
	}
	if spacing == 0 {
		spacing = defaultHardcoverSpacing
	}
	s.hardcoverGate.Lock()
	defer s.hardcoverGate.Unlock()
	if wait := time.Until(s.hardcoverLast.Add(spacing)); wait > 0 {
		select {
		case <-time.After(wait):
		case <-ctx.Done():
			return ctx.Err()
		}
	}
	s.hardcoverLast = time.Now()
	return nil
}

// addSeriesFans adds the books a series does not have to the series items of a page of the library, as far as
// Hardcover answers in time.
func (s *Server) addSeriesFans(ctx context.Context, items []ReadingWork) {
	if s.hardcover == nil {
		return
	}
	var wg sync.WaitGroup
	for i := range items {
		item := &items[i]
		if item.EntityType != "collection" || len(item.SeriesBooks) == 0 {
			continue
		}
		wg.Add(1)
		go func() {
			defer wg.Done()
			candidates := s.seriesCandidatesFor(ctx, item)
			if len(candidates) == 0 {
				return
			}
			roster := pickSeries(candidates, item.Authors, item.SeriesBooks)
			item.SeriesBooks = mergeSeriesRoster(item.SeriesBooks, roster, s.now(), s.readingCoverPath)
		}()
	}
	wg.Wait()
}

// readingCoverPath is the hub path of a cover at an address, which the image proxy serves and no one else can
// ask it to, or "" for an address it will not take.
func (s *Server) readingCoverPath(address string) string {
	if address == "" {
		return ""
	}
	if token := s.images.registerReadingCover(address); token != "" {
		return "/v1/img/reading/" + token
	}
	return ""
}
