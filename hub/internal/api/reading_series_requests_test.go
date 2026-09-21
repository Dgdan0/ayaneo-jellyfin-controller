package api

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"ayaneohub/internal/adapters/openlibrary"
	"ayaneohub/internal/adapters/wikidata"
)

func TestReadingSeriesPreviewAndExactGroupedRequest(t *testing.T) {
	var created []map[string]any
	var linked []map[string]any
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.URL.Path == "/api/discover/search":
			_, _ = w.Write([]byte(`{"results":[{"contentType":"ebook","source":"openlibrary","sourceId":"OL1W","title":"Red Rising","author":"Pierce Brown","isbn":"9780345539786"}]}`))
		case r.URL.Path == "/api/quality-profiles":
			_, _ = w.Write([]byte(`[{"id":7,"name":"English EPUB","isDefault":true}]`))
		case r.URL.Path == "/api/auth/login":
			_, _ = w.Write([]byte(`{"redirect_to":"bookkeeprr://hub/auth?exchange=one"}`))
		case r.URL.Path == "/api/mobile/exchange":
			_, _ = w.Write([]byte(`{"token":"admin-token"}`))
		case r.Method == http.MethodGet && r.URL.Path == "/api/book-series":
			_, _ = w.Write([]byte(`{"bookSeries":[]}`))
		case r.Method == http.MethodPost && r.URL.Path == "/api/book-series":
			var body map[string]any
			_ = json.NewDecoder(r.Body).Decode(&body)
			if body["name"] != "Red Rising Saga" || body["contentType"] != "ebook" {
				t.Fatalf("parent body = %#v", body)
			}
			w.WriteHeader(http.StatusCreated)
			_, _ = w.Write([]byte(`{"id":90,"name":"Red Rising Saga","contentType":"ebook","source":"manual","memberCount":0}`))
		case r.Method == http.MethodGet && r.URL.Path == "/api/series":
			_, _ = w.Write([]byte(`{"rows":[],"total":0,"page":1,"limit":100}`))
		case r.Method == http.MethodPost && r.URL.Path == "/api/series":
			var body map[string]any
			_ = json.NewDecoder(r.Body).Decode(&body)
			created = append(created, body)
			w.WriteHeader(http.StatusCreated)
			_, _ = fmt.Fprintf(w, `{"id":%d}`, len(created))
		case r.Method == http.MethodPost && strings.HasPrefix(r.URL.Path, "/api/book-series/90/members"):
			var body map[string]any
			_ = json.NewDecoder(r.Body).Decode(&body)
			linked = append(linked, body)
			_, _ = w.Write([]byte(`{"id":90,"name":"Red Rising Saga","contentType":"ebook","source":"manual","memberCount":1,"books":[]}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer upstream.Close()

	ol := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/isbn/9780345539786.json":
			_, _ = w.Write([]byte(`{"works":[{"key":"/works/OL1W"}]}`))
		case "/works/OL1W.json":
			_, _ = w.Write([]byte(`{"key":"/works/OL1W","title":"Red Rising","series":[{"series":{"key":"/series/OL100L"},"position":"1"}]}`))
		case "/series/OL100L.json":
			_, _ = w.Write([]byte(`{"name":"Red Rising Saga"}`))
		case "/search.json":
			_, _ = w.Write([]byte(`{"numFound":3,"docs":[
				{"key":"/works/OL1W","title":"Red Rising","author_key":["OL1A"],"author_name":["Pierce Brown"],"first_publish_year":2014,"cover_i":10,"isbn":["9780345539786"]},
				{"key":"/works/OL2W","title":"Golden Son","author_key":["OL1A"],"author_name":["Pierce Brown"],"first_publish_year":2015,"cover_i":20,"isbn":["9780345539816"]},
				{"key":"/works/OL3W","title":"Morning Star","author_key":["OL1A"],"author_name":["Pierce Brown"],"first_publish_year":2016,"cover_i":30,"isbn":["9780345539861"]}
			]}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer ol.Close()

	cfg := readingAcquisitionConfig(upstream.URL, []string{"reading", "request"}, true)
	cfg.Server.ReadingTransfers = filepath.Join(t.TempDir(), "reading-transfers.json")
	server := NewServer(cfg)
	server.openlibrary = openlibrary.New(ol.URL)
	handler := server.Handler()
	search := libraryRequest(handler, "/v1/reading/search?q=Red%20Rising&type=ebook")
	var found ReadingSearchResponse
	_ = json.Unmarshal(search.Body.Bytes(), &found)
	key := found.Results[0].Key

	previewResponse := libraryRequest(handler, "/v1/reading/requests/series-preview?key="+key)
	if previewResponse.Code != http.StatusOK {
		t.Fatalf("preview = %d: %s", previewResponse.Code, previewResponse.Body.String())
	}
	var previewResponseBody ReadingSeriesPreviewResponse
	if err := json.Unmarshal(previewResponse.Body.Bytes(), &previewResponseBody); err != nil {
		t.Fatal(err)
	}
	preview := previewResponseBody.Scopes[0]
	if preview.SeriesID != "OL100L" || preview.Name != "Red Rising Saga" || len(preview.Books) != 3 || preview.Books[1].Title != "Golden Son" {
		t.Fatalf("preview = %+v", preview)
	}

	createdResponse := readingJSONRequest(handler, http.MethodPost, "/v1/reading/requests",
		`{"key":"`+key+`","mode":"series","seriesId":"OL100L","bookIds":["OL1W","OL3W"],"qualityProfileId":7,"monitoring":"all"}`)
	if createdResponse.Code != http.StatusAccepted {
		t.Fatalf("create = %d: %s", createdResponse.Code, createdResponse.Body.String())
	}
	var response ReadingRequestResponse
	_ = json.Unmarshal(createdResponse.Body.Bytes(), &response)
	if response.ParentSeriesID != 90 || response.Requested != 2 || response.State != "accepted" || len(created) != 2 || len(linked) != 2 {
		t.Fatalf("response=%+v created=%#v linked=%#v", response, created, linked)
	}
	if created[0]["flow"] != "single" || created[0]["olid"] != "OL1W" || created[1]["olid"] != "OL3W" {
		t.Fatalf("child requests = %#v", created)
	}
	if linked[0]["position"] != float64(1) || linked[1]["position"] != float64(3) {
		t.Fatalf("links = %#v", linked)
	}

	// A lost client response may cause the exact request to be replayed. The
	// durable manifest must return the same result without recreating children
	// or relinking members that were already accepted.
	replayed := readingJSONRequest(handler, http.MethodPost, "/v1/reading/requests",
		`{"key":"`+key+`","mode":"series","seriesId":"OL100L","bookIds":["OL1W","OL3W"],"qualityProfileId":7,"monitoring":"all"}`)
	if replayed.Code != http.StatusAccepted || len(created) != 2 || len(linked) != 2 {
		t.Fatalf("replay=%d created=%d linked=%d body=%s", replayed.Code, len(created), len(linked), replayed.Body.String())
	}
}

func TestReadingSeriesRequestRejectsBookOutsidePreview(t *testing.T) {
	store := newReadingSeriesPreviewStore(4)
	store.put("reading:00000000000000000000000000000000", ReadingSeriesPreview{
		SeriesID: "OL100L", Books: []ReadingSeriesPreviewBook{{ID: "OL1W"}},
	})
	if _, err := store.selected("reading:00000000000000000000000000000000", "OL100L", []string{"OL999W"}); err == nil {
		t.Fatal("expected an out-of-roster selection error")
	}
}

func TestReadingSeriesPreviewRepairsPartialOpenLibraryTrilogyWithWikidata(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/discover/search":
			_, _ = w.Write([]byte(`{"results":[{"contentType":"ebook","source":"openlibrary","sourceId":"OL1W","title":"The Final Empire","author":"Brandon Sanderson","isbn":"9780000000001"}]}`))
		case "/api/book-series":
			_, _ = w.Write([]byte(`{"bookSeries":[]}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer upstream.Close()

	ol := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/isbn/9780000000001.json":
			_, _ = w.Write([]byte(`{"works":[{"key":"/works/OL1W"}]}`))
		case "/works/OL1W.json":
			_, _ = w.Write([]byte(`{"subjects":["series:The Mistborn Saga","series:Mistborn Original Trilogy"]}`))
		case "/search.json":
			if strings.HasPrefix(r.URL.Query().Get("q"), "subject:") {
				_, _ = w.Write([]byte(`{"numFound":1,"docs":[{"key":"/works/OL1W","title":"The Final Empire","author_key":["OL1A"],"author_name":["Brandon Sanderson"],"first_publish_year":2006}]}`))
				return
			}
			_, _ = w.Write([]byte(`{"numFound":4,"docs":[
				{"key":"/works/OL1W","title":"The Final Empire","author_key":["OL1A"],"author_name":["Brandon Sanderson"],"first_publish_year":2001},
				{"key":"/works/OL2W","title":"The Well of Ascension","author_key":["OL1A"],"author_name":["Brandon Sanderson"],"first_publish_year":2018},
				{"key":"/works/OL3W","title":"The Hero of Ages","author_key":["OL1A"],"author_name":["Brandon Sanderson"],"first_publish_year":1999},
				{"key":"/works/OL4W","title":"The Alloy of Law","author_key":["OL1A"],"author_name":["Brandon Sanderson"],"first_publish_year":2011}
			]}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer ol.Close()

	wiki := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"results":{"bindings":[
			{"series":{"value":"http://www.wikidata.org/entity/Q10"},"seriesLabel":{"value":"Mistborn"},"book":{"value":"http://www.wikidata.org/entity/Q1"},"bookLabel":{"value":"The Final Empire"},"olid":{"value":"OL1W"},"date":{"value":"2006"}},
			{"series":{"value":"http://www.wikidata.org/entity/Q10"},"seriesLabel":{"value":"Mistborn"},"book":{"value":"http://www.wikidata.org/entity/Q2"},"bookLabel":{"value":"The Well of Ascension"},"olid":{"value":"OL2W"},"date":{"value":"2007"}},
			{"series":{"value":"http://www.wikidata.org/entity/Q10"},"seriesLabel":{"value":"Mistborn"},"book":{"value":"http://www.wikidata.org/entity/Q3"},"bookLabel":{"value":"The Hero of Ages"},"olid":{"value":"OL3W"},"date":{"value":"2008"}},
			{"series":{"value":"http://www.wikidata.org/entity/Q10"},"seriesLabel":{"value":"Mistborn"},"book":{"value":"http://www.wikidata.org/entity/Q4"},"bookLabel":{"value":"The Alloy of Law"},"olid":{"value":"OL4W"},"date":{"value":"2011"}}
		]}}`))
	}))
	defer wiki.Close()

	server := NewServer(readingAcquisitionConfig(upstream.URL, []string{"reading", "request"}, true))
	server.openlibrary = openlibrary.New(ol.URL)
	server.wikidata = wikidata.New(wiki.URL)
	handler := server.Handler()
	search := libraryRequest(handler, "/v1/reading/search?q=Mistborn&type=ebook")
	var found ReadingSearchResponse
	_ = json.Unmarshal(search.Body.Bytes(), &found)
	previewResponse := libraryRequest(handler, "/v1/reading/requests/series-preview?key="+found.Results[0].Key)
	if previewResponse.Code != http.StatusOK {
		t.Fatalf("preview = %d: %s", previewResponse.Code, previewResponse.Body.String())
	}
	var body ReadingSeriesPreviewResponse
	if err := json.Unmarshal(previewResponse.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if len(body.Scopes) != 2 || body.Scopes[0].Name != "Mistborn" || len(body.Scopes[0].Books) != 4 {
		t.Fatalf("scopes = %+v", body.Scopes)
	}
	if body.Scopes[1].Name != "Mistborn Original Trilogy" || len(body.Scopes[1].Books) != 3 || body.Scopes[1].Books[2].Title != "The Hero of Ages" {
		t.Fatalf("trilogy = %+v", body.Scopes[1])
	}
}
