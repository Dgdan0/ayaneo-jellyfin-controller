package komga

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"reflect"
	"strconv"
	"strings"
	"sync"
	"testing"
)

func TestExportReadingStatePaginatesAndPreservesReadListOrder(t *testing.T) {
	var mu sync.Mutex
	requests := make([]string, 0)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		username, password, ok := r.BasicAuth()
		if !ok || username != "reader@example.test" || password != "test-password" {
			t.Fatalf("basic auth = %q %q %v", username, password, ok)
		}
		mu.Lock()
		requests = append(requests, r.Method+" "+r.URL.RequestURI())
		mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/api/v2/users/me":
			_, _ = w.Write([]byte(`{"id":"user-1","email":"reader@example.test"}`))
		case r.Method == http.MethodPost && r.URL.Path == "/api/v1/books/list":
			var body map[string]any
			if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
				t.Fatal(err)
			}
			if len(body) != 0 {
				t.Fatalf("unfiltered book search body = %+v", body)
			}
			switch r.URL.Query().Get("page") {
			case "0":
				_, _ = w.Write([]byte(bookPageJSON(false, 0, 2, `{
					"id":"book-1","fileHash":"hash-1","libraryId":"library-1","name":"Issue 1","seriesId":"series-1","seriesTitle":"Series",
					"metadata":{"title":"First","number":"1","isbn":""},"media":{"pagesCount":20},
					"readProgress":{"page":7,"completed":false,"lastModified":"2026-09-20T10:00:00Z","readDate":"2026-09-20T10:00:00Z"}
				}`)))
			case "1":
				_, _ = w.Write([]byte(bookPageJSON(true, 1, 2, `{
					"id":"book-2","fileHash":"hash-2","libraryId":"library-1","name":"Issue 2","seriesId":"series-1","seriesTitle":"Series",
					"metadata":{"title":"Second","number":"2","isbn":"9780000000002"},"media":{"pagesCount":22},
					"readProgress":{"page":22,"completed":true,"lastModified":"2026-09-20T11:00:00Z","readDate":"2026-09-20T11:00:00Z"}
				}`)))
			default:
				t.Fatalf("unexpected books page %q", r.URL.Query().Get("page"))
			}
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/readlists":
			_, _ = w.Write([]byte(`{"content":[{"id":"list-1","name":"Lantern Order","ordered":true}],"last":true,"number":0,"totalPages":1}`))
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/readlists/list-1/books":
			if r.URL.Query().Get("unpaged") != "true" {
				t.Fatalf("readlist query = %s", r.URL.RawQuery)
			}
			_, _ = w.Write([]byte(bookPageJSON(true, 0, 1, `{"id":"book-2","fileHash":"hash-2","name":"Issue 2"},{"id":"book-1","fileHash":"hash-1","name":"Issue 1"}`)))
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()

	client, err := NewClient(server.URL, "reader@example.test", "test-password", server.Client())
	if err != nil {
		t.Fatal(err)
	}
	export, err := client.ExportReadingState(context.Background())
	if err != nil {
		t.Fatal(err)
	}

	if export.SchemaVersion != 1 || export.User.ID != "user-1" {
		t.Fatalf("export identity = %+v", export)
	}
	if len(export.Progress) != 2 || export.Progress[0].BookID != "book-1" || export.Progress[0].Page != 7 || export.Progress[1].Completed != true {
		t.Fatalf("progress = %+v", export.Progress)
	}
	if len(export.ReadLists) != 1 || !reflect.DeepEqual(export.ReadLists[0].BookIDs, []string{"book-2", "book-1"}) {
		t.Fatalf("readlists = %+v", export.ReadLists)
	}
	wantRequests := []string{
		"GET /api/v2/users/me",
		"POST /api/v1/books/list?page=0&size=200&sort=id%2Casc",
		"POST /api/v1/books/list?page=1&size=200&sort=id%2Casc",
		"GET /api/v1/readlists",
		"GET /api/v1/readlists/list-1/books?unpaged=true",
	}
	if !reflect.DeepEqual(requests, wantRequests) {
		t.Fatalf("requests = %#v, want %#v", requests, wantRequests)
	}
}

func TestExportReadingStateOmitsUnreadBooksAndCredentials(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch r.URL.Path {
		case "/api/v2/users/me":
			_, _ = w.Write([]byte(`{"id":"user-1","email":"person@example.test"}`))
		case "/api/v1/books/list":
			_, _ = w.Write([]byte(bookPageJSON(true, 0, 1, `{"id":"unread","fileHash":"hash","name":"Unread","metadata":{},"media":{"pagesCount":10}}`)))
		case "/api/v1/readlists":
			_, _ = w.Write([]byte(`[]`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()

	client, err := NewClient(server.URL, "person@example.test", "never-serialize-this", server.Client())
	if err != nil {
		t.Fatal(err)
	}
	export, err := client.ExportReadingState(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if len(export.Progress) != 0 {
		t.Fatalf("unread progress exported: %+v", export.Progress)
	}
	raw, err := json.Marshal(export)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(raw), "never-serialize-this") {
		t.Fatalf("credential leaked in export: %s", raw)
	}
}

func TestExportReadingStateSupportsAPIKeyWithoutBasicCredentials(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if got := r.Header.Get("X-API-Key"); got != "migration-api-key" {
			t.Fatalf("X-API-Key = %q", got)
		}
		if _, _, ok := r.BasicAuth(); ok || r.Header.Get("Authorization") != "" {
			t.Fatalf("API-key request also sent basic authentication")
		}
		w.Header().Set("Content-Type", "application/json")
		switch r.URL.Path {
		case "/api/v2/users/me":
			_, _ = w.Write([]byte(`{"id":"api-user","email":"reader@example.test"}`))
		case "/api/v1/books/list":
			_, _ = w.Write([]byte(bookPageJSON(true, 0, 1, `{"id":"book","metadata":{},"media":{"pagesCount":10},"readProgress":{"page":3,"completed":false}}`)))
		case "/api/v1/readlists":
			_, _ = w.Write([]byte(`[]`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()

	client, err := NewAPIKeyClient(server.URL, "migration-api-key", server.Client())
	if err != nil {
		t.Fatal(err)
	}
	export, err := client.ExportReadingState(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if export.User.ID != "api-user" || len(export.Progress) != 1 || export.Progress[0].Page != 3 {
		t.Fatalf("API-key export = %+v", export)
	}
	raw, err := json.Marshal(export)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(raw), "migration-api-key") {
		t.Fatalf("API key leaked in export: %s", raw)
	}
}

func TestExportReadingStateRedactsUpstreamErrorBodies(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		_, _ = w.Write([]byte(`database failure token=super-secret`))
	}))
	defer server.Close()

	client, err := NewClient(server.URL, "reader", "password", server.Client())
	if err != nil {
		t.Fatal(err)
	}
	_, err = client.ExportReadingState(context.Background())
	if err == nil {
		t.Fatal("expected export error")
	}
	if strings.Contains(err.Error(), "super-secret") || !strings.Contains(err.Error(), "500") {
		t.Fatalf("unsafe error = %q", err)
	}
}

func TestNewClientRejectsCredentialBearingAndNonHTTPURLs(t *testing.T) {
	for _, rawURL := range []string{"file:///tmp/komga", "https://user:password@example.test"} {
		if _, err := NewClient(rawURL, "reader", "password", http.DefaultClient); err == nil {
			t.Errorf("accepted unsafe URL %q", rawURL)
		}
	}
	if _, err := NewClient("https://example.test", "", "password", http.DefaultClient); err == nil {
		t.Fatal("accepted empty username")
	}
	if _, err := NewAPIKeyClient("https://example.test", "", http.DefaultClient); err == nil {
		t.Fatal("accepted empty API key")
	}
}

func bookPageJSON(last bool, page, totalPages int, content string) string {
	return `{"content":[` + content + `],"last":` + boolString(last) + `,"number":` + intString(page) + `,"totalPages":` + intString(totalPages) + `}`
}

func boolString(value bool) string {
	if value {
		return "true"
	}
	return "false"
}

func intString(value int) string {
	return strconv.Itoa(value)
}
