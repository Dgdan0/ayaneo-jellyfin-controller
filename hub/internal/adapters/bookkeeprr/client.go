// Package bookkeeprr provides the read-only discovery boundary for BookKeeprr.
package bookkeeprr

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"sync"

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
	base  *httpx.Base
	admin *httpx.Base
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
	client := &Client{base: base}
	if strings.TrimSpace(cfg.Username) != "" && cfg.Password.IsSet() {
		unauthenticated, err := httpx.New(httpx.Options{
			Name: "bookkeeprr", BaseURL: cfg.BaseURL, Auth: httpx.NoAuth{},
			Timeout: cfg.Timeout.OrDefault(0), InsecureSkipVerify: cfg.InsecureSkipVerify,
		})
		if err != nil {
			return nil, err
		}
		auth := &mobileAdminAuth{
			base: unauthenticated, username: strings.TrimSpace(cfg.Username), password: cfg.Password.Reveal(),
		}
		admin, err := httpx.New(httpx.Options{
			Name: "bookkeeprr", BaseURL: cfg.BaseURL, Auth: auth,
			Timeout: cfg.Timeout.OrDefault(0), InsecureSkipVerify: cfg.InsecureSkipVerify,
		})
		if err != nil {
			return nil, err
		}
		client.admin = admin
	}
	return client, nil
}

type mobileAdminAuth struct {
	mu       sync.Mutex
	base     *httpx.Base
	username string
	password string
	token    string
}

func (a *mobileAdminAuth) Apply(req *http.Request) error {
	token, err := a.ensure(req.Context())
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+token)
	return nil
}

func (a *mobileAdminAuth) Reauth(ctx context.Context, resp *http.Response) (bool, error) {
	if resp.StatusCode != http.StatusUnauthorized {
		return false, nil
	}
	failedToken := strings.TrimPrefix(resp.Request.Header.Get("Authorization"), "Bearer ")
	a.mu.Lock()
	// Another concurrent request may already have replaced the failed token.
	// Keep that fresh credential rather than forcing a second login that could
	// invalidate or race it.
	if a.token != "" && a.token != failedToken {
		a.mu.Unlock()
		return true, nil
	}
	a.token = ""
	a.mu.Unlock()
	_, err := a.ensure(ctx)
	return err == nil, err
}

func (a *mobileAdminAuth) ensure(ctx context.Context) (string, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.token != "" {
		return a.token, nil
	}
	var login struct {
		RedirectTo   string `json:"redirect_to"`
		RequiresTOTP bool   `json:"requiresTotp"`
	}
	err := a.base.PostJSON(ctx, "/api/auth/login", map[string]string{
		"username": a.username, "password": a.password, "return_to": "bookkeeprr://hub/auth",
	}, &login)
	if err != nil {
		return "", err
	}
	if login.RequiresTOTP {
		return "", fmt.Errorf("bookkeeprr: the Hub service account cannot use interactive two-factor authentication")
	}
	redirect, err := url.Parse(login.RedirectTo)
	if err != nil || redirect.Scheme != "bookkeeprr" {
		return "", fmt.Errorf("bookkeeprr: login did not return a mobile exchange code")
	}
	code := redirect.Query().Get("exchange")
	if code == "" {
		return "", fmt.Errorf("bookkeeprr: login did not return a mobile exchange code")
	}
	var exchanged struct {
		Token string `json:"token"`
	}
	if err := a.base.PostJSON(ctx, "/api/mobile/exchange", map[string]string{"exchange_code": code}, &exchanged); err != nil {
		return "", err
	}
	if exchanged.Token == "" {
		return "", fmt.Errorf("bookkeeprr: mobile exchange returned no token")
	}
	a.token = exchanged.Token
	return a.token, nil
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
	Results []Item         `json:"results"`
	TookMS  int            `json:"tookMs"`
	Errors  ProviderErrors `json:"errors"`
}

type ProviderError struct {
	Source  string `json:"source"`
	Message string `json:"message"`
}

// ProviderErrors accepts both BookKeeprr response shapes. Version 1.1.1 uses
// a provider-name-to-message object, while older responses and fixtures used
// an array of explicit objects. A healthy search may also return null.
type ProviderErrors []ProviderError

func (e *ProviderErrors) UnmarshalJSON(data []byte) error {
	data = bytes.TrimSpace(data)
	if len(data) == 0 || bytes.Equal(data, []byte("null")) {
		*e = ProviderErrors{}
		return nil
	}
	if data[0] == '[' {
		var values []ProviderError
		if err := json.Unmarshal(data, &values); err != nil {
			return err
		}
		*e = values
		return nil
	}
	var keyed map[string]string
	if err := json.Unmarshal(data, &keyed); err != nil {
		return fmt.Errorf("provider errors: %w", err)
	}
	keys := make([]string, 0, len(keyed))
	for source := range keyed {
		keys = append(keys, source)
	}
	sort.Strings(keys)
	values := make(ProviderErrors, 0, len(keys))
	for _, source := range keys {
		values = append(values, ProviderError{Source: source, Message: keyed[source]})
	}
	*e = values
	return nil
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
	MalID       *int        `json:"malId,omitempty"`
	Sources     ItemSources `json:"sources,omitempty"`
}

type ItemSources struct {
	Anilist      *int   `json:"anilist,omitempty"`
	Mangadex     string `json:"mangadex,omitempty"`
	Mal          *int   `json:"mal,omitempty"`
	Comicvine    *int   `json:"comicvine,omitempty"`
	OpenLibrary  string `json:"openlibrary,omitempty"`
	Audnex       string `json:"audnex,omitempty"`
	NovelUpdates string `json:"novelupdates,omitempty"`
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

type QualityProfile struct {
	ID                     int    `json:"id"`
	Name                   string `json:"name"`
	PreferCompleteBatches  bool   `json:"preferCompleteBatches"`
	PreferredGroupsJSON    string `json:"preferredGroupsJson"`
	PreferredLanguagesJSON string `json:"preferredLanguagesJson"`
	MinSizeMB              *int   `json:"minSizeMb"`
	MaxSizeMB              *int   `json:"maxSizeMb"`
	PreferOriginals        bool   `json:"preferOriginals"`
	IsDefault              bool   `json:"isDefault"`
}

type DownloadSeries struct {
	ID          int         `json:"id"`
	Title       string      `json:"title"`
	CoverURL    string      `json:"coverUrl,omitempty"`
	ContentType ContentType `json:"contentType"`
}

type DownloadRelease struct {
	ID          int    `json:"id"`
	Title       string `json:"title"`
	IndexerGUID string `json:"indexerGuid"`
	IndexerName string `json:"indexerName,omitempty"`
	IndexerKind string `json:"indexerKind,omitempty"`
}

type Download struct {
	ID            int              `json:"id"`
	QBTHash       string           `json:"qbtHash,omitempty"`
	Status        string           `json:"status"`
	AddedAt       string           `json:"addedAt"`
	CompletedAt   *string          `json:"completedAt"`
	ImportedAt    *string          `json:"importedAt"`
	Error         *string          `json:"error"`
	Progress      *float64         `json:"progress"`
	DownloadSpeed *int64           `json:"downloadSpeed"`
	ETA           *int64           `json:"eta"`
	Seeds         *int             `json:"seeds"`
	SizeBytes     *int64           `json:"sizeBytes"`
	Release       *DownloadRelease `json:"release"`
	Series        *DownloadSeries  `json:"series"`
}

type DownloadsResponse struct {
	Downloads []Download `json:"downloads"`
}

// CreateSeriesRequest mirrors BookKeeprr's discriminated /api/series body.
// Callers populate only the fields belonging to the selected content type.
type CreateSeriesRequest struct {
	ContentType ContentType `json:"contentType"`
	Flow        string      `json:"flow,omitempty"`
	OLID        string      `json:"olid,omitempty"`
	ISBN        string      `json:"isbn,omitempty"`
	Author      string      `json:"author,omitempty"`
	Title       string      `json:"title,omitempty"`
	Year        int         `json:"year,omitempty"`
	CoverURL    string      `json:"coverUrl,omitempty"`
	Description string      `json:"description,omitempty"`

	TitleEnglish     string `json:"titleEnglish,omitempty"`
	TitleRomaji      string `json:"titleRomaji,omitempty"`
	TitleNative      string `json:"titleNative,omitempty"`
	Status           string `json:"status,omitempty"`
	AnilistID        *int   `json:"anilistId,omitempty"`
	MalID            *int   `json:"malId,omitempty"`
	MangadexID       string `json:"mangadexId,omitempty"`
	ComicvineID      int    `json:"comicvineId,omitempty"`
	Publisher        string `json:"publisher,omitempty"`
	StartYear        int    `json:"startYear,omitempty"`
	NovelUpdatesSlug string `json:"novelUpdatesSlug,omitempty"`
	ASIN             string `json:"asin,omitempty"`

	TotalVolumes     int    `json:"totalVolumes,omitempty"`
	QualityProfileID int    `json:"qualityProfileId"`
	Monitoring       string `json:"monitoring,omitempty"`
}

type CreatedSeries struct {
	ID int `json:"id"`
}

type GrabbedRelease struct {
	DownloadID int    `json:"downloadId"`
	QBTHash    string `json:"qbtHash"`
	Status     string `json:"status"`
}

func (c *Client) CanRequest() bool { return c != nil && c.admin != nil }

func (c *Client) QualityProfiles(ctx context.Context) ([]QualityProfile, error) {
	out := []QualityProfile{}
	if err := c.base.GetJSON(ctx, "/api/quality-profiles", nil, &out); err != nil {
		return nil, err
	}
	return out, nil
}

func (c *Client) Downloads(ctx context.Context) (*DownloadsResponse, error) {
	out := &DownloadsResponse{Downloads: []Download{}}
	if err := c.base.GetJSON(ctx, "/api/downloads", nil, out); err != nil {
		return nil, err
	}
	return out, nil
}

func (c *Client) CreateSeries(ctx context.Context, request CreateSeriesRequest) (*CreatedSeries, error) {
	if c.admin == nil {
		return nil, &httpx.Error{
			Service: "bookkeeprr", Kind: httpx.KindAuth,
			Err: fmt.Errorf("admin username and password are not configured"),
		}
	}
	out := &CreatedSeries{}
	if err := c.admin.PostJSON(ctx, "/api/series", request, out); err != nil {
		return nil, err
	}
	return out, nil
}

func (c *Client) CancelDownload(ctx context.Context, qbtHash string) error {
	if c == nil || c.admin == nil {
		return &httpx.Error{Service: "bookkeeprr", Kind: httpx.KindAuth, Err: fmt.Errorf("admin username and password are not configured")}
	}
	qbtHash = strings.TrimSpace(qbtHash)
	if qbtHash == "" {
		return fmt.Errorf("bookkeeprr: download hash is required")
	}
	var out struct {
		OK bool `json:"ok"`
	}
	return c.admin.DeleteJSON(ctx, "/api/downloads/"+url.PathEscape(qbtHash), &out)
}

func (c *Client) GrabRelease(ctx context.Context, releaseID int) (*GrabbedRelease, error) {
	if c == nil || c.admin == nil {
		return nil, &httpx.Error{Service: "bookkeeprr", Kind: httpx.KindAuth, Err: fmt.Errorf("admin username and password are not configured")}
	}
	if releaseID <= 0 {
		return nil, fmt.Errorf("bookkeeprr: release id must be positive")
	}
	out := &GrabbedRelease{}
	if err := c.admin.PostJSON(ctx, "/api/releases/"+strconv.Itoa(releaseID)+"/grab", map[string]any{}, out); err != nil {
		return nil, err
	}
	return out, nil
}
