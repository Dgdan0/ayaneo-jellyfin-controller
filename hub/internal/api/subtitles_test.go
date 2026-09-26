package api

import (
	"ayaneohub/internal/config"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestSubtitleSearchBindsSelectionAndConsumesOnce(t *testing.T) {
	const itemID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
	directory := t.TempDir()
	path := filepath.Join(directory, "movie.mkv")
	sidecar := filepath.Join(directory, "other-release.he.srt")
	if err := os.WriteFile(path, []byte("video"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(sidecar, []byte("1\n00:00:00,000 --> 00:00:01,000\nHello\n"), 0600); err != nil {
		t.Fatal(err)
	}
	writes := 0
	refreshes := 0
	historyAction := 2
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodPost && r.URL.Path == "/Items/"+itemID+"/Refresh":
			refreshes++
			if r.URL.Query().Get("ReplaceAllMetadata") != "false" {
				t.Error("subtitle refresh must preserve metadata")
			}
			w.WriteHeader(http.StatusNoContent)
		case strings.Contains(r.URL.Path, "/Items/"+itemID):
			json.NewEncoder(w).Encode(map[string]any{"Id": itemID, "Type": "Movie", "Path": path, "ProviderIds": map[string]string{"Tmdb": "10"}})
		case r.URL.Path == "/api/v3/movie":
			w.Write([]byte(`[{"id":7,"tmdbId":10,"hasFile":true}]`))
		case r.URL.Path == "/api/movies":
			if r.URL.Query().Get("radarrid[]") != "7" {
				t.Error("wrong movie filter")
			}
			json.NewEncoder(w).Encode(map[string]any{"data": []any{map[string]any{"radarrId": 7, "path": path, "subtitles": []any{map[string]any{"name": "Hebrew", "code2": "he", "path": sidecar}}}}})
		case r.URL.Path == "/api/movies/history":
			json.NewEncoder(w).Encode(map[string]any{"data": []any{map[string]any{"radarrId": 7, "action": historyAction, "parsed_timestamp": "09/17/26 14:12:34", "subtitles_path": sidecar, "score": "91.11%", "provider": "example", "language": map[string]string{"name": "Hebrew"}}}})
		case r.URL.Path == "/api/providers/movies" && r.Method == "GET":
			w.Write([]byte(`{"data":[{"provider":"example","language":"he","score":91,"subtitle":"private-serialized-provider-selection","hearing_impaired":"False","forced":"False","original_format":"True"}]}`))
		case r.URL.Path == "/api/providers/movies" && r.Method == "POST":
			writes++
			r.ParseForm()
			if r.Form.Get("radarrid") != "7" || r.Form.Get("subtitle") != "private-serialized-provider-selection" {
				t.Error("wrong selected subtitle")
			}
			w.WriteHeader(204)
		default:
			http.NotFound(w, r)
		}
	}))
	defer upstream.Close()
	cfg := libraryAPIConfig(upstream.URL, strings.Repeat("b", 32))
	cfg.Auth.Tokens[0].Scopes = []string{"read", "control", "download"}
	cfg.Services["radarr"] = config.ServiceConfig{Enabled: true, BaseURL: upstream.URL}
	cfg.Services["bazarr"] = config.ServiceConfig{Enabled: true, BaseURL: upstream.URL}
	server := NewServer(cfg)
	handler := server.Handler()
	base := "/v1/library/items/" + itemID + "/subtitles"
	call := func(method, url, body, user string) *httptest.ResponseRecorder {
		r := httptest.NewRequest(method, url, strings.NewReader(body))
		r.Header.Set("Authorization", "Bearer "+libraryTestToken)
		if user != "" {
			r.Header.Set(jellyfinUserHeader, user)
		}
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, r)
		return w
	}
	state := call("GET", base, "", "")
	if state.Code != 200 || !strings.Contains(state.Body.String(), `"score":"91.11%"`) || !strings.Contains(state.Body.String(), `"installed":true`) {
		t.Fatalf("state: %d %s", state.Code, state.Body.String())
	}
	if w := call("POST", base+"/refresh", "{}", ""); w.Code != http.StatusAccepted || refreshes != 1 {
		t.Fatalf("targeted refresh: %d %s; upstream=%d", w.Code, w.Body.String(), refreshes)
	}
	if _, err := os.Stat(filepath.Join(directory, "movie.pocketds.he.srt")); err != nil {
		t.Fatalf("Jellyfin sidecar not prepared: %v", err)
	}
	for _, action := range []int{3, 4, 6, 7, 0} {
		historyAction = action
		response := call("GET", base, "", "")
		var result struct {
			Records []subtitleRecord `json:"records"`
		}
		if response.Code != 200 || json.Unmarshal(response.Body.Bytes(), &result) != nil || len(result.Records) != 1 {
			t.Fatalf("history action %d: %s", action, response.Body.String())
		}
		if !result.Records[0].Installed {
			t.Fatalf("current track lost for action %d", action)
		}
		if action == 0 && result.Records[0].Score != "" {
			t.Fatal("deleted event must not supply a current file's score")
		}
		if action != 0 && result.Records[0].Score != "91.11%" {
			t.Fatalf("score lost for action %d", action)
		}
	}
	historyAction = 2
	search := func() string {
		w := call("POST", base+"/search", "{}", "")
		if w.Code != 200 {
			t.Fatalf("search: %d %s", w.Code, w.Body.String())
		}
		if strings.Contains(w.Body.String(), "private-serialized") {
			t.Fatal("upstream selection leaked")
		}
		var response struct {
			Candidates []subtitleChoice `json:"candidates"`
		}
		json.Unmarshal(w.Body.Bytes(), &response)
		if len(response.Candidates) != 1 {
			t.Fatal("missing choice")
		}
		return response.Candidates[0].Ticket
	}
	ticket := search()
	body := `{"ticket":"` + ticket + `"}`
	if w := call("POST", base+"/download", body, strings.Repeat("c", 32)); w.Code != 409 {
		t.Fatalf("other user: %d", w.Code)
	}
	if w := call("POST", base+"/download", strings.TrimSuffix(body, "}")+`,"subtitle":"injected"}`, ""); w.Code != 400 {
		t.Fatalf("injection: %d", w.Code)
	}
	path = filepath.Join(directory, "replaced.mkv")
	if w := call("POST", base+"/download", body, ""); w.Code != 409 {
		t.Fatalf("changed file: %d", w.Code)
	}
	path = filepath.Join(directory, "movie.mkv")
	if w := call("POST", base+"/download", body, ""); w.Code != 200 {
		t.Fatalf("download: %d %s", w.Code, w.Body.String())
	}
	if w := call("POST", base+"/download", body, ""); w.Code != 409 {
		t.Fatalf("duplicate: %d", w.Code)
	}
	if writes != 1 {
		t.Fatalf("writes=%d", writes)
	}
	if refreshes != 2 {
		t.Fatalf("download did not start Jellyfin item refresh: %d", refreshes)
	}
	expired := search()
	entry := server.subtitleTickets[expired]
	entry.Expires = time.Now().Add(-time.Second)
	server.subtitleTickets[expired] = entry
	if w := call("POST", base+"/download", `{"ticket":"`+expired+`"}`, ""); w.Code != 409 {
		t.Fatalf("expired: %d", w.Code)
	}
}
func TestSubtitleWritesRequireControl(t *testing.T) {
	cfg := libraryAPIConfig("", "")
	cfg.Auth.Tokens[0].Scopes = []string{"read"}
	handler := NewServer(cfg).Handler()
	for _, action := range []string{"search", "download", "refresh"} {
		r := httptest.NewRequest("POST", "/v1/library/items/"+strings.Repeat("a", 32)+"/subtitles/"+action, strings.NewReader("{}"))
		r.Header.Set("Authorization", "Bearer "+libraryTestToken)
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, r)
		if w.Code != 403 {
			t.Fatalf("%s: %d", action, w.Code)
		}
	}
}
