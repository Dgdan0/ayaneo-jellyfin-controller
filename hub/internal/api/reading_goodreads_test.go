package api

// Importing the owner's Goodreads export (#39): matching its rows to the hub's works, what
// is kept of them, and what is never kept or logged.

import (
	"bytes"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"reflect"
	"strings"
	"sync"
	"testing"

	"ayaneohub/internal/adapters/storyteller"
	readingdomain "ayaneohub/internal/reading"
)

func (e *bookPageEnv) importGoodreads(body, query string, headers ...string) *httptest.ResponseRecorder {
	e.t.Helper()
	headers = append([]string{"Content-Type", "text/csv"}, headers...)
	return e.do(http.MethodPost, "/v1/reading/import/goodreads"+query, body, headers...)
}

func (e *bookPageEnv) importOK(body, query string, headers ...string) GoodreadsImportReport {
	e.t.Helper()
	got := e.importGoodreads(body, query, headers...)
	if got.Code != http.StatusOK {
		e.t.Fatalf("import = %d: %s", got.Code, got.Body.String())
	}
	var report GoodreadsImportReport
	if err := json.Unmarshal(got.Body.Bytes(), &report); err != nil {
		e.t.Fatal(err)
	}
	return report
}

// importStatus is what the hub says of a profile's last import.
func (e *bookPageEnv) importStatus(profile string) (GoodreadsImportReport, string) {
	e.t.Helper()
	var headers []string
	if profile != "" {
		headers = []string{jellyfinUserHeader, profile}
	}
	got := e.do(http.MethodGet, "/v1/reading/import/goodreads", "", headers...)
	if got.Code != http.StatusOK {
		e.t.Fatalf("status = %d: %s", got.Code, got.Body.String())
	}
	var report GoodreadsImportReport
	if err := json.Unmarshal(got.Body.Bytes(), &report); err != nil {
		e.t.Fatal(err)
	}
	return report, got.Body.String()
}

type lockedBuffer struct {
	mu   sync.Mutex
	text bytes.Buffer
}

func (l *lockedBuffer) Write(p []byte) (int, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.text.Write(p)
}

func (l *lockedBuffer) String() string {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.text.String()
}

// captureLog sends everything the hub logs to a buffer for the rest of the test.
func captureLog(t *testing.T) *lockedBuffer {
	t.Helper()
	logged := &lockedBuffer{}
	previous := slog.Default()
	slog.SetDefault(slog.New(slog.NewTextHandler(logged, &slog.HandlerOptions{Level: slog.LevelDebug})))
	t.Cleanup(func() { slog.SetDefault(previous) })
	return logged
}

var sampleCounts = goodreadsCounts{Rows: 6, Matched: 4, Works: 4, Unmatched: 1, Skipped: 1}

func TestADryRunSaysWhatWouldMatchAndKeepsNothing(t *testing.T) {
	env := newBookPageEnv(t, bookPageOptions{})
	ids := env.works()

	report := env.importOK(grSample(), "?dryRun=true")
	if !report.DryRun || report.Imported || report.ImportedAt != "" {
		t.Errorf("report = %+v", report)
	}
	if report.goodreadsCounts != sampleCounts {
		t.Errorf("counts = %+v, want %+v", report.goodreadsCounts, sampleCounts)
	}
	wantMatches := []goodreadsMatchItem{
		{Title: "Red Rising (Red Rising Saga, #1)", Author: "Pierce Brown", WorkID: ids["Red Rising"], By: "isbn"},
		{Title: "The Final Empire (Mistborn, #1)", Author: "Brandon Sanderson", WorkID: ids["The Final Empire"], By: "title_author"},
		{Title: "Piranesi", Author: "Susanna Clarke", WorkID: ids["Piranesi"], By: "title_author"},
		{Title: "Words of Radiance (The Stormlight Archive, #2)", Author: "Brandon Sanderson", WorkID: ids["Words of Radiance: Book Two"], By: "title_author"},
	}
	if !reflect.DeepEqual(report.Matches, wantMatches) {
		t.Errorf("matches:\n got %+v\nwant %+v", report.Matches, wantMatches)
	}
	wantMisses := []goodreadsMissItem{{Title: "Some Book I Never Added", Author: "Nobody Famous", Reason: "not_in_library"}}
	if !reflect.DeepEqual(report.UnmatchedRows, wantMisses) || report.Truncated {
		t.Errorf("unmatched = %+v (truncated %v)", report.UnmatchedRows, report.Truncated)
	}

	// Nothing was kept: not a profile's import, not a book's "you", not a file.
	if status, raw := env.importStatus(""); status.Imported || strings.TrimSpace(raw) != `{"imported":false}` {
		t.Errorf("status = %s", raw)
	}
	env.expectYou(ids["Red Rising"], "", nil)
	if _, err := os.Stat(env.youPath()); err == nil {
		t.Error("a dry run wrote a file")
	}
}

func TestAnImportKeepsWhatThePageUsesOfTheMatchedRowsAndNothingElse(t *testing.T) {
	env := newBookPageEnv(t, bookPageOptions{})
	ids := env.works()

	report := env.importOK(grSample(), "")
	if report.DryRun || !report.Imported || report.ImportedAt != "2026-10-07T12:00:00Z" || report.goodreadsCounts != sampleCounts {
		t.Fatalf("report = %+v", report)
	}

	env.expectYou(ids["Red Rising"], "", &ReadingYou{Rating: 5, Finished: "2026-09", ReadCount: 2, Shelves: []string{"favorites", "sci-fi"}, Status: "read", Source: "goodreads"})
	env.expectYou(ids["The Final Empire"], "", &ReadingYou{Rating: 4, Finished: "2024-03", ReadCount: 1, Shelves: []string{"fantasy"}, Status: "read", Source: "goodreads"})
	// "to-read" and "currently-reading" are statuses; the shelves are the person's own.
	env.expectYou(ids["Piranesi"], "", &ReadingYou{Shelves: []string{"magic"}, Status: "to-read", Source: "goodreads"})
	env.expectYou(ids["Words of Radiance: Book Two"], "", &ReadingYou{Shelves: []string{"epic"}, Status: "currently-reading", Source: "goodreads"})
	env.expectYou(ids["Golden Son"], "", nil)

	// With no Hardcover, readers at large are the average in the export.
	for title, want := range map[string]*ReadingCommunity{
		"Red Rising":                  {Rating: 4.28, Source: "goodreads"},
		"The Final Empire":            {Rating: 4.45, Source: "goodreads"},
		"Piranesi":                    {Rating: 4.18, Source: "goodreads"},
		"Words of Radiance: Book Two": nil,
		"Golden Son":                  nil,
	} {
		if got := env.view(ids[title], "").Community; !reflect.DeepEqual(got, want) {
			t.Errorf("%s: community = %+v, want %+v", title, got, want)
		}
	}

	// The last import can be looked at again.
	status, _ := env.importStatus("")
	if !status.Imported || status.ImportedAt != report.ImportedAt || status.goodreadsCounts != sampleCounts ||
		len(status.Matches) != 4 || len(status.UnmatchedRows) != 1 || status.DryRun {
		t.Errorf("status = %+v", status)
	}

	// The file holds the fields above, never a review, a note or a row's numbers.
	saved, err := os.ReadFile(env.youPath())
	if err != nil {
		t.Fatal(err)
	}
	for _, private := range []string{secretReview, secretNote, "9780345539786", "0345539788", "Date Added", "2025/01/02", "382"} {
		if strings.Contains(string(saved), private) {
			t.Errorf("the file holds %q", private)
		}
	}
}

func TestTheImportAndItsReportNeverReachTheLog(t *testing.T) {
	logged := captureLog(t)
	env := newBookPageEnv(t, bookPageOptions{})
	ids := env.works()
	env.importOK(grSample(), "?dryRun=true")
	env.importOK(grSample(), "")
	env.importStatus("")
	env.view(ids["Red Rising"], "")
	env.patchYou(ids["Red Rising"], `{"rating":3}`)
	env.do(http.MethodDelete, "/v1/reading/import/goodreads", "")
	// A refused one, whose rows the error must not repeat.
	refused := env.importGoodreads("Title,Nothing\nMARKER-IN-A-REFUSED-FILE,x\n", "")
	if refused.Code != http.StatusBadRequest || strings.Contains(refused.Body.String(), "MARKER") {
		t.Errorf("refused import = %d: %s", refused.Code, refused.Body.String())
	}

	text := logged.String()
	if !strings.Contains(text, "goodreads import") || !strings.Contains(text, "rows=6") || !strings.Contains(text, "matched=4") {
		t.Errorf("the import left no counts in the log:\n%s", text)
	}
	for _, private := range []string{"Red Rising", "Piranesi", "Sanderson", "Pierce", "Clarke", "Nobody", "Never Added", secretReview, secretNote, "9780345539786", "MARKER"} {
		if strings.Contains(text, private) {
			t.Errorf("the log holds %q:\n%s", private, text)
		}
	}
}

func TestAReimportReplacesTheLastOneAndKeepsWhatTheAppSet(t *testing.T) {
	env := newBookPageEnv(t, bookPageOptions{})
	ids := env.works()
	red := ids["Red Rising"]
	env.importOK(grSample(), "")

	// In an app: the rating is taken back, and the book was finished again, three times in all.
	env.patchYou(red, `{"rating":null,"finished":"2026-10","readCount":3}`)

	second := goodreadsCSV(
		map[string]string{"Title": "Red Rising", "Author": "Pierce Brown", "ISBN13": `="9780345539786"`, "My Rating": "4", "Date Read": "2025/01/05",
			"Bookshelves": "reread", "Exclusive Shelf": "read", "Read Count": "1"},
		grFinalEmpire,
	)
	report := env.importOK(second, "")
	if report.Rows != 2 || report.Matched != 2 || report.Works != 2 || report.Unmatched != 0 {
		t.Errorf("second report = %+v", report.goodreadsCounts)
	}

	// The import's shelves are the new ones; the app's values stand over its rating,
	// month and count, the cleared rating among them.
	env.expectYou(red, "", &ReadingYou{Finished: "2026-10", ReadCount: 3, Shelves: []string{"reread"}, Status: "read", Source: "app"})
	// A book the new export left out has nothing from the old.
	env.expectYou(ids["Piranesi"], "", nil)
	env.expectYou(ids["The Final Empire"], "", &ReadingYou{Rating: 4, Finished: "2024-03", ReadCount: 1, Shelves: []string{"fantasy"}, Status: "read", Source: "goodreads"})
	if status, _ := env.importStatus(""); status.Rows != 2 || len(status.Matches) != 2 || len(status.UnmatchedRows) != 0 {
		t.Errorf("status = %+v", status)
	}
}

func TestForgettingTheImportKeepsWhatTheAppSet(t *testing.T) {
	env := newBookPageEnv(t, bookPageOptions{})
	ids := env.works()
	env.importOK(grSample(), "")
	env.patchYou(ids["Piranesi"], `{"rating":5}`)

	for i := 0; i < 2; i++ { // forgetting again is not an error
		got := env.do(http.MethodDelete, "/v1/reading/import/goodreads", "")
		if got.Code != http.StatusOK || strings.TrimSpace(got.Body.String()) != `{"imported":false}` {
			t.Fatalf("forget = %d: %s", got.Code, got.Body.String())
		}
	}
	if status, _ := env.importStatus(""); status.Imported {
		t.Error("the import is still there")
	}
	env.expectYou(ids["Piranesi"], "", &ReadingYou{Rating: 5, Shelves: []string{}, Source: "app"})
	env.expectYou(ids["Red Rising"], "", nil)
	if got := env.view(ids["Red Rising"], "").Community; got != nil {
		t.Errorf("the forgotten export still rates a book: %+v", got)
	}
}

func TestEachProfileHasItsOwnImport(t *testing.T) {
	env := newBookPageEnv(t, bookPageOptions{})
	ids := env.works()
	env.importOK(grSample(), "", jellyfinUserHeader, bpProfileA)
	other := goodreadsCSV(map[string]string{"Title": "Golden Son", "Author": "Pierce Brown", "My Rating": "1", "Exclusive Shelf": "read", "Date Read": "2020/02/02"})
	env.importOK(other, "", jellyfinUserHeader, bpProfileB)

	env.expectYou(ids["Red Rising"], bpProfileA, &ReadingYou{Rating: 5, Finished: "2026-09", ReadCount: 2, Shelves: []string{"favorites", "sci-fi"}, Status: "read", Source: "goodreads"})
	env.expectYou(ids["Red Rising"], bpProfileB, nil)
	env.expectYou(ids["Golden Son"], bpProfileA, nil)
	env.expectYou(ids["Golden Son"], bpProfileB, &ReadingYou{Rating: 1, Finished: "2020-02", ReadCount: 1, Shelves: []string{}, Status: "read", Source: "goodreads"})
	env.expectYou(ids["Red Rising"], "", nil)

	if status, _ := env.importStatus(bpProfileA); status.Rows != 6 {
		t.Errorf("A's status = %+v", status)
	}
	if status, _ := env.importStatus(bpProfileB); status.Rows != 1 {
		t.Errorf("B's status = %+v", status)
	}
	if status, _ := env.importStatus(""); status.Imported {
		t.Errorf("the default profile has an import: %+v", status)
	}
	// Forgetting is one profile's.
	env.do(http.MethodDelete, "/v1/reading/import/goodreads", "", jellyfinUserHeader, bpProfileB)
	env.expectYou(ids["Red Rising"], bpProfileA, &ReadingYou{Rating: 5, Finished: "2026-09", ReadCount: 2, Shelves: []string{"favorites", "sci-fi"}, Status: "read", Source: "goodreads"})
	env.expectYou(ids["Golden Son"], bpProfileB, nil)
}

func TestSeveralRowsOfOneBookAreOneRecord(t *testing.T) {
	env := newBookPageEnv(t, bookPageOptions{})
	ids := env.works()
	body := goodreadsCSV(
		// The paperback, by its ISBN, still to read.
		map[string]string{"Title": "Red Rising", "Author": "Pierce Brown", "ISBN13": `="9780345539786"`, "Exclusive Shelf": "to-read", "Bookshelves": "to-read, kindle", "Read Count": "0"},
		// The audiobook, by its title, read.
		map[string]string{"Title": "Red Rising (Red Rising Saga, #1)", "Author": "Pierce Brown", "My Rating": "5", "Date Read": "2025/12/20", "Exclusive Shelf": "read", "Bookshelves": "audio", "Read Count": "1"},
	)
	report := env.importOK(body, "")
	if report.Rows != 2 || report.Matched != 2 || report.Works != 1 || report.Unmatched != 0 {
		t.Fatalf("report = %+v", report.goodreadsCounts)
	}
	env.expectYou(ids["Red Rising"], "", &ReadingYou{Rating: 5, Finished: "2025-12", ReadCount: 1, Shelves: []string{"kindle", "audio"}, Status: "read", Source: "goodreads"})
}

func TestARefusedImportKeepsNothingAndSaysWhy(t *testing.T) {
	big := "Title,Author\n" + strings.Repeat("a", 9<<20) + ",b\n"
	var tooManyRows strings.Builder
	tooManyRows.WriteString("Title,Author\n")
	for i := 0; i < 20001; i++ {
		tooManyRows.WriteString("a,b\n")
	}
	for name, test := range map[string]struct {
		body    string
		query   string
		headers []string
		code    int
		want    string
	}{
		"a form":                    {body: grSample(), headers: []string{"Content-Type", "multipart/form-data; boundary=x"}, code: http.StatusUnsupportedMediaType, want: "request body"},
		"JSON":                      {body: `{"title":"MARKER","author":"x"}`, code: http.StatusBadRequest, want: "not a Goodreads export"},
		"an empty file":             {body: ``, code: http.StatusBadRequest, want: "not a Goodreads export"},
		"no title column":           {body: "Name,Author\nMARKER,x\n", code: http.StatusBadRequest, want: "Title column"},
		"nothing to know a book by": {body: "Title,Pages\nMARKER,1\n", code: http.StatusBadRequest, want: "Author or ISBN"},
		"a dryRun that is neither":  {body: grSample(), query: "?dryRun=maybe", code: http.StatusBadRequest, want: "dryRun"},
		"a bad profile":             {body: grSample(), headers: []string{jellyfinUserHeader, "nope"}, code: http.StatusBadRequest, want: "Jellyfin user"},
		"too many bytes":            {body: big, code: http.StatusRequestEntityTooLarge, want: "larger"},
		"too many rows":             {body: tooManyRows.String(), code: http.StatusRequestEntityTooLarge, want: "larger"},
	} {
		t.Run(name, func(t *testing.T) {
			env := newBookPageEnv(t, bookPageOptions{})
			got := env.importGoodreads(test.body, test.query, test.headers...)
			if got.Code != test.code || !strings.Contains(got.Body.String(), test.want) || strings.Contains(got.Body.String(), "MARKER") {
				t.Fatalf("= %d: %s", got.Code, got.Body.String())
			}
			if status, _ := env.importStatus(""); status.Imported {
				t.Error("a refused import was kept")
			}
			if _, err := os.Stat(env.youPath()); err == nil {
				t.Error("a refused import wrote a file")
			}
		})
	}
}

func TestAnImportIsNotMadeAgainstALibraryThatCouldNotBeRead(t *testing.T) {
	env := newBookPageEnv(t, bookPageOptions{})
	env.story.down.Store(true)
	// Every row would be missing, and would be kept so.
	got := env.importGoodreads(grSample(), "")
	if got.Code != http.StatusServiceUnavailable || !strings.Contains(got.Body.String(), `"retryable":true`) {
		t.Fatalf("= %d: %s", got.Code, got.Body.String())
	}
	if _, err := os.Stat(env.youPath()); err == nil {
		t.Error("an import against nothing was kept")
	}
	env.story.down.Store(false)
	if report := env.importOK(grSample(), ""); report.Matched != 4 {
		t.Errorf("after it came back = %+v", report.goodreadsCounts)
	}
}

func TestImportRoutesNeedTheReadingScopeAndAToken(t *testing.T) {
	env := newBookPageEnv(t, bookPageOptions{scopes: []string{"read"}})
	for _, method := range []string{http.MethodPost, http.MethodGet, http.MethodDelete} {
		if got := env.do(method, "/v1/reading/import/goodreads", grSample()); got.Code != http.StatusForbidden || !strings.Contains(got.Body.String(), "forbidden_scope") {
			t.Errorf("%s with no reading scope = %d: %s", method, got.Code, got.Body.String())
		}
		recorder := httptest.NewRecorder()
		env.handler.ServeHTTP(recorder, httptest.NewRequest(method, "/v1/reading/import/goodreads", strings.NewReader(grSample())))
		if recorder.Code != http.StatusUnauthorized {
			t.Errorf("%s with no token = %d", method, recorder.Code)
		}
	}
}

func TestAReportListsAtMostTwoHundredRowsButItsCountsAreWhole(t *testing.T) {
	env := newBookPageEnv(t, bookPageOptions{})
	rows := []map[string]string{grRedRising}
	for i := 0; i < 250; i++ {
		rows = append(rows, map[string]string{"Title": fmt.Sprintf("Unknown book %d", i), "Author": "Nobody"})
	}
	report := env.importOK(goodreadsCSV(rows...), "")
	if report.Rows != 251 || report.Matched != 1 || report.Unmatched != 250 || len(report.UnmatchedRows) != 200 || !report.Truncated {
		t.Fatalf("report = %+v, %d listed, truncated %v", report.goodreadsCounts, len(report.UnmatchedRows), report.Truncated)
	}
	if report.UnmatchedRows[0].Title != "Unknown book 0" || report.UnmatchedRows[199].Title != "Unknown book 199" {
		t.Errorf("the list is %q ... %q", report.UnmatchedRows[0].Title, report.UnmatchedRows[199].Title)
	}
	status, _ := env.importStatus("")
	if status.Unmatched != 250 || len(status.UnmatchedRows) != 200 || !status.Truncated {
		t.Errorf("status = %+v", status.goodreadsCounts)
	}
}

// ---- the matcher, without a hub ----

func TestTheMatcherFindsAWorkByISBNThenByTitleAndAuthorAndNeverGuesses(t *testing.T) {
	catalog := map[string]string{
		"isbn:9780441172719": "rw_dune1", // the ISBN-13 of a Dune
		"isbn:9780345539786": "rw_red",   // Red Rising's, and
		"isbn:0345539788":    "rw_dark",  // its ISBN-10, held by another work
		"isbn:0345539818":    "rw_son",   // a work known by the ISBN-10 spelling only
	}
	isbn := func(key string) (string, bool) {
		id, ok := catalog[key]
		return id, ok
	}
	matcher := newGoodreadsMatcher([]matchCandidate{
		{ID: "rw_dune1", Title: "Dune", Authors: []string{"Frank Herbert"}},
		{ID: "rw_dune2", Title: "Dune", Authors: []string{"Frank Herbert", "Scott Brick"}},
		{ID: "rw_dune3", Title: "Dune", Authors: []string{"Brian Herbert"}},
		{ID: "rw_dark", Title: "Dark Matter: A Novel", Authors: []string{"Blake Crouch"}},
		{ID: "rw_darkother", Title: "Dark Matter", Authors: []string{"Someone Else"}},
		{ID: "rw_pira", Title: "Piranesi (A Novel, #1)", Authors: []string{"Susanna Clarke"}},
		{ID: "rw_red", Title: "Red Rising", Authors: []string{"Pierce Brown"}},
	}, isbn)

	row := func(title string, authors []string, isbns ...string) readingdomain.GoodreadsRow {
		return readingdomain.GoodreadsRow{Title: title, Authors: authors, ISBNs: isbns}
	}
	cases := []struct {
		name         string
		row          readingdomain.GoodreadsRow
		id, by, miss string
	}{
		{"by title and author", row("Dune", []string{"Brian Herbert"}), "rw_dune3", "title_author", ""},
		{"two works with that title and author", row("Dune", []string{"Frank Herbert"}), "", "", "ambiguous"},
		{"an author written the other way round", row("Piranesi", []string{"Clarke, Susanna"}), "rw_pira", "title_author", ""},
		{"a series note on the export's title", row("Piranesi (Piranesi, #1)", []string{"Susanna Clarke"}), "rw_pira", "title_author", ""},
		{"a subtitle on the library's title", row("Dark Matter", []string{"Blake Crouch"}), "rw_dark", "title_author", ""},
		{"a title with another author's name on it", row("Dark Matter", []string{"Nobody At All"}), "", "", "not_in_library"},
		{"a title and no author", row("Piranesi", nil), "", "", "not_in_library"},
		{"not in the library", row("Never Heard Of It", []string{"Frank Herbert"}), "", "", "not_in_library"},
		{"the ISBN wins over the title", row("A different name", []string{"Nobody"}, "9780441172719"), "rw_dune1", "isbn", ""},
		{"the other spelling of an ISBN", row("x", nil, "9780345539816"), "rw_son", "isbn", ""},
		{"an ISBN held by two works", row("Dark Matter", []string{"Blake Crouch"}, "9780345539786"), "", "", "ambiguous"},
		{"an ISBN the library does not have falls to the title", row("Dune", []string{"Brian Herbert"}, "9780306406157"), "rw_dune3", "title_author", ""},
	}
	for _, c := range cases {
		id, by, miss := matcher.match(c.row)
		if id != c.id || by != c.by || miss != c.miss {
			t.Errorf("%s: got (%q, %q, %q), want (%q, %q, %q)", c.name, id, by, miss, c.id, c.by, c.miss)
		}
	}
}

func TestAuthorKeysCompareNamesHoweverTheyAreWritten(t *testing.T) {
	same := [][]string{
		{"Brandon Sanderson", "Sanderson, Brandon", "sanderson brandon", "BRANDON  SANDERSON", "Brandon Sanderson (Narrator)", "Brandon Sanderson (Goodreads Author)"},
		{"J.R.R. Tolkien", "Tolkien, J. R. R.", "j r r tolkien"},
	}
	for _, group := range same {
		want := authorKey(group[0])
		if want == "" {
			t.Fatalf("%q has no key", group[0])
		}
		for _, name := range group {
			if got := authorKey(name); got != want {
				t.Errorf("%q = %q, want %q", name, got, want)
			}
		}
	}
	for _, name := range []string{"", "   ", "(Narrator)", "()"} {
		if got := authorKey(name); got != "" {
			t.Errorf("%q = %q, want nothing", name, got)
		}
	}
	if authorKey("Brandon Sanderson") == authorKey("Brian Sanderson") {
		t.Error("two people have one key")
	}
	works := authorKeys([]string{"Frank Herbert", "", "(x)", "Scott Brick"})
	if !authorsOverlap([]string{"Herbert, Frank"}, works) || authorsOverlap([]string{"Brian Herbert"}, works) || authorsOverlap(nil, works) || authorsOverlap([]string{""}, []string{""}) {
		t.Errorf("overlap is wrong for %v", works)
	}
}

func TestStorytellerISBNsAreTheOnesThatSayTheyAre(t *testing.T) {
	book := storyteller.Book{Identifiers: []storyteller.Identifier{
		{Type: "isbn", Value: "978-0-7653-1178-8"},
		{Type: "ISBN-10", Value: "0765311781"},        // the same book, its other spelling
		{Scheme: "ISBN", Identifier: "9780345539786"}, // spelled in the other fields
		{Type: "amazon", Value: "0441172717"},         // an ASIN of a book is its ISBN-10
		{Type: "goodreads", Value: "9780441172719"},   // another service's number is not one
		{Type: "isbn", Value: "9780765311789"},        // a check digit that does not fit
		{Value: "9780306406157"},                      // says nothing, and is one
		{Value: "not a number"},
		{Type: "isbn"},
	}}
	got := storytellerISBNs(book)
	want := []string{"9780765311788", "9780345539786", "9780441172719", "9780306406157"}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("isbns = %v, want %v", got, want)
	}
}
