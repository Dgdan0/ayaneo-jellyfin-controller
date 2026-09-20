package bookkeeprr

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"ayaneohub/internal/config"
)

func TestBrowseUsesBearerAuthAndDecodesRows(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/discover/browse" || r.URL.Query().Get("contentType") != "ebook" {
			t.Fatalf("request = %s %s", r.Method, r.URL.String())
		}
		if got := r.Header.Get("Authorization"); got != "Bearer test-key" {
			t.Fatalf("Authorization = %q", got)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"rows":[{"id":"ebook-trending","label":"Trending books","meta":"Popular now","items":[{"contentType":"ebook","source":"openlibrary","sourceId":"OL123W","title":"Red Rising","author":"Pierce Brown","year":2014,"isbn":"9780345539786","coverUrl":"https://covers.example/red-rising.jpg","description":"The first book.","inLib":true}]}]}`))
	}))
	defer upstream.Close()

	client, err := New(config.ServiceConfig{BaseURL: upstream.URL, APIKey: config.Secret("test-key")})
	if err != nil {
		t.Fatal(err)
	}
	got, err := client.Browse(context.Background(), TypeEbook)
	if err != nil {
		t.Fatal(err)
	}
	if len(got.Rows) != 1 || got.Rows[0].ID != "ebook-trending" || len(got.Rows[0].Items) != 1 {
		t.Fatalf("Browse() = %+v", got)
	}
	item := got.Rows[0].Items[0]
	if item.Title != "Red Rising" || item.Author != "Pierce Brown" || !item.InLibrary {
		t.Fatalf("item = %+v", item)
	}
}

func TestCategoryCarriesTypeRowAndPage(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		query := r.URL.Query()
		if r.URL.Path != "/api/discover/category" || query.Get("contentType") != "manga" ||
			query.Get("row") != "trending" || query.Get("page") != "3" {
			t.Fatalf("request = %s", r.URL.String())
		}
		_, _ = w.Write([]byte(`{"items":[{"contentType":"manga","source":"anilist","sourceId":42,"title":"Frieren"}],"hasMore":true}`))
	}))
	defer upstream.Close()

	client, err := New(config.ServiceConfig{BaseURL: upstream.URL, APIKey: config.Secret("key")})
	if err != nil {
		t.Fatal(err)
	}
	got, err := client.Category(context.Background(), TypeManga, "trending", 3)
	if err != nil {
		t.Fatal(err)
	}
	if !got.HasMore || len(got.Items) != 1 || got.Items[0].SourceID != "42" {
		t.Fatalf("Category() = %+v", got)
	}
}

func TestSearchSupportsAllTypesAndReportsProviderErrors(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/discover/search" || r.URL.Query().Get("q") != "Red Rising" ||
			r.URL.Query().Get("contentType") != "all" {
			t.Fatalf("request = %s", r.URL.String())
		}
		_, _ = w.Write([]byte(`{"results":[{"contentType":"audiobook","source":"audnex","sourceId":"abc","title":"Red Rising"}],"tookMs":91,"errors":[{"source":"openlibrary","message":"timed out"}]}`))
	}))
	defer upstream.Close()

	client, err := New(config.ServiceConfig{BaseURL: upstream.URL, APIKey: config.Secret("key")})
	if err != nil {
		t.Fatal(err)
	}
	got, err := client.Search(context.Background(), "Red Rising", TypeAll)
	if err != nil {
		t.Fatal(err)
	}
	if len(got.Results) != 1 || got.TookMS != 91 || len(got.Errors) != 1 || got.Errors[0].Source != "openlibrary" {
		t.Fatalf("Search() = %+v", got)
	}
}

func TestRejectsInvalidInputsBeforeCallingUpstream(t *testing.T) {
	calls := 0
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { calls++ }))
	defer upstream.Close()
	client, err := New(config.ServiceConfig{BaseURL: upstream.URL, APIKey: config.Secret("key")})
	if err != nil {
		t.Fatal(err)
	}

	if _, err := client.Browse(context.Background(), ContentType("movie")); err == nil {
		t.Error("Browse accepted a media content type")
	}
	if _, err := client.Category(context.Background(), TypeEbook, "", 1); err == nil {
		t.Error("Category accepted an empty row")
	}
	if _, err := client.Category(context.Background(), TypeEbook, "row", 0); err == nil {
		t.Error("Category accepted page zero")
	}
	if _, err := client.Search(context.Background(), "  ", TypeAll); err == nil {
		t.Error("Search accepted an empty query")
	}
	if calls != 0 {
		t.Fatalf("invalid inputs made %d upstream requests", calls)
	}
}
