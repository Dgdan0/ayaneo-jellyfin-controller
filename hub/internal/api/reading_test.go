package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"ayaneohub/internal/config"
)

func readingAPIConfig(baseURL string, scopes []string) *config.Config {
	return &config.Config{
		Auth: config.AuthConfig{
			Tokens: []config.TokenConfig{{
				Label: "reader", Raw: config.Secret(libraryTestToken), Scopes: scopes,
			}},
			RateLimit:      config.RateLimitConfig{RPM: 600, Burst: 100},
			AuthFailureBan: config.BanConfig{Attempts: 5},
		},
		Services: map[string]config.ServiceConfig{
			"bookkeeprr": {Enabled: true, BaseURL: baseURL, APIKey: config.Secret("book-key")},
		},
	}
}

func TestReadingDiscoverNormalizesBookKeeprrAndHidesCoverURL(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/discover/browse" || r.URL.Query().Get("contentType") != "ebook" {
			t.Fatalf("upstream request = %s", r.URL.String())
		}
		if r.Header.Get("Authorization") != "Bearer book-key" {
			t.Fatal("BookKeeprr bearer token missing")
		}
		_, _ = w.Write([]byte(`{"rows":[{"id":"ebook-trending","label":"Trending books","items":[{"contentType":"ebook","source":"openlibrary","sourceId":"OL123W","title":"Red Rising","author":"Pierce Brown","year":2014,"isbn":"9780345539786","coverUrl":"https://covers.example/red-rising.jpg","description":"The first book.","inLib":true}]}]}`))
	}))
	defer upstream.Close()

	got := libraryRequest(NewServer(readingAPIConfig(upstream.URL, []string{"reading"})).Handler(),
		"/v1/reading/discover?type=ebook")
	if got.Code != http.StatusOK {
		t.Fatalf("status = %d: %s", got.Code, got.Body.String())
	}
	var body ReadingDiscoverResponse
	if err := json.Unmarshal(got.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if len(body.Rows) != 1 || body.Rows[0].ContentType != "ebook" || len(body.Rows[0].Items) != 1 {
		t.Fatalf("body = %+v", body)
	}
	item := body.Rows[0].Items[0]
	if item.Title != "Red Rising" || item.Author != "Pierce Brown" || !item.InLibrary {
		t.Fatalf("item = %+v", item)
	}
	if !strings.HasPrefix(item.Cover, "/v1/img/reading/") || strings.Contains(item.Cover, "covers.example") {
		t.Fatalf("cover leaked the upstream URL: %q", item.Cover)
	}
}

func TestReadingCategoryAndSearchUseNormalizedContracts(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/discover/category":
			if r.URL.Query().Get("contentType") != "manga" || r.URL.Query().Get("row") != "trending" || r.URL.Query().Get("page") != "2" {
				t.Fatalf("category query = %s", r.URL.RawQuery)
			}
			_, _ = w.Write([]byte(`{"items":[{"contentType":"manga","source":"anilist","sourceId":"9","title":"Nana"}],"hasMore":false}`))
		case "/api/discover/search":
			if r.URL.Query().Get("q") != "Red Rising" || r.URL.Query().Get("contentType") != "all" {
				t.Fatalf("search query = %s", r.URL.RawQuery)
			}
			_, _ = w.Write([]byte(`{"results":[{"contentType":"audiobook","source":"audnex","sourceId":"a1","title":"Red Rising","author":"Pierce Brown"}],"tookMs":25,"errors":[]}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer upstream.Close()

	handler := NewServer(readingAPIConfig(upstream.URL, []string{"reading"})).Handler()
	category := libraryRequest(handler, "/v1/reading/discover/trending?type=manga&page=2")
	if category.Code != http.StatusOK {
		t.Fatalf("category status = %d: %s", category.Code, category.Body.String())
	}
	var discover ReadingDiscoverResponse
	if err := json.Unmarshal(category.Body.Bytes(), &discover); err != nil {
		t.Fatal(err)
	}
	if len(discover.Rows) != 1 || discover.Rows[0].Page != 2 || discover.Rows[0].HasMore || discover.Rows[0].Items[0].Title != "Nana" {
		t.Fatalf("category = %+v", discover)
	}

	search := libraryRequest(handler, "/v1/reading/search?q=Red%20Rising&type=all")
	if search.Code != http.StatusOK {
		t.Fatalf("search status = %d: %s", search.Code, search.Body.String())
	}
	var found ReadingSearchResponse
	if err := json.Unmarshal(search.Body.Bytes(), &found); err != nil {
		t.Fatal(err)
	}
	if found.Query != "Red Rising" || found.ContentType != "all" || len(found.Results) != 1 || found.Results[0].ContentType != "audiobook" {
		t.Fatalf("search = %+v", found)
	}
}

func TestReadingRoutesRequireReadingScopeAndValidateInputs(t *testing.T) {
	calls := 0
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { calls++ }))
	defer upstream.Close()

	withoutScope := NewServer(readingAPIConfig(upstream.URL, []string{"read"})).Handler()
	if got := libraryRequest(withoutScope, "/v1/reading/discover?type=ebook"); got.Code != http.StatusForbidden {
		t.Fatalf("without reading scope = %d, want 403", got.Code)
	}

	withScope := NewServer(readingAPIConfig(upstream.URL, []string{"reading"})).Handler()
	for _, path := range []string{
		"/v1/reading/discover?type=movie",
		"/v1/reading/discover/trending?type=ebook&page=0",
		"/v1/reading/search?type=all",
	} {
		if got := libraryRequest(withScope, path); got.Code != http.StatusBadRequest {
			t.Errorf("%s = %d, want 400", path, got.Code)
		}
	}
	if calls != 0 {
		t.Fatalf("rejected requests made %d upstream calls", calls)
	}
}

func TestReadingAllKeepsUsefulRowsWhenOneTypeFails(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		kind := r.URL.Query().Get("contentType")
		if kind == "comic" {
			http.Error(w, "provider unavailable", http.StatusBadGateway)
			return
		}
		if kind == "ebook" {
			_, _ = w.Write([]byte(`{"rows":[{"id":"ebook-trending","label":"Trending books","items":[{"contentType":"ebook","source":"openlibrary","sourceId":"one","title":"One Book"}]}]}`))
			return
		}
		_, _ = w.Write([]byte(`{"rows":[]}`))
	}))
	defer upstream.Close()

	got := libraryRequest(NewServer(readingAPIConfig(upstream.URL, []string{"reading"})).Handler(),
		"/v1/reading/discover?type=all")
	if got.Code != http.StatusOK {
		t.Fatalf("status = %d: %s", got.Code, got.Body.String())
	}
	var body ReadingDiscoverResponse
	if err := json.Unmarshal(got.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if len(body.Rows) != 1 || body.Rows[0].Items[0].Title != "One Book" {
		t.Fatalf("rows = %+v", body.Rows)
	}
	if len(body.Partial) != 1 || body.Partial[0].Affects[0] != "reading.comic" {
		t.Fatalf("partial = %+v", body.Partial)
	}
}
