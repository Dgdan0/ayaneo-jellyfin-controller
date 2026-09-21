package openlibrary

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestSeriesPreviewResolvesCanonicalRosterAndOrder(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/isbn/9780345539786.json":
			_, _ = w.Write([]byte(`{"works":[{"key":"/works/OL1W"}]}`))
		case "/works/OL1W.json":
			_, _ = w.Write([]byte(`{"key":"/works/OL1W","title":"Red Rising","series":[{"series":{"key":"/series/OL100L"},"position":"1"}]}`))
		case "/series/OL100L.json":
			_, _ = w.Write([]byte(`{"name":"Red Rising Saga"}`))
		case "/search.json":
			if got := r.URL.Query().Get("q"); got != "series_key:OL100L" {
				t.Fatalf("query = %q", got)
			}
			_, _ = w.Write([]byte(`{"numFound":3,"docs":[
				{"key":"/works/OL3W","title":"Morning Star","author_key":["OL1A"],"author_name":["Pierce Brown"],"first_publish_year":2016,"cover_i":30,"isbn":["9780345539861"]},
				{"key":"/works/OL1W","title":"Red Rising","author_key":["OL1A"],"author_name":["Pierce Brown"],"first_publish_year":2014,"cover_i":10,"isbn":["9780345539786"]},
				{"key":"/works/OL2W","title":"Golden Son","author_key":["OL1A"],"author_name":["Pierce Brown"],"first_publish_year":2015,"cover_i":20,"isbn":["9780345539816"]}
			]}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()

	preview, err := New(server.URL).SeriesPreview(context.Background(), Candidate{
		WorkID: "OL1W", ISBN: "9780345539786", Title: "Red Rising", Author: "Pierce Brown",
	})
	if err != nil {
		t.Fatal(err)
	}
	if preview.ID != "OL100L" || preview.Name != "Red Rising Saga" || preview.AuthorID != "OL1A" || len(preview.Books) != 3 {
		t.Fatalf("preview = %+v", preview)
	}
	for i, want := range []string{"OL1W", "OL2W", "OL3W"} {
		if preview.Books[i].WorkID != want || preview.Books[i].Position != i+1 {
			t.Fatalf("book %d = %+v", i, preview.Books[i])
		}
	}
	if preview.Books[0].CoverURL != "https://covers.openlibrary.org/b/id/10-L.jpg" {
		t.Fatalf("cover = %q", preview.Books[0].CoverURL)
	}
}

func TestSeriesPreviewsOffersBroaderSubjectScopeBeforeStructuredTrilogy(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/works/OL1W.json":
			_, _ = w.Write([]byte(`{
				"series":[{"series":{"key":"/series/OL100L"},"position":"1"}],
				"subjects":["series:Red Rising Trilogy","series:Red Rising Saga"]
			}`))
		case "/series/OL100L.json":
			_, _ = w.Write([]byte(`{"name":"Red Rising"}`))
		case "/search.json":
			query := r.URL.Query().Get("q")
			if query == "series_key:OL100L" || query == `subject:"Red Rising Trilogy"` {
				_, _ = w.Write([]byte(`{"numFound":3,"docs":[
					{"key":"/works/OL1W","title":"Red Rising","author_key":["OL1A"],"author_name":["Pierce Brown"],"first_publish_year":2014},
					{"key":"/works/OL2W","title":"Golden Son","author_key":["OL1A"],"author_name":["Pierce Brown"],"first_publish_year":2015},
					{"key":"/works/OL3W","title":"Morning Star","author_key":["OL1A"],"author_name":["Pierce Brown"],"first_publish_year":2016}
				]}`))
				return
			}
			if query == `subject:"Red Rising Saga"` {
				_, _ = w.Write([]byte(`{"numFound":6,"docs":[
					{"key":"/works/OL1W","title":"Red Rising","author_key":["OL1A"],"author_name":["Pierce Brown"],"first_publish_year":2014},
					{"key":"/works/OL2W","title":"Golden Son","author_key":["OL1A"],"author_name":["Pierce Brown"],"first_publish_year":2015},
					{"key":"/works/OL3W","title":"Morning Star","author_key":["OL1A"],"author_name":["Pierce Brown"],"first_publish_year":2016},
					{"key":"/works/OL4W","title":"Iron Gold","author_key":["OL1A"],"author_name":["Pierce Brown"],"first_publish_year":2018},
					{"key":"/works/OL5W","title":"Dark Age","author_key":["OL1A"],"author_name":["Pierce Brown"],"first_publish_year":2015,"publish_date":["2015","July 30, 2019","2019-07-30","2019","Apr 07, 2020"]},
					{"key":"/works/OL6W","title":"Light Bringer","author_key":["OL1A"],"author_name":["Pierce Brown"],"first_publish_year":2022,"publish_date":["2022","July 25, 2023","2023-07-25","2023","2024"]}
				]}`))
				return
			}
			t.Fatalf("unexpected query %q", query)
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()

	previews, err := New(server.URL).SeriesPreviews(context.Background(), Candidate{
		WorkID: "OL1W", Title: "Red Rising", Author: "Pierce Brown",
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(previews) != 2 {
		t.Fatalf("previews = %+v", previews)
	}
	if previews[0].Name != "Red Rising Saga" || len(previews[0].Books) != 6 {
		t.Fatalf("first scope = %+v", previews[0])
	}
	if got := []string{
		previews[0].Books[3].Title,
		previews[0].Books[4].Title,
		previews[0].Books[5].Title,
	}; fmt.Sprint(got) != "[Iron Gold Dark Age Light Bringer]" {
		t.Fatalf("publication order tail = %v", got)
	}
	if previews[1].Name != "Red Rising Trilogy" || len(previews[1].Books) != 3 {
		t.Fatalf("second scope = %+v", previews[1])
	}
}

func TestSeriesPreviewRejectsUnverifiedStandaloneWork(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"key":"/works/OL1W","title":"Standalone"}`))
	}))
	defer server.Close()

	_, err := New(server.URL).SeriesPreview(context.Background(), Candidate{WorkID: "OL1W"})
	if err == nil {
		t.Fatal("expected an unavailable-series error")
	}
}

func TestBooksByWorkIDsHydratesWikidataRosterFromOpenLibrary(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/search.json" {
			http.NotFound(w, r)
			return
		}
		query := r.URL.Query().Get("q")
		for _, id := range []string{"OL1W", "OL2W", "OL3W"} {
			if !strings.Contains(query, "/works/"+id) {
				t.Fatalf("query %q is missing %s", query, id)
			}
		}
		_, _ = w.Write([]byte(`{"numFound":3,"docs":[
			{"key":"/works/OL2W","title":"The Well of Ascension","author_name":["Brandon Sanderson"],"first_publish_year":2018,"cover_i":20},
			{"key":"/works/OL1W","title":"The Final Empire","author_name":["Brandon Sanderson"],"first_publish_year":2001,"cover_i":10},
			{"key":"/works/OL3W","title":"The Hero of Ages","author_name":["Brandon Sanderson"],"first_publish_year":1999,"cover_i":30}
		]}`))
	}))
	defer server.Close()

	books, err := New(server.URL).BooksByWorkIDs(
		context.Background(), []string{"OL1W", "OL2W", "OL3W"}, Candidate{Author: "Brandon Sanderson"},
	)
	if err != nil {
		t.Fatal(err)
	}
	if len(books) != 3 || books[0].WorkID != "OL1W" || books[1].WorkID != "OL2W" || books[2].WorkID != "OL3W" || books[2].Position != 3 || books[1].CoverURL == "" {
		t.Fatalf("books = %+v", books)
	}
}
