package komga

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"time"
)

const (
	pageSize          = 200
	maxResponseBytes  = 32 << 20
	maxPaginationPage = 100000
)

type Client struct {
	baseURL    *url.URL
	username   string
	password   string
	httpClient *http.Client
}

func NewClient(rawURL, username, password string, httpClient *http.Client) (*Client, error) {
	parsed, err := url.Parse(strings.TrimSpace(rawURL))
	if err != nil {
		return nil, fmt.Errorf("parse Komga URL: %w", err)
	}
	if parsed.Scheme != "http" && parsed.Scheme != "https" || parsed.Host == "" {
		return nil, fmt.Errorf("Komga URL must use HTTP or HTTPS")
	}
	if parsed.User != nil {
		return nil, fmt.Errorf("Komga URL must not contain credentials")
	}
	if strings.TrimSpace(username) == "" || password == "" {
		return nil, fmt.Errorf("Komga username and password are required")
	}
	if httpClient == nil {
		httpClient = http.DefaultClient
	}
	parsed.Path = strings.TrimRight(parsed.Path, "/")
	parsed.RawQuery = ""
	parsed.Fragment = ""
	return &Client{baseURL: parsed, username: username, password: password, httpClient: httpClient}, nil
}

type ReadingStateExport struct {
	SchemaVersion int              `json:"schemaVersion"`
	User          ExportUser       `json:"user"`
	Progress      []ProgressRecord `json:"progress"`
	ReadLists     []ReadListRecord `json:"readLists"`
}

type ExportUser struct {
	ID    string `json:"id"`
	Email string `json:"email,omitempty"`
}

type ProgressRecord struct {
	BookID       string    `json:"bookId"`
	FileHash     string    `json:"fileHash,omitempty"`
	LibraryID    string    `json:"libraryId,omitempty"`
	SeriesID     string    `json:"seriesId,omitempty"`
	SeriesTitle  string    `json:"seriesTitle,omitempty"`
	Title        string    `json:"title,omitempty"`
	Number       string    `json:"number,omitempty"`
	ISBN         string    `json:"isbn,omitempty"`
	Page         int       `json:"page"`
	PageCount    int       `json:"pageCount"`
	Completed    bool      `json:"completed"`
	LastModified time.Time `json:"lastModified"`
	ReadDate     time.Time `json:"readDate"`
}

type ReadListRecord struct {
	ID      string   `json:"id"`
	Name    string   `json:"name"`
	Ordered bool     `json:"ordered"`
	BookIDs []string `json:"bookIds"`
}

type komgaBook struct {
	ID          string `json:"id"`
	FileHash    string `json:"fileHash"`
	LibraryID   string `json:"libraryId"`
	Name        string `json:"name"`
	SeriesID    string `json:"seriesId"`
	SeriesTitle string `json:"seriesTitle"`
	Metadata    struct {
		Title  string `json:"title"`
		Number string `json:"number"`
		ISBN   string `json:"isbn"`
	} `json:"metadata"`
	Media struct {
		PagesCount int `json:"pagesCount"`
	} `json:"media"`
	ReadProgress *struct {
		Page         int       `json:"page"`
		Completed    bool      `json:"completed"`
		LastModified time.Time `json:"lastModified"`
		ReadDate     time.Time `json:"readDate"`
	} `json:"readProgress"`
}

type bookPage struct {
	Content    []komgaBook `json:"content"`
	Last       bool        `json:"last"`
	Number     int         `json:"number"`
	TotalPages int         `json:"totalPages"`
}

type komgaReadList struct {
	ID      string `json:"id"`
	Name    string `json:"name"`
	Ordered bool   `json:"ordered"`
}

// ExportReadingState reads the current Komga user's progress and visible read
// lists. Komga returns progress in the authenticated user's context, so callers
// run one export per account instead of using an administrator to impersonate
// other users.
func (client *Client) ExportReadingState(ctx context.Context) (ReadingStateExport, error) {
	result := ReadingStateExport{SchemaVersion: 1}
	if err := client.getJSON(ctx, "/api/v2/users/me", nil, &result.User); err != nil {
		return ReadingStateExport{}, fmt.Errorf("export Komga user: %w", err)
	}

	books, err := client.listBooks(ctx)
	if err != nil {
		return ReadingStateExport{}, err
	}
	for _, book := range books {
		if book.ReadProgress == nil {
			continue
		}
		title := strings.TrimSpace(book.Metadata.Title)
		if title == "" {
			title = strings.TrimSpace(book.Name)
		}
		result.Progress = append(result.Progress, ProgressRecord{
			BookID: book.ID, FileHash: book.FileHash, LibraryID: book.LibraryID,
			SeriesID: book.SeriesID, SeriesTitle: book.SeriesTitle, Title: title,
			Number: book.Metadata.Number, ISBN: book.Metadata.ISBN,
			Page: book.ReadProgress.Page, PageCount: book.Media.PagesCount,
			Completed: book.ReadProgress.Completed, LastModified: book.ReadProgress.LastModified,
			ReadDate: book.ReadProgress.ReadDate,
		})
	}
	sort.Slice(result.Progress, func(i, j int) bool { return result.Progress[i].BookID < result.Progress[j].BookID })

	var lists []komgaReadList
	if err := client.getJSON(ctx, "/api/v1/readlists", nil, &lists); err != nil {
		return ReadingStateExport{}, fmt.Errorf("export Komga read lists: %w", err)
	}
	for _, list := range lists {
		path := "/api/v1/readlists/" + url.PathEscape(list.ID) + "/books"
		var page bookPage
		if err := client.getJSON(ctx, path, url.Values{"unpaged": []string{"true"}}, &page); err != nil {
			return ReadingStateExport{}, fmt.Errorf("export Komga read list %q: %w", list.ID, err)
		}
		record := ReadListRecord{ID: list.ID, Name: list.Name, Ordered: list.Ordered}
		for _, book := range page.Content {
			record.BookIDs = append(record.BookIDs, book.ID)
		}
		result.ReadLists = append(result.ReadLists, record)
	}
	sort.Slice(result.ReadLists, func(i, j int) bool { return result.ReadLists[i].ID < result.ReadLists[j].ID })
	return result, nil
}

func (client *Client) listBooks(ctx context.Context) ([]komgaBook, error) {
	// BookSearch has no required fields in Komga's OpenAPI contract. An empty
	// object requests the complete visible catalog without relying on the
	// server's interpretation of an empty AnyOf condition.
	body := map[string]any{}
	books := make([]komgaBook, 0)
	for pageNumber := 0; pageNumber < maxPaginationPage; pageNumber++ {
		query := url.Values{
			"page": []string{strconv.Itoa(pageNumber)},
			"size": []string{strconv.Itoa(pageSize)},
			"sort": []string{"id,asc"},
		}
		var page bookPage
		if err := client.postJSON(ctx, "/api/v1/books/list", query, body, &page); err != nil {
			return nil, fmt.Errorf("export Komga progress page %d: %w", pageNumber, err)
		}
		books = append(books, page.Content...)
		if page.Last || page.TotalPages == 0 || pageNumber+1 >= page.TotalPages {
			return books, nil
		}
	}
	return nil, fmt.Errorf("export Komga progress exceeded pagination limit")
}

func (client *Client) getJSON(ctx context.Context, path string, query url.Values, destination any) error {
	return client.doJSON(ctx, http.MethodGet, path, query, nil, destination)
}

func (client *Client) postJSON(ctx context.Context, path string, query url.Values, body any, destination any) error {
	raw, err := json.Marshal(body)
	if err != nil {
		return err
	}
	return client.doJSON(ctx, http.MethodPost, path, query, bytes.NewReader(raw), destination)
}

func (client *Client) doJSON(ctx context.Context, method, requestPath string, query url.Values, body io.Reader, destination any) error {
	requestURL := *client.baseURL
	requestURL.Path = strings.TrimRight(client.baseURL.Path, "/") + requestPath
	requestURL.RawQuery = query.Encode()
	request, err := http.NewRequestWithContext(ctx, method, requestURL.String(), body)
	if err != nil {
		return err
	}
	request.SetBasicAuth(client.username, client.password)
	request.Header.Set("Accept", "application/json")
	if body != nil {
		request.Header.Set("Content-Type", "application/json")
	}
	response, err := client.httpClient.Do(request)
	if err != nil {
		return fmt.Errorf("Komga request failed: %w", err)
	}
	defer response.Body.Close()
	if response.StatusCode < http.StatusOK || response.StatusCode >= http.StatusMultipleChoices {
		_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, maxResponseBytes))
		return fmt.Errorf("Komga returned HTTP %d", response.StatusCode)
	}
	decoder := json.NewDecoder(io.LimitReader(response.Body, maxResponseBytes))
	if err := decoder.Decode(destination); err != nil {
		return fmt.Errorf("decode Komga response: %w", err)
	}
	return nil
}
