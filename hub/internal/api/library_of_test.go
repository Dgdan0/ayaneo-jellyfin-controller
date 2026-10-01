package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"ayaneohub/internal/adapters/jellyfin"
)

func TestTheNearestCollectionFolderIsTheLibrary(t *testing.T) {
	got := collectionFolder([]jellyfin.Item{
		{ID: "season", Type: "Season"}, {ID: "series", Type: "Series"},
		{ID: "anime", Name: "Anime", Type: "CollectionFolder", CollectionType: "tvshows"},
		{ID: "root", Name: "Media Folders", Type: "AggregateFolder"},
	})
	if got.ID != "anime" || got.Name != "Anime" {
		t.Fatalf("library = %+v", got)
	}
	if none := collectionFolder([]jellyfin.Item{{ID: "x", Type: "Folder"}}); none.ID != "" {
		t.Fatalf("no library = %+v", none)
	}
}

func TestItemDetailNamesItsLibrary(t *testing.T) {
	const id = "0123456789abcdef0123456789abcdef"
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/Items/" + id:
			_, _ = w.Write([]byte(`{"Id":"` + id + `","Name":"Bleach","Type":"Series"}`))
		case "/Items/" + id + "/Ancestors":
			_, _ = w.Write([]byte(`[{"Id":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","Name":"Anime","Type":"CollectionFolder","CollectionType":"tvshows"}]`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer upstream.Close()
	handler := NewServer(libraryAPIConfig(upstream.URL, "user-1")).Handler()
	got := libraryRequest(handler, "/v1/library/items/"+id)
	var body LibraryItemResponse
	if err := json.NewDecoder(got.Body).Decode(&body); err != nil {
		t.Fatal(err)
	}
	if body.Item.Library == nil || body.Item.Library.Name != "Anime" {
		t.Fatalf("library = %+v", body.Item.Library)
	}
}
