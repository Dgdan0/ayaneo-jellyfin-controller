package api

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"testing"
)

// Jellyfin applies a track index only to the media source it is named with: asked
// for an audio track without a source, it converts the default track again, and
// the index it was given does nothing. A client that names a track and no source
// (Apple's select, an older app's) means the version that is playing, so the hub
// names it (#24).

// jellyfinTwoVersions answers PlaybackInfo as Jellyfin does for a film in two
// versions, each with a Russian (default) and an English track: naming a source
// narrows the answer to it, and an audio index is applied to the source it is
// named with and to no other.
func jellyfinTwoVersions(request map[string]any) string {
	named, _ := request["MediaSourceId"].(string)
	source := func(id, name string, bitrate int) string {
		audio := 1
		if named == id {
			if index, ok := request["AudioStreamIndex"].(float64); ok {
				audio = int(index)
			}
		}
		return fmt.Sprintf(`{"Id":%q,"Name":%q,"Container":"mkv","Bitrate":%d,"SupportsDirectPlay":true,"MediaStreams":[`+
			`{"Index":0,"Type":"Video","Codec":"h264","Width":1920,"Height":1080},`+
			`{"Index":1,"Type":"Audio","Codec":"ac3","Language":"rus","DisplayTitle":"Russian AC3","Channels":6,"IsDefault":true},`+
			`{"Index":2,"Type":"Audio","Codec":"ac3","Language":"eng","DisplayTitle":"English AC3","Channels":6},`+
			`{"Index":3,"Type":"Subtitle","Codec":"srt","Language":"eng","DisplayTitle":"English","IsExternal":true}`+
			`],"DefaultAudioStreamIndex":%d}`, id, name, bitrate, audio)
	}
	var sources []string
	for _, candidate := range []struct {
		id, name string
		bitrate  int
	}{{"source-1", "1080p", 7_000_000}, {"source-2", "720p", 3_000_000}} {
		if named == "" || named == candidate.id {
			sources = append(sources, source(candidate.id, candidate.name, candidate.bitrate))
		}
	}
	return `{"PlaySessionId":"upstream-play","MediaSources":[` + strings.Join(sources, ",") + `]}`
}

// selectPlayback sends a select and returns the plan it answers with.
func selectPlayback(t *testing.T, handler http.Handler, sessionID, body string) PlaybackPrepareResponse {
	t.Helper()
	response := playbackRequest(handler, http.MethodPost, "/v1/playback/sessions/"+sessionID+"/select", body, playbackUserID)
	if response.Code != http.StatusOK {
		t.Fatalf("select %s = %d: %s", body, response.Code, response.Body.String())
	}
	var plan PlaybackPrepareResponse
	if err := json.Unmarshal(response.Body.Bytes(), &plan); err != nil {
		t.Fatal(err)
	}
	return plan
}

// newTwoVersionPlayback is a film in two versions with a session on the first.
func newTwoVersionPlayback(t *testing.T) (*playbackUpstream, http.Handler, PlaybackPrepareResponse) {
	t.Helper()
	upstream := newPlaybackUpstream(t)
	t.Cleanup(upstream.close)
	upstream.infoResponse = jellyfinTwoVersions
	handler := NewServer(libraryAPIConfig(upstream.server.URL, playbackUserID)).Handler()
	plan := preparePlaybackForTest(t, handler)
	if plan.SelectedMediaSourceID != "source-1" {
		t.Fatalf("the session began on %q", plan.SelectedMediaSourceID)
	}
	return upstream, handler, plan
}

// asked is the part of a PlaybackInfo body a track change is about.
func asked(request map[string]any) string {
	return fmt.Sprintf("source %v, audio %v, subtitle %v, bitrate %v",
		request["MediaSourceId"], request["AudioStreamIndex"], request["SubtitleStreamIndex"], request["MaxStreamingBitrate"])
}

// lastInfoRequest is what Jellyfin was last asked, with how many questions it has had.
func lastInfoRequest(t *testing.T, upstream *playbackUpstream, wantCount int) map[string]any {
	t.Helper()
	requests := upstream.playbackInfoRequests()
	if len(requests) != wantCount {
		t.Fatalf("Jellyfin has had %d PlaybackInfo requests, want %d", len(requests), wantCount)
	}
	return requests[len(requests)-1]
}

func TestSelectingATrackNamesTheVersionPlayingWhenTheClientNamesNone(t *testing.T) {
	for _, test := range []struct {
		name     string
		body     string
		audio    any // what Jellyfin was asked for, nil for nothing
		subtitle any
	}{
		{"an audio track", `{"positionMillis":900000,"audioStreamIndex":2}`, float64(2), nil},
		{"the default audio track again", `{"positionMillis":900000,"audioStreamIndex":1}`, float64(1), nil},
		{"a subtitle track", `{"positionMillis":900000,"subtitleStreamIndex":3}`, nil, float64(3)},
		{"subtitles off", `{"positionMillis":900000,"subtitleStreamIndex":-1}`, nil, float64(-1)},
		{"both", `{"positionMillis":900000,"audioStreamIndex":2,"subtitleStreamIndex":3}`, float64(2), float64(3)},
		{"a source that is an empty name", `{"positionMillis":900000,"audioStreamIndex":2,"mediaSourceId":""}`, float64(2), nil},
	} {
		t.Run(test.name, func(t *testing.T) {
			upstream, handler, plan := newTwoVersionPlayback(t)
			// The session was prepared without naming a source: Jellyfin was asked for the
			// film as it is, which is how it always was.
			if first := lastInfoRequest(t, upstream, 1); first["MediaSourceId"] != nil {
				t.Fatalf("prepare named a source: %s", asked(first))
			}
			changed := selectPlayback(t, handler, plan.SessionID, test.body)
			request := lastInfoRequest(t, upstream, 2)
			if request["MediaSourceId"] != "source-1" {
				t.Fatalf("the select asked Jellyfin for %s, want the version playing, source-1", asked(request))
			}
			if request["AudioStreamIndex"] != test.audio || request["SubtitleStreamIndex"] != test.subtitle {
				t.Fatalf("Jellyfin was asked for audio %v and subtitle %v, want %v and %v", request["AudioStreamIndex"], request["SubtitleStreamIndex"], test.audio, test.subtitle)
			}
			if changed.SelectedMediaSourceID != "source-1" {
				t.Fatalf("the plan says the version is %q", changed.SelectedMediaSourceID)
			}
			if test.audio != nil && (changed.SelectedAudioIndex == nil || float64(*changed.SelectedAudioIndex) != test.audio) {
				t.Fatalf("the plan says the audio is %v", changed.SelectedAudioIndex)
			}
		})
	}
}

// A select that names a version is the client's choice of it, as it always was,
// and the version it chose is the one the next select means.
func TestSelectingAVersionIsTheClientsChoiceAndTheNextSelectMeansIt(t *testing.T) {
	upstream, handler, plan := newTwoVersionPlayback(t)
	other := selectPlayback(t, handler, plan.SessionID, `{"positionMillis":900000,"mediaSourceId":"source-2","audioStreamIndex":2}`)
	request := lastInfoRequest(t, upstream, 2)
	if request["MediaSourceId"] != "source-2" || request["AudioStreamIndex"] != float64(2) || other.SelectedMediaSourceID != "source-2" {
		t.Fatalf("a select that names a version: %s, plan on %q", asked(request), other.SelectedMediaSourceID)
	}
	// Then a track and no version: the one that is playing now.
	selectPlayback(t, handler, plan.SessionID, `{"positionMillis":900000,"audioStreamIndex":1}`)
	if request := lastInfoRequest(t, upstream, 3); request["MediaSourceId"] != "source-2" || request["AudioStreamIndex"] != float64(1) {
		t.Fatalf("a track after a change of version asked Jellyfin for %s", asked(request))
	}
	// And back to the first, by name.
	back := selectPlayback(t, handler, plan.SessionID, `{"positionMillis":900000,"mediaSourceId":"source-1"}`)
	if request := lastInfoRequest(t, upstream, 4); request["MediaSourceId"] != "source-1" || back.SelectedMediaSourceID != "source-1" {
		t.Fatalf("back to the first version: %s, plan on %q", asked(request), back.SelectedMediaSourceID)
	}
}

// The language that was chosen stays chosen through a change that names no track:
// a lower quality asked for after English was picked is still English.
func TestAChangeThatNamesNoTrackKeepsTheLanguageChosenBefore(t *testing.T) {
	upstream, handler, plan := newTwoVersionPlayback(t)
	selectPlayback(t, handler, plan.SessionID, `{"positionMillis":900000,"audioStreamIndex":2}`)
	selectPlayback(t, handler, plan.SessionID, `{"positionMillis":900000,"maxBitrate":4000000}`)
	request := lastInfoRequest(t, upstream, 3)
	if request["MediaSourceId"] != "source-1" || request["AudioStreamIndex"] != float64(2) || request["MaxStreamingBitrate"] != float64(4_000_000) {
		t.Fatalf("a change of quality after a change of language asked Jellyfin for %s", asked(request))
	}
	selectPlayback(t, handler, plan.SessionID, `{"positionMillis":900000,"forceTranscode":true}`)
	request = lastInfoRequest(t, upstream, 4)
	if request["MediaSourceId"] != "source-1" || request["AudioStreamIndex"] != float64(2) {
		t.Fatalf("a forced conversion after a change of language asked Jellyfin for %s", asked(request))
	}
}

// Nothing is named that nobody chose: a change that carries no track on a session
// that has never had one leaves the choice of version to Jellyfin, as before.
func TestAChangeThatNamesNeitherATrackNorAVersionNamesNone(t *testing.T) {
	upstream, handler, plan := newTwoVersionPlayback(t)
	selectPlayback(t, handler, plan.SessionID, `{"positionMillis":900000,"maxBitrate":4000000}`)
	request := lastInfoRequest(t, upstream, 2)
	if _, named := request["MediaSourceId"]; named {
		t.Fatalf("a change of quality named a version: %s", asked(request))
	}
	if _, chosen := request["AudioStreamIndex"]; chosen {
		t.Fatalf("a change of quality chose a language: %s", asked(request))
	}
}

func TestSelectingAVersionJellyfinDoesNotHaveStillFails(t *testing.T) {
	_, handler, plan := newTwoVersionPlayback(t)
	response := playbackRequest(handler, http.MethodPost, "/v1/playback/sessions/"+plan.SessionID+"/select",
		`{"positionMillis":900000,"mediaSourceId":"source-9","audioStreamIndex":2}`, playbackUserID)
	if response.Code == http.StatusOK {
		t.Fatalf("a version that is not there was accepted: %s", response.Body.String())
	}
}
