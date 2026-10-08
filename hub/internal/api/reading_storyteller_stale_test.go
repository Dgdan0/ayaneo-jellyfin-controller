package api

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"ayaneohub/internal/adapters/storyteller"
	"ayaneohub/internal/cache"
	"ayaneohub/internal/config"
	readingdomain "ayaneohub/internal/reading"
)

// A Storyteller whose books the test changes between requests. Its list and its
// per-book records are separate, as they are upstream, so a test can let them
// disagree the way they did for A Game of Thrones (#50).
type movingStoryteller struct {
	mu          sync.Mutex
	list        map[int64]string // the list's entry for a book, as JSON
	detail      map[int64]string // the book's own record, as JSON
	detailCode  map[int64]int    // a status other than 200 for a book's record
	listCode    int              // a status other than 200 for the list
	listCalls   int
	detailCalls map[int64]int
	deleted     []int64
}

func (m *movingStoryteller) handler() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		m.mu.Lock()
		defer m.mu.Unlock()
		switch {
		case r.URL.Path == "/api/v2/token":
			_, _ = io.WriteString(w, `{"access_token":"story-token","token_type":"Bearer","expires_in":3600}`)
		case r.URL.Path == "/api/v2/books":
			m.listCalls++
			if m.listCode != 0 {
				http.Error(w, "list broke", m.listCode)
				return
			}
			entries := make([]string, 0, len(m.list))
			for _, id := range []int64{11, 12, 13} {
				if entry, ok := m.list[id]; ok {
					entries = append(entries, entry)
				}
			}
			_, _ = io.WriteString(w, "["+strings.Join(entries, ",")+"]")
		case strings.HasPrefix(r.URL.Path, "/api/v2/books/") && !strings.Contains(strings.TrimPrefix(r.URL.Path, "/api/v2/books/"), "/"):
			id, _ := strconv.ParseInt(strings.TrimPrefix(r.URL.Path, "/api/v2/books/"), 10, 64)
			if r.Method == http.MethodDelete {
				m.deleted = append(m.deleted, id)
				w.WriteHeader(http.StatusNoContent)
				return
			}
			m.detailCalls[id]++
			if code := m.detailCode[id]; code != 0 {
				// Storyteller's answer for a book id it has not got is a 500, not a 404.
				http.Error(w, "Error: no result at database/books.ts:55", code)
				return
			}
			body, ok := m.detail[id]
			if !ok {
				http.NotFound(w, r)
				return
			}
			_, _ = io.WriteString(w, body)
		default:
			http.NotFound(w, r)
		}
	})
}

// set changes a book in the list and in its own record together.
func (m *movingStoryteller) set(id int64, entry string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.list[id], m.detail[id] = entry, entry
}

// setDetailOnly changes the book's own record and leaves the list as it was.
func (m *movingStoryteller) setDetailOnly(id int64, entry string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.detail[id] = entry
}

// remove deletes the book, and Storyteller then answers its id with a 500.
func (m *movingStoryteller) remove(id int64) {
	m.mu.Lock()
	defer m.mu.Unlock()
	delete(m.list, id)
	delete(m.detail, id)
	m.detailCode[id] = http.StatusInternalServerError
}

func (m *movingStoryteller) breakDetail(id int64, code int) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.detailCode[id] = code
}

func (m *movingStoryteller) breakList(code int) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.listCode = code
}

func (m *movingStoryteller) emptyList() {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.list = map[int64]string{}
}

func (m *movingStoryteller) detailAsked(id int64) int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.detailCalls[id]
}

func (m *movingStoryteller) deletes() []int64 {
	m.mu.Lock()
	defer m.mu.Unlock()
	return append([]int64(nil), m.deleted...)
}

// gotBook is one book of A Game of Thrones with the read-along in the given state
// ("" for none). The title and author are the same for every id, as for the two
// records the owner's catalog bound to one work.
func gotBook(id int64, readaloud string) string {
	entry := fmt.Sprintf(`{"id":%d,"uuid":"book-%d","title":"A Game of Thrones","authors":[{"name":"George R. R. Martin"}],"ebook":{"uuid":"ebook-%d"},"audiobook":{"uuid":"audio-%d"}`, id, id, id, id)
	if readaloud != "" {
		entry += fmt.Sprintf(`,"readaloud":{"uuid":"aligned-%d","status":%q}`, id, readaloud)
	}
	return entry + `}`
}

// listExpired is longer than the hub keeps Storyteller's list at all (a minute fresh,
// ten stale), so the next read of it goes upstream and waits for the answer. The
// book's own record is nowhere near its day by then.
const listExpired = 12 * time.Minute

type staleEnv struct {
	t        *testing.T
	story    *movingStoryteller
	server   *Server
	handler  http.Handler
	catalog  string
	moment   time.Time
	momentMu sync.Mutex
}

func (e *staleEnv) now() time.Time {
	e.momentMu.Lock()
	defer e.momentMu.Unlock()
	return e.moment
}

func (e *staleEnv) advance(d time.Duration) {
	e.momentMu.Lock()
	defer e.momentMu.Unlock()
	e.moment = e.moment.Add(d)
}

func newStaleEnv(t *testing.T, books map[int64]string, tweak ...func(*config.Config)) *staleEnv {
	t.Helper()
	story := &movingStoryteller{list: map[int64]string{}, detail: map[int64]string{}, detailCode: map[int64]int{}, detailCalls: map[int64]int{}}
	for id, entry := range books {
		story.list[id], story.detail[id] = entry, entry
	}
	upstream := httptest.NewServer(story.handler())
	t.Cleanup(upstream.Close)
	env := &staleEnv{t: t, story: story, catalog: filepath.Join(t.TempDir(), "catalog.json"), moment: time.Date(2026, 10, 8, 12, 0, 0, 0, time.UTC)}
	cfg := readingCatalogConfig(upstream.URL, env.catalog, []string{"reading"})
	for _, change := range tweak {
		change(cfg)
	}
	env.server = NewServer(cfg)
	// The hub's own cache on a clock the test owns, so "a day later" is a line, not a wait.
	env.server.cache = cache.New().WithClock(env.now)
	env.handler = env.server.Handler()
	return env
}

// workWithSources is the work id the shelf gives the books, bound the way a person
// opening the library binds them, and then bound to every id given as well: a book
// that was in the list when the work was made keeps its binding when it leaves it.
func (e *staleEnv) workWithSources(ids ...int64) string {
	e.t.Helper()
	page := libraryRequest(e.handler, "/v1/reading/libraries/storyteller:books/items?page=1&view=works")
	var shelf ReadingLibraryItemsResponse
	if page.Code != http.StatusOK || json.Unmarshal(page.Body.Bytes(), &shelf) != nil || len(shelf.Items) == 0 {
		e.t.Fatalf("shelf = %d %s", page.Code, page.Body.String())
	}
	work := shelf.Items[0].ID
	bound, ok := e.server.readingCatalog.Resolve(work)
	if !ok {
		e.t.Fatalf("shelf work %q is not in the catalog", work)
	}
	for _, id := range ids {
		if _, err := e.server.readingCatalog.Bind(readingdomain.WorkBinding{Source: "storyteller", SourceID: strconv.FormatInt(id, 10), IdentityKeys: bound.IdentityKeys}); err != nil {
			e.t.Fatal(err)
		}
	}
	if joined, _ := e.server.readingCatalog.Resolve(work); len(joined.Sources) < len(ids) {
		e.t.Fatalf("sources %+v were not bound to one work", joined.Sources)
	}
	return work
}

func (e *staleEnv) work(id string) (ReadingWork, int) {
	e.t.Helper()
	response := libraryRequest(e.handler, "/v1/reading/works/"+id)
	var work ReadingWork
	if response.Code == http.StatusOK {
		if err := json.Unmarshal(response.Body.Bytes(), &work); err != nil {
			e.t.Fatalf("work body %s: %v", response.Body.String(), err)
		}
	}
	return work, response.Code
}

// boundIDs are the Storyteller books the work is bound to in the saved catalog
// file, not in the running store: a prune has to reach the disk.
func (e *staleEnv) boundIDs(workID string) []string {
	e.t.Helper()
	work, ok := readingdomain.NewCatalogStore(e.catalog).Resolve(workID)
	if !ok {
		e.t.Fatalf("work %s is not in the saved catalog", workID)
	}
	out := []string{}
	for _, source := range work.Sources {
		if source.Source == "storyteller" {
			out = append(out, source.SourceID)
		}
	}
	return out
}

func editionKinds(work ReadingWork) string {
	kinds := []string{}
	for _, edition := range work.Editions {
		kinds = append(kinds, edition.Kind)
	}
	return strings.Join(kinds, ",")
}

func hasEdition(work ReadingWork, kind string) bool {
	for _, edition := range work.Editions {
		if edition.Kind == kind {
			return true
		}
	}
	return false
}

// The ticket's first fault. The list says a book's read-along is ALIGNED; the
// book's own record, held for a day, still said PROCESSING, so the library card
// offered read along and the book's page did not.
func TestAWorkListsTheReadAlongAsSoonAsTheListSaysItIsAligned(t *testing.T) {
	env := newStaleEnv(t, map[int64]string{12: gotBook(12, "PROCESSING")})
	work := env.workWithSources(12)

	before, code := env.work(work)
	if code != http.StatusOK || hasEdition(before, "readaloud") {
		t.Fatalf("while processing: %d editions %s", code, editionKinds(before))
	}
	env.story.set(12, gotBook(12, "ALIGNED"))
	env.advance(listExpired)

	after, code := env.work(work)
	if code != http.StatusOK || !hasEdition(after, "readaloud") {
		t.Fatalf("once aligned: %d editions %s", code, editionKinds(after))
	}
	if len(after.Partial) != 0 {
		t.Fatalf("partial = %+v", after.Partial)
	}
}

// The minute-by-minute automation reads Storyteller's list without the cache. When
// it sees a status move it drops that book's record and leaves the screens that list,
// so the page changes with the next request and not with the next expiry.
func TestTheReadAlongAutomationDropsTheRecordOfABookWhoseStatusMoved(t *testing.T) {
	env := newStaleEnv(t, map[int64]string{12: gotBook(12, "PROCESSING")})
	work := env.workWithSources(12)
	if before, _ := env.work(work); hasEdition(before, "readaloud") {
		t.Fatalf("while processing: %s", editionKinds(before))
	}

	env.story.set(12, gotBook(12, "ALIGNED"))
	if err := env.server.reconcileReadingAlignments(context.Background()); err != nil {
		t.Fatal(err)
	}
	after, code := env.work(work)
	if code != http.StatusOK || !hasEdition(after, "readaloud") {
		t.Fatalf("right after the automation saw it: %d editions %s", code, editionKinds(after))
	}

	// Nothing moved since, so another pass drops nothing: the page does not go back
	// to Storyteller, which it would if the shelves' list still held the old status.
	asked := env.story.detailAsked(12)
	if err := env.server.reconcileReadingAlignments(context.Background()); err != nil {
		t.Fatal(err)
	}
	if _, code := env.work(work); code != http.StatusOK || env.story.detailAsked(12) != asked {
		t.Fatalf("a pass that saw no change still dropped the record: asked %d times, was %d", env.story.detailAsked(12), asked)
	}
}

// A read-along being made changes stage by stage. Its record is kept for seconds,
// whatever the list says.
func TestABookWhoseReadAlongIsBeingMadeIsNeverHeldForADay(t *testing.T) {
	env := newStaleEnv(t, map[int64]string{12: gotBook(12, "QUEUED")})
	work := env.workWithSources(12)
	if first, _ := env.work(work); hasEdition(first, "readaloud") {
		t.Fatalf("queued: %s", editionKinds(first))
	}
	env.advance(20 * time.Second)
	env.work(work)
	if asked := env.story.detailAsked(12); asked != 1 {
		t.Fatalf("a record 20 seconds old was read again (%d reads)", asked)
	}

	// The record moves on; the list the hub holds has not been read again yet.
	env.story.setDetailOnly(12, gotBook(12, "ALIGNED"))
	env.advance(20 * time.Second)
	after, code := env.work(work)
	if code != http.StatusOK || !hasEdition(after, "readaloud") {
		t.Fatalf("40 seconds on: %d editions %s", code, editionKinds(after))
	}
}

func TestABookWhoseReadAlongIsAlignedKeepsItsRecord(t *testing.T) {
	env := newStaleEnv(t, map[int64]string{12: gotBook(12, "ALIGNED")})
	work := env.workWithSources(12)
	env.work(work)
	env.advance(40 * time.Second)
	env.work(work)
	if asked := env.story.detailAsked(12); asked != 1 {
		t.Fatalf("an aligned book's record was read %d times, want once", asked)
	}
}

// The ticket's second fault. A bound book that Storyteller no longer lists is not
// an outage of Storyteller: no partial, and the binding is gone from the catalog
// file as well.
func TestABoundBookStorytellerNoLongerHasIsLeftOutAndPruned(t *testing.T) {
	env := newStaleEnv(t, map[int64]string{11: gotBook(11, ""), 12: gotBook(12, "ALIGNED")})
	work := env.workWithSources(11, 12)
	if got := env.boundIDs(work); len(got) != 2 {
		t.Fatalf("bound = %v", got)
	}
	env.story.remove(11)
	env.advance(listExpired)

	got, code := env.work(work)
	if code != http.StatusOK {
		t.Fatalf("status = %d", code)
	}
	if len(got.Partial) != 0 {
		t.Fatalf("a deleted book was reported as an outage: %+v", got.Partial)
	}
	if !hasEdition(got, "readaloud") || !hasEdition(got, "ebook") {
		t.Fatalf("the surviving book's editions are missing: %s", editionKinds(got))
	}
	if asked := env.story.detailAsked(11); asked != 0 {
		t.Fatalf("the hub asked Storyteller for a book its list does not have (%d times)", asked)
	}
	if ids := env.boundIDs(work); len(ids) != 1 || ids[0] != "12" {
		t.Fatalf("saved binding = %v, want only 12", ids)
	}
	if _, bound := env.server.readingCatalog.WorkIDFor("storyteller", "11"); bound {
		t.Fatal("the running catalog still binds the deleted book")
	}
}

// Only the list can say a book is gone. Storyteller's 500 for a book it still
// lists, a list that cannot be read and an empty list are none of them proof.
func TestAFailingBookIsAnOutageUnlessTheListSaysItIsGone(t *testing.T) {
	for _, tc := range []struct {
		name    string
		prepare func(env *staleEnv)
	}{
		{"a 500 for a listed book", func(env *staleEnv) {}},
		{"a list that cannot be read", func(env *staleEnv) { env.story.breakList(http.StatusServiceUnavailable) }},
		{"an empty list", func(env *staleEnv) { env.story.emptyList() }},
	} {
		t.Run(tc.name, func(t *testing.T) {
			env := newStaleEnv(t, map[int64]string{11: gotBook(11, ""), 12: gotBook(12, "ALIGNED")})
			work := env.workWithSources(11, 12)
			env.story.breakDetail(11, http.StatusInternalServerError)
			tc.prepare(env)
			env.advance(listExpired)

			got, code := env.work(work)
			if code != http.StatusOK {
				t.Fatalf("status = %d", code)
			}
			if len(got.Partial) != 1 || got.Partial[0].Service != "storyteller" || got.Partial[0].Reason != "upstream_unavailable" {
				t.Fatalf("partial = %+v, want one storyteller upstream_unavailable", got.Partial)
			}
			if ids := env.boundIDs(work); len(ids) != 2 {
				t.Fatalf("saved binding = %v: a book that may still exist was unbound", ids)
			}
		})
	}
}

// A work all of whose Storyteller books are gone is not "unavailable, try again".
func TestAWorkWhoseOnlyBooksAreGoneIsNotFound(t *testing.T) {
	env := newStaleEnv(t, map[int64]string{11: gotBook(11, ""), 12: gotBook(12, ""), 13: strings.Replace(gotBook(13, ""), "A Game of Thrones", "Another Book", 1)})
	work := env.workWithSources(11, 12)
	env.story.remove(11)
	env.story.remove(12)
	env.advance(listExpired)

	if _, code := env.work(work); code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404", code)
	}
	if ids := env.boundIDs(work); len(ids) != 0 {
		t.Fatalf("saved binding = %v", ids)
	}
}

// The same rule on every route that names a book: it is gone, not an outage.
func TestRoutesNameAGoneBookAsGoneAndNotAsAnOutage(t *testing.T) {
	for _, path := range []string{
		"/publications/11/file?format=ebook",
		"/publications/11/file?format=readaloud&audio=omit",
		"/publications/11/position",
		"/publications/11/audio",
		"/publications/11/audio/tracks/0?rev=0123456789ab",
		"/publications/11/audio/position",
	} {
		t.Run(path, func(t *testing.T) {
			env := newStaleEnv(t, map[int64]string{11: gotBook(11, ""), 12: gotBook(12, "ALIGNED")})
			work := env.workWithSources(11, 12)
			env.story.remove(11)
			env.advance(listExpired)

			response := libraryRequest(env.handler, "/v1/reading/works/"+work+path)
			if response.Code != http.StatusNotFound || !strings.Contains(response.Body.String(), "no longer in Storyteller") {
				t.Fatalf("%d %s, want 404 naming the book as gone", response.Code, response.Body.String())
			}
			if asked := env.story.detailAsked(11); asked != 0 {
				t.Fatalf("a gone book was asked for %d times", asked)
			}
			if ids := env.boundIDs(work); len(ids) != 1 || ids[0] != "12" {
				t.Fatalf("saved binding = %v", ids)
			}
		})
	}
}

// Deleting a work whose binding is dead must not fail on the book that is gone, and
// must not try to delete it.
func TestRemovingAWorkSkipsAGoneBook(t *testing.T) {
	folder := t.TempDir()
	for _, name := range []string{"live.epub", "keep.epub"} {
		if err := os.WriteFile(filepath.Join(folder, name), []byte("fixture"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	live := `{"id":12,"uuid":"book-12","title":"A Game of Thrones","authors":[{"name":"George R. R. Martin"}],"ebook":{"uuid":"ebook-12","filepath":"/library/live.epub"}}`
	dead := `{"id":11,"uuid":"book-11","title":"A Game of Thrones","authors":[{"name":"George R. R. Martin"}],"ebook":{"uuid":"ebook-11","filepath":"/library/dead.epub"}}`
	env := newStaleEnv(t, map[int64]string{11: dead, 12: live}, func(cfg *config.Config) {
		cfg.Auth.Tokens[0].Scopes = []string{"reading", "control"}
		cfg.Server.MediaRemovalRoots = []config.MediaRemovalRoot{{Service: "storyteller", Remote: "/library", Local: folder}}
	})
	work := env.workWithSources(11, 12)
	env.story.remove(11)
	env.advance(listExpired)

	response := removalPost(env.handler, "/v1/media/removal-preview", fmt.Sprintf(`{"kind":"reading","id":%q}`, work))
	if response.Code != http.StatusOK {
		t.Fatalf("preview %d %s", response.Code, response.Body.String())
	}
	var preview removalPreview
	if err := json.Unmarshal(response.Body.Bytes(), &preview); err != nil || preview.FileCount != 1 || preview.Files[0] != "live.epub" {
		t.Fatalf("preview = %+v (%v), want only the live book's file", preview, err)
	}
	response = removalPost(env.handler, "/v1/media/remove", fmt.Sprintf(`{"ticket":%q,"confirm":true}`, preview.Ticket))
	if response.Code != http.StatusOK {
		t.Fatalf("remove %d %s", response.Code, response.Body.String())
	}
	if deleted := env.story.deletes(); len(deleted) != 1 || deleted[0] != 12 {
		t.Fatalf("Storyteller was asked to delete %v, want only 12", deleted)
	}
	if _, err := os.Stat(filepath.Join(folder, "live.epub")); !os.IsNotExist(err) {
		t.Fatal("the live book's file survived")
	}
	if _, err := os.Stat(filepath.Join(folder, "keep.epub")); err != nil {
		t.Fatal("an unlisted file was deleted")
	}
}

func TestTheStampFollowsWhatChangesABooksDetailAndNotItsProgress(t *testing.T) {
	book := storyteller.Book{ID: 1, UpdatedAt: "2026-10-08 07:00:00",
		Ebook: &storyteller.Ebook{UUID: "e"}, Audiobook: &storyteller.Audiobook{UUID: "a"},
		Readaloud: &storyteller.Readaloud{UUID: "r", Status: "PROCESSING", CurrentStage: "TRANSCRIBE", StageProgress: 0.1, QueuePosition: 2}}
	base := storytellerStamp(book)

	progressed := book
	progressed.Readaloud = &storyteller.Readaloud{UUID: "r", Status: "PROCESSING", CurrentStage: "SYNC_CHAPTERS", StageProgress: 0.9}
	if storytellerStamp(progressed) != base {
		t.Fatal("a stage moving on is not a change of the book's detail")
	}
	for name, change := range map[string]func(*storyteller.Book){
		"aligned":         func(b *storyteller.Book) { b.Readaloud = &storyteller.Readaloud{UUID: "r", Status: "ALIGNED"} },
		"read-along gone": func(b *storyteller.Book) { b.Readaloud = nil },
		"read-along file": func(b *storyteller.Book) { b.Readaloud = &storyteller.Readaloud{UUID: "r2", Status: "PROCESSING"} },
		"read-along saved": func(b *storyteller.Book) {
			b.Readaloud = &storyteller.Readaloud{UUID: "r", Status: "PROCESSING", UpdatedAt: "later"}
		},
		"book updated":   func(b *storyteller.Book) { b.UpdatedAt = "2026-10-08 08:00:00" },
		"ebook missing":  func(b *storyteller.Book) { b.Ebook = &storyteller.Ebook{UUID: "e", Missing: true} },
		"audiobook swap": func(b *storyteller.Book) { b.Audiobook = &storyteller.Audiobook{UUID: "a2"} },
	} {
		changed := book
		change(&changed)
		if storytellerStamp(changed) == base {
			t.Errorf("%s did not change the stamp", name)
		}
	}
}
