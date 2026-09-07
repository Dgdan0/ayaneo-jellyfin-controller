package api

import (
	"testing"

	"ayaneohub/internal/adapters/jellyseerr"
)

func TestParseMediaKey(t *testing.T) {
	key, err := ParseMediaKey("tmdb:movie:27205")
	if err != nil {
		t.Fatal(err)
	}
	if key.Source != "tmdb" || key.Type != "movie" || key.ID != 27205 {
		t.Fatalf("got %+v", key)
	}
	if key.String() != "tmdb:movie:27205" {
		t.Fatalf("round trip lost something: %q", key.String())
	}
}

func TestSeriesMapsBackToJellyseerrsSpelling(t *testing.T) {
	// The API says "series"; Jellyseerr's URLs say "tv". The app should never
	// see the upstream spelling, and the upstream should never see ours.
	key, _ := ParseMediaKey("tmdb:series:1396")
	if key.JellyseerrType() != "tv" {
		t.Fatalf("JellyseerrType = %q, want tv", key.JellyseerrType())
	}
	movie, _ := ParseMediaKey("tmdb:movie:1")
	if movie.JellyseerrType() != "movie" {
		t.Fatalf("JellyseerrType = %q, want movie", movie.JellyseerrType())
	}
}

func TestBadKeysAreRejectedRatherThanPassedThrough(t *testing.T) {
	// The id goes straight into an upstream URL path, so anything unrecognised
	// is refused instead of forwarded and hoped for.
	for _, bad := range []string{
		"", "tmdb:movie", "tmdb:movie:27205:extra",
		"imdb:movie:27205",     // unsupported source
		"tmdb:person:27205",    // unsupported type
		"tmdb:movie:abc",       // not a number
		"tmdb:movie:-1",        // not positive
		"tmdb:movie:0",         // not positive
		"tmdb:movie:../../etc", // path traversal attempt
	} {
		if _, err := ParseMediaKey(bad); err == nil {
			t.Errorf("accepted %q", bad)
		}
	}
}

// --- pipeline -------------------------------------------------------------

func stageByID(p Pipeline, id string) Stage {
	for _, s := range p.Stages {
		if s.ID == id {
			return s
		}
	}
	return Stage{}
}

func TestPipelineForSomethingNeverRequested(t *testing.T) {
	p := buildPipeline(nil, "movie")
	if p.Summary != "Not requested" {
		t.Errorf("Summary = %q", p.Summary)
	}
	for _, s := range p.Stages {
		if s.State != StagePending {
			t.Errorf("stage %s is %q, want pending", s.ID, s.State)
		}
	}
}

func TestPipelineForSomethingDownloading(t *testing.T) {
	info := &jellyseerr.MediaInfo{
		Status: jellyseerr.StatusProcessing,
		DownloadStatus: []jellyseerr.DownloadingItem{{
			Size: 1000, SizeLeft: 370, TimeLeft: "00:08:12",
			Title: "Dune.2021.2160p", Status: "downloading",
		}},
	}
	p := buildPipeline(info, "movie")

	if stageByID(p, "request").State != StageDone {
		t.Error("a downloading title was obviously requested")
	}
	if stageByID(p, "grab").State != StageDone {
		t.Error("a downloading title obviously found a release")
	}
	download := stageByID(p, "download")
	if download.State != StageActive {
		t.Errorf("download is %q, want active", download.State)
	}
	if download.Progress < 0.62 || download.Progress > 0.64 {
		t.Errorf("Progress = %v, want ~0.63", download.Progress)
	}
	if stageByID(p, "library").State != StagePending {
		t.Error("it is not in the library yet")
	}
	// The summary is the one line the app shows at the top, so it has to carry
	// the useful numbers rather than the word "processing".
	if p.Summary == "" || p.Summary == "Looking for a release" {
		t.Errorf("Summary = %q, expected the transfer detail", p.Summary)
	}
}

func TestPipelineForSomethingAvailable(t *testing.T) {
	info := &jellyseerr.MediaInfo{
		Status:          jellyseerr.StatusAvailable,
		JellyfinMediaID: "abc123",
	}
	p := buildPipeline(info, "movie")
	for _, id := range []string{"request", "grab", "download", "import", "library"} {
		if got := stageByID(p, id).State; got != StageDone {
			t.Errorf("stage %s is %q, want done", id, got)
		}
	}
	if p.Summary != "In your library" {
		t.Errorf("Summary = %q", p.Summary)
	}
}

func TestPipelineForAPartialSeries(t *testing.T) {
	info := &jellyseerr.MediaInfo{Status: jellyseerr.StatusPartiallyAvailable}
	p := buildPipeline(info, "series")
	if stageByID(p, "library").State != StageActive {
		t.Error("a partial series is in progress, not done")
	}
	if p.Summary != "Some episodes available" {
		t.Errorf("Summary = %q", p.Summary)
	}
}

func TestPipelineForAPendingRequest(t *testing.T) {
	info := &jellyseerr.MediaInfo{Status: jellyseerr.StatusPending}
	p := buildPipeline(info, "movie")
	request := stageByID(p, "request")
	if request.State != StageDone {
		t.Errorf("request stage is %q, want done", request.State)
	}
	if request.Detail != "waiting for approval" {
		t.Errorf("Detail = %q", request.Detail)
	}
	if stageByID(p, "grab").State != StagePending {
		t.Error("nothing is being searched for until it is approved")
	}
}

func TestEveryStageNamesItsSource(t *testing.T) {
	// The app shows which service an answer came from, so "subtitles: unknown"
	// can say *why* rather than looking like a missing feature.
	for _, p := range []Pipeline{
		buildPipeline(nil, "movie"),
		buildPipeline(&jellyseerr.MediaInfo{Status: jellyseerr.StatusAvailable}, "movie"),
	} {
		for _, s := range p.Stages {
			if s.Source == "" {
				t.Errorf("stage %s has no source", s.ID)
			}
			if s.Label == "" {
				t.Errorf("stage %s has no label", s.ID)
			}
		}
	}
}

func TestYearOfPrefersTheFirstUsableDate(t *testing.T) {
	if got := yearOf("", "2008-01-20"); got != 2008 {
		t.Errorf("yearOf = %d", got)
	}
	if got := yearOf("2021-09-15", "1999-01-01"); got != 2021 {
		t.Errorf("yearOf = %d", got)
	}
	if got := yearOf("", ""); got != 0 {
		t.Errorf("yearOf = %d, want 0", got)
	}
	if got := yearOf("soon"); got != 0 {
		t.Errorf("junk date produced %d", got)
	}
}

func TestRequestStateNames(t *testing.T) {
	if requestStateName(jellyseerr.RequestPending) != "pending" {
		t.Error("pending")
	}
	if requestStateName(jellyseerr.RequestApproved) != "approved" {
		t.Error("approved")
	}
	if requestStateName(999) != "unknown" {
		t.Error("an unrecognised status must not be mislabelled")
	}
	for _, status := range []int{1, 2, 3, 4, 5} {
		if requestMessage(status) == "" {
			t.Errorf("status %d has no message", status)
		}
	}
}
