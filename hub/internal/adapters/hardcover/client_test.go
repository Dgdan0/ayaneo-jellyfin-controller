package hardcover

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"

	"ayaneohub/internal/config"
	"ayaneohub/internal/httpx"
)

// fakeHardcover is the GraphQL endpoint of Hardcover as much as the hub asks of it: one
// POST route that reads {query, variables} and answers by what is asked.
type fakeHardcover struct {
	t        *testing.T
	requests atomic.Int32
	tokens   []string
	// handler answers a query's variables with the JSON body to send.
	handler func(document string, variables map[string]any) (int, string)
}

func (f *fakeHardcover) serve() *httptest.Server {
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		f.requests.Add(1)
		f.tokens = append(f.tokens, r.Header.Get("Authorization"))
		if r.Method != http.MethodPost || r.URL.Path != "/v1/graphql" {
			f.t.Errorf("request = %s %s", r.Method, r.URL.Path)
		}
		var body struct {
			Query     string         `json:"query"`
			Variables map[string]any `json:"variables"`
		}
		raw, _ := io.ReadAll(r.Body)
		if err := json.Unmarshal(raw, &body); err != nil {
			f.t.Errorf("body is not a GraphQL request: %s", raw)
		}
		status, answer := f.handler(body.Query, body.Variables)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		_, _ = io.WriteString(w, answer)
	}))
}

func newClient(t *testing.T, server *httptest.Server, key string) *Client {
	t.Helper()
	client, err := New(config.ServiceConfig{BaseURL: server.URL, APIKey: config.Secret(key)})
	if err != nil {
		t.Fatal(err)
	}
	return client
}

const mistborn = `{"id": 7, "title": "The Final Empire", "slug": "the-final-empire", "rating": 4.4567, "ratings_count": 120433,
	"cached_tags": {"Genre": [{"tag": "Epic fantasy", "count": 90}, {"tag": "Fantasy", "count": 400}, {"tag": "fantasy", "count": 3}, {"tag": "Magic", "count": 50}],
	                "Mood": [{"tag": "Adventurous", "count": 800}]},
	"cached_contributors": [{"author": {"name": "Brandon Sanderson"}, "contribution": null}]}`

func TestNewDoesNothingWithoutAKey(t *testing.T) {
	for _, key := range []string{"", "   ", "Bearer ", "bearer   "} {
		client, err := New(config.ServiceConfig{Enabled: true, APIKey: config.Secret(key)})
		if client != nil || !errors.Is(err, ErrNoKey) {
			t.Errorf("key %q: %v, %v", key, client, err)
		}
	}
}

func TestByISBNAsksForBothFormsWithTheBearerKeyAndReadsTheBook(t *testing.T) {
	fake := &fakeHardcover{t: t}
	fake.handler = func(document string, variables map[string]any) (int, string) {
		if !strings.Contains(document, "isbn_13") || !strings.Contains(document, "isbn_10") {
			t.Errorf("the query does not ask by ISBN: %s", document)
		}
		if variables["isbn13"] != "9780765311788" || variables["isbn10"] != "0765311781" {
			t.Errorf("variables = %v", variables)
		}
		return 200, `{"data": {"editions": [{"book": ` + mistborn + `}]}}`
	}
	server := fake.serve()
	defer server.Close()

	// The token as hardcover.app shows it, "Bearer " and all, or without.
	for _, key := range []string{"abc.def.ghi", "Bearer abc.def.ghi"} {
		client := newClient(t, server, key)
		book, err := client.ByISBN(context.Background(), "9780765311788", "0765311781")
		if err != nil || book == nil {
			t.Fatalf("book = %+v, %v", book, err)
		}
		if book.ID != 7 || book.Title != "The Final Empire" || book.Slug != "the-final-empire" || book.Rating != 4.4567 || book.RatingsCount != 120433 {
			t.Errorf("book = %+v", book)
		}
		// Most tagged first, one spelling of a genre, and only the Genre tags.
		if got := strings.Join(book.Genres, "|"); got != "Fantasy|Epic fantasy|Magic" {
			t.Errorf("genres = %q", got)
		}
		if got := strings.Join(book.Authors, "|"); got != "Brandon Sanderson" {
			t.Errorf("authors = %q", got)
		}
	}
	for _, token := range fake.tokens {
		if token != "Bearer abc.def.ghi" {
			t.Errorf("Authorization = %q", token)
		}
	}
}

func TestAnISBN10WithACheckLetterIsAskedForWithACapitalX(t *testing.T) {
	fake := &fakeHardcover{t: t}
	fake.handler = func(_ string, variables map[string]any) (int, string) {
		if variables["isbn13"] != "9780765311788" || variables["isbn10"] != "076531178X" {
			t.Errorf("variables = %v", variables)
		}
		return 200, `{"data": {"editions": []}}`
	}
	server := fake.serve()
	defer server.Close()
	if book, err := newClient(t, server, "k").ByISBN(context.Background(), "9780765311788", "076531178x"); book != nil || err != nil {
		t.Fatalf("book = %+v, %v", book, err)
	}
}

func TestByISBNWithNoTenFormAsksWithTheThirteenAndNeverAskedForNothing(t *testing.T) {
	fake := &fakeHardcover{t: t}
	fake.handler = func(_ string, variables map[string]any) (int, string) {
		if variables["isbn10"] != "9791032305751" {
			t.Errorf("variables = %v: a null filter is an error to the server", variables)
		}
		return 200, `{"data": {"editions": []}}`
	}
	server := fake.serve()
	defer server.Close()
	client := newClient(t, server, "k")
	if book, err := client.ByISBN(context.Background(), "9791032305751", ""); book != nil || err != nil {
		t.Fatalf("an unknown ISBN = %+v, %v", book, err)
	}
	before := fake.requests.Load()
	if book, err := client.ByISBN(context.Background(), "  ", "0765311781"); book != nil || err != nil || fake.requests.Load() != before {
		t.Fatalf("no ISBN asked the service: %+v, %v", book, err)
	}
}

func TestByISBNTakesTheMostRatedOfTheBooksItsEditionsBelongTo(t *testing.T) {
	fake := &fakeHardcover{t: t}
	fake.handler = func(string, map[string]any) (int, string) {
		return 200, `{"data": {"editions": [{"book": null}, {"book": {"id": 1, "title": "A", "rating": "3.5", "ratings_count": "10"}}, {"book": {"id": 2, "title": "B", "rating": 4, "ratings_count": 900}}]}}`
	}
	server := fake.serve()
	defer server.Close()
	book, err := newClient(t, server, "k").ByISBN(context.Background(), "9780765311788", "")
	if err != nil || book == nil || book.ID != 2 || book.Rating != 4 || book.RatingsCount != 900 {
		t.Fatalf("book = %+v, %v", book, err)
	}
}

func TestByTitleListsTheBooksOfThatTitleMostRatedFirstAndReadsLooseJSON(t *testing.T) {
	fake := &fakeHardcover{t: t}
	fake.handler = func(document string, variables map[string]any) (int, string) {
		if variables["title"] != "The Final Empire" || !strings.Contains(document, "books(") {
			t.Errorf("document %q variables %v", document, variables)
		}
		// cached_tags and cached_contributors as JSON inside strings; a rating that is
		// null; one that is out of range.
		return 200, `{"data": {"books": [
			{"id": 1, "title": "The Final Empire", "rating": null, "ratings_count": null, "cached_tags": null, "cached_contributors": "[{\"author\": {\"name\": \"Someone Else\"}}]"},
			{"id": 2, "title": "The Final Empire", "rating": 4.5, "ratings_count": 500, "cached_tags": "{\"Genre\": [{\"tag\": \"Fantasy\", \"count\": 3}]}", "cached_contributors": [{"name": "Brandon Sanderson"}]},
			{"id": 3, "title": "The Final Empire", "rating": 9, "ratings_count": -5}
		]}}`
	}
	server := fake.serve()
	defer server.Close()
	books, err := newClient(t, server, "k").ByTitle(context.Background(), " The Final Empire ")
	if err != nil || len(books) != 3 {
		t.Fatalf("books = %+v, %v", books, err)
	}
	if books[0].ID != 2 || books[0].Rating != 4.5 || strings.Join(books[0].Genres, "|") != "Fantasy" || strings.Join(books[0].Authors, "|") != "Brandon Sanderson" {
		t.Errorf("first = %+v", books[0])
	}
	var one, three Book
	for _, book := range books {
		if book.ID == 1 {
			one = book
		}
		if book.ID == 3 {
			three = book
		}
	}
	if one.Rating != 0 || one.RatingsCount != 0 || strings.Join(one.Authors, "|") != "Someone Else" || len(one.Genres) != 0 {
		t.Errorf("a book nobody rated = %+v", one)
	}
	if three.Rating != 0 || three.RatingsCount != 0 {
		t.Errorf("numbers out of range = %+v", three)
	}
	if books, err := newClient(t, server, "k").ByTitle(context.Background(), "  "); books != nil || err != nil {
		t.Errorf("an empty title = %v, %v", books, err)
	}
}

func TestFailuresAreErrorsThatNameTheProblemAndNeverTheKey(t *testing.T) {
	const key = "super-secret-token-value"
	for name, test := range map[string]struct {
		status int
		body   string
		want   string
	}{
		"a GraphQL error":        {200, `{"errors": [{"message": "field 'ratings_count' not found in type: 'books'"}]}`, "ratings_count"},
		"an error with no words": {200, `{"errors": [{}]}`, "query failed"},
		"a rejected key":         {401, `{"error": "Unable to verify token"}`, "401"},
		"rate limited":           {429, `{"error": "Throttled"}`, "429"},
		"not JSON":               {200, `<html>`, "decode"},
		"the service down":       {503, `unavailable`, "503"},
	} {
		t.Run(name, func(t *testing.T) {
			fake := &fakeHardcover{t: t}
			fake.handler = func(string, map[string]any) (int, string) { return test.status, test.body }
			server := fake.serve()
			defer server.Close()
			_, err := newClient(t, server, key).ByISBN(context.Background(), "9780765311788", "")
			if err == nil || !strings.Contains(err.Error(), test.want) || strings.Contains(err.Error(), key) {
				t.Fatalf("err = %v, want %q and no key", err, test.want)
			}
			if test.status == 429 {
				var failure *httpx.Error
				if !errors.As(err, &failure) || failure.Kind != httpx.KindRateLimited {
					t.Errorf("a 429 is not classified as rate limiting: %v", err)
				}
			}
			if _, err := newClient(t, server, key).ByTitle(context.Background(), "x"); err == nil {
				t.Errorf("a title lookup hid the failure")
			}
		})
	}
}
