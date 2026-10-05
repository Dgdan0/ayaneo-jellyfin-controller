package api

import (
	"encoding/json"
	"fmt"
	"math"
	"net/http"
	"net/http/httptest"
	"os"
	"reflect"
	"slices"
	"sort"
	"strings"
	"sync"
	"testing"
	"time"
)

// positionEnv is an audiobook with Storyteller's real position table behind it
// and a clock the test sets.
type positionEnv struct {
	*audioEnv
	positions *storytellerPositions
	clock     *testClock
}

type testClock struct {
	mu sync.Mutex
	at time.Time
}

func (c *testClock) now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.at
}

func (c *testClock) set(at time.Time) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.at = at
}

// The moment the hub's clock reads, in milliseconds.
const clockStart = int64(1_800_000_000_000)

func withPositions(env *audioEnv) *positionEnv {
	positions := &storytellerPositions{}
	env.state.positions = positions
	clock := &testClock{at: time.UnixMilli(clockStart)}
	env.server.now = clock.now
	return &positionEnv{audioEnv: env, positions: positions, clock: clock}
}

// newPositionEnv is the tracked book with a read-along edition.
func newPositionEnv(t *testing.T) *positionEnv {
	t.Helper()
	return withPositions(newAlignedTrackedEnv(t, alignedOptions{}))
}

func (e *positionEnv) positionPath() string { return e.audioPath(e.child) + "/position" }

func (e *positionEnv) post(body string) *httptest.ResponseRecorder {
	e.t.Helper()
	return publicationRequest(e.handler, http.MethodPost, e.positionPath(), body)
}

func (e *positionEnv) read() ReadingAudioPositionResponse {
	e.t.Helper()
	response := e.get(e.positionPath())
	if response.Code != http.StatusOK {
		e.t.Fatalf("position = %d: %s", response.Code, response.Body.String())
	}
	var body ReadingAudioPositionResponse
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
		e.t.Fatal(err)
	}
	return body
}

func (e *positionEnv) position() *ReadingAudioPosition {
	e.t.Helper()
	return e.read().Position
}

// write saves a place by file name and offset and fails the test if it is not
// accepted.
func (e *positionEnv) write(name string, offsetMs int64) {
	e.t.Helper()
	if response := e.post(placeBody(trackIDFor(name), offsetMs, "")); response.Code != http.StatusOK {
		e.t.Fatalf("write %s @ %d = %d: %s", name, offsetMs, response.Code, response.Body.String())
	}
}

// placeBody is a POST body: a track, an offset and anything else the test adds
// after them (`,"completed":true`).
func placeBody(trackID string, offsetMs int64, extra string) string {
	return fmt.Sprintf(`{"trackId":%q,"offsetMs":%d%s}`, trackID, offsetMs, extra)
}

// savedLocator is what Storyteller holds after a write, as its audio apps read it.
type savedLocator struct {
	Href      string `json:"href"`
	Title     string `json:"title"`
	Type      string `json:"type"`
	Locations struct {
		Fragments        []string `json:"fragments"`
		Progression      float64  `json:"progression"`
		TotalProgression float64  `json:"totalProgression"`
	} `json:"locations"`
}

func (e *positionEnv) stored() (savedLocator, int64) {
	e.t.Helper()
	raw, timestamp, has := e.positions.stored()
	if !has {
		e.t.Fatal("Storyteller holds no position")
	}
	var locator savedLocator
	if err := json.Unmarshal(raw, &locator); err != nil {
		e.t.Fatal(err)
	}
	return locator, timestamp
}

func near(a, b float64) bool { return math.Abs(a-b) < 1e-9 }

// manifestProgress is how far through the book a place is as Storyteller's apps
// work it out: the lengths of the files before it in the manifest's order, plus
// the offset, over the lengths of all of them.
func manifestProgress(name string, offsetMs int64) float64 {
	var before, total int64
	for _, listed := range trackedManifestOrder {
		length := int64(trackedDurationSeconds[listed]*1000 + 0.5)
		if listed == name {
			before = total
		}
		total += length
	}
	return float64(before+offsetMs) / float64(total)
}

func trackedLength(name string) int64 { return int64(trackedDurationSeconds[name]*1000 + 0.5) }

func clock3(ms int64) string { return fmt.Sprintf("t=%d.%03d", ms/1000, ms%1000) }

func TestAudioPositionIsNullUntilSomethingIsSaved(t *testing.T) {
	env := newPositionEnv(t)
	for _, work := range []string{env.child, env.collection} {
		response := env.get(env.audioPath(work) + "/position")
		if response.Code != http.StatusOK || !strings.Contains(response.Body.String(), `"position":null`) {
			t.Fatalf("an empty slot for %s = %d: %s", work, response.Code, response.Body.String())
		}
		var body ReadingAudioPositionResponse
		if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil || body.WorkID != work || body.SourceItemID != "12" || body.Position != nil {
			t.Fatalf("body = %+v, %v", body, err)
		}
	}
}

// Storyteller's own apps write the audio form with the files in its manifest's
// order, and read it back the same way: that is what makes a place written here
// a place there, whatever order the hub plays the files in.
func TestAudioPositionIsWrittenInStorytellersAudioFormInManifestOrder(t *testing.T) {
	env := newPositionEnv(t)
	for _, test := range []struct {
		name   string
		offset int64
	}{
		{"Fixture Odyssey.mp3", 1_234_567}, // the story's first file, last in the manifest
		{"Fixture Odyssey (2).mp3", 777_001},
		{"Part 5 - 100% Pure & Co., It's #5 [Ünïcode] פרק.mp3", 0},
		{"Fixture Odyssey (3).MP3", 2_500_000},
		{"Fixture Odyssey (1).mp3", 999},
	} {
		env.write(test.name, test.offset)
		got, _ := env.stored()
		link := env.linkFor(test.name)
		if got.Href != test.name || got.Type != "audio/mpeg" || got.Title != link.Title {
			t.Errorf("%s: href %q type %q title %q", test.name, got.Href, got.Type, got.Title)
		}
		if want := []string{clock3(test.offset)}; !reflect.DeepEqual(got.Locations.Fragments, want) {
			t.Errorf("%s: fragments %v, want %v", test.name, got.Locations.Fragments, want)
		}
		length := trackedLength(test.name)
		if !near(got.Locations.Progression, float64(test.offset)/float64(length)) {
			t.Errorf("%s: progression %v", test.name, got.Locations.Progression)
		}
		if want := manifestProgress(test.name, test.offset); !near(got.Locations.TotalProgression, want) {
			t.Errorf("%s: totalProgression %v, want %v", test.name, got.Locations.TotalProgression, want)
		}
	}
}

func TestAudioPositionComesBackExactlyAsWritten(t *testing.T) {
	env := newPositionEnv(t)
	cumulative := map[string]int64{}
	var sum int64
	for _, name := range trackedTagOrder {
		cumulative[name] = sum
		sum += trackedLength(name)
	}
	last := trackedTagOrder[len(trackedTagOrder)-1]
	for _, name := range trackedTagOrder {
		for _, offset := range []int64{0, 1, 999, 1000, 500_123, trackedLength(name)} {
			env.write(name, offset)
			got := env.position()
			if got == nil {
				t.Fatalf("%s @ %d: no position", name, offset)
			}
			if got.TrackID != trackIDFor(name) || got.Track != slices.Index(trackedTagOrder, name) || got.OffsetMs != offset {
				t.Errorf("%s @ %d came back as %+v", name, offset, got)
			}
			if got.GlobalMs != cumulative[name]+offset || !got.Exact || got.Form != "audio" {
				t.Errorf("%s @ %d: global %d exact %v form %q", name, offset, got.GlobalMs, got.Exact, got.Form)
			}
			if want := name == last && offset >= trackedLength(name)-2000; got.Completed != want {
				t.Errorf("%s @ %d: completed %v, want %v", name, offset, got.Completed, want)
			}
			_, stamped := env.stored()
			if got.Timestamp != stamped || got.Timestamp < clockStart {
				t.Errorf("timestamp %d, Storyteller holds %d", got.Timestamp, stamped)
			}
		}
	}
}

func TestAudioPositionOfALoneM4BIsWrittenAsItsVirtualChapterFile(t *testing.T) {
	env := withPositions(newAlignedM4BEnv(t))
	id := trackIDFor("Fixture Chapters.m4b")
	total := int64(4_200_750)
	for _, test := range []struct {
		offset  int64
		href    string
		t       int64 // inside the chapter
		chapter int64 // the chapter's length
	}{
		{0, "00000-00001.mp3", 0, 1_500_500},
		{1_500_499, "00000-00001.mp3", 1_500_499, 1_500_500},
		{1_500_500, "00001-00001.mp3", 0, 1_800_250},
		{2_000_000, "00001-00001.mp3", 499_500, 1_800_250},
		{3_300_750, "00002-00001.mp3", 0, 900_000},
		{total, "00002-00001.mp3", 900_000, 900_000},
	} {
		if response := env.post(placeBody(id, test.offset, "")); response.Code != http.StatusOK {
			t.Fatalf("write %d = %d: %s", test.offset, response.Code, response.Body.String())
		}
		got, _ := env.stored()
		if got.Href != test.href || got.Type != "audio/mpeg" || !reflect.DeepEqual(got.Locations.Fragments, []string{clock3(test.t)}) {
			t.Errorf("offset %d: %q %q %v", test.offset, got.Href, got.Type, got.Locations.Fragments)
		}
		if !near(got.Locations.Progression, float64(test.t)/float64(test.chapter)) || !near(got.Locations.TotalProgression, float64(test.offset)/float64(total)) {
			t.Errorf("offset %d: progression %v total %v", test.offset, got.Locations.Progression, got.Locations.TotalProgression)
		}
		back := env.position()
		if back == nil || back.TrackID != id || back.Track != 0 || back.OffsetMs != test.offset || back.GlobalMs != test.offset || !back.Exact {
			t.Errorf("offset %d came back as %+v", test.offset, back)
		}
	}
}

// Finishing is not a place in the middle of a file: it is the whole book, which
// Storyteller's apps mark by a total of 1. It is written at the end of the last
// track the hub plays, so that is where it reads back; in the manifest's order
// that file may be anywhere, and the end of a file that is not the story's last
// must not look like the end of the book.
func TestAudioPositionCompletedWritesTheEndOfTheBook(t *testing.T) {
	env := newPositionEnv(t)
	part5 := "Part 5 - 100% Pure & Co., It's #5 [Ünïcode] פרק.mp3"
	if response := env.post(placeBody(trackIDFor("Fixture Odyssey.mp3"), 10, `,"completed":true`)); response.Code != http.StatusOK {
		t.Fatalf("completed = %d: %s", response.Code, response.Body.String())
	}
	got, _ := env.stored()
	if got.Href != part5 || !reflect.DeepEqual(got.Locations.Fragments, []string{"t=3000.250"}) ||
		got.Locations.Progression != 1 || got.Locations.TotalProgression != 1 {
		t.Fatalf("stored %+v", got)
	}
	back := env.position()
	if back == nil || !back.Completed || back.TrackID != trackIDFor(part5) || back.Track != 4 || back.OffsetMs != 3_000_250 || !back.Exact {
		t.Fatalf("back = %+v", back)
	}

	m4b := withPositions(newAlignedM4BEnv(t))
	if response := m4b.post(placeBody(trackIDFor("Fixture Chapters.m4b"), 5, `,"completed":true`)); response.Code != http.StatusOK {
		t.Fatalf("completed M4B = %d", response.Code)
	}
	got, _ = m4b.stored()
	if got.Href != "00002-00001.mp3" || !reflect.DeepEqual(got.Locations.Fragments, []string{"t=900.000"}) || got.Locations.TotalProgression != 1 {
		t.Fatalf("stored for the M4B %+v", got)
	}
	if back := m4b.position(); back == nil || !back.Completed || back.OffsetMs != 4_200_750 {
		t.Fatalf("back for the M4B = %+v", back)
	}
}

// The places Storyteller's own audio apps write: its manifest's file, however
// it spells the name, and a time in it.
func TestAudioPositionReadsAudioPlacesAsStorytellersAppsWriteThem(t *testing.T) {
	part5 := "Part 5 - 100% Pure & Co., It's #5 [Ünïcode] פרק.mp3"
	for _, test := range []struct {
		name    string
		locator string
		track   int
		offset  int64
	}{
		{"an encoded name", `{"href":"Fixture%20Odyssey%20(1).mp3","type":"audio/mpeg","locations":{"fragments":["t=12.5"],"progression":0.0125,"totalProgression":0.3}}`, 1, 12_500},
		{"a name with a leading slash", `{"href":"/Fixture Odyssey (3).MP3","locations":{"fragments":["t=3"],"totalProgression":0.3}}`, 3, 3000},
		{"a name that holds a percent sign", `{"href":` + jsonString(part5) + `,"locations":{"fragments":["t=100.001"],"totalProgression":0.3}}`, 4, 100_001},
		{"the same name encoded", `{"href":"` + strings.ReplaceAll(strings.ReplaceAll(urlEscape(part5), `\`, `\\`), `"`, `\"`) + `","locations":{"fragments":["t=1"],"totalProgression":0.3}}`, 4, 1000},
		{"no time, only a progression", `{"href":"Fixture Odyssey (2).mp3","locations":{"progression":0.5,"totalProgression":0.3}}`, 2, 1_000_375},
		{"a time past the end", `{"href":"Fixture Odyssey (1).mp3","locations":{"fragments":["t=99999"],"totalProgression":0.3}}`, 1, 1_000_500},
		{"a media fragment with an end", `{"href":"Fixture Odyssey (1).mp3","locations":{"fragments":["t=5,10"],"totalProgression":0.3}}`, 1, 5000},
		{"a media fragment with a clock name", `{"href":"Fixture Odyssey (1).mp3","locations":{"fragments":["t=npt:6.25"],"totalProgression":0.3}}`, 1, 6250},
		{"neither a time nor a progression", `{"href":"Fixture Odyssey (1).mp3","locations":{"totalProgression":0.3}}`, 1, 0},
		{"a negative time", `{"href":"Fixture Odyssey (1).mp3","locations":{"fragments":["t=-4"],"progression":0.5,"totalProgression":0.3}}`, 1, 500_250},
	} {
		t.Run(test.name, func(t *testing.T) {
			env := withPositions(newTrackedAudioEnv(t))
			env.positions.seed(test.locator, clockStart-1000)
			got := env.position()
			if got == nil {
				t.Fatal("no position")
			}
			if got.Track != test.track || got.TrackID != trackIDFor(trackedTagOrder[test.track]) || got.OffsetMs != test.offset || !got.Exact || got.Form != "audio" {
				t.Fatalf("read as %+v, want track %d at %d", got, test.track, test.offset)
			}
			if got.Timestamp != clockStart-1000 {
				t.Fatalf("timestamp %d is not Storyteller's", got.Timestamp)
			}
		})
	}
}

func jsonString(text string) string {
	out, _ := json.Marshal(text)
	return string(out)
}

func urlEscape(text string) string {
	// The kind of encoding a URL reference gets: everything but unreserved.
	var out strings.Builder
	for _, b := range []byte(text) {
		switch {
		case b >= 'a' && b <= 'z', b >= 'A' && b <= 'Z', b >= '0' && b <= '9', b == '-', b == '.', b == '_', b == '~':
			out.WriteByte(b)
		default:
			fmt.Fprintf(&out, "%%%02X", b)
		}
	}
	return out.String()
}

// alignedTruth is where each sentence of the generated edition is in the book,
// worked out from the manifest's own alignment list and the edition's own times.
type alignedSentence struct {
	href, fragment string
	track          int
	offset         int64
}

func (e *positionEnv) sentences() []alignedSentence {
	e.t.Helper()
	manifest := e.manifest()
	where := map[string]ReadingAlignedAudio{}
	for _, audio := range manifest.Alignment.Audio {
		where[audio.Href] = audio
	}
	var out []alignedSentence
	for _, par := range e.build.epub.Pars {
		audio, ok := where[par.Audio]
		if !ok {
			e.t.Fatalf("%s is not in the manifest's alignment", par.Audio)
		}
		out = append(out, alignedSentence{href: par.Text, fragment: par.Fragment, track: audio.Track, offset: audio.StartMs + par.BeginMs})
	}
	return out
}

func TestAudioPositionReadsTextPlacesOfAnAlignedBookExactly(t *testing.T) {
	env := newPositionEnv(t)
	truth := env.sentences()
	if len(truth) != 26 {
		t.Fatalf("the generated edition has %d sentences", len(truth))
	}
	for _, sentence := range truth {
		locator := fmt.Sprintf(`{"href":%s,"type":"application/xhtml+xml","locations":{"fragments":[%s],"progression":0.3,"totalProgression":0.4}}`, jsonString(sentence.href), jsonString(sentence.fragment))
		env.positions.seed(locator, clockStart-1)
		got := env.position()
		if got == nil || got.Track != sentence.track || got.OffsetMs != sentence.offset || !got.Exact || got.Form != "text" || got.TrackID != trackIDFor(trackedTagOrder[sentence.track]) {
			t.Fatalf("%s#%s read as %+v, want track %d at %d", sentence.href, sentence.fragment, got, sentence.track, sentence.offset)
		}
		if got.Sentence == nil || got.Sentence.Href != sentence.href || got.Sentence.Fragment != sentence.fragment {
			t.Fatalf("%s#%s names the sentence %+v", sentence.href, sentence.fragment, got.Sentence)
		}
	}

	// However the locator spells the document: relative to the package, with a
	// leading slash, the fragment on the href.
	chosen := truth[17]
	for name, locator := range map[string]string{
		"relative to the package":  fmt.Sprintf(`{"href":%s,"locations":{"fragments":[%q]}}`, jsonString(strings.TrimPrefix(chosen.href, "OEBPS/")), chosen.fragment),
		"a leading slash":          fmt.Sprintf(`{"href":%s,"locations":{"fragments":[%q]}}`, jsonString("/"+chosen.href), chosen.fragment),
		"the fragment on the href": fmt.Sprintf(`{"href":%s,"locations":{}}`, jsonString(chosen.href+"#"+chosen.fragment)),
	} {
		env.positions.seed(locator, clockStart-1)
		// A locator needs a totalProgression only for the guess; a sentence that
		// is found needs none.
		got := env.position()
		if got == nil || got.Track != chosen.track || got.OffsetMs != chosen.offset || !got.Exact {
			t.Errorf("%s: read as %+v, want track %d at %d", name, got, chosen.track, chosen.offset)
		}
	}
}

func TestAudioPositionNamesTheSentenceOfAnAudioPlace(t *testing.T) {
	env := newPositionEnv(t)
	truth := env.sentences()
	for _, sentence := range truth {
		name := trackedTagOrder[sentence.track]
		// The moment it begins, and a moment inside it.
		for _, offset := range []int64{sentence.offset, sentence.offset + 1} {
			env.write(name, offset)
			got := env.position()
			if got == nil || got.Sentence == nil || got.Sentence.Href != sentence.href || got.Sentence.Fragment != sentence.fragment {
				t.Fatalf("%s @ %d names %+v, want %s#%s", name, offset, got, sentence.href, sentence.fragment)
			}
		}
	}
	// Before the first sentence of a file and after the last.
	env.write("Fixture Odyssey (2).mp3", 0)
	if got := env.position(); got.Sentence == nil || got.Sentence.Fragment != "id3-s0" {
		t.Fatalf("the start of (2) names %+v", got.Sentence)
	}
	env.write("Fixture Odyssey (2).mp3", trackedLength("Fixture Odyssey (2).mp3"))
	if got := env.position(); got.Sentence == nil || got.Sentence.Fragment != "id3-s5" {
		t.Fatalf("the end of (2), the last sentence of its second chunk, names %+v", got.Sentence)
	}

	// Without an edition there is nothing to name.
	plain := withPositions(newTrackedAudioEnv(t))
	plain.write("Fixture Odyssey (2).mp3", 5000)
	if got := plain.position(); got == nil || got.Sentence != nil || !got.Exact {
		t.Fatalf("a book with no read-along edition = %+v", got)
	}
}

// Where a text place cannot be found in the edition, or there is no edition, the
// whole book's proportion is the best there is, and it says so.
func TestAudioPositionEstimatesWhereItCannotBeExact(t *testing.T) {
	// 0.5 of 13112152 ms is 6556076, which is 944924 into the story's third
	// file: the files before it are 4610652 and 1000500 long.
	const wantGlobal, wantOffset = int64(6_556_076), int64(944_924)
	for _, test := range []struct {
		name    string
		env     func(t *testing.T) *positionEnv
		locator string
	}{
		{"a text place of a book with no edition", func(t *testing.T) *positionEnv { return withPositions(newTrackedAudioEnv(t)) },
			`{"href":"chapter-4.xhtml","type":"application/xhtml+xml","locations":{"progression":0.4,"totalProgression":0.5}}`},
		{"a text place with no sentence in it", func(t *testing.T) *positionEnv { return newPositionEnv(t) },
			`{"href":"OEBPS/text/part0002.xhtml","locations":{"progression":0.4,"totalProgression":0.5}}`},
		{"a sentence the edition does not have", func(t *testing.T) *positionEnv { return newPositionEnv(t) },
			`{"href":"OEBPS/text/part0002.xhtml","locations":{"fragments":["nowhere"],"totalProgression":0.5}}`},
		{"an audio place of a file the book does not have", func(t *testing.T) *positionEnv { return newPositionEnv(t) },
			`{"href":"Renamed.mp3","type":"audio/mpeg","locations":{"fragments":["t=7"],"totalProgression":0.5}}`},
	} {
		t.Run(test.name, func(t *testing.T) {
			env := test.env(t)
			env.positions.seed(test.locator, clockStart-1)
			got := env.position()
			if got == nil || got.Exact || got.GlobalMs != wantGlobal || got.Track != 2 || got.OffsetMs != wantOffset || got.Sentence != nil {
				t.Fatalf("estimated as %+v, want track 2 at %d (global %d), not exact", got, wantOffset, wantGlobal)
			}
		})
	}

	env := withPositions(newTrackedAudioEnv(t))
	for name, locator := range map[string]string{
		"no totalProgression":       `{"href":"chapter-4.xhtml","locations":{"progression":0.4}}`,
		"not an object":             `"chapter-4"`,
		"an array":                  `[]`,
		"no locations":              `{"href":"chapter-4.xhtml"}`,
		"a total that is no number": `{"href":"x","locations":{"totalProgression":"half"}}`,
	} {
		env.positions.seed(locator, clockStart-1)
		if got := env.position(); got != nil {
			t.Errorf("%s: %+v", name, got)
		}
	}

	// The start and the end of the book.
	env.positions.seed(`{"href":"chapter-1.xhtml","locations":{"totalProgression":0}}`, clockStart-1)
	if got := env.position(); got == nil || got.Track != 0 || got.OffsetMs != 0 || got.Exact {
		t.Errorf("the start = %+v", got)
	}
	env.positions.seed(`{"href":"chapter-9.xhtml","locations":{"totalProgression":1}}`, clockStart-1)
	if got := env.position(); got == nil || got.Track != 4 || got.OffsetMs != 3_000_250 || !got.Completed || got.Exact {
		t.Errorf("the end = %+v", got)
	}
	// A place the hub knows exactly is finished where the book ends in the order
	// the hub plays it, whatever a total taken in the manifest's order says: the
	// manifest's last file is the story's first here.
	env.positions.seed(`{"href":"Fixture Odyssey.mp3","locations":{"fragments":["t=4610.652"],"progression":1,"totalProgression":1}}`, clockStart-1)
	if got := env.position(); got == nil || got.Completed || got.Track != 0 || got.OffsetMs != 4_610_652 || !got.Exact {
		t.Errorf("the end of the story's first file = %+v", got)
	}
	env.positions.seed(`{"href":"Part 5 - 100% Pure & Co., It's #5 [Ünïcode] פרק.mp3","locations":{"fragments":["t=2999"],"totalProgression":0.2}}`, clockStart-1)
	if got := env.position(); got == nil || !got.Completed || got.Track != 4 || got.OffsetMs != 2_999_000 {
		t.Errorf("a second from the end of the last file = %+v", got)
	}
}

func TestAudioPositionHasTheDocumentedFields(t *testing.T) {
	keys := func(value map[string]any) []string {
		out := make([]string, 0, len(value))
		for key := range value {
			out = append(out, key)
		}
		sort.Strings(out)
		return out
	}
	env := newPositionEnv(t)
	env.write("Fixture Odyssey (1).mp3", 4000)
	var body map[string]any
	if err := json.Unmarshal(env.get(env.positionPath()).Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if got, want := keys(body), []string{"position", "sourceItemId", "workId"}; !reflect.DeepEqual(got, want) {
		t.Errorf("keys = %v, want %v", got, want)
	}
	position := body["position"].(map[string]any)
	want := []string{"completed", "exact", "form", "globalMs", "offsetMs", "sentence", "timestamp", "track", "trackId", "updatedAt"}
	if got := keys(position); !reflect.DeepEqual(got, want) {
		t.Errorf("position keys = %v, want %v", got, want)
	}
	if got, want := keys(position["sentence"].(map[string]any)), []string{"fragment", "href"}; !reflect.DeepEqual(got, want) {
		t.Errorf("sentence keys = %v, want %v", got, want)
	}

	// The reply to a write is the EPUB route's, with the time it was stamped.
	var saved map[string]any
	response := env.post(placeBody(trackIDFor("Fixture Odyssey (1).mp3"), 5000, ""))
	if err := json.Unmarshal(response.Body.Bytes(), &saved); err != nil {
		t.Fatal(err)
	}
	if saved["ok"] != true || saved["action"] != "save_audio_position" || saved["timestamp"] == nil || len(saved) != 3 {
		t.Errorf("reply = %v", saved)
	}
}

func TestAudioPositionConflictIsTheEPUBRoutesConflict(t *testing.T) {
	env := newPositionEnv(t)
	env.write("Fixture Odyssey (1).mp3", 10_000)
	id := trackIDFor("Fixture Odyssey (1).mp3")
	before, stampedBefore := env.stored()
	writes := env.positions.posts

	conflict := env.post(placeBody(id, 20_000, `,"expected":{"trackId":"`+id+`","offsetMs":9999}`))
	got := decodeAudioError(t, conflict)
	if conflict.Code != http.StatusConflict || got.Code != "reading_position_conflict" || got.Service != "" || got.Retryable ||
		got.Message != "Reading progress changed on another device. Choose which position to continue from." {
		t.Fatalf("a stale base = %d %+v", conflict.Code, got)
	}
	if env.positions.posts != writes {
		t.Fatalf("a refused write reached Storyteller")
	}
	if after, stamped := env.stored(); !reflect.DeepEqual(after, before) || stamped != stampedBefore {
		t.Fatalf("a refused write changed the place: %+v @ %d", after, stamped)
	}

	// A base that is the place now held lets it through.
	if response := env.post(placeBody(id, 20_000, `,"expected":{"trackId":"`+id+`","offsetMs":10000}`)); response.Code != http.StatusOK {
		t.Fatalf("a current base = %d: %s", response.Code, response.Body.String())
	}
	if back := env.position(); back.OffsetMs != 20_000 {
		t.Fatalf("back = %+v", back)
	}

	// Another track is another place, whatever the offset.
	other := trackIDFor("Fixture Odyssey (2).mp3")
	if response := env.post(placeBody(id, 30_000, `,"expected":{"trackId":"`+other+`","offsetMs":20000}`)); response.Code != http.StatusConflict {
		t.Fatalf("a base on another track = %d", response.Code)
	}
	// No base: the write is not checked.
	if response := env.post(placeBody(id, 40_000, "")); response.Code != http.StatusOK {
		t.Fatalf("no base = %d", response.Code)
	}
}

func TestAudioPositionExpectedNullMeansNothingIsSaved(t *testing.T) {
	env := newPositionEnv(t)
	id := trackIDFor("Fixture Odyssey (1).mp3")
	if response := env.post(placeBody(id, 5000, `,"expected":null`)); response.Code != http.StatusOK {
		t.Fatalf("null against an empty slot = %d: %s", response.Code, response.Body.String())
	}
	if response := env.post(placeBody(id, 6000, `,"expected":null`)); response.Code != http.StatusConflict {
		t.Fatalf("null against a held place = %d", response.Code)
	}
	empty := newPositionEnv(t)
	if response := empty.post(placeBody(id, 6000, `,"expected":{"trackId":"`+id+`","offsetMs":5000}`)); response.Code != http.StatusConflict {
		t.Fatalf("a base against an empty slot = %d", response.Code)
	}
	if _, _, has := empty.positions.stored(); has {
		t.Fatal("a refused write was kept")
	}
}

// The base is a place, not a locator: what another app wrote in another form is
// compared as the place the hub reads it as, so a writer of text and a writer of
// audio can hand over to each other.
func TestAudioPositionExpectedIsComparedAsThePlaceReadFromTheCurrentRecord(t *testing.T) {
	env := newPositionEnv(t)
	truth := env.sentences()
	chosen := truth[8]
	locator := fmt.Sprintf(`{"href":%s,"locations":{"fragments":[%q],"totalProgression":0.4}}`, jsonString(chosen.href), chosen.fragment)
	env.positions.seed(locator, clockStart-1)
	read := env.position()
	id := trackIDFor("Fixture Odyssey (1).mp3")
	body := func(expected string) string { return placeBody(id, 123, `,"expected":`+expected) }
	if response := env.post(body(fmt.Sprintf(`{"trackId":%q,"offsetMs":%d}`, read.TrackID, read.OffsetMs+1))); response.Code != http.StatusConflict {
		t.Fatalf("a base one millisecond off = %d", response.Code)
	}
	if response := env.post(body(fmt.Sprintf(`{"trackId":%q,"offsetMs":%d}`, read.TrackID, read.OffsetMs))); response.Code != http.StatusOK {
		t.Fatalf("the place that was read = %d: %s", response.Code, response.Body.String())
	}
}

func TestAudioPositionIsStampedAfterTheCheckWithTheLaterOfNowAndOneAfterWhatIsHeld(t *testing.T) {
	id := trackIDFor("Fixture Odyssey (1).mp3")
	for _, test := range []struct {
		name   string
		stored int64 // 0: nothing is held
		want   int64
	}{
		{"nothing held", 0, clockStart},
		{"an older place", clockStart - 5000, clockStart},
		{"one written this millisecond", clockStart, clockStart + 1},
		{"a place stamped by a clock a minute ahead", clockStart + 60_000, clockStart + 60_001},
	} {
		t.Run(test.name, func(t *testing.T) {
			// What the app says the time is makes no difference, whatever it says.
			for _, claimed := range []string{`1`, `9000000000000000000`, `0`, `-5`} {
				env := newPositionEnv(t)
				if test.stored > 0 {
					env.positions.seed(`{"href":"Fixture Odyssey (2).mp3","locations":{"fragments":["t=1"],"totalProgression":0.2}}`, test.stored)
				}
				if response := env.post(placeBody(id, 7000, `,"timestamp":`+claimed)); response.Code != http.StatusOK {
					t.Fatalf("timestamp %s = %d: %s", claimed, response.Code, response.Body.String())
				}
				if _, stamped := env.stored(); stamped != test.want {
					t.Fatalf("timestamp %s: stamped %d, want %d", claimed, stamped, test.want)
				}
			}
		})
	}
}

func TestAudioPositionIgnoresTheClockOfTheApp(t *testing.T) {
	env := newPositionEnv(t)
	id := trackIDFor("Fixture Odyssey (1).mp3")
	stamps := []int64{}
	for i := 0; i < 3; i++ {
		if response := env.post(placeBody(id, int64(1000+i), `,"timestamp":1`)); response.Code != http.StatusOK {
			t.Fatalf("write %d = %d", i, response.Code)
		}
		_, stamped := env.stored()
		stamps = append(stamps, stamped)
	}
	// A clock that does not move still gives each write a moment of its own.
	if want := []int64{clockStart, clockStart + 1, clockStart + 2}; !reflect.DeepEqual(stamps, want) {
		t.Fatalf("stamps = %v, want %v", stamps, want)
	}
	// And one that has moved on is used.
	env.clock.set(time.UnixMilli(clockStart + 10_000))
	env.write("Fixture Odyssey (1).mp3", 9)
	if _, stamped := env.stored(); stamped != clockStart+10_000 {
		t.Fatalf("stamped %d with the clock at %d", stamped, clockStart+10_000)
	}
}

// Storyteller judges a write by its own table: a writer that got in between the
// hub's read and its write leaves the hub's stamp older than the held one.
func TestAudioPositionStorytellersOwnRefusalIsTheSameConflict(t *testing.T) {
	env := newPositionEnv(t)
	id := trackIDFor("Fixture Odyssey (1).mp3")
	env.positions.seed(`{"href":"Fixture Odyssey (2).mp3","locations":{"fragments":["t=1"],"totalProgression":0.2}}`, clockStart-1)
	env.positions.race = func() {
		env.positions.seed(`{"href":"Fixture Odyssey (3).MP3","locations":{"fragments":["t=9"],"totalProgression":0.4}}`, clockStart+100_000)
	}
	response := env.post(placeBody(id, 4000, ""))
	got := decodeAudioError(t, response)
	if response.Code != http.StatusConflict || got.Code != "reading_position_conflict" || got.Service != "storyteller" || got.Message != "a newer reading position already exists" {
		t.Fatalf("a race = %d %+v", response.Code, got)
	}
	if env.positions.refused != 1 {
		t.Fatalf("Storyteller refused %d writes", env.positions.refused)
	}
}

// The EPUB route and this one write the same row, so they take the same turn.
func TestAudioPositionTakesTheEPUBRoutesLock(t *testing.T) {
	env := newPositionEnv(t)
	id := trackIDFor("Fixture Odyssey (1).mp3")
	env.manifest() // the plan is built, so only the lock can hold the write up
	unlock := lockReadingCheckpoint("storyteller", "12")
	done := make(chan *httptest.ResponseRecorder, 1)
	go func() { done <- env.post(placeBody(id, 4000, "")) }()
	select {
	case <-done:
		unlock()
		t.Fatal("a write went ahead while the EPUB route held the book")
	case <-time.After(300 * time.Millisecond):
	}
	if _, _, has := env.positions.stored(); has {
		unlock()
		t.Fatal("the write reached Storyteller while the lock was held")
	}
	unlock()
	select {
	case response := <-done:
		if response.Code != http.StatusOK {
			t.Fatalf("after the lock = %d", response.Code)
		}
	case <-time.After(patient):
		t.Fatal("the write never finished once the lock was let go")
	}

	// And the other way: while this route is working, the EPUB route waits. The
	// order Storyteller accepts the two writes in is the order they took turns in.
	env.positions.race = func() { time.Sleep(200 * time.Millisecond) }
	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		env.post(placeBody(id, 5000, ""))
	}()
	go func() {
		defer wg.Done()
		// Let the audio write reach Storyteller, holding the book, first.
		eventually(t, "the audio write to reach Storyteller", func() bool {
			env.positions.mu.Lock()
			defer env.positions.mu.Unlock()
			return env.positions.race == nil
		})
		publicationRequest(env.handler, http.MethodPost, "/v1/reading/works/"+env.child+"/publications/12/position",
			`{"locator":{"href":"text/part0001.xhtml","locations":{"fragments":["id1-s0"]}},"timestamp":`+fmt.Sprint(clockStart+500_000)+`}`)
	}()
	within(t, "both writes", wg.Wait)
	env.positions.mu.Lock()
	defer env.positions.mu.Unlock()
	if len(env.positions.history) != 3 {
		t.Fatalf("Storyteller accepted %d writes", len(env.positions.history))
	}
	var first, second savedLocator
	_ = json.Unmarshal(env.positions.history[1], &first)
	_ = json.Unmarshal(env.positions.history[2], &second)
	if first.Href != "Fixture Odyssey (1).mp3" || second.Href != "text/part0001.xhtml" {
		t.Fatalf("the writes were accepted as %q then %q: they did not take turns", first.Href, second.Href)
	}
}

func TestAudioPositionRefusesWhatIsNotAPlace(t *testing.T) {
	env := newPositionEnv(t)
	id := trackIDFor("Fixture Odyssey (1).mp3")
	length := trackedLength("Fixture Odyssey (1).mp3")
	for name, body := range map[string]string{
		"an empty body":                  ``,
		"null":                           `null`,
		"an array":                       `[]`,
		"not json":                       `{"trackId":`,
		"no track":                       `{"offsetMs":5}`,
		"a track that is not an id":      `{"trackId":"Fixture Odyssey (1).mp3","offsetMs":5}`,
		"a track the book does not have": `{"trackId":"t_000000000000","offsetMs":5}`,
		"an id of the wrong shape":       `{"trackId":"t_5d41402abc4bff","offsetMs":5}`,
		"a track that is a number":       `{"trackId":1,"offsetMs":5}`,
		"no offset":                      `{"trackId":"` + id + `"}`,
		"a negative offset":              `{"trackId":"` + id + `","offsetMs":-1}`,
		"a fraction of a millisecond":    `{"trackId":"` + id + `","offsetMs":1.5}`,
		"an offset that is a string":     `{"trackId":"` + id + `","offsetMs":"5"}`,
		"an offset past the end":         fmt.Sprintf(`{"trackId":%q,"offsetMs":%d}`, id, length+1001),
		"an unknown field":               `{"trackId":"` + id + `","offsetMs":5,"form":"audio"}`,
		"completed that is not a flag":   `{"trackId":"` + id + `","offsetMs":5,"completed":"yes"}`,
		"a base that is a string":        `{"trackId":"` + id + `","offsetMs":5,"expected":"here"}`,
		"a base with no track":           `{"trackId":"` + id + `","offsetMs":5,"expected":{"offsetMs":5}}`,
		"a base with no offset":          `{"trackId":"` + id + `","offsetMs":5,"expected":{"trackId":"` + id + `"}}`,
		"a base with another field":      `{"trackId":"` + id + `","offsetMs":5,"expected":{"trackId":"` + id + `","offsetMs":5,"x":1}}`,
		"a base with a negative offset":  `{"trackId":"` + id + `","offsetMs":5,"expected":{"trackId":"` + id + `","offsetMs":-5}}`,
		"a body that is too big":         `{"trackId":"` + id + `","offsetMs":5,"pad":"` + strings.Repeat("x", 70<<10) + `"}`,
	} {
		response := env.post(body)
		if got := decodeAudioError(t, response); response.Code != http.StatusBadRequest || got.Code != CodeInvalidRequest {
			t.Errorf("%s = %d %+v", name, response.Code, got)
		}
	}
	if env.positions.posts != 0 {
		t.Fatalf("%d refused bodies reached Storyteller", env.positions.posts)
	}
	// A moment a little past the end is the end: a player's own clock and the
	// manifest's length are not the same to the millisecond.
	if response := env.post(placeBody(id, length+1000, "")); response.Code != http.StatusOK {
		t.Fatalf("a second past the end = %d: %s", response.Code, response.Body.String())
	}
	if got, _ := env.stored(); !reflect.DeepEqual(got.Locations.Fragments, []string{clock3(length)}) {
		t.Fatalf("kept as %v, want the end", got.Locations.Fragments)
	}
}

// A listening app writes every few seconds. What the hub knows of the files does
// not change when a place is written, so none of it is read again.
func TestAudioPositionNeverRebuildsTheTrackListByBeingWritten(t *testing.T) {
	env := newPositionEnv(t)
	env.write("Fixture Odyssey (1).mp3", 1000)
	if got := env.probed.Load(); got != 5 {
		t.Fatalf("the first place probed %d files", got)
	}
	for i := 0; i < 5; i++ {
		env.write("Fixture Odyssey (2).mp3", int64(2000+i))
		env.position()
	}
	if got := env.probed.Load(); got != 5 {
		t.Fatalf("writing and reading places probed %d files in all", got)
	}
	env.manifest()
	if got := env.probed.Load(); got != 5 {
		t.Fatalf("a manifest after the writes probed %d files in all", got)
	}
}

func TestAudioPositionIsNotAvailableWhereTheBookIsNot(t *testing.T) {
	// A book whose file is gone cannot say which track a place is in.
	env := newPositionEnv(t)
	if err := os.Remove(env.file("Fixture Odyssey (2).mp3")); err != nil {
		t.Fatal(err)
	}
	read := env.get(env.positionPath())
	if got := decodeAudioError(t, read); read.Code != http.StatusConflict || got.Code != "audio_not_streamable" || got.Reason != "missing_file" {
		t.Fatalf("read = %d %+v", read.Code, got)
	}
	write := env.post(placeBody(trackIDFor("Fixture Odyssey (1).mp3"), 5, ""))
	if got := decodeAudioError(t, write); write.Code != http.StatusConflict || got.Code != "audio_not_streamable" {
		t.Fatalf("write = %d %+v", write.Code, got)
	}
	if env.positions.posts != 0 {
		t.Fatal("a place was written for a book that cannot be streamed")
	}

	// A book with no audiobook has no place in one.
	none := newAudioEnv(t, audioEnvOptions{}, func(string) audioBuild { return audioBuild{} })
	for _, response := range []*httptest.ResponseRecorder{
		none.get(none.audioPath(none.child) + "/position"),
		publicationRequest(none.handler, http.MethodPost, none.audioPath(none.child)+"/position", `{"trackId":"t_000000000000","offsetMs":1}`),
	} {
		if response.Code != http.StatusNotFound {
			t.Fatalf("no audiobook = %d: %s", response.Code, response.Body.String())
		}
	}

	// The reading scope.
	denied := newAudioEnv(t, audioEnvOptions{scopes: []string{"read"}}, func(string) audioBuild { return audioBuild{} })
	for _, response := range []*httptest.ResponseRecorder{
		denied.get("/v1/reading/works/rw_00000000000000000000000000000000/publications/12/audio/position"),
		publicationRequest(denied.handler, http.MethodPost, "/v1/reading/works/rw_00000000000000000000000000000000/publications/12/audio/position", `{}`),
	} {
		if got := decodeAudioError(t, response); response.Code != http.StatusForbidden || got.Code != CodeForbiddenScope {
			t.Fatalf("without the scope = %d %+v", response.Code, got)
		}
	}
	// Only a read or a write.
	if got := publicationRequest(env.handler, http.MethodDelete, env.positionPath(), ""); got.Code != http.StatusMethodNotAllowed && got.Code != http.StatusNotFound {
		t.Fatalf("DELETE = %d", got.Code)
	}
}

// A place that the book's own manifest cannot be written against is not written.
func TestAudioPositionOfALoneM4BWithAChapterOfNoLengthIsNotKept(t *testing.T) {
	links := append([]audioLink(nil), chapteredLinks...)
	links[1].Duration = 0
	env := withPositions(newAlignedM4BEnvOf(t, links))
	response := env.post(placeBody(trackIDFor("Fixture Chapters.m4b"), 100, ""))
	if got := decodeAudioError(t, response); response.Code != http.StatusConflict || got.Code != "audio_not_streamable" || got.Reason != "unsupported_layout" {
		t.Fatalf("write = %d %+v", response.Code, got)
	}
	if env.positions.posts != 0 {
		t.Fatal("a place was written against chapters of unknown length")
	}
}
