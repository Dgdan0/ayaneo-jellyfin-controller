package reading

import (
	"archive/zip"
	"bytes"
	"errors"
	"io"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

func slimOf(t *testing.T, path string) ([]byte, []string) {
	t.Helper()
	file, size := openEPUB(t, path)
	var out bytes.Buffer
	omitted, err := WriteSlimEPUB(&out, file, size)
	if err != nil {
		t.Fatal(err)
	}
	return out.Bytes(), omitted
}

func TestSlimEPUBKeepsEveryEntryButTheAudioAsItWas(t *testing.T) {
	options := threeFileNarration()
	options.AudioBytes = 64 << 10
	fixture := generateAligned(t, options)
	slim, omitted := slimOf(t, fixture.Path)

	if !reflect.DeepEqual(omitted, fixture.Audio) {
		t.Fatalf("omitted %v, want %v", omitted, fixture.Audio)
	}
	original, err := zip.OpenReader(fixture.Path)
	if err != nil {
		t.Fatal(err)
	}
	defer original.Close()
	reduced, err := zip.NewReader(bytes.NewReader(slim), int64(len(slim)))
	if err != nil {
		t.Fatalf("the slim edition is not a zip: %v", err)
	}

	var want []*zip.File
	for _, entry := range original.File {
		if _, audio := AudioKindOf(entry.Name); !audio {
			want = append(want, entry)
		}
	}
	if len(reduced.File) != len(want) {
		t.Fatalf("%d entries, want %d", len(reduced.File), len(want))
	}
	for i, entry := range reduced.File {
		kept := want[i]
		if entry.Name != kept.Name || entry.Method != kept.Method || entry.CRC32 != kept.CRC32 ||
			entry.CompressedSize64 != kept.CompressedSize64 || entry.UncompressedSize64 != kept.UncompressedSize64 {
			t.Errorf("entry %d is %+v, want %+v", i, entry.FileHeader, kept.FileHeader)
		}
		// Reading it checks its CRC: the bytes are the bytes.
		if got, wantBody := readAll(t, entry), readAll(t, kept); !bytes.Equal(got, wantBody) {
			t.Errorf("%s changed", entry.Name)
		}
	}
	// An EPUB opens on its mimetype, stored.
	if first := reduced.File[0]; first.Name != "mimetype" || first.Method != zip.Store || string(readAll(t, first)) != "application/epub+zip" {
		t.Fatalf("the first entry is %q (method %d)", first.Name, first.Method)
	}
	if full, _ := os.Stat(fixture.Path); int64(len(slim))*10 > full.Size() {
		t.Fatalf("the slim edition is %d bytes of %d: the audio is still in it", len(slim), full.Size())
	}
	// Nothing the SMIL names is lost but the audio: its text is all there.
	for name := range map[string]bool{"OEBPS/smil/part0001.smil": true, "OEBPS/text/part0002.xhtml": true, "OEBPS/content.opf": true, "META-INF/container.xml": true} {
		found := false
		for _, entry := range reduced.File {
			found = found || entry.Name == name
		}
		if !found {
			t.Errorf("%s was left out", name)
		}
	}
}

func readAll(t *testing.T, entry *zip.File) []byte {
	t.Helper()
	stream, err := entry.Open()
	if err != nil {
		t.Fatal(err)
	}
	defer stream.Close()
	data, err := io.ReadAll(stream)
	if err != nil {
		t.Fatalf("%s: %v", entry.Name, err)
	}
	return data
}

// The same file always gives the same bytes, so a ranged, resumed download of
// it is the file it began.
func TestSlimEPUBIsTheSameBytesEveryTime(t *testing.T) {
	fixture := generateAligned(t, threeFileNarration())
	first, _ := slimOf(t, fixture.Path)
	second, _ := slimOf(t, fixture.Path)
	if !bytes.Equal(first, second) {
		t.Fatal("two runs differ")
	}
}

// The audio is most of an edition and is never read: that is what keeps a
// 293 MB file out of memory and a slim copy at a megabyte.
func TestSlimEPUBNeverReadsTheAudio(t *testing.T) {
	options := threeFileNarration()
	options.AudioBytes = 2 << 20
	fixture := generateAligned(t, options)
	file, size := openEPUB(t, fixture.Path)
	counted := &countingReaderAt{ReaderAt: file}
	var out bytes.Buffer
	if _, err := WriteSlimEPUB(&out, counted, size); err != nil {
		t.Fatal(err)
	}
	if read := counted.bytes.Load(); read > 512<<10 {
		t.Fatalf("read %d of %d bytes: the audio was read", read, size)
	}
	if out.Len() > 512<<10 {
		t.Fatalf("the slim edition is %d bytes", out.Len())
	}
}

func TestSlimEPUBLeavesOutAudioByAnyExtensionInAnyFolderAndNothingElse(t *testing.T) {
	fixture := generateAligned(t, threeFileNarration())
	path := rewriteEPUB(t, fixture.Path, func(files map[string][]byte, order *[]string) {
		for name, data := range map[string]string{
			"OEBPS/extras/Song.OPUS": "o", "OEBPS/extras/deep/er/voice.m4a": "m", "OEBPS/Audio/cover.jpg": "image",
			"OEBPS/Audio/notes.txt": "text", "OEBPS/audio-notes.mp3.txt": "text", "OEBPS/fonts/serif.woff": "font",
		} {
			files[name] = []byte(data)
			*order = append(*order, name)
		}
	})
	slim, omitted := slimOf(t, path)
	reduced, err := zip.NewReader(bytes.NewReader(slim), int64(len(slim)))
	if err != nil {
		t.Fatal(err)
	}
	names := map[string]bool{}
	for _, entry := range reduced.File {
		names[entry.Name] = true
	}
	for _, gone := range []string{"OEBPS/extras/Song.OPUS", "OEBPS/extras/deep/er/voice.m4a", "OEBPS/Audio/00001-00001.mp3"} {
		if names[gone] {
			t.Errorf("%s is still there", gone)
		}
	}
	for _, kept := range []string{"OEBPS/Audio/cover.jpg", "OEBPS/Audio/notes.txt", "OEBPS/audio-notes.mp3.txt", "OEBPS/fonts/serif.woff", "mimetype"} {
		if !names[kept] {
			t.Errorf("%s was left out", kept)
		}
	}
	if len(omitted) != 4+2 {
		t.Errorf("omitted %v", omitted)
	}
}

func TestSlimEPUBRefusesWhatIsNotAnArchive(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "x.epub")
	if err := os.WriteFile(path, []byte("not a zip archive at all"), 0o644); err != nil {
		t.Fatal(err)
	}
	file, size := openEPUB(t, path)
	var out bytes.Buffer
	if _, err := WriteSlimEPUB(&out, file, size); !errors.Is(err, ErrNotAnEPUB) {
		t.Fatalf("err = %v", err)
	}
	if out.Len() != 0 {
		t.Fatalf("%d bytes were written before the refusal", out.Len())
	}

	fixture := generateAligned(t, threeFileNarration())
	original := maxEntries
	t.Cleanup(func() { maxEntries = original })
	maxEntries = 5
	file, size = openEPUB(t, fixture.Path)
	if _, err := WriteSlimEPUB(&out, file, size); !errors.Is(err, ErrNotAnEPUB) {
		t.Fatalf("an archive over the entry cap: %v", err)
	}
}

// A writer that fails stops the copy and says so.
func TestSlimEPUBStopsWhenItsReaderGoesAway(t *testing.T) {
	fixture := generateAligned(t, threeFileNarration())
	file, size := openEPUB(t, fixture.Path)
	if _, err := WriteSlimEPUB(failingWriter{}, file, size); err == nil || !strings.Contains(err.Error(), "gone") {
		t.Fatalf("err = %v", err)
	}
}

type failingWriter struct{}

func (failingWriter) Write([]byte) (int, error) { return 0, errors.New("the client is gone") }
