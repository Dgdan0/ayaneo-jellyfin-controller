package api

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"reflect"
	"regexp"
	"strconv"
	"strings"
	"testing"
	"testing/fstest"
	"time"

	"ayaneohub/internal/config"
	readingdomain "ayaneohub/internal/reading"
)

// Our read-along packs (#66): the corrected sentences by default, the words when asked, and
// exactly today's edition whenever the pack does not hold.

const readalongTestUUID = "8dbe2614-83fc-4b76-86c1-3fe4510ea6af"

// readalongEnv is the aligned tracked book with /derived mapped as hub.yaml maps it, and the
// book carrying a uuid a pack can be named by.
type readalongEnv struct {
	*audioEnv
	derived string
}

func newReadalongEnv(t *testing.T) *readalongEnv {
	t.Helper()
	env := newAlignedTrackedEnv(t, alignedOptions{})
	derived := t.TempDir()
	env.server.cfg.Server.MediaRemovalRoots = append(env.server.cfg.Server.MediaRemovalRoots,
		config.MediaRemovalRoot{Service: "storyteller", Remote: "/derived", Local: derived})
	env.state.bookUUID = readalongTestUUID
	return &readalongEnv{audioEnv: env, derived: derived}
}

// pack writes the book's pack, and has the hub look again.
func (e *readalongEnv) pack(options readingdomain.ReadalongPackOptions) readingdomain.ReadalongPackFixture {
	e.t.Helper()
	dir := filepath.Join(e.derived, "readalong", readalongTestUUID)
	if err := os.RemoveAll(dir); err != nil {
		e.t.Fatal(err)
	}
	pack, err := readingdomain.GenerateReadalongPack(dir, e.build.epub, options)
	if err != nil {
		e.t.Fatal(err)
	}
	e.forget()
	return pack
}

func (e *readalongEnv) edition(query string) *http.Response {
	e.t.Helper()
	response := e.trackAt(http.MethodGet, "/v1/reading/works/"+e.child+"/publications/12/file?"+query, nil)
	return response.Result()
}

// copyOf is the EPUB the route answers with, its entries by name.
func (e *readalongEnv) copyOf(query string) (map[string]string, string) {
	e.t.Helper()
	response := e.trackAt(http.MethodGet, "/v1/reading/works/"+e.child+"/publications/12/file?"+query, nil)
	if response.Code != http.StatusOK {
		e.t.Fatalf("%s = %d: %s", query, response.Code, response.Body.String())
	}
	names, reader := zipNames(e.t, response.Body.Bytes())
	entries := map[string]string{}
	for i, name := range names {
		stream, err := reader.File[i].Open()
		if err != nil {
			e.t.Fatal(err)
		}
		data := make([]byte, reader.File[i].UncompressedSize64)
		if _, err := io.ReadFull(stream, data); err != nil {
			e.t.Fatal(err)
		}
		stream.Close()
		entries[name] = string(data)
	}
	return entries, response.Header().Get("X-Reading-Content-Hash")
}

const (
	slimQuery  = "format=readaloud&audio=omit"
	wholeQuery = "format=readaloud"
)

var wordSpan = regexp.MustCompile(`<span id="id1-s0-w0">`)

// today is what the hub serves a book with no pack: the manifest and the two editions.
type today struct {
	manifest    ReadingAudioManifest
	slim, whole string
}

func (e *readalongEnv) today() today {
	e.t.Helper()
	_, slim := e.copyOf(slimQuery)
	_, whole := e.copyOf(wholeQuery)
	return today{manifest: e.manifest(), slim: slim, whole: whole}
}

func TestAPackThatHoldsIsTheNarrationAndOffersItsWords(t *testing.T) {
	env := newReadalongEnv(t)
	before := env.today()
	if before.manifest.WordLevel || !before.manifest.Aligned {
		t.Fatalf("no pack yet: %+v", before.manifest)
	}
	pack := env.pack(readingdomain.ReadalongPackOptions{ShiftMs: 400, GapMs: 20})
	manifest := env.manifest()
	if !manifest.Aligned || !manifest.WordLevel {
		t.Fatalf("aligned %v, wordLevel %v, reason %q", manifest.Aligned, manifest.WordLevel, manifest.AlignmentReason)
	}
	// The pieces are the edition's and lie where they lay: the mapping is unchanged.
	if !reflect.DeepEqual(manifest.Alignment, before.manifest.Alignment) || !reflect.DeepEqual(manifest.Tracks, before.manifest.Tracks) {
		t.Fatalf("alignment %+v, want %+v", manifest.Alignment, before.manifest.Alignment)
	}
	// What the manifest says is another revision: its places are the pack's.
	if manifest.Revision == before.manifest.Revision {
		t.Fatal("a pack in use is the same revision")
	}
	// A sentence is a moment of the audiobook by the pack's times, which begin 400 ms later.
	record, _, err := env.server.storytellerBookRecord(context.Background(), 12)
	if err != nil {
		t.Fatal(err)
	}
	plan, _, err := env.server.audioPlanFor(context.Background(), env.server.reconcileStorytellerBook(*record))
	if err != nil {
		t.Fatal(err)
	}
	first := env.build.epub.Pars[0]
	if _, offset, found := plan.alignment.placeOf(first.Text, first.Fragment); !found || offset != first.BeginMs+400 {
		t.Fatalf("the first sentence is at %d (found %v), want %d", offset, found, first.BeginMs+400)
	}
	if sentence, found := plan.alignment.sentenceAt(0, first.BeginMs+200); !found || sentence.Fragment != first.Fragment || sentence.BeginMs != pack.Sentences[0].BeginMs {
		t.Fatalf("at 200 ms into the first sentence: %+v", sentence)
	}

	// The editions: the sentence set by default, the word set when asked.
	sentence, sentenceHash := env.copyOf(slimQuery)
	asked, askedHash := env.copyOf(slimQuery + "&granularity=sentence")
	words, wordHash := env.copyOf(slimQuery + "&granularity=word")
	if sentenceHash != askedHash || sentenceHash == wordHash || sentenceHash == before.slim {
		t.Fatalf("hashes: default %s, sentence %s, word %s, before %s", sentenceHash, askedHash, wordHash, before.slim)
	}
	if !reflect.DeepEqual(sentence, asked) {
		t.Fatal("granularity=sentence is not the default")
	}
	smil := "OEBPS/smil/part0001.smil"
	text := "OEBPS/text/part0001.xhtml"
	if !strings.Contains(sentence[smil], `clipBegin="0.400s"`) || strings.Contains(sentence[smil], "-w0") || wordSpan.MatchString(sentence[text]) {
		t.Fatalf("the default is not the sentence set:\n%s", sentence[smil])
	}
	if !strings.Contains(words[smil], `<seq id="id1-s0-seq" epub:textref="../text/part0001.xhtml#id1-s0">`) || !wordSpan.MatchString(words[text]) {
		t.Fatalf("the word edition is not the word set:\n%s\n%s", words[smil], words[text])
	}
	// Restyled and given its language as the edition's text is.
	if !columnStyleElement.MatchString(words[text]) || !rootLanguage.MatchString(words[text]) {
		t.Fatalf("the word text is not restyled: %s", words[text])
	}
	// The whole edition takes the same sets, its audio kept.
	whole, _ := env.copyOf(wholeQuery + "&granularity=word")
	if whole[smil] != words[smil] || whole["OEBPS/Audio/00001-00001.mp3"] == "" {
		t.Fatal("the whole word edition is not the word set with its audio")
	}
	wholeSentence, wholeHash := env.copyOf(wholeQuery)
	if wholeSentence[smil] != sentence[smil] || wholeHash == before.whole {
		t.Fatal("the whole edition is not the sentence set by default")
	}
}

// Each pack that does not hold is today's edition and today's manifest, byte for byte.
func TestAPackThatDoesNotHoldServesExactlyTodaysEdition(t *testing.T) {
	for _, test := range []struct {
		name    string
		options readingdomain.ReadalongPackOptions
		remove  bool
	}{
		{name: "stale", options: readingdomain.ReadalongPackOptions{ShiftMs: 400, Stale: true}},
		{name: "failed", options: readingdomain.ReadalongPackOptions{ShiftMs: 400, Failed: true}},
		{name: "not re-timed", options: readingdomain.ReadalongPackOptions{ShiftMs: 400, NotRetimed: true}},
		{name: "being written", options: readingdomain.ReadalongPackOptions{ShiftMs: 400, Written: time.Now()}},
		{name: "missing", options: readingdomain.ReadalongPackOptions{ShiftMs: 400}, remove: true},
	} {
		t.Run(test.name, func(t *testing.T) {
			env := newReadalongEnv(t)
			before := env.today()
			pack := env.pack(test.options)
			if test.remove {
				if err := os.RemoveAll(pack.Dir); err != nil {
					t.Fatal(err)
				}
				env.forget()
			}
			after := env.today()
			if after.manifest.WordLevel || after.manifest.Revision != before.manifest.Revision || !reflect.DeepEqual(after.manifest, before.manifest) {
				t.Fatalf("manifest %+v, want %+v", after.manifest, before.manifest)
			}
			if after.slim != before.slim || after.whole != before.whole {
				t.Fatalf("editions %s %s, want %s %s", after.slim, after.whole, before.slim, before.whole)
			}
			// Asking for words changes nothing either.
			if _, hash := env.copyOf(slimQuery + "&granularity=word"); hash != before.slim {
				t.Fatalf("the word edition of a pack that does not hold is %s, want %s", hash, before.slim)
			}
		})
	}
}

// A word set that does not narrate the edition's pieces as they are is not offered, and an app
// that asks for it anyway gets the sentence set.
func TestAWordSetThatDoesNotFitIsNotOffered(t *testing.T) {
	env := newReadalongEnv(t)
	pack := env.pack(readingdomain.ReadalongPackOptions{ShiftMs: 400})
	smil := filepath.Join(pack.Dir, readingdomain.GranularityWord, "OEBPS", "smil", "part0001.smil")
	data, err := os.ReadFile(smil)
	if err != nil {
		t.Fatal(err)
	}
	// The piece's last word ends a tenth of a second early: every clip still runs forward, and the
	// piece is no longer the length the manifest mapped.
	ends := regexp.MustCompile(`clipEnd="([0-9]+)\.([0-9]{3})s"`).FindAllSubmatchIndex(data, -1)
	last := ends[len(ends)-1]
	seconds, _ := strconv.Atoi(string(data[last[2]:last[3]]))
	millis, _ := strconv.Atoi(string(data[last[4]:last[5]]))
	early := seconds*1000 + millis - 100
	broken := string(data[:last[0]]) + fmt.Sprintf(`clipEnd="%d.%03ds"`, early/1000, early%1000) + string(data[last[1]:])
	if err := os.WriteFile(smil, []byte(broken), 0o644); err != nil {
		t.Fatal(err)
	}
	old := time.Now().Add(-time.Hour)
	if err := os.Chtimes(smil, old, old); err != nil {
		t.Fatal(err)
	}
	env.forget()
	manifest := env.manifest()
	if !manifest.Aligned || manifest.WordLevel {
		t.Fatalf("aligned %v, wordLevel %v", manifest.Aligned, manifest.WordLevel)
	}
	sentence, sentenceHash := env.copyOf(slimQuery)
	words, wordHash := env.copyOf(slimQuery + "&granularity=word")
	if wordHash != sentenceHash || !strings.Contains(words["OEBPS/smil/part0001.smil"], `clipBegin="0.400s"`) || !reflect.DeepEqual(words, sentence) {
		t.Fatal("asking for a word set that does not fit is not the sentence set")
	}
}

// A pack with only its sentences is the narration and offers no words.
func TestASentenceOnlyPackOffersNoWords(t *testing.T) {
	env := newReadalongEnv(t)
	env.pack(readingdomain.ReadalongPackOptions{ShiftMs: 400, Granularities: []string{readingdomain.GranularitySentence}})
	if manifest := env.manifest(); !manifest.Aligned || manifest.WordLevel {
		t.Fatalf("aligned %v, wordLevel %v", manifest.Aligned, manifest.WordLevel)
	}
	words, _ := env.copyOf(slimQuery + "&granularity=word")
	if !strings.Contains(words["OEBPS/smil/part0001.smil"], `clipBegin="0.400s"`) || wordSpan.MatchString(words["OEBPS/text/part0001.xhtml"]) {
		t.Fatal("not the sentence set")
	}
}

// A pack built again is copied again, and the revision moves with it.
func TestAPackBuiltAgainIsANewCopyAndRevision(t *testing.T) {
	env := newReadalongEnv(t)
	env.pack(readingdomain.ReadalongPackOptions{ShiftMs: 400})
	first := env.manifest()
	_, firstWords := env.copyOf(slimQuery + "&granularity=word")
	env.pack(readingdomain.ReadalongPackOptions{ShiftMs: 600})
	second := env.manifest()
	_, secondWords := env.copyOf(slimQuery + "&granularity=word")
	if first.Revision == second.Revision || firstWords == secondWords || !second.WordLevel {
		t.Fatalf("revisions %s %s, words %s %s", first.Revision, second.Revision, firstWords, secondWords)
	}
}

func TestGranularityIsForTheReadAlongEditionOnly(t *testing.T) {
	env := newReadalongEnv(t)
	for _, query := range []string{
		slimQuery + "&granularity=letters",
		slimQuery + "&granularity=Word",
		"format=ebook&granularity=word",
		"granularity=word",
		"format=audiobook&granularity=sentence",
	} {
		if response := env.edition(query); response.StatusCode != http.StatusBadRequest {
			t.Errorf("%s = %d", query, response.StatusCode)
		}
	}
}

// The copy is kept under the edition, the set's granularity and the pack's fingerprint.
func TestTheCopyKeyNamesTheSetAndThePack(t *testing.T) {
	file := readingdomain.MediaFile{Path: "D:/x/aligned.epub", Size: 10, ModTime: time.Unix(1_791_501_836, 0)}
	overlay := func(granularity, text string) *readingdomain.Overlay {
		set, err := readingdomain.NewOverlay(granularity, fstest.MapFS{"OEBPS/smil/a.smil": {Data: []byte(text)}}, "salt")
		if err != nil {
			t.Fatal(err)
		}
		return set
	}
	keys := map[string]bool{
		epubCopyKey("slim", file, nil):                                 true,
		epubCopyKey("slim", file, overlay("sentence", "<smil/>")):      true,
		epubCopyKey("slim", file, overlay("word", "<smil/>")):          true,
		epubCopyKey("slim", file, overlay("word", "<smil></smil>")):    true,
		epubCopyKey("readaloud", file, overlay("word", "<smil/>")):     true,
		epubCopyKey("readaloud", file, overlay("sentence", "<smil/>")): true,
	}
	if len(keys) != 6 {
		t.Fatalf("%d keys for six copies", len(keys))
	}
	if epubCopyKey("slim", file, nil) != "epub-copy:slim:D:/x/aligned.epub\x0010\x00"+"1791501836000000000" {
		t.Fatalf("the key of a copy with no pack changed: %q", epubCopyKey("slim", file, nil))
	}
}
