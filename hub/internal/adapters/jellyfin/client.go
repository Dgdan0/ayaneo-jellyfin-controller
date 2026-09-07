package jellyfin

import (
	"context"
	"fmt"
	"net/url"
	"strconv"

	"ayaneohub/internal/config"
	"ayaneohub/internal/httpx"
)

// The Jellyfin adapter.
//
// Two things about this server are settled by measurement rather than assumed,
// and both shaped what is here:
//
//   - **There is no provider-id query.** The public API exposes only
//     `hasTmdbId` and friends as *booleans*; you cannot ask "which item has
//     tmdbId 27205". So the hub keeps its own index, built by sweeping every
//     item once. On this library that sweep is 250 items, 436 KB and **362ms**,
//     which is why `internal/index` is a plain map and not the SQLite database
//     the plan called for.
//   - **`/Items/Latest` answers a bare array**, where every other paged query
//     answers an object with `Items` and `TotalRecordCount`. Decoding it as a
//     page silently yields nothing.
type Client struct {
	base   *httpx.Base
	userID string
}

func New(cfg config.ServiceConfig) (*Client, error) {
	base, err := httpx.New(httpx.Options{
		Name:    "jellyfin",
		BaseURL: cfg.BaseURL,
		// X-Emby-Token, not X-Api-Key. Jellyfin also accepts an `api_key` query
		// parameter, which is what its own embedded image URLs use.
		Auth:               httpx.HeaderAuth{Headers: map[string]string{"X-Emby-Token": cfg.APIKey.Reveal()}},
		Timeout:            cfg.Timeout.OrDefault(0),
		InsecureSkipVerify: cfg.InsecureSkipVerify,
		// /System/Info embeds the full changelog of every installed plugin and
		// runs to hundreds of KB, and a full library sweep is 436 KB here.
		MaxBody: 8 << 20,
	})
	if err != nil {
		return nil, err
	}
	return &Client{base: base, userID: cfg.UserID}, nil
}

// UserID is whose watch state the hub surfaces. Empty is a configuration
// problem rather than a runtime one, and the caller says so.
func (c *Client) UserID() string { return c.userID }

func (c *Client) Info(ctx context.Context) (*SystemInfo, error) {
	out := &SystemInfo{}
	if err := c.base.GetJSON(ctx, "/System/Info", nil, out); err != nil {
		return nil, err
	}
	return out, nil
}

func (c *Client) Users(ctx context.Context) ([]User, error) {
	var out []User
	if err := c.base.GetJSON(ctx, "/Users", nil, &out); err != nil {
		return nil, err
	}
	return out, nil
}

// Views are the library's top level: "Movies", "Shows", "Anime", and on this
// install two Marvel collections as well.
func (c *Client) Views(ctx context.Context) ([]Item, error) {
	if err := c.requireUser(); err != nil {
		return nil, err
	}
	out := &ItemsPage{}
	query := url.Values{"userId": {c.userID}}
	if err := c.base.GetJSON(ctx, "/UserViews", query, out); err != nil {
		return nil, err
	}
	return out.Items, nil
}

// ItemsQuery is the one query builder, because /Items takes a dozen parameters
// and spelling them at each call site is how they drift apart.
type ItemsQuery struct {
	ParentID   string
	Types      string // "Movie,Series"
	Filters    string // "IsResumable", "IsFavorite"
	SortBy     string // "SortName", "DateCreated", "DatePlayed"
	SortOrder  string // "Ascending", "Descending"
	Limit      int
	StartIndex int
	Recursive  bool
	SearchTerm string
	// Requested explicitly: Jellyfin omits ProviderIds unless asked, and the
	// whole index depends on it.
	Fields string
}

func (q ItemsQuery) values(userID string) url.Values {
	values := url.Values{"userId": {userID}}
	if q.Recursive {
		values.Set("recursive", "true")
	}
	set := func(k, v string) {
		if v != "" {
			values.Set(k, v)
		}
	}
	set("parentId", q.ParentID)
	set("includeItemTypes", q.Types)
	set("filters", q.Filters)
	set("sortBy", q.SortBy)
	set("sortOrder", q.SortOrder)
	set("searchTerm", q.SearchTerm)
	set("fields", q.Fields)
	if q.Limit > 0 {
		values.Set("limit", strconv.Itoa(q.Limit))
	}
	if q.StartIndex > 0 {
		values.Set("startIndex", strconv.Itoa(q.StartIndex))
	}
	return values
}

// Items is the general paged query.
//
// `/Items?userId=` rather than `/Users/{id}/Items`: the latter is gone in the
// published 12.0.0 spec, and this form works on both.
func (c *Client) Items(ctx context.Context, q ItemsQuery) (*ItemsPage, error) {
	if err := c.requireUser(); err != nil {
		return nil, err
	}
	out := &ItemsPage{}
	if err := c.base.GetJSON(ctx, "/Items", q.values(c.userID), out); err != nil {
		return nil, err
	}
	return out, nil
}

// Sweep reads the whole library, for the provider-id index.
//
// One request rather than paging: 250 items came back in 362ms here. The limit
// is generous but finite, so a library that outgrows it degrades to a partial
// index rather than an unbounded read.
func (c *Client) Sweep(ctx context.Context) (*ItemsPage, error) {
	return c.Items(ctx, ItemsQuery{
		Recursive: true,
		Types:     "Movie,Series",
		Fields:    "ProviderIds",
		SortBy:    "SortName",
		SortOrder: "Ascending",
		Limit:     10000,
	})
}

// Resume is "continue watching": part-watched items, most recent first.
func (c *Client) Resume(ctx context.Context, limit int) (*ItemsPage, error) {
	return c.Items(ctx, ItemsQuery{
		Recursive: true,
		Filters:   "IsResumable",
		SortBy:    "DatePlayed",
		SortOrder: "Descending",
		Fields:    "ProviderIds",
		Limit:     limit,
	})
}

// Favorites is what the user marked as a favourite.
//
// Measured empty on this install, which is worth knowing rather than
// debugging: the row stays hidden until something is starred in Jellyfin.
func (c *Client) Favorites(ctx context.Context, limit int) (*ItemsPage, error) {
	return c.Items(ctx, ItemsQuery{
		Recursive: true,
		Filters:   "IsFavorite",
		Types:     "Movie,Series",
		SortBy:    "SortName",
		SortOrder: "Ascending",
		Fields:    "ProviderIds",
		Limit:     limit,
	})
}

// NextUp is the episode after the one you finished.
//
// Jellyfin has a purpose-built endpoint for this and it is not worth deriving
// from watch state: it handles specials, gaps and season boundaries, and
// getting any of those wrong is very visible.
func (c *Client) NextUp(ctx context.Context, limit int) (*ItemsPage, error) {
	if err := c.requireUser(); err != nil {
		return nil, err
	}
	out := &ItemsPage{}
	query := url.Values{"userId": {c.userID}, "fields": {"ProviderIds"}}
	if limit > 0 {
		query.Set("limit", strconv.Itoa(limit))
	}
	if err := c.base.GetJSON(ctx, "/Shows/NextUp", query, out); err != nil {
		return nil, err
	}
	return out, nil
}

// Latest is recently added.
//
// Decoded as a **bare array**: this one endpoint does not use the paged
// envelope every other query returns, and decoding it as one yields an empty
// list with no error.
func (c *Client) Latest(ctx context.Context, limit int) ([]Item, error) {
	if err := c.requireUser(); err != nil {
		return nil, err
	}
	var out []Item
	query := url.Values{
		"userId": {c.userID},
		"fields": {"ProviderIds"},
	}
	if limit > 0 {
		query.Set("limit", strconv.Itoa(limit))
	}
	if err := c.base.GetJSON(ctx, "/Items/Latest", query, &out); err != nil {
		return nil, err
	}
	return out, nil
}

// ImagePath is the upstream path for one image, for the hub's proxy to fetch.
func ImagePath(itemID, imageType string) string {
	return fmt.Sprintf("/Items/%s/Images/%s", itemID, imageType)
}

func (c *Client) FetchImage(
	ctx context.Context, itemID, imageType string, maxWidth int, tag string,
) ([]byte, string, error) {
	query := url.Values{}
	// Jellyfin re-encodes at quality 90 by default, which put a 360px-wide
	// poster at 150 KB -- four times TMDB's equivalent, and a poster row is
	// two dozen of them over a phone connection. 82 is visually
	// indistinguishable at this size.
	query.Set("quality", "82")
	if maxWidth > 0 {
		query.Set("maxWidth", strconv.Itoa(maxWidth))
	}
	if tag != "" {
		query.Set("tag", tag)
	}
	return c.base.GetBytes(ctx, ImagePath(itemID, imageType), query)
}

func (c *Client) requireUser() error {
	if c.userID == "" {
		return fmt.Errorf("jellyfin: user_id is not configured")
	}
	return nil
}
