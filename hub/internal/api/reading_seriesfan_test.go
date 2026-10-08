package api

// The books of a series for the library's fan (#54): the ones you have, in order, with which you have read and which
// you are on, and the main numbered ones you do not have from Hardcover's series data. The pieces are pure and are
// tested as such; the last test is the listing the Pocket reads.

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"ayaneohub/internal/adapters/hardcover"
	"ayaneohub/internal/adapters/storyteller"
	"ayaneohub/internal/config"
)

func fanBook(id int64, title string, position float64, formats string, progression float64, updatedAt string) storyteller.Book {
	book := storyteller.Book{
		ID: id, Title: title, Authors: []storyteller.Creator{{Name: "Pierce Brown"}},
		Series: []storyteller.Series{{Name: "Red Rising", Position: position}},
	}
	if strings.Contains(formats, "e") {
		book.Ebook = &storyteller.Ebook{UUID: "e"}
	}
	if strings.Contains(formats, "a") {
		book.Audiobook = &storyteller.Audiobook{UUID: "a"}
	}
	if strings.Contains(formats, "r") {
		book.Readaloud = &storyteller.Readaloud{UUID: "r"}
	}
	if updatedAt != "" {
		book.Position = &storyteller.Position{UpdatedAt: updatedAt}
		book.Position.Locator.Locations.TotalProgression = progression
	}
	return book
}

func numbers(books []ReadingSeriesBook) string {
	out := make([]string, 0, len(books))
	for _, book := range books {
		mark := ""
		if !book.Owned {
			mark = "?"
		}
		out = append(out, book.Number+mark)
	}
	return strings.Join(out, " ")
}

func states(books []ReadingSeriesBook) string {
	out := make([]string, 0, len(books))
	for _, book := range books {
		state := book.State
		if state == "" {
			state = "-"
		}
		out = append(out, state)
	}
	return strings.Join(out, " ")
}

func TestOwnedSeriesBooksAreInOrderWithWhatYouHaveReadAndWhereYouAre(t *testing.T) {
	books := ownedSeriesBooks([]storyteller.Book{
		fanBook(3, "Morning Star", 3, "a", 0, ""),
		fanBook(1, "Red Rising", 1, "e", 1.0, "2026-09-01T10:00:00Z"),
		fanBook(2, "Golden Son", 2, "ea", 0.4, "2026-10-02T10:00:00Z"),
		fanBook(4, "Iron Gold", 4, "e", 0, ""),
	})
	if got := numbers(books); got != "1 2 3 4" {
		t.Fatalf("numbers = %q", got)
	}
	// The book you finished is read, the one you read last and have not finished is the one you are on.
	if got := states(books); got != "read on - -" {
		t.Errorf("states = %q", got)
	}
	// An audiobook alone has a square cover; the rest are books. All are owned and out.
	kinds := []string{books[0].Kind, books[1].Kind, books[2].Kind, books[3].Kind}
	if strings.Join(kinds, " ") != "book book audiobook book" {
		t.Errorf("kinds = %v", kinds)
	}
	for _, book := range books {
		if !book.Owned || !book.Released || !strings.HasPrefix(book.Cover, "/v1/img/reading/storyteller/") {
			t.Errorf("an owned book = %+v", book)
		}
	}
	if books[1].Title != "Golden Son" || books[2].Cover != "/v1/img/reading/storyteller/3" {
		t.Errorf("books = %+v", books)
	}
}

func TestTheBookYouAreOnIsTheOneReadLastEvenWhenItIsFinishedUntilTheWholeSeriesIs(t *testing.T) {
	two := []storyteller.Book{
		fanBook(1, "Red Rising", 1, "e", 1.0, "2026-09-01T10:00:00Z"),
		fanBook(2, "Golden Son", 2, "e", 1.0, "2026-10-02T10:00:00Z"),
		fanBook(3, "Morning Star", 3, "e", 0, ""),
	}
	if got := states(ownedSeriesBooks(two)); got != "read on -" {
		t.Errorf("finished two of three: %q", got)
	}
	two[2] = fanBook(3, "Morning Star", 3, "e", 1.0, "2026-10-03T10:00:00Z")
	// Every book you have is read: nothing is on.
	if got := states(ownedSeriesBooks(two)); got != "read read read" {
		t.Errorf("finished: %q", got)
	}
	// Nothing read at all: nothing is on or read.
	untouched := []storyteller.Book{fanBook(1, "Red Rising", 1, "e", 0, ""), fanBook(2, "Golden Son", 2, "e", 0, "")}
	if got := states(ownedSeriesBooks(untouched)); got != "- -" {
		t.Errorf("not started: %q", got)
	}
}

func TestAnEbookAndAnAudiobookOfOneBookAreOneBook(t *testing.T) {
	books := ownedSeriesBooks([]storyteller.Book{
		fanBook(1, "Red Rising", 1, "e", 0, ""),
		fanBook(11, "Red Rising", 1, "a", 0.2, "2026-10-02T10:00:00Z"),
		fanBook(2, "Golden Son", 2, "e", 0, ""),
	})
	if len(books) != 2 || numbers(books) != "1 2" {
		t.Fatalf("books = %+v", books)
	}
	// It is a book with an ebook in it, so its cover is tall.
	if books[0].Kind != "book" {
		t.Errorf("kind = %q", books[0].Kind)
	}
}

func TestANovellaYouOwnKeepsItsPlaceBetweenTwoBooks(t *testing.T) {
	books := ownedSeriesBooks([]storyteller.Book{
		fanBook(1, "Red Rising", 1, "e", 0, ""),
		fanBook(3, "Morning Star", 3, "e", 0, ""),
		fanBook(25, "Sons of Ares", 2.5, "e", 0, ""),
	})
	if got := numbers(books); got != "1 2.5 3" {
		t.Errorf("numbers = %q", got)
	}
}

var saga = hardcover.Series{
	ID: 1033, Name: "Red Rising Saga", Primary: 7, Authors: []string{"Pierce Brown"},
	Entries: []hardcover.SeriesEntry{
		{Position: 1, Main: true, Title: "Red Rising", ReleasedOn: "2014-01-28", Cover: "https://assets.hardcover.app/1.jpg"},
		{Position: 2, Main: true, Title: "Golden Son", ReleasedOn: "2015-01-01", Cover: "https://assets.hardcover.app/2.jpg"},
		{Position: 2.5, Title: "Sons of Ares", ReleasedOn: "2015-06-01"},
		{Position: 3, Main: true, Title: "Morning Star", ReleasedOn: "2016-09-27", Cover: "https://assets.hardcover.app/3.jpg"},
		{Position: 4, Main: true, Title: "Iron Gold", ReleasedOn: "2018-01-01", Cover: "https://assets.hardcover.app/4.jpg"},
		{Position: 4.1, Title: "Iron Gold, Part 1", ReleasedOn: "2018-09-12"},
		{Position: 5, Main: true, Title: "Dark Age", ReleasedOn: "2019-01-01"},
		{Position: 6, Main: true, Title: "Light Bringer", ReleasedOn: "2023-01-01"},
		{Position: 7, Main: true, Title: "Red God", ReleasedOn: ""},
		{Position: 8, Main: true, Title: "Next Year", ReleasedOn: "2027-01-01"},
	},
}

func covers(raw string) string {
	if raw == "" {
		return ""
	}
	return "/v1/img/reading/token-of-" + strings.TrimPrefix(raw, "https://assets.hardcover.app/")
}

func TestTheMainBooksYouDoNotHaveAreInTheFanOutlinedAndTheRestAreNot(t *testing.T) {
	owned := ownedSeriesBooks([]storyteller.Book{
		fanBook(1, "Red Rising", 1, "e", 1.0, "2026-09-01T10:00:00Z"),
		fanBook(2, "Golden Son", 2, "e", 0.3, "2026-10-02T10:00:00Z"),
		fanBook(25, "Sons of Ares", 2.5, "e", 0, ""),
	})
	merged := mergeSeriesRoster(owned, &saga, time.Date(2026, 10, 9, 0, 0, 0, 0, time.UTC), covers)
	// Only the released numbered books: not the novella or the part you do not have, not Red God (no date) or the
	// book announced for next year. The novella you own stays.
	if got := numbers(merged); got != "1 2 2.5 3? 4? 5? 6?" {
		t.Fatalf("numbers = %q", got)
	}
	missing := merged[3]
	if missing.Title != "Morning Star" || missing.Owned || !missing.Released || missing.Cover != "/v1/img/reading/token-of-3.jpg" || missing.State != "" {
		t.Errorf("a book you do not have = %+v", missing)
	}
	// A book with no picture has none to show.
	if merged[5].Cover != "" {
		t.Errorf("dark age = %+v", merged[5])
	}
	// What you have is as it was.
	if states(merged) != "read on - - - - -" || merged[0].Cover != owned[0].Cover {
		t.Errorf("the books you have changed: %s", states(merged))
	}
}

func TestABookYouHaveUnderAnotherNumberIsNotListedAsMissing(t *testing.T) {
	// Storyteller has no place for it (or the wrong one), and Hardcover has Golden Son as the second.
	owned := ownedSeriesBooks([]storyteller.Book{
		fanBook(1, "Red Rising", 1, "e", 0, ""),
		fanBook(2, "Golden Son: A Novel", 0, "e", 0, ""),
		fanBook(3, "The Morning Star", 9, "e", 0, ""),
	})
	merged := mergeSeriesRoster(owned, &saga, time.Date(2026, 10, 9, 0, 0, 0, 0, time.UTC), covers)
	var titles []string
	for _, book := range merged {
		titles = append(titles, book.Title)
	}
	for _, once := range []string{"Golden Son", "Morning Star"} {
		count := 0
		for _, title := range titles {
			if strings.Contains(title, once) {
				count++
			}
		}
		if count != 1 {
			t.Errorf("%s is listed %d times: %v", once, count, titles)
		}
	}
}

func TestNothingFromHardcoverLeavesWhatYouHave(t *testing.T) {
	owned := ownedSeriesBooks([]storyteller.Book{fanBook(1, "Red Rising", 1, "e", 0, "")})
	merged := mergeSeriesRoster(owned, nil, time.Now(), covers)
	if numbers(merged) != "1" {
		t.Errorf("merged = %v", merged)
	}
}

func TestTheSeriesThatHoldsTheBooksYouHaveIsTheOneChosen(t *testing.T) {
	trilogy := hardcover.Series{ID: 274673, Name: "Red Rising", Primary: 3, Authors: []string{"Pierce Brown"}, Entries: saga.Entries[:4]}
	imposter := hardcover.Series{ID: 5, Name: "Red Rising Saga", Primary: 12, Authors: []string{"Someone Else"}, Entries: saga.Entries}
	owned := ownedSeriesBooks([]storyteller.Book{
		fanBook(1, "Red Rising", 1, "e", 0, ""), fanBook(2, "Golden Son", 2, "e", 0, ""), fanBook(3, "Morning Star", 3, "e", 0, ""),
		fanBook(4, "Iron Gold", 4, "e", 0, ""), fanBook(5, "Dark Age", 5, "e", 0, ""),
	})
	authors := []string{"Pierce Brown"}
	// The saga holds all five and the trilogy only three; the larger series by another author is no candidate.
	if got := pickSeries([]hardcover.Series{imposter, trilogy, saga}, authors, owned); got == nil || got.ID != 1033 {
		t.Fatalf("picked %+v", got)
	}
	// Only the trilogy by the right author: two of its books are not what a saga is, but it is the series.
	if got := pickSeries([]hardcover.Series{imposter, trilogy}, authors, owned[:3]); got == nil || got.ID != 274673 {
		t.Fatalf("picked %+v", got)
	}
	// A series of the same name holding none of the books you have is another series.
	other := hardcover.Series{ID: 6, Name: "Red Rising Saga", Authors: authors, Entries: []hardcover.SeriesEntry{{Position: 1, Main: true, Title: "Something Else"}}}
	if got := pickSeries([]hardcover.Series{other}, authors, owned); got != nil {
		t.Errorf("picked %+v", got)
	}
	if got := pickSeries(nil, authors, owned); got != nil {
		t.Errorf("picked %+v", got)
	}
}

func TestASeriesIsAskedForUnderItsLikelyNames(t *testing.T) {
	names := seriesNames("Red Rising")
	for _, want := range []string{"Red Rising", "Red Rising Saga", "Red Rising Series", "The Red Rising"} {
		found := false
		for _, name := range names {
			found = found || name == want
		}
		if !found {
			t.Errorf("%q is not among %v", want, names)
		}
	}
	if len(names) > 16 {
		t.Errorf("too many names: %d", len(names))
	}
}

// ---------------------------------------------------------------------------- the listing the Pocket reads

type fanEnv struct {
	handler   http.Handler
	server    *Server
	hardcover *httptest.Server
	asked     atomic.Int32
	// status, when set, answers every Hardcover request with it.
	status atomic.Int32
}

const fanStoryBooks = `[
 {"id":1,"uuid":"b1","title":"Red Rising","authors":[{"name":"Pierce Brown"}],"series":[{"name":"Red Rising","position":1}],"ebook":{"uuid":"e1"},"position":{"updatedAt":"2026-09-01T10:00:00Z","locator":{"locations":{"totalProgression":1}}}},
 {"id":2,"uuid":"b2","title":"Golden Son","authors":[{"name":"Pierce Brown"}],"series":[{"name":"Red Rising","position":2}],"ebook":{"uuid":"e2"},"audiobook":{"uuid":"a2"},"readaloud":{"uuid":"r2"},"position":{"updatedAt":"2026-10-02T10:00:00Z","locator":{"locations":{"totalProgression":0.4}}}},
 {"id":3,"uuid":"b3","title":"Piranesi","authors":[{"name":"Susanna Clarke"}],"ebook":{"uuid":"e3"}}
]`

func newFanEnv(t *testing.T, withHardcover bool) *fanEnv {
	t.Helper()
	story := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v2/token":
			_, _ = io.WriteString(w, `{"access_token":"story-token","token_type":"Bearer","expires_in":3600}`)
		case "/api/v2/books":
			_, _ = io.WriteString(w, fanStoryBooks)
		default:
			http.NotFound(w, r)
		}
	}))
	t.Cleanup(story.Close)
	env := &fanEnv{}
	cfg := readingCatalogConfig(story.URL, filepath.Join(t.TempDir(), "catalog.json"), []string{"reading"})
	if withHardcover {
		env.hardcover = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			env.asked.Add(1)
			if code := int(env.status.Load()); code != 0 {
				http.Error(w, `{"error":"Throttled"}`, code)
				return
			}
			raw, _ := io.ReadAll(r.Body)
			var request struct {
				Query string `json:"query"`
			}
			_ = json.Unmarshal(raw, &request)
			if !strings.Contains(request.Query, "series(") {
				_, _ = io.WriteString(w, `{"errors":[{"message":"unexpected query"}]}`)
				return
			}
			_, _ = io.WriteString(w, `{"data":{"series":[{"id":1033,"name":"Red Rising Saga","primary_books_count":7,"author":{"name":"Pierce Brown"},"book_series":[
			 {"position":1,"details":"1","book":{"title":"Red Rising","slug":"r","release_date":"2014-01-28","cached_image":{"url":"https://assets.hardcover.app/1.jpg"}}},
			 {"position":2,"details":"2","book":{"title":"Golden Son","slug":"g","release_date":"2015-01-01","cached_image":{}}},
			 {"position":3,"details":"3","book":{"title":"Morning Star","slug":"m","release_date":"2016-09-27","cached_image":{"url":"https://assets.hardcover.app/3.jpg"}}},
			 {"position":4,"details":"4","book":{"title":"Iron Gold","slug":"i","release_date":"2099-01-01","cached_image":{}}}]}]}}`)
		}))
		t.Cleanup(env.hardcover.Close)
		cfg.Services["hardcover"] = config.ServiceConfig{Enabled: true, BaseURL: env.hardcover.URL, APIKey: config.Secret("hc-key")}
	}
	env.server = NewServer(cfg)
	env.server.now = func() time.Time { return time.Date(2026, 10, 9, 12, 0, 0, 0, time.UTC) }
	env.server.hardcoverSpacing = -1
	env.handler = env.server.Handler()
	return env
}

func (e *fanEnv) series(t *testing.T) ReadingWork {
	t.Helper()
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodGet, "/v1/reading/libraries/storyteller:books/items?page=1&sort=title&direction=asc&view=series", nil)
	request.Header.Set("Authorization", "Bearer "+libraryTestToken)
	e.handler.ServeHTTP(recorder, request)
	if recorder.Code != http.StatusOK {
		t.Fatalf("listing = %d: %s", recorder.Code, recorder.Body.String())
	}
	var page ReadingLibraryItemsResponse
	if err := json.Unmarshal(recorder.Body.Bytes(), &page); err != nil {
		t.Fatal(err)
	}
	for _, item := range page.Items {
		if item.EntityType == "collection" {
			return item
		}
	}
	t.Fatalf("no series among %+v", page.Items)
	return ReadingWork{}
}

func TestTheSeriesListingCarriesTheBooksYouHaveAndWhereYouAreWithNoHardcover(t *testing.T) {
	env := newFanEnv(t, false)
	series := env.series(t)
	if got := numbers(series.SeriesBooks); got != "1 2" || states(series.SeriesBooks) != "read on" {
		t.Fatalf("books = %+v", series.SeriesBooks)
	}
	// Golden Son has an ebook and an audiobook (and is aligned): a book.
	if series.SeriesBooks[1].Kind != "book" {
		t.Errorf("kind = %q", series.SeriesBooks[1].Kind)
	}
}

func TestTheSeriesListingAddsTheMissingMainBooksFromHardcoverAndKeepsThemForNextTime(t *testing.T) {
	env := newFanEnv(t, true)
	series := env.series(t)
	if got := numbers(series.SeriesBooks); got != "1 2 3?" {
		t.Fatalf("books = %+v", series.SeriesBooks)
	}
	missing := series.SeriesBooks[2]
	if missing.Title != "Morning Star" || missing.Owned || !strings.HasPrefix(missing.Cover, "/v1/img/reading/") || len(missing.Cover) != len("/v1/img/reading/")+32 {
		t.Errorf("missing = %+v", missing)
	}
	// The book is asked about once however many times the library is read.
	env.series(t)
	env.series(t)
	if got := env.asked.Load(); got != 1 {
		t.Errorf("Hardcover was asked %d times", got)
	}
}

func TestAHardcoverFailureNeverFailsTheListingAndLeavesHardcoverAloneForAWhile(t *testing.T) {
	env := newFanEnv(t, true)
	clock := time.Date(2026, 10, 9, 12, 0, 0, 0, time.UTC)
	env.server.now = func() time.Time { return clock }
	env.status.Store(http.StatusTooManyRequests)

	series := env.series(t)
	if numbers(series.SeriesBooks) != "1 2" {
		t.Fatalf("the listing when Hardcover limited the hub = %+v", series.SeriesBooks)
	}
	if env.asked.Load() != 1 {
		t.Fatalf("asked %d times", env.asked.Load())
	}
	// Inside the pause nothing is asked, and the books you have are still there.
	clock = clock.Add(4 * time.Minute)
	if numbers(env.series(t).SeriesBooks) != "1 2" || env.asked.Load() != 1 {
		t.Fatalf("Hardcover was asked %d times during its pause", env.asked.Load())
	}
	// After it, Hardcover is asked again and, answering, is believed.
	env.status.Store(0)
	clock = clock.Add(2 * time.Minute)
	if got := numbers(env.series(t).SeriesBooks); got != "1 2 3?" {
		t.Errorf("after the pause = %q", got)
	}
}
