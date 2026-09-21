package wikidata

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestSeriesContainingOpenLibraryWorkGroupsAndDeduplicatesOrderedMembers(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/sparql" || !strings.Contains(r.URL.Query().Get("query"), `"OL1W"`) {
			t.Fatalf("request = %s?%s", r.URL.Path, r.URL.RawQuery)
		}
		_, _ = w.Write([]byte(`{"results":{"bindings":[
			{"series":{"value":"http://www.wikidata.org/entity/Q10"},"seriesLabel":{"value":"Mistborn"},"book":{"value":"http://www.wikidata.org/entity/Q1"},"bookLabel":{"value":"The Final Empire"},"olid":{"value":"OL1W"},"date":{"value":"2006-07-17T00:00:00Z"}},
			{"series":{"value":"http://www.wikidata.org/entity/Q10"},"seriesLabel":{"value":"Mistborn"},"book":{"value":"http://www.wikidata.org/entity/Q1"},"bookLabel":{"value":"The Final Empire"},"olid":{"value":"OL1W"},"date":{"value":"2006-07-17T00:00:00Z"}},
			{"series":{"value":"http://www.wikidata.org/entity/Q10"},"seriesLabel":{"value":"Mistborn"},"book":{"value":"http://www.wikidata.org/entity/Q2"},"bookLabel":{"value":"The Well of Ascension"},"olid":{"value":"OL2W"},"date":{"value":"2007-08-21T00:00:00Z"}},
			{"series":{"value":"http://www.wikidata.org/entity/Q10"},"seriesLabel":{"value":"Mistborn"},"book":{"value":"http://www.wikidata.org/entity/Q3"},"bookLabel":{"value":"The Hero of Ages"},"olid":{"value":"OL3W"},"date":{"value":"2008-10-14T00:00:00Z"}}
		]}}`))
	}))
	defer server.Close()

	series, err := New(server.URL).SeriesContainingOpenLibraryWork(context.Background(), "OL1W")
	if err != nil {
		t.Fatal(err)
	}
	if len(series) != 1 || series[0].ID != "Q10" || series[0].Name != "Mistborn" {
		t.Fatalf("series = %+v", series)
	}
	if got := strings.Join(series[0].OpenLibraryWorkIDs, ","); got != "OL1W,OL2W,OL3W" {
		t.Fatalf("members = %q", got)
	}
}

func TestSeriesContainingOpenLibraryWorkRejectsInvalidIDBeforeNetwork(t *testing.T) {
	client := New("http://127.0.0.1:1")
	if _, err := client.SeriesContainingOpenLibraryWork(context.Background(), "../secret"); err == nil {
		t.Fatal("expected invalid work id")
	}
}
