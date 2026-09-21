// Package storyteller provides the authenticated read-only book and edition
// boundary for Storyteller.
package storyteller

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"

	"ayaneohub/internal/config"
	"ayaneohub/internal/httpx"
)

type Client struct {
	base     *url.URL
	http     *http.Client
	scanHTTP *http.Client
	username string
	password string

	tokenMu sync.Mutex
	token   string
	expires time.Time
}

func New(cfg config.ServiceConfig) (*Client, error) {
	parsed, err := url.Parse(strings.TrimRight(cfg.BaseURL, "/"))
	if err != nil || parsed.Host == "" {
		return nil, fmt.Errorf("storyteller: invalid base URL")
	}
	transport := http.DefaultTransport.(*http.Transport).Clone()
	if cfg.InsecureSkipVerify {
		transport.TLSClientConfig = &tls.Config{InsecureSkipVerify: true}
	}
	return &Client{
		base: parsed, username: strings.TrimSpace(cfg.Username), password: cfg.Password.Reveal(),
		http:     &http.Client{Transport: transport, Timeout: cfg.Timeout.OrDefault(8 * time.Second)},
		scanHTTP: &http.Client{Transport: transport, Timeout: 2 * time.Minute},
	}, nil
}

type Creator struct {
	UUID   string `json:"uuid,omitempty"`
	Name   string `json:"name"`
	FileAs string `json:"fileAs,omitempty"`
	Role   string `json:"role,omitempty"`
}

type Series struct {
	UUID     string  `json:"uuid,omitempty"`
	Name     string  `json:"name"`
	Position float64 `json:"position,omitempty"`
}

type Identifier struct {
	Type       string `json:"type,omitempty"`
	Scheme     string `json:"scheme,omitempty"`
	Value      string `json:"value,omitempty"`
	Identifier string `json:"identifier,omitempty"`
}

type Ebook struct {
	UUID      string `json:"uuid"`
	PageCount int    `json:"pageCount,omitempty"`
	FileSize  int64  `json:"fileSize,omitempty"`
	Missing   bool   `json:"missing"`
}

type Audiobook struct {
	UUID     string  `json:"uuid"`
	Duration float64 `json:"duration,omitempty"`
	FileSize int64   `json:"fileSize,omitempty"`
	Missing  bool    `json:"missing"`
}

type Readaloud struct {
	UUID    string `json:"uuid"`
	Missing bool   `json:"missing"`
}

type Locations struct {
	Position         int      `json:"position,omitempty"`
	Progression      float64  `json:"progression,omitempty"`
	TotalProgression float64  `json:"totalProgression,omitempty"`
	Fragments        []string `json:"fragments,omitempty"`
}

type Locator struct {
	Href      string    `json:"href,omitempty"`
	Type      string    `json:"type,omitempty"`
	Title     string    `json:"title,omitempty"`
	Target    int64     `json:"target,omitempty"`
	TimeStamp int64     `json:"timeStamp,omitempty"`
	Locations Locations `json:"locations"`
}

type Position struct {
	UUID      string  `json:"uuid"`
	Locator   Locator `json:"locator"`
	Timestamp int64   `json:"timestamp"`
	UpdatedAt string  `json:"updatedAt,omitempty"`
}

type Book struct {
	ID              int64        `json:"id"`
	UUID            string       `json:"uuid"`
	Title           string       `json:"title"`
	Subtitle        string       `json:"subtitle,omitempty"`
	Description     string       `json:"description,omitempty"`
	Language        string       `json:"language,omitempty"`
	PublicationDate string       `json:"publicationDate,omitempty"`
	PageCount       int          `json:"pageCount,omitempty"`
	Duration        float64      `json:"duration,omitempty"`
	CreatedAt       string       `json:"createdAt,omitempty"`
	UpdatedAt       string       `json:"updatedAt,omitempty"`
	Authors         []Creator    `json:"authors,omitempty"`
	Narrators       []Creator    `json:"narrators,omitempty"`
	Creators        []Creator    `json:"creators,omitempty"`
	Series          []Series     `json:"series,omitempty"`
	Identifiers     []Identifier `json:"identifiers,omitempty"`
	Ebook           *Ebook       `json:"ebook,omitempty"`
	Audiobook       *Audiobook   `json:"audiobook,omitempty"`
	Readaloud       *Readaloud   `json:"readaloud,omitempty"`
	Position        *Position    `json:"position,omitempty"`
}

type tokenResponse struct {
	AccessToken string `json:"access_token"`
	TokenType   string `json:"token_type"`
	ExpiresIn   int64  `json:"expires_in"`
}

func (c *Client) Books(ctx context.Context) ([]Book, error) {
	var out []Book
	if err := c.getJSON(ctx, "/api/v2/books", &out); err != nil {
		return nil, err
	}
	return out, nil
}

func (c *Client) Book(ctx context.Context, id int64) (*Book, error) {
	if id <= 0 {
		return nil, fmt.Errorf("storyteller: invalid book id")
	}
	var out Book
	if err := c.getJSON(ctx, "/api/v2/books/"+strconv.FormatInt(id, 10), &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// ScanAll runs Storyteller's installed v2 book processing route. The service
// account needs bookProcess permission; token renewal is handled exactly like
// every other authenticated Storyteller request.
func (c *Client) ScanAll(ctx context.Context) error {
	resp, err := c.request(ctx, c.scanHTTP, http.MethodPost, "/api/v2/books/scan")
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, httpx.MaxBodyBytes))
	return nil
}

func (c *Client) Cover(ctx context.Context, id int64) ([]byte, string, error) {
	if id <= 0 {
		return nil, "", fmt.Errorf("storyteller: invalid book id")
	}
	resp, err := c.get(ctx, "/api/v2/books/"+strconv.FormatInt(id, 10)+"/cover")
	if err != nil {
		return nil, "", err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 8<<20))
	if err != nil {
		return nil, "", err
	}
	contentType := strings.TrimSpace(strings.Split(resp.Header.Get("Content-Type"), ";")[0])
	if contentType == "" {
		contentType = http.DetectContentType(body)
	}
	if !strings.HasPrefix(strings.ToLower(contentType), "image/") {
		return nil, "", fmt.Errorf("storyteller: cover is not an image")
	}
	return body, contentType, nil
}

func (c *Client) getJSON(ctx context.Context, path string, out any) error {
	resp, err := c.get(ctx, path)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, httpx.MaxBodyBytes))
	if err != nil {
		return &httpx.Error{Service: "storyteller", Kind: httpx.KindDecode, Err: err}
	}
	if err := json.Unmarshal(body, out); err != nil {
		return &httpx.Error{Service: "storyteller", Kind: httpx.KindDecode, Err: err}
	}
	return nil
}

func (c *Client) get(ctx context.Context, path string) (*http.Response, error) {
	return c.request(ctx, c.http, http.MethodGet, path)
}

func (c *Client) request(ctx context.Context, client *http.Client, method, path string) (*http.Response, error) {
	for attempt := 0; attempt < 2; attempt++ {
		token, err := c.accessToken(ctx)
		if err != nil {
			return nil, err
		}
		target := *c.base
		target.Path = strings.TrimRight(c.base.Path, "/") + "/" + strings.TrimLeft(path, "/")
		req, err := http.NewRequestWithContext(ctx, method, target.String(), nil)
		if err != nil {
			return nil, err
		}
		req.Header.Set("Accept", "application/json, image/*")
		req.Header.Set("Authorization", "Bearer "+token)
		resp, err := client.Do(req)
		if err != nil {
			return nil, &httpx.Error{Service: "storyteller", Kind: transportKind(err), Err: err}
		}
		if resp.StatusCode == http.StatusUnauthorized && attempt == 0 {
			resp.Body.Close()
			c.invalidateToken(token)
			continue
		}
		if resp.StatusCode >= 400 {
			snippet, _ := io.ReadAll(io.LimitReader(resp.Body, 2048))
			resp.Body.Close()
			return nil, &httpx.Error{Service: "storyteller", Status: resp.StatusCode, Kind: statusKind(resp.StatusCode), Err: fmt.Errorf("%s", strings.TrimSpace(string(snippet)))}
		}
		return resp, nil
	}
	return nil, &httpx.Error{Service: "storyteller", Status: http.StatusUnauthorized, Kind: httpx.KindAuth}
}

func (c *Client) accessToken(ctx context.Context) (string, error) {
	c.tokenMu.Lock()
	defer c.tokenMu.Unlock()
	if c.token != "" && time.Now().Before(c.expires) {
		return c.token, nil
	}
	if c.username == "" || c.password == "" {
		return "", &httpx.Error{Service: "storyteller", Kind: httpx.KindAuth, Err: fmt.Errorf("username and password are required")}
	}
	target := *c.base
	target.Path = strings.TrimRight(c.base.Path, "/") + "/api/v2/token"
	form := url.Values{"usernameOrEmail": []string{c.username}, "password": []string{c.password}}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, target.String(), strings.NewReader(form.Encode()))
	if err != nil {
		return "", err
	}
	req.Header.Set("Accept", "application/json")
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	resp, err := c.http.Do(req)
	if err != nil {
		return "", &httpx.Error{Service: "storyteller", Kind: transportKind(err), Err: err}
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 400 {
		return "", &httpx.Error{Service: "storyteller", Status: resp.StatusCode, Kind: statusKind(resp.StatusCode)}
	}
	var token tokenResponse
	if err := json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(&token); err != nil {
		return "", &httpx.Error{Service: "storyteller", Kind: httpx.KindDecode, Err: err}
	}
	if token.AccessToken == "" || !strings.EqualFold(token.TokenType, "bearer") {
		return "", &httpx.Error{Service: "storyteller", Kind: httpx.KindAuth, Err: fmt.Errorf("invalid token response")}
	}
	validFor := time.Duration(token.ExpiresIn) * time.Second
	if validFor <= 0 {
		validFor = 5 * time.Minute
	}
	skew := 30 * time.Second
	if validFor <= skew {
		skew = validFor / 10
	}
	c.token = token.AccessToken
	c.expires = time.Now().Add(validFor - skew)
	return c.token, nil
}

func (c *Client) invalidateToken(token string) {
	c.tokenMu.Lock()
	defer c.tokenMu.Unlock()
	if c.token == token {
		c.token = ""
		c.expires = time.Time{}
	}
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

func transportKind(err error) httpx.Kind {
	text := strings.ToLower(err.Error())
	switch {
	case strings.Contains(text, "timeout"), strings.Contains(text, "deadline exceeded"):
		return httpx.KindTimeout
	case strings.Contains(text, "connection refused"), strings.Contains(text, "no connection could be made"):
		return httpx.KindRefused
	case strings.Contains(text, "no such host"):
		return httpx.KindDNS
	case strings.Contains(text, "tls"), strings.Contains(text, "certificate"):
		return httpx.KindTLS
	default:
		return httpx.KindUnclassified
	}
}
