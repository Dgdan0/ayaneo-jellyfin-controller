package api

import (
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

// An aligned EPUB can take longer than the JSON response budget on a remote link.
// Exercise a real TCP server and the logging ResponseWriter wrapper.
func TestStreamingResponseOutlivesServerWriteTimeout(t *testing.T) {
	server := httptest.NewUnstartedServer(withLogging(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if err := prepareLongStream(w); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		w.Header().Set("Content-Type", "application/epub+zip")
		w.Header().Set("Content-Length", "2")
		_, _ = io.WriteString(w, "a")
		time.Sleep(200 * time.Millisecond)
		_, _ = io.WriteString(w, "b")
	})))
	server.Config.WriteTimeout = 50 * time.Millisecond
	server.Start()
	defer server.Close()
	client := &http.Client{Timeout: 2 * time.Second}
	response, err := client.Get(server.URL)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	body, err := io.ReadAll(response.Body)
	if err != nil || response.StatusCode != http.StatusOK || string(body) != "ab" {
		t.Fatalf("slow EPUB stream = %d %q, read error %v", response.StatusCode, body, err)
	}
}
