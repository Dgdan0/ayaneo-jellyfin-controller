package jellyseerr

import (
	"context"
	"fmt"
	"net/url"
	"strconv"
)

// DiscoverKind is one of Jellyseerr's discover feeds.
//
// All four verified live on this install: trending answered 10000 results,
// movies 1175951, tv 230887, upcoming 3559 -- so paging has effectively no end
// and the app can scroll as long as anyone cares to.
type DiscoverKind string

const (
	DiscoverTrending DiscoverKind = "trending"
	DiscoverMovies   DiscoverKind = "movies"
	DiscoverTV       DiscoverKind = "tv"
	DiscoverUpcoming DiscoverKind = "upcoming"
)

func (k DiscoverKind) path() (string, error) {
	switch k {
	case DiscoverTrending:
		return "/api/v1/discover/trending", nil
	case DiscoverMovies:
		return "/api/v1/discover/movies", nil
	case DiscoverTV:
		return "/api/v1/discover/tv", nil
	case DiscoverUpcoming:
		return "/api/v1/discover/movies/upcoming", nil
	}
	return "", fmt.Errorf("unknown discover kind %q", k)
}

// Discover fetches one page of one feed.
func (c *Client) Discover(
	ctx context.Context, kind DiscoverKind, page int,
) (*SearchResponse, error) {
	path, err := kind.path()
	if err != nil {
		return nil, err
	}
	if page < 1 {
		page = 1
	}
	values := url.Values{}
	values.Set("page", strconv.Itoa(page))

	out := &SearchResponse{}
	if err := c.base.GetJSON(ctx, path, values, out); err != nil {
		return nil, err
	}
	return out, nil
}
