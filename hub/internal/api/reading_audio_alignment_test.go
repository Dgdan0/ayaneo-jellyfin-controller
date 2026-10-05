package api

import (
	"archive/zip"
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"math"
	"net/http"
	"os"
	"path/filepath"
	"reflect"
	"sort"
	"strings"
	"sync/atomic"
	"testing"

	readingdomain "ayaneohub/internal/reading"
)

// What Storyteller's manifest says each file of the tracked book lasts.
var trackedDurationSeconds = map[string]float64{
	"Part 5 - 100% Pure & Co., It's #5 [Ünïcode] פרק.mp3": 3000.25,
	"Fixture Odyssey (1).mp3":                             1000.5,
	"Fixture Odyssey (2).mp3":                             2000.75,
	"Fixture Odyssey (3).MP3":                             2500,
	"Fixture Odyssey.mp3":                                 4610.652,
}

// narrationOf is how an aligned edition narrates one of the book's files: as
// long as the file less the 12 ms the real alignment fell short by, in one chunk
// or, for the file called (2), two.
func narrationOf(name string, seconds float64) readingdomain.FixtureNarration {
	length := int64(math.Round(seconds*1000)) - 12
	if strings.Contains(name, "(2)") {
		return readingdomain.FixtureNarration{ChunkMs: []int64{1_000_000, length - 1_000_000}, Sentences: []int{3, 3}}
	}
	return readingdomain.FixtureNarration{ChunkMs: []int64{length}, Sentences: []int{5}}
}

const alignedEPUBStorytellerPath = "/library/audiobooks/Fixture Odyssey/aligned.epub"

// alignedOptions adjust the aligned edition of a generated tracked book.
type alignedOptions struct {
	// order is the files in the order the edition was aligned: its source
	// numbers. The default is the story's, which the tags give.
	order []string
	// seconds overrides what the manifest says a file lasts.
	seconds map[string]float64
	// narrate overrides how a file is narrated.
	narrate map[string]readingdomain.FixtureNarration
	// omit leaves files out of the narration.
	omit []string
	// readaloud overrides the edition Storyteller reports.
	readaloud string
	// epub changes the generated edition (to break it) before the hub sees it.
	epub func(path string)
	// audioBytes is the size of each audio entry of the edition (2048 by default).
	audioBytes int
}

// newAlignedTrackedEnv is the tracked book with a read-along edition beside it.
func newAlignedTrackedEnv(t *testing.T, options alignedOptions) *audioEnv {
	t.Helper()
	if options.order == nil {
		options.order = trackedTagOrder
	}
	if options.audioBytes == 0 {
		options.audioBytes = 2048
	}
	env := newAudioEnv(t, audioEnvOptions{}, func(root string) audioBuild {
		book, err := readingdomain.GenerateTrackedAudiobook(root)
		if err != nil {
			t.Fatal(err)
		}
		links := make([]audioLink, len(book.Files))
		seconds := func(name string) float64 {
			if value, ok := options.seconds[name]; ok {
				return value
			}
			return trackedDurationSeconds[name]
		}
		for i, file := range book.Files {
			kind, _ := readingdomain.AudioKindOf(file.Name)
			links[i] = audioLink{Href: file.Name, Type: kind.MIME, Title: fmt.Sprintf("Track %d", file.Track), Duration: seconds(file.Name), Size: file.Size}
		}
		var narrations []readingdomain.FixtureNarration
		for _, name := range options.order {
			if slicesContain(options.omit, name) {
				continue
			}
			if custom, ok := options.narrate[name]; ok {
				narrations = append(narrations, custom)
			} else {
				narrations = append(narrations, narrationOf(name, seconds(name)))
			}
		}
		epubPath := filepath.Join(book.Dir, "aligned.epub")
		fixture, err := readingdomain.GenerateAlignedEPUB(epubPath, readingdomain.AlignedEPUBOptions{PackageDir: "OEBPS", AudioBytes: options.audioBytes, Narrations: narrations})
		if err != nil {
			t.Fatal(err)
		}
		if options.epub != nil {
			options.epub(epubPath)
		}
		readaloud := options.readaloud
		if readaloud == "" {
			readaloud = `{"uuid":"aligned-12","filepath":"` + alignedEPUBStorytellerPath + `","status":"ALIGNED"}`
		}
		return audioBuild{json: storytellerAudiobook(t, "/library/"+book.Relative, links, false), book: book, links: links, readaloud: readaloud, epub: fixture}
	})
	for _, file := range env.build.book.Files {
		env.tags[file.Name] = file.Track
	}
	return env
}

func slicesContain(list []string, value string) bool {
	for _, item := range list {
		if item == value {
			return true
		}
	}
	return false
}

func (e *audioEnv) alignedTracks(manifest ReadingAudioManifest) (tracks []int, starts []int64, hrefs []string) {
	e.t.Helper()
	if manifest.Alignment == nil {
		e.t.Fatalf("the manifest has no alignment (aligned %v, reason %q)", manifest.Aligned, manifest.AlignmentReason)
	}
	for _, audio := range manifest.Alignment.Audio {
		tracks, starts, hrefs = append(tracks, audio.Track), append(starts, audio.StartMs), append(hrefs, audio.Href)
	}
	return
}

func TestAudioManifestMapsTheAlignedFilesToTheirTracksByLength(t *testing.T) {
	env := newAlignedTrackedEnv(t, alignedOptions{})
	manifest := env.manifest()
	if !manifest.Aligned || manifest.AlignmentReason != "" {
		t.Fatalf("aligned %v, reason %q", manifest.Aligned, manifest.AlignmentReason)
	}
	tracks, starts, hrefs := env.alignedTracks(manifest)
	// Narration order: five narrated files, the third in two chunks. The edition
	// was aligned in the story's order, which the tags also give, so each narrated
	// file is the track of the same place.
	if want := []int{0, 1, 2, 2, 3, 4}; !reflect.DeepEqual(tracks, want) {
		t.Errorf("tracks = %v, want %v", tracks, want)
	}
	if want := []int64{0, 0, 0, 1_000_000, 0, 0}; !reflect.DeepEqual(starts, want) {
		t.Errorf("starts = %v, want %v", starts, want)
	}
	wantHrefs := []string{
		"OEBPS/Audio/00001-00001.mp3", "OEBPS/Audio/00002-00001.mp3", "OEBPS/Audio/00003-00001.mp3",
		"OEBPS/Audio/00003-00002.mp3", "OEBPS/Audio/00004-00001.mp3", "OEBPS/Audio/00005-00001.mp3",
	}
	if !reflect.DeepEqual(hrefs, wantHrefs) {
		t.Errorf("hrefs = %v, want %v", hrefs, wantHrefs)
	}
}

// The edition numbers its files in the order it found them, which is not the
// manifest's order and need not be the story's. It is the lengths that say which
// narrated file is which.
func TestAudioManifestMapsByLengthWhateverTheNumbering(t *testing.T) {
	env := newAlignedTrackedEnv(t, alignedOptions{order: trackedManifestOrder})
	tracks, starts, _ := env.alignedTracks(env.manifest())
	// Narrated first: the manifest's first file (served last), then (1), then
	// (2) in two chunks, then (3), then the unsuffixed one (served first).
	if want := []int{4, 1, 2, 2, 3, 0}; !reflect.DeepEqual(tracks, want) {
		t.Errorf("tracks = %v, want %v", tracks, want)
	}
	if want := []int64{0, 0, 0, 1_000_000, 0, 0}; !reflect.DeepEqual(starts, want) {
		t.Errorf("starts = %v, want %v", starts, want)
	}
}

func TestAudioManifestIsNotAlignedWithoutAnEditionOrWithOneNotReady(t *testing.T) {
	plain := newTrackedAudioEnv(t).manifest()
	if plain.Aligned || plain.Alignment != nil || plain.AlignmentReason != "" {
		t.Fatalf("a book with no read-along: %+v", plain)
	}
	notReady := newAlignedTrackedEnv(t, alignedOptions{readaloud: `{"uuid":"aligned-12","filepath":"` + alignedEPUBStorytellerPath + `","status":"PROCESSING"}`}).manifest()
	if notReady.Aligned || notReady.Alignment != nil || notReady.AlignmentReason != "" {
		t.Fatalf("an edition still being aligned: %+v", notReady)
	}
}

func TestAudioManifestSaysWhyItCannotMapAnAlignment(t *testing.T) {
	shifted := func(name string) readingdomain.FixtureNarration {
		narration := narrationOf(name, trackedDurationSeconds[name])
		narration.ChunkMs[len(narration.ChunkMs)-1] += 5_000 // five seconds more than the file has
		return narration
	}
	for _, test := range []struct {
		name    string
		options alignedOptions
		want    string
	}{
		{"the edition is not on the disk", alignedOptions{epub: func(path string) { os.Remove(path) }}, "missing_file"},
		{"the edition's folder is mapped nowhere", alignedOptions{readaloud: `{"uuid":"a","filepath":"/data/assets/Book/aligned.epub","status":"ALIGNED"}`}, "unmapped_root"},
		{"the edition is not an archive", alignedOptions{epub: func(path string) { os.WriteFile(path, []byte("not a zip archive"), 0o644) }}, "unreadable"},
		{"the edition narrates nothing", alignedOptions{epub: func(path string) {
			stripOverlays(t, path)
		}}, "no_narration"},
		{"the edition narrates fewer files than the book has", alignedOptions{omit: []string{"Fixture Odyssey (3).MP3"}}, "files_do_not_match"},
		{"a narrated file is no length of the book", alignedOptions{narrate: map[string]readingdomain.FixtureNarration{"Fixture Odyssey (1).mp3": shifted("Fixture Odyssey (1).mp3")}}, "lengths_do_not_match"},
		{"two files cannot be told apart by their lengths", alignedOptions{
			seconds: map[string]float64{"Fixture Odyssey (1).mp3": 3600.000, "Fixture Odyssey (3).MP3": 3600.100},
			narrate: map[string]readingdomain.FixtureNarration{
				"Fixture Odyssey (1).mp3": {ChunkMs: []int64{3_599_988}, Sentences: []int{4}},
				"Fixture Odyssey (3).MP3": {ChunkMs: []int64{3_600_088}, Sentences: []int{4}},
			},
		}, "lengths_ambiguous"},
	} {
		t.Run(test.name, func(t *testing.T) {
			env := newAlignedTrackedEnv(t, test.options)
			manifest := env.manifest()
			if manifest.Aligned || manifest.Alignment != nil || manifest.AlignmentReason != test.want {
				t.Fatalf("aligned %v, alignment %v, reason %q, want %q", manifest.Aligned, manifest.Alignment, manifest.AlignmentReason, test.want)
			}
			// Everything that is not the alignment still works.
			if len(manifest.Tracks) != 5 || env.get(env.trackPath(env.child, "0", manifest.Revision)).Code != http.StatusOK {
				t.Fatalf("the tracks stopped working: %+v", manifest.Tracks)
			}
		})
	}
}

// stripOverlays takes the media overlays out of an edition's package: an EPUB
// that narrates nothing.
func stripOverlays(t *testing.T, path string) {
	t.Helper()
	rewriteAlignedEPUB(t, path, func(opf string) string {
		for _, id := range []string{"ov1", "ov2", "ov3", "ov4", "ov5"} {
			opf = strings.ReplaceAll(opf, ` media-overlay="`+id+`"`, "")
		}
		return opf
	})
}

// rewriteAlignedEPUB changes the package document of a generated edition in
// place and leaves every other entry as it was, in its order.
func rewriteAlignedEPUB(t *testing.T, path string, change func(opf string) string) {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	reader, err := zip.NewReader(bytes.NewReader(data), int64(len(data)))
	if err != nil {
		t.Fatal(err)
	}
	var out bytes.Buffer
	writer := zip.NewWriter(&out)
	for _, entry := range reader.File {
		stream, err := entry.Open()
		if err != nil {
			t.Fatal(err)
		}
		body, err := io.ReadAll(stream)
		stream.Close()
		if err != nil {
			t.Fatal(err)
		}
		if entry.Name == "OEBPS/content.opf" {
			body = []byte(change(string(body)))
		}
		target, err := writer.CreateHeader(&zip.FileHeader{Name: entry.Name, Method: entry.Method, Modified: entry.Modified})
		if err != nil {
			t.Fatal(err)
		}
		if _, err := target.Write(body); err != nil {
			t.Fatal(err)
		}
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, out.Bytes(), 0o644); err != nil {
		t.Fatal(err)
	}
}

func TestAudioManifestReadsEachEditionOnceAndAgainWhenItChanges(t *testing.T) {
	env := newAlignedTrackedEnv(t, alignedOptions{})
	var reads atomic.Int32
	real := env.server.readAlignment
	env.server.readAlignment = func(file io.ReaderAt, size int64) (*readingdomain.Alignment, error) {
		reads.Add(1)
		return real(file, size)
	}
	first := env.manifest()
	env.forget()
	second := env.manifest()
	if reads.Load() != 1 || first.Revision != second.Revision {
		t.Fatalf("%d reads of an unchanged edition, revisions %s and %s", reads.Load(), first.Revision, second.Revision)
	}
	// A new edition under the same name: its time and size are not the old ones.
	if _, err := readingdomain.GenerateAlignedEPUB(filepath.Join(env.build.book.Dir, "aligned.epub"), readingdomain.AlignedEPUBOptions{
		PackageDir: "OEBPS", AudioBytes: 4096, Narrations: alignedNarrations(env, trackedTagOrder),
	}); err != nil {
		t.Fatal(err)
	}
	env.forget()
	third := env.manifest()
	if reads.Load() != 2 {
		t.Fatalf("%d reads after the edition changed", reads.Load())
	}
	if third.Revision == second.Revision || !third.Aligned {
		t.Fatalf("a new edition kept the revision %s (aligned %v)", third.Revision, third.Aligned)
	}
}

func alignedNarrations(env *audioEnv, order []string) []readingdomain.FixtureNarration {
	var narrations []readingdomain.FixtureNarration
	for _, name := range order {
		narrations = append(narrations, narrationOf(name, trackedDurationSeconds[name]))
	}
	return narrations
}

// A lone M4B's chapters are what the edition narrates: each source is a chapter
// of the one file, and its place in the track is where the chapter starts.
func TestAudioManifestMapsAnEditionOfALoneM4BThroughItsChapters(t *testing.T) {
	env := newAlignedM4BEnv(t)
	manifest := env.manifest()
	tracks, starts, hrefs := env.alignedTracks(manifest)
	if !manifest.Aligned || len(manifest.Tracks) != 1 {
		t.Fatalf("aligned %v, %d tracks, reason %q", manifest.Aligned, len(manifest.Tracks), manifest.AlignmentReason)
	}
	if want := []int{0, 0, 0, 0}; !reflect.DeepEqual(tracks, want) {
		t.Errorf("tracks = %v, want %v", tracks, want)
	}
	// Chapters begin at 0, 1500500 and 3300750; the second is cut in two chunks.
	if want := []int64{0, 1_500_500, 2_400_500, 3_300_750}; !reflect.DeepEqual(starts, want) {
		t.Errorf("starts = %v, want %v", starts, want)
	}
	if len(hrefs) != 4 || hrefs[1] != "OEBPS/Audio/00002-00001.mp3" || hrefs[2] != "OEBPS/Audio/00002-00002.mp3" {
		t.Errorf("hrefs = %v", hrefs)
	}
}

func newAlignedM4BEnv(t *testing.T) *audioEnv { return newAlignedM4BEnvOf(t, chapteredLinks) }

func newAlignedM4BEnvOf(t *testing.T, links []audioLink) *audioEnv {
	t.Helper()
	return newAudioEnv(t, audioEnvOptions{}, func(root string) audioBuild {
		book, err := readingdomain.GenerateM4BAudiobook(root)
		if err != nil {
			t.Fatal(err)
		}
		fixture, err := readingdomain.GenerateAlignedEPUB(filepath.Join(book.Dir, "aligned.epub"), readingdomain.AlignedEPUBOptions{
			PackageDir: "OEBPS", AudioBytes: 2048,
			Narrations: []readingdomain.FixtureNarration{
				{ChunkMs: []int64{1_500_488}, Sentences: []int{5}},
				{ChunkMs: []int64{900_000, 900_238}, Sentences: []int{3, 3}},
				{ChunkMs: []int64{899_988}, Sentences: []int{4}},
			},
		})
		if err != nil {
			t.Fatal(err)
		}
		readaloud := `{"uuid":"aligned-12","filepath":"/library/audiobooks/Fixture Chapters/aligned.epub","status":"ALIGNED"}`
		return audioBuild{json: storytellerAudiobook(t, "/library/"+book.Relative, links, false), book: book, links: links, readaloud: readaloud, epub: fixture}
	})
}

// A chapter of unknown length cannot be told from another by it, so the edition
// cannot be mapped; the file still streams.
func TestAudioManifestOfALoneM4BWithAChapterOfNoLengthIsNotAligned(t *testing.T) {
	links := append([]audioLink(nil), chapteredLinks...)
	links[1].Duration = 0
	manifest := newAlignedM4BEnvOf(t, links).manifest()
	if manifest.Aligned || manifest.Alignment != nil || manifest.AlignmentReason != "lengths_do_not_match" || len(manifest.Tracks) != 1 {
		t.Fatalf("aligned %v, reason %q, %d tracks", manifest.Aligned, manifest.AlignmentReason, len(manifest.Tracks))
	}
}

func TestAudioManifestAlignmentHasTheDocumentedFields(t *testing.T) {
	var body map[string]any
	env := newAlignedTrackedEnv(t, alignedOptions{})
	if err := json.Unmarshal(env.get(env.audioPath(env.child)).Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if _, present := body["alignmentReason"]; present || body["aligned"] != true {
		t.Fatalf("an aligned book has no reason: %v", body)
	}
	alignment := body["alignment"].(map[string]any)
	if len(alignment) != 1 {
		t.Fatalf("alignment holds only its audio list: %v", alignment)
	}
	audio := alignment["audio"].([]any)
	if len(audio) != 6 {
		t.Fatalf("audio = %v", audio)
	}
	got := []string{}
	for key := range audio[0].(map[string]any) {
		got = append(got, key)
	}
	sort.Strings(got)
	if want := []string{"href", "startMs", "track"}; !reflect.DeepEqual(got, want) {
		t.Fatalf("an aligned file's keys = %v, want %v", got, want)
	}

	broken := newAlignedTrackedEnv(t, alignedOptions{epub: func(path string) { os.WriteFile(path, []byte("not a zip archive"), 0o644) }})
	body = map[string]any{}
	if err := json.Unmarshal(broken.get(broken.audioPath(broken.child)).Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if _, present := body["alignment"]; present || body["aligned"] != false || body["alignmentReason"] != "unreadable" {
		t.Fatalf("an unusable edition says why and lists nothing: %v", body)
	}
}

func TestAudioManifestAlignmentNamesNoHostFileAndFailureHoldsNoPath(t *testing.T) {
	env := newAlignedTrackedEnv(t, alignedOptions{})
	response := env.get(env.audioPath(env.child))
	text := response.Body.String()
	for _, bad := range []string{env.root, filepath.Base(filepath.Dir(env.root)), "aligned.epub", "/library", `\\`, "Fixture", ".epub"} {
		if strings.Contains(text, bad) {
			t.Errorf("the manifest holds %q: %.300s", bad, text)
		}
	}
	// Inside the edition, an audio file's path is the edition's own and is all
	// the app has to find it by.
	if !strings.Contains(text, `"OEBPS/Audio/00001-00001.mp3"`) {
		t.Errorf("no narrated file in %.300s", text)
	}
	broken := newAlignedTrackedEnv(t, alignedOptions{epub: func(path string) { os.WriteFile(path, []byte("not a zip archive"), 0o644) }})
	text = broken.get(broken.audioPath(broken.child)).Body.String()
	for _, bad := range []string{broken.root, "aligned.epub", "/library", "zip archive"} {
		if strings.Contains(text, bad) {
			t.Errorf("the failed manifest holds %q: %.300s", bad, text)
		}
	}
}

func TestAudioManifestTracksStillServeWithoutTheEPUB(t *testing.T) {
	env := newAlignedTrackedEnv(t, alignedOptions{})
	manifest := env.manifest()
	if err := os.Remove(filepath.Join(env.build.book.Dir, "aligned.epub")); err != nil {
		t.Fatal(err)
	}
	// The held list still says aligned; the files it names are what a track
	// request checks, and they are all there.
	if got := env.get(env.trackPath(env.child, "1", manifest.Revision)); got.Code != http.StatusOK {
		t.Fatalf("a track with the edition gone = %d", got.Code)
	}
	env.forget()
	if after := env.manifest(); after.Aligned || after.AlignmentReason != "missing_file" || after.Revision == manifest.Revision {
		t.Fatalf("after the edition went: aligned %v, reason %q, revision kept %v", after.Aligned, after.AlignmentReason, after.Revision == manifest.Revision)
	}
}
