package api

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	"ayaneohub/internal/adapters/storyteller"
	"ayaneohub/internal/config"
)

func TestReadaloudAutomationTreatsLegacyReadyRecordAsComplete(t *testing.T) {
	if activeReadaloud(storyteller.Book{Readaloud: &storyteller.Readaloud{UUID: "old-ready"}}) {
		t.Fatal("legacy ready record without status blocked future alignments")
	}
	if !activeReadaloud(storyteller.Book{Readaloud: &storyteller.Readaloud{UUID: "new-job", Status: "QUEUED"}}) {
		t.Fatal("queued alignment was not recognized")
	}
}

func TestReadaloudAutomationStartsOnlyNewCompletePairsAndPersistsAcrossRestart(t *testing.T) {
	phase := 0
	started := map[string]int{}
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v2/token":
			_, _ = w.Write([]byte(`{"access_token":"test","token_type":"Bearer","expires_in":3600}`))
		case "/api/v2/books":
			if r.Header.Get("Authorization") != "Bearer test" {
				t.Error("books were not authenticated")
			}
			first := `{"id":2,"uuid":"existing","title":"Existing","ebook":{"uuid":"e2"},"audiobook":{"uuid":"a2"}}`
			second := `{"id":3,"uuid":"new","title":"New","ebook":{"uuid":"e3"}`
			if phase > 0 {
				second += `,"audiobook":{"uuid":"a3"}`
			}
			if phase == 2 {
				second += `,"readaloud":{"uuid":"r3","status":"QUEUED"}`
			}
			if phase >= 3 {
				second += `,"readaloud":{"uuid":"r3","status":"ALIGNED"}`
			}
			second += `}`
			third := `{"id":4,"uuid":"later","title":"Later","ebook":{"uuid":"e4"},"audiobook":{"uuid":"a4"}}`
			books := first + `,` + second
			if phase >= 2 {
				books += `,` + third
			}
			_, _ = fmt.Fprintf(w, "[%s]", books)
		case "/api/v2/books/3/process", "/api/v2/books/4/process":
			if r.Method != http.MethodPost || r.Header.Get("Authorization") != "Bearer test" {
				t.Error("invalid process request")
			}
			started[r.URL.Path]++
			w.WriteHeader(http.StatusNoContent)
		default:
			http.NotFound(w, r)
		}
	}))
	defer upstream.Close()
	registry := filepath.Join(t.TempDir(), "reading-transfers.json")
	cfg := &config.Config{Server: config.ServerConfig{ReadingTransfers: registry}, Services: map[string]config.ServiceConfig{
		"storyteller": {Enabled: true, BaseURL: upstream.URL, Username: "worker", Password: config.Secret("secret")},
	}}
	server := NewServer(cfg)
	for _, stage := range []int{0, 1, 2, 3} {
		phase = stage
		if err := server.reconcileReadingAlignments(context.Background()); err != nil {
			t.Fatal(err)
		}
		if stage == 0 && len(started) != 0 {
			t.Fatalf("existing pair started: %v", started)
		}
		if stage == 2 && started["/api/v2/books/4/process"] != 0 {
			t.Fatal("queued job did not block another")
		}
	}
	if started["/api/v2/books/3/process"] != 1 || started["/api/v2/books/4/process"] != 1 {
		t.Fatalf("starts = %v", started)
	}
	server = NewServer(cfg)
	if err := server.reconcileReadingAlignments(context.Background()); err != nil {
		t.Fatal(err)
	}
	if started["/api/v2/books/4/process"] != 1 {
		t.Fatalf("restart queued again: %v", started)
	}
}

func TestReadaloudAutomationDoesNotStartWithDamagedRegistry(t *testing.T) {
	posts := 0
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v2/token":
			_, _ = w.Write([]byte(`{"access_token":"test","token_type":"Bearer","expires_in":3600}`))
		case "/api/v2/books":
			_, _ = w.Write([]byte(`[{"id":9,"uuid":"pair","ebook":{"uuid":"e9"},"audiobook":{"uuid":"a9"}}]`))
		case "/api/v2/books/9/process":
			posts++
			w.WriteHeader(http.StatusNoContent)
		default:
			http.NotFound(w, r)
		}
	}))
	defer upstream.Close()
	registry := filepath.Join(t.TempDir(), "reading-transfers.json")
	if err := os.WriteFile(readingAlignmentPath(registry), []byte("corrupt"), 0o600); err != nil {
		t.Fatal(err)
	}
	cfg := &config.Config{Server: config.ServerConfig{ReadingTransfers: registry}, Services: map[string]config.ServiceConfig{
		"storyteller": {Enabled: true, BaseURL: upstream.URL, Username: "worker", Password: config.Secret("secret")},
	}}
	if err := NewServer(cfg).reconcileReadingAlignments(context.Background()); err == nil {
		t.Fatal("damaged registry should stop automation")
	}
	if posts != 0 {
		t.Fatalf("started %d jobs with damaged registry", posts)
	}
}

func TestReadaloudAutomationRetriesFailedSubmissionAndSkipsMissingSources(t *testing.T) {
	failed := true
	posts := 0
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v2/token":
			_, _ = w.Write([]byte(`{"access_token":"test","token_type":"Bearer","expires_in":3600}`))
		case "/api/v2/books":
			_, _ = w.Write([]byte(`[{"id":7,"uuid":"candidate","ebook":{"uuid":"e7"},"audiobook":{"uuid":"a7"}},{"id":8,"uuid":"missing","ebook":{"uuid":"e8","missing":true},"audiobook":{"uuid":"a8"}}]`))
		case "/api/v2/books/7/process":
			posts++
			if failed {
				http.Error(w, "temporary", http.StatusServiceUnavailable)
			} else {
				w.WriteHeader(http.StatusNoContent)
			}
		default:
			http.NotFound(w, r)
		}
	}))
	defer upstream.Close()
	registry := filepath.Join(t.TempDir(), "reading-transfers.json")
	cfg := &config.Config{Server: config.ServerConfig{ReadingTransfers: registry}, Services: map[string]config.ServiceConfig{
		"storyteller": {Enabled: true, BaseURL: upstream.URL, Username: "worker", Password: config.Secret("secret")},
	}}
	server := NewServer(cfg)
	// An initialized registry represents a Hub that saw the books before they were paired.
	if err := server.readingAlignments.initialize(nil); err != nil {
		t.Fatal(err)
	}
	if err := server.reconcileReadingAlignments(context.Background()); err == nil {
		t.Fatal("failed submission was hidden")
	}
	failed = false
	if err := server.reconcileReadingAlignments(context.Background()); err != nil {
		t.Fatal(err)
	}
	if posts != 2 {
		t.Fatalf("posts = %d, want retry only for complete pair", posts)
	}
}
