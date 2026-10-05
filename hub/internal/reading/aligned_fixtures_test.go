package reading

import (
	"archive/zip"
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
