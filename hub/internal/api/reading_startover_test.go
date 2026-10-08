package api

// Start over (#60): one route that takes a book back to not started, in every format and on
// every device. Storyteller has no way to delete a position (its routes are a GET and a POST
// that only ever replaces one place with another, and only a newer one), so the hub keeps a
// stamp for the book and treats a place written before it as gone. These tests never let a
// write or a delete reach Storyteller: its position table is checked to be as it was.

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"
)

func (e *positionEnv) epubPositionPath() string {
	return "/v1/reading/works/" + e.child + "/publications/12/position"
}

func (e *positionEnv) startOver(work string) *httptest.ResponseRecorder {
	e.t.Helper()
	return publicationRequest(e.handler, http.MethodPost, "/v1/reading/works/"+work+"/start-over", "")
}

func (e *positionEnv) startedOver() ReadingStartOverResponse {
	e.t.Helper()
	response := e.startOver(e.child)
	if response.Code != http.StatusOK {
		e.t.Fatalf("start over = %d: %s", response.Code, response.Body.String())
	}
	var body ReadingStartOverResponse
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
		e.t.Fatal(err)
	}
	return body
}

func (e *positionEnv) epubPosition() ReadingEpubPosition {
	e.t.Helper()
	response := publicationRequest(e.handler, http.MethodGet, e.epubPositionPath(), "")
	if response.Code != http.StatusOK {
		e.t.Fatalf("epub position = %d: %s", response.Code, response.Body.String())
	}
	var body ReadingEpubPosition
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
		e.t.Fatal(err)
	}
	return body
}

func (e *positionEnv) postEpub(body string) *httptest.ResponseRecorder {
	e.t.Helper()
	return publicationRequest(e.handler, http.MethodPost, e.epubPositionPath(), body)
}

func errorCodeOf(t *testing.T, recorder *httptest.ResponseRecorder) string {
	t.Helper()
	var body errorBody
	if err := json.Unmarshal(recorder.Body.Bytes(), &body); err != nil {
		t.Fatalf("not an error envelope: %v: %s", err, recorder.Body.String())
	}
	return body.Error.Code
}

const startOverEpubLocator = `{"href":"chapter-5.xhtml","type":"application/xhtml+xml","locations":{"progression":0.1,"totalProgression":0.4,"position":51}}`

func TestStartOverForgetsThePlaceInEveryFormatAndWritesNothingToStoryteller(t *testing.T) {
	env := newPositionEnv(t)
	name := trackedTagOrder[1]
	env.write(name, 500_000)
	if env.position() == nil || string(env.epubPosition().Locator) == "null" {
		t.Fatal("setup: the book has a place")
	}
	posts := env.positions.posts
	locator, stamp, _ := env.positions.stored()
	env.clock.set(time.UnixMilli(clockStart + 60_000))

	reset := env.startedOver()
	if !reset.OK || reset.Action != "start_over" || reset.WorkID != env.child || reset.ResetAt != clockStart+60_000 {
		t.Fatalf("answer = %+v", reset)
	}

	if got := env.read(); got.Position != nil || got.ResetAt != reset.ResetAt {
		t.Fatalf("the listening place after: %+v", got)
	}
	if got := env.epubPosition(); string(got.Locator) != "null" || got.Audio != nil || got.ResetAt != reset.ResetAt {
		t.Fatalf("the reading place after: %s audio=%v resetAt=%d", got.Locator, got.Audio, got.ResetAt)
	}
	// The book's own page says it was started over, so a device can tell its old place from a new one.
	var work ReadingWork
	if err := json.Unmarshal(env.get("/v1/reading/works/"+env.child).Body.Bytes(), &work); err != nil || work.ResetAt != reset.ResetAt || work.Progress != nil {
		t.Fatalf("work: resetAt %d progress %+v, %v", work.ResetAt, work.Progress, err)
	}

	// Storyteller was neither written to nor asked to delete anything: its table is as it was.
	if env.positions.posts != posts {
		t.Fatalf("the hub wrote %d positions to Storyteller", env.positions.posts-posts)
	}
	if again, at, has := env.positions.stored(); !has || at != stamp || !sameJSON(again, locator) {
		t.Fatalf("Storyteller's place changed: %s @ %d", again, at)
	}
}

func TestAPlaceWrittenAfterStartOverIsKeptEvenWhenStorytellerHoldsALaterStamp(t *testing.T) {
	env := newPositionEnv(t)
	// A phone whose clock runs ahead wrote the place: Storyteller holds a stamp later than the hub's clock.
	future := clockStart + 600_000
	env.positions.seed(`{"href":"chapter-9.xhtml","type":"application/xhtml+xml","locations":{"totalProgression":0.5}}`, future)
	if env.position() == nil {
		t.Fatal("setup: the place is read")
	}
	env.clock.set(time.UnixMilli(clockStart + 1000))

	reset := env.startedOver()
	if reset.ResetAt != future+1 {
		t.Fatalf("the stamp must be after the place it forgets: %d, want %d", reset.ResetAt, future+1)
	}
	if env.position() != nil {
		t.Fatal("a place stamped after the hub's clock is still a place that went away")
	}

	id := trackIDFor(trackedTagOrder[0])
	if response := env.post(placeBody(id, 1000, fmt.Sprintf(`,"resetSeen":%d`, reset.ResetAt))); response.Code != http.StatusOK {
		t.Fatalf("a write that knows of the reset = %d: %s", response.Code, response.Body.String())
	}
	if _, at, _ := env.positions.stored(); at < reset.ResetAt {
		t.Fatalf("the new place is stamped %d, before the reset %d", at, reset.ResetAt)
	}
	if got := env.position(); got == nil || got.OffsetMs != 1000 || got.TrackID != id {
		t.Fatalf("the new place: %+v", got)
	}
	if env.positions.refused != 0 {
		t.Fatalf("Storyteller refused %d writes", env.positions.refused)
	}
}

func TestAWriteMadeFromAnOlderPlaceIsRefusedWithItsOwnCode(t *testing.T) {
	env := newPositionEnv(t)
	name := trackedTagOrder[0]
	env.write(name, 1000)
	env.clock.set(time.UnixMilli(clockStart + 60_000))
	reset := env.startedOver()
	posts := env.positions.posts
	id := trackIDFor(name)

	for _, seen := range []int64{0, reset.ResetAt - 1} {
		stale := fmt.Sprintf(`,"resetSeen":%d`, seen)
		audio := env.post(placeBody(id, 5000, stale))
		if audio.Code != http.StatusConflict || errorCodeOf(t, audio) != "reading_position_reset" {
			t.Fatalf("audio, saw %d = %d %s", seen, audio.Code, audio.Body.String())
		}
		epub := env.postEpub(`{"locator":` + startOverEpubLocator + `,"resetSeen":` + strconv.FormatInt(seen, 10) + `}`)
		if epub.Code != http.StatusConflict || errorCodeOf(t, epub) != "reading_position_reset" {
			t.Fatalf("epub, saw %d = %d %s", seen, epub.Code, epub.Body.String())
		}
	}
	if env.positions.posts != posts {
		t.Fatal("a refused write reached Storyteller")
	}
	// It is not the "choose which position" conflict, which an app answers by asking the person.
	if got := env.post(placeBody(id, 5000, `,"resetSeen":0`)); errorCodeOf(t, got) == codeReadingPositionConflict {
		t.Fatal("a reset must not be reported as a conflict between two places")
	}

	// A device that has seen the reset writes as it likes.
	seen := fmt.Sprintf(`,"resetSeen":%d`, reset.ResetAt)
	if response := env.post(placeBody(id, 5000, seen)); response.Code != http.StatusOK {
		t.Fatalf("audio, knowing = %d: %s", response.Code, response.Body.String())
	}
	if response := env.postEpub(`{"locator":` + startOverEpubLocator + `,"resetSeen":` + strconv.FormatInt(reset.ResetAt, 10) + `}`); response.Code != http.StatusOK {
		t.Fatalf("epub, knowing = %d: %s", response.Code, response.Body.String())
	}
}

func TestAnOlderAppIsRefusedOnlyWhenItsBaseIsThePlaceThatWentAway(t *testing.T) {
	env := newPositionEnv(t)
	name := trackedTagOrder[0]
	id := trackIDFor(name)
	env.write(name, 1000)
	old, _, _ := env.positions.stored()
	env.clock.set(time.UnixMilli(clockStart + 60_000))
	env.startedOver()

	// An app that does not send what reset it has seen, writing from the place that is gone.
	epub := env.postEpub(`{"locator":` + startOverEpubLocator + `,"checkBase":true,"expectedLocator":` + string(old) + `}`)
	if epub.Code != http.StatusConflict || errorCodeOf(t, epub) != "reading_position_reset" {
		t.Fatalf("epub from the old place = %d %s", epub.Code, epub.Body.String())
	}
	audio := env.post(placeBody(id, 5000, `,"expected":{"trackId":"`+id+`","offsetMs":1000}`))
	if audio.Code != http.StatusConflict || errorCodeOf(t, audio) != "reading_position_reset" {
		t.Fatalf("audio from the old place = %d %s", audio.Code, audio.Body.String())
	}

	// The same app writing from nothing (it read the book after the reset) is kept.
	if response := env.postEpub(`{"locator":` + startOverEpubLocator + `,"checkBase":true,"expectedLocator":null}`); response.Code != http.StatusOK {
		t.Fatalf("epub from nothing = %d: %s", response.Code, response.Body.String())
	}
	// Another device moved the book since, and that is the ordinary conflict again.
	moved := env.post(placeBody(id, 5000, `,"expected":{"trackId":"`+id+`","offsetMs":1000}`))
	if moved.Code != http.StatusConflict || errorCodeOf(t, moved) != codeReadingPositionConflict {
		t.Fatalf("audio after another device moved it = %d %s", moved.Code, moved.Body.String())
	}
}

func TestStartOverTakesAwayTheFinishedMonthAndKeepsTheRestForOneProfile(t *testing.T) {
	env := newBookPageEnv(t, bookPageOptions{})
	id := env.work("The Final Empire")
	env.patchYou(id, `{"rating":4,"finished":"2026-09","readCount":2}`)
	env.patchYou(id, `{"finished":"2026-08"}`, jellyfinUserHeader, bpProfileB)

	response := env.do(http.MethodPost, "/v1/reading/works/"+id+"/start-over", "")
	if response.Code != http.StatusOK {
		t.Fatalf("start over = %d: %s", response.Code, response.Body.String())
	}
	if !strings.Contains(response.Header().Get("Vary"), jellyfinUserHeader) {
		t.Errorf("the answer varies by profile, and Vary = %q", response.Header().Get("Vary"))
	}
	var body ReadingStartOverResponse
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if body.You == nil || body.You.Finished != "" || body.You.Status != "" || body.You.Rating != 4 || body.You.ReadCount != 2 {
		t.Fatalf("you after = %+v: the finish is gone, the rating and the count stay", body.You)
	}
	if got := env.view(id, "").You; got == nil || got.Finished != "" || got.Rating != 4 || got.ReadCount != 2 {
		t.Fatalf("the page after = %+v", got)
	}
	// What another profile marked finished is theirs.
	if got := env.view(id, bpProfileB).You; got == nil || got.Finished != "2026-08" {
		t.Fatalf("another profile's finish = %+v", got)
	}
	// And the work says it was started over, through a restart.
	first := env.view(id, "").ResetAt
	if first == 0 {
		t.Fatal("the work does not say it was started over")
	}
	env.start()
	if again := env.view(id, "").ResetAt; again != first {
		t.Fatalf("after a restart %d, was %d", again, first)
	}
}

func TestStartOverHidesTheFinishedMonthOfAGoodreadsImportAndKeepsItsRatingAndShelves(t *testing.T) {
	env := newBookPageEnv(t, bookPageOptions{})
	env.importOK(goodreadsCSV(grRedRising), "")
	id := env.work("Red Rising")
	before := env.view(id, "").You
	if before == nil || before.Finished != "2026-09" || before.Status != "read" || before.Rating != 5 || before.ReadCount != 2 || len(before.Shelves) == 0 {
		t.Fatalf("setup: the import says %+v", before)
	}
	if got := env.do(http.MethodPost, "/v1/reading/works/"+id+"/start-over", ""); got.Code != http.StatusOK {
		t.Fatalf("start over = %d: %s", got.Code, got.Body.String())
	}
	after := env.view(id, "").You
	if after == nil || after.Finished != "" || after.Status != "" || after.Rating != 5 || after.ReadCount != 2 || len(after.Shelves) != len(before.Shelves) {
		t.Fatalf("after = %+v, was %+v: the finish goes, the rating, the count and the shelves stay", after, before)
	}
	// Importing again does not bring the finish back: the person took it away.
	env.importOK(goodreadsCSV(grRedRising), "")
	if again := env.view(id, "").You; again == nil || again.Finished != "" {
		t.Fatalf("after another import = %+v", again)
	}
}

func TestStartOverNeedsAKnownWorkAndTheReadingScope(t *testing.T) {
	env := newBookPageEnv(t, bookPageOptions{})
	for path, want := range map[string]int{
		"/v1/reading/works/not-a-work/start-over":                         http.StatusBadRequest,
		"/v1/reading/works/rw_00000000000000000000000000000000/start-over": http.StatusNotFound,
	} {
		if got := env.do(http.MethodPost, path, ""); got.Code != want {
			t.Errorf("%s = %d, want %d: %s", path, got.Code, want, got.Body.String())
		}
	}
	id := env.work("The Final Empire")
	if got := env.do(http.MethodGet, "/v1/reading/works/"+id+"/start-over", ""); got.Code != http.StatusMethodNotAllowed && got.Code != http.StatusNotFound {
		t.Errorf("a GET = %d", got.Code)
	}
	readOnly := newBookPageEnv(t, bookPageOptions{scopes: []string{"read"}})
	if got := readOnly.do(http.MethodPost, "/v1/reading/works/rw_00000000000000000000000000000000/start-over", ""); got.Code != http.StatusForbidden {
		t.Errorf("without the reading scope = %d: %s", got.Code, got.Body.String())
	}
}

// startOverStoryteller is Storyteller with one title in two books, an ebook and an audiobook,
// each with its own position table as the real one keeps it, and the place in its book list.
type startOverStoryteller struct {
	*httptest.Server
	tables map[string]*storytellerPositions
	posts  int
}

func newStartOverStoryteller(t *testing.T, audiobookTitle string) *startOverStoryteller {
	t.Helper()
	fake := &startOverStoryteller{tables: map[string]*storytellerPositions{"12": {}, "13": {}}}
	series := `"series":[{"uuid":"series-red","name":"Red Rising","position":1}]`
	books := map[string]string{
		"12": `"id":12,"uuid":"book-12","title":"Red Rising","authors":[{"name":"Pierce Brown"}],` + series + `,"ebook":{"uuid":"ebook-12","pageCount":400}`,
		"13": `"id":13,"uuid":"book-13","title":"` + audiobookTitle + `","authors":[{"name":"Pierce Brown"}],"narrators":[{"name":"Tim Gerard Reynolds"}],` + series + `,"audiobook":{"uuid":"audio-13","duration":7200}`,
	}
	withPlace := func(id string) string {
		table := fake.tables[id]
		locator, stamp, has := table.stored()
		raw := "{" + books[id]
		if has {
			raw += `,"position":{"uuid":"p-` + id + `","locator":` + string(locator) + `,"timestamp":` + strconv.FormatInt(stamp, 10) + `}`
		}
		return raw + "}"
	}
	fake.Server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.URL.Path == "/api/v2/token":
			_, _ = io.WriteString(w, `{"access_token":"story-token","token_type":"Bearer","expires_in":3600}`)
		case r.URL.Path == "/api/v2/books":
			_, _ = io.WriteString(w, "["+withPlace("12")+","+withPlace("13")+"]")
		case r.URL.Path == "/api/Library/libraries":
			_, _ = io.WriteString(w, `[]`)
		case strings.HasSuffix(r.URL.Path, "/positions"):
			id := strings.TrimSuffix(strings.TrimPrefix(r.URL.Path, "/api/v2/books/"), "/positions")
			table, ok := fake.tables[id]
			if !ok {
				http.NotFound(w, r)
				return
			}
			if r.Method != http.MethodGet {
				fake.posts++
			}
			table.serve(t, w, r)
		case strings.HasPrefix(r.URL.Path, "/api/v2/books/"):
			id := strings.TrimPrefix(r.URL.Path, "/api/v2/books/")
			if _, ok := books[id]; !ok || r.Method != http.MethodGet {
				http.NotFound(w, r)
				return
			}
			_, _ = io.WriteString(w, withPlace(id))
		default:
			http.NotFound(w, r)
		}
	}))
	t.Cleanup(fake.Close)
	return fake
}

// Both books of one title are one work: starting it over forgets the place in each, so the work
// reads as never opened and nothing is "on" in its series.
func TestStartOverForgetsTheEbookAndTheAudiobookOfOneTitleAndTheSeriesNoLongerSaysOn(t *testing.T) {
	// The audiobook's title says it so or not: the work knows it as an edition either way.
	for _, title := range []string{"Red Rising", "Red Rising (Unabridged)"} {
		t.Run(title, func(t *testing.T) { startOverEbookAndAudiobook(t, title) })
	}
}

func startOverEbookAndAudiobook(t *testing.T, audiobookTitle string) {
	story := newStartOverStoryteller(t, audiobookTitle)
	server := NewServer(readingCatalogConfig(story.URL, filepath.Join(t.TempDir(), "catalog.json"), []string{"reading"}))
	clock := &testClock{at: time.UnixMilli(clockStart + 5_000)}
	server.now = clock.now
	handler := server.Handler()

	story.tables["12"].seed(`{"href":"chapter-4.xhtml","locations":{"totalProgression":0.3}}`, clockStart)
	story.tables["13"].seed(`{"href":"track-2.mp3","type":"audio/mpeg","locations":{"fragments":["t=60.000"],"totalProgression":0.2}}`, clockStart+1)

	page := libraryRequest(handler, "/v1/reading/libraries/storyteller:books/items?page=1&sort=title&direction=asc")
	var shelf ReadingLibraryItemsResponse
	if err := json.Unmarshal(page.Body.Bytes(), &shelf); err != nil || len(shelf.Items) == 0 {
		t.Fatalf("shelf = %d %s, %v", page.Code, page.Body.String(), err)
	}
	series := shelf.Items[0]
	var onBefore bool
	for _, book := range series.SeriesBooks {
		onBefore = onBefore || book.State == "on"
	}
	if !onBefore {
		t.Fatalf("setup: the series says you are on a book: %+v", series.SeriesBooks)
	}
	detail := func(id string) ReadingWork {
		got := libraryRequest(handler, "/v1/reading/works/"+id)
		var work ReadingWork
		if got.Code != http.StatusOK || json.Unmarshal(got.Body.Bytes(), &work) != nil {
			t.Fatalf("work %s = %d: %s", id, got.Code, got.Body.String())
		}
		return work
	}
	collection := detail(series.ID)
	if len(collection.Sections) == 0 || len(collection.Sections[0].Items) == 0 {
		t.Fatalf("collection = %+v", collection)
	}
	child := collection.Sections[0].Items[0].WorkID
	if before := detail(child); before.Progress == nil || before.Progress.Percentage == 0 {
		t.Fatalf("setup: the work has a place: %+v", before.Progress)
	}

	response := publicationRequest(handler, http.MethodPost, "/v1/reading/works/"+child+"/start-over", "")
	if response.Code != http.StatusOK {
		t.Fatalf("start over = %d: %s", response.Code, response.Body.String())
	}
	if after := detail(child); after.Progress != nil || after.ResetAt == 0 {
		t.Fatalf("after: progress %+v resetAt %d: both books' places must be gone", after.Progress, after.ResetAt)
	}
	for _, id := range []string{"12", "13"} {
		got := publicationRequest(handler, http.MethodGet, "/v1/reading/works/"+child+"/publications/"+id+"/position", "")
		if got.Code == http.StatusOK && !strings.Contains(got.Body.String(), `"locator":null`) {
			t.Errorf("book %s still has a place: %s", id, got.Body.String())
		}
	}
	// The shelf's series, built from the same list, no longer says you are on a book.
	page = libraryRequest(handler, "/v1/reading/libraries/storyteller:books/items?page=1&sort=title&direction=asc")
	shelf = ReadingLibraryItemsResponse{}
	if err := json.Unmarshal(page.Body.Bytes(), &shelf); err != nil || len(shelf.Items) == 0 {
		t.Fatal(err)
	}
	for _, book := range shelf.Items[0].SeriesBooks {
		if book.State == "on" || book.State == "read" {
			t.Errorf("the series still says %q of %s", book.State, book.Title)
		}
	}
	if story.posts != 0 {
		t.Fatalf("%d writes reached Storyteller", story.posts)
	}
}

func TestStartOverOfAComicMarksTheSeriesUnreadInKavitaAndRefusesAnOlderWrite(t *testing.T) {
	state := &publicationUpstreamState{}
	upstream := newReadingPublicationUpstream(t, state)
	defer upstream.Close()
	server := NewServer(readingCatalogConfig(upstream.URL, filepath.Join(t.TempDir(), "catalog.json"), []string{"reading"}))
	server.now = func() time.Time { return time.UnixMilli(clockStart) }
	handler := server.Handler()
	workID := bindPublicationWork(t, handler)
	path := "/v1/reading/works/" + workID + "/start-over"

	// Kavita failing leaves everything as it was, so the person can try again.
	state.unreadFails = true
	if got := publicationRequest(handler, http.MethodPost, path, ""); got.Code == http.StatusOK {
		t.Fatalf("a failed mark-unread was reported as done: %s", got.Body.String())
	}
	var manifest ReadingPublicationManifest
	if err := json.Unmarshal(libraryRequest(handler, "/v1/reading/works/"+workID+"/publications/6").Body.Bytes(), &manifest); err != nil || manifest.ResetAt != 0 {
		t.Fatalf("a reset that did not happen is recorded: %+v, %v", manifest, err)
	}

	state.unreadFails = false
	state.unread = nil
	got := publicationRequest(handler, http.MethodPost, path, "")
	if got.Code != http.StatusOK {
		t.Fatalf("start over = %d: %s", got.Code, got.Body.String())
	}
	if len(state.unread) != 1 || state.unread[0] != 9 {
		t.Fatalf("Kavita was told to mark series %v unread, want [9]", state.unread)
	}
	var reset ReadingStartOverResponse
	if err := json.Unmarshal(got.Body.Bytes(), &reset); err != nil || reset.ResetAt != clockStart {
		t.Fatalf("answer = %+v, %v", reset, err)
	}
	if err := json.Unmarshal(libraryRequest(handler, "/v1/reading/works/"+workID+"/publications/6").Body.Bytes(), &manifest); err != nil || manifest.ResetAt != reset.ResetAt {
		t.Fatalf("manifest resetAt = %d, want %d (%v)", manifest.ResetAt, reset.ResetAt, err)
	}

	// A page written from before the reset is refused, and never reaches Kavita.
	state.saved.PageNum = -1
	stale := publicationRequest(handler, http.MethodPost, "/v1/reading/works/"+workID+"/publications/6/progress", `{"pageIndex":2,"resetSeen":0}`)
	if stale.Code != http.StatusConflict || errorCodeOf(t, stale) != "reading_position_reset" || state.saved.PageNum != -1 {
		t.Fatalf("stale page = %d %s (saved %d)", stale.Code, stale.Body.String(), state.saved.PageNum)
	}
	fresh := publicationRequest(handler, http.MethodPost, "/v1/reading/works/"+workID+"/publications/6/progress", fmt.Sprintf(`{"pageIndex":2,"resetSeen":%d}`, reset.ResetAt))
	if fresh.Code != http.StatusOK || state.saved.PageNum != 2 {
		t.Fatalf("a page written knowing of the reset = %d %s (saved %d)", fresh.Code, fresh.Body.String(), state.saved.PageNum)
	}
}
