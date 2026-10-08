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

// An Apple download's kept subtitles (#45). These tests need no ffmpeg except where
// they say so: a grant is made the way a prepare makes it, over a small file on disk,
// and the fake Jellyfin holds the subtitle files and says what it was asked for.
//
// The fake numbers streams as Jellyfin 10.11 does: the sidecar subtitles come first
// (0, 1, …) and the file's own streams follow, so adding a sidecar moves every stream
// of the file up by one. (Measured on a Drake & Josh episode: subtitle 0 and 1 are
// the .srt files beside it, video 2, audio 3, where the MP4 holds the video at 0.)

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

// fakeStream is one stream of the film. Its name is what a test calls it and what
// the fake records when Jellyfin is asked for its text.
type fakeStream struct {
	name string
	info map[string]any // Jellyfin's fields, without the index
	file subtitleFile
}

func filePart(name string, info map[string]any) fakeStream { return fakeStream{name: name, info: info} }

// embedded is a subtitle inside the film's file; sidecar is one beside it.
func embedded(name, codec, language, title string, file subtitleFile, flags ...string) fakeStream {
	info := map[string]any{"Type": "Subtitle", "Codec": codec, "Language": language, "Title": title,
		"DisplayTitle": strings.TrimSpace(language + " - " + codec)}
	for _, flag := range flags {
		switch flag {
		case "external":
			info["IsExternal"] = true
		case "forced":
			info["IsForced"] = true
		case "default":
			info["IsDefault"] = true
		case "hi":
			info["IsHearingImpaired"] = true
		}
	}
	return fakeStream{name: name, info: info, file: file}
}

func sidecar(name, codec, language, title string, file subtitleFile, flags ...string) fakeStream {
	return embedded(name, codec, language, title, file, append([]string{"external"}, flags...)...)
}

const srtEnglish = "1\n00:00:01,000 --> 00:00:02,000\nHello there\n"

const assEnglish = "[Script Info]\nScriptType: v4.00+\n\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
	"Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,{\\an8}Signs\\Nand more\n"

const vttEnglish = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nAlready WebVTT\n"

func hebrewFile() subtitleFile {
	return subtitleFile{raw: windows1255("1\n00:00:00,500 --> 00:00:02,000\n" + hebrewWords + "\n")}
}

type subtitleUpstream struct {
	t      *testing.T
	server *httptest.Server

	mu     sync.Mutex
	source map[string]any
	// sidecars are the subtitle files beside the film; inside are the streams of the
	// file itself. Jellyfin 10.11 numbers the sidecars first; sidecarsLast is the
	// older numbering.
	sidecars     []fakeStream
	inside       []fakeStream
	sidecarsLast bool
	asked        []string // "name:format", in order
	itemGone     bool
	delay        time.Duration
	inFlight     int
	peak         int
}

// newSubtitleUpstream is a Jellyfin whose film has a Hebrew SRT beside it (which holds
// Windows-1255 bytes on its disk) and, inside the file, the video, an AAC track, an
// English SRT, a forced ASS track and a French picture subtitle. Numbered as 10.11
// does, they are: Hebrew 0, video 1, audio 2, English 3, signs 4, French 5. Inside the
// file they are video 0, audio 1, English 2, signs 3, French 4.
func newSubtitleUpstream(t *testing.T, path string, sidecarsLast bool) *subtitleUpstream {
	t.Helper()
	u := &subtitleUpstream{t: t, sidecarsLast: sidecarsLast}
	u.source = map[string]any{
		"Id": offlineSourceID, "Name": "Original 1080p", "Path": path, "Container": "mkv", "Size": 123456,
		"Bitrate": 8_000_000, "RunTimeTicks": int64(3 * 10_000_000),
	}
	u.sidecars = []fakeStream{sidecar("heb", "subrip", "heb", "", hebrewFile())}
	u.inside = []fakeStream{
		filePart("video", map[string]any{"Type": "Video", "Codec": "h264", "Width": 1920, "Height": 1080, "PixelFormat": "yuv420p"}),
		filePart("audio", map[string]any{"Type": "Audio", "Codec": "aac", "Language": "eng", "Channels": 2, "IsDefault": true}),
		embedded("eng", "subrip", "eng", "Dialogue", subtitleFile{text: srtEnglish}, "default"),
		embedded("signs", "ass", "eng", "Signs", subtitleFile{text: assEnglish}, "forced"),
		embedded("fre", "hdmv_pgs_subtitle", "fre", "", subtitleFile{}),
	}
	u.server = httptest.NewServer(http.HandlerFunc(u.serve))
	t.Cleanup(u.server.Close)
	return u
}

// streams is the film's streams in Jellyfin's order. The caller holds the lock.
func (u *subtitleUpstream) streams() []fakeStream {
	if u.sidecarsLast {
		return append(slices.Clone(u.inside), u.sidecars...)
	}
	return append(slices.Clone(u.sidecars), u.inside...)
}

// index is Jellyfin's number for a stream today.
func (u *subtitleUpstream) index(name string) int {
	u.mu.Lock()
	defer u.mu.Unlock()
	for at, stream := range u.streams() {
		if stream.name == name {
			return at
		}
	}
	u.t.Fatalf("the fake film has no stream called %q", name)
	return -1
}

// edit changes the film under the lock; it must not call the locking methods.
func (u *subtitleUpstream) edit(change func(u *subtitleUpstream)) {
	u.mu.Lock()
	defer u.mu.Unlock()
	change(u)
}

func (u *subtitleUpstream) change(edit func(source map[string]any)) {
	u.mu.Lock()
	defer u.mu.Unlock()
	edit(u.source)
}

// addSidecar puts a subtitle file beside the film at a place in the order Jellyfin
// numbers them (negative: after the others).
func (u *subtitleUpstream) addSidecar(at int, stream fakeStream) {
	u.mu.Lock()
	defer u.mu.Unlock()
	if at < 0 || at > len(u.sidecars) {
		at = len(u.sidecars)
	}
	u.sidecars = slices.Insert(u.sidecars, at, stream)
}

func (u *subtitleUpstream) dropSidecar(name string) {
	u.mu.Lock()
	defer u.mu.Unlock()
	u.sidecars = slices.DeleteFunc(u.sidecars, func(stream fakeStream) bool { return stream.name == name })
}

func (u *subtitleUpstream) setFile(name string, file subtitleFile) {
	u.mu.Lock()
	defer u.mu.Unlock()
	for _, list := range [][]fakeStream{u.sidecars, u.inside} {
		for at := range list {
			if list[at].name == name {
				list[at].file = file
				return
			}
		}
	}
	u.t.Fatalf("the fake film has no stream called %q", name)
}

func (u *subtitleUpstream) itemJSON() string {
	u.mu.Lock()
	defer u.mu.Unlock()
	streams := u.streams()
	list := make([]any, 0, len(streams))
	defaultAudio := -1
	for at, stream := range streams {
		info := map[string]any{"Index": at}
		for key, value := range stream.info {
			info[key] = value
		}
		if stream.info["Type"] == "Audio" && stream.info["IsDefault"] == true && defaultAudio < 0 {
			defaultAudio = at
		}
		list = append(list, info)
	}
	source := map[string]any{}
	for key, value := range u.source {
		source[key] = value
	}
	source["MediaStreams"] = list
	if defaultAudio >= 0 {
		source["DefaultAudioStreamIndex"] = defaultAudio
	}
	document := map[string]any{
		"Id": offlineItemID, "Name": "Film", "Type": "Movie", "RunTimeTicks": int64(3 * 10_000_000),
		"MediaSources": []any{source},
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
	streams := u.streams()
	found := index >= 0 && index < len(streams) && streams[index].info["Type"] == "Subtitle"
	var stream fakeStream
	if found {
		stream = streams[index]
		u.asked = append(u.asked, stream.name+":"+match[2])
	} else {
		u.asked = append(u.asked, match[1]+"?:"+match[2])
	}
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
	file := stream.file
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

type subtitleRig struct {
	t        *testing.T
	dir      string
	path     string
	upstream *subtitleUpstream
	server   *Server
	handler  http.Handler
	grant    offlineGrant
}

func newSubtitleRig(t *testing.T) *subtitleRig { return newNumberedSubtitleRig(t, false) }

func newNumberedSubtitleRig(t *testing.T, sidecarsLast bool) *subtitleRig {
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
	rig := &subtitleRig{t: t, dir: dir, path: path, upstream: newSubtitleUpstream(t, path, sidecarsLast)}
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

// omittedAt is the omitted entry for a stream of the fake film.
func (r *subtitleRig) omittedAt(list AppleSubtitleList, name string) *AppleSubtitleOmitted {
	index := r.upstream.index(name)
	for at := range list.Omitted {
		if list.Omitted[at].SourceIndex == index {
			return &list.Omitted[at]
		}
	}
	return nil
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
	// Jellyfin's order: the sidecar is numbered first. The keys are those of the file's
	// own indexes (the English track is the file's stream 2), whatever Jellyfin says.
	if got := keysOf(list); !slices.Equal(got, []string{"ext-heb", "emb-2", "emb-3"}) {
		t.Fatalf("keys = %v, want the Hebrew sidecar and the two embedded text tracks, in Jellyfin's order", got)
	}

	english := rig.mustTrack(list, "emb-2")
	if english.SourceIndex != 3 || english.Language != "eng" || english.Title != "Dialogue" || english.Label != "eng - subrip" ||
		english.Codec != "subrip" || english.External || !english.Default || english.Forced || english.HearingImpaired || english.RTL ||
		english.URL != subtitleDir+rig.grant.ID+"/subtitle-tracks/emb-2" || english.MP4Index == nil || *english.MP4Index != 1 {
		t.Errorf("english = %+v: Jellyfin's index for it is 3 (the sidecar is 0, the video 1, the audio 2), the file's is 2", english)
	}
	signs := rig.mustTrack(list, "emb-3")
	if signs.SourceIndex != 4 || signs.Codec != "ass" || !signs.Forced || signs.Default || signs.MP4Index == nil || *signs.MP4Index != 2 {
		t.Errorf("signs = %+v", signs)
	}
	hebrew := rig.mustTrack(list, "ext-heb")
	if hebrew.SourceIndex != 0 || hebrew.Language != "heb" || !hebrew.External || !hebrew.RTL || hebrew.MP4Index == nil || *hebrew.MP4Index != 0 {
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
	if french := list.Omitted[0]; french.SourceIndex != 5 || french.Language != "fra" || french.Codec != "hdmv_pgs_subtitle" ||
		french.Reason != "picture_subtitle" || french.Key != "" || french.External {
		t.Errorf("french = %+v", french)
	}
	if !strings.Contains(response.Body.String(), `"omitted":[{`) {
		t.Errorf("omitted is not an array: %s", response.Body.String())
	}

	// Only the sidecar was read: an embedded track is signed without being asked for,
	// or a film with 39 subtitle tracks would make Jellyfin extract them all.
	if asked := rig.upstream.askedFor(); !slices.Equal(asked, []string{"heb:srt"}) {
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
	// The embedded track was asked of Jellyfin by Jellyfin's number for it.
	if asked := rig.upstream.askedFor(); !slices.Contains(asked, "eng:srt") || !slices.Contains(asked, "signs:ass") {
		t.Errorf("Jellyfin was asked for %v", asked)
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
	raw := rig.upstream.sidecars[0].file.raw
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
	rig.upstream.setFile("heb", subtitleFile{raw: windows1255("1\n00:00:00,500 --> 00:00:02,000\nשלום עולם, מתוקן\n")})
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

func TestANewLanguageIsANewKeyAndAnEmbeddedTrackKeepsItsKeyAndSignatureWhenJellyfinMovesItsIndex(t *testing.T) {
	rig := newSubtitleRig(t)
	before := rig.list()
	englishWas := rig.mustTrack(before, "emb-2").SourceIndex

	// Bazarr adds French, and Jellyfin numbers it before the Hebrew one: every stream
	// of the file, the video and the audio included, moves up by one.
	rig.upstream.addSidecar(0, sidecar("fra", "subrip", "fra", "", subtitleFile{text: "1\n00:00:01,000 --> 00:00:02,000\nBonjour\n"}))
	after := rig.list()

	if got := keysOf(after); !slices.Equal(got, []string{"ext-fra", "ext-heb", "emb-2", "emb-3"}) {
		t.Fatalf("keys = %v", got)
	}
	for _, key := range keysOf(before) {
		if rig.mustTrack(before, key).Signature != rig.mustTrack(after, key).Signature {
			t.Errorf("%s changed because another language arrived", key)
		}
	}
	english := rig.mustTrack(after, "emb-2")
	if english.SourceIndex != englishWas+1 || english.MP4Index == nil || *english.MP4Index != 1 {
		t.Errorf("english = %+v, want Jellyfin's index %d and still the MP4's second subtitle", english, englishWas+1)
	}
	if moved := rig.mustTrack(after, "ext-heb"); moved.SourceIndex != 1 || moved.MP4Index == nil || *moved.MP4Index != 0 {
		t.Errorf("the Hebrew sidecar = %+v, want index 1 and still the MP4's first subtitle", moved)
	}
	french := rig.mustTrack(after, "ext-fra")
	if french.MP4Index != nil || french.Language != "fra" || french.SourceIndex != 0 {
		t.Errorf("french = %+v: it arrived after the MP4 was made, so it has no place in it", french)
	}
	// The fetch finds the track by its key, wherever Jellyfin has put it.
	if body := rig.get(rig.mustTrack(after, "ext-heb").URL).Body.String(); !strings.Contains(body, hebrewWords) {
		t.Errorf("hebrew = %q", body)
	}
	if body := rig.get(french.URL).Body.String(); !strings.Contains(body, "Bonjour") {
		t.Errorf("french = %q", body)
	}
	if body := rig.get(english.URL).Body.String(); !strings.Contains(body, "Hello there") {
		t.Errorf("english = %q: it is asked of Jellyfin by Jellyfin's number for it, which moved", body)
	}
	asked := rig.upstream.askedFor()
	if asked[len(asked)-1] != "eng:srt" {
		t.Errorf("Jellyfin was last asked for %v", asked)
	}
}

func TestATrackThatDisappearsIsNotListedAndItsKeyIsNotServedAndNothingElseMoves(t *testing.T) {
	rig := newSubtitleRig(t)
	before := rig.list()
	rig.upstream.dropSidecar("heb")
	list := rig.list()
	if got := keysOf(list); !slices.Equal(got, []string{"emb-2", "emb-3"}) {
		t.Fatalf("keys = %v", got)
	}
	for _, key := range keysOf(list) {
		if rig.mustTrack(before, key).Signature != rig.mustTrack(list, key).Signature {
			t.Errorf("%s changed because a sidecar went", key)
		}
	}
	if got := rig.get(subtitleDir + rig.grant.ID + "/subtitle-tracks/ext-heb"); got.Code != http.StatusNotFound {
		t.Errorf("a gone track = %d, want 404", got.Code)
	}
}

func TestAnOlderJellyfinThatNumbersSidecarsLastGivesTheSameKeysSignaturesAndPlaces(t *testing.T) {
	first, last := newSubtitleRig(t), newNumberedSubtitleRig(t, true)
	a, b := first.list(), last.list()
	if len(a.Tracks) != 3 || len(b.Tracks) != 3 {
		t.Fatalf("keys %v and %v", keysOf(a), keysOf(b))
	}
	for _, track := range a.Tracks {
		other := last.mustTrack(b, track.Key)
		if other.Signature != track.Signature || other.Language != track.Language || other.External != track.External {
			t.Errorf("%s differs: %+v and %+v", track.Key, track, other)
		}
	}
	// The MP4 holds its subtitles in the plan's order, which is Jellyfin's, so the
	// places differ between the two numberings; each is a place in a file of three.
	for _, list := range []AppleSubtitleList{a, b} {
		places := []int{}
		for _, track := range list.Tracks {
			if track.MP4Index == nil {
				t.Fatalf("%+v has no place in the MP4", track)
			}
			places = append(places, *track.MP4Index)
		}
		slices.Sort(places)
		if !slices.Equal(places, []int{0, 1, 2}) {
			t.Errorf("places = %v", places)
		}
	}
	// Numbered last, the Hebrew sidecar is stream 5 and the English one is stream 2.
	if hebrew := last.mustTrack(b, "ext-heb"); hebrew.SourceIndex != 5 || last.mustTrack(b, "emb-2").SourceIndex != 2 {
		t.Errorf("hebrew %+v", hebrew)
	}
	// And a sidecar added there moves nothing.
	last.upstream.addSidecar(-1, sidecar("fra", "subrip", "fra", "", subtitleFile{text: srtEnglish}))
	after := last.list()
	for _, key := range []string{"emb-2", "emb-3", "ext-heb"} {
		if last.mustTrack(b, key).Signature != last.mustTrack(after, key).Signature || last.mustTrack(after, key).SourceIndex != last.mustTrack(b, key).SourceIndex {
			t.Errorf("%s moved though the sidecar was added last", key)
		}
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

func TestSourceFileIndexesCountOnlyTheSidecarsNumberedBeforeAStream(t *testing.T) {
	tracks := []PlaybackTrack{
		{Index: 0, External: true}, {Index: 1, External: true}, {Index: 2}, {Index: 3}, {Index: 4}, {Index: 5, External: true},
	}
	got := sourceFileIndexes(tracks)
	if got[2] != 0 || got[3] != 1 || got[4] != 2 || len(got) != 3 {
		t.Errorf("file indexes = %v, want Jellyfin's 2, 3, 4 at 0, 1, 2 (the sidecars are not in the file)", got)
	}
	if inFile(got, 3) != 1 || inFile(got, 99) != 99 {
		t.Errorf("inFile = %d and %d", inFile(got, 3), inFile(got, 99))
	}
}

func TestSeveralSidecarsOfOneLanguageAreListedUnderNumberedKeys(t *testing.T) {
	rig := newSubtitleRig(t)
	rig.upstream.addSidecar(-1, sidecar("forced", "subrip", "heb", "", subtitleFile{text: "1\n00:00:01,000 --> 00:00:02,000\nמוכרח\n"}, "forced"))
	rig.upstream.addSidecar(-1, sidecar("hi", "subrip", "heb", "", subtitleFile{text: "1\n00:00:01,000 --> 00:00:02,000\nלכבדי שמיעה\n"}, "hi"))
	rig.upstream.addSidecar(-1, sidecar("second", "subrip", "heb", "Second", subtitleFile{text: "1\n00:00:01,000 --> 00:00:02,000\nשני\n"}))
	list := rig.list()
	want := []string{"ext-heb", "ext-heb-forced", "ext-heb-sdh", "ext-heb-2", "emb-2", "emb-3"}
	if got := keysOf(list); !slices.Equal(got, want) {
		t.Fatalf("keys = %v, want %v", got, want)
	}
	if second := rig.mustTrack(list, "ext-heb-2"); second.Title != "Second" || second.MP4Index != nil {
		t.Errorf("second = %+v", second)
	}
}

func TestEachFormatIsAskedOfJellyfinAsItIsAndTheOthersAsSRT(t *testing.T) {
	rig := newSubtitleRig(t)
	rig.upstream.addSidecar(-1, sidecar("styled", "ass", "eng", "Styled", subtitleFile{text: assEnglish}))
	rig.upstream.addSidecar(-1, sidecar("spa", "webvtt", "spa", "", subtitleFile{text: vttEnglish}))
	rig.upstream.addSidecar(-1, sidecar("ita", "mov_text", "ita", "", subtitleFile{text: srtEnglish}))
	rig.upstream.addSidecar(-1, sidecar("deu", "ssa", "deu", "", subtitleFile{text: assEnglish}))
	list := rig.list()
	if len(list.Tracks) != 7 {
		t.Fatalf("keys = %v", keysOf(list))
	}
	asked := rig.upstream.askedFor()
	slices.Sort(asked)
	if want := []string{"deu:ass", "heb:srt", "ita:srt", "spa:vtt", "styled:ass"}; !slices.Equal(asked, want) {
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
	rig.upstream.addSidecar(-1, sidecar("styled", "ass", "eng", "Styled", subtitleFile{text: srtEnglish, refuses: []string{"ass"}}))
	rig.upstream.addSidecar(-1, sidecar("spa", "webvtt", "spa", "", subtitleFile{text: srtEnglish, refuses: []string{"vtt", "srt"}}))
	list := rig.list()
	asked := rig.upstream.askedFor()
	slices.Sort(asked)
	if want := []string{"heb:srt", "spa:srt", "spa:vtt", "styled:ass", "styled:srt"}; !slices.Equal(asked, want) {
		t.Errorf("Jellyfin was asked for %v, want %v", asked, want)
	}
	if got := keysOf(list); !slices.Equal(got, []string{"ext-heb", "ext-eng", "emb-2", "emb-3"}) {
		t.Errorf("keys = %v: the ASS sidecar should be listed from its SRT, and the one Jellyfin refuses in both formats should not", got)
	}
	if body := rig.get(rig.mustTrack(list, "ext-eng").URL).Body.String(); !strings.Contains(body, "Hello there") {
		t.Errorf("styled = %q", body)
	}
	if refused := rig.omittedAt(list, "spa"); refused == nil || refused.Key != "ext-spa" || refused.Reason != "unreadable" {
		t.Errorf("omitted = %+v", list.Omitted)
	}
}

func TestAnUnsupportedTextFormatAndAPictureAreOmittedWithTheirReasonsAndCannotBeFetched(t *testing.T) {
	rig := newSubtitleRig(t)
	rig.upstream.addSidecar(-1, sidecar("ger", "dvdsub", "ger", "", subtitleFile{}))
	rig.upstream.edit(func(u *subtitleUpstream) {
		u.inside = append(u.inside, embedded("jpn", "mystery_format", "jpn", "", subtitleFile{}))
	})
	list := rig.list()
	reasons := map[string]string{}
	for _, name := range []string{"fre", "ger", "jpn"} {
		omitted := rig.omittedAt(list, name)
		if omitted == nil {
			t.Fatalf("%s is not omitted: %+v", name, list.Omitted)
		}
		reasons[name] = omitted.Reason
		if omitted.Key != "" {
			t.Errorf("%+v has a key though nothing can be fetched", omitted)
		}
	}
	if reasons["fre"] != "picture_subtitle" || reasons["ger"] != "picture_subtitle" || reasons["jpn"] != "unsupported_format" || len(list.Omitted) != 3 {
		t.Errorf("omitted = %+v", list.Omitted)
	}
	// The picture inside the file is the file's stream 4, the unsupported one stream 5.
	for _, key := range []string{"emb-4", "ext-ger", "emb-5"} {
		if got := rig.get(subtitleDir + rig.grant.ID + "/subtitle-tracks/" + key); got.Code != http.StatusNotFound {
			t.Errorf("%s = %d, want 404", key, got.Code)
		}
	}
	if asked := rig.upstream.askedFor(); slices.Contains(asked, "ger:srt") || slices.Contains(asked, "fre:srt") {
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
			rig.upstream.setFile("heb", file)
			list := rig.list()
			if got := keysOf(list); !slices.Equal(got, []string{"emb-2", "emb-3"}) {
				t.Fatalf("keys = %v", got)
			}
			// The key stays in `omitted`: the track has not gone, so the app must keep
			// the file it already has rather than delete it.
			hebrew := rig.omittedAt(list, "heb")
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
			rig.upstream.setFile("heb", subtitleFile{status: status})
			response := rig.get(rig.listPath())
			// Leaving the track out would tell the app it had gone, and the app would
			// delete a good file because Jellyfin had a bad moment.
			if failure := rig.errorOf(response); response.Code != http.StatusBadGateway || failure.Code != CodeUpstreamDown || !failure.Retryable {
				t.Fatalf("list = %d %+v: a track that could not be asked about must not be reported as gone", response.Code, failure)
			}
			// Asked again once Jellyfin is back, the whole list is there.
			rig.upstream.setFile("heb", subtitleFile{text: srtEnglish})
			if got := keysOf(rig.list()); !slices.Equal(got, []string{"ext-heb", "emb-2", "emb-3"}) {
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
			r.upstream.edit(func(u *subtitleUpstream) { u.itemGone = true })
		}, "source_missing"},
		"it has no picture": {func(r *subtitleRig) {
			r.upstream.edit(func(u *subtitleUpstream) { u.inside = u.inside[1:] })
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
	if got := keysOf(rig.list()); !slices.Equal(got, []string{"ext-heb", "emb-2", "emb-3"}) {
		t.Errorf("keys = %v", got)
	}
}

func TestAnExpiredGrantNoLongerListsOrServesItsSubtitles(t *testing.T) {
	rig := newSubtitleRig(t)
	rig.expire()
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
	if got := rig.get(subtitleDir + rig.grant.ID + "/subtitles/0"); got.Code != http.StatusNotFound {
		t.Errorf("the original subtitle route for an Apple grant = %d, want the 404 it always was", got.Code)
	}
	release := playbackRequest(rig.handler, http.MethodDelete, subtitleDir+rig.grant.ID+"/media", "", playbackUserID)
	if release.Code != http.StatusNoContent {
		t.Fatalf("release = %d", release.Code)
	}
	if got := keysOf(rig.list()); !slices.Equal(got, []string{"ext-heb", "emb-2", "emb-3"}) {
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
	for _, first := range "abcdefghijklmnopqrstuvwxyz" {
		language := "q" + string(first) + "z"
		rig.upstream.addSidecar(-1, sidecar(language, "subrip", language, "", subtitleFile{text: srtEnglish}))
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
	rig.upstream.setFile("heb", subtitleFile{status: http.StatusNotFound})
	rig.list()
	rig.get(subtitleDir + rig.grant.ID + "/subtitle-tracks/ext-heb")
	rig.upstream.setFile("heb", subtitleFile{status: http.StatusInternalServerError})
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
	rig.upstream.addSidecar(-1, sidecar("eng", "subrip", "eng", rig.path, subtitleFile{text: srtEnglish}))
	rig.upstream.addSidecar(-1, sidecar("spa", "subrip", "spa", "Signs/Songs\n  and   more", subtitleFile{text: srtEnglish}))
	rig.upstream.addSidecar(-1, sidecar("ita", "subrip", "ita", strings.Repeat("long ", 80), subtitleFile{text: srtEnglish}))
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
	if asked := rig.upstream.askedFor(); !slices.Equal(asked, []string{"heb:srt", "heb:srt"}) {
		t.Errorf("two lists read the sidecar as %v, want it twice", asked)
	}
}

func TestAnEmbeddedSignatureIsBoundToTheSourceTheTrackAndTheConvertersVersion(t *testing.T) {
	rig := newSubtitleRig(t)
	stream := jellyfin.MediaStream{Index: 3, Type: "Subtitle", Codec: "subrip", Language: "eng"}
	base := embeddedSubtitleSignature(rig.grant, stream, 2)
	if again := embeddedSubtitleSignature(rig.grant, stream, 2); again != base {
		t.Error("the same track signs differently twice")
	}
	// Jellyfin's number for it is not in the signature: it moves when a sidecar is added.
	moved := stream
	moved.Index = 9
	if embeddedSubtitleSignature(rig.grant, moved, 2) != base {
		t.Error("the signature follows Jellyfin's index, which moves when a sidecar is added")
	}
	otherSource := rig.grant
	otherSource.MediaSourceID = "another"
	otherSize := rig.grant
	otherSize.Manifest.Source.SizeBytes++
	otherLanguage := stream
	otherLanguage.Language = "fre"
	for name, other := range map[string]string{
		"source": embeddedSubtitleSignature(otherSource, stream, 2), "size": embeddedSubtitleSignature(otherSize, stream, 2),
		"place in the file": embeddedSubtitleSignature(rig.grant, stream, 3), "language": embeddedSubtitleSignature(rig.grant, otherLanguage, 2),
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
// it, and the list that was made from the same plan. This film is numbered the old
// way, with its sidecar last.
func TestARealAppleDownloadsSubtitleListMatchesItsMP4AndSurvivesTheRelease(t *testing.T) {
	rig := newAppleRig(t, nil)
	manifest, ready := rig.ready(t)
	inFile := rig.subtitleLanguagesInTheMP4(t, manifest, ready)

	if release := rig.request(http.MethodDelete, manifest.MediaURL, ""); release.Code != http.StatusNoContent {
		t.Fatalf("release = %d", release.Code)
	}
	list := rig.subtitleList(t, manifest)
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

// subtitleLanguagesInTheMP4 downloads the finished MP4 and says which languages its
// subtitle options are, in the order the file holds them.
func (r *appleRig) subtitleLanguagesInTheMP4(t *testing.T, manifest OfflineManifest, ready OfflineStatus) []string {
	t.Helper()
	served := r.request(http.MethodGet, manifest.MediaURL, "")
	if served.Code != http.StatusOK || int64(served.Body.Len()) != ready.SizeBytes {
		t.Fatalf("media = %d", served.Code)
	}
	out := filepath.Join(t.TempDir(), "served.mp4")
	if err := os.WriteFile(out, served.Body.Bytes(), 0o600); err != nil {
		t.Fatal(err)
	}
	var languages []string
	for _, stream := range probeMP4(t, out) {
		if stream.CodecType == "subtitle" {
			languages = append(languages, stream.Language)
		}
	}
	return languages
}

func (r *appleRig) subtitleList(t *testing.T, manifest OfflineManifest) AppleSubtitleList {
	t.Helper()
	response := r.request(http.MethodGet, subtitleDir+manifest.GrantID+"/subtitle-tracks", "")
	if response.Code != http.StatusOK {
		t.Fatalf("list = %d: %s", response.Code, response.Body.String())
	}
	var list AppleSubtitleList
	if err := json.Unmarshal(response.Body.Bytes(), &list); err != nil {
		t.Fatal(err)
	}
	return list
}

// A film Jellyfin 10.11 numbers the way it numbered a Drake & Josh episode: the
// sidecar is stream 0, the video 1. The MP4 is built (it failed on "Stream map '0:2'
// matches no streams" when the hub mapped by Jellyfin's numbers), its subtitle
// options are those the list names, and when Bazarr adds a sidecar in front of
// everything the grant still renews, the file the hub holds is kept, and the tracks
// already kept have the same keys and signatures.
func TestARealDownloadNumberedSidecarFirstIsBuiltListedAndRenewedPastANewSidecarInFrontOfIt(t *testing.T) {
	rig := newAppleRig(t, nil)
	rig.upstream.withSidecars(appleSidecar{"heb", hebrewSidecar})
	manifest, ready := rig.ready(t)

	if got := rig.subtitleLanguagesInTheMP4(t, manifest, ready); !slices.Equal(got, []string{"heb", "eng"}) {
		t.Fatalf("the MP4's subtitles are %v, want the sidecar's first and then the file's own, as the plan has them", got)
	}
	before := rig.subtitleList(t, manifest)
	if got := keysOf(before); !slices.Equal(got, []string{"ext-heb", "emb-3"}) {
		t.Fatalf("keys = %v", got)
	}
	hebrewBefore, englishBefore := before.Tracks[0], before.Tracks[1]
	if hebrewBefore.SourceIndex != 0 || englishBefore.SourceIndex != 4 || hebrewBefore.MP4Index == nil || *hebrewBefore.MP4Index != 0 ||
		englishBefore.MP4Index == nil || *englishBefore.MP4Index != 1 {
		t.Errorf("hebrew %+v english %+v", hebrewBefore, englishBefore)
	}

	// Bazarr adds French. Jellyfin numbers it 0, the Hebrew sidecar becomes 1, and the
	// film's streams all move up again. A month goes by.
	planned, _ := rig.server.offline.get(manifest.GrantID)
	expired := planned
	expired.ExpiresAt = time.Now().Add(-time.Minute).UnixMilli()
	expired.Manifest.ExpiresAt = expired.ExpiresAt
	if err := rig.server.offline.put(expired); err != nil {
		t.Fatal(err)
	}
	rig.upstream.withSidecars(appleSidecar{"fra", "1\n00:00:01,000 --> 00:00:02,000\nBonjour\n"}, appleSidecar{"heb", hebrewSidecar})
	if got := rig.request(http.MethodGet, subtitleDir+manifest.GrantID+"/subtitle-tracks", ""); got.Code != http.StatusGone {
		t.Fatalf("list on an expired grant = %d", got.Code)
	}

	renewed := rig.request(http.MethodPost, subtitleDir+manifest.GrantID+"/renew", `{}`)
	if renewed.Code != http.StatusOK {
		t.Fatalf("renew = %d: %s", renewed.Code, renewed.Body.String())
	}
	stored, _ := rig.server.offline.get(manifest.GrantID)
	if stored.PlanSignature != planned.PlanSignature {
		t.Errorf("the stored plan changed from %q to %q", planned.PlanSignature, stored.PlanSignature)
	}
	if status := rig.status(manifest.GrantID); status.State != "ready" || status.ETag != ready.ETag {
		t.Errorf("after the renewal the file is %+v, want the one it was (%s)", status, ready.ETag)
	}

	after := rig.subtitleList(t, manifest)
	if got := keysOf(after); !slices.Equal(got, []string{"ext-fra", "ext-heb", "emb-3"}) {
		t.Fatalf("keys after French arrived = %v", got)
	}
	for _, track := range before.Tracks {
		var now AppleSubtitleTrack
		for _, candidate := range after.Tracks {
			if candidate.Key == track.Key {
				now = candidate
			}
		}
		if now.Signature != track.Signature {
			t.Errorf("%s changed from %s to %s because another language arrived", track.Key, track.Signature, now.Signature)
		}
		if now.MP4Index == nil || *now.MP4Index != *track.MP4Index {
			t.Errorf("%s moved in the MP4: %v and %v", track.Key, track.MP4Index, now.MP4Index)
		}
	}
	if english := after.Tracks[2]; english.SourceIndex != englishBefore.SourceIndex+1 {
		t.Errorf("english = %+v: Jellyfin's number for it should have moved up by one", english)
	}
	if french := after.Tracks[0]; french.MP4Index != nil || french.SourceIndex != 0 {
		t.Errorf("french = %+v", french)
	}
	if bonjour := rig.request(http.MethodGet, after.Tracks[0].URL, ""); bonjour.Code != http.StatusOK || !strings.Contains(bonjour.Body.String(), "Bonjour") {
		t.Errorf("french = %d %q", bonjour.Code, bonjour.Body.String())
	}
}
