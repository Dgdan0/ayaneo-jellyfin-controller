package api

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"ayaneohub/internal/adapters/storyteller"
	"ayaneohub/internal/config"
)

func readingCatalogConfig(baseURL, catalogPath string, scopes []string) *config.Config {
	return &config.Config{
		Server: config.ServerConfig{ReadingCatalog: catalogPath},
		Auth: config.AuthConfig{
			Tokens:    []config.TokenConfig{{Label: "reader", Raw: config.Secret(libraryTestToken), Scopes: scopes}},
			RateLimit: config.RateLimitConfig{RPM: 600, Burst: 100}, AuthFailureBan: config.BanConfig{Attempts: 5},
		},
		Services: map[string]config.ServiceConfig{
			"kavita":      {Enabled: true, BaseURL: baseURL, APIKey: config.Secret("kavita-key")},
			"storyteller": {Enabled: true, BaseURL: baseURL, Username: "reader", Password: config.Secret("secret")},
		},
	}
}

func newReadingCatalogUpstream(t *testing.T) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v2/token":
			_, _ = io.WriteString(w, `{"access_token":"story-token","token_type":"Bearer","expires_in":3600}`)
			return
		case "/api/v2/books":
			if r.Header.Get("Authorization") != "Bearer story-token" {
				t.Fatal("Storyteller bearer token missing")
			}
			_, _ = io.WriteString(w, `[{"id":12,"uuid":"story-book","title":"Red Rising","description":"Darrow's story.","language":"en","authors":[{"name":"Pierce Brown"}],"ebook":{"uuid":"ebook-1","pageCount":400},"audiobook":{"uuid":"audio-1","duration":7200},"position":{"locator":{"locations":{"totalProgression":0.25}}}}]`)
			return
		case "/api/v2/books/12":
			if r.Header.Get("Authorization") != "Bearer story-token" {
				t.Fatal("Storyteller bearer token missing")
			}
			_, _ = io.WriteString(w, `{"id":12,"uuid":"story-book","title":"Red Rising","description":"Darrow's story.","language":"en","authors":[{"name":"Pierce Brown"}],"ebook":{"uuid":"ebook-1","pageCount":400},"audiobook":{"uuid":"audio-1","duration":7200},"position":{"locator":{"locations":{"totalProgression":0.25}}}}`)
			return
		case "/api/v2/books/12/cover":
			if r.Header.Get("Authorization") != "Bearer story-token" {
				t.Fatal("Storyteller bearer token missing")
			}
			w.Header().Set("Content-Type", "image/jpeg")
			_, _ = w.Write([]byte("story-cover"))
			return
		}

		if r.Header.Get("X-Api-Key") != "kavita-key" {
			t.Fatal("Kavita X-Api-Key missing")
		}
		switch r.URL.Path {
		case "/api/Library/libraries":
			_, _ = io.WriteString(w, `[{"id":2,"name":"Comics","type":1}]`)
		case "/api/Series/v2":
			w.Header().Set("Pagination", `{"currentPage":1,"itemsPerPage":60,"totalItems":1,"totalPages":1}`)
			_, _ = io.WriteString(w, `[{"id":9,"name":"Saga","sortName":"Saga","libraryId":2,"pages":12,"pagesRead":3,"format":1}]`)
		case "/api/Series/9":
			_, _ = io.WriteString(w, `{"id":9,"name":"Saga","sortName":"Saga","libraryId":2,"pages":12,"pagesRead":3,"format":1}`)
		case "/api/Series/metadata":
			_, _ = io.WriteString(w, `{"summary":"A space opera.","releaseYear":2012,"language":"en","writers":[{"name":"Brian K. Vaughan"}],"genres":[{"title":"Science Fiction"}]}`)
		case "/api/Series/volumes":
			_, _ = io.WriteString(w, `[{"id":5,"name":"1","number":1,"pages":12,"pagesRead":3,"chapters":[{"id":6,"title":"Issue 1","number":"1","pages":12,"pagesRead":3,"format":1}]}]`)
		case "/api/Reader/continue-point":
			_, _ = io.WriteString(w, `{"id":6,"title":"Issue 1","number":"1","pages":12,"pagesRead":3}`)
		case "/api/Image/series-cover":
			w.Header().Set("Content-Type", "image/png")
			_, _ = w.Write([]byte("kavita-cover"))
		default:
			http.NotFound(w, r)
		}
	}))
}

func TestReadingLibrariesCombineKavitaAndStoryteller(t *testing.T) {
	upstream := newReadingCatalogUpstream(t)
	defer upstream.Close()
	handler := NewServer(readingCatalogConfig(upstream.URL, filepath.Join(t.TempDir(), "catalog.json"), []string{"reading"})).Handler()

	got := libraryRequest(handler, "/v1/reading/libraries")
	if got.Code != http.StatusOK {
		t.Fatalf("status = %d: %s", got.Code, got.Body.String())
	}
	var body ReadingLibrariesResponse
	if err := json.Unmarshal(got.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if len(body.Libraries) != 2 || body.Libraries[0].ID != "kavita:2" || body.Libraries[0].Kind != "comic" || body.Libraries[1].ID != "storyteller:books" {
		t.Fatalf("libraries = %+v", body.Libraries)
	}
	if len(body.Partial) != 0 {
		t.Fatalf("partial = %+v", body.Partial)
	}
}

func TestReadingLibraryItemsAndDetailsUseStableHubWorkIDs(t *testing.T) {
	upstream := newReadingCatalogUpstream(t)
	defer upstream.Close()
	catalogPath := filepath.Join(t.TempDir(), "catalog.json")
	handler := NewServer(readingCatalogConfig(upstream.URL, catalogPath, []string{"reading"})).Handler()

	page := libraryRequest(handler, "/v1/reading/libraries/kavita:2/items?page=1&sort=title&direction=asc")
	if page.Code != http.StatusOK {
		t.Fatalf("page status = %d: %s", page.Code, page.Body.String())
	}
	var kavitaPage ReadingLibraryItemsResponse
	if err := json.Unmarshal(page.Body.Bytes(), &kavitaPage); err != nil {
		t.Fatal(err)
	}
	if kavitaPage.Page != 1 || kavitaPage.Total != 1 || kavitaPage.HasMore || len(kavitaPage.Items) != 1 {
		t.Fatalf("page = %+v", kavitaPage)
	}
	comic := kavitaPage.Items[0]
	if !strings.HasPrefix(comic.ID, "rw_") || comic.LibraryID != "kavita:2" || comic.Kind != "comic" || comic.Progress == nil || comic.Progress.Percentage != 0.25 {
		t.Fatalf("comic = %+v", comic)
	}
	if comic.Artwork != "/v1/img/reading/kavita/9" {
		t.Fatalf("comic artwork = %q", comic.Artwork)
	}

	detailResponse := libraryRequest(handler, "/v1/reading/works/"+comic.ID)
	if detailResponse.Code != http.StatusOK {
		t.Fatalf("detail status = %d: %s", detailResponse.Code, detailResponse.Body.String())
	}
	var detail ReadingWork
	if err := json.Unmarshal(detailResponse.Body.Bytes(), &detail); err != nil {
		t.Fatal(err)
	}
	if detail.ID != comic.ID || detail.Overview != "A space opera." || len(detail.Authors) != 1 || len(detail.Editions) != 1 || detail.Editions[0].Kind != "comic" {
		t.Fatalf("detail = %+v", detail)
	}
	if len(detail.Sections) != 1 || len(detail.Sections[0].Items) != 1 || detail.Continue == nil || detail.Continue.SourceItemID != "6" {
		t.Fatalf("detail hierarchy = %+v / continue %+v", detail.Sections, detail.Continue)
	}

	restarted := NewServer(readingCatalogConfig(upstream.URL, catalogPath, []string{"reading"})).Handler()
	restored := libraryRequest(restarted, "/v1/reading/works/"+comic.ID)
	if restored.Code != http.StatusOK {
		t.Fatalf("persisted work id status = %d: %s", restored.Code, restored.Body.String())
	}
}

func TestStorytellerLibraryMapsEditionsAndProgress(t *testing.T) {
	upstream := newReadingCatalogUpstream(t)
	defer upstream.Close()
	handler := NewServer(readingCatalogConfig(upstream.URL, filepath.Join(t.TempDir(), "catalog.json"), []string{"reading"})).Handler()
	page := libraryRequest(handler, "/v1/reading/libraries/storyteller:books/items?page=1&sort=title&direction=asc")
	if page.Code != http.StatusOK {
		t.Fatalf("page status = %d: %s", page.Code, page.Body.String())
	}
	var body ReadingLibraryItemsResponse
	if err := json.Unmarshal(page.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if len(body.Items) != 1 || len(body.Items[0].Availability) != 2 || body.Items[0].Progress == nil || body.Items[0].Progress.Percentage != 0.25 {
		t.Fatalf("items = %+v", body.Items)
	}
	detailResponse := libraryRequest(handler, "/v1/reading/works/"+body.Items[0].ID)
	if detailResponse.Code != http.StatusOK {
		t.Fatalf("detail status = %d: %s", detailResponse.Code, detailResponse.Body.String())
	}
	var detail ReadingWork
	if err := json.Unmarshal(detailResponse.Body.Bytes(), &detail); err != nil {
		t.Fatal(err)
	}
	if len(detail.Editions) != 2 || detail.Editions[0].Source != "storyteller" || detail.Authors[0] != "Pierce Brown" {
		t.Fatalf("detail = %+v", detail)
	}
}

func TestReadingCatalogImagesProxyAuthenticatedSources(t *testing.T) {
	upstream := newReadingCatalogUpstream(t)
	defer upstream.Close()
	handler := NewServer(readingCatalogConfig(upstream.URL, filepath.Join(t.TempDir(), "catalog.json"), []string{"reading"})).Handler()
	for path, expected := range map[string][2]string{
		"/v1/img/reading/kavita/9":       {"image/png", "kavita-cover"},
		"/v1/img/reading/storyteller/12": {"image/jpeg", "story-cover"},
	} {
		got := libraryRequest(handler, path)
		if got.Code != http.StatusOK || got.Header().Get("Content-Type") != expected[0] || got.Body.String() != expected[1] {
			t.Fatalf("%s = %d %q %q", path, got.Code, got.Header().Get("Content-Type"), got.Body.String())
		}
	}
}

func TestKavitaUnnumberedChapterSentinelIsNotExposed(t *testing.T) {
	for input, want := range map[string]string{
		"-100000": "",
		"100000":  "",
		" 1 ":     "1",
		"Special": "Special",
	} {
		if got := kavitaChapterNumber(input); got != want {
			t.Errorf("kavitaChapterNumber(%q) = %q, want %q", input, got, want)
		}
	}
}

func TestReadingCatalogRoutesRequireScopeAndValidateIDs(t *testing.T) {
	upstream := newReadingCatalogUpstream(t)
	defer upstream.Close()
	withoutScope := NewServer(readingCatalogConfig(upstream.URL, "", []string{"read"})).Handler()
	if got := libraryRequest(withoutScope, "/v1/reading/libraries"); got.Code != http.StatusForbidden {
		t.Fatalf("without scope = %d", got.Code)
	}
	withScope := NewServer(readingCatalogConfig(upstream.URL, "", []string{"reading"})).Handler()
	for _, path := range []string{
		"/v1/reading/libraries/kavita:nope/items?page=1&sort=title&direction=asc",
		"/v1/reading/libraries/kavita:2/items?page=0&sort=title&direction=asc",
		"/v1/reading/libraries/storyteller:books/items?page=1&sort=nope&direction=asc",
		"/v1/reading/works/not-a-work-id",
		"/v1/img/reading/kavita/not-a-number",
	} {
		if got := libraryRequest(withScope, path); got.Code != http.StatusBadRequest {
			t.Errorf("%s = %d, want 400: %s", path, got.Code, got.Body.String())
		}
	}
}

func TestReadingCatalogReturnsNotFoundForMissingKavitaLibrary(t *testing.T) {
	upstream := newReadingCatalogUpstream(t)
	defer upstream.Close()
	handler := NewServer(readingCatalogConfig(upstream.URL, filepath.Join(t.TempDir(), "catalog.json"), []string{"reading"})).Handler()

	got := libraryRequest(handler, "/v1/reading/libraries/kavita:999/items?page=1&sort=title&direction=asc")
	if got.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404: %s", got.Code, got.Body.String())
	}
}

func TestSortStorytellerBooksIsDeterministicForEqualValues(t *testing.T) {
	books := []storyteller.Book{
		{ID: 2, Title: "Same", CreatedAt: "2026-01-01"},
		{ID: 1, Title: "Same", CreatedAt: "2026-01-01"},
		{ID: 3, Title: "Later", CreatedAt: "2026-02-01"},
	}

	sortStorytellerBooks(books, "added", "desc")
	got := []int64{books[0].ID, books[1].ID, books[2].ID}
	want := []int64{3, 1, 2}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("order = %v, want %v", got, want)
		}
	}
	sortStorytellerBooks(books, "added", "desc")
	gotAgain := []int64{books[0].ID, books[1].ID, books[2].ID}
	for i := range want {
		if gotAgain[i] != want[i] {
			t.Fatalf("second order = %v, want stable %v", gotAgain, want)
		}
	}
}
