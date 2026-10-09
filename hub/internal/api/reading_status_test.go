package api

// A book's reading status (#63): the one word the apps show for where a book stands, worked
// out from what the hub already keeps (a month finished, the Goodreads shelf, a place begun)
// and outranked by what the person chose.

import (
	"encoding/json"
	"net/http"
	"strings"
	"testing"
	"time"
)

func TestTheEffectiveStatusIsTheChoiceElseTheStrongestSign(t *testing.T) {
	read := &ReadingYou{Status: "read", Shelves: []string{}}
	for _, tc := range []struct {
		name     string
		you      *ReadingYou
		progress *ReadingProgress
		want     string
	}{
		{"nothing to say", nil, nil, ""},
		{"a you with no sign", &ReadingYou{Shelves: []string{}, Rating: 4}, nil, ""},
		{"a place begun", nil, &ReadingProgress{Percentage: 12}, "reading"},
		{"a place at the very start is not begun", nil, &ReadingProgress{Percentage: 0}, ""},
		{"read to the end", nil, &ReadingProgress{Percentage: 100, Completed: true}, "finished"},
		{"a month finished", &ReadingYou{Finished: "2026-09"}, nil, "finished"},
		{"an import that says read", read, nil, "finished"},
		{"an import that says to read", &ReadingYou{Status: "to-read"}, nil, "want"},
		{"an import that says currently reading", &ReadingYou{Status: "currently-reading"}, nil, "reading"},
		// Finished is stronger than a place begun: a book read twice is on its second pass, and it is still read.
		{"a month finished and a place begun", &ReadingYou{Finished: "2026-09"}, &ReadingProgress{Percentage: 3}, "finished"},
		{"read to the end beats to-read", &ReadingYou{Status: "to-read"}, &ReadingProgress{Completed: true}, "finished"},
		{"to-read beats a place begun", &ReadingYou{Status: "to-read"}, &ReadingProgress{Percentage: 4}, "want"},
		// The person's choice beats every guess, in both directions.
		{"chosen not reading beats a place begun", &ReadingYou{Chosen: "not-reading"}, &ReadingProgress{Percentage: 40}, "not-reading"},
		{"chosen reading beats read to the end", &ReadingYou{Chosen: "reading"}, &ReadingProgress{Percentage: 100, Completed: true}, "reading"},
		{"chosen reading beats a month finished", &ReadingYou{Chosen: "reading", Finished: "2026-09"}, nil, "reading"},
		{"chosen want beats an import that says read", &ReadingYou{Chosen: "want", Status: "read"}, nil, "want"},
		{"chosen finished with no month", &ReadingYou{Chosen: "finished"}, nil, "finished"},
		// Wanted and then opened is being read; wanted again after finishing is still wanted.
		{"chosen want and a place begun is reading", &ReadingYou{Chosen: "want"}, &ReadingProgress{Percentage: 20}, "reading"},
		{"chosen want and read to the end is still want", &ReadingYou{Chosen: "want"}, &ReadingProgress{Percentage: 100, Completed: true}, "want"},
		{"chosen not reading stays with a place begun", &ReadingYou{Chosen: "not-reading"}, &ReadingProgress{Percentage: 20}, "not-reading"},
		{"a word nobody can choose is no choice", &ReadingYou{Chosen: "abandoned", Status: "to-read"}, nil, "want"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if got := effectiveReadingStatus(tc.you, tc.progress); got != tc.want {
				t.Errorf("status = %q, want %q", got, tc.want)
			}
		})
	}
}

// statusOf is the status a work's page shows now.
func (e *bookPageEnv) statusOf(id, profile string) string {
	e.t.Helper()
	return e.view(id, profile).Status
}

// chooseStatus makes a change that must be accepted and returns the response.
func (e *bookPageEnv) chooseStatus(id, body string, headers ...string) ReadingYouResponse {
	e.t.Helper()
	got := e.patch(id, body, headers...)
	if got.Code != http.StatusOK {
		e.t.Fatalf("PATCH %s = %d: %s", body, got.Code, got.Body.String())
	}
	var response ReadingYouResponse
	if err := json.Unmarshal(got.Body.Bytes(), &response); err != nil {
		e.t.Fatal(err)
	}
	return response
}

func TestAnAppChoosesAReadingStatusAndTheWorkSaysSo(t *testing.T) {
	env := newBookPageEnv(t, bookPageOptions{})
	id := env.work("Piranesi")
	if got := env.statusOf(id, ""); got != "" {
		t.Fatalf("a book nobody has touched has the status %q", got)
	}

	for _, status := range []string{"want", "reading", "not-reading"} {
		response := env.chooseStatus(id, `{"status":"`+status+`"}`)
		if response.Status != status || response.You == nil || response.You.Chosen != status || response.You.Source != "app" {
			t.Fatalf("after %s: %+v you=%+v", status, response, response.You)
		}
		if got := env.statusOf(id, ""); got != status {
			t.Errorf("the work says %q after %s", got, status)
		}
		if got := env.view(id, "").You; got == nil || got.Chosen != status {
			t.Errorf("the work's you = %+v after %s", got, status)
		}
		// A status that is not finished is not a month finished or a time read.
		if you := env.view(id, "").You; you.Finished != "" || you.ReadCount != 0 {
			t.Errorf("%s made a finish: %+v", status, you)
		}
	}

	// null takes the choice back: with nothing else to go on there is nothing to say.
	response := env.chooseStatus(id, `{"status":null}`)
	if response.Status != "" || response.You != nil {
		t.Errorf("after clearing = %+v you=%+v", response, response.You)
	}
	if got := env.statusOf(id, ""); got != "" {
		t.Errorf("the work says %q after the choice was cleared", got)
	}
}

func TestFinishedByStatusIsThisMonthAndOneTimeRead(t *testing.T) {
	env := newBookPageEnv(t, bookPageOptions{})
	id := env.work("Piranesi")

	response := env.chooseStatus(id, `{"status":"finished"}`)
	want := &ReadingYou{Finished: "2026-10", ReadCount: 1, Shelves: []string{}, Status: "read", Chosen: "finished", Source: "app"}
	if response.Status != "finished" || response.You == nil || !youEqual(response.You, want) {
		t.Fatalf("finished = %+v you=%+v, want %+v", response, response.You, want)
	}

	// Reading it again later: the month and the count are the book's history and stay.
	env.chooseStatus(id, `{"status":"reading"}`)
	got := env.view(id, "").You
	if got.Finished != "2026-10" || got.ReadCount != 1 || got.Chosen != "reading" {
		t.Errorf("reading again = %+v", got)
	}
	// And finishing again keeps the month it already had instead of moving it to this one.
	env.clock.advance(40 * 24 * time.Hour)
	env.chooseStatus(id, `{"status":"finished"}`)
	if got := env.view(id, "").You; got.Finished != "2026-10" || got.ReadCount != 1 {
		t.Errorf("finishing again = %+v", got)
	}

	// A month said with the status is the month.
	other := env.work("Skyward")
	env.chooseStatus(other, `{"status":"finished","finished":"2026-03","readCount":2}`)
	if got := env.view(other, "").You; got.Finished != "2026-03" || got.ReadCount != 2 || got.Chosen != "finished" {
		t.Errorf("finished in March = %+v", got)
	}
}

func youEqual(a, b *ReadingYou) bool {
	left, _ := json.Marshal(a)
	right, _ := json.Marshal(b)
	return string(left) == string(right)
}

func TestAMonthFinishedWithoutAStatusIsAFinishAndTakesAChosenStatusWithIt(t *testing.T) {
	env := newBookPageEnv(t, bookPageOptions{})
	id := env.work("Piranesi")

	// An app that knows no statuses: Mark finished is a month, and the status follows from it.
	env.chooseStatus(id, `{"finished":"2026-09"}`)
	if got := env.statusOf(id, ""); got != "finished" {
		t.Errorf("a month finished says %q", got)
	}
	if you := env.view(id, "").You; you.Chosen != "" {
		t.Errorf("a finish that chose nothing has the choice %q", you.Chosen)
	}

	// Where a status was chosen, a month finished is a finish and takes its place.
	env.chooseStatus(id, `{"status":"not-reading"}`)
	if got := env.statusOf(id, ""); got != "not-reading" {
		t.Fatalf("not reading says %q", got)
	}
	env.chooseStatus(id, `{"finished":"2026-10"}`)
	if got := env.statusOf(id, ""); got != "finished" {
		t.Errorf("a finish over not-reading says %q", got)
	}

	// A finish taken away takes the status it made. Mark unread, from an app that knows no statuses.
	env.chooseStatus(id, `{"finished":null}`)
	if got := env.statusOf(id, ""); got != "" {
		t.Errorf("a finish taken away says %q", got)
	}
	// ... but not a status that is not a finish.
	env.chooseStatus(id, `{"status":"reading"}`)
	env.chooseStatus(id, `{"finished":null}`)
	if got := env.statusOf(id, ""); got != "reading" {
		t.Errorf("a finish taken away from a book being read says %q", got)
	}
}

func TestTheStatusFollowsTheGoodreadsImportUntilTheAppChoosesAndEachProfileHasItsOwn(t *testing.T) {
	env := newBookPageEnv(t, bookPageOptions{})
	toRead, read, current := env.work("Piranesi"), env.work("Skyward"), env.work("Golden Son")
	err := env.server.readingYou.replaceImport(bpProfileA, &youImport{ImportedAt: "2026-10-07T12:00:00Z", Works: map[string]youRecord{
		toRead:  {Status: "to-read", Shelves: []string{}},
		read:    {Status: "read", Finished: "2026-02", ReadCount: 1, Shelves: []string{}},
		current: {Status: "currently-reading", Shelves: []string{}},
	}})
	if err != nil {
		t.Fatal(err)
	}
	for id, want := range map[string]string{toRead: "want", read: "finished", current: "reading"} {
		if got := env.statusOf(id, bpProfileA); got != want {
			t.Errorf("%s imported says %q, want %q", id, got, want)
		}
		// Another profile has no import.
		if got := env.statusOf(id, bpProfileB); got != "" {
			t.Errorf("%s says %q to a profile with no import", id, got)
		}
	}

	// A choice outranks the import, and is the choice of that profile alone.
	env.chooseStatus(read, `{"status":"not-reading"}`, jellyfinUserHeader, bpProfileA)
	if got := env.statusOf(read, bpProfileA); got != "not-reading" {
		t.Errorf("a choice over an import says %q", got)
	}
	// The book is still read: the history is the import's, not the choice's.
	if you := env.view(read, bpProfileA).You; you.Finished != "2026-02" {
		t.Errorf("the month finished = %q", you.Finished)
	}
	if got := env.statusOf(read, bpProfileB); got != "" {
		t.Errorf("another profile's status = %q", got)
	}
	// Taking the choice back lets the import speak again.
	env.chooseStatus(read, `{"status":null}`, jellyfinUserHeader, bpProfileA)
	if got := env.statusOf(read, bpProfileA); got != "finished" {
		t.Errorf("after the choice was taken back = %q", got)
	}
}

func TestAStatusSurvivesARestart(t *testing.T) {
	env := newBookPageEnv(t, bookPageOptions{})
	id := env.work("Piranesi")
	env.chooseStatus(id, `{"status":"want"}`, jellyfinUserHeader, bpProfileA)
	env.start()
	if got := env.statusOf(id, bpProfileA); got != "want" {
		t.Errorf("after a restart = %q", got)
	}
}

func TestOnlyAStatusThatCanBeChosenIsAccepted(t *testing.T) {
	env := newBookPageEnv(t, bookPageOptions{})
	id := env.work("Piranesi")
	for _, body := range []string{
		`{"status":"abandoned"}`, `{"status":""}`, `{"status":3}`, `{"status":true}`, `{"status":["want"]}`,
		`{"status":"Want"}`, `{"status":"read"}`, `{"status":"to-read"}`,
	} {
		got := env.patch(id, body)
		if got.Code != http.StatusBadRequest || !strings.Contains(got.Body.String(), "invalid_request") {
			t.Errorf("%s = %d: %s", body, got.Code, got.Body.String())
		}
	}
	if got := env.statusOf(id, ""); got != "" {
		t.Errorf("a refused change was kept: %q", got)
	}
	// A status together with a bad value refuses the lot, so a half change is not made.
	if got := env.patch(id, `{"status":"want","rating":9}`); got.Code != http.StatusBadRequest {
		t.Errorf("a status with a bad rating = %d", got.Code)
	}
	if got := env.statusOf(id, ""); got != "" {
		t.Errorf("a half change was kept: %q", got)
	}
}

func TestTheShelfCarriesEachBooksStatusForTheProfileAsking(t *testing.T) {
	env := newBookPageEnv(t, bookPageOptions{})
	want, reading, finished := env.work("Piranesi"), env.work("Skyward"), env.work("Golden Son")
	env.chooseStatus(want, `{"status":"want"}`, jellyfinUserHeader, bpProfileA)
	env.chooseStatus(reading, `{"status":"reading"}`, jellyfinUserHeader, bpProfileA)
	env.chooseStatus(finished, `{"status":"finished"}`, jellyfinUserHeader, bpProfileA)

	shelf := func(profile string) (map[string]string, string) {
		got := env.do(http.MethodGet, "/v1/reading/libraries/storyteller:books/items?page=1&sort=title&direction=asc&view=works", "", jellyfinUserHeader, profile)
		if got.Code != http.StatusOK {
			t.Fatalf("shelf = %d: %s", got.Code, got.Body.String())
		}
		var page ReadingLibraryItemsResponse
		if err := json.Unmarshal(got.Body.Bytes(), &page); err != nil {
			t.Fatal(err)
		}
		statuses := map[string]string{}
		for _, item := range page.Items {
			statuses[item.ID] = item.Status
		}
		return statuses, got.Header().Get("Vary")
	}
	statuses, vary := shelf(bpProfileA)
	if !strings.Contains(vary, jellyfinUserHeader) {
		t.Errorf("the shelf varies by profile, and Vary = %q", vary)
	}
	for id, status := range map[string]string{want: "want", reading: "reading", finished: "finished"} {
		if statuses[id] != status {
			t.Errorf("the shelf says %q for %s, want %q", statuses[id], id, status)
		}
	}
	// Another profile sees none of it.
	others, _ := shelf(bpProfileB)
	for id, status := range others {
		if status != "" {
			t.Errorf("another profile's shelf says %q for %s", status, id)
		}
	}
	// A book with no status says nothing at all, rather than an empty word.
	raw := env.do(http.MethodGet, "/v1/reading/libraries/storyteller:books/items?page=1&view=works", "", jellyfinUserHeader, bpProfileB).Body.String()
	if strings.Contains(raw, `"status"`) {
		t.Error("a shelf with no statuses carries the key anyway")
	}
}

func TestAnAuthorsBooksCarryTheirStatusToo(t *testing.T) {
	env := newBookPageEnv(t, bookPageOptions{})
	id := env.work("Red Rising")
	env.chooseStatus(id, `{"status":"finished"}`, jellyfinUserHeader, bpProfileA)

	got := env.do(http.MethodGet, "/v1/reading/libraries/storyteller:books/authors", "", jellyfinUserHeader, bpProfileA)
	if got.Code != http.StatusOK {
		t.Fatalf("authors = %d: %s", got.Code, got.Body.String())
	}
	if !strings.Contains(got.Header().Get("Vary"), jellyfinUserHeader) {
		t.Errorf("Vary = %q", got.Header().Get("Vary"))
	}
	var page ReadingAuthorsResponse
	if err := json.Unmarshal(got.Body.Bytes(), &page); err != nil {
		t.Fatal(err)
	}
	found := false
	for _, author := range page.Authors {
		for _, item := range author.Items {
			if item.ID == id {
				found = true
				if item.Status != "finished" {
					t.Errorf("Red Rising among its author's books says %q", item.Status)
				}
			}
		}
	}
	if !found {
		t.Error("Red Rising is not among its author's books")
	}
}

func TestStartOverTakesAFinishedStatusWithTheFinishAndKeepsTheOthers(t *testing.T) {
	env := newBookPageEnv(t, bookPageOptions{})
	finished, putDown := env.work("Piranesi"), env.work("Skyward")
	env.chooseStatus(finished, `{"status":"finished"}`)
	env.chooseStatus(putDown, `{"status":"not-reading"}`)

	for _, id := range []string{finished, putDown} {
		if got := env.do(http.MethodPost, "/v1/reading/works/"+id+"/start-over", ""); got.Code != http.StatusOK {
			t.Fatalf("start over = %d: %s", got.Code, got.Body.String())
		}
	}
	// The finish is gone, and the status that was only the finish with it.
	if got := env.statusOf(finished, ""); got != "" {
		t.Errorf("a finished book started over says %q", got)
	}
	// Not reading says nothing of the place, so it stays.
	if got := env.statusOf(putDown, ""); got != "not-reading" {
		t.Errorf("a book put down and started over says %q", got)
	}
}
