package api

import (
	"strings"
	"testing"

	"ayaneohub/internal/adapters/jellyseerr"
	"ayaneohub/internal/index"
)

func TestMediaKeyUsesTmdbAsTheSpine(t *testing.T) {
	if got := mediaKey("movie", 27205); got != "tmdb:movie:27205" {
		t.Errorf("mediaKey = %q", got)
	}
	// Jellyseerr says "tv"; the rest of this API says "series", and the app
	// should never see the upstream spelling.
	if got := mediaKey("tv", 1396); got != "tmdb:series:1396" {
		t.Errorf("mediaKey = %q", got)
	}
}

func TestAvailabilityFromStatus(t *testing.T) {
	cases := map[int]string{
		jellyseerr.StatusAvailable:          AvailAvailable,
		jellyseerr.StatusPartiallyAvailable: AvailPartiallyAvailable,
		jellyseerr.StatusProcessing:         AvailProcessing,
		jellyseerr.StatusPending:            AvailRequested,
		jellyseerr.StatusBlocklisted:        AvailBlocked,
		jellyseerr.StatusDeleted:            AvailDeleted,
		jellyseerr.StatusUnknown:            AvailNotInLibrary,
	}
	for status, want := range cases {
		got := availabilityFor(&jellyseerr.MediaInfo{Status: status})
		if got != want {
			t.Errorf("status %d -> %q, want %q", status, got, want)
		}
	}
}

func TestNoMediaInfoMeansNotInLibrary(t *testing.T) {
	// A title Jellyseerr has never heard of has no mediaInfo at all.
	if got := availabilityFor(nil); got != AvailNotInLibrary {
		t.Errorf("nil mediaInfo -> %q", got)
	}
}

func TestActiveDownloadBeatsTheStatusCode(t *testing.T) {
	// Jellyseerr still calls it "processing" while bytes are moving. "63% and
	// eight minutes left" is a far more useful answer than "processing", and
	// telling those apart is the question this whole app exists to answer.
	info := &jellyseerr.MediaInfo{
		Status:         jellyseerr.StatusProcessing,
		DownloadStatus: []jellyseerr.DownloadingItem{{Size: 100, SizeLeft: 37}},
	}
	if got := availabilityFor(info); got != AvailDownloading {
		t.Errorf("got %q, want downloading", got)
	}
}

func TestProgress(t *testing.T) {
	d := jellyseerr.DownloadingItem{Size: 1000, SizeLeft: 370}
	if p := d.Progress(); p < 0.62 || p > 0.64 {
		t.Errorf("Progress() = %v, want ~0.63", p)
	}
	// An unknown size must not render as 0% -- that reads as "stalled".
	if p := (jellyseerr.DownloadingItem{Size: 0}).Progress(); p != -1 {
		t.Errorf("unknown size -> %v, want -1", p)
	}
	// A size that has grown mid-transfer must not produce a negative bar.
	if p := (jellyseerr.DownloadingItem{Size: 100, SizeLeft: 150}).Progress(); p != 0 {
		t.Errorf("oversized sizeLeft -> %v, want 0", p)
	}
}

func TestActionsFollowAvailabilityAndScopes(t *testing.T) {
	all := []string{"read", "request", "control", "play"}

	play := actionsFor(AvailAvailable, all)
	if play[0] != "play" {
		t.Errorf("available should offer play first, got %v", play)
	}

	request := actionsFor(AvailNotInLibrary, all)
	if !contains(request, "request") {
		t.Errorf("missing title should offer request, got %v", request)
	}
	if contains(request, "play") {
		t.Errorf("cannot play what is not there: %v", request)
	}

	// Already downloading: neither request again nor play yet.
	downloading := actionsFor(AvailDownloading, all)
	if contains(downloading, "request") || contains(downloading, "play") {
		t.Errorf("downloading should offer neither, got %v", downloading)
	}
}

func TestScopesAreEnforced(t *testing.T) {
	readOnly := []string{"read"}
	if contains(actionsFor(AvailNotInLibrary, readOnly), "request") {
		t.Error("a read-only token was offered request")
	}
	if contains(actionsFor(AvailAvailable, readOnly), "play") {
		t.Error("a read-only token was offered play")
	}
	// It can still open the detail screen.
	if !contains(actionsFor(AvailAvailable, readOnly), "detail") {
		t.Error("detail should always be available")
	}
}

func TestYearParsing(t *testing.T) {
	cases := map[string]int{
		"2021-09-15": 2021,
		"1984-12-14": 1984,
		"":           0,
		"20":         0,
		"soon-ish":   0, // unreleased titles really do carry junk here
	}
	for date, want := range cases {
		got := jellyseerr.Result{ReleaseDate: date}.Year()
		if got != want {
			t.Errorf("Year(%q) = %d, want %d", date, got, want)
		}
	}
	// TV uses a different field entirely.
	if got := (jellyseerr.Result{FirstAirDate: "2008-01-20"}).Year(); got != 2008 {
		t.Errorf("firstAirDate ignored: %d", got)
	}
}

func TestHitPosterPointsAtTheHubNotTmdb(t *testing.T) {
	// The handheld must never see a TMDB URL: images ride the one authenticated
	// connection it already has.
	hit := hitFrom(jellyseerr.Result{
		ID: 438631, MediaType: "movie", Title: "Dune",
		ReleaseDate: "2021-09-15", PosterPath: "/abc.jpg",
	}, []string{"read"}, imagePrefix)

	if !strings.HasPrefix(hit.Media.Poster, "/v1/img/") {
		t.Errorf("poster is not hub-relative: %q", hit.Media.Poster)
	}
	if strings.Contains(hit.Media.Poster, "tmdb.org") {
		t.Errorf("poster leaks an upstream URL: %q", hit.Media.Poster)
	}
	if hit.Subtitle != "2021 · Movie" {
		t.Errorf("Subtitle = %q", hit.Subtitle)
	}
}

func TestJellyfinIndexCorrectsStaleSearchAndDiscoverCards(t *testing.T) {
	server := &Server{index: index.New()}
	server.index.Rebuild([]index.Source{{
		ItemID: "jellyfin-series", Name: "Last Seen", JellyfinType: "Series", Tmdb: "258230",
	}})
	scopes := []string{"read", "play"}
	raw := hitFrom(jellyseerr.Result{
		ID: 258230, MediaType: "tv", Name: "Last Seen",
		MediaInfo: &jellyseerr.MediaInfo{Status: jellyseerr.StatusProcessing},
	}, scopes, imagePrefix)
	got := server.enrichHitWithLibrary(raw, scopes)
	if got.Availability != AvailPartiallyAvailable || got.JellyfinItemID != "jellyfin-series" {
		t.Fatalf("enriched hit = %+v", got)
	}
	if !contains(got.Actions, "play") {
		t.Fatalf("partially available title should be playable: %v", got.Actions)
	}
}

func TestLibraryEnrichmentPreservesAnActiveDownload(t *testing.T) {
	server := &Server{index: index.New()}
	server.index.Rebuild([]index.Source{{
		ItemID: "jellyfin-series", Name: "Show", JellyfinType: "Series", Tmdb: "42",
	}})
	raw := SearchHit{
		Media:        MediaRef{Type: "series", IDs: MediaID{Tmdb: 42}},
		Availability: AvailDownloading,
	}
	got := server.enrichHitWithLibrary(raw, []string{"read", "play"})
	if got.Availability != AvailDownloading || got.JellyfinItemID == "" {
		t.Fatalf("active download was hidden: %+v", got)
	}
}

func contains(list []string, want string) bool {
	for _, s := range list {
		if s == want {
			return true
		}
	}
	return false
}
