package arr

import (
	"context"
	"net/http"
	"net/url"
	"strconv"
	"time"
)

type CalendarSeries struct {
	ID        int    `json:"id"`
	Title     string `json:"title"`
	Overview  string `json:"overview"`
	TmdbID    int    `json:"tmdbId"`
	TvdbID    int    `json:"tvdbId"`
	Monitored bool   `json:"monitored"`
}
type CalendarEpisode struct {
	ID            int            `json:"id"`
	SeriesID      int            `json:"seriesId"`
	SeasonNumber  int            `json:"seasonNumber"`
	EpisodeNumber int            `json:"episodeNumber"`
	Title         string         `json:"title"`
	Overview      string         `json:"overview"`
	AirDate       string         `json:"airDate"`
	AirDateUTC    string         `json:"airDateUtc"`
	Monitored     bool           `json:"monitored"`
	HasFile       bool           `json:"hasFile"`
	Series        CalendarSeries `json:"series"`
}
type CalendarMovie struct {
	ID              int    `json:"id"`
	Title           string `json:"title"`
	Overview        string `json:"overview"`
	TmdbID          int    `json:"tmdbId"`
	Year            int    `json:"year"`
	Monitored       bool   `json:"monitored"`
	HasFile         bool   `json:"hasFile"`
	InCinemas       string `json:"inCinemas"`
	DigitalRelease  string `json:"digitalRelease"`
	PhysicalRelease string `json:"physicalRelease"`
}

// Fetch a padded UTC range, then filter in the Hub's requested local timezone.
// Movie release dates are civil dates even when serialized as midnight UTC.
func (c *Client) Calendar(ctx context.Context, start, end time.Time) ([]CalendarEpisode, []CalendarMovie, error) {
	q := url.Values{"start": {start.AddDate(0, 0, -1).UTC().Format(time.RFC3339)}, "end": {end.AddDate(0, 0, 1).UTC().Format(time.RFC3339)}, "unmonitored": {"false"}}
	if c.kind == Sonarr {
		q.Set("includeSeries", "true")
		var items []CalendarEpisode
		err := c.base.GetJSON(ctx, "/api/v3/calendar", q, &items)
		return items, nil, err
	}
	var items []CalendarMovie
	err := c.base.GetJSON(ctx, "/api/v3/calendar", q, &items)
	return nil, items, err
}

// Only a configured Arr origin and numeric library id can be addressed.
func (c *Client) Poster(ctx context.Context, id int) (*http.Response, error) {
	return c.base.Open(ctx, http.MethodGet, "/api/v3/mediacover/"+strconv.Itoa(id)+"/poster.jpg", nil, nil)
}
