package api

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"reflect"
	"regexp"
	"slices"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"ayaneohub/internal/config"
	readingdomain "ayaneohub/internal/reading"
)

// audioLink is one entry of Storyteller's audiobook manifest, as it writes it.
type audioLink struct {
	Href     string  `json:"href"`
	Type     string  `json:"type,omitempty"`
	Title    string  `json:"title,omitempty"`
	Duration float64 `json:"duration,omitempty"`
	Size     int64   `json:"size,omitempty"`
}

// storytellerAudiobook is the "audiobook" JSON Storyteller reports for a
// folder: where it is in Storyteller's own filesystem, and a manifest of it.
func storytellerAudiobook(t *testing.T, folder string, links []audioLink, missing bool) string {
	t.Helper()
	total := 0.0
	for _, link := range links {
		total += link.Duration
	}
	body, err := json.Marshal(map[string]any{
		"uuid": "audio-12", "filepath": folder, "duration": total, "missing": missing,
		"manifest": map[string]any{"readingOrder": links},
	})
	if err != nil {
		t.Fatal(err)
	}
	return string(body)
}

// audioBuild is what a test makes under the media root: the audiobook JSON
// Storyteller will report, and what was generated for it.
type audioBuild struct {
	json  string
	book  readingdomain.AudiobookFixture
	links []audioLink
}

// audioEnv is a hub whose Storyteller reports one audiobook, book 12, whose
// files were generated on disk and are mapped through media_removal_roots.
// Storyteller's /files route fails the test if it is called.
type audioEnv struct {
	t          *testing.T
	root       string
	state      *epubUpstreamState
	upstream   *httptest.Server
	server     *Server
	handler    http.Handler
	collection string
	child      string
	build      audioBuild

	// What the stand-in ffprobe says, by file name.
	mu       sync.Mutex
	tags     map[string]int
	chapters map[string][]probedChapter
	noProbe  bool
	probed   atomic.Int32
}

type audioEnvOptions struct {
	scopes []string
	roots  func(root string) []config.MediaRemovalRoot
}

// newAudioEnv builds the environment around whatever build generates under the
// media root.
func newAudioEnv(t *testing.T, options audioEnvOptions, build func(root string) audioBuild) *audioEnv {
	t.Helper()
	if options.scopes == nil {
		options.scopes = []string{"reading"}
	}
	if options.roots == nil {
		options.roots = func(root string) []config.MediaRemovalRoot {
			return []config.MediaRemovalRoot{{Service: "storyteller", Remote: "/library", Local: root}}
		}
	}
	root := t.TempDir()
	env := &audioEnv{t: t, root: root, tags: map[string]int{}, chapters: map[string][]probedChapter{}}
	env.build = build(root)
	env.state = &epubUpstreamState{audio: env.build.json, noFiles: true, narrators: `[{"name":"Jon Lindstrom"}]`}
	env.upstream = newEpubUpstream(t, env.state)
	t.Cleanup(env.upstream.Close)
	cfg := readingCatalogConfig(env.upstream.URL, filepath.Join(t.TempDir(), "catalog.json"), options.scopes)
	cfg.Server.MediaRemovalRoots = options.roots(root)
	env.server = NewServer(cfg)
	env.server.probeAudio = env.probe
	env.handler = env.server.Handler()
	if slices.Contains(options.scopes, "reading") {
		env.collection, env.child = bindEpubWork(t, env.handler)
	}
	return env
}

func (e *audioEnv) probe(_ context.Context, path string) (probedAudio, error) {
	e.probed.Add(1)
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.noProbe {
		return probedAudio{}, fmt.Errorf("ffprobe executable was not found")
	}
	name := filepath.Base(path)
	// What it says about duration is deliberately not the manifest's.
	return probedAudio{DurationMs: 777, Track: e.tags[name], Chapters: e.chapters[name]}, nil
}

// newTrackedAudioEnv is the five-file book whose manifest order is not its
// story: Storyteller lists the file with no suffix last, and its tags say it
// is first. Each file's nominal length is Storyteller's, not the second the
// real audio lasts, and the stand-in ffprobe reads every file's tag.
func newTrackedAudioEnv(t *testing.T) *audioEnv {
	t.Helper()
	durations := []float64{3000.25, 1000.5, 2000.75, 2500, 4610.652}
	env := newAudioEnv(t, audioEnvOptions{}, func(root string) audioBuild {
		book, err := readingdomain.GenerateTrackedAudiobook(root)
		if err != nil {
			t.Fatal(err)
		}
		links := make([]audioLink, len(book.Files))
		for i, file := range book.Files {
			kind, _ := readingdomain.AudioKindOf(file.Name)
			links[i] = audioLink{Href: file.Name, Type: kind.MIME, Title: fmt.Sprintf("Track %d", file.Track), Duration: durations[i], Size: file.Size}
		}
		return audioBuild{json: storytellerAudiobook(t, "/library/"+book.Relative, links, false), book: book, links: links}
	})
	for _, file := range env.build.book.Files {
		env.tags[file.Name] = file.Track
	}
	return env
}

// The chapters of the lone M4B, as Storyteller's manifest lists them.
var chapteredLinks = []audioLink{
	{Href: "00000-00001.mp3", Type: "audio/mpeg", Title: "Opening", Duration: 1500.5},
	{Href: "00001-00001.mp3", Type: "audio/mpeg", Title: "The Middle", Duration: 1800.25},
	{Href: "00002-00001.mp3", Type: "audio/mpeg", Title: "Track 3", Duration: 900},
}

// newM4BAudioEnv is a folder of one .m4b whose manifest lists its chapters.
func newM4BAudioEnv(t *testing.T, links []audioLink) *audioEnv {
	t.Helper()
	return newAudioEnv(t, audioEnvOptions{}, func(root string) audioBuild {
		book, err := readingdomain.GenerateM4BAudiobook(root)
		if err != nil {
			t.Fatal(err)
		}
		return audioBuild{json: storytellerAudiobook(t, "/library/"+book.Relative, links, false), book: book, links: links}
	})
}

func (e *audioEnv) audioPath(work string) string {
	return "/v1/reading/works/" + work + "/publications/12/audio"
}

func (e *audioEnv) get(path string) *httptest.ResponseRecorder {
	e.t.Helper()
	return libraryRequest(e.handler, path)
}

func (e *audioEnv) manifest() ReadingAudioManifest {
	e.t.Helper()
	response := e.get(e.audioPath(e.child))
	if response.Code != http.StatusOK {
		e.t.Fatalf("manifest = %d: %s", response.Code, response.Body.String())
	}
	var manifest ReadingAudioManifest
	if err := json.Unmarshal(response.Body.Bytes(), &manifest); err != nil {
		e.t.Fatal(err)
	}
	return manifest
}

// forget drops what the hub remembers of Storyteller and of the files, as a
// rescan does, so the next request reads both again.
func (e *audioEnv) forget() { e.server.invalidateReadingCatalog() }

func (e *audioEnv) file(name string) string { return filepath.Join(e.build.book.Dir, name) }

func (e *audioEnv) append(name, extra string) {
	e.t.Helper()
	handle, err := os.OpenFile(e.file(name), os.O_APPEND|os.O_WRONLY, 0)
	if err != nil {
		e.t.Fatal(err)
	}
	defer handle.Close()
	if _, err := handle.WriteString(extra); err != nil {
		e.t.Fatal(err)
	}
}

func (e *audioEnv) setTags(tags map[string]int) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.tags = tags
}

func trackIDFor(href string) string {
	sum := sha256.Sum256([]byte(href))
	return "t_" + hex.EncodeToString(sum[:])[:12]
}

// namesOf says which file each track of a manifest is, by the hub's id for it.
func (e *audioEnv) namesOf(manifest ReadingAudioManifest) []string {
	byID := map[string]string{}
	for _, file := range e.build.book.Files {
		byID[trackIDFor(file.Name)] = file.Name
	}
	names := make([]string, len(manifest.Tracks))
	for i, track := range manifest.Tracks {
		names[i] = byID[track.ID]
		if names[i] == "" {
			e.t.Fatalf("track %d has an id %q that is no file's", i, track.ID)
		}
	}
	return names
}

func (e *audioEnv) linkFor(name string) audioLink {
	for _, link := range e.build.links {
		if link.Href == name {
			return link
		}
	}
	e.t.Fatalf("no link for %s", name)
	return audioLink{}
}

func decodeAudioError(t *testing.T, response *httptest.ResponseRecorder) Error {
	t.Helper()
	var body errorBody
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
		t.Fatalf("not an error body: %v: %s", err, response.Body.String())
	}
	return body.Error
}

// The storyteller manifest order: the file with no suffix last.
var trackedManifestOrder = []string{
	"Part 5 - 100% Pure & Co., It's #5 [Ünïcode] פרק.mp3",
	"Fixture Odyssey (1).mp3",
	"Fixture Odyssey (2).mp3",
	"Fixture Odyssey (3).MP3",
	"Fixture Odyssey.mp3",
}

// The order its own tags give: the story's.
var trackedTagOrder = []string{
	"Fixture Odyssey.mp3",
	"Fixture Odyssey (1).mp3",
	"Fixture Odyssey (2).mp3",
	"Fixture Odyssey (3).MP3",
	"Part 5 - 100% Pure & Co., It's #5 [Ünïcode] פרק.mp3",
}

func TestAudioManifestPutsATrackedBookInTagOrderWithoutStorytellersArchive(t *testing.T) {
	env := newTrackedAudioEnv(t)
	for _, work := range []string{env.child, env.collection} {
		response := env.get(env.audioPath(work))
		if response.Code != http.StatusOK || !strings.HasPrefix(response.Header().Get("Content-Type"), "application/json") {
			t.Fatalf("manifest for %s = %d %q: %s", work, response.Code, response.Header().Get("Content-Type"), response.Body.String())
		}
		var manifest ReadingAudioManifest
		if err := json.Unmarshal(response.Body.Bytes(), &manifest); err != nil {
			t.Fatal(err)
		}
		if manifest.WorkID != work || manifest.SourceItemID != "12" {
			t.Fatalf("ids = %q, %q", manifest.WorkID, manifest.SourceItemID)
		}
		if !regexp.MustCompile(`^[0-9a-f]{12}$`).MatchString(manifest.Revision) {
			t.Fatalf("revision = %q", manifest.Revision)
		}
		if manifest.Narrator != "Jon Lindstrom" || manifest.Aligned {
			t.Fatalf("narrator %q, aligned %v", manifest.Narrator, manifest.Aligned)
		}
		if manifest.TotalMs != 13112152 {
			t.Fatalf("totalMs = %d, want the manifest's lengths summed, 13112152", manifest.TotalMs)
		}
		if got := env.namesOf(manifest); !reflect.DeepEqual(got, trackedTagOrder) {
			t.Fatalf("order = %q\nwant %q", got, trackedTagOrder)
		}
		etags := map[string]bool{}
		for i, track := range manifest.Tracks {
			name := trackedTagOrder[i]
			info, err := os.Stat(env.file(name))
			if err != nil {
				t.Fatal(err)
			}
			link := env.linkFor(name)
			kind, _ := readingdomain.AudioKindOf(name)
			if track.Index != i || track.ID != trackIDFor(name) || track.Title != link.Title || track.Bytes != info.Size() || track.Mime != kind.MIME {
				t.Errorf("track %d = %+v", i, track)
			}
			if want := int64(link.Duration*1000 + 0.5); track.DurationMs != want {
				t.Errorf("track %d lasts %d ms, the manifest says %d (the probe says 777)", i, track.DurationMs, want)
			}
			if !regexp.MustCompile(`^"[0-9a-f]{16}"$`).MatchString(track.ETag) || etags[track.ETag] {
				t.Errorf("track %d etag %q is not a strong, distinct one", i, track.ETag)
			}
			etags[track.ETag] = true
		}
		if !strings.Contains(response.Body.String(), `"chapters":[]`) {
			t.Errorf("a book without chapters says so with an empty list: %s", response.Body.String())
		}
	}
	if env.state.fileCalls != 0 {
		t.Fatalf("Storyteller's archive was built %d times", env.state.fileCalls)
	}
}

func TestAudioManifestKeepsStorytellersOrderUnlessEveryTagIsThereOnceFromOne(t *testing.T) {
	complete := map[string]int{}
	for i, name := range trackedTagOrder {
		complete[name] = i + 1
	}
	with := func(change func(map[string]int)) map[string]int {
		tags := map[string]int{}
		for name, track := range complete {
			tags[name] = track
		}
		change(tags)
		return tags
	}
	for _, test := range []struct {
		name    string
		tags    map[string]int
		noProbe bool
		want    []string
	}{
		{"every tag, once, from one", complete, false, trackedTagOrder},
		{"one file has no tag", with(func(t map[string]int) { delete(t, "Fixture Odyssey (2).mp3") }), false, trackedManifestOrder},
		{"a tag twice", with(func(t map[string]int) { t["Fixture Odyssey (2).mp3"] = 2 }), false, trackedManifestOrder},
		{"a gap", with(func(t map[string]int) { t["Part 5 - 100% Pure & Co., It's #5 [Ünïcode] פרק.mp3"] = 6 }), false, trackedManifestOrder},
		{"tags that start at zero", with(func(t map[string]int) {
			for name := range t {
				t[name]--
			}
		}), false, trackedManifestOrder},
		{"no tags at all", map[string]int{}, false, trackedManifestOrder},
		{"no ffprobe", complete, true, trackedManifestOrder},
	} {
		t.Run(test.name, func(t *testing.T) {
			env := newTrackedAudioEnv(t)
			env.setTags(test.tags)
			env.noProbe = test.noProbe
			manifest := env.manifest()
			if got := env.namesOf(manifest); !reflect.DeepEqual(got, test.want) {
				t.Fatalf("order = %q\nwant %q", got, test.want)
			}
			if len(manifest.Chapters) != 0 || len(manifest.Tracks) != 5 {
				t.Fatalf("a book without probe results still lists its tracks and no chapters: %+v", manifest)
			}
		})
	}
}

func TestAudioManifestTakesLengthsFromStorytellerAndFallsBackOnlyWhenTheyAreMissing(t *testing.T) {
	env := newAudioEnv(t, audioEnvOptions{}, func(root string) audioBuild {
		book, err := readingdomain.GenerateTrackedAudiobook(root)
		if err != nil {
			t.Fatal(err)
		}
		links := []audioLink{
			{Href: "Fixture Odyssey.mp3", Duration: 100.5},
			{Href: "Fixture Odyssey (1).mp3"}, // no length, no title
			{Href: "Fixture Odyssey (2).mp3", Title: "Chapter of its own", Duration: 300},
		}
		return audioBuild{json: storytellerAudiobook(t, "/library/"+book.Relative, links, false), book: book, links: links}
	})
	manifest := env.manifest()
	if len(manifest.Tracks) != 3 {
		t.Fatalf("tracks = %+v", manifest.Tracks)
	}
	// Lengths: Storyteller's where it has one, the probe's (777 ms here) where
	// it has none. Titles: Storyteller's, else "Track N" by place.
	for i, want := range []struct {
		ms    int64
		title string
	}{{100500, "Track 1"}, {777, "Track 2"}, {300000, "Chapter of its own"}} {
		if got := manifest.Tracks[i]; got.DurationMs != want.ms || got.Title != want.title {
			t.Errorf("track %d = %d ms %q, want %d ms %q", i, got.DurationMs, got.Title, want.ms, want.title)
		}
	}
	if manifest.TotalMs != 100500+777+300000 {
		t.Errorf("total = %d", manifest.TotalMs)
	}
}

func TestAudioManifestListsChaptersOnlyFromFilesWithTwoOrMoreMarks(t *testing.T) {
	env := newTrackedAudioEnv(t)
	second := "Fixture Odyssey (1).mp3"
	env.mu.Lock()
	env.chapters[second] = []probedChapter{{"One", 0, 400000}, {"Two", 400000, 800000}, {"", 800000, 1000500}}
	// One mark spanning a whole file is not a chapter list.
	env.chapters["Fixture Odyssey.mp3"] = []probedChapter{{"The whole file", 0, 4610652}}
	env.mu.Unlock()
	manifest := env.manifest()
	// The file's place in the served order, not Storyteller's.
	place := slices.Index(env.namesOf(manifest), second)
	want := []ReadingAudioChapter{{"One", 0, place}, {"Two", 400000, place}, {"Chapter 3", 800000, place}}
	if !reflect.DeepEqual(manifest.Chapters, want) {
		t.Fatalf("chapters = %+v, want %+v", manifest.Chapters, want)
	}
	if place != 1 {
		t.Fatalf("(1) is served second by its tag, got place %d", place)
	}

	// Marks that cannot be a chapter list of that file are not one.
	for name, marks := range map[string][]probedChapter{
		"marks that go backwards":   {{"A", 500, 600}, {"B", 100, 200}},
		"marks at the same moment":  {{"A", 100, 200}, {"B", 100, 300}},
		"all but one past the end":  {{"A", 0, 100}, {"B", 99999999999, 99999999999}},
		"nothing but a zero length": {{"A", 0, 0}},
	} {
		t.Run(name, func(t *testing.T) {
			env := newTrackedAudioEnv(t)
			env.mu.Lock()
			env.chapters[second] = marks
			env.mu.Unlock()
			if got := env.manifest().Chapters; len(got) != 0 {
				t.Fatalf("chapters = %+v", got)
			}
		})
	}
}

func TestAudioManifestOfALoneM4BIsOneTrackWithStorytellersChapters(t *testing.T) {
	env := newM4BAudioEnv(t, chapteredLinks)
	manifest := env.manifest()
	info, err := os.Stat(env.file("Fixture Chapters.m4b"))
	if err != nil {
		t.Fatal(err)
	}
	if len(manifest.Tracks) != 1 {
		t.Fatalf("tracks = %+v", manifest.Tracks)
	}
	track := manifest.Tracks[0]
	// The one track is the file, named by the book's own title (Storyteller's
	// chapter names are not file names), as long as Storyteller says the whole.
	if track.Index != 0 || track.ID != trackIDFor("Fixture Chapters.m4b") || track.Title != "Red Rising" ||
		track.DurationMs != 4200750 || track.Bytes != info.Size() || track.Mime != "audio/mp4" {
		t.Fatalf("track = %+v", track)
	}
	if manifest.TotalMs != 4200750 {
		t.Fatalf("total = %d", manifest.TotalMs)
	}
	want := []ReadingAudioChapter{{"Opening", 0, 0}, {"The Middle", 1500500, 0}, {"Track 3", 3300750, 0}}
	if !reflect.DeepEqual(manifest.Chapters, want) {
		t.Fatalf("chapters = %+v, want %+v", manifest.Chapters, want)
	}
	if env.probed.Load() != 0 {
		t.Errorf("a lone M4B's chapters come from the manifest, yet ffprobe ran %d times", env.probed.Load())
	}

	// One chapter link is one mark spanning the file: no chapter list.
	single := newM4BAudioEnv(t, chapteredLinks[:1])
	if got := single.manifest(); len(got.Chapters) != 0 || len(got.Tracks) != 1 || got.Tracks[0].DurationMs != 1500500 {
		t.Fatalf("one chapter link = %+v", got)
	}
}

func TestAudioManifestRevisionFollowsTheFilesAndNotTheRequest(t *testing.T) {
	env := newTrackedAudioEnv(t)
	first := env.manifest()
	again := env.manifest()
	if first.Revision != again.Revision {
		t.Fatalf("two reads of one book disagree: %s, %s", first.Revision, again.Revision)
	}
	env.forget()
	if got := env.manifest().Revision; got != first.Revision {
		t.Fatalf("a rescan of unchanged files changed the revision: %s, %s", first.Revision, got)
	}

	revision := first.Revision
	check := func(what string, change func()) {
		t.Helper()
		change()
		env.forget()
		got := env.manifest().Revision
		if got == revision {
			t.Errorf("%s did not change the revision", what)
		}
		revision = got
	}
	check("a file growing", func() { env.append("Fixture Odyssey (2).mp3", "more") })
	check("a file's modified time", func() {
		if err := os.Chtimes(env.file("Fixture Odyssey (2).mp3"), time.Now(), time.Now().Add(-90*time.Minute)); err != nil {
			t.Fatal(err)
		}
	})
	check("another order", func() {
		tags := map[string]int{}
		for i, name := range trackedManifestOrder {
			tags[name] = i + 1
		}
		env.setTags(tags)
		// Tags live inside the files and what was learned of a file is kept for as
		// long as the file is the same, so this stands for tags that were rewritten.
		env.server.probeMu.Lock()
		clear(env.server.probeCache)
		env.server.probeMu.Unlock()
	})
	check("a length", func() {
		links := append([]audioLink(nil), env.build.links...)
		links[0].Duration += 1
		env.state.audio = storytellerAudiobook(t, "/library/"+env.build.book.Relative, links, false)
	})
}

func TestAudioManifestSaysNothingOfTheHostAnywhere(t *testing.T) {
	check := func(what string, env *audioEnv, response *httptest.ResponseRecorder) {
		t.Helper()
		var everything strings.Builder
		everything.WriteString(response.Body.String())
		for name, values := range response.Header() {
			everything.WriteString(name + ": " + strings.Join(values, ",") + "\n")
		}
		text := everything.String()
		forbidden := []string{env.root, filepath.Base(env.root), "/library", `\\`, "Fixture", ".mp3", ".MP3", ".m4b", "Ünï", "פרק", "cover", "notes"}
		for _, file := range env.build.book.Files {
			forbidden = append(forbidden, file.Name)
		}
		for _, bad := range forbidden {
			if strings.Contains(text, bad) {
				t.Errorf("%s: the response holds %q:\n%s", what, bad, text)
			}
		}
	}

	tracked := newTrackedAudioEnv(t)
	check("tracked manifest", tracked, tracked.get(tracked.audioPath(tracked.child)))
	m4b := newM4BAudioEnv(t, chapteredLinks)
	check("m4b manifest", m4b, m4b.get(m4b.audioPath(m4b.child)))

	// Failures say why without saying where.
	missing := newTrackedAudioEnv(t)
	if err := os.Remove(missing.file("Fixture Odyssey (2).mp3")); err != nil {
		t.Fatal(err)
	}
	response := missing.get(missing.audioPath(missing.child))
	if response.Code != http.StatusConflict {
		t.Fatalf("missing file = %d", response.Code)
	}
	check("missing file", missing, response)
	unmapped := newAudioEnv(t, audioEnvOptions{roots: func(string) []config.MediaRemovalRoot { return nil }}, func(root string) audioBuild {
		book, err := readingdomain.GenerateTrackedAudiobook(root)
		if err != nil {
			t.Fatal(err)
		}
		links := []audioLink{{Href: book.Files[0].Name}}
		return audioBuild{json: storytellerAudiobook(t, "/library/"+book.Relative, links, false), book: book, links: links}
	})
	response = unmapped.get(unmapped.audioPath(unmapped.child))
	if response.Code != http.StatusConflict {
		t.Fatalf("unmapped = %d", response.Code)
	}
	check("unmapped root", unmapped, response)
}

func TestAudioManifestSaysWhyABookCannotBeStreamed(t *testing.T) {
	odyssey := func(links func(book readingdomain.AudiobookFixture) []audioLink, folder string) func(root string) audioBuild {
		return func(root string) audioBuild {
			book, err := readingdomain.GenerateTrackedAudiobook(root)
			if err != nil {
				t.Fatal(err)
			}
			if err := os.MkdirAll(filepath.Join(book.Dir, "folder.mp3"), 0o755); err != nil {
				t.Fatal(err)
			}
			list := links(book)
			where := folder
			switch where {
			case "":
				where = "/library/" + book.Relative
			case "none":
				where = ""
			}
			return audioBuild{json: storytellerAudiobook(t, where, list, false), book: book, links: list}
		}
	}
	first := func(book readingdomain.AudiobookFixture) audioLink { return audioLink{Href: book.Files[0].Name} }

	for _, test := range []struct {
		name    string
		options audioEnvOptions
		build   func(root string) audioBuild
		change  func(env *audioEnv)
		reason  string
	}{
		{"no mapping covers the folder", audioEnvOptions{roots: func(string) []config.MediaRemovalRoot { return nil }},
			odyssey(func(b readingdomain.AudiobookFixture) []audioLink { return []audioLink{first(b)} }, ""), nil, "unmapped_root"},
		{"the mapping is for another folder", audioEnvOptions{roots: func(root string) []config.MediaRemovalRoot {
			return []config.MediaRemovalRoot{{Service: "storyteller", Remote: "/elsewhere", Local: root}}
		}}, odyssey(func(b readingdomain.AudiobookFixture) []audioLink { return []audioLink{first(b)} }, ""), nil, "unmapped_root"},
		{"a file is gone", audioEnvOptions{}, odyssey(func(b readingdomain.AudiobookFixture) []audioLink {
			return []audioLink{first(b), {Href: "Not There.mp3"}}
		}, ""), nil, "missing_file"},
		{"the folder is gone", audioEnvOptions{}, odyssey(func(b readingdomain.AudiobookFixture) []audioLink { return []audioLink{first(b)} }, "/library/audiobooks/Nowhere"), nil, "missing_file"},
		{"a file Storyteller listed was deleted", audioEnvOptions{}, odyssey(func(b readingdomain.AudiobookFixture) []audioLink {
			return []audioLink{first(b), {Href: b.Files[1].Name}}
		}, ""), func(env *audioEnv) {
			if err := os.Remove(env.file(env.build.book.Files[1].Name)); err != nil {
				t.Fatal(err)
			}
		}, "missing_file"},
		{"a link to a cover", audioEnvOptions{}, odyssey(func(b readingdomain.AudiobookFixture) []audioLink {
			return []audioLink{first(b), {Href: "cover.jpg"}}
		}, ""), nil, "unsupported_layout"},
		{"a link that climbs", audioEnvOptions{}, odyssey(func(b readingdomain.AudiobookFixture) []audioLink {
			return []audioLink{{Href: "../Fixture Chapters/Fixture Chapters.m4b"}}
		}, ""), nil, "unsupported_layout"},
		{"an absolute link", audioEnvOptions{}, odyssey(func(b readingdomain.AudiobookFixture) []audioLink {
			return []audioLink{{Href: "/etc/passwd.mp3"}}
		}, ""), nil, "unsupported_layout"},
		{"a link to a folder", audioEnvOptions{}, odyssey(func(b readingdomain.AudiobookFixture) []audioLink {
			return []audioLink{first(b), {Href: "folder.mp3"}}
		}, ""), nil, "unsupported_layout"},
		{"a link with an empty name", audioEnvOptions{}, odyssey(func(b readingdomain.AudiobookFixture) []audioLink {
			return []audioLink{first(b), {Href: ""}}
		}, ""), nil, "unsupported_layout"},
		{"the same file listed twice", audioEnvOptions{}, odyssey(func(b readingdomain.AudiobookFixture) []audioLink {
			return []audioLink{first(b), first(b)}
		}, ""), nil, "unsupported_layout"},
		{"no links", audioEnvOptions{}, odyssey(func(b readingdomain.AudiobookFixture) []audioLink { return nil }, ""), nil, "unsupported_layout"},
		{"no folder", audioEnvOptions{}, odyssey(func(b readingdomain.AudiobookFixture) []audioLink { return []audioLink{first(b)} }, "none"), nil, "unsupported_layout"},
		{"a folder that is not absolute", audioEnvOptions{}, odyssey(func(b readingdomain.AudiobookFixture) []audioLink { return []audioLink{first(b)} }, "library/audiobooks"), nil, "unsupported_layout"},
	} {
		t.Run(test.name, func(t *testing.T) {
			env := newAudioEnv(t, test.options, test.build)
			if test.change != nil {
				test.change(env)
			}
			response := env.get(env.audioPath(env.child))
			if response.Code != http.StatusConflict {
				t.Fatalf("status = %d: %s", response.Code, response.Body.String())
			}
			got := decodeAudioError(t, response)
			if got.Code != "audio_not_streamable" || got.Reason != test.reason || got.Retryable || got.Message == "" {
				t.Fatalf("error = %+v, want audio_not_streamable / %s", got, test.reason)
			}
			if env.probed.Load() != 0 {
				t.Errorf("ffprobe ran %d times for a book that cannot be streamed", env.probed.Load())
			}
		})
	}
}

func TestAudioManifestOfALoneM4BNeedsExactlyOneM4BInItsFolder(t *testing.T) {
	for name, test := range map[string]struct {
		change func(env *audioEnv)
		reason string
	}{
		"its m4b is gone":      {func(env *audioEnv) { os.Remove(env.file("Fixture Chapters.m4b")) }, "missing_file"},
		"a second m4b arrived": {func(env *audioEnv) { os.WriteFile(env.file("Another.m4b"), []byte("x"), 0o644) }, "unsupported_layout"},
	} {
		t.Run(name, func(t *testing.T) {
			env := newM4BAudioEnv(t, chapteredLinks)
			test.change(env)
			response := env.get(env.audioPath(env.child))
			if response.Code != http.StatusConflict || decodeAudioError(t, response).Reason != test.reason {
				t.Fatalf("status %d: %s", response.Code, response.Body.String())
			}
		})
	}
}

func TestAudioManifestIsNotFoundWithoutAnAudiobookOrForAnotherBook(t *testing.T) {
	// An audiobook Storyteller no longer has the files of, and one it never had.
	missing := newAudioEnv(t, audioEnvOptions{}, func(root string) audioBuild {
		book, err := readingdomain.GenerateTrackedAudiobook(root)
		if err != nil {
			t.Fatal(err)
		}
		links := []audioLink{{Href: book.Files[0].Name}}
		return audioBuild{json: storytellerAudiobook(t, "/library/"+book.Relative, links, true), book: book, links: links}
	})
	none := newAudioEnv(t, audioEnvOptions{}, func(string) audioBuild { return audioBuild{} })
	for name, env := range map[string]*audioEnv{"missing": missing, "none": none} {
		for _, work := range []string{env.child, env.collection} {
			response := env.get(env.audioPath(work))
			if response.Code != http.StatusNotFound || decodeAudioError(t, response).Code != CodeNotFound {
				t.Errorf("%s via %s = %d: %s", name, work, response.Code, response.Body.String())
			}
		}
	}

	env := newTrackedAudioEnv(t)
	for path, want := range map[string]int{
		"/v1/reading/works/" + env.child + "/publications/999/audio":                                  http.StatusNotFound,
		"/v1/reading/works/rw_00000000000000000000000000000000/publications/12/audio":                 http.StatusNotFound,
		"/v1/reading/works/not-a-work/publications/12/audio":                                          http.StatusBadRequest,
		"/v1/reading/works/" + env.child + "/publications/twelve/audio":                               http.StatusBadRequest,
		"/v1/reading/works/" + env.child + "/publications/0/audio":                                    http.StatusBadRequest,
		"/v1/reading/works/" + env.child + "/publications/12/audio/":                                  http.StatusNotFound,
		"/v1/reading/works/" + env.child + "/publications/12/audio/tracks":                            http.StatusNotFound,
		"/v1/reading/works/" + env.child + "/publications/12/audio?format=ebook":                      http.StatusOK,
		"/v1/reading/works/" + env.child + "/publications/12/audio?format=audiobook&rev=whatever&x=1": http.StatusOK,
	} {
		if got := env.get(path).Code; got != want {
			t.Errorf("%s = %d, want %d", path, got, want)
		}
	}
}

func TestAudioManifestNeedsTheReadingScope(t *testing.T) {
	env := newAudioEnv(t, audioEnvOptions{scopes: []string{"read"}}, func(root string) audioBuild {
		book, err := readingdomain.GenerateTrackedAudiobook(root)
		if err != nil {
			t.Fatal(err)
		}
		return audioBuild{book: book}
	})
	response := env.get("/v1/reading/works/rw_00000000000000000000000000000000/publications/12/audio")
	if response.Code != http.StatusForbidden || decodeAudioError(t, response).Code != CodeForbiddenScope {
		t.Fatalf("without the scope = %d: %s", response.Code, response.Body.String())
	}
}

func TestAudioManifestIsUnavailableWhileStorytellerIs(t *testing.T) {
	env := newTrackedAudioEnv(t)
	env.upstream.Close()
	response := env.get(env.audioPath(env.child))
	if response.Code != http.StatusServiceUnavailable || decodeAudioError(t, response).Code != CodeUpstreamDown {
		t.Fatalf("with Storyteller down = %d: %s", response.Code, response.Body.String())
	}

	// And when the hub was never given Storyteller at all.
	cfg := readingCatalogConfig("http://127.0.0.1:1", filepath.Join(t.TempDir(), "catalog.json"), []string{"reading"})
	delete(cfg.Services, "storyteller")
	handler := NewServer(cfg).Handler()
	response = libraryRequest(handler, "/v1/reading/works/rw_00000000000000000000000000000000/publications/12/audio")
	if response.Code != http.StatusServiceUnavailable {
		t.Fatalf("without Storyteller configured = %d: %s", response.Code, response.Body.String())
	}
}

func TestAudioManifestIsHeldForAMinuteAndClearedWithItsWork(t *testing.T) {
	env := newTrackedAudioEnv(t)
	now := time.Now()
	env.server.cache.WithClock(func() time.Time { return now })

	first := env.manifest()
	if first.Cache.Hit {
		t.Errorf("a manifest built just now reports a cache hit: %+v", first.Cache)
	}
	env.append("Fixture Odyssey (2).mp3", "changed on disk")
	if cached := env.manifest(); cached.Revision != first.Revision || !cached.Cache.Hit || cached.Cache.Stale {
		t.Fatalf("inside its minute the manifest is the one built: %+v", cached)
	}

	now = now.Add(59 * time.Second)
	if cached := env.manifest(); cached.Revision != first.Revision {
		t.Fatalf("at 59 s the manifest was rebuilt")
	}
	now = now.Add(2 * time.Second)
	second := env.manifest()
	if second.Revision == first.Revision {
		t.Fatalf("after a minute the manifest was not rebuilt")
	}

	// A rescan or a deletion expires everything the hub knows of Storyteller.
	env.append("Fixture Odyssey (2).mp3", "and again")
	if cached := env.manifest(); cached.Revision != second.Revision {
		t.Fatalf("the manifest was rebuilt without a reason")
	}
	env.server.invalidateReadingCatalog()
	third := env.manifest()
	if third.Revision == second.Revision {
		t.Fatalf("clearing the reading caches kept the manifest")
	}

	// So does anything that drops the work's own record.
	env.append("Fixture Odyssey (2).mp3", "once more")
	env.server.invalidateStorytellerWork("12")
	if fourth := env.manifest(); fourth.Revision == third.Revision {
		t.Fatalf("invalidating the work kept the manifest")
	}
}

func TestAudioManifestProbesEachUnchangedFileOnce(t *testing.T) {
	env := newTrackedAudioEnv(t)
	env.manifest()
	if got := env.probed.Load(); got != 5 {
		t.Fatalf("probed %d files for a book of five", got)
	}
	env.forget()
	env.manifest()
	if got := env.probed.Load(); got != 5 {
		t.Fatalf("a rescan of unchanged files probed again: %d", got)
	}
	env.append("Fixture Odyssey (1).mp3", "grown")
	env.forget()
	env.manifest()
	if got := env.probed.Load(); got != 6 {
		t.Fatalf("one changed file made %d probes in all, want 6", got)
	}
}

func TestAudioManifestHasTheDocumentedFields(t *testing.T) {
	keys := func(value map[string]any) []string {
		out := make([]string, 0, len(value))
		for key := range value {
			out = append(out, key)
		}
		sort.Strings(out)
		return out
	}
	env := newM4BAudioEnv(t, chapteredLinks)
	var body map[string]any
	if err := json.Unmarshal(env.get(env.audioPath(env.child)).Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	want := []string{"aligned", "cache", "chapters", "narrator", "revision", "sourceItemId", "totalMs", "tracks", "workId"}
	if got := keys(body); !reflect.DeepEqual(got, want) {
		t.Errorf("manifest keys = %v, want %v", got, want)
	}
	track := body["tracks"].([]any)[0].(map[string]any)
	if got, want := keys(track), []string{"bytes", "durationMs", "etag", "id", "index", "mime", "title"}; !reflect.DeepEqual(got, want) {
		t.Errorf("track keys = %v, want %v", got, want)
	}
	chapter := body["chapters"].([]any)[0].(map[string]any)
	if got, want := keys(chapter), []string{"startMs", "title", "track"}; !reflect.DeepEqual(got, want) {
		t.Errorf("chapter keys = %v, want %v", got, want)
	}
	if cache := body["cache"].(map[string]any); cache["hit"] != false {
		t.Errorf("cache = %v", cache)
	}
}

// Storyteller writes a track's href as the file's name. Should a version write
// it as a URL reference instead, percent-encoded, the files are still found:
// the name as given is tried first (so "100% Pure.mp3" stays itself), and the
// decoded one only when that is not there.
func TestAudioManifestFindsFilesWhoseLinksArePercentEncoded(t *testing.T) {
	env := newAudioEnv(t, audioEnvOptions{}, func(root string) audioBuild {
		book, err := readingdomain.GenerateTrackedAudiobook(root)
		if err != nil {
			t.Fatal(err)
		}
		links := make([]audioLink, len(book.Files))
		for i, file := range book.Files {
			links[i] = audioLink{Href: url.PathEscape(file.Name), Duration: 100 + float64(i)}
		}
		return audioBuild{json: storytellerAudiobook(t, "/library/"+book.Relative, links, false), book: book, links: links}
	})
	manifest := env.manifest()
	if len(manifest.Tracks) != 5 {
		t.Fatalf("tracks = %+v", manifest.Tracks)
	}
	for i, file := range env.build.book.Files {
		track := manifest.Tracks[i]
		// Identified by the name Storyteller gave, encoded or not.
		if track.Bytes != file.Size || track.ID != trackIDFor(env.build.links[i].Href) {
			t.Errorf("track %d = %+v, want %d bytes", i, track, file.Size)
		}
	}
}

// Windows ignores case, so two names can be one file; playing it twice would
// make the book longer than it is.
func TestAudioManifestRefusesTwoLinksToOneFile(t *testing.T) {
	env := newAudioEnv(t, audioEnvOptions{}, func(root string) audioBuild {
		book, err := readingdomain.GenerateTrackedAudiobook(root)
		if err != nil {
			t.Fatal(err)
		}
		links := []audioLink{{Href: "Fixture Odyssey.mp3"}, {Href: "FIXTURE ODYSSEY.MP3"}}
		return audioBuild{json: storytellerAudiobook(t, "/library/"+book.Relative, links, false), book: book, links: links}
	})
	if _, err := os.Stat(env.file("FIXTURE ODYSSEY.MP3")); err != nil {
		t.Skip("this file system tells names apart by case, so there is only one way to say it")
	}
	response := env.get(env.audioPath(env.child))
	if response.Code != http.StatusConflict || decodeAudioError(t, response).Reason != "unsupported_layout" {
		t.Fatalf("two names for one file = %d: %s", response.Code, response.Body.String())
	}
}

// The whole path with the real tool, when there is one: the generated book's
// own TRCK tags, read by ffprobe, put its files in the story's order.
func TestAudioManifestWithTheRealFFprobeOrdersTheGeneratedBookByItsTags(t *testing.T) {
	if _, err := findFFprobe(); err != nil {
		t.Skip("no ffprobe on this machine")
	}
	env := newTrackedAudioEnv(t)
	env.server.probeAudio = runFFprobe
	// Real processes on a machine that may be busy: a probe that timed out would
	// leave the order to Storyteller's manifest, which is a different test.
	env.server.probeTimeout = patient
	manifest := env.manifest()
	if got := env.namesOf(manifest); !reflect.DeepEqual(got, trackedTagOrder) {
		t.Fatalf("order = %q\nwant %q", got, trackedTagOrder)
	}
	if len(manifest.Chapters) != 0 {
		t.Fatalf("one-second narrations have no chapters: %+v", manifest.Chapters)
	}
}
