package api

// A profile's "you" for a book (#39): what an app sets, what the Goodreads import says,
// and how the two come together.

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"reflect"
	"runtime"
	"strings"
	"testing"
	"time"
)

func (e *bookPageEnv) patch(id, body string, headers ...string) *httptest.ResponseRecorder {
	e.t.Helper()
	return e.do(http.MethodPatch, "/v1/reading/works/"+id+"/you", body, headers...)
}

// patchYou makes a change that must be accepted and returns what is now true.
func (e *bookPageEnv) patchYou(id, body string, headers ...string) *ReadingYou {
	e.t.Helper()
	got := e.patch(id, body, headers...)
	if got.Code != http.StatusOK {
		e.t.Fatalf("PATCH %s = %d: %s", body, got.Code, got.Body.String())
	}
	var response ReadingYouResponse
	if err := json.Unmarshal(got.Body.Bytes(), &response); err != nil {
		e.t.Fatal(err)
	}
	if response.WorkID != id {
		e.t.Errorf("workId = %q, want %q", response.WorkID, id)
	}
	if !strings.Contains(got.Header().Get("Vary"), jellyfinUserHeader) {
		e.t.Errorf("Vary = %q", got.Header().Get("Vary"))
	}
	return response.You
}

func (e *bookPageEnv) expectYou(id, profile string, want *ReadingYou) {
	e.t.Helper()
	if got := e.view(id, profile).You; !reflect.DeepEqual(got, want) {
		e.t.Fatalf("you = %+v, want %+v", got, want)
	}
}

func TestAnAppSetsAndClearsTheRatingTheMonthFinishedAndTheReadCount(t *testing.T) {
	env := newBookPageEnv(t, bookPageOptions{})
	id := env.work("The Final Empire")
	env.expectYou(id, "", nil)

	// A month finished is a book read, once unless the person says otherwise.
	want := &ReadingYou{Rating: 4, Finished: "2026-09", ReadCount: 1, Shelves: []string{}, Status: "read", Source: "app"}
	if got := env.patchYou(id, `{"rating":4,"finished":"2026-09"}`); !reflect.DeepEqual(got, want) {
		t.Fatalf("after the first change = %+v", got)
	}
	env.expectYou(id, "", want)

	// Keys that are not in the body are left alone.
	want = &ReadingYou{Rating: 4, Finished: "2026-09", ReadCount: 3, Shelves: []string{}, Status: "read", Source: "app"}
	if got := env.patchYou(id, `{"readCount":3}`); !reflect.DeepEqual(got, want) {
		t.Fatalf("after a count = %+v", got)
	}

	// Another month finished is not another time read.
	want.Finished = "2026-10"
	if got := env.patchYou(id, `{"finished":"2026-10"}`); !reflect.DeepEqual(got, want) {
		t.Fatalf("after another month = %+v", got)
	}

	// null clears.
	want.Rating = 0
	if got := env.patchYou(id, `{"rating":null}`); !reflect.DeepEqual(got, want) {
		t.Fatalf("after clearing the rating = %+v", got)
	}
	want = &ReadingYou{ReadCount: 3, Shelves: []string{}, Source: "app"}
	if got := env.patchYou(id, `{"finished":null}`); !reflect.DeepEqual(got, want) {
		t.Fatalf("after clearing the month = %+v", got)
	}
	env.expectYou(id, "", want)

	// Nothing left is no "you", in the answer and on the page.
	cleared := env.patch(id, `{"readCount":null}`)
	if cleared.Code != http.StatusOK || !strings.Contains(cleared.Body.String(), `"you":null`) {
		t.Fatalf("clearing the last = %d: %s", cleared.Code, cleared.Body.String())
	}
	env.expectYou(id, "", nil)
}

func TestAMonthFinishedWithACountAsksForNoAutomaticOne(t *testing.T) {
	env := newBookPageEnv(t, bookPageOptions{})
	id := env.work("Piranesi")
	got := env.patchYou(id, `{"finished":"2026-05","readCount":4}`)
	if got == nil || got.ReadCount != 4 || got.Finished != "2026-05" {
		t.Fatalf("you = %+v", got)
	}
}

func TestChangesThatAreNotAcceptedAreRefusedWholeAndKeepNothing(t *testing.T) {
	env := newBookPageEnv(t, bookPageOptions{})
	id := env.work("The Final Empire")
	for _, body := range []string{
		``, `not json`, `[]`, `null`, `"rating"`, `{}`,
		`{"rating":0}`, `{"rating":6}`, `{"rating":-1}`, `{"rating":3.5}`, `{"rating":"4"}`, `{"rating":true}`, `{"rating":[4]}`,
		`{"finished":"2026-13"}`, `{"finished":"2026-00"}`, `{"finished":"1899-12"}`, `{"finished":"2026-11"}`, `{"finished":"2027-01"}`,
		`{"finished":"2026-10-01"}`, `{"finished":"2026/09"}`, `{"finished":2026}`, `{"finished":""}`, `{"finished":true}`,
		`{"readCount":0}`, `{"readCount":100}`, `{"readCount":-1}`, `{"readCount":1.5}`, `{"readCount":"2"}`,
		`{"stars":3}`, `{"rating":4,"stars":3}`, `{"rating":4,"finished":"2026-13"}`, `{"Rating":4}`,
	} {
		got := env.patch(id, body)
		if got.Code != http.StatusBadRequest || !strings.Contains(got.Body.String(), "invalid_request") {
			t.Errorf("%q = %d: %s", body, got.Code, got.Body.String())
		}
	}
	if _, err := os.Stat(env.youPath()); err == nil {
		t.Error("a refused change was written down")
	}
	env.expectYou(id, "", nil)
}

func TestTheEdgesOfWhatIsAcceptedAreAccepted(t *testing.T) {
	env := newBookPageEnv(t, bookPageOptions{})
	id := env.work("The Final Empire")
	// This month is not the future (the clock says October 2026); 1900 is not too old.
	for _, body := range []string{`{"rating":1}`, `{"rating":5}`, `{"finished":"2026-10"}`, `{"finished":"1900-01"}`, `{"readCount":1}`, `{"readCount":99}`, ` {"rating" : 3 } `} {
		if got := env.patch(id, body); got.Code != http.StatusOK {
			t.Errorf("%q = %d: %s", body, got.Code, got.Body.String())
		}
	}
	env.clock.advance(31 * 24 * time.Hour)
	if got := env.patch(id, `{"finished":"2026-11"}`); got.Code != http.StatusOK {
		t.Errorf("a month that has come = %d: %s", got.Code, got.Body.String())
	}
}

func TestChangingYouNeedsTheReadingScopeAndAKnownWork(t *testing.T) {
	env := newBookPageEnv(t, bookPageOptions{})
	valid := "rw_" + strings.Repeat("0", 32)
	if got := env.patch("rw_nothex", `{"rating":3}`); got.Code != http.StatusBadRequest {
		t.Errorf("a malformed id = %d", got.Code)
	}
	if got := env.patch("not-an-id", `{"rating":3}`); got.Code != http.StatusBadRequest {
		t.Errorf("a malformed id = %d", got.Code)
	}
	if got := env.patch(valid, `{"rating":3}`); got.Code != http.StatusNotFound {
		t.Errorf("an unknown work = %d: %s", got.Code, got.Body.String())
	}
	if got := env.patch(env.work("Piranesi"), `{"rating":3}`, jellyfinUserHeader, "nope"); got.Code != http.StatusBadRequest {
		t.Errorf("a malformed profile = %d", got.Code)
	}

	request := httptest.NewRequest(http.MethodPatch, "/v1/reading/works/"+valid+"/you", strings.NewReader(`{"rating":3}`))
	recorder := httptest.NewRecorder()
	env.handler.ServeHTTP(recorder, request)
	if recorder.Code != http.StatusUnauthorized {
		t.Errorf("no token = %d", recorder.Code)
	}

	readOnly := newBookPageEnv(t, bookPageOptions{scopes: []string{"read"}})
	if got := readOnly.patch(valid, `{"rating":3}`); got.Code != http.StatusForbidden || !strings.Contains(got.Body.String(), "forbidden_scope") {
		t.Errorf("a token with no reading scope = %d: %s", got.Code, got.Body.String())
	}
}

func TestEachProfileHasItsOwnYou(t *testing.T) {
	env := newBookPageEnv(t, bookPageOptions{})
	id := env.work("Piranesi")
	env.patchYou(id, `{"rating":5}`, jellyfinUserHeader, bpProfileA)
	env.patchYou(id, `{"rating":2,"readCount":7}`, jellyfinUserHeader, bpProfileB)

	env.expectYou(id, bpProfileA, &ReadingYou{Rating: 5, Shelves: []string{}, Source: "app"})
	env.expectYou(id, bpProfileB, &ReadingYou{Rating: 2, ReadCount: 7, Shelves: []string{}, Source: "app"})
	// The hub's default profile is a third.
	env.expectYou(id, "", nil)
	// A header in capitals is the same profile.
	env.expectYou(id, strings.ToUpper(bpProfileA), &ReadingYou{Rating: 5, Shelves: []string{}, Source: "app"})
}

func TestYouSurvivesARestartAndTheFileIsPrivateAndHoldsOnlyWhatWasSet(t *testing.T) {
	env := newBookPageEnv(t, bookPageOptions{})
	id := env.work("Piranesi")
	want := env.patchYou(id, `{"rating":3,"finished":"2026-08"}`, jellyfinUserHeader, bpProfileA)

	env.start()
	env.expectYou(id, bpProfileA, want)

	info, err := os.Stat(env.youPath())
	if err != nil {
		t.Fatal(err)
	}
	if runtime.GOOS != "windows" && info.Mode().Perm() != 0o600 {
		t.Errorf("the file is %v, want 0600", info.Mode().Perm())
	}
	if filepath.Dir(env.youPath()) != filepath.Dir(env.cfg.Server.OfflineRegistry) || filepath.Base(env.youPath()) != "reading-you.json" {
		t.Errorf("the file is %s, beside the other registries", env.youPath())
	}
	if _, err := os.Stat(env.youPath() + ".tmp"); err == nil {
		t.Error("a temporary file was left behind")
	}
}

func TestAFileThatCannotBeReadIsKeptAsItIsAndNothingIsWrittenOverIt(t *testing.T) {
	for name, content := range map[string]string{
		"not JSON":            "{this was cut off",
		"another version":     `{"version":2,"profiles":{}}`,
		"profiles are absent": `{"version":1}`,
	} {
		t.Run(name, func(t *testing.T) {
			env := newBookPageEnv(t, bookPageOptions{youFile: content})
			id := env.work("Piranesi")

			// The page is served, as if nobody had said anything.
			env.expectYou(id, "", nil)
			if got := env.patch(id, `{"rating":3}`); got.Code != http.StatusInternalServerError || !strings.Contains(got.Body.String(), "could not be saved") {
				t.Errorf("a change = %d: %s", got.Code, got.Body.String())
			}
			if got := env.do(http.MethodPost, "/v1/reading/import/goodreads", grSample(), "Content-Type", "text/csv"); got.Code != http.StatusInternalServerError {
				t.Errorf("an import = %d: %s", got.Code, got.Body.String())
			}
			if got := env.do(http.MethodPost, "/v1/reading/import/goodreads?dryRun=true", grSample(), "Content-Type", "text/csv"); got.Code != http.StatusOK {
				t.Errorf("a dry run writes nothing, and = %d: %s", got.Code, got.Body.String())
			}
			if saved, err := os.ReadFile(env.youPath()); err != nil || string(saved) != content {
				t.Errorf("the file is now %q (%v)", saved, err)
			}
		})
	}
}

// ---- how an import record and an app's edit come together ----

func TestMergeYou(t *testing.T) {
	imported := func() *youRecord {
		return &youRecord{Rating: 5, Average: 4.2, Finished: "2026-09", ReadCount: 2, Shelves: []string{"favorites"}, Status: "read"}
	}
	cleared := &editInt{Cleared: true}
	cases := []struct {
		name   string
		record *youRecord
		edit   *youEdit
		want   *ReadingYou
	}{
		{"nothing", nil, nil, nil},
		{"an empty edit", nil, &youEdit{}, nil},
		{"the import alone", imported(), nil,
			&ReadingYou{Rating: 5, Finished: "2026-09", ReadCount: 2, Shelves: []string{"favorites"}, Status: "read", Source: "goodreads"}},
		{"an edit replaces a value", imported(), &youEdit{Rating: &editInt{Value: 3}},
			&ReadingYou{Rating: 3, Finished: "2026-09", ReadCount: 2, Shelves: []string{"favorites"}, Status: "read", Source: "app"}},
		{"an edit can clear a value", imported(), &youEdit{Rating: cleared},
			&ReadingYou{Finished: "2026-09", ReadCount: 2, Shelves: []string{"favorites"}, Status: "read", Source: "app"}},
		{"a cleared month is not read", imported(), &youEdit{Finished: &editString{Cleared: true}},
			&ReadingYou{Rating: 5, ReadCount: 2, Shelves: []string{"favorites"}, Source: "app"}},
		{"a month on a to-read book", &youRecord{Status: "to-read", Shelves: []string{"magic"}}, &youEdit{Finished: &editString{Value: "2026-01"}},
			&ReadingYou{Finished: "2026-01", Shelves: []string{"magic"}, Status: "read", Source: "app"}},
		{"a month on a book being read", &youRecord{Status: "currently-reading"}, &youEdit{Finished: &editString{Value: "2026-01"}},
			&ReadingYou{Finished: "2026-01", Shelves: []string{}, Status: "currently-reading", Source: "app"}},
		{"clearing a month on a to-read book", &youRecord{Status: "to-read"}, &youEdit{Finished: &editString{Cleared: true}},
			&ReadingYou{Shelves: []string{}, Status: "to-read", Source: "app"}},
		{"an edit with no import", nil, &youEdit{Rating: &editInt{Value: 4}},
			&ReadingYou{Rating: 4, Shelves: []string{}, Source: "app"}},
		{"a record of nothing but the average says nothing", &youRecord{Average: 4.1}, nil, nil},
		{"everything cleared, from a rating alone", &youRecord{Rating: 5}, &youEdit{Rating: cleared}, nil},
		{"a count from an edit", imported(), &youEdit{ReadCount: &editInt{Value: 9}},
			&ReadingYou{Rating: 5, Finished: "2026-09", ReadCount: 9, Shelves: []string{"favorites"}, Status: "read", Source: "app"}},
	}
	for _, c := range cases {
		if got := mergeYou(c.record, c.edit); !reflect.DeepEqual(got, c.want) {
			t.Errorf("%s:\n got %+v\nwant %+v", c.name, got, c.want)
		}
	}

	// What is returned is the caller's own: changing its shelves leaves the record's.
	record := imported()
	got := mergeYou(record, nil)
	got.Shelves[0] = "changed"
	if record.Shelves[0] != "favorites" {
		t.Error("the merge shares the record's shelves")
	}
}

func TestParseYouMonth(t *testing.T) {
	for month, want := range map[string]bool{
		"2026-10": true, "2026-09": true, "1900-01": true, "2000-12": true,
		"2026-11": false, "2027-01": false, "1899-12": false, "2026-13": false, "2026-00": false,
		"2026-9": false, "26-09": false, "2026-09-01": false, "": false, "2026 09": false, " 2026-09": false,
	} {
		if got := parseYouMonth(month, "2026-10"); got != want {
			t.Errorf("%q = %v, want %v", month, got, want)
		}
	}
}

// ---- the store ----

func TestTheYouFileLivesBesideTheOtherRegistries(t *testing.T) {
	if got := readingYouPath(filepath.Join("data", "offline-grants.json")); got != filepath.Join("data", "reading-you.json") {
		t.Errorf("path = %q", got)
	}
	if got := readingYouPath("  "); got != "" {
		t.Errorf("no registry is no file, and = %q", got)
	}
}

func TestTheYouStoreKeepsEditsAndImportsAndPrunesWhatIsEmpty(t *testing.T) {
	path := filepath.Join(t.TempDir(), "reading-you.json")
	store := newReadingYouStore(path)

	record, edit, err := store.update("p1", "rw_a", func(_ *youRecord, edit *youEdit) { edit.Rating = &editInt{Value: 4} })
	if err != nil || record != nil || edit == nil || edit.Rating.Value != 4 {
		t.Fatalf("update = %+v %+v %v", record, edit, err)
	}
	imported := &youImport{ImportedAt: "2026-10-07T12:00:00Z", Works: map[string]youRecord{"rw_a": {Rating: 2, Shelves: []string{"x"}}}}
	if err := store.replaceImport("p1", imported); err != nil {
		t.Fatal(err)
	}

	// The change is shown the import's record to decide by, and a copy of it.
	if _, _, err := store.update("p1", "rw_a", func(record *youRecord, _ *youEdit) {
		if record == nil || record.Rating != 2 {
			t.Errorf("record = %+v", record)
		}
		record.Shelves[0] = "changed"
	}); err != nil {
		t.Fatal(err)
	}
	if got, _ := store.view("p1", "rw_a"); got.Shelves[0] != "x" {
		t.Error("a change reached into the stored record")
	}

	// A second store reads what the first wrote.
	other := newReadingYouStore(path)
	if got, edit := other.view("p1", "rw_a"); got == nil || got.Rating != 2 || edit == nil || edit.Rating.Value != 4 {
		t.Fatalf("read back = %+v %+v", got, edit)
	}
	if got := other.importOf("p1"); got == nil || got.ImportedAt != imported.ImportedAt {
		t.Errorf("import read back = %+v", got)
	}
	if got := other.importOf("p2"); got != nil {
		t.Errorf("another profile's import = %+v", got)
	}

	// Emptying a profile removes it.
	if _, _, err := store.update("p1", "rw_a", func(_ *youRecord, edit *youEdit) { *edit = youEdit{} }); err != nil {
		t.Fatal(err)
	}
	if err := store.replaceImport("p1", nil); err != nil {
		t.Fatal(err)
	}
	saved, err := os.ReadFile(path)
	if err != nil || strings.Contains(string(saved), "p1") {
		t.Errorf("the file still holds the profile: %v %s", err, saved)
	}
}

func TestAChangeThatCannotBeSavedIsNotKept(t *testing.T) {
	// The folder it would be written in is a file.
	blocker := filepath.Join(t.TempDir(), "blocker")
	if err := os.WriteFile(blocker, []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
	store := newReadingYouStore(filepath.Join(blocker, "inside", "reading-you.json"))
	if _, _, err := store.update("p", "rw_a", func(_ *youRecord, edit *youEdit) { edit.Rating = &editInt{Value: 4} }); err == nil {
		t.Fatal("the change was saved to a place that cannot hold it")
	}
	if _, edit := store.view("p", "rw_a"); edit != nil {
		t.Errorf("an unsaved change is in the store: %+v", edit)
	}
	if err := store.replaceImport("p", &youImport{Works: map[string]youRecord{"rw_a": {Rating: 1}}}); err == nil {
		t.Fatal("the import was saved to a place that cannot hold it")
	}
	if store.importOf("p") != nil {
		t.Error("an unsaved import is in the store")
	}
}

func TestAStoreWithNoPathKeepsItsDataInMemoryOnly(t *testing.T) {
	store := newReadingYouStore("")
	if _, _, err := store.update("p", "rw_a", func(_ *youRecord, edit *youEdit) { edit.ReadCount = &editInt{Value: 2} }); err != nil {
		t.Fatal(err)
	}
	if _, edit := store.view("p", "rw_a"); edit == nil || edit.ReadCount.Value != 2 {
		t.Errorf("edit = %+v", edit)
	}
}
