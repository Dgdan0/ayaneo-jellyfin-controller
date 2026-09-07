// Package jellyseerr talks to Jellyseerr, which is the single most valuable
// adapter in the hub.
//
// It is not just search: its MediaInfo already carries the identifiers that link
// a title to Radarr/Sonarr and to the Jellyfin library, and its own tracker
// polls the *arr queues, so download progress arrives here too. That is why the
// first phase of the hub is useful on its own, before any other adapter exists.
package jellyseerr

import (
	"context"
	"fmt"
	"net/url"
	"strconv"

	"ayaneohub/internal/config"
	"ayaneohub/internal/httpx"
)

type Client struct {
	base *httpx.Base
	// Sent as X-API-User. Without it every request the hub creates is attributed
	// to admin user 1 rather than to the person who actually asked for it.
	actAsUserID int
}

func New(cfg config.ServiceConfig) (*Client, error) {
	headers := map[string]string{"X-Api-Key": cfg.APIKey.Reveal()}
	if cfg.ActAsUserID > 0 {
		headers["X-API-User"] = strconv.Itoa(cfg.ActAsUserID)
	}
	base, err := httpx.New(httpx.Options{
		Name:               "jellyseerr",
		BaseURL:            cfg.BaseURL,
		Auth:               httpx.HeaderAuth{Headers: headers},
		Timeout:            cfg.Timeout.OrDefault(0),
		InsecureSkipVerify: cfg.InsecureSkipVerify,
	})
	if err != nil {
		return nil, err
	}
	return &Client{base: base, actAsUserID: cfg.ActAsUserID}, nil
}

func (c *Client) Status(ctx context.Context) (*Status, error) {
	out := &Status{}
	if err := c.base.GetJSON(ctx, "/api/v1/status", nil, out); err != nil {
		return nil, err
	}
	return out, nil
}

// Search is a multi-search across movies, TV and people.
//
// Results come back already annotated with mediaInfo where Jellyseerr knows the
// title, which is what lets the app show "you already have this" without a
// second call.
func (c *Client) Search(ctx context.Context, query string, page int) (*SearchResponse, error) {
	if page < 1 {
		page = 1
	}
	values := url.Values{}
	values.Set("query", query)
	values.Set("page", strconv.Itoa(page))

	out := &SearchResponse{}
	if err := c.base.GetJSON(ctx, "/api/v1/search", values, out); err != nil {
		return nil, err
	}
	return out, nil
}

// Trending backs the Discover screen when nothing has been searched for.
func (c *Client) Trending(ctx context.Context, page int) (*SearchResponse, error) {
	if page < 1 {
		page = 1
	}
	values := url.Values{}
	values.Set("page", strconv.Itoa(page))

	out := &SearchResponse{}
	if err := c.base.GetJSON(ctx, "/api/v1/discover/trending", values, out); err != nil {
		return nil, err
	}
	return out, nil
}

// Detail fetches one title. mediaType must be "movie" or "tv", and tmdbID is the
// TMDB id -- the same id that comes back as Result.ID.
func (c *Client) Detail(ctx context.Context, mediaType string, tmdbID int) (*Detail, error) {
	if mediaType != "movie" && mediaType != "tv" {
		return nil, fmt.Errorf("jellyseerr: mediaType must be movie or tv, got %q", mediaType)
	}
	out := &Detail{}
	path := fmt.Sprintf("/api/v1/%s/%d", mediaType, tmdbID)
	if err := c.base.GetJSON(ctx, path, nil, out); err != nil {
		return nil, err
	}
	return out, nil
}

func (c *Client) Requests(ctx context.Context, filter string, page int) (*RequestPage, error) {
	if page < 1 {
		page = 1
	}
	values := url.Values{}
	values.Set("take", "20")
	values.Set("skip", strconv.Itoa((page-1)*20))
	if filter != "" {
		values.Set("filter", filter)
	}
	out := &RequestPage{}
	if err := c.base.GetJSON(ctx, "/api/v1/request", values, out); err != nil {
		return nil, err
	}
	return out, nil
}

func (c *Client) RequestCounts(ctx context.Context) (*RequestCounts, error) {
	out := &RequestCounts{}
	if err := c.base.GetJSON(ctx, "/api/v1/request/count", nil, out); err != nil {
		return nil, err
	}
	return out, nil
}

// CreateRequest asks for a title.
//
// Not retried on failure anywhere up the stack: a timeout does not mean it did
// not happen, and a duplicate request is a real annoyance to clean up.
func (c *Client) CreateRequest(ctx context.Context, body RequestBody) (*MediaRequest, error) {
	out := &MediaRequest{}
	if err := c.base.PostJSON(ctx, "/api/v1/request", body, out); err != nil {
		return nil, err
	}
	return out, nil
}

// Detail is one title's full record.
//
// Only the fields the app actually shows are decoded; Jellyseerr returns a great
// deal more, and pulling all of it through would make the payload the slow part
// of a screen on a phone connection.
type Detail struct {
	ID               int            `json:"id"`
	Title            string         `json:"title"`
	Name             string         `json:"name"`
	Overview         string         `json:"overview"`
	ReleaseDate      string         `json:"releaseDate"`
	FirstAirDate     string         `json:"firstAirDate"`
	PosterPath       string         `json:"posterPath"`
	BackdropPath     string         `json:"backdropPath"`
	Runtime          int            `json:"runtime"`
	VoteAverage      float64        `json:"voteAverage"`
	Genres           []Genre        `json:"genres"`
	NumberOfSeasons  int            `json:"numberOfSeasons"`
	NumberOfEpisodes int            `json:"numberOfEpisodes"`
	ExternalIDs      *ExternalIDs   `json:"externalIds"`
	MediaInfo        *MediaInfo     `json:"mediaInfo"`
	Seasons          []DetailSeason `json:"seasons"`
	Credits          *Credits       `json:"credits"`
	RelatedVideos    []RelatedVideo `json:"relatedVideos"`
}

// Trailer picks the clip worth offering, or "" when there is none.
//
// A real trailer first, then a teaser; behind-the-scenes and featurettes are
// deliberately not offered, because a button labelled "Trailer" that plays a
// costume-design interview is worse than no button. YouTube only -- it is the
// one site the app can hand to an intent and expect something to happen.
func (d Detail) Trailer() (key string, watchURL string) {
	for _, want := range []string{"Trailer", "Teaser"} {
		for _, video := range d.RelatedVideos {
			if video.Type == want && video.Site == "YouTube" && video.Key != "" {
				url := video.URL
				if url == "" {
					url = "https://www.youtube.com/watch?v=" + video.Key
				}
				return video.Key, url
			}
		}
	}
	return "", ""
}

type Genre struct {
	ID   int    `json:"id"`
	Name string `json:"name"`
}

type ExternalIDs struct {
	ImdbID string `json:"imdbId"`
	TvdbID *int   `json:"tvdbId"`
}

// RelatedVideo is one TMDB clip. Types seen on this install: "Trailer",
// "Teaser", "Behind the Scenes", "Clip", "Featurette".
type RelatedVideo struct {
	Key  string `json:"key"`
	Name string `json:"name"`
	Site string `json:"site"`
	Size int    `json:"size"`
	Type string `json:"type"`
	URL  string `json:"url"`
}

type DetailSeason struct {
	ID           int    `json:"id"`
	SeasonNumber int    `json:"seasonNumber"`
	Name         string `json:"name"`
	EpisodeCount int    `json:"episodeCount"`
	AirDate      string `json:"airDate"`
}

// Seasons2 is the season list. Named around the Seasons field on api.MediaDetail
// being a count, so the two never get confused at a call site.
func (d Detail) Seasons2() []DetailSeason { return d.Seasons }

func (d Detail) DisplayTitle() string {
	if d.Title != "" {
		return d.Title
	}
	return d.Name
}

func (c *Client) Person(ctx context.Context, id int) (*Person, error) {
	out := &Person{}
	if err := c.base.GetJSON(ctx, fmt.Sprintf("/api/v1/person/%d", id), nil, out); err != nil {
		return nil, err
	}
	return out, nil
}

func (c *Client) PersonCredits(ctx context.Context, id int) (*CombinedCredits, error) {
	out := &CombinedCredits{}
	path := fmt.Sprintf("/api/v1/person/%d/combined_credits", id)
	if err := c.base.GetJSON(ctx, path, nil, out); err != nil {
		return nil, err
	}
	return out, nil
}
