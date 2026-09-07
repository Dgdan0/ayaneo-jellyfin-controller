// Package arr talks to Radarr and Sonarr.
//
// One package for both, because their v3 APIs are the same shape with different
// nouns: /queue, /system/status and /release are identical bar the id field
// (movieId versus seriesId/episodeId). Two near-identical packages would drift.
package arr

import (
	"context"
	"net/url"
	"strconv"
	"strings"

	"ayaneohub/internal/config"
	"ayaneohub/internal/httpx"
)

type Kind string

const (
	Radarr Kind = "radarr"
	Sonarr Kind = "sonarr"
)

type Client struct {
	base *httpx.Base
	kind Kind
}

func New(kind Kind, cfg config.ServiceConfig) (*Client, error) {
	base, err := httpx.New(httpx.Options{
		Name:               string(kind),
		BaseURL:            cfg.BaseURL,
		Auth:               httpx.HeaderAuth{Headers: map[string]string{"X-Api-Key": cfg.APIKey.Reveal()}},
		Timeout:            cfg.Timeout.OrDefault(0),
		InsecureSkipVerify: cfg.InsecureSkipVerify,
		// Interactive search is the one endpoint here that legitimately returns
		// megabytes: 100 releases carrying magnet URIs and full tracker lists
		// came to 500 KB for one ordinary film, and a busier title would blow
		// through the 1 MB default and fail to decode.
		MaxBody: 8 << 20,
	})
	if err != nil {
		return nil, err
	}
	return &Client{base: base, kind: kind}, nil
}

func (c *Client) Kind() Kind { return c.kind }

// QueueRecord is one item in the *arr queue.
//
// Field names are verbatim from QueueResource, including the lower-case
// `sizeleft` and `timeleft`, which do not follow the camelCase of everything
// around them.
type QueueRecord struct {
	ID       int     `json:"id"`
	MovieID  int     `json:"movieId"`
	SeriesID int     `json:"seriesId"`
	Title    string  `json:"title"`
	Size     float64 `json:"size"`
	SizeLeft float64 `json:"sizeleft"`
	TimeLeft string  `json:"timeleft"`
	Status   string  `json:"status"`
	// The download client's identifier. Sonarr sets this to the torrent hash
	// uppercased, so the join to qBittorrent is a case-insensitive compare.
	//
	// It can be **empty**: a failed grab leaves a phantom queue row that never
	// auto-cleans (Radarr #11585, Sonarr #8852). That row is exactly the thing
	// the user opened the app to find, so it is surfaced as stuck rather than
	// dropped.
	DownloadID              string          `json:"downloadId"`
	Protocol                string          `json:"protocol"`
	DownloadClient          string          `json:"downloadClient"`
	Indexer                 string          `json:"indexer"`
	TrackedDownloadStatus   string          `json:"trackedDownloadStatus"`
	TrackedDownloadState    string          `json:"trackedDownloadState"`
	ErrorMessage            string          `json:"errorMessage"`
	StatusMessages          []StatusMessage `json:"statusMessages"`
	EstimatedCompletionTime string          `json:"estimatedCompletionTime"`
	Movie                   *Movie          `json:"movie"`
	Series                  *Series         `json:"series"`
	Episode                 *Episode        `json:"episode"`
}

type StatusMessage struct {
	Title    string   `json:"title"`
	Messages []string `json:"messages"`
}

type Movie struct {
	ID     int    `json:"id"`
	Title  string `json:"title"`
	Year   int    `json:"year"`
	TmdbID int    `json:"tmdbId"`
	ImdbID string `json:"imdbId"`
	// Filled by a direct lookup; absent from the copy embedded in a queue row.
	HasFile          bool   `json:"hasFile"`
	Monitored        bool   `json:"monitored"`
	QualityProfileID int    `json:"qualityProfileId"`
	RootFolderPath   string `json:"rootFolderPath"`
}

type Series struct {
	ID               int    `json:"id"`
	Title            string `json:"title"`
	Year             int    `json:"year"`
	TvdbID           int    `json:"tvdbId"`
	ImdbID           string `json:"imdbId"`
	Monitored        bool   `json:"monitored"`
	QualityProfileID int    `json:"qualityProfileId"`
	RootFolderPath   string `json:"rootFolderPath"`
}

type Episode struct {
	ID            int    `json:"id"`
	SeasonNumber  int    `json:"seasonNumber"`
	EpisodeNumber int    `json:"episodeNumber"`
	Title         string `json:"title"`
}

type queuePage struct {
	Page         int           `json:"page"`
	PageSize     int           `json:"pageSize"`
	TotalRecords int           `json:"totalRecords"`
	Records      []QueueRecord `json:"records"`
}

// IsStuck reports a row that needs a human.
//
// Two separate failure shapes: the *arr itself flagging an error, and a row with
// no download-client item at all. The second is the phantom-row bug, and it is
// invisible unless you look for it.
func (q QueueRecord) IsStuck() bool {
	if q.TrackedDownloadStatus == "error" || q.TrackedDownloadStatus == "warning" {
		return true
	}
	return q.DownloadID == "" || q.TrackedDownloadState == "downloadFailed" ||
		q.TrackedDownloadState == "importFailed" || q.TrackedDownloadState == "importBlocked"
}

// Problem is the first human-readable reason this row is unhappy.
func (q QueueRecord) Problem() string {
	if q.ErrorMessage != "" {
		return q.ErrorMessage
	}
	for _, m := range q.StatusMessages {
		if len(m.Messages) > 0 {
			return m.Messages[0]
		}
		if m.Title != "" {
			return m.Title
		}
	}
	if q.DownloadID == "" {
		return "no item in the download client"
	}
	return ""
}

// DisplayTitle is what the row is actually about, rather than the release name.
func (q QueueRecord) DisplayTitle() string {
	if q.Movie != nil && q.Movie.Title != "" {
		return q.Movie.Title
	}
	if q.Series != nil && q.Series.Title != "" {
		title := q.Series.Title
		if q.Episode != nil {
			title += " " + formatEpisode(q.Episode.SeasonNumber, q.Episode.EpisodeNumber)
		}
		return title
	}
	return q.Title
}

func formatEpisode(season, episode int) string {
	return "S" + pad2(season) + "E" + pad2(episode)
}

func pad2(n int) string {
	s := strconv.Itoa(n)
	if len(s) < 2 {
		return "0" + s
	}
	return s
}

// Queue fetches everything currently in flight.
func (c *Client) Queue(ctx context.Context) ([]QueueRecord, error) {
	values := url.Values{}
	values.Set("pageSize", "200")
	// Without these the rows carry only ids, and the app would have to make a
	// second call per row just to show a title.
	if c.kind == Radarr {
		values.Set("includeMovie", "true")
		values.Set("includeUnknownMovieItems", "true")
	} else {
		values.Set("includeSeries", "true")
		values.Set("includeEpisode", "true")
		values.Set("includeUnknownSeriesItems", "true")
	}

	var page queuePage
	if err := c.base.GetJSON(ctx, "/api/v3/queue", values, &page); err != nil {
		return nil, err
	}
	return page.Records, nil
}

type SystemStatus struct {
	Version      string `json:"version"`
	AppName      string `json:"appName"`
	InstanceName string `json:"instanceName"`
}

func (c *Client) Status(ctx context.Context) (*SystemStatus, error) {
	out := &SystemStatus{}
	if err := c.base.GetJSON(ctx, "/api/v3/system/status", nil, out); err != nil {
		return nil, err
	}
	return out, nil
}

// RemoveFromQueue drops a queue row.
//
// blocklist tells the *arr never to grab that release again, which is what you
// want for something that failed; search asks it to look for a replacement
// immediately. Both are off by default because both are consequential.
func (c *Client) RemoveFromQueue(
	ctx context.Context, id int, removeFromClient, blocklist, search bool,
) error {
	values := url.Values{}
	values.Set("removeFromClient", strconv.FormatBool(removeFromClient))
	values.Set("blocklist", strconv.FormatBool(blocklist))
	values.Set("skipRedownload", strconv.FormatBool(!search))
	return c.base.Delete(ctx, "/api/v3/queue/"+strconv.Itoa(id), values)
}

// DownloadClients reports which clients this *arr is configured to use.
//
// Worth knowing because a usenet client means downloadId is an nzo_id and the
// qBittorrent join will find nothing for those rows -- not a bug, but something
// the diagnostics should say out loud rather than leaving as a mystery.
func (c *Client) DownloadClients(ctx context.Context) ([]DownloadClient, error) {
	var out []DownloadClient
	if err := c.base.GetJSON(ctx, "/api/v3/downloadclient", nil, &out); err != nil {
		return nil, err
	}
	return out, nil
}

type DownloadClient struct {
	ID             int    `json:"id"`
	Name           string `json:"name"`
	Implementation string `json:"implementation"`
	Protocol       string `json:"protocol"`
	Enable         bool   `json:"enable"`
}

func (d DownloadClient) IsUsenet() bool {
	return strings.EqualFold(d.Protocol, "usenet")
}
