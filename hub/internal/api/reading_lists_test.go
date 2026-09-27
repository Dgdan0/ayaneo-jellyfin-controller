package api

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestServerReadingListsMapsUnpromotedAndRejectsUnknownList(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/readinglist/lists":
			io.WriteString(w, `[{"id":19,"title":"My Marvelous Year 1962","promoted":false,"itemCount":2}]`)
		case "/api/readinglist/items":
			io.WriteString(w, `[{"id":2,"order":1,"seriesId":4327,"chapterId":8338,"seriesName":"Amazing Adult Fantasy","libraryId":2,"libraryType":1,"title":"Issue #7"},{"id":1,"order":0,"seriesId":2016,"chapterId":8421,"seriesName":"Fantastic Four","libraryId":2,"libraryType":1,"title":"Issue #1","pagesRead":9,"pagesTotal":36}]`)
		default:
			t.Errorf("unexpected endpoint %s", r.URL)
		}
	}))
	defer upstream.Close()
	handler := NewServer(readingCatalogConfig(upstream.URL, "", []string{"reading"})).Handler()
	lists := libraryRequest(handler, "/v1/reading/lists")
	if lists.Code != 200 {
		t.Fatalf("lists %d %s", lists.Code, lists.Body.String())
	}
	got := libraryRequest(handler, "/v1/reading/lists/19")
	var body struct {
		List  ServerReadingList        `json:"list"`
		Items []ServerReadingListEntry `json:"items"`
	}
	if got.Code != 200 {
		t.Fatalf("list %d %s", got.Code, got.Body.String())
	}
	if err := json.Unmarshal(got.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if body.List.Promoted || len(body.Items) != 2 || body.Items[0].SourceItemID != "8421" || body.Items[1].SourceItemID != "8338" || body.Items[0].WorkID == body.Items[1].WorkID || body.Items[0].Progress.Percentage != 0.25 {
		t.Fatalf("body %+v", body)
	}
	for path, code := range map[string]int{"/v1/reading/lists/99": 404, "/v1/reading/lists/no": 400} {
		if got := libraryRequest(handler, path); got.Code != code {
			t.Errorf("%s: %d", path, got.Code)
		}
	}
	forbidden := NewServer(readingCatalogConfig(upstream.URL, "", []string{"read"})).Handler()
	if got := libraryRequest(forbidden, "/v1/reading/lists"); got.Code != 403 {
		t.Errorf("missing scope: %d", got.Code)
	}
}
