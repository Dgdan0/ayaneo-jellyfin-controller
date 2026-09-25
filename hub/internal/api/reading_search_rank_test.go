package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"reflect"
	"testing"
)

func TestReadingSearchKeepsCloseTitlesAndSeparatesBroadSuggestions(t *testing.T) {
	items := []ReadingItem{
		{Title: "And They Found Dragons", Author: "Ted Dekker"},
		{Title: "The Blood Mirror (1 of 2): Lightbringer Saga 4", Author: "Brent Weeks"},
		{Title: "Night of the Lightbringer", Author: "Peter Berresford Ellis"},
		{Title: "Azrael and the Light Bringer", Author: "Someone Else"},
		{Title: "Light Bringer: A Red Rising Novel", Author: "Pierce Brown", ContentType: "audiobook"},
		{Title: "Light Bringer", Author: "Pierce Brown", ContentType: "ebook"},
		{Title: "Necromancy en Masse", Author: "Light Bringer"},
	}
	close, broader := rankReadingSearch("Light Bringer", items)
	if got := readingTitles(close); !reflect.DeepEqual(got, []string{
		"Light Bringer", "Light Bringer: A Red Rising Novel",
	}) {
		t.Fatalf("close titles = %v", got)
	}
	if got := readingTitles(broader); !reflect.DeepEqual(got, []string{
		"Azrael and the Light Bringer", "Night of the Lightbringer",
		"The Blood Mirror (1 of 2): Lightbringer Saga 4", "Necromancy en Masse", "And They Found Dragons",
	}) {
		t.Fatalf("broader titles = %v", got)
	}
}

func TestReadingSearchAuthorFallbackWhenNoTitleMatches(t *testing.T) {
	items := []ReadingItem{{Title: "Red Rising", Author: "Pierce Brown"}, {Title: "Golden Son", Author: "Pierce Brown"}, {Title: "Pierce the Darkness", Author: "Other"}}
	close, broader := rankReadingSearch("Pierce Brown", items)
	if got := readingTitles(close); !reflect.DeepEqual(got, []string{"Red Rising", "Golden Son"}) {
		t.Fatalf("author results = %v", got)
	}
	if len(broader) != 1 || broader[0].Title != "Pierce the Darkness" {
		t.Fatalf("broader results = %v", readingTitles(broader))
	}
}

func TestReadingSearchMatchesCombinedTitleAndAuthor(t *testing.T) {
	items := []ReadingItem{
		{Title: "The Final Empire", Author: "Brandon Sanderson"},
		{Title: "The Final Empire", Author: "Someone Else"},
		{Title: "Another Book", Author: "Brandon Sanderson"},
	}
	close, broader := rankReadingSearch("The Final Empire Brandon Sanderson", items)
	if got := readingTitles(close); !reflect.DeepEqual(got, []string{"The Final Empire"}) || close[0].Author != "Brandon Sanderson" {
		t.Fatalf("combined title and author matches = %+v", close)
	}
	if len(broader) != 2 {
		t.Fatalf("broader results = %+v", broader)
	}
}

func TestReadingSearchCanMatchBookTitleAfterSeriesPrefix(t *testing.T) {
	close, _ := rankReadingSearch("The Final Empire", []ReadingItem{{Title: "Mistborn: The Final Empire"}})
	if len(close) != 1 {
		t.Fatalf("book title after series prefix was hidden: %v", readingTitles(close))
	}
}

func TestReadingSearchResponseSeparatesBroaderMatches(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"results":[
			{"source":"openlibrary","sourceId":"a","contentType":"ebook","title":"And They Found Dragons"},
			{"source":"itunes","sourceId":"b","contentType":"audiobook","title":"Light Bringer: A Red Rising Novel","author":"Pierce Brown"},
			{"source":"openlibrary","sourceId":"c","contentType":"ebook","title":"Light Bringer","author":"Pierce Brown"}
		],"errors":{}}`))
	}))
	defer upstream.Close()
	got := libraryRequest(NewServer(readingAPIConfig(upstream.URL, []string{"reading"})).Handler(),
		"/v1/reading/search?q=Light%20Bringer&type=all")
	if got.Code != http.StatusOK {
		t.Fatalf("search status = %d: %s", got.Code, got.Body.String())
	}
	var body ReadingSearchResponse
	if err := json.Unmarshal(got.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if titles := readingTitles(body.Results); !reflect.DeepEqual(titles, []string{"Light Bringer", "Light Bringer: A Red Rising Novel"}) {
		t.Fatalf("close results = %v", titles)
	}
	if titles := readingTitles(body.BroaderResults); !reflect.DeepEqual(titles, []string{"And They Found Dragons"}) {
		t.Fatalf("broader results = %v", titles)
	}
}

func readingTitles(items []ReadingItem) []string {
	out := make([]string, 0, len(items))
	for _, item := range items {
		out = append(out, item.Title)
	}
	return out
}
