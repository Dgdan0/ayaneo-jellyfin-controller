package api

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
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

func TestArtworkForAWholeGridDoesNotRunOutOrConsumeTheScreenBudget(t *testing.T) {
	// A 60-title Library page asks for 60 posters at once; on the screen
	// budget (burst 30) half of them came back 429 and stayed blank.
	cfg := libraryAPIConfig("", "")
	cfg.Auth.RateLimit = config.RateLimitConfig{RPM: 1, Burst: 1}
	server := NewServer(cfg)
	handler := server.withAuth(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	if got := authenticatedMiddlewareRequest(handler, "/v1/library/view/items").Code; got != http.StatusNoContent {
		t.Fatalf("page request returned %d", got)
	}
	for i := 0; i < 60; i++ {
		if got := authenticatedMiddlewareRequest(handler, "/v1/img/jf/item/Primary").Code; got != http.StatusNoContent {
			t.Fatalf("poster %d returned %d", i+1, got)
		}
	}
	if got := authenticatedMiddlewareRequest(handler, "/v1/home").Code; got != http.StatusTooManyRequests {
		t.Fatalf("screen budget was not independent of artwork: %d", got)
	}
}

func TestReaderPagesAndFilesDoNotConsumeTheScreenBudget(t *testing.T) {
	// Skimming a comic turns pages faster than any screen asks for data.
	cfg := libraryAPIConfig("", "")
	cfg.Auth.RateLimit = config.RateLimitConfig{RPM: 1, Burst: 1}
	server := NewServer(cfg)
	handler := server.withAuth(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	if got := authenticatedMiddlewareRequest(handler, "/v1/reading/home").Code; got != http.StatusNoContent {
		t.Fatalf("first screen request returned %d", got)
	}
	base := "/v1/reading/works/kavita:12/publications/kavita-chapter:34"
	for i := 0; i < 40; i++ {
		path := base + "/pages/" + string(rune('0'+i%10))
		switch i % 4 {
		case 1:
			path += "/thumb"
		case 2:
			path = base + "/file"
		}
		if got := authenticatedMiddlewareRequest(handler, path).Code; got != http.StatusNoContent {
			t.Fatalf("reader request %d (%s) returned %d", i+1, path, got)
		}
	}
	// Progress and position are screen-sized writes and stay on the screen budget.
	if got := authenticatedMiddlewareRequest(handler, base+"/progress").Code; got != http.StatusTooManyRequests {
		t.Fatalf("progress was taken off the screen budget: %d", got)
	}
}

func TestArtworkStillHasItsOwnLimit(t *testing.T) {
	server := NewServer(libraryAPIConfig("", ""))
	server.artworkLimiter = auth.NewLimiter(1, 1)
	handler := server.withAuth(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	if got := authenticatedMiddlewareRequest(handler, "/v1/img/tmdb/w342/a.jpg").Code; got != http.StatusNoContent {
		t.Fatalf("first image returned %d", got)
	}
	if got := authenticatedMiddlewareRequest(handler, "/v1/img/tmdb/w342/b.jpg").Code; got != http.StatusTooManyRequests {
		t.Fatalf("artwork budget overflow returned %d, want 429", got)
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

func TestRequireScopeRefusesWithAScopeErrorNotAnAuthError(t *testing.T) {
	// A 403 tells the app the token still works for everything else.
	request := httptest.NewRequest(http.MethodPost, "/v1/downloads/x/stop", nil)
	request = request.WithContext(context.WithValue(request.Context(), ctxToken, auth.Token{Label: "x", Scopes: []string{"read"}}))
	recorder := httptest.NewRecorder()
	if requireScope(recorder, request, "control", "control downloads") {
		t.Fatal("a read-only token passed a control check")
	}
	if recorder.Code != http.StatusForbidden || !strings.Contains(recorder.Body.String(), `"code":"forbidden_scope"`) ||
		!strings.Contains(recorder.Body.String(), "not allowed to control downloads") {
		t.Fatalf("got %d %s", recorder.Code, recorder.Body.String())
	}

	allowed := request.WithContext(context.WithValue(request.Context(), ctxToken, auth.Token{Label: "x", Scopes: []string{"control"}}))
	if !requireScope(httptest.NewRecorder(), allowed, "control", "control downloads") {
		t.Fatal("a control token was refused")
	}
}

// An audiobook's tracks are transport, like a book's file: a player asks for
// ranges of one track and the head of the next. Its manifest and its place are
// screen-sized, as progress is, and must not hide behind a path that merely
// contains "/audio".
func TestAudioTracksAreTransportWhileTheManifestAndThePlaceAreScreens(t *testing.T) {
	cfg := libraryAPIConfig("", "")
	cfg.Auth.RateLimit = config.RateLimitConfig{RPM: 1, Burst: 1}
	server := NewServer(cfg)
	handler := server.withAuth(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	base := "/v1/reading/works/rw_00000000000000000000000000000000/publications/12"
	if got := authenticatedMiddlewareRequest(handler, base+"/audio").Code; got != http.StatusNoContent {
		t.Fatalf("manifest returned %d", got)
	}
	for i := 0; i < 60; i++ {
		path := fmt.Sprintf("%s/audio/tracks/%d?rev=0123456789ab", base, i%7)
		if got := authenticatedMiddlewareRequest(handler, path).Code; got != http.StatusNoContent {
			t.Fatalf("track request %d (%s) returned %d", i+1, path, got)
		}
	}
	for _, screen := range []string{"/audio", "/audio/position", "/audio/"} {
		if got := authenticatedMiddlewareRequest(handler, base+screen).Code; got != http.StatusTooManyRequests {
			t.Errorf("%s was taken off the screen budget: %d", screen, got)
		}
	}
}
