package jellyfin

import (
	"context"
	"net/url"
)

type Session struct {
	ID             string `json:"Id"`
	UserID         string `json:"UserId"`
	DeviceName     string `json:"DeviceName"`
	Client         string `json:"Client"`
	NowPlayingItem *Item  `json:"NowPlayingItem"`
	PlayState      struct {
		IsPaused   bool   `json:"IsPaused"`
		PlayMethod string `json:"PlayMethod"`
	} `json:"PlayState"`
}

func (c *Client) Sessions(ctx context.Context) ([]Session, error) {
	var result []Session
	err := c.base.GetJSON(ctx, "/Sessions", url.Values{"activeWithinSeconds": {"120"}}, &result)
	return result, err
}
