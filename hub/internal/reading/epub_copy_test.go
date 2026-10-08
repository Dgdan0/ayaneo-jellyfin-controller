package reading

import (
	"archive/zip"
	"bytes"
	"crypto/sha256"
	"errors"
	"io"
	"math/rand"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"sync/atomic"
	"testing"
	"testing/iotest"
)

// copyOf writes the copy of a book on disk and returns it.
func copyOf(t *testing.T, path string, options CopyOptions) ([]byte, CopyReport) {
	t.Helper()
	file, size := openEPUB(t, path)
	var out bytes.Buffer
	report, err := WriteReadingEPUB(&out, file, size, options)
	if err != nil {
		t.Fatal(err)
	}
	return out.Bytes(), report
}

func slimOf(t *testing.T, path string) ([]byte, []string) {
	t.Helper()
	data, report := copyOf(t, path, CopyOptions{OmitAudio: true})
	return data, report.Omitted
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

// archiveOf opens zip bytes: the entries in order, and what each holds.
func archiveOf(t *testing.T, data []byte) ([]*zip.File, map[string][]byte) {
	t.Helper()
	reader, err := zip.NewReader(bytes.NewReader(data), int64(len(data)))
	if err != nil {
		t.Fatalf("not a zip: %v", err)
	}
	contents := map[string][]byte{}
	for _, entry := range reader.File {
		contents[entry.Name] = readAll(t, entry)
	}
	return reader.File, contents
}

func namesOf(files []*zip.File) []string {
	names := make([]string, len(files))
	for i, file := range files {
		names[i] = file.Name
	}
	return names
}

func TestCopyKeepsEveryEntryButTheAudioAsItWas(t *testing.T) {
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

// Without options the copy is the archive, entry for entry.
func TestCopyWithNoOptionsIsTheSameEntriesWithTheSameBytes(t *testing.T) {
	fixture := generateAligned(t, threeFileNarration())
	data, report := copyOf(t, fixture.Path, CopyOptions{})
	original, err := os.ReadFile(fixture.Path)
	if err != nil {
		t.Fatal(err)
	}
	files, copied := archiveOf(t, data)
	originalFiles, originals := archiveOf(t, original)
	if !reflect.DeepEqual(namesOf(files), namesOf(originalFiles)) || !reflect.DeepEqual(copied, originals) {
		t.Fatal("the copy is not the archive")
	}
	if len(report.Omitted) != 0 || report.Edited != 0 || report.FontSizes != 0 || report.Styled != 0 {
		t.Fatalf("report = %+v", report)
	}
}

// The same file always gives the same bytes, so a ranged, resumed download of
// it is the file it began.
func TestCopyIsTheSameBytesEveryTime(t *testing.T) {
	fixture := generateAligned(t, threeFileNarration())
	for _, options := range []CopyOptions{{OmitAudio: true}, {OmitAudio: true, Restyle: true}, {Restyle: true}} {
		first, _ := copyOf(t, fixture.Path, options)
		second, _ := copyOf(t, fixture.Path, options)
		if !bytes.Equal(first, second) {
			t.Fatalf("two runs differ with %+v", options)
		}
	}
}

// The audio is most of an edition and is never read: that is what keeps a
// 293 MB file out of memory and a slim copy at a megabyte.
func TestCopyNeverReadsTheAudioItLeavesOut(t *testing.T) {
	options := threeFileNarration()
	options.AudioBytes = 2 << 20
	fixture := generateAligned(t, options)
	file, size := openEPUB(t, fixture.Path)
	counted := &countingReaderAt{ReaderAt: file}
	var out bytes.Buffer
	if _, err := WriteReadingEPUB(&out, counted, size, CopyOptions{OmitAudio: true, Restyle: true}); err != nil {
		t.Fatal(err)
	}
	if read := counted.bytes.Load(); read > 512<<10 {
		t.Fatalf("read %d of %d bytes: the audio was read", read, size)
	}
	if out.Len() > 512<<10 {
		t.Fatalf("the slim edition is %d bytes", out.Len())
	}
}

func TestCopyLeavesOutAudioByAnyExtensionInAnyFolderAndNothingElse(t *testing.T) {
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
	files, _ := archiveOf(t, slim)
	names := map[string]bool{}
	for _, name := range namesOf(files) {
		names[name] = true
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

func TestCopyRefusesWhatIsNotAnArchive(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "x.epub")
	if err := os.WriteFile(path, []byte("not a zip archive at all"), 0o644); err != nil {
		t.Fatal(err)
	}
	file, size := openEPUB(t, path)
	var out bytes.Buffer
	if _, err := WriteReadingEPUB(&out, file, size, CopyOptions{OmitAudio: true}); !errors.Is(err, ErrNotAnEPUB) {
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
	if _, err := WriteReadingEPUB(&out, file, size, CopyOptions{}); !errors.Is(err, ErrNotAnEPUB) {
		t.Fatalf("an archive over the entry cap: %v", err)
	}
}

// A writer that fails stops the copy and says so.
func TestCopyStopsWhenItsWriterGoesAway(t *testing.T) {
	fixture := generateAligned(t, threeFileNarration())
	file, size := openEPUB(t, fixture.Path)
	if _, err := WriteReadingEPUB(failingWriter{}, file, size, CopyOptions{Restyle: true}); err == nil || !strings.Contains(err.Error(), "gone") {
		t.Fatalf("err = %v", err)
	}
}

type failingWriter struct{}

func (failingWriter) Write([]byte) (int, error) { return 0, errors.New("the client is gone") }

// A reader that stops being able to read (a cancelled request, a disk that went)
// stops the copy, whether it was planning or serving, with the reader's own error.
func TestCopyStopsWhenItsSourceCannotBeRead(t *testing.T) {
	options := threeFileNarration()
	options.AudioBytes = 1 << 20
	fixture := generateAligned(t, options)
	file, size := openEPUB(t, fixture.Path)
	gone := errors.New("the request was cancelled")
	for _, allowed := range []int64{0, 4 << 10, 700 << 10} {
		failing := &failAfterReaderAt{ReaderAt: file, allowed: allowed, err: gone}
		_, err := WriteReadingEPUB(io.Discard, failing, size, CopyOptions{Restyle: true})
		if !errors.Is(err, gone) && !errors.Is(err, ErrNotAnEPUB) {
			t.Errorf("after %d bytes: %v", allowed, err)
		}
		if allowed > 0 && !errors.Is(err, gone) {
			t.Errorf("after %d bytes the copy ended with %v, not the reader's error", allowed, err)
		}
	}

	// Served from a plan, the source is read as the client reads.
	plan, err := PlanReadingEPUB(file, size, CopyOptions{})
	if err != nil {
		t.Fatal(err)
	}
	failing := &failAfterReaderAt{ReaderAt: file, allowed: 0, err: gone}
	if _, err := io.Copy(io.Discard, plan.Reader(failing)); !errors.Is(err, gone) {
		t.Fatalf("serving with a source that cannot be read: %v", err)
	}
}

type failAfterReaderAt struct {
	io.ReaderAt
	allowed int64
	read    atomic.Int64
	err     error
}

func (f *failAfterReaderAt) ReadAt(p []byte, offset int64) (int, error) {
	if f.read.Add(int64(len(p))) > f.allowed {
		return 0, f.err
	}
	return f.ReaderAt.ReadAt(p, offset)
}

// What is served is what is written, byte for byte, at any offset and in any size
// of read, whether an entry is held in memory or read from the source file.
func TestAPlanServesTheBytesItWrites(t *testing.T) {
	for _, held := range []int64{16 << 10, 0} {
		held := held
		t.Run(map[bool]string{true: "small entries held", false: "every entry read from the source"}[held > 0], func(t *testing.T) {
			previous := heldEntryBytes
			t.Cleanup(func() { heldEntryBytes = previous })
			heldEntryBytes = held

			options := threeFileNarration()
			options.AudioBytes = 8 << 10
			fixture := generateAligned(t, options)
			path := rewriteEPUB(t, fixture.Path, func(files map[string][]byte, order *[]string) {
				files["OEBPS/Styles/book.css"] = []byte("body { font-size: small } p { font-size: 12px }")
				*order = append(*order, "OEBPS/Styles/book.css")
				// A big entry that is not audio, and does not compress.
				big := make([]byte, 40<<10)
				rand.New(rand.NewSource(7)).Read(big)
				files["OEBPS/images/plate.jpg"] = big
				*order = append(*order, "OEBPS/images/plate.jpg")
			})
			file, size := openEPUB(t, path)
			for _, variant := range []CopyOptions{{}, {OmitAudio: true}, {Restyle: true}, {OmitAudio: true, Restyle: true}} {
				plan, err := PlanReadingEPUB(file, size, variant)
				if err != nil {
					t.Fatal(err)
				}
				var written bytes.Buffer
				if _, err := WriteReadingEPUB(&written, file, size, variant); err != nil {
					t.Fatal(err)
				}
				want := written.Bytes()
				if plan.Size != int64(len(want)) || plan.SHA256 != sha256.Sum256(want) {
					t.Fatalf("%+v: size %d (want %d) or hash differ", variant, plan.Size, len(want))
				}
				if held == 0 && plan.Held() > 64<<10 {
					t.Errorf("%+v: %d bytes held with every entry left in the source", variant, plan.Held())
				}
				// Both kinds of piece are in play, or this proves nothing about either.
				inMemory, source := 0, 0
				for _, piece := range plan.pieces {
					if piece.mem != nil {
						inMemory++
					} else {
						source++
					}
				}
				if inMemory == 0 || source == 0 {
					t.Errorf("%+v: %d held pieces and %d from the source", variant, inMemory, source)
				}
				if err := iotest.TestReader(plan.Reader(file), want); err != nil {
					t.Fatalf("%+v: %v", variant, err)
				}
				random := rand.New(rand.NewSource(11))
				reader := plan.Reader(file)
				for i := 0; i < 400; i++ {
					offset := random.Int63n(int64(len(want)) + 10)
					length := random.Intn(5000)
					buf := make([]byte, length)
					n, err := reader.ReadAt(buf, offset)
					end := min(offset+int64(length), int64(len(want)))
					if offset >= int64(len(want)) {
						end = int64(len(want))
						offset = min(offset, end)
					}
					expected := want[min(offset, int64(len(want))):end]
					if !bytes.Equal(buf[:n], expected) || (n < length && err == nil) || (n == length && err != nil && err != io.EOF) {
						t.Fatalf("%+v: ReadAt(%d bytes at %d) = %d, %v", variant, length, offset, n, err)
					}
				}
			}
		})
	}
}

// The plan reads a source once and keeps a book's text and headers; the pages of
// pictures stay where they are.
func TestAPlanKeepsOnlyWhatItMadeAndTheSmallEntries(t *testing.T) {
	fixture := generateAligned(t, threeFileNarration())
	path := rewriteEPUB(t, fixture.Path, func(files map[string][]byte, order *[]string) {
		big := make([]byte, 3<<20)
		rand.New(rand.NewSource(3)).Read(big)
		files["OEBPS/images/plate.jpg"] = big
		*order = append(*order, "OEBPS/images/plate.jpg")
	})
	file, size := openEPUB(t, path)
	plan, err := PlanReadingEPUB(file, size, CopyOptions{OmitAudio: true, Restyle: true})
	if err != nil {
		t.Fatal(err)
	}
	if plan.Held() > 256<<10 || plan.Size < 3<<20 {
		t.Fatalf("a %d byte copy holds %d bytes", plan.Size, plan.Held())
	}
	limited, err := PlanReadingEPUB(file, size, CopyOptions{OmitAudio: true, Restyle: true, MaxHeld: 1000})
	if limited != nil || !errors.Is(err, ErrCopyTooLarge) {
		t.Fatalf("a plan over its limit: %v", err)
	}
	if plan, err := PlanReadingEPUB(file, size, CopyOptions{OmitAudio: true, Restyle: true, MaxHeld: plan.Held()}); err != nil || plan == nil {
		t.Fatalf("a plan exactly at its limit: %v", err)
	}
}
