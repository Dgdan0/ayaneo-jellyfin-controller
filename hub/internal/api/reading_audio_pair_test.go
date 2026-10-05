package api

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	"ayaneohub/internal/config"
	readingdomain "ayaneohub/internal/reading"
)

func pairEdition(source, kind, narrator string, ms int64) ReadingEdition {
	return ReadingEdition{ID: kind + "-" + source + "-" + narrator, WorkID: "rw_x", Source: "storyteller", SourceItemID: source, Kind: kind, Narrator: narrator, DurationMS: ms}
}

func idsOf(editions []ReadingEdition) []string {
	out := make([]string, len(editions))
	for i, edition := range editions {
		out[i] = edition.Kind + ":" + edition.SourceItemID
	}
	return out
}

// A book Storyteller holds twice is one book: the audiobook that is offered is
// the one of the copy that also has the read-along edition (else the ebook), so
// there is one place and one set of tracks to follow it. A different narration
// is not a copy.
func TestOneAudiobookPerWorkKeepsTheCopyThatHasTheReadAlong(t *testing.T) {
	const jon, length = "Jon Lindstrom", int64(36_538_680)
	for _, test := range []struct {
		name     string
		editions []ReadingEdition
		want     []string
	}{
		{"the second copy of the audio is left out", []ReadingEdition{
			pairEdition("12", "ebook", "", 0), pairEdition("12", "audiobook", jon, length), pairEdition("12", "readaloud", jon, 0), pairEdition("13", "audiobook", jon, length),
		}, []string{"ebook:12", "audiobook:12", "readaloud:12"}},
		{"whichever order Storyteller lists them in", []ReadingEdition{
			pairEdition("13", "audiobook", jon, length), pairEdition("12", "ebook", "", 0), pairEdition("12", "audiobook", jon, length), pairEdition("12", "readaloud", jon, 0),
		}, []string{"ebook:12", "audiobook:12", "readaloud:12"}},
		{"the ebook decides when neither has a read-along", []ReadingEdition{
			pairEdition("13", "audiobook", jon, length), pairEdition("12", "audiobook", jon, length), pairEdition("12", "ebook", "", 0),
		}, []string{"audiobook:12", "ebook:12"}},
		{"a read-along beats an ebook", []ReadingEdition{
			pairEdition("12", "ebook", "", 0), pairEdition("12", "audiobook", jon, length),
			pairEdition("13", "ebook", "", 0), pairEdition("13", "audiobook", jon, length), pairEdition("13", "readaloud", jon, 0),
		}, []string{"ebook:12", "ebook:13", "audiobook:13", "readaloud:13"}},
		{"the first copy when none has anything else", []ReadingEdition{
			pairEdition("13", "audiobook", jon, length), pairEdition("12", "audiobook", jon, length),
		}, []string{"audiobook:13"}},
		{"three copies make one", []ReadingEdition{
			pairEdition("14", "audiobook", jon, length), pairEdition("13", "audiobook", jon, length), pairEdition("12", "audiobook", jon, length), pairEdition("12", "readaloud", jon, 0),
		}, []string{"audiobook:12", "readaloud:12"}},
		{"the same people in another order and case are the same narration", []ReadingEdition{
			pairEdition("12", "audiobook", "Jon Lindstrom, Ann Reader", length), pairEdition("12", "readaloud", "", 0), pairEdition("13", "audiobook", "ann reader, JON LINDSTROM", length),
		}, []string{"audiobook:12", "readaloud:12"}},
		{"lengths a second or two apart are the same recording", []ReadingEdition{
			pairEdition("12", "audiobook", jon, length), pairEdition("13", "audiobook", jon, length-2000),
		}, []string{"audiobook:12"}},
		{"a length that is not known does not make it another narration", []ReadingEdition{
			pairEdition("12", "audiobook", jon, length), pairEdition("13", "audiobook", jon, 0),
		}, []string{"audiobook:12"}},

		{"another narrator is another narration", []ReadingEdition{
			pairEdition("12", "audiobook", "Narrator A", length), pairEdition("13", "audiobook", "Narrator B", length),
		}, []string{"audiobook:12", "audiobook:13"}},
		{"an abridgement by the same narrator is another recording", []ReadingEdition{
			pairEdition("12", "audiobook", jon, length), pairEdition("13", "audiobook", jon, length/3),
		}, []string{"audiobook:12", "audiobook:13"}},
		{"nobody named is not known to be the same", []ReadingEdition{
			pairEdition("12", "audiobook", "", length), pairEdition("13", "audiobook", "", length),
		}, []string{"audiobook:12", "audiobook:13"}},
		// Dark Matter as Storyteller really holds it: the audio-only book names nobody.
		{"a copy that names nobody is the same audio when the lengths agree to the second", []ReadingEdition{
			pairEdition("12", "ebook", "", 0), pairEdition("12", "audiobook", jon, length), pairEdition("12", "readaloud", jon, 0), pairEdition("13", "audiobook", "", length),
		}, []string{"ebook:12", "audiobook:12", "readaloud:12"}},
		{"an unnamed narration a few seconds apart is another recording", []ReadingEdition{
			pairEdition("12", "audiobook", jon, length), pairEdition("13", "audiobook", "", length-5000),
		}, []string{"audiobook:12", "audiobook:13"}},
		{"an unnamed copy of unknown length is not known to be the same", []ReadingEdition{
			pairEdition("12", "audiobook", jon, length), pairEdition("13", "audiobook", "", 0),
		}, []string{"audiobook:12", "audiobook:13"}},
		{"one audiobook is left as it is", []ReadingEdition{
			pairEdition("12", "ebook", "", 0), pairEdition("12", "audiobook", jon, length),
		}, []string{"ebook:12", "audiobook:12"}},
		{"nothing to do for a work of no audiobook", []ReadingEdition{
			pairEdition("12", "ebook", "", 0), {ID: "k", Source: "kavita", SourceItemID: "5", Kind: "ebook"},
		}, []string{"ebook:12", "ebook:5"}},
		{"another service's audiobook is not Storyteller's", []ReadingEdition{
			pairEdition("12", "audiobook", jon, length), {ID: "k", Source: "other", SourceItemID: "12", Kind: "audiobook", Narrator: jon, DurationMS: length},
		}, []string{"audiobook:12", "audiobook:12"}},
	} {
		t.Run(test.name, func(t *testing.T) {
			before := append([]ReadingEdition(nil), test.editions...)
			got := oneAudiobookPerWork(test.editions)
			if !reflect.DeepEqual(idsOf(got), test.want) {
				t.Fatalf("editions = %v, want %v", idsOf(got), test.want)
			}
			if !reflect.DeepEqual(test.editions, before) {
				t.Fatal("the list it was given was changed")
			}
		})
	}
}

// pairEnv is Storyteller holding Dark Matter's shape: the one book with its ebook,
// its audiobook and its read-along edition, and a second book that is the same
// audio again with nothing else.
type pairEnv struct {
	t         *testing.T
	handler   http.Handler
	workID    string
	full      *audioEnv
	dupFolder string
	dupBook   readingdomain.AudiobookFixture
	zipCalls  int
}

func newPairEnv(t *testing.T, duplicateFirst bool) *pairEnv {
	t.Helper()
	// The first book, as the audio tests build it, then a second folder of the same
	// audio under another mapped root.
	full := newAlignedTrackedEnv(t, alignedOptions{})
	legacy := t.TempDir()
	dup, err := readingdomain.GenerateTrackedAudiobook(legacy)
	if err != nil {
		t.Fatal(err)
	}
	links := full.build.links
	dupJSON := storytellerAudiobook(t, "/legacy/"+dup.Relative, links, false)
	const narrators = `[{"name":"Jon Lindstrom"}]`
	pair := &pairEnv{t: t, full: full, dupFolder: legacy, dupBook: dup}
	bookA := func() string {
		return `{"id":12,"uuid":"book-12","title":"Pair Book","authors":[{"name":"Blake Pair"}],"narrators":` + narrators +
			`,"ebook":{"uuid":"ebook-12","pageCount":400},"audiobook":` + full.build.json + `,"readaloud":` + full.build.readaloud + `}`
	}
	bookB := `{"id":13,"uuid":"book-13","title":"Pair Book","authors":[{"name":"Blake Pair"}],"narrators":` + narrators + `,"audiobook":` + dupJSON + `}`
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v2/token":
			_, _ = io.WriteString(w, `{"access_token":"story-token","token_type":"Bearer","expires_in":3600}`)
		case "/api/v2/books":
			if duplicateFirst {
				_, _ = io.WriteString(w, "["+bookB+","+bookA()+"]")
			} else {
				_, _ = io.WriteString(w, "["+bookA()+","+bookB+"]")
			}
		case "/api/v2/books/12":
			_, _ = io.WriteString(w, bookA())
		case "/api/v2/books/13":
			_, _ = io.WriteString(w, bookB)
		case "/api/v2/books/13/files":
			pair.zipCalls++
			w.Header().Set("Content-Type", "application/zip")
			_, _ = io.WriteString(w, "audio archive")
		case "/api/v2/books/12/positions", "/api/v2/books/13/positions":
			http.NotFound(w, r)
		case "/api/Library/libraries":
			_, _ = io.WriteString(w, `[]`)
		default:
			http.NotFound(w, r)
		}
	}))
	t.Cleanup(upstream.Close)
	cfg := readingCatalogConfig(upstream.URL, filepath.Join(t.TempDir(), "catalog.json"), []string{"reading"})
	cfg.Server.MediaRemovalRoots = []config.MediaRemovalRoot{
		{Service: "storyteller", Remote: "/library", Local: full.root},
		{Service: "storyteller", Remote: "/legacy", Local: legacy},
	}
	cfg.Auth.RateLimit = config.RateLimitConfig{RPM: 1_000_000, Burst: 100_000}
	server := NewServer(cfg)
	server.probeAudio = full.probe
	pair.handler = server.Handler()

	page := libraryRequest(pair.handler, "/v1/reading/libraries/storyteller:books/items?page=1&sort=title&direction=asc")
	var shelf ReadingLibraryItemsResponse
	if page.Code != http.StatusOK || json.Unmarshal(page.Body.Bytes(), &shelf) != nil || len(shelf.Items) != 1 {
		t.Fatalf("the two books are not one item on the shelf: %d %s", page.Code, page.Body.String())
	}
	pair.workID = shelf.Items[0].ID
	return pair
}

func (p *pairEnv) work() ReadingWork {
	p.t.Helper()
	response := libraryRequest(p.handler, "/v1/reading/works/"+p.workID)
	var work ReadingWork
	if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &work) != nil {
		p.t.Fatalf("work = %d %s", response.Code, response.Body.String())
	}
	return work
}

func TestAMergedWorkOffersTheAudiobookOfTheBookWithTheReadAlong(t *testing.T) {
	for _, duplicateFirst := range []bool{false, true} {
		t.Run(fmt.Sprintf("duplicate listed first: %v", duplicateFirst), func(t *testing.T) {
			pair := newPairEnv(t, duplicateFirst)
			work := pair.work()
			var kinds []string
			for _, edition := range work.Editions {
				kinds = append(kinds, edition.Kind+":"+edition.SourceItemID)
			}
			if want := []string{"ebook:12", "audiobook:12", "readaloud:12"}; !sameSet(kinds, want) {
				t.Fatalf("editions = %v, want %v", kinds, want)
			}
			if !containsString(work.Availability, "audiobook") || !containsString(work.Availability, "readaloud") || !containsString(work.Availability, "ebook") {
				t.Fatalf("availability = %v", work.Availability)
			}

			// The book offered is the one that streams, aligned.
			manifest := libraryRequest(pair.handler, "/v1/reading/works/"+pair.workID+"/publications/12/audio")
			var body ReadingAudioManifest
			if manifest.Code != http.StatusOK || json.Unmarshal(manifest.Body.Bytes(), &body) != nil || !body.Aligned || len(body.Tracks) != 5 {
				t.Fatalf("the offered audiobook = %d %s", manifest.Code, manifest.Body.String())
			}
		})
	}
}

// What an app that was shown the duplicate before can still do with it.
func TestTheDuplicatesRoutesKeepWorkingForOlderApps(t *testing.T) {
	pair := newPairEnv(t, false)
	base := "/v1/reading/works/" + pair.workID + "/publications/13"
	archive := libraryRequest(pair.handler, base+"/file?format=audiobook")
	if archive.Code != http.StatusOK || archive.Body.String() != "audio archive" || pair.zipCalls != 1 {
		t.Fatalf("its archive = %d %q", archive.Code, archive.Body.String())
	}
	manifest := libraryRequest(pair.handler, base+"/audio")
	var body ReadingAudioManifest
	if manifest.Code != http.StatusOK || json.Unmarshal(manifest.Body.Bytes(), &body) != nil || body.SourceItemID != "13" || len(body.Tracks) != 5 || body.Aligned {
		t.Fatalf("its manifest = %d %s", manifest.Code, manifest.Body.String())
	}
	track := libraryRequest(pair.handler, base+"/audio/tracks/0?rev="+body.Revision)
	if track.Code != http.StatusOK || track.Body.Len() == 0 {
		t.Fatalf("its first track = %d", track.Code)
	}
	position := libraryRequest(pair.handler, base+"/audio/position")
	if position.Code != http.StatusOK || !strings.Contains(position.Body.String(), `"position":null`) {
		t.Fatalf("its place = %d %s", position.Code, position.Body.String())
	}
}

func sameSet(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for _, item := range a {
		if !containsString(b, item) {
			return false
		}
	}
	return true
}
