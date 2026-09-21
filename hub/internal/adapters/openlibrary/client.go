// Package openlibrary resolves a concrete Open Library work into a verified
// series roster. It is deliberately small: discovery stays in BookKeeprr and
// this client is only used before a grouped ebook request.
package openlibrary

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"sort"
	"strings"
	"time"
)

const defaultBaseURL = "https://openlibrary.org"

type Client struct {
	baseURL string
	http    *http.Client
}

type Candidate struct {
	WorkID string
	ISBN   string
	Title  string
	Author string
}

type Series struct {
	ID             string
	Name           string
	Description    string
	AuthorID       string
	Author         string
	AuthorImageURL string
	Books          []Book
}

type Book struct {
	WorkID   string
	Title    string
	AuthorID string
	Author   string
	Year     int
	ISBN     string
	CoverURL string
	Position int
}

func New(baseURL string) *Client {
	if strings.TrimSpace(baseURL) == "" {
		baseURL = defaultBaseURL
	}
	return &Client{
		baseURL: strings.TrimRight(baseURL, "/"),
		http:    &http.Client{Timeout: 12 * time.Second},
	}
}

func (c *Client) SeriesPreview(ctx context.Context, candidate Candidate) (*Series, error) {
	previews, err := c.SeriesPreviews(ctx, candidate)
	if err != nil {
		return nil, err
	}
	return &previews[0], nil
}

func (c *Client) SeriesPreviews(ctx context.Context, candidate Candidate) ([]Series, error) {
	workID := normalizeWorkID(candidate.WorkID)
	if isbn := normalizeISBN(candidate.ISBN); isbn != "" {
		var edition struct {
			Works []struct {
				Key string `json:"key"`
			} `json:"works"`
		}
		if err := c.getJSON(ctx, "/isbn/"+url.PathEscape(isbn)+".json", nil, &edition); err == nil && len(edition.Works) > 0 {
			if resolved := normalizeWorkID(edition.Works[0].Key); resolved != "" {
				workID = resolved
			}
		}
	}
	if workID == "" {
		return nil, fmt.Errorf("openlibrary: result has no work id")
	}

	var work struct {
		Series []struct {
			Series struct {
				Key string `json:"key"`
			} `json:"series"`
			Position string `json:"position"`
		} `json:"series"`
		Subjects []string `json:"subjects"`
	}
	if err := c.getJSON(ctx, "/works/"+url.PathEscape(workID)+".json", nil, &work); err != nil {
		return nil, err
	}
	if len(work.Series) == 0 && len(work.Subjects) == 0 {
		return nil, fmt.Errorf("openlibrary: no verified series is attached to this work")
	}
	type scopeCandidate struct{ id, name, description, query string }
	scopes := []scopeCandidate{}
	seenNames := map[string]bool{}
	for _, link := range work.Series {
		seriesID := normalizeSeriesID(link.Series.Key)
		if seriesID == "" {
			continue
		}
		var document struct {
			Name        string `json:"name"`
			Title       string `json:"title"`
			Description any    `json:"description"`
		}
		if err := c.getJSON(ctx, "/series/"+url.PathEscape(seriesID)+".json", nil, &document); err != nil {
			continue
		}
		name := strings.TrimSpace(document.Name)
		if name == "" {
			name = strings.TrimSpace(document.Title)
		}
		if name == "" {
			continue
		}
		normalized := strings.ToLower(strings.Join(strings.Fields(name), " "))
		seenNames[normalized] = true
		scopes = append(scopes, scopeCandidate{
			id: seriesID, name: name, description: descriptionText(document.Description),
			query: "series_key:" + seriesID,
		})
	}
	for _, subject := range work.Subjects {
		parts := strings.SplitN(subject, ":", 2)
		if len(parts) != 2 || !strings.EqualFold(strings.TrimSpace(parts[0]), "series") {
			continue
		}
		name := strings.TrimSpace(parts[1])
		if name == "" {
			continue
		}
		normalized := strings.ToLower(strings.Join(strings.Fields(name), " "))
		if seenNames[normalized] {
			continue
		}
		seenNames[normalized] = true
		digest := sha256.Sum256([]byte("openlibrary-series-subject:" + normalized))
		scopes = append(scopes, scopeCandidate{
			id: fmt.Sprintf("subject:%x", digest[:12]), name: name,
			query: `subject:"` + strings.ReplaceAll(name, `"`, "") + `"`,
		})
	}
	if len(scopes) == 0 {
		return nil, fmt.Errorf("openlibrary: series identity is missing")
	}

	previews := make([]Series, 0, len(scopes))
	for _, scope := range scopes {
		books, err := c.seriesBooks(ctx, scope.query, candidate)
		if err != nil {
			continue
		}
		containsCandidate := false
		for _, book := range books {
			if book.WorkID == workID {
				containsCandidate = true
				break
			}
		}
		if !containsCandidate {
			continue
		}
		authorID, author := "", strings.TrimSpace(candidate.Author)
		for _, book := range books {
			if authorID == "" && book.AuthorID != "" {
				authorID = book.AuthorID
			}
			if author == "" && book.Author != "" {
				author = book.Author
			}
		}
		preview := Series{
			ID: scope.id, Name: scope.name, Description: scope.description,
			AuthorID: authorID, Author: author, Books: books,
		}
		if authorID != "" {
			preview.AuthorImageURL = "https://covers.openlibrary.org/a/olid/" + url.PathEscape(authorID) + "-L.jpg?default=false"
		}
		previews = append(previews, preview)
	}
	if len(previews) == 0 {
		return nil, fmt.Errorf("openlibrary: series contains no usable books")
	}
	previews = deduplicateRosters(previews)
	sort.SliceStable(previews, func(i, j int) bool {
		if len(previews[i].Books) != len(previews[j].Books) {
			return len(previews[i].Books) > len(previews[j].Books)
		}
		return strings.ToLower(previews[i].Name) < strings.ToLower(previews[j].Name)
	})
	return previews, nil
}

func deduplicateRosters(previews []Series) []Series {
	unique := make([]Series, 0, len(previews))
	byRoster := map[string]int{}
	for _, preview := range previews {
		ids := make([]string, 0, len(preview.Books))
		for _, book := range preview.Books {
			ids = append(ids, book.WorkID)
		}
		key := strings.Join(ids, "|")
		if index, exists := byRoster[key]; exists {
			// Open Library can describe the same roster through a terse series
			// entity and a clearer subject label. Keep one choice and prefer the
			// label that gives the reader more context (for example "Trilogy").
			if len([]rune(preview.Name)) > len([]rune(unique[index].Name)) {
				unique[index] = preview
			}
			continue
		}
		byRoster[key] = len(unique)
		unique = append(unique, preview)
	}
	return unique
}

func (c *Client) seriesBooks(ctx context.Context, searchQuery string, candidate Candidate) ([]Book, error) {
	query := url.Values{}
	query.Set("q", searchQuery)
	query.Set("fields", "key,title,author_key,author_name,first_publish_year,publish_date,cover_i,isbn")
	query.Set("limit", "200")
	var search struct {
		NumFound int `json:"numFound"`
		Docs     []struct {
			Key              string   `json:"key"`
			Title            string   `json:"title"`
			AuthorKeys       []string `json:"author_key"`
			AuthorNames      []string `json:"author_name"`
			FirstPublishYear int      `json:"first_publish_year"`
			PublishDates     []string `json:"publish_date"`
			CoverID          int      `json:"cover_i"`
			ISBNs            []string `json:"isbn"`
		} `json:"docs"`
	}
	if err := c.getJSON(ctx, "/search.json", query, &search); err != nil {
		return nil, err
	}
	if search.NumFound > len(search.Docs) {
		return nil, fmt.Errorf("openlibrary: series roster is incomplete (%d of %d books)", len(search.Docs), search.NumFound)
	}
	books := make([]Book, 0, len(search.Docs))
	for _, document := range search.Docs {
		id := normalizeWorkID(document.Key)
		title := strings.TrimSpace(document.Title)
		if id == "" || title == "" {
			continue
		}
		book := Book{
			WorkID: id,
			Title:  title,
			Year:   dominantPublicationYear(document.PublishDates, document.FirstPublishYear),
		}
		if len(document.AuthorKeys) > 0 {
			book.AuthorID = normalizeAuthorID(document.AuthorKeys[0])
		}
		if len(document.AuthorNames) > 0 {
			book.Author = strings.TrimSpace(document.AuthorNames[0])
		}
		if book.Author == "" {
			book.Author = strings.TrimSpace(candidate.Author)
		}
		book.ISBN = preferredISBN(document.ISBNs)
		if document.CoverID > 0 {
			book.CoverURL = fmt.Sprintf("https://covers.openlibrary.org/b/id/%d-L.jpg", document.CoverID)
		}
		books = append(books, book)
	}
	if len(books) == 0 {
		return nil, fmt.Errorf("openlibrary: series contains no usable books")
	}
	sort.SliceStable(books, func(i, j int) bool {
		left, right := books[i], books[j]
		if left.Year != right.Year {
			if left.Year == 0 {
				return false
			}
			if right.Year == 0 {
				return true
			}
			return left.Year < right.Year
		}
		return strings.ToLower(left.Title) < strings.ToLower(right.Title)
	})
	for index := range books {
		books[index].Position = index + 1
	}
	return books, nil
}

func dominantPublicationYear(dates []string, fallback int) int {
	counts := map[int]int{}
	for _, date := range dates {
		for start := 0; start+4 <= len(date); start++ {
			chunk := date[start : start+4]
			year := 0
			valid := true
			for _, digit := range chunk {
				if digit < '0' || digit > '9' {
					valid = false
					break
				}
				year = year*10 + int(digit-'0')
			}
			if valid && year >= 1400 && year <= time.Now().Year()+2 {
				counts[year]++
				break
			}
		}
	}
	bestYear, bestCount := fallback, 1
	for year, count := range counts {
		if count > bestCount || (count == bestCount && year == fallback) {
			bestYear, bestCount = year, count
		}
	}
	return bestYear
}

func (c *Client) getJSON(ctx context.Context, path string, query url.Values, out any) error {
	target := c.baseURL + path
	if len(query) > 0 {
		target += "?" + query.Encode()
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, target, nil)
	if err != nil {
		return fmt.Errorf("openlibrary: %w", err)
	}
	request.Header.Set("Accept", "application/json")
	request.Header.Set("User-Agent", "AyaneoHub/1.0 (series preview)")
	response, err := c.http.Do(request)
	if err != nil {
		return fmt.Errorf("openlibrary: %w", err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return fmt.Errorf("openlibrary: HTTP %d", response.StatusCode)
	}
	if err := json.NewDecoder(response.Body).Decode(out); err != nil {
		return fmt.Errorf("openlibrary: invalid response: %w", err)
	}
	return nil
}

func normalizeWorkID(value string) string {
	value = strings.TrimSpace(strings.TrimSuffix(value, ".json"))
	value = strings.TrimPrefix(value, "/works/")
	if strings.HasPrefix(value, "OL") && strings.HasSuffix(value, "W") {
		return value
	}
	return ""
}

func normalizeSeriesID(value string) string {
	value = strings.TrimSpace(strings.TrimSuffix(value, ".json"))
	value = strings.TrimPrefix(value, "/series/")
	// Open Library's series entities currently use the generic OL…L suffix.
	// Accept the older …S form too because existing fixtures and cached records
	// may still carry it.
	if strings.HasPrefix(value, "OL") && (strings.HasSuffix(value, "L") || strings.HasSuffix(value, "S")) {
		return value
	}
	return ""
}

func normalizeAuthorID(value string) string {
	value = strings.TrimSpace(strings.TrimSuffix(value, ".json"))
	value = strings.TrimPrefix(value, "/authors/")
	if strings.HasPrefix(value, "OL") && strings.HasSuffix(value, "A") {
		return value
	}
	return ""
}

func normalizeISBN(value string) string {
	var out strings.Builder
	for _, char := range value {
		if char >= '0' && char <= '9' {
			out.WriteRune(char)
		}
		if (char == 'x' || char == 'X') && out.Len() == 9 {
			out.WriteRune('X')
		}
	}
	if out.Len() == 10 || out.Len() == 13 {
		return out.String()
	}
	return ""
}

func preferredISBN(values []string) string {
	for _, value := range values {
		if normalized := normalizeISBN(value); len(normalized) == 13 {
			return normalized
		}
	}
	for _, value := range values {
		if normalized := normalizeISBN(value); normalized != "" {
			return normalized
		}
	}
	return ""
}

func descriptionText(value any) string {
	switch typed := value.(type) {
	case string:
		return strings.TrimSpace(typed)
	case map[string]any:
		text, _ := typed["value"].(string)
		return strings.TrimSpace(text)
	default:
		return ""
	}
}
