package api

import "strings"

// Explanations use observed states only. Suggestions never imply an action ran.
// Action is an existing scoped action, not a separate permission path.
type ActivityDiagnosis struct {
	Code           string   `json:"code"`
	Title          string   `json:"title"`
	Explanation    string   `json:"explanation"`
	Evidence       []string `json:"evidence"`
	NextStep       string   `json:"nextStep"`
	NeedsAttention bool     `json:"needsAttention"`
	Action         string   `json:"action,omitempty"`
}

func diagnoseActivity(item ActivityItem, partial []Partial) *ActivityDiagnosis {
	d := &ActivityDiagnosis{Evidence: []string{}}
	if item.ClientState != "" {
		d.Evidence = append(d.Evidence, "qBittorrent: "+item.ClientState)
	}
	if item.Arr != nil {
		if item.Arr.TrackedDownloadState != "" {
			d.Evidence = append(d.Evidence, item.Arr.Service+": "+item.Arr.TrackedDownloadState)
		}
		if item.Arr.Problem != "" {
			d.Evidence = append(d.Evidence, item.Arr.Problem)
		}
	}
	hasWarning := func(w string) bool {
		for _, v := range item.Warnings {
			if v == w {
				return true
			}
		}
		return false
	}
	set := func(code, title, explanation, next string, attention bool) *ActivityDiagnosis {
		d.Code = code
		d.Title = title
		d.Explanation = explanation
		d.NextStep = next
		d.NeedsAttention = attention
		return d
	}
	// An unreachable client cannot establish that a download has disappeared.
	for _, p := range partial {
		if p.Service == "qbittorrent" && item.TorrentHash == "" && (hasWarning("unmatched_download") || hasWarning("no_client_item")) {
			return set("client_unavailable", "Download client unavailable", "The Hub could not check qBittorrent, so this transfer's client status is unknown.", "Check qBittorrent in Manage, then refresh Transfers.", true)
		}
	}
	if item.ClientState == "missingFiles" {
		return set("missing_files", "Downloaded files are missing", "qBittorrent reports missing files. The current state does not tell us whether they were moved, deleted, or the drive disconnected.", "Check the download drive and save location in qBittorrent before retrying.", true)
	}
	if item.ClientState == "error" {
		return set("client_error", "Download client reported an error", "qBittorrent marked this transfer as an error. A disk or permissions problem has not been confirmed.", "Open qBittorrent and check the transfer error, free disk space, and folder permissions.", true)
	}
	if item.Arr != nil && strings.TrimSpace(item.Arr.Problem) != "" && item.Stage == ActStuck {
		if item.Progress >= 1 || item.ClientStage == ActSeeding || strings.HasPrefix(strings.ToLower(item.Arr.TrackedDownloadState), "import") {
			return set("import_blocked", "Import needs attention", item.Arr.Service+" reported a problem with this release. Its exact message is shown below.", "Check the reported import issue in "+item.Arr.Service+". Search for another release only if this one cannot be used.", true)
		}
		return set("queue_problem", "Queue needs attention", item.Arr.Service+" reported a problem with this release. Its exact message is shown below.", "Review the reported problem in "+item.Arr.Service+" before retrying or replacing this release.", true)
	}
	if hasWarning("no_client_item") || hasWarning("unmatched_download") {
		return set("unmatched_download", "Queue and client do not match", "The queue entry could not be matched to a current client transfer. This alone does not prove its files are missing.", "Check the download client and the Radarr/Sonarr queue before removing this entry.", true)
	}
	if item.ClientStage == ActStopped || item.Stage == ActStopped {
		for _, a := range item.Actions {
			if a == "start" {
				d.Action = "start"
			}
		}
		return set("paused", "Transfer is paused", "The download client is not running this transfer.", "Resume when you want it to continue.", false)
	}
	switch item.ClientState {
	case "stalledDL", "metaDL", "forcedMetaDL":
		if item.SpeedBps == 0 {
			d.Evidence = append(d.Evidence, "Connected seeds: "+itoa(item.Seeds)+" · peers: "+itoa(item.Peers))
			return set("waiting_peers", "Waiting for transfer data", "No data is arriving in the current snapshot. Peers may be unavailable or not supplying the needed data; this does not establish a permanent failure.", "Refresh after a few minutes. If it remains stalled, inspect peers and trackers in qBittorrent or search for another release.", true)
		}
	case "queuedDL", "queuedUP":
		return set("queued", "Waiting in the download queue", "qBittorrent has queued this transfer behind its active-transfer limits.", "Let an active transfer finish, or review queue limits and priority in qBittorrent.", false)
	case "checkingDL", "checkingUP", "checkingResumeData", "moving", "allocating":
		return set("checking", "Preparing or checking files", "qBittorrent is checking, allocating, or moving the files.", "Allow this operation to finish; refresh to see the next state.", false)
	}
	switch item.Stage {
	case ActImporting:
		return set("importing", "Waiting for library import", "The download queue reports an import in progress. Jellyfin availability has not been checked here.", "Allow Radarr/Sonarr to import the files, then check the title in Library.", false)
	case ActDownloading:
		return set("downloading", "Download is in progress", "The latest queue state reports an active download.", "Keep downloading; refresh to see updated progress.", false)
	case ActDone, ActSeeding:
		return set("download_complete", "Download is complete", "The client has finished downloading. This does not confirm a successful import or Jellyfin scan.", "Open the title in Library to check availability.", false)
	case ActStuck:
		return set("reported_problem", "Transfer needs attention", "A service marked this transfer as stuck, but supplied no more specific cause.", "Review the queue details in its source service before taking action.", true)
	case ActQueued:
		return set("queued", "Transfer is waiting", "The source queue reports a waiting transfer without a more specific reason.", "Refresh later or inspect the source queue for details.", false)
	}
	return set("unknown", "Not enough information", "The available response does not establish where this transfer is blocked.", "Refresh Transfers and check service health in Manage.", false)
}
