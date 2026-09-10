package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"ayaneohub/internal/adapters/jellyfin"
	"ayaneohub/internal/config"
)

const libraryTestToken = "a-strong-test-token-with-more-than-32-characters"

func libraryAPIConfig(baseURL, userID string) *config.Config {
	services := map[string]config.ServiceConfig{}
	if baseURL != "" {
		services["jellyfin"] = config.ServiceConfig{
			Enabled: true, BaseURL: baseURL, APIKey: config.Secret("test-key"), UserID: userID,
		}
	}
	return &config.Config{
		Auth: config.AuthConfig{
			Tokens:         []config.TokenConfig{{Label: "test", Raw: config.Secret(libraryTestToken)}},
			RateLimit:      config.RateLimitConfig{RPM: 600, Burst: 100},
			AuthFailureBan: config.BanConfig{Attempts: 5},
		},
		Services: services,
	}
}

func libraryRequest(handler http.Handler, path string) *httptest.ResponseRecorder {
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodGet, path, nil)
	request.Header.Set("Authorization", "Bearer "+libraryTestToken)
	handler.ServeHTTP(recorder, request)
	return recorder
}

func TestLibraryDetailRoutesRequireAuthentication(t *testing.T) {
	cfg := libraryAPIConfig("", "")
	server := NewServer(cfg).Handler()
	for _, path := range []string{
		"/v1/library/items/0123456789abcdef0123456789abcdef",
		"/v1/library/series/0123456789abcdef0123456789abcdef/seasons",
		"/v1/library/series/0123456789abcdef0123456789abcdef/episodes?seasonId=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
	} {
		recorder := httptest.NewRecorder()
		server.ServeHTTP(recorder, httptest.NewRequest(http.MethodGet, path, nil))
		if recorder.Code != http.StatusUnauthorized {
			t.Errorf("%s returned %d without a token", path, recorder.Code)
		}
	}
}

func TestLibraryRoutesRejectInvalidAndMissingIDsBeforeCallingJellyfin(t *testing.T) {
	calls := 0
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		http.Error(w, "unexpected", http.StatusInternalServerError)
	}))
	defer upstream.Close()
	handler := NewServer(libraryAPIConfig(upstream.URL, "user-1")).Handler()

	for _, path := range []string{
		"/v1/library/items/not-an-id",
		"/v1/library/series/not-an-id/seasons",
		"/v1/library/series/0123456789abcdef0123456789abcdef/episodes",
	} {
		if got := libraryRequest(handler, path).Code; got != http.StatusBadRequest {
			t.Errorf("%s returned %d, want 400", path, got)
		}
	}
	if got := libraryRequest(handler, "/v1/library/anything/else/here").Code; got != http.StatusNotFound {
		t.Errorf("unknown route returned %d, want 404", got)
	}
	if calls != 0 {
		t.Fatalf("invalid requests reached Jellyfin %d times", calls)
	}
}

func TestLibraryNeedsJellyfinAndAConfiguredUser(t *testing.T) {
	if got := libraryRequest(NewServer(libraryAPIConfig("", "")).Handler(), "/v1/library").Code; got != http.StatusServiceUnavailable {
		t.Fatalf("missing Jellyfin returned %d", got)
	}
	upstream := httptest.NewServer(http.NotFoundHandler())
	defer upstream.Close()
	if got := libraryRequest(NewServer(libraryAPIConfig(upstream.URL, "")).Handler(), "/v1/library").Code; got != http.StatusServiceUnavailable {
		t.Fatalf("missing Jellyfin user returned %d", got)
	}
}

func TestLibraryArtworkPrefersViewImageAndFallsBackToContainedTitle(t *testing.T) {
	const explicitID = "11111111111111111111111111111111"
	const fallbackID = "22222222222222222222222222222222"
	const movieID = "33333333333333333333333333333333"
	itemCalls := 0
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/UserViews":
			_, _ = w.Write([]byte(`{"Items":[` +
				`{"Id":"` + explicitID + `","Name":"Marvel","CollectionType":"movies","ImageTags":{"Primary":"custom"}},` +
				`{"Id":"` + fallbackID + `","Name":"Shows","CollectionType":"tvshows"}` +
				`]}`))
		case "/Items/" + explicitID + "/Images":
			_, _ = w.Write([]byte(`[{"ImageType":"Primary","Path":"C:\\ProgramData\\Jellyfin\\Server\\root\\default\\Marvel\\folder.jpg"}]`))
		case "/Items/" + fallbackID + "/Images":
			_, _ = w.Write([]byte(`[{"ImageType":"Primary","Path":"C:\\ProgramData\\Jellyfin\\Server\\metadata\\library\\22\\poster.png"}]`))
		case "/Items":
			itemCalls++
			if r.URL.Query().Get("parentId") != fallbackID || r.URL.Query().Get("limit") != "1" {
				t.Errorf("fallback artwork query = %v", r.URL.Query())
			}
			_, _ = w.Write([]byte(`{"Items":[{"Id":"` + movieID +
				`","Name":"Poster source","Type":"Series","ImageTags":{"Primary":"picked"}}],"TotalRecordCount":1}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer upstream.Close()

	got := libraryRequest(NewServer(libraryAPIConfig(upstream.URL, "user-1")).Handler(), "/v1/library")
	if got.Code != http.StatusOK {
		t.Fatalf("library returned %d: %s", got.Code, got.Body.String())
	}
	var body LibraryResponse
	if err := json.NewDecoder(got.Body).Decode(&body); err != nil {
		t.Fatal(err)
	}
	if len(body.Views) != 2 {
		t.Fatalf("views = %+v", body.Views)
	}
	if body.Views[0].Image != "/v1/img/jf/"+explicitID+"/Primary?tag=custom" {
		t.Fatalf("explicit artwork = %q", body.Views[0].Image)
	}
	if body.Views[1].Image != "/v1/img/jf/"+movieID+"/Primary?tag=picked" {
		t.Fatalf("fallback artwork = %q", body.Views[1].Image)
	}
	if itemCalls != 1 {
		t.Fatalf("fallback queried %d times", itemCalls)
	}
}

func TestExplicitLibraryArtworkRecognisesFolderImages(t *testing.T) {
	if !hasExplicitLibraryArtwork([]jellyfin.ImageInfo{{
		ImageType: "Primary", Path: `C:\ProgramData\Jellyfin\Server\root\default\Marvel\folder.webp`,
	}}) {
		t.Fatal("folder artwork was treated as generated")
	}
	if hasExplicitLibraryArtwork([]jellyfin.ImageInfo{{
		ImageType: "Primary", Path: `C:\ProgramData\Jellyfin\Server\metadata\library\aa\poster.png`,
	}}) {
		t.Fatal("generated collage was treated as explicit")
	}
}

func TestDailyLibraryArtworkIndexIsStableAndBounded(t *testing.T) {
	a := dailyLibraryArtworkIndex("2026-09-07", "library-a", 250)
	b := dailyLibraryArtworkIndex("2026-09-07", "library-a", 250)
	if a != b || a < 0 || a >= 250 {
		t.Fatalf("indices = %d, %d", a, b)
	}
	if got := dailyLibraryArtworkIndex("2026-09-07", "library-a", 0); got != 0 {
		t.Fatalf("empty library index = %d", got)
	}
}

func TestLibraryItemsMapsSortAndDirectionToJellyfin(t *testing.T) {
	const viewID = "0123456789abcdef0123456789abcdef"
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		q := r.URL.Query()
		if q.Get("sortBy") != "CommunityRating" || q.Get("sortOrder") != "Descending" ||
			q.Get("startIndex") != "60" {
			t.Errorf("sorted library query = %v", q)
		}
		_, _ = w.Write([]byte(`{"Items":[],"TotalRecordCount":0}`))
	}))
	defer upstream.Close()

	got := libraryRequest(NewServer(libraryAPIConfig(upstream.URL, "user-1")).Handler(),
		"/v1/library/"+viewID+"/items?sort=rating&order=desc&page=2")
	if got.Code != http.StatusOK {
		t.Fatalf("items returned %d: %s", got.Code, got.Body.String())
	}
	var body LibraryItemsResponse
	if err := json.NewDecoder(got.Body).Decode(&body); err != nil {
		t.Fatal(err)
	}
	if body.SortedBy != "rating" || body.SortOrder != "desc" {
		t.Fatalf("sort envelope = %+v", body)
	}
}

func TestLibraryItemsRejectsUnknownSortWithoutCallingJellyfin(t *testing.T) {
	calls := 0
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
	}))
	defer upstream.Close()
	handler := NewServer(libraryAPIConfig(upstream.URL, "user-1")).Handler()
	got := libraryRequest(handler,
		"/v1/library/0123456789abcdef0123456789abcdef/items?sort=surprise")
	if got.Code != http.StatusBadRequest || calls != 0 {
		t.Fatalf("unknown sort returned %d after %d upstream calls", got.Code, calls)
	}
}

func TestLibraryDetailRoutesDispatchAndPageEpisodes(t *testing.T) {
	const id = "0123456789abcdef0123456789abcdef"
	const season = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Emby-Token") != "test-key" || r.URL.Query().Get("userId") != "user-1" {
			t.Errorf("missing Jellyfin authentication/user: header=%q query=%v",
				r.Header.Get("X-Emby-Token"), r.URL.Query())
		}
		switch r.URL.Path {
		case "/Items/" + id:
			_, _ = w.Write([]byte(`{"Id":"` + id + `","Name":"Movie","Type":"Movie"}`))
		case "/Shows/" + id + "/Seasons":
			_, _ = w.Write([]byte(`{"Items":[{"Id":"` + season + `","Name":"Specials","Type":"Season","IndexNumber":0}],"TotalRecordCount":1}`))
		case "/Shows/" + id + "/Episodes":
			if r.URL.Query().Get("seasonId") != season || r.URL.Query().Get("startIndex") != "60" {
				t.Errorf("episode paging query = %v", r.URL.Query())
			}
			_, _ = w.Write([]byte(`{"Items":[{"Id":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","Name":"Episode 61","Type":"Episode"}],"TotalRecordCount":61}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer upstream.Close()
	handler := NewServer(libraryAPIConfig(upstream.URL, "user-1")).Handler()

	if got := libraryRequest(handler, "/v1/library/items/"+id); got.Code != http.StatusOK {
		t.Fatalf("item returned %d: %s", got.Code, got.Body.String())
	}
	if got := libraryRequest(handler, "/v1/library/series/"+id+"/seasons"); got.Code != http.StatusOK {
		t.Fatalf("seasons returned %d: %s", got.Code, got.Body.String())
	}
	got := libraryRequest(handler, "/v1/library/series/"+id+"/episodes?seasonId="+season+"&page=2")
	if got.Code != http.StatusOK {
		t.Fatalf("episodes returned %d: %s", got.Code, got.Body.String())
	}
	var body LibraryEpisodesResponse
	if err := json.NewDecoder(got.Body).Decode(&body); err != nil {
		t.Fatal(err)
	}
	if body.Page != 2 || body.TotalPages != 2 || body.Total != 61 || len(body.Items) != 1 {
		t.Fatalf("episode envelope = %+v", body)
	}
}

func TestDeletedLibraryItemBecomesNotFound(t *testing.T) {
	upstream := httptest.NewServer(http.NotFoundHandler())
	defer upstream.Close()
	handler := NewServer(libraryAPIConfig(upstream.URL, "user-1")).Handler()
	got := libraryRequest(handler, "/v1/library/items/0123456789abcdef0123456789abcdef")
	if got.Code != http.StatusNotFound {
		t.Fatalf("deleted item returned %d: %s", got.Code, got.Body.String())
	}
}

func TestLibraryItemMappingKeepsJellyfinIdentityWithoutTmdb(t *testing.T) {
	item := jellyfin.Item{
		ID: "0123456789abcdef0123456789abcdef", Name: "Local film", Type: "Movie",
		ProductionYear: 2024, RunTimeTicks: 7_200 * jellyfin.TicksPerSecond,
		Genres: []string{"Drama"}, UserData: &jellyfin.UserData{
			PlayedPercentage: 37.5, PlaybackPositionTicks: 2_700 * jellyfin.TicksPerSecond,
		},
	}
	got := libraryItemFrom(item)
	if got.ID != item.ID || got.Type != "movie" || got.Title != "Local film" {
		t.Fatalf("identity changed: %+v", got)
	}
	if got.RuntimeSeconds != 7_200 || got.PositionSeconds != 2_700 || got.Progress != .375 {
		t.Fatalf("watch state changed: %+v", got)
	}
	if got.Genres == nil {
		t.Fatal("genres must encode as [] rather than null")
	}
}

func TestSpecialsKeepSeasonZero(t *testing.T) {
	got := libraryItemFrom(jellyfin.Item{
		ID: "0123456789abcdef0123456789abcdef", Name: "Specials", Type: "Season", IndexNumber: 0,
	})
	if got.Type != "season" || got.SeasonNumber != 0 || got.Title != "Specials" {
		t.Fatalf("specials were lost: %+v", got)
	}
}

func TestEpisodeMappingKeepsParentageAndOrderNumbers(t *testing.T) {
	got := libraryItemFrom(jellyfin.Item{
		ID: "0123456789abcdef0123456789abcdef", Name: "Pilot", Type: "Episode",
		SeriesID:          "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
		SeasonID:          "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
		ParentIndexNumber: 1, IndexNumber: 2,
	})
	if got.SeriesID == "" || got.SeasonID == "" || got.SeasonNumber != 1 || got.IndexNumber != 2 {
		t.Fatalf("episode position changed: %+v", got)
	}
	if got.Subtitle != "S1E2 · Pilot" {
		t.Fatalf("subtitle = %q", got.Subtitle)
	}
}

func TestUnknownJellyfinItemTypeStaysExplicit(t *testing.T) {
	if got := libraryType("Audio"); got != "unknown" {
		t.Fatalf("unknown type mapped to %q", got)
	}
}

func TestLibraryStateUpdatesSelectedUserAndReturnsFreshItem(t *testing.T) {
	const itemID = "0123456789abcdef0123456789abcdef"
	const userID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
	mutated := false
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodPost && r.URL.Path == "/Users/"+userID+"/PlayedItems/"+itemID:
			mutated = true
			w.WriteHeader(http.StatusNoContent)
		case r.Method == http.MethodGet && r.URL.Path == "/Items/"+itemID:
			if !mutated || r.URL.Query().Get("userId") != userID {
				t.Errorf("fresh item read before mutation or for wrong user: %v", r.URL.Query())
			}
			_, _ = w.Write([]byte(`{"Id":"` + itemID + `","Name":"Movie","Type":"Movie","UserData":{"Played":true}}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer upstream.Close()
	handler := NewServer(libraryAPIConfig(upstream.URL, userID)).Handler()
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodPost, "/v1/library/items/"+itemID+"/state",
		strings.NewReader(`{"played":true}`))
	request.Header.Set("Authorization", "Bearer "+libraryTestToken)
	handler.ServeHTTP(recorder, request)
	if recorder.Code != http.StatusOK {
		t.Fatalf("state update returned %d: %s", recorder.Code, recorder.Body.String())
	}
	var body LibraryItemResponse
	if err := json.NewDecoder(recorder.Body).Decode(&body); err != nil {
		t.Fatal(err)
	}
	if !mutated || !body.Item.Played {
		t.Fatalf("mutation response = %+v", body)
	}
}

func TestLibraryStateRequiresOneFieldAndPlayScope(t *testing.T) {
	const itemID = "0123456789abcdef0123456789abcdef"
	calls := 0
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { calls++ }))
	defer upstream.Close()
	cfg := libraryAPIConfig(upstream.URL, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
	cfg.Auth.Tokens[0].Scopes = []string{"read"}
	handler := NewServer(cfg).Handler()
	for _, body := range []string{`{"played":true}`, `{}`, `{"played":true,"favorite":true}`} {
		recorder := httptest.NewRecorder()
		request := httptest.NewRequest(http.MethodPost, "/v1/library/items/"+itemID+"/state", strings.NewReader(body))
		request.Header.Set("Authorization", "Bearer "+libraryTestToken)
		handler.ServeHTTP(recorder, request)
		if recorder.Code != http.StatusForbidden {
			t.Fatalf("read-only state update returned %d", recorder.Code)
		}
	}
	if calls != 0 {
		t.Fatalf("forbidden updates reached Jellyfin %d times", calls)
	}
}

func TestLibrarySearchAndFavoritesUseJellyfinFilters(t *testing.T) {
	const userID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
	seenSearch := false
	seenFavorites := false
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/Items" || r.URL.Query().Get("userId") != userID {
			http.NotFound(w, r)
			return
		}
		if r.URL.Query().Get("searchTerm") == "Mentalist" {
			seenSearch = true
		}
		if r.URL.Query().Get("filters") == "IsFavorite" {
			seenFavorites = true
		}
		_, _ = w.Write([]byte(`{"Items":[],"TotalRecordCount":0}`))
	}))
	defer upstream.Close()
	handler := NewServer(libraryAPIConfig(upstream.URL, userID)).Handler()
	if got := libraryRequest(handler, "/v1/library/search?q=Mentalist"); got.Code != http.StatusOK {
		t.Fatalf("search returned %d: %s", got.Code, got.Body.String())
	}
	if got := libraryRequest(handler, "/v1/library/favorites"); got.Code != http.StatusOK {
		t.Fatalf("favorites returned %d: %s", got.Code, got.Body.String())
	}
	if !seenSearch || !seenFavorites {
		t.Fatalf("search=%v favorites=%v", seenSearch, seenFavorites)
	}
}

func TestLibraryStateBodyValidationStopsBeforeJellyfin(t *testing.T) {
	const itemID = "0123456789abcdef0123456789abcdef"
	calls := 0
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { calls++ }))
	defer upstream.Close()
	handler := NewServer(libraryAPIConfig(upstream.URL, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")).Handler()
	for _, body := range []string{`{}`, `{"played":true,"favorite":false}`, `not-json`} {
		recorder := httptest.NewRecorder()
		request := httptest.NewRequest(http.MethodPost, "/v1/library/items/"+itemID+"/state", strings.NewReader(body))
		request.Header.Set("Authorization", "Bearer "+libraryTestToken)
		handler.ServeHTTP(recorder, request)
		if recorder.Code != http.StatusBadRequest {
			t.Errorf("body %q returned %d", body, recorder.Code)
		}
	}
	if calls != 0 {
		t.Fatalf("invalid bodies reached Jellyfin %d times", calls)
	}
}
