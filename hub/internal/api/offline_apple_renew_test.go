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
//
// Jellyfin 10.11 numbers the sidecars first, so a sidecar added or removed moves every
// stream of the film, the video and the audio too. The fake numbers them that way (and
// as older Jellyfins did, last, where a test says so).

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

func TestASidecarAddedInFrontOfTheFilesStreamsStillRenewsAndItsSubtitlesCanBeRefreshed(t *testing.T) {
	rig := newSubtitleRig(t)
	planned, _ := rig.server.offline.get(rig.grant.ID)
	before := rig.list()
	rig.expire()
	// The grant has expired, so the app cannot list its subtitles until it renews.
	if got := rig.get(rig.listPath()); got.Code != http.StatusGone {
		t.Fatalf("list on an expired grant = %d", got.Code)
	}

	// Bazarr finds French, and Jellyfin numbers it before the Hebrew sidecar: the video,
	// the audio and every subtitle of the file move up by one.
	videoWas := rig.upstream.index("video")
	rig.upstream.addSidecar(0, sidecar("fra", "subrip", "fra", "", subtitleFile{text: "1\n00:00:01,000 --> 00:00:02,000\nBonjour\n"}))
	if rig.upstream.index("video") != videoWas+1 {
		t.Fatal("the fake did not number the sidecar first")
	}

	manifest := rig.renewed(t)
	if manifest.GrantID != rig.grant.ID || manifest.Format != "apple" || manifest.Apple == nil ||
		manifest.Apple.StatusURL != subtitleDir+rig.grant.ID+"/status" || manifest.MediaURL != planned.Manifest.MediaURL ||
		manifest.ExpiresAt <= time.Now().UnixMilli() || len(manifest.Subtitles) != 0 {
		t.Fatalf("renewed = %+v", manifest)
	}
	// The manifest is still the one the MP4 was promised under: its subtitle options and
	// the indexes they name are those of the file the app has, the video still the
	// source's stream 1.
	if len(manifest.Apple.Subtitles) != len(planned.Manifest.Apple.Subtitles) ||
		manifest.Apple.Subtitles[0].SourceIndex != 0 || manifest.Apple.Subtitles[0].Language != "heb" ||
		manifest.Apple.Video.SourceIndex != videoWas || manifest.Source.Tracks[0].Language != "heb" || !manifest.Source.Tracks[0].External {
		t.Errorf("the renewed manifest was rewritten: %+v", manifest.Apple)
	}

	// The plan the build checks a file against is the one stored, untouched.
	stored, _ := rig.server.offline.get(rig.grant.ID)
	if stored.PlanSignature != planned.PlanSignature || stored.PlanSignature == "" || stored.ExpiresAt <= time.Now().UnixMilli() {
		t.Errorf("stored plan %q, want %q, and a new expiry (%d)", stored.PlanSignature, planned.PlanSignature, stored.ExpiresAt)
	}

	// And the subtitles can be refreshed: French is there, and the tracks already kept
	// have their keys, their signatures and their places in the MP4.
	list := rig.list()
	if got := keysOf(list); !slices.Equal(got, []string{"ext-fra", "ext-heb", "emb-2", "emb-3"}) {
		t.Fatalf("keys after the renewal = %v", got)
	}
	for _, key := range keysOf(before) {
		now, was := rig.mustTrack(list, key), rig.mustTrack(before, key)
		if now.Signature != was.Signature || *now.MP4Index != *was.MP4Index {
			t.Errorf("%s was %+v and is %+v", key, was, now)
		}
	}
	if rig.mustTrack(list, "ext-fra").MP4Index != nil {
		t.Error("French is not in the MP4 the app has")
	}
}

func TestASidecarRemovedOrReplacedAfterTheDownloadStillRenews(t *testing.T) {
	t.Run("removed, so every stream of the film moves down", func(t *testing.T) {
		rig := newSubtitleRig(t)
		before := rig.list()
		rig.expire()
		rig.upstream.dropSidecar("heb")
		rig.renewed(t)
		list := rig.list()
		if got := keysOf(list); !slices.Equal(got, []string{"emb-2", "emb-3"}) {
			t.Errorf("keys = %v", got)
		}
		for _, key := range keysOf(list) {
			if rig.mustTrack(list, key).Signature != rig.mustTrack(before, key).Signature {
				t.Errorf("%s changed because a sidecar went", key)
			}
		}
	})
	t.Run("replaced", func(t *testing.T) {
		rig := newSubtitleRig(t)
		rig.expire()
		rig.upstream.setFile("heb", subtitleFile{text: "1\n00:00:01,000 --> 00:00:02,000\nמתוקן\n"})
		rig.renewed(t)
	})
	t.Run("a sidecar of another kind in its place", func(t *testing.T) {
		rig := newSubtitleRig(t)
		rig.expire()
		rig.upstream.dropSidecar("heb")
		rig.upstream.addSidecar(0, sidecar("heb", "ass", "heb", "", subtitleFile{text: assEnglish}, "forced"))
		rig.renewed(t)
	})
	t.Run("one taken away and another put in front", func(t *testing.T) {
		rig := newSubtitleRig(t)
		rig.expire()
		rig.upstream.dropSidecar("heb")
		rig.upstream.addSidecar(0, sidecar("fra", "subrip", "fra", "", subtitleFile{text: srtEnglish}))
		rig.upstream.addSidecar(1, sidecar("spa", "subrip", "spa", "", subtitleFile{text: srtEnglish}))
		rig.renewed(t)
	})
}

func TestAJellyfinThatNumbersSidecarsLastStillRenewsPastANewSidecar(t *testing.T) {
	rig := newNumberedSubtitleRig(t, true)
	rig.expire()
	rig.upstream.addSidecar(-1, sidecar("fra", "subrip", "fra", "", subtitleFile{text: srtEnglish}))
	rig.renewed(t)
	if got := keysOf(rig.list()); !slices.Equal(got, []string{"emb-2", "emb-3", "ext-heb", "ext-fra"}) {
		t.Errorf("keys = %v", got)
	}
}

func TestARenewalOfAnUnchangedGrantKeepsItsPlan(t *testing.T) {
	rig := newSubtitleRig(t)
	rig.expire()
	before, _ := rig.server.offline.get(rig.grant.ID)
	manifest := rig.renewed(t)
	after, _ := rig.server.offline.get(rig.grant.ID)
	if after.PlanSignature != before.PlanSignature || manifest.Apple == nil || manifest.Apple.Subtitles[0].Language != "heb" {
		t.Errorf("renewed = %+v", manifest)
	}
}

func TestARealChangeToTheFileStillRefusesTheRenewalEvenWhenSidecarsMoveEveryIndex(t *testing.T) {
	cases := map[string]func(u *subtitleUpstream){
		"the file is another size": func(u *subtitleUpstream) { u.source["Size"] = 999 },
		"another audio track": func(u *subtitleUpstream) {
			u.inside = append(u.inside, filePart("audio2", map[string]any{"Type": "Audio", "Codec": "ac3", "Language": "heb", "Channels": 2}))
		},
		"an audio track is gone": func(u *subtitleUpstream) { u.inside = slices.Delete(u.inside, 1, 2) },
		"an audio track is converted differently": func(u *subtitleUpstream) {
			u.inside[1].info["Codec"] = "dts"
		},
		"another default audio track": func(u *subtitleUpstream) {
			u.inside[1].info["IsDefault"] = false
			u.inside = append(u.inside, filePart("audio2", map[string]any{"Type": "Audio", "Codec": "aac", "Language": "heb", "Channels": 2, "IsDefault": true}))
		},
		"a stream was added ahead of the audio": func(u *subtitleUpstream) {
			u.inside = slices.Insert(u.inside, 1, filePart("font", map[string]any{"Type": "Attachment", "Codec": "ttf"}))
		},
		"the audio track moved in the file": func(u *subtitleUpstream) {
			u.inside[0], u.inside[1] = u.inside[1], u.inside[0]
		},
		"the video is another codec": func(u *subtitleUpstream) { u.inside[0].info["Codec"] = "mpeg4" },
		"the video needs converting": func(u *subtitleUpstream) { u.inside[0].info["PixelFormat"] = "yuv420p10le" },
		"another container":          func(u *subtitleUpstream) { u.source["Container"] = "avi" },
		"a subtitle inside the file is added": func(u *subtitleUpstream) {
			u.inside = append(u.inside, embedded("spa", "subrip", "spa", "", subtitleFile{text: srtEnglish}))
		},
		"a subtitle inside the file is gone": func(u *subtitleUpstream) { u.inside = slices.Delete(u.inside, 3, 4) },
		"a subtitle inside the file is forced now": func(u *subtitleUpstream) {
			u.inside[2].info["IsForced"] = true
		},
		"the version it was made from is not there": func(u *subtitleUpstream) { u.source["Id"] = "another-version" },
	}
	for name, change := range cases {
		for _, withSidecar := range []bool{false, true} {
			label := name
			if withSidecar {
				label += ", and a sidecar arrived as well"
			}
			t.Run(label, func(t *testing.T) {
				rig := newSubtitleRig(t)
				rig.expire()
				stored, _ := rig.server.offline.get(rig.grant.ID)
				rig.upstream.edit(change)
				if withSidecar {
					rig.upstream.addSidecar(0, sidecar("fra", "subrip", "fra", "", subtitleFile{text: srtEnglish}))
				}
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
}

func TestAStreamAddedAheadOfTheAudioRefusesTheRenewalEvenWhenNoSubtitleMovesWithIt(t *testing.T) {
	rig := newSubtitleRig(t)
	// A film with no subtitle inside its file, so that the audio is all that can move.
	rig.upstream.edit(func(u *subtitleUpstream) { u.inside = u.inside[:2] })
	rig.grant = rig.makeGrant(offlineFormatApple, "movie-without-embedded-subtitles")
	rig.expire()
	rig.upstream.addSidecar(0, sidecar("fra", "subrip", "fra", "", subtitleFile{text: srtEnglish}))
	rig.renewed(t) // a sidecar in front moves every stream, and that alone is not a change

	rig.upstream.edit(func(u *subtitleUpstream) {
		u.inside = slices.Insert(u.inside, 1, filePart("font", map[string]any{"Type": "Attachment", "Codec": "ttf"}))
	})
	rig.expire()
	if response := rig.renew(); response.Code != http.StatusConflict {
		t.Errorf("renew after a stream was added ahead of the audio = %d, want 409", response.Code)
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

// On a real download numbered the old way (the sidecar last): a sidecar arrives after
// the MP4 was made, the grant renews, and the file the hub holds is still the one that
// was made (nothing is rebuilt, since the plan the build checks against is the one
// stored).
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
