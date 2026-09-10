package api

import (
	"context"
	"net/http"
	"sort"
	"strings"
	"sync"
	"time"

	"ayaneohub/internal/adapters/arr"
	"ayaneohub/internal/adapters/qbittorrent"
	"ayaneohub/internal/cache"
)

// Stage names for a transfer. Deliberately a small, plain vocabulary: the app
// should never learn that "stalledDL" and "metaDL" are different words for
// waiting.
const (
	ActDownloading = "downloading"
	ActQueued      = "queued"
	ActSeeding     = "seeding"
	ActImporting   = "importing"
	ActStopped     = "stopped"
	ActStuck       = "stuck"
	ActDone        = "done"
)

type ArrRef struct {
	Service               string `json:"service"`
	QueueID               int    `json:"queueId,omitempty"`
	MovieID               int    `json:"movieId,omitempty"`
	SeriesID              int    `json:"seriesId,omitempty"`
	TmdbID                int    `json:"tmdbId,omitempty"`
	TvdbID                int    `json:"tvdbId,omitempty"`
	TrackedDownloadState  string `json:"trackedDownloadState,omitempty"`
	TrackedDownloadStatus string `json:"trackedDownloadStatus,omitempty"`
	Problem               string `json:"problem,omitempty"`
}

// ActivityItem is one transfer, normalised across three services.
type ActivityItem struct {
	ID         string  `json:"id"`
	Title      string  `json:"title"`
	MediaTitle string  `json:"mediaTitle,omitempty"`
	Stage      string  `json:"stage"`
	Progress   float64 `json:"progress"`

	SizeBytes      int64 `json:"sizeBytes,omitempty"`
	RemainingBytes int64 `json:"remainingBytes,omitempty"`
	SpeedBps       int64 `json:"speedBps,omitempty"`
	UploadBps      int64 `json:"uploadBps,omitempty"`
	// -1 when there is no meaningful estimate, rather than a fake number.
	ETASeconds int64 `json:"etaSeconds"`

	Seeds    int    `json:"seeds,omitempty"`
	Peers    int    `json:"peers,omitempty"`
	Protocol string `json:"protocol,omitempty"`
	Client   string `json:"client,omitempty"`
	// ClientStage stays independent from the combined displayed stage. Sonarr
	// can make a completed transfer "stuck" at import time, but qBittorrent may
	// still be stopped and therefore needs a Start action.
	ClientStage string `json:"clientStage,omitempty"`
	Category    string `json:"category,omitempty"`
	Indexer     string `json:"indexer,omitempty"`

	Arr         *ArrRef `json:"arr,omitempty"`
	TorrentHash string  `json:"torrentHash,omitempty"`
	// exact when the *arr downloadId matched a torrent hash; none when there is
	// no client item to match, which is a real and common state.
	MatchConfidence string   `json:"matchConfidence"`
	Warnings        []string `json:"warnings,omitempty"`
	Actions         []string `json:"actions"`
	// QueueItems is greater than one when Sonarr expanded one season or series
	// pack into an episode row for every file. The transfer is still one thing
	// in qBittorrent, so the API and app keep it as one controllable row.
	QueueItems int `json:"queueItems,omitempty"`
}

type ActivitySummary struct {
	Downloading    int   `json:"downloading"`
	Queued         int   `json:"queued"`
	Seeding        int   `json:"seeding"`
	Stuck          int   `json:"stuck"`
	DownSpeedBytes int64 `json:"downSpeedBytes"`
	UpSpeedBytes   int64 `json:"upSpeedBytes"`
}

type ActivityResponse struct {
	GeneratedAt time.Time       `json:"generatedAt"`
	Summary     ActivitySummary `json:"summary"`
	Items       []ActivityItem  `json:"items"`
	Partial     []Partial       `json:"partial"`
}

type activitySources struct {
	torrents []qbittorrent.Torrent
	queues   map[string][]arr.QueueRecord
	partial  []Partial
	mu       sync.Mutex
}

func (s *Server) handleActivity(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := timeoutFor(r, s.cfg.Server.RequestTimeout.OrDefault(20*time.Second))
	defer cancel()

	// Deliberately not cached beyond a few seconds, and never served stale: a
	// torrent list from two minutes ago is not slightly out of date, it is
	// wrong. It shows finished downloads as running and misses the one that just
	// failed.
	sources, _, err := cache.Fetch(ctx, s.cache, "activity", cache.Downloads,
		func(ctx context.Context) (*activitySources, error) {
			return s.gatherActivity(ctx), nil
		})
	if err != nil {
		writeError(w, r, http.StatusServiceUnavailable, Error{
			Code:      CodeUpstreamDown,
			Message:   "could not read the download queues",
			Retryable: true,
		})
		return
	}

	showAll := r.URL.Query().Get("all") == "true"
	scopes := TokenFrom(r.Context()).Scopes
	out := buildActivity(sources, showAll, scopes)

	// This screen needs any one of the three to be useful, not all of them.
	if len(out.Items) == 0 && len(out.Partial) > 0 && len(out.Partial) >= 3 {
		writeError(w, r, http.StatusServiceUnavailable, Error{
			Code:      CodeUpstreamDown,
			Message:   "no download service is responding",
			Retryable: true,
		})
		return
	}
	writeJSON(w, http.StatusOK, out)
}

// gatherActivity fans out to all three services at once.
//
// A failure in any of them is recorded rather than returned: the screen is
// useful with two of three, and losing Radarr should not blank the Sonarr rows.
func (s *Server) gatherActivity(ctx context.Context) *activitySources {
	sources := &activitySources{queues: map[string][]arr.QueueRecord{}}
	var wg sync.WaitGroup

	if s.qbittorrent != nil {
		wg.Add(1)
		go func() {
			defer wg.Done()
			torrents, err := s.qbittorrent.Torrents(ctx)
			sources.mu.Lock()
			defer sources.mu.Unlock()
			if err != nil {
				sources.partial = append(sources.partial, Partial{
					Service: "qbittorrent",
					Reason:  "unreachable",
					Affects: []string{"activity.progress", "activity.speed"},
					Message: "qBittorrent is not responding",
				})
				return
			}
			sources.torrents = torrents
		}()
	}

	for name, client := range s.arrs {
		wg.Add(1)
		go func(name string, client *arr.Client) {
			defer wg.Done()
			records, err := client.Queue(ctx)
			sources.mu.Lock()
			defer sources.mu.Unlock()
			if err != nil {
				sources.partial = append(sources.partial, Partial{
					Service: name,
					Reason:  "unreachable",
					Affects: []string{"activity.items"},
					Message: name + " is not responding",
				})
				return
			}
			sources.queues[name] = records
		}(name, client)
	}

	wg.Wait()
	return sources
}

// buildActivity is the join, and it is pure so the awkward cases are testable.
func buildActivity(sources *activitySources, showAll bool, scopes []string) ActivityResponse {
	out := ActivityResponse{
		GeneratedAt: time.Now().UTC(),
		Items:       []ActivityItem{},
		Partial:     sources.partial,
	}
	if out.Partial == nil {
		out.Partial = []Partial{}
	}

	// Index every identity a torrent answers to. Three per torrent, because a
	// v2 or hybrid torrent has both infohashes and which one an *arr recorded
	// depends on how it was added.
	byHash := map[string]*qbittorrent.Torrent{}
	for i := range sources.torrents {
		for _, h := range sources.torrents[i].Hashes() {
			byHash[h] = &sources.torrents[i]
		}
	}

	claimed := map[string]bool{}
	canControl := hasScope(scopes, "control")

	// *arr queue rows first: they know what a transfer is actually *for*.
	for service, records := range sources.queues {
		for _, group := range groupQueueRecords(records) {
			item := itemFromQueue(service, group[0], byHash, canControl)
			mergeQueueGroup(service, group, &item, canControl)
			if item.TorrentHash != "" {
				claimed[item.TorrentHash] = true
			}
			out.Items = append(out.Items, item)
		}
	}

	// Then any torrent no *arr claimed: manual adds, and anything left seeding.
	for i := range sources.torrents {
		t := &sources.torrents[i]
		if claimed[strings.ToLower(t.Hash)] {
			continue
		}
		if !showAll && t.IsFinished() && !t.IsError() {
			// Thirty finished torrents seeding away is not "activity", and
			// burying one live download under them makes the screen useless.
			continue
		}
		out.Items = append(out.Items, itemFromTorrent(t, canControl))
	}

	for _, item := range out.Items {
		switch item.Stage {
		case ActDownloading:
			out.Summary.Downloading++
		case ActQueued:
			out.Summary.Queued++
		case ActSeeding:
			out.Summary.Seeding++
		case ActStuck:
			out.Summary.Stuck++
		}
		out.Summary.DownSpeedBytes += item.SpeedBps
		out.Summary.UpSpeedBytes += item.UploadBps
	}

	// Problems first, then the things actually moving, then the rest. Someone
	// opening this screen is usually asking "what is wrong" or "how long".
	sort.SliceStable(out.Items, func(a, b int) bool {
		return stageRank(out.Items[a].Stage) < stageRank(out.Items[b].Stage)
	})
	return out
}

func stageRank(stage string) int {
	switch stage {
	case ActStuck:
		return 0
	case ActDownloading:
		return 1
	case ActImporting:
		return 2
	case ActQueued:
		return 3
	case ActStopped:
		return 4
	case ActSeeding:
		return 5
	}
	return 6
}

func itemFromQueue(
	service string, record arr.QueueRecord,
	byHash map[string]*qbittorrent.Torrent, canControl bool,
) ActivityItem {
	item := ActivityItem{
		ID:         service + ":queue:" + itoa(record.ID),
		Title:      record.Title,
		MediaTitle: record.DisplayTitle(),
		Protocol:   record.Protocol,
		Client:     record.DownloadClient,
		Indexer:    record.Indexer,
		ETASeconds: -1,
		Arr: &ArrRef{
			Service:               service,
			QueueID:               record.ID,
			MovieID:               record.MovieID,
			SeriesID:              record.SeriesID,
			TrackedDownloadState:  record.TrackedDownloadState,
			TrackedDownloadStatus: record.TrackedDownloadStatus,
			Problem:               record.Problem(),
		},
		SizeBytes:      int64(record.Size),
		RemainingBytes: int64(record.SizeLeft),
	}
	if record.Movie != nil {
		item.Arr.TmdbID = record.Movie.TmdbID
	}
	if record.Series != nil {
		item.Arr.TvdbID = record.Series.TvdbID
	}
	if record.Size > 0 {
		item.Progress = (record.Size - record.SizeLeft) / record.Size
	}

	var torrent *qbittorrent.Torrent
	if record.DownloadID != "" {
		// Case-insensitive: Sonarr stores the hash uppercased.
		torrent = byHash[strings.ToLower(record.DownloadID)]
	}

	switch {
	case torrent != nil:
		item.MatchConfidence = "exact"
		item.TorrentHash = strings.ToLower(torrent.Hash)
		// Control endpoints accept the download client's identity. A matched
		// *arr queue id is useful metadata, but it cannot start or stop a
		// qBittorrent transfer.
		item.ID = "qbit:" + item.TorrentHash
		item.Progress = torrent.Progress
		item.SizeBytes = torrent.Size
		item.RemainingBytes = torrent.AmountLeft
		item.SpeedBps = torrent.DownloadDPS
		item.UploadBps = torrent.UploadBPS
		item.ETASeconds = torrent.ETASeconds()
		item.Seeds = torrent.Seeds
		item.Peers = torrent.Leechers
		item.Category = torrent.Category
		item.ClientStage = stageForTorrent(torrent)
		item.Stage = item.ClientStage
		if torrent.IsFinished() && stageForArr(record) == ActImporting {
			item.Stage = ActImporting
		}

	case record.DownloadID == "":
		// The phantom-row bug. Not dropped, because a stuck row is precisely
		// what someone opened this screen to find.
		item.MatchConfidence = "none"
		item.Stage = ActStuck
		item.Warnings = append(item.Warnings, "no_client_item")

	case strings.EqualFold(record.Protocol, "usenet"):
		// downloadId is an nzo_id, so there was never a torrent to find. The
		// *arr's own numbers are all there is, and that is not a fault.
		item.MatchConfidence = "none"
		item.Stage = stageForArr(record)
		item.Warnings = append(item.Warnings, "usenet_no_client_detail")

	default:
		// A hash that matches nothing: cross-seed, a recheck, a re-add, or a
		// torrent removed behind the *arr's back.
		item.MatchConfidence = "none"
		item.Stage = stageForArr(record)
		item.Warnings = append(item.Warnings, "unmatched_download")
	}

	if record.IsStuck() {
		item.Stage = ActStuck
	}
	item.Actions = actionsForActivity(item, canControl)
	return item
}

// groupQueueRecords folds Sonarr's per-episode queue rows back into the one
// download-client job they describe. Records without a download id remain
// independent because there is no safe evidence that they belong together.
func groupQueueRecords(records []arr.QueueRecord) [][]arr.QueueRecord {
	groups := make([][]arr.QueueRecord, 0, len(records))
	positions := make(map[string]int, len(records))
	for _, record := range records {
		key := "queue:" + itoa(record.ID)
		if record.DownloadID != "" {
			key = "download:" + strings.ToLower(record.DownloadID)
		}
		if at, ok := positions[key]; ok {
			groups[at] = append(groups[at], record)
			continue
		}
		positions[key] = len(groups)
		groups = append(groups, []arr.QueueRecord{record})
	}
	return groups
}

func mergeQueueGroup(
	service string, records []arr.QueueRecord, item *ActivityItem, canControl bool,
) {
	if len(records) <= 1 {
		return
	}
	item.QueueItems = len(records)
	if item.TorrentHash == "" && records[0].DownloadID != "" {
		item.ID = service + ":download:" + strings.ToLower(records[0].DownloadID)
	}
	if series := records[0].Series; series != nil && series.Title != "" {
		item.MediaTitle = series.Title
	}

	importing := false
	for _, record := range records {
		if stageForArr(record) == ActImporting {
			importing = true
		}
		if !record.IsStuck() {
			continue
		}
		item.Stage = ActStuck
		if item.Arr != nil && item.Arr.Problem == "" {
			item.Arr.Problem = record.Problem()
		}
	}
	if item.Stage != ActStuck && item.Progress >= 1 && importing {
		item.Stage = ActImporting
	}
	item.Actions = actionsForActivity(*item, canControl)
}

func itemFromTorrent(t *qbittorrent.Torrent, canControl bool) ActivityItem {
	item := ActivityItem{
		ID:              "qbit:" + strings.ToLower(t.Hash),
		Title:           t.Name,
		Stage:           stageForTorrent(t),
		Progress:        t.Progress,
		SizeBytes:       t.Size,
		RemainingBytes:  t.AmountLeft,
		SpeedBps:        t.DownloadDPS,
		UploadBps:       t.UploadBPS,
		ETASeconds:      t.ETASeconds(),
		Seeds:           t.Seeds,
		Peers:           t.Leechers,
		Category:        t.Category,
		Protocol:        "torrent",
		Client:          "qBittorrent",
		ClientStage:     stageForTorrent(t),
		TorrentHash:     strings.ToLower(t.Hash),
		MatchConfidence: "none",
	}
	if t.IsError() {
		item.Warnings = append(item.Warnings, t.State)
	}
	item.Actions = actionsForActivity(item, canControl)
	return item
}

// stageForTorrent flattens qBittorrent's fifteen states into six words.
func stageForTorrent(t *qbittorrent.Torrent) string {
	switch t.State {
	case "error", "missingFiles":
		return ActStuck
	case "stoppedUP", "pausedUP", "stoppedDL", "pausedDL":
		return ActStopped
	case "uploading", "stalledUP", "forcedUP", "checkingUP":
		return ActSeeding
	case "queuedDL", "queuedUP", "allocating", "metaDL", "forcedMetaDL", "checkingDL",
		"checkingResumeData", "moving":
		return ActQueued
	case "downloading", "forcedDL", "stalledDL":
		if t.State == "stalledDL" && t.DownloadDPS == 0 {
			return ActQueued
		}
		return ActDownloading
	}
	if t.IsFinished() {
		return ActDone
	}
	return ActQueued
}

func stageForArr(record arr.QueueRecord) string {
	switch strings.ToLower(record.Status) {
	case "completed":
		return ActImporting
	case "paused":
		return ActStopped
	case "queued", "delay", "downloadclientunavailable":
		return ActQueued
	}
	if record.TrackedDownloadState == "importPending" ||
		record.TrackedDownloadState == "importing" {
		return ActImporting
	}
	return ActDownloading
}

func actionsForActivity(item ActivityItem, canControl bool) []string {
	if !canControl {
		return []string{}
	}
	actions := make([]string, 0, 4)
	if item.TorrentHash != "" {
		if item.ClientStage == ActStopped {
			actions = append(actions, "start")
		} else {
			actions = append(actions, "stop")
		}
		actions = append(actions, "delete", "delete_with_data")
	}
	if item.Arr != nil {
		// Removing a stuck row and blocklisting the release is the fix for the
		// most common failure, so it is offered directly rather than buried.
		actions = append(actions, "arr_remove")
		if item.Stage == ActStuck {
			actions = append(actions, "arr_blocklist_and_search")
		}
	}
	return actions
}

func hasScope(scopes []string, want string) bool {
	for _, s := range scopes {
		if s == want {
			return true
		}
	}
	return false
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	negative := n < 0
	if negative {
		n = -n
	}
	var buf [20]byte
	i := len(buf)
	for n > 0 {
		i--
		buf[i] = byte('0' + n%10)
		n /= 10
	}
	if negative {
		i--
		buf[i] = '-'
	}
	return string(buf[i:])
}
