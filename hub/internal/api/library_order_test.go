package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
)

const (
	orderAnime  = "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1"
	orderMovies = "b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2"
	orderShows  = "c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3"
	orderHadas  = "dddddddddddddddddddddddddddddddd"
)

// orderUpstream is a Jellyfin that lists three libraries out of name order.
func orderUpstream(t *testing.T) *httptest.Server {
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/UserViews":
			_, _ = w.Write([]byte(`{"Items":[` +
				`{"Id":"` + orderShows + `","Name":"Shows","CollectionType":"tvshows"},` +
				`{"Id":"` + orderAnime + `","Name":"anime","CollectionType":"tvshows"},` +
				`{"Id":"` + orderMovies + `","Name":"Movies","CollectionType":"movies"}]}`))
		case "/Items":
			_, _ = w.Write([]byte(`{"Items":[],"TotalRecordCount":0}`))
		default:
			http.NotFound(w, r)
		}
	}))
}

func orderRequest(handler http.Handler, method, path, body, profile string) *httptest.ResponseRecorder {
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(method, path, strings.NewReader(body))
	request.Header.Set("Authorization", "Bearer "+libraryTestToken)
	if profile != "" {
		request.Header.Set(jellyfinUserHeader, profile)
	}
	handler.ServeHTTP(recorder, request)
	return recorder
}

func libraryOrderSeen(t *testing.T, handler http.Handler, profile string) (string, string) {
	t.Helper()
	got := orderRequest(handler, http.MethodGet, "/v1/library", "", profile)
	if got.Code != http.StatusOK {
		t.Fatalf("library returned %d: %s", got.Code, got.Body.String())
	}
	var body LibraryResponse
	if err := json.NewDecoder(got.Body).Decode(&body); err != nil {
		t.Fatal(err)
	}
	var names []string
	for _, view := range body.Views {
		names = append(names, view.Name)
	}
	return strings.Join(names, ","), body.Order
}

func TestArrangeLibrariesPutsTheSavedOrderFirstThenAToZ(t *testing.T) {
	type lib struct{ id, name string }
	libs := []lib{{"s", "Shows"}, {"a", "anime"}, {"m", "Movies"}, {"z", "Zoo"}}
	arrange := func(saved ...string) (string, bool) {
		out, custom := arrangeLibraries(libs, func(l lib) string { return l.id }, func(l lib) string { return l.name }, saved)
		var ids []string
		for _, l := range out {
			ids = append(ids, l.id)
		}
		return strings.Join(ids, ","), custom
	}
	if got, custom := arrange(); got != "a,m,s,z" || custom {
		t.Fatalf("nothing saved = %s (custom %v), want A to Z ignoring case", got, custom)
	}
	if got, custom := arrange("z", "s"); got != "z,s,a,m" || !custom {
		t.Fatalf("saved z,s = %s (custom %v)", got, custom)
	}
	// A library that is gone is skipped; one added since joins the A to Z tail.
	if got, custom := arrange("gone", "m"); got != "m,a,s,z" || !custom {
		t.Fatalf("saved gone,m = %s (custom %v)", got, custom)
	}
	if got, custom := arrange("gone"); got != "a,m,s,z" || custom {
		t.Fatalf("only a gone library saved = %s (custom %v)", got, custom)
	}
}

func TestLibraryOrderIsAToZUntilSavedAndBelongsToOneProfile(t *testing.T) {
	upstream := orderUpstream(t)
	defer upstream.Close()
	handler := NewServer(libraryAPIConfig(upstream.URL, "user-1")).Handler()

	if names, order := libraryOrderSeen(t, handler, ""); names != "anime,Movies,Shows" || order != "name" {
		t.Fatalf("default = %s (%s), want A to Z", names, order)
	}
	put := orderRequest(handler, http.MethodPut, "/v1/library/order",
		`{"side":"media","ids":["`+orderShows+`","`+strings.ToUpper(orderAnime)+`","`+orderShows+`"]}`, "")
	if put.Code != http.StatusOK {
		t.Fatalf("save returned %d: %s", put.Code, put.Body.String())
	}
	var saved LibraryOrderResponse
	if err := json.NewDecoder(put.Body).Decode(&saved); err != nil {
		t.Fatal(err)
	}
	if strings.Join(saved.IDs, ",") != orderShows+","+orderAnime || saved.Order != "custom" {
		t.Fatalf("saved = %+v: a repeat counts once and ids are lower-case", saved)
	}
	if names, order := libraryOrderSeen(t, handler, ""); names != "Shows,anime,Movies" || order != "custom" {
		t.Fatalf("after saving = %s (%s)", names, order)
	}
	// Another profile on the same hub keeps its own (still A to Z).
	if names, order := libraryOrderSeen(t, handler, orderHadas); names != "anime,Movies,Shows" || order != "name" {
		t.Fatalf("other profile = %s (%s)", names, order)
	}
	// An empty list goes back to A to Z.
	if got := orderRequest(handler, http.MethodPut, "/v1/library/order", `{"side":"media","ids":[]}`, ""); got.Code != http.StatusOK {
		t.Fatalf("reset returned %d", got.Code)
	}
	if names, order := libraryOrderSeen(t, handler, ""); names != "anime,Movies,Shows" || order != "name" {
		t.Fatalf("after reset = %s (%s)", names, order)
	}
}

func TestLibraryOrderRefusesBadInputAndKeepsWhatWasSaved(t *testing.T) {
	upstream := orderUpstream(t)
	defer upstream.Close()
	handler := NewServer(libraryAPIConfig(upstream.URL, "user-1")).Handler()
	if got := orderRequest(handler, http.MethodPut, "/v1/library/order", `{"side":"media","ids":["`+orderMovies+`"]}`, ""); got.Code != http.StatusOK {
		t.Fatalf("save returned %d", got.Code)
	}
	tooMany := `{"side":"media","ids":[` + strings.TrimSuffix(strings.Repeat(`"`+orderAnime+`",`, libraryOrderLimit+1), ",") + `]}`
	for _, c := range []struct{ body, profile string }{
		{`{"side":"music","ids":[]}`, ""},
		{`{"side":"media","ids":["not-a-view"]}`, ""},
		{`{"side":"books","ids":["../etc"]}`, ""},
		{`{"side":"media","ids":"` + orderAnime + `"}`, ""},
		{`{"side":"media","ids":[],"extra":1}`, ""},
		{`not json`, ""},
		{tooMany, ""},
		{`{"side":"media","ids":[]}`, "not-a-user"},
	} {
		if got := orderRequest(handler, http.MethodPut, "/v1/library/order", c.body, c.profile); got.Code != http.StatusBadRequest {
			t.Errorf("body %.60q (profile %q) returned %d, want 400", c.body, c.profile, got.Code)
		}
	}
	if names, _ := libraryOrderSeen(t, handler, ""); names != "Movies,anime,Shows" {
		t.Fatalf("a refused save changed the order: %s", names)
	}
}

func TestLibraryOrderSurvivesARestart(t *testing.T) {
	upstream := orderUpstream(t)
	defer upstream.Close()
	cfg := libraryAPIConfig(upstream.URL, "user-1")
	cfg.Server.OfflineRegistry = filepath.Join(t.TempDir(), "offline-grants.json")

	first := NewServer(cfg).Handler()
	if got := orderRequest(first, http.MethodPut, "/v1/library/order", `{"side":"media","ids":["`+orderShows+`"]}`, ""); got.Code != http.StatusOK {
		t.Fatalf("save returned %d", got.Code)
	}
	again := NewServer(cfg).Handler()
	if names, order := libraryOrderSeen(t, again, ""); names != "Shows,anime,Movies" || order != "custom" {
		t.Fatalf("after a restart = %s (%s)", names, order)
	}
}

func TestBooksLibrariesFollowTheirOwnSavedOrder(t *testing.T) {
	upstream := newReadingCatalogUpstream(t)
	defer upstream.Close()
	handler := NewServer(readingCatalogConfig(upstream.URL, filepath.Join(t.TempDir(), "catalog.json"), []string{"reading", "read"})).Handler()

	ids := func() (string, string) {
		got := orderRequest(handler, http.MethodGet, "/v1/reading/libraries", "", "")
		var body ReadingLibrariesResponse
		if err := json.Unmarshal(got.Body.Bytes(), &body); err != nil || got.Code != http.StatusOK {
			t.Fatalf("libraries = %d %s (%v)", got.Code, got.Body.String(), err)
		}
		return strings.Join(readingLibraryIDs(body.Libraries), ","), body.Order
	}
	if got, order := ids(); got != "storyteller:books,kavita:2" || order != "name" {
		t.Fatalf("default = %s (%s), want A to Z", got, order)
	}
	if got := orderRequest(handler, http.MethodPut, "/v1/library/order", `{"side":"books","ids":["kavita:2"]}`, ""); got.Code != http.StatusOK {
		t.Fatalf("save returned %d: %s", got.Code, got.Body.String())
	}
	if got, order := ids(); got != "kavita:2,storyteller:books" || order != "custom" {
		t.Fatalf("after saving = %s (%s)", got, order)
	}
}
