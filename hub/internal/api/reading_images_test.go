package api

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestReadingCoverProxyServesOnlyRegisteredHTTPSImagesAndCachesThem(t *testing.T) {
	calls := 0
	cover := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		w.Header().Set("Content-Type", "image/jpeg")
		_, _ = w.Write([]byte("jpeg-data"))
	}))
	defer cover.Close()

	server := NewServer(libraryAPIConfig("", ""))
	server.images.client = cover.Client()
	token := server.images.registerReadingCover(cover.URL + "/cover.jpg")
	if token == "" {
		t.Fatal("a valid HTTPS cover was rejected")
	}

	path := "/v1/img/reading/" + token
	for range 2 {
		got := libraryRequest(server.Handler(), path)
		if got.Code != http.StatusOK || got.Body.String() != "jpeg-data" || got.Header().Get("Content-Type") != "image/jpeg" {
			t.Fatalf("cover response = %d %q %q", got.Code, got.Header().Get("Content-Type"), got.Body.String())
		}
	}
	if calls != 1 {
		t.Fatalf("cover upstream called %d times, want once", calls)
	}
	if got := libraryRequest(server.Handler(), "/v1/img/reading/00000000000000000000000000000000"); got.Code != http.StatusNotFound {
		t.Fatalf("unregistered token = %d, want 404", got.Code)
	}
}

func TestReadingCoverRegistryRejectsUnsafeDestinations(t *testing.T) {
	proxy := newImageProxy()
	for _, raw := range []string{
		"http://covers.example/book.jpg",
		"https://user:password@covers.example/book.jpg",
		"file:///etc/passwd",
		"",
	} {
		if token := proxy.registerReadingCover(raw); token != "" {
			t.Errorf("registered unsafe cover %q as %q", raw, token)
		}
	}
}
