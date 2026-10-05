package reading

import (
	"archive/zip"
	"fmt"
	"io"
	"path/filepath"
	"strings"
	"testing"
)

// A read-along edition as Storyteller makes it, small: three narrated files,
// the second so long that it is cut into two chunks, as a source over its
// maximum track length is.
func threeFileNarration() AlignedEPUBOptions {
	return AlignedEPUBOptions{
		PackageDir: "OEBPS",
		AudioBytes: 4096,
		Narrations: []FixtureNarration{
			{ChunkMs: []int64{100_000}, Sentences: []int{8}},
			{ChunkMs: []int64{7_200_000, 3_300_000}, Sentences: []int{12, 6}},
			{ChunkMs: []int64{50_000}, Sentences: []int{5}},
		},
	}
}

func TestGeneratedAlignedEPUBIsAReadAlongEditionWithStoredAudio(t *testing.T) {
	path := filepath.Join(t.TempDir(), "aligned.epub")
	fixture, err := GenerateAlignedEPUB(path, threeFileNarration())
	if err != nil {
		t.Fatal(err)
	}
	if fixture.Package != "OEBPS/content.opf" || len(fixture.Audio) != 4 || len(fixture.Pars) != 8+12+6+5 {
		t.Fatalf("package %q, %d audio files, %d sentences", fixture.Package, len(fixture.Audio), len(fixture.Pars))
	}
	for i, want := range []string{"OEBPS/Audio/00001-00001.mp3", "OEBPS/Audio/00002-00001.mp3", "OEBPS/Audio/00002-00002.mp3", "OEBPS/Audio/00003-00001.mp3"} {
		if fixture.Audio[i] != want {
			t.Errorf("audio %d = %q, want %q", i, fixture.Audio[i], want)
		}
	}

	reader, err := zip.OpenReader(path)
	if err != nil {
		t.Fatal(err)
	}
	defer reader.Close()
	if first := reader.File[0]; first.Name != "mimetype" || first.Method != zip.Store {
		t.Fatalf("the first entry is %q (method %d), want the stored mimetype", first.Name, first.Method)
	}
	methods := map[string]uint16{}
	for _, file := range reader.File {
		methods[file.Name] = file.Method
	}
	for _, audio := range fixture.Audio {
		if methods[audio] != zip.Store {
			t.Errorf("%s is not stored", audio)
		}
	}
	if methods["OEBPS/smil/part0001.smil"] != zip.Deflate {
		t.Errorf("the SMIL is not compressed")
	}

	// Each chunk's sentences are gapless and the last ends where the chunk does.
	byAudio := map[string][]FixturePar{}
	for _, par := range fixture.Pars {
		byAudio[par.Audio] = append(byAudio[par.Audio], par)
	}
	for _, chunk := range fixture.Chunks {
		pars := byAudio[chunk.Entry]
		if len(pars) == 0 || pars[0].BeginMs != 0 || pars[len(pars)-1].EndMs != chunk.LengthMs {
			t.Fatalf("%s does not run from 0 to %d: %+v", chunk.Entry, chunk.LengthMs, pars)
		}
		for i := 1; i < len(pars); i++ {
			if pars[i].BeginMs != pars[i-1].EndMs {
				t.Fatalf("%s has a gap or overlap before sentence %d", chunk.Entry, i)
			}
		}
	}

	smil := readZipText(t, reader, "OEBPS/smil/part0002.smil")
	// The same moment is written in more than one way.
	for _, form := range []string{"s\"", ":", "ms\"", "min\"", "h\""} {
		if !strings.Contains(smil, form) {
			t.Errorf("the SMIL never writes a clock as %q:\n%.600s", form, smil)
		}
	}
	root := generateAligned(t, threeFileNarration())
	if root.Package != fixture.Package || len(root.Pars) != len(fixture.Pars) {
		t.Fatal("generation is not deterministic")
	}
}

func TestGeneratedAlignedEPUBCanPutItsPackageAtTheRoot(t *testing.T) {
	options := threeFileNarration()
	options.PackageDir = ""
	fixture := generateAligned(t, options)
	if fixture.Package != "content.opf" || fixture.Audio[0] != "Audio/00001-00001.mp3" || fixture.Pars[0].Text != "text/part0001.xhtml" {
		t.Fatalf("root package: %q, %q, %q", fixture.Package, fixture.Audio[0], fixture.Pars[0].Text)
	}
}

// The shape of an edition made from a Calibre book (Mistborn's is one): the
// package at the root of the archive, the text beside it as .htm, the overlays in
// MediaOverlays, the audio as .mp4 chunks, and names with spaces and brackets
// written in the package and the overlays as they are, not percent-encoded.
// Chapters are cut across chunks, as the real ones are.
func calibreShape() AlignedEPUBOptions {
	return AlignedEPUBOptions{
		AudioBytes: 1024,
		Layout: AlignedLayout{
			AudioExt: ".mp4", OverlayDir: "MediaOverlays", TextAtRoot: true, TextExt: ".htm",
			ChapterName: func(chapter int) string {
				return fmt.Sprintf("Brandon Sanderson - [Mistborn 01] - The Final Empire_split_%03d", chapter)
			},
		},
		Narrations: []FixtureNarration{
			// Chapter 2 begins in the first chunk and ends in the second.
			{ChunkMs: []int64{60_000, 30_000}, Sentences: []int{4, 2}, Chapters: []int{3, 2, 1}},
			{ChunkMs: []int64{40_000}, Sentences: []int{4}},
		},
	}
}

func TestGeneratedAlignedEPUBCanTakeTheShapeOfACalibreEdition(t *testing.T) {
	path := filepath.Join(t.TempDir(), "aligned.epub")
	fixture, err := GenerateAlignedEPUB(path, calibreShape())
	if err != nil {
		t.Fatal(err)
	}
	const name = "Brandon Sanderson - [Mistborn 01] - The Final Empire_split_%03d"
	if fixture.Package != "content.opf" || len(fixture.Audio) != 3 || len(fixture.Pars) != 4+2+4 {
		t.Fatalf("package %q, %d audio files, %d sentences", fixture.Package, len(fixture.Audio), len(fixture.Pars))
	}
	for i, want := range []string{"Audio/00001-00001.mp4", "Audio/00001-00002.mp4", "Audio/00002-00001.mp4"} {
		if fixture.Audio[i] != want {
			t.Errorf("audio %d = %q, want %q", i, fixture.Audio[i], want)
		}
	}
	// Sentences are named for their chapter and their place in it; chapter 2 holds
	// the last sentence of the first chunk and the first of the second.
	second := fixture.Pars[3]
	third := fixture.Pars[4]
	if second.Text != fmt.Sprintf(name, 2)+".htm" || second.Fragment != "id2-s0" || second.Audio != "Audio/00001-00001.mp4" ||
		third.Text != fmt.Sprintf(name, 2)+".htm" || third.Fragment != "id2-s1" || third.Audio != "Audio/00001-00002.mp4" || third.BeginMs != 0 {
		t.Fatalf("the chapter that crosses a chunk: %+v then %+v", second, third)
	}
	if last := fixture.Pars[9]; last.Text != fmt.Sprintf(name, 4)+".htm" || last.Fragment != "id4-s3" || last.Audio != "Audio/00002-00001.mp4" {
		t.Fatalf("the last sentence: %+v", last)
	}

	reader, err := zip.OpenReader(path)
	if err != nil {
		t.Fatal(err)
	}
	defer reader.Close()
	var names []string
	for _, file := range reader.File {
		names = append(names, file.Name)
	}
	for _, want := range []string{"mimetype", "META-INF/container.xml", "content.opf",
		fmt.Sprintf(name, 1) + ".htm", fmt.Sprintf(name, 4) + ".htm",
		"MediaOverlays/" + fmt.Sprintf(name, 1) + ".smil", "MediaOverlays/" + fmt.Sprintf(name, 4) + ".smil"} {
		found := false
		for _, have := range names {
			found = found || have == want
		}
		if !found {
			t.Errorf("no %q in %q", want, names)
		}
	}
	opf := readZipText(t, reader, "content.opf")
	for _, want := range []string{
		`<item id="ch2" href="` + fmt.Sprintf(name, 2) + `.htm" media-type="application/xhtml+xml" media-overlay="ov2"/>`,
		`<item id="ov2" href="MediaOverlays/` + fmt.Sprintf(name, 2) + `.smil" media-type="application/smil+xml"/>`,
		`<item id="au1-2" href="Audio/00001-00002.mp4" media-type="audio/mp4"/>`,
	} {
		if !strings.Contains(opf, want) {
			t.Errorf("the package lacks %s", want)
		}
	}
	smil := readZipText(t, reader, "MediaOverlays/"+fmt.Sprintf(name, 2)+".smil")
	for _, want := range []string{`src="../` + fmt.Sprintf(name, 2) + `.htm#id2-s0"`, `src="../Audio/00001-00001.mp4"`, `src="../Audio/00001-00002.mp4"`} {
		if !strings.Contains(smil, want) {
			t.Errorf("the overlay lacks %s: %s", want, smil)
		}
	}
	if methods := map[string]uint16{}; true {
		for _, file := range reader.File {
			methods[file.Name] = file.Method
		}
		if methods["Audio/00002-00001.mp4"] != zip.Store {
			t.Error("the audio is not stored")
		}
	}
}

// Percent-encoded references name the same files: an archive keeps the names as
// they are, and a reference to them is a URL reference.
func TestGeneratedAlignedEPUBCanWriteItsReferencesPercentEncoded(t *testing.T) {
	options := calibreShape()
	options.Layout.EncodedRefs = true
	path := filepath.Join(t.TempDir(), "aligned.epub")
	if _, err := GenerateAlignedEPUB(path, options); err != nil {
		t.Fatal(err)
	}
	reader, err := zip.OpenReader(path)
	if err != nil {
		t.Fatal(err)
	}
	defer reader.Close()
	opf := readZipText(t, reader, "content.opf")
	smil := readZipText(t, reader, "MediaOverlays/Brandon Sanderson - [Mistborn 01] - The Final Empire_split_001.smil")
	if !strings.Contains(opf, `href="Brandon%20Sanderson%20-%20%5BMistborn%2001%5D%20-%20The%20Final%20Empire_split_001.htm"`) ||
		!strings.Contains(opf, `href="MediaOverlays/Brandon%20Sanderson%20-%20%5BMistborn%2001%5D%20-%20The%20Final%20Empire_split_001.smil"`) {
		t.Errorf("the package's references are not encoded: %.900s", opf)
	}
	if !strings.Contains(smil, `src="../Brandon%20Sanderson%20-%20%5BMistborn%2001%5D%20-%20The%20Final%20Empire_split_001.htm#id1-s0"`) {
		t.Errorf("the overlay's references are not encoded: %s", smil)
	}
}

// The text and the narration need not agree on an order: a book's front matter is
// read where it is, and a heading may be spoken late.
func TestGeneratedAlignedEPUBCanListItsChaptersInAnotherOrderThanTheyAreSpoken(t *testing.T) {
	options := calibreShape()
	options.SpineOrder = []int{3, 0, 1, 2}
	path := filepath.Join(t.TempDir(), "aligned.epub")
	fixture, err := GenerateAlignedEPUB(path, options)
	if err != nil {
		t.Fatal(err)
	}
	reader, err := zip.OpenReader(path)
	if err != nil {
		t.Fatal(err)
	}
	defer reader.Close()
	opf := readZipText(t, reader, "content.opf")
	spine := opf[strings.Index(opf, "<spine>"):]
	if order := []string{`idref="ch4"`, `idref="ch1"`, `idref="ch2"`, `idref="ch3"`}; !inOrder(spine, order) {
		t.Fatalf("the spine is not in the order asked for: %s", spine)
	}
	// The truth about the narration is still in the order it is spoken.
	if fixture.Pars[0].Fragment != "id1-s0" || fixture.Pars[9].Fragment != "id4-s3" {
		t.Fatalf("the sentences moved: %+v ... %+v", fixture.Pars[0], fixture.Pars[9])
	}
}

func inOrder(text string, parts []string) bool {
	at := 0
	for _, part := range parts {
		i := strings.Index(text[at:], part)
		if i < 0 {
			return false
		}
		at += i + len(part)
	}
	return true
}

// generateAligned writes a fixture under a temp folder and returns what it holds.
func generateAligned(t *testing.T, options AlignedEPUBOptions) AlignedEPUBFixture {
	t.Helper()
	fixture, err := GenerateAlignedEPUB(filepath.Join(t.TempDir(), "aligned.epub"), options)
	if err != nil {
		t.Fatal(err)
	}
	return fixture
}

func readZipText(t *testing.T, reader *zip.ReadCloser, name string) string {
	t.Helper()
	for _, file := range reader.File {
		if file.Name != name {
			continue
		}
		entry, err := file.Open()
		if err != nil {
			t.Fatal(err)
		}
		defer entry.Close()
		data, err := io.ReadAll(entry)
		if err != nil {
			t.Fatal(err)
		}
		return string(data)
	}
	t.Fatalf("no entry %s", name)
	return ""
}
