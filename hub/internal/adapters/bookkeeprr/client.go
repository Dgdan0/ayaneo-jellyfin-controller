// Package bookkeeprr provides the read-only discovery boundary for BookKeeprr.
package bookkeeprr

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/url"
	"strconv"
	"strings"

	"ayaneohub/internal/config"
	"ayaneohub/internal/httpx"
)

type ContentType string

const (
	TypeAll        ContentType = "all"
	TypeEbook      ContentType = "ebook"
	TypeAudiobook  ContentType = "audiobook"
	TypeComic      ContentType = "comic"
	TypeManga      ContentType = "manga"
	TypeLightNovel ContentType = "light_novel"
)

var concreteTypes = []ContentType{TypeEbook, TypeAudiobook, TypeComic, TypeManga, TypeLightNovel}

func ConcreteTypes() []ContentType {
	return append([]ContentType(nil), concreteTypes...)
}

func ParseContentType(raw string, allowAll bool) (ContentType, error) {
	kind := ContentType(strings.TrimSpace(strings.ToLower(raw)))
	if kind == TypeAll && allowAll {
		return kind, nil
	}
	for _, valid := range concreteTypes {
		if kind == valid {
			return kind, nil
		}
	}
	return "", fmt.Errorf("bookkeeprr: unsupported content type %q", raw)
}

type Client struct {
	base *httpx.Base
}

func New(cfg config.ServiceConfig) (*Client, error) {
	base, err := httpx.New(httpx.Options{
		Name:    "bookkeeprr",
		BaseURL: cfg.BaseURL,
		Auth: httpx.HeaderAuth{Headers: map[string]string{
			"Authorization": "Bearer " + cfg.APIKey.Reveal(),
		}},
		Timeout:            cfg.Timeout.OrDefault(0),
		InsecureSkipVerify: cfg.InsecureSkipVerify,
	})
	if err != nil {
		return nil, err
	}
	return &Client{base: base}, nil
}

type BrowseResponse struct {
	Rows []BrowseRow `json:"rows"`
}

type BrowseRow struct {
	ID    string `json:"id"`
	Label string `json:"label"`
	Meta  string `json:"meta,omitempty"`
	Items []Item `json:"items"`
}

type CategoryResponse struct {
	Items   []Item `json:"items"`
	HasMore bool   `json:"hasMore"`
}

type SearchResponse struct {
	Results []Item          `json:"results"`
	TookMS  int             `json:"tookMs"`
	Errors  []ProviderError `json:"errors"`
}

type ProviderError struct {
	Source  string `json:"source"`
	Message string `json:"message"`
}

type Item struct {
	ContentType ContentType `json:"contentType"`
	Source      string      `json:"source"`
	SourceID    string      `json:"-"`
	Title       string      `json:"title"`
	Author      string      `json:"author,omitempty"`
	Year        int         `json:"year,omitempty"`
	ISBN        string      `json:"isbn,omitempty"`
	CoverURL    string      `json:"coverUrl,omitempty"`
	Description string      `json:"description,omitempty"`
	InLibrary   bool        `json:"inLib"`
}

func (i *Item) UnmarshalJSON(data []byte) error {
	type itemAlias Item
	var raw struct {
		itemAlias
		SourceID json.RawMessage `json:"sourceId"`
	}
	if err := json.Unmarshal(data, &raw); err != nil {
		return err
	}
	*i = Item(raw.itemAlias)
	if len(raw.SourceID) == 0 || bytes.Equal(raw.SourceID, []byte("null")) {
		return nil
	}
	if raw.SourceID[0] == '"' {
		return json.Unmarshal(raw.SourceID, &i.SourceID)
	}
	var number json.Number
	if err := json.Unmarshal(raw.SourceID, &number); err != nil {
		return fmt.Errorf("sourceId: %w", err)
	}
	i.SourceID = number.String()
	return nil
}

func (c *Client) Browse(ctx context.Context, contentType ContentType) (*BrowseResponse, error) {
	kind, err := ParseContentType(string(contentType), false)
	if err != nil {
		return nil, err
	}
	query := url.Values{"contentType": []string{string(kind)}}
	out := &BrowseResponse{}
	if err := c.base.GetJSON(ctx, "/api/discover/browse", query, out); err != nil {
		return nil, err
	}
	return out, nil
}

func (c *Client) Category(ctx context.Context, contentType ContentType, row string, page int) (*CategoryResponse, error) {
	kind, err := ParseContentType(string(contentType), false)
	if err != nil {
		return nil, err
	}
	row = strings.TrimSpace(row)
	if row == "" {
		return nil, fmt.Errorf("bookkeeprr: row is required")
	}
	if page < 1 {
		return nil, fmt.Errorf("bookkeeprr: page must be positive")
	}
	query := url.Values{}
	query.Set("contentType", string(kind))
	query.Set("row", row)
	query.Set("page", strconv.Itoa(page))
	out := &CategoryResponse{}
	if err := c.base.GetJSON(ctx, "/api/discover/category", query, out); err != nil {
		return nil, err
	}
	return out, nil
}

func (c *Client) Search(ctx context.Context, queryText string, contentType ContentType) (*SearchResponse, error) {
	queryText = strings.TrimSpace(queryText)
	if queryText == "" {
		return nil, fmt.Errorf("bookkeeprr: q is required")
	}
	kind, err := ParseContentType(string(contentType), true)
	if err != nil {
		return nil, err
	}
	query := url.Values{}
	query.Set("q", queryText)
	query.Set("contentType", string(kind))
	out := &SearchResponse{}
	if err := c.base.GetJSON(ctx, "/api/discover/search", query, out); err != nil {
		return nil, err
	}
	return out, nil
}
