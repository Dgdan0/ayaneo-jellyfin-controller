package reading

import (
	"archive/zip"
	"errors"
	"io"
	"net/url"
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

// A reader spells a name with spaces and brackets as it is, or percent-encoded
// as a URL reference is, with or without a slash in front; the edition's package
// is at the root, so its documents have no folder to be relative to.
func TestAlignmentFindTakesANameWithSpacesAndBracketsHoweverItIsSpelt(t *testing.T) {
	fixture := generateAligned(t, calibreShape())
	alignment, err := readAlignmentOf(t, fixture.Path)
	if err != nil {
		t.Fatal(err)
	}
	want := fixture.Pars[4] // chapter 2, whose second sentence opens the second chunk
	if want.Text != "Brandon Sanderson - [Mistborn 01] - The Final Empire_split_002.htm" {
		t.Fatalf("the sentence is in %q", want.Text)
	}
	for name, href := range map[string]string{
		"as it is":                     want.Text,
		"percent-encoded":              url.PathEscape(want.Text),
		"a slash in front":             "/" + want.Text,
		"percent-encoded, a slash":     "/" + url.PathEscape(want.Text),
		"the fragment on the name":     want.Text + "#" + want.Fragment,
		"the fragment, encoded name":   url.PathEscape(want.Text) + "#" + want.Fragment,
		"lower-case escapes (%5b %5d)": strings.NewReplacer("%5B", "%5b", "%5D", "%5d").Replace(url.PathEscape(want.Text)),
	} {
		fragment := want.Fragment
		if strings.Contains(href, "#") {
			fragment = ""
		}
		file, par, ok := alignment.Find(href, fragment)
		if !ok || par.Fragment != want.Fragment || par.BeginMs != want.BeginMs || alignment.Files[file].Entry != want.Audio {
			t.Errorf("%s: Find(%q, %q) = file %d, %+v, %v", name, href, fragment, file, par, ok)
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
	if err != nil || !reflect.DeepEqual(got.File, []int{1, 2, 0}) || got.ByOrder {
		t.Fatalf("match = %+v, %v", got, err)
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
	// A file that only one narration can be settles what is left: the first
	// narration fits one file alone, the second fits both, so it is the other. That
	// is the lengths' doing, and the order of the files has no part in it.
	for durations, want := range map[[2]int64][]int{
		{3_600_050, 3_600_500}: {0, 1},
		{3_600_500, 3_600_050}: {1, 0},
	} {
		got, err = MatchSources([]AlignedSource{span(1, 3_600_000), span(1, 3_600_300)}, durations[:])
		if err != nil || !reflect.DeepEqual(got.File, want) || got.ByOrder {
			t.Errorf("durations %v: %+v, %v, want %v by length", durations, got, err, want)
		}
	}
	if _, err := MatchSources(nil, nil); !errors.Is(err, ErrAlignmentCount) {
		t.Errorf("nothing to match: %v", err)
	}
}

// Lengths cannot always tell the narrated files apart: Mistborn's two parts are
// 12:20:13 and 12:20:13, fourteen milliseconds apart in what was narrated. The
// narration takes the book's files in the order it reads them, so the files
// lengths leave open are paired in the order they are given (the order they are
// played in), and the pairing says that is how it was made.
func TestMatchSourcesPairsFilesOfOneLengthInTheOrderTheyAreRead(t *testing.T) {
	span := func(number, chunks int, length int64) AlignedSource {
		return AlignedSource{Number: number, Files: make([]int, chunks), LengthMs: length}
	}
	// The real two: seven chunks each, so a tolerance of 355 ms, which both fit.
	got, err := MatchSources(
		[]AlignedSource{span(1, 7, 44_413_476), span(2, 7, 44_413_462)},
		[]int64{44_413_512, 44_413_530})
	if err != nil || !reflect.DeepEqual(got.File, []int{0, 1}) || !got.ByOrder {
		t.Fatalf("two parts of one length = %+v, %v", got, err)
	}

	// Lengths decide first, and order only what they leave open: the file nothing
	// else can be is settled, and the two that are alike are paired in order.
	got, err = MatchSources(
		[]AlignedSource{span(1, 1, 1_000_000), span(2, 1, 3_599_990), span(3, 1, 3_600_090)},
		[]int64{3_600_000, 1_000_012, 3_600_100})
	if err != nil || !reflect.DeepEqual(got.File, []int{1, 0, 2}) || !got.ByOrder {
		t.Fatalf("one told apart and two alike = %+v, %v", got, err)
	}

	// More than two alike, in order.
	got, err = MatchSources(
		[]AlignedSource{span(1, 1, 3_600_000), span(2, 1, 3_600_005), span(3, 1, 3_600_010), span(4, 1, 3_600_015)},
		[]int64{3_600_000, 3_600_005, 3_600_010, 3_600_015})
	if err != nil || !reflect.DeepEqual(got.File, []int{0, 1, 2, 3}) || !got.ByOrder {
		t.Fatalf("four alike = %+v, %v", got, err)
	}

	// The order is not a guess against the lengths: where what is left open would
	// have to put a narration on a file it is not the length of, nothing is paired.
	// These three are 3600.000, 3600.200 and 3600.400 s long; the first narration is
	// the length of the second or third, the second of the first or second, and the
	// third of any, so in order the first would be the first file, which it cannot be.
	_, err = MatchSources(
		[]AlignedSource{span(1, 1, 3_600_300), span(2, 1, 3_600_100), span(3, 1, 3_600_200)},
		[]int64{3_600_000, 3_600_200, 3_600_400})
	if !errors.Is(err, ErrAlignmentAmbiguous) {
		t.Fatalf("an order that lengths contradict: %v", err)
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

// The text of an edition is in the order of the book and its narration is in the
// order it is spoken, and they need not agree. Mistborn's lists a short chapter
// (two sentences) among its front matter and speaks it in the middle of the second
// audio file, between the end of one chapter and the beginning of another. Taken in
// the order the overlays are listed, that file's sentences go back in time; taken in
// the order they are spoken, which their own times say, they do not.
func TestReadAlignmentReadsNarrationInTheOrderItIsSpokenWhateverOrderTheTextIsListedIn(t *testing.T) {
	options := calibreShape()
	options.Narrations = []FixtureNarration{
		{ChunkMs: []int64{3_600_000, 1_800_000}, Sentences: []int{6, 4}, Chapters: []int{4, 6}},
		// Chapters 3, 4 and 5 share the first chunk; the second is two sentences.
		{ChunkMs: []int64{3_600_000, 3_600_000, 900_000}, Sentences: []int{5, 6, 3}, Chapters: []int{2, 2, 10}},
	}
	// Chapter 4, spoken after chapter 3, is listed ahead of everything.
	options.SpineOrder = []int{3, 0, 1, 2, 4}
	fixture := generateAligned(t, options)
	alignment, err := readAlignmentOf(t, fixture.Path)
	if err != nil {
		t.Fatalf("a chapter spoken later than it is listed: %v", err)
	}

	// The audio files are in the order they are spoken, not the order the text first
	// reaches them, and each holds its sentences as they are spoken.
	if len(alignment.Files) != len(fixture.Audio) {
		t.Fatalf("%d audio files, want %d", len(alignment.Files), len(fixture.Audio))
	}
	spoken := map[string][]FixturePar{}
	for _, par := range fixture.Pars {
		spoken[par.Audio] = append(spoken[par.Audio], par)
	}
	for i, file := range alignment.Files {
		if file.Entry != fixture.Audio[i] {
			t.Fatalf("file %d is %s, want %s", i, file.Entry, fixture.Audio[i])
		}
		want := spoken[file.Entry]
		if len(file.Pars) != len(want) {
			t.Fatalf("%s holds %d sentences, want %d", file.Entry, len(file.Pars), len(want))
		}
		for k, par := range file.Pars {
			if par.Text != want[k].Text || par.Fragment != want[k].Fragment || par.BeginMs != want[k].BeginMs || par.EndMs != want[k].EndMs {
				t.Fatalf("%s sentence %d = %+v, want %+v", file.Entry, k, par, want[k])
			}
		}
		if file.LengthMs != want[len(want)-1].EndMs {
			t.Errorf("%s ends at %d, want %d", file.Entry, file.LengthMs, want[len(want)-1].EndMs)
		}
	}

	// Every sentence is found where it is spoken, and every moment of it names it,
	// the displaced chapter's among them.
	for _, par := range fixture.Pars {
		index, found, ok := alignment.Find(par.Text, par.Fragment)
		if !ok || alignment.Files[index].Entry != par.Audio || found.BeginMs != par.BeginMs {
			t.Fatalf("Find(%s, %s) = file %d %+v, %v", par.Text, par.Fragment, index, found, ok)
		}
		for _, at := range []int64{par.BeginMs, (par.BeginMs + par.EndMs) / 2, par.EndMs - 1} {
			if got, ok := alignment.Sentence(index, at); !ok || got.Fragment != par.Fragment || got.Text != par.Text {
				t.Fatalf("at %d ms of %s: %+v, %v, want %s", at, par.Audio, got, ok, par.Fragment)
			}
		}
	}
}

// A sentence that is out of its place within one overlay is no more a reason to
// refuse the edition than one out of its place among them: each is where its own
// times put it.
func TestReadAlignmentSortsSentencesOfOneOverlayByWhenTheyAreSpoken(t *testing.T) {
	base := generateAligned(t, threeFileNarration()).Path
	changed := rewriteEPUB(t, base, func(f map[string][]byte, _ *[]string) {
		replaceIn(f, "OEBPS/smil/part0001.smil", `clipBegin="0:00:12.500"`, `clipBegin="3000ms"`)
		replaceIn(f, "OEBPS/smil/part0001.smil", `clipBegin="00:25.000"`, `clipBegin="1000ms"`)
	})
	alignment, err := readAlignmentOf(t, changed)
	if err != nil {
		t.Fatal(err)
	}
	for _, file := range alignment.Files {
		for i := 1; i < len(file.Pars); i++ {
			if file.Pars[i].BeginMs < file.Pars[i-1].BeginMs {
				t.Fatalf("%s: sentence %d begins at %d, before the one ahead of it at %d", file.Entry, i, file.Pars[i].BeginMs, file.Pars[i-1].BeginMs)
			}
		}
	}
	// What moved can still be found, and heard.
	first := alignment.Files[0]
	if got, ok := alignment.Sentence(0, 1000); !ok || got.BeginMs != 1000 {
		t.Fatalf("at 1 s of %s: %+v, %v", first.Entry, got, ok)
	}
}

// Mistborn: The Final Empire, as measured: two narrated files of seven chunks each,
// every chunk cut where a sentence ends, so none is two hours exactly; the audio
// is .mp4, the package is at the root and its names have spaces and brackets. The
// two files are 44413.476 and 44413.462 seconds long.
func TestReadAlignmentOfTheShapeOfARealCalibreEdition(t *testing.T) {
	options := calibreShape()
	options.Narrations = []FixtureNarration{
		{ChunkMs: []int64{7_198_016, 7_198_670, 7_183_314, 7_218_066, 7_196_612, 7_204_781, 1_214_017}, Sentences: []int{4, 4, 4, 4, 4, 4, 2}},
		{ChunkMs: []int64{7_198_368, 7_201_550, 7_194_720, 7_204_909, 7_197_192, 7_201_760, 1_214_963}, Sentences: []int{4, 4, 4, 4, 4, 4, 2}},
	}
	for _, encoded := range []bool{false, true} {
		options.Layout.EncodedRefs = encoded
		fixture := generateAligned(t, options)
		alignment, err := readAlignmentOf(t, fixture.Path)
		if err != nil {
			t.Fatalf("encoded references %v: %v", encoded, err)
		}
		if alignment.Package != "content.opf" || len(alignment.Files) != 14 {
			t.Fatalf("package %q, %d audio files", alignment.Package, len(alignment.Files))
		}
		sources, err := alignment.Sources()
		if err != nil || len(sources) != 2 || len(sources[0].Files) != 7 || len(sources[1].Files) != 7 ||
			sources[0].LengthMs != 44_413_476 || sources[1].LengthMs != 44_413_462 {
			t.Fatalf("sources = %+v, %v", sources, err)
		}
		if want := []int64{0, 7_198_016, 14_396_686, 21_580_000, 28_798_066, 35_994_678, 43_199_459}; !reflect.DeepEqual(sources[0].ChunkStartMs, want) {
			t.Fatalf("chunk starts = %v, want %v", sources[0].ChunkStartMs, want)
		}
		// The two are the length of both of two files 12:20:13 long, and are paired
		// by the order they are read in.
		got, err := MatchSources(sources, []int64{44_413_512, 44_413_530})
		if err != nil || !reflect.DeepEqual(got.File, []int{0, 1}) || !got.ByOrder {
			t.Fatalf("pairing = %+v, %v", got, err)
		}
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
