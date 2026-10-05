package api

import (
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// Exercise the actual authenticated offline route, not only the deadline helper.
// The production JSON budget is two minutes; a resumed movie can take much longer.
func TestOfflineMediaRangeOutlivesServerWriteTimeout(t *testing.T) {
	for _, protocol := range []string{"http1", "http2"} {
		t.Run(protocol, func(t *testing.T) {
			fixture := &offlineUpstream{t: t}
			upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.URL.Path != "/Videos/"+offlineItemID+"/stream" {
					fixture.serve(w, r)
					return
				}
				if r.Header.Get("Range") != "bytes=2-5" || r.Header.Get("X-Emby-Token") != "test-key" {
					t.Errorf("range or upstream authentication was not forwarded")
				}
				w.Header().Set("Content-Type", "video/x-matroska")
				w.Header().Set("Content-Length", "4")
				w.Header().Set("Content-Range", "bytes 2-5/8")
				w.Header().Set("Accept-Ranges", "bytes")
				w.WriteHeader(http.StatusPartialContent)
				_, _ = io.WriteString(w, "23")
				_ = http.NewResponseController(w).Flush()
				time.Sleep(250 * time.Millisecond)
				_, _ = io.WriteString(w, "45")
			}))
			defer upstream.Close()
			api := NewServer(offlineConfig(upstream.URL, filepath.Join(t.TempDir(), "grants.json")))
			manifest := prepareOffline(t, api.Handler())
			server := httptest.NewUnstartedServer(api.Handler())
			server.Config.WriteTimeout = 75 * time.Millisecond
			server.EnableHTTP2 = protocol == "http2"
			server.StartTLS()
			defer server.Close()
			request, err := http.NewRequest(http.MethodGet, server.URL+manifest.MediaURL, nil)
			if err != nil {
				t.Fatal(err)
			}
			request.Header.Set("Authorization", "Bearer "+libraryTestToken)
			request.Header.Set(jellyfinUserHeader, playbackUserID)
			request.Header.Set("Range", "bytes=2-5")
			client := server.Client()
			client.Timeout = patient
			response, err := client.Do(request)
			if err != nil {
				t.Fatalf("resumed offline stream failed: %v", err)
			}
			defer response.Body.Close()
			body, err := io.ReadAll(response.Body)
			if err != nil || string(body) != "2345" {
				t.Fatalf("resumed offline stream body = %q, error = %v", body, err)
			}
			if response.StatusCode != http.StatusPartialContent || response.Header.Get("Content-Range") != "bytes 2-5/8" {
				t.Fatalf("resume status/headers lost: %d %v", response.StatusCode, response.Header)
			}
			if response.ProtoMajor != map[string]int{"http1": 1, "http2": 2}[protocol] {
				t.Fatalf("test used unexpected protocol %s", response.Proto)
			}
			if !strings.Contains(response.Header.Get("Cache-Control"), "no-store") {
				t.Fatal("offline media must still bypass response caches")
			}
		})
	}
}
