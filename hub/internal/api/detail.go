package api

import (
	"fmt"
	"strconv"
	"strings"

	"ayaneohub/internal/adapters/jellyseerr"
)

// MediaKey is a parsed "tmdb:movie:27205".
type MediaKey struct {
	Source string // always "tmdb" today
	Type   string // movie | series
	ID     int
}

// JellyseerrType maps back to the word Jellyseerr uses on the wire.
func (k MediaKey) JellyseerrType() string {
	if k.Type == "series" {
		return "tv"
	}
	return "movie"
}

func (k MediaKey) String() string {
	return fmt.Sprintf("%s:%s:%d", k.Source, k.Type, k.ID)
}

// ParseMediaKey is strict on purpose.
//
// The key goes into an upstream URL path, so anything unrecognised is rejected
// rather than passed through and hoped for.
func ParseMediaKey(raw string) (MediaKey, error) {
	parts := strings.Split(raw, ":")
	if len(parts) != 3 {
		return MediaKey{}, fmt.Errorf("key must be source:type:id, got %q", raw)
	}
	source, mediaType, idText := parts[0], parts[1], parts[2]
	if source != "tmdb" {
		return MediaKey{}, fmt.Errorf("unsupported id source %q", source)
	}
	if mediaType != "movie" && mediaType != "series" {
		return MediaKey{}, fmt.Errorf("type must be movie or series, got %q", mediaType)
	}
	id, err := strconv.Atoi(idText)
	if err != nil || id <= 0 {
		return MediaKey{}, fmt.Errorf("%q is not a positive id", idText)
	}
	return MediaKey{Source: source, Type: mediaType, ID: id}, nil
}

// Stage is one step of the journey from "I want this" to "it is in the library".
//
// The state vocabulary is deliberately small and includes "stuck" and "unknown":
// a stage that failed and a stage we could not ask about are different things,
// and collapsing them into "pending" is how an app quietly lies.
type Stage struct {
	ID string `json:"id"`
	// The full label, for a list.
	Label string `json:"label"`
	// A one- or two-word version, for the horizontal strip where five of these
	// have to fit across a 7-inch screen.
	Short  string `json:"short"`
	State  string `json:"state"` // done | active | pending | failed | stuck | unknown
	Detail string `json:"detail,omitempty"`
	Source string `json:"source"`
	// 0..1 while active, or -1 when the size is unknown.
	Progress float64 `json:"progress,omitempty"`
	// True when the answer is missing because a service could not be reached,
	// rather than because the step has not happened.
	Degraded bool `json:"degraded,omitempty"`
}

const (
	StageDone    = "done"
	StageActive  = "active"
	StagePending = "pending"
	StageFailed  = "failed"
	StageStuck   = "stuck"
	StageUnknown = "unknown"
)

type Pipeline struct {
	Summary string  `json:"summary"`
	Stages  []Stage `json:"stages"`
}

type MediaDetail struct {
	Media          MediaRef `json:"media"`
	Overview       string   `json:"overview,omitempty"`
	RuntimeMinutes int      `json:"runtimeMinutes,omitempty"`
	Genres         []string `json:"genres,omitempty"`
	Rating         float64  `json:"rating,omitempty"`
	Seasons        int      `json:"seasons,omitempty"`
	Episodes       int      `json:"episodes,omitempty"`
	// The seasons themselves, for a series. Sent here so the app can ask which
	// season to search for releases in without a second round trip -- Sonarr
	// has no "search the whole series" call, a season number is required.
	SeasonList []SeasonOption `json:"seasonList,omitempty"`
	// A YouTube watch URL, when TMDB knows of a trailer. Empty is common --
	// The Mentalist has only behind-the-scenes clips -- so the app hides the
	// button rather than offering one that goes nowhere.
	TrailerURL string `json:"trailerUrl,omitempty"`
	// The bare YouTube id as well, because the app plays the trailer in an
	// embedded player and the embed path takes an id, not a watch URL. Sending
	// both means the app never parses a URL.
	TrailerKey     string       `json:"trailerKey,omitempty"`
	Availability   string       `json:"availability"`
	JellyfinItemID string       `json:"jellyfinItemId,omitempty"`
	Cast           []CastMember `json:"cast,omitempty"`
	Pipeline       Pipeline     `json:"pipeline"`
	Actions        []string     `json:"actions"`
	Partial        []Partial    `json:"partial"`
	Cache          CacheInfo    `json:"cache"`
}

// buildPipeline describes where a title actually is.
//
// In this phase every stage comes from Jellyseerr alone, which is further than
// it sounds: its mediaInfo carries the request, the *arr service ids, and a live
// downloadStatus fed by its own tracker polling the *arr queues. The remaining
// adapters will sharpen the grab and import stages rather than replace them.
func buildPipeline(info *jellyseerr.MediaInfo, mediaType string) Pipeline {
	if info == nil {
		return Pipeline{
			Summary: "Not requested",
			Stages: []Stage{
				{ID: "request", Label: "Request", Short: "Request", State: StagePending, Source: "jellyseerr"},
				{ID: "grab", Label: "Find a release", Short: "Grab", State: StagePending, Source: "arr"},
				{ID: "download", Label: "Download", Short: "Download", State: StagePending, Source: "download client"},
				{ID: "import", Label: "Import to library", Short: "Import", State: StagePending, Source: "arr"},
				{ID: "library", Label: "In Jellyfin", Short: "Library", State: StagePending, Source: "jellyfin"},
			},
		}
	}

	downloading := len(info.DownloadStatus) > 0
	available := info.Status == jellyseerr.StatusAvailable
	partial := info.Status == jellyseerr.StatusPartiallyAvailable
	processing := info.Status == jellyseerr.StatusProcessing
	pending := info.Status == jellyseerr.StatusPending

	request := Stage{ID: "request", Label: "Requested", Short: "Request", Source: "jellyseerr", State: StageDone}
	switch {
	case pending:
		request.Detail = "waiting for approval"
	case info.Status == jellyseerr.StatusUnknown:
		request.State = StagePending
		request.Label = "Request"
	}

	grab := Stage{ID: "grab", Label: "Find a release", Short: "Grab", Source: "arr", State: StagePending}
	if downloading || available || partial {
		grab.State = StageDone
		if downloading {
			grab.Detail = info.DownloadStatus[0].Title
		}
	} else if processing {
		grab.State = StageActive
		grab.Detail = "searching indexers"
	}

	download := Stage{ID: "download", Label: "Download", Short: "Download", Source: "download client", State: StagePending}
	switch {
	case downloading:
		d := info.DownloadStatus[0]
		download.State = StageActive
		download.Progress = d.Progress()
		download.Detail = describeTransfer(d)
	case available || partial:
		download.State = StageDone
	}

	imported := Stage{ID: "import", Label: "Import to library", Short: "Import", Source: "arr", State: StagePending}
	if available || partial {
		imported.State = StageDone
	} else if downloading {
		imported.State = StagePending
	}

	library := Stage{ID: "library", Label: "In Jellyfin", Short: "Library", Source: "jellyfin", State: StagePending}
	switch {
	case available:
		library.State = StageDone
		if info.JellyfinMediaID != "" {
			library.Detail = "ready to play"
		}
	case partial:
		library.State = StageActive
		library.Detail = "some episodes available"
	}

	return Pipeline{
		Summary: summarise(info, mediaType),
		Stages:  []Stage{request, grab, download, imported, library},
	}
}

// refinePipelineWithActivity replaces Jellyseerr's delayed transfer snapshot
// with the live *arr/qBittorrent join when both refer to the same monitored
// movie or series. It deliberately leaves library availability with Jellyseerr
// and Jellyfin; the activity services only know about the journey there.
func refinePipelineWithActivity(
	p Pipeline, mediaType string, externalServiceID int, ids MediaID, items []ActivityItem,
) Pipeline {
	service := "radarr"
	if mediaType == "series" {
		service = "sonarr"
	}
	var match *ActivityItem
	for i := range items {
		item := &items[i]
		if item.Arr == nil || item.Arr.Service != service {
			continue
		}
		id := item.Arr.MovieID
		if mediaType == "series" {
			id = item.Arr.SeriesID
		}
		idMatches := externalServiceID > 0 && id == externalServiceID
		providerMatches := mediaType == "series" && ids.Tvdb > 0 && item.Arr.TvdbID == ids.Tvdb
		providerMatches = providerMatches ||
			(mediaType == "movie" && ids.Tmdb > 0 && item.Arr.TmdbID == ids.Tmdb)
		if !idMatches && !providerMatches {
			continue
		}
		if match == nil || stageRank(item.Stage) < stageRank(match.Stage) {
			match = item
		}
	}
	if match == nil {
		return p
	}

	request := pipelineStage(&p, "request")
	grab := pipelineStage(&p, "grab")
	download := pipelineStage(&p, "download")
	imported := pipelineStage(&p, "import")
	if request != nil {
		request.State = StageDone
	}
	if grab != nil {
		grab.State = StageDone
		grab.Detail = match.Title
	}
	if download == nil || imported == nil {
		return p
	}

	download.Source = "download client"
	download.Progress = match.Progress
	detail := activityPipelineDetail(*match)
	switch match.Stage {
	case ActDownloading, ActQueued:
		download.State = StageActive
		download.Detail = detail
		imported.State = StagePending
		p.Summary = strings.Title(match.Stage) + " — " + detail
	case ActStopped:
		download.State = StageStuck
		download.Detail = detail
		imported.State = StagePending
		p.Summary = "Download stopped — " + detail
	case ActImporting:
		download.State = StageDone
		download.Progress = 1
		download.Detail = "100%"
		imported.State = StageActive
		imported.Detail = activityProblem(*match)
		p.Summary = "Importing into the library"
	case ActStuck:
		if match.Progress >= .999 {
			download.State = StageDone
			download.Progress = 1
			imported.State = StageStuck
			imported.Detail = activityProblem(*match)
			p.Summary = "Import stuck"
		} else {
			download.State = StageStuck
			download.Detail = activityProblem(*match)
			imported.State = StagePending
			p.Summary = "Download stuck"
		}
	case ActSeeding, ActDone:
		download.State = StageDone
		download.Progress = 1
		download.Detail = "100%"
	}
	return p
}

func pipelineStage(p *Pipeline, id string) *Stage {
	for i := range p.Stages {
		if p.Stages[i].ID == id {
			return &p.Stages[i]
		}
	}
	return nil
}

func activityPipelineDetail(item ActivityItem) string {
	parts := make([]string, 0, 2)
	if item.Progress > 0 {
		parts = append(parts, fmt.Sprintf("%.0f%%", item.Progress*100))
	}
	if item.SpeedBps > 0 {
		parts = append(parts, fmt.Sprintf("%.1f MB/s", float64(item.SpeedBps)/1_000_000))
	}
	if len(parts) == 0 {
		return strings.Title(item.Stage)
	}
	return strings.Join(parts, " · ")
}

func activityProblem(item ActivityItem) string {
	if item.Arr != nil && item.Arr.Problem != "" {
		return item.Arr.Problem
	}
	if len(item.Warnings) > 0 {
		return item.Warnings[0]
	}
	return activityPipelineDetail(item)
}

func describeTransfer(d jellyseerr.DownloadingItem) string {
	parts := make([]string, 0, 3)
	if p := d.Progress(); p >= 0 {
		parts = append(parts, fmt.Sprintf("%.0f%%", p*100))
	}
	if d.TimeLeft != "" && d.TimeLeft != "00:00:00" {
		parts = append(parts, d.TimeLeft+" left")
	}
	if d.Status != "" {
		parts = append(parts, d.Status)
	}
	return strings.Join(parts, " · ")
}

// summarise is the one line the app puts at the top of a detail screen.
func summarise(info *jellyseerr.MediaInfo, mediaType string) string {
	if len(info.DownloadStatus) > 0 {
		return "Downloading — " + describeTransfer(info.DownloadStatus[0])
	}
	switch info.Status {
	case jellyseerr.StatusAvailable:
		return "In your library"
	case jellyseerr.StatusPartiallyAvailable:
		if mediaType == "series" {
			return "Some episodes available"
		}
		return "Partially available"
	case jellyseerr.StatusProcessing:
		return "Looking for a release"
	case jellyseerr.StatusPending:
		return "Requested — waiting for approval"
	case jellyseerr.StatusBlocklisted:
		return "Blocked"
	case jellyseerr.StatusDeleted:
		return "Removed from the library"
	}
	return "Not requested"
}
