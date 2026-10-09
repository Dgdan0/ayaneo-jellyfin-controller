package reading

import (
	"archive/zip"
	"bytes"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"ayaneohub/internal/config"
)

const packTestUUID = "6adf9cb8-b1ac-4dc4-9ca7-2bc1ac3dfdb8"

// packOptions is an edition of two narrated files in three chapters, the second file in two pieces,
// with names that need encoding (#61) and a sentence the aligner ran past the end of its piece.
func packOptions() AlignedEPUBOptions {
	return AlignedEPUBOptions{
		PackageDir: "OEBPS", AudioBytes: 512,
		Layout: AlignedLayout{AudioExt: ".mp4", OverlayDir: "MediaOverlays", TextDir: "Text", EncodedRefs: true,
			ChapterName: func(chapter int) string { return "Author - [Series 01] - Part_" + string(rune('0'+chapter)) }},
		Narrations: []FixtureNarration{
			{ChunkMs: []int64{60_000}, Sentences: []int{30}, Chapters: []int{30}},
			{ChunkMs: []int64{90_000, 30_000}, Sentences: []int{40, 20}, Chapters: []int{35, 25}, OverrunMs: []int64{0, 800}},
		},
	}
}

// packWorld is an edition, its pack under a /derived mapping like hub.yaml's, and the roots.
type packWorld struct {
	edition AlignedEPUBFixture
	pack    ReadalongPackFixture
	roots   []config.MediaRemovalRoot
	file    MediaFile
}

func newPackWorld(t *testing.T, options ReadalongPackOptions) packWorld {
	t.Helper()
	derived := t.TempDir()
	edition := generateAligned(t, packOptions())
	pack, err := GenerateReadalongPack(filepath.Join(derived, "readalong", packTestUUID), edition, options)
	if err != nil {
		t.Fatal(err)
	}
	library := filepath.Dir(edition.Path)
	roots := []config.MediaRemovalRoot{
		{Service: "storyteller", Remote: "/data/assets", Local: library},
		{Service: "storyteller", Remote: "/derived", Local: derived},
	}
	file, err := ResolveEPUBFile(roots, "storyteller", "/data/assets/"+filepath.Base(edition.Path))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { file.Close() })
	return packWorld{edition: edition, pack: pack, roots: roots, file: file}
}

func (w packWorld) find(t *testing.T) (*Pack, string) {
	t.Helper()
	return FindPack(w.roots, packTestUUID, w.file, time.Now())
}

func TestAPackThatHoldsIsFoundWithBothSets(t *testing.T) {
	world := newPackWorld(t, ReadalongPackOptions{ShiftMs: 400, Unspoken: []string{"id2-s3"}})
	pack, reason := world.find(t)
	if pack == nil {
		t.Fatalf("no pack: %s", reason)
	}
	sentence, word := pack.Overlay(GranularitySentence), pack.Overlay(GranularityWord)
	if sentence == nil || word == nil {
		t.Fatalf("sets %v %v", sentence, word)
	}
	// The sentence set replaces the SMIL; the word set the SMIL and the text.
	if len(sentence.Names()) != 3 || len(word.Names()) != 6 {
		t.Fatalf("sentence %v, word %v", sentence.Names(), word.Names())
	}
	if !word.Has("OEBPS/Text/Author - [Series 01] - Part_1.xhtml") || !sentence.Has("OEBPS/MediaOverlays/Author - [Series 01] - Part_3.smil") {
		t.Fatalf("names %v", word.Names())
	}
	if sentence.Fingerprint == word.Fingerprint || len(sentence.Fingerprint) != 16 {
		t.Fatalf("fingerprints %q %q", sentence.Fingerprint, word.Fingerprint)
	}
	// Uppercase in the folder name or the manifest is the same book.
	if pack, reason := FindPack(world.roots, strings.ToUpper(packTestUUID), world.file, time.Now()); pack == nil {
		t.Fatalf("upper-case uuid: %s", reason)
	}
}

// Each way a pack can fail to hold is today's edition, with its reason.
func TestAPackThatDoesNotHoldIsNotUsed(t *testing.T) {
	for _, test := range []struct {
		name    string
		options ReadalongPackOptions
		reason  string
	}{
		{"failed", ReadalongPackOptions{Failed: true}, PackFailed},
		{"not re-timed", ReadalongPackOptions{NotRetimed: true}, PackNotRetimed},
		{"stale: built from another edition", ReadalongPackOptions{Stale: true}, PackStale},
		{"still being written", ReadalongPackOptions{Written: time.Now().Add(-time.Minute)}, PackSettling},
		{"another book's manifest", ReadalongPackOptions{UUID: "00000000-0000-0000-0000-000000000000"}, PackOtherBook},
		{"a file that is not a document", ReadalongPackOptions{Extra: map[string]map[string][]byte{GranularityWord: {"OEBPS/Styles/x.css": []byte("p{}")}}}, PackUnexpected},
	} {
		t.Run(test.name, func(t *testing.T) {
			world := newPackWorld(t, test.options)
			if pack, reason := world.find(t); pack != nil || reason != test.reason {
				t.Fatalf("got %v %q, want %q", pack, reason, test.reason)
			}
		})
	}
	t.Run("no pack", func(t *testing.T) {
		world := newPackWorld(t, ReadalongPackOptions{})
		if pack, reason := FindPack(world.roots, "11111111-2222-3333-4444-555555555555", world.file, time.Now()); pack != nil || reason != PackNone {
			t.Fatalf("got %v %q", pack, reason)
		}
		if pack, reason := FindPack(world.roots, "../readalong", world.file, time.Now()); pack != nil || reason != PackBadIdentity {
			t.Fatalf("a uuid that is a path: %v %q", pack, reason)
		}
		if pack, reason := FindPack(world.roots[:1], packTestUUID, world.file, time.Now()); pack != nil || reason != PackUnmapped {
			t.Fatalf("no /derived mapping: %v %q", pack, reason)
		}
	})
	t.Run("stale by its time", func(t *testing.T) {
		world := newPackWorld(t, ReadalongPackOptions{})
		later := world.file.ModTime.Add(2 * time.Second)
		if err := os.Chtimes(world.edition.Path, later, later); err != nil {
			t.Fatal(err)
		}
		file, err := ResolveEPUBFile(world.roots, "storyteller", "/data/assets/"+filepath.Base(world.edition.Path))
		if err != nil {
			t.Fatal(err)
		}
		defer file.Close()
		if pack, reason := FindPack(world.roots, packTestUUID, file, time.Now()); pack != nil || reason != PackStale {
			t.Fatalf("got %v %q", pack, reason)
		}
	})
	t.Run("a set missing that the manifest lists", func(t *testing.T) {
		world := newPackWorld(t, ReadalongPackOptions{})
		if err := os.RemoveAll(filepath.Join(world.pack.Dir, GranularityWord)); err != nil {
			t.Fatal(err)
		}
		if pack, reason := world.find(t); pack != nil || reason != PackNoSet {
			t.Fatalf("got %v %q", pack, reason)
		}
	})
}

// The sentence set is read in place of the edition's SMIL: the pack's times, the pack's sentences,
// and each audio piece the same length, which is what maps it onto the tracks.
func TestTheSentenceSetIsTheNarration(t *testing.T) {
	world := newPackWorld(t, ReadalongPackOptions{ShiftMs: 400, Unspoken: []string{"id2-s3"}})
	pack, _ := world.find(t)
	plain, err := ReadAlignment(world.file, world.file.Size)
	if err != nil {
		t.Fatal(err)
	}
	overlaid, err := ReadOverlaidAlignment(world.file, world.file.Size, pack.Overlay(GranularitySentence))
	if err != nil {
		t.Fatal(err)
	}
	var got []FixturePar
	for _, file := range overlaid.Files {
		for _, par := range file.Pars {
			got = append(got, FixturePar{Text: par.Text, Fragment: par.Fragment, Audio: file.Entry, BeginMs: par.BeginMs, EndMs: par.EndMs})
		}
	}
	if len(got) != len(world.pack.Sentences) || len(got) != len(world.edition.Pars)-1 {
		t.Fatalf("%d sentences, want %d", len(got), len(world.pack.Sentences))
	}
	for i := range got {
		if got[i] != world.pack.Sentences[i] {
			t.Fatalf("sentence %d = %+v, want %+v", i, got[i], world.pack.Sentences[i])
		}
	}
	if _, _, found := overlaid.Find("OEBPS/Text/Author - [Series 01] - Part_2.xhtml", "id2-s3"); found {
		t.Fatal("an unspoken sentence is narrated")
	}
	if len(overlaid.Files) != len(plain.Files) {
		t.Fatalf("%d pieces, want %d", len(overlaid.Files), len(plain.Files))
	}
	for i := range plain.Files {
		if overlaid.Files[i].Entry != plain.Files[i].Entry || overlaid.Files[i].LengthMs != plain.Files[i].LengthMs {
			t.Fatalf("piece %d: %s %d, want %s %d", i, overlaid.Files[i].Entry, overlaid.Files[i].LengthMs, plain.Files[i].Entry, plain.Files[i].LengthMs)
		}
	}
	// Storyteller's sentence past the end of its piece is not in the pack.
	if overlaid.PastEnd != 0 || plain.PastEnd != 1 {
		t.Fatalf("past the end: %d in the pack, %d in the edition", overlaid.PastEnd, plain.PastEnd)
	}
	if !overlaid.SamePieces(plain) {
		t.Fatal("the pieces differ")
	}
	if len(overlaid.Contents) == 0 || len(overlaid.Chapters()) != len(plain.Chapters()) {
		t.Fatalf("contents %d, chapters %d of %d", len(overlaid.Contents), len(overlaid.Chapters()), len(plain.Chapters()))
	}
}

// The word set narrates a <par> per word, each a place of its own in the text.
func TestTheWordSetNarratesEachWord(t *testing.T) {
	world := newPackWorld(t, ReadalongPackOptions{ShiftMs: 400, GapMs: 30})
	pack, _ := world.find(t)
	sentences, err := ReadOverlaidAlignment(world.file, world.file.Size, pack.Overlay(GranularitySentence))
	if err != nil {
		t.Fatal(err)
	}
	words, err := ReadOverlaidAlignment(world.file, world.file.Size, pack.Overlay(GranularityWord))
	if err != nil {
		t.Fatal(err)
	}
	count := 0
	for _, file := range words.Files {
		count += len(file.Pars)
	}
	if count != len(world.pack.Words) || count <= len(world.edition.Pars) {
		t.Fatalf("%d words, want %d", count, len(world.pack.Words))
	}
	_, par, found := words.Find("OEBPS/Text/Author - [Series 01] - Part_1.xhtml", "id1-s2-w3")
	if !found || par.Fragment != "id1-s2-w3" {
		t.Fatalf("word %+v %v", par, found)
	}
	if !words.SamePieces(sentences) {
		t.Fatal("the word set's pieces are not the sentence set's")
	}
}

// The par cap: a word copy may hold far more pars than a sentence set or Storyteller's edition.
func TestTheParCapIsRaisedForWordsOnly(t *testing.T) {
	world := newPackWorld(t, ReadalongPackOptions{})
	pack, _ := world.find(t)
	defer func(saved int) { maxPars = saved }(maxPars)
	maxPars = len(world.edition.Pars) + 1
	if _, err := ReadOverlaidAlignment(world.file, world.file.Size, pack.Overlay(GranularityWord)); err != nil {
		t.Fatalf("a word set over the sentence cap: %v", err)
	}
	maxPars = len(world.edition.Pars) - 2
	if _, err := ReadAlignment(world.file, world.file.Size); err == nil {
		t.Fatal("the edition is over the sentence cap and was read")
	}
	if _, err := ReadOverlaidAlignment(world.file, world.file.Size, pack.Overlay(GranularitySentence)); err == nil {
		t.Fatal("the sentence set is over the sentence cap and was read")
	}
	defer func(saved int) { maxWordPars = saved }(maxWordPars)
	maxWordPars = len(world.pack.Words) - 1
	if _, err := ReadOverlaidAlignment(world.file, world.file.Size, pack.Overlay(GranularityWord)); err == nil {
		t.Fatal("a word set over the word cap was read")
	}
}

// The copy: the pack's entries in place of the edition's, restyled as the edition's are, the
// audio still left out, and everything else as it was.
func TestTheWordCopyIsTheEditionWithThePacksEntries(t *testing.T) {
	world := newPackWorld(t, ReadalongPackOptions{ShiftMs: 400})
	pack, _ := world.find(t)
	word := pack.Overlay(GranularityWord)
	var copied bytes.Buffer
	report, err := WriteReadingEPUB(&copied, world.file, world.file.Size, CopyOptions{OmitAudio: true, Restyle: true, MendNarration: true, Overlay: word})
	if err != nil {
		t.Fatal(err)
	}
	if report.Overlaid != len(word.Names()) || report.Mended != 0 || len(report.Omitted) != 3 {
		t.Fatalf("report %+v", report)
	}
	reader, err := zip.NewReader(bytes.NewReader(copied.Bytes()), int64(copied.Len()))
	if err != nil {
		t.Fatal(err)
	}
	source, err := zip.NewReader(world.file, world.file.Size)
	if err != nil {
		t.Fatal(err)
	}
	entry := func(r *zip.Reader, name string) string {
		for _, file := range r.File {
			if file.Name == name {
				stream, _ := file.Open()
				data, _ := io.ReadAll(stream)
				stream.Close()
				return string(data)
			}
		}
		return ""
	}
	text := entry(reader, "OEBPS/Text/Author - [Series 01] - Part_1.xhtml")
	if !strings.Contains(text, `<span id="id1-s0"><span id="id1-s0-w0">Sentence</span>`) {
		t.Fatalf("the text is not the word set's: %s", text)
	}
	// Restyled as the edition's text is: the two-column style the reading copy gives every document.
	plainCopy := new(bytes.Buffer)
	if _, err := WriteReadingEPUB(plainCopy, world.file, world.file.Size, CopyOptions{OmitAudio: true, Restyle: true}); err != nil {
		t.Fatal(err)
	}
	plainReader, _ := zip.NewReader(bytes.NewReader(plainCopy.Bytes()), int64(plainCopy.Len()))
	plainText := entry(plainReader, "OEBPS/Text/Author - [Series 01] - Part_1.xhtml")
	if plainText == entry(source, "OEBPS/Text/Author - [Series 01] - Part_1.xhtml") {
		t.Fatal("the fixture's text is not restyled at all, so this proves nothing")
	}
	styleAt := strings.Index(plainText, "<style")
	if styleAt < 0 || !strings.Contains(text, plainText[styleAt:strings.Index(plainText[styleAt:], "</style>")+styleAt]) {
		t.Fatalf("the word set's text is not restyled:\n%s\nwant the style of\n%s", text, plainText)
	}
	if smil := entry(reader, "OEBPS/MediaOverlays/Author - [Series 01] - Part_1.smil"); !strings.Contains(smil, `epub:textref="../Text/Author%20-%20%5BSeries%2001%5D%20-%20Part_1.xhtml#id1-s0"`) {
		t.Fatalf("the SMIL is not the word set's: %s", smil)
	}
	// Everything else is the edition's, entry for entry, in its order.
	var names []string
	for _, file := range reader.File {
		names = append(names, file.Name)
	}
	var sourceNames []string
	for _, file := range source.File {
		if _, audio := AudioKindOf(file.Name); !audio {
			sourceNames = append(sourceNames, file.Name)
		}
	}
	if strings.Join(names, "\n") != strings.Join(sourceNames, "\n") {
		t.Fatalf("entries\n%v\nwant\n%v", names, sourceNames)
	}
	if nav := entry(reader, "OEBPS/nav.xhtml"); nav != entry(plainReader, "OEBPS/nav.xhtml") {
		t.Fatal("an entry the pack does not hold changed")
	}
	// The whole edition with the word set, its audio kept, reads as the word set says.
	var whole bytes.Buffer
	if _, err := WriteReadingEPUB(&whole, world.file, world.file.Size, CopyOptions{Restyle: true, MendNarration: true, Overlay: word}); err != nil {
		t.Fatal(err)
	}
	words, err := ReadAlignment(bytes.NewReader(whole.Bytes()), int64(whole.Len()))
	if err != nil {
		t.Fatal(err)
	}
	count := 0
	for _, file := range words.Files {
		count += len(file.Pars)
	}
	if count != len(world.pack.Words) {
		t.Fatalf("the whole word copy narrates %d words, want %d", count, len(world.pack.Words))
	}
}

// A pack whose names are not the edition's is not applied at all.
func TestAPackThatDoesNotFitItsEditionIsRefused(t *testing.T) {
	world := newPackWorld(t, ReadalongPackOptions{Extra: map[string]map[string][]byte{
		GranularityWord: {"OEBPS/Text/not in the book.xhtml": []byte("<html/>")},
	}})
	pack, reason := world.find(t)
	if pack == nil {
		t.Fatalf("no pack: %s", reason)
	}
	word := pack.Overlay(GranularityWord)
	if _, err := PlanReadingEPUB(world.file, world.file.Size, CopyOptions{Overlay: word}); !errors.Is(err, ErrOverlayMismatch) {
		t.Fatalf("copy: %v", err)
	}
	if _, err := ReadOverlaidAlignment(world.file, world.file.Size, word); !errors.Is(err, ErrOverlayMismatch) || !errors.Is(err, ErrBadAlignment) {
		t.Fatalf("alignment: %v", err)
	}
	// The sentence set has no stray file and still holds.
	if _, err := ReadOverlaidAlignment(world.file, world.file.Size, pack.Overlay(GranularitySentence)); err != nil {
		t.Fatal(err)
	}
}

// The fingerprint follows the pack: a set written again is another overlay.
func TestTheFingerprintChangesWithThePack(t *testing.T) {
	world := newPackWorld(t, ReadalongPackOptions{})
	first, _ := world.find(t)
	again, _ := world.find(t)
	if first.Overlay(GranularityWord).Fingerprint != again.Overlay(GranularityWord).Fingerprint {
		t.Fatal("the same pack read twice is two fingerprints")
	}
	smil := filepath.Join(world.pack.Dir, GranularityWord, "OEBPS", "MediaOverlays", "Author - [Series 01] - Part_1.smil")
	data, err := os.ReadFile(smil)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(smil, append(data, '\n'), 0o644); err != nil {
		t.Fatal(err)
	}
	old := time.Now().Add(-time.Hour)
	if err := os.Chtimes(smil, old, old); err != nil {
		t.Fatal(err)
	}
	changed, _ := world.find(t)
	if changed.Overlay(GranularityWord).Fingerprint == first.Overlay(GranularityWord).Fingerprint {
		t.Fatal("a set written again has the same fingerprint")
	}
	if changed.Overlay(GranularitySentence).Fingerprint != first.Overlay(GranularitySentence).Fingerprint {
		t.Fatal("the sentence set changed with the word set")
	}
}
