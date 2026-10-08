package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"slices"
	"testing"
	"time"
)

// Renewing an Apple grant when only the sidecar subtitles changed (#45). Bazarr adds,
// replaces and removes sidecars for as long as a download is kept, and that does not
// make the MP4 another file; a real change to the file still does.

func (r *subtitleRig) expire() {
	r.t.Helper()
	grant, _ := r.server.offline.get(r.grant.ID)
	grant.ExpiresAt = time.Now().Add(-time.Minute).UnixMilli()
	grant.Manifest.ExpiresAt = grant.ExpiresAt
	if err := r.server.offline.put(grant); err != nil {
		r.t.Fatal(err)
	}
}

func (r *subtitleRig) renew() *httptest.ResponseRecorder {
	return playbackRequest(r.handler, http.MethodPost, subtitleDir+r.grant.ID+"/renew", `{}`, playbackUserID)
}

func (r *subtitleRig) renewed(t *testing.T) OfflineManifest {
	t.Helper()
	response := r.renew()
	if response.Code != http.StatusOK {
		t.Fatalf("renew = %d: %s", response.Code, response.Body.String())
	}
	var manifest OfflineManifest
	if err := json.Unmarshal(response.Body.Bytes(), &manifest); err != nil {
		t.Fatal(err)
	}
	r.noLeak(response.Body.String())
	return manifest
}

func TestASidecarAddedAfterTheDownloadStillRenewsAndItsSubtitlesCanBeRefreshed(t *testing.T) {
	rig := newSubtitleRig(t)
	planned, _ := rig.server.offline.get(rig.grant.ID)
	rig.expire()
	// The grant has expired, so the app cannot list its subtitles until it renews.
	if got := rig.get(rig.listPath()); got.Code != http.StatusGone {
		t.Fatalf("list on an expired grant = %d", got.Code)
	}

	// Bazarr finds French, and Jellyfin numbers it before the Hebrew sidecar.
	rig.upstream.change(func(source map[string]any) {
		source["MediaStreams"] = []any{
			map[string]any{"Index": 0, "Type": "Video", "Codec": "h264", "Width": 1920, "Height": 1080, "PixelFormat": "yuv420p"},
			map[string]any{"Index": 1, "Type": "Audio", "Codec": "aac", "Language": "eng", "Channels": 2, "IsDefault": true},
			subStream(2, "subrip", "eng", "Dialogue", "default"),
			subStream(3, "ass", "eng", "Signs", "forced"),
			subStream(4, "hdmv_pgs_subtitle", "fre", ""),
			subStream(5, "subrip", "fra", "", "external"),
			subStream(6, "subrip", "heb", "", "external"),
		}
	})
	rig.upstream.setFile(6, rig.upstream.files[5])
	rig.upstream.setFile(5, subtitleFile{text: "1\n00:00:01,000 --> 00:00:02,000\nBonjour\n"})

	manifest := rig.renewed(t)
	if manifest.GrantID != rig.grant.ID || manifest.Format != "apple" || manifest.Apple == nil ||
		manifest.Apple.StatusURL != subtitleDir+rig.grant.ID+"/status" || manifest.MediaURL != planned.Manifest.MediaURL ||
		manifest.ExpiresAt <= time.Now().UnixMilli() || len(manifest.Subtitles) != 0 {
		t.Fatalf("renewed = %+v", manifest)
	}
	// The manifest is still the one the MP4 was promised under: its subtitle options
	// and the indexes they name are those of the file the app has.
	if len(manifest.Apple.Subtitles) != len(planned.Manifest.Apple.Subtitles) ||
		manifest.Apple.Subtitles[3].SourceIndex != 5 || manifest.Apple.Subtitles[3].Language != "heb" ||
		manifest.Source.Tracks[5].Language != "heb" {
		t.Errorf("the renewed manifest was rewritten: %+v", manifest.Apple.Subtitles)
	}

	// The plan the build checks a file against is the one stored, untouched.
	stored, _ := rig.server.offline.get(rig.grant.ID)
	if stored.PlanSignature != planned.PlanSignature || stored.PlanSignature == "" || stored.ExpiresAt <= time.Now().UnixMilli() {
		t.Errorf("stored plan %q, want %q, and a new expiry (%d)", stored.PlanSignature, planned.PlanSignature, stored.ExpiresAt)
	}

	// And the subtitles can be refreshed: French is there, Hebrew has kept its place.
	list := rig.list()
	if got := keysOf(list); !slices.Equal(got, []string{"emb-2", "emb-3", "ext-fra", "ext-heb"}) {
		t.Fatalf("keys after the renewal = %v", got)
	}
	if hebrew := rig.mustTrack(list, "ext-heb"); hebrew.MP4Index == nil || *hebrew.MP4Index != 2 {
		t.Errorf("hebrew = %+v", hebrew)
	}
	if rig.mustTrack(list, "ext-fra").MP4Index != nil {
		t.Error("French is not in the MP4 the app has")
	}
}

func TestASidecarRemovedOrReplacedAfterTheDownloadStillRenews(t *testing.T) {
	t.Run("removed", func(t *testing.T) {
		rig := newSubtitleRig(t)
		rig.expire()
		rig.upstream.change(func(source map[string]any) {
			streams := source["MediaStreams"].([]any)
			source["MediaStreams"] = streams[:len(streams)-1]
		})
		rig.renewed(t)
		if got := keysOf(rig.list()); !slices.Equal(got, []string{"emb-2", "emb-3"}) {
			t.Errorf("keys = %v", got)
		}
	})
	t.Run("replaced", func(t *testing.T) {
		rig := newSubtitleRig(t)
		rig.expire()
		rig.upstream.setFile(5, subtitleFile{text: "1\n00:00:01,000 --> 00:00:02,000\nמתוקן\n"})
		rig.renewed(t)
	})
	t.Run("a sidecar of another kind in its place", func(t *testing.T) {
		rig := newSubtitleRig(t)
		rig.expire()
		rig.upstream.change(func(source map[string]any) {
			streams := source["MediaStreams"].([]any)
			streams[len(streams)-1] = subStream(5, "ass", "heb", "", "external", "forced")
		})
		rig.renewed(t)
	})
}

func TestARenewalOfAnUnchangedGrantKeepsItsPlan(t *testing.T) {
	rig := newSubtitleRig(t)
	rig.expire()
	before, _ := rig.server.offline.get(rig.grant.ID)
	manifest := rig.renewed(t)
	after, _ := rig.server.offline.get(rig.grant.ID)
	if after.PlanSignature != before.PlanSignature || manifest.Apple == nil || manifest.Apple.Subtitles[3].Language != "heb" {
		t.Errorf("renewed = %+v", manifest)
	}
}

func TestARealChangeToTheFileStillRefusesTheRenewal(t *testing.T) {
	cases := map[string]func(source map[string]any){
		"the file is another size": func(source map[string]any) { source["Size"] = 999 },
		"another audio track": func(source map[string]any) {
			source["MediaStreams"] = append(source["MediaStreams"].([]any),
				map[string]any{"Index": 6, "Type": "Audio", "Codec": "ac3", "Language": "heb", "Channels": 2})
		},
		"an audio track is gone": func(source map[string]any) {
			streams := source["MediaStreams"].([]any)
			source["MediaStreams"] = append(append([]any{}, streams[:1]...), streams[2:]...)
		},
		"an audio track is converted differently": func(source map[string]any) {
			source["MediaStreams"].([]any)[1].(map[string]any)["Codec"] = "dts"
		},
		"another default audio track": func(source map[string]any) {
			source["MediaStreams"] = append(source["MediaStreams"].([]any),
				map[string]any{"Index": 6, "Type": "Audio", "Codec": "aac", "Language": "heb", "Channels": 2})
			source["DefaultAudioStreamIndex"] = 6
		},
		"the video is another codec": func(source map[string]any) {
			source["MediaStreams"].([]any)[0].(map[string]any)["Codec"] = "mpeg4"
		},
		"the video needs converting": func(source map[string]any) {
			source["MediaStreams"].([]any)[0].(map[string]any)["PixelFormat"] = "yuv420p10le"
		},
		"another container": func(source map[string]any) { source["Container"] = "avi" },
		"a subtitle inside the file is added": func(source map[string]any) {
			source["MediaStreams"] = append(source["MediaStreams"].([]any), subStream(6, "subrip", "spa", ""))
		},
		"a subtitle inside the file is gone": func(source map[string]any) {
			streams := source["MediaStreams"].([]any)
			source["MediaStreams"] = append(append([]any{}, streams[:3]...), streams[4:]...)
		},
		"a subtitle inside the file is forced now": func(source map[string]any) {
			source["MediaStreams"].([]any)[2].(map[string]any)["IsForced"] = true
		},
		"the version it was made from is not there": func(source map[string]any) { source["Id"] = "another-version" },
	}
	for name, change := range cases {
		t.Run(name, func(t *testing.T) {
			rig := newSubtitleRig(t)
			rig.expire()
			stored, _ := rig.server.offline.get(rig.grant.ID)
			rig.upstream.change(change)
			response := rig.renew()
			failure := rig.errorOf(response)
			if response.Code != http.StatusConflict || failure.Code != "source_changed" {
				t.Fatalf("renew = %d %+v, want 409 source_changed", response.Code, failure)
			}
			after, _ := rig.server.offline.get(rig.grant.ID)
			if after.ExpiresAt != stored.ExpiresAt || after.PlanSignature != stored.PlanSignature {
				t.Error("a refused renewal changed the grant")
			}
		})
	}
}

func TestASidecarChangeDoesNotHideARealChangeMadeAtTheSameTime(t *testing.T) {
	rig := newSubtitleRig(t)
	rig.expire()
	rig.upstream.addStream(subStream(6, "subrip", "fra", "", "external"), subtitleFile{text: srtEnglish})
	rig.upstream.change(func(source map[string]any) {
		source["MediaStreams"] = append(source["MediaStreams"].([]any),
			map[string]any{"Index": 7, "Type": "Audio", "Codec": "ac3", "Language": "heb", "Channels": 2})
	})
	if response := rig.renew(); response.Code != http.StatusConflict {
		t.Errorf("renew = %d, want 409", response.Code)
	}
}

func TestAnOriginalGrantRenewsAsItAlwaysDid(t *testing.T) {
	rig := newSubtitleRig(t)
	original := rig.makeGrant("", "movie-original")
	grant, _ := rig.server.offline.get(original.ID)
	grant.ExpiresAt = time.Now().Add(-time.Minute).UnixMilli()
	if err := rig.server.offline.put(grant); err != nil {
		t.Fatal(err)
	}
	if response := playbackRequest(rig.handler, http.MethodPost, subtitleDir+original.ID+"/renew", `{}`, playbackUserID); response.Code != http.StatusOK {
		t.Errorf("renew = %d: %s", response.Code, response.Body.String())
	}
	rig.upstream.change(func(source map[string]any) { source["Size"] = 999 })
	if response := playbackRequest(rig.handler, http.MethodPost, subtitleDir+original.ID+"/renew", `{}`, playbackUserID); response.Code != http.StatusConflict {
		t.Errorf("renew of a resized original = %d, want 409", response.Code)
	}
}

// On a real download: a sidecar arrives after the MP4 was made, the grant renews, and
// the file the hub holds is still the one that was made (nothing is rebuilt, since
// the plan the build checks against is the one stored), while a rebuild would refuse.
func TestAfterARealDownloadASidecarAddedLaterRenewsAndTheMP4IsKept(t *testing.T) {
	rig := newAppleRig(t, nil)
	manifest, ready := rig.ready(t)
	before, _ := rig.server.offline.get(manifest.GrantID)
	expired := before
	expired.ExpiresAt = time.Now().Add(-time.Minute).UnixMilli()
	expired.Manifest.ExpiresAt = expired.ExpiresAt
	if err := rig.server.offline.put(expired); err != nil {
		t.Fatal(err)
	}
	rig.upstream.change(func(source map[string]any) {
		source["MediaStreams"] = append(source["MediaStreams"].([]any),
			map[string]any{"Index": 6, "Type": "Subtitle", "Codec": "subrip", "Language": "fra", "DisplayTitle": "French - SubRip", "IsExternal": true})
	})

	renewed := rig.request(http.MethodPost, "/v1/offline/grants/"+manifest.GrantID+"/renew", `{}`)
	if renewed.Code != http.StatusOK {
		t.Fatalf("renew = %d: %s", renewed.Code, renewed.Body.String())
	}
	var again OfflineManifest
	if err := json.Unmarshal(renewed.Body.Bytes(), &again); err != nil {
		t.Fatal(err)
	}
	if len(again.Apple.Subtitles) != len(manifest.Apple.Subtitles) || again.ExpiresAt <= time.Now().UnixMilli() {
		t.Errorf("renewed = %+v", again.Apple)
	}
	after, _ := rig.server.offline.get(manifest.GrantID)
	if after.PlanSignature != before.PlanSignature {
		t.Errorf("the stored plan changed from %q to %q", before.PlanSignature, after.PlanSignature)
	}
	if status := rig.status(manifest.GrantID); status.State != "ready" || status.ETag != ready.ETag {
		t.Errorf("after the renewal the file is %+v, want the one it was (%s)", status, ready.ETag)
	}
	if media := rig.request(http.MethodGet, manifest.MediaURL, ""); media.Code != http.StatusOK || int64(media.Body.Len()) != ready.SizeBytes {
		t.Errorf("media = %d", media.Code)
	}
}
