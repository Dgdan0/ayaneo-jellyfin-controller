package api

import (
	"ayaneohub/internal/config"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestSubtitleSearchBindsSelectionAndConsumesOnce(t *testing.T) {
	const itemID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
	path := "/media/movie.mkv"
	writes := 0
	historyAction := 2
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case strings.Contains(r.URL.Path, "/Items/"+itemID):
			json.NewEncoder(w).Encode(map[string]any{"Id": itemID, "Type": "Movie", "ProviderIds": map[string]string{"Tmdb": "10"}})
		case r.URL.Path == "/api/v3/movie":
			w.Write([]byte(`[{"id":7,"tmdbId":10,"hasFile":true}]`))
		case r.URL.Path == "/api/movies":
			if r.URL.Query().Get("radarrid[]") != "7" {
				t.Error("wrong movie filter")
			}
			json.NewEncoder(w).Encode(map[string]any{"data": []any{map[string]any{"radarrId": 7, "path": path, "subtitles": []any{map[string]any{"name": "Hebrew", "path": "/media/movie.he.srt"}}}}})
		case r.URL.Path == "/api/movies/history":
			json.NewEncoder(w).Encode(map[string]any{"data": []any{map[string]any{"radarrId": 7, "action": historyAction, "parsed_timestamp": "09/17/26 14:12:34", "subtitles_path": "/media/movie.he.srt", "score": "91.11%", "provider": "example", "language": map[string]string{"name": "Hebrew"}}}})
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
	cfg.Auth.Tokens[0].Scopes = []string{"read", "control"}
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
	path = "/media/replaced.mkv"
	if w := call("POST", base+"/download", body, ""); w.Code != 409 {
		t.Fatalf("changed file: %d", w.Code)
	}
	path = "/media/movie.mkv"
	if w := call("POST", base+"/download", body, ""); w.Code != 200 {
		t.Fatalf("download: %d %s", w.Code, w.Body.String())
	}
	if w := call("POST", base+"/download", body, ""); w.Code != 409 {
		t.Fatalf("duplicate: %d", w.Code)
	}
	if writes != 1 {
		t.Fatalf("writes=%d", writes)
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
	for _, action := range []string{"search", "download"} {
		r := httptest.NewRequest("POST", "/v1/library/items/"+strings.Repeat("a", 32)+"/subtitles/"+action, strings.NewReader("{}"))
		r.Header.Set("Authorization", "Bearer "+libraryTestToken)
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, r)
		if w.Code != 403 {
			t.Fatalf("%s: %d", action, w.Code)
		}
	}
}
