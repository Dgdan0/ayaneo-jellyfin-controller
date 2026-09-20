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
