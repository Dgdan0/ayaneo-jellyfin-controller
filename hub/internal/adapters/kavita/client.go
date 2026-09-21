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
	"time"

	"ayaneohub/internal/config"
	"ayaneohub/internal/httpx"
)

type Client struct {
	base   *httpx.Base
	apiKey string
}

func New(cfg config.ServiceConfig) (*Client, error) {
	base, err := httpx.New(httpx.Options{
		Name: "kavita", BaseURL: cfg.BaseURL,
		Auth:    httpx.HeaderAuth{Headers: map[string]string{"X-Api-Key": cfg.APIKey.Reveal()}},
		Timeout: cfg.Timeout.OrDefault(0), InsecureSkipVerify: cfg.InsecureSkipVerify,
	})
	if err != nil {
		return nil, err
	}
	return &Client{base: base, apiKey: cfg.APIKey.Reveal()}, nil
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

// ChapterInfo is the stable subset of Kavita's reader manifest needed by the
// Hub. File names deliberately stay adapter-private because clients receive
// only opaque Hub page routes.
type ChapterInfo struct {
	ChapterNumber  string          `json:"chapterNumber"`
	VolumeNumber   string          `json:"volumeNumber"`
	VolumeID       int             `json:"volumeId"`
	SeriesName     string          `json:"seriesName"`
	SeriesFormat   int             `json:"seriesFormat"`
	SeriesID       int             `json:"seriesId"`
	LibraryID      int             `json:"libraryId"`
	LibraryType    int             `json:"libraryType"`
	ChapterTitle   string          `json:"chapterTitle"`
	Pages          int             `json:"pages"`
	IsSpecial      bool            `json:"isSpecial"`
	Subtitle       string          `json:"subtitle"`
	Title          string          `json:"title"`
	PageDimensions []FileDimension `json:"pageDimensions"`
	DoublePairs    map[string]int  `json:"doublePairs"`
}

type FileDimension struct {
	Width      int    `json:"width"`
	Height     int    `json:"height"`
	PageNumber int    `json:"pageNumber"`
	FileName   string `json:"fileName"`
	IsWide     bool   `json:"isWide"`
}

type Progress struct {
	VolumeID        int    `json:"volumeId"`
	ChapterID       int    `json:"chapterId"`
	PageNum         int    `json:"pageNum"`
	SeriesID        int    `json:"seriesId"`
	LibraryID       int    `json:"libraryId"`
	BookScrollID    string `json:"bookScrollId"`
	LastModifiedUTC string `json:"lastModifiedUtc,omitempty"`
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

// ScanAll asks Kavita to rescan every configured library. A scan can take
// longer than an ordinary catalog lookup, so it gets its own bounded timeout
// without weakening the short timeout used by screen requests.
func (c *Client) ScanAll(ctx context.Context) error {
	return c.base.WithTimeout(2*time.Minute).PostJSON(ctx, "/api/Library/scan-all", nil, nil)
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

func (c *Client) ChapterInfo(ctx context.Context, chapterID int) (*ChapterInfo, error) {
	if chapterID <= 0 {
		return nil, fmt.Errorf("kavita: invalid chapter id")
	}
	query := url.Values{
		"chapterId":         []string{strconv.Itoa(chapterID)},
		"extractPdf":        []string{"false"},
		"includeDimensions": []string{"true"},
	}
	var out ChapterInfo
	// The first call may extract and cache a large archive. Kavita documents it
	// as the reader bootstrap, so the short catalog deadline is inappropriate.
	if err := c.base.WithTimeout(2*time.Minute).GetJSON(ctx, "/api/Reader/chapter-info", query, &out); err != nil {
		return nil, err
	}
	if out.SeriesID <= 0 || out.LibraryID <= 0 || out.VolumeID <= 0 || out.Pages <= 0 {
		return nil, fmt.Errorf("kavita: incomplete chapter info")
	}
	if out.DoublePairs == nil {
		out.DoublePairs = map[string]int{}
	}
	return &out, nil
}

func (c *Client) Progress(ctx context.Context, chapterID int) (*Progress, error) {
	if chapterID <= 0 {
		return nil, fmt.Errorf("kavita: invalid chapter id")
	}
	var out Progress
	if err := c.base.GetJSON(ctx, "/api/Reader/get-progress", url.Values{"chapterId": []string{strconv.Itoa(chapterID)}}, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) OpenPage(ctx context.Context, chapterID, page int) (*http.Response, error) {
	if chapterID <= 0 || page < 0 {
		return nil, fmt.Errorf("kavita: invalid page request")
	}
	response, err := c.base.Open(ctx, http.MethodGet, "/api/Reader/image", url.Values{
		"chapterId":  []string{strconv.Itoa(chapterID)},
		"page":       []string{strconv.Itoa(page)},
		"extractPdf": []string{"false"},
		// Kavita's image action authenticates with this query value rather than
		// the controller-wide X-Api-Key filter. This URL remains loopback-only
		// inside the adapter and is never returned by the Hub.
		"apiKey": []string{c.apiKey},
	}, nil)
	if err != nil {
		return nil, err
	}
	if response.StatusCode != http.StatusOK {
		response.Body.Close()
		return nil, &httpx.Error{Service: "kavita", Status: response.StatusCode, Kind: statusKind(response.StatusCode)}
	}
	return response, nil
}

func (c *Client) SaveProgress(ctx context.Context, progress Progress) error {
	if progress.VolumeID <= 0 || progress.ChapterID <= 0 || progress.PageNum < 0 ||
		progress.SeriesID <= 0 || progress.LibraryID <= 0 {
		return fmt.Errorf("kavita: invalid reader progress")
	}
	return c.base.PostJSON(ctx, "/api/Reader/progress", progress, nil)
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
	case http.StatusTooManyRequests:
		return httpx.KindRateLimited
	default:
		if status >= 500 {
			return httpx.KindUpstream5xx
		}
		return httpx.KindBadRequest
	}
}
