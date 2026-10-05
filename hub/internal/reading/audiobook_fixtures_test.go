package reading

import (
	"bytes"
	"crypto/sha256"
	"encoding/binary"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"
)

func TestTrackedAudiobookFixtureNeedsItsTagsToBePutInOrder(t *testing.T) {
	root := t.TempDir()
	book, err := GenerateTrackedAudiobook(root)
	if err != nil {
		t.Fatal(err)
	}
	if book.Relative != "audiobooks/Fixture Odyssey" || book.Dir != filepath.Join(root, "audiobooks", "Fixture Odyssey") {
		t.Fatalf("folder = %q, %q", book.Relative, book.Dir)
	}
	if len(book.Files) != 5 {
		t.Fatalf("%d files, want 5", len(book.Files))
	}

	// What Storyteller's manifest lists is not the story's order: the file with
	// no suffix is the first track by its tags and sorts after the others.
	tags := make([]int, len(book.Files))
	for i, file := range book.Files {
		tags[i] = file.Track
	}
	if sort.IntsAreSorted(tags) {
		t.Fatalf("the manifest order %v is already the tag order, so a test of ordering would prove nothing", tags)
	}
	sorted := append([]int(nil), tags...)
	sort.Ints(sorted)
	for i, want := range []int{1, 2, 3, 4, 5} {
		if sorted[i] != want {
			t.Fatalf("tags %v are not exactly 1..5", tags)
		}
	}

	seen := map[[32]byte]string{}
	for _, file := range book.Files {
		audio, err := os.ReadFile(filepath.Join(book.Dir, file.Name))
		if err != nil {
			t.Fatal(err)
		}
		if int64(len(audio)) != file.Size {
			t.Errorf("%s is %d bytes, described as %d", file.Name, len(audio), file.Size)
		}
		frames := fixtureID3TextFrames(t, audio)
		if frames["TRCK"] != fmt.Sprintf("%d/5", file.Track) {
			t.Errorf("%s carries track %q, described as %d", file.Name, frames["TRCK"], file.Track)
		}
		if frames["TIT2"] == "" || frames["TALB"] != "Fixture Odyssey" {
			t.Errorf("%s: title %q, album %q", file.Name, frames["TIT2"], frames["TALB"])
		}
		digest := sha256.Sum256(audio)
		if other, dup := seen[digest]; dup {
			t.Errorf("%s and %s are the same bytes, so a wrong track would go unnoticed", file.Name, other)
		}
		seen[digest] = file.Name
	}
}

func TestTrackedAudiobookFixtureHasTheNamesThatBreakThingsAndNoneThatAreNotWindows(t *testing.T) {
	root := t.TempDir()
	book, err := GenerateTrackedAudiobook(root)
	if err != nil {
		t.Fatal(err)
	}
	var all string
	for _, file := range book.Files {
		all += file.Name
		for _, bad := range `<>:"/\|?*` {
			if strings.ContainsRune(file.Name, bad) {
				t.Errorf("%q holds %q, which a Windows folder cannot", file.Name, bad)
			}
		}
		// Every generated name is one the hub's read-only resolver takes.
		roots := mediaRoots(root)
		opened, err := ResolveMediaFile(roots, "storyteller", "/library/"+book.Relative+"/"+file.Name)
		if err != nil {
			t.Errorf("%q is refused by ResolveMediaFile: %v", file.Name, err)
			continue
		}
		opened.Close()
	}
	for _, want := range []string{" (1)", "%", "&", "#", "[", "'", ",", "Ü", "ï", "פרק", ".MP3"} {
		if !strings.Contains(all, want) {
			t.Errorf("no generated name holds %q", want)
		}
	}
}

func TestM4BAudiobookFixtureIsAFolderWithOneM4BAndThingsThatAreNotAudio(t *testing.T) {
	root := t.TempDir()
	book, err := GenerateM4BAudiobook(root)
	if err != nil {
		t.Fatal(err)
	}
	if book.Relative != "audiobooks/Fixture Chapters" || len(book.Files) != 1 || book.Files[0].Name != "Fixture Chapters.m4b" {
		t.Fatalf("book = %+v", book)
	}
	data, err := os.ReadFile(filepath.Join(book.Dir, "Fixture Chapters.m4b"))
	if err != nil {
		t.Fatal(err)
	}
	if int64(len(data)) != book.Files[0].Size || len(data) < 64<<10 {
		t.Fatalf("m4b is %d bytes, described as %d", len(data), book.Files[0].Size)
	}
	// An MP4 box tree: an ftyp that names the brand, then an mdat that fills
	// the rest. The bytes inside are stored, not audio: only the hub's reading
	// of them as a file is under test.
	if string(data[4:8]) != "ftyp" || string(data[8:12]) != "M4B " {
		t.Fatalf("header = %q", data[:16])
	}
	ftypSize := int(binary.BigEndian.Uint32(data[:4]))
	if string(data[ftypSize+4:ftypSize+8]) != "mdat" || int(binary.BigEndian.Uint32(data[ftypSize:ftypSize+4])) != len(data)-ftypSize {
		t.Fatalf("the mdat box does not fill the rest of the file")
	}
	for _, name := range []string{"cover.jpg", "notes.txt"} {
		if _, err := os.Stat(filepath.Join(book.Dir, name)); err != nil {
			t.Errorf("%s was not generated: %v", name, err)
		}
	}
	if len(book.Others) != 2 {
		t.Errorf("others = %v", book.Others)
	}
}

func TestAudiobookFixturesAreDeterministic(t *testing.T) {
	digestOf := func() string {
		root := t.TempDir()
		var all bytes.Buffer
		for _, generate := range []func(string) (AudiobookFixture, error){GenerateTrackedAudiobook, GenerateM4BAudiobook} {
			book, err := generate(root)
			if err != nil {
				t.Fatal(err)
			}
			for _, file := range book.Files {
				data, err := os.ReadFile(filepath.Join(book.Dir, file.Name))
				if err != nil {
					t.Fatal(err)
				}
				all.Write(data)
			}
		}
		sum := sha256.Sum256(all.Bytes())
		return fmt.Sprintf("%x", sum)
	}
	if a, b := digestOf(), digestOf(); a != b {
		t.Fatalf("two runs differ: %s and %s", a, b)
	}
}

// A retag with no track leaves the generated audio exactly as it was before
// track tags existed, so the lab's own fixture set is unchanged.
func TestRetaggingWithoutATrackIsTheOldBytes(t *testing.T) {
	audio, err := fixtureMP3()
	if err != nil {
		t.Fatal(err)
	}
	frames := fixtureID3TextFrames(t, audio)
	if _, has := frames["TRCK"]; has {
		t.Fatal("the lab narration gained a track tag")
	}
}
