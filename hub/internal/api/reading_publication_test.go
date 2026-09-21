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

	"ayaneohub/internal/adapters/kavita"
)

type publicationUpstreamState struct {
	chapterInfoCalls int
	pageCalls        int
	saved            kavita.Progress
}

func newReadingPublicationUpstream(t *testing.T, state *publicationUpstreamState) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Api-Key") != "kavita-key" {
			t.Fatalf("Kavita auth = %q", r.Header.Get("X-Api-Key"))
		}
		switch r.URL.Path {
		case "/api/Library/libraries":
			_, _ = io.WriteString(w, `[{"id":2,"name":"Comics","type":1}]`)
		case "/api/Series/v2":
			w.Header().Set("Pagination", `{"currentPage":1,"itemsPerPage":60,"totalItems":1,"totalPages":1}`)
			_, _ = io.WriteString(w, `[{"id":9,"name":"Saga","sortName":"Saga","libraryId":2,"pages":3,"pagesRead":1,"format":1}]`)
		case "/api/Series/9":
			_, _ = io.WriteString(w, `{"id":9,"name":"Saga","sortName":"Saga","libraryId":2,"pages":3,"pagesRead":1,"format":1}`)
		case "/api/Series/metadata":
			_, _ = io.WriteString(w, `{"summary":"A space opera.","writers":[{"name":"Brian K. Vaughan"}]}`)
		case "/api/Series/volumes":
			_, _ = io.WriteString(w, `[{"id":5,"name":"1","number":1,"pages":3,"pagesRead":1,"chapters":[{"id":6,"title":"Issue 1","number":"1","pages":3,"pagesRead":1,"format":1}]}]`)
		case "/api/Reader/continue-point":
			_, _ = io.WriteString(w, `{"id":6,"title":"Issue 1","number":"1","pages":3,"pagesRead":1}`)
		case "/api/Reader/chapter-info":
			state.chapterInfoCalls++
			if r.URL.Query().Get("chapterId") != "6" || r.URL.Query().Get("includeDimensions") != "true" {
				t.Fatalf("chapter info query = %s", r.URL.RawQuery)
			}
			_, _ = io.WriteString(w, `{"chapterNumber":"1","volumeNumber":"1","volumeId":5,"seriesName":"Saga","seriesId":9,"libraryId":2,"libraryType":1,"chapterTitle":"Issue 1","pages":3,"pageDimensions":[{"width":1200,"height":1800,"pageNumber":0,"fileName":"000.jpg","isWide":false},{"width":1200,"height":1800,"pageNumber":1,"fileName":"001.jpg","isWide":false},{"width":2400,"height":1600,"pageNumber":2,"fileName":"002.jpg","isWide":true}],"doublePairs":{"0":1}}`)
		case "/api/Reader/get-progress":
			_, _ = io.WriteString(w, `{"volumeId":5,"chapterId":6,"pageNum":1,"seriesId":9,"libraryId":2}`)
		case "/api/Reader/image":
			state.pageCalls++
			if r.URL.Query().Get("chapterId") != "6" || r.URL.Query().Get("page") != "2" || r.URL.Query().Get("apiKey") != "kavita-key" {
				t.Fatalf("page query = %s", r.URL.RawQuery)
			}
			w.Header().Set("Content-Type", "image/jpeg")
			w.Header().Set("Content-Length", "10")
			w.Header().Set("Cache-Control", "private, max-age=120")
			_, _ = w.Write([]byte("page-image"))
		case "/api/Reader/progress":
			if err := json.NewDecoder(r.Body).Decode(&state.saved); err != nil {
				t.Fatal(err)
			}
			w.WriteHeader(http.StatusNoContent)
		default:
			http.NotFound(w, r)
		}
	}))
}

func publicationRequest(handler http.Handler, method, path, body string) *httptest.ResponseRecorder {
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(method, path, strings.NewReader(body))
	request.Header.Set("Authorization", "Bearer "+libraryTestToken)
	if body != "" {
		request.Header.Set("Content-Type", "application/json")
	}
	handler.ServeHTTP(recorder, request)
	return recorder
}

func bindPublicationWork(t *testing.T, handler http.Handler) string {
	t.Helper()
	response := libraryRequest(handler, "/v1/reading/libraries/kavita:2/items?page=1&sort=title&direction=asc")
	if response.Code != http.StatusOK {
		t.Fatalf("bind page = %d: %s", response.Code, response.Body.String())
	}
	var page ReadingLibraryItemsResponse
	if err := json.Unmarshal(response.Body.Bytes(), &page); err != nil {
		t.Fatal(err)
	}
	if len(page.Items) != 1 {
		t.Fatalf("bind items = %+v", page.Items)
	}
	return page.Items[0].ID
}

func TestReadingPublicationManifestHidesKavitaCoordinatesAndPreservesPageMetadata(t *testing.T) {
	state := &publicationUpstreamState{}
	upstream := newReadingPublicationUpstream(t, state)
	defer upstream.Close()
	handler := NewServer(readingCatalogConfig(upstream.URL, filepath.Join(t.TempDir(), "catalog.json"), []string{"reading"})).Handler()
	workID := bindPublicationWork(t, handler)

	got := libraryRequest(handler, "/v1/reading/works/"+workID+"/publications/6")
	if got.Code != http.StatusOK {
		t.Fatalf("manifest = %d: %s", got.Code, got.Body.String())
	}
	var manifest ReadingPublicationManifest
	if err := json.Unmarshal(got.Body.Bytes(), &manifest); err != nil {
		t.Fatal(err)
	}
	if manifest.WorkID != workID || manifest.Source != "kavita" || manifest.SourceItemID != "6" || manifest.SeriesTitle != "Saga" || manifest.Title != "Issue 1" {
		t.Fatalf("manifest identity = %+v", manifest)
	}
	if manifest.Kind != "comic" || manifest.Direction != "ltr" || manifest.PageCount != 3 || manifest.CurrentPage != 1 || len(manifest.Pages) != 3 || !manifest.Pages[2].IsWide {
		t.Fatalf("manifest pages = %+v", manifest)
	}
	if manifest.PreviousSourceItemID != "" || manifest.NextSourceItemID != "" || manifest.DoublePairs["0"] != 1 {
		t.Fatalf("manifest navigation = %+v", manifest)
	}
	encoded := got.Body.String()
	if strings.Contains(encoded, "kavita-key") || strings.Contains(encoded, upstream.URL) || strings.Contains(encoded, "fileName") || strings.Contains(encoded, "000.jpg") {
		t.Fatalf("manifest leaked upstream details: %s", encoded)
	}
}

func TestReadingPublicationPageStreamsOnlyValidatedChapterPages(t *testing.T) {
	state := &publicationUpstreamState{}
	upstream := newReadingPublicationUpstream(t, state)
	defer upstream.Close()
	handler := NewServer(readingCatalogConfig(upstream.URL, filepath.Join(t.TempDir(), "catalog.json"), []string{"reading"})).Handler()
	workID := bindPublicationWork(t, handler)

	got := libraryRequest(handler, "/v1/reading/works/"+workID+"/publications/6/pages/2")
	if got.Code != http.StatusOK || got.Header().Get("Content-Type") != "image/jpeg" || got.Body.String() != "page-image" || state.pageCalls != 1 {
		t.Fatalf("page = %d %q %q calls=%d", got.Code, got.Header().Get("Content-Type"), got.Body.String(), state.pageCalls)
	}
	if got.Header().Get("Cache-Control") != "private, max-age=120" {
		t.Fatalf("cache control = %q", got.Header().Get("Cache-Control"))
	}
	outside := libraryRequest(handler, "/v1/reading/works/"+workID+"/publications/6/pages/3")
	if outside.Code != http.StatusBadRequest || state.pageCalls != 1 {
		t.Fatalf("outside page = %d calls=%d: %s", outside.Code, state.pageCalls, outside.Body.String())
	}
}

func TestReadingPublicationProgressUsesServerValidatedKavitaIdentifiers(t *testing.T) {
	state := &publicationUpstreamState{}
	upstream := newReadingPublicationUpstream(t, state)
	defer upstream.Close()
	handler := NewServer(readingCatalogConfig(upstream.URL, filepath.Join(t.TempDir(), "catalog.json"), []string{"reading"})).Handler()
	workID := bindPublicationWork(t, handler)

	got := publicationRequest(handler, http.MethodPost, "/v1/reading/works/"+workID+"/publications/6/progress", `{"pageIndex":2}`)
	if got.Code != http.StatusOK {
		t.Fatalf("progress = %d: %s", got.Code, got.Body.String())
	}
	if state.saved.VolumeID != 5 || state.saved.ChapterID != 6 || state.saved.PageNum != 2 || state.saved.SeriesID != 9 || state.saved.LibraryID != 2 {
		t.Fatalf("saved = %+v", state.saved)
	}
	if bytes.Contains(got.Body.Bytes(), []byte("kavita-key")) {
		t.Fatalf("progress response leaked a key: %s", got.Body.String())
	}
}

func TestReadingPublicationRejectsWrongWorkChapterInvalidIDsAndMissingScope(t *testing.T) {
	state := &publicationUpstreamState{}
	upstream := newReadingPublicationUpstream(t, state)
	defer upstream.Close()
	withScope := NewServer(readingCatalogConfig(upstream.URL, filepath.Join(t.TempDir(), "catalog.json"), []string{"reading"})).Handler()
	workID := bindPublicationWork(t, withScope)

	wrong := libraryRequest(withScope, "/v1/reading/works/"+workID+"/publications/999")
	if wrong.Code != http.StatusNotFound || state.chapterInfoCalls != 0 {
		t.Fatalf("wrong chapter = %d, info calls=%d: %s", wrong.Code, state.chapterInfoCalls, wrong.Body.String())
	}
	for _, path := range []string{
		"/v1/reading/works/not-a-work/publications/6",
		"/v1/reading/works/" + workID + "/publications/nope",
		"/v1/reading/works/" + workID + "/publications/6/pages/-1",
	} {
		if got := libraryRequest(withScope, path); got.Code != http.StatusBadRequest {
			t.Errorf("%s = %d, want 400: %s", path, got.Code, got.Body.String())
		}
	}
	withoutScope := NewServer(readingCatalogConfig(upstream.URL, "", []string{"read"})).Handler()
	if got := libraryRequest(withoutScope, "/v1/reading/works/"+workID+"/publications/6"); got.Code != http.StatusForbidden {
		t.Fatalf("without scope = %d", got.Code)
	}
}
