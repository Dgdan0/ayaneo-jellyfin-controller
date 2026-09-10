package api

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestJellyfinLibraryScanIsAuthenticatedScopedAndStartsUpstream(t *testing.T) {
	called := false
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/Library/Refresh" {
			t.Fatalf("upstream request = %s %s", r.Method, r.URL.Path)
		}
		if r.Header.Get("X-Emby-Token") != "test-key" {
			t.Fatal("missing Jellyfin authentication")
		}
		called = true
		w.WriteHeader(http.StatusNoContent)
	}))
	defer upstream.Close()

	handler := NewServer(libraryAPIConfig(upstream.URL, "user-1")).Handler()
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodPost, "/v1/manage/jellyfin/scan", nil)
	request.Header.Set("Authorization", "Bearer "+libraryTestToken)
	handler.ServeHTTP(recorder, request)
	if recorder.Code != http.StatusAccepted || !called {
		t.Fatalf("scan = %d %s; upstream called=%v", recorder.Code, recorder.Body.String(), called)
	}

	readOnly := libraryAPIConfig(upstream.URL, "user-1")
	readOnly.Auth.Tokens[0].Scopes = []string{"read"}
	recorder = httptest.NewRecorder()
	request = httptest.NewRequest(http.MethodPost, "/v1/manage/jellyfin/scan", nil)
	request.Header.Set("Authorization", "Bearer "+libraryTestToken)
	NewServer(readOnly).Handler().ServeHTTP(recorder, request)
	if recorder.Code != http.StatusForbidden {
		t.Fatalf("read-only scan = %d, want 403", recorder.Code)
	}
}

func TestJellyfinLibraryScanHandlesMissingService(t *testing.T) {
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodPost, "/v1/manage/jellyfin/scan", nil)
	request.Header.Set("Authorization", "Bearer "+libraryTestToken)
	NewServer(libraryAPIConfig("", "user-1")).Handler().ServeHTTP(recorder, request)
	if recorder.Code != http.StatusServiceUnavailable {
		t.Fatalf("missing Jellyfin scan = %d, want 503", recorder.Code)
	}
}
