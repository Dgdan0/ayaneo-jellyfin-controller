package api

import (
	"ayaneohub/internal/adapters/storyteller"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
)

func TestAuthorShelvesUseWholeCatalogAndPageEachAuthor(t *testing.T) {
	books := []storyteller.Book{}
	for i := 1; i <= 75; i++ {
		books = append(books, storyteller.Book{ID: int64(i), Title: fmt.Sprintf("Volume %d", i), Authors: []storyteller.Creator{{UUID: "pierce", Name: "Pierce Brown"}}, Series: []storyteller.Series{{Name: "Saga", Position: float64(i)}}})
	}
	books = append(books, storyteller.Book{ID: 100, Title: "Shared", Authors: []storyteller.Creator{{UUID: "pierce", Name: "Pierce Brown"}, {UUID: "brandon", Name: "Brandon Sanderson"}}}, storyteller.Book{ID: 101, Title: "Unknown"})
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/v2/token" {
			fmt.Fprint(w, `{"access_token":"t","token_type":"Bearer","expires_in":3600}`)
			return
		}
		json.NewEncoder(w).Encode(books)
	}))
	defer upstream.Close()
	handler := NewServer(readingCatalogConfig(upstream.URL, filepath.Join(t.TempDir(), "catalog.json"), []string{"reading"})).Handler()
	get := func(path string) ReadingAuthorsResponse {
		t.Helper()
		result := libraryRequest(handler, path)
		if result.Code != 200 {
			t.Fatalf("%d %s", result.Code, result.Body.String())
		}
		var out ReadingAuthorsResponse
		if err := json.Unmarshal(result.Body.Bytes(), &out); err != nil {
			t.Fatal(err)
		}
		return out
	}
	root := "/v1/reading/libraries/storyteller:books/authors"
	out := get(root)
	if len(out.Authors) != 3 || out.Authors[0].Name != "Brandon Sanderson" || out.Authors[1].Total != 76 || out.Authors[2].Name != "Unknown author" {
		t.Fatalf("groups: %+v", out.Authors)
	}
	group := out.Authors[1]
	if len(group.Items) != 12 || group.Items[1].SeriesIndex != 2 || group.Items[9].SeriesIndex != 10 {
		t.Fatalf("numeric series order: %+v", group.Items)
	}
	seen := map[string]bool{}
	for page := 1; page <= 7; page++ {
		pageOut := get(fmt.Sprintf("%s?authorId=%s&page=%d", root, group.ID, page))
		for _, item := range pageOut.Authors[0].Items {
			if seen[item.ID] {
				t.Fatal("duplicate across pages")
			}
			seen[item.ID] = true
		}
	}
	if len(seen) != 76 {
		t.Fatalf("paged only %d works", len(seen))
	}
	descending := get(root + "?direction=desc")
	if descending.Authors[0].Name != "Pierce Brown" || descending.Authors[0].Items[1].SeriesIndex != 2 || descending.Authors[2].Name != "Unknown author" {
		t.Fatal("descending reversed member order or misplaced unknown")
	}
	for _, path := range []string{root + "?page=0", root + "?authorId=bad"} {
		if got := libraryRequest(handler, path); got.Code != 400 {
			t.Fatalf("invalid query: %d", got.Code)
		}
	}
	unauth := httptest.NewRecorder()
	handler.ServeHTTP(unauth, httptest.NewRequest("GET", root, nil))
	if unauth.Code != 401 {
		t.Fatalf("auth %d", unauth.Code)
	}
	denied := NewServer(readingCatalogConfig(upstream.URL, "", []string{"browse"})).Handler()
	if got := libraryRequest(denied, root); got.Code != 403 {
		t.Fatalf("scope %d", got.Code)
	}
}

func TestReadingResolveUsesISBNAndDoesNotInventTitleMatches(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/v2/token" {
			fmt.Fprint(w, `{"access_token":"t","token_type":"Bearer","expires_in":3600}`)
			return
		}
		fmt.Fprint(w, `[{"id":1,"title":"Red Rising","identifiers":[{"type":"isbn","value":"978-0-345-53978-6"}],"authors":[{"name":"Pierce Brown"}],"ebook":{"uuid":"e"}}]`)
	}))
	defer upstream.Close()
	handler := NewServer(readingCatalogConfig(upstream.URL, filepath.Join(t.TempDir(), "catalog.json"), []string{"reading"})).Handler()
	matched := libraryRequest(handler, "/v1/reading/resolve?source=openlibrary&sourceId=OL1W&isbn=9780345539786")
	if matched.Code != 200 || !strings.Contains(matched.Body.String(), `"workId":"rw_`) {
		t.Fatalf("resolve: %d %s", matched.Code, matched.Body.String())
	}
	unknown := libraryRequest(handler, "/v1/reading/resolve?source=openlibrary&sourceId=OL2W&title=Red+Rising")
	if unknown.Code != 200 || strings.Contains(unknown.Body.String(), `"workId":"rw_`) {
		t.Fatalf("title-only match: %s", unknown.Body.String())
	}
	invalid := libraryRequest(handler, "/v1/reading/resolve?source=openlibrary&sourceId=OL1W&isbn=garbage")
	if invalid.Code != 400 {
		t.Fatalf("bad isbn: %d", invalid.Code)
	}
}

func TestWorkGridKeepsBooksSeparateWhileSeriesViewKeepsCollections(t *testing.T) {
	s := NewServer(readingCatalogConfig("http://127.0.0.1", "", []string{"reading"}))
	books := []storyteller.Book{
		{ID: 1, Title: "Red Rising", Authors: []storyteller.Creator{{Name: "Pierce Brown"}}, Series: []storyteller.Series{{Name: "Red Rising", Position: 1}}},
		{ID: 2, Title: "Golden Son", Authors: []storyteller.Creator{{Name: "Pierce Brown"}}, Series: []storyteller.Series{{Name: "Red Rising", Position: 2}}},
	}
	individual, err := s.storytellerShelfWithGrouping("storyteller:books", books, false)
	if err != nil || len(individual) != 2 || individual[0].EntityType != "work" {
		t.Fatalf("individual grid: %+v, %v", individual, err)
	}
	collections, err := s.storytellerShelf("storyteller:books", books)
	if err != nil || len(collections) != 1 || collections[0].EntityType != "collection" {
		t.Fatalf("collections: %+v, %v", collections, err)
	}
}
