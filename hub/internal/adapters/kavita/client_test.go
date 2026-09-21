package kavita

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"

	"ayaneohub/internal/config"
)

func TestCatalogUsesAPIKeyBodyFilterSortAndPagination(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Api-Key") != "kavita-key" {
			t.Fatal("missing Kavita X-Api-Key")
		}
		switch r.URL.Path {
		case "/api/Library/libraries":
			_, _ = io.WriteString(w, `[{"id":2,"name":"Comics","type":1}]`)
		case "/api/Series/v2":
			if r.Method != http.MethodPost || r.URL.Query().Get("PageNumber") != "2" || r.URL.Query().Get("PageSize") != "60" {
				t.Fatalf("series request = %s %s", r.Method, r.URL.String())
			}
			var body SeriesFilter
			if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
				t.Fatal(err)
			}
			if len(body.Statements) != 1 || body.Statements[0].Field != 19 || body.Statements[0].Comparison != 0 || body.Statements[0].Value != "2" {
				t.Fatalf("library filter = %+v", body)
			}
			if body.SortOptions == nil || body.SortOptions.SortField != 1 || body.SortOptions.IsAscending {
				t.Fatalf("sort = %+v", body.SortOptions)
			}
			w.Header().Set("Pagination", `{"currentPage":2,"itemsPerPage":60,"totalItems":61,"totalPages":2}`)
			_, _ = io.WriteString(w, `[{"id":9,"name":"Saga","sortName":"Saga","libraryId":2,"pages":12,"pagesRead":3,"format":1}]`)
		default:
			http.NotFound(w, r)
		}
	}))
	defer upstream.Close()

	client, err := New(config.ServiceConfig{BaseURL: upstream.URL, APIKey: config.Secret("kavita-key")})
	if err != nil {
		t.Fatal(err)
	}
	libraries, err := client.Libraries(context.Background())
	if err != nil || len(libraries) != 1 || libraries[0].Name != "Comics" {
		t.Fatalf("Libraries() = %+v, %v", libraries, err)
	}
	page, err := client.Series(context.Background(), 2, 2, 60, SortTitle, Descending)
	if err != nil {
		t.Fatal(err)
	}
	if page.Page != 2 || page.Total != 61 || page.TotalPages != 2 || len(page.Items) != 1 || page.Items[0].Name != "Saga" {
		t.Fatalf("Series() = %+v", page)
	}
}

func TestReadingSortAliasesMatchKavitaSeriesAndLastReadFields(t *testing.T) {
	for input, want := range map[Sort]int{
		SortTitle: 1, SortSeries: 1, SortAdded: 2, SortProgress: 7, SortLastRead: 7,
	} {
		got, err := sortField(input)
		if err != nil || got != want {
			t.Errorf("sortField(%q) = %d, %v; want %d", input, got, err, want)
		}
	}
}

func TestDetailVolumesAndCoverStayAuthenticated(t *testing.T) {
	requests := 0
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests++
		if r.Header.Get("X-Api-Key") != "kavita-key" {
			t.Fatal("missing Kavita X-Api-Key")
		}
		switch r.URL.Path {
		case "/api/Series/4":
			_, _ = io.WriteString(w, `{"id":4,"name":"Lab Manga","libraryId":3,"pages":2,"pagesRead":1,"format":1}`)
		case "/api/Series/metadata":
			_, _ = io.WriteString(w, `{"summary":"A journey.","releaseYear":2024,"language":"en","writers":[{"name":"Lab Author"}],"genres":[{"title":"Adventure"}]}`)
		case "/api/Series/volumes":
			_, _ = io.WriteString(w, `[{"id":4,"name":"1","number":1,"pages":2,"pagesRead":1,"chapters":[{"id":7,"title":"One","number":"1","pages":2,"pagesRead":1,"format":1,"isbn":"9780000000002"}]}]`)
		case "/api/Reader/continue-point":
			_, _ = io.WriteString(w, `{"id":7,"title":"One","number":"1","pages":2,"pagesRead":1}`)
		case "/api/Image/series-cover":
			w.Header().Set("Content-Type", "image/png")
			_, _ = w.Write([]byte("png"))
		default:
			http.NotFound(w, r)
		}
	}))
	defer upstream.Close()
	client, err := New(config.ServiceConfig{BaseURL: upstream.URL, APIKey: config.Secret("kavita-key")})
	if err != nil {
		t.Fatal(err)
	}
	detail, err := client.Detail(context.Background(), 4)
	if err != nil || detail.Series.Name != "Lab Manga" || detail.Metadata.Writers[0].Name != "Lab Author" || detail.Continue.ID != 7 || len(detail.Volumes) != 1 {
		t.Fatalf("Detail() = %+v, %v", detail, err)
	}
	body, contentType, err := client.Cover(context.Background(), 4)
	if err != nil || string(body) != "png" || contentType != "image/png" {
		t.Fatalf("Cover() = %q, %q, %v", body, contentType, err)
	}
	if requests != 5 {
		t.Fatalf("requests = %d", requests)
	}
}

func TestScanAllUsesAuthenticatedInstalledRoute(t *testing.T) {
	calls := 0
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		if r.URL.Path != "/api/Library/scan-all" || r.Method != http.MethodPost {
			t.Fatalf("scan request = %s %s", r.Method, r.URL.Path)
		}
		if r.Header.Get("X-Api-Key") != "kavita-key" {
			t.Fatalf("scan auth = %q", r.Header.Get("X-Api-Key"))
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer upstream.Close()
	client, err := New(config.ServiceConfig{BaseURL: upstream.URL, APIKey: config.Secret("kavita-key")})
	if err != nil {
		t.Fatal(err)
	}
	if err := client.ScanAll(context.Background()); err != nil {
		t.Fatal(err)
	}
	if calls != 1 {
		t.Fatalf("scan calls = %d", calls)
	}
}

func TestReaderManifestPageAndProgressStayAuthenticated(t *testing.T) {
	var saved Progress
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Api-Key") != "kavita-key" {
			t.Fatalf("reader auth = %q", r.Header.Get("X-Api-Key"))
		}
		switch r.URL.Path {
		case "/api/Reader/chapter-info":
			if r.Method != http.MethodGet || r.URL.Query().Get("chapterId") != "7" ||
				r.URL.Query().Get("extractPdf") != "false" || r.URL.Query().Get("includeDimensions") != "true" {
				t.Fatalf("chapter info request = %s %s", r.Method, r.URL.String())
			}
			_, _ = io.WriteString(w, `{"chapterNumber":"1","volumeNumber":"1","volumeId":4,"seriesName":"Lab Manga","seriesId":9,"libraryId":3,"libraryType":0,"chapterTitle":"Awakening","pages":3,"pageDimensions":[{"width":1200,"height":1800,"pageNumber":0,"fileName":"000.jpg","isWide":false},{"width":2400,"height":1600,"pageNumber":1,"fileName":"001.jpg","isWide":true}],"doublePairs":{"1":2}}`)
		case "/api/Reader/get-progress":
			if r.URL.Query().Get("chapterId") != "7" {
				t.Fatalf("progress query = %s", r.URL.RawQuery)
			}
			_, _ = io.WriteString(w, `{"volumeId":4,"chapterId":7,"pageNum":1,"seriesId":9,"libraryId":3,"bookScrollId":""}`)
		case "/api/Reader/image":
			if r.Method != http.MethodGet || r.URL.Query().Get("chapterId") != "7" || r.URL.Query().Get("page") != "2" || r.URL.Query().Get("apiKey") != "kavita-key" {
				t.Fatalf("page request = %s %s", r.Method, r.URL.String())
			}
			w.Header().Set("Content-Type", "image/jpeg")
			w.Header().Set("Cache-Control", "private, max-age=120")
			_, _ = w.Write([]byte("page-two"))
		case "/api/Reader/progress":
			if r.Method != http.MethodPost {
				t.Fatalf("progress method = %s", r.Method)
			}
			if err := json.NewDecoder(r.Body).Decode(&saved); err != nil {
				t.Fatal(err)
			}
			w.WriteHeader(http.StatusNoContent)
		default:
			http.NotFound(w, r)
		}
	}))
	defer upstream.Close()

	client, err := New(config.ServiceConfig{BaseURL: upstream.URL, APIKey: config.Secret("kavita-key")})
	if err != nil {
		t.Fatal(err)
	}
	info, err := client.ChapterInfo(context.Background(), 7)
	if err != nil || info.SeriesID != 9 || info.Pages != 3 || len(info.PageDimensions) != 2 || !info.PageDimensions[1].IsWide || info.DoublePairs["1"] != 2 {
		t.Fatalf("ChapterInfo() = %+v, %v", info, err)
	}
	progress, err := client.Progress(context.Background(), 7)
	if err != nil || progress.PageNum != 1 || progress.SeriesID != 9 {
		t.Fatalf("Progress() = %+v, %v", progress, err)
	}
	response, err := client.OpenPage(context.Background(), 7, 2)
	if err != nil {
		t.Fatal(err)
	}
	body, readErr := io.ReadAll(response.Body)
	response.Body.Close()
	if readErr != nil || response.StatusCode != http.StatusOK || response.Header.Get("Content-Type") != "image/jpeg" || string(body) != "page-two" {
		t.Fatalf("OpenPage() = %d %q %q, %v", response.StatusCode, response.Header.Get("Content-Type"), body, readErr)
	}
	want := Progress{VolumeID: 4, ChapterID: 7, PageNum: 2, SeriesID: 9, LibraryID: 3}
	if err := client.SaveProgress(context.Background(), want); err != nil {
		t.Fatal(err)
	}
	if saved.VolumeID != 4 || saved.ChapterID != 7 || saved.PageNum != 2 || saved.SeriesID != 9 || saved.LibraryID != 3 {
		t.Fatalf("saved progress = %+v", saved)
	}
}

func TestReaderMethodsRejectInvalidCoordinatesWithoutCallingKavita(t *testing.T) {
	calls := 0
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		http.Error(w, "unexpected", http.StatusInternalServerError)
	}))
	defer upstream.Close()
	client, err := New(config.ServiceConfig{BaseURL: upstream.URL, APIKey: config.Secret("kavita-key")})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := client.ChapterInfo(context.Background(), 0); err == nil {
		t.Fatal("ChapterInfo accepted chapter zero")
	}
	if _, err := client.OpenPage(context.Background(), 7, -1); err == nil {
		t.Fatal("OpenPage accepted a negative page")
	}
	if err := client.SaveProgress(context.Background(), Progress{ChapterID: 7, PageNum: -1}); err == nil {
		t.Fatal("SaveProgress accepted an incomplete position")
	}
	if calls != 0 {
		t.Fatalf("invalid requests reached Kavita %d times", calls)
	}
}
