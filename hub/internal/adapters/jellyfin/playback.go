package jellyfin

import (
	"context"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
	"strings"
)

// WithPlaybackDevice gives Jellyfin a stable dashboard/session identity. The
// API key remains in X-Emby-Token and never enters this display header.
func (c *Client) WithPlaybackDevice(deviceID, deviceName, version string) *Client {
	clean := func(value string, limit int) string {
		value = strings.NewReplacer("\"", "", "\\", "", ",", "").Replace(strings.TrimSpace(value))
		if len(value) > limit {
			value = value[:limit]
		}
		return value
	}
	deviceID = clean(deviceID, 80)
	deviceName = clean(deviceName, 80)
	version = clean(version, 32)
	if deviceName == "" {
		deviceName = "AYANEO Pocket DS"
	}
	if version == "" {
		version = "0.1"
	}
	authorization := fmt.Sprintf(
		`MediaBrowser Client="Pocket DS Hub", Device="%s", DeviceId="%s", Version="%s"`,
		deviceName, deviceID, version,
	)
	return &Client{
		base: c.base.WithHeaders(map[string]string{
			"Authorization": authorization,
			"X-Application": "Pocket DS Hub/" + version,
		}),
		userID: c.userID,
	}
}

func (c *Client) PlaybackInfo(
	ctx context.Context, itemID string, request PlaybackInfoRequest,
) (*PlaybackInfo, error) {
	if err := c.requireUser(); err != nil {
		return nil, err
	}
	request.UserID = c.userID
	out := &PlaybackInfo{}
	if err := c.base.PostJSON(ctx, "/Items/"+itemID+"/PlaybackInfo", request, out); err != nil {
		return nil, err
	}
	return out, nil
}

// NextUpForSeries asks Jellyfin for the user's exact resume/next target. With
// enableResumable it returns the unfinished episode before a later unplayed one.
func (c *Client) NextUpForSeries(
	ctx context.Context, seriesID string, enableResumable bool,
) (*ItemsPage, error) {
	if err := c.requireUser(); err != nil {
		return nil, err
	}
	out := &ItemsPage{}
	query := url.Values{
		"userId": {c.userID}, "seriesId": {seriesID}, "limit": {"1"},
		"enableResumable":     {strconv.FormatBool(enableResumable)},
		"disableFirstEpisode": {"false"},
		"fields":              {"Overview,ProviderIds,Genres"},
	}
	if err := c.base.GetJSON(ctx, "/Shows/NextUp", query, out); err != nil {
		return nil, err
	}
	return out, nil
}

func (c *Client) EpisodesFrom(
	ctx context.Context, seriesID, startItemID string, limit int,
) (*ItemsPage, error) {
	if err := c.requireUser(); err != nil {
		return nil, err
	}
	out := &ItemsPage{}
	query := url.Values{
		"userId": {c.userID}, "isMissing": {"false"}, "sortBy": {"SortName"},
		"fields": {"Overview,ProviderIds,Genres,MediaSources"},
	}
	if startItemID != "" {
		query.Set("startItemId", startItemID)
	}
	if limit > 0 {
		query.Set("limit", strconv.Itoa(limit))
	}
	if err := c.base.GetJSON(ctx, "/Shows/"+seriesID+"/Episodes", query, out); err != nil {
		return nil, err
	}
	return out, nil
}

// AdjacentEpisodes returns the small ordered window around one episode. The
// current item can sit at either edge at a season/series boundary, so callers
// still compare episode numbers rather than assuming fixed response positions.
func (c *Client) AdjacentEpisodes(
	ctx context.Context, seriesID, episodeID string,
) (*ItemsPage, error) {
	if err := c.requireUser(); err != nil {
		return nil, err
	}
	out := &ItemsPage{}
	query := url.Values{
		"userId": {c.userID}, "adjacentTo": {episodeID}, "limit": {"3"},
		"isMissing": {"false"}, "sortBy": {"SortName"},
		"fields": {"Overview,ProviderIds,Genres"},
	}
	if err := c.base.GetJSON(ctx, "/Shows/"+seriesID+"/Episodes", query, out); err != nil {
		return nil, err
	}
	return out, nil
}

func (c *Client) SendPlaybackEvent(ctx context.Context, path string, event PlaybackEvent) error {
	return c.base.PostJSON(ctx, path, event, nil)
}

func (c *Client) CloseTranscode(ctx context.Context, deviceID, playSessionID string) error {
	if playSessionID == "" {
		return nil
	}
	return c.base.Delete(ctx, "/Videos/ActiveEncodings", url.Values{
		"deviceId": {deviceID}, "playSessionId": {playSessionID},
	})
}

// OpenResource streams one Jellyfin-owned path. Authentication and TLS policy
// are inherited from the adapter; callers still validate the path against the
// playback session before reaching here.
func (c *Client) OpenResource(
	ctx context.Context, method, resource string, headers http.Header,
) (*http.Response, error) {
	parsed, err := url.Parse(resource)
	if err != nil || parsed.IsAbs() || parsed.Host != "" || !strings.HasPrefix(parsed.Path, "/") {
		return nil, fmt.Errorf("jellyfin: invalid playback resource")
	}
	query := parsed.Query()
	// Jellyfin currently emits `ApiKey`, while older clients and plugins use
	// `api_key` or `token`. Strip credentials case-insensitively and authenticate
	// with the adapter-owned X-Emby-Token header instead.
	for name := range query {
		switch strings.ToLower(name) {
		case "apikey", "api_key", "token", "access_token", "x-emby-token":
			query.Del(name)
		}
	}
	return c.base.Open(ctx, method, parsed.Path, query, headers)
}
