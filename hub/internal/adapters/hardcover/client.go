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

const titleQuery = `query ($title: String!) {
  books(where: {title: {_eq: $title}}, limit: 20) { ` + bookFields + ` }
}`

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

// ByTitle is the books with exactly this title, most rated first. Which of them is
// the one wanted is the caller's to say, by author.
func (c *Client) ByTitle(ctx context.Context, title string) ([]Book, error) {
	title = strings.TrimSpace(title)
	if title == "" {
		return nil, nil
	}
	var out struct {
		Data struct {
			Books []node `json:"books"`
		} `json:"data"`
		Errors []graphQLError `json:"errors"`
	}
	if err := c.query(ctx, titleQuery, map[string]any{"title": title}, &out); err != nil {
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
		if key := strings.ToLower(name); name != "" && !seen[key] {
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
