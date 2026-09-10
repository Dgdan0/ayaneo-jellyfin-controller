package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"ayaneohub/internal/config"
)

func TestReleaseTargetsReturnsSeasonArtAndOnlyAiredEpisodes(t *testing.T) {
	sonarr := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v3/series":
			if r.URL.Query().Get("tvdbId") != "451782" {
				t.Errorf("series query = %v", r.URL.Query())
			}
			_, _ = w.Write([]byte(`[{"id":98,"title":"Last Seen","tvdbId":451782}]`))
		case "/api/v3/episode":
			if r.URL.Query().Get("seriesId") != "98" || r.URL.Query().Get("seasonNumber") != "1" {
				t.Errorf("episode query = %v", r.URL.Query())
			}
			_, _ = w.Write([]byte(`[
                    {"id":9998,"seriesId":98,"seasonNumber":1,"episodeNumber":1,"title":"The Dispatcher","airDate":"2000-01-01","airDateUtc":"2000-01-01T04:00:00Z","runtime":53,"monitored":true,"hasFile":false},
                    {"id":9999,"seriesId":98,"seasonNumber":1,"episodeNumber":2,"title":"The Call","airDate":"2999-01-01","airDateUtc":"2999-01-01T04:00:00Z","runtime":52,"monitored":true,"hasFile":false},
                    {"id":10000,"seriesId":98,"seasonNumber":1,"episodeNumber":3,"title":"Recovered","runtime":44,"monitored":false,"hasFile":true}
                ]`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer sonarr.Close()

	jellyseerr := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v1/tv/258230":
			_, _ = w.Write([]byte(`{"name":"Last Seen","externalIds":{"tvdbId":451782}}`))
		case "/api/v1/tv/258230/season/1":
			_, _ = w.Write([]byte(`{
                    "name":"Season 1","seasonNumber":1,"posterPath":"/season.jpg",
                    "episodes":[
                        {"seasonNumber":1,"episodeNumber":1,"name":"The Dispatcher","overview":"A clue appears.","stillPath":"/episode-1.jpg"},
                        {"seasonNumber":1,"episodeNumber":2,"name":"The Call","stillPath":"/episode-2.jpg"}
                    ]
                }`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer jellyseerr.Close()

	cfg := &config.Config{
		Auth: config.AuthConfig{
			Tokens:         []config.TokenConfig{{Label: "test", Raw: config.Secret(libraryTestToken)}},
			RateLimit:      config.RateLimitConfig{RPM: 600, Burst: 100},
			AuthFailureBan: config.BanConfig{Attempts: 5},
		},
		Services: map[string]config.ServiceConfig{
			"sonarr":     {Enabled: true, BaseURL: sonarr.URL, APIKey: config.Secret("sonarr-key")},
			"jellyseerr": {Enabled: true, BaseURL: jellyseerr.URL, APIKey: config.Secret("jellyseerr-key")},
		},
	}
	recorder := libraryRequest(
		NewServer(cfg).Handler(),
		"/v1/media/tmdb:series:258230/release-targets?season=1",
	)
	if recorder.Code != http.StatusOK {
		t.Fatalf("targets returned %d: %s", recorder.Code, recorder.Body.String())
	}
	var body ReleaseTargetsResponse
	if err := json.NewDecoder(recorder.Body).Decode(&body); err != nil {
		t.Fatal(err)
	}
	if body.Title != "Last Seen" || body.SeasonTitle != "Season 1" ||
		body.SeasonImage != "/v1/img/tmdb/w342/season.jpg" {
		t.Fatalf("header = %+v", body)
	}
	if len(body.Episodes) != 2 || body.Episodes[0].Episode != 1 || body.Episodes[1].Episode != 3 {
		t.Fatalf("aired episodes = %+v", body.Episodes)
	}
	if body.Episodes[0].Image != "/v1/img/tmdb/w500/episode-1.jpg" ||
		body.Episodes[0].Overview != "A clue appears." {
		t.Fatalf("episode artwork join = %+v", body.Episodes[0])
	}
	if !body.Episodes[1].HasFile || body.Episodes[1].Monitored {
		t.Fatalf("Sonarr state = %+v", body.Episodes[1])
	}
}

func TestReleaseTargetsRequiresSeriesAndExplicitSeason(t *testing.T) {
	handler := NewServer(libraryAPIConfig("", "")).Handler()
	for _, path := range []string{
		"/v1/media/tmdb:movie:258230/release-targets?season=1",
		"/v1/media/tmdb:series:258230/release-targets",
		"/v1/media/tmdb:series:258230/release-targets?season=-1",
	} {
		if got := libraryRequest(handler, path).Code; got != http.StatusBadRequest {
			t.Errorf("%s returned %d, want 400", path, got)
		}
	}
}
