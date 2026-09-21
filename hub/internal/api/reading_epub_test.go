package api

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
)

type epubUpstreamState struct {
	fileCalls int
	rangeSeen string
	saved     json.RawMessage
	timestamp int64
	conflict  bool
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
			_, _ = io.WriteString(w, `{"id":12,"uuid":"book-12","title":"Red Rising","authors":[{"name":"Pierce Brown"}],"series":[{"uuid":"series-red","name":"Red Rising","position":1}],"ebook":{"uuid":"ebook-12","pageCount":400}}`)
		case "/api/v2/books/12/files":
			state.fileCalls++
			state.rangeSeen = r.Header.Get("Range")
			if r.URL.Query().Get("format") != "ebook" || r.Header.Get("Authorization") != "Bearer story-token" {
				t.Fatalf("file request = %s auth=%q", r.URL.String(), r.Header.Get("Authorization"))
			}
			w.Header().Set("Content-Type", "application/epub+zip")
			w.Header().Set("Content-Range", "bytes 4-7/12")
			w.Header().Set("Content-Length", "4")
			w.Header().Set("Accept-Ranges", "bytes")
			w.Header().Set("ETag", `"edition-12"`)
			w.Header().Set("X-Storyteller-Hash", "sha256:content-hash")
			w.Header().Set("X-Internal-Path", `C:\Books\Red Rising.epub`)
			w.WriteHeader(http.StatusPartialContent)
			_, _ = io.WriteString(w, "4567")
		case "/api/v2/books/12/positions":
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
