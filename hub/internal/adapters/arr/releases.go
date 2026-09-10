package arr

import (
	"context"
	"fmt"
	"net/url"
	"strconv"
	"time"
)

// Interactive search: every release an indexer knows about, with the reasons
// Radarr or Sonarr would refuse each one.
//
// Measured on this stack for Gran Torino: 100 releases in 3.6s, of which
// **none** were acceptable -- 99 carried "Quality for release in queue already
// meets cutoff". Which is exactly the sort of thing this screen exists to say
// out loud, because from the outside it just looks like nothing is downloading.

// Release is one candidate.
//
// Two fields must never leave the hub, and they are the reason the app is given
// an opaque id instead of this struct:
//
//   - DownloadURL embeds the **Prowlarr API key in clear text**. Verified on
//     this install: all 40 of 40 releases in one search carried it.
//   - GUID is a magnet URI with the full tracker list, up to ~1.2 KB each.
//
// Beyond the leak, handing the app a URL and grabbing whatever it sends back
// would let any token with a grab scope make the hub fetch an arbitrary
// address. The id indirection removes both problems at once.
type Release struct {
	GUID      string `json:"guid"`
	IndexerID int    `json:"indexerId"`
	Indexer   string `json:"indexer"`
	Title     string `json:"title"`
	Size      int64  `json:"size"`
	Seeders   int    `json:"seeders"`
	Leechers  int    `json:"leechers"`
	Protocol  string `json:"protocol"`
	Age       int    `json:"age"`
	// Radarr says "rejected"; Sonarr says the same. Both fill rejections with
	// human sentences, which are passed through verbatim.
	Rejected          bool         `json:"rejected"`
	TemporarilyReject bool         `json:"temporarilyRejected"`
	Rejections        []string     `json:"rejections"`
	Approved          bool         `json:"approved"`
	DownloadAllowed   bool         `json:"downloadAllowed"`
	CustomFormatScore int          `json:"customFormatScore"`
	ReleaseGroup      string       `json:"releaseGroup"`
	PublishDate       string       `json:"publishDate"`
	IndexerFlags      IndexerFlags `json:"indexerFlags"`
	Languages         []IDName     `json:"languages"`
	Quality           struct {
		Quality struct {
			ID         int    `json:"id"`
			Name       string `json:"name"`
			Resolution int    `json:"resolution"`
			Source     string `json:"source"`
		} `json:"quality"`
		Revision struct {
			Version  int  `json:"version"`
			Real     int  `json:"real"`
			IsRepack bool `json:"isRepack"`
		} `json:"revision"`
	} `json:"quality"`
	// Sonarr only.
	FullSeason bool  `json:"fullSeason"`
	SeasonNum  int   `json:"seasonNumber"`
	EpisodeNos []int `json:"episodeNumbers"`
}

type IDName struct {
	ID   int    `json:"id"`
	Name string `json:"name"`
}

// QualityName is the label a person recognises, e.g. "Bluray-1080p".
func (r Release) QualityName() string { return r.Quality.Quality.Name }

func (r Release) Resolution() int { return r.Quality.Quality.Resolution }

// Freeleech reports whether the indexer flagged this as not counting against
// the user's ratio. Worth surfacing: on a private tracker it is often the
// deciding factor between two otherwise equal releases.
func (r Release) Freeleech() bool {
	for _, flag := range r.IndexerFlags.Names() {
		switch flag {
		case "G_Freeleech", "Freeleech", "FreeLeech", "G_Halfleech":
			return true
		}
	}
	return false
}

func (r Release) LanguageNames() []string {
	names := make([]string, 0, len(r.Languages))
	for _, lang := range r.Languages {
		if lang.Name != "" {
			names = append(names, lang.Name)
		}
	}
	return names
}

// MovieByTmdb resolves a TMDB id to Radarr's own movie id.
//
// This is deliberately asked of Radarr rather than taken from Jellyseerr's
// `mediaInfo.externalServiceId`, which is null for anything that was not added
// *through* Jellyseerr -- verified here on a series that is plainly in the
// library. Radarr's own index is the authority on what Radarr holds.
//
// @return nil, nil when the title is not in Radarr at all, which is an ordinary
// answer rather than a failure.
func (c *Client) MovieByTmdb(ctx context.Context, tmdbID int) (*Movie, error) {
	if c.kind != Radarr {
		return nil, fmt.Errorf("MovieByTmdb is Radarr-only, called on %s", c.kind)
	}
	var out []Movie
	query := url.Values{"tmdbId": {strconv.Itoa(tmdbID)}}
	if err := c.base.GetJSON(ctx, "/api/v3/movie", query, &out); err != nil {
		return nil, err
	}
	if len(out) == 0 {
		return nil, nil
	}
	return &out[0], nil
}

// SeriesByTvdb resolves a TVDB id to Sonarr's own series id.
func (c *Client) SeriesByTvdb(ctx context.Context, tvdbID int) (*Series, error) {
	if c.kind != Sonarr {
		return nil, fmt.Errorf("SeriesByTvdb is Sonarr-only, called on %s", c.kind)
	}
	var out []Series
	query := url.Values{"tvdbId": {strconv.Itoa(tvdbID)}}
	if err := c.base.GetJSON(ctx, "/api/v3/series", query, &out); err != nil {
		return nil, err
	}
	if len(out) == 0 {
		return nil, nil
	}
	return &out[0], nil
}

// Releases runs an interactive search.
//
// Slow by nature -- it queries every configured indexer synchronously and the
// caller must allow for that. Measured here at 1-4s with one indexer; a stack
// with a dozen takes correspondingly longer, which is why the app shows this on
// a screen of its own rather than inside a menu.
func (c *Client) Releases(ctx context.Context, query url.Values) ([]Release, error) {
	var out []Release
	// Its own deadline, well past the per-service cap: the cap exists so a sick
	// service cannot hang a screen, and this call is slow when everything is
	// perfectly healthy. The caller's context still bounds it.
	slow := c.base.WithTimeout(ReleaseSearchTimeout)
	if err := slow.GetJSON(ctx, "/api/v3/release", query, &out); err != nil {
		return nil, err
	}
	return out, nil
}

// ReleaseSearchTimeout is how long an interactive search may take.
//
// Measured on this stack: 1-4s for a movie with one indexer. A full Sonarr
// season across several indexers is much slower, and timing it out reports a
// healthy service as down.
const ReleaseSearchTimeout = 110 * time.Second

// MovieReleases searches for one Radarr movie.
func (c *Client) MovieReleases(ctx context.Context, movieID int) ([]Release, error) {
	return c.Releases(ctx, url.Values{"movieId": {strconv.Itoa(movieID)}})
}

// SeasonReleases searches for one Sonarr season.
//
// Sonarr wants a season number alongside the series; asking for a series alone
// is not a supported search.
func (c *Client) SeasonReleases(ctx context.Context, seriesID, season int) ([]Release, error) {
	return c.Releases(ctx, url.Values{
		"seriesId":     {strconv.Itoa(seriesID)},
		"seasonNumber": {strconv.Itoa(season)},
	})
}

// Episodes returns Sonarr's canonical episode records for one season. Release
// searches use the returned id rather than guessing scope from a release name.
func (c *Client) Episodes(ctx context.Context, seriesID, season int) ([]Episode, error) {
	if c.kind != Sonarr {
		return nil, fmt.Errorf("Episodes is Sonarr-only, called on %s", c.kind)
	}
	var out []Episode
	query := url.Values{
		"seriesId":      {strconv.Itoa(seriesID)},
		"seasonNumber":  {strconv.Itoa(season)},
		"includeImages": {"false"},
	}
	if err := c.base.GetJSON(ctx, "/api/v3/episode", query, &out); err != nil {
		return nil, err
	}
	return out, nil
}

// EpisodeReleases searches for one Sonarr episode. Sonarr may still return a
// season pack that contains it; the API layer labels those as outside the
// selected scope before the app can grab one accidentally.
func (c *Client) EpisodeReleases(ctx context.Context, episodeID int) ([]Release, error) {
	if c.kind != Sonarr {
		return nil, fmt.Errorf("EpisodeReleases is Sonarr-only, called on %s", c.kind)
	}
	return c.Releases(ctx, url.Values{"episodeId": {strconv.Itoa(episodeID)}})
}

// Grab hands one release back for download.
//
// Only the guid and indexer id are sent: both *arrs look the release up in the
// cache left by the search that produced it. That cache is why a grab has to
// follow a search reasonably promptly, and why the hub keeps its own copy of
// the result set rather than expecting the app to hold one.
func (c *Client) Grab(ctx context.Context, guid string, indexerID int) error {
	body := map[string]any{"guid": guid, "indexerId": indexerID}
	return c.base.PostJSON(ctx, "/api/v3/release", body, nil)
}
