package qbittorrent

import (
	"context"
	"encoding/json"
	"fmt"
	"net/url"
	"strconv"
	"strings"
)

// Units are bytes/sec in the installed 5.0.4 API source, including preferences.
// Some wiki tables incorrectly label preferences KiB/sec. Never multiply twice.
type Bandwidth struct {
	Mode                   string `json:"mode"`
	DownloadBps            int64  `json:"downloadBps"`
	UploadBps              int64  `json:"uploadBps"`
	AlternativeDownloadBps int64  `json:"alternativeDownloadBps"`
	AlternativeUploadBps   int64  `json:"alternativeUploadBps"`
	QueueingEnabled        bool   `json:"queueingEnabled"`
	SchedulerEnabled       bool   `json:"schedulerEnabled"`
	ModeSwitchSupported    bool   `json:"modeSwitchSupported"`
	CanControl             bool   `json:"canControl"`
}
type BandwidthChange struct {
	Mode        string `json:"mode,omitempty"`
	LimitsFor   string `json:"limitsFor,omitempty"`
	DownloadBps *int64 `json:"downloadBps,omitempty"`
	UploadBps   *int64 `json:"uploadBps,omitempty"`
}

func (b BandwidthChange) Validate() error {
	validMode := func(v string) bool { return v == "normal" || v == "alternative" }
	if b.Mode != "" && !validMode(b.Mode) {
		return fmt.Errorf("mode must be normal or alternative")
	}
	hasLimits := b.DownloadBps != nil || b.UploadBps != nil
	if hasLimits && !validMode(b.LimitsFor) {
		return fmt.Errorf("limitsFor must identify normal or alternative limits")
	}
	if !hasLimits && b.LimitsFor != "" {
		return fmt.Errorf("no limits supplied")
	}
	if b.Mode == "" && !hasLimits {
		return fmt.Errorf("no change supplied")
	}
	for _, v := range []*int64{b.DownloadBps, b.UploadBps} {
		if v != nil && (*v < 0 || *v > 1<<30) {
			return fmt.Errorf("limits must be 0–1073741824 bytes/sec; zero means unlimited")
		}
	}
	return nil
}

func (c *Client) Bandwidth(ctx context.Context) (Bandwidth, error) {
	var prefs struct {
		Down     int64 `json:"dl_limit"`
		Up       int64 `json:"up_limit"`
		AltDown  int64 `json:"alt_dl_limit"`
		AltUp    int64 `json:"alt_up_limit"`
		Queue    bool  `json:"queueing_enabled"`
		Schedule bool  `json:"scheduler_enabled"`
	}
	out := Bandwidth{}
	if err := c.base.GetJSON(ctx, "/api/v2/app/preferences", nil, &prefs); err != nil {
		return out, err
	}
	var mode, version string
	if err := c.base.GetText(ctx, "/api/v2/transfer/speedLimitsMode", &mode); err != nil {
		return out, err
	}
	mode = strings.TrimSpace(mode)
	if mode != "0" && mode != "1" {
		return out, fmt.Errorf("invalid speed limits mode")
	}
	if err := c.base.GetText(ctx, "/api/v2/app/version", &version); err != nil {
		return out, err
	}
	major, _, _ := strings.Cut(strings.TrimPrefix(strings.TrimSpace(version), "v"), ".")
	number, _ := strconv.Atoi(major)
	out = Bandwidth{Mode: "normal", DownloadBps: max(0, prefs.Down), UploadBps: max(0, prefs.Up), AlternativeDownloadBps: max(0, prefs.AltDown), AlternativeUploadBps: max(0, prefs.AltUp), QueueingEnabled: prefs.Queue, SchedulerEnabled: prefs.Schedule, ModeSwitchSupported: number >= 5}
	if mode == "1" {
		out.Mode = "alternative"
	}
	return out, nil
}

func (c *Client) SetBandwidth(ctx context.Context, change BandwidthChange) (Bandwidth, error) {
	if err := change.Validate(); err != nil {
		return Bandwidth{}, err
	}
	c.controlMu.Lock()
	defer c.controlMu.Unlock()
	before, err := c.Bandwidth(ctx)
	if err != nil {
		return before, err
	}
	if change.Mode != "" && !before.ModeSwitchSupported {
		return before, fmt.Errorf("explicit mode switching requires qBittorrent 5 or later")
	}
	settings := map[string]int64{}
	prefix := ""
	if change.LimitsFor == "alternative" {
		prefix = "alt_"
	}
	if change.DownloadBps != nil {
		settings[prefix+"dl_limit"] = *change.DownloadBps
	}
	if change.UploadBps != nil {
		settings[prefix+"up_limit"] = *change.UploadBps
	}
	if len(settings) > 0 {
		payload, _ := json.Marshal(settings)
		if err := c.post(ctx, "/api/v2/app/setPreferences", url.Values{"json": {string(payload)}}); err != nil {
			return before, err
		}
	}
	if change.Mode != "" && change.Mode != before.Mode {
		mode := "0"
		if change.Mode == "alternative" {
			mode = "1"
		}
		if err := c.post(ctx, "/api/v2/transfer/setSpeedLimitsMode", url.Values{"mode": {mode}}); err != nil {
			return before, err
		}
	}
	after, err := c.Bandwidth(ctx)
	if err != nil {
		return after, err
	}
	if change.Mode != "" && after.Mode != change.Mode {
		return after, fmt.Errorf("mode changed before verification; refresh to read current settings")
	}
	down, up := after.DownloadBps, after.UploadBps
	if change.LimitsFor == "alternative" {
		down, up = after.AlternativeDownloadBps, after.AlternativeUploadBps
	}
	if (change.DownloadBps != nil && down != *change.DownloadBps) || (change.UploadBps != nil && up != *change.UploadBps) {
		return after, fmt.Errorf("limits did not match the requested values; refresh before retrying")
	}
	return after, nil
}

func (c *Client) ChangePriority(ctx context.Context, hash, action string) (int, error) {
	paths := map[string]string{"priority_up": "increasePrio", "priority_down": "decreasePrio"}
	path, ok := paths[action]
	if !ok {
		return 0, fmt.Errorf("unsupported priority action")
	}
	c.controlMu.Lock()
	defer c.controlMu.Unlock()
	before, err := c.Bandwidth(ctx)
	if err != nil {
		return 0, err
	}
	if !before.QueueingEnabled {
		return 0, fmt.Errorf("torrent queueing is disabled in qBittorrent")
	}
	find := func() (int, error) {
		items, err := c.Torrents(ctx)
		if err != nil {
			return 0, err
		}
		for _, item := range items {
			if strings.EqualFold(item.Hash, hash) {
				return item.Priority, nil
			}
		}
		return 0, fmt.Errorf("transfer no longer exists")
	}
	if _, err := find(); err != nil {
		return 0, err
	}
	if err := c.post(ctx, "/api/v2/torrents/"+path, url.Values{"hashes": {hash}}); err != nil {
		return 0, err
	}
	return find()
}
