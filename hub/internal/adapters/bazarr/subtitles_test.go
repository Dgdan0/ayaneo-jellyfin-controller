package bazarr

import (
	"ayaneohub/internal/config"
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestEpisodeSubtitleWireContract(t *testing.T) {
	writes := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/episodes":
			if r.URL.Query().Get("episodeid[]") != "42" {
				t.Error("missing exact episode filter")
			}
			w.Write([]byte(`{"data":[{"sonarrEpisodeId":99,"path":"wrong"},{"sonarrEpisodeId":42,"sonarrSeriesId":3,"path":"/show/episode.mkv"}]}`))
		case "/api/episodes/history":
			if r.URL.Query().Get("episodeid") != "42" {
				t.Error("wrong history target")
			}
			w.Write([]byte(`{"data":[{"sonarrEpisodeId":99},{"sonarrEpisodeId":42,"action":1,"score":"86.67%"}]}`))
		case "/api/providers/episodes":
			if r.Method == "GET" {
				if r.URL.Query().Get("episodeid") != "42" {
					t.Error("wrong search target")
				}
				w.Write([]byte(`{"data":[{"provider":"example","language":"en","score":87,"subtitle":"opaque","forced":"False","hearing_impaired":"True","original_format":"False"}]}`))
				return
			}
			writes++
			r.ParseForm()
			for key, want := range map[string]string{"seriesid": "3", "episodeid": "42", "subtitle": "opaque", "provider": "example", "hi": "True", "forced": "False", "original_format": "False"} {
				if r.Form.Get(key) != want {
					t.Errorf("%s=%s", key, r.Form.Get(key))
				}
			}
			w.WriteHeader(204)
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()
	client, _ := New(config.ServiceConfig{BaseURL: server.URL})
	ctx := context.Background()
	media, err := client.SubtitleMedia(ctx, false, 42)
	if err != nil || media.EpisodeID != 42 {
		t.Fatalf("media=%+v err=%v", media, err)
	}
	history, err := client.SubtitleHistory(ctx, false, 42)
	if err != nil || len(history) != 1 || history[0].Score != "86.67%" {
		t.Fatalf("history=%+v err=%v", history, err)
	}
	candidates, err := client.SearchSubtitles(ctx, false, 42)
	if err != nil || len(candidates) != 1 {
		t.Fatalf("search err=%v", err)
	}
	if err = client.DownloadSubtitle(ctx, false, 42, 3, candidates[0]); err != nil {
		t.Fatal(err)
	}
	if writes != 1 {
		t.Fatalf("writes=%d", writes)
	}
}
