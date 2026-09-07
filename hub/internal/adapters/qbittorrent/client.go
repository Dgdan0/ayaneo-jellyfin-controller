// Package qbittorrent talks to the download client.
//
// It is the odd one out of the six, twice over:
//
//   - **Authentication varies by build.** Newer versions accept an API key as a
//     bearer token; older ones need a cookie session from /auth/login, and the
//     cookie's *name* is not stable across versions either. On top of that,
//     "bypass authentication for clients on localhost" is on by default, so a
//     hub on the same machine often needs no credential at all — which is the
//     case on this install.
//   - **The state vocabulary changed in 5.x.** `pausedUP`/`pausedDL` became
//     `stoppedUP`/`stoppedDL`, and the actions became start/stop rather than
//     resume/pause. Verified live on this box: stoppedUP, stalledDL,
//     missingFiles and uploading are present; no paused* names at all.
package qbittorrent

import (
	"context"
	"net/url"
	"strconv"
	"strings"

	"ayaneohub/internal/config"
	"ayaneohub/internal/httpx"
)

type Client struct {
	base *httpx.Base
	// Resolved by Probe. Older builds want pause/resume instead.
	usesStopStart bool
	apiVersion    string
}

func New(cfg config.ServiceConfig) (*Client, error) {
	var auth httpx.Authenticator = httpx.NoAuth{}
	if cfg.APIKey.IsSet() {
		auth = httpx.HeaderAuth{
			Headers: map[string]string{"Authorization": "Bearer " + cfg.APIKey.Reveal()},
		}
	}
	origin := strings.TrimRight(cfg.BaseURL, "/")
	base, err := httpx.New(httpx.Options{
		Name:    "qbittorrent",
		BaseURL: cfg.BaseURL,
		Auth:    auth,
		// qBittorrent compares these against the Host header and rejects the
		// request otherwise. This bites everyone exactly once.
		Headers:            map[string]string{"Referer": origin, "Origin": origin},
		Timeout:            cfg.Timeout.OrDefault(0),
		InsecureSkipVerify: cfg.InsecureSkipVerify,
	})
	if err != nil {
		return nil, err
	}
	// Assume the modern vocabulary; Probe corrects it if the build is older.
	return &Client{base: base, usesStopStart: true}, nil
}

// Torrent is one transfer, with only the fields the app actually shows.
type Torrent struct {
	Hash        string  `json:"hash"`
	InfohashV1  string  `json:"infohash_v1"`
	InfohashV2  string  `json:"infohash_v2"`
	Name        string  `json:"name"`
	State       string  `json:"state"`
	Progress    float64 `json:"progress"`
	Size        int64   `json:"size"`
	AmountLeft  int64   `json:"amount_left"`
	Downloaded  int64   `json:"downloaded"`
	DownloadDPS int64   `json:"dlspeed"`
	UploadBPS   int64   `json:"upspeed"`
	ETA         int64   `json:"eta"`
	Seeds       int     `json:"num_seeds"`
	Leechers    int     `json:"num_leechs"`
	Category    string  `json:"category"`
	AddedOn     int64   `json:"added_on"`
	ContentPath string  `json:"content_path"`
}

// etaUnknown is qBittorrent's sentinel for "no estimate".
//
// 8640000 seconds is 100 days. It is not documented as a sentinel, but it is
// what the field carries whenever there is nothing to estimate from, and
// rendering "100 days left" next to a stalled torrent is worse than saying
// nothing.
const etaUnknown = 8640000

// ETASeconds returns -1 when there is no meaningful estimate.
func (t Torrent) ETASeconds() int64 {
	if t.ETA <= 0 || t.ETA >= etaUnknown {
		return -1
	}
	return t.ETA
}

// Hashes returns every identity this torrent answers to.
//
// Three, not one: a BitTorrent v2 or hybrid torrent has both a v1 and a v2
// infohash, and which one an *arr recorded as its downloadId depends on when and
// how it was added. Matching against all of them is the difference between a
// working join and a mysterious orphan.
func (t Torrent) Hashes() []string {
	out := make([]string, 0, 3)
	for _, h := range []string{t.Hash, t.InfohashV1, t.InfohashV2} {
		if h != "" {
			out = append(out, strings.ToLower(h))
		}
	}
	return out
}

// IsStopped covers both vocabularies so callers do not have to care.
func (t Torrent) IsStopped() bool {
	switch t.State {
	case "stoppedUP", "stoppedDL", "pausedUP", "pausedDL":
		return true
	}
	return false
}

// IsFinished reports whether the bytes are all there, seeding or not.
func (t Torrent) IsFinished() bool {
	switch t.State {
	case "uploading", "stalledUP", "queuedUP", "forcedUP", "stoppedUP", "pausedUP", "checkingUP":
		return true
	}
	return t.Progress >= 1.0
}

// IsError is the set worth surfacing loudly.
func (t Torrent) IsError() bool {
	switch t.State {
	case "error", "missingFiles":
		return true
	}
	return false
}

type Version struct {
	API string
	App string
}

// Probe settles which build this is, so the action vocabulary is right.
func (c *Client) Probe(ctx context.Context) (Version, error) {
	var version Version
	var raw string
	if err := c.base.GetText(ctx, "/api/v2/app/webapiVersion", &raw); err != nil {
		return version, err
	}
	version.API = strings.TrimSpace(raw)
	c.apiVersion = version.API

	var app string
	if err := c.base.GetText(ctx, "/api/v2/app/version", &app); err == nil {
		version.App = strings.TrimSpace(app)
		// 5.x renamed pause/resume to stop/start. Reading the major version is
		// more honest than sniffing state names, which only tells you about the
		// torrents that happen to exist right now.
		version.App = strings.TrimPrefix(version.App, "v")
		if major, _, ok := strings.Cut(version.App, "."); ok {
			if n, err := strconv.Atoi(major); err == nil {
				c.usesStopStart = n >= 5
			}
		}
		version.App = app
	}
	return version, nil
}

func (c *Client) Torrents(ctx context.Context) ([]Torrent, error) {
	var out []Torrent
	if err := c.base.GetJSON(ctx, "/api/v2/torrents/info", nil, &out); err != nil {
		return nil, err
	}
	return out, nil
}

// Stop pauses a transfer, using whichever verb this build understands.
func (c *Client) Stop(ctx context.Context, hashes ...string) error {
	path := "/api/v2/torrents/stop"
	if !c.usesStopStart {
		path = "/api/v2/torrents/pause"
	}
	return c.post(ctx, path, url.Values{"hashes": {strings.Join(hashes, "|")}})
}

func (c *Client) Start(ctx context.Context, hashes ...string) error {
	path := "/api/v2/torrents/start"
	if !c.usesStopStart {
		path = "/api/v2/torrents/resume"
	}
	return c.post(ctx, path, url.Values{"hashes": {strings.Join(hashes, "|")}})
}

// Delete removes a transfer, and optionally the files with it.
func (c *Client) Delete(ctx context.Context, deleteFiles bool, hashes ...string) error {
	return c.post(ctx, "/api/v2/torrents/delete", url.Values{
		"hashes":      {strings.Join(hashes, "|")},
		"deleteFiles": {strconv.FormatBool(deleteFiles)},
	})
}

func (c *Client) post(ctx context.Context, path string, form url.Values) error {
	return c.base.PostForm(ctx, path, form, nil)
}
