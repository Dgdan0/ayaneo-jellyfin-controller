package api

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync"
	"testing"

	"ayaneohub/internal/adapters/jellyfin"
)

const (
	playbackItemID     = "11111111111111111111111111111111"
	playbackSeriesID   = "22222222222222222222222222222222"
	playbackNextID     = "33333333333333333333333333333333"
	playbackUserID     = "44444444444444444444444444444444"
	playbackPreviousID = "55555555555555555555555555555555"
)

type playbackUpstream struct {
	t      *testing.T
	server *httptest.Server
	mu     sync.Mutex
	events []string
	// positions records every resume point written to the user's item data,
	// which is what Jellyfin actually keeps; /Sessions/Playing* saves nothing
	// for an API-key caller.
	positions []int64
	played    int
}

func newPlaybackUpstream(t *testing.T) *playbackUpstream {
	t.Helper()
	u := &playbackUpstream{t: t}
	u.server = httptest.NewServer(http.HandlerFunc(u.serve))
	return u
}

func (u *playbackUpstream) close() { u.server.Close() }

func (u *playbackUpstream) serve(w http.ResponseWriter, r *http.Request) {
	if r.Header.Get("X-Emby-Token") != "test-key" {
		u.t.Errorf("missing upstream credential on %s", r.URL.Path)
	}
	switch {
	case r.Method == http.MethodGet && r.URL.Path == "/Items/"+playbackItemID:
		_, _ = io.WriteString(w, `{"Id":"`+playbackItemID+`","Name":"Pilot","Type":"Episode",`+
			`"SeriesName":"A Show","SeriesId":"`+playbackSeriesID+`","ParentIndexNumber":1,"IndexNumber":2,`+
			`"RunTimeTicks":27000000000,"UserData":{"PlaybackPositionTicks":9000000000},`+
			`"Chapters":[{"Name":"Cold open","StartPositionTicks":100000000},{"Name":"Act two","StartPositionTicks":9000000000}],`+
			`"Trickplay":{"source-1":{"320":{"Width":320,"Height":180,"TileWidth":4,"TileHeight":3,`+
			`"ThumbnailCount":25,"Interval":10000}}}}`)
	case r.Method == http.MethodGet && r.URL.Path == "/MediaSegments/"+playbackItemID:
		_, _ = io.WriteString(w, `[{"Id":"intro","Type":"Intro","StartTicks":200000000,"EndTicks":800000000}]`)
	case r.Method == http.MethodPost && r.URL.Path == "/Items/"+playbackItemID+"/PlaybackInfo":
		var request map[string]any
		if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
			u.t.Fatal(err)
		}
		if request["UserId"] != playbackUserID || request["StartTimeTicks"] != float64(9_000_000_000) {
			u.t.Errorf("playback request identity/resume = %+v", request)
		}
		if r.Header.Get("Authorization") == "" || r.Header.Get("X-Application") == "" {
			u.t.Errorf("playback device headers missing: %v", r.Header)
		}
		_, _ = io.WriteString(w, `{"PlaySessionId":"upstream-play", "MediaSources":[{`+
			`"Id":"source-1","Name":"1080p","Path":"X:/media/pilot.mkv","Container":"mkv","Bitrate":7000000,"SupportsDirectPlay":true,`+
			`"MediaStreams":[`+
			`{"Index":0,"Type":"Video","Codec":"h264","Width":1920,"Height":1080,"AverageFrameRate":23.976,"BitRate":6000000},`+
			`{"Index":1,"Type":"Audio","Codec":"aac","Language":"eng","DisplayTitle":"English AAC","Channels":2,"IsDefault":true},`+
			`{"Index":2,"Type":"Subtitle","Codec":"srt","Language":"heb","DisplayTitle":"Hebrew","IsExternal":true,"DeliveryMethod":"External","DeliveryUrl":"/Videos/`+playbackItemID+`/source-1/Subtitles/2/0/Stream.srt"}`+
			`],"DefaultAudioStreamIndex":1}]}`)
	case r.Method == http.MethodGet && r.URL.Path == "/Shows/"+playbackSeriesID+"/Episodes":
		if r.URL.Query().Get("adjacentTo") != playbackItemID {
			u.t.Errorf("adjacent episode query = %v", r.URL.Query())
		}
		_, _ = io.WriteString(w, `{"Items":[`+
			`{"Id":"`+playbackPreviousID+`","Name":"Previous","Type":"Episode","SeriesId":"`+playbackSeriesID+`","ParentIndexNumber":1,"IndexNumber":1},`+
			`{"Id":"`+playbackItemID+`","Name":"Pilot","Type":"Episode","SeriesId":"`+playbackSeriesID+`","ParentIndexNumber":1,"IndexNumber":2},`+
			`{"Id":"`+playbackNextID+`","Name":"Next","Type":"Episode","SeriesId":"`+playbackSeriesID+`","ParentIndexNumber":1,"IndexNumber":3}`+
			`]}`)
	case r.Method == http.MethodGet && r.URL.Path == "/Videos/"+playbackItemID+"/stream":
		if r.Header.Get("Range") != "bytes=2-5" {
			u.t.Errorf("Range = %q", r.Header.Get("Range"))
		}
		w.Header().Set("Content-Range", "bytes 2-5/8")
		w.Header().Set("Accept-Ranges", "bytes")
		w.Header().Set("Content-Type", "video/x-matroska")
		w.WriteHeader(http.StatusPartialContent)
		_, _ = io.WriteString(w, "2345")
	case r.Method == http.MethodGet && r.URL.Path == "/Videos/"+playbackItemID+"/Trickplay/320/0.jpg":
		if r.URL.Query().Get("MediaSourceId") != "source-1" {
			u.t.Errorf("trickplay media source = %q", r.URL.Query().Get("MediaSourceId"))
		}
		w.Header().Set("Content-Type", "image/jpeg")
		_, _ = io.WriteString(w, "jpeg-tile")
	case r.Method == http.MethodGet && strings.Contains(r.URL.Path, "/Subtitles/2/"):
		w.Header().Set("Content-Type", "application/x-subrip")
		_, _ = io.WriteString(w, "1\n00:00:00,000 --> 00:00:01,000\nHello\n")
	case r.Method == http.MethodPost && strings.HasPrefix(r.URL.Path, "/Sessions/Playing"):
		u.mu.Lock()
		u.events = append(u.events, r.URL.Path)
		u.mu.Unlock()
		w.WriteHeader(http.StatusNoContent)
	case r.Method == http.MethodDelete && r.URL.Path == "/Videos/ActiveEncodings":
		u.mu.Lock()
		u.events = append(u.events, "cleanup")
		u.mu.Unlock()
		w.WriteHeader(http.StatusNoContent)
	case r.Method == http.MethodGet && r.URL.Path == "/System/Configuration":
		_, _ = io.WriteString(w, `{"MinResumePct":5,"MaxResumePct":90,"MinResumeDurationSeconds":300}`)
	case r.Method == http.MethodPost && r.URL.Path == "/UserItems/"+playbackItemID+"/UserData":
		if r.URL.Query().Get("userId") != playbackUserID {
			u.t.Errorf("user data written for %q", r.URL.Query().Get("userId"))
		}
		var update map[string]any
		if err := json.NewDecoder(r.Body).Decode(&update); err != nil {
			u.t.Fatal(err)
		}
		if _, touched := update["IsFavorite"]; touched {
			u.t.Errorf("a position write must not touch favourite: %v", update)
		}
		u.mu.Lock()
		u.positions = append(u.positions, int64(update["PlaybackPositionTicks"].(float64)))
		u.mu.Unlock()
		_, _ = io.WriteString(w, `{}`)
	case r.Method == http.MethodPost && r.URL.Path == "/Users/"+playbackUserID+"/PlayedItems/"+playbackItemID:
		u.mu.Lock()
		u.played++
		u.mu.Unlock()
		_, _ = io.WriteString(w, `{}`)
	default:
		http.NotFound(w, r)
	}
}

func (u *playbackUpstream) savedPositions() []int64 {
	u.mu.Lock()
	defer u.mu.Unlock()
	return append([]int64(nil), u.positions...)
}

func playbackEvent(t *testing.T, handler http.Handler, sessionID, kind string, sequence int, positionMillis int64) {
	t.Helper()
	body := `{"type":"` + kind + `","sequence":` + strconv.Itoa(sequence) + `,"positionMillis":` +
		strconv.FormatInt(positionMillis, 10) + `,"paused":false,"volume":100}`
	got := playbackRequest(handler, http.MethodPost, "/v1/playback/sessions/"+sessionID+"/events", body, playbackUserID)
	if got.Code != http.StatusOK {
		t.Fatalf("%s returned %d: %s", kind, got.Code, got.Body.String())
	}
}

func playbackRequest(handler http.Handler, method, path, body, userID string) *httptest.ResponseRecorder {
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(method, path, strings.NewReader(body))
	request.Header.Set("Authorization", "Bearer "+libraryTestToken)
	request.Header.Set("Content-Type", "application/json")
	if userID != "" {
		request.Header.Set(jellyfinUserHeader, userID)
	}
	handler.ServeHTTP(recorder, request)
	return recorder
}

func preparePlaybackForTest(t *testing.T, handler http.Handler) PlaybackPrepareResponse {
	t.Helper()
	body := `{"startMode":"resume","device":{"id":"pocket-test","name":"Pocket DS","version":"test"},` +
		`"capabilities":{"width":1920,"height":1080,"maxAudioChannels":2,"videoCodecs":["h264"],"audioCodecs":["aac"]}}`
	recorder := playbackRequest(handler, http.MethodPost, "/v1/playback/items/"+playbackItemID+"/prepare", body, playbackUserID)
	if recorder.Code != http.StatusOK {
		t.Fatalf("prepare returned %d: %s", recorder.Code, recorder.Body.String())
	}
	var plan PlaybackPrepareResponse
	if err := json.NewDecoder(recorder.Body).Decode(&plan); err != nil {
		t.Fatal(err)
	}
	return plan
}

func TestPlaybackPrepareNormalizesResumeTracksAndNextEpisode(t *testing.T) {
	upstream := newPlaybackUpstream(t)
	defer upstream.close()
	server := NewServer(libraryAPIConfig(upstream.server.URL, playbackUserID))
	server.previewFrame = func(_ context.Context, sourcePath string, positionMillis int64) ([]byte, error) {
		if sourcePath != "X:/media/pilot.mkv" || positionMillis != 125_000 {
			t.Fatalf("preview request = %q at %d", sourcePath, positionMillis)
		}
		return []byte("preview-jpeg"), nil
	}
	handler := server.Handler()
	plan := preparePlaybackForTest(t, handler)
	if plan.PositionMillis != 900_000 || plan.DurationMillis != 2_700_000 || plan.PlayMethod != "DirectPlay" {
		t.Fatalf("plan timing/method = %+v", plan)
	}
	if plan.MediaURL != "/v1/playback/sessions/"+plan.SessionID+"/stream" || plan.MIMEType != "video/x-matroska" {
		t.Fatalf("safe media URL = %+v", plan)
	}
	if len(plan.AudioTracks) != 1 || plan.SelectedAudioIndex == nil || *plan.SelectedAudioIndex != 1 {
		t.Fatalf("audio tracks = %+v selected=%v", plan.AudioTracks, plan.SelectedAudioIndex)
	}
	if len(plan.SubtitleTracks) != 1 || !plan.SubtitleTracks[0].External ||
		!strings.HasPrefix(plan.SubtitleTracks[0].ExternalURL, "/v1/playback/sessions/") {
		t.Fatalf("subtitle tracks = %+v", plan.SubtitleTracks)
	}
	if plan.NextItem == nil || plan.NextItem.ID != playbackNextID {
		t.Fatalf("next item = %+v", plan.NextItem)
	}
	if plan.PreviousItem == nil || plan.PreviousItem.ID != playbackPreviousID {
		t.Fatalf("previous item = %+v", plan.PreviousItem)
	}
	if plan.Trickplay == nil || plan.Trickplay.Width != 320 || plan.Trickplay.IntervalMillis != 10_000 ||
		!strings.HasSuffix(plan.Trickplay.TileURL, "/trickplay") {
		t.Fatalf("trickplay = %+v", plan.Trickplay)
	}
	if !strings.HasSuffix(plan.PreviewURL, "/preview") {
		t.Fatalf("preview URL = %q", plan.PreviewURL)
	}
	if len(plan.Chapters) != 2 || plan.Chapters[0].Name != "Cold open" ||
		plan.Chapters[1].PositionMillis != 900_000 {
		t.Fatalf("chapters = %+v", plan.Chapters)
	}
	if len(plan.Segments) != 1 || plan.Segments[0].Type != "Intro" ||
		plan.Segments[0].StartMillis != 20_000 || plan.Segments[0].EndMillis != 80_000 {
		t.Fatalf("segments = %+v", plan.Segments)
	}
	preview := playbackRequest(
		handler, http.MethodGet, plan.PreviewURL+"?positionMillis=129999", "", playbackUserID,
	)
	if preview.Code != http.StatusOK || preview.Body.String() != "preview-jpeg" ||
		preview.Header().Get("Content-Type") != "image/jpeg" {
		t.Fatalf("preview response = %d %v %q", preview.Code, preview.Header(), preview.Body.String())
	}
}

func TestPlaybackRangeSubtitleOwnershipEventsAndCleanup(t *testing.T) {
	upstream := newPlaybackUpstream(t)
	defer upstream.close()
	handler := NewServer(libraryAPIConfig(upstream.server.URL, playbackUserID)).Handler()
	plan := preparePlaybackForTest(t, handler)

	wrongUser := playbackRequest(handler, http.MethodGet, plan.MediaURL, "", "55555555555555555555555555555555")
	if wrongUser.Code != http.StatusNotFound {
		t.Fatalf("other user stream returned %d", wrongUser.Code)
	}

	rangeRequest := httptest.NewRequest(http.MethodGet, plan.MediaURL, nil)
	rangeRequest.Header.Set("Authorization", "Bearer "+libraryTestToken)
	rangeRequest.Header.Set(jellyfinUserHeader, playbackUserID)
	rangeRequest.Header.Set("Range", "bytes=2-5")
	rangeResponse := httptest.NewRecorder()
	handler.ServeHTTP(rangeResponse, rangeRequest)
	if rangeResponse.Code != http.StatusPartialContent || rangeResponse.Body.String() != "2345" ||
		rangeResponse.Header().Get("Content-Range") != "bytes 2-5/8" {
		t.Fatalf("range response = %d %v %q", rangeResponse.Code, rangeResponse.Header(), rangeResponse.Body.String())
	}

	subtitle := playbackRequest(handler, http.MethodGet, plan.SubtitleTracks[0].ExternalURL, "", playbackUserID)
	if subtitle.Code != http.StatusOK || !strings.Contains(subtitle.Body.String(), "Hello") {
		t.Fatalf("subtitle response = %d %q", subtitle.Code, subtitle.Body.String())
	}
	shifted := playbackRequest(
		handler,
		http.MethodGet,
		plan.SubtitleTracks[0].ExternalURL+"?offsetMillis=500",
		"",
		playbackUserID,
	)
	if shifted.Code != http.StatusOK || !strings.Contains(shifted.Body.String(), "00:00:00,500 --> 00:00:01,500") {
		t.Fatalf("shifted subtitle response = %d %q", shifted.Code, shifted.Body.String())
	}
	trickplay := playbackRequest(
		handler,
		http.MethodGet,
		plan.Trickplay.TileURL+"/0",
		"",
		playbackUserID,
	)
	if trickplay.Code != http.StatusOK || trickplay.Body.String() != "jpeg-tile" ||
		trickplay.Header().Get("Content-Disposition") != "inline" {
		t.Fatalf("trickplay response = %d %v %q", trickplay.Code, trickplay.Header(), trickplay.Body.String())
	}
	missingTile := playbackRequest(
		handler,
		http.MethodGet,
		plan.Trickplay.TileURL+"/3",
		"",
		playbackUserID,
	)
	if missingTile.Code != http.StatusNotFound {
		t.Fatalf("out-of-range trickplay tile returned %d", missingTile.Code)
	}

	for sequence, kind := range []string{"started", "progress", "paused", "stopped"} {
		body := `{"type":"` + kind + `","sequence":` + strconv.Itoa(sequence+1) + `,"positionMillis":910000,"paused":false,"volume":100}`
		got := playbackRequest(handler, http.MethodPost, "/v1/playback/sessions/"+plan.SessionID+"/events", body, playbackUserID)
		if got.Code != http.StatusOK {
			t.Fatalf("%s returned %d: %s", kind, got.Code, got.Body.String())
		}
	}
	closed := playbackRequest(handler, http.MethodDelete, "/v1/playback/sessions/"+plan.SessionID, "", playbackUserID)
	if closed.Code != http.StatusOK {
		t.Fatalf("delete returned %d: %s", closed.Code, closed.Body.String())
	}
	// Progress, pause and stop each become the user's resume point; "started"
	// is only the position playback began from.
	if got := upstream.savedPositions(); len(got) != 3 || got[2] != 9_100_000_000 {
		t.Fatalf("saved resume points = %v", got)
	}
	upstream.mu.Lock()
	defer upstream.mu.Unlock()
	if len(upstream.events) != 5 || upstream.events[len(upstream.events)-1] != "cleanup" {
		t.Fatalf("upstream event order = %v", upstream.events)
	}
}

func TestAbandonedPlaybackSessionStillSavesTheLastPosition(t *testing.T) {
	upstream := newPlaybackUpstream(t)
	defer upstream.close()
	handler := NewServer(libraryAPIConfig(upstream.server.URL, playbackUserID)).Handler()
	plan := preparePlaybackForTest(t, handler)
	playbackEvent(t, handler, plan.SessionID, "started", 1, 900_000)
	playbackEvent(t, handler, plan.SessionID, "progress", 2, 1_200_000)

	closed := playbackRequest(handler, http.MethodDelete, "/v1/playback/sessions/"+plan.SessionID, "", playbackUserID)
	if closed.Code != http.StatusOK {
		t.Fatalf("delete returned %d: %s", closed.Code, closed.Body.String())
	}
	if got := upstream.savedPositions(); len(got) != 2 || got[1] != 12_000_000_000 {
		t.Fatalf("saved resume points = %v", got)
	}
}

func TestStoppingNearTheEndMarksTheEpisodeWatched(t *testing.T) {
	upstream := newPlaybackUpstream(t)
	defer upstream.close()
	handler := NewServer(libraryAPIConfig(upstream.server.URL, playbackUserID)).Handler()
	plan := preparePlaybackForTest(t, handler)
	playbackEvent(t, handler, plan.SessionID, "started", 1, 900_000)
	playbackEvent(t, handler, plan.SessionID, "stopped", 2, 2_600_000) // 96% of 45 minutes

	upstream.mu.Lock()
	played := upstream.played
	upstream.mu.Unlock()
	if got := upstream.savedPositions(); played != 1 || len(got) != 1 || got[0] != 0 {
		t.Fatalf("watched = %d, saved resume points = %v", played, got)
	}
}

func TestShiftSubtitleTimingsSupportsSRTAndWebVTT(t *testing.T) {
	input := []byte("00:00:01,250 --> 00:00:03,000\nHi\n\n00:04.500 --> 00:06.000\nThere\n")
	got := string(shiftSubtitleTimings(input, -500))
	want := "00:00:00,750 --> 00:00:02,500\nHi\n\n00:04.000 --> 00:05.500\nThere\n"
	if got != want {
		t.Fatalf("shifted subtitle = %q, want %q", got, want)
	}
}

func TestPlaybackRequiresPlayScope(t *testing.T) {
	cfg := libraryAPIConfig("", playbackUserID)
	cfg.Auth.Tokens[0].Scopes = []string{"read"}
	handler := NewServer(cfg).Handler()
	for _, request := range []*http.Request{
		httptest.NewRequest(http.MethodPost, "/v1/playback/items/"+playbackItemID+"/prepare", strings.NewReader(`{}`)),
		httptest.NewRequest(http.MethodGet, "/v1/library/series/"+playbackSeriesID+"/play-target", nil),
	} {
		request.Header.Set("Authorization", "Bearer "+libraryTestToken)
		recorder := httptest.NewRecorder()
		handler.ServeHTTP(recorder, request)
		if recorder.Code != http.StatusForbidden {
			t.Errorf("%s returned %d, want 403", request.URL.Path, recorder.Code)
		}
	}
}

func TestSeriesPlayTargetPrefersTheUsersResumableEpisode(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/Shows/NextUp" || r.URL.Query().Get("seriesId") != playbackSeriesID ||
			r.URL.Query().Get("enableResumable") != "true" || r.URL.Query().Get("userId") != playbackUserID {
			t.Errorf("play-target query = %s %v", r.URL.Path, r.URL.Query())
			http.NotFound(w, r)
			return
		}
		_, _ = io.WriteString(w, `{"Items":[{"Id":"`+playbackItemID+`","Name":"Continue me",`+
			`"Type":"Episode","SeriesId":"`+playbackSeriesID+`","ParentIndexNumber":2,"IndexNumber":4,`+
			`"UserData":{"PlaybackPositionTicks":400000000}}]}`)
	}))
	defer upstream.Close()
	handler := NewServer(libraryAPIConfig(upstream.URL, playbackUserID)).Handler()
	recorder := playbackRequest(handler, http.MethodGet,
		"/v1/library/series/"+playbackSeriesID+"/play-target", "", playbackUserID)
	if recorder.Code != http.StatusOK {
		t.Fatalf("play target returned %d: %s", recorder.Code, recorder.Body.String())
	}
	var target SeriesPlayTargetResponse
	if err := json.NewDecoder(recorder.Body).Decode(&target); err != nil {
		t.Fatal(err)
	}
	if target.Kind != "resume" || target.Item.ID != playbackItemID || target.Item.SeasonNumber != 2 {
		t.Fatalf("target = %+v", target)
	}
}

func TestHLSManifestRewritingKeepsEveryResourceInsideTheSession(t *testing.T) {
	manifest := "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\nsegment0.ts?token=hidden\n"
	got, err := rewriteHLSManifest(strings.NewReader(manifest),
		"/videos/"+playbackItemID+"/master.m3u8?api_key=secret", playbackItemID, playbackSeriesID)
	if err != nil {
		t.Fatal(err)
	}
	text := string(got)
	if strings.Contains(text, "api_key") || strings.Contains(text, "token=hidden") ||
		strings.Count(text, "/v1/playback/sessions/"+playbackSeriesID+"/hls/") != 2 {
		t.Fatalf("rewritten manifest = %q", text)
	}
	if validPlaybackResource("/Videos/"+playbackNextID+"/segment.ts", playbackItemID) {
		t.Fatal("resource for another item was accepted")
	}
}

func TestPlaybackResourceAcceptsJellyfinUUIDFormattingAndRemovesCredentials(t *testing.T) {
	hyphenated := "11111111-1111-1111-1111-111111111111"
	raw := "/Videos/" + hyphenated + "/master.m3u8?DeviceId=pocket&ApiKey=secret&token=older"
	if !validPlaybackResource(raw, playbackItemID) {
		t.Fatal("Jellyfin's hyphenated playback item id was rejected")
	}
	clean, err := sanitizePlaybackResource(raw)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(strings.ToLower(clean), "apikey") ||
		strings.Contains(strings.ToLower(clean), "token") ||
		!strings.Contains(clean, "DeviceId=pocket") {
		t.Fatalf("sanitized playback resource = %q", clean)
	}
	if got := playbackResourceQueryValue("/Videos/x/master.m3u8?AudioCodec=aac", "audioCodec"); got != "aac" {
		t.Fatalf("transcode audio codec = %q", got)
	}
}

func TestResumeRulesRejectTinyAndNearlyFinishedPositions(t *testing.T) {
	for _, tc := range []struct{ saved, duration, want int64 }{
		{29_999, 600_000, 0}, {30_000, 600_000, 30_000}, {570_000, 600_000, 0},
	} {
		item := jellyfin.Item{
			RunTimeTicks: tc.duration * 10_000,
			UserData:     &jellyfin.UserData{PlaybackPositionTicks: tc.saved * 10_000},
		}
		position := playbackStartPosition(item, PlaybackPrepareBody{StartMode: "resume"}, tc.duration)
		if position != tc.want {
			t.Errorf("resume(%d,%d) = %d, want %d", tc.saved, tc.duration, position, tc.want)
		}
	}
}

func TestResumeContinuesARewatch(t *testing.T) {
	item := jellyfin.Item{RunTimeTicks: 1_440_000 * 10_000,
		UserData: &jellyfin.UserData{Played: true, PlaybackPositionTicks: 192_000 * 10_000}}
	if got := playbackStartPosition(item, PlaybackPrepareBody{StartMode: "resume"}, 1_440_000); got != 192_000 {
		t.Fatalf("resume of a rewatch = %d, want 192000", got)
	}
}

func TestTextSubtitlesAreNeverBurnedIn(t *testing.T) {
	profile := buildDeviceProfile(PlaybackPrepareBody{})
	methods := map[string]string{}
	for _, sub := range profile.SubtitleProfiles {
		methods[sub.Format] = sub.Method
	}
	for _, format := range []string{"srt", "subrip", "vtt", "webvtt", "ass", "ssa"} {
		if methods[format] != "External" {
			t.Fatalf("%s is %q: the app draws it, so burning it in shows every line twice", format, methods[format])
		}
	}
	for _, format := range []string{"pgssub", "dvdsub"} {
		if methods[format] != "Encode" {
			t.Fatalf("%s is %q: a picture subtitle can only be burned in", format, methods[format])
		}
	}
}

func TestForcedFallbackRemovesDirectPlayProfiles(t *testing.T) {
	normal := buildDeviceProfile(PlaybackPrepareBody{Capabilities: PlaybackCapabilities{
		VideoCodecs: []string{"h264"}, AudioCodecs: []string{"aac"},
	}})
	forced := buildDeviceProfile(PlaybackPrepareBody{ForceTranscode: true, Capabilities: PlaybackCapabilities{
		VideoCodecs: []string{"h264"}, AudioCodecs: []string{"aac"},
	}})
	if len(normal.DirectPlayProfiles) == 0 || len(forced.DirectPlayProfiles) != 0 || len(forced.TranscodingProfiles) == 0 {
		t.Fatalf("normal direct=%d forced direct=%d transcode=%d",
			len(normal.DirectPlayProfiles), len(forced.DirectPlayProfiles), len(forced.TranscodingProfiles))
	}
}
