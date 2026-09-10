package api

import (
	"testing"

	"ayaneohub/internal/adapters/arr"
	"ayaneohub/internal/adapters/qbittorrent"
)

func sources(
	torrents []qbittorrent.Torrent, queues map[string][]arr.QueueRecord,
) *activitySources {
	if queues == nil {
		queues = map[string][]arr.QueueRecord{}
	}
	return &activitySources{torrents: torrents, queues: queues}
}

func itemByID(r ActivityResponse, id string) *ActivityItem {
	for i := range r.Items {
		if r.Items[i].ID == id {
			return &r.Items[i]
		}
	}
	return nil
}

const control = "control"

func TestTheArrToTorrentJoinIsCaseInsensitive(t *testing.T) {
	// Sonarr stores downloadId as the torrent hash **uppercased**. Verified in
	// its source, and verified live on this stack: E21600E8... matched a
	// lower-case qBittorrent hash. A case-sensitive compare finds nothing and
	// every row looks orphaned.
	torrents := []qbittorrent.Torrent{{
		Hash: "abc123def456", Name: "Some.Release", State: "downloading",
		Progress: 0.63, Size: 1000, AmountLeft: 370, DownloadDPS: 4_200_000,
	}}
	queues := map[string][]arr.QueueRecord{"sonarr": {{
		ID: 7, DownloadID: "ABC123DEF456", Title: "Some.Release",
		Series: &arr.Series{Title: "A Show"},
	}}}

	out := buildActivity(sources(torrents, queues), false, []string{control})
	item := itemByID(out, "qbit:abc123def456")
	if item == nil {
		t.Fatal("the queue row vanished")
	}
	if item.MatchConfidence != "exact" {
		t.Fatalf("MatchConfidence = %q, want exact", item.MatchConfidence)
	}
	if item.Progress < 0.62 || item.Progress > 0.64 {
		t.Fatalf("Progress = %v, want the torrent's 0.63", item.Progress)
	}
	if item.SpeedBps != 4_200_000 {
		t.Fatalf("SpeedBps = %d -- the torrent's live speed should win", item.SpeedBps)
	}
}

func TestAMatchedTorrentIsNotListedTwice(t *testing.T) {
	torrents := []qbittorrent.Torrent{{
		Hash: "aaa", Name: "R", State: "downloading", Progress: 0.5,
	}}
	queues := map[string][]arr.QueueRecord{"radarr": {{ID: 1, DownloadID: "AAA"}}}
	out := buildActivity(sources(torrents, queues), false, nil)
	if len(out.Items) != 1 {
		t.Fatalf("got %d items, want the queue row only", len(out.Items))
	}
}

func TestHybridTorrentsMatchOnEitherInfohash(t *testing.T) {
	// A v2 or hybrid torrent has both, and which one an *arr recorded depends on
	// how it was added.
	torrents := []qbittorrent.Torrent{{
		Hash: "v2hash", InfohashV1: "v1hash", InfohashV2: "v2hash",
		Name: "R", State: "downloading",
	}}
	queues := map[string][]arr.QueueRecord{"radarr": {{ID: 1, DownloadID: "V1HASH"}}}
	out := buildActivity(sources(torrents, queues), false, nil)
	if itemByID(out, "qbit:v2hash").MatchConfidence != "exact" {
		t.Fatal("a v1 infohash should have matched")
	}
}

func TestAPhantomQueueRowIsSurfacedAsStuck(t *testing.T) {
	// A failed grab leaves a row with no downloadId that never auto-cleans
	// (Radarr #11585, Sonarr #8852). It is exactly what someone opens this
	// screen to find, so it must never be silently dropped.
	queues := map[string][]arr.QueueRecord{"radarr": {{
		ID: 3, Title: "Something.That.Failed", DownloadID: "",
	}}}
	out := buildActivity(sources(nil, queues), false, []string{control})
	item := itemByID(out, "radarr:queue:3")
	if item == nil {
		t.Fatal("the phantom row was dropped")
	}
	if item.Stage != ActStuck {
		t.Fatalf("Stage = %q, want stuck", item.Stage)
	}
	if !contains(item.Warnings, "no_client_item") {
		t.Fatalf("Warnings = %v", item.Warnings)
	}
}

func TestUsenetIsNotReportedAsAMissingTorrent(t *testing.T) {
	// downloadId is an nzo_id, so there was never a torrent to find. That is not
	// a fault and must not be dressed up as one.
	queues := map[string][]arr.QueueRecord{"sonarr": {{
		ID: 4, DownloadID: "SABnzbd_nzo_abc", Protocol: "usenet",
		Size: 100, SizeLeft: 40, Status: "downloading",
	}}}
	out := buildActivity(sources(nil, queues), false, nil)
	item := itemByID(out, "sonarr:queue:4")
	if contains(item.Warnings, "unmatched_download") {
		t.Error("usenet was reported as an unmatched torrent")
	}
	if !contains(item.Warnings, "usenet_no_client_detail") {
		t.Errorf("Warnings = %v", item.Warnings)
	}
	// The *arr's own numbers are all there is, and they should still be used.
	if item.Progress < 0.59 || item.Progress > 0.61 {
		t.Errorf("Progress = %v, want ~0.6 from the arr's sizeleft", item.Progress)
	}
}

func TestAnUnmatchedTorrentHashIsWarnedAbout(t *testing.T) {
	// Cross-seed, a recheck, a re-add, or a torrent removed behind the arr's back.
	queues := map[string][]arr.QueueRecord{"radarr": {{
		ID: 5, DownloadID: "HASHTHATISGONE", Protocol: "torrent",
	}}}
	out := buildActivity(sources(nil, queues), false, nil)
	if !contains(itemByID(out, "radarr:queue:5").Warnings, "unmatched_download") {
		t.Fatal("an orphaned downloadId should be flagged")
	}
}

func TestAnArrWarningMakesTheRowStuckEvenAtFullProgress(t *testing.T) {
	// The live case that found a real problem on this stack: a fully downloaded
	// episode Sonarr refused to import because the file was named .mkv.exe.
	torrents := []qbittorrent.Torrent{{
		Hash: "aaa", Name: "Show.S01E01.mkv.exe", State: "uploading", Progress: 1.0,
	}}
	queues := map[string][]arr.QueueRecord{"sonarr": {{
		ID: 9, DownloadID: "AAA", Status: "completed",
		TrackedDownloadStatus: "warning", TrackedDownloadState: "importPending",
		StatusMessages: []arr.StatusMessage{{
			Title:    "Show.S01E01.mkv.exe",
			Messages: []string{"Caution: Found executable file with extension: '.exe'"},
		}},
	}}}
	out := buildActivity(sources(torrents, queues), false, nil)
	item := itemByID(out, "qbit:aaa")
	if item.Stage != ActStuck {
		t.Fatalf("Stage = %q -- 100%% downloaded but not importable is stuck", item.Stage)
	}
	if item.Arr.Problem == "" {
		t.Fatal("the reason must be carried through, not just the state")
	}
}

func TestEpisodeRowsSharingOneTorrentBecomeOneSeriesPack(t *testing.T) {
	torrents := []qbittorrent.Torrent{{
		Hash: "packhash", Name: "The.Mentalist.S01-S08", State: "downloading",
		Progress: .4, Size: 40_000, AmountLeft: 24_000,
	}}
	series := &arr.Series{Title: "The Mentalist"}
	queues := map[string][]arr.QueueRecord{"sonarr": {
		{ID: 11, DownloadID: "PACKHASH", Title: "The.Mentalist.S01-S08", Series: series,
			Episode: &arr.Episode{SeasonNumber: 1, EpisodeNumber: 1}},
		{ID: 12, DownloadID: "PACKHASH", Title: "The.Mentalist.S01-S08", Series: series,
			Episode: &arr.Episode{SeasonNumber: 1, EpisodeNumber: 2}},
	}}

	out := buildActivity(sources(torrents, queues), false, []string{control})
	if len(out.Items) != 1 {
		t.Fatalf("got %d rows for one torrent, want one", len(out.Items))
	}
	item := out.Items[0]
	if item.ID != "qbit:packhash" {
		t.Fatalf("ID = %q, want the controllable qBittorrent id", item.ID)
	}
	if item.MediaTitle != "The Mentalist" || item.QueueItems != 2 {
		t.Fatalf("pack metadata = %+v", item)
	}
	if !contains(item.Actions, "stop") {
		t.Fatalf("Actions = %v, want stop", item.Actions)
	}
}

func TestFinishedTorrentsAreHiddenUnlessAskedFor(t *testing.T) {
	// Thirty seeding torrents is not "activity", and burying one live download
	// under them makes the screen useless. Measured: this stack has 30 torrents
	// and 1 queue row.
	torrents := []qbittorrent.Torrent{
		{Hash: "a", Name: "Seeding", State: "uploading", Progress: 1},
		{Hash: "b", Name: "Live", State: "downloading", Progress: 0.2, DownloadDPS: 1},
	}
	quiet := buildActivity(sources(torrents, nil), false, nil)
	if len(quiet.Items) != 1 || quiet.Items[0].Title != "Live" {
		t.Fatalf("expected only the live download, got %d items", len(quiet.Items))
	}
	everything := buildActivity(sources(torrents, nil), true, nil)
	if len(everything.Items) != 2 {
		t.Fatalf("all=true should show both, got %d", len(everything.Items))
	}
}

func TestABrokenTorrentIsShownEvenWhenFinished(t *testing.T) {
	// missingFiles at 100% is exactly the thing that must not be filtered out.
	// Three of these were sitting unnoticed on this stack.
	torrents := []qbittorrent.Torrent{
		{Hash: "a", Name: "Gone", State: "missingFiles", Progress: 1},
	}
	out := buildActivity(sources(torrents, nil), false, nil)
	if len(out.Items) != 1 || out.Items[0].Stage != ActStuck {
		t.Fatalf("a missingFiles torrent was hidden: %+v", out.Items)
	}
}

func TestProblemsSortToTheTop(t *testing.T) {
	torrents := []qbittorrent.Torrent{
		{Hash: "a", Name: "Seeding", State: "uploading", Progress: 1},
		{Hash: "b", Name: "Live", State: "downloading", Progress: 0.2, DownloadDPS: 1},
		{Hash: "c", Name: "Broken", State: "missingFiles", Progress: 1},
	}
	out := buildActivity(sources(torrents, nil), true, nil)
	if out.Items[0].Stage != ActStuck {
		t.Fatalf("first item is %q -- problems come first", out.Items[0].Stage)
	}
}

func TestStageMapping(t *testing.T) {
	cases := map[string]string{
		"downloading": ActDownloading, "forcedDL": ActDownloading,
		"uploading": ActSeeding, "stalledUP": ActSeeding,
		"stoppedUP": ActStopped, "pausedUP": ActStopped, // both vocabularies
		"queuedDL": ActQueued, "metaDL": ActQueued, "moving": ActQueued,
		"error": ActStuck, "missingFiles": ActStuck,
	}
	for state, want := range cases {
		got := stageForTorrent(&qbittorrent.Torrent{State: state, DownloadDPS: 1})
		if got != want {
			t.Errorf("%s -> %q, want %q", state, got, want)
		}
	}
	// A stalled download with no bytes moving is waiting, not downloading.
	if got := stageForTorrent(&qbittorrent.Torrent{State: "stalledDL"}); got != ActQueued {
		t.Errorf("stalled with no speed -> %q, want queued", got)
	}
}

func TestUnknownEtaIsMinusOneNotAHundredDays(t *testing.T) {
	// qBittorrent reports 8640000 when it has nothing to estimate from, and
	// "100 days left" next to a stalled torrent is worse than saying nothing.
	if got := (qbittorrent.Torrent{ETA: 8640000}).ETASeconds(); got != -1 {
		t.Errorf("ETASeconds = %d, want -1", got)
	}
	if got := (qbittorrent.Torrent{ETA: 0}).ETASeconds(); got != -1 {
		t.Errorf("ETASeconds = %d, want -1", got)
	}
	if got := (qbittorrent.Torrent{ETA: 480}).ETASeconds(); got != 480 {
		t.Errorf("ETASeconds = %d, want 480", got)
	}
}

func TestControlActionsNeedTheScope(t *testing.T) {
	torrents := []qbittorrent.Torrent{
		{Hash: "a", Name: "Live", State: "downloading", Progress: 0.2, DownloadDPS: 1},
	}
	readOnly := buildActivity(sources(torrents, nil), false, []string{"read"})
	if len(readOnly.Items[0].Actions) != 0 {
		t.Fatalf("a read-only token was offered %v", readOnly.Items[0].Actions)
	}
	full := buildActivity(sources(torrents, nil), false, []string{control})
	if !contains(full.Items[0].Actions, "stop") {
		t.Fatalf("Actions = %v", full.Items[0].Actions)
	}
}

func TestAStoppedTorrentOffersStartNotStop(t *testing.T) {
	torrents := []qbittorrent.Torrent{{Hash: "a", Name: "P", State: "stoppedDL"}}
	out := buildActivity(sources(torrents, nil), true, []string{control})
	actions := out.Items[0].Actions
	if !contains(actions, "start") || contains(actions, "stop") {
		t.Fatalf("Actions = %v", actions)
	}
}

func TestStoppedTorrentWithAnImportErrorStillOffersStart(t *testing.T) {
	torrents := []qbittorrent.Torrent{{
		Hash: "aaaaaaaaaaaaaaaaaaaa", Name: "Pack", State: "stoppedUP", Progress: 1,
	}}
	queues := map[string][]arr.QueueRecord{"sonarr": {{
		ID: 9, DownloadID: "AAAAAAAAAAAAAAAAAAAA", Status: "completed",
		TrackedDownloadStatus: "warning", TrackedDownloadState: "importPending",
	}}}
	out := buildActivity(sources(torrents, queues), false, []string{control})
	item := out.Items[0]
	if item.Stage != ActStuck || item.ClientStage != ActStopped {
		t.Fatalf("stages = displayed %q, client %q", item.Stage, item.ClientStage)
	}
	if !contains(item.Actions, "start") || contains(item.Actions, "stop") {
		t.Fatalf("Actions = %v", item.Actions)
	}
}

func TestSummaryCounts(t *testing.T) {
	torrents := []qbittorrent.Torrent{
		{Hash: "a", Name: "L", State: "downloading", Progress: 0.2, DownloadDPS: 1_000},
		{Hash: "b", Name: "Q", State: "queuedDL"},
		{Hash: "c", Name: "B", State: "missingFiles", Progress: 1},
	}
	out := buildActivity(sources(torrents, nil), true, nil)
	if out.Summary.Downloading != 1 || out.Summary.Queued != 1 || out.Summary.Stuck != 1 {
		t.Fatalf("summary = %+v", out.Summary)
	}
	if out.Summary.DownSpeedBytes != 1_000 {
		t.Fatalf("DownSpeedBytes = %d", out.Summary.DownSpeedBytes)
	}
}

func TestAServiceBeingDownDoesNotBlankTheOthers(t *testing.T) {
	s := sources(
		[]qbittorrent.Torrent{{Hash: "a", Name: "L", State: "downloading", DownloadDPS: 1}},
		nil,
	)
	s.partial = []Partial{{Service: "radarr", Reason: "unreachable"}}
	out := buildActivity(s, false, nil)
	if len(out.Items) != 1 {
		t.Fatal("losing radarr should not blank the torrent rows")
	}
	if len(out.Partial) != 1 {
		t.Fatal("the failure must still be reported")
	}
}

func TestTheMediaTitleIsShownNotJustTheReleaseName(t *testing.T) {
	queues := map[string][]arr.QueueRecord{"sonarr": {{
		ID: 1, Title: "Dark.Matter.2024.S02E03.1080p.x265-ELiTe",
		DownloadID: "x", Series: &arr.Series{Title: "Dark Matter"},
		Episode: &arr.Episode{SeasonNumber: 2, EpisodeNumber: 3},
	}}}
	out := buildActivity(sources(nil, queues), false, nil)
	if got := out.Items[0].MediaTitle; got != "Dark Matter S02E03" {
		t.Fatalf("MediaTitle = %q", got)
	}
	// The release name is still carried, for when it is the thing you need.
	if out.Items[0].Title == "" {
		t.Fatal("the release name was lost")
	}
}
