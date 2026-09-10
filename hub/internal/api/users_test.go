package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
)

func TestUsersListsEnabledProfilesAndMarksTheSelection(t *testing.T) {
	const defaultID = "11111111111111111111111111111111"
	const selectedID = "22222222222222222222222222222222"
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/Users" {
			http.NotFound(w, r)
			return
		}
		_, _ = w.Write([]byte(`[
			{"Id":"` + defaultID + `","Name":"Default","Policy":{"IsDisabled":false}},
			{"Id":"` + selectedID + `","Name":"Selected","Policy":{"IsDisabled":false}},
			{"Id":"33333333333333333333333333333333","Name":"Disabled","Policy":{"IsDisabled":true}}
		]`))
	}))
	defer upstream.Close()

	handler := NewServer(libraryAPIConfig(upstream.URL, defaultID)).Handler()
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodGet, "/v1/users", nil)
	request.Header.Set("Authorization", "Bearer "+libraryTestToken)
	request.Header.Set(jellyfinUserHeader, selectedID)
	handler.ServeHTTP(recorder, request)
	if recorder.Code != http.StatusOK {
		t.Fatalf("users returned %d: %s", recorder.Code, recorder.Body.String())
	}
	var body UsersResponse
	if err := json.NewDecoder(recorder.Body).Decode(&body); err != nil {
		t.Fatal(err)
	}
	if len(body.Users) != 2 || body.Users[0].Selected || !body.Users[1].Selected {
		t.Fatalf("users = %+v", body.Users)
	}
	if recorder.Header().Get("Vary") != jellyfinUserHeader {
		t.Fatalf("Vary = %q", recorder.Header().Get("Vary"))
	}
}

func TestHomeCacheIsSeparatedBySelectedUser(t *testing.T) {
	const firstID = "11111111111111111111111111111111"
	const secondID = "22222222222222222222222222222222"
	calls := map[string]int{}
	var callsMu sync.Mutex
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		userID := r.URL.Query().Get("userId")
		callsMu.Lock()
		calls[userID]++
		callsMu.Unlock()
		switch r.URL.Path {
		case "/Items", "/Shows/NextUp":
			_, _ = w.Write([]byte(`{"Items":[],"TotalRecordCount":0}`))
		case "/Items/Latest":
			_, _ = w.Write([]byte(`[]`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer upstream.Close()
	handler := NewServer(libraryAPIConfig(upstream.URL, firstID)).Handler()

	for _, userID := range []string{firstID, secondID} {
		recorder := httptest.NewRecorder()
		request := httptest.NewRequest(http.MethodGet, "/v1/home", nil)
		request.Header.Set("Authorization", "Bearer "+libraryTestToken)
		request.Header.Set(jellyfinUserHeader, userID)
		handler.ServeHTTP(recorder, request)
		if recorder.Code != http.StatusOK {
			t.Fatalf("home for %s returned %d", userID, recorder.Code)
		}
	}
	callsMu.Lock()
	defer callsMu.Unlock()
	if calls[firstID] != 4 || calls[secondID] != 4 {
		t.Fatalf("upstream calls were shared across users: %+v", calls)
	}
}
