package api

import (
	"testing"
	"time"

	"ayaneohub/internal/adapters/arr"
)

func TestMultiSeasonRejectionCannotBeOverridden(t *testing.T) {
	pack := &arr.Release{Rejections: []string{
		"WEBDL-720p is not wanted in profile",
		"Multi-season releases are not supported",
	}}
	if !releaseScopeBlocked(pack) {
		t.Fatal("a multi-season pack must be blocked from a one-season grab")
	}
	normalOverride := &arr.Release{Rejections: []string{"WEBDL-720p is not wanted in profile"}}
	if releaseScopeBlocked(normalOverride) {
		t.Fatal("ordinary quality-profile overrides should remain available")
	}
}

func TestEpisodeSearchBlocksPacksOutsideSelectedEpisode(t *testing.T) {
	target := &arrTarget{season: 1, episode: 2}
	for name, release := range map[string]*arr.Release{
		"full season":    {FullSeason: true, SeasonNum: 1},
		"multiple eps":   {SeasonNum: 1, EpisodeNos: []int{1, 2}},
		"other episode":  {SeasonNum: 1, EpisodeNos: []int{3}},
		"another season": {SeasonNum: 2, EpisodeNos: []int{2}},
	} {
		if !releaseOutsideTarget(release, target) {
			t.Errorf("%s was allowed outside S01E02", name)
		}
	}
	if releaseOutsideTarget(&arr.Release{SeasonNum: 1, EpisodeNos: []int{2}}, target) {
		t.Fatal("the exact selected episode was blocked")
	}
}

func TestReleasedEpisodeDateUsesSonarrUTC(t *testing.T) {
	now := time.Date(2026, 9, 9, 12, 0, 0, 0, time.UTC)
	if !episodeHasAired(arr.Episode{AirDateUTC: "2026-09-09T04:00:00Z"}, now) {
		t.Fatal("an aired episode was hidden")
	}
	if episodeHasAired(arr.Episode{AirDateUTC: "2026-09-16T04:00:00Z"}, now) {
		t.Fatal("a future episode was shown")
	}
	if !episodeHasAired(arr.Episode{HasFile: true}, now) {
		t.Fatal("an undated episode with a file should remain searchable")
	}
}
