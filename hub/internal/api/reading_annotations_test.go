package api

// Highlights and notes (#62): kept per Jellyfin profile and per work on the hub, so the ebook and its read-along
// share them and every device has them. An anchor is edition-independent (the document path in #61's one spelling
// and a quote with its neighbours); the colour, the note and the stamps travel with it, and a delete leaves a
// tombstone so another device learns of it. Last write wins on updatedAt.

import (
	"ayaneohub/internal/config"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

const annotationsTestTime = int64(1_790_000_000_000)

func (e *bookPageEnv) annotations(workID string, query string, headers ...string) (*httptest.ResponseRecorder, ReadingAnnotationsResponse) {
	e.t.Helper()
	got := e.do(http.MethodGet, "/v1/reading/works/"+workID+"/annotations"+query, "", headers...)
	var body ReadingAnnotationsResponse
	if got.Code == http.StatusOK {
		if err := json.Unmarshal(got.Body.Bytes(), &body); err != nil {
			e.t.Fatal(err)
		}
	}
	return got, body
}

func (e *bookPageEnv) annotate(method, path, body string, headers ...string) (*httptest.ResponseRecorder, ReadingAnnotationResponse) {
	e.t.Helper()
	got := e.do(method, path, body, headers...)
	var answer ReadingAnnotationResponse
	if got.Code == http.StatusOK {
		if err := json.Unmarshal(got.Body.Bytes(), &answer); err != nil {
			e.t.Fatal(err)
		}
	}
	return got, answer
}

func annotationBody(id, color, note string, updatedAt int64) string {
	idPart := ""
	if id != "" {
		idPart = fmt.Sprintf(`"id":%q,`, id)
	}
	return fmt.Sprintf(`{%s"color":%q,"note":%q,"document":"OEBPS/chapter-1.xhtml","quote":{"before":"the old light, like a ","highlight":"bird","after":".” She had laughed"},"locator":{"href":"OEBPS/chapter-1.xhtml","locations":{"progression":0.4}},"createdAt":%d,"updatedAt":%d}`,
		idPart, color, note, updatedAt, updatedAt)
}

func TestAnnotationsStartEmptyAndAreKeptPerWorkAndPerProfile(t *testing.T) {
	env := newBookPageEnv(t, bookPageOptions{})
	env.server.now = func() time.Time { return time.UnixMilli(annotationsTestTime + 5_000) }
	redRising, finalEmpire := env.work("Red Rising"), env.work("The Final Empire")

	got, list := env.annotations(redRising, "")
	if got.Code != http.StatusOK || len(list.Annotations) != 0 || list.WorkID != redRising || list.ServerTime != annotationsTestTime+5_000 {
		t.Fatalf("an empty list = %d %+v", got.Code, list)
	}
	if !strings.Contains(got.Header().Get("Vary"), jellyfinUserHeader) || strings.Contains(got.Body.String(), `"annotations":null`) {
		t.Errorf("Vary %q, body %s", got.Header().Get("Vary"), got.Body.String())
	}

	created, answer := env.annotate(http.MethodPost, "/v1/reading/works/"+redRising+"/annotations", annotationBody("", "yellow", "", annotationsTestTime))
	if created.Code != http.StatusOK || !answer.Applied || !strings.HasPrefix(answer.Annotation.ID, "an_") || answer.Annotation.Color != "yellow" {
		t.Fatalf("create = %d %s", created.Code, created.Body.String())
	}
	if answer.Annotation.Document != "OEBPS/chapter-1.xhtml" || answer.Annotation.Quote.Highlight != "bird" || answer.Annotation.Quote.Before != "the old light, like a " {
		t.Fatalf("the anchor was not kept: %+v", answer.Annotation)
	}
	if answer.Annotation.UpdatedAt != annotationsTestTime || answer.Annotation.CreatedAt != annotationsTestTime || len(answer.Annotation.Locator) == 0 {
		t.Fatalf("stamps and hint: %+v", answer.Annotation)
	}

	// The same work, the same profile: there. Another work, another profile: not.
	if _, list := env.annotations(redRising, ""); len(list.Annotations) != 1 || list.Annotations[0].ID != answer.Annotation.ID {
		t.Fatalf("list = %+v", list)
	}
	if _, list := env.annotations(finalEmpire, ""); len(list.Annotations) != 0 {
		t.Fatalf("another work has %+v", list)
	}
	if _, list := env.annotations(redRising, "", jellyfinUserHeader, bpProfileB); len(list.Annotations) != 0 {
		t.Fatalf("another profile has %+v", list)
	}
}

func TestACreateWithAnIdOfItsOwnIsKeptAsItIsAndRepeatedHarmlessly(t *testing.T) {
	env := newBookPageEnv(t, bookPageOptions{})
	id := env.work("Red Rising")
	path := "/v1/reading/works/" + id + "/annotations"
	mine := "an_0123456789abcdef0123456789abcdef"
	for attempt := 0; attempt < 2; attempt++ {
		got, answer := env.annotate(http.MethodPost, path, annotationBody(mine, "blue", "", annotationsTestTime))
		if got.Code != http.StatusOK || answer.Annotation.ID != mine {
			t.Fatalf("attempt %d = %d %s", attempt, got.Code, got.Body.String())
		}
	}
	if _, list := env.annotations(id, ""); len(list.Annotations) != 1 {
		t.Fatalf("a retried create made %d", len(list.Annotations))
	}
}

func TestTheLastWriteWinsOnUpdatedAtAndAnOlderOneChangesNothing(t *testing.T) {
	env := newBookPageEnv(t, bookPageOptions{})
	work := env.work("Red Rising")
	path := "/v1/reading/works/" + work + "/annotations/"
	id := "an_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
	env.annotate(http.MethodPost, "/v1/reading/works/"+work+"/annotations", annotationBody(id, "yellow", "", annotationsTestTime))

	newer, answer := env.annotate(http.MethodPut, path+id, annotationBody(id, "pink", "worth remembering", annotationsTestTime+1_000))
	if newer.Code != http.StatusOK || !answer.Applied || answer.Annotation.Color != "pink" || answer.Annotation.Note != "worth remembering" || answer.Annotation.CreatedAt != annotationsTestTime {
		t.Fatalf("a newer write = %d %+v", newer.Code, answer)
	}
	// Written offline earlier and arriving late: it loses, and what won is answered so the device adopts it.
	older, lost := env.annotate(http.MethodPut, path+id, annotationBody(id, "green", "stale", annotationsTestTime+500))
	if older.Code != http.StatusOK || lost.Applied || lost.Annotation.Color != "pink" || lost.Annotation.Note != "worth remembering" {
		t.Fatalf("an older write = %d %+v", older.Code, lost)
	}
	// The same stamp is the same write again, or a tie that changes nothing.
	tie, kept := env.annotate(http.MethodPut, path+id, annotationBody(id, "green", "tie", annotationsTestTime+1_000))
	if tie.Code != http.StatusOK || kept.Applied || kept.Annotation.Color != "pink" {
		t.Fatalf("a tie = %+v", kept)
	}
	// An update of one the hub has not seen is a create (the create was lost on the way).
	other := "an_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
	if got, made := env.annotate(http.MethodPut, path+other, annotationBody(other, "blue", "", annotationsTestTime)); got.Code != http.StatusOK || !made.Applied {
		t.Fatalf("an update of an unknown id = %d %s", got.Code, got.Body.String())
	}
	// The path's id and the body's must agree.
	if got := env.do(http.MethodPut, path+id, annotationBody(other, "blue", "", annotationsTestTime+9_000)); got.Code != http.StatusBadRequest {
		t.Fatalf("an id that disagrees = %d", got.Code)
	}
}

func TestADeleteLeavesATombstoneAnOlderWriteCannotUndo(t *testing.T) {
	env := newBookPageEnv(t, bookPageOptions{})
	env.server.now = func() time.Time { return time.UnixMilli(annotationsTestTime + 60_000) }
	work := env.work("Red Rising")
	path := "/v1/reading/works/" + work + "/annotations/"
	id := "an_cccccccccccccccccccccccccccccccc"
	env.annotate(http.MethodPost, "/v1/reading/works/"+work+"/annotations", annotationBody(id, "yellow", "a note", annotationsTestTime))

	deleted, answer := env.annotate(http.MethodDelete, path+id+"?updatedAt="+fmt.Sprint(annotationsTestTime+2_000), "")
	if deleted.Code != http.StatusOK || !answer.Applied || !answer.Annotation.Deleted || answer.Annotation.UpdatedAt != annotationsTestTime+2_000 {
		t.Fatalf("delete = %d %s", deleted.Code, deleted.Body.String())
	}
	// A list says nothing of it, unless asked what changed: then it is the tombstone another device needs.
	if _, list := env.annotations(work, ""); len(list.Annotations) != 0 {
		t.Fatalf("the list still has %+v", list.Annotations)
	}
	_, changes := env.annotations(work, "?since=0")
	if len(changes.Annotations) != 1 || !changes.Annotations[0].Deleted || changes.Annotations[0].ID != id {
		t.Fatalf("changes = %+v", changes.Annotations)
	}
	// What the tombstone keeps is the anchor, not the note: a deleted note is not kept.
	if changes.Annotations[0].Note != "" {
		t.Errorf("a deleted highlight keeps its note: %q", changes.Annotations[0].Note)
	}
	// A write from before the delete cannot bring it back.
	if got, lost := env.annotate(http.MethodPut, path+id, annotationBody(id, "pink", "", annotationsTestTime+1_000)); got.Code != http.StatusOK || lost.Applied || !lost.Annotation.Deleted {
		t.Fatalf("an older write over a tombstone = %d %+v", got.Code, lost)
	}
	// A newer one does: that is Undo.
	if got, back := env.annotate(http.MethodPut, path+id, annotationBody(id, "pink", "", annotationsTestTime+3_000)); got.Code != http.StatusOK || !back.Applied || back.Annotation.Deleted {
		t.Fatalf("a newer write over a tombstone = %d %+v", got.Code, back)
	}
	// Deleting what was never there is a tombstone too, so a device that deleted it offline is told it is done.
	if got, none := env.annotate(http.MethodDelete, path+"an_dddddddddddddddddddddddddddddddd", ""); got.Code != http.StatusOK || !none.Annotation.Deleted {
		t.Fatalf("delete of an unknown id = %d %s", got.Code, got.Body.String())
	}
}

func TestChangesSinceAStampAreOnlyWhatMovedAfterIt(t *testing.T) {
	env := newBookPageEnv(t, bookPageOptions{})
	work := env.work("Red Rising")
	base := "/v1/reading/works/" + work + "/annotations"
	for i, at := range []int64{1000, 2000, 3000} {
		env.annotate(http.MethodPost, base, annotationBody(fmt.Sprintf("an_%032x", i+1), "yellow", "", annotationsTestTime+at))
	}
	_, since := env.annotations(work, fmt.Sprintf("?since=%d", annotationsTestTime+2000))
	if len(since.Annotations) != 1 || since.Annotations[0].ID != fmt.Sprintf("an_%032x", 3) {
		t.Fatalf("since the second = %+v", since.Annotations)
	}
	if got := env.do(http.MethodGet, base+"?since=soon", ""); got.Code != http.StatusBadRequest {
		t.Fatalf("a bad since = %d", got.Code)
	}
	// Listed in the order they sit in the book's first document, then by when they were made.
	_, all := env.annotations(work, "")
	for i := 1; i < len(all.Annotations); i++ {
		if all.Annotations[i-1].CreatedAt > all.Annotations[i].CreatedAt {
			t.Errorf("not in order: %+v", all.Annotations)
		}
	}
}

func TestAnAnnotationIsRefusedWhenItsAnchorIsNotOne(t *testing.T) {
	env := newBookPageEnv(t, bookPageOptions{})
	work := env.work("Red Rising")
	base := "/v1/reading/works/" + work + "/annotations"
	good := annotationBody("", "yellow", "", annotationsTestTime)
	for name, body := range map[string]string{
		"a colour that is not one":     strings.Replace(good, `"yellow"`, `"purple"`, 1),
		"no colour":                    strings.Replace(good, `"color":"yellow",`, ``, 1),
		"no quote":                     strings.Replace(good, `"highlight":"bird"`, `"highlight":""`, 1),
		"no document":                  strings.Replace(good, `"document":"OEBPS/chapter-1.xhtml"`, `"document":""`, 1),
		"a document out of the book":   strings.Replace(good, `"document":"OEBPS/chapter-1.xhtml"`, `"document":"../../etc/passwd"`, 1),
		"an id that is not one":        strings.Replace(good, `{"color"`, `{"id":"../x","color"`, 1),
		"an unknown field":             strings.Replace(good, `{"color"`, `{"secret":1,"color"`, 1),
		"a note too long":              strings.Replace(good, `"note":""`, `"note":"`+strings.Repeat("n", 4001)+`"`, 1),
		"a quote too long":             strings.Replace(good, `"highlight":"bird"`, `"highlight":"`+strings.Repeat("q", 2001)+`"`, 1),
		"a hint that is not an object": strings.Replace(good, `"locator":{"href":"OEBPS/chapter-1.xhtml","locations":{"progression":0.4}}`, `"locator":"x"`, 1),
		"not json":                     `{`,
	} {
		if got := env.do(http.MethodPost, base, body); got.Code != http.StatusBadRequest {
			t.Errorf("%s = %d: %s", name, got.Code, got.Body.String())
		}
	}
	if _, list := env.annotations(work, ""); len(list.Annotations) != 0 {
		t.Fatalf("a refused annotation was kept: %+v", list.Annotations)
	}
}

func TestADocumentIsKeptInTheOneSpelling(t *testing.T) {
	env := newBookPageEnv(t, bookPageOptions{})
	work := env.work("Red Rising")
	body := strings.Replace(annotationBody("", "green", "", annotationsTestTime), `"document":"OEBPS/chapter-1.xhtml"`, `"document":"/OEBPS/chapter-1.xhtml#p3"`, 1)
	got, answer := env.annotate(http.MethodPost, "/v1/reading/works/"+work+"/annotations", body)
	if got.Code != http.StatusOK || answer.Annotation.Document != "OEBPS/chapter-1.xhtml" {
		t.Fatalf("document = %q (%d %s): no leading slash and no fragment", answer.Annotation.Document, got.Code, got.Body.String())
	}
}

func TestAClockAheadOfTheHubCannotWinForEver(t *testing.T) {
	env := newBookPageEnv(t, bookPageOptions{})
	env.server.now = func() time.Time { return time.UnixMilli(annotationsTestTime) }
	work := env.work("Red Rising")
	base := "/v1/reading/works/" + work + "/annotations"
	id := "an_eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
	// A phone a year ahead: its edit is stamped no later than the hub's own clock and a little more.
	got, answer := env.annotate(http.MethodPost, base, annotationBody(id, "yellow", "", annotationsTestTime+365*24*3600*1000))
	if got.Code != http.StatusOK || answer.Annotation.UpdatedAt > annotationsTestTime+10*60*1000 {
		t.Fatalf("stamp %d after a clock a year ahead", answer.Annotation.UpdatedAt)
	}
	// So a later edit from a phone whose clock is right still wins.
	env.server.now = func() time.Time { return time.UnixMilli(annotationsTestTime + 30*60*1000) }
	if _, later := env.annotate(http.MethodPut, base+"/"+id, annotationBody(id, "blue", "", annotationsTestTime+20*60*1000)); !later.Applied || later.Annotation.Color != "blue" {
		t.Fatalf("a later edit lost: %+v", later)
	}
}

func TestAnnotationsNeedAKnownWorkAndTheReadingScopeAndTheListIsBounded(t *testing.T) {
	env := newBookPageEnv(t, bookPageOptions{})
	// Five hundred writes are more than a screen is allowed in a minute.
	env.cfg.Auth.RateLimit = config.RateLimitConfig{RPM: 1_000_000, Burst: 100_000}
	env.start()
	unknown := "/v1/reading/works/rw_00000000000000000000000000000000/annotations"
	if got := env.do(http.MethodGet, unknown, ""); got.Code != http.StatusNotFound {
		t.Errorf("unknown work = %d", got.Code)
	}
	if got := env.do(http.MethodGet, "/v1/reading/works/nope/annotations", ""); got.Code != http.StatusBadRequest {
		t.Errorf("bad id = %d", got.Code)
	}
	readOnly := newBookPageEnv(t, bookPageOptions{scopes: []string{"read"}})
	for _, method := range []string{http.MethodGet, http.MethodPost} {
		if got := readOnly.do(method, unknown, annotationBody("", "yellow", "", 1)); got.Code != http.StatusForbidden {
			t.Errorf("%s without the reading scope = %d", method, got.Code)
		}
	}
	work := env.work("Red Rising")
	base := "/v1/reading/works/" + work + "/annotations"
	for i := 0; i < readingAnnotationLimit; i++ {
		body := annotationBody(fmt.Sprintf("an_%032x", i+1), "yellow", "", annotationsTestTime+int64(i))
		if got := env.do(http.MethodPost, base, body); got.Code != http.StatusOK {
			t.Fatalf("annotation %d = %d: %s", i, got.Code, got.Body.String())
		}
	}
	if got := env.do(http.MethodPost, base, annotationBody("an_ffffffffffffffffffffffffffffffff", "yellow", "", annotationsTestTime+99_999)); got.Code != http.StatusConflict {
		t.Fatalf("one over the limit = %d: %s", got.Code, got.Body.String())
	}
	// An edit of one that is there is not a new one.
	if got := env.do(http.MethodPut, base+"/"+fmt.Sprintf("an_%032x", 1), annotationBody(fmt.Sprintf("an_%032x", 1), "pink", "", annotationsTestTime+99_999)); got.Code != http.StatusOK {
		t.Fatalf("an edit at the limit = %d", got.Code)
	}
}

func TestAnnotationsSurviveARestartAndStayPrivate(t *testing.T) {
	env := newBookPageEnv(t, bookPageOptions{})
	work := env.work("Red Rising")
	id := "an_1234567890abcdef1234567890abcdef"
	env.annotate(http.MethodPost, "/v1/reading/works/"+work+"/annotations", annotationBody(id, "green", "kept", annotationsTestTime))
	env.start()
	if _, list := env.annotations(work, ""); len(list.Annotations) != 1 || list.Annotations[0].Note != "kept" {
		t.Fatalf("after a restart %+v", list.Annotations)
	}
	path := readingAnnotationsPath(env.cfg.Server.OfflineRegistry)
	if info, err := os.Stat(path); err != nil || (info.Mode().Perm()&0o077 != 0 && filepath.Separator == '/') {
		t.Fatalf("the file is private: %v %v", info, err)
	}
}

func TestAnAnnotationsFileThatCannotBeReadIsKeptAndNothingIsWrittenOverIt(t *testing.T) {
	env := newBookPageEnv(t, bookPageOptions{})
	path := readingAnnotationsPath(env.cfg.Server.OfflineRegistry)
	if err := os.WriteFile(path, []byte(`{"version":9}`), 0o600); err != nil {
		t.Fatal(err)
	}
	env.start()
	work := env.work("Red Rising")
	if got := env.do(http.MethodPost, "/v1/reading/works/"+work+"/annotations", annotationBody("", "yellow", "", annotationsTestTime)); got.Code != http.StatusInternalServerError {
		t.Fatalf("a write over a file that cannot be read = %d: %s", got.Code, got.Body.String())
	}
	if body, _ := os.ReadFile(path); string(body) != `{"version":9}` {
		t.Fatalf("the file was written over: %s", body)
	}
	if got := env.do(http.MethodGet, "/v1/reading/works/"+work+"/annotations", ""); got.Code != http.StatusOK {
		t.Fatalf("a read is still answered, empty: %d", got.Code)
	}
}
