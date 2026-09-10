package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"ayaneohub/internal/adapters/jellyfin"
)

func TestHomeKeepsRowOrderAndMapsEpisodeProgress(t *testing.T) {
	const episodeID = "11111111111111111111111111111111"
	const seriesID = "22222222222222222222222222222222"
	const nextID = "33333333333333333333333333333333"
	const movieID = "44444444444444444444444444444444"
	const hiddenNextID = "66666666666666666666666666666666"
	const latestEpisodeID = "88888888888888888888888888888888"
	const latestSeriesID = "99999999999999999999999999999999"

	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Emby-Token") != "test-key" || r.URL.Query().Get("userId") != "user-1" {
			t.Errorf("missing Jellyfin authentication/user: header=%q query=%v",
				r.Header.Get("X-Emby-Token"), r.URL.Query())
		}
		switch r.URL.Path {
		case "/Items":
			switch r.URL.Query().Get("filters") {
			case "IsFavorite":
				_, _ = w.Write([]byte(`{"Items":[],"TotalRecordCount":0}`))
			case "IsResumable":
				_, _ = w.Write([]byte(`{"Items":[{` +
					`"Id":"` + episodeID + `","Name":"Pilot","Type":"Episode",` +
					`"SeriesName":"A Show","SeriesId":"` + seriesID + `",` +
					`"SeriesPrimaryImageTag":"series-poster","ImageTags":{"Primary":"episode-still"},` +
					`"ParentIndexNumber":1,"IndexNumber":2,` +
					`"UserData":{"PlayedPercentage":50}}],"TotalRecordCount":1}`))
			default:
				t.Errorf("unexpected /Items query: %v", r.URL.Query())
				http.Error(w, "unexpected", http.StatusBadRequest)
			}
		case "/Shows/NextUp":
			_, _ = w.Write([]byte(`{"Items":[` +
				`{"Id":"` + hiddenNextID + `","Name":"Episode 3","Type":"Episode",` +
				`"SeriesName":"A Show","SeriesId":"` + seriesID + `"},` +
				`{"Id":"` + nextID + `","Name":"Next","Type":"Episode",` +
				`"SeriesName":"Another Show","SeriesId":"77777777777777777777777777777777"}` +
				`],"TotalRecordCount":2}`))
		case "/Items/Latest":
			if r.URL.Query().Get("includeItemTypes") != "Movie,Episode" {
				t.Errorf("latest item types = %q", r.URL.Query().Get("includeItemTypes"))
			}
			if r.URL.Query().Get("groupItems") != "true" {
				t.Errorf("latest groupItems = %q", r.URL.Query().Get("groupItems"))
			}
			_, _ = w.Write([]byte(`[{"Id":"` + latestEpisodeID +
				`","Name":"New Episode","Type":"Episode","SeriesId":"` + latestSeriesID +
				`","SeriesName":"New Show","SeriesPrimaryImageTag":"new-show-poster"},` +
				`{"Id":"` + movieID +
				`","Name":"New Film","Type":"Movie","ProductionYear":2026}]`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer upstream.Close()

	got := libraryRequest(NewServer(libraryAPIConfig(upstream.URL, "user-1")).Handler(), "/v1/home")
	if got.Code != http.StatusOK {
		t.Fatalf("home returned %d: %s", got.Code, got.Body.String())
	}
	var body HomeResponse
	if err := json.NewDecoder(got.Body).Decode(&body); err != nil {
		t.Fatal(err)
	}
	if len(body.Rows) != 3 || body.Rows[0].ID != "continue" ||
		body.Rows[1].ID != "nextup" || body.Rows[2].ID != "latest" {
		t.Fatalf("row order = %+v", body.Rows)
	}
	continued := body.Rows[0].Items[0]
	if continued.JellyfinItemID != episodeID || continued.Progress != .5 {
		t.Fatalf("continue item = %+v", continued)
	}
	wantPoster := "/v1/img/jf/" + seriesID + "/Primary?tag=series-poster"
	if continued.Media.Poster != wantPoster || continued.Subtitle != "S1E2 · Pilot" ||
		continued.Media.Type != "episode" ||
		continued.Media.Backdrop != "/v1/img/jf/"+episodeID+"/Primary?tag=episode-still" {
		t.Fatalf("episode card = %+v", continued)
	}
	if got := body.Rows[1].Items; len(got) != 1 || got[0].JellyfinItemID != nextID {
		t.Fatalf("next up should exclude a series still being watched: %+v", got)
	}
	latest := body.Rows[2].Items
	if len(latest) != 2 || latest[0].JellyfinItemID != latestSeriesID ||
		latest[0].Media.Type != "series" || latest[0].Media.Title != "New Show" ||
		latest[0].Media.Poster != "/v1/img/jf/"+latestSeriesID+"/Primary?tag=new-show-poster" ||
		latest[1].JellyfinItemID != movieID {
		t.Fatalf("recent title cards = %+v", latest)
	}
	if len(body.Partial) != 0 {
		t.Fatalf("unexpected partials = %+v", body.Partial)
	}
}

func TestRecentlyAddedCollapsesEpisodesFromTheSameSeries(t *testing.T) {
	server := &Server{}
	items := []jellyfin.Item{
		{ID: "episode-2", Type: "Episode", SeriesID: "series", SeriesName: "Show"},
		{ID: "episode-1", Type: "Episode", SeriesID: "series", SeriesName: "Show"},
		{ID: "movie", Type: "Movie", Name: "Film"},
	}
	got := server.homeRowHits("latest", items)
	if len(got) != 2 || got[0].JellyfinItemID != "series" || got[1].JellyfinItemID != "movie" {
		t.Fatalf("recent title cards = %+v", got)
	}
}

func TestContinueWatchingKeepsOneCardPerSeries(t *testing.T) {
	const seriesID = "22222222222222222222222222222222"
	items := []jellyfin.Item{
		{ID: "one", Type: "Episode", SeriesID: seriesID, Name: "First"},
		{ID: "two", Type: "Episode", SeriesID: seriesID, Name: "Second"},
		{ID: "movie", Type: "Movie", Name: "A Movie"},
	}
	got := distinctHomeSeries(items)
	if len(got) != 2 || got[0].ID != "one" || got[1].ID != "movie" {
		t.Fatalf("distinct resume items = %+v", got)
	}
}

func TestHomeRetainsSuccessfulRowsWhenOneSourceFails(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/Items":
			if r.URL.Query().Get("filters") == "IsResumable" {
				if r.URL.Query().Get("includeItemTypes") != "Movie,Episode" {
					t.Errorf("resume item types = %q", r.URL.Query().Get("includeItemTypes"))
				}
				http.Error(w, "offline", http.StatusBadGateway)
				return
			}
			_, _ = w.Write([]byte(`{"Items":[],"TotalRecordCount":0}`))
		case "/Shows/NextUp":
			_, _ = w.Write([]byte(`{"Items":[],"TotalRecordCount":0}`))
		case "/Items/Latest":
			_, _ = w.Write([]byte(`[{"Id":"55555555555555555555555555555555",` +
				`"Name":"Still available","Type":"Movie"}]`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer upstream.Close()

	got := libraryRequest(NewServer(libraryAPIConfig(upstream.URL, "user-1")).Handler(), "/v1/home")
	if got.Code != http.StatusOK {
		t.Fatalf("home returned %d: %s", got.Code, got.Body.String())
	}
	var body HomeResponse
	if err := json.NewDecoder(got.Body).Decode(&body); err != nil {
		t.Fatal(err)
	}
	if len(body.Rows) != 1 || body.Rows[0].ID != "latest" {
		t.Fatalf("successful rows = %+v", body.Rows)
	}
	if len(body.Partial) != 1 || body.Partial[0].Affects[0] != "rows.continue" {
		t.Fatalf("partial = %+v", body.Partial)
	}
}
