package api

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"sync"
	"testing"

	"ayaneohub/internal/config"
)

type readingScanFixture struct {
	mu          sync.Mutex
	kavitaScans int
	storyScans  int
	bookLists   int
	failStory   bool
}

func (f *readingScanFixture) handler(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	defer f.mu.Unlock()
	switch r.URL.Path {
	case "/api/downloads":
		f.bookLists++
		_, _ = io.WriteString(w, `{"downloads":[{"id":9,"qbtHash":"0123456789abcdef0123456789abcdef01234567","status":"imported","addedAt":"2026-09-21T10:00:00Z","importedAt":"2026-09-21T10:05:00Z","release":{"id":3,"title":"Red Rising EPUB"},"series":{"id":4,"title":"Red Rising","contentType":"ebook"}}]}`)
	case "/api/Library/scan-all":
		if r.Method != http.MethodPost || r.Header.Get("X-Api-Key") != "kavita-key" {
			panic("invalid Kavita scan request")
		}
		f.kavitaScans++
		w.WriteHeader(http.StatusOK)
	case "/api/v2/token":
		_, _ = io.WriteString(w, `{"access_token":"story-token","token_type":"Bearer","expires_in":3600}`)
	case "/api/v2/books/scan":
		if r.Method != http.MethodPost || r.Header.Get("Authorization") != "Bearer story-token" {
			panic("invalid Storyteller scan request")
		}
		f.storyScans++
		if f.failStory {
			http.Error(w, "scan failed", http.StatusInternalServerError)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	default:
		http.NotFound(w, r)
	}
}

func readingScanConfig(baseURL, registry string, scopes []string) *config.Config {
	return &config.Config{
		Server: config.ServerConfig{ReadingTransfers: registry},
		Auth: config.AuthConfig{
			Tokens:    []config.TokenConfig{{Label: "reader", Raw: config.Secret(libraryTestToken), Scopes: scopes}},
			RateLimit: config.RateLimitConfig{RPM: 600, Burst: 100}, AuthFailureBan: config.BanConfig{Attempts: 5},
		},
		Services: map[string]config.ServiceConfig{
			"bookkeeprr":  {Enabled: true, BaseURL: baseURL, APIKey: config.Secret("book-key")},
			"kavita":      {Enabled: true, BaseURL: baseURL, APIKey: config.Secret("kavita-key")},
			"storyteller": {Enabled: true, BaseURL: baseURL, Username: "reader", Password: config.Secret("secret")},
		},
	}
}

func TestManageReadingScanCanTargetOneReaderAndNeedsControlScope(t *testing.T) {
	fixture := &readingScanFixture{}
	upstream := httptest.NewServer(http.HandlerFunc(fixture.handler))
	defer upstream.Close()
	registry := filepath.Join(t.TempDir(), "reading-transfers.json")

	readOnly := NewServer(readingScanConfig(upstream.URL, registry, []string{"reading"})).Handler()
	if got := readingJSONRequest(readOnly, http.MethodPost, "/v1/manage/reading/scan?service=kavita", `{}`); got.Code != http.StatusForbidden {
		t.Fatalf("read-only scan = %d", got.Code)
	}

	handler := NewServer(readingScanConfig(upstream.URL, registry, []string{"reading", "control"})).Handler()
	got := readingJSONRequest(handler, http.MethodPost, "/v1/manage/reading/scan?service=kavita", `{}`)
	if got.Code != http.StatusAccepted {
		t.Fatalf("targeted scan = %d: %s", got.Code, got.Body.String())
	}
	var body ReadingScanResponse
	if err := json.Unmarshal(got.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if !body.OK || len(body.Services) != 1 || body.Services[0] != "kavita" || fixture.kavitaScans != 1 || fixture.storyScans != 0 {
		t.Fatalf("response = %+v, calls = %d/%d", body, fixture.kavitaScans, fixture.storyScans)
	}
	if invalid := readingJSONRequest(handler, http.MethodPost, "/v1/manage/reading/scan?service=bookkeeprr", `{}`); invalid.Code != http.StatusBadRequest {
		t.Fatalf("invalid target = %d", invalid.Code)
	}
}

func TestImportedReadingTransferScansEachReaderOnceAcrossRestart(t *testing.T) {
	fixture := &readingScanFixture{}
	upstream := httptest.NewServer(http.HandlerFunc(fixture.handler))
	defer upstream.Close()
	registry := filepath.Join(t.TempDir(), "reading-transfers.json")
	cfg := readingScanConfig(upstream.URL, registry, []string{"reading", "control"})

	server := NewServer(cfg)
	if err := server.reconcileReadingImports(context.Background()); err != nil {
		t.Fatal(err)
	}
	if err := server.reconcileReadingImports(context.Background()); err != nil {
		t.Fatal(err)
	}
	restarted := NewServer(cfg)
	if err := restarted.reconcileReadingImports(context.Background()); err != nil {
		t.Fatal(err)
	}
	if fixture.bookLists != 3 || fixture.kavitaScans != 1 || fixture.storyScans != 1 {
		t.Fatalf("lists=%d kavita=%d storyteller=%d", fixture.bookLists, fixture.kavitaScans, fixture.storyScans)
	}
}

func TestFailedReaderScanStaysPendingWithoutRepeatingSuccessfulReader(t *testing.T) {
	fixture := &readingScanFixture{failStory: true}
	upstream := httptest.NewServer(http.HandlerFunc(fixture.handler))
	defer upstream.Close()
	cfg := readingScanConfig(upstream.URL, filepath.Join(t.TempDir(), "reading-transfers.json"), []string{"reading", "control"})
	server := NewServer(cfg)
	if err := server.reconcileReadingImports(context.Background()); err == nil {
		t.Fatal("partial scan returned no error")
	}
	fixture.mu.Lock()
	fixture.failStory = false
	fixture.mu.Unlock()
	if err := server.reconcileReadingImports(context.Background()); err != nil {
		t.Fatal(err)
	}
	if fixture.kavitaScans != 1 || fixture.storyScans != 2 {
		t.Fatalf("kavita=%d storyteller=%d", fixture.kavitaScans, fixture.storyScans)
	}
}

func TestImportedContentTargetsOnlyCompatibleReaders(t *testing.T) {
	tests := []struct {
		contentType string
		kavita      bool
		storyteller bool
	}{
		{contentType: "ebook", kavita: true, storyteller: true},
		{contentType: "audiobook", storyteller: true},
		{contentType: "comic", kavita: true},
		{contentType: "manga", kavita: true},
		{contentType: "light_novel", kavita: true},
		{contentType: "unknown"},
	}
	for _, test := range tests {
		t.Run(test.contentType, func(t *testing.T) {
			if got := readerHandles("kavita", test.contentType); got != test.kavita {
				t.Fatalf("Kavita handles %q = %v, want %v", test.contentType, got, test.kavita)
			}
			if got := readerHandles("storyteller", test.contentType); got != test.storyteller {
				t.Fatalf("Storyteller handles %q = %v, want %v", test.contentType, got, test.storyteller)
			}
		})
	}
}
