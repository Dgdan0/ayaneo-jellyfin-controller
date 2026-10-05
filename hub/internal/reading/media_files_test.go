package reading

import (
	"ayaneohub/internal/config"
	"errors"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"runtime"
	"testing"
)

func mediaRoots(root string) []config.MediaRemovalRoot {
	return []config.MediaRemovalRoot{{Service: "storyteller", Remote: "/library", Local: root}}
}

// writeMediaFiles makes each named file under root; names are slash-separated
// and the folders they sit in are made on the way.
func writeMediaFiles(t *testing.T, root string, files map[string]string) {
	t.Helper()
	for name, body := range files {
		full := filepath.Join(root, filepath.FromSlash(name))
		if err := os.MkdirAll(filepath.Dir(full), 0o700); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(full, []byte(body), 0o600); err != nil {
			t.Fatal(err)
		}
	}
}

func failureOf(t *testing.T, err error) MediaFailure {
	t.Helper()
	var media *MediaError
	if !errors.As(err, &media) {
		t.Fatalf("error %v does not say why: it is not a MediaError", err)
	}
	return media.Failure
}

// trySymlink needs a privilege some accounts do not hold (this machine's does
// not), so the callers skip rather than fail when it is missing.
func trySymlink(t *testing.T, target, link string) bool {
	t.Helper()
	if err := os.Symlink(target, link); err != nil {
		t.Logf("no symlink privilege here, so this case is skipped: %v", err)
		return false
	}
	return true
}

// tryJunction makes a Windows directory junction, which needs no privilege.
// Go reports one as ModeIrregular, not ModeSymlink, and it is the kind of link
// a Windows media folder is most likely to hold. The junction is removed
// before the temp folder around it, so cleaning up cannot follow it.
func tryJunction(t *testing.T, target, link string) bool {
	t.Helper()
	if runtime.GOOS != "windows" {
		return false
	}
	if out, err := exec.Command("cmd", "/c", "mklink", "/J", link, target).CombinedOutput(); err != nil {
		t.Logf("could not make a junction, so this case is skipped: %v %s", err, out)
		return false
	}
	t.Cleanup(func() { _ = os.Remove(link) })
	return true
}

func TestResolveMediaFileOpensAVerifiedAudioFileForReadingOnly(t *testing.T) {
	root := t.TempDir()
	writeMediaFiles(t, root, map[string]string{"audiobooks/Dark Matter/Dark Matter (1).mp3": "abcdefgh"})
	onDisk := filepath.Join(root, "audiobooks", "Dark Matter", "Dark Matter (1).mp3")
	file, err := ResolveMediaFile(mediaRoots(root), "storyteller", "/library/audiobooks/Dark Matter/Dark Matter (1).mp3")
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()

	info, err := os.Stat(onDisk)
	if err != nil {
		t.Fatal(err)
	}
	if file.Size != 8 || !file.ModTime.Equal(info.ModTime()) {
		t.Fatalf("size %d, modified %v, want 8 and %v", file.Size, file.ModTime, info.ModTime())
	}
	if _, err := file.Seek(2, io.SeekStart); err != nil {
		t.Fatal(err)
	}
	rest, err := io.ReadAll(file)
	if err != nil || string(rest) != "cdefgh" {
		t.Fatalf("read after seek = %q, %v", rest, err)
	}

	// The handle is O_RDONLY at the operating system, not only hidden behind an
	// interface: even a caller that digs the file out cannot change it.
	if writer, ok := file.ReadOnlyFile.(io.Writer); ok {
		if _, err := writer.Write([]byte("XX")); err == nil {
			t.Fatal("the handle accepted a write")
		}
	}
	after, err := os.ReadFile(onDisk)
	if err != nil || string(after) != "abcdefgh" {
		t.Fatalf("file on disk = %q, %v", after, err)
	}
}

func TestResolveMediaFileAcceptsRealWorldNames(t *testing.T) {
	root := t.TempDir()
	names := []string{
		"Dark Matter.mp3",
		"Dark Matter (1).mp3",
		"UPPER CASE.MP3",
		"Mistborn (1).m4b",
		"track 01 - a [b] & c, d's + e #1.m4a",
		"100% Pure.mp3",
		"Track%2001.mp3", // literal characters, not an escape for anyone here
		"Pâté – Ünïcode 02.mp3",
		"פרק 3 - המפץ הגדול.mp3",
		"a.b.c.opus",
		"one.flac", "two.wav", "three.ogg", "four.aac", "five.aiff", "six.weba", "seven.oga", "eight.mp4",
	}
	files := map[string]string{}
	for _, name := range names {
		files["audiobooks/Book/"+name] = "x"
	}
	writeMediaFiles(t, root, files)
	for _, name := range names {
		file, err := ResolveMediaFile(mediaRoots(root), "storyteller", "/library/audiobooks/Book/"+name)
		if err != nil {
			t.Errorf("%q refused: %v", name, err)
			continue
		}
		file.Close()
	}
}

func TestResolveMediaFileRefusesWhatCouldClimbAliasOrIsNotAudio(t *testing.T) {
	root := t.TempDir()
	writeMediaFiles(t, root, map[string]string{
		"Book/a.mp3":     "audio",
		"Book/cover.jpg": "image", "Book/notes.txt": "text", "Book/a.mp3.exe": "exe", "Book/noextension": "x",
		"Book/book.zip": "zip", "Book/list.m3u8": "#EXTM3U", "Book/a.cue": "cue",
		"secret.mp3": "outside the book folder but inside the root",
	})
	if err := os.MkdirAll(filepath.Join(root, "Book", "folder.mp3"), 0o700); err != nil {
		t.Fatal(err)
	}
	roots := mediaRoots(root)

	for _, test := range []struct {
		name   string
		remote string
	}{
		{"parent part", "/library/Book/../secret.mp3"},
		{"dot part", "/library/Book/./a.mp3"},
		{"empty part", "/library/Book//a.mp3"},
		{"trailing slash", "/library/Book/a.mp3/"},
		{"not absolute", "library/Book/a.mp3"},
		{"drive letter", "C:/Windows/a.mp3"},
		{"drive letter with backslashes", `C:\Windows\a.mp3`},
		{"drive letter as a part", "/library/C:/a.mp3"},
		{"alternate data stream", "/library/Book/a.mp3:stream"},
		{"alternate data stream by type", "/library/Book/a.mp3::$DATA"},
		{"backslashes that climb", `/library/Book\..\secret.mp3`},
		{"NUL byte", "/library/Book/a.mp3\x00.txt"},
		{"percent-encoded parent", "/library/Book/%2e%2e/secret.mp3"},
		{"upper-case percent-encoded parent", "/library/Book/%2E%2E/secret.mp3"},
		{"twice-encoded parent", "/library/Book/%252e%252e/secret.mp3"},
		{"percent-encoded separator", "/library/Book/..%2fsecret.mp3"},
		{"percent-encoded drive", "/library/Book/c%3a.mp3"},
		{"trailing dot", "/library/Book/a.mp3."},
		{"trailing space", "/library/Book/a.mp3 "},
		{"trailing dot on a folder", "/library/Book./a.mp3"},
		{"device name", "/library/Book/CON"},
		{"device name with an audio extension", "/library/Book/NUL.mp3"},
		{"device name in lower case", "/library/Book/con.mp3"},
		{"numbered device name", "/library/Book/COM1.mp3"},
		{"numbered device name above nine", "/library/Book/lpt9.m4b"},
		{"device name as a folder", "/library/AUX/a.mp3"},
		{"superscript device name", "/library/Book/COM\u00b9.mp3"},
		{"console handle name", "/library/Book/CONIN$.mp3"},
		{"wildcard", "/library/Book/*.mp3"},
		{"question mark", "/library/Book/a?.mp3"},
		{"angle bracket", "/library/Book/a<b.mp3"},
		{"pipe", "/library/Book/a|b.mp3"},
		{"control character", "/library/Book/a\x07.mp3"},
		{"an image", "/library/Book/cover.jpg"},
		{"text", "/library/Book/notes.txt"},
		{"an audio name that is really an executable", "/library/Book/a.mp3.exe"},
		{"no extension", "/library/Book/noextension"},
		{"an archive", "/library/Book/book.zip"},
		{"a playlist, which a demuxer would follow", "/library/Book/list.m3u8"},
		{"a cue sheet", "/library/Book/a.cue"},
		{"a folder with an audio name", "/library/Book/folder.mp3"},
	} {
		t.Run(test.name, func(t *testing.T) {
			file, err := ResolveMediaFile(roots, "storyteller", test.remote)
			if err == nil {
				file.Close()
				t.Fatalf("%q was accepted", test.remote)
			}
			if got := failureOf(t, err); got != MediaRefused {
				t.Fatalf("%q = %v (%v), want a refusal", test.remote, got, err)
			}
		})
	}
}

func TestResolveMediaFileSaysWhyAFileCannotBeFound(t *testing.T) {
	root := t.TempDir()
	writeMediaFiles(t, root, map[string]string{"Book/a.mp3": "audio"})
	driveRoot := filepath.VolumeName(root) + string(os.PathSeparator)

	for _, test := range []struct {
		name    string
		roots   []config.MediaRemovalRoot
		service string
		remote  string
		want    MediaFailure
	}{
		{"a file that is not there", mediaRoots(root), "storyteller", "/library/Book/missing.mp3", MediaMissing},
		{"a folder that is not there", mediaRoots(root), "storyteller", "/library/Nope/a.mp3", MediaMissing},
		{"a path no mapping covers", mediaRoots(root), "storyteller", "/other/Book/a.mp3", MediaUnmapped},
		{"a mapping that only shares a prefix", mediaRoots(root), "storyteller", "/library2/Book/a.mp3", MediaUnmapped},
		{"another service's mapping", mediaRoots(root), "kavita", "/library/Book/a.mp3", MediaUnmapped},
		{"no mappings at all", nil, "storyteller", "/library/Book/a.mp3", MediaUnmapped},
		{"a mapping that is not absolute", []config.MediaRemovalRoot{{Service: "storyteller", Remote: "/library", Local: "relative"}}, "storyteller", "/library/Book/a.mp3", MediaUnmapped},
		{"a mapping that is a drive root", []config.MediaRemovalRoot{{Service: "storyteller", Remote: "/library", Local: driveRoot}}, "storyteller", "/library/Book/a.mp3", MediaUnmapped},
		{"a mapped folder that is gone", []config.MediaRemovalRoot{{Service: "storyteller", Remote: "/library", Local: filepath.Join(root, "gone")}}, "storyteller", "/library/Book/a.mp3", MediaUnmapped},
	} {
		t.Run(test.name, func(t *testing.T) {
			file, err := ResolveMediaFile(test.roots, test.service, test.remote)
			if err == nil {
				file.Close()
				t.Fatal("accepted")
			}
			if got := failureOf(t, err); got != test.want {
				t.Fatalf("failure = %v (%v), want %v", got, err, test.want)
			}
		})
	}
}

func TestResolveMediaFilePrefersTheLongestMappedPrefix(t *testing.T) {
	general, specific := t.TempDir(), t.TempDir()
	writeMediaFiles(t, general, map[string]string{"audiobooks/Book/a.mp3": "from the general root"})
	writeMediaFiles(t, specific, map[string]string{"Book/a.mp3": "from the specific root"})
	roots := []config.MediaRemovalRoot{
		{Service: "storyteller", Remote: "/library", Local: general},
		{Service: "storyteller", Remote: "/library/audiobooks", Local: specific},
	}
	file, err := ResolveMediaFile(roots, "storyteller", "/library/audiobooks/Book/a.mp3")
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	body, _ := io.ReadAll(file)
	if string(body) != "from the specific root" {
		t.Fatalf("read %q", body)
	}
}

func TestResolveMediaFileRefusesLinksOfEveryKind(t *testing.T) {
	root, outside := t.TempDir(), t.TempDir()
	writeMediaFiles(t, root, map[string]string{"Book/a.mp3": "audio", "Real/b.mp3": "audio"})
	writeMediaFiles(t, outside, map[string]string{"secret.mp3": "outside"})
	roots := mediaRoots(root)
	made := 0

	refuse := func(remote string) {
		t.Helper()
		file, err := ResolveMediaFile(roots, "storyteller", remote)
		if err == nil {
			file.Close()
			t.Fatalf("%s was followed", remote)
		}
		if got := failureOf(t, err); got != MediaRefused {
			t.Fatalf("%s = %v (%v), want a refusal", remote, got, err)
		}
	}

	// A link to a folder outside the root, and one that points back inside it:
	// the second would let an alias stand for another book.
	if tryJunction(t, outside, filepath.Join(root, "OutsideJunction")) {
		made++
		refuse("/library/OutsideJunction/secret.mp3")
	}
	if tryJunction(t, filepath.Join(root, "Real"), filepath.Join(root, "Alias")) {
		made++
		refuse("/library/Alias/b.mp3")
	}
	if trySymlink(t, filepath.Join(outside, "secret.mp3"), filepath.Join(root, "Book", "link.mp3")) {
		made++
		refuse("/library/Book/link.mp3")
	}
	if trySymlink(t, outside, filepath.Join(root, "Linked")) {
		made++
		refuse("/library/Linked/secret.mp3")
	}

	if made == 0 {
		t.Skip("neither a junction nor a symlink could be made here")
	}
}

// The file is checked by path and then opened by path; in between, someone who
// can write to the media folder could put something else there. The handle
// must be the very file that was checked.
func TestResolveMediaFileOpensTheFileThatWasChecked(t *testing.T) {
	newBook := func(t *testing.T) (root string, roots []config.MediaRemovalRoot) {
		t.Helper()
		root = t.TempDir()
		writeMediaFiles(t, root, map[string]string{"Book/a.mp3": "the checked file"})
		return root, mediaRoots(root)
	}
	walk := func(t *testing.T, roots []config.MediaRemovalRoot) walked {
		t.Helper()
		checked, fail := walkMediaPath(roots, "storyteller", "/library/Book/a.mp3", walkRules{strictNames: true})
		if fail != walkOK {
			t.Fatalf("walk failed: %v", fail)
		}
		return checked
	}
	// Written first and renamed over, so the new file exists beside the old one
	// and the two cannot share an identity by reuse.
	swapWithAnotherFile := func(t *testing.T, root string) error {
		t.Helper()
		replacement := filepath.Join(root, "Book", "replacement.tmp")
		if err := os.WriteFile(replacement, []byte("a different file"), 0o600); err != nil {
			t.Fatal(err)
		}
		return os.Rename(replacement, filepath.Join(root, "Book", "a.mp3"))
	}
	mustRefuse := func(t *testing.T, checked walked) {
		t.Helper()
		file, err := openWalked(checked)
		if err == nil {
			file.Close()
			t.Fatal("a file swapped in after the check was opened")
		}
		if got := failureOf(t, err); got != MediaRefused {
			t.Fatalf("failure = %v (%v), want a refusal", got, err)
		}
	}

	t.Run("replaced by another file", func(t *testing.T) {
		root, roots := newBook(t)
		checked := walk(t, roots)
		if err := swapWithAnotherFile(t, root); err != nil {
			t.Fatal(err)
		}
		mustRefuse(t, checked)
	})

	t.Run("replaced by a link", func(t *testing.T) {
		root, roots := newBook(t)
		checked := walk(t, roots)
		outside := t.TempDir()
		writeMediaFiles(t, outside, map[string]string{"other.mp3": "something else"})
		target := filepath.Join(root, "Book", "a.mp3")
		if err := os.Remove(target); err != nil {
			t.Fatal(err)
		}
		if !trySymlink(t, filepath.Join(outside, "other.mp3"), target) {
			t.Skip("no symlink privilege")
		}
		mustRefuse(t, checked)
	})

	t.Run("a folder on the way replaced by a junction", func(t *testing.T) {
		root, roots := newBook(t)
		checked := walk(t, roots)
		outside := t.TempDir()
		writeMediaFiles(t, outside, map[string]string{"a.mp3": "another file with the same name"})
		if err := os.Rename(filepath.Join(root, "Book"), filepath.Join(root, "Book.moved")); err != nil {
			t.Fatal(err)
		}
		if !tryJunction(t, outside, filepath.Join(root, "Book")) {
			t.Skip("no junction here")
		}
		mustRefuse(t, checked)
	})

	t.Run("replaced after it was opened", func(t *testing.T) {
		root, roots := newBook(t)
		file, err := ResolveMediaFile(roots, "storyteller", "/library/Book/a.mp3")
		if err != nil {
			t.Fatal(err)
		}
		defer file.Close()
		// Windows keeps a file that is open from being replaced; elsewhere the
		// replacement works and the handle keeps the old file. Either way the
		// bytes served from the handle are the bytes that were checked.
		if err := swapWithAnotherFile(t, root); err != nil {
			t.Logf("the open file could not be replaced, as on Windows: %v", err)
		}
		body, err := io.ReadAll(file)
		if err != nil || string(body) != "the checked file" {
			t.Fatalf("handle read %q, %v", body, err)
		}
	})
}

func TestListMediaFolderNamesOnlyTheAudioFilesInIt(t *testing.T) {
	root := t.TempDir()
	writeMediaFiles(t, root, map[string]string{
		"Book/b.mp3": "x", "Book/a.m4b": "x", "Book/C.MP3": "x", "Book/cover.jpg": "x", "Book/notes.txt": "x",
		"Book/nested/deep.mp3": "x", "Other/z.mp3": "x",
	})
	if err := os.MkdirAll(filepath.Join(root, "Book", "folder.mp3"), 0o700); err != nil {
		t.Fatal(err)
	}
	names, err := ListMediaFolder(mediaRoots(root), "storyteller", "/library/Book")
	if err != nil {
		t.Fatal(err)
	}
	if want := []string{"C.MP3", "a.m4b", "b.mp3"}; !reflect.DeepEqual(names, want) {
		t.Fatalf("names = %v, want %v", names, want)
	}

	for _, test := range []struct {
		name   string
		remote string
		want   MediaFailure
	}{
		{"a folder that is not there", "/library/Nope", MediaMissing},
		{"a file, not a folder", "/library/Book/b.mp3", MediaRefused},
		{"a path that climbs", "/library/Book/..", MediaRefused},
		{"a path no mapping covers", "/other/Book", MediaUnmapped},
	} {
		t.Run(test.name, func(t *testing.T) {
			_, err := ListMediaFolder(mediaRoots(root), "storyteller", test.remote)
			if err == nil {
				t.Fatal("accepted")
			}
			if got := failureOf(t, err); got != test.want {
				t.Fatalf("failure = %v (%v), want %v", got, err, test.want)
			}
		})
	}
}

func TestListMediaFolderSkipsLinksInsideIt(t *testing.T) {
	root, outside := t.TempDir(), t.TempDir()
	writeMediaFiles(t, root, map[string]string{"Book/real.mp3": "x"})
	writeMediaFiles(t, outside, map[string]string{"secret.mp3": "x"})
	made := tryJunction(t, outside, filepath.Join(root, "Book", "junction.mp3"))
	made = trySymlink(t, filepath.Join(outside, "secret.mp3"), filepath.Join(root, "Book", "link.mp3")) || made
	if !made {
		t.Skip("neither a junction nor a symlink could be made here")
	}
	names, err := ListMediaFolder(mediaRoots(root), "storyteller", "/library/Book")
	if err != nil {
		t.Fatal(err)
	}
	if want := []string{"real.mp3"}; !reflect.DeepEqual(names, want) {
		t.Fatalf("names = %v, want %v", names, want)
	}
}

func TestAudioKindOfIsTheOneListOfWhatTheHubServes(t *testing.T) {
	for name, want := range map[string]AudioKind{
		"a.mp3":            {Ext: ".mp3", MIME: "audio/mpeg", Demuxer: "mp3"},
		"A.MP3":            {Ext: ".mp3", MIME: "audio/mpeg", Demuxer: "mp3"},
		"a.m4a":            {Ext: ".m4a", MIME: "audio/mp4", Demuxer: "mov"},
		"a.m4b":            {Ext: ".m4b", MIME: "audio/mp4", Demuxer: "mov"},
		"a.mp4":            {Ext: ".mp4", MIME: "audio/mp4", Demuxer: "mov"},
		"/library/b/c.M4B": {Ext: ".m4b", MIME: "audio/mp4", Demuxer: "mov"},
		"a.aac":            {Ext: ".aac", MIME: "audio/aac", Demuxer: "aac"},
		"a.ogg":            {Ext: ".ogg", MIME: "audio/ogg", Demuxer: "ogg"},
		"a.oga":            {Ext: ".oga", MIME: "audio/ogg", Demuxer: "ogg"},
		"a.opus":           {Ext: ".opus", MIME: "audio/opus", Demuxer: "ogg"},
		"a.flac":           {Ext: ".flac", MIME: "audio/flac", Demuxer: "flac"},
		"a.wav":            {Ext: ".wav", MIME: "audio/wav", Demuxer: "wav"},
		"a.aiff":           {Ext: ".aiff", MIME: "audio/aiff", Demuxer: "aiff"},
		"a.weba":           {Ext: ".weba", MIME: "audio/webm", Demuxer: "matroska,webm"},
	} {
		got, ok := AudioKindOf(name)
		if !ok || got != want {
			t.Errorf("AudioKindOf(%q) = %+v, %v, want %+v", name, got, ok, want)
		}
	}
	for _, name := range []string{"", "a", "a.", ".", "a.jpg", "a.mp3.exe", "a.m3u8", "a.zip", "a.txt", "a.cue", "mp3", "dir.mp3/file"} {
		if kind, ok := AudioKindOf(name); ok {
			t.Errorf("AudioKindOf(%q) = %+v, want none", name, kind)
		}
	}
}

// A read-along edition is an EPUB, and it is read through the same walk: only
// the list of what may be opened differs.
func TestResolveEPUBFileOpensOnlyEPUBsThroughTheSameWalk(t *testing.T) {
	root := t.TempDir()
	writeMediaFiles(t, root, map[string]string{
		"Dark Matter/aligned.epub": "epub", "Dark Matter/UPPER.EPUB": "epub", "Dark Matter/a.mp3": "audio",
		"Dark Matter/a.epub.exe": "exe", "Dark Matter/notes.txt": "text",
	})
	if err := os.MkdirAll(filepath.Join(root, "Dark Matter", "folder.epub"), 0o700); err != nil {
		t.Fatal(err)
	}
	roots := mediaRoots(root)

	for _, name := range []string{"aligned.epub", "UPPER.EPUB"} {
		file, err := ResolveEPUBFile(roots, "storyteller", "/library/Dark Matter/"+name)
		if err != nil {
			t.Errorf("%s refused: %v", name, err)
			continue
		}
		file.Close()
	}
	for _, remote := range []string{
		"/library/Dark Matter/a.mp3", "/library/Dark Matter/a.epub.exe", "/library/Dark Matter/notes.txt",
		"/library/Dark Matter/folder.epub", "/library/Dark Matter/aligned.epub:stream", "/library/Dark Matter/../Dark Matter/aligned.epub",
		"/library/Dark Matter/NUL.epub", "/library/Dark Matter/aligned.epub.",
	} {
		file, err := ResolveEPUBFile(roots, "storyteller", remote)
		if err == nil {
			file.Close()
			t.Errorf("%s was accepted", remote)
			continue
		}
		if got := failureOf(t, err); got != MediaRefused {
			t.Errorf("%s = %v (%v), want a refusal", remote, got, err)
		}
	}
	// And an audio resolver does not take an EPUB.
	if file, err := ResolveMediaFile(roots, "storyteller", "/library/Dark Matter/aligned.epub"); err == nil {
		file.Close()
		t.Error("the audio twin opened an EPUB")
	}
	for _, test := range []struct {
		remote string
		want   MediaFailure
	}{{"/library/Dark Matter/gone.epub", MediaMissing}, {"/other/x.epub", MediaUnmapped}} {
		file, err := ResolveEPUBFile(roots, "storyteller", test.remote)
		if err == nil {
			file.Close()
			t.Fatalf("%s was accepted", test.remote)
		}
		if got := failureOf(t, err); got != test.want {
			t.Errorf("%s = %v, want %v", test.remote, got, test.want)
		}
	}
}

// A zip keeps its directory at the end of the file, so a verified file can be
// read at an offset: a read-along edition's SMIL is read without reading its
// audio.
func TestMediaFileCanBeReadAtAnOffsetAndStillOnlyRead(t *testing.T) {
	root := t.TempDir()
	writeMediaFiles(t, root, map[string]string{"Book/a.mp3": "0123456789"})
	file, err := ResolveMediaFile(mediaRoots(root), "storyteller", "/library/Book/a.mp3")
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	got := make([]byte, 4)
	if n, err := file.ReadAt(got, 3); err != nil || n != 4 || string(got) != "3456" {
		t.Fatalf("ReadAt = %q, %d, %v", got, n, err)
	}
	// Reading at an offset leaves the sequential position where it was.
	rest, err := io.ReadAll(file)
	if err != nil || string(rest) != "0123456789" {
		t.Fatalf("sequential read after ReadAt = %q, %v", rest, err)
	}
	if writer, ok := file.ReadOnlyFile.(io.Writer); ok {
		if _, err := writer.Write([]byte("XX")); err == nil {
			t.Fatal("the handle accepted a write")
		}
	}
}
