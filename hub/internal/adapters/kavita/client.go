// Package kavita provides the authenticated read-only catalog boundary for
// Kavita. Mutations and raw filesystem paths intentionally stay outside it.
package kavita

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"

	"ayaneohub/internal/config"
	"ayaneohub/internal/httpx"
)

type Client struct{ base *httpx.Base }

func New(cfg config.ServiceConfig) (*Client, error) {
	base, err := httpx.New(httpx.Options{
		Name: "kavita", BaseURL: cfg.BaseURL,
		Auth:    httpx.HeaderAuth{Headers: map[string]string{"X-Api-Key": cfg.APIKey.Reveal()}},
		Timeout: cfg.Timeout.OrDefault(0), InsecureSkipVerify: cfg.InsecureSkipVerify,
	})
	if err != nil {
		return nil, err
	}
	return &Client{base: base}, nil
}

type Library struct {
	ID         int    `json:"id"`
	Name       string `json:"name"`
	Type       int    `json:"type"`
	CoverImage string `json:"coverImage,omitempty"`
}

type Series struct {
	ID         int     `json:"id"`
	Name       string  `json:"name"`
	SortName   string  `json:"sortName"`
	LibraryID  int     `json:"libraryId"`
	Pages      int     `json:"pages"`
	PagesRead  int     `json:"pagesRead"`
	Format     int     `json:"format"`
	Created    string  `json:"created,omitempty"`
	UserRating float64 `json:"userRating,omitempty"`
}

type Person struct {
	Name string `json:"name"`
}

type Tag struct {
	Title string `json:"title"`
}

type Metadata struct {
	Summary     string   `json:"summary,omitempty"`
	ReleaseYear int      `json:"releaseYear,omitempty"`
	Language    string   `json:"language,omitempty"`
	Writers     []Person `json:"writers,omitempty"`
	Genres      []Tag    `json:"genres,omitempty"`
}

type Chapter struct {
	ID          int      `json:"id"`
	Title       string   `json:"title"`
	Number      string   `json:"number"`
	Pages       int      `json:"pages"`
	PagesRead   int      `json:"pagesRead"`
	Format      int      `json:"format"`
	ISBN        string   `json:"isbn,omitempty"`
	IsSpecial   bool     `json:"isSpecial"`
	Language    string   `json:"language,omitempty"`
	Summary     string   `json:"summary,omitempty"`
	ReleaseDate string   `json:"releaseDate,omitempty"`
	Writers     []Person `json:"writers,omitempty"`
}

type Volume struct {
	ID        int       `json:"id"`
	Name      string    `json:"name"`
	Number    float64   `json:"number"`
	Pages     int       `json:"pages"`
	PagesRead int       `json:"pagesRead"`
	Chapters  []Chapter `json:"chapters"`
}

type Detail struct {
	Series   Series
	Metadata Metadata
	Volumes  []Volume
	Continue Chapter
}

type Sort string
type Direction string

const (
	SortTitle    Sort      = "title"
	SortSeries   Sort      = "series"
	SortAdded    Sort      = "added"
	SortProgress Sort      = "progress"
	SortLastRead Sort      = "last_read"
	Ascending    Direction = "asc"
	Descending   Direction = "desc"
)

type FilterStatement struct {
	Comparison int    `json:"comparison"`
	Field      int    `json:"field"`
	Value      string `json:"value"`
}

type SortOption struct {
	SortField   int  `json:"sortField"`
	IsAscending bool `json:"isAscending"`
}

type SeriesFilter struct {
	Statements  []FilterStatement `json:"statements"`
	Combination int               `json:"combination"`
	SortOptions *SortOption       `json:"sortOptions"`
	EntityType  int               `json:"entityType"`
	LimitTo     int               `json:"limitTo"`
}

type SeriesPage struct {
	Page       int
	PageSize   int
	Total      int
	TotalPages int
	Items      []Series
}

type pagination struct {
	CurrentPage  int `json:"currentPage"`
	ItemsPerPage int `json:"itemsPerPage"`
	TotalItems   int `json:"totalItems"`
	TotalPages   int `json:"totalPages"`
}

func (c *Client) Libraries(ctx context.Context) ([]Library, error) {
	var out []Library
	if err := c.base.GetJSON(ctx, "/api/Library/libraries", nil, &out); err != nil {
		return nil, err
	}
	return out, nil
}

func (c *Client) Series(ctx context.Context, libraryID, page, pageSize int, sortBy Sort, direction Direction) (*SeriesPage, error) {
	if libraryID <= 0 || page < 1 || pageSize < 1 || pageSize > 200 {
		return nil, fmt.Errorf("kavita: invalid series page request")
	}
	sortField, err := sortField(sortBy)
	if err != nil {
		return nil, err
	}
	if direction != Ascending && direction != Descending {
		return nil, fmt.Errorf("kavita: unsupported direction %q", direction)
	}
	body := SeriesFilter{
		Statements:  []FilterStatement{{Comparison: 0, Field: 19, Value: strconv.Itoa(libraryID)}},
		Combination: 1, EntityType: 0, LimitTo: 0,
		SortOptions: &SortOption{SortField: sortField, IsAscending: direction == Ascending},
	}
	query := url.Values{"PageNumber": []string{strconv.Itoa(page)}, "PageSize": []string{strconv.Itoa(pageSize)}}
	var items []Series
	headers, err := c.base.PostJSONHeaders(ctx, "/api/Series/v2", query, body, &items)
	if err != nil {
		return nil, err
	}
	meta := pagination{CurrentPage: page, ItemsPerPage: pageSize, TotalItems: len(items), TotalPages: page}
	if raw := headers.Get("Pagination"); raw != "" {
		if err := json.Unmarshal([]byte(raw), &meta); err != nil {
			return nil, fmt.Errorf("kavita: decoding pagination: %w", err)
		}
	}
	return &SeriesPage{Page: meta.CurrentPage, PageSize: meta.ItemsPerPage, Total: meta.TotalItems, TotalPages: meta.TotalPages, Items: items}, nil
}

func sortField(value Sort) (int, error) {
	switch value {
	case SortTitle, SortSeries:
		return 1, nil
	case SortAdded:
		return 2, nil
	case SortProgress, SortLastRead:
		return 7, nil
	default:
		return 0, fmt.Errorf("kavita: unsupported sort %q", value)
	}
}

func (c *Client) Detail(ctx context.Context, seriesID int) (*Detail, error) {
	if seriesID <= 0 {
		return nil, fmt.Errorf("kavita: invalid series id")
	}
	var series Series
	if err := c.base.GetJSON(ctx, "/api/Series/"+strconv.Itoa(seriesID), nil, &series); err != nil {
		return nil, err
	}
	query := url.Values{"seriesId": []string{strconv.Itoa(seriesID)}}
	var metadata Metadata
	if err := c.base.GetJSON(ctx, "/api/Series/metadata", query, &metadata); err != nil {
		return nil, err
	}
	var volumes []Volume
	if err := c.base.GetJSON(ctx, "/api/Series/volumes", query, &volumes); err != nil {
		return nil, err
	}
	var continueAt Chapter
	if err := c.base.GetJSON(ctx, "/api/Reader/continue-point", query, &continueAt); err != nil {
		return nil, err
	}
	return &Detail{Series: series, Metadata: metadata, Volumes: volumes, Continue: continueAt}, nil
}

func (c *Client) Cover(ctx context.Context, seriesID int) ([]byte, string, error) {
	if seriesID <= 0 {
		return nil, "", fmt.Errorf("kavita: invalid series id")
	}
	resp, err := c.base.Open(ctx, http.MethodGet, "/api/Image/series-cover", url.Values{"seriesId": []string{strconv.Itoa(seriesID)}}, nil)
	if err != nil {
		return nil, "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, "", &httpx.Error{Service: "kavita", Status: resp.StatusCode, Kind: statusKind(resp.StatusCode)}
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, 8<<20))
	if err != nil {
		return nil, "", err
	}
	contentType := strings.TrimSpace(strings.Split(resp.Header.Get("Content-Type"), ";")[0])
	if contentType == "" {
		contentType = http.DetectContentType(body)
	}
	if !strings.HasPrefix(strings.ToLower(contentType), "image/") {
		return nil, "", fmt.Errorf("kavita: cover is not an image")
	}
	return body, contentType, nil
}

func statusKind(status int) httpx.Kind {
	switch status {
	case http.StatusUnauthorized, http.StatusForbidden:
		return httpx.KindAuth
	case http.StatusNotFound:
		return httpx.KindNotFound
	default:
		if status >= 500 {
			return httpx.KindUpstream5xx
		}
		return httpx.KindBadRequest
	}
}
