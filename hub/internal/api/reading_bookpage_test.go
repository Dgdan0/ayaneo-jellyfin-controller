package api

// The book page's data (#39): what the hub adds to a work for a profile (its "you") and
// for readers at large (Hardcover's rating and genres). This file is the harness the three
// test files of that work share, and the tests of the community half; reading_you_test.go
// has the "you" record and reading_goodreads_test.go the import.

import (
	"bytes"
	"encoding/csv"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"ayaneohub/internal/adapters/hardcover"
	"ayaneohub/internal/config"
)

const (
	bpRedRisingISBN = "9780345539786" // ISBN-10 0345539788
	bpGoldenSonISBN = "9780345539816"
	bpProfileA      = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
	bpProfileB      = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
)

// Storyteller's books: one with an ISBN, one with an ISBN Hardcover has no edition for,
// and three with none.
var bpBooks = []string{
	`{"id":21,"uuid":"book-21","title":"Red Rising","authors":[{"name":"Pierce Brown"}],"identifiers":[{"type":"isbn","value":"978-0-345-53978-6"}],"ebook":{"uuid":"e21","pageCount":382}}`,
	`{"id":22,"uuid":"book-22","title":"The Final Empire","authors":[{"name":"Brandon Sanderson"}],"ebook":{"uuid":"e22"}}`,
	`{"id":23,"uuid":"book-23","title":"Piranesi","authors":[{"name":"Susanna Clarke"}],"ebook":{"uuid":"e23"}}`,
	`{"id":24,"uuid":"book-24","title":"Words of Radiance: Book Two","authors":[{"name":"Brandon Sanderson"}],"ebook":{"uuid":"e24"}}`,
	`{"id":25,"uuid":"book-25","title":"Golden Son","authors":[{"name":"Pierce Brown"}],"identifiers":[{"type":"isbn","value":"9780345539816"}],"ebook":{"uuid":"e25"}}`,
}

// bookPageStoryteller serves the books above.
type bookPageStoryteller struct {
	*httptest.Server
	down atomic.Bool
}

func newBookPageStoryteller(t *testing.T) *bookPageStoryteller {
	t.Helper()
	byID := map[string]string{}
	for _, raw := range bpBooks {
		var head struct {
			ID int `json:"id"`
		}
		if err := json.Unmarshal([]byte(raw), &head); err != nil {
			t.Fatal(err)
		}
		byID[strconv.Itoa(head.ID)] = raw
	}
	fake := &bookPageStoryteller{}
	fake.Server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		path := r.URL.Path
		switch {
		case path == "/api/v2/token":
			_, _ = io.WriteString(w, `{"access_token":"story-token","token_type":"Bearer","expires_in":3600}`)
		case fake.down.Load() && strings.HasPrefix(path, "/api/v2/books"):
			http.Error(w, "storyteller is down", http.StatusInternalServerError)
		case path == "/api/v2/books":
			_, _ = io.WriteString(w, "["+strings.Join(bpBooks, ",")+"]")
		case strings.HasPrefix(path, "/api/v2/books/"):
			raw, ok := byID[strings.TrimPrefix(path, "/api/v2/books/")]
			if !ok {
				http.NotFound(w, r)
				return
			}
			_, _ = io.WriteString(w, raw)
		case path == "/api/Library/libraries":
			_, _ = io.WriteString(w, `[]`)
		default:
			http.NotFound(w, r)
		}
	}))
	return fake
}

const (
	hcRedRising   = `{"id":7,"title":"Red Rising","slug":"red-rising","rating":4.5678,"ratings_count":250000,"cached_tags":{"Genre":[{"tag":"Dystopian","count":700},{"tag":"Science fiction","count":900},{"tag":"Fantasy","count":20}],"Mood":[{"tag":"Dark","count":5000}]},"cached_contributors":[{"author":{"name":"Pierce Brown"}}]}`
	hcFinalEmpire = `{"id":8,"title":"The Final Empire","slug":"the-final-empire","rating":4.41,"ratings_count":120000,"cached_tags":{"Genre":[{"tag":"Fantasy","count":400}]},"cached_contributors":[{"author":{"name":"Brandon Sanderson"}}]}`
	hcImposter    = `{"id":9,"title":"The Final Empire","slug":"another","rating":1.5,"ratings_count":900000,"cached_tags":{"Genre":[{"tag":"Cookery","count":9}]},"cached_contributors":[{"author":{"name":"Someone Else"}}]}`
	hcGoldenSon   = `{"id":10,"title":"Golden Son","slug":"golden-son","rating":4.6,"ratings_count":150000,"cached_tags":{"Genre":[{"tag":"Science fiction","count":300}]},"cached_contributors":[{"author":{"name":"Pierce Brown"}}]}`
)

// bookPageHardcover is Hardcover's GraphQL endpoint as much as the hub asks of it.
type bookPageHardcover struct {
	*httptest.Server
	requests atomic.Int32
	// status, when set, answers every request with it.
	status atomic.Int32
	mu     sync.Mutex
	gate   chan struct{}
	auth   []string
	asked  []string
}

func newBookPageHardcover(t *testing.T) *bookPageHardcover {
	t.Helper()
	fake := &bookPageHardcover{}
	fake.Server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fake.requests.Add(1)
		raw, _ := io.ReadAll(r.Body)
		var request struct {
			Query     string         `json:"query"`
			Variables map[string]any `json:"variables"`
		}
		if r.Method != http.MethodPost || r.URL.Path != "/v1/graphql" || json.Unmarshal(raw, &request) != nil {
			t.Errorf("Hardcover was asked %s %s: %s", r.Method, r.URL.Path, raw)
		}
		fake.mu.Lock()
		fake.auth = append(fake.auth, r.Header.Get("Authorization"))
		fake.asked = append(fake.asked, string(raw))
		gate := fake.gate
		fake.mu.Unlock()
		if gate != nil {
			select {
			case <-gate:
			case <-r.Context().Done():
				return
			}
		}
		if code := int(fake.status.Load()); code != 0 {
			http.Error(w, `{"error":"Throttled"}`, code)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, answerHardcover(request.Query, request.Variables))
	}))
	return fake
}

func answerHardcover(query string, variables map[string]any) string {
	switch {
	case strings.Contains(query, "editions("):
		if variables["isbn13"] == bpRedRisingISBN {
			return `{"data":{"editions":[{"book":` + hcRedRising + `}]}}`
		}
		return `{"data":{"editions":[]}}`
	case strings.Contains(query, "books("):
		switch variables["title"] {
		case "The Final Empire":
			return `{"data":{"books":[` + hcImposter + `,` + hcFinalEmpire + `]}}`
		case "Golden Son":
			return `{"data":{"books":[` + hcGoldenSon + `]}}`
		}
		return `{"data":{"books":[]}}`
	}
	return `{"errors":[{"message":"unexpected query"}]}`
}

// hold makes Hardcover wait for the returned release function before it answers.
func (h *bookPageHardcover) hold() (release func()) {
	gate := make(chan struct{})
	h.mu.Lock()
	h.gate = gate
	h.mu.Unlock()
	var once sync.Once
	return func() { once.Do(func() { close(gate) }) }
}

func (h *bookPageHardcover) tokens() []string {
	h.mu.Lock()
	defer h.mu.Unlock()
	return append([]string(nil), h.auth...)
}

type bookPageClock struct {
	mu sync.Mutex
	at time.Time
}

func (c *bookPageClock) now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.at
}

func (c *bookPageClock) advance(d time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.at = c.at.Add(d)
}

type bookPageOptions struct {
	// hardcover tells the hub about Hardcover, with hardcoverKey as its api_key.
	hardcover    bool
	hardcoverKey string
	scopes       []string
	// youFile is written as reading-you.json before the hub starts.
	youFile string
}

type bookPageEnv struct {
	t         *testing.T
	cfg       *config.Config
	server    *Server
	handler   http.Handler
	story     *bookPageStoryteller
	hardcover *bookPageHardcover
	clock     *bookPageClock
	dir       string
}

func newBookPageEnv(t *testing.T, options bookPageOptions) *bookPageEnv {
	t.Helper()
	dir := t.TempDir()
	story := newBookPageStoryteller(t)
	t.Cleanup(story.Close)
	fakeHardcover := newBookPageHardcover(t)
	t.Cleanup(fakeHardcover.Close)
	scopes := options.scopes
	if scopes == nil {
		scopes = []string{"reading"}
	}
	cfg := readingCatalogConfig(story.URL, filepath.Join(dir, "catalog.json"), scopes)
	cfg.Server.OfflineRegistry = filepath.Join(dir, "offline.json")
	if options.hardcover {
		cfg.Services["hardcover"] = config.ServiceConfig{Enabled: true, BaseURL: fakeHardcover.URL, APIKey: config.Secret(options.hardcoverKey)}
	}
	if options.youFile != "" {
		if err := os.WriteFile(readingYouPath(cfg.Server.OfflineRegistry), []byte(options.youFile), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	env := &bookPageEnv{
		t: t, cfg: cfg, story: story, hardcover: fakeHardcover, dir: dir,
		clock: &bookPageClock{at: time.Date(2026, 10, 7, 12, 0, 0, 0, time.UTC)},
	}
	env.start()
	return env
}

// start builds the hub afresh on the same files, as a restart does.
func (e *bookPageEnv) start() {
	e.server = NewServer(e.cfg)
	e.server.now = e.clock.now
	e.handler = e.server.Handler()
}

func (e *bookPageEnv) youPath() string { return readingYouPath(e.cfg.Server.OfflineRegistry) }

func (e *bookPageEnv) do(method, path, body string, headers ...string) *httptest.ResponseRecorder {
	e.t.Helper()
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(method, path, strings.NewReader(body))
	request.Header.Set("Authorization", "Bearer "+libraryTestToken)
	for i := 0; i+1 < len(headers); i += 2 {
		request.Header.Set(headers[i], headers[i+1])
	}
	e.handler.ServeHTTP(recorder, request)
	return recorder
}

// works are the ids of Storyteller's books by title, as the shelf binds them.
func (e *bookPageEnv) works() map[string]string {
	e.t.Helper()
	page := e.do(http.MethodGet, "/v1/reading/libraries/storyteller:books/items?page=1&sort=title&direction=asc", "")
	if page.Code != http.StatusOK {
		e.t.Fatalf("shelf = %d: %s", page.Code, page.Body.String())
	}
	var shelf ReadingLibraryItemsResponse
	if err := json.Unmarshal(page.Body.Bytes(), &shelf); err != nil {
		e.t.Fatal(err)
	}
	ids := map[string]string{}
	for _, item := range shelf.Items {
		ids[item.Title] = item.ID
	}
	return ids
}

func (e *bookPageEnv) work(title string) string {
	e.t.Helper()
	id, ok := e.works()[title]
	if !ok {
		e.t.Fatalf("no work titled %q on the shelf", title)
	}
	return id
}

// view reads a work's page as a profile ("" for the hub's default).
func (e *bookPageEnv) view(id, profile string) ReadingWork {
	e.t.Helper()
	var headers []string
	if profile != "" {
		headers = []string{jellyfinUserHeader, profile}
	}
	got := e.do(http.MethodGet, "/v1/reading/works/"+id, "", headers...)
	if got.Code != http.StatusOK {
		e.t.Fatalf("work = %d: %s", got.Code, got.Body.String())
	}
	if !strings.Contains(got.Header().Get("Vary"), jellyfinUserHeader) {
		e.t.Errorf("the page varies by profile, and Vary = %q", got.Header().Get("Vary"))
	}
	var work ReadingWork
	if err := json.Unmarshal(got.Body.Bytes(), &work); err != nil {
		e.t.Fatal(err)
	}
	return work
}

// goodreadsColumns are the columns of a real export, in its order.
var goodreadsColumns = []string{
	"Book Id", "Title", "Author", "Author l-f", "Additional Authors", "ISBN", "ISBN13", "My Rating",
	"Average Rating", "Publisher", "Binding", "Number of Pages", "Year Published", "Original Publication Year",
	"Date Read", "Date Added", "Bookshelves", "Bookshelves with positions", "Exclusive Shelf", "My Review",
	"Spoiler", "Private Notes", "Read Count", "Owned Copies",
}

// goodreadsCSV writes rows, each a map by column, as Goodreads does (the ISBNs arrive
// as ="…", which the writer quotes).
func goodreadsCSV(rows ...map[string]string) string {
	var out bytes.Buffer
	writer := csv.NewWriter(&out)
	_ = writer.Write(goodreadsColumns)
	for _, row := range rows {
		record := make([]string, len(goodreadsColumns))
		for i, name := range goodreadsColumns {
			record[i] = row[name]
		}
		_ = writer.Write(record)
	}
	writer.Flush()
	return out.String()
}

const (
	secretReview = "SECRET REVIEW TEXT"
	secretNote   = "SECRET PRIVATE NOTE"
)

var (
	// Found by ISBN, in the wrapped form, with a review and a note that must never be kept.
	grRedRising = map[string]string{
		"Title": "Red Rising (Red Rising Saga, #1)", "Author": "Pierce Brown", "ISBN": `="0345539788"`, "ISBN13": `="9780345539786"`,
		"My Rating": "5", "Average Rating": "4.28", "Number of Pages": "382", "Date Read": "2026/09/14", "Date Added": "2025/01/02",
		"Bookshelves": "favorites, sci-fi", "Exclusive Shelf": "read", "My Review": secretReview, "Private Notes": secretNote, "Read Count": "2",
	}
	// Found by title and author, past the series note.
	grFinalEmpire = map[string]string{
		"Title": "The Final Empire (Mistborn, #1)", "Author": "Brandon Sanderson", "ISBN": `=""`, "ISBN13": `=""`,
		"My Rating": "4", "Average Rating": "4.45", "Date Read": "2024/03/01", "Bookshelves": "fantasy", "Exclusive Shelf": "read", "Read Count": "1",
	}
	// To read: a status, and one shelf of its own.
	grPiranesi = map[string]string{
		"Title": "Piranesi", "Author": "Susanna Clarke", "My Rating": "0", "Average Rating": "4.18",
		"Bookshelves": "to-read, magic", "Exclusive Shelf": "to-read", "Read Count": "0",
	}
	// Found by what precedes its subtitle.
	grWordsOfRadiance = map[string]string{
		"Title": "Words of Radiance (The Stormlight Archive, #2)", "Author": "Brandon Sanderson", "My Rating": "0",
		"Bookshelves": "currently-reading, epic", "Exclusive Shelf": "currently-reading",
	}
	grNotInLibrary = map[string]string{
		"Title": "Some Book I Never Added", "Author": "Nobody Famous", "Exclusive Shelf": "to-read", "Bookshelves": "to-read",
	}
)

// grSample is an export of those rows and a blank one, which has no title.
func grSample() string {
	return goodreadsCSV(grRedRising, grFinalEmpire, grPiranesi, grWordsOfRadiance, grNotInLibrary, map[string]string{})
}

// ---- the community half: Hardcover's rating and genres ----

func TestWithoutAHardcoverKeyNothingIsAskedAndThePageHasNoCommunity(t *testing.T) {
	for name, options := range map[string]bookPageOptions{
		"not configured":      {},
		"enabled with no key": {hardcover: true, hardcoverKey: ""},
		"a key of spaces":     {hardcover: true, hardcoverKey: "   "},
	} {
		t.Run(name, func(t *testing.T) {
			env := newBookPageEnv(t, options)
			id := env.work("Red Rising")
			got := env.do(http.MethodGet, "/v1/reading/works/"+id, "")
			if got.Code != http.StatusOK {
				t.Fatalf("work = %d: %s", got.Code, got.Body.String())
			}
			var raw map[string]json.RawMessage
			if err := json.Unmarshal(got.Body.Bytes(), &raw); err != nil {
				t.Fatal(err)
			}
			// What an older app sees: the same page, with neither new field.
			for _, field := range []string{"community", "you", "isbns"} {
				if _, present := raw[field]; present {
					t.Errorf("%q is on the page: %s", field, got.Body.String())
				}
			}
			if env.server.hardcover != nil {
				t.Error("the hub built a Hardcover client with no key")
			}
			if env.hardcover.requests.Load() != 0 {
				t.Errorf("Hardcover was asked %d times with no key", env.hardcover.requests.Load())
			}
		})
	}
}

func TestACommunityRatingAndGenresComeFromHardcoverByISBN(t *testing.T) {
	env := newBookPageEnv(t, bookPageOptions{hardcover: true, hardcoverKey: "hc-key"})
	id := env.work("Red Rising")

	work := env.view(id, "")
	want := &ReadingCommunity{Rating: 4.57, Count: 250000, Source: "hardcover"}
	if work.Community == nil || *work.Community != *want {
		t.Fatalf("community = %+v, want %+v", work.Community, want)
	}
	// Most tagged first, and only the Genre tags.
	if got := strings.Join(work.Genres, "|"); got != "Science fiction|Dystopian|Fantasy" {
		t.Errorf("genres = %q", got)
	}
	if got := env.hardcover.tokens(); len(got) != 1 || got[0] != "Bearer hc-key" {
		t.Errorf("Authorization = %q", got)
	}
	env.hardcover.mu.Lock()
	asked := env.hardcover.asked[0]
	env.hardcover.mu.Unlock()
	if !strings.Contains(asked, bpRedRisingISBN) || !strings.Contains(asked, "0345539788") {
		t.Errorf("the book was not asked for by both spellings of its ISBN: %s", asked)
	}

	// A second view is answered from what was kept.
	again := env.view(id, "")
	if again.Community == nil || *again.Community != *want || env.hardcover.requests.Load() != 1 {
		t.Errorf("second view: %+v after %d requests", again.Community, env.hardcover.requests.Load())
	}
}

func TestACommunityRatingCanBeFoundByTitleAndOneOfTheWorksAuthors(t *testing.T) {
	env := newBookPageEnv(t, bookPageOptions{hardcover: true, hardcoverKey: "hc-key"})

	// The Final Empire has no ISBN; a book of that title by someone else has the most
	// ratings and must not be taken for it.
	empire := env.view(env.work("The Final Empire"), "")
	if empire.Community == nil || empire.Community.Rating != 4.41 || empire.Community.Count != 120000 || empire.Community.Source != "hardcover" {
		t.Fatalf("community = %+v", empire.Community)
	}
	if got := strings.Join(empire.Genres, "|"); got != "Fantasy" {
		t.Errorf("genres = %q (the other book's must not be added)", got)
	}

	// Golden Son has an ISBN Hardcover has no edition of, and is found by its title.
	before := env.hardcover.requests.Load()
	son := env.view(env.work("Golden Son"), "")
	if son.Community == nil || son.Community.Rating != 4.6 {
		t.Fatalf("a book found by its title = %+v", son.Community)
	}
	if asked := env.hardcover.requests.Load() - before; asked != 2 {
		t.Errorf("Golden Son took %d requests, want the ISBN and then the title", asked)
	}
}

func TestABookHardcoverDoesNotHaveHasNoCommunityAndIsNotAskedAboutAgain(t *testing.T) {
	env := newBookPageEnv(t, bookPageOptions{hardcover: true, hardcoverKey: "hc-key"})
	id := env.work("Piranesi")
	for i := 0; i < 3; i++ {
		if work := env.view(id, ""); work.Community != nil {
			t.Fatalf("community = %+v", work.Community)
		}
	}
	if got := env.hardcover.requests.Load(); got != 1 {
		t.Errorf("a book it does not have was asked about %d times", got)
	}
}

func TestAFirstViewDoesNotWaitForASlowHardcoverAndFindsTheAnswerNextTime(t *testing.T) {
	env := newBookPageEnv(t, bookPageOptions{hardcover: true, hardcoverKey: "hc-key"})
	env.server.communityBudget = 20 * time.Millisecond
	release := env.hardcover.hold()
	t.Cleanup(release)
	id := env.work("Red Rising")

	first := env.view(id, "")
	if first.Community != nil || len(first.Genres) != 0 {
		t.Fatalf("the first view waited for Hardcover: %+v %v", first.Community, first.Genres)
	}
	if first.Title != "Red Rising" {
		t.Errorf("the page is not whole: %+v", first)
	}

	// The lookup carries on behind the page, and is there for the next view.
	release()
	eventually(t, "the lookup that outlived the first view", func() bool {
		return env.view(id, "").Community != nil
	})
	if got := env.hardcover.requests.Load(); got != 1 {
		t.Errorf("the slow lookup was made %d times", got)
	}
}

func TestAHardcoverFailureNeverFailsThePageAndLeavesHardcoverAloneForAWhile(t *testing.T) {
	env := newBookPageEnv(t, bookPageOptions{hardcover: true, hardcoverKey: "hc-key"})
	env.hardcover.status.Store(http.StatusTooManyRequests)
	red, empire := env.work("Red Rising"), env.work("The Final Empire")

	if work := env.view(red, ""); work.Community != nil || work.Title != "Red Rising" {
		t.Fatalf("a page when Hardcover limited the hub = %+v", work)
	}
	if env.hardcover.requests.Load() != 1 {
		t.Fatalf("requests = %d", env.hardcover.requests.Load())
	}

	// Another book, and the same one again, inside the pause: nothing is asked.
	env.clock.advance(4 * time.Minute)
	env.view(empire, "")
	env.view(red, "")
	if got := env.hardcover.requests.Load(); got != 1 {
		t.Fatalf("Hardcover was asked %d times during its pause", got)
	}

	// After it, Hardcover is asked again and, answering, is believed.
	env.hardcover.status.Store(0)
	env.clock.advance(2 * time.Minute)
	if work := env.view(red, ""); work.Community == nil || work.Community.Source != "hardcover" {
		t.Fatalf("after the pause = %+v", work.Community)
	}
	if got := env.hardcover.requests.Load(); got != 2 {
		t.Errorf("requests = %d", got)
	}
}

func TestAnUnreadableHardcoverAnswerIsNoCommunityToo(t *testing.T) {
	env := newBookPageEnv(t, bookPageOptions{hardcover: true, hardcoverKey: "hc-key"})
	broken := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = io.WriteString(w, `{"errors":[{"message":"field 'ratings_count' not found in type: 'books'"}]}`)
	}))
	defer broken.Close()
	svc := env.cfg.Services["hardcover"]
	svc.BaseURL = broken.URL
	env.cfg.Services["hardcover"] = svc
	env.start()

	if work := env.view(env.work("Red Rising"), ""); work.Community != nil || work.Title != "Red Rising" {
		t.Fatalf("page = %+v", work)
	}
}

func TestMergeGenresAddsToTheWorksOwnWithoutRepeatsUpToTheLine(t *testing.T) {
	cases := []struct {
		name       string
		own, extra []string
		want       string
	}{
		{"nothing of its own", nil, []string{"Fantasy", "Epic fantasy"}, "Fantasy|Epic fantasy"},
		{"a repeat in another case", []string{"fantasy"}, []string{"Fantasy", "Magic"}, "fantasy|Magic"},
		{"blanks and spaces", []string{"Fantasy"}, []string{"  ", " Magic "}, "Fantasy|Magic"},
		{"stops at the line", []string{"a"}, []string{"b", "c", "d", "e", "f", "g", "h"}, "a|b|c|d|e|f"},
		{"its own are never cut", []string{"a", "b", "c", "d", "e", "f", "g", "h"}, []string{"i"}, "a|b|c|d|e|f|g|h"},
		{"nothing at all", nil, nil, ""},
	}
	for _, c := range cases {
		if got := strings.Join(mergeGenres(c.own, c.extra), "|"); got != c.want {
			t.Errorf("%s: %q, want %q", c.name, got, c.want)
		}
	}
}

func TestCommunityIsHardcoversOtherwiseGoodreadsAverage(t *testing.T) {
	book := &hardcover.Book{Rating: 4.5678, RatingsCount: 12}
	if got := communityOf(book, &youRecord{Average: 3.9}); got == nil || got.Rating != 4.57 || got.Count != 12 || got.Source != "hardcover" {
		t.Errorf("both = %+v", got)
	}
	// A book Hardcover has but nobody has rated is not a rating of 0.
	if got := communityOf(&hardcover.Book{}, &youRecord{Average: 3.9}); got == nil || got.Rating != 3.9 || got.Count != 0 || got.Source != "goodreads" {
		t.Errorf("unrated at Hardcover = %+v", got)
	}
	if got := communityOf(nil, &youRecord{Average: 4.1234}); got == nil || got.Rating != 4.12 || got.Source != "goodreads" {
		t.Errorf("export only = %+v", got)
	}
	if got := communityOf(nil, &youRecord{}); got != nil {
		t.Errorf("a record with no average = %+v", got)
	}
	if got := communityOf(nil, nil); got != nil {
		t.Errorf("neither = %+v", got)
	}
}

func TestTheCommunityKeyIsTheSameForTheSameBookHoweverItsAuthorsAreWritten(t *testing.T) {
	a := communityInput{isbns: []string{bpRedRisingISBN}, title: "Red Rising (Red Rising Saga, #1)", authors: []string{"Pierce Brown", "Brown, Pierce"}}
	b := communityInput{isbns: []string{bpRedRisingISBN}, title: "Red Rising", authors: []string{"Brown Pierce", "pierce brown"}}
	if a.key() != b.key() {
		t.Errorf("%q vs %q", a.key(), b.key())
	}
	other := communityInput{isbns: []string{bpGoldenSonISBN}, title: "Golden Son", authors: []string{"Pierce Brown"}}
	if a.key() == other.key() {
		t.Error("two books share a key")
	}
	if !strings.HasPrefix(a.key(), "reading:") {
		t.Errorf("key %q is outside the reading sweeps", a.key())
	}
	if !(communityInput{}).empty() || (communityInput{title: "x"}).empty() || (communityInput{isbns: []string{"1"}}).empty() {
		t.Error("empty is wrong")
	}
}
