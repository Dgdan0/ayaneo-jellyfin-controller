package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"ayaneohub/internal/adapters/bazarr"
	"ayaneohub/internal/config"
)

func notificationTestConfig(sonarr, radarr, bazarr string) *config.Config {
	return &config.Config{
		Auth: config.AuthConfig{
			Tokens:         []config.TokenConfig{{Label: "test", Raw: config.Secret(libraryTestToken)}},
			RateLimit:      config.RateLimitConfig{RPM: 600, Burst: 100},
			AuthFailureBan: config.BanConfig{Attempts: 5},
		},
		Services: map[string]config.ServiceConfig{
			"sonarr": {Enabled: true, BaseURL: sonarr, APIKey: config.Secret("sonarr-key")},
			"radarr": {Enabled: true, BaseURL: radarr, APIKey: config.Secret("radarr-key")},
			"bazarr": {Enabled: true, BaseURL: bazarr, APIKey: config.Secret("bazarr-key")},
		},
	}
}

func TestNotificationsRequireAuthentication(t *testing.T) {
	handler := NewServer(notificationTestConfig("", "", "")).Handler()
	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, httptest.NewRequest(http.MethodGet, "/v1/notifications", nil))
	if recorder.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", recorder.Code)
	}
}

func TestNotificationsGroupArrAndBazarrEventsWithCurrentHealth(t *testing.T) {
	sonarr := notificationArrServer(t, "sonarr")
	defer sonarr.Close()
	radarr := notificationArrServer(t, "radarr")
	defer radarr.Close()
	bazarr := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-API-KEY") != "bazarr-key" {
			t.Errorf("Bazarr API key header missing")
		}
		if r.URL.Query().Get("start") != "0" && r.URL.Path != "/api/system/health" {
			t.Errorf("Bazarr paging query = %v", r.URL.Query())
		}
		switch r.URL.Path {
		case "/api/episodes/history":
			_, _ = w.Write([]byte(`{"data":[{"action":1,"seriesTitle":"Example Show","episode_number":"1x02","episodeTitle":"Second","timestamp":"an hour ago","parsed_timestamp":"09/09/26 17:00:00","description":"downloaded","language":{"name":"English","code2":"en"},"provider":"OpenSubtitles","score":"95%"}],"total":1}`))
		case "/api/movies/history":
			_, _ = w.Write([]byte(`{"data":[{"action":3,"title":"Example Movie","timestamp":"yesterday","parsed_timestamp":"09/08/26 17:00:00","description":"better subtitle","language":{"name":"Hebrew","code2":"he"},"provider":"Ktuvit"}],"total":1}`))
		case "/api/system/health":
			_, _ = w.Write([]byte(`{"data":[{"object":"Language profile","issue":"No profile is assigned"}]}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer bazarr.Close()

	recorder := libraryRequest(
		NewServer(notificationTestConfig(sonarr.URL, radarr.URL, bazarr.URL)).Handler(),
		"/v1/notifications",
	)
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d: %s", recorder.Code, recorder.Body.String())
	}
	var response NotificationsResponse
	if err := json.NewDecoder(recorder.Body).Decode(&response); err != nil {
		t.Fatal(err)
	}
	if len(response.Sections) != 3 || response.Sections[0].Service != "sonarr" ||
		response.Sections[1].Service != "radarr" || response.Sections[2].Service != "bazarr" {
		t.Fatalf("sections = %+v", response.Sections)
	}
	if response.AttentionCount != 2 {
		t.Fatalf("attentionCount = %d, want 2", response.AttentionCount)
	}
	if got := response.Sections[0].Items; len(got) != 2 || !got[0].Active ||
		got[1].Title != "Example Show S01E02 · Second · Imported" {
		t.Fatalf("Sonarr items = %+v", got)
	}
	if got := response.Sections[1].Items; len(got) != 1 || got[0].Title != "Example Movie · Grabbed" {
		t.Fatalf("Radarr items = %+v", got)
	}
	if got := response.Sections[2].Items; len(got) != 3 || !got[0].Active ||
		got[1].Title != "Example Show 1x02 · Second · Subtitle downloaded" {
		t.Fatalf("Bazarr items = %+v", got)
	}
}

func TestNotificationsKeepOtherServicesWhenOneHistoryFails(t *testing.T) {
	sonarr := notificationArrServer(t, "sonarr")
	defer sonarr.Close()
	radarr := notificationArrServer(t, "radarr")
	defer radarr.Close()
	bazarr := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/episodes/history":
			_, _ = w.Write([]byte(`{"data":[],"total":0}`))
		case "/api/movies/history":
			http.Error(w, "temporarily unavailable", http.StatusServiceUnavailable)
		case "/api/system/health":
			_, _ = w.Write([]byte(`{"data":[]}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer bazarr.Close()

	recorder := libraryRequest(
		NewServer(notificationTestConfig(sonarr.URL, radarr.URL, bazarr.URL)).Handler(),
		"/v1/notifications",
	)
	var response NotificationsResponse
	if err := json.NewDecoder(recorder.Body).Decode(&response); err != nil {
		t.Fatal(err)
	}
	if recorder.Code != http.StatusOK || response.Sections[2].State != "degraded" ||
		len(response.Partial) != 1 || len(response.Sections[0].Items) == 0 {
		t.Fatalf("partial response = status %d, body %+v", recorder.Code, response)
	}
}

func notificationArrServer(t *testing.T, service string) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Api-Key") != service+"-key" {
			t.Errorf("%s API key header missing", service)
		}
		switch r.URL.Path {
		case "/api/v3/history":
			wantLimit := "20"
			if service == "sonarr" {
				wantLimit = "60"
			}
			if r.URL.Query().Get("pageSize") != wantLimit || r.URL.Query().Get("sortDirection") != "descending" {
				t.Errorf("%s history query = %v", service, r.URL.Query())
			}
			if service == "sonarr" {
				if r.URL.Query().Get("includeSeries") != "true" || r.URL.Query().Get("includeEpisode") != "true" {
					t.Errorf("Sonarr include query = %v", r.URL.Query())
				}
				_, _ = w.Write([]byte(`{"records":[{"id":11,"eventType":"downloadFolderImported","date":"2026-09-09T16:00:00Z","sourceTitle":"Example.Show.S01E02","quality":{"quality":{"name":"WEBDL-1080p"}},"series":{"title":"Example Show"},"episode":{"seasonNumber":1,"episodeNumber":2,"title":"Second"}}]}`))
			} else {
				if r.URL.Query().Get("includeMovie") != "true" {
					t.Errorf("Radarr include query = %v", r.URL.Query())
				}
				_, _ = w.Write([]byte(`{"records":[{"id":22,"eventType":"grabbed","date":"2026-09-09T15:00:00Z","sourceTitle":"Example.Movie.2026","movie":{"title":"Example Movie"}}]}`))
			}
		case "/api/v3/health":
			if service == "sonarr" {
				_, _ = w.Write([]byte(`[{"source":"Download clients","type":"warning","message":"Client is temporarily unavailable"}]`))
			} else {
				_, _ = w.Write([]byte(`[]`))
			}
		default:
			http.NotFound(w, r)
		}
	}))
}

func TestNotificationsAcceptPerServiceHistoryLimits(t *testing.T) {
	wants := map[string]string{"sonarr": "40", "radarr": "60"}
	serverFor := func(service string) *httptest.Server {
		return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			switch r.URL.Path {
			case "/api/v3/history":
				if got := r.URL.Query().Get("pageSize"); got != wants[service] {
					t.Errorf("%s pageSize = %q, want %q", service, got, wants[service])
				}
				_, _ = w.Write([]byte(`{"records":[]}`))
			case "/api/v3/health":
				_, _ = w.Write([]byte(`[]`))
			default:
				http.NotFound(w, r)
			}
		}))
	}
	sonarr := serverFor("sonarr")
	defer sonarr.Close()
	radarr := serverFor("radarr")
	defer radarr.Close()
	bazarr := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/system/health" {
			_, _ = w.Write([]byte(`{"data":[]}`))
			return
		}
		if got := r.URL.Query().Get("length"); got != "100" {
			t.Errorf("Bazarr length = %q, want 100", got)
		}
		_, _ = w.Write([]byte(`{"data":[],"total":0}`))
	}))
	defer bazarr.Close()

	recorder := libraryRequest(
		NewServer(notificationTestConfig(sonarr.URL, radarr.URL, bazarr.URL)).Handler(),
		"/v1/notifications?sonarrLimit=40&radarrLimit=60&bazarrLimit=100",
	)
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d: %s", recorder.Code, recorder.Body.String())
	}
}

func TestNotificationsRejectInvalidHistoryLimit(t *testing.T) {
	recorder := libraryRequest(
		NewServer(notificationTestConfig("", "", "")).Handler(),
		"/v1/notifications?sonarrLimit=101",
	)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", recorder.Code)
	}
}

func TestNotificationIDsDoNotDependOnListPosition(t *testing.T) {
	record := bazarr.EpisodeHistory{
		ID: 12, Action: 1, ParsedTimestamp: "09/09/26 17:00:00",
		SeriesTitle: "Example", EpisodeNumber: "1x02", EpisodeTitle: "Second",
	}
	if first, second := bazarrEpisodeNotification(record).ID, bazarrEpisodeNotification(record).ID; first != second {
		t.Fatalf("stable IDs differ: %q != %q", first, second)
	}
}

func TestBazarrTimestampChoosesThePlausibleLocaleOrdering(t *testing.T) {
	now := time.Date(2026, time.September, 10, 12, 0, 0, 0, time.Local)
	parsed := parseBazarrLocalTime("09/08/26 17:00:00", now)
	if parsed.Month() != time.September || parsed.Day() != 8 {
		t.Fatalf("parsed = %s", parsed)
	}
}
