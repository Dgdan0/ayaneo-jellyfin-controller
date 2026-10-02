package api

import (
	"ayaneohub/internal/adapters/openlibrary"
	"ayaneohub/internal/adapters/storyteller"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync/atomic"
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
	// Open Library knows Pierce Brown through one of his books here, and nobody else.
	var lookups atomic.Int32
	ol := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		lookups.Add(1)
		if r.URL.Path == "/search.json" && r.URL.Query().Get("author") == "Pierce Brown" {
			fmt.Fprint(w, `{"docs":[{"author_key":["OL7621609A"],"author_name":["Pierce Brown"]}]}`)
			return
		}
		fmt.Fprint(w, `{"docs":[]}`)
	}))
	defer ol.Close()
	server := NewServer(readingCatalogConfig(upstream.URL, filepath.Join(t.TempDir(), "catalog.json"), []string{"reading"}))
	server.openlibrary = openlibrary.New(ol.URL)
	handler := server.Handler()
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
	if len(out.Authors) != 3 || out.Authors[0].Name != "Brandon Sanderson" || out.Authors[2].Name != "Unknown author" {
		t.Fatalf("groups: %+v", out.Authors)
	}
	// A writer without a requested series still gets a portrait, found through their own book.
	if !strings.HasPrefix(out.Authors[1].Artwork, "/v1/img/reading/") || out.Authors[0].Artwork != "" || out.Authors[2].Artwork != "" {
		t.Fatalf("portraits: %q %q %q", out.Authors[0].Artwork, out.Authors[1].Artwork, out.Authors[2].Artwork)
	}
	before := lookups.Load()
	get(root)
	if lookups.Load() != before {
		t.Fatalf("portraits were looked up again: %d -> %d", before, lookups.Load())
	}
	// An author's shelf is their series, then their books outside a series.
	group := out.Authors[1]
	if group.Name != "Pierce Brown" || group.Total != 2 || group.SeriesCount != 1 || group.BookCount != 76 ||
		group.Items[0].EntityType != "collection" || group.Items[0].Title != "Saga" || group.Items[0].BookCount != 75 ||
		group.Items[1].Title != "Shared" {
		t.Fatalf("Pierce Brown shelf: %+v", group)
	}
	// One author asked for: each series carries its books, in numeric order.
	detail := get(fmt.Sprintf("%s?authorId=%s", root, group.ID)).Authors[0]
	saga := detail.Items[0]
	if len(saga.Sections) != 1 || len(saga.Sections[0].Items) != 75 ||
		saga.Sections[0].Items[1].Number != "2" || saga.Sections[0].Items[9].Number != "10" {
		t.Fatalf("series books: %+v", saga.Sections)
	}
	descending := get(root + "?direction=desc")
	if descending.Authors[0].Name != "Pierce Brown" || descending.Authors[0].Items[0].Title != "Saga" || descending.Authors[2].Name != "Unknown author" {
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
	// Open Library knows Pierce Brown through one of his books here, and nobody else.
	lookups := 0
	ol := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		lookups++
		if r.URL.Path == "/search.json" && r.URL.Query().Get("author") == "Pierce Brown" {
			fmt.Fprint(w, `{"docs":[{"author_key":["OL7621609A"],"author_name":["Pierce Brown"]}]}`)
			return
		}
		fmt.Fprint(w, `{"docs":[]}`)
	}))
	defer ol.Close()
	server := NewServer(readingCatalogConfig(upstream.URL, filepath.Join(t.TempDir(), "catalog.json"), []string{"reading"}))
	server.openlibrary = openlibrary.New(ol.URL)
	handler := server.Handler()
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

// A book's detail links to its series page and its author's page; both ids
// must be the ones those pages are served under.
func TestBookDetailLinksToItsSeriesAndAuthorPages(t *testing.T) {
	s := NewServer(readingCatalogConfig("http://127.0.0.1", "", []string{"reading"}))
	brown := []storyteller.Creator{{Name: "Brown, Pierce"}}
	books := []storyteller.Book{
		{ID: 1, Title: "Red Rising", Authors: brown, Series: []storyteller.Series{{Name: "Red Rising", Position: 1}}},
		{ID: 6, Title: "Light Bringer", Authors: brown, Series: []storyteller.Series{{Name: "Red Rising", Position: 6}}},
		{ID: 9, Title: "Dark Matter", Authors: []storyteller.Creator{{Name: "Blake Crouch"}}},
	}
	shelf, err := s.storytellerShelf("storyteller:books", books)
	if err != nil {
		t.Fatal(err)
	}
	seriesPage := ""
	for _, work := range shelf {
		if work.EntityType == "collection" {
			seriesPage = work.ID
		}
	}
	detail, err := s.storytellerWork("storyteller:books", books[1], true)
	if err != nil || seriesPage == "" || detail.SeriesID != seriesPage {
		t.Fatalf("seriesId = %q, series page %q, %v", detail.SeriesID, seriesPage, err)
	}
	if len(detail.AuthorRefs) != 1 || detail.AuthorRefs[0] != readingAuthorRef("Pierce Brown") {
		t.Fatalf("authorRefs = %+v", detail.AuthorRefs)
	}
	standalone, err := s.storytellerWork("storyteller:books", books[2], true)
	if err != nil || standalone.SeriesID != "" {
		t.Fatalf("standalone seriesId = %q, %v", standalone.SeriesID, err)
	}
	// The list shelf stays lean: links are for a book's own page.
	listed, _ := s.storytellerWork("storyteller:books", books[1], false)
	if listed.SeriesID != "" || listed.AuthorRefs != nil {
		t.Fatalf("listed work carries links: %+v", listed)
	}
}
