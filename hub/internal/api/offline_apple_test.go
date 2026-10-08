package api

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"ayaneohub/internal/config"
	"ayaneohub/internal/repackage"
)

// Offline downloads that play on Apple (#5): the hub repackages a source into an
// MP4 and serves it under the grant. These tests run the real ffmpeg on a small
// film they generate, and skip where there is none.

type appleSample struct {
	dir  string
	path string
	size int64
}

// makeAppleSample is a three-second film: 10-bit HEVC, E-AC-3 and DTS audio, and
// an SRT subtitle inside the file.
func makeAppleSample(t *testing.T) appleSample {
	t.Helper()
	ffmpeg, err := findFFmpeg()
	if err != nil {
		t.Skip("ffmpeg is not installed here")
	}
	dir := t.TempDir()
	srt := filepath.Join(dir, "en.srt")
	if err := os.WriteFile(srt, []byte("1\n00:00:00,500 --> 00:00:02,000\nHello there\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	library := filepath.Join(dir, "library")
	if err := os.MkdirAll(library, 0o700); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(library, "Film (2024).mkv")
	command := exec.Command(ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
		"-f", "lavfi", "-i", "testsrc2=size=320x180:rate=24:duration=3",
		"-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=3",
		"-f", "lavfi", "-i", "sine=frequency=880:sample_rate=48000:duration=3",
		"-i", srt,
		"-map", "0:v", "-map", "1:a", "-map", "2:a", "-map", "3:s",
		"-c:v", "libx265", "-preset", "ultrafast", "-x265-params", "log-level=none", "-pix_fmt", "yuv420p10le",
		"-c:a:0", "eac3", "-b:a:0", "384k", "-ac:a:0", "6",
		"-c:a:1", "dca", "-strict", "-2", "-b:a:1", "768k", "-ac:a:1", "6",
		"-c:s", "srt",
		"-metadata:s:a:0", "language=eng", "-metadata:s:a:1", "language=rus", "-metadata:s:s:0", "language=eng",
		path)
	if output, err := command.CombinedOutput(); err != nil {
		t.Skipf("this ffmpeg cannot make the sample: %v: %s", err, output)
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	return appleSample{dir: dir, path: path, size: info.Size()}
}

func (s appleSample) hash(t *testing.T) [32]byte {
	t.Helper()
	data, err := os.ReadFile(s.path)
	if err != nil {
		t.Fatal(err)
	}
	return sha256.Sum256(data)
}

// appleItemIDs are the items the fake Jellyfin knows: three films that are all
// the one sample, so a batch can name several.
var appleItemIDs = []string{offlineItemID, "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", "ffffffffffffffffffffffffffffffff"}

type appleUpstream struct {
	t      *testing.T
	server *httptest.Server
	sample appleSample

	mu              sync.Mutex
	source          map[string]any
	itemType        string
	subtitleCalls   []string
	subtitleToken   []string
	subtitleMissing bool
	// sidecars are the subtitle files beside the film, by Jellyfin's index for them.
	sidecars map[int]string
	// hidePaths is a profile that is not an administrator's: its own reads of an
	// item leave out the files' paths, and only a read with no user has them.
	hidePaths   bool
	serverReads int
}

func newAppleUpstream(t *testing.T, sample appleSample) *appleUpstream {
	t.Helper()
	u := &appleUpstream{t: t, sample: sample, itemType: "Movie", sidecars: map[int]string{5: hebrewSidecar}}
	u.source = u.defaultSource()
	u.server = httptest.NewServer(http.HandlerFunc(u.serve))
	t.Cleanup(u.server.Close)
	return u
}

func (u *appleUpstream) defaultSource() map[string]any {
	return map[string]any{
		"Id": offlineSourceID, "Name": "Original 1080p", "Path": u.sample.path, "Container": "mkv",
		"Size": u.sample.size, "Bitrate": 8_000_000, "RunTimeTicks": int64(3 * 10_000_000),
		"DefaultAudioStreamIndex": 2,
		"MediaStreams": []any{
			map[string]any{"Index": 0, "Type": "Video", "Codec": "hevc", "Profile": "Main 10", "Width": 320, "Height": 180, "BitDepth": 10, "PixelFormat": "yuv420p10le"},
			map[string]any{"Index": 1, "Type": "Audio", "Codec": "eac3", "Language": "eng", "Channels": 6, "DisplayTitle": "English - E-AC3 - 5.1"},
			map[string]any{"Index": 2, "Type": "Audio", "Codec": "dts", "Language": "rus", "Channels": 6, "DisplayTitle": "Russian - DTS - 5.1", "IsDefault": true},
			map[string]any{"Index": 3, "Type": "Subtitle", "Codec": "subrip", "Language": "eng", "DisplayTitle": "English - SubRip"},
			map[string]any{"Index": 4, "Type": "Subtitle", "Codec": "hdmv_pgs_subtitle", "Language": "fre", "DisplayTitle": "French - PGSSUB"},
			map[string]any{"Index": 5, "Type": "Subtitle", "Codec": "subrip", "Language": "heb", "DisplayTitle": "Hebrew - SubRip", "IsExternal": true,
				"DeliveryUrl": "/Videos/" + offlineItemID + "/" + offlineSourceID + "/Subtitles/5/0/Stream.srt"},
		},
	}
}

// appleSubtitlePath is a request for any item's subtitle: the fake serves the same
// sidecars for the three films it holds.
var appleSubtitlePath = regexp.MustCompile(`/Subtitles/(\d+)/0/Stream\.([a-z]+)$`)

const hebrewSidecar = "1\n00:00:00,500 --> 00:00:02,000\nשלום עולם\n"

// withSidecars renumbers the film the way Jellyfin 10.11 does: the subtitle files
// beside it come first (index 0, 1, …) and the file's own streams follow, so the
// video that is stream 0 of the MKV is stream 1 or 2 to Jellyfin. The first is
// listed first; the film's own streams are the default ones, shifted.
func (u *appleUpstream) withSidecars(files ...appleSidecar) {
	u.mu.Lock()
	defer u.mu.Unlock()
	streams := []any{}
	u.sidecars = map[int]string{}
	for at, file := range files {
		u.sidecars[at] = file.text
		streams = append(streams, map[string]any{"Index": at, "Type": "Subtitle", "Codec": "subrip", "Language": file.language,
			"DisplayTitle": file.language + " - SubRip", "IsExternal": true,
			"DeliveryUrl": fmt.Sprintf("/Videos/%s/%s/Subtitles/%d/0/Stream.srt", offlineItemID, offlineSourceID, at)})
	}
	shift := len(files)
	for _, stream := range u.defaultSource()["MediaStreams"].([]any) {
		stream := stream.(map[string]any)
		if external, _ := stream["IsExternal"].(bool); external {
			continue
		}
		stream["Index"] = stream["Index"].(int) + shift
		streams = append(streams, stream)
	}
	u.source["MediaStreams"] = streams
	u.source["DefaultAudioStreamIndex"] = 2 + shift
}

type appleSidecar struct{ language, text string }

// change edits the media source Jellyfin reports.
func (u *appleUpstream) change(edit func(source map[string]any)) {
	u.mu.Lock()
	defer u.mu.Unlock()
	edit(u.source)
}

func (u *appleUpstream) itemJSON(id string) string { return u.itemJSONFor(id, false) }

// itemJSONFor is an item as Jellyfin writes it: asServer is the read that has no
// user, which is shown every path.
func (u *appleUpstream) itemJSONFor(id string, asServer bool) string {
	u.mu.Lock()
	defer u.mu.Unlock()
	source := u.source
	if u.hidePaths && !asServer {
		source = map[string]any{}
		for key, value := range u.source {
			if key != "Path" {
				source[key] = value
			}
		}
	}
	document := map[string]any{
		"Id": id, "Name": "Film", "Type": u.itemType, "RunTimeTicks": int64(3 * 10_000_000),
		"MediaSources": []any{source},
	}
	if u.itemType == "Episode" {
		document["SeriesId"], document["SeasonId"] = offlineSeriesID, offlineSeasonID
		document["SeriesName"], document["ParentIndexNumber"], document["IndexNumber"] = "A Series", 1, 1
	}
	body, err := json.Marshal(document)
	if err != nil {
		u.t.Fatal(err)
	}
	return string(body)
}

func (u *appleUpstream) serve(w http.ResponseWriter, r *http.Request) {
	switch {
	case r.Method == http.MethodGet && strings.HasPrefix(r.URL.Path, "/Items/") && slices.Contains(appleItemIDs, strings.TrimPrefix(r.URL.Path, "/Items/")):
		_, _ = io.WriteString(w, u.itemJSON(strings.TrimPrefix(r.URL.Path, "/Items/")))
	case r.Method == http.MethodGet && r.URL.Path == "/Items" && slices.Contains(appleItemIDs, r.URL.Query().Get("ids")):
		if r.URL.Query().Get("userId") != "" {
			u.t.Errorf("a read for paths named a user: %v", r.URL.Query())
		}
		u.mu.Lock()
		u.serverReads++
		u.mu.Unlock()
		id := r.URL.Query().Get("ids")
		_, _ = io.WriteString(w, `{"Items":[`+u.itemJSONFor(id, true)+`],"TotalRecordCount":1}`)
	case r.Method == http.MethodGet && r.URL.Path == "/Items/"+offlineSeriesID:
		_, _ = io.WriteString(w, `{"Id":"`+offlineSeriesID+`","Name":"A Series","Type":"Series"}`)
	case r.Method == http.MethodGet && r.URL.Path == "/Shows/"+offlineSeriesID+"/Seasons":
		_, _ = io.WriteString(w, `{"Items":[{"Id":"`+offlineSeasonID+`","Name":"Season 1","Type":"Season","IndexNumber":1}]}`)
	case r.Method == http.MethodGet && r.URL.Path == "/Shows/"+offlineSeriesID+"/Episodes":
		_, _ = io.WriteString(w, `{"Items":[`+u.itemJSON(offlineItemID)+`]}`)
	case r.Method == http.MethodGet && r.URL.Path == "/Shows/NextUp":
		_, _ = io.WriteString(w, `{"Items":[{"Id":"`+offlineItemID+`"}]}`)
	case r.Method == http.MethodGet && appleSubtitlePath.MatchString(r.URL.Path):
		match := appleSubtitlePath.FindStringSubmatch(r.URL.Path)
		index, _ := strconv.Atoi(match[1])
		u.mu.Lock()
		u.subtitleCalls = append(u.subtitleCalls, r.URL.Path)
		u.subtitleToken = append(u.subtitleToken, r.Header.Get("X-Emby-Token")+"|"+r.URL.RawQuery)
		missing := u.subtitleMissing
		body, found := u.sidecars[index]
		u.mu.Unlock()
		if missing || !found || match[2] != "srt" {
			http.NotFound(w, r)
			return
		}
		_, _ = io.WriteString(w, body)
	default:
		http.NotFound(w, r)
	}
}

func (u *appleUpstream) subtitleFetches() ([]string, []string) {
	u.mu.Lock()
	defer u.mu.Unlock()
	return append([]string(nil), u.subtitleCalls...), append([]string(nil), u.subtitleToken...)
}

type appleRig struct {
	t        *testing.T
	sample   appleSample
	upstream *appleUpstream
	cache    string
	registry string
	server   *Server
	handler  http.Handler
}

// newAppleRig is a hub with the cache and the registry in temp folders, the real
// ffmpeg (with the software encoder, so a run does not depend on this PC's GPU)
// and a Jellyfin that holds the sample.
func newAppleRig(t *testing.T, mutate func(*config.Config)) *appleRig {
	t.Helper()
	sample := makeAppleSample(t)
	rig := &appleRig{t: t, sample: sample, upstream: newAppleUpstream(t, sample)}
	rig.cache, rig.registry = filepath.Join(t.TempDir(), "cache"), filepath.Join(t.TempDir(), "registry.json")
	rig.start(mutate)
	return rig
}

func (r *appleRig) start(mutate func(*config.Config)) {
	r.t.Helper()
	cfg := offlineConfig(r.upstream.server.URL, r.registry)
	cfg.Server.OfflineCache = r.cache
	if mutate != nil {
		mutate(cfg)
	}
	r.server = NewServer(cfg)
	r.server.pickEncoder = func(context.Context, string) repackage.Encoder { return repackage.X264 }
	r.t.Cleanup(r.server.closeRepackager)
	r.handler = r.server.Handler()
}

func (r *appleRig) request(method, path, body string) *httptest.ResponseRecorder {
	return playbackRequest(r.handler, method, path, body, playbackUserID)
}

func (r *appleRig) prepare(format string) OfflineManifest {
	r.t.Helper()
	manifests := r.prepareAll(format, "movie-1")
	return manifests[0]
}

func (r *appleRig) prepareAll(format string, keys ...string) []OfflineManifest {
	r.t.Helper()
	response := r.rawPrepare(format, keys...)
	if response.Code != http.StatusOK {
		r.t.Fatalf("prepare returned %d: %s", response.Code, response.Body.String())
	}
	var decoded OfflinePrepareResponse
	if err := json.Unmarshal(response.Body.Bytes(), &decoded); err != nil {
		r.t.Fatal(err)
	}
	return decoded.Items
}

func (r *appleRig) rawPrepare(format string, keys ...string) *httptest.ResponseRecorder {
	r.t.Helper()
	items := make([]string, 0, len(keys))
	for at, key := range keys {
		items = append(items, `{"clientItemKey":"`+key+`","itemId":"`+appleItemIDs[at]+`"}`)
	}
	formatField := ""
	if format != "" {
		formatField = `"format":"` + format + `",`
	}
	body := `{"batchKey":"batch-1",` + formatField + `"items":[` + strings.Join(items, ",") + `]}`
	return r.request(http.MethodPost, "/v1/offline/prepare", body)
}

func (r *appleRig) status(grantID string) OfflineStatus {
	r.t.Helper()
	response := r.request(http.MethodGet, "/v1/offline/grants/"+grantID+"/status", "")
	if response.Code != http.StatusOK {
		r.t.Fatalf("status returned %d: %s", response.Code, response.Body.String())
	}
	var status OfflineStatus
	if err := json.Unmarshal(response.Body.Bytes(), &status); err != nil {
		r.t.Fatal(err)
	}
	return status
}

func (r *appleRig) waitFor(grantID, state string) OfflineStatus {
	r.t.Helper()
	deadline := time.Now().Add(60 * time.Second)
	for {
		status := r.status(grantID)
		if status.State == state {
			return status
		}
		if time.Now().After(deadline) {
			r.t.Fatalf("gave up waiting for %s to be %s; it is %+v", grantID, state, status)
		}
		// An app asks every few seconds; the transport budget is 60 a second, and
		// a tight loop here would spend it and be told to slow down.
		time.Sleep(40 * time.Millisecond)
	}
}

func (r *appleRig) ready(t *testing.T) (OfflineManifest, OfflineStatus) {
	t.Helper()
	manifest := r.prepare("apple")
	return manifest, r.waitFor(manifest.GrantID, "ready")
}

func probeMP4(t *testing.T, path string) []struct {
	CodecType, CodecName, CodecTag, Language string
	Channels                                 int
	Default                                  int
} {
	t.Helper()
	ffprobe, err := findFFprobe()
	if err != nil {
		t.Skip("ffprobe is not installed here")
	}
	output, err := exec.Command(ffprobe, "-v", "error", "-show_streams", "-of", "json", path).Output()
	if err != nil {
		t.Fatalf("ffprobe: %v", err)
	}
	var decoded struct {
		Streams []struct {
			CodecType   string            `json:"codec_type"`
			CodecName   string            `json:"codec_name"`
			CodecTag    string            `json:"codec_tag_string"`
			Channels    int               `json:"channels"`
			Tags        map[string]string `json:"tags"`
			Disposition map[string]int    `json:"disposition"`
		} `json:"streams"`
	}
	if err := json.Unmarshal(output, &decoded); err != nil {
		t.Fatal(err)
	}
	var out []struct {
		CodecType, CodecName, CodecTag, Language string
		Channels                                 int
		Default                                  int
	}
	for _, stream := range decoded.Streams {
		out = append(out, struct {
			CodecType, CodecName, CodecTag, Language string
			Channels                                 int
			Default                                  int
		}{stream.CodecType, stream.CodecName, stream.CodecTag, stream.Tags["language"], stream.Channels, stream.Disposition["default"]})
	}
	return out
}

func TestAnApplePrepareSaysWhatTheMP4WillHold(t *testing.T) {
	rig := newAppleRig(t, nil)
	response := rig.rawPrepare("apple", "movie-1")
	if response.Code != http.StatusOK {
		t.Fatalf("prepare = %d: %s", response.Code, response.Body.String())
	}
	var decoded OfflinePrepareResponse
	if err := json.Unmarshal(response.Body.Bytes(), &decoded); err != nil {
		t.Fatal(err)
	}
	manifest := decoded.Items[0]
	if manifest.Format != "apple" || manifest.Apple == nil {
		t.Fatalf("manifest = %+v", manifest)
	}
	apple := manifest.Apple
	if apple.Container != "mp4" || apple.MIMEType != "video/mp4" || apple.EstimatedSizeBytes <= 0 ||
		apple.StatusURL != "/v1/offline/grants/"+manifest.GrantID+"/status" {
		t.Fatalf("apple = %+v", apple)
	}
	if video := apple.Video; video.SourceIndex != 0 || video.Codec != "hevc" || video.OutputCodec != "hevc" || video.Tag != "hvc1" ||
		video.Converted || video.Width != 320 || video.Height != 180 {
		t.Errorf("video = %+v, want the HEVC copied and tagged hvc1", video)
	}
	if len(apple.Audio) != 2 {
		t.Fatalf("audio = %+v", apple.Audio)
	}
	if first := apple.Audio[0]; first.SourceIndex != 1 || first.Language != "eng" || first.OutputCodec != "eac3" || first.Converted || first.Default {
		t.Errorf("first audio = %+v", first)
	}
	if second := apple.Audio[1]; second.SourceIndex != 2 || second.Language != "rus" || second.OutputCodec != "aac" || !second.Converted ||
		second.Reason != "unsupported_codec" || second.OutputChannels != 6 || !second.Default {
		t.Errorf("second audio = %+v, want the DTS as 5.1 AAC and the default", second)
	}
	if len(apple.Subtitles) != 3 {
		t.Fatalf("subtitles = %+v", apple.Subtitles)
	}
	if english := apple.Subtitles[0]; !english.Available || english.External || english.OutputCodec != "mov_text" || english.Language != "eng" {
		t.Errorf("english = %+v", english)
	}
	if french := apple.Subtitles[1]; french.Available || french.Reason != "picture_subtitle" || french.OutputCodec != "" || french.Language != "fra" {
		t.Errorf("french = %+v, want left out as a picture subtitle", french)
	}
	if hebrew := apple.Subtitles[2]; !hebrew.Available || !hebrew.External || hebrew.Language != "heb" {
		t.Errorf("hebrew = %+v", hebrew)
	}
	// The original's own description and its sidecar list are as they were or empty.
	if manifest.Source.Container != "mkv" || manifest.Source.SizeBytes != rig.sample.size || len(manifest.Source.Tracks) != 6 {
		t.Errorf("source = %+v, want the original", manifest.Source)
	}
	body := response.Body.String()
	if !strings.Contains(body, `"subtitles":[]`) {
		t.Errorf("the sidecar list is not an empty array: %s", body)
	}
	if manifest.MediaURL != "/v1/offline/grants/"+manifest.GrantID+"/media" {
		t.Errorf("mediaUrl = %q", manifest.MediaURL)
	}
	for _, leak := range []string{rig.sample.dir, "Film (2024)", `"path"`, `"Path"`} {
		if strings.Contains(body, leak) {
			t.Errorf("the manifest names %q", leak)
		}
	}
	if grants, err := os.ReadFile(rig.registry); err != nil || bytes.Contains(grants, []byte(rig.sample.dir)) {
		t.Errorf("the grant registry holds a path (err %v)", err)
	}
}

func TestAnOriginalPrepareIsAsItWasAndMentionsNothingNew(t *testing.T) {
	rig := newAppleRig(t, nil)
	for _, format := range []string{"", "original"} {
		response := rig.rawPrepare(format, "key-"+format)
		if response.Code != http.StatusOK {
			t.Fatalf("format %q: prepare = %d: %s", format, response.Code, response.Body.String())
		}
		body := response.Body.String()
		for _, absent := range []string{`"format"`, `"apple"`, `"statusUrl"`} {
			if strings.Contains(body, absent) {
				t.Errorf("format %q: an original manifest mentions %s", format, absent)
			}
		}
		var decoded OfflinePrepareResponse
		_ = json.Unmarshal(response.Body.Bytes(), &decoded)
		if len(decoded.Items[0].Subtitles) != 1 || decoded.Items[0].Source.Container != "mkv" {
			t.Errorf("format %q: manifest = %+v", format, decoded.Items[0])
		}
	}
}

func TestAnAppleDownloadIsPreparedThenServedByRange(t *testing.T) {
	rig := newAppleRig(t, nil)
	before := rig.sample.hash(t)
	manifest, ready := rig.ready(t)

	if ready.Format != "apple" || ready.Percent != 100 || ready.QueuePosition != 0 || ready.SizeBytes <= 0 ||
		ready.EstimatedSizeBytes != manifest.Apple.EstimatedSizeBytes || ready.Error != nil {
		t.Fatalf("ready = %+v", ready)
	}
	if !strings.HasPrefix(ready.ETag, `"`) || !strings.HasSuffix(ready.ETag, `"`) || strings.HasPrefix(ready.ETag, "W/") {
		t.Fatalf("etag = %q, want a strong validator", ready.ETag)
	}

	full := rig.request(http.MethodGet, manifest.MediaURL, "")
	if full.Code != http.StatusOK || int64(full.Body.Len()) != ready.SizeBytes {
		t.Fatalf("media = %d with %d bytes, want 200 and %d", full.Code, full.Body.Len(), ready.SizeBytes)
	}
	for name, want := range map[string]string{
		"Content-Type": "video/mp4", "Accept-Ranges": "bytes", "ETag": ready.ETag,
		"Content-Length": strconv.FormatInt(ready.SizeBytes, 10), "X-Content-Type-Options": "nosniff",
	} {
		if got := full.Header().Get(name); got != want {
			t.Errorf("%s = %q, want %q", name, got, want)
		}
	}
	for _, name := range []string{"Content-Disposition", "Last-Modified"} {
		if full.Header().Get(name) != "" {
			t.Errorf("the response has %s: %q", name, full.Header().Get(name))
		}
	}
	whole := full.Body.Bytes()

	ranged := func(headers map[string]string) *httptest.ResponseRecorder {
		recorder := httptest.NewRecorder()
		request := httptest.NewRequest(http.MethodGet, manifest.MediaURL, nil)
		request.Header.Set("Authorization", "Bearer "+libraryTestToken)
		request.Header.Set(jellyfinUserHeader, playbackUserID)
		for name, value := range headers {
			request.Header.Set(name, value)
		}
		rig.handler.ServeHTTP(recorder, request)
		return recorder
	}
	part := ranged(map[string]string{"Range": "bytes=10-19"})
	if part.Code != http.StatusPartialContent || !bytes.Equal(part.Body.Bytes(), whole[10:20]) ||
		part.Header().Get("Content-Range") != "bytes 10-19/"+strconv.FormatInt(ready.SizeBytes, 10) {
		t.Fatalf("range = %d %q %q", part.Code, part.Header().Get("Content-Range"), part.Body.Bytes())
	}
	if tail := ranged(map[string]string{"Range": "bytes=-16"}); tail.Code != http.StatusPartialContent || !bytes.Equal(tail.Body.Bytes(), whole[len(whole)-16:]) {
		t.Errorf("a suffix range = %d", tail.Code)
	}
	if resumed := ranged(map[string]string{"Range": "bytes=100-", "If-Range": ready.ETag}); resumed.Code != http.StatusPartialContent || !bytes.Equal(resumed.Body.Bytes(), whole[100:]) {
		t.Errorf("a resume with the ETag = %d", resumed.Code)
	}
	if changed := ranged(map[string]string{"Range": "bytes=100-", "If-Range": `"another-file"`}); changed.Code != http.StatusOK || !bytes.Equal(changed.Body.Bytes(), whole) {
		t.Errorf("a resume with another file's ETag = %d, want the whole file", changed.Code)
	}
	if multi := ranged(map[string]string{"Range": "bytes=0-1,5-6"}); multi.Code != http.StatusBadRequest {
		t.Errorf("two ranges = %d, want 400", multi.Code)
	}
	if beyond := ranged(map[string]string{"Range": "bytes=" + strconv.FormatInt(ready.SizeBytes+5, 10) + "-"}); beyond.Code != http.StatusRequestedRangeNotSatisfiable {
		t.Errorf("a range past the end = %d, want 416", beyond.Code)
	}
	head := httptest.NewRecorder()
	headRequest := httptest.NewRequest(http.MethodHead, manifest.MediaURL, nil)
	headRequest.Header.Set("Authorization", "Bearer "+libraryTestToken)
	headRequest.Header.Set(jellyfinUserHeader, playbackUserID)
	rig.handler.ServeHTTP(head, headRequest)
	if head.Code != http.StatusOK || head.Body.Len() != 0 || head.Header().Get("Content-Length") != strconv.FormatInt(ready.SizeBytes, 10) {
		t.Errorf("HEAD = %d with %d bytes and length %q", head.Code, head.Body.Len(), head.Header().Get("Content-Length"))
	}

	// What was served is an MP4 that holds what the manifest said.
	out := filepath.Join(t.TempDir(), "served.mp4")
	if err := os.WriteFile(out, whole, 0o600); err != nil {
		t.Fatal(err)
	}
	streams := probeMP4(t, out)
	var kinds, codecs, languages []string
	for _, stream := range streams {
		kinds, codecs, languages = append(kinds, stream.CodecType), append(codecs, stream.CodecName), append(languages, stream.Language)
	}
	if strings.Join(kinds, " ") != "video audio audio subtitle subtitle" || strings.Join(codecs, " ") != "hevc eac3 aac mov_text mov_text" ||
		strings.Join(languages, " ") != "und eng rus eng heb" {
		t.Errorf("the file holds %v %v %v", kinds, codecs, languages)
	}
	if streams[0].CodecTag != "hvc1" || streams[2].Channels != 6 {
		t.Errorf("picture tag %q, converted audio %d channels", streams[0].CodecTag, streams[2].Channels)
	}

	// The Hebrew subtitle was asked of Jellyfin as text, with the hub's own credential.
	calls, tokens := rig.upstream.subtitleFetches()
	if len(calls) != 1 || calls[0] != "/Videos/"+offlineItemID+"/"+offlineSourceID+"/Subtitles/5/0/Stream.srt" || tokens[0] != "test-key|" {
		t.Errorf("subtitle fetches = %v %v", calls, tokens)
	}

	// The cache holds the file and its record and nothing else; the source is untouched.
	names := dirNames(t, rig.cache)
	if len(names) != 2 || names[0] != manifest.GrantID+".json" || names[1] != manifest.GrantID+".mp4" {
		t.Errorf("cache = %v", names)
	}
	if rig.sample.hash(t) != before {
		t.Error("the source file changed")
	}
	if siblings := dirNames(t, filepath.Dir(rig.sample.path)); len(siblings) != 1 {
		t.Errorf("something was written beside the source: %v", siblings)
	}
	for _, response := range []*httptest.ResponseRecorder{full, part} {
		if bytes.Contains(response.Body.Bytes(), []byte(rig.sample.dir)) {
			t.Error("the served file names the source's folder")
		}
	}
}

func dirNames(t *testing.T, dir string) []string {
	t.Helper()
	entries, err := os.ReadDir(dir)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		t.Fatal(err)
	}
	var names []string
	for _, entry := range entries {
		names = append(names, entry.Name())
	}
	return names
}

func TestMediaIsAConflictWithItsReasonUntilTheFileIsReady(t *testing.T) {
	rig := newAppleRig(t, nil)
	gate := make(chan struct{})
	started := make(chan struct{}, 1)
	rig.server.buildMP4 = func(ctx context.Context, spec repackage.BuildSpec, progress func(int)) error {
		progress(40)
		started <- struct{}{}
		select {
		case <-gate:
		case <-ctx.Done():
			return ctx.Err()
		}
		return repackage.Build(ctx, spec, progress)
	}
	manifest := rig.prepare("apple")
	<-started

	preparing := rig.waitFor(manifest.GrantID, "preparing")
	if preparing.Percent != 40 || preparing.QueuePosition != 0 || preparing.SizeBytes != 0 || preparing.ETag != "" {
		t.Fatalf("status while running = %+v", preparing)
	}
	for _, method := range []string{http.MethodGet, http.MethodHead} {
		response := playbackRequest(rig.handler, method, manifest.MediaURL, "", playbackUserID)
		if response.Code != http.StatusConflict {
			t.Fatalf("%s while preparing = %d", method, response.Code)
		}
		if method == http.MethodGet {
			var body errorBody
			if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
				t.Fatal(err)
			}
			if body.Error.Code != "offline_preparing" || body.Error.Reason != "preparing" || !body.Error.Retryable ||
				response.Header().Get("Retry-After") != "5" {
				t.Errorf("conflict = %+v, Retry-After %q", body.Error, response.Header().Get("Retry-After"))
			}
		}
	}
	close(gate)
	rig.waitFor(manifest.GrantID, "ready")
	if response := rig.request(http.MethodGet, manifest.MediaURL, ""); response.Code != http.StatusOK {
		t.Fatalf("media once ready = %d", response.Code)
	}
}

func TestItemsAreRepackagedOneAtATimeInTheOrderPreparedWithoutAnyoneAsking(t *testing.T) {
	rig := newAppleRig(t, nil)
	var running, peak atomic.Int32
	started := make(chan string, 3)
	gate := make(chan struct{})
	rig.server.buildMP4 = func(ctx context.Context, spec repackage.BuildSpec, progress func(int)) error {
		now := running.Add(1)
		for {
			seen := peak.Load()
			if now <= seen || peak.CompareAndSwap(seen, now) {
				break
			}
		}
		defer running.Add(-1)
		started <- strings.TrimSuffix(filepath.Base(spec.Out), ".mp4.part")
		select {
		case <-gate:
		case <-ctx.Done():
			return ctx.Err()
		}
		return repackage.Build(ctx, spec, progress)
	}
	// Prepare the three at once and do not ask about any of them: the hub starts
	// work as soon as it is told what is wanted, in the order it was told.
	manifests := rig.prepareAll("apple", "first", "second", "third")
	next := func() string {
		select {
		case id := <-started:
			return id
		case <-time.After(10 * time.Second):
			t.Fatal("no repackage started")
			return ""
		}
	}
	if got := next(); got != manifests[0].GrantID {
		t.Fatalf("started %s first, want the first prepared, %s", got, manifests[0].GrantID)
	}
	first, second, third := rig.status(manifests[0].GrantID), rig.status(manifests[1].GrantID), rig.status(manifests[2].GrantID)
	if first.State != "preparing" || first.QueuePosition != 0 || second.State != "queued" || second.QueuePosition != 1 ||
		third.State != "queued" || third.QueuePosition != 2 {
		t.Fatalf("first %+v, second %+v, third %+v", first, second, third)
	}
	select {
	case other := <-started:
		t.Fatalf("%s started while the first was running", other)
	case <-time.After(100 * time.Millisecond):
	}
	close(gate)
	if got := next(); got != manifests[1].GrantID {
		t.Fatalf("started %s second, want %s", got, manifests[1].GrantID)
	}
	if got := next(); got != manifests[2].GrantID {
		t.Fatalf("started %s third, want %s", got, manifests[2].GrantID)
	}
	for _, manifest := range manifests {
		rig.waitFor(manifest.GrantID, "ready")
	}
	if peak.Load() != 1 {
		t.Errorf("%d repackages ran at once", peak.Load())
	}
}

func TestAFailedRepackageIsNamedWithoutAPathAndRetryQueuesItAgain(t *testing.T) {
	rig := newAppleRig(t, nil)
	var calls atomic.Int32
	rig.server.buildMP4 = func(ctx context.Context, spec repackage.BuildSpec, progress func(int)) error {
		if calls.Add(1) == 1 {
			return &repackage.FFmpegError{
				Err:    errors.New("exit status 1"),
				Stderr: "Error opening input file " + spec.Inputs.SourcePath + ": Invalid data found when processing input",
			}
		}
		return repackage.Build(ctx, spec, progress)
	}
	manifest := rig.prepare("apple")
	failed := rig.waitFor(manifest.GrantID, "failed")
	if failed.Error == nil || failed.Error.Code != "ffmpeg_failed" || !failed.Error.Retryable {
		t.Fatalf("failure = %+v", failed.Error)
	}
	for _, text := range []string{failed.Error.Message, failed.Error.Code} {
		if strings.Contains(text, rig.sample.dir) || strings.Contains(text, "Invalid data") {
			t.Errorf("the failure says too much: %q", text)
		}
	}

	// Media answers with the failure rather than a wait.
	response := rig.request(http.MethodGet, manifest.MediaURL, "")
	var body errorBody
	_ = json.Unmarshal(response.Body.Bytes(), &body)
	if response.Code != http.StatusConflict || body.Error.Code != "offline_failed" || body.Error.Reason != "ffmpeg_failed" {
		t.Fatalf("media after a failure = %d %+v", response.Code, body.Error)
	}
	if strings.Contains(response.Body.String(), rig.sample.dir) {
		t.Error("the failure response names a path")
	}
	// Asking again does not start it again; a retry does.
	if again := rig.status(manifest.GrantID); again.State != "failed" || calls.Load() != 1 {
		t.Fatalf("asking again after a failure: %+v after %d runs", again, calls.Load())
	}
	retried := rig.request(http.MethodPost, "/v1/offline/grants/"+manifest.GrantID+"/retry", "")
	if retried.Code != http.StatusOK {
		t.Fatalf("retry = %d: %s", retried.Code, retried.Body.String())
	}
	var queued OfflineStatus
	_ = json.Unmarshal(retried.Body.Bytes(), &queued)
	if queued.State != "queued" && queued.State != "preparing" && queued.State != "ready" {
		t.Fatalf("after a retry the state is %q", queued.State)
	}
	rig.waitFor(manifest.GrantID, "ready")
	// Retrying what is finished changes nothing.
	again := rig.request(http.MethodPost, "/v1/offline/grants/"+manifest.GrantID+"/retry", "")
	var settled OfflineStatus
	_ = json.Unmarshal(again.Body.Bytes(), &settled)
	if settled.State != "ready" || calls.Load() != 2 {
		t.Errorf("a retry of a finished file: %+v after %d runs", settled, calls.Load())
	}
}

func TestReleasingFreesTheFileAndAskingAgainMakesItAgain(t *testing.T) {
	rig := newAppleRig(t, nil)
	manifest, first := rig.ready(t)
	if response := rig.request(http.MethodDelete, manifest.MediaURL, ""); response.Code != http.StatusNoContent || response.Body.Len() != 0 {
		t.Fatalf("release = %d %q", response.Code, response.Body.String())
	}
	deadline := time.Now().Add(5 * time.Second)
	for len(dirNames(t, rig.cache)) != 0 {
		if time.Now().After(deadline) {
			t.Fatalf("the cache still holds %v", dirNames(t, rig.cache))
		}
		time.Sleep(5 * time.Millisecond)
	}
	// Releasing twice is fine, and the grant is still good: asking starts it again.
	if response := rig.request(http.MethodDelete, manifest.MediaURL, ""); response.Code != http.StatusNoContent {
		t.Fatalf("a second release = %d", response.Code)
	}
	second := rig.waitFor(manifest.GrantID, "ready")
	if second.SizeBytes <= 0 || second.ETag == first.ETag {
		t.Errorf("rebuilt = %+v, first %+v: a rebuilt file is a different file", second, first)
	}
}

func TestAnAppleGrantIsScopedLikeEveryOfflineGrant(t *testing.T) {
	rig := newAppleRig(t, func(cfg *config.Config) {
		cfg.Auth.Tokens = append(cfg.Auth.Tokens, config.TokenConfig{
			Label: "other", Raw: config.Secret("a-different-strong-token-with-more-than-32-characters"),
		}, config.TokenConfig{
			Label: "reader", Raw: config.Secret("a-read-only-strong-token-with-more-than-32-characters"), Scopes: []string{"read", "play"},
		})
	})
	manifest, _ := rig.ready(t)
	with := func(token, user, method, path string) *httptest.ResponseRecorder {
		recorder := httptest.NewRecorder()
		request := httptest.NewRequest(method, path, nil)
		request.Header.Set("Authorization", "Bearer "+token)
		request.Header.Set(jellyfinUserHeader, user)
		rig.handler.ServeHTTP(recorder, request)
		return recorder
	}
	routes := []struct{ method, path string }{
		{http.MethodGet, "/v1/offline/grants/" + manifest.GrantID + "/status"},
		{http.MethodGet, "/v1/offline/grants/" + manifest.GrantID + "/media"},
		{http.MethodPost, "/v1/offline/grants/" + manifest.GrantID + "/retry"},
		{http.MethodDelete, "/v1/offline/grants/" + manifest.GrantID + "/media"},
	}
	for _, route := range routes {
		if got := with(libraryTestToken, offlineOtherUser, route.method, route.path); got.Code != http.StatusNotFound {
			t.Errorf("%s %s as another Jellyfin user = %d, want 404", route.method, route.path, got.Code)
		}
		if got := with("a-different-strong-token-with-more-than-32-characters", playbackUserID, route.method, route.path); got.Code != http.StatusNotFound {
			t.Errorf("%s %s as another token = %d, want 404", route.method, route.path, got.Code)
		}
		if got := with("a-read-only-strong-token-with-more-than-32-characters", playbackUserID, route.method, route.path); got.Code != http.StatusForbidden {
			t.Errorf("%s %s without the download scope = %d, want 403", route.method, route.path, got.Code)
		}
	}
	// Nothing of the above released it.
	if status := rig.status(manifest.GrantID); status.State != "ready" {
		t.Fatalf("after the refusals the file is %+v", status)
	}
	// A grant that does not exist, and a malformed id, are 404 like any other.
	if got := rig.request(http.MethodGet, "/v1/offline/grants/00000000000000000000000000000000/status", ""); got.Code != http.StatusNotFound {
		t.Errorf("an unknown grant = %d", got.Code)
	}

	// An expired grant may no longer be played or asked about, but may still free its file.
	grant, _ := rig.server.offline.get(manifest.GrantID)
	grant.ExpiresAt = time.Now().Add(-time.Minute).UnixMilli()
	grant.Manifest.ExpiresAt = grant.ExpiresAt
	if err := rig.server.offline.put(grant); err != nil {
		t.Fatal(err)
	}
	for _, path := range []string{"/status", "/media"} {
		if got := rig.request(http.MethodGet, "/v1/offline/grants/"+manifest.GrantID+path, ""); got.Code != http.StatusGone {
			t.Errorf("GET %s after expiry = %d, want 410", path, got.Code)
		}
	}
	if got := rig.request(http.MethodDelete, manifest.MediaURL, ""); got.Code != http.StatusNoContent {
		t.Errorf("a release after expiry = %d, want 204", got.Code)
	}
}

func TestAnApplePrepareThatCannotBeKeptSaysWhy(t *testing.T) {
	type refusal struct {
		name   string
		mutate func(*appleRig)
		format string
		status int
		code   string
		reason string
	}
	for _, test := range []refusal{
		{name: "an unknown format", format: "mp3", status: http.StatusBadRequest, code: "invalid_request"},
		{name: "no ffmpeg", format: "apple", status: http.StatusServiceUnavailable, code: "offline_apple_unavailable", reason: "no_ffmpeg",
			mutate: func(r *appleRig) {
				r.server.ffmpegPath = func() (string, error) { return "", errors.New("ffmpeg executable was not found") }
			}},
		{name: "no cache folder", format: "apple", status: http.StatusServiceUnavailable, code: "offline_apple_unavailable", reason: "no_cache",
			mutate: func(r *appleRig) { r.start(func(cfg *config.Config) { cfg.Server.OfflineCache = "" }) }},
		{name: "a film with no picture", format: "apple", status: http.StatusConflict, code: "invalid_request", reason: "no_video",
			mutate: func(r *appleRig) {
				r.upstream.change(func(source map[string]any) {
					source["MediaStreams"] = []any{map[string]any{"Index": 1, "Type": "Audio", "Codec": "aac", "Language": "eng", "Channels": 2}}
				})
			}},
		{name: "a source with no path", format: "apple", status: http.StatusConflict, code: "invalid_request", reason: "no_source_file",
			mutate: func(r *appleRig) { r.upstream.change(func(source map[string]any) { source["Path"] = "" }) }},
		{name: "a path that is not a file path", format: "apple", status: http.StatusConflict, code: "invalid_request", reason: "no_source_file",
			mutate: func(r *appleRig) {
				r.upstream.change(func(source map[string]any) { source["Path"] = "http://127.0.0.1:9/film.mkv" })
			}},
		{name: "a file that is not on this PC", format: "apple", status: http.StatusConflict, code: "invalid_request", reason: "no_source_file",
			mutate: func(r *appleRig) {
				r.upstream.change(func(source map[string]any) {
					source["Path"] = filepath.Join(filepath.Dir(r.sample.path), "gone.mkv")
				})
			}},
	} {
		t.Run(test.name, func(t *testing.T) {
			rig := newAppleRig(t, nil)
			if test.mutate != nil {
				test.mutate(rig)
			}
			response := rig.rawPrepare(test.format, "movie-1")
			var body errorBody
			_ = json.Unmarshal(response.Body.Bytes(), &body)
			if response.Code != test.status || body.Error.Code != test.code || body.Error.Reason != test.reason {
				t.Fatalf("prepare = %d %+v: %s", response.Code, body.Error, response.Body.String())
			}
			if strings.Contains(response.Body.String(), rig.sample.dir) {
				t.Error("the refusal names a path")
			}
			// A refusal is all or nothing: nothing was queued or kept.
			if names := dirNames(t, rig.cache); len(names) != 0 {
				t.Errorf("a refused prepare left %v in the cache", names)
			}
		})
	}
}

func TestAChangedSourceFailsTheRepackageAsSourceChanged(t *testing.T) {
	rig := newAppleRig(t, nil)
	// Prepare two, and change the file while the worker is held by the first: the
	// second must notice, and must not run ffmpeg on a file that is not the one
	// that was prepared.
	hold := make(chan struct{})
	var runs atomic.Int32
	rig.server.buildMP4 = func(ctx context.Context, spec repackage.BuildSpec, progress func(int)) error {
		runs.Add(1)
		<-hold
		return repackage.Build(ctx, spec, progress)
	}
	manifests := rig.prepareAll("apple", "first", "second")
	rig.waitFor(manifests[0].GrantID, "preparing")
	for deadline := time.Now().Add(10 * time.Second); runs.Load() != 1; time.Sleep(2 * time.Millisecond) {
		if time.Now().After(deadline) {
			t.Fatal("the first repackage never reached ffmpeg")
		}
	}
	file, err := os.OpenFile(rig.sample.path, os.O_APPEND|os.O_WRONLY, 0)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := file.WriteString("more"); err != nil {
		t.Fatal(err)
	}
	file.Close()
	close(hold)
	changed := rig.waitFor(manifests[1].GrantID, "failed")
	if changed.Error == nil || changed.Error.Code != "source_changed" || changed.Error.Retryable {
		t.Fatalf("failure = %+v", changed.Error)
	}
	// The first was past the check when the file changed and ran; the second did not.
	if runs.Load() != 1 {
		t.Fatalf("ffmpeg ran %d times, want only for the first", runs.Load())
	}
}

func TestAFileJellyfinNoLongerHasFailsAsSourceMissing(t *testing.T) {
	rig := newAppleRig(t, nil)
	hold := make(chan struct{})
	rig.server.buildMP4 = func(ctx context.Context, spec repackage.BuildSpec, progress func(int)) error {
		<-hold
		return repackage.Build(ctx, spec, progress)
	}
	manifests := rig.prepareAll("apple", "first", "second")
	rig.waitFor(manifests[0].GrantID, "preparing")
	if err := os.Remove(rig.sample.path); err != nil {
		t.Fatal(err)
	}
	close(hold)
	missing := rig.waitFor(manifests[1].GrantID, "failed")
	if missing.Error == nil || missing.Error.Code != "source_missing" {
		t.Fatalf("failure = %+v", missing.Error)
	}
}

func TestAHubWithoutFFmpegFailsAJobAsNoFFmpegAndNamesNoPath(t *testing.T) {
	rig := newAppleRig(t, nil)
	manifest := rig.prepare("apple")
	rig.waitFor(manifest.GrantID, "ready")
	rig.request(http.MethodDelete, manifest.MediaURL, "")
	rig.server.ffmpegPath = func() (string, error) { return "", errors.New("ffmpeg executable was not found at C:\\secret") }
	// Status still answers (it needs only the cache); the build is what needs ffmpeg.
	failed := rig.waitFor(manifest.GrantID, "failed")
	if failed.Error.Code != "no_ffmpeg" || failed.Error.Retryable || strings.Contains(failed.Error.Message, "secret") {
		t.Fatalf("failure = %+v", failed.Error)
	}
}

func TestAMissingSubtitleFromJellyfinFailsTheJobAsSubtitleUnavailable(t *testing.T) {
	rig := newAppleRig(t, nil)
	rig.upstream.mu.Lock()
	rig.upstream.subtitleMissing = true
	rig.upstream.mu.Unlock()
	manifest := rig.prepare("apple")
	failed := rig.waitFor(manifest.GrantID, "failed")
	if failed.Error.Code != "subtitle_unavailable" || !failed.Error.Retryable {
		t.Fatalf("failure = %+v", failed.Error)
	}
}

func TestRenewKeepsAnAppleGrantAndItsFileAndRefusesAChangedPlan(t *testing.T) {
	rig := newAppleRig(t, nil)
	manifest, ready := rig.ready(t)
	expire := func() {
		grant, _ := rig.server.offline.get(manifest.GrantID)
		grant.ExpiresAt = time.Now().Add(-time.Minute).UnixMilli()
		grant.Manifest.ExpiresAt = grant.ExpiresAt
		if err := rig.server.offline.put(grant); err != nil {
			t.Fatal(err)
		}
	}
	expire()
	renewed := rig.request(http.MethodPost, "/v1/offline/grants/"+manifest.GrantID+"/renew", `{}`)
	if renewed.Code != http.StatusOK {
		t.Fatalf("renew = %d: %s", renewed.Code, renewed.Body.String())
	}
	var again OfflineManifest
	if err := json.Unmarshal(renewed.Body.Bytes(), &again); err != nil {
		t.Fatal(err)
	}
	if again.GrantID != manifest.GrantID || again.Format != "apple" || again.Apple == nil ||
		again.Apple.StatusURL != "/v1/offline/grants/"+manifest.GrantID+"/status" ||
		again.MediaURL != manifest.MediaURL || len(again.Subtitles) != 0 || again.ExpiresAt <= time.Now().UnixMilli() {
		t.Fatalf("renewed = %+v", again)
	}
	// The same file is still there: nothing was rebuilt.
	if status := rig.status(manifest.GrantID); status.State != "ready" || status.ETag != ready.ETag {
		t.Fatalf("after a renewal the file is %+v, want the one it was (%s)", status, ready.ETag)
	}

	// A source that now has another audio track is another plan: restart the item.
	expire()
	rig.upstream.change(func(source map[string]any) {
		streams := source["MediaStreams"].([]any)
		source["MediaStreams"] = append(streams, map[string]any{"Index": 6, "Type": "Audio", "Codec": "ac3", "Language": "heb", "Channels": 2})
	})
	changed := rig.request(http.MethodPost, "/v1/offline/grants/"+manifest.GrantID+"/renew", `{}`)
	var body errorBody
	_ = json.Unmarshal(changed.Body.Bytes(), &body)
	if changed.Code != http.StatusConflict || body.Error.Code != "source_changed" {
		t.Fatalf("a renewal of a changed plan = %d %+v", changed.Code, body.Error)
	}
}

func TestAnAppleGrantHasNoSidecarSubtitlesToFetch(t *testing.T) {
	rig := newAppleRig(t, nil)
	manifest := rig.prepare("apple")
	for _, track := range []string{"3", "5", "0"} {
		if response := rig.request(http.MethodGet, "/v1/offline/grants/"+manifest.GrantID+"/subtitles/"+track, ""); response.Code != http.StatusNotFound {
			t.Errorf("subtitle %s = %d, want 404: they are inside the MP4", track, response.Code)
		}
	}
}

func TestOneKeyInTwoFormatsIsTwoGrantsAndEachIsIdempotent(t *testing.T) {
	rig := newAppleRig(t, nil)
	original := rig.prepareAll("", "same-key")[0]
	apple := rig.prepareAll("apple", "same-key")[0]
	if original.GrantID == apple.GrantID || original.Format != "" || apple.Format != "apple" {
		t.Fatalf("original %q (%q), apple %q (%q)", original.GrantID, original.Format, apple.GrantID, apple.Format)
	}
	if again := rig.prepareAll("apple", "same-key")[0]; again.GrantID != apple.GrantID {
		t.Errorf("the apple grant changed from %s to %s", apple.GrantID, again.GrantID)
	}
	if again := rig.prepareAll("original", "same-key")[0]; again.GrantID != original.GrantID {
		t.Errorf("the original grant changed from %s to %s", original.GrantID, again.GrantID)
	}
	// And the original is still the original: it is proxied from Jellyfin, not repackaged.
	if status := rig.status(original.GrantID); status.Format != "original" || status.State != "ready" || status.ETag != "" || status.SizeBytes != rig.sample.size {
		t.Errorf("an original grant's status = %+v", status)
	}
	if response := rig.request(http.MethodDelete, original.MediaURL, ""); response.Code != http.StatusNoContent {
		t.Errorf("releasing an original grant = %d, want 204 (nothing held)", response.Code)
	}
	if response := rig.request(http.MethodPost, "/v1/offline/grants/"+original.GrantID+"/retry", ""); response.Code != http.StatusOK {
		t.Errorf("retrying an original grant = %d", response.Code)
	}
}

func TestAFinishedAppleFileSurvivesAHubRestart(t *testing.T) {
	rig := newAppleRig(t, nil)
	manifest, ready := rig.ready(t)
	rig.server.closeRepackager()

	var rebuilds atomic.Int32
	rig.start(nil)
	rig.server.buildMP4 = func(ctx context.Context, spec repackage.BuildSpec, progress func(int)) error {
		rebuilds.Add(1)
		return repackage.Build(ctx, spec, progress)
	}
	status := rig.status(manifest.GrantID)
	if status.State != "ready" || status.ETag != ready.ETag || status.SizeBytes != ready.SizeBytes {
		t.Fatalf("after a restart %+v, want the file that was ready (%s)", status, ready.ETag)
	}
	if response := rig.request(http.MethodGet, manifest.MediaURL, ""); response.Code != http.StatusOK || int64(response.Body.Len()) != ready.SizeBytes {
		t.Fatalf("media after a restart = %d", response.Code)
	}
	if rebuilds.Load() != 0 {
		t.Fatalf("a finished file was built again %d times", rebuilds.Load())
	}
}

func TestTheSelectionCanPreviewTheMP4AnAppleDeviceWouldGet(t *testing.T) {
	rig := newAppleRig(t, nil)
	rig.upstream.mu.Lock()
	rig.upstream.itemType = "Episode"
	rig.upstream.mu.Unlock()

	plain := rig.request(http.MethodGet, "/v1/offline/series/"+offlineSeriesID+"/selection", "")
	if plain.Code != http.StatusOK || strings.Contains(plain.Body.String(), `"apple"`) {
		t.Fatalf("a selection without a format = %d, mentioning apple: %v", plain.Code, strings.Contains(plain.Body.String(), `"apple"`))
	}
	var original OfflineSelectionResponse
	_ = json.Unmarshal(plain.Body.Bytes(), &original)

	preview := rig.request(http.MethodGet, "/v1/offline/series/"+offlineSeriesID+"/selection?format=apple", "")
	if preview.Code != http.StatusOK {
		t.Fatalf("selection = %d: %s", preview.Code, preview.Body.String())
	}
	var catalog OfflineSelectionResponse
	if err := json.Unmarshal(preview.Body.Bytes(), &catalog); err != nil {
		t.Fatal(err)
	}
	episode := catalog.Seasons[0].Episodes[0]
	if !episode.Available || episode.Apple == nil || episode.Apple.VideoConverted || episode.Apple.AudioConverted != 1 || episode.Apple.OmittedSubtitles != 1 {
		t.Fatalf("episode = %+v", episode)
	}
	if episode.EstimatedSizeBytes != episode.Apple.EstimatedSizeBytes || episode.EstimatedSizeBytes <= 0 ||
		catalog.EstimatedSizeBytes != episode.EstimatedSizeBytes || catalog.EpisodeCount != 1 {
		t.Errorf("sizes: episode %d, apple %d, total %d (the original is %d)",
			episode.EstimatedSizeBytes, episode.Apple.EstimatedSizeBytes, catalog.EstimatedSizeBytes, original.EstimatedSizeBytes)
	}
	// The sources are still the original's.
	if len(episode.Sources) != 1 || episode.Sources[0].Container != "mkv" || episode.Sources[0].SizeBytes != rig.sample.size {
		t.Errorf("sources = %+v", episode.Sources)
	}

	// An episode with no picture cannot be an MP4.
	rig.upstream.change(func(source map[string]any) {
		source["MediaStreams"] = []any{map[string]any{"Index": 1, "Type": "Audio", "Codec": "aac", "Channels": 2}}
	})
	empty := rig.request(http.MethodGet, "/v1/offline/series/"+offlineSeriesID+"/selection?format=apple", "")
	var none OfflineSelectionResponse
	_ = json.Unmarshal(empty.Body.Bytes(), &none)
	if got := none.Seasons[0].Episodes[0]; got.Available || got.Apple != nil || none.EpisodeCount != 0 {
		t.Errorf("an episode with no picture = %+v (count %d)", got, none.EpisodeCount)
	}
	if bad := rig.request(http.MethodGet, "/v1/offline/series/"+offlineSeriesID+"/selection?format=flac", ""); bad.Code != http.StatusBadRequest {
		t.Errorf("an unknown format = %d, want 400", bad.Code)
	}
}

func TestTheGrantRegistryNamesTheFormatAndThePlanAndNoPath(t *testing.T) {
	rig := newAppleRig(t, nil)
	manifest := rig.prepare("apple")
	original := rig.prepareAll("", "other")[0]
	registry, err := os.ReadFile(rig.registry)
	if err != nil {
		t.Fatal(err)
	}
	var decoded offlineRegistryFile
	if err := json.Unmarshal(registry, &decoded); err != nil {
		t.Fatal(err)
	}
	apple, plain := decoded.Grants[manifest.GrantID], decoded.Grants[original.GrantID]
	if apple.Format != "apple" || len(apple.PlanSignature) != 16 || plain.Format != "" || plain.PlanSignature != "" {
		t.Fatalf("registry: apple %q %q, original %q %q", apple.Format, apple.PlanSignature, plain.Format, plain.PlanSignature)
	}
	if bytes.Contains(registry, []byte(rig.sample.dir)) || bytes.Contains(registry, []byte("Film (2024)")) {
		t.Error("the registry names the source file")
	}
	// An older registry, written before any of this, still reads as original grants.
	if strings.Contains(string(registry), `"format":""`) {
		t.Error("an original grant writes an empty format into the registry")
	}
}

// Jellyfin may leave the files' paths out of what a profile that is not an
// administrator's reads, and the repackage needs the file, so the hub asks the
// server itself for the path, at prepare and again when it builds.
func TestAProfileThatIsNotShownThePathStillGetsAnAppleDownload(t *testing.T) {
	rig := newAppleRig(t, nil)
	rig.upstream.mu.Lock()
	rig.upstream.hidePaths = true
	rig.upstream.mu.Unlock()
	manifest := rig.prepare("apple")
	if manifest.Apple == nil || manifest.Apple.Video.Codec != "hevc" {
		t.Fatalf("manifest = %+v", manifest)
	}
	ready := rig.waitFor(manifest.GrantID, "ready")
	if ready.SizeBytes <= 0 {
		t.Fatalf("ready = %+v", ready)
	}
	rig.upstream.mu.Lock()
	reads := rig.upstream.serverReads
	rig.upstream.mu.Unlock()
	if reads < 2 {
		t.Errorf("the server was asked for the path %d times, want at prepare and at build", reads)
	}
	// And the manifest still names no path.
	body, _ := json.Marshal(manifest)
	if strings.Contains(string(body), rig.sample.dir) {
		t.Error("the manifest names the source's folder")
	}
}

// The plan is checked again when the file is built: a source that Jellyfin has
// since analysed differently (another track, another codec) holds a different MP4
// from the one the manifest promised.
func TestAChangedPlanFailsTheRepackageAsSourceChanged(t *testing.T) {
	rig := newAppleRig(t, nil)
	hold := make(chan struct{})
	var runs atomic.Int32
	rig.server.buildMP4 = func(ctx context.Context, spec repackage.BuildSpec, progress func(int)) error {
		runs.Add(1)
		<-hold
		return repackage.Build(ctx, spec, progress)
	}
	manifests := rig.prepareAll("apple", "first", "second")
	rig.waitFor(manifests[0].GrantID, "preparing")
	for deadline := time.Now().Add(10 * time.Second); runs.Load() != 1; time.Sleep(2 * time.Millisecond) {
		if time.Now().After(deadline) {
			t.Fatal("the first repackage never reached ffmpeg")
		}
	}
	rig.upstream.change(func(source map[string]any) {
		streams := source["MediaStreams"].([]any)
		source["MediaStreams"] = append(streams, map[string]any{"Index": 6, "Type": "Audio", "Codec": "ac3", "Language": "heb", "Channels": 2})
	})
	close(hold)
	changed := rig.waitFor(manifests[1].GrantID, "failed")
	if changed.Error == nil || changed.Error.Code != "source_changed" || changed.Error.Retryable {
		t.Fatalf("failure = %+v", changed.Error)
	}
	if runs.Load() != 1 {
		t.Fatalf("ffmpeg ran %d times", runs.Load())
	}
}
