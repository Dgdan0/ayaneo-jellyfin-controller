package reading

import (
	"archive/zip"
	"errors"
	"io"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"sync/atomic"
	"testing"
)

func openEPUB(t *testing.T, path string) (*os.File, int64) {
	t.Helper()
	file, err := os.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { file.Close() })
	info, err := file.Stat()
	if err != nil {
		t.Fatal(err)
	}
	return file, info.Size()
}

func readAlignmentOf(t *testing.T, path string) (*Alignment, error) {
	t.Helper()
	file, size := openEPUB(t, path)
	return ReadAlignment(file, size)
}

func TestReadAlignmentReturnsEverySentenceOfAGeneratedEditionInOrder(t *testing.T) {
	for _, dir := range []string{"OEBPS", ""} {
		t.Run("package in "+dir, func(t *testing.T) {
			options := threeFileNarration()
			options.PackageDir = dir
			fixture := generateAligned(t, options)
			alignment, err := readAlignmentOf(t, fixture.Path)
			if err != nil {
				t.Fatal(err)
			}
			if alignment.Package != fixture.Package || len(alignment.Files) != len(fixture.Chunks) {
				t.Fatalf("package %q with %d files", alignment.Package, len(alignment.Files))
			}
			var pars []FixturePar
			for i, file := range alignment.Files {
				want := fixture.Chunks[i]
				if file.Entry != want.Entry || file.Source != want.Source || file.Chunk != want.Chunk || file.LengthMs != want.LengthMs {
					t.Errorf("file %d = %+v, want %+v", i, file, want)
				}
				for _, par := range file.Pars {
					pars = append(pars, FixturePar{Text: par.Text, Fragment: par.Fragment, Audio: file.Entry, BeginMs: par.BeginMs, EndMs: par.EndMs})
				}
			}
			if !reflect.DeepEqual(pars, fixture.Pars) {
				t.Fatalf("the sentences read are not the ones written (%d of %d)", len(pars), len(fixture.Pars))
			}
		})
	}
}

// countingReaderAt shows how much of an edition is read: its audio is most of
// its bytes and must stay unread.
type countingReaderAt struct {
	io.ReaderAt
	bytes atomic.Int64
}

func (c *countingReaderAt) ReadAt(p []byte, offset int64) (int, error) {
	n, err := c.ReaderAt.ReadAt(p, offset)
	c.bytes.Add(int64(n))
	return n, err
}

func TestReadAlignmentReadsTheDirectoryAndTheXMLAndNeverTheAudio(t *testing.T) {
	options := threeFileNarration()
	options.AudioBytes = 2 << 20 // four files, 8 MiB of audio
	fixture := generateAligned(t, options)
	file, size := openEPUB(t, fixture.Path)
	if size < 8<<20 {
		t.Fatalf("the edition is %d bytes: the audio is not there to be left unread", size)
	}
	counted := &countingReaderAt{ReaderAt: file}
	if _, err := ReadAlignment(counted, size); err != nil {
		t.Fatal(err)
	}
	if read := counted.bytes.Load(); read > 512<<10 {
		t.Fatalf("read %d of %d bytes: the audio was read", read, size)
	}
}

func TestParseClockAcceptsEveryFormAndRefusesTheRest(t *testing.T) {
	for raw, want := range map[string]int64{
		"0.000s": 0, "16.000s": 16000, "12.5s": 12500, "12": 12000, "12.250": 12250, " 5s ": 5000,
		"0:00:12.500": 12500, "1:02:03.5": 3723500, "00:12.500": 12500, "2:03.250": 123250, "01:40.000": 100000,
		"1500ms": 1500, "0ms": 0, "1.5min": 90000, "0.208333333min": 12500, "0.5h": 1800000, "0.003472222222h": 12500,
		"npt=3.2s": 3200, "npt=1:02": 62000, "7200.000s": 7200000, "1.001s": 1001,
	} {
		got, err := parseClock(raw)
		if err != nil || got != want {
			t.Errorf("parseClock(%q) = %d, %v, want %d", raw, got, err, want)
		}
	}
	for _, raw := range []string{"", " ", "abc", "-1s", "-5", "1:2:3:4", "1:x", "NaNs", "Inf", "1e400", "1e9s", "99999999999s", "s", "ms", ":", "1::2", "0x10", "--"} {
		if got, err := parseClock(raw); err == nil {
			t.Errorf("parseClock(%q) = %d, want a refusal", raw, got)
		}
	}
}

func TestAlignmentSentenceIsTheLastOneBegunByATime(t *testing.T) {
	alignment := &Alignment{Files: []AlignedFile{{Entry: "a", LengthMs: 40, Pars: []AlignedPar{
		{Text: "t", Fragment: "s0", BeginMs: 10, EndMs: 20},
		{Text: "t", Fragment: "s1", BeginMs: 20, EndMs: 30},
		{Text: "t", Fragment: "s2", BeginMs: 35, EndMs: 40}, // a gap before it
	}}, {Entry: "empty"}}}
	for ms, want := range map[int64]string{
		-5: "s0", 0: "s0", 9: "s0", 10: "s0", 19: "s0", 20: "s1", 29: "s1", 30: "s1", 34: "s1", 35: "s2", 39: "s2", 40: "s2", 5000: "s2",
	} {
		par, ok := alignment.Sentence(0, ms)
		if !ok || par.Fragment != want {
			t.Errorf("at %d ms: %q, %v, want %q", ms, par.Fragment, ok, want)
		}
	}
	if _, ok := alignment.Sentence(1, 0); ok {
		t.Error("a file with no sentences gave one")
	}
	if _, ok := alignment.Sentence(7, 0); ok {
		t.Error("a file that is not there gave a sentence")
	}
	if _, ok := alignment.Sentence(-1, 0); ok {
		t.Error("file -1 gave a sentence")
	}
}

func TestAlignmentFindToleratesHowALocatorSpellsAHref(t *testing.T) {
	fixture := generateAligned(t, threeFileNarration())
	alignment, err := readAlignmentOf(t, fixture.Path)
	if err != nil {
		t.Fatal(err)
	}
	want := fixture.Pars[3] // part 1, fourth sentence
	for _, href := range []string{
		"OEBPS/text/part0001.xhtml",  // the zip path, as the app's read-along writes it
		"/OEBPS/text/part0001.xhtml", // a leading slash, which Storyteller stores too
		"text/part0001.xhtml",        // relative to the package
		"/text/part0001.xhtml",
		"OEBPS/text/part0001.xhtml#" + want.Fragment, // the fragment in the href
		"OEBPS/text%2Fpart0001.xhtml",                // an escaped slash
		"OEBPS/text/part%30001.xhtml",                // an escaped digit
	} {
		fragment := want.Fragment
		if strings.Contains(href, "#") {
			fragment = ""
		}
		file, par, ok := alignment.Find(href, fragment)
		if !ok || par.Fragment != want.Fragment || par.BeginMs != want.BeginMs || alignment.Files[file].Entry != want.Audio {
			t.Errorf("Find(%q, %q) = file %d, %+v, %v", href, fragment, file, par, ok)
		}
	}
	for _, miss := range [][2]string{
		{"OEBPS/text/part0001.xhtml", "id1-s999"},
		{"OEBPS/text/part0001.xhtml", "ID1-S3"},
		{"OEBPS/text/part0001.xhtml", ""},
		{"OEBPS/text/part0002.xhtml", "id1-s3"},
		{"part0001.xhtml", "id1-s3"},
		{"", "id1-s3"},
		{"../OEBPS/text/part0001.xhtml", "id1-s3"},
		{"OEBPS/text/part0001.xhtml.bak", "id1-s3"},
	} {
		if _, par, ok := alignment.Find(miss[0], miss[1]); ok {
			t.Errorf("Find(%q, %q) found %+v", miss[0], miss[1], par)
		}
	}
}

func TestAlignmentSourcesGroupTheChunksOfOneNarratedFile(t *testing.T) {
	fixture := generateAligned(t, threeFileNarration())
	alignment, err := readAlignmentOf(t, fixture.Path)
	if err != nil {
		t.Fatal(err)
	}
	sources, err := alignment.Sources()
	if err != nil {
		t.Fatal(err)
	}
	want := []AlignedSource{
		{Number: 1, Files: []int{0}, ChunkStartMs: []int64{0}, LengthMs: 100_000},
		{Number: 2, Files: []int{1, 2}, ChunkStartMs: []int64{0, 7_200_000}, LengthMs: 10_500_000},
		{Number: 3, Files: []int{3}, ChunkStartMs: []int64{0}, LengthMs: 50_000},
	}
	if !reflect.DeepEqual(sources, want) {
		t.Fatalf("sources = %+v\nwant %+v", sources, want)
	}

	for name, files := range map[string][]AlignedFile{
		"a name that is not Storyteller's": {{Entry: "OEBPS/Audio/chapter1.mp3", LengthMs: 5}},
		"a gap in the chunks":              {{Entry: "a/00001-00001.mp3", Source: 1, Chunk: 1, LengthMs: 5}, {Entry: "a/00001-00003.mp3", Source: 1, Chunk: 3, LengthMs: 5}},
		"chunks that start at two":         {{Entry: "a/00001-00002.mp3", Source: 1, Chunk: 2, LengthMs: 5}},
		"one chunk twice":                  {{Entry: "a/00001-00001.mp3", Source: 1, Chunk: 1, LengthMs: 5}, {Entry: "b/00001-00001.mp3", Source: 1, Chunk: 1, LengthMs: 5}},
		"a source that has no length":      {{Entry: "a/00001-00001.mp3", Source: 1, Chunk: 1, LengthMs: 0}},
		"no files":                         nil,
	} {
		if _, err := (&Alignment{Files: files}).Sources(); !errors.Is(err, ErrBadAlignment) || (name == "no files" && !errors.Is(err, ErrNoAlignment)) {
			t.Errorf("%s: %v", name, err)
		}
	}
	// Out of order in the edition, in order by number.
	shuffled := &Alignment{Files: []AlignedFile{
		{Entry: "a/00002-00001.mp3", Source: 2, Chunk: 1, LengthMs: 7},
		{Entry: "a/00001-00002.mp3", Source: 1, Chunk: 2, LengthMs: 6},
		{Entry: "a/00001-00001.mp3", Source: 1, Chunk: 1, LengthMs: 5},
	}}
	got, err := shuffled.Sources()
	if err != nil || len(got) != 2 || got[0].Number != 1 || !reflect.DeepEqual(got[0].Files, []int{2, 1}) || !reflect.DeepEqual(got[0].ChunkStartMs, []int64{0, 5}) || got[0].LengthMs != 11 {
		t.Fatalf("shuffled = %+v, %v", got, err)
	}
	// Storyteller names the chapters of a lone M4B from zero ("00000-00001.mp3" in
	// its manifest), and a narration of them may number its files the same way.
	// Which file is which is the lengths' business, not the number's.
	fromZero := &Alignment{Files: []AlignedFile{
		{Entry: "a/00000-00001.mp3", Source: 0, Chunk: 1, LengthMs: 5},
		{Entry: "a/00001-00001.mp3", Source: 1, Chunk: 1, LengthMs: 7},
	}}
	if got, err := fromZero.Sources(); err != nil || len(got) != 2 || got[0].Number != 0 || got[1].Number != 1 || got[0].LengthMs != 5 || got[1].LengthMs != 7 {
		t.Fatalf("a narration numbered from zero = %+v, %v", got, err)
	}
}

func TestMatchSourcesPairsNarrationWithFilesByLengthNotByNumber(t *testing.T) {
	span := func(chunks int, length int64) AlignedSource {
		return AlignedSource{Number: 1, Files: make([]int, chunks), LengthMs: length}
	}
	// Storyteller numbers its files in directory order and lists them by name, so
	// the numbers say nothing of which file is which.
	got, err := MatchSources(
		[]AlignedSource{span(1, 100_000), span(2, 10_500_000), span(1, 50_000)},
		[]int64{50_012, 100_012, 10_500_030},
	)
	if err != nil || !reflect.DeepEqual(got, []int{1, 2, 0}) {
		t.Fatalf("match = %v, %v", got, err)
	}

	// The tolerance is 250 ms and 15 ms for each chunk.
	for _, test := range []struct {
		chunks int
		off    int64
		ok     bool
	}{
		{1, 250 + 15, true}, {1, 250 + 15 + 1, false}, {1, -(250 + 15), true}, {1, -(250 + 15 + 1), false},
		{4, 250 + 60, true}, {4, 250 + 60 + 1, false}, {2, 0, true},
	} {
		_, err := MatchSources([]AlignedSource{span(test.chunks, 60_000)}, []int64{60_000 + test.off})
		if (err == nil) != test.ok {
			t.Errorf("%d chunks off by %d ms: %v, want ok=%v", test.chunks, test.off, err, test.ok)
		}
	}

	if _, err := MatchSources([]AlignedSource{span(1, 5_000)}, []int64{5_000, 9_000}); !errors.Is(err, ErrAlignmentCount) {
		t.Errorf("fewer narrated files than files: %v", err)
	}
	if _, err := MatchSources([]AlignedSource{span(1, 5_000), span(1, 9_000)}, []int64{5_000}); !errors.Is(err, ErrAlignmentCount) {
		t.Errorf("more narrated files than files: %v", err)
	}
	if _, err := MatchSources([]AlignedSource{span(1, 5_000), span(1, 9_000)}, []int64{5_000, 12_000}); !errors.Is(err, ErrAlignmentMismatch) {
		t.Errorf("a file with no length to match: %v", err)
	}
	// Two files of one length cannot be told apart by it.
	if _, err := MatchSources([]AlignedSource{span(1, 3_600_000), span(1, 3_600_050)}, []int64{3_600_000, 3_600_020}); !errors.Is(err, ErrAlignmentAmbiguous) {
		t.Errorf("two files of nearly one length: %v", err)
	}
	// But a file that only one narration can be settles what is left: the first
	// narration fits one file alone, the second fits both, so it is the other.
	for durations, want := range map[[2]int64][]int{
		{3_600_050, 3_600_500}: {0, 1},
		{3_600_500, 3_600_050}: {1, 0},
	} {
		got, err = MatchSources([]AlignedSource{span(1, 3_600_000), span(1, 3_600_300)}, durations[:])
		if err != nil || !reflect.DeepEqual(got, want) {
			t.Errorf("durations %v: %v, %v, want %v", durations, got, err, want)
		}
	}
	if _, err := MatchSources(nil, nil); !errors.Is(err, ErrAlignmentCount) {
		t.Errorf("nothing to match: %v", err)
	}
}

// rewriteEPUB writes a copy of a generated edition with its entries changed.
func rewriteEPUB(t *testing.T, source string, change func(files map[string][]byte, order *[]string)) string {
	t.Helper()
	reader, err := zip.OpenReader(source)
	if err != nil {
		t.Fatal(err)
	}
	defer reader.Close()
	files := map[string][]byte{}
	var order []string
	for _, entry := range reader.File {
		if entry.Name == "mimetype" {
			continue
		}
		stream, err := entry.Open()
		if err != nil {
			t.Fatal(err)
		}
		data, err := io.ReadAll(stream)
		stream.Close()
		if err != nil {
			t.Fatal(err)
		}
		files[entry.Name] = data
		order = append(order, entry.Name)
	}
	change(files, &order)
	specs := make([]zipFileSpec, 0, len(order))
	for _, name := range order {
		if data, ok := files[name]; ok {
			_, isAudio := AudioKindOf(name)
			specs = append(specs, zipFileSpec{name: name, data: data, store: isAudio})
		}
	}
	data, err := writeZip("application/epub+zip", specs)
	if err != nil {
		t.Fatal(err)
	}
	out := filepath.Join(t.TempDir(), "changed.epub")
	if err := os.WriteFile(out, data, 0o644); err != nil {
		t.Fatal(err)
	}
	return out
}

func replaceIn(files map[string][]byte, name, old, replacement string) {
	files[name] = []byte(strings.Replace(string(files[name]), old, replacement, 1))
}

func TestReadAlignmentRefusesWhatCouldHarmOrMislead(t *testing.T) {
	base := generateAligned(t, threeFileNarration()).Path
	const smil, opf, container, text = "OEBPS/smil/part0001.smil", "OEBPS/content.opf", "META-INF/container.xml", "OEBPS/text/part0001.xhtml"
	doctype := `<!DOCTYPE smil [<!ENTITY boom "boom">]>`
	for name, change := range map[string]func(files map[string][]byte, order *[]string){
		"a DOCTYPE in a SMIL": func(f map[string][]byte, _ *[]string) {
			replaceIn(f, smil, "<smil ", doctype+"<smil ")
		},
		"a DOCTYPE in the package": func(f map[string][]byte, _ *[]string) {
			replaceIn(f, opf, "<package ", doctype+"<package ")
		},
		"a DOCTYPE in the container": func(f map[string][]byte, _ *[]string) {
			replaceIn(f, container, "<container ", doctype+"<container ")
		},
		"an entity declared in a SMIL": func(f map[string][]byte, _ *[]string) {
			replaceIn(f, smil, "<smil ", `<!ENTITY x "y"><smil `)
		},
		"an encoding the reader does not know": func(f map[string][]byte, _ *[]string) {
			replaceIn(f, smil, `encoding="UTF-8"`, `encoding="UTF-7"`)
		},
		"no container": func(f map[string][]byte, o *[]string) { delete(f, container) },
		"no package":   func(f map[string][]byte, o *[]string) { delete(f, opf) },
		"a container with no rootfile": func(f map[string][]byte, _ *[]string) {
			f[container] = []byte(`<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles/></container>`)
		},
		"a media overlay that is not in the manifest": func(f map[string][]byte, _ *[]string) {
			replaceIn(f, opf, `media-overlay="ov1"`, `media-overlay="nowhere"`)
		},
		"a SMIL that is not in the archive": func(f map[string][]byte, o *[]string) { delete(f, smil) },
		"text that is not in the archive":   func(f map[string][]byte, o *[]string) { delete(f, text) },
		"audio that is not in the archive": func(f map[string][]byte, o *[]string) {
			delete(f, "OEBPS/Audio/00001-00001.mp3")
		},
		"a sentence with no fragment": func(f map[string][]byte, _ *[]string) {
			replaceIn(f, smil, `part0001.xhtml#id1-s0"`, `part0001.xhtml"`)
		},
		"a path that climbs out of the edition": func(f map[string][]byte, _ *[]string) {
			replaceIn(f, smil, `src="../Audio/00001-00001.mp3"`, `src="../../../Audio/00001-00001.mp3"`)
		},
		"an absolute path": func(f map[string][]byte, _ *[]string) {
			replaceIn(f, smil, `src="../Audio/00001-00001.mp3"`, `src="/OEBPS/Audio/00001-00001.mp3"`)
		},
		"an address": func(f map[string][]byte, _ *[]string) {
			replaceIn(f, smil, `src="../Audio/00001-00001.mp3"`, `src="http://example.test/00001-00001.mp3"`)
		},
		"a backslash": func(f map[string][]byte, _ *[]string) {
			replaceIn(f, smil, `src="../Audio/00001-00001.mp3"`, `src="..\Audio\00001-00001.mp3"`)
		},
		"a query": func(f map[string][]byte, _ *[]string) {
			replaceIn(f, smil, `src="../Audio/00001-00001.mp3"`, `src="../Audio/00001-00001.mp3?x=1"`)
		},
		"a sentence that ends before it begins": func(f map[string][]byte, _ *[]string) {
			replaceIn(f, smil, `clipBegin="0.000s" clipEnd="12500ms"`, `clipBegin="12.5s" clipEnd="2s"`)
		},
		"a sentence that is not a time": func(f map[string][]byte, _ *[]string) {
			replaceIn(f, smil, `clipEnd="12500ms"`, `clipEnd="soon"`)
		},
		"sentences out of order in one file": func(f map[string][]byte, _ *[]string) {
			replaceIn(f, smil, `clipBegin="0:00:12.500"`, `clipBegin="3000ms"`)
			replaceIn(f, smil, `clipBegin="00:25.000"`, `clipBegin="1000ms"`)
		},
		"not a zip": func(f map[string][]byte, o *[]string) {},
	} {
		t.Run(name, func(t *testing.T) {
			path := base
			if name != "not a zip" {
				path = rewriteEPUB(t, base, change)
			} else {
				path = filepath.Join(t.TempDir(), "not.epub")
				if err := os.WriteFile(path, []byte("not a zip archive"), 0o644); err != nil {
					t.Fatal(err)
				}
			}
			alignment, err := readAlignmentOf(t, path)
			if err == nil {
				t.Fatalf("accepted: %d files", len(alignment.Files))
			}
			if !errors.Is(err, ErrBadAlignment) {
				t.Fatalf("the failure is not an ErrBadAlignment: %v", err)
			}
			if strings.Contains(err.Error(), "boom") || strings.Contains(err.Error(), os.TempDir()) {
				t.Fatalf("the error repeats what it was given: %v", err)
			}
		})
	}
}

func TestReadAlignmentCapsWhatItWillHold(t *testing.T) {
	base := generateAligned(t, threeFileNarration()).Path
	original := struct {
		xml           int64
		pars, entries int
	}{maxXMLBytes, maxPars, maxEntries}
	t.Cleanup(func() { maxXMLBytes, maxPars, maxEntries = original.xml, original.pars, original.entries })

	maxXMLBytes = 1 << 10
	if _, err := readAlignmentOf(t, base); !errors.Is(err, ErrBadAlignment) {
		t.Errorf("a SMIL over the XML cap: %v", err)
	}
	maxXMLBytes = original.xml
	maxPars = 20
	if _, err := readAlignmentOf(t, base); !errors.Is(err, ErrBadAlignment) {
		t.Errorf("more sentences than the cap: %v", err)
	}
	maxPars = original.pars
	maxEntries = 6
	if _, err := readAlignmentOf(t, base); !errors.Is(err, ErrBadAlignment) {
		t.Errorf("more entries than the cap: %v", err)
	}
	maxEntries = original.entries
	if _, err := readAlignmentOf(t, base); err != nil {
		t.Fatalf("with the caps restored: %v", err)
	}
}

func TestReadAlignmentLeavesOutASentenceWithNothingToHearAndReadsEachSMILOnce(t *testing.T) {
	base := generateAligned(t, threeFileNarration())
	changed := rewriteEPUB(t, base.Path, func(f map[string][]byte, _ *[]string) {
		// A zero-length boundary, as word alignment can emit for an unmatched word.
		replaceIn(f, "OEBPS/smil/part0003.smil", `<par id="p26">`, `<par id="zero"><text src="../text/part0003.xhtml#id3-s0"/><audio src="../Audio/00003-00001.mp3" clipBegin="0s" clipEnd="0s"/></par><par id="p26">`)
		// The same overlay named by a second chapter.
		replaceIn(f, "OEBPS/content.opf", `<spine>`, `<spine><itemref idref="ch1"/>`)
	})
	alignment, err := readAlignmentOf(t, changed)
	if err != nil {
		t.Fatal(err)
	}
	total := 0
	for _, file := range alignment.Files {
		total += len(file.Pars)
	}
	if total != len(base.Pars) {
		t.Fatalf("%d sentences, want the %d written: the zero-length one stays out and an overlay is read once", total, len(base.Pars))
	}
}

func TestReadAlignmentOfAnEditionWithNoNarrationIsNotAnAlignment(t *testing.T) {
	base := generateAligned(t, threeFileNarration()).Path
	path := rewriteEPUB(t, base, func(f map[string][]byte, _ *[]string) {
		replaceIn(f, "OEBPS/content.opf", ` media-overlay="ov1"`, "")
		replaceIn(f, "OEBPS/content.opf", ` media-overlay="ov2"`, "")
		replaceIn(f, "OEBPS/content.opf", ` media-overlay="ov3"`, "")
	})
	if _, err := readAlignmentOf(t, path); !errors.Is(err, ErrNoAlignment) {
		t.Fatalf("an EPUB with no media overlays: %v", err)
	}
}
