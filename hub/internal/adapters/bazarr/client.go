// Package bazarr talks to Bazarr's authenticated API.
package bazarr

import (
	"context"
	"net/url"
	"strings"
	"sync"

	"ayaneohub/internal/config"
	"ayaneohub/internal/httpx"
)

type Client struct {
	base   *httpx.Base
	prefix string
}

func New(cfg config.ServiceConfig) (*Client, error) {
	base, err := httpx.New(httpx.Options{
		Name:               "bazarr",
		BaseURL:            cfg.BaseURL,
		Auth:               httpx.HeaderAuth{Headers: map[string]string{"X-API-KEY": cfg.APIKey.Reveal()}},
		Timeout:            cfg.Timeout.OrDefault(0),
		InsecureSkipVerify: cfg.InsecureSkipVerify,
	})
	if err != nil {
		return nil, err
	}
	prefix := strings.TrimRight(cfg.APIBasePath, "/")
	if prefix == "" {
		prefix = "/api"
	}
	return &Client{base: base, prefix: prefix}, nil
}

type Language struct {
	Code2 string `json:"code2"`
	Code3 string `json:"code3"`
	Name  string `json:"name"`
}

type EpisodeHistory struct {
	ID              int      `json:"id"`
	Action          int      `json:"action"`
	SeriesTitle     string   `json:"seriesTitle"`
	EpisodeNumber   string   `json:"episode_number"`
	EpisodeTitle    string   `json:"episodeTitle"`
	Timestamp       string   `json:"timestamp"`
	ParsedTimestamp string   `json:"parsed_timestamp"`
	Description     string   `json:"description"`
	Language        Language `json:"language"`
	Provider        string   `json:"provider"`
	Score           string   `json:"score"`
	Upgradable      bool     `json:"upgradable"`
}

type MovieHistory struct {
	ID              int      `json:"id"`
	Action          int      `json:"action"`
	Title           string   `json:"title"`
	Timestamp       string   `json:"timestamp"`
	ParsedTimestamp string   `json:"parsed_timestamp"`
	Description     string   `json:"description"`
	Language        Language `json:"language"`
	Provider        string   `json:"provider"`
	Score           string   `json:"score"`
	Upgradable      bool     `json:"upgradable"`
}

type History struct {
	Episodes []EpisodeHistory
	Movies   []MovieHistory
}

type episodeHistoryResponse struct {
	Data  []EpisodeHistory `json:"data"`
	Total int              `json:"total"`
}

type movieHistoryResponse struct {
	Data  []MovieHistory `json:"data"`
	Total int            `json:"total"`
}

// History fetches both subtitle timelines. Bazarr deliberately keeps series
// and movies separate, so the adapter preserves both lists and lets the Hub
// merge them after normalising their timestamps.
func (c *Client) History(ctx context.Context, limit int) (History, error) {
	if limit <= 0 {
		limit = 20
	}
	values := url.Values{}
	values.Set("start", "0")
	values.Set("length", itoa(limit))
	var episodes episodeHistoryResponse
	var movies movieHistoryResponse
	var episodeErr, movieErr error
	var wait sync.WaitGroup
	wait.Add(2)
	go func() {
		defer wait.Done()
		episodeErr = c.base.GetJSON(ctx, c.prefix+"/episodes/history", values, &episodes)
	}()
	go func() {
		defer wait.Done()
		movieErr = c.base.GetJSON(ctx, c.prefix+"/movies/history", values, &movies)
	}()
	wait.Wait()
	if episodeErr != nil {
		return History{}, episodeErr
	}
	if movieErr != nil {
		return History{}, movieErr
	}
	if episodes.Data == nil {
		episodes.Data = []EpisodeHistory{}
	}
	if movies.Data == nil {
		movies.Data = []MovieHistory{}
	}
	return History{Episodes: episodes.Data, Movies: movies.Data}, nil
}

type HealthIssue struct {
	Object string `json:"object"`
	Issue  string `json:"issue"`
}

type healthResponse struct {
	Data []HealthIssue `json:"data"`
}

func (c *Client) Health(ctx context.Context) ([]HealthIssue, error) {
	var response healthResponse
	if err := c.base.GetJSON(ctx, c.prefix+"/system/health", nil, &response); err != nil {
		return nil, err
	}
	if response.Data == nil {
		response.Data = []HealthIssue{}
	}
	return response.Data, nil
}

func itoa(value int) string {
	if value == 0 {
		return "0"
	}
	var buffer [20]byte
	position := len(buffer)
	for value > 0 {
		position--
		buffer[position] = byte('0' + value%10)
		value /= 10
	}
	return string(buffer[position:])
}
