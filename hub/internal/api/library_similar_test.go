package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestLibrarySimilarReturnsJellyfinsMoreLikeThisAsCards(t *testing.T) {
	const id = "0123456789abcdef0123456789abcdef"
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/Items/"+id+"/Similar" {
			http.NotFound(w, r)
			return
		}
		if r.URL.Query().Get("userId") != "user-1" || r.URL.Query().Get("limit") != "16" {
			t.Errorf("similar query = %v", r.URL.Query())
		}
		_, _ = w.Write([]byte(`{"Items":[{"Id":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","Name":"Heat","Type":"Movie","ProductionYear":1995,
			"UserData":{"Played":true}}],"TotalRecordCount":1}`))
	}))
	defer upstream.Close()
	handler := NewServer(libraryAPIConfig(upstream.URL, "user-1")).Handler()

	got := libraryRequest(handler, "/v1/library/items/"+id+"/similar")
	if got.Code != http.StatusOK {
		t.Fatalf("similar returned %d: %s", got.Code, got.Body.String())
	}
	var body LibraryItemsResponse
	if err := json.NewDecoder(got.Body).Decode(&body); err != nil {
		t.Fatal(err)
	}
	if len(body.Items) != 1 || body.Items[0].Media.Title != "Heat" || !body.Items[0].Played {
		t.Fatalf("similar items = %+v", body.Items)
	}
	if bad := libraryRequest(handler, "/v1/library/items/not-an-id/similar"); bad.Code != http.StatusBadRequest {
		t.Fatalf("bad id returned %d", bad.Code)
	}
}
