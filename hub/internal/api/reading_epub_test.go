package api

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"reflect"
	"strconv"
	"strings"
	"sync"
	"testing"
)

type epubUpstreamState struct {
	fileCalls  int
	rangeSeen  string
	saved      json.RawMessage
	timestamp  int64
	conflict   bool
	readaloud  bool
	audiobook  bool
	formatSeen string
	// audio is the raw JSON of the "audiobook" field Storyteller reports for
	// book 12 when a test needs its real shape: the folder it lives in, in
	// Storyteller's own filesystem, and a manifest of its files (see
	// storytellerAudiobook). Empty keeps the bare edition older tests use.
	audio string
	// narrators is the raw JSON array of the book's narrators.
	narrators string
	// noFiles makes Storyteller's /files route, the one that builds and sends a
	// ZIP, fail the test if it is called: audio streamed from the hub's own files
	// must never go through it.
	noFiles bool
	// readaloudJSON is the raw JSON of the "readaloud" field for book 12 beside
	// an audio one: the aligned EPUB's path in Storyteller's filesystem and its
	// status.
	readaloudJSON string
	// positions, when set, is Storyteller's real position table for book 12,
	// with the rules of its database/positions.ts, in place of the fixed
	// locator and the one-shot conflict switch of the older tests.
	positions *storytellerPositions
}

// storytellerPositions is Storyteller's position table for one book, kept as
// database/positions.ts keeps it: one row, a Readium locator and a timestamp.
// A write is refused when the stored timestamp is newer, or equal with another
// locator; a read of an empty table is a 404.
type storytellerPositions struct {
	mu        sync.Mutex
	locator   json.RawMessage
	timestamp int64
	has       bool
	posts     int // writes asked for
	refused   int // writes refused
	// race, when set, runs once as a write arrives and before it is judged, with
	// the table unlocked: another writer getting in first.
	race func()
	// history is each locator the table accepted, in the order it did.
	history []json.RawMessage
}

func (p *storytellerPositions) seed(locator string, timestamp int64) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.locator, p.timestamp, p.has = json.RawMessage(locator), timestamp, true
}

// stored is the locator and timestamp now held, as a test reads them.
func (p *storytellerPositions) stored() (json.RawMessage, int64, bool) {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.locator, p.timestamp, p.has
}

func (p *storytellerPositions) serve(t *testing.T, w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodPost {
		p.mu.Lock()
		race := p.race
		p.race = nil
		p.mu.Unlock()
		if race != nil {
			race()
		}
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	switch r.Method {
	case http.MethodGet:
		if !p.has {
			http.NotFound(w, r)
			return
		}
		_, _ = io.WriteString(w, `{"uuid":"position-12","locator":`+string(p.locator)+`,"timestamp":`+strconv.FormatInt(p.timestamp, 10)+`,"updatedAt":"2026-10-05T12:00:00Z"}`)
	case http.MethodPost:
		var body struct {
			Locator   json.RawMessage `json:"locator"`
			Timestamp int64           `json:"timestamp"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			t.Errorf("the hub wrote an unreadable position: %v", err)
			http.Error(w, "bad body", http.StatusBadRequest)
			return
		}
		p.posts++
		if p.has && (p.timestamp > body.Timestamp || (p.timestamp == body.Timestamp && !sameJSON(p.locator, body.Locator))) {
			p.refused++
			http.Error(w, "a newer position exists", http.StatusConflict)
			return
		}
		p.locator, p.timestamp, p.has = body.Locator, body.Timestamp, true
		p.history = append(p.history, body.Locator)
		w.WriteHeader(http.StatusNoContent)
	}
}

func sameJSON(a, b json.RawMessage) bool {
	var left, right any
	return json.Unmarshal(a, &left) == nil && json.Unmarshal(b, &right) == nil && reflect.DeepEqual(left, right)
}

func newEpubUpstream(t *testing.T, state *epubUpstreamState) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v2/token":
			_, _ = io.WriteString(w, `{"access_token":"story-token","token_type":"Bearer","expires_in":3600}`)
		case "/api/v2/books":
			_, _ = io.WriteString(w, `[{"id":12,"uuid":"book-12","title":"Red Rising","authors":[{"name":"Pierce Brown"}],"series":[{"uuid":"series-red","name":"Red Rising","position":1}],"ebook":{"uuid":"ebook-12","pageCount":400}}]`)
		case "/api/v2/books/12":
			if state.audio != "" {
				narrators := state.narrators
				if narrators == "" {
					narrators = "[]"
				}
				readaloud := ""
				if state.readaloudJSON != "" {
					readaloud = `,"readaloud":` + state.readaloudJSON
				}
				_, _ = io.WriteString(w, `{"id":12,"uuid":"book-12","title":"Red Rising","authors":[{"name":"Pierce Brown"}],"narrators":`+narrators+
					`,"series":[{"uuid":"series-red","name":"Red Rising","position":1}],"ebook":{"uuid":"ebook-12","pageCount":400},"audiobook":`+state.audio+readaloud+`}`)
				return
			}
			if state.readaloud {
				_, _ = io.WriteString(w, `{"id":12,"uuid":"book-12","title":"Red Rising","ebook":{"uuid":"ebook-12"},"audiobook":{"uuid":"audio-12"},"readaloud":{"uuid":"aligned-12"}}`)
				return
			}
			if state.audiobook {
				_, _ = io.WriteString(w, `{"id":12,"uuid":"book-12","title":"Red Rising","ebook":{"uuid":"ebook-12"},"audiobook":{"uuid":"audio-12"}}`)
				return
			}
			_, _ = io.WriteString(w, `{"id":12,"uuid":"book-12","title":"Red Rising","authors":[{"name":"Pierce Brown"}],"series":[{"uuid":"series-red","name":"Red Rising","position":1}],"ebook":{"uuid":"ebook-12","pageCount":400}}`)
		case "/api/v2/books/12/files":
			if state.noFiles {
				t.Errorf("Storyteller's /files route was called (%s): streaming must read the files itself", r.URL)
				http.Error(w, "streaming must not build an archive", http.StatusInternalServerError)
				return
			}
			state.fileCalls++
			state.rangeSeen = r.Header.Get("Range")
			state.formatSeen = r.URL.Query().Get("format")
			if (state.formatSeen != "ebook" && !(state.readaloud && state.formatSeen == "readaloud") && !(state.audiobook && state.formatSeen == "audiobook")) || r.Header.Get("Authorization") != "Bearer story-token" {
				t.Fatalf("file request = %s auth=%q", r.URL.String(), r.Header.Get("Authorization"))
			}
			if state.formatSeen == "audiobook" {
				w.Header().Set("Content-Type", "application/zip")
			} else {
				w.Header().Set("Content-Type", "application/epub+zip")
			}
			w.Header().Set("Content-Range", "bytes 4-7/12")
			w.Header().Set("Content-Length", "4")
			w.Header().Set("Accept-Ranges", "bytes")
			w.Header().Set("ETag", `"edition-12"`)
			w.Header().Set("X-Storyteller-Hash", "sha256:content-hash")
			w.Header().Set("X-Internal-Path", `C:\Books\Red Rising.epub`)
			w.WriteHeader(http.StatusPartialContent)
			_, _ = io.WriteString(w, "4567")
		case "/api/v2/books/12/positions":
			if state.positions != nil {
				state.positions.serve(t, w, r)
				return
			}
			switch r.Method {
			case http.MethodGet:
				_, _ = io.WriteString(w, `{"uuid":"position-12","locator":{"href":"chapter-4.xhtml","type":"application/xhtml+xml","locations":{"progression":0.4,"totalProgression":0.32,"position":44},"text":{"highlight":"Darrow"}},"timestamp":1700000000000}`)
			case http.MethodPost:
				if state.conflict {
					http.Error(w, "newer position", http.StatusConflict)
					return
				}
				var body struct {
					Locator   json.RawMessage `json:"locator"`
					Timestamp int64           `json:"timestamp"`
				}
				if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
					t.Fatal(err)
				}
				state.saved, state.timestamp = body.Locator, body.Timestamp
				w.WriteHeader(http.StatusNoContent)
			}
		case "/api/Library/libraries":
			_, _ = io.WriteString(w, `[]`)
		default:
			http.NotFound(w, r)
		}
	}))
}

func TestReadingReadaloudRequiresAvailableEditionAndForwardsOnlyAllowedFormat(t *testing.T) {
	for _, available := range []bool{false, true} {
		state := &epubUpstreamState{readaloud: available}
		upstream := newEpubUpstream(t, state)
		handler := NewServer(readingCatalogConfig(upstream.URL, filepath.Join(t.TempDir(), "catalog.json"), []string{"reading"})).Handler()
		_, child := bindEpubWork(t, handler)
		path := "/v1/reading/works/" + child + "/publications/12/file"
		got := libraryRequest(handler, path+"?format=readaloud")
		if available {
			if got.Code != 206 || state.formatSeen != "readaloud" {
				t.Fatalf("readaloud = %d, format %q", got.Code, state.formatSeen)
			}
		} else if got.Code != 404 || state.fileCalls != 0 {
			t.Fatalf("unavailable = %d calls %d", got.Code, state.fileCalls)
		}
		for _, format := range []string{"audio", "../../secret", "http://example.org"} {
			if got := libraryRequest(handler, path+"?format="+format); got.Code != 400 {
				t.Fatalf("format %s = %d", format, got.Code)
			}
		}
		upstream.Close()
	}
}

func TestReadingAudiobookArchiveRequiresAvailableEdition(t *testing.T) {
	for _, available := range []bool{false, true} {
		state := &epubUpstreamState{audiobook: available}
		upstream := newEpubUpstream(t, state)
		handler := NewServer(readingCatalogConfig(upstream.URL, filepath.Join(t.TempDir(), "catalog.json"), []string{"reading"})).Handler()
		_, child := bindEpubWork(t, handler)
		got := libraryRequest(handler, "/v1/reading/works/"+child+"/publications/12/file?format=audiobook")
		if available {
			if got.Code != 206 || got.Header().Get("Content-Type") != "application/zip" || state.formatSeen != "audiobook" {
				t.Fatalf("audio archive = %d type=%q format=%q", got.Code, got.Header().Get("Content-Type"), state.formatSeen)
			}
		} else if got.Code != 404 || state.fileCalls != 0 {
			t.Fatalf("unavailable = %d calls %d", got.Code, state.fileCalls)
		}
		upstream.Close()
	}
}

func bindEpubWork(t *testing.T, handler http.Handler) (string, string) {
	t.Helper()
	page := libraryRequest(handler, "/v1/reading/libraries/storyteller:books/items?page=1&sort=title&direction=asc")
	if page.Code != http.StatusOK {
		t.Fatalf("bind shelf = %d: %s", page.Code, page.Body.String())
	}
	var shelf ReadingLibraryItemsResponse
	if err := json.Unmarshal(page.Body.Bytes(), &shelf); err != nil || len(shelf.Items) != 1 {
		t.Fatalf("shelf = %+v, %v", shelf, err)
	}
	collectionID := shelf.Items[0].ID
	detail := libraryRequest(handler, "/v1/reading/works/"+collectionID)
	if detail.Code != http.StatusOK {
		t.Fatalf("collection detail = %d: %s", detail.Code, detail.Body.String())
	}
	var work ReadingWork
	if err := json.Unmarshal(detail.Body.Bytes(), &work); err != nil || len(work.Sections) != 1 || len(work.Sections[0].Items) != 1 {
		t.Fatalf("collection = %+v, %v", work, err)
	}
	return collectionID, work.Sections[0].Items[0].WorkID
}

func TestReadingEpubStreamsValidatedRangesForCollectionAndChild(t *testing.T) {
	state := &epubUpstreamState{}
	upstream := newEpubUpstream(t, state)
	defer upstream.Close()
	handler := NewServer(readingCatalogConfig(upstream.URL, filepath.Join(t.TempDir(), "catalog.json"), []string{"reading"})).Handler()
	collectionID, childID := bindEpubWork(t, handler)

	for _, workID := range []string{collectionID, childID} {
		recorder := httptest.NewRecorder()
		request := httptest.NewRequest(http.MethodGet, "/v1/reading/works/"+workID+"/publications/12/file", nil)
		request.Header.Set("Authorization", "Bearer "+libraryTestToken)
		request.Header.Set("Range", "bytes=4-7")
		handler.ServeHTTP(recorder, request)
		if recorder.Code != http.StatusPartialContent || recorder.Body.String() != "4567" || state.rangeSeen != "bytes=4-7" {
			t.Fatalf("file for %s = %d %q range=%q", workID, recorder.Code, recorder.Body.String(), state.rangeSeen)
		}
		if recorder.Header().Get("Content-Range") != "bytes 4-7/12" || recorder.Header().Get("ETag") != `"edition-12"` || recorder.Header().Get("X-Reading-Content-Hash") != "sha256:content-hash" {
			t.Fatalf("safe headers = %+v", recorder.Header())
		}
		if recorder.Header().Get("X-Internal-Path") != "" || strings.Contains(recorder.Body.String(), "Books") {
			t.Fatalf("file response leaked upstream details: %+v", recorder.Header())
		}
	}
	if state.fileCalls != 2 {
		t.Fatalf("file calls = %d", state.fileCalls)
	}
}

func TestReadingEpubProgressRoundTripsFullLocatorAndSurfacesConflict(t *testing.T) {
	state := &epubUpstreamState{}
	upstream := newEpubUpstream(t, state)
	defer upstream.Close()
	handler := NewServer(readingCatalogConfig(upstream.URL, filepath.Join(t.TempDir(), "catalog.json"), []string{"reading"})).Handler()
	_, childID := bindEpubWork(t, handler)
	path := "/v1/reading/works/" + childID + "/publications/12/position"

	got := publicationRequest(handler, http.MethodGet, path, "")
	if got.Code != http.StatusOK || !bytes.Contains(got.Body.Bytes(), []byte(`"highlight":"Darrow"`)) || bytes.Contains(got.Body.Bytes(), []byte("story-token")) {
		t.Fatalf("position = %d: %s", got.Code, got.Body.String())
	}
	locator := `{"href":"chapter-5.xhtml","type":"application/xhtml+xml","locations":{"progression":0.1,"totalProgression":0.4,"position":51},"text":{"before":"red","highlight":"rising"}}`
	saved := publicationRequest(handler, http.MethodPost, path, `{"locator":`+locator+`,"timestamp":1700000001234}`)
	if saved.Code != http.StatusOK || state.timestamp != 1700000001234 || !bytes.Equal(state.saved, []byte(locator)) {
		t.Fatalf("save = %d %s upstream=%s @ %d", saved.Code, saved.Body.String(), state.saved, state.timestamp)
	}
	state.conflict = true
	conflict := publicationRequest(handler, http.MethodPost, path, `{"locator":`+locator+`,"timestamp":1}`)
	if conflict.Code != http.StatusConflict {
		t.Fatalf("conflict = %d: %s", conflict.Code, conflict.Body.String())
	}
}

func TestReadingEpubRejectsWrongBindingInvalidRangeLocatorAndMissingScope(t *testing.T) {
	state := &epubUpstreamState{}
	upstream := newEpubUpstream(t, state)
	defer upstream.Close()
	withScope := NewServer(readingCatalogConfig(upstream.URL, filepath.Join(t.TempDir(), "catalog.json"), []string{"reading"})).Handler()
	_, childID := bindEpubWork(t, withScope)

	for _, test := range []struct {
		method string
		path   string
		body   string
	}{
		{http.MethodGet, "/v1/reading/works/" + childID + "/publications/999/file", ""},
		{http.MethodGet, "/v1/reading/works/not-a-work/publications/12/file", ""},
		{http.MethodPost, "/v1/reading/works/" + childID + "/publications/12/position", `{"locator":{"locations":{}},"timestamp":1}`},
	} {
		got := publicationRequest(withScope, test.method, test.path, test.body)
		if got.Code != http.StatusNotFound && got.Code != http.StatusBadRequest {
			t.Errorf("%s %s = %d: %s", test.method, test.path, got.Code, got.Body.String())
		}
	}
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodGet, "/v1/reading/works/"+childID+"/publications/12/file", nil)
	request.Header.Set("Authorization", "Bearer "+libraryTestToken)
	request.Header.Set("Range", "bytes=0-4,8-12")
	withScope.ServeHTTP(recorder, request)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("multi-range = %d: %s", recorder.Code, recorder.Body.String())
	}
	withoutScope := NewServer(readingCatalogConfig(upstream.URL, filepath.Join(t.TempDir(), "catalog-2.json"), []string{"read"})).Handler()
	denied := libraryRequest(withoutScope, "/v1/reading/works/"+childID+"/publications/12/file")
	if denied.Code != http.StatusForbidden {
		t.Fatalf("missing scope = %d: %s", denied.Code, denied.Body.String())
	}
}

func TestReadingEpubConditionalCheckpointRejectsChangedBase(t *testing.T) {
	state := &epubUpstreamState{}
	upstream := newEpubUpstream(t, state)
	defer upstream.Close()
	handler := NewServer(readingCatalogConfig(upstream.URL, filepath.Join(t.TempDir(), "catalog.json"), []string{"reading"})).Handler()
	_, child := bindEpubWork(t, handler)
	path := "/v1/reading/works/" + child + "/publications/12/position"
	var current ReadingEpubPosition
	if err := json.Unmarshal(publicationRequest(handler, http.MethodGet, path, "").Body.Bytes(), &current); err != nil {
		t.Fatal(err)
	}
	local := `{"href":"chapter-5.xhtml","locations":{"position":51}}`
	bad := publicationRequest(handler, http.MethodPost, path, `{"locator":`+local+`,"timestamp":1700000001234,"checkBase":true,"expectedLocator":null}`)
	if bad.Code != http.StatusConflict || state.saved != nil {
		t.Fatalf("stale base: %d, saved=%s", bad.Code, state.saved)
	}
	good := publicationRequest(handler, http.MethodPost, path, `{"locator":`+local+`,"timestamp":1700000001234,"checkBase":true,"expectedLocator":`+string(current.Locator)+`}`)
	if good.Code != http.StatusOK || state.saved == nil {
		t.Fatalf("matching base: %d %s", good.Code, good.Body.String())
	}
}
