package api

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"ayaneohub/internal/adapters/kavita"
	"ayaneohub/internal/adapters/storyteller"
	"ayaneohub/internal/config"
)

func readingCatalogConfig(baseURL, catalogPath string, scopes []string) *config.Config {
	return &config.Config{
		Server: config.ServerConfig{ReadingCatalog: catalogPath},
		Auth: config.AuthConfig{
			Tokens:    []config.TokenConfig{{Label: "reader", Raw: config.Secret(libraryTestToken), Scopes: scopes}},
			RateLimit: config.RateLimitConfig{RPM: 600, Burst: 100}, AuthFailureBan: config.BanConfig{Attempts: 5},
		},
		Services: map[string]config.ServiceConfig{
			"kavita":      {Enabled: true, BaseURL: baseURL, APIKey: config.Secret("kavita-key")},
			"storyteller": {Enabled: true, BaseURL: baseURL, Username: "reader", Password: config.Secret("secret")},
		},
	}
}

func newReadingCatalogUpstream(t *testing.T) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v2/token":
			_, _ = io.WriteString(w, `{"access_token":"story-token","token_type":"Bearer","expires_in":3600}`)
			return
		case "/api/v2/books":
			if r.Header.Get("Authorization") != "Bearer story-token" {
				t.Fatal("Storyteller bearer token missing")
			}
			_, _ = io.WriteString(w, `[{"id":12,"uuid":"story-book","title":"Red Rising","description":"Darrow's story.","language":"en","authors":[{"name":"Pierce Brown"}],"ebook":{"uuid":"ebook-1","pageCount":400},"audiobook":{"uuid":"audio-1","duration":7200},"position":{"locator":{"locations":{"totalProgression":0.25}}}}]`)
			return
		case "/api/v2/books/12":
			if r.Header.Get("Authorization") != "Bearer story-token" {
				t.Fatal("Storyteller bearer token missing")
			}
			_, _ = io.WriteString(w, `{"id":12,"uuid":"story-book","title":"Red Rising","description":"Darrow's story.","language":"en","authors":[{"name":"Pierce Brown"}],"ebook":{"uuid":"ebook-1","pageCount":400},"audiobook":{"uuid":"audio-1","duration":7200},"position":{"locator":{"locations":{"totalProgression":0.25}}}}`)
			return
		case "/api/v2/books/12/cover":
			if r.Header.Get("Authorization") != "Bearer story-token" {
				t.Fatal("Storyteller bearer token missing")
			}
			w.Header().Set("Content-Type", "image/jpeg")
			_, _ = w.Write([]byte("story-cover"))
			return
		}

		if r.Header.Get("X-Api-Key") != "kavita-key" {
			t.Fatal("Kavita X-Api-Key missing")
		}
		switch r.URL.Path {
		case "/api/Library/libraries":
			_, _ = io.WriteString(w, `[{"id":2,"name":"Comics","type":1}]`)
		case "/api/Series/v2":
			w.Header().Set("Pagination", `{"currentPage":1,"itemsPerPage":60,"totalItems":1,"totalPages":1}`)
			_, _ = io.WriteString(w, `[{"id":9,"name":"Saga","sortName":"Saga","libraryId":2,"pages":12,"pagesRead":3,"format":1}]`)
		case "/api/Series/9":
			_, _ = io.WriteString(w, `{"id":9,"name":"Saga","sortName":"Saga","libraryId":2,"pages":12,"pagesRead":3,"format":1}`)
		case "/api/Series/metadata":
			_, _ = io.WriteString(w, `{"summary":"A space opera.","releaseYear":2012,"language":"en","writers":[{"name":"Brian K. Vaughan"}],"genres":[{"title":"Science Fiction"}]}`)
		case "/api/Series/volumes":
			_, _ = io.WriteString(w, `[{"id":5,"name":"1","number":1,"pages":12,"pagesRead":3,"chapters":[{"id":6,"title":"Issue 1","number":"1","pages":12,"pagesRead":3,"format":1}]}]`)
		case "/api/Reader/continue-point":
			_, _ = io.WriteString(w, `{"id":6,"title":"Issue 1","number":"1","pages":12,"pagesRead":3}`)
		case "/api/Image/series-cover":
			w.Header().Set("Content-Type", "image/png")
			_, _ = w.Write([]byte("kavita-cover"))
		default:
			http.NotFound(w, r)
		}
	}))
}

func TestReadingLibrariesCombineKavitaAndStoryteller(t *testing.T) {
	upstream := newReadingCatalogUpstream(t)
	defer upstream.Close()
	handler := NewServer(readingCatalogConfig(upstream.URL, filepath.Join(t.TempDir(), "catalog.json"), []string{"reading"})).Handler()

	got := libraryRequest(handler, "/v1/reading/libraries")
	if got.Code != http.StatusOK {
		t.Fatalf("status = %d: %s", got.Code, got.Body.String())
	}
	var body ReadingLibrariesResponse
	if err := json.Unmarshal(got.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if len(body.Libraries) != 2 || body.Libraries[0].ID != "kavita:2" || body.Libraries[0].Kind != "comic" || body.Libraries[1].ID != "storyteller:books" {
		t.Fatalf("libraries = %+v", body.Libraries)
	}
	if containsString(body.Libraries[0].Capabilities, "sort:author") || !containsString(body.Libraries[0].Capabilities, "sort:last_read") || !containsString(body.Libraries[1].Capabilities, "sort:author") {
		t.Fatalf("sort capabilities = %+v / %+v", body.Libraries[0].Capabilities, body.Libraries[1].Capabilities)
	}
	if len(body.Partial) != 0 {
		t.Fatalf("partial = %+v", body.Partial)
	}
}

func TestReadingLibrariesPreferStorytellerBooksAndKeepKavitaFallback(t *testing.T) {
	storyAvailable := true
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v2/token":
			_, _ = io.WriteString(w, `{"access_token":"story-token","token_type":"Bearer","expires_in":3600}`)
		case "/api/v2/books":
			if !storyAvailable {
				http.Error(w, "temporarily unavailable", http.StatusServiceUnavailable)
				return
			}
			_, _ = io.WriteString(w, `[{"id":1,"title":"Red Rising","ebook":{"uuid":"epub-1"}}]`)
		case "/api/Library/libraries":
			_, _ = io.WriteString(w, `[{"id":1,"name":"Books","type":2},{"id":2,"name":"Comics","type":1}]`)
		default:
			http.NotFound(w, r)
		}
	}))
	defer upstream.Close()

	configured := NewServer(readingCatalogConfig(upstream.URL, filepath.Join(t.TempDir(), "catalog.json"), []string{"reading"})).Handler()
	response := libraryRequest(configured, "/v1/reading/libraries")
	if response.Code != http.StatusOK {
		t.Fatalf("preferred libraries = %d: %s", response.Code, response.Body.String())
	}
	var preferred ReadingLibrariesResponse
	if err := json.Unmarshal(response.Body.Bytes(), &preferred); err != nil {
		t.Fatal(err)
	}
	if got := readingLibraryIDs(preferred.Libraries); strings.Join(got, ",") != "kavita:2,storyteller:books" {
		t.Fatalf("preferred library ids = %v", got)
	}

	storyAvailable = false
	fallback := NewServer(readingCatalogConfig(upstream.URL, filepath.Join(t.TempDir(), "fallback-catalog.json"), []string{"reading"})).Handler()
	response = libraryRequest(fallback, "/v1/reading/libraries")
	if response.Code != http.StatusOK {
		t.Fatalf("fallback libraries = %d: %s", response.Code, response.Body.String())
	}
	var degraded ReadingLibrariesResponse
	if err := json.Unmarshal(response.Body.Bytes(), &degraded); err != nil {
		t.Fatal(err)
	}
	if got := readingLibraryIDs(degraded.Libraries); strings.Join(got, ",") != "kavita:1,kavita:2" {
		t.Fatalf("fallback library ids = %v", got)
	}
	if len(degraded.Partial) != 1 || degraded.Partial[0].Service != "storyteller" {
		t.Fatalf("fallback partial = %+v", degraded.Partial)
	}
}

func readingLibraryIDs(libraries []ReadingLibrary) []string {
	ids := make([]string, 0, len(libraries))
	for _, library := range libraries {
		ids = append(ids, library.ID)
	}
	return ids
}

func TestReadingLibraryItemsAndDetailsUseStableHubWorkIDs(t *testing.T) {
	upstream := newReadingCatalogUpstream(t)
	defer upstream.Close()
	catalogPath := filepath.Join(t.TempDir(), "catalog.json")
	handler := NewServer(readingCatalogConfig(upstream.URL, catalogPath, []string{"reading"})).Handler()

	page := libraryRequest(handler, "/v1/reading/libraries/kavita:2/items?page=1&sort=title&direction=asc")
	if page.Code != http.StatusOK {
		t.Fatalf("page status = %d: %s", page.Code, page.Body.String())
	}
	var kavitaPage ReadingLibraryItemsResponse
	if err := json.Unmarshal(page.Body.Bytes(), &kavitaPage); err != nil {
		t.Fatal(err)
	}
	if kavitaPage.Page != 1 || kavitaPage.Total != 1 || kavitaPage.HasMore || len(kavitaPage.Items) != 1 {
		t.Fatalf("page = %+v", kavitaPage)
	}
	comic := kavitaPage.Items[0]
	if !strings.HasPrefix(comic.ID, "rw_") || comic.LibraryID != "kavita:2" || comic.Kind != "comic" || comic.Progress == nil || comic.Progress.Percentage != 0.25 {
		t.Fatalf("comic = %+v", comic)
	}
	if comic.Artwork != "/v1/img/reading/kavita/9" {
		t.Fatalf("comic artwork = %q", comic.Artwork)
	}

	detailResponse := libraryRequest(handler, "/v1/reading/works/"+comic.ID)
	if detailResponse.Code != http.StatusOK {
		t.Fatalf("detail status = %d: %s", detailResponse.Code, detailResponse.Body.String())
	}
	var detail ReadingWork
	if err := json.Unmarshal(detailResponse.Body.Bytes(), &detail); err != nil {
		t.Fatal(err)
	}
	if detail.ID != comic.ID || detail.Overview != "A space opera." || len(detail.Authors) != 1 || len(detail.Editions) != 1 || detail.Editions[0].Kind != "comic" {
		t.Fatalf("detail = %+v", detail)
	}
	if len(detail.Sections) != 1 || len(detail.Sections[0].Items) != 1 || detail.Continue == nil || detail.Continue.SourceItemID != "6" {
		t.Fatalf("detail hierarchy = %+v / continue %+v", detail.Sections, detail.Continue)
	}

	restarted := NewServer(readingCatalogConfig(upstream.URL, catalogPath, []string{"reading"})).Handler()
	restored := libraryRequest(restarted, "/v1/reading/works/"+comic.ID)
	if restored.Code != http.StatusOK {
		t.Fatalf("persisted work id status = %d: %s", restored.Code, restored.Body.String())
	}
}

func TestStorytellerLibraryMapsEditionsAndProgress(t *testing.T) {
	upstream := newReadingCatalogUpstream(t)
	defer upstream.Close()
	handler := NewServer(readingCatalogConfig(upstream.URL, filepath.Join(t.TempDir(), "catalog.json"), []string{"reading"})).Handler()
	page := libraryRequest(handler, "/v1/reading/libraries/storyteller:books/items?page=1&sort=title&direction=asc")
	if page.Code != http.StatusOK {
		t.Fatalf("page status = %d: %s", page.Code, page.Body.String())
	}
	var body ReadingLibraryItemsResponse
	if err := json.Unmarshal(page.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if len(body.Items) != 1 || len(body.Items[0].Availability) != 2 || body.Items[0].Progress == nil || body.Items[0].Progress.Percentage != 0.25 {
		t.Fatalf("items = %+v", body.Items)
	}
	detailResponse := libraryRequest(handler, "/v1/reading/works/"+body.Items[0].ID)
	if detailResponse.Code != http.StatusOK {
		t.Fatalf("detail status = %d: %s", detailResponse.Code, detailResponse.Body.String())
	}
	var detail ReadingWork
	if err := json.Unmarshal(detailResponse.Body.Bytes(), &detail); err != nil {
		t.Fatal(err)
	}
	if len(detail.Editions) != 2 || detail.Editions[0].Source != "storyteller" || detail.Authors[0] != "Pierce Brown" {
		t.Fatalf("detail = %+v", detail)
	}
}

func TestStorytellerLibraryGroupsSeriesAndOpensOrderedBooks(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v2/token":
			_, _ = io.WriteString(w, `{"access_token":"story-token","token_type":"Bearer","expires_in":3600}`)
		case "/api/v2/books":
			_, _ = io.WriteString(w, `[
				{"id":1,"uuid":"rr-1","title":"Red Rising","authors":[{"name":"Pierce Brown"}],"series":[{"name":"Red Rising","position":1}],"ebook":{"uuid":"epub-1","pageCount":400},"position":{"locator":{"locations":{"totalProgression":0.5}},"updatedAt":"2026-08-01T12:00:00Z"}},
				{"id":2,"uuid":"rr-2","title":"Golden Son","authors":[{"name":"Pierce Brown"}],"series":[{"name":"Red Rising","position":2}],"ebook":{"uuid":"epub-2","pageCount":430},"position":{"locator":{"locations":{"totalProgression":0.2}},"updatedAt":"2026-09-20T12:00:00Z"}},
				{"id":3,"uuid":"standalone","title":"The Left Hand of Darkness","authors":[{"name":"Ursula K. Le Guin"}],"ebook":{"uuid":"epub-3","pageCount":300}}
			]`)
		case "/api/v2/books/1":
			_, _ = io.WriteString(w, `{"id":1,"uuid":"rr-1","title":"Red Rising","authors":[{"name":"Pierce Brown"}],"series":[{"name":"Red Rising","position":1}],"ebook":{"uuid":"epub-1","pageCount":400},"position":{"locator":{"locations":{"totalProgression":0.5}},"updatedAt":"2026-08-01T12:00:00Z"}}`)
		case "/api/v2/books/2":
			_, _ = io.WriteString(w, `{"id":2,"uuid":"rr-2","title":"Golden Son","authors":[{"name":"Pierce Brown"}],"series":[{"name":"Red Rising","position":2}],"ebook":{"uuid":"epub-2","pageCount":430},"position":{"locator":{"locations":{"totalProgression":0.2}},"updatedAt":"2026-09-20T12:00:00Z"}}`)
		default:
			http.NotFound(w, r)
		}
	}))
	defer upstream.Close()

	cfg := readingCatalogConfig(upstream.URL, filepath.Join(t.TempDir(), "catalog.json"), []string{"reading"})
	delete(cfg.Services, "kavita")
	handler := NewServer(cfg).Handler()

	page := libraryRequest(handler, "/v1/reading/libraries/storyteller:books/items?page=1&sort=series&direction=asc")
	if page.Code != http.StatusOK {
		t.Fatalf("series page = %d: %s", page.Code, page.Body.String())
	}
	var body ReadingLibraryItemsResponse
	if err := json.Unmarshal(page.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if body.Total != 2 || len(body.Items) != 2 {
		t.Fatalf("grouped page = %+v", body)
	}
	var collection ReadingWork
	for _, item := range body.Items {
		if item.EntityType == "collection" {
			collection = item
		}
	}
	if collection.ID == "" || collection.Title != "Red Rising" || collection.BookCount != 2 || collection.Authors[0] != "Pierce Brown" {
		t.Fatalf("collection = %+v", collection)
	}

	lastRead := libraryRequest(handler, "/v1/reading/libraries/storyteller:books/items?page=1&sort=last_read&direction=desc")
	if lastRead.Code != http.StatusOK {
		t.Fatalf("last-read page = %d: %s", lastRead.Code, lastRead.Body.String())
	}
	var recent ReadingLibraryItemsResponse
	if err := json.Unmarshal(lastRead.Body.Bytes(), &recent); err != nil {
		t.Fatal(err)
	}
	if len(recent.Items) == 0 || recent.Items[0].ID != collection.ID {
		t.Fatalf("last-read order = %+v", recent.Items)
	}

	detailResponse := libraryRequest(handler, "/v1/reading/works/"+collection.ID)
	if detailResponse.Code != http.StatusOK {
		t.Fatalf("collection detail = %d: %s", detailResponse.Code, detailResponse.Body.String())
	}
	var detail ReadingWork
	if err := json.Unmarshal(detailResponse.Body.Bytes(), &detail); err != nil {
		t.Fatal(err)
	}
	if detail.EntityType != "collection" || len(detail.Sections) != 1 || len(detail.Sections[0].Items) != 2 {
		t.Fatalf("collection detail = %+v", detail)
	}
	books := detail.Sections[0].Items
	if books[0].Title != "Red Rising" || books[1].Title != "Golden Son" || books[0].WorkID == "" || books[1].WorkID == "" || books[0].WorkID == books[1].WorkID {
		t.Fatalf("ordered books = %+v", books)
	}
}

func TestStorytellerShelfRepairsMissingSeriesFromAcquisitionManifest(t *testing.T) {
	temporary := t.TempDir()
	cfg := readingCatalogConfig("http://127.0.0.1:1", filepath.Join(temporary, "catalog.json"), []string{"reading"})
	cfg.Server.ReadingTransfers = filepath.Join(temporary, "reading-transfers.json")
	server := NewServer(cfg)
	preview := ReadingSeriesPreview{
		SeriesID: "OL100L", Name: "Red Rising Saga", Author: "Pierce Brown",
		Books: []ReadingSeriesPreviewBook{
			{ID: "OL1W", Title: "Red Rising", Author: "Pierce Brown", ISBN: "9780345539786", Position: 1},
			{ID: "OL2W", Title: "Golden Son", Author: "Pierce Brown", ISBN: "9780345539816", Position: 2},
		},
	}
	manifest, err := server.readingAcquisitions.begin("reading:key", preview, preview.Books, 7, "all")
	if err != nil {
		t.Fatal(err)
	}
	for index, book := range preview.Books {
		if err := server.readingAcquisitions.setBook(manifest.ID, book.ID, 20+index, "accepted", ""); err != nil {
			t.Fatal(err)
		}
	}
	items, err := server.storytellerShelf("storyteller:books", []storyteller.Book{
		{ID: 1, Title: "Red Rising", Authors: []storyteller.Creator{{Name: "Pierce Brown"}}, Identifiers: []storyteller.Identifier{{Type: "ISBN", Value: "9780345539786"}}, Ebook: &storyteller.Ebook{UUID: "one"}},
		{ID: 2, Title: "Golden Son", Authors: []storyteller.Creator{{Name: "Pierce Brown"}}, Identifiers: []storyteller.Identifier{{Type: "ISBN", Value: "9780345539816"}}, Ebook: &storyteller.Ebook{UUID: "two"}},
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(items) != 1 || items[0].EntityType != "collection" || items[0].Title != "Red Rising Saga" || items[0].BookCount != 2 {
		t.Fatalf("items = %+v", items)
	}
}

func TestStorytellerShelfKeepsRequestedCollectionWhenEpubAddsSeriesPrefix(t *testing.T) {
	temporary := t.TempDir()
	cfg := readingCatalogConfig("http://127.0.0.1:1", filepath.Join(temporary, "catalog.json"), []string{"reading"})
	cfg.Server.ReadingTransfers = filepath.Join(temporary, "reading-transfers.json")
	server := NewServer(cfg)
	preview := ReadingSeriesPreview{
		SeriesID: "subject:mistborn-original-trilogy", Name: "Mistborn Original Trilogy", Author: "Brandon Sanderson",
		Books: []ReadingSeriesPreviewBook{
			{ID: "OL1W", Title: "The Final Empire", Author: "Brandon Sanderson", Position: 1},
			{ID: "OL3W", Title: "The Hero of Ages", Author: "Brandon Sanderson", Position: 3},
		},
	}
	manifest, err := server.readingAcquisitions.begin("reading:key", preview, preview.Books, 7, "all")
	if err != nil {
		t.Fatal(err)
	}
	for index, book := range preview.Books {
		if err := server.readingAcquisitions.setBook(manifest.ID, book.ID, 30+index, "accepted", ""); err != nil {
			t.Fatal(err)
		}
	}
	rawSeries := []storyteller.Series{{UUID: "epub-series", Name: "Mistborn Trilogy"}}
	items, err := server.storytellerShelf("storyteller:books", []storyteller.Book{
		{ID: 1, Title: "Mistborn: The Final Empire", Authors: []storyteller.Creator{{Name: "Brandon Sanderson"}}, Series: rawSeries, Ebook: &storyteller.Ebook{UUID: "one"}},
		{ID: 2, Title: "The Hero of Ages", Authors: []storyteller.Creator{{Name: "Brandon Sanderson"}}, Series: rawSeries, Ebook: &storyteller.Ebook{UUID: "two"}},
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(items) != 1 || items[0].EntityType != "collection" || items[0].Title != preview.Name || items[0].BookCount != 2 {
		t.Fatalf("items = %+v", items)
	}
}

func TestStorytellerCollectionIncludesMissingManifestBooksAndContinueArtwork(t *testing.T) {
	temporary := t.TempDir()
	cfg := readingCatalogConfig("http://127.0.0.1:1", filepath.Join(temporary, "catalog.json"), []string{"reading"})
	cfg.Server.ReadingTransfers = filepath.Join(temporary, "reading-transfers.json")
	server := NewServer(cfg)
	fullRoster := []ReadingSeriesPreviewBook{
		{ID: "OL1W", Title: "Red Rising", Author: "Pierce Brown", Position: 1, CoverURL: "https://covers.example/one.jpg"},
		{ID: "OL2W", Title: "Golden Son", Author: "Pierce Brown", Position: 2, CoverURL: "https://covers.example/two.jpg"},
		{ID: "OL3W", Title: "Morning Star", Author: "Pierce Brown", Position: 3, CoverURL: "https://covers.example/three.jpg"},
	}
	preview := ReadingSeriesPreview{
		SeriesID: "OL100L", Name: "Red Rising Saga", Author: "Pierce Brown",
		Description: "Humanity reached the stars.", Books: fullRoster[:2], FullBooks: fullRoster,
	}
	manifest, err := server.readingAcquisitions.begin("reading:key", preview, preview.Books, 7, "all")
	if err != nil {
		t.Fatal(err)
	}
	if err := server.readingAcquisitions.setBook(manifest.ID, "OL1W", 20, "accepted", ""); err != nil {
		t.Fatal(err)
	}
	available := server.reconcileStorytellerBook(storyteller.Book{
		ID: 1, Title: "Red Rising", Authors: []storyteller.Creator{{Name: "Pierce Brown"}},
		Identifiers: []storyteller.Identifier{{Type: "ISBN", Value: ""}}, Ebook: &storyteller.Ebook{UUID: "one"},
		Position: &storyteller.Position{Timestamp: 10, Locator: storyteller.Locator{Locations: storyteller.Locations{TotalProgression: .25}}},
	})
	sourceID, title, grouped := storytellerSeriesSource(available)
	if !grouped {
		t.Fatal("available book was not reconciled into its requested series")
	}
	detail, err := server.storytellerCollection("storyteller:books", &storytellerSeriesGroup{
		sourceID: sourceID, title: title, books: []storyteller.Book{available},
	}, true)
	if err != nil {
		t.Fatal(err)
	}
	if detail.Overview != preview.Description || detail.BookCount != 1 || len(detail.Sections) != 1 || len(detail.Sections[0].Items) != 3 {
		t.Fatalf("detail = %+v", detail)
	}
	items := detail.Sections[0].Items
	if items[0].Availability != "available" || items[0].WorkID == "" || items[0].Artwork == "" {
		t.Fatalf("available item = %+v", items[0])
	}
	if items[1].Availability != "missing" || items[1].WorkID != "" || items[1].Artwork == "" || items[2].Availability != "missing" {
		t.Fatalf("missing items = %+v", items[1:])
	}
	if detail.Continue == nil || detail.Continue.WorkID != items[0].WorkID || detail.Continue.Artwork != items[0].Artwork {
		t.Fatalf("continue = %+v, item = %+v", detail.Continue, items[0])
	}
}

func TestStorytellerCollectionFallsBackToCleanFirstBookDescription(t *testing.T) {
	server := NewServer(readingCatalogConfig("http://127.0.0.1:1", filepath.Join(t.TempDir(), "catalog.json"), []string{"reading"}))
	detail, err := server.storytellerCollection("storyteller:books", &storytellerSeriesGroup{
		sourceID: "series-one", title: "Red Rising", books: []storyteller.Book{{
			ID: 1, Title: "Red Rising", Description: `<div><p><strong>NEW YORK TIMES</strong></p><p>Darrow &amp; his people.<br>Rise.</p></div>`,
			Authors: []storyteller.Creator{{Name: "Pierce Brown"}},
			Series:  []storyteller.Series{{Name: "Red Rising", Position: 1}}, Ebook: &storyteller.Ebook{UUID: "one"},
		}},
	}, true)
	if err != nil {
		t.Fatal(err)
	}
	if detail.Overview != "NEW YORK TIMES\n\nDarrow & his people.\nRise." {
		t.Fatalf("overview = %q", detail.Overview)
	}
}

func TestReadingDescriptionTextRemovesMarkupAndKeepsParagraphs(t *testing.T) {
	got := readingDescriptionText(`<p><em>Hello</em> &amp; goodbye.</p><p>Next&nbsp;line<br/>Now</p>`)
	if got != "Hello & goodbye.\n\nNext line\nNow" {
		t.Fatalf("description = %q", got)
	}
}

func TestStorytellerAuthorSortUsesFirstAuthorThenSeriesPosition(t *testing.T) {
	books := []storyteller.Book{
		{ID: 3, Title: "B", Authors: []storyteller.Creator{{Name: "Zed"}}},
		{ID: 2, Title: "Second", Authors: []storyteller.Creator{{Name: "Amy"}}, Series: []storyteller.Series{{Name: "Saga", Position: 2}}},
		{ID: 1, Title: "First", Authors: []storyteller.Creator{{Name: "Amy"}}, Series: []storyteller.Series{{Name: "Saga", Position: 1}}},
	}

	sortStorytellerBooks(books, "author", "asc")
	got := []int64{books[0].ID, books[1].ID, books[2].ID}
	want := []int64{1, 2, 3}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("author order = %v, want %v", got, want)
		}
	}
}

func TestCreatorNamesCollapsesDisplayAndFileAsDuplicates(t *testing.T) {
	got := creatorNames([]storyteller.Creator{
		{Name: "Pierce Brown"},
		{Name: "Brown, Pierce"},
	})
	if len(got) != 1 || got[0] != "Pierce Brown" {
		t.Fatalf("creator names = %v", got)
	}
}

func TestReadingCatalogImagesProxyAuthenticatedSources(t *testing.T) {
	upstream := newReadingCatalogUpstream(t)
	defer upstream.Close()
	handler := NewServer(readingCatalogConfig(upstream.URL, filepath.Join(t.TempDir(), "catalog.json"), []string{"reading"})).Handler()
	for path, expected := range map[string][2]string{
		"/v1/img/reading/kavita/9":       {"image/png", "kavita-cover"},
		"/v1/img/reading/storyteller/12": {"image/jpeg", "story-cover"},
	} {
		got := libraryRequest(handler, path)
		if got.Code != http.StatusOK || got.Header().Get("Content-Type") != expected[0] || got.Body.String() != expected[1] {
			t.Fatalf("%s = %d %q %q", path, got.Code, got.Header().Get("Content-Type"), got.Body.String())
		}
	}
}

func TestKavitaUnnumberedChapterSentinelIsNotExposed(t *testing.T) {
	for input, want := range map[string]string{
		"-100000": "",
		"100000":  "",
		" 1 ":     "1",
		"Special": "Special",
	} {
		if got := kavitaChapterNumber(input); got != want {
			t.Errorf("kavitaChapterNumber(%q) = %q, want %q", input, got, want)
		}
	}
	if got := kavitaChapterTitle("-100000", "-100000", "Volume 1"); got != "Volume 1" {
		t.Fatalf("sentinel title = %q", got)
	}
	if got := kavitaChapterTitle("", "4", "Volume 1"); got != "Chapter 4" {
		t.Fatalf("numbered title = %q", got)
	}
}

func TestKavitaContinueTitleResolvesSentinelFromOwningVolume(t *testing.T) {
	chapter := kavita.Chapter{ID: 42, Title: "-100000", Number: "-100000"}
	volumes := []kavita.Volume{{
		ID: 7, Name: "1", Chapters: []kavita.Chapter{chapter},
	}}
	if got := kavitaContinueTitle(chapter, volumes, "Chainsaw Man"); got != "Volume 1" {
		t.Fatalf("continue title = %q", got)
	}
	if got := kavitaContinueTitle(kavita.Chapter{ID: 99, Title: "-100000", Number: "-100000"}, volumes, "Chainsaw Man"); got != "Chainsaw Man" {
		t.Fatalf("unmatched continue title = %q", got)
	}
}

func TestReadingCatalogRoutesRequireScopeAndValidateIDs(t *testing.T) {
	upstream := newReadingCatalogUpstream(t)
	defer upstream.Close()
	withoutScope := NewServer(readingCatalogConfig(upstream.URL, "", []string{"read"})).Handler()
	if got := libraryRequest(withoutScope, "/v1/reading/libraries"); got.Code != http.StatusForbidden {
		t.Fatalf("without scope = %d", got.Code)
	}
	withScope := NewServer(readingCatalogConfig(upstream.URL, "", []string{"reading"})).Handler()
	for _, path := range []string{
		"/v1/reading/libraries/kavita:nope/items?page=1&sort=title&direction=asc",
		"/v1/reading/libraries/kavita:2/items?page=0&sort=title&direction=asc",
		"/v1/reading/libraries/kavita:2/items?page=1&sort=author&direction=asc",
		"/v1/reading/libraries/storyteller:books/items?page=1&sort=nope&direction=asc",
		"/v1/reading/works/not-a-work-id",
		"/v1/img/reading/kavita/not-a-number",
	} {
		if got := libraryRequest(withScope, path); got.Code != http.StatusBadRequest {
			t.Errorf("%s = %d, want 400: %s", path, got.Code, got.Body.String())
		}
	}
}

func TestReadingCatalogReturnsNotFoundForMissingKavitaLibrary(t *testing.T) {
	upstream := newReadingCatalogUpstream(t)
	defer upstream.Close()
	handler := NewServer(readingCatalogConfig(upstream.URL, filepath.Join(t.TempDir(), "catalog.json"), []string{"reading"})).Handler()

	got := libraryRequest(handler, "/v1/reading/libraries/kavita:999/items?page=1&sort=title&direction=asc")
	if got.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404: %s", got.Code, got.Body.String())
	}
}

func TestSortStorytellerBooksIsDeterministicForEqualValues(t *testing.T) {
	books := []storyteller.Book{
		{ID: 2, Title: "Same", CreatedAt: "2026-01-01"},
		{ID: 1, Title: "Same", CreatedAt: "2026-01-01"},
		{ID: 3, Title: "Later", CreatedAt: "2026-02-01"},
	}

	sortStorytellerBooks(books, "added", "desc")
	got := []int64{books[0].ID, books[1].ID, books[2].ID}
	want := []int64{3, 1, 2}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("order = %v, want %v", got, want)
		}
	}
	sortStorytellerBooks(books, "added", "desc")
	gotAgain := []int64{books[0].ID, books[1].ID, books[2].ID}
	for i := range want {
		if gotAgain[i] != want[i] {
			t.Fatalf("second order = %v, want stable %v", gotAgain, want)
		}
	}
}

func containsString(values []string, want string) bool {
	for _, value := range values {
		if value == want {
			return true
		}
	}
	return false
}
