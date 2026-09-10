package api

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"ayaneohub/internal/auth"
	"ayaneohub/internal/config"
)

func TestPlaybackTransportDoesNotConsumeInteractiveRateLimit(t *testing.T) {
	cfg := libraryAPIConfig("", "")
	cfg.Auth.RateLimit = config.RateLimitConfig{RPM: 1, Burst: 1}
	server := NewServer(cfg)
	handler := server.withAuth(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))

	if got := authenticatedMiddlewareRequest(handler, "/v1/home").Code; got != http.StatusNoContent {
		t.Fatalf("first interactive request returned %d", got)
	}
	for i := 0; i < 50; i++ {
		path := "/v1/playback/sessions/session/hls/segment"
		if i%2 == 1 {
			path = "/v1/offline/grants/grant/media"
		}
		if got := authenticatedMiddlewareRequest(handler, path).Code; got != http.StatusNoContent {
			t.Fatalf("playback transport request %d returned %d", i+1, got)
		}
	}
	if got := authenticatedMiddlewareRequest(handler, "/v1/home").Code; got != http.StatusTooManyRequests {
		t.Fatalf("second interactive request returned %d, want 429", got)
	}
}

func TestPlaybackTransportStillHasAuthenticationAndItsOwnLimit(t *testing.T) {
	server := NewServer(libraryAPIConfig("", ""))
	server.playbackLimiter = auth.NewLimiter(1, 1)
	handler := server.withAuth(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))

	unauthorized := httptest.NewRequest(http.MethodGet, "/v1/playback/sessions/session/stream", nil)
	unauthorized.Header.Set("Authorization", "Bearer wrong")
	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, unauthorized)
	if recorder.Code != http.StatusUnauthorized {
		t.Fatalf("invalid playback credential returned %d", recorder.Code)
	}

	if got := authenticatedMiddlewareRequest(handler, "/v1/playback/sessions/session/stream").Code; got != http.StatusNoContent {
		t.Fatalf("first playback request returned %d", got)
	}
	if got := authenticatedMiddlewareRequest(handler, "/v1/playback/sessions/session/events").Code; got != http.StatusTooManyRequests {
		t.Fatalf("playback budget overflow returned %d, want 429", got)
	}
}

func authenticatedMiddlewareRequest(handler http.Handler, path string) *httptest.ResponseRecorder {
	request := httptest.NewRequest(http.MethodGet, path, nil)
	request.Header.Set("Authorization", "Bearer "+libraryTestToken)
	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, request)
	return recorder
}
