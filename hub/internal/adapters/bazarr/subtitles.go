package bazarr

import (
	"context"
	"fmt"
	"net/url"
	"strconv"
	"time"
)

type SubtitleTrack struct {
	Name   string  `json:"name"`
	Code2  string  `json:"code2"`
	Path   *string `json:"path"`
	Forced bool    `json:"forced"`
	HI     bool    `json:"hi"`
}
type SubtitleMedia struct {
	RadarrID  int             `json:"radarrId"`
	EpisodeID int             `json:"sonarrEpisodeId"`
	SeriesID  int             `json:"sonarrSeriesId"`
	Path      string          `json:"path"`
	Subtitles []SubtitleTrack `json:"subtitles"`
}
type SubtitleHistory struct {
	ParsedTimestamp string   `json:"parsed_timestamp"`
	Timestamp       string   `json:"timestamp"`
	Description     string   `json:"description"`
	Language        Language `json:"language"`
	Provider        string   `json:"provider"`
	Score           string   `json:"score"`
	Path            string   `json:"subtitles_path"`
	Action          int      `json:"action"`
	RadarrID        int      `json:"radarrId"`
	EpisodeID       int      `json:"sonarrEpisodeId"`
}

// Serialized subtitles stay inside the Hub and are only returned to the same Bazarr.
type SubtitleCandidate struct {
	Provider       string   `json:"provider"`
	Language       string   `json:"language"`
	Score          float64  `json:"score"`
	Matches        []string `json:"matches"`
	DontMatches    []string `json:"dont_matches"`
	ReleaseInfo    []string `json:"release_info"`
	HI             string   `json:"hearing_impaired"`
	Forced         string   `json:"forced"`
	OriginalFormat string   `json:"original_format"`
	Subtitle       string   `json:"subtitle"`
}

func subtitleKind(movie bool) string {
	if movie {
		return "movies"
	}
	return "episodes"
}
func subtitleID(movie bool) string {
	if movie {
		return "radarrid"
	}
	return "episodeid"
}
func (c *Client) SubtitleMedia(ctx context.Context, movie bool, id int) (*SubtitleMedia, error) {
	var out struct {
		Data []SubtitleMedia `json:"data"`
	}
	err := c.base.GetJSON(ctx, c.prefix+"/"+subtitleKind(movie), url.Values{subtitleID(movie) + "[]": {strconv.Itoa(id)}}, &out)
	if err != nil {
		return nil, err
	}
	for _, v := range out.Data {
		if (movie && v.RadarrID == id) || (!movie && v.EpisodeID == id) {
			return &v, nil
		}
	}
	return nil, fmt.Errorf("subtitle media is not indexed by Bazarr")
}
func (c *Client) SubtitleHistory(ctx context.Context, movie bool, id int) ([]SubtitleHistory, error) {
	var out struct {
		Data []SubtitleHistory `json:"data"`
	}
	err := c.base.GetJSON(ctx, c.prefix+"/"+subtitleKind(movie)+"/history", url.Values{subtitleID(movie): {strconv.Itoa(id)}, "length": {"100"}, "start": {"0"}}, &out)
	result := []SubtitleHistory{}
	for _, v := range out.Data {
		if (movie && v.RadarrID == id) || (!movie && v.EpisodeID == id) {
			result = append(result, v)
		}
	}
	return result, err
}
func (c *Client) SearchSubtitles(ctx context.Context, movie bool, id int) ([]SubtitleCandidate, error) {
	var out struct {
		Data []SubtitleCandidate `json:"data"`
	}
	err := c.base.WithTimeout(90*time.Second).GetJSON(ctx, c.prefix+"/providers/"+subtitleKind(movie), url.Values{subtitleID(movie): {strconv.Itoa(id)}}, &out)
	return out.Data, err
}
func (c *Client) DownloadSubtitle(ctx context.Context, movie bool, id, seriesID int, v SubtitleCandidate) error {
	values := url.Values{subtitleID(movie): {strconv.Itoa(id)}, "hi": {v.HI}, "forced": {v.Forced}, "original_format": {v.OriginalFormat}, "provider": {v.Provider}, "subtitle": {v.Subtitle}}
	if !movie {
		values.Set("seriesid", strconv.Itoa(seriesID))
	}
	return c.base.WithTimeout(90*time.Second).PostForm(ctx, c.prefix+"/providers/"+subtitleKind(movie), values, nil)
}
