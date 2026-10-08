package api

// How Storyteller lays a narration over a book that is not one narrated file for
// each of the book's (#51): a single file cut at its chapters, a piece of it left
// out, a track with nothing narrated, a sentence written past the end of its audio.

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"net/http"
	"os"
	"reflect"
	"regexp"
	"strings"
	"testing"

	readingdomain "ayaneohub/internal/reading"
)

// Ender's Game's last four tracks and Sunrise on the Reaping's credits and part
// titles are not in the edition, which narrates the rest.
func TestAudioManifestLeavesATrackThatNothingNarrates(t *testing.T) {
	env := newAlignedTrackedEnv(t, alignedOptions{omit: []string{"Fixture Odyssey (3).MP3"}})
	manifest := env.manifest()
	if !manifest.Aligned || manifest.AlignmentReason != "" {
		t.Fatalf("aligned %v, reason %q", manifest.Aligned, manifest.AlignmentReason)
	}
	tracks, starts, _ := env.alignedTracks(manifest)
	if want := []int{0, 1, 2, 2, 4}; !reflect.DeepEqual(tracks, want) {
		t.Errorf("tracks = %v, want %v", tracks, want)
	}
	if want := []int64{0, 0, 0, 1_000_000, 0}; !reflect.DeepEqual(starts, want) {
		t.Errorf("starts = %v, want %v", starts, want)
	}
	if len(manifest.Tracks) != 5 || env.get(env.trackPath(env.child, "3", manifest.Revision)).Code != http.StatusOK {
		t.Fatalf("the track nothing narrates does not play: %+v", manifest.Tracks)
	}
}

// Dune is one M4B, and Storyteller cut it at its eighteen chapters: one narrated
// file, 00001-00001 to 00001-00018, each piece a chapter. A chapter with nothing
// narrated in it has no piece, and the others keep their numbers.
func TestAudioManifestMapsALoneM4BCutAtItsChapters(t *testing.T) {
	for name, test := range map[string]struct {
		narration readingdomain.FixtureNarration
		starts    []int64
	}{
		"every chapter narrated": {readingdomain.FixtureNarration{ChunkMs: []int64{1_500_488, 1_800_238, 899_988}, Sentences: []int{5, 6, 4}}, []int64{0, 1_500_500, 3_300_750}},
		"the second not":         {readingdomain.FixtureNarration{ChunkMs: []int64{1_500_488, 899_988}, Sentences: []int{5, 4}, Numbers: []int{1, 3}}, []int64{0, 3_300_750}},
	} {
		t.Run(name, func(t *testing.T) {
			manifest := newAlignedM4BEnvWith(t, chapteredLinks, []readingdomain.FixtureNarration{test.narration}).manifest()
			if !manifest.Aligned || len(manifest.Tracks) != 1 {
				t.Fatalf("aligned %v, reason %q", manifest.Aligned, manifest.AlignmentReason)
			}
			var tracks []int
			var starts []int64
			for _, audio := range manifest.Alignment.Audio {
				tracks, starts = append(tracks, audio.Track), append(starts, audio.StartMs)
			}
			if !reflect.DeepEqual(starts, test.starts) || slicesMax(tracks) != 0 {
				t.Fatalf("tracks %v starts %v, want track 0 at %v", tracks, starts, test.starts)
			}
		})
	}
	// A piece that is not its chapter's length is no chapter of the file.
	wrong := newAlignedM4BEnvWith(t, chapteredLinks, []readingdomain.FixtureNarration{
		{ChunkMs: []int64{1_500_488, 1_801_238, 899_988}, Sentences: []int{5, 6, 4}},
	}).manifest()
	if wrong.Aligned || wrong.AlignmentReason != "lengths_do_not_match" {
		t.Fatalf("a piece a second longer than its chapter: aligned %v, reason %q", wrong.Aligned, wrong.AlignmentReason)
	}
}

func slicesMax(values []int) int {
	most := 0
	for _, value := range values {
		most = max(most, value)
	}
	return most
}

// The Dark Forest is one file with 54 chapter marks; Storyteller cut it at them and
// left out the first, 52 s of credits. Its pieces lie where its marks say.
func TestAudioManifestMapsAFileCutAtItsChapterMarks(t *testing.T) {
	second := "Fixture Odyssey (1).mp3" // 1000.5 s, the second track
	env := newAlignedTrackedEnv(t, alignedOptions{narrate: map[string]readingdomain.FixtureNarration{
		second: {ChunkMs: []int64{399_990, 200_490}, Sentences: []int{4, 3}, Numbers: []int{2, 3}},
	}})
	env.mu.Lock()
	env.chapters[second] = []probedChapter{{"One", 0, 400_000}, {"Two", 400_000, 800_000}, {"", 800_000, 1_000_500}}
	env.mu.Unlock()
	manifest := env.manifest()
	if !manifest.Aligned {
		t.Fatalf("aligned %v, reason %q", manifest.Aligned, manifest.AlignmentReason)
	}
	tracks, starts, hrefs := env.alignedTracks(manifest)
	if want := []int{0, 1, 1, 2, 2, 3, 4}; !reflect.DeepEqual(tracks, want) {
		t.Errorf("tracks = %v, want %v", tracks, want)
	}
	if want := []int64{0, 400_000, 800_000, 0, 1_000_000, 0, 0}; !reflect.DeepEqual(starts, want) {
		t.Errorf("starts = %v, want %v", starts, want)
	}
	if hrefs[1] != "OEBPS/Audio/00002-00002.mp3" {
		t.Errorf("hrefs = %v", hrefs)
	}
}

// overrunOptions is the tracked book whose second file the aligner ran past the
// end of, as it did 14 times in 8 of this library's books.
func overrunOptions() alignedOptions {
	return alignedOptions{narrate: map[string]readingdomain.FixtureNarration{
		"Fixture Odyssey (1).mp3": {ChunkMs: []int64{1_000_488}, Sentences: []int{40}, OverrunMs: []int64{5_439}},
	}}
}

// A sentence written as ending before it begins (A Clash of Kings has 3 of 28,409)
// marks where its audio ends: the edition is mapped as any other, and both editions
// the hub serves say so in their SMIL, which the apps read themselves and would
// refuse otherwise.
func TestAudioManifestMapsAnEditionTheAlignerRanPast(t *testing.T) {
	env := newAlignedTrackedEnv(t, overrunOptions())
	manifest := env.manifest()
	if !manifest.Aligned || manifest.AlignmentReason != "" {
		t.Fatalf("aligned %v, reason %q", manifest.Aligned, manifest.AlignmentReason)
	}
	if tracks, _, _ := env.alignedTracks(manifest); !reflect.DeepEqual(tracks, []int{0, 1, 2, 2, 3, 4}) {
		t.Fatalf("tracks = %v", tracks)
	}
	whole := env.get("/v1/reading/works/" + env.child + "/publications/12/file?format=readaloud")
	if whole.Code != http.StatusOK {
		t.Fatalf("whole = %d: %s", whole.Code, whole.Body.String())
	}
	read, err := readingdomain.ReadAlignment(bytes.NewReader(whole.Body.Bytes()), int64(whole.Body.Len()))
	if err != nil || read.PastEnd != 0 || read.CutAtEnd != 0 {
		t.Fatalf("the whole edition served still runs past its audio: %+v, %v", read, err)
	}
	// The slim edition's SMIL is the whole one's, word for word.
	slim := env.slim(nil)
	_, slimZip := zipNames(t, slim.Body.Bytes())
	_, wholeZip := zipNames(t, whole.Body.Bytes())
	_, originalZip := zipNames(t, mustRead(t, env.build.epub.Path))
	changed := 0
	for _, entry := range slimZip.File {
		if !strings.HasSuffix(entry.Name, ".smil") {
			continue
		}
		mended := zipEntry(t, slimZip, entry.Name)
		if !bytes.Equal(mended, zipEntry(t, wholeZip, entry.Name)) {
			t.Errorf("%s differs between the editions", entry.Name)
		}
		if !bytes.Equal(mended, zipEntry(t, originalZip, entry.Name)) {
			changed++
		}
	}
	if changed != 1 {
		t.Fatalf("%d overlays changed, want the one with the overrun", changed)
	}
}

func mustRead(t *testing.T, path string) []byte {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return data
}

// The whole edition (`?format=readaloud`) is the hub's copy of the file on this
// PC, its audio inside: Storyteller hashes the whole file before the first byte of
// every answer, a Range among them (21.6 s for Dune's 1.2 GB), and the hub plans
// its copy once.
func TestReadaloudWholeEditionIsTheHubsCopyWithItsAudio(t *testing.T) {
	env := newAlignedTrackedEnv(t, alignedOptions{audioBytes: 64 << 10})
	base := "/v1/reading/works/" + env.child + "/publications/12/file"
	full := env.get(base + "?format=readaloud")
	if full.Code != http.StatusOK {
		t.Fatalf("whole = %d: %s", full.Code, full.Body.String())
	}
	body := full.Body.Bytes()
	names, copied := zipNames(t, body)
	originalNames, original := zipNames(t, mustRead(t, env.build.epub.Path))
	if strings.Join(names, "\n") != strings.Join(originalNames, "\n") {
		t.Fatalf("entries\n%q\nwant\n%q", names, originalNames)
	}
	for _, audio := range env.build.epub.Audio {
		if !bytes.Equal(zipEntry(t, copied, audio), zipEntry(t, original, audio)) {
			t.Fatalf("%s is not the edition's audio", audio)
		}
	}
	sum := sha256.Sum256(body)
	header := full.Header()
	if header.Get("Content-Type") != "application/epub+zip" || header.Get("X-Reading-Content-Hash") != "sha256:"+hex.EncodeToString(sum[:]) ||
		!regexp.MustCompile(`^"[0-9a-f]{32}"$`).MatchString(header.Get("ETag")) {
		t.Fatalf("type %q, hash %q, etag %q", header.Get("Content-Type"), header.Get("X-Reading-Content-Hash"), header.Get("ETag"))
	}
	piece := env.trackAt(http.MethodGet, base+"?format=readaloud", map[string]string{"Range": "bytes=100-", "If-Range": header.Get("ETag")})
	if piece.Code != http.StatusPartialContent || !bytes.Equal(piece.Body.Bytes(), body[100:]) {
		t.Fatalf("a resumed download = %d with %d bytes", piece.Code, piece.Body.Len())
	}
	for name, query := range map[string]string{
		"another value":             "?format=readaloud&audio=keep",
		"a value in the wrong case": "?format=readaloud&audio=OMIT",
		"no format":                 "?audio=omit",
		"the ebook":                 "?format=ebook&audio=omit",
		"the audiobook archive":     "?format=audiobook&audio=omit",
	} {
		if got := env.get(base + query); got.Code != http.StatusBadRequest || decodeAudioError(t, got).Code != CodeInvalidRequest {
			t.Errorf("%s = %d: %s", name, got.Code, got.Body.String())
		}
	}
	// env.state.noFiles: Storyteller's own file route fails the test if it is called.
}

// Where the hub cannot open the edition, Storyteller's file goes through as it did.
func TestReadaloudWholeEditionGoesThroughStorytellerWhenTheHubCannotOpenIt(t *testing.T) {
	env := newAlignedTrackedEnv(t, alignedOptions{readaloud: `{"uuid":"a","filepath":"/data/assets/Book/aligned.epub","status":"ALIGNED"}`})
	env.state.noFiles = false
	env.state.readaloud = true
	full := env.get("/v1/reading/works/" + env.child + "/publications/12/file?format=readaloud")
	if full.Code != http.StatusPartialContent || full.Body.String() != "4567" || env.state.formatSeen != "readaloud" || env.state.fileCalls != 1 {
		t.Fatalf("the full edition = %d %q (format %q, %d calls)", full.Code, full.Body.String(), env.state.formatSeen, env.state.fileCalls)
	}
}
