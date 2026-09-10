package arr

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"ayaneohub/internal/config"
)

func TestEpisodeSearchUsesSonarrEpisodeID(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v3/episode":
			if r.URL.Query().Get("seriesId") != "98" || r.URL.Query().Get("seasonNumber") != "1" {
				t.Errorf("episode list query = %v", r.URL.Query())
			}
			_, _ = w.Write([]byte(`[{"id":9998,"seriesId":98,"seasonNumber":1,"episodeNumber":1,"title":"The Dispatcher"}]`))
		case "/api/v3/release":
			if r.URL.Query().Get("episodeId") != "9998" {
				t.Errorf("release query = %v", r.URL.Query())
			}
			_, _ = w.Write([]byte(`[{"title":"Last Seen S01E01","episodeNumbers":[1]}]`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()

	client, err := New(Sonarr, config.ServiceConfig{
		BaseURL: server.URL, APIKey: config.Secret("test-key"),
	})
	if err != nil {
		t.Fatal(err)
	}
	episodes, err := client.Episodes(context.Background(), 98, 1)
	if err != nil || len(episodes) != 1 || episodes[0].ID != 9998 {
		t.Fatalf("episodes = %+v, err = %v", episodes, err)
	}
	releases, err := client.EpisodeReleases(context.Background(), episodes[0].ID)
	if err != nil || len(releases) != 1 || releases[0].EpisodeNos[0] != 1 {
		t.Fatalf("releases = %+v, err = %v", releases, err)
	}
}
