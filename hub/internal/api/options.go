package api

import (
	"context"
	"net/http"
	"time"

	"ayaneohub/internal/adapters/jellyseerr"
	"ayaneohub/internal/cache"
)

// Everything the request dialog needs, in one call.
//
// Without this the app either hard-codes profile ids -- which are per-install
// and meaningless elsewhere -- or asks the user to type a Windows path on a
// handheld. Both are worse than one round trip.

type RequestOption struct {
	ID      int    `json:"id"`
	Label   string `json:"label"`
	Detail  string `json:"detail,omitempty"`
	Default bool   `json:"default"`
}

type RootFolderOption struct {
	ID             int    `json:"id"`
	Path           string `json:"path"`
	Label          string `json:"label"`
	FreeSpaceBytes int64  `json:"freeSpaceBytes"`
	Default        bool   `json:"default"`
}

type SeasonOption struct {
	Number       int    `json:"number"`
	Name         string `json:"name"`
	EpisodeCount int    `json:"episodeCount"`
	Year         int    `json:"year,omitempty"`
}

type RequestOptions struct {
	Key        string `json:"key"`
	Type       string `json:"type"`
	Title      string `json:"title"`
	Service    string `json:"service"`
	ServerID   int    `json:"serverId"`
	ServerName string `json:"serverName"`
	// True when a separate 4K instance is configured. Nothing on this stack has
	// one, and the app hides the toggle rather than offering a switch that
	// silently sends the request to the same place.
	Has4k       bool               `json:"has4k"`
	Profiles    []RequestOption    `json:"profiles"`
	RootFolders []RootFolderOption `json:"rootFolders"`
	Tags        []RequestOption    `json:"tags"`
	Seasons     []SeasonOption     `json:"seasons,omitempty"`
	Partial     []Partial          `json:"partial"`
	Cache       CacheInfo          `json:"cache"`
}

// handleRequestOptions answers "what can I choose when requesting this?".
func (s *Server) handleRequestOptions(w http.ResponseWriter, r *http.Request) {
	key, err := ParseMediaKey(r.URL.Query().Get("key"))
	if err != nil {
		writeError(w, r, http.StatusBadRequest, Error{
			Code: CodeInvalidRequest, Message: err.Error(),
		})
		return
	}
	client := s.jellyseerr
	if client == nil {
		writeError(w, r, http.StatusServiceUnavailable, Error{
			Code: CodeUpstreamDown, Service: "jellyseerr",
			Message: "Jellyseerr is not configured",
		})
		return
	}

	ctx, cancel := timeoutFor(r, s.cfg.Server.RequestTimeout.OrDefault(20*time.Second))
	defer cancel()

	service := "radarr"
	if key.Type == "series" {
		service = "sonarr"
	}

	// Profiles and root folders change when someone edits Radarr, which is
	// rarely. Ten minutes keeps the dialog instant without going stale in any
	// way that matters.
	settings, meta, err := cache.Fetch(ctx, s.cache, "service:"+service, cache.ServiceOptions,
		func(ctx context.Context) (*serviceSettings, error) {
			return loadServiceSettings(ctx, client, service)
		})
	if err != nil {
		writeUpstreamError(w, r, "jellyseerr", err)
		return
	}

	out := RequestOptions{
		Key:         key.String(),
		Type:        key.Type,
		Service:     service,
		ServerID:    settings.Detail.Server.ID,
		ServerName:  settings.Detail.Server.Name,
		Has4k:       settings.Has4k,
		Profiles:    make([]RequestOption, 0, len(settings.Detail.Profiles)),
		RootFolders: make([]RootFolderOption, 0, len(settings.Detail.RootFolders)),
		Tags:        make([]RequestOption, 0, len(settings.Detail.Tags)),
		Partial:     []Partial{},
		Cache:       cacheInfoFrom(meta),
	}

	defaultProfile := settings.Detail.Server.ActiveProfileID
	defaultFolder := settings.Detail.Server.ActiveDirectory
	for _, p := range settings.Detail.Profiles {
		out.Profiles = append(out.Profiles, RequestOption{
			ID: p.ID, Label: p.Name, Default: p.ID == defaultProfile,
		})
	}
	for _, f := range settings.Detail.RootFolders {
		out.RootFolders = append(out.RootFolders, RootFolderOption{
			ID:             f.ID,
			Path:           f.Path,
			Label:          folderLabel(f.Path),
			FreeSpaceBytes: f.FreeSpace,
			Default:        f.Path == defaultFolder,
		})
	}
	for _, t := range settings.Detail.Tags {
		out.Tags = append(out.Tags, RequestOption{ID: t.ID, Label: t.Label})
	}

	// The title and the season list come from the detail the app has already
	// seen, so this stays one call rather than two.
	detail, _, detailErr := cache.Fetch(ctx, s.cache, "detail:"+key.String(), cache.Availability,
		func(ctx context.Context) (*jellyseerr.Detail, error) {
			return client.Detail(ctx, key.JellyseerrType(), key.ID)
		})
	if detailErr == nil && detail != nil {
		out.Title = detail.DisplayTitle()
		if key.Type == "series" {
			for _, season := range detail.Seasons {
				// Season 0 is Specials. Offered, but never a default -- almost
				// nobody means "and the Christmas episodes" by "get this show".
				if season.EpisodeCount == 0 {
					continue
				}
				out.Seasons = append(out.Seasons, SeasonOption{
					Number:       season.SeasonNumber,
					Name:         season.Name,
					EpisodeCount: season.EpisodeCount,
					Year:         yearOf(season.AirDate),
				})
			}
		}
	} else if detailErr != nil {
		out.Partial = append(out.Partial, Partial{
			Service: "jellyseerr",
			Reason:  "detail_unavailable",
			Affects: []string{"title", "seasons"},
			Message: "Could not read the title's details",
		})
	}

	writeJSON(w, http.StatusOK, out)
}

type serviceSettings struct {
	Detail *jellyseerr.ServiceDetail
	Has4k  bool
}

// loadServiceSettings picks the instance a request would actually go to.
//
// v1 assumes one non-4K instance per type. If more than one is configured the
// default wins and the caller is told 4K exists, because silently picking the
// wrong server shows the wrong progress with total confidence -- the single
// most confusing failure this system can produce.
func loadServiceSettings(
	ctx context.Context, client *jellyseerr.Client, service string,
) (*serviceSettings, error) {
	servers, err := client.Servers(ctx, service)
	if err != nil {
		return nil, err
	}
	if len(servers) == 0 {
		return nil, &noServerError{service: service}
	}
	chosen := servers[0]
	has4k := false
	for _, server := range servers {
		if server.Is4k {
			has4k = true
			continue
		}
		if server.IsDefault {
			chosen = server
		}
	}
	detail, err := client.ServiceDetail(ctx, service, chosen.ID)
	if err != nil {
		return nil, err
	}
	// The list entry carries the active defaults; the detail's copy of the
	// server does too, but only the list is guaranteed to have been filled in.
	detail.Server = chosen
	return &serviceSettings{Detail: detail, Has4k: has4k}, nil
}

type noServerError struct{ service string }

func (e *noServerError) Error() string {
	return "Jellyseerr has no " + e.service + " server configured"
}

// folderLabel is the last one or two path segments.
//
// "E:\Videos\Daniel\Marvel\Movies" and "E:\Videos\Daniel\Movies" both end in
// "Movies", so one segment is not enough to tell them apart -- and telling them
// apart is the entire reason this field exists on this stack.
func folderLabel(path string) string {
	segments := splitPath(path)
	switch len(segments) {
	case 0:
		return path
	case 1:
		return segments[0]
	default:
		return segments[len(segments)-2] + "\\" + segments[len(segments)-1]
	}
}

func splitPath(path string) []string {
	out := []string{}
	current := ""
	for _, c := range path {
		if c == '\\' || c == '/' {
			if current != "" {
				out = append(out, current)
			}
			current = ""
			continue
		}
		current += string(c)
	}
	if current != "" {
		out = append(out, current)
	}
	return out
}
