// Package hardcover reads a book's community rating and genres from Hardcover
// (hardcover.app), whose public GraphQL API takes a free per-user key. It is the book
// page's source for "4.5 from readers" and the genre line (#39).
//
// It does nothing without a key: New refuses to build a client, so the hub holds none
// and no request can be made. A book it cannot find is nil with no error; an error is
// the service being unreachable, rate limiting, or an answer it cannot read, and the
// hub treats every one of them as no community data rather than a failure of the page.
package hardcover

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"strconv"
	"strings"

	"ayaneohub/internal/config"
	"ayaneohub/internal/httpx"
)

const (
	defaultBaseURL = "https://api.hardcover.app"
	graphQLPath    = "/v1/graphql"
	// How many books a title can match before the search is not worth reading.
	titleCandidates = 20
)

// ErrNoKey is a service with no api_key: nothing is asked of Hardcover.
var ErrNoKey = errors.New("hardcover: no api_key")

type Client struct {
	base *httpx.Base
}

// New builds the client. The key is the token on hardcover.app > Settings > API, with
// or without the "Bearer " it is shown with; base_url is only for a test.
func New(cfg config.ServiceConfig) (*Client, error) {
	words := strings.Fields(cfg.APIKey.Reveal())
	if len(words) > 0 && strings.EqualFold(words[0], "bearer") {
		words = words[1:]
	}
	token := strings.Join(words, "")
	if token == "" {
		return nil, ErrNoKey
	}
	baseURL := strings.TrimSpace(cfg.BaseURL)
	if baseURL == "" {
		baseURL = defaultBaseURL
	}
	base, err := httpx.New(httpx.Options{
		Name: "hardcover", BaseURL: baseURL,
		Auth:    httpx.HeaderAuth{Headers: map[string]string{"Authorization": "Bearer " + token}},
		Headers: map[string]string{"User-Agent": "AyaneoHub (personal media server)"},
		Timeout: cfg.Timeout.OrDefault(0),
	})
	if err != nil {
		return nil, err
	}
	return &Client{base: base}, nil
}

// Book is what the page uses of a Hardcover book.
type Book struct {
	ID    int
	Title string
	Slug  string
	// Authors are the names Hardcover credits the book to.
	Authors []string
	// Rating is the readers' average, 0 to 5, and RatingsCount how many rated; both 0
	// for a book nobody has rated.
	Rating       float64
	RatingsCount int
	// Genres are the book's Genre tags, the most tagged first.
	Genres []string
}

const bookFields = `id title slug rating ratings_count cached_tags cached_contributors`

const editionQuery = `query ($isbn13: String!, $isbn10: String!) {
  editions(where: {_or: [{isbn_13: {_eq: $isbn13}}, {isbn_10: {_eq: $isbn10}}]}, limit: 5) {
    book { ` + bookFields + ` }
  }
}`

// The match is exact, so the title is asked in each of its likely spellings at once,
// the most rated first. Measured on 2026-10-08: "Dark Matter" is the title of more than
// twenty books, and with no order the twenty returned left out Blake Crouch's (3,072
// ratings); `_ilike`, which would have taken care of the capitals, is refused with a 403
// ("not permitted on this server").
const titleQuery = `query ($titles: [String!]!) {
  books(where: {title: {_in: $titles}}, order_by: {ratings_count: desc}, limit: 20) { ` + bookFields + ` }
}`

// smallWords are a title's words Hardcover writes in lower case after its first word.
var smallWords = map[string]bool{
	"a": true, "an": true, "and": true, "as": true, "at": true, "but": true, "by": true,
	"for": true, "from": true, "in": true, "into": true, "nor": true, "of": true, "on": true,
	"or": true, "the": true, "to": true, "with": true,
}

// maxSpellings caps how many spellings one lookup asks for.
const maxSpellings = 8

// TitleSpellings are the ways Hardcover may have written a title: as given; with its
// small words in lower case (Storyteller's "The Shadow Of What Was Lost" is "The Shadow
// of What Was Lost" there); with a leading "The" taken away or added ("Well of
// Ascension" is "The Well of Ascension"); and with a curly apostrophe made straight.
// The title as given is first.
func TitleSpellings(title string) []string {
	title = strings.Join(strings.Fields(title), " ")
	if title == "" {
		return nil
	}
	var out []string
	seen := map[string]bool{}
	add := func(spelling string) {
		if spelling != "" && !seen[spelling] && len(out) < maxSpellings {
			seen[spelling] = true
			out = append(out, spelling)
		}
	}
	bases := []string{title}
	if straight := strings.NewReplacer("’", "'", "‘", "'").Replace(title); straight != title {
		bases = append(bases, straight)
	}
	for _, base := range bases {
		forms := []string{base, smallWordsLower(base)}
		for _, form := range forms {
			add(form)
		}
		for _, form := range forms {
			if rest, ok := withoutLeadingThe(form); ok {
				add(rest)
			} else {
				add("The " + form)
			}
		}
	}
	return out
}

func smallWordsLower(title string) string {
	words := strings.Split(title, " ")
	for i, word := range words {
		if i > 0 && smallWords[strings.ToLower(word)] {
			words[i] = strings.ToLower(word)
		}
	}
	return strings.Join(words, " ")
}

func withoutLeadingThe(title string) (string, bool) {
	if len(title) > 4 && strings.EqualFold(title[:4], "the ") {
		return title[4:], true
	}
	return "", false
}

// ByISBN is the book of an edition with this ISBN-13 (or its ISBN-10 form), or nil.
func (c *Client) ByISBN(ctx context.Context, isbn13, isbn10 string) (*Book, error) {
	isbn13 = strings.TrimSpace(isbn13)
	// An ISBN-10 ends in an X, not an x, wherever the number was kept lower-cased.
	if isbn10 = strings.ToUpper(strings.TrimSpace(isbn10)); isbn10 == "" {
		// A filter on a null is an error to the server; the number again matches nothing new.
		isbn10 = isbn13
	}
	if isbn13 == "" {
		return nil, nil
	}
	var out struct {
		Data struct {
			Editions []struct {
				Book *node `json:"book"`
			} `json:"editions"`
		} `json:"data"`
		Errors []graphQLError `json:"errors"`
	}
	if err := c.query(ctx, editionQuery, map[string]any{"isbn13": isbn13, "isbn10": isbn10}, &out); err != nil {
		return nil, err
	}
	if err := firstError(out.Errors); err != nil {
		return nil, err
	}
	var best *Book
	for _, edition := range out.Data.Editions {
		if edition.Book == nil {
			continue
		}
		if book := edition.Book.book(); best == nil || book.RatingsCount > best.RatingsCount {
			best = &book
		}
	}
	return best, nil
}

// ByTitle is the books with this title in any of its TitleSpellings, most rated first.
// Which of them is the one wanted is the caller's to say, by author.
func (c *Client) ByTitle(ctx context.Context, title string) ([]Book, error) {
	titles := TitleSpellings(title)
	if len(titles) == 0 {
		return nil, nil
	}
	var out struct {
		Data struct {
			Books []node `json:"books"`
		} `json:"data"`
		Errors []graphQLError `json:"errors"`
	}
	if err := c.query(ctx, titleQuery, map[string]any{"titles": titles}, &out); err != nil {
		return nil, err
	}
	if err := firstError(out.Errors); err != nil {
		return nil, err
	}
	books := make([]Book, 0, len(out.Data.Books))
	for _, candidate := range out.Data.Books {
		books = append(books, candidate.book())
	}
	sort.SliceStable(books, func(i, j int) bool { return books[i].RatingsCount > books[j].RatingsCount })
	if len(books) > titleCandidates {
		books = books[:titleCandidates]
	}
	return books, nil
}

// Series is a Hardcover series with its main entries, for a library's fan of a series' books (#54).
type Series struct {
	ID   int
	Name string
	// Authors are the names the series is credited to (one, as Hardcover keeps it).
	Authors []string
	// Primary is how many books Hardcover counts as the series' own, novellas and omnibuses aside.
	Primary int
	// Entries are the featured, canonical books of the series in order of their place in it, one for each place.
	Entries []SeriesEntry
}

// SeriesEntry is one book of a series.
type SeriesEntry struct {
	// Position is its place in the series: 3, or 2.5 for a novella between two books.
	Position float64
	// Main is a numbered book of the series, as against a novella or a part: its place is a whole number
	// and Hardcover's own note for it (`details`) says that number or nothing.
	Main  bool
	Title string
	Slug  string
	// ReleasedOn is "2024-10-01", or "" for a book with no date: it is not out.
	ReleasedOn string
	// Cover is the https address of its cover, or "".
	Cover string
}

// The entries asked for are the series' own: featured, not a compilation, the canonical book of its place
// (Hardcover keeps a "duplicate" for every translation, and each of them takes the series' place too, which
// is why position 1 of the Red Rising Saga is eleven rows until they are filtered out). Measured on
// 2026-10-09: Red Rising Saga is 11 rows of which 4.1, 4.2, 5.1 and 5.2 are the parts of two books;
// The Stormlight Archive is 32 rows with the dramatised adaptations among them; `_ilike` is refused with a
// 403 here as for titles, so the name is matched exactly and asked in several spellings.
const seriesQuery = `query ($names: [String!]!) {
  series(where: {name: {_in: $names}, state: {_eq: "active"}}, order_by: {primary_books_count: desc}, limit: 6) {
    id name primary_books_count author { name }
    book_series(where: {featured: {_eq: true}, position: {_gte: 1}, compilation: {_eq: false},
                        book: {state: {_eq: "normalized"}, compilation: {_eq: false}, canonical_id: {_is_null: true}}},
                order_by: [{position: asc}, {book: {users_count: desc}}]) {
      position details book { id title slug release_date cached_image }
    }
  }
}`

// SeriesByName is the series that go by any of names (see TitleSpellings), the largest first, each with its
// entries. Which of them is the one wanted is the caller's to say, by author and by the books it has.
func (c *Client) SeriesByName(ctx context.Context, names []string) ([]Series, error) {
	if len(names) == 0 {
		return nil, nil
	}
	var out struct {
		Data struct {
			Series []struct {
				ID      flexNumber `json:"id"`
				Name    string     `json:"name"`
				Primary flexNumber `json:"primary_books_count"`
				Author  *struct {
					Name string `json:"name"`
				} `json:"author"`
				BookSeries []struct {
					Position flexNumber `json:"position"`
					Details  string     `json:"details"`
					Book     struct {
						Title       string          `json:"title"`
						Slug        string          `json:"slug"`
						ReleaseDate string          `json:"release_date"`
						CachedImage json.RawMessage `json:"cached_image"`
					} `json:"book"`
				} `json:"book_series"`
			} `json:"series"`
		} `json:"data"`
		Errors []graphQLError `json:"errors"`
	}
	if err := c.query(ctx, seriesQuery, map[string]any{"names": names}, &out); err != nil {
		return nil, err
	}
	if err := firstError(out.Errors); err != nil {
		return nil, err
	}
	series := make([]Series, 0, len(out.Data.Series))
	for _, candidate := range out.Data.Series {
		entry := Series{ID: int(candidate.ID), Name: candidate.Name, Primary: int(candidate.Primary)}
		if candidate.Author != nil && strings.TrimSpace(candidate.Author.Name) != "" {
			entry.Authors = []string{strings.TrimSpace(candidate.Author.Name)}
		}
		seen := map[string]bool{}
		for _, row := range candidate.BookSeries {
			position := float64(row.Position)
			// The most read book of a place is first (the query's order), and is the one kept.
			key := strconv.FormatFloat(position, 'f', -1, 64)
			if position < 1 || seen[key] || strings.TrimSpace(row.Book.Title) == "" {
				continue
			}
			seen[key] = true
			whole := position == float64(int(position))
			details := strings.TrimSpace(row.Details)
			entry.Entries = append(entry.Entries, SeriesEntry{
				Position: position, Title: strings.TrimSpace(row.Book.Title), Slug: row.Book.Slug,
				Main:       whole && (details == "" || details == key),
				ReleasedOn: strings.TrimSpace(row.Book.ReleaseDate), Cover: coverOf(row.Book.CachedImage),
			})
		}
		series = append(series, entry)
	}
	return series, nil
}

// coverOf is the address in a book's cached_image, `{"url": "https://..."}` or `{}`.
func coverOf(raw json.RawMessage) string {
	var image struct {
		URL string `json:"url"`
	}
	if !decodeLoose(raw, &image) {
		return ""
	}
	return strings.TrimSpace(image.URL)
}

func (c *Client) query(ctx context.Context, document string, variables map[string]any, out any) error {
	return c.base.PostJSON(ctx, graphQLPath, map[string]any{"query": document, "variables": variables}, out)
}

type graphQLError struct {
	Message string `json:"message"`
}

// firstError is the service's own words for what went wrong. They describe the query,
// never the key.
func firstError(errs []graphQLError) error {
	for _, e := range errs {
		if message := strings.TrimSpace(e.Message); message != "" {
			if len(message) > 200 {
				message = message[:200]
			}
			return fmt.Errorf("hardcover: %s", message)
		}
	}
	if len(errs) > 0 {
		return errors.New("hardcover: the query failed")
	}
	return nil
}

// node is a book as the API sends it. Numbers may arrive as numbers or strings and
// the cached fields as JSON or as JSON inside a string, so each is read loosely.
type node struct {
	ID                 flexNumber      `json:"id"`
	Title              string          `json:"title"`
	Slug               string          `json:"slug"`
	Rating             flexNumber      `json:"rating"`
	RatingsCount       flexNumber      `json:"ratings_count"`
	CachedTags         json.RawMessage `json:"cached_tags"`
	CachedContributors json.RawMessage `json:"cached_contributors"`
}

func (n node) book() Book {
	book := Book{
		ID: int(n.ID), Title: n.Title, Slug: n.Slug,
		Rating: float64(n.Rating), RatingsCount: int(n.RatingsCount),
		Genres: genresOf(n.CachedTags), Authors: authorsOf(n.CachedContributors),
	}
	if book.Rating < 0 || book.Rating > 5 {
		book.Rating = 0
	}
	if book.RatingsCount < 0 {
		book.RatingsCount = 0
	}
	return book
}

// notGenres are Genre tags readers put on a book that say how it was had, not what it
// is (seen on 2026-10-08: "Audiobook" and "General" among The Shadow of What Was Lost's).
var notGenres = map[string]bool{
	"audiobook": true, "audiobooks": true, "general": true, "ebook": true, "ebooks": true, "kindle": true,
}

// genresOf is the Genre tags of a book's cached_tags, whose shape is
// {"Genre": [{"tag": "Fantasy", "count": 12}, ...], "Mood": [...], ...}.
func genresOf(raw json.RawMessage) []string {
	var tags map[string][]struct {
		Tag   string     `json:"tag"`
		Count flexNumber `json:"count"`
	}
	if !decodeLoose(raw, &tags) {
		return nil
	}
	list := tags["Genre"]
	sort.SliceStable(list, func(i, j int) bool { return list[i].Count > list[j].Count })
	var genres []string
	seen := map[string]bool{}
	for _, entry := range list {
		name := strings.TrimSpace(entry.Tag)
		if key := strings.ToLower(name); name != "" && !seen[key] && !notGenres[key] {
			seen[key] = true
			genres = append(genres, name)
		}
	}
	return genres
}

// authorsOf is the names in cached_contributors: [{"author": {"name": "..."}, ...}].
func authorsOf(raw json.RawMessage) []string {
	var contributors []struct {
		Author struct {
			Name string `json:"name"`
		} `json:"author"`
		Name string `json:"name"`
	}
	if !decodeLoose(raw, &contributors) {
		return nil
	}
	var names []string
	for _, contributor := range contributors {
		name := strings.TrimSpace(contributor.Author.Name)
		if name == "" {
			name = strings.TrimSpace(contributor.Name)
		}
		if name != "" {
			names = append(names, name)
		}
	}
	return names
}

// decodeLoose reads JSON that may itself be a string holding JSON.
func decodeLoose(raw json.RawMessage, out any) bool {
	raw = json.RawMessage(strings.TrimSpace(string(raw)))
	if len(raw) == 0 || string(raw) == "null" {
		return false
	}
	if raw[0] == '"' {
		var inner string
		if json.Unmarshal(raw, &inner) != nil {
			return false
		}
		raw = json.RawMessage(inner)
	}
	return json.Unmarshal(raw, out) == nil
}

// flexNumber is a number sent as a number, a string or null.
type flexNumber float64

func (f *flexNumber) UnmarshalJSON(data []byte) error {
	text := strings.Trim(strings.TrimSpace(string(data)), `"`)
	if text == "" || text == "null" {
		*f = 0
		return nil
	}
	value, err := strconv.ParseFloat(text, 64)
	if err != nil {
		*f = 0
		return nil
	}
	*f = flexNumber(value)
	return nil
}
