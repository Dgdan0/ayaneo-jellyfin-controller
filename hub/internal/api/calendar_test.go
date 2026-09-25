package api

import (
	"ayaneohub/internal/adapters/arr"
	"ayaneohub/internal/config"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestCalendarKeepsDateOnlyMovieDatesAndConvertsEpisodes(t *testing.T) {
	loc, _ := time.LoadLocation("Asia/Jerusalem")
	start, _ := time.ParseInLocation(time.DateOnly, "2026-09-25", loc)
	end := start.AddDate(0, 0, 7)
	movie := arr.CalendarMovie{ID: 3, Title: "A film", TmdbID: 99, Monitored: true, DigitalRelease: "2026-09-25T00:00:00Z", InCinemas: "2026-09-27T00:00:00Z"}
	items := movieCalendarItems(movie, start, end)
	if len(items) != 2 || items[0].Date != "2026-09-25" || items[0].ReleaseType != "Digital release" || items[0].At != "" {
		t.Fatalf("movie dates: %+v", items)
	}
	episode := arr.CalendarEpisode{ID: 7, AirDate: "2026-09-25", AirDateUTC: "2026-09-25T23:30:00Z", Monitored: true, Series: arr.CalendarSeries{ID: 8, Title: "A show", TmdbID: 88, Monitored: true}}
	item, ok := episodeCalendarItem(episode, start, end, loc)
	if !ok || item.Date != "2026-09-26" || item.Media.Key != "tmdb:series:88" {
		t.Fatalf("episode date: %+v, %v", item, ok)
	}
	episode.Monitored = false
	if _, ok := episodeCalendarItem(episode, start, end, loc); ok {
		t.Fatal("unmonitored episode included")
	}
	episode.Monitored = true
	episode.AirDateUTC = ""
	episode.AirDate = "2026-09-30"
	item, ok = episodeCalendarItem(episode, start, end, loc)
	if !ok || item.At != "" || item.Date != "2026-09-30" {
		t.Fatalf("date-only episode: %+v", item)
	}
}

func TestCalendarRejectsInvalidRangeAndTimezone(t *testing.T) {
	s := NewServer(libraryAPIConfig("", "")).Handler()
	for _, query := range []string{
		"start=bad&end=2026-09-30", "start=2026-09-25&end=2026-09-25",
		"start=2026-09-25&end=2027-09-25", "start=2026-09-25&end=2026-10-02&timezone=Nope",
	} {
		if r := libraryRequest(s, "/v1/calendar?"+query); r.Code != 400 {
			t.Errorf("%s: %d %s", query, r.Code, r.Body.String())
		}
	}
}

func TestCalendarPartialFailureAndPosterIsolation(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Api-Key") != "calendar-key" {
			t.Error("missing upstream auth")
		}
		if r.URL.Path == "/api/v3/calendar" {
			if r.URL.Query().Get("includeSeries") != "true" || r.URL.Query().Get("unmonitored") != "false" {
				t.Errorf("query: %s", r.URL.RawQuery)
			}
			_, _ = w.Write([]byte(`[{"id":7,"seriesId":8,"seasonNumber":2,"episodeNumber":1,"title":"Episode","airDateUtc":"2026-09-25T20:00:00Z","monitored":true,"series":{"id":8,"title":"A show","tmdbId":88,"monitored":true}}]`))
			return
		}
		if r.URL.Path == "/api/v3/mediacover/8/poster.jpg" {
			w.Header().Set("Content-Type", "image/jpeg")
			_, _ = w.Write([]byte("image"))
			return
		}
		http.NotFound(w, r)
	}))
	defer upstream.Close()
	cfg := libraryAPIConfig("", "")
	cfg.Services = map[string]config.ServiceConfig{
		"sonarr": {Enabled: true, BaseURL: upstream.URL, APIKey: config.Secret("calendar-key")},
		"radarr": {Enabled: true, BaseURL: "http://127.0.0.1:1"},
	}
	handler := NewServer(cfg).Handler()
	r := libraryRequest(handler, "/v1/calendar?start=2026-09-25&end=2026-10-02&timezone=Asia%2FJerusalem")
	if r.Code != 200 {
		t.Fatalf("%d: %s", r.Code, r.Body.String())
	}
	var body CalendarResponse
	if err := json.Unmarshal(r.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if len(body.Items) != 1 || len(body.Partial) != 1 || body.Items[0].Media.Poster != "/v1/img/arr/sonarr/8" {
		t.Fatalf("body: %+v", body)
	}
	if r = libraryRequest(handler, "/v1/img/arr/sonarr/8"); r.Code != 200 {
		t.Fatalf("poster: %d", r.Code)
	}
	for _, path := range []string{"/v1/img/arr/sonarr/0", "/v1/img/arr/sonarr/not-an-id", "/v1/img/arr/other/8"} {
		if r = libraryRequest(handler, path); r.Code != 400 {
			t.Errorf("bad poster %s: %d", path, r.Code)
		}
	}
}
