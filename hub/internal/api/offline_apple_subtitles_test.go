package api

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"ayaneohub/internal/adapters/jellyfin"
	"ayaneohub/internal/config"
	"ayaneohub/internal/webvtt"
)

// An Apple download's kept subtitles (#45). These tests need no ffmpeg: a grant is
// made the way a prepare makes it, over a small file on disk, and the fake Jellyfin
// holds the subtitle files and says what it was asked for.

const (
	hebrewWords = "שלום עולם"
	subtitleDir = "/v1/offline/grants/"
)

// windows1255 writes Hebrew letters as the legacy code page does, as a subtitle file
// made on an old Windows PC would hold them.
func windows1255(text string) []byte {
	var out []byte
	for _, r := range text {
		if r >= 0x05D0 && r <= 0x05EA {
			out = append(out, byte(0xE0+(r-0x05D0)))
		} else {
			out = append(out, byte(r))
		}
	}
	return out
}

// jellyfinDecodes is what Jellyfin does before it answers: detect the file's code
// page and hand over UTF-8.
func jellyfinDecodes(raw []byte) string {
	var out strings.Builder
	for _, b := range raw {
		if b >= 0xE0 && b <= 0xFA {
			out.WriteRune(rune(0x05D0 + int(b) - 0xE0))
		} else {
			out.WriteByte(b)
		}
	}
	return out.String()
}

type subtitleFile struct {
	text string
	// raw, when set, is the file's bytes in Windows-1255, which the fake decodes.
	raw []byte
	// status, when set, is what Jellyfin answers instead of the file.
	status int
	// refuses are the formats Jellyfin answers 400 to for this track.
	refuses []string
}

type subtitleUpstream struct {
	t      *testing.T
	server *httptest.Server

	mu       sync.Mutex
	source   map[string]any
	files    map[int]subtitleFile
	asked    []string // "index:format", in order
	itemGone bool
	delay    time.Duration
	inFlight int
	peak     int
}

func subStream(index int, codec, language, title string, flags ...string) map[string]any {
	stream := map[string]any{"Index": index, "Type": "Subtitle", "Codec": codec, "Language": language, "Title": title,
		"DisplayTitle": strings.TrimSpace(language + " - " + codec)}
	for _, flag := range flags {
		switch flag {
		case "external":
			stream["IsExternal"] = true
		case "forced":
			stream["IsForced"] = true
		case "default":
			stream["IsDefault"] = true
		case "hi":
			stream["IsHearingImpaired"] = true
		}
	}
	return stream
}

const srtEnglish = "1\n00:00:01,000 --> 00:00:02,000\nHello there\n"

const assEnglish = "[Script Info]\nScriptType: v4.00+\n\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
	"Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,{\\an8}Signs\\Nand more\n"

const vttEnglish = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nAlready WebVTT\n"

// newSubtitleUpstream is a Jellyfin whose film has an English SRT inside the file
// (2), a forced ASS track inside it (3), a French picture subtitle (4) and a Hebrew
// SRT beside it (5), which holds Windows-1255 bytes on its disk.
func newSubtitleUpstream(t *testing.T, path string) *subtitleUpstream {
	t.Helper()
	u := &subtitleUpstream{t: t, files: map[int]subtitleFile{}}
	u.source = map[string]any{
		"Id": offlineSourceID, "Name": "Original 1080p", "Path": path, "Container": "mkv", "Size": 123456,
		"Bitrate": 8_000_000, "RunTimeTicks": int64(3 * 10_000_000), "DefaultAudioStreamIndex": 1,
		"MediaStreams": []any{
			map[string]any{"Index": 0, "Type": "Video", "Codec": "h264", "Width": 1920, "Height": 1080, "PixelFormat": "yuv420p"},
			map[string]any{"Index": 1, "Type": "Audio", "Codec": "aac", "Language": "eng", "Channels": 2, "IsDefault": true},
			subStream(2, "subrip", "eng", "Dialogue", "default"),
			subStream(3, "ass", "eng", "Signs", "forced"),
			subStream(4, "hdmv_pgs_subtitle", "fre", ""),
			subStream(5, "subrip", "heb", "", "external"),
		},
	}
	u.files[2] = subtitleFile{text: srtEnglish}
	u.files[3] = subtitleFile{text: assEnglish}
	u.files[5] = subtitleFile{raw: windows1255("1\n00:00:00,500 --> 00:00:02,000\n" + hebrewWords + "\n")}
	u.server = httptest.NewServer(http.HandlerFunc(u.serve))
	t.Cleanup(u.server.Close)
	return u
}

func (u *subtitleUpstream) change(edit func(source map[string]any)) {
	u.mu.Lock()
	defer u.mu.Unlock()
	edit(u.source)
}

// addStream puts a subtitle stream into the source at its index.
func (u *subtitleUpstream) addStream(stream map[string]any, file subtitleFile) {
	u.mu.Lock()
	defer u.mu.Unlock()
	streams := u.source["MediaStreams"].([]any)
	u.source["MediaStreams"] = append(streams, stream)
	u.files[stream["Index"].(int)] = file
}

func (u *subtitleUpstream) setFile(index int, file subtitleFile) {
	u.mu.Lock()
	defer u.mu.Unlock()
	u.files[index] = file
}

func (u *subtitleUpstream) itemJSON() string {
	u.mu.Lock()
	defer u.mu.Unlock()
	document := map[string]any{
		"Id": offlineItemID, "Name": "Film", "Type": "Movie", "RunTimeTicks": int64(3 * 10_000_000),
		"MediaSources": []any{u.source},
	}
	body, err := json.Marshal(document)
	if err != nil {
		u.t.Fatal(err)
	}
	return string(body)
}

var subtitleResource = regexp.MustCompile(`^/Videos/` + offlineItemID + `/` + offlineSourceID + `/Subtitles/(\d+)/0/Stream\.([a-z]+)$`)

func (u *subtitleUpstream) serve(w http.ResponseWriter, r *http.Request) {
	if r.Header.Get("X-Emby-Token") != "test-key" || strings.Contains(strings.ToLower(r.URL.RawQuery), "apikey") {
		u.t.Errorf("a request to Jellyfin without the hub's credential in its header, or with one in its URL: %s?%s", r.URL.Path, r.URL.RawQuery)
	}
	if r.Method == http.MethodGet && r.URL.Path == "/Items/"+offlineItemID {
		u.mu.Lock()
		gone := u.itemGone
		u.mu.Unlock()
		if gone {
			http.NotFound(w, r)
			return
		}
		_, _ = io.WriteString(w, u.itemJSON())
		return
	}
	match := subtitleResource.FindStringSubmatch(r.URL.Path)
	if r.Method != http.MethodGet || match == nil {
		http.NotFound(w, r)
		return
	}
	index, _ := strconv.Atoi(match[1])
	u.mu.Lock()
	u.asked = append(u.asked, match[1]+":"+match[2])
	file, found := u.files[index]
	delay := u.delay
	u.inFlight++
	u.peak = max(u.peak, u.inFlight)
	u.mu.Unlock()
	defer func() {
		u.mu.Lock()
		u.inFlight--
		u.mu.Unlock()
	}()
	if delay > 0 {
		time.Sleep(delay)
	}
	switch {
	case !found:
		http.NotFound(w, r)
	case slices.Contains(file.refuses, match[2]):
		w.WriteHeader(http.StatusBadRequest)
	case file.status != 0:
		w.WriteHeader(file.status)
	case file.raw != nil:
		_, _ = io.WriteString(w, jellyfinDecodes(file.raw))
	default:
		_, _ = io.WriteString(w, file.text)
	}
}

func (u *subtitleUpstream) askedFor() []string {
	u.mu.Lock()
	defer u.mu.Unlock()
	return append([]string(nil), u.asked...)
}

func (u *subtitleUpstream) forget() {
	u.mu.Lock()
	defer u.mu.Unlock()
	u.asked = nil
}

type subtitleRig struct {
	t        *testing.T
	dir      string
	path     string
	upstream *subtitleUpstream
	server   *Server
	handler  http.Handler
	grant    offlineGrant
}

func newSubtitleRig(t *testing.T) *subtitleRig {
	t.Helper()
	dir := t.TempDir()
	library := filepath.Join(dir, "library")
	if err := os.MkdirAll(library, 0o700); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(library, "Film (2024).mkv")
	if err := os.WriteFile(path, []byte("not really a film"), 0o600); err != nil {
		t.Fatal(err)
	}
	rig := &subtitleRig{t: t, dir: dir, path: path, upstream: newSubtitleUpstream(t, path)}
	cfg := offlineConfig(rig.upstream.server.URL, filepath.Join(dir, "registry.json"))
	cfg.Auth.Tokens = append(cfg.Auth.Tokens, config.TokenConfig{
		Label: "other", Raw: config.Secret("a-different-strong-token-with-more-than-32-characters"),
	}, config.TokenConfig{
		Label: "reader", Raw: config.Secret("a-read-only-strong-token-with-more-than-32-characters"), Scopes: []string{"read", "play"},
	})
	rig.server = NewServer(cfg)
	rig.handler = rig.server.Handler()
	rig.grant = rig.makeGrant(offlineFormatApple, "movie-1")
	return rig
}

// makeGrant stores a grant for the film the way a prepare does, from the source as
// it is now.
func (r *subtitleRig) makeGrant(format, clientKey string) offlineGrant {
	r.t.Helper()
	var item jellyfin.Item
	if err := json.Unmarshal([]byte(r.upstream.itemJSON()), &item); err != nil {
		r.t.Fatal(err)
	}
	grant, err := makeGrant(format, "test", playbackUserID, "batch-1", clientKey, item, "")
	if err != nil {
		r.t.Fatal(err)
	}
	if err := r.server.offline.put(grant); err != nil {
		r.t.Fatal(err)
	}
	return grant
}

func (r *subtitleRig) get(path string) *httptest.ResponseRecorder {
	return playbackRequest(r.handler, http.MethodGet, path, "", playbackUserID)
}

func (r *subtitleRig) listPath() string { return subtitleDir + r.grant.ID + "/subtitle-tracks" }

func (r *subtitleRig) list() AppleSubtitleList {
	r.t.Helper()
	response := r.get(r.listPath())
	if response.Code != http.StatusOK {
		r.t.Fatalf("list = %d: %s", response.Code, response.Body.String())
	}
	var list AppleSubtitleList
	if err := json.Unmarshal(response.Body.Bytes(), &list); err != nil {
		r.t.Fatal(err)
	}
	r.noLeak(response.Body.String())
	return list
}

func (r *subtitleRig) track(list AppleSubtitleList, key string) (AppleSubtitleTrack, bool) {
	for _, track := range list.Tracks {
		if track.Key == key {
			return track, true
		}
	}
	return AppleSubtitleTrack{}, false
}

func (r *subtitleRig) mustTrack(list AppleSubtitleList, key string) AppleSubtitleTrack {
	r.t.Helper()
	track, found := r.track(list, key)
	if !found {
		r.t.Fatalf("no track %q in %+v", key, keysOf(list))
	}
	return track
}

func keysOf(list AppleSubtitleList) []string {
	keys := []string{}
	for _, track := range list.Tracks {
		keys = append(keys, track.Key)
	}
	return keys
}

// noLeak fails when a response names a file, a folder or a credential.
func (r *subtitleRig) noLeak(body string) {
	r.t.Helper()
	for _, leak := range []string{r.dir, r.path, "Film (2024)", `"path"`, `"Path"`, "test-key", "ApiKey", "X-Emby"} {
		if strings.Contains(body, leak) {
			r.t.Errorf("a response mentions %q: %s", leak, body)
		}
	}
}

func (r *subtitleRig) errorOf(response *httptest.ResponseRecorder) Error {
	r.t.Helper()
	var body errorBody
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
		r.t.Fatalf("not an error body (%d): %s", response.Code, response.Body.String())
	}
	r.noLeak(response.Body.String())
	return body.Error
}

func TestTheListNamesEveryTextSubtitleWithAKeyASignatureAndWhereItSitsInTheMP4(t *testing.T) {
	rig := newSubtitleRig(t)
	response := rig.get(rig.listPath())
	if response.Code != http.StatusOK || response.Header().Get("Cache-Control") != "no-store" {
		t.Fatalf("list = %d %v", response.Code, response.Header())
	}
	var list AppleSubtitleList
	if err := json.Unmarshal(response.Body.Bytes(), &list); err != nil {
		t.Fatal(err)
	}
	rig.noLeak(response.Body.String())
	if list.GrantID != rig.grant.ID || list.Format != "apple" {
		t.Fatalf("list = %+v", list)
	}
	if got := keysOf(list); !slices.Equal(got, []string{"emb-2", "emb-3", "ext-heb"}) {
		t.Fatalf("keys = %v, want the two embedded text tracks and the Hebrew sidecar, in source order", got)
	}

	english := rig.mustTrack(list, "emb-2")
	if english.SourceIndex != 2 || english.Language != "eng" || english.Title != "Dialogue" || english.Label != "eng - subrip" ||
		english.Codec != "subrip" || english.External || !english.Default || english.Forced || english.HearingImpaired || english.RTL ||
		english.URL != subtitleDir+rig.grant.ID+"/subtitle-tracks/emb-2" || english.MP4Index == nil || *english.MP4Index != 0 {
		t.Errorf("english = %+v", english)
	}
	signs := rig.mustTrack(list, "emb-3")
	if signs.Codec != "ass" || !signs.Forced || signs.Default || signs.MP4Index == nil || *signs.MP4Index != 1 {
		t.Errorf("signs = %+v", signs)
	}
	hebrew := rig.mustTrack(list, "ext-heb")
	if hebrew.SourceIndex != 5 || hebrew.Language != "heb" || !hebrew.External || !hebrew.RTL || hebrew.MP4Index == nil || *hebrew.MP4Index != 2 {
		t.Errorf("hebrew = %+v", hebrew)
	}
	seen := map[string]bool{}
	for _, track := range list.Tracks {
		if !regexp.MustCompile(`^[0-9a-f]{32}$`).MatchString(track.Signature) || seen[track.Signature] {
			t.Errorf("%s has signature %q (a 32-character hex string, different for every track)", track.Key, track.Signature)
		}
		seen[track.Signature] = true
	}

	// The French picture subtitle is left out with its reason and has no key.
	if len(list.Omitted) != 1 {
		t.Fatalf("omitted = %+v", list.Omitted)
	}
	if french := list.Omitted[0]; french.SourceIndex != 4 || french.Language != "fra" || french.Codec != "hdmv_pgs_subtitle" ||
		french.Reason != "picture_subtitle" || french.Key != "" || french.External {
		t.Errorf("french = %+v", french)
	}
	if !strings.Contains(response.Body.String(), `"omitted":[{`) {
		t.Errorf("omitted is not an array: %s", response.Body.String())
	}

	// Only the sidecar was read: an embedded track is signed without being asked for,
	// or a film with 39 subtitle tracks would make Jellyfin extract them all.
	if asked := rig.upstream.askedFor(); !slices.Equal(asked, []string{"5:srt"}) {
		t.Errorf("Jellyfin was asked for %v, want only the sidecar", asked)
	}
}

func TestATrackIsServedAsWebVTTWithItsSignatureAsTheETag(t *testing.T) {
	rig := newSubtitleRig(t)
	list := rig.list()
	for _, track := range list.Tracks {
		response := rig.get(track.URL)
		if response.Code != http.StatusOK {
			t.Fatalf("%s = %d: %s", track.Key, response.Code, response.Body.String())
		}
		header := response.Header()
		if header.Get("Content-Type") != "text/vtt; charset=utf-8" || header.Get("ETag") != `"`+track.Signature+`"` ||
			header.Get("Cache-Control") != "private, no-store" || header.Get("X-Content-Type-Options") != "nosniff" {
			t.Errorf("%s headers = %v", track.Key, header)
		}
		if !strings.HasPrefix(response.Body.String(), "WEBVTT\n\n") {
			t.Errorf("%s body = %q", track.Key, response.Body.String())
		}
		rig.noLeak(response.Body.String())
	}

	// The ASS track is plain text cues, and a SRT's comma is a dot.
	if body := rig.get(subtitleDir + rig.grant.ID + "/subtitle-tracks/emb-3").Body.String(); body != "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nSigns\nand more\n\n" {
		t.Errorf("signs = %q", body)
	}
	if body := rig.get(subtitleDir + rig.grant.ID + "/subtitle-tracks/emb-2").Body.String(); body != "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHello there\n\n" {
		t.Errorf("english = %q", body)
	}

	// An app that holds the signature is told nothing changed, and HEAD has no body.
	hebrew := rig.mustTrack(list, "ext-heb")
	request := httptest.NewRequest(http.MethodGet, hebrew.URL, nil)
	request.Header.Set("Authorization", "Bearer "+libraryTestToken)
	request.Header.Set(jellyfinUserHeader, playbackUserID)
	request.Header.Set("If-None-Match", `"`+hebrew.Signature+`"`)
	unchanged := httptest.NewRecorder()
	rig.handler.ServeHTTP(unchanged, request)
	if unchanged.Code != http.StatusNotModified || unchanged.Body.Len() != 0 {
		t.Errorf("If-None-Match = %d %q", unchanged.Code, unchanged.Body.String())
	}
	head := playbackRequest(rig.handler, http.MethodHead, hebrew.URL, "", playbackUserID)
	if head.Code != http.StatusOK || head.Body.Len() != 0 || head.Header().Get("ETag") != `"`+hebrew.Signature+`"` {
		t.Errorf("HEAD = %d %q %v", head.Code, head.Body.String(), head.Header())
	}
}

func TestAWindows1255HebrewSRTReachesTheAppAsUTF8HebrewInTheOrderItWasWritten(t *testing.T) {
	rig := newSubtitleRig(t)
	// The file on the PC's disk is not UTF-8 at all; the hub has to take what Jellyfin
	// hands over rather than read it, and must not damage it on the way.
	raw := rig.upstream.files[5].raw
	if len(raw) == 0 || bytes.Contains(raw, []byte(hebrewWords)) {
		t.Fatalf("the sidecar should be held in Windows-1255, not UTF-8: %x", raw)
	}
	response := rig.get(subtitleDir + rig.grant.ID + "/subtitle-tracks/ext-heb")
	if response.Code != http.StatusOK {
		t.Fatalf("hebrew = %d: %s", response.Code, response.Body.String())
	}
	want := "WEBVTT\n\n00:00:00.500 --> 00:00:02.000\n" + hebrewWords + "\n\n"
	if response.Body.String() != want {
		t.Fatalf("hebrew = %q, want %q", response.Body.String(), want)
	}
	if !strings.Contains(response.Header().Get("Content-Type"), "utf-8") {
		t.Errorf("Content-Type = %q", response.Header().Get("Content-Type"))
	}
}

func TestAReplacedSidecarKeepsItsKeyAndChangesItsSignatureAndNothingElseMoves(t *testing.T) {
	rig := newSubtitleRig(t)
	before := rig.list()
	rig.upstream.setFile(5, subtitleFile{raw: windows1255("1\n00:00:00,500 --> 00:00:02,000\nשלום עולם, מתוקן\n")})
	after := rig.list()
	if !slices.Equal(keysOf(before), keysOf(after)) {
		t.Fatalf("the keys changed: %v to %v", keysOf(before), keysOf(after))
	}
	for _, key := range []string{"emb-2", "emb-3"} {
		if rig.mustTrack(before, key).Signature != rig.mustTrack(after, key).Signature {
			t.Errorf("%s changed though nothing about it did", key)
		}
	}
	if rig.mustTrack(before, "ext-heb").Signature == rig.mustTrack(after, "ext-heb").Signature {
		t.Fatal("the Hebrew sidecar was replaced and its signature did not change")
	}
	// The same text again signs the same.
	if again := rig.list(); rig.mustTrack(again, "ext-heb").Signature != rig.mustTrack(after, "ext-heb").Signature {
		t.Error("the signature of unchanged text moved")
	}
	// What is served carries the new signature and the new words.
	changed := rig.mustTrack(after, "ext-heb")
	response := rig.get(changed.URL)
	if !strings.Contains(response.Body.String(), "מתוקן") || response.Header().Get("ETag") != `"`+changed.Signature+`"` {
		t.Errorf("served %q with %v", response.Body.String(), response.Header())
	}
}

func TestANewLanguageIsANewKeyThatLeavesTheOthersAloneEvenWhenIndexesMove(t *testing.T) {
	rig := newSubtitleRig(t)
	before := rig.list()

	// Bazarr adds French, and Jellyfin numbers it before the Hebrew one: the Hebrew
	// sidecar is now at index 6.
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
	after := rig.list()

	if got := keysOf(after); !slices.Equal(got, []string{"emb-2", "emb-3", "ext-fra", "ext-heb"}) {
		t.Fatalf("keys = %v", got)
	}
	for _, key := range keysOf(before) {
		if rig.mustTrack(before, key).Signature != rig.mustTrack(after, key).Signature {
			t.Errorf("%s changed because another language arrived", key)
		}
	}
	if moved := rig.mustTrack(after, "ext-heb"); moved.SourceIndex != 6 || moved.MP4Index == nil || *moved.MP4Index != 2 {
		t.Errorf("the Hebrew sidecar = %+v, want index 6 and still the MP4's third subtitle", moved)
	}
	french := rig.mustTrack(after, "ext-fra")
	if french.MP4Index != nil || french.Language != "fra" || french.SourceIndex != 5 {
		t.Errorf("french = %+v: it arrived after the MP4 was made, so it has no place in it", french)
	}
	// The fetch finds the track by its key, wherever Jellyfin has put it.
	if body := rig.get(rig.mustTrack(after, "ext-heb").URL).Body.String(); !strings.Contains(body, hebrewWords) {
		t.Errorf("hebrew = %q", body)
	}
	if body := rig.get(french.URL).Body.String(); !strings.Contains(body, "Bonjour") {
		t.Errorf("french = %q", body)
	}
}

func TestATrackThatDisappearsIsNotListedAndItsKeyIsNotServed(t *testing.T) {
	rig := newSubtitleRig(t)
	rig.upstream.change(func(source map[string]any) {
		streams := source["MediaStreams"].([]any)
		source["MediaStreams"] = streams[:len(streams)-1]
	})
	list := rig.list()
	if got := keysOf(list); !slices.Equal(got, []string{"emb-2", "emb-3"}) {
		t.Fatalf("keys = %v", got)
	}
	if got := rig.get(subtitleDir + rig.grant.ID + "/subtitle-tracks/ext-heb"); got.Code != http.StatusNotFound {
		t.Errorf("a gone track = %d, want 404", got.Code)
	}
}

func TestKeysNameTracksByWhatTheyAreAndNumberTheOnesThatWouldShareAName(t *testing.T) {
	keys := appleSubtitleKeys([]appleKeyInput{
		{Index: 2, External: false, Language: "eng"},
		{Index: 3, External: false, Language: "eng", Forced: true},
		{Index: 6, External: true, Language: "eng"},
		{Index: 7, External: true, Language: "eng", Forced: true},
		{Index: 8, External: true, Language: "eng", HearingImpaired: true},
		{Index: 9, External: true, Language: "eng"},
		{Index: 10, External: true, Language: "heb"},
		{Index: 11, External: true, Language: "fre"},
		{Index: 12, External: true, Language: ""},
		{Index: 13, External: true, Language: "eng"},
	})
	want := []string{"emb-2", "emb-3", "ext-eng", "ext-eng-forced", "ext-eng-sdh", "ext-eng-2", "ext-heb", "ext-fra", "ext-und", "ext-eng-3"}
	if !slices.Equal(keys, want) {
		t.Fatalf("keys = %v\nwant   %v", keys, want)
	}
	for _, key := range keys {
		if !appleSubtitleKeyPattern.MatchString(key) {
			t.Errorf("%q is not a safe key", key)
		}
	}
	// An external track's index plays no part: the same tracks at other indexes are
	// the same keys, which is what lets a file be replaced or a language added.
	shifted := appleSubtitleKeys([]appleKeyInput{{Index: 40, External: true, Language: "heb"}, {Index: 41, External: true, Language: "eng"}})
	if !slices.Equal(shifted, []string{"ext-heb", "ext-eng"}) {
		t.Errorf("shifted = %v", shifted)
	}
}

func TestSeveralSidecarsOfOneLanguageAreListedUnderNumberedKeys(t *testing.T) {
	rig := newSubtitleRig(t)
	rig.upstream.addStream(subStream(6, "subrip", "heb", "", "external", "forced"), subtitleFile{text: "1\n00:00:01,000 --> 00:00:02,000\nמוכרח\n"})
	rig.upstream.addStream(subStream(7, "subrip", "heb", "", "external", "hi"), subtitleFile{text: "1\n00:00:01,000 --> 00:00:02,000\nלכבדי שמיעה\n"})
	rig.upstream.addStream(subStream(8, "subrip", "heb", "Second", "external"), subtitleFile{text: "1\n00:00:01,000 --> 00:00:02,000\nשני\n"})
	list := rig.list()
	want := []string{"emb-2", "emb-3", "ext-heb", "ext-heb-forced", "ext-heb-sdh", "ext-heb-2"}
	if got := keysOf(list); !slices.Equal(got, want) {
		t.Fatalf("keys = %v, want %v", got, want)
	}
	if second := rig.mustTrack(list, "ext-heb-2"); second.Title != "Second" || second.MP4Index != nil {
		t.Errorf("second = %+v", second)
	}
}

func TestEachFormatIsAskedOfJellyfinAsItIsAndTheOthersAsSRT(t *testing.T) {
	rig := newSubtitleRig(t)
	rig.upstream.addStream(subStream(6, "ass", "eng", "Styled", "external"), subtitleFile{text: assEnglish})
	rig.upstream.addStream(subStream(7, "webvtt", "spa", "", "external"), subtitleFile{text: vttEnglish})
	rig.upstream.addStream(subStream(8, "mov_text", "ita", "", "external"), subtitleFile{text: srtEnglish})
	rig.upstream.addStream(subStream(9, "ssa", "deu", "", "external"), subtitleFile{text: assEnglish})
	list := rig.list()
	if len(list.Tracks) != 7 {
		t.Fatalf("keys = %v", keysOf(list))
	}
	asked := rig.upstream.askedFor()
	slices.Sort(asked)
	if want := []string{"5:srt", "6:ass", "7:vtt", "8:srt", "9:ass"}; !slices.Equal(asked, want) {
		t.Errorf("Jellyfin was asked for %v, want %v", asked, want)
	}
	if body := rig.get(rig.mustTrack(list, "ext-spa").URL).Body.String(); body != vttEnglish {
		t.Errorf("an existing WebVTT is passed through, got %q", body)
	}
	if body := rig.get(rig.mustTrack(list, "ext-eng").URL).Body.String(); body != "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nSigns\nand more\n\n" {
		t.Errorf("an ASS sidecar = %q", body)
	}
}

func TestAJellyfinThatWillNotGiveATrackInItsOwnFormatIsAskedForSRT(t *testing.T) {
	rig := newSubtitleRig(t)
	rig.upstream.addStream(subStream(6, "ass", "eng", "Styled", "external"), subtitleFile{text: srtEnglish, refuses: []string{"ass"}})
	rig.upstream.addStream(subStream(7, "webvtt", "spa", "", "external"), subtitleFile{text: srtEnglish, refuses: []string{"vtt", "srt"}})
	list := rig.list()
	asked := rig.upstream.askedFor()
	slices.Sort(asked)
	if want := []string{"5:srt", "6:ass", "6:srt", "7:srt", "7:vtt"}; !slices.Equal(asked, want) {
		t.Errorf("Jellyfin was asked for %v, want %v", asked, want)
	}
	if got := keysOf(list); !slices.Equal(got, []string{"emb-2", "emb-3", "ext-heb", "ext-eng"}) {
		t.Errorf("keys = %v: the ASS sidecar should be listed from its SRT, and the one Jellyfin refuses in both formats should not", got)
	}
	if body := rig.get(rig.mustTrack(list, "ext-eng").URL).Body.String(); !strings.Contains(body, "Hello there") {
		t.Errorf("styled = %q", body)
	}
	var refused *AppleSubtitleOmitted
	for at := range list.Omitted {
		if list.Omitted[at].SourceIndex == 7 {
			refused = &list.Omitted[at]
		}
	}
	if refused == nil || refused.Key != "ext-spa" || refused.Reason != "unreadable" {
		t.Errorf("omitted = %+v", list.Omitted)
	}
}

func TestAnUnsupportedTextFormatAndAPictureAreOmittedWithTheirReasonsAndCannotBeFetched(t *testing.T) {
	rig := newSubtitleRig(t)
	rig.upstream.addStream(subStream(6, "dvdsub", "ger", "", "external"), subtitleFile{})
	rig.upstream.addStream(subStream(7, "mystery_format", "jpn", ""), subtitleFile{})
	list := rig.list()
	reasons := map[int]string{}
	for _, omitted := range list.Omitted {
		reasons[omitted.SourceIndex] = omitted.Reason
		if omitted.Key != "" {
			t.Errorf("%+v has a key though nothing can be fetched", omitted)
		}
	}
	if reasons[4] != "picture_subtitle" || reasons[6] != "picture_subtitle" || reasons[7] != "unsupported_format" || len(reasons) != 3 {
		t.Errorf("omitted = %+v", list.Omitted)
	}
	for _, key := range []string{"emb-4", "ext-ger", "emb-7"} {
		if got := rig.get(subtitleDir + rig.grant.ID + "/subtitle-tracks/" + key); got.Code != http.StatusNotFound {
			t.Errorf("%s = %d, want 404", key, got.Code)
		}
	}
	if asked := rig.upstream.askedFor(); slices.Contains(asked, "6:srt") || slices.Contains(asked, "4:srt") {
		t.Errorf("Jellyfin was asked for a picture subtitle: %v", asked)
	}
}

func TestASidecarJellyfinCannotHandOverIsOmittedWithItsKeyAndTheRestIsStillListed(t *testing.T) {
	for name, file := range map[string]subtitleFile{
		"the file is gone":         {status: http.StatusNotFound},
		"jellyfin refuses it":      {status: http.StatusUnprocessableEntity},
		"it is not subtitles":      {text: "Dear diary, this is a letter and has no times in it.\n"},
		"it is over eight million": {text: strings.Repeat("x", 8<<20+10)},
	} {
		t.Run(name, func(t *testing.T) {
			rig := newSubtitleRig(t)
			rig.upstream.setFile(5, file)
			list := rig.list()
			if got := keysOf(list); !slices.Equal(got, []string{"emb-2", "emb-3"}) {
				t.Fatalf("keys = %v", got)
			}
			var hebrew *AppleSubtitleOmitted
			for at := range list.Omitted {
				if list.Omitted[at].SourceIndex == 5 {
					hebrew = &list.Omitted[at]
				}
			}
			// The key stays in `omitted`: the track has not gone, so the app must keep
			// the file it already has rather than delete it.
			if hebrew == nil || hebrew.Key != "ext-heb" || hebrew.Reason != "unreadable" || hebrew.Language != "heb" || !hebrew.External {
				t.Fatalf("omitted = %+v", list.Omitted)
			}
			response := rig.get(subtitleDir + rig.grant.ID + "/subtitle-tracks/ext-heb")
			if failure := rig.errorOf(response); response.Code != http.StatusUnprocessableEntity || failure.Code != "subtitle_unreadable" || failure.Retryable {
				t.Errorf("the track = %d %+v", response.Code, failure)
			}
		})
	}
}

func TestAJellyfinThatCannotAnswerFailsTheWholeListSoNoGoodFileIsDeleted(t *testing.T) {
	for name, status := range map[string]int{"it fails": http.StatusInternalServerError, "it is unavailable": http.StatusServiceUnavailable,
		"it turns the hub's credential away": http.StatusUnauthorized, "it is too busy": http.StatusTooManyRequests} {
		t.Run(name, func(t *testing.T) {
			rig := newSubtitleRig(t)
			rig.upstream.setFile(5, subtitleFile{status: status})
			response := rig.get(rig.listPath())
			// Leaving the track out would tell the app it had gone, and the app would
			// delete a good file because Jellyfin had a bad moment.
			if failure := rig.errorOf(response); response.Code != http.StatusBadGateway || failure.Code != CodeUpstreamDown || !failure.Retryable {
				t.Fatalf("list = %d %+v: a track that could not be asked about must not be reported as gone", response.Code, failure)
			}
			// Asked again once Jellyfin is back, the whole list is there.
			rig.upstream.setFile(5, subtitleFile{text: srtEnglish})
			if got := keysOf(rig.list()); !slices.Equal(got, []string{"emb-2", "emb-3", "ext-heb"}) {
				t.Errorf("keys = %v", got)
			}
		})
	}
}

func TestJellyfinNotAnsweringAtAllIsARetryableBadGateway(t *testing.T) {
	rig := newSubtitleRig(t)
	rig.upstream.server.Close()
	response := rig.get(rig.listPath())
	if failure := rig.errorOf(response); (response.Code != http.StatusBadGateway && response.Code != http.StatusServiceUnavailable) ||
		!failure.Retryable || failure.Code != CodeUpstreamDown {
		t.Errorf("list = %d %+v", response.Code, failure)
	}
	response = rig.get(subtitleDir + rig.grant.ID + "/subtitle-tracks/emb-2")
	if failure := rig.errorOf(response); (response.Code != http.StatusBadGateway && response.Code != http.StatusServiceUnavailable) || !failure.Retryable {
		t.Errorf("track = %d %+v", response.Code, failure)
	}
}

func TestSubtitlesOfAVideoThatIsNotTheDownloadedOneAreNotOffered(t *testing.T) {
	cases := map[string]struct {
		change func(*subtitleRig)
		reason string
	}{
		"the file is another size": {func(r *subtitleRig) {
			r.upstream.change(func(source map[string]any) { source["Size"] = 999 })
		}, "source_differs"},
		"the source is not there": {func(r *subtitleRig) {
			r.upstream.change(func(source map[string]any) { source["Id"] = "another-version" })
		}, "source_missing"},
		"the item is not there": {func(r *subtitleRig) {
			r.upstream.mu.Lock()
			r.upstream.itemGone = true
			r.upstream.mu.Unlock()
		}, "source_missing"},
		"it has no picture": {func(r *subtitleRig) {
			r.upstream.change(func(source map[string]any) {
				source["MediaStreams"] = source["MediaStreams"].([]any)[1:]
			})
		}, "source_differs"},
	}
	for name, test := range cases {
		t.Run(name, func(t *testing.T) {
			rig := newSubtitleRig(t)
			test.change(rig)
			for _, path := range []string{rig.listPath(), rig.listPath() + "/emb-2"} {
				response := rig.get(path)
				failure := rig.errorOf(response)
				if response.Code != http.StatusConflict || failure.Code != "source_changed" || failure.Reason != test.reason || failure.Retryable {
					t.Errorf("%s = %d %+v", path, response.Code, failure)
				}
			}
			if asked := rig.upstream.askedFor(); len(asked) != 0 {
				t.Errorf("subtitles were fetched for another video: %v", asked)
			}
		})
	}
}

func TestTheSubtitleRoutesAreScopedLikeEveryOfflineGrant(t *testing.T) {
	rig := newSubtitleRig(t)
	with := func(token, user, path string) *httptest.ResponseRecorder {
		recorder := httptest.NewRecorder()
		request := httptest.NewRequest(http.MethodGet, path, nil)
		request.Header.Set("Authorization", "Bearer "+token)
		request.Header.Set(jellyfinUserHeader, user)
		rig.handler.ServeHTTP(recorder, request)
		return recorder
	}
	paths := []string{rig.listPath(), rig.listPath() + "/emb-2", rig.listPath() + "/ext-heb"}
	for _, path := range paths {
		if got := with(libraryTestToken, offlineOtherUser, path); got.Code != http.StatusNotFound {
			t.Errorf("%s as another Jellyfin user = %d, want 404", path, got.Code)
		}
		if got := with("a-different-strong-token-with-more-than-32-characters", playbackUserID, path); got.Code != http.StatusNotFound {
			t.Errorf("%s as another token = %d, want 404", path, got.Code)
		}
		if got := with("a-read-only-strong-token-with-more-than-32-characters", playbackUserID, path); got.Code != http.StatusForbidden {
			t.Errorf("%s without the download scope = %d, want 403", path, got.Code)
		}
		if got := with("not-a-token", playbackUserID, path); got.Code != http.StatusUnauthorized {
			t.Errorf("%s with no valid token = %d, want 401", path, got.Code)
		}
	}
	for _, path := range []string{
		subtitleDir + "00000000000000000000000000000000/subtitle-tracks",
		subtitleDir + "00000000000000000000000000000000/subtitle-tracks/emb-2",
		subtitleDir + "not-a-grant/subtitle-tracks",
	} {
		if got := rig.get(path); got.Code != http.StatusNotFound {
			t.Errorf("%s = %d, want 404 for a grant that does not exist", path, got.Code)
		}
	}
	for _, key := range []string{"EMB-2", "emb_2", "emb-2%2F..", strings.Repeat("a", 49), "emb-9"} {
		if got := rig.get(rig.listPath() + "/" + key); got.Code != http.StatusNotFound {
			t.Errorf("key %q = %d, want 404", key, got.Code)
		}
	}
	if asked := rig.upstream.askedFor(); len(asked) != 0 {
		t.Errorf("a refused request still asked Jellyfin for subtitles: %v", asked)
	}
	// Nothing above changed what the grant lists.
	if got := keysOf(rig.list()); !slices.Equal(got, []string{"emb-2", "emb-3", "ext-heb"}) {
		t.Errorf("keys = %v", got)
	}
}

func TestAnExpiredGrantNoLongerListsOrServesItsSubtitles(t *testing.T) {
	rig := newSubtitleRig(t)
	grant, _ := rig.server.offline.get(rig.grant.ID)
	grant.ExpiresAt = time.Now().Add(-time.Minute).UnixMilli()
	grant.Manifest.ExpiresAt = grant.ExpiresAt
	if err := rig.server.offline.put(grant); err != nil {
		t.Fatal(err)
	}
	for _, path := range []string{rig.listPath(), rig.listPath() + "/emb-2", rig.listPath() + "/ext-heb"} {
		response := rig.get(path)
		if failure := rig.errorOf(response); response.Code != http.StatusGone || failure.Code != "grant_expired" {
			t.Errorf("%s = %d %+v, want 410 grant_expired", path, response.Code, failure)
		}
	}
	if asked := rig.upstream.askedFor(); len(asked) != 0 {
		t.Errorf("an expired grant still asked Jellyfin for subtitles: %v", asked)
	}
}

func TestAGrantThatTheHubNoLongerHoldsListsNothing(t *testing.T) {
	rig := newSubtitleRig(t)
	// A hub whose registry has lost the grant (a different file, as after the grants
	// were cleared) answers as it does for a grant that never existed.
	cfg := offlineConfig(rig.upstream.server.URL, filepath.Join(rig.dir, "another-registry.json"))
	fresh := NewServer(cfg).Handler()
	response := playbackRequest(fresh, http.MethodGet, rig.listPath(), "", playbackUserID)
	if response.Code != http.StatusNotFound {
		t.Errorf("list = %d, want 404", response.Code)
	}
	response = playbackRequest(fresh, http.MethodGet, rig.listPath()+"/emb-2", "", playbackUserID)
	if response.Code != http.StatusNotFound {
		t.Errorf("track = %d, want 404", response.Code)
	}
}

func TestAnOriginalDownloadHasNoSubtitleListToRefresh(t *testing.T) {
	rig := newSubtitleRig(t)
	original := rig.makeGrant("", "movie-original")
	for _, path := range []string{subtitleDir + original.ID + "/subtitle-tracks", subtitleDir + original.ID + "/subtitle-tracks/emb-2"} {
		if got := rig.get(path); got.Code != http.StatusNotFound {
			t.Errorf("%s = %d, want 404", path, got.Code)
		}
	}
}

func TestTheListWorksAfterTheAppReleasedTheMP4AndTheOldRoutesAreAsTheyWere(t *testing.T) {
	rig := newSubtitleRig(t)
	if got := rig.get(subtitleDir + rig.grant.ID + "/subtitles/5"); got.Code != http.StatusNotFound {
		t.Errorf("the original subtitle route for an Apple grant = %d, want the 404 it always was", got.Code)
	}
	release := playbackRequest(rig.handler, http.MethodDelete, subtitleDir+rig.grant.ID+"/media", "", playbackUserID)
	if release.Code != http.StatusNoContent {
		t.Fatalf("release = %d", release.Code)
	}
	if got := keysOf(rig.list()); !slices.Equal(got, []string{"emb-2", "emb-3", "ext-heb"}) {
		t.Errorf("after the MP4 was released the keys are %v", got)
	}
	// The manifest an older app reads is what it was: no new field, the sidecar list empty.
	body, err := json.Marshal(rig.grant.Manifest)
	if err != nil {
		t.Fatal(err)
	}
	for _, news := range []string{"subtitle-tracks", "signature", "mp4Index", "rtl"} {
		if strings.Contains(string(body), news) {
			t.Errorf("the manifest mentions %q: %s", news, body)
		}
	}
	if !strings.Contains(string(body), `"subtitles":[]`) || !strings.Contains(string(body), `"format":"apple"`) {
		t.Errorf("manifest = %s", body)
	}
}

func TestTooManySidecarsAreCappedAndReadFourAtATime(t *testing.T) {
	rig := newSubtitleRig(t)
	rig.upstream.delay = 15 * time.Millisecond
	languages := []string{}
	for _, first := range "abcdefghijklmnopqrstuvwxyz" {
		languages = append(languages, "q"+string(first)+"z")
	}
	for at, language := range languages {
		rig.upstream.addStream(subStream(10+at, "subrip", language, "", "external"), subtitleFile{text: srtEnglish})
	}
	list := rig.list()
	externals, tooMany := 0, 0
	for _, track := range list.Tracks {
		if track.External {
			externals++
		}
	}
	for _, omitted := range list.Omitted {
		if omitted.Reason == "too_many" {
			tooMany++
			if omitted.Key != "" {
				t.Errorf("%+v has a key", omitted)
			}
		}
	}
	// 27 sidecars exist (the Hebrew one and 26 more): 24 are listed.
	if externals != 24 || tooMany != 3 {
		t.Errorf("%d external tracks listed and %d left out as too many, want 24 and 3", externals, tooMany)
	}
	rig.upstream.mu.Lock()
	peak := rig.upstream.peak
	rig.upstream.mu.Unlock()
	if peak > 4 {
		t.Errorf("Jellyfin was asked %d at a time, want at most 4", peak)
	}
}

func TestTheLogNamesNoPathAndNoCredentialWhenSubtitlesFail(t *testing.T) {
	var logged bytes.Buffer
	previous := slog.Default()
	slog.SetDefault(slog.New(slog.NewTextHandler(&logged, &slog.HandlerOptions{Level: slog.LevelDebug})))
	t.Cleanup(func() { slog.SetDefault(previous) })

	rig := newSubtitleRig(t)
	rig.upstream.setFile(5, subtitleFile{status: http.StatusNotFound})
	rig.list()
	rig.get(subtitleDir + rig.grant.ID + "/subtitle-tracks/ext-heb")
	rig.upstream.setFile(5, subtitleFile{status: http.StatusInternalServerError})
	rig.get(rig.listPath())
	rig.get(subtitleDir + rig.grant.ID + "/subtitle-tracks/ext-heb")
	if logged.Len() == 0 {
		t.Fatal("nothing was logged for a subtitle that failed")
	}
	for _, leak := range []string{rig.dir, rig.path, "Film (2024)", "test-key"} {
		if strings.Contains(logged.String(), leak) {
			t.Errorf("the log mentions %q:\n%s", leak, logged.String())
		}
	}
}

func TestATrackNameThatIsAPathIsNotShown(t *testing.T) {
	rig := newSubtitleRig(t)
	rig.upstream.addStream(subStream(6, "subrip", "eng", rig.path, "external"), subtitleFile{text: srtEnglish})
	rig.upstream.addStream(subStream(7, "subrip", "spa", "Signs/Songs\n  and   more", "external"), subtitleFile{text: srtEnglish})
	rig.upstream.addStream(subStream(8, "subrip", "ita", strings.Repeat("long ", 80), "external"), subtitleFile{text: srtEnglish})
	list := rig.list() // list() fails a response that names the path or its folder
	if title := rig.mustTrack(list, "ext-eng").Title; title != "" {
		t.Errorf("a path was shown as a title: %q", title)
	}
	if title := rig.mustTrack(list, "ext-spa").Title; title != "Signs/Songs and more" {
		t.Errorf("title = %q", title)
	}
	if title := rig.mustTrack(list, "ext-ita").Title; len([]rune(title)) != 120 {
		t.Errorf("a long title has %d characters, want it cut to 120", len([]rune(title)))
	}
}

func TestTheHubKeepsNothingBetweenTwoLists(t *testing.T) {
	// A replaced file is seen by the next list, not after a delay.
	rig := newSubtitleRig(t)
	rig.list()
	rig.list()
	if asked := rig.upstream.askedFor(); !slices.Equal(asked, []string{"5:srt", "5:srt"}) {
		t.Errorf("two lists read the sidecar as %v, want it twice", asked)
	}
}

func TestAnEmbeddedSignatureIsBoundToTheSourceTheTrackAndTheConvertersVersion(t *testing.T) {
	rig := newSubtitleRig(t)
	var stream jellyfin.MediaStream
	data, _ := json.Marshal(subStream(2, "subrip", "eng", ""))
	if err := json.Unmarshal(data, &stream); err != nil {
		t.Fatal(err)
	}
	base := embeddedSubtitleSignature(rig.grant, stream)
	if again := embeddedSubtitleSignature(rig.grant, stream); again != base {
		t.Error("the same track signs differently twice")
	}
	otherSource := rig.grant
	otherSource.MediaSourceID = "another"
	otherSize := rig.grant
	otherSize.Manifest.Source.SizeBytes++
	otherTrack := stream
	otherTrack.Index++
	otherLanguage := stream
	otherLanguage.Language = "fre"
	for name, other := range map[string]string{
		"source": embeddedSubtitleSignature(otherSource, stream), "size": embeddedSubtitleSignature(otherSize, stream),
		"index": embeddedSubtitleSignature(rig.grant, otherTrack), "language": embeddedSubtitleSignature(rig.grant, otherLanguage),
	} {
		if other == base {
			t.Errorf("the signature ignores the %s", name)
		}
	}
	// The converter's version is in it, so a change to what a file turns into means
	// every app fetches the track again: pin that the version is what is hashed.
	text := fmt.Sprintf("embedded|webvtt%d|%s|%d|%d|%s|%s", webvtt.Version, rig.grant.MediaSourceID, rig.grant.Manifest.Source.SizeBytes, 2, "subrip", "eng")
	if signatureOf(text) != base {
		t.Error("the signature is not the one the contract describes")
	}
}

// The whole path on a real file: the MP4 the hub made, the subtitle options inside
// it, and the list that was made from the same plan.
func TestARealAppleDownloadsSubtitleListMatchesItsMP4AndSurvivesTheRelease(t *testing.T) {
	rig := newAppleRig(t, nil)
	manifest, ready := rig.ready(t)

	served := rig.request(http.MethodGet, manifest.MediaURL, "")
	if served.Code != http.StatusOK || int64(served.Body.Len()) != ready.SizeBytes {
		t.Fatalf("media = %d", served.Code)
	}
	out := filepath.Join(t.TempDir(), "served.mp4")
	if err := os.WriteFile(out, served.Body.Bytes(), 0o600); err != nil {
		t.Fatal(err)
	}
	var inFile []string
	for _, stream := range probeMP4(t, out) {
		if stream.CodecType == "subtitle" {
			inFile = append(inFile, stream.Language)
		}
	}

	if release := rig.request(http.MethodDelete, manifest.MediaURL, ""); release.Code != http.StatusNoContent {
		t.Fatalf("release = %d", release.Code)
	}
	response := rig.request(http.MethodGet, subtitleDir+manifest.GrantID+"/subtitle-tracks", "")
	if response.Code != http.StatusOK {
		t.Fatalf("list after the release = %d: %s", response.Code, response.Body.String())
	}
	var list AppleSubtitleList
	if err := json.Unmarshal(response.Body.Bytes(), &list); err != nil {
		t.Fatal(err)
	}
	if got := keysOf(list); !slices.Equal(got, []string{"emb-3", "ext-heb"}) {
		t.Fatalf("keys = %v", got)
	}
	byPlace := make([]string, len(inFile))
	for _, track := range list.Tracks {
		if track.MP4Index == nil || *track.MP4Index >= len(byPlace) {
			t.Fatalf("%+v has no place in the MP4's %v", track, inFile)
		}
		byPlace[*track.MP4Index] = track.Language
	}
	if !slices.Equal(byPlace, inFile) || !slices.Equal(inFile, []string{"eng", "heb"}) {
		t.Errorf("the list says the MP4's subtitles are %v, the file holds %v", byPlace, inFile)
	}
	if len(list.Omitted) != 1 || list.Omitted[0].Language != "fra" || list.Omitted[0].Reason != "picture_subtitle" {
		t.Errorf("omitted = %+v", list.Omitted)
	}
	hebrew := rig.request(http.MethodGet, subtitleDir+manifest.GrantID+"/subtitle-tracks/ext-heb", "")
	if hebrew.Code != http.StatusOK || !strings.Contains(hebrew.Body.String(), "שלום עולם") ||
		hebrew.Header().Get("ETag") != `"`+list.Tracks[1].Signature+`"` {
		t.Errorf("hebrew = %d %q %v", hebrew.Code, hebrew.Body.String(), hebrew.Header())
	}
}
